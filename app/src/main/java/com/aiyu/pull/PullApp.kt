package com.aiyu.pull

import android.app.Application
import com.aiyu.pull.download.DownloadManager
import com.aiyu.pull.service.Notifier
import com.aiyu.pull.video.VideoEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.aiyu.pull.data.SettingsRepository
import com.aiyu.pull.update.UpdateWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class PullApp : Application() {
    @Inject lateinit var manager: DownloadManager
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var videos: VideoEngine

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.aiyu.pull.data.AppLocale.wrap(base))

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        notifier.createChannels() // API 33+: re-localize notification channel names on language change
    }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // Every process start, including the one Android makes to restart the sticky service after killing Pull.
        manager.restore()
        MainScope().launch {
            UpdateWorker.schedule(this@PullApp, settings.current().updatesEnabled)
            runCatching { videos.checkAndUpdate() }
        }
    }
}
