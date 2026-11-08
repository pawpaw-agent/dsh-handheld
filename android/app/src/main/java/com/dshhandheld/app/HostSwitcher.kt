package com.dshhandheld.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshhandheld.diag.DiagLog
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

private const val TAG = "DshHandheld"

/** 探针超时：局域网正常低于 100ms，900ms 只用来分辨「这台机器在不在」。 */
private const val PROBE_TIMEOUT_MS = 900

/** 切换流程总兜底：最坏拨号 3×15s + 隧道回收，超过这个时间就别再挂着进度层。 */
private const val SWITCH_TIMEOUT_MS = 46_000L

/**
 * 隧道通了但页面迟迟没有 onPageFinished（同 origin 切换本来就不重载）——
 * 到点就把进度层收掉，别让用户以为卡住了。
 */
private const val PAGE_READY_FALLBACK_MS = 1_500L

/** A3 进度层的三行文案，编号与连接屏既有 ①②③ 引导一致。 */
private val STEP_LABELS = listOf("① 断开当前连接", "② 重新拨号建立通道", "③ 重新加载页面")

/**
 * 多服务器快速切换的 UI 层（设计稿 A1/A2/A3/A5）。
 *
 * 为什么单独一个文件：MainActivity 已经 3300 行，而这一层是自成一体的纯 UI ——
 * 一条贴在网页顶部的轻量入口、一个自绘的底部弹层、一层切换进度、以及连接失败时的
 * 「换一台」。MainActivity 只留入口与回调（见 attach 的五个参数），便于并行开发时合并。
 *
 * 与数据层的分工（沿用 SshHosts 注释里那条）：切换主机 = 把选中那条经
 * SshHosts.setActive 写进 ssh_json，再走既有重连流程。本文件不碰隧道、不碰 WebView、
 * 不注入 JS，只负责把「选哪台」与「切得怎么样」表达清楚。
 *
 * 配色与控件工厂走 [UiKit]（全 App 黑白色调，只有状态点例外 —— 与连接屏状态块同一个约定）。
 */
object HostSwitcher {

    /**
     * 挂到 MainActivity 的网页根布局上。
     *
     * @param root      MainActivity 的 FrameLayout 根（网页、连接屏、断线横幅都在里面）。
     * @param connectProgress 连接屏 ①②③ 进度块；失败时的「换一台」挂在这里。
     * @param onSwitch  真正执行切换的回调，返回 false = 正在连接中、这次没切（由本层收掉进度）。
     * @param onManage  去连接屏（新建 / 编辑 / 删除主机都在那里，不在弹层里重复一套表单）。
     */
    fun attach(
        activity: Activity,
        root: FrameLayout,
        connectProgress: ViewGroup?,
        onSwitch: (String) -> Boolean,
        onManage: () -> Unit,
    ): HostSwitcherUi = HostSwitcherUi(activity, root, connectProgress, onSwitch, onManage)
}

/**
 * HostSwitcher.attach 的返回值：MainActivity 只通过这几个方法回报事实。
 *
 * 所有方法都必须在主线程调用（与 syncConnectUi / onPageFinished 同线程）。
 */
