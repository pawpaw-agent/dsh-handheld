package dsh.conformance;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a corpus case actually exercises.
 *
 * <p>A conformance suite that passes because it tests nothing is worse than no
 * suite, so every case reports the capabilities it covers and the harness fails
 * when a capability has no coverage at all. Two kinds of evidence are reported,
 * and they mean different things:</p>
 *
 * <ul>
 *   <li><b>input</b> — the byte stream contains the relevant sequence. This is
 *       exact: it says the corpus exercises the feature, not that any emulator
 *       handled it.</li>
 *   <li><b>screen</b> — the effect is visible in the final screen snapshot. This
 *       is observable state, and it is what a differential comparison actually
 *       compares.</li>
 * </ul>
 *
 * <p>A feature listed under neither is untested. A feature listed under input but
 * not screen still has discriminating power (the two implementations must agree on
 * the resulting screen, including "nothing changed"), but a mismatch is less
 * likely to be caught, so the report keeps them apart.</p>
 */
public final class Coverage {

    /** Capability tags the harness expects the corpus to cover. */
    public static final Set<String> REQUIRED = new LinkedHashSet<>(java.util.Arrays.asList(
            "text", "colour", "colour-extended", "attr-bold", "attr-underline", "attr-inverse",
            "attr-other", "cursor-moved", "erase", "insert-delete", "scroll-region",
            "alt-screen", "charset-graphics", "wrap", "tabs", "wide-chars", "combining",
            "box-drawing", "osc-title", "scrollback", "modes", "cr-overwrite"));

    private Coverage() {
    }

