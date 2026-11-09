package com.aiyu.pull

import android.app.Application
import com.aiyu.pull.download.DownloadManager
import com.aiyu.pull.service.Notifier
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

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // Every process start, including the one Android makes to restart the sticky service after killing Pull.
        manager.restore()
        MainScope().launch { UpdateWorker.schedule(this@PullApp, settings.current().updatesEnabled) }
    }
}
