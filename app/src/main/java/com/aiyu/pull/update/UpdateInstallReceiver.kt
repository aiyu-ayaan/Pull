package com.aiyu.pull.update

import com.aiyu.pull.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * How an install ended. A PackageInstaller session answers here:
 *  - PENDING_USER_ACTION: the system's confirmation dialog, handed back rather than shown; something has to start it.
 *  - SUCCESS: nothing to do, this process is about to be replaced.
 *  - anything else: a reason, said out loud on the update screen. The common one is an APK signed with a
 *    different key from the installed build (e.g. a debug build, or a first sideloaded release).
 * Not exported: only the platform may answer a session this app created.
 */
@AndroidEntryPoint
class UpdateInstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: AppUpdater

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // started from a receiver: no task of its own
                runCatching { context.startActivity(confirm) }
                    .onFailure { updater.fail(it.message ?: context.getString(R.string.update_err_screen)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.dismiss()
            PackageInstaller.STATUS_FAILURE_CONFLICT -> updater.fail(context.getString(R.string.update_err_signature))
            else -> updater.fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: context.getString(R.string.update_err_install))
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)

    companion object {
        const val ACTION_STATUS = "com.aiyu.pull.UPDATE_INSTALL_STATUS"
    }
}
