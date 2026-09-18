package com.dshhandheld.protocol

import android.webkit.CookieManager
import com.dshhandheld.diag.DiagLog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Base64

/**
 * 订阅 dsh Host 的**权威**回合状态，用来在页面被系统冻住时也能发出「任务完成」通知。
 *
 * ## 为什么需要它（2026-09-17 真机诊断）
 *
 * 此前的完成判据全靠页面：插件观察 `[class*="_turnStatus"]` 的出现/消失。但 WebView 的渲染
 * 是**独立子进程**（`dumpsys` 里那串 `SandboxedProcessService`，不可见时是 `WPRI`），它被系统
 * waive/冻结后 JS 停跑、DOM 不再更新 —— 实测退到后台超过 ~30s 后，页面连一次心跳都没有，
 * 「这一轮结束了」这个 DOM 变化**从未发生**。于是通知时有时无（只在退到后台 30s 内结束的那次
 * 有幸命中）。`setRendererPriorityPolicy(IMPORTANT, false)` 也没能救回来。
 *
 * 而 Host 自己一直在发状态跃迁（这一条与页面无关）：
 *
 * ```
 * 客户端 → {"type":"open","streamId":"…","endpoint":"$events","payload":{"args":{}}}
 * 服务端 → {"type":"item","value":{"type":"ready","clientId":"…","host":{"home":"…"}}}
 * 服务端 → {"type":"item","value":{"type":"emit","event":"api-session/status",
 *                                   "args":["session-…", false]}}      ← [会话, 是否在跑]
 * ```
 *
 * 上面三条是 2026-09-17 在本机（手机 SSH 的目标就是它）用同样帧打出来的实测结果：观察器跨越
 * 一轮的结束，在**页面被冻着**的情况下收到了 `running=false`。所以本类做的事就是：连
 * `/api/remote.mux`，开 `$events`，把 `api-session/status` 转给 [onStatus]。
 *
 * ## 为什么不加依赖
 *
 * 只用到一条只读的 WS 连接：握手 + 文本帧 + Ping/Pong。项目本来就在裸 `Socket` 上做隧道
 * （[SshTunnel]），为这一件事引 OkHttp（≈1MB）不划算，所以这里按 RFC 6455 写最小实现 ——
 * 只支持本用例需要的部分，其余显式拒绝（见 [readFrame]）。
 *
 * ## 认证
 *
 * `/api/remote.mux` 从回环也是 401，必须带 browser-session cookie。App 不用自己换 token：
 * WebView 已经换好了，直接把它的 jar 读出来带上即可（[cookieFor]）。
 */
