package jp.wakelock

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ClockAlarmTest {
    private val utc = ZoneId.of("UTC")
    private val monday = ms("2026-11-23T06:59:00Z")
    private val seven = monday + 60_000
    private val ready = WakeState(bathroom = "A".repeat(22), kitchen = "B".repeat(22))
    private val alarm = ClockAlarm("a", minute = 420, zoneId = utc.id)
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private fun WakeState.store(card: ClockAlarm) = saveCard(card, monday, utc)
    private fun WakeState.tick(at: Long) = deliverClock(requireNotNull(clockEvent), at)
    private fun WakeState.persist(): WakeState {
        val p = mutablePreferencesOf()
        AlarmRecords.write(p, this)
        return AlarmRecords.read(p)
    }

    private fun complete(initial: WakeState): WakeState {
        val scan = initial.beginScan()
        val kitchen = scan.verify(scan.stepId, ready.qrPayload(Place.BATHROOM), seven + 1)
        val waiting = kitchen.verify(kitchen.stepId, ready.qrPayload(Place.KITCHEN), seven + 2)
        val first = waiting.tick(waiting.deadline)
        val finalWait = first.verify(first.stepId, ready.qrPayload(Place.KITCHEN), first.deadline)
        val final = finalWait.tick(finalWait.deadline)
        return final.verify(final.stepId, ready.qrPayload(Place.BATHROOM), final.deadline)
    }

    @Test fun oneShotUsesNextLocalTimeAndTurnsOffAtStart() {
        val scheduled = ready.store(alarm)
        assertEquals(seven, scheduled.clockEvent!!.deadline)
        assertEquals(scheduled, scheduled.tick(seven - 1))
        val ring = scheduled.tick(seven)
        assertEquals(Phase.INITIAL_ALARM, ring.phase)
        assertFalse(ring.alarms.single().enabled)
        assertEquals(ring, ring.deliverClock(scheduled.clockEvent!!, seven))
        assertEquals(seven + 86_400_000, alarm.next(seven, utc).due)
    }

    @Test fun sundayAndSaturdayBitsWrapAcrossTheWeek() {
        val weekend = alarm.copy(days = 65)
        val saturday = ms("2026-11-28T07:00:00Z")
        assertEquals(saturday, weekend.next(monday, utc).due)
        assertEquals(saturday + 86_400_000, weekend.next(saturday, utc).due)
        assertEquals(saturday + 7 * 86_400_000L, weekend.next(saturday + 86_400_000, utc).due)
    }

    @Test fun repeatingOwnerSchedulesNextWeekOnlyAfterFinalQr() {
        val ring = ready.store(alarm.copy(days = 2)).tick(seven)
        assertTrue(ring.owner!!.enabled)
        assertEquals(0, ring.owner!!.due)
        val done = complete(ring)
        assertEquals(Phase.COMPLETED, done.phase)
        val released = done.finishOwner(done.finished, utc).persist()
        assertNull(released.ownerId)
        assertEquals(seven + 7 * 86_400_000L, released.clockEvent!!.deadline)
    }

    @Test fun collisionWaitsBehindProtocolEvenAcrossRestart() {
        val second = alarm.copy(id = "b", label = "通勤", sound = AlarmSound("content://test/b", "B"), vibrate = false)
        val scheduled = ready.store(second).store(alarm)
        val first = scheduled.tick(seven)
        assertEquals("a", first.ownerId)
        val pending = first.persist()
        assertEquals(first, pending)
        assertEquals(0, pending.owner!!.due)
        assertEquals(seven, pending.nextCard!!.due)
        assertNull(pending.clockEvent!!.alarmId)
        val done = complete(pending)
        val finished = done.finishOwner(done.finished, utc)
        val next = finished.tick(done.finished)
        assertEquals("b", next.ownerId)
        assertEquals(second.sound, next.owner!!.sound)
        assertFalse(next.owner!!.vibrate)
        assertNotEquals(first.generation, next.generation)
        assertThrows(InputError::class.java) { next.beginScan().verify(first.beginScan().stepId, ready.qrPayload(Place.BATHROOM), done.finished) }
    }

    @Test fun emergencyStopConsumesOnlyOwnerAndKeepsQueue() {
        val ring = ready.store(alarm.copy(days = 127)).store(alarm.copy(id = "b", minute = 421)).tick(seven)
        val stopped = ring.abort(seven + 180_000).finishOwner(seven + 180_000, utc)
        assertEquals(Phase.EMERGENCY_STOPPED, stopped.phase)
        assertEquals(seven + 86_400_000, stopped.alarms.first { it.id == "a" }.due)
        assertEquals("b", stopped.clockEvent!!.alarmId)
        assertEquals("b", stopped.tick(seven + 180_000).ownerId)
    }

    @Test fun snoozeRestoresSameOwnerAndCannotReplaceAnyQr() {
        val ring = ready.store(alarm).tick(seven)
        val hold = ring.snooze(ring.stepId, seven + 10).persist()
        assertTrue(hold.phase.active)
        assertFalse(hold.phase.ringing)
        assertEquals(hold, hold.tick(hold.deadline - 1))
        assertThrows(InputError::class.java) { hold.verify(hold.stepId, ready.qrPayload(Place.BATHROOM), seven + 20) }
        assertThrows(InputError::class.java) { hold.issueQr(Place.BATHROOM, hold.bathroom) }
        val resumed = hold.tick(hold.deadline)
        assertEquals(ring.stepId, resumed.stepId)
        assertEquals(ring.ownerId, resumed.ownerId)
        assertThrows(InputError::class.java) { resumed.snooze(resumed.stepId, hold.deadline) }
        assertEquals(Phase.AWAITING_BATHROOM_QR, resumed.beginScan().phase)
    }

    @Test fun laterStepsAndStaleButtonsCannotSnooze() {
        val ring = ready.store(alarm).tick(seven)
        assertThrows(InputError::class.java) { ring.snooze(StepId(99, ring.phase), seven) }
        for (phase in Phase.entries.filter { it != Phase.INITIAL_ALARM }) {
            val other = ring.copy(phase = phase)
            assertThrows(InputError::class.java) { other.snooze(other.stepId, seven) }
        }
    }

    @Test fun editToggleDeleteInvalidatePreviousDeliveries() {
        val scheduled = ready.store(alarm)
        val event = scheduled.clockEvent!!
        val later = scheduled.store(alarm.copy(minute = 450))
        assertEquals(later, later.deliverClock(event, seven))
        val off = later.store(later.alarms.single().copy(enabled = false))
        assertNull(off.clockEvent)
        val on = off.store(off.alarms.single().copy(enabled = true))
        assertEquals(seven + 1_800_000, on.clockEvent!!.deadline)
        val removed = on.deleteCard("a").persist()
        assertNull(removed.clockEvent)
        assertEquals(removed, removed.deliverClock(on.clockEvent!!, seven + 1_800_000))
    }

    @Test fun metadataEditsKeepDueAndOtherCardsIndependent() {
        val scheduled = ready.store(alarm).store(alarm.copy(id = "b"))
        val updated = scheduled.modifyCard("b") { it.copy(label = "遅番", vibrate = false, sound = AlarmSound("content://test/1", "音")) }.persist()
        assertEquals(scheduled.alarms[0], updated.alarms[0])
        assertEquals(seven, updated.alarms[1].due)
        assertEquals("遅番", updated.alarms[1].label)
        assertThrows(InputError::class.java) { updated.tick(seven).modifyCard("a") { it.copy(enabled = false) } }
    }

    @Test fun missingQrAllowsDraftButRefusesEnabling() {
        assertThrows(InputError::class.java) { WakeState().store(alarm) }
        val draft = WakeState().store(alarm.copy(enabled = false))
        assertNull(draft.clockEvent)
        assertThrows(InputError::class.java) { draft.store(alarm) }
    }

    @Test fun legacySingleAlarmMigratesExactlyOnceAndDeletionStaysDeleted() {
        val prefs = mutablePreferencesOf()
        val at = seven + 14 * 86_400_000L
        val selected = AlarmSound("content://test/file", "以前の音", true)
        WakeRecord.write(prefs, ready.schedule(at, true, monday))
        SoundRecord.write(prefs, selected)
        val migrated = AlarmRecords.read(prefs)
        assertEquals(at, migrated.clockEvent!!.deadline)
        assertEquals(selected, migrated.alarms.single().sound)
        assertEquals(ready.bathroom, migrated.bathroom)
        assertEquals(migrated, migrated.persist())
        AlarmRecords.write(prefs, migrated.deleteCard("legacy"))
        assertTrue(AlarmRecords.read(prefs).alarms.isEmpty())
        assertEquals(selected, SoundRecord.read(prefs))
    }

    @Test fun legacyRunningSessionRetainsDeadlineAndExpectedQr() {
        val legacy = ready.schedule(seven, true, monday)
        val ring = legacy.fire(legacy.event, seven).beginScan()
        val migrated = AlarmRecords.migrate(ring, AlarmSound(), utc).persist()
        assertEquals("legacy", migrated.ownerId)
        assertEquals(ring.stepId, migrated.stepId)
        assertEquals(ring.deadline, migrated.clockEvent!!.deadline)
        assertEquals(Phase.AWAITING_KITCHEN_QR, migrated.verify(ring.stepId, ready.qrPayload(Place.BATHROOM), seven + 1).phase)
    }

    @Test fun zoneChangeKeepsWallTimeAndOverdueQueue() {
        val eastern = ZoneId.of("America/New_York")
        val moved = ready.store(alarm).store(alarm.copy(id = "b", days = 2)).recalculate(monday, eastern, true)
        assertTrue(moved.alarms.all { it.due == ms("2026-11-23T12:00:00Z") })
        val overdue = ready.store(alarm).recalculate(seven + 1, eastern, true)
        assertEquals(seven, overdue.clockEvent!!.deadline)
    }

    @Test fun daylightSavingGapAndOverlapRingOncePerLocalDay() {
        val eastern = ZoneId.of("America/New_York")
        val gap = alarm.copy(minute = 150, days = 1)
        assertEquals(ms("2026-03-08T07:30:00Z"), gap.next(ms("2026-03-08T05:00:00Z"), eastern).due)
        val overlap = alarm.copy(minute = 90, days = 1)
        val first = overlap.next(ms("2026-11-01T04:00:00Z"), eastern).due
        assertEquals(ms("2026-11-01T05:30:00Z"), first)
        assertEquals(ms("2026-11-08T06:30:00Z"), overlap.next(first, eastern).due)
    }
}