    /**
     * Classify one case.
     *
     * @param input  the exact bytes fed to the terminal.
     * @param screen the resulting screen snapshot.
     * @return capability tags, each prefixed {@code in:} or {@code screen:}.
     */
    public static Set<String> of(byte[] input, Screen screen) {
        // Two decodings, on purpose. Escape-sequence scanning is byte-oriented and
        // must never be confused by multi-byte text, so it uses ISO-8859-1 (one char
        // per byte). Character-level questions (box drawing, combining marks) are
        // about text, so they use UTF-8. Using one decoding for both silently missed
        // every non-ASCII feature: the U+2500 and U+0300 ranges cannot appear when
        // each UTF-8 byte is mapped to its own Latin-1 character.
        String text = new String(input, StandardCharsets.ISO_8859_1);
        String utf8 = new String(input, StandardCharsets.UTF_8);
        Set<String> tags = new LinkedHashSet<>();

        // ── input-side: which sequences the case contains ──────────────────
        boolean hasText = false;
        for (byte b : input) {
            if (b >= 0x20 && b != 0x7F) {
                hasText = true;
                break;
            }
        }
        if (hasText) {
            tags.add("in:text");
        }
        if (text.contains("\u001b[") && text.matches("(?s).*\\u001b\\[[0-9;]*3[0-8].*")) {
            tags.add("in:colour");
        }
        if (text.contains("38;5;") || text.contains("48;5;") || text.contains("38;2;") || text.contains("48;2;")) {
            tags.add("in:colour-extended");
        }
        if (text.contains("[1m") || text.contains(";1m") || text.contains(";1;")) {
            tags.add("in:attr-bold");
        }
        if (text.contains("[4m") || text.contains(";4m") || text.contains(";4;")) {
            tags.add("in:attr-underline");
        }
        if (text.contains("[7m") || text.contains(";7m") || text.contains(";7;")) {
            tags.add("in:attr-inverse");
        }
        if (text.matches("(?s).*\\u001b\\[[0-9;]*[235689]m.*")) {
            tags.add("in:attr-other");
        }
        if (text.matches("(?s).*\\u001b\\[[0-9;]*[ABCDHf].*") || text.contains("[s") || text.contains("[u")) {
            tags.add("in:cursor-moved");
        }
        if (text.matches("(?s).*\\u001b\\[[0-9;]*[JKX].*")) {
            tags.add("in:erase");
        }
        if (text.matches("(?s).*\\u001b\\[[0-9;]*[@PLM].*")) {
            tags.add("in:insert-delete");
        }
        if (text.matches("(?s).*\\u001b\\[[0-9;]+;[0-9]+r.*") || text.contains("[?6h")) {
            tags.add("in:scroll-region");
        }
        if (text.contains("?1049") || text.contains("?1047") || text.contains("?47")) {
            tags.add("in:alt-screen");
        }
        if (text.contains("\u001b(0") || text.contains("\u001b)0") || text.contains("\u000e")) {
            tags.add("in:charset-graphics");
        }
        if (text.contains("?7l") || text.matches("(?s).*\\u001b\\[[0-9;]*[CHf].*")) {
            tags.add("in:wrap");
        }
        // 只认**真的用了 tab**（制表符字节，或 tab-clear/HT 相关序列）。
        // 原先还有一支 `text.contains("H") && text.contains("\u001b[")`：`H` 是 CUP
        // （`ESC[…H`）的终结字节，于是**任何**定位过光标的用例都被算成「覆盖了 tabs」
        // —— 判据恒真（2026-09-17 审计 M7）。语料里真含 `\t` 的用例有 2 个
        // （syn-controls-basic、syn-tab-stops），这条能力不会因此落空。
        if (text.contains("\t")) {
            tags.add("in:tabs");
        }
        if (text.matches("(?s).*\\u001b\\[[0-9;]*g.*") || text.contains("\u001bH")) {
            tags.add("in:tabs");
        }
        // 「宽字符」按 East Asian Width 的 W/F 判（2026-09-17 审计 M7）：原先判的是
        // 「输入里有任一字节 ≥0x80」—— 组合重音、制表符画框、Latin-1 全都算「宽」。
        // 实测 8 个命中里 5 个一个宽字符都没有（real-dpkg-list / real-htop-screen /
        // real-man-page / syn-utf8-box-drawing / syn-utf8-combining），于是把真正含
        // 宽字符的 3 个用例删掉，门禁依旧全绿 —— 这条能力等于没在守。
        if (hasWideCharacters(utf8)) {
            tags.add("in:wide-chars");
        }
        if (containsCombining(utf8)) {
            tags.add("in:combining");
        }
        if (utf8.matches("(?s).*[\u2500-\u257F\u2580-\u259F].*")) {
            tags.add("in:box-drawing");
        }
        if (text.contains("\u001b]0;") || text.contains("\u001b]2;")) {
            tags.add("in:osc-title");
        }
        if (text.contains("?1000") || text.contains("?1002") || text.contains("?1006")
                || text.contains("?2004") || text.contains("?25") || text.contains("?5")
                || text.contains("?1h")) {
            tags.add("in:modes");
        }
        if (text.contains("\r") && !text.contains("\r\n")) {
            tags.add("in:cr-overwrite");
        }
        if (input.length > 4000) {
            tags.add("in:scrollback");
        }

        // ── screen-side: what is observable in the result ──────────────────
        boolean colour = false;
        boolean extended = false;
        boolean bold = false;
        boolean underline = false;
        boolean inverse = false;
        boolean otherAttr = false;
        boolean wide = false;
        boolean box = false;
        if (screen != null) {
            for (int row = 0; row < screen.rows; row++) {
                for (int column = 0; column < screen.columns; column++) {
                    Screen.Cell cell = screen.cells[row][column];
                    if (cell.fg != Screen.COLOR_DEFAULT_FOREGROUND
                            || cell.bg != Screen.COLOR_DEFAULT_BACKGROUND) {
                        colour = true;
                        if (cell.fg > 15 && cell.fg < Screen.COLOR_DEFAULT_FOREGROUND) {
                            extended = true;
                        }
                        if (cell.bg > 15 && cell.bg < Screen.COLOR_DEFAULT_BACKGROUND) {
                            extended = true;
                        }
                    }
                    if ((cell.effect & 1) != 0) {
                        bold = true;
                    }
                    if ((cell.effect & 4) != 0) {
                        underline = true;
                    }
                    if ((cell.effect & 16) != 0) {
                        inverse = true;
                    }
                    if ((cell.effect & ~1 & ~4 & ~16) != 0) {
                        otherAttr = true;
                    }
                    char ch = cell.text;
                    // Box-drawing and block elements are narrow despite their high code
                    // points, so they must be excluded before the wide ranges.
                    boolean boxChar = (ch >= 0x2500 && ch <= 0x257F) || (ch >= 0x2580 && ch <= 0x259F);
                    if (boxChar) {
                        box = true;
                    } else if (ch >= 0x1100 && ch <= 0xD7FF || ch >= 0xF900 && ch <= 0xFAFF) {
                        wide = true;
                    }
                }
            }
            if (colour) {
                tags.add("screen:colour");
            }
            if (extended) {
                tags.add("screen:colour-extended");
            }
            if (bold) {
                tags.add("screen:attr-bold");
            }
            if (underline) {
                tags.add("screen:attr-underline");
            }
            if (inverse) {
                tags.add("screen:attr-inverse");
            }
            if (otherAttr) {
                tags.add("screen:attr-other");
            }
            if (wide) {
                tags.add("screen:wide-chars");
            }
            if (box) {
                tags.add("screen:box-drawing");
            }
            if (!screen.title.isEmpty()) {
                tags.add("screen:osc-title");
            }
            if (screen.alternateBuffer) {
                tags.add("screen:alt-screen");
            }
            if (screen.cursorRow != 0 || screen.cursorColumn != 0) {
                tags.add("screen:cursor-moved");
            }
            boolean anyText = false;
            for (int row = 0; row < screen.rows && !anyText; row++) {
                for (int column = 0; column < screen.columns; column++) {
                    char ch = screen.cells[row][column].text;
                    if (ch != ' ' && ch != 0) {
                        anyText = true;
                        break;
                    }
                }
            }
            if (anyText) {
                tags.add("screen:text");
            }
        }
        return tags;
    }

