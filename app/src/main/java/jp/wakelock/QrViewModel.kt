package jp.wakelock

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*

class QrViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app as WakeApp
    internal var busy by mutableStateOf(false)
        private set
    internal var notice by mutableStateOf("")
    init {
        viewModelScope.launch(Dispatchers.IO) { clearExpiredQrShares(appContext) }
    }

    private fun runOperation(failure: String, operation: suspend () -> String) {
        if (busy) return
        busy = true
        notice = ""
        viewModelScope.launch {
            try {
                notice = operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InputError) {
                notice = e.message.orEmpty()
            } catch (_: Exception) {
                notice = failure
            } finally {
                busy = false
            }
        }
    }

    internal fun issue(place: Place, expected: String) = runOperation("QRコードを登録できませんでした。") {
        appContext.controller.issueQr(place, expected)
        if (expected.isEmpty()) "${place.title}のQRコードを登録しました。"
        else "再発行しました。${place.title}のQRコードを印刷し直してください。"
    }

    internal fun save(place: Place, payload: String, uri: Uri) = runOperation("PNGを保存できませんでした。保存先と空き容量を確認してください。") {
        withContext(Dispatchers.IO) {
            val image = QrCard(place, payload).bitmap()
            try {
                image.savePng { appContext.contentResolver.openOutputStream(uri, "wt") }
            } finally {
                image.recycle()
            }
        }
        "${place.pngName}を保存しました。"
    }
}
