# NativeMorph Engine — source package

Three layers live here:

1. `engine-apk/` — the Android app (Kotlin): PS5-style purple library UI,
   SAF folder import, on-device import/optimizer via JNI, Godot 4.7.2
   runtime (official AAR) + Godot 3.6.2 runtime (relocated template),
   floating touch-control overlays, per-game profiles.
   Run `./get-dependencies.sh` inside it first — it downloads the official
   Godot AAR + 3.6.2 export templates and regenerates the relocated 3.x
   runtime layer (`tools/relocate_godot3.py`).
   Build: `gradle assembleRelease` (needs Android SDK, NDK 29, JDK 17).

2. `native/` — the C++17 core shared by the CLI driver and the APK JNI:
   spec-accurate PCK v1/v2/v3/v4 reader/writer, ECFG (project.binary)
   decoder, mobile profile patcher, byte-exact repacker.

3. `website/` — this Next.js download page (npm install && npm run dev).

`scripts/` holds the verification battery: synthetic pack matrix, the
real-game import battery runner and the gameplay screenshot driver used to
produce the screenshots on the website.

Licenses: app code is yours to use freely. Godot is MIT (Copyright Godot
Engine contributors). Material Symbols are Apache-2.0 (Google). The release
keystore ships at engine-apk/keystore/nativemorph.jks, so
`gradle assembleRelease` works out of the box and rebuilds install as
updates of this APK.

NOTE: `engine-apk/app/src/main/jniLibs/arm64-v8a/libgodot3x_droid.so` (the
relocated 3.6.2 runtime) is NOT shipped in this zip — run
`engine-apk/get-dependencies.sh` to regenerate it from the official 3.6.2
export templates.
