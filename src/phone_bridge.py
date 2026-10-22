#!/usr/bin/env python3
"""ClipCascade 手机 -> 桌面 剪贴板桥。

背景：Android 10+ 的 ClipboardService 会拒绝非前台应用读剪贴板
（logcat: "Denying clipboard access to com.clipcascade, application is not in focus"），
ClipCascade 官方给的 READ_LOGS 方案在 Android 15 上已失效，因此手机复制 -> 桌面这条向是断的。

绕法：ClipboardService 内部对 SHELL_UID(2000) 有硬编码放行（"Shell can access the
clipboard for testing purposes"），而 adb shell 正是 uid 2000。于是在手机侧跑一个
长驻轮询（/data/local/tmp/clip.dex），把剪贴板变化以 "T:<base64>" 逐行回传，
本进程再写进桌面剪贴板 —— 桌面端 ClipCascade 随即把它当本地复制正常上行/落盘。

反向（桌面 -> 手机）由 ClipCascade 自己负责，本脚本不参与；相同内容会被去重，不成环。
"""
import base64
import hashlib
import logging
import os
import shutil
import subprocess
import sys
import time
from logging.handlers import RotatingFileHandler

DEX = "/data/local/tmp/clip.dex"
MAIN_CLASS = "com.jay.readclip.Clip"
POLL_MS = os.environ.get("BRIDGE_POLL_MS", "500")

HOME = os.path.expanduser("~")
LOG_DIR = os.path.join(HOME, ".local/share/clipcascade")
STATE_FILE = os.path.join(LOG_DIR, ".bridge-last-device")
LOG_FILE = os.path.join(LOG_DIR, "phone_bridge.log")

# 候选 adb 地址：上次成功的 > 无线 > USB 序列号
WIFI_ADDR = os.environ.get("BRIDGE_WIFI_ADDR", "")   # 例 192.168.1.23:5555，留空则自动发现
USB_SERIAL = os.environ.get("BRIDGE_USB_SERIAL", "")  # 留空则自动发现

# 桌面会话环境（systemd user 服务里 DISPLAY/WAYLAND_DISPLAY 不一定有）
SESSION_ENV = dict(os.environ)
SESSION_ENV.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
SESSION_ENV.setdefault("DISPLAY", ":0")
SESSION_ENV.setdefault("WAYLAND_DISPLAY", "wayland-0")
SESSION_ENV.setdefault("DBUS_SESSION_BUS_ADDRESS", f"unix:path=/run/user/{os.getuid()}/bus")


def setup_logging():
    os.makedirs(LOG_DIR, exist_ok=True)
    fmt = logging.Formatter("%(asctime)s - %(levelname)s - %(message)s")
    log = logging.getLogger("bridge")
    log.setLevel(logging.INFO)
    fh = RotatingFileHandler(LOG_FILE, maxBytes=1 << 20, backupCount=2, encoding="utf-8")
    fh.setFormatter(fmt)
    log.addHandler(fh)
    sh = logging.StreamHandler(sys.stdout)
    sh.setFormatter(fmt)
    log.addHandler(sh)
    return log


log = setup_logging()


def adb(*args, timeout=15):
    return subprocess.run(["adb", *args], capture_output=True, text=True,
                          timeout=timeout, env=os.environ)


def read_last_device():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return f.read().strip() or None
    except OSError:
        return None


def write_last_device(serial):
    try:
        with open(STATE_FILE, "w", encoding="utf-8") as f:
            f.write(serial)
    except OSError:
        pass


def mdns_candidates():
    """手机换了 IP 时靠 mDNS 发现 adb 服务（adb 自带 mdns 探测）。"""
    try:
        out = adb("mdns", "services", timeout=12).stdout
    except (subprocess.TimeoutExpired, OSError):
        return []
    found = []
    for line in out.splitlines():
        low = line.lower()
        # 只认 adb 服务名，不再要求设备名匹配（换机/改名后依然能发现）
        if "_adb" not in low:
            continue
        parts = line.split()
        if parts and ":" in parts[-1]:
            found.append(parts[-1].strip())
    return found


def online_devices():
    """adb devices 中已经在线的 serial。USB 直连最稳，排最前。"""
    try:
        out = adb("devices", timeout=8).stdout
    except (subprocess.TimeoutExpired, OSError):
        return []
    found = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            found.append(parts[0])
    # 不含 ":" 的是 USB 序列号，优先于无线地址
    found.sort(key=lambda s: (":" in s, s))
    return found


def candidates():
    seen, out = set(), []
    for c in (*online_devices(), USB_SERIAL, WIFI_ADDR,
              *mdns_candidates(), read_last_device()):
        if c and c not in seen:
            seen.add(c)
            out.append(c)
    return out


def connect(serial):
    """返回可用的 serial（USB 序列号本身就是已在线设备，不需要 connect）。"""
    try:
        if ":" in serial:
            r = adb("connect", serial, timeout=10)
            if "connected" not in (r.stdout + r.stderr).lower():
                return None
        r = adb("-s", serial, "get-state", timeout=8)
        if "device" in r.stdout.split():
            return serial
    except (subprocess.TimeoutExpired, OSError):
        pass
    return None


def ensure_dex(serial):
    r = adb("-s", serial, "shell", f"[ -f {DEX} ] && echo yes || echo no", timeout=8)
    if "yes" in r.stdout:
        return True
    local = os.path.join(LOG_DIR, "clip.dex")
    if not os.path.exists(local):
        log.error("本机缺少 %s，无法推送到手机", local)
        return False
    adb("-s", serial, "push", local, DEX, timeout=20)
    return True