class HostSwitcherUi internal constructor(
    private val activity: Activity,
    private val root: FrameLayout,
    private val connectProgress: ViewGroup?,
    private val onSwitch: (String) -> Boolean,
    private val onManage: () -> Unit,
) {

    /** A3 切换进度层走到哪一步。 */
    private enum class Phase { NONE, DISCONNECTING, DIALING, LOADING, FAILED, DONE }

    /** 弹层里一行要随探针结果更新的三个控件。 */
    private class RowViews(val dot: View, val latency: TextView, val status: TextView)

    private val prefs: SharedPreferences =
        activity.getSharedPreferences(DshApp.PREFS, Context.MODE_PRIVATE)
    private val ui = Handler(Looper.getMainLooper())
    private val gone = AtomicBoolean(false)
    private val healthLock = Any()

    /** 每台主机的「上次连接结果」，来自/写回 ssh_host_health_json。 */
    private var health: Map<String, HostHealth.Record> = emptyMap()

    private var webScreen = false
    private var tunneled = false
    private var failed = false

    /** A1：网页态常驻入口（顶部一条轻量胶囊，不是悬浮按钮）。 */
    private var chip: TextView? = null
    /** A5：连接失败时接到 ①②③ 下面的「换一台」。 */
    private var failureLink: TextView? = null
    /** A3：切换进度层（盖住整屏、BACK 收不掉）。 */
    private var progressLayer: View? = null
    private var progressTitle: TextView? = null
    private var progressSub: TextView? = null
    private var progressButtons: View? = null
    private var step1: TextView? = null
    private var step2: TextView? = null
    private var step3: TextView? = null

    private var switching = false
    private var phase = Phase.NONE
    private var switchLabel = ""
    private var switchTimeout: Runnable? = null
    private var loadFallback: Runnable? = null

    /** A2 底部弹层；每次打开重建，弹层里的探针用 sheetGeneration 作废上一代结果。 */
    private var sheet: Dialog? = null

    @Volatile
    private var sheetGeneration = 0
    private var rowViews: MutableMap<String, RowViews> = mutableMapOf()

    init {
        health = HostHealth.parse(prefs.getString(HostHealth.PREF_KEY, null))
        buildChip()
        buildFailureLink()
        buildProgressLayer()
    }

    // ── MainActivity 的挂钩点 ────────────────────────────────────────────────

    /** 顶层屏幕变了：只有网页态才露出主机入口。 */
    fun onWebScreen(web: Boolean) {
        webScreen = web
        refreshChip()
    }

    /**
     * 连接屏每次重画都会送来一份事实（syncConnectUi 里那一个调用点）。
     *
     * 它同时承担三件事：刷新入口/失败动作的可见性、把切换的结局写回健康记录、
     * 以及驱动 A3 进度层（①②③ 的勾从隧道与相位事实推导，不另记一套状态）。
     */
    fun onConnectFacts(f: ConnectFacts) {
        val prevTunneled = tunneled
        val prevFailed = failed
        tunneled = f.tunneled
        failed = f.failed
        val activeId = SshHosts.activeId(prefs)
        if (activeId.isNotBlank()) {
            if (f.tunneled && !prevTunneled) recordOk(activeId)
            if (f.failed && !f.connecting && !prevFailed) recordFail(activeId)
        }
        // A5：失败才给「换一台」；只有一台机器时切换等于重试，不摆这个误导动作。
        if (f.failed && !f.connecting) {
            if (!prevFailed) {
                failureLink?.visibility =
                    if (SshHosts.list(prefs).size > 1) View.VISIBLE else View.GONE
            }
        } else {
            failureLink?.visibility = View.GONE
        }
        refreshChip()
        if (!switching) return
        when {
            f.connecting -> setPhase(Phase.DIALING)
            f.failed -> setPhase(Phase.FAILED)
            f.tunneled -> setPhase(Phase.LOADING)
        }
    }

    /** A3 的最后一步：切换后的页面真的加载完了。 */
    fun onPageLoaded(url: String?) {
        if (!switching || phase != Phase.LOADING) return
        if (url?.startsWith("http") != true) return
        DiagLog.i(TAG, "多主机：切换后的页面已加载 url=" + url)
        phase = Phase.DONE
        renderSteps()
        loadFallback?.let {
            ui.removeCallbacks(it)
            loadFallback = null
        }
        ui.postDelayed({ if (!gone.get()) hideSwitchProgress() }, 450L)
    }

    /** 切换进行中吃掉 BACK：进度层不可被按没（A3）。 */
    fun onBackPressed(): Boolean = switching

    /** Activity 销毁：收掉弹层与所有自绘视图，作废在跑的探针。 */
    fun onDestroy() {
        gone.set(true)
        sheetGeneration++
        runCatching { sheet?.dismiss() }
        sheet = null
        ui.removeCallbacksAndMessages(null)
        listOf<View?>(chip, failureLink, progressLayer).forEach { v ->
            (v?.parent as? ViewGroup)?.removeView(v)
        }
        chip = null
        failureLink = null
        progressLayer = null
    }

    // ── A1 网页态入口 ────────────────────────────────────────────────────────

    /**
     * 入口放在网页顶部右上角，做成一条贴着边缘的深色胶囊。
     *
     * 为什么不是悬浮按钮：需求明确不要 FAB；这里的定位是「原生工具条上的一个主机开关」——
     * 只占右上角一小块，不占新的一行，也不碰页面左侧的抽屉开关（那个区域必须留出来）。
     * 这一区在真机上会与 dsh 自己的顶栏右上角有重叠，属于设计稿里「需真机权衡」的那一项。
     */
    private fun buildChip() {
        val v = UiKit.text(activity, "", 11f, UiKit.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = pillDrawable()
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener {
                DiagLog.i(TAG, "多主机：点网页态主机入口")
                showSheet()
            }
        }
        chip = v
        root.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(6)
            marginEnd = dp(8)
        })
        refreshChip()
    }

    /** 入口只在「网页态 + 隧道活着 + 没在切换」时显示；文案带上当前主机名。 */
    private fun refreshChip() {
        val v = chip ?: return
        val config = SshConfig.load(prefs)
        val label = shortLabel(config?.label ?: "切换主机")
        v.text = "⇄ " + label
        v.contentDescription = "切换主机（当前：" + label + "）"
        val alive = (activity.application as? DshApp)?.liveTunnel() != null
        v.visibility = if (webScreen && alive && !switching) View.VISIBLE else View.GONE
    }

    // ── A5 失败后的「换一台」 ────────────────────────────────────────────────

    /** 挂在连接屏 ①②③ 进度块的最后一行：只有失败且有多台主机时才出现。 */
    private fun buildFailureLink() {
        val link = UiKit.text(activity, "连不上？换一台试试 ›", 12f, UiKit.ACCENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(12), 0, dp(2))
            visibility = View.GONE
            setOnClickListener {
                DiagLog.i(TAG, "多主机：连接失败 → 打开换一台")
                showSheet()
            }
        }
        failureLink = link
        connectProgress?.addView(link, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    // ── A3 切换进度层 ────────────────────────────────────────────────────────

    /**
     * 自绘一层「正在切换到 X」，①②③ 与连接屏那套同构。
     *
     * 复用既有 ①②③ 的语义而不是控件：连接屏那块画在连接屏上，而用户此刻在网页上，
     * 所以这里在网页根布局里再画一份（控件自绘，逻辑仍只由隧道/相位事实驱动）。
     */
    private fun buildProgressLayer() {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val title = UiKit.text(activity, "", 17f, UiKit.TEXT, bold = true)
        progressTitle = title
        val sub = UiKit.text(activity, "", 11f, UiKit.MUTED)
        progressSub = sub
        step1 = stepLine()
        step2 = stepLine()
        step3 = stepLine()
        card.addView(title)
        card.addView(sub, rowTop(6))
        card.addView(step1, rowTop(18))
        card.addView(step2, rowTop(8))
        card.addView(step3, rowTop(8))

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        progressButtons = buttons
        buttons.addView(
            UiKit.button(activity, "换一台", UiKit.Style.PRIMARY, textSize = 13f) {
                hideSwitchProgress()
                showSheet()
            },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) }
        )
        buttons.addView(
            UiKit.button(activity, "返回连接屏", UiKit.Style.SECONDARY, textSize = 13f) {
                hideSwitchProgress()
                onManage()
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        card.addView(buttons, rowTop(18))

        val layer = FrameLayout(activity).apply {
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            addView(card, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = dp(26)
                rightMargin = dp(26)
            })
        }
        progressLayer = layer
        root.addView(layer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun stepLine(): TextView = UiKit.text(activity, "", 13f, UiKit.MUTED)

    /** 开始一次切换：先把进度层立起来，再交给回调去拆隧道/重拨。 */
    private fun beginSwitch(label: String) {
        switchLabel = label
        switching = true
        failed = false
        phase = Phase.DISCONNECTING
        progressTitle?.text = "正在切换到「" + label + "」…"
        progressSub?.text = "页面会重新加载一次，稍等几秒"
        progressButtons?.visibility = View.GONE
        renderSteps()
        progressLayer?.visibility = View.VISIBLE
        switchTimeout?.let { ui.removeCallbacks(it) }
        val timeout = Runnable {
            if (gone.get() || !switching) return@Runnable
            DiagLog.w(TAG, "多主机：切换超时（" + switchLabel + "）")
            hideSwitchProgress()
            toast("切换超时，请回连接屏重试")
        }
        switchTimeout = timeout
        ui.postDelayed(timeout, SWITCH_TIMEOUT_MS)
        refreshChip()
    }

    private fun setPhase(next: Phase) {
        if (phase == next || phase == Phase.DONE) return
        phase = next
        when (next) {
            Phase.FAILED -> {
                loadFallback?.let {
                    ui.removeCallbacks(it)
                    loadFallback = null
                }
                progressTitle?.text = "没连上「" + switchLabel + "」"
                progressSub?.text = "换一台试试，或回连接屏检查配置"
                progressButtons?.visibility = View.VISIBLE
            }
            Phase.LOADING -> {
                progressSub?.text = "隧道已通，正在等页面加载"
                loadFallback?.let { ui.removeCallbacks(it) }
                val fallback = Runnable {
                    if (!gone.get() && switching && phase == Phase.LOADING) {
                        DiagLog.i(TAG, "多主机：页面加载回调未到，按就绪收掉进度层")
                        hideSwitchProgress()
                    }
                }
                loadFallback = fallback
                ui.postDelayed(fallback, PAGE_READY_FALLBACK_MS)
            }
            else -> Unit
        }
        renderSteps()
    }

    private fun renderSteps() {
        val views = listOf(step1, step2, step3)
        val active = when (phase) {
            Phase.DISCONNECTING -> 0
            Phase.DIALING -> 1
            Phase.LOADING, Phase.DONE -> 2
            else -> -1
        }
        views.forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            when (phase) {
                Phase.FAILED -> {
                    v.text = STEP_LABELS[i] + (when (i) {
                        0 -> "  ✓"
                        1 -> "  ✗"
                        else -> "  未执行"
                    })
                    v.setTextColor(if (i == 1) UiKit.ERROR else UiKit.MUTED)
                }
                Phase.DONE -> {
                    v.text = STEP_LABELS[i] + "  ✓"
                    v.setTextColor(UiKit.TEXT)
                }
                else -> {
                    v.text = STEP_LABELS[i] + (when {
                        i < active -> "  ✓"
                        i == active -> "  ⋯"
                        else -> ""
                    })
                    v.setTextColor(if (i <= active) UiKit.TEXT else UiKit.MUTED)
                }
            }
        }
    }

    private fun hideSwitchProgress() {
        switching = false
        phase = Phase.NONE
        switchTimeout?.let {
            ui.removeCallbacks(it)
            switchTimeout = null
        }
        loadFallback?.let {
            ui.removeCallbacks(it)
            loadFallback = null
        }
        progressLayer?.visibility = View.GONE
        refreshChip()
    }

    // ── A2 主机底部弹层 ──────────────────────────────────────────────────────

    private fun showSheet() {
        if (gone.get() || switching) return
        val hosts = SshHosts.list(prefs)
        val activeId = SshHosts.activeId(prefs)
        val gen = ++sheetGeneration
        rowViews = mutableMapOf()

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetDrawable()
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        box.addView(View(activity).apply { background = handleDrawable() },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                UiKit.text(activity, "切换主机", 17f, UiKit.TEXT, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(UiKit.text(activity, "取消", 12f, UiKit.MUTED).apply {
                isClickable = true
                setPadding(dp(14), dp(8), 0, dp(8))
                setOnClickListener { dismissSheet() }
            })
        }
        box.addView(header, rowTop(10))

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        hosts.forEach { h ->
            list.addView(hostRow(h, h.id == activeId), rowTop(if (list.childCount == 0) 8 else 6))
        }
        if (hosts.isEmpty()) {
            list.addView(
                UiKit.text(activity, "还没有保存过主机，先去连接屏添加一台。", 12f, UiKit.MUTED),
                rowTop(14)
            )
        }
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val scroll = MaxHeightScrollView(activity, maxH).apply {
            addView(list, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        box.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        box.addView(
            UiKit.button(activity, "＋ 新建或管理主机…", UiKit.Style.SECONDARY, textSize = 13f) {
                dismissSheet()
                onManage()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(10) }
        )

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(box)
            setCanceledOnTouchOutside(true)
            setOnDismissListener {
                sheetGeneration++
                sheet = null
            }
        }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            // App 是沉浸式全屏：弹层自己的窗口也要保持全屏，否则弹出时系统栏会闪一下。
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        sheet = dialog
        dialog.show()
        hosts.forEach { startProbe(it, gen) }
    }

    /** 弹层的一行：状态点 / 名称 + 地址 / 延迟 + 上次结果，点即切换。 */
    private fun hostRow(h: SshConfig, active: Boolean): View {
        val dot = View(activity).apply {
            background = dotDrawable(if (active && tunneled) UiKit.OK else UiKit.DIM)
        }
        val latency = UiKit.text(activity, "…", 11f, UiKit.MUTED).apply { gravity = Gravity.END }
        val status = UiKit.text(
            activity,
            if (active) "当前" else HostHealth.summary(health[h.id], System.currentTimeMillis()),
            10f,
            if (active) UiKit.OK else UiKit.MUTED
        ).apply { gravity = Gravity.END }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rowDrawable(active)
            isClickable = true
            isFocusable = true
            addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(UiKit.text(activity, h.label, 15f, UiKit.TEXT))
                addView(
                    UiKit.text(activity, hostAddress(h), 11f, UiKit.MUTED),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                )
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(latency)
                addView(
                    status,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(3) }
                )
            })
            setOnClickListener {
                DiagLog.i(TAG, "多主机：弹层点击 " + h.label)
                pickHost(h.id)
            }
        }
        rowViews[h.id] = RowViews(dot, latency, status)
        return row
    }

    /** 点一行 = 切。配置不完整的先带去连接屏补，不在弹层里重开一套表单。 */
    private fun pickHost(id: String) {
        val picked = SshHosts.list(prefs).firstOrNull { it.id == id } ?: return
        if (!usable(picked)) {
            dismissSheet()
            toast("「" + picked.label + "」还没配全，先去连接屏补上")
            onManage()
            return
        }
        dismissSheet()
        DiagLog.i(TAG, "多主机：底部弹层选择 " + picked.label)
        beginSwitch(picked.label)
        if (!onSwitch(picked.id)) {
            hideSwitchProgress()
            toast("正在连接，稍后再切")
        }
    }

    private fun dismissSheet() {
        sheetGeneration++
        runCatching { sheet?.dismiss() }
        sheet = null
    }

    // ── A2 延迟探针 ──────────────────────────────────────────────────────────

    /**
     * 延迟来自对 SSH 端口的一次直连握手（TCP connect 往返），不是 ping 也不是 HTTP。
     *
     * 为什么不用「上次拨号耗时」：那里面含认证与密钥交换，反映的是 SSH 建立成本，
     * 用户看「延迟 ms」想知道的其实是「这台机器现在通不通、快不快」。
     * 探针在后台线程跑，900ms 超时，失败就显示「不通」—— 这正是 A2 想提前告诉用户的事。
     */
    private fun startProbe(h: SshConfig, gen: Int) {
        if (h.host.isBlank()) return
        val port = h.port.coerceIn(1, 65535)
        Thread {
            val started = SystemClock.elapsedRealtime()
            val ok = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(h.host, port), PROBE_TIMEOUT_MS)
                }
            }.isSuccess
            val ms = SystemClock.elapsedRealtime() - started
            if (gone.get() || gen != sheetGeneration) return@Thread
            ui.post { onProbeResult(h.id, ok, ms, gen) }
        }.apply {
            name = "host-probe"
            isDaemon = true
        }.start()
    }

    private fun onProbeResult(id: String, ok: Boolean, ms: Long, gen: Int) {
        if (gone.get() || gen != sheetGeneration) return
        synchronized(healthLock) {
            health = if (ok) {
                HostHealth.noteOk(health, id, System.currentTimeMillis())
            } else {
                HostHealth.noteFail(health, id, "探针失败")
            }
        }
        persistHealth()
        applyProbe(id, ok, ms)
    }

    private fun applyProbe(id: String, ok: Boolean, ms: Long) {
        val rv = rowViews[id] ?: return
        val active = id == SshHosts.activeId(prefs) && tunneled
        rv.latency.text = when {
            !ok && active -> "已连接"
            !ok -> "不通"
            else -> ms.toString() + " ms"
        }
        rv.latency.setTextColor(if (ok || active) UiKit.OK else UiKit.ERROR)
        rv.dot.background = dotDrawable(if (ok || active) UiKit.OK else UiKit.ERROR)
        when {
            active -> {
                rv.status.text = "当前"
                rv.status.setTextColor(UiKit.OK)
            }
            ok -> {
                rv.status.text = "刚连过 ✓"
                rv.status.setTextColor(UiKit.MUTED)
            }
            else -> {
                rv.status.text = "上次失败"
                rv.status.setTextColor(UiKit.ERROR)
            }
        }
    }

    // ── 健康记录（上次连接结果）──────────────────────────────────────────────

    private fun recordOk(id: String) {
        synchronized(healthLock) {
            health = HostHealth.noteOk(health, id, System.currentTimeMillis())
        }
        persistHealth()
    }

    private fun recordFail(id: String) {
        synchronized(healthLock) {
            health = HostHealth.noteFail(health, id, "连接失败")
        }
        persistHealth()
    }

    private fun persistHealth() {
        runCatching {
            prefs.edit().putString(HostHealth.PREF_KEY, HostHealth.encode(health)).apply()
        }.onFailure {
            DiagLog.w(TAG, "多主机：健康记录写入失败 " + it.javaClass.simpleName)
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────────

    /** 够不够直接连：地址/账号在，认证材料也在（私钥还要文件真的在）。 */
    private fun usable(h: SshConfig): Boolean {
        if (h.host.isBlank() || h.user.isBlank()) return false
        if (h.usesKey) return h.keyPath.isNotBlank() && File(h.keyPath).exists()
        return h.password.isNotEmpty()
    }

    private fun hostAddress(h: SshConfig): String {
        val who = if (h.user.isBlank()) "" else h.user + "@"
        val port = if (h.port == SshConfig.DEFAULT_SSH_PORT) "" else ":" + h.port
        val addr = who + h.host + port
        return if (addr.isBlank()) "（地址未填）" else addr
    }

    private fun shortLabel(text: String): String =
        if (text.length <= 12) text else text.take(11) + "…"

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = UiKit.dp(activity, v)

    private fun rowTop(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun pillDrawable(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(15).toFloat()
        setColor(0xCC16161D.toInt())
        setStroke(dp(1), 0x3DFFFFFF)
    }

    private fun dotDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rowDrawable(active: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (active) 0x1AFFFFFF else 0x0AFFFFFF)
        setStroke(dp(1), if (active) 0x3DFFFFFF else 0x1AFFFFFF)
    }

    private fun sheetDrawable(): GradientDrawable = GradientDrawable().apply {
        setColor(0xFF14141A.toInt())
        cornerRadii = floatArrayOf(
            dp(22).toFloat(), dp(22).toFloat(),
            dp(22).toFloat(), dp(22).toFloat(),
            0f, 0f, 0f, 0f
        )
        setStroke(dp(1), 0x3DFFFFFF)
    }

    private fun handleDrawable(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(2).toFloat()
        setColor(0x33FFFFFF)
    }

    /** 内容最多长到 maxHeightPx，超了在弹层内部滚动。 */
    private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) :
        ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val capped = View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, capped)
        }
    }
}

