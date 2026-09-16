# dsh-handheld

**把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）装进口袋的 Android 客户端。**

完整 dsh Web 界面 + 内置 SSH 隧道 + 终端模式。服务端**零改动**——不需要装任何 dsh 插件，不需要 `--host 0.0.0.0`。

```
┌──────────────────────────────┐
│        dsh-handheld          │
│                              │
│  ① 看网页（全屏 WebView）    │
│  ② 打开终端（远程 shell）    │
│                              │
│  内置 SSH 隧道（dbclient）   │
└───────────────┬──────────────┘
                │  隧道后访问 http://127.0.0.1:3080
                │  （服务端视角 = 本机回环）
                ▼
┌──────────────────────────────┐
│          dsh web             │
│    （dsh --profile web）     │
│    运行在你的笔记本 / VPS    │
└───────────────┬──────────────┘
                ▼
┌──────────────────────────────┐
│    DeepSeek Harness Agent    │
│   你自己的 key、自己的配置   │
└──────────────────────────────┘
```

---

## 为什么用 SSH 隧道

DSH 出于安全设计，把**配置平面**（设置页、凭据管理、模型探测、目录选择等）**硬性限制为仅本机回环可访问**。从局域网 IP 访问这些接口会返回 `HTTP 403`——这是官方的安全边界（`PRIVILEGED_METHODS`），本项目不绕过它。

SSH 本地端口转发让服务端**仍然认为请求来自本机**，因此是**唯一既能远程使用、又不破坏官方安全模型的完整方案**，且自带 SSH 认证。本项目把这个过程内置了：填一次账号密码，App 自己维持隧道。

| | 局域网直连 | **内置 SSH 隧道** |
|---|---|---|
| 对话 / 会话 / 工作区 | ✅ | ✅ |
| 设置页 / 凭据 / 模型探测 | ❌ 403 | ✅ |
| 额外服务端插件 | 需要 | **不需要** |
| 认证 | dsh token | **SSH + dsh token（双重）** |
| 跨网络 | 需 Tailscale 等 | ✅ 只要 SSH 可达 |

---

## 功能

