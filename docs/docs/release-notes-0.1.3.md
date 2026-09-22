**安全修复版。** 修掉了 0.1.2 中两个叠加后会直接泄露凭据的问题，并补上发布签名。

> ⚠️ **签名变更，无法覆盖安装。** 本版起改用独立 release 签名密钥（0.1.2 及更早是 debug 签名）。Android 不允许签名不同的包互相覆盖：**需先卸载旧版**，已保存的连接配置会一并清除。`applicationId` 不变（`com.dshhandheld.app`）。

## 🔴 修复的安全问题

0.1.2 发布的 APK 是 **debuggable** 的，而 SSH 密码与 dsh token **以明文存在 SharedPreferences**。两者叠加的后果是：任何人把手机接上 adb（同 Wi-Fi 开无线调试即可），无需 root 就能读走凭据：

```
adb shell run-as com.dshhandheld.app cat shared_prefs/dsh-handheld.xml
```

`run-as` 对 debuggable 应用无需 root 即可工作。同一个包还开着 WebView 远程调试，意味着可以 attach 到 WebView 读走 dsh 认证 cookie 并注入任意 JS。

| | 0.1.2 | 0.1.3 |
|---|---|---|
| `android:debuggable` | `true` | **缺失（= false）** |
| SSH 密码 / dsh token | 明文 | **AndroidKeyStore AES-256-GCM** |
| 签名 | debug 密钥（公开） | **独立 release 密钥** |

### 凭据加密的做法

- 密钥由 **AndroidKeyStore** 持有且不可导出——即便应用私有目录被完整复制到另一台设备也解不开。
- AES-256-GCM，**系统生成随机 IV**（避免固定 IV 这种致命错误）。
- **升级无感**：读到历史明文会原样返回并顺手迁移为密文。
- **失败降级不崩溃**：Keystore 密钥失效（设备策略变更等）时按“未配置”处理，让用户重新输入。
- **不使用** `androidx.security:security-crypto`——该库全部 API 已被官方废弃（1.1.0-beta01 起 “Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore”），故直接按官方指引使用平台 Keystore，不引入额外依赖。

**不在威胁模型内**：已 root 且能在应用进程内执行代码的攻击者——此时应用自身必须能解密，任何应用侧加密都无济于事。

## 其他变更

- **依赖升级**：`webkit` 1.9.0 → **1.15.0**，`core-ktx` 1.12.0 → **1.13.1**（为什么只能升到
  这里，见 [依赖升级上限](releasing.md)）。
- **CI 加固**：改为构建 release 包，secrets 缺失时告警；新增硬校验——产物若含 `application-debuggable` 则**构建直接失败**。签名材料用后即删，不入库。
- **修正 vendored Termux 文件的归属头**：6 个副本中有 2 个实为本地已修改（`ExtraKeyButton` 加 `rowSpan`、`ExtraKeysView` 改字号与跨行），原注释却写 “Vendored unmodified”。
- README 增补「凭据存储」「版本与升级」「Termux vendoring」「发布与签名」四节。

## 构建与验证

本构建由 GitHub Actions 编译产出。产物已实测：

| 项 | 结果 |
|---|---|
| `application-debuggable` | 缺失（= 不可调试） |
| 签名证书 | `CN=dsh-handheld release, O=dsh-handheld`（非 debug 密钥） |
| `package` / `versionCode` / `versionName` | `com.dshhandheld.app` / 30 / 0.1.3 |
| `SecurePrefs` / `MainActivity` / `SshTunnel` | 均在 DEX 中 |

**真机功能验证仍未完成**（SSH 隧道重建、断线恢复、BACK 语义等尚未在实机跑过）。已知问题见 [`docs/known-issues.md`](https://github.com/pawpaw-agent/dsh-handheld/blob/main/docs/known-issues.md)。

## 安装

下载 `dsh-handheld-0.1.3.apk`，按 [标准安装步骤](releasing.md) 操作。本版**签名变更**
（改用独立 release 密钥），装过 0.1.2 或更早版本必须先卸载。

## 许可

见 [标准许可说明](releasing.md)。

---

**SHA-256**（`dsh-handheld-0.1.3.apk`）：`0de177829d3726ce35f50ceaba96a01700e4051711bdf860e663d349362c87da`
