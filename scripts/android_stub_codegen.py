#!/usr/bin/env python3
"""Stand-in for generated/default when default.xex isn't available (CI).

Renders the SDK's codegen templates with no game code: every guest function
that src/ refers to is defined, and aborts when called. The result compiles
and links the whole Android build (runtime, libmain.so, APK) so a commit can
be checked without the game, but it does NOT run the game.

Used by scripts/build_android.sh --compile-check. Needs jinja2.
"""

import glob
import os
import re
import sys

import jinja2


def main() -> int:
    root = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), ".."))
    templates = os.path.join(root, "thirdparty/rexglue-sdk/resources/templates")
    out = os.path.join(root, "generated/default")
    os.makedirs(out, exist_ok=True)

    names = set()
    for path in glob.glob(os.path.join(root, "src/*.cpp")) + glob.glob(os.path.join(root, "src/*.h")):
        with open(path, encoding="utf-8", errors="replace") as f:
            names |= set(re.findall(r"\bsub_8[0-9A-F]{7}\b", f.read()))
    functions = [
        {"name": n, "address": "0x" + n[4:], "below_code_base": False, "is_import": False}
        for n in sorted(names)
    ]
    flags = dict.fromkeys(
        ["skip_lr", "ctr_as_local", "xer_as_local", "reserved_as_local", "skip_msr",
         "cr_as_local", "non_argument_as_local", "non_volatile_as_local"], False)
    data = {
        "project": "burstlimit",
        "functions": functions,
        "config_flags": {k: str(v).lower() for k, v in flags.items()},
        "image_base": "0x82000000",
        "image_size": "0x00800000",
        "code_base": "0x820A0000",
        "code_size": "0x00600000",
        "thunk_reserve_size": "0x0",
        "rexcrt_heap": False,
        "has_dll_modules": False,
        "is_dll": False,
        "recomp_files": ["burstlimit_stub_funcs.cpp"],
        "cmake_var": lambda v: "${" + v + "}",
    }
    # pch_h tests the flags as booleans, init_cpp prints them.
    pch_data = dict(data, config_flags=flags)

    env = jinja2.Environment(loader=jinja2.FileSystemLoader(templates), keep_trailing_newline=True)
    outputs = [
        ("codegen/pch_h.inja", "burstlimit_pch.h", pch_data),
        ("codegen/funcs_h.inja", "burstlimit_funcs.h", data),
        ("codegen/init_h.inja", "burstlimit_init.h", data),
        ("codegen/init_cpp.inja", "burstlimit_init.cpp", data),
        ("codegen/register_cpp.inja", "burstlimit_register.cpp", data),
        ("codegen/sources_cmake.inja", "sources.cmake", data),
    ]
    for template, name, values in outputs:
        with open(os.path.join(out, name), "w", encoding="utf-8") as f:
            f.write(env.get_template(template).render(**values))

    with open(os.path.join(out, "burstlimit_stub_funcs.cpp"), "w", encoding="utf-8") as f:
        f.write("// Stand-in (scripts/android_stub_codegen.py): no game code.\n")
        f.write('#include "burstlimit_init.h"\n\n')
        for n in sorted(names):
            f.write(f"DEFINE_REX_FUNC({n}) {{ (void)ctx; (void)base; std::abort(); }}\n")
    stamp = os.path.join(out, "codegen.build.stamp")
    with open(stamp, "w", encoding="utf-8") as f:
        f.write("stub\n")
    with open(os.path.join(out, "codegen.d"), "w", encoding="utf-8") as f:
        f.write(stamp + ":\n")
    print(f"generated/default: stand-in with {len(names)} stub functions (no game code)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
