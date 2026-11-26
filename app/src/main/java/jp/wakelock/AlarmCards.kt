package jp.wakelock

import android.media.RingtoneManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

private val days = listOf("日","月","火","水","木","金","土")
private val weekOrder = listOf(1,2,3,4,5,6,0)
internal fun clockText(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

@Composable
internal fun AlarmHome(state: WakeState, permissions: List<PermissionItem>, select: (String) -> Unit,
                       edit: (ClockAlarm?) -> Unit, toggle: (ClockAlarm, Boolean) -> Unit, undo: (ClockAlarm) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val next = state.nextCard
    val now by produceState(System.currentTimeMillis(), next?.due) {
        while (true) { value = System.currentTimeMillis(); delay(1000L * 30) }
    }
    val missing = Place.entries.firstOrNull { !state.registered(it) }
    val permission = permissions.firstOrNull { !it.allowed }
    val ready = missing == null && permission == null
    Column {
        Bar("WakeLock")
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(0.dp))
                Panel {
                    Text("次のアラーム", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    val minutes = (((next?.due ?: now) - now + 59999) / 60000).coerceAtLeast(0)
                    Text(if (next == null) "アラームはありません" else "あと " +
                        (if (minutes >= 60) "${minutes / 60}時間" else "") + "${minutes % 60}分",
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                    next?.let { Text(listOf(formatted(it.due, "M/d（E）HH:mm"), it.label).filter(String::isNotBlank).joinToString(" ・ "),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                    Row(Modifier.fillMaxWidth().then(if (ready) Modifier else Modifier.clickable {
                        select(if (missing != null) "qr" else "settings")
                    }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ready) Icons.Filled.CheckCircle else Icons.Filled.Error, null,
                            Modifier.size(20.dp), tint = if (ready) cs.primary else cs.error)
                        Spacer(Modifier.width(8.dp))
                        Text(when { missing != null -> "${missing.title}のQRが未登録"; permission != null -> "${permission.name}が未許可"
                            else -> "準備完了：QR 2か所 ・ 権限 4/4" }, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.timing == Timing.SHORT) Text("短時間テスト中", style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
                }
                Spacer(Modifier.height(8.dp))
                Text("アラーム", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                state.alarms.sortedWith(compareBy<ClockAlarm> { !it.enabled }.thenBy { it.minute }.thenBy { it.id }).forEach { card ->
                    key(card.id) {
                        Panel(onClick = { edit(card) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(clockText(card.minute), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                                        color = if (card.enabled) cs.onSurface else cs.onSurfaceVariant)
                                    val repeat = if (card.days != 0) weekOrder.filter { card.days and (1 shl it) != 0 }.joinToString(" ") { days[it] }
                                        else if (card.due > 0) formatted(card.due, "M/d（E）") + "のみ" else "1回のみ"
                                    Text(listOf(card.label, repeat).filter(String::isNotBlank).joinToString(" ・ "), style = MaterialTheme.typography.bodyMedium)
                                }
                                Switch(card.enabled, { toggle(card, it) }, modifier = Modifier.semantics { contentDescription = "${card.label.ifBlank { clockText(card.minute) }}のアラーム" })
                            }
                            if (card.skipAt > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.EventBusy, null, Modifier.size(20.dp))
                                Text(" 次回 ${formatted(card.skipAt,"M/d（E）")}はスキップ", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { undo(card) }) { Text("取消") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
            FloatingActionButton(onClick = { edit(null) }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                Icon(Icons.Filled.Add, "アラームを追加")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmEditor(card: ClockAlarm, fresh: Boolean, timing: Timing, close: () -> Unit,
                         save: (Int, String, Int, Boolean, Long?, AlarmSound) -> Unit, remove: () -> Unit) {
    val clock = rememberTimePickerState(card.minute / 60, card.minute % 60, is24Hour = true)
    var dial by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(card.label) }
    var repeat by rememberSaveable { mutableIntStateOf(card.days) }
    var vibration by rememberSaveable { mutableStateOf(card.vibrate) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var uri by rememberSaveable { mutableStateOf(card.sound.uri) }
    var title by rememberSaveable { mutableStateOf(card.sound.name) }
    var document by rememberSaveable { mutableStateOf(card.sound.document) }
    val sound = AlarmSound(uri, title, document)
    val minute = clock.hour * 60 + clock.minute
    val unchanged = repeat == card.days && minute == card.minute
    val target = remember(card, minute, repeat) {
        when {
            !card.enabled || repeat == 0 -> null
            !fresh && unchanged && card.due > 0 -> card.skipAt.takeIf { it > 0 } ?: card.due
            else -> card.copy(minute = minute, days = repeat, skipAt = 0).next(System.currentTimeMillis(), ZoneId.systemDefault()).due
        }
    }
    var skip by rememberSaveable(minute, repeat) { mutableStateOf(unchanged && card.skipAt > 0) }
    val context = LocalContext.current
    LaunchedEffect(uri, document) {
        val selected = uri
        if (selected != null && selected != card.sound.uri) {
            title = withContext(Dispatchers.IO) { soundTitle(context, selected.toUri(), document) }
        }
    }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val preview = remember { SoundPreview(context, scope) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) preview.stop() }
        owner.lifecycle.addObserver(obs)
        onDispose { preview.stop(); owner.lifecycle.removeObserver(obs) }
    }
    BackHandler { if (choosing) choosing = false else close() }
    if (choosing) {
        Column {
            Bar("アラーム音", { choosing = false })
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SoundSettings(sound) { picked, doc ->
                    val selected = picked?.takeUnless { !doc && RingtoneManager.isDefault(it) }
                    uri = selected?.toString()
                    document = selected != null && doc
                    title = when (uri) { null -> AlarmSound().name; card.sound.uri -> card.sound.name; else -> "選択した音声" }
                    choosing = false
                }
            }
        }
        return
    }
    Column {
        Bar(if (fresh) "アラームを追加" else "アラームを編集", close, true) {
            TextButton(onClick = { save(minute, name.trim(), repeat, vibration, target.takeIf { skip }, sound) }) { Text("保存") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (dial) TimePicker(clock)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!dial) TimeInput(clock)
                    Tool(if (dial) Icons.Filled.Keyboard else Icons.Filled.Schedule,
                        if (dial) "数字で入力" else "時計盤を使う") { dial = !dial }
                }
            }
            TextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名前") })
            Spacer(Modifier.height(8.dp))
            Text("繰り返し", style = MaterialTheme.typography.titleSmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val rowWidth = maxOf(maxWidth, 336.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()).width(rowWidth), horizontalArrangement = Arrangement.SpaceBetween) {
                    weekOrder.forEach { i ->
                        val bit = 1 shl i
                        val selected = repeat and bit != 0
                        Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            OutlinedIconToggleButton(selected, { repeat = repeat xor bit },
                                modifier = Modifier.semantics { contentDescription = days[i] + "曜日"; stateDescription = if (selected) "選択中" else "未選択" },
                                colors = IconButtonDefaults.outlinedIconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primary,
                                    checkedContentColor = MaterialTheme.colorScheme.onPrimary)) { Text(days[i]) }
                        }
                    }
                }
            }
            if (target != null) {
                SettingRow(Icons.Filled.EventBusy, "次回だけスキップ", "${formatted(target, "M/d（E）")}の1回だけ鳴らしません") {
                    Switch(skip, { skip = it }, Modifier.semantics { contentDescription = "次回だけスキップ" })
                }
            }
            SettingRow(Icons.Filled.MusicNote,"アラーム音", sound.name, { preview.stop(); choosing = true }) {
                Tool(if (preview.playing) Icons.Filled.StopCircle else Icons.Filled.PlayCircle,
                    if (preview.playing) "試聴を停止" else "試聴") { if (preview.playing) preview.stop() else preview.start(sound) }
            }
            preview.message?.let { Text(it) }
            SettingRow(Icons.Filled.Vibration, "バイブレーション") {
                Switch(vibration, { vibration = it }, Modifier.semantics { contentDescription = "バイブレーション" })
            }
            HorizontalDivider()
            Text("起床プロトコル（全アラーム共通）", style = MaterialTheme.typography.titleSmall)
            ProtocolTrack(0, timing = timing)
            Text("4回すべて読み取ると起床完了。待ち時間は直前の読み取りから数えます。", style = MaterialTheme.typography.bodyMedium)
            if (!fresh) TextButton(onClick = { delete = true }) {
                Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                Text("このアラームを削除", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("${card.label.ifBlank { clockText(card.minute) }}を削除しますか？") },
        confirmButton = { TextButton(onClick = { delete = false; remove() }) { Text("削除する", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("キャンセル") } })
}
