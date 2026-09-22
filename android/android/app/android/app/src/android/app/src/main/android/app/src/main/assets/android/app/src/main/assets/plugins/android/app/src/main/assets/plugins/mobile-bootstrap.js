/*
 * dsh-web-mobile 注入引导脚本 —— **单一来源**。
 *
 * 这份脚本在页面 document-start 时执行，钩住 dsh 写入的 window.__DSH_BOOT__ 启动图，
 * 往里面补一条 dsh-web-mobile 插件项（entry + batch），使 WebView 引导循环去 create 它。
 * 插件 bundle 本身由 shouldInterceptRequest（App）或 Fetch.fulfillRequest（验证 harness）
 * 从本地返回，**服务端零改动**。
 *
 * 两个消费者：
 *   - App：MainActivity 读本文件 + 替换占位符 → addDocumentStartJavaScript
 *   - 验证：scripts/ui-verify.mjs 读本文件 + 同样替换 → Page.addScriptToEvaluateOnNewDocument
 *
 * 之所以从 Kotlin 裸字符串里搬出来：那份副本与 harness 必须逐字一致，否则「测过的」
 * 与「跑的」不是同一个东西。搬成文件后两边读同一份，占位符由各自的常量填充；
 * 常量是否一致由 CI 不变量守着（见 .github/workflows/ci.yml）。
 *
 * 占位符（由调用方替换）：
 *   {{ID}}   插件 id，必须与 bundle 内部的 `id: "dsh-web-mobile"` 一致
 *   {{URL}}  插件 client.js 的 URL（App 侧指向自己的 agent 数据 URL）
 *   {{REV}}  缓存键，内容变更必须换值
 *
 * 另外它负责一件与「注入」无关、但必须发生在 document-start 的事：**补 viewport-fit=cover**。
 * 宿主 index 的 meta 是 `width=device-width, initial-scale=1`，没有 viewport-fit —— Chromium
 * 于是把 viewport 按「刘海/挖孔」内缩（本机实测 DisplayCutout insets=128 设备 px = 34 CSS px，
 * 页面内容因此整体下移 34px）。本脚本比宿主 HTML 里那条 meta 先被解析到，而 Chromium 只认
 * **第一条** viewport meta，所以补在这里最稳。补成功后给 <html> 打 data-dsh-cover 标记，
 * 适配层据此把会话头的上边距调到「刚好避开挖孔」，而不是白白留 34px。
 */
(function(){
  // ── 让页面用满整屏：补 viewport-fit=cover（见文件头注释）───────────────────
  (function(){
    try {
      var apply = function(){
        var head = document.head || document.documentElement;
        if (!head) return false;
        var metas = document.querySelectorAll('meta[name="viewport"]');
        var m = metas.length > 0 ? metas[0] : null;
        if (m === null) {
          m = document.createElement("meta");
          m.setAttribute("name", "viewport");
          head.insertBefore(m, head.firstChild);
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
        document.documentElement.setAttribute("data-dsh-cover", "1");
        return true;
      };
      var report = function(stage){
        try {
          if (window.dshNative && window.dshNative.postMessage) {
            var meta = document.querySelector('meta[name="viewport"]');
            window.dshNative.postMessage(JSON.stringify({
              type: "viewport-diag",
              stage: stage,
              innerW: window.innerWidth,
              innerH: window.innerHeight,
              screenH: screen.height,
              dpr: window.devicePixelRatio,
              meta: meta ? meta.getAttribute("content") : null
            }));
          }
        } catch (e) {}
      };
      var mark = function(stage){ report(stage + ":" + (apply() ? "ok" : "no-head")); };
      mark("start");
      if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", function(){ mark("dom"); }, { once: true });
      }
      window.addEventListener("load", function(){ mark("load"); }, { once: true });
    } catch (e) {}
  })();

  try {
    var stored;
    Object.defineProperty(window, "__DSH_BOOT__", {
      configurable: true,
      get: function(){ return stored; },
      set: function(v){
        try {
          if (v && Array.isArray(v.entries) && Array.isArray(v.batches)) {
            // 幂等（审计 L16）：同一文档里第二次写 __DSH_BOOT__ 时再 push 一遍，宿主解析器会抛
            // `duplicate graph entry`，整个前端停在「Failed to load plugins」。按 id 去重即可免疫。
            for (var i = 0; i < v.entries.length; i++) {
              if (v.entries[i] && v.entries[i].id === "{{ID}}") { stored = v; return; }
            }
            v.entries.push({
              id: "{{ID}}",
              url: "{{URL}}",
              rev: "{{REV}}",
              inject: [],
              external: []
            });
            v.batches.push({
              phase: "application",
              url: "{{URL}}",
              rev: "{{REV}}",
              entries: ["{{ID}}"]
            });
          }
        } catch(e) {}
        stored = v;
      }
    });
  } catch(e) {}
})();
