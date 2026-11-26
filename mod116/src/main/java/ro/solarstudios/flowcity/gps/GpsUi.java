package ro.solarstudios.flowcity.gps;

import java.util.HashMap;
import java.util.Map;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Componentele reutilizabile ale GPS-ului: butoane, file, comutatoare, carduri, bara de derulare, stari goale,
 * plus setul de iconite vectoriale (desenate din linii si discuri, cu aceeasi grosime de traseu).
 *
 * <p>Fiecare componenta primeste un {@code id} ca sa-si tina minte animatia de hover intre cadre
 * (vezi {@link #hot(String, boolean, float)}); desenul ramane imediat, fara obiecte per cadru.
 */
final class GpsUi {
    private GpsUi() {}

    // ------------------------------------------------------------------ animatii de hover

    private static final Map<String, Float> HOT = new HashMap<>();
    private static float dt = 0.016f;

    /** Se apeleaza o data pe cadru, inainte de desen. */
    static void frame(float delta) {
        dt = delta;
        if (HOT.size() > 600) HOT.clear();
    }

    /** Valoarea 0..1 a starii de hover pentru elementul cu id-ul dat (tranzitie ~150ms). */
    static float hot(String id, boolean on, float speed) {
        float cur = HOT.getOrDefault(id, on ? 1f : 0f);
        float v = GpsTheme.to(cur, on ? 1f : 0f, dt, speed);
        if (Math.abs(v - (on ? 1f : 0f)) < 0.002f) v = on ? 1f : 0f;
        HOT.put(id, v);
        return v;
    }

    static float hot(String id, boolean on) {
        return hot(id, on, 14f);
    }

    static boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ iconite vectoriale

    /**
     * Iconita vectoriala centrata in (cx, cy), inscrisa intr-un patrat de latura {@code s}.
     * Grosimea traseului e proportionala cu marimea, ca sa arate la fel la orice scara.
     */
    static void icon(GuiGraphics g, String name, float cx, float cy, float s, int c) {
        float u = s / 16f;
        float w = Math.max(1.1f, 1.55f * u);
        switch (name) {
            case "home": {
                Gfx.line(g, cx - 6f * u, cy - 0.5f * u, cx, cy - 6.5f * u, w, c);
                Gfx.line(g, cx, cy - 6.5f * u, cx + 6f * u, cy - 0.5f * u, w, c);
                Gfx.line(g, cx - 4.4f * u, cy - 1.4f * u, cx - 4.4f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx + 4.4f * u, cy - 1.4f * u, cx + 4.4f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx - 4.4f * u, cy + 6f * u, cx + 4.4f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx - 1.8f * u, cy + 6f * u, cx - 1.8f * u, cy + 1.6f * u, w, c);
                Gfx.line(g, cx + 1.8f * u, cy + 6f * u, cx + 1.8f * u, cy + 1.6f * u, w, c);
                Gfx.line(g, cx - 1.8f * u, cy + 1.6f * u, cx + 1.8f * u, cy + 1.6f * u, w, c);
                break;
            }
            case "map": {
                Gfx.line(g, cx - 6f * u, cy - 4.4f * u, cx - 6f * u, cy + 5.6f * u, w, c);
                Gfx.line(g, cx - 6f * u, cy - 4.4f * u, cx - 1.6f * u, cy - 6.2f * u, w, c);
                Gfx.line(g, cx - 1.6f * u, cy - 6.2f * u, cx - 1.6f * u, cy + 3.8f * u, w, c);
                Gfx.line(g, cx - 1.6f * u, cy - 6.2f * u, cx + 2.6f * u, cy - 4.4f * u, w, c);
                Gfx.line(g, cx + 2.6f * u, cy - 4.4f * u, cx + 2.6f * u, cy + 5.6f * u, w, c);
                Gfx.line(g, cx + 2.6f * u, cy - 4.4f * u, cx + 6.4f * u, cy - 6.2f * u, w, c);
                Gfx.line(g, cx + 6.4f * u, cy - 6.2f * u, cx + 6.4f * u, cy + 3.8f * u, w, c);
                Gfx.line(g, cx + 6.4f * u, cy + 3.8f * u, cx + 2.6f * u, cy + 5.6f * u, w, c);
                Gfx.line(g, cx - 1.6f * u, cy + 3.8f * u, cx - 6f * u, cy + 5.6f * u, w, c);
                break;
            }
            case "pin": {
                ringStroke(g, cx, cy - 1.6f * u, 4.6f * u, w, c);
                Gfx.line(g, cx - 3.3f * u, cy + 1.6f * u, cx, cy + 6.6f * u, w, c);
                Gfx.line(g, cx + 3.3f * u, cy + 1.6f * u, cx, cy + 6.6f * u, w, c);
                Gfx.disc(g, cx, cy - 1.6f * u, 1.7f * u, c);
                break;
            }
            case "star": {
                starShape(g, cx, cy, 6.6f * u, 2.9f * u, c);
                break;
            }
            case "star-line": {
                float[] p = starPoints(cx, cy, 6.6f * u, 2.9f * u);
                for (int i = 0; i < 10; i++) {
                    int j = (i + 1) % 10;
                    Gfx.line(g, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], w, c);
                }
                break;
            }
            case "route": {
                ringStroke(g, cx - 4f * u, cy - 4f * u, 2.4f * u, w, c);
                ringStroke(g, cx + 4.4f * u, cy + 4.4f * u, 2.4f * u, w, c);
                Gfx.line(g, cx - 4f * u, cy - 1.4f * u, cx - 4f * u, cy + 1.4f * u, w, c);
                Gfx.line(g, cx - 4f * u, cy + 1.4f * u, cx + 1.4f * u, cy - 1.2f * u, w, c);
                Gfx.line(g, cx + 1.4f * u, cy - 1.2f * u, cx + 4.4f * u, cy + 1.8f * u, w, c);
                break;
            }
            case "clock": {
                ringStroke(g, cx, cy, 6.3f * u, w, c);
                Gfx.line(g, cx, cy - 3.4f * u, cx, cy + 0.4f * u, w, c);
                Gfx.line(g, cx, cy + 0.4f * u, cx + 3f * u, cy + 1.8f * u, w, c);
                break;
            }
            case "gear": {
                ringStroke(g, cx, cy, 6.4f * u, w, c);
                ringStroke(g, cx, cy, 2.5f * u, w, c);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8.0;
                    float ax = cx + (float) Math.cos(a) * 5.6f * u;
                    float ay = cy + (float) Math.sin(a) * 5.6f * u;
                    float bx = cx + (float) Math.cos(a) * 7.6f * u;
                    float by = cy + (float) Math.sin(a) * 7.6f * u;
                    Gfx.line(g, ax, ay, bx, by, w * 1.3f, c);
                }
                break;
            }
            case "search": {
                ringStroke(g, cx - 1.2f * u, cy - 1.2f * u, 5.2f * u, w, c);
                Gfx.line(g, cx + 2.6f * u, cy + 2.6f * u, cx + 6.4f * u, cy + 6.4f * u, w * 1.15f, c);
                break;
            }
            case "close": {
                Gfx.line(g, cx - 4.6f * u, cy - 4.6f * u, cx + 4.6f * u, cy + 4.6f * u, w * 1.15f, c);
                Gfx.line(g, cx + 4.6f * u, cy - 4.6f * u, cx - 4.6f * u, cy + 4.6f * u, w * 1.15f, c);
                break;
            }
            case "plus": {
                Gfx.line(g, cx - 5.2f * u, cy, cx + 5.2f * u, cy, w * 1.2f, c);
                Gfx.line(g, cx, cy - 5.2f * u, cx, cy + 5.2f * u, w * 1.2f, c);
                break;
            }
            case "minus": {
                Gfx.line(g, cx - 5.2f * u, cy, cx + 5.2f * u, cy, w * 1.2f, c);
                break;
            }
            case "target": {
                ringStroke(g, cx, cy, 5.4f * u, w, c);
                Gfx.disc(g, cx, cy, 1.7f * u, c);
                Gfx.line(g, cx, cy - 7.4f * u, cx, cy - 4.4f * u, w, c);
                Gfx.line(g, cx, cy + 4.4f * u, cx, cy + 7.4f * u, w, c);
                Gfx.line(g, cx - 7.4f * u, cy, cx - 4.4f * u, cy, w, c);
                Gfx.line(g, cx + 4.4f * u, cy, cx + 7.4f * u, cy, w, c);
                break;
            }
            case "expand": {
                for (int i = 0; i < 4; i++) {
                    float sxg = (i & 1) == 0 ? -1f : 1f;
                    float syg = i < 2 ? -1f : 1f;
                    Gfx.line(g, cx + sxg * 2.6f * u, cy + syg * 6.2f * u, cx + sxg * 6.2f * u, cy + syg * 6.2f * u, w, c);
                    Gfx.line(g, cx + sxg * 6.2f * u, cy + syg * 6.2f * u, cx + sxg * 6.2f * u, cy + syg * 2.6f * u, w, c);
                }
                break;
            }
            case "layers": {
                Gfx.line(g, cx - 6.2f * u, cy - 2.4f * u, cx, cy - 6.2f * u, w, c);
                Gfx.line(g, cx, cy - 6.2f * u, cx + 6.2f * u, cy - 2.4f * u, w, c);
                Gfx.line(g, cx + 6.2f * u, cy - 2.4f * u, cx, cy + 1.4f * u, w, c);
                Gfx.line(g, cx, cy + 1.4f * u, cx - 6.2f * u, cy - 2.4f * u, w, c);
                Gfx.line(g, cx - 6.2f * u, cy + 2.2f * u, cx, cy + 6f * u, w, c);
                Gfx.line(g, cx + 6.2f * u, cy + 2.2f * u, cx, cy + 6f * u, w, c);
                break;
            }
            case "trash": {
                Gfx.line(g, cx - 5.4f * u, cy - 3.6f * u, cx + 5.4f * u, cy - 3.6f * u, w, c);
                Gfx.line(g, cx - 2f * u, cy - 3.6f * u, cx - 2f * u, cy - 5.6f * u, w, c);
                Gfx.line(g, cx + 2f * u, cy - 3.6f * u, cx + 2f * u, cy - 5.6f * u, w, c);
                Gfx.line(g, cx - 2f * u, cy - 5.6f * u, cx + 2f * u, cy - 5.6f * u, w, c);
                Gfx.line(g, cx - 4.2f * u, cy - 3.6f * u, cx - 3.6f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx + 4.2f * u, cy - 3.6f * u, cx + 3.6f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx - 3.6f * u, cy + 6f * u, cx + 3.6f * u, cy + 6f * u, w, c);
                break;
            }
            case "check": {
                Gfx.line(g, cx - 5f * u, cy + 0.4f * u, cx - 1.4f * u, cy + 4.2f * u, w * 1.25f, c);
                Gfx.line(g, cx - 1.4f * u, cy + 4.2f * u, cx + 5.4f * u, cy - 4.4f * u, w * 1.25f, c);
                break;
            }
            case "chev-right": {
                Gfx.line(g, cx - 1.8f * u, cy - 4.6f * u, cx + 2.4f * u, cy, w, c);
                Gfx.line(g, cx + 2.4f * u, cy, cx - 1.8f * u, cy + 4.6f * u, w, c);
                break;
            }
            case "chev-left": {
                Gfx.line(g, cx + 1.8f * u, cy - 4.6f * u, cx - 2.4f * u, cy, w, c);
                Gfx.line(g, cx - 2.4f * u, cy, cx + 1.8f * u, cy + 4.6f * u, w, c);
                break;
            }
            case "nav": { // sageata de navigatie (triunghi cu crestatura)
                Gfx.poly(g, new float[] {cx, cy - 6.6f * u, cx + 5.4f * u, cy + 6.2f * u, cx, cy + 2.8f * u, cx - 5.4f * u, cy + 6.2f * u}, c);
                break;
            }
            case "flag": {
                Gfx.line(g, cx - 4.2f * u, cy - 6.2f * u, cx - 4.2f * u, cy + 6.4f * u, w, c);
                Gfx.poly(g, new float[] {cx - 3.2f * u, cy - 5.6f * u, cx + 5.4f * u, cy - 3f * u, cx - 3.2f * u, cy - 0.4f * u}, c);
                break;
            }
            case "compass": {
                ringStroke(g, cx, cy, 6.6f * u, w, c);
                Gfx.poly(g, new float[] {cx, cy - 4.4f * u, cx + 2.2f * u, cy + 0.4f * u, cx, cy - 0.6f * u}, c);
                Gfx.poly(g, new float[] {cx, cy + 4.4f * u, cx - 2.2f * u, cy - 0.4f * u, cx, cy + 0.6f * u}, GpsTheme.alpha(c, 110));
                break;
            }
            case "eye": {
                Gfx.line(g, cx - 6.6f * u, cy, cx - 2.6f * u, cy - 3.6f * u, w, c);
                Gfx.line(g, cx - 2.6f * u, cy - 3.6f * u, cx + 2.6f * u, cy - 3.6f * u, w, c);
                Gfx.line(g, cx + 2.6f * u, cy - 3.6f * u, cx + 6.6f * u, cy, w, c);
                Gfx.line(g, cx + 6.6f * u, cy, cx + 2.6f * u, cy + 3.6f * u, w, c);
                Gfx.line(g, cx + 2.6f * u, cy + 3.6f * u, cx - 2.6f * u, cy + 3.6f * u, w, c);
                Gfx.line(g, cx - 2.6f * u, cy + 3.6f * u, cx - 6.6f * u, cy, w, c);
                Gfx.disc(g, cx, cy, 1.9f * u, c);
                break;
            }
            case "grid": {
                for (int i = -1; i <= 1; i += 2) {
                    Gfx.line(g, cx + i * 2.2f * u, cy - 6.2f * u, cx + i * 2.2f * u, cy + 6.2f * u, w * 0.85f, c);
                    Gfx.line(g, cx - 6.2f * u, cy + i * 2.2f * u, cx + 6.2f * u, cy + i * 2.2f * u, w * 0.85f, c);
                }
                break;
            }
            case "bell": {
                Gfx.line(g, cx - 5f * u, cy + 3f * u, cx + 5f * u, cy + 3f * u, w, c);
                Gfx.line(g, cx - 4f * u, cy + 3f * u, cx - 4f * u, cy - 1f * u, w, c);
                Gfx.line(g, cx + 4f * u, cy + 3f * u, cx + 4f * u, cy - 1f * u, w, c);
                arcStroke(g, cx, cy - 1f * u, 4f * u, 180f, 360f, w, c);
                Gfx.line(g, cx, cy - 5f * u, cx, cy - 6.4f * u, w, c);
                Gfx.line(g, cx - 1.6f * u, cy + 3f * u, cx - 1.6f * u, cy + 5.4f * u, w, c);
                Gfx.line(g, cx + 1.6f * u, cy + 3f * u, cx + 1.6f * u, cy + 5.4f * u, w, c);
                Gfx.line(g, cx - 1.6f * u, cy + 5.4f * u, cx + 1.6f * u, cy + 5.4f * u, w, c);
                break;
            }
            case "pencil": {
                Gfx.line(g, cx - 5.6f * u, cy + 5.6f * u, cx - 4.4f * u, cy + 1.6f * u, w, c);
                Gfx.line(g, cx - 4.4f * u, cy + 1.6f * u, cx + 3.4f * u, cy - 6.2f * u, w, c);
                Gfx.line(g, cx + 3.4f * u, cy - 6.2f * u, cx + 6f * u, cy - 3.6f * u, w, c);
                Gfx.line(g, cx + 6f * u, cy - 3.6f * u, cx - 1.8f * u, cy + 4.2f * u, w, c);
                Gfx.line(g, cx - 1.8f * u, cy + 4.2f * u, cx - 5.6f * u, cy + 5.6f * u, w, c);
                break;
            }
            default:
                Gfx.disc(g, cx, cy, 2.4f * u, c);
                break;
        }
    }

    /** Cerc desenat din segmente (contur, nu plin). */
    static void ringStroke(GuiGraphics g, float cx, float cy, float r, float w, int c) {
        arcStroke(g, cx, cy, r, 0f, 360f, w, c);
    }

    /** Arc desenat din segmente scurte; unghiurile sunt in grade, 0 = sus, sensul acelor de ceas. */
    static void arcStroke(GuiGraphics g, float cx, float cy, float r, float a0, float a1, float w, int c) {
        int steps = Math.max(8, (int) (r * 1.5f));
        float px = 0;
        float py = 0;
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(a0 + (a1 - a0) * i / (double) steps - 90.0);
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (i > 0) Gfx.line(g, px, py, x, y, w, c);
            px = x;
            py = y;
        }
    }

    private static float[] starPoints(float cx, float cy, float ro, float ri) {
        float[] p = new float[20];
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36);
            float r = (i % 2 == 0) ? ro : ri;
            p[i * 2] = cx + (float) Math.cos(a) * r;
            p[i * 2 + 1] = cy + (float) Math.sin(a) * r;
        }
        return p;
    }

    /** Stea plina (triunghiuri din centru, ca sa fie convexa corect). */
    static void starShape(GuiGraphics g, float cx, float cy, float ro, float ri, int c) {
        float[] p = starPoints(cx, cy, ro, ri);
        for (int i = 0; i < 10; i++) {
            int j = (i + 1) % 10;
            Gfx.poly(g, new float[] {cx, cy, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]}, c);
        }
    }

    /** Pastila rotunda cu iconita unei categorii, pe fundalul culorii ei. */
    static void badge(GuiGraphics g, String icon, float cx, float cy, float r, int color) {
        Gfx.disc(g, cx, cy, r, GpsTheme.alpha(color, 38));
        Gfx.disc(g, cx, cy, r - 1.2f, GpsTheme.alpha(color, 26));
        icon(g, icon, cx, cy, r * 1.15f, color);
    }

    // ------------------------------------------------------------------ butoane

    static final int PRIMARY = 0;
    static final int GHOST = 1;
    static final int DANGER = 2;
    static final int SOFT = 3;

    /**
     * Buton pastila cu text si, optional, iconita. Intoarce true daca mouse-ul e deasupra
     * (apelantul inregistreaza zona de click separat, ca sa pastreze ordinea desenului).
     */
    static boolean button(GuiGraphics g, String id, String label, String ic, float x, float y, float w, float h,
                          int style, boolean enabled, double mx, double my) {
        boolean hov = enabled && in(mx, my, x, y, w, h);
        float t = hot(id, hov);
        float r = Math.min(h / 2f, GpsTheme.R_MD);
        int fill;
        int line;
        int fg;
        switch (style) {
            case PRIMARY:
                fill = enabled ? GpsTheme.mix(GpsTheme.ACCENT, 0xFFA78BFA, t) : GpsTheme.PANEL;
                line = fill;
                fg = enabled ? 0xFFFFFFFF : GpsTheme.FAINT;
                break;
            case DANGER:
                fill = GpsTheme.mix(GpsTheme.alpha(GpsTheme.DANGER, 26), GpsTheme.alpha(GpsTheme.DANGER, 60), t);
                line = GpsTheme.mix(GpsTheme.alpha(GpsTheme.DANGER, 90), GpsTheme.DANGER, t);
                fg = enabled ? GpsTheme.mix(0xFFF28B8B, 0xFFFFFFFF, t) : GpsTheme.FAINT;
                break;
            case SOFT:
                fill = GpsTheme.mix(GpsTheme.PANEL, GpsTheme.PANEL_HI, t);
                line = GpsTheme.mix(GpsTheme.LINE, GpsTheme.ACCENT_DIM, t * 0.8f);
                fg = enabled ? GpsTheme.mix(0xFFD6D9E0, GpsTheme.TEXT, t) : GpsTheme.FAINT;
                break;
            default:
                fill = GpsTheme.mix(0x00000000, GpsTheme.alpha(0xFFFFFF, 14), t);
                line = GpsTheme.mix(GpsTheme.LINE, 0xFF4A5261, t);
                fg = enabled ? GpsTheme.mix(GpsTheme.MUTED, GpsTheme.TEXT, t) : GpsTheme.FAINT;
                break;
        }
        if (style == PRIMARY && enabled) {
            Gfx.rrect(g, x, y + 1.5f, w, h, r, GpsTheme.alpha(0x000000, 40));
            Gfx.rrect(g, x, y, w, h, r, fill, GpsTheme.mix(fill, 0xFF000000, 0.14f));
        } else {
            Gfx.rrect(g, x, y, w, h, r, line);
            Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, r - 1f, fill);
        }
        float tw = Gfx.width(label, GpsTheme.T_BODY, true);
        float iw = ic == null ? 0f : h * 0.56f + 5f;
        float sx = x + (w - tw - iw) / 2f;
        if (ic != null) icon(g, ic, sx + h * 0.28f, y + h / 2f, h * 0.52f, fg);
        Gfx.text(g, label, sx + iw, y + h / 2f + GpsTheme.T_BODY * 0.36f, GpsTheme.T_BODY, fg, 0, true, 0.1f);
        return hov;
    }

    /** Buton patrat, doar iconita (pentru controalele hartii). */
    static boolean iconButton(GuiGraphics g, String id, String ic, float x, float y, float s, boolean active, double mx, double my) {
        boolean hov = in(mx, my, x, y, s, s);
        float t = hot(id, hov);
        int fill = active ? GpsTheme.mix(GpsTheme.ACCENT_DIM, GpsTheme.ACCENT, t) : GpsTheme.mix(GpsTheme.GLASS, GpsTheme.GLASS_HI, t);
        int line = active ? GpsTheme.ACCENT : GpsTheme.mix(GpsTheme.LINE, 0xFF4A5261, t);
        GpsTheme.panel(g, x, y, s, s, GpsTheme.R_MD, fill, line);
        icon(g, ic, x + s / 2f, y + s / 2f, s * 0.46f, active ? 0xFFFFFFFF : GpsTheme.mix(0xFFCBD2DC, GpsTheme.TEXT, t));
        return hov;
    }

    /** Fila/pastila de filtru. */
    static boolean chip(GuiGraphics g, String id, String label, float x, float y, float w, float h, boolean sel, int dot, double mx, double my) {
        boolean hov = in(mx, my, x, y, w, h);
        float t = hot(id, hov);
        int fill = sel ? GpsTheme.alpha(GpsTheme.ACCENT, 46) : GpsTheme.mix(GpsTheme.BG2, GpsTheme.PANEL_HI, t);
        int line = sel ? GpsTheme.ACCENT : GpsTheme.mix(GpsTheme.LINE, 0xFF49505D, t);
        Gfx.rrect(g, x, y, w, h, h / 2f, line);
        Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, h / 2f - 1f, fill);
        float tx = x + h * 0.46f;
        if (dot != 0) {
            Gfx.disc(g, tx, y + h / 2f, 2.6f, dot);
            tx += 8f;
        }
        Gfx.text(g, label, tx, y + h / 2f + GpsTheme.T_SMALL * 0.36f, GpsTheme.T_SMALL,
            sel ? GpsTheme.TEXT : GpsTheme.mix(GpsTheme.MUTED, GpsTheme.TEXT, t), 0, true, 0.2f);
        return hov;
    }

    static float chipWidth(String label, float h, boolean dot) {
        return Gfx.width(label, GpsTheme.T_SMALL, true) + h * 0.92f + (dot ? 8f : 0f);
    }

    /** Comutator stil iOS. */
    static boolean toggle(GuiGraphics g, String id, float x, float y, boolean on, double mx, double my) {
        float w = 34f;
        float h = 18f;
        boolean hov = in(mx, my, x - 2f, y - 2f, w + 4f, h + 4f);
        float t = hot(id + ":on", on, 16f);
        float ht = hot(id, hov);
        int track = GpsTheme.mix(GpsTheme.mix(GpsTheme.PANEL, GpsTheme.PANEL_HI, ht), GpsTheme.ACCENT, t);
        Gfx.rrect(g, x, y, w, h, h / 2f, track);
        if (t < 0.5f) Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, h / 2f - 1f, GpsTheme.mix(GpsTheme.BG, track, t * 2f));
        float kx = x + 2f + t * (w - h);
        Gfx.disc(g, kx + (h - 4f) / 2f, y + h / 2f, (h - 4f) / 2f, GpsTheme.mix(0xFF8C93A1, 0xFFFFFFFF, t));
        return hov;
    }

    /** Bara de derulare subtire, doar cand e nevoie. */
    static void scrollbar(GuiGraphics g, float x, float y, float h, float content, float view, float offset) {
        if (content <= view + 1f) return;
        float th = Math.max(26f, h * view / content);
        float ty = y + (h - th) * Math.max(0f, Math.min(1f, offset / (content - view)));
        Gfx.rrect(g, x, y, 3f, h, 1.5f, GpsTheme.alpha(0xFFFFFF, 10));
        Gfx.rrect(g, x, ty, 3f, th, 1.5f, GpsTheme.alpha(0xFFFFFF, 46));
    }

    /** Stare goala: iconita estompata, titlu, explicatie. */
    static void empty(GuiGraphics g, String ic, String title, String sub, float cx, float cy, float maxW) {
        Gfx.disc(g, cx, cy - 16f, 23f, GpsTheme.alpha(0xFFFFFF, 8));
        icon(g, ic, cx, cy - 16f, 22f, GpsTheme.alpha(GpsTheme.MUTED, 130));
        Gfx.text(g, title, cx, cy + 18f, GpsTheme.T_H2, GpsTheme.TEXT, 1, true, 0.2f);
        Gfx.text(g, clip(sub, GpsTheme.T_SMALL, maxW), cx, cy + 33f, GpsTheme.T_SMALL, GpsTheme.MUTED, 1, true, 0f);
    }

    /** Trei puncte care pulseaza, pentru datele inca nesosite de la server. */
    static void loading(GuiGraphics g, float cx, float cy, float t, int color) {
        for (int i = 0; i < 3; i++) {
            float p = (t * 1.6f - i * 0.18f) % 1f;
            float s = 2.4f + 1.6f * (float) Math.max(0, Math.sin(p * Math.PI));
            Gfx.disc(g, cx - 10f + i * 10f, cy, s, GpsTheme.fade(color, 0.45f + 0.55f * (float) Math.max(0, Math.sin(p * Math.PI))));
        }
    }

    /** Taie textul la latimea data, cu "..." la final. */
    static String clip(String s, float size, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, true) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "…", size, true) > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
