package jp.wakelock

import android.app.*
import android.content.*
import android.os.Build
import android.os.PowerManager
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WakeApp : Application() {
    internal val history by lazy { HistoryStore(this) }
    override fun onCreate() {
        super.onCreate()
        history.start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    val controller by lazy { WakeController(this) }
    internal val soundStore by lazy { AlarmSoundStore(this) }
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
        val event = requireNotNull(state.clockEvent)
        intent.putExtra("alarmId", event.alarmId)
        intent.putExtra(RUN, event.step.generation).putExtra(PHASE, event.step.phase.name).putExtra(DEADLINE, event.deadline)
        val delivery = PendingIntent.getBroadcast(app, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, alerts.openApp), delivery)
        } catch (e: SecurityException) {
            if (exact) throw e
            // If special access was revoked between check and set, the saved deadline is retried on grant.
        }
    }

    // If the process dies between DataStore commit and OS side effects, this private delivery
    // reconciles the committed state. The normal path removes it immediately.
    private suspend fun reconcileOnCrash(operation: suspend () -> Unit) {
        val intent = Intent(app, RestoreReceiver::class.java).setAction("jp.wakelock.RECONCILE")
        val recovery = PendingIntent.getBroadcast(app, 3, intent, PendingIntent.FLAG_IMMUTABLE)
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 60_000, recovery)
        try {
            operation()
        } catch (e: InputError) {
            manager.cancel(recovery)
            throw e
        }
        manager.cancel(recovery)
    }

    suspend fun change(transform: (WakeState) -> WakeState) = lock.withLock {
        reconcileOnCrash { applyState(store.change { transform(it).finishOwner(System.currentTimeMillis(), ZoneId.systemDefault()) }) }
    }

    internal suspend fun addAlarm(id: String, minute: Int) = lock.withLock {
        applyState(store.edit { current, prefs ->
            val card = ClockAlarm(id = id, minute = minute, sound = SoundRecord.read(prefs),
                enabled = Place.entries.all(current::registered))
            current.saveCard(card, System.currentTimeMillis(), ZoneId.systemDefault())
        })
    }

    // Registration shares the protocol lock but does not need to reschedule the alarm.
    internal suspend fun issueQr(place: Place, expected: String) = lock.withLock {
        store.change { it.issueQr(place, expected) }
    }

    suspend fun deliver(event: AlarmEvent) = lock.withLock {
        if (states.first().clockEvent != event) return@withLock
        reconcileOnCrash { applyState(store.change { it.deliverClock(event, System.currentTimeMillis()) }) }
    }

    suspend fun restore(fromBroadcast: Boolean, clockChanged: Boolean = false) = lock.withLock {
        cancel(0, Intent(app, AlarmReceiver::class.java)) // v1 PendingIntent had no action.
        val now = System.currentTimeMillis()
        reconcileOnCrash {
            val state = store.change {
                val current = it.finishOwner(now, ZoneId.systemDefault()).recalculate(now, ZoneId.systemDefault(), clockChanged)
                val event = current.clockEvent
                if (fromBroadcast || event == null) current else current.deliverClock(event, now)
            }
            if (fromBroadcast && state.phase.ringing) {
                // A boot broadcast cannot start a mediaPlayback FGS directly.
                setClock(state, state.restoreAt(now))
                if (!exact) alerts.ringFallback(state.phase)
            } else {
                applyState(state)
            }
        }
    }

    private fun applyState(state: WakeState) {
        setClock(state, if (state.phase.active) state.nextAlarm else state.clockEvent?.deadline)
        if (state.phase.ringing) {
            val intent = Intent(app, RingService::class.java).putExtra(PHASE, state.phase.name)
                .putExtra("escalated", state.escalated).putExtra("vibrate", state.owner?.vibrate != false)
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
        controller.deliver(AlarmEvent(StepId(intent.getLongExtra(RUN, -1), phase), intent.getLongExtra(DEADLINE, -1), intent.getStringExtra("alarmId")))
    }
}
class RestoreReceiver : WakeReceiver() {
    override suspend fun receive(controller: WakeController, intent: Intent) = controller.restore(
        fromBroadcast = true, clockChanged = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED)
}
