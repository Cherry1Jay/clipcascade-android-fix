# ClipCascade 安卓后台剪贴板修复

让 **「手机复制 → 电脑粘贴」** 在较新的 Android 上重新自动生效。

针对 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 一个长期断点：Android 10 之后 App 在**后台**读不到剪贴板，
导致只有「电脑 → 手机」能自动同步，反向必须手动分享。本仓库给出**三种**走通这条链路的实现，按侵入性从高到低排列。

> ⚠️ 使用前请完整阅读 **[免责声明 / DISCLAIMER](DISCLAIMER.md)** 与 **[NOTICE（许可与致谢）](NOTICE.md)**。
> 本项目的任一方案都会读取你的剪贴板内容，请自行评估合规性与风险。

---

## 三条路线怎么选

| | 方案 A：合并版 APK | 方案 B：LSPosed 模块 | 方案 C：adb 桥 |
|---|---|---|---|
| 目录 | [`merged/`](merged/) | [`lsposed-hook/`](lsposed-hook/) | [`src/`](src/) `scripts/` `systemd/` |
| 需要 root | ✅ 需要 | ✅ 需要 + LSPosed | ❌ 不需要 |
| 需要电脑常开 | ❌ 不需要 | ❌ 不需要 | ✅ 需要 |
| 需要改官方 APK | ✅ 需要重签名 | ❌ 不需要 | ❌ 不需要 |
| 手机端包数量 | **1 个** | 2 个（App + 模块） | 2 个（App + 脚本） |
| 官方升级 | ❌ 每次要重新合并 | ✅ 可照常升级 | ✅ 可照常升级 |
| 实测延迟 | ~1 s | ~1 s | 247–527 ms |
| 状态 | **推荐** | 备选 | 无 root 时的唯一选择 |

三条路线解决的是**同一个断点**，任选其一即可，不要同时启用。

---

## 问题背景

ClipCascade 设计上是双向同步。但 Android 10（API 29）起 `ClipboardService` 引入了焦点限制：

```
E ClipboardService: Denying clipboard access to com.clipcascade,
  application is not in focus nor is it a system service for user 0
```

于是手机端在后台读不到剪贴板，「手机复制 → 电脑」这条向静默失效。

官方文档给的 workaround 是授予 `READ_LOGS`、从 logcat 里抓系统打印的剪贴板内容 —— **这个方案在 Android 15 之后已失效**，
`ClipboardService` 不再把内容写进 logcat。

本项目在 Android 17 / HyperOS 4.0 上把这条路彻底查了一遍，得到三个关键结论：

| 尝试 | 结果 |
|---|---|
| `appops set com.clipcascade READ_CLIPBOARD allow` | ❌ 无效。拦截点在 `ClipboardService.clipboardAccessAllowed(...)`，它不看这个 appop |
| 把 LSPosed 模块注入 `system_server` 直接改 `clipboardAccessAllowed` | ❌ 失败。legacy(api 82) 与 modern(libxposed 102) 模块都进不了 system_server |
| 只解决「读被拒」 | ❌ 不够。App 切后台会自己调 `stopListening()`，后台连「剪贴板变了」的事件都不发生 |

### 为什么「以 shell 身份读」能绕过

AOSP 的 `ClipboardService.clipboardAccessAllowed()` 里有一段硬编码放行：

```java
} else if (uid == Process.SHELL_UID) {
    // Shell can access the clipboard for testing purposes.
    return true;
}
```

`SHELL_UID` 就是 2000。于是 `app_process` 以 uid 2000 通过 binder 直调 `IClipboard`，不受焦点限制。

一个反直觉的实测结果：

| 身份 | 读剪贴板 |
|---|---|
| `adb shell`（uid 2000） | ✅ 正常 |
| `su -c`（uid 0，root） | ❌ 返回 `EMPTY` |
| `su 2000 -c`（root 降权到 shell） | ✅ 正常 |

AppOps 按**调用 uid** 判定，root 反而不放行。所以三种方案里凡是要读剪贴板的地方，都必须**降权到 2000**，不能图省事直接 `su`。

---

## 方案 A：合并版 APK（推荐）

把桥接逻辑直接编进官方 APK，装完只有一个软件包，不依赖 LSPosed、不依赖 adb、不需要电脑常开。

详见 [`merged/README.md`](merged/README.md)。

