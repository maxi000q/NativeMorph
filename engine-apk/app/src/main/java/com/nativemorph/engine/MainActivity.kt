package com.nativemorph.engine

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.nativemorph.engine.core.FolderScan
import com.nativemorph.engine.core.GameLibrary
import com.nativemorph.engine.core.GameMeta
import com.nativemorph.engine.core.Layouts
import com.nativemorph.engine.core.Names
import com.nativemorph.engine.core.Prefs
import com.nativemorph.engine.core.Profiles
import com.nativemorph.engine.ui.AuroraBackground
import com.nativemorph.engine.ui.GameCarousel
import com.nativemorph.engine.ui.Icons
import com.nativemorph.engine.ui.IconGlyphs
import com.nativemorph.engine.ui.LayoutPreview
import com.nativemorph.engine.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * PS5-main-menu style game library (violet identity).
 *
 * Layout: ambient aurora background, clock top-right, a small centered
 * function row (import / settings / about) and the horizontal game card
 * carousel. Import is FOLDER based: pick the game's folder - .exe + .pck,
 * .zip, .apk and .obb inside are detected automatically.
 */
class MainActivity : ComponentActivity() {

    private lateinit var carousel: GameCarousel
    private lateinit var infoName: TextView
    private lateinit var infoMeta: TextView
    private lateinit var playBtn: Button
    private lateinit var clock: TextView
    private lateinit var emptyBox: LinearLayout
    private var games: List<GameMeta> = emptyList()

