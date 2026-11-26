package jp.wakelock

import java.io.Writer
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class HistoryRange(val from: LocalDate? = null, val through: LocalDate? = null) {
    init { require(from == null || through == null || from <= through) }
    fun contains(row: WakeHistory): Boolean {
        val day = Instant.ofEpochMilli(row.ring).atZone(ZoneId.of(row.zone)).toLocalDate()
        return (from == null || day >= from) && (through == null || day <= through)
    }

    companion object {
        fun month(month: YearMonth) = HistoryRange(month.atDay(1), month.atEndOfMonth())
    }
}

internal object HistoryCsv {
    const val header = "record_id,alarm_id,alarm_name,scheduled_at,first_ring_at,bathroom_1_at,kitchen_1_at,kitchen_2_at,bathroom_2_at,completed_at,ended_at,duration_seconds,snooze_count,escalation_count,status"
    private val iso = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT)
    private fun text(value: String): String {
        val first = value.dropWhile { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' }.firstOrNull()
        val unsafe = first != null && first in "=+-@＝＋－＠" || value.firstOrNull() in listOf('\t', '\r', '\n')
        val safe = if (unsafe) "'$value" else value
        return if (safe.any { it in ",\"\r\n" }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun write(out: Writer, rows: List<WakeHistory>, range: HistoryRange) {
        out.write(header + "\r\n")
        for (row in rows.sortedWith(compareBy(WakeHistory::ring, WakeHistory::id))) {
            if (!range.contains(row)) continue
            val zone = ZoneId.of(row.zone)
            fun time(at: Long?) = at?.let { iso.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()
            val fields = listOf(text(row.id), text(row.alarmId.orEmpty()), text(row.name), time(row.scheduled),
                time(row.ring), time(row.bathroom1), time(row.kitchen1), time(row.kitchen2), time(row.bathroom2),
                time(row.completed), time(row.ended), row.seconds?.toString().orEmpty(), row.snoozes.toString(),
                row.escalations?.toString().orEmpty(), row.status.name)
            out.write(fields.joinToString(",") + "\r\n")
        }
    }
}
