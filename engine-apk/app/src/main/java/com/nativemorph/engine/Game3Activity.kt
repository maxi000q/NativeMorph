package com.nativemorph.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nativemorph.engine.core.GameLibrary
import com.nativemorph.engine.core.GameMeta
import com.nativemorph.engine.overlay.ControlOverlay
import org.nativemorph.god3x.FullScreenGodotApp

/**
 * Hosts the embedded Godot 3.6.2 runtime (from the official 3.6.2 export
 * template, java layer relocated to org.nativemorph.god3x) and runs the
 * imported PCK v1 pack via --main-pack, exactly like GameActivity does for
 * the 4.7.2 runtime and 4.x packs.
 *
 * Runs in the :g3 process: the 3.x and 4.x engine libraries export the same
 * JNI symbol names (Java_org_godotengine_godot_*), so they must never share
 * a process. The ControlOverlay floats above the engine's fragment with the
 * selected mobile UI layout, exactly like on the 4.x side.
 */
class Game3Activity : FullScreenGodotApp() {

    companion object {
        const val EXTRA_GAME_ID = GameActivity.EXTRA_GAME_ID

        fun launch(ctx: Context, gameId: String) {
            ctx.startActivity(
                Intent(ctx, Game3Activity::class.java).putExtra(EXTRA_GAME_ID, gameId)
            )
        }
    }

    private var gameId: String? = null
    private var meta: GameMeta? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        gameId = intent.getStringExtra(EXTRA_GAME_ID)
        meta = gameId?.let { GameLibrary.get(this, it) }

        if (meta == null) {
            // Without a loaded game there is no --main-pack to hand the
            // engine; it would abort with a cryptic setup error.
            finish()
            return
        }

        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        // The lib's godot_app_layout fills the content view; float our
        // overlay on top of it (addContentView stacks above the existing
        // content).
        val overlay = ControlOverlay(this)
        addContentView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.attach(this, gameId ?: "", 3)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Engine boot arguments (GodotHost): the pack was already optimized at
     * import time; --main-pack points the 3.6.2 runtime at the game file.
     *
     * CRITICAL: the 3.x GodotHost default returns Collections.emptyList() -
     * an IMMUTABLE list. Calling add() on it (which is exactly what the old
     * code did) throws UnsupportedOperationException and crashes the
     * activity during onCreate - this is why 3.x games never started.
     * Always start from our own mutable ArrayList.
     */
    override fun getCommandLine(): MutableList<String> {
        val args: MutableList<String> = ArrayList(super.getCommandLine() ?: emptyList())
        val m = meta ?: return args
        args.add("--main-pack")
        args.add(m.pckAbsPath)
        return args
    }
}
