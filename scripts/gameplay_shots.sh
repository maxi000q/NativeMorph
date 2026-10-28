#!/bin/bash
# Gameplay screenshot battery: boots every IMPORTED game pack in the REAL
# engine (4.7.2 / 3.6.2) on software GL, drives it with synthetic input and
# records movie frames - the exact --main-pack command lines our Android
# activities construct per profile.
set -u
SHOTS=/tmp/shots
IMP=/tmp/battery/imported
XKEYS=/home/z/my-project/scripts/emutest/xkeys
G4=/tmp/ohtest/Godot_v4.7.2-stable_linux.x86_64
G3=/tmp/Godot_v3.6.2-stable_x11.64
mkdir -p "$SHOTS"

run4() {  # name pack extra_args input_seq
  local name="$1" pack="$2" extra="$3" seq="$4"
  echo "== $name =="
  DISPLAY=:95 timeout 240 env LIBGL_ALWAYS_SOFTWARE=1 "$G4" \
    --main-pack "$IMP/$pack/game.pck" $extra --fixed-fps 5 --quit-after 200 \
    --write-movie "$SHOTS/$name.avi" > "$SHOTS/$name.log" 2>&1 &
  local pid=$!
  if [ -n "$seq" ]; then
    sleep 12
    $XKEYS :95 "$seq" >> "$SHOTS/$name.log" 2>&1
  fi
  wait $pid
  grep -m1 "NMBOOT" "$SHOTS/$name.log" || echo "NO NMBOOT in $name"
}

run3() {  # name pack input_seq
  local name="$1" pack="$2" seq="$3"
  echo "== $name =="
  DISPLAY=:95 timeout 240 env LIBGL_ALWAYS_SOFTWARE=1 "$G3" \
    --main-pack "$IMP/$pack/game.pck" --fixed-fps 5 --quit-after 200 \
    --write-movie "$SHOTS/$name.avi" > "$SHOTS/$name.log" 2>&1 &
  local pid=$!
  if [ -n "$seq" ]; then
    sleep 12
    $XKEYS :95 "$seq" >> "$SHOTS/$name.log" 2>&1
  fi
  wait $pid
  grep -m1 "NMBOOT" "$SHOTS/$name.log" || echo "NO NMBOOT in $name"
}

# 4.x games - renderer per profile exactly like the app builds the cmdline
run4 cosmic-racer  cosmic-racer  "--rendering-method gl_compatibility" "L,s500,R,s500,R,s500,L,s500,R,s500,R,s500,L,s500,R"
run4 pixel-jumper  pixel-jumper  "--rendering-method gl_compatibility" "R,s400,R,A,s300,R,A,s300,R,s400,L,s400,R,A,s300,A,s300,R"
run4 star-catcher star-catcher  "--rendering-method mobile"            "s800,c400x200,s400,c800x300,s400,c300x450,s400,c900x500,s400,c600x250,s400,c500x600"
run4 puzzle-2048   puzzle-2048   "--rendering-method gl_compatibility" "L,s400,U,s400,R,s400,D,s400,L,L,s400,U,s400,R,s400,D,s400,R,R"
run4 maze-explorer maze-explorer "--rendering-method gl_compatibility" "D,s400,D,s400,D,s400,R,s400,R,s400,D,s400,D,s400,D,s400,L,s400,L"
run4 space-shooter space-shooter "--rendering-method mobile"            "A,s300,L,s300,A,s300,R,s300,R,s300,A,s300,A,s300,L,s300,A,s300,R,s300,A"
run4 open-house-battery open-house-battery "--rendering-method mobile --fixed-fps 60" ""
run4 open-house-compat  open-house-compat  "--rendering-method gl_compatibility" ""
# 3.x games via the rendered 3.6.2 editor runtime
run3 retro-pong    retro-pong    "U,s400,U,s400,D,s400,D,s400,U,s400,U,s400,D,s400,U,s400,D,s400"
run3 snake-classic snake-classic "R,s400,R,s400,R,s400,D,s400,D,s400,L,s400,L,s400,U,s400,R,s400,R"

echo "=== extracting frames ==="
for f in "$SHOTS"/*.avi; do
  n=$(basename "$f" .avi)
  ffmpeg -y -loglevel error -i "$f" -vf "select='not(mod(n\,50))'" -vsync vfr "$SHOTS/${n}_f%02d.png"
done
ls -la "$SHOTS"/*.png | awk '{print $NF, $5}'
