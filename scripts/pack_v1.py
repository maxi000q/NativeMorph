#!/usr/bin/env python3
"""Pack a 3.x game project folder into a spec-accurate v1 PCK (the format
Godot 3.0-3.6 writes): res://-prefixed stored paths padded to 4, no flags,
absolute offsets. Matches make_test_packs.py's verified writer."""
import hashlib
import os
import struct
import sys

MAGIC = 0x43504447  # LE u32 "GDPC"


def md5(b: bytes) -> bytes:
    return hashlib.md5(b).digest()


def pack_v1(project_dir: str, out_path: str, ver=(3, 6, 2)) -> int:
    files = []
    for root, dirs, names in os.walk(project_dir):
        dirs[:] = [d for d in dirs if d not in (".godot", ".git", "__macosx")]
        for n in sorted(names):
            if n == "export_presets.cfg":
                continue
            full = os.path.join(root, n)
            rel = os.path.relpath(full, project_dir).replace(os.sep, "/")
            files.append(("res://" + rel, full))
    files.sort(key=lambda t: t[0])

    entries = []
    for rp, full in files:
        data = open(full, "rb").read()
        entries.append((rp, len(data), md5(data), data))

    head = struct.pack("<IIIII", MAGIC, 1, *ver)
    head += b"\0" * 64
    head += struct.pack("<I", len(entries))

    # padded path length (len + pad) per entry, offset(8) + size(8) + md5(16)
    def entry_size(p: bytes) -> int:
        pad = (4 - (len(p) % 4)) % 4
        return 4 + len(p) + pad + 8 + 8 + 16

    dirsz = sum(entry_size(p.encode()) for p, _, _, _ in entries)
    base = len(head) + dirsz

    with open(out_path, "wb") as out:
        out.write(head)
        pos = base
        for p, size, digest, _ in entries:
            pb = p.encode()
            pad = (4 - (len(pb) % 4)) % 4
            out.write(struct.pack("<I", len(pb) + pad))
            out.write(pb + b"\0" * pad)
            out.write(struct.pack("<QQ", pos, size))
            out.write(digest)
            pos += size
        for _, _, _, data in entries:
            out.write(data)
    return len(entries)


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    n = pack_v1(src, dst)
    print(f"packed {n} entries -> {dst} ({os.path.getsize(dst)} bytes)")
