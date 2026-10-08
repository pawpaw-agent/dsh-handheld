/*
 * dsh-handheld-mobile 的**引导脚本**（纯注入版，2026-10-08 重新设计）
 *
 * 只做一件事：尽早补 `viewport-fit=cover`。
 *
 * ## 为什么要抢时间
 *
 * 宿主的 index 里那条 meta 是 `width=device-width, initial-scale=1`，没有 viewport-fit
 * —— Chromium 于是把 viewport 按「刘海/挖孔」内缩（真机实测 DisplayCutout insets=128
 * 设备 px = 34 CSS px，页面内容整体下移）。而 Chromium **只认第一条** viewport meta，
 * 所以我们必须插在宿主那条**之前**。
 *
 * 老版本把它挂在 `DOMContentLoaded` 兜底上 —— 那已经晚了（宿主 meta 早就解析过了）。
 * 现在：`document-start` 注册进 boot 队列 → 在 **`<html>` 一出现**就接手；而 `<head>`
 * 比 `<html>` 晚一步才建，所以本脚本再挂一个**只盯到成功为止**的 observer，
 * 补上 meta 后立刻断开（不做常驻观察者，不跟宿主 SPA 的每次 mutation 起舞）。
 *
 * 补成功后给 `<html>` 打 `data-dsh-cover`，适配层据此把会话头上边距调到「刚好避开挖孔」
 * 而不是白留 34px。
 *
 * ## 与插件机制的关系
 *
 * ⚠️ 旧版这里负责往 `window.__DSH_BOOT__` 里**补一条插件条目**，让宿主引导循环去 create
 * 适配层。dsh 0.1.7-rc.2 起加载器会按服务端清单回收这类条目（`tearDownEntryFiber` +
 * `removeOwnedStyles`），适配层因此在真机上被拆掉。**那段逻辑已整体删除** —— 适配层现在由
 * App 在 document-start 直接注入，不产生任何插件条目，加载器无从回收。
 *
 * 另外删掉了 `window.dshNative.postMessage` 那段上报（App 早已移除该 JS 接口，是死代码）；
 * 现在所有自证都走 `diag.js` 的信标。
 *
 * 消费方只有 App：MainActivity 读本文件 → `addDocumentStartJavaScript`。
 */
(function (root) {
  "use strict";

  var applied = false;
  var observer = null;

  function note(stage) {
    try {
      var st = root.__dshHandheldBootState;
      if (st) st.cover = stage + (applied ? ":ok" : ":pending");
    } catch (e) { /* 自证不该影响主流程 */ }
  }

  function tryApply() {
    if (applied) return true;
    var doc = root.document;
    if (!doc || !doc.head || !doc.documentElement) return false;
    var metas = doc.querySelectorAll('meta[name="viewport"]');
    var m = metas.length > 0 ? metas[0] : null;
    if (m === null) {
      m = doc.createElement("meta");
      m.setAttribute("name", "viewport");
      doc.head.insertBefore(m, doc.head.firstChild);
    }
    var cur = m.getAttribute("content") || "";
    if (cur.indexOf("viewport-fit") < 0) {
      // 只补 viewport-fit，宿主其它设置原样保留
      m.setAttribute("content", cur ? cur + ", viewport-fit=cover"
                                    : "width=device-width, initial-scale=1, viewport-fit=cover");
    }
    // 宿主/插件再插第二条也只会被忽略，但留着容易误导，直接去掉
    for (var i = 1; i < metas.length; i++) {
      if (metas[i].parentNode) metas[i].parentNode.removeChild(metas[i]);
    }
    doc.documentElement.setAttribute("data-dsh-cover", "1");
    applied = true;
    stopWatching();
    note("early");
    return true;
  }

  function stopWatching() {
    if (observer) { try { observer.disconnect(); } catch (e) { /* ignore */ } observer = null; }
  }

  function watch() {
    if (tryApply()) return;
    try {
      if (!root.MutationObserver) return;
      observer = new root.MutationObserver(function () { tryApply(); });
      // <head> 是 <html> 的子节点；<html> 此刻可能刚出现，也可能还没有
      observer.observe(root.document.documentElement || root.document, { childList: true, subtree: true });
    } catch (e) { /* 观测不上就靠 boot 的兜底调度 */ }
    tryApply();
  }

  if (root.__dshHandheldBoot) root.__dshHandheldBoot(watch);
  else watch();   // boot.js 缺失时退化（不该发生：注入顺序由 App 保证，且有一致性检查守着）

  // 兜底：DOMContentLoaded / load 时再试一次（正常路径下早已成功）
  try {
    root.document.addEventListener("DOMContentLoaded", function () { if (!applied) { tryApply(); note("dcl"); } }, { once: true });
  } catch (e) { /* ignore */ }
})(window);
