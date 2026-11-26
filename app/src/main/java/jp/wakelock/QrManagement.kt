package jp.wakelock

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QrManagement(activity: Activity, state: WakeState, vm: QrViewModel,
                          scan: (Place) -> Unit, tab: (String) -> Unit, history: List<WakeHistory> = emptyList()) {
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
    fun save(p: Place) {
        pendingPlace = p
        pendingFingerprint = fingerprint(state.token(p))
        try { createFile.launch(p.pngName) } catch (_: RuntimeException) {
            pendingPlace = null
            vm.notice = "保存先を選ぶアプリを開けませんでした。"
        }
    }
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    fun share(p: Place) {
        if (sharing) return
        val payload = state.qrPayload(p)
        sharing = true
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    val image = QrCard(p, payload).bitmap()
                    try { qrShareIntent(activity, p, image) } finally { image.recycle() }
                }
                activity.startActivity(intent)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { vm.notice = "共有画面を開けませんでした。" }
            finally { sharing = false }
        }
    }
    val place = selected
    Column(Modifier.fillMaxSize()) {
        Bar(place?.let { "${it.title}のQRコード" } ?: "QRコード", if (place != null) ::list else null)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.notice.isNotEmpty()) Text(vm.notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (place == null) {
                Text("家の2か所に貼って使います。色とアイコンは起床画面と同じです。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                for (p in Place.entries) key(p) {
                    val registered = state.registered(p)
                    var menu by remember { mutableStateOf(false) }
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PlaceBadge(p)
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                Text(p.steps, style = MaterialTheme.typography.bodyMedium)
                            }
                            Icon(if (registered) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(18.dp),
                                tint = if (registered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            Text(if (registered) "登録済み" else "未登録", style = MaterialTheme.typography.labelMedium)
                        }
                        val lastScan = remember(p, history) {
                            history.maxOfOrNull { row ->
                                if (p == Place.BATHROOM) maxOf(row.bathroom1 ?: 0L, row.bathroom2 ?: 0L)
                                else maxOf(row.kitchen1 ?: 0L, row.kitchen2 ?: 0L)
                            }
                        }
                        if (lastScan != null && lastScan > 0L) Text("この場所の最終読み取り　${formatted(lastScan, "M/d HH:mm")}",
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (registered) {
                                FilledTonalButton(onClick = { selected = p; vm.notice = "" }) { Icon(Icons.Filled.Print,null,Modifier.size(18.dp)); Text(" 表示・印刷") }
                                OutlinedButton(onClick = { share(p) }, enabled = !sharing) { Icon(Icons.Filled.Share,null,Modifier.size(18.dp)); Text(" 共有") }
                                Box {
                                    Tool(Icons.Filled.MoreVert, "${p.title}のその他") { menu = true }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("PNG保存") }, onClick = { menu = false; save(p) })
                                        DropdownMenuItem(text = { Text("カメラで登録") }, onClick = { menu = false; replaceByCamera = p })
                                        DropdownMenuItem(text = { Text("再発行…") }, onClick = {
                                            menu = false; confirmedFingerprint = fingerprint(state.token(p)); confirm = p
                                        })
                                    }
                                }
                            } else {
                                Button(onClick = { selected = p; vm.issue(p, "") }, enabled = !vm.busy) { Text("QRを作成") }
                                OutlinedButton(onClick = { scan(p) }, enabled = !vm.busy) { Text("カメラで登録") }
                            }
                        }
                    }
                }
                Text("再発行すると古いQRは使えなくなります。", style = MaterialTheme.typography.bodySmall)
            } else if (state.registered(place)) {
                DisposableEffect(activity) {
                    val flag = WindowManager.LayoutParams.FLAG_SECURE
                    val wasSecure = activity.window.attributes.flags and flag != 0
                    activity.window.addFlags(flag)
                    onDispose { if (!wasSecure) activity.window.clearFlags(flag) }
                }
                val payload = state.qrPayload(place)
                key(payload) { QrPreview(activity, place, payload, vm, { save(place) }, { share(place) }) }
            } else if (vm.busy) Text("QRコードを登録しています…")
        }
        if (place == null) Tabs("qr", tab)
    }
    replaceByCamera?.let { p ->
        AlertDialog(onDismissRequest = { replaceByCamera = null }, title = { Text("${p.title}のQRコードを読み取りますか？") },
            text = { Text("別のQRコードを登録すると、今のQRコードは使えなくなります。") },
            dismissButton = { TextButton(onClick = { replaceByCamera = null }) { Text("キャンセル") } },
            confirmButton = { TextButton(onClick = { replaceByCamera = null; scan(p) }) { Text("カメラを開く") } })
    }
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null }, icon = { Icon(Icons.Filled.Refresh, null) }, title = { Text("${p.title}のQRを再発行しますか？") },
            text = { Text("いま貼ってある${p.title}のQRは使えなくなります。新しいQRを印刷して貼り替えてください。") },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("キャンセル") } },
            confirmButton = { TextButton(onClick = {
                confirm = null
                val token = state.token(p)
                if (token.isNotEmpty() && fingerprint(token) == confirmedFingerprint) {
                    selected = p
                    vm.issue(p, token)
                } else vm.notice = "登録状態が変わりました。もう一度確認してください。"
            }) { Text("再発行する", color = MaterialTheme.colorScheme.error) } })
    }
}

@Composable
private fun QrPreview(activity: Activity, place: Place, payload: String, vm: QrViewModel, save: () -> Unit, share: () -> Unit) {
    var cardImage by remember { mutableStateOf<Pair<QrCard, Bitmap>?>(null) }
    var failed by remember { mutableStateOf(false) }
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
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Image(image.asImageBitmap(), "${place.title}に設置する印刷用QRコード",
            Modifier.size(264.dp).clip(RoundedCornerShape(12.dp)).background(Color.White))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaceBadge(place)
        Column {
            Text(place.title, style = MaterialTheme.typography.titleLarge)
            Text("${place.steps} で読み取ります", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = {
        try { printQr(activity, card, image) } catch (_: RuntimeException) {
            vm.notice = "印刷を利用できません。PNG保存または共有をお使いください。"
        }
    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Print,null); Text(" A4で印刷") }
    FilledTonalButton(save, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Download,null); Text(" PNGで保存") }
    OutlinedButton(share, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Share,null); Text(" 共有") }
    Text("このQRを撮影・共有すると誰でも読み取れます。共有先に注意してください。", style = MaterialTheme.typography.bodySmall)
}
