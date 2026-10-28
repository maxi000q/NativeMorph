#!/usr/bin/env python3
"""Generate spec-accurate Godot PCK test packs (formats 1/2/3/4), plus
exe-embedded and zip variants, mirroring the engine writer:
  editor/export/editor_export_platform.cpp  (4.7 master: v2/v3/v4)
  core/io/file_access_pack.cpp              (3.5.3/3.6.2: v1)

Everything LITTLE-endian. Entries have NO compression (this format
generation has none). Paths in v2+ are padded to a 4-byte multiple.
"""
import hashlib
import os
import struct
import sys

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/nmtest"
os.makedirs(OUT, exist_ok=True)

MAGIC = 0x43504447  # LE u32 of "GDPC"
REL_FILEBASE = 1 << 1

PG4 = (
    "; Engine configuration file.\n"
    "config_version=5\n"
    "\n"
    "[application]\n"
    "\n"
    'config/name="NM Probe 4"\n'
    'run/main_scene="res://main.tscn"\n'
    'config/features=PackedStringArray("4.7")\n'
    "\n"
    "[rendering]\n"
    "\n"
    'renderer/rendering_method="forward_plus"\n'
    "anti_aliasing/quality/msaa_3d=4\n"
)

PG3 = (
    "; Engine configuration file.\n"
    "config_version=4\n"
    "\n"
    "[application]\n"
    "\n"
    'config/name="NM Probe 3"\n'
    'run/main_scene="res://main.tscn"\n'
    "\n"
    "[rendering]\n"
    "\n"
    "quality/driver/fallback_to_gles2=false\n"
)

TSCN4 = ('[gd_scene load_steps=2 format=3]\n\n'
         '[ext_resource type="Script" path="res://main.gd" id="1"]\n\n'
         '[node name="root" type="Node"]\n'
         'script = ExtResource("1")\n')
TSCN3 = ('[gd_scene load_steps=2 format=2]\n\n'
         '[ext_resource path="res://main.gd" type="Script" id=1]\n\n'
         '[node name="root" type="Node"]\n'
         'script = ExtResource( 1 )\n')

GD4 = '''extends Node
func _ready():
    var png = FileAccess.get_file_as_bytes("res://icon.png")
    var txt = FileAccess.get_file_as_bytes("res://text/readme.txt")
    var dat = FileAccess.get_file_as_bytes("res://data/asset.dat")
    print("NMPROBE_OK png=", png.size(), " txt=", txt.size(), " dat=", dat.size())
    get_tree().quit()
'''

GD3 = '''extends Node
func _ready():
    var f = File.new()
    f.open("res://icon.png", File.READ)
    var png = f.get_len()
    f.close()
    f.open("res://text/readme.txt", File.READ)
    var txt = f.get_len()
    f.close()
    f.open("res://data/asset.dat", File.READ)
    var dat = f.get_len()
    f.close()
    print("NMPROBE_OK png=", png, " txt=", txt, " dat=", dat)
    get_tree().quit()
'''


def md5(b):
    return hashlib.md5(b).digest()


def align(n, a):
    return (a - (n % a)) % a


def build_files(engine):
    files = {}
    pg = PG4 if engine == 4 else PG3
    tscn = TSCN4 if engine == 4 else TSCN3
    files["project.godot"] = pg.encode()
    files["main.tscn"] = tscn.encode()
    files["main.gd"] = (GD4 if engine == 4 else GD3).encode()
    files["icon.png"] = bytes(range(256)) * 8      # 2048 B
    files["data/asset.dat"] = os.urandom(1000)     # binary payload
    files["text/readme.txt"] = b"hello nativemorph\n" * 4
    return files


def write_entry_v1(out, path, abs_off, size, digest):
    pad = (4 - (len(path) % 4)) % 4   # 3.6.2 writer pads paths
    out += struct.pack("<I", len(path) + pad)
    out += path + b"\0" * pad
    out += struct.pack("<QQ", abs_off, size)
    out += digest
    return out


def write_entry_v2plus(out, path, rel_off, size, digest, flags=0):
    pad = (4 - (len(path) % 4)) % 4
    out += struct.pack("<I", len(path) + pad)
    out += path + b"\0" * pad
    out += struct.pack("<QQ", rel_off, size)
    out += digest
    out += struct.pack("<I", flags)
    return out


