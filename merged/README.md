# 方案 A：合并版 APK（推荐）

把后台剪贴板桥**直接编进 ClipCascade 官方 APK**，安装后只有一个软件包：
不需要 LSPosed 模块、不需要 adb、不需要常开电脑。

> ⚠️ **GPL-3.0 修改声明**：本目录产物是在上游 ClipCascade 手机端 APK 基础上修改得到的
> **修改版本（modified version）**，不是独立作品。修改者 **FairyJJ**，日期 **2026-10-22**。
> 逐条改动、对应源码位置与分发条件见 [../NOTICE.md](../NOTICE.md) 第二节。

## 为什么这么做

官方 App 在 HyperOS 上后台拿不到剪贴板：

1. 切后台时会自己调 `ClipboardListenerModule.stopListening()`，不再监听；
2. HyperOS 还压制了后台的 `OnPrimaryClipChangedListener` 回调，连「变了」的事件都没有；
3. 官方兜底是用 `READ_LOGS` 读 logcat，Android 15+ 已失效；
4. `appops set ... READ_CLIPBOARD allow` 也无效，拦截点在 `ClipboardService.clipboardAccessAllowed(...)`。

之前的做法是外挂一个 LSPosed 模块（`../lsposed-hook/`）。这里改成**把同样的逻辑编进 App 自己**：
LSPosed 剩下的唯一作用就是「在目标进程里跑我们的代码」，而这一层现在可以省掉。

## 实现

`src/com/jay/ccbridge/BridgeProvider.java`，一个空的 `ContentProvider`：

| 阶段 | 动作 |
|---|---|
| `onCreate()` | 进程一启动就执行（早于 `Application.onCreate`），起后台线程 |
| 线程启动 | 把 `assets/clip.dex` 用 `su` 复制到 `/data/local/tmp/clip.dex`（自愈，不怕被清） |
| 等 ReactContext | 反射 `MainApplication.getReactNativeHost().getReactInstanceManager().getCurrentReactContext()` |
| 常驻轮询 | 一个 `su 2000 -c "app_process … Clip poll 800"` 子进程持续输出 `T:<base64>` |
| 内容变化 | 反射构造 `{content, type}` 并 `emit("onClipboardChange", map)`，等价于 App 自己的 `sendEventToJS`，交给 JS 层走原有加密上行 |

关键点：读剪贴板的是 **uid 2000（shell）**，AOSP 对 `SHELL_UID` 有硬编码放行；
以 root（uid 0）读反而会被 AppOps 拒绝。

## 构建

```bash
./build.sh ~/Downloads/ClipCascade.apk out/clipcascade-merged.apk
# 可选：VERSION_NAME=1.0-ccbridge VERSION_CODE=2
```

依赖：`JDK`、Android `build-tools`（`d8` / `zipalign` / `apksigner`）、`platform` 的 `android.jar`、
`apktool.jar`。路径可用环境变量覆盖：`BUILD_TOOLS`、`ANDROID_JAR`、`APKTOOL_JAR`、`KEYSTORE`。

构建做的事：编译 provider → apktool 反编译（`-s` 保留 dex/lib 原样）→ 插入 `<provider>` →
版本号加后缀 → 放入 `classes3.dex` 与 `assets/clip.dex` → 重打包 → `zipalign -p 4` → 签名。

> 注意：lib 必须保持 `Stored` 且 4K 对齐（App 是 `extractNativeLibs="false"`），
> `zipalign -p 4` 不能省。

## 安装（签名变了，必须卸载重装）

```bash
# 1. 备份数据（含登录态与加密密钥）
adb shell su -c 'tar czf /data/local/tmp/ccdata.tgz -C /data/data com.clipcascade'
adb pull /data/local/tmp/ccdata.tgz

# 2. 卸载 + 安装
adb uninstall com.clipcascade
adb install out/clipcascade-merged.apk
# 首次启动一次，让系统用新 uid 建好数据目录
adb shell am start -n com.clipcascade/.MainActivity

# 3. 恢复数据（uid 会变，必须重新 chown；SELinux 的 per-app category 也要补）
adb shell am force-stop com.clipcascade
UID_NEW=$(adb shell dumpsys package com.clipcascade | grep -m1 -o 'uid=[0-9]*' | cut -d= -f2)
adb shell "su -c 'tar xzf /data/local/tmp/ccdata.tgz --strip-components=1 --no-same-owner -C /data/data/com.clipcascade'"
adb shell "su -c 'chown -R $UID_NEW:$UID_NEW /data/data/com.clipcascade'"
adb shell "su -c 'chcon -R u:object_r:app_data_file:s0:<categories> /data/data/com.clipcascade'"
```

第 3 步的 `<categories>` 用系统刚建好的目录上那串（`ls -Zd /data/data/com.clipcascade`，
形如 `s0:c75,c258,c512,c768`）。**漏掉 categories 会表现为**：
App 崩溃 `WorkManager cannot access the app's internal data store`、
provider 写文件 `EACCES`。而 `restorecon` 只会设到 `app_data_file:s0`，补不回 categories。

## 开自启动（必须手动做一次）

重装会重置 HyperOS 的自启动白名单，开机时广播会被拦：

```
Unable to launch app com.clipcascade for broadcast BOOT_COMPLETED: process is not permitted to auto start
```

到「设置 → 应用设置 → 应用管理 → ClipCascade → 自启动」打开即可。
应用被杀后也能靠它拉起来。

## 已知限制

- **官方更新不能直接装**（签名不同）。每次要 `build.sh` 重新合并，再走一次上面的重装流程。
- 依赖 root（KernelSU/SukiSU）授权 `su`；拒绝授权就退化成官方行为（后台不同步）。
- 剪贴板轮询间隔 800ms，实测手机→桌面 1s 左右。
- 启动后第一次读到的内容只当基线，不推给桌面（避免用手机旧内容覆盖电脑剪贴板）。
