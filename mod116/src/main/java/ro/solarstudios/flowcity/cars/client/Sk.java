package ro.solarstudios.flowcity.cars.client;

import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Elemente de desen in stil "joc de masini" (liste inclinate, colturi taiate, bare segmentate, un singur accent):
 * folosite de dealer si tuning ca sa arate la fel. Fara colturi rotunjite si fara gradiente decorative.
 */
public final class Sk {
    public static final int ACC = 0xFFFF5B2E;
    public static final int ACC_DARK = 0xFFC9441F;
    public static final int WHITE = 0xFFF2F2F0;
    public static final int MUTED = 0xFF8A8D94;
    public static final int FAINT = 0xFF565960;
    public static final int GOOD = 0xFF62D98F;
    public static final int BAD = 0xFFF0584C;
    public static final int WARN = 0xFFE9A23B;
    public static final int PANEL = 0xD90B0C0F;
    public static final int ROW = 0xB0141519;
    public static final int ROW_HOT = 0xD0202228;

    private Sk() {}

    public static boolean in(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Paralelogram inclinat spre dreapta (sk = cat se decaleaza sus fata de jos). */
    public static void slant(GuiGraphics g, float x, float y, float w, float h, float sk, int color) {
        Gfx.poly(g, new float[] {x + sk, y, x + w, y, x + w - sk, y + h, x, y + h}, color);
    }

    /** Dreptunghi cu colturile sus-dreapta si jos-stanga taiate. */
    public static void cut(GuiGraphics g, float x, float y, float w, float h, float c, int color) {
        Gfx.poly(g, new float[] {x, y, x + w - c, y, x + w, y + c, x + w, y + h, x + c, y + h, x, y + h - c}, color);
    }

    /** Fundalul ecranului: intunecat, cu dungi diagonale fine si o banda de accent. */
    public static void background(GuiGraphics g, float l, float t, float r, float b, float open) {
        Gfx.rrect(g, l, t, r - l, b - t, 0, Gfx.fade(0xFF0E0F12, 0.96f * open), Gfx.fade(0xFF060607, 0.98f * open));
        int stripe = Gfx.fade(0xFFFFFFFF, 0.025f * open);
        for (float x = l - (b - t); x < r; x += 22f) Gfx.line(g, x, b, x + (b - t), t, 1f, stripe);
        // banda de accent in colt, ca pe meniurile de joc
        Gfx.poly(g, new float[] {l, t, l + 120, t, l + 96, t + 5, l, t + 5}, Gfx.fade(ACC, open));
    }

    /** Podea in perspectiva: linii care converg spre orizont. */
    public static void floor(GuiGraphics g, float x0, float x1, float horizon, float ground, int paint) {
        float cx = (x0 + x1) / 2f;
        int line = Gfx.fade(0xFFFFFFFF, 0.06f);
        for (int i = -7; i <= 7; i++) {
            Gfx.line(g, cx + i * 6f, horizon, cx + i * 46f, ground + 26f, 0.8f, line);
        }
        float y = horizon;
        float step = 2.4f;
        while (y < ground + 26f) {
            Gfx.line(g, x0, y, x1, y, 0.8f, Gfx.fade(0xFFFFFFFF, 0.04f + 0.05f * (y - horizon) / (ground + 26f - horizon)));
            y += step;
            step *= 1.38f;
        }
        Gfx.scrim(g, cx, ground + 4, 118, 17, Gfx.fade(paint, 0.34f));
        Gfx.scrim(g, cx, ground + 4, 78, 8, 0x55FFFFFF);
    }

    /** Bara segmentata (v din max). */
    public static void segs(GuiGraphics g, float x, float y, float w, float h, int v, int max, int fill) {
        float gap = 2f;
        float sw = (w - gap * (max - 1)) / max;
        for (int i = 0; i < max; i++) Gfx.rrect(g, x + i * (sw + gap), y, sw, h, 0, i < v ? fill : 0xFF23252B);
    }

    /** Buton de inchidere patrat cu X. */
    public static boolean close(GuiGraphics g, float x, float y, float mx, float my) {
        boolean h = in(mx, my, x, y, 20, 20);
        Gfx.rrect(g, x, y, 20, 20, 0, h ? ACC : 0xFF1A1C21);
        int c = h ? 0xFF160A05 : MUTED;
        Gfx.line(g, x + 6, y + 6, x + 14, y + 14, 1.6f, c);
        Gfx.line(g, x + 14, y + 6, x + 6, y + 14, 1.6f, c);
        return h;
    }

    /** Buton mare inclinat (plin = actiunea principala). */
    public static void button(GuiGraphics g, String label, float x, float y, float w, float h, boolean enabled, boolean filled, boolean hover) {
        int bg;
        int fg;
        if (!enabled) {
            bg = 0xFF1C1E23;
            fg = FAINT;
        } else if (filled) {
            bg = hover ? 0xFFFF7448 : ACC;
            fg = 0xFF1A0B05;
        } else {
            bg = hover ? 0xFF2A2D34 : 0xFF1C1E23;
            fg = hover ? WHITE : 0xFFC9CBD0;
        }
        slant(g, x, y, w, h, 8f, bg);
        if (enabled && !filled) slant(g, x, y + h - 2, w - 8f, 2, 0f, ACC);
        Gfx.text(g, label, x + w / 2f + 2, y + h / 2f + 3.6f, 9f, fg, 1, true, 0.4f);
    }
}
