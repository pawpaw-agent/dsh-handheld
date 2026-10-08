/*
 * 引导队列（2026-10-08 重新设计·接缝 1+2）。
 *
 * ## 为什么需要它
 *
 * 层的所有段都由 App 在 **document-start** 注入（`addDocumentStartJavaScript` /
 * `addScriptToEvaluateOnNewDocument`）。那个时刻 `document` 对象已经存在，但
 * **`document.documentElement` 与 `document.head` 都还是 null**。
 *
 * 2026-10-08 的实测事故：`runner.js` 顶层直接
 * `(document.head || document.documentElement).appendChild(tag)` → 两者都是 null →
 * `TypeError` → **整个 runner 死掉** → 样式与四条修复全部静默失效，真机上只表现为
 * 「适配层没生效」，没有任何报错（App 只收 WARNING+ 的 console，而层当时走 console.log）。
 *
 * 所以规则改成**结构性的**，而不是靠每个调用点自觉判空：
 *
 *   **document-start 段只允许"定义"，呈现型工作一律注册进这里，DOM 一出现就执行。**
 *
 * ## 时机：比 DOMContentLoaded 更早
 *
 * 用 `MutationObserver` 监听 `document`（对象本身在 document-start 就存在）——
 * `<html>` 一被创建就回调，**在解析期**、且在宿主页面自己的脚本之前。这对 viewport
 * 补丁尤其关键：Chromium 只认第一条 viewport meta，等 DOMContentLoaded 再补可能已经晚了。
 *
 * observer 在首次成功 flush 后**立刻断开**（它只为"最初那一刻"服务，不做常驻观察者 ——
 * 常驻会跟着宿主 SPA 的每一次 mutation 回调，白烧电）。
 *
 * ## 契约
 *
 *   window.__dshHandheldBoot(fn)      注册一个 boot 任务（DOM 未就绪则排队）
 *   window.__dshHandheldBootState     状态：{dom, when, tasks, done, errors[]}
 *
 * 任务**互不影响**：单个抛异常被记录进 state.errors 并继续下一个（与 runner 的修复隔离同约定）。
 */
(function (root) {
  "use strict";

  var t0 = (root.performance && root.performance.now) ? root.performance.now() : Date.now();
  var pending = [];
  var started = false;
  var observer = null;

  var state = {
    dom: false,            // documentElement 是否已出现
    when: "pending",       // pending | early（解析期）| ready-state | dcl | load | injected（注入时已就绪）
    tasks: 0,
    done: 0,
    errors: [],            // [{task, msg}]
    t0: t0,
  };
  root.__dshHandheldBootState = state;

  function now() {
    return (root.performance && root.performance.now) ? root.performance.now() : Date.now();
  }

  function domReady() {
    return !!(root.document && root.document.documentElement);
  }

  function disarm() {
    if (observer) { try { observer.disconnect(); } catch (e) { /* ignore */ } observer = null; }
    try {
      if (root.document) root.document.removeEventListener("readystatechange", onReadyState);
      root.removeEventListener("DOMContentLoaded", onDcl);
      root.removeEventListener("load", onLoad);
    } catch (e) { /* ignore */ }
  }

  function flush(when) {
    if (started || !domReady()) return;
    started = true;
    state.dom = true;
    state.when = when || "unknown";
    disarm();
    for (var i = 0; i < pending.length; i++) {
      var fn = pending[i];
      try {
        fn();
        state.done++;
      } catch (e) {
        // 隔离：一条任务炸掉不影响其余，也绝不让异常冒到宿主页面
        state.errors.push({ task: fn && fn.name ? fn.name : ("#" + i), msg: String(e && e.message ? e.message : e) });
      }
    }
    pending = [];
  }

  function onReadyState() { flush("ready-state"); }
  function onDcl() { flush("dcl"); }
  function onLoad() { flush("load"); }

  // ── 装配最早时机 ──────────────────────────────────────────────────────────
  function arm() {
    if (started) return;
    try {
      if (root.MutationObserver && root.document) {
        observer = new root.MutationObserver(function () { flush("early"); });
        observer.observe(root.document, { childList: true, subtree: true });
      }
    } catch (e) { /* 观测不上就靠下面的兜底 */ }
    try {
      if (root.document) {
        root.document.addEventListener("readystatechange", onReadyState);
        if (root.document.readyState !== "loading") flush("injected");
      }
      root.addEventListener("DOMContentLoaded", onDcl);
      root.addEventListener("load", onLoad);
    } catch (e) { /* ignore */ }
    // App 在页面已加载后重新注入时，DOM 早就绪 —— 立刻跑
    flush("injected");
  }

  root.__dshHandheldBoot = function (fn) {
    if (typeof fn !== "function") return;
    state.tasks++;
    if (started) {
      try { fn(); state.done++; }
      catch (e) { state.errors.push({ task: fn.name || "?", msg: String(e && e.message ? e.message : e) }); }
      return;
    }
    pending.push(fn);
    arm();
  };

  arm();
})(window);
