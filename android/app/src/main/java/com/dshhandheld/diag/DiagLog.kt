package com.dshhandheld.diag

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue

/**
 * 机内取证：把 App 自己的日志留在手机上，出问题时不用电脑也能看。
 *
 * ## 为什么必须自己记，而不是去读 logcat
 *
 * 1. `READ_LOGS` 是 `signature|privileged` 权限，**普通应用读不到** logcat；
 * 2. 就算读得到也没用：logcat 只是内存里的环形缓冲（实测这台机器 `main` 只有 5 MiB），
 *    而三星的 `View.setRequestedFrameRate` 在 WebView 持续重绘时以 **662 条/10 秒**
 *    （约 1.1 MB/分钟）刷屏 —— 5 MiB 撑不到 5 分钟，我们的隧道日志会被冲得一条不剩。
 *    （用 `adb shell setprop log.tag.View W` 可以压掉那个刷屏，见 `docs/known-issues.md`。）
 *
 * 这正是「出问题时没有证据」这个缺口的成因，也是这个类存在的全部理由。
 *
 * ## 它怎么工作
 *
 * - [i] / [w] / [e] **同时**走 `android.util.Log`（adb 行为完全不变）并记一份到内存；
 * - 内存里是最近 [RING_CAP] 条的环形缓冲 —— 应用内查看器的数据源；
 * - 另有 `diag-log` 写线程把同样的行追加到 `filesDir/[FILE_NAME]`，**重启不丢**；
 * - 单文件超 [FILE_CAP] 时，启动时把上一份旋转成 `[FILE_NAME].1`（只留一代）。
 *
 * 写入刻意**不在调用线程**做：日志点里有主线程的生命周期回调，同步 append+flush 会引入卡顿。
 * 队列满就丢弃并计数（诊断日志永远不该影响主流程）。
 */
object DiagLog {
    private const val TAG = "DiagLog"
    private const val FILE_NAME = "diag.log"
    private const val RING_CAP = 600
    private const val FILE_CAP = 256 * 1024L
    private const val QUEUE_CAP = 4096
    private const val TAIL_BYTES = 48 * 1024

    data class Entry(val ts: Long, val level: Char, val tag: String, val msg: String)

