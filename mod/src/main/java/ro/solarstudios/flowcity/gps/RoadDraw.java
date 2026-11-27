package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;

/** Desen comun al retelei de drumuri (harta GPS si editor): linii decupate pe ecran, sensuri unice, nume de strazi. */
final class RoadDraw {
    private RoadDraw() {}

    private static final int MAX_LINES = 7000;

    /** Deseneaza muchiile unei retele. Transformare: ecran = centru + (lume - cam) * zoom. */
    static void network(GuiGraphics g, RoadNet.Data d, double camX, double camZ, float zoom, float cx, float cy, float sw, float sh, int color, float thick, boolean oneWayArrows, java.util.Set<Integer> hot) {
        int n = 0;
        float margin = 30f;
        for (int[] e : d.edges) {
            int[] a = d.nodes.get(e[0]);
            int[] b = d.nodes.get(e[1]);
            if (a == null || b == null) continue;
            float ax = (float) (cx + (a[0] + 0.5 - camX) * zoom);
            float ay = (float) (cy + (a[1] + 0.5 - camZ) * zoom);
            float bx = (float) (cx + (b[0] + 0.5 - camX) * zoom);
            float by = (float) (cy + (b[1] + 0.5 - camZ) * zoom);
            if ((ax < -margin && bx < -margin) || (ax > sw + margin && bx > sw + margin) || (ay < -margin && by < -margin) || (ay > sh + margin && by > sh + margin)) continue;
            boolean h = hot != null && (hot.contains(e[0]) && hot.contains(e[1]));
            Gfx.line(g, ax, ay, bx, by, h ? thick + 1.2f : thick, h ? 0xFFFFC857 : color);
            if (oneWayArrows && (e[2] & 1) != 0) arrow(g, ax, ay, bx, by, 0xFFFFFFFF);
            if (++n >= MAX_LINES) return;
        }
    }

    private static void arrow(GuiGraphics g, float ax, float ay, float bx, float by, int color) {
        float dx = bx - ax;
        float dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 14f) return;
        float ux = dx / len;
        float uy = dy / len;
        float mx = (ax + bx) / 2f;
        float my = (ay + by) / 2f;
        Gfx.poly(g, new float[] {mx + ux * 5f, my + uy * 5f, mx - ux * 4f - uy * 4f, my - uy * 4f + ux * 4f, mx - ux * 4f + uy * 4f, my - uy * 4f - ux * 4f}, color);
    }

    /** Numele strazilor, la mijlocul segmentelor lungi (cel mult ~28 pe cadru, fara suprapuneri apropiate). */
    static void names(GuiGraphics g, RoadNet.Data d, double camX, double camZ, float zoom, float cx, float cy, float sw, float sh) {
        if (zoom < 0.9f) return;
        List<float[]> placed = new ArrayList<>();
        int count = 0;
        for (int[] e : d.edges) {
            if (e[3] < 0 || e[3] >= d.names.size()) continue;
            int[] a = d.nodes.get(e[0]);
            int[] b = d.nodes.get(e[1]);
            if (a == null || b == null) continue;
            float ax = (float) (cx + (a[0] + 0.5 - camX) * zoom);
            float ay = (float) (cy + (a[1] + 0.5 - camZ) * zoom);
            float bx = (float) (cx + (b[0] + 0.5 - camX) * zoom);
            float by = (float) (cy + (b[1] + 0.5 - camZ) * zoom);
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 60f) continue;
            float mx = (ax + bx) / 2f;
            float my = (ay + by) / 2f;
            if (mx < 20 || my < 20 || mx > sw - 20 || my > sh - 20) continue;
            boolean near = false;
            for (float[] p : placed) if (Math.abs(p[0] - mx) < 90 && Math.abs(p[1] - my) < 16) near = true;
            if (near) continue;
            placed.add(new float[] {mx, my});
            String s = d.names.get(e[3]);
            float w = Gfx.width(s, 6.6f, true) + 8f;
            Gfx.rrect(g, mx - w / 2f, my - 6f, w, 11f, 4f, 0xC0081018);
            Gfx.text(g, s, mx, my + 2.4f, 6.6f, 0xFFD6E4F5, 1, true, 0f);
            if (++count >= 28) return;
        }
    }
}
