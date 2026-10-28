package com.nativemorph.engine.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * Material Symbols Rounded icon font (Google, Apache-2.0), downloaded from
 * github.com/google/material-design-icons and subset to the glyphs listed
 * in IconGlyphs. Icons render by direct PUA codepoint - no ligatures, so it
 * works on every Android version including minSdk 24.
 */
object Icons {
    private var cached: Typeface? = null

    fun tf(ctx: Context): Typeface =
        cached ?: run {
            val t = Typeface.createFromAsset(ctx.assets, "fonts/material_symbols.ttf")
            cached = t
            t
        }

    /** An icon glyph view; tint with setTextColor. */
    fun icon(
        ctx: Context,
        glyph: String,
        sizeDp: Float,
        tint: Int,
    ): TextView = TextView(ctx).apply {
        text = glyph
        typeface = tf(ctx)
        setTextColor(tint)
        textSize = sizeDp
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    /** Circular icon button (PS5 function-row style). */
    fun circleButton(
        ctx: Context,
        glyph: String,
        sizeDp: Float,
        tint: Int,
        bg: Int,
        onClick: (TextView) -> Unit,
    ): TextView = icon(ctx, glyph, sizeDp, tint).apply {
        val pad = ((sizeDp * 44f / 24f) - sizeDp) / 2f
        setPadding(padDp(ctx, pad), padDp(ctx, pad), padDp(ctx, pad), padDp(ctx, pad))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick(this) }
    }

    private fun padDp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
        ).toInt()
}