    private val clock: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val ring = ArrayDeque<Entry>()
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAP)

    @Volatile private var writer: Thread? = null
    /** 磁盘写入是否已经中断（审计 L3）：写线程因 IO 异常退出后，页头不能再说「队列满」。 */
    @Volatile private var diskFailed = false
    @Volatile private var dir: File? = null
    @Volatile private var ringCleared = 0
    private var dropped = 0

    /**
     * 上一次进程为什么没了（由 Application 在启动时填入）。
     * 数据来自系统落盘的 `ApplicationExitInfo` —— 那是唯一**应用自己读得到**的持久记录。
     */
    @Volatile var lastExitSummary: String? = null

    private fun line(e: Entry): String =
        "${clock.format(Instant.ofEpochMilli(e.ts))} ${e.level}/${e.tag}: ${e.msg}"

    /** 由 `Application.onCreate` 调用一次。失败也只是退化成「只有内存缓冲」。 */
    fun init(filesDir: File) {
        if (writer != null) return
        dir = filesDir
        try {
            val f = File(filesDir, FILE_NAME)
            if (f.exists() && f.length() > FILE_CAP) {
                val prev = File(filesDir, "$FILE_NAME.1")
                prev.delete()
                f.renameTo(prev)
            }
            val out = FileOutputStream(f, /* append = */ true).bufferedWriter()
            writer = Thread({ pump(out) }, "diag-log").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            // 用平台 Log 直写，避免走本类造成递归
            android.util.Log.w(TAG, "diag.log 打不开，只保留内存缓冲: ${e.message}")
        }
    }

    private fun pump(out: Writer) {
        try {
            while (true) {
                out.append(queue.take()).append('\n')
                // 批量：把此刻已排队的都写完再 flush。日志是突发式的，
                // 这样常态下每次 flush 覆盖一整批，而不是每行一次。
                while (true) {
                    val more = queue.poll() ?: break
                    out.append(more).append('\n')
                }
                out.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            android.util.Log.w(TAG, "diag.log 写入中断: ${e.message}")
            // 置位并清 writer（审计 L3）：否则此后每行照旧入队、4096 行后 dropped 增长，
            // 诊断页只说「队列满丢弃 N 条」—— 真正的原因（写线程死了）永远看不到。
            diskFailed = true
            writer = null
        }
    }

    private fun record(level: Char, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, redact(msg))
        synchronized(ring) {
            if (ring.size >= RING_CAP) ring.removeFirst()
            ring.addLast(e)
        }
        if (writer != null && !queue.offer(line(e))) dropped++
        // writer == null 时不再入队：diskFailed 已在 stats() 里单独说明
    }

    /**
     * dsh 浏览器 token 的打码正则（token 是 32 字节 base64url ⇒ 至少 40 字符，这里放宽到 16）。
     *
     * 为什么必须兜底：`connectWeb` 把 token 拼进 URL，而 WebView 的当前 URL 就是那个带 token 的
     * URL —— 任何打印 URL 的日志都会顺带带上整条凭据（2026-09-17 审计 H2：它落进
     * `filesDir/diag.log`（重启不丢）、上诊断页、还能被「复制全部」带走，与代码里那句
     * 「token 本身不记，只记长度」直接矛盾）。
     *
     * 打在 [record] 这一处**唯一的收口**上：内存环形缓冲与磁盘文件都走它，以后新加的日志
     * 也不会再漏。幂等（打码后不再匹配）。
     */
    private val TOKEN_RE = Regex("""(?i)\btoken=[A-Za-z0-9_\-.]{16,}""")

    /** 把文本里的 `token=…` 打码成 `token=***`（幂等）。 */
    fun redact(text: String): String = if (text.contains("token=")) TOKEN_RE.replace(text, "token=***") else text

    fun i(tag: String, msg: String) { val m = redact(msg); Log.i(tag, m); record('I', tag, m) }
    fun w(tag: String, msg: String) { val m = redact(msg); Log.w(tag, m); record('W', tag, m) }
    fun e(tag: String, msg: String) { val m = redact(msg); Log.e(tag, m); record('E', tag, m) }

    /**
     * 平台 `Log` 的 Throwable 重载也必须照抄 —— 少一个就是**编译期**才发现，
     * 而且只有真正用了 3 参数那个调用点会报错（0.1.6 的 CI 就栽在这上面：
     * 已移除的 `TuiActivity.kt:334` 的 `Log.e(TAG, "...", e)`）。
     */
    fun w(tag: String, msg: String, tr: Throwable) {
        val m = redact(msg); Log.w(tag, m, tr); record('W', tag, withTrace(m, tr))
    }
    fun e(tag: String, msg: String, tr: Throwable) {
        val m = redact(msg); Log.e(tag, m, tr); record('E', tag, withTrace(m, tr))
    }

    /** 堆栈并进同一条目（缩进续行），免得「一行一条」的日志看起来像是别人打的。 */
    private fun withTrace(msg: String, tr: Throwable): String =
        msg + " ← " + Log.getStackTraceString(tr).trimEnd().replace("\n", "\n    ")

    /** 内存环形缓冲（本次运行）的全部内容。 */
    fun snapshot(): String = synchronized(ring) {
        ring.joinToString("\n") { line(it) }
    }

    /** 环形缓冲的占用情况，给查看器做标题。 */
    fun stats(): String {
        val n = synchronized(ring) { ring.size }
        val cl = ringCleared
        val d = dropped
        return buildString {
            append("$n/$RING_CAP 条")
            if (cl > 0) append("，已清空过 $cl 次")
            if (d > 0) append("，队列满丢弃 $d 条")
            if (diskFailed) append("，**磁盘写入已中断**（IO 异常后不再写文件）")
        }
    }

    /**
     * 磁盘上的日志尾部 —— 主要是**上一次运行**留下来的（本次运行的在 [snapshot] 里）。
     * 只读最后 [TAIL_BYTES]，避免为了看一眼日志把整个文件读进内存。
     */
    fun persistedTail(): String {
        // 审计 M18：旋转会把上一代整体 rename 成 `diag.log.1`，而原先**全项目没有任何地方读它**
        // —— 诊断页标着「含上一次运行」，真到崩溃后重启（最需要它的时刻）反而看不到。
        // 现在两代都读，分段标注。
        val prev = dir?.let { File(it, "$FILE_NAME.1") }
        val prevText = prev?.takeIf { it.exists() && it.length() > 0L }?.let { readTail(it) }
        val cur = readTail(dir?.let { File(it, FILE_NAME) })
        return when {
            prevText.isNullOrBlank() -> cur
            cur.isBlank() -> "── 上一代（diag.log.1）──\n$prevText"
            else -> "── 上一代（diag.log.1）──\n$prevText\n\n── 本次（diag.log）──\n$cur"
        }
    }

    private fun readTail(f: File?): String {
        if (f == null) return ""
        return try {
            if (!f.exists() || f.length() == 0L) return ""
            val len = f.length()
            if (len <= TAIL_BYTES) f.readText()
            else RandomAccessFile(f, "r").use { raf ->
                raf.seek(len - TAIL_BYTES)
                val buf = ByteArray(TAIL_BYTES)
                raf.readFully(buf)
                "…（文件较大，只显示最后 ${TAIL_BYTES / 1024} KB）\n" + String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "（读取失败：${e.message}）"
        }
    }

    /** 只清内存缓冲（磁盘文件是追加式的，不在这里动）。 */
    fun clearRing() {
        synchronized(ring) { ring.clear() }
        ringCleared++
        i(TAG, "内存日志缓冲已清空")
    }
}
