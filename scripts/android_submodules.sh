#!/usr/bin/env bash
# Checks out the SDK and only the dependencies the Android build uses, shallow
# (much smaller than git submodule update --init --recursive).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
git -C "$ROOT" submodule update --init --depth 1 thirdparty/rexglue-sdk
for dep in cli11 libmspack FFmpeg tomlplusplus simde xxHash spdlog fmt utfcpp imgui sdl3 \
           vulkan-headers vulkan-memory-allocator spirv-headers spirv-tools glslang o1heap inja; do
  git -C "$ROOT/thirdparty/rexglue-sdk" submodule update --init --depth 1 "thirdparty/$dep"
done
