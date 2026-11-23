package jp.wakelock

import android.content.Context
import android.media.AudioManager
import android.os.Build
import kotlin.math.roundToInt

internal data class AlarmVolume(val min: Int, val max: Int, val current: Int, val fixed: Boolean, val muted: Boolean) {
    val adjustable get() = !fixed && max > min
    val fraction get() = if (max > min) ((current - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
    val low get() = muted || current == 0 || (max > min && fraction <= 0.15f)
    fun index(fraction: Float) = min + ((max - min) * fraction.coerceIn(0f, 1f)).roundToInt()
}

internal class AlarmVolumeControl(private val read: () -> AlarmVolume, private val write: (Int) -> Unit) {
    fun current() = read()

    fun change(fraction: Float): AlarmVolume {
        val before = read()
        check(before.adjustable) { "この端末ではアラーム音量を変更できません。" }
        val index = before.index(fraction)
        if (index != before.current) write(index)
        return read().also { check(it.current == index) { "端末の設定により音量を変更できませんでした。" } }
    }
}

internal fun volumeControl(context: Context): AlarmVolumeControl {
    val audio = context.getSystemService(AudioManager::class.java)
    val stream = AudioManager.STREAM_ALARM
    return AlarmVolumeControl({
        val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        AlarmVolume(min, audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream), audio.isVolumeFixed, audio.isStreamMute(stream))
    }, { index -> audio.setStreamVolume(stream, index, 0) })
}
