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

import androidx.annotation.NonNull;

import java.util.HashMap;

/** The {@link Class} that implements special buttons for {@link ExtraKeysView}. */
public class SpecialButton {

    private static final HashMap<String, SpecialButton> map = new HashMap<>();

    public static final SpecialButton CTRL = new SpecialButton("CTRL");
    public static final SpecialButton ALT = new SpecialButton("ALT");
    public static final SpecialButton SHIFT = new SpecialButton("SHIFT");
    public static final SpecialButton FN = new SpecialButton("FN");

    /** The special button key. */
    private final String key;

    /**
     * Initialize a {@link SpecialButton}.
     *
     * @param key The unique key name for the special button. The key is registered in {@link #map}
     *            with which the {@link SpecialButton} can be retrieved via a call to
     *            {@link #valueOf(String)}.
     */
    public SpecialButton(@NonNull final String key) {
        this.key = key;
        map.put(key, this);
    }

    /** Get {@link #key} for this {@link SpecialButton}. */
    public String getKey() {
        return key;
    }

    /**
     * Get the {@link SpecialButton} for {@code key}.
     *
     * @param key The unique key name for the special button.
     */
    public static SpecialButton valueOf(String key) {
        return map.get(key);
    }

    @NonNull
    @Override
    public String toString() {
        return key;
    }

}
