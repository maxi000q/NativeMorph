#!/bin/bash
# NativeMorph: fetch the third-party binaries not shipped in the source zip.
# Everything downloaded here is an official Godot project release artifact.
#
# v1.3.1: the official 4.7.2 template AAR is additionally patched by
# tools/patch_godot_lib.py so the engine accepts --main-pack paths OUTSIDE
# the APK (the fix for the "Unable to setup Godot Engine, aborting" error
# that killed every game — see README.md, "The v1.3.1 fix").
set -e
cd "$(dirname "$0")"

echo "== 1. Godot 4.7.2 release AAR (the embedded 4.x runtime) =="
mkdir -p libs
if [ ! -f libs/godot-lib-4.7.2-release.aar ]; then
  # NOTE: the official asset name is godot-lib.<ver>.stable.template_release.aar
  # (v1.3.0's script used a wrong URL and failed with 404).
  curl -L -o libs/godot-lib-4.7.2-release.aar \
    "https://github.com/godotengine/godot/releases/download/4.7.2-stable/godot-lib.4.7.2.stable.template_release.aar"
fi

echo "== 2. Patch the 4.7.2 runtime: allow external --main-pack paths =="
python3 tools/patch_godot_lib.py libs/godot-lib-4.7.2-release.aar

echo "== 3. Godot 3.6.2 export templates (source of the relocated 3.x runtime) =="
mkdir -p tools/dl
if [ ! -f tools/dl/godot-3.6.2-templates.tpz ]; then
  curl -L -o tools/dl/godot-3.6.2-templates.tpz \
    "https://github.com/godotengine/godot/releases/download/3.6.2-stable/Godot_v3.6.2-stable_export_templates.tpz"
fi

echo "== 4. Relocate the 3.6.2 android runtime to org.nativemorph.god3x =="
if [ ! -f tools/dl/android_release.apk ]; then
  unzip -o -q tools/dl/godot-3.6.2-templates.tpz templates/android_release.apk -d tools/dl
  mv tools/dl/templates/android_release.apk tools/dl/android_release.apk
  rmdir tools/dl/templates
fi
python3 tools/patch_godot3_so.py tools/dl/android_release.apk \
  app/src/main/jniLibs/arm64-v8a/libgodot3x_droid.so

echo "== 5. (optional) Material Symbols font subset =="
# assets/fonts/material_symbols.ttf ships prebuilt in the source zip, so this
# is only needed if you want to regenerate the glyph set:
#   pip install fonttools brotli
#   python3 ../../../scripts/msicons/subset_icons.py
echo "Done. Now open this folder in Android Studio or run:"
echo "  ANDROID_HOME=<sdk> JAVA_HOME=<jdk17+> gradle assembleRelease"
