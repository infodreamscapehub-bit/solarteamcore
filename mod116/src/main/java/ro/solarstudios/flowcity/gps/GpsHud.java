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
            GuiGraphics g = new GuiGraphics(e.getMatrixStack());
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
                if (demo) {
                    d = 1200;
                    eta = "3 min";
                    dx = 0;
                    dz = -1;
                } else {
                    d = GpsNav.distance(mc);
                    eta = GpsNav.fmtEta(GpsNav.etaSeconds(mc));
                    dx = GpsNav.x - mc.player.getX();
                    dz = GpsNav.z - mc.player.getZ();
                }
                Gfx.text(g, "GPS", x + 12, y + 14, 6.4f, acc, 0, true, 1.2f);
                Gfx.text(g, "direcție directă", x + 34, y + 14, 6.2f, 0xFF7B8AA0, 0);
                Gfx.text(g, clip(name, 8.4f, true, 96f), x + 12, y + 28, 8.4f, 0xFFFFFFFF, 0, true, 0f);
                Gfx.text(g, GpsNav.fmtDist(d), x + 12, y + 50, 15f, 0xFFFFFFFF, 0, true, 0f);
                Gfx.text(g, eta, x + 12 + Math.max(52f, Gfx.width(GpsNav.fmtDist(d), 15f, true) + 6f), y + 49, 7f, 0xFFC9D4E4, 0);
                float cx = x + W - 32f;
                float cy = y + H / 2f;
                Gfx.disc(g, cx, cy, 21f, 0xFF0C1522);
                Gfx.disc(g, cx, cy, 19f, 0xFF1A2A40);
                if (d < 0) return;
                if (d <= GpsData.arrive) {
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
            if (dist < 0 || dist > 2500 || dist < 2.5) return;
            ActiveRenderInfo cam = mc.gameRenderer.getMainCamera();
            Vector3d c = cam.getPosition();
            MatrixStack ps = e.getMatrixStack();
            IRenderTypeBuffer.Impl buf = mc.renderBuffers().bufferSource();
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
