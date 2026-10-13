Direct host resolve compute shaders, from XenDroid
(https://github.com/rfandango/XenDroid, commit 779680a,
emulator-core/src/main/cpp/xenia/src/xenia/{gpu,ui}/shaders), unchanged.
Xenia code, BSD license (LICENSE). Built into a C++ header by
scripts/gen_direct_host_resolve.py; used by the SDK patch
(vulkan/render_target_cache.cpp, TryDirectHostResolveCopy).
