# 免责声明 / DISCLAIMER

**请在使用、复制、修改或分发本项目之前完整阅读本页。**
下载、安装、运行或以任何方式使用本项目，即视为你已阅读、理解并接受下列全部条款。
如果你不同意其中任何一条，请立即停止使用并删除本项目。

---

## 1. 无任何担保

本项目按**「现状」（AS IS）**提供，不附带任何形式的明示或默示担保，包括但不限于对**适销性**、
**特定用途适用性**、**不侵权**、**无缺陷**、**不中断运行**的担保。

作者不保证本项目：

- 与你的设备型号、系统版本、ROM、ClipCascade 版本兼容；
- 在任何情况下不丢失、不篡改、不重复同步剪贴板内容；
- 不存在缺陷、漏洞或未预期的行为；
- 能够满足你的任何特定功能或合规要求。

## 2. 责任限制

在适用法律允许的最大范围内，**作者及任何贡献者均不对**因使用或无法使用本项目而产生的
**任何直接、间接、附带、特殊、惩罚性或后果性损害**承担责任，包括但不限于：

- 数据丢失、数据损坏、剪贴板内容错乱或被覆盖；
- **凭据泄露**（token、密码、验证码、私钥、API key 等）；
- 隐私泄露、商业秘密泄露、通信内容外泄；
- 设备异常、系统不稳定、应用崩溃、电池损耗；
- 业务中断、利润损失、商誉损失；
- 因本项目的使用而导致的任何第三方索赔。

**即使作者已被告知上述损害的可能性，本条依然适用。**

## 3. 你应当知道的风险

本项目的工作方式决定了以下风险是**内生的**，不是缺陷：

| 风险 | 说明 |
|---|---|
| **剪贴板内容明文传输** | 内容经 `adb` 通道原样传到桌面并写入系统剪贴板，未做额外加密。同机上可执行 `adb` 的进程均可能读取。 |
| **日志与凭据** | 本项目刻意只记录「字数 + 指纹」而**不记录原文**。但如果你自行修改脚本、或使用其他工具（输入法剪贴板历史、ClipCascade 服务端、shell 历史、`journalctl` 之外的日志采集）导致内容留痕，后果由你承担。**绝不建议通过剪贴板传递敏感凭据。** |
| **无线调试暴露端口** | `persist.adb.tcp.port=5555` 会让 adb 在网络接口上监听。请限制在内网，或配合防火墙；不使用时关闭。 |
| **轮询耗电** | 手机侧持续轮询会带来额外唤醒与耗电。 |
| **锁屏状态不可靠** | 锁屏时系统隐藏剪贴板，读取返回空、写入不报错但结果不确定，可能出现「看起来没同步」。 |

## 4. 合规与合法使用

你**自行负责**确保使用行为符合：

- 所在国家或地区的**法律法规**（包括但不限于网络安全、数据安全、个人信息保护相关法律）；
- 你的**雇主或组织的内部政策**；
- 你所用设备与服务（手机厂商、云服务、ClipCascade 服务端等）的**服务条款**；
- 任何适用于剪贴板内容（可能包含他人个人信息、商业秘密）的**授权与同意要求**。

**严禁**将本项目用于：未经授权读取他人设备剪贴板、窃取凭据、监控他人、绕过组织安全管控，
或任何其他违法、侵权用途。此类行为与本项目作者无关，责任由行为人自负。

## 5. 与上游项目无关

本项目是**第三方独立工具**，与 [ClipCascade](https://github.com/Sathvik-Rao/ClipCascade)
及其作者 **Sathvik Rao 无任何隶属、合作或背书关系**。

- 上游项目不对本项目提供任何支持、担保或认可；
- **请勿**就本项目的问题到上游仓库提 issue；
- 上游的许可（GPL-3.0）与本项目的许可相互独立，详见 [NOTICE](NOTICE)。

## 6. 无技术支持义务

作者没有义务提供技术支持、缺陷修复、版本更新或问题答复。任何形式的帮助均属**自愿且无担保**，
不构成对本条款的豁免。

## 7. 协议与条款变更

作者可在不另行通知的情况下修改本项目及本免责声明。你继续使用即视为接受修改后的条款。
本条款的可分割性：若任一条款被认定无效或不可执行，其余条款仍完整有效。

---

*Last updated: 2026-10-22*

---

## English (short version)

This project is provided **AS IS**, without warranty of any kind. You are solely responsible for how you use it,
for the legality of that use, and for any consequences — including credential or privacy leakage, data loss, device
malfunction, or violation of any law, policy, or terms of service.

Clipboard content transits the `adb` channel in plaintext. **Never put tokens, passwords, or private keys on the
clipboard for cross-device transfer.** This project intentionally logs only content length and a truncated hash,
never the raw text — keep that invariant if you fork it.

This is an independent third-party tool. It is **not affiliated with, endorsed by, or supported by** the upstream
ClipCascade project or its author. Do not file issues about this project upstream.

The authors accept no liability for any direct, indirect, incidental, special, punitive, or consequential damages
arising from the use of, or inability to use, this software — even if advised of the possibility of such damages.
