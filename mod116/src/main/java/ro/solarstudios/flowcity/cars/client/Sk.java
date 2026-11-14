package ro.solarstudios.flowcity.cars.client;

import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Elemente de desen in stil "joc de masini" (liste inclinate, colturi taiate, bare segmentate, un singur accent):
 * folosite de dealer si tuning ca sa arate la fel. Fara colturi rotunjite si fara gradiente decorative.
 */
public final class Sk {
    public static final int ACC = 0xFF4DA3FF;
    public static final int ACC_DARK = 0xFF2F7FD6;
    public static final int WHITE = 0xFFF2F7FF;
    public static final int MUTED = 0xFF8FA3BD;
    public static final int FAINT = 0xFF55667C;
    public static final int GOOD = 0xFF62D98F;
    public static final int BAD = 0xFFF0584C;
    public static final int WARN = 0xFFE9A23B;
    public static final int PANEL = 0xD9081018;
    public static final int ROW = 0xB0111A28;
    public static final int ROW_HOT = 0xD01B2A40;

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

    /**
     * Fundalul ecranului, in tema de iarna FlowCity: albastru adanc, ninsoare, o ghirlanda cu beculete sus si o banda de
     * accent (gheata) in colt.
     */
    public static void background(GuiGraphics g, float l, float t, float r, float b, float open) {
        Gfx.rrect(g, l, t, r - l, b - t, 0, Gfx.fade(0xFF0E1A2E, 0.96f * open), Gfx.fade(0xFF060A14, 0.98f * open));
        Gfx.scrim(g, (l + r) / 2f, t, (r - l) * 0.6f, (b - t) * 0.5f, Gfx.fade(0xFF4DA3FF, 0.10f * open));
        long now = System.currentTimeMillis();
        float tt = now / 1000f;
        float w = r - l;
        float h = b - t;
        for (int i = 0; i < 46; i++) {
            float k1 = frac(i * 0.6180339f);
            float k2 = frac(i * 0.7548776f + 0.3f);
            float k3 = frac(i * 0.5698403f + 0.7f);
            float speed = 9f + 16f * k2;
            float x = l + frac(k1 + 0.04f * (float) Math.sin(tt * 0.6f + i)) * w;
            float y = t + frac(k3 + tt * speed / h) * h;
            float rad = 0.7f + 1.5f * k2;
            Gfx.disc(g, x, y, rad, Gfx.fade(0xFFFFFFFF, (0.18f + 0.4f * k2) * open));
        }
        // ghirlanda cu beculete
        float arc = 96f;
        int[] cols = {0xFFFF5A5A, 0xFFF6C15A, 0xFF4DA3FF, 0xFF5FDC8F};
        float px = l;
        float py = t + 1;
        for (float x = l + 4; x <= r + 4; x += 4) {
            float ph = ((x - l) % arc) / arc;
            float y = t + 1.5f + 8f * (float) Math.sin(Math.PI * ph);
            Gfx.line(g, px, py, x, y, 1f, Gfx.fade(0xFF1F2B3D, open));
            px = x;
            py = y;
        }
        int n = 0;
        for (float x = l + arc / 6f; x < r; x += arc / 3f) {
            float ph = ((x - l) % arc) / arc;
            float y = t + 1.5f + 8f * (float) Math.sin(Math.PI * ph);
            int c = cols[n % cols.length];
            float tw = 0.55f + 0.45f * (float) Math.sin(tt * 2.4f + n * 1.7f);
            Gfx.disc(g, x, y + 3.5f, 5f, Gfx.fade(c, 0.12f * tw * open));
            Gfx.disc(g, x, y + 3.5f, 2.3f, Gfx.fade(c, (0.65f + 0.35f * tw) * open));
            n++;
        }
        // banda de accent (gheata) in colt
        Gfx.poly(g, new float[] {l, t + 14, l + 120, t + 14, l + 96, t + 19, l, t + 19}, Gfx.fade(ACC, open));
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
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
        for (int i = 0; i < max; i++) Gfx.rrect(g, x + i * (sw + gap), y, sw, h, 0, i < v ? fill : 0xFF1B2839);
    }

    /** Buton de inchidere patrat cu X. */
    public static boolean close(GuiGraphics g, float x, float y, float mx, float my) {
        boolean h = in(mx, my, x, y, 20, 20);
        Gfx.rrect(g, x, y, 20, 20, 0, h ? ACC : 0xFF14202F);
        int c = h ? 0xFF061426 : MUTED;
        Gfx.line(g, x + 6, y + 6, x + 14, y + 14, 1.6f, c);
        Gfx.line(g, x + 14, y + 6, x + 6, y + 14, 1.6f, c);
        return h;
    }

    /** Buton mare inclinat (plin = actiunea principala). */
    public static void button(GuiGraphics g, String label, float x, float y, float w, float h, boolean enabled, boolean filled, boolean hover) {
        int bg;
        int fg;
        if (!enabled) {
            bg = 0xFF14202F;
            fg = FAINT;
        } else if (filled) {
            bg = hover ? 0xFF7DBBFF : ACC;
            fg = 0xFF061426;
        } else {
            bg = hover ? 0xFF22344D : 0xFF14202F;
            fg = hover ? WHITE : 0xFFC9CBD0;
        }
        slant(g, x, y, w, h, 8f, bg);
        if (enabled && !filled) slant(g, x, y + h - 2, w - 8f, 2, 0f, ACC);
        Gfx.text(g, label, x + w / 2f + 2, y + h / 2f + 3.6f, 9f, fg, 1, true, 0.4f);
    }
}
