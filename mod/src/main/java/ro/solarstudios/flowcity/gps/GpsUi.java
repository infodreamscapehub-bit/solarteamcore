package ro.solarstudios.flowcity.gps;

import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Componentele GPS-ului, legate de design system-ul global ({@link FUi}). Numele scurte raman, ca sa nu se
 * schimbe ecranele GPS; la inceputul cadrului se trimit spre tema globala accentul si opacitatea alese de jucator.
 */
final class GpsUi {
    private GpsUi() {}

    static final int PRIMARY = FUi.PRIMARY;
    static final int GHOST = FUi.GHOST;
    static final int DANGER = FUi.DANGER;
    static final int SOFT = FUi.SOFT;

    /** Se apeleaza o data pe cadru, inainte de desen. */
    static void frame(float delta) {
        FTheme.accent = GpsPrefs.accent();
        FTheme.opacity = GpsPrefs.opacity;
        FUi.frame(delta);
    }

    static float hot(String id, boolean on, float speed) {
        return FUi.hot(id, on, speed);
    }

    static float hot(String id, boolean on) {
        return FUi.hot(id, on);
    }

    static boolean in(double mx, double my, float x, float y, float w, float h) {
        return FUi.in(mx, my, x, y, w, h);
    }

    static void icon(GuiGraphics g, String name, float cx, float cy, float s, int c) {
        FUi.icon(g, name, cx, cy, s, c);
    }

    static void ringStroke(GuiGraphics g, float cx, float cy, float r, float w, int c) {
        FUi.ringStroke(g, cx, cy, r, w, c);
    }

    static void arcStroke(GuiGraphics g, float cx, float cy, float r, float a0, float a1, float w, int c) {
        FUi.arcStroke(g, cx, cy, r, a0, a1, w, c);
    }

    static void starShape(GuiGraphics g, float cx, float cy, float ro, float ri, int c) {
        FUi.starShape(g, cx, cy, ro, ri, c);
    }

    static void badge(GuiGraphics g, String icon, float cx, float cy, float r, int color) {
        FUi.badge(g, icon, cx, cy, r, color);
    }

    static boolean button(GuiGraphics g, String id, String label, String ic, float x, float y, float w, float h,
                          int style, boolean enabled, double mx, double my) {
        return FUi.button(g, id, label, ic, x, y, w, h, style, enabled, mx, my);
    }

    static boolean iconButton(GuiGraphics g, String id, String ic, float x, float y, float s, boolean active, double mx, double my) {
        return FUi.iconButton(g, id, ic, x, y, s, active, mx, my);
    }

    static boolean chip(GuiGraphics g, String id, String label, float x, float y, float w, float h, boolean sel, int dot, double mx, double my) {
        return FUi.chip(g, id, label, x, y, w, h, sel, dot, mx, my);
    }

    static float chipWidth(String label, float h, boolean dot) {
        return FUi.chipWidth(label, h, dot);
    }

    static boolean toggle(GuiGraphics g, String id, float x, float y, boolean on, double mx, double my) {
        return FUi.toggle(g, id, x, y, on, mx, my);
    }

    static void scrollbar(GuiGraphics g, float x, float y, float h, float content, float view, float offset) {
        FUi.scrollbar(g, x, y, h, content, view, offset);
    }

    static void empty(GuiGraphics g, String ic, String title, String sub, float cx, float cy, float maxW) {
        FUi.empty(g, ic, title, sub, cx, cy, maxW);
    }

    static void loading(GuiGraphics g, float cx, float cy, float t, int color) {
        FUi.loading(g, cx, cy, t, color);
    }

    static String clip(String s, float size, float maxW) {
        return FUi.clip(s, size, maxW);
    }
}
