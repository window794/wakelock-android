package jp.wakelock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.*
import java.time.format.DateTimeFormatter

private val exactTime = DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss")
private fun WakeHistory.display(at: Long?) =
    at?.let { exactTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.of(zone))) } ?: "未記録"
private fun duration(s: Long?) = s?.let { "${it / 60}分${it % 60}秒" } ?: "未確定"

@Composable
internal fun HistoryScreen(vm: WakeViewModel, choose: (HistoryRange, String) -> Unit, home: () -> Unit) {
    val rows by vm.historyRows.collectAsStateWithLifecycle(initialValue = null)
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var export by rememberSaveable { mutableStateOf(false) }
    val row = rows?.find { it.id == detail }
    fun back() {
        if (export || detail != null) { export = false; detail = null } else home()
    }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = { back() }) { Text(if (export || detail != null) "履歴一覧に戻る" else "ホームへ") }
        if (export) {
            HistoryExport(vm, choose)
        } else if (row != null) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Heading(row.name.ifBlank { "起床履歴" })
                Text("設定日時　" + row.display(row.scheduled))
                Text("記録のタイムゾーン　" + row.zone)
                HorizontalDivider()
                listOf("アラーム鳴動" to row.ring, "洗面所QR認証" to row.bathroom1, "キッチンQR認証" to row.kitchen1,
                    "キッチンQR再認証" to row.kitchen2, "洗面所QR最終認証" to row.bathroom2).forEach { (label, at) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(row.display(at))
                }
                HorizontalDivider()
                Text(row.status.title, style = MaterialTheme.typography.titleLarge)
                Text("終了日時　" + row.display(row.ended))
                Text("所要時間　" + duration(row.seconds))
                Text("スヌーズ　${row.snoozes}回　／　無応答の強化　" + (row.escalations?.let { "${it}回" } ?: "未記録"))
            }
        } else {
            Heading("起床履歴")
            OutlinedButton(onClick = { vm.exportNotice = ""; export = true }) { Text("CSVエクスポート") }
            if (vm.historyError != null) Text(vm.historyError.orEmpty())
            else if (rows == null) Text("読み込み中…")
            else if (rows!!.isEmpty()) Text("起床履歴はまだありません。")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows.orEmpty(), key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { detail = item.id }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(item.display(item.ring), style = MaterialTheme.typography.labelLarge)
                            Text(item.name.ifBlank { "名前なしのアラーム" }, style = MaterialTheme.typography.titleMedium)
                            Text("設定　" + item.display(item.scheduled))
                            Text("${item.status.title}　所要時間　" + duration(item.seconds))
                        }
                    }
                }
            }
        }
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
        Heading("CSVエクスポート")
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
