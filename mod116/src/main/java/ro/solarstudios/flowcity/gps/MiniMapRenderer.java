package ro.solarstudios.flowcity.gps;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix4f;
import org.lwjgl.opengl.GL11;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Desenul minimap-ului FlowCity. Foloseste ACEEASI harta ca ecranul GPS ({@link GpsMap}: aceleasi dale, aceeasi transformare
 * X/Z -> ecran), aceleasi locatii/zone/iconite ({@link GpsData}, {@link GpsIcons}) si aceeasi destinatie ({@link GpsNav}).
 * Forma (patrat cu colturi taiate / cerc) se obtine prin decupare geometrica pe un poligon convex ({@link MiniMapClip}).
 * Tot ce se deseneaza e in coordonate locale 0..size, dupa o translatie la pozitia elementului de HUD.
 */
public final class MiniMapRenderer {
    private MiniMapRenderer() {}

    private static final int CIRCLE_N = 40;

    // ------------------------------------------------------------------ poligonul de decupare (cache)

    private static final float[] clip = new float[MiniMapClip.MAX * 2];
    private static int clipN;
    private static int clipSize = -1;
    private static boolean clipCircle;
    private static float clipRad;

    private static void ensureClip(int size, boolean circle) {
        if (size == clipSize && circle == clipCircle) return;
        clipSize = size;
        clipCircle = circle;
        float s = size;
        float r = s / 2f;
        if (circle) {
            clipN = CIRCLE_N;
            for (int i = 0; i < CIRCLE_N; i++) {
                double a = Math.PI * 2.0 * i / CIRCLE_N;
                clip[i * 2] = r + (float) Math.cos(a) * (r - 0.5f);
                clip[i * 2 + 1] = r + (float) Math.sin(a) * (r - 0.5f);
            }
            clipRad = r;
        } else {
            float c = Math.min(11f, s * 0.1f);
            float[] p = {c, 0, s - c, 0, s, c, s, s - c, s - c, s, c, s, 0, s - c, 0, c};
            clipN = 8;
            System.arraycopy(p, 0, clip, 0, p.length);
            clipRad = r * 1.4143f;
        }
    }

    // ------------------------------------------------------------------ pozitia implicita (sub panoul HUD, dreapta sus)

    private static final float[] DEF = new float[4];
    private static long defAt;
    private static int defSw;
    private static int defSh;
    private static int defSize;
    private static boolean defCoords;

    /** Dreptunghiul implicit {x, y, w, h}: colt dreapta-sus, sub panoul principal de HUD (nu se suprapune cu el). Vector comun, nu-l modifica. */
    public static float[] defaults(int sw, int sh) {
        long now = System.currentTimeMillis();
        if (now - defAt < 500 && sw == defSw && sh == defSh && defSize == MiniMapSettings.size && defCoords == MiniMapSettings.showCoords) return DEF;
        defAt = now;
        defSw = sw;
        defSh = sh;
        defSize = MiniMapSettings.size;
        defCoords = MiniMapSettings.showCoords;
        float w = MiniMapSettings.size;
        float h = w + (MiniMapSettings.showCoords ? 16f : 0f);
        float x = sw - w - 8f;
        float y = 6f;
        HudLayout.El hud = HudLayout.get("hud");
        if (hud != null && hud.visible) {
            float[] r = HudLayout.live(hud) ? HudLayout.rect("hud") : HudLayout.defaults("hud", sw, sh);
            if (r[0] + r[2] > x - 4f) y = Math.max(y, r[1] + r[3] + 6f);
        }
        if (y + h > sh - 40f) y = Math.max(4f, sh - 40f - h);
        DEF[0] = x;
        DEF[1] = y;
        DEF[2] = w;
        DEF[3] = h;
        return DEF;
    }

    // ------------------------------------------------------------------ locatii (cache, GpsData.all() aloca o lista noua)

    private static final List<GpsData.Loc> locs = new ArrayList<>();
    private static long locsAt;
    private static String locsDim = "";

    private static void refreshLocs(Minecraft mc) {
        long now = System.currentTimeMillis();
        long every = MiniMapSettings.lowPerf ? 3000L : 1000L;
        String dim = GpsData.dimOf(mc);
        if (now - locsAt < every && dim.equals(locsDim)) return;
        locsAt = now;
        locsDim = dim;
        locs.clear();
        if (!GpsPrefs.layerOn("locations")) return;
        for (GpsData.Loc l : GpsData.all()) {
            if (l.hidden || !l.dim.equals(dim)) continue;
            if (MiniMapSettings.hiddenCats.contains(l.cat)) continue;
            if (!GpsPrefs.layerOn(GpsData.locLayer(l))) continue;
            locs.add(l);
        }
    }

