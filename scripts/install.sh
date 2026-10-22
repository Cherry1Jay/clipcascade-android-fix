#!/usr/bin/env bash
# 一键安装：推 dex + 部署桥接脚本 + 装 systemd 用户服务 + 自检。
# 用法：
#   ./scripts/install.sh                 # 自动发现设备
#   ./scripts/install.sh 192.168.1.23:5555
#   ./scripts/install.sh ABC123DEF        # USB 序列号
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-}"
DEST="$HOME/.local/share/clipcascade"
UNIT="$HOME/.config/systemd/user/clipcascade-phone-bridge.service"
DEX_ON_PHONE="/data/local/tmp/clip.dex"

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m!!\033[0m %s\n' "$*" >&2; exit 1; }

# --- 0) 前置检查 ---
command -v adb >/dev/null || die "没装 adb（Arch: pacman -S android-tools）"
command -v wl-copy >/dev/null || command -v xclip >/dev/null \
  || die "需要 wl-clipboard（Wayland）或 xclip（X11）"
[ -f "$ROOT/prebuilt/clip.dex" ] || die "找不到 prebuilt/clip.dex，先跑 ./scripts/build-dex.sh"

# --- 1) 确定设备 ---
if [ -n "$TARGET" ]; then
  case "$TARGET" in
    *:*) adb connect "$TARGET" >/dev/null 2>&1 || true ;;
  esac
  SERIAL="$TARGET"
else
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
  [ -n "$SERIAL" ] || die "没有已授权的设备。先 adb devices 确认（手机上要点「允许调试」）"
fi
say "目标设备: $SERIAL"
adb -s "$SERIAL" shell true >/dev/null 2>&1 || die "连不上 $SERIAL"

# --- 2) 推 dex ---
say "推送 clip.dex"
adb -s "$SERIAL" push "$ROOT/prebuilt/clip.dex" "$DEX_ON_PHONE" >/dev/null
adb -s "$SERIAL" shell chmod 644 "$DEX_ON_PHONE"

# --- 3) 自检：binder 能不能读写 ---
say "自检（写入并读回）"
OUT="$(adb -s "$SERIAL" shell "CLASSPATH=$DEX_ON_PHONE app_process /system/bin com.jay.readclip.Clip diag 'install-check'" 2>&1 | tr -d '\r')"
echo "$OUT"
echo "$OUT" | grep -q "read1: T:" || {
  echo
  echo "读回为空。常见原因："
  echo "  1. 手机正锁屏 —— Android 会隐藏剪贴板。先解锁再试。"
  echo "     adb -s $SERIAL shell dumpsys window | grep mDreamingLockscreen"
  echo "  2. 该 ROM 的 IClipboard 方法签名不同（本项目按 4 参数签名实现）。"
  exit 1
}

# --- 4) 部署脚本与单元 ---
say "部署到 $DEST"
mkdir -p "$DEST" "$(dirname "$UNIT")"
install -m 755 "$ROOT/src/phone_bridge.py" "$DEST/phone_bridge.py"
install -m 644 "$ROOT/systemd/clipcascade-phone-bridge.service" "$UNIT"

# 把设备写进单元（按目标类型选变量）
if printf '%s' "$SERIAL" | grep -q ':'; then
  sed -i "s|^Environment=BRIDGE_WIFI_ADDR=.*|Environment=BRIDGE_WIFI_ADDR=$SERIAL|" "$UNIT"
else
  sed -i "s|^Environment=BRIDGE_USB_SERIAL=.*|Environment=BRIDGE_USB_SERIAL=$SERIAL|" "$UNIT"
fi

systemctl --user daemon-reload
systemctl --user enable --now clipcascade-phone-bridge

sleep 5
systemctl --user is-active clipcascade-phone-bridge >/dev/null \
  || { journalctl --user -u clipcascade-phone-bridge -n 20 --no-pager; die "服务没起来"; }

say "已启动。实时日志："
echo "    journalctl --user -u clipcascade-phone-bridge -f"
echo
say "现在在手机上复制一段文字，桌面剪贴板应在一秒内变成它。"
echo "    桌面端 ClipCascade 必须同时在运行，反向同步才完整。"
