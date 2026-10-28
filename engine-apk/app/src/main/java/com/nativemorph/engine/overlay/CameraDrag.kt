package com.nativemorph.engine.overlay

import android.view.MotionEvent

/**
 * Shared synthetic camera driver used by every layout's camera stick.
 *
 * Godot games rotate the camera from touch drags (directly or through the
 * engine's default `emulate_mouse_from_touch`). This helper converts a
 * normalized stick vector into a continuous synthetic drag on the game
 * surface: an ACTION_DOWN anchor, ACTION_MOVE deltas scaled to the screen,
 * and an ACTION_UP on release. The anchor sits right of screen center where
 * look-areas usually are, and the drag point is clamped to the screen so the
 * game never sees off-surface coordinates.
 *
 * v1.3.3: uses the injector's real finger-stream (pointer id 1, pressure 1,
 * SOURCE_TOUCHSCREEN) targeted at the engine's render view - the previous
 * version dispatched to the wrong view (the hidden GodotEditText), which
 * swallowed the drags and popped the on-screen keyboard.
 */
class CameraDrag(private val injector: GameInputInjector) {

    private var down = false
    private var x = 0f
    private var y = 0f
    private var w = 1f
    private var h = 1f

    /** Pixels of synthetic drag per unit stick vector: ~1/36 of the width. */
    val sensitivity: Float get() = (w / 36f).coerceAtLeast(14f)

    fun bind(width: Float, height: Float) {
        w = width.coerceAtLeast(1f)
        h = height.coerceAtLeast(1f)
        if (!down) {
            x = w * 0.72f
            y = h * 0.5f
        }
    }

    fun vector(dx: Float, dy: Float) {
        if (!down) {
            down = true
            x = w * 0.72f
            y = h * 0.5f
            injector.touchDown(POINTER_ID, x, y)
        }
        x = (x + dx * sensitivity).coerceIn(8f, w - 8f)
        y = (y + dy * sensitivity).coerceIn(8f, h - 8f)
        injector.touchMove(POINTER_ID, x, y)
    }

    fun release() {
        if (down) {
            injector.touchUp(POINTER_ID, x, y)
            down = false
        }
    }

    companion object {
        /** Dedicated pointer slot so the drag never collides with the
         * virtual mouse's id-0 finger or with the first real finger. */
        const val POINTER_ID = 1
    }
}
