#!/usr/bin/env bash
# 编译 src/Clip.java -> prebuilt/clip.dex，不需要 Android Studio。
# 依赖：JDK 17+（javac）、curl、unzip。
set -euo pipefail

BT_ZIP="build-tools_r36_linux.zip"
PLAT_ZIP="platform-36_r02.zip"
BASE="https://dl.google.com/android/repository"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$HOME/.cache/clip-bridge-sdk}"
mkdir -p "$WORK"
echo "==> 工作目录: $WORK"

# --- 1) build-tools（提供 d8） ---
D8="$(find -L "$WORK/bt" -maxdepth 3 -name d8 -type f 2>/dev/null | head -1 || true)"
if [ -z "$D8" ]; then
  [ -f "$WORK/$BT_ZIP" ] || { echo "==> 下载 $BT_ZIP"; curl -fL --retry 3 -o "$WORK/$BT_ZIP" "$BASE/$BT_ZIP"; }
  echo "==> 解压 build-tools"
  rm -rf "$WORK/bt"; mkdir -p "$WORK/bt"
  unzip -q -o "$WORK/$BT_ZIP" -d "$WORK/bt"
  D8="$(find -L "$WORK/bt" -maxdepth 3 -name d8 -type f | head -1)"
fi
[ -n "$D8" ] && [ -x "$D8" ] || { echo "!! 没找到 d8"; exit 1; }
echo "==> d8: $D8"

# --- 2) platform（提供 android.jar） ---
AJ="$(find -L "$WORK/platform" -name android.jar 2>/dev/null | head -1 || true)"
if [ -z "$AJ" ]; then
  [ -f "$WORK/$PLAT_ZIP" ] || { echo "==> 下载 $PLAT_ZIP"; curl -fL --retry 3 -o "$WORK/$PLAT_ZIP" "$BASE/$PLAT_ZIP"; }
  echo "==> 解压 platform"
  mkdir -p "$WORK/platform"
  unzip -q -o "$WORK/$PLAT_ZIP" -d "$WORK/platform"
  AJ="$(find -L "$WORK/platform" -name android.jar | head -1)"
fi
[ -n "$AJ" ] && [ -f "$AJ" ] || { echo "!! 没找到 android.jar"; exit 1; }
echo "==> android.jar: $AJ"

# --- 3) javac ---
OUT="$WORK/out"; DEXDIR="$WORK/dex"
rm -rf "$OUT" "$DEXDIR"; mkdir -p "$OUT" "$DEXDIR"
echo "==> javac"
javac -source 8 -target 8 -nowarn -cp "$AJ" -d "$OUT" "$ROOT/src/Clip.java"

# --- 4) d8（只打包本类；android.* 由设备系统提供，不进 dex） ---
echo "==> d8"
"$D8" --lib "$AJ" --min-api 21 --output "$DEXDIR" "$OUT/com/jay/readclip/Clip.class"

# --- 5) 产物 ---
mkdir -p "$ROOT/prebuilt"
cp "$DEXDIR/classes.dex" "$ROOT/prebuilt/clip.dex"
echo "==> 完成: $ROOT/prebuilt/clip.dex ($(stat -c%s "$ROOT/prebuilt/clip.dex") bytes)"
echo "==> 推送: adb push prebuilt/clip.dex /data/local/tmp/clip.dex"
