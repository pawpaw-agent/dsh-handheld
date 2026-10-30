package com.dshhandheld.app

import android.webkit.WebView

/**
 * WebView 端的界面增强（App 侧注入）。
 *
 * ⚠️ 方向变更记录：2026-10-15 曾确定「零注入、只用原版网页」，所有注入层被移除。
 * 2026-10-30 用户明确要求「侧边栏改成悬浮按钮显示，在 app 端注入」，故重新引入 **一项**
 * 最小注入：只改侧栏的呈现方式，不动内容、不改宿主代码。
 *
 * 设计要点（都在真机上验证过，见 docs/handheld-injection-sidebar.md）：
 *  - 宿主的 `grid-template-columns: 56px minmax(0,1fr) minmax(0,0px)` 是**内联样式**，
 *    但样式表里的 `!important` 能覆盖内联 —— 所以纯 CSS 就能把侧栏那列压成 0；
 *  - 侧栏用 `position:fixed` 变成浮层后**脱离网格流**，其余列会被整体前移（内容列被挤成 0 宽、
 *    页面变空白）。必须显式给 centerCol/rightbarCol/bottomRow/overlayLayer/handle 指定 grid-column；
 *  - 开合状态**完全跟随宿主**（读 rail 的 `collapsed` 类），不自己维护状态机，
 *    避免与宿主的内部状态不一致；
 *  - 切换动作 = 对宿主自己的「打开/收起侧边栏」按钮派发 click，不直接改宿主状态。
 */
object WebInject {

    /** 侧栏 → 悬浮按钮。幂等：重复注入会自己识别并返回 already。 */
    private const val SIDEBAR_FAB_JS = 
        "(function () {" +
        "  var V = \"v3\";" +
        "  if (window.__dshhhSidebarFab === V) return \"already\";" +
        "  window.__dshhhSidebarFab = V;" +
        "  var RAIL = \"[class*=hHd-Xa_root]\";" +
        "  var CSS = [" +
        "    \".pI_x6G_frame{grid-template-columns:0 minmax(0px,1fr) 0 !important}\"," +
        "    \".pI_x6G_frame > .pI_x6G_centerCol{grid-column:2 !important}\"," +
        "    \".pI_x6G_frame > .pI_x6G_rightbarCol{grid-column:3 !important}\"," +
        "    \".pI_x6G_frame > .pI_x6G_bottomRow{grid-column:1 / -1 !important}\"," +
        "    \".pI_x6G_frame > .pI_x6G_overlayLayer{grid-column:1 / -1 !important}\"," +
        "    \".pI_x6G_frame > .pI_x6G_handle{grid-column:3 !important}\"," +
        "    \"[class*=sidebarCol]{position:fixed !important;left:0;top:0;bottom:0;\"," +
        "    \"  width:280px !important;max-width:82vw;z-index:60;background:#17171a;\"," +
        "    \"  transform:translateX(-102%);transition:transform .18s ease;\"," +
        "    \"  box-shadow:2px 0 18px rgba(0,0,0,.55);display:block !important;overflow:hidden}\"," +
        "    \"html.dshhh-open [class*=sidebarCol]{transform:translateX(0)}\"," +
        "    \"#dshhh-fab{position:fixed;left:10px;bottom:150px;width:44px;height:44px;border-radius:22px;\"," +
        "    \"  background:rgba(43,43,48,.92);border:1px solid #3d3d44;z-index:70;\"," +
        "    \"  display:flex;align-items:center;justify-content:center;box-shadow:0 3px 12px rgba(0,0,0,.55)}\"," +
        "    \"html.dshhh-open #dshhh-fab{background:#3a3a42}\"," +
        "    \"#dshhh-fab i{display:block;width:18px;height:2px;background:#e6e6ea;border-radius:1px;\"," +
        "    \"  box-shadow:0 -6px 0 #e6e6ea,0 6px 0 #e6e6ea}\"," +
        "    \"#dshhh-scrim{position:fixed;top:0;right:0;bottom:0;left:0;background:rgba(0,0,0,.42);z-index:55;display:none}\"," +
        "    \"html.dshhh-open #dshhh-scrim{display:block}\"" +
        "  ].join(String.fromCharCode(10));" +
        "  function rail() { return document.querySelector(RAIL); }" +
        "  function hostOpen() { var r = rail(); return !!r && r.className.toString().indexOf(\"collapsed\") < 0; }" +
        "  function sync() { document.documentElement.classList.toggle(\"dshhh-open\", hostOpen()); }" +
        "  function build() {" +
        "    if (!document.body) return false;" +
        "    if (!document.getElementById(\"dshhh-sidebar-style\")) {" +
        "      var st = document.createElement(\"style\"); st.id = \"dshhh-sidebar-style\"; st.textContent = CSS;" +
        "      document.head.appendChild(st);" +
        "    }" +
        "    if (!document.getElementById(\"dshhh-fab\")) {" +
        "      var fab = document.createElement(\"div\"); fab.id = \"dshhh-fab\";" +
        "      fab.setAttribute(\"role\", \"button\"); fab.setAttribute(\"aria-label\", \"\\u4fa7\\u8fb9\\u680f\");" +
        "      fab.innerHTML = \"<i></i>\";" +
        "      fab.addEventListener(\"click\", function (e) { e.preventDefault(); e.stopPropagation(); toggleHost(); });" +
        "      document.body.appendChild(fab);" +
        "    }" +
        "    if (!document.getElementById(\"dshhh-scrim\")) {" +
        "      var sc = document.createElement(\"div\"); sc.id = \"dshhh-scrim\";" +
        "      sc.addEventListener(\"click\", function (e) { e.preventDefault(); e.stopPropagation(); toggleHost(); });" +
        "      document.body.appendChild(sc);" +
        "    }" +
        "    return true;" +
        "  }" +
        "  function toggleHost() {" +
        "    var t = document.querySelector(\"[aria-label=\\\u6536\\u8d77\\u4fa7\\u8fb9\\u680f]\") ||" +
        "            document.querySelector(\"[aria-label=\\\u6253\\u5f00\\u4fa7\\u8fb9\\u680f]\");" +
        "    if (!t) return false; t.click(); setTimeout(sync, 60); return true;" +
        "  }" +
        "  if (!build()) {" +
        "    var tries = 0;" +
        "    var timer = setInterval(function () { tries++; if (build() || tries > 40) clearInterval(timer); }, 250);" +
        "    return \"deferred\";" +
        "  }" +
        "  var obs = new MutationObserver(sync);" +
        "  var bound = null;" +
        "  setInterval(function () {" +
        "    var r = rail();" +
        "    if (r && r !== bound) { obs.disconnect(); obs.observe(r, {attributes:true, attributeFilter:[\"class\"]}); bound = r; }" +
        "    sync();" +
        "  }, 700);" +
        "  sync();" +
        "  return \"ok\";" +
        "})()"

    /** 幂等注入；页面每次加载（含 SPA 重建）后调用一次即可。 */
    fun applySidebarFab(wv: WebView?) {
        wv?.evaluateJavascript(SIDEBAR_FAB_JS, null)
    }
}
