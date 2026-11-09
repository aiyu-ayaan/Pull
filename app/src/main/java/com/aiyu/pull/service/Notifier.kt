package com.aiyu.pull.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aiyu.pull.MainActivity
import com.aiyu.pull.R
import com.aiyu.pull.ui.formatBytes
import com.aiyu.pull.ui.formatDuration
import com.aiyu.pull.ui.formatSpeed
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** A newer Pull release exists. Gated by the auto-update switch, not a notification category. */
    fun updateAvailable(releaseName: String, installedName: String) = post(
        UPDATE_ID.toLong(),
        NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_pull)
            .setContentTitle(context.getString(R.string.notif_update_title, releaseName))
            .setContentText(context.getString(R.string.notif_update_text, installedName))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(null))
            .build(),
    )

    fun cancelUpdateAvailable() = nm.cancel(UPDATE_ID)

    /**
     * The service's single ongoing notification: all active downloads summed. A custom view keeps Pull's wavy
     * progress bar in the shade; the wave is a bitmap whose phase shifts on every (1 Hz) update, so it crawls.
     */
    fun ongoing(count: Int, title: String?, downloaded: Long, total: Long, speed: Long, connections: Int): Notification {
        val fraction = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else -1f
        val percent = if (fraction >= 0) String.format(Locale.getDefault(), "%.1f%%", fraction * 100) else ""
        val eta = if (total > 0 && speed > 0) formatDuration((total - downloaded) / speed) else null
        val line = listOfNotNull(formatSpeed(speed), eta?.let { context.getString(R.string.eta_left, it) }).joinToString(" · ")
        val name = if (count <= 1 && title != null) title else context.resources.getQuantityString(R.plurals.notif_downloading, count, count)
        val sizes = if (total > 0) context.getString(R.string.details_of, formatBytes(downloaded), formatBytes(total)) else formatBytes(downloaded)
        val detail = context.getString(R.string.notif_detail, sizes, connections)
        val wave = WaveBitmap.draw(context, fraction)
        fun views(layout: Int) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.title, name)
            setTextViewText(R.id.percent, percent)
            setTextViewText(R.id.line, line)
            setImageViewBitmap(R.id.wave, wave)
            if (layout == R.layout.notif_progress_big) setTextViewText(R.id.detail, detail)
        }
        return NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_pull)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notif_progress))
            .setCustomBigContentView(views(R.layout.notif_progress_big))
            // Plain fields still feed accessibility, the lock screen and watches.
            .setContentTitle(name)
            .setContentText(listOf(percent, line).filter { it.isNotEmpty() }.joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(null))
            .addAction(0, context.getString(R.string.action_pause_all), NotificationActionReceiver.pauseAll(context))
            .build()
    }

    fun completed(id: Long, name: String, size: Long) = post(
        id,
        NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_pull)
            .setContentTitle(name)
            .setContentText(context.getString(R.string.notif_completed, formatBytes(size)))
            .setAutoCancel(true)
            .setContentIntent(openApp(id))
            .build(),
    )

    fun failed(id: Long, name: String, linkExpired: Boolean) = post(
        id,
        NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_pull)
            .setContentTitle(name)
            .setContentText(context.getString(if (linkExpired) R.string.notif_link_expired else R.string.notif_failed))
            .setAutoCancel(true)
            .setContentIntent(openApp(id))
            .build(),
    )

    fun cancel(id: Long) = nm.cancel(id.toInt())

    private fun post(id: Long, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching { nm.notify(id.toInt(), n) }
    }

    private fun openApp(downloadId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { downloadId?.let { putExtra(MainActivity.EXTRA_DOWNLOAD_ID, it) } }
        return PendingIntent.getActivity(
            context, downloadId?.toInt() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
        const val CH_UPDATES = "updates"
        const val ONGOING_ID = Int.MAX_VALUE
        const val UPDATE_ID = Int.MAX_VALUE - 1
    }
}
