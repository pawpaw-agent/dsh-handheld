package com.dshhandheld.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dshhandheld.protocol.SshTunnel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import com.dshhandheld.diag.DiagLog

/**
 * dsh-handheld 连接屏 —— 纯 WebView 版：
 *
 *  全屏 WebView 加载 dsh web 前端，功能与桌面 100% 一致
 *  （Markdown/代码高亮/设置页……），自带：
 *    - crypto.randomUUID 文档启动注入（局域网明文 HTTP 防白屏，与 dsh-lan-access 幂等同款）
 *    - Basic Auth 弹窗（隧道/反代场景）
 *    - 错误页（重试 / 换服务器），retainedWebView 跨重建保活
 *
 *  SSH 隧道可选：经 sshd 本地端口转发访问，服务端视角为回环，
 *  配置平面（settings/credentials/…）全解锁 —— 官方文档认可的合规远程路径。
 *  SSH 配置持久化，App 重启自动恢复；断线重连后 WebView 自动跟随新端口。
 *
 *  视觉：全 App 黑白色调 —— 与启动图标一致。
 */
class MainActivity : Activity() {
    private var webView: WebView? = null
    private var connectView: View? = null
    private var errorView: View? = null
    /** 诊断信息页（机内取证）的容器与正文；见 [showDiagPage]。 */
    private var diagView: View? = null
    private var diagBody: TextView? = null
    private var progressBar: ProgressBar? = null
    private var lastUrl: String? = null

    /**
     * 隧道事件订阅。隧道由 DshApp 统一持有，回调改为广播，本 Activity 只订阅。
     * onDestroy 必须反注册（观察者持有 Activity 引用）。
     */
    private val tunnelObserver = object : DshApp.TunnelObserver {
        override fun onTunnelBaseChanged(base: String) {
            onUi {
                // 只置 ack：lastUrl 与 prefs["url"] 由 connectWeb 自己写，这里再写一遍是重复赋值。
                // 本地基址变了 → cookie 失效，必须重新走 token 交换。
                sshTokenAck = false
                connectWeb(base)
            }
        }
    }
    private var pendingAuth: HttpAuthHandler? = null
    private var statusView: TextView? = null
    private var sshKeyPathInput: EditText? = null
    private var disconnectButton: Button? = null
    private var backToWebButton: Button? = null

    // Step3 连接进度行（引导流程；null = 界面未构造/非引导状态）
    private var stepGuideLine1: TextView? = null
    private var stepGuideLine2: TextView? = null
    private var stepGuideLine3: TextView? = null
    private var stepGuideCard: View? = null
    private var stepGuideStep2: View? = null
    private var step1Card: LinearLayout? = null

    /** 连接进行中守卫：防连点「连接」并发多个隧道/多次设置回调。 */
    private val connecting = AtomicBoolean(false)


    /** 页面加载失败后的自动重试余量（WiFi 断连/隧道重建窗口期自动恢复，无需手动点重试）。 */
    private var loadRetriesLeft = 0

    /**
     * 当前 URL 是否已成功完成过 token 交换（cookie 已种下）。
     * cookie 按 host:port 绑定：SSH 重连换端口后必须置 false 重新认证。
     * 直连模式端口不变，仅首次需要。
     */
    private var sshTokenAck = false

    /** 401 时是否已尝试过回退干净 URL（防重复回退循环；每次 connectWeb 重置）。 */
    private var unauthorizedCleanTried = false
    private lateinit var prefs: android.content.SharedPreferences

    // ── 生命周期契约：本实例排的工作不得活在实例之外 ──────────────────────
    /**
     * 本 Activity 实例是否仍然存活。`onDestroy` 置 false。
     *
     * 存在的理由：本 Activity 起的工作（隧道拨号、token 抓取、页面加载重试）跑在后台线程或
     * 延时队列上，**可能在本实例销毁之后才回来**。而它们回来时要改的东西是**共享的**——
     * `prefs["url"]`、保活的 WebView、以及 DshApp 持有的隧道。一个已死实例去改这些状态，
     * 就是「用户已经离开连接屏，页面却突然自己开始重载」这类问题的来源。
     */
    private val alive = AtomicBoolean(true)

    /**
     * 由本 Activity 拥有的主线程 Handler。
     *
     * 用它而不是 `webView.postDelayed(...)`：WebView 被 DshApp 保活到进程结束，把回调挂在
     * 它身上等于把「本实例的闭包（含 Activity 引用）」存进一个永不释放的队列，而且
     * `onDestroy` 无从取消。用自己的 Handler，销毁时一句 `removeCallbacksAndMessages(null)`
     * 就干净了。
     */
    private val ui = Handler(Looper.getMainLooper())

    /** 回主线程执行，但**仅在本实例仍存活时**；销毁后迟到的回调直接丢弃。 */
    private fun onUi(block: () -> Unit) {
        ui.post { if (alive.get()) block() }
    }

    // ── 界面状态（单一真相）────────────────────────────────────────────────
    /**
     * 顶层屏幕。
     *
     * 提示页（错误页 / 401 令牌页）**刻意不在此枚举内**：它是盖在当前屏幕之上的覆盖层，
     * 隐藏后自然露出下面那一屏，所以不需要记录「从哪来」。把它塞进这个枚举反而要多维护
     * 一个「返回目标」字段。
     */
    private enum class Screen { CONNECT, WEB }

    /**
     * 连接屏内部的三步（三张卡片互斥显示）。
     *
     * 此前 `step1Card` / `stepGuideStep2` / `stepGuideCard` 的 visibility 由 **5 处**
     * 各自设置，其中 `showConnectScreen()` 与 `guideBackToStep2()` 的差异（前者复位进度行、
     * 后者不复位）只体现在代码里而没有名字。现在只由 [showStep] 一处决定。
     */
    private enum class ConnectStep { MODE, CREDENTIALS, CONNECTING }

    private var screen = Screen.CONNECT
    private var connectStep = ConnectStep.MODE

    /**
     * 切到某一屏。**唯一**改动连接屏可见性的地方。
     *
     * 收敛的动机是两个已发生的 bug：`about:blank` 死路（把「有没有页面」等同于
     * `url 非空`）与错误页文案错位（两个页面共用容器、谁先构造谁定文案）——两者都不是
     * 算错，而是「该显示哪一屏」没有单一表示，于是某个分支漏了。
     */
    private fun showScreen(target: Screen) {
        if (screen != target) DiagLog.i(TAG, "screen: $screen → $target")
        screen = target
        connectView?.visibility = if (target == Screen.CONNECT) View.VISIBLE else View.GONE
    }

    /**
     * 切到连接屏内的某一步。
     *
     * @param resetGuide 是否把 ①②③ 进度行复位。只有「主动回到连接屏」（[showConnectScreen]）
     *   需要复位——Step3 是上一轮连接的残留，不复位会出现「连接中…」却早已连上的矛盾画面；
     *   而在 Step3 与本步之间来回切换时（[guideBackToStep2]）**不能**复位，否则刚跑出来的
     *   「✓ 已连上」会被抹掉。
     */
    private fun showStep(step: ConnectStep, resetGuide: Boolean = false) {
        if (connectStep != step) DiagLog.i(TAG, "connectStep: $connectStep → $step（resetGuide=$resetGuide）")
        connectStep = step
        step1Card?.visibility = if (step == ConnectStep.MODE) View.VISIBLE else View.GONE
        stepGuideStep2?.visibility = if (step == ConnectStep.CREDENTIALS) View.VISIBLE else View.GONE
        stepGuideCard?.visibility = if (step == ConnectStep.CONNECTING) View.VISIBLE else View.GONE
        if (resetGuide) resetGuideLines()
    }

