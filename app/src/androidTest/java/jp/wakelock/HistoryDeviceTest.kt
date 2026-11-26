package jp.wakelock

import androidx.datastore.preferences.core.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val due = 1_800_000_000_000L
    private val card = ClockAlarm(id = "fixture", minute = 420, label = "テストの朝", due = due)
    private val ready = WakeState(bathroom = "A".repeat(22), kitchen = "B".repeat(22), alarms = listOf(card))

    @Test fun atomicProgressSurvivesReopenAndArchiveReplayAfterCardDeletion() = runBlocking {
        val unique = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "$unique.preferences_pb")
        val dbName = "$unique.db"
        var at = due
        suspend fun open(block: suspend (WakeStore, HistoryStore, androidx.datastore.core.DataStore<Preferences>) -> Unit) {
            val job = SupervisorJob()
            val prefs = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            val history = HistoryStore(context, prefs, dbName)
            try { block(WakeStore(prefs) { at }, history, prefs) }
            finally { job.cancelAndJoin(); history.close() }
        }
        var id = ""
        try {
            open { store, history, _ ->
                store.change { ready }
                store.change { it.deliverClock(it.clockEvent!!, at) }
                id = history.rows.first().single().id
                store.change { it.beginScan() }
                at += 1000
                val step = store.states.first().stepId
                val raw = ready.qrPayload(Place.BATHROOM)
                store.change { it.verify(step, raw, at) }
                try { store.change { it.verify(step, raw, at) }; fail("Duplicate QR accepted") } catch (_: InputError) { }
            }
            open { store, history, prefs ->
                var log = history.rows.first().single()
                assertEquals(id, log.id)
                assertEquals(at, log.bathroom1)
                assertEquals(RunStatus.IN_PROGRESS, log.status)
                at += 1000
                store.change { it.verify(it.stepId, ready.qrPayload(Place.KITCHEN), at) }
                val k1 = at
                at += 600_000
                store.change { it.deliverClock(it.clockEvent!!, at) }
                store.change { it.verify(it.stepId, ready.qrPayload(Place.KITCHEN), at) }
                val k2 = at
                at += 300_000
                store.change { it.deliverClock(it.clockEvent!!, at) }
                store.change { it.verify(it.stepId, ready.qrPayload(Place.BATHROOM), at).finishOwner(at, ZoneId.of("UTC")) }
                store.change { it.deleteCard(card.id) }
                val batch = HistoryJournal.pending(prefs.data.first())
                assertEquals(1, batch.size)
                history.archive(batch)
                history.archive(batch)
                log = history.rows.first().single()
                assertEquals(listOf(k1, k2, at, at), listOf(log.kitchen1, log.kitchen2, log.bathroom2, log.completed))
                assertEquals(RunStatus.COMPLETED, log.status)
                assertEquals((at - due) / 1000, log.seconds)
                assertNull(store.states.first().clockEvent)
            }
            open { _, history, _ ->
                val row = history.rows.first().single()
                assertEquals(card.label, row.name)
                assertEquals(id, row.id)
            }
        } finally { context.deleteDatabase(dbName); file.delete() }
    }

    @Test fun migrationDoesNotBackfillAndCorruptionIsInterrupted() {
        val prefs = mutablePreferencesOf()
        val old = ready.copy(phase = Phase.COMPLETED, finished = due)
        HistoryJournal.record(prefs, old, old, due)
        assertNull(HistoryJournal.active(prefs))
        assertTrue(HistoryJournal.pending(prefs).isEmpty())
        val active = ready.deliverClock(ready.clockEvent!!, due)
        HistoryJournal.record(prefs, active, active, due)
        assertNull(HistoryJournal.active(prefs)!!.escalations)
        val broken = active.copy(phase = Phase.RECOVERY_REQUIRED)
        HistoryJournal.record(prefs, broken, broken, due + 1)
        assertNull(HistoryJournal.active(prefs))
        assertTrue(HistoryJournal.pending(prefs).single().contains("INTERRUPTED"))
    }

    @Test fun skipPersistsAndSkippedDeliveryCreatesNoObservation() {
        val zone = ZoneId.systemDefault()
        val source = ready.copy(alarms = listOf(card.copy(days = 127)))
        val skipped = source.skipOccurrence(card.id, due, false, due - 10, zone)
        val prefs = mutablePreferencesOf()
        AlarmRecords.write(prefs, skipped)
        val restored = AlarmRecords.read(prefs)
        assertEquals(skipped, restored)
        HistoryJournal.record(prefs, restored, restored.deliverClock(source.clockEvent!!, due), due)
        assertNull(HistoryJournal.active(prefs))
        assertTrue(HistoryJournal.pending(prefs).isEmpty())
    }
}
