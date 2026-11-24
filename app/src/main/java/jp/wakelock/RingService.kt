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
    private val attributes = alarmAttributes
    private val player by lazy { alarmPlayer(this, scope) }
    private var selectedUri: String? = null
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
        val buzz = intent?.getBooleanExtra("vibrate", true) != false
        if (!foreground(hint, strong, buzz)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            val state = controller.states.first()
            val sound = state.owner?.sound ?: try {
                (application as WakeApp).soundStore.sounds.first()
            } catch (_: java.io.IOException) {
                AlarmSound()
            }
            if (startId != latestStart) return@launch
            if (!state.phase.ringing || ((hint != state.phase || strong != state.escalated || buzz != (state.owner?.vibrate != false)) &&
                    !foreground(state.phase, state.escalated, state.owner?.vibrate != false))) {
                stopSelf(startId)
                return@launch
            }
            phase = state.phase
            if (selectedUri != sound.uri && focusRequested) {
                stopAudio()
                abandonFocus()
            }
            selectedUri = sound.uri
            if (state.owner?.vibrate != false) vibrate(state.escalated)
            else {
                vibrator.cancel()
                vibrationEscalated = null
            }
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

    private fun foreground(value: Phase?, escalated: Boolean, vibrate: Boolean): Boolean {
        try {
            val id = if ((value?.step ?: 0) >= 3) Alerts.CHECK_ID else Alerts.RING_ID
            startForeground(id, controller.alerts.alarm(value, escalated, vibrate))
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
        if (phase == null) return
        controller.alerts.clearBackup()
        player.start(selectedUri) {
            abandonFocus()
            phase?.let(controller.alerts::ringFallback)
        }
    }

    private fun stopAudio() {
        player.stop()
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
