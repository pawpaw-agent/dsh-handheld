/*
 * dsh-handheld-mobile 的**引导脚本（纯注入版，2026-10-08）**
 *
 * 只做一件事：在 document-start 补 `viewport-fit=cover`。
 *
 * 宿主的 index 里那条 meta 是 `width=device-width, initial-scale=1`，没有 viewport-fit
 * —— Chromium 于是把 viewport 按「刘海/挖孔」内缩（真机实测 DisplayCutout insets=128
 * 设备 px = 34 CSS px，页面内容整体下移）。本脚本比宿主 HTML 里那条 meta 先被解析到，
 * 而 Chromium **只认第一条** viewport meta，所以补在这里最稳；补成功后给 <html> 打
 * `data-dsh-cover` 标记，适配层据此把会话头的上边距调到「刚好避开挖孔」而不是白留 34px。
 *
 * ⚠️ 旧版这里还负责往 `window.__DSH_BOOT__` 里**补一条插件条目**，让宿主引导循环去
 * create 适配层。dsh 0.1.7-rc.2 起加载器会按服务端清单回收这类条目（`tearDownEntryFiber`
 * + `removeOwnedStyles`），适配层因此在真机上被拆掉。**那段逻辑已整体删除** —— 适配层
 * 现在由 App 在 document-start 直接注入，不产生任何插件条目，加载器无从回收。
 *
 * 消费方只有 App：MainActivity 读本文件 → `addDocumentStartJavaScript`。
 * 与适配层一样，**不注册插件、不依赖 require/模块加载器**。
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

})();
