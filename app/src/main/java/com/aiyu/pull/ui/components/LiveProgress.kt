package com.aiyu.pull.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiyu.pull.engine.SegmentProgress
import com.aiyu.pull.ui.theme.PullMotion
import com.aiyu.pull.ui.theme.rememberReducedMotion

/**
 * A live number that glides linearly from the previous 4 Hz sample to the new one over one sample period.
 * Byte counters then tick continuously, the way desktop download managers feel, while every value shown is real:
 * it only ever moves between two measured samples, never ahead of them.
 */
@Composable
fun rememberGlide(target: Long): State<Long> {
    val reduced = rememberReducedMotion()
    var from by remember { mutableLongStateOf(target) }
    var to by remember { mutableLongStateOf(target) }
    val t = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (reduced || target < to) { // a drop (restart) jumps; only growth glides
            from = target; to = target; t.snapTo(1f); return@LaunchedEffect
        }
        from = from + ((to - from) * t.value).toLong()
        to = target
        t.snapTo(0f)
        t.animateTo(1f, tween(PullMotion.SAMPLE_MILLIS, easing = LinearEasing))
    }
    return remember { derivedStateOf { from + ((to - from) * t.value).toLong() } }
}

/**
 * Connection map: the whole file as one bar. Finished bytes are filled, each connection's unfinished range is
 * track, and each live connection's write head is a bright tick. Work stealing shows up as new heads appearing.
 */
@Composable
fun SegmentMap(
    totalBytes: Long,
    segments: List<SegmentProgress>,
    complete: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
) {
    val c = MaterialTheme.colorScheme
    val active = segments.count { it.active }
    Canvas(
        modifier.fillMaxWidth().height(height).semantics {
            contentDescription = "$active connections downloading"
        },
    ) {
        val r = CornerRadius(size.height / 2)
        val bar = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r)) }
        clipPath(bar) {
            if (totalBytes <= 0) {
                drawRect(if (complete) c.primary else c.surfaceContainerHighest)
                return@clipPath
            }
            drawRect(c.primary) // saved segments list only unfinished ranges: everything else is done
            val w = size.width / totalBytes.toFloat()
            for (s in segments) {
                val cur = s.current.coerceAtMost(s.end + 1)
                if (cur > s.end) continue
                val x = cur * w
                drawRect(c.secondaryContainer, Offset(x, 0f), Size(((s.end + 1 - cur) * w).coerceAtLeast(1f), size.height))
            }
            for (s in segments) {
                // Boundary between ranges, so each connection's share stays readable.
                drawRect(c.surface, Offset(s.start * w - 0.5f, 0f), Size(1.dp.toPx(), size.height))
                if (s.active && s.current <= s.end) {
                    drawRect(c.tertiary, Offset(s.current * w - 1.5.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
                }
            }
        }
    }
}

/** Speed over the last minute: filled area under a line. Scaled to its own peak so small changes stay visible. */
@Composable
fun SpeedGraph(history: List<Long>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        // Faint guide lines at quarters.
        for (k in 1..3) {
            val y = size.height * k / 4
            drawLine(c.outlineVariant.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
        if (history.size < 2) return@Canvas
        val max = (history.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat() * 1.15f
        val step = size.width / (SPEED_POINTS - 1)
        val x0 = size.width - step * (history.size - 1)
        val line = Path()
        history.forEachIndexed { i, v ->
            val x = x0 + i * step
            val y = size.height - v / max * size.height
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(x0, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(c.primary.copy(alpha = 0.35f), c.primary.copy(alpha = 0.02f))))
        drawPath(line, c.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private const val SPEED_POINTS = 60