    private var importDialog: android.app.AlertDialog? = null
    private var dialogRef: android.app.AlertDialog? = null

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { importFolder(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.color(this@MainActivity, R.color.bg))
        }
        root.addView(AuroraBackground(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        }, 0)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- top bar: wordmark + clock (PS5 vibe) ------------------------
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(34f), Ui.dp(26f), Ui.dp(34f), Ui.dp(4f))
        }
        bar.addView(TextView(this).apply {
            text = "NATIVEMORPH"
            setTextColor(0xFF9C86FF.toInt())
            textSize = 15f
            letterSpacing = 0.28f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        clock = TextView(this).apply {
            setTextColor(0xFF9AA3B5.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        bar.addView(clock)
        col.addView(bar)
        tickClock()

        // ---- function row (PS5's small icon row above the games) ---------
        val frow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, Ui.dp(10f), 0, Ui.dp(6f))
        }
        frow.addView(functionButton(IconGlyphs.FOLDER_OPEN, "Import") { pickFolder.launch(null) })
        frow.addView(functionButton(IconGlyphs.SETTINGS, "Settings") {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        })
        frow.addView(functionButton(IconGlyphs.INFO, "About") { showAbout() })
        col.addView(frow)
        // ---- empty state --------------------------------------------------
        emptyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(40f), 0, Ui.dp(40f), 0)
        }
        emptyBox.addView(Icons.icon(this, IconGlyphs.BROWSE, 64f, 0xFF9C86FF.toInt()).apply {
            alpha = 0.9f
        })
        emptyBox.addView(Ui.title(this, "Choose a game folder").apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(18f), 0, Ui.dp(6f))
        })
        emptyBox.addView(Ui.body(this,
            "Pick the folder your game lives in. NativeMorph finds the game " +
                "data automatically - a side-by-side .exe + .pck pair, a " +
                "self-contained .exe, a .zip, a Godot .apk or an .obb. " +
                "Folders with several games import them all.", true).apply {
            gravity = Gravity.CENTER
        })
        emptyBox.addView(Button(this).apply {
            text = getString(R.string.import_game)
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(Ui.dp(28f), Ui.dp(14f), Ui.dp(28f), Ui.dp(14f))
            background = GradientDrawable().apply {
                setColor(Ui.color(this@MainActivity, R.color.accent))
                cornerRadius = Ui.dp(26f).toFloat()
            }
            setOnClickListener { pickFolder.launch(null) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(22f)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        col.addView(emptyBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- game carousel --------------------------------------------------
        carousel = GameCarousel(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            onSelected = { runOnUiThread { showInfo(it) } }
            onCardTap = { runOnUiThread { launchSelected(it) } }
            onCardLongPress = { runOnUiThread { showGameSheet(it) } }
        }
        col.addView(carousel)

        // ---- info panel for the selected game -------------------------------
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(30f), Ui.dp(2f), Ui.dp(30f), Ui.dp(30f))
        }
        infoName = TextView(this).apply {
            setTextColor(0xFFEDF0F7.toInt())
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        info.addView(infoName)
        infoMeta = Ui.body(this, "", true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(3f), 0, Ui.dp(12f))
        }
        info.addView(infoMeta)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        playBtn = Button(this).apply {
            text = getString(R.string.play)
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(Ui.dp(34f), 0, Ui.dp(34f), 0)
            minHeight = Ui.dp(54f)
            background = GradientDrawable().apply {
                setColor(Ui.color(this@MainActivity, R.color.accent))
                cornerRadius = Ui.dp(27f).toFloat()
            }
            setOnClickListener { launchSelected(selectedPos) }
        }
        btnRow.addView(playBtn)
        btnRow.addView(iconButton(IconGlyphs.TUNE) { showGameSheet(selectedPos) })
        btnRow.addView(iconButton(IconGlyphs.DESCRIPTION) {
            games.getOrNull(selectedPos)?.let { showReport(it) }
        })
        btnRow.addView(iconButton(IconGlyphs.DELETE) { confirmDelete() })
        info.addView(btnRow)
        col.addView(info)

        root.addView(col)
        setContentView(root)
    }

    private var selectedPos = 0

    override fun onResume() {
        super.onResume()
        refreshList()
        tickClock()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        (window.decorView.rootView as? android.view.ViewGroup)?.getChildAt(0)
            ?.let { if (it is AuroraBackground) it.setRunning(hasFocus) }
    }

    private fun tickClock() {
        clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    // ------------------------------------------------------------------ chrome

    private fun functionButton(glyph: String, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(16f), Ui.dp(6f), Ui.dp(16f), Ui.dp(6f))
            setOnClickListener {
                onClick()
            }
            addView(Icons.icon(this@MainActivity, glyph, 22f, 0xFF9AA3B5.toInt()))
            addView(Ui.caption(this@MainActivity, label.uppercase()).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(4f), 0, 0)
            })
        }

    private fun iconButton(glyph: String, onClick: () -> Unit): TextView =
        Icons.icon(this, glyph, 20f, 0xFF9AA3B5.toInt()).apply {
            val pad = Ui.dp(14f)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x339C86FF)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = Ui.dp(10f)
            }
        }

    private fun showAbout() {
        android.app.AlertDialog.Builder(this)
            .setTitle("NativeMorph Engine")
            .setMessage(
                "Run your Godot games natively on Android - no emulation.\n\n" +
                    "Embedded runtimes: Godot 4.7.2 and Godot 3.6.2 (ARM64).\n" +
                    "Import a game FOLDER: the engine data inside (.pck, " +
                    "self-contained .exe, .zip, Godot .apk, .obb) is detected " +
                    "automatically, optimized on-device and run from the app " +
                    "sandbox.\n\n" +
                    "UI icons: Material Symbols Rounded (Google, Apache-2.0)."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------------ library

    private fun refreshList() {
        games = GameLibrary.list(this)
        val has = games.isNotEmpty()
        emptyBox.visibility = if (has) View.GONE else View.VISIBLE
        carousel.visibility = if (has) View.VISIBLE else View.GONE
        infoName.visibility = if (has) View.VISIBLE else View.GONE
        infoMeta.visibility = if (has) View.VISIBLE else View.GONE
        playBtn.visibility = if (has) View.VISIBLE else View.GONE
        if (!has) return

        carousel.clearCards()
        games.forEachIndexed { i, g ->
            val bmp = g.iconPath?.let { p ->
                try {
                    BitmapFactory.decodeFile(p)
                } catch (e: Exception) {
                    null
                }
            }
            val card = GameCarousel.GameCardView(this).apply {
                bind(bmp, g.name, "GODOT ${g.engineVersion}")
            }
            carousel.addCard(card, g.id)
        }
        carousel.selectInitial(0)
        showInfo(0)
    }

    private fun showInfo(position: Int) {
        selectedPos = position
        val g = games.getOrNull(position) ?: return
        infoName.text = g.name
        infoMeta.text =
            "Godot ${g.engineVersion}  -  ${g.sizeHuman}  -  ${g.files} files  -  " +
                Profiles.label(g.profile)
    }

    private fun launchSelected(position: Int) {
        val g = games.getOrNull(position) ?: return
        if (g.engineMajor == 3) Game3Activity.launch(this, g.id)
        else GameActivity.launch(this, g.id)
    }

    private fun confirmDelete() {
        val g = games.getOrNull(selectedPos) ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete ${g.name}?")
            .setMessage("The imported game data is removed from the app. " +
                "Your original files are not touched.")
            .setPositiveButton(R.string.delete) { _, _ ->
                GameLibrary.delete(this, g.id)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------ import

    private fun importFolder(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // one-shot grant: fine for a single import pass
        }

        val progTitle = Ui.body(this, "Scanning folder...")
        val progStage = Ui.body(this, "Looking for game data...", true)
        val bar = ProgressBar(this).apply { isIndeterminate = true }
        val box = Ui.vertical(this, 22).apply { addView(progTitle); addView(progStage); addView(bar) }
        importDialog?.dismiss()
        importDialog = android.app.AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .create()
        importDialog?.show()

        val progress = object : NativeBridge.Progress {
            override fun onProgress(stage: String, pct: Int, msg: String) {
                runOnUiThread { progStage.text = "[$stage] $msg" }
            }
        }

        thread {
            // 1. scan the picked folder tree
            val (candidates, scanNotes) = try {
                FolderScan.scan(contentResolver, tree)
            } catch (e: Exception) {
                runOnUiThread {
                    finishImportDialog()
                    Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@thread
            }

            if (candidates.isEmpty()) {
                runOnUiThread {
                    finishImportDialog()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("No game found in this folder")
                        .setMessage(
                            "NativeMorph looks for Godot game data: a .pck (with or " +
                                "without a side-by-side .exe launcher), a self-contained " +
                                ".exe, a .zip, a Godot .apk or an .obb.\n\n" +
                                (if (scanNotes.isEmpty()) "" else scanNotes.joinToString("\n")))
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@thread
            }

            val imported = mutableListOf<String>()
            val failures = mutableListOf<String>()
            val dupes = mutableListOf<String>()

            // 2. import every candidate
            for ((i, cand) in candidates.withIndex()) {
                runOnUiThread {
                    progTitle.text = "Importing ${i + 1}/${candidates.size}: ${cand.name}"
                    progStage.text = "Copying game data..."
                }
                val work = GameLibrary.createWork(this)
                val safe = Names.safeFile(cand.name, "game_${i}")
                val src = File(work, "source.${cand.ext}")

                // copy from SAF into the app sandbox
                try {
                    contentResolver.openInputStream(cand.uri)?.use { input ->
                        src.outputStream().use { output ->
                            input.copyTo(output, 1 shl 16)
                        }
                    } ?: throw IllegalStateException("cannot open ${cand.name}")
                } catch (e: Exception) {
                    failures.add("${cand.name}: copy failed (${e.message})")
                    work.deleteRecursively()
                    continue
                }

                // run the on-device optimizer
                val reportJson = try {
                    NativeBridge.importGame(
                        src.absolutePath,
                        work.absolutePath,
                        Profiles.patchOptsJson(Prefs.profile(this)),
                        progress,
                    )
                } catch (e: Exception) {
                    work.deleteRecursively()
                    failures.add("${cand.name}: ${e.message}")
                    continue
                }
                src.delete()
                File(work, "import.pck").delete()

                val j = try {
                    JSONObject(reportJson)
                } catch (e: Exception) {
                    work.deleteRecursively()
                    failures.add("${cand.name}: import crashed")
                    continue
                }
                if (!j.optBoolean("ok")) {
                    work.deleteRecursively()
                    failures.add("${cand.name}: ${j.optString("error", "import failed")}")
                    continue
                }

                val name = Names.clean(
                    j.optString("name").ifEmpty { cand.name },
                    cand.name,
                )
                val iconRel = j.optString("icon").orNullIfEmpty()
                GameLibrary.save(
                    this,
                    GameMeta(
                        id = work.name,
                        name = name,
                        engineVersion = j.optString("engine_version", "?"),
                        pckAbsPath = File(work, "game.pck").absolutePath,
                        sizeBytes = j.optLong("size_out", 0),
                        sizeHuman = j.optString("size_out_h", "?"),
                        importedAt = System.currentTimeMillis(),
                        profile = Prefs.profile(this),
                        layoutOverride = null,
                        notesJson = j.optJSONArray("notes")?.toString() ?: "[]",
                        files = j.optInt("files", 0),
                        iconPath = iconRel?.let { File(work, it).absolutePath },
                    ),
                )
                imported.add("$name (${j.optString("size_in_h")} -> ${j.optString("size_out_h")})")
            }

            // 3. summary
            runOnUiThread {
                finishImportDialog()
                refreshList()
                val sb = StringBuilder()
                if (imported.isNotEmpty())
                    sb.append("Imported ${imported.size} game(s):\n")
                        .append(imported.joinToString("\n") { "  - $it" })
                if (failures.isNotEmpty() || dupes.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append("Could not import ${failures.size + dupes.size} file(s):\n")
                        .append(failures.joinToString("\n") { "  - $it" })
                        .append(dupes.joinToString("\n") { "  - $it" })
                }
                if (scanNotes.isNotEmpty()) sb.append("\n\n").append(scanNotes.joinToString("\n"))
                android.app.AlertDialog.Builder(this)
                    .setTitle(
                        if (failures.isEmpty() && dupes.isEmpty() && imported.isNotEmpty())
                            "Import complete"
                        else "Import finished"
                    )
                    .setMessage(sb.toString().ifEmpty { "Nothing was imported." })
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun String.orNullIfEmpty(): String? = if (isEmpty()) null else this

    private fun finishImportDialog() {
        importDialog?.dismiss()
        importDialog = null
    }

    // ------------------------------------------------------------------ sheets

    private fun showGameSheet(position: Int) {
        val g = games.getOrNull(position) ?: return
        val ctx = this
        val box = Ui.vertical(ctx, 6)
        box.addView(Ui.title(ctx, g.name))
        box.addView(
            Ui.body(ctx, "Godot ${g.engineVersion} - ${g.files} files - ${g.sizeHuman}", true)
        )

        box.addView(Ui.caption(ctx, "MOBILE UI LAYOUT").let { it.setPadding(0, Ui.dp(14f), 0, Ui.dp(4f)); it })
        var chosen = g.layoutOverride ?: Prefs.layout(ctx)
        val rows = LinkedHashMap<String, LinearLayout>()
        for (id in Layouts.all) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(6f), 0, Ui.dp(6f))
            }
            val preview = LayoutPreview(ctx).apply { kind = id }
            row.addView(preview)
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(12f), 0, 0, 0)
            }
            textCol.addView(Ui.body(ctx, Layouts.label(id)).apply { typeface = Typeface.DEFAULT_BOLD })
            textCol.addView(Ui.body(ctx, Layouts.description(id), true))
            row.addView(
                textCol,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.setOnClickListener {
                chosen = id
                rows.forEach { (k, v) -> v.alpha = if (k == id) 1f else 0.45f }
            }
            rows[id] = row
            box.addView(row)
        }
        rows.forEach { (k, v) -> v.alpha = if (k == chosen) 1f else 0.45f }

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setView(box)
            .setPositiveButton(R.string.play) { _, _ ->
                GameLibrary.setLayoutOverride(ctx, g, if (chosen == Prefs.layout(ctx)) null else chosen)
                launchGame(g)
            }
            .setNegativeButton("Close", null)
            .create()
        dialogRef = dialog
        dialog.show()
    }

    private fun launchGame(g: GameMeta) {
        if (g.engineMajor == 3) Game3Activity.launch(this, g.id)
        else GameActivity.launch(this, g.id)
    }

    private fun showReport(g: GameMeta) {
        val ctx = this
        val sb = StringBuilder()
        sb.append("Engine: Godot ${g.engineVersion}\n")
        sb.append("Files in pack: ${g.files}\n")
        sb.append("Optimized size: ${g.sizeHuman}\n")
        sb.append("Profile: ${Profiles.description(g.profile)}\n\n")
        val notes = try {
            JSONArray(g.notesJson)
        } catch (e: Exception) {
            JSONArray()
        }
        sb.append(if (notes.length() == 0) "No portability notes." else formatNotes(notes))
        android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.report)
            .setMessage(sb)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatNotes(notes: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until notes.length()) {
            val n = notes.optJSONObject(i) ?: continue
            sb.append("- [${if (n.optBoolean("blocker")) "!" else "i"}] ${n.optString("message")}\n")
        }
        return sb.toString().trimEnd()
    }
}
