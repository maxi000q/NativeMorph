package com.nativemorph.engine.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.nativemorph.engine.ui.Ui

/**
 * Multi-touch virtual control toolkit.
 *
 * A TouchLayoutView owns a set of Control objects (buttons / d-pads /
 * sticks). ControlOverlay routes raw MotionEvents into feed(); each pointer
 * is bound to the control it first landed on, so chords (dpad + buttons,
 * two sticks) work. Controls emit synthetic KeyEvents (and optionally
 * MotionEvents) through GameInputInjector into the engine view below.
 */
abstract class TouchLayoutView(
    context: Context,
    val injector: GameInputInjector,
) : View(context) {

    var buttonScale = 1f
    var buttonAlpha = 0.55f
    var haptics = true

    protected val controls = ArrayList<Control>()
    private val pointers = HashMap<Int, Control>()

    protected fun addControl(c: Control) {
        c.owner = this
        controls.add(c)
    }

    /** Route one raw overlay event. Returns true if any control consumed it. */
    fun feed(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val c = hitTest(ev.getX(i), ev.getY(i))
                if (c != null) {
                    pointers[ev.getPointerId(i)] = c
                    c.onDown(ev.getX(i), ev.getY(i))
                }
                invalidate()
                return c != null
            }
            MotionEvent.ACTION_MOVE -> {
                var any = false
                for (i in 0 until ev.pointerCount) {
                    val c = pointers[ev.getPointerId(i)]
                    if (c != null) {
                        c.onMove(ev.getX(i), ev.getY(i))
                        any = true
                    }
                }
                if (any) invalidate()
                return any
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val c = pointers.remove(ev.getPointerId(i))
                c?.onUp()
                invalidate()
                return c != null
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.values.forEach { it.onUp() }
                pointers.clear()
                invalidate()
                return true
            }
        }
        return false
    }

    private fun hitTest(x: Float, y: Float): Control? {
        var best: Control? = null
        var bestD = Float.MAX_VALUE
        for (c in controls) {
            if (c.contains(x, y)) {
                val d = c.distance2(x, y)
                if (d < bestD) {
                    best = c
                    bestD = d
                }
            }
        }
        return best
    }

    fun buzz() {
        if (haptics) injector.vibrate(12)
    }

    protected open fun onLayoutChanged(w: Int, h: Int) {}

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        onLayoutChanged(w, h)
    }

    // ------------------------------------------------------------ drawing
    val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xB3FFFFFF.toInt()
    }
    val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2EFFFFFF.toInt()
    }
    val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x996C4DFF.toInt()
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        isFakeBoldText = true
    }
    val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xE6EDF0F7.toInt()
    }

    protected fun dp(v: Float): Float = Ui.dp(v) * buttonScale

    override fun onDraw(canvas: Canvas) {
        for (c in controls) c.draw(canvas, this)
    }
}

// ===========================================================================
abstract class Control {
    lateinit var owner: TouchLayoutView

    abstract fun contains(x: Float, y: Float): Boolean
    abstract fun distance2(x: Float, y: Float): Float
    open fun onDown(x: Float, y: Float) {}
    open fun onMove(x: Float, y: Float) {}
    open fun onUp() {}
    abstract fun draw(c: Canvas, v: TouchLayoutView)

    protected fun key(code: Int, down: Boolean) {
        owner.injector.key(code, down)
    }

    protected fun buzz() = owner.buzz()
}

/** Round button emitting a keyboard key while pressed. */
class KeyButton(
    private val cx: Float,
    private val cy: Float,
    private val radius: Float,
    private val label: String,
    private val keyCode: Int,
) : Control() {

    var pressed = false
        private set

    override fun contains(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        val r = radius * 1.35f
        return dx * dx + dy * dy <= r * r
    }

    override fun distance2(x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy
    }

    override fun onDown(x: Float, y: Float) {
        if (!pressed) {
            pressed = true
            buzz()
            key(keyCode, true)
        }
    }

    override fun onUp() {
        if (pressed) {
            pressed = false
            key(keyCode, false)
        }
    }

    override fun draw(c: Canvas, v: TouchLayoutView) {
        v.idlePaint.alpha = (255 * v.buttonAlpha * 0.30f).toInt()
        v.activePaint.alpha = (255 * v.buttonAlpha).toInt()
        c.drawCircle(cx, cy, radius, if (pressed) v.activePaint else v.idlePaint)
        v.rimPaint.alpha = (255 * v.buttonAlpha).toInt()
        c.drawCircle(cx, cy, radius, v.rimPaint)
        v.labelPaint.textSize = radius * 0.85f
        c.drawText(label, cx, cy - (v.labelPaint.descent() + v.labelPaint.ascent()) / 2, v.labelPaint)
    }
}

/**
 * 8-way directional pad: any direction combination (incl. diagonals) is
 * emitted as held/released key transitions with a small dead-zone.
 */