class HarnessEventsClient(
    /** 隧道基址，形如 `http://127.0.0.1:3080`。 */
    private val baseUrl: String,
    /** 收到 `api-session/status` 时回调：`(sessionId, running)`。在客户端线程上调用。 */
    private val onStatus: (String, Boolean) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: Socket? = null

    fun start() {
        if (thread != null) return
        stopped.set(false)
        thread = Thread({ runLoop() }, "harness-events").apply {
            isDaemon = true
            start()
        }
    }

    /** 幂等停止：关 socket 让读循环退出。 */
    fun stop() {
        stopped.set(true)
        runCatching { socket?.close() }
        socket = null
        thread = null
    }

    private fun runLoop() {
        var backoffMs = 3_000L
        while (!stopped.get()) {
            try {
                connectAndPump()
                backoffMs = 3_000L
            } catch (e: Exception) {
                if (stopped.get()) return
                DiagLog.w(TAG, "事件流断开：${e.javaClass.simpleName}: ${e.message}（${backoffMs}ms 后重连）")
            }
            if (stopped.get()) return
            try {
                Thread.sleep(backoffMs)
            } catch (_: InterruptedException) {
                return
            }
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun connectAndPump() {
        val uri = java.net.URI(baseUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port > 0) uri.port else 80
        val cookie = cookieFor(baseUrl)
        if (cookie.isNullOrBlank()) {
            // 还没换到 cookie（页面尚未完成 token 交换）—— 等下一轮重连，不是错误。
            throw IllegalStateException("尚无 dsh-auth cookie（页面还没完成 token 交换）")
        }

        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 5_000)
        socket = s
        val out = s.getOutputStream()
        val input = s.getInputStream()

        handshake(out, input, host, port, cookie)
        DiagLog.i(TAG, "事件流已连接：$baseUrl/api/remote.mux")
        sendText(out, """{"type":"open","streamId":"handheld-1","endpoint":"$eventsEndpoint","payload":{"args":{}}}""")

        while (!stopped.get()) {
            val frame = readFrame(input) ?: break
            when (frame.opcode) {
                OP_TEXT -> handleText(frame.payload)
                OP_PING -> sendControl(out, OP_PONG, frame.payload)
                OP_PONG -> Unit
                OP_CLOSE -> {
                    DiagLog.i(TAG, "事件流被服务端关闭")
                    return
                }
                else -> DiagLog.w(TAG, "事件流：忽略 opcode=${frame.opcode}")
            }
        }
    }

    /** ready 帧给的客户端身份（回 waterfall 结果时要带上）。 */
    @Volatile private var clientId: String? = null

    /**
     * 对 waterfall 事件回一个「我不处理」的结果。
     *
     * 协议（`dsh-client-connection` 的 unary 桥）：
     * ```
     * POST /api/$events/result   {"type":"client-request","rpcId":…,
     *                             "method":"$events/result",
     *                             "payload":{"args":{"clientId":…,"eventId":…,
     *                                                "outcome":{"kind":"next"}}}}
     * ```
     * 响应里带 `ok`；失败只记日志 —— 不回话才是问题，回话失败最坏也只是让 Host 继续等，
     * 与不做的后果一样，所以不值得把读循环搞复杂。
     */
    private fun answerWaterfallNext(eventId: String) {
        val id = clientId
        if (id.isNullOrEmpty() || eventId.isEmpty()) {
            DiagLog.w(TAG, "waterfall 事件缺少 clientId/eventId，无法回话（eventId=$eventId）")
            return
        }
        val body = "{\"type\":\"client-request\",\"rpcId\":\"" + java.util.UUID.randomUUID()
            .toString() + "\",\"method\":\"\$events/result\",\"payload\":{\"args\":{"
            + "\"clientId\":\"" + id + "\",\"eventId\":\"" + eventId
            + "\",\"outcome\":{\"kind\":\"next\"}}}}"
        val resp = postJson("/api/\$events/result", body)
        DiagLog.i(TAG, "waterfall 已回 next（eventId=$eventId）${if (resp != null) "" else "（响应读取失败）"}")
    }

    /** 最小 HTTP/1.1 POST（只给上面的 RPC 用；返回响应体或 null）。 */
    private fun postJson(path: String, json: String): String? = runCatching {
        val uri = java.net.URI(baseUrl)
        val host = uri.host ?: "127.0.0.1"
        val port = if (uri.port > 0) uri.port else 80
        val cookie = cookieFor(baseUrl).orEmpty()
        Socket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5_000)
            val out = s.getOutputStream()
            val bytes = json.toByteArray(Charsets.UTF_8)
            val req = "POST $path HTTP/1.1\r\nHost: $host:$port\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                (if (cookie.isNotEmpty()) "Cookie: $cookie\r\n" else "") +
                "Connection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
            s.getInputStream().bufferedReader(Charsets.UTF_8).readText()
        }
    }.getOrNull()

    private fun handleText(bytes: ByteArray) {
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        // 一帧可能是 ready / emit / waterfall / cancel / error，这里只关心状态跃迁。
        if (json.optString("type") != "item") return
        val value = json.optJSONObject("value") ?: return
        when (value.optString("type")) {
            "ready" -> {
                clientId = value.optString("clientId")
                DiagLog.i(TAG, "事件流就绪：clientId=$clientId")
            }
            "emit" -> {
                if (value.optString("event") != STATUS_EVENT) return
                val args = value.optJSONArray("args") ?: return
                if (args.length() < 2) return
                val sessionId = args.optString(0)
                val running = args.optBoolean(1, false)
                DiagLog.i(TAG, "Host 报告回合状态：session=$sessionId running=$running")
                runCatching { onStatus(sessionId, running) }
                    .onFailure { DiagLog.w(TAG, "onStatus 回调抛异常：${it.message}") }
            }
            // waterfall 事件（approval/request、user-questions/request）**必须回话**：
            // Host 会等这个客户端的 result 才继续（`forwardWaterfall` 等 dispatch 结算）。
            // 本客户端不处理它们（那是页面那个客户端的事），但必须明确回一个
            // `{kind:"next"}` —— 表示「我不处理，交给下一个」。不回会让审批/提问卡在
            // 我们这条连接上（这是审计之后新增订阅时唯一真正的行为风险）。
            "waterfall" -> answerWaterfallNext(value.optString("eventId"))
            else -> Unit
        }
    }

    // ── 最小 WebSocket（RFC 6455）：只实现本用例需要的子集 ──────────────────

    private class Frame(val opcode: Int, val payload: ByteArray)

    private fun handshake(out: OutputStream, input: InputStream, host: String, port: Int, cookie: String) {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val req = buildString {
            append("GET $muxPath HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Origin: http://$host:$port\r\n")
            append("Cookie: $cookie\r\n")
            append("\r\n")
        }
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        // 只读到响应头结束：101 就是成功，401/其他一律当失败（重连会退避）。
        val head = readHttpHead(input)
        val statusLine = head.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 101")) {
            throw IllegalStateException("WebSocket 升级失败：$statusLine")
        }
    }

    private fun readHttpHead(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0 // 匹配 \r\n\r\n
        while (true) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("握手期间连接被关闭")
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
            if (buf.size() > 16 * 1024) throw IllegalStateException("握手响应头过大")
        }
        return buf.toString("ISO-8859-1")
    }

    /** 读一帧；EOF 返回 null。服务端帧不掩码，但这里也接受掩码帧（防御性）。 */
    private fun readFrame(input: InputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0L
            repeat(8) { len = (len shl 8) or input.read().toLong() }
        }
        // 事件都是小帧；给个上限，避免坏帧把内存吃光。
        if (len > MAX_FRAME) throw IllegalStateException("帧过大：$len")
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(len.toInt()).also { input.readFully(it) }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return Frame(opcode, payload)
    }

    private fun sendText(out: OutputStream, text: String) = sendControl(out, OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** 客户端发出的帧**必须**掩码（RFC 6455 §5.3）。 */
    private fun sendControl(out: OutputStream, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode)
        when {
            payload.size < 126 -> header.write(0x80 or payload.size)
            payload.size < 65536 -> {
                header.write(0x80 or 126)
                header.write((payload.size shr 8) and 0xFF)
                header.write(payload.size and 0xFF)
            }
            else -> throw IllegalArgumentException("控制帧过大")
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        header.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        out.write(header.toByteArray())
        out.write(masked)
        out.flush()
    }

    private fun InputStream.readFully(dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = read(dst, off, dst.size - off)
            if (n < 0) throw IllegalStateException("帧读取中断")
            off += n
        }
    }

    companion object {
        private const val TAG = "DshEvents"
        private const val muxPath = "/api/remote.mux"
        private const val eventsEndpoint = "\$events"
        private const val STATUS_EVENT = "api-session/status"
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_FRAME = 4 * 1024 * 1024

        /** 从 WebView 的 cookie jar 里取 browser-session cookie（页面已完成 token 交换）。 */
        fun cookieFor(baseUrl: String): String? =
            runCatching { CookieManager.getInstance().getCookie(baseUrl) }.getOrNull()
    }
}