package jp.wakelock

import java.time.*
import java.util.UUID

/** Sunday is bit 0; zero means a single occurrence. A repeating owner has no due time until it finishes. */
data class ClockAlarm(
    val id: String = UUID.randomUUID().toString(),
    val minute: Int,
    val days: Int = 0,
    val label: String = "",
    val sound: AlarmSound = AlarmSound(),
    val vibrate: Boolean = true,
    val enabled: Boolean = true,
    val due: Long = 0,
    val zoneId: String = ZoneId.systemDefault().id,
    val skipAt: Long = 0
) {
    init { require(minute in 0..1439 && days in 0..127) }

    fun next(now: Long, zone: ZoneId): ClockAlarm {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0L..14L) {
            val day = today.plusDays(offset)
            if (days != 0 && days and (1 shl (day.dayOfWeek.value % 7)) == 0) continue
            val at = onDay(day, zone)
            if (at > now && at != skipAt) return copy(due = at, zoneId = zone.id)
        }
        error("No future occurrence")
    }

    fun onDay(day: LocalDate, zone: ZoneId): Long =
        day.atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
}

internal val WakeState.nextCard get() = alarms.asSequence().filter { it.enabled && it.due > 0 }
    .minWithOrNull(compareBy(ClockAlarm::due, ClockAlarm::id))
internal val WakeState.owner get() = alarms.find { it.id == ownerId }

// The protocol owns the single OS slot through QR waits and snooze. Other due times stay on disk.
internal val WakeState.clockEvent: AlarmEvent? get() =
    if (phase.active) event else nextCard?.let { AlarmEvent(stepId, it.due, it.id) }

internal fun WakeState.saveCard(card: ClockAlarm, now: Long, zone: ZoneId): WakeState {
    if (phase.active) throw InputError("起床確認が終わってから変更してください。")
    val old = alarms.find { it.id == card.id }
    if (card.enabled && Place.entries.any { !registered(it) }) throw InputError("先に2地点のQRコードを登録してください。")
    val updated = when {
        !card.enabled -> card.copy(due = 0, skipAt = 0)
        old == null || !old.enabled || old.minute != card.minute || old.days != card.days -> card.copy(skipAt = 0).next(now, zone)
        else -> card.copy(due = old.due, zoneId = old.zoneId)
    }
    val cards = if (old == null) (alarms + updated).sortedBy { it.id }
        else alarms.map { if (it.id == updated.id) updated else it }
    return copy(alarms = cards)
}

internal fun WakeState.modifyCard(id: String, change: (ClockAlarm) -> ClockAlarm): WakeState {
    val current = alarms.find { it.id == id } ?: throw InputError("アラームが削除されました。")
    return saveCard(change(current), System.currentTimeMillis(), ZoneId.systemDefault())
}

internal fun WakeState.deleteCard(id: String): WakeState {
    if (phase.active) throw InputError("起床確認が終わってから削除してください。")
    return copy(alarms = alarms.filterNot { it.id == id })
}

internal fun WakeState.deliverClock(delivery: AlarmEvent, now: Long): WakeState {
    if (clockEvent != delivery || now < delivery.deadline) return this
    if (phase.active) return fire(delivery, now)
    val card = nextCard ?: return this
    val startedState = copy(date = card.due, enabled = true, phase = Phase.IDLE,
        ownerId = card.id, generation = generation + 1, snoozeUsed = false,
        alarms = alarms.map { if (it.id == card.id) it.copy(enabled = it.days != 0, due = 0, skipAt = it.skipAt.takeIf { target -> target > now } ?: 0) else it })
    return startedState.fire(startedState.event, now)
}

// Both normal completion and emergency stop consume the owner's occurrence.
internal fun WakeState.finishOwner(now: Long, zone: ZoneId): WakeState {
    if (phase.active || ownerId == null) return this
    return copy(alarms = alarms.map {
        if (it.id == ownerId && it.enabled && it.days != 0) it.next(maxOf(now, date), zone) else it
    }, ownerId = null, snoozeUsed = false)
}

internal fun WakeState.snooze(step: StepId, now: Long): WakeState {
    if (step != stepId || phase != Phase.INITIAL_ALARM || snoozeUsed)
        throw InputError("スヌーズは最初の読み取り前に1回だけ使えます。")
    return copy(phase = Phase.SNOOZED, snoozeUsed = true, escalated = false, deadline = now + 300_000)
}

internal fun WakeState.skipOccurrence(id: String, expected: Long, undo: Boolean, now: Long, zone: ZoneId): WakeState {
    val card = alarms.find { it.id == id } ?: throw InputError("アラームが削除されました。")
    if (phase.active || !card.enabled || card.days == 0 || expected <= now || card.zoneId != zone.id ||
        (if (undo) card.skipAt != expected else card.due != expected || card.skipAt > now))
        throw InputError("予約が変わりました。次の予定を確認してください。")
    val updated = if (undo) card.copy(skipAt = 0, due = minOf(card.due, expected)) else card.copy(skipAt = expected).next(expected, zone)
    return copy(alarms = alarms.map { if (it.id == id) updated else it })
}

internal fun WakeState.recalculate(now: Long, zone: ZoneId, clockChanged: Boolean): WakeState = copy(alarms = alarms.map { saved ->
    val it = if (saved.skipAt != 0L && (saved.skipAt <= now || saved.zoneId != zone.id)) saved.copy(skipAt = 0) else saved
    // Overdue occurrences wait their turn, including across a reboot.
    if (!it.enabled || it.due <= now || (!clockChanged && it.zoneId == zone.id)) it
    else if (it.days != 0) it.next(now, zone)
    else {
        val day = Instant.ofEpochMilli(it.due).atZone(ZoneId.of(it.zoneId)).toLocalDate()
        it.copy(due = it.onDay(day, zone), zoneId = zone.id)
    }
})
