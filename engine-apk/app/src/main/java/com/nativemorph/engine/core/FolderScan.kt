package com.nativemorph.engine.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Recursively scans a SAF document tree (the folder the user picked) and
 * finds Godot game candidates - one game per folder.
 *
 * Games that ship as a Windows .exe plus a side-by-side .pck are the
 * primary case: when a folder holds both, the .pck is imported and the
 * launcher .exe is ignored (the native layer verifies the pack anyway).
 * Folders inside the picked folder are treated as separate games, so a
 * "Games" directory with many game folders imports them all.
 */
object FolderScan {

    data class Candidate(
        val uri: Uri,
        val name: String,       // sanitized display name
        val ext: String,        // pck | exe | zip | apk | obb
        val size: Long,
        val folderName: String, // parent folder display name (for messages)
    )

    private const val MAX_DEPTH = 6
    private const val MAX_ENTRIES = 8000
    private const val MAX_GAMES = 25

    private val junk = setOf("thumbs.db", "desktop.ini", ".ds_store", "desktop")

    private class Found(
        val uri: Uri,
        val name: String,
        val size: Long,
        val ext: String,
    )

    fun scan(resolver: ContentResolver, tree: Uri): Pair<List<Candidate>, List<String>> {
        val notes = mutableListOf<String>()
        val games = mutableListOf<Candidate>()
        val state = intArrayOf(0)
        val root = DocumentsContract.getTreeDocumentId(tree)

        fun walk(docId: String, depth: Int, folderName: String) {
            if (depth > MAX_DEPTH || state[0] > MAX_ENTRIES || games.size >= MAX_GAMES)
                return
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val proj = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val perExt = HashMap<String, Found>()
            try {
                resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                    val idxId = c.getColumnIndex(proj[0])
                    val idxName = c.getColumnIndex(proj[1])
                    val idxMime = c.getColumnIndex(proj[2])
                    val idxSize = c.getColumnIndex(proj[3])
                    while (c.moveToNext()) {
                        state[0]++
                        if (state[0] > MAX_ENTRIES) break
                        val id = c.getString(idxId) ?: continue
                        val rawName = c.getString(idxName) ?: continue
                        val mime = c.getString(idxMime) ?: ""
                        val size = if (idxSize >= 0) c.getLong(idxSize) else 0L

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (rawName.startsWith(".") ||
                                rawName.equals("__macosx", ignoreCase = true)
                            ) continue
                            walk(id, depth + 1, Names.clean(rawName, "folder"))
                            continue
                        }

                        val name = rawName.trim()
                        val lower = name.lowercase()
                        if (lower in junk || lower.startsWith(".")) continue
                        if (!Names.printable(name)) {
                            notes.add("Skipped a file with a corrupted name in \"$folderName\"")
                            continue
                        }
                        val ext = when {
                            lower.endsWith(".pck") -> "pck"
                            lower.endsWith(".exe") -> "exe"
                            lower.endsWith(".zip") -> "zip"
                            lower.endsWith(".apk") -> "apk"
                            lower.endsWith(".obb") -> "obb"
                            else -> null
                        } ?: continue
                        if (size in 1..1023 && (ext == "exe" || ext == "pck")) continue

                        val f = Found(
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                            name, size, ext,
                        )
                        val prev = perExt[ext]
                        // keep the largest of each extension in the folder
                        if (prev == null || f.size > prev.size) perExt[ext] = f
                    }
                }
            } catch (e: Exception) {
                notes.add("Some subfolders could not be read: ${e.message ?: "provider error"}")
            }

            // one game per folder
            val pcks = perExt.values.filter { it.ext == "pck" }.sortedByDescending { it.size }
            val exes = perExt.values.filter { it.ext == "exe" }.sortedByDescending { it.size }
            val archives = perExt.values.filter { it.ext in listOf("zip", "apk", "obb") }
                .sortedByDescending { it.size }
            val picked: List<Found> = when {
                pcks.isNotEmpty() -> pcks      // side packs become games too
                exes.isNotEmpty() -> listOf(exes.first())
                archives.isNotEmpty() -> listOf(archives.first())
                else -> emptyList()
            }
            picked.forEach { f ->
                if (games.size < MAX_GAMES) {
                    games.add(
                        Candidate(
                            f.uri,
                            Names.clean(f.name.substringBeforeLast('.'), "game"),
                            f.ext,
                            f.size,
                            folderName,
                        )
                    )
                }
            }
        }

        walk(root, 0, "picked folder")
        if (state[0] > MAX_ENTRIES)
            notes.add("The folder is very large; scanning stopped after $MAX_ENTRIES entries")
        if (games.size >= MAX_GAMES)
            notes.add("Only the first $MAX_GAMES games were imported; import the rest in another pass")
        return Pair(games, notes)
    }
}
