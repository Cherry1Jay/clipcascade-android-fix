#!/bin/bash
# 构建 com.jay.cchook —— 让 ClipCascade 在后台也能同步剪贴板的 LSPosed 模块
#
# 依赖（都不需要 Android Studio）：
#   javac / d8 / aapt / zipalign / apksigner   —— 来自 build-tools（本机：/home/jay/android-bt/android-16）
#   android.jar                                —— platform-36（本机：/home/jay/android-bt/platform/android-36）
#   libxposed api 102 classes.jar              —— /tmp/libxp/classes.jar
#     curl -LO https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
#     unzip api-102.0.0.aar classes.jar
#   签名用自签 keystore（自己 genkeypair 一个即可，包名不变、签名不变才能覆盖安装）
set -e
cd "$(dirname "$0")"

AJ=${AJ:-/home/jay/android-bt/platform/android-36/android.jar}
BT=${BT:-/home/jay/android-bt/android-16}
API=${API:-/tmp/libxp/classes.jar}
KS=${KS:-./keystore.jks}
KSPASS=${KSPASS:-android}
VER=${1:-1}

[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KSPASS" -keypass "$KSPASS" \
    -alias cchook -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=CCHook,O=Jay,C=CN"

sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER\"/" AndroidManifest.xml

rm -rf build && mkdir -p build/classes build/dex
javac -source 8 -target 8 -nowarn -cp "$API:$AJ" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --lib "$API" --min-api 26 --output build/dex build/classes/com/jay/cchook/*.class
"$BT/aapt" package -f -M AndroidManifest.xml -A assets -I "$AJ" -F build/base.apk

python3 - <<'EOF'
import zipfile
with zipfile.ZipFile('build/base.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
    for f in ('module.prop', 'java_init.list', 'scope.list'):
        z.write('META-INF/xposed/' + f, 'META-INF/xposed/' + f)
EOF

"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
    --out build/cchook.apk build/aligned.apk
echo "built: build/cchook.apk"
