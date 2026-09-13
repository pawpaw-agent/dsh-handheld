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

    /** 只有「窄视口 + 触摸主指针」才生效：桌面窗口完全不受影响。 */
    var MOBILE_QUERY = "(max-width: 1023px) and (pointer: coarse)";
    /** 抽屉宽：窄手机 86vw，最宽 340px（再宽在平板上也不像个抽屉了）。 */
    var DRAWER_W = "min(86vw, 340px)";

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
     挤到只剩一条。手机上不需要窄条：两轨归零，中栏吃满，侧栏改由下面画成浮层。 */
  [data-handheld="frame"] {
    grid-template-columns: minmax(0, 1fr) 0 0 !important;
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
    transform: translateX(-102%);
    transition: transform .22s cubic-bezier(.2, .7, .3, 1);
    /* 刘海与手势条：抽屉自己吃安全区，里面的内容不必各自处理 */
    padding-top: env(safe-area-inset-top, 0px);
    padding-bottom: env(safe-area-inset-bottom, 0px);
    border-right: 0 !important;
    will-change: transform;
  }
  [data-handheld="frame"]:not([data-sidebar-collapsed]) > [class*="_sidebarCol"] {
    transform: none;
    box-shadow: 0 0 42px rgba(0, 0, 0, .55);
  }
  /* 收起时彻底让出指针，免得一条看不见的侧栏吃掉边缘手势 */
  [data-handheld="frame"][data-sidebar-collapsed] > [class*="_sidebarCol"] {
    pointer-events: none;
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
    padding-left: 8px !important;
    padding-right: 8px !important;
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
     胶囊 / 谱系的弹层是 position:absolute + top:calc(100% + 5px)，锚在触发按钮的 root
     上。这里只做两件事：保证 root 仍是定位元素（否则包含块上溯到整屏高的 frame，
     菜单会掉到屏幕外），以及让它往左展开、不越出视口。 */
  [data-handheld="frame"] [data-phase] header [class*="_root"]:has(> button[class*="_trigger"]) {
    position: relative !important;
  }
  [data-handheld="frame"] [data-phase] header [class*="_menu"] {
    left: auto !important;
    right: 0 !important;
    width: min(336px, calc(100vw - 16px));
    max-width: none;
    max-height: min(420px, calc(100dvh - 120px));
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
     工作区标题行那颗 + 开的目录选择器由**宿主**决定：本部署里 directory-picker-auto
     判成 native，对话框开在电脑桌面上，手机上按下去什么都不会发生。要让它可用就得在
     宿主侧把交互钉成 -browse —— 本项目不动服务端 composition，所以这个入口直接去掉。
     两个 aria-label 是 dsh 自己词典里的 zh / en 值；换第三种语言时它会重新出现
     （只是多一个按了没反应的入口，不会坏）。 */
  [aria-label="添加工作区"],
  [aria-label="Add workspace"] {
    display: none !important;
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
        // shell.overlay 槽的内容渲染在 frame > overlayLayer 里，往上两层就是 frame。
        var frame =
          node && node.parentElement ? node.parentElement.parentElement : null;
        if (!frame) return undefined;
        frame.setAttribute("data-handheld", "frame");

        var onFrameClick = function (event) {
          var target = event.target;
          if (!target || typeof target.closest !== "function") return;
          var col = frame.querySelector('[class*="_sidebarCol"]');
          if (!col || !col.contains(target)) return;
          // 抽屉里的输入框与按钮（搜索、视图选项…）自己处理这一笔；其余点击
          //（会话行、工作区行）意味着「选完了」，顺手把抽屉收起来。
          if (target.closest("input, textarea, select, button, [contenteditable]")) return;
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