/**
 * 每台主机「上次连接结果」的纯逻辑（ssh_host_health_json）。
 *
 * 为什么单独存一个键而不塞进 SshConfig：那是连接参数，改显示名都不该让隧道重建
 * （见 fingerprint）；而健康记录是每拨一次号就变的观测值，混进去只会污染配置语义。
 *
 * 只做 JSON 编解码与升级规则，不碰 Android —— JVM 单测可直接跑（见 HostHealthTest）。
 */
object HostHealth {
    const val PREF_KEY = "ssh_host_health_json"

    /** 一条记录：最近一次成功的时间戳（0 = 没成功过），以及最近一次失败的类型。 */
    data class Record(val okAt: Long = 0L, val err: String = "")

    /** JSON → 记录表。空/非法整体丢弃；单条坏掉只跳那一条（与 SshHosts.parseList 同策略）。 */
    fun parse(raw: String?): Map<String, Record> {
        if (raw.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Record>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val one = runCatching { obj.getJSONObject(id) }.getOrNull() ?: continue
            out[id] = Record(okAt = one.optLong("okAt", 0L), err = one.optString("err", ""))
        }
        return out
    }

    fun encode(records: Map<String, Record>): String {
        val obj = JSONObject()
        records.forEach { (id, r) ->
            obj.put(id, JSONObject().put("okAt", r.okAt).put("err", r.err))
        }
        return obj.toString()
    }

    /** 成功：刷新 okAt 并清掉失败标记。 */
    fun noteOk(records: Map<String, Record>, id: String, now: Long): Map<String, Record> =
        records + (id to Record(now, ""))

    /** 失败：只记失败，保留 okAt —— 「最近一次成功」对用户仍然有用。 */
    fun noteFail(records: Map<String, Record>, id: String, err: String): Map<String, Record> =
        records + (id to Record(records[id]?.okAt ?: 0L, err))

    /** 弹层里那一行小字：「上次失败」/「刚连过」/「N 分钟前 ✓」；没记录返回空串。 */
    fun summary(record: Record?, now: Long): String = when {
        record == null -> ""
        record.err.isNotBlank() -> "上次失败"
        record.okAt <= 0L -> ""
        else -> {
            val minutes = ((now - record.okAt) / 60_000L).coerceAtLeast(0L)
            when {
                minutes < 1L -> "刚连过 ✓"
                minutes < 60L -> minutes.toString() + " 分钟前 ✓"
                else -> (minutes / 60L).toString() + " 小时前 ✓"
            }
        }
    }
}
