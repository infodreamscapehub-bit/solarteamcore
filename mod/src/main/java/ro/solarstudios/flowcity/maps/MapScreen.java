package ro.solarstudios.flowcity.maps;

import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.Mth;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Harta mare in stil GTA V (meniul de pauza): teren descoperit, iconite, filtre, waypoint cu ruta. */
public final class MapScreen extends FlowScreen {
    private static final int[][] LEVELS = {{1, 8}, {1, 4}, {1, 2}, {2, 2}, {4, 2}, {8, 2}, {16, 2}}; // {blocuri/texel, px GUI/texel}
    private static int level = 2;

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
    private final EditBox wpField;
    private final EditBox searchField;
    private boolean showPopup;
    private int popX;
    private int popY;
    private int popW;
    private int popH;
    private double popWx;
    private double popWz;
    private MapsState.Wp popWp;
    private boolean showDlg;
    private int dlgId;
    private int dlgColor = MapsState.PALETTE[0];
    private String dlgType = "";
    private double dlgX;
    private double dlgZ;
    private int selWp;
    private long lastClickAt;
    private String lastClickKey = "";
    private int srvScroll;
    private int perScroll;
    private int srvY0;
    private int srvY1;
    private int perY0;
    private int perY1;
    private int editorTab; // 0 = puncte, 1 = zone
    private int zoneScroll;
    private int zTool; // 0 alege, 1 dreptunghi, 2 poligon
    private int zId;
    private int zType = 1;
    private final List<double[]> zPts = new ArrayList<>();
    private boolean zDrag;
    private double zDragX;
    private double zDragZ;
    private boolean zPressing;
    private double zPressX;
    private double zPressY;
    private int legendStart;
    private String gpsToastName;
    private long gpsToastAt;
    private final EditBox nameField;
    private int draftId;
    private String draftType = "altele";
    private boolean draftSet;
    private double draftX;
    private double draftZ;

    public MapScreen() {
        this(false);
    }

