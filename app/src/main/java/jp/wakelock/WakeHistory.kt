package jp.wakelock

import java.time.ZoneId
import java.util.UUID

internal enum class RunStatus(val title: String) {
    IN_PROGRESS("進行中"), COMPLETED("完了"), EMERGENCY_STOPPED("緊急停止"), INTERRUPTED("中断")
}

internal data class WakeHistory(
    val id: String,
    val generation: Long,
    val alarmId: String?,
    val name: String,
    val scheduled: Long?,
    val ring: Long,
    val zone: String,
    val bathroom1: Long? = null,
    val kitchen1: Long? = null,
    val kitchen2: Long? = null,
    val bathroom2: Long? = null,
    val ended: Long? = null,
    val snoozes: Int = 0,
    val escalations: Int? = 0,
    val status: RunStatus = RunStatus.IN_PROGRESS
) {
    val completed get() = ended.takeIf { status == RunStatus.COMPLETED }
    val seconds get() = ended?.takeIf { it >= ring }?.let { (it - ring) / 1000 }

    fun matches(s: WakeState) = s.phase.active && generation == s.generation && ring == s.started

    companion object {
        fun start(s: WakeState, legacy: Boolean = false) = WakeHistory(
            UUID.randomUUID().toString(), s.generation, s.ownerId, s.owner?.label.orEmpty(),
            s.date.takeIf { it > 0 }, s.started, ZoneId.systemDefault().id,
            kitchen1 = s.qrDone.takeIf { it > 0 }, kitchen2 = s.firstDone.takeIf { it > 0 },
            snoozes = if (s.snoozeUsed) 1 else 0, escalations = if (legacy) null else 0)
    }

    fun advance(before: WakeState, after: WakeState, now: Long): WakeHistory {
        if (after.generation != generation) return copy(status = RunStatus.INTERRUPTED)
        val phase = after.phase
        val updated = copy(
            snoozes = if (phase == Phase.SNOOZED) 1 else snoozes,
            escalations = if (!before.escalated && after.escalated) escalations?.plus(1) else escalations,
            bathroom1 = if (before.phase == Phase.AWAITING_BATHROOM_QR && phase == Phase.AWAITING_KITCHEN_QR) now else bathroom1,
            kitchen1 = after.qrDone.takeIf { it > 0 } ?: kitchen1,
            kitchen2 = after.firstDone.takeIf { it > 0 } ?: kitchen2)
        return when {
            phase.active -> updated
            before.phase == Phase.FINAL_CHALLENGE_ACTIVE && phase == Phase.COMPLETED && after.finished > 0 ->
                updated.copy(bathroom2 = after.finished, ended = after.finished, status = RunStatus.COMPLETED)
            phase == Phase.EMERGENCY_STOPPED && after.lastAbort > 0 ->
                updated.copy(ended = after.lastAbort, status = RunStatus.EMERGENCY_STOPPED)
            else -> updated.copy(status = RunStatus.INTERRUPTED)
        }
    }
}
