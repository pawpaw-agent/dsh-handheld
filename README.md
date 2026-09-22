# dsh-handheld

**把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）装进口袋的 Android 客户端。**

完整 dsh Web 界面 + 内置 SSH 隧道 + 终端模式。**服务端零改动**：不用装服务端插件，也不需要 `--host 0.0.0.0`。

```
App（全屏 WebView / SSH 终端）
   └─ 内置 SSH 本地端口转发（dropbear dbclient）
        ─▶ 电脑上的 dsh --profile web     ← 服务端视角：请求来自本机回环
```

---

## 为什么是 SSH 隧道

DSH 把**配置平面**（设置页、凭据管理、模型探测、目录选择…）限制为**仅本机回环可访问**，判据是
`isTrustedApiRequest`（Host 必须是回环或在 `trustedHosts` 里，且 Origin 与 Host 同源）。从局域网 IP
访问这些接口只会被拒——这是官方的安全边界，本项目不绕过它。

SSH 本地端口转发让服务端**仍然认为请求来自本机**，因此是既能远程使用、又不破坏官方安全模型的
完整方案，还顺带多一层 SSH 认证。这一切 App 内置了：填一次账号密码，隧道由 App 自己维持。

| | 局域网直连 | **内置 SSH 隧道** |
|---|---|---|
| 对话 / 会话 / 工作区 | ✅ | ✅ |
| 设置页 / 凭据 / 模型探测 | ❌ | ✅ |
| 额外服务端插件 | 需要 | **不需要** |
| 认证 | dsh token | **SSH + dsh token（双重）** |

---

## 功能

