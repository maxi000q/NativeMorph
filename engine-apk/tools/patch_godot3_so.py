#!/usr/bin/env python3
"""
patch_godot3_so.py — relocate the official Godot 3.6.2 Android runtime for
NativeMorph (v1.3.1 build tooling; equivalent of tools/relocate_godot3.py's
patch_so() step, but self-contained: it works directly from the official
export-template .tpz without needing a checkout of the Godot source tree).

The 3.6.2 engine library binds its Java native methods to the ORIGINAL
org.godotengine.godot classes via exported Java_org_godotengine_godot_*
symbols and JNI class-reference strings in .rodata. NativeMorph's java layer
lives at org.nativemorph.god3x (so the 3.x and 4.7.2 runtimes can coexist in
one APK), so every class-reference string is binary-rewritten:

    org/godotengine/godot  ->  org/nativemorph/god3x   (same length, 21)
    org.godotengine.godot  ->  org.nativemorph.god3x   (same length, 21)

Same-length replacement keeps every ELF offset and string-table entry valid.
The exported Java_org_godotengine_godot_* symbol NAMES stay untouched;
libnm_godot3_jni.so (see app/src/main/cpp/nm_godot3_jni.cpp) re-registers
them onto the relocated classes with RegisterNatives at load time.

The java module (engine-apk/godot3-lib) ships pre-relocated in the source
zip; this script only regenerates the native side:

    app/src/main/jniLibs/arm64-v8a/libgodot3x_droid.so

USAGE
-----
    python3 tools/patch_godot3_so.py <path-to-3.6.2-android_release.apk-or-.so>
                                     <output-libgodot3x_droid.so>
"""
import os
import sys
import zipfile

OLD_SLASH = b"org/godotengine/godot"
NEW_SLASH = b"org/nativemorph/god3x"
OLD_PKG = b"org.godotengine.godot"
NEW_PKG = b"org.nativemorph.god3x"
assert len(OLD_SLASH) == len(NEW_SLASH)
assert len(OLD_PKG) == len(NEW_PKG)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__ + "\nusage: patch_godot3_so.py <in> <out>")
    src, dst = sys.argv[1], sys.argv[2]

    if src.endswith(".apk"):
        zf = zipfile.ZipFile(src)
        data = bytearray(zf.read("lib/arm64-v8a/libgodot_android.so"))
    else:
        data = bytearray(open(src, "rb").read())

    slash = data.count(OLD_SLASH)
    dot = data.count(OLD_PKG)
    if slash == 0:
        raise SystemExit(
            "FATAL: no JNI class references found — not a Godot 3.x android "
            "engine library?")
    data = data.replace(OLD_SLASH, NEW_SLASH)
    data = data.replace(OLD_PKG, NEW_PKG)

    # self-check: nothing left, size unchanged
    assert OLD_SLASH not in data
    assert OLD_PKG not in data

    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "wb") as fh:
        fh.write(data)
    print(f"relocated 3.6.2 engine: {slash} slash-form + {dot} dot-form "
          f"references -> {dst} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
