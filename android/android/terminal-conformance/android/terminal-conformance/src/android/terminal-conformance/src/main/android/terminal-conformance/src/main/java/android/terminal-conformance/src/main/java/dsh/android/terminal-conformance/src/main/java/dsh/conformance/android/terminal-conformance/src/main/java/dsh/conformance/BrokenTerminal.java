package dsh.conformance;

/**
 * A deliberately wrong terminal, used only to prove the harness can fail.
 *
 * <p>A differential suite that never reports a difference is indistinguishable from
 * one that is broken, so the self-test runs every corpus case through this
 * implementation and asserts that the harness flags it. It ignores its input and
 * returns a fixed screen, which is the most common way a real emulator regresses:
 * the bytes arrive but nothing reaches the screen.</p>
 */
public final class BrokenTerminal implements TerminalUnderTest {

    private final int rows;
    private final int columns;

    public BrokenTerminal(int rows, int columns) {
        this.rows = rows;
        this.columns = columns;
    }

    @Override
    public String name() {
        return "broken";
    }

    @Override
    public void feed(byte[] data, int offset, int length) {
        // Intentionally ignores everything: the screen never changes.
    }

    @Override
    public void resize(int newRows, int newColumns) {
    }

    @Override
    public void reset() {
    }

    @Override
    public Screen snapshot() {
        Screen.Cell[][] cells = new Screen.Cell[rows][columns];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                cells[row][column] = new Screen.Cell(' ', Screen.COLOR_DEFAULT_FOREGROUND,
                        Screen.COLOR_DEFAULT_BACKGROUND, 0);
            }
        }
        return new Screen(rows, columns, cells, 0, 0, false, "", new boolean[rows]);
    }
}