def _read_clipboard(cmd):
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=3, env=SESSION_ENV)
        if p.returncode == 0:
            return p.stdout.decode("utf-8", "replace")
    except (subprocess.TimeoutExpired, OSError):
        pass
    return None


def _set_once(write_cmd, verify_cmd, text):
    """写剪贴板。wl-copy / xclip 都会 fork 出持有 selection 的常驻子进程，
    因此不能等它退出（会一直挂到超时），只能写进去后回读校验。"""
    try:
        p = subprocess.Popen(write_cmd, stdin=subprocess.PIPE,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             env=SESSION_ENV)
    except OSError:
        return False
    try:
        p.stdin.write(text.encode("utf-8"))
        p.stdin.flush()
        p.stdin.close()
    except OSError:
        pass
    deadline = time.time() + 2.0
    while time.time() < deadline:
        got = _read_clipboard(verify_cmd)
        if got is not None and (got == text or got.rstrip("\n") == text.rstrip("\n")):
            return True
        time.sleep(0.1)
    return False


SETTERS = (
    (["wl-copy"], ["wl-paste", "--no-newline"]),
    (["xclip", "-selection", "clipboard"], ["xclip", "-o", "-selection", "clipboard"]),
)


def set_clipboard(text):
    for write_cmd, verify_cmd in SETTERS:
        if not shutil.which(write_cmd[0]):
            continue
        if _set_once(write_cmd, verify_cmd, text):
            return write_cmd[0]
    return None


def run_session(serial):
    """起一个长驻轮询，逐行消费，直到断流。"""
    cmdline = f"CLASSPATH={DEX} app_process /system/bin {MAIN_CLASS} poll {POLL_MS}"
    log.info("连接 %s，启动手机端轮询（%sms）", serial, POLL_MS)
    proc = subprocess.Popen(["adb", "-s", serial, "shell", cmdline],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, bufsize=1, env=os.environ)
    last = None
    expect_initial = False
    got_ready = False
    try:
        for raw in proc.stdout:
            line = raw.strip()
            if not line:
                continue
            if line == "READY":
                got_ready = True
                expect_initial = True
                write_last_device(serial)
                log.info("手机端轮询就绪，开始同步")
                continue
            if expect_initial:
                # READY 之后的第一条是手机剪贴板的现状，只记录不覆盖桌面
                expect_initial = False
                if line.startswith("T:"):
                    try:
                        last = base64.b64decode(line[2:]).decode("utf-8", "replace")
                        log.info("基线已记录（%d 字），只同步后续变化", len(last))
                    except Exception:
                        pass
                else:
                    log.info("基线状态：%s，等待新的复制", line[:24])
                continue
            if line.startswith("T:"):
                try:
                    text = base64.b64decode(line[2:]).decode("utf-8", "replace")
                except Exception:
                    log.warning("base64 解码失败，跳过一行")
                    continue
                if not text or text == last:
                    continue
                if set_clipboard(text) is None:
                    log.error("写入桌面剪贴板失败（wl-copy / xclip 均不可用）")
                    last = text
                    continue
                last = text
                # 安全约定：日志绝不落剪贴板原文（可能含 token / 密码 / 私密文本），
                # 只记长度 + 内容指纹，够排查"有没有同步"而不泄露内容。
                fp = hashlib.sha256(text.encode("utf-8")).hexdigest()[:8]
                log.info("手机 -> 桌面 %d 字（指纹 %s）", len(text), fp)
            elif line.startswith("N:"):
                log.debug("剪贴板是非文本（%s），跳过", line[2:])
            elif line.startswith("E:"):
                log.warning("手机端读取异常：%s", line[2:])
            else:
                log.debug("忽略输出：%s", line[:80])
    finally:
        try:
            proc.terminate()
        except OSError:
            pass
        err = proc.stderr.read() if proc.stderr else ""
        if err.strip():
            log.debug("adb stderr: %s", err.strip()[:300])
    if not got_ready:
        log.warning("轮询未就绪就退出（%s）", serial)


def notify(summary, body, urgency="normal"):
    """桌面通知。断线时主动提示，避免"复制了没反应"却没人知道通道已断。"""
    if not shutil.which("notify-send"):
        return
    try:
        subprocess.run(["notify-send", "-a", "ClipCascade", "-u", urgency,
                        "-t", "8000", summary, body],
                       timeout=5, env=SESSION_ENV,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (subprocess.TimeoutExpired, OSError):
        pass


def main():
    backoff = 3
    offline_since = None
    notified = False
    while True:
        for serial in candidates():
            s = connect(serial)
            if not s:
                continue
            if not ensure_dex(s):
                continue
            offline_since = None
            backoff = 3
            if notified:
                notify("ClipCascade 手机桥已恢复", "手机剪贴板同步重新连上（%s）" % s, "low")
                log.info("通道恢复：%s", s)
                notified = False
            try:
                run_session(s)
            except KeyboardInterrupt:
                log.info("收到中断，退出")
                return
            except Exception as exc:                       # noqa: BLE001
                log.error("会话异常：%s", exc)
            log.warning("%s 会话结束，%ds 后重连", s, backoff)
            time.sleep(backoff)
            backoff = min(backoff * 2, 30)

        if offline_since is None:
            offline_since = time.time()
        elif not notified and time.time() - offline_since >= 90:
            notify("ClipCascade 手机桥已断开",
                   "手机复制的内容暂时同步不过来：请检查数据线或手机端无线调试（5555）",
                   "critical")
            notified = True
            log.warning("已离线 %.0fs，已发出断开提醒", time.time() - offline_since)
        log.warning("没有可用设备（试过 %s），%ds 后重试",
                    ", ".join(candidates()), backoff)
        time.sleep(backoff)
        backoff = min(backoff * 2, 30)


if __name__ == "__main__":
    main()
