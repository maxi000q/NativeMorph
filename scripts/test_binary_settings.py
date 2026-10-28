#!/usr/bin/env python3
"""Binary project settings (project.binary / ECFG) regression suite.

Godot exports with the default "Convert Text Resources to Binary" option
store compiled project.binary instead of text project.godot. The import core
must:
  1. decode ECFG (both the 4.x and 3.x variant tables) and convert it to
     patchable text (settings=binary-converted), dropping project.binary and
     adding project.godot,
  2. fail SAFE when a value uses an unsupported variant type
     (settings=binary-kept: pack repacked untouched, game still imports),
  3. reject packs with no settings entry at all.

Cases:
  v4 + real OPEN HOUSE project.binary   (if the real game was downloaded)
  v4 + synthetic 4.x ECFG               (strings/ints/bools/floats/arrays...)
  v1 + synthetic 3.x ECFG               (POOL_* arrays, 3.x type table)
  v4 + ECFG with an OBJECT value        (fail-safe -> kept)
  v2 + no settings entry                (actionable error)
"""
import json
import os
import shutil
import struct
import subprocess
import sys

ROOT = "/home/z/my-project"
NATIVE = f"{ROOT}/nativemorph/native"
OUT = "/tmp/nmbin"
REAL_GAME = "/tmp/ohtest/game/LINUX/OPEN HOUSE.pck"
REAL_BIN = "/tmp/ohtest/project.binary"
PNG = (b"\x89PNG\r\n\x1a\n" + bytes(range(60)))  # stand-in raster icon

# ---- variant encoders (mirror core/io/marshalls.cpp writers) -------------

def v_str(s, gen=4):
    b = s.encode() if isinstance(s, str) else s
    pad = (4 - len(b) % 4) % 4
    return struct.pack("<I", 4 if gen == 4 else 4) + struct.pack("<I", len(b)) + b + b"\0" * pad

def v_int(i):
    return struct.pack("<I", 2) + struct.pack("<i", i)

def v_bool(b):
    return struct.pack("<I", 1) + struct.pack("<I", 1 if b else 0)

def v_float(f):
    return struct.pack("<I", 3) + struct.pack("<f", f)

def v_packed_strings(gen, items):
    t = 34 if gen == 4 else 23  # PACKED_STRING_ARRAY / POOL_STRING_ARRAY
    out = struct.pack("<I", t) + struct.pack("<I", len(items))
    for s in items:
        b = s.encode()
        pad = (4 - len(b) % 4) % 4
        out += struct.pack("<I", len(b)) + b + b"\0" * pad
    return out

def v_color(gen, rgba):
    t = 20 if gen == 4 else 14
    return struct.pack("<I", t) + struct.pack("<4f", *rgba)

def v_dict4(pairs):
    out = struct.pack("<I", 27) + struct.pack("<I", len(pairs))
    for k, v in pairs:
        out += v_str(k) + v_int(v)
    return out

def v_array4(items):
    out = struct.pack("<I", 28) + struct.pack("<I", len(items))
    for it in items:
        out += v_int(it)
    return out

def v_object24():
    # OBJECT with OBJECT_AS_ID flag: unsupported by the converter on purpose
    return struct.pack("<I", 24 | (1 << 16)) + struct.pack("<Q", 12345)

def build_ecfg(entries):
    """entries: [(key, encoded_variant_bytes)] -> ECFG blob"""
    out = b"ECFG" + struct.pack("<I", len(entries))
    for key, val in entries:
        kb = key.encode()
        out += struct.pack("<I", len(kb)) + kb   # keys: NO padding
        out += struct.pack("<I", len(val)) + val  # framed value
    return out

# ---- pack builders --------------------------------------------------------

def md5(data):
    import hashlib
    return hashlib.md5(data).digest()

