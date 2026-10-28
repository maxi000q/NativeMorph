#!/usr/bin/env python3
"""Verify a repacked nativemorph PCK against its source.

Checks (little-endian container):
  1. header fields (magic, format, version, count)
  2. project.godot was patched (mobile renderer, scale, shadows)
  3. every other entry payload is byte-identical to the source
  4. MD5 fields are valid for all entries

Usage: verify_pck.py <source.pck> <repacked.pck>
"""
import hashlib
import struct
import sys


def rd32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def rd64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def parse(data):
    assert data[:4] == b"GDPC", "bad magic"
    fmt = rd32(data, 4)
    assert fmt == 2, f"format {fmt} != 2"
    ver = (rd32(data, 8), rd32(data, 12), rd32(data, 16))
    pack_flags = rd32(data, 20)
    file_base = rd64(data, 84)
    count = rd32(data, 92)
    off = 96
    entries = []
    for _ in range(count):
        plen = rd32(data, off)
        off += 4
        path = data[off:off + plen].decode()
        off += plen
        eoff = rd64(data, off); off += 8
        esize = rd64(data, off); off += 8
        md5 = data[off:off + 16]; off += 16
        flags = rd32(data, off); off += 4
        size_comp = esize
        if flags & 0xFF:
            size_comp = rd64(data, off); off += 8
        entries.append((path, eoff, esize, md5, flags, size_comp))
    return dict(fmt=fmt, ver=ver, pack_flags=pack_flags, file_base=file_base,
                entries=entries)


def main():
    src_p, rep_p = sys.argv[1], sys.argv[2]
    src = open(src_p, "rb").read()
    rep = open(rep_p, "rb").read()
    a, b = parse(src), parse(rep)

    assert a["ver"] == b["ver"], f"version changed: {a['ver']} -> {b['ver']}"
    ae = {e[0]: e for e in a["entries"]}
    be = {e[0]: e for e in b["entries"]}
    assert ae.keys() == be.keys(), "entry set changed"

    ok = True
    for path, (_, soff, ssize, smd5, sflags, ssc) in ae.items():
        (_, roff, rsize, rmd5, rflags, rsc) = be[path]
        if path == "res://project.godot":
            txt = rep[roff:roff + rsize].decode()
            checks = [
                ('renderer/rendering_method="mobile"' in txt, "mobile renderer"),
                ('renderer/rendering_method.mobile="mobile"' in txt, "mobile override"),
                ("scaling_3d/scale=0.85" in txt, "scale 0.85"),
                ("scaling_3d/mode=1" in txt, "FSR mode"),
                ("directional_shadow/size=1024" in txt, "shadow atlas"),
                ('config/name="Nativemorph Test Game"' in txt, "name preserved"),
                ("msaa_3d=0" in txt, "msaa"),
            ]
            for good, label in checks:
                print(f"  [{'OK' if good else 'FAIL'}] project.godot: {label}")
                ok &= good
            real_md5 = hashlib.md5(rep[roff:roff + rsize]).digest()
            assert real_md5 == rmd5, "patched entry MD5 mismatch"
            print("  [OK] project.godot: fresh MD5 valid")
            assert rflags == 0, "patched entry not stored uncompressed"
            continue
        # untouched entry: payload byte-identical + MD5 carried over
        same = src[soff:soff + ssc] == rep[roff:roff + rsc]
        print(f"  [{'OK' if same else 'FAIL'}] {path}: payload byte-identical")
        ok &= same
        assert smd5 == rmd5, f"{path}: MD5 changed"
        assert hashlib.md5(src[soff:soff + ssize]).digest() == rmd5, \
            f"{path}: source MD5 invalid"

    # directory offsets must point at correct payloads (verified implicitly by
    # the byte-compare above) and the trailer must not exist (plain pck)
    print(f"entries={len(b['entries'])} version={b['ver']} file_base={b['file_base']}")
    print("VERIFY:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
