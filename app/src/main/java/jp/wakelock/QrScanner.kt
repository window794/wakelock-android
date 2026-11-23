package jp.wakelock

import android.content.Context
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
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
fun QrScanner(context: Context, place: Place, onRead: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onRead)
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    val view = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    Text("${place.title}のQRコードを枠内に映してください。")
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(260.dp))
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        BigButton("カメラを再起動") { attempt++ }
    }
    DisposableEffect(lifecycle, view, attempt, place) {
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val reader = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        var active = true
        var delivered = false
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        val observer = Observer<CameraState> { state ->
            if (active && state.error != null) error = "カメラを使用できません。他のカメラアプリを閉じてください。"
        }
        var busy = false
        analysis.setAnalyzer(main) { frame ->
            val media = frame.image
            if (!active || delivered || busy || media == null) {
                frame.close()
            } else {
                busy = true
                try {
                    reader.process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees))
                        .addOnSuccessListener(main) { codes ->
                            val raw = codes.firstOrNull()?.rawValue
                            if (active && !delivered && raw != null) {
                                delivered = true
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
                    camera?.cameraInfo?.cameraState?.observe(lifecycle, observer)
                } catch (_: Exception) {
                    error = "背面カメラを起動できません。権限とカメラの使用状況を確認してください。"
                }
            }
        }, main)
        onDispose {
            active = false
            camera?.cameraInfo?.cameraState?.removeObserver(observer)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            if (!busy) reader.close()
        }
    }
}
