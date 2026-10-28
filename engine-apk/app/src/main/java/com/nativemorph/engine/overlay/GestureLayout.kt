package com.nativemorph.engine.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Layout 3 - "Gesture Minimal": zero visible buttons. Every touch goes to the
 * game untouched; a passive observer adds gestures on top:
 *   - swipe anywhere -> direction key tap (arrows)
 *   - double-tap    -> SPACE
 *   - two-finger tap -> ENTER (menu/confirm)
 *   - long-press     -> ESCAPE (back/pause)
 *
 * v1.3.2: optional camera joystick on the right half (Settings / quick
 * panel). Touches that land on it rotate the camera with synthetic drags;
 * everything else still passes through and keeps the gesture layer.
 */
class GestureLayout(
    context: Context,
    injector: GameInputInjector,
    private val cameraStick: Boolean = false,
) : TouchLayoutView(context, injector) {

    private val handler = Handler(Looper.getMainLooper())
    private val cam = CameraDrag(injector)

    private class Ptr {
        var startX = 0f
        var startY = 0f
        var startT = 0L
        var moved = false
    }

    private val ptrs = HashMap<Int, Ptr>()
    private var lastTapAt = 0L
    private var longFired = false
    private var twoFingerCandidate = false
    private var twoFingerAt = 0L

    private val longRunnable = Runnable {
        if (ptrs.size == 1 && !longFired) {
            longFired = true
            tapKey(KeyEvent.KEYCODE_ESCAPE)
        }
    }

    /** Observes pass-through events; never consumes them. */
    fun observe(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = Ptr()
                p.startX = ev.x
                p.startY = ev.y
                p.startT = SystemClock.uptimeMillis()
                ptrs[ev.getPointerId(0)] = p
                longFired = false
                twoFingerCandidate = false
                handler.postDelayed(longRunnable, 600)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2) {
                    twoFingerCandidate = true
                    twoFingerAt = SystemClock.uptimeMillis()
                }
                handler.removeCallbacks(longRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val p = ptrs[ev.getPointerId(i)] ?: continue
                    val dx = ev.getX(i) - p.startX
                    val dy = ev.getY(i) - p.startY
                    if (dx * dx + dy * dy > dp(12f) * dp(12f)) {
                        p.moved = true
                        handler.removeCallbacks(longRunnable)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // keep waiting for the final UP to judge the gesture
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRunnable)
                val upIdx = ev.actionIndex
                val upX = ev.getX(upIdx)
                val upY = ev.getY(upIdx)
                val p = ptrs.remove(ev.getPointerId(upIdx))
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL || p == null) {
                    ptrs.clear()
                    return
                }
                val now = SystemClock.uptimeMillis()
                val dur = now - p.startT

                if (twoFingerCandidate && dur < 400 && now - twoFingerAt < 400) {
                    twoFingerCandidate = false
                    ptrs.clear()
                    tapKey(KeyEvent.KEYCODE_ENTER)
                    return
                }
                if (longFired) {
                    longFired = false
                    return
                }
                if (p.moved && dur < 600) {
                    // swipe -> direction tap
                    val dx = upX - p.startX
                    val dy = upY - p.startY
                    val code = when {
                        Math.abs(dx) > Math.abs(dy) ->
                            if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                        else ->
                            if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    }
                    tapKey(code)
                    return
                }
                if (!p.moved && dur < 280) {
                    // double-tap -> SPACE
                    if (now - lastTapAt < 320) {
                        lastTapAt = 0
                        tapKey(KeyEvent.KEYCODE_SPACE)
                    } else {
                        lastTapAt = now
                    }
                }
            }
        }
    }

    private fun tapKey(code: Int) {
        injector.key(code, true)
        handler.postDelayed({ injector.key(code, false) }, 60)
    }

    override fun onLayoutChanged(w: Int, h: Int) {
        controls.clear()
        cam.bind(w.toFloat(), h.toFloat())
        if (!cameraStick) return
        val m = dp(26f)
        val r = dp(56f)
        val stick = Stick(w - m - r, h * 0.5f, r)
        stick.onVector { dx, dy -> cam.vector(dx, dy) }
        stick.onRelease { cam.release() }
        addControl(stick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
