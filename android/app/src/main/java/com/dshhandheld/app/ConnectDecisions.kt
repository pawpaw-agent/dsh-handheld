package com.dshhandheld.app

/**
 * 连接屏的**交互决策表** —— 纯函数，不碰 Android（可在 JVM 单测里直接跑）。
 *
 * 为什么抽出来：`syncConnectUi` / `onBackPressed` / 主机下拉这三处各自内联了
 * 「多个事实 → 一个动作」的判定，改一处很容易破坏另一处 —— 真发生过：
 * 「连接中不许折叠设置」「连接中不许重复点主按钮」都有守卫，唯独**主机下拉漏了**，
 * 于是连接中切主机会作废在飞的那次拨号、状态条与引导行来回跳。
 *
 * 事实（[ConnectFacts]）由调用方从「唯一真相」取：
 *   tunneled  = `(application as DshApp).liveTunnel() != null`
 *   pageAlive = `webView?.url?.startsWith("http") == true`
 *   connecting= `connectPhase == CONNECTING && !connectFailed`
 *   failed    = `connectFailed`
 *   formOpen  = `connectPhase == EDIT`
 */

/** 状态块：标题 + 圆点颜色语义。 */
enum class HeroState { CONNECTING, TUNNELED, FAILED, IDLE }

/** 主按钮点下去做什么（文案由 [ConnectDecisions.primaryLabel] 给）。 */
enum class PrimaryAction { CANCEL_CONNECT, OPEN_WEB, CONNECT }

/** 连接屏上按 BACK 做什么。 */
enum class ConnectBack { COLLAPSE_FORM, TO_BACKGROUND }

data class ConnectFacts(
    val tunneled: Boolean = false,
    val pageAlive: Boolean = false,
    /** CONNECTING 且**尚未失败**（失败后主按钮要回到「重连」，不再是「取消」）。 */
    val connecting: Boolean = false,
    val failed: Boolean = false,
    /** 表单是否展开（`phase == EDIT`）。 */
    val formOpen: Boolean = false,
) {
    companion object {
        fun of(
            tunneled: Boolean, pageAlive: Boolean,
            connecting: Boolean, failed: Boolean, formOpen: Boolean,
        ) = ConnectFacts(tunneled, pageAlive, connecting, failed, formOpen)
    }
}

object ConnectDecisions {

    /**
     * 状态块的标题。
     *
     * ⚠️ **隧道事实优先**：`tunneled` 成立时标题就是「已连上电脑」，哪怕上一次重连
     * 失败了 —— 早先失败标志排在前面，于是出现过「顶部红字『连不上你的电脑』，
     * 主按钮却是可点的『打开 dsh 网页』」这种自相矛盾的屏。失败原因降级为状态条
     * 里的一行说明（见 [showFailureLine]），不再篡改标题。
     */
    fun hero(f: ConnectFacts): HeroState = when {
        f.connecting -> HeroState.CONNECTING
        f.tunneled -> HeroState.TUNNELED
        f.failed -> HeroState.FAILED
        else -> HeroState.IDLE
    }

    /** 失败原因那行要不要显示（与标题解耦：隧道活着时它仍是有用的一句说明）。 */
    fun showFailureLine(f: ConnectFacts): Boolean = f.failed

    /** 进度块（①②③）要不要显示：连接中或刚失败过。 */
    fun showProgress(f: ConnectFacts): Boolean = f.connecting || f.failed

    /** 主按钮点下去做什么。 */
    fun primaryAction(f: ConnectFacts): PrimaryAction = when {
        f.connecting -> PrimaryAction.CANCEL_CONNECT
        f.tunneled && f.pageAlive -> PrimaryAction.OPEN_WEB
        else -> PrimaryAction.CONNECT
    }

    /** 主按钮文案（与 [primaryAction] 一一对应，别各写一套）。 */
    fun primaryLabel(f: ConnectFacts): String = when (primaryAction(f)) {
        PrimaryAction.CANCEL_CONNECT -> "取消连接"
        PrimaryAction.OPEN_WEB -> "打开 dsh 网页"
        PrimaryAction.CONNECT -> "连上并打开 dsh 网页"
    }

    /**
     * 连接屏上按 BACK 做什么。
     *
     * 表单展开时**先收表单**（Android 惯例：BACK 先退一层 UI），再按一次才退到后台。
     * 早先这里恒为 `moveTaskToBack`，用户想收表单只能去点「收起」。
     * 连接中不收（与「连接设置折叠」同一守卫）：那时屏幕上的取消入口不该被 BACK 挪走。
     */
    fun backOnConnectScreen(f: ConnectFacts): ConnectBack =
        if (f.formOpen && !f.connecting) ConnectBack.COLLAPSE_FORM else ConnectBack.TO_BACKGROUND

    /**
     * 允不允许切主机。
     *
     * 连接中禁止 —— 与「连接设置折叠」「主按钮」**同一判据**。允许的话会
     * `disconnectCurrent()` 作废在飞的那次拨号，再自动重连，用户看到的是状态条与
     * 引导行来回跳。
     */
    fun hostSwitchAllowed(f: ConnectFacts): Boolean = !f.connecting

    /** 隧道不可用时要提醒用户的那句话（网页态横幅与连接屏状态条共用）。 */
    const val TUNNEL_LOST_HINT = "连接已断开，点这里回连接屏"
}
