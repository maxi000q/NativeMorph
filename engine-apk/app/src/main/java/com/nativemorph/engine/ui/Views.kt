package com.nativemorph.engine.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nativemorph.engine.core.Layouts

/** Tiny programmatic-UI toolkit (no view binding needed for this app). */
object Ui {
    fun dp(v: Float): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density + 0.5f).toInt()

    fun color(ctx: Context, id: Int): Int =
        androidx.core.content.ContextCompat.getColor(ctx, id)

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 22f
        setTextColor(color(ctx, com.nativemorph.engine.R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun body(ctx: Context, text: String, secondary: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        setTextColor(
            color(
                ctx,
                if (secondary) com.nativemorph.engine.R.color.text_secondary
                else com.nativemorph.engine.R.color.text_primary
            )
        )
    }

    fun caption(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(color(ctx, com.nativemorph.engine.R.color.text_secondary))
    }

    fun vertical(ctx: Context, padDp: Int = 16): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp.toFloat()), dp(padDp.toFloat()), dp(padDp.toFloat()), dp(padDp.toFloat()))
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        background = gradientDrawable(ctx)
    }

    private fun gradientDrawable(ctx: Context): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color(ctx, com.nativemorph.engine.R.color.bg_card))
            cornerRadius = dp(12f).toFloat()
            setStroke(1, color(ctx, com.nativemorph.engine.R.color.stroke))
        }
}

/**
 * Small canvas preview of one of the three mobile UI layouts; reused by the
 * settings screen and the per-game launcher sheet.
 */
class LayoutPreview @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var kind: String = Layouts.CLASSIC
        set(v) {
            field = v
            invalidate()
        }

    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x556C4DFF.toInt()
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF9C86FF.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF9C86FF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            Layouts.CLASSIC -> {
                val cx = w * 0.26f
                val cy = h * 0.5f
                val r = h * 0.12f
                val d = h * 0.2f
                listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                    val x = cx + dx * d
                    val y = cy + dy * d
                    c.drawCircle(x, y, r, fill)
                    c.drawCircle(x, y, r, rim)
                }
                val bx = w * 0.74f
                val by = h * 0.5f
                val br = h * 0.11f
                val bd = h * 0.19f
                listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f).forEach { (dx, dy) ->
                    val x = bx + dx * bd
                    val y = by + dy * bd
                    c.drawCircle(x, y, br, fill)
                    c.drawCircle(x, y, br, rim)
                }
            }
            Layouts.TWIN -> {
                val r = h * 0.24f
                val ly = h * 0.5f
                val lx = w * 0.26f
                val rx = w * 0.74f
                c.drawCircle(lx, ly, r, fill)
                c.drawCircle(lx, ly, r, rim)
                c.drawCircle(lx + r * 0.45f, ly - r * 0.3f, r * 0.4f, knob)
                c.drawCircle(rx, ly, r, fill)
                c.drawCircle(rx, ly, r, rim)
                c.drawCircle(rx - r * 0.35f, ly + r * 0.35f, r * 0.4f, knob)
            }
            else -> {
                val y = h * 0.5f
                c.drawLine(w * 0.2f, y, w * 0.6f, y, strokePaint)
                c.drawLine(w * 0.6f, y, w * 0.52f, y - h * 0.1f, strokePaint)
                c.drawLine(w * 0.6f, y, w * 0.52f, y + h * 0.1f, strokePaint)
                c.drawCircle(w * 0.8f, y, h * 0.12f, fill)
                c.drawCircle(w * 0.8f, y, h * 0.12f, rim)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = Ui.dp(88f)
        setMeasuredDimension(
            resolveSize(side, widthMeasureSpec),
            resolveSize(side, heightMeasureSpec),
        )
    }
}
