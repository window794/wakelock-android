package jp.wakelock

import android.app.Service
import android.content.Intent
import android.media.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class RingService : Service() {
    private val controller get() = (application as WakeApp).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var vibrating = false
    private var checking = false
    private var latestStart = 0
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        // On a sticky restart, show the quiet recheck notification until DataStore is loaded.
        if (!showForeground(intent?.getBooleanExtra(CHECKING, false) ?: true)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            val phase = controller.states.first().phase
            if (startId != latestStart) return@launch
            val recheck = phase == Phase.CHECKING
            if (phase.playsAlarm && (checking == recheck || showForeground(recheck))) startRinging()
            else stopSelf(startId)
        }
        return START_STICKY
    }

    private fun showForeground(recheck: Boolean): Boolean {
        checking = recheck
        try {
            if (recheck) startForeground(Alerts.CHECK_ID, controller.alerts.check())
            else startForeground(Alerts.RING_ID, controller.alerts.ring())
            return true
        } catch (_: IllegalStateException) {
            controller.alerts.ringFallback(recheck)
        } catch (_: SecurityException) {
            controller.alerts.ringFallback(recheck)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun startRinging() {
        if (!vibrating && vibrator.hasVibrator()) {
            val pulse = VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(pulse, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vibrator.vibrate(pulse, attributes)
            }
            vibrating = true
        }
        if (player != null) return
        val audio = MediaPlayer()
        try {
            audio.setAudioAttributes(attributes)
            audio.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            val hasSystemTone = uri != null && runCatching { audio.setDataSource(this, uri) }.isSuccess
            if (!hasSystemTone) {
                resources.openRawResourceFd(R.raw.alarm).use {
                    audio.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            audio.isLooping = true
            audio.setOnPreparedListener { it.start() }
            audio.setOnErrorListener { failed, _, _ ->
                failed.release()
                player = null
                controller.alerts.ringFallback(checking)
                true
            }
            player = audio
            audio.prepareAsync()
            controller.alerts.clearBackup()
        } catch (_: Exception) {
            audio.release()
            player = null
            controller.alerts.ringFallback(checking)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        player = null
        vibrator.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        const val CHECKING = "jp.wakelock.extra.CHECKING"
    }
}
