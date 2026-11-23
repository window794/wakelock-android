package jp.wakelock

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class Alerts(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    init {
        val ring = NotificationChannel(RING, "アラーム鳴動", NotificationManager.IMPORTANCE_HIGH)
        ring.setSound(null, null) // Looping audio comes from RingService.
        val check = NotificationChannel(CHECK, "起床の再確認", NotificationManager.IMPORTANCE_HIGH)
        check.enableVibration(true)
        val backup = NotificationChannel(BACKUP, "鳴動できない時の通知", NotificationManager.IMPORTANCE_HIGH)
        backup.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attributes)
        manager.createNotificationChannels(listOf(ring, check, backup))
    }

    val fullScreen get() = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
    val notifications get() = manager.areNotificationsEnabled() &&
        listOf(RING, CHECK, BACKUP).all { manager.getNotificationChannel(it).importance != NotificationManager.IMPORTANCE_NONE }

    val openApp: PendingIntent get() = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)

    private fun notice(channel: String, title: String, body: String, full: Boolean): Notification {
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title).setContentText(body).setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (full && fullScreen) builder.setFullScreenIntent(openApp, true)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    fun ring() = notice(RING, "起床時刻です", "タップしてQR認証へ", true)
    fun check() = post(2, notice(CHECK, "起きていますか？", "60秒以内にタップして回答してください", false))
    fun ringFallback() = post(3, notice(BACKUP, "アラームを確認してください", "タップしてWakeLockを開いてください", true))
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            manager.notify(id, notification)
    }
    fun clearCheck() = manager.cancel(2)
    fun clearBackup() = manager.cancel(3)
    fun clearRinging() {
        manager.cancel(1)
        clearBackup()
    }

    companion object {
        const val RING_ID = 1
        private const val RING = "ring"
        private const val CHECK = "check"
        private const val BACKUP = "backup"
    }
}
