package jp.wakelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlin.math.roundToInt

private class SoundPreview(context: Context, scope: CoroutineScope) {
    private val player = alarmPlayer(context, scope)
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    var playing by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun start(sound: AlarmSound) {
        if (playing) return
        message = null
        lateinit var focus: AudioFocusRequest
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { if (request === focus && it < 0) stop() }.build()
        if (runCatching { manager.requestAudioFocus(focus) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            message = "今は音声を使用できません。少し待ってから試してください。"
            return
        }
        request = focus
        playing = true
        player.start(sound.uri, onFallback = { message = "音源を読み込めないため、代わりのアラーム音で試聴します。" }) {
            stop()
            message = "音を再生できませんでした。別の音源を選んでください。"
        }
    }

    fun stop() {
        player.stop()
        playing = false
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }

    fun selectionChanged() {
        stop()
        message = null
    }
}

private fun registeredSounds(context: Context): List<AlarmSound> {
    val manager = RingtoneManager(context)
    manager.setType(RingtoneManager.TYPE_ALARM)
    return manager.cursor.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val uri = manager.getRingtoneUri(cursor.position) ?: continue
                add(AlarmSound(uri.toString(), cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: "アラーム音"))
            }
        }
    }
}

@Composable
internal fun SoundSettings(sound: AlarmSound, select: (Uri?, Boolean) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val preview = remember(context, scope) { SoundPreview(context, scope) }
    LaunchedEffect(sound) { preview.selectionChanged() }
    val control = remember(context) { volumeControl(context) }
    var volume by remember { mutableStateOf<AlarmVolume?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showList by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<AlarmSound>?>(null) }
    fun refreshVolume() {
        volume = runCatching { control.current() }.getOrNull()
        dragging = null
    }
    DisposableEffect(owner, preview, control) {
        refreshVolume()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshVolume()
            if (event == Lifecycle.Event.ON_PAUSE) preview.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            preview.stop()
        }
    }

    fun choose(uri: Uri?, document: Boolean = false) {
        preview.stop()
        error = null
        select(uri, document)
        showList = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            if (uri != null && uri.scheme == "content") choose(uri)
            else showList = true
        }
    }
    val openLocalAudio = remember {
        object : ActivityResultContracts.OpenDocument() {
            override fun createIntent(context: Context, input: Array<String>) =
                super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }
    val document = rememberLauncherForActivityResult(openLocalAudio) { uri ->
        if (uri != null) choose(uri, true)
    }
    Heading("アラーム音")
    Text("現在のアラーム音", style = MaterialTheme.typography.labelLarge)
    Text(sound.name, style = MaterialTheme.typography.headlineSmall)
    BigButton("端末のアラーム音から選ぶ") {
        preview.stop()
        error = null
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, default)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound.uri?.toUri() ?: default)
        try {
            picker.launch(intent)
        } catch (_: RuntimeException) {
            showList = true
        }
    }
    OutlinedButton(onClick = {
        preview.stop()
        try {
            document.launch(arrayOf("audio/*"))
        } catch (_: RuntimeException) {
            error = "この端末ではファイル選択を開けません。端末のアラーム音を選んでください。"
        }
    }) { Text("音声ファイルから選ぶ") }
    if (sound.uri != null) TextButton(onClick = { choose(null) }) { Text("端末のデフォルトに戻す") }

    BigButton(if (preview.playing) "停止" else "試聴") {
        if (preview.playing) preview.stop() else preview.start(sound)
    }
    preview.message?.let { Text(it) }

    HorizontalDivider()
    Text("アラーム音量", style = MaterialTheme.typography.titleLarge)
    Text("この設定は端末全体のアラーム音量に反映されます")
    val level = volume
    if (level != null) {
        val fraction = dragging ?: level.fraction
        Text("${(fraction * 100).roundToInt()}%")
        Slider(value = fraction, onValueChange = { dragging = it }, enabled = level.adjustable,
            steps = (level.max - level.min - 1).coerceIn(0, 100),
            onValueChangeFinished = {
                dragging?.let {
                    try {
                        volume = control.change(it)
                        error = null
                    } catch (_: RuntimeException) {
                        refreshVolume()
                        error = "音量を変更できません。端末の音量設定やおやすみモードを確認してください。"
                    }
                }
                dragging = null
            })
        if (!level.adjustable) Text("この端末ではアラーム音量が固定されています。")
        if (level.low) Text("アラーム音量が小さいか、ミュートされています。", color = MaterialTheme.colorScheme.error)
    } else Text("端末のアラーム音量を取得できませんでした。")
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    if (showList) {
        LaunchedEffect(Unit) {
            choices = withContext(Dispatchers.IO) { runCatching { registeredSounds(context) }.getOrDefault(emptyList()) }
        }
        AlertDialog(onDismissRequest = { showList = false }, title = { Text("端末のアラーム音") },
            text = {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    item { TextButton(onClick = { choose(null) }) { Text("端末のデフォルト") } }
                    items(choices.orEmpty()) { tone ->
                        TextButton(onClick = { choose(tone.uri?.toUri()) }) { Text(tone.name) }
                    }
                    if (choices?.isEmpty() == true) item { Text("取得できる登録音がありません。音声ファイルも選べます。") }
                }
            }, confirmButton = { TextButton(onClick = { showList = false }) { Text("閉じる") } })
    }
}
