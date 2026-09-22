**小修版。** 摘掉了手机端一个「点了必然报错」的菜单项，并把「会话删除」的官方口径写进文档。

## 变更

- **移除「删除会话」菜单项。** 上游移动端适配插件 v2.4.0 新增的这项功能需要**宿主半边**才能工作：点击后它会 `POST /api/mobile-nav.session.delete`，而这条路由要由插件的服务端部分注册。本项目的设计是「纯 App 侧注入，服务端零改动」，宿主半边从不安装，因此该接口恒为 `HTTP 404` —— 点下去只会看到「删除失败：HTTP 404」。现在这一项不再注入，会话行「⋯」菜单恢复为官方三项：重命名 / 分叉会话 / 归档会话。
- 插件缓存键 `rev` 随内容升级为 `dsh-web-mobile-2.4.0-dsh1`（内容变了 rev 必须变，否则 WebView 可能命中旧缓存）。
- 新增 [`docs/vendored-plugin-patches.md`](https://github.com/pawpaw-agent/dsh-handheld/blob/main/docs/vendored-plugin-patches.md)：记录这唯一一处对上游 bundle 的补丁（位置、原因、影响面、重新 vendoring 步骤），以及会话删除的做法。

## 会话到底怎么删（官方口径）

dsh 官方把物理删除定位为**外部动作**，不在接口里提供（持久化后端「已知限制与延期工作」原文：*不删除会话文件——日志在 `root` 下累积，直到外部移除；seam 无删除接口*）。UI 上给的是「归档会话」，**单向且不回收磁盘**。要真删，直接在电脑上删目录即可，索引会跟随外部移除、界面随即干净：

```sh
rm -rf ~/.dsh/sessions/<projectKey>/session-<uuid>/     # 一个目录 = 一个会话
```

前提：该会话没在运行（运行中的会话被宿主持有，收尾时可能把日志目录回写重建）。定位方式、投影缓存与附件说明见补丁文档。

## 版本

| | |
|---|---|
| `package` / `versionCode` / `versionName` | `com.dshhandheld.app` / **31** / **0.1.4** |
| 签名 | 与 0.1.3 相同 → **可直接覆盖安装**（0.1.2 及更早仍需先卸载） |

**真机功能验证仍未完成**（SSH 隧道重建、断线恢复、BACK 语义等尚未在实机跑过）。已知问题见 [`docs/known-issues.md`](https://github.com/pawpaw-agent/dsh-handheld/blob/main/docs/known-issues.md)。

## 安装

下载 `dsh-handheld-0.1.4.apk`，按 [标准安装步骤](releasing.md) 操作。本版签名与 0.1.3 相同，
**可直接覆盖安装**。

## 许可

见 [标准许可说明](releasing.md)。

---

**SHA-256**（`dsh-handheld-0.1.4.apk`，2,087,746 字节）：`430024d3d61fd363c3e4f159632b3fa7ddb3cd3c8568b344470dd60a821fc624`

发布前已在产物上核对：`versionCode=31` / `versionName=0.1.4` / `android:debuggable` 缺失（= false）；
`assets/plugins/dsh-web-mobile-client.js` 的 md5 为 `5037d9d78a6eadf5e397a7fe557985f6`（即打过 P1 补丁的那份，
与本仓库文件逐字节一致）；v2 签名证书与 0.1.3 完全相同（序列号 `0xdda8efbf…`，
指纹 `a9401663c90066dddd619167fb2b4452d28fae2f187ea4a967214d08607b78e7`），故可直接覆盖安装。
