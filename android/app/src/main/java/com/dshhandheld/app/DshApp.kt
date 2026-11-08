package com.dshhandheld.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.MutableContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import androidx.annotation.RequiresApi
import com.dshhandheld.diag.DiagLog
import com.dshhandheld.protocol.HarnessEventsClient
import com.dshhandheld.protocol.SshTunnel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application 级单例：持有跨 Activity 存活的 WebView 与 **唯一的 SSH 隧道**。
 *
 * - WebView：跨重建保活，避免重新 loadUrl —— 前端 bundle 不必
 *   重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * - SshTunnel：**所有权在这里**。[ensureTunnel] 是唯一入口，按配置指纹复用；
 *   调用方拿到的都是同一条隧道（此前 MainActivity 与后台通知服务各建一条 =
 *   两个 dbclient / 两个本地端口）。关闭只有 [closeTunnel]（用户手动断开）
 *   或进程结束。
 * - SshTunnel.binPath / SshTunnel.homeDir：dbclient 可执行文件与它的 HOME
 *   （nativeLibraryDir/libdbclient.so、filesDir），SshTunnel 自身无 Context，
 *   由这里注入（方案 B：进程式隧道与终端共用）。
 *
 * 后台行为：**不做保活**（2026-10-31 用户决定）。
 *
 * 曾经有一层前台服务（TunnelService，0.1.8 起）专门让进程不进 cached 队列，
 * 另外还试过 WiFiLock 关 Wi-Fi 省电。两者都已移除 —— 结论是：这个 App 不需要
 * 「后台一直活着」，需要的是**重连可靠**。所以现在的策略是：
 *
 * - 进程被冻结/回收**不视为故障**，回到前台时按需重建隧道；
 * - 重建的时机与判据交给 [SshTunnel] 的看门狗 + 回到前台时的健康检查；
 * - 阈值按真机链路质量放宽（见 SshTunnel 的 PROBE_* 注释），避免把好隧道拆掉。
 *
 * 任务完成通知仍由 [Notifier] + Host 事件流提供，与隧道保活无关。
 */
class DshApp : Application() {

    /** WebView 保留实例。 */
    @Volatile
    var retainedWebView: WebView? = null
        private set

    /**
     * 保活 WebView 的 context 包装器。
     *
     * **为什么需要它**：WebView 必须用 Activity context 创建（WebView 的默认实现要用窗口，
     * 例如页面里的 `confirm()` 会走 WebChromeClient 的默认对话框），但它被本 Application
     * 长期持有。若不处理，**第一个 MainActivity 连同它整棵连接屏视图树**会随进程存活到结束
     * —— Activity 重建（"不保留活动"、多窗口、字号/语言变更）后，旧实例也回收不掉。
     *
     * [MutableContextWrapper] 是官方推荐的解法：WebView 实例与它的全部状态（JS 运行时、
     * localStorage、滚动位置）都保留，但它看到的 base context 可以跟着当前 Activity 走；
     * Activity 销毁时换回 application，陈旧 Activity 即可被回收。
     */
    @Volatile
    private var retainedWebViewContext: MutableContextWrapper? = null

    /**
     * 取得保活 WebView（首次调用时创建），并把它的 context 指向 [activity]。
     *
     * 每次 Activity 重建都要调一次：这正是「换掉 WebView 眼中的 Activity」的时机。
     */
    fun obtainWebView(activity: Activity): WebView {
        val existing = retainedWebView
        val wrapper = retainedWebViewContext
        if (existing != null && wrapper != null) {
            if (wrapper.baseContext !== activity) {
                DiagLog.i(TAG, "obtainWebView: 复用保活实例，context → ${activity.javaClass.simpleName}")
                wrapper.baseContext = activity
            }
            return existing
        }
        DiagLog.i(TAG, "obtainWebView: 首次创建")
        val w = MutableContextWrapper(activity)
        retainedWebViewContext = w
        return WebView(w).also { view ->
            retainedWebView = view
            // 注：2026-09-17 曾在这里加 setRendererPriorityPolicy(IMPORTANT, false) 想让被冻的
            // renderer 活下来 —— 真机实测**没有效果**（后台 6 分钟仍然一条心跳都没有），
            // 却让一个 renderer 进程长期不被 waive。通知现在由 [HarnessEventsClient] 从 Host 的
            // 权威状态拿到，不再依赖页面，所以这里已经撤掉。
        }
    }

    // ── 页面 → App 的消息通道（任务完成通知的触发源）──────────────────────
    /**
     * 是否正在生成。
     *
     * 2026-09-25 起**唯一来源是 Host 事件流**（[HarnessEventsClient] → [onHostTurnStatus]）：
     * 注入层已移除，页面不再自报回合状态。[MainActivity.onPause] 用它决定要不要
     * `pauseTimers()` —— 生成期间暂停定时器会把「结束了」这个信号一起推迟，通知就永远不会响。
     */
    @Volatile
    var pageBusy: Boolean = false
        private set

    // ── 前台判定 ────────────────────────────────────────────────────────
    /**
     * 当前有几个 Activity 处于 started 状态（0 = 用户在别的 App 或熄屏）。
     *
     * 用**计数**而不是布尔：Activity 切换时 `B.onStart` 早于 `A.onStop`，
     * 布尔会被后到的 `A.onStop` 抹成「不在前台」。
     */
    private val visibleActivities = java.util.concurrent.atomic.AtomicInteger(0)

    fun onActivityStarted() {
        val n = visibleActivities.incrementAndGet()
        DiagLog.i(TAG, "Activity started（可见数 $n）")
    }

