package com.dshhandheld.app

import android.view.View
import com.termux.shared.terminal.io.TerminalExtraKeys
import com.termux.view.TerminalView

/**
 * dsh-handheld 版 [TerminalExtraKeys]：复用 Termux 官方 extra-keys 派发，
 * 仅拦截非终端键：
 *  - KEYBOARD —— 收/呼软键盘（Termux 同款行为）
 * 其余键（ESC/TAB/CTRL/ALT/方向键/HOME/END/PGUP/PGDN/普通字符）交给
 * Termux 原实现（KeyEvent → TerminalView.onKeyDown / inputCodePoint，
 * CTRL/ALT 状态经 readControlKey/readAltKey 从 ExtraKeysView 读取）。
 */
class DshTerminalExtraKeys(
    terminalView: TerminalView,
    private val onToggleKeyboard: () -> Unit
) : TerminalExtraKeys(terminalView) {

    override fun onTerminalExtraKeyButtonClick(
        view: View, key: String,
        ctrlDown: Boolean, altDown: Boolean, shiftDown: Boolean, fnDown: Boolean
    ) {
        when (key) {
            "KEYBOARD" -> onToggleKeyboard()
            else -> super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
        }
    }
}
