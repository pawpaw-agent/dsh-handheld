package com.dshhandheld.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「是不是我们的隧道页面」的判定。
 *
 * 为什么值得测：判错的后果是**功能坏掉而不是报错** —— 判宽了，任意页面都能申请麦克风；
 * 判窄了，麦克风被拒（真机第一版就是被 `PermissionRequest.origin` 的**尾斜杠**
 * 判成了非隧道 origin，日志里留下「拒绝（origin=http://127.0.0.1:3080/）」）。
 */
class TunnelOriginTest {

    @Test
    fun `带尾斜杠的 origin 是隧道（真机踩过的那一条）`() {
        assertTrue(TunnelOrigin.isTunnel("http://127.0.0.1:3080/"))
    }

    @Test
    fun `不带尾斜杠的 origin 也是隧道`() {
        assertTrue(TunnelOrigin.isTunnel("http://127.0.0.1:3080"))
    }

    @Test
    fun `整条 URL 也是隧道`() {
        assertTrue(TunnelOrigin.isTunnel("http://127.0.0.1:3080/?token=abc"))
        assertTrue(TunnelOrigin.isTunnel("http://127.0.0.1:3080/api/remote.mux"))
    }

    @Test
    fun `非本机、非 http、端口不在候选集的一律不是`() {
        assertFalse(TunnelOrigin.isTunnel("http://192.168.0.135:3080/"))   // 局域网地址
        assertFalse(TunnelOrigin.isTunnel("https://127.0.0.1:3080/"))      // 不是 http
        assertFalse(TunnelOrigin.isTunnel("http://127.0.0.1:9999/"))       // 端口不在候选集
        assertFalse(TunnelOrigin.isTunnel("http://localhost:3080/"))       // 不是字面 127.0.0.1
        assertFalse(TunnelOrigin.isTunnel("about:blank"))
        assertFalse(TunnelOrigin.isTunnel(""))
        assertFalse(TunnelOrigin.isTunnel("不是 URL"))
    }

    @Test
    fun `scheme 大小写不敏感`() {
        assertTrue(TunnelOrigin.isTunnel("HTTP://127.0.0.1:3080/"))
    }

    @Test
    fun `origins 覆盖全部端口候选且不带尾斜杠`() {
        val origins = TunnelOrigin.origins()
        assertTrue(origins.isNotEmpty())
        assertTrue(origins.all { it.startsWith("http://127.0.0.1:") && !it.endsWith("/") })
        assertEquals(origins.size, origins.map { it }.toSet().size)   // 无重复
    }
}
