package com.dshhandheld.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接屏交互决策表。
 *
 * 这三条（状态标题、BACK 语义、切主机许可）此前各自内联在三处 UI 代码里，
 * 其中「切主机」漏了连接中守卫 —— 这组测试就是把它固化下来，防止再漏。
 */
class ConnectDecisionsTest {

    private fun facts(
        tunneled: Boolean = false, pageAlive: Boolean = false,
        connecting: Boolean = false, failed: Boolean = false, formOpen: Boolean = false,
    ) = ConnectFacts.of(tunneled, pageAlive, connecting, failed, formOpen)

    // ── A1：状态标题 ────────────────────────────────────────────────

    @Test
    fun `隧道活着时标题是已连上电脑 —— 哪怕上一次重连失败`() {
        // 早先失败标志排在隧道事实前面，于是出现「红字『连不上你的电脑』+
        // 可点的『打开 dsh 网页』」这种自相矛盾的屏。
        assertEquals(HeroState.TUNNELED, ConnectDecisions.hero(facts(tunneled = true, failed = true)))
        // 失败原因仍要能看到，只是降级为一行说明
        assertTrue(ConnectDecisions.showFailureLine(facts(tunneled = true, failed = true)))
    }

    @Test
    fun `连接中优先于一切`() {
        assertEquals(HeroState.CONNECTING, ConnectDecisions.hero(facts(connecting = true)))
        assertEquals(
            HeroState.CONNECTING,
            ConnectDecisions.hero(facts(connecting = true, tunneled = true, failed = true))
        )
    }

    @Test
    fun `没隧道时失败态与空闲态分得开`() {
        assertEquals(HeroState.FAILED, ConnectDecisions.hero(facts(failed = true)))
        assertEquals(HeroState.IDLE, ConnectDecisions.hero(facts()))
        assertFalse(ConnectDecisions.showFailureLine(facts()))
    }

    @Test
    fun `进度块在连接中或失败过时显示`() {
        assertTrue(ConnectDecisions.showProgress(facts(connecting = true)))
        assertTrue(ConnectDecisions.showProgress(facts(failed = true)))
        assertFalse(ConnectDecisions.showProgress(facts(tunneled = true)))
    }

    // ── 主按钮 ────────────────────────────────────────────────────

    @Test
    fun `主按钮动作与文案一一对应`() {
        assertEquals(PrimaryAction.CANCEL_CONNECT, ConnectDecisions.primaryAction(facts(connecting = true)))
        assertEquals("取消连接", ConnectDecisions.primaryLabel(facts(connecting = true)))
        assertEquals(PrimaryAction.OPEN_WEB, ConnectDecisions.primaryAction(facts(tunneled = true, pageAlive = true)))
        assertEquals("打开 dsh 网页", ConnectDecisions.primaryLabel(facts(tunneled = true, pageAlive = true)))
        assertEquals(PrimaryAction.CONNECT, ConnectDecisions.primaryAction(facts(tunneled = true)))
        assertEquals("连上并打开 dsh 网页", ConnectDecisions.primaryLabel(facts(tunneled = true)))
        assertEquals(PrimaryAction.CONNECT, ConnectDecisions.primaryAction(facts()))
    }

    // ── A2：BACK ──────────────────────────────────────────────────

    @Test
    fun `表单展开时 BACK 先收表单`() {
        assertEquals(ConnectBack.COLLAPSE_FORM, ConnectDecisions.backOnConnectScreen(facts(formOpen = true)))
    }

    @Test
    fun `表单收起时 BACK 退到后台`() {
        assertEquals(ConnectBack.TO_BACKGROUND, ConnectDecisions.backOnConnectScreen(facts()))
    }

    @Test
    fun `连接中 BACK 不收表单 —— 取消入口不该被挪走`() {
        assertEquals(
            ConnectBack.TO_BACKGROUND,
            ConnectDecisions.backOnConnectScreen(facts(formOpen = true, connecting = true))
        )
    }

    // ── B1：切主机 ────────────────────────────────────────────────

    @Test
    fun `连接中禁止切主机`() {
        assertFalse(ConnectDecisions.hostSwitchAllowed(facts(connecting = true)))
        // 失败后（不再是 connecting）允许切 —— 用户正需要换一台试
        assertTrue(ConnectDecisions.hostSwitchAllowed(facts(failed = true)))
    }

    @Test
    fun `其余情况都允许切主机`() {
        assertTrue(ConnectDecisions.hostSwitchAllowed(facts()))
        assertTrue(ConnectDecisions.hostSwitchAllowed(facts(tunneled = true, pageAlive = true)))
        assertTrue(ConnectDecisions.hostSwitchAllowed(facts(formOpen = true)))
    }
}
