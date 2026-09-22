package dsh.conformance;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable, implementation-agnostic snapshot of a terminal screen.
 *
 * <p>This is the comparison unit of the conformance harness: two emulator
 * implementations are considered equivalent for a given byte stream when their
 * {@code Screen} snapshots are equal. It deliberately captures everything a user
 * can observe — per-cell character, foreground/background colour, attributes,
 * cursor position, alternate-screen state and title — so that a difference can
 * never hide in state the renderer would show but a text-only dump would miss.</p>
 */
public final class Screen {

    /**
     * Foreground index meaning "the terminal's configured default foreground".
     *
     * <p>Values {@code 0..255} are palette indices; anything at or above these
     * sentinels is not a palette colour at all. The numbers are the reference
     * implementation's own encoding, adopted here as the harness contract so both
     * sides speak one language: an implementation under test must map its internal
     * default-colour state onto these sentinels, exactly as it must map an SGR 31
     * onto palette index 1.</p>
     */
    public static final int COLOR_DEFAULT_FOREGROUND = 256;
    /** Background counterpart of {@link #COLOR_DEFAULT_FOREGROUND}. */
    public static final int COLOR_DEFAULT_BACKGROUND = 257;

    /** One character cell. */
    public static final class Cell {
        /** The UTF-16 unit stored for this column (a wide char's trailer column is a filler). */
        public final char text;
        /** Foreground colour index as the emulator reports it. */
        public final int fg;
        /** Background colour index as the emulator reports it. */
        public final int bg;
        /** Packed attribute bits (bold / italic / underline / inverse / ...). */
        public final int effect;

