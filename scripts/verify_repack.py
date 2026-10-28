#!/usr/bin/env python3
"""Verify nativemorph repacks: parse original + repacked, compare every
untouched payload byte-exact, check stored MD5s, check the project.godot
patch, and re-validate the container structure per the engine spec."""
import hashlib
import struct
import sys

MAGIC = 0x43504447


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def parse(path):
    d = open(path, "rb").read()
    base = 0
    if u32(d, 0) != MAGIC:
        # embedded
        assert d[-4:] == b"GDPC", "no magic"
        sz = u64(d, len(d) - 12)
        base = len(d) - 12 - sz
        assert u32(d, base) == MAGIC
    fmt = u32(d, base + 4)
    vmaj = u32(d, base + 8)
    files = {}
    if fmt == 1:
        assert base + 84 + 4 <= len(d)
        count = u32(d, base + 84)
        off = base + 88
        for _ in range(count):
            plen = u32(d, off); off += 4
            p = d[off:off + plen].rstrip(b"\0").decode()
            p = p[6:] if p.startswith("res://") else p
            off += plen
            abs_off = u64(d, off); off += 8
            size = u64(d, off); off += 8
            md5 = d[off:off + 16]; off += 16
            files[p] = (abs_off, size, md5)
    else:
        flags = u32(d, base + 20)
        fb = u64(d, base + 24)
        fb = fb + base if (fmt >= 3 or (flags & 2)) else fb
        if fmt >= 3:
            dir_off = base + u64(d, base + 32)
        else:
            dir_off = base + 96
        count = u32(d, dir_off)
        off = dir_off + 4
        for _ in range(count):
            plen = u32(d, off); off += 4
            p = d[off:off + plen].rstrip(b"\0").decode()
            p = p[6:] if p.startswith("res://") else p
            off += plen
            rel = u64(d, off); off += 8
            size = u64(d, off); off += 8
            md5 = d[off:off + 16]; off += 16
            eflags = u32(d, off); off += 4
            files[p] = (fb + rel, size, md5)
    return d, fmt, vmaj, files


def main(orig_path, new_path, engine):
    d1, fmt1, vmaj1, f1 = parse(orig_path)
    d2, fmt2, vmaj2, f2 = parse(new_path)
    assert fmt1 == fmt2, f"format changed {fmt1}->{fmt2}"
    assert vmaj1 == vmaj2, f"engine version changed"
    assert set(f1) == set(f2), f"file set changed: {set(f1) ^ set(f2)}"

    fails = 0
    for p in sorted(f1):
        o1, s1, m1 = f1[p]
        o2, s2, m2 = f2[p]
        data2 = d2[o2:o2 + s2]
        if p == "project.godot":
            # patched: md5 must match new content
            if hashlib.md5(data2).digest() != m2:
                print(f"  [FAIL] {p}: stored md5 mismatch")
                fails += 1
            txt = data2.decode()
            if engine == 4:
                for needle in ('renderer/rendering_method="mobile"',
                              'scaling_3d/scale=1'):
                    if needle not in txt:
                        print(f"  [FAIL] {p}: missing '{needle}'")
                        fails += 1
            else:
                if "quality/driver/fallback_to_gles2=true" not in txt:
                    print(f"  [FAIL] {p}: missing 3.x profile key")
                    fails += 1
                if "config/name=\"NM Probe 3\"" not in txt:
                    print(f"  [FAIL] {p}: name lost")
                    fails += 1
            continue
        # untouched: payload byte-exact
        data1 = d1[o1:o1 + s1]
        if data1 != data2:
            print(f"  [FAIL] {p}: payload differs")
            fails += 1
        elif hashlib.md5(data1).digest() != m1 or m1 != m2:
            print(f"  [FAIL] {p}: md5 mismatch (orig-stored consistency)")
            fails += 1
    print(f"  [{'PASS' if fails == 0 else 'FAIL'}] {orig_path} -> {new_path} "
          f"(fmt {fmt1}, {len(f1)} files, {fails} failures)")
    return fails


if __name__ == "__main__":
    a, b, e = sys.argv[1], sys.argv[2], int(sys.argv[3])
    sys.exit(1 if main(a, b, e) else 0)
