package com.dshhandheld.app

import android.webkit.WebView
import com.dshhandheld.diag.DiagLog

/**
 * WebView 端的界面增强（App 侧注入）。
 *
 * ⚠️ 方向变更记录：2026-10-15 曾确定「零注入、只用原版网页」并移除全部注入层；
 * 2026-10-30 用户明确要求「侧边栏改成悬浮按钮显示，在 app 端注入」，故重新引入，
 * 但**只此一项**：不改页面内容、不改宿主代码，关掉开关即回到原版。
 *
 * 设计要点（均在真机验证，过程与踩坑见 docs/handheld-injection-sidebar.md）：
 *
 * 1. 宿主 `[class*=frame]` 的 `grid-template-columns` 是**内联样式**，但样式表 `!important` 能覆盖；
 *    只把第一列（图标栏）替换成 0，内容列即可吃满整幅宽度。
 * 2. 侧栏用 `position:fixed` 变浮层后**脱离网格流**，其余列会整体前移（内容列被挤成 0、白屏）。
 *    必须显式给 centerCol/rightbarCol/bottomRow/overlayLayer/handle 指定 grid-column。
 * 3. **不能**把整条 grid-template 写死成 `0 1fr 0` —— 那会把宿主的右侧面板一起压成 0。
 * 4. 开合状态**完全跟随宿主**（读 rail 的 `collapsed` 类），不自己维护状态机。
 * 5. 切换动作 = 对宿主自己的「打开/收起侧边栏」按钮派发 click，不直接改宿主状态。
 * 6. **不要**在 JS 里写 `[aria-label="..."]` 这类带引号的选择器 —— Kotlin/JS 双层转义极易写错
 *    （踩过：生成出 `"[aria-label=\" + X + "\"]"`，整段变成一个字符串）。改为遍历
 *    `[aria-label]` 逐个比较文本，彻底不需要转义。
 * 7. 注入结果通过 `evaluateJavascript` 的回调写进 DiagLog —— 否则语法/运行时错误会被静默吞掉。
 * 8. **选择器不要带 CSS 哈希前缀**（如 `pI_x6G_frame`）：哈希随宿主构建变化，
 *    dsh 每次升级都可能全变。统一用 `[class*=名字]` 只匹配名字部分。
 */
object WebInject {

    private const val SIDEBAR_FAB_JS =
        "(function () {" +
        "  try {" +
        "    var V = \"v12\";" +
        "    if (window.__dshhhSidebarFab === V) return \"already\";" +
        // 预留的让位开关：若页面（例如服务端插件）已自带窄屏适配并打了
        // data-dsh-mobile-fit 标记，就跳过本注入，避免出现两个悬浮按钮。
        //
        // 背景：曾实现过 dsh 插件版（dsh-mobile-fit）来做同一件事，但用户明确要求
        // 「不要改 dsh」，故已从 profile 完整撤回；此分支保留为空钩子，当前不会触发。
        "    if (document.documentElement.getAttribute(\"data-dsh-mobile-fit\")) {" +
        "      window.__dshhhSidebarFab = \"skipped:plugin\";" +
        "      return \"skipped(plugin)\";" +
        "    }" +
        "    window.__dshhhSidebarFab = V;" +
        "    var LBL_CLOSE = String.fromCharCode(25910,36215,20391,36793,26639);" +
        "    var LBL_OPEN  = String.fromCharCode(25171,24320,20391,36793,26639);" +
        // 只用「名字部分」匹配，不带哈希前缀（宿主升级后哈希会变）
        "    var FRAME = \"[class*=frame]\";" +
        "    var RAIL = \"[class*=hHd-Xa_root]\";" +

