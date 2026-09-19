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
| `data-composer-stats` | 输入框上方那行统计（轮/步 · tok/s、tokens · 缓存命中）：窄屏下重排它，把手机宽度用满（见「设计取舍」） |

除这些属性，还依赖一组结构（类名是 CSS Modules 的哈希前缀，只能用 `[class*=…]` 匹配）。
**它们同样进了契约，而且是两份清单**（2026-09-14 补，见 `known-issues.md` §六 B1）：

| 清单 | 含义 | 找不到时 |
|---|---|---|
| `classHooks`（17 个） | dsh **核心客户端包**里的界面（`_frame` / `_sidebarCol` / `_turnStatus` / `_navList` / `_sep` …） | 完整检查**判失败** —— 那条适配规则已经空转 |
| `classHooksPlugin`（1 个） | **可选插件包**提供的界面（`_moreButton` 来自 `dsh-session-log-export`） | 只提示，不判失败：没装那个插件时规则本来就是空转 |

局限（写下来免得当成没做）：短子串（`_split` / `_count` / `_menu`）在 dsh 里命中多个模块，
所以这个金丝雀只能发现「后缀整体消失」，发现不了「我们想指的那个元素换了模块」。
更强的一条得按真实 DOM 结构断言，需要浏览器 + 一个跑着的 dsh 实例。

结构如下：

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
- **统计行在窄屏用满宽度**（`data-composer-stats`，1.0.18）。宿主给这一行的是
  `width:100% + max-width:--dsh-chat-content-width + 左右各 32px 留白 + justify-content:center`；
  而它住在 composer dock 那个槽里（`conversation.composer.dock` → `.uV2eYG_root`，左右再各 16px），
  `--dsh-chat-content-width` 在窄屏又恒取下限 680px（所以 `max-width` 形同不存在）。
  算下来 384px 的手机上只有 **288px** 可用，而两个胶囊（`9 轮 298 步 · 111 tok/s` /
  `62.6M tok · 缓存命中 98%`）在 13px 下要 ≈349px —— **两个都被省略号吃掉一截**，两侧却空着
  32px（真机截图：`111···` / `缓存命···`；用户的原话是「没有利用完手机屏幕宽度」）。
  改法：左右各 12px、两个胶囊分列两端（`space-between` 把余量吃掉，而不是堆在中间）、
  字号 **11.5px**、分隔符左右 2px、装不下时 **`flex-wrap` 换行**（宁可两行，也不用省略号）。
  **只在 ≤560px 生效**：448px 起宿主那套居中布局本来就装得下，不能让横屏与小平板也贴到屏幕两边。

  这一条的真机教训值得单记：**第一版只按视口宽度建模**（以为有 360px 可用），小回环全绿、
  装上真机照旧 `111···` —— 漏了父容器那 32px，而 12px 字号在小回环里的余量只有 6px，
  真机字体（Roboto / Noto Sans CJK 比 fixture 的字体宽一点）一宽就吃光。定位靠真机截图反推：
  行最左的 ink 起点 37.1 CSS px = 16（父）+ 12（自己）+ 8（胶囊内边距）+ 图标内缩。
  小回环现在按父容器建模，并把**余量 ≥ 12px** 写进断言 —— 「装是装下了」不算过，
  要留得出真机字体的余量（`scripts/composer-stats-lab.mjs`）。

  **真机验证（2026-09-17，SM-S9280 / Android 16 / release APK，384 CSS px）**：同一台手机、
  同一份会话，1.0.17（第一版）显示 `11 轮 398 步 · 107 to···` / `89M tok · 缓存命中 9···`；
  1.0.18 显示 `11 轮 456 步 · 110 tok/s` / `110M tok · 缓存命中 98%` —— **两段完整、单行**。
  截图量出来的几何：ink 跨度 37.1 → 337.1 CSS px（行的内容盒是 28 → 340 = dock 16 + 自己 12），
  两个胶囊之间空 22.7px，余量 ≈18px（与小回环的 22px 之差就是字体宽度差）。

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
   —— 断言这些钩子（含类名后缀）确实还在 dsh 前端产物里
