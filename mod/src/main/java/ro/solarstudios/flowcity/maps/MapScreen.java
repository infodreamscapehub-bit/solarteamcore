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

        // ---- bara de sus
        Ui.rect(ms, 0, 0, width, 28, 0xFF0E1522);
        Ui.rect(ms, 0, 27, width, 1, 0xFF2A3A57);
        Ui.rect(ms, 10, 6, 4, 16, Theme.ACCENT);
        ms.push();
        ms.translate(20, 8, 0);
        ms.scale(1.5f, 1.5f, 1f);
        Ui.smallCaps(ms, "Harta", 0, 0, Theme.TEXT);
        ms.pop();
        Ui.text(ms, "FlowCity", 82, 11, Theme.MUTED);
        String pos = (int) mc.player.getPosX() + ", " + (int) mc.player.getPosY() + ", " + (int) mc.player.getPosZ();
        Ui.textRight(ms, "Tu: " + pos, width - 40, 10, Theme.MUTED);
        button(ms, "X", width - 28, 6, 18, 16, mx, my, true, 0xFF5B2A35, this::closeScreen);
        if (MapsState.admin) {
            button(ms, editor ? "IESI DIN EDITOR" : "EDITOR GPS", width - 138, 6, 104, 16, mx, my, true, editor ? Theme.OK : 0xFF8A5A00, () -> {
                editor = !editor;
                if (editor) newDraftHere();
            });
        }

        // ---- zona hartii
        int rightW = Math.min(180, Math.max(140, width / 4));
        int f = f();
        int rawW = width - 20 - rightW - 10;
        int rawH = height - 28 - 8 - 26;
        aw = rawW / f * f;
        ah = rawH / f * f;
        ax = 10;
        ay = 36;
        if (view == null) view = new MapView(384, "flowcity_bigmap", false);
        if (follow) {
            vx = mc.player.getPosX();
            vz = mc.player.getPosZ();
        }
        if (view.needsRefresh(vx, vz, scale(), aw / f, ah / f, 6000)) view.refresh(MathHelper.floor(vx), MathHelper.floor(vz), scale());

        Ui.rect(ms, ax - 2, ay - 2, aw + 4, ah + 4, 0xFF2A3A57);
        view.draw(ms, ax, ay, aw, ah, vx, vz, f);
        hit(ax, ay, aw, ah, () -> {
            if (editor) {
                draftX = wx(mx0);
                draftZ = wz(my0);
                draftSet = true;
            }
        });
        Ui.scissor(ax, ay, aw, ah);

        // ruta
        
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
            boolean h = Ui.in(mx, my, sx - 9, sy - 9, 18, 18) && inMap(mx, my);
            Blips.draw(ms, p.type, sx, sy, h ? 18 : 14, h);
            if (h) hover = p;
            final MapsState.Poi pp = p;
            hit(sx - 9, sy - 9, 18, 18, () -> {
                if (editor) {
                    selectPoi(pp);
                    return;
                }
                MapsClient.gpsTo(pp.name, pp.type, pp.x, pp.z);
                toast("Waypoint: " + pp.name, false);
            });
        }
        // waypoint
        if (MapsState.hasTarget) {
            int sx = Math.round(gx(MapsState.tx));
            int sy = Math.round(gy(MapsState.tz));
            float bob = (float) Math.sin(now / 250.0) * 1.5f;
            if (MapsState.targetType != null) Blips.draw(ms, MapsState.targetType, sx, sy - 22 + Math.round(bob), 14, true);
            Blips.pin(ms, sx, sy + Math.round(bob), 12, MapsClient.ROUTE_COLOR);
            double d = Math.hypot(MapsState.tx - mc.player.getPosX(), MapsState.tz - mc.player.getPosZ());
            String lab = MapsState.targetName + "  " + MapsClient.meters(d);
            int lw = Ui.width(lab) + 10;
            Ui.rect(ms, sx - lw / 2, sy + 4, lw, 12, 0xDD0A0F18);
            Ui.textCenter(ms, lab, sx, sy + 6, Theme.TEXT);
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
        float ppx = gx(mc.player.getPosX());
        float ppz = gy(mc.player.getPosZ());
        MapsClient.drawPlayer(ms, ppx, ppz, mc.player.rotationYaw);
        Ui.endScissor();

        // tooltip blip
        if (hover != null) {
            String t = hover.name + "  -  " + Blips.label(hover.type);
            int w = Ui.width(t) + 12;
            int tx = Math.min(ax + aw - w, Math.max(ax, mx + 10));
            int ty = Math.max(ay, my - 22);
            Ui.rect(ms, tx, ty, w, 14, 0xF00A0F18);
            Ui.rect(ms, tx, ty, 2, 14, Theme.ACCENT);
            Ui.text(ms, t, tx + 6, ty + 3, Theme.TEXT);
        }
        // coordonate sub cursor
        if (inMap(mx, my)) Ui.text(ms, (int) wx(mx) + ", " + (int) wz(my), ax + 6, ay + ah - 12, 0xFF1A2233);

        // ---- panou dreapta
        int rx = ax + aw + 14;
        int rw = width - rx - 10;
        if (editor) {
            drawEditor(ms, mx, my, rx, rw);
        } else {
            int yy = 36;
            Ui.rect(ms, rx, yy, rw, 62, 0xFF0E1522);
            Ui.rect(ms, rx, yy, rw, 2, MapsState.hasTarget ? MapsClient.ROUTE_COLOR : Theme.LINE);
            Ui.smallCaps(ms, "Waypoint", rx + 8, yy + 8, Theme.MUTED);
            if (MapsState.hasTarget) {
                double d = Math.hypot(MapsState.tx - mc.player.getPosX(), MapsState.tz - mc.player.getPosZ());
                Ui.text(ms, Ui.clip(MapsState.targetName, rw - 16), rx + 8, yy + 20, Theme.TEXT);
                Ui.text(ms, MapsClient.meters(d) + (MapsState.routeNetwork ? "  pe strazi" : "  linie dreapta"), rx + 8, yy + 31, MapsClient.ROUTE_COLOR);
                button(ms, "STERGE WAYPOINT", rx + 8, yy + 42, rw - 16, 14, mx, my, true, Theme.BAD, () -> {
                    MapsState.clearTarget();
                    toast("Waypoint sters", false);
                });
            } else {
                Ui.text(ms, "Niciunul setat.", rx + 8, yy + 20, Theme.FAINT);
                Ui.text(ms, "Click dreapta pe harta.", rx + 8, yy + 31, Theme.FAINT);
            }
            yy += 68;

            int hw = (rw - 6) / 2;
            button(ms, "ZOOM +", rx, yy, hw, 15, mx, my, level > 0, Theme.ACCENT_DIM, () -> level = Math.max(0, level - 1));
            button(ms, "ZOOM -", rx + hw + 6, yy, hw, 15, mx, my, level < LEVELS.length - 1, Theme.ACCENT_DIM, () -> level = Math.min(LEVELS.length - 1, level + 1));
            yy += 19;
            button(ms, "CENTREAZA", rx, yy, hw, 15, mx, my, !follow, Theme.ACCENT_DIM, () -> follow = true);
            button(ms, MapsState.minimap ? "RADAR: ON" : "RADAR: OFF", rx + hw + 6, yy, hw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.minimap = !MapsState.minimap);
            yy += 19;
            button(ms, MapsState.radarPos == 0 ? "RADAR: JOS" : "RADAR: SUS", rx, yy, hw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.radarPos = 1 - MapsState.radarPos);
            button(ms, "RADAR ZOOM", rx + hw + 6, yy, hw, 15, mx, my, true, Theme.PANEL_3, () -> MapsState.miniScale = MapsState.miniScale == 1 ? 2 : (MapsState.miniScale == 2 ? 4 : 1));
            yy += 23;

            // ---- legenda / filtre
            Ui.smallCaps(ms, "Legenda", rx, yy, Theme.MUTED);
            int bw = (rw - 40) / 2;
            button(ms, "TOATE", rx + rw - 2 * bw - 4, yy - 3, bw, 13, mx, my, true, Theme.PANEL_3, () -> MapsState.hidden.clear());
            button(ms, "NIMIC", rx + rw - bw, yy - 3, bw, 13, mx, my, true, Theme.PANEL_3, () -> {
                for (MapsState.Poi p : MapsState.pois) MapsState.hidden.add(p.type);
            });
            yy += 14;
            Map<String, Integer> counts = new HashMap<>();
            for (MapsState.Poi p : MapsState.pois) counts.merge(p.type, 1, Integer::sum);
            List<String> types = new ArrayList<>(counts.keySet());
            types.sort(Comparator.comparing(Blips::label));
            int listH = height - 30 - yy;
            int rowH = 18;
            int max = Math.max(0, types.size() * rowH - listH);
            listScroll = Math.max(0, Math.min(listScroll, max));
            if (types.isEmpty()) Ui.text(ms, "Niciun punct. Adminii: /harta poi add", rx, yy + 4, Theme.FAINT);
            Ui.scissor(rx, yy, rw, listH);
            int ry = yy - listScroll;
            for (String t : types) {
                if (ry + rowH > yy && ry < yy + listH) {
                    boolean on = !MapsState.hidden.contains(t);
                    boolean h = Ui.in(mx, my, rx, ry, rw, rowH - 2) && my >= yy && my < yy + listH;
                    Ui.rect(ms, rx, ry, rw, rowH - 2, h ? 0xFF1B2740 : 0xFF111A2B);
                    Blips.sprite(ms, Blips.icon(t), rx + 3, ry + 1, 14);
                    Ui.text(ms, Blips.label(t), rx + 22, ry + 4, on ? Theme.TEXT : Theme.FAINT);
                    Ui.textRight(ms, String.valueOf(counts.get(t)), rx + rw - 22, ry + 4, Theme.MUTED);
                    Ui.frame(ms, rx + rw - 16, ry + 3, 10, 10, 0xFF0A0F18, on ? Theme.OK : Theme.LINE);
                    if (on) Ui.rect(ms, rx + rw - 14, ry + 5, 6, 6, Theme.OK);
                    if (my >= yy && my < yy + listH) {
                        final String tt = t;
                        hit(rx, ry, rw, rowH - 2, () -> {
                            if (!MapsState.hidden.remove(tt)) MapsState.hidden.add(tt);
                        });
                    }
                }
                ry += rowH;
            }
            Ui.endScissor();


        }

        // ---- bara de jos (indicii taste)
        int by = height - 24;
        Ui.rect(ms, 0, by, width, 24, 0xFF0E1522);
        Ui.rect(ms, 0, by, width, 1, 0xFF2A3A57);
        int kx = 12;
        kx = key(ms, kx, by + 6, "CLICK", "Waypoint la icon") + 14;
        kx = key(ms, kx, by + 6, "CLICK DREAPTA", "Waypoint oriunde") + 14;
        kx = key(ms, kx, by + 6, "TRAGE", "Muta harta") + 14;
        kx = key(ms, kx, by + 6, "ROTITA", "Zoom") + 14;
        key(ms, kx, by + 6, "M / ESC", "Inchide");
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
        if (button == 1 && inMap(mx, my) && view != null) {
            double x = wx(mx);
            double z = wz(my);
            MapsClient.gpsTo("Waypoint", null, x, z);
            toast("Waypoint setat", false);
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
        if (inMap(mx, my)) {
            if (delta > 0) level = Math.max(0, level - 1);
            else if (delta < 0) level = Math.min(LEVELS.length - 1, level + 1);
        } else {
            listScroll -= (int) (delta * 20);
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