    /** Whether the text carries a Unicode combining mark (category Mn/Mc/Me, approximated by range). */
    /**
     * 是否含「宽 / 全角」字符（Unicode East Asian Width = W 或 F）。
     *
     * <p>不引第三方表，按常用区间判：CJK 与假名/谚文、全角形式、CJK 标点与兼容表意，
     * 以及 emoji（绝大多数属 W）。判宽了会退化成「有非 ASCII」——所以要排除
     * 组合重音（{@code in:combining} 单独判）与制表符画框这类**窄**字符。</p>
     */
    private static boolean hasWideCharacters(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isEastAsianWide(cp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEastAsianWide(int cp) {
        return cp >= 0x1100 && cp <= 0x115F        // Hangul Jamo（初声）
                || cp >= 0x2E80 && cp <= 0x303E    // CJK 部首补充 / 标点
                || cp >= 0x3041 && cp <= 0x33FF    // 假名 … CJK 兼容
                || cp >= 0x3400 && cp <= 0x4DBF    // CJK 扩展 A
                || cp >= 0x4E00 && cp <= 0x9FFF    // CJK 统一表意
                || cp >= 0xA000 && cp <= 0xA4CF    // 彝文
                || cp >= 0xAC00 && cp <= 0xD7A3    // Hangul 音节
                || cp >= 0xF900 && cp <= 0xFAFF    // CJK 兼容表意
                || cp >= 0xFE30 && cp <= 0xFE6F    // CJK 兼容形式
                || cp >= 0xFF00 && cp <= 0xFF60    // 全角形式
                || cp >= 0xFFE0 && cp <= 0xFFE6
                || cp >= 0x1F300 && cp <= 0x1FAFF  // emoji / 符号
                || cp >= 0x20000 && cp <= 0x3FFFD; // CJK 扩展 B 及以后
    }

    private static boolean containsCombining(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x0300 && ch <= 0x036F      // combining diacritical marks
                    || ch >= 0x1AB0 && ch <= 0x1AFF
                    || ch >= 0x20D0 && ch <= 0x20FF
                    || ch >= 0xFE20 && ch <= 0xFE2F) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reduce a tag set to bare capability names, ignoring the input/screen split.
     *
     * @param tags tags as returned by {@link #of}.
     * @return capability names without their evidence prefix.
     */
    public static Set<String> capabilities(Set<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        for (String tag : tags) {
            out.add(tag.substring(tag.indexOf(':') + 1));
        }
        return out;
    }
}
