package jp.wakelock

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.wakeData by preferencesDataStore("wake")

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

    fun read(p: Preferences) = WakeState(
        date = p[date] ?: 0, enabled = p[enabled] ?: false,
        phase = p[phase]?.let(Phase::valueOf) ?: Phase.IDLE,
        bathroom = p[bathroom].orEmpty(), kitchen = p[kitchen].orEmpty(),
        started = p[started] ?: 0, qrDone = p[qrDone] ?: 0, deadline = p[deadline] ?: 0,
        finished = p[finished] ?: 0, lastAbort = p[lastAbort] ?: 0
    )
    fun write(p: MutablePreferences, s: WakeState) {
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
