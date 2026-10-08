/*
 * 运行器（2026-10-08 重新设计·接缝 2+3）。
 *
 * 职责：
 *   1. **顶层只注册**：所有 DOM 读写在 `__dshHandheldBoot` 之后（DOM 一出现就跑，
 *      见 boot.js）。document-start 阶段绝不碰 DOM —— 那正是 2026-10-08 那次静默失效的根因；
 *   2. 挂样式表（`window.__dshHandheldCss` 由 App 从 styles.css 读入后注入）；
 *   3. 观察 DOM，某条修复依赖的钩子齐了就调用它一次；
 *   4. 单条修复抛异常时**隔离**它自己（记日志、其余照跑、页面不受影响），并把失败写进信标；
 *   5. 轻量自愈：样式或 `[data-handheld="frame"]` 标记被冲掉时补回来。
 *
 * 与旧层最大的差别：这里**没有**「跟加载器斗」的逻辑。旧层是注册成 dsh 插件的，
 * 0.1.7-rc.2 起加载器会按服务端清单回收条目（`tearDownEntryFiber` + `removeOwnedStyles`），
 * 于是需要 5.8 KB 的看门狗一轮轮自愈（真机日志 `heals:1`）。现在脚本由 App 在
 * document-start 直接注入，**没有任何东西会回收它**，自愈只剩「页面自己重渲染」这一种情况。
 */
