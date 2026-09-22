package com.dshhandheld.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView
import com.termux.shared.terminal.io.extrakeys.SpecialButton
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import com.dshhandheld.diag.DiagLog

/**
 * dsh-handheld SSH 终端模式 —— Dropbear dbclient + Termux 原生渲染。
 *
 * 架构（B 方案，桥接清零）：
 *  - TerminalSession 直接启动 `libdbclient.so`（我们 CI 从官方 mkj/dropbear
 *    源码编译的 dropbear 客户端）：`dbclient -i <key> -y -tt user@host`
 *  - JNI createSubprocess 为它分配本地 pty；dbclient 经 sshd 拿到远端 pty；
 *    键盘/渲染/滚动/光标全部走 Termux 标准路径 —— 无任何手动桥。
 *  - 注意：argv 数组第一个元素必须是程序名（execvp(cmd, argv) 的约定）。
 *
 * 认证（2026-09-17 订正，审计 L5）：本项目**两条路都可用** ——
 *  - 密码：dropbear 的 getpass() 在 Android 上不可用，但 `scripts/localoptions.h` 开了
 *    `DROPBEAR_CLI_PASSWORD_AUTH 1` + `DROPBEAR_USE_PASSWORD_ENV 1`，并用构建期补丁把
 *    getpass 换成读 `DROPBEAR_PASSWORD` 环境变量（见 [launchSession] 的密码分支）。
 *    本节此前写「官方 CI 采用 pubkey-only，因此本模式使用私钥」——与 localoptions.h 相反。
 *  - 私钥：连接屏导入的私钥（ssh_json authType=key）直接用；没有则本 Activity 用
 *    dropbearkey 生成一对（app 私有目录），把公钥加到服务端 authorized_keys（日志给命令）。
 *
 * 许可：Termux terminal-view/emulator 与 Dropbear 均为 permissive/GPL-3.0，
 * 本项目对应以 GPL-3.0 分发。
 */
class TuiActivity : Activity() {

    private var terminalView: TerminalView? = null
    private var extraKeysView: ExtraKeysView? = null
    private var session: TerminalSession? = null
    private var statusView: TextView? = null

