#!/usr/bin/env python3
"""Sparse-extract the Android system image zip: zero blocks become file holes
so peak disk usage stays near the compressed size instead of 3.2GB."""
import sys
import zipfile
import os

BLOCK = 1024 * 1024  # 1MB

def extract_sparse(zf: zipfile.ZipFile, info: zipfile.ZipInfo, dest: str) -> None:
    total = info.file_size
    written = 0
    with zf.open(info) as src, open(dest, "wb") as out:
        pos = 0
        zero_run_at = -1
        while pos < total:
            chunk = src.read(min(BLOCK, total - pos))
            if not chunk:
                break
            if chunk.count(b"\x00") == len(chunk):
                # all-zero block: extend the hole, do not write
                if zero_run_at < 0:
                    zero_run_at = pos
            else:
                if zero_run_at >= 0:
                    # hole ends here: skip ahead (file grows as zeros)
                    out.seek(pos)
                    zero_run_at = -1
                out.write(chunk)
            pos += len(chunk)
            written += len(chunk)
        # final size
        out.truncate(total)

def main() -> None:
    zip_path, dest_dir = sys.argv[1], sys.argv[2]
    os.makedirs(dest_dir, exist_ok=True)
    with zipfile.ZipFile(zip_path) as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            dest = os.path.join(dest_dir, info.filename)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            extract_sparse(zf, info, dest)
            os.chmod(dest, 0o644)
            print(f"  {info.filename} apparent={info.file_size}")

if __name__ == "__main__":
    main()
