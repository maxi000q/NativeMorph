package com.nativemorph.engine

/**
 * Bridge to the on-device native optimizer (libnativemorph.so).
 *
 * importGame() runs the full pipeline: locate PCK (pck/zip/exe/apk),
 * portability audit, project.godot patch, byte-exact repack. The callback is
 * invoked from the calling thread; run it on a worker thread.
 */
object NativeBridge {

    init {
        System.loadLibrary("nativemorph")
    }

    interface Progress {
        /** stage: copy|scan|inspect|patch|repack|done ; pct 0..100 */
        fun onProgress(stage: String, pct: Int, msg: String)
    }

    /** @return JSON report string (ok / rejected / error fields). */
    external fun importGame(
        srcPath: String,
        workDir: String,
        optsJson: String,
        cb: Progress,
    ): String

    external fun coreVersion(): String
}
