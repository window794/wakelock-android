package jp.wakelock

import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val bath = "wakelock:v1:bathroom:" + "A".repeat(22)
    private val kitchen = "wakelock:v1:kitchen:" + "B".repeat(22)
    private val ready = WakeState().register(Place.BATHROOM, bath).register(Place.KITCHEN, kitchen)
    private val armed = ready.schedule(1000, true, 1)
    private fun WakeState.tick(at: Long) = fire(event, at)
    private fun WakeState.scan(raw: String, at: Long) = verify(stepId, raw, at)
    private val initial = armed.tick(1000)
    private val bathroom = initial.beginScan()
    private val second = bathroom.scan(bath, 2000)
    private val waiting = second.scan(kitchen, 3000)
    private val first = waiting.tick(603000)
    // Use a late scan, after escalation, to expose wrong bases for the final deadline.
    private val next = first.tick(663000).scan(kitchen, 680000)
    private val final = next.tick(980000)
    private val done = final.scan(bath, 982000)

    @Test fun initialAlarmRequiresBathroomScan() {
        assertEquals(Phase.INITIAL_ALARM, initial.phase)
        assertEquals(Phase.AWAITING_BATHROOM_QR, bathroom.phase)
        assertThrows(InputError::class.java) { initial.scan(bath, 1001) }
    }
    @Test fun bathroomUnlocksOnlyTheInitialKitchenStep() {
        assertEquals(Phase.AWAITING_KITCHEN_QR, second.phase)
        assertThrows(InputError::class.java) { bathroom.scan(kitchen, 1500) }
        assertThrows(InputError::class.java) { second.scan(bath, 2001) }
    }
    @Test fun initialKitchenStartsTenMinuteSilentWait() {
        assertEquals(Phase.WAITING_FIRST_CHALLENGE, waiting.phase)
        assertFalse(waiting.phase.ringing)
        assertEquals(3000L, waiting.qrDone)
        assertEquals(603000L, waiting.nextAlarm)
    }
    @Test fun firstChallengeStartsOnlyWhenDue() {
        assertEquals(waiting, waiting.tick(602999))
        assertEquals(Phase.FIRST_CHALLENGE_ACTIVE, first.phase)
        assertTrue(first.phase.ringing)
        assertEquals(663000L, first.nextAlarm)
    }
    @Test fun firstChallengeRequiresMatchingKitchen() {
        for (bad in listOf(bath, kitchen.dropLast(1) + "X", "$kitchen ")) {
            assertThrows(InputError::class.java) { first.scan(bad, 604000) }
        }
    }
    @Test fun firstSuccessStartsFiveMinutesFromActualScan() {
        assertEquals(Phase.WAITING_FINAL_CHALLENGE, next.phase)
        assertEquals(680000L, next.firstDone)
        assertEquals(980000L, next.nextAlarm)
        assertFalse(next.escalated)
        assertFalse(next.phase.ringing)
    }
    @Test fun finalChallengeStartsOnlyWhenDue() {
        assertEquals(next, next.tick(979999))
        assertEquals(Phase.FINAL_CHALLENGE_ACTIVE, final.phase)
        assertEquals(1040000L, final.nextAlarm)
    }
    @Test fun finalRequiresRegisteredBathroom() {
        for (bad in listOf(kitchen, bath.dropLast(1) + "X")) {
            assertThrows(InputError::class.java) { final.scan(bad, 981000) }
        }
    }
    @Test fun onlyFinalQrCompletesAndRecordsAllTimes() {
        assertEquals(Phase.COMPLETED, done.phase)
        assertFalse(done.phase.ringing)
        assertEquals(listOf(1000L, 3000L, 680000L, 982000L), listOf(done.started, done.qrDone, done.firstDone, done.finished))
    }
    @Test fun escalationHasOneDeadlinePerAlarmPhase() {
        for (s in listOf(initial, bathroom, second, first, final)) {
            val at = s.nextAlarm!!
            assertFalse(s.tick(at - 1).escalated)
            val strong = s.tick(at)
            assertEquals(s.phase, strong.phase)
            assertTrue(strong.escalated)
            assertNull(strong.nextAlarm)
            assertEquals(strong, strong.fire(s.event, at + 1))
        }
        assertEquals(initial.deadline, second.deadline)
    }
    @Test fun noResponseNeverCompletesOrSkipsAnActiveChallenge() {
        for (s in listOf(initial, first, final)) {
            val late = s.tick(Long.MAX_VALUE)
            assertTrue(late.phase.ringing)
            assertEquals(s.phase, late.tick(Long.MAX_VALUE).phase)
            assertEquals(0L, late.finished)
        }
    }
    @Test fun staleAlarmAndRepeatedScansCannotConsumeLaterSteps() {
        val rescheduled = armed.schedule(armed.date, true, 2)
        assertEquals(rescheduled, rescheduled.fire(armed.event, 1000))
        assertEquals(initial, initial.fire(armed.event, 500000))
        assertEquals(waiting, waiting.fire(second.event, 603000))
        assertThrows(InputError::class.java) { first.verify(second.stepId, kitchen, 610000) }
        assertThrows(InputError::class.java) { first.verify(first.stepId.copy(generation = 0), kitchen, 610000) }
        assertThrows(InputError::class.java) { waiting.scan(kitchen, 4000) }
    }
    @Test fun completionCancelsAllWakeups() {
        assertNull(done.nextAlarm)
        assertNull(done.restoreAt(990000))
        assertEquals(done, done.fire(final.event, 2000000))
    }
    @Test fun emergencyStopIsDurableAtEveryStep() {
        for (s in listOf(initial, bathroom, second, waiting, first, next, final)) {
            val p = mutablePreferencesOf()
            WakeRecord.write(p, s.abort(999000))
            val stopped = WakeRecord.read(p)
            assertEquals(Phase.EMERGENCY_STOPPED, stopped.phase)
            assertFalse(stopped.phase.active)
            assertEquals(999000L, stopped.lastAbort)
            assertNull(stopped.restoreAt(2000000))
            assertEquals(stopped, stopped.fire(s.event, 2000000))
        }
    }
    @Test fun processRestartRoundTripsTheRecordAndEvent() {
        for (s in listOf(armed, initial, bathroom, second, waiting, first, first.tick(663000), next, final, done)) {
            val p = mutablePreferencesOf()
            WakeRecord.write(p, s)
            assertEquals(s, WakeRecord.read(p))
        }
    }
    @Test fun bootRestoresTheRightAlarmWithoutResettingAnActiveTimeout() {
        assertEquals(1000L, armed.restoreAt(1))
        assertEquals(603000L, waiting.restoreAt(10000))
        assertEquals(980000L, next.restoreAt(700000))
        assertEquals(605000L, first.restoreAt(604000))
        assertEquals(first, first.tick(605000))
        assertEquals(663000L, first.restoreAt(662800))
        assertTrue(first.tick(670000).escalated)
        assertEquals(671000L, first.tick(670000).restoreAt(670000))
    }
    @Test fun validatesCompatibleQrAndKeepsRegistrations() {
        assertEquals("A".repeat(22), readToken(bath, Place.BATHROOM))
        for (bad in listOf("", "$bath ", bath.dropLast(1), "$bath:", bath.replace("v1","v2"), kitchen)) {
            assertThrows(InputError::class.java) { ready.register(Place.BATHROOM, bad) }
        }
        assertThrows(InputError::class.java) { WakeState().schedule(100, true, 1) }
        assertThrows(InputError::class.java) { first.register(Place.KITCHEN, kitchen) }
    }
    @Test fun timingIsFrozenWhileArmedOrActive() {
        assertEquals(Timing.NORMAL, ready.timing)
        assertThrows(InputError::class.java) { armed.setTiming(Timing.SHORT) }
        assertThrows(InputError::class.java) { waiting.setTiming(Timing.NORMAL) }
        if (!BuildConfig.DEBUG) {
            assertThrows(InputError::class.java) { ready.setTiming(Timing.SHORT) }
            return
        }
        val quick = ready.setTiming(Timing.SHORT).schedule(1000, true, 1).tick(1000)
        assertEquals(16000L, quick.nextAlarm)
        val w = quick.beginScan().scan(bath, 2000).scan(kitchen, 3000)
        assertEquals(33000L, w.nextAlarm)
        val c = w.tick(33000)
        assertEquals(48000L, c.nextAlarm)
        val w2 = c.scan(kitchen, 34000)
        assertEquals(54000L, w2.nextAlarm)
        assertEquals(69000L, w2.tick(54000).nextAlarm)
    }
    private fun legacy(name: String) = mutablePreferencesOf(
        stringPreferencesKey("phase") to name, longPreferencesKey("date") to 1000L,
        booleanPreferencesKey("enabled") to true, stringPreferencesKey("bathroom") to ready.bathroom,
        stringPreferencesKey("kitchen") to ready.kitchen, longPreferencesKey("deadline") to 200L)

    @Test fun migrationKeepsAnArmedIdleDateAndBothTokens() {
        val migrated = WakeRecord.read(legacy("IDLE"))
        assertEquals(1000L, migrated.nextAlarm)
        assertEquals(ready.bathroom, migrated.bathroom)
        assertEquals(ready.kitchen, migrated.kitchen)
        assertEquals(0L, migrated.deadline)
    }
    @Test fun migrationRetiresOldSessionsIncludingSelfReportedSuccess() {
        for (name in listOf("ALARM_RINGING","AWAITING_BATHROOM_QR","AWAITING_KITCHEN_QR","AWAITING_RECHECK","CHECKING","COMPLETED","ABORTED","unknown")) {
            val migrated = WakeRecord.read(legacy(name))
            assertNull(migrated.nextAlarm)
            assertFalse(migrated.phase.active)
            assertNotEquals(Phase.COMPLETED, migrated.phase)
            assertEquals(ready.kitchen, migrated.kitchen)
        }
    }
    @Test fun brokenRecordSafelyReturnsToSetup() {
        val p = mutablePreferencesOf()
        WakeRecord.write(p, next)
        p[longPreferencesKey("deadline")] = 0
        assertEquals(Phase.RECOVERY_REQUIRED, WakeRecord.read(p).phase)
        assertNull(WakeRecord.read(p).nextAlarm)
        p[longPreferencesKey("deadline")] = next.deadline - 1000
        assertEquals(Phase.RECOVERY_REQUIRED, WakeRecord.read(p).phase)
    }
    @Test fun overdueWaitAdvancesOnlyOneStepAndClockRollbackNeverFiresEarly() {
        assertEquals(next, next.tick(500000))
        val late = waiting.tick(2000000)
        assertEquals(Phase.FIRST_CHALLENGE_ACTIVE, late.phase)
        assertEquals(2060000L, late.nextAlarm)
    }
    @Test fun disableAndEditKeepSingleShotSettings() {
        assertThrows(InputError::class.java) { armed.schedule(10, true, 10) }
        val off = armed.schedule(armed.date, false, 5)
        assertNull(off.nextAlarm)
        assertEquals(armed.date, off.date)
        assertEquals(off, off.fire(armed.event, 4000))
    }
}
