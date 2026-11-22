package ro.solarstudios.flowcity.gps;

import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Iconitele categoriilor GPS, desenate din forme simple (fara texturi). Un insemn rotund: disc inchis + inel + simbol. */
public final class GpsIcons {
    private GpsIcons() {}

    private static final int DARK = 0xFF0A1626;

    /** Biblioteca iconitelor incluse in mod: {id, nume, categorie}. Toate sunt vectoriale (desenate cu Gfx), nu texturi PNG. */
    public static final String[][] BUILTIN = {
        {"police", "Poliție", "Poliție"},
        {"hospital", "Spital", "Spital"},
        {"bank", "Bancă", "Bancă"},
        {"atm", "ATM", "ATM"},
        {"shop", "Magazin", "Magazine"},
        {"mall", "Mall", "Magazine"},
        {"fuel", "Benzinărie", "Benzinării"},
        {"repair", "Service auto", "Service Auto"},
        {"tuning", "Tuning", "Tuning"},
        {"city_hall", "Primărie", "Primărie"},
        {"garage", "Garaj", "Garaje"},
        {"restaurant", "Restaurant", "Restaurante"},
        {"fishing", "Pescuit", "Zone de pescuit"},
        {"bait", "Momeală", "Zone de pescuit"},
        {"border", "Vamă", "Vama"},
        {"home", "Proprietate", "Proprietăți"},
        {"prison", "Închisoare", "Alte locații"},
        {"pin", "Marcaj", "Alte locații"}
    };

    /** Categoriile bibliotecii, in ordinea afisarii (cele personalizate vin din icons.json, cu categoria lor). */
    public static final String[] LIB_CATEGORIES = {
        "Poliție", "Spital", "Bancă", "ATM", "Magazine", "Benzinării", "Service Auto", "Tuning", "Primărie", "Garaje",
        "Restaurante", "Zone de pescuit", "Vama", "Proprietăți", "Alte locații"
    };

    /** Insemn de marcaj: raza r, culoarea categoriei; hot = evidentiat (inel dublu). */
    public static void badge(GuiGraphics g, String icon, float cx, float cy, float r, int color, float alpha, boolean hot) {
        int col = Gfx.fade(color, alpha);
        Gfx.disc(g, cx, cy + 1f, r + 1.5f, Gfx.fade(0x66000000, alpha));
        Gfx.disc(g, cx, cy, r, Gfx.fade(DARK, alpha));
        Gfx.ring(g, cx, cy, r - 1.3f, r, col, col);
        if (hot) Gfx.ring(g, cx, cy, r + 1.6f, r + 2.6f, Gfx.fade(0xFFFFFFFF, alpha * 0.9f), Gfx.fade(0xFFFFFFFF, alpha * 0.9f));
        glyph(g, icon, cx, cy, r * 0.5f, col, alpha);
    }

    /**
     * Marcaj de harta: simbolul mare, colorat, cu contur intunecat in jur (fara disc), ca sa se vada pe orice teren.
     * u = unitate (simbolul acopera aprox. +-1.1u); hot = inel alb in jurul lui.
     */
    public static void pin(GuiGraphics g, String icon, float cx, float cy, float u, int color, float alpha, boolean hot) {
        int halo = Gfx.fade(0xFF050B14, alpha);
        float o = Math.max(0.9f, u * 0.26f);
        glyph(g, icon, cx - o, cy, u, halo, alpha);
        glyph(g, icon, cx + o, cy, u, halo, alpha);
        glyph(g, icon, cx, cy - o, u, halo, alpha);
        glyph(g, icon, cx, cy + o, u, halo, alpha);
        if (hot) {
            int w = Gfx.fade(0xFFFFFFFF, alpha * 0.9f);
            Gfx.ring(g, cx, cy, u * 1.45f, u * 1.45f + 1.3f, w, w);
        }
        glyph(g, icon, cx, cy, u, Gfx.fade(color, alpha), alpha);
    }

