package ro.solarstudios.flowcity.gps;

import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Identitatea vizuala a GPS-ului FlowCity: paleta, spatierile, scara de text si cateva primitive de suprafata
 * (panou de sticla, umbra moale, separator). Totul e desenat din cod; nu exista texturi.
 *
 * <p>Reguli: fundalul e aproape negru, panourile urca in doua trepte (BG2, PANEL), accentul violet se foloseste
 * doar pentru starea activa si actiunea principala, iar albastrul doar pentru hartă (traseu, jucator, selectie).
 */
final class GpsTheme {
    private GpsTheme() {}

    // ------------------------------------------------------------------ culori

    static final int BG = 0xFF101216;
    static final int BG2 = 0xFF191D24;
    static final int PANEL = 0xFF202630;
    static final int PANEL_HI = 0xFF262D39;
    static final int ACCENT = 0xFF8B5CF6;
    static final int ACCENT_DIM = 0xFF6D45C8;
    static final int SKY = 0xFF38BDF8;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0xFF9CA3AF;
    static final int FAINT = 0xFF646B78;
    static final int DANGER = 0xFFEF4444;
    static final int OK = 0xFF22C55E;
    static final int GOLD = 0xFFF5B942;

    /** Contur de 1px pe panouri (foarte discret, nu o bordura groasa). */
    static final int LINE = 0xFF2B313C;
    static final int LINE_SOFT = 0x14FFFFFF;
    /** Panou peste harta: lasa harta sa se simta dedesubt. */
    static final int GLASS = 0xF0141820;
    static final int GLASS_HI = 0xF01C222C;
    /** Voal peste harta cand o pagina acopera zona centrala. */
    static final int SCRIM = 0xD60B0D11;

    // ------------------------------------------------------------------ spatiere si raze

    static final float S1 = 4f;
    static final float S2 = 8f;
    static final float S3 = 12f;
    static final float S4 = 16f;
    static final float S5 = 24f;
    static final float R_SM = 8f;
    static final float R_MD = 12f;
    static final float R_LG = 16f;

    // ------------------------------------------------------------------ text

    static final float T_H1 = 15f;
    static final float T_H2 = 11.5f;
    static final float T_BODY = 9.4f;
    static final float T_SMALL = 8.2f;
    static final float T_CAPTION = 6.8f;
    /** Spatiere intre litere pentru etichetele scrise cu majuscule. */
    static final float SP_CAPTION = 1.3f;

    // ------------------------------------------------------------------ miscare

    /** Interpolare cadru-cu-cadru spre o tinta; speed ~ 1/durata (12 = ~160ms). */
    static float to(float cur, float target, float dt, float speed) {
        return cur + (target - cur) * Math.min(1f, dt * speed);
    }

    /** Ease-out cubic, pentru intrari scurte. */
    static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    static int fade(int argb, float a) {
        return Gfx.fade(argb, Math.max(0f, Math.min(1f, a)));
    }

    /** Culoare cu opacitate absoluta (0..255), pastrand nuanta. */
    static int alpha(int argb, int a) {
        return (a << 24) | (argb & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        return Gfx.mixArgb(a, b, Math.max(0f, Math.min(1f, t)));
    }

    // ------------------------------------------------------------------ suprafete

    /** Umbra moale sub un panou (cateva straturi din ce in ce mai transparente, fara halou). */
    static void shadow(GuiGraphics g, float x, float y, float w, float h, float r) {
        for (int i = 3; i >= 1; i--) {
            float o = i * 1.6f;
            Gfx.rrect(g, x - o, y - o + 2f, w + o * 2f, h + o * 2f, r + o, alpha(0x000000, 10 + (3 - i) * 6));
        }
    }

    /** Opacitatea aleasa de jucator, aplicata umplerilor de panou (conturul si textul raman la fel). */
    static int op(int argb) {
        float o = GpsPrefs.opacity;
        if (o >= 0.999f) return argb;
        return alpha(argb, Math.round(((argb >>> 24) & 0xFF) * o));
    }

    /** Panou plin, cu contur de 1px. */
    static void panel(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int line) {
        Gfx.rrect(g, x, y, w, h, r, line);
        Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, Math.max(0f, r - 1f), op(fill));
    }

    /** Panou peste harta: umbra + sticla + contur. */
    static void glass(GuiGraphics g, float x, float y, float w, float h, float r) {
        shadow(g, x, y, w, h, r);
        panel(g, x, y, w, h, r, GLASS, LINE);
        // lumina fina pe muchia de sus, ca sa nu para plat
        Gfx.rrect(g, x + r * 0.6f, y + 1f, w - r * 1.2f, 1f, 0.5f, LINE_SOFT);
    }

    /** Card pe fundal opac (in pagini). */
    static void card(GuiGraphics g, float x, float y, float w, float h, float r, float hot) {
        int fill = mix(BG2, PANEL_HI, hot);
        Gfx.rrect(g, x, y, w, h, r, mix(LINE, GpsPrefs.accent(), hot * 0.55f));
        Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, Math.max(0f, r - 1f), op(fill));
    }

    /** Linie de separare orizontala. */
    static void divider(GuiGraphics g, float x, float y, float w) {
        Gfx.rrect(g, x, y, w, 1f, 0f, LINE);
    }

    /** Eticheta scurta cu majuscule (sectiune). */
    static void caption(GuiGraphics g, String s, float x, float baseline, int color) {
        Gfx.text(g, s, x, baseline, T_CAPTION, color, 0, true, SP_CAPTION);
    }
}
