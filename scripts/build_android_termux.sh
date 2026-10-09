#!/usr/bin/env bash
# Builds the Android APK on the phone itself, in Termux (no PC needed).
#
#   scripts/build_android_termux.sh                    uses game_data_root/default.xex
#   scripts/build_android_termux.sh --iso <game.iso>   takes default.xex out of the ISO first
#   scripts/build_android_termux.sh --apk-only         repackage from the last native build
#
# The phone runs the recompiler on default.xex and compiles the game code
# with Termux's clang, like the PC build does. default.xex never leaves the
# phone. The APK built here asks for the ISO / game folder on its first
# start, like any other build. See README-android.md ("Building on the phone
# with Termux") for the packages to install first.
#
# Environment:
#   JOBS                        parallel compile jobs (default: from the free RAM)
#   APK_KEYSTORE, APK_KEY_ALIAS, APK_KEYSTORE_PASS
#                               signing key; without it one is made in
#                               ~/.burstlimit/burstlimit.keystore and reused
#   APK_VERSION_NAME, APK_VERSION_CODE
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_SRC="$ROOT/thirdparty/rexglue-sdk"
OUT="$ROOT/out/android"
BUILD="$ROOT/out/build/termux"
APP="$ROOT/android/app/src/main"
STATE="$HOME/.burstlimit"
API_LEVEL=29
APK_NAME="DBZ-Burst-Limit-Recompiled-android.apk"
VERSION_NAME="${APK_VERSION_NAME:-0.4.0-android}"
VERSION_CODE="${APK_VERSION_CODE:-$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 1)}"
PACKAGE="com.dbzburstlimit.recompiled"
# Android 15 platform (android.jar, API 35) from Google's SDK repository.
PLATFORM_ZIP_URL="https://dl.google.com/android/repository/platform-35_r02.zip"
US_XEX_SHA1="aec598f88cf51181fc377b148e0b1ad30db4485c"

log() { printf '\n==> %s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

APK_ONLY=0
ISO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk-only) APK_ONLY=1 ;;
    --iso) shift; ISO="${1:-}"; [ -n "$ISO" ] || die "--iso needs the path of the ISO" ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
  shift
done

# --- Checks -------------------------------------------------------------------
[ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] && [[ "$PREFIX" == *com.termux* ]] ||
  die "this script is for Termux (on a PC, use scripts/build_android.sh)"
[ "$(uname -m)" = "aarch64" ] || die "an arm64 (aarch64) phone is needed, this is $(uname -m)"
# tool:package
missing=()
packages=()
for entry in git:git cmake:cmake ninja:ninja clang:clang clang++:clang python3:python \
             javac:openjdk-21 keytool:openjdk-21 java:openjdk-21 d8:d8 aapt2:aapt2 \
             apksigner:apksigner llvm-strip:llvm llvm-readelf:llvm curl:curl unzip:unzip; do
  tool="${entry%%:*}"
  package="${entry#*:}"
  if ! command -v "$tool" >/dev/null; then
    missing+=("$tool")
    case " ${packages[*]} " in *" $package "*) ;; *) packages+=("$package") ;; esac
  fi
