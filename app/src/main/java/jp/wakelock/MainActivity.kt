package jp.wakelock

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.*
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import jp.wakelock.ui.theme.WakeTheme
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
        enableEdgeToEdge()
        if (savedInstanceState?.getBoolean("csv.pending") == true) {
            pendingExport = HistoryRange(savedInstanceState.getString("csv.from")?.let(LocalDate::parse),
                savedInstanceState.getString("csv.through")?.let(LocalDate::parse))
        }
        setContent {
            WakeTheme {
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
        val defaultSound by vm.sound.collectAsStateWithLifecycle()
        val sound = defaultSound ?: return
        val message by vm.message.collectAsStateWithLifecycle()
        val phase = state.phase
        val active = phase.active
        val controller = vm.controller
        var page by vm::page
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        var newCard by rememberSaveable { mutableStateOf(false) }
        var scanning by rememberSaveable { mutableStateOf<String?>(null) }
        var abortDialog by rememberSaveable(state.generation) { mutableStateOf(false) }
        val complete = vm.result.visible(state)
        val register = !active && page == "qr"
        val scanStep = StepId(state.generation, if (phase == Phase.INITIAL_ALARM) Phase.AWAITING_BATHROOM_QR else phase)
        val scanRoute = "auth/${scanStep.generation}/${scanStep.phase.name}"
        val validPlace = if (register) Place.entries.find { scanning == "reg" + it.name }
            else phase.place?.takeIf { scanning == scanRoute }
        LaunchedEffect(active) { if (active) page = "home" }
        LaunchedEffect(scanning, validPlace) { if (validPlace == null) scanning = null }
        LaunchedEffect(phase) { lockScreen(phase.ringing) }
        var permissionTick by remember { mutableIntStateOf(0) }
        val cameraGranted = remember(resumeCount, permissionTick) {
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        val notifyGranted = remember(resumeCount, permissionTick) {
            Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        var cameraRequested by rememberSaveable { mutableStateOf(false) }
        var notifyRequested by rememberSaveable { mutableStateOf(false) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            cameraRequested = true
            permissionTick++
        }
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notifyRequested = true
            permissionTick++
        }
        val permissions = listOf(
            PermissionItem(Icons.Filled.AlarmOn, "正確なアラーム", controller.exact, "時刻どおりに予約できません") {
                if (Build.VERSION.SDK_INT >= 31) openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            },
            PermissionItem(Icons.Filled.Notifications, "通知", controller.alerts.notifications, "アラーム通知を表示できません") {
                if (!notifyGranted && Build.VERSION.SDK_INT >= 33 &&
                    (!notifyRequested || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                else openSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS, false)
            },
            PermissionItem(Icons.Filled.Fullscreen, "ロック画面の全画面通知", controller.alerts.fullScreen, "ロック中に画面を開けません") {
                if (Build.VERSION.SDK_INT >= 34) openSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            },
            PermissionItem(Icons.Filled.PhotoCamera, "カメラ", cameraGranted, "QRを読み取れません") {
                if (!cameraRequested || shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                    camera.launch(Manifest.permission.CAMERA)
                else openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
        )
        val permissionHelp: @Composable () -> Unit = {
            permissions.take(2).filterNot { it.allowed }.forEach {
                TextButton(it.request) { Text("${it.name}を許可する") }
            }
        }
        val skippedAt = state.alarms.map { it.skipAt }.filter { it > 0 }.minOrNull()
        LaunchedEffect(skippedAt, resumeCount) {
            if (skippedAt != null) { delay((skippedAt - System.currentTimeMillis()).coerceAtLeast(0)); vm.refresh() }
        }
        fun tab(next: String) { vm.message.value = null; page = next }
        fun back() {
            when {
                scanning != null -> scanning = null
                complete -> { vm.result.dismiss(); tab("home") }
                page == "sound" -> tab("settings")
                else -> tab("home")
            }
        }
        BackHandler(scanning != null || complete || (!active && page != "home")) { back() }
        val host = remember { SnackbarHostState() }
        LaunchedEffect(message, validPlace) {
            val text = message
            if (validPlace == null && text != null) {
                host.showSnackbar(text)
                if (vm.message.value == text) vm.message.value = null
            }
        }
        LaunchedEffect(vm.exportNotice, page, active) {
            val notice = vm.exportNotice
            if (!active && page != "history" && notice.isNotBlank()) {
                host.showSnackbar(notice)
                if (vm.exportNotice == notice) vm.exportNotice = ""
            }
        }
        val pageAlpha = remember { Animatable(1f) }
        LaunchedEffect(page) {
            if (!active && !complete) {
                pageAlpha.snapTo(0f)
                pageAlpha.animateTo(1f, tween(300))
            }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().graphicsLayer {
            alpha = if (active || complete || scanning != null) 1f else pageAlpha.value
        }) {
            when {
                validPlace != null -> key(scanning) {
                    var retry by remember { mutableIntStateOf(0) }
                    var notice by remember { mutableStateOf("") }
                    val invalid: (String) -> Unit = { explanation ->
                        vm.message.value = null
                        notice = when (explanation) {
                            "${validPlace.title}のQRコードを読み取ってください。" -> {
                                val other = if (validPlace == Place.BATHROOM) Place.KITCHEN else Place.BATHROOM
                                "これは${other.title}のQRです。${validPlace.title}のQRを読み取ってください。"
                            }
                            else -> explanation
                        }
                        retry++
                    }
                    if (cameraGranted) QrScanner(this@MainActivity, validPlace, if (active) phase.step else null,
                        retry, notice, { scanning = null }, if (active) ({ abortDialog = true }) else null) { raw ->
                        if (register) vm.edit(onSaved = { scanning = null }, onFailure = invalid) { it.register(validPlace, raw) }
                        else vm.verify(scanStep, raw, invalid)
                    } else Column {
                        Bar("${validPlace.title}のQR", { scanning = null }, true)
                        Column(Modifier.padding(16.dp)) {
                            Text("QRコードの読み取りにはカメラの許可が必要です。")
                            BigButton("カメラを許可") { camera.launch(Manifest.permission.CAMERA) }
                            TextButton({ openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) }) { Text("アプリの権限設定を開く") }
                            if (active) TextButton({ abortDialog = true }) { Text("緊急停止") }
                        }
                    }
                }
                phase.ringing -> RingScreen(state, scan = {
                    vm.edit(onSaved = { scanning = scanRoute }) {
                        if (it.stepId != state.stepId) throw InputError("認証の段階が変わりました。")
                        it.beginScan()
                    }
                }, snooze = { vm.edit { it.snooze(state.stepId, System.currentTimeMillis()) } },
                    stop = { abortDialog = true }, permissionHelp)
                phase.waiting -> WaitScreen(state, controller.exact, { abortDialog = true }, permissionHelp)
                phase == Phase.SNOOZED -> Column(Modifier.padding(16.dp)) {
                    Heading("スヌーズ中")
                    Text("${stamp(state.deadline)} にもう一度鳴ります。次は洗面所のQR認証です。")
                    TextButton({ abortDialog = true }) { Text("緊急停止") }
                    permissionHelp()
                }
                complete -> {
                    val rows by vm.historyRows.collectAsStateWithLifecycle(initialValue = emptyList())
                    DoneScreen(state, rows.find { it.generation == state.generation && it.ring == state.started && it.status == RunStatus.COMPLETED }, ::back)
                }
                page == "edit" && editingId != null -> key(editingId) {
                    val id = requireNotNull(editingId)
                    val card = state.alarms.find { it.id == id } ?: ClockAlarm(id = id, minute = 420, sound = sound,
                        enabled = Place.entries.all(state::registered))
                    var saving by remember { mutableStateOf(false) }
                    AlarmEditor(card, newCard, state.timing, ::back, save = { minute, label, days, vibrate, skipTarget, picked ->
                        if (!saving) {
                            saving = true
                            vm.edit(onFailure = { saving = false }, onSaved = {
                                if (picked != card.sound) vm.selectSound(picked.uri?.toUri(), picked.document, id)
                                tab("home")
                            }) { current ->
                                val old = current.alarms.find { it.id == id }
                                if (!newCard && old == null) throw InputError("アラームが削除されました。")
                                val now = System.currentTimeMillis()
                                val zone = ZoneId.systemDefault()
                                val updated = current.saveCard((old ?: card).copy(minute = minute, days = days,
                                    label = label, vibrate = vibrate), now, zone)
                                val saved = updated.alarms.first { it.id == id }
                                if (skipTarget != null && saved.skipAt != skipTarget) updated.skipOccurrence(id, skipTarget, false, now, zone)
                                else if (skipTarget == null && saved.skipAt > 0) updated.skipOccurrence(id, card.skipAt, true, now, zone)
                                else updated
                            }
                        }
                    }, remove = { vm.removeAlarm(id) { tab("home") } })
                }
                page == "sound" -> Column {
                    Bar("初期のアラーム音", ::back)
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoundSettings(sound) { uri, doc -> vm.selectSound(uri, doc) }
                    }
                }
                page == "history" -> HistoryScreen(vm, ::chooseCsv, ::tab)
                register -> {
                    val history by vm.historyRows.collectAsStateWithLifecycle(initialValue = emptyList())
                    QrManagement(this@MainActivity, state, qrViewModel,
                    scan = { p -> vm.message.value = null; scanning = "reg" + p.name }, tab = ::tab, history = history)
                }
                else -> Column {
                    Box(Modifier.weight(1f)) {
                        if (page == "settings") SettingsScreen(sound, state, permissions, { tab("sound") }, {
                            try { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
                            catch (_: android.content.ActivityNotFoundException) { tab("sound") }
                        }) { on -> vm.edit { it.setTiming(if (on) Timing.SHORT else Timing.NORMAL) } }
                        else AlarmHome(state, permissions, ::tab, edit = { card ->
                            editingId = card?.id ?: java.util.UUID.randomUUID().toString(); newCard = card == null; tab("edit")
                        }, toggle = { card, on -> vm.edit { it.modifyCard(card.id) { alarm -> alarm.copy(enabled = on) } } },
                            undo = { card -> vm.edit { it.skipOccurrence(card.id, card.skipAt, true, System.currentTimeMillis(), ZoneId.systemDefault()) } })
                    }
                    Tabs(page, ::tab)
                }
            }
            SnackbarHost(host, Modifier.align(Alignment.BottomCenter))
        }
        if (active && abortDialog) AlertDialog(onDismissRequest = { abortDialog = false },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("緊急停止しますか？") },
            text = { Text("アラームを止めて、今回は「緊急停止」として履歴に記録します。起床完了にはなりません。") },
            confirmButton = { TextButton(onClick = {
                abortDialog = false
                scanning = null
                vm.edit {
                    if (it.generation != state.generation) throw InputError("別のアラームが始まりました。")
                    it.abort(System.currentTimeMillis())
                }
            }) { Text("緊急停止する", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { abortDialog = false }) { Text("キャンセル") } })
    }
}

@Composable
fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineLarge)

@Composable
fun BigButton(text: String, action: () -> Unit) {
    Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
