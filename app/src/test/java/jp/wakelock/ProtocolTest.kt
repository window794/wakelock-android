package jp.wakelock

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val bath = "wakelock:v1:bathroom:" + "A".repeat(22)
    private val kitchen = "wakelock:v1:kitchen:" + "B".repeat(22)
    private val ready = WakeState().register(Place.BATHROOM, bath).register(Place.KITCHEN, kitchen)
    private val armed = ready.schedule(100, true, 1)
    private val scanning = armed.fire(100).beginScan()
    private val second = scanning.verify(Place.BATHROOM, bath, 101)
    private val waiting = second.verify(Place.KITCHEN, kitchen, 102)
    private val checking = waiting.fire(600102)

    @Test fun validatesWholePayloadAndPlace() {
        for (bad in listOf("", "$bath ", bath.dropLast(1), "$bath:", bath.replace("v1", "v2"), bath.replace("AA", "A+"))) {
            assertThrows(InputError::class.java) { readToken(bad, Place.BATHROOM) }
        }
        assertThrows(InputError::class.java) { ready.register(Place.KITCHEN, bath) }
        assertEquals("A".repeat(22), readToken(bath, Place.BATHROOM))
    }
    @Test fun tokensMustBeRegisteredAndMatchExactly() {
        assertThrows(InputError::class.java) { scanning.verify(Place.BATHROOM, bath.dropLast(1) + "Z", 101) }
        assertThrows(InputError::class.java) { scanning.copy(bathroom = "").verify(Place.BATHROOM, bath, 101) }
        assertThrows(InputError::class.java) { WakeState().schedule(10, true, 1) }
        assertThrows(InputError::class.java) { scanning.register(Place.BATHROOM, bath) }
    }
    @Test fun rejectsSkipsAndDuplicateScans() {
        assertThrows(InputError::class.java) { scanning.verify(Place.KITCHEN, kitchen, 101) }
        assertThrows(InputError::class.java) { second.verify(Place.KITCHEN, bath, 102) }
        assertThrows(InputError::class.java) { second.verify(Place.BATHROOM, bath, 102) }
        assertThrows(InputError::class.java) { waiting.verify(Place.KITCHEN, kitchen, 103) }
        assertEquals(waiting, waiting.answer(true, 103))
    }
    @Test fun transitionsScheduleOnlyTheNecessaryDeadline() {
        assertEquals(100L, armed.nextAlarm)
        assertEquals(armed, armed.fire(99))
        val ringing = armed.fire(100)
        assertEquals(Phase.ALARM_RINGING, ringing.phase)
        assertNull(ringing.nextAlarm)
        assertEquals(Phase.AWAITING_BATHROOM_QR, scanning.phase)
        assertEquals(Phase.AWAITING_KITCHEN_QR, second.phase)
        assertEquals(Phase.AWAITING_RECHECK, waiting.phase)
        assertEquals(600102L, waiting.nextAlarm)
        assertEquals(waiting, waiting.fire(600101))
        assertEquals(Phase.CHECKING, checking.phase)
        assertEquals(660102L, checking.nextAlarm)
        assertEquals(checking, checking.fire(600102))
        val completed = checking.answer(true, 660101)
        assertEquals(Phase.COMPLETED, completed.phase)
        assertEquals(listOf(100L, 102L, 660101L), listOf(completed.started, completed.qrDone, completed.finished))
        assertNull(completed.nextAlarm)
        assertEquals(completed, completed.fire(999999))
    }
    @Test fun sleepyLateOrMissingAnswerRingsAgain() {
        for (retry in listOf(checking.answer(false, 600105), checking.answer(true, 660102), checking.fire(660102))) {
            assertEquals(Phase.ALARM_RINGING, retry.phase)
            assertEquals(100L, retry.started)
            assertNull(retry.nextAlarm)
            assertEquals(Phase.AWAITING_BATHROOM_QR, retry.beginScan().phase)
        }
    }
    @Test fun abortIsDistinctAndCancelsOutstandingWork() {
        for (s in listOf(scanning, second, waiting, checking)) {
            val stopped = s.abort(700000)
            assertEquals(Phase.ABORTED, stopped.phase)
            assertNull(stopped.nextAlarm)
            assertEquals(0L, stopped.finished)
            assertEquals(stopped, stopped.fire(800000))
            assertEquals(700000L, stopped.schedule(900000, true, 800000).lastAbort)
        }
    }
    @Test fun editingAndDisablingReplaceReservation() {
        assertThrows(InputError::class.java) { ready.schedule(10, true, 10) }
        val moved = armed.schedule(200, true, 5)
        assertEquals(moved, moved.fire(100))
        val off = moved.schedule(200, false, 6)
        assertEquals(200L, off.date)
        assertNull(off.nextAlarm)
        assertEquals(off, off.fire(201))
    }
    @Test fun recordRoundTripsAcrossRestarts() {
        val p = mutablePreferencesOf()
        assertEquals(WakeState(), WakeRecord.read(p))
        for (s in listOf(armed, second, checking, checking.answer(true, 600103), scanning.abort(110))) {
            WakeRecord.write(p, s)
            assertEquals(s, WakeRecord.read(p))
        }
    }
}
