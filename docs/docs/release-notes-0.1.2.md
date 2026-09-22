**首个公开发布。** 把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）装进口袋的 Android 客户端 —— 完整 dsh Web 界面 + 内置 SSH 隧道 + 终端模式，**服务端零改动**。

> ⚠️ **本项目原名 `dsh-mobile`，现更名 `dsh-handheld`。** 因 `applicationId` 由 `com.dshmobile.app` 改为 `com.dshhandheld.app`，这是一次**全新安装**：已装旧版需先卸载，且已保存的连接配置会丢失。

## 功能

- **看 dsh 网页** — 全屏 WebView 加载 dsh 官方前端，功能与桌面端一致（Markdown、代码高亮、会话树、设置页、模型管理……）
- **内置 SSH 隧道** — 打包 dropbear `dbclient`（arm64）做本地端口转发，服务端视角为**本机回环**，因此**解锁设置页 / 凭据 / 模型探测等配置平面**（dsh 官方把这些接口硬性限制为仅 127.0.0.1 可访问，返回 403）。无需任何服务端插件、无需 `--host 0.0.0.0`
- **打开终端** — 经 SSH PTY 打开远程 shell，Termux 原生渲染（真 IME 交互，非 WebView），底部常驻键排（ESC/TAB/CTRL/方向键/Enter）
- **token 全自动** — SSH 模式下自动从服务端提取 dsh 的启动 token，服务重启后无需手动更新
- **仅 arm64** — `dbclient` 只为 `arm64-v8a` 构建

## 本版变更

- 全量更名：显示名、包名、`applicationId`、主题、存储键
- **移除**原生协议客户端方案与后台通知（App 现为纯 WebView）；删除随之产生的死代码 850 行与 `okhttp3` 依赖，APK 更小
- README 重写：删除已移除功能的描述，改以「内置 SSH 隧道」为主线
- 修复 `push-via-api.py` 硬编码提交信息、丢弃 `--message` 的缺陷；新增整树镜像推送脚本

## 安装

下载 `dsh-handheld-0.1.2.apk`，按 [标准安装步骤](releasing.md) 操作。本版是**全新安装**
（`applicationId` 由 `com.dshmobile.app` 改为 `com.dshhandheld.app`），装过旧版需先卸载。

## 构建与验证状态

本构建由 GitHub Actions（[CI](https://github.com/pawpaw-agent/dsh-handheld/actions)）编译产出，并通过产物校验：包名 `com.dshhandheld.app`、组件类、应用名均已在 DEX 与 Manifest 中确认，旧名零残留。

**真机功能验证尚未完成**——SSH 隧道重建、断线恢复、BACK 语义等行为尚未在实机跑过。已知问题与待验证项见 [`docs/known-issues.md`](https://github.com/pawpaw-agent/dsh-handheld/blob/main/docs/known-issues.md)。

## 许可

见 [标准许可说明](releasing.md)。

---

**SHA-256**（`dsh-handheld-0.1.2.apk`）：`25da1d483f38f74f3da7cbf40ddce09a1361ed0e488050a4a23f72f63c0d1fcc`
