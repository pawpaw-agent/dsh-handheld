package com.dshhandheld.app

import android.webkit.WebView

/**
 * WebView 端的界面增强（App 侧注入）。
 *
 * ⚠️ 方向变更记录：2026-10-15 曾确定「零注入、只用原版网页」并移除全部注入层；
 * 2026-10-30 用户明确要求「侧边栏改成悬浮按钮显示，在 app 端注入」，故重新引入，
 * 但**只此一项**：不改页面内容、不改宿主代码，关掉开关即回到原版。
 *
 * 设计要点（均在真机验证，过程与踩坑见 docs/handheld-injection-sidebar.md）：
 *
 * 1. 宿主 `.pI_x6G_frame` 的 `grid-template-columns` 是**内联样式**，但样式表 `!important` 能覆盖；
 *    侧栏那一列压成 0，内容列即可吃满整幅宽度。
 * 2. 侧栏用 `position:fixed` 变浮层后**脱离网格流**，其余列会整体前移（内容列被挤成 0、白屏）。
 *    必须显式给 centerCol/rightbarCol/bottomRow/overlayLayer/handle 指定 grid-column。
 * 3. **不能**把整条 grid-template 写死成 `0 1fr 0` —— 那会把宿主的右侧面板一起压成 0。
 *    正确做法：读出宿主的模板，只把第一列改 0，其余列原样保留（右侧面板仍可正常打开）。
 * 4. 开合状态**完全跟随宿主**（读 rail 的 `collapsed` 类），不自己维护状态机。
 * 5. 切换动作 = 对宿主自己的「打开/收起侧边栏」按钮派发 click，不直接改宿主状态。
 * 6. 中文标签用 String.fromCharCode 在 JS 里拼，避免 Kotlin/JS 双层转义出错（踩过）。
 */
object WebInject {

    private const val SIDEBAR_FAB_JS =
        "(function () {" +
        "  var V = \"v4\";" +
        "  if (window.__dshhhSidebarFab === V) return \"already\";" +
        "  window.__dshhhSidebarFab = V;" +
        "  var LBL_CLOSE = String.fromCharCode(25910,36215,20391,36793,26639);" +
        "  var LBL_OPEN  = String.fromCharCode(25171,24320,20391,36793,26639);" +
        "  var FRAME = \".pI_x6G_frame\";" +
        "  var RAIL = \"[class*=hHd-Xa_root]\";" +

        "  var CSS = [" +
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

        "  function fixGrid() {" +
        "    var f = document.querySelector(FRAME);" +
        "    if (!f) return;" +
        "    var raw = f.style.gridTemplateColumns || \"\";" +
        "    if (!raw) return;" +
        "    var parts = raw.match(/(?:minmax\\([^)]*\\)|[^\\s]+)/g);" +
        "    if (!parts || parts.length < 2) return;" +
        "    parts[0] = \"0px\";" +
        "    var want = parts.join(\" \");" +
        "    if (raw !== want) f.style.setProperty(\"grid-template-columns\", want, \"important\");" +
        "  }" +

        "  function toggleHost() {" +
        "    var t = document.querySelector(\"[aria-label=\\\" + LBL_CLOSE + \"\\\"]\") ||" +
        "            document.querySelector(\"[aria-label=\\\" + LBL_OPEN + \"\\\"]\");" +
        "    if (!t) return false;" +
        "    t.click(); setTimeout(sync, 60); return true;" +
        "  }" +

        "  function build() {" +
        "    if (!document.body) return false;" +
        "    if (!document.getElementById(\"dshhh-sidebar-style\")) {" +
        "      var st = document.createElement(\"style\"); st.id = \"dshhh-sidebar-style\"; st.textContent = CSS;" +
        "      document.head.appendChild(st);" +
        "    }" +
        "    if (!document.getElementById(\"dshhh-fab\")) {" +
        "      var fab = document.createElement(\"div\"); fab.id = \"dshhh-fab\";" +
        "      fab.setAttribute(\"role\", \"button\"); fab.setAttribute(\"aria-label\", \"sidebar-fab\");" +
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

        "  if (!build()) {" +
        "    var tries = 0;" +
        "    var timer = setInterval(function () { tries++; if (build() || tries > 40) clearInterval(timer); }, 250);" +
        "    return \"deferred\";" +
        "  }" +

        "  var obs = new MutationObserver(sync);" +
        "  var fObs = new MutationObserver(function () { fixGrid(); });" +
        "  var bound = null, fBound = null;" +
        "  setInterval(function () {" +
        "    var r = rail();" +
        "    if (r && r !== bound) { obs.disconnect(); obs.observe(r, {attributes:true, attributeFilter:[\"class\"]}); bound = r; }" +
        "    var f = document.querySelector(FRAME);" +
        "    if (f && f !== fBound) { fObs.disconnect(); fObs.observe(f, {attributes:true, attributeFilter:[\"style\"]}); fBound = f; }" +
        "    fixGrid();" +
        "    sync();" +
        "  }, 500);" +

        "  fixGrid();" +
        "  sync();" +
        "  return \"ok\";" +
        "})()"

    /** 幂等注入；页面每次加载完成后调用一次即可（内部自带重试与状态同步）。 */
    fun applySidebarFab(wv: WebView?) {
        wv?.evaluateJavascript(SIDEBAR_FAB_JS, null)
    }
}