    // ------------------------------------------------------------------ geometrie curenta (setata la fiecare cadru)

    private static float cx;
    private static float cy;
    private static float zoom;
    private static double camX;
    private static double camZ;
    private static double ct = 1.0;
    private static double st;
    private static float outX;
    private static float outY;

    /** Proiecteaza o coordonata de lume pe minimap (rezultat in outX/outY). */
    private static void project(double wx, double wz) {
        double dx = (wx - camX) * zoom;
        double dz = (wz - camZ) * zoom;
        outX = (float) (cx + dx * ct - dz * st);
        outY = (float) (cy + dx * st + dz * ct);
    }

    // ------------------------------------------------------------------ desen principal

    private static final float[] zIn = new float[6];
    private static final float[] zOut = new float[2 * (MiniMapClip.MAX + 8)];
    private static final float[] zTmp = new float[2 * (MiniMapClip.MAX + 8)];
    private static long zoomShownUntil;

    /** Afiseaza scurt nivelul de zoom langa harta (apelat la schimbarea zoom-ului). */
    public static void noteZoom() {
        zoomShownUntil = System.currentTimeMillis() + 1400L;
    }

    /** Latimea x inaltimea ocupate (harta + linia de coordonate). */
    public static float heightFor(float size) {
        return size + (MiniMapSettings.showCoords ? 16f : 0f);
    }

    /**
     * Deseneaza minimap-ul cu coltul stanga-sus la (x, y), cu latura/diametrul size. Se apeleaza intre HudLayout.begin si end
     * (sau direct, pentru previzualizarea din setari).
     */
    public static void render(GuiGraphics g, float x, float y, float size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerEntity pl = mc.player;
        if (pl == null || mc.level == null) return;
        boolean circle = MiniMapSettings.circular;
        ensureClip(Math.round(size), circle);
        float pt = mc.getFrameTime();
        camX = MathHelper.lerp(pt, pl.xo, pl.getX());
        camZ = MathHelper.lerp(pt, pl.zo, pl.getZ());
        float yaw = pl.yRotO + MathHelper.wrapDegrees(pl.yRot - pl.yRotO) * pt;
        float r = size / 2f;
        cx = r;
        cy = r;
        zoom = MiniMapSettings.zoom;
        double theta = 0.0;
        if (MiniMapSettings.rotate) {
            double fx = -Math.sin(Math.toRadians(yaw));
            double fz = Math.cos(Math.toRadians(yaw));
            theta = -Math.PI / 2.0 - Math.atan2(fz, fx);
        }
        ct = Math.cos(theta);
        st = Math.sin(theta);
        float op = MiniMapSettings.opacity;

        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        try {
            // umbra + fundal inchis (zonele neexplorate raman intunecate)
            if (circle) {
                Gfx.disc(g, r, r + 1.5f, r + 1.5f, Gfx.fade(0x66000000, op));
            } else {
                Gfx.rrect(g, -1f, 1f, size + 2f, size + 2f, 6f, Gfx.fade(0x55000000, op));
            }
            fan(g, clip, clipN, Gfx.fade(0xFF0B1626, op));

            GpsMap.drawClipped(g, camX, camZ, zoom, cx, cy, theta, clip, clipN, clipRad, op * Gfx.alpha);

            if (MiniMapSettings.showZones && !MiniMapSettings.lowPerf) drawZones(g, mc, op);
            if (MiniMapSettings.showLocations) drawLocs(g, mc);
            drawWaypoint(g, mc);
            drawPlayer(g, yaw);
            drawFrame(g, size, circle);
            if (MiniMapSettings.showCompass) drawCompass(g, size, circle);
            drawZoomNote(g, size);
            if (MiniMapSettings.showCoords) {
                Sk.cut(g, 0f, size + 2f, size, 13f, 4f, Gfx.fade(0xD0081018, op));
                Gfx.rrect(g, 0f, size + 4f, 2.5f, 9f, 0f, Sk.ACC);
                String s = "X: " + Math.round(pl.getX()) + "   Z: " + Math.round(pl.getZ());
                Gfx.text(g, s, size / 2f, size + 11.6f, 7.2f, Sk.WHITE, 1, true, 0f);
            }
        } finally {
            g.pose().popPose();
        }
    }

