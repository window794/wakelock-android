package jp.wakelock

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    internal var page by mutableStateOf("home")
    internal val result = CompletionPresentation()
    private val history = (app as WakeApp).history
    internal var historyError by mutableStateOf<String?>(null)
    internal val historyRows = history.rows.onEach { historyError = null }.catch {
        historyError = "履歴を読み込めませんでした。もう一度開いてください。"
        emit(emptyList())
    }
    internal var exportBusy by mutableStateOf(false)
    internal var exportNotice by mutableStateOf("")

    internal fun exportCsv(uri: Uri, range: HistoryRange) {
        if (exportBusy) return
        exportBusy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val rows = history.rows.first()
                    val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("保存先を開けません。")
                    stream.bufferedWriter(Charsets.UTF_8).use { HistoryCsv.write(it, rows, range) }
                }
                exportNotice = "CSVを保存しました。"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                exportNotice = "CSVを保存できませんでした。保存先と空き容量を確認してください。"
            } finally { exportBusy = false }
        }
    }

    internal fun verify(step: StepId, raw: String, onFailure: (String) -> Unit = {}) {
        val ticket = result.ticket
        edit(onFailure = onFailure, onSaved = {
            if (step.phase == Phase.FINAL_CHALLENGE_ACTIVE) result.completed(step.generation, ticket)
        }) { it.verify(step, raw, System.currentTimeMillis()) }
    }

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

    fun edit(onSaved: () -> Unit = {}, onFailure: (String) -> Unit = {}, transform: (WakeState) -> WakeState) =
        update(onSaved, onFailure) { controller.change(transform) }

    private fun update(onSaved: () -> Unit, onFailure: (String) -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                message.value = null
                onSaved()
            } catch (e: InputError) {
                message.value = e.message
                onFailure(e.message.orEmpty())
            } catch (_: IOException) {
                val explanation = "端末に保存できませんでした。空き容量を確認してもう一度お試しください。"
                message.value = explanation
                onFailure(explanation)
            }
        }
    }
    internal fun addAlarm(minute: Int, onSaved: (String) -> Unit) {
        val id = java.util.UUID.randomUUID().toString()
        update({ onSaved(id) }) { controller.addAlarm(id, minute) }
    }

    internal fun removeAlarm(id: String, onRemoved: () -> Unit = {}) {
        edit(onSaved = {
            onRemoved()
            viewModelScope.launch {
                try { soundStore.afterDeletion() } catch (_: IOException) { /* Retried on the next selection. */ }
            }
        }) { it.deleteCard(id) }
    }

    fun refresh() {
        viewModelScope.launch { controller.restore(fromBroadcast = false) }
    }
}


// A presentation receipt lives only in this foreground visit. It is never restored from DataStore or a Bundle.
internal class CompletionPresentation {
    var ticket by mutableLongStateOf(0)
        private set
    private var run by mutableStateOf<Long?>(null)
    fun completed(generation: Long, expectedTicket: Long) {
        if (ticket == expectedTicket) run = generation
    }
    fun visible(state: WakeState) = state.phase == Phase.COMPLETED && run == state.generation
    fun dismiss() { run = null; ticket++ }
}
