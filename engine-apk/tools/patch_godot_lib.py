#!/usr/bin/env python3
"""
patch_godot_lib.py — NativeMorph engine runtime fix (v1.3.1).

WHY
---
Official Godot Android export templates are built with
`disable_path_overrides=yes` (the SConstruct default for templates), so the
OVERRIDE_PATH_ENABLED define is absent. In that configuration
Main::setup() (main/main.cpp, 4.7.2-stable lines 1864-1896) only accepts a
`--main-pack` that resolves to FileAccess::ACCESS_RESOURCES — i.e. a pack
INSIDE the APK's own assets/ directory. Every other path aborts:

    "--main-pack is attempting to load from outside of the executable, but
     this Godot binary was compiled without support for path overrides.
     Aborting."

which surfaces in the app as the fatal
"Unable to setup the Godot engine! Aborting..." dialog — for every game,
because NativeMorph stores optimized packs in the app sandbox and always
launches them via `--main-pack <sandbox path>`.

Godot's own error message prescribes the remedy: build the engine with
`disable_path_overrides=no`. Building the whole engine is heavy, so this
script applies the exact same semantic change to the OFFICIAL prebuilt
template_release AAR: in the arm64 libgodot_android.so, the validation
compiles down to a single conditional branch

    Main::setup+0x...:  bl    FileAccess::get_access_type()
    0x123bef0:          cbnz  w0, <"--main-pack outside the executable" error>

and we replace that one `cbnz` with a NOP. The null-ref check, the pack
loading, argument parsing and everything else in the engine are untouched;
the assets-only rejection simply becomes unreachable dead code, exactly as
if OVERRIDE_PATH_ENABLED had been defined at build time.

USAGE
-----
    python3 tools/patch_godot_lib.py libs/godot-lib-4.7.2-release.aar

Pure Python 3 (stdlib only). Verifies the instruction word before patching
and refuses to touch anything if the binary does not match the analyzed
4.7.2-stable template layout.
"""
import os
import struct
import sys
import zipfile

GATE_VA = 0x123bef0          # va of the cbnz inside Main::setup
EXPECT_WORD = 0x35090B40     # `cbnz w0, +0x21e6c`
NOP_WORD = 0xD503201F        # `nop`
EXPECT_SIZE = 71114944       # official 4.7.2-stable arm64 template .so size

# ELF64 little-endian section header walking (no external tools needed).
SHT_PROGBITS = 1


def elf_text_off(elf):
    if elf[:4] != b"\x7fELF" or elf[4] != 2 or elf[5] != 1:
        raise SystemExit("not an ELF64 little-endian file")
    e_shoff = struct.unpack_from("<Q", elf, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", elf, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", elf, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", elf, 0x3E)[0]

    def shdr(i):
        base = e_shoff + i * e_shentsize
        (name, typ, flags, addr, off, size) = struct.unpack_from(
            "<IIQQQQ", elf, base)
        return dict(name=name, typ=typ, addr=addr, off=off, size=size)

    strtab = shdr(e_shstrndx)
    names = elf[strtab["off"]:strtab["off"] + strtab["size"]]

    def zstr(table, off):
        end = table.find(b"\x00", off)
        return table[off:end].decode()

    text = None
    for i in range(e_shnum):
        s = shdr(i)
        if s["typ"] == SHT_PROGBITS and zstr(names, s["name"]) == ".text":
            text = s
            break
    if text is None:
        raise SystemExit("no .text section found")
    # translate the gate's virtual address to a file offset
    return text["off"] + (GATE_VA - text["addr"])


def patch_so(data):
    if len(data) != EXPECT_SIZE:
        raise SystemExit(
            f"unexpected .so size {len(data)} (expected {EXPECT_SIZE}); "
            "this is not the official 4.7.2-stable arm64 template binary")
    off = elf_text_off(data)
    word = struct.unpack_from("<I", data, off)[0]
    if word != EXPECT_WORD:
        raise SystemExit(
            f"instruction at 0x{off:x} is 0x{word:08x}, expected the "
            "analyzed cbnz — engine layout unknown, refusing to patch")
    out = bytearray(data)
    struct.pack_into("<I", out, off, NOP_WORD)
    print(f"  patched arm64 libgodot_android.so: "
          f"0x{word:08x} (cbnz w0, err) -> 0x{NOP_WORD:08x} (nop) @ 0x{off:x}")
    return bytes(out)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__ + "\nusage: patch_godot_lib.py <aar>")
    aar = sys.argv[1]
    zin = zipfile.ZipFile(aar, "r")
    entries = {i.filename: zin.read(i.filename) for i in zin.infolist()
               if not i.filename.endswith("/")}
    target = "jni/arm64-v8a/libgodot_android.so"
    if target not in entries:
        raise SystemExit(f"{target} not found inside the AAR")
    print(f"patching {aar} :: {target}")
    entries[target] = patch_so(entries[target])

    tmp = aar + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for name, blob in entries.items():
            zout.writestr(name, blob)
    os.replace(tmp, aar)

    # post-verify: re-open and confirm the NOP is in place
    back = zipfile.ZipFile(aar, "r").read(target)
    off = elf_text_off(back)
    got = struct.unpack_from("<I", back, off)[0]
    print(f"  verification: word now 0x{got:08x} "
          f"({'NOP ok' if got == NOP_WORD else 'FAILED'})")
    if got != NOP_WORD:
        raise SystemExit(1)
    print("done — the engine now accepts external --main-pack paths "
          "(same semantics as disable_path_overrides=no)")


if __name__ == "__main__":
    main()
