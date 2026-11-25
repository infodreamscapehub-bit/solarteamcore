package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Harta GPS pe tot ecranul: harta lumii desenata din chunk-uri (GpsMap), marcaje, zone RP, traseu direct, panou LOCATII.
 * Navigarea e DIRECTA (nu ruta pe strazi). Coordonate ecran = coordonate GUI; nordul (-Z) e in sus.
 */
public final class GpsScreen extends FlowScreen {
    static final float M = 14f;
    private static final int VIOLET = 0xFF8B7BFF;
    private static final float PITCH = 46f;
    private static final float ROW_H = 38f;
    private static final String PIN = "_pin";

    // camera
    private double camX;
    private double camZ;
    private double tgtX;
    private double tgtZ;
    private boolean anim;
    private float zoom;
    private long lastNs;
    private final long born = System.currentTimeMillis();

    // stare
    private final TextFieldWidget search = field("Caută destinație…", 40, s -> {
        dirty = true;
        scroll = 0;
    });
    private boolean dirty = true;
    private final List<GpsData.Loc> shown = new ArrayList<>();
    /** Randurile listei: locatiile cu acelasi nume se grupeaza intr-un rand cu sageti < i/n >. */
    private final List<List<GpsData.Loc>> rows = new ArrayList<>();
    private final java.util.Map<String, Integer> variant = new java.util.HashMap<>();
    private List<GpsData.Loc> all = new ArrayList<>();
    private GpsData.Loc selected;
    private int totalInDim;
    private String filter = "TOATE";
    private int scroll;
    private boolean panelOpen = false;
    private float panelAnim = 1f;
    private boolean menuOpen;
    private final GpsSidebar side = new GpsSidebar(this);
    private final GpsLeftBar bar = new GpsLeftBar(this);
    private boolean limit;
    private float leftW;
    private boolean layersOpen;
    private boolean showZones;
    private long lastRefresh;
    private String note = "";
    private long noteUntil;
    private String lastClickId = "";
    private long lastClickAt;

    // mouse
    private boolean pressMap;
    private boolean moved;
    private double pressX;
    private double pressY;

    // layout calculat la fiecare cadru
    private float panelW;
    private float panelX;
    private float shift;
    private float mapCx;
    private float mapCy;
    private float areaRight;
    private float listTop;
    private float listBottom;
    private int rowsVisible = 1;

    public GpsScreen() {
        super("GPS", 520, 290);
        GpsPrefs.load();
        Minecraft mc = Minecraft.getInstance();
        menuOpen = false;
        showZones = GpsPrefs.showZones != null ? GpsPrefs.showZones : GpsData.showZonesDefault;
        zoom = GpsPrefs.zoom > 0f ? GpsPrefs.zoom : (float) GpsData.defaultZoom;
        if (mc.player != null) {
            camX = mc.player.getX();
            camZ = mc.player.getZ();
        }
        if (!GpsData.loaded) GpsClient.requestConfig();
        if (GpsNav.active) {
            for (GpsData.Loc l : GpsData.all()) if (GpsNav.isTarget(l.id)) selected = l;
        }
    }

