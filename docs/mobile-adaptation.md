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
| `data-sidebar-right-panel` | 右侧边栏面板（取值 `fullscreen` / `push`）：手机上它只要打开就必然是 fullscreen |
| `data-sidebar-right-mode` | 右侧边栏那个「全屏 / 退出全屏」按钮：手机上它与「收起」是同一个动作，隐藏（见下） |

除这些属性，还依赖三个结构（类名是哈希前缀，用 `[class*=…]` 匹配）：

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
- **抽屉用 `left` 定位，不用 `transform`**：设置对话框（`position: fixed` 浮层）渲染在
  **侧栏里面**（`SettingsRoot` 注册进 `sidebar.settings` 槽）。`transform` 或
  `will-change: transform` 会让侧栏成为 fixed 后代的包含块 —— 对话框就被缩进抽屉的
  坐标系（真机实测：只有 329px 宽、贴着屏幕左边，视口是 384px）。用 `left` 位移没有
  这个副作用。
- **CSS 优先，JS 只做三件事**：打 `data-handheld` 标记、注册两个槽（会话头的目录按钮、
  外壳浮层的遮罩与浮动入口）、在抽屉里点会话行时收起抽屉。
- **槽而不是手塞 DOM**：遮罩与按钮注册进宿主的 `shell.overlay` /
  `conversation.session.header.actions` 槽，跟着 React 的重渲染走 —— 手塞的节点会在
  会话切换、面板重挂时丢。
- **计数收进 aria-label**：窄屏下后台任务胶囊只留状态点与下箭头，完整计数仍在按钮的
  `aria-label` 上（状态与无障碍信息都没丢，只是不再霸占标题宽度）。
- **浮层住在抽屉里，但只借住 DOM**：设置对话框由宿主注册进 `sidebar.settings` 槽，
  所以它在 DOM 上是侧栏的后代；但它 `position: fixed`、盖满整屏，视觉上是视口级的。
  这条「借住」关系有两个坑，1.0.11 / 1.0.12 都堵上了：① 抽屉收起时的 `pointer-events: none`
  会把对话框一起冻住（它是继承属性）→ 抽屉里只要有 `[role=dialog][aria-modal]`，
  整列就把指针要回来；② 「抽屉里点一下就收起来」的启发式会把「关对话框」的那一笔
  （对话框里的文字、以及**作为面板兄弟的遮罩**）当成"选完了" → 抽屉里有模态时整条
  启发式让位，判据按祖先范围而不是 `target.closest`。
  详见 `docs/known-issues.md` §五。
- **BACK 三级阶梯**（`MainActivity.handleWebBack()`）：手机上「关掉这一屏」的直觉就是系统
  返回键，所以 BACK 按 **模态 → 抽屉 → 原语义** 依次尝试：① 页面里有模态（设置页）时派发
  一次 Escape（dsh 的模态在 document 上监听 Escape），240ms 后复查，模态还在就往下走；
  ② 抽屉开着就点我们自己的遮罩收起它（开合的真相始终在页面侧）；③ 都没有才走原来的语义
  （网页历史 → 连接屏 → 退到后台）。每一级都要求「真的做到了」才停，绝不让 BACK 变成空操作。
- **右侧边栏在手机上只有一个出口**（1.0.14）。宿主自己算的是
  `autoFullscreen = viewportWidth < 768`、`fullscreen = autoFullscreen || mode === "fullscreen"`，
  所以 384px 的视口里右侧边栏**只要打开就是全屏**；而面板右上那个「全屏 / 退出全屏」按钮的
  onClick 是 `if (fullscreen && autoFullscreen) setExpanded(false)` —— 手机上它和
  「收起右侧边栏」**完全是同一个动作**（真机复现：点它，面板直接关掉），唯一多出来的效果是
  把持久化的 mode 写成 `push`，会在用户回到电脑上打开同一个 dsh 时改变面板的初始形态。
  按「做不到 / 重复的入口不留」把那个按钮隐藏（`data-sidebar-right-panel=fullscreen` 时），
  判据用宿主写的两个 `data-*` 而不是 aria-label 的语种。
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
| 设置弹窗的手机布局 | ✅ 做了 | 桌面是 800px 面板里 188px 竖导航 + 内容列，手机上内容列被压到 ~100px（一个字一行）。改成近全屏 + 导航变顶部 **2×2 网格**（第 8 节；为什么不是横滑 tab：360px 下第 4 个会被切掉）。四周留 16px（60 设备像素 ≈ 4.2mm）当「点外面关掉」的遮罩条 —— 原来 8px ≈ 2.1mm，手指点不到 |
| 预览浮层的全屏化 | ❌ 没做 | 实测预览在手机上已可用（全屏态自带「退出全屏」） |
| 第三方 UI 套件（dsh-web-ui / aionui）兼容层 | ❌ 不做 | 本部署没装那套东西 |
| 会话行的长按菜单 | ❌ 没做 | 宿主自己的 `⋯` 菜单够用 |
| 会话日志下载入口 | ❌ 去掉 | 宿主那枚「⋯」按钮就是它，按「做不到/不需要的入口不留」整枚隐藏（见上一条）；`/export` 命令仍在 |

