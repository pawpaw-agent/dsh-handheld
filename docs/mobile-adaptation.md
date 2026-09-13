# 手机端适配层（自研）

> 2026-09-13 起，这一层是**本仓库自己的代码**。此前它是 vendored 的第三方
> [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT，292 KB / 26 模块），
> 我们靠「在上游文件体内重打补丁」维持它 —— 每次上游发版都要照流程重打一遍，补丁与上游
> 代码混在一起，说不清哪一行是我们的。上游插件与其许可证已删除。

## 它是什么

一个标准的 **dsh 客户端插件**（浏览器半边），只做手机端的界面适配，不改任何服务端行为：

```
android/app/src/main/assets/plugins/
├── dsh-handheld-mobile.js    ← 适配层本体（我们自己写，约 15 KB）
└── mobile-bootstrap.js       ← 注入引导（doc-start 钩 window.__DSH_BOOT__，补一条 entry + batch）
```

加载链路（**服务端零改动**）：

```
App 启动 → WebView 加载 dsh 页面
  └─ addDocumentStartJavaScript(mobile-bootstrap.js)  ← 读 assets，占位符换成 App 常量
       └─ 启动图里多一条 entry/batch：id=dsh-handheld-mobile, url=/plugins/??…/client.js&rev=…
            └─ WebView 请求那个 URL
                 └─ shouldInterceptRequest 命中 → 返回 APK 里的 dsh-handheld-mobile.js
```

插件的运行时外部依赖只有 `react` / `react/jsx-runtime` /
`@deepseek-ai/dsh-client-ui-primitives`，都在前端壳的 staticModules 种子里。

## 它依赖宿主什么

契约文件 `scripts/mobile-hooks-contract.json` 是唯一事实来源，由
`scripts/check-mobile-hooks.mjs` 维护与校验：

| 依赖 | 用途 |
|---|---|
| `data-phase` | 判断会话处于哪个阶段（`hero` / `active` / `settling` / `inert`）：hero 阶段没有会话头，浮动入口在那时出现 |
| `data-sidebar-collapsed` | **抽屉的开合信号**：宿主窄屏下的 `!narrowExpanded`，我们不自己维护开合状态 |

除这两个属性，还依赖三个结构（类名是哈希前缀，用 `[class*=…]` 匹配）：

```
div[class*="_frame"]                     外壳网格：侧栏 | 中栏 | 右栏（还有 _overlayLayer）
  > div[class*="_sidebarCol"]            侧栏 —— 被我们画成抽屉
div[class*="_root"][data-phase]          会话
  > header > div[class*="_titleRow"]
      > div[class*="_crumbs"]            标题道（可压缩）
      > div[class*="_headerActions"]     动作道（不让步）
      > [class*="_root"]:has(> button[class*="_trigger"]) > [class*="_menu"]   头部弹层
```

dsh 哪天改了这些，CI 的 `Mobile adaptation contract` 会红，而不是手机上一声不响地坏掉。
**注意扫描范围是整份 bundle**：适配规则大量写在 CSS 里，只扫 JS 的 `querySelector` 会让
依赖漏出契约（2026-09-13 改为扫全文，钩子集合因此从 6 个收敛到 2 个）。

## 设计取舍（写下来免得以后当成 bug 查）

- **抽屉的开合状态归宿主**。我们只调用 `ctx.layout.toggleSidebar()`，读它写的
  `data-sidebar-collapsed`。自己再存一份状态迟早会和宿主的窄屏逻辑打架。
- **CSS 优先，JS 只做三件事**：打 `data-handheld` 标记、注册两个槽（会话头的目录按钮、
  外壳浮层的遮罩与浮动入口）、在抽屉里点会话行时收起抽屉。
- **槽而不是手塞 DOM**：遮罩与按钮注册进宿主的 `shell.overlay` /
  `conversation.session.header.actions` 槽，跟着 React 的重渲染走 —— 手塞的节点会在
  会话切换、面板重挂时丢。
- **计数收进 aria-label**：窄屏下后台任务胶囊只留状态点与下箭头，完整计数仍在按钮的
  `aria-label` 上（状态与无障碍信息都没丢，只是不再霸占标题宽度）。
- **做不到的入口不留**：凡是「动作发生在电脑上」的入口，手机上按了都没反应，一律隐藏 ——
  ① 工作区标题行的 `+`（目录选择器判成 native，对话框开在电脑桌面）；
  ② 会话头右上角的「在 文件管理器 中打开工作目录」（`open-in-app`：宿主探测本机应用，
  在本机打开工作目录）；
  ③ 会话头那枚「⋯」（`session-log-export`：唯一功能是下载 Session 日志）。
  本项目不动服务端 composition，所以不去改宿主的判定，直接隐藏入口。

## 本版**没有**做的（与上游能力的差距，按需再补）

| 上游有 | 我们 | 说明 |
|---|---|---|
| 抽屉边缘滑动手势（上游 62 KB） | ❌ 没做 | 只能点按钮开合；手势要处理与可拖动浮动组件互让、和系统返回手势冲突，等有明确需求再做 |
| 输入区/键盘的细节防护 | ❌ 没做 | 实测手机上输入区本来就正常 |
| 设置弹窗、预览的全屏化 | ❌ 没做 | 实测两者在手机上已可用 |
| 第三方 UI 套件（dsh-web-ui / aionui）兼容层 | ❌ 不做 | 本部署没装那套东西 |
| 会话行的长按菜单 | ❌ 没做 | 宿主自己的 `⋯` 菜单够用 |
| 会话日志下载入口 | ❌ 去掉 | 宿主那枚「⋯」按钮就是它，按「做不到/不需要的入口不留」整枚隐藏（见上一条）；`/export` 命令仍在 |

## 怎么验证

三层，和以前一样，只是第一层的对象换成了我们自己的代码：

1. **契约金丝雀**（秒级，CI）：`node scripts/check-mobile-hooks.mjs --contract`
2. **对已安装 dsh 的完整检查**（本机）：`node scripts/check-mobile-hooks.mjs`
   —— 断言那两个钩子确实还在 dsh 前端产物里
3. **真机**：装 CI 产物 → `adb exec-out screencap`（不需要 debuggable）+ `uiautomator dump`
   取无障碍树核对元素与坐标

另有一个**本地渲染回环**用于快速迭代 CSS（这个沙箱里 Chromium 发不出 HTTP，所以用
fixture 页面 + 真实的 dsh 组件 CSS，`file://` 加载）：见 `docs/mobile-ui-verification.md`。

## 版本与缓存

`MainActivity.MOBILE_PLUGIN_REV` 是 WebView 侧的缓存键：**内容变了必须换 rev**，否则可能
命中旧缓存。CI 断言 App 常量与 bundle 内的 `id` 一致（`check-mobile-hooks.mjs` 的静态
不变量）。当前为 `dsh-handheld-mobile-1.0.7`。
