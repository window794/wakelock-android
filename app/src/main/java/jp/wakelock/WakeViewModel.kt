package jp.wakelock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

class WakeViewModel(app: Application) : AndroidViewModel(app) {
    val controller = (app as WakeApp).controller
    val state = controller.states.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val message = MutableStateFlow<String?>(null)

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
