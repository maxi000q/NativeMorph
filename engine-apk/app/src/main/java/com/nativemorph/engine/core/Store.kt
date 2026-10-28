package com.nativemorph.engine.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

// ---------------------------------------------------------------------------
// The three mobile UI layouts selectable BEFORE import (settings gear) and
// per-game at launch; the active one floats above any running game.
// ---------------------------------------------------------------------------
object Layouts {
    const val CLASSIC = "classic"
    const val TWIN = "twin"
    const val GESTURE = "gesture"

    val all = listOf(CLASSIC, TWIN, GESTURE)
    fun label(id: String): String = when (id) {
        CLASSIC -> "Classic Pad"
        TWIN -> "Twin Stick"
        GESTURE -> "Gesture Minimal"
        else -> id
    }

    fun description(id: String): String = when (id) {
        CLASSIC ->
            "D-pad + A/B/X/Y + Start/Select. Digital keys; works with almost " +
            "every desktop game. Add the camera stick for drag-to-look games."
        TWIN ->
            "Two analog sticks (movement + camera), shoulder triggers. Right " +
            "stick drives the camera with synthetic touch drags."
        GESTURE ->
            "No visible buttons. Tap = action, swipe = move, double-tap = " +
            "jump, two-finger tap = menu. For touch-friendly games; the " +
            "camera stick can be added too."
        else -> ""
    }
}

// ---------------------------------------------------------------------------
// Optimization profiles. FIDELITY is the "same graphics" default: mobile
// Vulkan renderer, authored MSAA/shadows preserved, 1.0 render scale.
// ---------------------------------------------------------------------------
object Profiles {
    const val FIDELITY = "fidelity"
    const val BATTERY = "battery"
    const val COMPAT = "compat"

    fun label(id: String): String = when (id) {
        FIDELITY -> "Fidelity (same graphics)"
        BATTERY -> "Battery Saver"
        COMPAT -> "Compatibility (GL)"
        else -> id
    }

    fun description(id: String): String = when (id) {
        FIDELITY ->
            "Runs the game exactly as its developer exported it: the " +
            "authored renderer (Forward+, mobile or compatibility), MSAA, " +
            "shadows and render scale are all kept. The safest choice for " +
            "visual fidelity."
        BATTERY ->
            "0.75 render scale with FSR upscaling and the mobile Vulkan " +
            "renderer. Softer image, notably longer battery life. " +
            "Recommended for long sessions."
        COMPAT ->
            "Forces the OpenGL ES 3 compatibility renderer - the most " +
            "conservative pipeline. Use this if a game shows a black screen " +
            "or crashes with the other profiles."
        else -> ""
    }

    fun all(): List<String> = listOf(FIDELITY, BATTERY, COMPAT)

    /** Patch options JSON consumed by the native import pipeline. */
    fun patchOptsJson(id: String): String = when (id) {
        BATTERY ->
            "{\"renderer\":\"mobile\",\"scale\":0.75,\"fsr\":true," +
            "\"msaa\":-1,\"shadows\":-1}"
        COMPAT ->
            "{\"renderer\":\"gl_compatibility\",\"scale\":1.0,\"fsr\":false," +
            "\"msaa\":-1,\"shadows\":-1}"
        else ->
            "{\"renderer\":\"keep\",\"scale\":1.0,\"fsr\":false," +
            "\"msaa\":-1,\"shadows\":-1}"
    }

