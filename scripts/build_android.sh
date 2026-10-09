#!/usr/bin/env bash
# Builds the Android APK (arm64-v8a).
#
#   scripts/build_android.sh                 full build: codegen + native + APK
#   scripts/build_android.sh --apk-only      repackage the APK from the last native build
#   scripts/build_android.sh --compile-check build everything without default.xex, with a
#                                            stand-in for the game code (CI): the APK it makes
#                                            does NOT run the game
#
# Needs: game_data_root/default.xex (US version, the recompiler reads it), the
# Android SDK (platform + build-tools) and NDK r28 or newer, CMake, Ninja,
# clang (20 or newer) for the build machine, and a JDK 17+.
#
# Environment:
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK (default: ~/Android/Sdk)
#   ANDROID_NDK                      NDK folder (default: newest in $ANDROID_HOME/ndk)
#   HOST_CC / HOST_CXX               build machine compilers (default: clang / clang++)
#   HOST_REXGLUE                     an existing rexglue for the build machine (skips building it)
#   APK_KEYSTORE, APK_KEY_ALIAS,     release signing key; without it a key is made
#   APK_KEYSTORE_PASS                in out/android/burstlimit.keystore and reused
#   BURSTLIMIT_VERSION_OVERRIDE      online version string (see README)
#   APK_VERSION_NAME, APK_VERSION_CODE  APK version (default 0.4.0-android, 1)
#   USE_CCACHE=1                     compile through ccache
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_SRC="$ROOT/thirdparty/rexglue-sdk"
OUT="$ROOT/out/android"
HOST_BUILD="$ROOT/out/build/android-host"
TARGET_BUILD="$ROOT/out/build/android-arm64"
APP="$ROOT/android/app/src/main"
API_LEVEL=29
APK_NAME="DBZ-Burst-Limit-Recompiled-android.apk"
VERSION_NAME="${APK_VERSION_NAME:-0.4.0-android}"
VERSION_CODE="${APK_VERSION_CODE:-1}"
PACKAGE="com.dbzburstlimit.recompiled"

APK_ONLY=0
COMPILE_CHECK=0
for arg in "$@"; do
  case "$arg" in
    --apk-only) APK_ONLY=1 ;;
    --compile-check) COMPILE_CHECK=1 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

if [ "$COMPILE_CHECK" -eq 1 ]; then
  APK_NAME="DBZ-Burst-Limit-Recompiled-android-compile-check.apk"
fi

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- Tools --------------------------------------------------------------------
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d "$ANDROID_HOME" ] || die "Android SDK not found (set ANDROID_HOME)"
if [ -z "${ANDROID_NDK:-}" ]; then
  ANDROID_NDK="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  ANDROID_NDK="${ANDROID_NDK%/}"
fi
[ -f "${ANDROID_NDK:-}/build/cmake/android.toolchain.cmake" ] ||
  die "Android NDK not found (set ANDROID_NDK or install one with sdkmanager \"ndk;29.0.14206865\")"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
BUILD_TOOLS="${BUILD_TOOLS%/}"
[ -x "$BUILD_TOOLS/aapt2" ] || die "Android build-tools not found (sdkmanager \"build-tools;35.0.0\")"
PLATFORM_JAR=""
for p in $(ls -d "$ANDROID_HOME"/platforms/android-*/ 2>/dev/null | sort -V -r); do
  # android-35 or newer: SDL's Java side uses API 35 constants.
  # Stable platforms only (android-35, not android-37.2-beta1).
  name="$(basename "$p")"
  [[ "$name" =~ ^android-([0-9]+)$ ]] || continue
  level="${BASH_REMATCH[1]}"
  if [ "$level" -ge 35 ] && [ -f "$p/android.jar" ]; then
    PLATFORM_JAR="$p/android.jar"
    break
  fi
done
[ -n "$PLATFORM_JAR" ] || die "Android platform 35+ not found (sdkmanager \"platforms;android-35\")"
NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
NDK_SYSROOT_LIB="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android"
for tool in cmake ninja javac keytool zip unzip; do
  command -v "$tool" >/dev/null || die "$tool not found"
