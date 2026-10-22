# NOTICE — 归属、来源、修改声明与许可

本文件说明本仓库的代码来源、与上游项目的关系、**对上游作品所做的修改**，以及许可证的适用方式。
分发本仓库、其修改版本或其二进制产物时，必须保留本文件。

---

## 一、上游作品与版权归属

| 项 | 内容 |
|---|---|
| 项目 | **ClipCascade** |
| 作者 | **Sathvik Rao**（GitHub [@Sathvik-Rao](https://github.com/Sathvik-Rao)） |
| 仓库 | https://github.com/Sathvik-Rao/ClipCascade |
| 版权 | **Copyright (C) Sathvik Rao and ClipCascade contributors** |
| 许可证 | **GNU General Public License v3.0**（GPL-3.0） |
| 本项目基于的上游版本 | 上游 release **`3.2.0`**；手机端 APK `com.clipcascade` `versionName=1.0` |

上游作品的全部版权归其作者与贡献者所有。本仓库**不主张**对上游作品的任何权利。

---

## 二、⚠️ 方案 A（合并版 APK）是对上游作品的修改版本

这一节是 GPL-3.0 **§5(a)** 要求的「显著修改声明」，请勿删除。

**`merged/` 目录下的产物（`clipcascade-merged.apk`）是在上游 ClipCascade 手机端 APK 基础上修改得到的二进制，
属于 GPL-3.0 意义上的修改版本（modified version），不是独立作品。**

| 项 | 内容 |
|---|---|
| 修改者 | FairyJJ（[@FairyJJ](https://github.com/FairyJJ)） |
| 修改日期 | **2026-10-22** |
| 修改对象 | 上游 ClipCascade 手机端 APK，`versionName=1.0` |
| 修改性质 | 附加代码 + 清单声明 + 版本号后缀；**未删除、未修改上游任何既有代码或资源** |

### 具体修改内容（逐条）

| # | 改动 | 说明 |
|---|---|---|
| 1 | 新增 `classes3.dex` | 编入 `merged/src/com/jay/ccbridge/BridgeProvider.java`，本仓库原创 |
| 2 | `AndroidManifest.xml` 增加一个 `<provider>` | 声明 `com.jay.ccbridge.BridgeProvider`，用于进程启动钩子 |
| 3 | 新增 `assets/clip.dex` | 本仓库原创的剪贴板读取载荷（源码 `src/Clip.java`） |
| 4 | `versionName` / `versionCode` 追加后缀 | 改为 `1.0-ccbridge` / 提升 code，便于区分官方版 |
| 5 | 重新签名 | 使用本仓库自签密钥（`lsposed-hook/keystore.jks`），**与原版签名不同** |

上游的 `classes.dex`、`classes2.dex`、`assets/index.android.bundle`、`lib/`、`res/`、`resources.arsc` 均**逐字节保留原样**，
可通过 `merged/build.sh` 从官方 APK 重新构建并比对验证。

### 对应源码（GPL-3.0 §6）

GPL-3.0 要求分发二进制时提供**对应源码**（Corresponding Source）。本项目按下列方式满足：

| 组成 | 对应源码位置 |
|---|---|
| 我们新增的全部代码 | 本仓库：`merged/src/`、`src/Clip.java`、`merged/build.sh`、`scripts/build-dex.sh` |
| 上游原有部分 | 上游仓库 https://github.com/Sathvik-Rao/ClipCascade （release `3.2.0`），未作修改 |
| 复现完整产物 | `cd merged && ./build.sh <官方APK> out/apk` —— 9 秒可复现，无需任何私有材料 |

即：任何人拿「上游官方 APK + 本仓库」就能重建出完全等价的产物。

---

## 三、本仓库的原创部分

以下内容由本仓库作者原创编写，以 **GPL-3.0** 授权（与上游一致）：

| 路径 | 说明 |
|---|---|
| `src/Clip.java` | 反射调用 `IClipboard` 的 `app_process` 载荷（`read` / `write` / `poll` / `diag`） |
| `prebuilt/clip.dex` | 上述 `Clip.java` 的编译产物 |
| `src/phone_bridge.py` | 方案 C 的桌面端桥接守护进程（设备发现、流消费、剪贴板写入、脱敏日志） |
| `systemd/clipcascade-phone-bridge.service` | 方案 C 的 systemd 用户单元 |
| `lsposed-hook/src/com/jay/cchook/MainHook.java` | 方案 B 的 LSPosed 模块（modern libxposed API 102） |
| `merged/src/com/jay/ccbridge/BridgeProvider.java` | 方案 A 的进程内桥接 |
| `scripts/build-dex.sh`、`merged/build.sh`、`lsposed-hook/build.sh` | 构建脚本 |
| `merged/`、`lsposed-hook/` 下的 `META-INF/`、`assets/`、`AndroidManifest.xml` | 模块/补丁元信息 |
| `README.md`、`NOTICE.md`、`DISCLAIMER.md`、`docs/*`、各子目录 README | 文档 |

---

## 四、第三方组件

### 随本仓库分发

| 组件 | 许可证 | 用途 |
|---|---|---|
| `prebuilt/clip.dex`、`lsposed-hook/prebuilt/cchook.apk` | GPL-3.0（本仓库授权） | 预编译产物，源码见上一节 |

### 运行时 / 构建期依赖（**不随本仓库分发**）

| 组件 | 许可证 | 用途 |
|---|---|---|
| Android SDK `build-tools` / `platform`（`android.jar`） | Android SDK License / Apache-2.0（AOSP 部分） | 编译期，由脚本从 Google 官方源按需下载 |
| AOSP `IClipboard` / `ClipboardService` 接口 | Apache-2.0 | 运行时以**反射**调用，非静态链接 |
| [libxposed/api](https://github.com/libxposed/api) | Apache-2.0 | 方案 B 编译期依赖 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | GPL-3.0 | 方案 B 运行时框架 |
| [Apktool](https://github.com/iBotPeaches/Apktool) | Apache-2.0 | 方案 A 重打包工具 |
| [KernelSU](https://github.com/tiann/KernelSU) / SukiSU | GPL-3.0 | 提供 `su`（方案 A / B） |
| `wl-clipboard` | GPL-3.0 | 方案 C 桌面剪贴板（Wayland） |
| `xclip` | GPL-2.0 | 方案 C 桌面剪贴板（X11 备用） |
| `android-tools`（`adb`） | Apache-2.0 | 方案 C 传输通道 |
| Python 标准库 | PSF License | 方案 C 运行时 |

---

## 五、GPL-3.0 合规提示

若你分发本仓库的**修改版本**或**二进制形式**（包括把 `clipcascade-merged.apk`、`clip.dex`、`cchook.apk` 打进你的发行包）：

1. 必须保留本 `NOTICE.md` 与 `LICENSE`；
2. 必须以 GPL-3.0 授权你的修改，并提供对应**完整源代码**及可用的构建方式；
3. 必须**显著标明**你做了哪些修改（本文件第二节即是范例），并给出日期；
4. 不得附加任何限制接收者行使 GPL 权利的条款；
5. 分发修改版 APK 时，还须保留上游的版权声明与许可文本，不得暗示上游对你的修改提供背书。

---

## 六、关于本仓库的免责声明（不是许可限制）

本仓库的 [`DISCLAIMER.md`](DISCLAIMER.md) 是**责任与风险提示**，用于在适用法律允许的范围内排除担保与责任。
它**不构成**对 GPL-3.0 所授予权利（运行、研究、修改、再分发）的额外限制，也不是 GPL-3.0 §7 意义上的附加条款。
若其中任何表述与 GPL-3.0 冲突，以 GPL-3.0 为准。

---

## 七、商标与隶属关系

- `ClipCascade` 名称及相关标识归其作者所有，本项目**仅作事实性提及**以说明互操作对象。
- 本项目是**独立第三方工具**，与上游及其作者**无任何隶属、合作、赞助或背书关系**。
- **请勿**就本项目的问题到上游仓库提 issue。

详见 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 八、致谢

- **Sathvik Rao** 与 ClipCascade 贡献者 —— 本项目只是给上游作品接回它在较新 Android 上断掉的一条腿。
- **AOSP** `ClipboardService` / `IClipboard` 的维护者 —— 对 `SHELL_UID` 的硬编码放行是全部方案的立足点。
- **libxposed**、**LSPosed**、**Apktool**、**KernelSU** 的作者与维护者 —— 没有这些工具链，方案 A / B 无法实现。
- **wl-clipboard** / **xclip** / **android-tools** —— 方案 C 的运行时基础。

---

*最后更新：2026-10-22*
