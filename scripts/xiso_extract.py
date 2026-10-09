#!/usr/bin/env python3
"""Extracts an Xbox 360 disc image (XDVDFS game partition), like extract-xiso.

  xiso_extract.py game.iso out_dir              the whole game partition
  xiso_extract.py game.iso out_dir default.xex  only these paths

Works on full disc images (redump: the game partition starts at an offset
that depends on the disc type) and on trimmed "XISO" images. The same logic
as the Android app's XisoExtractor.java.
"""

import os
import struct
import sys

SECTOR = 2048
MAGIC = b"MICROSOFT*XBOX*MEDIA"
# Game partition start: trimmed XISO, XGD2, XGD3, XGD1 (original Xbox).
PARTITION_OFFSETS = (0, 0xFD90000, 0x2080000, 0x18300000)
ATTRIBUTE_DIRECTORY = 0x10


def find_partition(f):
    size = os.fstat(f.fileno()).st_size
    for offset in PARTITION_OFFSETS:
        pos = offset + 32 * SECTOR
        if pos + 28 > size:
            continue
        f.seek(pos)
        descriptor = f.read(28)
        if descriptor[:20] == MAGIC:
            root_sector, root_size = struct.unpack("<II", descriptor[20:28])
            return offset, root_sector, root_size
    return None


def list_entries(f, partition, root_sector, root_size):
    """(path, sector, size, is_directory) for every entry, folders first."""
    entries = []
    folders = [("", root_sector, root_size)]
    visited = set()
    while folders:
        path, sector, size = folders.pop(0)
        if size == 0 or sector in visited:
            continue
        visited.add(sector)
        f.seek(partition + sector * SECTOR)
        table = f.read(size)
        if len(table) < size:
            raise IOError(f"directory table past the end of the image: {path or '/'}")
        pending, seen = [0], set()
        while pending:
            offset = pending.pop()
            if offset + 14 > len(table) or offset in seen:
                continue
            seen.add(offset)
            left, right, entry_sector, entry_size, attributes, name_length = struct.unpack_from(
                "<HHIIBB", table, offset)
            if left == 0xFFFF and right == 0xFFFF:
                continue  # Padding: an empty folder.
            name = table[offset + 14:offset + 14 + name_length].decode("latin-1")
            if name and name not in (".", "..") and not any(c in name for c in "/\\\0"):
                child = f"{path}/{name}" if path else name
                is_directory = bool(attributes & ATTRIBUTE_DIRECTORY)
                entries.append((child, entry_sector, entry_size, is_directory))
                if is_directory:
                    folders.append((child, entry_sector, entry_size))
            for link in (left, right):
                if link not in (0, 0xFFFF):
                    pending.append(link * 4)
    return entries


def main(argv):
    if len(argv) < 3:
        print(__doc__.strip())
        return 2
    image, out_dir, only = argv[1], argv[2], {p.lower() for p in argv[3:]}
    with open(image, "rb") as f:
        found = find_partition(f)
        if not found:
            print("error: not an Xbox 360 disc image (no XDVDFS game partition)", file=sys.stderr)
            return 1
        partition, root_sector, root_size = found
        entries = list_entries(f, partition, root_sector, root_size)
        if only:
            entries = [e for e in entries if e[0].lower() in only]
            missing = only - {e[0].lower() for e in entries}
            if missing:
                print(f"error: not in the image: {', '.join(sorted(missing))}", file=sys.stderr)
                return 1
        total = sum(e[2] for e in entries if not e[3])
        done = 0
        for path, sector, size, is_directory in entries:
            target = os.path.join(out_dir, *path.split("/"))
            if is_directory:
                os.makedirs(target, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            f.seek(partition + sector * SECTOR)
            remaining = size
            with open(target, "wb") as out:
                while remaining:
                    chunk = f.read(min(remaining, 1 << 20))
                    if not chunk:
                        raise IOError(f"unexpected end of the image in {path}")
                    out.write(chunk)
                    remaining -= len(chunk)
                    done += len(chunk)
            print(f"{done * 100 // max(total, 1):3d}%  {path}", flush=True)
    print(f"Extracted {sum(1 for e in entries if not e[3])} files ({total / 1048576:.1f} MB) to {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
