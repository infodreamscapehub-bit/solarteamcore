package ro.solarstudios.flowcity.gps;

import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Identitatea vizuala a GPS-ului: numele scurte folosite de ecranele GPS, legate de design system-ul global
 * ({@link FTheme}). Accentul si opacitatea alese de jucator se trimit spre tema globala in
 * {@link GpsUi#frame(float)}, la inceputul fiecarui cadru.
 */
final class GpsTheme {
    private GpsTheme() {}

    static final int BG = FTheme.BG;
    static final int BG2 = FTheme.BG2;
    static final int PANEL = FTheme.PANEL;
    static final int PANEL_HI = FTheme.PANEL_HI;
    static final int ACCENT = FTheme.ACCENT;
    static final int ACCENT_DIM = FTheme.ACCENT_DIM;
    static final int SKY = FTheme.SKY;
    static final int TEXT = FTheme.TEXT;
    static final int MUTED = FTheme.MUTED;
    static final int FAINT = FTheme.FAINT;
    static final int DANGER = FTheme.DANGER;
    static final int OK = FTheme.OK;
    static final int GOLD = FTheme.GOLD;
    static final int LINE = FTheme.LINE;
    static final int LINE_SOFT = FTheme.LINE_SOFT;
    static final int GLASS = FTheme.GLASS;
    static final int GLASS_HI = FTheme.GLASS_HI;
    static final int SCRIM = FTheme.SCRIM;

    static final float S1 = FTheme.S1;
    static final float S2 = FTheme.S2;
    static final float S3 = FTheme.S3;
    static final float S4 = FTheme.S4;
    static final float S5 = FTheme.S5;
    static final float R_SM = FTheme.R_SM;
    static final float R_MD = FTheme.R_MD;
    static final float R_LG = FTheme.R_LG;

    static final float T_H1 = FTheme.T_H1;
    static final float T_H2 = FTheme.T_H2;
    static final float T_BODY = FTheme.T_BODY;
    static final float T_SMALL = FTheme.T_SMALL;
    static final float T_CAPTION = FTheme.T_CAPTION;
    static final float SP_CAPTION = FTheme.SP_CAPTION;

    static float to(float cur, float target, float dt, float speed) {
        return FTheme.to(cur, target, dt, speed);
    }

    static float ease(float t) {
        return FTheme.ease(t);
    }

    static int fade(int argb, float a) {
        return FTheme.fade(argb, a);
    }

    static int alpha(int argb, int a) {
        return FTheme.alpha(argb, a);
    }

    static int mix(int a, int b, float t) {
        return FTheme.mix(a, b, t);
    }

    static void shadow(GuiGraphics g, float x, float y, float w, float h, float r) {
        FTheme.shadow(g, x, y, w, h, r);
    }

    static int op(int argb) {
        return FTheme.op(argb);
    }

    static void panel(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int line) {
        FTheme.panel(g, x, y, w, h, r, fill, line);
    }

    static void glass(GuiGraphics g, float x, float y, float w, float h, float r) {
        FTheme.glass(g, x, y, w, h, r);
    }

    static void card(GuiGraphics g, float x, float y, float w, float h, float r, float hot) {
        FTheme.card(g, x, y, w, h, r, hot);
    }

    static void divider(GuiGraphics g, float x, float y, float w) {
        FTheme.divider(g, x, y, w);
    }

    static void caption(GuiGraphics g, String s, float x, float baseline, int color) {
        FTheme.caption(g, s, x, baseline, color);
    }
}