    /** Doar simbolul (fara insemn), u = unitate (aprox. jumatate din raza insemnului). */
    public static void glyph(GuiGraphics g, String icon, float cx, float cy, float u, int col, float alpha) {
        int dk = Gfx.fade(DARK, alpha);
        switch (icon == null ? "" : icon) {
            case "mall":
                Gfx.rrect(g, cx - 0.9f * u, cy - 0.35f * u, 1.8f * u, 1.45f * u, 1.2f, col);
                Gfx.ring(g, cx, cy - 0.35f * u, 0.38f * u, 0.58f * u, col, col);
                Gfx.rrect(g, cx - 0.9f * u, cy - 0.35f * u, 1.8f * u, 0.4f * u, 0f, Gfx.fade(0x33000000, alpha));
                break;
            case "bait":
            case "fishing":
                Gfx.line(g, cx + 0.2f * u, cy - 1.0f * u, cx + 0.2f * u, cy + 0.3f * u, 1.3f, col);
                Gfx.ring(g, cx - 0.2f * u, cy + 0.35f * u, 0.4f * u, 0.65f * u, col, col);
                Gfx.disc(g, cx + 0.2f * u, cy - 1.0f * u, 0.22f * u, col);
                break;
            case "city_hall":
                Gfx.poly(g, new float[] {cx - 1.0f * u, cy - 0.25f * u, cx, cy - 1.0f * u, cx + 1.0f * u, cy - 0.25f * u}, col);
                Gfx.rrect(g, cx - 0.8f * u, cy - 0.2f * u, 1.6f * u, 1.1f * u, 0f, col);
                Gfx.rrect(g, cx - 0.22f * u, cy + 0.2f * u, 0.44f * u, 0.7f * u, 0f, dk);
                break;
            case "police":
                Gfx.poly(g, new float[] {cx - 0.85f * u, cy - 0.95f * u, cx + 0.85f * u, cy - 0.95f * u, cx + 0.85f * u, cy + 0.15f * u, cx, cy + 1.05f * u, cx - 0.85f * u, cy + 0.15f * u}, col);
                Gfx.disc(g, cx, cy - 0.15f * u, 0.28f * u, dk);
                break;
            case "tuning":
                Gfx.line(g, cx - 0.8f * u, cy + 0.8f * u, cx + 0.8f * u, cy - 0.8f * u, 1.8f, col);
                Gfx.line(g, cx - 0.8f * u, cy - 0.8f * u, cx + 0.8f * u, cy + 0.8f * u, 1.8f, col);
                Gfx.disc(g, cx + 0.8f * u, cy - 0.8f * u, 0.32f * u, col);
                Gfx.disc(g, cx - 0.8f * u, cy - 0.8f * u, 0.32f * u, col);
                break;
            case "border":
                Gfx.rrect(g, cx - 1.0f * u, cy - 0.35f * u, 2.0f * u, 0.5f * u, 0f, col);
                Gfx.rrect(g, cx - 0.55f * u, cy - 0.35f * u, 0.38f * u, 0.5f * u, 0f, dk);
                Gfx.rrect(g, cx + 0.25f * u, cy - 0.35f * u, 0.38f * u, 0.5f * u, 0f, dk);
                Gfx.rrect(g, cx - 1.0f * u, cy - 0.35f * u, 0.3f * u, 1.4f * u, 0f, col);
                break;
            case "hospital":
                Gfx.rrect(g, cx - 0.28f * u, cy - 0.95f * u, 0.56f * u, 1.9f * u, 0f, col);
                Gfx.rrect(g, cx - 0.95f * u, cy - 0.28f * u, 1.9f * u, 0.56f * u, 0f, col);
                break;
            case "bank":
                Gfx.poly(g, new float[] {cx - 1.0f * u, cy - 0.3f * u, cx, cy - 1.0f * u, cx + 1.0f * u, cy - 0.3f * u}, col);
                for (int i = -1; i <= 1; i++) Gfx.rrect(g, cx + i * 0.6f * u - 0.16f * u, cy - 0.15f * u, 0.32f * u, 0.9f * u, 0f, col);
                Gfx.rrect(g, cx - 1.0f * u, cy + 0.85f * u, 2.0f * u, 0.22f * u, 0f, col);
                break;
            case "atm":
                Gfx.text(g, "$", cx, cy + 0.72f * u, 2.1f * u, col, 1, true, 0f);
                break;
            case "fuel":
                Gfx.rrect(g, cx - 0.7f * u, cy - 0.95f * u, 1.1f * u, 1.9f * u, 1.2f, col);
                Gfx.rrect(g, cx - 0.5f * u, cy - 0.7f * u, 0.7f * u, 0.55f * u, 0f, dk);
                Gfx.line(g, cx + 0.4f * u, cy - 0.5f * u, cx + 0.9f * u, cy - 0.1f * u, 1.3f, col);
                Gfx.line(g, cx + 0.9f * u, cy - 0.1f * u, cx + 0.9f * u, cy + 0.6f * u, 1.3f, col);
                break;
            case "repair":
                Gfx.disc(g, cx, cy, 0.7f * u, col);
                for (int i = 0; i < 4; i++) {
                    double a = Math.PI / 4 * i;
                    float dx = (float) Math.cos(a) * 1.0f * u;
                    float dy = (float) Math.sin(a) * 1.0f * u;
                    Gfx.line(g, cx - dx, cy - dy, cx + dx, cy + dy, 1.8f, col);
                }
                Gfx.disc(g, cx, cy, 0.32f * u, dk);
                break;
            case "shop":
                Gfx.poly(g, new float[] {cx - 0.95f * u, cy - 0.35f * u, cx + 0.95f * u, cy - 0.35f * u, cx + 0.65f * u, cy + 0.75f * u, cx - 0.65f * u, cy + 0.75f * u}, col);
                Gfx.line(g, cx - 0.6f * u, cy - 0.35f * u, cx - 0.25f * u, cy - 0.95f * u, 1.2f, col);
                Gfx.line(g, cx + 0.6f * u, cy - 0.35f * u, cx + 0.25f * u, cy - 0.95f * u, 1.2f, col);
                break;
            case "garage":
                Gfx.poly(g, new float[] {cx - 0.5f * u, cy - 0.7f * u, cx + 0.45f * u, cy - 0.7f * u, cx + 0.8f * u, cy - 0.1f * u, cx - 0.8f * u, cy - 0.1f * u}, col);
                Gfx.rrect(g, cx - 1.05f * u, cy - 0.15f * u, 2.1f * u, 0.75f * u, 1.2f, col);
                Gfx.disc(g, cx - 0.5f * u, cy + 0.6f * u, 0.3f * u, dk);
                Gfx.disc(g, cx + 0.5f * u, cy + 0.6f * u, 0.3f * u, dk);
                Gfx.disc(g, cx - 0.5f * u, cy + 0.6f * u, 0.16f * u, col);
                Gfx.disc(g, cx + 0.5f * u, cy + 0.6f * u, 0.16f * u, col);
                break;
            case "prison":
                Gfx.rrect(g, cx - 0.9f * u, cy - 0.9f * u, 1.8f * u, 1.8f * u, 1.2f, col);
                for (int i = -1; i <= 1; i++) Gfx.rrect(g, cx + i * 0.5f * u - 0.1f * u, cy - 0.75f * u, 0.2f * u, 1.5f * u, 0f, dk);
                break;
            case "restaurant":
                Gfx.line(g, cx - 0.55f * u, cy - 0.95f * u, cx - 0.55f * u, cy + 0.95f * u, 1.5f, col);
                Gfx.line(g, cx - 0.85f * u, cy - 0.95f * u, cx - 0.85f * u, cy - 0.2f * u, 1.2f, col);
                Gfx.line(g, cx - 0.25f * u, cy - 0.95f * u, cx - 0.25f * u, cy - 0.2f * u, 1.2f, col);
                Gfx.line(g, cx - 0.85f * u, cy - 0.2f * u, cx - 0.25f * u, cy - 0.2f * u, 1.4f, col);
                Gfx.line(g, cx + 0.6f * u, cy - 0.95f * u, cx + 0.6f * u, cy + 0.95f * u, 1.5f, col);
                Gfx.poly(g, new float[] {cx + 0.6f * u, cy - 0.95f * u, cx + 0.95f * u, cy - 0.5f * u, cx + 0.95f * u, cy + 0.15f * u, cx + 0.6f * u, cy + 0.15f * u}, col);
                break;
            case "home":
                Gfx.poly(g, new float[] {cx - 1.0f * u, cy - 0.1f * u, cx, cy - 1.0f * u, cx + 1.0f * u, cy - 0.1f * u}, col);
                Gfx.rrect(g, cx - 0.7f * u, cy - 0.1f * u, 1.4f * u, 1.0f * u, 0f, col);
                break;
            default:
                GpsData.CIcon ci = icon == null ? null : GpsData.customIcons.get(icon);
                if (ci != null) {
                    custom(g, ci, cx, cy, u, col, dk);
                    break;
                }
                Gfx.disc(g, cx, cy - 0.25f * u, 0.7f * u, col);
                Gfx.poly(g, new float[] {cx - 0.55f * u, cy + 0.05f * u, cx + 0.55f * u, cy + 0.05f * u, cx, cy + 1.0f * u}, col);
                Gfx.disc(g, cx, cy - 0.25f * u, 0.28f * u, dk);
                break;
        }
    }