## 怎么验证

三层，和以前一样，只是第一层的对象换成了我们自己的代码：

1. **契约金丝雀**（秒级，CI）：`node scripts/check-mobile-hooks.mjs --contract`
2. **对已安装 dsh 的完整检查**（本机）：`node scripts/check-mobile-hooks.mjs`
   —— 断言那两个钩子确实还在 dsh 前端产物里
3. **真机**：装 CI 产物 → `adb exec-out screencap`（不需要 debuggable）+ `uiautomator dump`
   取无障碍树核对元素与坐标；**命中测试**（点了有没有反应）只能用 `input tap` 驱动 + 看
   实际状态变化 —— 无障碍树给不出「这一笔被谁吃住」，而 1.0.11 修的正是这一类故障

另有一个**本地渲染回环**用于快速迭代 CSS（这个沙箱里 Chromium 发不出 HTTP，所以用
fixture 页面 + 真实的 dsh 组件 CSS，`file://` 加载）：见 `docs/mobile-ui-verification.md`。

## 它与 App 的原生通道（1.0.15 起）

适配层多做了一件事：**把「这一轮生成结束了」告诉 App** —— 任务完成通知的触发源。

| | |
|---|---|
| 判据（完成） | dsh 的「深度求索中…」指示器：`dsh-client-ui-chat` 的 `div[class*="_turnStatus"][role=status]`。它随 `running` 挂载/卸载，我们盯**从有到无**的那次跃迁 |
| 判据（等你在手机上点一下） | `[data-question-key]` / `[data-approval-key]` / `[data-plan-review-key]` —— 提问、工具审批、计划确认三张卡，pending 时挂载、回答后卸载。用 key 去重（同一张卡重渲染不会重复提醒） |
| 通道 | `window.dshNative.postMessage(...)` —— WebView 的 `addWebMessageListener`（`androidx.webkit`），origin 只放行隧道实际会用的两个（`SshTunnel.PORT_CANDIDATES`），**只进不出**（App 不向页面发指令） |
| 消息 | `{"type":"turn-start"}`、`{"type":"turn-done","title":…,"ms":…}`、`{"type":"needs-input","title":…,"key":…}` |
| 没有桥时 | 静默跳过 —— 同一份 bundle 在桌面浏览器里只是不通知，不影响适配 |

三条不显然的实现约束（都写在代码注释里）：

1. **不能用 `requestAnimationFrame` 节流**（上面那个标记兜底 effect 用了没事，标记丢了只是不好看）：
   页面在后台时 rAF 不跑，而我们要捕获的恰恰是「用户在别的 App 里」时发生的那次结束。
   改成「记住上次找到的节点 + `isConnected`（O(1)）判断它还在不在」。
2. **不能用定时器判「输出停了」**：后台的 `setTimeout`/`setInterval` 会被节流到分钟级，
   而 `MutationObserver` 回调是微任务，跟着 JS 任务走，不受节流影响。
3. **`webView.pauseTimers()` 与这个功能直接冲突**：它是全局的（"layout, parsing, and
   JavaScript timers"），而「结束了」这个信号要靠页面重新渲染出来。所以
   `MainActivity.onPause()` 只在页面空闲时才暂停定时器（判据是 `DshApp.pageBusy`，
   由 `turn-start`/`turn-done` 维护）—— 代价是生成期间后台多耗一点电。

## 版本与缓存

`MainActivity.MOBILE_PLUGIN_REV` 是 WebView 侧的缓存键：**内容变了必须换 rev**，否则可能
命中旧缓存。CI 断言 App 常量与 bundle 内的 `id` 一致（`check-mobile-hooks.mjs` 的静态
不变量）。当前为 `dsh-handheld-mobile-1.0.16`。
