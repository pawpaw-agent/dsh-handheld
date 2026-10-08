/*
 * 运行器（2026-10-08 重新设计）。
 *
 * 职责只有四件：
 *   1. 挂样式表（`window.__dshHandheldCss` 由 App 从 styles.css 读入后注入）；
 *   2. 观察 DOM，某条修复依赖的钩子齐了就调用它一次；
 *   3. 单条修复抛异常时**隔离**它自己（记日志、其余照跑、页面不受影响）；
 *   4. 轻量自愈：样式或 `[data-handheld="frame"]` 标记被冲掉时补回来。
 *
 * 与旧层最大的差别：这里**没有**「跟加载器斗」的逻辑。旧层是注册成 dsh 插件的，
 * 0.1.7-rc.2 起加载器会按服务端清单回收条目（`tearDownEntryFiber` + `removeOwnedStyles`），
 * 于是需要 5.8 KB 的看门狗一轮轮自愈（真机日志 `heals:1`）。现在脚本由 App 在
 * document-start 直接注入，**没有任何东西会回收它**，自愈只剩「页面自己重渲染」这一种情况。
 */
(function (root) {
  "use strict";

  var diag = root.__dshHandheldDiag || {
    on: false, t0: 0,
    wrap: function (n, f) { return f; },
    log: function () {}, post: function () {}, report: function () {},
  };
  var H = root.__dshHandheldHooks || {};
  var FIXES = root.__dshHandheldFixes || [];
  var STYLE_MARK = "dsh-handheld-mobile/mobile.css";

  // ── 1. 样式表 ────────────────────────────────────────────────
  var styleTag = null;
  function injectStyles() {
    var css = root.__dshHandheldCss;
    if (!css) { diag.log("样式：__dshHandheldCss 未提供，跳过"); return; }
    var tag = document.createElement("style");
    // ⚠️ 绝不能带 data-plugin：宿主 0.1.7-rc.2 的 removeOwnedStyles(id) 删的就是
    // `style[data-plugin="<id>"]`。换成宿主不认识的标记，那条删除路径就够不着。
    tag.dataset.handheldCss = STYLE_MARK;
    tag.textContent = css;
    (document.head || document.documentElement).appendChild(tag);
    // 宿主的组件样式是各自 append 上去的、有的还带 !important：再 append 一次让本表
    // 停在 <head> 末尾，层叠顺序才稳定（旧层做法，真机验证过）。
    root.setTimeout(function () {
      if (tag.isConnected && document.head) document.head.appendChild(tag);
    }, 0);
    styleTag = tag;
    diag.log("样式：已挂载 " + css.length + " 字符");
  }

  // ── 2. 修复的应用与隔离 ──────────────────────────────────────
  var applied = Object.create(null);

  function hooksReady(needs) {
    for (var i = 0; i < needs.length; i++) {
      var sel = H[needs[i]];
      if (!sel) { diag.log("修复依赖了未登记的钩子：" + needs[i]); return false; }
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
      diag.log("修复抛异常（已隔离）：" + f.id + " — " + e);
    }
  }

  function sweep() {
    for (var i = 0; i < FIXES.length; i++) applyFix(FIXES[i]);
    // 4. 轻量自愈：整套 CSS 以 [data-handheld="frame"] 为前提，标记没了就补。
    if (H.frame && !document.querySelector(H.frame)) {
      applied["frame-tagging"] = false;
      for (var j = 0; j < FIXES.length; j++) {
        if (FIXES[j].id === "frame-tagging") applyFix(FIXES[j]);
      }
    }
    if (styleTag && !styleTag.isConnected) {
      diag.log("样式：被移除了，重新挂载");
      injectStyles();
    }
  }

  // ── 3. 触发：初始一次 + mutation 去抖（rAF）──────────────────
  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    var run = function () { scheduled = false; sweep(); };
    if (root.requestAnimationFrame) root.requestAnimationFrame(run);
    else root.setTimeout(run, 16);
  }

  injectStyles();
  schedule();
  if (root.MutationObserver) {
    try {
      new root.MutationObserver(schedule).observe(document.documentElement, {
        childList: true, subtree: true,
      });
    } catch (e) { diag.log("MutationObserver 挂不上：" + e); }
  }
  // 页面加载完成后再兜一次（有些结构是 load 之后才挂的）
  if (document.readyState !== "complete") {
    root.addEventListener("load", schedule, { once: true });
  }
  root.setTimeout(function () { diag.report("注入完成"); }, 0);
})(window);
