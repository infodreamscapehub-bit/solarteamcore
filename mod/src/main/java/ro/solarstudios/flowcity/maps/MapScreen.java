package ro.solarstudios.flowcity.maps;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.math.MathHelper;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Harta mare in stil GTA V (meniul de pauza): teren descoperit, iconite, filtre, waypoint cu ruta. */
public final class MapScreen extends FlowScreen {
    private static final int[][] LEVELS = {{1, 4}, {1, 2}, {2, 2}, {4, 2}, {8, 2}}; // {blocuri/texel, px GUI/texel}
    private static int level = 1;

    private MapView view;
    private double vx;
    private double vz;
    private boolean follow = true;
    private int listScroll;
    private int mx0;
    private int my0;
    private int ax;
    private int ay;
    private int aw;
    private int ah;
    private final long born = System.currentTimeMillis();

    // editor
    private boolean editor;
    private boolean sidebar;
    private int legendStart;
    private String gpsToastName;
    private long gpsToastAt;
    private final TextFieldWidget nameField;
    private int draftId;
    private String draftType = "altele";
    private boolean draftSet;
    private double draftX;
    private double draftZ;

    public MapScreen() {
        this(false);
    }

    public MapScreen(boolean openEditor) {
        super("eMaps", 100, 100);
        this.nameField = field("Nume punct", 40, null);
        this.editor = openEditor && MapsState.admin;
        if (openEditor) newDraftHere();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            vx = mc.player.getPosX();
            vz = mc.player.getPosZ();
        }
        MapsClient.requestPois();
    }

    @Override
    protected java.util.List<TextFieldWidget> fields() {
        return editor ? Collections.singletonList(nameField) : Collections.<TextFieldWidget>emptyList();
    }

    private void newDraftHere() {
        Minecraft mc = Minecraft.getInstance();
        draftId = 0;
        draftType = "altele";
        draftSet = mc.player != null;
        if (mc.player != null) {
            draftX = mc.player.getPosX();
            draftZ = mc.player.getPosZ();
        }
        nameField.setText("");
    }

    private void selectPoi(MapsState.Poi p) {
        draftId = p.id;
        draftType = p.type;
        draftX = p.x;
        draftZ = p.z;
        draftSet = true;
        nameField.setText(p.name);
    }

    private void save() {
        String nm = nameField.getText().trim();
        if (!draftSet || nm.isEmpty()) {
            toast("Scrie un nume si alege locul pe harta.", true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int h = MapCache.height((int) Math.floor(draftX), (int) Math.floor(draftZ));
        double y = h > 0 ? h : (mc.player != null ? mc.player.getPosY() : 64);
        MapsClient.savePoi(draftId, nm, draftType, draftX, y, draftZ);
        draftId = 0;
        draftSet = false;
        nameField.setText("");
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public void onClose() {
        if (view != null) view.close();
        view = null;
    }

    private int f() {
        return LEVELS[level][1];
    }

    private int scale() {
        return LEVELS[level][0];
    }

    private boolean inMap(double x, double y) {
        return Ui.in((int) x, (int) y, ax, ay, aw, ah);
    }

    private float gx(double wx) {
        return view.gx(wx, ax, aw, vx, f());
    }

    private float gy(double wz) {
        return view.gy(wz, ay, ah, vz, f());
    }

    private double wx(double sx) {
        return vx + (sx - (ax + aw / 2.0)) * scale() / f();
    }

    private double wz(double sy) {
        return vz + (sy - (ay + ah / 2.0)) * scale() / f();
    }

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        mx0 = mx;
        my0 = my;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        px = 0;
        py = 0;
        pw = width;
        ph = height;
        long now = System.currentTimeMillis();
        float appear = Math.min(1f, (now - born) / 200f);

        Ui.rect(ms, 0, 0, width, height, 0xFF0A0F18);

        // ---- harta pe tot ecranul (stil EGK)
        int f = f();
        aw = width / f * f;
        ah = height / f * f;
        ax = 0;
        ay = 0;
        if (view == null) view = new MapView(384, "flowcity_bigmap", false);
        if (follow) {
            vx = mc.player.getPosX();
            vz = mc.player.getPosZ();
        }
        if (view.needsRefresh(vx, vz, scale(), aw / f, ah / f, 6000)) view.refresh(MathHelper.floor(vx), MathHelper.floor(vz), scale());
        view.draw(ms, ax, ay, aw, ah, vx, vz, f);
        hit(ax, ay, aw, ah, () -> {
            if (editor) {
                draftX = wx(mx0);
                draftZ = wz(my0);
                draftSet = true;
            }
        });
        Ui.scissor(ax, ay, aw, ah);

        MapsClient.Proj pr = new MapsClient.Proj() {
            @Override
            public float x(double w) {
                return gx(w);
            }

            @Override
            public float y(double w) {
                return gy(w);
            }
        };
        MapsClient.drawRoute(ms, pr, 4, ax, ay, ax + aw, ay + ah);

        // blips
        MapsState.Poi hover = null;
        for (MapsState.Poi p : MapsState.pois) {
            if (MapsState.hidden.contains(p.type)) continue;
            int sx = Math.round(gx(p.x));
            int sy = Math.round(gy(p.z));
            if (sx < ax - 8 || sy < ay - 8 || sx > ax + aw + 8 || sy > ay + ah + 8) continue;
            boolean h = Ui.in(mx, my, sx - 9, sy - 9, 18, 18) && inMap(mx, my) && !overUi(mx, my);
            Blips.draw(ms, p.type, sx, sy, h ? 20 : 16, h);
            if (h) hover = p;
            final MapsState.Poi pp = p;
            hit(sx - 9, sy - 9, 18, 18, () -> {
                if (editor) {
                    selectPoi(pp);
                    return;
                }
                setGps(pp.name, pp.type, pp.x, pp.z);
            });
        }
        // waypoint
        if (MapsState.hasTarget) {
            int sx = Math.round(gx(MapsState.tx));
            int sy = Math.round(gy(MapsState.tz));
            float bob = (float) Math.sin(now / 250.0) * 1.5f;
            if (MapsState.targetType != null) Blips.draw(ms, MapsState.targetType, sx, sy - 22 + Math.round(bob), 16, true);
            Blips.pin(ms, sx, sy + Math.round(bob), 12, MapsClient.ROUTE_COLOR);
            double d = Math.hypot(MapsState.tx - mc.player.getPosX(), MapsState.tz - mc.player.getPosZ());
            String lab = MapsState.targetName + "  " + MapsClient.meters(d);
            int lw = Ui.smallCapsWidth(lab) + 10;
            Ui.rect(ms, sx - lw / 2, sy + 4, lw, 12, 0xDD0A0F18);
            Ui.smallCaps(ms, lab, sx - lw / 2 + 5, sy + 6, Theme.TEXT);
        }
        // punctul in editare
        if (editor && draftSet) {
            int sx = Math.round(gx(draftX));
            int sy = Math.round(gy(draftZ));
            float bob = (float) Math.sin(now / 200.0) * 1.5f;
            Ui.rect(ms, sx - 10, sy - 1, 21, 1, 0xAAFFFFFF);
            Ui.rect(ms, sx, sy - 10, 1, 21, 0xAAFFFFFF);
            Blips.draw(ms, draftType, sx, sy - 18 + Math.round(bob), 16, true);
            Blips.pin(ms, sx, sy + Math.round(bob), 10, 0xFFFFD54A);
        }
        // jucator
        MapsClient.drawPlayer(ms, gx(mc.player.getPosX()), gy(mc.player.getPosZ()), mc.player.rotationYaw);
        Ui.endScissor();

        // puncte cardinale pe margini
        Ui.textCenter(ms, "N", width / 2, 4, 0xFFFFFFFF);
        Ui.textCenter(ms, "S", width / 2, height - 12, 0xFFFFFFFF);
        Ui.text(ms, "W", 4, height / 2 - 4, 0xFFFFFFFF);
        Ui.text(ms, "E", width - 10, height / 2 - 4, 0xFFFFFFFF);

        // coordonate sus-centru
        String coords = inMap(mx, my) ? "X: " + (int) wx(mx) + " | Z: " + (int) wz(my) : "X: " + (int) mc.player.getPosX() + " | Z: " + (int) mc.player.getPosZ();
        Ui.textCenter(ms, coords, width / 2, 10, 0xFFE6ECFA);

        // tooltip blip (card)
        if (hover != null) {
            String nm = hover.name;
            String l2 = "Click: seteaza GPS";
            int w = Math.max(Ui.smallCapsWidth(nm), Ui.width(l2)) + 18;
            int tx = Math.min(width - w - 4, mx + 12);
            int ty = Math.max(4, my - 16);
            Ui.rect(ms, tx, ty, w, 30, 0xEE0B1530);
            Ui.rect(ms, tx, ty, 2, 30, Theme.ACCENT);
            Ui.smallCaps(ms, nm, tx + 8, ty + 5, Theme.TEXT);
            Ui.text(ms, l2, tx + 8, ty + 18, Theme.OK);
        }

        // ---- buton hamburger
        boolean hh = Ui.in(mx, my, 10, 10, 24, 24);
        Ui.rect(ms, 10, 10, 24, 24, hh ? 0xFF2A56C8 : 0xFF1E44A8);
        Ui.frame(ms, 10, 10, 24, 24, 0x00000000, 0xFF5B8CFF);
        for (int i = 0; i < 3; i++) Ui.rect(ms, 15, 16 + i * 5, 14, 2, 0xFFFFFFFF);
        hit(10, 10, 24, 24, () -> sidebar = !sidebar);

        // ---- legenda dreapta (pastile) sau editor
        int rx = width - 190;
        if (editor) {
            Ui.rect(ms, rx - 8, 0, 198, height, 0xE60A0F18);
            drawEditor(ms, mx, my, rx, 180);
        } else {
            drawLegend(ms, mx, my);
        }

        // ---- sidebar stanga
        if (sidebar) drawSidebar(ms, mx, my, mc);

        // ---- toast GPS setat
        long since = now - gpsToastAt;
        if (since < 3200 && gpsToastName != null) {
            float a = since < 200 ? since / 200f : (since > 2800 ? (3200 - since) / 400f : 1f);
            int al = (int) (a * 255) << 24;
            int tw = Math.max(200, Ui.smallCapsWidth("Destinatie: " + gpsToastName) + 60);
            int tx = width / 2 - tw / 2;
            int ty = 24 + (int) ((1f - a) * -10);
            Ui.rect(ms, tx, ty, tw, 36, (int) (a * 0xEE) << 24 | 0x0B2A24);
            Ui.rect(ms, tx, ty, 3, 36, al | 0x2ECC71);
            Ui.rect(ms, tx + 10, ty + 8, 20, 20, (int) (a * 0xFF) << 24 | 0x2A7FD0);
            Ui.smallCaps(ms, "OK", tx + 14, ty + 14, 0xFFFFFFFF);
            Ui.smallCaps(ms, "GPS setat", tx + 40, ty + 7, al | 0xFFFFFF);
            Ui.smallCaps(ms, "Destinatie: " + gpsToastName, tx + 40, ty + 20, al | 0xC8D2E6);
        }

        // ---- anulare GPS (jos-stanga)
        if (MapsState.hasTarget) {
            int bw = Ui.smallCapsWidth("Anulare GPS") + 34;
            boolean ch = Ui.in(mx, my, 10, height - 28, bw, 20);
            Ui.rect(ms, 10, height - 28, bw, 20, ch ? 0xDD2A1620 : 0xCC10131C);
            Ui.text(ms, "[", 16, height - 22, Theme.BAD);
            Ui.text(ms, "X", 22, height - 22, Theme.BAD);
            Ui.text(ms, "]", 29, height - 22, Theme.BAD);
            Ui.smallCaps(ms, "Anulare GPS", 40, height - 22, Theme.TEXT);
            hit(10, height - 28, bw, 20, () -> {
                MapsState.clearTarget();
                toast("GPS anulat", false);
            });
        }
    }

    private boolean overUi(int mx, int my) {
        if (mx >= width - 190) return true;
        if (sidebar && mx < 150) return true;
        return mx < 36 && my < 36;
    }

    private void setGps(String name, String type, double x, double z) {
        MapsClient.gpsTo(name, type, x, z);
        gpsToastName = name;
        gpsToastAt = System.currentTimeMillis();
    }

    private void drawLegend(MatrixStack ms, int mx, int my) {
        int total = Blips.TYPES.length;
        int perPage = Math.max(4, Math.min(15, (height - 60) / 28));
        int maxStart = Math.max(0, total - perPage);
        legendStart = Math.max(0, Math.min(legendStart, maxStart));
        int y = 10;
        for (int i = legendStart; i < Math.min(total, legendStart + perPage); i++) {
            String t = Blips.TYPES[i][0];
            String lab = Blips.TYPES[i][1];
            boolean on = !MapsState.hidden.contains(t);
            int tw = Ui.smallCapsWidth(lab);
            int w = tw + 44;
            int x = width - 10 - w;
            boolean h = Ui.in(mx, my, x, y, w, 24);
            Ui.rect(ms, x, y, w, 24, h ? 0xF0152A5E : 0xE60B1530);
            Ui.frame(ms, x, y, w, 24, 0x00000000, on ? 0xFF1E3A8A : 0xFF2A2F3F);
            Ui.smallCaps(ms, lab, x + 10, y + 8, on ? 0xFFFFFFFF : 0xFF6B7488);
            Blips.sprite(ms, Blips.icon(t), x + w - 24, y + 4, 16);
            if (!on) Ui.rect(ms, x + 6, y + 12, w - 12, 1, 0xAA6B7488);
            final String tt = t;
            hit(x, y, w, 24, () -> {
                if (!MapsState.hidden.remove(tt)) MapsState.hidden.add(tt);
            });
            y += 28;
        }
        // pagina n/total + sageti
        String pg = Math.min(total, legendStart + perPage) + "/" + total;
        int w = Ui.width(pg) + 44;
        int x = width - 10 - w;
        Ui.rect(ms, x, y, w, 24, 0xE60B1530);
        Ui.frame(ms, x, y, w, 24, 0x00000000, 0xFF1E3A8A);
        Ui.text(ms, pg, x + 10, y + 8, Theme.TEXT);
        Ui.text(ms, legendStart + perPage >= total ? "^" : "v", x + w - 18, y + 8, Theme.ACCENT);
        hit(x, y, w, 24, () -> legendStart = legendStart + perPage >= total ? 0 : Math.min(maxStart, legendStart + perPage));
    }

    private void drawSidebar(MatrixStack ms, int mx, int my, Minecraft mc) {
        int sx = 10;
        int sy = 40;
        int sw = 138;
        int sh = height - 50;
        Ui.rect(ms, sx, sy, sw, sh, 0xEE0A0F18);
        Ui.frame(ms, sx, sy, sw, sh, 0x00000000, 0xFF2A3A57);
        int yy = sy + 8;
        Ui.smallCaps(ms, "eMaps", sx + 8, yy, Theme.TEXT);
        Ui.rect(ms, sx + 8, yy + 11, sw - 16, 1, 0xFF2A3A57);
        yy += 18;
        if (MapsState.hasTarget) {
            double d = Math.hypot(MapsState.tx - mc.player.getPosX(), MapsState.tz - mc.player.getPosZ());
            Ui.smallCaps(ms, "GPS activ", sx + 8, yy, MapsClient.ROUTE_COLOR);
            Ui.text(ms, Ui.clip(MapsState.targetName, sw - 16), sx + 8, yy + 11, Theme.TEXT);
            Ui.text(ms, MapsClient.meters(d), sx + 8, yy + 22, Theme.MUTED);
            yy += 36;
        } else {
            Ui.text(ms, "Niciun GPS setat.", sx + 8, yy, Theme.FAINT);
            Ui.text(ms, "Click dreapta = GPS.", sx + 8, yy + 11, Theme.FAINT);
            yy += 26;
        }
        int bw = sw - 16;
        int hw = (bw - 4) / 2;
        button(ms, "ZOOM +", sx + 8, yy, hw, 15, mx, my, level > 0, Theme.ACCENT_DIM, () -> level = Math.max(0, level - 1));
        button(ms, "ZOOM -", sx + 12 + hw, yy, hw, 15, mx, my, level < LEVELS.length - 1, Theme.ACCENT_DIM, () -> level = Math.min(LEVELS.length - 1, level + 1));
        yy += 19;
        button(ms, "CENTREAZA PE MINE", sx + 8, yy, bw, 15, mx, my, !follow, Theme.ACCENT_DIM, () -> follow = true);
        yy += 19;
        button(ms, "ARATA TOATE ICOANELE", sx + 8, yy, bw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.hidden.clear());
        yy += 19;
        button(ms, "ASCUNDE ICOANELE", sx + 8, yy, bw, 15, mx, my, true, Theme.PANEL_3, () -> {
            for (String[] t : Blips.TYPES) MapsState.hidden.add(t[0]);
        });
        yy += 25;
        Ui.smallCaps(ms, "Minimapa", sx + 8, yy, Theme.MUTED);
        yy += 12;
        button(ms, MapsState.minimap ? "RADAR: ON" : "RADAR: OFF", sx + 8, yy, hw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.minimap = !MapsState.minimap);
        button(ms, MapsState.radarPos == 0 ? "JOS" : "SUS", sx + 12 + hw, yy, hw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.radarPos = 1 - MapsState.radarPos);
        yy += 19;
        button(ms, "RADAR ZOOM", sx + 8, yy, bw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.miniScale = MapsState.miniScale == 1 ? 2 : (MapsState.miniScale == 2 ? 4 : 1));
        yy += 25;
        if (MapsState.admin) {
            button(ms, editor ? "IESI DIN EDITOR" : "EDITOR GPS", sx + 8, yy, bw, 15, mx, my, true, editor ? Theme.OK : 0xFF8A5A00, () -> {
                editor = !editor;
                if (editor) newDraftHere();
            });
            yy += 19;
        }
        button(ms, "INCHIDE HARTA", sx + 8, sy + sh - 23, bw, 15, mx, my, true, 0xFF5B2A35, this::closeScreen);
    }

    private void drawEditor(MatrixStack ms, int mx, int my, int rx, int rw) {
        int yy = 36;
        Ui.rect(ms, rx, yy, rw, 3, 0xFF8A5A00);
        yy += 8;
        Ui.smallCaps(ms, "Editor de puncte", rx, yy, Theme.TEXT);
        yy += 14;
        Ui.text(ms, "Click pe harta = alege locul.", rx, yy, Theme.FAINT);
        Ui.text(ms, "Click pe un icon = editeaza-l.", rx, yy + 10, Theme.FAINT);
        yy += 26;

        Ui.smallCaps(ms, "Nume", rx, yy, Theme.MUTED);
        yy += 11;
        drawField(ms, nameField, rx, yy, rw, 16, mx, my);
        yy += 22;

        Ui.smallCaps(ms, "Icoana", rx, yy, Theme.MUTED);
        Ui.textRight(ms, Blips.label(draftType), rx + rw, yy, Theme.ACCENT);
        yy += 11;
        int cell = 22;
        int cols = Math.max(4, rw / cell);
        for (int i = 0; i < Blips.TYPES.length; i++) {
            String t = Blips.TYPES[i][0];
            int cx = rx + (i % cols) * cell;
            int cy = yy + (i / cols) * cell;
            boolean sel = t.equals(draftType);
            boolean h = Ui.in(mx, my, cx, cy, cell - 2, cell - 2);
            Ui.rect(ms, cx, cy, cell - 2, cell - 2, sel ? 0xFF8A5A00 : (h ? 0xFF1B2740 : 0xFF111A2B));
            Blips.sprite(ms, Blips.icon(t), cx + 2, cy + 2, cell - 6);
            hit(cx, cy, cell - 2, cell - 2, () -> draftType = t);
        }
        yy += ((Blips.TYPES.length + cols - 1) / cols) * cell + 4;

        Ui.smallCaps(ms, "Loc", rx, yy, Theme.MUTED);
        Ui.textRight(ms, draftSet ? (int) draftX + ", " + (int) draftZ : "-", rx + rw, yy, Theme.TEXT);
        yy += 13;
        int hw = (rw - 6) / 2;
        button(ms, draftId > 0 ? "SALVEAZA #" + draftId : "ADAUGA PUNCT", rx, yy, hw, 16, mx, my, draftSet, Theme.OK, this::save);
        button(ms, "PUNCT NOU", rx + hw + 6, yy, hw, 16, mx, my, true, Theme.ACCENT_DIM, this::newDraftHere);
        yy += 20;
        button(ms, "STERGE PUNCTUL", rx, yy, rw, 16, mx, my, draftId > 0, Theme.BAD, () -> {
            MapsClient.deletePoi(draftId);
            draftId = 0;
            draftSet = false;
            nameField.setText("");
        });
        yy += 24;

        Ui.smallCaps(ms, "Retea GPS", rx, yy, Theme.MUTED);
        yy += 12;
        String[][] items = {{"CHEIE", "gps_wrench"}, {"SEGMENT", "gps_path"}, {"SENS UNIC", "gps_oneway"}, {"DUBLU SENS", "gps_twoway"}, {"INTERSECTIE", "gps_intersection"}};
        for (int i = 0; i < items.length; i++) {
            final String it = items[i][1];
            int bx = rx + (i % 2) * (hw + 6);
            int by2 = yy + (i / 2) * 19;
            button(ms, items[i][0], bx, by2, hw, 16, mx, my, true, Theme.PANEL_3, () -> MapsClient.give(it));
        }
    }

    private int key(MatrixStack ms, int x, int y, String k, String label) {
        int w = Ui.width(k) + 8;
        Ui.rect(ms, x, y - 1, w, 12, 0xFF2A3A57);
        Ui.text(ms, k, x + 4, y + 1, Theme.TEXT);
        Ui.text(ms, label, x + w + 5, y + 1, Theme.MUTED);
        return x + w + 5 + Ui.width(label);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 1 && inMap(mx, my) && view != null && !overUi((int) mx, (int) my)) {
            double x = wx(mx);
            double z = wz(my);
            setGps("Waypoint", null, x, z);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && inMap(mx, my)) {
            vx -= dx * scale() / f();
            vz -= dy * scale() / f();
            follow = false;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inMap(mx, my) && !(mx >= width - 190 && !editor)) {
            if (delta > 0) level = Math.max(0, level - 1);
            else if (delta < 0) level = Math.min(LEVELS.length - 1, level + 1);
        } else {
            legendStart -= (int) Math.signum(delta);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (MapsClient.openKey != null && MapsClient.openKey.matchesKey(key, scan)) {
            closeScreen();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
