package jp.wakelock

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatted(at: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern(pattern, Locale.JAPAN).format(Instant.ofEpochMilli(at).atZone(zone))

internal val protocolPlaces = listOf(Place.BATHROOM, Place.KITCHEN, Place.KITCHEN, Place.BATHROOM)
internal val Place.symbol get() = if (this == Place.BATHROOM) Icons.Filled.WaterDrop else Icons.Filled.Coffee
internal val Place.steps get() = if (this == Place.BATHROOM) "ステップ 1 と 4" else "ステップ 2 と 3"
internal val Place.main: Color @Composable get() = if (this == Place.BATHROOM) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
internal val Place.onMain: Color @Composable get() = if (this == Place.BATHROOM) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary
internal val Place.container: Color @Composable get() = if (this == Place.BATHROOM) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
internal val Place.onContainer: Color @Composable get() = if (this == Place.BATHROOM) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer

@Composable
internal fun Badge(icon: ImageVector, bg: Color, fg: Color, large: Boolean = false) {
    Box(Modifier.size(if (large) 96.dp else 40.dp).background(bg,
        if (large) MaterialTheme.shapes.extraLarge else CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(if (large) 48.dp else 20.dp), tint = fg)
    }
}

@Composable
internal fun PlaceBadge(place: Place, large: Boolean = false) = Badge(place.symbol, place.container, place.onContainer, large)

@Composable
internal fun Tool(icon: ImageVector, label: String, click: () -> Unit) {
    IconButton(onClick = click) { Icon(icon, label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Bar(title: String, back: (() -> Unit)? = null, close: Boolean = false,
                 actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Text(title) }, navigationIcon = {
        if (back != null) Tool(if (close) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
            if (close) "閉じる" else "戻る", back)
    }, actions = actions, windowInsets = WindowInsets(0))
}

private data class Tab(val key: String, val title: String, val filled: ImageVector, val outline: ImageVector)
private val tabs = listOf(Tab("home","アラーム", Icons.Filled.Alarm, Icons.Outlined.Alarm),
    Tab("history","履歴", Icons.Filled.History, Icons.Outlined.History),
    Tab("qr","QRコード", Icons.Filled.QrCode, Icons.Outlined.QrCode),
    Tab("settings","設定", Icons.Filled.Settings, Icons.Outlined.Settings))

@Composable
internal fun Tabs(page: String, select: (String) -> Unit) {
    NavigationBar(windowInsets = WindowInsets(0), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        tabs.forEach { tab ->
            NavigationBarItem(selected = page == tab.key, onClick = { select(tab.key) },
                icon = { Icon(if (page == tab.key) tab.filled else tab.outline, null) }, label = { Text(tab.title) })
        }
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp), content = content)
    }
}

@Composable
internal fun Section(text: String) {
    Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ProtocolTrack(done: Int, modifier: Modifier = Modifier, timing: Timing = Timing.NORMAL, compact: Boolean = false, reached: List<Boolean>? = null) {
    val cs = MaterialTheme.colorScheme
    val next = protocolPlaces.getOrNull(done)
    val description = "起床プロトコル ${reached?.count { it } ?: done}/4 完了" +
        if (next != null && reached == null) "。次は${next.title}" else ""
    Row(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 0.dp)) {
        protocolPlaces.forEachIndexed { index, place ->
            if (compact) {
                val completed = reached?.get(index) ?: (index < done)
                val color = when { completed -> place.main; index == done -> place.container; else -> cs.surfaceContainerHighest }
                Spacer(Modifier.weight(1f).height(4.dp).background(color, CircleShape))
            } else {
                val current = index == done
                val bg = when { current -> place.main; index < done -> place.container; else -> Color.Transparent }
                val fg = when { current -> place.onMain; index < done -> place.onContainer; else -> cs.onSurfaceVariant }
                Column(Modifier.widthIn(max = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val border = if (index > done) Modifier.border(1.dp, cs.outline, CircleShape) else Modifier
                    Box(Modifier.size(40.dp).then(border).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(when { current -> place.symbol; index < done -> Icons.Filled.Check; else -> Icons.Outlined.Lock },
                            null, Modifier.size(20.dp), tint = fg)
                    }
                    Text(place.title, style = MaterialTheme.typography.labelMedium)
                }
                if (index < 3) {
                    val color = when { index + 1 == done -> protocolPlaces[index + 1].main; index < done -> cs.onSurfaceVariant; else -> cs.outlineVariant }
                    val intervals = if (timing == Timing.SHORT) listOf("すぐ","30秒","20秒") else listOf("すぐ","10分","5分")
                    Column(Modifier.weight(listOf(1f,3f,2f)[index]), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(intervals[index], style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(2.dp).background(color, CircleShape))
                    }
                }
            }
        }
    }
}
