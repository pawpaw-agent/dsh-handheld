package com.dshhandheld.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多主机的三条关键规则：编解码、新增/就地更新、删除后当前项归谁。
 *
 * 为什么值得测：这三条决定的都是「用户的配置会不会丢 / 会不会多出副本」——
 * 真机上踩过一次（「存为新主机」总是新增，改一下当前主机就多一条副本）。
 */
class SshHostsTest {

    private fun host(id: String, host: String = "192.168.0.135", name: String = "") =
        SshConfig(id = id, name = name, host = host, user = "xsj", password = "pw")

    @Test
    fun `列表编解码往返`() {
        val list = listOf(host("a", name = "家里"), host("b", host = "10.0.0.2"))
        assertEquals(list, SshHosts.parseList(SshHosts.encodeList(list)))
    }

    @Test
    fun `空或非法 JSON 得到空列表`() {
        assertTrue(SshHosts.parseList(null).isEmpty())
        assertTrue(SshHosts.parseList("").isEmpty())
        assertTrue(SshHosts.parseList("不是 JSON").isEmpty())
        assertTrue(SshHosts.parseList("[]").isEmpty())
    }

    @Test
    fun `坏的那条跳过，其余照收（逐条容错）`() {
        val raw = """[{"id":"a","sshHost":"1.1.1.1","sshUser":"u"}, "坏元素", {"id":"b","sshHost":"2.2.2.2","sshUser":"u"}]"""
        val out = SshHosts.parseList(raw)
        assertEquals(2, out.size)
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test
    fun `id 为空时新增，用传入的 newId`() {
        val out = SshHosts.merge(listOf(host("a")), host("").copy(host = "10.0.0.9"), "generated")
        assertEquals(2, out.size)
        assertEquals("generated", out.last().id)
        assertEquals("10.0.0.9", out.last().host)
    }

    @Test
    fun `id 已存在时就地更新，不产生副本`() {
        val before = listOf(host("a", name = "旧的"), host("b"))
        val out = SshHosts.merge(before, host("a", name = "新的"), "shouldNotBeUsed")
        assertEquals(2, out.size)                                   // 没多出第三条
        assertEquals("新的", out.first { it.id == "a" }.name)
        assertEquals("b", out.last().id)                            // 顺序不变
    }

    @Test
    fun `删除后当前项的归属`() {
        val list = listOf(host("a"), host("b"), host("c"))
        // 删的不是当前项 → 当前项不动
        assertEquals("b", SshHosts.activeAfterRemove(list, "c", "b"))
        // 删的是当前项 → 接上第一条
        assertEquals("a", SshHosts.activeAfterRemove(list, "b", "b"))
        // 删光了 → 置空
        assertEquals("", SshHosts.activeAfterRemove(emptyList(), "a", "a"))
    }
}
