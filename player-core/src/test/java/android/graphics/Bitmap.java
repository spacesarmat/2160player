package android.graphics;

import java.util.Arrays;

/** Минимальная JVM-реализация для unit-тестов (её создаёт PgsParser). */
public final class Bitmap {
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }

    private final int width;
    private final int height;
    private final int[] pixels;

    private Bitmap(int[] pixels, int width, int height) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return new Bitmap(colors.clone(), width, height);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getPixel(int x, int y) {
        return pixels[y * width + x];
    }

    public boolean sameAs(Bitmap other) {
        return other != null && width == other.width && height == other.height
                && Arrays.equals(pixels, other.pixels);
    }
}