done
LAUNCHER_ARGS=()
if [ "${USE_CCACHE:-0}" = "1" ]; then
  command -v ccache >/dev/null || die "USE_CCACHE=1 but ccache not found"
  LAUNCHER_ARGS=(-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache)
fi
HOST_CC="${HOST_CC:-clang}"
HOST_CXX="${HOST_CXX:-clang++}"
JOBS="${JOBS:-$(nproc)}"

echo "SDK:         $ANDROID_HOME"
echo "NDK:         $ANDROID_NDK"
echo "build-tools: $BUILD_TOOLS"
echo "platform:    $PLATFORM_JAR"

mkdir -p "$OUT"
LIBS="$OUT/lib/arm64-v8a"

GENERATED="$ROOT/generated/default"
STUB_MARKER="$GENERATED/burstlimit_stub_funcs.cpp"

if [ "$APK_ONLY" -eq 0 ]; then
  # --- Game executable --------------------------------------------------------
  XEX="$ROOT/game_data_root/default.xex"
  if [ "$COMPILE_CHECK" -eq 1 ]; then
    if [ -d "$GENERATED" ] && [ ! -f "$STUB_MARKER" ]; then
      die "generated/default holds recompiled game code; --compile-check would replace it with the
stand-in. Move it away first, or build normally."
    fi
  else
    [ -f "$XEX" ] || die "game_data_root/default.xex not found: the recompiler translates the game code
from it at build time (the APK itself asks for the ISO on the phone). See README-android.md."
    # Left over from a --compile-check: the real codegen starts from scratch.
    if [ -f "$STUB_MARKER" ]; then
      rm -rf "$GENERATED"
    fi
  fi
  if [ "$COMPILE_CHECK" -eq 0 ] && command -v sha1sum >/dev/null; then
    sha="$(sha1sum "$XEX" | cut -d' ' -f1)"
    [ "$sha" = "aec598f88cf51181fc377b148e0b1ad30db4485c" ] ||
      echo "warning: default.xex SHA-1 $sha is not the US version this project targets"
  fi

  # --- SDK Android patch ------------------------------------------------------
  PATCH="$ROOT/android/patches/rexglue-sdk-android.patch"
  [ -f "$SDK_SRC/CMakeLists.txt" ] || die "SDK submodule missing: git submodule update --init --recursive"
  if git -C "$SDK_SRC" apply --reverse --check "$PATCH" 2>/dev/null; then
    echo "SDK Android patch: already applied"
  else
    log "Applying the SDK Android patch"
    git -C "$SDK_SRC" apply --whitespace=nowarn "$PATCH" ||
      die "the SDK Android patch doesn't apply. The SDK submodule is at another commit, or holds
