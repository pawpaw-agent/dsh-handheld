package com.dshhandheld.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ssh_host_chip_pos 的纯逻辑：编解码容错、旋转后的夹紧、松手吸附到哪一侧。
 *
 * 与 HostHealthTest 同一类：这些规则错了不会崩，只是胶囊跑到屏幕外、或者每次重启换个地方 ——
 * 只能在真机上一眼看出来，所以放单测里固定住。坐标都是随手取的整像素，重点在关系不在数值。
 */
class HostChipPosTest {

    /** 竖屏 1080×2340、无系统栏 inset、横向余量 8 / 纵向余量 6。 */
    private val portrait = HostChipPos.area(1080, 2340, 0, 0, 0, 0, 8, 6)

    /** 旋转成横屏：同一条记录要跟着新的尺寸重新夹紧。 */
    private val landscape = HostChipPos.area(2340, 1080, 0, 0, 0, 0, 8, 6)

    private val chipW = 300
    private val chipH = 90

    @Test
    fun `没存过就落在右上角`() {
        val placed = HostChipPos.resolve(null, portrait, chipW, chipH)
        // 右边：1072 - 300；纵向：贴着上余量 6
        assertEquals(772, placed.left)
        assertEquals(6, placed.top)
        assertEquals(HostChipPos.Edge.END, placed.pos.edge)
    }

    @Test
    fun `编解码往返`() {
        assertEquals("end|406", HostChipPos.encode(HostChipPos.Pos(HostChipPos.Edge.END, 406)))
        assertEquals("start|12", HostChipPos.encode(HostChipPos.Pos(HostChipPos.Edge.START, 12)))
        assertEquals(
            HostChipPos.Pos(HostChipPos.Edge.END, 406),
            HostChipPos.parse("end|406")
        )
        assertEquals(
            HostChipPos.Pos(HostChipPos.Edge.START, 12),
            HostChipPos.parse("start|12")
        )
    }

    @Test
    fun `坏记录当作没存过`() {
        assertNull(HostChipPos.parse(null))
        assertNull(HostChipPos.parse(""))
        assertNull(HostChipPos.parse("不是记录"))
        assertNull(HostChipPos.parse("end"))
        assertNull(HostChipPos.parse("end|"))
        assertNull(HostChipPos.parse("end|x"))
        assertNull(HostChipPos.parse("middle|1"))
        assertNull(HostChipPos.parse("end|1|2"))
    }

    @Test
    fun `旋转后仍贴原来那一侧并夹紧纵向`() {
        // 竖屏里存在最底下（2000），横屏高只有 1080 → 纵向要被夹到 984。
        val placed = HostChipPos.resolve(
            HostChipPos.Pos(HostChipPos.Edge.END, 2000), landscape, chipW, chipH
        )
        assertEquals(2032, placed.left)
        assertEquals(984, placed.top)
        assertEquals(984, placed.pos.top)
        // 夹紧后的模型再摆回竖屏：还在右边，纵向仍在可用区间内
        val back = HostChipPos.resolve(placed.pos, portrait, chipW, chipH)
        assertEquals(772, back.left)
        assertEquals(984, back.top)
    }

    @Test
    fun `越界的纵向被夹回可用区`() {
        val above = HostChipPos.resolve(
            HostChipPos.Pos(HostChipPos.Edge.START, -50), portrait, chipW, chipH
        )
        assertEquals(6, above.top)
        val below = HostChipPos.resolve(
            HostChipPos.Pos(HostChipPos.Edge.START, 99999), portrait, chipW, chipH
        )
        assertEquals(2244, below.top)
    }

    @Test
    fun `容器比胶囊还窄时贴住左边`() {
        val narrow = HostChipPos.area(100, 500, 0, 0, 0, 0, 8, 6)
        val placed = HostChipPos.resolve(
            HostChipPos.Pos(HostChipPos.Edge.END, 500), narrow, chipW, chipH
        )
        assertEquals(8, placed.left)
        assertEquals(404, placed.top)
    }

    @Test
    fun `松手按中心吸附到最近的一侧`() {
        val toLeft = HostChipPos.at(10, 100, portrait, chipW, chipH)
        assertEquals(HostChipPos.Edge.START, toLeft.pos.edge)
        assertEquals(8, HostChipPos.resolve(toLeft.pos, portrait, chipW, chipH).left)

        val toRight = HostChipPos.at(900, 100, portrait, chipW, chipH)
        assertEquals(HostChipPos.Edge.END, toRight.pos.edge)
        assertEquals(772, HostChipPos.resolve(toRight.pos, portrait, chipW, chipH).left)
    }

    @Test
    fun `拖动越界时夹在可用区`() {
        val outside = HostChipPos.at(-500, -500, portrait, chipW, chipH)
        assertEquals(8, outside.left)
        assertEquals(6, outside.top)
        val far = HostChipPos.at(5000, 5000, portrait, chipW, chipH)
        assertEquals(772, far.left)
        assertEquals(2244, far.top)
    }

    @Test
    fun `系统栏与刘海的 inset 从四边扣掉`() {
        val a = HostChipPos.area(1080, 2340, 60, 80, 40, 100, 8, 6)
        assertEquals(68, a.left)
        assertEquals(86, a.top)
        assertEquals(1032, a.right)
        assertEquals(2234, a.bottom)
        assertEquals(964, a.width)
    }
}
