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

    internal fun selectSound(uri: Uri?, document: Boolean, alarmId: String? = null) {
        viewModelScope.launch {
            try {
                soundStore.select(uri, document, alarmId)
                message.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                message.value = "音源を保存できませんでした。別の音源か端末のデフォルトを選んでください。"
            }
        }
    }

    fun edit(onSaved: () -> Unit = {}, transform: (WakeState) -> WakeState) =
        update(onSaved) { controller.change(transform) }

    private fun update(onSaved: () -> Unit, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                message.value = null
                onSaved()
            } catch (e: InputError) {
                message.value = e.message
            } catch (_: IOException) {
                message.value = "端末に保存できませんでした。空き容量を確認してもう一度お試しください。"
            }
        }
    }
    internal fun addAlarm(minute: Int, onSaved: (String) -> Unit) {
        val id = java.util.UUID.randomUUID().toString()
        update({ onSaved(id) }) { controller.addAlarm(id, minute) }
    }

    internal fun removeAlarm(id: String) {
        edit(onSaved = {
            viewModelScope.launch {
                try { soundStore.afterDeletion() } catch (_: IOException) { /* Retried on the next selection. */ }
            }
        }) { it.deleteCard(id) }
    }

    fun refresh() {
        viewModelScope.launch { controller.restore(fromBroadcast = false) }
    }
}