3. **统计行的 A/B 断言**（本机，需要 dsh + chromium）：

   ```sh
   node scripts/plugin-css.mjs /tmp/plugin.css
   node scripts/composer-stats-lab.mjs --css /tmp/plugin.css
   ```

   宿主那几段 CSS 从**安装产物**里现抽（不把 dsh 的样式抄进仓库），fixture 用真实 class 名，
   连**父容器**（composer dock）一起搭；360 / 384 / 412px 三个宽度各跑两遍：
   **A 不注入必须复现截断**（复现不了 = fixture 失真，B 的通过不算证据），
   **B 注入后不得截断、两侧不得留白、且不换行时余量 ≥ 12px**（真机字体更宽）。
   拿 1.0.16 的 CSS 跑会红 —— 所以它不是一条永远通过的断言。
4. **真机**：装 CI 产物 → `adb exec-out screencap`（不需要 debuggable）+ `uiautomator dump`
   取无障碍树核对元素与坐标；**命中测试**（点了有没有反应）只能用 `input tap` 驱动 + 看
   实际状态变化 —— 无障碍树给不出「这一笔被谁吃住」，而 1.0.11 修的正是这一类故障

另有一个**本地渲染回环**用于快速迭代 CSS（这个沙箱里 Chromium 发不出 HTTP，所以用
fixture 页面 + 真实的 dsh 组件 CSS，`file://` 加载）：见 `docs/mobile-ui-verification.md`。

## 它与 App 的原生通道（1.0.15 起）

适配层多做了一件事：**把「这一轮生成结束了」告诉 App** —— 任务完成通知的触发源。

| | |
|---|---|
| 判据（完成） | dsh 的「深度求索中…」指示器：`dsh-client-ui-chat` 的 `div[class*="_turnStatus"][role=status]`。它随 `running` 挂载/卸载，我们盯**从有到无**的那次跃迁。**必须是可见的那一个**（见下） |
| 判据（等你在手机上点一下） | `[data-question-key]` / `[data-approval-key]` / `[data-plan-review-key]` —— 提问、工具审批、计划确认三张卡，pending 时挂载、回答后卸载。用 key 去重（同一张卡重渲染不会重复提醒） |
| 通道 | `window.dshNative.postMessage(...)` —— WebView 的 `addWebMessageListener`（`androidx.webkit`），origin 只放行隧道实际会用的两个（`SshTunnel.PORT_CANDIDATES`），**只进不出**（App 不向页面发指令） |
| 消息 | `{"type":"turn-start"}`、`{"type":"turn-done","title":…,"ms":…}`、`{"type":"needs-input","title":…,"key":…}`、诊断心跳 `{"type":"turn-state","nodes":N}` |
| 没有桥时 | 静默跳过 —— 同一份 bundle 在桌面浏览器里只是不通知，不影响适配 |

### 2026-09-17：「完成后没有收到弹窗提醒」——判据必须取**可见**的那个节点（1.0.19）

真机日志（`adb logcat -s DshApp`，0.1.11 + 插件 1.0.18，开关=true、权限已给、
`dsh-turn` 渠道存在）：

```
00:22:33  页面报告：一轮生成开始（pageBusy=true）
00:28:41  页面报告：一轮生成结束（367948ms，…，前台=true）→ App 在前台，不发通知
00:29:57  页面报告：一轮生成开始（pageBusy=true）
（此后一整小时：一条「页面报告」都没有 —— 那一轮早已结束，用户也回来看过了）
```

也就是说：`turn-start` 收到过两次、`turn-done` 只在第一次收到，之后**观察者卡死**。
原因是判据只按 `found.isConnected` 判、用 `document.querySelector` 取**第一个**
`_turnStatus`：对话与轨迹两个面板各挂一份同名节点，隐藏的那份 `isConnected` 永远为真 →
`running` 一直停在 true → 此后再也不报结束。

改法：`pick()` / `live()` 都要求 **`isConnected && getClientRects().length > 0`**
（后者 = 真的被布局出来，`display:none` 与零尺寸都为 0），两个面板谁可见认谁。

同时还加了**诊断心跳**：跑一轮期间每 60s 发一条 `{"type":"turn-state","nodes":N}`，
App 把它记进日志（`DshApp.onPageMessage` 的兜底分支现在会打印消息内容）。
这一层的失败模式是「一声不响地不再报」—— 没有心跳，事后只能从「一条都没有」反推，
连「观察者死了」还是「它看错了节点」都分不出来。

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

## 空间：留白到底是谁的（1.0.25 – 1.0.34）

