package ro.solarstudios.flowcity.gps;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.gen.Heightmap;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudEditorScreen;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Cardul de navigare din HUD (element "gps", mutabil in editorul de HUD) si fasciculul/inelul de la destinatie, in lume. */
public final class GpsHud {
    private GpsHud() {}

    private static final float W = 156f;
    private static final float H = 64f;

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            boolean editor = mc.screen instanceof HudEditorScreen;
            if (mc.screen != null && !editor) return;
            boolean demo = editor && !GpsNav.active;
            if (!GpsNav.active && !demo) return;
            if (!demo && !GpsPrefs.hudOn) return;
            GuiGraphics g = new GuiGraphics(e.getMatrixStack());
            if (!demo) pill(g, mc);
            float x = 10f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f + 40f;
            if (!HudLayout.begin(g, "gps", x, y, W, H)) return;
            try {
                int acc = HudLayout.accent("gps", GpsNav.active ? GpsNav.color : 0xFF4DA3FF);
                Gfx.rrect(g, x - 1, y + 2, W + 2, H + 2, 10, 0x55000000);
                Gfx.rrect(g, x, y, W, H, 9, 0xEE15202F, 0xEE0A101A);
                Gfx.rrect(g, x, y + 8, 3, H - 16, 0, acc);
                String name = demo ? "Primărie Sector 2" : GpsNav.name;
                double d;
                String eta;
                double dx;
                double dz;
                double straight;
                RoadRouteProvider.Step nx = null;
                if (demo) {
                    d = 1200;
                    straight = 1200;
                    eta = "3 min";
                    dx = 0;
                    dz = -1;
                } else {
                    d = GpsNav.travelDistance(mc);
                    straight = GpsNav.distance(mc);
                    eta = GpsNav.fmtEta(GpsNav.etaSeconds(mc));
                    dx = GpsNav.x - mc.player.getX();
                    dz = GpsNav.z - mc.player.getZ();
                    double[] la = RoadRouteProvider.I.lookahead(14);
                    if (la != null) {
                        dx = la[0] - mc.player.getX();
                        dz = la[1] - mc.player.getZ();
                    }
                    nx = RoadRouteProvider.I.next();
                }
                Gfx.text(g, "GPS", x + 12, y + 14, 6.4f, acc, 0, true, 1.2f);
                Gfx.text(g, demo ? "pe străzi" : clip(RoadRouteProvider.I.statusText(), 6.2f, false, 84f), x + 34, y + 14, 6.2f, 0xFF7B8AA0, 0);
                String line2 = name;
                if (nx != null) line2 = nx.turn == 9 ? "Sosire: " + name : "În " + GpsNav.fmtDist(RoadRouteProvider.I.nextDist()) + " · " + nx.shortText();
                Gfx.text(g, clip(line2, 8.4f, true, 96f), x + 12, y + 28, 8.4f, 0xFFFFFFFF, 0, true, 0f);
                Gfx.text(g, GpsNav.fmtDist(d), x + 12, y + 50, 15f, 0xFFFFFFFF, 0, true, 0f);
                Gfx.text(g, eta, x + 12 + Math.max(52f, Gfx.width(GpsNav.fmtDist(d), 15f, true) + 6f), y + 49, 7f, 0xFFC9D4E4, 0);
                float cx = x + W - 32f;
                float cy = y + H / 2f;
                Gfx.disc(g, cx, cy, 21f, 0xFF0C1522);
                Gfx.disc(g, cx, cy, 19f, 0xFF1A2A40);
                if (d < 0) return;
                if (straight <= GpsData.arrive) {
                    Gfx.disc(g, cx, cy, 8f, 0xFF4CD08A);
                    return;
                }
                double target = Math.toDegrees(Math.atan2(-dx, dz));
                double rel = demo ? 0 : target - mc.player.yRot;
                rel = ((rel % 360.0) + 540.0) % 360.0 - 180.0;
                GpsIcons.arrow(g, cx, cy, 11f, Math.toRadians(rel), acc, 0xFF0A1220);
            } finally {
                HudLayout.end(g);
            }
        }

        @SubscribeEvent
        public void onWorld(RenderWorldLastEvent e) {
            Minecraft mc = Minecraft.getInstance();
            if (!GpsNav.active || mc.player == null || mc.level == null) return;
            double dist = GpsNav.distance(mc);
            if (dist < 0) return;
            ActiveRenderInfo cam = mc.gameRenderer.getMainCamera();
            Vector3d c = cam.getPosition();
            MatrixStack ps = e.getMatrixStack();
            IRenderTypeBuffer.Impl buf = mc.renderBuffers().bufferSource();
            if (GpsNav.route.roads()) {
                ribbon(mc, ps, buf, c);
                if (dist < 2.5 || dist > 2500) {
                    buf.endBatch(RenderType.lightning());
                    return;
                }
            } else if (dist > 2500 || dist < 2.5) {
                return;
            }
            double gy = mc.level.getHeight(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(GpsNav.x), MathHelper.floor(GpsNav.z));
            if (gy <= 1) gy = mc.player.getY();
            int rgb = GpsNav.color & 0xFFFFFF;
            long now = System.currentTimeMillis();
            float slow = 0.5f + 0.5f * (float) Math.sin(now / 700.0);
            float fade = (float) Math.min(1.0, (dist - 2.5) / 10.0);
            if (dist < 90) {
                ring(ps, buf, c, GpsNav.x, gy, GpsNav.z, 1.3f, 0.08f, rgb, (int) ((150 + 80 * slow) * fade));
                float ph = (now % 2400L) / 2400f;
                ring(ps, buf, c, GpsNav.x, gy, GpsNav.z, 0.6f + 1.6f * ph, 0.05f, rgb, (int) (130 * (1f - ph) * fade));
            }
            float w = (float) Math.max(0.09, Math.min(1.4, dist * 0.0035));
            beam(ps, buf, c, GpsNav.x, gy, GpsNav.z, 110f, w, rgb, (int) (170 * fade));
            buf.endBatch(RenderType.lightning());
        }
    }

    /** Indicatia de viraj, sus in centru (doar cand traseul urmeaza drumurile si manevra e aproape). */
    private static void pill(GuiGraphics g, Minecraft mc) {
        RoadRouteProvider.Step st = RoadRouteProvider.I.next();
        if (st == null) return;
        double d = RoadRouteProvider.I.nextDist();
        if (st.turn == 9 ? d > 150 : d > 400) return;
        String l1 = st.turn == 9 ? "Sosire" : "În " + GpsNav.fmtDist(d);
        String l2 = st.turn == 9 ? GpsNav.name : st.text() + (st.street.isEmpty() ? "" : " · " + st.street);
        l2 = clip(l2, 8.2f, true, 230f);
        float w = Math.max(130f, Math.max(Gfx.width(l1, 10.5f, true), Gfx.width(l2, 8.2f, true)) + 70f);
        float h = 36f;
        float x = (mc.getWindow().getGuiScaledWidth() - w) / 2f;
        float y = 24f;
        int acc = GpsNav.color;
        Gfx.rrect(g, x - 1, y + 2, w + 2, h + 2, 11, 0x55000000);
        Gfx.rrect(g, x, y, w, h, 10, 0xF015202F, 0xF00A101A);
        Gfx.disc(g, x + 24, y + h / 2f, 15f, 0xFF0C1522);
        if (st.turn == 9) Gfx.disc(g, x + 24, y + h / 2f, 7f, 0xFF4CD08A);
        else GpsIcons.arrow(g, x + 24, y + h / 2f, 9f, Math.toRadians(st.arrowDeg()), acc, 0xFF0A1220);
        Gfx.text(g, l1, x + 46, y + 15, 10.5f, 0xFFFFFFFF, 0, true, 0f);
        Gfx.text(g, l2, x + 46, y + 28, 8.2f, 0xFFC9D4E4, 0, true, 0f);
    }

    /** Panglica traseului pe sol, ~150 m in fata (linie intrerupta, animata). */
    private static void ribbon(Minecraft mc, MatrixStack ps, IRenderTypeBuffer buf, Vector3d c) {
        java.util.List<double[]> path = GpsNav.route.path(mc.player.getX(), mc.player.getZ(), GpsNav.x, GpsNav.z);
        if (path.size() < 2) return;
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        int rgb = GpsNav.color & 0xFFFFFF;
        int cr = (rgb >> 16) & 255;
        int cg = (rgb >> 8) & 255;
        int cb = rgb & 255;
        double py = mc.player.getY();
        double phase = (System.currentTimeMillis() % 1400L) / 1400.0 * 3.2;
        double walked = 0;
        double limit = 150;
        double base = RoadRouteProvider.I.along();
        ps.pushPose();
        ps.translate(-c.x, -c.y, -c.z);
        Matrix4f m = ps.last().pose();
        int quads = 0;
        for (int i = 0; i + 1 < path.size() && walked < limit && quads < 400; i++) {
            double ax = path.get(i)[0];
            double az = path.get(i)[1];
            double bx = path.get(i + 1)[0];
            double bz = path.get(i + 1)[1];
            double len = Math.hypot(bx - ax, bz - az);
            if (len < 0.01) continue;
            double ux = (bx - ax) / len;
            double uz = (bz - az) / len;
            double nxp = -uz * 0.55;
            double nzp = ux * 0.55;
            // dashuri ancorate de traseu: 2.0 m desenat + 1.2 m pauza, curgand spre destinatie
            double g0 = base + walked;
            long k0 = (long) Math.floor((g0 - phase - 2.0) / 3.2);
            for (long k = k0; quads < 400; k++) {
                double ds = k * 3.2 + phase - g0;
                if (ds >= len || walked + ds > limit) break;
                double d0 = Math.max(0, ds);
                double d1 = Math.min(len, ds + 2.0);
                if (d1 - d0 < 0.2) continue;
                double x0 = ax + ux * d0;
                double z0 = az + uz * d0;
                double x1 = ax + ux * d1;
                double z1 = az + uz * d1;
                double y0 = groundY(mc, x0, z0, py);
                double y1 = groundY(mc, x1, z1, py);
                int a = (int) (190 * Math.max(0.0, 1.0 - (walked + d0) / limit));
                if (a < 8) continue;
                // ambele fete (tipul de randare poate taia fata din spate)
                vc.vertex(m, (float) (x0 - nxp), (float) y0, (float) (z0 - nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x0 + nxp), (float) y0, (float) (z0 + nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x1 + nxp), (float) y1, (float) (z1 + nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x1 - nxp), (float) y1, (float) (z1 - nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x1 - nxp), (float) y1, (float) (z1 - nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x1 + nxp), (float) y1, (float) (z1 + nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x0 + nxp), (float) y0, (float) (z0 + nzp)).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, (float) (x0 - nxp), (float) y0, (float) (z0 - nzp)).color(cr, cg, cb, a).endVertex();
                quads++;
            }
            walked += len;
        }
        ps.popPose();
    }

    private static double groundY(Minecraft mc, double x, double z, double py) {
        double gy = mc.level.getHeight(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(x), MathHelper.floor(z));
        if (gy <= 1 || Math.abs(gy - py) > 6) gy = Math.floor(py);
        return gy + 0.07;
    }

    private static void ring(MatrixStack ps, IRenderTypeBuffer buf, Vector3d c, double x, double y, double z, float r, float thick, int rgb, int alpha) {
        if (alpha < 3) return;
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.translate(x - c.x, y - c.y + 0.05, z - c.z);
        Matrix4f m = ps.last().pose();
        int seg = 40;
        float in = Math.max(0f, r - thick);
        int cr = (rgb >> 16) & 255;
        int cg = (rgb >> 8) & 255;
        int cb = rgb & 255;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg;
            double a1 = (i + 1) * Math.PI * 2 / seg;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            vc.vertex(m, c0 * in, 0, s0 * in).color(cr, cg, cb, alpha).endVertex();
            vc.vertex(m, c0 * r, 0, s0 * r).color(cr, cg, cb, alpha).endVertex();
            vc.vertex(m, c1 * r, 0, s1 * r).color(cr, cg, cb, alpha).endVertex();
            vc.vertex(m, c1 * in, 0, s1 * in).color(cr, cg, cb, alpha).endVertex();
        }
        ps.popPose();
    }

    private static void beam(MatrixStack ps, IRenderTypeBuffer buf, Vector3d c, double x, double y, double z, float h, float w, int rgb, int alpha) {
        if (alpha < 4) return;
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.translate(x - c.x, y - c.y, z - c.z);
        Matrix4f m = ps.last().pose();
        int cr = (rgb >> 16) & 255;
        int cg = (rgb >> 8) & 255;
        int cb = rgb & 255;
        vc.vertex(m, -w, 0, 0).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, w, 0, 0).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, w, h, 0).color(cr, cg, cb, 0).endVertex();
        vc.vertex(m, -w, h, 0).color(cr, cg, cb, 0).endVertex();
        vc.vertex(m, 0, 0, -w).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, 0, 0, w).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, 0, h, w).color(cr, cg, cb, 0).endVertex();
        vc.vertex(m, 0, h, -w).color(cr, cg, cb, 0).endVertex();
        ps.popPose();
    }
}
