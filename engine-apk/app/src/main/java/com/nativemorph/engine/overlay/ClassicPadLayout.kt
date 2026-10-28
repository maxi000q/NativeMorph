package com.nativemorph.engine.overlay

import android.content.Context
import android.view.KeyEvent

/**
 * Layout 1 - "Classic Pad": D-pad left, A/B/X/Y diamond right, Start/Select
 * center. The most compatible desktop mapping (arrow keys + WASD-adjacent
 * action keys).
 *
 * v1.3.2: optional camera joystick on the upper right (enabled from Settings
 * or the in-game quick panel). It drives the game camera with synthetic
 * touch drags, so drag-to-look games rotate without giving up the pad.
 */
class ClassicPadLayout(
    context: Context,
    injector: GameInputInjector,
    private val cameraStick: Boolean = false,
) : TouchLayoutView(context, injector) {

    private val cam = CameraDrag(injector)

    override fun onLayoutChanged(w: Int, h: Int) {
        controls.clear()
        cam.bind(w.toFloat(), h.toFloat())
        val m = dp(30f)

        // D-pad (left, lower half)
        val dR = dp(62f)
        val dCx = m + dR
        val dCy = h * 0.66f
        addControl(
            DPad(
                dCx, dCy, dR,
                intArrayOf(
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                ),
            )
        )

        // A/B/X/Y diamond (right, lower half)
        val bR = dp(34f)
        val bCx = w - m - dp(64f)
        val bCy = h * 0.68f
        val d = bR * 1.35f + dp(6f)
        addControl(KeyButton(bCx, bCy + d, bR, "A", KeyEvent.KEYCODE_SPACE))
        addControl(KeyButton(bCx + d, bCy, bR, "B", KeyEvent.KEYCODE_E))
        addControl(KeyButton(bCx - d, bCy, bR, "X", KeyEvent.KEYCODE_Q))
        addControl(KeyButton(bCx, bCy - d, bR, "Y", KeyEvent.KEYCODE_F))

        // Start / Select (top center-right, small)
        val sR = dp(18f)
        val sCy = dp(40f)
        addControl(KeyButton(w * 0.5f + dp(30f), sCy, sR, "ST", KeyEvent.KEYCODE_ENTER))
        addControl(KeyButton(w * 0.5f - dp(30f), sCy, sR, "SL", KeyEvent.KEYCODE_ESCAPE))

        // Shoulder triggers (top corners)
        addControl(Trigger(m + dp(46f), dp(30f), dp(26f), dp(13f), "L", KeyEvent.KEYCODE_SHIFT_LEFT))
        addControl(Trigger(w - m - dp(46f), dp(30f), dp(26f), dp(13f), "R", KeyEvent.KEYCODE_TAB))

        // Camera stick (upper right, between the R trigger and the buttons)
        if (cameraStick) {
            val cR = dp(48f)
            val stick = Stick(w - m - dp(12f) - cR, h * 0.34f, cR)
            stick.onVector { dx, dy -> cam.vector(dx, dy) }
            stick.onRelease { cam.release() }
            addControl(stick)
        }
    }
}
