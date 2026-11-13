# ClipCascade 安卓后台剪贴板修复（合并版）

让 **「手机复制 → 电脑粘贴」** 在较新的 Android 上重新自动生效。

针对 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade) 一个长期断点：Android 10 之后 App 在**后台**读不到剪贴板，
导致只有「电脑 → 手机」能自动同步，反向必须手动分享。本项目把修复逻辑**直接编进官方 APK** ——
装完只有一个软件包，不需要 LSPosed、不需要 adb、不需要电脑常开。

> ⚠️ 使用前请完整阅读 **[免责声明](DISCLAIMER.md)** 与 **[NOTICE（许可与致谢）](NOTICE.md)**。
> 本项目会读取并转发你的剪贴板内容。**装的是重新签名的非官方 APK**，请自行评估风险。

---

## 它怎么工作

### 三个排查结论

| 尝试 | 结果 |
|---|---|
| `appops set com.clipcascade READ_CLIPBOARD allow` | ❌ 无效。拦截点在 `ClipboardService.clipboardAccessAllowed(...)`，它不看这个 appop |
| LSPosed 模块注入 `system_server` 改 `clipboardAccessAllowed` | ❌ 失败。legacy(api 82) 与 modern(libxposed 102) 模块都进不了 system_server |
| 只解决「读被拒」 | ❌ 不够。App 切后台会自己调 `stopListening()`，后台连「剪贴板变了」的事件都不发生 |

官方文档给的 workaround 是授予 `READ_LOGS`、从 logcat 抓剪贴板内容 —— **Android 15 之后已失效**。

### 绕过点：系统对 shell 的硬编码放行

AOSP 的 `ClipboardService.clipboardAccessAllowed()` 里有一段：

```java
} else if (uid == Process.SHELL_UID) {
    // Shell can access the clipboard for testing purposes.
    return true;
}
```

`SHELL_UID` 就是 2000。一个反直觉的实测结果：

| 身份 | 读剪贴板 |
|---|---|
| `adb shell`（uid 2000） | ✅ 正常 |
| `su -c`（uid 0，root） | ❌ 返回 `EMPTY` |
| `su 2000 -c`（root 降权到 shell） | ✅ 正常 |

AppOps 按**调用 uid** 判定，root 反而不放行。所以本项目读剪贴板时一律降权到 2000。

### 合并后的数据流

```
BridgeProvider.onCreate()          ← ContentProvider 是免费的进程启动钩子
   │
   ├─ su 把 assets/clip.dex 复制到 /data/local/tmp（自愈）
   │
   ├─ 等 ReactContext 就绪（反射 MainApplication → ReactNativeHost → ReactInstanceManager）
   │
   ├─ 常驻一个 su 2000 -c "app_process … Clip poll 800" 子进程
   │      └─ 持续输出 T:<base64>（uid 2000 直调 IClipboard，不受焦点限制）
   │
   └─ 内容变化 → 反射构造 {content, type} → emit("onClipboardChange")
                     └─ 等价于 App 自己的 sendEventToJS，交给 JS 层走原有加密上行
```

关键点：**真正干活的还是 ClipCascade 自己**。我们只是把「它读不到」那一步换成 root 代读，再把事件递回去。
所以账号、密钥、端到端加密、多设备同步全部沿用原样，一行都没改。

反过来（桌面 → 手机）本来就由 ClipCascade 自己负责，本项目不参与。

---

## 用法

### 1. 构建

```bash
# 依赖：JDK 17+、Android build-tools（d8/zipalign/apksigner）、platform android.jar、apktool.jar
cd merged
./build.sh ~/Downloads/ClipCascade.apk out/clipcascade-merged.apk
```

环境变量可覆盖：`BUILD_TOOLS`、`ANDROID_JAR`、`APKTOOL_JAR`、`KEYSTORE`、`VERSION_NAME`、`VERSION_CODE`。

脚本做的事：编译 `BridgeProvider` → apktool 反编译（`-s` 保留 dex/lib 原样）→ 插入 `<provider>` →
改版本号 → 放入 `classes3.dex` 与 `assets/clip.dex` → 重打包 → **`zipalign -p 4`** → 签名。

> lib 必须保持 `Stored` 且 4K 对齐（App 是 `extractNativeLibs="false"`），`zipalign -p 4` 不能省。

构建完可以跑一遍静态冒烟检查：

```bash
./scripts/verify-merged.sh out/clipcascade-merged.apk
```

