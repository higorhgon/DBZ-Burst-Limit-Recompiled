#!/usr/bin/env python3
"""Rewrites an unsigned APK with zipalign's layout, for systems without
zipalign (Termux).

  apk_align.py in.apk out.apk

resources.arsc is stored uncompressed on a 4-byte boundary (Android 11+
refuses to install it otherwise), native libraries are stored uncompressed
on a 16 KB boundary (page aligned on 4 KB and 16 KB devices), everything
else is deflated. Sign the result with apksigner afterwards.
"""

import sys
import zipfile


def alignment(name):
    if name.endswith(".so"):
        return 16384
    if name == "resources.arsc":
        return 4
    return 0  # Deflated.


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip())
        return 2
    with zipfile.ZipFile(argv[1]) as src, zipfile.ZipFile(argv[2], "w") as dst:
        for item in src.infolist():
            data = src.read(item)
            info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            info.external_attr = item.external_attr
            align = alignment(item.filename)
            if align:
                info.compress_type = zipfile.ZIP_STORED
                # The data starts after the 30-byte local header, the name and
                # the extra field: pad the extra field to land on the boundary.
                start = dst.fp.tell() + 30 + len(info.filename.encode("utf-8"))
                # zipalign's alignment record: id 0xD935, data size, the
                # alignment (u16), zero padding. apksigner reads it and keeps
                # the alignment when it signs. At least 6 bytes.
                pad = (-start) % align
                while pad < 6:
                    pad += align
                info.extra = (0xD935).to_bytes(2, "little") + (pad - 4).to_bytes(2, "little") \
                    + align.to_bytes(2, "little") + b"\0" * (pad - 6)
            else:
                info.compress_type = zipfile.ZIP_DEFLATED
            dst.writestr(info, data)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
