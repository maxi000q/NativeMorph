#!/usr/bin/env bash
# nativemorph - align + sign the assembled APK.
# usage: sign.sh <in.apk> <out.apk> <keystore> <alias> <pass>
set -euo pipefail

IN="${1:?in apk}" OUT="${2:?out apk}" KS="${3:?keystore}"
ALIAS="${4:?alias}" PASS="${5:?pass}"

BT="${ANDROID_HOME:?ANDROID_HOME not set}/build-tools"
BT_DIR="$(ls -1 "$BT" 2>/dev/null | sort -V | tail -1)"
[ -n "$BT_DIR" ] || { echo "no build-tools under $ANDROID_HOME" >&2; exit 1; }
BT="$BT/$BT_DIR"

echo "[sign] zipalign"
"$BT/zipalign" -f -p 4 "$IN" "$OUT"

echo "[sign] apksigner (v2+v3 scheme, ARM64)"
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias "$ALIAS" \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  "$OUT"

"$BT/apksigner" verify --print-certs "$OUT"
echo "[sign] wrote $OUT"
