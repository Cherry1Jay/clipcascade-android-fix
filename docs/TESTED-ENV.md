# 测试环境与验证记录

本页记录**实际跑通**的软硬件组合与验证方法。README 里的「支持范围」全部以此为依据。
凡是没在这里出现的组合，都属于**未验证**。

## 一、测试主机一览

### 手机端（运行合并版 App）

| 项 | 值 | 获取命令（adb shell） |
|---|---|---|
| 机型 | 小米 17 Pro | `getprop ro.product.model` → `25098PN5AC` |
| 代号 | `pandora` | `getprop ro.product.device` |
| Android 版本 | **17** | `getprop ro.build.version.release` |
| API Level | **37** | `getprop ro.build.version.sdk` |
| ROM | HyperOS `OS4.0` | `getprop ro.mi.os.version.name` |
| Root | KernelSU | `su -c id` |
| 剪贴板读取身份 | `uid=2000(shell)` | — |

> 能读到剪贴板的身份是 **`uid=2000`（SHELL_UID）**，不是 root。以 root（uid 0）直接读反而会被 AppOps 拒绝 ——
> 所以代码里用 `su 2000 -c` 降权。

### 桌面端（只用于验证，不参与运行）

| 项 | 值 | 获取命令 |
|---|---|---|
| 发行版 | EndeavourOS（Arch Linux rolling） | `grep PRETTY_NAME /etc/os-release` |
| 内核 | `7.2.9-arch1-1` | `uname -r` |
| 桌面环境 | GNOME 51，**Wayland**（`wayland-0`） | `echo $XDG_SESSION_TYPE` |
| 剪贴板工具 | `wl-clipboard 2.3.0` | `pacman -Q wl-clipboard` |
| adb | `Android Debug Bridge 1.0.41` | `adb version` |

### ClipCascade 自身版本

| 组件 | 版本 |
|---|---|
| 服务端 | Docker 镜像 `sathvikrao/clipcascade:0.7.0` |
| 桌面客户端 | Linux GUI `3.2.0` |
| Android 客户端 | 包名 `com.clipcascade`，`versionName=1.0`（React Native / Hermes） |
| 上游 Release | `3.2.0` |

### 构建工具链

| 项 | 值 |
|---|---|
| build-tools | **36.0.0**（`build-tools_r36_linux.zip` → 解压目录名 `android-16`） |
| platform | **android-36**（`platform-36_r02.zip`） |
| apktool | **3.0.3** |
| `javac` | JDK 17，`-source 8 -target 8` |
| `d8` | `--min-api 26` |
| 产物 | `classes3.dex` ≈ 6 KB；合并后 APK ≈ 89 MB（与原版几乎一致） |

## 二、验证方法（可复现）

### 1. 手机 → 桌面

必须**全等比对**并按小步进轮询，不能 `sleep 3` 看一眼就算通过。

```bash
export WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/1000
D="adb -s <serial>"
TOK="probe-$(date +%H%M%S)"

# 用 uid 2000 写一条，等价于你在手机上复制
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip write '$TOK'"

START=$(date +%s%N)
for i in $(seq 1 30); do
  CUR=$(wl-paste --no-newline 2>/dev/null)
  [ "$CUR" = "$TOK" ] && { END=$(date +%s%N); echo "hit $(( (END-START)/1000000 )) ms"; break; }
  sleep 0.15
done
```

关键前提：**App 必须已切到后台**（`input keyevent 3`），否则测的是前台路径，不能证明修复生效。

### 2. 桌面 → 手机

```bash
printf 'reverse-probe-%s' "$(date +%H%M%S)" | wl-copy
sleep 4
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip read"
# 输出形如 T:<base64>，解码即原文
```

这条路径**不经过本项目的改动**，走的是 ClipCascade 自己的同步 + App 写入剪贴板。
如果这一步失败，问题在 ClipCascade 或服务端，不在本项目。

### 3. 桥接是否真的在工作

```bash
# 看桥接自己打的日志（Tag = CCBridge）
adb logcat -s CCBridge
```

正常应看到 `dex deployed` / `react context ready` / `baseline recorded`，
之后每次内容变化有 `emitted onClipboardChange (<n> chars)`。

### 4. 产物静态冒烟

```bash
BUILD_TOOLS=<build-tools目录> ./scripts/verify-merged.sh out/clipcascade-merged.apk
```

## 三、实测结果

版本 `1.0-ccbridge`（`versionCode=2`），基于官方 `versionName=1.0` 合并。

| 项 | 结果 |
|---|---|
| 后台复制 → 桌面 | ✅ 1 s 内（子进程 poll 800 ms） |
| 后台搁置 90 s 后再复制 | ✅ 仍工作（App 有前台服务，进程未被冻结） |
| 中文长文本 / 连续 3 次快速复制 | ✅ 完整不丢 |
| 桌面 → 手机 | ✅ 4 s 内 |
| 同步回环 | ✅ 无 |
| `ksud soft-reboot` 后 | ✅ 打开 App 即自动恢复轮询（首次轮询子进程起来约 10 s） |
| 登录态 / 加密密钥 | ✅ 迁移后同步正常，账号仍在 |
| `install -r` 覆盖安装（同签名） | ✅ 数据保留 |
| 包体积 | 89 MB，与原版一致（+14 KB） |
| 开机自启 | ⚠️ 重装重置 HyperOS 自启动白名单，需手动开一次 |

## 四、数据迁移的三个坑

| 坑 | 表现 | 正解 |
|---|---|---|
| 重装后 uid 变化（10584 → 10587） | WorkManager 报 `file system on the device is in a bad state`，provider 写文件 `EACCES` | `chown -R <新uid>:<新uid>` |
| `restorecon` 补不回 SELinux per-app category | 同上 | `chcon -R u:object_r:app_data_file:s0:c75,c258,c512,c768`；toybox 的 `chcon` **不支持 `--reference`** |
| `rm -rf /data/data/<pkg>` 后不重装 | App 直接起不来，`pm clear` 也救不回（PMS 的 `ceDataInode` 对不上） | 只能卸载重装 |

**正确顺序**：备份 tar → 卸载 → 安装 → 先启动一次让系统建目录 → `force-stop` →
`tar xzf … --strip-components=1 --no-same-owner` → `chown` 新 uid → `chcon` 根目录那串 context → 启动。

## 五、未验证的组合

以下**没有实测**，请勿当作支持承诺：

- Android 10 / 11 / 12 / 13 / 14 / 15 / 16（原理相同，但各版本 `IClipboard` 方法签名可能不同）
- 非小米 / 非 HyperOS 的 ROM（OneUI、ColorOS、原生 AOSP 等）
- 无 root 环境（本项目离不开 root）
- 多台手机同时使用同一账号

## 六、关于 `IClipboard` 方法签名（移植时必看）

本项目的 `clip.dex` 按 **4 参数**签名写死：

```
getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId)
setPrimaryClip(ClipData, String pkg, String attributionTag, int userId, int deviceId)
```

网上常见的示例是 **3 参数**（少了 `deviceId`），在 API 37 上**直接匹配不到方法**，
表现为 `if (sGet == null) throw ...` 或反射返回 `null`。

移植到其他版本时，先枚举真实签名再改代码。`src/Clip.java` 的做法是遍历
`icb.getClass().getMethods()`，按**方法名 + 精确参数数组**（`Arrays.equals(m.getParameterTypes(), args)`）
匹配，而不是靠参数个数猜。

---

*最后更新：2026-10-22*
