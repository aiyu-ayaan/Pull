package com.aiyu.pull.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pull's wavy (M3 Expressive) progress bar as a bitmap, for notifications where Compose cannot run.
 * Wavy part up to the progress, flat track after it, stop dot at the end. [fraction] < 0 = unknown size (all wave).
 * The phase follows the clock, so each refresh moves the wave forward.
 */
object WaveBitmap {
    fun draw(context: Context, fraction: Float): Bitmap {
        val dm = context.resources.displayMetrics
        val d = dm.density
        val w = (dm.widthPixels - 96 * d).toInt().coerceIn(200, 2000)
        val h = (14 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val active = if (dark) 0xFF9DD67D.toInt() else 0xFF386A20.toInt()
        val track = if (dark) 0xFF33492A.toInt() else 0xFFCFE8BE.toInt()
        val stroke = 4 * d
        val pad = stroke / 2 + 1
        val mid = h / 2f
        val amp = h / 2f - stroke / 2 - 1
        val wavelength = 22 * d
        val phase = (System.currentTimeMillis() / 1000 % 8) * (PI / 4)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val end = w - pad
        val x = if (fraction < 0) end else pad + (end - pad) * fraction
        // Track first, with a gap after the wave like the Compose indicator, and the stop dot.
        val gap = 4 * d
        if (fraction >= 0 && x + gap < end) {
            paint.color = track
            canvas.drawLine(x + gap + stroke / 2, mid, end, mid, paint)
            paint.style = Paint.Style.FILL
            paint.color = active
            canvas.drawCircle(end, mid, stroke / 2, paint)
            paint.style = Paint.Style.STROKE
        }
        if (x > pad) {
            val path = Path()
            var px = pad
            path.moveTo(px, mid + (amp * sin(2 * PI * px / wavelength - phase)).toFloat())
            while (px < x) {
                px = (px + 2).coerceAtMost(x)
                path.lineTo(px, mid + (amp * sin(2 * PI * px / wavelength - phase)).toFloat())
            }
            paint.color = active
            canvas.drawPath(path, paint)
        }
        return bmp
    }
}
