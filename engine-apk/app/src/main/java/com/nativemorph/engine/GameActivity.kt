package com.nativemorph.engine

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nativemorph.engine.core.GameLibrary
import com.nativemorph.engine.core.GameMeta
import com.nativemorph.engine.core.Profiles
import com.nativemorph.engine.overlay.ControlOverlay
import com.nativemorph.engine.ui.Icons
import com.nativemorph.engine.ui.IconGlyphs
import com.nativemorph.engine.ui.Ui
import org.godotengine.godot.GodotActivity
import java.io.File

/**
 * Hosts the embedded Godot 4.7.2 runtime (from the official prebuilt AAR) and
 * runs the user's optimized pack via `--main-pack` - exactly the mechanism the
 * official Godot Android editor uses to open projects from storage.
 *
 * v1.2: the renderer is no longer force-overridden on the command line. The
 * Fidelity profile keeps the game's authored rendering method (patched into
 * project.godot at import time only when a profile asks for it). A watchdog
 * notices if the engine never reaches its main loop and offers a one-tap
 * rescue: relaunch with the conservative gl_compatibility renderer.
 */
class GameActivity : GodotActivity() {

    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_FORCE_RENDERER = "force_renderer"
        private const val WATCHDOG_MS = 18_000L

        fun launch(ctx: Context, gameId: String, forceRenderer: String? = null) {
            ctx.startActivity(
                Intent(ctx, GameActivity::class.java)
                    .putExtra(EXTRA_GAME_ID, gameId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .apply { forceRenderer?.let { putExtra(EXTRA_FORCE_RENDERER, it) } }
            )
        }
    }

    private var gameId: String? = null
    private var meta: GameMeta? = null
    private var forceRenderer: String? = null
    private var mainLoopStarted = false
    private val main = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { maybeEngineStuck() }

    override fun onCreate(savedInstanceState: Bundle?) {
        gameId = intent.getStringExtra(EXTRA_GAME_ID)
        meta = gameId?.let { GameLibrary.get(this, it) }
        forceRenderer = intent.getStringExtra(EXTRA_FORCE_RENDERER)

        if (meta == null) {
            // Without a loaded game there is no --main-pack to hand the
            // engine; it would abort with a cryptic setup error.
            finish()
            return
        }

        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        findViewById<ControlOverlay>(R.id.control_overlay)?.attach(this, gameId ?: "")

        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * singleTask hardening (v1.3.1): if a launch intent for a DIFFERENT game
     * (or a different forced renderer) arrives while this instance is still
     * alive - e.g. the watchdog's "retry with GL" button, or a relaunch from
     * the library after the process was reused - the engine cannot hot-swap
     * packs, so finish this instance and start a fresh one for the request.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newId = intent.getStringExtra(EXTRA_GAME_ID) ?: return
        val newRenderer = intent.getStringExtra(EXTRA_FORCE_RENDERER)
        if (newId == gameId && newRenderer == forceRenderer) return
        val ctx = this
        main.post {
            finish()
            launch(ctx, newId, newRenderer)
        }
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
     * Engine boot arguments. --main-pack points at the optimized pack in the
     * app sandbox. The rendering method is only forced when the selected
     * profile asks for one (Battery = mobile, Compatibility = GLES3); Fidelity
     * keeps whatever the game was authored with - forcing "mobile" here used
     * to break Forward+ games.
     *
     * CRITICAL: GodotHost's default implementation returns
     * Collections.emptyList() - an IMMUTABLE list. Mutating it in place
     * (addAll/add) throws UnsupportedOperationException and kills the
     * engine thread before setup even runs. Always start from our own
     * mutable ArrayList.
     */
    override fun getCommandLine(): MutableList<String> {
        val args: MutableList<String> = ArrayList(super.getCommandLine() ?: emptyList())
        val m = meta ?: return args
        args += listOf("--main-pack", m.pckAbsPath)
        val renderer = forceRenderer ?: Profiles.rendererOverride(m.profile)
        if (renderer != null) args += listOf("--rendering-method", renderer)
        if (m.profile == Profiles.BATTERY) args += listOf("--fixed-fps", "60")
        return args
    }

    // ---- diagnostics -------------------------------------------------------

    override fun onGodotSetupCompleted() {
        super.onGodotSetupCompleted()
        main.removeCallbacks(watchdog)
        // setup finished, but the main loop is the real "game is running"
        // signal - give it a little more rope before declaring failure.
        main.postDelayed(watchdog, WATCHDOG_MS / 2)
    }

    override fun onGodotMainLoopStarted() {
        super.onGodotMainLoopStarted()
        mainLoopStarted = true
        main.removeCallbacks(watchdog)
    }

    private fun maybeEngineStuck() {
        if (mainLoopStarted || isFinishing || isDestroyed) return
        val m = meta ?: return
        val pck = File(m.pckAbsPath)
        val exists = pck.isFile
        val size = if (exists) pck.length() else 0L

        val diag = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(26f), Ui.dp(26f), Ui.dp(26f), Ui.dp(18f))
            setBackgroundColor(0xF20B0714.toInt())
        }
        diag.addView(Icons.icon(this, IconGlyphs.WARNING, 44f, 0xFFFFB454.toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        diag.addView(TextView(this).apply {
            text = "${m.name} did not start"
            setTextColor(0xFFEDF0F7.toInt())
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(14f), 0, Ui.dp(4f))
        })
        val packOk = exists && size > 0
        diag.addView(TextView(this).apply {
            text = buildString {
                append("The engine did not reach its main loop.\n\n")
                append("Game pack: ")
                append(when {
                    !exists -> "missing (re-import the game)"
                    size <= 0 -> "empty (re-import the game)"
                    else -> "present (${m.sizeHuman})"
                })
                append("\nRuntime: Godot ${m.engineVersion} (4.7.2 engine)")
                append("\nProfile: ${Profiles.label(m.profile)}")
                append("\n\nMost black-screen games are fixed by running them " +
                    "with the conservative OpenGL renderer.")
            }
            setTextColor(0xFF9AA3B5.toInt())
            textSize = 14f
            setLineSpacing(0f, 1.35f)
        })

        val retry = Button(this).apply {
            text = "Retry with Compatibility renderer (GL)"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF6C4DFF.toInt())
                cornerRadius = Ui.dp(14f).toFloat()
            }
            setOnClickListener {
                GameActivity.launch(this@GameActivity, m.id, "gl_compatibility")
                finish()
            }
        }
        diag.addView(retry, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(52f)).apply {
            topMargin = Ui.dp(18f)
        })
        val back = Button(this).apply {
            text = "Back to library"
            setTextColor(0xFF9AA3B5.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF171221.toInt())
                cornerRadius = Ui.dp(14f).toFloat()
                setStroke(1, 0xFF2A3244.toInt())
            }
            setOnClickListener { finish() }
        }
        diag.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(48f)).apply {
            topMargin = Ui.dp(10f)
        })

        setContentView(diag)
        findViewById<ControlOverlay>(R.id.control_overlay)?.visibility = View.GONE
    }

    override fun getGodotAppLayout(): Int = R.layout.activity_game
}
