package com.nativemorph.engine.overlay

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup

/**
 * Finds the engine's render view inside the activity and injects synthetic
 * input into it - no shell permissions, no global injection APIs, purely
 * in-process event dispatch (same mechanism the engine's own views use).
 *
 * v1.3.3 CRITICAL FIX: the view lookup used to accept ANY view from a Godot
 * package. Both embedded runtimes insert a hidden GodotEditText (the IME
 * bridge) into the hierarchy BEFORE the render view, so the old depth-first
 * search returned that EditText. Every synthetic touch was then consumed by
 * it (the game saw nothing at all) and, worse, touching an EditText focuses
 * it and pops the on-screen keyboard. The lookup now only ever accepts a
 * SurfaceView from an engine package - i.e. the actual render view - which
 * filters out the EditText in both runtimes.
 */
class GameInputInjector(private val activity: Activity) {

    private var target: View? = null
    private val downTimes = HashMap<Int, Long>()
    private val targetLoc = IntArray(2)

    /** Locate the Godot render view (VkSurfaceView / GLSurfaceView child). */
    fun attach(): Boolean {
        val found = findGodotView(activity.window.decorView)
        if (found != null) {
            target = found
        }
        return found != null
    }

    val ready: Boolean get() = target != null

    /**
     * The engine's render views are SurfaceViews:
     *   4.x: org.godotengine.godot.GodotVulkanRenderView / GodotGLRenderView
     *   3.x: org.nativemorph.god3x.GodotView
     * The GodotEditText IME bridge lives in the same packages but is a plain
     * EditText, so requiring SurfaceView excludes it (and every other helper
     * view) while still matching both runtimes without compile-time deps.
     */
    private fun findGodotView(v: View): View? {
        val n = v.javaClass.name
        if (v is SurfaceView && !n.contains("EditText") &&
            (n.contains("godotengine") || n.contains("nativemorph.god3x"))
        ) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findGodotView(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Returns true when some view took the event. */
    fun key(code: Int, down: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val downTime = if (down) now else (downTimes.remove(code) ?: now)
        val ev = KeyEvent(
            downTime, now,
            if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, 0, -1, 0, 0,
            InputDevice.SOURCE_KEYBOARD,
        )
        val t = target
        if (t != null && t.dispatchKeyEvent(ev)) return true
        return activity.dispatchKeyEvent(ev)
    }

    /** Forward a REAL overlay event to the game (pass-through mode). */
    fun forwardToGame(ev: MotionEvent): Boolean {
        val t = target
        return if (t != null) t.dispatchTouchEvent(ev) else false
    }

    // ------------------------------------------------------------ touch stream
    //
    // Synthetic FINGER events (SOURCE_TOUCHSCREEN + TOOL_TYPE_FINGER) built
    // with an explicit pointer id. The engine treats them exactly like real
    // finger touches: games receive InputEventScreenTouch / InputEventScreenDrag
    // and Godot's GUI layer handles them for Control-based UI. Additionally,
    // Godot's "emulate mouse from touch" project setting (on by default)
    // turns index-0 touches into mouse events, which is why the virtual
    // mouse's left button uses pointer id 0. The camera stick uses id 1 so
    // the two streams can never fight over the same slot.
    //
    // Coordinates are given in WINDOW space (the overlay is full-screen at
    // the window origin, so its local coordinates qualify) and are mapped
    // into the render view's local space before dispatch.

    private fun touchEvent(
        pointerId: Int,
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
    ): MotionEvent {
        target?.getLocationInWindow(targetLoc)
        val props = MotionEvent.PointerProperties().apply {
            id = pointerId
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x - targetLoc[0]
            this.y = y - targetLoc[1]
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun dispatchTouch(ev: MotionEvent): Boolean {
        val t = target ?: run {
            ev.recycle()
            return false
        }
        val r = t.dispatchTouchEvent(ev)
        ev.recycle()
        return r
    }

    /** Press a synthetic finger ([pointerId]) at window position x/y. */
    fun touchDown(pointerId: Int, x: Float, y: Float): Boolean {
        downTimes[pointerId] = SystemClock.uptimeMillis()
        return dispatchTouch(
            touchEvent(pointerId, MotionEvent.ACTION_DOWN, x, y, downTimes[pointerId]!!)
        )
    }

    /** Drag the synthetic finger to a new window position. */
    fun touchMove(pointerId: Int, x: Float, y: Float): Boolean =
        dispatchTouch(
            touchEvent(
                pointerId, MotionEvent.ACTION_MOVE, x, y,
                downTimes[pointerId] ?: SystemClock.uptimeMillis(),
            )
        )

    /** Lift the synthetic finger. */
    fun touchUp(pointerId: Int, x: Float, y: Float): Boolean {
        val t = downTimes.remove(pointerId) ?: SystemClock.uptimeMillis()
        return dispatchTouch(touchEvent(pointerId, MotionEvent.ACTION_UP, x, y, t))
    }

    // ------------------------------------------------------------ virtual mouse
    //
    // Synthetic MOUSE events for the on-screen cursor. Both embedded engines
    // (4.7.2 AAR and the relocated 3.6.2 layer) classify a MotionEvent as a
    // mouse event when its tool type is TOOL_TYPE_MOUSE (4.x checks that
    // first) or its source is SOURCE_MOUSE (3.x checks the source), and
    // route it to GodotLib.dispatchMouseEvent: DOWN/UP with a button mask
    // become mouse button presses, ACTION_MOVE with a button held becomes a
    // drag, and ACTION_HOVER_MOVE (delivered through
    // dispatchGenericMotionEvent) becomes cursor motion. Never send
    // ACTION_BUTTON_PRESS/RELEASE - both runtimes explicitly ignore them to
    // avoid duplicate streams.

    private fun mouseEvent(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
        buttons: Int,
    ): MotionEvent {
        target?.getLocationInWindow(targetLoc)
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x - targetLoc[0]
            this.y = y - targetLoc[1]
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(props), arrayOf(coords),
            0, buttons, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0,
        )
    }

    private fun dispatchMouse(ev: MotionEvent, generic: Boolean): Boolean {
        val t = target ?: run {
            ev.recycle()
            return false
        }
        val r = if (generic) t.dispatchGenericMotionEvent(ev) else t.dispatchTouchEvent(ev)
        ev.recycle()
        return r
    }

    /** Mouse button down at the virtual cursor (secondary = right button). */
    fun mouseDown(x: Float, y: Float, secondary: Boolean = false) {
        val btn = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        dispatchMouse(
            mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, btn),
            generic = false,
        )
    }

    /** Mouse button up at the virtual cursor (secondary = right button). */
    fun mouseUp(x: Float, y: Float, secondary: Boolean = false) {
        val btn = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        dispatchMouse(
            mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, btn),
            generic = false,
        )
    }

    /**
     * Cursor motion. While a button is held the move is a drag (button mask
     * set, delivered through the touch channel); otherwise it is a hover
     * move delivered through the generic-motion channel.
     */
    fun mouseMove(x: Float, y: Float, buttons: Int = 0) {
        if (buttons != 0) {
            dispatchMouse(
                mouseEvent(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, buttons),
                generic = false,
            )
        } else {
            dispatchMouse(
                mouseEvent(
                    SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE, x, y, 0
                ),
                generic = true,
            )
        }
    }

    fun vibrate(ms: Long) {
        try {
            val v = activity.getSystemService(Activity.VIBRATOR_SERVICE)
                as? android.os.Vibrator ?: return
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(
                    android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (e: Exception) {
            // haptics are best-effort
        }
    }
}
