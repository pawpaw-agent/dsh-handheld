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
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.roundToInt
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

/** ④ 胶囊吸附动画：120~180ms 之间取中，太快看不出「吸过去了」，太慢像卡住。 */
private const val CHIP_SNAP_MS = 160L

/** 胶囊贴左右的余量（原先固定右上角时用的就是 marginEnd = 8dp）。 */
private const val CHIP_SIDE_GAP_DP = 8

/** 胶囊贴上下的余量（原先的 topMargin 是 6dp；底部同样留 6dp，别让圆角压着屏幕边）。 */
private const val CHIP_VERT_GAP_DP = 6

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

    /** ④：胶囊位置（贴哪一侧 + 纵向像素）；null = 没存过，按默认的右上角摆。 */
    private var chipPos: HostChipPos.Pos? = null
    /** 正在拖动胶囊：布局回调这时不要按模型把它重摆一遍。 */
    private var chipDragging = false
    /** 按下那一刻的手指位置与胶囊几何，拖动位移都相对它算。 */
    private var chipDownX = 0f
    private var chipDownY = 0f
    private var chipBaseLeft = 0
    private var chipBaseTop = 0
    private var chipStartTx = 0f
    private var chipStartTy = 0f
    /** 吸附动画的代次：收尾动作发现自己不是最新那次就什么都不写。 */
    private var chipSnapToken = 0
    /**
     * 父容器尺寸变了（旋转 / 分屏 / 系统栏让位）就按新尺寸重新夹紧。
     *
     * 这个字段必须声明在 init 之前：init 里的 buildChip 会立刻注册它。
     */
    private val chipLayoutWatcher = View.OnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
        if (right != oldRight || bottom != oldBottom) applyChipPos()
    }
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
        // ④：位置记录和健康记录一样是自包含的一条；坏了就当作没存过，回落到默认右上角。
        chipPos = HostChipPos.parse(prefs.getString(HostChipPos.PREF_KEY, null))
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
        val page = url ?: return
        // 带 token 的中间页不算「页面已加载」，与 onPageFinished 里置 ack 的判据保持一致。
        if (!page.startsWith("http") || page.contains("?token=")) return
        DiagLog.i(TAG, "多主机：切换后的页面已加载 url=" + page)
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
        chipSnapToken++
        root.removeOnLayoutChangeListener(chipLayoutWatcher)
        listOf<View?>(chip, failureLink, progressLayer).forEach { v ->
            if (v != null) (v.parent as? ViewGroup)?.removeView(v)
        }
        chip = null
        failureLink = null
        progressLayer = null
    }

    // ── A1 网页态入口 ────────────────────────────────────────────────────────

    /**
     * 入口做成一条贴着边缘的深色胶囊，默认在右上角。
     *
     * 为什么不是悬浮按钮：需求明确不要 FAB；这里的定位是「原生工具条上的一个主机开关」——
     * 只占一小块，不占新的一行，也不碰页面左侧的抽屉开关（那个区域必须留出来）。
     * 这一区在真机上会与 dsh 自己的顶栏右上角有重叠，正是设计稿里「需真机权衡」的那一项：
     * 现在交给用户自己拖开（见 [installChipTouch]），位置记在 ssh_host_chip_pos 里。
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
        // 位置用绝对边距表达（不再是 gravity TOP|END）：拖动改的就是这两个边距。
        // 这里的初值只是占位，第一次布局时 applyChipPos 会按记忆位置改写。
        root.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(CHIP_SIDE_GAP_DP)
            topMargin = dp(CHIP_VERT_GAP_DP)
        })
        installChipTouch(v)
        root.addOnLayoutChangeListener(chipLayoutWatcher)
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
        val show = webScreen && alive && !switching
        // 文案变了宽度就变；露出之前先按记忆位置摆好，免得先在旧边距上闪一帧再跳过去。
        if (show) applyChipPos()
        v.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ── ④ 胶囊拖动与位置记忆 ────────────────────────────────────────────────

    /**
     * 触摸分两种走法：位移超过系统触摸阈值（约 8dp）才算拖动，未达阈值的抬手仍是点击。
     *
     * 为什么不是「长按才拖」：胶囊本来就压在网页顶栏上，用户想挪开时不会先去长按；
     * 直接拖 + 阈值判定既保住了「点一下开弹层」，横向滑动时也不会被误当成拖动。
     * 点击显式走 [View.performClick]：原生按下态被这层 onTouch 吃掉了，只有它能让原来的
     * onClick（含 TalkBack 的点击动作）继续生效。
     */
    private fun installChipTouch(v: TextView) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 上一次吸附动画没走完就按下了：以屏幕上此刻的位置为基准，别从旧边距起算。
                    cancelChipAnimation(view)
                    val lp = view.layoutParams as? FrameLayout.LayoutParams
                    if (lp == null) {
                        false
                    } else {
                        chipDragging = false
                        chipDownX = e.rawX
                        chipDownY = e.rawY
                        chipBaseLeft = lp.leftMargin
                        chipBaseTop = lp.topMargin
                        chipStartTx = view.translationX
                        chipStartTy = view.translationY
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - chipDownX
                    val dy = e.rawY - chipDownY
                    if (!chipDragging && dx * dx + dy * dy < (slop * slop).toFloat()) {
                        true
                    } else {
                        if (!chipDragging) {
                            chipDragging = true
                            setChipPressed(true)
                            // 拖动期间别让父容器把事件当成自己的滚动或点击收走。
                            view.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        dragChip(view, dx, dy)
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // 极快的一甩可能一个 MOVE 都没送到：按抬手点的位移补判一次，别把它当点击。
                    if (!chipDragging) {
                        val dx = e.rawX - chipDownX
                        val dy = e.rawY - chipDownY
                        if (dx * dx + dy * dy >= (slop * slop).toFloat()) {
                            chipDragging = true
                            dragChip(view, dx, dy)
                        }
                    }
                    val dragged = chipDragging
                    endChipDrag(view)
                    if (dragged) snapChip(view) else view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // 被系统打断（来电、父容器抢走事件）也照样落位，别停在半空。
                    val dragged = chipDragging
                    endChipDrag(view)
                    if (dragged) snapChip(view)
                    true
                }
                // 多指动作属于同一次手势：吃掉，别漏给下面的网页。
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> true
                else -> false
            }
        }
    }

    /**
     * 拖动中只改 translation，不动边距。
     *
     * 边距一改就是整棵视图树重新布局（网页根里还挂着 WebView），每帧来一次会明显掉手感；
     * translation 只影响绘制。落位时才把最终边距一次性写回（见 [snapChip]）。
     */
    private fun dragChip(view: View, dx: Float, dy: Float) {
        val area = chipArea() ?: return
        val size = chipSize(view)
        if (size[0] <= 0 || size[1] <= 0) return
        val placed = HostChipPos.at(
            (chipBaseLeft + chipStartTx + dx).roundToInt(),
            (chipBaseTop + chipStartTy + dy).roundToInt(),
            area, size[0], size[1]
        )
        view.translationX = (placed.left - chipBaseLeft).toFloat()
        view.translationY = (placed.top - chipBaseTop).toFloat()
    }

    /** 手指离开了：恢复按下态、放开父容器的事件拦截（拖动与被打断都走这里）。 */
    private fun endChipDrag(view: View) {
        chipDragging = false
        setChipPressed(false)
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    /**
     * 松手吸附：水平贴到最近的一侧（纵向保留），补一段 160ms 的位移动画。
     *
     * 动画只是看起来在动 —— 最终边距在动画结束时一次性写回、translation 归零；否则下一次
     * 布局（旋转、弹层收起、系统栏让位）会按旧边距把胶囊摆回拖之前的位置。
     * 位置在松手时就落盘，不依赖动画跑完：Activity 在这 160ms 里被销毁也不会丢掉记忆。
     */
    private fun snapChip(view: View) {
        val area = chipArea() ?: return
        val size = chipSize(view)
        if (size[0] <= 0 || size[1] <= 0) return
        val cur = HostChipPos.at(
            (chipBaseLeft + view.translationX).roundToInt(),
            (chipBaseTop + view.translationY).roundToInt(),
            area, size[0], size[1]
        )
        val target = HostChipPos.resolve(cur.pos, area, size[0], size[1])
        chipPos = target.pos
        persistChipPos(target.pos)
        val token = ++chipSnapToken
        view.animate()
            .translationX((target.left - chipBaseLeft).toFloat())
            .translationY((target.top - chipBaseTop).toFloat())
            .setDuration(CHIP_SNAP_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 被新的拖动 / 布局作废，或 Activity 已经销毁：这次收尾就什么都不写。
                if (!gone.get() && token == chipSnapToken) {
                    val lp = view.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.leftMargin = target.left
                        lp.topMargin = target.top
                        view.translationX = 0f
                        view.translationY = 0f
                        view.requestLayout()
                    }
                }
            }
            .start()
    }

    /** 作废正在跑的吸附动画：cancel 会连带跑收尾动作，所以先抬代次把它挡掉。 */
    private fun cancelChipAnimation(view: View) {
        chipSnapToken++
        view.animate().cancel()
    }

    /**
     * 把胶囊摆到记忆的位置；父容器尺寸或安全区变了也走这里。
     *
     * 位置没变化就直接返回 —— 否则「设边距 → 触发布局 → 布局回调 → 再设边距」会自己转起来。
     */
    private fun applyChipPos() {
        val v = chip ?: return
        if (gone.get() || chipDragging) return
        val area = chipArea() ?: return
        val size = chipSize(v)
        if (size[0] <= 0 || size[1] <= 0) return
        val placed = HostChipPos.resolve(chipPos, area, size[0], size[1])
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.leftMargin == placed.left && lp.topMargin == placed.top) return
        cancelChipAnimation(v)
        lp.leftMargin = placed.left
        lp.topMargin = placed.top
        v.translationX = 0f
        v.translationY = 0f
        v.requestLayout()
    }

    /**
     * 父容器里给胶囊用的可用矩形：扣掉系统栏 / 刘海，再留一层贴边余量。
     *
     * App 是沉浸全屏，系统栏默认收起，所以 inset 平时是 0；但挖孔屏的刘海、以及用户划出
     * 系统栏的那一刻会给出真实值，胶囊不能压在这些区域下面。
     */
    private fun chipArea(): HostChipPos.Area? {
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return null
        val bars = androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
        )
        return HostChipPos.area(
            w, h,
            bars?.left ?: 0, bars?.top ?: 0, bars?.right ?: 0, bars?.bottom ?: 0,
            dp(CHIP_SIDE_GAP_DP), dp(CHIP_VERT_GAP_DP)
        )
    }

    /** 胶囊的像素尺寸；还没被布局过（GONE 期间、或刚改完文案）就按内容量一次。 */
    private fun chipSize(v: View): IntArray {
        if (v.width <= 0 || v.height <= 0 || v.isLayoutRequested) {
            val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            v.measure(spec, spec)
        }
        return intArrayOf(v.measuredWidth, v.measuredHeight)
    }

    /** 按下的视觉：背景加深一档。不改尺寸，免得拖动时胶囊自己忽大忽小。 */
    private fun setChipPressed(pressed: Boolean) {
        chip?.background = pillDrawable(pressed)
    }

    private fun persistChipPos(pos: HostChipPos.Pos) {
        runCatching {
            prefs.edit().putString(HostChipPos.PREF_KEY, HostChipPos.encode(pos)).apply()
        }.onFailure {
            DiagLog.w(TAG, "多主机：胶囊位置写入失败 " + it.javaClass.simpleName)
        }
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
            val tv = v ?: return@forEachIndexed
            when (phase) {
                Phase.FAILED -> {
                    tv.text = STEP_LABELS[i] + (when (i) {
                        0 -> "  ✓"
                        1 -> "  ✗"
                        else -> "  未执行"
                    })
                    tv.setTextColor(if (i == 1) UiKit.ERROR else UiKit.MUTED)
                }
                Phase.DONE -> {
                    tv.text = STEP_LABELS[i] + "  ✓"
                    tv.setTextColor(UiKit.TEXT)
                }
                else -> {
                    tv.text = STEP_LABELS[i] + (when {
                        i < active -> "  ✓"
                        i == active -> "  ⋯"
                        else -> ""
                    })
                    tv.setTextColor(if (i <= active) UiKit.TEXT else UiKit.MUTED)
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

    /** 地址一律写成 user@host:port（A2 要求看到端口；多主机时这个端口是必要信息）。 */
    private fun hostAddress(h: SshConfig): String {
        val who = if (h.user.isBlank()) "" else h.user + "@"
        val addr = who + h.host + ":" + h.port
        return if (h.host.isBlank()) "（地址未填）" else addr
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

    /** 胶囊底：不透明一点 = 「按下了」，拖动时用（描边也提亮一档，黑底上才看得出来）。 */
    private fun pillDrawable(pressed: Boolean = false): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(15).toFloat()
        setColor(if (pressed) 0xF216161D.toInt() else 0xCC16161D.toInt())
        setStroke(dp(1), if (pressed) 0x66FFFFFF else 0x3DFFFFFF)
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

/**
 * 网页态胶囊的位置（纯逻辑，不碰 Android —— JVM 单测可直接跑，见 HostChipPosTest）。
 *
 * 为什么存「贴哪一侧 + 纵向像素」而不是原始 x/y：旋转屏幕后父容器宽度会变，一个旧的 x
 * 很可能落在新屏幕之外；改成「侧 + 纵向」后，水平方向跟着新的边缘走，纵向再按新高度夹
 * 一次即可 —— 这就是需求里的「边缘 + 偏移」，只是松手必定吸附到边缘，水平偏移恒为 0。
 *
 * 存储格式：ssh_host_chip_pos = "end|406"（侧|纵向 px）。解析失败一律当作「没存过」，
 * 回落到默认的右上角：一条坏记录不该让入口消失或跑到屏幕外。
 */
object HostChipPos {

    const val PREF_KEY = "ssh_host_chip_pos"

    /** 停在哪一侧。 */
    enum class Edge { START, END }

    /**
     * @param edge 吸附侧（START = 左边，END = 右边）
     * @param top  胶囊上沿的 px（相对父容器顶部）
     */
    data class Pos(val edge: Edge, val top: Int)

    /** 可放置区域（px，已扣掉系统栏 / 刘海与贴边余量）。 */
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 一次摆放的结果：夹紧后的模型，以及此刻该用的左上角像素。 */
    data class Placement(val pos: Pos, val left: Int, val top: Int)

    /** 存成 "end|406"；用字符串而不是枚举序号，改枚举顺序读旧值也不会错位。 */
    fun encode(pos: Pos): String =
        (if (pos.edge == Edge.END) "end" else "start") + "|" + pos.top

    /** 容错解析：为空、缺字段、非数字、未知侧 —— 一律返回 null（= 没存过）。 */
    fun parse(raw: String?): Pos? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 2) return null
        val edge = when (parts[0].trim()) {
            "start" -> Edge.START
            "end" -> Edge.END
            else -> return null
        }
        return Pos(edge, parts[1].trim().toIntOrNull() ?: return null)
    }

    /**
     * 按容器尺寸算可用区域：四边都扣掉系统栏 / 刘海（inset）与贴边余量。
     *
     * 扣完比胶囊还小时让 left/right（top/bottom）相等，后面用 coerceAtLeast 兜住，
     * 保证「贴住左上角」而不是出现负的可用宽度。
     */
    fun area(
        containerW: Int,
        containerH: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        gapH: Int,
        gapV: Int,
    ): Area {
        val left = insetLeft + gapH
        val top = insetTop + gapV
        val right = (containerW - insetRight - gapH).coerceAtLeast(left)
        val bottom = (containerH - insetBottom - gapV).coerceAtLeast(top)
        return Area(left, top, right, bottom)
    }

    /** 松手该吸到哪一侧：看中心落在可用区的左半还是右半。 */
    fun snapEdge(left: Int, area: Area, chipW: Int): Edge =
        if (left + chipW / 2 < area.left + area.width / 2) Edge.START else Edge.END

    /** 拖动中的落点：横向纵向都夹进可用区（父容器比胶囊还小时贴住左上）。 */
    fun at(left: Int, top: Int, area: Area, chipW: Int, chipH: Int): Placement {
        val maxLeft = (area.right - chipW).coerceAtLeast(area.left)
        val maxTop = (area.bottom - chipH).coerceAtLeast(area.top)
        val l = left.coerceIn(area.left, maxLeft)
        val t = top.coerceIn(area.top, maxTop)
        return Placement(Pos(snapEdge(l, area, chipW), t), l, t)
    }

    /**
     * 恢复 / 夹紧：把存下来的模型摆进当前可用区，返回夹紧后的模型与实际左上角。
     *
     * 没存过（null）就回到设计稿的默认位置 —— 右上角，等于把原先的 gravity TOP|END 摊成像素。
     */
    fun resolve(saved: Pos?, area: Area, chipW: Int, chipH: Int): Placement {
        val maxLeft = (area.right - chipW).coerceAtLeast(area.left)
        val maxTop = (area.bottom - chipH).coerceAtLeast(area.top)
        val top = (saved?.top ?: area.top).coerceIn(area.top, maxTop)
        val edge = saved?.edge ?: Edge.END
        val left = if (edge == Edge.END) maxLeft else area.left
        return Placement(Pos(edge, top), left, top)
    }
}