        "    var CSS = [" +
        "      \"@media (max-width:520px){[class*=frame]{grid-template-columns:0 minmax(0px,1fr) 0 !important;\"," +
        "      \"  transition:none !important}}\"," +
        "      \"[class*=frame] > [class*=centerCol]{grid-column:2 !important}\"," +
        "      \"[class*=frame] > [class*=rightbarCol]{grid-column:3 !important}\"," +
        "      \"[class*=frame] > [class*=bottomRow]{grid-column:1 / -1 !important}\"," +
        "      \"[class*=frame] > [class*=overlayLayer]{grid-column:1 / -1 !important}\"," +
        "      \"[class*=frame] > [class*=handle]{grid-column:3 !important}\"," +
        "      \"[class*=sidebarCol]{position:fixed !important;left:0;top:0;bottom:0;\"," +
        // 全屏抽屉（2026-10-31 用户要求）：铺满整幅，不再固定 280px。
        "      \"  width:100vw !important;max-width:none !important;z-index:60;background:#17171a;\"," +
        // transform 必须 !important：宿主可能给同一元素写内联 transform（展开/收起动画），
        // 内联样式优先级高于普通规则，会把我们的滑动直接顶掉 —— 表现就是「不走动画，直接闪一下」。
        // will-change/translateZ：把抽屉提到独立合成层，滑动时走合成器，不随主线程掉帧
        // （2026-10-31 真机实测：未加时 21fps，且有 276px 的跳帧）。
        // 去掉 box-shadow：全屏抽屉不需要，且阴影要跟随整块面板每帧重绘，开销很大。
        "      \"  transform:translateX(-102%) !important;transition:transform .18s ease;\"," +
        "      \"  will-change:transform;backface-visibility:hidden;contain:paint;\"," +
        "      \"  display:block !important;overflow:hidden}\"," +
        "      \"[class*=sidebarCol] > *{width:100% !important;max-width:none !important}\"," +
        "      \"[class*=sidebarCol] [class*=hHd-Xa_root]{width:100% !important;max-width:none !important}\"," +
        "      \"html.dshhh-open [class*=sidebarCol]{transform:translateX(0) !important}\"," +
        // 悬浮按钮：最左上角；同时给头部让出左内边距，避免盖住宿主的「智能体团队」入口
        "      \"#dshhh-fab{position:fixed;left:6px;top:6px;width:40px;height:40px;border-radius:20px;\"," +
        "      \"  background:rgba(43,43,48,.92);border:1px solid #3d3d44;z-index:70;\"," +
        "      \"  display:flex;align-items:center;justify-content:center;box-shadow:0 3px 12px rgba(0,0,0,.55)}\"," +
        "      \"header[class*=header]{padding-left:56px !important}\"," +
        // 全屏抽屉铺满后，悬浮按钮会压在面板上；让它随抽屉一起移出（抽屉自带关闭按钮/遮罩）。
        "      \"html.dshhh-open #dshhh-fab{opacity:0;pointer-events:none;transition:opacity .12s}\"," +
        "      \"#dshhh-fab i{display:block;width:18px;height:2px;background:#e6e6ea;border-radius:1px;\"," +
        "      \"  box-shadow:0 -6px 0 #e6e6ea,0 6px 0 #e6e6ea}\"," +
        // 遮罩用 opacity 过渡，不用 display 切换：display 变化会触发整屏重排。
        "      \"#dshhh-scrim{position:fixed;top:0;right:0;bottom:0;left:0;background:rgba(0,0,0,.42);z-index:55;\"," +
        "      \"  opacity:0;pointer-events:none;transition:opacity .18s ease}\"," +
        "      \"html.dshhh-open #dshhh-scrim{opacity:1;pointer-events:auto}\"," +
        "      \"/* 消息区滚动条隐藏（见 tuneMessages 加的 .dshhh-msg） */\"," +
        "      \".dshhh-msg::-webkit-scrollbar{width:0 !important;height:0 !important}\"," +
        "      \".dshhh-msg{scrollbar-width:none !important}\"" +
        "    ].join(String.fromCharCode(10));" +

        "    var MSG_PAD = \"12px\";" +
        "    function tuneMessages() {" +
        "      if (animating()) return;" +
        "      var cols = document.querySelectorAll(\"[class*=column]\");" +
        "      for (var i = 0; i < cols.length; i++) {" +
        "        var c = cols[i];" +
        "        var sc = c.parentElement;" +
        "        if (!sc || !sc.style) continue;" +
        "        var txt = (c.innerText || \"\").length;" +
        "        if (txt < 400) continue;" +
        "        if (!sc.classList.contains(\"dshhh-msg\")) sc.classList.add(\"dshhh-msg\");" +
        "        var base = 12;" +
        "        var diff = innerWidth - sc.getBoundingClientRect().width;" +
        "        if (!(diff >= 0)) diff = 0;" +
        "        var padR = Math.max(4, base - diff);" +
        "        var cs = getComputedStyle(sc);" +
        "        if (cs.paddingLeft === base + \"px\" && cs.paddingRight === padR + \"px\") continue;" +
        "        sc.style.setProperty(\"padding-left\", base + \"px\", \"important\");" +
        "        sc.style.setProperty(\"padding-right\", padR + \"px\", \"important\");" +
        "      }" +
        "    }" +

        "    function rail() { return document.querySelector(RAIL); }" +
        "    function hostOpen() { var r = rail(); return !!r && r.className.toString().indexOf(\"collapsed\") < 0; }" +
        "    function sync() { document.documentElement.classList.toggle(\"dshhh-open\", hostOpen()); }" +

        // 找宿主侧栏开关：遍历 [aria-label] 比较文本，避免带引号的选择器
        "    function findToggle() {" +
        "      var els = document.querySelectorAll(\"[aria-label]\");" +
        "      for (var i = 0; i < els.length; i++) {" +
        "        var a = els[i].getAttribute(\"aria-label\");" +
        "        if (a === LBL_CLOSE || a === LBL_OPEN) return els[i];" +
        "      }" +
        "      return null;" +
        "    }" +

