#!/usr/bin/env bash
# 合并产物的静态冒烟检查。
#
# 目的：上游改结构时（类名、事件名、清单格式、资产名）让流水线**尽早失败**，
# 而不是产出一个「装得上但后台不同步」的包 —— 那种失败在 CI 里看不出来。
#
# 用法：verify-merged.sh <合并后的 APK>
# 可选环境变量：BUILD_TOOLS、EXPECT_VERSION_NAME、EXPECT_VERSION_CODE
set -euo pipefail

APK="${1:?用法: verify-merged.sh <合并后的 APK>}"
[ -f "$APK" ] || { echo "::error::找不到 $APK"; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"

AAPT2="$(command -v aapt2 || true)"
if [ -z "$AAPT2" ] && [ -n "${BUILD_TOOLS:-}" ]; then AAPT2="$BUILD_TOOLS/aapt2"; fi
[ -x "$AAPT2" ] || { echo "::error::找不到 aapt2（设置 BUILD_TOOLS 或加入 PATH）"; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0

ok()   { echo "  [ok]   $*"; }
bad()  { echo "  [FAIL] $*"; fail=1; }
note() { echo "  [info] $*"; }

echo "==> 1/5 产物内容"
unzip -l "$APK" > "$TMP/list.txt"
for f in classes3.dex assets/clip.dex; do
  grep -q " $f$" "$TMP/list.txt" && ok "$f 已打入" || bad "$f 缺失"
done

unzip -p "$APK" classes3.dex > "$TMP/classes3.dex"
grep -aq "com/jay/ccbridge/BridgeProvider" "$TMP/classes3.dex" \
  && ok "classes3.dex 是我们的桥接代码" || bad "classes3.dex 里找不到 BridgeProvider"

unzip -p "$APK" assets/clip.dex > "$TMP/clip.dex"
D1=$(md5sum "$TMP/clip.dex" | cut -d' ' -f1)
D2=$(md5sum "$ROOT/prebuilt/clip.dex" | cut -d' ' -f1)
[ "$D1" = "$D2" ] && ok "assets/clip.dex 与 prebuilt 一致 ($D1)" || bad "clip.dex 不一致: $D1 != $D2"

echo "==> 2/5 清单声明"
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" > "$TMP/man.txt" 2>/dev/null || true
grep -q "com.jay.ccbridge.BridgeProvider" "$TMP/man.txt" \
  && ok "provider 已声明" || bad "provider 未声明（build.sh 的注入步骤失效）"
grep -q "com.clipcascade" "$TMP/man.txt" && ok "包名仍是 com.clipcascade" || bad "包名变了"

echo "==> 3/5 版本号"
BADGING="$("$AAPT2" dump badging "$APK" 2>/dev/null | sed -n '1p' || true)"
VNAME=$(printf '%s' "$BADGING" | grep -o "versionName='[^']*'" | cut -d"'" -f2 || true)
VCODE=$(printf '%s' "$BADGING" | grep -o "versionCode='[^']*'" | cut -d"'" -f2 || true)
note "versionName=$VNAME versionCode=$VCODE"
[ -n "$VNAME" ] && [ -n "$VCODE" ] || bad "读不到版本号"
if [ -n "${EXPECT_VERSION_NAME:-}" ] && [ "$VNAME" != "$EXPECT_VERSION_NAME" ]; then
  bad "versionName 期望 $EXPECT_VERSION_NAME，实际 $VNAME"
fi
if [ -n "${EXPECT_VERSION_CODE:-}" ] && [ "$VCODE" != "$EXPECT_VERSION_CODE" ]; then
  bad "versionCode 期望 $EXPECT_VERSION_CODE，实际 $VCODE"
fi

echo "==> 4/5 上游结构（BridgeProvider 运行时依赖的符号）"
# 这些符号在上游 dex 里必须还在，否则反射会全部落空、表现为「装得上但不同步」。
unzip -o -q "$APK" "classes*.dex" -d "$TMP/dex" 2>/dev/null || true
# 排除 classes3.dex（我们自己的桥接），否则它里面的反射字符串会让检查假通过
for f in "$TMP"/dex/classes*.dex; do
  case "$(basename "$f")" in classes3.dex) continue ;; esac
  cat "$f" >> "$TMP/upstream.bin"
done
for sym in getReactNativeHost getReactInstanceManager getCurrentReactContext RCTDeviceEventEmitter onClipboardChange; do
  if grep -aq "$sym" "$TMP/upstream.bin"; then ok "上游仍含 $sym"; else bad "上游已无 $sym —— 反射链路可能失效，需人工适配"; fi
done

echo "==> 5/5 原生库对齐（extractNativeLibs=false 时必须 Stored + 4K）"
if [ -n "${BUILD_TOOLS:-}" ] && [ -x "$BUILD_TOOLS/zipalign" ]; then
  if "$BUILD_TOOLS/zipalign" -c -p 4 "$APK" >/dev/null 2>&1; then
    ok "zipalign -p 4 通过"
  else
    bad "原生库未 4K 对齐，Android 会拒绝安装"
  fi
else
  note "没有 zipalign，跳过对齐检查"
fi

echo
if [ "$fail" -ne 0 ]; then
  echo "::error::冒烟检查未通过，产物不可发布"
  exit 1
fi
echo "全部检查通过：$APK"
