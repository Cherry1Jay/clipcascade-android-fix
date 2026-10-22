# 方案 B：LSPosed 模块

在 ClipCascade **App 进程内**用 LSPosed 模块接管剪贴板读取，让 App 自己走它原有的加密上行。
不改官方 APK，官方发版可照常升级。

> 如果你不介意改 APK，优先用 [`../merged/`](../merged/) 的合并版 —— 只需一个软件包，少一层依赖。
> 本模块保留给「不想重签名、想跟着官方升级」的场景。

## 为什么 adb 那条路被放弃

adb 方案（`phone_bridge.py`）能用，但 adb 本身太脆：拔线断、无线调试被系统关掉断、
重启后 5555 不监听也断，而断了以后桥接只是安静重试，用户看到的就是「复制了没反应」。

## 为什么不能只 hook system_server

`ClipboardService.clipboardAccessAllowed()` 是真正拦人的地方（`appops set ... READ_CLIPBOARD allow`
压不住它，实测无效）。但本机 **LSPosed 2.2.1 (7930) + HyperOS / Android 17** 下：

- legacy xposed API（api 82）的模块**完全进不了 system_server**，日志里一行都没有；
- 换成 modern libxposed API 102 + `staticScope` 后，App 进程能注入，但 system_server 仍然不加载我们的模块
  （同一台机器上只有 `com.tiger.usbmanager` 等少数 modern 模块出现在 `(system)` 日志里）。

所以改成在 **App 进程内**兜底。

## 这条路线踩到的真实原因

排查下来是两个坑叠在一起：

1. **后台读取会被拒**：`Denying clipboard access to com.clipcascade, application is not in focus
   nor is it a system service`；
2. **更关键：后台连"剪贴板变了"的事件都没有**。App 的 `ClipboardListenerModule.stopListening()`
   在切后台时被调用，`OnPrimaryClipChangedListener` 在 HyperOS 上不再回调，于是它既不读也不报错。
   App 自带的兜底是 `READ_LOGS` 读 logcat（`logcatProcess` / `logcatThread` 字段就是它），Android 15+ 已失效。

## 模块做了什么

全部在 App 进程内，三件事：

| 动作 | 目的 |
|---|---|
| 拦住 `ClipboardListenerModule.stopListening()` | 监听在后台不被关掉（顺手留着，前台仍然走原生回调） |
| 常驻一个 `su 2000 -c "app_process ... Clip poll 800"` 子进程 | ClipboardService 对 `SHELL_UID(2000)` 有硬编码放行，用它代读；单个常驻进程，不是每次 fork |
| 内容变化时反射构造 `{"content": 文本, "type": "text"}`，调用 App 自己的 `sendEventToJS(...)` | 把事件伪装成原生回调，JS 层照原逻辑加密上行 |

App 需要授予 root（KernelSU 会弹一次，选「记住」）。

## 构建 / 安装

```bash
./build.sh 1
adb install -r build/cchook.apk
L=/data/adb/modules/zygisk_lsposed/lspctl
adb shell su -c "$L module enable --user=0 com.jay.cchook"
adb shell su -c "$L scope add --user=0 com.jay.cchook com.clipcascade"
```

改完作用域用**软重启**生效，别整机重启：

```bash
adb shell su -c 'ksud soft-reboot'
```

> 手机端还需要 `/data/local/tmp/clip.dex`（本仓库 `prebuilt/clip.dex`，见上级 README）——
> 它是以 uid 2000 读剪贴板的小 `app_process` 载荷。

## 实测（Xiaomi 17 Pro / Android 17 / HyperOS OS4.0）

| 场景 | 结果 |
|---|---|
| 后台复制 → 桌面 | ✅ 约 1s 内到位（轮询 800ms） |
| 后台放置 90s 后复制 | ✅ 仍然工作（App 有前台服务，进程不被冻结，子进程也不被冻） |
| 中文长文本 / 连续 3 次快速复制 | ✅ 完整、不丢 |
| 桌面 → 手机 | ✅ 由 App 原生写入，4s 内到位 |
| 回环 | ✅ 无（App 侧按内容去重） |
| 软重启后 | ✅ App 由 `BOOT_COMPLETED` 自动拉起，模块自动注入，首帧当基线不覆盖桌面 |
| 重启后第一次复制 | ⚠️ 轮询子进程起来有 ~10s 延迟，这期间的第一次快照会被当基线，不会上屏 |

## 已知限制

- 只同步文本（图片/文件不支持，和上游一致）。
- 依赖 root（KernelSU/Magisk）+ LSPosed；App 侧 `su` 授权被撤销后会退化成「后台不同步」。
- 依赖 ClipCascade App 保持运行（它有前台服务，正常不会被杀）。
- HyperOS 的剪贴板隐私保护可能弹「XX 读取了剪贴板」提示，属正常。
