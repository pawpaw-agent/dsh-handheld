package com.dshhandheld.protocol

import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.dshhandheld.diag.DiagLog

/**
 * SSH 本地端口转发 —— dbclient 进程方案。
 *
 * 与终端模式（TuiActivity）共用同一我们 CI 编译的 dropbear dbclient：
 * 密码/证书/TOFU（-y）/known_hosts（HOME）走完全一致的环境变量与参数，
 * 不再引入第二套 SSH 实现（JSch 依赖移除后，认证行为 100% 统一）。
 *
 * 转发实现：`dbclient -p <sshPort> -y -q -N -o ExitOnForwardFailure=yes
 *   -o BatchMode=yes -L 127.0.0.1:<localPort>:<remoteHost>:<remotePort> user@host`
 *  - `-N`：不执行远程命令（纯隧道）
 *  - `-o ExitOnForwardFailure=yes`：本地转发 bind 失败（端口被占）时**进程退出**，
 *    而不是继续活着假装在工作。这是"隧道死了但看起来连着"的来源之一。
 *  - `-o BatchMode=yes`：永不读 stdin 等提示（无 tty 时任何提问都会永久挂住）。
 *    未知的 `-o` 选项只会打 warning 不会退出，所以这两条对旧 dropbear 也安全。
 *  - `-L`：本地转发。注意 dropbear 的 -L 端口 0 **不自分配**（JSch 有此能力，
 *    dbclient 没有），因此本地端口由本类预选（bind(0) 拿系统分配的空闲端口）。
 *  - ⚠️ **没有** `-T` 连接超时：dropbear 的 `-T` 是"不分配 pty"（见 cli-runopts.c），
 *    不是超时。连接超时由 [waitForLocal] 的失败路径实现（杀进程重试）。
 *  - 断线：dbclient 进程退出 → 看门狗检测 → 重建（新端口 → onLocalBaseChanged）。
 *
 * ## 健康判定：真流量，不是"端口能连"
 *
 * [isHealthy] 与看门狗都用 [probeDataPlane]：**往转发口发一个真实 HTTP 请求**，
 * 收到任意 HTTP 状态行才算通。只探测"127.0.0.1:<port> 能 connect"是不够的 ——
 * 监听 socket 可能属于一个已被替换下来、SSH 通道已死的残留进程，那种情况下 connect
 * 立刻成功、真正的请求却永远没有回应，于是隧道"看起来连着"但页面永远转圈（实测过）。
 * 同一个探针同时就是这个隧道的 keepalive（周期性制造双向流量）。
 *
 * 隧道远端固定是 dsh web 的 HTTP 端点（[com.dshhandheld.app.SshConfig.DEFAULT_REMOTE_PORT]），
 * 所以用 HTTP 判定健康是成立的，也是用户真正关心的那个条件。
 *
 * ## 进程所有权：单一 owner + 全量回收
 *
 * [spawned] 记录派生过的每一个 dbclient，重建前先把旧 owner 回收掉：残留进程会一直
 * 占着本地端口（导致新进程 bind 失败、并让端口从 3080 漂到 13080，进而改变 WebView
 * 的 origin），也会让健康检查被"冒名者"骗过。
 *
 * 令牌获取（[execOnce]）：同样用 dbclient 一次性进程执行
 * `dbclient -y -q -K 3 user@host "<cmd>"` 收集 stdout（命令模式无 pty）。
 *
 * 对外契约与旧 JSch 版一致（start/localBaseUrl/onStateChange/
 * onLocalBaseChanged/close/execOnce），调用方无需改动。
 *
 * dbclient 路径：DshApp.onCreate 通过 [binPath] 注入（Application 有
 * applicationInfo.nativeLibraryDir；SshTunnel 自身无 Context）。
 */
class SshTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val remoteHost: String,
    private val remotePort: Int,
    private val auth: Auth,
    /**
     * 本地转发端口候选（按序取第一个空闲的）。**必须稳定**，理由见 [pickFreePort]。
     *
     * 默认 [PORT_CANDIDATES]（3080 优先）。目前只有 DshApp 一处调用
     * （[com.dshhandheld.app.DshApp.ensureTunnel]），WebView 与后台通知共用同一条
     * 隧道，因此进程内就是一个端口。
     */
    private val preferredPorts: List<Int> = PORT_CANDIDATES,
) : Closeable {

    sealed class Auth {
        data class Password(val password: String) : Auth()
        data class KeyPair(val privateKeyFile: File, val passphrase: String? = null) : Auth()
    }

    private val started = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    /** 当前 owner：唯一被认作"我们的隧道"的 dbclient。 */
    @Volatile private var proc: Process? = null

    /**
     * 派生过的**全部** dbclient（含已被替换下来的）。不回收就会泄漏：每个残留进程
     * 都占着一条 SSH 会话，并且可能继续监听着本地端口，让新进程 bind 失败
     * （实测 "Address already in use"），同时让健康检查被"冒名者"骗过。
     */
    private val spawned: MutableList<Process> = Collections.synchronizedList(mutableListOf())

    @Volatile private var localPort: Int = 0

    /** 连续多少次重建都没能拿到可用隧道 —— 用于退避，别对着 SSH 服务端打连环拳。 */
    @Volatile private var consecutiveFailures: Int = 0

    private var reconnectThread: Thread? = null

    /** 隧道就绪后手机侧访问的本地基址（http://127.0.0.1:<port>）。 */
    @Volatile var localBaseUrl: String? = null
        private set

    @Volatile var onStateChange: ((String) -> Unit)? = null  // connecting / connected / reconnecting

    /**
     * 最近一次失败的原因（dbclient 的真实 stderr，或本类的判定）；成功时不清空、由调用方读走。
     *
     * 为什么需要它：[onStateChange] 在本项目里只被 DshApp 用来记日志，UI 拿不到 —— 于是
     * 「主机身份变了」「私钥读不了」这类**能改变用户下一步**的失败，全都塌缩成一句
     * 「连不上你的电脑（检查地址/账号/密码）」，把用户引向错误的方向。
     * 这个字段是给调用方把原因带到界面上的通道（见 `DshApp.lastTunnelError` 与
     * `MainActivity.tunnelFailureHint`）。
     */
    @Volatile var lastError: String? = null
        private set

    /** 记下失败原因并广播状态；**所有失败路径都必须走这里**，别再直接 invoke。 */
    private fun fail(msg: String) {
        lastError = msg
        onStateChange?.invoke("failed: $msg")
    }

    /** 本地端口/基址变化（断线重连后会更换端口）；调用方需据此重新加载。 */
    @Volatile var onLocalBaseChanged: ((String) -> Unit)? = null

    /** 建立隧道并阻塞等待本地监听就绪（最多 [LOCAL_READY_TIMEOUT_MS] × [MAX_ATTEMPTS]）。成功返回本地端口。 */
    @Synchronized
    fun start(): Int {
        if (started.get()) return localPort
        started.set(true)
        connectOnce()
        // 守护：周期性探真流量（兼 keepalive），连续失败才判定不健康并重建
        reconnectThread = Thread {
            var misses = 0
            while (started.get()) {
                val up = isHealthy()
                if (up) {
                    misses = 0
                    consecutiveFailures = 0
                } else {
                    misses++
                    if (misses >= PROBE_MISS_LIMIT) {
                        misses = 0
                        onStateChange?.invoke("reconnecting")
                        if (connecting.compareAndSet(false, true)) {
                            try {
                                if (!connectOnce()) {
                                    consecutiveFailures++
                                    // 重建失败：**基址不能留着**。原先刻意不清（为了「重建后端口相同、
                                    // origin 不变」），但那是成功路径的理由；失败时留着它就等于对外
                                    // 宣布一个已经不通的地址（审计 H5：界面因此显示「已连上电脑」、
                                    // 点进去是死页面，且只有切前后台才自愈）。
                                    localBaseUrl = null
                                }
                            } finally {
                                connecting.set(false)
                            }
                        }
                    }
                }
                val sleepMs = when {
                    up -> PROBE_INTERVAL_MS
                    consecutiveFailures > 0 -> backoffMs()
                    else -> PROBE_RETRY_MS
                }
                try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "ssh-tunnel-watchdog"; start() }
        return localPort
    }

    /**
     * **非阻塞**的存活判据：owner 进程还在、本地监听端口还占着。
     *
     * 与 [isHealthy] 的分工：那个要发真流量（最长阻塞 4.5s，只能后台线程用），
     * 这个只是读两个字段 —— 给 UI 判「现在到底有没有一条活着的隧道」用。
     *
     * 为什么需要它（2026-09-17 审计 H5）：界面原先的判据是 `sshTunnel != null`，
     * 而看门狗重建失败时 `sshTunnel` 与 `localBaseUrl` 都不会变 —— 于是「隧道已死」
     * 被稳定地显示成「已连上电脑」，点进去就是死页面。
     */
    val isUp: Boolean
        get() = started.get() && localPort > 0 && proc?.isAlive == true

    /**
     * 隧道是否可用：owner 存活 **且真的能过流量**（[probeDataPlane]）。
     *
     * 供复用判定：DshApp 只在「配置未变且健康」时把已有隧道交给下一个调用方，否则
     * 重建；MainActivity 回到前台时也用它决定要不要重连。因此这里必须用真探针 ——
     * 只看"端口能连"会把一条已经死掉的隧道判成健康，然后继续复用它（实测过）。
     *
     * ⚠️ 会阻塞到探针超时（正常几毫秒，异常最多 [PROBE_TIMEOUT_MS]），
     * 调用方必须在后台线程调用。
     */
    fun isHealthy(): Boolean {
        val p = proc ?: return false
        if (!p.isAlive) return false
        val port = localPort
        return port > 0 && probeDataPlane(port)
    }

    /**
     * 真流量探针：往本地转发口发一个最小 HTTP 请求，收到任意 HTTP 状态行即算通
     * （dsh web 对无令牌请求回 401，同样算通）。
     *
     * 为什么不能用"能 connect"代替：本地监听可能属于一个已被替换、SSH 通道已死的
     * 残留进程 —— connect 立刻成功，请求却永远没有回应。这正是网页模式
     * "看起来连着、页面永远转圈"的成因。
     *
     * 同时它就是这个隧道的 keepalive：周期性的真实双向流量，比 SSH 层的空 keepalive
     * 更能防止链路被中间设备（NAT / WiFi 省电）静默丢弃。
     */
    private fun probeDataPlane(port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), PROBE_CONNECT_MS)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(("GET / HTTP/1.0\r\nHost: 127.0.0.1:$port\r\n"
                    + "Connection: close\r\n\r\n").toByteArray())
                flush()
            }
            val buf = ByteArray(16)
            var n = 0
            // 单次 read 可能只回来 "HT"，读到够判定的字节数或流结束为止
            while (n < 5) {
                val r = s.getInputStream().read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
            }
            n >= 5 && String(buf, 0, 5, Charsets.US_ASCII) == "HTTP/"
        }
    } catch (_: Exception) { false }

    /** 重建连续失败后的退避：OpenSSH 9.8+ 默认开着 PerSourcePenalties，别打连环拳。 */
    private fun backoffMs(): Long = when (consecutiveFailures) {
        0, 1 -> 5_000L
        2 -> 15_000L
        else -> 45_000L
    }

    /**
     * 选本地转发端口：按 [PORT_CANDIDATES] 顺序取第一个空闲的（**不用随机端口**）。
     *
     * 为什么端口必须稳定：WebView 的 origin 是 `http://127.0.0.1:<port>`，端口一变
     * origin 就变，localStorage / IndexedDB / cache 全部另起一份。实测随机端口时
     * 重连后上一 origin 的 localStorage 读不到（探针 key 返回 null），每条会话的
     * 草稿/视图状态丢失，旧 origin 的 ~4.5KB 还永久留在 leveldb 里。服务端 cookie
     * 名又含 authority（`dsh-auth-` + sha256(authority)），端口固定后浏览器直接
     * 覆盖而不是累积（HTTP 431 那次的根因）。
     *
     * 首选 3080 与 dsh web 的默认端口一致，撞车时退到 13080。
     *
     * ⚠️ 实测过的漂移：残留 dbclient 占着 3080 时会退到 13080，origin 随之改变 ——
     * 所以 [connectOnce] 必须在选端口前先回收旧 owner，否则端口稳定性无从谈起。
     */
    private fun pickFreePort(): Int {
        for (p in preferredPorts) {
            if (isPortFree(p)) {
                if (p != preferredPorts.first()) {
                    DiagLog.w(TAG, "本地端口 ${preferredPorts.first()} 被占用，改用 $p")
                }
                return p
            }
        }
        // 明显异常状态（候选端口都被占）→ 显式报错，不偷偷换随机端口（换了 origin 就变）
        DiagLog.e(TAG, "本地端口候选 ${preferredPorts.joinToString()} 全部被占用")
        return -1
    }

    /** 试绑即释放。dbclient -L 不接受端口 0，必须先定下具体端口（存在极小抢注竞态）。 */
    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }

    private fun buildArgs(bin: String, port: Int): List<String> {
        val args = mutableListOf(
            bin,
            "-p", sshPort.toString(),
            "-y",                 // 首连接受未知主机公钥（TOFU，与终端模式一致）
            "-q",                 // 静默 remote banner/日志
            // ⚠️ 不用 -K <n>：dropbear keepalive 依赖服务端应答，而本部署的
            // OpenSSH 10.0p2 不回答其 keepalive（实测 90s=30×3 后
            // "Keepalive timeout"），隧道必死、页面永远加载不完。
            // 默认 0 = 永不超时（与电脑端普通 ssh -L 行为一致），
            // 链路是否还活着由 [probeDataPlane] 的周期性真实流量判定。
            "-N",                 // 纯隧道，不执行远程命令
            // bind 失败（端口被占）→ 直接退出，而不是继续活着假装在工作
            "-o", "ExitOnForwardFailure=yes",
            // 绝不读 stdin 等提示（无 tty 时会永久挂住）
            "-o", "BatchMode=yes",
            "-L", "127.0.0.1:$port:$remoteHost:$remotePort",
        )
        if (auth is Auth.KeyPair) {
            args += listOf("-i", auth.privateKeyFile.absolutePath)
            auth.passphrase?.takeIf { it.isNotEmpty() }?.let {
                DiagLog.w(TAG, "key passphrase unsupported by dbclient CLI; try without")
            }
        }
        args += "$sshUser@$sshHost"
        return args
    }

    /** @return 是否拿到了可用的隧道（供看门狗统计连续失败次数）。 */
    private fun connectOnce(): Boolean {
        onStateChange?.invoke("connecting")
        val bin = binPath
        if (bin == null) {
            DiagLog.e(TAG, "dbclient path not set — DshApp.onCreate should inject it")
            fail("内部错误（dbclient 路径未设置）")
            return false
        }
        // 先回收旧 owner：固定端口要立刻能重绑，而且不能让残留进程冒名监听
        killCurrent()
        for (attempt in 1..MAX_ATTEMPTS) {
            if (!started.get()) return false
            val port = pickFreePort()
            if (port < 0) {
                onStateChange?.invoke(
                    "failed: 本地端口 ${preferredPorts.joinToString(" / ")} 都被占用，请关掉占用它的应用后重试")
                return false
            }
            val args = buildArgs(bin, port)
            DiagLog.i(TAG, "dbclient tune #$attempt: ${args.joinToString(" ").take(160)}")
            val sink = LogSink()
            val p = try {
                val pb = ProcessBuilder(args).redirectErrorStream(true)
                pb.environment().clear()
                baseEnv().forEach { (k, v) -> pb.environment().put(k, v) }
                pb.start()
            } catch (e: Exception) {
                DiagLog.w(TAG, "dbclient launch failed: ${e.message}")
                if (attempt == MAX_ATTEMPTS) fail(e.message ?: "dbclient 启动失败")
                continue
            }
            spawned.add(p)
            pumpLogs(p, sink)
            // 端口就绪 = TCP 连上 + 认证通过（dropbear 在会话建立后才 bind -L）。
            // 这一步同时充当连接超时：卡死的 connect 实测要 3 分钟以上，
            // 而正常连上只要 0.5–3s（链路吃紧时见过 9–14s）。
            val ready = waitForLocal(port, p, LOCAL_READY_TIMEOUT_MS)
            if (!ready || !p.isAlive) {
                val state = if (p.isAlive) "alive" else "exited rc=${runCatching { p.exitValue() }.getOrNull()}"
                val err = sink.text()
                DiagLog.w(TAG, "dbclient tune #$attempt 未就绪（$state）: ${err.take(200)}")
                reap(p)
                if (attempt == MAX_ATTEMPTS) {
                    // err 是 dbclient 的**真实输出**（认证失败 / host key mismatch / 私钥读不了…），
                    // 原样交给调用方：它是唯一能区分这些故障的东西。
                    fail(err.ifEmpty { "连接超时（$state）" })
                }
                continue
            }
            // 等待期间隧道可能已被 close()（用户在连接屏重连 / 换了配置）——
            // 那就别再认领这个进程，否则它会在新隧道旁边抢端口
            if (!started.get()) {
                DiagLog.i(TAG, "隧道已关闭，放弃本次连接结果")
                reap(p)
                return false
            }
            // 端口能连还不够：确认隧道真的能把流量送出去再收回来
            if (!probeDataPlane(port)) {
                DiagLog.w(TAG, "dbclient tune #$attempt 端口就绪但探针无响应，丢弃重试")
                reap(p)
                if (attempt == MAX_ATTEMPTS) {
                    fail(
                        "本地端口已就绪，但对 dsh 的请求无响应"
                            + "（隧道可能已断，或电脑上的 dsh 没在运行）")
                }
                continue
            }
            // 探针最长 4.5s，期间用户可能已经「断开连接」（= close()）——发布前**再复查一次**
            // （审计 M10：原先那次复查在探针之前，覆盖不到这个窗口；命中后会把一条已经作废的
            // 隧道认领回来，「断开」被后台结果逆转）。
            if (!started.get()) {
                DiagLog.i(TAG, "探针期间隧道已被关闭，放弃本次连接结果")
                reap(p)
                return false
            }
            proc = p
            localPort = port
            val base = "http://127.0.0.1:$port"
            val changed = localBaseUrl != base
            val first = localBaseUrl == null
            localBaseUrl = base
            onStateChange?.invoke("connected")
            if (changed && !first) onLocalBaseChanged?.invoke(base)
            return true
        }
        return false
    }

    /** 轮询本地端口可连 + 进程存活，等待隧道就绪。返回进程是否仍存活。 */
    private fun waitForLocal(port: Int, p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            if (try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                true
            } catch (_: Exception) { false }) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /**
     * 消费 dbclient 输出（-q 后信息很少，认证/转发错误行仍会打印），避免管道阻塞，
     * 同时把最近几行留在 [sink] 里供失败诊断。
     *
     * 为什么不让调用方直接读 `p.inputStream`：那是阻塞读到 EOF 的，进程还活着
     * （连接卡住）时会把调用线程永久挂住。
     */
    private fun pumpLogs(p: Process, sink: LogSink) {
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine {
                    sink.append(it)
                    DiagLog.i(TAG, "dbclient: $it")
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "dbclient-log"; start() }
    }

    /** 最近一次 dbclient 尝试的输出（有上限，读它不会阻塞）。 */
    private class LogSink {
        private val sb = StringBuilder()
        @Synchronized fun append(line: String) {
            if (sb.length < SINK_LIMIT) sb.append(line).append('\n')
        }
        @Synchronized fun text(): String = sb.toString().trim()
        private companion object { const val SINK_LIMIT = 4_000 }
    }

    /** 杀掉一个 dbclient 并等它真正退出（destroy 是异步的，端口要等进程没了才释放）。 */
    private fun reap(p: Process) {
        spawned.remove(p)
        if (proc === p) {
            proc = null
            localPort = 0
        }
        try {
            p.destroy()
            if (!p.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS)) {
                DiagLog.w(TAG, "dbclient 未在 ${REAP_WAIT_MS}ms 内退出，强制杀")
                p.destroyForcibly()
                p.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {}
    }

    /** 回收当前 owner（刻意不碰 [localBaseUrl]：重建后端口相同时 origin 不变）。 */
    private fun killCurrent() {
        proc?.let { reap(it) }
        proc = null
        localPort = 0
    }

    /** 回收**全部**派生过的 dbclient —— 只收最后一个会留下占着端口和 SSH 会话的幽灵。 */
    private fun killAll() {
        val all = synchronized(spawned) { spawned.toList() }
        for (p in all) reap(p)
        proc = null
        localPort = 0
    }

    /** 与终端模式一致的认证 env（HOME 写 known_hosts；密码经 DROPBEAR_PASSWORD）。 */
    private fun baseEnv(): Map<String, String> {
        val m = mutableMapOf(
            // 必须与终端模式同一个 HOME（TuiActivity 用 filesDir），否则 TOFU 信任
            // 会分成两份 known_hosts：隧道接受过的公钥，终端模式下还要再接受一次。
            "HOME" to (homeDir ?: "/data/data/com.dshhandheld.app"),
            "TERM" to "xterm-256color"
        )
        if (auth is Auth.Password) m["DROPBEAR_PASSWORD"] = auth.password
        return m
    }

    /**
     * 在远端执行一条命令并收集 stdout（一次性 dbclient 进程，跑完即断）。
     * 命令模式：无 pty（不分配 tty），远端命令结束后 dbclient 自动退出。
     * 超时后杀进程返回已收集内容；失败返回 null。
     */
    fun execOnce(cmd: String, timeoutMs: Long = 8_000): String? {
        val bin = binPath ?: return null
        val args = mutableListOf(
            bin,
            "-p", sshPort.toString(),
            // -K 必须 ≥1：dbclient 拒绝 -K 0（"Bad keepalive '0'"），
            // 实测认证后命令都不执行——token 自动获取因此全失败（401）。
            "-y", "-q", "-K", "3",
        )
        if (auth is Auth.KeyPair) args += listOf("-i", auth.privateKeyFile.absolutePath)
        args += listOf("$sshUser@$sshHost", cmd)
        // `p` 声明在 try **外面**（审计 L1）：异常路径（线程被中断等）原先直接 return null，
        // 把这个子进程留到远端命令结束或 keepalive 超时（~90s）；而它又从不进 `spawned`，
        // `killAll()` 也够不着它。
        var p: Process? = null
        return try {
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment().clear()
            baseEnv().forEach { (k, v) -> pb.environment().put(k, v) }
            val proc = pb.start()
            p = proc
            val out = StringBuffer()
            val reader = Thread {
                try { proc.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
                catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (proc.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(100)
            if (proc.isAlive) {
                // 超时也要**确实**收掉：留着的话它占着一条 SSH 会话不下来
                proc.destroy()
                if (!proc.waitFor(1_000, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            }
            reader.join(500)
            out.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            DiagLog.w(TAG, "execOnce failed: ${e.message}")
            runCatching {
                p?.let { if (it.isAlive) { it.destroy(); if (!it.waitFor(1_000, TimeUnit.MILLISECONDS)) it.destroyForcibly() } }
            }
            null
        }
    }

    override fun close() {
        started.set(false)
        // join 一下（审计 M10）：原先只 interrupt，看门狗可能正好在 connectOnce 里，
        // 于是 close 返回之后它还会把结果发布出来 —— 「断开必须赢」就又漏了一个窗口。
        // close() 的调用方都在后台线程（DshApp.closeTunnel 起 worker、ensureTunnel 在拨号线程）。
        reconnectThread?.let { th ->
            th.interrupt()
            runCatching { th.join(WATCHDOG_JOIN_MS) }
                .onFailure { Thread.currentThread().interrupt() }
        }
        reconnectThread = null
        killAll()
        localBaseUrl = null
    }

    companion object {
        private const val TAG = "SshTunnel"

        /** 单次尝试里"连接 + 认证 + 本地监听就绪"的等待上限。 */
        private const val LOCAL_READY_TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 3
        private const val REAP_WAIT_MS = 1_500L

        /** `close()` 等看门狗收尾的上限（审计 M10）。 */
        private const val WATCHDOG_JOIN_MS = 1_000L

        /** 隧道 keepalive / 健康检查周期。 */
        private const val PROBE_INTERVAL_MS = 30_000L
        /** 判定不健康后的短重试间隔。 */
        private const val PROBE_RETRY_MS = 3_000L
        /** 连续失败达到这个次数才拆掉重建（避开单次抖动）。 */
        private const val PROBE_MISS_LIMIT = 2
        private const val PROBE_CONNECT_MS = 1_500
        private const val PROBE_TIMEOUT_MS = 3_000

        /** WebView 用的本地转发端口候选（按序取第一个空闲的）：3080 与 dsh web
         *  默认端口一致，被占时退到 13080。端口必须稳定，理由见 [pickFreePort]。 */
        val PORT_CANDIDATES = listOf(3080, 13080)

        /** dbclient 可执行文件路径：由 DshApp.onCreate 注入（nativeLibraryDir/libdbclient.so）。 */
        @Volatile
        var binPath: String? = null

        /**
         * dbclient 的 `HOME`：同样由 DshApp.onCreate 注入（`filesDir`）。
         *
         * 必须与终端模式（TuiActivity 的 `filesDir`）指向同一处，否则 `known_hosts`
         * 会分两份，同一主机的 TOFU 信任要在两个模式里各接受一次。
         */
        @Volatile
        var homeDir: String? = null

    }
}
