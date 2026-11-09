package com.aiyu.pull.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aiyu.pull.service.Notifier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * The check that happens while the app is closed. The launch check reaches everybody except the phone that
 * hasn't opened Pull in three weeks, which is exactly the phone running a three-week-old build.
 *
 * Once a day, unmetered network, and it only draws a notification when a newer release exists. It never
 * downloads (that would spend storage on a decision nobody made) and never installs.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun updater(): AppUpdater
        fun notifier(): Notifier
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val updater = deps.updater()
        // A failed check is a network worse than WorkManager thought; tomorrow's run is the retry.
        val state = updater.check() as? UpdateState.Available ?: return Result.success()
        deps.notifier().updateAvailable(state.release.name, updater.installedName)
        return Result.success()
    }

    companion object {
        private const val WORK = "pull.update.check"

        /** Daily while auto-update is on, cancelled the moment it's off. Idempotent (KEEP), safe on every launch. */
        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) { work.cancelUniqueWork(WORK); return }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            // KEEP, not UPDATE: replacing it on every launch resets the period, so an app opened daily would never reach the end of one.
            work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
