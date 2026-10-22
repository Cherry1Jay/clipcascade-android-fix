# 免责声明 / DISCLAIMER

**请在使用、复制、修改或分发本项目之前完整阅读本页。**
下载、安装、运行或以任何方式使用本项目，即视为你已阅读、理解并接受下列全部条款。
如果你不同意其中任何一条，请立即停止使用并删除本项目。

> 本免责声明是**责任与风险提示**，不构成对 [GPL-3.0](LICENSE) 所授予权利的额外限制。
> 与许可条款冲突时，以 GPL-3.0 为准（见 [NOTICE](NOTICE.md) 第六节）。

---

## 1. 无任何担保

本项目按**「现状」（AS IS）**提供，不附带任何形式的明示或默示担保，包括但不限于对**适销性**、
**特定用途适用性**、**不侵权**、**无缺陷**、**不中断运行**的担保。

作者不保证本项目：

- 与你的设备型号、系统版本、ROM、Root 方案、ClipCascade 版本兼容；
- 在任何情况下不丢失、不篡改、不重复同步剪贴板内容；
- 不存在缺陷、漏洞或未预期的行为；
- 能够满足你的任何特定功能或合规要求。

## 2. 责任限制

在适用法律允许的最大范围内，**作者及任何贡献者均不对**因使用或无法使用本项目而产生的
**任何直接、间接、附带、特殊、惩罚性或后果性损害**承担责任，包括但不限于：

- 数据丢失、数据损坏、剪贴板内容错乱或被覆盖；
- **凭据泄露**（token、密码、验证码、私钥、API key 等）；
- 隐私泄露、商业秘密泄露、通信内容外泄；
- **设备无法开机、系统不稳定、应用崩溃、数据分区损坏**（尤其是执行 `su`、重签名安装、
  修改 `SELinux` 上下文等操作时）；
- **保修失效**、厂商服务拒绝、Root 导致的系统完整性校验失败；
- 业务中断、利润损失、商誉损失；
- 因本项目的使用而导致的任何第三方索赔。

**即使作者已被告知上述损害的可能性，本条依然适用。**

## 3. 你应当知道的风险

本项目的工作方式决定了以下风险是**内生的**，不是缺陷：

| 风险 | 说明 |
|---|---|
| **安装的是非官方签名 APK** | 产物由作者自签（`keystore/ccbridge.jks`，口令公开），**与官方签名不同**。安装前必须卸载官方版，这会导致：登录态需自行备份迁移、HyperOS 等 ROM 的自启动白名单被重置、无法直接覆盖升级官方版本。 |
| **官方更新不可直接安装** | 每次上游发版都要重新构建并重装，否则会停留在旧版本。作者无义务跟进上游更新（仓库内虽有自动流水线，但不保证长期可用）。 |
| **剪贴板内容明文流转** | 在本项目新增的这段链路上内容不加密：以明文经 `su` 子进程从 uid 2000 的进程读出，再交给 App 的 JS 层。ClipCascade 自身的端到端加密**只覆盖**「设备 → 服务端 → 设备」那一段。 |
| **日志与凭据** | 本项目不把剪贴板内容写进日志。但 App 自身、输入法剪贴板历史、ClipCascade 服务端等其他环节可能留痕，后果由你承担。**绝不建议通过剪贴板传递敏感凭据。** |
| **Root 权限** | 本项目需要 `su`。虽然调用形式是 `su 2000 -c …`（降权到 shell uid），但授予 root 本身即扩大攻击面。请仅使用可信来源的 Root 方案。 |
| **依赖 AOSP 实现细节** | 本案立足点是 `SHELL_UID` 的硬编码放行与特定 `IClipboard` 方法签名。**系统大版本更新后可能直接失效，且不会报错**，表现为「复制了没反应」。 |
| **轮询耗电** | 手机侧 800 ms 一轮持续轮询，会带来额外唤醒与耗电。 |
| **锁屏状态不可靠** | 锁屏时系统隐藏剪贴板，读取返回空、写入不报错但结果不确定，可能出现「看起来没同步」。 |
| **数据迁移操作有风险** | 恢复备份时的 `chown` / `chcon` 命令若用错路径或 uid，可能导致 App 无法启动。请照 README 的步骤逐条执行，并在卸载前完成备份。 |

## 4. 合规与合法使用

你**自行负责**确保使用行为符合：

- 所在国家或地区的**法律法规**（包括但不限于网络安全、数据安全、个人信息保护相关法律）；
- 你的**雇主或组织的内部政策**（很多单位禁止在办公设备上使用 Root 或非官方签名应用）；
- 你所用设备与服务（手机厂商、云服务、ClipCascade 服务端等）的**服务条款**；
- 任何适用于剪贴板内容（可能包含他人个人信息、商业秘密）的**授权与同意要求**。

**严禁**将本项目用于：未经授权读取他人设备剪贴板、窃取凭据、监控他人、绕过组织安全管控，
或任何其他违法、侵权用途。此类行为与本项目作者无关，责任由行为人自负。

## 5. 与上游项目无关

本项目是**第三方独立工具**，与 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade)
及其作者 **Sathvik Rao 无任何隶属、合作或背书关系**。

- 上游项目不对本项目提供任何支持、担保或认可；
- **请勿**就本项目的问题到上游仓库提 issue；
- 本项目产出的合并版 APK 是**作者修改的版本**，不是官方发布物。遇到任何异常，请先怀疑本项目而不是上游。

## 6. 无技术支持义务

作者没有义务提供技术支持、缺陷修复、版本更新或问题答复。任何形式的帮助均属**自愿且无担保**，
不构成对本条款的豁免。

## 7. 条款变更与可分割性

作者可在不另行通知的情况下修改本项目及本免责声明。你继续使用即视为接受修改后的条款。
若任一条款被认定无效或不可执行，其余条款仍完整有效。

---

*Last updated: 2026-10-22*

---

## English (short version)

This project is provided **AS IS**, without warranty of any kind. You are solely responsible for how you use it,
for the legality of that use, and for any consequences — including credential or privacy leakage, data loss,
device malfunction or bricking, voided warranty, or violation of any law, policy, or terms of service.

The output APK is a **modified build of the upstream ClipCascade Android app**, signed with a project-local key.
It is **not** an official release, cannot be updated over-the-air from upstream, and requires uninstalling the
official app first (which drops your login state unless you migrate the app data yourself).

Clipboard content transits the added code path in plaintext. ClipCascade's own end-to-end encryption covers only
the device → server → device leg, **not** the path this project adds. **Never put tokens, passwords, or private
keys on the clipboard for cross-device transfer.**

The approach relies on two AOSP implementation details (`SHELL_UID` clipboard exemption and a specific
`IClipboard` method signature) and **may silently break after a system upgrade**.

This is an independent third-party tool. It is **not affiliated with, endorsed by, or supported by** the upstream
ClipCascade project or its author. Do not file issues about this project upstream.

The authors accept no liability for any direct, indirect, incidental, special, punitive, or consequential damages
arising from the use of, or inability to use, this software — even if advised of the possibility of such damages.

This disclaimer does **not** limit any rights granted to you by the GNU GPL v3.0; where they conflict, the license
prevails. See [NOTICE](NOTICE.md) for attribution and the modification statement required by GPL-3.0 §5(a).
