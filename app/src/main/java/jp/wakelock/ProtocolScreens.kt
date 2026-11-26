package jp.wakelock

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun RingScreen(s: WakeState, scan: () -> Unit, snooze: () -> Unit, stop: () -> Unit, permissionHelp: @Composable () -> Unit) {
    val p = requireNotNull(s.phase.place)
    val step = s.phase.step
    val phase = listOf("フェーズ1 ・ 起床時刻です","フェーズ1 ・ 洗面所を確認しました",
        if (s.timing == Timing.SHORT) "フェーズ2 ・ 30秒後の再確認" else "フェーズ2 ・ 10分後の再確認", "フェーズ3 ・ 最終確認")
    val prompt = listOf("洗面所に行って\nQRコードを読み取ってください", "そのままキッチンへ。\n読み取るとアラームが止まります",
        "起きていますか？\nもう一度キッチンへ", "これで最後です。\n洗面所で読み取ると起床完了")
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Alarm, null, Modifier.size(20.dp))
                        Text("  ${formatted(s.date, "HH:mm")} ${s.owner?.label.orEmpty()}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("ステップ $step / 4", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(phase[step - 1], color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.escalated) Text("認証を待っています", color = MaterialTheme.colorScheme.error)
                }
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    PlaceBadge(p, large = true)
                    Text("${p.title}へ", style = MaterialTheme.typography.displayLarge, color = p.main)
                    Text(prompt[step - 1], style = MaterialTheme.typography.titleLarge)
                }
                Column {
                    permissionHelp()
                    ProtocolTrack(step - 1, timing = s.timing)
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        Button(scan, Modifier.fillMaxWidth().heightIn(min = 96.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.main, contentColor = p.onMain)) {
            Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Text("QRを読み取る", style = MaterialTheme.typography.headlineSmall)
        }
        if (s.phase == Phase.INITIAL_ALARM && !s.snoozeUsed) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(snooze, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Filled.Snooze,null); Text(" スヌーズ 5分（残り1回）") }
        }
        TextButton(stop, Modifier.align(Alignment.CenterHorizontally)) { Text("読み取れないとき（緊急停止）") }
    }
}

@Composable
internal fun WaitScreen(s: WakeState, exact: Boolean, stop: () -> Unit, permissionHelp: @Composable () -> Unit) {
    val done = s.phase.step
    val next = protocolPlaces[done]
    val interval = if (done == 2) s.timing.first else s.timing.final
    val remaining by produceState((s.deadline - System.currentTimeMillis()).coerceAtLeast(0L), s.deadline) {
        while (true) { value = (s.deadline - System.currentTimeMillis()).coerceAtLeast(0L); delay(1000) }
    }
    val seconds = (remaining + 999) / 1000
    val progress by animateFloatAsState((remaining.toFloat() / interval).coerceIn(0f,1f), tween(200), label = "残り時間")
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LockClock, null)
                Text(" 起床プロトコル進行中", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text("$done / 4 完了", style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp,Alignment.CenterVertically)) {
                Box(Modifier.size(240.dp).clearAndSetSemantics { contentDescription = "次の確認まで あと${(seconds + 59) / 60}分" }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), color = next.main,
                        strokeWidth = 8.dp, trackColor = MaterialTheme.colorScheme.secondaryContainer, strokeCap = StrokeCap.Round)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"))
                        Text("次の確認まで", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlaceBadge(next)
                        Column {
                            Text("次は ${next.title}", style = MaterialTheme.typography.titleLarge)
                            Text(if (exact) "${formatted(s.deadline, "HH:mm")} にアラームが鳴ります" else "正確なアラームの許可待ちです")
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProtocolTrack(done, timing = s.timing)
                if (exact) Text("アプリを閉じても大丈夫。時間になると自動で鳴ります。", style = MaterialTheme.typography.bodyMedium)
                permissionHelp()
                TextButton(stop) { Text("緊急停止") }
            }
        }
    }
}

@Composable
internal fun DoneScreen(s: WakeState, history: WakeHistory?, home: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val events = listOf(s.started, history?.bathroom1, s.qrDone, s.firstDone, s.finished)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(8.dp))
            Badge(Icons.Filled.Check, cs.primaryContainer, cs.onPrimaryContainer, true)
            Text("起床完了", style = MaterialTheme.typography.headlineLarge)
            Text("おはようございます。4回の確認が終わりました。")
            Panel { Row {
                Metric("完了時刻", formatted(s.finished,"HH:mm"), Modifier.weight(1f))
                Metric("所要時間", elapsedText((s.finished - s.started).takeIf { it >= 0 }?.div(1000)), Modifier.weight(1f))
            } }
            events.forEachIndexed { i, at ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (i == 0) Badge(Icons.Filled.Alarm, cs.secondaryContainer, cs.onSecondaryContainer) else PlaceBadge(protocolPlaces[i - 1])
                    Text(eventLabels[i], Modifier.weight(1f))
                    Text(at?.let { formatted(it,"HH:mm:ss") } ?: "未記録", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Button(home, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("ホームへ") }
        if (history != null) Text("起床履歴に保存しました", style = MaterialTheme.typography.bodySmall)
    }
}

internal val eventLabels = listOf("アラーム鳴動","① 洗面所","② キッチン","③ キッチン（再確認）","④ 洗面所（最終確認）")
internal fun elapsedText(seconds: Long?) = seconds?.let { "${it / 60}分${it % 60}秒" } ?: "未確定"

@Composable
internal fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
    }
}
