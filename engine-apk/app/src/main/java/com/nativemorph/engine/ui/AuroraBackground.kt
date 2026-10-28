package com.nativemorph.engine.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * PS5-main-menu style ambient background, recolored to the NativeMorph
 * violet identity: near-black base with three slowly drifting radial
 * gradients (deep violet, purple, indigo) and a soft vignette. The motion
 * is time-based and self-driving while the window is visible.
 */
class AuroraBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val base = Color.parseColor("#0B0714")
    private val vignette = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0.5f, 0.5f, 1.4f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xB2000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private class Blob(
        val color: Int,
        val rFraction: Float,   // radius as fraction of max(w,h)
        val speed: Float,       // radians/sec
        val cx: Float,          // orbit center (fraction of size)
        val cy: Float,
        val orbit: Float,       // orbit radius (fraction)
        val phase: Float,
    ) {
        var paint: Paint? = null
        var w = 0f
        var h = 0f
    }

    private val blobs = listOf(
        Blob(0x2E6C4DFF.toInt(), 0.85f, 0.061f, 0.22f, 0.18f, 0.16f, 0.0f),
        Blob(0x389C86FF.toInt(), 0.70f, -0.047f, 0.82f, 0.28f, 0.20f, 2.1f),
        Blob(0x2CB388FF.toInt(), 0.55f, 0.083f, 0.55f, 0.85f, 0.14f, 4.2f),
    )

    private var running = true

    fun setRunning(on: Boolean) {
        running = on
        if (on) postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vignette.shader = RadialGradient(
            w * 0.5f, h * 0.5f, Math.max(w, h) * 1.2f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0xB2000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        for (b in blobs) {
            b.w = w.toFloat()
            b.h = h.toFloat()
            b.paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    0f, 0f, Math.max(w, h) * b.rFraction,
                    intArrayOf(b.color, Color.TRANSPARENT),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = SystemClock.elapsedRealtime() / 1000f
        canvas.drawColor(base)
        for (b in blobs) {
            val p = b.paint ?: continue
            val x = (b.cx + b.orbit * kotlin.math.sin(t * b.speed + b.phase)) * b.w
            val y = (b.cy + b.orbit * kotlin.math.cos(t * b.speed * 0.8f + b.phase)) * b.h
            canvas.save()
            canvas.translate(x, y)
            canvas.drawPaint(p)
            canvas.restore()
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignette)
        if (running) postInvalidateOnAnimation()
    }
}
