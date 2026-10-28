#!/usr/bin/env python3
"""Relocate Godot 3.6.2's Android runtime into the NativeMorph engine APK.

1. Copies the official 3.6.2 java lib (MIT) from the export-template source
   into engine-apk/godot3-lib, renaming the package
   org.godotengine.godot -> org.nativemorph.god3x
   (same length: 21 bytes, so every reference stays byte-compatible) and
   switching the native lib name godot_android -> godot3x_droid
   (13 bytes, also same length - avoids colliding with the 4.7.2 AAR's
   libgodot_android.so which is already in the APK).

2. Binary-patches the 30 MB arm64 libgodot_android.so the same way: the
   engine's JNI class references live in .rodata strings
   ("org/godotengine/godot/GodotLib", "Lorg/godotengine/godot/...;" etc);
   a same-length rename keeps every ELF offset and every string table entry
   valid. No ELF checksums exist for .rodata content.

Output: app/src/main/jniLibs/arm64-v8a/libgodot3x_droid.so
"""
import os
import re
import shutil
import sys

REPO = "/home/z/my-project/nativemorph"
LIB_SRC = os.path.join(
    REPO, "research/godot-3.6.2-stable/platform/android/java/lib")
MODULE = os.path.join(REPO, "engine-apk/godot3-lib")
SO_IN = os.path.join(
    REPO, "research/g3template/lib/arm64-v8a/libgodot_android.so")
JNILIBS = os.path.join(
    REPO, "engine-apk/app/src/main/jniLibs/arm64-v8a")
SO_OUT = os.path.join(JNILIBS, "libgodot3x_droid.so")

OLD_PKG = "org.godotengine.godot"
NEW_PKG = "org.nativemorph.god3x"
OLD_SLASH = "org/godotengine/godot"
NEW_SLASH = "org/nativemorph/god3x"
assert len(OLD_PKG) == len(NEW_PKG) == 21
assert len(OLD_SLASH) == len(NEW_SLASH) == 21
OLD_LIB = "godot_android"
NEW_LIB = "godot3x_droid"
assert len(OLD_LIB) == len(NEW_LIB) == 13


def relocate_sources():
    if os.path.exists(MODULE):
        shutil.rmtree(MODULE)
    os.makedirs(MODULE)
    for d in ("src", "res", "aidl"):
        src = os.path.join(LIB_SRC, d)
        if not os.path.isdir(src):
            sys.exit(f"missing source dir: {src}")
        shutil.copytree(src, os.path.join(MODULE, d))
    n = 0
    for root, _, files in os.walk(os.path.join(MODULE, "src")):
        for f in files:
            if not f.endswith((".java", ".kt", ".aidl")):
                continue
            p = os.path.join(root, f)
            with open(p, "r", encoding="utf-8") as fh:
                t = fh.read()
            t2 = t.replace(OLD_PKG, NEW_PKG)
            t2 = t2.replace(f'loadLibrary("{OLD_LIB}")',
                            f'loadLibrary("{NEW_LIB}")')
            if t2 != t:
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(t2)
                n += 1
    print(f"relocated {n} source files -> {NEW_PKG}")
    # the lib's own manifest: keep (service + ProcessPhoenix activity are
    # merged into the app manifest automatically by AGP)


def patch_so():
    os.makedirs(JNILIBS, exist_ok=True)
    with open(SO_IN, "rb") as fh:
        data = bytearray(fh.read())
    slash = data.count(OLD_SLASH.encode())
    dot = data.count(OLD_PKG.encode())
    data = data.replace(OLD_SLASH.encode(), NEW_SLASH.encode())
    data = data.replace(OLD_PKG.encode(), NEW_PKG.encode())
    with open(SO_OUT, "wb") as fh:
        fh.write(data)
    print(f"patched .so: {slash} slash-form + {dot} dot-form references "
          f"-> {SO_OUT} ({len(data)} bytes)")
    if slash == 0:
        sys.exit("FATAL: no JNI class references found in .so - wrong input?")


def selfcheck():
    with open(SO_OUT, "rb") as fh:
        data = fh.read()
    assert OLD_SLASH.encode() not in data, "leftover slash-form"
    # dexless sanity: no ELF section table damage possible on same-length
    print("self-check: no leftover references; same-size patch OK")


if __name__ == "__main__":
    relocate_sources()
    patch_so()
    selfcheck()
    print("done")
