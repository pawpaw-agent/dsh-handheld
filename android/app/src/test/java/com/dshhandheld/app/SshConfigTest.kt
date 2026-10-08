package com.dshhandheld.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SshConfig` 的序列化契约。
 *
 * 为什么值得测：这份 JSON 是**用户唯一保存的连接信息**，而且多主机迁移要在新旧格式之间
 * 来回解析 —— 键名写错、默认值写错，表现都是「保存了但没生效」这类静默失败。
 */
class SshConfigTest {

    private val full = SshConfig(
        id = "abc12345",
        name = "家里的台式机",
        host = "192.168.0.135",
        port = 2222,
        user = "xsj",
        remoteHost = "127.0.0.1",
        remotePort = 3080,
        authType = SshConfig.AUTH_PASSWORD,
        password = "pw",
    )

    @Test
    fun `JSON 往返保留全部连接字段`() {
        val back = SshConfig.fromJson(full.toJson())
        assertEquals(full, back)
    }

    @Test
    fun `私钥方式的往返`() {
        val key = full.copy(
            authType = SshConfig.AUTH_KEY, password = "",
            keyPath = "/sdcard/id_ed25519", keyPass = "secret",
        )
        val back = SshConfig.fromJson(key.toJson())
        assertEquals(key, back)
        assertEquals(true, back.usesKey)
    }

    @Test
    fun `旧格式（没有 id 与 name 两个键）解析取默认值`() {
        // 老版本存的就是这几个键 —— 多主机迁移必须能读它
        val legacy = """{"sshHost":"10.0.0.2","sshPort":22,"sshUser":"u",
            "remoteHost":"127.0.0.1","remotePort":3080,"authType":"password","password":"p"}"""
        val cfg = SshConfig.parse(legacy)
        assertEquals("10.0.0.2", cfg?.host)
        assertEquals("", cfg?.id)
        assertEquals("", cfg?.name)
        assertEquals("u@10.0.0.2", cfg?.label)   // 没有 name → 用 user@host 兜底
    }

    @Test
    fun `fingerprint 不含 id 与 name —— 改显示名不该重建隧道`() {
        val renamed = full.copy(name = "换了个名字")
        val reidentified = full.copy(id = "ffffffff")
        assertEquals(full.fingerprint(), renamed.fingerprint())
        assertEquals(full.fingerprint(), reidentified.fingerprint())
        // 但连接字段变了必须变（否则会错误复用旧隧道）
        assertNotEquals(full.fingerprint(), full.copy(host = "10.0.0.9").fingerprint())
        assertNotEquals(full.fingerprint(), full.copy(port = 22).fingerprint())
        assertNotEquals(full.fingerprint(), full.copy(password = "other").fingerprint())
    }

    @Test
    fun `isComplete 只要求地址与账号`() {
        assertEquals(true, full.isComplete)
        assertEquals(false, full.copy(host = "").isComplete)
        assertEquals(false, full.copy(user = "").isComplete)
        assertEquals(true, full.copy(password = "").isComplete)   // 密码可以后补
    }

    @Test
    fun `parse 对非法输入返回 null`() {
        assertNull(SshConfig.parse(null))
        assertNull(SshConfig.parse(""))
        assertNull(SshConfig.parse("不是 JSON"))
    }

    @Test
    fun `私钥方式缺路径时 toAuth 返回 null（调用方据此判定不可用）`() {
        assertNull(full.copy(authType = SshConfig.AUTH_KEY, keyPath = "").toAuth())
    }
}