    private companion object {
        const val TAG = "TuiActivity"
        /** `dropbearkey` 的等待上限（审计 M8）：挂住时不能永久停在「准备 SSH 密钥…」。 */
        const val KEYGEN_TIMEOUT_SEC = 10L
        // 配色与连接屏共用一份定义（见 UiKit）；此前两个 Activity 各写一遍。
        const val COL_BG = UiKit.BG
        const val COL_TEXT = UiKit.TEXT
        const val COL_TEXT_ACTIVE = UiKit.TEXT_ACTIVE
        const val COL_MUTED = UiKit.MUTED
        const val KEY_NAME = "id_dropbear"

        /**
         * extra-keys 布局 —— Termux 官方 7+7（style="default" 文字风格），
         * 仅 KEYBOARD（⌨ 收/呼软键盘）跨两行：{key:"KEYBOARD", rowSpan:2}，
         * 其余按键每个一格。ExtraKeysView 支持 rowSpan 单元格合并。
         */
        const val EXTRA_KEYS_LAYOUT =
            """[["ESC","/",{"key":"-","popup":"|"},"HOME","UP","END","PGUP",
               {"key":"KEYBOARD","rowSpan":2}],
               ["TAB","CTRL","ALT","LEFT","DOWN","RIGHT","PGDN"]]"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 「decor 不替我们让位」必须在 setContentView **之前**声明：这一句决定后面收到的
        // 是「已经摆好的内容区」还是「原始 inset」。理由见 applyInsetsHandling()。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        val root = FrameLayout(this).apply { setBackgroundColor(COL_BG) }
        setContentView(root)
        applyInsetsHandling(root)

        statusView = TextView(this).apply {
            text = "准备 SSH…"
            textSize = 14f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        terminalView = TerminalView(this, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            // 字号：Termux setTextSize 直接用 px（其 KDoc 误导）；按密度换算，默认 15dp。
            // ⚠️ `tui_font_size_px` 现在**只有读、没有写**（审计 L5：全仓没有任何写入者，
            // 也没有 A+/A- 的 UI）——所以那个 pref 恒为默认值；注释里承诺「可调」是空头支票，
            // 要么补 UI，要么等真有需求再说。
            val density = resources.displayMetrics.density
            val defaultPx = (15 * density).toInt()
            val savedPx = getSharedPreferences("dsh-handheld", MODE_PRIVATE)
                .getInt("tui_font_size_px", defaultPx)
            val finalPx = savedPx.coerceIn((12 * density).toInt(), (36 * density).toInt())
            DiagLog.i(TAG, "init font: density=$density default=${defaultPx}px saved=${savedPx}px final=${finalPx}px")
            setTextSize(finalPx)
            setTypeface(Typeface.MONOSPACE)
        }
        // 修饰键（CTRL/ALT/SHIFT/FN）状态桥接：Termux 同款 —— 由 ExtraKeysView 的
        // SpecialButton 持有，readXxxKey 时读取并按需自动复位（长按锁定除外）。
        terminalView?.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = 1f
            // 点击终端区域：聚焦 + 弹软键盘（Termux 同款；仅 requestFocus 不够，
            // 必须显式 showSoftInput 才会弹出）
            override fun onSingleTapUp(e: android.view.MotionEvent) {
                terminalView?.requestFocus()
                showSoftKeyboard()
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            // 字符级输入（Termux 默认 true；三星/Gboard 兼容）
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onLongPress(event: android.view.MotionEvent): Boolean = false

            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
            override fun readControlKey(): Boolean = readModifier(SpecialButton.CTRL)
            override fun readAltKey(): Boolean = readModifier(SpecialButton.ALT)
            override fun readShiftKey(): Boolean = readModifier(SpecialButton.SHIFT)
            override fun readFnKey(): Boolean = readModifier(SpecialButton.FN)

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
            // emulator 就绪（首次 attach / 尺寸变化）时启动光标闪烁
            override fun onEmulatorSet() { startCursorBlinker() }
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        })
        column.addView(terminalView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        column.addView(buildExtraKeys())
        root.addView(column)

        // 状态提示放在最上层（覆盖终端区域），失败信息（如 dbclient 缺失）可见
        root.addView(statusView)

        startDbclient()
    }

    /**
     * 自己算内容区（2026-09-22 真机回归的修复；证据见 docs/known-issues.md §九）。
     *
     * 为什么必须自己做：Android 15 起，targetSdk ≥ 35 的 App 被**强制 edge-to-edge**，
     * 系统不再替应用让位 —— `windowSoftInputMode=adjustResize` 形同虚设。同一份 APK
     * 双机实测：Android 13 上终端视图随键盘收缩 981px（正好是键盘高度）、PTY 39→21 行；
     * Android 16 上窗口 frame 与 PTY 都不动，键盘直接盖住整条附加键栏（发现 1），
     * 终端首行还落在挖孔带里（发现 4）。
     *
     * 做法：顶边避开系统声明的状态栏/挖孔带，底边取 max(系统栏, 键盘)，用 padding 收缩整列。
     * `TerminalView` 在 `onSizeChanged` 里重算行列并给远端发 SIGWINCH —— 与 Android 13 上
     * 系统替我们 resize 时走的是同一条路，所以 PTY 会跟着键盘走（发现 2 随之消失）。
     *
     * 顶边取 `systemBars | displayCutout` 的并集而不是只取状态栏：Android 13 上窗口本身
     * 就摆在挖孔带之下（frame `[0,88]`），窗口内的挖孔 inset 是 0，不会重复让位；
     * Android 16 上窗口盖住了挖孔（frame `[0,0]`），这时它才是 128 —— 正好补上发现 4。
     *
     * API < 30 不接管：那里 `adjustResize` 本来有效，而且没有 `WindowInsets.Type`。
     */
    private fun applyInsetsHandling(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val top = bars.top
            val bottom = maxOf(bars.bottom, ime.bottom)
            if (v.paddingTop != top || v.paddingBottom != bottom) {
                v.setPadding(0, top, 0, bottom)
                DiagLog.i(
                    TAG,
                    "insets: padTop=$top padBottom=$bottom " +
                        "(bars=${bars.top}/${bars.bottom} cutout=${insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top} " +
                        "ime=${ime.bottom} density=${resources.displayMetrics.density})"
                )
                // 布局要等这一帧走完才生效，所以尺寸另发一条 —— 真机取证时直接看这两行。
                v.post {
                    val loc = IntArray(2)
                    terminalView?.getLocationOnScreen(loc)
                    DiagLog.i(
                        TAG,
                        "layout: root=${v.width}x${v.height} pad=$top/$bottom " +
                            "termTop=${loc[1]} termH=${terminalView?.height} " +
                            "extraKeysH=${extraKeysView?.height}"
                    )
                }
            }
            // 内容区由我们独占计算，不再往下传（子 View 不需要、也不该再各让一次）。
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * 终端模式入口：先做不阻塞的校验，再把**会 fork 进程的部分**挪到后台线程。
     *
     * 私钥分支要跑 dropbearkey（`-t ed25519` 生成密钥，`-y` 读公钥），两处都要
     * `waitFor()` / 读子进程输出 —— 放在主线程就是阻塞 I/O，首次用私钥登录时最明显。
     * 密码分支不 fork，直接继续。
     */
    private fun startDbclient() {
        val libDir = applicationInfo.nativeLibraryDir
        val dbclient = File(libDir, "libdbclient.so")
        if (!dbclient.exists()) {
            showStatus("dbclient 缺失（APK 未包含？）\n$libDir")
            return
        }

        val cfg = SshConfig.load(getSharedPreferences("dsh-handheld", MODE_PRIVATE))
        if (cfg == null || !cfg.isComplete) {
            showStatus("未配置 SSH，请先回连接屏填写")
            return
        }

        // TERM 必须传：TerminalSession 用给定 env 启动 dbclient（不继承父进程），
        // 缺少 TERM 时远端 bash 的 tput setaf 探测失败 → color_prompt=no → 无颜色。
        // xterm-256color 让远端提示符 / ls / dircolors 全部恢复彩色。
        // HOME 指向 filesDir：dbclient 在此写 known_hosts/.ssh（避免落到 /data/.ssh 报权限），
        // 且与 SshTunnel 用同一份，TOFU 信任不分裂。
        val baseEnv = arrayOf("HOME=${filesDir.absolutePath}", "TERM=xterm-256color")

        if (cfg.authType == SshConfig.AUTH_KEY) {
            // 认证方式：-i 导入的私钥；缺私钥时尝试自动生成（dropbearkey）。
            showStatus("准备 SSH 密钥…")
            Thread {
                val keyPath = resolveKeyPath()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (keyPath == null) {
                        // 可点重连：导入私钥后回这一屏不用退出重进（审计 M5）
                        showStatus("SSH 密钥不可用，请回连接屏导入私钥", retryable = true)
                    } else {
                        launchSession(dbclient, cfg, baseEnv, arrayOf("-i", keyPath))
                    }
                }
            }.apply { isDaemon = true; name = "ssh-key-resolve"; start() }
        } else {
            // 密码（默认）：DROPBEAR_PASSWORD 环境变量（补丁后 getpass 不再需要，
            // 与 Termux 原版 ssh 体验一致）。
            launchSession(dbclient, cfg, baseEnv + "DROPBEAR_PASSWORD=${cfg.password}", arrayOf())
        }
    }

    /**
     * 构造 dbclient 参数并让 TerminalSession 启动它（主线程）。
     *  - dbclient 二进制：nativeLibraryDir（APK 内 libdbclient.so，extractNativeLibs
     *    时系统会解压到该目录且可执行）。
     *  - argv[0] = 程序名（JNI execvp 约定）。
     */
    private fun launchSession(
        dbclient: File, cfg: SshConfig, env: Array<String>, keyArgs: Array<String>
    ) {
        val args = arrayOf(
            dbclient.absolutePath,
            "-p", cfg.port.toString(),
            "-y",                 // 首连接受未知主机公钥（TOFU，与现有行为一致）
            "-t",                 // 分配 pty（交互终端）
            *keyArgs,
            "${cfg.user}@${cfg.host}"
        )
        // 只记 env 的**键名**，不记值：DROPBEAR_PASSWORD 的值就是登录密码。
        // （原写法 `it.take(8)` 恰好只截到 "DROPBEAR" 这个键名而侥幸没泄漏密码，
        //  但缩短键名/换认证方式就会漏 —— 不能靠运气。）
        DiagLog.i(TAG, "dbclient: args=${args.toList()} envKeys=${env.map { it.substringBefore('=') }}")

        showStatus("连接 ${cfg.user}@${cfg.host}:${cfg.port} …")
        val client = object : TerminalSessionClient {
            // Termux 同款：新数据到达必须 onScreenUpdated() → invalidate() + 滚回底部，
            // 否则字节进了 emulator 但没人重绘，只能等光标闪烁（500ms）时机性刷新 → 打字卡顿。
            override fun onTextChanged(changedSession: TerminalSession) {
                if (session === changedSession) terminalView?.onScreenUpdated()
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                // 可点重连（审计 M5）：原先这条提示**永驻**、且整个 Activity 没有任何重连入口
                // —— 终端已死，用户只能按返回键退出重进。慢速链路下 vim 之类的退出也算一次
                // 「会话结束」，重连按钮在这里是刚需。
                runOnUiThread { showStatus("SSH 会话结束", retryable = true) }
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                // Termux 同款：长按选择 → 复制菜单 → 写入系统剪贴板。
                // 空实现会让菜单看起来可用但复制无效果（实测 UI 死交互）。
                if (text.isEmpty()) return
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip ?: return
                val paste = clip.getItemAt(0).coerceToText(this@TuiActivity).toString()
                if (paste.isNotEmpty()) session?.emulator?.paste(paste)
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            // Termux 同款（审计 L4）：远端程序 DECSET/DECRST 25（隐藏/恢复光标）时，
            // TerminalView 的 KDoc 明确要求在这个回调里重启闪烁 —— 而 setTerminalCursorBlinkerState
            // 一进来就把「闪烁」关掉，只在光标不可见时提前 return。空实现的结果是：
            // 远端隐藏过光标、又经历一次后台↔前台，此后光标常亮不闪，直到下一次尺寸变化。
            override fun onTerminalCursorStateChange(state: Boolean) {
                if (state) startCursorBlinker()
            }
            override fun getTerminalCursorStyle(): Int = 0
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        }
        val s = TerminalSession(
            dbclient.absolutePath, "/", args, env, 5000, client
        )
        session = s
        terminalView?.attachSession(s)
        clearStatus()

        // Termux 同款启动逻辑：聚焦终端 + 延迟拉起软键盘（等窗口/焦点稳定）。
        // attachSession 后 TerminalView 会经 updateSize() 拿到实际尺寸并回调
        // onEmulatorSet() → startCursorBlinker()（若已就绪届时即闪）。
        terminalView?.requestFocus()
        terminalView?.postDelayed({ showSoftKeyboard() }, 300)
        DiagLog.i(TAG, "dbclient started via TerminalSession")
    }

    /**
     * 显示覆盖整屏的状态提示。
     *
     * `retryable = true` 时点它一下就能重新发起会话（审计 M5：会话结束/失败的提示原先永驻，
     * 而整个 Activity 没有任何重连路径 —— 只能退出重进）。
     */
    private fun showStatus(text: String, retryable: Boolean = false) {
        val v = statusView ?: return
        v.text = if (retryable) "$text\n\n（点这里重连）" else text
        v.visibility = View.VISIBLE
        v.isClickable = retryable
        v.setOnClickListener(if (retryable) View.OnClickListener { retrySession() } else null)
    }

    /** 收起提示（连上以后）。 */
    private fun clearStatus() {
        statusView?.apply {
            visibility = View.GONE
            isClickable = false
            setOnClickListener(null)
        }
    }

    /** 丢掉旧会话再走一遍启动流程（[showStatus] 的可点重连）。 */
    private fun retrySession() {
        DiagLog.i(TAG, "retrySession: 用户点了重连")
        session?.let { runCatching { it.finishIfRunning() } }
        session = null
        terminalView?.let { runCatching { it.onScreenUpdated() } }
        clearStatus()
        startDbclient()
    }

    /**
     * 返回可用的私钥路径；若无导入密钥则用 dropbearkey 生成（并提示公钥）。
     *
     * ⚠️ **必须在后台线程调用**：内部两处 fork 子进程（生成密钥、读公钥），
     * 并用 `waitFor()` / `readText()` 等它跑完 —— 在主线程上是阻塞 I/O。
     * 调用点见 [startDbclient] 的私钥分支。
     */
    private fun resolveKeyPath(): String? {
        val libDir = applicationInfo.nativeLibraryDir
        // 1) 连接屏导入的私钥
        val imported = SshConfig.load(getSharedPreferences("dsh-handheld", MODE_PRIVATE))?.keyPath
        if (!imported.isNullOrBlank() && File(imported).exists()) return imported

        // 2) 自动生成一对（app 私有目录），公钥一行提示加到服务端
        val dir = filesDir
        val key = File(dir, KEY_NAME)
        if (!key.exists()) {
            val dbkey = File(libDir, "libdropbearkey.so")
            if (!dbkey.exists()) return null
            // 整段兜住（审计 M8）：`start()` 在文件在但不可执行/SELinux 拒绝时抛 IOException，
            // 而这是**后台线程**——未捕获异常会让 Android 直接杀掉整个进程（不是弹个提示）。
            // 同时 waitFor 必须带超时：dropbearkey 挂住时原先会永久停在「准备 SSH 密钥…」。
            val ok = try {
                val gen = ProcessBuilder(dbkey.absolutePath, "-t", "ed25519", "-f", key.absolutePath)
                    .redirectErrorStream(true).start()
                if (!gen.waitFor(KEYGEN_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)) {
                    DiagLog.e(TAG, "dropbearkey 超时（${KEYGEN_TIMEOUT_SEC}s），杀掉它")
                    gen.destroy()
                    if (!gen.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) gen.destroyForcibly()
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                DiagLog.e(TAG, "dropbearkey 起不来/被中断：${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!ok || !key.exists()) { DiagLog.e(TAG, "dropbearkey failed"); return null }
            DiagLog.i(TAG, "generated key at ${key.absolutePath}")
        }
        // 输出公钥到日志（方便用户加到服务端 authorized_keys）
        val dbkey = File(libDir, "libdropbearkey.so")
        if (dbkey.exists()) {
            try {
                val pub = ProcessBuilder(dbkey.absolutePath, "-y", "-f", key.absolutePath)
                    .redirectErrorStream(true).start()
                val text = pub.inputStream.bufferedReader().readText().trim()
                DiagLog.i(TAG, "PUBKEY (add to server ~/.ssh/authorized_keys): $text")
            } catch (_: Exception) {}
        }
        return key.absolutePath
    }

    /**
     * 底部常驻快捷键栏 —— Termux 官方 ExtraKeysView（原样移植）：
     *  - 布局 = Termux 默认 extra-keys（ESC / - | 方向键 HOME END PGUP 等）
     *    + KEYBOARD（收/呼软键盘）
     *  - 长按方向键自动重复、向上滑弹出 popup、CTRL/ALT 长按锁定 —— 全部 Termux
     *    原生行为（ExtraKeysView 自带）
     *  - 客户端 DshTerminalExtraKeys：拦截 KEYBOARD，其余走 Termux 派发器
     */
    private fun buildExtraKeys(): View {
        val extras = ExtraKeysView(this, null).apply {
            setButtonColors(COL_TEXT, COL_TEXT_ACTIVE, 0x1AFFFFFF, 0xFF4A4A55.toInt())
            setButtonTextAllCaps(false)
            setExtraKeysViewClient(
                DshTerminalExtraKeys(terminalView!!, onToggleKeyboard = { toggleSoftKeyboard() })
            )
            try {
                reload(ExtraKeysInfo(EXTRA_KEYS_LAYOUT, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES))
            } catch (e: Exception) {
                DiagLog.e(TAG, "extra keys layout failed", e)
            }
        }
        extraKeysView = extras
        // 键排高度：每行 25dp（两行共 50dp；⌨ 跨两行 = 50dp ≈ 最小可点击目标 48dp）。
        // ⚠️ 2026-09-22 订正：这里原写「随 windowSoftInputMode=adjustResize 软键盘弹出时
        // 窗口收缩、键排自动顶到键盘上方 —— 无需额外处理」。targetSdk 35 起这句不成立：
        // Android 15+ 强制 edge-to-edge，系统不再让位，键盘会整条盖住键排（发现 1）。
        // 现在由 applyInsetsHandling() 把键盘高度算进内容区，键排自然落在键盘上沿。
        val h = (50 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            setBackgroundColor(0xFF16161A.toInt())
            addView(extras, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
    }

    /** 读取 ExtraKeysView 修饰键状态（Termux 同款：短按单次生效，长按锁定）。 */
    private fun readModifier(button: SpecialButton): Boolean =
        extraKeysView?.readSpecialButton(button, true) == true

    /** ⌨ 键：收/呼软键盘（Termux KeyboardUtils.toggleSoftKeyboard 同款）。
     * 用 IMM.toggleSoftInput(SHOW_FORCED, 0)：系统自判当前状态并切换，
     * 无需读取 isAcceptingText（焦点在 TerminalView 时恒为 true，会误判）。 */
    private fun toggleSoftKeyboard() {
        val tv = terminalView ?: return
        tv.requestFocus()
        tv.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }, 60)
    }

    /**
     * 显式拉起软键盘（Termux KeyboardUtils.showSoftKeyboard 同款）。
     * TerminalView 是 text editor（onCheckIsTextEditor()=true），但仅 requestFocus
     * 不会强制弹出 —— 多数输入法（Gboard/三星）需要 showSoftInput 触发。
     * 幂等：已显示时系统自行忽略；未聚焦时系统忽略（调用方需已 requestFocus）。
     */
    private fun showSoftKeyboard() {
        val tv = terminalView ?: return
        tv.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
            imm.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
            DiagLog.i(TAG, "showSoftInput requested (focus=${tv.hasFocus()})")
        }, 120)
    }

    /**
     * 启动光标闪烁。Termux 同款：TerminalView 默认 blink rate=0（禁用），
     * 必须先 setTerminalCursorBlinkerRate() 再 setTerminalCursorBlinkerState(true,true)
     * 才会开始闪；onEmulatorSet / onResume 各调一次（幂等）。
     */
    private fun startCursorBlinker() {
        val tv = terminalView ?: return
        if (tv.setTerminalCursorBlinkerRate(500))
            tv.setTerminalCursorBlinkerState(true, true)
        else
            DiagLog.w(TAG, "cursor blink rate rejected")
    }

    private fun stopCursorBlinker() {
        terminalView?.setTerminalCursorBlinkerState(false, true)
    }

    /**
     * 终端模式也算「App 在前台」（审计 M17）。
     *
     * 前台计数（[DshApp.onActivityStarted]）原先只有 MainActivity 参与，而它判的是
     * 「页面报告任务完成时要不要弹通知」—— 用户在终端里时 App 明明在前台，
     * 却会被当成「不在前台」而弹一条通知，与文档写的「App 在前台时不打扰」矛盾。
     */
    override fun onStart() {
        super.onStart()
        (application as? DshApp)?.onActivityStarted()
    }

    override fun onStop() {
        (application as? DshApp)?.onActivityStopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        startCursorBlinker()
        // 从后台回来（如切输入法设置）时恢复软键盘
        terminalView?.postDelayed({
            terminalView?.requestFocus()
            showSoftKeyboard()
        }, 200)
    }

    override fun onPause() {
        stopCursorBlinker()
        super.onPause()
    }

    override fun onDestroy() {
        session?.let { runCatching { it.finishIfRunning() } }
        session = null
        super.onDestroy()
    }
}
