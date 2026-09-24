package com.dshhandheld.app

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 连接屏与终端模式共用的 UI 基元。
 *
 * 抽出来的两个理由：
 *  1. **配色**此前在 `MainActivity` 与已移除的 `TuiActivity` 各定义一份（`values/colors.xml` 里
 *     还有第三份，13 个颜色全零引用，已删）。同一组黑白色调写两遍，改一处就会漂移。
 *  2. **按钮 chrome**在两个 Activity 里共重复 12 次（`text` / `isAllCaps` / 字号 / 字色 /
 *     背景 / 点击），只有文案与三种样式不同。其中两处漏了字号，正是复制粘贴漂移的证据。
 *
 * 这里只放**无状态**的东西：常量、尺寸换算、以及几个控件工厂。凡是需要持有 Activity
 * 字段的逻辑一律留在原处。
 */
object UiKit {

    // ── 配色：全 App 黑白色调，与启动图标一致 ──────────────────────────────
    const val BG = 0xFF0A0A0E.toInt()
    const val TEXT = 0xFFF5F5F7.toInt()
    const val TITLE = 0xFFF5F5F7.toInt()
    const val MUTED = 0x99FFFFFF.toInt()
    const val DIM = 0x55FFFFFF.toInt()
    const val HINT = 0x66FFFFFF.toInt()
    const val ACCENT = 0xFFF5F5F7.toInt()
    const val ACCENT_TEXT = 0xFF0A0A0E.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()

    /**
     * 连接屏状态点的两个状态色（0.1.10 起引入）。
     *
     * 全 App 只有这一处**刻意**用了彩色：状态块要在半秒内回答「连上没有」，
     * 而黑白灰里表达「好 / 进行中」只能靠读文案。除此之外一切仍是黑白。
     */
    const val OK = 0xFF5EE6A8.toInt()
    const val WARN = 0xFFF5C86B.toInt()

    /** 终端键排的按下态颜色（Termux 同款青蓝，用于高亮锁定的修饰键）。 */
    const val TEXT_ACTIVE = 0xFF80DEEA.toInt()

    /**
     * dp → px。
     *
     * `+ 0.5f` 是四舍五入，不是笔误：直接截断会让 1dp 在低密度屏上变成 0px
     * （MainActivity 原来就是这个写法，迁移时保持一致）。
     */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** 纵向排列时最常用的 LayoutParams：只调上边距。 */
    fun rowParams(
        top: Int = 0,
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    ) = LinearLayout.LayoutParams(width, height).apply { topMargin = top }

    /**
     * 按钮样式。三种：主操作（浅底深字）/ 次操作（描边）/ 危险操作（红字描边）。
     */
    enum class Style { PRIMARY, SECONDARY, ERROR }

    /**
     * 统一按钮工厂。
     *
     * @param textSize 传 `null` 保持主题默认字号（平台 `Theme.Material` 的按钮是 14sp）。
     *   刻意保留这个分叉：大字号的落地页按钮（「下一步」「连上并打开…」）本来就不设字号，
     *   统一成某个具体值会改变观感。
     */
    fun button(
        context: Context,
        text: String,
        style: Style = Style.PRIMARY,
        textSize: Float? = null,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        if (textSize != null) setTextSize(textSize)
        setTextColor(
            when (style) {
                Style.PRIMARY -> ACCENT_TEXT
                Style.SECONDARY -> TEXT
                Style.ERROR -> ERROR
            }
        )
        setBackgroundResource(
            when (style) {
                // 危险操作复用次操作的描边背景，只换字色（「断开连接」原样如此）
                Style.PRIMARY -> R.drawable.bg_button_primary
                Style.SECONDARY, Style.ERROR -> R.drawable.bg_button_secondary
            }
        )
        setOnClickListener { onClick() }
    }

    /**
     * 统一文本工厂。
     *
     * @param bold 用 `Typeface.DEFAULT_BOLD`（不是 `sans-serif-light`，标题与正文的
     *   字体差异是刻意保留的观感）。
     * @param letterSpacing 0 表示不设置（平台默认），非 0 才写入。
     */
    fun text(
        context: Context,
        content: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        letterSpacing: Float = 0f
    ): TextView = TextView(context).apply {
        text = content
        textSize = size
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (letterSpacing != 0f) this.letterSpacing = letterSpacing
    }
}
