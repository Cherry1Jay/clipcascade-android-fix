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

## 二、⚠️ 本项目产物是对上游作品的修改版本

这一节是 GPL-3.0 **§5(a)** 要求的「显著修改声明」，请勿删除。

**本仓库 `merged/build.sh` 的产物（`clipcascade-merged.apk`）是在上游 ClipCascade 手机端 APK 基础上修改得到的二进制，
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
| 4 | `versionName` / `versionCode` 改写 | 形如 `1.0-ccbridge`（CI 产物为 `ccbridge.<上游tag>`），便于区分官方版 |
| 5 | 重新签名 | 使用本仓库自签密钥（`keystore/ccbridge.jks`），**与原版签名不同** |

上游的 `classes.dex`、`classes2.dex`、`assets/index.android.bundle`、`lib/`、`res/`、`resources.arsc` 均**逐字节保留原样**，
可通过 `merged/build.sh` 从官方 APK 重新构建并比对验证。

### 对应源码（GPL-3.0 §6）

GPL-3.0 要求分发二进制时提供**对应源码**（Corresponding Source）。本项目按下列方式满足：

| 组成 | 对应源码位置 |
|---|---|
| 我们新增的全部代码 | 本仓库：`merged/src/`、`src/Clip.java`、`merged/build.sh`、`scripts/build-dex.sh` |
| 上游原有部分 | 上游仓库 https://github.com/Sathvik-Rao/ClipCascade （release `3.2.0`），未作修改 |
| 复现完整产物 | `cd merged && ./build.sh <官方APK> out/apk` —— 约 9 秒可复现，无需任何私有材料 |

即：任何人拿「上游官方 APK + 本仓库」就能重建出等价的产物。

---

## 三、本仓库的原创部分

以下内容由本仓库作者原创编写，以 **GPL-3.0** 授权（与上游一致）：

| 路径 | 说明 |
|---|---|
| `merged/src/com/jay/ccbridge/BridgeProvider.java` | 进程内桥接：启动钩子、dex 自愈部署、ReactContext 等待、常驻轮询、事件回注 |
| `src/Clip.java` | 反射调用 `IClipboard` 的 `app_process` 载荷（`read` / `write` / `poll` / `diag`） |
| `prebuilt/clip.dex` | 上述 `Clip.java` 的编译产物（构建材料，会被打进 APK 的 `assets/`） |
| `merged/build.sh` | 合并、重打包、对齐、签名的构建脚本 |
| `scripts/build-dex.sh` | 不依赖 Android Studio 的 `clip.dex` 构建脚本 |
| `scripts/verify-merged.sh` | 产物静态冒烟检查（provider / 符号 / 对齐） |
| `.github/workflows/upstream-sync.yml` | 跟进上游发版的自动构建流水线 |
| `README.md`、`NOTICE.md`、`DISCLAIMER.md`、`docs/*`、`merged/README.md` | 文档 |

---

## 四、第三方组件

### 随本仓库分发

| 组件 | 许可证 | 用途 |
|---|---|---|
| `prebuilt/clip.dex` | GPL-3.0（本仓库授权） | 预编译产物，源码见上一节 |

### 构建期 / 运行时依赖（**不随本仓库分发**）

| 组件 | 许可证 | 用途 |
|---|---|---|
| Android SDK `build-tools` / `platform`（`android.jar`） | Android SDK License / Apache-2.0（AOSP 部分） | 编译期，由 CI 与脚本从 Google 官方源下载 |
| AOSP `IClipboard` / `ClipboardService` 接口 | Apache-2.0 | 运行时以**反射**调用，非静态链接 |
| [Apktool](https://github.com/iBotPeaches/Apktool) | Apache-2.0 | 重打包工具 |
| [KernelSU](https://github.com/tiann/KernelSU) / SukiSU | GPL-3.0 | 提供 `su` |
| GitHub Actions runner 组件 | 各自许可 | CI |

---

## 五、GPL-3.0 合规提示

若你分发本仓库的**修改版本**或**二进制形式**（包括把 `clipcascade-merged.apk`、`clip.dex` 打进你的发行包）：

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
- **AOSP** `ClipboardService` / `IClipboard` 的维护者 —— 对 `SHELL_UID` 的硬编码放行是本案的立足点。
- **Apktool**、**KernelSU** 的作者与维护者 —— 没有这些工具链，本项目无法实现。

---

*最后更新：2026-10-22*
