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
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
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
    private val qrViewModel: QrViewModel by viewModels()
    private var resumeCount by mutableIntStateOf(0)
    private var pendingExport: HistoryRange? = null
    private val csvDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val range = pendingExport
        pendingExport = null
        if (uri != null && range != null) vm.exportCsv(uri, range)
    }

    private fun chooseCsv(range: HistoryRange, filename: String) {
        pendingExport = range
        try {
            csvDocument.launch(filename)
        } catch (_: RuntimeException) {
            pendingExport = null
            vm.exportNotice = "保存先を開けませんでした。"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingExport?.let {
            outState.putBoolean("csv.pending", true)
            outState.putString("csv.from", it.from?.toString())
            outState.putString("csv.through", it.through?.toString())
        }
    }
    override fun onResume() {
        super.onResume()
        resumeCount++
        vm.refresh()
    }
    override fun onStop() {
        if (!isChangingConfigurations) vm.result.dismiss()
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN || intent.action == Alerts.OPEN_ALARM) {
            vm.result.dismiss()
            vm.page = "home"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("csv.pending") == true) {
            pendingExport = HistoryRange(savedInstanceState.getString("csv.from")?.let(LocalDate::parse),
                savedInstanceState.getString("csv.through")?.let(LocalDate::parse))
        }
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
        var page by vm::page
        var soundTarget by rememberSaveable { mutableStateOf<String?>(null) }
        var expanded by rememberSaveable { mutableStateOf<String?>(null) }
        var adding by rememberSaveable { mutableStateOf(false) }
        var scanning by rememberSaveable { mutableStateOf<String?>(null) }
        var abortDialog by rememberSaveable(state.generation) { mutableStateOf(false) }
        val complete = vm.result.visible(state)
        val register = !active && page == "qr"
        val scanStep = StepId(state.generation, if (phase == Phase.INITIAL_ALARM) Phase.AWAITING_BATHROOM_QR else phase)
        val scanRoute = "auth/${scanStep.generation}/${scanStep.phase.name}"
        val validPlace = if (register) Place.entries.find { scanning == "reg" + it.name }
            else phase.place?.takeIf { scanning == scanRoute }
        LaunchedEffect(active) { if (active) { page = "home"; adding = false } }
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
                complete -> vm.result.dismiss()
                else -> page = "home"
            }
        }
        BackHandler(scanning != null || complete || (!active && page != "home")) { back() }

        val skippedAt = state.alarms.map { it.skipAt }.filter { it > 0 }.minOrNull()
        LaunchedEffect(skippedAt, resumeCount) {
            if (skippedAt != null) {
                delay((skippedAt - System.currentTimeMillis()).coerceAtLeast(0))
                vm.refresh()
            }
        }
        if (!active && !complete && page == "history") {
            HistoryScreen(vm, ::chooseCsv) { page = "home" }
            return
        }
        val showList = !active && !complete && page == "home"
        Box(Modifier.fillMaxSize()) {
            key(phase, page, validPlace) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (showList && vm.exportNotice.isNotEmpty()) Text(vm.exportNotice)
                    if (message != null) Text(message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (BuildConfig.DEBUG && state.timing == Timing.SHORT)
                        Text("デバッグ短時間モード", color = MaterialTheme.colorScheme.tertiary)
                    when {
                        validPlace != null -> {
                            Heading(if (register) "${validPlace.title}を登録" else "${validPlace.title}を認証")
                            if (cameraGranted) {
                                QrScanner(this@MainActivity, validPlace) { raw ->
                                    scanning = null
                                    if (register) vm.edit { it.register(validPlace, raw) } else vm.verify(scanStep, raw)
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
                            state.owner?.label?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                            Text("STEP ${phase.step} / 4　${phase.label}", style = MaterialTheme.typography.titleLarge)
                            Text(localTime(now).format(DateTimeFormatter.ofPattern("HH:mm")), style = MaterialTheme.typography.displayLarge)
                            Heading(phase.prompt)
                            LinearProgressIndicator(progress = { (phase.step - 1) / 4f }, modifier = Modifier.fillMaxWidth())
                            if (state.escalated) Text("認証を待っています", color = MaterialTheme.colorScheme.error)
                            BigButton("${phase.place?.title}のQRコードを読む") {
                                vm.edit(onSaved = { scanning = scanRoute }) {
                                    if (it.stepId != state.stepId) throw InputError("認証の段階が変わりました。")
                                    it.beginScan()
                                }
                            }
                            if (phase == Phase.INITIAL_ALARM && !state.snoozeUsed) {
                                OutlinedButton(onClick = { vm.edit { it.snooze(state.stepId, System.currentTimeMillis()) } }) {
                                    Text("5分スヌーズ（1回）")
                                }
                            }
                        }
                        phase == Phase.SNOOZED -> {
                            Heading("スヌーズ中")
                            Text("${stamp(state.deadline)} にもう一度鳴ります。次は洗面所のQR認証です。")
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
                        page == "sound" -> {
                            val defaultSound by vm.sound.collectAsStateWithLifecycle()
                            val card = state.alarms.find { it.id == soundTarget }
                            val current = if (soundTarget == null) defaultSound else card?.sound
                            Text(card?.label?.ifBlank { clockText(card.minute) } ?: "新規アラームの初期設定")
                            current?.let { SoundSettings(it) { uri, document -> vm.selectSound(uri, document, soundTarget) } }
                            TextButton(onClick = { back() }) { Text("アラーム一覧に戻る") }
                        }
                        register -> {
                            QrManagement(this@MainActivity, state, qrViewModel, scan = { p ->
                                vm.message.value = null
                                scanning = "reg" + p.name
                            }, home = { back() })
                        }
                        else -> {
                            Heading("アラーム")
                            val next = state.nextCard
                            Text(if (next == null) "次のアラームはありません" else "次のアラーム　${stamp(next.due)}")
                            if (next != null && !controller.exact) Text("予約は権限の許可待ちです。", color = MaterialTheme.colorScheme.error)
                            if (state.alarms.isEmpty()) Text("＋でアラームを追加できます。")
                            if (Place.entries.any { !state.registered(it) }) Text("アラームをオンにするには2地点のQRを登録してください。")
                            state.alarms.sortedWith(compareBy(ClockAlarm::minute, ClockAlarm::id)).forEach { card ->
                                key(card.id) {
                                    AlarmCard(card, expanded == card.id, expand = { expanded = if (expanded == card.id) null else card.id },
                                        edit = { change -> vm.edit { it.modifyCard(card.id, change) } },
                                        sound = { vm.message.value = null; soundTarget = card.id; page = "sound" },
                                        remove = { vm.removeAlarm(card.id) }, skip = { at, undo ->
                                        vm.edit { it.skipOccurrence(card.id, at, undo, System.currentTimeMillis(), ZoneId.systemDefault()) }
                                    })
                                }
                            }
                            OutlinedButton(onClick = { vm.message.value = null; soundTarget = null; page = "sound" }) {
                                Text("初期のアラーム音・端末音量")
                            }
                            BigButton("QRコード管理") { vm.message.value = null; qrViewModel.notice = ""; page = "qr" }
                            if (BuildConfig.DEBUG) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("短時間テスト（30秒・20秒）")
                                    Switch(state.timing == Timing.SHORT, { on ->
                                        vm.edit { it.setTiming(if (on) Timing.SHORT else Timing.NORMAL) }
                                    }, enabled = state.alarms.none { it.enabled })
                                }
                            }
                            Text(Place.entries.joinToString("　") { it.title + if (state.registered(it)) " ✓" else " 未登録" })
                            if (state.lastAbort != 0L) Text("直近の緊急停止　${stamp(state.lastAbort)}")
                            BigButton("起床履歴") { page = "history" }
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
                    if (showList) Spacer(Modifier.height(76.dp))
                }
            }
            if (showList) FloatingActionButton(onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp)
                    .semantics { contentDescription = "アラームを追加" }) {
                Text("＋", style = MaterialTheme.typography.headlineMedium)
            }
        }
        if (adding && showList) AlarmTimeDialog(420, { adding = false }) { minute ->
            adding = false
            vm.addAlarm(minute) { expanded = it }
        }
        if (active && abortDialog) AlertDialog(
            onDismissRequest = { abortDialog = false }, title = { Text("起床確認を中止しますか？") },
            text = { Text("この回の音と起床確認を停止します。他のアラームと次回の繰り返し予約は有効です。") },
            confirmButton = { TextButton(onClick = {
                abortDialog = false
                scanning = null
                vm.edit {
                    if (it.generation != state.generation) throw InputError("別のアラームが始まりました。")
                    it.abort(System.currentTimeMillis())
                }
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
