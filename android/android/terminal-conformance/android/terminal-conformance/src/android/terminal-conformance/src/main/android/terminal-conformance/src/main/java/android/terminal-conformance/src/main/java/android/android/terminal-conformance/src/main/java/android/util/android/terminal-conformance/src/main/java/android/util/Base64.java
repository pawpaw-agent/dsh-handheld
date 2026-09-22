package android.util;

/**
 * Minimal JVM stand-in for {@code android.util.Base64}.
 *
 * <p>The conformance harness runs the reference terminal emulator on a plain JVM;
 * the real emulator uses this class in exactly one place (OSC 52 clipboard
 * handling), so only {@link #decode(String, int)} needs to exist.</p>
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;

    private Base64() {
    }

    /** Decode base64 text, tolerating whitespace and missing padding. */
    public static byte[] decode(String str, int flags) {
        if (str == null || str.isEmpty()) {
            return new byte[0];
        }
        String cleaned = str.replaceAll("\\s", "");
        int padding = (4 - cleaned.length() % 4) % 4;
        if (padding > 0) {
            cleaned = cleaned + "=".repeat(padding);
        }
        try {
            if ((flags & URL_SAFE) != 0) {
                return java.util.Base64.getUrlDecoder().decode(cleaned);
            }
            return java.util.Base64.getMimeDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
