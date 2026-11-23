package jp.wakelock

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.PowerManager
import androidx.core.net.toUri
import kotlinx.coroutines.*
import java.io.IOException

// A candidate plays until cancellation or failure (including errors reported after prepare).
internal class AlarmSoundPlayer(
    private val scope: CoroutineScope,
    private val defaultUri: String,
    private val play: suspend (String?) -> Unit
) {
    private var job: Job? = null

    fun start(selected: String?, onFallback: () -> Unit = {}, onFailure: () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            val sources = listOfNotNull(selected, defaultUri).distinct() + listOf(null)
            for ((index, uri) in sources.withIndex()) {
                if (index > 0) onFallback()
                try {
                    play(uri)
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureActive()
                }
            }
            onFailure()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

internal val alarmAttributes: AudioAttributes get() = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

internal fun alarmPlayer(context: Context, scope: CoroutineScope): AlarmSoundPlayer {
    // The symbolic system URI also supports Android's cached default when direct media access is restricted.
    val systemDefault = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString()
    return AlarmSoundPlayer(scope, systemDefault) { uri ->
        val failed = CompletableDeferred<Unit>()
        var owned: MediaPlayer? = null
        try {
            withContext(Dispatchers.IO) {
                val player = MediaPlayer()
                owned = player
                player.setAudioAttributes(alarmAttributes)
                player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                player.setOnErrorListener { _, _, _ ->
                    failed.completeExceptionally(IOException("音源を再生できません。"))
                    true
                }
                if (uri != null) player.setDataSource(context, uri.toUri())
                else context.resources.openRawResourceFd(R.raw.alarm).use {
                    player.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                }
                player.isLooping = true
                player.prepare()
            }
            currentCoroutineContext().ensureActive()
            checkNotNull(owned).start()
            failed.await()
        } finally {
            owned?.release()
        }
    }
}