    /** Runtime --rendering-method cmdline override, or null to keep authored. */
    fun rendererOverride(id: String): String? = when (id) {
        BATTERY -> "mobile"
        COMPAT -> "gl_compatibility"
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Preferences (the pre-upload settings screen writes these).
// ---------------------------------------------------------------------------
object Prefs {
    private const val FILE = "nativemorph"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun layout(ctx: Context): String =
        sp(ctx).getString("layout", Layouts.CLASSIC) ?: Layouts.CLASSIC

    fun setLayout(ctx: Context, v: String) = sp(ctx).edit().putString("layout", v).apply()

    fun profile(ctx: Context): String =
        sp(ctx).getString("profile", Profiles.FIDELITY) ?: Profiles.FIDELITY

    fun setProfile(ctx: Context, v: String) = sp(ctx).edit().putString("profile", v).apply()

    fun buttonScale(ctx: Context): Float =
        sp(ctx).getFloat("bscale", 1.0f)

    fun setButtonScale(ctx: Context, v: Float) = sp(ctx).edit().putFloat("bscale", v).apply()

    fun buttonAlpha(ctx: Context): Float =
        sp(ctx).getFloat("balpha", 0.55f)

    fun setButtonAlpha(ctx: Context, v: Float) = sp(ctx).edit().putFloat("balpha", v).apply()

    fun haptics(ctx: Context): Boolean = sp(ctx).getBoolean("haptics", true)

    fun setHaptics(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("haptics", v).apply()

    /** Extra camera joystick floating above any layout (Classic Pad & Gesture). */
    fun cameraStick(ctx: Context): Boolean = sp(ctx).getBoolean("camstick", true)

    fun setCameraStick(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("camstick", v).apply()
}

// ---------------------------------------------------------------------------
// Name sanitization: SAF display names can carry control characters,
// zero-width runes and path separators. Everything we store or display goes
// through clean()/safeFile() first.
// ---------------------------------------------------------------------------
object Names {
    private val ctrl = Regex("[\\p{Cc}\\p{Cf}]")  // C0/C1 controls + zero-width

    /** Human display name: keep unicode, drop control/format runes. */
    fun clean(raw: String?, fallback: String): String {
        val s = ctrl.replace(raw ?: "", "").trim(' ', '\u00A0', '.', '-', '_')
        return if (s.isEmpty()) fallback else s.take(64)
    }

    /** File name inside our sandbox: also strip separators and wildcards. */
    fun safeFile(raw: String?, fallback: String): String {
        val s = clean(raw, fallback)
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(' ', '_')
        return s.ifEmpty { fallback }
    }

    fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(i + 1).lowercase() else ""
    }

    /** True if the name contains only printable characters. */
    fun printable(name: String?): Boolean =
        name != null && !ctrl.containsMatchIn(name)
}

// ---------------------------------------------------------------------------
// Imported game model + library storage.
// One directory per game under <external files>/games/<id>/: game.pck (the
// optimized pack), meta.json. The optimized pck is what the engine runs via
// --main-pack; the raw import is deleted after success.
// ---------------------------------------------------------------------------
data class GameMeta(
    val id: String,
    val name: String,
    val engineVersion: String,
    val pckAbsPath: String,
    val sizeBytes: Long,
    val sizeHuman: String,
    val importedAt: Long,
    val profile: String,
    val layoutOverride: String?,   // null = app default layout
    val notesJson: String,
    val files: Int,
    val iconPath: String? = null,  // extracted game icon (absolute path)
) {
    /** 3 => run on the embedded 3.6.2 runtime, 4 => 4.7.2. */
    val engineMajor: Int
        get() = if (engineVersion.startsWith("3")) 3 else 4
}

object GameLibrary {

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "games").apply { mkdirs() }

    fun createWork(ctx: Context): File =
        File(dir(ctx), UUID.randomUUID().toString()).apply { mkdirs() }

    fun get(ctx: Context, id: String): GameMeta? {
        val f = File(File(dir(ctx), id), "meta.json")
        if (!f.isFile) return null
        return try {
            val j = JSONObject(f.readText())
            val icon = if (j.isNull("icon")) null else j.optString("icon", null)
            GameMeta(
                id = id,
                name = j.optString("name", "Godot game"),
                engineVersion = j.optString("engine", "4"),
                pckAbsPath = File(f.parentFile, "game.pck").absolutePath,
                sizeBytes = j.optLong("size", 0),
                sizeHuman = j.optString("size_h", "?"),
                importedAt = j.optLong("at", 0),
                profile = j.optString("profile", Profiles.FIDELITY),
                layoutOverride = if (j.isNull("layout")) null else j.optString("layout", null),
                notesJson = j.optJSONArray("notes")?.toString() ?: "[]",
                files = j.optInt("files", 0),
                iconPath = icon?.let { File(f.parentFile, it).absolutePath },
            )
        } catch (e: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<GameMeta> =
        dir(ctx).listFiles { d -> d.isDirectory }
            ?.mapNotNull { get(ctx, it.name) }
            ?.sortedByDescending { it.importedAt }
            ?: emptyList()

    fun save(ctx: Context, meta: GameMeta) {
        val dir = File(dir(ctx), meta.id)
        val notes = try {
            JSONArray(meta.notesJson)
        } catch (e: Exception) {
            JSONArray()
        }
        JSONObject()
            .put("name", meta.name)
            .put("engine", meta.engineVersion)
            .put("size", meta.sizeBytes)
            .put("size_h", meta.sizeHuman)
            .put("at", meta.importedAt)
            .put("profile", meta.profile)
            .put("layout", meta.layoutOverride ?: JSONObject.NULL)
            .put("notes", notes)
            .put("files", meta.files)
            .put("icon", meta.iconPath?.let { File(it).name } ?: JSONObject.NULL)
            .let { File(dir, "meta.json").writeText(it.toString()) }
    }

    fun setLayoutOverride(ctx: Context, meta: GameMeta, layout: String?) {
        save(ctx, meta.copy(layoutOverride = layout))
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), id).deleteRecursively()
    }
}
