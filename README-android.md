# Android port (experimental)

An arm64 Android build of Dragon Ball Z: Burst Limit Recompiled: the same recompiled game code and runtime as
the PC version, with the Vulkan renderer, SDL3 for the window, audio and controllers, and an Android app around
it that sets up the game files.

> **Status: untested on a real device.** The port builds, and the parts that could be tested off-device were:
> the PowerPC instruction test suite passes on ARM64 (1462 cases), the AArch64 fiber switch, the ISO extractor,
> the APK packaging. Whether a given phone's Vulkan driver runs the Xenos renderer well enough is the big open
> question. Logs and reports are welcome (see [Logs](#logs)).

---

## How it works

The game code is translated ahead of time from the US `default.xex` into C++ and compiled for arm64, exactly like
the PC build. So:

- **Building the APK needs `default.xex`** (once, on the build machine): the recompiler reads it. Unlike
  decompilation projects (Ship of Harkinian and other HarbourMaster ports), where the game logic is already source
  code and the ROM only provides assets, here the game code itself comes from the `.xex`.
- **Using the APK needs your disc image**: on the first start the app asks for the Xbox 360 **ISO** (or a folder
  you already extracted), checks that it is the US version, and extracts the whole game partition (`default.xex`,
  `LONG2DATA/...`) into the app's storage. The `.xex` there is what the runtime loads (its data and imports); the
  CPK files hold the assets.

The APK contains no game data.

---

## Requirements (to play)

- Android 10 or newer, **arm64** (64-bit) device
- **Vulkan 1.1** or newer (the app warns when the device doesn't report it)
- Free space for the game files (about the size of the game partition of the disc, several GB)
- Your own **US (NTSC-U)** Xbox 360 disc image (`.iso`, full redump-style image or `extract-xiso` "XISO"), or the
  folder extracted from it
- A controller is recommended (Bluetooth / USB, any that Android sees as a gamepad); there is an on-screen
  controller too

---

## Installing and first start

1. Install the APK (`adb install -r DBZ-Burst-Limit-Recompiled-android.apk`, or open it on the phone and allow
   installing from that source).
2. Open **DBZ Burst Limit**. Tap **Select ISO (Xbox 360)** and pick your disc image, or **Select extracted
   folder** and pick the folder that contains `default.xex` and `LONG2DATA`.
3. The app checks `default.xex` first (SHA-1 `aec598f88cf51181fc377b148e0b1ad30db4485c`, US version) and stops
   right away with another version. Then it extracts or copies everything, with a progress bar. Keep the app in
   front while it works.
4. Tap **Play**.

Where things go (no storage permission needed; reachable over USB under `Android/data`):

| Path | Contents |
|---|---|
| `Android/data/com.dbzburstlimit.recompiled/files/game_data_root` | Game files |
| `Android/data/com.dbzburstlimit.recompiled/files/user` | `burstlimit.toml` (settings), saves, shader cache, `burstlimit.log` |
| `Android/data/com.dbzburstlimit.recompiled/files/textures` | Texture packs (`textures/replace`) and dumps |

The launcher also has the on-screen controller switch and opacity, and an **Extra settings** field for any
setting from the [settings table](README.md#settings) as a command-line option, for example
`--frame_rate=60 --draw_resolution_scale_x=1 --draw_resolution_scale_y=1`.

### Controls

- A connected controller works as on PC: **Back + Start** opens the settings menu.
- The **on-screen controller** (left stick, D-pad, A/B/X/Y, LB/RB, LT/RT, Back/Start) is a virtual Xbox 360 pad.
  It hides itself while a physical controller is connected and comes back when it disconnects. Fingers can slide
  from one button to another.

### Logs

`files/user/burstlimit.log` (path above), or live with `adb logcat -s rexglue SDL`. A crash report needs the
log plus the phone model and Android version.

---

## Building the APK

On Linux (tested on Ubuntu 24.04; Arch Linux works the same way). The build machine runs the recompiler, so the
SDK's codegen tool is built for it first, then everything else is cross-compiled with the NDK.

### What you need

- `game_data_root/default.xex` from your game (US version), as for the PC build
- CMake 3.25+, Ninja, Git, clang/clang++ **20 or newer** (for the build machine's codegen tool), a JDK 17+
- The Android SDK command-line tools with: `platforms;android-35` (or newer), `build-tools;35.0.0` (or newer),
  `ndk;29.0.14206865` (r28 or newer)
- The X11 / Wayland development files SDL3 needs to configure on the build machine (codegen tool build)

On Arch Linux:

```sh
sudo pacman -S --needed git cmake ninja clang lld jdk17-openjdk zip unzip \
  libx11 libxcursor libxext libxrandr libxi libxss libxtst libxkbcommon wayland wayland-protocols \
  libdecor alsa-lib libpulse pipewire
# Android SDK: android-studio, or the AUR package android-sdk-cmdline-tools-latest, then:
sdkmanager --sdk_root="$HOME/Android/Sdk" "platforms;android-35" "build-tools;35.0.0" "ndk;29.0.14206865"
```

### Build

```sh
git clone <this repository>
cd DBZ-Burst-Limit-Recompiled
scripts/android_submodules.sh   # or: git submodule update --init --recursive
cp /path/to/your/game/default.xex game_data_root/
ANDROID_HOME="$HOME/Android/Sdk" scripts/build_android.sh
```

The APK is `out/android/DBZ-Burst-Limit-Recompiled-android.apk`. The script:

1. Applies `android/patches/rexglue-sdk-android.patch` to `thirdparty/rexglue-sdk` (the Android support in the
   runtime; the submodule belongs to another repository, so the changes live here as a patch).
2. Builds `rexglue` for the build machine (`out/build/android-host`).
3. Configures for `arm64-v8a` / API 29 with the NDK, runs codegen on `default.xex`, and builds `libmain.so`
   (the game), `librexruntime.so` and `librexgpu-xenos.so` (`out/build/android-arm64`).
4. Packages and signs the APK with the SDK's build tools (aapt2, d8, zipalign, apksigner), no Gradle needed.

`scripts/build_android.sh --apk-only` repackages the APK from the last native build (for changes to the Java
side). Signing: without `APK_KEYSTORE` the script makes `out/android/burstlimit.keystore` on the first build and
reuses it. Keep it: Android only installs an update over an existing install when both are signed with the
same key. Other options are listed at the top of the script.

`scripts/build_android.sh --compile-check` builds everything without `default.xex`, with a stand-in for the game
code (what CI runs without the game): the APK it makes does not run the game.

`android/` is also a normal Gradle project (Android Studio) for the Java side; it takes the native libraries from
the folder given with `-PnativeLibsDir=` (the script stages them in `out/android/lib`).

### Building on the phone with Termux

The phone can do the whole build itself: Termux runs the recompiler on `default.xex` and compiles the game code
with its own clang, so `default.xex` never leaves the phone and no PC is needed. It takes a while (about an hour or
more, depending on the phone) and roughly 10 GB of free space; keep the phone charging.

1. Install **Termux from F-Droid or GitHub** (the Play Store version is outdated), open it and run:

   ```sh
   termux-setup-storage
   pkg update -y && pkg upgrade -y
   pkg install -y git cmake ninja clang lld llvm python openjdk-21 aapt aapt2 apksigner d8 curl unzip libc++
   termux-wake-lock
   ```

2. Get the code (only the dependencies the Android build uses, shallow):

   ```sh
   cd ~
   git clone --depth 1 -b <branch> https://github.com/higorhgon/DBZ-Burst-Limit-Recompiled.git
   cd DBZ-Burst-Limit-Recompiled
   scripts/android_submodules.sh
   ```

3. Build, giving it your ISO (it takes `default.xex` out of it), or with `default.xex` copied to
   `game_data_root/`:

   ```sh
   scripts/build_android_termux.sh --iso ~/storage/downloads/<your game>.iso
   # or: mkdir -p game_data_root && cp ~/storage/downloads/default.xex game_data_root/ && scripts/build_android_termux.sh
   ```

4. The APK is copied to **Downloads**: open it in the file manager to install it. On its first start it asks for the
   ISO (or extracted folder) as usual.

If the build gets killed (the phone ran out of memory), run it again with fewer jobs: `JOBS=1
scripts/build_android_termux.sh` (it continues where it stopped). The signing key is made in
`~/.burstlimit/burstlimit.keystore`: keep it to install later builds over this one. `scripts/xiso_extract.py
game.iso out_dir` extracts a disc image on any machine with Python.

### GitHub Actions

Two workflows build the APK on GitHub (both use `.github/workflows/android-build.yml`):

| Workflow | When | Result |
|---|---|---|
| **Android** (`android.yml`) | Every push (any branch, not README-only changes), and pull requests from forks | The APK as an **artifact** of the run (kept 14 days) |
| **Android release** (`android-release.yml`) | By hand: Actions > Android release > Run workflow, with a tag (e.g. `v0.4.0-android.1`) | A GitHub **release** with the APK and its SHA-256 |

The recompiler needs `default.xex`, which never goes in the repository. The workflows download it from a
private place given in the secrets below (Settings > Secrets and variables > Actions > New repository secret).
Without `GAME_XEX_URL` (forks, pull requests from forks, or before you set it), the commit build runs
`scripts/build_android.sh --compile-check` instead: everything is compiled and packaged with a stand-in for the
game code, which checks the commit, but no APK is published. A release always needs the real build.

| Secret | Needed for | Value |
|---|---|---|
| `GAME_XEX_URL` | playable APKs, releases | Direct download URL of your `default.xex` (US). Checked against the SHA-1 above. |
| `GAME_XEX_AUTH_HEADER` | when the URL needs a login | One HTTP header, e.g. `Authorization: Bearer <token>` |
| `APK_KEYSTORE_BASE64` | releases (optional for commit builds) | The signing key, base64-encoded |
| `APK_KEYSTORE_PASS` | with the key | Its password |
| `APK_KEY_ALIAS` | with the key | Its alias |

**Hosting `default.xex` privately**: one way is a release asset in a **private** repository of your own:

1. Create a private repository (e.g. `burstlimit-private`), make a release in it, and attach `default.xex`.
2. Get the asset's API URL:
   `gh api repos/<you>/burstlimit-private/releases/latest --jq '.assets[] | "\(.url) \(.name)"'`
   (`https://api.github.com/repos/<you>/burstlimit-private/releases/assets/<id>`). That is `GAME_XEX_URL`.
3. Make a fine-grained personal access token with read access to **Contents** of that repository only, and set
   `GAME_XEX_AUTH_HEADER` to `Authorization: Bearer <token>`.

Anyone who has the URL and the header can download the file, so keep both in secrets only.

**Signing key** (make it once and keep a backup: Android installs an update only when it is signed with the same
key as the installed app):

```sh
keytool -genkeypair -keystore burstlimit-release.keystore -alias burstlimit \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=DBZ Burst Limit Recompiled"
base64 -w0 burstlimit-release.keystore   # -> APK_KEYSTORE_BASE64
```

Without the key, commit builds are signed with a throwaway key: they install fine, but not over a release install
(uninstall first, which deletes the extracted game files and saves of the app). The version code is the commit
count of the branch, so a newer build always installs over an older one with the same key.

> The APK holds the recompiled game code (no game data). The run artifacts of a public repository can be downloaded
> by any signed-in GitHub user, and releases by everyone, as with the PC builds' releases.

---

## What the port changes

In the runtime (the SDK patch):

- Android window surface for the Vulkan presenter (`ANativeWindow` from SDL3), SDL3 set up for Android (AAudio /
  OpenSL ES, HIDAPI, sensors), `SDL_main` entry point
- Fibers on AArch64 without `ucontext` (bionic has none): a small assembly context switch
- No robust mutexes (bionic), libc++ `clock_cast` fallback, Android shared memory, logs to logcat
- A 64 KB allocation granularity on Android, so the guest's 4 KB-page physical heap works on 4 KB **and** 16 KB
  page devices (the same 0x1000 offset Windows uses, matched in the recompiled code)
- Paths: the GPU plugin from the app's native library folder, the config with the user data
- POSIX fixes for the online code (socket errors), which the Linux build needs too

In this repository: `src/burstlimit_android.cpp` (the on-screen controller as an SDL virtual gamepad), the
Android library target, a cross-build option for codegen (`REXGLUE_CODEGEN_EXECUTABLE`), the Android app
(`android/`) and `scripts/build_android.sh`.

### Known limits

- Performance on phones is unknown; start with the defaults (720p, 30 FPS cap) and raise them in the settings
  menu.
- Online play: the lobby client is Windows-only for now, so the lobby (`online_lobby_url`) is not available on
  Android yet.
- NVIDIA DLSS and AMD FSR 4 are D3D12-only and not on Android; FSR 1 / CAS (`present_effect`) are.