    fun onActivityStopped() {
        val n = visibleActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        DiagLog.i(TAG, "Activity stopped（可见数 $n）")
    }

    /**
     * Activity 销毁时调用：把 WebView 的 context 换回 application，释放对 Activity 的引用。
     *
     * 与 [obtainWebView] 配对。漏掉这一步，[MutableContextWrapper] 就等于没加。
     */
    fun releaseWebViewContext() {
        retainedWebViewContext?.let {
            if (it.baseContext !== this) {
                DiagLog.i(TAG, "releaseWebViewContext: context → application")
                it.baseContext = this
            }
        }
    }

    /**
     * 当前 SSH 隧道（唯一实例）。只由 [ensureTunnel] / [closeTunnel] 改动；
     * 其他模块只读，**不要自己 close**（会打断 WebView 页面）。
     */
    @Volatile
    var sshTunnel: SshTunnel? = null
        private set

    /**
     * 最近一次拨号失败的原因（**只在 [ensureTunnel] 返回 null 时有意义**；成功路径会清空它）。
     *
     * 数据来自 [SshTunnel.lastError]（dbclient 的真实输出）。为什么要有它：失败原因此前只进
     * DiagLog，UI 上所有失败都塌缩成「连不上你的电脑（检查地址/账号/密码）」，而
     * 「主机身份变了」是**用户下一步动作完全不同**的事 —— 它还需要一个出口
     * （见 `MainActivity.resetKnownHosts`）。
     */
    @Volatile
    var lastTunnelError: String? = null
        private set

    /** 隧道基址变化的订阅者（目前只有 MainActivity 订阅）。 */
    interface TunnelObserver {
        fun onTunnelBaseChanged(base: String) {}

        /**
         * 隧道「可用 ↔ 不可用」变了。
         *
         * 为什么要单独一条（2026-09-17 审计 H5）：看门狗的 `onStateChange` 原先只被
         * [ensureTunnel] 写成一行日志，而界面判「连上没有」用的是 `sshTunnel != null` ——
         * 看门狗重建失败时那个对象还在，于是界面稳定地显示「已连上电脑」、
         * 点进去是死页面，只有切前后台才自愈。
         */
        fun onTunnelAliveChanged(alive: Boolean) {}

        /**
         * 自动重试状态变了（拨号失败 → 进入退避等待 / 开始拨号 / 拨通 / 被取消）。
         *
         * 参数与 [TunnelRetryState] 一一对应：[attempt] = 序列内第几次（**0 = 不在重试**），
         * [nextAtMs] = 下一次拨号的时刻（[SystemClock.elapsedRealtime] 基准；0 = 正在拨号），
         * [lastError] = 最近一次失败原因（dbclient 的原始机器文本，界面自己决定怎么翻译）。
         *
         * **带默认实现**：既有订阅者不必改动（目前只有 MainActivity 一处订阅）。
         */
        fun onRetryStateChanged(attempt: Int, nextAtMs: Long, lastError: String?) {}
    }

    private val tunnelObservers = CopyOnWriteArrayList<TunnelObserver>()

    /**
     * 只保护 [sshTunnel] / [tunnelFingerprint] / [dialGeneration] 这几个字段，临界区永远是
     * 常数级。**主线程会拿它**（「断开连接」按钮 → [closeTunnel]），所以这里绝不能出现
     * 拨号、`reap()` 这类阻塞动作 —— 2026-09-14 的审计就是在这一条上发现 ANR 的。
     */
    private val tunnelLock = Any()

    /** 串行化拨号（同一时刻只允许一条）。拨号是长阻塞（最坏 3×15s），只在后台线程上拿。 */
    private val dialLock = Any()

    private var tunnelFingerprint: String? = null

    /**
     * 拨号代数：每次 [closeTunnel] 自增。拨号开始时记下当时的代数，发布结果前对不上就丢弃
     * —— 用户在拨号期间点了「断开连接」时，这次拨号已经作废，不能把隧道又发布出去
     * （否则「断开」被一个迟到的后台结果逆转）。
     */
    private var dialGeneration = 0L

    /** 正在后台回收旧隧道的线程；下一次拨号前 join 它，端口稳定才不会被自己人白漂一次。 */
    @Volatile
    private var pendingClose: Thread? = null

    fun addTunnelObserver(o: TunnelObserver) {
        if (!tunnelObservers.contains(o)) tunnelObservers.add(o)
    }

    fun removeTunnelObserver(o: TunnelObserver) {
        tunnelObservers.remove(o)
    }

    /**
     * 界面上判「现在有没有一条**活着**的隧道」用这个，不要直接读 [sshTunnel]。
     *
     * 区别在隧道死掉之后：`sshTunnel` 仍指向那个对象（复用判定还要用它），而这里返回 null。
     * 判据是非阻塞的 [SshTunnel.isUp]（进程在 + 端口占着），主线程可以随时调。
     */
    fun liveTunnel(): SshTunnel? = sshTunnel?.takeIf { it.isUp }

    /** 「任务完成」通知开关是否开着（页面在后台是否必须保持活着由它决定，见 MainActivity.onPause）。 */
    fun turnNotifyEnabled(): Boolean = prefs.getBoolean(PREF_NOTIF_TURN, false)

    /**
     * 订阅 Host 权威回合状态的客户端（见 [HarnessEventsClient] 的类注释）。
     *
     * 2026-09-25 注入层移除后，这条事件流是「一轮在不在跑」的**唯一**来源 ——
     * 它同时负责 [pageBusy]（决定后台要不要 pauseTimers）与任务完成通知。
     */
    private var eventsClient: HarnessEventsClient? = null
    private var eventsBase: String? = null

