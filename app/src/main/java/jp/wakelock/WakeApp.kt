package jp.wakelock

import android.app.*
import android.content.*
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WakeApp : Application() {
    val controller by lazy { WakeController(this) }
}

class WakeController(private val app: Context) {
    private val store = WakeStore(app)
    val states = store.states
    val alerts = Alerts(app)
    private val manager = app.getSystemService(AlarmManager::class.java)
    private val lock = Mutex()
    val exact get() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()

    private val delivery: PendingIntent get() = PendingIntent.getBroadcast(
        app, 0, Intent(app, AlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun setClock(time: Long?) {
        if (time == null || !exact) {
            manager.cancel(delivery)
            return
        }
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(time, alerts.openApp), delivery)
        } catch (_: SecurityException) {
            if (exact) throw InputError("予約に失敗しました。アラームの権限を確認してください。")
            // Revocation races with canScheduleExactAlarms. The saved date survives the grant.
        }
    }

    suspend fun change(transform: (WakeState) -> WakeState) = lock.withLock {
        applyState(store.change(transform))
    }

    suspend fun deliver() = lock.withLock {
        applyState(store.change { it.fire(System.currentTimeMillis()) })
    }

    suspend fun restore(fromBroadcast: Boolean) = lock.withLock {
        val current = states.first()
        if (fromBroadcast && current.phase.playsAlarm) {
            // Android 15+ prohibits starting a mediaPlayback FGS from BOOT_COMPLETED.
            val resumeAt = System.currentTimeMillis() + 1000
            if (exact) setClock(minOf(current.nextAlarm ?: resumeAt, resumeAt))
            else alerts.ringFallback(current.phase == Phase.CHECKING)
        } else if (fromBroadcast) {
            setClock(current.nextAlarm)
        } else {
            applyState(store.change { it.fire(System.currentTimeMillis()) })
        }
    }

    private fun applyState(state: WakeState) {
        setClock(state.nextAlarm)
        if (state.phase.playsAlarm) {
            val checking = state.phase == Phase.CHECKING
            val intent = Intent(app, RingService::class.java).putExtra(RingService.CHECKING, checking)
            try {
                app.startForegroundService(intent)
            } catch (_: IllegalStateException) {
                alerts.ringFallback(checking)
            } catch (_: SecurityException) {
                alerts.ringFallback(checking)
            }
        } else {
            app.stopService(Intent(app, RingService::class.java))
            alerts.clearRinging()
        }
    }
}

abstract class WakeReceiver : BroadcastReceiver() {
    abstract suspend fun receive(controller: WakeController)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLock:delivery")
        wake.acquire(10_000)
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                receive((context.applicationContext as WakeApp).controller)
            } finally {
                if (wake.isHeld) wake.release()
                pending.finish()
            }
        }
    }
}

class AlarmReceiver : WakeReceiver() {
    override suspend fun receive(controller: WakeController) = controller.deliver()
}
class RestoreReceiver : WakeReceiver() {
    override suspend fun receive(controller: WakeController) = controller.restore(fromBroadcast = true)
}