    /** Deseneaza o iconita personalizata (forme normalizate in -1..1, scalate cu u). */
    public static void custom(GuiGraphics g, GpsData.CIcon ci, float cx, float cy, float u, int col, int dk) {
        for (GpsData.Shape sh : ci.shapes) {
            int c = sh.dark ? dk : col;
            float[] p = sh.p;
            switch (sh.t) {
                case "poly": {
                    float[] t = sh.tris;
                    for (int i = 0; i + 5 < t.length; i += 6) {
                        Gfx.poly(g, new float[] {cx + t[i] * u, cy + t[i + 1] * u, cx + t[i + 2] * u, cy + t[i + 3] * u, cx + t[i + 4] * u, cy + t[i + 5] * u}, c);
                    }
                    break;
                }
                case "line":
                    if (p.length >= 4) Gfx.line(g, cx + p[0] * u, cy + p[1] * u, cx + p[2] * u, cy + p[3] * u, Math.max(1f, sh.w * u), c);
                    break;
                case "circle":
                    if (p.length >= 2) Gfx.disc(g, cx + p[0] * u, cy + p[1] * u, Math.max(0.5f, sh.r * u), c);
                    break;
                case "ring":
                    if (p.length >= 2) Gfx.ring(g, cx + p[0] * u, cy + p[1] * u, Math.max(0f, (sh.r - sh.w / 2f) * u), (sh.r + sh.w / 2f) * u, c, c);
                    break;
                case "rect":
                    if (p.length >= 4) Gfx.rrect(g, cx + p[0] * u, cy + p[1] * u, p[2] * u, p[3] * u, 0f, c);
                    break;
                default:
                    break;
            }
        }
    }

