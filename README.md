# ClipCascade Phone → Desktop Bridge

**让「手机复制 → 电脑粘贴」自动生效**，不需要 root、不需要 Xposed/LSPosed、不需要改系统。

针对 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 在 Android 10+ 上的一个断点：
手机端的**后台**读剪贴板被系统拒绝，导致只有「电脑 → 手机」能自动同步，反向必须手动分享。

> ⚠️ **使用前请先读 [免责声明](DISCLAIMER.md)。** 本项目会经由 `adb` 通道读取并改写剪贴板内容，
> 请自行评估在你环境里的合规性与风险。作者不对任何数据泄露、凭据外泄或设备异常负责。

---

## 问题背景

ClipCascade 的设计是双向同步。但在 Android 10（API 29）之后，`ClipboardService` 引入了焦点限制：

```
E ClipboardService: Denying clipboard access to com.clipcascade, application is not in focus
```

结果就是后台的 ClipCascade 读不到剪贴板，「手机复制 → 电脑」这条向静默失效。

官方文档给的 workaround 是授予 `READ_LOGS`，从 logcat 里抓系统打印的剪贴板内容。**这个方案在 Android 15 之后已经失效** —— `ClipboardService` 不再把剪贴板内容写进 logcat 了。所以在较新的机型上，即使照做也看不到任何效果。

## 原理：借用系统对 shell 的豁免

AOSP 的 `ClipboardService.clipboardAccessAllowed()` 里有一段硬编码放行：

```java
} else if (uid == Process.SHELL_UID) {
    // Shell can access the clipboard for testing purposes.
    return true;
}
```

而 `adb shell` 的 uid 恰好就是 `2000`（`SHELL_UID`）。于是：

```
手机剪贴板
   │  (app_process 以 shell 身份 binder 直调 IClipboard，不受焦点限制)
   ▼
clip.dex  poll 循环  ──►  T:<base64>  逐行输出
   │
   │  adb（USB 或无线）
   ▼
phone_bridge.py  ──►  wl-copy / xclip  ──►  桌面剪贴板
   │
   ▼
桌面 ClipCascade 视作本地复制，照常加密上行
```

反过来（桌面 → 手机）**完全由 ClipCascade 自己负责**，本项目不参与。相同内容会被去重，不会形成回环。

## 支持范围

