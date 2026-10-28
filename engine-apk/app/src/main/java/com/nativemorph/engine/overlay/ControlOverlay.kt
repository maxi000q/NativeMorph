package com.nativemorph.engine.overlay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.nativemorph.engine.Game3Activity
import com.nativemorph.engine.GameActivity
import com.nativemorph.engine.R
import com.nativemorph.engine.core.GameLibrary
import com.nativemorph.engine.core.Layouts
import com.nativemorph.engine.core.Prefs
import com.nativemorph.engine.ui.Icons
import com.nativemorph.engine.ui.IconGlyphs
import com.nativemorph.engine.ui.Ui
import org.godotengine.godot.utils.ProcessPhoenix

/**
 * The floating layer that sits on top of ANY running game:
 *  - a draggable handle strip (always visible, edge-clamped): gear, virtual
 *    mouse toggle, and - while the mouse is on - dedicated LEFT and RIGHT
 *    click buttons,
 *  - the active mobile UI layout (classic / twin / gesture), optionally with
 *    the camera joystick from Settings,
 *  - the virtual mouse: a PS-style cursor moved by dragging anywhere (the
 *    whole screen becomes a touchpad). LEFT click injects a real synthetic
 *    finger at the cursor (works on every touch-driven control, and Godot's
 *    emulate-mouse-from-touch also drives mouse-aware UI); RIGHT click
 *    injects a real secondary mouse button. Tap-to-click on the pad, hold
 *    LEFT + drag for drag gestures,
 *  - a quick panel for live switching, button tuning, restart and exit.
 *
 * v1.3.3: clicks go through GameInputInjector's render-view-targeted event
 * streams, so they actually reach the game (the previous build dispatched
 * them at the hidden GodotEditText, where they did nothing but pop the
 * on-screen keyboard).
 */
class ControlOverlay @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : android.widget.FrameLayout(context, attrs) {

    private var hostActivity: Activity? = null
    private var gameId = ""
    private var engineFlavor = 4
    private lateinit var inj: GameInputInjector

    private var layoutId = Layouts.CLASSIC
    private var layoutView: TouchLayoutView? = null

    // ------------------------------------------------------------ handle strip
    private var gearX = 0f
    private var gearY = 0f
    private val gearR = Ui.dp(20f).toFloat()
    private val stripGap: Float get() = gearR * 2.75f
    private fun mouseY(): Float = gearY + stripGap
    private fun rmbY(): Float = gearY + stripGap * 2f
    private fun lmbY(): Float = gearY + stripGap * 3f

    private class HandlePress(val code: Int, val offX: Float, val offY: Float) {
        var moved = false
    }

    private val handlePtrs = HashMap<Int, HandlePress>()

    // ------------------------------------------------------------ virtual mouse
    private var mouseMode = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var lmbHeld = false
    private var rmbHeld = false

    private var padId = -1
    private var padLastX = 0f
    private var padLastY = 0f
    private var padDist = 0f
    private val padConsumed = HashSet<Int>()

