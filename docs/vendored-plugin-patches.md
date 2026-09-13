# Vendored 插件补丁记录 — dsh-web-mobile

本项目的移动端界面适配靠 App 侧注入第三方插件
[dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT，作者 mexiaosqwq）实现，
bundle 原样放在 `android/app/src/main/assets/plugins/dsh-web-mobile-client.js`，由
`MainActivity` 的 `addDocumentStartJavaScript` + `shouldInterceptRequest` 喂给 WebView。

**默认原则是逐字节保留上游产物**（便于核对来源、升级时直接覆盖）。本文件记录全部例外。

## 基线

| | 值 |
|---|---|
| 上游包 | `dsh-web-mobile` **v2.4.1**（npm `latest`，取自包内 `lib/client.js`） |
| 基线大小 / md5 | 288,873 字节 / `52f53e55c49870c6a042b8abfb93281c` |
| 当前大小 / md5 | 289,694 字节 / `c2908c6b68c9547c47031ff65f7ce416` |
| 差异 | 2 个 hunk（P1 注释掉 1 行调用；P2 放宽 1 条选择器） |

> **v2.4.0 → v2.4.1 的上游变更**（与本项目补丁无关，直接随基线继承）：
> `sidebar-swipe.js` 86 行 —— 抽屉手势让位给可拖动的浮动组件、起始区固定 45%；
> `session-menu.js` 28 行 —— 删除项在触摸下的 arm 判定；另有把 mobile effect 的
> 触发条件从 `MOBILE_QUERY` 泛化为可传 `TOUCH_QUERY` 的重构。

## 补丁清单（共 2 处）

### P1 — 禁用「删除会话」菜单项

**位置**：bundle 内 `index.js` 装配段（上游 v2.4.1 第 5891 行）。

```js
// 上游原文
    (0, session_menu_ts_1.installSessionMenuDelete)(ctx);
```

**现状**：该行被注释掉，并附上一段说明注释。

**原因**：这是上游 v2.4.0 新增功能里**唯一需要宿主半边**的一项。点击它注入的
「删除会话」会 `POST /api/mobile-nav.session.delete`，而该路由由插件的宿主半边
（`lib/index.js` + `lib/delete-session.js`）注册。本项目的设计是
「**纯 App 侧注入，服务端零改动**」——宿主半边永远不安装，于是：

```
POST /api/mobile-nav.session.delete  →  HTTP 404  not found     （实测）
```

菜单项因此必然报「删除失败：HTTP 404」。与其留一个点了必错的按钮，不如不安装它。

**为什么不改成 CSS 隐藏**：CSS 只遮像素，DOM、事件监听、确认弹窗逻辑仍在运行，
还多出一处 App 侧运行时补丁；直接从装配段摘掉最干净。

**影响面**：仅移除「删除会话」菜单项及其确认弹窗。bundle 内其余 `session-menu`
代码（`resolveSessionId`、`DELETE_ITEM_MARKER`、`TRASH_SVG`、`mobileNav` 字典里的
`delete*` 文案、`[data-mobile-nav="session-delete"]` 样式）保持上游原样，但不再被
执行或使用。插件的抽屉、滑动手势、键盘守卫、响应压缩等其它能力**均未改动**。

### P2 — 后台任务胶囊的窄屏压缩不再要求「同时存在子代理谱系」

**位置**：bundle 内 `effects/aionui-compat.js` 的 `@media (max-width: 559px)` 块。

**上游选择器**：

```css
header:has([class*="_crumbs"] [class*="_root"])
  [class*="_headerActions"] [class*="_root"]:not([class*="_switcherRoot"])
  :has(> button[class*="_trigger"]) [class*="_count"] { display: none !important; }
```

**现状**：去掉 `:has([class*="_crumbs"] [class*="_root"])` 这个前置守卫，其余不变。

**原因**：上游只在「子代理谱系 + 后台任务」**同时**存在时才压缩后台任务胶囊。真机实测
（Galaxy S24 Ultra，WebView 视口 384px CSS 宽）两者**只出现其一**时同样挤压，而且更糟：
会话标题被压成一个字，渲染出来是 `检..`。

机制是两条规则对撞 —— 标题道 `[class*="_crumbs"]` 是 `flex: 1 1 0; min-width: 0`
（吸收全部挤压），而状态胶囊是 `flex: 0 0 auto; min-width: max-content`（一步不让）。
胶囊此时占着整行「1 个后台任务运行中」约 150px，标题只剩约 40px。

**为什么这样改是安全的**：压缩手法本身是上游既有的，不是新发明 —— 保留 `triggerDot`
（运行中的状态点）与下箭头，`aria-label` 仍是完整计数，所以**状态与无障碍信息都没丢**，
只是把它的适用条件放宽到本该覆盖的那一半场景。

**取证方式**：`adb exec-out screencap`（不依赖 debuggable，release 包也能看真实渲染），
配合从 dsh 官方产物读出的 `JobListAction` DOM 契约：

```jsx
<div class="…_root"><button class="…_trigger" aria-label={countLabel}>
  <StateDot class="…_triggerDot"/><span class="…_count">1 个后台任务运行中</span><IconChevronDownOutline14/>
</button><ul class="…_menu">…</ul></div>
```

## 验证方法

用手机尺寸（384×832、`hasTouch`、触屏 UA）打开真实 dsh 页面，按 App 的方式注入
bundle，点开任意会话行的「⋯」菜单，然后检查菜单项与补丁标记：

| 注入的 bundle | ⋯ 菜单项 | `[data-mobile-nav="session-delete"]` |
|---|---|---|
| 上游 v2.4.0 | 重命名 / 分叉会话 / 归档会话 / **删除会话** | 1 |
| 本项目（P1 已打） | 重命名 / 分叉会话 / 归档会话 | **0** |

两次都无 console 错误，菜单仍是宿主原生的三项。

P2 的验证在真机上看会话头：在「有后台任务运行、无子代理谱系」的状态下，标题应保持
可读（不再是单字加省略号），胶囊收缩为 `状态点 + 下箭头`。

## 重新 vendoring 步骤

1. 取上游产物：`npm pack dsh-web-mobile@<version>`，用包内 `lib/client.js` 覆盖
   `android/app/src/main/assets/plugins/dsh-web-mobile-client.js`。
2. 校验基线 md5 是否等于上表（换版本则更新本表）。
3. 重新应用 P1：`grep -n "installSessionMenuDelete)(ctx);"` 定位那一行并注释掉。
4. 重新应用 P2：`grep -n 'class\*="_count"'` 找到那条选择器，删掉
   `header:has([class*="_crumbs"] [class*="_root"]) ` 前缀。
5. `node --check` 确认语法通过。
   ⚠️ **CSS 整体位于 JS 模板字符串内**：新增注释里**不能出现反引号**，否则会提前
   终止模板字符串。这一条是实测踩过的 —— `node --check` 会以
   `SyntaxError: Unexpected identifier` 报出来。
6. 同步更新 `MainActivity.MOBILE_PLUGIN_REV` 与 `scripts/ui-verify.mjs` 的
   `PLUGIN_REV`（两处必须一致，`check-mobile-hooks.mjs` 的静态不变量会校验 id，
   但 rev 的一致性靠这两处手改）：rev 是 WebView 侧的缓存键，内容变了 rev 不变
   可能命中旧缓存。
7. 跑 `node scripts/check-mobile-hooks.mjs --contract` —— 重新 vendoring 会改变
   插件依赖的 dsh 钩子集合，那必须是显式动作。
8. 若上游已把该功能做成无需宿主半边，或本项目决定安装宿主半边，则删除 P1。

## 附：会话删除走「外部移除」

dsh 官方把物理删除定位为**外部动作**，不在自己的接口里提供
（`dsh-session-persistence-jsonl`「已知限制与延期工作」原文：*不删除会话文件——日志在
`root` 下累积，直到外部移除；seam 无删除接口*；UI 上给的是「归档会话」，单向且不回收磁盘）。
所以删会话直接在电脑上做即可，索引层会跟随外部移除，界面随即干净：

```sh
# 存储布局：~/.dsh/sessions/<projectKey>/session-<uuid>/
ls -lt ~/.dsh/sessions/*/*/session.jsonl.zstd | head   # 最近活动的会话
du -sh ~/.dsh/sessions/*/*/ | sort -h | tail           # 最占磁盘的会话

rm -rf ~/.dsh/sessions/<projectKey>/session-<uuid>/    # 一个目录 = 一个会话的全部日志世代
```

注意事项：

- **别删正在运行的会话。** 运行中的会话被宿主持有，dispose 阶段可能把日志目录回写重建
  （社区插件的更新日志踩过这个坑）。先确认它没在跑，再删已经结束的会话最稳。
- 投影缓存 `~/.dsh/storages/session_projcache/sessions/session-<id>.json` 是派生数据，
  留着无害（不占多少空间），想一并清掉也可以。
- 附件字节在共享的 content-addressed 存储里，不会随会话删除；只在没有任何日志引用它们时
  才算不可达垃圾。