    private companion object {
        const val TAG = "DshHandheld"
        // 配色与尺寸基元集中在 UiKit（此前 MainActivity / TuiActivity 各定义一份）。
        // 保留这些别名是为了让 60 余处调用点不必改动；定义只有一处。
        const val COL_BG = UiKit.BG
        const val COL_TEXT = UiKit.TEXT
        const val COL_TITLE = UiKit.TITLE
        const val COL_MUTED = UiKit.MUTED
        const val COL_DIM = UiKit.DIM
        const val COL_HINT = UiKit.HINT
        const val COL_ACCENT = UiKit.ACCENT
        const val COL_ACCENT_TEXT = UiKit.ACCENT_TEXT
        const val COL_ERROR = UiKit.ERROR
        const val UA_MARKER = "DshHandheld/1.0"
        const val DEFAULT_PORT = "3080"
        const val REQ_PICK_KEY = 2001

        const val PREF_SERVER_TOKEN = "server_token" // dsh 0.1.2+ 一次性启动 token（服务重启后自动更新）

        // ── 手机端适配插件（dsh-handheld-mobile，本仓库自研）────────────────
        // 纯 App 侧注入，服务端零改动：doc-start 时用 setter 钩住 window.__DSH_BOOT__，
        // 在服务端写入的启动图里补一条插件项（entry + batch，URL 指向我们自己的 agent
        // 数据 URL）；WebView 引导循环按清单 create 该插件时，shouldInterceptRequest 命中
        // 该 URL 返回 APK assets 里的插件 bundle。
        // 插件运行时外部依赖仅 react/jsx-runtime + dsh-client-ui-primitives，均已在前端壳
        // 的 staticModules 种子里（已验证），无需额外注入。
        //
        // 2026-09-13 起这一层是**我们自己的代码**：此前 vendored 的第三方
        // dsh-web-mobile（MIT）已删除 —— 每次上游发版都要在它体内重打补丁，补丁与上游
        // 代码混在一起说不清归属。适配层源码见 assets/plugins/dsh-handheld-mobile.js。
        //
        // id 必须与那个 bundle 内的 `id: "dsh-handheld-mobile"` 一致，改不得（CI 有断言）。
        // rev 只是 WebView 侧的缓存键：内容变更必须换 rev，否则可能命中旧缓存。
        const val MOBILE_PLUGIN_ID = "dsh-handheld-mobile"
        const val MOBILE_PLUGIN_REV = "dsh-handheld-mobile-1.0.7"
        const val MOBILE_PLUGIN_URL = "/plugins/??$MOBILE_PLUGIN_ID/client.js&rev=$MOBILE_PLUGIN_REV"

        /**
         * 读注入引导脚本（assets 单一来源），把占位符换成上面的常量。
         *
         * 脚本内容放在 `assets/plugins/mobile-bootstrap.js` 而不是这里的裸字符串：
         * 验证 harness（scripts/ui-verify.mjs）必须与 App 执行**逐字相同**的引导逻辑，
         * 两份副本一定会漂移。现在两边读同一份文件，只各自填占位符；常量是否一致由
         * CI 不变量守着。
         */
        fun mobileBootstrapJs(assets: android.content.res.AssetManager): String =
            assets.open("plugins/mobile-bootstrap.js").use { it.readBytes() }
                .toString(Charsets.UTF_8)
                .replace("{{ID}}", MOBILE_PLUGIN_ID)
                .replace("{{URL}}", MOBILE_PLUGIN_URL)
                .replace("{{REV}}", MOBILE_PLUGIN_REV)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dsh-handheld", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // 仅 debug 包开启 WebView 远程调试，方便真机调试；release 不暴露。
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val root = FrameLayout(this).apply { setBackgroundColor(COL_BG) }

        // ── WebView（保留实例，跨重建保活）────────────────────────
        // context 由 DshApp 用 MutableContextWrapper 管理：实例保活，但 base context
        // 每次重建都换成本次 Activity，销毁时换回 application —— 否则旧 Activity
        // 会被这个 Application 级引用一直拖住（见 DshApp.obtainWebView）。
        val app = application as DshApp
        webView = app.obtainWebView(this).apply {
            (parent as? ViewGroup)?.removeView(this)
            if (!settings.userAgentString.contains(UA_MARKER)) {
                settings.userAgentString = settings.userAgentString + " " + UA_MARKER
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // 引导脚本从 assets 读入（单一来源，与验证 harness 共用同一份文件）
                val bootstrap = try {
                    // 显式限定接收者：这里的 apply 块接收者是 WebView，不写全会在
                    // 外层作用域里去找 assets，读起来容易误会。
                    mobileBootstrapJs(this@MainActivity.assets)
                } catch (e: Exception) {
                    DiagLog.e(TAG, "读取 mobile-bootstrap.js 失败，移动端适配将不生效：${e.message}")
                    null
                }
                if (bootstrap != null) {
                    WebViewCompat.addDocumentStartJavaScript(this, bootstrap, setOf("*"))
                }
            }
            webViewClient = object : WebViewClient() {
                // 拦截手机端适配插件 bundle：返回 APK assets 里的客户端脚本
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    // 兜底匹配：?? 可能被编码为 %3F%3F，只锚定 /plugins/ 前缀 + 插件 id
                    if (!u.contains("/plugins/") || !u.contains("$MOBILE_PLUGIN_ID/client.js")) return null
                    val bytes = try {
                        assets.open("plugins/dsh-handheld-mobile.js").use { it.readBytes() }
                    } catch (e: Exception) { return null }
                    return android.webkit.WebResourceResponse(
                        "text/javascript", "utf-8", ByteArrayInputStream(bytes)
                    )
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 带 ?token= 的成功页面：服务端已 303 换 cookie 并落地干净 URL；
                    // 后续加载成功也记住认证态（cookie 有效期内无需重复 token 交换）。
                    // 只对 http(s) 正式页面置 ack：about:blank 等内部加载不能污染状态
                    //（1.5.2 断开连接加载 about:blank 会把 ack 误置 true → 省掉 token 交换 → 401）。
                    val u = webView?.url
                    if (u?.startsWith("http") == true && !u.contains("?token=")) {
                        sshTokenAck = true
                        DiagLog.i(TAG, "onPageFinished: 正式页面加载完成 → ack=true url=$u")
                        guideLine(3, "③ 打开 dsh 网页 ✓ 已打开", state = false)
                    } else if (u != null) {
                        // about:blank / 带 token 的中间页：刻意不置 ack（1.5.2 的 401 回归源于此）
                        DiagLog.i(TAG, "onPageFinished: 非正式页面，不置 ack url=$u")
                    }
                    hideErrorPage()
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    // ERR_ABORTED(-3) = 导航被取消（重载/加载 about:blank 打断上一请求），不算失败
                    if (request?.isForMainFrame == true && error?.errorCode != -3) {
                        // 错误描述此前只上屏（给用户看的文案会随场景改写），日志里必须留原始值
                        DiagLog.w(TAG, "onReceivedError: code=${error?.errorCode} " +
                            "desc=${error?.description} url=${request.url}")
                        showErrorPage(error?.description?.toString() ?: "网络错误")
                        scheduleLoadRetry()
                    }
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, resp: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true) {
                        val code = resp?.statusCode ?: 0
                        // 431（cookie 累积顶爆头部上限）当年就是在这里静默失败、只能靠 CDP 手工挖
                        DiagLog.w(TAG, "onReceivedHttpError: HTTP $code url=${request.url} " +
                            "reason=${resp?.reasonPhrase}")
                        if (code == 401) {
                            handleUnauthorized(view)
                        } else {
                            showErrorPage("HTTP $code")
                            scheduleLoadRetry()
                        }
                    }
                }
                override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                    handler ?: return
                    showAuthDialog(handler, host)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 100) {
                        progressBar?.visibility = View.GONE
                    } else {
                        progressBar?.visibility = View.VISIBLE
                        progressBar?.progress = newProgress
                    }
                }
            }
            // 没有 DownloadListener 时 Android WebView 会**静默丢弃**下载：dsh 的
            // 「导出会话日志」是同源 URL + `download` 属性，服务端确实发了请求、前端也确实
            // 弹了「Session 导出已开始下载」，但手机上永远没有文件（2026-09-13 真机取证：
            // /sdcard/Download 无 dsh-session-*.zip、dumpsys download 为空）。
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
        root.addView(webView)

        // 细进度条（WebView 加载时顶部一条白线，保持沉浸、不遮挡内容）
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(COL_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { gravity = Gravity.TOP }
            visibility = View.GONE
        }
        root.addView(progressBar)

        // ── 连接屏 ───────────────────────────────────────────────
        connectView = createConnectView()
        root.addView(connectView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        setContentView(root)
        applyImmersive()
        (application as DshApp).addTunnelObserver(tunnelObserver)

        // 自动连接（仅 SSH 模式）：存在完整 SSH 配置即恢复隧道 + 加载回环 URL；
        // 否则停留在连接屏。直连模式已移除。
        val savedUrl = prefs.getString("url", null)
        val currentUrl = webView?.url
        val baseMatch = savedUrl != null && currentUrl != null &&
            currentUrl.trimEnd('/').startsWith(savedUrl.trimEnd('/'))
        val sshSaved = SshConfig.load(prefs)?.takeIf { it.isComplete }
        // 三分支判定是「重开 App 走哪条路」的唯一决策点，此前零日志：
        // 出问题时（白屏 / 意外重连 / 该重连却没重连）无法从日志判断走了哪条。
        DiagLog.i(TAG, "onCreate: savedUrl=$savedUrl currentUrl=$currentUrl baseMatch=$baseMatch " +
            "retainedWebView=${app.retainedWebView != null} sshPrefs=${sshSaved != null}")
        if (savedUrl != null && !baseMatch) {
            if (sshSaved != null) {
                DiagLog.i(TAG, "onCreate: 分支2 冷启动重连（WebView 不在保存的基址上 + ssh 配置可用）")
                autoConnectSsh()
            } else {
                DiagLog.i(TAG, "onCreate: 分支3 停留连接屏（有 url 但 ssh 配置不可用）")
                showScreen(Screen.CONNECT)
            }
        } else if (currentUrl?.startsWith("http") == true) {
            // 必须是**正式页面**才算「已在网页上」。此前判的是 `!isNullOrBlank()`，
            // 于是「断开连接」载入的 about:blank 也命中这一支 → 连接屏被 GONE 掉、
            // WebView 又是空白，重开 App 就是一条无 UI 出口的死路（BACK 只 moveTaskToBack）。
            DiagLog.i(TAG, "onCreate: 分支1 直接回网页（复用保活 WebView，不重连不重载）")
            showScreen(Screen.WEB)
        } else {
            DiagLog.i(TAG, "onCreate: 分支3 停留连接屏（无保存 url 且 WebView 非正式页面：$currentUrl）")
        }
    }

    /** 启动时从保存的 SSH 配置恢复隧道，成功后把 WebView 指向新的本地端口 URL。 */
    private fun autoConnectSsh() {
        val savedSsh = SshConfig.load(prefs)
        if (savedSsh == null || !savedSsh.isComplete) {
            showScreen(Screen.CONNECT); return
        }
        val app = application as DshApp
        // 用户可能已在连接屏手动点了「连接」：自动恢复不抢占
        if (!beginConnect()) { DiagLog.i(TAG, "autoConnectSsh: 连接进行中，让位给手动连接"); return }
        status("自动重建 SSH 隧道…")
        showScreen(Screen.CONNECT)
        DiagLog.i(TAG, "autoConnectSsh: 冷启动恢复 " +
            "${savedSsh.user}@${savedSsh.host}:${savedSsh.port} → " +
            "远端 ${savedSsh.remoteHost}:${savedSsh.remotePort} auth=${savedSsh.authType}")
        Thread {
            if (savedSsh.usesKey && savedSsh.keyPath.isBlank()) {
                DiagLog.w(TAG, "autoConnectSsh: 私钥路径为空，放弃自动恢复")
                onUi { status("私钥路径为空，请到连接屏重新填写"); endConnect() }
                return@Thread
            }
            // 非强制：后台服务可能已经用同一份配置建好了隧道，直接复用（不必重拨）
            val tunnel = app.ensureTunnel(savedSsh, force = false)
            val base = tunnel?.localBaseUrl
            DiagLog.i(TAG, "autoConnectSsh: ensureTunnel(force=false) → base=$base")
            // 失败先退：隧道没起来就不再干跑 token 探测（4×8s 白等）
            if (tunnel == null || base == null) {
                DiagLog.w(TAG, "autoConnectSsh: 隧道未建立，回连接屏等用户手动重试")
                onUi { status("自动连接失败，请在连接屏手动重试"); refreshConnectState(); endConnect() }
                return@Thread
            }
            // 自动获取最新 token（服务重启后旧 token 失效；失败静默回退）
            autoFetchToken(tunnel)
            onUi {
                // lastUrl 与 prefs["url"] 由 connectWeb 自己写，这里不必再来一遍。
                showScreen(Screen.WEB)
                sshTokenAck = false
                connectWeb(base)
                refreshConnectState()
                endConnect()
            }
        }.apply { name = "ssh-autoconnect"; isDaemon = true }.start()
    }

    // ── 连接屏 UI ─────────────────────────────────────────────
    private fun createConnectView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COL_BG)
            isFillViewport = true
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        scroll.addView(outer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(20), dp(22), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        outer.addView(card)

        // 三种文本角色（原先是三个几乎同构的局部工厂，只有字号/字色/粗体/字距不同）
        fun label(text: String): TextView =
            UiKit.text(this@MainActivity, text, 11f, COL_DIM, letterSpacing = 0.12f)

        fun input(hint: String, prefill: String = "", pwd: Boolean = false, number: Boolean = false): EditText =
            EditText(this@MainActivity).apply {
                this.hint = hint
                textSize = 14f
                setTextColor(COL_TEXT)
                setHintTextColor(COL_HINT)
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setSingleLine(true)
                setHorizontallyScrolling(true)
                when {
                    pwd -> inputType = (InputType.TYPE_CLASS_TEXT
                        or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                    number -> inputType = InputType.TYPE_CLASS_NUMBER
                    else -> inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                if (prefill.isNotEmpty()) setText(prefill)
            }

        fun segment(text: String, initial: Boolean = false): RadioButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 12f
                isChecked = initial
                buttonDrawable = null
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                setSingleLine(true)
                setMinWidth(0)
                setMinimumWidth(0)
                setMinEms(0)
                setMaxEms(5)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setBackgroundResource(R.drawable.bg_segment)
                setTextColor(if (initial) COL_ACCENT_TEXT else COL_TEXT)
                setOnCheckedChangeListener { _, checked ->
                    setTextColor(if (checked) COL_ACCENT_TEXT else COL_TEXT)
                }
            }

        /**
         * 密码行：输入框 + 显示/隐藏切换（避免密码框永远黑点）。
         */
        fun pwdRow(field: EditText): View {
            // 按钮要在自己的点击回调里改自己的文案，所以先建后挂监听。
            val showBtn = UiKit.button(this@MainActivity, "显示", UiKit.Style.SECONDARY, textSize = 12f) {}
            showBtn.setTextColor(COL_DIM)
            showBtn.setOnClickListener {
                val wasMasked = field.transformationMethod != null
                field.transformationMethod = if (wasMasked) null
                    else android.text.method.PasswordTransformationMethod.getInstance()
                field.text?.let { field.setSelection(it.length) }
                // 刚揭开（现在可见）→ 按钮变「隐藏」；刚遮上 → 变「显示」
                showBtn.text = if (wasMasked) "隐藏" else "显示"
                showBtn.setTextColor(if (wasMasked) COL_ACCENT else COL_DIM)
            }
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(field, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
                addView(showBtn, LinearLayout.LayoutParams(dp(56), dp(42)))
            }
        }

        // ── 头部 ──────────────────────────────────────────────
        val headerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        headerRow.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "DSH Handheld"
                textSize = 18f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setTextColor(COL_TITLE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "DeepSeek Harness · 手机端"
                textSize = 10f
                setTextColor(COL_MUTED)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        // 诊断入口放在头部：隧道坏掉时，这一屏正是唯一还能操作的地方
        headerRow.addView(UiKit.text(this@MainActivity, "诊断", 12f, COL_DIM).apply {
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnClickListener { showDiagPage() }
        })
        card.addView(headerRow, rowParams(top = dp(2), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 三步连接引导：选模式 → 填连接信息 → 连接中 ──────────────────
        // 关键字段引用保留为类字段（连接流程/状态钩子复用）
        var step2Card: LinearLayout? = null   // 填信息
        var step3Card: LinearLayout? = null   // 连接中
        var connectMainBtn: Button? = null    // 底部主按钮（每步复用）
        var webMode = true
        // 预填用：这里**不做完整性校验**（地址填了、账号还没填也要把地址带出来）
        val savedSsh = SshConfig.load(prefs)

        // 步骤容器（后续在 card 内按顺序 addView）
        fun stepLabel(text: String): TextView =
            UiKit.text(this@MainActivity, text, 13f, COL_TITLE, bold = true)

        fun stepHint(text: String): TextView =
            UiKit.text(this@MainActivity, text, 11f, COL_MUTED)

        // ── Step 1：你想做什么 ─────────────────────────────────────────
        step1Card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        step1Card!!.addView(stepLabel("你想做什么？"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        step1Card!!.addView(stepHint("手机连上你电脑上的 DeepSeek Harness。"), rowParams(top = dp(2), width = ViewGroup.LayoutParams.MATCH_PARENT))

        fun modeCard(text: String, sub: String): Pair<LinearLayout, (Boolean) -> Unit> {
            val title = TextView(this@MainActivity).apply {
                this.text = text
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COL_TEXT)
                setIncludeFontPadding(false)
                setSingleLine(true)
            }
            val desc = TextView(this@MainActivity).apply {
                this.text = sub
                textSize = 10f
                setTextColor(COL_MUTED)
                setIncludeFontPadding(false)
                setSingleLine(true)
            }
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(12), dp(10), dp(10))
                setBackgroundResource(R.drawable.bg_mode_card_off)
                addView(title)
                addView(desc, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) })
            }
            val paint = { on: Boolean ->
                card.setBackgroundResource(if (on) R.drawable.bg_mode_card_on else R.drawable.bg_mode_card_off)
                title.setTextColor(if (on) COL_ACCENT_TEXT else COL_TEXT)
                desc.setTextColor(if (on) 0x55000000.toInt() else COL_MUTED)
            }
            return card to paint
        }
        val (webModeCard, paintWeb) = modeCard("看 dsh 网页", "浏览会话、聊天")
        val (termModeCard, paintTerm) = modeCard("打开终端", "远程敲命令")
        fun selectMode(web: Boolean) {
            webMode = web
            paintWeb(web)
            paintTerm(!web)
            // 主按钮文案跟模式：创建早于 connectMainBtn，故用可空调用
            connectMainBtn?.text = if (web) "连上并打开 dsh 网页" else "连上并打开终端"
        }
        webModeCard.setOnClickListener { selectMode(true) }
        termModeCard.setOnClickListener { selectMode(false) }
        val modeRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(webModeCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(6) })
            addView(termModeCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(6) })
        }
        step1Card!!.addView(modeRow, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))
        selectMode(true)
        // 小字：避免选择负担
        step1Card!!.addView(stepHint("· 看 dsh 网页：管理和浏览电脑上的 dsh 界面\n· 打开终端：远程敲命令，像在电脑前一样"),
            rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step1Card!!.addView(UiKit.button(this@MainActivity, "下一步", UiKit.Style.PRIMARY) {
            showStep(ConnectStep.CREDENTIALS)
        }, rowParams(top = dp(12), height = dp(46), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Step 2：连接信息 ───────────────────────────────────────────
        step2Card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        step2Card!!.addView(stepLabel("连接信息"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(stepHint("你电脑上运行 dsh 的地址和登录账号。"),
            rowParams(top = dp(2), width = ViewGroup.LayoutParams.MATCH_PARENT))

        step2Card!!.addView(label("电脑地址"), rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshHostInput = input("IP 地址", savedSsh?.host ?: "")
        val sshPortInput = input("22", savedSsh?.port?.toString() ?: "22", number = true)
        val sshHostRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sshHostInput, LinearLayout.LayoutParams(0, dp(42), 3f).apply { marginEnd = dp(8) })
            addView(sshPortInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        step2Card!!.addView(sshHostRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(stepHint("端口一般用 22，不用改。"), rowParams(top = dp(4), width = ViewGroup.LayoutParams.MATCH_PARENT))

        step2Card!!.addView(label("登录账号"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshUserInput = input("用户名", savedSsh?.user ?: "")
        step2Card!!.addView(sshUserInput, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 登录方式（主区常显）：密码 / 私钥 —— 选哪个就显示哪一套字段
        step2Card!!.addView(label("登录方式"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val authPassBtn = segment("密码", true)
        val authKeyBtn = segment("私钥", false)
        val authGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(authPassBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
            addView(authKeyBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        }
        step2Card!!.addView(authGroup, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 密码分支：标题 + 输入框打包，随登录方式整体显隐
        //（此前标题无条件显示、输入框单独 GONE，选私钥后主区会剩一个空标题）
        val sshPassInput = input("密码", savedSsh?.password ?: "", pwd = true)
        val passRow = pwdRow(sshPassInput)
        val passBlock = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("电脑登录密码"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(passRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        }
        step2Card!!.addView(passBlock, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 私钥分支
        val keyPathInput = input("点「导入」选文件，或直接填路径", savedSsh?.keyPath ?: "")
        keyPathInput.isFocusable = true
        sshKeyPathInput = keyPathInput
        val browseKeyBtn = UiKit.button(this@MainActivity, "导入", UiKit.Style.SECONDARY, textSize = 12f) {
            pickSshKey()
        }
        val keyPathRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyPathInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
            addView(browseKeyBtn, LinearLayout.LayoutParams(dp(56), dp(42)))
        }
        val keyPassInput = input("没有就留空", savedSsh?.keyPass ?: "", pwd = true)
        val keyPassRow = pwdRow(keyPassInput)
        val keyBlock = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("私钥路径"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(keyPathRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(label("私钥口令（可选）"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(keyPassRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        }
        step2Card!!.addView(keyBlock, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 认证方式切换：两套字段整体显隐（无折叠区，不会出现「字段藏在别处」的状态）
        val savedAuthType = savedSsh?.authType ?: SshConfig.AUTH_PASSWORD
        if (savedAuthType == SshConfig.AUTH_KEY) authKeyBtn.isChecked = true
        fun syncAuthFields() {
            val key = authKeyBtn.isChecked
            passBlock.visibility = if (key) View.GONE else View.VISIBLE
            keyBlock.visibility = if (key) View.VISIBLE else View.GONE
        }
        authGroup.setOnCheckedChangeListener { _, _ ->
            syncAuthFields()
            // 切登录方式后旧的校验错误会跟当前状态矛盾（如已是密码模式却提示「请填写私钥路径」）
            status("")
        }
        syncAuthFields()

        // dsh 端口：dsh 网页在你电脑上的端口
        step2Card!!.addView(label("dsh 端口"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshTargetPortInput = input(
            "3080",
            (savedSsh?.remotePort ?: DEFAULT_PORT.toInt()).toString(),
            number = true
        )
        step2Card!!.addView(sshTargetPortInput, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(stepHint("dsh 网页的端口，默认 3080，一般不用改。"),
            rowParams(top = dp(4), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // Step 2 底部：上一步 + 主按钮（文案跟模式）
        val step2Nav = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        step2Nav.addView(UiKit.button(this@MainActivity, "上一步", UiKit.Style.SECONDARY, textSize = 12f) {
            showStep(ConnectStep.MODE)
        }, LinearLayout.LayoutParams(dp(88), dp(46)).apply { marginEnd = dp(6) })
        connectMainBtn = UiKit.button(
            this@MainActivity,
            if (webMode) "连上并打开 dsh 网页" else "连上并打开终端",
            UiKit.Style.PRIMARY
        ) {
            sshTokenAck = false

            // 校验
            val sh = sshHostInput.text.toString().trim()
            val su = sshUserInput.text.toString().trim()
            val sport = sshPortInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22
            val target = sshTargetPortInput.text.toString().trim().ifEmpty { DEFAULT_PORT }
                .toIntOrNull()?.coerceIn(1, 65535) ?: 3080
            if (sh.isBlank() || su.isBlank()) { status("请填写电脑地址和登录账号", true); guideBackToStep2(); return@button }
            val auth = if (authKeyBtn.isChecked) {
                val path = keyPathInput.text.toString().trim()
                if (path.isBlank()) { status("请填写私钥路径，或点「导入」选文件", true); guideBackToStep2(); return@button }
                val keyFile = File(path)
                if (!keyFile.exists()) { status("私钥文件不存在：$path", true); guideBackToStep2(); return@button }
                SshTunnel.Auth.KeyPair(keyFile, keyPassInput.text.toString().ifEmpty { null })
            } else {
                if (sshPassInput.text.toString().isEmpty()) { status("请填写电脑登录密码", true); guideBackToStep2(); return@button }
                SshTunnel.Auth.Password(sshPassInput.text.toString())
            }
            if (!webMode) {
                persistSshConfig(sh, sport, su, target, auth)
                startActivity(Intent(this@MainActivity, TuiActivity::class.java))
                return@button
            }
            if (!beginConnect()) { guideBackToStep2(); return@button }
            guideLine(1, "① 检查电脑 正在连接…", state = true)
            connectViaSsh(sh, sport, su, target, auth)
        }
        step2Nav.addView(connectMainBtn!!, LinearLayout.LayoutParams(0, dp(46), 1f))
        step2Card!!.addView(step2Nav, rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Step 3：连接中 ─────────────────────────────────────────────
        // 行内容由类级 guideLine* 更新（connectViaSsh/autoConnectSsh/onPageFinished 共用）
        fun guideRow(text: String): TextView =
            UiKit.text(this@MainActivity, text, 13f, COL_MUTED).apply { setIncludeFontPadding(false) }
        val line1 = guideRow("① 检查电脑 等待连接…")
        val line2 = guideRow("② 建立安全通道")
        val line3 = guideRow("③ 打开 dsh 网页")
        stepGuideLine1 = line1
        stepGuideLine2 = line2
        stepGuideLine3 = line3

        step3Card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        step3Card!!.addView(stepLabel("连接中…"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(line1, rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(line2, rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(line3, rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(UiKit.button(this@MainActivity, "返回修改", UiKit.Style.SECONDARY, textSize = 12f) {
            guideBackToStep2()
        }, rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))
        stepGuideCard = step3Card
        stepGuideStep2 = step2Card

        // ── 组装：header → step1 → step2 → step3 ──────────────────────
        card.addView(step1Card, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(step2Card!!.apply { visibility = View.GONE }, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(step3Card!!.apply { visibility = View.GONE }, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))

        card.addView(spacer(dp(10)))

        // 状态条（全局错误/连接进度；Step3 行内也有状态）
        statusView = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            minHeight = dp(28)
            setBackgroundResource(R.drawable.bg_status_pill)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            visibility = View.GONE
        }
        card.addView(statusView, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 回到网页（隧道还活着、WebView 里还有页面时才显示）：
        // 从网页按返回后再点一下就能回去，不必重建隧道（重建会换本地端口）
        backToWebButton = UiKit.button(this@MainActivity, "回到网页", UiKit.Style.PRIMARY, textSize = 12f) {
            if (webView?.url?.startsWith("http") == true) {
                showScreen(Screen.WEB)
            } else {
                // WebView 已空（如断开后）→ 有隧道就重新加载，没有则提示重连
                val url = lastUrl
                val tunneled = (application as DshApp).sshTunnel != null
                if (tunneled && !url.isNullOrBlank()) {
                    sshTokenAck = false
                    connectWeb(url)
                } else {
                    status("网页已关闭，请重新连接", true)
                }
            }
        }
        card.addView(backToWebButton!!, rowParams(top = dp(10), height = dp(36), width = ViewGroup.LayoutParams.MATCH_PARENT))
        backToWebButton!!.visibility = View.GONE

        // 断开连接（仅隧道运行时显示）
        disconnectButton = UiKit.button(this@MainActivity, "断开连接", UiKit.Style.ERROR, textSize = 12f) {
            disconnectCurrent()
        }
        card.addView(disconnectButton!!, rowParams(top = dp(10), height = dp(36), width = ViewGroup.LayoutParams.MATCH_PARENT))
        disconnectButton!!.visibility = View.GONE


        // 上次连接摘要（单行，非空时显示）
        savedSsh?.takeIf { it.host.isNotBlank() }?.let {
            card.addView(UiKit.singleLineText(
                this@MainActivity,
                "上次连接: ${it.user}@${it.host}:${it.port} → ${it.remoteHost}:${it.remotePort}",
                10f, COL_DIM, Gravity.CENTER
            ), rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))
        }

        return scroll
    }

    private fun pickSshKey() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_KEY)
        } catch (_: Exception) {
            status("无法打开文件选择器")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_KEY || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val dest = File(filesDir, "ssh_private_key")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: run { status("读取私钥失败"); return }
            sshKeyPathInput?.setText(dest.absolutePath)
            status("私钥已导入到应用私有目录")
        } catch (e: Exception) {
            status("导入私钥失败：${e.message ?: "未知错误"}")
        }
    }

    private fun status(msg: String, err: Boolean = false) {
        statusView?.text = msg
        statusView?.setTextColor(if (err) COL_ERROR else COL_MUTED)
        statusView?.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    // ── 下载转交 ─────────────────────────────────────────────
    /**
     * 把 WebView 拦到的下载交给系统 DownloadManager。
     *
     * 两个坑都写在这里，免得下次又被同一处咬：
     *
     * 1. **认证**：dsh 跑在 SSH 隧道后面（`http://127.0.0.1:<port>`），认证是 cookie
     *    （`dsh-auth-*`）。DownloadManager 在 system_server 里取 URL，**不带** WebView 的
     *    cookie jar，不显式塞 `Cookie` 头就会下到一个 401 的 HTML 页面。
     * 2. **落点**：API 29+ 的公共 Downloads 目录不需要任何权限（走 MediaStore）；
     *    API 26–28 写公共目录要 `WRITE_EXTERNAL_STORAGE`，这里退到应用专属外部目录，
     *    宁可落点差一点也不要为此申请一个存储权限。
     */
    private fun enqueueDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val filename = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (e: Exception) {
            "dsh-download"
        }
        DiagLog.i(TAG, "onDownloadStart: name=$filename mime=$mimeType url=${url.take(140)}")
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType ?: "application/octet-stream")
                setTitle(filename)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                } else {
                    setDestinationInExternalFilesDir(
                        this@MainActivity, Environment.DIRECTORY_DOWNLOADS, filename
                    )
                }
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "已开始下载 $filename", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // 失败要说出来：dsh 前端只会显示「已交给浏览器」，它并不知道有没有落地
            DiagLog.e(TAG, "下载转交失败：${e.message}")
            Toast.makeText(this, "下载失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
    }

    // ── WebView 直连 ─────────────────────────────────────────
    /**
     * 通过 SSH 自动获取 dsh web 的浏览器认证 token（服务端 journald 日志）。
     *
     * dsh 0.1.2+ 的 launch token 每次服务重启会变化，手动查日志很麻烦；
     * SSH 模式下 App 直接代跑 journalctl 提取最新 token 并存入 prefs，
     * 用户不再需要手输。失败（服务端无 journald/Unit 名不同等）时静默
     * 返回 null，连接流程回退到已存/手输 token。
     *
     * 命令选择：优先 user unit；兜底全量 user journal（2 个候选，都会试）。
     * 只认 `?token=` 后 base64url 字符（服务重启后旧 token 行仍在日志里，
     * 取最后一行 = 当前进程的 token）。
     */
    private fun autoFetchToken(tunnel: SshTunnel): String? {
        // SSH 非交互会话通常没有 XDG_RUNTIME_DIR，而 journalctl --user 依赖它
        // 定位用户会话的 systemd 实例 —— 显式导出（UID 1000 为主流桌面/服务器用户）。
        val xdg = "export XDG_RUNTIME_DIR=/run/user/$(id -u 2>/dev/null || echo 1000); "
        // 实测教训（树莓派）：部分宿主 user journal 不在（journalctl --user 报
        // No journal files），但 system journal 可按 _SYSTEMD_USER_UNIT 过滤。
        // 候选顺序：user-unit → system-unit 过滤 → 全量 user → 全量 system → 日志文件。
        val commands = arrayOf(
            // 候选 1：system journal 按用户单元过滤（user journal 缺失时仍可用）
            "$xdg journalctl _SYSTEMD_USER_UNIT=dsh-web.service -n 200 --no-pager 2>/dev/null " +
                "| grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2",
            // 候选 2：dsh-web service（systemd user unit，常见部署）
            "$xdg journalctl --user -u dsh-web.service -n 200 --no-pager 2>/dev/null " +
                "| grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2",
            // 候选 3：全量 system journal 找 dsh（Unit 名不同/非 systemd 时兜底）
            "journalctl -n 500 --no-pager 2>/dev/null " +
                "| grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2",
            // 候选 4：常见日志文件（手动 nohup 等部署）
            "for f in ~/.dsh/web.log ~/.dsh/web_log ~/.dsh/dsh-web.log; do " +
                "grep -oE 'token=[A-Za-z0-9_-]+' \"\$f\" 2>/dev/null; done | tail -1 | cut -d= -f2"
        )
        for ((i, cmd) in commands.withIndex()) {
            val out = tunnel.execOnce(cmd)
            DiagLog.i(TAG, "autoFetchToken: cmd#$i rc=${if (out == null) "null" else "len=" + out.length}")
            val token = out?.trim()
            if (!token.isNullOrEmpty() && token.length >= 40) {
                // 只记长度：令牌前缀本身也是凭据（此前记了 take(8)，等于往 logcat 写半个口令）
                DiagLog.i(TAG, "autoFetchToken: SUCCESS len=${token.length}")
                SecurePrefs.putString(prefs, PREF_SERVER_TOKEN, token)
                return token
            }
        }
        DiagLog.w(TAG, "autoFetchToken: all commands failed/long-empty; fall back to manual token")
        return null
    }

    /**
     * 加载 dsh web。DSH 0.1.2+ 的浏览器认证：
     *  - 有 token 且尚未种下 cookie：加载 `/?token=…`，服务端 303 → 换 `Set-Cookie`
     *    → 自动跳转干净 `/`（cookie 之后由 WebView 持久持有，无需再带 token）；
     *  - 已认证（上次成功加载过 / cookie 仍在）：直接加载干净 URL。
     *
     * 注意：SSH 隧道断线重连会换本地端口（cookie 按 host:port 绑定失效），
     * 此时 [sshTokenAck] 为 false，会重新走 token 交换。
     */
    private fun connectWeb(url: String) {
        status("连接中… $url")
        showScreen(Screen.WEB)
        lastUrl = url
        unauthorizedCleanTried = false
        loadRetriesLeft = 3
        prefs.edit().putString("url", url).apply()
        val token = SecurePrefs.getString(prefs, PREF_SERVER_TOKEN)?.trim().orEmpty()
        val needsToken = token.isNotEmpty() && !sshTokenAck
        // 认证决策是 401/431 类问题的第一现场：是否带 token、cookie jar 是否清了、
        // 最终请求的 URL 长什么样，全部留痕（token 本身不记，只记长度）。
        DiagLog.i(TAG, "connectWeb: url=$url ack=$sshTokenAck " +
            "tokenLen=${token.length} needsToken=$needsToken")
        if (needsToken) {
            // 431 修复（实测根因）：每次 token 交换 WebView 会追加一个 365 天有效的
            // dsh-auth-* cookie（127.0.0.1 同 host），累计 69 个 ≈ 15.5KB 顶到
            // 服务器 maxHeaderSize 16KB → HTTP 431 → 页面永远加载失败。
            // 要重新认证时先清空 cookie jar（本 WebView 唯一用途就是这一页；
            // 服务端会在 /?token= 交换后下发新 cookie）。
            DiagLog.i(TAG, "connectWeb: 清空 cookie jar（防 dsh-auth-* 累积 → HTTP 431）")
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
        }
        webView?.loadUrl(if (needsToken) "$url/?token=$token" else url)
    }

    // ── SSH 隧道（纯 WebView 用）─────────────────────────────
    private fun connectViaSsh(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, auth: SshTunnel.Auth) {
        status("SSH 隧道建立中… $sshUser@$sshHost")
        guideStep3Show()
        val app = application as DshApp
        val cfg = buildSshConfig(sshHost, sshPort, sshUser, remotePort, auth)
        DiagLog.i(TAG, "connectViaSsh: 手动连接（强制重建）$sshUser@$sshHost:$sshPort → 远端 $remotePort " +
            "auth=${if (auth is SshTunnel.Auth.KeyPair) "key" else "password"}")
        Thread {
            // 用户明确点了连接 → 强制重建（可能正是一条他自己觉得有问题的隧道）
            val tunnel = app.ensureTunnel(cfg, force = true)
            val base = tunnel?.localBaseUrl
            DiagLog.i(TAG, "connectViaSsh: ensureTunnel(force=true) → base=$base")
            // 失败先退：隧道没起来就不再干跑 token 探测（4×8s 白等）
            if (tunnel == null || base == null) {
                DiagLog.w(TAG, "connectViaSsh: 隧道建立失败")
                onUi {
                    status("隧道建立失败（检查 SSH 主机/端口/用户/认证）")
                    // 提示词跟登录方式：私钥用户看到「检查密码」会懵
                    val what = if (auth is SshTunnel.Auth.KeyPair) "私钥" else "密码"
                    guideLine(1, "① 检查电脑 ✗ 连不上你的电脑（检查地址/账号/$what）", state = null)
                    guideLine(2, "② 建立安全通道 未开始", state = true)
                    // ensureTunnel 已把旧隧道关掉，这里必须刷新，否则
                    // 「回到网页 / 断开连接」会留在屏幕上指向一个已死的隧道
                    refreshConnectState()
                    endConnect()
                }
                return@Thread
            }
            onUi { guideLine(1, "① 检查电脑 ✓ 已连上电脑", state = false) }
            // 自动获取最新 token（服务重启后旧 token 失效；失败静默回退）
            val token = autoFetchToken(tunnel)
            onUi {
                DiagLog.i(TAG, "connectViaSsh: token=${if (token != null) "已获取" else "未获取（仍尝试打开）"}")
                if (token != null) guideLine(2, "② 建立安全通道 ✓ 已连通", state = false)
                else guideLine(2, "② 建立安全通道 ⚠ 未获取令牌，仍尝试打开", state = null)
                persistSshConfig(sshHost, sshPort, sshUser, remotePort, auth)
                sshTokenAck = false
                connectWeb(base)
                refreshConnectState()
                endConnect()
            }
        }.apply { name = "ssh-connect"; isDaemon = true }.start()
    }

    /**
     * 把连接屏的输入与认证信息组装成 [SshConfig]（持久化与建隧道共用一份）。
     *
     * 字段定义在 SshConfig —— 此前这里、`DshApp` 的指纹函数、`TuiActivity` 的
     * dbclient 启动各写一遍，加字段漏一处就是「保存了但没生效」。
     */
    private fun buildSshConfig(
        sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, auth: SshTunnel.Auth
    ): SshConfig {
        val base = SshConfig(
            host = sshHost,
            port = sshPort,
            user = sshUser,
            remotePort = remotePort,
        )
        return when (auth) {
            is SshTunnel.Auth.Password -> base.copy(
                authType = SshConfig.AUTH_PASSWORD, password = auth.password
            )
            is SshTunnel.Auth.KeyPair -> base.copy(
                authType = SshConfig.AUTH_KEY,
                keyPath = auth.privateKeyFile.absolutePath,
                keyPass = auth.passphrase ?: "",
            )
        }
    }

    private fun persistSshConfig(
        sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, auth: SshTunnel.Auth
    ) {
        try {
            SshConfig.save(prefs, buildSshConfig(sshHost, sshPort, sshUser, remotePort, auth))
        } catch (_: Exception) {}
    }

    // ── Basic Auth（WebView 隧道/反代场景）────────────────────
    private fun showAuthDialog(handler: HttpAuthHandler, host: String?) {
        if (pendingAuth != null) { handler.cancel(); return }
        pendingAuth = handler
        val usernameInput = EditText(this).apply {
            hint = "username"
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val passwordInput = EditText(this).apply {
            hint = "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(usernameInput)
            addView(passwordInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        AlertDialog.Builder(this)
            .setTitle("${host ?: "服务器"}需要登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                pendingAuth?.proceed(usernameInput.text.toString(), passwordInput.text.toString()); pendingAuth = null
            }
            .setNegativeButton("取消") { _, _ -> pendingAuth?.cancel(); pendingAuth = null }
            .setOnDismissListener { pendingAuth = null }
            .show()
    }

    // ── 整屏提示页（错误页 / 401 令牌页）────────────────────────
    /** 提示页上的一个按钮。 */
    private class OverlayAction(
        val text: String,
        val primary: Boolean,
        val onClick: () -> Unit
    )

    /**
     * 在 WebView 上覆盖一屏提示（标题 / 副标题 / 若干按钮）。
     *
     * 两个页面共用同一个容器 [errorView]，但**每次显示都重写全部文本与按钮**。
     * 早前的实现是「谁先构造谁定文案」：401 令牌页从不写副标题，于是先出网络错误、
     * 后出 401 时，页面会顶着「需要访问令牌」的标题配一条过期的 `HTTP xxx`；反向同理。
     * 按钮改为每次重建，两页各自的按钮文案/动作不同也不再互相污染。
     */
    private fun showOverlay(title: String, subtitle: String, actions: List<OverlayAction>) {
        val box = errorView as? LinearLayout ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(UiKit.text(this@MainActivity, "", 22f, COL_TEXT).apply { gravity = Gravity.CENTER })
            addView(
                UiKit.text(this@MainActivity, "", 14f, COL_MUTED).apply { gravity = Gravity.CENTER },
                rowParams(top = dp(8))
            )
            errorView = this
            (webView?.parent as? ViewGroup)?.addView(this)
        }
        (box.getChildAt(0) as? TextView)?.text = title
        (box.getChildAt(1) as? TextView)?.text = subtitle
        while (box.childCount > 2) box.removeViewAt(2)
        actions.forEachIndexed { index, action ->
            box.addView(
                UiKit.button(
                    this,
                    action.text,
                    if (action.primary) UiKit.Style.PRIMARY else UiKit.Style.SECONDARY,
                    onClick = action.onClick
                ),
                rowParams(top = dp(if (index == 0) 24 else 12), height = dp(48), width = dp(200))
            )
        }
        box.visibility = View.VISIBLE
    }

    private fun showErrorPage(message: String) {
        showOverlay(
            "连接失败", message,
            listOf(
                OverlayAction("重试", true) {
                    hideErrorPage(); lastUrl?.let { sshTokenAck = false; connectWeb(it) }
                },
                OverlayAction("换服务器", false) { hideErrorPage(); showConnectScreen() }
            )
        )
    }

    private fun hideErrorPage() { errorView?.visibility = View.GONE }

    // ── 诊断信息页（机内取证）────────────────────────────────────
    /**
     * 把「出问题时唯一的证据」放到手机上。
     *
     * 存在的理由见 [DiagLog] 的类注释：普通应用**读不到 logcat**，而 logcat 本身也只是内存
     * 环形缓冲 —— 在这台三星上被每帧一条的 `View.setRequestedFrameRate` 冲掉，5 MiB 撑不到
     * 5 分钟。于是「手机上出了问题却没有任何记录」成了最后的取证缺口。
     *
     * 本页内容 = 上次退出原因（系统落盘的 `ApplicationExitInfo`，应用自己读得到）
     * + 本次运行的内存日志 + 磁盘日志尾部（含上一次运行）。
     *
     * 收集放在后台线程：里面要调 `isHealthy()`（真流量探针，会阻塞到超时）。
     */
    private fun showDiagPage() {
        val box = diagView as? LinearLayout ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            diagView = this
            (webView?.parent as? ViewGroup)?.addView(this)
        }
        box.removeAllViews()
        box.addView(UiKit.text(this, "诊断信息", 20f, COL_TITLE, bold = true))

        val body = TextView(this).apply {
            textSize = 10f
            setTextColor(COL_MUTED)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "（正在收集…）"
        }
        diagBody = body
        box.addView(
            ScrollView(this).apply { setBackgroundColor(COL_BG); addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(10) }
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            UiKit.button(this, "复制全部", UiKit.Style.SECONDARY) {
                val text = body.text.toString()
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("dsh-handheld 诊断信息", text))
                Toast.makeText(this, "已复制 ${text.length} 字", Toast.LENGTH_SHORT).show()
            },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) }
        )
        row.addView(
            UiKit.button(this, "关闭", UiKit.Style.PRIMARY) { diagView?.visibility = View.GONE },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )
        box.addView(row, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))
        box.visibility = View.VISIBLE

        // ⚠️ WebView 的方法**只能在主线程**调用（`webView.url` 也不例外）。
        // 采集在后台线程跑，所以必须在起线程**之前**把它读下来 ——
        // 0.1.6 就是漏了这一步，一按「诊断」就
        // `A WebView method was called on thread 'diag-collect'` 崩掉。
        val webUrl = webView?.url
        Thread {
            // 诊断页自己绝不能把 App 弄死：收集失败就显示失败
            val text = try {
                buildDiagText(webUrl)
            } catch (e: Throwable) {
                "（收集失败：${e.javaClass.simpleName}: ${e.message}）"
            }
            onUi { if (diagView?.visibility == View.VISIBLE) diagBody?.text = text }
        }.apply { name = "diag-collect"; isDaemon = true }.start()
    }

    /**
     * 诊断页正文。**在后台线程组装**（[SshTunnel.isHealthy] 会阻塞）。
     *
     * @param webUrl 由调用方在**主线程**上取好传进来 —— 这里不能碰 `webView`。
     */
    private fun buildDiagText(webUrl: String?): String {
        val t = (application as? DshApp)?.sshTunnel
        return buildString {
            append("── 上次退出 ──\n")
            append(DiagLog.lastExitSummary ?: "（无记录：首次运行，或系统低于 Android 11）")
            append("\n\n── 当前状态 ──\n")
            append("版本       ").append(pkgVer()).append('\n')
            append("屏幕       ").append(screen).append(" / ").append(connectStep).append('\n')
            append("隧道       ").append(t?.localBaseUrl ?: "（无）")
            if (t != null) append("   健康=").append(t.isHealthy())
            append('\n')
            append("WebView    ").append(webUrl ?: "（无）").append('\n')
            append("prefs[url] ").append(prefs.getString("url", null) ?: "（无）").append('\n')
            append("\n── 本次运行日志（").append(DiagLog.stats()).append("）──\n")
            append(DiagLog.snapshot().ifEmpty { "（空）" }).append('\n')
            val tail = DiagLog.persistedTail()
            if (tail.isNotEmpty()) {
                append("\n── 磁盘日志尾部（含上一次运行）──\n").append(tail).append('\n')
            }
        }
    }

    private fun pkgVer(): String = try {
        "v${packageManager.getPackageInfo(packageName, 0).versionName}"
    } catch (_: Exception) {
        "v?"
    }

    /**
     * 401 处理。分两段：
     *  1. 若当前 URL 带 `?token=`（token 过期，但 cookie 可能仍有效——服务重启
     *     后 token 更新、cookie 签名密钥持久化），先回退加载干净 URL；
     *  2. 干净 URL 也 401（cookie 确实失效）→ 提示用户更新令牌。
     *
     * 幂等通过 [unauthorizedCleanTried] 标志防死循环（每次 connectWeb 重置）。
     */
    private fun handleUnauthorized(view: WebView?) {
        val url = view?.url ?: lastUrl ?: return
        if (!unauthorizedCleanTried && url.contains("?token=")) {
            unauthorizedCleanTried = true
            sshTokenAck = false
            DiagLog.w(TAG, "401: 带 token 页失败 → 回退干净 URL 重试 url=$url")
            lastUrl?.let { view?.loadUrl(it.substringBefore("?")) }
            return
        }
        DiagLog.w(TAG, "401: 干净 URL 仍失败（cookie 确实失效）→ 令牌提示页 url=$url")
        showTokenPromptPage()
    }

    /**
     * DSH 0.1.2+ 浏览器认证提示页：401 时说明需要一次性启动 token。
     * 令牌由应用自动从服务端日志获取——提供「自动获取并重连」一键处理。
     */
    private fun showTokenPromptPage() {
        showOverlay(
            "需要访问令牌",
            "dsh 0.1.2+ 需要一次性启动令牌（服务重启后旧令牌失效）。\n" +
                "应用会自动从服务端日志重新获取；\n" +
                "若仍失败，请检查服务端 dsh-web.service 是否在运行。",
            listOf(
                OverlayAction("自动获取令牌并重连", true) { reFetchTokenAndReload() },
                OverlayAction("重试", false) {
                    hideErrorPage()
                    lastUrl?.let { sshTokenAck = false; connectWeb(it) }
                }
            )
        )
    }

    /** 401 令牌页：用当前隧道重新自动获取令牌并重载（失败则回到提示页）。 */
    private fun reFetchTokenAndReload() {
        val tunnel = (application as DshApp).sshTunnel
        if (tunnel == null) {
            status("尚无 SSH 隧道，请先连接", true)
            hideErrorPage(); showConnectScreen()
            return
        }
        hideErrorPage()
        status("自动获取令牌…")
        Thread {
            val token = autoFetchToken(tunnel)
            onUi {
                // autoFetchToken 只在拿到 ≥40 字符的 token 时返回非 null，所以这里
                // 只需判空（原先还重判了一次 length < 40，那半段不可达）。
                if (token == null) {
                    status("自动获取令牌失败，请检查服务端 dsh-web.service", true)
                    showTokenPromptPage()
                } else {
                    sshTokenAck = false
                    lastUrl?.let { connectWeb(it) }
                }
            }
        }.apply { name = "token-refetch"; isDaemon = true }.start()
    }

    // ── 生命周期 ──────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        DiagLog.i(TAG, "onResume: url=${webView?.url} tunnel=${(application as DshApp).sshTunnel != null}")
        webView?.onResume(); webView?.resumeTimers()
        refreshConnectState()
        revalidateTunnel()
    }

    /**
     * 回到前台时用**真流量探针**重新确认隧道，必要时重建。
     *
     * 为什么必须有这一步：熄屏一段时间后整个进程会被 Android 冻结（实测
     * `freezer:/frozen`），冻结期间看门狗不运行、dbclient 子进程同样冻在
     * `connect()` 里（解冻时一起报 "Connect failed: Software caused connection abort"）。
     * 等用户回来时隧道往往已经死了，而 [DshApp.sshTunnel] 与 `localBaseUrl` 都还在 ——
     * 直接复用就会把 WebView 留在一条永远加载不完的隧道上。
     *
     * 探针会真的往隧道里发一个 HTTP 请求（见 [SshTunnel.isHealthy]），所以它不会把
     * "端口还在监听"误判成"隧道可用"。健康时什么都不做，不打扰用户。
     */
    private fun revalidateTunnel() {
        val app = application as? DshApp ?: return
        val tunnel = app.sshTunnel ?: return
        // 只在真的停在网页上时才管隧道（webView 还没建好时同样跳过）
        val url = webView?.url
        if (url == null || !url.startsWith("http")) return
        val cfg = SshConfig.load(prefs) ?: return
        if (!cfg.isComplete) return
        Thread {
            if (tunnel.isHealthy()) {
                DiagLog.i(TAG, "revalidateTunnel: 探针通过，复用现有隧道 ${tunnel.localBaseUrl}")
                return@Thread
            }
            DiagLog.w(TAG, "revalidateTunnel: 探针失败 —— 重建隧道")
            // 连接守卫与 status() 都必须在 UI 线程上（beginConnect 失败会写状态栏）
            onUi {
                if (beginConnect()) rebuildTunnel(cfg)
                else DiagLog.i(TAG, "revalidateTunnel: 已有连接在进行，让位")
            }
        }.apply { name = "tunnel-probe"; isDaemon = true }.start()
    }

    /** [revalidateTunnel] 的续作：探针判定隧道已死后重建（已在 UI 线程持好连接守卫）。 */
    private fun rebuildTunnel(cfg: SshConfig) {
        Thread {
            val app = application as DshApp
            // 必须在 ensureTunnel(force=true) **之前**取：它会 close() 旧隧道并把 localBaseUrl 置空
            val prevBase = app.sshTunnel?.localBaseUrl
            val t = app.ensureTunnel(cfg, force = true)
            val base = t?.localBaseUrl
            if (base == null) {
                DiagLog.w(TAG, "rebuildTunnel: 重建失败")
                onUi { status("重连失败，请回连接屏手动重试"); refreshConnectState(); endConnect() }
                return@Thread
            }
            onUi {
                if (base == prevBase) {
                    // **同 origin 重建：不重载页面。** 这是省流量的关键一笔。
                    //
                    // 端口没变 → origin 没变 → cookie 仍有效、WebView 缓存仍能命中，页面自己的
                    // 重试会把后续请求接到新隧道上；整页重载纯属浪费。实测一次冷加载 ≈4.7 MB
                    // （其中 /assets/* 约 446 KB，因为 dsh 没给这些内容哈希命名的文件发
                    // Cache-Control/ETag，每次都必须重新下载），而重载之后 SPA 还要把会话历史
                    // 重新拉一遍 —— 对长会话那才是大头。
                    //
                    // 代价：页面上那条已经断掉的流不会自己恢复，需要手动刷新
                    // （连接屏的「回到网页」就是一次重载）。
                    DiagLog.i(TAG, "rebuildTunnel: 同 origin（$base）→ 不重载页面（省流量）")
                    refreshConnectState()
                    endConnect()
                } else {
                    // origin 变了：服务端 cookie 名含 authority，必然失效 → 必须重走 token 交换并重载
                    DiagLog.i(TAG, "rebuildTunnel: origin 变了（$prevBase → $base）→ 重新加载")
                    autoFetchToken(t)
                    sshTokenAck = false
                    connectWeb(base)
                    refreshConnectState()
                    endConnect()
                }
            }
        }.apply { name = "tunnel-rebuild"; isDaemon = true }.start()
    }

    override fun onDestroy() {
        DiagLog.i(TAG, "onDestroy: isFinishing=$isFinishing（隧道由 DshApp 持有，不随 Activity 销毁）")
        // 先履行生命周期契约：此后所有 onUi 回调与延时任务都作废，避免一个已销毁的实例
        // 去改共享状态（prefs["url"]、保活 WebView、DshApp 的隧道）。
        alive.set(false)
        ui.removeCallbacksAndMessages(null)
        val app = application as? DshApp
        app?.removeTunnelObserver(tunnelObserver)
        // WebView 仍由 DshApp 保活，但必须把它的 context 从本 Activity 上摘下来，
        // 否则旧 Activity（含整棵连接屏视图树）会被这个 Application 级引用拖住。
        app?.releaseWebViewContext()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        DiagLog.i(TAG, "onPause")
        webView?.onPause(); webView?.pauseTimers()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            // 覆盖层优先关掉：否则连接屏的 BACK 语义（moveTaskToBack）会把 App 退到后台、
            // 而诊断页还盖在上面 —— 回来时仍是一个"按什么都没反应"的页面。
            diagView?.visibility == View.VISIBLE -> {
                DiagLog.i(TAG, "BACK: 关闭诊断页")
                diagView?.visibility = View.GONE
            }
            screen == Screen.CONNECT -> {
                DiagLog.i(TAG, "BACK: 连接屏 → 退到后台（隧道保持）")
                moveTaskToBack(true)
            }
            webView?.canGoBack() == true -> {
                DiagLog.i(TAG, "BACK: 网页历史回退 url=${webView?.url}")
                webView?.goBack()
            }
            webView?.url?.startsWith("http") == true -> {
                DiagLog.i(TAG, "BACK: 无历史 → 回连接屏")
                showConnectScreen()
            }
            else -> {
                DiagLog.i(TAG, "BACK: 非网页状态 → 退到后台 url=${webView?.url}")
                moveTaskToBack(true)
            }
        }
    }

    /**
     * 沉浸式全屏：内容画到状态栏/导航栏后面（含刘海）。
     * 同时使用 WindowInsetsController（API 30+ 正道）和传统 systemUiVisibility 标志，
     * 以兼容三星 One UI / 不同 WebView 版本对沉浸式的处理差异。
     */
    private fun applyImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // 让状态栏/导航栏区域透明，WebView 内容从下面一直铺到屏幕边缘
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        window.decorView.post {
            val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                   androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 传统沉浸标志兜底（部分 One UI / 老 WebView 依赖它才能真正全屏）
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ── 小工具 ────────────────────────────────────────────────
    /** 连接守卫：CAS 置位；失败时提示用户等待。 */
    private fun beginConnect(): Boolean {
        if (!connecting.compareAndSet(false, true)) {
            status("正在连接中，请稍候…")
            return false
        }
        return true
    }

    private fun endConnect() { connecting.set(false) }

    // ── 引导流 Step3 状态（类级：connectViaSsh/autoConnectSsh/onPageFinished 共用）────
    private fun guideStep3Show() {
        showStep(ConnectStep.CONNECTING)
        guideLine(1, "① 检查电脑 正在连接…", state = true)
        guideLine(2, "② 建立安全通道", state = true)
        guideLine(3, "③ 打开 dsh 网页", state = true)
    }
    /** 引导行统一按 ①②③ 编号（1 起）；此前映射是 0 基、调用方传 1 基，
     *  导致「① 检查电脑」被写进第二行、② 行永远不更新。 */
    private fun guideLineView(index: Int): TextView? = when (index) {
        1 -> stepGuideLine1
        2 -> stepGuideLine2
        else -> stepGuideLine3
    }

    /**
     * 写引导行。
     *
     * @param state `true` = 进行中（灰），`false` = 完成（亮），`null` = 失败（红）。
     *   三态用可空布尔表达，好过原来三个只差一个颜色的函数（`guideLineDone` 与
     *   `guideLine` 的默认行为本来就完全一致）。
     */
    private fun guideLine(index: Int, text: String, state: Boolean? = true) {
        val v = guideLineView(index) ?: return
        v.text = text
        v.setTextColor(
            when (state) {
                true -> COL_MUTED
                false -> COL_ACCENT
                null -> COL_ERROR
            }
        )
    }
    private fun guideBackToStep2() {
        showStep(ConnectStep.CREDENTIALS)
    }

    /** 关闭并释放当前 SSH 隧道（无则 no-op）。所有权在 DshApp，这里只是转发。 */
    private fun closeCurrentTunnel() {
        (application as DshApp).closeTunnel()
    }

    /** 断开连接：停隧道、清回连 URL、回连接屏。 */
    private fun disconnectCurrent() {
        DiagLog.i(TAG, "disconnectCurrent: 关隧道 + 删 prefs[url] + 载入 about:blank")
        closeCurrentTunnel()
        prefs.edit().remove("url").apply()
        sshTokenAck = false
        unauthorizedCleanTried = false
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        showConnectScreen()
        status("已断开")
    }

    /** 按隧道状态刷新连接屏上的按钮与提示。 */
    private fun refreshConnectState() {
        val tunneled = (application as DshApp).sshTunnel != null
        disconnectButton?.visibility = if (tunneled) View.VISIBLE else View.GONE
        backToWebButton?.visibility =
            if (tunneled && webView?.url?.startsWith("http") == true) View.VISIBLE else View.GONE
        // 「回到网页 / 断开连接」的可见性此前不可观测：连接失败后按钮残留
        // （指向死隧道）就是这类问题，只能靠截图发现。
        DiagLog.i(TAG, "refreshConnectState: tunneled=$tunneled webUrl=${webView?.url} " +
            "backToWeb=${backToWebButton?.visibility == View.VISIBLE} " +
            "disconnect=${disconnectButton?.visibility == View.VISIBLE}")
        // 回到连接屏时清掉残留的进度文案（「连接中… http://127.0.0.1:端口」既过时又是术语）。
        // 隧道不在时不覆盖调用方刚设的错误提示。
        if (tunneled) status("已连上电脑")
    }

    /** 回到连接屏统一收口：一律停在 Step 2，并复位 Step3 的进度行。
     *  Step3 是上一轮连接的残留，直接显示会出现「连接中…」却早已连上的矛盾画面。 */
    private fun showConnectScreen() {
        DiagLog.i(TAG, "showConnectScreen: 回连接屏 Step2（tunnel=${(application as DshApp).sshTunnel != null}）")
        showStep(ConnectStep.CREDENTIALS, resetGuide = true)
        showScreen(Screen.CONNECT)
        refreshConnectState()
    }

    private fun resetGuideLines() {
        guideLine(1, "① 检查电脑 等待连接…", state = true)
        guideLine(2, "② 建立安全通道", state = true)
        guideLine(3, "③ 打开 dsh 网页", state = true)
    }

    /** 失败自动重试：5s 后重载（若隧道仍活；watchdog 会重建死隧道）。每次 connectWeb 重置 3 次余量。 */
    private fun scheduleLoadRetry() {
        if (loadRetriesLeft <= 0) return
        loadRetriesLeft--
        // 用本 Activity 的 Handler，而不是 webView.postDelayed：后者会把本实例的闭包
        // 存进永不释放的保活 WebView，且 onDestroy 无从取消 —— 结果是一个已销毁的
        // Activity 仍能触发共享 WebView 的重载。
        ui.postDelayed({
            if (!alive.get()) {
                DiagLog.i(TAG, "auto-retry 放弃：Activity 已销毁")
                return@postDelayed
            }
            if ((application as DshApp).sshTunnel != null && lastUrl != null) {
                DiagLog.i(TAG, "auto-retry page load (retriesLeft=$loadRetriesLeft) via $lastUrl")
                sshTokenAck = false
                connectWeb(lastUrl!!)
            }
        }, 5000)
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    /** 纵向排列最常用的 LayoutParams；定义在 UiKit（此前本文件里有两份逐字相同的副本）。 */
    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        UiKit.rowParams(top, width, height)

    private fun dp(n: Int) = UiKit.dp(this, n)
}
