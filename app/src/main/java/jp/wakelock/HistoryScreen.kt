package jp.wakelock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.*

private fun WakeHistory.time(at: Long?, pattern: String = "HH:mm:ss") =
    at?.let { formatted(it, pattern, ZoneId.of(zone)) } ?: "未記録"
private val WakeHistory.scans get() = listOf(bathroom1, kitchen1, kitchen2, bathroom2)
private val WakeHistory.statusText get() = when (status) {
    RunStatus.COMPLETED -> "起床完了"
    RunStatus.INTERRUPTED -> "途中終了"
    else -> status.title
}

@Composable
private fun StatusIcon(status: RunStatus) {
    val cs = MaterialTheme.colorScheme
    val (icon, color) = when (status) {
        RunStatus.COMPLETED -> Icons.Filled.CheckCircle to cs.primary
        RunStatus.EMERGENCY_STOPPED -> Icons.Filled.Error to cs.error
        else -> Icons.Filled.Pending to cs.onSurfaceVariant
    }
    Icon(icon, null, Modifier.size(18.dp), tint = color)
}

@Composable
internal fun HistoryScreen(vm: WakeViewModel, choose: (HistoryRange, String) -> Unit, tab: (String) -> Unit) {
    val rows by vm.historyRows.collectAsStateWithLifecycle(initialValue = null)
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var export by rememberSaveable { mutableStateOf(false) }
    var monthValue by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = YearMonth.parse(monthValue)
    val filtered = remember(rows, month) { rows.orEmpty().filter(HistoryRange.month(month)::contains) }
    val selected = rows?.find { it.id == detail }
    fun list() { export = false; detail = null }
    BackHandler(detail != null || export) { list() }
    Column(Modifier.fillMaxSize()) {
        if (export) {
            Bar("CSVエクスポート", ::list)
            Column(Modifier.padding(16.dp)) { HistoryExport(vm, choose) }
        } else if (selected != null) {
            HistoryDetail(selected, ::list)
        } else {
            Bar("起床履歴") { Tool(Icons.Filled.Download,"CSVエクスポート") { vm.exportNotice = ""; export = true } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.Filled.ChevronLeft,"前の月") { monthValue = month.minusMonths(1).toString() }
                Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleMedium)
                Tool(Icons.Filled.ChevronRight,"次の月") { monthValue = month.plusMonths(1).toString() }
            }
            val times = filtered.filter { it.status == RunStatus.COMPLETED }.mapNotNull { it.seconds }
            if (times.isNotEmpty()) Panel(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row {
                    for ((label,value) in listOf("起床完了" to filtered.count { it.status == RunStatus.COMPLETED }.toString(),
                        "緊急停止" to filtered.count { it.status == RunStatus.EMERGENCY_STOPPED }.toString(),
                        "平均所要" to "${(times.average() / 60).toInt()}分")) {
                        Column(Modifier.weight(1f)) { Text(value, style = MaterialTheme.typography.headlineSmall); Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (filtered.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.History, null, Modifier.size(48.dp))
                    Text(vm.historyError ?: if (rows == null) "読み込み中…" else "起床履歴はまだありません")
                }
            } else LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { row ->
                    val cs = MaterialTheme.colorScheme
                    val last = row.scans.indexOfLast { it != null } + 1
                    Row(Modifier.fillMaxWidth().clickable { detail = row.id }.heightIn(min = 88.dp).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(cs.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text(row.time(row.ring, "d"))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${row.time(row.ring, "E")} ・ ${row.time(row.scheduled,"HH:mm")} ${row.name}")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusIcon(row.status)
                                Text(" ${row.statusText}" + when {
                                    row.status == RunStatus.COMPLETED -> " ・ " + elapsedText(row.seconds)
                                    last > 0 -> " ・ ステップ${last}まで確認"
                                    else -> ""
                                }, style = MaterialTheme.typography.bodyMedium)
                            }
                            ProtocolTrack(last, compact = true, modifier = Modifier.width(160.dp), reached = row.scans.map { it != null })
                        }
                        Icon(Icons.Filled.ChevronRight,null)
                    }
                }
            }
            Tabs("history", tab)
        }
    }
}

