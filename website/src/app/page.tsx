import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { CopyButton } from "@/components/copy-button";
import {
  Download,
  Package,
  ShieldCheck,
  Cpu,
  Gamepad2,
  Sparkles,
  AlertTriangle,
  CircleCheck,
  CircleX,
  Terminal,
  FileArchive,
  MousePointer2,
  Tv,
} from "lucide-react";

const APK_URL = "/apk/NativeMorphEngine-v1.3.2.apk";
const APK_SHA256 =
  "fa82a2869d782cb62c13de99ef32ee7976dd8d4cbe6d35617a377b1c98f0c807";
const APK_BYTES = 106_831_798;
const SOURCE_URL = "/downloads/NativeMorph-source-v1.3.2.zip";
const SOURCE_SHA256 = "source-zip-v1.3.2";

function Mark({ className = "h-8 w-8" }: { className?: string }) {
  return (
    <svg viewBox="0 0 108 108" className={className} aria-hidden="true">
      <rect width="108" height="108" rx="26" fill="#6C4DFF" />
      <path d="M42 36 L42 72 L74 54 Z" fill="#FFFFFF" />
      <path
        d="M34 66 A18 18 0 1 0 54 84 A14 14 0 1 1 34 66 Z"
        fill="#FFFFFF"
        opacity="0.72"
      />
    </svg>
  );
}

const whatsNew = [
  {
    t: "Camera rotation joystick",
    d: "A camera stick now floats on the right side of the screen above any game (Classic Pad and Gesture layouts; Twin Stick always had one). Enable or disable it in Settings or live from the in-game gear panel - it drives drag-to-look cameras with smooth synthetic drags.",
  },
  {
    t: "Virtual mouse with click button",
    d: "Next to the gear handle there is now a mouse button: tap it and the whole screen becomes a touchpad with a visible cursor. Drag anywhere to move the cursor (it rides on top of every on-screen control), tap the pad to click, and hold the target button to drag with the left mouse button pressed.",
  },
  {
    t: "PS5-style main menu",
    d: "The library was rebuilt around the console home screen: a slim function bar with circular icons and the clock up top, the selected game's name and Play button right above a row of square tiles, and a flowing silk-wave aurora background with drifting sparkles - all still in the signature purple theme.",
  },
];

const errorChain = [
  "Official Godot Android export templates are built with disable_path_overrides=yes, so the OVERRIDE_PATH_ENABLED define is absent.",
  "Main::setup() (main/main.cpp, 4.7.2-stable, lines 1864-1896) then only accepts a --main-pack that resolves to ACCESS_RESOURCES - a pack inside the APK's own assets/.",
  "NativeMorph stores optimized packs in the app sandbox and always launches them via --main-pack <sandbox path> - an external path.",
  "Main::setup() returns ERR -> GodotLib.setup() returns false -> the app throws \"Unable to setup the Godot engine! Aborting...\" - for every game, every time.",
];

const alsoFixed = [
  "The 3.x module (godot3-lib) was packaged at the zip root instead of engine-apk/godot3-lib, so settings.gradle.kts could not resolve it and gradle assembleRelease failed out of the box.",
  "get-dependencies.sh used a wrong AAR URL (godot-lib.4.7.2.stable.release.aar - a 404). The official asset is godot-lib.4.7.2.stable.template_release.aar.",
  "get-dependencies.sh now regenerates the relocated Godot 3.6.2 runtime directly from the official export templates - no Godot source checkout required.",
  "GameActivity handles onNewIntent (it is singleTask), so launching a different game - or the watchdog retry-with-GL - always starts a fresh engine instance.",
  "The source zip ships a release keystore, so rebuilds install as updates of this exact APK.",
];

const matrix = [
  {
    input: "Godot 4.x .pck (PC export)",
    verdict: "Supported",
    why: "PCK v2 / v3 / v4 containers (4.0 through 4.7+) repacked on-device; runs on the embedded Godot 4.7.2 runtime.",
    ok: true,
  },
  {
    input: "Godot 3.x .pck",
    verdict: "Supported",
    why: "PCK v1 packs (3.0 - 3.6) run on the embedded Godot 3.6.2 runtime.",
    ok: true,
  },
  {
    input: ".exe + .pck folder / .zip / .obb",
    verdict: "Supported",
    why: "Pick the folder: side-by-side .exe + .pck pairs, self-contained .exe, .zip, Godot .apk and .obb inputs are located automatically. Folders with several games import them all.",
    ok: true,
  },
  {
    input: "Unity games",
    verdict: "Rejected",
    why: "Unity PC engine code is x86-64 machine code - it cannot run natively on ARM. The app refuses with a reason instead of pretending.",
    ok: false,
  },
  {
    input: "Encrypted packs / DRM",
    verdict: "Rejected",
    why: "Encrypted directories and payloads are refused by design.",
    ok: false,
  },
];