(function (root) {
  "use strict";

  var diag = root.__dshHandheldDiag || {
    on: false, t0: 0, status: {},
    wrap: function (n, f) { return f; },
    log: function () {}, post: function () {}, report: function () {},
    beacon: function () {}, fail: function () {},
  };
  var H = root.__dshHandheldHooks || {};
  var FIXES = root.__dshHandheldFixes || [];
  var STYLE_MARK = "dsh-handheld-mobile/mobile.css";

  // ── 手机档门 ──────────────────────────────────────────────────────────────
  //
  // 层只服务手机：整张样式表本来就只在 `(max-width:1023px) and (pointer:coarse)` 下生效
  // （见 styles.css 顶部），所以 JS 侧（打标记、写 --vh、跑四条修复）也用同一条件 ——
  // 否则在桌面浏览器上会做一堆**没有样式配合**的徒劳改动。
  // 取不到 matchMedia 时按手机处理：App 里永远是手机，宁可多做事也不要静默不做事。
  var MOBILE_QUERY = "(max-width: 1023px) and (pointer: coarse)";
  function isMobile() {
    try {
      if (!root.matchMedia) return true;
      return !!root.matchMedia(MOBILE_QUERY).matches;
    } catch (e) { return true; }
  }

  var styleTag = null;
  var applied = Object.create(null);
  var observing = false;
  var loggedOff = false;
  var finalized = false;

  // ── 样式表 ────────────────────────────────────────────────────────────────
  function injectStyles() {
    if (!isMobile()) {
      if (diag.status) diag.status.styles = "skipped";
      return false;      // 桌面档：整层不做事（样式表本来也只在手机档生效）
    }
    var css = root.__dshHandheldCss;
    if (!css) { diag.log("样式：__dshHandheldCss 未提供，跳过"); return false; }
    var host = document.head || document.documentElement;
    if (!host) return false;      // 正常路径下 boot 保证了它存在；保险起见不抛
    var tag = document.createElement("style");
    // ⚠️ 绝不能带 data-plugin：宿主 0.1.7-rc.2 的 removeOwnedStyles(id) 删的就是
    // `style[data-plugin="<id>"]`。换成宿主不认识的标记，那条删除路径就够不着。
    tag.dataset.handheldCss = STYLE_MARK;
    tag.textContent = css;
    host.appendChild(tag);
    // 宿主的组件样式是各自 append 上去的、有的还带 !important：再 append 一次让本表
    // 停在 <head> 末尾，层叠顺序才稳定（旧层做法，真机验证过）。
    root.setTimeout(function () {
      if (tag.isConnected && document.head) document.head.appendChild(tag);
    }, 0);
    styleTag = tag;
    if (diag.status) { diag.status.styles = "ok"; diag.status.stylesBytes = css.length; }
    diag.log("样式：已挂载 " + css.length + " 字符");
    return true;
  }

  // ── 修复的应用与隔离 ──────────────────────────────────────────────────────
  function hooksReady(needs) {
    for (var i = 0; i < needs.length; i++) {
      var sel = H[needs[i]];
      if (!sel) { diag.fail("hook:" + needs[i], new Error("未登记的钩子")); return false; }
      if (!document.querySelector(sel)) return false;
    }
    return true;
  }

  function applyFix(f) {
    // repeat 的修复（幂等且便宜，如标记兜底）每轮都跑；其余只跑一次。
    if (applied[f.id] && !f.repeat) return;
    if (!hooksReady(f.needs)) return;
    applied[f.id] = true;
    try {
      f.body({}, diag.wrap, diag.post);
      diag.log("修复已应用：" + f.id);
    } catch (e) {
      // 隔离：只影响这一条。下轮还会再试（applied 置回 false）。
      applied[f.id] = false;
      diag.fail("fix:" + f.id, e);
      diag.log("修复抛异常（已隔离）：" + f.id + " — " + e);
    }
  }

  function countApplied() {
    var n = 0;
    for (var i = 0; i < FIXES.length; i++) if (applied[FIXES[i].id]) n++;
    return n;
  }

  function pendingIds() {
    var out = [];
    for (var i = 0; i < FIXES.length; i++) if (!applied[FIXES[i].id]) out.push(FIXES[i].id);
    return out;
  }

  function observe() {
    if (!root.MutationObserver) return false;
    if (!document.documentElement) return false;
    try {
      new root.MutationObserver(schedule).observe(document.documentElement, {
        childList: true, subtree: true,
      });
      return true;
    } catch (e) { diag.log("MutationObserver 挂不上：" + e); return false; }
  }

  function sweep() {
    for (var i = 0; i < FIXES.length; i++) applyFix(FIXES[i]);
    // 轻量自愈：整套 CSS 以 [data-handheld="frame"] 为前提，标记没了就补。
    if (H.frame && !document.querySelector(H.frame)) {
      applied["frame-tagging"] = false;
      for (var j = 0; j < FIXES.length; j++) {
        if (FIXES[j].id === "frame-tagging") applyFix(FIXES[j]);
      }
    }
    if (styleTag && !styleTag.isConnected) {
      diag.log("样式：被移除了，重新挂载");
      injectStyles();
    } else if (!styleTag) {
      injectStyles();
    }
    if (!observing) observing = observe();
  }

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    var run = function () {
      scheduled = false;
      if (!isMobile()) {
        if (!loggedOff) {
          loggedOff = true;
          diag.log("非手机档：整层不生效（" + MOBILE_QUERY + "）");
          finalize("skipped");
        }
        return;
      }
      sweep();
      finalize("sweep");
    };
    if (root.requestAnimationFrame) root.requestAnimationFrame(run);
    else root.setTimeout(run, 16);
  }

  /** 汇总状态并写信标（只发一次，之后只有失败才再发）。 */
  function finalize(why) {
    if (!diag.status) return;
    if (!isMobile()) {
      diag.status.skipped = "non-mobile";
      diag.status.mobile = false;
      diag.beacon(!finalized);
      finalized = true;
      return;
    }
    diag.status.mobile = true;
    diag.status.styles = styleTag ? "ok" : (diag.status.styles || "missing");
    diag.status.fixesTotal = FIXES.length;
    diag.status.fixes = countApplied();
    diag.status.pending = pendingIds();
    diag.report("注入完成");
    diag.beacon(!finalized);
    finalized = true;
  }

  // ── 顶层只注册（接缝 2）──────────────────────────────────────────────────
  // window 级监听不碰 DOM，放这里没问题；DOM 相关的全部进 boot。
  if (root.addEventListener) root.addEventListener("resize", schedule);

  function boot() {
    observing = observe();
    injectStyles();
    schedule();
  }

  if (root.__dshHandheldBoot) root.__dshHandheldBoot(boot);
  else boot();   // 退化路径（boot.js 缺失；不该发生，一致性检查守着注入顺序）
})(window);
