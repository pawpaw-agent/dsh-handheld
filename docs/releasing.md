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

**本仓库自身：GPL-3.0**（见 `LICENSE`）。

选它的**历史理由**是当时 vendored 了 7 个 GPLv3-only 的 Termux `terminal/io/*` 文件
（`termux-shared` 主许可；其 MIT 例外逐文件列举，不含 `terminal/io/*`）。**那些文件已于
2026-09-25 随原生终端一起删除** —— 也就是说，现在**没有**「必须 GPL」的技术约束了。
换不换许可由版权人决定，且换许可不追溯已发布的旧版本。历史核对（含那 7 个文件
「哪几个被本地改过、为什么当时必须 vendoring」）见
[`docs/archive/terminal-rewrite-plan.md`](archive/terminal-rewrite-plan.md) 附录 B 与
[`docs/consolidation-audit.md`](consolidation-audit.md) §4.1。

**APK 里实际打包的第三方组件只剩两类**：

| 组件 | 许可 | 来源 |
|---|---|---|
| `androidx.core:core-ktx` | Apache-2.0 | Gradle 依赖 |
| Dropbear `dbclient` / `dropbearkey` | MIT 风格（多组件混合，见其 LICENSE） | `scripts/build-dropbear.sh` 交叉编译 |

dsh 网页本身**不在 APK 里** —— 它跑在电脑上，由 WebView 加载。
**2026-09-25 起也不再注入手机端适配层**（原 `assets/plugins/dsh-handheld-mobile.js` 已删除）；
更早 vendored 的第三方 dsh-web-mobile 及其许可证已于 2026-09-13 删除。

### 随附许可证文本（2026-10-07 已解决）

Dropbear 的 `LICENSE.txt` 现在作为**资源文件**打进 APK：
`android/app/src/main/assets/licenses/dropbear-LICENSE.txt`（仓库内有一份基线副本，
CI 构建时用 dropbear 产物里的那份覆盖，保证与二进制同源）。

CI 有一条断言：**APK 内必须能列出该条目**，否则构建失败。这条断言防的是「打包方式一改就
静默丢失」—— 此前它被拷进 `jniLibs/`，而 AGP 只打包那里的 `.so`，`.txt` 根本进不去。

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
