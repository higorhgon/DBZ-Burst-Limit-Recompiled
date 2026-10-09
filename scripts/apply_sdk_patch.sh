#!/usr/bin/env bash
# Applies android/patches/rexglue-sdk-android.patch to thirdparty/rexglue-sdk.
# Nothing to do when it is already applied; an earlier version of the patch
# (from this repository's history) is swapped for the current one.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_SRC="$ROOT/thirdparty/rexglue-sdk"
PATCH_REL="android/patches/rexglue-sdk-android.patch"
PATCH="$ROOT/$PATCH_REL"

[ -f "$SDK_SRC/CMakeLists.txt" ] || {
  echo "error: SDK submodule missing: scripts/android_submodules.sh" >&2
  exit 1
}
if git -C "$SDK_SRC" apply --reverse --check "$PATCH" 2>/dev/null; then
  echo "SDK Android patch: already applied"
  exit 0
fi
if git -C "$SDK_SRC" apply --check "$PATCH" 2>/dev/null; then
  git -C "$SDK_SRC" apply --whitespace=nowarn "$PATCH"
  echo "SDK Android patch: applied"
  exit 0
fi
# An earlier version applied? Take it off, then apply the current one.
old="$(mktemp)"
trap 'rm -f "$old"' EXIT
for commit in $(git -C "$ROOT" log --format=%H -- "$PATCH_REL" 2>/dev/null); do
  git -C "$ROOT" show "$commit:$PATCH_REL" > "$old" 2>/dev/null || continue
  if git -C "$SDK_SRC" apply --reverse --check "$old" 2>/dev/null; then
    git -C "$SDK_SRC" apply --reverse "$old"
    git -C "$SDK_SRC" apply --whitespace=nowarn "$PATCH"
    echo "SDK Android patch: updated (replaced the version from ${commit:0:7})"
    exit 0
  fi
done
cat >&2 <<MSG
error: the SDK Android patch doesn't apply: the SDK submodule is at another commit or holds
other changes. To start over (drops changes in the SDK's src/ and include/):
  git -C thirdparty/rexglue-sdk checkout . && git -C thirdparty/rexglue-sdk clean -fd src include
MSG
exit 1