```bash
# 1. 从官方 Release 下载对应版本的 APK（本项目基于 ClipCascade 手机端 1.0 验证）
#    https://github.com/Sathvik-Rao/ClipCascade/releases

# 2. 合并（需要 JDK + Android build-tools + android.jar + apktool.jar）
cd merged
./build.sh ~/Downloads/ClipCascade.apk out/clipcascade-merged.apk

# 3. 签名变了，必须先卸载再装
adb uninstall com.clipcascade
adb install out/clipcascade-merged.apk
```

**关键实现**：加一个空的 `ContentProvider`（`merged/src/com/jay/ccbridge/BridgeProvider.java`）。
`ContentProvider.onCreate()` 是免费且必然执行的进程启动钩子，比 hook 稳；随后它启动轮询、把变化反射成
App 自己的 `onClipboardChange` 事件，交给原有 JS 层走加密上行。

> 合并版受 GPL-3.0 约束（是对上游二进制的修改版本），分发前务必读 [NOTICE](NOTICE.md) 第二节。

### 可能会踩的三个坑

| 坑 | 表现 | 处理 |
|---|---|---|
| 重装后 uid 变化 | WorkManager 崩、写文件 `EACCES` | 恢复数据后 `chown -R <新uid>` |
| SELinux per-app category 丢失 | 同上，`restorecon` 也补不回 | `chcon -R u:object_r:app_data_file:s0:cXX,cYY,...` |
| 删了 `/data/data/<pkg>` 没重装 | App 直接起不来，`pm clear` 也救不回 | 必须卸载重装 |

---

## 方案 B：LSPosed 模块

不想改官方 APK 就用这个。模块注入 `com.clipcascade` 进程，拦 `stopListening()`，并用常驻子进程代读剪贴板。

详见 [`lsposed-hook/README.md`](lsposed-hook/README.md)。

```bash
adb install lsposed-hook/prebuilt/cchook.apk
# LSPosed 里勾选作用域：系统框架不需要，只勾 com.clipcascade
```

需要 LSPosed **2.2.1+**，模块走 modern libxposed API 102。

---

## 方案 C：adb 桥（不需要 root）

桌面端跑一个守护进程，通过 `adb` 从手机拉剪贴板，写进桌面剪贴板；桌面 ClipCascade 视作本地复制照常上行。

```
手机剪贴板
   │  (app_process 以 uid 2000 binder 直调 IClipboard，不受焦点限制)
   ▼
clip.dex  poll 循环  ──►  T:<base64>  逐行输出
   │
   │  adb（USB 或无线）
   ▼
phone_bridge.py  ──►  wl-copy / xclip  ──►  桌面剪贴板
```

```bash
adb devices                                    # 确认设备已连接
adb push prebuilt/clip.dex /data/local/tmp/clip.dex

install -m 755 src/phone_bridge.py ~/.local/share/clipcascade/phone_bridge.py
install -m 644 systemd/clipcascade-phone-bridge.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now clipcascade-phone-bridge
journalctl --user -u clipcascade-phone-bridge -f
```

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `BRIDGE_WIFI_ADDR` | 空 | 无线 adb 地址，如 `192.168.1.23:5555` |
| `BRIDGE_USB_SERIAL` | 空 | USB 序列号；留空则自动发现 |
| `BRIDGE_POLL_MS` | `500` | 轮询间隔，越小越灵敏越耗电 |

候选顺序：上次成功的设备 → `BRIDGE_WIFI_ADDR` → mDNS → `BRIDGE_USB_SERIAL`，全失败则指数退避重连。

> 这条路线依赖 adb 通道存活。**通道断了桥接只会安静重试，你看不到任何提示** —— 这是它最大的坑。
> 仓库里的 `phone_bridge.py` 已经加了离线 90 s 弹桌面通知。

---

## 支持范围

| 项 | 状态 |
|---|---|
| 文本（含中文、多字节、长文本） | ✅ 实测可用（含 8 KB） |
| 图片 / 文件 / 富文本 | ❌ 不处理 |
| 手机 → 桌面 | ✅ 本项目负责 |
| 桌面 → 手机 | ✅ 由 ClipCascade 原生负责 |
| 锁屏状态下同步 | ❌ 系统会隐藏剪贴板 |

## 测试环境

全部数据来自**实际跑通**，不是「理论上支持」。完整版本表与复现脚本见 [`docs/TESTED-ENV.md`](docs/TESTED-ENV.md)。

