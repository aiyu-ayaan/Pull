package com.aiyu.pull.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.aiyu.pull.download.DownloadManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps Pull alive while bytes move. specialUse on API 34+ (no 6 h dataSync cap on Android 15), dataSync below.
 * Sticky: if Android kills the process, it restarts it and PullApp.onCreate re-queues what was running.
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var manager: DownloadManager
    @Inject lateinit var notifier: Notifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always first: a startForegroundService() must be answered within seconds even if there is nothing to do.
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, Notifier.ONGOING_ID, notifier.ongoing(1, null, 0, 0, 0, 0), type)
        if (!observing) {
            observing = true
            acquireWakeLock()
            scope.launch {
                // The lock has a timeout as a safety net; renew it so overnight downloads keep the CPU awake.
                while (true) {
                    kotlinx.coroutines.delay(60 * 60 * 1000L)
                    wakeLock?.acquire(WAKE_TIMEOUT)
                }
            }
            scope.launch {
                combine(manager.live, manager.downloads) { live, rows -> live to rows }
                    .sample(1_000) // notification updates are rate-limited by the system anyway
                    .collect { (live, rows) ->
                        if (live.isEmpty()) return@collect
                        val name = rows.firstOrNull { it.id in live }?.fileName
                        val total = if (live.values.any { it.totalBytes <= 0 }) -1 else live.values.sumOf { it.totalBytes }
                        val connections = live.values.sumOf { l -> l.segments.count { it.active } }
                        val n = notifier.ongoing(live.size, name, live.values.sumOf { it.downloadedBytes }, total, live.values.sumOf { it.bytesPerSecond }, connections)
                        runCatching { androidx.core.app.NotificationManagerCompat.from(this@DownloadService).notify(Notifier.ONGOING_ID, n) }
                    }
            }
            scope.launch {
                // Give a just-queued download a moment to start, then leave once nothing runs.
                kotlinx.coroutines.delay(2_000)
                manager.activeCount.first { it == 0 }
                stopSelfCleanly()
            }
        }
        return START_STICKY
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        // Only while the service runs, which is only while a download moves bytes. Released in onDestroy.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pull:download")
            .apply { setReferenceCounted(false); acquire(WAKE_TIMEOUT) }
        // Without it Wi-Fi drops into power save with the screen off and sockets stall until the engine gives up.
        @Suppress("DEPRECATION")
        wifiLock = runCatching {
            getSystemService(android.net.wifi.WifiManager::class.java)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Pull:download")
                .apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        const val WAKE_TIMEOUT = 2 * 60 * 60 * 1000L
    }
}
