package com.aiyu.pull.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.aiyu.pull.ui.theme.PullMotion
import com.aiyu.pull.ui.theme.rememberReducedMotion

/** Hero sequence used on landing + brand mark. */
val HeroShapes: List<RoundedPolygon>
    get() = listOf(
        MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Sunny,
        MaterialShapes.Gem, MaterialShapes.Puffy, MaterialShapes.SoftBurst,
    )

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Continuously morphs through [shapes] (loops back to first) while slowly rotating.
 * Each segment: emphasized morph for 60%, then rests — reads as "breathing", not spinning.
 * Static first shape when [animate] is false or system animations are off.
 */
@Composable
fun MorphingShape(
    brush: Brush,
    modifier: Modifier = Modifier,
    shapes: List<RoundedPolygon> = HeroShapes,
    animate: Boolean = true,
    segmentMillis: Int = PullMotion.MORPH_MILLIS,
    rotationMillis: Int = PullMotion.ORBIT_MILLIS,
) {
    val morphs = remember(shapes) { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val live = animate && !rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "morph")
    val t = if (live) transition.animateFloat(
        0f, shapes.size.toFloat(),
        infiniteRepeatable(tween(segmentMillis * shapes.size, easing = LinearEasing)), label = "t",
    ) else null
    val rotation = if (live) transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(rotationMillis, easing = LinearEasing), RepeatMode.Restart), label = "rot",
    ) else null

    val path = remember { Path() }
    val matrix = remember { Matrix() }
    Canvas(modifier) {
        val value = t?.value ?: 0f
        val index = value.toInt() % morphs.size
        val local = value - value.toInt()
        val progress = Emphasized.transform((local / 0.6f).coerceAtMost(1f))
        path.rewind()
        morphs[index].toPath(progress, path)
        // Polygons are normalized around (0.5,0.5) in unit space -> scale to canvas.
        matrix.reset()
        matrix.scale(size.width, size.height)
        path.transform(matrix)
        rotate(rotation?.value ?: 0f, pivot = Offset(size.width / 2, size.height / 2)) {
            drawPath(path, brush)
        }
    }
}

/** Brand hero fill: primary into its tonal partner (works in light, dark and dynamic schemes). */
@androidx.compose.runtime.Composable
fun heroBrush(): Brush {
    val c = androidx.compose.material3.MaterialTheme.colorScheme
    return Brush.linearGradient(listOf(c.primary, c.inversePrimary))
}