other changes (an older version of the patch?). To start over: git -C thirdparty/rexglue-sdk checkout . &&
git -C thirdparty/rexglue-sdk clean -fd src include, then run this again."
  fi

  # --- Codegen tool for the build machine ------------------------------------
  if [ -z "${HOST_REXGLUE:-}" ]; then
    log "Building rexglue (codegen) for the build machine"
    host_flags=()
    case "$(uname -m)" in
      x86_64) host_flags=(-DCMAKE_C_FLAGS=-march=x86-64-v2 -DCMAKE_CXX_FLAGS=-march=x86-64-v2) ;;
    esac
    cmake -S "$SDK_SRC" -B "$HOST_BUILD" -G Ninja \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_COMPILER="$HOST_CC" -DCMAKE_CXX_COMPILER="$HOST_CXX" \
      "${host_flags[@]}" "${LAUNCHER_ARGS[@]}" -DREXGLUE_ENABLE_TRACY=OFF >/dev/null
    cmake --build "$HOST_BUILD" --target rexglue -j "$JOBS"
    HOST_REXGLUE="$(find "$SDK_SRC/out" -path '*linux-*' -name rexglue -type f -perm -u+x | head -1)"
  fi
  [ -x "$HOST_REXGLUE" ] || die "host rexglue not found: $HOST_REXGLUE"
  echo "rexglue: $HOST_REXGLUE"
  CODEGEN="$HOST_REXGLUE"
  if [ "$COMPILE_CHECK" -eq 1 ]; then
    log "Compile check: stand-in for the game code (the APK will not run the game)"
    python3 "$ROOT/scripts/android_stub_codegen.py" "$ROOT"
    CODEGEN="$(type -P true)"
  fi

  # --- Native libraries ------------------------------------------------------
  log "Building the native libraries (arm64-v8a, API $API_LEVEL)"
  cmake_args=(
    -S "$ROOT" -B "$TARGET_BUILD" -G Ninja
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API_LEVEL" -DANDROID_STL=c++_shared
    -DCMAKE_BUILD_TYPE=Release
    -DREXGLUE_ENABLE_TRACY=OFF
    -DREXGLUE_RECOMP_DEBUG_INFO=none
    -DREXGLUE_CODEGEN_EXECUTABLE="$CODEGEN"
    "${LAUNCHER_ARGS[@]}"
  )
  if [ -n "${BURSTLIMIT_VERSION_OVERRIDE:-}" ]; then
    cmake_args+=(-DBURSTLIMIT_VERSION_OVERRIDE="$BURSTLIMIT_VERSION_OVERRIDE")
  fi
  cmake "${cmake_args[@]}"
  # Codegen first (it writes generated/default/sources.cmake), then configure
  # again so the generated sources are part of the build.
  cmake --build "$TARGET_BUILD" --target burstlimit_codegen -j "$JOBS"
  cmake "$TARGET_BUILD" >/dev/null
  cmake --build "$TARGET_BUILD" --target burstlimit -j "$JOBS"

  log "Staging the native libraries"
  rm -rf "$LIBS" && mkdir -p "$LIBS"
  SDK_OUT="$SDK_SRC/out/android-arm64"
  for lib in "$TARGET_BUILD/libmain.so" "$SDK_OUT/librexruntime.so" "$SDK_OUT/librexgpu-xenos.so" \
             "$NDK_SYSROOT_LIB/libc++_shared.so"; do
    [ -f "$lib" ] || die "missing $lib"
    "$NDK_BIN/llvm-strip" --strip-unneeded -o "$LIBS/$(basename "$lib")" "$lib"
  done
  ls -la "$LIBS"
fi

[ -f "$LIBS/libmain.so" ] || die "no native libraries in $LIBS (run without --apk-only first)"

# --- APK ----------------------------------------------------------------------
log "Packaging the APK"
WORK="$OUT/apk-work"
rm -rf "$WORK" && mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"

# The manifest names no package (Gradle sets it from the namespace).
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$APP/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

"$BUILD_TOOLS/aapt2" compile --dir "$APP/res" -o "$WORK/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$WORK/base.apk" -I "$PLATFORM_JAR" \
  --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version "$API_LEVEL" --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" --auto-add-overlay "$WORK/res.zip"

SDL_JAVA="$SDK_SRC/thirdparty/sdl3/android-project/app/src/main/java"
find "$APP/java" "$SDL_JAVA" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac -nowarn -encoding UTF-8 -source 17 -target 17 -Xlint:-options \
  -classpath "$PLATFORM_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
mapfile -t classes < <(find "$WORK/classes" -name '*.class')
"$BUILD_TOOLS/d8" --release --min-api "$API_LEVEL" --lib "$PLATFORM_JAR" \
  --output "$WORK/dex" "${classes[@]}"

cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$WORK/dex" && zip -q -X "$WORK/unsigned.apk" classes*.dex)
# Native libraries are stored (not compressed) so they stay page aligned.
(cd "$OUT" && zip -q -X -0 "$WORK/unsigned.apk" lib/arm64-v8a/*.so)
"$BUILD_TOOLS/zipalign" -f -P 16 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

KEYSTORE="${APK_KEYSTORE:-$OUT/burstlimit.keystore}"
KEY_ALIAS="${APK_KEY_ALIAS:-burstlimit}"
KEY_PASS="${APK_KEYSTORE_PASS:-burstlimit}"
if [ ! -f "$KEYSTORE" ]; then
  echo "Making a signing key: $KEYSTORE (keep it: updates must be signed with the same key)"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DBZ Burst Limit Recompiled" >/dev/null 2>&1
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT/$APK_NAME" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$OUT/$APK_NAME"

log "Done: $OUT/$APK_NAME"
ls -la "$OUT/$APK_NAME"
echo "Install: adb install -r \"$OUT/$APK_NAME\" (or copy it to the phone and open it)"