class DPad(
    private val cx: Float,
    private val cy: Float,
    private val radius: Float,
    private val keys: IntArray,   // [up, down, left, right]
) : Control() {

    private val active = BooleanArray(4)

    override fun contains(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        val r = radius * 1.6f
        return dx * dx + dy * dy <= r * r
    }

    override fun distance2(x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy
    }

    override fun onDown(x: Float, y: Float) {
        buzz()
        update(x, y)
    }

    override fun onMove(x: Float, y: Float) = update(x, y)

    override fun onUp() {
        for (i in 0..3) setActive(i, false)
    }

    private fun update(x: Float, y: Float) {
        val nx = (x - cx) / radius
        val ny = (y - cy) / radius
        if (Math.sqrt((nx * nx + ny * ny).toDouble()) < 0.22) {
            for (i in 0..3) setActive(i, false)
            return
        }
        setActive(0, ny < -0.45f)   // up
        setActive(1, ny > 0.45f)   // down
        setActive(2, nx < -0.45f)  // left
        setActive(3, nx > 0.45f)   // right
    }

    private fun setActive(i: Int, on: Boolean) {
        if (active[i] != on) {
            active[i] = on
            key(keys[i], on)
        }
    }

    override fun draw(c: Canvas, v: TouchLayoutView) {
        v.idlePaint.alpha = (255 * v.buttonAlpha * 0.22f).toInt()
        v.rimPaint.alpha = (255 * v.buttonAlpha).toInt()
        c.drawCircle(cx, cy, radius, v.idlePaint)
        c.drawCircle(cx, cy, radius, v.rimPaint)
        val d = radius * 0.62f
        val r = radius * 0.34f
        val dirs = listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)
        for ((i, dir) in dirs.withIndex()) {
            val px = cx + dir.first * d
            val py = cy + dir.second * d
            if (active[i]) {
                v.activePaint.alpha = (255 * v.buttonAlpha).toInt()
                c.drawCircle(px, py, r, v.activePaint)
            } else {
                v.idlePaint.alpha = (255 * v.buttonAlpha * 0.35f).toInt()
                c.drawCircle(px, py, r, v.idlePaint)
            }
        }
    }
}

/** Analog stick; reports a normalized vector, knob drawn for feedback. */
class Stick(
    private val cx: Float,
    private val cy: Float,
    val radius: Float,
    private val deadZone: Float = 0.18f,
) : Control() {

    var vx = 0f
    var vy = 0f
        private set
    var engaged = false
        private set

    private var onVectorCb: ((Float, Float) -> Unit)? = null
    private var onReleaseCb: (() -> Unit)? = null

    fun onVector(cb: (Float, Float) -> Unit) { onVectorCb = cb }
    fun onRelease(cb: () -> Unit) { onReleaseCb = cb }

    private var knobX = cx
    private var knobY = cy

    override fun contains(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        val r = radius * 1.7f
        return dx * dx + dy * dy <= r * r
    }

    override fun distance2(x: Float, y: Float): Float {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy
    }

    override fun onDown(x: Float, y: Float) {
        engaged = true
        buzz()
        update(x, y)
    }

    override fun onMove(x: Float, y: Float) = update(x, y)

    override fun onUp() {
        engaged = false
        vx = 0f; vy = 0f
        knobX = cx; knobY = cy
        onReleaseCb?.invoke()
    }

    private fun update(x: Float, y: Float) {
        var nx = (x - cx) / radius
        var ny = (y - cy) / radius
        val mag = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat()
        if (mag > 1f) {
            nx /= mag
            ny /= mag
        }
        vx = if (mag < deadZone) 0f else nx
        vy = if (mag < deadZone) 0f else ny
        knobX = cx + nx * radius * 0.62f
        knobY = cy + ny * radius * 0.62f
        onVectorCb?.invoke(vx, vy)
    }

    override fun draw(c: Canvas, v: TouchLayoutView) {
        v.idlePaint.alpha = (255 * v.buttonAlpha * 0.20f).toInt()
        v.rimPaint.alpha = (255 * v.buttonAlpha).toInt()
        c.drawCircle(cx, cy, radius, v.idlePaint)
        c.drawCircle(cx, cy, radius, v.rimPaint)
        c.drawCircle(knobX, knobY, radius * 0.38f, v.knobPaint)
        if (engaged) {
            v.activePaint.alpha = (255 * v.buttonAlpha * 0.55f).toInt()
            c.drawCircle(knobX, knobY, radius * 0.38f, v.activePaint)
        }
    }
}

/** Small pill trigger at a screen corner. */
class Trigger(
    private val cx: Float,
    private val cy: Float,
    private val rx: Float,
    private val ry: Float,
    private val label: String,
    private val keyCode: Int,
) : Control() {

    var pressed = false
        private set

    override fun contains(x: Float, y: Float): Boolean =
        Math.abs(x - cx) <= rx * 1.3f && Math.abs(y - cy) <= ry * 1.3f

    override fun distance2(x: Float, y: Float): Float =
        (x - cx) * (x - cx) + (y - cy) * (y - cy)

    override fun onDown(x: Float, y: Float) {
        if (!pressed) {
            pressed = true
            buzz()
            key(keyCode, true)
        }
    }

    override fun onUp() {
        if (pressed) {
            pressed = false
            key(keyCode, false)
        }
    }

    override fun draw(c: Canvas, v: TouchLayoutView) {
        v.idlePaint.alpha = (255 * v.buttonAlpha * 0.28f).toInt()
        v.activePaint.alpha = (255 * v.buttonAlpha).toInt()
        val p = if (pressed) v.activePaint else v.idlePaint
        val rect = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawRoundRect(rect, ry, ry, p)
        v.rimPaint.alpha = (255 * v.buttonAlpha).toInt()
        c.drawRoundRect(rect, ry, ry, v.rimPaint)
        v.labelPaint.textSize = ry * 0.9f
        c.drawText(label, cx, cy - (v.labelPaint.descent() + v.labelPaint.ascent()) / 2, v.labelPaint)
    }
}
