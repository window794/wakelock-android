package jp.wakelock

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.*
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
internal fun QrScanner(context: Context, place: Place, step: Int?, retry: Int, notice: String,
                       close: () -> Unit, stop: (() -> Unit)?, onRead: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onRead)
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    val view = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val host = remember { SnackbarHostState() }
    LaunchedEffect(retry) { if (retry > 0 && notice.isNotEmpty()) host.showSnackbar(notice) }
    Box(Modifier.fillMaxSize().background(cs.surfaceContainerLowest)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(cs.surface.copy(alpha = .94f)).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Tool(Icons.Filled.Close, "閉じる", close)
                Text("${place.title}のQR", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (boundCamera?.cameraInfo?.hasFlashUnit() == true) {
                    IconToggleButton(torch, { enabled ->
                        val c = boundCamera ?: return@IconToggleButton
                        val change = c.cameraControl.enableTorch(enabled)
                        change.addListener({ runCatching { change.get() }.onSuccess { torch = enabled } },
                            ContextCompat.getMainExecutor(context))
                    }) { Icon(if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        if (torch) "ライトを消す" else "ライトを点ける") }
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val edge = minOf(256.dp, maxWidth * .7f, maxHeight * .6f)
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    val color = place.main
                    Canvas(Modifier.size(edge)) {
                        val side = size.minDimension
                        val reach = side * .19f
                        val round = reach * .55f
                        val stroke = 4.dp.toPx()
                        repeat(4) { i ->
                            rotate(i * 90f) {
                                val path = Path().apply {
                                    moveTo(stroke / 2,reach); lineTo(stroke / 2,round)
                                    quadraticTo(stroke / 2,stroke / 2,round,stroke / 2); lineTo(reach,stroke / 2)
                                }
                                drawPath(path,color,style = Stroke(stroke))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Surface(color = cs.surface.copy(alpha = .94f), shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("枠の中にQRを入れてください", style = MaterialTheme.typography.titleLarge)
                            Text("${place.title}に貼った「${place.title}」ラベルのQR", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    error?.let {
                        Surface { Column { Text(it, color = cs.error); TextButton(onClick = { attempt++ }) { Text("カメラを再起動") } } }
                    }
                }
            }
            Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PlaceBadge(place)
                        Column(Modifier.weight(1f)) {
                            Text(if (step == null) "${place.title}に登録" else "ステップ $step / 4", style = MaterialTheme.typography.titleMedium)
                            Text("${if (place == Place.KITCHEN) "洗面所" else "キッチン"}のQRでは進めません", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (stop != null) TextButton(stop) { Text("緊急停止") }
                    }
                    if (step != null) ProtocolTrack(step - 1, compact = true)
                }
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
    var unlock by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        if (retry > 0) { delay(1200); unlock = retry }
    }
    val retryValue by rememberUpdatedState(unlock)
    DisposableEffect(lifecycle, view, attempt, place) {
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val reader = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        var active = true
        var deliveredAt = -1
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        val observer = Observer<CameraState> { state ->
            if (active && state.error != null) error = "カメラを使用できません。他のカメラアプリを閉じてください。"
        }
        var busy = false
        analysis.setAnalyzer(main) { frame ->
            val media = frame.image
            if (!active || deliveredAt == retryValue || busy || media == null) {
                frame.close()
            } else {
                busy = true
                try {
                    reader.process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener(main) { codes ->
                            val raw = codes.firstOrNull()?.rawValue
                            if (active && deliveredAt != retryValue && raw != null) {
                                deliveredAt = retryValue
                                callback(raw)
                            }
                        }.addOnFailureListener(main) {
                            if (active) error = "読み取りに失敗しました。カメラを再起動してください。"
                        }.addOnCompleteListener(main) {
                            frame.close()
                            busy = false
                            if (!active) reader.close()
                        }
                } catch (_: RuntimeException) {
                    busy = false
                    frame.close()
                    error = "画像を読み取れませんでした。カメラを再起動してください。"
                }
            }
        }
        future.addListener({
            if (active) {
                try {
                    val source = future.get()
                    provider = source
                    preview.surfaceProvider = view.surfaceProvider
                    camera = source.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    boundCamera = camera
                    camera?.cameraInfo?.cameraState?.observe(lifecycle, observer)
                } catch (_: Exception) {
                    error = "背面カメラを起動できません。権限とカメラの使用状況を確認してください。"
                }
            }
        }, main)
        onDispose {
            active = false
            camera?.cameraInfo?.cameraState?.removeObserver(observer)
            boundCamera = null
            torch = false
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            if (!busy) reader.close()
        }
    }
}
