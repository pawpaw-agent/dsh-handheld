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
import android.graphics.drawable.GradientDrawable
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
import android.webkit.ValueCallback
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
import androidx.core.widget.doAfterTextChanged
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

    // ── 连接屏（状态优先版）的引用：见 [createConnectView] ────────────────
    /** 贴底动作区那一个主按钮（文案与动作随相位/隧道状态变）。 */
    private var connectMainBtn: Button? = null
    /** 「断开连接」文字链（隧道活着才显示；旧版是与主按钮同权重的整宽按钮）。 */
    private var disconnectLink: TextView? = null
    /** 状态块：色点 + 一句话 + 目标行。 */
    private var heroDot: View? = null
    private var heroTitle: TextView? = null
    private var heroSub: TextView? = null
    /** ①②③ 进度行容器（连接中，以及刚失败还没重试时显示）。 */
    private var progressBlock: View? = null
    /** 连接设置卡：摘要行（折叠时）+ 展开/收起动作 + 表单本体。 */
    private var settingsSummary: TextView? = null
    private var settingsAction: TextView? = null
    private var formBody: View? = null
    /** 表单滚动容器：校验失败要把对应的输入框滚进视野，需要它算偏移。 */
    private var connectScroll: ScrollView? = null
    private var connectContent: LinearLayout? = null

    // 通知开关（连接屏「任务完成时提醒我」）：权限回调要回来改它，故留一份引用。
    private var notifSwitchView: android.widget.Switch? = null
    private var notifSwitchListener: android.widget.CompoundButton.OnCheckedChangeListener? = null
    private var refreshNotifHintView: (() -> Unit)? = null

    // ①②③ 连接进度行（引导流程；null = 界面未构造/非引导状态）
    private var stepGuideLine1: TextView? = null
    private var stepGuideLine2: TextView? = null
    private var stepGuideLine3: TextView? = null

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
     * 连接屏内部的相位（0.1.10 起由「三步向导」改为「状态优先」）。
     *
     * 旧版是三张互斥显示的卡片（选模式 / 填表 / 连接中），用户要按「下一步」走完流程；
     * 现在只有一条主线：状态块常在，表单收进可折叠的「连接设置」卡，主按钮随相位换文案。
     *
     * 取值不是「用户走到哪一步」而是「这一屏现在该长什么样」：
     *  - [IDLE]   常态：看状态 + 一个主按钮（表单折叠）
     *  - [EDIT]   用户主动在改配置（表单展开）
     *  - [CONNECTING] 正在连（进度行 + 主按钮变「取消连接」）
     */
    private enum class ConnectPhase { IDLE, EDIT, CONNECTING }

    private var screen = Screen.CONNECT
    private var connectPhase = ConnectPhase.IDLE

    /**
     * 上一次连接尝试是否以失败告终。
     *
     * 它**不是**一个独立相位：失败后主按钮要回到「连上并打开…」（＝重试），但 ①②③ 里那条
     * 红色的失败原因得留在屏幕上给用户看。用一个布尔把它与相位解耦，比加一个
     * `FAILED` 相位再在两个相位间同步按钮文案简单。
     */
    private var connectFailed = false

    /**
     * 连接尝试计数：每次「连接 / 取消 / 断开」自增。
     *
     * 拨号线程与它的 `onUi` 回调要拿它比对 —— 用户取消之后，那条仍在飞的拨号会走到
     * 「隧道建立失败」分支，把 `已取消连接` 覆盖成 `隧道建立失败（检查 SSH…）`，
     * 甚至在极端时序下把界面切回网页。作废靠代数，不靠取消标志位。
     */
    private var connectAttempt = 0

    /** 连接屏当前选的是「看网页」还是「开终端」。 */
    private var webMode = true

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
     * 切相位。**唯一**改动「表单展开/收起、进度行显示与否」的地方。
     *
     * @param resetGuide 是否把 ①②③ 进度行复位。只有「主动回到连接屏」（[showConnectScreen]）
     *   需要复位——进度行是上一轮连接的残留，不复位会出现「连接中…」却早已连上的矛盾画面；
     *   而在连接中与常态之间来回时**不能**复位，否则刚跑出来的「✓ 已连上」会被抹掉。
     */
    private fun showPhase(phase: ConnectPhase, resetGuide: Boolean = false) {
        if (connectPhase != phase) DiagLog.i(TAG, "connectPhase: $connectPhase → $phase（resetGuide=$resetGuide）")
        connectPhase = phase
        // 开始改配置就不该再挂着上一轮的红字：那条 ① 行说的是「这次尝试失败了」，
        // 用户已经在动手改，它只会误导。
        if (phase == ConnectPhase.EDIT) connectFailed = false
        if (resetGuide) resetGuideLines()
        syncConnectUi()
    }

    /**
     * 把连接屏三块（状态块 / 表单 / 主按钮）按当前事实重画一遍。
     *
     * 单一入口：此前「主按钮文案」「按钮可见性」「状态文案」由 4 处各自设置，
     * 于是出现了「连接中却显示回到网页」（那一下会切到上一轮的页面，而隧道正在重建）。
     * 现在所有分支都只读事实（相位、失败标志、隧道、页面），不记忆上一次画了什么。
     */
    private fun syncConnectUi() {
        val tunneled = (application as DshApp).sshTunnel != null
        val pageAlive = webView?.url?.startsWith("http") == true
        val connecting = connectPhase == ConnectPhase.CONNECTING && !connectFailed

        formBody?.visibility = if (connectPhase == ConnectPhase.EDIT) View.VISIBLE else View.GONE
        settingsSummary?.visibility = if (connectPhase == ConnectPhase.EDIT) View.GONE else View.VISIBLE
        settingsAction?.text = if (connectPhase == ConnectPhase.EDIT) "收起" else "修改 ›"
        progressBlock?.visibility =
            if (connectPhase == ConnectPhase.CONNECTING || connectFailed) View.VISIBLE else View.GONE

        val (title, dotColor) = when {
            connecting -> "连接中…" to UiKit.WARN
            connectFailed -> "连不上你的电脑" to COL_ERROR
            tunneled -> "已连上电脑" to UiKit.OK
            else -> "未连接" to COL_DIM
        }
        heroTitle?.text = title
        heroDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
        heroSub?.text = heroSubtitle(tunneled)

        connectMainBtn?.text = when {
            connecting -> "取消连接"
            !webMode -> "打开终端"
            tunneled && pageAlive -> "打开 dsh 网页"
            else -> "连上并打开 dsh 网页"
        }
        disconnectLink?.visibility = if (tunneled) View.VISIBLE else View.GONE
    }

    /**
     * 状态块下面那行小字：报**电脑**，不报隧道在手机这一头的地址。
     *
     * 原来连着的时候这里显示 `http://127.0.0.1:3080`。那是从实现出发的（它确实是当前在用的
     * 东西），但从用户出发是错的：**127.0.0.1 是手机自己**，看一眼只会让人以为「连到了本机」；
     * 而且它是术语 —— README 里写着连接屏刻意不出现 SSH / 端口 / 令牌。用户想确认的是
     * 「我连的是哪台电脑」，那就该看到那台电脑。
     *
     * 隧道的本地基址没有丢：诊断页（连接屏右上「诊断」）里一直有它，那才是排障该去的地方。
     */
    private fun heroSubtitle(tunneled: Boolean): String {
        val saved = SshConfig.load(prefs)
        if (saved == null || !saved.isComplete) return "还没配置过"
        // SSH 端口是 22 就不写出来：默认值写出来只是噪音（dsh 端口同理，见设置卡摘要）
        val port = if (saved.port == SshConfig.DEFAULT_SSH_PORT) "" else ":${saved.port}"
        return if (tunneled) "${saved.user}@${saved.host}$port"
        else "上次连的是 ${saved.user}@${saved.host}$port"
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
        /** POST_NOTIFICATIONS 的运行时申请（连接屏「任务完成时提醒我」开关）。 */
        const val REQ_NOTIF = 2003
        /** WebView 内 <input type=file> 的文件选择请求（与私钥导入分开）。 */
        const val REQ_WEB_FILE = 2002

        // ── 网页模态与系统 BACK（见 dismissWebModalThenFallback）────────────
        /** 页面里有没有活着的模态对话框（设置页就是其中之一，没有 URL 语义可退）。 */
        const val JS_MODAL_OPEN =
            "(function(){return document.querySelector('[role=dialog][aria-modal=true]')?true:false})()"

        /**
         * 有模态就替用户按一下 Esc。
         *
         * 用 `document.dispatchEvent` 而不是派给某个元素：dsh 的模态自己在 document 上
         * 监听 Escape（`document.addEventListener("keydown", ...)`），派给元素要猜焦点在哪。
         * 返回 true = 确实有模态、Esc 已经发出去了。
         */
        const val JS_ESCAPE_MODAL =
            "(function(){var d=document.querySelector('[role=dialog][aria-modal=true]');" +
                "if(!d)return false;" +
                "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',bubbles:true}));" +
                "return true})()"

        /** 发完 Esc 到复查之间留的时间：React 提交状态需要一帧。 */
        const val MODAL_ESCAPE_SETTLE_MS = 240L

        /**
         * BACK 阶梯的第二级：抽屉开着就点它的遮罩关掉。
         *
         * 判据是宿主自己写的 `data-sidebar-collapsed`（窄屏下 = !narrowExpanded）——
         * 浏览器半边只认这一个真相，App 这边也照它判；点遮罩而不是直接改状态，
         * 是为了让「开合」始终只由页面侧那一份状态决定。
         * 返回 true = 抽屉本来是开的、这一下已经按下去了。
         */
        const val JS_CLOSE_DRAWER =
            "(function(){var f=document.querySelector('[data-handheld=\"frame\"]');" +
                "if(!f||f.hasAttribute('data-sidebar-collapsed'))return false;" +
                "var b=document.querySelector('[data-handheld=\"backdrop\"]');" +
                "if(!b)return false;b.click();return true})()"

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
        const val MOBILE_PLUGIN_REV = "dsh-handheld-mobile-1.0.16"
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
        prefs = getSharedPreferences(DshApp.PREFS, Context.MODE_PRIVATE)

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

                /**
                 * 页面的 `<input type=file>` 要选择器：转交系统文件选择器，选完把 URI 回填。
                 *
                 * 页面里两个入口都走这里（「添加附件」= 任意文件，「回形针」= 图片），
                 * accept 与多选由 dsh 自己写在 input 上，这里原样透传，不做二次过滤 ——
                 * 过滤归 dsh 的校验（它有格式/大小/张数的那一整套文案）。
                 */
                override fun onShowFileChooser(
                    view: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // 上一次没回话的回调必须先作废：留着它页面那个 input 会永远卡在
                    // 「等待选择」，之后再点也不会弹（用户看到的就是「按钮坏了」）。
                    webFileCallback?.onReceiveValue(null)
                    webFileCallback = filePathCallback
                    val accepts = fileChooserParams?.acceptTypes
                        ?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        if (accepts.size == 1 && !accepts[0].contains(",")) {
                            type = accepts[0]
                        } else {
                            type = "*/*"
                            if (accepts.isNotEmpty()) {
                                putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
                            }
                        }
                        if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    DiagLog.i(TAG, "onShowFileChooser: accept=${accepts.joinToString()} " +
                        "multi=${fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE}")
                    return try {
                        startActivityForResult(intent, REQ_WEB_FILE)
                        true
                    } catch (e: Exception) {
                        DiagLog.e(TAG, "onShowFileChooser: 打不开系统文件选择器：${e.message}")
                        webFileCallback = null
                        filePathCallback?.onReceiveValue(null)
                        false
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
        val attempt = ++connectAttempt
        connectFailed = false
        // 自动恢复也走「连接中」相位：这样主按钮显示「取消连接」——
        // 否则用户看着一个「连上并打开」的按钮，点下去只会得到「正在连接中，请稍候」。
        status("")
        showScreen(Screen.CONNECT)
        guideStep3Show()
        guideLine(1, "① 检查电脑 正在恢复连接…", state = true)
        DiagLog.i(TAG, "autoConnectSsh: 冷启动恢复 " +
            "${savedSsh.user}@${savedSsh.host}:${savedSsh.port} → " +
            "远端 ${savedSsh.remoteHost}:${savedSsh.remotePort} auth=${savedSsh.authType} attempt=$attempt")
        Thread {
            if (savedSsh.usesKey && savedSsh.keyPath.isBlank()) {
                DiagLog.w(TAG, "autoConnectSsh: 私钥路径为空，放弃自动恢复")
                onUi {
                    if (connectAttempt != attempt) return@onUi
                    connectFailed = true
                    status("私钥路径为空，请到连接屏重新填写")
                    endConnect()
                }
                return@Thread
            }
            // 非强制：后台服务可能已经用同一份配置建好了隧道，直接复用（不必重拨）
            val tunnel = app.ensureTunnel(savedSsh, force = false)
            val base = tunnel?.localBaseUrl
            DiagLog.i(TAG, "autoConnectSsh: ensureTunnel(force=false) → base=$base attempt=$attempt")
            // 失败先退：隧道没起来就不再干跑 token 探测（4×8s 白等）
            if (tunnel == null || base == null) {
                DiagLog.w(TAG, "autoConnectSsh: 隧道未建立，回连接屏等用户手动重试")
                onUi {
                    if (connectAttempt != attempt) {
                        DiagLog.i(TAG, "autoConnectSsh: 这次尝试已被取消/取代，丢弃失败回调")
                        return@onUi
                    }
                    connectFailed = true
                    // app.lastTunnelError：隧道自己的判定（密钥不可用 / dbclient 的 stderr）。
                    // 拿不到签名就退回原来的通用文案，绝不把英文机器文本直接上屏。
                    val hint = tunnelFailureHint(app.lastTunnelError)
                    status(hint ?: "自动连接失败，请在连接屏手动重试")
                    refreshConnectState()
                    endConnect()
                }
                return@Thread
            }
            // 自动获取最新 token（服务重启后旧 token 失效；失败静默回退）
            autoFetchToken(tunnel)
            onUi {
                if (connectAttempt != attempt) {
                    DiagLog.i(TAG, "autoConnectSsh: 这次尝试已被取消/取代，不切屏")
                    return@onUi
                }
                connectFailed = false
                // lastUrl 与 prefs["url"] 由 connectWeb 自己写，这里不必再来一遍。
                showScreen(Screen.WEB)
                sshTokenAck = false
                connectWeb(base)
                showPhase(ConnectPhase.IDLE)
                refreshConnectState()
                endConnect()
            }
        }.apply { name = "ssh-autoconnect"; isDaemon = true }.start()
    }

    // ── 连接屏 UI ─────────────────────────────────────────────
    /**
     * 连接屏（状态优先版，0.1.10 推倒重来）。
     *
     * 三块，自上而下：
     *  1. **状态块** —— 一个色点 + 一句话 + 一行目标。回答「现在是什么情况」；
     *     连接中 / 刚失败时，下面展开 ①②③ 进度行。
     *  2. **连接设置卡** —— 全部输入框收在里面，默认折叠成一行摘要。
     *  3. **贴底动作区** —— 网页/终端 + 一个主按钮 + 一条「断开连接」文字链。
     *
     * 为什么推倒：旧版是「选模式 → 填表 → 连接中」三步向导，三张卡片互斥显示；而
     * 「回到网页 / 断开连接 / 状态条」是三张卡片之外的常驻成员，于是**连接中同时看到
     * 三个同权重的整宽按钮**（返回修改 / 回到网页 / 断开连接），主操作要靠读文案才分得清 ——
     * 而且那一下「回到网页」指向的是上一轮的页面（隧道正在重建，页面此时是死的）。
     * 更根本的是优先级反了：这一屏每天的实际用法是「看一眼连上没有 / 点一下进网页」，
     * 9 个输入框却摊在最前面。现在按使用频率排：状态与主按钮常在，配置收进折叠卡。
     *
     * 动作区挂在**根布局底部**（而不是随卡片流走）：拇指够得着；软键盘弹起时
     * （manifest 里 `adjustResize`）它被顶到键盘上方，不会盖住正在编辑的输入框。
     */
    private fun createConnectView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        connectScroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        connectContent = content
        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ── 控件工厂 ───────────────────────────────────────────
        fun label(text: String, field: EditText? = null): TextView =
            UiKit.text(this@MainActivity, text, 11f, COL_DIM, letterSpacing = 0.12f).apply {
                // labelFor 把「电脑地址」这个标签接到下面的输入框上。旧版只有 hint：
                // 一旦填上内容 hint 就消失，读屏与视力不佳的用户只剩一个「编辑框」。
                if (field != null) labelFor = field.id
            }

        fun hint(text: String): TextView = UiKit.text(this@MainActivity, text, 11f, COL_MUTED)

        /** 就地错误行：默认 GONE（不占位），校验失败时贴在对应输入框下面。 */
        fun errorLine(): TextView =
            UiKit.text(this@MainActivity, "", 11f, COL_ERROR).apply { visibility = View.GONE }

        fun input(hint: String, prefill: String = "", pwd: Boolean = false, number: Boolean = false): EditText =
            EditText(this@MainActivity).apply {
                id = View.generateViewId()
                this.hint = hint
                textSize = 15f
                setTextColor(COL_TEXT)
                setHintTextColor(COL_HINT)
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(14), 0, dp(14), 0)
                gravity = Gravity.CENTER_VERTICAL
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
                textSize = 13f
                isChecked = initial
                buttonDrawable = null
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                setSingleLine(true)
                setMinWidth(0)
                setMinimumWidth(0)
                setMinEms(0)
                setMaxEms(6)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setBackgroundResource(R.drawable.bg_segment)
                // 字色跟选中态走的是 selector（见 res/color/segment_text.xml）：
                // RadioGroup 添加子控件时会覆盖子控件自己的 OnCheckedChangeListener，
                // 靠回调改字色在组里是失效的 —— 选中那一段会变成白字白底。
                setTextColor(
                    this@MainActivity.resources.getColorStateList(
                        R.color.segment_text, this@MainActivity.theme
                    )
                )
            }

        /** 密码行：输入框 + 显示/隐藏切换（避免密码框永远黑点）。 */
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
                addView(field, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
                addView(showBtn, LinearLayout.LayoutParams(dp(64), dp(46)))
            }
        }

        // ── 1 品牌行 + 状态块 ──────────────────────────────────
        val brandRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brandRow.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        })
        brandRow.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiKit.text(this@MainActivity, "DSH Handheld", 17f, COL_TITLE).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            addView(UiKit.text(this@MainActivity, "DeepSeek Harness · 手机端", 10f, COL_MUTED),
                rowParams(top = dp(2)))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        // 诊断入口留在常显的品牌行：隧道坏掉时这一屏是唯一还能操作的地方。
        // 触摸高度给到 48dp —— 旧版是 12sp 文字 + 6dp 内边距（≈30dp），偏小。
        brandRow.addView(UiKit.text(this@MainActivity, "诊断", 12f, COL_DIM).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener { showDiagPage() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        content.addView(brandRow, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))

        val dot = View(this@MainActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COL_DIM)
            }
        }
        heroDot = dot
        val title = UiKit.text(this@MainActivity, "未连接", 22f, COL_TEXT, bold = true)
        heroTitle = title
        content.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(10)
            })
            addView(title)
        }, rowParams(top = dp(26), width = ViewGroup.LayoutParams.MATCH_PARENT))

        val sub = UiKit.text(this@MainActivity, "", 12f, COL_MUTED)
        heroSub = sub
        content.addView(sub, rowParams(top = dp(9), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ①②③ 进度行：连接中与「刚失败还没重试」时显示（失败原因要留在屏幕上）
        fun guideRow(text: String): TextView =
            UiKit.text(this@MainActivity, text, 13f, COL_MUTED).apply { setIncludeFontPadding(false) }
        val line1 = guideRow("① 检查电脑 等待连接…")
        val line2 = guideRow("② 建立安全通道")
        val line3 = guideRow("③ 打开 dsh 网页")
        stepGuideLine1 = line1
        stepGuideLine2 = line2
        stepGuideLine3 = line3
        val progress = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(line1, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(line2, rowParams(top = dp(9), width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(line3, rowParams(top = dp(9), width = ViewGroup.LayoutParams.MATCH_PARENT))
        }
        progressBlock = progress
        content.addView(progress, rowParams(top = dp(18), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 2 连接设置卡（默认折叠）────────────────────────────
        // 预填用：这里**不做完整性校验**（地址填了、账号还没填也要把地址带出来）
        val savedSsh = SshConfig.load(prefs)

        val theForm = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        formBody = theForm

        // 电脑地址 + 端口：各自带可见标签。端口预填 22 之后它的 hint 不再显示，
        // 「靠 hint 说明字段含义」在预填场景下是失效的。
        val sshHostInput = input("192.168.0.1", savedSsh?.host ?: "")
        val sshPortInput = input("22", savedSsh?.port?.toString() ?: "22", number = true)
        theForm.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("电脑地址", sshHostInput))
                addView(sshHostInput, rowParams(top = dp(7), height = dp(46),
                    width = ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f).apply {
                marginEnd = dp(8)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("端口", sshPortInput))
                addView(sshPortInput, rowParams(top = dp(7), height = dp(46),
                    width = ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val errHost = errorLine()
        theForm.addView(errHost, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        theForm.addView(hint("你电脑的地址；端口一般用 22。"),
            rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        val sshUserInput = input("用户名", savedSsh?.user ?: "")
        theForm.addView(label("登录账号", sshUserInput),
            rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        theForm.addView(sshUserInput, rowParams(top = dp(7), height = dp(46),
            width = ViewGroup.LayoutParams.MATCH_PARENT))
        val errUser = errorLine()
        theForm.addView(errUser, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 登录方式（主区常显）：密码 / 私钥 —— 选哪个就显示哪一套字段
        theForm.addView(label("登录方式"), rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val authPassBtn = segment("密码", true)
        val authKeyBtn = segment("私钥", false)
        val authGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(authPassBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
            addView(authKeyBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        }
        theForm.addView(authGroup, rowParams(top = dp(7), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 密码分支：标题 + 输入框打包，随登录方式整体显隐
        //（此前标题无条件显示、输入框单独 GONE，选私钥后主区会剩一个空标题）
        val sshPassInput = input("密码", savedSsh?.password ?: "", pwd = true)
        val passRow = pwdRow(sshPassInput)
        val errPass = errorLine()
        val passBlock = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("电脑登录密码", sshPassInput), rowParams(top = dp(12),
                width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(passRow, rowParams(top = dp(7), width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(errPass, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        }
        theForm.addView(passBlock, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 私钥分支
        val keyPathInput = input("点「导入」选文件，或直接填路径", savedSsh?.keyPath ?: "")
        keyPathInput.isFocusable = true
        sshKeyPathInput = keyPathInput
        val browseKeyBtn = UiKit.button(this@MainActivity, "导入", UiKit.Style.SECONDARY, textSize = 12f) {
            pickSshKey()
        }
        val errKey = errorLine()
        val keyBlock = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("私钥路径", keyPathInput), rowParams(top = dp(12),
                width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(keyPathInput, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
                addView(browseKeyBtn, LinearLayout.LayoutParams(dp(64), dp(46)))
            }, rowParams(top = dp(7), width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(errKey, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
            // 「私钥口令」输入框已移除：dbclient **结构上**解不开带口令的密钥
            // （keyimport.c 里 openssh_read 的 passphrase 是 UNUSED，见 SshKeyImport）。
            // 收了口令却用不上，等于把用户往「密码错了」的方向引。改成把限制写在明处。
            addView(
                hint("OpenSSH 格式的私钥会在导入时自动转换；带口令的私钥不支持 —— "
                    + "请先在电脑上执行 ssh-keygen -p -f <私钥> -N \"\" 去掉口令。"),
                rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        theForm.addView(keyBlock, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))

        fun syncAuthFields() {
            val key = authKeyBtn.isChecked
            passBlock.visibility = if (key) View.GONE else View.VISIBLE
            keyBlock.visibility = if (key) View.VISIBLE else View.GONE
        }
        // 预选：保存的是私钥方式就切到私钥（此时监听器还没挂，不会触发回调）
        if ((savedSsh?.authType ?: SshConfig.AUTH_PASSWORD) == SshConfig.AUTH_KEY) {
            authKeyBtn.isChecked = true
        }
        syncAuthFields()

        // dsh 端口：dsh 网页在你电脑上的端口
        val sshTargetPortInput = input(
            "3080",
            (savedSsh?.remotePort ?: DEFAULT_PORT.toInt()).toString(),
            number = true
        )
        theForm.addView(label("dsh 端口", sshTargetPortInput),
            rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        theForm.addView(sshTargetPortInput, rowParams(top = dp(7), height = dp(46),
            width = ViewGroup.LayoutParams.MATCH_PARENT))
        theForm.addView(hint("dsh 网页的端口，默认 3080。"),
            rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 主机密钥变更后的**唯一出口**（见 resetKnownHosts）。放在表单末尾、用弱化的
        // 文字链：它不是日常操作，但出事时必须在手机上够得着 —— 此前只能清应用数据。
        theForm.addView(
            UiKit.text(this@MainActivity, "重置已信任的电脑身份", 12f, COL_ACCENT).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(0, dp(16), 0, dp(2))
                setOnClickListener { resetKnownHosts() }
            },
            rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val summary = UiKit.text(this@MainActivity, "", 13f, COL_TEXT)
        settingsSummary = summary
        // gravity=CENTER_VERTICAL 是**必须**的：这个 TextView 高 48dp（为触摸目标），
        // 而 TextView 默认把文字画在顶部 —— 不设它，「收起 / 修改 ›」会比左边的
        // 标题高出约 16dp。真机截图上先看到的就是这个错位（第一版只改了左列，不够）。
        val settingsToggle = UiKit.text(this@MainActivity, "修改 ›", 12f, COL_MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        settingsAction = settingsToggle

        /** 折叠时那一行摘要：直接读输入框的当前值，不做第二份真相。 */
        fun updateSummary() {
            val host = sshHostInput.text.toString().trim()
            val user = sshUserInput.text.toString().trim()
            val port = sshTargetPortInput.text.toString().trim().ifEmpty { DEFAULT_PORT }
            val auth = if (authKeyBtn.isChecked) "私钥" else "密码"
            summary.text = if (host.isBlank() && user.isBlank()) {
                "还没配置"
            } else {
                buildList<String> {
                    add((if (user.isBlank()) "" else "$user@") + host.ifBlank { "（地址未填）" })
                    add(auth)
                    // 端口只在**不是默认值**时露出来：默认 3080 写出来只是噪音，
                    // 而 README 承诺「连接屏不暴露端口这类术语」—— 收起状态下更该守住。
                    if (port != DEFAULT_PORT) add("dsh 端口 $port")
                }.joinToString(" · ")
            }
        }
        updateSummary()

        // 切登录方式：两套字段整体显隐 + 清掉与新模式矛盾的错误提示
        authGroup.setOnCheckedChangeListener { _, _ ->
            syncAuthFields()
            errPass.visibility = View.GONE
            errKey.visibility = View.GONE
            updateSummary()
        }

        // 输入即清错：不然改好了还挂着红字，用户会以为没生效
        fun clearOnEdit(field: EditText, err: TextView) {
            field.doAfterTextChanged {
                if (err.visibility == View.VISIBLE) err.visibility = View.GONE
                field.setBackgroundResource(R.drawable.bg_input)
            }
        }
        clearOnEdit(sshHostInput, errHost)
        clearOnEdit(sshUserInput, errUser)
        clearOnEdit(sshPassInput, errPass)
        clearOnEdit(keyPathInput, errKey)

        val settingsHeader = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            // 左列也给 48dp 并让内容居中：展开时左列只剩一行标签（11sp ≈ 15dp），
            // 不这么做它贴顶、右边的「收起」居中，两者会差出 15dp —— 真机截图里一眼可见。
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("连接设置"))
                addView(summary, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(settingsToggle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            setOnClickListener {
                val next =
                    if (connectPhase == ConnectPhase.EDIT) ConnectPhase.IDLE else ConnectPhase.EDIT
                showPhase(next)
                if (next == ConnectPhase.IDLE) updateSummary()
            }
        }
        content.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(settingsHeader, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
            addView(theForm, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        }, rowParams(top = dp(24), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 2b 通知卡 ──────────────────────────────────────────
        // 任务完成提醒的开关。放在连接屏而不是藏进诊断页：这是用户唯一会主动进设置的屏幕，
        // 而「要不要被打扰」是用户自己的决定 —— 默认关闭，打开了才去要权限。
        val notifSwitch = android.widget.Switch(this@MainActivity).apply {
            isChecked = prefs.getBoolean(DshApp.PREF_NOTIF_TURN, false)
        }
        notifSwitchView = notifSwitch
        val notifHint = UiKit.text(this@MainActivity, "", 11f, COL_MUTED)

        fun refreshNotifHint() {
            val allowed = Notifier.allowed(this@MainActivity)
            notifHint.text = when {
                !notifSwitch.isChecked -> "关闭：生成结束、以及在等你选择时，都不提醒。"
                !allowed -> "没有通知权限，提醒不会生效（点开关重新申请，或到系统设置里开启）。"
                else -> "生成结束、或停下来等你批准／回答时提醒 —— 只在 App 不在前台时才发。"
            }
            notifHint.setTextColor(if (notifSwitch.isChecked && !allowed) COL_ERROR else COL_MUTED)
        }
        refreshNotifHintView = { refreshNotifHint() }

        /** 打开：权限齐了就落盘，缺权限则先去申请（结果在 onRequestPermissionsResult 处理）。 */
        fun enableTurnNotif() {
            prefs.edit().putBoolean(DshApp.PREF_NOTIF_TURN, true).apply()
            Notifier.ensureChannels(this@MainActivity, Notifier.CHANNEL_TURN)
            Notifier.ensureChannels(this@MainActivity, Notifier.CHANNEL_ASK)
            status("已开启提醒")
            refreshNotifHint()
            DiagLog.i(TAG, "通知开关：开（系统允许=${Notifier.allowed(this@MainActivity)}）")
        }

        // 监听器要留一份引用：权限被拒时得先摘掉它再拨回开关，否则拨回这一下又会触发「关闭」分支
        val notifListener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            if (checked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    DiagLog.i(TAG, "通知开关：先申请 POST_NOTIFICATIONS")
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF
                    )
                } else {
                    enableTurnNotif()
                }
            } else {
                prefs.edit().putBoolean(DshApp.PREF_NOTIF_TURN, false).apply()
                Notifier.cancelTurn(this@MainActivity)
                status("已关闭提醒")
                refreshNotifHint()
                DiagLog.i(TAG, "通知开关：关")
            }
        }
        notifSwitch.setOnCheckedChangeListener(notifListener)
        notifSwitchListener = notifListener
        refreshNotifHint()

        content.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(UiKit.text(this@MainActivity, "dsh 需要你时提醒我", 14f, COL_TEXT))
                    addView(notifHint, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(notifSwitch, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)
                ).apply { gravity = Gravity.CENTER_VERTICAL })
            }, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        }, rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 3 贴底动作区 ───────────────────────────────────────
        val actionZone = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_action_bar)
            setPadding(dp(20), dp(12), dp(20), dp(18))
        }

        // 状态条（全局提示；字段级错误走输入框下面那几行）
        val status = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            minHeight = dp(30)
            setBackgroundResource(R.drawable.bg_status_pill)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            visibility = View.GONE
        }
        statusView = status
        actionZone.addView(status, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 看网页 / 开终端：不再是独立一步，而是决定主按钮做什么
        val webModeBtn = segment("看 dsh 网页", true)
        val termModeBtn = segment("打开终端", false)
        val modeGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(webModeBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) })
            addView(termModeBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        }
        actionZone.addView(modeGroup, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))
        modeGroup.setOnCheckedChangeListener { _, _ ->
            webMode = webModeBtn.isChecked
            syncConnectUi()
        }

        /** 校验失败：红框 + 红字贴在字段下面 + 把它滚进视野 + 聚焦（软键盘跟着弹）。 */
        fun fail(field: EditText, err: TextView, msg: String) {
            err.text = msg
            err.visibility = View.VISIBLE
            field.setBackgroundResource(R.drawable.bg_input_error)
            scrollFieldIntoView(field)
        }

        /**
         * 主按钮的动作 —— 四种情况全在这一处判定：
         * 取消连接 / 回网页（隧道与页面都在）/ 打开终端 / 连上并打开。
         */
        fun onPrimaryAction() {
            val app = application as DshApp
            val tunneled = app.sshTunnel != null
            val pageAlive = webView?.url?.startsWith("http") == true

            if (connectPhase == ConnectPhase.CONNECTING && !connectFailed) {
                cancelConnect()
                return
            }
            // 隧道与页面都在（且要的是网页）→ 直接回网页：不重连、不重载、不动 token，
            // 也就不该被表单校验拦下。
            if (webMode && tunneled && pageAlive) {
                DiagLog.i(TAG, "连接屏：回网页（隧道活着、页面在）")
                showScreen(Screen.WEB)
                return
            }

            // 重试前把上一轮的错误痕迹全抹掉（红字 + 红框），否则改好了还留着红框
            listOf(
                errHost to sshHostInput, errUser to sshUserInput,
                errPass to sshPassInput, errKey to keyPathInput
            ).forEach { (err, field) ->
                err.visibility = View.GONE
                field.setBackgroundResource(R.drawable.bg_input)
            }
            status("")

            val sh = sshHostInput.text.toString().trim()
            val su = sshUserInput.text.toString().trim()
            val sport = sshPortInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22
            val target = sshTargetPortInput.text.toString().trim().ifEmpty { DEFAULT_PORT }
                .toIntOrNull()?.coerceIn(1, 65535) ?: 3080
            if (sh.isBlank()) { fail(sshHostInput, errHost, "请填写电脑地址"); return }
            if (su.isBlank()) { fail(sshUserInput, errUser, "请填写登录账号"); return }
            val auth = if (authKeyBtn.isChecked) {
                val path = keyPathInput.text.toString().trim()
                if (path.isBlank()) {
                    fail(keyPathInput, errKey, "请填写私钥路径，或点「导入」选文件"); return
                }
                val keyFile = File(path)
                if (!keyFile.exists()) { fail(keyPathInput, errKey, "私钥文件不存在：$path"); return }
                // 不带口令：dbclient 用不了它（见 SshKeyImport），真正的转换在
                // DshApp.resolveAuth / TuiActivity.resolveKeyPath 里做。
                SshTunnel.Auth.KeyPair(keyFile)
            } else {
                val pw = sshPassInput.text.toString()
                if (pw.isEmpty()) { fail(sshPassInput, errPass, "请填写电脑登录密码"); return }
                SshTunnel.Auth.Password(pw)
            }
            updateSummary()
            if (!webMode) {
                persistSshConfig(sh, sport, su, target, auth)
                DiagLog.i(TAG, "连接屏：打开终端（终端自己建连接，不经这里的隧道）")
                startActivity(Intent(this@MainActivity, TuiActivity::class.java))
                return
            }
            if (!beginConnect()) { status("正在连接中，请稍候…"); return }
            connectAttempt++
            connectFailed = false
            guideLine(1, "① 检查电脑 正在连接…", state = true)
            connectViaSsh(sh, sport, su, target, auth, connectAttempt)
        }

        val main = UiKit.button(this@MainActivity, "", UiKit.Style.PRIMARY, textSize = 15f) {
            onPrimaryAction()
        }
        connectMainBtn = main
        actionZone.addView(main, rowParams(top = dp(10), height = dp(54),
            width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 断开连接：只有隧道活着才出现，且退化成一条文字链 ——
        // 它不该跟主按钮抢同一个位置（旧版三个整宽按钮并排，主操作要靠读文案分辨）。
        val link = UiKit.text(this@MainActivity, "断开连接", 13f, COL_ERROR).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, dp(16), 0, dp(8))
            setOnClickListener { disconnectCurrent() }
        }
        disconnectLink = link
        actionZone.addView(link, rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(actionZone, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 键盘弹起时把动作区顶上去。
        //
        // 不能只靠 manifest 的 `adjustResize`：本 Activity 关了 `decorFitsSystemWindows`
        // （沉浸全屏的前提），「系统自动把窗口缩到键盘之上」就不再是框架的职责，各版本
        // 行为不一致。这里直接消费 IME inset —— 窗口已经缩过时这个值是 0，不会重复位移，
        // 所以两种行为下都对。
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            v.setPadding(
                0, 0, 0,
                insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            )
            insets
        }

        // ── 初始相位 ───────────────────────────────────────────
        // 配置齐全 → 折叠（这一屏每天只是「看一眼 + 点一下」）；缺东西 → 摊开，
        // 别让用户对着一个「修改 ›」猜里面缺什么。
        connectPhase = if (savedConfigUsable()) ConnectPhase.IDLE else ConnectPhase.EDIT
        syncConnectUi()
        return root
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

    /**
     * 通知权限的结果。
     *
     * 被拒时要把开关**拨回去**：开关停在「开」而权限没给，用户下次会以为提醒坏了。
     * 拨回去之前先摘掉监听器 —— 否则这一次 `isChecked = false` 又会触发一遍「关闭」分支。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        DiagLog.i(TAG, "POST_NOTIFICATIONS 结果：granted=$granted")
        val sw = notifSwitchView ?: return
        if (granted) {
            prefs.edit().putBoolean(DshApp.PREF_NOTIF_TURN, true).apply()
            Notifier.ensureChannels(this, Notifier.CHANNEL_TURN)
            status("已开启任务完成提醒")
        } else {
            prefs.edit().putBoolean(DshApp.PREF_NOTIF_TURN, false).apply()
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = false
            sw.setOnCheckedChangeListener(notifSwitchListener)
            status("没有通知权限，提醒未开启", err = true)
        }
        refreshNotifHintView?.invoke()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // WebView 的文件选择：**无论取消还是成功都要回话**（null 也算），否则页面那个
        // input 会一直挂着，用户再点「添加附件」就没反应了。
        if (requestCode == REQ_WEB_FILE) {
            val cb = webFileCallback
            webFileCallback = null
            val uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            DiagLog.i(TAG, "onShowFileChooser 返回：${uris?.size ?: 0} 个文件（resultCode=$resultCode）")
            cb?.onReceiveValue(uris)
            return
        }
        if (requestCode != REQ_PICK_KEY || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        // 拷贝 + 转换都在这里做，且**整段在后台线程**：转换要 fork 一个子进程
        // （dropbearconvert，最长 10s）。按本项目的既有教训（审计 A1/A2），
        // 主线程上的阻塞调用必然 ANR。
        status("正在导入私钥…")
        Thread {
            val dest = File(filesDir, SshKeyImport.IMPORTED_FILE_NAME)
            val outcome: SshKeyImport.Outcome? = try {
                val copied = try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    dest.exists() && dest.length() > 0L
                } catch (e: Exception) {
                    DiagLog.w(TAG, "读取私钥失败：${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                if (!copied) {
                    null
                } else {
                    // dbclient 的 -i 只认 dropbear 格式；OpenSSH 密钥必须在这里转。
                    // 就地转换的另一个好处：错在哪（带口令 / 不是私钥）**在导入这一步**
                    // 就能说清楚，而不是拖到连接时变成一句「检查私钥」。
                    SshKeyImport.ensureUsable(
                        dest, filesDir, SshKeyImport.converterPath(applicationInfo.nativeLibraryDir)
                    )
                }
            } catch (e: Exception) {
                DiagLog.w(TAG, "导入私钥失败：${e.javaClass.simpleName}: ${e.message}")
                null
            }
            onUi {
                when (outcome) {
                    null -> status("读取私钥失败", err = true)
                    is SshKeyImport.Outcome.Ready -> {
                        // 存**转换后**的路径（已经是 dropbear 格式，dbclient 直接可用）
                        sshKeyPathInput?.setText(outcome.file.absolutePath)
                        status(if (outcome.converted) "私钥已导入并转换为可用格式" else "私钥已导入")
                    }
                    is SshKeyImport.Outcome.NeedsPassphraseRemoval ->
                        status("这把私钥带口令，手机端的 SSH 组件无法解密：请先在电脑上执行 "
                            + "ssh-keygen -p -f <私钥> -N \"\" 去掉口令，再重新导入", err = true)
                    is SshKeyImport.Outcome.Failed ->
                        status("私钥不可用：${outcome.reason}", err = true)
                }
            }
        }.apply { name = "ssh-key-import"; isDaemon = true }.start()
    }

    private fun status(msg: String, err: Boolean = false) {
        statusView?.text = msg
        statusView?.setTextColor(if (err) COL_ERROR else COL_MUTED)
        statusView?.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    // ── WebView 内的文件选择 ─────────────────────────────────
    /**
     * 页面里那个 `<input type=file>` 的回调。
     *
     * **不实现 onShowFileChooser 的后果是「按钮点了没反应」**：dsh 输入区的「添加附件」
     * 与回形针都是 `fileInputRef.current.click()`，WebView 只能靠 WebChromeClient 把这个
     * 请求交出来；默认实现返回 false，于是既不报错也不弹选择器（2026-09-14 真机复现：
     * 点 + 只把输入框聚焦、弹出软键盘）。
     */
    private var webFileCallback: ValueCallback<Array<Uri>>? = null

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
    /**
     * 手动连接（强制重建隧道）。
     *
     * @param attempt 调用方（[MainActivity.onPrimaryAction] 里那个局部函数）自增后的尝试计数。
     *   两个 `onUi` 回调都拿它与 [connectAttempt] 比对：用户中途按了「取消连接」时，
     *   这次拨号的结果必须作废 —— 否则回程会把「已取消连接」覆盖成「隧道建立失败」，
     *   极端时序下还会把界面切回网页。
     */
    private fun connectViaSsh(
        sshHost: String,
        sshPort: Int,
        sshUser: String,
        remotePort: Int,
        auth: SshTunnel.Auth,
        attempt: Int,
    ) {
        // 状态条让位给状态块与 ①②③：这一屏上「正在连接」由状态块那句 + 主按钮的
        // 「取消连接」表达，状态条只负责报错与一次性提示，这里先把上一轮的残留清掉。
        status("")
        guideStep3Show()
        val app = application as DshApp
        val cfg = buildSshConfig(sshHost, sshPort, sshUser, remotePort, auth)
        DiagLog.i(TAG, "connectViaSsh: 手动连接（强制重建）$sshUser@$sshHost:$sshPort → 远端 $remotePort " +
            "auth=${if (auth is SshTunnel.Auth.KeyPair) "key" else "password"} attempt=$attempt")
        Thread {
            // 用户明确点了连接 → 强制重建（可能正是一条他自己觉得有问题的隧道）
            val tunnel = app.ensureTunnel(cfg, force = true)
            val base = tunnel?.localBaseUrl
            DiagLog.i(TAG, "connectViaSsh: ensureTunnel(force=true) → base=$base attempt=$attempt")
            // 失败先退：隧道没起来就不再干跑 token 探测（4×8s 白等）
            if (tunnel == null || base == null) {
                DiagLog.w(TAG, "connectViaSsh: 隧道建立失败（attempt=$attempt）")
                onUi {
                    if (connectAttempt != attempt) {
                        DiagLog.i(TAG, "connectViaSsh: 这次尝试已被取消/取代（attempt=$attempt " +
                            "当前=$connectAttempt），丢弃失败回调")
                        return@onUi
                    }
                    connectFailed = true
                    // 提示词跟登录方式：私钥用户看到「检查密码」会懵
                    val what = if (auth is SshTunnel.Auth.KeyPair) "私钥" else "密码"
                    val hint = tunnelFailureHint(app.lastTunnelError)
                    status(hint ?: "连不上你的电脑")
                    guideLine(1, "① 检查电脑 ✗ "
                        + (hint ?: "连不上你的电脑（检查地址/账号/$what）"), state = null)
                    guideLine(2, "② 建立安全通道 未开始", state = true)
                    // ensureTunnel 已把旧隧道关掉，这里必须刷新，否则
                    // 「断开连接」会留在屏幕上指向一个已死的隧道
                    refreshConnectState()
                    endConnect()
                }
                return@Thread
            }
            onUi {
                if (connectAttempt != attempt) return@onUi
                guideLine(1, "① 检查电脑 ✓ 已连上电脑", state = false)
            }
            // 自动获取最新 token（服务重启后旧 token 失效；失败静默回退）
            val token = autoFetchToken(tunnel)
            onUi {
                if (connectAttempt != attempt) {
                    DiagLog.i(TAG, "connectViaSsh: 这次尝试已被取消/取代（attempt=$attempt " +
                        "当前=$connectAttempt），不切屏、不重载")
                    return@onUi
                }
                DiagLog.i(TAG, "connectViaSsh: token=${if (token != null) "已获取" else "未获取（仍尝试打开）"}")
                if (token != null) guideLine(2, "② 建立安全通道 ✓ 已连通", state = false)
                else guideLine(2, "② 建立安全通道 ⚠ 未获取令牌，仍尝试打开", state = null)
                persistSshConfig(sshHost, sshPort, sshUser, remotePort, auth)
                connectFailed = false
                // 同 origin 恢复：隧道端口没漂、WebView 上那一页还停在同一个 origin 时，
                // **不重载**。页面自己的重连（SSE/fetch 重试）会接到新隧道上；cookie 在同一
                // authority 下仍然有效；万一服务端重启过导致 401，onReceivedHttpError 那条
                // 既有路径会带着新 token 重新走一次 connectWeb（见 handleUnauthorized）。
                // 断掉的那条流由下面的 offline→online 提示唤起重连，不再需要手动刷新。
                val loaded = webView?.url
                val sameOrigin = loaded != null && loaded.startsWith("http") &&
                    runCatching { android.net.Uri.parse(loaded).let { "${it.scheme}://${it.authority}" } }
                        .getOrNull() == base
                if (sameOrigin) {
                    DiagLog.i(TAG, "connectViaSsh: 同 origin（$base）且页面还在 → 不重载（省流量与视图状态）")
                    guideLine(3, "③ 打开 dsh 网页 ✓ 已恢复原页面", state = false)
                    // 与 connectWeb 对齐的两处簿记：prefs[url] 供冷启动自动重连，lastUrl 供
                    // 401 恢复路径（handleUnauthorized）重新走一次带 token 的加载。
                    lastUrl = base
                    prefs.edit().putString("url", base).apply()
                    showScreen(Screen.WEB)
                    // 推一下页面自己的重连。dsh 的连接层监听 online/offline 并据此调
                    // controller.setNetworkAvailable()（见 dsh-client-connection）——但隧道
                    // 掉线期间浏览器的 navigator.onLine 一直是 true，所以只发 online 是空操作；
                    // 必须先 offline 再 online 造出那次状态跃迁，重连逻辑才会跑。没有监听者时
                    // 这两个 dispatch 也无害。
                    webView?.evaluateJavascript(
                        "window.dispatchEvent(new Event('offline'));" +
                            "setTimeout(function(){window.dispatchEvent(new Event('online'));},0);",
                        null
                    )
                } else {
                    sshTokenAck = false
                    connectWeb(base)
                }
                // 连上了就离开「连接中」相位：留在里面会让状态块继续写着「连接中…」、
                // 主按钮写着「取消连接」，而用户下一次从网页按返回回到这一屏时会看到它们。
                showPhase(ConnectPhase.IDLE)
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

    /**
     * 把隧道的原始失败原因翻成一句用户能照着做的话。
     *
     * 两条约束同时成立，所以不能把 stderr 直接上屏：
     *  1. 连接屏刻意不出现术语（README「连接屏刻意去术语」），而 dbclient 的原因是英文机器文本；
     *  2. 但有些失败**必须**改变用户的下一步（主机身份变了、私钥带口令）——那种情况下
     *     「检查地址/账号/密码」是误导，用户会一直在错误的地方试。
     * 所以只认几个能改变动作的签名，其余返回 null，由调用方沿用原来的通用文案。
     * 原始值不会丢：它同时在诊断页与 DiagLog 里。
     */
    private fun tunnelFailureHint(raw: String?): String? {
        val r = raw?.lowercase() ?: return null
        return when {
            // dbclient: "ssh-ed25519 host key mismatch for <host> !"
            r.contains("host key mismatch") ->
                "这台电脑的 SSH 身份和上次不一样（重装过系统？）。到「连接设置」里" +
                    "「重置已信任的电脑身份」之后再连"
            // DshApp.resolveAuth 的判定（dbclient 解不开带口令的密钥）
            r.contains("带口令") ->
                "私钥带口令，手机端解不开：请先在电脑上执行 ssh-keygen -p -N \"\" 去掉口令再导入"
            r.contains("缺少 libdropbearconvert") ->
                "APK 里缺少私钥转换工具（构建不完整？）"
            r.contains("不像是一把 ssh 私钥") ->
                "选中的文件不是 SSH 私钥"
            r.contains("密钥文件不存在") ->
                "私钥文件不在了，请重新导入"
            // dbclient 读不了非 dropbear 格式时的原始输出（正常路径下已被转换挡掉）
            r.contains("string too long") || r.contains("failed loading keyfile") ->
                "私钥读不了（dbclient 只认 dropbear 格式）"
            r.contains("permission denied") || r.contains("no auth methods") ->
                "服务器拒绝了登录：账号或密码/私钥不对"
            r.contains("connection refused") ->
                "电脑上没有 SSH 服务在监听（端口填对了吗？）"
            r.contains("timed out") || r.contains("timeout") || r.contains("no route to host") ->
                "连不上这台电脑：地址或网络不通"
            else -> null
        }
    }

    /**
     * 清掉 TOFU 记录：`$HOME/.ssh/known_hosts`（HOME = `filesDir`，见 [SshTunnel.homeDir]）。
     *
     * 这是手机端**唯一的出路**。服务器重装、容器重建、或 DHCP 把同一个 IP 分给了另一台
     * 机器之后，`known_hosts` 里的旧指纹会让 dbclient 直接拒绝连接 —— `-y`（本项目的 TOFU）
     * 只放行**未知**主机，不匹配的仍然拒绝（dropbear `cli-kex.c`）。而这个文件在应用私有
     * 目录里，用户没有别的地方可以删，此前只能清应用数据（配置一并丢失）。
     */
    private fun resetKnownHosts() {
        val f = File(filesDir, ".ssh/known_hosts")
        val existed = f.exists()
        val ok = !existed || runCatching { f.delete() }.getOrDefault(false)
        DiagLog.i(TAG, "重置 known_hosts: path=${f.absolutePath} existed=$existed ok=$ok")
        status(
            if (ok) "已清除信任记录，下次连接会重新确认这台电脑"
            else "清除失败：${f.absolutePath}",
            err = !ok
        )
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
            append("屏幕       ").append(screen).append(" / ").append(connectPhase)
                .append(" failed=").append(connectFailed).append('\n')
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
     * 可见性计数（[DshApp.onActivityStarted]）—— 决定「页面报告任务完成时要不要弹通知」。
     *
     * 用 onStart/onStop 而不是 onResume/onPause：后者在**权限对话框、系统弹窗**盖上来时也会
     * 触发（那一刻用户其实还在这一屏上，只是被系统对话框挡住），会把「前台」误判成「后台」，
     * 于是自己刚点的开关立刻给自己发一条通知。
     */
    override fun onStart() {
        super.onStart()
        (application as? DshApp)?.onActivityStarted()
    }

    override fun onStop() {
        (application as? DshApp)?.onActivityStopped()
        super.onStop()
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
                // 探针要跑满 1.5–4.5s，这期间用户可能已经「断开连接」或换了隧道。
                // 迟到的结果不能算数：否则一个后台探针会把用户刚断开的连接又建回来
                // （断开被逆转，prefs["url"] 也会被重新写回）。
                if (app.sshTunnel !== tunnel) {
                    DiagLog.i(TAG, "revalidateTunnel: 现场已变（当前隧道 ${app.sshTunnel?.localBaseUrl}），丢弃这次探测结果")
                    return@onUi
                }
                if (beginConnect()) rebuildTunnel(cfg)
                else DiagLog.i(TAG, "revalidateTunnel: 已有连接在进行，让位")
            }
        }.apply { name = "tunnel-probe"; isDaemon = true }.start()
    }

    /** [revalidateTunnel] 的续作：探针判定隧道已死后重建（已在 UI 线程持好连接守卫）。 */
    private fun rebuildTunnel(cfg: SshConfig) {
        // 与 connectViaSsh 同一套代数守卫：重建要跑好几秒，这期间用户可能已经
        // 「回连接屏 → 断开连接」，那一下必须赢（否则迟到的回调会把页面又切回来）。
        val attempt = connectAttempt
        Thread {
            val app = application as DshApp
            // 必须在 ensureTunnel(force=true) **之前**取：它会 close() 旧隧道并把 localBaseUrl 置空
            val prevBase = app.sshTunnel?.localBaseUrl
            val t = app.ensureTunnel(cfg, force = true)
            val base = t?.localBaseUrl
            // 直接对 t 判空：后面的 autoFetchToken(t) 需要 t 的非空类型，靠 `base != null`
            // 去反推 t 非空依赖编译器的智能转换，写明确一点不吃亏。
            if (t == null || base == null) {
                DiagLog.w(TAG, "rebuildTunnel: 重建失败")
                onUi {
                    if (connectAttempt != attempt) {
                        DiagLog.i(TAG, "rebuildTunnel: 期间用户已断开/取消，丢弃失败回调")
                        return@onUi
                    }
                    status("重连失败，请回连接屏手动重试"); refreshConnectState(); endConnect()
                }
                return@Thread
            }
            // origin 变了 → cookie 名含 authority，必然失效 → 得重新取令牌。
            // ⚠️ 取令牌**必须在后台线程**：autoFetchToken 会跑最多 4 条 SSH 命令
            // （execOnce 每条默认超时 8s，内部是轮询子进程）。原先这一句写在下面的
            // onUi 块里 = 主线程冻结最长 32s（ANR），2026-09-14 审计抓到的就是它；
            // 同一函数的另外三个调用点本来都在后台线程，只有这里漏了。
            val sameOrigin = base == prevBase
            if (!sameOrigin) {
                DiagLog.i(TAG, "rebuildTunnel: origin 变了（$prevBase → $base），先在后台取令牌再回主线程")
                autoFetchToken(t)
            }
            onUi {
                if (connectAttempt != attempt) {
                    DiagLog.i(TAG, "rebuildTunnel: 期间用户已断开/取消，不切屏、不重载")
                    return@onUi
                }
                if (sameOrigin) {
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
        webView?.onPause()
        // 页面正在生成时**不暂停定时器**：pauseTimers 是全局的（"layout, parsing, and
        // JavaScript timers"），而「这一轮结束了」这个信号要靠页面里的 React 重新渲染出来
        // —— 定时器一停，渲染与观察者都可能推迟到用户回到 App 才跑，任务完成通知就永远不会响。
        // 代价是后台时多耗一点电，所以只在真有活干的时候让路（空闲即暂停）。
        val busy = (application as? DshApp)?.pageBusy == true
        if (busy) DiagLog.i(TAG, "onPause: 页面正在生成，保留定时器（任务完成通知依赖它）")
        else webView?.pauseTimers()
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
            else -> handleWebBack()
        }
    }

    /**
     * 网页里的 BACK 阶梯：**模态 → 抽屉 → 原语义**。
     *
     * 一、页面里有模态（`[role=dialog][aria-modal]`，例如设置页）→ 当 Esc 用。
     * 二、模态没有、但目录抽屉开着 → 收起抽屉。
     * 三、都没有 → 原来的语义（网页历史 → 连接屏 → 退到后台），一个字没改。
     *
     * 为什么要有前两级：手机上没有 Esc 键，铺满整屏的浮层与抽屉，用户唯一的"关掉"直觉
     * 就是返回手势。不接这两下的话，BACK 会把用户送到网页历史甚至连接屏，而设置页/抽屉
     * 还开在那里 —— 回来仍是同一屏，看起来就是"按了没反应"。
     *
     * 三处必须小心：
     *  1. `evaluateJavascript` 是异步的，所以只能先问页面、再在回调里决定下一级，
     *     不能像原来那样同步 `when` 一把梭；
     *  2. 页面"吃掉了"不等于"关掉了" —— 有的模态不监听 Escape。所以发完 Esc 等一下
     *     再复查一次，模态还在就照样往下走，绝不把 BACK 变成空操作；
     *  3. 抽屉那一级直接点页面里我们自己的遮罩（`[data-handheld="backdrop"]`），
     *     不去写宿主状态：开合的唯一真相在页面侧，App 只是替用户按了一下它的按钮。
     */
    private fun handleWebBack() {
        val wv = webView
        if (wv == null) {
            fallbackBack()
            return
        }
        wv.evaluateJavascript(JS_ESCAPE_MODAL) { escaped ->
            if (escaped != "true") {
                closeDrawerOrFallback()
                return@evaluateJavascript
            }
            ui.postDelayed({
                val live = alive.get()
                val current = webView
                if (!live || current == null) return@postDelayed
                current.evaluateJavascript(JS_MODAL_OPEN) { stillOpen ->
                    if (stillOpen == "true") {
                        DiagLog.i(TAG, "BACK: 模态没吃下 Esc，继续下一级")
                        closeDrawerOrFallback()
                    } else {
                        DiagLog.i(TAG, "BACK: 关掉网页模态（Esc）")
                    }
                }
            }, MODAL_ESCAPE_SETTLE_MS)
        }
    }

    /** 第二级：抽屉开着就收起（点页面里那个遮罩，开合状态仍由页面自己持有）。 */
    private fun closeDrawerOrFallback() {
        val wv = webView
        if (wv == null) {
            fallbackBack()
            return
        }
        wv.evaluateJavascript(JS_CLOSE_DRAWER) { closed ->
            if (closed == "true") {
                DiagLog.i(TAG, "BACK: 收起目录抽屉")
            } else {
                fallbackBack()
            }
        }
    }

    /** 第三级（原来的 BACK 语义）：网页历史 → 连接屏 → 退到后台。 */
    private fun fallbackBack() {
        when {
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

    // ── 引导流进度行（类级：connectViaSsh/autoConnectSsh/onPageFinished 共用）────
    /** 进入「连接中」：相位 + ① 行文案一起复位。 */
    private fun guideStep3Show() {
        showPhase(ConnectPhase.CONNECTING, resetGuide = true)
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
    /**
     * 把控件滚进视野。
     *
     * 用在「校验失败 → 指出是哪个输入框」：错误行贴在字段下面，但字段可能在折叠区里、
     * 也可能在屏幕外，不滚过去等于只报了个看不见的错。
     */
    private fun scrollFieldIntoView(target: View) {
        val sc = connectScroll ?: return
        val content = connectContent ?: return
        var y = 0
        var cur: View? = target
        while (cur != null && cur !== content) {
            y += cur.top
            cur = cur.parent as? View
        }
        val to = (y - dp(90)).coerceAtLeast(0)
        sc.post {
            sc.smoothScrollTo(0, to)
            target.requestFocus()
        }
    }

    /** 关闭并释放当前 SSH 隧道（无则 no-op）。所有权在 DshApp，这里只是转发。 */
    private fun closeCurrentTunnel() {
        (application as DshApp).closeTunnel()
    }

    /**
     * 保存的配置够不够「直接连」：地址、账号在，且认证材料齐。
     *
     * 用它决定连接屏初始是折叠还是摊开 —— 判据是**配置本身**，不是「上次展开过吗」：
     * 少一个要持久化的状态，也少一类「展开了却是空的」故障。
     */
    private fun savedConfigUsable(): Boolean {
        val c = SshConfig.load(prefs) ?: return false
        if (!c.isComplete) return false
        return if (c.usesKey) c.keyPath.isNotBlank() else c.password.isNotEmpty()
    }

    /**
     * 取消进行中的连接。
     *
     * 与 [disconnectCurrent] 的差别只有一处：**不动 prefs["url"]** —— 用户取消的是一次尝试，
     * 不是要丢掉「上次打开的是哪个页面」。两者都要 `connectAttempt++`：那条仍在飞的拨号
     * 会以「隧道建立失败」回来，不作废它就会把「已取消连接」覆盖成一条吓人的报错。
     */
    private fun cancelConnect() {
        DiagLog.i(TAG, "cancelConnect: 用户取消连接（作废在飞的拨号 attempt=$connectAttempt）")
        connectAttempt++
        connectFailed = false
        closeCurrentTunnel()
        sshTokenAck = false
        endConnect()
        showPhase(ConnectPhase.IDLE)
        status("已取消连接")
    }

    /** 断开连接：停隧道、清回连 URL、回连接屏。 */
    private fun disconnectCurrent() {
        // **刻意不把页面清成 about:blank**：页面留着，重新连接时若隧道仍落在同一个 origin
        // （端口没漂）就能直接恢复，省掉一次整页重载（实测 ≈4.7 MB + SPA 重新拉会话历史）。
        // 页面在没有隧道时是死的（请求全失败），连接屏盖在上面，没有可交互面。
        // 「断开」不再等于「丢弃页面」——这一点与后台掉线后回前台的处理（0.1.9 同 origin
        // 重建不重载）保持一致。
        DiagLog.i(TAG, "disconnectCurrent: 关隧道 + 删 prefs[url]（保留页面，重连同 origin 可复用），" +
            "webUrl=${webView?.url}")
        connectAttempt++
        connectFailed = false
        closeCurrentTunnel()
        prefs.edit().remove("url").apply()
        sshTokenAck = false
        unauthorizedCleanTried = false
        webView?.stopLoading()
        showConnectScreen()
        status("已断开")
    }

    /**
     * 按隧道/页面状态刷新连接屏。
     *
     * 自身**不碰控件**：全部交给 [syncConnectUi] 一处重画。此前主按钮文案、两个按钮的可见性、
     * 状态条文案由 4 处各自设置，于是出现「连接中却显示回到网页」——那一下会切到上一轮的页面，
     * 而隧道正在重建。
     */
    private fun refreshConnectState() {
        syncConnectUi()
        val tunneled = (application as DshApp).sshTunnel != null
        // 按钮可见性此前不可观测：连接失败后按钮残留（指向死隧道）就是这类问题，只能靠截图发现。
        DiagLog.i(TAG, "refreshConnectState: phase=$connectPhase failed=$connectFailed tunneled=$tunneled " +
            "webUrl=${webView?.url} primary=${connectMainBtn?.text} " +
            "disconnect=${disconnectLink?.visibility == View.VISIBLE}")
        // 隧道活着时状态块已经写着「已连上电脑」，状态条再写一遍就是同一句话说两次；
        // 这里只清掉残留的进度文案（如「连接中… http://…」既过时又是术语）。
        // 失败提示不能清：那时 tunneled=false。
        if (tunneled && !connectFailed) status("")
    }

    /**
     * 回到连接屏统一收口：回常态 + 复位进度行。
     *
     * 相位按**配置是否齐全**决定（缺东西就摊开表单），不再无条件停在「填表」那一步 ——
     * 旧版无论配置齐不齐都停 Step 2，于是「按返回键回到连接屏」看到的是一张 9 行的表。
     */
    private fun showConnectScreen() {
        connectFailed = false
        DiagLog.i(TAG, "showConnectScreen: 回连接屏（tunnel=${(application as DshApp).sshTunnel != null}）")
        showPhase(
            if (savedConfigUsable()) ConnectPhase.IDLE else ConnectPhase.EDIT,
            resetGuide = true
        )
        showScreen(Screen.CONNECT)
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

    /** 纵向排列最常用的 LayoutParams；定义在 UiKit（此前本文件里有两份逐字相同的副本）。 */
    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        UiKit.rowParams(top, width, height)

    private fun dp(n: Int) = UiKit.dp(this, n)
}