- **看 dsh 网页** — 全屏 WebView 加载 dsh 官方前端，功能与桌面端一致（Markdown、代码高亮、会话树、设置页、模型管理……）。WebView 跨 Activity 重建保持存活，回到 App 不重载。
- **内置 SSH 隧道** — 打包 dropbear `dbclient`（arm64）做进程式本地端口转发，固定 `3080`（被占用时回退 `13080`）。断线自动重连，App 重启后自动重建。认证支持**密码**与**私钥**；私钥现阶段须为 dropbear 原生格式，**带口令的私钥不支持**（手机端 SSH 组件解不开）。
- **打开终端** — 经 SSH PTY 打开远程 shell，用 Termux 的 [terminal-view](https://github.com/termux/termux-app) 原生渲染（真 IME 交互，非 WebView），底部常驻键排（ESC / TAB / CTRL / 方向键…）。进去就是普通远程 shell，可自由输入 `dsh-tui` 等命令，不做任何自动启动。
- **token 全自动** — dsh 0.1.2+ 的浏览器 token 由 App 从服务端自动提取并保存，服务重启后无需手动更新；失败时回退到连接屏手动填写。
- **任务完成提醒** — dsh 生成结束时 App 自己发通知（0.1.12 起**浮出横幅**），点开回到 App。**默认关闭**，在连接屏「任务完成时提醒我」打开；App 在前台时不打扰。不依赖任何服务端推送配置。
- **连接屏刻意去术语** — 只出现「电脑地址 / 登录账号 / 电脑登录密码 / 看 dsh 网页 / 打开终端」，不暴露 SSH、端口、令牌等概念。

---

## 快速开始

1. **电脑上启动 dsh web**：`dsh --profile web`（默认监听 `http://127.0.0.1:3080`）
2. **手机上安装**：从 [Releases](../../releases) 下载 `dsh-handheld-<版本>.apk`（CI 构建、release 签名；CI 另有 debug artifact 供真机取 WebView 证据）
3. **连接**：打开 App 填三项（只需一次）后点主按钮，隧道由 App 自动建立

| 字段 | 填什么 |
|---|---|
| 电脑地址 | 电脑的局域网 IP（如 `192.168.1.100`）或 Tailscale IP |
| 登录账号 / 密码 | 你在这台电脑上的 SSH 账号密码（隧道用） |
| 端口 | 一般不用改：SSH `22`，dsh `3080` |

跨网络：用 [Tailscale](https://tailscale.com/) 等组网后填其 IP，隧道照常生效。

---

## 凭据与安全

- SSH 密码与 dsh token 经 **AndroidKeyStore** AES-GCM 加密后落盘（`SecurePrefs.kt`，`enc.v1.` 前缀）；密钥不可导出，把应用私有目录整个复制到另一台设备也解不开。Keystore 失效（设备策略变更等）时按「未配置」处理并让你重新输入，不是崩溃。不用已废弃的 `androidx.security:security-crypto`，直接按官方指引用平台 Keystore。
- **发布包不可调试**：CI 有一道硬校验——APK 里出现 `application-debuggable` 就直接构建失败（`debuggable=true` 会让 `run-as` 无需 root 读到应用私有目录，并使 WebView 远程调试对整个局域网开放）。
- 签名材料只经 CI 注入、**不入库**（公开仓库里的签名密钥 = 任何人都能签出可覆盖安装的升级包）。
- `usesCleartextTraffic="true"`：隧道里的流量已由 SSH 加密，明文只存在于设备本地回环。
- **仅 arm64-v8a**：`dbclient` 目前只为 arm64 构建，32 位与 x86 设备不适用。
- 不在威胁模型内：已 root 且能在应用进程内执行代码的攻击者——此时应用自身必须能解密，任何应用侧加密都无济于事。

---

## 版本与依赖

工具链与依赖上限的一览表（Gradle / AGP / Kotlin / JDK / compileSdk / minSdk、两个库的升级上限）
在 [`docs/releasing.md`](docs/releasing.md)；结论是 **compileSdk 36 + `core-ktx` 1.18.0 + `webkit` 1.17.0**
为当前上限，升级受两条独立约束（AAR 元数据的 `minCompileSdk` ≤ 36、传递依赖的 `kotlin-stdlib`
metadata 版本 ≤ 编译器可读上限）。

待办（各自单列一步，不宜混在依赖升级里）：

- **`targetSdk` 35 已落地（2026-09-22），升 36 之前要先修终端**：主界面那一半真机回归通过
  （沉浸式、顶部压缩、左右抽屉/侧栏安全区都量过）；**终端新增一条缺陷** —— 软键盘弹出时
  附加键栏整条被键盘盖住（targetSdk 34 上它浮在键盘上方），另有一条既有缺陷（PTY 行列数
  不跟软键盘：44 行 vs 可见 28.4 行）。两条都得先给 `TuiActivity` 补 IME inset 处理。
  清单、A/B 数据与其余发现见 [`docs/known-issues.md`](docs/known-issues.md) §九。
  **根因已用交叉验证定位**：同一个 APK 装在 Android 13（SM-G7810 / API 33）上两条都不出现，
  终端视图随键盘精确收缩 981px、PTY 39 → 21 行跟随 —— 即 Android 15+ 起系统不再替应用
  resize，必须由终端自己 `setDecorFitsSystemWindows(false)` + 消费 `Type.ime()` inset。
- **`compileSdk` 36 → 37**：解锁 `core-ktx` 1.19.0，连带 build-tools 37 与 `platforms;android-37.0`。
- **启用 R8**：`release` 目前 `isMinifyEnabled = false`，首次启用需真机验证（可能裁掉运行期才引用的类）。

---

## 发布与签名

发布产物由 CI 用 **release 签名**构建；secrets 缺失时（fork / PR）回退 debug 签名并告警，该产物
**不可对外分发**。

| GitHub Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | PKCS12 keystore 的 base64 |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

> ⚠️ **务必备份 keystore 与口令。** 丢失后无法再发布可覆盖安装的升级包，只能让所有用户卸载重装。

安装说明、许可与依赖上限的公共部分在 [`docs/releasing.md`](docs/releasing.md)，各版本差异在
`docs/release-notes-0.1.*.md`，版本号在 `android/app/build.gradle.kts`。

---

## 移动端界面适配

dsh 官方前端是桌面布局，窄屏下侧栏常驻挤占内容。本项目在 **App 侧**注入一个**自研的**客户端插件
（`android/app/src/main/assets/plugins/dsh-handheld-mobile.js`）：`addDocumentStartJavaScript` 钩住
`__DSH_BOOT__` 启动图，`shouldInterceptRequest` 从 APK assets 供 bundle，**服务端零改动**。

它建立在 dsh 的 DOM 之上（`data-*` 属性与哈希类名后缀，清单以 `scripts/mobile-hooks-contract.json`
为准）。这些**没有版本契约**：dsh 改个属性名或换层结构，适配就**静默失效**（抽屉不弹、布局错位），
只能在手机上发现 —— 所以 CI 里有一只契约金丝雀：

```sh
node scripts/check-mobile-hooks.mjs --contract   # CI 门禁：插件实读的钩子 ⇄ 契约文件
node scripts/check-mobile-hooks.mjs              # 升级 dsh 后在本机跑（对照已安装的 dsh 产物）
```

设计取舍与能力边界见 [`docs/mobile-adaptation.md`](docs/mobile-adaptation.md)，验证方式（本地渲染回环、
真机取证、A/B 断言）见 [`docs/mobile-ui-verification.md`](docs/mobile-ui-verification.md)。

---

## 出问题时怎么拿证据

- **手机上**：连接屏右上角 **「诊断」** —— 上次进程退出原因（低内存被杀 / 崩溃 / 被用户停止）、本次
  运行日志、磁盘上含上次运行的日志尾部，可一键复制。不需要电脑。普通应用**读不到 logcat**，所以必须自己记。
- **连着电脑时**：先压掉三星每帧一条的刷屏，否则 5 MiB 的 logcat 缓冲撑不到 5 分钟、我们的行会被冲光：

  ```sh
  adb shell setprop log.tag.View W     # 实测 662 条/10s → 1 条/10s；重启自动失效
  adb logcat | grep -E "DshApp|DshHandheld|SshTunnel|TuiActivity|DiagLog"
  ```

已知问题、行为记录与历次真机验证见 [`docs/known-issues.md`](docs/known-issues.md)。

---

## 项目结构

```
android/
  app/                         # App 本体：连接屏 + WebView 壳 + 隧道编排 + 终端模式
    src/main/java/com/dshhandheld/{app,protocol}/   # MainActivity / DshApp / SshTunnel …
    src/main/java/com/termux/shared/terminal/io/    # vendored 的 Termux 额外键栏（见 License）
    src/main/assets/plugins/                        # 注入的移动端适配插件（自研）
    src/main/jniLibs/arm64-v8a/                     # dbclient / dropbearkey（CI 阶段构建后放入）
  terminal-conformance/        # 纯 JVM 终端行为回归测试台（不进 APK）
scripts/                       # dropbear 交叉编译、契约金丝雀、渲染回环、API 推送
docs/                          # 已知问题、适配与验证、发布说明
.github/workflows/ci.yml       # dbclient → 契约与一致性门禁 → 构建并校验 release APK
```

---

## License

**GPL-3.0**（[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)）。衍生作品需同样以
GPL-3.0 开源 —— **这是 `java/com/termux/shared/terminal/io/` 下那 7 个 vendored 文件决定的**：

| 组件 | 许可证 | 位置 |
|---|---|---|
| Termux `terminal-view` / `terminal-emulator` | Apache-2.0（上游 `LICENSE.md` 的例外条款） | Gradle 依赖 |
| Termux `termux-shared` 的 `terminal/io/**`（vendored，7 个文件） | **GPLv3-only** | `java/com/termux/shared/terminal/io/` |
| Dropbear `dbclient` / `dropbearkey` | MIT 风格（随附文件） | `jniLibs/.../LICENSE-dropbear.txt` |

想改用宽松许可证，唯一合法路径是先用自研实现替掉那 7 个文件；完整的许可证核对（含「为什么 pin 的
`v0.118.1` 与 `master` 结论相反」）见 [`docs/terminal-rewrite-plan.md`](docs/terminal-rewrite-plan.md) 附录 B。

> ⚠️ **待补的合规缺口**：Apache-2.0 要求随附许可证文本，而当前 APK 里没有 —— CI 把 dropbear 的
> `LICENSE.txt` 拷进 `jniLibs/`，AGP 只打包那里的 `.so`，那个 `.txt` 进不了 APK。