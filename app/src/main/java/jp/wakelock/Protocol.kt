package jp.wakelock

enum class Place(val wire: String, val title: String) {
    BATHROOM("bathroom", "洗面所"), KITCHEN("kitchen", "キッチン")
}

enum class Phase(val label: String, val step: Int = 0, val place: Place? = null) {
    IDLE("待機中"),
    SNOOZED("スヌーズ中", 1),
    INITIAL_ALARM("初回アラーム", 1, Place.BATHROOM),
    AWAITING_BATHROOM_QR("初回認証", 1, Place.BATHROOM),
    AWAITING_KITCHEN_QR("初回認証", 2, Place.KITCHEN),
    WAITING_FIRST_CHALLENGE("初回認証完了", 2),
    FIRST_CHALLENGE_ACTIVE("起床確認", 3, Place.KITCHEN),
    WAITING_FINAL_CHALLENGE("キッチン再認証完了", 3),
    FINAL_CHALLENGE_ACTIVE("最終確認", 4, Place.BATHROOM),
    COMPLETED("起床プロトコル完了", 4),
    EMERGENCY_STOPPED("緊急停止"),
    RECOVERY_REQUIRED("予約の再設定が必要です");

    val waiting get() = this == WAITING_FIRST_CHALLENGE || this == WAITING_FINAL_CHALLENGE
    val ringing get() = place != null
    val active get() = waiting || ringing || this == SNOOZED
    val prompt get() = when (this) {
        FIRST_CHALLENGE_ACTIVE -> "起床確認：キッチンに移動してください"
        FINAL_CHALLENGE_ACTIVE -> "最終確認：洗面所に移動してください"
        else -> place?.let { "${it.title}に移動してください" } ?: label
    }
}

class InputError(message: String) : IllegalStateException(message)

private val qrFormat = Regex("""wakelock:v1:(bathroom|kitchen):([A-Za-z0-9_-]{22})""")
fun readToken(raw: String, expected: Place): String {
    val match = qrFormat.matchEntire(raw) ?: throw InputError("WakeLock用のQRコード形式ではありません。")
    if (match.groupValues[1] != expected.wire) throw InputError("${expected.title}のQRコードを読み取ってください。")
    return match.groupValues[2]
}

enum class Timing(val first: Long, val final: Long, val escalation: Long) {
    NORMAL(600_000, 300_000, 60_000), SHORT(30_000, 20_000, 15_000)
}

// An OS delivery or a camera result belongs to a particular run and step.
data class StepId(val generation: Long, val phase: Phase)
data class AlarmEvent(val step: StepId, val deadline: Long, val alarmId: String? = null)

data class WakeState(
    val date: Long = 0,
    val enabled: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val bathroom: String = "",
    val kitchen: String = "",
    val started: Long = 0,
    val qrDone: Long = 0,
    val firstDone: Long = 0,
    val deadline: Long = 0,
    val finished: Long = 0,
    val lastAbort: Long = 0,
    val generation: Long = 0,
    val escalated: Boolean = false,
    val timing: Timing = Timing.NORMAL,
    val alarms: List<ClockAlarm> = emptyList(),
    val ownerId: String? = null,
    val snoozeUsed: Boolean = false
) {
    val stepId get() = StepId(generation, phase)
    val nextAlarm: Long? get() = when {
        phase == Phase.IDLE && enabled -> date
        phase.active && deadline > 0 -> deadline
        else -> null
    }
    val event get() = AlarmEvent(stepId, nextAlarm ?: 0)

    // On boot a ringing service needs a new exact delivery, including after escalation.
    fun restoreAt(now: Long): Long? = if (phase.ringing) minOf(nextAlarm ?: Long.MAX_VALUE, now + 1000) else nextAlarm

    internal fun token(place: Place) = when (place) {
        Place.BATHROOM -> bathroom
        Place.KITCHEN -> kitchen
    }
    fun registered(place: Place) = token(place).isNotEmpty()

    fun register(place: Place, raw: String): WakeState {
        if (phase.active) throw InputError("実行中はQRコードを変更できません。")
        val value = readToken(raw, place)
        return if (place == Place.BATHROOM) copy(bathroom = value) else copy(kitchen = value)
    }

    fun setTiming(value: Timing): WakeState {
        if (enabled || alarms.any { it.enabled } || phase.active) throw InputError("予約をオフにしてから切り替えてください。")
        if (value == Timing.SHORT && !BuildConfig.DEBUG) throw InputError("短時間モードはデバッグ版専用です。")
        return copy(timing = value)
    }

    fun schedule(at: Long, on: Boolean, now: Long): WakeState {
        if (phase.active) throw InputError("起床確認が進行中です。")
        if (on && at <= now) throw InputError("現在より後の日時を選んでください。")
        if (on && Place.entries.any { !registered(it) }) throw InputError("先に2地点のQRコードを登録してください。")
        return copy(date = at, enabled = on, phase = if (on) Phase.IDLE else phase, generation = generation + 1)
    }

    fun fire(delivered: AlarmEvent, now: Long): WakeState {
        if (delivered != event || now < (nextAlarm ?: return this)) return this
        return when (phase) {
            Phase.IDLE -> copy(phase = Phase.INITIAL_ALARM, enabled = false, started = now, qrDone = 0,
                firstDone = 0, finished = 0, deadline = now + timing.escalation, escalated = false)
            Phase.SNOOZED -> copy(phase = Phase.INITIAL_ALARM, deadline = now + timing.escalation)
            Phase.WAITING_FIRST_CHALLENGE -> copy(phase = Phase.FIRST_CHALLENGE_ACTIVE, deadline = now + timing.escalation)
            Phase.WAITING_FINAL_CHALLENGE -> copy(phase = Phase.FINAL_CHALLENGE_ACTIVE, deadline = now + timing.escalation)
            else -> copy(escalated = true, deadline = 0)
        }
    }

    fun beginScan() = if (phase == Phase.INITIAL_ALARM) copy(phase = Phase.AWAITING_BATHROOM_QR) else this

    fun verify(step: StepId, raw: String, now: Long): WakeState {
        val expected = phase.place
        if (step != stepId || expected == null || phase == Phase.INITIAL_ALARM)
            throw InputError("認証の段階が変わりました。現在の地点を確認してください。")
        val value = readToken(raw, expected)
        if (!registered(expected) || value != token(expected)) throw InputError("登録済みのQRコードと一致しません。")
        return when (phase) {
            Phase.AWAITING_BATHROOM_QR -> copy(phase = Phase.AWAITING_KITCHEN_QR)
            Phase.AWAITING_KITCHEN_QR -> copy(phase = Phase.WAITING_FIRST_CHALLENGE, qrDone = now,
                escalated = false, deadline = now + timing.first)
            Phase.FIRST_CHALLENGE_ACTIVE -> copy(phase = Phase.WAITING_FINAL_CHALLENGE, firstDone = now,
                escalated = false, deadline = now + timing.final)
            Phase.FINAL_CHALLENGE_ACTIVE -> copy(phase = Phase.COMPLETED, finished = now, escalated = false, deadline = 0)
            else -> throw InputError("現在はQR認証の段階ではありません。")
        }
    }

    fun abort(now: Long) = if (phase.active) copy(phase = Phase.EMERGENCY_STOPPED, enabled = false,
        deadline = 0, escalated = false, lastAbort = now) else this
}
