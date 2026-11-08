package com.dshhandheld.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ssh_host_health_json 的纯逻辑：容错解析、编解码往返、失败不清掉「上次成功」。
 *
 * 这些规则决定的都是「下次打开弹层时状态点/上次结果是不是在说谎」——
 * 与 SshHostsTest 同一类：错了不报错，只是功能悄悄坏掉。
 */
class HostHealthTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `空或非法 JSON 得到空表`() {
        assertTrue(HostHealth.parse(null).isEmpty())
        assertTrue(HostHealth.parse("").isEmpty())
        assertTrue(HostHealth.parse("不是 JSON").isEmpty())
        assertTrue(HostHealth.parse("[]").isEmpty())
    }

    @Test
    fun `单条坏掉只跳那一条`() {
        val raw = """{"a":{"okAt":1,"err":""},"b":"坏元素","c":{"okAt":2,"err":"连接失败"}}"""
        val out = HostHealth.parse(raw)
        assertEquals(setOf("a", "c"), out.keys)
        assertEquals(2L, out["c"]?.okAt)
        assertEquals("连接失败", out["c"]?.err)
    }

    @Test
    fun `编解码往返`() {
        val records = mapOf(
            "a" to HostHealth.Record(okAt = 123L, err = ""),
            "b" to HostHealth.Record(okAt = 0L, err = "连接失败"),
        )
        assertEquals(records, HostHealth.parse(HostHealth.encode(records)))
    }

    @Test
    fun `失败保留上次成功时间`() {
        val before = mapOf("a" to HostHealth.Record(okAt = 100L, err = ""))
        val fail = HostHealth.noteFail(before, "a", "连接失败")
        assertEquals(100L, fail["a"]?.okAt)
        assertEquals("连接失败", fail["a"]?.err)
        // 再成功一次：失败标记清掉，时间刷新
        val ok = HostHealth.noteOk(fail, "a", 200L)
        assertEquals(200L, ok["a"]?.okAt)
        assertEquals("", ok["a"]?.err)
    }

    @Test
    fun `摘要文案`() {
        assertEquals("", HostHealth.summary(null, now))
        assertEquals("上次失败", HostHealth.summary(HostHealth.Record(0L, "连接失败"), now))
        assertEquals("刚连过 ✓", HostHealth.summary(HostHealth.Record(now - 20_000L, ""), now))
        assertEquals("2 分钟前 ✓", HostHealth.summary(HostHealth.Record(now - 120_000L, ""), now))
        assertEquals("3 小时前 ✓", HostHealth.summary(HostHealth.Record(now - 3 * 3_600_000L, ""), now))
    }
}
