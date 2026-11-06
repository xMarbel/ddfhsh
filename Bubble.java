package pl.playertracker;

import net.minecraft.client.gui.DrawContext;

/** Pomocnik do rysowania zaokrąglonych "bąbelków" w GUI. */
public final class Bubble {

    private Bubble() {}

    public static int lerp(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (int) (((a >>> 24) & 255) + (((b >>> 24) & 255) - ((a >>> 24) & 255)) * t);
        int ar = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int ag = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int ab = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }

    private static int inset(int j, int r) {
        if (j >= r) return 0;
        double d = r - j - 0.5;
        return r - (int) Math.round(Math.sqrt(Math.max(0, r * (double) r - d * d)));
    }

    /** Zaokrąglony prostokąt z pionowym gradientem (top == bottom => jednolity). */
    public static void rect(DrawContext c, int x, int y, int w, int h, int r, int top, int bottom) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (top == bottom) {
            if (h - 2 * r > 0) c.fill(x, y + r, x + w, y + h - r, top);
            for (int i = 0; i < r; i++) {
                int in = inset(i, r);
                c.fill(x + in, y + i, x + w - in, y + i + 1, top);
                c.fill(x + in, y + h - 1 - i, x + w - in, y + h - i, top);
            }
            return;
        }
        for (int i = 0; i < h; i++) {
            int in = inset(Math.min(i, h - 1 - i), r);
            int col = lerp(top, bottom, h <= 1 ? 0f : i / (float) (h - 1));
            c.fill(x + in, y + i, x + w - in, y + i + 1, col);
        }
    }

    public static void rect(DrawContext c, int x, int y, int w, int h, int r, int color) {
        rect(c, x, y, w, h, r, color, color);
    }

    /** Bąbelek z 1px obwódką. */
    public static void bordered(DrawContext c, int x, int y, int w, int h, int r, int border, int top, int bottom) {
        rect(c, x, y, w, h, r, border);
        rect(c, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), top, bottom);
    }

    /** Miękki cień pod bąbelkiem. */
    public static void shadow(DrawContext c, int x, int y, int w, int h, int r) {
        rect(c, x - 4, y - 3, w + 8, h + 8, r + 4, 0x12000000);
        rect(c, x - 2, y - 1, w + 4, h + 5, r + 2, 0x1C000000);
    }
}