done
[ ${#missing[@]} -eq 0 ] || die "missing: ${missing[*]}
Install them with (one at a time, so one failing package doesn't stop the others):
  for p in ${packages[*]}; do pkg install -y \"\$p\" || echo \">>> FAILED: \$p\"; done"
LIBCXX="$PREFIX/lib/libc++_shared.so"
[ -f "$LIBCXX" ] || die "$LIBCXX not found (pkg install libc++)"

if [ -z "${JOBS:-}" ]; then
  # Recompiled game files can take 2-3 GB of RAM each to compile.
  mem_kb="$(awk '/MemAvailable/ {print $2}' /proc/meminfo)"
  JOBS=$(( mem_kb / (2600 * 1024) ))
  [ "$JOBS" -ge 1 ] || JOBS=1
  [ "$JOBS" -le "$(nproc)" ] || JOBS="$(nproc)"
fi
echo "Compile jobs: $JOBS (set JOBS=... to change; fewer if the build gets killed)"

mkdir -p "$OUT" "$STATE"
LIBS="$OUT/lib/arm64-v8a"

# --- android.jar ----------------------------------------------------------------
PLATFORM_JAR="$STATE/android-35.jar"
if [ ! -f "$PLATFORM_JAR" ]; then
  log "Downloading the Android 15 SDK platform (android.jar, ~60 MB)"
  tmp="$(mktemp -d)"
  curl -fL --retry 3 -o "$tmp/platform.zip" "$PLATFORM_ZIP_URL" ||
    die "download failed: $PLATFORM_ZIP_URL"
  jar_path="$(unzip -Z1 "$tmp/platform.zip" | grep '/android\.jar$' | head -1)"
  [ -n "$jar_path" ] || die "no android.jar in $PLATFORM_ZIP_URL"
  unzip -q -o "$tmp/platform.zip" "$jar_path" -d "$tmp"
  mv "$tmp/$jar_path" "$PLATFORM_JAR"
  rm -rf "$tmp"
fi

if [ "$APK_ONLY" -eq 0 ]; then
  # --- Game executable ----------------------------------------------------------
  XEX="$ROOT/game_data_root/default.xex"
  if [ -n "$ISO" ]; then
    log "Taking default.xex out of $ISO"
    python3 "$ROOT/scripts/xiso_extract.py" "$ISO" "$ROOT/game_data_root" default.xex
  fi
  [ -f "$XEX" ] || die "game_data_root/default.xex not found. Copy it there, or pass --iso <game.iso>."
  sha="$(sha1sum "$XEX" | cut -d' ' -f1)"
  [ "$sha" = "$US_XEX_SHA1" ] ||
    die "default.xex is not the US (NTSC-U) version (SHA-1 $sha): only the US version works"
  # Left over from a --compile-check on another machine.
  if [ -f "$ROOT/generated/default/burstlimit_stub_funcs.cpp" ]; then
    rm -rf "$ROOT/generated/default"
  fi

  # --- SDK Android patch --------------------------------------------------------
  log "SDK Android patch"
  "$ROOT/scripts/apply_sdk_patch.sh"

  # --- Native build -------------------------------------------------------------
  # Termux's CMake builds natively for Android, so the SDK's own rexglue
  # (built in the same tree) runs the codegen here.
  log "Configuring (first time: a few minutes)"
  cmake -S "$ROOT" -B "$BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DREXGLUE_ENABLE_TRACY=OFF \
    -DREXGLUE_RECOMP_DEBUG_INFO=none \
    -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
    -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"

  log "Recompiling the game code from default.xex (rexglue codegen)"
  cmake --build "$BUILD" --target burstlimit_codegen -j "$JOBS"
  cmake "$BUILD" >/dev/null

  log "Compiling (the long part: can take an hour or more; keep the phone charging)"
  cmake --build "$BUILD" --target burstlimit -j "$JOBS"

  log "Staging the native libraries"
  rm -rf "$LIBS" && mkdir -p "$LIBS"
  SDK_OUT="$SDK_SRC/out/android-arm64"
  for lib in "$BUILD/libmain.so" "$SDK_OUT/librexruntime.so" "$SDK_OUT/librexgpu-xenos.so" "$LIBCXX"; do
    [ -f "$lib" ] || die "missing $lib"
    llvm-strip --strip-unneeded -o "$LIBS/$(basename "$lib")" "$lib"
  done
  # Everything they load must come with Android or the APK, not with Termux.
  allowed=" libc.so libm.so libdl.so liblog.so libandroid.so libOpenSLES.so libc++_shared.so librexruntime.so "
  for lib in "$LIBS"/*.so; do
    for needed in $(llvm-readelf -d "$lib" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do
      case "$allowed" in
        *" $needed "*) ;;
        *) die "$(basename "$lib") needs $needed, which is from Termux, not Android" ;;
      esac
    done
  done
  ls -la "$LIBS"
fi

[ -f "$LIBS/libmain.so" ] || die "no native libraries in $LIBS (run without --apk-only first)"

# --- APK ----------------------------------------------------------------------
log "Packaging the APK"
WORK="$OUT/apk-work"
rm -rf "$WORK" && mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex"
sed "s|<manifest |<manifest package=\"$PACKAGE\" |" "$APP/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

aapt2 compile --dir "$APP/res" -o "$WORK/res.zip"
aapt2 link -o "$WORK/base.apk" -I "$PLATFORM_JAR" \
  --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version "$API_LEVEL" --target-sdk-version 35 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" --auto-add-overlay "$WORK/res.zip"

SDL_JAVA="$SDK_SRC/thirdparty/sdl3/android-project/app/src/main/java"
find "$APP/java" "$SDL_JAVA" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac -nowarn -encoding UTF-8 -source 17 -target 17 -Xlint:-options \
  -classpath "$PLATFORM_JAR" -d "$WORK/classes" @"$WORK/sources.txt"
mapfile -t classes < <(find "$WORK/classes" -name '*.class')
d8 --release --min-api "$API_LEVEL" --lib "$PLATFORM_JAR" \
  --output "$WORK/dex" "${classes[@]}"

cp "$WORK/base.apk" "$WORK/unsigned.apk"
(cd "$WORK/dex" && python3 -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); [z.write(f) for f in sys.argv[2:]]" "$WORK/unsigned.apk" classes*.dex)
(cd "$OUT" && python3 -c "import sys,zipfile; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_STORED); [z.write(f) for f in sys.argv[2:]]" "$WORK/unsigned.apk" lib/arm64-v8a/*.so)
python3 "$ROOT/scripts/apk_align.py" "$WORK/unsigned.apk" "$WORK/aligned.apk"

KEYSTORE="${APK_KEYSTORE:-$STATE/burstlimit.keystore}"
KEY_ALIAS="${APK_KEY_ALIAS:-burstlimit}"
KEY_PASS="${APK_KEYSTORE_PASS:-burstlimit}"
if [ ! -f "$KEYSTORE" ]; then
  echo "Making a signing key: $KEYSTORE (keep it: updates must be signed with the same key)"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=DBZ Burst Limit Recompiled" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
  --out "$OUT/$APK_NAME" "$WORK/aligned.apk"
apksigner verify "$OUT/$APK_NAME"

log "Done: $OUT/$APK_NAME"
if [ -d "$HOME/storage/downloads" ]; then
  cp "$OUT/$APK_NAME" "$HOME/storage/downloads/"
  echo "Copied to Downloads: open $APK_NAME in the file manager to install it."
else
  echo "Run termux-setup-storage and this again with --apk-only to get a copy in Downloads,"
  echo "or: termux-open \"$OUT/$APK_NAME\""
fi
