package jp.wakelock

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale

private val weekdays = listOf("日", "月", "火", "水", "木", "金", "土")
internal fun clockText(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmTimeDialog(initial: Int, close: () -> Unit, save: (Int) -> Unit) {
    val clock = rememberTimePickerState(initial / 60, initial % 60, is24Hour = true)
    var keyboard by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("アラーム時刻", style = MaterialTheme.typography.titleMedium)
                if (keyboard) TimeInput(clock) else TimePicker(clock, layoutType = TimePickerLayoutType.Vertical)
                TextButton(onClick = { keyboard = !keyboard }) { Text(if (keyboard) "時計盤を使う" else "数字で入力") }
                Row {
                    TextButton(onClick = close) { Text("キャンセル") }
                    Button(onClick = { save(clock.hour * 60 + clock.minute) }) { Text("決定") }
                }
            }
        }
    }
}

@Composable
internal fun AlarmCard(card: ClockAlarm, expanded: Boolean, expand: () -> Unit,
                       edit: ((ClockAlarm) -> ClockAlarm) -> Unit, sound: () -> Unit, remove: () -> Unit) {
    var dialog by rememberSaveable { mutableStateOf("") }
    val time = clockText(card.minute)
    val repeat = weekdays.filterIndexed { day, _ -> card.days and (1 shl day) != 0 }.joinToString("・").ifEmpty { "1回のみ" }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "詳細を閉じる" else "詳細を開く", onClick = expand)
            .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(time, style = MaterialTheme.typography.displaySmall)
                if (card.label.isNotBlank()) Text(card.label, style = MaterialTheme.typography.titleMedium)
                Text(repeat)
            }
            Switch(card.enabled, { value -> edit { it.copy(enabled = value) } },
                modifier = Modifier.semantics { contentDescription = "${card.label.ifBlank { time }}のアラーム" })
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HorizontalDivider()
            TextButton(onClick = { dialog = "time" }) { Text("時刻を変更　$time") }
            Text("繰り返し", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                weekdays.forEachIndexed { index, day ->
                    val mask = 1 shl index
                    val checked = card.days and mask != 0
                    val colors = MaterialTheme.colorScheme
                    Surface(selected = checked, onClick = { edit { it.copy(days = it.days xor mask) } },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "${day}曜日" },
                        color = androidx.compose.ui.graphics.Color.Transparent) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = if (checked) colors.primary else colors.surfaceContainerHighest) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Text(day) }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { dialog = "name" }) { Text("名前　" + card.label.ifBlank { "未設定" }) }
            TextButton(onClick = sound) { Text("アラーム音　" + card.sound.name) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("バイブレーション", Modifier.weight(1f))
                Switch(card.vibrate, { value -> edit { it.copy(vibrate = value) } },
                    modifier = Modifier.semantics { contentDescription = "バイブレーション" })
            }
            if (card.enabled && card.due > 0) Text("次回　" + stamp(card.due))
            TextButton(onClick = { dialog = "delete" }) { Text("削除", color = MaterialTheme.colorScheme.error) }
        }
    }
    when (dialog) {
        "time" -> AlarmTimeDialog(card.minute, { dialog = "" }) { minute ->
            dialog = ""
            edit { it.copy(minute = minute) }
        }
        "name" -> {
            var name by rememberSaveable { mutableStateOf(card.label) }
            AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("アラームの名前") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("名前") }) },
                confirmButton = { TextButton(onClick = { dialog = ""; edit { it.copy(label = name.trim()) } }) { Text("保存") } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("キャンセル") } })
        }
        "delete" -> AlertDialog(onDismissRequest = { dialog = "" },
            title = { Text("${card.label.ifBlank { time }}を削除しますか？") },
            confirmButton = { TextButton(onClick = { dialog = ""; remove() }) { Text("削除する") } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("キャンセル") } })
    }
}