    /** Tasta B: harta se deschide direct cu dialogul de waypoint nou la pozitia ta. */
    public static MapScreen quickWaypoint() {
        MapScreen s = new MapScreen(false);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) s.openDialog(null, mc.player.getX(), mc.player.getZ());
        return s;
    }

    /** Zoom cu pastrarea punctului de sub cursor (ca la Xaero). dir < 0 = mai aproape. */
    private void zoomAt(double mx, double my, int dir) {
        int next = Math.max(0, Math.min(LEVELS.length - 1, level + dir));
        if (next == level) return;
        boolean anchor = view != null && aw > 0 && inMap(mx, my);
        double wxm = anchor ? wx(mx) : vx;
        double wzm = anchor ? wz(my) : vz;
        level = next;
        if (!anchor) return;
        int nf = f();
        int naw = width / nf * nf;
        int nah = height / nf * nf;
        vx = wxm - (mx - naw / 2.0) * scale() / nf;
        vz = wzm - (my - nah / 2.0) * scale() / nf;
        follow = false;
    }

    public MapScreen(boolean openEditor) {
        super("eMaps", 100, 100);
        this.nameField = field("Nume punct", 40, null);
        this.wpField = field("Nume punct...", 24, null);
        this.searchField = field("Cauta...", 16, null);
        this.editor = openEditor && MapsState.admin;
        if (openEditor) newDraftHere();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            vx = mc.player.getX();
            vz = mc.player.getZ();
        }
        MapsClient.requestPois();
    }

    @Override
    protected java.util.List<EditBox> fields() {
        List<EditBox> l = new ArrayList<>();
        if (showDlg) l.add(wpField);
        else {
            if (editor) l.add(nameField);
            if (sidebar) l.add(searchField);
        }
        return l;
    }

    private void newDraftHere() {
        Minecraft mc = Minecraft.getInstance();
        draftId = 0;
        draftType = "altele";
        draftSet = mc.player != null;
        if (mc.player != null) {
            draftX = mc.player.getX();
            draftZ = mc.player.getZ();
        }
        nameField.setValue("");
    }

    private void selectPoi(MapsState.Poi p) {
        draftId = p.id;
        draftType = p.type;
        draftX = p.x;
        draftZ = p.z;
        draftSet = true;
        nameField.setValue(p.name);
    }

    private void save() {
        String nm = nameField.getValue().trim();
        if (!draftSet || nm.isEmpty()) {
            toast("Scrie un nume si alege locul pe harta.", true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int h = MapCache.height((int) Math.floor(draftX), (int) Math.floor(draftZ));
        double y = h > 0 ? h : (mc.player != null ? mc.player.getY() : 64);
        MapsClient.savePoi(draftId, nm, draftType, draftX, y, draftZ);
        draftId = 0;
        draftSet = false;
        nameField.setValue("");
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
    protected void draw(GuiGraphics ms, int mx, int my) {
        mx0 = mx;
        my0 = my;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        px = 0;
        py = 0;
        pw = width;
        ph = height;
        long now = System.currentTimeMillis();
        float appear = Math.min(1f, (now - born) / 200f);

        Ui.rect(ms, 0, 0, width, height, 0xFF14161A);

        // ---- harta pe tot ecranul (stil EGK)
        int f = f();
        aw = width / f * f;
        ah = height / f * f;
        ax = 0;
        ay = 0;
        if (view == null) view = new MapView(384, "flowcity_bigmap", false);
        if (follow) {
            vx = mc.player.getX();
            vz = mc.player.getZ();
        }
        if (view.needsRefresh(vx, vz, scale(), aw / f, ah / f, 6000)) view.refresh(Mth.floor(vx), Mth.floor(vz), scale());
        view.draw(ms, ax, ay, aw, ah, vx, vz, f);
        if (!(showPopup || showDlg)) hit(ax, ay, aw, ah, () -> {
            if (editor && editorTab == 0) {
                draftX = wx(mx0);
                draftZ = wz(my0);
                draftSet = true;
            }
        });
        Ui.scissor(ax, ay, aw, ah);
        if (MapsState.grid) drawGrid(ms);

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
        MapsState.Zone hoverZone = null;
        if (MapsState.zonesVisible || (editor && editorTab == 1)) {
            boolean canHover = inMap(mx, my) && !overUi(mx, my) && !(editor && editorTab == 1 && zTool != 0);
            double hxw = wx(mx);
            double hzw = wz(my);
            if (canHover) {
                for (int i = MapsState.zones.size() - 1; i >= 0; i--) {
                    if (MapsState.zones.get(i).contains(hxw, hzw)) {
                        hoverZone = MapsState.zones.get(i);
                        break;
                    }
                }
            }
            for (MapsState.Zone z : MapsState.zones) {
                if (editor && editorTab == 1 && z.id == zId && !zPts.isEmpty()) continue; // se deseneaza ciorna
                int rgb = MapsState.zoneRgb(z.type);
                int op = (Integer) MapsState.zoneType(z.type)[4];
                int al = Math.min(255, op * 255 / 100 + (z == hoverZone ? 40 : 0));
                fillPoly(ms, z.pts, al << 24 | rgb);
                outline(ms, z.pts, true, (z == hoverZone ? 0xFF000000 : 0xCC000000) | rgb);
            }
        }
        if (editor && editorTab == 1 && !zPts.isEmpty()) {
            int rgb = MapsState.zoneRgb(zType);
            if (zPts.size() >= 3) fillPoly(ms, zPts, 0x70000000 | rgb);
            outline(ms, zPts, zPts.size() >= 3, 0xFF000000 | rgb);
            for (double[] pt : zPts) Ui.rect(ms, Math.round(gx(pt[0])) - 2, Math.round(gy(pt[1])) - 2, 5, 5, 0xFFFFFFFF);
        }

        MapsClient.drawRoute(ms, pr, 4, ax, ay, ax + aw, ay + ah);

        boolean modal = showPopup || showDlg;
        // blips (puncte server)
        MapsState.Poi hover = null;
        for (MapsState.Poi p : MapsState.pois) {
            if (MapsState.hidden.contains(p.type)) continue;
            int sx = Math.round(gx(p.x));
            int sy = Math.round(gy(p.z));
            if (sx < ax - 8 || sy < ay - 8 || sx > ax + aw + 8 || sy > ay + ah + 8) continue;
            boolean h = !modal && Ui.in(mx, my, sx - 9, sy - 9, 18, 18) && inMap(mx, my) && !overUi(mx, my);
            Blips.draw(ms, p.type, sx, sy, h ? 20 : 16, h);
            if (h) hover = p;
            if (modal) continue;
            final MapsState.Poi pp = p;
            hit(sx - 9, sy - 9, 18, 18, () -> {
                if (editor && editorTab == 1) return;
                if (editor) {
                    selectPoi(pp);
                    return;
                }
                setGps(pp.name, pp.type, pp.x, pp.z);
            });
        }
        // waypoint-uri personale
        MapsState.Wp hoverWp = null;
        for (MapsState.Wp w : MapsState.wps) {
            int sx = Math.round(gx(w.x));
            int sy = Math.round(gy(w.z));
            if (sx < ax - 8 || sy < ay - 24 || sx > ax + aw + 8 || sy > ay + ah + 8) continue;
            boolean h = !modal && Ui.in(mx, my, sx - 8, sy - 18, 16, 20) && inMap(mx, my) && !overUi(mx, my);
            int size = h ? 15 : 13;
            if (w.id == selWp) Ui.rect(ms, sx - 9, sy - size - 3, 19, size + 6, 0x66FFFFFF);
            Blips.pin(ms, sx, sy, size, w.color);
            if (!w.type.isEmpty()) Blips.sprite(ms, Blips.icon(w.type), sx - 5, sy - size / 2 - 5, 10);
            String nm = Ui.clip(w.name, 70);
            int nw = Ui.smallCapsWidth(nm);
            Ui.rect(ms, sx - nw / 2 - 2, sy - size - 14, nw + 4, 10, 0x99000000);
            Ui.smallCaps(ms, nm, sx - nw / 2, sy - size - 13, 0xFFFFFFFF);
            if (h) hoverWp = w;
            if (modal) continue;
            final MapsState.Wp ww = w;
            hit(sx - 8, sy - size - 2, 16, size + 4, () -> clickWp(ww));
        }
        // locul ultimei morti
        if (MapsState.hasDeath) {
            int sx = Math.round(gx(MapsState.deathX));
            int sy = Math.round(gy(MapsState.deathZ));
            Blips.sprite(ms, Blips.icon("moarte"), sx - 6, sy - 6, 12);
        }
        // waypoint
        if (MapsState.hasTarget) {
            int sx = Math.round(gx(MapsState.tx));
            int sy = Math.round(gy(MapsState.tz));
            float bob = (float) Math.sin(now / 250.0) * 1.5f;
            if (MapsState.targetType != null) Blips.draw(ms, MapsState.targetType, sx, sy - 22 + Math.round(bob), 16, true);
            Blips.pin(ms, sx, sy + Math.round(bob), 12, MapsClient.ROUTE_COLOR);
            double d = Math.hypot(MapsState.tx - mc.player.getX(), MapsState.tz - mc.player.getZ());
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
        MapsClient.drawPlayer(ms, gx(mc.player.getX()), gy(mc.player.getZ()), mc.player.getYRot());
        Ui.endScissor();

        // nord + bara de jos: coordonate sub cursor, scara, scurtaturi
        Ui.textCenter(ms, "N", width / 2, 4, 0xCCFFFFFF);
        Ui.rect(ms, width / 2 - 2, 13, 5, 1, 0xCCFFFFFF);
        drawInfoBar(ms, mx, my, mc);

        // tooltip blip (card)
        if (hover != null) {
            String nm = hover.name;
            String l2 = "Click: seteaza GPS";
            int w = Math.max(Ui.smallCapsWidth(nm), Ui.width(l2)) + 18;
            int tx = Math.min(width - w - 4, mx + 12);
            int ty = Math.max(4, my - 16);
            Ui.rect(ms, tx, ty, w, 30, 0xEE16181D);
            Ui.rect(ms, tx, ty, 2, 30, Theme.ACCENT);
            Ui.smallCaps(ms, nm, tx + 8, ty + 5, Theme.TEXT);
            Ui.text(ms, l2, tx + 8, ty + 18, Theme.OK);
        }

        if (!modal && hover == null && hoverWp != null) {
            String l2 = "Dublu click: GPS   DEL: sterge";
            int w = Math.max(Ui.smallCapsWidth(hoverWp.name), Ui.width(l2)) + 18;
            int tx = Math.min(width - w - 4, mx + 12);
            int ty = Math.max(4, my - 16);
            Ui.rect(ms, tx, ty, w, 42, 0xEE16181D);
            Ui.rect(ms, tx, ty, 3, 42, hoverWp.color);
            Ui.smallCaps(ms, hoverWp.name, tx + 9, ty + 5, hoverWp.color);
            Ui.text(ms, "Coords: " + (int) hoverWp.x + ", " + (int) hoverWp.z, tx + 9, ty + 17, Theme.MUTED);
            Ui.text(ms, l2, tx + 9, ty + 29, Theme.OK);
        } else if (hover == null && hoverZone != null) {
            Object[] t = MapsState.zoneType(hoverZone.type);
            int w = Math.max(Ui.smallCapsWidth(hoverZone.name), Ui.smallCapsWidth(t[1] + " - " + t[2])) + 18;
            int tx = Math.min(width - w - 4, mx + 12);
            int ty = Math.max(4, my - 16);
            Ui.rect(ms, tx, ty, w, 30, 0xEE16181D);
            Ui.rect(ms, tx, ty, 3, 30, 0xFF000000 | MapsState.zoneRgb(hoverZone.type));
            Ui.smallCaps(ms, hoverZone.name, tx + 9, ty + 5, Theme.TEXT);
            Ui.smallCaps(ms, t[1] + " - " + t[2], tx + 9, ty + 18, 0xFF000000 | MapsState.zoneRgb(hoverZone.type));
        }

        // ---- buton hamburger
        boolean hh = Ui.in(mx, my, 10, 10, 24, 24);
        Ui.round(ms, 10, 10, 24, 24, sidebar ? 0xF0262A32 : (hh ? 0xF0262A32 : 0xE016181D));
        for (int i = 0; i < 3; i++) Ui.rect(ms, 16, 16 + i * 5, 12, 2, sidebar ? Theme.ACCENT : Theme.TEXT);
        hit(10, 10, 24, 24, () -> sidebar = !sidebar);

        // ---- legenda dreapta (pastile) sau editor
        int rx = width - 190;
        if (editor) {
            Ui.rect(ms, rx - 8, 0, 198, height, 0xF016181D);
            Ui.rect(ms, rx - 8, 0, 1, height, Theme.LINE);
            drawEditor(ms, mx, my, rx, 180);
        } else {
            drawLegend(ms, mx, my);
        }

        // ---- sidebar stanga
        if (sidebar) drawSidebar(ms, mx, my, mc);

        if (showPopup) drawPopup(ms, mx, my);
        if (showDlg) drawDialog(ms, mx, my);

        // ---- toast GPS setat
        long since = now - gpsToastAt;
        if (since < 3200 && gpsToastName != null) {
            float a = since < 200 ? since / 200f : (since > 2800 ? (3200 - since) / 400f : 1f);
            int al = (int) (a * 255) << 24;
            int tw = Math.max(200, Ui.smallCapsWidth("Destinatie: " + gpsToastName) + 60);
            int tx = width / 2 - tw / 2;
            int ty = 24 + (int) ((1f - a) * -10);
            Ui.round(ms, tx, ty, tw, 32, (int) (a * 0xF0) << 24 | 0x16181D);
            Ui.rect(ms, tx, ty + 1, 2, 30, al | 0xE9A23B);
            Ui.smallCaps(ms, "GPS setat", tx + 12, ty + 6, al | 0xFFFFFF);
            Ui.text(ms, "Destinatie: " + gpsToastName, tx + 12, ty + 18, al | 0xA19F98);
        }

        // ---- anulare GPS (jos-stanga)
        if (MapsState.hasTarget) {
            int bw = Ui.smallCapsWidth("Anulare GPS") + 34;
            boolean ch = Ui.in(mx, my, 10, height - 46, bw, 20);
            int gy0 = height - 46;
            Ui.round(ms, 10, gy0, bw, 20, ch ? 0xF0262A32 : 0xE016181D);
            Ui.rect(ms, 10, gy0 + 1, 2, 18, Theme.BAD);
            Ui.text(ms, "x", 20, gy0 + 6, Theme.BAD);
            Ui.smallCaps(ms, "Anulare GPS", 32, gy0 + 6, Theme.TEXT);
            hit(10, gy0, bw, 20, () -> {
                MapsState.clearTarget();
                toast("GPS anulat", false);
            });
        }
    }

    private boolean overUi(int mx, int my) {
        if (mx >= width - (editor ? 198 : 190)) return true;
        if (sidebar && mx < 164) return true;
        return mx < 36 && my < 36;
    }

    private void setGps(String name, String type, double x, double z) {
        MapsClient.gpsTo(name, type, x, z);
        gpsToastName = name;
        gpsToastAt = System.currentTimeMillis();
    }

    private void drawLegend(GuiGraphics ms, int mx, int my) {
        List<MapsState.Poi> list = new ArrayList<>(MapsState.pois);
        Collections.sort(list, Comparator.comparing((MapsState.Poi q) -> q.name.toLowerCase(Locale.ROOT)));
        int total = list.size();
        int perPage = Math.max(3, Math.min(15, (height - 90) / 28));
        int maxStart = Math.max(0, total - perPage);
        legendStart = Math.max(0, Math.min(legendStart, maxStart));
        int y = 10;
        for (int i = legendStart; i < Math.min(total, legendStart + perPage); i++) {
            final MapsState.Poi p = list.get(i);
            boolean on = !MapsState.hidden.contains(p.type);
            String lab = Ui.clip(p.name, 150);
            int w = Ui.smallCapsWidth(lab) + 44;
            int x = width - 10 - w;
            boolean h = Ui.in(mx, my, x, y, w, 24) && !(showPopup || showDlg);
            Ui.round(ms, x, y, w, 24, h ? 0xF0262A32 : 0xE016181D);
            Ui.rect(ms, x, y + 1, 2, 22, on ? Theme.ACCENT : Theme.LINE);
            Ui.smallCaps(ms, lab, x + 10, y + 8, on ? 0xFFFFFFFF : Theme.FAINT);
            Blips.sprite(ms, Blips.icon(p.type), x + w - 24, y + 4, 16);
            if (!(showPopup || showDlg)) {
                hit(x, y, w, 24, () -> {
                    vx = p.x;
                    vz = p.z;
                    follow = false;
                    setGps(p.name, p.type, p.x, p.z);
                });
            }
            y += 28;
        }
        // comutator zone
        {
            String lab = "Zone";
            int w = Ui.smallCapsWidth(lab) + 44;
            int x = width - 10 - w;
            boolean on = MapsState.zonesVisible;
            boolean h = Ui.in(mx, my, x, y, w, 24);
            Ui.round(ms, x, y, w, 24, h ? 0xF0262A32 : 0xE016181D);
            Ui.rect(ms, x, y + 1, 2, 22, on ? Theme.ACCENT : Theme.LINE);
            Ui.smallCaps(ms, lab, x + 10, y + 8, on ? 0xFFFFFFFF : Theme.FAINT);
            for (int i = 0; i < 4; i++) Ui.rect(ms, x + w - 38 + (i % 2) * 8, y + 4 + (i / 2) * 8, 7, 7, (on ? 0xFF000000 : 0x66000000) | MapsState.zoneRgb(i + 1));
            if (!(showPopup || showDlg)) hit(x, y, w, 24, () -> {
                MapsState.zonesVisible = !MapsState.zonesVisible;
                MapsState.saveSettings();
            });
            y += 28;
        }
        if (total > perPage) {
            String pg = Math.min(total, legendStart + perPage) + "/" + total;
            int w = Ui.width(pg) + 44;
            int x = width - 10 - w;
            Ui.round(ms, x, y, w, 24, 0xE016181D);
            Ui.text(ms, pg, x + 10, y + 8, Theme.TEXT);
            Ui.text(ms, legendStart + perPage >= total ? "^" : "v", x + w - 18, y + 8, Theme.ACCENT);
            if (!(showPopup || showDlg)) hit(x, y, w, 24, () -> legendStart = legendStart + perPage >= total ? 0 : Math.min(maxStart, legendStart + perPage));
        }
    }

    private void drawGrid(GuiGraphics ms) {
        int sc = scale();
        int f = f();
        double chunkPx = 16.0 * f / sc;
        int step = chunkPx >= 8 ? 16 : (chunkPx * 32 >= 24 ? 512 : 0);
        if (step == 0) return;
        double left = wx(ax), right = wx(ax + aw), top = wz(ay), bottom = wz(ay + ah);
        for (long gx0 = (long) Math.floor(left / step) * step; gx0 <= right; gx0 += step) {
            int sx = Math.round(gx(gx0));
            boolean region = Math.floorMod(gx0, 512) == 0;
            Ui.rect(ms, sx, ay, 1, ah, region ? 0x50FFFFFF : 0x1EFFFFFF);
        }
        for (long gz0 = (long) Math.floor(top / step) * step; gz0 <= bottom; gz0 += step) {
            int sy = Math.round(gy(gz0));
            boolean region = Math.floorMod(gz0, 512) == 0;
            Ui.rect(ms, ax, sy, aw, 1, region ? 0x50FFFFFF : 0x1EFFFFFF);
        }
    }

    /** Bara de jos: coordonatele de sub cursor (cu inaltimea cunoscuta), scara si scurtaturile. */
    private void drawInfoBar(GuiGraphics ms, int mx, int my, Minecraft mc) {
        boolean cur = inMap(mx, my) && !overUi(mx, my);
        int bx = cur ? (int) Math.floor(wx(mx)) : (int) Math.floor(mc.player.getX());
        int bz = cur ? (int) Math.floor(wz(my)) : (int) Math.floor(mc.player.getZ());
        int hy = MapCache.height(bx, bz);
        String coords = "X " + bx + "   Y " + (hy > 0 ? String.valueOf(hy) : "?") + "   Z " + bz;
        int cw = Ui.width(coords) + 16;
        int by = height - 22;
        Ui.round(ms, width / 2 - cw / 2, by, cw, 16, 0xE016181D);
        Ui.textCenter(ms, coords, width / 2, by + 4, cur ? Theme.TEXT : Theme.MUTED);

        // scara: o bara de lungime "rotunda" in metri
        double mPerPx = (double) scale() / f();
        int[] nice = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};
        int meters = nice[nice.length - 1];
        for (int n : nice) if (n / mPerPx >= 40) {
            meters = n;
            break;
        }
        int len = (int) Math.round(meters / mPerPx);
        int sx = width - (editor ? 206 : 14) - len;
        if (!editor) sx = Math.min(sx, width - 14 - len);
        Ui.rect(ms, sx, by + 10, len, 2, 0xE0FFFFFF);
        Ui.rect(ms, sx, by + 6, 1, 6, 0xE0FFFFFF);
        Ui.rect(ms, sx + len - 1, by + 6, 1, 6, 0xE0FFFFFF);
        Ui.rect(ms, sx - 2, by + 12, len + 4, 1, 0x60000000);
        String lab = meters >= 1000 ? (meters / 1000.0 + " km").replace(".0 ", " ") : meters + " m";
        Ui.textRight(ms, lab, sx + len, by - 4, 0xE0FFFFFF);

        // scurtaturi (doar pe ecrane late)
        if (width > 520 && !editor) {
            int kx = 44;
            if (MapsState.hasTarget) return;
            kx = key(ms, kx, by + 3, "B", "waypoint") + 10;
            kx = key(ms, kx, by + 3, "Spatiu", "centreaza") + 10;
            kx = key(ms, kx, by + 3, "G", "grila") + 10;
            key(ms, kx, by + 3, "T", "stil");
        }
    }

    private void toggleRow(GuiGraphics ms, int x, int y, int w, String label, boolean on, int mx, int my, Runnable r) {
        boolean h = Ui.in(mx, my, x, y, w, 14);
        if (h) Ui.rect(ms, x - 2, y - 1, w + 4, 15, 0x14FFFFFF);
        Ui.text(ms, label, x, y + 3, h ? Theme.TEXT : 0xFFD4D2CC);
        // comutator mic
        int sx = x + w - 20;
        Ui.round(ms, sx, y + 3, 20, 8, on ? Theme.ACCENT_DIM : Theme.PANEL_3);
        Ui.round(ms, on ? sx + 11 : sx + 1, y + 2, 8, 10, on ? Theme.ACCENT : 0xFF8A8C92);
        hit(x - 2, y - 1, w + 4, 15, () -> {
            r.run();
            MapsState.saveSettings();
        });
    }

    private void drawSidebar(GuiGraphics ms, int mx, int my, Minecraft mc) {
        int sx = 10;
        int sy = 40;
        int sw = 154;
        int sh = height - 50;
        Ui.round(ms, sx, sy, sw, sh, 0xF016181D);
        Ui.smallCaps(ms, "Harta", sx + 8, sy + 8, Theme.TEXT);
        Ui.textRight(ms, MapCache.chunkCount() + " chunk-uri", sx + sw - 8, sy + 8, Theme.FAINT);
        drawField(ms, searchField, sx + 8, sy + 22, sw - 16, 14, mx, my);

        int bw = sw - 16;
        int hw = (bw - 4) / 2;
        int bottomH = 6 * 15 + 2 * 18 + 8;
        int listTop = sy + 44;
        int listBot = sy + sh - bottomH - 6;
        int boxH = Math.max(40, (listBot - listTop) / 2);
        String q = searchField.getValue().trim().toLowerCase(Locale.ROOT);

        List<Object[]> srv = new ArrayList<>();
        for (MapsState.Poi p : MapsState.pois) if (q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q)) srv.add(new Object[] {p.name, Theme.ACCENT, p.type, p.x, p.z, p.id, false});
        Collections.sort(srv, Comparator.comparing((Object[] o) -> ((String) o[0]).toLowerCase(Locale.ROOT)));
        List<Object[]> per = new ArrayList<>();
        for (MapsState.Wp w : MapsState.wps) if (q.isEmpty() || w.name.toLowerCase(Locale.ROOT).contains(q)) per.add(new Object[] {w.name, w.color, w.type, w.x, w.z, w.id, true});

        srvY0 = listTop;
        srvY1 = listTop + boxH - 4;
        perY0 = listTop + boxH;
        perY1 = listTop + 2 * boxH - 4;
        srvScroll = listBox(ms, mx, my, sx + 8, srvY0, bw, srvY1 - srvY0, "Locuri din oras", srv, srvScroll);
        perScroll = listBox(ms, mx, my, sx + 8, perY0, bw, perY1 - perY0, "Waypoint-urile mele", per, perScroll);

        int yy = sy + sh - bottomH;
        Ui.rect(ms, sx + 8, yy - 4, bw, 1, Theme.LINE);
        toggleRow(ms, sx + 8, yy, bw, "Teren real", MapsState.terrain, mx, my, () -> MapsState.terrain = !MapsState.terrain);
        yy += 15;
        toggleRow(ms, sx + 8, yy, bw, "Waypoint-uri in lume", MapsState.worldMarkers, mx, my, () -> MapsState.worldMarkers = !MapsState.worldMarkers);
        yy += 15;
        toggleRow(ms, sx + 8, yy, bw, "Grila chunk-uri", MapsState.grid, mx, my, () -> MapsState.grid = !MapsState.grid);
        yy += 15;
        toggleRow(ms, sx + 8, yy, bw, "Zone", MapsState.zonesVisible, mx, my, () -> MapsState.zonesVisible = !MapsState.zonesVisible);
        yy += 15;
        toggleRow(ms, sx + 8, yy, bw, "Minimap", MapsState.minimap, mx, my, () -> MapsState.minimap = !MapsState.minimap);
        yy += 15;
        toggleRow(ms, sx + 8, yy, bw, "Minimap sus", MapsState.radarPos == 1, mx, my, () -> MapsState.radarPos = 1 - MapsState.radarPos);
        yy += 18;
        ghost(ms, "-", sx + 8, yy, 20, 15, mx, my, () -> zoomAt(-1, -1, 1));
        ghost(ms, "+", sx + 30, yy, 20, 15, mx, my, () -> zoomAt(-1, -1, -1));
        button(ms, "Waypoint aici", sx + 54, yy, bw - 46, 15, mx, my, true, Theme.ACCENT, () -> openDialog(null, mc.player.getX(), mc.player.getZ()));
        yy += 18;
        if (MapsState.admin) {
            button(ms, editor ? "Iesi din editor" : "Editor admin", sx + 8, yy, hw, 15, mx, my, true, editor ? Theme.OK : Theme.PANEL_3, () -> {
                editor = !editor;
                editorTab = 0;
                if (editor) newDraftHere();
            });
            ghost(ms, follow ? "Inchide" : "Centreaza", sx + 12 + hw, yy, hw, 15, mx, my, () -> {
                if (follow) onClose();
                else follow = true;
            });
        } else {
            ghost(ms, "Centreaza pe mine", sx + 8, yy, bw, 15, mx, my, () -> follow = true);
        }
    }

    /** Lista cu scroll (locuri server / waypoint-uri). Intoarce scroll-ul corectat. */
    private int listBox(GuiGraphics ms, int mx, int my, int x, int y, int w, int h, String title, List<Object[]> items, int scroll) {
        Ui.smallCaps(ms, title, x, y, Theme.MUTED);
        Ui.textRight(ms, String.valueOf(items.size()), x + w, y, Theme.FAINT);
        int lineH = 16;
        int rows = Math.max(1, (h - 14) / lineH);
        int maxScroll = Math.max(0, items.size() - rows);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        boolean modal = showPopup || showDlg;
        if (items.isEmpty()) Ui.text(ms, title.startsWith("Way") ? "Click dreapta pe harta." : "Nimic aici.", x + 2, y + 16, Theme.FAINT);
        for (int i = 0; i < rows && scroll + i < items.size(); i++) {
            final Object[] it = items.get(scroll + i);
            int cy = y + 13 + i * lineH;
            int rw = w - (items.size() > rows ? 5 : 0);
            boolean hov = !modal && Ui.in(mx, my, x, cy, rw, 15);
            boolean sel = (Boolean) it[6] && (Integer) it[5] == selWp;
            if (sel || hov) Ui.rect(ms, x, cy, rw, 15, sel ? 0xFF262A32 : 0x14FFFFFF);
            if (sel) Ui.rect(ms, x, cy, 2, 15, Theme.ACCENT);
            String t = (String) it[2];
            if (t != null && !t.isEmpty()) Blips.sprite(ms, Blips.icon(t), x + 3, cy + 2, 11);
            else Ui.round(ms, x + 5, cy + 4, 7, 7, (Integer) it[1]);
            Ui.text(ms, Ui.clip((String) it[0], rw - 20), x + 18, cy + 4, hov || sel ? 0xFFFFFFFF : 0xFFCFCDC6);
            if (!modal) {
                hit(x, cy, rw, 15, () -> {
                    vx = (Double) it[3];
                    vz = (Double) it[4];
                    follow = false;
                    String k = (Boolean) it[6] ? "w" + it[5] : "p" + it[5];
                    long now = System.currentTimeMillis();
                    if ((Boolean) it[6]) selWp = (Integer) it[5];
                    if (k.equals(lastClickKey) && now - lastClickAt < 450) {
                        setGps((String) it[0], t == null || t.isEmpty() ? null : t, (Double) it[3], (Double) it[4]);
                    }
                    lastClickKey = k;
                    lastClickAt = now;
                });
            }
        }
        if (items.size() > rows) {
            int bx = x + w - 2;
            int by = y + 13;
            int bh = rows * lineH - 2;
            int th = Math.max(4, rows * bh / items.size());
            int ty = by + (maxScroll == 0 ? 0 : scroll * (bh - th) / maxScroll);
            Ui.rect(ms, bx, by, 1, bh, 0x20FFFFFF);
            Ui.rect(ms, bx, ty, 1, th, 0x80FFFFFF);
        }
        return scroll;
    }

    // ------------------------------------------------------------------ waypoint-uri personale (meniu click dreapta + dialog)

    private void clickWp(MapsState.Wp w) {
        long now = System.currentTimeMillis();
        String k = "w" + w.id;
        selWp = w.id;
        if (k.equals(lastClickKey) && now - lastClickAt < 450) setGps(w.name, w.type.isEmpty() ? null : w.type, w.x, w.z);
        lastClickKey = k;
        lastClickAt = now;
    }

    private MapsState.Wp wpAt(double mx, double my) {
        for (int i = MapsState.wps.size() - 1; i >= 0; i--) {
            MapsState.Wp w = MapsState.wps.get(i);
            int sx = Math.round(gx(w.x));
            int sy = Math.round(gy(w.z));
            if (Ui.in((int) mx, (int) my, sx - 8, sy - 18, 16, 20)) return w;
        }
        return null;
    }

    private void openDialog(MapsState.Wp w, double x, double z) {
        showDlg = true;
        showPopup = false;
        dlgId = w == null ? 0 : w.id;
        dlgColor = w == null ? MapsState.PALETTE[0] : w.color;
        dlgType = w == null ? "" : w.type;
        dlgX = w == null ? x : w.x;
        dlgZ = w == null ? z : w.z;
        wpField.setValue(w == null ? "" : w.name);
        wpField.setFocused(true);
    }

    private void drawPopup(GuiGraphics ms, int mx, int my) {
        List<String[]> items = new ArrayList<>();
        items.add(new String[] {"Seteaza GPS aici", "gps", "ok"});
        if (popWp != null) {
            items.add(new String[] {"Editeaza waypoint", "edit", ""});
            items.add(new String[] {"Sterge waypoint", "del", "bad"});
        } else {
            items.add(new String[] {"Adauga waypoint", "add", ""});
            if (MapsState.admin) items.add(new String[] {"Adauga punct server", "srv", ""});
        }
        items.add(new String[] {"Centreaza harta aici", "center", ""});
        items.add(new String[] {"Copiaza coordonatele", "copy", ""});
        popW = 150;
        popH = items.size() * 16 + 22;
        popX = Math.min(Math.max(2, popX), width - popW - 2);
        popY = Math.min(Math.max(2, popY), height - popH - 2);
        Ui.round(ms, popX + 1, popY + 2, popW, popH, 0x60000000);
        Ui.round(ms, popX, popY, popW, popH, 0xF516181D);
        String head = popWp != null ? popWp.name : (int) popWx + ", " + (int) popWz;
        Ui.text(ms, Ui.clip(head, popW - 16), popX + 8, popY + 6, popWp != null ? popWp.color | 0xFF000000 : Theme.FAINT);
        Ui.rect(ms, popX + 6, popY + 17, popW - 12, 1, Theme.LINE);
        for (int i = 0; i < items.size(); i++) {
            final String[] it = items.get(i);
            int iy = popY + 20 + i * 16;
            boolean h = Ui.in(mx, my, popX + 3, iy, popW - 6, 15);
            if (h) Ui.rect(ms, popX + 3, iy, popW - 6, 15, 0xFF262A32);
            int col = it[2].equals("bad") ? Theme.BAD : (h ? Theme.TEXT : 0xFFD4D2CC);
            Ui.text(ms, it[0], popX + 9, iy + 4, col);
            hit(popX + 3, iy, popW - 6, 15, () -> popupAction(it[1]));
        }
    }

    private void popupAction(String id) {
        showPopup = false;
        switch (id) {
            case "gps":
                if (popWp != null) setGps(popWp.name, popWp.type.isEmpty() ? null : popWp.type, popWp.x, popWp.z);
                else setGps("Waypoint", null, popWx, popWz);
                break;
            case "add":
                openDialog(null, popWx, popWz);
                break;
            case "edit":
                openDialog(popWp, 0, 0);
                break;
            case "del":
                if (popWp != null) MapsClient.deleteWp(popWp.id);
                break;
            case "center":
                vx = popWp != null ? popWp.x : popWx;
                vz = popWp != null ? popWp.z : popWz;
                follow = false;
                break;
            case "copy": {
                double x = popWp != null ? popWp.x : popWx;
                double z = popWp != null ? popWp.z : popWz;
                int h = MapCache.height((int) Math.floor(x), (int) Math.floor(z));
                Minecraft.getInstance().keyboardHandler.setClipboard((int) Math.floor(x) + " " + (h > 0 ? h : "~") + " " + (int) Math.floor(z));
                toast("Coordonate copiate.", false);
                break;
            }
            case "srv":
                editor = true;
                editorTab = 0;
                newDraftHere();
                draftX = popWx;
                draftZ = popWz;
                draftSet = true;
                break;
            default:
                break;
        }
    }

    private int[] dlgBounds() {
        int dw = 262;
        int dh = Math.min(height - 8, 262);
        return new int[] {(width - dw) / 2, (height - dh) / 2, dw, dh};
    }

    private void drawDialog(GuiGraphics ms, int mx, int my) {
        int[] b = dlgBounds();
        int dx = b[0];
        int dy = b[1];
        int dw = b[2];
        int dh = b[3];
        Ui.rect(ms, 0, 0, width, height, 0x88000000);
        Ui.round(ms, dx + 1, dy + 3, dw, dh, 0x70000000);
        Ui.round(ms, dx, dy, dw, dh, 0xFA16181D);
        Ui.rect(ms, dx + 1, dy + 24, dw - 2, 1, Theme.LINE);
        Ui.smallCaps(ms, dlgId > 0 ? "Editeaza waypoint" : "Waypoint nou", dx + 10, dy + 8, Theme.TEXT);
        Ui.textRight(ms, "X " + (int) dlgX + "  Z " + (int) dlgZ, dx + dw - 10, dy + 8, Theme.FAINT);

        // previzualizare: cum arata pe harta
        int pvx = dx + dw - 26;
        int pvy = dy + 52;
        if (dlgType.isEmpty()) Blips.pin(ms, pvx, pvy + 6, 13, dlgColor);
        else Blips.draw(ms, dlgType, pvx, pvy - 2, 18, false);

        Ui.text(ms, "Nume", dx + 10, dy + 32, Theme.MUTED);
        drawField(ms, wpField, dx + 10, dy + 43, dw - 56, 16, mx, my);

        Ui.text(ms, "Iconita", dx + 10, dy + 67, Theme.MUTED);
        Ui.textRight(ms, dlgType.isEmpty() ? "pin simplu" : Blips.label(dlgType), dx + dw - 10, dy + 67, Theme.FAINT);
        int cols = 10;
        int cell = 24;
        int gx0 = dx + (dw - cols * cell) / 2;
        int count = Blips.TYPES.length + 1;
        for (int i = 0; i < count; i++) {
            final String t = i == 0 ? "" : Blips.TYPES[i - 1][0];
            if (!t.isEmpty() && (t.equals("altele") || t.equals("atm"))) continue;
            int k = i - (i > indexOf("atm") ? 1 : 0) - (i > indexOf("altele") ? 1 : 0);
            int cx = gx0 + (k % cols) * cell;
            int cy = dy + 78 + (k / cols) * cell;
            boolean sel = t.equals(dlgType);
            boolean h = Ui.in(mx, my, cx, cy, cell - 2, cell - 2);
            if (sel || h) Ui.round(ms, cx, cy, cell - 2, cell - 2, sel ? 0xFF33302A : 0xFF262A32);
            if (sel) Ui.rect(ms, cx + 2, cy + cell - 3, cell - 6, 1, Theme.ACCENT);
            if (t.isEmpty()) Blips.pin(ms, cx + 11, cy + 18, 11, dlgColor);
            else Blips.sprite(ms, Blips.icon(t), cx + 2, cy + 2, cell - 6);
            hit(cx, cy, cell - 2, cell - 2, () -> dlgType = t);
        }
        int rowsI = (count - 2 + cols - 1) / cols;
        int cyY = dy + 80 + rowsI * cell;

        Ui.text(ms, "Culoare", dx + 10, cyY, Theme.MUTED);
        cyY += 11;
        for (int i = 0; i < MapsState.PALETTE.length; i++) {
            final int col = MapsState.PALETTE[i];
            int cx = gx0 + (i % 10) * cell + 4;
            int cy = cyY + (i / 10) * 17;
            boolean sel = col == dlgColor;
            if (sel) Ui.round(ms, cx - 2, cy - 2, 18, 18, 0xFFFFFFFF);
            else if (Ui.in(mx, my, cx, cy, 14, 14)) Ui.round(ms, cx - 1, cy - 1, 16, 16, 0x88FFFFFF);
            Ui.round(ms, cx, cy, 14, 14, col);
            hit(cx, cy, 14, 14, () -> dlgColor = col);
        }

        int by = dy + dh - 26;
        Minecraft mc = Minecraft.getInstance();
        ghost(ms, "Pozitia mea", dx + 10, by, 74, 18, mx, my, () -> {
            if (mc.player != null) {
                dlgX = mc.player.getX();
                dlgZ = mc.player.getZ();
            }
        });
        ghost(ms, "Anuleaza", dx + dw - 150, by, 66, 18, mx, my, () -> showDlg = false);
        button(ms, "Salveaza", dx + dw - 80, by, 70, 18, mx, my, true, Theme.ACCENT, this::saveWp);
    }

    private static int indexOf(String type) {
        for (int i = 0; i < Blips.TYPES.length; i++) if (Blips.TYPES[i][0].equals(type)) return i + 1;
        return Integer.MAX_VALUE;
    }

    private void saveWp() {
        String nm = wpField.getValue().trim();
        if (nm.isEmpty()) {
            toast("Scrie un nume pentru waypoint.", true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int h = MapCache.height((int) Math.floor(dlgX), (int) Math.floor(dlgZ));
        double y = h > 0 ? h : (mc.player != null ? mc.player.getY() : 64);
        MapsClient.saveWp(dlgId, nm, dlgColor, dlgType, dlgX, y, dlgZ);
        showDlg = false;
    }

    private void drawEditor(GuiGraphics ms, int mx, int my, int rx, int rw) {
        int tw = (rw - 4) / 2;
        button(ms, "Puncte", rx, 10, tw, 18, mx, my, true, editorTab == 0 ? Theme.ACCENT : Theme.PANEL_3, () -> {
            editorTab = 0;
            newDraftHere();
        });
        button(ms, "Zone", rx + tw + 4, 10, tw, 18, mx, my, true, editorTab == 1 ? Theme.ACCENT : Theme.PANEL_3, () -> {
            editorTab = 1;
            newZone();
        });
        if (editorTab == 1) drawZoneEditor(ms, mx, my, rx, rw);
        else drawPoiEditor(ms, mx, my, rx, rw);
    }

    private void newZone() {
        zId = 0;
        zPts.clear();
        zDrag = false;
        nameField.setValue("");
    }

    private void selectZone(MapsState.Zone z) {
        zId = z.id;
        zType = z.type;
        zPts.clear();
        for (double[] p : z.pts) zPts.add(new double[] {p[0], p[1]});
        nameField.setValue(z.name);
    }

    private void saveZone() {
        String nm = nameField.getValue().trim();
        if (nm.isEmpty() || zPts.size() < 3) {
            toast("Scrie un nume si deseneaza zona (min. 3 colturi).", true);
            return;
        }
        MapsClient.saveZone(zId, nm, zType, zPts);
        newZone();
    }

    private void drawZoneEditor(GuiGraphics ms, int mx, int my, int rx, int rw) {
        int yy = 36;
        yy += 4;
        Ui.smallCaps(ms, "Editor de zone", rx, yy, Theme.TEXT);
        yy += 14;
        String[] hint = zTool == 0 ? new String[] {"Click pe o zona = o editezi.", "Alege o unealta ca sa desenezi."}
            : (zTool == 1 ? new String[] {"Trage pe harta = dreptunghi.", ""} : new String[] {"Click = adaugi colt.", "Click dreapta = sterge ultimul."});
        Ui.text(ms, hint[0], rx, yy, Theme.FAINT);
        Ui.text(ms, hint[1], rx, yy + 10, Theme.FAINT);
        yy += 24;

        int tw = (rw - 8) / 3;
        String[] names = {"Alege", "Dreptunghi", "Poligon"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            button(ms, names[i], rx + i * (tw + 4), yy, tw, 16, mx, my, true, zTool == i ? Theme.ACCENT : Theme.PANEL_3, () -> {
                zTool = t;
                zDrag = false;
            });
        }
        yy += 24;

        Ui.smallCaps(ms, "Nume", rx, yy, Theme.MUTED);
        yy += 11;
        drawField(ms, nameField, rx, yy, rw, 16, mx, my);
        yy += 24;

        Ui.smallCaps(ms, "Tip", rx, yy, Theme.MUTED);
        Ui.textRight(ms, String.valueOf(MapsState.zoneType(zType)[2]), rx + rw, yy, Theme.ACCENT);
        yy += 11;
        int hw = (rw - 4) / 2;
        for (int i = 0; i < 4; i++) {
            final int id = i + 1;
            int cx = rx + (i % 2) * (hw + 4);
            int cy = yy + (i / 2) * 20;
            boolean sel = zType == id;
            boolean h = Ui.in(mx, my, cx, cy, hw, 18);
            Ui.rect(ms, cx, cy, hw, 18, sel ? 0xFF33302A : (h ? 0xFF262A32 : Theme.PANEL_2));
            if (sel) Ui.rect(ms, cx, cy, 2, 18, Theme.ACCENT);
            Ui.round(ms, cx + 5, cy + 4, 10, 10, 0xFF000000 | MapsState.zoneRgb(id));
            Ui.smallCaps(ms, String.valueOf(MapsState.zoneType(id)[1]), cx + 19, cy + 5, Theme.TEXT);
            hit(cx, cy, hw, 18, () -> zType = id);
        }
        yy += 46;

        Ui.smallCaps(ms, "Colturi", rx, yy, Theme.MUTED);
        Ui.textRight(ms, String.valueOf(zPts.size()), rx + rw, yy, Theme.TEXT);
        yy += 13;
        button(ms, zId > 0 ? "Salveaza #" + zId : "Adauga zona", rx, yy, hw, 16, mx, my, zPts.size() >= 3, Theme.OK, this::saveZone);
        button(ms, "Zona noua", rx + hw + 4, yy, hw, 16, mx, my, true, Theme.ACCENT_DIM, this::newZone);
        yy += 20;
        button(ms, "Sterge zona", rx, yy, rw, 16, mx, my, zId > 0, 0xFF6B2F2B, () -> {
            MapsClient.deleteZone(zId);
            newZone();
        });
        yy += 26;

        // lista zonelor existente: click = o editezi si centrezi harta pe ea
        Ui.smallCaps(ms, "Zone existente", rx, yy, Theme.MUTED);
        Ui.textRight(ms, String.valueOf(MapsState.zones.size()), rx + rw, yy, Theme.FAINT);
        yy += 12;
        int rows = Math.max(1, (height - 12 - yy) / 16);
        zoneScroll = Math.max(0, Math.min(zoneScroll, Math.max(0, MapsState.zones.size() - rows)));
        if (MapsState.zones.isEmpty()) Ui.text(ms, "Nicio zona inca.", rx, yy + 2, Theme.FAINT);
        for (int i = zoneScroll; i < Math.min(MapsState.zones.size(), zoneScroll + rows); i++) {
            final MapsState.Zone z = MapsState.zones.get(i);
            int ry = yy + (i - zoneScroll) * 16;
            boolean sel = z.id == zId;
            boolean h = Ui.in(mx, my, rx, ry, rw, 15);
            if (sel || h) Ui.rect(ms, rx, ry, rw, 15, sel ? 0xFF33302A : 0x14FFFFFF);
            if (sel) Ui.rect(ms, rx, ry, 2, 15, Theme.ACCENT);
            Ui.round(ms, rx + 5, ry + 4, 7, 7, 0xFF000000 | MapsState.zoneRgb(z.type));
            Ui.text(ms, Ui.clip(z.name, rw - 70), rx + 17, ry + 4, sel || h ? Theme.TEXT : 0xFFCFCDC6);
            Ui.textRight(ms, String.valueOf(MapsState.zoneType(z.type)[1]), rx + rw - 3, ry + 4, Theme.FAINT);
            hit(rx, ry, rw, 15, () -> {
                selectZone(z);
                zTool = 0;
                double cx = 0, cz = 0;
                for (double[] pt : z.pts) {
                    cx += pt[0];
                    cz += pt[1];
                }
                if (!z.pts.isEmpty()) {
                    vx = cx / z.pts.size();
                    vz = cz / z.pts.size();
                    follow = false;
                }
            });
        }
    }

    private void fillPoly(GuiGraphics ms, List<double[]> pts, int argb) {
        int n = pts.size();
        if (n < 3) return;
        float[] px = new float[n];
        float[] py = new float[n];
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            px[i] = gx(pts.get(i)[0]);
            py[i] = gy(pts.get(i)[1]);
            minY = Math.min(minY, py[i]);
            maxY = Math.max(maxY, py[i]);
        }
        int y0 = Math.max(0, (int) minY) & ~1;
        int y1 = Math.min(height, (int) maxY + 1);
        float[] xs = new float[n];
        for (int y = y0; y < y1; y += 2) {
            float yy = y + 1;
            int c = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                if ((py[i] > yy) != (py[j] > yy)) xs[c++] = px[j] + (yy - py[j]) * (px[i] - px[j]) / (py[i] - py[j]);
            }
            Arrays.sort(xs, 0, c);
            for (int k = 0; k + 1 < c; k += 2) {
                int a = Math.max(0, Math.round(xs[k]));
                int b = Math.min(width, Math.round(xs[k + 1]));
                if (b > a) Ui.rect(ms, a, y, b - a, 2, argb);
            }
        }
    }

    private void outline(GuiGraphics ms, List<double[]> pts, boolean closed, int argb) {
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            if (!closed && i == n - 1) break;
            double[] a = pts.get(i);
            double[] b = pts.get((i + 1) % n);
            float x0 = gx(a[0]);
            float y0 = gy(a[1]);
            float x1 = gx(b[0]);
            float y1 = gy(b[1]);
            float len = (float) Math.hypot(x1 - x0, y1 - y0);
            int steps = (int) Math.min(2000, Math.max(1, Math.ceil(len / 2f)));
            for (int k = 0; k <= steps; k++) {
                float t = k / (float) steps;
                int sx = Math.round(x0 + (x1 - x0) * t);
                int sy = Math.round(y0 + (y1 - y0) * t);
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;
                Ui.rect(ms, sx - 1, sy - 1, 2, 2, argb);
            }
        }
    }

    private void drawPoiEditor(GuiGraphics ms, int mx, int my, int rx, int rw) {
        int yy = 36;
        yy += 4;
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
            Ui.rect(ms, cx, cy, cell - 2, cell - 2, sel ? Theme.ACCENT : (h ? 0xFF262A32 : Theme.PANEL_2));
            Blips.sprite(ms, Blips.icon(t), cx + 2, cy + 2, cell - 6);
            hit(cx, cy, cell - 2, cell - 2, () -> draftType = t);
        }
        yy += ((Blips.TYPES.length + cols - 1) / cols) * cell + 4;

        Ui.smallCaps(ms, "Loc", rx, yy, Theme.MUTED);
        Ui.textRight(ms, draftSet ? (int) draftX + ", " + (int) draftZ : "-", rx + rw, yy, Theme.TEXT);
        yy += 13;
        int hw = (rw - 6) / 2;
        button(ms, draftId > 0 ? "Salveaza #" + draftId : "Adauga punct", rx, yy, hw, 16, mx, my, draftSet, Theme.OK, this::save);
        button(ms, "Punct nou", rx + hw + 6, yy, hw, 16, mx, my, true, Theme.ACCENT_DIM, this::newDraftHere);
        yy += 20;
        button(ms, "Sterge punctul", rx, yy, rw, 16, mx, my, draftId > 0, 0xFF6B2F2B, () -> {
            MapsClient.deletePoi(draftId);
            draftId = 0;
            draftSet = false;
            nameField.setValue("");
        });
        yy += 24;

        Ui.smallCaps(ms, "Retea GPS", rx, yy, Theme.MUTED);
        yy += 12;
        String[][] items = {{"Cheie GPS", "gps_wrench"}, {"Segment", "gps_path"}, {"Sens unic", "gps_oneway"}, {"Dublu sens", "gps_twoway"}, {"Intersectie", "gps_intersection"}};
        for (int i = 0; i < items.length; i++) {
            final String it = items[i][1];
            int bx = rx + (i % 2) * (hw + 6);
            int by2 = yy + (i / 2) * 19;
            button(ms, items[i][0], bx, by2, hw, 16, mx, my, true, Theme.PANEL_3, () -> MapsClient.give(it));
        }
        yy += 3 * 19 + 8;
        Ui.smallCaps(ms, "Harta orasului", rx, yy, Theme.MUTED);
        yy += 12;
        int tw3 = (rw - 8) / 3;
        int[] radii = {256, 512, 1024};
        for (int i = 0; i < 3; i++) {
            final int r = radii[i];
            button(ms, "R " + r, rx + i * (tw3 + 4), yy, tw3, 16, mx, my, true, Theme.PANEL_3, () -> {
                MapsClient.scanCity(r);
                toast("Scanare ceruta (raza " + r + ").", false);
            });
        }
        Ui.text(ms, "Scaneaza zona din jurul tau.", rx, yy + 20, Theme.FAINT);
    }

    private int key(GuiGraphics ms, int x, int y, String k, String label) {
        int w = Ui.width(k) + 8;
        Ui.round(ms, x, y - 1, w, 12, 0xE0262A32);
        Ui.text(ms, k, x + 4, y + 1, Theme.TEXT);
        Ui.text(ms, label, x + w + 5, y + 1, Theme.MUTED);
        return x + w + 5 + Ui.width(label);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (showDlg) {
            int[] b = dlgBounds();
            if (!Ui.in((int) mx, (int) my, b[0], b[1], b[2], b[3])) return true;
            return super.mouseClicked(mx, my, button);
        }
        if (showPopup) {
            if (!Ui.in((int) mx, (int) my, popX, popY, popW, popH)) {
                showPopup = false;
                return true;
            }
            return super.mouseClicked(mx, my, button);
        }
        if (button == 1 && inMap(mx, my) && view != null && !overUi((int) mx, (int) my) && !(editor && editorTab == 1)) {
            popWp = wpAt(mx, my);
            popWx = wx(mx);
            popWz = wz(my);
            popX = (int) mx;
            popY = (int) my;
            showPopup = true;
            return true;
        }
        if (editor && editorTab == 1 && inMap(mx, my) && !overUi((int) mx, (int) my) && view != null) {
            if (button == 0) {
                double wxm = wx(mx);
                double wzm = wz(my);
                if (zTool == 1) {
                    zDrag = true;
                    zDragX = wxm;
                    zDragZ = wzm;
                    setRect(wxm, wzm);
                    return true;
                }
                if (zTool == 2) {
                    zPressing = true;
                    zPressX = mx;
                    zPressY = my;
                } else {
                    for (int i = MapsState.zones.size() - 1; i >= 0; i--) {
                        if (MapsState.zones.get(i).contains(wxm, wzm)) {
                            selectZone(MapsState.zones.get(i));
                            return true;
                        }
                    }
                }
            } else if (button == 1) {
                if (zTool == 2 && !zPts.isEmpty()) zPts.remove(zPts.size() - 1);
                return true;
            }
        }
        if (button == 1 && inMap(mx, my) && view != null && !overUi((int) mx, (int) my)) {
            double x = wx(mx);
            double z = wz(my);
            setGps("Waypoint", null, x, z);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void setRect(double x1, double z1) {
        zPts.clear();
        double x0 = Math.round(zDragX);
        double z0 = Math.round(zDragZ);
        x1 = Math.round(x1);
        z1 = Math.round(z1);
        zPts.add(new double[] {x0, z0});
        zPts.add(new double[] {x1, z0});
        zPts.add(new double[] {x1, z1});
        zPts.add(new double[] {x0, z1});
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            if (zDrag) {
                zDrag = false;
                if (zPts.size() == 4 && (Math.abs(zPts.get(0)[0] - zPts.get(1)[0]) < 3 || Math.abs(zPts.get(1)[1] - zPts.get(2)[1]) < 3)) zPts.clear();
                return true;
            }
            if (zPressing) {
                zPressing = false;
                if (Math.hypot(mx - zPressX, my - zPressY) < 4 && zPts.size() < 64) zPts.add(new double[] {Math.round(wx(mx)), Math.round(wz(my))});
                return true;
            }
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (zDrag && button == 0) {
            setRect(wx(mx), wz(my));
            return true;
        }
        if (zPressing && Math.hypot(mx - zPressX, my - zPressY) >= 4) zPressing = false;
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
        if (sidebar && mx < 164 && !showDlg) {
            int d = delta > 0 ? -1 : 1;
            if (my >= perY0) perScroll = Math.max(0, perScroll + d);
            else srvScroll = Math.max(0, srvScroll + d);
            return true;
        }
        if (editor && editorTab == 1 && mx >= width - 198) {
            zoneScroll = Math.max(0, zoneScroll - (int) Math.signum(delta));
            return true;
        }
        if (inMap(mx, my) && !(mx >= width - 190 && !editor)) {
            if (delta != 0) zoomAt(mx, my, delta > 0 ? -1 : 1);
        } else {
            legendStart -= (int) Math.signum(delta);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (EditBox f : fields()) if (f.isFocused()) return super.keyPressed(key, scan, mods);
        if (key == 256 && (showDlg || showPopup)) {
            showDlg = false;
            showPopup = false;
            return true;
        }
        if (key == 261 && selWp > 0 && !showDlg) {
            MapsClient.deleteWp(selWp);
            selWp = 0;
            return true;
        }
        if (MapsClient.openKey != null && MapsClient.openKey.matches(key, scan)) {
            onClose();
            return true;
        }
        if (!showDlg && !showPopup) {
            Minecraft mc = Minecraft.getInstance();
            switch (key) {
                case 66: // B: waypoint sub cursor (sau la tine)
                    if (inMap(mx0, my0) && !overUi(mx0, my0)) openDialog(null, wx(mx0), wz(my0));
                    else if (mc.player != null) openDialog(null, mc.player.getX(), mc.player.getZ());
                    return true;
                case 32: // Spatiu: centreaza
                    follow = true;
                    return true;
                case 71: // G: grila
                    MapsState.grid = !MapsState.grid;
                    MapsState.saveSettings();
                    return true;
                case 84: // T: teren real / atlas
                    MapsState.terrain = !MapsState.terrain;
                    MapsState.saveSettings();
                    return true;
                case 61: case 334: // +
                    zoomAt(-1, -1, -1);
                    return true;
                case 45: case 333: // -
                    zoomAt(-1, -1, 1);
                    return true;
                default:
                    break;
            }
        }
        return super.keyPressed(key, scan, mods);
    }
}