        // 只把第一列改成 0，其余列保留宿主原值（否则右侧面板会被压没）
        // ⚠️ 注释必须写在 Kotlin 层；若把行注释写进字符串字面量，拼出来的 JS
        // 是**一整行**，该注释会把它后面所有代码全部注释掉 → 语法错误（2026-10-31 踩过）。
        // 同时，注释文字里也不要出现成对的双引号：校验脚本按双引号抓字面量，会把示例也拼进去。
        "    var animUntil = 0;" +
        "    function animating() { return Date.now() < animUntil; }" +
        "    function markAnim() { animUntil = Date.now() + 450; }" +
        "    function fixGrid() {" +
        // 窄屏（手机）由 CSS @media 用 !important 全权接管，JS 完全不需要介入。
        "      if (innerWidth <= 520 || animating()) return;" +
        "      var f = document.querySelector(FRAME);" +
        "      if (!f || !f.style || !f.style.gridTemplateColumns) return;" +
        "      var raw = f.style.gridTemplateColumns;" +
        "      var parts = raw.split(/\\s+/);" +
        "      if (parts.length < 2) return;" +
        "      parts[0] = \"0px\";" +
        "      var want = parts.join(\" \");" +
        "      if (raw !== want) f.style.setProperty(\"grid-template-columns\", want, \"important\");" +
        "    }" +

        "    function toggleHost() {" +
        "      var t = findToggle();" +
        "      if (!t) return false;" +
        "      markAnim();" +
        "      t.click();" +
        "      setTimeout(sync, 60);" +
        "      return true;" +
        "    }" +

        "    function build() {" +
        "      if (!document.body || !document.head) return false;" +
        "      if (!document.getElementById(\"dshhh-sidebar-style\")) {" +
        "        var st = document.createElement(\"style\"); st.id = \"dshhh-sidebar-style\";" +
        "        st.textContent = CSS; document.head.appendChild(st);" +
        "      }" +
        "      if (!document.getElementById(\"dshhh-fab\")) {" +
        "        var fab = document.createElement(\"div\"); fab.id = \"dshhh-fab\";" +
        "        fab.setAttribute(\"role\", \"button\"); fab.setAttribute(\"aria-label\", \"sidebar-fab\");" +
        "        fab.innerHTML = \"<i></i>\";" +
        "        fab.addEventListener(\"click\", function (e) { e.preventDefault(); e.stopPropagation(); toggleHost(); });" +
        "        document.body.appendChild(fab);" +
        "      }" +
        "      if (!document.getElementById(\"dshhh-scrim\")) {" +
        "        var sc = document.createElement(\"div\"); sc.id = \"dshhh-scrim\";" +
        "        sc.addEventListener(\"click\", function (e) { e.preventDefault(); e.stopPropagation(); toggleHost(); });" +
        "        document.body.appendChild(sc);" +
        "      }" +
        "      return true;" +
        "    }" +

        // 观察器只绑一次；延迟构建成功后也会绑定（上一版在这里漏了，导致注入后无任何效果）
        "    var started = false;" +
        "    function start() {" +
        "      if (!build()) return false;" +
        "      if (started) return true;" +
        "      started = true;" +
        // 观察器挂在 documentElement 的整棵子树（只过滤 class 变化）。
        //
        // 为什么不再直接观察 rail 节点（2026-10-31 逐帧实测的教训）：
        // 宿主展开侧栏时会**替换** rail 那个节点，我们持有的引用立刻变成游离节点，
        // 观察器从此再也不触发；只能等 setInterval 兜底（最长 500ms）。
        // 实测「dshhh-open 生效」因此晚约 200ms —— 这段时间栅格已经被宿主拉到 280px，
        // 内容被推走后再滑回来，就是用户看到的跳变。
        "      var obs = new MutationObserver(function () { sync(); });" +
        "      obs.observe(document.documentElement, {attributes:true, subtree:true, attributeFilter:[\"class\"]});" +
        "      var fObs = new MutationObserver(function () { fixGrid(); });" +
        "      var boundFrame = null;" +
        "      setInterval(function () {" +
        "        var f = document.querySelector(FRAME);" +
        "        if (f && f !== boundFrame) { fObs.disconnect(); fObs.observe(f, {attributes:true, attributeFilter:[\"style\"]}); boundFrame = f; }" +
        "        fixGrid(); sync(); tuneMessages();" +
        "      }, 250);" +
        "      fixGrid(); sync(); tuneMessages();" +
        "      return true;" +
        "    }" +

        "    if (start()) return \"ok\";" +
        "    var tries = 0;" +
        "    var timer = setInterval(function () { tries++; if (start() || tries > 60) clearInterval(timer); }, 250);" +
        "    return \"deferred\";" +
        "  } catch (e) {" +
        "    window.__dshhhSidebarFab = null;" +
        "    return \"error: \" + (e && e.message ? e.message : String(e));" +
        "  }" +
        "})()"

    /**
     * 幂等注入；页面每次加载完成后调用一次即可（内部自带重试与状态同步）。
     *
     * 注入结果写进 DiagLog —— `evaluateJavascript` 默认会把语法/运行时错误静默吞掉，
     * 没有这行日志就只能靠猜（2026-10-30 因此多绕了一圈）。
     */
    fun applySidebarFab(wv: WebView?) {
        wv?.evaluateJavascript(SIDEBAR_FAB_JS) { result ->
            DiagLog.i("DshHandheld", "inject: 侧栏悬浮按钮 → " + result)
        }
    }
}