    public void dataChanged() {
        dirty = true;
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>(1);
        if (panelOpen) l.add(search);
        return l;
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        if (selected == null && !shown.isEmpty()) select(shown.get(0), true);
        activate();
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - lastRefresh > 2000) {
            lastRefresh = System.currentTimeMillis();
            dirty = true;
            if (!GpsData.loaded) GpsClient.requestConfig();
        }
    }

    @Override
    public void removed() {
        GpsPrefs.zoom = zoom;
        GpsPrefs.showZones = showZones;
        GpsPrefs.save();
        GpsMap.flush();
        super.removed();
    }


    // ------------------------------------------------------------------ legatura cu meniul lateral

    int screenW() {
        return width;
    }

    float leftW() {
        return leftW;
    }

    float zoomValue() {
        return zoom;
    }

    void zoomStep(float factor) {
        zoomBy(factor, mapCx, mapCy);
    }

    boolean limitOn() {
        return limit;
    }

    void toggleLimit() {
        limit = !limit;
    }

    void markDirty() {
        dirty = true;
    }

    void cancelNav() {
        cancel();
    }

    void showAt(double x, double z) {
        tgtX = x;
        tgtZ = z;
        anim = true;
    }

    void selectLoc(GpsData.Loc l) {
        select(l, true);
    }

    void activateSel() {
        if (selected != null && !GpsNav.isTarget(selected.id)) activate();
    }

    /** Punctul pus de jucator cu click-dreapta (daca exista). */
    GpsData.Loc pinLoc() {
        return selected != null && PIN.equals(selected.id) ? selected : null;
    }

    /** Deschide panoul de locatii filtrat pe grupul dat (TOATE / FAV / un grup). */
    void openList(String f) {
        filter = f;
        scroll = 0;
        panelOpen = true;
        dirty = true;
    }

    boolean zonesOn() {
        return showZones;
    }

    void toggleZones() {
        showZones = !showZones;
        GpsPrefs.showZones = showZones;
        GpsPrefs.save();
    }

    boolean panelIsOpen() {
        return panelOpen;
    }

    void togglePanel() {
        panelOpen = !panelOpen;
    }

    void afterReset() {
        showZones = GpsData.showZonesDefault;
        zoom = (float) GpsData.defaultZoom;
        dirty = true;
    }

    private float sideBottom() {
        float b = height - M;
        if (GpsNav.active) b -= 82f;
        if (showZones && !GpsData.zones.isEmpty()) b -= 34f;
        return b;
    }

    // ------------------------------------------------------------------ date / filtre

    private List<String> chips() {
        Set<String> s = new LinkedHashSet<>();
        s.add("TOATE");
        if (!GpsData.groups.isEmpty()) s.addAll(GpsData.groups);
        else for (GpsData.Cat c : GpsData.cats) if (!c.group.isEmpty()) s.add(c.group);
        s.add("FAV");
        return new ArrayList<>(s);
    }

    private void rebuild() {
        dirty = false;
        Minecraft mc = Minecraft.getInstance();
        String dim = GpsData.dimOf(mc);
        all = GpsData.all();
        String q = GpsData.norm(search.getValue().trim());
        String[] toks = q.isEmpty() ? new String[0] : q.split("\\s+");
        shown.clear();
        totalInDim = 0;
        for (GpsData.Loc l : all) {
            if (!l.dim.equals(dim)) continue;
            if (!GpsPrefs.layerOn("locations") || !GpsPrefs.layerOn(GpsData.locLayer(l))) continue;
            if (MiniMapSettings.hiddenCats.contains(l.cat)) continue;
            totalInDim++;
            if ("FAV".equals(filter)) {
                if (!GpsPrefs.fav.contains(l.id)) continue;
            } else if (!"TOATE".equals(filter) && !filter.equals(l.group)) {
                continue;
            }
            boolean ok = true;
            for (String t : toks) {
                if (!l.key.contains(t)) {
                    ok = false;
                    break;
                }
            }
            if (ok) shown.add(l);
        }
        Collections.sort(shown, (a, b) -> {
            boolean fa = GpsPrefs.fav.contains(a.id);
            boolean fb = GpsPrefs.fav.contains(b.id);
            if (fa != fb) return fa ? -1 : 1;
            return GpsData.norm(a.name).compareTo(GpsData.norm(b.name));
        });
        if (selected != null && !PIN.equals(selected.id)) {
            GpsData.Loc fresh = null;
            for (GpsData.Loc l : all) if (l.id.equals(selected.id)) fresh = l;
            selected = fresh;
        }
        rows.clear();
        java.util.LinkedHashMap<String, List<GpsData.Loc>> gm = new java.util.LinkedHashMap<>();
        for (GpsData.Loc l : shown) {
            List<GpsData.Loc> gl = gm.get(GpsData.norm(l.name));
            if (gl == null) {
                gl = new ArrayList<>(2);
                gm.put(GpsData.norm(l.name), gl);
            }
            gl.add(l);
        }
        rows.addAll(gm.values());
        if (selected != null) syncVariant(selected);
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - rowsVisible)));
    }

    private static String key(List<GpsData.Loc> grp) {
        return GpsData.norm(grp.get(0).name);
    }

    private GpsData.Loc cur(List<GpsData.Loc> grp) {
        Integer i = variant.get(key(grp));
        int k = i == null ? 0 : i;
        if (k < 0 || k >= grp.size()) k = 0;
        return grp.get(k);
    }

    private void syncVariant(GpsData.Loc l) {
        for (List<GpsData.Loc> grp : rows) {
            for (int j = 0; j < grp.size(); j++) {
                if (grp.get(j).id.equals(l.id)) {
                    variant.put(key(grp), j);
                    return;
                }
            }
        }
    }

    private boolean hasSel(List<GpsData.Loc> grp) {
        if (selected == null) return false;
        for (GpsData.Loc l : grp) if (l.id.equals(selected.id)) return true;
        return false;
    }

    private void cycle(List<GpsData.Loc> grp, int d) {
        Integer cur = variant.get(key(grp));
        int i = cur == null ? 0 : cur;
        i = ((i + d) % grp.size() + grp.size()) % grp.size();
        variant.put(key(grp), i);
        select(grp.get(i), true);
    }

    private double dist(GpsData.Loc l) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !l.dim.equals(GpsData.dimOf(mc))) return -1;
        double dx = l.x - mc.player.getX();
        double dz = l.z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ actiuni

    void say(String s) {
        note = s;
        noteUntil = System.currentTimeMillis() + 2600;
    }

    private void select(GpsData.Loc l, boolean focus) {
        selected = l;
        if (l != null && focus) {
            tgtX = l.x;
            tgtZ = l.z;
            anim = true;
        }
        if (l != null) {
            syncVariant(l);
            revealSelected();
        }
    }

    private void revealSelected() {
        int i = -1;
        for (int k = 0; k < rows.size(); k++) {
            if (hasSel(rows.get(k))) {
                i = k;
                break;
            }
        }
        if (i < 0) return;
        if (i < scroll) scroll = i;
        else if (i >= scroll + rowsVisible) scroll = i - rowsVisible + 1;
        clampScroll();
    }

    private void activate() {
        if (selected == null) return;
        if (GpsNav.isTarget(selected.id)) {
            GpsNav.clear();
            say("GPS anulat");
            return;
        }
        GpsNav.set(selected.id, selected.name, selected.x, selected.z, selected.dim, selected.color);
        say("GPS setat: " + selected.name);
    }

    private void cancel() {
        GpsNav.clear();
        say("GPS anulat");
    }

    private void toggleFav(GpsData.Loc l) {
        if (l == null || PIN.equals(l.id)) return;
        if (!GpsPrefs.fav.remove(l.id)) GpsPrefs.fav.add(l.id);
        dirty = true;
    }

    void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        tgtX = mc.player.getX();
        tgtZ = mc.player.getZ();
        anim = true;
    }

    void fitAll() {
        int[] b = GpsMap.explored();
        if (b == null) {
            say("Nu ai explorat încă nimic");
            return;
        }
        float w = b[2] - b[0];
        float h = b[3] - b[1];
        float aw = Math.max(100f, areaRight - 40f);
        float ah = Math.max(100f, height - 100f);
        zoom = Math.max(0.15f, Math.min(6f, Math.min(aw / Math.max(64f, w), ah / Math.max(64f, h))));
        tgtX = (b[0] + b[2]) / 2.0;
        tgtZ = (b[1] + b[3]) / 2.0;
        anim = true;
    }

    private void zoomBy(float factor, float sx, float sy) {
        double wx = camX + (sx - mapCx) / zoom;
        double wz = camZ + (sy - mapCy) / zoom;
        zoom = Math.max(0.15f, Math.min(6f, zoom * factor));
        camX = wx - (sx - mapCx) / zoom;
        camZ = wz - (sy - mapCy) / zoom;
        anim = false;
    }

    private void setPin(double mx, double my) {
        GpsData.Loc p = new GpsData.Loc();
        p.id = PIN;
        p.name = "Punct personalizat";
        p.catName = "Marcaj";
        p.icon = "pin";
        p.color = 0xFFFFC857;
        p.desc = "Setat de tine pe hartă";
        p.dim = GpsData.dimOf(Minecraft.getInstance());
        p.x = Math.round(camX + (mx - mapCx) / zoom);
        p.z = Math.round(camZ + (my - mapCy) / zoom);
        selected = p;
    }

    // ------------------------------------------------------------------ input

    private boolean overPanel(double mx, double my) {
        return (panelAnim > 0.05f && mx >= panelX + shift && my >= M && my <= height - M) || side.over(mx, my) || bar.over(mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (super.mouseClicked(mx, my, button)) return true;
            if (overPanel(mx, my)) return true;
            if (GpsNav.active && mx < M + leftW + cardW() && my > height - M - 74) return true;
            layersOpen = false;
            pressMap = true;
            moved = false;
            pressX = mx;
            pressY = my;
            return true;
        }
        if (button == 1 && !overPanel(mx, my)) {
            setPin(mx, my);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pressMap && button == 0) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 4) moved = true;
            if (moved) {
                camX -= dx / zoom;
                camZ -= dy / zoom;
                anim = false;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (pressMap && button == 0) {
            pressMap = false;
            if (!moved) mapClick((float) mx, (float) my);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private void mapClick(float mx, float my) {
        GpsData.Loc best = null;
        float bd = 14f * 14f;
        for (GpsData.Loc l : shown) {
            float dx = sx(l.x) - mx;
            float dy = sy(l.z) - my;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        if (best != null) select(best, false);
        else selected = null;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (bar.over(mx, my) && !side.over(mx, my)) {
            bar.scrollBy(-(int) Math.signum(delta));
        } else if (side.over(mx, my)) {
            side.scrollBy(-(int) Math.signum(delta));
        } else if (overPanel(mx, my)) {
            scroll -= (int) Math.signum(delta) * 2;
            clampScroll();
        } else {
            zoomBy(delta > 0 ? 1.2f : 1f / 1.2f, (float) mx, (float) my);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused()) {
            if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
                step(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        if (GpsClient.key != null && GpsClient.key.matches(key, scan)) {
            onClose();
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_DOWN:
                step(1);
                return true;
            case GLFW.GLFW_KEY_UP:
                step(-1);
                return true;
            case GLFW.GLFW_KEY_PAGE_DOWN:
                scroll += rowsVisible;
                clampScroll();
                return true;
            case GLFW.GLFW_KEY_PAGE_UP:
                scroll -= rowsVisible;
                clampScroll();
                return true;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                activate();
                return true;
            case GLFW.GLFW_KEY_SLASH:
                panelOpen = true;
                focus(search, true);
                return true;
            case GLFW.GLFW_KEY_Z:
                showZones = !showZones;
                say(showZones ? "Zone RP: afișate" : "Zone RP: ascunse");
                return true;
            case GLFW.GLFW_KEY_C:
                centerOnPlayer();
                return true;
            case GLFW.GLFW_KEY_F:
                toggleFav(selected);
                return true;
            case GLFW.GLFW_KEY_TAB:
                panelOpen = !panelOpen;
                return true;
            case GLFW.GLFW_KEY_EQUAL:
            case GLFW.GLFW_KEY_KP_ADD:
                zoomBy(1.3f, mapCx, mapCy);
                return true;
            case GLFW.GLFW_KEY_MINUS:
            case GLFW.GLFW_KEY_KP_SUBTRACT:
                zoomBy(1f / 1.3f, mapCx, mapCy);
                return true;
            default:
                return super.keyPressed(key, scan, mods);
        }
    }

    private void step(int d) {
        if (shown.isEmpty()) return;
        int i = shown.indexOf(selected);
        i = i < 0 ? (d > 0 ? 0 : shown.size() - 1) : Math.max(0, Math.min(shown.size() - 1, i + d));
        select(shown.get(i), true);
    }

    // ------------------------------------------------------------------ transformari

    private float sx(double wx) {
        return (float) (mapCx + (wx - camX) * zoom);
    }

    private float sy(double wz) {
        return (float) (mapCy + (wz - camZ) * zoom);
    }

    void hitF(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(x), Math.round(y), Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), r);
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private float cardW() {
        return Math.min(330f, Math.max(220f, areaRight - 90f));
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float open = ease((System.currentTimeMillis() - born) / 380f);
        float t = (System.currentTimeMillis() - born) / 1000f;

        panelAnim += ((panelOpen ? 1f : 0f) - panelAnim) * Math.min(1f, dt * 10f);
        panelW = Math.max(236f, Math.min(292f, width * 0.26f));
        panelX = width - panelW - M;
        shift = (1f - Math.min(panelAnim, open)) * (panelW + M + 12f);
        areaRight = width - (panelW + M) * panelAnim;
        leftW = bar.width();
        mapCx = leftW + (areaRight - leftW) / 2f;
        mapCy = height / 2f;
        if (anim) {
            camX += (tgtX - camX) * Math.min(1f, dt * 7f);
            camZ += (tgtZ - camZ) * Math.min(1f, dt * 7f);
            if (Math.abs(tgtX - camX) < 0.3 && Math.abs(tgtZ - camZ) < 0.3) anim = false;
        }
        if (limit) {
            int[] eb = GpsMap.explored();
            if (eb != null) {
                camX = Math.max(eb[0] - 128.0, Math.min(eb[2] + 128.0, camX));
                camZ = Math.max(eb[1] - 128.0, Math.min(eb[3] + 128.0, camZ));
            }
        }
        if (dirty) rebuild();

        // fundal + harta
        Gfx.rrect(g, 0, 0, width, height, 0, 0xFF0C1C33, 0xFF08111F);
        drawGrid(g);
        GpsMap.draw(g, camX, camZ, zoom, mapCx, mapCy, width, height, open);
        g.fillGradient(0, 0, width, 46, 0x66070F1C, 0x00070F1C);
        if (showZones) drawZones(g);
        if (GpsPrefs.layerOn("roads") && RoadNet.hasRoads()) {
            RoadDraw.network(g, RoadNet.live, camX, camZ, zoom, mapCx, mapCy, width, height, 0x887F93AD, Math.max(1.2f, Math.min(3f, zoom * 1.4f)), zoom >= 0.9f, null);
            RoadDraw.names(g, RoadNet.live, camX, camZ, zoom, mapCx, mapCy, width, height);
        }
        drawRoute(g, t);
        GpsData.Loc hover = drawMarkers(g, mc, mx, my, t);
        drawPlayer(g, mc, t);

        // interfata
        bar.draw(g, mx, my, dt, height);
        drawTop(g, mc, mx, my);
        drawCard(g, mc, mx, my);
        drawControls(g, mx, my);
        side.draw(g, mx, my, dt, menuOpen, M + 48f, sideBottom());
        drawLayers(g, mx, my);
        drawPanel(g, mc, mx, my, t);
        if (hover != null && !overPanel(mx, my)) tooltip(g, hover, mx, my);
        if (System.currentTimeMillis() < noteUntil) {
            float w = Gfx.width(note, 8.6f, true) + 28f;
            float x = mapCx - w / 2f;
            box(g, x, 56, w, 24, 12f, 0xF00A1220, Sk.ACC);
            Gfx.text(g, note, mapCx, 72, 8.6f, Sk.WHITE, 1, true, 0f);
        }
    }

    private void drawGrid(GuiGraphics g) {
        if (zoom < 2.2f && !GpsPrefs.grid) return;
        double base = 16.0;
        while (base * zoom < 40.0) base *= 2.0;
        float step = (float) (base * zoom);
        float x0 = (float) (mapCx + (Math.floor((camX - mapCx / zoom) / base) * base - camX) * zoom);
        float y0 = (float) (mapCy + (Math.floor((camZ - mapCy / zoom) / base) * base - camZ) * zoom);
        int col = GpsPrefs.grid ? 0x22FFFFFF : 0x0CFFFFFF;
        for (float x = x0; x < width; x += step) g.fill(Math.round(x), 0, Math.round(x) + 1, height, col);
        for (float y = y0; y < height; y += step) g.fill(0, Math.round(y), width, Math.round(y) + 1, col);
    }

    private void drawZones(GuiGraphics g) {
        String dim = GpsData.dimOf(Minecraft.getInstance());
        for (GpsData.Zone z : GpsData.zones) {
            if (!z.dim.equals(dim) || !z.enabled || !GpsPrefs.layerOn(GpsData.zoneLayer(z))) continue;
            float x0 = sx(z.minX);
            float x1 = sx(z.maxX);
            float y0 = sy(z.minZ);
            float y1 = sy(z.maxZ);
            if (x1 < 0 || y1 < 0 || x0 > width || y0 > height) continue;
            int fill = (z.color & 0xFFFFFF) | (Math.round(z.alpha * 2.55f) << 24);
            int edge = (z.outline & 0xFFFFFF) | 0xE0000000;
            float[] tr = z.tris;
            for (int i = 0; i + 5 < tr.length; i += 6) {
                Gfx.poly(g, new float[] {sx(tr[i]), sy(tr[i + 1]), sx(tr[i + 2]), sy(tr[i + 3]), sx(tr[i + 4]), sy(tr[i + 5])}, fill);
            }
            int n = z.px.length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                Gfx.line(g, sx(z.px[i]), sy(z.pz[i]), sx(z.px[j]), sy(z.pz[j]), 1.6f, edge);
            }
            if (zoom >= 0.45f && x1 - x0 > 50) {
                Gfx.textS(g, clip(z.name, 7.4f, true, x1 - x0 - 8), (x0 + x1) / 2f, (y0 + y1) / 2f + 3f, 7.4f, (z.color & 0xFFFFFF) | 0xFF000000, 1, true, 0.6f);
            }
        }
    }

    /** Taie segmentul la dreptunghiul ecranului (Liang-Barsky); intoarce {t0, t1} sau null. */
    private float[] clipSeg(float x0, float y0, float x1, float y1) {
        float t0 = 0f;
        float t1 = 1f;
        float dx = x1 - x0;
        float dy = y1 - y0;
        float[] p = {-dx, dx, -dy, dy};
        float[] q = {x0 + 60f, width + 60f - x0, y0 + 60f, height + 60f - y0};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(p[i]) < 1e-6f) {
                if (q[i] < 0) return null;
            } else {
                float r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return null;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return null;
                    if (r < t1) t1 = r;
                }
            }
        }
        return new float[] {t0, t1};
    }

    private void drawRoute(GuiGraphics g, float t) {
        Minecraft mc = Minecraft.getInstance();
        if (!GpsNav.active || mc.player == null || !GpsNav.dim.equals(GpsData.dimOf(mc))) return;
        List<double[]> path = GpsNav.route.path(mc.player.getX(), mc.player.getZ(), GpsNav.x, GpsNav.z);
        for (int i = 0; i + 1 < path.size(); i++) {
            float ax = sx(path.get(i)[0]);
            float ay = sy(path.get(i)[1]);
            float bx = sx(path.get(i + 1)[0]);
            float by = sy(path.get(i + 1)[1]);
            float[] c = clipSeg(ax, ay, bx, by);
            if (c == null) continue;
            float x0 = ax + (bx - ax) * c[0];
            float y0 = ay + (by - ay) * c[0];
            float x1 = ax + (bx - ax) * c[1];
            float y1 = ay + (by - ay) * c[1];
            Gfx.line(g, x0, y0, x1, y1, 6f, 0x484DA3FF);
            float len = (float) Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0));
            if (len < 1f) continue;
            float ux = (x1 - x0) / len;
            float uy = (y1 - y0) / len;
            float off = (t * 34f) % 16f;
            int n = 0;
            for (float s = -off; s < len && n < 500; s += 16f, n++) {
                float d0 = Math.max(0f, s);
                float d1 = Math.min(len, s + 9f);
                if (d1 <= d0) continue;
                Gfx.line(g, x0 + ux * d0, y0 + uy * d0, x0 + ux * d1, y0 + uy * d1, 3f, 0xFFBFE0FF);
            }
        }
        if (GpsNav.route.roads()) {
            for (RoadRouteProvider.Step st : RoadRouteProvider.I.steps()) {
                if (st.turn == 9) continue;
                float x = sx(st.x);
                float y = sy(st.z);
                if (x < -10 || y < -10 || x > width + 10 || y > height + 10) continue;
                Gfx.disc(g, x, y, 4.4f, 0xFF0A1220);
                Gfx.disc(g, x, y, 3f, 0xFFBFE0FF);
            }
        }
    }

    private GpsData.Loc drawMarkers(GuiGraphics g, Minecraft mc, int mx, int my, float t) {
        float u0 = Math.max(4.8f, Math.min(6.8f, 3.9f + zoom * 1.3f));
        float r = u0 * 1.35f;
        GpsData.Loc hover = null;
        float bd = (r + 3f) * (r + 3f);
        boolean mouseMap = !overPanel(mx, my);
        for (GpsData.Loc l : shown) {
            if (l.hidden) continue;
            float x = sx(l.x);
            float y = sy(l.z);
            if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;
            if (mouseMap) {
                float d = (x - mx) * (x - mx) + (y - my) * (y - my);
                if (d < bd) {
                    bd = d;
                    hover = l;
                }
            }
        }
        GpsData.Loc sel = selected;
        for (GpsData.Loc l : shown) {
            if (l == sel || l.hidden) continue;
            float x = sx(l.x);
            float y = sy(l.z);
            if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;
            boolean hv = l == hover;
            float u = u0 * l.size / 100f * (hv ? 1.18f : 1f);
            GpsIcons.pin(g, l.icon, x, y, u, l.color, 1f, GpsNav.isTarget(l.id));
            if (zoom >= 1.5f || hv) Gfx.textS(g, clip(l.name, 7.2f, true, 110f), x, y + u * 1.4f + 8f, 7.2f, Sk.WHITE, 1, true, 0.3f);
        }
        // waypoint-urile jucatorului (+ cele partajate de admini)
        if (GpsPrefs.layerOn("waypoints")) {
            String wdim = GpsData.dimOf(mc);
            for (GpsWps.Wp w : GpsWps.all()) {
                if (!w.dim.equals(wdim) || GpsNav.isTarget("wp:" + w.id)) continue;
                float x = sx(w.x);
                float y = sy(w.z);
                if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;
                GpsIcons.pin(g, "pin", x, y, u0 * 0.95f, w.color, 1f, false);
                if (zoom >= 1.2f) Gfx.textS(g, clip(w.name, 7f, true, 110f), x, y + u0 * 1.3f + 8f, 7f, Sk.WHITE, 1, true, 0.3f);
            }
        }
        if (sel != null && (!PIN.equals(sel.id) || GpsPrefs.layerOn("waypoints"))) {
            float x = sx(sel.x);
            float y = sy(sel.z);
            float ph = (t % 1.6f) / 1.6f;
            Gfx.ring(g, x, y, r + 3f + ph * 12f, r + 4f + ph * 12f, Gfx.fade(sel.color, 0.8f * (1f - ph)), Gfx.fade(sel.color, 0.8f * (1f - ph)));
            GpsIcons.pin(g, sel.icon, x, y, u0 * 1.25f, sel.color, 1f, true);
            Gfx.textS(g, clip(sel.name, 8f, true, 140f), x, y + u0 * 1.7f + 9f, 8f, Sk.WHITE, 1, true, 0.3f);
        }
        // destinatia activa daca nu e in lista curenta (ex. setata de server)
        if (GpsNav.active && GpsPrefs.layerOn("waypoints") && GpsNav.dim.equals(GpsData.dimOf(mc)) && !isShown(GpsNav.id)) {
            float x = sx(GpsNav.x);
            float y = sy(GpsNav.z);
            GpsIcons.pin(g, "pin", x, y, u0 * 1.1f, GpsNav.color, 1f, true);
            Gfx.textS(g, clip(GpsNav.name, 7.6f, true, 140f), x, y + u0 * 1.5f + 9f, 7.6f, Sk.WHITE, 1, true, 0.3f);
        }
        // indicator pe margine cand destinatia e in afara ecranului
        if (GpsNav.active && GpsNav.dim.equals(GpsData.dimOf(mc))) {
            float x = sx(GpsNav.x);
            float y = sy(GpsNav.z);
            if (x < 20 || y < 70 || x > areaRight - 20 || y > height - 20) {
                float ex = Math.max(28f, Math.min(areaRight - 28f, x));
                float ey = Math.max(84f, Math.min(height - 84f, y));
                double ang = Math.atan2(x - mapCx, -(y - mapCy));
                GpsIcons.arrow(g, ex, ey, 10f, ang, GpsNav.color, 0xFF0A1220);
            }
        }
        return hover;
    }

    private boolean isShown(String id) {
        for (GpsData.Loc l : shown) if (l.id.equals(id)) return true;
        return false;
    }

    private void drawPlayer(GuiGraphics g, Minecraft mc, float t) {
        float x = sx(mc.player.getX());
        float y = sy(mc.player.getZ());
        if (x < -20 || y < -20 || x > width + 20 || y > height + 20) return;
        float ph = (t % 2f) / 2f;
        Gfx.ring(g, x, y, 7f + ph * 10f, 7.8f + ph * 10f, Gfx.fade(0xFF4DA3FF, 0.7f * (1f - ph)), Gfx.fade(0xFF4DA3FF, 0.7f * (1f - ph)));
        Gfx.disc(g, x, y, 8.5f, 0x88000000);
        double ang = Math.PI + Math.toRadians(mc.player.yRot);
        GpsIcons.arrow(g, x, y, 7.5f, ang, 0xFFFFFFFF, 0xFF1B6FD1);
    }

    private void tooltip(GuiGraphics g, GpsData.Loc l, int mx, int my) {
        double d = dist(l);
        String line = l.catName + (d >= 0 ? "  ·  " + GpsNav.fmtDist(d) : "");
        float w = Math.max(Gfx.width(l.name, 9f, true), Gfx.width(line, 7.4f, false)) + 24f;
        float x = Math.min(mx + 14f, width - w - 6f);
        float y = Math.min(my + 14f, height - 44f);
        box(g, x, y, w, 36, 10f, 0xF20A1220, 0xFF2A3A55);
        Gfx.rrect(g, x + 6, y + 8, 3, 20, 1.5f, l.color);
        Gfx.text(g, l.name, x + 12, y + 15, 9f, Sk.WHITE, 0, true, 0f);
        Gfx.text(g, line, x + 12, y + 28, 7.4f, Sk.MUTED, 0);
    }

    // ------------------------------------------------------------------ butoane

    /** Dreptunghi rotunjit cu contur subtire (conturul = acelasi dreptunghi, mai mare cu bw). */
    static void box(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int edge, float bw) {
        Gfx.rrect(g, x - bw, y - bw, w + 2f * bw, h + 2f * bw, r + bw, edge);
        Gfx.rrect(g, x, y, w, h, r, fill);
    }

    static void box(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int edge) {
        box(g, x, y, w, h, r, fill, edge, 1f);
    }

    private static String fmt1(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private void btn(GuiGraphics g, String label, float x, float y, float w, float h, int style, int mx, int my, Runnable r) {
        boolean hov = Sk.in(mx, my, x, y, w, h);
        int bg;
        int fg;
        int edge;
        if (style == 0) {
            bg = hov ? 0xFF7DBBFF : Sk.ACC;
            fg = 0xFF061426;
            edge = 0xFF9CCBFF;
        } else if (style == 2) {
            bg = hov ? 0xFFFF7A6E : Sk.BAD;
            fg = 0xFF1A0705;
            edge = 0xFFFFA096;
        } else if (style == 3) {
            bg = hov ? 0xFF3A2F6B : 0xFF2A2250;
            fg = Sk.WHITE;
            edge = VIOLET;
        } else {
            bg = hov ? 0xFF1B2E47 : 0xE80B1424;
            fg = hov ? Sk.WHITE : 0xFFC9CBD0;
            edge = hov ? Sk.ACC : 0xFF22344D;
        }
        box(g, x, y, w, h, Math.min(12f, h * 0.34f), bg, edge);
        Gfx.text(g, label, x + w / 2f + 1, y + h / 2f + 3.4f, 8.6f, fg, 1, true, 0.5f);
        hitF(x, y, w, h, r);
    }

    private void sq(GuiGraphics g, float x, float y, float s, int mx, int my, Runnable r) {
        boolean hov = Sk.in(mx, my, x, y, s, s);
        box(g, x, y, s, s, 11f, hov ? 0xF0223656 : 0xE80B1424, hov ? Sk.ACC : 0xFF22344D);
        hitF(x, y, s, s, r);
    }

    private void drawControls(GuiGraphics g, int mx, int my) {
        float s = 36f;
        float x = areaRight - M - s;
        float y3 = height - M - s;
        float y2 = y3 - s - 7f;
        float y1 = y2 - s - 7f;
        sq(g, x, y1, s, mx, my, () -> zoomBy(1.3f, mapCx, mapCy));
        Gfx.line(g, x + 11, y1 + s / 2f, x + s - 11, y1 + s / 2f, 2f, Sk.WHITE);
        Gfx.line(g, x + s / 2f, y1 + 11, x + s / 2f, y1 + s - 11, 2f, Sk.WHITE);
        sq(g, x, y2, s, mx, my, () -> zoomBy(1f / 1.3f, mapCx, mapCy));
        Gfx.line(g, x + 11, y2 + s / 2f, x + s - 11, y2 + s / 2f, 2f, Sk.WHITE);
        sq(g, x, y3, s, mx, my, this::centerOnPlayer);
        float cy3 = y3 + s / 2f;
        Gfx.ring(g, x + s / 2f, cy3, 6f, 7.6f, Sk.WHITE, Sk.WHITE);
        Gfx.disc(g, x + s / 2f, cy3, 2.2f, Sk.ACC);
        Gfx.line(g, x + s / 2f, y3 + 7, x + s / 2f, y3 + 12, 1.6f, Sk.WHITE);
        Gfx.line(g, x + s / 2f, y3 + s - 7, x + s / 2f, y3 + s - 12, 1.6f, Sk.WHITE);
        Gfx.line(g, x + 7, cy3, x + 12, cy3, 1.6f, Sk.WHITE);
        Gfx.line(g, x + s - 7, cy3, x + s - 12, cy3, 1.6f, Sk.WHITE);
    }

    /** Litera de busola pe marginea hartii (fara fundal, cu umbra). */
    private void compass(GuiGraphics g, String letter, float x, float y) {
        Gfx.textS(g, letter, x, y, 12f, "N".equals(letter) ? 0xFFFFD36B : Sk.WHITE, 1, true, 0f);
    }

    private void drawTop(GuiGraphics g, Minecraft mc, int mx, int my) {
        // meniu (hamburger)
        float bx = M + leftW;
        float by = M;
        float hs = 40f;
        boolean hov = Sk.in(mx, my, bx, by, hs, hs);
        boolean act = menuOpen || hov;
        box(g, bx, by, hs, hs, 12f, act ? 0xF0223656 : 0xE80B1424, act ? Sk.ACC : 0xFF22344D);
        for (int i = 0; i < 3; i++) Gfx.rrect(g, bx + 11, by + 12 + i * 6.5f, 18, 2.6f, 1.3f, Sk.WHITE);
        hitF(bx, by, hs, hs, () -> menuOpen = !menuOpen);
        btn(g, "MINIMAP", bx + hs + 8, by, 96, hs, 1, mx, my, () -> mc.setScreen(new MiniMapSettingsScreen()));
        if (GpsData.admin) {
            btn(g, "EDITOR HARTĂ", bx + hs + 112, by, 132, hs, 3, mx, my, () -> mc.setScreen(new MapEditorScreen(camX, camZ, zoom)));
        }
        // coordonate (pastila sus, la centru)
        String sxs = "X: " + fmt1(mc.player.getX()) + "   |   Z: " + fmt1(mc.player.getZ());
        float w = Gfx.width(sxs, 10f, true) + 56f;
        float x = mapCx - w / 2f;
        box(g, x, M, w, 34, 17f, 0xEC0A1220, 0xFF2A3A55);
        Gfx.poly(g, new float[] {x + 18, M + 9, x + 24, M + 24, x + 18, M + 20.5f, x + 12, M + 24}, Sk.ACC);
        Gfx.text(g, sxs, mapCx + 8, M + 22.5f, 10f, Sk.WHITE, 1, true, 0.3f);
        // busola: litere pe marginile hartii
        compass(g, "N", mapCx, 76f);
        compass(g, "S", mapCx, height - 14f);
        compass(g, "V", leftW + 16f, mapCy + 4f);
        compass(g, "E", areaRight - 16f, mapCy + 4f);
        if (!overPanel(mx, my)) {
            long cx = Math.round(camX + (mx - mapCx) / zoom);
            long cz = Math.round(camZ + (my - mapCy) / zoom);
            Gfx.textS(g, "cursor " + cx + " , " + cz, mapCx, M + 50f, 7f, 0xFFD6E4F5, 1, false, 0f);
        }
    }

    /** Straturile hartii, comutabile independent (se retin local). */
    static final String[][] LAYERS = {
        {"locations", "Locații"}, {"legal", "Zone legale"}, {"illegal", "Zone ilegale"}, {"special", "Zone speciale"},
        {"police", "Poliție"}, {"hospital", "Spitale"}, {"shop", "Magazine"}, {"waypoints", "Waypoint-uri"}, {"other", "Alte categorii"},
        {"roads", "Rețea de drumuri"}
    };

    private void drawLayers(GuiGraphics g, int mx, int my) {
        if (!layersOpen) return;
        float w = 214f;
        float rowH = 26f;
        float h = (LAYERS.length + 1) * rowH + 40f;
        float x = M + leftW + 280f;
        float y = M + 48f;
        box(g, x, y, w, h, 14f, 0xF40A1220, 0xFF2A3A55);
        Gfx.text(g, "STRATURI", x + 14, y + 20, 8f, Sk.ACC, 0, true, 1.4f);
        hitF(x, y, w, h, () -> {});
        for (int i = 0; i < LAYERS.length; i++) {
            final String id = LAYERS[i][0];
            float ry = y + 30f + i * rowH;
            boolean on = GpsPrefs.layerOn(id);
            boolean hv = Sk.in(mx, my, x + 4, ry, w - 8, rowH - 2);
            if (hv) Gfx.rrect(g, x + 4, ry, w - 8, rowH - 2, 8f, 0xFF1B2E47);
            Gfx.rrect(g, x + 14, ry + 5, 14, 14, 3, on ? Sk.ACC : 0xFF22344D);
            if (on) {
                Gfx.line(g, x + 17, ry + 12, x + 20.5f, ry + 15.5f, 1.8f, 0xFF061426);
                Gfx.line(g, x + 20.5f, ry + 15.5f, x + 26, ry + 8, 1.8f, 0xFF061426);
            }
            Gfx.text(g, LAYERS[i][1], x + 38, ry + 16.5f, 8.6f, on ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            hitF(x + 4, ry, w - 8, rowH - 2, () -> {
                GpsPrefs.toggleLayer(id);
                dirty = true;
            });
        }
        float ry = y + 30f + LAYERS.length * rowH;
        boolean snd = GpsPrefs.navSound;
        if (Sk.in(mx, my, x + 4, ry, w - 8, rowH - 2)) Gfx.rrect(g, x + 4, ry, w - 8, rowH - 2, 8f, 0xFF1B2E47);
        Gfx.rrect(g, x + 14, ry + 5, 14, 14, 3, snd ? Sk.ACC : 0xFF22344D);
        if (snd) {
            Gfx.line(g, x + 17, ry + 12, x + 20.5f, ry + 15.5f, 1.8f, 0xFF061426);
            Gfx.line(g, x + 20.5f, ry + 15.5f, x + 26, ry + 8, 1.8f, 0xFF061426);
        }
        Gfx.text(g, "Sunete la viraje", x + 38, ry + 16.5f, 8.6f, snd ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
        hitF(x + 4, ry, w - 8, rowH - 2, () -> {
            GpsPrefs.navSound = !GpsPrefs.navSound;
            GpsPrefs.save();
        });
    }

    private void drawCard(GuiGraphics g, Minecraft mc, int mx, int my) {
        float h = 74f;
        if (showZones && !GpsData.zones.isEmpty()) {
            float ly = height - M - (GpsNav.active ? h + 8f : 0f) - 26f;
            float lx = M + leftW;
            box(g, lx, ly, 160, 26, 13f, 0xE80B1424, 0xFF22344D);
            Gfx.rrect(g, lx + 12, ly + 8.5f, 9, 9, 3, 0xFF2FBF71);
            Gfx.text(g, "LEGAL", lx + 26, ly + 17, 6.8f, Sk.WHITE, 0, true, 0.6f);
            Gfx.rrect(g, lx + 80, ly + 8.5f, 9, 9, 3, 0xFFE5484D);
            Gfx.text(g, "ILEGAL", lx + 94, ly + 17, 6.8f, Sk.WHITE, 0, true, 0.6f);
        }
        if (!GpsNav.active) return;
        float w = cardW();
        float x = M + leftW;
        float y = height - M - h;
        box(g, x, y, w, h, 15f, 0xF00A1220, 0xFF2A3A55);
        Gfx.rrect(g, x + 8, y + 12, 3.5f, h - 24, 1.7f, GpsNav.color);
        Gfx.text(g, "RUTĂ ACTIVĂ", x + 22, y + 19, 6.6f, Sk.ACC, 0, true, 1.4f);
        Gfx.text(g, RoadRouteProvider.I.statusText(), x + 22 + Gfx.width("RUTĂ ACTIVĂ", 6.6f, true) + 14f, y + 19, 6.6f, Sk.MUTED, 0);
        Gfx.text(g, clip(GpsNav.name, 10.5f, true, w - 190f), x + 22, y + 38, 10.5f, Sk.WHITE, 0, true, 0f);
        double d = GpsNav.travelDistance(mc);
        String info = d < 0 ? "în altă lume" : GpsNav.fmtDist(d) + "  ·  ~" + GpsNav.fmtEta(GpsNav.etaSeconds(mc));
        RoadRouteProvider.Step nx = RoadRouteProvider.I.next();
        if (nx != null && d >= 0) {
            info += "  ·  " + (nx.turn == 9 ? "sosire" : "în " + GpsNav.fmtDist(RoadRouteProvider.I.nextDist()) + ": " + nx.text().replace("Virează ", "").replace("Întoarce-te", "întoarcere"));
        }
        Gfx.text(g, clip(info, 9f, true, w - 190f), x + 22, y + 58, 9f, 0xFFC9D4E4, 0, true, 0f);
        // buton "Anulare GPS" (pastila cu X rosu)
        float bw = 138f;
        float bh = 34f;
        float bx = x + w - bw - 14f;
        float by = y + (h - bh) / 2f;
        boolean hv = Sk.in(mx, my, bx, by, bw, bh);
        box(g, bx, by, bw, bh, 12f, hv ? 0xFF2A1518 : 0xFF1A1014, hv ? Sk.BAD : 0xFF6B2A2E);
        Gfx.rrect(g, bx + 8, by + 8, 18, 18, 5f, Sk.BAD);
        Gfx.line(g, bx + 13, by + 13, bx + 21, by + 21, 1.8f, Sk.WHITE);
        Gfx.line(g, bx + 21, by + 13, bx + 13, by + 21, 1.8f, Sk.WHITE);
        Gfx.text(g, "Anulare GPS", bx + 33, by + 21.5f, 9f, Sk.WHITE, 0, true, 0f);
        hitF(bx, by, bw, bh, this::cancel);
    }

    // ------------------------------------------------------------------ panou LOCATII

    private void drawPanel(GuiGraphics g, Minecraft mc, int mx, int my, float t) {
        if (panelAnim < 0.02f) return;
        float x = panelX + shift;
        float y = M;
        float w = panelW;
        float h = height - 2 * M;
        box(g, x, y, w, h, 18f, 0xF20A1220, 0xFF2A3A55);
        Gfx.rrect(g, x + 14, y + 17, 3.5f, 19, 1.7f, Sk.ACC);
        Gfx.text(g, "LOCAȚII", x + 24, y + 33, 15f, Sk.WHITE, 0, true, 0.6f);
        Gfx.text(g, totalInDim + " total", x + w - 18, y + 31, 8f, Sk.MUTED, 2);
        Gfx.rrect(g, x + 16, y + 43, w - 32, 2, 1f, Sk.ACC);
        Gfx.rrect(g, x + w - 16 - (w - 32) * 0.22f, y + 43, (w - 32) * 0.22f, 2, 1f, VIOLET);

        // cautare
        float sy0 = y + 55;
        box(g, x + 14, sy0, w - 28, 28, 10f, 0xE00D1626, search.isFocused() ? Sk.ACC : 0xFF22344D);
        Gfx.ring(g, x + 29, sy0 + 13, 3.2f, 4.4f, Sk.MUTED, Sk.MUTED);
        Gfx.line(g, x + 32, sy0 + 16, x + 36, sy0 + 20, 1.6f, Sk.MUTED);
        search.x = Math.round(x + 44);
        search.y = Math.round(sy0 + 10);
        search.setWidth(Math.round(w - 64));
        search.render(g.pose(), mx, my, 0f);
        hitF(x + 14, sy0, w - 28, 28, () -> focus(search, true));

        // filtre
        List<String> cs = chips();
        float cx = x + 14;
        float cy = sy0 + 37;
        float maxX = x + w - 14;
        for (String c : cs) {
            String label = "FAV".equals(c) ? "★" : c;
            float cw = Gfx.width(label, 7.2f, true) + 22f;
            if ("FAV".equals(c)) cw = 34f;
            if (cx + cw > maxX) {
                cx = x + 14;
                cy += 25f;
            }
            boolean sel = c.equals(filter);
            boolean hv = Sk.in(mx, my, cx, cy, cw, 21);
            box(g, cx, cy, cw, 21, 8f, sel ? Sk.ACC : hv ? 0xFF22344D : 0xE00D1626, sel ? Sk.ACC : 0xFF22344D);
            if ("FAV".equals(c)) star(g, cx + cw / 2f, cy + 10.5f, 6f, sel ? 0xFF061426 : 0xFFFFC857);
            else Gfx.text(g, label, cx + cw / 2f + 1, cy + 14.5f, 7.2f, sel ? 0xFF061426 : Sk.MUTED, 1, true, 0.5f);
            final String key = c;
            hitF(cx, cy, cw, 21, () -> {
                filter = key;
                scroll = 0;
                dirty = true;
            });
            cx += cw + 6f;
        }
        listTop = cy + 32f;

        // zona de jos: bara de pagini + detalii
        float pbH = 34f;
        float pbY = y + h - 14 - pbH;
        boolean compact = height < 360;
        float dH = selected != null ? (compact ? 72f : 104f) : 0f;
        float dY = pbY - 8 - dH;
        listBottom = (selected != null ? dY : pbY) - 8f;
        rowsVisible = Math.max(1, (int) ((listBottom - listTop + (PITCH - ROW_H)) / PITCH));
        clampScroll();

        // lista
        float rowW = w - 36f;
        if (rows.isEmpty()) {
            String msg = !GpsData.loaded && GpsData.all().isEmpty() ? "Se încarcă locațiile…" : (totalInDim == 0 ? "Nicio locație configurată" : "Nicio locație găsită");
            Gfx.text(g, msg, x + w / 2f, listTop + 30, 8.6f, Sk.MUTED, 1);
            if (totalInDim == 0 && GpsData.loaded) Gfx.text(g, GpsData.admin ? "Adaugă din EDITOR HARTĂ → LOCAȚII" : "Adminul poate adăuga locații din editor", x + w / 2f, listTop + 46, 6.8f, Sk.FAINT, 1);
        }
        for (int i = 0; i < rowsVisible; i++) {
            int idx = scroll + i;
            if (idx >= rows.size()) break;
            final List<GpsData.Loc> grp = rows.get(idx);
            GpsData.Loc l = cur(grp);
            int n = grp.size();
            float ry = listTop + i * PITCH;
            float appear = ease((System.currentTimeMillis() - born - i * 40L) / 260f);
            float rx = x + 14 + (1f - appear) * 18f;
            boolean sel = hasSel(grp);
            boolean hv = Sk.in(mx, my, x + 14, ry, rowW, ROW_H);
            box(g, rx, ry, rowW, ROW_H, 12f, sel ? 0xF016294A : hv ? 0xF0152238 : 0xE00D1626, sel ? Sk.ACC : hv ? 0xFF2F4668 : 0xFF1E2C44, sel ? 2f : 1f);
            if (sel) Gfx.rrect(g, rx + 5, ry + 9, 3f, ROW_H - 18, 1.5f, VIOLET);
            float icx = rx + 27;
            float icy = ry + ROW_H / 2f;
            if (GpsNav.isTarget(l.id)) Gfx.ring(g, icx, icy, 10.5f, 11.6f, Gfx.fade(0xFFFFFFFF, 0.9f * appear), Gfx.fade(0xFFFFFFFF, 0.9f * appear));
            GpsIcons.glyph(g, l.icon, icx, icy, 5.4f, Gfx.fade(l.color, appear), appear);
            int nameCol = sel || hv ? Sk.WHITE : 0xFFD6E0EE;
            if (n > 1) {
                float a2 = rx + rowW - 30f;
                String lab = (variantIndex(grp) + 1) + "/" + n;
                float lw = Gfx.width(lab, 9f, true);
                float lx = a2 - 8f - lw / 2f;
                float a1 = lx - lw / 2f - 8f - 22f;
                Gfx.text(g, clip(upper(l.name), 9.2f, true, a1 - (rx + 46) - 6f), rx + 46, icy + 3.4f, 9.2f, nameCol, 0, true, 0.4f);
                final float fa1 = a1;
                final float fa2 = a2;
                final float fry = ry;
                final GpsData.Loc fl0 = l;
                hitF(x + 14, ry, rowW, ROW_H, () -> rowClick(fl0));
                Gfx.text(g, lab, lx, icy + 3.4f, 9f, Sk.WHITE, 1, true, 0f);
                stepBtn(g, fa1, fry + 8f, 22f, 22f, true, mx, my, () -> cycle(grp, -1));
                stepBtn(g, fa2, fry + 8f, 22f, 22f, false, mx, my, () -> cycle(grp, 1));
            } else {
                double d = dist(l);
                String ds = d < 0 ? "-" : GpsNav.fmtDist(d);
                float dw = Gfx.width(ds, 7.4f, false);
                if (GpsPrefs.fav.contains(l.id)) star(g, rx + rowW - 14 - dw - 12f, icy, 4.6f, 0xFFFFC857);
                Gfx.text(g, ds, rx + rowW - 14, icy + 2.8f, 7.4f, sel ? Sk.WHITE : Sk.MUTED, 2);
                float tw = rowW - 46 - dw - 34;
                Gfx.text(g, clip(upper(l.name), 9.2f, true, tw), rx + 46, icy + 3.4f, 9.2f, nameCol, 0, true, 0.4f);
                final GpsData.Loc fl = l;
                hitF(x + 14, ry, rowW, ROW_H, () -> rowClick(fl));
            }
        }

        // scrollbar personalizat (indicator; derulare cu rotita / PgUp / PgDn / sageti)
        if (rows.size() > rowsVisible) {
            float th = Math.max(20f, listBottom - listTop);
            float trackX = x + w - 17f;
            Gfx.rrect(g, trackX, listTop, 4f, th, 2f, 0xFF14202F);
            float thumb = Math.max(20f, th * rowsVisible / (float) rows.size());
            float maxS = rows.size() - rowsVisible;
            float ty = listTop + (th - thumb) * (maxS <= 0 ? 0f : scroll / maxS);
            Gfx.rrect(g, trackX, ty, 4f, thumb, 2f, Sk.ACC, VIOLET);
        }

        // detalii selectie
        if (selected != null) {
            GpsData.Loc s = selected;
            box(g, x + 14, dY, w - 28, dH, 13f, 0xFF0F1A2C, 0xFF22344D);
            Gfx.rrect(g, x + 22, dY + 9, 3.2f, 44, 1.6f, s.color);
            Gfx.text(g, clip(s.name, 10.5f, true, w - 96), x + 34, dY + 21, 10.5f, Sk.WHITE, 0, true, 0f);
            double d = dist(s);
            String sub = s.catName + (d >= 0 ? "  ·  " + GpsNav.fmtDist(d) : "");
            Gfx.text(g, clip(sub, 7.2f, false, w - 86), x + 34, dY + 33, 7.2f, Sk.MUTED, 0);
            if (!compact) {
                String desc = s.desc.isEmpty() ? "" : s.desc;
                Gfx.text(g, clip(desc, 7.2f, false, w - 58), x + 34, dY + 45, 7.2f, 0xFF9CB4D0, 0);
                Gfx.text(g, "X: " + Math.round(s.x) + "   Z: " + Math.round(s.z), x + 34, dY + 57, 7.4f, Sk.FAINT, 0, true, 0.2f);
            }
            if (!PIN.equals(s.id)) {
                boolean fav = GpsPrefs.fav.contains(s.id);
                float fx = x + w - 38;
                star(g, fx + 8, dY + 16, 7f, fav ? 0xFFFFC857 : 0xFF55667C);
                hitF(fx - 4, dY + 5, 24, 22, () -> toggleFav(s));
            }
            boolean target = GpsNav.isTarget(s.id);
            btn(g, target ? "ANULEAZĂ GPS" : "SETEAZĂ GPS", x + 24, dY + dH - (compact ? 35 : 38), w - 48, compact ? 28 : 30, target ? 2 : 0, mx, my, this::activate);
        }

        // bara de pagini: "x-y / total" + Scroll
        box(g, x + 14, pbY, w - 28, pbH, 12f, 0xE00D1626, 0xFF22344D);
        String range = rows.isEmpty() ? "0 / 0" : (scroll + 1) + "-" + Math.min(scroll + rowsVisible, rows.size()) + " / " + rows.size();
        Gfx.text(g, range, x + 28, pbY + 22f, 10f, Sk.WHITE, 0, true, 0.3f);
        Gfx.text(g, "Scroll", x + w - 86, pbY + 20.5f, 7.2f, Sk.FAINT, 2);
        float bx = x + w - 20 - 52;
        arrowBtn(g, bx, pbY + 5, 24, 24, true, mx, my, () -> {
            scroll -= rowsVisible;
            clampScroll();
        });
        arrowBtn(g, bx + 28, pbY + 5, 24, 24, false, mx, my, () -> {
            scroll += rowsVisible;
            clampScroll();
        });
    }

    private int variantIndex(List<GpsData.Loc> grp) {
        Integer i = variant.get(key(grp));
        int k = i == null ? 0 : i;
        return k < 0 || k >= grp.size() ? 0 : k;
    }

    private void rowClick(GpsData.Loc fl) {
        long now = System.currentTimeMillis();
        boolean dbl = fl.id.equals(lastClickId) && now - lastClickAt < 380;
        lastClickId = fl.id;
        lastClickAt = now;
        select(fl, true);
        if (dbl) activate();
    }

    /** Sageata stanga/dreapta din randul cu mai multe locatii ("< 2/2 >"). */
    private void stepBtn(GuiGraphics g, float x, float y, float w, float h, boolean left, int mx, int my, Runnable r) {
        boolean hv = Sk.in(mx, my, x, y, w, h);
        box(g, x, y, w, h, 7f, hv ? 0xFF22344D : 0xFF14202F, hv ? Sk.ACC : 0xFF2A3A55);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        int col = hv ? Sk.WHITE : Sk.MUTED;
        if (left) Gfx.poly(g, new float[] {cx - 3.5f, cy, cx + 2.5f, cy - 5f, cx + 2.5f, cy + 5f}, col);
        else Gfx.poly(g, new float[] {cx + 3.5f, cy, cx - 2.5f, cy - 5f, cx - 2.5f, cy + 5f}, col);
        hitF(x, y, w, h, r);
    }

    private void arrowBtn(GuiGraphics g, float x, float y, float w, float h, boolean up, int mx, int my, Runnable r) {
        boolean hv = Sk.in(mx, my, x, y, w, h);
        Gfx.rrect(g, x, y, w, h, 8f, hv ? 0xFF22344D : 0xFF14202F);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        if (up) Gfx.poly(g, new float[] {cx, cy - 4f, cx + 5f, cy + 3f, cx - 5f, cy + 3f}, hv ? Sk.WHITE : Sk.MUTED);
        else Gfx.poly(g, new float[] {cx, cy + 4f, cx + 5f, cy - 3f, cx - 5f, cy - 3f}, hv ? Sk.WHITE : Sk.MUTED);
        hitF(x, y, w, h, r);
    }

    private void star(GuiGraphics g, float cx, float cy, float r, int color) {
        float[] p = new float[24];
        p[0] = cx;
        p[1] = cy;
        for (int i = 0; i <= 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? r : r * 0.45f;
            p[2 + i * 2] = cx + (float) Math.cos(a) * rr;
            p[3 + i * 2] = cy + (float) Math.sin(a) * rr;
        }
        Gfx.poly(g, p, color);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