用户 2026-09-19 连报四条「留白太多 / 两侧不对称 / 顶部大片留白」。真机量下来是**四个来源**，
逐条证据见 `docs/release-notes-0.1.14.md`；这里只留结论与下次要用的判据：

| 来源 | 实测 | 修法 |
|---|---|---|
| 宿主正文内边距 | `.EvIC1a_scroll{padding:16px calc(…+16px)}` → 两侧各 32px | ≤560px 覆盖成 12px（**+40px 内容宽度**） |
| 会话头高度 | `min-height:76px` **压不动** —— 76 是内容算出来的（10+30+10+25） | 动内容：`padding-top 10→4`、标题行 `min-height 30→24`、页签 `margin-top 10→4` |
| 挖孔安全区 | viewport 没 `viewport-fit=cover` → Chromium 内缩 34px；补上之后宿主外壳又用 JS 内联塞了 `padding-top:35px`（挖孔本体只有 ~11px） | `mobile-bootstrap.js` 补 cover；再用 `html[data-dsh-cover] [class*="_frame"]:has([data-phase]){padding-top:12px !important}` 压内联 |
| **右侧滚动条槽** | `.wSkVaW_scrollBody{scrollbar-gutter:stable;margin-right:2px}` + `--dsh-scrollbar-width:8px` → 右侧恒定多 10px（每块内容：输入卡 27.5/37.3、统计行 41.9/51.5） | `[class*="_scrollBody"]{margin-right:0;padding-right:4px}` → 右侧 8+4 = 12 = 左侧（**滚动条保留**，内容 +10px） |

判据速查：**Δ 恒定 = 某处固定让位**（内边距写错只会差一处）；**Δ 只在个别元素上 = 那个元素自己的盒子**。

**三条教训**：

1. **header 的 `padding-right` 不要碰**：宿主 `.wSkVaW_headerCorner` 带 `margin-right:-16px`，
   把它从 28px 压到 12px 会让右端按钮落到 −4px（用户报「右侧边栏按钮太靠右了」）。两侧的空间
   优化只作用于正文与统计行。
2. **量，不要估**：小回环（`composer-stats-lab.mjs`）对真机字体乐观 —— 真机余量 ≈ 小回环余量 **+32px**；
   截图量化只能数深色像素（左/右/上第一个深色像素 ÷ dpr）。所以这一版加了四个**一次性**诊断
   （`viewport-diag` / `stats-diag` / `layout-diag`（祖先链 + `env()` 探针）/ `side-diag`（滚动体的
   margin/padding/gutter + `offsetWidth − clientWidth` 的真实滚动条宽度）），判据从「我估」变成
   「页面自己报的数」。
3. **盒模型问题先在小回环里复现再改**（负对照不复现就不认结论）：统计行与这次的滚动条槽都是
   小回环先复现、真机再确认。滚动条那条的坑是 `--hide-scrollbars` —— 带上它滚动条宽度归零，
   负对照就永远复现不出来。

## 版本与缓存

`MainActivity.MOBILE_PLUGIN_REV` 是 WebView 侧的缓存键：**内容变了必须换 rev**，否则可能
命中旧缓存。CI 断言 App 常量与 bundle 内的 `id` 一致（`check-mobile-hooks.mjs` 的静态
不变量；`mobile-contract` 里还有一步 `node --check` —— CSS 写在模板字面量里，注释里一个
反引号就能把模板提前结束）。

当前为 `dsh-handheld-mobile-1.0.34`。最近的几档：1.0.34 = 压掉宿主的滚动条槽（左右各 12px 对称）
+ `side-diag`；1.0.33 = 压掉宿主外壳写死的 35px 安全区内边距；
1.0.31/1.0.32 = `layout-diag`（祖先链 + `env()` 探针，就是把上面那条揪出来的工具）；
1.0.30 = 头部左右按钮对称（8 → 12px）；1.0.29 = 右端按钮贴边回归的修复；1.0.28 = 统计行被省略号
吃掉 + `stats-diag`；1.0.25 = 统计行一行到底 + CI 语法检查；1.0.19 = 任务完成通知的判据改取可见节点。

> 一遍遍地踩同一个坑：**内容改了就必须换 rev**，否则手机的 WebView 缓存会把旧的那份喂回来
> （1.0.17、以及 `viewport-fit=cover` 那次都漏换过）。
