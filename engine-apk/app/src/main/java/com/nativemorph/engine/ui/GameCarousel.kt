package com.nativemorph.engine.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * PS5-main-menu style game row: a horizontally snapping carousel of large
 * game cards. The card nearest the center grows, gains a violet glow and
 * full opacity; neighbours shrink and dim. Pure touch + d-pad friendly.
 */
class GameCarousel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    companion object {
        const val CARD_W_DP = 172f
        const val CARD_H_DP = 228f
        private const val MAX_SCALE = 1.17f
        private const val MIN_SCALE = 0.86f
        private const val MIN_ALPHA = 0.45f
    }

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val cards = mutableListOf<FrameLayout>()
    private val tags = mutableListOf<Any?>()

    var onSelected: ((position: Int) -> Unit)? = null
    var onCardTap: ((position: Int) -> Unit)? = null
    var onCardLongPress: ((position: Int) -> Unit)? = null

    private val settle = Runnable { snap() }
    private var selectedPosition = 0

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(
            row,
            FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && cards.isNotEmpty()) {
            padEdges()
            applyTransforms()
        }
    }

    private fun padEdges() {
        val side = (width - Ui.dp(CARD_W_DP * MAX_SCALE)) / 2
        row.setPadding(side, 0, side, 0)
    }

    fun clearCards() {
        cards.clear()
        tags.clear()
        row.removeAllViews()
    }

    fun addCard(content: View, tag: Any?): FrameLayout {
        val card = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(CARD_W_DP), Ui.dp(CARD_H_DP)).apply {
                setMargins(Ui.dp(13f), 0, Ui.dp(13f), 0)
            }
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = Ui.dp(20f).toFloat()
                setColor(Color.TRANSPARENT)
            }
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            setOnClickListener {
                val idx = cards.indexOf(this)
                if (idx >= 0) {
                    if (idx == selectedPosition) onCardTap?.invoke(idx) else snapTo(idx)
                }
            }
            setOnLongClickListener {
                val idx = cards.indexOf(this)
                if (idx >= 0) onCardLongPress?.invoke(idx)
                true
            }
            isFocusable = true
        }
        cards.add(card)
        tags.add(tag)
        row.addView(card)
        return card
    }

    fun cardAt(position: Int): FrameLayout? = cards.getOrNull(position)
    fun tagAt(position: Int): Any? = tags.getOrNull(position)
    val cardCount: Int get() = cards.size

    fun snapTo(position: Int) {
        if (cards.isEmpty()) return
        val target = position.coerceIn(0, cards.size - 1)
        val side = (width - Ui.dp(CARD_W_DP * MAX_SCALE)) / 2
        val x = cards[target].left - side
        smoothScrollTo(x.coerceAtLeast(0), 0)
    }

    private fun snap() {
        if (cards.isEmpty() || width == 0) return
        val center = scrollX + width / 2
        var best = 0
        var bestD = Int.MAX_VALUE
        cards.forEachIndexed { i, c ->
            val d = kotlin.math.abs((c.left + c.right) / 2 - center)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best != selectedPosition) {
            selectedPosition = best
            onSelected?.invoke(best)
        }
        snapTo(best)
    }

    fun selectInitial(position: Int) {
        selectedPosition = position
        viewTreeObserver.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width > 0 && cards.isNotEmpty()) {
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                    padEdges()
                    val side = (width - Ui.dp(CARD_W_DP * MAX_SCALE)) / 2
                    val left = cards[position.coerceIn(0, cards.size - 1)].left
                    scrollTo((left - side).coerceAtLeast(0), 0)
                    applyTransforms()
                }
            }
        })
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        applyTransforms()
        removeCallbacks(settle)
        postDelayed(settle, 220)
    }

    override fun fling(velocityX: Int) {
        super.fling((velocityX * 0.55f).toInt()) // gentler fling so snap wins
    }

    private fun applyTransforms() {
        if (width == 0) return
        val center = scrollX + width / 2f
        cards.forEach { c ->
            val cardCenter = (c.left + c.right) / 2f
            val dist = kotlin.math.abs(cardCenter - center) / (width * 0.5f)
            val closeness = 1f - dist.coerceIn(0f, 1f)
            val scale = MIN_SCALE + (MAX_SCALE - MIN_SCALE) * closeness
            val alpha = MIN_ALPHA + (1f - MIN_ALPHA) * closeness
            c.pivotX = c.width / 2f
            c.pivotY = c.height / 2f
            c.scaleX = scale
            c.scaleY = scale
            c.alpha = alpha
            (c.getChildAt(0) as? GameCardView)?.setGlow(closeness)
        }
    }

    // ---- card content ----------------------------------------------------

    /**
     * The PS5-style tile: rounded art, violet glow for the selected card,
     * letter fallback when the game has no decodable icon.
     */
    class GameCardView(context: Context) : FrameLayout(context) {

        fun bind(
            bitmap: Bitmap?,
            title: String,
            engine: String,
        ) {
            removeAllViews()
            val corner = Ui.dp(20f).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(0xFF171221.toInt())
            }
            clipToOutline = true
            if (bitmap != null) {
                val d = RoundedBitmapDrawableFactory.create(resources, bitmap).apply {
                    cornerRadius = corner
                }
                ImageView(context).apply {
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    addView(
                        this,
                        FrameLayout.LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                // dark scrim so badges stay readable on bright art
                addView(
                    android.view.View(context).apply {
                        background = GradientDrawable().apply {
                            orientation = GradientDrawable.Orientation.BOTTOM_TOP
                            colors = intArrayOf(0xCC0B0714.toInt(), Color.TRANSPARENT)
                            cornerRadius = corner
                        }
                    },
                    FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                    ),
                )
            } else {
                addView(
                    TextView(context).apply {
                        text = title.trim().take(2).uppercase()
                        setTextColor(0xFF9C86FF.toInt())
                        textSize = 64f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            addView(
                TextView(context).apply {
                    text = engine
                    setTextColor(0xFFEDF0F7.toInt())
                    textSize = 10f
                    letterSpacing = 0.09f
                    setPadding(Ui.dp(10f), Ui.dp(5f), Ui.dp(10f), Ui.dp(5f))
                    background = GradientDrawable().apply {
                        cornerRadius = Ui.dp(8f).toFloat()
                        setColor(0x996C4DFF.toInt())
                    }
                },
                FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START,
                ).apply { setMargins(Ui.dp(10f), 0, 0, Ui.dp(10f)) },
            )
        }

        private var glow = -1f

        fun setGlow(v: Float) {
            if (kotlin.math.abs(v - glow) < 0.02f) return
            glow = v
            if (Build.VERSION.SDK_INT >= 28) {
                outlineAmbientShadowColor = 0xFF6C4DFF.toInt()
                outlineSpotShadowColor = 0xFF9C86FF.toInt()
            }
            elevation = 2f + 16f * v
        }
    }
}
