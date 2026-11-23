package jp.wakelock

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

internal fun localTime(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
internal fun stamp(ms: Long) = if (ms == 0L) "未設定" else localTime(ms).format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"))

class MainActivity : ComponentActivity() {
    private val vm: WakeViewModel by viewModels()
    private var resumeCount by mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        resumeCount++
        vm.refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9DD6C6))
                else lightColorScheme(primary = Color(0xFF146453))
            MaterialTheme(colorScheme = colors) {
                Surface(Modifier.fillMaxSize()) { Content() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun lockScreen(active: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(active)
            setTurnScreenOn(active)
        } else {
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (active) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    private fun openSettings(action: String, packageUri: Boolean = true) {
        val intent = Intent(action)
        if (packageUri) intent.data = "package:$packageName".toUri()
        else intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    @Composable
    private fun Content() {
        val loaded by vm.state.collectAsStateWithLifecycle()
        val state = loaded ?: return
        val message by vm.message.collectAsStateWithLifecycle()
        val phase = state.phase
        val active = phase.active
        val controller = vm.controller
        var page by rememberSaveable { mutableStateOf("home") }
        var scanning by rememberSaveable { mutableStateOf<String?>(null) }
        var abortDialog by rememberSaveable { mutableStateOf(false) }
        var seenCompletion by rememberSaveable { mutableLongStateOf(0L) }
        val complete = phase == Phase.COMPLETED && state.finished != seenCompletion
        val register = !active && page == "qr"
        val scanStep = StepId(state.generation, if (phase == Phase.INITIAL_ALARM) Phase.AWAITING_BATHROOM_QR else phase)
        val scanRoute = "auth/${scanStep.generation}/${scanStep.phase.name}"
        val validPlace = if (register) Place.entries.find { scanning == "reg" + it.name }
            else phase.place?.takeIf { scanning == scanRoute }
        LaunchedEffect(active) { if (active) page = "home" }
        LaunchedEffect(scanning, validPlace) { if (validPlace == null) scanning = null }
        LaunchedEffect(phase) {
            lockScreen(phase.ringing)
        }
        var permissionTick by remember { mutableIntStateOf(0) }
        val cameraGranted = remember(resumeCount, permissionTick) {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        val notifyGranted = remember(resumeCount, permissionTick) {
            Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionTick++ }
        fun back() {
            vm.message.value = null
            when {
                scanning != null -> scanning = null
                complete -> seenCompletion = state.finished
                else -> page = "home"
            }
        }
        BackHandler(scanning != null || complete || (!active && page != "home")) { back() }

        key(phase, page, validPlace) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (message != null) Text(message.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (BuildConfig.DEBUG && state.timing == Timing.SHORT)
                    Text("デバッグ短時間モード", color = MaterialTheme.colorScheme.tertiary)
                when {
                    validPlace != null -> {
                        Heading(if (register) "${validPlace.title}を登録" else "${validPlace.title}を認証")
                        if (cameraGranted) {
                            QrScanner(this@MainActivity, validPlace) { raw ->
                                scanning = null
                                vm.edit { if (register) it.register(validPlace, raw) else it.verify(scanStep, raw, System.currentTimeMillis()) }
                            }
                        } else {
                            Text("QRコードの読み取りにはカメラの許可が必要です。")
                            BigButton("カメラを許可") { camera.launch(Manifest.permission.CAMERA) }
                            TextButton(onClick = { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) { Text("アプリの権限設定を開く") }
                        }
                        OutlinedButton(onClick = { back() }) { Text("読み取りを閉じる") }
                    }
                    phase.ringing -> {
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                        Text("STEP ${phase.step} / 4　${phase.label}", style = MaterialTheme.typography.titleLarge)
                        Text(localTime(now).format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.displayLarge)
                        Heading(phase.prompt)
                        LinearProgressIndicator(progress = { (phase.step - 1) / 4f }, modifier = Modifier.fillMaxWidth())
                        if (state.escalated) Text("認証を待っています", color = MaterialTheme.colorScheme.error)
                        BigButton("${phase.place?.title}のQRコードを読む") {
                            vm.edit(onSaved = { scanning = scanRoute }) { it.beginScan() }
                        }
                    }
                    phase.waiting -> {
                        val now by produceState(System.currentTimeMillis(), state.deadline) {
                            while (true) { value = System.currentTimeMillis(); delay(500) }
                        }
                        val target = if (phase == Phase.WAITING_FIRST_CHALLENGE) Place.KITCHEN else Place.BATHROOM
                        Heading(phase.label)
                        Text("次の確認まで " + durationText((state.deadline - now + 999).coerceAtLeast(0)),
                            style = MaterialTheme.typography.headlineMedium)
                        LinearProgressIndicator(progress = { phase.step / 4f }, modifier = Modifier.fillMaxWidth())
                        Text(if (controller.exact) "次は${target.title}のQR認証です。画面を閉じても起床確認は続きます。"
                            else "予約は保留中です。下の設定から正確なアラームを許可してください。")
                    }
                    complete -> {
                        Heading("起床プロトコル完了")
                        Text("アラーム開始　${stamp(state.started)}")
                        Text("初回2地点認証　${stamp(state.qrDone)}")
                        Text("起床確認・キッチン　${stamp(state.firstDone)}")
                        Text("最終確認・洗面所　${stamp(state.finished)}")
                        Text("全体の所要時間　" + if (state.finished >= state.started) durationText(state.finished - state.started) else "端末時刻変更のため不明")
                        BigButton("ホームへ") { back() }
                    }
                    page == "settings" -> {
                        AlarmSettings(this@MainActivity, state, save = { at ->
                            vm.edit(onSaved = { page = "home" }) { it.schedule(at, true, System.currentTimeMillis()) }
                        })
                        TextButton(onClick = { back() }) { Text("ホームに戻る") }
                    }
                    page == "sound" -> {
                        val sound by vm.sound.collectAsStateWithLifecycle()
                        sound?.let { SoundSettings(it, vm::selectSound) }
                        TextButton(onClick = { back() }) { Text("ホームに戻る") }
                    }
                    register -> {
                        Heading("QRコード登録")
                        Place.entries.forEach { p ->
                            Text("${p.title}　" + if (state.registered(p)) "登録済み" else "未登録")
                            BigButton(if (state.registered(p)) "${p.title}のQRを変更" else "${p.title}のQRを登録") {
                                vm.message.value = null
                                scanning = "reg" + p.name
                            }
                        }
                        TextButton(onClick = { back() }) { Text("ホームに戻る") }
                    }
                    else -> {
                        Heading("WakeLock")
                        Text("起床プロトコル　${phase.label}")
                        Text("次のアラーム", style = MaterialTheme.typography.titleMedium)
                        Text(if (state.enabled) stamp(state.date) else "予約なし", style = MaterialTheme.typography.headlineMedium)
                        if (state.enabled && !controller.exact) Text("予約は権限の許可待ちです。", color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("アラーム " + if (state.enabled) "オン" else "オフ")
                            Switch(state.enabled, { on ->
                                if (on && state.date <= System.currentTimeMillis()) page = "settings"
                                else vm.edit { it.schedule(it.date, on, System.currentTimeMillis()) }
                            })
                        }
                        BigButton("アラームの日時を設定") { vm.message.value = null; page = "settings" }
                        BigButton("アラーム音・音量の設定") { vm.message.value = null; page = "sound" }
                        BigButton("QRコード登録") { vm.message.value = null; page = "qr" }
                        if (BuildConfig.DEBUG) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("短時間テスト（30秒・20秒）")
                                Switch(state.timing == Timing.SHORT, { on ->
                                    vm.edit { it.setTiming(if (on) Timing.SHORT else Timing.NORMAL) }
                                }, enabled = !state.enabled)
                            }
                        }
                        Text(Place.entries.joinToString("　") { it.title + if (state.registered(it)) " ✓" else " 未登録" })
                        if (state.lastAbort != 0L) Text("直近の緊急停止　${stamp(state.lastAbort)}")
                        if (phase == Phase.COMPLETED) TextButton(onClick = { seenCompletion = 0 }) { Text("起床確認結果を見る") }
                    }
                }
                if (active) {
                    OutlinedButton(onClick = { abortDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("緊急停止") }
                }
                if ((!active && page == "home" && !complete) || (active && (!controller.exact || !controller.alerts.notifications))) {
                    HorizontalDivider()
                    Text("権限の状態", style = MaterialTheme.typography.titleLarge)
                    PermissionLine("正確なアラーム", controller.exact) {
                        if (Build.VERSION.SDK_INT >= 31) openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    }
                    PermissionLine("通知", controller.alerts.notifications) {
                        if (!notifyGranted && Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, false)
                    }
                    if (!notifyGranted && Build.VERSION.SDK_INT >= 33)
                        TextButton(onClick = { notify.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("通知を許可") }
                    PermissionLine("ロック画面の全画面通知", controller.alerts.fullScreen) {
                        if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    }
                    PermissionLine("カメラ", cameraGranted) { openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }
                }
            }
        }
        if (active && abortDialog) AlertDialog(
            onDismissRequest = { abortDialog = false }, title = { Text("起床確認を中止しますか？") },
            text = { Text("音と予約を停止し、緊急停止として記録します。") },
            confirmButton = { TextButton(onClick = {
                abortDialog = false
                scanning = null
                vm.edit { it.abort(System.currentTimeMillis()) }
            }) { Text("緊急停止する") } },
            dismissButton = { TextButton(onClick = { abortDialog = false }) { Text("続ける") } }
        )
    }
}

@Composable
fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineLarge)

private fun durationText(ms: Long): String {
    val seconds = ms / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
fun BigButton(text: String, action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PermissionLine(name: String, allowed: Boolean, settings: () -> Unit) {
    if (allowed) Text("$name　許可済み")
    else OutlinedButton(onClick = settings) { Text("$name　設定を開く") }
}

@Composable
private fun AlarmSettings(activity: Activity, state: WakeState, save: (Long) -> Unit) {
    val suggested = remember {
        if (state.date > System.currentTimeMillis()) localTime(state.date)
        else {
            val six = LocalDate.now().atTime(6, 0)
            if (six > LocalDateTime.now()) six else six.plusDays(1)
        }
    }
    var day by rememberSaveable { mutableLongStateOf(suggested.toLocalDate().toEpochDay()) }
    var minutes by rememberSaveable { mutableIntStateOf(suggested.hour * 60 + suggested.minute) }
    var warning by rememberSaveable { mutableStateOf("") }
    val date = LocalDate.ofEpochDay(day)
    val local = date.atStartOfDay().plusMinutes(minutes.toLong())
    Heading("アラーム設定")
    Text("現在の予約　" + if (state.enabled) stamp(state.date) else "なし")
    BigButton(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))) {
        DatePickerDialog(activity, { _, year, month, d -> day = LocalDate.of(year, month + 1, d).toEpochDay(); warning = "" },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    BigButton(local.format(DateTimeFormatter.ofPattern("HH:mm"))) {
        TimePickerDialog(activity, { _, hour, minute -> minutes = hour * 60 + minute; warning = "" },
            local.hour, local.minute, true).show()
    }
    Text("次回鳴動予定　" + local.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")))
    if (warning.isNotEmpty()) Text(warning, color = MaterialTheme.colorScheme.error)
    BigButton("保存してオンにする") {
        val zone = ZoneId.systemDefault()
        val at = local.atZone(zone).toInstant().toEpochMilli()
        when {
            zone.rules.getValidOffsets(local).isEmpty() -> warning = "夏時間の切り替えで存在しない時刻です。"
            at <= System.currentTimeMillis() -> warning = "過去の日時です。現在より後を選んでください。"
            else -> save(at)
        }
    }
}
