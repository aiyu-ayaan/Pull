package com.aiyu.pull

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiyu.pull.ui.add.AddRequest
import com.aiyu.pull.ui.add.copyTorrent
import com.aiyu.pull.ui.navigation.AppEvent
import com.aiyu.pull.ui.navigation.PullAppShell
import com.aiyu.pull.ui.settings.SettingsViewModel
import com.aiyu.pull.ui.theme.PullTheme
import com.aiyu.pull.ui.theme.WithClickHaptics
import dagger.hilt.android.AndroidEntryPoint

/** `drawable/splash_mark` runs 960ms from launch; onCreate is ~200ms in, so the first frame waits the rest. */
private const val SPLASH_MS = 760L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val settingsVm: SettingsViewModel by viewModels()
    private var event by mutableStateOf<AppEvent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Splash stays until settings load, so the first frame already has the right theme.
        // Android 12+ also lets drawable/splash_mark finish on a fresh launch.
        val holdUntil = if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SystemClock.uptimeMillis() + SPLASH_MS
        } else 0L
        splash.setKeepOnScreenCondition {
            settingsVm.settings.value == null || SystemClock.uptimeMillis() < holdUntil
        }
        enableEdgeToEdge()
        // A recreated activity (rotation, process death) already handled its launch intent.
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by settingsVm.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            PullTheme(s.themeMode, s.dynamicColor) {
                WithClickHaptics {
                    PullAppShell(event, onEventHandled = { event = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1)
        when {
            id >= 0 -> event = AppEvent.OpenDownload(id)
            intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("magnet", true) ->
                event = AppEvent.Add(AddRequest(intent.dataString.orEmpty()))
            intent.action == Intent.ACTION_VIEW && intent.data != null ->
                copyTorrent(this, intent.data!!)?.let { event = AppEvent.Add(AddRequest(torrentFile = it)) }
        }
    }

    companion object {
        const val EXTRA_DOWNLOAD_ID = "download_id"
    }
}
