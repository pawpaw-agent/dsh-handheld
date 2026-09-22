package android.graphics;

/** Minimal JVM stand-in for the three {@code android.graphics.Color} accessors the emulator uses. */
public final class Color {

    private Color() {
    }

    public static int red(int color) {
        return (color >> 16) & 0xFF;
    }

    public static int green(int color) {
        return (color >> 8) & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }
}