    /**
     * 事件流报告的「正在跑」的会话；null = 当前没有在跑的一轮。
     *
     * 这是**权威**回合状态：页面适配层移除后，它自己就是完整的一路来源。
     */
    @Volatile private var runningSession: String? = null

    /**
     * 按当前隧道同步事件订阅。
     *
     * 三个入口都要调它：隧道起来/关掉、用户改开关、进程重建。独立成一处是为了不出现
     * 「隧道换了端口而订阅还指着旧端口」这种静默失效（3080 ⇄ 13080）。
     *
     * **不再按通知开关决定订不订**：开关只该管「要不要打扰用户」，而 [pageBusy] 关系到
     * 后台会不会被 pauseTimers 冻住 —— 那个判断与开关无关（注入层在时是页面自报，
     * 现在只剩这一条路）。
     */
    fun syncEventsClient() {
        val base = liveTunnel()?.localBaseUrl
        if (base == null) { stopEventsClient(); return }
        if (eventsClient != null && eventsBase == base) return
        stopEventsClient()
        eventsBase = base
        // 具名参数（不能用尾随 lambda）：新增的 onSessionNamed 是最后一个参数，
        // 尾随写法会把它当成 onStatus，编译能过但语义完全错位。
        eventsClient = HarnessEventsClient(
            baseUrl = base,
            onStatus = { sessionId, running -> onHostTurnStatus(sessionId, running) },
            onSessionNamed = { id, name -> sessionNames[id] = name },
        ).also { it.start() }
        DiagLog.i(TAG, "事件流：订阅 $base（权威回合状态）")
    }

    private fun stopEventsClient() {
        eventsClient?.stop()
        eventsClient = null
        eventsBase = null
    }


    /**
     * C1：会话 id → 名字。由事件流的 `api-session/added` 填（见 [HarnessEventsClient]）。
     * 拿不到就不放进来 —— 通知侧退回「id 后 6 位」，不编造名字。
     */
    private val sessionNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun onHostTurnStatus(sessionId: String, running: Boolean) {
        if (running) {
            if (runningSession == sessionId) return
            runningSession = sessionId
            pageBusy = true
            DiagLog.i(TAG, "事件流报告回合开始（session=$sessionId，pageBusy=true）")
            return
        }
        if (runningSession != sessionId) return
        runningSession = null
        pageBusy = false
        val foreground = visibleActivities.get() > 0
        DiagLog.i(TAG, "事件流报告回合结束（session=$sessionId，前台=$foreground）")
        if (!turnNotifyEnabled()) return
        if (foreground) { DiagLog.i(TAG, "App 在前台，不发通知"); return }
        // C1：会话名现在由事件流的 `api-session/added` 提供（注入层移除后 App 自己订）。
        // 取不到就退回「会话 …后6位」—— 多会话时至少能区分是哪一条跑完了，
        // 比恒定的「回到 App 看结果」有用；也绝不显示空名。
        val label = sessionNames[sessionId]?.takeIf { it.isNotBlank() }
            ?: "会话 …${sessionId.takeLast(6)}"
        DiagLog.i(TAG, "回合完成通知：会话=$sessionId 标签=$label（有名字=${sessionNames.containsKey(sessionId)}）")
        Notifier.turnDone(this, label)
    }

    private fun notifyTunnelAlive(alive: Boolean) {
        DiagLog.i(TAG, "隧道可用性：alive=$alive（通知 ${tunnelObservers.size} 个观察者）")
        tunnelObservers.forEach { o ->
            runCatching { o.onTunnelAliveChanged(alive) }
                .onFailure { DiagLog.w(TAG, "观察者 onTunnelAliveChanged 抛异常：${it.message}") }
        }
    }

