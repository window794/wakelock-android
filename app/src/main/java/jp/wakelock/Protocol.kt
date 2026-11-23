package jp.wakelock

enum class Place(val wire: String, val title: String) {
    BATHROOM("bathroom", "洗面所"), KITCHEN("kitchen", "キッチン")
}

enum class Phase {
    IDLE, ALARM_RINGING, AWAITING_BATHROOM_QR, AWAITING_KITCHEN_QR,
    AWAITING_RECHECK, CHECKING, COMPLETED, ABORTED;

    val ringing get() = this in setOf(ALARM_RINGING, AWAITING_BATHROOM_QR, AWAITING_KITCHEN_QR)
    val active get() = ringing || this == AWAITING_RECHECK || this == CHECKING
}

class InputError(message: String) : IllegalStateException(message)

private val qrFormat = Regex("""wakelock:v1:(bathroom|kitchen):([A-Za-z0-9_-]{22})""")
fun readToken(raw: String, expected: Place): String {
    val match = qrFormat.matchEntire(raw) ?: throw InputError("WakeLock用のQRコード形式ではありません。")
    if (match.groupValues[1] != expected.wire) throw InputError("${expected.title}のQRコードを読み取ってください。")
    return match.groupValues[2]
}

data class WakeState(
    val date: Long = 0,
    val enabled: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val bathroom: String = "",
    val kitchen: String = "",
    val started: Long = 0,
    val qrDone: Long = 0,
    val deadline: Long = 0,
    val finished: Long = 0,
    val lastAbort: Long = 0
) {
    val nextAlarm: Long? get() = when {
        enabled && !phase.active -> date
        phase == Phase.AWAITING_RECHECK || phase == Phase.CHECKING -> deadline
        else -> null
    }

    fun registered(place: Place) = token(place).isNotEmpty()
    private fun token(place: Place) = if (place == Place.BATHROOM) bathroom else kitchen

    fun register(place: Place, raw: String): WakeState {
        if (phase.active) throw InputError("実行中はQRコードを変更できません。")
        val value = readToken(raw, place)
        return if (place == Place.BATHROOM) copy(bathroom = value) else copy(kitchen = value)
    }

    fun schedule(at: Long, on: Boolean, now: Long): WakeState {
        if (phase.active) throw InputError("起床確認が進行中です。")
        if (on && at <= now) throw InputError("現在より後の日時を選んでください。")
        if (on && Place.entries.any { !registered(it) }) throw InputError("先に2地点のQRコードを登録してください。")
        return copy(date = at, enabled = on, phase = if (on) Phase.IDLE else phase)
    }

    fun fire(now: Long): WakeState {
        val due = nextAlarm ?: return this
        if (now < due) return this
        return when (phase) {
            Phase.AWAITING_RECHECK -> copy(phase = Phase.CHECKING, deadline = now + RESPONSE_MS)
            Phase.CHECKING -> ringAgain()
            else -> copy(phase = Phase.ALARM_RINGING, enabled = false, started = now, qrDone = 0, finished = 0, deadline = 0)
        }
    }

    fun beginScan() = if (phase == Phase.ALARM_RINGING) copy(phase = Phase.AWAITING_BATHROOM_QR) else this

    fun verify(place: Place, raw: String, now: Long): WakeState {
        val expected = when (phase) {
            Phase.AWAITING_BATHROOM_QR -> Place.BATHROOM
            Phase.AWAITING_KITCHEN_QR -> Place.KITCHEN
            else -> throw InputError("現在はQR認証の段階ではありません。")
        }
        if (place != expected) throw InputError("次は${expected.title}を認証してください。")
        val value = readToken(raw, expected)
        if (!registered(expected)) throw InputError("${expected.title}のQRコードが未登録です。")
        if (value != token(expected)) throw InputError("登録済みのQRコードと一致しません。")
        return if (place == Place.BATHROOM) copy(phase = Phase.AWAITING_KITCHEN_QR)
        else copy(phase = Phase.AWAITING_RECHECK, qrDone = now, deadline = now + RECHECK_MS)
    }

    fun answer(awake: Boolean, now: Long): WakeState {
        if (phase != Phase.CHECKING) return this
        return if (awake && now < deadline) copy(phase = Phase.COMPLETED, finished = now, deadline = 0)
        else ringAgain()
    }

    private fun ringAgain() = copy(phase = Phase.ALARM_RINGING, qrDone = 0, deadline = 0)
    fun abort(now: Long) = if (phase.active) copy(phase = Phase.ABORTED, enabled = false, deadline = 0, lastAbort = now) else this

    companion object {
        const val RECHECK_MS = 10 * 60_000L
        const val RESPONSE_MS = 60_000L
    }
}