def build_v2(fmt, entries, padded=True, engine=(4, 4, 1)):
    """entries: [(path, payload)] ; formats 2/3/4, dir at offset like engine."""
    head = struct.pack("<IIIII", 0x43504447, fmt, *engine)
    head += struct.pack("<I", 2)              # pack_flags: REL_FILEBASE
    head += struct.pack("<Q", 0)              # file_base placeholder @24
    if fmt >= 3:
        head += struct.pack("<Q", 0)          # dir_offset placeholder @32
        head += b"\0" * 32                    # reserved/salt
        while len(head) % 16:
            head += b"\0"
    else:
        head += b"\0" * 64                    # 16 reserved words
    file_base = len(head)
    head = head[:24] + struct.pack("<Q", file_base) + head[32:]
    payload = b""
    dirents = []
    for path, data in entries:
        off = len(payload)
        payload += data
        if fmt >= 3:
            while len(payload) % 16:
                payload += b"\0"
        pb = path.encode()
        if padded:
            pb += b"\0" * ((4 - len(pb) % 4) % 4)
        dirent = struct.pack("<I", len(pb)) + pb + struct.pack("<QQ", off, len(data)) + md5(data)
        if fmt >= 2:
            dirent += struct.pack("<I", 0)
        dirents.append(dirent)
    if fmt >= 3:
        # v3/v4: payload right after the header, directory at the end
        body = head + payload
        dir_off = len(body)
        blob = body + struct.pack("<I", len(entries)) + b"".join(dirents)
        blob = blob[:32] + struct.pack("<Q", dir_off) + blob[40:]
    else:
        # v2: directory right after the 96-byte header, payload after it
        dir_size = 4 + sum(len(d) for d in dirents)
        file_base = 96 + dir_size
        head = head[:24] + struct.pack("<Q", file_base) + head[32:]
        blob = head + struct.pack("<I", len(entries)) + b"".join(dirents) + payload
    return blob

def build_v1(entries, engine=(3, 6, 0)):
    head = struct.pack("<IIIII", 0x43504447, 1, *engine) + b"\0" * 64
    dirents = b""
    payload = b""
    for path, data in entries:
        off = len(head) + 4 + len(dirents)  # computed below after all entries
        pb = ("res://" + path).encode()
        pb += b"\0" * ((4 - len(pb) % 4) % 4)
        dirents += struct.pack("<I", len(pb)) + pb  # placeholder, fixed after
        payload += data
    # two passes: build with real offsets
    dirents = b""
    payload = b""
    base = len(head) + 4 + sum(
        4 + len(("res://" + p).encode()) + (4 - len(("res://" + p).encode()) % 4) % 4 + 8 + 8 + 16
        for p, _ in entries)
    for path, data in entries:
        off = base + len(payload)
        pb = ("res://" + path).encode()
        pb += b"\0" * ((4 - len(pb) % 4) % 4)
        dirents += (struct.pack("<I", len(pb)) + pb +
                    struct.pack("<QQ", off, len(data)) + md5(data))
        payload += data
    return head + struct.pack("<I", len(entries)) + dirents + payload

# ---- harness --------------------------------------------------------------

def build_driver():
    driver = f"{OUT}/host_driver"
    build = subprocess.run(
        ["g++", "-std=c++17", "-O1", "-w",
         "-I", f"{NATIVE}/include", "-I", f"{ROOT}/scripts/stubs",
         f"{ROOT}/scripts/host_driver.cpp",
         f"{NATIVE}/src/pck.cpp", f"{NATIVE}/src/patch.cpp",
         f"{NATIVE}/src/ecfg.cpp",
         f"{ROOT}/scripts/stubs/zip_stub.c",
         "-o", driver],
        capture_output=True, text=True)
    if build.returncode != 0:
        print("BUILD FAILED:\n" + build.stderr)
        sys.exit(1)
    return driver

def run_driver(driver, pack, work, opts='{"renderer":"keep"}'):
    os.makedirs(work, exist_ok=True)
    r = subprocess.run([driver, pack, work, opts], capture_output=True, text=True)
    try:
        return json.loads(r.stdout.strip())
    except Exception:
        return {"ok": False, "error": f"no json: {r.stdout!r} {r.stderr!r}"}

def verify(driver, a, b, mode="converted"):
    r = subprocess.run([driver, "--verify", a, b, mode], capture_output=True, text=True)
    try:
        return json.loads(r.stdout.strip())
    except Exception:
        return {"ok": False, "error": r.stdout + r.stderr}

def entries_of(path):
    """Minimal directory reader for assertions."""
    import struct as st
    buf = open(path, "rb").read()
    fmt = st.unpack_from("<I", buf, 4)[0]
    base = 0
    if st.unpack_from("<I", buf, 0)[0] == 0x43504447:
        base = 0
    dir_off = st.unpack_from("<Q", buf, 32)[0] if fmt >= 3 else (84 if fmt == 1 else 96)
    count = st.unpack_from("<I", buf, dir_off)[0]
    off = dir_off + 4
    out = []
    for _ in range(count):
        plen = st.unpack_from("<I", buf, off)[0]; off += 4
        p = buf[off:off + plen].rstrip(b"\0").decode(); off += plen
        off += 8 + 8 + 16
        if fmt >= 2:
            off += 4
        out.append(p)
    return out