export default function Home() {
  return (
    <div className="flex min-h-screen flex-col bg-zinc-950 text-zinc-100">
      {/* header */}
      <header className="sticky top-0 z-10 border-b border-zinc-800/80 bg-zinc-950/85 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-4 sm:px-6">
          <Mark className="h-9 w-9" />
          <div className="flex-1">
            <p className="text-lg font-semibold leading-none">NativeMorph</p>
            <p className="text-xs text-zinc-400">Engine · v1.3.2 · new controls + PS5 UI</p>
          </div>
          <Badge
            variant="secondary"
            className="hidden border-zinc-700 bg-zinc-900 text-zinc-300 sm:inline-flex"
          >
            Godot 4.7.2 + 3.6.2 runtimes
          </Badge>
        </div>
      </header>

      <main className="flex-1">
        {/* hero + downloads */}
        <section className="mx-auto max-w-6xl px-4 pb-14 pt-12 sm:px-6 md:pt-16">
          <div className="flex flex-col items-start gap-6">
            <Badge className="border-violet-500/40 bg-violet-500/10 text-violet-300 hover:bg-violet-500/10">
              No emulation · No streaming · No account
            </Badge>
            <h1 className="max-w-3xl text-4xl font-bold leading-tight tracking-tight md:text-6xl">
              Your Godot games.
              <br />
              <span className="text-violet-400">Natively Android.</span>
            </h1>
            <p className="max-w-2xl text-base leading-relaxed text-zinc-400 sm:text-lg">
              Download the engine APK once. Inside the app, pick the folder your
              game lives in - the optimizer adapts it on-device and the embedded
              Godot runtimes (3.6.2 and 4.7.2) run it at full native speed.
              v1.3.1 fixed the engine-level bug that stopped{" "}
              <span className="font-semibold text-zinc-200">every</span> game
              from launching; v1.3.2 adds camera controls, a virtual mouse
              and a PS5-style menu.
            </p>

            {/* download cards */}
            <div className="grid w-full grid-cols-1 gap-4 md:grid-cols-2">
              <Card className="min-w-0 border-violet-500/30 bg-gradient-to-b from-violet-500/10 to-zinc-900/60">
                <CardContent className="flex h-full flex-col gap-4 p-6">
                  <div className="flex items-center gap-3">
                    <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-violet-500/15">
                      <Download className="h-6 w-6 text-violet-300" aria-hidden="true" />
                    </span>
                    <div>
                      <h2 className="font-semibold leading-tight">
                        Engine APK — v1.3.2 (latest)
                      </h2>
                      <p className="text-xs text-zinc-400">
                        107 MB · arm64-v8a · Android 7.0+ · signed
                      </p>
                    </div>
                  </div>
                  <p className="text-sm leading-relaxed text-zinc-400">
                    The latest build: camera stick, virtual mouse and the
                    PS5-style menu, on top of the v1.3.1 engine fix. Install it
                    over the old one (same package, higher versionCode) or
                    fresh - your imported games are kept.
                  </p>
                  <Button
                    asChild
                    size="lg"
                    className="mt-auto h-14 bg-violet-600 px-8 text-base font-semibold text-white hover:bg-violet-500"
                  >
                    <a href={APK_URL} download>
                      <Download className="mr-2 h-5 w-5" aria-hidden="true" />
                      Download Engine APK
                    </a>
                  </Button>
                  <div className="flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-950/70 p-2.5">
                    <ShieldCheck
                      className="h-4 w-4 shrink-0 text-emerald-500"
                      aria-hidden="true"
                    />
                    <p className="min-w-0 flex-1 truncate font-mono text-[11px] text-zinc-500">
                      SHA-256 {APK_SHA256}
                    </p>
                    <CopyButton value={APK_SHA256} label="Copy APK SHA-256" />
                  </div>
                </CardContent>
              </Card>

              <Card className="min-w-0 border-zinc-800 bg-zinc-900/60">
                <CardContent className="flex h-full flex-col gap-4 p-6">
                  <div className="flex items-center gap-3">
                    <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-zinc-800">
                      <FileArchive className="h-6 w-6 text-zinc-300" aria-hidden="true" />
                    </span>
                    <div>
                      <h2 className="font-semibold leading-tight">
                        Source code — v1.3.2 (latest)
                      </h2>
                      <p className="text-xs text-zinc-400">
                        1.0 MB zip · Kotlin + C++ + build tooling
                      </p>
                    </div>
                  </div>
                  <p className="text-sm leading-relaxed text-zinc-400">
                    The complete fixed project: Android app, native
                    import/optimizer core, the engine patch scripts, the
                    relocated 3.6.2 layer, a release keystore and the download
                    website.{" "}
                    <span className="text-zinc-300">
                      Run engine-apk/get-dependencies.sh
                    </span>{" "}
                    to fetch and patch the official runtimes, then{" "}
                    <span className="font-mono text-xs text-zinc-300">
                      gradle assembleRelease
                    </span>
                    .
                  </p>
                  <Button
                    asChild
                    size="lg"
                    variant="outline"
                    className="mt-auto h-14 border-zinc-700 bg-zinc-900 px-8 text-base font-semibold text-zinc-200 hover:bg-zinc-800 hover:text-white"
                  >
                    <a href={SOURCE_URL} download>
                      <Package className="mr-2 h-5 w-5" aria-hidden="true" />
                      Download Source (.zip)
                    </a>
                  </Button>
                  <div className="flex items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-950/70 p-2.5">
                    <Terminal
                      className="h-4 w-4 shrink-0 text-zinc-500"
                      aria-hidden="true"
                    />
                    <p className="min-w-0 flex-1 truncate font-mono text-[11px] text-zinc-500">
                      {APK_BYTES.toLocaleString("en-US")} bytes · versionCode 7
                      · versionName 1.3.2
                    </p>
                  </div>
                </CardContent>
              </Card>
            </div>
          </div>
        </section>

        {/* what's new in 1.3.2 */}
        <section className="border-t border-zinc-800/60 bg-zinc-900/30">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6">
            <div className="flex items-center gap-3">
              <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-500/15">
                <Sparkles
                  className="h-5 w-5 text-violet-300"
                  aria-hidden="true"
                />
              </span>
              <h2 className="text-2xl font-bold tracking-tight">
                New in v1.3.2:{" "}
                <span className="text-violet-400">
                  camera control, a virtual mouse, a PS5-style menu
                </span>
              </h2>
            </div>
            <div className="mt-8 grid grid-cols-1 gap-4 md:grid-cols-3">
              {whatsNew.map((f, i) => (
                <Card key={f.t} className="border-zinc-800 bg-zinc-900/60">
                  <CardContent className="p-6">
                    <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-500/15">
                      {i === 0 ? (
                        <Gamepad2
                          className="h-5 w-5 text-violet-300"
                          aria-hidden="true"
                        />
                      ) : i === 1 ? (
                        <MousePointer2
                          className="h-5 w-5 text-violet-300"
                          aria-hidden="true"
                        />
                      ) : (
                        <Tv
                          className="h-5 w-5 text-violet-300"
                          aria-hidden="true"
                        />
                      )}
                    </span>
                    <h3 className="mt-4 font-semibold">{f.t}</h3>
                    <p className="mt-2 text-sm leading-relaxed text-zinc-400">
                      {f.d}
                    </p>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>
        </section>

        {/* the fix */}
        <section className="border-t border-zinc-800/60 bg-zinc-900/30">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6">
            <div className="flex items-center gap-3">
              <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-500/15">
                <AlertTriangle
                  className="h-5 w-5 text-amber-400"
                  aria-hidden="true"
                />
              </span>
              <h2 className="text-2xl font-bold tracking-tight">
                Fixed in v1.3.1:{" "}
                <span className="text-violet-400">
                  &ldquo;Unable to setup Godot Engine, aborting&rdquo;
                </span>
              </h2>
            </div>
            <p className="mt-4 max-w-3xl leading-relaxed text-zinc-400">
              v1.3.0 still failed on every game because the crash was never in
              the app layer - it is in the engine binary itself. The error
              chain, verified against the official 4.7.2-stable source and the
              prebuilt AAR:
            </p>
            <ol className="mt-6 grid grid-cols-1 gap-3 md:grid-cols-2">
              {errorChain.map((step, i) => (
                <li
                  key={i}
                  className="flex gap-3 rounded-xl border border-zinc-800 bg-zinc-950/60 p-4"
                >
                  <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-violet-500/15 text-sm font-bold text-violet-300">
                    {i + 1}
                  </span>
                  <p className="text-sm leading-relaxed text-zinc-400">{step}</p>
                </li>
              ))}
            </ol>
            <Card className="mt-6 border-violet-500/30 bg-violet-500/5">
              <CardContent className="flex flex-col gap-4 p-6 sm:flex-row sm:items-start">
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-violet-500/15">
                  <Sparkles
                    className="h-5 w-5 text-violet-300"
                    aria-hidden="true"
                  />
                </span>
                <div>
                  <h3 className="font-semibold">The fix</h3>
                  <p className="mt-2 text-sm leading-relaxed text-zinc-400">
                    Exactly the remedy Godot&rsquo;s own error message prescribes
                    (&ldquo;use the{" "}
                    <span className="font-mono text-xs text-zinc-300">
                      disable_path_overrides=no
                    </span>{" "}
                    SCons option&rdquo;), applied to the official prebuilt
                    runtime: in the arm64{" "}
                    <span className="font-mono text-xs text-zinc-300">
                      libgodot_android.so
                    </span>
                    , the assets-only validation compiles to a single{" "}
                    <span className="font-mono text-xs text-zinc-300">
                      cbnz w0, &lt;error&gt;
                    </span>{" "}
                    inside{" "}
                    <span className="font-mono text-xs text-zinc-300">
                      Main::setup
                    </span>{" "}
                    - it becomes a{" "}
                    <span className="font-mono text-xs text-zinc-300">nop</span>
                    . Nothing else is touched; the rejection path is dead code,
                    precisely as if the engine had been compiled with path
                    overrides enabled. The script is{" "}
                    <span className="font-mono text-xs text-zinc-300">
                      engine-apk/tools/patch_godot_lib.py
                    </span>{" "}
                    in the source zip - self-verifying, and it refuses to touch
                    anything if the binary does not match the analyzed layout.
                  </p>
                </div>
              </CardContent>
            </Card>
            <div className="mt-8">
              <h3 className="font-semibold">Also repaired in this build</h3>
              <ul className="mt-4 grid grid-cols-1 gap-2 md:grid-cols-2">
                {alsoFixed.map((f, i) => (
                  <li
                    key={i}
                    className="flex gap-2.5 rounded-lg border border-zinc-800/70 bg-zinc-950/40 p-3.5"
                  >
                    <CircleCheck
                      className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500"
                      aria-hidden="true"
                    />
                    <p className="text-sm leading-relaxed text-zinc-400">{f}</p>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </section>

        {/* install */}
        <section className="mx-auto max-w-6xl px-4 py-14 sm:px-6">
          <h2 className="text-2xl font-bold tracking-tight">
            Install in three steps
          </h2>
          <div className="mt-8 grid grid-cols-1 gap-4 md:grid-cols-3">
            {[
              {
                n: "1",
                t: "Download and install",
                d: "Open the APK on your Android device and allow installs from this source when prompted. Installing over v1.3.0 keeps your imported games.",
              },
              {
                n: "2",
                t: "Import your game",
                d: "Tap Import and pick the game FOLDER. A Windows .exe next to a .pck is detected automatically - so are self-contained .exe, .zip, Godot .apk and .obb files. Folders with several games import them all.",
              },
              {
                n: "3",
                t: "Play",
                d: "Press Play. The floating control overlay (your chosen layout) appears above the game, with a gear handle for live controls and a one-tap Compatibility (GL) rescue if a game stays black.",
              },
            ].map((s) => (
              <Card key={s.n} className="border-zinc-800 bg-zinc-900/60">
                <CardContent className="p-6">
                  <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-violet-500/15 text-sm font-bold text-violet-300">
                    {s.n}
                  </span>
                  <h3 className="mt-4 font-semibold">{s.t}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-zinc-400">
                    {s.d}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>
        </section>

        {/* what's inside */}
        <section className="border-t border-zinc-800/60 bg-zinc-900/30">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6">
            <h2 className="text-2xl font-bold tracking-tight">
              One APK, two engines, an optimizer
            </h2>
            <div className="mt-8 grid grid-cols-1 gap-4 md:grid-cols-3">
              {[
                {
                  icon: Cpu,
                  t: "Embedded runtimes",
                  d: "The official Godot 4.7.2 stable runtime (patched to accept external packs) plus the official 3.6.2 runtime, relocated so both can ship in one arm64 APK. Unity inputs are rejected with a machine-readable reason.",
                },
                {
                  icon: Gamepad2,
                  t: "Three mobile layouts + camera & mouse",
                  d: "Classic Pad, Twin Stick and Gesture Minimal float above any game, live-switchable from the in-game gear - now with an optional camera joystick, a virtual mouse (touchpad cursor + click button that works over every control), and per-game layout overrides.",
                },
                {
                  icon: Sparkles,
                  t: "On-device optimizer",
                  d: "PCK v1-v4 parse, profile patch and byte-exact repack happen entirely on the device. Fidelity keeps authored graphics; Battery adds 0.75 scale + FSR; Compatibility forces GL as the rescue path.",
                },
              ].map((f) => (
                <Card key={f.t} className="border-zinc-800 bg-zinc-900/60">
                  <CardContent className="p-6">
                    <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-500/15">
                      <f.icon
                        className="h-5 w-5 text-violet-300"
                        aria-hidden="true"
                      />
                    </span>
                    <h3 className="mt-4 font-semibold">{f.t}</h3>
                    <p className="mt-2 text-sm leading-relaxed text-zinc-400">
                      {f.d}
                    </p>
                  </CardContent>
                </Card>
              ))}
            </div>
          </div>
        </section>

        {/* matrix */}
        <section className="border-t border-zinc-800/60 bg-zinc-900/30">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6">
            <h2 className="text-2xl font-bold tracking-tight">
              What runs and what does not
            </h2>
            <Card className="mt-8 overflow-x-auto border-zinc-800 bg-zinc-900/60">
              <table className="w-full min-w-[640px] text-left text-sm">
                <thead>
                  <tr className="border-b border-zinc-800 text-zinc-400">
                    <th scope="col" className="p-4 font-medium">
                      Input
                    </th>
                    <th scope="col" className="p-4 font-medium">
                      Verdict
                    </th>
                    <th scope="col" className="p-4 font-medium">
                      Why
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {matrix.map((m) => (
                    <tr
                      key={m.input}
                      className="border-b border-zinc-800/60 last:border-0"
                    >
                      <td className="p-4 font-medium">{m.input}</td>
                      <td className="p-4">
                        <span
                          className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold ${
                            m.ok
                              ? "bg-emerald-500/10 text-emerald-400"
                              : "bg-rose-500/10 text-rose-400"
                          }`}
                        >
                          {m.ok ? (
                            <CircleCheck className="h-3.5 w-3.5" aria-hidden="true" />
                          ) : (
                            <CircleX className="h-3.5 w-3.5" aria-hidden="true" />
                          )}
                          {m.verdict}
                        </span>
                      </td>
                      <td className="p-4 text-zinc-400">{m.why}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
            <p className="mt-6 text-sm leading-relaxed text-zinc-500">
              Your games, your rights: the engine never uploads anything.
              Imports, optimization and storage all happen on the device. The
              embedded Godot runtime is the official prebuilt template,
              MIT-licensed by the Godot project; UI icons are Material Symbols
              (Google, Apache-2.0).
            </p>
          </div>
        </section>
      </main>

      {/* footer */}
      <footer className="mt-auto border-t border-zinc-800/80 bg-zinc-950">
        <div className="mx-auto flex max-w-6xl flex-col gap-3 px-4 py-8 text-sm text-zinc-500 sm:flex-row sm:items-center sm:justify-between sm:px-6">
          <div>
            <p>
              NativeMorph Engine 1.3.2 · Godot 4.7.2 + 3.6.2 runtimes (MIT) ·
              Material Symbols (Apache-2.0)
            </p>
            <Separator className="my-3 sm:hidden" />
            <p className="font-mono text-xs">
              APK SHA-256 {APK_SHA256.slice(0, 24)}…
            </p>
          </div>
          <Badge
            variant="secondary"
            className="w-fit border-emerald-500/30 bg-emerald-500/10 text-emerald-400"
          >
            Launch bug fixed · controls & PS5 UI · v1.3.2
          </Badge>
        </div>
      </footer>
    </div>
  );
}
