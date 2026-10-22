# 测试环境与验证记录

本页记录**实际跑通**的软硬件组合与验证方法。README 里的「支持范围」全部以此为依据。
凡是没在这里出现的组合，都属于**未验证**。

## 一、测试主机一览

### 桌面端（桥接运行处）

| 项 | 值 | 获取命令 |
|---|---|---|
| 发行版 | EndeavourOS（Arch Linux rolling） | `grep PRETTY_NAME /etc/os-release` |
| 内核 | `7.2.9-arch1-1` | `uname -r` |
| 架构 | x86_64 | `uname -m` |
| 桌面环境 | GNOME 51 | `gnome-shell --version` |
| 会话类型 | **Wayland**（`wayland-0`），另有 XWayland `:0` | `echo $XDG_SESSION_TYPE` |
| Python | `/usr/bin/python3` = **3.14.8** | `/usr/bin/python3 -V` |
| 剪贴板工具（主） | `wl-clipboard 1:2.3.0-1` | `pacman -Q wl-clipboard` |
| 剪贴板工具（备） | `xclip` | `command -v xclip` |
| adb | `Android Debug Bridge 1.0.41` | `adb version` |

> 桌面端**不需要** root，也不需要图形界面特权。脚本通过 `subprocess` 调 `wl-copy` / `xclip`。

### 手机端（被读取剪贴板的一侧）

| 项 | 值 | 获取命令（adb shell） |
|---|---|---|
| 机型 | 小米 17 Pro | `getprop ro.product.model` → `25098PN5AC` |
| 代号 | `pandora` | `getprop ro.product.device` |
| Android 版本 | **17** | `getprop ro.build.version.release` |
| API Level | **37** | `getprop ro.build.version.sdk` |
| ROM | HyperOS `OS4.0` | `getprop ro.mi.os.version.name` |
| Root | KernelSU（**本项目不需要，仅说明环境**） | `su -c id` |
| adb 身份 | `uid=2000(shell)` | `id` |

> 关键点：能读到剪贴板靠的是 **`uid=2000`（SHELL_UID）**，跟是否 root 无关。
> 测试机虽然 root 了，但实现路径全程未使用 `su`。

### ClipCascade 自身版本

| 组件 | 版本 |
|---|---|
| 服务端 | Docker 镜像 `sathvikrao/clipcascade:0.7.0` |
| 桌面客户端 | Linux GUI `3.2.0`（Python，系统 Python 3.14 + venv） |
| Android 客户端 | 包名 `com.clipcascade`，`versionName=1.0`（React Native 构建） |
| 上游 `version.json` | release `3.2.0` / server `3.1.0` |

### 构建工具链（仅在需要重新编译 `clip.dex` 时用到）

| 项 | 值 |
|---|---|
| build-tools | **36.0.0**（`build-tools_r36_linux.zip` → 解压目录名 `android-16`） |
| platform | **android-36**（`platform-36_r02.zip`） |
| `javac` | JDK 17+，`-source 8 -target 8` |
| `d8` | `--min-api 21` |
| 产物 | `classes.dex` ≈ 6.2 KB |

## 二、验证方法（可复现）

### 1. 方向正确性：手机 → 桌面

```bash
export WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/1000
D="adb -s <serial>"
TOK="probe-$(date +%H%M%S)"

$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip write '$TOK'"

START=$(date +%s%N)
for i in $(seq 1 30); do
  CUR=$(wl-paste --no-newline 2>/dev/null)
  [ "$CUR" = "$TOK" ] && { END=$(date +%s%N); echo "hit $(( (END-START)/1000000 )) ms"; break; }
  sleep 0.15
done
```

**判定标准**：必须**全等比对**（`[ "$CUR" = "$TOK" ]`），不能只 `sleep 3` 看一眼。
按 150 ms 步进轮询才能量出真实延迟。

### 2. 方向正确性：桌面 → 手机

```bash
printf 'reverse-probe-%s' "$(date +%H%M%S)" | wl-copy
sleep 3
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip read"
# 输出形如 T:<base64>，解码即原文
```

**注意**：这条路径**不经过本项目**，走的是 ClipCascade 自己的同步 + 手机 App 写入剪贴板。
如果这一步失败，问题在 ClipCascade，不在本桥接。

### 3. binder 是否被静默拒绝

Android 在拒绝时会返回 `null` 而不抛异常，很容易误判成「内容为空」。用 `diag` 子命令在**同一进程内**
写完立刻读回，能把「写失败」和「读被拒」区分开：

```bash
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip diag 'test'"
```

### 4. 日志脱敏检查

```bash
journalctl --user -u clipcascade-phone-bridge | grep -c '<你测试用的明文>'
# 期望输出 0
```

## 三、实测结果

| 项 | 结果 |
|---|---|
| 手机 → 桌面延迟 | **247 ms**、**527 ms**（两轮，poll 500 ms） |
| 桌面 → 手机 | 3 秒内到位 |
| 中文 / 多字节 | ✅ 完整，无乱码 |
| 长文本 | ✅ 8 KB 一次通过 |
| 跨进程可见性 | ✅ 另一进程写入后，长驻 poll 能读到 |
| 服务启动基线 | ✅ 只记录手机现状，**不覆盖**桌面已有内容 |
| 锁屏状态 | ❌ 读返回空、写不可靠（系统行为，非缺陷） |

## 四、未验证的组合

以下**没有实测**，请勿当作支持承诺：

- Android 10 / 11 / 12 / 13 / 14 / 15（原理相同，但各版本 `IClipboard` 方法签名可能不同 —— 本项目是按 4 参数签名写的，见下）
- Windows / macOS 桌面端（本项目只做了 Linux）
- 非小米 / 非 HyperOS 的 ROM（OneUI、ColorOS、原生 AOSP 等）
- 无 root 设备（按原理不需要 root，但没在纯原生设备上跑过）
- 多台手机同时桥接

## 五、关于 `IClipboard` 方法签名（移植时必看）

本项目的代码按 **4 参数**签名写死：

```
getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId)
setPrimaryClip(ClipData, String pkg, String attributionTag, int userId, int deviceId)
```

网上常见的示例是 **3 参数**（少了 `deviceId`），在 API 37 上**直接匹配不到方法**，
表现为 `if (sGet == null) throw ...` 或反射返回 `null`。

移植到其他版本时，先枚举真实签名再改代码：

```bash
# 用一个打印 IClipboard 全部方法的探针类（参考 src/Clip.java 的反射初始化部分）
$D shell "CLASSPATH=/data/local/tmp/probe.dex app_process /system/bin <你的探针类>"
```

`Clip.java` 里的做法是：遍历 `icb.getClass().getMethods()`，按**方法名 + 精确参数数组**
（`Arrays.equals(m.getParameterTypes(), args)`) 匹配，而不是靠参数个数猜。

---

*最后更新：2026-10-22*
