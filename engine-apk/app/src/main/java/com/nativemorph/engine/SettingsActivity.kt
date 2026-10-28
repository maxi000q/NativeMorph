package com.nativemorph.engine

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.nativemorph.engine.core.Layouts
import com.nativemorph.engine.core.Prefs
import com.nativemorph.engine.core.Profiles
import com.nativemorph.engine.ui.LayoutPreview
import com.nativemorph.engine.ui.Ui

/**
 * The settings screen, reachable from the library's gear icon BEFORE any
 * import. Everything selected here becomes the default applied when a game
 * is imported (profile) and when it is played (layout, overlay look).
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@SettingsActivity, R.color.bg))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(16f), Ui.dp(16f), Ui.dp(16f), Ui.dp(8f))
        }
        bar.addView(Ui.title(this, getString(R.string.settings)))
        root.addView(bar)

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = Ui.vertical(this, 18)
        scroll.addView(body)
        root.addView(scroll)

        // ---- section: default mobile UI layout (the one that floats
        //      above the game) ----------------------------------------------
        body.addView(sectionLabel("DEFAULT MOBILE UI LAYOUT"))
        body.addView(
            Ui.body(
                this,
                "Pick the on-screen control layout that appears above any " +
                    "running game. You can also override it per game.",
                true,
            )
        )
        val layoutRows = LinkedHashMap<String, LinearLayout>()
        var selectedLayout = Prefs.layout(this)
        for (id in Layouts.all) {
            val row = selectableRow(id, Layouts.label(id), Layouts.description(id)) {
                layoutRows.forEach { (k, v) -> v.alpha = if (k == id) 1f else 0.45f }
                selectedLayout = id
                Prefs.setLayout(this, id)
            }
            row.addView(LayoutPreview(this).apply { kind = id }, 0)
            row.alpha = if (id == selectedLayout) 1f else 0.45f
            layoutRows[id] = row
            body.addView(row)
        }

        // ---- section: optimization profile --------------------------------
        body.addView(sectionLabel("OPTIMIZATION PROFILE"))
        body.addView(
            Ui.body(
                this,
                "Applied when a game is imported. Fidelity is the default: " +
                    "the game runs with exactly the graphics it was exported " +
                    "with. Compatibility is the rescue option for games that " +
                    "show a black screen.",
                true,
            )
        )
        for (id in Profiles.all()) {
            val row = selectableRow(
                id,
                Profiles.label(id),
                Profiles.description(id),
                selected = (id == Prefs.profile(this)),
            ) { Prefs.setProfile(this, id) }
            body.addView(row)
        }

        // ---- section: camera & mouse ------------------------------------
        body.addView(sectionLabel("CAMERA & MOUSE"))
        body.addView(
            Ui.body(
                this,
                "The camera joystick floats on the right side of the screen " +
                    "and rotates the camera in any game (drag-to-look). The " +
                    "virtual mouse gives you a movable cursor with a click " +
                    "button - both are also available mid-game from the gear " +
                    "handle.",
                true,
            )
        )

        val camRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(14f), Ui.dp(12f), Ui.dp(14f), Ui.dp(12f))
            background = GradientDrawable().apply {
                setColor(Ui.color(this@SettingsActivity, R.color.bg_card))
                cornerRadius = Ui.dp(12f).toFloat()
                setStroke(1, Ui.color(this@SettingsActivity, R.color.stroke))
            }
        }
        camRow.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(Ui.body(this@SettingsActivity, "Camera stick").apply { typeface = Typeface.DEFAULT_BOLD })
                addView(
                    Ui.body(
                        this@SettingsActivity,
                        "Right-side camera joystick above any game. Twin Stick " +
                            "always has one built in.",
                        true,
                    )
                )
            }
        )
        camRow.addView(
            Switch(this).apply {
                isChecked = Prefs.cameraStick(this@SettingsActivity)
                setOnCheckedChangeListener { _, c ->
                    Prefs.setCameraStick(this@SettingsActivity, c)
                }
            }
        )
        body.addView(camRow)

        val mouseInfo = Ui.card(this)
        mouseInfo.addView(
            Ui.body(this, "Virtual mouse", true).apply { typeface = Typeface.DEFAULT_BOLD })
        mouseInfo.addView(
            Ui.body(
                this,
                "While a game runs, the gear handle sits next to a mouse " +
                    "button. Tap it to turn the whole screen into a touchpad " +
                    "with a visible cursor; drag to move it, tap the pad to " +
                    "click, and hold the target button to drag with the left " +
                    "button pressed. It works on top of every on-screen " +
                    "control.",
                true,
            )
        )
        body.addView(mouseInfo)

        // ---- section: overlay appearance ----------------------------------
        body.addView(sectionLabel("OVERLAY BUTTONS"))

        body.addView(Ui.body(this, "Button size", true))
        body.addView(slider((Prefs.buttonScale(this) * 100).toInt(), 70, 140) {
            Prefs.setButtonScale(this, it / 100f)
        })

        body.addView(Ui.body(this, "Button visibility", true))
        body.addView(slider((Prefs.buttonAlpha(this) * 100).toInt(), 30, 100) {
            Prefs.setButtonAlpha(this, it / 100f)
        })

        val hapticsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(10f), 0, Ui.dp(10f))
        }
        hapticsRow.addView(
            Ui.body(this, "Haptic feedback on button edges").let { t ->
                t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                t
            }
        )
        val sw = Switch(this).apply {
            isChecked = Prefs.haptics(this@SettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setHaptics(this@SettingsActivity, c) }
        }
        hapticsRow.addView(sw)
        body.addView(hapticsRow)

        // ---- section: about -------------------------------------------------
        body.addView(sectionLabel("ABOUT"))
        val about = Ui.card(this)
        about.addView(Ui.body(this, "NativeMorph Engine 1.3.3"))
        about.addView(Ui.body(this, "Embedded engines: Godot 4.7.2 (4.x packs) and Godot 3.6.2 (3.x packs) - both official prebuilt runtimes, MIT.", true))
        about.addView(Ui.body(this, "Native core: ${com.nativemorph.engine.NativeBridge.coreVersion()}", true))
        about.addView(
            Ui.body(
                this,
                "You supply the game files; you keep the rights. Nothing is " +
                    "uploaded - imports and optimization run entirely on this device.",
                true,
            )
        )
        body.addView(about)

        setContentView(root)
    }

    private fun sectionLabel(text: String): TextView {
        val t = Ui.caption(this, text)
        t.setPadding(0, Ui.dp(22f), 0, Ui.dp(6f))
        return t
    }

    private fun selectableRow(
        id: String,
        title: String,
        desc: String,
        selected: Boolean = false,
        onClick: () -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(14f), Ui.dp(12f), Ui.dp(14f), Ui.dp(12f))
            background = GradientDrawable().apply {
                setColor(Ui.color(this@SettingsActivity, R.color.bg_card))
                cornerRadius = Ui.dp(12f).toFloat()
                setStroke(
                    if (selected) Ui.dp(2f) else 1,
                    Ui.color(
                        this@SettingsActivity,
                        if (selected) R.color.accent else R.color.stroke,
                    ),
                )
            }
            setOnClickListener {
                (background as? GradientDrawable)?.setStroke(
                    Ui.dp(2f), Ui.color(this@SettingsActivity, R.color.accent)
                )
                onClick()
            }
        }
        addViewSafe(row, Ui.body(this, title).apply { typeface = Typeface.DEFAULT_BOLD })
        addViewSafe(row, Ui.body(this, desc, true))
        return row
    }

    private fun addViewSafe(parent: LinearLayout, child: android.view.View) {
        parent.addView(child)
    }

    private fun slider(initial: Int, min: Int, max: Int, onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            this.max = max
            if (android.os.Build.VERSION.SDK_INT >= 26) this.min = min
            progress = initial
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }
}
