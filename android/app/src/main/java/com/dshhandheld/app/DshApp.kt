package com.dshhandheld.app

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.MutableContextWrapper
import android.os.Build
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dshhandheld.diag.DiagLog
import com.dshhandheld.protocol.HarnessEventsClient
import com.dshhandheld.protocol.SshTunnel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

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
 * 后台行为：**有前台服务**（[com.dshhandheld.app.TunnelService]，0.1.8 起）—— 进程不进
 * cached 队列，既不冻结也不容易被低内存杀手挑中，隧道看门狗因此有机会自愈。
 * 历史上这里写过「没有前台服务、完成通知改由服务端经微信推送」：那条路 2026-09-14
 * 已被 App 自己的任务完成通知取代（[Notifier] + 页面消息通道 [attachPageBridge]）。
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
            attachPageBridge(view)
        }
    }

    // ── 页面 → App 的消息通道（任务完成通知的触发源）──────────────────────
    /**
     * 注册 WebView 消息通道。
     *
     * **只在这里注册一次**：WebView 是跨 Activity 保活的（见类注释），而
     * `addWebMessageListener` 是**累加**的 —— 挂在 Activity 里注册，每次重建都会多一层
     * 监听，同一条消息发 N 次、通知也发 N 条。
     *
     * 通道名与页面约定：`window.dshNative.postMessage(JSON.stringify({type, ...}))`。
     * 允许的 origin 只有隧道实际会用的那两个（`SshTunnel.PORT_CANDIDATES`）——
     * 这是个**只进不出**的通道（App 不向页面发指令），但也没必要让任意 origin 都能进来。
     */
    private fun attachPageBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            DiagLog.w(TAG, "WebView 不支持 WEB_MESSAGE_LISTENER，任务完成通知不会触发")
            return
        }
        val origins = SshTunnel.PORT_CANDIDATES.map { "http://127.0.0.1:$it" }.toSet()
        try {
            WebViewCompat.addWebMessageListener(
                view, PAGE_BRIDGE, origins
            ) { _, message, origin, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                onPageMessage(message.data, origin?.toString())
            }
            DiagLog.i(TAG, "已注册页面消息通道 $PAGE_BRIDGE（允许 origin：$origins）")
        } catch (e: Exception) {
            DiagLog.w(TAG, "注册页面消息通道失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 页面来的一条消息。
     *
     * 目前只有一种：`{"type":"turn-done","title":"<会话标题>","ms":<这一轮跑了多久>}` ——
     * 由 `assets/plugins/dsh-handheld-mobile.js` 在「深度求索中…」消失时发出。
     *
     * 三道闸门决定要不要真的弹通知：用户开关 → 是否在前台 → 系统权限（后者在 [Notifier]）。
     */
    private fun onPageMessage(data: String?, origin: String?) {
        if (data.isNullOrBlank()) return
        val json = runCatching { org.json.JSONObject(data) }.getOrNull()
        if (json == null) {
            DiagLog.w(TAG, "页面消息不是 JSON（来自 $origin）：${data.take(80)}")
            return
        }
        when (json.optString("type")) {
            "turn-start" -> {
                pageBusy = true
                lastTurnTitle = json.optString("title").takeIf { it.isNotBlank() } ?: lastTurnTitle
                // 认下「这一轮属于哪支会话」（见 syncEventsClient）：权柄在事件流那边 ——
                // 页面被系统冻住时它不会报结束，靠这条把两路信号对上。
                watchSession = lastRunningSession
                pendingWatch = watchSession == null
                turnNotified = false   // 新一轮：允许再通知一次（去重按轮算）
                DiagLog.i(TAG, "页面报告：一轮生成开始（pageBusy=true，watch=${watchSession ?: "待对齐"}）")
            }
            "turn-done" -> {
                pageBusy = false
                // 页面这条路已经报过了：把事件流那边的等待清掉，免得同一轮弹两条通知。
                watchSession = null
                pendingWatch = false
                val title = json.optString("title").takeIf { it.isNotBlank() }
                val ms = json.optLong("ms", 0L)
                // 页面侧现在**一律上报结束**（含 <1.5s 的闪现，带 short=true）——只上报「开始」
                // 会让 pageBusy 永远卡在 true，后台的 pauseTimers 省电设计就静默失效了
                // （2026-09-17 审计 H8）。太短的一轮由这里决定不打扰用户。
                if (json.optBoolean("short", false)) {
                    DiagLog.i(TAG, "页面报告：一轮生成结束（${ms}ms），太短，不通知")
                    return
                }
                val on = prefs.getBoolean(PREF_NOTIF_TURN, false)
                val foreground = visibleActivities.get() > 0
                DiagLog.i(TAG, "页面报告：一轮生成结束（${ms}ms，标题=$title，开关=$on，前台=$foreground）")
                when {
                    !on -> Unit
                    foreground -> DiagLog.i(TAG, "App 在前台，不发通知")
                    // 真机实测：事件流那条路只早到 138ms，两条路会各发一条（用户会看到两次提醒）。
                    // 去重按「轮」算 —— turn-start 时复位，谁先到谁发。
                    turnNotified -> DiagLog.i(TAG, "这一轮已经通知过（事件流先到），不重复发")
                    else -> {
                        turnNotified = true
                        Notifier.turnDone(this, title)
                    }
                }
            }
            "layout-diag" -> {
                // 顶部留白到底是谁的：header/标题行/页签/正文起点的 rect 与计算样式。
                DiagLog.i(TAG, "布局诊断：${json}")
            }
            "stats-diag" -> {
                // 统计行的真实几何（scrollWidth/clientWidth/字号）：调参不再靠估。
                DiagLog.i(TAG, "统计行几何：${json}")
            }
            "viewport-diag" -> {
                // 引导脚本报的 viewport 事实（innerH/screen 高/dpr/meta）：用来确认
                // 「viewport-fit=cover 到底生效没有」——顶部那 34px 就是这么定案的。
                DiagLog.i(TAG, "viewport 诊断：${json}")
            }
            "turn-tick" -> {
                // 每 5 分钟一条的存活探针：JS 到底有没有在跑（renderer 被冻就没有这一条）。
                // 2026-09-17 那几次「后台收不到通知」就是靠它定性的，别删。
                DiagLog.i(TAG, "页面存活探针：${json}")
            }
            "turn-state" -> {
                // 诊断心跳：页面每隔 60s（且这期间有过 DOM 变化）自报一次「它看见什么」。
                // 它是「一次都没命中」那种失败形态的唯一证据，所以只记日志、不参与通知。
                // 审计 M21 的答案就是靠它拿到的：两份 _turnStatus 在 rects/visibility 上
                // 完全一样，只有几何能区分。
                DiagLog.i(TAG, "页面心跳：${json}")
            }
            "needs-input" -> {
                val title = json.optString("title").takeIf { it.isNotBlank() }
                val key = json.optString("key")
                val on = prefs.getBoolean(PREF_NOTIF_TURN, false)
                val foreground = visibleActivities.get() > 0
                DiagLog.i(TAG, "页面报告：在等你选择（key=$key，标题=$title，开关=$on，前台=$foreground）")
                when {
                    !on -> Unit
                    foreground -> DiagLog.i(TAG, "App 在前台，不发通知")
                    else -> Notifier.needsInput(this, title)
                }
            }
            // 认不出的消息也把**内容**记下来：适配层的诊断心跳（turn-state）走的就是这条。
            // 2026-09-17 那次「完成后没有收到弹窗提醒」，日志里只有「一条 turn-done 都没有」
            // 可查 —— 有它就能直接看到观察者当时看到几个候选节点。
            else -> DiagLog.w(TAG, "未知的页面消息：${json.toString().take(200)}")
        }
    }

    /**
     * 页面是否正在生成。
     *
     * 由页面的 `turn-start` / `turn-done` 消息维护；[MainActivity.onPause] 用它决定要不要
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

    /** 订阅 Host 权威回合状态的客户端（见 [HarnessEventsClient] 的类注释）。 */
    private var eventsClient: HarnessEventsClient? = null
    private var eventsBase: String? = null

    /** 事件流最近一次报告「在跑」的会话（turn-start 时用它把两路信号对上）。 */
    @Volatile private var lastRunningSession: String? = null
    /** 正在等哪一支会话跑完；null 表示「页面还没报告开始」或「已经报过了」。 */
    @Volatile private var watchSession: String? = null
    /** 页面已经报了开始，但事件流还没告诉我们「谁在跑」—— 等下一帧 running=true 认领。 */
    @Volatile private var pendingWatch = false
    /** 最近一次 turn-start 带的标题，给事件流那条通知用。 */
    @Volatile private var lastTurnTitle: String? = null
    /** 这一轮是否已经发过通知（两条路径都会到，谁先到谁发）。 */
    @Volatile private var turnNotified = false

    /**
     * 按「通知开关 + 当前隧道」同步事件订阅。
     *
     * 三个入口都要调它：隧道起来/关掉、用户改开关、进程重建。独立成一处是为了不出现
     * 「隧道换了端口而订阅还指着旧端口」这种静默失效（3080 ⇄ 13080）。
     */
    fun syncEventsClient() {
        if (!turnNotifyEnabled()) { stopEventsClient(); return }
        val base = liveTunnel()?.localBaseUrl
        if (base == null) { stopEventsClient(); return }
        if (eventsClient != null && eventsBase == base) return
        stopEventsClient()
        eventsBase = base
        eventsClient = HarnessEventsClient(base) { sessionId, running ->
            onHostTurnStatus(sessionId, running)
        }.also { it.start() }
        DiagLog.i(TAG, "事件流：订阅 $base（权威回合状态）")
    }

    private fun stopEventsClient() {
        eventsClient?.stop()
        eventsClient = null
        eventsBase = null
    }

    /**
     * Host 报告的权威回合状态。页面被冻时它是**唯一**还能到达的「跑完了」信号。
     */
    private fun onHostTurnStatus(sessionId: String, running: Boolean) {
        if (running) {
            lastRunningSession = sessionId
            if (pendingWatch) {
                watchSession = sessionId
                pendingWatch = false
                DiagLog.i(TAG, "事件流：认领本轮会话 $sessionId")
            }
            return
        }
        if (watchSession == null && !pendingWatch) return   // 页面没报过开始 → 不由事件流发通知
        val watched = watchSession
        if (watched != null && watched != sessionId) return // 别的会话结束了，与我们无关
        watchSession = null
        pendingWatch = false
        // 页面被冻时它自己不会报结束：这里顺手把 pageBusy 复位（省电那套依赖它）。
        pageBusy = false
        val foreground = visibleActivities.get() > 0
        DiagLog.i(TAG, "事件流报告回合结束（session=$sessionId，前台=$foreground）")
        if (!turnNotifyEnabled()) return
        if (foreground) { DiagLog.i(TAG, "App 在前台，不发通知"); return }
        if (turnNotified) { DiagLog.i(TAG, "这一轮已经通知过（页面先到），不重复发"); return }
        turnNotified = true
        Notifier.turnDone(this, lastTurnTitle)
    }

    /**
     * 适配层 bundle 的字节（读一次，缓存）。
     *
     * 给 [MainActivity.DetachedWebViewClient] 用：Activity 销毁后 WebView 仍由本 Application
     * 保活，页面若在这期间重载，`shouldInterceptRequest` 还得把 bundle 喂出去 ——
     * 而那个 client 刻意不持 Activity，只能从 Application 拿（审计 M1）。
     */
    /**
     * 「移除已注入的那份 document-start 脚本」的动作（审计 L8/B3）。
     *
     * WebView 是 Application 保活的，所以「注入了几份」这件事也必须跟它同寿命记在一处：
     * 记在 Activity 上就会随重建丢引用、于是每重建一次就多一份脚本在同一个 WebView 上累积。
     *
     * 存**动作**而不是句柄：`WebViewCompat.addDocumentStartJavaScript` 返回的句柄类型名
     * 在 androidx.webkit 各版本里变过（写死它会让整个 App 编译不过 —— CI 实测踩过一次），
     * 由调用方就地捕获、这里只负责在下次注入前把它调用掉。
     */
    @Volatile
    var bootstrapRemover: (() -> Unit)? = null

    val pluginBundleBytes: ByteArray? by lazy {
        runCatching {
            assets.open("plugins/dsh-handheld-mobile.js").use { it.readBytes() }
        }.onFailure { DiagLog.w(TAG, "读取适配层 bundle 失败：${it.message}") }.getOrNull()
    }

    private fun notifyTunnelAlive(alive: Boolean) {
        DiagLog.i(TAG, "隧道可用性：alive=$alive（通知 ${tunnelObservers.size} 个观察者）")
        tunnelObservers.forEach { o ->
            runCatching { o.onTunnelAliveChanged(alive) }
                .onFailure { DiagLog.w(TAG, "观察者 onTunnelAliveChanged 抛异常：${it.message}") }
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
        // HOME 用 filesDir，与终端模式（TuiActivity）一致：两边写同一份 known_hosts，
        // TOFU 信任才不会分裂成两份。见 SshTunnel.homeDir。
        SshTunnel.homeDir = filesDir.absolutePath
        // 开关打开着就把通知渠道准备好（顺手做一次渠道迁移：删掉旧的 dsh-turn）。
        // 不能等到第一条通知才建：渠道是用户在系统设置里能单独调的对象，
        // 「第一条通知之前看不到它」会让「为什么没弹横幅」变成一个查不到的空白。
        if (prefs.getBoolean(PREF_NOTIF_TURN, false)) {
            Notifier.ensureChannels(this, Notifier.CHANNEL_TURN)
            Notifier.ensureChannels(this, Notifier.CHANNEL_ASK)
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
     */
    fun ensureTunnel(cfg: SshConfig, force: Boolean = false): SshTunnel? {
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

            val t = build(cfg) ?: return null
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
                DiagLog.i(TAG, "ensureTunnel: 拨号期间用户已断开/已换配置，丢弃这次拨号结果")
                t.close()
                return null
            }
            // 隧道真的起来了才需要保活。放在成功分支里（而不是 build()），
            // 免得拨号失败也留下一个常驻前台服务。
            lastTunnelError = null
            TunnelService.start(this, "已连接到 ${cfg.host}")
            DiagLog.i(TAG, "ensureTunnel: 已建立 ${t.localBaseUrl}")
            // 隧道刚起来：把权威回合状态的订阅挂上（页面被冻时通知只能靠它）。
            syncEventsClient()
            return t
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
        val closing = synchronized(tunnelLock) {
            dialGeneration++
            val cur = sshTunnel
            sshTunnel = null
            tunnelFingerprint = null
            cur
        }
        // 隧道没了就不该继续占着前台服务
        TunnelService.stop(this)
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

        /** 页面注入的桥对象名：页面侧写 `window.dshNative.postMessage(...)`。 */
        private const val PAGE_BRIDGE = "dshNative"

        /**
         * 「任务完成时提醒我」开关的偏好键。
         *
         * 定义在这里（而不是 MainActivity）是因为**读写分居两处**：开关在连接屏，
         * 判断在 [onPageMessage]。键名写两遍就会漂移成「开了没反应」。
         */
        const val PREF_NOTIF_TURN = "notif_turn_done"

        /** 普通偏好文件名（开关不是凭据，不必走 SecurePrefs）。 */
        const val PREFS = "dsh-handheld"
    }

    /** 普通偏好；与 MainActivity 共用同一个文件，键名见 [PREF_NOTIF_TURN]。 */
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
}
