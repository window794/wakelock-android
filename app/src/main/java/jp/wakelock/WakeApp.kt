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

private const val TICK = "jp.wakelock.PROTOCOL_V2"
private const val RUN = "run"
private const val PHASE = "phase"
private const val DEADLINE = "deadline"

class WakeController(private val app: Context) {
    private val store = WakeStore(app)
    val states = store.states
    val alerts = Alerts(app)
    private val manager = app.getSystemService(AlarmManager::class.java)
    private val lock = Mutex()
    val exact get() = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()

    private fun cancel(code: Int, intent: Intent) {
        PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let {
            manager.cancel(it)
            it.cancel()
        }
    }

    private fun setClock(state: WakeState, at: Long?) {
        val intent = Intent(app, AlarmReceiver::class.java).setAction(TICK)
        if (at == null || !exact) {
            cancel(2, intent)
            return
        }
        val event = state.event
        intent.putExtra(RUN, event.step.generation).putExtra(PHASE, event.step.phase.name).putExtra(DEADLINE, event.deadline)
        val delivery = PendingIntent.getBroadcast(app, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, alerts.openApp), delivery)
        } catch (e: SecurityException) {
            if (exact) throw e
            // If special access was revoked between check and set, the saved deadline is retried on grant.
        }
    }

    suspend fun change(transform: (WakeState) -> WakeState) = lock.withLock {
        applyState(store.change(transform))
    }

    suspend fun deliver(event: AlarmEvent) = lock.withLock {
        if (states.first().event != event) return@withLock
        applyState(store.change { it.fire(event, System.currentTimeMillis()) })
    }

    suspend fun restore(fromBroadcast: Boolean) = lock.withLock {
        cancel(0, Intent(app, AlarmReceiver::class.java)) // v1 PendingIntent had no action.
        val now = System.currentTimeMillis()
        val state = store.change { if (fromBroadcast) it else it.fire(it.event, now) }
        if (fromBroadcast && state.phase.ringing) {
            // A boot broadcast cannot start a mediaPlayback FGS directly.
            setClock(state, state.restoreAt(now))
            if (!exact) alerts.ringFallback(state.phase)
        } else {
            applyState(state)
        }
    }

    private fun applyState(state: WakeState) {
        setClock(state, state.nextAlarm)
        if (state.phase.ringing) {
            val intent = Intent(app, RingService::class.java).putExtra(PHASE, state.phase.name)
                .putExtra("escalated", state.escalated)
            try {
                app.startForegroundService(intent)
            } catch (_: IllegalStateException) {
                alerts.ringFallback(state.phase)
            } catch (_: SecurityException) {
                alerts.ringFallback(state.phase)
            }
        } else {
            app.stopService(Intent(app, RingService::class.java))
            alerts.clearRinging()
        }
    }
}

abstract class WakeReceiver : BroadcastReceiver() {
    abstract suspend fun receive(controller: WakeController, intent: Intent)

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val power = context.getSystemService(PowerManager::class.java)
        val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLock:delivery")
        wake.acquire(10_000)
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                receive((context.applicationContext as WakeApp).controller, intent)
            } finally {
                if (wake.isHeld) wake.release()
                pending.finish()
            }
        }
    }
}

class AlarmReceiver : WakeReceiver() {
    override suspend fun receive(controller: WakeController, intent: Intent) {
        if (intent.action != TICK || !intent.hasExtra(RUN) || !intent.hasExtra(DEADLINE)) return
        val phase = Phase.entries.find { it.name == intent.getStringExtra(PHASE) } ?: return
        controller.deliver(AlarmEvent(StepId(intent.getLongExtra(RUN, -1), phase), intent.getLongExtra(DEADLINE, -1)))
    }
}
class RestoreReceiver : WakeReceiver() {
    override suspend fun receive(controller: WakeController, intent: Intent) = controller.restore(fromBroadcast = true)
}
