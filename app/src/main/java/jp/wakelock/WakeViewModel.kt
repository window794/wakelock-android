package jp.wakelock

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

class WakeViewModel(app: Application) : AndroidViewModel(app) {
    val controller = (app as WakeApp).controller
    val state = controller.states.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val message = MutableStateFlow<String?>(null)
    private val soundStore = (app as WakeApp).soundStore
    internal val sound = soundStore.sounds.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    internal fun selectSound(uri: Uri?, document: Boolean) {
        viewModelScope.launch {
            try {
                soundStore.select(uri, document)
                message.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                message.value = "音源を保存できませんでした。別の音源か端末のデフォルトを選んでください。"
            }
        }
    }

    fun edit(onSaved: () -> Unit = {}, transform: (WakeState) -> WakeState) {
        viewModelScope.launch {
            try {
                controller.change(transform)
                message.value = null
                onSaved()
            } catch (e: InputError) {
                message.value = e.message
            } catch (_: IOException) {
                message.value = "端末に保存できませんでした。空き容量を確認してもう一度お試しください。"
            }
        }
    }
    fun refresh() {
        viewModelScope.launch { controller.restore(fromBroadcast = false) }
    }
}