    // ------------------------------------------------------------------ straturi

    private static void drawZones(GuiGraphics g, Minecraft mc, float op) {
        String dim = GpsData.dimOf(mc);
        double wr = clipRad / zoom + 4.0;
        for (GpsData.Zone z : GpsData.zones) {
            if (!z.enabled || !z.dim.equals(dim) || !GpsPrefs.layerOn(GpsData.zoneLayer(z))) continue;
            if (z.maxX < camX - wr || z.minX > camX + wr || z.maxZ < camZ - wr || z.minZ > camZ + wr) continue;
            int fill = (z.color & 0xFFFFFF) | (Math.round(z.alpha * 2.3f * op) << 24);
            float[] tr = z.tris;
            for (int i = 0; i + 5 < tr.length; i += 6) {
                for (int k = 0; k < 3; k++) {
                    project(tr[i + k * 2], tr[i + k * 2 + 1]);
                    zIn[k * 2] = outX;
                    zIn[k * 2 + 1] = outY;
                }
                int n = MiniMapClip.clip(zIn, 3, 2, clip, clipN, zOut, zTmp);
                if (n >= 3) fan(g, MiniMapClip.resultInTmp ? zTmp : zOut, n, fill);
            }
            int edge = (z.outline & 0xFFFFFF) | 0xD0000000;
            int n = z.px.length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                project(z.px[i], z.pz[i]);
                float ax = outX;
                float ay = outY;
                project(z.px[j], z.pz[j]);
                if (!MiniMapClip.clipSegment(clip, clipN, ax, ay, outX, outY)) continue;
                float bx = outX;
                float by = outY;
                Gfx.line(g, ax + (bx - ax) * MiniMapClip.t0, ay + (by - ay) * MiniMapClip.t0, ax + (bx - ax) * MiniMapClip.t1, ay + (by - ay) * MiniMapClip.t1, 1.3f, edge);
            }
        }
    }

    private static void drawLocs(GuiGraphics g, Minecraft mc) {
        refreshLocs(mc);
        int max = MiniMapSettings.lowPerf ? 24 : 64;
        int drawn = 0;
        for (int i = 0, n = locs.size(); i < n && drawn < max; i++) {
            GpsData.Loc l = locs.get(i);
            if (GpsNav.isTarget(l.id)) continue;
            project(l.x, l.z);
            if (!MiniMapClip.inside(clip, clipN, outX, outY)) continue;
            float rr = Math.max(3.4f, Math.min(6.5f, 4.4f * l.size / 100f));
            GpsIcons.badge(g, l.icon, outX, outY, rr, l.color, 1f, false);
            drawn++;
        }
    }

    private static void drawWaypoint(GuiGraphics g, Minecraft mc) {
        if (!GpsNav.active || !MiniMapSettings.showWaypoint || !GpsPrefs.layerOn("waypoints")) return;
        double d = GpsNav.distance(mc);
        if (d < 0) return;
        project(GpsNav.x, GpsNav.z);
        float px = outX;
        float py = outY;
        float t = (System.currentTimeMillis() % 1600L) / 1600f;
        String dist = GpsNav.fmtDist(d);
        float s = clipSize;
        if (MiniMapClip.inside(clip, clipN, px, py)) {
            Gfx.ring(g, px, py, 5f + t * 9f, 5.8f + t * 9f, Gfx.fade(GpsNav.color, 0.8f * (1f - t)), Gfx.fade(GpsNav.color, 0.8f * (1f - t)));
            GpsIcons.badge(g, "pin", px, py, 5.6f, GpsNav.color, 1f, true);
            return;
        }
        // in afara razei: sageata pe margine, spre destinatie
        float vx = px - cx;
        float vy = py - cy;
        float len = (float) Math.sqrt(vx * vx + vy * vy);
        if (len < 0.01f) return;
        float k;
        float half = s / 2f - 8f;
        if (clipCircle) {
            k = half / len;
        } else {
            k = half / Math.max(Math.abs(vx), Math.abs(vy));
        }
        float ex = cx + vx * k;
        float ey = cy + vy * k;
        double ang = Math.atan2(vx, -vy);
        Gfx.disc(g, ex, ey, 7.2f, Gfx.fade(0xC0081018, 1f));
        GpsIcons.arrow(g, ex, ey, 5.2f, ang, GpsNav.color, 0xFF0A1220);
        // distanta, spre interior, ca sa nu iasa din harta
        float tx = cx + vx * k * 0.62f;
        float ty = cy + vy * k * 0.62f;
        float tw = Gfx.width(dist, 6.6f, true);
        Gfx.rrect(g, tx - tw / 2f - 3f, ty - 6f, tw + 6f, 10f, 3f, 0xC0081018);
        Gfx.text(g, dist, tx, ty + 1.6f, 6.6f, Sk.WHITE, 1, true, 0f);
    }

    private static void drawPlayer(GuiGraphics g, float yaw) {
        float ph = (System.currentTimeMillis() % 2000L) / 2000f;
        Gfx.ring(g, cx, cy, 5f + ph * 7f, 5.6f + ph * 7f, Gfx.fade(0xFF4DA3FF, 0.6f * (1f - ph)), Gfx.fade(0xFF4DA3FF, 0.6f * (1f - ph)));
        double ang = MiniMapSettings.rotate ? 0.0 : Math.toRadians(yaw - 180f);
        Gfx.disc(g, cx, cy, 6.6f, 0x88000000);
        GpsIcons.arrow(g, cx, cy, 5.6f, ang, 0xFFFFFFFF, 0xFF1B6FD1);
    }

    private static void drawFrame(GuiGraphics g, float size, boolean circle) {
        int line = Gfx.fade(0xFF4DA3FF, 0.85f);
        if (circle) {
            float r = size / 2f;
            Gfx.ring(g, r, r, r - 1.6f, r + 0.4f, line, line);
            Gfx.ring(g, r, r, r + 0.4f, r + 2.2f, 0x55000000, 0x00000000);
        } else {
            for (int i = 0; i < clipN; i++) {
                int j = (i + 1) % clipN;
                Gfx.line(g, clip[i * 2], clip[i * 2 + 1], clip[j * 2], clip[j * 2 + 1], 1.7f, line);
            }
        }
    }

    private static void drawCompass(GuiGraphics g, float size, boolean circle) {
        String[] names = {"N", "E", "S", "V"};
        double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        float half = size / 2f - 7f;
        for (int i = 0; i < 4; i++) {
            double vx = dirs[i][0] * ct - dirs[i][1] * st;
            double vy = dirs[i][0] * st + dirs[i][1] * ct;
            float k = circle ? half : (float) (half / Math.max(Math.abs(vx), Math.abs(vy)));
            float lx = cx + (float) vx * k;
            float ly = cy + (float) vy * k;
            Gfx.disc(g, lx, ly, 4.6f, 0xB0081018);
            Gfx.text(g, names[i], lx, ly + 2.3f, 6.4f, i == 0 ? Sk.BAD : Sk.WHITE, 1, true, 0f);
        }
    }

    private static void drawZoomNote(GuiGraphics g, float size) {
        if (System.currentTimeMillis() > zoomShownUntil) return;
        String s = "x" + String.format(java.util.Locale.ROOT, "%.1f", MiniMapSettings.zoom);
        float w = Gfx.width(s, 7f, true) + 8f;
        Gfx.rrect(g, 5f, size - 17f, w, 11f, 3f, 0xC0081018);
        Gfx.text(g, s, 9f, size - 8.6f, 7f, Sk.WHITE, 0, true, 0f);
    }

    // ------------------------------------------------------------------ primitive

    /** Poligon convex plin dintr-un vector de puncte x,y (n varfuri), fara alocari. */
    private static void fan(GuiGraphics g, float[] p, int n, int argb) {
        if (n < 3) return;
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
        RenderSystem.disableAlphaTest();
        RenderSystem.disableCull();
        RenderSystem.shadeModel(GL11.GL_SMOOTH);
        BufferBuilder b = Tessellator.getInstance().getBuilder();
        b.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        int a = Math.round(((argb >>> 24) & 255) * Gfx.alpha);
        int rr = (argb >> 16) & 255;
        int gg = (argb >> 8) & 255;
        int bb = argb & 255;
        for (int i = 0; i < n; i++) b.vertex(m, p[i * 2], p[i * 2 + 1], 0f).color(rr, gg, bb, a).endVertex();
        Tessellator.getInstance().end();
        RenderSystem.shadeModel(GL11.GL_FLAT);
        RenderSystem.enableCull();
        RenderSystem.enableAlphaTest();
        RenderSystem.enableTexture();
    }
}
