<div align="center">

# NativeMorph Engine

**Run your Godot PC games natively on Android — one engine APK.**
**No emulation. No streaming. No uploads.**

[![Release](https://img.shields.io/badge/release-v0.0.1-8b5cf6)](https://github.com/maxi000q/NativeMorph/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20(arm64)-3ddc84)](https://github.com/maxi000q/NativeMorph)
[![Discord](https://img.shields.io/badge/Discord-join%20us-5865F2)](https://discord.gg/NQgP9JxtU3)

</div>

---

## 📖 About

**NativeMorph** is a free, open Android app that lets you run **your own Godot
games** — built for PC — natively on your phone or tablet. It is *not* an
emulator and *not* a streaming client: the actual Godot engine runs on your
device, and your game files never leave it.

- **Two embedded official runtimes** — Godot **4.7.2 stable** and Godot
  **3.6.2 stable** (both MIT-licensed by the Godot project), packed into a
  single APK.
- **Import your own files** — `.pck` packs, `.zip` bundles, Windows
  `.exe + .pck` pairs, and Godot `.apk` / `.obb` exports. Import, optimization
  and storage happen **100% on your device** — nothing is ever uploaded
  anywhere.
- **On-device optimizer** — packs are parsed, profiled (Fidelity / Battery
  Saver / Compatibility) and repacked locally, byte-exact.
- **Floating touch controls** — three overlay layouts (Classic Pad, Twin
  Stick, Gesture Minimal) above any game, switchable live while playing.
- **Console-style library UI** — a PS5-inspired game carousel with per-game
  settings, optimization reports and game icons extracted straight from your
  packs.

---

## ⚠️ Some games will NOT work

NativeMorph is an independent hobby project, **not** a commercial product, and
game compatibility is **not guaranteed**. Please read this before you try it:

- **Some games simply won't run.** Games may fail to import, crash at launch,
  show a black screen, or misbehave (broken input, missing audio, graphical
  glitches). This is expected — every game is different.
- **Unity games are rejected** — Unity desktop builds contain x86/x86-64
  engine code that cannot run natively on ARM. Only Godot games are supported.
- **GDExtension native modules** compiled only for desktop platforms (Windows
  / Linux / macOS) will not load on Android.
- **Encrypted packs are not supported.**
- **Very large packs** (over ~1.6 GB) are refused on-device.
- **arm64-v8a only** (64-bit Android, minSdk 24 / Android 7.0+). 32-bit
  devices are not supported.

> If a game doesn't work, it doesn't. There is no fix on the roadmap for every
> title — use the engine with games you can actually test, and report what you
> find on Discord.

---

## ⚖️ Legal disclaimer — please read

> **This must be said clearly and loudly:**

- **No affiliation whatsoever.** NativeMorph is an independent personal
  project. It is **NOT affiliated with, endorsed by, sponsored by, or related
  to** the Godot project, Google, or **any** game developer, publisher or
  platform. "Godot" is a trademark of its respective owners.
- **Nothing pirated.** This repository and the app contain **NO games, NO
  ROMs, NO copyrighted content of any kind**. NativeMorph ships as an empty
  engine/player only. It distributes nothing but its own code.
- **We do not invite or encourage anyone.** The developer does **not** invite,
  encourage, condone or endorse the use of NativeMorph with any files the user
  does not own or has no legal right to use. **Whoever downloads, installs or
  uses this app does so entirely by their own decision and carries full
  responsibility for what they do with it.**
- **Strong liability waiver.** THE SOFTWARE IS PROVIDED "AS IS", WITHOUT
  WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
  WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
  NON-INFRINGEMENT. IN NO EVENT SHALL THE DEVELOPER BE LIABLE FOR ANY CLAIM,
  DAMAGES, DATA LOSS, DEVICE DAMAGE, OR OTHER LIABILITY, WHETHER IN AN ACTION
  OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
  THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
- **By downloading, installing or using NativeMorph you accept this disclaimer
  in full. If you do not agree, do not use it.**

---

## 💬 Discord

Questions, compatibility reports, updates and help:

<div align="center">

### **[Join the NativeMorph Discord](https://discord.gg/NQgP9JxtU3)**

`https://discord.gg/NQgP9JxtU3`

</div>