def make_v1(ver=(3, 6, 2), at=0):
    """3.x pack. `at` = position the pack will live at in the host file
    (the 3.x exporter writes EXE-absolute offsets when embedding)."""
    files = build_files(3)
    head = struct.pack("<IIIII", MAGIC, 1, *ver) + b"\0" * 64
    head += struct.pack("<I", len(files))
    dirsz = sum(4 + ((len(p) + 6 + 3) & ~3) + 8 + 8 + 16 for p in files)
    payload_base = at + len(head) + dirsz
    d = b""
    pos = payload_base
    body = b""
    for p, data in files.items():
        d = write_entry_v1(d, ("res://" + p).encode(), pos, len(data), md5(data))
        body += data
        pos += len(data)
    return head + d + body


def make_v2plus(fmt, ver, padded_paths=True):
    files = build_files(4)
    head = struct.pack("<IIIII", MAGIC, fmt, *ver)
    head += struct.pack("<I", REL_FILEBASE)     # pack_flags
    head += struct.pack("<Q", 0)                # file_base placeholder
    if fmt >= 3:
        head += struct.pack("<Q", 0)            # dir_offset placeholder
        head += b"\0" * 64                      # reserved/salt region (32+32)
        while len(head) % 16:
            head += b"\0"
    else:
        head += b"\0" * 64                      # 16 reserved words
        head += struct.pack("<I", len(files))   # count right after reserved

    # payload region (16-aligned) starts after the header; v2: after dir
    def dir_entry_size(p):
        pad = (4 - (len(p) % 4)) % 4 if padded_paths else 0
        return 4 + len(p) + pad + 8 + 8 + 16 + 4

    if fmt <= 2:
        dirsz = sum(dir_entry_size(p) for p in files)
        payload_base = len(head) + dirsz
    else:
        payload_base = len(head)

    body = b""
    offs = {}
    for p, data in files.items():
        offs[p] = payload_base + len(body)
        body += data
        if fmt >= 3:
            body += b"\0" * align(len(body), 16)
    if fmt >= 3:
        body += b"\0" * align(len(body), 16)

    d = b""
    if fmt >= 3:
        d += struct.pack("<I", len(files))  # count lives at dir_offset
    for p, data in files.items():
        if padded_paths:
            d = write_entry_v2plus(d, p.encode(), offs[p] - payload_base,
                                   len(data), md5(data))
        else:
            # unpadded variant (old v2 exporter style)
            d += struct.pack("<I", len(p)) + p.encode()
            d += struct.pack("<QQ", offs[p] - payload_base, len(data))
            d += md5(data) + struct.pack("<I", 0)

    if fmt >= 3:
        head = (head[:24] + struct.pack("<Q", payload_base) + head[32:]
                if False else head)
        # patch file_base @24 and dir_offset @32
        head = head[:24] + struct.pack("<Q", payload_base) + head[32:]
        dir_off = payload_base + len(body)
        head = head[:32] + struct.pack("<Q", dir_off) + head[40:]
        return head + body + d
    else:
        head = head[:24] + struct.pack("<Q", payload_base) + head[32:]
        return head + d + body


def embed(pack, stub_size=256, v1=False, ver=(3, 6, 2)):
    stub = b"MZ" + os.urandom(stub_size - 2)
    out = stub + b"\0" * align(len(stub), 8)
    if v1:
        # 3.x: rebuild with exe-absolute offsets at the embed position
        pack = make_v1(ver, at=len(out))
    out += pack
    # the writer pads the embedded region (incl. the 12-byte trailer) to 8,
    # then stores pck_size = current pos - pck start (INCLUDING that pad)
    pad2 = align(len(pack) + 12, 8)
    out += b"\0" * pad2
    out += struct.pack("<Q", len(pack) + pad2)  # pck size incl. trailing pad
    out += struct.pack("<I", MAGIC)
    return out


def main():
    w = lambda name, data: open(os.path.join(OUT, name), "wb").write(data)

    # 3.x
    v1 = make_v1()
    w("probe_v1.pck", v1)
    w("probe_v1.exe", embed(make_v1((3, 5, 1)), v1=True, ver=(3, 5, 1)))

    # 4.x eras
    w("probe_v2.pck", make_v2plus(2, (4, 2, 2)))
    w("probe_v3.pck", make_v2plus(3, (4, 4, 1)))
    w("probe_v4.pck", make_v2plus(4, (4, 7, 2)))
    w("probe_v4_nopad.pck", make_v2plus(4, (4, 7, 2), padded_paths=False))
    w("probe_v4.exe", embed(make_v2plus(4, (4, 7, 2))))

    # zip bundle
    import zipfile
    with zipfile.ZipFile(os.path.join(OUT, "probe_bundle.zip"), "w") as z:
        z.write(os.path.join(OUT, "probe_v4.pck"), "probe_v4.pck")

    # sanity: sizes
    for f in sorted(os.listdir(OUT)):
        print(f, os.path.getsize(os.path.join(OUT, f)))


if __name__ == "__main__":
    main()
