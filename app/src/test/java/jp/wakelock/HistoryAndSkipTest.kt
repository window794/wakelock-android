package jp.wakelock

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*

class HistoryAndSkipTest {
    private val utc = ZoneId.of("UTC")
    private val due = Instant.parse("2026-11-26T07:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private val card = ClockAlarm("work", 420, days = 62, label = "仕事", due = due, zoneId = "UTC")
    private val ready = WakeState(bathroom = "A".repeat(22), kitchen = "B".repeat(22), alarms = listOf(card))
    private val running = ready.deliverClock(ready.clockEvent!!, due)
    private val start = WakeHistory.start(running).copy(zone = "UTC")
    private fun csv(rows: List<WakeHistory>, range: HistoryRange = HistoryRange()) =
        StringWriter().also { HistoryCsv.write(it, rows, range) }.toString()

    @Test fun skipThursdayLeavesFridayAndWeekdaySettingsIntact() {
        val skip = ready.skipOccurrence(card.id, due, false, due - 1, utc)
        assertEquals(card.copy(due = due + day, skipAt = due), skip.nextCard)
        assertEquals(skip, skip.deliverClock(ready.clockEvent!!, due))
        val restored = skip.recalculate(due - 1, utc, false)
        assertEquals(skip, restored)
        val friday = restored.recalculate(due + 1, utc, false)
        assertEquals(0, friday.nextCard!!.skipAt)
        assertEquals(Phase.INITIAL_ALARM, friday.deliverClock(friday.clockEvent!!, due + day).phase)
    }

    @Test fun undoRestoresOnlyUnexpiredOccurrence() {
        val skip = ready.skipOccurrence(card.id, due, false, due - 20, utc)
        assertEquals(ready, skip.skipOccurrence(card.id, due, true, due - 5, utc))
        assertThrows(InputError::class.java) { skip.skipOccurrence(card.id, due, true, due, utc) }
        assertThrows(InputError::class.java) { skip.skipOccurrence(card.id, due, false, due - 2, utc) }
    }

    @Test fun editsPreserveSkipOnlyForUnchangedSchedule() {
        val state = ready.skipOccurrence(card.id, due, false, due - 20, utc)
        val original = state.nextCard!!
        for (edit in listOf(original.copy(minute = 430), original.copy(days = 1), original.copy(enabled = false))) {
            assertEquals(0, state.saveCard(edit, due - 20, utc).alarms.single().skipAt)
        }
        assertEquals(due, state.saveCard(original.copy(label = "変更"), due - 20, utc).nextCard!!.skipAt)
        assertEquals(0, state.recalculate(due - 20, ZoneId.of("Asia/Tokyo"), true).nextCard!!.skipAt)
    }

    @Test fun sameTimePeerStillRunsAndActiveOrSingleCannotSkip() {
        val others = ready.copy(alarms = listOf(card, card.copy(id = "other")))
        val skip = others.skipOccurrence(card.id, due, false, due - 1, utc)
        assertEquals("other", skip.deliverClock(skip.clockEvent!!, due).ownerId)
        for (state in listOf(running, ready.copy(alarms = listOf(card.copy(days = 0))))) {
            assertThrows(InputError::class.java) { state.skipOccurrence(card.id, due, false, due - 1, utc) }
        }
    }

    @Test fun weeklySkipCanCrossTwoWeeksAfterClockReset() {
        val weekly = card.copy(days = 16, skipAt = due)
        assertEquals(due + 7 * day, weekly.next(due - 7 * day, utc).due)
    }

    @Test fun csvHeaderNullsOffsetsNumericTypesAndStableIds() {
        val record = start.copy(id = "run-1", zone = "Asia/Tokyo")
        val exported = csv(listOf(record))
        assertEquals(HistoryCsv.header, exported.substringBefore("\r\n"))
        assertTrue(exported.contains("run-1,work,仕事,2026-11-26T16:00:00.000+09:00,2026-11-26T16:00:00.000+09:00"))
        assertTrue(exported.endsWith(",,,,,,,,0,0,IN_PROGRESS\r\n"))
        assertEquals(exported, csv(listOf(record)))
    }

    @Test fun csvQuotesMultilineAndNeutralizesSpreadsheetFormulas() {
        val result = csv(listOf(start.copy(name = "名前,\"朝\"\n仕事")))
        assertTrue(result.contains("\"名前,\"\"朝\"\"\n仕事\""))
        for (name in listOf("=1+1", " +1", "-1", "@foo", "\tcmd", "\rtest", "＝1")) {
            assertTrue(csv(listOf(start.copy(name = name))).contains("'$name"))
        }
    }

    @Test fun csvRangesAreInclusiveInRecordedZoneAndTiesAreStable() {
        val first = start.copy(ring = Instant.parse("2026-11-30T15:00:00Z").toEpochMilli(), id = "a", zone = "Asia/Tokyo")
        val second = first.copy(id = "b")
        val outside = first.copy(id = "other", ring = first.ring - 1)
        val all = listOf(second, outside, first)
        val december = csv(all, HistoryRange.month(YearMonth.of(2026, 12)))
        assertEquals(december, csv(all.reversed(), HistoryRange(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1))))
        assertFalse(december.contains("other,"))
        assertTrue(december.indexOf("\r\na,") < december.indexOf("\r\nb,"))
    }

    @Test fun observationCountsTransitionsButNotReplayedEscalations() {
        val escalated = running.fire(running.event, running.deadline)
        var log = start.advance(running, escalated, running.deadline)
        log = log.advance(escalated, escalated, running.deadline + 1)
        val snooze = escalated.snooze(escalated.stepId, running.deadline + 2)
        log = log.advance(escalated, snooze, running.deadline + 2)
        assertEquals(1, log.escalations)
        assertEquals(1, log.snoozes)
        assertNull(log.completed)
        assertEquals(start.id, log.id)
    }

    @Test fun stopAndInterruptionNeverReportCompletion() {
        val stopped = start.advance(running, running.abort(due + 15_999), due + 15_999)
        assertEquals(RunStatus.EMERGENCY_STOPPED, stopped.status)
        assertEquals(15L, stopped.seconds)
        assertNull(stopped.completed)
        val interrupted = start.advance(running, running.copy(phase = Phase.RECOVERY_REQUIRED), due + 10)
        assertEquals(RunStatus.INTERRUPTED, interrupted.status)
        assertNull(interrupted.ended)
        assertNull(interrupted.seconds)
    }

    @Test fun resultRequiresFreshForegroundFinalVerification() {
        val presentation = CompletionPresentation()
        val done = running.copy(phase = Phase.COMPLETED, finished = due + 900_000)
        assertFalse(presentation.visible(done))
        presentation.completed(done.generation, presentation.ticket)
        assertTrue(presentation.visible(done))
        assertFalse(presentation.visible(running))
        val oldTicket = presentation.ticket
        presentation.dismiss()
        presentation.completed(done.generation, oldTicket)
        assertFalse(presentation.visible(done))
        assertFalse(CompletionPresentation().visible(done))
        assertFalse(presentation.visible(done.abort(due).copy(phase = Phase.EMERGENCY_STOPPED)))
    }
}
