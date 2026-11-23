package jp.wakelock

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

internal val Context.wakeData by preferencesDataStore("wake")

internal object WakeRecord {
    private val date = longPreferencesKey("date")
    private val enabled = booleanPreferencesKey("enabled")
    private val phase = stringPreferencesKey("phase")
    private val bathroom = stringPreferencesKey("bathroom")
    private val kitchen = stringPreferencesKey("kitchen")
    private val started = longPreferencesKey("started")
    private val qrDone = longPreferencesKey("qrDone")
    private val deadline = longPreferencesKey("deadline")
    private val finished = longPreferencesKey("finished")
    private val lastAbort = longPreferencesKey("lastAbort")
    private val version = intPreferencesKey("protocolVersion")
    private val firstDone = longPreferencesKey("firstDone")
    private val generation = longPreferencesKey("generation")
    private val escalated = booleanPreferencesKey("escalated")
    private val shortMode = booleanPreferencesKey("shortMode")

    fun read(p: Preferences): WakeState {
        val base = WakeState(date = p[date] ?: 0, bathroom = p[bathroom].orEmpty(),
            kitchen = p[kitchen].orEmpty(), lastAbort = p[lastAbort] ?: 0, generation = p[generation] ?: 0)
        val recovery = base.copy(phase = Phase.RECOVERY_REQUIRED)
        val registered = Place.entries.all { base.registered(it) }
        val on = p[enabled] == true
        if (p[version] == null) return when (p[phase]) {
            null, "IDLE" -> if (on && (base.date <= 0 || !registered)) recovery else base.copy(enabled = on)
            "COMPLETED" -> base // A v1 self-report is not a v2 final QR.
            "ABORTED" -> base.copy(phase = Phase.EMERGENCY_STOPPED)
            else -> recovery
        }
        if (p[version] != 2) return recovery
        val savedPhase = Phase.entries.find { it.name == p[phase] } ?: return recovery
        val short = p[shortMode] == true
        if (short && !BuildConfig.DEBUG && (on || savedPhase.active)) return recovery
        val state = base.copy(phase = savedPhase, enabled = on, started = p[started] ?: 0,
            qrDone = p[qrDone] ?: 0, firstDone = p[firstDone] ?: 0, finished = p[finished] ?: 0,
            deadline = p[deadline] ?: 0, escalated = p[escalated] == true,
            timing = if (short && BuildConfig.DEBUG) Timing.SHORT else Timing.NORMAL)
        if (on && (savedPhase != Phase.IDLE || state.date <= 0 || !registered)) return recovery
        if (savedPhase.active && (!registered || state.started <= 0)) return recovery
        if (savedPhase.ringing && (state.escalated != (state.deadline == 0L) || state.deadline < 0)) return recovery
        if (savedPhase.waiting) {
            val expected = if (savedPhase == Phase.WAITING_FIRST_CHALLENGE) state.qrDone + state.timing.first
                else state.firstDone + state.timing.final
            if (state.deadline <= 0 || state.escalated || state.deadline != expected) return recovery
        }
        if ((savedPhase.step >= 3 || savedPhase == Phase.WAITING_FIRST_CHALLENGE) && state.qrDone <= 0) return recovery
        if ((savedPhase.step >= 4 || savedPhase == Phase.WAITING_FINAL_CHALLENGE) && state.firstDone <= 0) return recovery
        if (savedPhase == Phase.COMPLETED && (state.started <= 0 || state.finished <= 0)) return recovery
        return state
    }
    fun write(p: MutablePreferences, s: WakeState) {
        p[version] = 2
        p[firstDone] = s.firstDone
        p[generation] = s.generation
        p[escalated] = s.escalated
        p[shortMode] = s.timing == Timing.SHORT
        p[date] = s.date
        p[enabled] = s.enabled
        p[phase] = s.phase.name
        p[bathroom] = s.bathroom
        p[kitchen] = s.kitchen
        p[started] = s.started
        p[qrDone] = s.qrDone
        p[deadline] = s.deadline
        p[finished] = s.finished
        p[lastAbort] = s.lastAbort
    }
}

class WakeStore(private val context: Context) {
    val states = context.wakeData.data.map(WakeRecord::read)
    suspend fun change(transform: (WakeState) -> WakeState): WakeState {
        val data = context.wakeData.edit { prefs -> WakeRecord.write(prefs, transform(WakeRecord.read(prefs))) }
        return WakeRecord.read(data)
    }
}