@Composable
private fun HistoryDetail(row: WakeHistory, back: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Bar(row.time(row.ring, "M月d日（E）"), back)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(row.status)
            Text(" ${row.statusText}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("予定 ${row.time(row.scheduled,"HH:mm")} ・ ${row.name}", style = MaterialTheme.typography.bodyMedium)
        }
        Panel {
            Row {
                Metric("初回アラーム", row.time(row.ring), Modifier.weight(1f))
                Metric(if (row.status == RunStatus.COMPLETED) "起床完了" else "終了", row.time(row.ended), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Metric("所要時間", elapsedText(row.seconds), Modifier.weight(1f))
                Metric("スヌーズ・緊急停止", "${row.snoozes}回 ・ " + if (row.status == RunStatus.EMERGENCY_STOPPED) "あり" else "なし", Modifier.weight(1f))
            }
        }
        Text("経過", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall, color = cs.primary)
        val events = listOf(row.ring) + row.scans
        val visible = events.indices.filter { events[it] != null }
        visible.forEachIndexed { order, index ->
            val at = requireNotNull(events[index])
            val previous = events.getOrNull(index - 1)
            val subtitle = if (index == 0) row.name else previous?.takeIf { it <= at }?.let {
                (if (index == 1) "鳴動から " else "前の認証から ") + elapsedText((at - it) / 1000)
            }.orEmpty()
            TimelineLine(eventLabels[index], subtitle, row.time(at), order > 0, order < visible.lastIndex || row.status != RunStatus.IN_PROGRESS) {
                if (index == 0) Badge(Icons.Filled.Alarm,cs.secondaryContainer,cs.onSecondaryContainer) else PlaceBadge(protocolPlaces[index - 1])
            }
        }
        if (row.status != RunStatus.IN_PROGRESS) TimelineLine(row.statusText, elapsedText(row.seconds),row.time(row.ended), true, false) {
            val emergency = row.status == RunStatus.EMERGENCY_STOPPED
            Badge(if (emergency) Icons.Filled.Error else if (row.status == RunStatus.COMPLETED) Icons.Filled.Check else Icons.Filled.Pending,
                if (emergency) cs.error else cs.primary, if (emergency) cs.onError else cs.onPrimary)
        }
        Text("無応答の強化　" + (row.escalations?.let { "${it}回" } ?: "未記録") + "　・　" + row.zone,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TimelineLine(label: String, subtitle: String, time: String, before: Boolean, after: Boolean, badge: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight().width(2.dp)) {
                Spacer(Modifier.weight(1f).fillMaxWidth().then(if (before) Modifier.background(MaterialTheme.colorScheme.outlineVariant) else Modifier))
                Spacer(Modifier.weight(1f).fillMaxWidth().then(if (after) Modifier.background(MaterialTheme.colorScheme.outlineVariant) else Modifier))
            }
            badge()
        }
        Column(Modifier.weight(1f)) { Text(label); if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium) }
        Text(time, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun HistoryExport(vm: WakeViewModel, choose: (HistoryRange, String) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("全期間") }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var from by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var to by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val range = runCatching {
        when (mode) {
            "月指定" -> HistoryRange.month(YearMonth.parse(month))
            "日付範囲" -> HistoryRange(LocalDate.parse(from), LocalDate.parse(to))
            else -> HistoryRange()
        }
    }.getOrNull()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("初回鳴動の日付で絞り込みます。")
        for (choice in listOf("全期間", "月指定", "日付範囲")) {
            FilterChip(mode == choice, { mode = choice }, label = { Text(choice) })
        }
        if (mode == "月指定") OutlinedTextField(month, { month = it }, label = { Text("対象月（YYYY-MM）") }, singleLine = true)
        if (mode == "日付範囲") {
            OutlinedTextField(from, { from = it }, label = { Text("開始日（YYYY-MM-DD）") }, singleLine = true)
            OutlinedTextField(to, { to = it }, label = { Text("終了日（YYYY-MM-DD）") }, singleLine = true)
        }
        if (range == null) Text("日付の形式と開始・終了の順番を確認してください。")
        Button(enabled = range != null && !vm.exportBusy, onClick = {
            val selected = range ?: return@Button
            val suffix = when (mode) { "月指定" -> month; "日付範囲" -> "${from}_$to"; else -> "all" }
            vm.exportNotice = ""
            choose(selected, "wakelock_history_$suffix.csv")
        }) { Text(if (vm.exportBusy) "保存中…" else "保存先を選ぶ") }
        if (vm.exportNotice.isNotEmpty()) Text(vm.exportNotice)
    }
}