        public Cell(char text, int fg, int bg, int effect) {
            this.text = text;
            this.fg = fg;
            this.bg = bg;
            this.effect = effect;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Cell)) {
                return false;
            }
            Cell c = (Cell) other;
            return text == c.text && fg == c.fg && bg == c.bg && effect == c.effect;
        }

        @Override
        public int hashCode() {
            return (text * 31 + fg) * 31 + bg;
        }
    }

    public final int rows;
    public final int columns;
    public final Cell[][] cells;
    public final int cursorRow;
    public final int cursorColumn;
    public final boolean alternateBuffer;
    public final String title;
    /**
     * Per-row "this row continues onto the next one" flags.
     *
     * <p>Not cosmetic: a terminal records that a row was produced by wrapping rather
     * than by a newline, and uses it to reflow text when the window is resized and to
     * decide what a selection contains. Two emulators that agree on every visible
     * character but disagree here will diverge the moment the user rotates the phone,
     * so it belongs in the snapshot.</p>
     */
    public final boolean[] lineWrap;

    public Screen(int rows, int columns, Cell[][] cells, int cursorRow, int cursorColumn,
                  boolean alternateBuffer, String title) {
        this(rows, columns, cells, cursorRow, cursorColumn, alternateBuffer, title, new boolean[rows]);
    }

    public Screen(int rows, int columns, Cell[][] cells, int cursorRow, int cursorColumn,
                  boolean alternateBuffer, String title, boolean[] lineWrap) {
        this.rows = rows;
        this.columns = columns;
        this.cells = cells;
        this.cursorRow = cursorRow;
        this.cursorColumn = cursorColumn;
        this.alternateBuffer = alternateBuffer;
        this.title = title == null ? "" : title;
        this.lineWrap = lineWrap == null ? new boolean[rows] : lineWrap;
    }

    /**
     * Render a deterministic, diff-friendly text form of this screen.
     *
     * <p>Each row emits its style runs, so a colour or attribute difference is
     * visible in the diff text rather than hidden behind identical characters.</p>
     *
     * @return the canonical dump, newline separated.
     */
    public String dump() {
        StringBuilder out = new StringBuilder();
        out.append("size=").append(rows).append('x').append(columns)
                .append(" cursor=").append(cursorRow).append(',').append(cursorColumn)
                .append(" alt=").append(alternateBuffer)
                .append(" title=").append(quote(title))
                .append(" wrap=").append(wrapFlags())
                .append('\n');
        for (int row = 0; row < rows; row++) {
            out.append(String.format("%3d |", row));
            Cell[] line = cells[row];
            int column = 0;
            while (column < columns) {
                Cell start = line[column];
                int end = column;
                StringBuilder text = new StringBuilder();
                while (end < columns && line[end].fg == start.fg && line[end].bg == start.bg
                        && line[end].effect == start.effect) {
                    text.append(line[end].text);
                    end++;
                }
                out.append(' ').append(style(start)).append(' ')
                        .append(quote(text.toString().stripTrailing()));
                column = end;
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Human-readable form of one run's style, naming the default-colour sentinels. */
    private static String style(Cell cell) {
        return String.format("fg=%s bg=%s%s", color(cell.fg, COLOR_DEFAULT_FOREGROUND),
                color(cell.bg, COLOR_DEFAULT_BACKGROUND), attributes(cell.effect));
    }

    private static String color(int index, int defaultIndex) {
        return index == defaultIndex ? "def" : Integer.toString(index);
    }

    private static String attributes(int effect) {
        if (effect == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder(" attrs=");
        if ((effect & 1) != 0) {
            out.append("bold,");
        }
        if ((effect & 2) != 0) {
            out.append("italic,");
        }
        if ((effect & 4) != 0) {
            out.append("underline,");
        }
        if ((effect & 8) != 0) {
            out.append("blink,");
        }
        if ((effect & 16) != 0) {
            out.append("inverse,");
        }
        if ((effect & 32) != 0) {
            out.append("invisible,");
        }
        if ((effect & 64) != 0) {
            out.append("strike,");
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    /**
     * Render this screen with ANSI colours, for eyeballing a real program's output.
     *
     * <p>Style escapes are emitted only when the style changes, so the result is
     * what a terminal would actually receive rather than one sequence per cell.</p>
     *
     * @return printable rows, ANSI-escaped.
     */
    public String render() {
        List<String> lines = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            StringBuilder line = new StringBuilder();
            int currentFg = Integer.MIN_VALUE;
            int currentBg = Integer.MIN_VALUE;
            int currentEffect = Integer.MIN_VALUE;
            for (int column = 0; column < columns; column++) {
                Cell cell = cells[row][column];
                if (cell.fg != currentFg || cell.bg != currentBg || cell.effect != currentEffect) {
                    line.append("\u001b[0");
                    appendSgrColor(line, cell.fg, COLOR_DEFAULT_FOREGROUND, 38, 39);
                    appendSgrColor(line, cell.bg, COLOR_DEFAULT_BACKGROUND, 48, 49);
                    appendSgrAttributes(line, cell.effect);
                    line.append('m');
                    currentFg = cell.fg;
                    currentBg = cell.bg;
                    currentEffect = cell.effect;
                }
                line.append(cell.text);
            }
            lines.add(line.append("\u001b[0m").toString().stripTrailing());
        }
        return String.join("\n", lines);
    }

    private static void appendSgrColor(StringBuilder out, int index, int defaultIndex, int base, int reset) {
        if (index == defaultIndex) {
            out.append(';').append(reset);
        } else {
            out.append(';').append(base).append(";5;").append(index);
        }
    }

    private static void appendSgrAttributes(StringBuilder out, int effect) {
        if ((effect & 1) != 0) {
            out.append(";1");
        }
        if ((effect & 2) != 0) {
            out.append(";3");
        }
        if ((effect & 4) != 0) {
            out.append(";4");
        }
        if ((effect & 8) != 0) {
            out.append(";5");
        }
        if ((effect & 16) != 0) {
            out.append(";7");
        }
        if ((effect & 32) != 0) {
            out.append(";8");
        }
        if ((effect & 64) != 0) {
            out.append(";9");
        }
    }

    /** Compare against another snapshot. */
    public boolean sameAs(Screen other) {
        if (other == null || rows != other.rows || columns != other.columns
                || cursorRow != other.cursorRow || cursorColumn != other.cursorColumn
                || alternateBuffer != other.alternateBuffer || !title.equals(other.title)) {
            return false;
        }
        for (int row = 0; row < rows; row++) {
            if (lineWrap[row] != other.lineWrap[row]) {
                return false;
            }
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!cells[row][column].equals(other.cells[row][column])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Describe the first differing cell, for a useful failure message.
     *
     * @param other the snapshot to compare with.
     * @return a human-readable difference summary.
     */
    public String diff(Screen other) {
        if (other == null) {
            return "other snapshot is null";
        }
        StringBuilder out = new StringBuilder();
        if (rows != other.rows || columns != other.columns) {
            out.append("geometry ").append(rows).append('x').append(columns)
                    .append(" vs ").append(other.rows).append('x').append(other.columns).append('\n');
        }
        if (cursorRow != other.cursorRow || cursorColumn != other.cursorColumn) {
            out.append("cursor ").append(cursorRow).append(',').append(cursorColumn)
                    .append(" vs ").append(other.cursorRow).append(',').append(other.cursorColumn).append('\n');
        }
        if (alternateBuffer != other.alternateBuffer) {
            out.append("alternate ").append(alternateBuffer).append(" vs ").append(other.alternateBuffer).append('\n');
        }
        if (!title.equals(other.title)) {
            out.append("title ").append(quote(title)).append(" vs ").append(quote(other.title)).append('\n');
        }
        for (int row = 0; row < Math.min(rows, other.lineWrap.length); row++) {
            if (lineWrap[row] != other.lineWrap[row]) {
                out.append("lineWrap[").append(row).append("] ").append(lineWrap[row])
                        .append(" vs ").append(other.lineWrap[row]).append('\n');
            }
        }
        int shown = 0;
        for (int row = 0; row < Math.min(rows, other.rows) && shown < 8; row++) {
            for (int column = 0; column < Math.min(columns, other.columns) && shown < 8; column++) {
                Cell a = cells[row][column];
                Cell b = other.cells[row][column];
                if (!a.equals(b)) {
                    out.append(String.format("cell(%d,%d): char %s/%s fg %d/%d bg %d/%d e %d/%d%n",
                            row, column, quote(String.valueOf(a.text)), quote(String.valueOf(b.text)),
                            a.fg, b.fg, a.bg, b.bg, a.effect, b.effect));
                    shown++;
                }
            }
        }
        return out.toString();
    }

    /** Compact rendering of the wrap flags, e.g. {@code "........X.."} (X = wraps). */
    public String wrapFlags() {
        StringBuilder out = new StringBuilder(rows);
        for (boolean flag : lineWrap) {
            out.append(flag ? 'W' : '.');
        }
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c == 0x7F) {
                out.append(String.format("\\x%02x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
