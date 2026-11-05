package com.dshhandheld.app

import android.webkit.WebView
import com.dshhandheld.diag.DiagLog

/**
 * 页面内的移动端适配（用户 2026-10-30 要求「在 app 端注入」；2026-11-05 大幅精简）。
 *
 * ## 为什么只剩这么点
 * dsh 0.2.2 起自带移动端 UI：侧栏变成滑入式抽屉、内容区不再位移、顶栏自带开关按钮、
 * 会话行自带「归档 / 置顶」按钮（只是被 CSS 藏起来，悬停才显示）。
 * 实测 384x832 零注入：抽屉 280px 滑入、内容区 x 恒为 0。
 *
 * 因此原先那一大套（栅格改写、悬浮按钮、遮罩、消息 padding、逐帧优化）全部删除，
 * 避免与宿主原生实现打架（例如两个开关按钮、两套动画互相覆盖）。
 *
 * ## 保留的两件事（宿主没有）
 * 1. 展开时全屏：宿主写死 280px，用户明确要求全屏，故覆盖为 100vw。
 *    只改外层不够 —— 里层面板 hHd-Xa_root 也写死 280px，必须一起撑满，
 *    否则右边会留一条底色（2026-10-31 真机实测：外层 384、内层 280）。
 * 2. 让会话行的「归档 / 置顶」按钮常显：宿主默认 display:none（悬停才出），
 *    手机没有悬停，等于永远看不到。
 *
 * ## 关键约束（踩过的坑，别重犯）
 * 1. 注入 JS 拼好后是一整行：里面绝对不能出现行注释（会注释掉后面所有代码）。
 * 2. Kotlin 注释里不要写带双引号的示例：校验脚本按双引号抓字面量，会把示例拼进 JS。
 * 3. Kotlin 块注释会嵌套：正文里出现斜杠星号会吞掉后面的代码。
 * 4. transform 必须 !important：宿主给同一元素写内联 transform，普通规则会被压掉。
 * 5. 只有「侧栏列真实存在」时才置 dshhh-open：欢迎页没有侧栏，否则只有遮罩没有抽屉。
 */

object WebInject {

    /**
     * 幂等注入；页面每次加载完成后调用一次即可（内部自带重试与状态同步）。
     *
     * 注入结果写进 DiagLog —— evaluateJavascript 默认会把语法/运行时错误静默吞掉，
     * 没有这行日志就只能靠猜。
     */
    private const val MOBILE_SIDEBAR_JS = "" +
        "(function () {" +
        "  try {" +
        "    var V = \"m1\";" +
        "    if (window.__dshhhSidebar === V) return \"already\";" +
        "    window.__dshhhSidebar = V;" +
        "" +
        "    var NL = String.fromCharCode(10);" +
        "    var CSS = [" +
        "      \"[class*=sidebarCol]{position:relative;width:100vw !important;max-width:none !important;transform:translateX(-101%) !important;transition:transform .22s ease !important;will-change:transform}\"," +
        "      \"html.dshhh-open [class*=sidebarCol]{transform:translateX(0) !important}\"," +
        "      \"[class*=sidebarCol] > *{width:100% !important;max-width:none !important}\"," +
        "      \"[class*=sidebarCol] [class*=hHd-Xa_root]{width:100% !important;max-width:none !important}\"," +
        "      \"[class*=rowQuickActions] button{display:flex !important}\"," +
        "      \"[class*=rowResting]{margin-right:2px !important;min-width:40px !important;display:inline-flex !important;justify-content:flex-end !important}\"" +
        "    ].join(NL);" +
        "" +
        "    function col() { return document.querySelector(\"[class*=sidebarCol]\"); }" +
        "    function rail() { return document.querySelector(\"[class*=hHd-Xa_root]\"); }" +
        "" +
        "    function sync() {" +
        "      var c = col();" +
        "      var r = rail();" +
        "      var open = !!c && !!r && r.className.toString().indexOf(\"collapsed\") < 0;" +
        "      document.documentElement.classList.toggle(\"dshhh-open\", open);" +
        "    }" +
        "" +
        "    function start() {" +
        "      if (!document.getElementById(\"dshhh-mobile-style\")) {" +
        "        var st = document.createElement(\"style\");" +
        "        st.id = \"dshhh-mobile-style\";" +
        "        st.textContent = CSS;" +
        "        document.head.appendChild(st);" +
        "      }" +
        "      sync();" +
        "      new MutationObserver(sync).observe(document.documentElement, { attributes: true, subtree: true, attributeFilter: [\"class\"] });" +
        "      setInterval(sync, 300);" +
        "      return true;" +
        "    }" +
        "" +
        "    if (!document.body || !document.head) return \"no-dom\";" +
        "    return start() ? \"ok\" : \"fail\";" +
        "  } catch (e) {" +
        "    window.__dshhhSidebar = null;" +
        "    return \"error: \" + (e && e.message ? e.message : String(e));" +
        "  }" +
        "})()"

    fun applySidebarFab(wv: WebView?) {
        wv?.evaluateJavascript(MOBILE_SIDEBAR_JS) { result ->
            DiagLog.i("DshHandheld", "inject: 移动端适配 → " + result)
        }
    }
}
