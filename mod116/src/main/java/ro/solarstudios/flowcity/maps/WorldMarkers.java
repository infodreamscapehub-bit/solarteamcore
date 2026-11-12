package ro.solarstudios.flowcity.maps;

import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.util.math.vector.Vector3d;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/**
 * Waypoint-urile vazute in lume: iconita + distanta deasupra locului, numele apare cand te uiti spre el.
 * Proiectie facuta de mana din pozitia si rotatia camerei (fara render in lume, deci merge cu orice shader).
 */
public final class WorldMarkers {
    private WorldMarkers() {}

    private static final double MAX_DIST = 3000;

    public static void draw(GuiGraphics ms, Minecraft mc) {
        if (!MapsState.worldMarkers || mc.player == null) return;
        ActiveRenderInfo cam = mc.gameRenderer.getMainCamera();
        Vector3d c = cam.getPosition();
        double yaw = Math.toRadians(cam.getYRot());
        double pitch = Math.toRadians(cam.getXRot());
        // baza camerei (conventia Minecraft: yaw 0 = spre +Z, pitch pozitiv = in jos)
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        double fov = Math.toRadians(mc.options.fov);
        double focal = (h / 2.0) / Math.tan(fov / 2.0);
        Proj p = new Proj(c, fx, fy, fz, rx, ry, rz, ux, uy, uz, w, h, focal);

        for (MapsState.Wp wp : MapsState.wps) {
            marker(ms, p, wp.name, wp.type, wp.color, wp.x, wp.y + 1.2, wp.z, 0);
        }
        if (MapsState.hasTarget) {
            double ty = mc.player.getY() + 1.2;
            int hh = MapCache.height((int) Math.floor(MapsState.tx), (int) Math.floor(MapsState.tz));
            if (hh > 0) ty = hh + 1.5;
            marker(ms, p, MapsState.targetName, MapsState.targetType, MapsClient.ROUTE_COLOR, MapsState.tx, ty, MapsState.tz, 1);
        }
    }

    private static final class Proj {
        final Vector3d c;
        final double fx, fy, fz, rx, ry, rz, ux, uy, uz, focal;
        final int w, h;

        Proj(Vector3d c, double fx, double fy, double fz, double rx, double ry, double rz, double ux, double uy, double uz, int w, int h, double focal) {
            this.c = c;
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            this.ux = ux;
            this.uy = uy;
            this.uz = uz;
            this.w = w;
            this.h = h;
            this.focal = focal;
        }
    }

    /** kind 0 = waypoint, 1 = destinatie GPS (ramane lipita de marginea ecranului cand e in spate). */
    private static void marker(GuiGraphics ms, Proj p, String name, String type, int color, double x, double y, double z, int kind) {
        double dx = x - p.c.x, dy = y - p.c.y, dz = z - p.c.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > MAX_DIST || dist < 3) return;
        double cz = dx * p.fx + dy * p.fy + dz * p.fz;
        double cx = dx * p.rx + dy * p.ry + dz * p.rz;
        double cy = dx * p.ux + dy * p.uy + dz * p.uz;
        int sx;
        int sy;
        if (cz < 0.2) {
            if (kind != 1) return;
            // in spate: sageata la marginea de jos, in directia buna
            sx = (int) Math.max(14, Math.min(p.w - 14, p.w / 2.0 + (cx > 0 ? 1 : -1) * (p.w / 2.0 - 14)));
            sy = p.h - 40;
        } else {
            sx = (int) Math.round(p.w / 2.0 + cx / cz * p.focal);
            sy = (int) Math.round(p.h / 2.0 - cy / cz * p.focal);
            if (kind == 1) {
                sx = Math.max(14, Math.min(p.w - 14, sx));
                sy = Math.max(18, Math.min(p.h - 30, sy));
            } else if (sx < -20 || sy < -20 || sx > p.w + 20 || sy > p.h + 20) {
                return;
            }
        }
        // mai mic cand e departe, dar mereu lizibil
        int size = dist > 400 ? 10 : (dist > 120 ? 12 : 14);
        boolean aimed = Math.abs(sx - p.w / 2) < 40 && Math.abs(sy - p.h / 2) < 40;
        int alpha = aimed ? 0xFF : 0xC8;
        if (type != null && !type.isEmpty()) {
            Blips.sprite(ms, Blips.icon(type), sx - size / 2, sy - size / 2, size);
        } else {
            int r = size / 2 - 1;
            // romb in culoarea waypoint-ului
            for (int i = 0; i <= r; i++) {
                Ui.rect(ms, sx - i, sy - r + i, i * 2 + 1, 1, Theme.alpha(0xFF101010, alpha));
                Ui.rect(ms, sx - i, sy + r - i, i * 2 + 1, 1, Theme.alpha(0xFF101010, alpha));
            }
            for (int i = 0; i < r; i++) {
                Ui.rect(ms, sx - i, sy - r + 1 + i, i * 2 + 1, 1, Theme.alpha(color, alpha));
                Ui.rect(ms, sx - i, sy + r - 1 - i, i * 2 + 1, 1, Theme.alpha(color, alpha));
            }
        }
        String d = MapsClient.meters(dist);
        int dw = Ui.width(d);
        Ui.rect(ms, sx - dw / 2 - 2, sy + size / 2 + 2, dw + 4, 10, Theme.alpha(0xFF000000, aimed ? 0xA0 : 0x70));
        Ui.text(ms, d, sx - dw / 2, sy + size / 2 + 3, Theme.alpha(0xFFFFFFFF, alpha));
        if (aimed || kind == 1) {
            String n = Ui.clip(name, 140);
            int nw = Ui.width(n);
            Ui.rect(ms, sx - nw / 2 - 3, sy - size / 2 - 13, nw + 6, 11, 0xB0000000);
            Ui.rect(ms, sx - nw / 2 - 3, sy - size / 2 - 3, nw + 6, 1, Theme.alpha(color, 0xFF));
            Ui.text(ms, n, sx - nw / 2, sy - size / 2 - 11, 0xFFFFFFFF);
        }
    }
}
