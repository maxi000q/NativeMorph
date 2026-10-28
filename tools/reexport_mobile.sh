#!/usr/bin/env bash
# nativemorph - tier 1a: official headless re-export for OWNED Godot projects.
#
# Highest-fidelity mobile conversion for projects you have the source for:
# the engine's own importer re-encodes every texture to ASTC at import time,
# the mobile renderer is selected by the export preset, and the result is the
# official Android artifact. No data surgery involved.
#
# usage: reexport_mobile.sh <project-dir> [output.apk]
set -euo pipefail

PROJ="${1:?project directory}"
OUT="${2:-$(basename "$PROJ")_mobile.apk}"
GODOT="${GODOT_BIN:-godot}"

cd "$PROJ"

# One-time keystore if none exists
if [ ! -f .nativemorph.keystore ]; then
  keytool -genkeypair -v -keystore .nativemorph.keystore \
    -alias nativemorph -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass nativemorph -keypass nativemorph \
    -dname "CN=nativemorph, O=self"
fi

# Ensure an Android export preset exists (mobile renderer + ASTC imports).
python3 - <<'PY'
import configparser, os
preset = "export_presets.cfg"
if not os.path.exists(preset):
    with open(preset, "w") as f:
        f.write('[preset.0]\nname="Android Mobile"\nplatform="Android"\n'
                'runnable=true\n'
                '[preset.0.options]\n'
                'gradle_build/use_gradle_build=false\n'
                'package/unique_name="com.nativemorph.export"\n'
                'graphics/32_bits_float=true\n')
PY

# Import with mobile VRAM defaults: ASTC is the Adreno-native block format.
echo "[reexport] headless re-import (ASTC presets)"
"$GODOT" --headless --import --import-settings-source=false

echo "[reexport] export Android (mobile renderer selected by preset)"
"$GODOT" --headless --export-release "Android Mobile" "$OUT" \
  --export-features mobile

echo "[reexport] done: $OUT (debug-sign with:"
echo "  apksigner sign --ks .nativemorph.keystore ... )"
