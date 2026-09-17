package com.termux.terminal;

import dsh.conformance.Screen;
import dsh.conformance.TerminalUnderTest;

/**
 * Conformance-harness adapter for the reference (Termux) emulator.
 *
 * <p>Lives in {@code com.termux.terminal} on purpose: reading the screen requires
 * the package-private {@code TerminalBuffer.mLines}, and a conformance harness is
 * exactly the place where reaching for the reference implementation's internals is
 * legitimate — the alternative (text-only reads) would silently miss colour and
 * attribute differences, which is where terminal bugs actually hide.</p>
 *
 * <p>This class never feeds anything back into the emulator except the bytes the
 * caller supplies, so the reference behaves exactly as it does in production.</p>
 */
public final class TermuxOracle implements TerminalUnderTest {

    private final int rows;
    private final int columns;
    private final Integer transcriptRows;

    private TerminalEmulator emulator;

    public TermuxOracle(int rows, int columns, Integer transcriptRows) {
        this.rows = rows;
        this.columns = columns;
        this.transcriptRows = transcriptRows;
        this.emulator = newEmulator();
    }

    @Override
    public String name() {
        return "termux";
    }

    private TerminalEmulator newEmulator() {
        TerminalOutput output = new TerminalOutput() {
            @Override
            public void write(byte[] data, int offset, int count) {
                // The harness never sends input, so this cannot be reached; the
                // emulator would otherwise hand us bytes destined for the pty.
            }

            @Override
            public void titleChanged(String oldTitle, String newTitle) {
            }

            @Override
            public void onCopyTextToClipboard(String text) {
            }

            @Override
            public void onPasteTextFromClipboard() {
            }

            @Override
            public void onBell() {
            }

            @Override
            public void onColorsChanged() {
            }
        };
        TerminalSessionClient client = new NoopClient();
        // Signature is taken from the 0.118.1 artifact (javap), not from the
        // repository's master branch: master has since grown a cell-size-aware
        // constructor, so the two disagree.
        return new TerminalEmulator(output, columns, rows, transcriptRows, client);
    }

    @Override
    public void feed(byte[] data, int offset, int length) {
        if (offset == 0) {
            emulator.append(data, length);
            return;
        }
        // TerminalEmulator.append takes (buffer, length) with no offset, so a
        // non-zero offset needs its own array. Keeping the common offset==0 path
        // allocation-free matters: the harness feeds thousands of slices.
        byte[] slice = new byte[length];
        System.arraycopy(data, offset, slice, 0, length);
        emulator.append(slice, length);
    }

    @Override
    public void resize(int newRows, int newColumns) {
        emulator.resize(newColumns, newRows);
    }

    @Override
    public void reset() {
        emulator = newEmulator();
    }

    @Override
    public Screen snapshot() {
        TerminalBuffer buffer = emulator.getScreen();
        // 尺寸从**当前** buffer 读，不能再用构造时的 rows/columns（审计 L12）：
        // resize() 之后 screen 会换成新的 TerminalBuffer，行列数都变了；照旧尺寸读会越界
        // 或者截掉一部分屏幕，而门禁最该覆盖的正是「resize 之后对不对」。
        int rows = buffer.mScreenRows;
        int columns = buffer.mColumns;
        Screen.Cell[][] cells = new Screen.Cell[rows][columns];
        boolean[] lineWrap = new boolean[rows];
        for (int row = 0; row < rows; row++) {
            int internal = buffer.externalToInternalRow(row);
            TerminalRow line = buffer.mLines[internal];
            for (int column = 0; column < columns; column++) {
                long style = line.getStyle(column);
                cells[row][column] = new Screen.Cell(
                        line.mText[column],
                        TextStyle.decodeForeColor(style),
                        TextStyle.decodeBackColor(style),
                        TextStyle.decodeEffect(style));
            }
            lineWrap[row] = buffer.getLineWrap(row);
        }
        return new Screen(rows, columns, cells, emulator.getCursorRow(), emulator.getCursorCol(),
                emulator.isAlternateBufferActive(), emulator.getTitle(), lineWrap);
    }

    /** The emulator's client surface is irrelevant to conformance; nothing here affects the screen. */
    private static final class NoopClient implements TerminalSessionClient {
        @Override
        public void onTextChanged(TerminalSession session) {
        }

        @Override
        public void onTitleChanged(TerminalSession session) {
        }

        @Override
        public void onSessionFinished(TerminalSession session) {
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return 0;
        }

        @Override
        public void logError(String tag, String message) {
        }

        @Override
        public void logWarn(String tag, String message) {
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
        }
    }
}
