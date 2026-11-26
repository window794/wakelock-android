package jp.wakelock

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException

// Only the active observation and unacknowledged terminal snapshots live alongside alarm state.
internal object HistoryJournal {
    private val active = stringPreferencesKey("history.active")
    private val pending = stringSetPreferencesKey("history.pending")
    fun active(p: Preferences) = p[active]?.let(::decodeHistory)
    fun pending(p: Preferences) = p[pending].orEmpty()

    fun record(p: MutablePreferences, before: WakeState, after: WakeState, now: Long) {
        fun enqueue(row: WakeHistory) { p[pending] = pending(p) + encodeHistory(row) }
        var row = active(p)
        if (row != null && !row.matches(before)) {
            enqueue(row.copy(status = RunStatus.INTERRUPTED))
            row = null
        }
        if (row == null && before.phase.active) row = WakeHistory.start(before, legacy = true)
        if (row != null) {
            row = row.advance(before, after, now)
            if (row.status != RunStatus.IN_PROGRESS) {
                enqueue(row)
                row = null
            }
        }
        if (row == null && after.phase.active) row = WakeHistory.start(after)
        if (row == null) p.remove(active) else p[active] = encodeHistory(row)
    }

    fun acknowledge(p: MutablePreferences, written: Set<String>) {
        val rest = pending(p) - written
        if (rest.isEmpty()) p.remove(pending) else p[pending] = rest
    }
}

private fun encodeHistory(h: WakeHistory) = JSONObject().apply {
    put("id", h.id); put("generation", h.generation); put("alarm", h.alarmId); put("name", h.name)
    put("scheduled", h.scheduled); put("ring", h.ring); put("zone", h.zone)
    put("b1", h.bathroom1); put("k1", h.kitchen1); put("k2", h.kitchen2); put("b2", h.bathroom2)
    put("end", h.ended); put("snoozes", h.snoozes); put("escalations", h.escalations); put("status", h.status.name)
}.toString()

private fun decodeHistory(raw: String): WakeHistory = with(JSONObject(raw)) {
    fun time(key: String) = if (isNull(key)) null else getLong(key)
    WakeHistory(getString("id"), getLong("generation"), optString("alarm").ifEmpty { null }, getString("name"),
        time("scheduled"), getLong("ring"), getString("zone"), time("b1"), time("k1"), time("k2"), time("b2"),
        time("end"), getInt("snoozes"), time("escalations")?.toInt(), RunStatus.valueOf(getString("status")))
}

internal class HistoryStore(context: Context, private val preferences: DataStore<Preferences> = context.wakeData,
                            databaseName: String = "wake-history.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history (id TEXT PRIMARY KEY NOT NULL, first_ring INTEGER NOT NULL, snapshot TEXT NOT NULL)")
        db.execSQL("CREATE INDEX history_time ON history(first_ring, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unknown wake history schema upgrade $oldVersion to $newVersion")
    }

    suspend fun archive(pending: Set<String>) = withContext(Dispatchers.IO) {
        if (pending.isEmpty()) return@withContext
        val db = writableDatabase
        db.transaction {
            for (snapshot in pending) {
                val row = decodeHistory(snapshot)
                val values = ContentValues().apply {
                    put("id", row.id); put("first_ring", row.ring); put("snapshot", snapshot)
                }
                db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
        preferences.edit { HistoryJournal.acknowledge(it, pending) }
    }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        preferences.data.map(HistoryJournal::pending).distinctUntilChanged()
            .onEach { archive(it) }.retryWhen { cause, _ ->
                if (cause is IOException || cause is android.database.sqlite.SQLiteException) {
                    delay(5_000)
                    true
                } else false
            }.collect()
    }

    val rows = preferences.data.map { HistoryJournal.active(it) to HistoryJournal.pending(it) }
        .distinctUntilChanged().map { (active, waiting) ->
            val all = mutableMapOf<String, WakeHistory>()
            readableDatabase.rawQuery("SELECT snapshot FROM history ORDER BY first_ring DESC, id", null).use { c ->
                while (c.moveToNext()) decodeHistory(c.getString(0)).let { all[it.id] = it }
            }
            waiting.map(::decodeHistory).forEach { all[it.id] = it }
            active?.let { all.putIfAbsent(it.id, it) }
            all.values.sortedWith(compareByDescending<WakeHistory> { it.ring }.thenBy { it.id })
        }.flowOn(Dispatchers.IO)
}
