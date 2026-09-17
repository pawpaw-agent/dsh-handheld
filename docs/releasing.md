# 发布说明的公共部分

本文件是各版本发布说明（`docs/release-notes-0.1.*.md`）共用内容的**唯一出处**。
发布说明只写本版差异，安装 / 许可 / 依赖上限一律链到此处，避免三份逐字重复而各自漂移。

## 安装

1. 从对应 Release 的 Assets 下载 APK（文件名形如 `dsh-handheld-<版本>.apk`）。
2. 在电脑上启动 dsh web：

   ```sh
   dsh --profile web
   # 默认监听 http://127.0.0.1:3080
   ```

3. 打开 App，填写**电脑地址**（局域网 IP 或 Tailscale IP）、**登录账号**、
   **电脑登录密码**（隧道用）即可。

**签名兼容性**：0.1.2 及更早用 debug 签名，0.1.3 起改用独立 release 签名。签名不同的包
Android 不允许覆盖安装，必须先卸载旧版（已保存的连接配置会一并清除）；0.1.3 与 0.1.4
签名相同，可以直接覆盖安装。

## 许可

GPL-3.0。**原因是 `java/com/termux/shared/terminal/io/` 下那 7 个 vendored 文件为
GPLv3-only**（Termux `v0.118.1` 的 `termux-shared` 主许可；其 MIT 例外逐文件列举，不含
`terminal/io/*`）。Gradle 依赖 `terminal-view` / `terminal-emulator` 本身是 Apache-2.0。
完整核对见 `docs/terminal-rewrite-plan.md` 附录 B；7 个文件「哪几个被本地改过、
为什么必须 vendoring」见 `docs/consolidation-audit.md` §4.1。

另打包 Dropbear `dbclient` / `dropbearkey`（MIT 风格）。手机端适配层
（`assets/plugins/dsh-handheld-mobile.js`）是本仓库自研，无第三方代码 —— 此前 vendored 的
第三方 dsh-web-mobile 及其许可证已于 2026-09-13 一并删除。

> **发布前待确认**：Apache-2.0 要求随附许可证文本，当前 APK 内没有 —— CI 把 dropbear 的
> `LICENSE.txt` 拷进 `jniLibs/`，而 AGP 只打包那里的 `.so`，那个 `.txt` 进不了 APK。

> 各版本附带组件与许可结论一致，版本之间没有差异。

## 依赖升级上限（结论）

工具链：Gradle **9.7.1** + AGP **9.4.0** + AGP **内置 Kotlin**（KGP 2.2.10）+ JDK **17**，
`compileSdk` **36** / `targetSdk` **34**（未动）/ `minSdk` 26。`org.jetbrains.kotlin.android`
插件已移除：AGP 9 起 `android.builtInKotlin` 默认 true，再应用它会直接构建失败。同理
`android.kotlinOptions{}` 也没了（内置 Kotlin 的 `jvmTarget` 默认取 `compileOptions.targetCompatibility`，
写与不写等价）。要换比 AGP 自带的 2.2.10 更高的 KGP，只能走顶级 build 文件的
`buildscript { classpath(...) }` —— **不能**再用 `plugins{}` 块（AGP 9 起 KGP 是 AGP 的运行时依赖，
在 `plugins{}` 里声明它是非法组合）。

依赖升级上限受**两个独立约束**，必须同时满足：① AAR 元数据的 `minCompileSdk` ≤ 当前
`compileSdk`（36）；② 传递依赖的 `kotlin-stdlib` metadata 版本 ≤ Kotlin 编译器可读上限。
第②条曾把项目锁死 —— Kotlin 1.9.22 最多读到 metadata 2.0.0，而 `webkit` 从 1.16.0 起引入
`kotlin-stdlib:2.1.20`（metadata 2.1.0），一升就编译失败（`Module was compiled with an
incompatible version of Kotlin`）。改用内置 Kotlin（KGP 2.2.10）后**该约束已解除**。
因此当前可用上限是 **core-ktx 1.18.0 + webkit 1.17.0**；再往上走 core-ktx 1.19.0 需要
`compileSdk` 37（并要求 AGP ≥ 9.1.0），是单独一步。

> **`targetSdk` 为什么不跟着 `compileSdk` 一起升**：`compileSdk` 只决定能调用哪些 API，
> `targetSdk` 决定系统按哪一版的行为对待 App，后者是运行时行为变更。34→35 恰好最重
> （Android 15 起强制 edge-to-edge），而本项目主界面是一整个 WebView 加一层终端，
> 最吃 insets，需真机回归后再动。注意 AGP 9 起不写 `targetSdk` 会自动跟随 `compileSdk`，
> 故必须显式写死。

> 同一约束在 `android/app/build.gradle.kts` 的 `dependencies` 注释里有展开，README 只留结论
> 并指回本节；结论以本节为准。
