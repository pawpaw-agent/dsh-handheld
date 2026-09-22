package android.util;

/** Minimal JVM stand-in for {@code android.util.Log} (used by the reference emulator's Logger). */
public final class Log {

    private Log() {
    }

    public static int v(String tag, String msg) {
        return 0;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg) {
        System.err.println("E/" + tag + ": " + msg);
        return 0;
    }
}
