package com.aiyu.pull.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aiyu.pull.download.DownloadManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var manager: DownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PAUSE_ALL) manager.pauseAll()
    }

    companion object {
        private const val ACTION_PAUSE_ALL = "com.aiyu.pull.PAUSE_ALL"

        fun pauseAll(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, NotificationActionReceiver::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