它会验证 provider 已声明、`clip.dex` 一致、上游仍含反射依赖的符号（`getReactNativeHost`、
`RCTDeviceEventEmitter`、`onClipboardChange` 等），以及原生库对齐。**上游改结构时这一步会失败**，
避免产出一个「装得上但不同步」的包。

### 2. 安装

签名与官方不同，**必须先卸载**。

```bash
adb uninstall com.clipcascade
adb install clipcascade-merged.apk
```

### 3. 迁移数据（想保留登录态与加密密钥时做）

重装后 **uid 会变**，直接解压旧数据会因属主不对而崩：

```bash
# 备份（在卸载之前做）
adb shell su -c 'tar czf /data/local/tmp/ccdata.tgz -C /data/data com.clipcascade'
adb pull /data/local/tmp/ccdata.tgz

# 装完先启动一次，让系统用新 uid 建好目录，然后 force-stop
adb shell am force-stop com.clipcascade

UID_NEW=$(adb shell dumpsys package com.clipcascade | grep -m1 -o 'uid=[0-9]*' | cut -d= -f2)
adb shell "su -c 'tar xzf /data/local/tmp/ccdata.tgz --strip-components=1 --no-same-owner -C /data/data/com.clipcascade'"
adb shell "su -c 'chown -R $UID_NEW:$UID_NEW /data/data/com.clipcascade'"
adb shell "su -c 'chcon -R u:object_r:app_data_file:s0:<categories> /data/data/com.clipcascade'"
```

`<categories>` 取系统刚建好的目录上那串（`ls -Zd /data/data/com.clipcascade`，形如 `s0:c75,c258,c512,c768`）。

### 4. 打开自启动（必做一次）

重装会重置 HyperOS 的自启动白名单，开机广播会被拦：

```
Unable to launch app com.clipcascade for broadcast BOOT_COMPLETED: process is not permitted to auto start
```

到「设置 → 应用管理 → ClipCascade → 自启动」打开。顺手把省电策略设成「无限制」。

---

## 自动跟进上游

`.github/workflows/upstream-sync.yml` 会**每天两次**检查上游 Release：

```
check（比对上游 tag 与 upstream-* 标签）
  → build（下载官方 ClipCascade.apk、合并、静态冒烟）
  → release（打 upstream-<tag> 标签、发 Release、附 APK 与校验和）
```

- 只在上游发新版本时构建，已发布过就跳过。
- 失败会**自动开 issue**（上游改结构、资产改名、对齐失败等）。
- 也可手动触发：Actions → `upstream-sync` → Run workflow，勾选 `force` 强制重建。
- 产物版本号：`versionName=ccbridge.<上游tag>`，`versionCode` 用日期+运行号保证可覆盖安装。

> CI 用的是仓库内的自签密钥（`keystore/ccbridge.jks`，口令 `android`）。它是**公开的调试密钥**，
> 只为保证「本地构建」与「CI 构建」签名一致、能互相覆盖安装。要自己用就换成你的密钥并设 `KEYSTORE`。

### 发布版本怎么读

| 标签 | 来源 | 说明 |
|---|---|---|
| `upstream-<tag>` | CI 自动 | **装这个**。跟随上游 release tag，例如 `upstream-3.2.0` |
| `v2.0.0` | 手工构建 | 最初在真机上验证通过的那一版，已被 `upstream-*` 取代，保留作记录 |

`versionName` 规则：手工版 `1.0-ccbridge`；CI 版 `ccbridge.<上游tag>`。
`versionCode`：手工版是 `2`，CI 版为「日期 × 100 + 运行号」（如 `26102201`），保证单调递增、可覆盖安装。

> 你自己改代码重建时，建议设 `VERSION_CODE` 为一个比当前已装版本更大的值，否则 `adb install -r` 会报版本降级。

---

## 支持范围

| 项 | 状态 |
|---|---|
| 文本（含中文、多字节、长文本） | ✅ 实测可用（含 8 KB） |
| 图片 / 文件 / 富文本 | ❌ 不处理 |
| 手机 → 桌面 | ✅ 本项目负责 |
| 桌面 → 手机 | ✅ 由 ClipCascade 原生负责 |
| 锁屏状态下同步 | ❌ 系统会隐藏剪贴板 |
| 需要 root | ✅ 需要（`su`） |

## 测试环境

