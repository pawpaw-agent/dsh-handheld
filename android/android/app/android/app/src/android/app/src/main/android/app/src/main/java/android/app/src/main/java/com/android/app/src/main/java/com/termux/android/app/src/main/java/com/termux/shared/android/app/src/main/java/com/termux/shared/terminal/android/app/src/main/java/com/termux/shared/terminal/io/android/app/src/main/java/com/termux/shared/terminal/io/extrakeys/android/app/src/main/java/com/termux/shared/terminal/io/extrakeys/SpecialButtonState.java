/*
 * Derived from Termux app v0.118.1 (GPL-3.0),
 * github.com/termux/termux-app - termux-shared:terminal/io/extrakeys.
 *
 * Vendored VERBATIM (only this header added; package and body unchanged).
 *
 * 注意：本文件与 JitPack 依赖 `terminal-view` 内的同名类**同包同名**，编译时源码
 * 优先于 jar，因此这里的副本会永久覆盖依赖里的版本。升级 terminal-view 时，
 * 未被 vendored 的类会更新，而本文件**不会** —— 上游修复无法自动到达。
 */
package com.termux.shared.terminal.io.extrakeys;

import android.widget.Button;

import java.util.ArrayList;
import java.util.List;

/** The {@link Class} that maintains a state of a {@link SpecialButton} */
public class SpecialButtonState {

    /** If special button has been created for the {@link ExtraKeysView}. */
    boolean isCreated = false;
    /** If special button is active. */
    boolean isActive = false;
    /** If special button is locked due to long hold on it and should not be deactivated if its
     * state is read. */
    boolean isLocked = false;

    List<Button> buttons = new ArrayList<>();

    ExtraKeysView mExtraKeysView;

    /**
     * Initialize a {@link SpecialButtonState} to maintain state of a {@link SpecialButton}.
     *
     * @param extraKeysView The {@link ExtraKeysView} instance in which the {@link SpecialButton}
     *                      is to be registered.
     */
    public SpecialButtonState(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    /** Set {@link #isCreated}. */
    public void setIsCreated(boolean value) {
        isCreated = value;
    }

    /** Set {@link #isActive}. */
    public void setIsActive(boolean value) {
        isActive = value;
        buttons.forEach(button -> button.setTextColor(value ? mExtraKeysView.getButtonActiveTextColor() : mExtraKeysView.getButtonTextColor()));
    }

    /** Set {@link #isLocked}. */
    public void setIsLocked(boolean value) {
        isLocked = value;
    }

}