failures = []

def check(cond, what):
    print(("  PASS " if cond else "  FAIL ") + what)
    if not cond:
        failures.append(what)

def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    driver = build_driver()

    # ---- case 1: real OPEN HOUSE (4.7, format 4) --------------------------
    if os.path.exists(REAL_GAME):
        print("case: real OPEN HOUSE pack (Godot 4.7.2, fmt 4, project.binary)")
        work = f"{OUT}/real"
        rep = run_driver(driver, REAL_GAME, work)
        check(rep.get("ok"), f"import ok ({rep.get('error')})")
        check(rep.get("settings") == "binary-converted", "settings=binary-converted")
        check(rep.get("name") == "OPEN HOUSE", f"name={rep.get('name')!r}")
        check(rep.get("icon") == "icon.png", "icon extracted")
        v = verify(driver, REAL_GAME, f"{work}/game.pck")
        check(v.get("ok"), f"roundtrip {v.get('matched')}/{v.get('total')}")
        names = entries_of(f"{work}/game.pck")
        check("project.binary" not in names and "project.godot" in names,
              "binary removed, text added")
        txt = open(f"{work}/patched_project.godot", encoding="utf-8").read()
        check('config/name="OPEN HOUSE"' in txt and "config_version=5" in txt,
              "converted text has name + config_version")
        check('run/main_scene="res://main.tscn"' in txt, "main scene survived")
    else:
        print("case: real OPEN HOUSE pack - SKIPPED (game not downloaded)")

    # ---- case 2: synthetic 4.x ECFG, format 4 -----------------------------
    print("case: synthetic v4 pack + 4.x ECFG")
    ecfg = build_ecfg([
        ("application/config/name", v_str("Blüme · 測試 \"quoted\"")),
        ("application/config/description", v_str("synthetic 4.x settings")),
        ("application/run/main_scene", v_str("res://main.tscn")),
        ("application/config/features", v_packed_strings(4, ["4.4", "GL Compatibility"])),
        ("application/config/icon", v_str("res://icon.png")),
        ("display/window/size/viewport_width", v_int(1152)),
        ("display/window/size/viewport_height", v_int(648)),
        ("rendering/environment/defaults/default_clear_color", v_color(4, (0.1, 0.2, 0.3, 1.0))),
        ("my_game/flags", v_dict4([("a", 1), ("b", 2)])),
        ("my_game/levels", v_array4([1, 2, 3])),
        ("my_game/enabled", v_bool(True)),
        ("my_game/volume", v_float(0.35)),
    ])
    pack = f"{OUT}/synth4.pck"
    with open(pack, "wb") as f:
        f.write(build_v2(4, [
            ("project.binary", ecfg),
            ("main.tscn", b"[node]\n"),
            ("icon.png", PNG),
        ]))
    work = f"{OUT}/synth4_work"
    rep = run_driver(driver, pack, work)
    check(rep.get("ok"), f"import ok ({rep.get('error')})")
    check(rep.get("settings") == "binary-converted", "settings=binary-converted")
    check(rep.get("name") == "Blüme · 測試 \"quoted\"", f"utf8 name={rep.get('name')!r}")
    check(rep.get("icon") == "icon.png", "icon extracted")
    v = verify(driver, pack, f"{work}/game.pck")
    check(v.get("ok"), f"roundtrip {v.get('matched')}/{v.get('total')}")
    names = entries_of(f"{work}/game.pck")
    check("project.binary" not in names and "project.godot" in names,
          "binary removed, text added")
    txt = open(f"{work}/patched_project.godot", encoding="utf-8").read()
    check('config/name="Blüme · 測試 \\"quoted\\""' in txt, "name escaped in text")
    check('PackedStringArray("4.4", "GL Compatibility")' in txt, "features rendered")
    check("Color(0." in txt and "1.0)" in txt, "color rendered")
    check("{\n" not in txt and '"a": 1' in txt, "dict rendered")

    # ---- case 3: synthetic 3.x ECFG, format 1 -----------------------------
    print("case: synthetic v1 pack + 3.x ECFG")
    ecfg3 = build_ecfg([
        ("application/config/name", v_str("Three Point Six")),
        ("application/run/main_scene", v_str("res://main.tscn")),
        ("application/config/features", v_packed_strings(3, ["3.6"])),
        ("application/config/icon", v_str("res://icon.png")),
        ("display/window/size/width", v_int(1280)),
        ("rendering/quality/filters/msaa", v_int(2)),
        ("my_game/pool", v_packed_strings(3, ["x", "y"])),
        ("my_game/tint", v_color(3, (0.5, 0.5, 0.5, 1.0))),
    ])
    pack3 = f"{OUT}/synth3.pck"
    with open(pack3, "wb") as f:
        f.write(build_v1([
            ("project.binary", ecfg3),
            ("main.tscn", b"[node]\n"),
            ("icon.png", PNG),
        ]))
    work3 = f"{OUT}/synth3_work"
    rep = run_driver(driver, pack3, work3)
    check(rep.get("ok"), f"import ok ({rep.get('error')})")
    check(rep.get("settings") == "binary-converted", "settings=binary-converted")
    check(rep.get("name") == "Three Point Six", f"name={rep.get('name')!r}")
    v = verify(driver, pack3, f"{work3}/game.pck")
    check(v.get("ok"), f"roundtrip {v.get('matched')}/{v.get('total')}")
    txt = open(f"{work3}/patched_project.godot", encoding="utf-8").read()
    check("config_version=4" in txt, "3.x config_version=4")
    check('PoolStringArray("3.6")' in txt, "3.x pool strings rendered")
    # 3.x profile key present (fallback_to_gles2 robustness patch)
    check("fallback_to_gles2" in txt, "3.x profile applied to converted text")

    # ---- case 4: fail-safe (OBJECT value -> binary kept) ------------------
    print("case: v4 pack + ECFG with OBJECT value (fail-safe)")
    ecfg_bad = build_ecfg([
        ("application/config/name", v_str("Unconvertible")),
        ("application/config/icon", v_str("res://icon.png")),
        ("weird/thing", v_object24()),
    ])
    pack4 = f"{OUT}/synthbad.pck"
    with open(pack4, "wb") as f:
        f.write(build_v2(4, [
            ("project.binary", ecfg_bad),
            ("icon.png", PNG),
        ]))
    work4 = f"{OUT}/synthbad_work"
    rep = run_driver(driver, pack4, work4)
    check(rep.get("ok"), f"import still ok ({rep.get('error')})")
    check(rep.get("settings") == "binary-kept", "settings=binary-kept")
    check(rep.get("name") == "Unconvertible", "name recovered leniently")
    v = verify(driver, pack4, f"{work4}/game.pck", "kept")
    check(v.get("ok"), f"kept roundtrip {v.get('matched')}/{v.get('total')}")
    names = entries_of(f"{work4}/game.pck")
    check("project.binary" in names and "project.godot" not in names,
          "binary kept byte-exact, no text added")

    # ---- case 5: no settings at all -> actionable error -------------------
    print("case: v2 pack without any settings entry")
    pack5 = f"{OUT}/nosettings.pck"
    with open(pack5, "wb") as f:
        f.write(build_v2(2, [("main.tscn", b"[node]\n")]))
    rep = run_driver(driver, pack5, f"{OUT}/nosettings_work")
    check(not rep.get("ok"), "import rejected")
    check("project.godot" in rep.get("error", "") and
          "project.binary" in rep.get("error", ""),
          f"actionable error: {rep.get('error')!r}")

    # ---- case 6: text pack regression -------------------------------------
    print("case: text project.godot pack still works")
    pack6 = f"{OUT}/textpack.pck"
    pg = (b'config_version=5\n\n[application]\nconfig/name="Texty"\n'
          b'run/main_scene="res://main.tscn"\n\n[rendering]\n'
          b'renderer/rendering_method="forward_plus"\n')
    with open(pack6, "wb") as f:
        f.write(build_v2(2, [("project.godot", pg), ("main.tscn", b"[node]\n"),
                             ("icon.png", PNG)]))
    work6 = f"{OUT}/text_work"
    rep = run_driver(driver, pack6, work6)
    check(rep.get("ok"), f"import ok ({rep.get('error')})")
    check(rep.get("settings") == "text", "settings=text")
    check(rep.get("name") == "Texty", "name from text")
    v = verify(driver, pack6, f"{work6}/game.pck", "text")
    check(v.get("ok"), f"roundtrip {v.get('matched')}/{v.get('total')}")
    txt = open(f"{work6}/patched_project.godot", encoding="utf-8").read()
    check('rendering_method="forward_plus"' in txt, "keep profile preserved renderer")

    print()
    if failures:
        print(f"FAILURES ({len(failures)}):")
        for f_ in failures:
            print(" -", f_)
        sys.exit(1)
    print("ALL BINARY-SETTINGS TESTS PASSED")

if __name__ == "__main__":
    main()
