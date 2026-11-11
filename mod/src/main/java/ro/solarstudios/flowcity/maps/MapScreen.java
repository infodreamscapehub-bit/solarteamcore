package ro.solarstudios.flowcity.maps;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.MathHelper;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Harta mare eMaps: teren, puncte de interes, GPS. Trage = muta, rotita = zoom, click pe punct = GPS, click dreapta = punct propriu. */
public final class MapScreen extends FlowScreen {
    private static final int S = 220;
    private MapView view;
    private double centerX;
    private double centerZ;
    private int scale = 1;
    private int listScroll;
    private int lastMx;
    private int lastMy;
    private boolean followPlayer = true;

    public MapScreen() {
        super("eMaps", 440, 276);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            centerX = mc.player.getPosX();
            centerZ = mc.player.getPosZ();
        }
        MapsClient.requestPois();
    }

    @Override
    public void removed() {
        if (view != null) view.close();
        view = null;
    }

    private int mapX() {
        return px + 12;
    }

    private int mapY() {
        return py + 36;
    }

    private boolean inMap(double mx, double my) {
        return Ui.in((int) mx, (int) my, mapX(), mapY(), S, S);
    }

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        lastMx = mx;
        lastMy = my;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        header(ms, "eMaps", "harta si GPS  |  M = inchide", mx, my);
        if (followPlayer) {
            centerX = mc.player.getPosX();
            centerZ = mc.player.getPosZ();
        }
        int x = mapX();
        int y = mapY();
        if (view == null) view = new MapView(S, "flowcity_bigmap");
        int cx = MathHelper.floor(centerX);
        int cz = MathHelper.floor(centerZ);
        if (view.needsRefresh(cx, cz, scale, 2000)) view.refresh(mc.world, cx, cz, scale);

        Ui.rect(ms, x - 2, y - 2, S + 4, S + 4, Theme.LINE);
        view.draw(ms, x, y, S, S);
        hit(x, y, S, S, () -> { }); // zona hartii (sub restul hit-urilor)

        MapsClient.drawRoute(ms, view, x, y, S, S, x, y, x + S, y + S);

        // puncte de interes
        MapsState.Poi hover = null;
        for (MapsState.Poi p : MapsState.pois) {
            int sx = Math.round(view.sx(p.x, x, S));
            int sy = Math.round(view.sy(p.z, y, S));
            if (sx < x + 4 || sy < y + 4 || sx > x + S - 4 || sy > y + S - 4) continue;
            int c = MapsState.typeColor(p.type);
            boolean h = Ui.in(mx, my, sx - 6, sy - 6, 12, 12);
            Ui.round(ms, sx - 6, sy - 6, 12, 12, 0xFF000000);
            Ui.round(ms, sx - 5, sy - 5, 10, 10, c);
            Ui.textCenter(ms, MapsState.typeLetter(p.type), sx, sy - 4, 0xFF101010);
            if (h) hover = p;
            final MapsState.Poi pp = p;
            hit(sx - 6, sy - 6, 12, 12, () -> {
                MapsClient.gpsTo(pp.name, pp.x, pp.z);
                toast("GPS spre " + pp.name, false);
            });
        }
        if (MapsState.hasTarget) {
            int sx = Math.round(view.sx(MapsState.tx, x, S));
            int sy = Math.round(view.sy(MapsState.tz, y, S));
            if (sx >= x && sy >= y && sx < x + S && sy < y + S) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 220.0);
                Ui.rect(ms, sx - 4, sy - 4, 9, 9, Theme.alpha(0xFFFFC83D, (int) (150 + 100 * pulse)));
                Ui.rect(ms, sx - 1, sy - 1, 3, 3, 0xFF000000);
            }
        }
        float px0 = view.sx(mc.player.getPosX(), x, S);
        float pz0 = view.sy(mc.player.getPosZ(), y, S);
        if (px0 >= x && pz0 >= y && px0 < x + S && pz0 < y + S) MapsClient.drawPlayer(ms, px0, pz0, mc.player.rotationYaw);

        Ui.text(ms, "1 pixel = " + scale + (scale == 1 ? " bloc" : " blocuri"), x + 4, y + S - 11, Theme.alpha(0xFFFFFFFF, 200));
        if (hover != null) {
            String t = hover.name + "  (" + hover.type + ")";
            int w = Ui.width(t) + 10;
            int tx = Math.min(x + S - w, Math.max(x, lastMx + 8));
            Ui.frame(ms, tx, Math.max(y, lastMy - 18), w, 14, Theme.PANEL_3, Theme.ACCENT);
            Ui.text(ms, t, tx + 5, Math.max(y, lastMy - 18) + 3, Theme.TEXT);
        }

        // panou dreapta
        int rx = px + 12 + S + 12;
        int rw = pw - (rx - px) - 12;
        Ui.text(ms, "GPS", rx, y, Theme.MUTED);
        if (MapsState.hasTarget) {
            Ui.text(ms, Ui.clip(MapsState.targetName, rw), rx, y + 12, Theme.TEXT);
            double d = Math.hypot(MapsState.tx - mc.player.getPosX(), MapsState.tz - mc.player.getPosZ());
            Ui.text(ms, MapsClient.meters(d) + (MapsState.routeIsStraight ? "  (linie dreapta)" : "  (ruta pe strazi)"), rx, y + 24, 0xFFFFC83D);
            button(ms, "OPRESTE GPS", rx, y + 38, rw, 16, mx, my, true, Theme.BAD, () -> {
                MapsState.clearTarget();
                toast("GPS oprit", false);
            });
        } else {
            Ui.text(ms, "Nicio destinatie.", rx, y + 12, Theme.FAINT);
            Ui.text(ms, "Click pe un punct din lista", rx, y + 24, Theme.FAINT);
            Ui.text(ms, "sau click dreapta pe harta.", rx, y + 34, Theme.FAINT);
        }

        int by = y + 60;
        int hw = (rw - 6) / 2;
        button(ms, "ZOOM -", rx, by, hw, 16, mx, my, scale < 4, Theme.ACCENT_DIM, () -> {
            scale = Math.min(4, scale * 2);
        });
        button(ms, "ZOOM +", rx + hw + 6, by, hw, 16, mx, my, scale > 1, Theme.ACCENT_DIM, () -> {
            scale = Math.max(1, scale / 2);
        });
        button(ms, "CENTREAZA", rx, by + 20, hw, 16, mx, my, !followPlayer, Theme.ACCENT_DIM, () -> followPlayer = true);
        button(ms, MapsState.minimap ? "MINIMAP: ON" : "MINIMAP: OFF", rx + hw + 6, by + 20, hw, 16, mx, my, true, Theme.PANEL_3, () -> MapsState.minimap = !MapsState.minimap);

        // lista puncte, cele mai apropiate primele
        int ly = by + 46;
        Ui.text(ms, "PUNCTE DE INTERES", rx, ly, Theme.MUTED);
        ly += 12;
        List<MapsState.Poi> sorted = new ArrayList<>(MapsState.pois);
        final double ppx = mc.player.getPosX();
        final double ppz = mc.player.getPosZ();
        sorted.sort(Comparator.comparingDouble(p -> Math.hypot(p.x - ppx, p.z - ppz)));
        int listH = py + ph - 14 - ly;
        int rowH = 20;
        int total = sorted.size() * rowH;
        int max = Math.max(0, total - listH);
        listScroll = Math.max(0, Math.min(listScroll, max));
        if (sorted.isEmpty()) Ui.text(ms, "Niciun punct (adminii le adauga cu /harta poi add).", rx, ly + 2, Theme.FAINT);
        Ui.scissor(rx, ly, rw, listH);
        int yy = ly - listScroll;
        for (MapsState.Poi p : sorted) {
            if (yy + rowH > ly && yy < ly + listH) {
                boolean h = Ui.in(mx, my, rx, yy, rw, rowH - 2) && my >= ly && my < ly + listH;
                Ui.round(ms, rx, yy, rw, rowH - 2, h ? Theme.PANEL_3 : Theme.PANEL_2);
                Ui.rect(ms, rx + 3, yy + 5, 8, 8, MapsState.typeColor(p.type));
                Ui.text(ms, Ui.clip(p.name, rw - 70), rx + 16, yy + 2, Theme.TEXT);
                Ui.text(ms, p.type, rx + 16, yy + 10, Theme.FAINT);
                Ui.textRight(ms, MapsClient.meters(Math.hypot(p.x - ppx, p.z - ppz)), rx + rw - 4, yy + 6, Theme.MUTED);
                if (my >= ly && my < ly + listH) {
                    final MapsState.Poi pp = p;
                    hit(rx, yy, rw, rowH - 2, () -> {
                        MapsClient.gpsTo(pp.name, pp.x, pp.z);
                        toast("GPS spre " + pp.name, false);
                    });
                }
            }
            yy += rowH;
        }
        Ui.endScissor();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 1 && inMap(mx, my) && view != null) {
            double wx = view.cx - S * view.scale / 2.0 + (mx - mapX()) * view.scale;
            double wz = view.cz - S * view.scale / 2.0 + (my - mapY()) * view.scale;
            MapsClient.gpsTo("Punct pe harta", wx, wz);
            toast("GPS spre punctul ales", false);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && inMap(mx, my)) {
            centerX -= dx * scale;
            centerZ -= dy * scale;
            followPlayer = false;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inMap(mx, my)) {
            if (delta > 0) scale = Math.max(1, scale / 2);
            else if (delta < 0) scale = Math.min(4, scale * 2);
        } else {
            listScroll -= (int) (delta * 20);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (MapsClient.openKey != null && MapsClient.openKey.matchesKey(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
