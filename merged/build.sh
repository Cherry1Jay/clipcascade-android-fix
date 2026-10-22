#!/usr/bin/env bash
# 把后台剪贴板桥合并进 ClipCascade 官方 APK，产出单一可安装包。
#
# 用法：./build.sh <输入 APK> [输出 APK]
#   ./build.sh ~/Downloads/ClipCascade.apk out/clipcascade-merged.apk
#
# 依赖：JDK、Android build-tools（d8/zipalign/apksigner）、platform android.jar、apktool.jar
set -euo pipefail

SRC_APK="${1:?用法: build.sh <输入 APK> [输出 APK]}"
OUT_APK="${2:-out/clipcascade-merged.apk}"

HERE="$(cd "$(dirname "$0")" && pwd)"
BT="${BUILD_TOOLS:-/home/jay/android-bt/android-16}"
AJ="${ANDROID_JAR:-/home/jay/android-bt/platform/android-36/android.jar}"
APKTOOL="${APKTOOL_JAR:-/tmp/apktool.jar}"
KS="${KEYSTORE:-$HERE/../lsposed-hook/keystore.jks}"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.0-ccbridge}"

WORK="$HERE/build/work"
DEXSRC="$HERE/build/dex"
rm -rf "$WORK" "$DEXSRC"
mkdir -p "$WORK" "$DEXSRC" "$(dirname "$OUT_APK")"

echo "==> 1/6 编译 BridgeProvider"
javac -source 8 -target 8 -nowarn -bootclasspath "$AJ" \
      -d "$WORK/classes" "$HERE/src/com/jay/ccbridge/BridgeProvider.java" 2>&1 \
  | grep -v "bootstrap class path" || true
"$BT/d8" --lib "$AJ" --min-api 26 --output "$DEXSRC" \
      "$WORK"/classes/com/jay/ccbridge/*.class

echo "==> 2/6 反编译原 APK（保留 dex/lib 原样）"
java -jar "$APKTOOL" d -s -f -o "$WORK/apk" "$SRC_APK" >/dev/null

echo "==> 3/6 注入 provider 声明"
python3 - "$WORK/apk/AndroidManifest.xml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
if "ccbridge" in s:
    print("    provider 已存在，跳过")
else:
    node = ('        <provider android:authorities="com.clipcascade.ccbridge"'
            ' android:exported="false" android:initOrder="-1000"'
            ' android:name="com.jay.ccbridge.BridgeProvider"/>\n')
    s = s.replace("    </application>", node + "    </application>", 1)
    open(p, "w", encoding="utf-8").write(s)
    print("    provider 已插入")
PY

echo "==> 4/6 版本号 + 放入桥接 dex"
python3 - "$WORK/apk/apktool.yml" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import sys, re
p, vc, vn = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(p, encoding="utf-8").read()
s = re.sub(r"(versionInfo:\n  versionCode: )\S+", r"\g<1>" + vc, s)
s = re.sub(r"(versionInfo:\n  versionCode: \S+\n  versionName: )\S+", r"\g<1>" + vn, s)
open(p, "w", encoding="utf-8").write(s)
PY
cp "$DEXSRC/classes.dex" "$WORK/apk/classes3.dex"
cp "$HERE/../prebuilt/clip.dex" "$WORK/apk/assets/clip.dex"

echo "==> 5/6 重新打包"
java -jar "$APKTOOL" b "$WORK/apk" -o "$WORK/unsigned.apk" >/dev/null

echo "==> 6/6 对齐 + 签名"
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
      --out "$OUT_APK" "$WORK/aligned.apk"
"$BT/apksigner" verify "$OUT_APK"
ls -lh "$OUT_APK"
echo "完成：$OUT_APK"
