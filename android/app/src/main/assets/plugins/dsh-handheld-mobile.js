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
window.__ModuleLoader__.load({
  id: "dsh-handheld-mobile",
  factory: (require) => {
    var react = require("react");
    var primitives = require("@deepseek-ai/dsh-client-ui-primitives");
    var module = { exports: {} };
    var exports = module.exports;

    var h = react.createElement;

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
    /** 抽屉宽：窄手机 86vw，最宽 340px（再宽在平板上也不像个抽屉了）。 */
    var DRAWER_W = "min(86vw, 340px)";

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

  /* ---------- 2. 侧栏 = 左抽屉 ----------
     开合状态直接读宿主写的 data-sidebar-collapsed（窄屏下它等价于 !narrowExpanded），
     所以点宿主的任何入口、或窗口尺寸变化，抽屉都跟着走。 */
  [data-handheld="frame"] > [class*="_sidebarCol"] {
    position: fixed !important;
    top: 0;
    bottom: 0;
    left: 0;
    width: ${DRAWER_W} !important;
    max-width: 86vw;
    z-index: 40;
    /* 关：整列推到屏幕外。
       刻意用 left 而不是 transform —— transform（以及 will-change: transform）会让
       这一列成为 position:fixed 后代的**包含块**，而设置对话框恰恰渲染在侧栏里
       （SettingsRoot 注册进 sidebar.settings 槽，是个 position:fixed 浮层）。
       踩过的后果：对话框被缩进抽屉的坐标系 —— 只有抽屉那么宽、贴着屏幕左边
       （真机实测 329px vs 视口 384px），里面的桌面两栏布局被压成一字一行。 */
    left: calc(-1 * (min(86vw, 340px) + 12px));
    transition: left .22s cubic-bezier(.2, .7, .3, 1);
    /* 刘海与手势条：抽屉自己吃安全区，里面的内容不必各自处理 */
    padding-top: env(safe-area-inset-top, 0px);
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
  }
  /* ……但抽屉里只要挂着模态，整列就必须照旧吃指针。
     设置对话框是**视口级**浮层（fixed + inset:0，盖满整屏），逻辑上却住在侧栏里
     （SettingsRoot 注册进 sidebar.settings 槽）：抽屉一收，上面那条 pointer-events:none
     会连同它一起冻住 —— 对话框还在屏幕上，却变成一张点不动的画，点击直接穿透到
     背后的页面（真机实测：点 × 不关、点导航不切分区、点对话框外的输入框反而把软键盘
     调起来）。pointer-events 是继承属性，在这一层要回来，里面的遮罩与面板自动跟随。
     判据只用 [role=dialog][aria-modal]：不依赖任何包裹层结构，宿主换一层包裹也成立。 */
  [data-handheld="frame"][data-sidebar-collapsed]:has([role="dialog"][aria-modal="true"]) > [class*="_sidebarCol"] {
    pointer-events: auto;
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
    left: 8px !important;
    top: 12px !important;
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
    padding-left: 20px !important;
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
     (a) 工作区标题行那颗 +（dsh 自己的按钮，aria-label = workspace.add）：它开的目录
     选择器由**宿主**决定，本部署里 directory-picker-auto 判成 native，对话框开在电脑
     桌面上，手机上按下去什么都不会发生。要让它可用就得在宿主侧把交互钉成 -browse ——
     本项目不动服务端 composition，所以这个入口直接去掉。
     两个 aria-label 是 dsh 自己词典里的 zh / en 值；换第三种语言时它会重新出现
     （只是多一个按了没反应的入口，不会坏）。

     (b) 会话头右上角的「在 XXX 中打开工作目录」（dsh-client-ui-open-in-app 的分屏按钮）：
     它是**在电脑上**用某个已安装的程序打开当前会话的工作目录 —— 宿主探测到的是
     filemanager（GET /open-in-app/apps 返回 {"apps":["filemanager"]}），点击调
     /open-in-app/open，效果是**电脑桌面**上弹出文件管理器窗口，手机上什么都不会发生。
     同一个理由去掉。判据用类名而不是 aria-label：这个按钮的类名带模块哈希
     （_split / _main / _chevron），本 dsh 的样式语料里只有三处 _split，另两处
     （轨迹表、交付物卡片）都不在会话头里，所以 header [class*="_split"] 能精确命中它。
     注意：上面这段注释里**不能出现反引号** —— CSS 整段在 JS 模板字符串内，
     反引号会提前终止字符串（踩过两次，node --check 会以 SyntaxError 报出来）。 */
  [aria-label="添加工作区"],
  [aria-label="Add workspace"] {
    display: none !important;
  }
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
     作用域挂在 html:has([data-handheld="frame"]) 而不是 frame 上：这个对话框是 fixed
     浮层，可能被渲染到 frame 之外（portal），挂在 frame 上会漏掉。

     面板四周留 32px（每边 16px = 本机 60 设备像素 ≈ 4.2mm），这一圈就是「点外面关掉」
     那个遮罩条。原来每边 8px：真机复验时我得精确点到 x=4 CSS px 才点到 —— 8px ≈ 2.1mm，
     手指根本不可能（触摸目标惯例 ≥ 4mm）。留白加大后 ✕ 与 BACK 仍是主要关闭路径
     （见 docs/mobile-adaptation.md），「点外面」只是顺手也能关。 */
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"]:has(> nav) {
    width: calc(100vw - 32px) !important;
    max-width: calc(100vw - 32px) !important;
    height: calc(100dvh - 32px) !important;
    max-height: calc(100dvh - 32px) !important;
    border-radius: 20px !important;
    flex-direction: column !important;
  }
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"] > nav {
    flex: none !important;
    width: auto !important;
    padding: 8px 10px 4px !important;
    overflow: visible !important;
  }
  /* 「设置」那行标题在窄屏不占位（网格自己说明是什么） */
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"] > nav > :first-child {
    display: none !important;
  }
  /* 4 个分区 = 2x2 网格：全部可见、一次点击、不横滑、不折行 */
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"] > nav > [class*="_navList"] {
    display: grid !important;
    grid-template-columns: 1fr 1fr !important;
    gap: 4px !important;
    min-width: 0;
  }
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"] > nav [class*="_navCell"] {
    flex: none !important;
    width: 100% !important;
    height: 34px !important;
    padding: 6px 10px !important;
    white-space: nowrap;
  }
  /* 内容列铺满，内边距收紧 */
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"] > [class*="_content"] {
    flex: 1 1 auto !important;
    min-height: 0 !important;
  }
  html:has([data-handheld="frame"]) [role="dialog"][aria-modal="true"] [class*="_options"] {
    padding: 0 14px 18px !important;
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

     改法：左右各 12px、两个胶囊分列两端（space-between 把余量吃掉，而不是堆在中间）、
     字号 11.5px、分隔符左右 2px；装不下时**换行**，不再用省略号。

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
      padding-left: 12px !important;
      padding-right: 12px !important;
      justify-content: space-between !important;
      gap: 4px !important;
      row-gap: 2px !important;
      flex-wrap: wrap !important;
      font-size: 11.5px !important;
    }
    /* 分隔符「·」宿主给了左右各 6px；窄屏收到 2px —— 两个胶囊各省 8px，共 16px 余量 */
    [data-composer-stats] [class*="_sep"] {
      margin: 0 2px !important;
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
        tag.dataset.plugin = "dsh-handheld-mobile";
        tag.dataset.pluginCss = "dsh-handheld-mobile/mobile.css";
        tag.textContent = CSS;
        document.head.appendChild(tag);
        // 宿主的组件样式是各自 append 上去的，有的还带 !important。再 append 一次让
        // 这张表停在 <head> 末尾，层叠顺序才是稳定的，而不是靠运气。
        window.setTimeout(function () {
          if (tag.isConnected) document.head.appendChild(tag);
        }, 0);
        return function () {
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
          }
        };
        var schedule = function () {
          if (raf !== 0 || disposed) return;
          raf = window.requestAnimationFrame(function () {
            raf = 0;
            mark();
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
        mark();
        return function () {
          disposed = true;
          observer.disconnect();
          if (raf !== 0) window.cancelAnimationFrame(raf);
        };
      }, "dsh-handheld-mobile: frame marker");

      // ── 任务完成 → 通知 App ──────────────────────────────────
      //
      // 判据是 dsh 自己那个「深度求索中…」指示器：dsh-client-ui-chat 的 ChatView 里
      //   div[class*="_turnStatus"][role=status][aria-live=polite] {t("chat.deepDiving")}
      // 它在 `running` 为真时挂载、结束就卸载，所以我们盯的是**它从有到无**的那一次跃迁。
      // 类名 `_turnStatus` 全安装唯一（只有 dsh-client-ui-chat 定义它），不依赖文案语种。
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
      ctx.effect(function () {
        var TURN_STATUS = '[class*="_turnStatus"]';
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
          var fallback = null;
          var connected = null;
          for (var i = 0; i < list.length; i++) {
            var el = list[i];
            if (!el.isConnected) continue;
            if (connected === null) connected = el;
            if (onScreen(el)) return el;
            if (fallback === null && el.getClientRects().length > 0) fallback = el;
          }
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

        var check = function () {
          if (disposed) return;
          checks++;
          if (!live(found)) found = pick();
          var present = found !== null;
          var at = now();
          if (present && !running) {
            running = true;
            startedAt = at;
            lastBeat = at;
            // 标题也带上：页面被冻时「结束」由 Host 的事件流报告（见 HarnessEventsClient），
            // 那条路径拿不到 DOM，只能用这里记下的标题。
            post({ type: "turn-start", title: sessionLabel() });
          } else if (!present && running) {
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
              watching: found !== null ? geom(found) : null,
              nodes: probe(),
            });
          }
        };

        var observer = new MutationObserver(function () {
          mutations++;
          check();
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
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
        return function () {
          disposed = true;
          observer.disconnect();
          window.clearInterval(tickTimer);
        };
      }, "dsh-handheld-mobile: turn watcher");

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

        var observer = new MutationObserver(check);
        observer.observe(document.documentElement, { childList: true, subtree: true });
        check();
        return function () {
          disposed = true;
          observer.disconnect();
        };
      }, "dsh-handheld-mobile: needs-input watcher");

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
    };

    return module.exports;
  },
});
