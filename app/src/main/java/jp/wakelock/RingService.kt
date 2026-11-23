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
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var player: MediaPlayer? = null
    private var vibrationEscalated: Boolean? = null
    private var phase: Phase? = null
    private var latestStart = 0
    private var focusRequested = false
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(true).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (focusRequested) when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> startAudio()
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        stopAudio()
                        abandonFocus()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> stopAudio()
                }
            }, Handler(Looper.getMainLooper())).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val hint = Phase.entries.find { it.name == intent?.getStringExtra("phase") }
        val strong = intent?.getBooleanExtra("escalated", false) == true
        if (!foreground(hint, strong)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            val state = controller.states.first()
            if (startId != latestStart) return@launch
            if (!state.phase.ringing || ((hint != state.phase || strong != state.escalated) &&
                    !foreground(state.phase, state.escalated))) {
                stopSelf(startId)
                return@launch
            }
            phase = state.phase
            vibrate(state.escalated)
            if (!focusRequested) {
                focusRequested = true
                when (audioManager.requestAudioFocus(focusRequest)) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> startAudio()
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> Unit // GAIN callback starts playback.
                    else -> focusRequested = false // Keep the visual and vibration cues.
                }
            }
        }
        return START_STICKY
    }

    private fun foreground(value: Phase?, escalated: Boolean): Boolean {
        try {
            val id = if ((value?.step ?: 0) >= 3) Alerts.CHECK_ID else Alerts.RING_ID
            startForeground(id, controller.alerts.alarm(value, escalated))
            return true
        } catch (_: IllegalStateException) {
            value?.let(controller.alerts::ringFallback)
        } catch (_: SecurityException) {
            value?.let(controller.alerts::ringFallback)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun vibrate(strong: Boolean) {
        if (vibrationEscalated == strong || !vibrator.hasVibrator()) return
        val pattern = if (strong) longArrayOf(0, 650, 160, 650, 200) else longArrayOf(0, 600, 600)
        val pulse = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= 33) vibrator.vibrate(pulse, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        else vibrator.vibrate(pulse, attributes)
        vibrationEscalated = strong
    }

    private fun startAudio() {
        if (player != null || phase == null) return
        val audio = MediaPlayer()
        player = audio
        try {
            audio.setAudioAttributes(attributes)
            audio.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            if (uri == null || runCatching { audio.setDataSource(this, uri) }.isFailure) {
                resources.openRawResourceFd(R.raw.alarm).use {
                    audio.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
            }
            audio.isLooping = true
            audio.setOnPreparedListener { if (player === it) it.start() }
            audio.setOnErrorListener { failed, _, _ ->
                if (player === failed) playbackFailed()
                true
            }
            audio.prepareAsync()
            controller.alerts.clearBackup()
        } catch (_: Exception) {
            playbackFailed()
        }
    }

    private fun playbackFailed() {
        stopAudio()
        abandonFocus()
        phase?.let(controller.alerts::ringFallback)
    }

    private fun stopAudio() {
        val previous = player
        player = null
        previous?.release()
    }

    private fun abandonFocus() {
        if (focusRequested) {
            focusRequested = false
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        phase = null
        stopAudio()
        abandonFocus()
        vibrator.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
