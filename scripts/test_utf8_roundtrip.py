#!/usr/bin/env python3
"""UTF-8 regression test for the nativemorph pack core.

Reproduces the user's "non-printable path in directory" rejection:
real Godot games contain UTF-8 asset paths (CJK, Cyrillic, accents) and
may carry an emoji project name. This script:
  1. generates v2 (unpadded) and v3 (padded) packs full of UTF-8 paths,
  2. runs the host driver (same core as the on-device JNI import),
  3. verifies the patched project.godot keeps the authored renderer,
  4. verifies every other entry roundtrips byte-exact,
  5. verifies the game icon was extracted.

Usage: python3 test_utf8_roundtrip.py
"""
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys

ROOT = "/home/z/my-project"
NATIVE = f"{ROOT}/nativemorph/native"
OUT = "/tmp/nmutf8"

MAGIC = 0x43504447  # LE u32 of "GDPC"
REL_FILEBASE = 1 << 1

def make_png():
    """Minimal valid 1x1 purple PNG."""
    import zlib
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    idat = zlib.compress(b"\x00\x6c\x4d\xff")
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", idat) + chunk(b"IEND", b""))


PNG = make_png()

PG = (
    "; Engine configuration file.\n"
    "config_version=5\n"
    "\n"
    "[application]\n"
    "\n"
    'config/name="Test \u00dcnn\u00efcode \u6771\u4eac \u30b2\u30fc\u30e0 \U0001f3ae"\n'
    'run/main_scene="res://main.tscn"\n'
    'config/icon="res://icon.png"\n'
    "\n"
    "[rendering]\n"
    "\n"
    'renderer/rendering_method="forward_plus"\n'
    "anti_aliasing/quality/msaa_3d=4\n"
).encode()

# The UTF-8 torture set: CJK, Cyrillic, accented Latin, Arabic paths.
FILES = {
    "project.godot": PG,
    "main.tscn": b"[gd_scene format=3]\n",
    "icon.png": PNG,
    "assets/\u6d4b\u8bd5/\u7eb9\u7406.png": b"\x89PNG-fake-texdata" + b"\x01" * 64,
    "\u043c\u0443\u0437\u044b\u043a\u0430/\u0442\u0440\u0435\u043a_01.ogg": b"OggS" + b"\x02" * 96,
    "caf\u00e9/h\u00e9ros.wav": b"RIFF" + b"\x03" * 80,
    "\u0627\u0644\u0639\u0631\u0628\u064a\u0629/data.bin": b"\x04" * 48,
}


def md5(b):
    return hashlib.md5(b).digest()


def align(n, a):
    return (a - (n % a)) % a


def write_entry(out, path, rel_off, size, digest):
    pad = (4 - (len(path) % 4)) % 4
    out += struct.pack("<I", len(path) + pad)
    out += path + b"\0" * pad
    out += struct.pack("<QQ", rel_off, size)
    out += digest
    out += struct.pack("<I", 0)  # flags
    return out


def make_v2(fmt, padded):
    """Mirrors the verified make_test_packs.make_v2plus layout."""
    head = struct.pack("<IIIII", MAGIC, fmt, 4, 3 if fmt == 2 else 7, 2)
    head += struct.pack("<I", REL_FILEBASE)   # pack_flags @20
    head += struct.pack("<Q", 0)              # file_base @24 (patched)
    if fmt >= 3:
        head += struct.pack("<Q", 0)          # dir_offset @32 (patched)
        head += b"\0" * 64                    # reserved/salt region
        while len(head) % 16:
            head += b"\0"
    else:
        head += b"\0" * 64                    # 16 reserved words
        head += struct.pack("<I", len(FILES))  # count right after

    def dir_entry_size(p):
        pad = (4 - (len(p.encode()) % 4)) % 4 if padded else 0
        return 4 + len(p.encode()) + pad + 8 + 8 + 16 + 4

    if fmt <= 2:
        dirsz = sum(dir_entry_size(p) for p in FILES)
        payload_base = len(head) + dirsz
    else:
        payload_base = len(head)

    body = b""
    offs = {}
    for p, data in FILES.items():
        offs[p] = payload_base + len(body)
        body += data
        if fmt >= 3:
            body += b"\0" * align(len(body), 16)
    if fmt >= 3:
        body += b"\0" * align(len(body), 16)

    d = b""
    if fmt >= 3:
        d += struct.pack("<I", len(FILES))    # count lives at dir_offset
    for p, data in FILES.items():
        raw = p.encode()
        if padded:
            d = write_entry(d, raw, offs[p] - payload_base, len(data), md5(data))
        else:
            d += struct.pack("<I", len(raw)) + raw
            d += struct.pack("<QQ", offs[p] - payload_base, len(data))
            d += md5(data) + struct.pack("<I", 0)

    head = head[:24] + struct.pack("<Q", payload_base) + head[32:]
    if fmt >= 3:
        dir_off = payload_base + len(body)
        head = head[:32] + struct.pack("<Q", dir_off) + head[40:]
        return head + body + d
    return head + d + body


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    packs = {}
    for fmt, padded in ((2, False), (3, True)):
        data = make_v2(fmt, padded)
        path = f"{OUT}/v{fmt}.pck"
        with open(path, "wb") as f:
            f.write(data)
        packs[fmt] = path

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

    failures = []
    for fmt, path in packs.items():
        work = f"{OUT}/work_v{fmt}"
        os.makedirs(work)
        run = subprocess.run([driver, path, work, '{"renderer":"keep"}'],
                             capture_output=True, text=True)
        try:
            rep = json.loads(run.stdout.strip())
        except Exception:
            print(f"v{fmt}: driver did not emit JSON: {run.stdout!r} err={run.stderr!r}")
            failures.append(f"v{fmt} json")
            continue
        if not rep.get("ok"):
            failures.append(f"v{fmt} import failed: {rep.get('error')}")
            continue
        print(f"v{fmt}: ok, {rep['files']} files, icon={rep.get('icon')!r}, "
              f"renderer={rep.get('renderer')!r}, name={rep.get('name')!r}")

        patched = open(f"{work}/patched_project.godot", encoding="utf-8").read()
        if 'rendering_method="forward_plus"' not in patched:
            failures.append(f"v{fmt} renderer was not kept as authored")
        if 'rendering_method="mobile"' in patched or \
           'rendering_method="gl_compatibility"' in patched:
            failures.append(f"v{fmt} renderer override leaked into keep profile")
        if "scaling_3d/scale" in patched:
            failures.append(f"v{fmt} scale key forced on keep profile")
        if rep.get("icon") != "icon.png" or not os.path.exists(f"{work}/icon.png"):
            failures.append(f"v{fmt} icon not extracted")
        if open(f"{work}/icon.png", "rb").read() != PNG:
            failures.append(f"v{fmt} extracted icon bytes differ")

        chk = subprocess.run([driver, "--verify", path, f"{work}/game.pck"],
                             capture_output=True, text=True)
        try:
            v = json.loads(chk.stdout)
        except Exception:
            v = {"ok": False, "error": chk.stdout + chk.stderr}
        if not v.get("ok"):
            failures.append(f"v{fmt} roundtrip: {v.get('error')}")
        else:
            print(f"v{fmt}: roundtrip {v['matched']}/{v['total']} entries byte-exact")
            if v["matched"] != v["total"]:
                failures.append(f"v{fmt} roundtrip mismatch")

    print()
    if failures:
        print("FAILURES:")
        for f_ in failures:
            print(" -", f_)
        sys.exit(1)
    print("ALL UTF-8 ROUNDTRIP TESTS PASSED")


if __name__ == "__main__":
    main()
