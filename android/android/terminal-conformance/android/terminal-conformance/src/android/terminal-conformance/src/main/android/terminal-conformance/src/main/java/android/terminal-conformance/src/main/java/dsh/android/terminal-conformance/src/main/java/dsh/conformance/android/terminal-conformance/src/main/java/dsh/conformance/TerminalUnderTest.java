package dsh.conformance;

/**
 * One terminal implementation under test.
 *
 * <p>The harness drives every implementation through this interface only, so the
 * reference emulator and the in-house emulator are compared on identical
 * footing: same bytes in, same {@link Screen} out.</p>
 */
public interface TerminalUnderTest {

    /** A short name used in reports. */
    String name();

    /**
     * Feed a slice of raw pty bytes.
     *
     * <p>Slices are not aligned to anything: a call may end in the middle of an
     * escape sequence or a UTF-8 character, exactly as a real read does. An
     * implementation must carry the partial sequence into the next call.</p>
     *
     * @param data   the buffer holding the slice.
     * @param offset index of the slice's first byte.
     * @param length number of bytes in the slice.
     */
    void feed(byte[] data, int offset, int length);

    /** Resize the screen, as a pty window-size change would. */
    void resize(int rows, int columns);

    /** Clear all state, as a fresh session would start. */
    void reset();

    /** Take an immutable snapshot of everything a user can observe. */
    Screen snapshot();
}