- **看 dsh 网页** — 全屏 WebView 加载 dsh 官方 Web 前端，功能与桌面端一致（Markdown、代码高亮、会话树、设置页、模型管理……）。内置 `crypto.randomUUID` 文档启动注入（防局域网明文 HTTP 下白屏）、跨 Activity 重建的 WebView 保活。
- **内置 SSH 隧道** — 打包 dropbear `dbclient`（arm64）做进程式本地端口转发，固定使用 `3080`（占用时回退 `13080`）。支持**密码**与**私钥**（含口令，可用系统文件选择器导入）两种认证；断线自动重连，App 重启后自动重建隧道。
- **打开终端** — 经 SSH PTY 打开远程 shell，用 Termux 的 [terminal-view](https://github.com/termux/termux-app) 原生渲染（真 IME / 软键盘交互，非 WebView），底部常驻键排（ESC / TAB / CTRL / 方向键 / Enter）。进入后就是普通远程 shell，**自由输入 `dsh-tui` 等命令**，不做任何自动启动。
- **token 全自动** — dsh 0.1.2+ 启用了浏览器 token 认证。SSH 模式下 App 会在服务端自动提取最新 token 并保存，服务重启后无需手动更新；失败时回退到连接屏手动填写。
- **任务完成提醒** — dsh 生成结束时，App 自己发一条通知（点开回到 App）。**默认关闭**，在连接屏「任务完成时提醒我」打开（会申请通知权限）；App 在前台时不打扰。此前这件事靠服务端推送，现在由页面里的适配插件报告「这一轮结束了」，不再依赖服务端配置。
- **连接屏刻意去术语** — 只出现「电脑地址 / 登录账号 / 电脑登录密码 / 看 dsh 网页 / 打开终端」，不暴露 SSH、端口、令牌等概念。

---

## 快速开始

### 1. 在电脑上启动 dsh web

```sh
dsh --profile web
# 默认监听 http://127.0.0.1:3080
```

### 2. 安装 App

从 [Releases](../../releases) 下载 APK，或自行构建：

```sh
cd android && ./gradlew assembleDebug
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

> CI（GitHub Actions）也会构建 debug APK 作为 artifact。

### 3. 连接

打开 App，填三项（只需一次）后点主按钮，SSH 隧道由 App 自动建立：

| 字段 | 填什么 |
|---|---|
| 电脑地址 | 电脑的局域网 IP（如 `192.168.1.100`）或 Tailscale IP |
| 登录账号 / 密码 | 你在这台电脑上的 SSH 账号密码（隧道用） |
| 端口 | 一般不用改：SSH 用 `22`，dsh 用 `3080` |

连接屏按**使用频率**排：上面是状态（未连接 / 连接中… / 已连上电脑，附目标地址），
下面贴底一个主按钮。配置只填一次 —— 之后这一屏打开就是**收起**状态，点一下就回网页；
要改配置就展开「连接设置」那张卡。想开终端把分段控件切到「**打开终端**」。

---

## 连接方式

| 场景 | 说明 |
|---|---|
| **同一 WiFi（推荐）** | 填电脑局域网 IP，走内置 SSH 隧道 |
| **跨网络** | 用 [Tailscale](https://tailscale.com/) 等组网后填其 IP；隧道仍然生效 |
| **纯局域网直连（不建隧道）** | 功能受限（配置平面 403），需要服务端插件把 dsh 绑到 `0.0.0.0` |

---

## 项目结构

```
dsh-handheld/
├── android/
│   ├── app/src/main/
│   │   ├── java/com/dshhandheld/
│   │   │   ├── app/
│   │   │   │   ├── MainActivity.kt           # 连接屏 + WebView 壳 + 隧道编排
│   │   │   │   ├── TuiActivity.kt            # SSH 终端模式（PTY + Termux 渲染）
│   │   │   │   ├── DshTerminalExtraKeys.kt   # 终端底部常驻键排
│   │   │   │   ├── SecurePrefs.kt            # 凭据静态加密（AndroidKeyStore AES-GCM）
│   │   │   │   └── DshApp.kt                 # Application：持有保活 WebView 与唯一隧道
│   │   │   └── protocol/
│   │   │       └── SshTunnel.kt              # dbclient 进程 + 端口选择 + 看门狗
│   │   ├── java/com/termux/shared/terminal/io/   # vendored Termux 额外键栏（7 文件，见下）
│   │   ├── assets/plugins/                   # 注入的移动端适配插件（MIT，见下）
│   │   ├── jniLibs/arm64-v8a/                # dbclient（CI 阶段构建后放入）
│   │   └── AndroidManifest.xml
│   ├── terminal-conformance/                 # 纯 JVM 终端行为回归测试台（不进 APK）
│   └── gradlew + gradle/ + *.gradle.kts      # 构建入口与 Gradle wrapper
├── scripts/
│   ├── build-dropbear.sh                     # 交叉编译 dropbear dbclient
│   ├── localoptions.h                        # dropbear 裁剪配置
│   ├── push-via-api.py                       # 增量推送（git 传输不可用时）
│   ├── mirror-via-api.py                     # 整树镜像推送（重命名/删除时更稳）
│   ├── check-mobile-hooks.mjs                # 移动端适配契约金丝雀（CI 门禁）
│   ├── composer-stats-lab.mjs                # 统计行「用满宽度」的 A/B 断言（本机 + chromium）
│   └── ui-verify.mjs                         # 真实页面渲染验证（手机视口，需联网浏览器）
├── docs/
│   ├── known-issues.md                       # 已知问题与行为记录
│   ├── terminal-rewrite-plan.md              # 终端自研计划（已中止，只留结论）
│   ├── releasing.md                          # 发布说明的公共部分（安装 / 许可 / 依赖上限）
│   ├── release-notes-0.1.{2,3,4}.md          # 各版本发布说明
│   ├── mobile-adaptation.md                  # 自研手机端适配层：依赖、取舍、边界
│   ├── dsh-plugins-404-fix.md
│   └── archive/dsh-protocol.md               # 【存档】DSH 线上协议逆向规格（已无实现）
├── .github/workflows/ci.yml                  # dbclient → 终端一致性门禁 → 构建并校验 release APK
└── LICENSE
```

---

## 凭据存储

SSH 密码与私钥口令**不以明文落盘**：

```
SharedPreferences "dsh-handheld"
  ssh_json      → enc.v1.<base64(iv ‖ AES-256-GCM 密文)>
  server_token  → 同上
  url / ssh_enabled → 明文（非敏感）
```

密钥由 **AndroidKeyStore** 持有且不可导出，因此即便应用私有目录被完整复制到另一台设备
也无法解密。实现见 `SecurePrefs.kt`。

**不使用** `androidx.security:security-crypto`——该库的全部 API 已被官方废弃
（1.1.0-beta01 起："Deprecated all APIs in favour of existing platform APIs and direct use
of Android Keystore"），故直接按官方指引使用平台 Keystore，不引入额外依赖。

**兼容性**：读取时若发现历史版本写入的明文，会原样返回并**顺手迁移**为密文，用户无感。
若 Keystore 密钥失效（设备策略变更等），解密失败按“未配置”处理并让用户重新输入，
而不是让 App 崩溃。

**不在威胁模型内**：已 root 且能在应用进程内执行代码的攻击者——此时应用自身必须能解密，
任何应用侧加密都无济于事。

---

## 版本与升级

### 工具链

| 组件 | 版本 | 出处 / 约束 |
|---|---|---|
| Gradle | **9.7.1** | `android/gradle/wrapper/gradle-wrapper.properties`，wrapper 是**唯一出处** |
| Android Gradle 插件 | **9.4.0** | 最高支持 API 37；最低要 Gradle 9.6.0 / JDK 17 / build-tools 36.0.0 |
| Kotlin | **内置（KGP 2.2.10）** | AGP 9 起内置编译，**不再单独声明插件** |
| JDK | 17 | AGP 9 与 Gradle 9.7 的共同下限（Gradle 9.7 支持 17–26） |
| compileSdk | **36** | |
| targetSdk | **34**（未动） | 见「待办的现代化项」 |
| minSdk | 26 | |

**`org.jetbrains.kotlin.android` 插件已移除。** AGP 9 的 `android.builtInKotlin` 默认为
`true`，此时再应用它就直接构建失败：

```
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

同理 `android.kotlinOptions{}` 也没了 —— 内置 Kotlin 的 `jvmTarget` 默认取
`compileOptions.targetCompatibility`（本项目 17），写与不写等价，不写反而少一处漂移。
要换比 AGP 自带的 2.2.10 更高的 KGP，只能走顶级 build 文件的
`buildscript { classpath(...) }`，**不能**再用 `plugins{}` 块（AGP 9 起 KGP 是 AGP 的
运行时依赖，`plugins{}` 里声明它是非法组合）。

### 依赖上限

升级上限受**两个独立约束**，必须同时满足：

1. **AAR 元数据的 `minCompileSdk`** ≤ 当前 `compileSdk`（36）；
2. **传递依赖的 `kotlin-stdlib` metadata 版本** ≤ Kotlin 编译器可读上限。

第 2 条曾把项目锁死：Kotlin 1.9.22 最多读到 metadata 2.0.0，而 **`webkit` 从 1.16.0 起
引入 `kotlin-stdlib:2.1.20`**（metadata 2.1.0），一升就编译失败（`Module was compiled with
an incompatible version of Kotlin`）。改用内置 Kotlin（KGP 2.2.10，可读 metadata 2.1.0）
后**这条约束已经消失** —— 下面两个库都只需要 `kotlin-stdlib:2.1.20`。

| 库 | 当前 | 升级上限 | 卡在哪 |
|---|---|---|---|
| `androidx.webkit:webkit` | **1.17.0** | 1.17.0 | 1.18.0 尚无正式版（当前只有 alpha01） |
| `androidx.core:core-ktx` | **1.18.0** | 1.18.0 | 1.19.0 要 `minCompileSdk` **37**（且要求 AGP ≥ 9.1.0） |
| `termux terminal-view` | 0.118.1 | — | 见下方 vendoring 说明 |

> **这两个数字怎么来的**（比查文档可靠）：解包 AAR，读
> `META-INF/com/android/build/gradle/aar-metadata.properties` 里的 `minCompileSdk` 与
> `minAndroidGradlePluginVersion`；`kotlin-stdlib` 版本读同名 `.pom`。
> 例：`core-1.18.0.aar` → `minCompileSdk=36, minAndroidGradlePluginVersion=8.9.1`；
> `core-1.19.0.aar` → `minCompileSdk=37, minAndroidGradlePluginVersion=9.1.0`；
> `webkit-1.17.0.aar` → `minCompileSdk=33`。

### 待办的现代化项

- **`targetSdk` 34 → 35/36**：`compileSdk` 只决定「能调用哪些 API」，`targetSdk` 决定
  「系统按哪一版的行为对待这个 App」—— 后者是**运行时行为变更**。34→35 恰好是最重的一档：
  Android 15 起强制 edge-to-edge（系统栏区域不再自动让位），35→36 还有一批前台服务与
  权限收紧。本项目主界面是一整个 WebView 加一层终端，正是最吃 insets 的形状，
  **需真机回归后再动**，不宜混在依赖升级里。
  另注：AGP 9 起 `android.sdk.defaultTargetSdkToCompileSdkIfUnset` 默认为 `true`，
  **不写 `targetSdk` 就会自动跟随 `compileSdk`** —— 所以这里必须显式写死。
- **`compileSdk` 36 → 37**：解锁 `core-ktx` 1.19.0，连带 build-tools 37 与
  `platforms;android-37.0`（API 37 起平台包带小版本号，仓库里是 `37.0` / `37.1` / `37.2`，
  没有裸的 `android-37`）。单列一步，便于定位问题。
- **启用 R8**：`release` 变体目前 `isMinifyEnabled = false`。首次启用压缩/混淆需真机验证
  （R8 可能裁掉运行期才引用的类），不宜与签名变更同时进行。

### Termux 组件的 vendoring

`android/app/src/main/java/com/termux/shared/terminal/io/` 下的 **7 个文件**是从 Termux
`v0.118.1` 复制的副本（`extrakeys/` 6 个 + `terminal/io/TerminalExtraKeys.java` 1 个）：

- **5 个逐字未改**（仅加归属头）；
- **2 个有本地改动**：`ExtraKeyButton`（新增 `rowSpan` 配置项）与 `ExtraKeysView`
  （字号与纵向跨行）。两文件的头注释已写明区别。

**为什么必须 vendoring**：这些类**不在** JitPack 依赖 `terminal-view` 里。实测
`terminal-view-0.118.1.aar` 的 `classes.jar` 只有 **19 个类**、全部位于 `com/termux/view/**`
（含 `textselection`），其中**没有**任何 `com.termux.shared.terminal.io.*`——上游从未把这些类
发布进 `terminal-view` 构件。所以这不是「源码覆盖 jar 里的同名类」，而是「只此一份」。

> 本节此前写「6 个文件」「与 jar 同包同名、源码优先」，两条都与构件实测不符；现按解包
> `classes.jar` 的结果更正（`docs/consolidation-audit.md` §4.1 的核对与实测一致）。
> `extrakeys/` 下 6 个文件的归属头里也重复了「同包同名」这条错误说法，改 `.java` 时一并修正。

**升级 `terminal-view` 时的真实风险**：这 7 个文件**冻结在 v0.118.1**，上游对它们的修复不会
随依赖升级到达。反过来，「与 jar 里的版本手工合并」是空谈——没有 jar 版本可合并。

#### vendored 代码的许可证 —— 以及为什么项目是 GPL-3.0

先说结论：**项目以 GPL-3.0 发布，是这 7 个文件决定的。**

| 组件 | 许可 | 依据 |
|---|---|---|
| `terminal-view` / `terminal-emulator`（Gradle 依赖） | **Apache-2.0** | termux-app 根 `LICENSE.md` 把它们列为 GPLv3 的例外 |
| 上面这 7 个 vendored 文件 | **GPLv3-only** | `termux-shared/LICENSE.md` @ **`v0.118.1`** |

第二条容易看错，因为它取决于**看哪个 tag**：`master` 上 `termux-shared` 是 MIT、GPLv3-only
收窄到 `com/termux/shared/termux/*`；但本项目 pin 的 `v0.118.1`（以及 0.118.2 / 0.118.3）
**主许可就是 GPLv3 only**，MIT 例外是**逐文件列举**的，**不含** `terminal/io/*`。
（0.119 起反过来，但 extrakeys 恰好被移进 `com/termux/shared/termux/extrakeys/` —— 仍是
GPLv3-only 那个目录。所以**升级 Termux 也解不开这个约束**。）

⇒ 只要这 7 个文件还在源码里，整个项目就必须是 GPL-3.0。想改用 MIT/Apache-2.0，唯一合法
路径是**先用自研实现替掉它们**（额外键栏约 250–350 行 Kotlin），此后源码不含 GPL 组件。
**是否值得为许可证自由做这次重写，尚未决定。**

> 本节此前写「这 7 个文件是 MIT，头注释应改成 MIT，与项目许可证无关」——**那是错的**，
> 依据取自 `master` 而非项目 pin 的 `v0.118.1`。已订正。

**待补的合规缺口**（与选哪个许可证无关）：Apache-2.0 组件要求随附许可证文本，而当前 APK 里
没有 —— CI 把 dropbear 的 `LICENSE.txt` 拷进 `jniLibs/`，AGP 只打包那里的 `.so`，那个
`.txt` 进不了 APK。核对过程见
[`docs/terminal-rewrite-plan.md`](docs/terminal-rewrite-plan.md) 附录 B。

---

## 发布与签名

发布产物由 CI 用 **release 签名**构建，签名材料经环境变量注入，**不入库**（公开仓库里的
签名密钥等于任何人都能签出可覆盖安装的“升级包”）：

| GitHub Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | PKCS12 keystore 的 base64 |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

secrets 缺失时（fork / PR）回退 debug 签名并告警，该产物**不可对外分发**。CI 另有一道
硬校验：release APK 若含 `application-debuggable` 则**构建直接失败**——`debuggable=true`
会让 `run-as` 无需 root 即可读取应用私有目录，并使 WebView 远程调试对整个局域网开放。

> ⚠️ **务必备份 keystore 与口令。** 丢失后无法再发布可覆盖安装的升级包，只能让所有用户
> 卸载重装。

---

## 移动端界面适配

dsh 官方 Web 前端是桌面布局，窄屏下侧栏会常驻挤占内容。本项目在 **App 侧**注入一个
**自研的**客户端插件（`assets/plugins/dsh-handheld-mobile.js`）来适配——
`addDocumentStartJavaScript` 钩住 `__DSH_BOOT__` 启动图，`shouldInterceptRequest` 从 APK
assets 返回插件 bundle，**服务端不需要装任何插件**。

**适配建立在 dsh 的 DOM 之上**：一组 `data-*` 属性（`data-phase`、`data-sidebar-collapsed`、
`data-composer-stats` …）加若干层结构与哈希类名后缀（外壳网格与侧栏列、会话头、头部弹层、
输入框上方那行统计）。这些**没有版本契约** —— dsh 独立演进，适配层按内容换 rev
（当前 `dsh-handheld-mobile-1.0.17`，见 `MainActivity.MOBILE_PLUGIN_REV`）。dsh 改个属性名
或那几层结构，适配就**静默失效**（抽屉不弹、布局错位），只能在手机上发现。

因此有**契约金丝雀**在 CI 里守着：`node scripts/check-mobile-hooks.mjs --contract`
断言适配层实际读取的钩子与提交在仓库里的 `scripts/mobile-hooks-contract.json` 完全一致
（扫描范围是**整份 bundle**，规则写在 CSS 里也算），改了依赖而不更新契约，CI 直接失败。

**升级 dsh 之后**请在本机跑一次完整检查（需要装着 dsh 的环境）：

```sh
node scripts/check-mobile-hooks.mjs
```

它会逐个断言这些钩子在当前 dsh 前端里确实存在。完整说明与「真实页面渲染验证」
（`scripts/ui-verify.mjs`，手机视口 + A/B 对照 + 截图）、以及单条适配规则的 A/B 断言
（`scripts/composer-stats-lab.mjs`，统计行用满宽度那条）见
[`docs/mobile-ui-verification.md`](docs/mobile-ui-verification.md)。

> ℹ️ **手机端适配层是本仓库自研**（`android/app/src/main/assets/plugins/dsh-handheld-mobile.js`，
> 约 36 KB）：一个标准的 dsh 客户端插件，由 App 在 document-start 注入，服务端零改动。
>
> 2026-09-13 之前这一层是 vendored 的第三方 dsh-web-mobile（MIT）外加 5 个手工补丁 ——
> 每次上游发版都要在上游文件**体内**重打一遍，补丁与上游代码混在一起说不清归属。
> 现在它是我们自己的代码：进 git、有版本、能单独 review；上游插件与许可证已删除。
>
> 它依赖的 dsh DOM 钩子（`data-*` 属性与哈希类名后缀，当前 8 + 17 个）由
> `scripts/check-mobile-hooks.mjs` 对着 `scripts/mobile-hooks-contract.json` 守 —— dsh 哪天
> 改了这些，CI 的 `Mobile adaptation contract` 会红，而不是手机上一声不响地坏掉。
> 清单以契约文件为准，README 不抄第二份。
>
> 设计取舍、能力边界（本版**没有**做手势等）与验证方式见
> [`docs/mobile-adaptation.md`](docs/mobile-adaptation.md)；三层验证见
> [`docs/mobile-ui-verification.md`](docs/mobile-ui-verification.md)。
> 2026-09-13 的全 UI 走查结论见 [`docs/known-issues.md`](docs/known-issues.md) §五。

---

## 注意事项

- **明文 HTTP** — `usesCleartextTraffic="true"`。隧道模式下流量本身已由 SSH 加密，明文仅存在于设备本地回环；但如果用局域网直连，请确保在可信内网。
- **安全** — SSH 密码与 dsh token 经 AndroidKeyStore 加密后落盘（见「凭据存储」）；发布包不可调试。dsh 0.1.2+ 默认启用浏览器 token 认证，SSH 隧道再叠加一层 SSH 认证。公开 WiFi 下建议用 Tailscale 而不是直接暴露端口。
- **仅 arm64** — `dbclient` 目前只为 `arm64-v8a` 构建，不适用于 32 位或 x86 设备。
- **签名变更需重装** — 0.1.3 起改用独立发布签名（此前为 debug 签名）。签名不同，Android **不允许覆盖安装**：需先卸载旧版，已保存的连接配置会一并清除。
- **真机验证状态** — 见 `docs/known-issues.md`。

---

## 已知问题与取证

见 [`docs/known-issues.md`](docs/known-issues.md)。

### 出问题时怎么拿到证据

- **在手机上**：连接屏右上角 **「诊断」** —— 显示上次进程退出原因（低内存被杀 / 崩溃 /
  被用户停止）、本次运行的日志、以及磁盘上含上次运行的日志尾部，可一键复制。不需要电脑。
  为什么必须由 App 自己记：普通应用**读不到 logcat**，而 logcat 本身也只是内存环形缓冲
  （详见 `docs/known-issues.md` §四）。
- **连着电脑时**：先压掉三星每帧一条的刷屏，否则 5 MiB 的 logcat 缓冲撑不到 5 分钟，
  我们的行会被冲光（曾因此误判为「没打日志」）：

  ```sh
  adb shell setprop log.tag.View W     # 实测 662 条/10s → 1 条/10s；重启自动失效
  adb logcat | grep -E "DshApp|DshHandheld|SshTunnel|TuiActivity|DiagLog"
  ```

---

## License

**GPL-3.0**（[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)）

本项目以 GPL-3.0 发布，衍生作品需同样以 GPL-3.0 开源。**原因是
`java/com/termux/shared/terminal/io/` 下那 7 个 vendored 文件是 GPLv3-only**（见上文
「Termux 组件的 vendoring」）。想改用宽松许可证，需先用自研实现替掉它们。

项目中还打包了第三方组件，各自的许可证**随附于对应目录**：

| 组件 | 许可证 | 位置 |
|---|---|---|
| Termux `terminal-view` / `terminal-emulator` | Apache-2.0（上游 `LICENSE.md` 的例外条款） | Gradle 依赖 |
| Termux `termux-shared` 的 `terminal/io/**`（vendored） | **GPLv3-only** | `java/com/termux/shared/terminal/io/` |
| Dropbear `dbclient` / `dropbearkey` | MIT 风格（见随附文件） | `jniLibs/.../LICENSE-dropbear.txt` |

> ⚠️ Apache-2.0 要求随附许可证文本，**当前 APK 里没有**（进不了 APK 的那份 `.txt` 见上文
> 「Termux 组件的 vendoring」）。这是待补的合规项。
