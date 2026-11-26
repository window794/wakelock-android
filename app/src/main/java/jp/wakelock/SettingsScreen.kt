package jp.wakelock

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal data class PermissionItem(val icon: ImageVector, val name: String, val allowed: Boolean,
                                   val impact: String, val request: () -> Unit)

@Composable
internal fun SettingRow(icon: ImageVector, name: String, info: String? = null, click: (() -> Unit)? = null,
                        error: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(name) }, supportingContent = info?.let { { Text(it,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingContent = { Icon(icon, null) }, trailingContent = trailing,
        modifier = if (click == null) Modifier else Modifier.clickable(onClick = click))
}

@Composable
internal fun SettingsScreen(sound: AlarmSound, state: WakeState, permissions: List<PermissionItem>,
                            selectSound: () -> Unit, volume: () -> Unit, toggle: (Boolean) -> Unit) {
    Column {
        Bar("設定")
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section("アラーム")
            SettingRow(Icons.Filled.MusicNote, "初期のアラーム音", sound.name, selectSound) { Icon(Icons.Filled.ChevronRight, null) }
            SettingRow(Icons.AutoMirrored.Filled.VolumeUp, "端末のアラーム音量", "Androidの音量設定を開きます", volume) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }
            if (BuildConfig.DEBUG) {
                Section("テスト")
                SettingRow(Icons.Filled.Timer,"短時間テスト","待ち時間を30秒・20秒に短縮") {
                    Switch(state.timing == Timing.SHORT, toggle, enabled = state.alarms.none { it.enabled },
                        modifier = Modifier.semantics { contentDescription = "短時間テスト" })
                }
            }
            Section("権限")
            permissions.forEach { item ->
                SettingRow(item.icon, item.name, if (item.allowed) "許可済み" else "未許可 ・ ${item.impact}", error = !item.allowed) {
                    if (item.allowed) Icon(Icons.Filled.CheckCircle, "許可済み", tint = MaterialTheme.colorScheme.primary)
                    else FilledTonalButton(onClick = item.request) { Text("許可する") }
                }
            }
            Section("情報")
            SettingRow(Icons.Filled.Info, "バージョン", BuildConfig.VERSION_NAME)
            if (state.lastAbort > 0) SettingRow(Icons.Filled.History, "直近の緊急停止", stamp(state.lastAbort))
        }
    }
}
