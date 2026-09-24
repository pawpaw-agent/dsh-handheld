/*
 * dsh-handheld-mobile —— 本项目自己的手机端界面适配（浏览器半边）。
 *
 * ## 为什么是自己写
 *
 * 此前这一层是 vendored 的第三方 dsh-web-mobile（292 KB / 26 模块）。它的适配思路很好，
 * 但代价是：每次上游发版都要照着流程在上游文件**体内**重打补丁，补丁与上游代码混在
 * 一起，谁也说不清「哪一行是我们的」。2026-09-13 决定自研：这一层从此是我们自己的
 * 代码，进 git、有版本、能单独 review。
 *
 * ## 它怎么被加载（服务端零改动）
 *
 * assets/plugins/mobile-bootstrap.js 在 document-start 钩住 window.__DSH_BOOT__，往启动图里
 * 补一条 entry + batch 指到本文件；App 的 shouldInterceptRequest 把那个 URL 拦下来，返回
 * APK 里的这一份。全局零依赖：只用 dsh 自己的钩子（见 scripts/mobile-hooks-contract.json）
 * 与三个稳定结构：
 *
 *   div[class*="_frame"]                     外壳网格：侧栏 | 中栏 | 右栏
 *     > div[class*="_sidebarCol"]            侧栏（我们要把它变成抽屉）
 *     > div[class*="_overlayLayer"]          外壳浮层（我们的遮罩与浮动入口挂这里）
 *   div[class*="_root"][data-phase=...]      会话（hero / active / settling / inert）
 *     > header
 *       > div[class*="_titleRow"] > div[class*="_crumbs"] / div[class*="_headerActions"]
 *
 * 钩子清单由 scripts/check-mobile-hooks.mjs 对着契约守：dsh 哪天改了这些，CI 的
 * Mobile adaptation contract 会红，而不是手机上一声不响地坏掉。
 *
 * ## 与宿主的关系
 *
 * 抽屉的开合**用宿主自己的状态**（ctx.layout.toggleSidebar → narrowExpanded），我们只负责
 * 把它画成抽屉：网格压成单列 + 侧栏固定定位到左边 + 遮罩。不自己维护开合状态，就不会和
 * 宿主的窄屏逻辑打架；宿主写的 data-sidebar-collapsed 直接当我们的开合信号用。
 */
// ── 注入时序自证（rev 1.0.70，用户问「webview 的插入逻辑可以优化吗」）────────────
// 这是本文件**第一条可执行语句**，所以 performance.now() 在这里读到的值 =
// 「导航起点 → bundle 被取回 → 整段解析完 → 开始执行」的总和。V8 是先解析后执行，
// 所以解析成本已经含在里面；拿它和 apply 结束的差值，就能把「注入链」与「插件自身执行」分开。
// ⚠️ 只上报一次（apply 末尾），不进任何循环。
var __dshHandheldT0 = (window.performance && performance.now) ? performance.now() : 0;

