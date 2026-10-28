#!/bin/bash
# Run the full game battery through host_driver = the SAME native import
# pipeline (load -> inspect -> patch -> repack -> icon) the APK's JNI runs.
# FIDELITY profile (the app default) for all; battery+compat for OPEN HOUSE.
set -u
DRV=/tmp/ohtest/host_driver
SRC=/tmp/battery/staged
OUT=/tmp/battery/imported
mkdir -p "$OUT"
PASS=0; FAIL=0
declare -a SUMMARY

run_import() {  # name, src, opts
  local name="$1" src="$2" opts="$3"
  local work="$OUT/$name"
  rm -rf "$work"; mkdir -p "$work"
  local json
  json=$("$DRV" "$src" "$work" "$opts" 2>"$work/stderr.txt")
  echo "$json" > "$work/report.json"
  local ok
  ok=$(printf '%s' "$json" | python3 -c "import json,sys; j=json.load(sys.stdin); print('1' if j.get('ok') else '0')" 2>/dev/null)
  if [ "$ok" = "1" ]; then
    PASS=$((PASS+1))
    SUMMARY+=("PASS  $name  $(printf '%s' "$json" | python3 -c "import json,sys; j=json.load(sys.stdin); print(j.get('name','?'), j.get('engine_version','?'), j.get('format','?'), j.get('size_in_h','?')+' -> '+j.get('size_out_h','?'), 'files='+str(j.get('files','?')), 'settings='+str(j.get('settings','?')))")")
  else
    FAIL=$((FAIL+1))
    SUMMARY+=("FAIL  $name  $(printf '%s' "$json" | python3 -c "import json,sys; j=json.load(sys.stdin); print(j.get('error','no error field'))" 2>/dev/null)")
  fi
}

FID='{"renderer":"keep","scale":1.0,"fsr":false,"msaa":-1,"shadows":-1}'
BAT='{"renderer":"mobile","scale":0.75,"fsr":true,"msaa":-1,"shadows":-1}'
CMP='{"renderer":"gl_compatibility","scale":1.0,"fsr":false,"msaa":-1,"shadows":-1}'

run_import cosmic-racer       "$SRC/cosmic-racer.pck"      "$FID"
run_import pixel-jumper       "$SRC/pixel-jumper.pck"      "$FID"
run_import star-catcher       "$SRC/Star Catcher.pck"      "$FID"
run_import puzzle-2048        "$SRC/puzzle-2048.pck"      "$FID"
run_import maze-explorer      "$SRC/maze-explorer.pck"     "$FID"
run_import space-shooter      "$SRC/space-shooter.pck"     "$FID"
run_import retro-pong         "$SRC/retro-pong.pck"        "$FID"
run_import snake-classic      "$SRC/snake-classic.pck"     "$FID"
run_import open-house         "$SRC/OPEN HOUSE.pck"        "$FID"
run_import open-house-battery "$SRC/OPEN HOUSE.pck"        "$BAT"
run_import open-house-compat  "$SRC/OPEN HOUSE.pck"        "$CMP"
run_import probe-exe-embedded "$SRC/probe_v4.exe"          "$FID"
run_import probe-zip-bundle   "$SRC/probe_bundle.zip"      "$FID"
run_import probe-fmt2         "$SRC/probe_fmt2.pck"       "$FID"
run_import probe-fmt1         "$SRC/probe_fmt1.pck"       "$FID"

echo "=========================================="
for s in "${SUMMARY[@]}"; do echo "$s"; done
echo "=========================================="
echo "PASS=$PASS FAIL=$FAIL"
ls -la "$OUT"/*/game.pck 2>/dev/null | wc -l
