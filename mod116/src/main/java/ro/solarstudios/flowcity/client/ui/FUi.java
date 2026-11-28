package ro.solarstudios.flowcity.client.ui;

import java.util.HashMap;
import java.util.Map;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Componentele reutilizabile FlowCity (design system global): butoane, file, comutatoare, carduri, bara de derulare, stari goale,
 * plus setul de iconite vectoriale (desenate din linii si discuri, cu aceeasi grosime de traseu).
 *
 * <p>Fiecare componenta primeste un {@code id} ca sa-si tina minte animatia de hover intre cadre
 * (vezi {@link #hot(String, boolean, float)}); desenul ramane imediat, fara obiecte per cadru.
 */
public final class FUi {
    private FUi() {}

    // ------------------------------------------------------------------ animatii de hover

    private static final Map<String, Float> HOT = new HashMap<>();
    private static float dt = 0.016f;

    /** Se apeleaza o data pe cadru, inainte de desen. */
    public static void frame(float delta) {
        dt = delta;
        if (HOT.size() > 600) HOT.clear();
    }

    /** Valoarea 0..1 a starii de hover pentru elementul cu id-ul dat (tranzitie ~150ms). */
    public static float hot(String id, boolean on, float speed) {
        float cur = HOT.getOrDefault(id, on ? 1f : 0f);
        float v = FTheme.to(cur, on ? 1f : 0f, dt, speed);
        if (Math.abs(v - (on ? 1f : 0f)) < 0.002f) v = on ? 1f : 0f;
        HOT.put(id, v);
        return v;
    }

    public static float hot(String id, boolean on) {
        return hot(id, on, 14f);
    }

    public static boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ iconite vectoriale

    /**
     * Iconita vectoriala centrata in (cx, cy), inscrisa intr-un patrat de latura {@code s}.
     * Grosimea traseului e proportionala cu marimea, ca sa arate la fel la orice scara.
     */
    public static void icon(GuiGraphics g, String name, float cx, float cy, float s, int c) {
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
            case "chev-down": {
                Gfx.line(g, cx - 4.6f * u, cy - 1.8f * u, cx, cy + 2.4f * u, w, c);
                Gfx.line(g, cx, cy + 2.4f * u, cx + 4.6f * u, cy - 1.8f * u, w, c);
                break;
            }
            case "chev-up": {
                Gfx.line(g, cx - 4.6f * u, cy + 1.8f * u, cx, cy - 2.4f * u, w, c);
                Gfx.line(g, cx, cy - 2.4f * u, cx + 4.6f * u, cy + 1.8f * u, w, c);
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
                Gfx.poly(g, new float[] {cx, cy + 4.4f * u, cx - 2.2f * u, cy - 0.4f * u, cx, cy + 0.6f * u}, FTheme.alpha(c, 110));
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
            case "card": {
                Gfx.line(g, cx - 6.4f * u, cy - 4f * u, cx + 6.4f * u, cy - 4f * u, w, c);
                Gfx.line(g, cx + 6.4f * u, cy - 4f * u, cx + 6.4f * u, cy + 4.6f * u, w, c);
                Gfx.line(g, cx + 6.4f * u, cy + 4.6f * u, cx - 6.4f * u, cy + 4.6f * u, w, c);
                Gfx.line(g, cx - 6.4f * u, cy + 4.6f * u, cx - 6.4f * u, cy - 4f * u, w, c);
                Gfx.line(g, cx - 6.4f * u, cy - 1f * u, cx + 6.4f * u, cy - 1f * u, w * 1.5f, c);
                Gfx.line(g, cx - 4.4f * u, cy + 2.6f * u, cx - 1.6f * u, cy + 2.6f * u, w, c);
                break;
            }
            case "lock": {
                Gfx.line(g, cx - 4.6f * u, cy - 1f * u, cx + 4.6f * u, cy - 1f * u, w, c);
                Gfx.line(g, cx + 4.6f * u, cy - 1f * u, cx + 4.6f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx + 4.6f * u, cy + 6f * u, cx - 4.6f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx - 4.6f * u, cy + 6f * u, cx - 4.6f * u, cy - 1f * u, w, c);
                arcStroke(g, cx, cy - 1f * u, 3f * u, 180f, 360f, w, c);
                break;
            }
            case "unlock": {
                Gfx.line(g, cx - 4.6f * u, cy - 1f * u, cx + 4.6f * u, cy - 1f * u, w, c);
                Gfx.line(g, cx + 4.6f * u, cy - 1f * u, cx + 4.6f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx + 4.6f * u, cy + 6f * u, cx - 4.6f * u, cy + 6f * u, w, c);
                Gfx.line(g, cx - 4.6f * u, cy + 6f * u, cx - 4.6f * u, cy - 1f * u, w, c);
                arcStroke(g, cx - 1.6f * u, cy - 1f * u, 3f * u, 180f, 360f, w, c);
                Gfx.line(g, cx + 1.4f * u, cy - 1f * u, cx + 1.4f * u, cy - 3.2f * u, w, c);
                break;
            }
            case "user": {
                ringStroke(g, cx, cy - 2.6f * u, 3f * u, w, c);
                arcStroke(g, cx, cy + 7f * u, 6f * u, 200f, 340f, w, c);
                break;
            }
            case "send": {
                Gfx.line(g, cx - 6f * u, cy, cx + 6f * u, cy, w, c);
                Gfx.line(g, cx + 6f * u, cy, cx + 2f * u, cy - 4f * u, w, c);
                Gfx.line(g, cx + 6f * u, cy, cx + 2f * u, cy + 4f * u, w, c);
                break;
            }
            case "list": {
                for (int i = -1; i <= 1; i++) {
                    Gfx.line(g, cx - 6f * u, cy + i * 4.2f * u, cx - 4.6f * u, cy + i * 4.2f * u, w * 1.3f, c);
                    Gfx.line(g, cx - 2.4f * u, cy + i * 4.2f * u, cx + 6f * u, cy + i * 4.2f * u, w, c);
                }
                break;
            }
            case "shield": {
                Gfx.line(g, cx - 5f * u, cy - 5f * u, cx + 5f * u, cy - 5f * u, w, c);
                Gfx.line(g, cx - 5f * u, cy - 5f * u, cx - 5f * u, cy + 1f * u, w, c);
                Gfx.line(g, cx + 5f * u, cy - 5f * u, cx + 5f * u, cy + 1f * u, w, c);
                Gfx.line(g, cx - 5f * u, cy + 1f * u, cx, cy + 6.6f * u, w, c);
                Gfx.line(g, cx + 5f * u, cy + 1f * u, cx, cy + 6.6f * u, w, c);
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
    public static void ringStroke(GuiGraphics g, float cx, float cy, float r, float w, int c) {
        arcStroke(g, cx, cy, r, 0f, 360f, w, c);
    }

    /** Arc desenat din segmente scurte; unghiurile sunt in grade, 0 = sus, sensul acelor de ceas. */
    public static void arcStroke(GuiGraphics g, float cx, float cy, float r, float a0, float a1, float w, int c) {
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
    public static void starShape(GuiGraphics g, float cx, float cy, float ro, float ri, int c) {
        float[] p = starPoints(cx, cy, ro, ri);
        for (int i = 0; i < 10; i++) {
            int j = (i + 1) % 10;
            Gfx.poly(g, new float[] {cx, cy, p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1]}, c);
        }
    }

    /** Pastila rotunda cu iconita unei categorii, pe fundalul culorii ei. */
    public static void badge(GuiGraphics g, String icon, float cx, float cy, float r, int color) {
        Gfx.disc(g, cx, cy, r, FTheme.alpha(color, 38));
        Gfx.disc(g, cx, cy, r - 1.2f, FTheme.alpha(color, 26));
        icon(g, icon, cx, cy, r * 1.15f, color);
    }

    // ------------------------------------------------------------------ butoane

    public static final int PRIMARY = 0;
    public static final int GHOST = 1;
    public static final int DANGER = 2;
    public static final int SOFT = 3;

    /**
     * Buton pastila cu text si, optional, iconita. Intoarce true daca mouse-ul e deasupra
     * (apelantul inregistreaza zona de click separat, ca sa pastreze ordinea desenului).
     */
    public static boolean button(GuiGraphics g, String id, String label, String ic, float x, float y, float w, float h,
                          int style, boolean enabled, double mx, double my) {
        boolean hov = enabled && in(mx, my, x, y, w, h);
        float t = hot(id, hov);
        float r = Math.min(h / 2f, FTheme.R_MD);
        int fill;
        int line;
        int fg;
        switch (style) {
            case PRIMARY:
                fill = enabled ? FTheme.mix(FTheme.accent, FTheme.mix(FTheme.accent, 0xFFFFFFFF, 0.28f), t) : FTheme.PANEL;
                line = fill;
                fg = enabled ? 0xFFFFFFFF : FTheme.FAINT;
                break;
            case DANGER:
                fill = FTheme.mix(FTheme.alpha(FTheme.DANGER, 26), FTheme.alpha(FTheme.DANGER, 60), t);
                line = FTheme.mix(FTheme.alpha(FTheme.DANGER, 90), FTheme.DANGER, t);
                fg = enabled ? FTheme.mix(0xFFF28B8B, 0xFFFFFFFF, t) : FTheme.FAINT;
                break;
            case SOFT:
                fill = FTheme.mix(FTheme.PANEL, FTheme.PANEL_HI, t);
                line = FTheme.mix(FTheme.LINE, FTheme.mix(FTheme.accent, 0xFF000000, 0.22f), t * 0.8f);
                fg = enabled ? FTheme.mix(0xFFD6D9E0, FTheme.TEXT, t) : FTheme.FAINT;
                break;
            default:
                fill = FTheme.mix(0x00000000, FTheme.alpha(0xFFFFFF, 14), t);
                line = FTheme.mix(FTheme.LINE, 0xFF4A5261, t);
                fg = enabled ? FTheme.mix(FTheme.MUTED, FTheme.TEXT, t) : FTheme.FAINT;
                break;
        }
        if (style == PRIMARY && enabled) {
            Gfx.rrect(g, x, y + 1.5f, w, h, r, FTheme.alpha(0x000000, 40));
            Gfx.rrect(g, x, y, w, h, r, fill, FTheme.mix(fill, 0xFF000000, 0.14f));
        } else {
            Gfx.rrect(g, x, y, w, h, r, line);
            Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, r - 1f, fill);
        }
        float tw = Gfx.width(label, FTheme.T_BODY, true);
        float iw = ic == null ? 0f : h * 0.56f + 5f;
        float sx = x + (w - tw - iw) / 2f;
        if (ic != null) icon(g, ic, sx + h * 0.28f, y + h / 2f, h * 0.52f, fg);
        Gfx.text(g, label, sx + iw, y + h / 2f + FTheme.T_BODY * 0.36f, FTheme.T_BODY, fg, 0, true, 0.1f);
        return hov;
    }

    /** Buton patrat, doar iconita (pentru controalele hartii). */
    public static boolean iconButton(GuiGraphics g, String id, String ic, float x, float y, float s, boolean active, double mx, double my) {
        boolean hov = in(mx, my, x, y, s, s);
        float t = hot(id, hov);
        int fill = active ? FTheme.mix(FTheme.mix(FTheme.accent, 0xFF000000, 0.22f), FTheme.accent, t) : FTheme.mix(FTheme.GLASS, FTheme.GLASS_HI, t);
        int line = active ? FTheme.accent : FTheme.mix(FTheme.LINE, 0xFF4A5261, t);
        FTheme.panel(g, x, y, s, s, FTheme.R_MD, fill, line);
        icon(g, ic, x + s / 2f, y + s / 2f, s * 0.46f, active ? 0xFFFFFFFF : FTheme.mix(0xFFCBD2DC, FTheme.TEXT, t));
        return hov;
    }

    /** Fila/pastila de filtru. */
    public static boolean chip(GuiGraphics g, String id, String label, float x, float y, float w, float h, boolean sel, int dot, double mx, double my) {
        boolean hov = in(mx, my, x, y, w, h);
        float t = hot(id, hov);
        int fill = sel ? FTheme.alpha(FTheme.accent, 46) : FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, t);
        int line = sel ? FTheme.accent : FTheme.mix(FTheme.LINE, 0xFF49505D, t);
        Gfx.rrect(g, x, y, w, h, h / 2f, line);
        Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, h / 2f - 1f, fill);
        float tx = x + h * 0.46f;
        if (dot != 0) {
            Gfx.disc(g, tx, y + h / 2f, 2.6f, dot);
            tx += 8f;
        }
        Gfx.text(g, label, tx, y + h / 2f + FTheme.T_SMALL * 0.36f, FTheme.T_SMALL,
            sel ? FTheme.TEXT : FTheme.mix(FTheme.MUTED, FTheme.TEXT, t), 0, true, 0.2f);
        return hov;
    }

    public static float chipWidth(String label, float h, boolean dot) {
        return Gfx.width(label, FTheme.T_SMALL, true) + h * 0.92f + (dot ? 8f : 0f);
    }

    /** Comutator stil iOS. */
    public static boolean toggle(GuiGraphics g, String id, float x, float y, boolean on, double mx, double my) {
        float w = 34f;
        float h = 18f;
        boolean hov = in(mx, my, x - 2f, y - 2f, w + 4f, h + 4f);
        float t = hot(id + ":on", on, 16f);
        float ht = hot(id, hov);
        int track = FTheme.mix(FTheme.mix(FTheme.PANEL, FTheme.PANEL_HI, ht), FTheme.accent, t);
        Gfx.rrect(g, x, y, w, h, h / 2f, track);
        if (t < 0.5f) Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, h / 2f - 1f, FTheme.mix(FTheme.BG, track, t * 2f));
        float kx = x + 2f + t * (w - h);
        Gfx.disc(g, kx + (h - 4f) / 2f, y + h / 2f, (h - 4f) / 2f, FTheme.mix(0xFF8C93A1, 0xFFFFFFFF, t));
        return hov;
    }

    /** Bara de derulare subtire, doar cand e nevoie. */
    public static void scrollbar(GuiGraphics g, float x, float y, float h, float content, float view, float offset) {
        if (content <= view + 1f) return;
        float th = Math.max(26f, h * view / content);
        float ty = y + (h - th) * Math.max(0f, Math.min(1f, offset / (content - view)));
        Gfx.rrect(g, x, y, 3f, h, 1.5f, FTheme.alpha(0xFFFFFF, 10));
        Gfx.rrect(g, x, ty, 3f, th, 1.5f, FTheme.alpha(0xFFFFFF, 46));
    }

    /** Stare goala: iconita estompata, titlu, explicatie. */
    public static void empty(GuiGraphics g, String ic, String title, String sub, float cx, float cy, float maxW) {
        Gfx.disc(g, cx, cy - 16f, 23f, FTheme.alpha(0xFFFFFF, 8));
        icon(g, ic, cx, cy - 16f, 22f, FTheme.alpha(FTheme.MUTED, 130));
        Gfx.text(g, title, cx, cy + 18f, FTheme.T_H2, FTheme.TEXT, 1, true, 0.2f);
        Gfx.text(g, clip(sub, FTheme.T_SMALL, maxW), cx, cy + 33f, FTheme.T_SMALL, FTheme.MUTED, 1, true, 0f);
    }

    /** Trei puncte care pulseaza, pentru datele inca nesosite de la server. */
    public static void loading(GuiGraphics g, float cx, float cy, float t, int color) {
        for (int i = 0; i < 3; i++) {
            float p = (t * 1.6f - i * 0.18f) % 1f;
            float s = 2.4f + 1.6f * (float) Math.max(0, Math.sin(p * Math.PI));
            Gfx.disc(g, cx - 10f + i * 10f, cy, s, FTheme.fade(color, 0.45f + 0.55f * (float) Math.max(0, Math.sin(p * Math.PI))));
        }
    }

    /** Taie textul la latimea data, cu "..." la final. */
    public static String clip(String s, float size, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, true) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "…", size, true) > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    // ------------------------------------------------------------------ carduri, antete, cautare

    /** Card de continut, cu animatie de hover dupa id. Intoarce true daca mouse-ul e deasupra. */
    public static boolean card(GuiGraphics g, String id, float x, float y, float w, float h, boolean enabled, double mx, double my) {
        boolean hov = enabled && in(mx, my, x, y, w, h);
        FTheme.card(g, x, y, w, h, FTheme.R_MD, hot(id, hov));
        return hov;
    }

    /** Antet de pagina: titlu mare, subtitlu optional si o linie de separare sub el. */
    public static void header(GuiGraphics g, String title, String sub, float x, float y, float w) {
        Gfx.text(g, title, x, y + FTheme.T_H1, FTheme.T_H1, FTheme.TEXT, 0, true, 0.15f);
        if (sub != null && !sub.isEmpty()) {
            Gfx.text(g, clip(sub, FTheme.T_SMALL, w), x, y + FTheme.T_H1 + 11f, FTheme.T_SMALL, FTheme.MUTED, 0, true, 0f);
        }
        FTheme.divider(g, x, y + (sub == null || sub.isEmpty() ? 22f : 32f), w);
    }

    /**
     * Bara de cautare. {@code text} e continutul curent (gol = se arata sugestia), {@code focus} aprinde conturul.
     * Nu citeste tastatura: apelantul tine textul si deschide campul la click.
     */
    public static boolean searchBar(GuiGraphics g, String id, String text, String hint, float x, float y, float w, float h,
                                    boolean focus, double mx, double my) {
        boolean hov = in(mx, my, x, y, w, h);
        float t = hot(id, hov || focus);
        int line = focus ? FTheme.accent : FTheme.mix(FTheme.LINE, 0xFF49505D, t);
        FTheme.panel(g, x, y, w, h, h / 2f, FTheme.mix(FTheme.BG2, FTheme.PANEL, t), line);
        icon(g, "search", x + h * 0.56f, y + h / 2f, h * 0.46f, focus ? FTheme.accent : FTheme.MUTED);
        boolean empty = text == null || text.isEmpty();
        float tx = x + h * 0.98f;
        Gfx.text(g, clip(empty ? hint : text, FTheme.T_SMALL, w - h * 1.5f), tx, y + h / 2f + FTheme.T_SMALL * 0.36f,
            FTheme.T_SMALL, empty ? FTheme.FAINT : FTheme.TEXT, 0, true, 0f);
        return hov;
    }

    // ------------------------------------------------------------------ progres, slider, dropdown

    /** Bara de progres orizontala (0..1), cu colturi rotunde. */
    public static void progress(GuiGraphics g, float x, float y, float w, float h, float frac, int color) {
        frac = Math.max(0f, Math.min(1f, frac));
        Gfx.rrect(g, x, y, w, h, h / 2f, FTheme.alpha(0xFFFFFF, 18));
        if (frac > 0.001f) Gfx.rrect(g, x, y, Math.max(h, w * frac), h, h / 2f, color);
    }

    /** Bara de progres cu eticheta deasupra (stanga) si valoare (dreapta). */
    public static void progress(GuiGraphics g, String label, String value, float x, float y, float w, float frac, int color) {
        Gfx.text(g, label, x, y, FTheme.T_SMALL, FTheme.MUTED, 0, true, 0f);
        if (value != null) Gfx.text(g, value, x + w, y, FTheme.T_SMALL, FTheme.TEXT, 2, true, 0f);
        progress(g, x, y + 4f, w, 4f, frac, color);
    }

    /**
     * Slider orizontal. {@code v} e valoarea 0..1; intoarce valoarea noua cand se trage, altfel aceeasi.
     * {@code drag} spune daca butonul stang e apasat (apelantul il are din starea ecranului).
     */
    public static float slider(GuiGraphics g, String id, float x, float y, float w, float v, boolean drag, double mx, double my) {
        float h = 16f;
        boolean hov = in(mx, my, x - 4f, y - 4f, w + 8f, h + 8f);
        float t = hot(id, hov || drag);
        float out = v;
        if (drag && hov) out = Math.max(0f, Math.min(1f, (float) (mx - x) / Math.max(1f, w)));
        float cy = y + h / 2f;
        Gfx.rrect(g, x, cy - 2f, w, 4f, 2f, FTheme.alpha(0xFFFFFF, 16));
        Gfx.rrect(g, x, cy - 2f, Math.max(4f, w * out), 4f, 2f, FTheme.accent);
        float kx = x + w * out;
        Gfx.disc(g, kx, cy, 6f + t * 1.2f, FTheme.alpha(0x000000, 60));
        Gfx.disc(g, kx, cy, 5f + t * 1.2f, 0xFFFFFFFF);
        return out;
    }

    /**
     * Dropdown inchis (doar randul de selectie). Lista deschisa se deseneaza cu {@link #dropdownList}.
     * Intoarce true daca mouse-ul e deasupra randului.
     */
    public static boolean dropdown(GuiGraphics g, String id, String label, String value, float x, float y, float w, float h,
                                   boolean open, double mx, double my) {
        boolean hov = in(mx, my, x, y, w, h);
        float t = hot(id, hov || open);
        FTheme.panel(g, x, y, w, h, FTheme.R_SM, FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, t),
            open ? FTheme.accent : FTheme.mix(FTheme.LINE, 0xFF49505D, t));
        float tx = x + FTheme.S3;
        if (label != null && !label.isEmpty()) {
            Gfx.text(g, label, tx, y + h / 2f + FTheme.T_SMALL * 0.36f, FTheme.T_SMALL, FTheme.MUTED, 0, true, 0f);
            Gfx.text(g, clip(value, FTheme.T_SMALL, w - 40f), x + w - FTheme.S3 - 12f, y + h / 2f + FTheme.T_SMALL * 0.36f,
                FTheme.T_SMALL, FTheme.TEXT, 2, true, 0f);
        } else {
            Gfx.text(g, clip(value, FTheme.T_SMALL, w - 32f), tx, y + h / 2f + FTheme.T_SMALL * 0.36f, FTheme.T_SMALL, FTheme.TEXT, 0, true, 0f);
        }
        float ax = x + w - FTheme.S3;
        float ay = y + h / 2f;
        float d = open ? -1f : 1f;
        Gfx.line(g, ax - 4f, ay - 1.6f * d, ax, ay + 1.8f * d, 1.3f, FTheme.MUTED);
        Gfx.line(g, ax, ay + 1.8f * d, ax + 4f, ay - 1.6f * d, 1.3f, FTheme.MUTED);
        return hov;
    }

    /** Inaltimea listei deschise a unui dropdown cu {@code n} optiuni. */
    public static float dropdownHeight(int n, float row) {
        return n * row + 4f;
    }

    /**
     * Lista deschisa a unui dropdown. Intoarce indicele randului de sub mouse, sau -1.
     * Desenarea se face peste restul paginii, deci se apeleaza la final.
     */
    public static int dropdownList(GuiGraphics g, String id, String[] options, int sel, float x, float y, float w, float row,
                                   double mx, double my) {
        float h = dropdownHeight(options.length, row);
        FTheme.shadow(g, x, y, w, h, FTheme.R_SM);
        FTheme.panel(g, x, y, w, h, FTheme.R_SM, FTheme.PANEL, FTheme.LINE);
        int over = -1;
        for (int i = 0; i < options.length; i++) {
            float ry = y + 2f + i * row;
            boolean hov = in(mx, my, x + 2f, ry, w - 4f, row);
            if (hov) over = i;
            float t = hot(id + ":" + i, hov);
            if (t > 0.01f) Gfx.rrect(g, x + 2f, ry, w - 4f, row, FTheme.R_SM - 3f, FTheme.alpha(0xFFFFFF, (int) (14 * t)));
            Gfx.text(g, clip(options[i], FTheme.T_SMALL, w - 28f), x + FTheme.S3, ry + row / 2f + FTheme.T_SMALL * 0.36f,
                FTheme.T_SMALL, i == sel ? FTheme.TEXT : FTheme.MUTED, 0, true, 0f);
            if (i == sel) icon(g, "check", x + w - 12f, ry + row / 2f, 9f, FTheme.accent);
        }
        return over;
    }

    // ------------------------------------------------------------------ modal, erori

    /** Voal peste tot ecranul, inainte de un modal. */
    public static void scrim(GuiGraphics g, float x, float y, float w, float h, float t) {
        Gfx.rrect(g, x, y, w, h, 0f, FTheme.fade(FTheme.SCRIM, t));
    }

    /**
     * Fereastra de confirmare centrata, cu titlu, text si doua butoane. Deseneaza voalul singura.
     * Intoarce 1 daca mouse-ul e peste butonul principal, 2 peste cel secundar, 0 altfel;
     * dreptunghiurile de click se iau din {@link #modalPrimary} si {@link #modalSecondary}.
     */
    public static int modal(GuiGraphics g, String id, String title, String text, String okLabel, String cancelLabel,
                            boolean danger, float sx, float sy, float sw, float sh, float t, double mx, double my) {
        scrim(g, sx, sy, sw, sh, t);
        float w = Math.min(sw - FTheme.S5 * 2f, 260f);
        float h = 104f;
        float x = sx + (sw - w) / 2f;
        float y = sy + (sh - h) / 2f + (1f - t) * 10f;
        lastModal[0] = x;
        lastModal[1] = y;
        lastModal[2] = w;
        lastModal[3] = h;
        FTheme.shadow(g, x, y, w, h, FTheme.R_LG);
        FTheme.panel(g, x, y, w, h, FTheme.R_LG, FTheme.PANEL, FTheme.LINE);
        Gfx.text(g, clip(title, FTheme.T_H2, w - FTheme.S4 * 2f), x + w / 2f, y + FTheme.S5, FTheme.T_H2, FTheme.TEXT, 1, true, 0.15f);
        if (text != null) {
            Gfx.text(g, clip(text, FTheme.T_SMALL, w - FTheme.S4 * 2f), x + w / 2f, y + FTheme.S5 + 15f,
                FTheme.T_SMALL, FTheme.MUTED, 1, true, 0f);
        }
        float bw = (w - FTheme.S4 * 2f - FTheme.S2) / 2f;
        float by = y + h - FTheme.S4 - 26f;
        boolean h2 = button(g, id + ":no", cancelLabel, null, x + FTheme.S4, by, bw, 26f, GHOST, true, mx, my);
        boolean h1 = button(g, id + ":ok", okLabel, null, x + FTheme.S4 + bw + FTheme.S2, by, bw, 26f,
            danger ? DANGER : PRIMARY, true, mx, my);
        lastModal[4] = x + FTheme.S4 + bw + FTheme.S2;
        lastModal[5] = x + FTheme.S4;
        lastModal[6] = by;
        lastModal[7] = bw;
        return h1 ? 1 : (h2 ? 2 : 0);
    }

    private static final float[] lastModal = new float[8];

    /** Dreptunghiul butonului principal al ultimului modal desenat: {x, y, w, h}. */
    public static float[] modalPrimary() {
        return new float[] {lastModal[4], lastModal[6], lastModal[7], 26f};
    }

    /** Dreptunghiul butonului secundar al ultimului modal desenat: {x, y, w, h}. */
    public static float[] modalSecondary() {
        return new float[] {lastModal[5], lastModal[6], lastModal[7], 26f};
    }

    /** Dreptunghiul ferestrei ultimului modal desenat: {x, y, w, h}. */
    public static float[] modalBounds() {
        return new float[] {lastModal[0], lastModal[1], lastModal[2], lastModal[3]};
    }

    public static final int MSG_ERROR = 0;
    public static final int MSG_OK = 1;
    public static final int MSG_INFO = 2;

    /** Banda de mesaj (eroare, reusita, informatie): bara colorata la stanga, iconita si text. */
    public static void message(GuiGraphics g, int kind, String text, float x, float y, float w) {
        int c = kind == MSG_ERROR ? FTheme.DANGER : (kind == MSG_OK ? FTheme.OK : FTheme.SKY);
        String ic = kind == MSG_ERROR ? "close" : (kind == MSG_OK ? "check" : "bell");
        float h = 26f;
        FTheme.panel(g, x, y, w, h, FTheme.R_SM, FTheme.alpha(c, 22), FTheme.alpha(c, 80));
        Gfx.rrect(g, x + 1f, y + 4f, 2.5f, h - 8f, 1.25f, c);
        icon(g, ic, x + 16f, y + h / 2f, 10f, c);
        Gfx.text(g, clip(text, FTheme.T_SMALL, w - 34f), x + 26f, y + h / 2f + FTheme.T_SMALL * 0.36f,
            FTheme.T_SMALL, FTheme.TEXT, 0, true, 0f);
    }
}
