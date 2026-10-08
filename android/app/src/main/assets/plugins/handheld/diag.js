/*
 * 自证、开销与**信标**（2026-10-08 重新设计·接缝 3）。
 *
 * ## 为什么要有信标
 *
 * 2026-10-08 的事故：`runner.js` 在 document-start 抛了一次 `TypeError`，整个层静默失效。
 * 真机上看到的现象只是「适配层没生效」—— 与「样式本来就没写好」**无法区分**，而且
 * 一条日志都没有：App 的 `onConsoleMessage` **只收 WARNING 及以上**，而层当时全走
 * `console.log`，于是「诊断页」（连接屏右上「诊断」）里看不到层的任何一行。
 *
 * 现在层在每次页面加载后产出**一行状态**，两条通道互补：
 *
 *   1. `console.warn("[handheld] …")` —— 唯一能穿过 App 现有门槛的级别；
 *      **只在状态签名变化或失败时发**，不淹掉诊断页里有用的那几条；
 *   2. `<html data-handheld-status="…">`（紧凑 JSON）—— App 可随时用 `evaluateJavascript`
 *      读，不依赖日志；诊断页据此显示一行「适配层：…」。
 *
 * 信标本身**绝不抛异常、绝不影响主流程**（整个层的老约定）。
 *
 * ## 开销
 *
 * `wrap()` 包装高频回调累计耗时；`report()` 报一次注入总耗时。
 * 这些都**默认关闭**（`window.__dshHandheldDiag = true` 才启用），关闭时全是零成本替身。
 */
(function (root) {
  "use strict";
  var on = root.__dshHandheldDiag === true;
  var t0 = (root.performance && root.performance.now) ? root.performance.now() : Date.now();
  var cost = {}, costN = {};
  var lastSig = null;

  var now = function () {
    return (root.performance && root.performance.now) ? root.performance.now() : Date.now();
  };
  var ms = function () { return Math.round((now() - t0) * 10) / 10; };

  /** 正在收集的状态（各段往里写，信标统一读它） */
  var status = { ok: true, stage: null, err: null, styles: null, stylesBytes: 0,
    fixes: 0, fixesTotal: 0, pending: [], mobile: null, skipped: null };
  root.__dshHandheldStatus = status;

  function sig() {
    return [status.ok, status.stage, status.styles, status.fixes, status.fixesTotal,
      status.mobile, status.pending.join(",")].join("|");
  }

  function text() {
    var s = status;
    var parts = [];
    parts.push(s.ok ? "ok" : "FAILED");
    if (s.skipped) {
      // 桌面档不是失败：层本来只服务手机（与 harness 的 V8 一致）
      parts.push("skipped=" + s.skipped);
      parts.push("mobile=no");
      var b0 = root.__dshHandheldBootState;
      if (b0) parts.push("dom=" + b0.when);
      parts.push("cost=" + ms() + "ms");
      return parts.join(" ");
    }
    if (!s.ok) {
      parts.push("stage=" + (s.stage || "?"));
      parts.push("err=" + String(s.err || "").slice(0, 120));
    }
    parts.push("styles=" + (s.styles || "?") + "(" + Math.round(s.stylesBytes / 1024 * 10) / 10 + "KB)");
    parts.push("fixes=" + s.fixes + "/" + s.fixesTotal);
    if (s.pending.length) parts.push("pending=" + s.pending.join(","));
    parts.push("mobile=" + (s.mobile === null ? "?" : (s.mobile ? "yes" : "no")));
    var bs = root.__dshHandheldBootState;
    if (bs) {
      parts.push("dom=" + bs.when);
      if (bs.cover) parts.push("cover=" + bs.cover);
      if (bs.errors && bs.errors.length) parts.push("bootErr=" + bs.errors.length);
    }
    parts.push("cost=" + ms() + "ms");
    return parts.join(" ");
  }

  root.__dshHandheldDiag = {
    on: on,
    status: status,
    t0: t0,
    /** 包装高频回调，累计耗时；关闭时原样返回（零开销）。 */
    wrap: function (name, fn) {
      if (!on) return fn;
      return function () {
        var t = now();
        try { return fn.apply(null, arguments); }
        finally {
          cost[name] = (cost[name] || 0) + (now() - t);
          costN[name] = (costN[name] || 0) + 1;
        }
      };
    },
    /** 详细日志：级别 INFO（App 不收，只有开着 __dshHandheldDiag 时才看）。 */
    log: function (msg) {
      if (!on) return;
      try { console.log("[handheld] " + msg); } catch (e) { /* 诊断不该影响主流程 */ }
    },
    /** 旧的页面→App 通知桥已随注入层移除而删除（App 改用事件流）。这里只落日志。 */
    post: function (msg) {
      if (!on) return;
      try { console.log("[handheld:post] " + JSON.stringify(msg)); } catch (e) { /* 同上 */ }
    },
    /** 记一次失败：第一次失败即定音（后续同名失败不刷屏，只保留首条）。 */
    fail: function (stage, err) {
      var msg = String(err && err.message ? err.message : err);
      try {
        if (status.ok) { status.ok = false; status.stage = stage; status.err = msg; }
        else if (status.stage !== stage) { return; }   // 与首条不同的失败另算一次（外层会再报）
      } catch (e) { /* ignore */ }
      root.__dshHandheldDiag.beacon();
    },
    /** 产出一行状态：写 <html> 属性 + （签名变了才）warn 一行。 */
    beacon: function (force) {
      try {
        var t = text();
        var de = root.document && root.document.documentElement;
        if (de) {
          de.setAttribute("data-handheld-status", JSON.stringify({
            ok: status.ok, stage: status.stage, err: status.err,
            styles: status.styles, fixes: status.fixes + "/" + status.fixesTotal,
            skipped: status.skipped,
            pending: status.pending, mobile: status.mobile,
            dom: root.__dshHandheldBootState ? root.__dshHandheldBootState.when : null,
            costMs: ms(),
          }));
        }
        var s = sig() + "|" + status.err;
        if (force || s !== lastSig) {
          lastSig = s;
          // ⚠️ 必须 WARNING 级：App 的 onConsoleMessage 只收 WARNING+，INFO 会到不了诊断页
          try { console.warn("[handheld] " + t); } catch (e) { /* ignore */ }
        }
      } catch (e) { /* 信标自身绝不抛 */ }
    },
    report: function (phase) {
      if (!on) return;
      var parts = [];
      for (var k in cost) {
        if (cost[k] >= 20) parts.push(k + "=" + Math.round(cost[k]) + "ms/" + costN[k] + "次");
      }
      try {
        console.log("[handheld] " + phase + " 累计 " + ms() + "ms" +
          (parts.length ? "；回调开销 " + parts.join(" ") : ""));
      } catch (e) { /* 同上 */ }
    },
  };
})(window);
