#!/usr/bin/env python3
"""3.x ECFG end-to-end proof: rebuild the bootable task-4 probe v1 pack with
project.binary (3.x ECFG) instead of text project.godot, let the import core
convert it, then boot the result in the REAL Godot 3.6.2 headless runtime.
Success = the probe script prints NMPROBE_OK (settings parsed + scene ran +
all payload reads intact)."""
import hashlib
import os
import struct
import subprocess
import sys

SRC = "/tmp/nmtest/probe_v1.pck"
DRIVER = "/tmp/nmbin/host_driver"
G3 = "/tmp/ohtest/Godot_v3.6.2-stable_linux_headless.64"
OUT = "/tmp/nmg3"

def md5(b):
    return hashlib.md5(b).digest()

def read_v1(path):
    buf = open(path, "rb").read()
    assert struct.unpack_from("<I", buf, 4)[0] == 1
    count = struct.unpack_from("<I", buf, 84)[0]
    off = 88
    entries = []
    for _ in range(count):
        plen = struct.unpack_from("<I", buf, off)[0]; off += 4
        p = buf[off:off + plen].rstrip(b"\0").decode()
        p = p[6:] if p.startswith("res://") else p
        off += plen
        abs_off, size = struct.unpack_from("<QQ", buf, off); off += 16
        off += 16  # md5
        entries.append((p, buf[abs_off:abs_off + size]))
    return entries

# ---- 3.x variant encoders --------------------------------------------------
def v_str(s):
    b = s.encode()
    pad = (4 - len(b) % 4) % 4
    return struct.pack("<I", 4) + struct.pack("<I", len(b)) + b + b"\0" * pad

def v_int(i):
    return struct.pack("<I", 2) + struct.pack("<i", i)

def v_bool(b):
    return struct.pack("<I", 1) + struct.pack("<I", 1 if b else 0)

def v_pool_strings(items):
    out = struct.pack("<I", 23) + struct.pack("<I", len(items))
    for s in items:
        b = s.encode()
        pad = (4 - len(b) % 4) % 4
        out += struct.pack("<I", len(b)) + b + b"\0" * pad
    return out

def build_ecfg(entries):
    out = b"ECFG" + struct.pack("<I", len(entries))
    for key, val in entries:
        kb = key.encode()
        out += struct.pack("<I", len(kb)) + kb + struct.pack("<I", len(val)) + val
    return out

def build_v1(entries, engine=(3, 6, 0)):
    head = struct.pack("<IIIII", 0x43504447, 1, *engine) + b"\0" * 64
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

def main():
    os.makedirs(OUT, exist_ok=True)
    entries = read_v1(SRC)
    print("source entries:", [p for p, _ in entries])

    # same settings as the probe's text project.godot, compiled to 3.x ECFG
    ecfg = build_ecfg([
        ("application/config/name", v_str("NM Probe 3")),
        ("application/run/main_scene", v_str("res://main.tscn")),
        ("application/config/features", v_pool_strings(["3.6"])),
        ("rendering/quality/driver/fallback_to_gles2", v_bool(False)),
        ("display/window/size/test_width", v_int(1280)),
        ("display/window/size/test_height", v_int(720)),
    ])
    bin_entries = [("project.binary", ecfg)] + [(p, d) for p, d in entries
                                                if p != "project.godot"]
    pack = f"{OUT}/probe_v1_binary.pck"
    open(pack, "wb").write(build_v1(bin_entries))

    # import (ECFG -> text) with the SAME core the APK uses
    work = f"{OUT}/work"
    os.makedirs(work, exist_ok=True)
    r = subprocess.run([DRIVER, pack, work, '{"renderer":"keep"}'],
                        capture_output=True, text=True)
    print("import:", r.stdout.strip())
    ok = '"ok":true' in r.stdout and '"binary-converted"' in r.stdout
    if not ok:
        print("IMPORT FAILED"); sys.exit(1)

    # boot in the REAL 3.6.2 runtime: the probe quits itself after printing
    r = subprocess.run([G3, "--main-pack", f"{work}/game.pck", "--no-window"],
                       capture_output=True, text=True, timeout=120)
    out = (r.stdout + r.stderr)
    print("boot output:")
    for line in out.splitlines():
        if line.strip():
            print("  |", line.strip()[:120])
    if "NMPROBE_OK" in out:
        print("3.X ECFG -> TEXT CONVERSION VERIFIED IN THE REAL 3.6.2 RUNTIME")
        sys.exit(0)
    print("BOOT DID NOT REACH THE PROBE"); sys.exit(1)

if __name__ == "__main__":
    main()