| 项 | 状态 |
|---|---|
| 文本（含中文、多字节、长文本） | ✅ 实测可用（含 8 KB） |
| 图片 / 文件 | ❌ 不处理，直接跳过 |
| 手机 → 桌面 | ✅ 本项目负责 |
| 桌面 → 手机 | ✅ 由 ClipCascade 原生负责 |
| 需要 root | ❌ 不需要 |
| 需要 Xposed / LSPosed | ❌ 不需要 |
| 锁屏状态下同步 | ❌ Android 会隐藏剪贴板，见[已知限制](#已知限制) |
| 桌面端 | Linux（Wayland / X11） |

## 快速开始

前置条件：桌面端已装 `adb`、`wl-clipboard`（Wayland）或 `xclip`（X11）；手机已开启开发者选项与 USB 调试。

```bash
# 1. 确认设备已连接
adb devices

# 2. 把轮询工具推到手机
adb push prebuilt/clip.dex /data/local/tmp/clip.dex

# 3. 部署桥接脚本与服务
mkdir -p ~/.local/share/clipcascade
install -m 755 src/phone_bridge.py ~/.local/share/clipcascade/phone_bridge.py
install -m 644 systemd/clipcascade-phone-bridge.service ~/.config/systemd/user/

# 4. 按自己环境改两行（设备序列号 / 无线地址），然后启动
systemctl --user daemon-reload
systemctl --user enable --now clipcascade-phone-bridge

# 5. 看日志确认连上
journalctl --user -u clipcascade-phone-bridge -f
```

想摆脱数据线，用无线 adb（重启后仍生效）：

```bash
adb shell su -c 'setprop persist.adb.tcp.port 5555'
adb connect <手机IP>:5555
```

然后把服务里的 `BRIDGE_WIFI_ADDR` 改成 `<手机IP>:5555`。

## 配置

通过 systemd 环境变量设置：

| 变量 | 默认 | 说明 |
|---|---|---|
| `BRIDGE_WIFI_ADDR` | 空 | 无线 adb 地址，如 `192.168.1.23:5555`。留空则自动发现 |
| `BRIDGE_USB_SERIAL` | 空 | USB 序列号，`adb devices` 里的那一串。留空则自动发现 |
| `BRIDGE_POLL_MS` | `500` | 轮询间隔（毫秒）。越小越灵敏，越耗电 |

候选地址的尝试顺序：上次成功的设备 → `BRIDGE_WIFI_ADDR` → mDNS（`adb mdns services`）→ `BRIDGE_USB_SERIAL`。全失败则指数退避重连。

运行时也可以手动操作：

```bash
D="adb -s <serial>"
# 读
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip read"
# 写
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip write 'hello'"
# 自检（同进程内写完立刻读回，用来判断 binder 是否被静默拒绝）
$D shell "CLASSPATH=/data/local/tmp/clip.dex app_process /system/bin com.jay.readclip.Clip diag 'test'"
```

## 从源码编译 clip.dex

只需要两份官方 zip，不需要 Android Studio：

```bash
./scripts/build-dex.sh
```

脚本会下载 `build-tools_r36_linux.zip` 与 `platform-36_r02.zip`，用 `javac` + `d8` 产出 `classes.dex`。
仓库里的 `prebuilt/clip.dex` 就是它的产物（`d8 --min-api 21`）。

## 测试环境

以下是**实际验证通过**的组合，不是「理论上支持」：

| 角色 | 具体版本 |
|---|---|
| 桌面系统 | EndeavourOS（Arch Linux rolling），内核 `7.2.9-arch1-1`，x86_64 |
| 桌面会话 | GNOME 51，**Wayland**（`wayland-0`），同时可用 XWayland `:0` |
| 桌面 Python | `/usr/bin/python3` **3.14.8** |
| 剪贴板工具 | `wl-clipboard 2.3.0`（主）/ `xclip`（备） |
| adb | `Android Debug Bridge 1.0.41`（`android-tools`） |
| 手机 | **小米 17 Pro**（`25098PN5AC` / 代号 `pandora`） |
| 手机系统 | **Android 17（SDK 37）**，HyperOS `OS4.0`，已 root（KernelSU） |
| 手机 App | `com.clipcascade`，`versionName=1.0`（React Native 构建） |
| ClipCascade 服务端 | Docker 镜像 `sathvikrao/clipcascade:0.7.0` |
| ClipCascade 桌面端 | Linux GUI `3.2.0` |
| 编译工具 | `build-tools 36.0.0` + `platform android-36`，`--min-api 21` |

实测延迟（手机写入剪贴板 → 桌面剪贴板命中）：**247 ms** / **527 ms**（不同轮次，poll 500 ms）。

**未测试**：Android 10–14、Windows / macOS 桌面、非小米 ROM、无 root 环境（按原理不需要 root，但未实测）。
如果你在其他组合上跑通了或跑不通，欢迎开 issue 补充。

## 已知限制

1. **锁屏期间整条链路静默**。屏幕锁定时 `getPrimaryClip()` 一律返回 `null`，`setPrimaryClip()` 不报错但结果不可靠。
   排查时先看：`adb shell dumpsys window | grep mDreamingLockscreen`。
   正常使用不受影响 —— 你复制的那一刻手机必然是解锁的。
2. **只同步文本**。图片、文件、富文本不走这条路。
3. **依赖 adb 常驻**。端口/地址变化会导致短暂断连，服务会自动重连。
4. **轮询有固定成本**。500 ms 一轮意味着手机侧持续唤醒，长时间不用建议停服务。

## 安全与隐私

请务必理解这几点：

- **剪贴板内容会明文经过 adb 通道**，并落到桌面剪贴板。同一台机器上能跑 `adb` 的进程都能读到。
- **无线 adb 监听 5555 端口**。虽然 adb 有 RSA 密钥认证，但仍应限制在内网，或加防火墙规则。不用时建议
  `adb shell su -c 'setprop persist.adb.tcp.port ""'` 关掉。
- **本项目的日志只记录「字数 + sha256 前 8 位指纹」，绝不写原文**。因为 systemd/journald 是明文持久化，
  记原文等于把 token、密码、验证码写进日志文件。示例：

  ```
  手机 -> 桌面 36 字（指纹 49dbbc62）
  ```

  指纹只够判断「有没有同步、是不是同一条」，无法反推内容。**自行修改脚本时请保留这个约定。**
- 不要把 token / 私钥临时放进剪贴板来跨设备传递 —— 它会被全链路留痕。

## 卸载

```bash
systemctl --user disable --now clipcascade-phone-bridge
rm -f ~/.config/systemd/user/clipcascade-phone-bridge.service \
      ~/.local/share/clipcascade/phone_bridge.py
adb shell "rm -f /data/local/tmp/clip.dex"
adb shell su -c 'setprop persist.adb.tcp.port ""'   # 关掉无线调试
```

## 许可

**GPL-3.0**，与上游 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 保持一致。

本项目不包含也没有复制上游的代码，两者通过 `adb` 松耦合协同，严格说并不构成 GPL 的衍生作品；
选择同一协议是为了让「ClipCascade + 本项目」作为整体分发时没有任何协议摩擦。

详见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 致谢

- [Sathvik Rao](https://github.com/Sathvik-Rao) / [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) —— 跨设备剪贴板同步与加密的底座。
- AOSP `ClipboardService` 中为 shell 保留的那条测试用放行路径。

---

## English

**ClipCascade Phone → Desktop Bridge** — restores automatic clipboard sync from Android to desktop for
[ClipCascade](https://github.com/Sathvik-Rao/ClipCascade), where background clipboard reads are denied since Android 10.

Instead of hooking the system (root/Xposed), it relies on AOSP's built-in exemption: `ClipboardService` always allows
`SHELL_UID` (2000) to access the clipboard. A tiny `app_process` payload polls the clipboard on-device, streams
`T:<base64>` lines over `adb`, and the desktop side writes them into the local clipboard — where ClipCascade picks
them up as a normal local copy.

- No root, no Xposed/LSPosed, no system modification.
- Reverse direction is handled by ClipCascade itself.
- Text only. Does not work while the phone is locked.
- Tested on: EndeavourOS / GNOME 51 Wayland / Python 3.14.8 ↔ Xiaomi 17 Pro / Android 17 (SDK 37) / HyperOS OS4.0.
- Licensed **GPL-3.0**, matching upstream. See [DISCLAIMER.md](DISCLAIMER.md).
