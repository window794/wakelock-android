package jp.wakelock

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.Base64

// Bind a returning document picker or confirmation to its original credential across rotation.
// Saved UI state contains the fingerprint and place only.
private fun fingerprint(value: String): String = Base64.getEncoder().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))

@Composable
internal fun QrManagement(activity: Activity, state: WakeState, vm: QrViewModel,
                          scan: (Place) -> Unit, home: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<Place?>(null) }
    var confirm by rememberSaveable { mutableStateOf<Place?>(null) }
    var replaceByCamera by rememberSaveable { mutableStateOf<Place?>(null) }
    var confirmedFingerprint by rememberSaveable { mutableStateOf("") }
    var pendingPlace by rememberSaveable { mutableStateOf<Place?>(null) }
    var pendingFingerprint by rememberSaveable { mutableStateOf("") }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val place = pendingPlace
        pendingPlace = null
        if (uri != null && place != null) {
            if (fingerprint(state.token(place)) == pendingFingerprint) vm.save(place, state.qrPayload(place), uri)
            else vm.notice = "登録が変更されたため、保存するQRコードを選び直してください。"
        }
    }

    fun list() {
        selected = null
        vm.notice = ""
    }
    BackHandler(selected != null) { list() }
    if (vm.notice.isNotEmpty()) Text(vm.notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    val place = selected
    if (place == null) {
        Heading("QRコード管理")
        for (p in Place.entries) {
            val registered = state.registered(p)
            Text("${p.title}　" + if (registered) "登録済み" else "未登録",
                style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                vm.notice = ""
                selected = p
                if (!registered) vm.issue(p, "")
            }, enabled = !vm.busy) { Text(if (registered) "${p.title}のQRコードを表示" else "${p.title}のQRコードを生成") }
            OutlinedButton(onClick = {
                vm.notice = ""
                if (registered) replaceByCamera = p else scan(p)
            }, enabled = !vm.busy) {
                Text("${p.title}のQRをカメラで登録")
            }
            if (registered) TextButton(onClick = {
                confirmedFingerprint = fingerprint(state.token(p))
                confirm = p
            }, enabled = !vm.busy) { Text("${p.title}のQRコードを再発行") }
            HorizontalDivider()
        }
        TextButton(onClick = home) { Text("ホームに戻る") }
    } else {
        Heading("${place.title} QRコード")
        if (state.registered(place)) {
            DisposableEffect(activity) {
                val flag = WindowManager.LayoutParams.FLAG_SECURE
                val wasSecure = activity.window.attributes.flags and flag != 0
                activity.window.addFlags(flag)
                onDispose { if (!wasSecure) activity.window.clearFlags(flag) }
            }
            val payload = state.qrPayload(place)
            key(payload) {
                QrPreview(activity, place, payload, vm, save = {
                    pendingPlace = place
                    pendingFingerprint = fingerprint(state.token(place))
                    try {
                        createFile.launch(place.pngName)
                    } catch (_: RuntimeException) {
                        pendingPlace = null
                        vm.notice = "保存先を選ぶアプリを開けませんでした。"
                    }
                })
            }
        } else if (vm.busy) {
            Text("QRコードを登録しています…")
        }
        TextButton(onClick = { list() }) { Text("QRコード管理に戻る") }
    }
    replaceByCamera?.let { p ->
        AlertDialog(onDismissRequest = { replaceByCamera = null }, title = { Text("${p.title}のQRコードを読み取りますか？") },
            text = { Text("別のQRコードを登録すると、今のQRコードは使えなくなります。") },
            dismissButton = { TextButton(onClick = { replaceByCamera = null }) { Text("キャンセル") } },
            confirmButton = { TextButton(onClick = { replaceByCamera = null; scan(p) }) { Text("カメラを開く") } })
    }
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("${p.title}のQRコードを再発行しますか？") },
            text = { Text("現在使用しているQRコードは無効になります。印刷済みのQRコードも使用できなくなります。") },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("キャンセル") } },
            confirmButton = { TextButton(onClick = {
                confirm = null
                val token = state.token(p)
                if (token.isNotEmpty() && fingerprint(token) == confirmedFingerprint) {
                    selected = p
                    vm.issue(p, token)
                } else vm.notice = "登録状態が変わりました。もう一度確認してください。"
            }) { Text("再発行する") } })
    }
}

@Composable
private fun QrPreview(activity: Activity, place: Place, payload: String, vm: QrViewModel, save: () -> Unit) {
    var cardImage by remember { mutableStateOf<Pair<QrCard, Bitmap>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(payload) {
        try {
            cardImage = withContext(Dispatchers.Default) {
                val card = QrCard(place, payload)
                card to card.bitmap()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val ready = cardImage
    if (ready == null || vm.busy) {
        Text(if (failed) "QR画像を作成できませんでした。" else "QRコードを処理しています…")
        return
    }
    val (card, image) = ready
    Image(image.asImageBitmap(), "${place.title}に設置する印刷用QRコード",
        Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height))
    Text("このQRコードには認証情報が含まれます。画像や印刷物を受け取った人は複製できます。")
    Button(onClick = save, enabled = !sharing) { Text("PNG保存") }
    OutlinedButton(onClick = {
        sharing = true
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { qrShareIntent(activity, place, image) }
                activity.startActivity(intent)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                vm.notice = "共有画面を開けませんでした。"
            } finally {
                sharing = false
            }
        }
    }, enabled = !sharing) { Text(if (sharing) "共有用の画像を準備中…" else "共有") }
    OutlinedButton(onClick = {
        try {
            printQr(activity, card, image)
        } catch (_: RuntimeException) {
            vm.notice = "印刷を利用できません。PNG保存または共有をお使いください。"
        }
    }, enabled = !sharing) { Text("印刷") }
}