| 角色 | 具体版本 |
|---|---|
| 手机 | 小米 17 Pro（`25098PN5AC` / `pandora`），**Android 17 / SDK 37 / HyperOS OS4.0**，KernelSU |
| 桌面 | EndeavourOS（Arch rolling），内核 `7.2.9-arch1-1`，GNOME 51 **Wayland** |
| ClipCascade 服务端 | Docker `sathvikrao/clipcascade:0.7.0` |
| ClipCascade 桌面端 | Linux GUI `3.2.0` |
| ClipCascade 手机端 | `com.clipcascade` `versionName=1.0` |
| LSPosed（方案 B） | 2.2.1 (7930) |
| 编译 | build-tools 36.0.0 + platform android-36，apktool 3.0.3 |

**未测试**：Android 10–16、Windows / macOS 桌面、非小米 ROM、纯原生 AOSP。
跑通了或跑不通都欢迎开 issue 补充。

## 已知限制

1. **锁屏期间整条链路静默**。`getPrimaryClip()` 返回 `null`，`setPrimaryClip()` 不报错但不可靠。判断：`adb shell dumpsys window | grep mDreamingLockscreen`。
2. **只同步文本**。
3. **合并版无法直接升级官方版本**（签名不同），每次要重新 `build.sh` 并重装。
4. **轮询有固定成本**：手机侧持续唤醒，长时间不用建议停掉。
5. 方案 A / B 依赖 root 授予 `su`；拒绝授权就退化成官方行为。

## 安全与隐私

- **剪贴板内容在这条链路上是不加密的**（方案 C 走 adb 明文；方案 A/B 在设备内部传递）。
  ClipCascade 自身的端到端加密只覆盖「设备 → 服务端 → 设备」那一段。
- **绝不建议把 token / 密码 / 私钥临时放进剪贴板**跨设备传递 —— 它会全链路留痕，包括输入法剪贴板历史。
- 本项目的日志**只记录「字数 + sha256 前 8 位指纹」，绝不写原文**。例如：

  ```
  手机 -> 桌面 36 字（指纹 49dbbc62）
  ```

  systemd/journald 是明文持久化，记原文等于把凭据写进日志文件。**自己 fork 时请保留这个约定。**
- 方案 C 的无线 adb 会在网络接口上监听 5555。限制在内网，或不用时关掉。

## 卸载

```bash
# 方案 A：装回官方 APK（需先卸载，签名不同）
adb uninstall com.clipcascade

# 方案 B
adb uninstall com.jay.cchook

# 方案 C
systemctl --user disable --now clipcascade-phone-bridge
rm -f ~/.config/systemd/user/clipcascade-phone-bridge.service \
      ~/.local/share/clipcascade/phone_bridge.py
adb shell "rm -f /data/local/tmp/clip.dex"
```

---

## 上游、致谢与许可

本项目**不是** ClipCascade 的分支，也不为上游提供支持。

| | |
|---|---|
| 上游项目 | **ClipCascade** |
| 作者 | **Sathvik Rao**（[@Sathvik-Rao](https://github.com/Sathvik-Rao)） |
| 仓库 | https://github.com/Sathvik-Rao/ClipCascade |
| 许可证 | **GNU GPL-3.0** |
| 本项目基于的上游版本 | 手机端 `versionName=1.0`（对应上游 release `3.2.0`） |

**特别致谢**：

- **Sathvik Rao** —— ClipCascade 本体。没有这个项目，就没有「手机复制、电脑粘贴自动同步」这件事本身；
  本仓库做的只是把它在较新 Android 上断掉的那一条腿接回去。
- AOSP 的 `ClipboardService` / `IClipboard` 维护者 —— 对 `SHELL_UID` 的硬编码放行是本文所有方案的立足点。
- [libxposed/api](https://github.com/libxposed/api)（Apache-2.0）、[LSPosed](https://github.com/LSPosed/LSPosed)（GPL-3.0）、
  [Apktool](https://github.com/iBotPeaches/Apktool)（Apache-2.0）、[KernelSU](https://github.com/tiann/KernelSU)（GPL-3.0）——
  方案 B / A 的工具链。
- `wl-clipboard`、`xclip`、`android-tools` —— 方案 C 的运行时依赖。

**许可**：本仓库原创代码以 **GPL-3.0** 授权，与上游保持一致，全文见 [LICENSE](LICENSE)。
合并版 APK 是对上游二进制的修改版本，其分发条件见 **[NOTICE](NOTICE.md)**。

`ClipCascade` 名称与标识归其作者所有，本项目仅作事实性提及，不构成隶属、合作或背书。

---

*本项目按「现状」提供，不附带任何担保。使用即表示接受 [DISCLAIMER](DISCLAIMER.md) 全部条款。*