    private var quickPanel: LinearLayout? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val gearBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC141926.toInt() }
    private val gearRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF6C4DFF.toInt()
    }
    private val handleBgOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC241A4D.toInt() }
    private val handleRimOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = 0xFF9C86FF.toInt()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDF0F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDF0F7.toInt()
        style = Paint.Style.FILL
    }
    private val sideActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9C86FF.toInt()
        style = Paint.Style.FILL
    }
    private val sideIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val sideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF141926.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cursorRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF6C4DFF.toInt()
    }
    private val cursorShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000.toInt() }
    private val cursorPath = Path()

    // Material Symbols Rounded glyph (downloaded icon font, not hand-drawn)
    private val gearIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDF0F7.toInt()
        textAlign = Paint.Align.CENTER
    }

    init {
        setWillNotDraw(false)
        // classic cursor arrow, tip at (0,0), ~28dp tall
        val s = Ui.dp(1.55f).toFloat()
        val pts = floatArrayOf(
            0f, 0f, 0f, 16.5f, 3.8f, 12.9f,
            6.2f, 18.3f, 8.7f, 17.2f, 6.3f, 11.9f, 11.5f, 11.9f,
        )
        cursorPath.moveTo(pts[0] * s, pts[1] * s)
        var i = 2
        while (i < pts.size) {
            cursorPath.lineTo(pts[i] * s, pts[i + 1] * s)
            i += 2
        }
        cursorPath.close()
    }

    // ------------------------------------------------------------- attach
    @SuppressLint("ClickableViewAccessibility")
    fun attach(activity: Activity, id: String, flavor: Int = 4) {
        hostActivity = activity
        gameId = id
        engineFlavor = if (flavor == 3) 3 else 4
        inj = GameInputInjector(activity)

        val meta = GameLibrary.get(context, id)
        layoutId = meta?.layoutOverride ?: Prefs.layout(context)
        applyPrefsFromStore()
        rebuildLayout()
        // The engine's render view attaches asynchronously after the fragment
        // is created; retry until the injector finds it.
        retryFindEngineView(0)
    }

    private fun retryFindEngineView(attempt: Int) {
        handler.postDelayed({
            if (!inj.attach() && attempt < 20) retryFindEngineView(attempt + 1)
        }, 500)
    }

    private fun applyPrefsFromStore() {
        layoutView?.let {
            it.buttonScale = Prefs.buttonScale(context)
            it.buttonAlpha = Prefs.buttonAlpha(context)
            it.haptics = Prefs.haptics(context)
        }
    }

    private fun rebuildLayout() {
        layoutView?.let { removeView(it) }
        val camStick = Prefs.cameraStick(context)
        layoutView = when (layoutId) {
            Layouts.TWIN -> TwinStickLayout(context, inj)
            Layouts.GESTURE -> GestureLayout(context, inj, camStick)
            else -> ClassicPadLayout(context, inj, camStick)
        }
        layoutView?.let { lv ->
            lv.buttonScale = Prefs.buttonScale(context)
            lv.buttonAlpha = Prefs.buttonAlpha(context)
            lv.haptics = Prefs.haptics(context)
            addView(
                lv,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        invalidate()
    }

    private fun setLayoutLive(id: String) {
        layoutId = id
        Prefs.setLayout(context, id)
        GameLibrary.get(context, gameId)?.let {
            GameLibrary.setLayoutOverride(context, it, null)
        }
        rebuildLayout()
    }

    // --------------------------------------------------------- touch router
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 1. quick panel is a real widget tree: route normally while inside it
        val qp = quickPanel
        if (qp != null && qp.visibility == VISIBLE) {
            qp.getLocationInWindow(tmpLoc)
            val l = tmpLoc[0] - Ui.dp(12f)
            val t = tmpLoc[1] - Ui.dp(12f)
            val r = tmpLoc[0] + qp.width + Ui.dp(12f)
            val b = tmpLoc[1] + qp.height + Ui.dp(12f)
            if (ev.rawX >= l && ev.rawX <= r && ev.rawY >= t && ev.rawY <= b) {
                return super.dispatchTouchEvent(ev)
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                hideQuickPanel()
                return true
            }
        }

        // 2. handle strip: gear / mouse toggle / left + right click buttons
        if (handleTouches(ev)) return true

        // 3. active layout (controls consume; GestureLayout only consumes
        //    when the optional camera stick is engaged)
        val lv = layoutView
        if (lv != null && lv.feed(ev)) return true

        // 4. virtual mouse touchpad: with the mouse on, every remaining
        //    touch moves the cursor instead of reaching the game
        if (mouseMode && touchpad(ev)) return true

        // 5. gesture observer for the minimal layout
        if (lv is GestureLayout) lv.observe(ev)

        // 6. everything else falls through to the game below this overlay
        return inj.forwardToGame(ev)
    }

    private val tmpLoc = IntArray(2)

    // ------------------------------------------------------------ handle strip
    private fun handleAt(x: Float, y: Float): Int {
        val hit = gearR * 2.2f
        if (mouseMode) {
            var dx = x - gearX
            var dy = y - lmbY()
            if (dx * dx + dy * dy <= hit * hit) return HANDLE_LMB
            dx = x - gearX
            dy = y - rmbY()
            if (dx * dx + dy * dy <= hit * hit) return HANDLE_RMB
        }
        var dx = x - gearX
        var dy = y - mouseY()
        if (dx * dx + dy * dy <= hit * hit) return HANDLE_MOUSE
        dx = x - gearX
        dy = y - gearY
        if (dx * dx + dy * dy <= hit * hit) return HANDLE_GEAR
        return -1
    }

    private fun handleTouches(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val h = handleAt(ev.getX(i), ev.getY(i))
                if (h >= 0) {
                    handlePtrs[ev.getPointerId(i)] =
                        HandlePress(h, gearX - ev.getX(i), gearY - ev.getY(i))
                    when (h) {
                        HANDLE_LMB -> {
                            lmbHeld = true
                            inj.touchDown(MOUSE_POINTER, cursorX, cursorY)
                            buzz()
                        }
                        HANDLE_RMB -> {
                            rmbHeld = true
                            inj.mouseDown(cursorX, cursorY, secondary = true)
                            buzz()
                        }
                    }
                }
                return h >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                var any = false
                for (i in 0 until ev.pointerCount) {
                    val p = handlePtrs[ev.getPointerId(i)] ?: continue
                    any = true
                    // only the gear / mouse toggle drag the strip around;
                    // the click buttons stay put while pressed
                    if (p.code == HANDLE_GEAR || p.code == HANDLE_MOUSE) {
                        val nx = ev.getX(i) + p.offX
                        val ny = ev.getY(i) + p.offY
                        if (Math.abs(nx - gearX) > Ui.dp(6f) || Math.abs(ny - gearY) > Ui.dp(6f)) {
                            p.moved = true
                        }
                        clampStrip(nx, ny)
                    }
                }
                if (any) invalidate()
                return any
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = ev.actionIndex
                val p = handlePtrs.remove(ev.getPointerId(i))
                if (p == null) return handlePtrs.isNotEmpty()
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    releaseClicksIfHeld()
                    return true
                }
                when (p.code) {
                    HANDLE_GEAR -> if (!p.moved) toggleQuickPanel()
                    HANDLE_MOUSE -> if (!p.moved) setMouseMode(!mouseMode)
                    HANDLE_LMB -> releaseLeftIfHeld()
                    HANDLE_RMB -> releaseRightIfHeld()
                }
                return true
            }
        }
        return handlePtrs.isNotEmpty()
    }

    private fun releaseLeftIfHeld() {
        if (lmbHeld) {
            lmbHeld = false
            inj.touchUp(MOUSE_POINTER, cursorX, cursorY)
            buzz()
        }
    }

    private fun releaseRightIfHeld() {
        if (rmbHeld) {
            rmbHeld = false
            inj.mouseUp(cursorX, cursorY, secondary = true)
            buzz()
        }
    }

    private fun releaseClicksIfHeld() {
        releaseLeftIfHeld()
        releaseRightIfHeld()
    }

    private fun clampStrip(nx: Float, ny: Float) {
        val bottomExtra = if (mouseMode) stripGap * 3f else stripGap
        gearX = nx.coerceIn(gearR, width - gearR)
        gearY = ny.coerceIn(
            gearR + Ui.dp(24f),
            (height - gearR - bottomExtra).coerceAtLeast(gearR * 2f),
        )
    }

    // ------------------------------------------------------------ virtual mouse
    private fun setMouseMode(on: Boolean) {
        if (mouseMode == on) return
        mouseMode = on
        buzz()
        if (on) {
            cursorX = width * 0.5f
            cursorY = height * 0.42f
            inj.mouseMove(cursorX, cursorY)
        } else {
            releaseClicksIfHeld()
        }
        clampStrip(gearX, gearY)
        invalidate()
    }

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(2f, (width - 2f).coerceAtLeast(4f))
        cursorY = (cursorY + dy).coerceIn(2f, (height - 2f).coerceAtLeast(4f))
        // keep every active stream glued to the cursor:
        //  - left button: the synthetic finger follows
        //  - right button: a real secondary-button drag follows
        //  - otherwise: plain hover motion
        if (lmbHeld) {
            inj.touchMove(MOUSE_POINTER, cursorX, cursorY)
        } else if (rmbHeld) {
            inj.mouseMove(cursorX, cursorY, MotionEvent.BUTTON_SECONDARY)
        } else {
            inj.mouseMove(cursorX, cursorY)
        }
        invalidate()
    }

    private fun touchpad(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                padConsumed.add(ev.getPointerId(i))
                if (padId == -1) {
                    padId = ev.getPointerId(i)
                    padLastX = ev.getX(i)
                    padLastY = ev.getY(i)
                    padDist = 0f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    if (ev.getPointerId(i) != padId) continue
                    val dx = ev.getX(i) - padLastX
                    val dy = ev.getY(i) - padLastY
                    padLastX = ev.getX(i)
                    padLastY = ev.getY(i)
                    padDist += Math.abs(dx) + Math.abs(dy)
                    // touchpad gain: cursor travels a little further than the finger
                    moveCursor(dx * 1.5f, dy * 1.5f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                val id = ev.getPointerId(i)
                if (id == padId) {
                    // a still tap on the pad = left click at the cursor
                    if (padDist < Ui.dp(10f) && !lmbHeld && !rmbHeld) {
                        inj.touchDown(MOUSE_POINTER, cursorX, cursorY)
                        inj.touchUp(MOUSE_POINTER, cursorX, cursorY)
                        buzz()
                    }
                    padId = -1
                }
                padConsumed.remove(id)
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    padConsumed.clear()
                    padId = -1
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                padConsumed.clear()
                padId = -1
                return true
            }
        }
        return padConsumed.isNotEmpty()
    }

    private fun buzz() {
        if (Prefs.haptics(context)) inj.vibrate(12)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (gearX == 0f && gearY == 0f && w > 0) {
            gearX = w - gearR * 1.9f
            gearY = gearR * 1.9f + Ui.dp(24f)
        }
        if (cursorX == 0f && cursorY == 0f && w > 0) {
            cursorX = w * 0.5f
            cursorY = h * 0.42f
        }
        clampStrip(gearX, gearY)
    }

    // ------------------------------------------------------------- drawing
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        // gear handle (Material Symbols glyph)
        canvas.drawCircle(gearX, gearY, gearR, gearBg)
        canvas.drawCircle(gearX, gearY, gearR, gearRim)
        if (gearIcon.typeface == null)
            gearIcon.typeface = Icons.tf(context)
        gearIcon.textSize = gearR * 1.15f
        val fm = gearIcon.fontMetrics
        val ty = gearY - (fm.ascent + fm.descent) / 2f
        canvas.drawText(IconGlyphs.SETTINGS, gearX, ty, gearIcon)

        // mouse toggle
        drawMouseHandle(canvas, gearX, mouseY())
        if (mouseMode) {
            // right + left click buttons
            drawMouseButton(canvas, gearX, rmbY(), right = true)
            drawMouseButton(canvas, gearX, lmbY(), right = false)
            // the cursor sits on top of everything
            drawCursor(canvas)
        }
    }

    private fun drawMouseHandle(c: Canvas, cx: Float, cy: Float) {
        val r = gearR * 0.92f
        c.drawCircle(cx, cy, r, if (mouseMode) handleBgOn else gearBg)
        c.drawCircle(cx, cy, r, if (mouseMode) handleRimOn else gearRim)
        // mouse body: rounded capsule + middle divider + wheel
        val bw = r * 0.62f
        val bh = r * 0.95f
        val rect = RectF(cx - bw, cy - bh, cx + bw, cy + bh)
        c.drawRoundRect(rect, bw, bw, iconPaint)
        c.drawLine(cx, cy - bh, cx, cy - bh * 0.15f, iconPaint)
        c.drawLine(cx - bw, cy - bh * 0.15f, cx + bw, cy - bh * 0.15f, iconPaint)
        c.drawCircle(cx, cy - bh * 0.58f, r * 0.10f, iconFillPaint)
    }

    /**
     * Round click button with a mouse-body glyph; the pressed side is filled
     * with the accent color and carries the L / R letter.
     */
    private fun drawMouseButton(c: Canvas, cx: Float, cy: Float, right: Boolean) {
        val pressed = if (right) rmbHeld else lmbHeld
        val r = if (right) gearR * 0.92f else gearR * 1.02f
        c.drawCircle(cx, cy, r, if (pressed) handleBgOn else gearBg)
        c.drawCircle(cx, cy, r, if (pressed) handleRimOn else gearRim)

        val bw = r * 0.66f
        val bh = r * 0.92f
        val top = cy - bh
        val body = RectF(cx - bw, top, cx + bw, cy + bh)
        c.drawRoundRect(body, bw * 0.9f, bw * 0.9f, iconPaint)
        c.drawLine(cx, top, cx, top + bh * 1.9f, iconPaint)
        c.drawLine(cx - bw, top, cx + bw, top, iconPaint)

        val half = RectF(
            if (right) cx else cx - bw,
            top,
            if (right) cx + bw else cx,
            top + bh * 1.9f,
        )
        c.save()
        c.clipPath(Path().apply {
            addRoundRect(body, bw * 0.9f, bw * 0.9f, Path.Direction.CW)
        })
        c.drawRect(half, if (pressed) sideActivePaint else sideIdlePaint)
        c.restore()

        sideLabelPaint.textSize = r * 0.42f
        sideLabelPaint.color =
            if (pressed) 0xFF141926.toInt() else 0xFFEDF0F7.toInt()
        val lx = if (right) cx + bw * 0.5f else cx - bw * 0.5f
        val lfm = sideLabelPaint.fontMetrics
        c.drawText(
            if (right) "R" else "L", lx,
            top + bh * 0.95f - (lfm.ascent + lfm.descent) / 2f,
            sideLabelPaint,
        )
    }

    private fun drawCursor(c: Canvas) {
        val ox = Ui.dp(1.2f).toFloat()
        c.save()
        c.translate(cursorX, cursorY)
        // soft drop shadow, then white arrow with a violet rim
        c.save()
        c.translate(ox, ox)
        c.drawPath(cursorPath, cursorShadow)
        c.restore()
        c.drawPath(cursorPath, cursorPaint)
        c.drawPath(cursorPath, cursorRim)
        c.restore()
    }

    // ------------------------------------------------------------ quick panel
    private fun toggleQuickPanel() {
        if (quickPanel?.visibility == VISIBLE) hideQuickPanel() else showQuickPanel()
    }

    private fun hideQuickPanel() {
        quickPanel?.visibility = GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showQuickPanel() {
        val qp = quickPanel ?: buildQuickPanel().also { quickPanel = it }
        qp.visibility = VISIBLE
        qp.requestLayout()
    }

    private fun buildQuickPanel(): LinearLayout {
        val ctx = context
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(16f), Ui.dp(14f), Ui.dp(16f), Ui.dp(14f))
            background = GradientDrawable().apply {
                setColor(0xF2141926.toInt())
                cornerRadius = Ui.dp(16f).toFloat()
                setStroke(1, Ui.color(ctx, R.color.stroke))
            }
        }

        val title = TextView(ctx).apply {
            text = "Overlay Controls"
            textSize = 16f
            setTextColor(Ui.color(ctx, R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(title)
        panel.addView(
            TextView(ctx).apply {
                text = "Applies instantly, above any game"
                textSize = 11f
                setTextColor(Ui.color(ctx, R.color.text_secondary))
                setPadding(0, Ui.dp(2f), 0, Ui.dp(8f))
            }
        )

        // layout switcher
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (id in Layouts.all) {
            val b = Button(ctx).apply {
                text = Layouts.label(id)
                textSize = 11f
                setTextColor(
                    if (id == layoutId) 0xFFFFFFFF.toInt() else Ui.color(ctx, R.color.text_secondary)
                )
                background = GradientDrawable().apply {
                    setColor(
                        if (id == layoutId) Ui.color(ctx, R.color.accent)
                        else Ui.color(ctx, R.color.bg_card)
                    )
                    cornerRadius = Ui.dp(10f).toFloat()
                }
                setOnClickListener { setLayoutLive(id); refreshPanelSelection(); }
                tag = id
            }
            row.addView(
                b,
                LinearLayout.LayoutParams(0, Ui.dp(40f), 1f).also { m ->
                    m.setMargins(Ui.dp(3f), 0, Ui.dp(3f), 0)
                },
            )
        }
        panel.addView(row)

        fun label(text: String): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(Ui.color(ctx, R.color.text_secondary))
            setPadding(0, Ui.dp(12f), 0, Ui.dp(2f))
        }

        // size slider
        panel.addView(label("Button size"))
        panel.addView(SeekBar(ctx).apply {
            max = 140
            min = 70
            progress = (Prefs.buttonScale(ctx) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setButtonScale(ctx, v / 100f)
                    layoutView?.buttonScale = v / 100f
                    layoutView?.requestLayout()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // opacity slider
        panel.addView(label("Button visibility"))
        panel.addView(SeekBar(ctx).apply {
            max = 100
            min = 30
            progress = (Prefs.buttonAlpha(ctx) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setButtonAlpha(ctx, v / 100f)
                    layoutView?.buttonAlpha = v / 100f
                    layoutView?.invalidate()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        // camera stick switch (live)
        panel.addView(switchRow("Camera stick", Prefs.cameraStick(ctx)) { on ->
            Prefs.setCameraStick(ctx, on)
            rebuildLayout()
        })

        // virtual mouse switch (same as the floating mouse button)
        panel.addView(switchRow("Mouse cursor", mouseMode) { on ->
            setMouseMode(on)
        })

        // restart / exit
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(
            Button(ctx).apply {
                text = "Restart"
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    setColor(Ui.color(ctx, R.color.accent))
                    cornerRadius = Ui.dp(10f).toFloat()
                }
                setOnClickListener {
                    val target =
                        if (engineFlavor == 3) Game3Activity::class.java
                        else GameActivity::class.java
                    val intent = Intent(ctx, target)
                        .putExtra(GameActivity.EXTRA_GAME_ID, gameId)
                        .putExtra("new_launch_requested", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ProcessPhoenix.triggerRebirth(ctx, intent)
                }
            },
            LinearLayout.LayoutParams(0, Ui.dp(42f), 1f).also { it.setMargins(0, 0, Ui.dp(4f), 0) },
        )
        actions.addView(
            Button(ctx).apply {
                text = "Exit game"
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF2A3244.toInt())
                    cornerRadius = Ui.dp(10f).toFloat()
                }
                setOnClickListener { hostActivity?.finish() }
            },
            LinearLayout.LayoutParams(0, Ui.dp(42f), 1f).also { it.setMargins(Ui.dp(4f), 0, 0, Ui.dp(4f)) },
        )
        panel.addView(
            actions,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = Ui.dp(14f)
            },
        )

        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        lp.rightMargin = Ui.dp(16f)
        addView(panel, lp)
        return panel
    }

    private fun switchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(10f), 0, 0)
            addView(
                TextView(ctx).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(Ui.color(ctx, R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            addView(
                Switch(ctx).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, c -> onChange(c) }
                }
            )
        }
    }

    private fun refreshPanelSelection() {
        val qp = quickPanel ?: return
        val row = qp.getChildAt(2)
        if (row is LinearLayout) {
            for (b in (0 until row.childCount).map { row.getChildAt(it) }) {
                if (b is Button) {
                    val id = b.tag as? String ?: continue
                    b.setTextColor(
                        if (id == layoutId) 0xFFFFFFFF.toInt()
                        else Ui.color(context, R.color.text_secondary)
                    )
                    (b.background as? GradientDrawable)?.setColor(
                        if (id == layoutId) Ui.color(context, R.color.accent)
                        else Ui.color(context, R.color.bg_card)
                    )
                }
            }
        }
    }

    companion object {
        private const val HANDLE_GEAR = 0
        private const val HANDLE_MOUSE = 1
        private const val HANDLE_RMB = 2
        private const val HANDLE_LMB = 3

        /** Pointer id of the virtual mouse's synthetic finger. Id 0 is what
         * Godot's emulate-mouse-from-touch uses, so the left click drives
         * BOTH touch-driven controls and mouse-aware UI. */
        private const val MOUSE_POINTER = 0
    }
}
