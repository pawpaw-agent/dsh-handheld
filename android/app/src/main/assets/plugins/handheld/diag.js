/*
 * 自证与开销（**默认不跑**，2026-10-08 重新设计）。
 *
 * 旧层把这些东西混在业务代码里、每次加载都跑：注入时序、回调开销包装、环境采样、
 * 以及一堆「一次性诊断」（手指档体检 / 右侧栏探针 / 点击追踪 / 布局与环境诊断）。
 * 那些诊断的结论已经写进 docs/archive/mobile-adaptation.md，不该跟着每次加载进手机。
 *
 * 现在只留两项有长期价值的，而且**默认关闭**：
 *   - 注入时序：脚本开始执行 → 全部修复应用完，各花多少毫秒；
 *   - 开销计数：被包装的三个高频回调各累计多少毫秒（10 秒报一次，低于 20ms 静默）。
 *
 * 开启方式：`window.__dshHandheldDiag = true`（App 侧调试时注入一行即可）。
 * 关闭时下面全部是零成本替身：`wrap` 原样返回函数、`log/post` 空实现。
 */
(function (root) {
  "use strict";
  var on = root.__dshHandheldDiag === true;
  var t0 = (root.performance && performance.now) ? performance.now() : Date.now();
  var cost = {}, costN = {};

  var now = function () {
    return (root.performance && performance.now) ? performance.now() : Date.now();
  };

  root.__dshHandheldDiag = {
    on: on,
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
    log: function (msg) {
      if (!on) return;
      try { console.log("[handheld] " + msg); } catch (e) { /* 诊断不该影响主流程 */ }
    },
    /** 旧的页面→App 通知桥已随注入层移除而删除（App 改用事件流）。这里只落日志。 */
    post: function (msg) {
      if (!on) return;
      try { console.log("[handheld:post] " + JSON.stringify(msg)); } catch (e) { /* 同上 */ }
    },
    report: function (phase) {
      if (!on) return;
      var ms = Math.round((now() - t0) * 10) / 10;
      var parts = [];
      for (var k in cost) {
        if (cost[k] >= 20) parts.push(k + "=" + Math.round(cost[k]) + "ms/" + costN[k] + "次");
      }
      try {
        console.log("[handheld] " + phase + " 累计 " + ms + "ms" +
          (parts.length ? "；回调开销 " + parts.join(" ") : ""));
      } catch (e) { /* 同上 */ }
    },
  };
})(window);
