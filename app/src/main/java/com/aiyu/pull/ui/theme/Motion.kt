package com.aiyu.pull.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Shared motion tokens. Component springs come from MaterialTheme.motionScheme (expressive). */
object PullMotion {
    const val MORPH_MILLIS = 2400
    const val ORBIT_MILLIS = 18_000
    /** Live numbers glide between 4 Hz samples over one sample period, so counters tick instead of jumping. */
    const val SAMPLE_MILLIS = 250
}

/** True when the user turned animations off (Developer options / Accessibility "Remove animations"). */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