    /** Sageata de directie (varf spre unghiul ang, in radiani; 0 = in sus pe ecran), cu contur. */
    public static void arrow(GuiGraphics g, float cx, float cy, float size, double ang, int fill, int edge) {
        float ux = (float) Math.sin(ang);
        float uy = (float) -Math.cos(ang);
        float px = -uy;
        float py = ux;
        float[] outer = {cx + ux * size * 1.15f, cy + uy * size * 1.15f, cx - ux * size * 0.8f + px * size * 0.85f, cy - uy * size * 0.8f + py * size * 0.85f, cx - ux * size * 0.25f, cy - uy * size * 0.25f, cx - ux * size * 0.8f - px * size * 0.85f, cy - uy * size * 0.8f - py * size * 0.85f};
        Gfx.poly(g, outer, edge);
        float s = 0.72f;
        float[] inner = {cx + ux * size * 1.15f * s, cy + uy * size * 1.15f * s, cx - ux * size * 0.8f * s + px * size * 0.85f * s, cy - uy * size * 0.8f * s + py * size * 0.85f * s, cx - ux * size * 0.25f * s, cy - uy * size * 0.25f * s, cx - ux * size * 0.8f * s - px * size * 0.85f * s, cy - uy * size * 0.8f * s - py * size * 0.85f * s};
        Gfx.poly(g, inner, fill);
    }
}