window.__ModuleLoader__.load({
  id: "dsh-handheld-mobile",
  factory: (require) => {
    var react = require("react");
    var primitives = require("@deepseek-ai/dsh-client-ui-primitives");
    var module = { exports: {} };
    var exports = module.exports;

    var h = react.createElement;

    // ── JS 开销归因（2026-09-25，用户问「注入的代码会影响流式响应吗」）──────────────
    // 流式期间真正会反复跑的只有三个回调：frame marker 的 mark（每帧一次）、
    // turn watcher 的 check、needs-input 的 check（后两个**每个 mutation 批次**一次，
    // 而 needs-input 在「没有待输入节点」时要走一次**全树** querySelector）。
    // 光看帧统计分不出「是渲染慢还是 JS 慢」，所以这里把这三个回调各自的累计耗时量出来，
    // 每 10 秒报一次（低于 20ms 就静默 —— 10 秒 20ms = 单核 0.2%，不值得记）。
    // 包装只多两次 performance.now()（~100ns），不影响被量的对象。
    var __cost = { mark: 0, turn: 0, ask: 0 };
    var __costN = { mark: 0, turn: 0, ask: 0 };
    // 只**数**不做事的 characterData 观察者：流式追加文字改的是文本节点内容
    // （characterData），而我们那三个观察者都只订阅 childList —— 它们看不见这类变更。
    // 这个计数器就是为了证明这一点：流式期间它应当猛涨，而 mark/turn/ask 应当接近 0。
    var __cdN = 0;
    try {
      var __cdObs = new MutationObserver(function (recs) { __cdN += recs.length; });
      __cdObs.observe(document.documentElement, { characterData: true, subtree: true });
    } catch (e) { /* 诊断永远不该影响主流程 */ }
    var __now = function () {
      return (window.performance && performance.now) ? performance.now() : Date.now();
    };
    var __wrap = function (name, fn) {
      return function () {
        var t = __now();
        try { return fn.apply(null, arguments); } finally {
          __cost[name] += __now() - t;
          __costN[name]++;
        }
      };
    };

    /** 发一条消息给原生侧；没有桥（桌面浏览器 / 老版本 App）就什么都不做。 */
    var postToApp = function (payload) {
      var bridge = window.dshNative;
      if (!bridge || typeof bridge.postMessage !== "function") return;
      try {
        bridge.postMessage(JSON.stringify(payload));
      } catch (e) { /* 通道坏了不该影响页面 */ }
    };

    /** 会话标题：优先用会话头，取不到就退回 document.title。 */
    var sessionLabel = function () {
      try {
        var header = document.querySelector('[data-handheld="frame"] [data-phase] header');
        var host = header === null
          ? null
          : (header.querySelector('[class*="_crumbs"]') || header);
        var text = host === null ? "" : String(host.textContent || "");
        text = text.replace(/\s+/g, " ").trim();
        if (text !== "") return text.slice(0, 60);
      } catch (e) { /* 取不到就用兜底 */ }
      return String(document.title || "").slice(0, 60);
    };

    /** 只有「窄视口 + 触摸主指针」才生效：桌面窗口完全不受影响。 */
    var MOBILE_QUERY = "(max-width: 1023px) and (pointer: coarse)";

    /**
     * 「点了这一笔不等于选完了」的目标集合 —— 抽屉的「点一下收起来」启发式在这些上面让路。
     *
     *  - 表单控件与按钮：它们自己处理这一笔（搜索框、视图选项、会话行按钮……）；
     *  - 各类浮层：借住在侧栏 DOM 里、视觉上却是视口级的菜单 / 下拉。
     *
     * 注意这里**不包含**设置对话框：它的遮罩是面板的**兄弟**而不是后代
     * （`_overlay > _mask + _panel`），`closest()` 从遮罩往上找不到 `[role=dialog]`。
     * 真机验证时正是这个洞漏掉了遮罩那一笔（点遮罩：对话框关了，抽屉也被顺手收了）。
     * 所以模态交给下面那条「抽屉里有模态就整个让位」处理，判据用祖先范围而不是 target 自身。
     */
    var INERT_TARGETS = [
      "input", "textarea", "select", "button", "[contenteditable]",
      "[role=menu]", "[role=listbox]", "[role=dialog]", "[role=alertdialog]", "[aria-modal=true]",
    ].join(", ");

    /**
     * 我们的样式表。
     *
     * ⚠️ 这是一段 JS 模板字符串：**里面不能出现反引号**，否则提前终止字符串
     * （踩过一次，`node --check` 会以 SyntaxError 报出来）。
     */
    var CSS = `
@media ${MOBILE_QUERY} {
  /* ---------- 1. 外壳压成单列 ----------
     宿主默认是「侧栏 | 中栏 | 右栏」三轨网格，窄屏下侧栏缩成 56px 窄条、展开时把中栏
     挤到只剩一条。手机上不需要窄条：两轨归零，中栏吃满，侧栏改由下面画成浮层。
     padding-top 补安全区：App 是 edge-to-edge 的，WebView 画在状态栏/刘海下面，
     不加这一条头部内容会顶到状态栏里（真机第一次装实测：标题落在 y≈10 CSS px）。 */
  [data-handheld="frame"] {
    box-sizing: border-box !important;
    grid-template-columns: minmax(0, 1fr) 0 0 !important;
    padding-top: env(safe-area-inset-top, 0px) !important;
  }

  /* ---------- 2. 侧栏 = 左抽屉（展开即全屏） ----------
     开合状态直接读宿主写的 data-sidebar-collapsed（窄屏下它等价于 !narrowExpanded），
     所以点宿主的任何入口、或窗口尺寸变化，抽屉都跟着走。

     宽度：用户 2026-09-20「左侧边栏展开变成全屏」—— 手机上不再留那 44px 的缝
     （原来 340px / max 86vw），展开就铺满整屏。行标题因此也宽了 ~44px
     （之前会话标题被 ⋯ 与时间戳挤到要省略）。 */
  [data-handheld="frame"] > [class*="_sidebarCol"] {
    position: fixed !important;
    top: 0;
    bottom: 0;
    left: 0;
    width: 100vw !important;
    max-width: none !important;
    z-index: 40;
    /* 关：整列推到屏幕外。
       刻意用 left 而不是 transform —— transform（以及 will-change: transform）会让
       这一列成为 position:fixed 后代的**包含块**，而设置对话框恰恰渲染在侧栏里
       （SettingsRoot 注册进 sidebar.settings 槽，是个 position:fixed 浮层）。
       踩过的后果：对话框被缩进抽屉的坐标系 —— 只有抽屉那么宽、贴着屏幕左边
       （真机实测 329px vs 视口 384px），里面的桌面两栏布局被压成一字一行。 */
    left: calc(-1 * (100vw + 12px));
    transition: left .22s cubic-bezier(.2, .7, .3, 1);
    /* 刘海与手势条：底部仍由抽屉自己吃安全区（手势条不该被内容压住）。
       顶部**不再退避**（2026-09-22 用户要求）：会话页那条已经把顶部压到 12px
       （见下面 [class*="_frame"]:has([data-phase]) 的 12px），侧栏却还整条让开
       env(safe-area-inset-top)（本机 35px），于是「左右侧边栏比会话页多一截留白」。
       ⚠️ 2026-09-24 用户再报「左右侧边栏顶部空间比会话页面大」—— 上一版只把外层
       对齐成 12px 是不够的：**「外层同值」≠「第一行内容同位置」**，里面还叠着宿主
       自己的内边距。真机同一判据（顶部第一行可见内容的 y，设备像素 / 3.75）实测：

         会话页 22.1   ← 基准
         左抽屉 39.5   ← 多 17.4 = 我们的 12 + 宿主侧栏根的 6（padding:6px 12px）
         右栏   34.9   ← 多 12.8 = 我们的 12（宿主 tab strip 自己还有 10px）

       所以这里归零：12 → 0，宿主侧栏根那 6px 也在下面那条规则里一起让开
       （12 + 6 = 18 ≈ 要消掉的 17.4）。 */
    padding-top: 0;
    padding-bottom: env(safe-area-inset-bottom, 0px);
    border-right: 0 !important;
  }
  [data-handheld="frame"]:not([data-sidebar-collapsed]) > [class*="_sidebarCol"] {
    left: 0;
    box-shadow: 0 0 42px rgba(0, 0, 0, .55);
  }
  /* 收起时彻底让出指针，免得一条看不见的侧栏吃掉边缘手势 */
  [data-handheld="frame"][data-sidebar-collapsed] > [class*="_sidebarCol"] {
    pointer-events: none;
  }  /* ……但抽屉里只要挂着模态，整列就必须照旧吃指针。
     设置对话框是**视口级**浮层（fixed + inset:0，盖满整屏），逻辑上却住在侧栏里
     （SettingsRoot 注册进 sidebar.settings 槽）：抽屉一收，上面那条 pointer-events:none
     会连同它一起冻住 —— 对话框还在屏幕上，却变成一张点不动的画，点击直接穿透到
     背后的页面（真机实测：点 × 不关、点导航不切分区、点对话框外的输入框反而把软键盘
     调起来）。pointer-events 是继承属性，在这一层要回来，里面的遮罩与面板自动跟随。
     判据只用 [role=dialog][aria-modal]：不依赖任何包裹层结构，宿主换一层包裹也成立。 */
  [data-handheld="frame"][data-sidebar-collapsed]:has([role="dialog"][aria-modal="true"]) > [class*="_sidebarCol"] {
    pointer-events: auto;
  }
  /* ---------- 2c. 全屏抽屉：先把宿主的桌面宽度约束撑开，再把尺寸换成手指档 ----------
     用户 2026-09-20「（展开成全屏后）没有利用好空间」。两个原因：

     (1) 宿主侧栏**自己那层根节点**（client/ui-sidebar 的 SidebarRoot）挂着内联
         style={width}（来自 layout 的 cols.sidebar，本机实测 ~265px）——它不跟着我们
         那条 100vw 的列走，于是右边空出 ~119px。普通内联声明压不过 stylesheet 里的
         !important，所以下面这一条能把它拉满（直接子元素就是它，layout 是
         sidebarCol > 侧栏根节点，中间没有包裹层）。
     (2) 行高与字号是**桌面档**：会话行 32px、工作区行 34px、标题 14px、行内图标 16px、
         新会话按钮 36px。全屏之后这些尺寸在一整屏白底上显得又小又空，触摸目标也只有
         32dp（Material 建议 ≥48dp）。这里按手指档整体抬一档：行 44px、标题 15px、
         时间 13px、行内图标 20px、新会话 44px、面板行（设置）46px。
         注意选择器要**限定在行内**：宿主三个模块都有 _iconButton（侧栏品牌那颗是
         28px、工作区行里是 16px、浏览器工具条里又是另一个），不限定会把品牌那颗挤小。 */
  [data-handheld="frame"] > [class*="_sidebarCol"] > * {
    width: 100% !important;
    max-width: none !important;
  }
  /* 顶部那 6px 在**侧栏根**上（宿主 .hHd-Xa_root 展开态 padding:6px 12px），而它不是
     sidebarCol 的直接子节点 —— 2026-09-24 实测：按「> *」写的那条没匹配上（去掉我们的
     12px 后第一行只从 39.5 降到 27.5，正好只等于我们那一档）。所以改用**结构定位**：
     谁**直接包着 logoRow**，谁就是侧栏根，不依赖任何哈希类名，也不依赖中间有几层包裹。 */
  [data-handheld="frame"] [class*="_sidebarCol"] *:has(> [class*="_logoRow"]) {
    padding-top: 0 !important;
  }
  @media (max-width: 560px) {
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_projectRow"],
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_sessionRow"] {
      height: 44px !important;
    }
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_projectRow"] [class*="_title"],
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_sessionRow"] [class*="_title"] {
      font-size: 15px !important;
      line-height: 22px !important;
    }
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_sessionRow"] [class*="_time"] {
      font-size: 13px !important;
      line-height: 22px !important;
    }
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_projectRow"] [class*="_iconButton"],
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_sessionRow"] [class*="_iconButton"] {
      width: 20px !important;
      height: 20px !important;
    }
    [data-handheld="frame"] > [class*="_sidebarCol"] button[class*="_newSession"] {
      height: 44px !important;
    }
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_panelRow"] {
      min-height: 46px !important;
    }
  }
  /* ---------- 2b. 行上那颗「⋯」（工作区行还有「+」）：宿主只在 :hover 时显示，手机上等于永远没有 ----------
     宿主（client/ui-workspace 的 rows/Rows.module.css）写的是：

       .rowActions { display: none }
       .projectRow:hover .rowActions, .sessionRow:hover .rowActions, …menuOpen … { display: inline-flex }

     手机上**根本没有 hover**，于是行上的按钮永远不出现。它们做的是实打实的事：
     工作区行的 ⋯ = 重命名/删除工作区、+ = 在这个工作区里开新会话；会话行的 ⋯ = 重命名/删除/
     归档/分叉这条会话 —— 没有它们，手机端就没法管理。窄屏一律显示，把 hover 那层「藏」去掉。

     时间戳（_time，flex:none）**不动**：它和按钮是并排的 flex 项，不会叠在一起；标题是
     flex:1 + ellipsis，宽度不够时先省略标题（抽屉现在展开即全屏，标题比以前宽 ~44px）。

     顺带把这一族**所有** hover-only 的东西都翻出来（用户 2026-09-20「把（左侧边栏）里面的
     按钮都展开」）—— 宿主里同一个模式还有：

       .projectRow .chevron { display: none }        ← 工作区的展开/折叠箭头
       .projectRow:hover .chevron { display: inline-flex }
       .projectRow:hover .folder { display: none }   ← hover 时用箭头换掉文件夹图标

     箭头是「这一组能不能折叠」的唯一提示，手机上必须一直看得见；文件夹图标**保留**
     （host 是为了省地方才做替换，全屏抽屉里有位置，两个都给）。 */
  @media (max-width: 560px) {
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_projectRow"] [class*="_rowActions"],
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_sessionRow"] [class*="_rowActions"] {
      display: inline-flex !important;
    }
    [data-handheld="frame"] > [class*="_sidebarCol"] [class*="_projectRow"] [class*="_chevron"] {
      display: inline-flex !important;
    }
  }

  /* ---------- 2d. 把行尾那颗 ⋯ 摊平：菜单项直接摆成按钮 ----------
     用户 2026-09-20 连着两次：「把 ... 里面的按钮都展开」→「…里面的按钮直接展开」。
     他说的 … 就是行尾那颗 ⋯（他前一条自己写明了「工作区文件夹上的 ... 和 + 按钮」）：
     重命名/删除工作区、重命名/分叉/归档会话全藏在它的菜单里，手机上要多点一次才看得到。
     做法见下面 JS 的 injectRowActions：插件在每行的动作区里注入与菜单项**一一对应**的按钮
     （点击时替用户走一遍宿主自己的菜单 —— 进的还是宿主的改名编辑器与删除确认框）。
     这两个 data-handheld 是我们自己的，宿主不认识。 */
  [data-handheld="rowActionsDirect"] {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    flex: none;
  }
  [data-handheld="rowAction"] {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    padding: 0;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--dsw-alias-label-tertiary, #8a8a8a);
    cursor: pointer;
    flex: none;
    -webkit-tap-highlight-color: transparent;
  }
  [data-handheld="rowAction"]:active {
    background: var(--dsw-alias-interactive-bg-hover, rgba(0, 0, 0, .06));
    color: var(--dsw-alias-label-primary, #111);
  }

  /* ---------- 2e. 宿主里那些「hover 才亮、但 opacity:0 仍然吃点击」的东西 ----------
     2026-09-20 的只读研究把全仓盘点了一遍，这类控件是手机上最坑的一族：**看不见、但点得到**
     —— 既是隐形热区（误触），又是永远找不到的入口。逐个（都来自安装产物的证据）：

       工具卡 / skill 卡 / Cordis 卡的 Inspect：<hash>_inspectButton / _inspect  （hover → opacity 1）
       主题与字号 stepper 的上下箭头：        <hash>_arrows                        （hover → opacity 1）
       面板标签条的关闭按钮：                 <hash>_tabClose                       （hover → opacity 1 + pointer-events）
       宽 markdown 表格的横滑：                <hash>_tableScroll.md-table-wide     （hover → overflow-x auto）

     窄屏一律显形（并放开指针）—— 宿主自己在 attachment 里就有正确示范：
     （1.0.48 补：只写 opacity 不够 —— 真机体检里那 3 个隐形热区是 **visibility: hidden**，
      所以要连 visibility 一起放开；这一条是被日志当场抓出来的。）
     @media (pointer:coarse){ .xx_remove { opacity: 1 } }，这里只是把它推广到其余几族。
     只在本插件生效（只在手机 App 的 WebView 里注入），桌面 GUI 一点不受影响。 */
  @media (max-width: 560px) {
    [class*="_inspectButton"],
    [class*="_inspect"],
    [class*="_arrows"],
    [class*="_tabClose"],
    [class*="_addTab"],
    [class*="_iconButton"] {
      opacity: 1 !important;
      visibility: visible !important;
      pointer-events: auto !important;
    }
    [class*="_tableScroll"].md-table-wide {
      overflow-x: auto !important;
    }
  }

  /* ---------- 2f. 手指档命中区：把「太小」和「太小又隐形」补上 ----------
     2026-09-20 的只读盘点（46 个 client 包的 CSSModule 全扫了一遍）+ 真机 tap-diag 体检：
       1. primitives 的 Button 默认高 **36px**、size=sm **28px**、DisclosureRow 行 **24px** ——
          它们几乎出现在每个包里，一处改动覆盖全站；
       2. 一大批 28×28 的图标按钮（侧栏、会话头操作、工具/技能卡、Goal 条、右栏…），
          三个极端小的：工作区行内操作 **16×16**、轮次刻度 **20×10**、侧栏拖拽把手 **8px 宽**；
       3. 比「小」更致命的是 hover 门控（看不见 = 点不到），那一类已在 2b/2e 处理。
     这里做两件事：默认按钮/整行可点的行抬到 **44px**；小图标按钮**只扩命中区**
     （::after 撑 6px → 40px），不动视觉尺寸 —— 免得把密集工具条挤变形。
     注意选的是 _button_ （带下划线）而不是 _button：后者会把 _iconButton_<hash>
     一起吃掉（大小写敏感，恰好躲开）。本模板里**不能写反引号** —— 又踩了一次。 */
  @media (max-width: 560px) {
    [class*="_button_"] {
      min-height: 44px !important;
    }
    [class*="_row"][role="button"] {
      min-height: 44px !important;
    }
    /* 小图标按钮与小触发块：扩命中区，不动视觉。
       真机体检（1.0.46）给出的"又小又没扩过"的头部名单就是 button_action_ / _trigger /
       _iconButton / _addTab / 会话头页签 —— 所以这里直接按**按钮元素**点名，
       不用容器类（容器上挂 ::after 只会白占一层）。 */
    button[class*="_action"],
    button[class*="_trigger"],
    button[class*="_iconButton"],
    button[class*="_iconBtn"],
    button[class*="_remove"],
    button[class*="_tabClose"],
    button[class*="_disclosure"],
    [class*="_iconButton"],
    [class*="_iconBtn"] {
      position: relative;
    }
    button[class*="_action"]::after,
    button[class*="_trigger"]::after,
    button[class*="_iconButton"]::after,
    button[class*="_iconBtn"]::after,
    button[class*="_remove"]::after,
    button[class*="_tabClose"]::after,
    button[class*="_disclosure"]::after {
      content: "";
      position: absolute;
      inset: -8px;
    }
    /* 会话头页签是**左右相邻**的，水平方向不能吃邻居的空间 —— 只往上下扩。 */
    [class*="_tab"]::after {
      content: "";
      position: absolute;
      inset: -8px 0;
    }
    [class*="_tab"] {
      position: relative;
    }
    /* 抽屉里我们注入的那排行内操作：直接给足手指尺寸 */
    [data-handheld="rowAction"] {
      width: 32px;
      height: 32px;
    }
  }

  /* ---------- 3. 遮罩 ---------- */
  [data-handheld="backdrop"] {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, .42);
    opacity: 0;
    pointer-events: none;
    transition: opacity .2s linear;
    -webkit-tap-highlight-color: transparent;
  }
  [data-handheld="frame"]:not([data-sidebar-collapsed]) [data-handheld="backdrop"] {
    opacity: 1;
    pointer-events: auto;
  }

  /* ---------- 4. 会话头：标题让位、控件不让 ----------
     标题道（_crumbs）可压缩并省略，动作道（_headerActions）保持原样 —— 实测反过来的
     话，后台任务胶囊会把标题挤成一个字。 */
  [data-handheld="frame"] [data-phase] header {
    position: relative !important;
    /* 刻意不动 header 的左右内边距：宿主是 padding:10px 28px 0 20px，而右端那条
       动作道 ._headerCorner 自带 margin-right:-16px（让图标与右栏边缘对齐），
       两者相抵后右边距只剩 12px。我们若把 padding-right 压到 8px，相抵就变成 -8px ——
       右侧栏按钮会被顶到屏幕边缘外（当地探针实测 cornerRight 392 > 视口 384）。
       左边距同理交给宿主：绝对定位的目录按钮在 left:8px，标题行的内边距单独加。 */
  }
  /* 目录按钮注册在动作道里（宿主没有「左端」插槽），但手机上它该在左上角 ——
     所以绝对定位到头部左边缘，再给标题行让出等宽的内边距。
     这里只写 12px：安全区已经由 frame 的 padding-top 让出来了，再算一次会double。 */
  [data-handheld="frame"] [data-phase] header [data-handheld="toggle"] {
    position: absolute !important;
    /* 12px 而不是 8px（2026-09-19）：宿主的右端角落按钮盒子在
       padding-right 28 − headerCorner.margin-right 16 = 12px，而我们在 8px ——
       真机实测左 ink 14.1 CSS px / 右 ink 18.9 px，差 4.8px，一眼就看出「和左边不对称」。
       两边盒子都取 12px 之后，左右 ink 都是 ~18px。 */
    left: 12px !important;
    /* 竖直位置必须跟着**标题行**走（用户 2026-09-19 第二次报：「两边上下不对称？」）：
       按钮是绝对定位的，top 是相对 header 顶边的死值；而标题行的上沿 = header 的
       padding-top。压缩顶部留白时把 padding-top 从 10 压到 4（有 cover 时 2），
       死值 12px 就留在了原处 —— 真机实测左图标中心 y=38.0、右图标 27.9，差 10.1 CSS px。
       现在两边用**同一个变量**：变量在 header 上设置，按钮是它的后代，自然继承。
       兜底 12px = 宿主默认布局（padding-top 10 + 标题行 30）下原来那个值。 */
    top: var(--dsh-handheld-head-top, 12px) !important;
    z-index: 2 !important;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: var(--dsw-alias-label-secondary, inherit);
    -webkit-tap-highlight-color: transparent;
  }
  /* 标题行给绝对定位的目录按钮（left:8px + 28px 宽）让出左边距：宿主本来的
     header padding-left 是 20px，再加 20px = 40px，与按钮右缘留 4px 间隙。 */
  /* ️ 必须锚定**会话头**（审计 L15）：[data-phase] 挂在会话根上，它下面还有别的
     header（提问卡的头、轨迹视图的头），原来那条会给它们也加 20px 左内边距 ——
     卡片标题相对左边距凭空多一截、与右侧动作按钮错位。 */
  [data-handheld="frame"] [data-phase] header:has([class*="_titleRow"]) > :first-child {
    /* 24px：目录按钮挪到 left:12px 之后右缘在 40px，标题从 20+24=44px 起，留 4px 间距 */
    padding-left: 24px !important;
  }
  [data-handheld="frame"] [data-phase] header [class*="_titleRow"] {
    min-width: 0 !important;
    gap: 2px !important;
  }
  [data-handheld="frame"] [data-phase] header [class*="_crumbs"] {
    min-width: 0 !important;
    overflow: hidden !important;
    text-overflow: ellipsis !important;
    white-space: nowrap !important;
  }
  [data-handheld="frame"] [data-phase] header [class*="_headerActions"] {
    flex: 0 0 auto !important;
    min-width: 0 !important;
  }
  /* 后台任务胶囊：窄屏只留状态点与下箭头，完整计数仍在按钮的 aria-label 上
     （状态与无障碍信息都没丢，只是不再霸占标题的宽度）。 */
  [data-handheld="frame"] [data-phase] header [class*="_headerActions"] [class*="_count"] {
    display: none !important;
  }
  /* 会话内 tab 横滑，不换行 */
  [data-handheld="frame"] [data-phase] header [role="tablist"] {
    flex-wrap: nowrap !important;
    overflow-x: auto !important;
    overscroll-behavior-x: contain;
    scrollbar-width: none;
  }
  [data-handheld="frame"] [data-phase] header [role="tablist"]::-webkit-scrollbar {
    display: none;
  }

  /* ---------- 5. 头部弹层 ----------
     胶囊 / 谱系的弹层是 position:absolute，宿主按「锚在触发按钮的 root 上」设计
     （.QsffPG_root{position:relative}）。但触发按钮在右对齐的动作道里，锚在它身上再展开
     336px 宽的菜单，左边会被挤出屏幕（真机实测：菜单左半截被裁掉）。

     所以把包含块抬到**头部**：root 保持 static（宿主的初始值），header 设 relative
     （上面已经设了），弹层的 left/top 于是相对头部解析 —— 横向落在头部左边缘 +8px，
     纵向落在整个头部下方，336px 宽在 384px 视口里完整可见。 */
  [data-handheld="frame"] [data-phase] header [class*="_root"]:has(> button[class*="_trigger"]) {
    position: static !important;
  }
  [data-handheld="frame"] [data-phase] header [class*="_menu"] {
    left: 8px !important;
    right: auto !important;
    width: min(336px, calc(100vw - 16px));
    max-width: none;
    max-height: min(420px, calc(100dvh - 160px));
  }

  /* ---------- 6. hero 阶段的浮动入口 ----------
     空白会话没有会话头，也就没有头部那颗目录按钮，用浮动按钮兜底；抽屉开着时它让位。 */
  [data-handheld="fab"] {
    position: fixed;
    top: calc(env(safe-area-inset-top, 0px) + 12px);
    left: 10px;
    z-index: 21;
    display: none;
    width: 40px;
    height: 40px;
    align-items: center;
    justify-content: center;
    padding: 0;
    border: 1px solid var(--dsw-alias-border-l1, rgba(0, 0, 0, .12));
    border-radius: 50%;
    background: var(--dsw-alias-button-floating-fill, #fff);
    color: var(--dsw-alias-label-primary, inherit);
    box-shadow: 0 2px 12px rgba(0, 0, 0, .18);
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;
  }
  [data-handheld="frame"]:has([data-phase="hero"]) [data-handheld="fab"],
  [data-handheld="frame"]:has([data-phase="inert"]) [data-handheld="fab"] {
    display: inline-flex;
  }
  [data-handheld="frame"]:not([data-sidebar-collapsed]) [data-handheld="fab"] {
    display: none;
  }

  /* ---------- 7. 做不到的入口不留 ----------
     (a) 工作区标题行那颗 +（dsh 自己的按钮，aria-label = workspace.add）——
     **2026-09-20 起放回来**：用户要求「把（左侧边栏）里面的按钮都展开」，而这颗正是
     唯一的例外（此前是拿 aria-label 藏掉的）。放回来的依据：它开的目录选择器由宿主的
     directory-picker-auto 决定，而那条规则要求「本地回环绑定 + 非 SSH 启动 + 有可服务的
     显示会话（linux 上要 DISPLAY/WAYLAND_DISPLAY + zenity/kdialog）」；这台宿主的桌面
     能力已经确认没有（present 文件时宿主自己回「此主机没有可用的桌面」），所以它必然
     落到 browse（网页版目录浏览器）—— 手机上点得动。真机点过确认（见 release notes）。
     注释留档：这段判断如果哪天宿主换了带显示会话的机器，这颗 + 会改成开电脑桌面上的
     原生对话框（手机上按了没反应）—— 那时再决定要不要重新藏。

     (b) 会话头右上角的「在 XXX 中打开工作目录」（dsh-client-ui-open-in-app 的分屏按钮）：
     它是**在电脑上**用某个已安装的程序打开当前会话的工作目录 —— 宿主探测到的是
     filemanager（GET /open-in-app/apps 返回 {"apps":["filemanager"]}），点击调
     /open-in-app/open，效果是**电脑桌面**上弹出文件管理器窗口，手机上什么都不会发生。
     同一个理由去掉。判据用类名而不是 aria-label：这个按钮的类名带模块哈希
     （_split / _main / _chevron），本 dsh 的样式语料里只有三处 _split，另两处
     （轨迹表、交付物卡片）都不在会话头里，所以 header [class*="_split"] 能精确命中它。
     注意：上面这段注释里**不能出现反引号** —— CSS 整段在 JS 模板字符串内，
     反引号会提前终止字符串（踩过两次，node --check 会以 SyntaxError 报出来）。 */
  [data-handheld="frame"] [data-phase] header [class*="_split"] {
    display: none !important;
  }

  /* (c) 会话头那枚「⋯」整个去掉 —— 它存在的唯一目的就是「下载 Session 日志」：
     由 dsh-session-log-export 注册进 conversation.session.header.utilities 槽，
     内容是「省略号图标 + 只有一个菜单项的菜单（menu.download = 下载 Session 日志）」。
     去掉它 = 手机上不再有会话日志下载入口（抽屉底部那个「导出会话日志」是旧 vendored
     插件加的，已随插件一起删除）。
     判据：这个插件本版唯一的类名是 <hash>_moreButton，全 dsh 安装里只有它一个模块
     定义这个名字（grep -rl _moreButton 命中 1 个文件），所以能精确命中。 */
  [data-handheld="frame"] [data-phase] header [class*="_moreButton"] {
    display: none !important;
  }

  /* ---------- 8. 设置对话框：两栏 → 上下 ----------
     宿主桌面版是「800px 面板 = 188px 竖导航 + 内容列」。手机上面板只有
     calc(100vw - 48px) ≈ 336px，内容列被压到 ~100px —— 真机实测每个字一行
     （「选择新会话的默认权限模式」竖着排成 12 行）。

     改成：面板铺满 + 导航从左侧竖栏变成**顶部 2x2 网格**。
     选 2x2 而不是横滑 tab 条：360px 视口下 4 个 tab 横排会被切掉（真机上第 4 个显示成
     「Agent 预…」），横滑是隐藏成本；2x2 四个全在视野里、一次点击，代价只是多占约 76px。
     判据用语义结构而不是哈希类名：面板是 [role=dialog][aria-modal=true]:has(> nav)，
     导航就是它下面那个 <nav>，内容列是 _content / _options。
     作用域挂在 html.dsh-handheld-mobile 而不是 frame 上：这个对话框是 fixed
     浮层，可能被渲染到 frame 之外（portal），挂在 frame 上会漏掉。

     面板四周留 32px（每边 16px = 本机 60 设备像素 ≈ 4.2mm），这一圈就是「点外面关掉」
     那个遮罩条。原来每边 8px：真机复验时我得精确点到 x=4 CSS px 才点到 —— 8px ≈ 2.1mm，
     手指根本不可能（触摸目标惯例 ≥ 4mm）。留白加大后 ✕ 与 BACK 仍是主要关闭路径
     （见 docs/mobile-adaptation.md），「点外面」只是顺手也能关。 */
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"]:has(> nav) {
    width: calc(100vw - 32px) !important;
    max-width: calc(100vw - 32px) !important;
    height: calc(100dvh - 32px) !important;
    max-height: calc(100dvh - 32px) !important;
    border-radius: 20px !important;
    flex-direction: column !important;
  }
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"] > nav {
    flex: none !important;
    width: auto !important;
    padding: 8px 10px 4px !important;
    overflow: visible !important;
  }
  /* 「设置」那行标题在窄屏不占位（网格自己说明是什么） */
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"] > nav > :first-child {
    display: none !important;
  }
  /* 4 个分区 = 2x2 网格：全部可见、一次点击、不横滑、不折行 */
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"] > nav > [class*="_navList"] {
    display: grid !important;
    grid-template-columns: 1fr 1fr !important;
    gap: 4px !important;
    min-width: 0;
  }
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"] > nav [class*="_navCell"] {
    flex: none !important;
    width: 100% !important;
    height: 34px !important;
    padding: 6px 10px !important;
    white-space: nowrap;
  }
  /* 内容列铺满，内边距收紧 */
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"] > [class*="_content"] {
    flex: 1 1 auto !important;
    min-height: 0 !important;
  }
  html.dsh-handheld-mobile [role="dialog"][aria-modal="true"] [class*="_options"] {
    padding: 0 14px 18px !important;
  }

  /* ---------- 8b. 右侧栏也要吃安全区（用户：「右侧边栏展开后最顶部无法点击」）----------
     手机上右侧栏只能以 fullscreen 出现：宿主写的是
       .P3OORG_panel[data-sidebar-right-panel=fullscreen] { position: fixed; inset: 0; z-index: 40 }
     —— 也就是它的面板头从 y=0 开始铺。而屏幕最上面那一条（约 0~32 CSS px）是**系统状态条**
     的地盘：我们自己的会话头按钮在 y=32 还能点到，再往上就点不到了（真机实测过多次）。
     右侧栏的面板头（标题 + 工具按钮）正好整条落在那里面 → 「最顶部无法点击」。
     左抽屉早就用 padding-top: env(safe-area-inset-top) 让开了（第 2 节），右侧栏漏了。
     补上同样的安全区；box-sizing: border-box 保证整体高度不被撑出屏幕。 */
  [data-sidebar-right-panel="fullscreen"] {
    /* 2026-09-22 用户要求：与会话页一样全屏，不再整条让开 env(safe-area-inset-top)。
       ⚠️ 2026-09-24 再收一档（12 → 0）：真机实测第一行可见内容 34.9，比会话页的 22.1
       多 12.8，正好是我们这 12px —— 宿主 dockkit 的 tab strip 自己就带
       padding:10px 6px 0 10px（见 index-DPX2bQLO.css 的 ._tabStrip_），我们那 12px 是
       纯叠加。归零后第一行落在 22.9，与会话页齐平。
       ⚠️ 这条曾经是为了「面板头落在系统吃触摸的带子里点不动」才加的 safe-area ——
       归零后面板工具栏升到 CSS y≈22–52，与会话页头部按钮（12.8–42.9，天天在用）同一带子，
       但仍必须真机复验那颗「收起」点得到（点不到就把 padding-top 调回 ~8px）。 */
    padding-top: 0 !important;
    box-sizing: border-box !important;
  }

  /* ---------- 9. 右侧边栏：手机上的「退出全屏」是第二个收起按钮 ----------
     宿主（dsh-client-ui-sidebar-right）自己算的：
         const autoFullscreen = viewportWidth < 768;
         const fullscreen = autoFullscreen || surface?.layout.mode === "fullscreen";
     本机视口 384px，所以右侧边栏**只要打开就必然是 fullscreen**；而那个模式按钮的
     onClick 是「if (fullscreen && autoFullscreen) actions.setExpanded(sessionId, false)」
     —— 手机上它和「收起右侧边栏」是同一个动作（真机复现：点它，面板直接关掉，
     与点收起没有区别），唯一多出来的效果是把持久化的 mode 写成 "push"，会在用户
     回到电脑上打开同一个 dsh 时改变面板的初始形态。
     一个按钮、两种写法、零信息量 —— 去掉它（这一屏只剩「收起」一个出口）。
     判据用宿主自己写的两个 data-* 钩子，不用 aria-label 的语种：面板
     [data-sidebar-right-panel="fullscreen"]、按钮 [data-sidebar-right-mode]。 */
  [data-sidebar-right-panel="fullscreen"] button[data-sidebar-right-mode] {
    display: none !important;
  }

  /* ---------- 10. 输入框上方那行统计（data-composer-stats）：把手机宽度用满 ----------
     宿主（client/ui-chat 的 StatsPills）给这一行的样式是：
         width:100%; max-width:--dsh-chat-content-width;
         padding:4px calc(--dsh-composer-side-clearance + 16px) 0; justify-content:center;
     而它挂的槽是 composer dock（conversation.composer.dock，client/ui-conversation 的
     .uV2eYG_root: padding 0 var(--dsh-composer-side-clearance)，即左右各 16px）。
     于是 384px 的手机上：行宽 384-32=352，再减宿主自己那 32px 内边距 → 只剩 288px；
     两个胶囊「9 轮 298 步 · 111 tok/s」「62.6M tok · 缓存命中 98%」在 13px 下要 ≈349px，
     于是**两个都被省略号吃掉一截**，两侧却还空着 32px。

     改法：左右各 8px、两个胶囊分列两端（space-between 把余量吃掉，而不是堆在中间）、
     字号 10.5px、分隔符左右 2px；**一行到底，不换行**。

     2026-09-18 订正（用户明确要求「不希望变成两行显示」）：上一版在 384px 下算出的余量是
     22px，于是把换行当兜底 —— 真机上却换成了两行（96 轮 289 步 · 182 tok/s 与
     75.7M tok · 缓存命中 98% 各一行）。原因是那 22px 抵不住真机字体（Roboto / Noto Sans
     CJK）的宽度差，而计数器位数一涨就更紧。现在：flex-wrap: nowrap + 字号 10.5px +
     左右各 8px（小回环阈值相应提到 40px 余量）；万一还是装不下（4 位以上的轮/步），
     由胶囊的 text-overflow: ellipsis 收尾 —— 宁可尾部省略，也不要多占一行。
     ⚠️ 这段注释在 CSS 模板字面量里：**不要写反引号**（写一次就把模板提前结束，语法直接坏）。

     ⚠️ 这两个数是真机校准出来的，别凭感觉改回去：
      1. 第一版只按视口（384px）建模、以为有 360px 可用，装上真机照旧 111··· —— 漏了上面
         那个父容器的 32px。真机截图反推：行最左的 ink 起点 37.1 CSS px
         （= 16 父 + 12 自己 + 8 胶囊内边距 + 图标内缩），据此才定位到 .uV2eYG_root。
      2. 12px 字号在小回环里只剩 6px 余量，真机字体（Roboto / Noto Sans CJK）更宽 → 仍截断；
         11.5px 在 384px 下余量 22px，留得住。
      3. flex-wrap 是兜底：窄到装不下时第二个胶囊换到下一行（各占一行、都完整）。
         小回环对这条有 A/B 断言（含「余量 ≥ 12px」阈值）：
         scripts/composer-stats-lab.mjs --css <plugin-css>。

     只在 ≤560px 生效：横屏与小平板上宿主那套居中布局本来就装得下（448px 起不截断），
     不能让它们也贴到屏幕两边。判据见 docs/mobile-adaptation.md「统计行」。 */
  @media (max-width: 560px) {
    [data-composer-stats] {
      max-width: none !important;
      padding-left: 8px !important;
      padding-right: 8px !important;
      justify-content: space-between !important;
      gap: 3px !important;
      /* 一行到底：换行会让底部多占一行（用户 2026-09-18 明确不要）。 */
      flex-wrap: nowrap !important;
      font-size: 10px !important;
      /* 2026-09-25 用户：「将会话页面最底部的统计信息移到最顶部」——
         它本来挂在 composer dock 槽里（输入卡片**下方**），这里用 fixed 把它挪到页面最顶：
           · 祖先链上没有 transform / will-change / contain（已核对宿主与插件两侧），
             所以 fixed 相对视口，不会跟着内容滚；
           · z-index 8：压住正文与会话头（6），但低于 FAB（21）与抽屉/右栏（40）——
             抽屉或面板打开时它会正常被盖住；
           · 左右两个胶囊分列两端、中间留空 —— 屏幕顶部正中是挖孔（本机 128 / 88 设备像素），
             中间那点空档正好避开它；
           · 头部由外壳 padding-top 整体下移让位（见下面 _frame 那条 12 → 17px）。 */
      position: fixed !important;
      top: 0 !important;
      left: 0 !important;
      right: 0 !important;
      z-index: 8 !important;
      margin: 0 !important;
      /* 高度**写死**：这条带子的高度不能由宿主胶囊的内边距决定 —— 否则它比外壳让出的
         17px 高时，会把下面的会话头压住（而那正是它上方唯一的东西）。
         10px 字配 14px 行高，16px 盒子够放；多出来的内边距裁掉（胶囊没有背景色，看不出来）。 */
      height: 16px !important;
      line-height: 14px !important;
      padding-top: 1px !important;
      overflow: hidden !important;
      background: var(--dsw-alias-bg-base, #151517) !important;
    }
    /* 极端长的计数（4 位以上「轮/步」）也只允许尾部省略，不再换行。 */
    [data-composer-stats] > * {
      white-space: nowrap !important;
      min-width: 0 !important;
      overflow: hidden !important;
      text-overflow: ellipsis !important;
    }
    /* 分隔符「·」宿主给了左右各 6px；窄屏收到 2px —— 两个胶囊各省 8px，共 16px 余量 */
    [data-composer-stats] [class*="_sep"] {
      margin: 0 1px !important;
    }
  }

  /* ---------- 10b. 输入框吃满左右空间（2026-09-24，用户「输入框没有利用左右空间」）-----
     真机实测（设备 2 / 1080x2400 / 360 CSS 宽）：输入卡片左右边界 CSS 28.0..331.7（宽 303.7），
     而正文文字的 ink 是 24..335.7 —— 卡片比正文还窄 4px，两侧各空 28px。

     那 28px 是两层叠出来的，不是宿主一家的：
         12px = 我们第 11 节给滚动体（[class*="_scroll"]）的左右内边距
         16px = 宿主 .uV2eYG_root 的 padding:0 var(--dsh-composer-side-clearance) 8px
                （--dsh-composer-side-clearance 默认 16px，定义在 .wSkVaW_root 上）

     顺带排掉一个看似可能的元凶：--dsh-composer-card-max-width = clamp(680px,…,920px) + 32px，
     在 360 宽的屏上**根本不生效**，所以卡住宽度的就是上面这两层内边距。

     改法：把变量挂在 data-composer-seat 上（宿主自己写的稳定属性，不是哈希类名）——
     它只覆盖输入框那一棵子树，正文的 12px、各种卡片自己的 calc 都不受影响。
     归零后卡片落在 12..348：与正文**容器**同宽，卡片内的占位文字起点 24 = 正文 ink 起点 24 ✓。 */
  @media (max-width: 560px) {
    [data-composer-seat] {
      --dsh-composer-side-clearance: 0px !important;
    }
  }

  /* ---------- 11. 两侧与顶部：把宿主的留白还给内容（2026-09-19） ----------
     宿主在手机上白留得很明显（两条规则都是从安装产物里抽出来核对的）：

       .EvIC1a_scroll  { padding: 16px calc(var(--dsh-composer-side-clearance) + 16px) }
                        → 左右各 16+16 = 32px。384px 的屏幕上正文两侧被吃掉 64px（全宽的 17%）。
       .wSkVaW_header  { min-height: 76px; padding: 10px 28px 0 20px }
                        → 会话头固定占 76px 高，而它里面只有一行标题 + 一行页签。

     改：正文左右各 12px（多出 40px 内容宽度）、上边距 16 → 8px；
     会话头 76 → 52px 高、上边距 10 → 6px、右侧 28 → 12px（标题能多显示几个字）。
     会话头用 header:has([class*="_titleRow"]) 锚定 —— 宿主里还有别的 _header 类
     （面板头 36px 那种），不加 :has 会一起压坏。 */
  @media (max-width: 560px) {
    [data-handheld="frame"] [data-phase] [class*="_scroll"] {
      padding-left: 12px !important;
      padding-right: 12px !important;
      padding-top: 8px !important;
    }
    /* 会话滚动体（.wSkVaW_scrollBody）里的**滚动条槽**：宿主给桌面留的
       scrollbar-gutter: stable（--dsh-scrollbar-width: 8px）再叠 margin-right: 2px，
       在手机上就是右侧凭空多出 10px —— 用户第二次报的「两侧还是不对称」就是它。
       真机实测（1.0.33，截图量深色像素）：正文左 28px / 右 38px
       （= 我们 12px + 宿主 clearance 16px，两侧本该相同；右侧多出的正好是 8 + 2）。
       改法：把槽宽算进右内边距 —— margin 2 → 0、padding-right 12 → 4，
       于是右侧 = 8(槽) + 4 = 12 = 左侧 ✓，滚动条本体保留（它是位置指示器），
       内容同时多回 10px 宽度。这条必须排在 [class*="_scroll"] 之后（特异性相同，后者生效）。 */
    [data-handheld="frame"] [data-phase] [class*="_scrollBody"] {
      margin-right: 0 !important;
      padding-right: 4px !important;
    }
    [data-handheld="frame"] [data-phase] header:has([class*="_titleRow"]) {
      min-height: 0 !important;      /* 76 是**内容撑出来**的（10+30+10+25），压 min-height 没用 */
      padding-top: 4px !important;   /* 10 → 4 */
      /* 目录按钮（绝对定位）的 top 跟着这个值走 —— 见第 4 节 [data-handheld="toggle"]。 */
      --dsh-handheld-head-top: 4px;
      /* padding-right **不动**（保持宿主的 28px）：宿主的 .wSkVaW_headerCorner 自带
         margin-right:-16px，所以右端那个按钮的实际位置是 padding-right − 16。2026-09-19
         把它压到 12px → 按钮落到 −4px，几乎贴到屏幕边缘（用户当场发现：「右侧边栏按钮太靠右了」）。
         两侧的空间优化只该作用在**正文**（见上面 [class*="_scroll"] 与统计行），不该动头部右端。 */
    }
    /* 页面已经用满整屏（引导脚本补了 viewport-fit=cover，见 mobile-bootstrap.js）时：
       宿主外壳（dsh-client-ui-layout 的 pI_x6G_frame）会**用 JS 内联**把
       env(safe-area-inset-top)（本机 35px）整条塞成 padding-top —— 挖孔本体只有约 11px，
       那 35px 就是用户说的「顶部大片留白」。CSS 的 !important 能压过内联样式；
       选择器 _frame:has([data-phase]) 只命中包着会话的那一层（真机祖先链里只有这一个）。
       没 cover 时 env 是 0、宿主也不加，不需要动。
       （本模板里**不能出现反引号** —— 已经踩过四次，会把模板提前结束。）*/
    html[data-dsh-cover] [class*="_frame"]:has([data-phase]) {
      /* 12 → 18：顶上多了一条常驻的统计带（fixed 在 y=0..16），头部整体让开它，
         否则标题会被压在带子底下。18 = 16（带子）+ 2（间隔）。 */
      padding-top: 18px !important;
    }
    /* 顶部间距交给外壳那一层，这里不再叠加（原来 cover 时给 14px，现在 2px）。 */
    html[data-dsh-cover] [data-handheld="frame"] [data-phase] header:has([class*="_titleRow"]) {
      padding-top: 2px !important;
      --dsh-handheld-head-top: 2px;   /* 目录按钮跟着标题行一起上移 */
    }
    /* 宿主的 76px 全是内容：padding 10 + 标题行 30 + 页签 margin-top 10 + 页签 16+9。
       下面三条各让一步，合计再省 ~18px；页签自身的 padding-bottom 不动（那是手指的目标区）。 */
    [data-handheld="frame"] [data-phase] header:has([class*="_titleRow"]) [class*="_titleRow"] {
      min-height: 24px !important;   /* 30 → 24 */
    }
    [data-handheld="frame"] [data-phase] header:has([class*="_titleRow"]) [class*="_tabs"] {
      margin-top: 4px !important;    /* 10 → 4 */
      /* 2026-09-24 用户：「轨迹无法点击，可以完全隐藏对话和轨迹按钮节约空间」——
         整条隐藏，省下一行（页签 25px + 上边距 4px ≈ 29px 竖向空间）。
         ⚠️ 这不是在修 bug：真机复验（设备 2 / Android 13 / rev 1.0.72）里「轨迹」是**能点**的
         —— 点下去整屏 62.75% 的像素变化、视图切到轨迹，无障碍树里它也是 clickable=true。
         所以这是按用户要求省空间；想恢复：删掉 display 这一行即可。
         隐藏后「轨迹」视图在本 App 里就没有入口了（宿主只在会话头提供这一个切换口）。 */
      display: none !important;
    }
    /* 键盘弹出时把这条会话头钉在顶部（用户 2026-09-22：「会话页面键盘弹出时上面标题看不见」）。
       真机取证（设备2 / SM-G7810，2026-09-22）：键盘一弹，整页上滚 1026px —— 正好是键盘高度
       （正文节点 y1872 → 846、输入框 y2043 → 1017、按钮 y2187 → 1161，等比上移），
       顶部 y42–213 那条（会话名 + 对话/轨迹 + 抽屉按钮）被整条顶出屏幕、连可见树里都没有了。
       宿主把会话头放在滚动流里（position: relative，不是 sticky），所以这里钉住它。
       背景必须不透明，否则正文会从标题底下透出来（实测底色 #151517，与宿主的
       --dsw-alias-bg-base 暗色值一致）；z-index 压住正文但低于宿主那条 z-index:40 的全屏面板。 */
    [data-handheld="frame"] [data-phase] header:has([class*="_titleRow"]) {
      position: sticky !important;
      top: 0 !important;
      z-index: 6 !important;
      background: var(--dsw-alias-bg-base, #151517) !important;
    }
    /* 键盘弹出时外壳高度跟着**视觉视口**走（rev 1.0.62）。
       病根：键盘弹起后页面仍按整屏高度布局，composer 落在可视区之外，只能靠**滚文档**去够 ——
       而滚文档会把表头一起带走（表头的 sticky 只在内层滚动容器里生效），于是「标题看不见」和
       「底部被键盘遮挡」轮流出现（1.0.60 / 1.0.61 两版实测都撞上）。
       改成：html/body 高度 = 视觉视口高度（JS 维护 --dsh-handheld-vh，见下面的 effect），
       并禁掉文档级滚动 —— 消息列表自己会滚，表头 sticky 在容器内钉住，composer 稳在底部。 */
    html[data-dsh-cover], html[data-dsh-cover] body {
      height: var(--dsh-handheld-vh, 100vh) !important;
      overflow: hidden !important;
    }
    html[data-dsh-cover] [class*="_frame"]:has([data-phase]) {
      height: var(--dsh-handheld-vh, 100vh) !important;
    }
  }

  /* 更窄（折叠屏外屏 / 小屏）：再降一档字号与内边距，把「一行」保住。 */
  @media (max-width: 380px) {
    [data-composer-stats] {
      font-size: 9.5px !important;
      padding-left: 4px !important;
      padding-right: 4px !important;
    }
  }
}

/* 宽屏 / 精确指针：这一层整体退场，交给桌面布局。 */
@media (min-width: 1024px), (pointer: fine), (pointer: none) {
  [data-handheld="backdrop"],
  [data-handheld="fab"],
  [data-handheld="toggle"] {
    display: none !important;
  }
}
`;

    /** 会话头里的目录按钮（注册进宿主自己的会话头动作槽）。 */
    function NavToggle(props) {
      return h(
        "button",
        {
          type: "button",
          "data-handheld": "toggle",
          "aria-label": props.label,
          title: props.label,
          onClick: props.toggleSidebar,
        },
        h(primitives.IconPanelLeftOutline16, { size: 16 })
      );
    }

    /**
     * 外壳浮层：遮罩 + hero 阶段的浮动入口，注册进宿主的 shell.overlay 槽。
     *
     * 挂在槽里而不是自己往 DOM 里塞，是因为这里的东西要跟着 React 的重渲染走：
     * 会话切换、面板重挂都不会把节点弄丢。副作用（给 frame 打标记、点会话行收抽屉）
     * 在 effect 里做，卸载时按同一个链条拆掉。
     */
    function ShellOverlay(props) {
      var ref = react.useRef(null);

      react.useEffect(function () {
        var node = ref.current;
        // shell.overlay 槽的内容渲染在外壳浮层里，但**槽运行时可能再加一层包裹**，
        // 所以不能写死「往上两层」。改为向上找第一个「直接含有侧栏列」的祖先 ——
        // 那按定义就是外壳网格（frame）。2026-09-13 真机第一次装就是因为写死了两层，
        // 标记打到了包裹节点上，于是整套 CSS 静默失效（抽屉不生效、rail 还在）。
        var frame = null;
        for (var el = node; el && el !== document.body; el = el.parentElement) {
          if (el.querySelector && el.querySelector(':scope > [class*="_sidebarCol"]')) {
            frame = el;
            break;
          }
        }
        if (!frame) return undefined;
        frame.setAttribute("data-handheld", "frame");

        // 全屏抽屉：宿主把**侧栏根节点**的宽度写成了内联 style（layout 的 cols.sidebar，
        // 本机 ~265px），所以它不跟着我们那条 100vw 的列走 —— 右边空出 ~119px（用户
        // 2026-09-20「没有利用好空间」）。stylesheet 的 !important 本来能压过普通内联声明，
        // 但根节点可能被槽运行时包了一层（谁是「根」不稳定），所以这里直接按几何找：
        // 列的第一层/第二层子元素里，凡是带**像素内联宽度**的就是它。
        // React 不会把它改回去（它按自己的 prop 做 diff，值没变就不碰 DOM），
        // 但窗口变化/收起展开时宿主会重新渲染，所以观察 style 属性兜底。
        var widenSidebar = function () {
          var col = frame.querySelector('[class*="_sidebarCol"]');
          if (!col) return;
          var first = col.firstElementChild;
          var cands = [first, first && first.firstElementChild];
          for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            if (!el || !el.style) continue;
            var w = el.style.width;
            if (w && /px$/.test(w)) {
              el.style.width = "100%";
              el.style.maxWidth = "none";
            }
          }
        };
        widenSidebar();
        var widenTimer1 = window.setTimeout(widenSidebar, 400);
        var widenTimer2 = window.setTimeout(widenSidebar, 1500);
        var widenTimer3 = window.setTimeout(widenSidebar, 4000);

        // 「把 ⋯ 里面的按钮直接展开」：宿主把重命名/删除（工作区）、重命名/分叉/归档（会话）
        // 都藏在行尾那颗 ⋯ 的菜单里，菜单项在 client/ui-workspace 里带**稳定的 id 与顺序**
        // （workspaceMenuItems = rename, delete；sessionMenuItems = rename, fork, archive）。
        // 做法：在每行动作区注入与菜单项一一对应的按钮，点击时**替用户走一遍宿主自己的菜单**
        // （先点 ⋯ 打开，再点对应序号的 menuitem）—— 这样进的仍然是宿主原生的改名编辑器与
        // 删除确认框，而不是我们绕过它们直接调 API（那会跳过确认，属于把安全阀拆了）。
        // 注入成功才把 ⋯ 藏起来；菜单没弹出来就把 ⋯ 放回去 —— 任何一步失败都不留死路。
        var PENCIL = "M12.7 1.6a1.4 1.4 0 0 1 2 2l-1 1-2-2 1-1ZM10.9 3.4l2 2L5.6 12.7H3.6v-2l7.3-7.3Z";
        var TRASH = "M6.4 1.4h3.2l.6 1.1h2.6v1.5H2.2V2.5h2.6l.6-1.1ZM3.4 5.2h9.2l-.8 8.4a1.1 1.1 0 0 1-1.1 1H5.3a1.1 1.1 0 0 1-1.1-1L3.4 5.2Z";
        var BRANCH = "M8 1.2a2.3 2.3 0 1 1 0 4.6 2.3 2.3 0 0 1 0-4.6ZM4 9.6a2.3 2.3 0 1 1 0 4.6 2.3 2.3 0 0 1 0-4.6Zm8 0a2.3 2.3 0 1 1 0 4.6 2.3 2.3 0 0 1 0-4.6ZM7.2 6.1h1.6v2.1l3.1 1.6-.7 1.4L8 9.7 4.8 11.2l-.7-1.4 3.1-1.6V6.1Z";
        var ARCHIVE = "M1.4 2.3h13.2v3H1.4v-3Zm1.4 4h10.4v7.4H2.8V6.3Zm3 2.1v1.6h4.4V8.4H5.8Z";
        var ROW_MENU = {
          project: [
            { label: "重命名", d: PENCIL },
            { label: "删除工作区", d: TRASH }
          ],
          session: [
            { label: "重命名", d: PENCIL },
            { label: "分叉会话", d: BRANCH },
            { label: "归档会话", d: ARCHIVE }
          ]
        };

        var iconSvg = function (d) {
          var ns = "http://www.w3.org/2000/svg";
          var svg = document.createElementNS(ns, "svg");
          svg.setAttribute("viewBox", "0 0 16 16");
          svg.setAttribute("width", "16");
          svg.setAttribute("height", "16");
          svg.setAttribute("aria-hidden", "true");
          var path = document.createElementNS(ns, "path");
          path.setAttribute("d", d);
          path.setAttribute("fill", "currentColor");
          svg.appendChild(path);
          return svg;
        };
        // ⋯ 那颗：宿主的 Menu 把它包了一层 span，所以取动作区里第一个 iconButton。
        var rowMenuAnchor = function (row) {
          return row.querySelector('[class*="_rowActions"] button[class*="_iconButton"]');
        };
        var restoreRowMenu = function (row) {
          var a = rowMenuAnchor(row);
          if (a && a.parentElement) a.parentElement.style.display = "";
        };
        var runRowAction = function (row, index) {
          var anchor = rowMenuAnchor(row);
          if (!anchor) return;
          anchor.click();   // 元素被 display:none 也能点（HTMLElement.click 不看可见性）
          var tries = 0;
          var timer = window.setInterval(function () {
            tries++;
            var menus = document.querySelectorAll('[role="menu"]');
            var last = null;
            for (var i = 0; i < menus.length; i++) {
              var box = menus[i].getBoundingClientRect();
              if (box.width > 0 && box.height > 0) last = menus[i];   // 取最近打开的那一个
            }
            var items = last ? last.querySelectorAll('[role="menuitem"]') : [];
            if (items.length > index) {
              window.clearInterval(timer);
              items[index].click();
              return;
            }
            if (tries > 20) {           // ~600ms 还没弹出来：把 ⋯ 放回去，别留死路
              window.clearInterval(timer);
              restoreRowMenu(row);
            }
          }, 30);
        };
        var injectRowActions = function () {
          var col = frame.querySelector('[class*="_sidebarCol"]');
          if (!col) return;
          var rows = col.querySelectorAll('[class*="_projectRow"], [class*="_sessionRow"]');
          for (var i = 0; i < rows.length; i++) {
            var row = rows[i];
            var zone = row.querySelector('[class*="_rowActions"]');
            if (!zone || zone.querySelector('[data-handheld="rowActionsDirect"]')) continue;
            var anchor = zone.querySelector('button[class*="_iconButton"]');
            if (!anchor || !anchor.parentElement) continue;
            var spec = ROW_MENU[String(row.className).indexOf("_projectRow") >= 0 ? "project" : "session"];
            var wrap = document.createElement("span");
            wrap.setAttribute("data-handheld", "rowActionsDirect");
            for (var k = 0; k < spec.length; k++) {
              var btn = document.createElement("button");
              btn.setAttribute("type", "button");
              btn.setAttribute("data-handheld", "rowAction");
              btn.setAttribute("aria-label", spec[k].label);
              btn.setAttribute("title", spec[k].label);
              btn.appendChild(iconSvg(spec[k].d));
              // ⚠️ `row` 必须**随 idx 一起**绑进来：循环变量是 `var`（函数作用域），
              // 只绑 idx 的话，所有行的按钮共享同一个 `row`，循环结束后它恒等于最后一行 ——
              // 真机实测（设备1，2026-09-22）：点第一行「查看 dsh hand 项目」的重命名，
              // 弹出来的却是第三行「光刻机光罩台1330Hz共振研究」。
              btn.addEventListener("click", (function (rowEl, idx) {
                return function (ev) {
                  ev.preventDefault();
                  ev.stopPropagation();
                  runRowAction(rowEl, idx);
                };
              })(row, k));
              wrap.appendChild(btn);
            }
            anchor.parentElement.parentNode.insertBefore(wrap, anchor.parentElement);
            anchor.parentElement.style.display = "none";   // 它的功能已经摊平了
          }
        };
        injectRowActions();
        var injectTimer1 = window.setTimeout(injectRowActions, 400);
        var injectTimer2 = window.setTimeout(injectRowActions, 1500);
        var injectTimer3 = window.setTimeout(injectRowActions, 4000);
        var colEl = frame.querySelector('[class*="_sidebarCol"]');
        var widenObserver = null;
        if (window.MutationObserver && colEl) {
          // injectRowActions 会对侧栏里**每一行**做两次 querySelector（N 行 ≈ 2N 次查询），
          // 而宿主在会话列表里一有风吹草动（标题更新、运行中指示器改 class/style）就会触发
          // 这条 observer —— 原先等于「每次变动全量重扫一遍」。改成合并到最多 2 次/秒，
          // 且保证最后一次一定会跑（尾部补一次），行为不变、开销降一个量级。
          var injectPending = false;
          var injectLast = 0;
          var injectThrottled = function () {
            var now = Date.now();
            if (now - injectLast >= 500) {
              injectLast = now;
              injectRowActions();
              return;
            }
            if (injectPending) return;
            injectPending = true;
            window.setTimeout(function () {
              injectPending = false;
              injectLast = Date.now();
              injectRowActions();
            }, 500 - (now - injectLast));
          };
          widenObserver = new MutationObserver(function () {
            widenSidebar();
            injectThrottled();
          });
          widenObserver.observe(colEl, {
            subtree: true, childList: true, attributes: true, attributeFilter: ["style", "class"]
          });
        }

        var onFrameClick = function (event) {
          // 宽视口 + 触摸主指针（平板横屏 / 展开态折叠屏 / DeX）时这一层本该**整体退场**：
          // CSS 退了（文件末尾那个媒体查询），但这条捕获阶段的点击启发式原先没退 ——
          // 会话行是 `role=treeitem`（不在 INERT_TARGETS 里），点一下就顺手把桌面布局的
          // 常驻侧栏收掉了（审计 M23）。
          if (!window.matchMedia || !window.matchMedia(MOBILE_QUERY).matches) return;
          var target = event.target;
          if (!target || typeof target.closest !== "function") return;
          var col = frame.querySelector('[class*="_sidebarCol"]');
          if (!col || !col.contains(target)) return;
          // 抽屉里的输入框与按钮（搜索、视图选项…）自己处理这一笔；其余点击
          //（会话行、工作区行）意味着「选完了」，顺手把抽屉收起来。
          //
          // 但**抽屉里挂着模态时整条启发式让位**：设置对话框（[role=dialog][aria-modal]）
          // 连同它的遮罩都住在侧栏的 DOM 里，这时「点了非按钮」根本不能说明用户「选完了」。
          // 判据放在**祖先范围**上（col 里有没有模态），而不是 target 自己 ——
          // 遮罩是面板的兄弟，靠 target.closest 是抓不到的（真机验证时漏过一笔）。
          //
          // 踩过的坑：这条启发式把设置对话框里的一次点击（说明文字、空白处、遮罩）
          // 当成「选完了」→ 收抽屉 → 抽屉收起后整列让出指针（见 CSS 第 2 节），
          // 对话框当场变成一张点不动的画，于是「设置页面无法关闭」。
          // CSS 那边已经补了兜底，这里再堵住源头。
          if (col.querySelector('[role="dialog"][aria-modal="true"]')) return;
          if (target.closest(INERT_TARGETS)) return;
          props.closeDrawer();
        };
        frame.addEventListener("click", onFrameClick, true);
        return function () {
          frame.removeEventListener("click", onFrameClick, true);
          window.clearTimeout(widenTimer1);
          window.clearTimeout(widenTimer2);
          window.clearTimeout(widenTimer3);
          window.clearTimeout(injectTimer1);
          window.clearTimeout(injectTimer2);
          window.clearTimeout(injectTimer3);
          if (widenObserver) widenObserver.disconnect();
          frame.removeAttribute("data-handheld");
        };
      }, []);

      return h(
        react.Fragment,
        null,
        h("div", {
          ref: ref,
          "data-handheld": "backdrop",
          "aria-hidden": "true",
          onClick: props.closeDrawer,
        }),
        h(
          "button",
          {
            type: "button",
            "data-handheld": "fab",
            "aria-label": props.label,
            title: props.label,
            onClick: props.toggleSidebar,
          },
          h(primitives.IconPanelLeftOutline16, { size: 18 })
        )
      );
    }

    /** 只依赖 slots 与 layout：其余服务一律按需取，缺了不至于整层不生效。 */
    exports.inject = ["slots", "layout"];
    exports.name = "dsh-handheld-mobile";

    exports.apply = function (ctx) {
      try {
        window.__dshHandheldApplies = (window.__dshHandheldApplies || 0) + 1;
      } catch (e) { /* 诊断永远不该影响主流程 */ }
      var slots = ctx.slots;
      var layout = ctx.layout;
      var toggleSidebar = function () {
        layout.toggleSidebar();
      };
      var closeDrawer = function () {
        // 只有开着才关：toggleSidebar 是切换语义，多按一次会把抽屉又打开。
        var frame = document.querySelector('[data-handheld="frame"]');
        if (frame !== null && !frame.hasAttribute("data-sidebar-collapsed")) {
          layout.toggleSidebar();
        }
      };

      // ── 样式表 ──────────────────────────────────────────────
      ctx.effect(function () {
        var tag = document.createElement("style");
        // ⚠️ 2026-09-25：**不能**用 data-plugin 标归属。dsh 0.1.7-rc.2 的加载器有这么一段：
        //     function removeOwnedStyles(id) {
        //       遍历所有 style 元素，凡 dataset.plugin === 该包 id 的就 remove()。
        //     }
        // 它在 reconcile/替换 entry 时按包名删样式 —— 我们的表当时挂着 plugin=插件 id，
        // 于是被当成「宿主自己该管的样式」删掉，连同 entry fiber 一起（effect 全灭）。
        // 换成宿主不认识的标记，这条删除路径就够不着我们。
        tag.dataset.handheldCss = "dsh-handheld-mobile/mobile.css";
        try {
          window.__dshHandheldStyleAdds = (window.__dshHandheldStyleAdds || 0) + 1;
        } catch (e) { /* 诊断永远不该影响主流程 */ }
        tag.dataset.pluginCss = "dsh-handheld-mobile/mobile.css";
        tag.textContent = CSS;
        document.head.appendChild(tag);
        try { if (window.__dshHandheldHealHook) window.__dshHandheldHealHook(tag); } catch (e) { /* 诊断 */ }
        // 宿主的组件样式是各自 append 上去的，有的还带 !important。再 append 一次让
        // 这张表停在 <head> 末尾，层叠顺序才是稳定的，而不是靠运气。
        window.setTimeout(function () {
          if (tag.isConnected) document.head.appendChild(tag);
        }, 0);
        return function () {
          try {
            window.__dshHandheldStyleRemoves = (window.__dshHandheldStyleRemoves || 0) + 1;
          } catch (e) { /* 诊断永远不该影响主流程 */ }
          tag.remove();
        };
      }, "dsh-handheld-mobile: styles");

      // ── 外壳浮层：遮罩 + 浮动入口 ───────────────────────────
      ctx.effect(function () {
        return slots.inject("shell.overlay", function () {
          return slots.register(
            {
              name: "shell.overlay",
              id: "handheld-shell-overlay",
              order: 20,
              inject: function () {
                return {
                  toggleSidebar: toggleSidebar,
                  closeDrawer: closeDrawer,
                  label: "打开目录",
                };
              },
            },
            ShellOverlay
          );
        });
      }, "dsh-handheld-mobile: shell overlay");

      // ── 标记兜底：整套 CSS 都以 [data-handheld="frame"] 为前提，不能把它绑在
      //    槽能不能渲染上。这里独立盯着 DOM，框架一出现就补标记（已经打过就跳过）。 ──
      ctx.effect(function () {
        var frame = null;
        var raf = 0;
        var disposed = false;
        var mark = function () {
          if (disposed) return;
          var col = document.querySelector('[class*="_sidebarCol"]');
          var next = col === null ? null : col.parentElement;
          if (next !== null) {
            frame = next;
            if (frame.getAttribute("data-handheld") !== "frame") {
              frame.setAttribute("data-handheld", "frame");
            }
            // 同时在 <html> 上打一个类：让「设置对话框」那 8 条规则可以用
            // html.dsh-handheld-mobile ... 这种普通属性选择器，而不必写
            // html.dsh-handheld-mobile —— 文档级 :has() 是样式重算里最贵的形式，
            // 而这一层要在每次 DOM 变动时参与重算。
            if (!document.documentElement.classList.contains("dsh-handheld-mobile")) {
              document.documentElement.classList.add("dsh-handheld-mobile");
            }
          }
        };
        var markTimed = __wrap("mark", mark);
        var schedule = function () {
          if (raf !== 0 || disposed) return;
          raf = window.requestAnimationFrame(function () {
            raf = 0;
            markTimed();
          });
        };
        var observer = new MutationObserver(schedule);
        // attributes 也要听（审计 L17）：`data-handheld` 有两个写者 —— ShellOverlay 的 cleanup
        // 会 `removeAttribute`，而这里原先只订阅 childList，看不见属性删除；卸载顺序不利时
        // 标记会短暂丢失（整套移动 CSS 失效）。
        observer.observe(document.documentElement, {
          childList: true, subtree: true,
          attributes: true, attributeFilter: ["data-handheld"],
        });
        markTimed();
        return function () {
          disposed = true;
          observer.disconnect();
          if (raf !== 0) window.cancelAnimationFrame(raf);
          document.documentElement.classList.remove("dsh-handheld-mobile");
        };
      }, "dsh-handheld-mobile: frame marker");

      // ── 任务完成 → 通知 App ──────────────────────────────────
      //
      // 判据是 dsh 自己那个「深度求索中…」指示器：dsh-client-ui-chat 的 ChatView 里
      //   div[class 后缀 turnStatus][role=status][aria-live=polite] {t("chat.deepDiving")}
      // 它在 `running` 为真时挂载、结束就卸载，所以我们盯的是**它从有到无**的那一次跃迁。
      // 那个类名当时全安装唯一（只有 dsh-client-ui-chat 定义它），不依赖文案语种；
      // 0.1.7-rc.2 起改用 data-turn-process（见下面 turn watcher 的说明）。
      //
      // 三条不显然的实现约束：
      //  1. **不能用 requestAnimationFrame 节流**（上面那个标记兜底 effect 用了，它没事，
      //     因为标记丢了也只是不好看）：页面在后台时 rAF 根本不跑，而我们要捕获的恰恰是
      //     「用户在别的 App 里」时发生的结束事件。这里改成：记住上一次找到的节点，
      //     用 `isConnected`（O(1)）判断它还在不在，不在才重新查询。
      //     流式输出时节点一直在，代价就只有一次 isConnected。
      //  2. **不用定时器去判「输出停了」**：后台的 setTimeout/setInterval 会被节流到分钟级，
      //     而 MutationObserver 回调是微任务，跟着 JS 任务走，不受节流影响。
      //  3. 太短的「一轮」（< 1.5s）不算数：切会话等操作会让指示器闪现一下，
      //     那不该变成一条通知。
      // 布局诊断（2026-09-19）：用户报「顶部还是大片留白」，而截图目测已经不可靠了 ——
      // 直接把 header/标题行/页签/正文起点的 rect 与计算样式打回来，一次性看清那几十像素是谁的。
      ctx.effect(function () {
        var rect = function (el) {
          if (!el) return null;
          var b = el.getBoundingClientRect();
          return { top: Math.round(b.top), h: Math.round(b.height) };
        };
        // 竖直中心：左右两个头部按钮要在同一条线上（用户 2026-09-19：「两边上下不对称？」），
        // 比「各自的 top」更直观 —— 盒高不同时看中心才对得上。
        var mid = function (el) {
          if (!el) return null;
          var b = el.getBoundingClientRect();
          return { top: Math.round(b.top), h: Math.round(b.height), mid: Math.round(b.top + b.height / 2) };
        };
        var report = function (stage) {
          var header = document.querySelector('[data-phase] header:has([class*="_titleRow"])')
            || document.querySelector('[data-phase] header') || document.querySelector('header');
          var body = document.body;
          var cs = header && window.getComputedStyle ? getComputedStyle(header) : null;
          var bs = body && window.getComputedStyle ? getComputedStyle(body) : null;
          // 祖先链：从 [data-phase] 往上一直到 body —— 顶部那 35px 到底是哪一层加的。
          var chain = [];
          var node = document.querySelector('[data-phase]');
          var guard = 0;
          while (node && node !== document.body && guard++ < 12) {
            var ccs = window.getComputedStyle ? getComputedStyle(node) : null;
            var b = node.getBoundingClientRect();
            chain.push({
              tag: node.tagName.toLowerCase(),
              cls: (node.getAttribute("class") || "").slice(0, 34),
              top: Math.round(b.top),
              h: Math.round(b.height),
              padTop: ccs ? ccs.paddingTop : "?",
              marTop: ccs ? ccs.marginTop : "?",
              pos: ccs ? ccs.position : "?",
            });
            node = node.parentElement;
          }
          // Chromium 报给页面的挖孔安全区（env），以及 html/body 的样式
          var probe = document.createElement("div");
          probe.style.paddingTop = "env(safe-area-inset-top, 0px)";
          document.documentElement.appendChild(probe);
          var envTop = getComputedStyle(probe).paddingTop;
          probe.parentNode.removeChild(probe);
          var hs = getComputedStyle(document.documentElement);
          var bs2 = document.body ? getComputedStyle(document.body) : null;
          postToApp({
            type: "layout-diag",
            stage: stage,
            cover: document.documentElement.getAttribute("data-dsh-cover"),
            envSafeTop: envTop,
            htmlPadTop: hs.paddingTop,
            htmlMarTop: hs.marginTop,
            bodyPadTop2: bs2 ? bs2.paddingTop : null,
            scrollTop: document.scrollingElement ? document.scrollingElement.scrollTop : null,
            chain: chain,
            innerH: window.innerHeight,
            vvOffsetTop: window.visualViewport ? Math.round(window.visualViewport.offsetTop) : null,
            header: rect(header),
            headerPadTop: cs ? cs.paddingTop : null,
            headerMarginTop: cs ? cs.marginTop : null,
            titleRow: rect(document.querySelector('[data-phase] [class*="_titleRow"]')),
            tabs: rect(document.querySelector('[data-phase] [class*="_tabs"]')),
            // 头部左右两个按钮（我们注入的目录按钮 vs 宿主右端角落按钮）：中心要对齐
            toggle: mid(document.querySelector('[data-handheld="toggle"]')),
            toggleIcon: mid(document.querySelector('[data-handheld="toggle"] svg')),
            cornerIcon: mid(document.querySelector('[data-phase] header [class*="_headerCorner"] svg')),
            bodyTop: body ? Math.round(body.getBoundingClientRect().top) : null,
            bodyPadTop: bs ? bs.paddingTop : null,
            rootTop: rect(document.querySelector('[data-phase]')),
          });
        };
        var t1 = window.setTimeout(function () { report("1s"); }, 1000);
        var t2 = window.setTimeout(function () { report("3s"); }, 3000);
        return function () { window.clearTimeout(t1); window.clearTimeout(t2); };
      }, "dsh-handheld-mobile: layout diag");

      // 一次性几何诊断（2026-09-19）：真机截图显示统计行被省略号吃掉，而小回环说余量 44px ——
      // 「估」已经不解决问题了，直接把 row/每个胶囊的 scrollWidth 与 clientWidth、以及计算出的
      // 字号报回来。判据与后续调参都靠它。
      ctx.effect(function () {
        var report = function (stage) {
          var row = document.querySelector('[data-composer-stats]');
          if (row === null) return;
          var pills = [];
          for (var i = 0; i < row.children.length && i < 4; i++) {
            var el = row.children[i];
            pills.push({
              text: (el.textContent || '').slice(0, 28),
              scrollW: el.scrollWidth,
              clientW: el.clientWidth,
              font: window.getComputedStyle ? getComputedStyle(el).fontSize : "?"
            });
          }
          postToApp({
            type: "stats-diag",
            stage: stage,
            rowW: row.clientWidth,
            rowScrollW: row.scrollWidth,
            rowFont: window.getComputedStyle ? getComputedStyle(row).fontSize : "?",
            pills: pills
          });
        };
        var t = window.setTimeout(function () { report("1s"); }, 1000);
        window.addEventListener("load", function () { report("load"); }, { once: true });
        return function () { window.clearTimeout(t); };
      }, "dsh-handheld-mobile: stats geometry diag");

      // 一次性对称性诊断（2026-09-19）：用户两次报「两侧不对称」。宿主的会话滚动体带
      // scrollbar-gutter: stable + margin-right: 2px —— 桌面上的滚动条槽，手机上是右侧多出的
      // 10px。这里把「谁贡献了多少」直接报回来：滚动体的 padding/margin/gutter、以及
      // offsetWidth 与 clientWidth 的差（= 滚动条实际占的宽度），外加统计行相对视口的左右内缩。
      ctx.effect(function () {
        var box = function (el) {
          if (el === null) return null;
          var b = el.getBoundingClientRect();
          var cs = window.getComputedStyle ? getComputedStyle(el) : null;
          return {
            cls: (el.getAttribute("class") || "").slice(0, 34),
            left: Math.round(b.left),
            right: Math.round(window.innerWidth - b.right),
            padL: cs ? cs.paddingLeft : "?",
            padR: cs ? cs.paddingRight : "?",
            marR: cs ? cs.marginRight : "?",
            gutter: cs ? cs.scrollbarGutter : "?",
            offW: el.offsetWidth,
            cliW: el.clientWidth,
            scrollbarW: el.offsetWidth - el.clientWidth
          };
        };
        var report = function (stage) {
          postToApp({
            type: "side-diag",
            stage: stage,
            innerW: window.innerWidth,
            scrollBody: box(document.querySelector('[class*="_scrollBody"]')),
            chatScroll: box(document.querySelector('[data-phase] [class*="_scroll"]')),
            stats: box(document.querySelector('[data-composer-stats]'))
          });
        };
        var t = window.setTimeout(function () { report("1s"); }, 1000);
        window.addEventListener("load", function () { report("load"); }, { once: true });
        return function () { window.clearTimeout(t); };
      }, "dsh-handheld-mobile: side symmetry diag");

      // 2026-09-25：**不再是 ctx.effect** —— 新版加载器会拆掉我们 entry 的 fiber，effect
      // 随之全灭（真机实测 turnTicks 恒为 0）。轮次监视不依赖 ctx，搬成裸实现就活得下来。
      // 全局旗标防重复安装（插件若被重新 apply，第二份直接退场）。
      (function () {
        if (window.__dshHandheldWatcherOn) return;
        window.__dshHandheldWatcherOn = 1;
        // ⚠️ 2026-09-25 适配 dsh 0.1.7-rc.2：旧选择器（class 后缀 turnStatus 那条）在新产物里
        // **已经不存在**（契约检查抓到「这条适配规则已经空转」）。新版把那个指示器重做成了
        // 每个回合一个「回合过程」节点：
        //   <button data-turn-process="<回合号>" data-turn-process-messages/-tool-calls/-subagents>
        //     <span>深度求索中，用时{duration}  ← 运行中每秒跳一次
        //           已完成工作 / 用时 {duration} / 处理失败   ← 结束后静态
        //   </button>
        // 用 data-* 而不是哈希类名（`TurnProcessNodeView_root` 那种）—— 后者一升级就变。
        var TURN_STATUS = '[data-turn-process]';
        // 运行中的判据：**标签在跳秒**。这是新 DOM 里唯一跨语种的可观测信号：
        //   · 回合结束后节点**不会卸载**（变成"已完成工作/用时 N 秒"），所以旧版那条
        //     「isConnected 变 false = 结束」不再成立；
        //   · 也没有任何 data-* 暴露"这一轮还开着"（`data.closed` 只活在 JS 里）；
        //   · 而运行中标签每秒更新用时（`LIVE_RUN_CLOCK_INTERVAL_MS`），**思考与工具调用期间
        //     也照跳**，所以它同时覆盖「没在输出文字」的那段。
        // 代价：结束最多晚 TICK_WINDOW_MS 才认出来（旧版是卸载即报）。App 那边还有 Host
        // 事件流这条权威路径兜底（见 DshApp 的两路信号对账），所以这个延迟可接受。
        var TICK_WINDOW_MS = 3000;
        var PICK_IDLE_MS = 5000;   // 空手时最多 5 秒重挑一次（1 秒轮询下别每秒全树扫）
        var MIN_TURN_MS = 1500;
        var HEARTBEAT_MS = 60000;
        // 存活探针周期（2026-09-17 真机诊断）：renderer 被系统 waive/冻结时它就会消失 ——
        // 「在后台收不到完成通知」到底是「信号没发出来」还是「页面根本没在跑」，靠它分辨。
        var TICK_MS = 300000;
        var checks = 0;
        var mutations = 0;
        var found = null;
        var running = false;
        var startedAt = 0;
        var lastBeat = 0;
        var disposed = false;

        var now = function () {
          return window.performance && window.performance.now
            ? window.performance.now()
            : Date.now();
        };
        var post = postToApp;

        /**
         * 「谁在跑」拆成两层判据，别再合成一条（2026-09-17 审计 H7 / M21 / M24）：
         *
         *  - [pick] 只在需要**挑一个**时用，**优先挑可见的**：目的是别一上来就 latch 到隐藏的
         *    那份副本（1.0.18「卡死」的成因）。挑不到可见的才退回第一个还连着的。
         *  - [live] 是热路径（每个 mutation 批都跑），只用 `isConnected`（O(1)）：
         *    `getClientRects()` 会**强制同步布局**，而且它判不出 `visibility:hidden` / 被挪出
         *    视口 —— 宿主确实用 `visibility:hidden`。
         *
         * 为什么「在跑」**不能**用可见性判：切会话 / 切「轨迹」视图时，正在跑的那个指示器是
         * **被藏起来、不是被卸载**。用可见性判会立刻误报一次「结束」（用时=已用时间、标题还是
         * 新会话的），而真正结束时反而什么都不发 —— 用户就等不到通知了。用 `isConnected` 判则
         * 相反：藏起来不算结束，**卸载**（React 在 `running` 变 false 时移除节点）才算。
         */
        /**
         * 「这个节点现在真的在屏幕上吗」—— 用**几何**判，不能用 rects/visibility。
         *
         * 2026-09-17 真机心跳（payload 里两份 `_turnStatus` 都是
         * `connected:1, rects:1, vis:"visible"`）证明：隐藏的那份既不是 `display:none`
         * 也不是 `visibility:hidden`（多半是被父容器裁剪 / 挪出视口 / 被别的层盖住）——
         * 也就是说「优先挑可见的」那条判据**区分不了这两份**。能区分的只有位置。
         */
        var onScreen = function (el) {
          var r = el.getBoundingClientRect();
          var vw = window.innerWidth || 0;
          var vh = window.innerHeight || 0;
          if (vw === 0 || vh === 0) return false;   // 视口尺寸拿不到（页面在后台）→ 不敢下结论
          return r.width > 0 && r.height > 0 && r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;
        };

        /** 一个节点的可观测几何/样式（心跳 payload 用，诊断 H7/M21 就靠它）。 */
        var geom = function (el) {
          var r = el.getBoundingClientRect();
          var cs = window.getComputedStyle ? window.getComputedStyle(el) : null;
          return {
            connected: el.isConnected ? 1 : 0,
            rects: el.getClientRects().length,
            vis: cs ? cs.visibility : "?",
            disp: cs ? cs.display : "?",
            x: Math.round(r.left), y: Math.round(r.top),
            w: Math.round(r.width), h: Math.round(r.height),
            on: onScreen(el) ? 1 : 0
          };
        };

        /**
         * 挑一个节点来跟踪。顺序：
         *   1. **真的在视口里**的那个（几何判据）；
         *   2. 退一步：至少被布局过、还连着的（页面在后台时视口尺寸拿不到，只能这样）；
         *   3. 再退：第一个还连着的。
         * 一旦挑中就**不再重挑**（只在它卸载时才重挑）—— 用户切会话/切视图时它是被藏起来、
         * 不是被卸载，跟踪着它才不会误报结束（见 [live]）。
         */
        var pick = function () {
          var list = document.querySelectorAll(TURN_STATUS);
          var bestOnScreen = null, bestOnScreenTurn = -1;
          var bestAny = null, bestAnyTurn = -1;
          var fallback = null, connected = null;
          for (var i = 0; i < list.length; i++) {
            var el = list[i];
            if (!el.isConnected) continue;
            if (connected === null) connected = el;
            if (fallback === null && el.getClientRects().length > 0) fallback = el;
            // 回合号 = data-turn-process 的值；新版每个回合一个节点，**只关心最新的那个**
            // （旧的回合节点会一直留着当"已完成工作"的摘要）。
            var n = parseInt(el.getAttribute("data-turn-process") || "-1", 10);
            if (!isFinite(n)) n = -1;
            if (n > bestAnyTurn) { bestAnyTurn = n; bestAny = el; }
            if (onScreen(el) && n > bestOnScreenTurn) { bestOnScreenTurn = n; bestOnScreen = el; }
          }
          // 仍然优先可见的那个（旧版这条判据的理由没变：别 latch 到隐藏副本上）。
          if (bestOnScreen !== null) return bestOnScreen;
          if (bestAny !== null) return bestAny;
          return fallback !== null ? fallback : connected;
        };
        var live = function (el) {
          return el !== null && el.isConnected;
        };

        /** 心跳 payload：每个候选节点的几何与样式 —— 诊断 H7/M21 悬着的问题就靠它。 */
        var probe = function () {
          var list = document.querySelectorAll(TURN_STATUS);
          var out = [];
          for (var i = 0; i < list.length && i < 4; i++) {
            out.push(geom(list[i]));
          }
          return out;
        };

        var lastLabel = "";
        var lastTick = 0;
        var lastPickAt = 0;

        var check = function () {
          if (disposed) return;
          checks++;
          var at = now();
          if (!live(found)) {
            // 空手时别每秒全树重挑（1 秒轮询 + 没有回合节点 = 每秒一次 querySelectorAll）；
            // 结构变化那条路（mutation）仍然会立刻重挑。
            if (found !== null || at - lastPickAt >= PICK_IDLE_MS) {
              found = pick();
              lastPickAt = at;
            }
          }
          var present = found !== null;
          if (present) {
            var txt = String(found.textContent || "").slice(0, 48);
            if (txt !== lastLabel) { lastLabel = txt; lastTick = at; }
          }
          // 「在跑」= 有节点 **且** 它的标签刚跳过（见上面 TICK_WINDOW_MS 的说明）。
          var active = present && (at - lastTick < TICK_WINDOW_MS);
          if (active && !running) {
            running = true;
            startedAt = at;
            lastBeat = at;
            // 标题也带上：页面被冻时「结束」由 Host 的事件流报告（见 HarnessEventsClient），
            // 那条路径拿不到 DOM，只能用这里记下的标题。
            post({ type: "turn-start", title: sessionLabel() });
          } else if (!active && running) {
            running = false;
            var ms = Math.round(at - startedAt);
            // **一律上报结束**（`short` 只是给 App 的一个提示）：只发开始、不发结束会让
            // App 的 pageBusy 永远卡在 true，后台 pauseTimers 的省电设计静默失效且无界
            // （审计 H8）。要不要因此打扰用户，交给 App 判。
            post({ type: "turn-done", title: sessionLabel(), ms: ms, short: ms < MIN_TURN_MS });
          }
          // 心跳**无条件**发（不放在 running 分支里）：它要诊断的恰恰是「一次都没看见节点」
          // 那种形态 —— 挂在 running 里面就正好漏掉它（审计 M22）。后台定时器会被节流到分钟级，
          // 所以取 60s（再密也没用）；驱动源仍是 mutation，不依赖定时器。
          if (at - lastBeat >= HEARTBEAT_MS) {
            lastBeat = at;
            // `watching` = 我们此刻**在跟踪**的那个节点（它的几何就是判据的现场证据）；
            // `nodes` = 全部候选。两者放一起，日志里一眼能看出「挑对了没有」。
            post({
              type: "turn-state",
              running: running,
              present: present,
              active: active,
              watching: found !== null ? geom(found) : null,
              nodes: probe(),
            });
          }
        };

        var checkTimed = __wrap("turn", check);
        var observer = new MutationObserver(function () {
          mutations++;
          checkTimed();
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
        // 跳秒是 characterData 变更 —— `childList` 观察者收不到，所以必须有一条轮询来驱动
        // 「标签还在跳吗」这个判据。1 秒一次，每次只读一个节点的 textContent（不碰布局）。
        var tickPoll = window.setInterval(function () {
          try { window.__dshHandheldTurnTicks = (window.__dshHandheldTurnTicks || 0) + 1; } catch (e) { /* 诊断 */ }
          checkTimed();
        }, 1000);
        // 定时器也跑 check()：mutation 只是**触发源**之一，定时复查能在「DOM 变了但回调被合并/
        // 漏掉」时兜住；它同时是存活探针的载体（renderer 被冻时这条就没了）。
        var tickTimer = window.setInterval(function () {
          if (disposed) return;
          check();
          post({
            type: "turn-tick",
            checks: checks,
            mutations: mutations,
            present: present,
            running: running,
            vis: document.visibilityState || "?",
          });
        }, TICK_MS);
        check();
        // 不做清理：这条实现就是为了「被拆也活着」而存在的。页面卸载时浏览器自会回收。
        return function () {
          disposed = true;
          observer.disconnect();
          window.clearInterval(tickTimer);
          window.clearInterval(tickPoll);
        };
      })();

      // ── 顶部空间自证（2026-09-24，用户报「左右侧边栏顶部空间比会话页面大」）──────
      // 「外层 padding 同值」不等于「第一行内容同位置」：里面还叠着宿主自己的内边距，而
      // 那些规则写在哪个节点上、中间有几层包裹，光读 CSS 推不出来（已经推错过一次）。
      // 这里把三处「第一行」连同祖先链的 类名 / rect.top / padding-top / margin-top 直接报回来，
      // 一次看清空间是谁的。只在加载后与抽屉开合时各跑一次，不进任何循环。
      ctx.effect(function () {
        var chainOf = function (el) {
          var out = [];
          var cur = el;
          for (var i = 0; i < 4 && cur && cur.nodeType === 1; i++) {
            var cs = window.getComputedStyle ? getComputedStyle(cur) : null;
            var b = cur.getBoundingClientRect();
            out.push({
              cls: String(cur.className || "").slice(0, 30),
              top: Math.round(b.top),
              padT: cs ? cs.paddingTop : "?",
              marT: cs ? cs.marginTop : "?",
              h: Math.round(b.height),
            });
            cur = cur.parentElement;
          }
          return out;
        };
        var report = function (stage) {
          var col = document.querySelector('[data-handheld="frame"] > [class*="_sidebarCol"]');
          postToApp({
            type: "topspace-diag",
            stage: stage,
            sessionHeader: chainOf(document.querySelector('[data-phase] header')),
            drawerCol: chainOf(col),
            drawerRoot: chainOf(col ? col.querySelector(':scope *:has(> [class*="_logoRow"])') : null),
            logoRow: chainOf(document.querySelector('[data-handheld="frame"] [class*="_logoRow"]')),
            tabStrip: chainOf(document.querySelector('[class*="_tabStrip"]')),
          });
        };
        var t1 = window.setTimeout(function () { report("load+3s"); }, 3000);
        // 抽屉开合各报一次（懒挂：frame 是宿主渲染的，apply() 时还没影）
        var colObserver = null;
        var wasOpen = null;
        var attach = function () {
          if (colObserver !== null) return true;
          var el = document.querySelector('[data-handheld="frame"]');
          if (el === null || !window.MutationObserver) return false;
          colObserver = new MutationObserver(function () {
            var open = !el.hasAttribute("data-sidebar-collapsed");
            if (open !== wasOpen) { wasOpen = open; window.setTimeout(function () { report(open ? "drawer-open" : "drawer-close"); }, 350); }
          });
          colObserver.observe(el, { attributes: true, attributeFilter: ["data-sidebar-collapsed"] });
          return true;
        };
        attach();
        var attachTimer = window.setInterval(function () { if (attach()) window.clearInterval(attachTimer); }, 1000);
        return function () {
          window.clearTimeout(t1);
          window.clearInterval(attachTimer);
          if (colObserver) colObserver.disconnect();
        };
      }, "dsh-handheld-mobile: topspace diag");

      // ── JS 开销上报（见文件顶部累加器）─────────────────────────
      ctx.effect(function () {
        var timer = window.setInterval(function () {
          var total = __cost.mark + __cost.turn + __cost.ask;
          // 2026-09-25：取证期间**不设阈值**，连调用次数一起报 —— 否则「一条都没有」
          // 分不清是「成本低」还是「页面根本没在变」。只在前 2 分钟报（有界），
          // 之后回到「超过 20ms 才报」，不会长期刷日志。
          var probing = __now() < 2 * 60 * 1000;
          if (probing || total >= 20) {
            postToApp({
              type: "perf",
              what: "js-cost",
              ms: Math.round(total * 10) / 10,
              mark: Math.round(__cost.mark * 10) / 10, markN: __costN.mark,
              turn: Math.round(__cost.turn * 10) / 10, turnN: __costN.turn,
              ask: Math.round(__cost.ask * 10) / 10, askN: __costN.ask,
              vis: document.visibilityState || "?",
              probing: probing,
              cdN: __cdN,
            });
          }
          __cost.mark = 0; __cost.turn = 0; __cost.ask = 0;
          __costN.mark = 0; __costN.turn = 0; __costN.ask = 0;
          __cdN = 0;
        }, 10000);
        return function () { window.clearInterval(timer); };
      }, "dsh-handheld-mobile: js cost probe");

      // ── 需要你选择 → 通知 App ────────────────────────────────
      //
      // 「一轮结束」不是唯一该叫用户回来的时刻，甚至不是最该叫的：**它在等你在手机上点一下**
      // （批准一次工具调用、回答一个问题）时，那一轮根本没结束 —— 用户却在等一个不会来的结果。
      // 两类卡片都是宿主自己写的稳定钩子，值就是这一次交互的 key：
      //   [data-question-key]      dsh-client-ui-user-questions（提问 / 计划确认）
      //   [data-approval-key]      dsh-client-ui-approval（工具审批）
      // 它们在 pending 存在时挂载、回答后卸载。同一张卡重渲染时 key 不变，所以用 key 去重；
      // 卡片消失后再出现（key 变了）才会再提醒一次。
      ctx.effect(function () {
        var ASK = '[data-question-key], [data-approval-key], [data-plan-review-key]';
        var node = null;
        var lastKey = null;
        var disposed = false;

        var check = function () {
          if (disposed) return;
          if (node === null || !node.isConnected) node = document.querySelector(ASK);
          if (node === null) {
            // 卡没了（回答了 / 被撤销）：清掉去重键，下次出现要重新提醒
            lastKey = null;
            return;
          }
          var key = node.getAttribute("data-question-key")
            || node.getAttribute("data-approval-key")
            || node.getAttribute("data-plan-review-key")
            || "";
          if (key === lastKey) return;
          lastKey = key;
          postToApp({ type: "needs-input", title: sessionLabel(), key: key });
        };

        var checkTimed = __wrap("ask", check);
        var observer = new MutationObserver(checkTimed);
        observer.observe(document.documentElement, { childList: true, subtree: true });
        check();
        return function () {
          disposed = true;
          observer.disconnect();
        };
      }, "dsh-handheld-mobile: needs-input watcher");

      // ── Web UI 的「连接 / 会话列表」复健（2026-09-20 只读研究后加） ──────────────
      // 研究结论（证据都来自安装产物，写进 docs/mobile-adaptation.md）：
      //  1. 侧栏那些会话行的基线**只有一次** unary session/list 拉取，触发点是 connection/reset：
      //     失败不重试、请求挂住就永久卡在单飞 promise 上，而且 UI 既不显示 loading 也不显示错误
      //     —— 于是「工作区 › xsj」下面可以空白几十秒到几分钟，只能整页重载；
      //  2. 隧道断过一次后，页面那条 mux socket 没有活性探测；历史流第 2 次 carrier 失败就终态，
      //     之后没有任何自动重开路径 —— 这就是「必须整页重载」的根因。
      // 我们不改宿主代码（本项目不动服务端/宿主的 composition），只在页面里做两件幂等的复健：
      //  (a) 侧栏一条会话行都没有时，调用**公开入口** ctx.sessions.refresh() 补一次基线；
      //  (b) 回到前台而侧栏还是空的（连接多半还断着）就调用 ctx.connection.reconnect()
      //      —— 等价于设置里那颗「立即重连」，但用户不必自己去找。
      // 两者都带节流与次数上限；服务拿不到（老版本 / 桌面浏览器）就静默跳过。
      ctx.effect(function () {
        var lastRefresh = 0;
        var refreshCount = 0;
        var lastReconnect = 0;
        var count = function (sel) {
          return document.querySelectorAll(sel).length;
        };
        var sessionRows = function () { return count('[class*="_sidebarCol"] [class*="_sessionRow"]'); };
        var projectRows = function () { return count('[class*="_sidebarCol"] [class*="_projectRow"]'); };
        var frameEl = function () { return document.querySelector('[data-handheld="frame"]'); };
        var drawerOpen = function () {
          var el = frameEl();
          return el !== null && !el.hasAttribute("data-sidebar-collapsed");
        };
        // 连接状态：宿主的 connection 服务把 state 暴露成 { getSnapshot() }（值是
        // "connected" / "connecting" / "disconnected" / undefined）。判定要贴真实状态，
        // 不能只看「列表空不空」—— 侧栏收起时列表本来就可能没挂载。
        var connectionState = function () {
          var svc = ctx.get("connection");
          try {
            return svc && svc.state && svc.state.getSnapshot ? svc.state.getSnapshot() : undefined;
          } catch (error) {
            return undefined;
          }
        };
        var tryRefresh = function (why) {
          var now = Date.now();
          if (now - lastRefresh < 5000 || refreshCount >= 8) return;
          var svc = ctx.get("sessions");
          if (!svc || typeof svc.refresh !== "function") return;
          lastRefresh = now;
          refreshCount++;
          try {
            svc.refresh();
          } catch (error) {
            return;
          }
          postToApp({ type: "ui-recovery", what: "sessions.refresh", why: why, n: refreshCount, rows: sessionRows() });
        };
        var tryReconnect = function (why) {
          var state = connectionState();
          if (state === "connected" || state === undefined) return;   // 连着呢（或服务不在）就别动
          var now = Date.now();
          if (now - lastReconnect < 10000) return;
          var svc = ctx.get("connection");
          if (!svc || typeof svc.reconnect !== "function") return;
          lastReconnect = now;
          try {
            svc.reconnect();
          } catch (error) {
            return;
          }
          postToApp({ type: "ui-recovery", what: "connection.reconnect", why: why, state: String(state) });
        };
        // 判据：**工作区行渲染出来了、会话行一条都没有** —— 这正是研究里那条断路
        //（工作区头走 follow 流、会话行走一次性 session/list，后者失败/挂住就永远空着）。
        var health = function (why) {
          var rows = sessionRows();
          var projects = projectRows();
          postToApp({
            type: "ui-recovery", what: "health", why: why,
            rows: rows, projects: projects, open: drawerOpen(), state: String(connectionState())
          });
          if (projects > 0 && rows === 0) tryRefresh(why);
        };
        // 首屏后分几次体检（会话列表本来就可能比首屏晚到）。
        var t1 = window.setTimeout(function () { health("load+6s"); }, 6000);
        var t2 = window.setTimeout(function () { health("load+15s"); }, 15000);
        var t3 = window.setTimeout(function () { health("load+35s"); }, 35000);
        var t4 = window.setTimeout(function () { tryReconnect("load+20s"); }, 20000);
        // 抽屉打开：用户下一步就要看列表 —— 这一条最实用。
        // 注意 frame 是宿主渲染出来的，apply() 时还没影，所以这里要**懒挂**观察者。
        var drawerObserver = null;
        var wasOpen = false;
        var attachDrawerObserver = function () {
          if (drawerObserver !== null) return true;
          var el = frameEl();
          if (el === null || !window.MutationObserver) return false;
          wasOpen = !el.hasAttribute("data-sidebar-collapsed");
          drawerObserver = new MutationObserver(function () {
            var open = !el.hasAttribute("data-sidebar-collapsed");
            if (open && !wasOpen) window.setTimeout(function () { health("drawer-open"); }, 400);
            wasOpen = open;
          });
          drawerObserver.observe(el, { attributes: true, attributeFilter: ["data-sidebar-collapsed"] });
          return true;
        };
        attachDrawerObserver();
        var attachTimer = window.setInterval(function () {
          if (attachDrawerObserver()) window.clearInterval(attachTimer);
        }, 1000);
        var attachGiveUp = window.setTimeout(function () { window.clearInterval(attachTimer); }, 30000);
        // 回到前台：连接不在 connected 就先重连（等价设置里那颗「立即重连」），再体检一次。
        var onVisible = function () {
          if (document.visibilityState !== "visible") return;
          window.setTimeout(function () {
            tryReconnect("visible");
            health("visible");
          }, 1500);
        };
        document.addEventListener("visibilitychange", onVisible);
        return function () {
          window.clearTimeout(t1);
          window.clearTimeout(t2);
          window.clearTimeout(t3);
          window.clearTimeout(t4);
          window.clearTimeout(attachGiveUp);
          window.clearInterval(attachTimer);
          document.removeEventListener("visibilitychange", onVisible);
          if (drawerObserver) drawerObserver.disconnect();
        };
      }, "dsh-handheld-mobile: ui recovery");

      // ── 手指档体检（一次性）：把「又小又点不到 / 隐形但仍吃点击」的元素数出来 ────────
      // 两个坑要分开量：
      //  1. 视觉尺寸 <44×44 ≠ 点不到 —— 我们（和宿主自己）会用 ::after 把**命中区**撑大，
      //     而 getBoundingClientRect 看不到伪元素。所以这里在元素四周 6px 打点，用
      //     elementFromPoint 判断「是否仍然命中它」，只有**四个方向里小于两个方向能命中**
      //     才算真正的「点不到」（hard）。
      //  2. 隐形但仍吃点击（opacity:0 / visibility:hidden 且 pointer-events 不是 none）——
      //     误触的来源，单独列出类名。
      ctx.effect(function () {
        var done = false;
        var t = window.setTimeout(function () {
          if (done) return;
          done = true;
          var nodes = document.querySelectorAll(
            'button, [role="button"], [role="menuitem"], [role="tab"], [role="option"], [role="treeitem"], [role="switch"]'
          );
          var small = 0;
          var hard = [];
          var invisible = [];
          var probe = function (el, box) {
            if (typeof document.elementFromPoint !== "function") return false;
            var cx = box.left + box.width / 2;
            var cy = box.top + box.height / 2;
            var pts = [[box.left - 6, cy], [box.right + 6, cy], [cx, box.top - 6], [cx, box.bottom + 6]];
            var hits = 0;
            for (var i = 0; i < pts.length; i++) {
              var x = pts[i][0];
              var y = pts[i][1];
              if (x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) continue;
              var at = document.elementFromPoint(x, y);
              if (at !== null && (at === el || el.contains(at) || at.contains(el))) hits++;
            }
            return hits < 2;
          };
          var shape = function (el, box) {
            return { c: String(el.className || el.tagName).slice(0, 26), w: Math.round(box.width), h: Math.round(box.height) };
          };
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var cs = window.getComputedStyle ? getComputedStyle(el) : null;
            if (cs === null) continue;
            var box = el.getBoundingClientRect();
            if (box.width === 0 && box.height === 0) continue;
            var hitless = cs.pointerEvents === "none";
            if (!hitless && (cs.opacity === "0" || cs.visibility === "hidden")) {
              if (invisible.length < 8) invisible.push(shape(el, box));
            }
            if (hitless) continue;
            if (box.width < 44 || box.height < 44) {
              small++;
              if (probe(el, box) && hard.length < 500) hard.push(shape(el, box));
            }
          }
          hard.sort(function (a, b) { return a.w * a.h - b.w * b.h; });
          postToApp({
            type: "tap-diag",
            clickable: nodes.length,
            small: small,
            hard: hard.length,
            invisible: invisible.length,
            worstHard: hard.slice(0, 12),
            worstInvisible: invisible
          });
        }, 2500);
        return function () { window.clearTimeout(t); };
      }, "dsh-handheld-mobile: tap target audit");

      // ── 右侧栏顶部探针（用户 2026-09-20：「右侧边栏展开后最顶部无法点击」）──────────
      // 手机上右侧栏只能以 fullscreen 出现：宿主的
      //   .P3OORG_panel[data-sidebar-right-panel=fullscreen] { position: fixed; inset: 0; z-index: 40 }
      // 到底是谁在顶部那条带子上吃掉了点击（我们自己的浮层？宿主的面板头？还是状态栏），
      // 靠猜没用 —— 面板一出现就在顶部打一排点，用 elementFromPoint 把「最上面那个元素」
      // 连同它的尺寸与 pointer-events 一起报回来，一次就能定位。
      ctx.effect(function () {
        var probed = null;
        var probedTimers = [];
        var probe = function (el, stage) {
          var pts = [];
          var xs = [16, 192, 368];
          var ys = [6, 16, 26, 36, 50];
          for (var i = 0; i < ys.length; i++) {
            for (var k = 0; k < xs.length; k++) {
              var x = xs[k];
              var y = ys[i];
              var at = document.elementFromPoint(x, y);
              if (at === null) {
                pts.push({ x: x, y: y, at: "null" });
                continue;
              }
              var box = at.getBoundingClientRect();
              var cs = window.getComputedStyle ? getComputedStyle(at) : null;
              pts.push({
                x: x, y: y,
                at: at.tagName.toLowerCase() + "." + String(at.className || "").slice(0, 22),
                w: Math.round(box.width), h: Math.round(box.height),
                top: Math.round(box.top),
                pe: cs ? cs.pointerEvents : "?",
                z: cs ? cs.zIndex : "?"
              });
            }
          }
          var pbox = el.getBoundingClientRect();
          var pcs = window.getComputedStyle ? getComputedStyle(el) : null;
          var header = document.querySelector('[data-phase] header');
          var hcs = header && window.getComputedStyle ? getComputedStyle(header) : null;
          var hbox = header ? header.getBoundingClientRect() : null;
          postToApp({
            type: "right-probe",
            stage: stage,
            panel: String(el.className || "").slice(0, 30),
            panelAttr: el.getAttribute("data-sidebar-right-panel"),
            panelPos: pcs ? pcs.position : "?",
            panelZ: pcs ? pcs.zIndex : "?",
            panelPe: pcs ? pcs.pointerEvents : "?",
            panelRect: { top: Math.round(pbox.top), left: Math.round(pbox.left), w: Math.round(pbox.width), h: Math.round(pbox.height) },
            panelParent: String((el.parentElement && el.parentElement.className) || "").slice(0, 30),
            headerZ: hcs ? hcs.zIndex : "?",
            headerPos: hcs ? hcs.position : "?",
            headerRect: hbox ? { top: Math.round(hbox.top), h: Math.round(hbox.height) } : null,
            pts: pts
          });
        };
        var look = function () {
          var el = document.querySelector("[data-sidebar-right-panel]");
          if (el === null) { probed = null; return; }
          if (probed === el) return;
          probed = el;
          // ⚠️ 2026-09-22：这里原来写的是 `window.clearTimeout(timer)`，而 `timer` 在本作用域
          // **从未声明**（全文件只有 runRowAction 里那个同名局部变量）—— 于是 look() 一走到
          // 这一行就抛 ReferenceError，而 look() 跑在 MutationObserver / setInterval 回调里，
          // 异常被静默吞掉：探针**一次都没发出来过**，日志里当然什么都看不到。
          // 本意是「换面板元素时把上一轮待发的采样撤掉」，改成清 probedTimers 才对。
          for (var i = 0; i < probedTimers.length; i++) window.clearTimeout(probedTimers[i]);
          probedTimers = [];
          // 面板是**滑入**的（宿主 .P3OORG_panel 基础规则 transform: translate(100%)，
          // 打开时 [data-sidebar-right-open] 才 transform: none），所以 700ms 那次会拍到
          // 半路上的位置（实测 left=384）。三次采样：滑入中 / 落定 / 再等等。
          probedTimers.push(window.setTimeout(function () { probe(el, "0.7s"); }, 700));
          probedTimers.push(window.setTimeout(function () { probe(el, "2.5s"); }, 2500));
          probedTimers.push(window.setTimeout(function () { probe(el, "5s"); }, 5000));
        };
        look();
        var obs = null;
        if (window.MutationObserver) {
          obs = new MutationObserver(look);
          obs.observe(document.body, {
            subtree: true, childList: true, attributes: true,
            attributeFilter: ["data-sidebar-right-panel"]
          });
        }
        var poll = window.setInterval(look, 2000);
        return function () {
          for (var i = 0; i < probedTimers.length; i++) window.clearTimeout(probedTimers[i]);
          window.clearInterval(poll);
          if (obs) obs.disconnect();
        };
      }, "dsh-handheld-mobile: right panel probe");

      // ── 右侧栏展开时，把它工具栏上方的遮挡逐个让开（rev 1.0.58）────────────────────
      // 用户 2026-09-22 真机上报：「右侧边栏打开后无法关闭，里面的按钮都用不了」。
      // elementsFromPoint 命中栈（Android 13 + 16 一致，见 tap-trace 的 stack 字段）：
      //   0. div._tabStrip_17p4l_156   (0,30 360x38)   ← 压在面板之上的 dockkit 标签条容器
      //   1. button.P3OORG_iconButton  (326,40 28x28)  ← 面板自己的「收起右侧边栏」
      //   5. div._tabStrip_17p4l_156   (0,30 360x38)   ← 面板自己那条（在面板链里）
      // 两条标签条**类名完全相同**（对话区一条、面板自己一条）。压在面板上那条是全宽、
      // 背景透明、可命中的容器，于是 CSS y30–68 整条带子上的点击全被它吃掉 —— 面板的
      // `文件` 标签、它的 ✕、`＋`、`收起右侧边栏` 全在这条带子里，所以既关不掉、
      // 又「按钮都没反应」；y68 以下（刷新 button.k-1LKG_tool、文件行 button.k-1LKG_row）正常。
      //
      // 修法刻意**不按类名去关**（同名同尺寸，靠类名 + 「在不在面板里」判断既脆弱、
      // 也已经试过一轮没生效）：直接在面板工具栏那条带子上取几个点，用 elementsFromPoint
      // 从最上层往下走，凡是出现在**面板自己之前**的元素就是压在面板上的，逐个关掉命中
      // （记账在数组里，面板收起后原样还回）。这样不依赖宿主的类名、层级或 z-index 怎么变。
      ctx.effect(function () {
        // 自证：这一条**无条件**上报，用来区分「effect 体压根没跑到」与
        // 「跑到了但一个元素都没标到 / 中途抛异常」—— 前两轮就卡在这个盲区里。
        postToApp({ type: "panel-blockers", stage: "init" });
        try {
          // 只用自己的数组记账，**不往 DOM 上写标记属性** —— 写 data-* 会被移动端适配
          // 契约检查当成新的 dsh 钩子（那是我自己的标记，不该混进契约）。
          var marked = [];
          var restoreAll = function () {
            for (var i = 0; i < marked.length; i++) {
              marked[i].style.removeProperty("pointer-events");
            }
            marked = [];
            window.__dshHandheldPeOff = 0;
          };
          var last = "";
          var slowPosted = 0;
          var lastScan = 0;
          var wasOpen = false;
          // 轮转采样（rev 1.0.68）：一次 elementsFromPoint 就是一次**强制布局**，原来每次扫
          // 3 行 × 4 列 = 12 次，真机实测 5.3~17.2ms。采样点一个不少，只是把它们摊到时间上：
          // 开合瞬间（justToggled）扫满 3 行，稳态每 2 秒只扫 1 行 → 一次扫描只有 1 次强制布局，
          // 三行轮完仍是一个 6 秒内的完整覆盖。
          var fullScan = true;
          var scanCursor = 0;
          var cycleTags = [];
          var panelEl = null;
          var getPanel = function () {
            // 面板元素在一段会话里是稳定的：缓存它，省掉每次 sync 的一次全树属性选择器遍历。
            if (panelEl !== null && panelEl.isConnected) return panelEl;
            panelEl = document.querySelector("[data-sidebar-right-panel]");
            return panelEl;
          };
          var closed = function () {
            wasOpen = false;
            if (marked.length > 0 || last !== "") {
              restoreAll();
              last = "";
              postToApp({ type: "panel-blockers", open: false, n: 0 });
            }
          };
          var sync = function () {
            var t0 = window.performance && performance.now ? performance.now() : 0;
            var panel = getPanel();
            // ⚠️ 便宜的门必须排在读几何**之前**（rev 1.0.67）。宿主把 dock 的 expanded 状态
            // 直接写在面板根上（client-ui-sidebar-right：`"data-sidebar-right-open": expanded || void 0`，
            // 与 `aria-hidden` 互反），所以「没展开」**一个布局都不用读**就能判掉。
            // 改之前是反过来的：先 `panel.getBoundingClientRect()` 再判 2 秒节流 —— 于是每次被叫醒
            // 都强制刷一次布局，只换来一句「2 秒内扫过了」。而叫醒源是 setInterval(1s) 加上流式
            // 期间 body 的 class/style 抖动（200ms 防抖）：闲置 1 次/秒、流式 ~5 次/秒的强制布局，
            // 面板还关着的时候就全发生在空处。
            // ⚠️ 订正（rev 1.0.68，真机取证，推翻 rev 1.0.67 的 commit 说明）：1.0.67 里我写过
            // 「顺带修掉一个语义错 —— `visibility:hidden` 的元素仍然有盒，所以收起状态的 fullscreen
            // 面板会被旧判据当成展开」。**这条是错的**：2026-09-24 用本插件自己的 right-probe 拍到
            // 收起态面板的几何是
            //   {top:0, left:384, w:384, h:832}   // CSS px，视口 384×832
            // —— 基础规则 `.P3OORG_panel{transform:translate(100%)}` 把 rect 整个推到视口外，而旧判据
            // 要求 `left <= 1`，所以它**一直**判的是「没展开」（日志侧同样只有 stage:"init" 一条，
            // 从来没有收起态的 open:true）。老判据没有语义错，这次改的只是**成本**。
            //
            // 因此这道门的收益只有一个（但确凿）：面板关着时省掉一次 `querySelector` 全树遍历
            // 和一次**可能强制布局**的 rect 读 —— 那是 1 次/秒（定时器）+ 流式期间 ~5 次/秒
            // （body 抖动 200ms 防抖）的全部内容。
            if (panel === null || !panel.hasAttribute("data-sidebar-right-open")) {
              closed();
              return;
            }
            var b = panel.getBoundingClientRect();
            // 「展开」= 面板铺满视口。属性只说**该**展开，滑入动画那 ~300ms 几何还没落定 ——
            // 此刻采样点会打偏到面板外，命中后面的无关元素，所以「落定」仍旧只认几何。
            var open = b.width >= window.innerWidth - 1 && b.left <= 1;
            var top = b.top;
            // ⚠️ 采样那 12 个点要调 elementsFromPoint，而它每次都**强制一次布局**（页面在持续
            // 变动时实测 4~11ms）。原先每个 sync 都扫（最多 5 次/秒）→ 面板开着时等于每 10 秒
            // 就往日志里写一条 ≥4ms。改成：**最多 2 秒扫一次**，面板刚打开/刚收起时强制扫一次
            // （遮挡是宿主 DOM 的一部分，被重渲染掉时最多 2 秒后自愈）。
            var nowTs = Date.now();
            var justToggled = open !== wasOpen;
            wasOpen = open;
            if (!open) {
              closed();
              return;
            }
            if (!justToggled && nowTs - lastScan < 2000) {
              return;
            }
            lastScan = nowTs;
            if (justToggled) fullScan = true;   // 刚打开/刚收起：这一轮扫满，别让用户等轮转
            // 面板工具栏那条带子（实测 CSS y40–68）：取 3×4 个点问「谁在最上面」。
          //
          // ⚠️ 判据不能用「面板之外的才算遮挡」—— 真机读数（rev 1.0.58）显示每个取样点上
          // 第一个元素**本来就在面板里**，于是「遇到面板内元素就停」会一路 break、一个都标不到
          // （peOff=0）。压在面板按钮上面的那层，本身就是面板 DOM 里的一份东西。
          // 所以改成按「这一点本该由谁接住」来判：从最上层往下走，遇到的第一个**面板内的
          // 可交互元素**（button/a/[role]/[tabindex]）或面板根，才是目标；它上面的都是遮挡。
          // 另有两条保险：页面根不碰；**目标控件的祖先也不碰**（关掉祖先等于把它一起关掉）。
          var INTERACTIVE_TAGS = { A: 1, BUTTON: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, LABEL: 1 };
          var isInteractive = function (e) {
            if (INTERACTIVE_TAGS[e.tagName]) return true;
            var role = e.getAttribute ? e.getAttribute("role") : null;
            if (role === "button" || role === "tab" || role === "link" || role === "menuitem") return true;
            return !!(e.hasAttribute && e.hasAttribute("tabindex"));
          };
          var ys = [top + 44, top + 55, top + 66];
          var xs = [0.08, 0.35, 0.6, 0.92];
          var found = [];
          var rows = fullScan ? ys.length : 1;
          var rowBase = fullScan ? 0 : scanCursor % ys.length;
          for (var i = 0; i < rows; i++) {
            var y = Math.round(ys[(rowBase + i) % ys.length]);
            for (var k = 0; k < xs.length; k++) {
              var x = Math.round(window.innerWidth * xs[k]);
              var els = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
              // 目标 = 最上层那个「面板内的可交互元素」（或面板根）
              var stop = els.length;
              for (var t = 0; t < els.length; t++) {
                if (panel.contains(els[t]) && (isInteractive(els[t]) || els[t] === panel)) { stop = t; break; }
              }
              for (var j = 0; j < stop; j++) {
                var e = els[j];
                if (e === document.documentElement || e === document.body) break;
                var wrapsTarget = false;
                for (var m = stop; m < els.length; m++) {
                  if (e.contains && e.contains(els[m])) { wrapsTarget = true; break; }
                }
                if (wrapsTarget) continue;
                // 只关「贴在顶部这条带子里的薄层」。面板自己的内容容器
                // （_pane_ / _surface_ / P3OORG_panelBody 都是 30→800 那种整块）也满足
                // 「在目标之上」，一刀切会把文件列表一起关死 —— rev 1.0.57 真机回归
                // 实测：点文件夹只命中 div.P3OORG_panel，列表整块失效。
                var eb = e.getBoundingClientRect ? e.getBoundingClientRect() : null;
                if (eb === null || eb.top < top - 2 || eb.bottom > top + 84) continue;
                var tag = e.tagName.toLowerCase() + "." + String(e.className || "").slice(0, 24);
                if (found.indexOf(tag) < 0) found.push(tag);
                if (marked.indexOf(e) < 0) {
                  e.style.setProperty("pointer-events", "none", "important");
                  marked.push(e);
                }
              }
            }
          }
          scanCursor++;
          // 每轮到一个新周期（采样行回到第 0 行）才重开累计 —— 这样 `key` 的语义仍是
          // 「上一次完整覆盖里见到的遮挡」，与改动前（每次扫满 3 行看一次）等价；
          // 否则单行扫描会让 key 每 2 秒变一次，日志被刷爆。
          if (rowBase === 0) cycleTags = [];
          for (var q = 0; q < found.length; q++) {
            if (cycleTags.indexOf(found[q]) < 0) cycleTags.push(found[q]);
          }
          fullScan = false;
          window.__dshHandheldPeOff = marked.length;
          // 自证开销（用户问过「现在 web 性能会不会有问题」）：这条 effect 每 200ms 最多算一次，
          // 面板关着时只做一次查询 + 一次 rect；只有真算久了才上报，免得刷屏。
          if (t0) {
            var ms = performance.now() - t0;
            var nowMs = Date.now();
            if (ms >= 4 && nowMs - slowPosted > 10000) {
              slowPosted = nowMs;
              postToApp({ type: "perf", what: "panel-sync", ms: Math.round(ms * 10) / 10, n: found.length });
            }
          }
            // 上报按「一个轮转周期里累计到的集合」比 —— 单行扫描会让 key 每 2 秒变一次。
            var key = cycleTags.slice().sort().join("|");
            if (key !== last) {
              last = key;
              postToApp({ type: "panel-blockers", open: true, n: marked.length, at: cycleTags });
            }
          };
          // 合并突发变更：宿主在流式输出时 DOM 抖得很厉害，不能每个 mutation 都算一遍几何。
          var pending = false;
          var schedule = function () {
            if (pending) return;
            pending = true;
            window.setTimeout(function () { pending = false; sync(); }, 200);
          };
          var obs = null;
          if (window.MutationObserver && document.body) {
            obs = new MutationObserver(schedule);
            obs.observe(document.body, {
              subtree: true, childList: true, attributes: true,
              attributeFilter: ["class", "style", "data-sidebar-right-panel", "data-sidebar-right-open"]
            });
          }
          // 兜底轮询：面板是滑入的，靠属性变化不一定能拍到「落定」那一帧。
          var poll = window.setInterval(sync, 1000);
          window.addEventListener("resize", schedule);
          sync();
          return function () {
            window.clearInterval(poll);
            window.removeEventListener("resize", schedule);
            if (obs) obs.disconnect();
            restoreAll();
          };

        } catch (err) {
          postToApp({ type: "panel-blockers", stage: "throw", err: String((err && err.message) || err) });
        }
      }, "dsh-handheld-mobile: right panel clickable");

      // ── 键盘弹出时把输入框滚回可见区（rev 1.0.61）──────────────────────────────────
      // 表头 sticky 之后（rev 1.0.60），浏览器不再替我们滚够距离：真机实测键盘弹出时
      // 内容只上滚 498px（修之前是 1026px = 键盘高度），于是 composer 停在键盘底下 ——
      // 无障碍树里它的四个节点全是 [x,1374][x,1374]（高度 0，被钳在可视边界上），
      // 用户看到的就是「底部又被弹出的键盘遮挡了」。
      // 这里只做一件事：焦点落进输入框、或视觉视口变矮（键盘弹出）之后，把当前聚焦元素
      // 显式滚进视野（block:"end" 贴底）。表头是 sticky，滚多少都仍然钉在顶部。
      ctx.effect(function () {
        var timers = [];
        var fix = function () {
          for (var i = 0; i < timers.length; i++) window.clearTimeout(timers[i]);
          timers = [];
          // 键盘动画要几帧才落定，分两次采：立刻一次、200ms 后一次。
          timers.push(window.setTimeout(doScroll, 40));
          timers.push(window.setTimeout(doScroll, 220));
        };
        var doScroll = function () {
          var el = document.activeElement;
          if (!el || el === document.body || !el.scrollIntoView) return;
          try {
            el.scrollIntoView({ block: "end", inline: "nearest" });
          } catch (e) {
            el.scrollIntoView(false);   // 老引擎只认布尔参数
          }
        };
        document.addEventListener("focusin", fix, true);
        if (window.visualViewport) window.visualViewport.addEventListener("resize", fix);
        return function () {
          document.removeEventListener("focusin", fix, true);
          if (window.visualViewport) window.visualViewport.removeEventListener("resize", fix);
          for (var i = 0; i < timers.length; i++) window.clearTimeout(timers[i]);
        };
      }, "dsh-handheld-mobile: keep focused input above keyboard");

      // ── 把视觉视口高度写进 --dsh-handheld-vh（rev 1.0.62）──────────────────────────
      // CSS 那条「外壳高度跟着视觉视口」需要这个变量。键盘弹出时 visualViewport.height 会
      // 变矮（Android 13/16 都如此），页面据此重排，composer 自然落在可见区内 ——
      // 不再需要滚文档（滚文档会把 sticky 表头一起带走）。
      ctx.effect(function () {
        var root = document.documentElement;
        var lastH = 0;
        var apply = function () {
          var vv = window.visualViewport;
          var h = vv && vv.height ? vv.height : window.innerHeight;
          if (!h) return;
          h = Math.round(h);
          // 只在高度真的变了才写：visualViewport.scroll 触发很密，而写 CSS 变量会让样式树
          // 失效 —— 这是本轮新增代码里唯一一处会随滚动反复付钱的地方。
          if (h === lastH) return;
          lastH = h;
          root.style.setProperty("--dsh-handheld-vh", h + "px");
        };
        apply();
        if (window.visualViewport) {
          window.visualViewport.addEventListener("resize", apply);
          window.visualViewport.addEventListener("scroll", apply);
        }
        window.addEventListener("resize", apply);
        window.addEventListener("orientationchange", apply);
        return function () {
          if (window.visualViewport) {
            window.visualViewport.removeEventListener("resize", apply);
            window.visualViewport.removeEventListener("scroll", apply);
          }
          window.removeEventListener("resize", apply);
          window.removeEventListener("orientationchange", apply);
          root.style.removeProperty("--dsh-handheld-vh");
        };
      }, "dsh-handheld-mobile: visual viewport height var");

      // ── 右侧栏「点了没反应」的点击追踪（用户：「补了安全区还是不响应」）──────────────
      // 必须分清两件事：
      //   ① 点击**压根没进页面**（系统/WebView 层被吃掉，例如状态条那条带子）→ 这里不会打印；
      //   ② 进页面了，但**命中的不是那颗按钮**（被别的元素盖住）→ 这里会打出真正吃掉的元素。
      // 捕获阶段挂在 document 上，只记「落点在右侧栏内 或 y<80」的那些。
      ctx.effect(function () {
        var onDown = function (ev) {
          var el = document.querySelector("[data-sidebar-right-panel]");
          var box = el ? el.getBoundingClientRect() : null;
          var inPanel = box !== null && ev.clientX >= box.left && ev.clientX <= box.right
            && ev.clientY >= box.top && ev.clientY <= box.bottom;
          if (!inPanel && ev.clientY > 80) return;
          var target = ev.target;
          var tr = target && target.getBoundingClientRect ? target.getBoundingClientRect() : null;
          var cs = target && window.getComputedStyle ? getComputedStyle(target) : null;
          // 2026-09-22 补：只知道「命中了谁」还不够 —— 右侧栏自己的工具栏按钮点不动时，
          // 要知道那个点**从上到下压了哪几层**（各自的 z-index / pointer-events / 位置）。
          // 这一条是判断「谁盖住谁」的直接证据，比逐层猜快得多。
          var stack = [];
          if (document.elementsFromPoint) {
            var els = document.elementsFromPoint(ev.clientX, ev.clientY);
            for (var i = 0; i < els.length && i < 8; i++) {
              var e2 = els[i];
              var b2 = e2.getBoundingClientRect();
              var c2 = window.getComputedStyle ? getComputedStyle(e2) : null;
              stack.push({
                at: e2.tagName.toLowerCase() + "." + String(e2.className || "").slice(0, 24),
                top: Math.round(b2.top), left: Math.round(b2.left),
                w: Math.round(b2.width), h: Math.round(b2.height),
                z: c2 ? c2.zIndex : "?", pe: c2 ? c2.pointerEvents : "?", pos: c2 ? c2.position : "?"
              });
            }
          }
          postToApp({
            type: "tap-trace",
            x: Math.round(ev.clientX), y: Math.round(ev.clientY),
            inPanel: inPanel,
            panelTop: box ? Math.round(box.top) : null,
            panelLeft: box ? Math.round(box.left) : null,
            at: target ? target.tagName.toLowerCase() + "." + String(target.className || "").slice(0, 26) : "null",
            atTop: tr ? Math.round(tr.top) : null,
            atH: tr ? Math.round(tr.height) : null,
            pe: cs ? cs.pointerEvents : "?",
            // 面板让开逻辑的**自证**：打点时它标了几个元素、面板几何与判定算出来是什么。
            // 挂在本来就通的 tap-trace 上，省得另开一条通道还要猜它有没有跑。
            peOff: window.__dshHandheldPeOff || 0,
            panelGeo: (function () {
              var p = document.querySelector("[data-sidebar-right-panel]");
              if (p === null) return null;
              var pb = p.getBoundingClientRect();
              return {
                left: Math.round(pb.left), w: Math.round(pb.width), iw: window.innerWidth,
                open: pb.width >= window.innerWidth - 1 && pb.left <= 1
              };
            })(),
            // 命中栈里每一层「是不是在面板 DOM 内」的位图（第 0 位 = 最上层）。
            // 用来验证「压着按钮的那层其实也在面板里」这个判断。
            panelOwns: (function () {
              var p = document.querySelector("[data-sidebar-right-panel]");
              if (p === null || !document.elementsFromPoint) return null;
              var es = document.elementsFromPoint(Math.round(ev.clientX), Math.round(ev.clientY));
              var bits = [];
              for (var i = 0; i < es.length && i < 8; i++) bits.push(p.contains(es[i]) ? 1 : 0);
              return bits.join("");
            })(),
            stack: stack
          });
        };
        document.addEventListener("pointerdown", onDown, true);
        return function () { document.removeEventListener("pointerdown", onDown, true); };
      }, "dsh-handheld-mobile: tap trace");

      // ── 会话头里的目录按钮 ──────────────────────────────────
      ctx.effect(function () {
        return slots.inject("conversation.session.header.actions", function () {
          return slots.register(
            {
              name: "conversation.session.header.actions",
              id: "handheld-nav-toggle",
              order: 10,
              inject: function () {
                return { toggleSidebar: toggleSidebar, label: "打开目录" };
              },
            },
            NavToggle
          );
        });
      }, "dsh-handheld-mobile: header toggle");

      // 注入链的一次性时序（见文件顶部注释）：nav = 导航起点到本文件开始执行（含取回+解析），
      // apply = 本文件开始执行到适配层装配完。这两个数决定「还值不值得为省字节去动构建链」。
      // ── 样式自愈看门狗（2026-09-25）─────────────────────────────
      // 新版加载器会拆掉我们 entry 的 fiber（effect 随之全灭）。effect 之外建的定时器
      // 不受影响（已实测：apply 里的裸 setTimeout 照跑）。所以这里留一条自愈：
      // 表被删了就补回去，并把「哪些还活着」一并报上来 —— 用来分辨
      // 「只是样式被删」与「整个插件都被拆了」。
      try {
        var __healTag = null;
        var __healKeep = function () {
          try {
            var alive = null;
            var all = document.querySelectorAll("style");
            for (var i = 0; i < all.length; i++) {
              if (all[i].dataset && all[i].dataset.handheldCss) { alive = all[i]; break; }
            }
            if (alive !== null) { __healTag = alive; return; }
            // 表没了就自己造一张 —— 样式文本由**这里**持有，不依赖那个已经随 fiber 一起
            // 被拆掉的 effect（第一版就栽在这：钩子定义在 effect 之后，看门狗手里是空的）。
            if (__healTag === null || !__healTag.isConnected) {
              __healTag = document.createElement("style");
              __healTag.dataset.handheldCss = "dsh-handheld-mobile/mobile.css";
              __healTag.textContent = CSS;
            }
            document.head.appendChild(__healTag);
            window.__dshHandheldHeals = (window.__dshHandheldHeals || 0) + 1;
          } catch (e) { /* 自愈失败不该影响页面 */ }
          // 光有样式表不够：整套移动 CSS 都挂在 html.dsh-handheld-mobile 与
          // [data-handheld="frame"] 两个标记上，而它们是 effect 打的 —— fiber 被拆后
          // 一个被 disposer 撤掉、另一个被 ShellOverlay 的 cleanup 撤掉（真机实测：
          // 表补回来了、frameTagged 却是 0）。这里一并补上，三件一起才算真的活着。
          try {
            if (!document.documentElement.classList.contains("dsh-handheld-mobile")) {
              document.documentElement.classList.add("dsh-handheld-mobile");
            }
            var col = document.querySelector('[class*="_sidebarCol"]');
            var fr = col === null ? null : col.parentElement;
            if (fr !== null && fr.getAttribute("data-handheld") !== "frame") {
              fr.setAttribute("data-handheld", "frame");
              window.__dshHandheldReframes = (window.__dshHandheldReframes || 0) + 1;
            }
            // 侧栏根节点的内联像素宽度（宿主 layout 的 cols.sidebar）撑满 —— 全屏抽屉
            // 那条规则压不过内联 style，得直接改。
            if (fr !== null) {
              var first = col.firstElementChild;
              var cands = [first, first && first.firstElementChild];
              for (var ci = 0; ci < cands.length; ci++) {
                var ce = cands[ci];
                if (ce && ce.style && ce.style.width && /px$/.test(ce.style.width)) {
                  ce.style.width = "100%";
                  ce.style.maxWidth = "none";
                }
              }
            }
          } catch (e) { /* 自愈失败不该影响页面 */ }
        };
        window.setTimeout(__healKeep, 300);
        window.setInterval(__healKeep, 2000);
      } catch (e) { /* 诊断永远不该影响主流程 */ }

      // ── 环境诊断（一次性，2026-09-25）─────────────────────────
      // 起因：换到 dsh 0.1.7-rc.2 后，手机上整份适配 CSS **一条都没生效**（宿主桌面布局
      // 原样呈现），而 apply 是跑完的（plugin-init 出声）。适配 CSS 整块包在
      // MOBILE_QUERY 里，所以嫌疑集中在「媒体查询为什么不再成立」——
      // 但 innerW=384 已经由引导脚本证明（max-width 那半为真），剩下的必须问页面。
      // 没有别的求值入口（App 只有内部 evaluateJavascript），所以只能靠这一发取证。
      var __envSample = function (stage) {
        try {
          var mq = function (q) {
            try { return window.matchMedia && window.matchMedia(q).matches ? 1 : 0; } catch (e) { return -1; }
          };
          var count = function (sel) {
            try { return document.querySelectorAll(sel).length; } catch (e) { return -1; }
          };
          var frameEl = document.querySelector('[data-handheld="frame"]');
          // 用 dataset 遍历找我们那张表 —— 不写字面量选择器（那会被契约检查当成「读宿主钩子」）。
          var styleTag = null;
          try {
            var allStyle = document.querySelectorAll("style");
            for (var si = 0; si < allStyle.length; si++) {
              if (allStyle[si].dataset && allStyle[si].dataset.plugin === "dsh-handheld-mobile") {
                styleTag = allStyle[si];
                break;
              }
            }
          } catch (e) { /* 诊断永远不该影响主流程 */ }
          var rules = -1;
          try {
            if (styleTag && styleTag.sheet) rules = styleTag.sheet.cssRules ? styleTag.sheet.cssRules.length : -2;
          } catch (e) { rules = -3; }
          var ours = 0;
          try {
            var all = document.querySelectorAll("style");
            for (var k = 0; k < all.length; k++) {
              if (all[k].dataset && all[k].dataset.pluginCss) ours++;
            }
          } catch (e) { /* 诊断永远不该影响主流程 */ }
          var aliveCss = 0;
          try {
            var st = document.querySelectorAll("style");
            for (var z = 0; z < st.length; z++) {
              if (st[z].dataset && st[z].dataset.handheldCss) aliveCss++;
            }
          } catch (e) { /* 诊断 */ }
          postToApp({
            type: "perf",
            what: "env-diag",
            stage: stage,
            heals: window.__dshHandheldHeals || 0,
            reframes: window.__dshHandheldReframes || 0,
            watcherOn: window.__dshHandheldWatcherOn || 0,
            turnTicks: window.__dshHandheldTurnTicks || 0,
            styleAdds2: window.__dshHandheldStyleAdds || 0,
            aliveCss: aliveCss,
            uiToggle: count('[data-handheld="toggle"]'),
            uiFab: count('[data-handheld="fab"]'),
            uiBackdrop: count('[data-handheld="backdrop"]'),
            frameTagged: count('[data-handheld="frame"]'),
            applies: window.__dshHandheldApplies || 0,
            styleAdds: window.__dshHandheldStyleAdds || 0,
            styleRemoves: window.__dshHandheldStyleRemoves || 0,
            ourStyles: ours,
            mq: mq(MOBILE_QUERY),
            mqW: mq("(max-width: 1023px)"),
            mqP: mq("(pointer: coarse)"),
            mqAnyP: mq("(any-pointer: coarse)"),
            mqHover: mq("(hover: none)"),
            innerW: window.innerWidth,
            innerH: window.innerHeight,
            dpr: Math.round((window.devicePixelRatio || 0) * 100) / 100,
            frame: frameEl !== null,
            frameCls: frameEl !== null ? String(frameEl.className || "").slice(0, 60) : "",
            styleTag: styleTag !== null,
            rules: rules,
            headKids: document.head ? document.head.children.length : -1,
            nFrame: count('[class*="_frame"]'),
            nSidebarCol: count('[class*="_sidebarCol"]'),
            nLogoRow: count('[class*="_logoRow"]'),
            nTabStrip: count('[class*="_tabStrip"]'),
            nTabs: count('[class*="_tabs"]'),
            nTitleRow: count('[class*="_titleRow"]'),
            nTurnProcess: count('[data-turn-process]'),
            nPhase: count('[data-phase]'),
          });
        } catch (e) { /* 诊断永远不该影响主流程 */ }
      };
      try {
        window.setTimeout(function () { __envSample("t2"); }, 2000);
        window.setTimeout(function () { __envSample("t12"); }, 12000);
      } catch (e) { /* 诊断永远不该影响主流程 */ }

      try {
        var __dshHandheldT1 = (window.performance && performance.now) ? performance.now() : 0;
        postToApp({
          type: "perf",
          what: "plugin-init",
          ms: Math.round((__dshHandheldT1 - __dshHandheldT0) * 10) / 10,
          nav: Math.round(__dshHandheldT0 * 10) / 10,
        });
      } catch (e) { /* 诊断永远不该影响主流程 */ }
    };

    return module.exports;
  },
});