    /**
     * 网络恢复时立刻唤醒隧道看门狗。
     *
     * 为什么需要它（2026-10-30 实测）：手机 Wi-Fi 掉一会儿再回来时，隧道看门狗
     * 正处于 5s / 15s / 45s 的退避等待里（见 SshTunnel.backoffMs）；这段等待与网络状态无关，纯属白等，
     * 表现就是用户说的「网明明回来了却还是连不上」。这里在系统报告网络可用时
     * 调 [SshTunnel.nudgeNow] 把退避清零并打断睡眠。
     *
     * 只用 default network 回调（不是 requestNetwork）：我们只关心「现在有没有网」，
     * 不关心是哪一条；重复触发无副作用 —— nudge 只是置标志位。
     */
    private fun watchNetworkForTunnel() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val t = sshTunnel
                    if (t != null) {
                        DiagLog.i(TAG, "网络已恢复 → 让隧道立即重试")
                        t.nudgeNow()
                        return
                    }
                    // sshTunnel == null：上一次拨号已经失败并留下了失败原因。若 DshApp 正挂着
                    // 自动重试，网络刚回来就别再干等退避 —— 立刻拨一次（用户说的
                    // 「网明明回来了却还是连不上」就是这一段在空等）。
                    // 没有挂起的序列时什么都不做：用户没在连，App 也不该自己拨。
                    if (retryAttempt > 0) {
                        DiagLog.i(TAG, "网络已恢复 → 立刻自动重试（跳过剩余退避）")
                        retryNow("网络恢复")
                    }
                }
            })
        } catch (t: Throwable) {
            // 注册失败（个别 ROM 限制）不该影响启动：退避仍在，只是恢复慢一点
            DiagLog.w(TAG, "网络回调注册失败：${t.javaClass.simpleName} ${t.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 诊断日志必须最先起来：后面每一行 DiagLog 都依赖它把文件打开
        DiagLog.init(filesDir)
        DiagLog.i(TAG, "DshApp.onCreate: ${pkgVersion()}")
        reportLastExit()
        SshTunnel.binPath = File(applicationInfo.nativeLibraryDir, "libdbclient.so")
            .takeIf { it.exists() }?.absolutePath
        // HOME 用 filesDir，与原终端模式（）一致：两边写同一份 known_hosts，
        // TOFU 信任才不会分裂成两份。见 SshTunnel.homeDir。
        SshTunnel.homeDir = filesDir.absolutePath
        watchNetworkForTunnel()
        // 开关打开着就把通知渠道准备好（顺手做一次渠道迁移：删掉旧的 dsh-turn）。
        // 不能等到第一条通知才建：渠道是用户在系统设置里能单独调的对象，
        // 「第一条通知之前看不到它」会让「为什么没弹横幅」变成一个查不到的空白。
        if (prefs.getBoolean(PREF_NOTIF_TURN, false)) {
            Notifier.ensureChannels(this)
        }
        // 冷启动时隧道可能还没恢复：syncEventsClient 自己会在没有隧道时什么都不做，
        // 等 ensureTunnel 成功那条路径再把它挂上。
        syncEventsClient()
    }

    private fun pkgVersion(): String = try {
        "v${packageManager.getPackageInfo(packageName, 0).versionName}"
    } catch (_: Exception) {
        "v?"
    }

    /**
     * 把「上一次进程为什么没了」记进诊断日志。
     *
     * 数据源是系统落盘的 `ApplicationExitInfo`（API 30+）—— 系统里**唯一**既重启不丢、
     * 应用自己又读得到的记录（logcat 读不到；dropbox 要 DUMP 权限，只有 adb 能看）。
     * 实测正是靠它把「客户端突然没了」定性成 `reason=3 (LOW_MEMORY)` 而不是崩溃：
     * `dumpsys activity exit-info` 里是同一份数据，但那个要电脑。
     */
    private fun reportLastExit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reportLastExitR()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reportLastExitR() {
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 3)
            if (reasons.isEmpty()) {
                DiagLog.i(TAG, "退出历史: 无记录")
                return
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            reasons.forEachIndexed { i, r ->
                DiagLog.w(TAG, "退出历史 #$i: ${fmt.format(Date(r.timestamp))} ${exitReasonText(r.reason)}"
                    + " importance=${r.importance} rss=${r.rss / 1024}MB desc=${r.description}")
            }
            // 顶部摘要优先给**最近的异常退出**：包更新 / 用户主动停止 / 正常退出都是
            // "无事发生"，却会把真正的问题（崩溃、被杀）从最近一条的位置挤掉 ——
            // 实测就是这样：安装新版后第一条永远是「应用被更新」。
            val notable = reasons.firstOrNull { isAbnormal(it.reason) } ?: reasons[0]
            DiagLog.lastExitSummary = fmt.format(Date(notable.timestamp)) + "  " + exitReasonText(notable.reason)
        } catch (e: Exception) {
            // 个别 ROM 会对非系统包拒绝这个查询；记录但不影响启动
            DiagLog.w(TAG, "读退出历史失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun isAbnormal(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> true
        else -> false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "正常退出"
        ApplicationExitInfo.REASON_SIGNALED -> "被信号杀死"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足被杀"
        ApplicationExitInfo.REASON_CRASH -> "崩溃（Java 异常）"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "崩溃（native）"
        ApplicationExitInfo.REASON_ANR -> "无响应（ANR）"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过高"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动停止"
        ApplicationExitInfo.REASON_USER_STOPPED -> "被用户停止"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程死亡"
        ApplicationExitInfo.REASON_FREEZER -> "被冻结"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "包状态变更"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用被更新"
        ApplicationExitInfo.REASON_OTHER -> "其它"
        else -> "未知（$reason）"
    }

    // ── 隧道所有权 ────────────────────────────────────────────────

    /**
     * 取得 SSH 配置 [cfg] 对应的隧道，必要时新建。**阻塞**（拨号 ~1-3s，失败最坏 3×15s），
     * 必须在后台线程调用。失败返回 null。
     *
     * 复用规则：已有隧道且配置指纹一致且 [SshTunnel.isHealthy] → 直接返回。
     * [force] = true 时强制重建（用户在连接屏明确点了「连接」，可能是在修一条
     * 自己觉得有问题的隧道）。
     *
     * ## 两把锁（2026-09-14 审计后拆开）
     *
     * 原先是「一把锁 + 整段拨号都在锁内」，于是主线程的「断开连接」要排队等拨号结束，
     * 最长几十秒 —— 那是必然 ANR。现在：
     *  - [tunnelLock]：只做字段级短临界区，主线程可以安全地拿；
     *  - [dialLock]：串行化拨号，长阻塞只发生在后台线程上；
     *  - [dialGeneration]：拨号期间发生过「断开/换配置」就丢弃这次结果。
     *
     * @param fromRetry 由自动重试引擎调用（见 [scheduleRetryAfterFailure]）。传 false 表示
     *   「有人主动要连」—— 入口处会先取消挂起的自动重试，避免刚拨上又被一次自动重拨拆掉。
     */
    fun ensureTunnel(cfg: SshConfig, force: Boolean = false, fromRetry: Boolean = false): SshTunnel? {
        // 任何「主动拨号」都先作废挂起的自动重试（用户点重试 / 冷启动自动恢复 / 回前台
        // 探针重建走的都是这条路）；自动重试自己（fromRetry）不能取消自己这条序列，
        // 否则计数被清零、永远退不到 300s 那一档。
        if (!fromRetry) clearRetry("主动拨号")

        val fp = cfg.fingerprint()
        // 先短看一眼能不能复用（健康探针本身要发真流量，放到锁外做）
        val reusable = synchronized(tunnelLock) {
            val cur = sshTunnel
            if (!force && cur != null && tunnelFingerprint == fp) cur else null
        }
        if (reusable != null && reusable.isHealthy()) {
            // 探针期间用户可能点了「断开连接」（closeTunnel 把 sshTunnel 置空）——
            // 返回一条已经不在册的隧道等于把「断开」逆转（审计 M10）。这里再确认一次身份。
            val stillOurs = synchronized(tunnelLock) { sshTunnel === reusable }
            if (stillOurs && reusable.isUp) {
                DiagLog.i(TAG, "ensureTunnel: 复用已有隧道 ${reusable.localBaseUrl}")
                clearRetry("隧道可用")
                return reusable
            }
            DiagLog.i(TAG, "ensureTunnel: 复用判定期间现场已变（仍是当前隧道=$stillOurs），重新拨号")
        }
        synchronized(dialLock) {
            // 等上一次「断开」的回收线程收完旧 owner：否则新拨号会看到端口还被占着，
            // 白白漂到 13080（origin 一变 cookie/localStorage 全换一份）。
            pendingClose?.let { th ->
                runCatching { th.join(CLOSE_JOIN_MS) }
                    .onFailure { Thread.currentThread().interrupt() }  // 别把中断标志吞掉
            }
            // 排队等锁期间别人可能已经拨好了
            val again = synchronized(tunnelLock) {
                val cur = sshTunnel
                if (!force && cur != null && tunnelFingerprint == fp) cur else null
            }
            if (again != null && again.isHealthy()) {
                DiagLog.i(TAG, "ensureTunnel: 复用已有隧道 ${again.localBaseUrl}（等锁期间建立）")
                clearRetry("隧道可用")
                return again
            }
            val (obsolete, generation) = synchronized(tunnelLock) {
                val cur = sshTunnel
                sshTunnel = null
                tunnelFingerprint = null
                cur to dialGeneration
            }
            // 收旧 owner 必须在选端口之前完成；它是阻塞的（每个进程最多 1.5s），
            // 所以刻意放在短锁之外 —— 代价由后台线程承担。
            obsolete?.close()

            val t = build(cfg)
            if (t == null) {
                // 配置本身不完整（缺地址 / 缺认证材料）：拨号压根没发生，重试没有意义 ——
                // 不排自动重试，该做的是回连接屏把配置补齐。
                DiagLog.w(TAG, "ensureTunnel: 配置不完整，不拨号也不排自动重试")
                return null
            }
            t.onStateChange = { s ->
                // 只记日志：状态条由 ①②③ 引导流程表达，connecting/connected 属内部
                // 状态（曾显示为「隧道: connected」，是术语）。
                DiagLog.i(TAG, "tunnel state: $s")
                // 但「可用性」必须告诉界面（审计 H5）：看门狗重建失败时原先只有这行日志。
                when {
                    s == "connected" -> notifyTunnelAlive(true)
                    s.startsWith("failed") -> notifyTunnelAlive(false)
                    else -> Unit   // connecting / reconnecting 还不改变「可用」判定
                }
            }
            t.onLocalBaseChanged = { b ->
                DiagLog.i(TAG, "tunnel base changed: $b")
                tunnelObservers.forEach { it.onTunnelBaseChanged(b) }
            }
            t.start()
            if (t.localBaseUrl == null) {
                // 失败原因带到 UI 去（否则只剩一句「检查地址/账号/密码」）
                lastTunnelError = t.lastError
                t.close()
                DiagLog.w(TAG, "ensureTunnel: 拨号失败（${lastTunnelError ?: "原因未记录"}）")
                // 排下一次自动重拨（主线程 Handler + 单线程 Executor，见本类的退避重试一节）
                scheduleRetryAfterFailure(cfg, generation)
                return null
            }
            val published = synchronized(tunnelLock) {
                if (dialGeneration != generation) {
                    false
                } else {
                    sshTunnel = t
                    tunnelFingerprint = fp
                    true
                }
            }
            if (!published) {
                // 用户已断开/已换配置：这次结果作废，**也不排自动重试** ——
                // 「断开」必须赢，不能几秒后被一次自动重拨推翻。
                DiagLog.i(TAG, "ensureTunnel: 拨号期间用户已断开/已换配置，丢弃这次拨号结果（不排自动重试）")
                t.close()
                return null
            }
            // 隧道真的起来了才需要保活。放在成功分支里（而不是 build()），
            // 免得拨号失败也留下一个常驻前台服务。
            lastTunnelError = null
            // 拨通了：自动重试序列到此结束（界面那行倒计时随即隐藏）。
            clearRetry("隧道可用")

            DiagLog.i(TAG, "ensureTunnel: 已建立 ${t.localBaseUrl}")
            // 隧道刚起来：把权威回合状态的订阅挂上（页面被冻时通知只能靠它）。
            syncEventsClient()
            return t
        }
    }

    // ── 拨号失败后的自动退避重试 ─────────────────────────────────────────
    //
    // 为什么要有它（用户明确抱怨过的痛点）：[ensureTunnel] 一条命只拨 3 次
    // （SshTunnel.MAX_ATTEMPTS），失败后返回 null —— 此后**没有任何东西**会再拨。
    // 手机网络抖一下（Wi-Fi 掉几秒又回来）就永久卡在「连不上」：用户必须手动点重试，
    // 或者切一次前台让 onResume 的探针去重建。
    //
    // 设计（2026-11-08）：
    //   · 定时用主线程 Handler，拨号在 [retryExecutor] 单线程上跑 —— ensureTunnel 是
    //     阻塞的（最坏 3×30s），绝不能占主线程；单线程也保证同一时刻只有一次自动拨号。
    //   · 退避 5 / 15 / 30 / 60 / 120 / 300 秒（300s 封顶），一条序列最多
    //     [RETRY_MAX_ATTEMPTS] 次。电脑整晚关机时到此为止，不会整夜空转。
    //   · 可见状态（attempt / nextAt / lastError）只在主线程写：所有入口都投到
    //     [retryHandler]，观察者回调因此也在主线程。字段标 @Volatile 方便界面与诊断页随时读。
    //   · 迟到的后台结果靠代数作废：[retryEpoch] 在取消/拨通时自增；
    //     「用户断开」还有一道 [dialGeneration] 校验（见 [scheduleRetryAfterFailure]）。
    //   · 不做保活：进程被冻结/回收时 Handler 自然停，回到前台仍由既有路径接手。

    /**
     * 自动重试的状态快照（给界面读）。
     *
     * [attempt] = 0 表示「不在重试」；[nextAtMs] 是下一次拨号的时刻，基准是
     * [SystemClock.elapsedRealtime]（界面用 `nextAtMs - elapsedRealtime()` 算剩余秒数，
     * 不受用户改系统时间影响），0 表示正在拨号、没有倒计时。
     */
    data class TunnelRetryState(
        val attempt: Int = 0,
        val nextAtMs: Long = 0L,
        val lastError: String? = null,
    )

    /** 自动重试的定时器（主线程）：可见状态全在它上面改。 */
    private val retryHandler = Handler(Looper.getMainLooper())

    /** 自动拨号线程：单线程 = 自动拨号串行（ensureTunnel 自己还有 dialLock 兜底）。 */
    private val retryExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tunnel-retry").apply { isDaemon = true }
    }

    /** 挂起的这一次重试用哪份配置重拨（拨号失败时记下；null = 没有序列）。 */
    @Volatile
    private var retryCfg: SshConfig? = null

    /** 序列内已排出的重试次数；0 = 不在重试（界面据此隐藏那行倒计时）。 */
    @Volatile
    private var retryAttempt = 0

    /** 下一次自动拨号的时刻（elapsedRealtime 基准；0 = 既没挂起也没在拨）。 */
    @Volatile
    private var retryNextAt = 0L

    /** 最近一次拨号失败的原因（给界面一行说明；拨通/取消时清空）。 */
    @Volatile
    private var retryLastError: String? = null

    /** 本序列内连续失败的次数，只用来算下一档退避（网络恢复/取消时清零）。 */
    @Volatile
    private var retryStep = 0

    /**
     * 重试代数：取消 / 拨通时自增。
     *
     * 后台线程改不了主线程的东西，就靠它把「迟到的结果」判掉：排下一次和开始拨号时各记下
     * 当时的代数，结果回来对不上就丢弃（用户断开的另一道保险是 [dialGeneration]）。
     */
    private val retryEpoch = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当前自动重试状态（任何线程可读；主线程读到的一定是最新）。 */
    fun currentRetryState(): TunnelRetryState =
        TunnelRetryState(retryAttempt, retryNextAt, retryLastError)

    /**
     * 取消挂起的自动重试（不碰隧道本身）。
     *
     * [closeTunnel] 已经内置了它；单独暴露是为了「隧道早就没了、但旧配置的重拨还挂着」
     * 的场景 —— 连接屏切换主机就属于这种：不撤掉的话，自动重拨可能在你点「连上」之前
     * 就用**旧主机**的配置拨通，界面上的「已连上电脑」会指向另一台电脑。
     */
    fun cancelTunnelRetry(reason: String) = clearRetry(reason)

    private fun notifyRetryState() {
        val s = currentRetryState()
        DiagLog.i(
            TAG,
            "自动重试状态：attempt=${s.attempt} " +
                "nextInMs=${if (s.nextAtMs > 0L) s.nextAtMs - SystemClock.elapsedRealtime() else 0} " +
                "err=${s.lastError ?: "-"}"
        )
        tunnelObservers.forEach { o ->
            runCatching { o.onRetryStateChanged(s.attempt, s.nextAtMs, s.lastError) }
                .onFailure { DiagLog.w(TAG, "观察者 onRetryStateChanged 抛异常：${it.message}") }
        }
    }

    /** 挂起的自动重拨：到点后交给 [retryExecutor] 真正拨号。 */
    private val retryTask = object : Runnable {
        override fun run() {
            val cfg = retryCfg
            if (retryAttempt == 0 || cfg == null) {
                // 到点前已被取消：已派发到队列里的这一条只能自己认账（removeCallbacks 撤不掉它）。
                DiagLog.i(TAG, "自动重试：没有挂起的序列（attempt=$retryAttempt），忽略这次到点")
                return
            }
            if (liveTunnel() != null) {
                DiagLog.i(TAG, "自动重试：已有可用隧道，取消序列")
                clearRetry("已有可用隧道")
                return
            }
            retryNextAt = 0L
            notifyRetryState()   // 界面从倒计时切成「正在拨号」
            startRetryDial(cfg)
        }
    }

    /**
     * 结束自动重试序列：撤掉挂起的任务、清零状态、通知界面把那行倒计时收起来。
     *
     * **任何线程可调**：撤任务与自增代数当场做（removeCallbacks 本身线程安全），
     * 可见状态一律回主线程改 —— 观察者回调因此也在主线程，界面不必自己再转一手。
     *
     * @param reason 只进日志：排查「为什么不再自动重试」时这一行是唯一线索。
     */
    private fun clearRetry(reason: String) {
        retryEpoch.incrementAndGet()
        retryHandler.removeCallbacks(retryTask)
        retryHandler.post {
            val had = retryAttempt != 0 || retryNextAt != 0L || retryLastError != null
            retryCfg = null
            retryAttempt = 0
            retryStep = 0
            retryNextAt = 0L
            retryLastError = null
            if (had) {
                DiagLog.i(TAG, "自动重试：序列结束（$reason）")
                notifyRetryState()
            }
        }
    }

    /**
     * 拨号失败后排下一次自动重试。
     *
     * 由 [ensureTunnel] 的失败路径调用，**可在任意线程**：可见状态的改动全部投到主线程。
     *
     * @param cfg 本次拨号用的配置 —— 重试就用它重拨（用户此时可能已经离开连接屏，
     *   不能指望界面再来喂一次配置）。
     * @param generation 本次拨号开始时的 [dialGeneration]。用户在这期间点了「断开连接」
     *   （[closeTunnel] 会自增它）就说明这次失败已经作废，不能再排下一次 ——
     *   否则「断开」会被一次自动重拨逆转。
     */
    private fun scheduleRetryAfterFailure(cfg: SshConfig, generation: Long) {
        val epoch = retryEpoch.get()
        retryHandler.post {
            if (epoch != retryEpoch.get()) {
                DiagLog.i(TAG, "自动重试：这次失败已被取消/已重连，不排下一次")
                return@post
            }
            if (dialGeneration != generation) {
                DiagLog.i(TAG, "自动重试：拨号期间用户已断开（或已换配置），不排下一次")
                return@post
            }
            if (retryAttempt >= RETRY_MAX_ATTEMPTS) {
                DiagLog.w(TAG, "自动重试：已连试 $RETRY_MAX_ATTEMPTS 次仍未成功，停止（等用户手动重试）")
                clearRetry("已达次数上限")
                return@post
            }
            retryCfg = cfg
            retryAttempt++
            val delay = RETRY_BACKOFF_MS[minOf(retryStep, RETRY_BACKOFF_MS.size - 1)]
            retryStep++
            retryLastError = lastTunnelError
            retryNextAt = SystemClock.elapsedRealtime() + delay
            retryHandler.removeCallbacks(retryTask)
            retryHandler.postDelayed(retryTask, delay)
            DiagLog.i(
                TAG,
                "自动重试：第 $retryAttempt 次已排定，${delay / 1000}s 后（上次失败：${retryLastError ?: "未记录"}）"
            )
            notifyRetryState()
        }
    }

    /**
     * 立刻拨一次、跳过剩余退避（网络恢复时用），并把退避档位清零。
     *
     * **可在任意线程**（系统网络回调不在主线程），实际动作回主线程做。反复触发无副作用：
     * 没有挂起的序列就什么都不做。
     */
    private fun retryNow(reason: String) {
        retryHandler.post {
            if (retryAttempt == 0) {
                DiagLog.i(TAG, "立刻重试：当前没有挂起的序列（$reason）")
                return@post
            }
            val cfg = retryCfg
            if (cfg == null) {
                clearRetry("没有可用的连接配置")
                return@post
            }
            if (liveTunnel() != null) {
                clearRetry("已有可用隧道")
                return@post
            }
            if (retryNextAt == 0L) {
                // 第 N 次已经在拨号（nextAt 在到点时被清成 0）：网络抖动一次不该再叠一次拨号。
                DiagLog.i(TAG, "立刻重试：第 $retryAttempt 次已在拨号，忽略（$reason）")
                return@post
            }
            retryHandler.removeCallbacks(retryTask)
            retryStep = 0                 // 退避重置：下一次失败重新从 5s 开始
            retryNextAt = 0L
            DiagLog.i(TAG, "立刻重试（$reason）：跳过剩余退避，第 $retryAttempt 次")
            notifyRetryState()
            startRetryDial(cfg)
        }
    }

    /** 在 [retryExecutor] 上真正拨一次号（阻塞，最长 3×30s）。主线程调用。 */
    private fun startRetryDial(cfg: SshConfig) {
        val attempt = retryAttempt
        val epoch = retryEpoch.get()
        DiagLog.i(TAG, "自动重试：第 $attempt 次开始拨号（${cfg.user}@${cfg.host}:${cfg.port}）")
        retryExecutor.execute {
            if (epoch != retryEpoch.get()) {
                DiagLog.i(TAG, "自动重试：第 $attempt 次在拨号前已被取消")
                return@execute
            }
            // fromRetry = true：自动重拨不能把自己这条序列在入口处取消掉。
            // force = false：这期间若有人已经把同一条配置拨通，直接复用，不拆一次新的。
            val t = try {
                ensureTunnel(cfg, force = false, fromRetry = true)
            } catch (e: Throwable) {
                // ensureTunnel 内部各条路径都自己兜了异常，走到这里说明是没预料到的 ——
                // 自动重试线程绝不能因此死掉（死了就再也不会有下一次）。
                DiagLog.e(TAG, "自动重试：第 $attempt 次拨号抛异常：${e.javaClass.simpleName} ${e.message}")
                null
            }
            retryHandler.post {
                if (t != null && t.localBaseUrl != null) {
                    // ensureTunnel 的成功路径已经 clearRetry 了（调用无害，幂等），这里只补日志。
                    // 注意**先判成功再判代数**：成功本身就会让代数自增，反过来判会把成功误报成
                    // 「结果迟到」，日志会误导排查。
                    DiagLog.i(TAG, "自动重试：第 $attempt 次成功 ${t.localBaseUrl}")
                    clearRetry("自动重拨成功")
                    return@post
                }
                if (epoch != retryEpoch.get()) {
                    DiagLog.i(TAG, "自动重试：第 $attempt 次结果迟到（期间已取消/已重连），丢弃")
                    return@post
                }
                // 失败：ensureTunnel 的失败路径已经排好了下一次（scheduleRetryAfterFailure）。
                // 万一没排上（配置不完整 / 拨号期间被取消），序列不能悬在半空 ——
                // 否则界面会永远停在「第 N 次重试」的倒计时上。
                if (retryNextAt == 0L && retryAttempt > 0) {
                    DiagLog.w(TAG, "自动重试：第 $attempt 次失败且没有下一次，停止序列")
                    clearRetry("失败且无下一次")
                }
            }
        }
    }

    /**
     * 关闭隧道（用户手动断开）。谁都不该在别处 close，否则会打断 WebView。
     *
     * **绝不阻塞调用方**：主线程（「断开连接」按钮）会调它，所以这里只做常数级的字段
     * 清理 + 自增 [dialGeneration]（作废所有在飞的拨号结果），真正的进程回收交给后台线程。
     * 回收线程记在 [pendingClose]，下一次拨号前会被 join —— 「端口稳定」仍然成立，
     * 只是这份代价不再由 UI 线程付（原先它要排队等拨号锁，是 ANR 来源）。
     */
    fun closeTunnel() {
        // 用户主动断开：**必须**撤掉挂起的自动重试，否则「断开」会在几秒后被一次自动
        // 重拨推翻（用户看到的就是「点了断开又自己连上」）。代数同时自增，在飞的自动
        // 拨号的迟到结果也会被丢弃。
        clearRetry("用户断开连接")
        val closing = synchronized(tunnelLock) {
            dialGeneration++
            val cur = sshTunnel
            sshTunnel = null
            tunnelFingerprint = null
            cur
        }

        // 订阅也停掉：它的目标是那条隧道里的 127.0.0.1:3080，隧道没了重连只会白转。
        stopEventsClient()
        // 明确告诉界面「已经不可用」：主动断开时界面自己会刷新，但看门狗/后台路径不一定。
        notifyTunnelAlive(false)
        if (closing != null) {
            val th = Thread { closing.close() }
            th.name = "tunnel-close"
            th.isDaemon = true
            pendingClose = th
            th.start()
        }
    }

    /** 配置指纹由 [SshConfig.fingerprint] 提供（字段定义在那里，不再各写一份）。 */
    private fun build(cfg: SshConfig): SshTunnel? {
        if (!cfg.isComplete) return null
        // 私钥方式但没给路径 → 配置不可用（终端模式另有「现生成一对」的回退，不在此列）
        val auth = cfg.toAuth() ?: return null
        return SshTunnel(
            sshHost = cfg.host,
            sshPort = cfg.port,
            sshUser = cfg.user,
            remoteHost = cfg.remoteHost,
            remotePort = cfg.remotePort,
            auth = auth,
            // WebView 的 origin 是 http://127.0.0.1:<port>，所以端口必须稳定
            // （3080 优先）；见 SshTunnel.pickFreePort。
            preferredPorts = SshTunnel.PORT_CANDIDATES,
        )
    }

    companion object {
        private const val TAG = "DshApp"
        /** 新拨号前最多等多久让上一次「断开」把旧 owner 收完（正常 <100ms）。 */
        private const val CLOSE_JOIN_MS = 5_000L

        /**
         * 自动重试的退避档位（毫秒）：5s → 15s → 30s → 60s → 120s → 300s，之后固定 300s。
         *
         * 前几档贴近「网络刚抖完、马上就能通」的常见情形；300s 封顶是为了电脑真的关机时
         * 别把手机网络/CPU 拖一整天（再密也没有意义：用户不在电脑旁的话拨多少次都连不上）。
         */
        private val RETRY_BACKOFF_MS = longArrayOf(
            5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L
        )

        /**
         * 一条序列最多自动重试多少次（按 300s 封顶算 ≈ 3 小时）。
         *
         * 为什么要有上限：电脑整晚关机时，无限重试就是整夜每 5 分钟派生一次 dbclient ——
         * 白耗电、白占 SSH 会话，而用户第二天手动点一下就能连上。40 次覆盖
         * 「出门几个小时、回来网还在」的常见情形，再往上只会变成后台空转。
         */
        private const val RETRY_MAX_ATTEMPTS = 40

        /**
         * 「任务完成时提醒我」开关的偏好键。
         *
         * 定义在这里（而不是 MainActivity）是因为**读写分居两处**：开关在连接屏，
         * 判断在 [onHostTurnStatus]。键名写两遍就会漂移成「开了没反应」。
         */
        const val PREF_NOTIF_TURN = "notif_turn_done"


        /** 普通偏好文件名（开关不是凭据，不必走 SecurePrefs）。 */
        const val PREFS = "dsh-handheld"
    }

    /** 普通偏好；与 MainActivity 共用同一个文件，键名见 [PREF_NOTIF_TURN]。 */
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
}
