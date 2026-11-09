package com.aiyu.pull.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.aiyu.pull.ui.theme.rememberReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * In-screen "step" that Back should undo (note page, result page) instead of leaving the app.
 * Follows the system predictive-back gesture: the content shrinks and slides toward the swipe edge while the
 * finger is down, [onBack] runs on commit, and a cancelled gesture springs back. Reduced motion skips the transform
 * but still handles Back.
 */
@Composable
fun PredictiveBack(enabled: Boolean = true, onBack: () -> Unit, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    var fromRight by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { e: BackEventCompat ->
                fromRight = e.swipeEdge == BackEventCompat.EDGE_RIGHT
                if (!reduced) progress.snapTo(e.progress)
            }
            onBack()
            progress.snapTo(0f) // the step is gone now; whatever shows next starts untransformed
        } catch (c: CancellationException) {
            scope.launch { progress.animateTo(0f, spring) }
            throw c
        }
    }

    // Untouched while idle: no layer, no clip, so the wrapped screen renders exactly as without the wrapper.
    val active = progress.value != 0f
    Box(
        if (!active) Modifier else Modifier
            .graphicsLayer {
                val p = progress.value
                val s = 1f - 0.1f * p
                scaleX = s
                scaleY = s
                translationX = (if (fromRight) -1f else 1f) * size.width * 0.04f * p
                alpha = 1f - 0.2f * p
            }
            .clip(RoundedCornerShape((28 * progress.value).dp)),
    ) { content() }
}