全部数据来自**实际跑通**。完整版本表与复现步骤见 [`docs/TESTED-ENV.md`](docs/TESTED-ENV.md)。

| 角色 | 具体版本 |
|---|---|
| 手机 | 小米 17 Pro（`25098PN5AC` / `pandora`），**Android 17 / SDK 37 / HyperOS OS4.0**，KernelSU |
| 桌面 | EndeavourOS（Arch rolling），内核 `7.2.9-arch1-1`，GNOME 51 **Wayland** |
| ClipCascade 服务端 | Docker `sathvikrao/clipcascade:0.7.0` |
| ClipCascade 桌面端 | Linux GUI `3.2.0` |
| ClipCascade 手机端 | `com.clipcascade` `versionName=1.0` |
| 编译 | build-tools 36.0.0 + platform android-36，apktool 3.0.3 |

**未测试**：Android 10–16、非小米 ROM、纯原生 AOSP、无 root 环境（本项目离不开 root）。
跑通了或跑不通都欢迎开 issue 补充。

## 已知限制

1. **官方更新不能直接装**（签名不同）。每次要重新构建并重装 —— 用 CI 产物可以省掉构建这一步。
2. **依赖 root 授予 `su`**；拒绝授权就退化成官方行为（后台不同步）。
3. **锁屏期间整条链路静默**。`getPrimaryClip()` 返回 `null`，`setPrimaryClip()` 不报错但不可靠。
4. **只同步文本**。
5. **轮询有固定成本**：800 ms 一轮，手机侧持续唤醒。
6. 启动后第一次读到的内容只当基线，不推给桌面（避免用手机旧内容覆盖电脑剪贴板）。
7. 依赖 `SHELL_UID` 放行与特定 `IClipboard` 方法签名这两处 AOSP 实现细节，**系统大版本更新后可能直接失效且不报错**。

## 安全与隐私

- **剪贴板内容在本项目这段链路上是不加密的**（设备内部以明文经 `su` 子进程传递）。
  ClipCascade 自身的端到端加密只覆盖「设备 → 服务端 → 设备」那一段。
- **绝不建议把 token / 密码 / 私钥临时放进剪贴板**跨设备传递 —— 它会全链路留痕，包括输入法剪贴板历史。
- 本项目不把剪贴板内容写进任何日志。

## 卸载

```bash
adb uninstall com.clipcascade
adb install <官方 ClipCascade.apk>
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
| 本项目基于的上游版本 | 手机端 `versionName=1.0`（上游 release `3.2.0`） |

**致谢**：

- **Sathvik Rao** —— ClipCascade 本体。没有这个项目，就没有「手机复制、电脑粘贴自动同步」这件事本身；
  本仓库只是把它在较新 Android 上断掉的那一条腿接回去。
- AOSP `ClipboardService` / `IClipboard` 维护者 —— 对 `SHELL_UID` 的硬编码放行是本案的立足点。
- [Apktool](https://github.com/iBotPeaches/Apktool)（Apache-2.0）、[KernelSU](https://github.com/tiann/KernelSU)（GPL-3.0）——
  构建与 root 工具链。

**许可**：本仓库原创代码以 **GPL-3.0** 授权，与上游一致，全文见 [LICENSE](LICENSE)。
本仓库产出的 APK 是**对上游二进制的修改版本**，分发条件见 [NOTICE](NOTICE.md) 第二节。

`ClipCascade` 名称与标识归其作者所有，本项目仅作事实性提及，不构成隶属、合作或背书。

---

*本项目按「现状」提供，不附带任何担保。使用即表示接受 [DISCLAIMER](DISCLAIMER.md) 全部条款。*

<!-- donate:start -->
---

## 支持作者 / Support the author

本项目**完全免费**，不设付费版、不做功能阉割，也没有任何强制署名或引流要求。你可以在遵守许可证的前提下自由使用、修改和分发。

如果它确实帮到了你，欢迎**自愿打赏**，金额随意；不打赏完全不影响使用，也不影响后续更新。

<table>
  <tr>
    <td align="center"><b>支付宝（推荐）</b><br>
      <img src="assets/donate/alipay.jpg" width="240" alt="支付宝收款码"></td>
    <td align="center"><b>微信支付</b><br>
      <img src="assets/donate/wechat.jpg" width="240" alt="微信收款码"></td>
  </tr>
</table>

<sub>Free to use — this project is and stays free. A voluntary tip is appreciated, never required.</sub>
<!-- donate:end -->
