package com.nativemorph.engine.overlay

import android.content.Context
import android.view.KeyEvent

/**
 * Layout 2 - "Twin Stick": left stick = movement (WASD, 8-way thresholds),
 * right stick = camera. Camera drives the game with synthetic touch drags
 * (works with drag-to-look games); A/B + shoulders as keys.
 *
 * v1.3.2: camera dragging moved into the shared [CameraDrag] helper so all
 * layouts behave identically (screen-relative sensitivity + clamping).
 */
class TwinStickLayout(context: Context, injector: GameInputInjector) :
    TouchLayoutView(context, injector) {

    private val cam = CameraDrag(injector)

    // WASD state emitted from the left stick vector
    private val wasd = BooleanArray(4)
    private val wasdKeys = intArrayOf(
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S,
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D,
    )

    override fun onLayoutChanged(w: Int, h: Int) {
        controls.clear()
        cam.bind(w.toFloat(), h.toFloat())
        val m = dp(26f)
        val r = dp(64f)

        val ls = Stick(m + r, h * 0.56f, r)
        ls.onVector { dx, dy ->
            val t = 0.35f
            setWasd(0, dy < -t)
            setWasd(1, dy > t)
            setWasd(2, dx < -t)
            setWasd(3, dx > t)
        }
        ls.onRelease { for (i in 0..3) setWasd(i, false) }
        addControl(ls)

        val rs = Stick(w - m - r, h * 0.56f, r)
        rs.onVector { dx, dy -> cam.vector(dx, dy) }
        rs.onRelease { cam.release() }
        addControl(rs)

        // action buttons near the right stick
        val bR = dp(30f)
        addControl(KeyButton(w - m - dp(6f) - bR, h * 0.56f + r + dp(46f), bR, "A", KeyEvent.KEYCODE_SPACE))
        addControl(KeyButton(w - m - dp(76f) - bR, h * 0.56f + r + dp(24f), bR, "B", KeyEvent.KEYCODE_E))

        // shoulders
        addControl(Trigger(m + dp(42f), dp(28f), dp(24f), dp(12f), "L1", KeyEvent.KEYCODE_SHIFT_LEFT))
        addControl(Trigger(w - m - dp(42f), dp(28f), dp(24f), dp(12f), "R1", KeyEvent.KEYCODE_CTRL_LEFT))
    }

    private fun setWasd(i: Int, on: Boolean) {
        if (wasd[i] != on) {
            wasd[i] = on
            injector.key(wasdKeys[i], on)
        }
    }
}
