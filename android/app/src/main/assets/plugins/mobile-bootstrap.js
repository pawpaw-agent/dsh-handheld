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
 */
(function(){
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
