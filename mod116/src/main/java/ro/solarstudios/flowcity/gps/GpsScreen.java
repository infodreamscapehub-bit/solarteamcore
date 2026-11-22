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
    private static final float M = 12f;
    private static final int VIOLET = 0xFF8B7BFF;
    private static final float PITCH = 30f;
    private static final float ROW_H = 26f;
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
    private List<GpsData.Loc> all = new ArrayList<>();
    private GpsData.Loc selected;
    private int totalInDim;
    private String filter = "TOATE";
    private int scroll;
    private boolean panelOpen = true;
    private float panelAnim = 1f;
    private boolean menuOpen;
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
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rowsVisible)));
    }

    private double dist(GpsData.Loc l) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !l.dim.equals(GpsData.dimOf(mc))) return -1;
        double dx = l.x - mc.player.getX();
        double dz = l.z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ------------------------------------------------------------------ actiuni

    private void say(String s) {
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
        if (l != null) revealSelected();
    }

    private void revealSelected() {
        int i = shown.indexOf(selected);
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

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        tgtX = mc.player.getX();
        tgtZ = mc.player.getZ();
        anim = true;
    }

    private void fitAll() {
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
        return panelAnim > 0.05f && mx >= panelX + shift && my >= M && my <= height - M;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (super.mouseClicked(mx, my, button)) return true;
            if (overPanel(mx, my)) return true;
            if (GpsNav.active && mx < 12 + cardW() && my > height - 12 - 70) return true;
            menuOpen = false;
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
        if (overPanel(mx, my)) {
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

    private void hitF(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(x), Math.round(y), Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), r);
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
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
        mapCx = areaRight / 2f;
        mapCy = height / 2f;
        if (anim) {
            camX += (tgtX - camX) * Math.min(1f, dt * 7f);
            camZ += (tgtZ - camZ) * Math.min(1f, dt * 7f);
            if (Math.abs(tgtX - camX) < 0.3 && Math.abs(tgtZ - camZ) < 0.3) anim = false;
        }
        if (dirty) rebuild();

        // fundal + harta
        Gfx.rrect(g, 0, 0, width, height, 0, 0xFF0C1C33, 0xFF08111F);
        drawGrid(g);
        GpsMap.draw(g, camX, camZ, zoom, mapCx, mapCy, width, height, open);
        g.fillGradient(0, 0, width, 70, 0xAA070F1C, 0x00070F1C);
        g.fillGradient(0, height - 80, width, height, 0x00070F1C, 0xAA070F1C);
        if (showZones) drawZones(g);
        drawRoute(g, t);
        GpsData.Loc hover = drawMarkers(g, mc, mx, my, t);
        drawPlayer(g, mc, t);
        drawSnow(g, t);

        // interfata
        drawTop(g, mc, mx, my);
        drawCard(g, mc, mx, my);
        drawControls(g, mx, my);
        drawLayers(g, mx, my);
        drawPanel(g, mc, mx, my, t);
        if (hover != null && !overPanel(mx, my)) tooltip(g, hover, mx, my);
        if (System.currentTimeMillis() < noteUntil) {
            float w = Gfx.width(note, 8.6f, true) + 28f;
            float x = mapCx - w / 2f;
            Sk.cut(g, x, 52, w, 22, 6, 0xF0081018);
            Gfx.rrect(g, x, 52, 3, 22, 0, Sk.ACC);
            Gfx.text(g, note, mapCx, 67, 8.6f, Sk.WHITE, 1, true, 0f);
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
    }

    private GpsData.Loc drawMarkers(GuiGraphics g, Minecraft mc, int mx, int my, float t) {
        float r = Math.max(6.5f, Math.min(10f, 5f + zoom * 1.6f));
        GpsData.Loc hover = null;
        float bd = (r + 4f) * (r + 4f);
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
            float lr = r * l.size / 100f;
            GpsIcons.badge(g, l.icon, x, y, hv ? lr + 1.5f : lr, l.color, 1f, GpsNav.isTarget(l.id));
            if (zoom >= 1.5f || hv) Gfx.textS(g, clip(l.name, 7.2f, true, 110f), x, y + r + 9f, 7.2f, Sk.WHITE, 1, true, 0.3f);
        }
        if (sel != null && (!PIN.equals(sel.id) || GpsPrefs.layerOn("waypoints"))) {
            float x = sx(sel.x);
            float y = sy(sel.z);
            float ph = (t % 1.6f) / 1.6f;
            Gfx.ring(g, x, y, r + 3f + ph * 12f, r + 4f + ph * 12f, Gfx.fade(sel.color, 0.8f * (1f - ph)), Gfx.fade(sel.color, 0.8f * (1f - ph)));
            GpsIcons.badge(g, sel.icon, x, y, r + 2f, sel.color, 1f, true);
            Gfx.textS(g, clip(sel.name, 8f, true, 140f), x, y + r + 12f, 8f, Sk.WHITE, 1, true, 0.3f);
        }
        // destinatia activa daca nu e in lista curenta (ex. setata de server)
        if (GpsNav.active && GpsPrefs.layerOn("waypoints") && GpsNav.dim.equals(GpsData.dimOf(mc)) && !isShown(GpsNav.id)) {
            float x = sx(GpsNav.x);
            float y = sy(GpsNav.z);
            GpsIcons.badge(g, "pin", x, y, r + 1f, GpsNav.color, 1f, true);
            Gfx.textS(g, clip(GpsNav.name, 7.6f, true, 140f), x, y + r + 11f, 7.6f, Sk.WHITE, 1, true, 0.3f);
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

    private void drawSnow(GuiGraphics g, float t) {
        for (int i = 0; i < 16; i++) {
            float sp = 9f + (i % 5) * 4f;
            float x = ((i * 97.3f + t * (5f + i % 4)) % (width + 24f)) - 12f + (float) Math.sin(t * 0.8f + i) * 6f;
            float y = ((i * 53.7f + t * sp) % (height + 24f)) - 12f;
            Gfx.disc(g, x, y, 0.9f + (i % 3) * 0.5f, 0x44FFFFFF);
        }
    }

    private void tooltip(GuiGraphics g, GpsData.Loc l, int mx, int my) {
        double d = dist(l);
        String line = l.catName + (d >= 0 ? "  ·  " + GpsNav.fmtDist(d) : "");
        float w = Math.max(Gfx.width(l.name, 9f, true), Gfx.width(line, 7.4f, false)) + 24f;
        float x = Math.min(mx + 14f, width - w - 6f);
        float y = Math.min(my + 14f, height - 44f);
        Sk.cut(g, x, y, w, 36, 6, 0xF2081018);
        Gfx.rrect(g, x, y, 3, 36, 0, l.color);
        Gfx.text(g, l.name, x + 12, y + 15, 9f, Sk.WHITE, 0, true, 0f);
        Gfx.text(g, line, x + 12, y + 28, 7.4f, Sk.MUTED, 0);
    }

    // ------------------------------------------------------------------ butoane

    private void btn(GuiGraphics g, String label, float x, float y, float w, float h, int style, int mx, int my, Runnable r) {
        boolean hov = Sk.in(mx, my, x, y, w, h);
        int bg;
        int fg;
        if (style == 0) {
            bg = hov ? 0xFF7DBBFF : Sk.ACC;
            fg = 0xFF061426;
        } else if (style == 2) {
            bg = hov ? 0xFFFF7A6E : Sk.BAD;
            fg = 0xFF1A0705;
        } else {
            bg = hov ? 0xFF22344D : 0xFF14202F;
            fg = hov ? Sk.WHITE : 0xFFC9CBD0;
        }
        Sk.slant(g, x, y, w, h, 7f, bg);
        Gfx.text(g, label, x + w / 2f + 1, y + h / 2f + 3.4f, 8.6f, fg, 1, true, 0.5f);
        hitF(x, y, w, h, r);
    }

    private void sq(GuiGraphics g, float x, float y, float s, int mx, int my, Runnable r) {
        boolean hov = Sk.in(mx, my, x, y, s, s);
        Sk.cut(g, x, y, s, s, 6f, hov ? 0xF0223656 : 0xF0081018);
        Gfx.rrect(g, x, y + s - 2, s, 2, 0, hov ? Sk.ACC : 0xFF22344D);
        hitF(x, y, s, s, r);
    }

    private void drawControls(GuiGraphics g, int mx, int my) {
        float s = 34f;
        float x = areaRight - M - s - (panelAnim > 0.5f ? 0f : 0f);
        float y3 = height - M - s;
        float y2 = y3 - s - 6f;
        float y1 = y2 - s - 6f;
        sq(g, x, y1, s, mx, my, () -> zoomBy(1.3f, mapCx, mapCy));
        Gfx.line(g, x + 11, y1 + s / 2f - 1, x + s - 11, y1 + s / 2f - 1, 2f, Sk.WHITE);
        Gfx.line(g, x + s / 2f, y1 + 10, x + s / 2f, y1 + s - 12, 2f, Sk.WHITE);
        sq(g, x, y2, s, mx, my, () -> zoomBy(1f / 1.3f, mapCx, mapCy));
        Gfx.line(g, x + 11, y2 + s / 2f - 1, x + s - 11, y2 + s / 2f - 1, 2f, Sk.WHITE);
        sq(g, x, y3, s, mx, my, this::centerOnPlayer);
        Gfx.ring(g, x + s / 2f, y3 + s / 2f - 1, 6f, 7.6f, Sk.WHITE, Sk.WHITE);
        Gfx.disc(g, x + s / 2f, y3 + s / 2f - 1, 2.2f, Sk.ACC);
        Gfx.line(g, x + s / 2f, y3 + 6, x + s / 2f, y3 + 11, 1.6f, Sk.WHITE);
        Gfx.line(g, x + s / 2f, y3 + s - 8, x + s / 2f, y3 + s - 13, 1.6f, Sk.WHITE);
        Gfx.line(g, x + 7, y3 + s / 2f - 1, x + 12, y3 + s / 2f - 1, 1.6f, Sk.WHITE);
        Gfx.line(g, x + s - 7, y3 + s / 2f - 1, x + s - 12, y3 + s / 2f - 1, 1.6f, Sk.WHITE);
    }

    private void compass(GuiGraphics g, String letter, float x, float y) {
        Gfx.disc(g, x, y, 10f, 0xE0081018);
        Gfx.ring(g, x, y, 9f, 10f, 0xFF22344D, 0xFF22344D);
        Gfx.text(g, letter, x, y + 3.4f, 8.6f, "N".equals(letter) ? Sk.ACC : Sk.WHITE, 1, true, 0f);
    }

    private void drawTop(GuiGraphics g, Minecraft mc, int mx, int my) {
        // meniu (hamburger)
        float bx = M;
        float by = M;
        boolean hov = Sk.in(mx, my, bx, by, 38, 38);
        Sk.cut(g, bx, by, 38, 38, 8, menuOpen || hov ? 0xF0223656 : 0xF0081018);
        for (int i = 0; i < 3; i++) Gfx.rrect(g, bx + 10, by + 11 + i * 6, 18, 2.4f, 1, Sk.WHITE);
        hitF(bx, by, 38, 38, () -> menuOpen = !menuOpen);
        btn(g, "MINIMAP", bx + 46, by, 96, 38, 0, mx, my, () -> mc.setScreen(new MiniMapSettingsScreen()));
        if (GpsData.admin) {
            btn(g, "EDITOR HARTĂ", bx + 150, by, 132, 38, 0, mx, my, () -> mc.setScreen(new MapEditorScreen(camX, camZ, zoom)));
        }
        if (menuOpen) {
            String[] items = {
                "Zone RP: " + (showZones ? "ACTIV" : "OPRIT"),
                "Centrează pe mine",
                "Vezi tot ce ai explorat",
                panelOpen ? "Ascunde panoul" : "Arată panoul",
                "Straturi hartă",
                "Grilă: " + (GpsPrefs.grid ? "ACTIVĂ" : "OPRITĂ")
            };
            float mw = 214f;
            float my0 = by + 44f;
            Sk.cut(g, bx, my0, mw, items.length * 30f + 10f, 8, 0xF4081018);
            for (int i = 0; i < items.length; i++) {
                float y = my0 + 5f + i * 30f;
                boolean h = Sk.in(mx, my, bx + 4, y, mw - 8, 28);
                if (h) Sk.slant(g, bx + 4, y, mw - 8, 28, 5f, 0xFF1B2E47);
                Gfx.text(g, items[i], bx + 16, y + 18, 8.6f, h ? Sk.WHITE : 0xFFC9CBD0, 0, true, 0f);
                final int idx = i;
                hitF(bx + 4, y, mw - 8, 28, () -> {
                    menuOpen = false;
                    if (idx == 0) {
                        showZones = !showZones;
                    } else if (idx == 1) {
                        centerOnPlayer();
                    } else if (idx == 2) {
                        fitAll();
                    } else if (idx == 3) {
                        panelOpen = !panelOpen;
                    } else if (idx == 4) {
                        layersOpen = !layersOpen;
                    } else {
                        GpsPrefs.grid = !GpsPrefs.grid;
                    }
                });
            }
        }
        // coordonate
        String sxs = "X: " + Math.round(mc.player.getX()) + "   |   Z: " + Math.round(mc.player.getZ());
        float w = Gfx.width(sxs, 10f, true) + 40f;
        float x = mapCx - w / 2f;
        Sk.cut(g, x, M, w, 30, 8, 0xF0081018);
        Gfx.rrect(g, x, M + 28, w, 2, 0, Sk.ACC);
        Gfx.disc(g, x + 14, M + 15, 3.4f, Sk.ACC);
        Gfx.text(g, sxs, mapCx + 5, M + 20.5f, 10f, Sk.WHITE, 1, true, 0.3f);
        // busola
        compass(g, "N", mapCx, 92f);
        compass(g, "S", mapCx, height - 26f);
        compass(g, "W", 26f, mapCy);
        compass(g, "E", areaRight - 26f, mapCy);
        if (!overPanel(mx, my)) {
            long cx = Math.round(camX + (mx - mapCx) / zoom);
            long cz = Math.round(camZ + (my - mapCy) / zoom);
            Gfx.text(g, "cursor " + cx + " , " + cz, mapCx, M + 44f, 7f, 0xFF9CB4D0, 1);
        }
    }

    /** Straturile hartii, comutabile independent (se retin local). */
    static final String[][] LAYERS = {
        {"locations", "Locații"}, {"legal", "Zone legale"}, {"illegal", "Zone ilegale"}, {"special", "Zone speciale"},
        {"police", "Poliție"}, {"hospital", "Spitale"}, {"shop", "Magazine"}, {"waypoints", "Waypoint-uri"}, {"other", "Alte categorii"}
    };

    private void drawLayers(GuiGraphics g, int mx, int my) {
        if (!layersOpen) return;
        float w = 214f;
        float rowH = 26f;
        float h = LAYERS.length * rowH + 40f;
        float x = M;
        float y = M + 44f + (menuOpen ? 6f * 30f + 16f : 0f);
        Sk.cut(g, x, y, w, h, 8, 0xF4081018);
        Gfx.text(g, "STRATURI", x + 14, y + 20, 8f, Sk.ACC, 0, true, 1.4f);
        hitF(x, y, w, h, () -> {});
        for (int i = 0; i < LAYERS.length; i++) {
            final String id = LAYERS[i][0];
            float ry = y + 30f + i * rowH;
            boolean on = GpsPrefs.layerOn(id);
            boolean hv = Sk.in(mx, my, x + 4, ry, w - 8, rowH - 2);
            if (hv) Sk.slant(g, x + 4, ry, w - 8, rowH - 2, 5f, 0xFF1B2E47);
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
    }

    private void drawCard(GuiGraphics g, Minecraft mc, int mx, int my) {
        if (showZones && !GpsData.zones.isEmpty()) {
            float ly = height - M - (GpsNav.active ? 78f : 0f) - 22f;
            Sk.cut(g, M, ly, 156, 20, 5, 0xE0081018);
            Gfx.rrect(g, M + 8, ly + 6, 9, 9, 2, 0xFF2FBF71);
            Gfx.text(g, "LEGAL", M + 22, ly + 14, 6.8f, Sk.WHITE, 0, true, 0.6f);
            Gfx.rrect(g, M + 76, ly + 6, 9, 9, 2, 0xFFE5484D);
            Gfx.text(g, "ILEGAL", M + 90, ly + 14, 6.8f, Sk.WHITE, 0, true, 0.6f);
        }
        if (!GpsNav.active) return;
        float w = cardW();
        float h = 70f;
        float x = M;
        float y = height - M - h;
        Sk.cut(g, x, y, w, h, 10, 0xF4081018);
        Gfx.rrect(g, x, y + 8, 4, h - 16, 0, GpsNav.color);
        Gfx.text(g, "RUTĂ ACTIVĂ", x + 16, y + 17, 6.6f, Sk.ACC, 0, true, 1.4f);
        Gfx.text(g, GpsNav.route.roads() ? "pe străzi" : "direcție directă", x + 16 + Gfx.width("RUTĂ ACTIVĂ", 6.6f, true) + 14f, y + 17, 6.6f, Sk.MUTED, 0);
        Gfx.text(g, clip(GpsNav.name, 10.5f, true, w - 150f), x + 16, y + 34, 10.5f, Sk.WHITE, 0, true, 0f);
        double d = GpsNav.distance(mc);
        String info = d < 0 ? "în altă lume" : GpsNav.fmtDist(d) + "  ·  ~" + GpsNav.fmtEta(GpsNav.etaSeconds(mc));
        Gfx.text(g, info, x + 16, y + 54, 9f, 0xFFC9D4E4, 0, true, 0f);
        btn(g, "ANULEAZĂ GPS", x + w - 126, y + h - 42, 114, 30, 2, mx, my, this::cancel);
    }

    // ------------------------------------------------------------------ panou LOCATII

    private void drawPanel(GuiGraphics g, Minecraft mc, int mx, int my, float t) {
        if (panelAnim < 0.02f) return;
        float x = panelX + shift;
        float y = M;
        float w = panelW;
        float h = height - 2 * M;
        Sk.cut(g, x - 1, y - 1, w + 2, h + 2, 17, 0x884DA3FF);
        Sk.cut(g, x, y, w, h, 16, 0xF4081018);
        Gfx.text(g, "LOCAȚII", x + 18, y + 32, 16f, Sk.WHITE, 0, true, 0.6f);
        Gfx.text(g, totalInDim + " total", x + w - 16, y + 30, 8f, Sk.MUTED, 2);
        Gfx.rrect(g, x + 16, y + 40, w - 32, 2, 0, Sk.ACC);
        Gfx.rrect(g, x + w - 16 - (w - 32) * 0.22f, y + 40, (w - 32) * 0.22f, 2, 0, VIOLET);

        // cautare
        float sy0 = y + 52;
        Sk.slant(g, x + 14, sy0, w - 28, 28, 4f, Sk.ROW);
        Gfx.rrect(g, x + 18, sy0 + 26, w - 36, 1.6f, 0, search.isFocused() ? Sk.ACC : 0xFF22344D);
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
        float cy = sy0 + 38;
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
            Sk.slant(g, cx, cy, cw, 21, 5f, sel ? Sk.ACC : hv ? 0xFF22344D : Sk.ROW);
            if ("FAV".equals(c)) star(g, cx + cw / 2f, cy + 10.5f, 6f, sel ? 0xFF061426 : 0xFFFFC857);
            else Gfx.text(g, label, cx + cw / 2f + 2, cy + 14.5f, 7.2f, sel ? 0xFF061426 : Sk.MUTED, 1, true, 0.5f);
            final String key = c;
            hitF(cx, cy, cw, 21, () -> {
                filter = key;
                scroll = 0;
                dirty = true;
            });
            cx += cw + 6f;
        }
        listTop = cy + 31f;

        // zona de jos: bara de pagini + detalii
        float pbH = 30f;
        float pbY = y + h - 14 - pbH;
        float dH = selected != null ? 100f : 0f;
        float dY = pbY - 8 - dH;
        listBottom = (selected != null ? dY : pbY) - 8f;
        rowsVisible = Math.max(1, (int) ((listBottom - listTop + 4f) / PITCH));
        clampScroll();

        // lista
        if (shown.isEmpty()) {
            String msg = !GpsData.loaded && GpsData.all().isEmpty() ? "Se încarcă locațiile…" : (totalInDim == 0 ? "Nicio locație configurată" : "Nicio locație găsită");
            Gfx.text(g, msg, x + w / 2f, listTop + 30, 8.6f, Sk.MUTED, 1);
            if (totalInDim == 0 && GpsData.loaded) Gfx.text(g, GpsData.admin ? "Adaugă din EDITOR HARTĂ → LOCAȚII" : "Adminul poate adăuga locații din editor", x + w / 2f, listTop + 46, 6.8f, Sk.FAINT, 1);
        }
        for (int i = 0; i < rowsVisible; i++) {
            int idx = scroll + i;
            if (idx >= shown.size()) break;
            GpsData.Loc l = shown.get(idx);
            float ry = listTop + i * PITCH;
            float appear = ease((System.currentTimeMillis() - born - i * 40L) / 260f);
            float rx = x + 14 + (1f - appear) * 18f;
            boolean sel = l == selected;
            boolean hv = Sk.in(mx, my, x + 14, ry, w - 28, ROW_H);
            Sk.slant(g, rx, ry, w - 28, ROW_H, 5f, sel ? 0xFF1B2E47 : hv ? Sk.ROW_HOT : Sk.ROW);
            if (sel) {
                Gfx.rrect(g, rx, ry + 3, 3, ROW_H - 6, 0, Sk.ACC);
                Gfx.rrect(g, rx + 5, ry + ROW_H - 1.5f, w - 38, 1.5f, 0, 0x884DA3FF);
            }
            GpsIcons.badge(g, l.icon, rx + 22, ry + ROW_H / 2f, 9f, l.color, appear, GpsNav.isTarget(l.id));
            double d = dist(l);
            String ds = d < 0 ? "-" : GpsNav.fmtDist(d);
            float dw = Gfx.width(ds, 7.4f, false);
            if (GpsPrefs.fav.contains(l.id)) star(g, x + w - 24 - dw - 12f, ry + ROW_H / 2f, 4.4f, 0xFFFFC857);
            Gfx.text(g, ds, x + w - 24, ry + ROW_H / 2f + 2.8f, 7.4f, sel ? Sk.WHITE : Sk.MUTED, 2);
            float tw = w - 28 - 44 - dw - 26;
            Gfx.text(g, clip(l.name, 8.8f, true, tw), rx + 38, ry + ROW_H / 2f + 3.2f, 8.8f, sel || hv ? Sk.WHITE : 0xFFD6E0EE, 0, true, 0f);
            final GpsData.Loc fl = l;
            hitF(x + 14, ry, w - 28, ROW_H, () -> {
                long now = System.currentTimeMillis();
                boolean dbl = fl.id.equals(lastClickId) && now - lastClickAt < 380;
                lastClickId = fl.id;
                lastClickAt = now;
                select(fl, true);
                if (dbl) activate();
            });
        }

        // detalii selectie
        if (selected != null) {
            GpsData.Loc s = selected;
            Sk.slant(g, x + 14, dY, w - 28, dH, 6f, 0xFF101C2E);
            Gfx.rrect(g, x + 14, dY + 6, 3, 46, 0, s.color);
            Gfx.text(g, clip(s.name, 10.5f, true, w - 86), x + 28, dY + 20, 10.5f, Sk.WHITE, 0, true, 0f);
            double d = dist(s);
            String sub = s.catName + (d >= 0 ? "  ·  " + GpsNav.fmtDist(d) : "");
            Gfx.text(g, clip(sub, 7.2f, false, w - 80), x + 28, dY + 32, 7.2f, Sk.MUTED, 0);
            String desc = s.desc.isEmpty() ? "" : s.desc;
            Gfx.text(g, clip(desc, 7.2f, false, w - 52), x + 28, dY + 44, 7.2f, 0xFF9CB4D0, 0);
            Gfx.text(g, "X: " + Math.round(s.x) + "   Z: " + Math.round(s.z), x + 28, dY + 56, 7.4f, Sk.FAINT, 0, true, 0.2f);
            if (!PIN.equals(s.id)) {
                boolean fav = GpsPrefs.fav.contains(s.id);
                float fx = x + w - 34;
                star(g, fx + 8, dY + 15, 7f, fav ? 0xFFFFC857 : 0xFF55667C);
                hitF(fx - 4, dY + 4, 24, 22, () -> toggleFav(s));
            }
            boolean target = GpsNav.isTarget(s.id);
            btn(g, target ? "ANULEAZĂ GPS" : "SETEAZĂ GPS", x + 24, dY + dH - 38, w - 48, 30, target ? 2 : 0, mx, my, this::activate);
        }

        // bara de pagini
        Sk.slant(g, x + 14, pbY, w - 28, pbH, 5f, Sk.ROW);
        String range = shown.isEmpty() ? "0 / 0" : (scroll + 1) + "-" + Math.min(scroll + rowsVisible, shown.size()) + " / " + shown.size();
        Gfx.text(g, range, x + 28, pbY + 19.5f, 9.4f, Sk.WHITE, 0, true, 0.3f);
        Gfx.text(g, "scroll", x + w - 84, pbY + 18.5f, 7.2f, Sk.FAINT, 2);
        float bx = x + w - 20 - 52;
        arrowBtn(g, bx, pbY + 3, 24, 24, true, mx, my, () -> {
            scroll -= rowsVisible;
            clampScroll();
        });
        arrowBtn(g, bx + 28, pbY + 3, 24, 24, false, mx, my, () -> {
            scroll += rowsVisible;
            clampScroll();
        });
    }

    private void arrowBtn(GuiGraphics g, float x, float y, float w, float h, boolean up, int mx, int my, Runnable r) {
        boolean hv = Sk.in(mx, my, x, y, w, h);
        Gfx.rrect(g, x, y, w, h, 3, hv ? 0xFF22344D : 0xFF14202F);
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

    @SuppressWarnings("unused")
    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
