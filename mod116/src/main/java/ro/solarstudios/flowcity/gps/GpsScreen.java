package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Interfata GPS FlowCity: harta tine tot ecranul, cu o bara neagra la dreapta in care locatiile serverului apar ca
 * butoane (cu paginare), un buton hamburger sus-stanga care deschide sertarul cu paginile (Acasa, Harta, Locatii,
 * Favorite, Rute recente, Setari), bannerul destinatiei sus si butonul de anulare a rutei jos-stanga.
 *
 * <p>Harta, traseul, waypoint-urile, zonele si rutarea pe drumuri raman cele existente (GpsMap, GpsNav, GpsWps,
 * GpsData, RoadNet); aici se schimba doar prezentarea si navigarea intre pagini. Totul e desenat din cod.
 */
public final class GpsScreen extends FlowScreen {
    static final float M = 14f;
    private static final String PIN = "_pin";

    private static final int P_HOME = 0;
    private static final int P_MAP = 1;
    private static final int P_LOC = 2;
    private static final int P_FAV = 3;
    private static final int P_ROUTES = 4;
    private static final int P_SETTINGS = 5;
    private static final int P_SEARCH = 6;
    private static final int PAGES = 7;

    private static final String[] NAV_ICON = {"home", "map", "pin", "star-line", "route", "gear"};
    private static final String[] NAV_NAME = {"Acasă", "Hartă", "Locații", "Favorite", "Rute recente", "Setări"};
    private static final String[] PAGE_SUB = {
        "Privire de ansamblu asupra navigației tale",
        "Explorează orașul și alege o destinație",
        "Toate punctele de interes de pe server",
        "Locurile pe care le-ai salvat",
        "Waypoint-urile tale și ultimele destinații",
        "Personalizează harta, navigarea și minimapul",
        "Rezultatele căutării"
    };

    /** Straturile hartii, comutabile independent (se retin local). */
    static final String[][] LAYERS = {
        {"locations", "Locații"}, {"legal", "Zone legale"}, {"illegal", "Zone ilegale"}, {"special", "Zone speciale"},
        {"police", "Poliție"}, {"hospital", "Spitale"}, {"shop", "Magazine"}, {"waypoints", "Waypoint-uri"}, {"other", "Alte categorii"},
        {"roads", "Rețea de drumuri"}
    };

    // ------------------------------------------------------------------ stare

    private int page = P_MAP;
    private int lastPage = P_MAP;
    private float pageAnim = 1f;
    private final float[] scroll = new float[PAGES];
    private float contentH;
    private float viewH;

    private double camX;
    private double camZ;
    private double tgtX;
    private double tgtZ;
    private boolean anim;
    private float zoom;
    private long lastNs;
    private final long born = System.currentTimeMillis();

    private final TextFieldWidget search = field("Caută o locație…", 40, s -> {
        dirty = true;
        if (!s.isEmpty() && page != P_SEARCH) go(P_SEARCH);
        scroll[P_SEARCH] = 0;
    });
    private boolean dirty = true;
    private final List<GpsData.Loc> shown = new ArrayList<>();
    private List<GpsData.Loc> all = new ArrayList<>();
    private GpsData.Loc selected;
    private float detail;
    private String catFilter = "";
    private boolean layersOpen;
    private long lastRefresh;
    private String note = "";
    private long noteUntil;

    // mouse
    private boolean pressMap;
    private boolean moved;
    private double pressX;
    private double pressY;

    // layout (recalculat la fiecare cadru)
    private float s = 1f;
    private float railW;
    private float headH;
    private float detailW;
    private float cx0;
    private float cy0;
    private float cx1;
    private float cy1;
    /** Zona in care se deseneaza harta (sub bara de locatii si sub sertar). */
    private float mx0;
    private float my0;
    private float mx1;
    private float my1;
    private float mapCx;
    private float mapCy;
    private boolean compact;
    private boolean detailOverlay;

    /** Bara neagra din dreapta cu locatiile serverului. */
    private float locBarW;
    private int locPage;
    private final List<GpsData.Loc> bar = new ArrayList<>();
    /** Sertarul din stanga (meniul vechi), deschis din butonul hamburger. */
    private boolean menuOpen;
    private float menuAnim;

    public GpsScreen() {
        super("GPS", 520, 290);
        GpsPrefs.load();
        Minecraft mc = Minecraft.getInstance();
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
        l.add(search);
        return l;
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        GpsPrefs.addSearch(search.getValue());
        if (!shown.isEmpty()) {
            List<GpsData.Loc> near = byDistance(shown);
            select(near.get(0), true);
            activate();
        }
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
        GpsPrefs.save();
        GpsMap.flush();
        super.removed();
    }

    // ------------------------------------------------------------------ actiuni

    private void go(int p) {
        if (p == page) return;
        lastPage = page;
        page = p;
        pageAnim = 0f;
        layersOpen = false;
    }

    void say(String text) {
        note = text;
        noteUntil = System.currentTimeMillis() + 2600;
    }

    private void select(GpsData.Loc l, boolean focus) {
        selected = l;
        if (l != null && focus) {
            tgtX = l.x;
            tgtZ = l.z;
            anim = true;
        }
    }

    private void activate() {
        if (selected == null) return;
        if (GpsNav.isTarget(selected.id)) {
            GpsNav.clear();
            say("Navigare anulată");
            return;
        }
        GpsNav.set(selected.id, selected.name, selected.x, selected.z, selected.dim, selected.color);
        if (GpsPrefs.pickSound) click();
        say("Destinație: " + selected.name);
    }

    private void navTo(String id, String name, double x, double z, String dim, int color) {
        if (page == P_SEARCH) GpsPrefs.addSearch(search.getValue());
        GpsNav.set(id, name, x, z, dim, color);
        if (GpsPrefs.pickSound) click();
        say("Destinație: " + name);
    }

    private void cancel() {
        GpsNav.clear();
        say("Navigare anulată");
    }

    private void toggleFav(GpsData.Loc l) {
        if (l == null || PIN.equals(l.id)) return;
        if (!GpsPrefs.fav.remove(l.id)) GpsPrefs.fav.add(l.id);
        GpsPrefs.save();
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
        float aw = Math.max(100f, cx1 - cx0 - 40f);
        float ah = Math.max(100f, cy1 - cy0 - 40f);
        zoom = Math.max(0.15f, Math.min(6f, Math.min(aw / Math.max(64f, w), ah / Math.max(64f, h))));
        tgtX = (b[0] + b[2]) / 2.0;
        tgtZ = (b[1] + b[3]) / 2.0;
        anim = true;
    }

    private void zoomBy(float factor, float px, float py) {
        double wx = camX + (px - mapCx) / zoom;
        double wz = camZ + (py - mapCy) / zoom;
        zoom = Math.max(0.15f, Math.min(6f, zoom * factor));
        camX = wx - (px - mapCx) / zoom;
        camZ = wz - (py - mapCy) / zoom;
        anim = false;
    }

    private void addWpHere(Minecraft mc, boolean shared) {
        if (mc.player == null) return;
        int n = (shared ? GpsWps.shared.size() : GpsWps.mine.size()) + 1;
        GpsWps.save("", (shared ? "Punct " : "Waypoint ") + n, Math.round(mc.player.getX()), Math.round(mc.player.getZ()),
            GpsData.dimOf(mc), shared ? GpsTheme.SKY : GpsTheme.GOLD, shared);
        say(shared ? "Waypoint partajat salvat" : "Waypoint salvat");
    }

    private void setPin(double px, double py) {
        GpsData.Loc p = new GpsData.Loc();
        p.id = PIN;
        p.name = "Punct personalizat";
        p.catName = "Marcaj";
        p.icon = "pin";
        p.color = GpsTheme.GOLD;
        p.desc = "Punct ales de tine pe hartă";
        p.dim = GpsData.dimOf(Minecraft.getInstance());
        p.x = Math.round(camX + (px - mapCx) / zoom);
        p.z = Math.round(camZ + (py - mapCy) / zoom);
        selected = p;
    }

    // ------------------------------------------------------------------ date

    private void rebuild() {
        dirty = false;
        Minecraft mc = Minecraft.getInstance();
        String dim = GpsData.dimOf(mc);
        all = GpsData.all();
        String q = GpsData.norm(search.getValue().trim());
        String[] toks = q.isEmpty() ? new String[0] : q.split("\\s+");
        shown.clear();
        for (GpsData.Loc l : all) {
            if (!l.dim.equals(dim) || l.hidden) continue;
            if (!GpsPrefs.layerOn("locations") || !GpsPrefs.layerOn(GpsData.locLayer(l))) continue;
            if (MiniMapSettings.hiddenCats.contains(l.cat)) continue;
            boolean ok = true;
            for (String t : toks) {
                if (!l.key.contains(t)) {
                    ok = false;
                    break;
                }
            }
            if (ok) shown.add(l);
        }
        bar.clear();
        bar.addAll(byDistance(shown));
        if (selected != null && !PIN.equals(selected.id)) {
            GpsData.Loc fresh = null;
            for (GpsData.Loc l : all) if (l.id.equals(selected.id)) fresh = l;
            selected = fresh;
        }
    }

    /** Locatiile vizibile, ordonate dupa distanta fata de jucator. */
    private List<GpsData.Loc> byDistance(List<GpsData.Loc> src) {
        Minecraft mc = Minecraft.getInstance();
        List<GpsData.Loc> out = new ArrayList<>(src);
        if (mc.player == null) return out;
        final double px = mc.player.getX();
        final double pz = mc.player.getZ();
        Collections.sort(out, (a, b) -> Double.compare(Math.hypot(a.x - px, a.z - pz), Math.hypot(b.x - px, b.z - pz)));
        return out;
    }

    private double dist(double x, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return -1;
        return Math.hypot(x - mc.player.getX(), z - mc.player.getZ());
    }

    private String distText(double x, double z) {
        double d = dist(x, z);
        return d < 0 ? "" : GpsNav.fmtDist(d);
    }

    private List<GpsData.Loc> favLocs() {
        String dim = GpsData.dimOf(Minecraft.getInstance());
        List<GpsData.Loc> out = new ArrayList<>();
        for (GpsData.Loc l : GpsData.all()) if (GpsPrefs.fav.contains(l.id) && l.dim.equals(dim) && !l.hidden) out.add(l);
        return byDistance(out);
    }

    /** Numarul de locatii vizibile pentru fiecare categorie, in ordinea categoriilor de pe server. */
    private Map<GpsData.Cat, Integer> catCounts() {
        String dim = GpsData.dimOf(Minecraft.getInstance());
        Map<String, Integer> n = new LinkedHashMap<>();
        for (GpsData.Loc l : GpsData.all()) {
            if (!l.dim.equals(dim) || l.hidden) continue;
            n.merge(l.cat, 1, Integer::sum);
        }
        Map<GpsData.Cat, Integer> out = new LinkedHashMap<>();
        for (GpsData.Cat c : GpsData.cats) {
            if (!c.enabled) continue;
            Integer k = n.get(c.id);
            if (k != null && k > 0) out.put(c, k);
        }
        return out;
    }

    private int acc() {
        return GpsPrefs.accent();
    }

    // ------------------------------------------------------------------ input

    private boolean overMap(double mx, double my) {
        if (menuOpen || mx < mx0 || mx >= mx1 || my < my0 || my >= my1) return false;
        return !(detail > 0.05f && mx > width - locBarW - detailW);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (super.mouseClicked(mx, my, button)) return true;
            if (layersOpen) {
                layersOpen = false;
                return true;
            }
            if (menuOpen && mx > cx1 && !(detail > 0.05f && mx > width - detailW)) {
                menuOpen = false;
                return true;
            }
            if (overMap(mx, my)) {
                pressMap = true;
                moved = false;
                pressX = mx;
                pressY = my;
            }
            return true;
        }
        if (button == 1 && overMap(mx, my)) {
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

    private void mapClick(float px, float py) {
        GpsData.Loc best = null;
        float bd = 15f * 15f;
        for (GpsData.Loc l : shown) {
            float dx = sx(l.x) - px;
            float dy = sy(l.z) - py;
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
        if (overMap(mx, my)) {
            zoomBy(delta > 0 ? 1.18f : 1f / 1.18f, (float) mx, (float) my);
        } else if (menuOpen && mx >= cx0 && mx <= cx1) {
            scroll[page] = Math.max(0f, Math.min(Math.max(0f, contentH - viewH), scroll[page] - (float) delta * 34f));
        } else if (!menuOpen && mx > mx1) {
            locPage += delta > 0 ? -1 : 1;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE && menuOpen && !search.isFocused()) {
            menuOpen = false;
            return true;
        }
        if (search.isFocused()) return super.keyPressed(key, scan, mods);
        if (GpsClient.key != null && GpsClient.key.matches(key, scan)) {
            onClose();
            return true;
        }
        switch (key) {
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                activate();
                return true;
            case GLFW.GLFW_KEY_SLASH:
                focus(search, true);
                return true;
            case GLFW.GLFW_KEY_C:
                centerOnPlayer();
                return true;
            case GLFW.GLFW_KEY_F:
                toggleFav(selected);
                return true;
            case GLFW.GLFW_KEY_M:
                menuOpen = false;
                go(P_MAP);
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

    static String clip(String str, float size, boolean xl, float maxW) {
        if (str == null) return "";
        if (Gfx.width(str, size, xl) <= maxW) return str;
        String t = str;
        while (t.length() > 1 && Gfx.width(t + "…", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    /** Dreptunghi rotunjit cu contur subtire (folosit si de editorul de harta). */
    static void box(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int edge, float bw) {
        Gfx.rrect(g, x - bw, y - bw, w + 2f * bw, h + 2f * bw, r + bw, edge);
        Gfx.rrect(g, x, y, w, h, r, fill);
    }

    static void box(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int edge) {
        box(g, x, y, w, h, r, fill, edge, 1f);
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        GpsUi.frame(dt);
        float t = (System.currentTimeMillis() - born) / 1000f;
        float open = GpsTheme.ease((System.currentTimeMillis() - born) / 340f);
        pageAnim = GpsTheme.to(pageAnim, 1f, dt, 11f);
        detail = GpsTheme.to(detail, selected != null ? 1f : 0f, dt, 12f);

        layout();
        if (search.isFocused() && page != P_SEARCH) go(P_SEARCH);
        if (anim) {
            camX += (tgtX - camX) * Math.min(1f, dt * 7f);
            camZ += (tgtZ - camZ) * Math.min(1f, dt * 7f);
            if (Math.abs(tgtX - camX) < 0.3 && Math.abs(tgtZ - camZ) < 0.3) anim = false;
        }
        if (dirty) rebuild();

        // fundal: harta tine tot ecranul
        Gfx.rrect(g, 0, 0, width, height, 0, GpsTheme.BG);
        drawMap(g, mc, mx, my, t, open);

        menuAnim = GpsTheme.to(menuAnim, menuOpen ? 1f : 0f, dt, 13f);
        if (menuAnim > 0.01f) drawDrawer(g, mc, mx, my);
        if (!menuOpen) {
            mapOverlays(g, mc, mx, my);
            drawLocBar(g, mx, my);
            drawTopBanner(g, mc);
            drawCancelGps(g, mx, my);
            drawMenuButton(g, mx, my);
            drawLayers(g, mx, my);
        }
        drawDetail(g, mx, my);
        drawNote(g);
    }

    /** Sertarul din stanga: bara de pagini plus pagina curenta, peste harta. */
    private void drawDrawer(GuiGraphics g, Minecraft mc, int mx, int my) {
        float slideX = (1f - menuAnim) * -(cx1 + 8f);
        Gfx.rrect(g, 0, 0, width, height, 0, GpsTheme.fade(GpsTheme.SCRIM, menuAnim * 0.86f));
        g.pose().pushPose();
        g.pose().translate(slideX, 0, 0);
        Gfx.rrect(g, 0, 0, cx1, height, 0, GpsTheme.BG);
        Gfx.rrect(g, cx1 - 1f, 0, 1f, height, 0, GpsTheme.LINE);

        viewH = cy1 - cy0 - GpsTheme.S4;
        float slide = (1f - pageAnim) * (page >= lastPage ? 18f : -18f);
        g.pose().pushPose();
        g.pose().translate(slide, 0, 0);
        Ui.scissor(Math.round(cx0 + slideX), Math.round(cy0), Math.round(Math.max(0f, cx1 - cx0)), Math.round(cy1 - cy0));
        switch (page) {
            case P_MAP:
            case P_LOC:
                pageLocations(g, mx, my);
                break;
            case P_FAV:
                pageFavorites(g, mx, my);
                break;
            case P_ROUTES:
                pageRoutes(g, mc, mx, my);
                break;
            case P_SETTINGS:
                pageSettings(g, mc, mx, my);
                break;
            case P_SEARCH:
                pageSearch(g, mx, my, (System.currentTimeMillis() - born) / 1000f);
                break;
            default:
                pageHome(g, mc, mx, my, (System.currentTimeMillis() - born) / 1000f);
                break;
        }
        Ui.endScissor();
        g.pose().popPose();
        GpsUi.scrollbar(g, cx1 - 6f, cy0 + GpsTheme.S2, cy1 - cy0 - GpsTheme.S4, contentH, viewH, scroll[page]);
        scroll[page] = Math.max(0f, Math.min(Math.max(0f, contentH - viewH), scroll[page]));

        drawRail(g, mc, mx, my);
        drawHeader(g, mc, mx, my);
        g.pose().popPose();
    }


    private void layout() {
        s = GpsPrefs.uiScale;
        compact = width < 620;
        railW = compact ? 54f * s : Math.max(150f, Math.min(186f, width * 0.195f)) * s;
        headH = 52f * s;
        detailW = Math.max(190f, Math.min(286f, width * 0.27f)) * s;
        locBarW = Math.max(150f, Math.min(230f, width * 0.19f)) * s;

        // harta e fundalul ecranului: tine tot ce ramane la stanga barei de locatii
        mx0 = 0f;
        my0 = 0f;
        mx1 = width - locBarW;
        my1 = height;
        mapCx = (mx1 - mx0) / 2f;
        mapCy = (my1 - my0) / 2f;

        // sertarul (paginile vechi) se asaza peste harta, din stanga
        float panelW = Math.max(240f, Math.min(360f, width * 0.33f)) * s;
        panelW = Math.min(panelW, Math.max(200f, width - railW - locBarW - 20f));
        detailW = Math.min(detailW, Math.max(190f, width - railW - 40f));
        detailOverlay = width - railW - panelW - detailW < 60f;
        cx0 = railW;
        cy0 = headH;
        cx1 = railW + panelW;
        cy1 = height;
    }

    // ------------------------------------------------------------------ harta

    private void drawMap(GuiGraphics g, Minecraft mc, int mx, int my, float t, float open) {
        float mw = mx1 - mx0;
        Ui.scissor(Math.round(mx0), Math.round(my0), Math.round(mw), Math.round(my1 - my0));
        Gfx.rrect(g, mx0, my0, mw, my1 - my0, 0, 0xFF0A0D13);
        GpsMap.draw(g, camX, camZ, zoom, mapCx, mapCy, width, height, open);
        if (GpsPrefs.grid) drawGrid(g);
        if (GpsPrefs.showZones != null ? GpsPrefs.showZones : GpsData.showZonesDefault) drawZones(g);
        if (GpsPrefs.layerOn("roads") && RoadNet.hasRoads()) {
            RoadDraw.network(g, RoadNet.live, camX, camZ, zoom, mapCx, mapCy, width, height,
                0x7F8FA3BD, Math.max(1.2f, Math.min(3f, zoom * 1.4f)), zoom >= 0.9f, null);
            RoadDraw.names(g, RoadNet.live, camX, camZ, zoom, mapCx, mapCy, width, height);
        }
        drawRoute(g, mc, t);
        GpsData.Loc hover = drawMarkers(g, mc, mx, my, t);
        drawPlayer(g, mc, t);
        Ui.endScissor();
        if (hover != null && GpsUi.in(mx, my, mx0, my0, mx1 - mx0, my1 - my0)) tooltip(g, hover, mx, my);
    }

    private void drawGrid(GuiGraphics g) {
        int stepW = zoom >= 2.4f ? 16 : (zoom >= 1f ? 64 : 256);
        int c = GpsTheme.alpha(0xFFFFFF, 10);
        double x0 = Math.floor((camX - (mapCx - mx0) / zoom) / stepW) * stepW;
        for (double x = x0; sx(x) < mx1 + stepW; x += stepW) {
            float px = sx(x);
            if (px >= mx0 && px <= mx1) Gfx.rrect(g, px, my0, 1f, my1 - my0, 0, c);
        }
        double z0 = Math.floor((camZ - (mapCy - my0) / zoom) / stepW) * stepW;
        for (double z = z0; sy(z) < my1 + stepW; z += stepW) {
            float py = sy(z);
            if (py >= my0 && py <= my1) Gfx.rrect(g, mx0, py, mx1 - mx0, 1f, 0, c);
        }
    }

    private void drawZones(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        String dim = GpsData.dimOf(mc);
        for (GpsData.Zone z : GpsData.zones) {
            if (!z.enabled || !z.dim.equals(dim) || z.tris.length < 6) continue;
            if (!GpsPrefs.layerOn(GpsData.zoneLayer(z))) continue;
            if (sx(z.maxX) < mx0 || sx(z.minX) > mx1 || sy(z.maxZ) < my0 || sy(z.minZ) > my1) continue;
            float[] tri = new float[6];
            for (int i = 0; i + 5 < z.tris.length; i += 6) {
                for (int k = 0; k < 3; k++) {
                    tri[k * 2] = sx(z.tris[i + k * 2]);
                    tri[k * 2 + 1] = sy(z.tris[i + k * 2 + 1]);
                }
                Gfx.poly(g, tri, z.color);
            }
            for (int i = 0; i + 1 < z.px.length; i++) {
                Gfx.line(g, sx(z.px[i]), sy(z.pz[i]), sx(z.px[i + 1]), sy(z.pz[i + 1]), 1.6f, z.outline);
            }
            if (z.px.length > 1) {
                Gfx.line(g, sx(z.px[z.px.length - 1]), sy(z.pz[z.pz.length - 1]), sx(z.px[0]), sy(z.pz[0]), 1.6f, z.outline);
            }
            if (zoom >= 0.55f) {
                float lx = sx((z.minX + z.maxX) / 2f);
                float ly = sy((z.minZ + z.maxZ) / 2f);
                if (lx > mx0 && lx < mx1 && ly > my0 && ly < my1) {
                    Gfx.textS(g, z.name, lx, ly, 7.6f, GpsTheme.alpha(z.outline, 220), 1, true, 0.4f);
                }
            }
        }
    }

    private void drawRoute(GuiGraphics g, Minecraft mc, float t) {
        if (!GpsNav.active || !GpsNav.dim.equals(GpsData.dimOf(mc))) return;
        List<double[]> path = GpsNav.route.path(mc.player.getX(), mc.player.getZ(), GpsNav.x, GpsNav.z);
        int rc = GpsPrefs.routeColor();
        int glow = GpsTheme.alpha(rc, 70);
        for (int i = 0; i + 1 < path.size(); i++) {
            float ax = sx(path.get(i)[0]);
            float ay = sy(path.get(i)[1]);
            float bx = sx(path.get(i + 1)[0]);
            float by = sy(path.get(i + 1)[1]);
            if ((ax < mx0 - 40 && bx < mx0 - 40) || (ax > mx1 + 40 && bx > mx1 + 40)) continue;
            if ((ay < my0 - 40 && by < my0 - 40) || (ay > my1 + 40 && by > my1 + 40)) continue;
            Gfx.line(g, ax, ay, bx, by, 9f, glow);
            Gfx.line(g, ax, ay, bx, by, 5.5f, rc);
            float len = (float) Math.hypot(bx - ax, by - ay);
            if (len < 1f) continue;
            float ux = (bx - ax) / len;
            float uy = (by - ay) / len;
            float off = (t * 36f) % 18f;
            int n = 0;
            for (float d = -off; d < len && n < 400; d += 18f, n++) {
                float d0 = Math.max(0f, d);
                float d1 = Math.min(len, d + 10f);
                if (d1 <= d0) continue;
                Gfx.line(g, ax + ux * d0, ay + uy * d0, ax + ux * d1, ay + uy * d1, 2.6f, GpsTheme.mix(rc, 0xFFFFFFFF, 0.72f));
            }
        }
        if (GpsNav.route.roads()) {
            for (RoadRouteProvider.Step st : RoadRouteProvider.I.steps()) {
                if (st.turn == 9) continue;
                float x = sx(st.x);
                float y = sy(st.z);
                if (x < mx0 || y < my0 || x > mx1 || y > my1) continue;
                Gfx.disc(g, x, y, 4.2f, 0xFF0B0E14);
                Gfx.disc(g, x, y, 2.8f, GpsTheme.mix(rc, 0xFFFFFFFF, 0.6f));
            }
        }
    }

    private GpsData.Loc drawMarkers(GuiGraphics g, Minecraft mc, int mx, int my, float t) {
        float u0 = Math.max(4.8f, Math.min(6.8f, 3.9f + zoom * 1.3f));
        GpsData.Loc hover = null;
        float bd = 15f * 15f;
        boolean onMap = !menuOpen && GpsUi.in(mx, my, mx0, my0, mx1 - mx0, my1 - my0);
        for (GpsData.Loc l : shown) {
            float x = sx(l.x);
            float y = sy(l.z);
            if (x < mx0 - 20 || y < my0 - 20 || x > mx1 + 20 || y > my1 + 20) continue;
            if (onMap) {
                float d = (x - mx) * (x - mx) + (y - my) * (y - my);
                if (d < bd) {
                    bd = d;
                    hover = l;
                }
            }
        }
        GpsData.Loc sel = selected;
        for (GpsData.Loc l : shown) {
            if (l == sel) continue;
            float x = sx(l.x);
            float y = sy(l.z);
            if (x < mx0 - 20 || y < my0 - 20 || x > mx1 + 20 || y > my1 + 20) continue;
            boolean hv = l == hover;
            float u = u0 * l.size / 100f * (hv ? 1.16f : 1f);
            GpsIcons.pin(g, l.icon, x, y, u, l.color, 1f, GpsNav.isTarget(l.id));
            if (zoom >= 1.5f || hv) Gfx.textS(g, clip(l.name, 7.2f, true, 110f), x, y + u * 1.4f + 8f, 7.2f, 0xFFEFF2F7, 1, true, 0.3f);
        }
        if (GpsPrefs.layerOn("waypoints")) {
            String wdim = GpsData.dimOf(mc);
            for (GpsWps.Wp w : GpsWps.all()) {
                if (!w.dim.equals(wdim) || GpsNav.isTarget("wp:" + w.id)) continue;
                float x = sx(w.x);
                float y = sy(w.z);
                if (x < mx0 - 20 || y < my0 - 20 || x > mx1 + 20 || y > my1 + 20) continue;
                GpsIcons.pin(g, "pin", x, y, u0 * 0.95f, w.color, 1f, false);
                if (zoom >= 1.2f) Gfx.textS(g, clip(w.name, 7f, true, 110f), x, y + u0 * 1.3f + 8f, 7f, 0xFFEFF2F7, 1, true, 0.3f);
            }
        }
        if (sel != null) {
            float x = sx(sel.x);
            float y = sy(sel.z);
            float ph = (t % 1.6f) / 1.6f;
            Gfx.ring(g, x, y, u0 * 1.4f + ph * 12f, u0 * 1.4f + 1.4f + ph * 12f,
                GpsTheme.fade(sel.color, 0.75f * (1f - ph)), GpsTheme.fade(sel.color, 0.75f * (1f - ph)));
            GpsIcons.pin(g, sel.icon, x, y, u0 * 1.25f, sel.color, 1f, true);
            Gfx.textS(g, clip(sel.name, 8f, true, 150f), x, y + u0 * 1.7f + 9f, 8f, 0xFFFFFFFF, 1, true, 0.3f);
        }
        if (GpsNav.active && GpsNav.dim.equals(GpsData.dimOf(mc))) {
            float x = sx(GpsNav.x);
            float y = sy(GpsNav.z);
            boolean listed = false;
            for (GpsData.Loc l : shown) if (l.id.equals(GpsNav.id)) listed = true;
            if (!listed && (sel == null || !sel.id.equals(GpsNav.id))) GpsIcons.pin(g, "pin", x, y, u0 * 1.1f, GpsNav.color, 1f, true);
            if (x < mx0 + 18 || y < my0 + 18 || x > mx1 - 18 || y > my1 - 18) {
                float ex = Math.max(mx0 + 24f, Math.min(mx1 - 24f, x));
                float ey = Math.max(my0 + 24f, Math.min(my1 - 24f, y));
                double ang = Math.atan2(x - mapCx, -(y - mapCy));
                GpsIcons.arrow(g, ex, ey, 10f, ang, GpsNav.color, 0xFF0B0E14);
            }
        }
        return hover;
    }

    private void drawPlayer(GuiGraphics g, Minecraft mc, float t) {
        float x = sx(mc.player.getX());
        float y = sy(mc.player.getZ());
        if (x < mx0 - 20 || y < my0 - 20 || x > mx1 + 20 || y > my1 + 20) return;
        float ph = (t % 2f) / 2f;
        int c = acc();
        Gfx.ring(g, x, y, 7f + ph * 11f, 7.9f + ph * 11f, GpsTheme.fade(c, 0.65f * (1f - ph)), GpsTheme.fade(c, 0.65f * (1f - ph)));
        Gfx.disc(g, x, y, 8.5f, 0x99000000);
        double ang = Math.PI + Math.toRadians(mc.player.yRot);
        GpsIcons.arrow(g, x, y, 7.5f, ang, 0xFFFFFFFF, c);
    }

    private void tooltip(GuiGraphics g, GpsData.Loc l, int mx, int my) {
        double d = dist(l.x, l.z);
        String line = l.catName + (d >= 0 && GpsPrefs.showDist ? "  ·  " + GpsNav.fmtDist(d) : "");
        float w = Math.max(Gfx.width(l.name, 9f, true), Gfx.width(line, 7.4f, true)) + 26f;
        float x = Math.min(mx + 14f, width - w - 6f);
        float y = Math.min(my + 14f, height - 46f);
        GpsTheme.glass(g, x, y, w, 38f, GpsTheme.R_SM);
        Gfx.rrect(g, x + 7f, y + 9f, 3f, 20f, 1.5f, l.color);
        Gfx.text(g, l.name, x + 14f, y + 16f, 9f, GpsTheme.TEXT, 0, true, 0f);
        Gfx.text(g, line, x + 14f, y + 29f, 7.4f, GpsTheme.MUTED, 0, true, 0f);
    }

    // ------------------------------------------------------------------ meniu lateral

    private void drawRail(GuiGraphics g, Minecraft mc, int mx, int my) {
        Gfx.rrect(g, 0, 0, railW, height, 0, GpsTheme.BG2);
        Gfx.rrect(g, railW - 1f, 0, 1f, height, 0, GpsTheme.LINE);
        int c = acc();

        // logo
        float lx = compact ? railW / 2f - 9f : 16f * s;
        Gfx.rrect(g, lx, 16f * s, 18f * s, 18f * s, 5f * s, c, GpsTheme.mix(c, 0xFF000000, 0.28f));
        GpsUi.icon(g, "nav", lx + 9f * s, 25f * s, 11f * s, 0xFFFFFFFF);
        if (!compact) {
            Gfx.text(g, "FLOWCITY", lx + 26f * s, 22f * s, 9.4f * s, GpsTheme.TEXT, 0, true, 1.5f);
            Gfx.text(g, "GPS", lx + 26f * s, 32f * s, 7.4f * s, c, 0, true, 2.4f);
        }

        float y = 56f * s;
        float itemH = 34f * s;
        for (int i = 0; i < NAV_NAME.length; i++) {
            boolean active = page == i || (i == P_LOC && page == P_SEARCH);
            boolean hov = GpsUi.in(mx, my, 6f, y, railW - 12f, itemH);
            float t = GpsUi.hot("nav" + i, hov);
            if (active) {
                Gfx.rrect(g, 6f, y, railW - 12f, itemH, GpsTheme.R_SM, GpsTheme.alpha(c, 38));
                Gfx.rrect(g, 6f, y + itemH * 0.22f, 2.6f, itemH * 0.56f, 1.3f, c);
            } else if (t > 0.01f) {
                Gfx.rrect(g, 6f, y, railW - 12f, itemH, GpsTheme.R_SM, GpsTheme.alpha(0xFFFFFF, (int) (16 * t)));
            }
            int fg = active ? GpsTheme.TEXT : GpsTheme.mix(GpsTheme.MUTED, GpsTheme.TEXT, t);
            float ix = compact ? railW / 2f : 24f * s;
            GpsUi.icon(g, NAV_ICON[i], ix, y + itemH / 2f, 15f * s, active ? c : fg);
            if (!compact) Gfx.text(g, NAV_NAME[i], 42f * s, y + itemH / 2f + 3.4f * s, 9.2f * s, fg, 0, true, 0.1f);
            final int pi = i;
            hitF(6f, y, railW - 12f, itemH, () -> {
                if (pi == P_MAP) {
                    menuOpen = false;
                    go(P_MAP);
                    return;
                }
                if (pi == P_LOC) catFilter = "";
                go(pi);
            });
            // numarul de favorite, ca pastila discreta
            if (i == P_FAV && !GpsPrefs.fav.isEmpty() && !compact) {
                String n = String.valueOf(GpsPrefs.fav.size());
                float bw = Gfx.width(n, 7f * s, true) + 11f * s;
                Gfx.rrect(g, railW - 14f - bw, y + itemH / 2f - 7f * s, bw, 14f * s, 7f * s, GpsTheme.alpha(0xFFFFFF, 18));
                Gfx.text(g, n, railW - 14f - bw / 2f, y + itemH / 2f + 2.6f * s, 7f * s, GpsTheme.MUTED, 1, true, 0f);
            }
            y += itemH + 2f * s;
        }

        // jos: pozitia jucatorului si scurtaturi
        float by = height - 14f * s;
        if (GpsData.admin) {
            by -= 30f * s;
            boolean hv = GpsUi.button(g, "edt", compact ? "" : "Editor hartă", "pencil", 8f, by, railW - 16f, 26f * s, GpsUi.GHOST, true, mx, my);
            hitF(8f, by, railW - 16f, 26f * s, () -> mc.setScreen(new MapEditorScreen(camX, camZ, zoom)));
            if (hv && compact) tip(g, "Editor hartă", railW + 6f, by);
        }
        by -= 30f * s;
        boolean hvm = GpsUi.button(g, "mnm", compact ? "" : "Minimap", "layers", 8f, by, railW - 16f, 26f * s, GpsUi.GHOST, true, mx, my);
        hitF(8f, by, railW - 16f, 26f * s, () -> mc.setScreen(new MiniMapSettingsScreen()));
        if (hvm && compact) tip(g, "Setări minimap", railW + 6f, by);

        if (!compact && GpsPrefs.showCoords) {
            by -= 44f * s;
            GpsTheme.panel(g, 8f, by, railW - 16f, 38f * s, GpsTheme.R_SM, GpsTheme.PANEL, GpsTheme.LINE);
            GpsTheme.caption(g, "POZIȚIA TA", 16f, by + 14f * s, GpsTheme.FAINT);
            Gfx.text(g, Math.round(mc.player.getX()) + ", " + Math.round(mc.player.getZ()),
                16f, by + 28f * s, 8.6f * s, GpsTheme.MUTED, 0, true, 0.2f);
        }
    }

    /** Eticheta mica langa un buton din bara ingusta. */
    private void tip(GuiGraphics g, String text, float x, float y) {
        float w = Gfx.width(text, GpsTheme.T_SMALL, true) + 16f;
        GpsTheme.glass(g, x, y, w, 22f, GpsTheme.R_SM);
        Gfx.text(g, text, x + 8f, y + 14.5f, GpsTheme.T_SMALL, GpsTheme.TEXT, 0, true, 0f);
    }

    // ------------------------------------------------------------------ antet

    private void drawHeader(GuiGraphics g, Minecraft mc, int mx, int my) {
        Gfx.rrect(g, railW, 0, cx1 - railW, headH, 0, GpsTheme.alpha(GpsTheme.BG, 236));
        Gfx.rrect(g, railW, headH - 1f, cx1 - railW, 1f, 0, GpsTheme.LINE);
        float x = railW + GpsTheme.S4;
        String title = page == P_SEARCH ? "Căutare" : NAV_NAME[page];
        Gfx.text(g, title, x, 22f * s, 13.6f * s, GpsTheme.TEXT, 0, true, 0.2f);
        float subW = cx1 - railW - 340f * s;
        if (subW > 70f) Gfx.text(g, clip(PAGE_SUB[page], 7.6f * s, true, subW), x, 36f * s, 7.6f * s, GpsTheme.MUTED, 0, true, 0f);

        // inchidere
        float cs = 28f * s;
        float cxb = cx1 - GpsTheme.S3 - cs;
        float cyb = (headH - cs) / 2f;
        boolean hc = GpsUi.in(mx, my, cxb, cyb, cs, cs);
        float ht = GpsUi.hot("close", hc);
        Gfx.rrect(g, cxb, cyb, cs, cs, GpsTheme.R_SM, GpsTheme.mix(0x00000000, GpsTheme.alpha(GpsTheme.DANGER, 52), ht));
        GpsUi.icon(g, "close", cxb + cs / 2f, cyb + cs / 2f, cs * 0.44f, GpsTheme.mix(GpsTheme.MUTED, 0xFFFFFFFF, ht));
        hitF(cxb, cyb, cs, cs, () -> menuOpen = false);

        // coordonate
        float right = cxb - GpsTheme.S2;
        if (false) {
            String co = Math.round(mc.player.getX()) + "  ·  " + Math.round(mc.player.getZ());
            float w = Gfx.width(co, 8.4f * s, true) + 42f * s;
            float hx = right - w;
            GpsTheme.panel(g, hx, (headH - 28f * s) / 2f, w, 28f * s, 14f * s, GpsTheme.PANEL, GpsTheme.LINE);
            GpsUi.icon(g, "target", hx + 15f * s, headH / 2f, 12f * s, acc());
            Gfx.text(g, co, hx + 27f * s, headH / 2f + 3f * s, 8.4f * s, GpsTheme.MUTED, 0, true, 0.3f);
            right = hx - GpsTheme.S2;
        }

        // cautare
        float sw = Math.min(238f * s, Math.max(120f, right - x - Gfx.width(title, 13.6f * s, true) - 40f));
        float sxp = right - sw;
        float syp = (headH - 28f * s) / 2f;
        boolean focused = search.isFocused();
        float ft = GpsUi.hot("searchbox", focused || GpsUi.in(mx, my, sxp, syp, sw, 28f * s));
        GpsTheme.panel(g, sxp, syp, sw, 28f * s, 14f * s, GpsTheme.mix(GpsTheme.PANEL, GpsTheme.PANEL_HI, ft),
            focused ? acc() : GpsTheme.mix(GpsTheme.LINE, 0xFF4A5261, ft));
        GpsUi.icon(g, "search", sxp + 15f * s, headH / 2f, 12f * s, focused ? acc() : GpsTheme.MUTED);
        search.x = Math.round(sxp + 27f * s);
        search.y = Math.round(headH / 2f - 4f);
        search.setWidth(Math.round(sw - 36f * s));
        search.setTextColor(0xFFFFFF);
        search.render(g.pose(), mx, my, 0);
        if (!search.getValue().isEmpty()) {
            float bx = sxp + sw - 20f * s;
            boolean hx2 = GpsUi.in(mx, my, bx - 8f, syp, 20f * s, 28f * s);
            GpsUi.icon(g, "close", bx, headH / 2f, 9f * s, hx2 ? GpsTheme.TEXT : GpsTheme.FAINT);
            hitF(bx - 8f, syp, 20f * s, 28f * s, () -> {
                search.setValue("");
                dirty = true;
                go(lastPage == P_SEARCH ? P_LOC : lastPage);
            });
        }
    }

    // ------------------------------------------------------------------ pagini: ajutoare

    private float py0() {
        return cy0 + GpsTheme.S3 - scroll[page];
    }

    private float pad() {
        return GpsTheme.S4;
    }

    private float pw() {
        return cx1 - cx0 - pad() * 2f - 6f;
    }

    private float sectionHeader(GuiGraphics g, String caption, float y, String action, Runnable run, int mx, int my) {
        GpsTheme.caption(g, caption, cx0 + pad(), y + 8f, GpsTheme.FAINT);
        if (action != null) {
            float w = Gfx.width(action, GpsTheme.T_SMALL, true) + 10f;
            float x = cx0 + pad() + pw() - w;
            boolean hv = GpsUi.in(mx, my, x, y - 4f, w, 20f);
            Gfx.text(g, action, x + 5f, y + 8.4f, GpsTheme.T_SMALL, hv ? GpsTheme.TEXT : GpsTheme.mix(GpsTheme.MUTED, acc(), 0.5f), 0, true, 0f);
            if (run != null) hitF(x, y - 4f, w, 20f, run);
        }
        return y + 18f;
    }

    /**
     * Card de locatie: pastila cu iconita, nume, categorie si distanta, stea si buton de navigare.
     * Intoarce inaltimea folosita.
     */
    private float locCard(GuiGraphics g, GpsData.Loc l, float x, float y, float w, int mx, int my) {
        float h = 50f * s;
        boolean hv = GpsUi.in(mx, my, x, y, w, h) && my > cy0 && my < cy1;
        float t = GpsUi.hot("loc" + l.id, hv);
        boolean target = GpsNav.isTarget(l.id);
        GpsTheme.card(g, x, y, w, h, GpsTheme.R_MD, t);
        if (target) {
            Gfx.rrect(g, x, y, w, h, GpsTheme.R_MD, GpsTheme.alpha(acc(), 120));
            Gfx.rrect(g, x + 1f, y + 1f, w - 2f, h - 2f, GpsTheme.R_MD - 1f, GpsTheme.mix(GpsTheme.BG2, GpsTheme.alpha(acc(), 60), 0.6f));
        }
        GpsUi.badge(g, l.icon, x + 24f * s, y + h / 2f, 15f * s, l.color);
        float tx = x + 44f * s;
        float tw = w - 44f * s - 74f * s;
        Gfx.text(g, clip(l.name, 9.6f * s, true, tw), tx, y + 21f * s, 9.6f * s, GpsTheme.TEXT, 0, true, 0f);
        String sub = l.catName;
        if (GpsPrefs.showDist) {
            String d = distText(l.x, l.z);
            if (!d.isEmpty()) sub = sub + "  ·  " + d;
        }
        Gfx.text(g, clip(sub, 7.6f * s, true, tw), tx, y + 34f * s, 7.6f * s, GpsTheme.MUTED, 0, true, 0f);

        // stea
        float bs = 26f * s;
        float sxb = x + w - bs * 2f - 14f * s;
        boolean fav = GpsPrefs.fav.contains(l.id);
        boolean hs2 = GpsUi.in(mx, my, sxb, y + (h - bs) / 2f, bs, bs);
        float st = GpsUi.hot("fav" + l.id, hs2);
        if (st > 0.01f) Gfx.rrect(g, sxb, y + (h - bs) / 2f, bs, bs, GpsTheme.R_SM, GpsTheme.alpha(0xFFFFFF, (int) (18 * st)));
        if (fav) GpsUi.starShape(g, sxb + bs / 2f, y + h / 2f, 7f * s, 3.1f * s, GpsTheme.GOLD);
        else GpsUi.icon(g, "star-line", sxb + bs / 2f, y + h / 2f, 14f * s, GpsTheme.mix(GpsTheme.FAINT, GpsTheme.GOLD, st));
        hitF(sxb, y + (h - bs) / 2f, bs, bs, () -> toggleFav(l));

        // navigare
        float gx = x + w - bs - 10f * s;
        boolean hg = GpsUi.in(mx, my, gx, y + (h - bs) / 2f, bs, bs);
        float gt = GpsUi.hot("go" + l.id, hg);
        Gfx.rrect(g, gx, y + (h - bs) / 2f, bs, bs, GpsTheme.R_SM,
            target ? GpsTheme.alpha(GpsTheme.DANGER, 60) : GpsTheme.mix(GpsTheme.alpha(acc(), 40), acc(), gt));
        GpsUi.icon(g, target ? "close" : "nav", gx + bs / 2f, y + h / 2f, 12f * s, target ? GpsTheme.DANGER : 0xFFFFFFFF);
        hitF(gx, y + (h - bs) / 2f, bs, bs, () -> {
            if (target) {
                cancel();
            } else {
                select(l, false);
                navTo(l.id, l.name, l.x, l.z, l.dim, l.color);
            }
        });

        hitF(x, y, w - bs * 2f - 20f * s, h, () -> {
            select(l, true);
            if (page != P_MAP && !compact) go(P_MAP);
        });
        return h;
    }

    /** Rand simplu cu iconita, titlu, subtitlu si pana la doua actiuni. */
    private float row(GuiGraphics g, String id, String ic, int color, String title, String sub, float x, float y, float w,
                      int mx, int my, Runnable click, String actIcon, Runnable act, String act2Icon, Runnable act2) {
        float h = 46f * s;
        boolean hv = GpsUi.in(mx, my, x, y, w, h) && my > cy0 && my < cy1;
        float t = GpsUi.hot(id, hv);
        GpsTheme.card(g, x, y, w, h, GpsTheme.R_MD, t);
        GpsUi.badge(g, ic, x + 23f * s, y + h / 2f, 14f * s, color);
        int acts = (act == null ? 0 : 1) + (act2 == null ? 0 : 1);
        float tw = w - 42f * s - acts * 30f * s - 12f;
        Gfx.text(g, clip(title, 9.2f * s, true, tw), x + 42f * s, y + (sub.isEmpty() ? h / 2f + 3.2f * s : 20f * s), 9.2f * s, GpsTheme.TEXT, 0, true, 0f);
        if (!sub.isEmpty()) Gfx.text(g, clip(sub, 7.4f * s, true, tw), x + 42f * s, y + 32f * s, 7.4f * s, GpsTheme.MUTED, 0, true, 0f);
        float bx = x + w - 10f * s;
        if (act2 != null) {
            bx -= 26f * s;
            iconAction(g, id + "a2", act2Icon, bx, y + (h - 26f * s) / 2f, 26f * s, mx, my, act2);
            bx -= 4f * s;
        }
        if (act != null) {
            bx -= 26f * s;
            iconAction(g, id + "a1", actIcon, bx, y + (h - 26f * s) / 2f, 26f * s, mx, my, act);
        }
        if (click != null) hitF(x, y, w - (acts * 30f * s), h, click);
        return h;
    }

    private void iconAction(GuiGraphics g, String id, String ic, float x, float y, float sz, int mx, int my, Runnable r) {
        boolean hv = GpsUi.in(mx, my, x, y, sz, sz);
        float t = GpsUi.hot(id, hv);
        boolean danger = "trash".equals(ic) || "close".equals(ic);
        Gfx.rrect(g, x, y, sz, sz, GpsTheme.R_SM, danger
            ? GpsTheme.mix(GpsTheme.alpha(GpsTheme.DANGER, 26), GpsTheme.alpha(GpsTheme.DANGER, 80), t)
            : GpsTheme.mix(GpsTheme.alpha(acc(), 32), acc(), t));
        GpsUi.icon(g, ic, x + sz / 2f, y + sz / 2f, sz * 0.46f, danger ? GpsTheme.mix(0xFFF0A3A3, 0xFFFFFFFF, t) : 0xFFFFFFFF);
        hitF(x, y, sz, sz, r);
    }

    // ------------------------------------------------------------------ pagina ACASA

    private void pageHome(GuiGraphics g, Minecraft mc, int mx, int my, float t) {
        float x = cx0 + pad();
        float w = pw();
        float y = py0();

        // destinatia curenta
        float ch = 92f * s;
        GpsTheme.card(g, x, y, w, ch, GpsTheme.R_LG, 0f);
        if (GpsNav.active) {
            double d = GpsNav.travelDistance(mc);
            Gfx.rrect(g, x, y, 3f, ch, 1.5f, GpsNav.color);
            GpsTheme.caption(g, "DESTINAȚIE ACTIVĂ", x + 18f * s, y + 22f * s, acc());
            Gfx.text(g, clip(GpsNav.name, 14f * s, true, w - 150f * s), x + 18f * s, y + 44f * s, 14f * s, GpsTheme.TEXT, 0, true, 0f);
            String info = d < 0 ? "în altă lume" : GpsNav.fmtDist(d) + "   ·   ~" + GpsNav.fmtEta(GpsNav.etaSeconds(mc));
            Gfx.text(g, info, x + 18f * s, y + 60f * s, 8.6f * s, GpsTheme.MUTED, 0, true, 0.2f);
            Gfx.text(g, RoadRouteProvider.I.statusText(), x + 18f * s, y + 76f * s, 7.4f * s, GpsTheme.FAINT, 0, true, 0f);
            float bw = 96f * s;
            GpsUi.button(g, "hgo", "Pe hartă", "map", x + w - bw * 2f - 22f * s, y + ch - 40f * s, bw, 28f * s, GpsUi.SOFT, true, mx, my);
            hitF(x + w - bw * 2f - 22f * s, y + ch - 40f * s, bw, 28f * s, () -> {
                tgtX = GpsNav.x;
                tgtZ = GpsNav.z;
                anim = true;
                go(P_MAP);
            });
            GpsUi.button(g, "hcan", "Anulează", "close", x + w - bw - 14f * s, y + ch - 40f * s, bw, 28f * s, GpsUi.DANGER, true, mx, my);
            hitF(x + w - bw - 14f * s, y + ch - 40f * s, bw, 28f * s, this::cancel);
        } else {
            GpsUi.icon(g, "nav", x + 34f * s, y + ch / 2f, 26f * s, GpsTheme.alpha(acc(), 150));
            Gfx.text(g, "Nicio destinație activă", x + 62f * s, y + 42f * s, 11.6f * s, GpsTheme.TEXT, 0, true, 0f);
            Gfx.text(g, "Caută un loc sau alege unul din Locații.", x + 62f * s, y + 58f * s, 8.2f * s, GpsTheme.MUTED, 0, true, 0f);
            float bw = 112f * s;
            GpsUi.button(g, "hfind", "Caută", "search", x + w - bw - 14f * s, y + (ch - 30f * s) / 2f, bw, 30f * s, GpsUi.PRIMARY, true, mx, my);
            hitF(x + w - bw - 14f * s, y + (ch - 30f * s) / 2f, bw, 30f * s, () -> focus(search, true));
        }
        y += ch + GpsTheme.S4;

        // actiuni rapide
        y = sectionHeader(g, "ACȚIUNI RAPIDE", y, null, null, mx, my);
        int n = compact || w < 330f ? 2 : 4;
        float gap = GpsTheme.S2;
        float tw = (w - gap * (n - 1)) / n;
        String[][] quick = {
            {"target", "Centrează", "pe poziția ta"},
            {"expand", "Vezi tot", "zona explorată"},
            {"flag", "Waypoint", "la poziția ta"},
            {"layers", "Straturi", "ce apare pe hartă"}
        };
        for (int i = 0; i < quick.length; i++) {
            float tx = x + (i % n) * (tw + gap);
            float ty = y + (i / n) * (56f * s + gap);
            boolean hv = GpsUi.in(mx, my, tx, ty, tw, 56f * s) && my > cy0 && my < cy1;
            float ht = GpsUi.hot("q" + i, hv);
            GpsTheme.card(g, tx, ty, tw, 56f * s, GpsTheme.R_MD, ht);
            GpsUi.icon(g, quick[i][0], tx + 20f * s, ty + 28f * s, 15f * s, GpsTheme.mix(acc(), 0xFFFFFFFF, ht * 0.4f));
            Gfx.text(g, clip(quick[i][1], 8.8f * s, true, tw - 46f * s), tx + 36f * s, ty + 25f * s, 8.8f * s, GpsTheme.TEXT, 0, true, 0f);
            Gfx.text(g, clip(quick[i][2], 7f * s, true, tw - 46f * s), tx + 36f * s, ty + 37f * s, 7f * s, GpsTheme.MUTED, 0, true, 0f);
            final int qi = i;
            hitF(tx, ty, tw, 56f * s, () -> {
                switch (qi) {
                    case 0:
                        centerOnPlayer();
                        go(P_MAP);
                        break;
                    case 1:
                        go(P_MAP);
                        fitAll();
                        break;
                    case 2:
                        addWpHere(mc, false);
                        break;
                    default:
                        go(P_MAP);
                        layersOpen = true;
                        break;
                }
            });
        }
        y += ((quick.length + n - 1) / n) * (56f * s + gap) + GpsTheme.S3;

        // favorite
        List<GpsData.Loc> favs = favLocs();
        if (!favs.isEmpty()) {
            y = sectionHeader(g, "FAVORITE", y, "Vezi toate", () -> go(P_FAV), mx, my);
            for (int i = 0; i < Math.min(3, favs.size()); i++) {
                y += locCard(g, favs.get(i), x, y, w, mx, my) + GpsTheme.S2;
            }
            y += GpsTheme.S2;
        }

        // rute recente
        if (!GpsPrefs.history.isEmpty()) {
            y = sectionHeader(g, "RUTE RECENTE", y, "Vezi toate", () -> go(P_ROUTES), mx, my);
            for (int i = 0; i < Math.min(3, GpsPrefs.history.size()); i++) {
                GpsPrefs.Hist h = GpsPrefs.history.get(i);
                y += histRow(g, h, x, y, w, mx, my) + GpsTheme.S2;
            }
        }
        contentH = y - py0() + GpsTheme.S5;
    }

    private float histRow(GuiGraphics g, GpsPrefs.Hist h, float x, float y, float w, int mx, int my) {
        String sub = Ui.time(h.at) + (GpsPrefs.showDist ? "  ·  " + distText(h.x, h.z) : "");
        return row(g, "h" + h.name + h.at, "clock", h.color, h.name, sub, x, y, w, mx, my,
            () -> {
                tgtX = h.x;
                tgtZ = h.z;
                anim = true;
                go(P_MAP);
            },
            "nav", () -> navTo(h.id, h.name, h.x, h.z, h.dim, h.color), null, null);
    }

    // ------------------------------------------------------------------ pagina LOCATII

    private void pageLocations(GuiGraphics g, int mx, int my) {
        float x = cx0 + pad();
        float w = pw();
        float y = py0();
        Map<GpsData.Cat, Integer> counts = catCounts();

        if (!GpsData.loaded) {
            GpsUi.loading(g, mapCx, cy0 + (cy1 - cy0) / 2f, (System.currentTimeMillis() - born) / 1000f, acc());
            Gfx.text(g, "Se încarcă locațiile…", mapCx, cy0 + (cy1 - cy0) / 2f + 26f, GpsTheme.T_SMALL, GpsTheme.MUTED, 1, true, 0f);
            contentH = 0f;
            return;
        }
        if (catFilter.isEmpty()) {
            if (counts.isEmpty()) {
                GpsUi.empty(g, "pin", "Nicio locație aici", "Serverul nu are locații în lumea în care te afli.",
                    mapCx, cy0 + (cy1 - cy0) / 2f - 20f, w - 40f);
                contentH = 0f;
                return;
            }
            y = sectionHeader(g, "CATEGORII", y, null, null, mx, my);
            int n = Math.max(1, (int) ((w + GpsTheme.S2) / (168f * s)));
            float gap = GpsTheme.S2;
            float tw = (w - gap * (n - 1)) / n;
            int i = 0;
            int total = 0;
            for (Map.Entry<GpsData.Cat, Integer> e : counts.entrySet()) total += e.getValue();
            float ty = y;
            // "toate"
            ty = catTile(g, "all", "pin", acc(), "Toate locațiile", total, x, ty, tw, gap, n, 0, mx, my, () -> catFilter = "*");
            i = 1;
            for (Map.Entry<GpsData.Cat, Integer> e : counts.entrySet()) {
                final GpsData.Cat c = e.getKey();
                catTile(g, "c" + c.id, c.icon, c.color, c.name, e.getValue(), x, y, tw, gap, n, i, mx, my, () -> catFilter = c.id);
                i++;
            }
            int rows = (i + n - 1) / n;
            y += rows * (62f * s + gap) + GpsTheme.S3;
            contentH = y - py0() + GpsTheme.S5;
            return;
        }

        // lista unei categorii
        String name = "Toate locațiile";
        int color = acc();
        for (GpsData.Cat c : GpsData.cats) if (c.id.equals(catFilter)) {
            name = c.name;
            color = c.color;
        }
        boolean hb = GpsUi.in(mx, my, x, y, 26f * s, 26f * s);
        Gfx.rrect(g, x, y, 26f * s, 26f * s, GpsTheme.R_SM, GpsTheme.mix(GpsTheme.PANEL, GpsTheme.PANEL_HI, GpsUi.hot("back", hb)));
        GpsUi.icon(g, "chev-left", x + 13f * s, y + 13f * s, 12f * s, GpsTheme.TEXT);
        hitF(x, y, 26f * s, 26f * s, () -> catFilter = "");
        Gfx.text(g, name, x + 34f * s, y + 17f * s, 10.4f * s, GpsTheme.TEXT, 0, true, 0f);
        y += 34f * s;

        List<GpsData.Loc> list = new ArrayList<>();
        for (GpsData.Loc l : shown) if ("*".equals(catFilter) || l.cat.equals(catFilter)) list.add(l);
        list = byDistance(list);
        Gfx.text(g, list.size() + (list.size() == 1 ? " locație" : " locații"), x, y + 8f, 7.6f * s, GpsTheme.FAINT, 0, true, 0f);
        y += 18f;
        if (list.isEmpty()) {
            GpsUi.empty(g, "search", "Nimic aici", "Încearcă altă categorie sau o căutare.", mapCx, y + 70f, w - 40f);
            y += 160f;
        }
        for (GpsData.Loc l : list) {
            if (y > cy1 + 60f) {
                y += 50f * s + GpsTheme.S2;
                continue;
            }
            y += locCard(g, l, x, y, w, mx, my) + GpsTheme.S2;
        }
        contentH = y - py0() + GpsTheme.S5;
    }

    private float catTile(GuiGraphics g, String id, String ic, int color, String name, int count, float x, float y,
                          float tw, float gap, int n, int i, int mx, int my, Runnable run) {
        float tx = x + (i % n) * (tw + gap);
        float ty = y + (i / n) * (62f * s + gap);
        float th = 62f * s;
        boolean hv = GpsUi.in(mx, my, tx, ty, tw, th) && my > cy0 && my < cy1;
        float t = GpsUi.hot(id, hv);
        GpsTheme.card(g, tx, ty, tw, th, GpsTheme.R_MD, t);
        Gfx.rrect(g, tx + 1f, ty + 1f, 3f, th - 2f, 1.5f, GpsTheme.alpha(color, 180));
        GpsUi.badge(g, ic, tx + 26f * s, ty + th / 2f, 15f * s, color);
        Gfx.text(g, clip(name, 9.4f * s, true, tw - 52f * s), tx + 46f * s, ty + 27f * s, 9.4f * s, GpsTheme.TEXT, 0, true, 0f);
        Gfx.text(g, count + (count == 1 ? " locație" : " locații"), tx + 46f * s, ty + 40f * s, 7.4f * s, GpsTheme.MUTED, 0, true, 0f);
        GpsUi.icon(g, "chev-right", tx + tw - 14f * s, ty + th / 2f, 11f * s, GpsTheme.mix(GpsTheme.FAINT, GpsTheme.TEXT, t));
        hitF(tx, ty, tw, th, run);
        return ty + th;
    }

    // ------------------------------------------------------------------ pagina CAUTARE

    private void pageSearch(GuiGraphics g, int mx, int my, float t) {
        float x = cx0 + pad();
        float w = pw();
        float y = py0();
        String q = search.getValue().trim();
        if (q.isEmpty()) {
            y = sectionHeader(g, "CĂUTĂRI RECENTE", y, GpsPrefs.searches.isEmpty() ? null : "Șterge",
                () -> {
                    GpsPrefs.searches.clear();
                    GpsPrefs.save();
                }, mx, my);
            if (GpsPrefs.searches.isEmpty()) {
                GpsUi.empty(g, "search", "Caută o locație", "Scrie un nume în bara de sus — rezultatele apar aici.", mapCx, y + 80f, w - 40f);
                contentH = 0f;
                return;
            }
            for (String sq : new ArrayList<>(GpsPrefs.searches)) {
                y += row(g, "sq" + sq, "clock", GpsTheme.MUTED, sq, "", x, y, w, mx, my, () -> {
                    search.setValue(sq);
                    dirty = true;
                }, "close", () -> {
                    GpsPrefs.searches.remove(sq);
                    GpsPrefs.save();
                }, null, null) + GpsTheme.S2;
            }
            contentH = y - py0() + GpsTheme.S5;
            return;
        }
        List<GpsData.Loc> list = byDistance(shown);
        Gfx.text(g, list.size() + " rezultate pentru „" + clip(q, 8f * s, true, 160f) + "”", x, y + 10f, 8f * s, GpsTheme.MUTED, 0, true, 0f);
        y += 22f;
        if (list.isEmpty()) {
            GpsUi.empty(g, "search", "Niciun rezultat", "Verifică scrierea sau caută după categorie în Locații.", mapCx, y + 70f, w - 40f);
            contentH = 0f;
            return;
        }
        for (GpsData.Loc l : list) {
            if (y > cy1 + 60f) {
                y += 50f * s + GpsTheme.S2;
                continue;
            }
            y += locCard(g, l, x, y, w, mx, my) + GpsTheme.S2;
        }
        contentH = y - py0() + GpsTheme.S5;
    }

    // ------------------------------------------------------------------ pagina FAVORITE

    private void pageFavorites(GuiGraphics g, int mx, int my) {
        float x = cx0 + pad();
        float w = pw();
        float y = py0();
        List<GpsData.Loc> favs = favLocs();
        if (favs.isEmpty()) {
            GpsUi.empty(g, "star-line", "Nicio favorită", "Apasă steaua de pe o locație ca să o salvezi aici.", mapCx, cy0 + 140f, w - 40f);
            contentH = 0f;
            return;
        }
        y = sectionHeader(g, favs.size() + " LOCAȚII SALVATE", y, null, null, mx, my);
        for (GpsData.Loc l : favs) y += locCard(g, l, x, y, w, mx, my) + GpsTheme.S2;
        contentH = y - py0() + GpsTheme.S5;
    }

    // ------------------------------------------------------------------ pagina RUTE

    private void pageRoutes(GuiGraphics g, Minecraft mc, int mx, int my) {
        float x = cx0 + pad();
        float w = pw();
        float y = py0();

        y = sectionHeader(g, "RUTA ACTIVĂ", y, null, null, mx, my);
        if (GpsNav.active) {
            double d = GpsNav.travelDistance(mc);
            String sub = (d < 0 ? "în altă lume" : GpsNav.fmtDist(d) + "  ·  ~" + GpsNav.fmtEta(GpsNav.etaSeconds(mc)))
                + "  ·  " + RoadRouteProvider.I.statusText();
            y += row(g, "act", "nav", GpsNav.color, GpsNav.name, sub, x, y, w, mx, my, () -> {
                tgtX = GpsNav.x;
                tgtZ = GpsNav.z;
                anim = true;
                go(P_MAP);
            }, "close", this::cancel, null, null) + GpsTheme.S3;
        } else {
            GpsTheme.card(g, x, y, w, 44f * s, GpsTheme.R_MD, 0f);
            Gfx.text(g, "Nicio rută activă", x + 16f * s, y + 26f * s, 8.8f * s, GpsTheme.MUTED, 0, true, 0f);
            y += 44f * s + GpsTheme.S3;
        }

        y = sectionHeader(g, "WAYPOINT-URILE MELE (" + GpsWps.mine.size() + "/" + GpsWps.max + ")", y,
            "+ Aici", () -> addWpHere(mc, false), mx, my);
        if (GpsWps.mine.isEmpty()) {
            GpsTheme.card(g, x, y, w, 40f * s, GpsTheme.R_MD, 0f);
            Gfx.text(g, "Niciun waypoint. Apasă „+ Aici” ca să salvezi poziția ta.", x + 16f * s, y + 24f * s, 7.8f * s, GpsTheme.MUTED, 0, true, 0f);
            y += 40f * s + GpsTheme.S3;
        }
        for (GpsWps.Wp wp : new ArrayList<>(GpsWps.mine)) y += wpRow(g, wp, x, y, w, mx, my, true) + GpsTheme.S2;
        y += GpsTheme.S2;

        if (!GpsWps.shared.isEmpty() || GpsData.admin) {
            y = sectionHeader(g, "PARTAJATE DE SERVER", y, GpsData.admin ? "+ Aici" : null,
                GpsData.admin ? () -> addWpHere(mc, true) : null, mx, my);
            for (GpsWps.Wp wp : new ArrayList<>(GpsWps.shared)) y += wpRow(g, wp, x, y, w, mx, my, GpsData.admin) + GpsTheme.S2;
            y += GpsTheme.S2;
        }

        y = sectionHeader(g, "ULTIMELE DESTINAȚII", y, GpsPrefs.history.isEmpty() ? null : "Șterge tot",
            () -> {
                GpsPrefs.history.clear();
                GpsPrefs.save();
            }, mx, my);
        if (GpsPrefs.history.isEmpty()) {
            GpsTheme.card(g, x, y, w, 40f * s, GpsTheme.R_MD, 0f);
            Gfx.text(g, "Destinațiile tale apar aici după prima navigare.", x + 16f * s, y + 24f * s, 7.8f * s, GpsTheme.MUTED, 0, true, 0f);
            y += 40f * s;
        }
        for (GpsPrefs.Hist h : new ArrayList<>(GpsPrefs.history)) y += histRow(g, h, x, y, w, mx, my) + GpsTheme.S2;
        contentH = y - py0() + GpsTheme.S5;
    }

    private float wpRow(GuiGraphics g, GpsWps.Wp wp, float x, float y, float w, int mx, int my, boolean canDelete) {
        String sub = Math.round(wp.x) + ", " + Math.round(wp.z) + (GpsPrefs.showDist ? "  ·  " + distText(wp.x, wp.z) : "");
        return row(g, "wp" + wp.id, "flag", wp.color, wp.name, sub, x, y, w, mx, my,
            () -> {
                tgtX = wp.x;
                tgtZ = wp.z;
                anim = true;
                go(P_MAP);
            },
            "nav", () -> navTo("wp:" + wp.id, wp.name, wp.x, wp.z, wp.dim, wp.color),
            canDelete ? "trash" : null, canDelete ? () -> GpsWps.delete(wp) : null);
    }

    // ------------------------------------------------------------------ pagina SETARI

    private void pageSettings(GuiGraphics g, Minecraft mc, int mx, int my) {
        float x = cx0 + pad();
        float w = pw();
        float y = py0();

        y = sectionHeader(g, "ASPECT", y, null, null, mx, my);
        y += themeRow(g, x, y, w, mx, my) + GpsTheme.S2;
        y += routeRow(g, x, y, w, mx, my) + GpsTheme.S2;
        y += stepRow(g, "uis", "Scalare interfață", String.format(Locale.ROOT, "%.0f%%", GpsPrefs.uiScale * 100f), x, y, w, mx, my,
            () -> setScale(-0.05f), () -> setScale(0.05f)) + GpsTheme.S2;
        y += stepRow(g, "opa", "Transparență panouri", Math.round(GpsPrefs.opacity * 100f) + "%", x, y, w, mx, my,
            () -> setOpacity(-0.05f), () -> setOpacity(0.05f)) + GpsTheme.S3;

        y = sectionHeader(g, "HARTĂ", y, null, null, mx, my);
        y += toggleRow(g, "tz", "Zone RP pe hartă", "Zonele legale și ilegale, colorate",
            GpsPrefs.showZones != null ? GpsPrefs.showZones : GpsData.showZonesDefault, x, y, w, mx, my, () -> {
                GpsPrefs.showZones = !(GpsPrefs.showZones != null ? GpsPrefs.showZones : GpsData.showZonesDefault);
                GpsPrefs.save();
            }) + GpsTheme.S2;
        y += toggleRow(g, "tg", "Grilă de coordonate", "Linii ajutătoare peste hartă", GpsPrefs.grid, x, y, w, mx, my, () -> {
            GpsPrefs.grid = !GpsPrefs.grid;
            GpsPrefs.save();
        }) + GpsTheme.S2;
        y += toggleRow(g, "twp", "Waypoint-uri pe hartă", "Punctele tale și cele ale serverului",
            GpsPrefs.layerOn("waypoints"), x, y, w, mx, my, () -> {
                GpsPrefs.toggleLayer("waypoints");
                GpsPrefs.save();
            }) + GpsTheme.S2;
        y += toggleRow(g, "tco", "Afișează coordonatele", "În antet și în bara laterală", GpsPrefs.showCoords, x, y, w, mx, my, () -> {
            GpsPrefs.showCoords = !GpsPrefs.showCoords;
            GpsPrefs.save();
        }) + GpsTheme.S2;
        y += toggleRow(g, "tdi", "Afișează distanțele", "Pe carduri și în liste", GpsPrefs.showDist, x, y, w, mx, my, () -> {
            GpsPrefs.showDist = !GpsPrefs.showDist;
            GpsPrefs.save();
        }) + GpsTheme.S2;
        float z = GpsPrefs.zoom > 0f ? GpsPrefs.zoom : (float) GpsData.defaultZoom;
        y += stepRow(g, "zm", "Zoom implicit", String.format(Locale.ROOT, "%.1fx", z), x, y, w, mx, my,
            () -> setZoom(z / 1.2f), () -> setZoom(z * 1.2f)) + GpsTheme.S3;

        y = sectionHeader(g, "NAVIGARE", y, null, null, mx, my);
        y += toggleRow(g, "thud", "Săgeata GPS pe ecran", "Indicatorul care te ghidează în timpul jocului",
            GpsPrefs.hudOn, x, y, w, mx, my, () -> {
                GpsPrefs.hudOn = !GpsPrefs.hudOn;
                GpsPrefs.save();
            }) + GpsTheme.S2;
        y += toggleRow(g, "tno", "Notificare la sosire", "Mesaj când ajungi la destinație", GpsPrefs.notify, x, y, w, mx, my, () -> {
            GpsPrefs.notify = !GpsPrefs.notify;
            GpsPrefs.save();
        }) + GpsTheme.S2;
        y += toggleRow(g, "tsn", "Sunete la viraje", "Anunț scurt înainte de fiecare viraj", GpsPrefs.navSound, x, y, w, mx, my, () -> {
            GpsPrefs.navSound = !GpsPrefs.navSound;
            GpsPrefs.save();
        }) + GpsTheme.S2;
        y += toggleRow(g, "tps", "Sunet la alegerea destinației", "Un clic scurt la confirmare", GpsPrefs.pickSound, x, y, w, mx, my, () -> {
            GpsPrefs.pickSound = !GpsPrefs.pickSound;
            GpsPrefs.save();
        }) + GpsTheme.S2;
        y += toggleRow(g, "tkl", "Păstrează ultima destinație", "Rămâne activă și după reconectare", GpsPrefs.keepLast, x, y, w, mx, my, () -> {
            GpsPrefs.keepLast = !GpsPrefs.keepLast;
            GpsPrefs.save();
        }) + GpsTheme.S3;

        y = sectionHeader(g, "MINIMAP", y, "Toate setările", () -> mc.setScreen(new MiniMapSettingsScreen()), mx, my);
        y += toggleRow(g, "tmm", "Minimap pornit", "Harta mică din colțul ecranului", MiniMapSettings.enabled, x, y, w, mx, my, () -> {
            MiniMapSettings.enabled = !MiniMapSettings.enabled;
            MiniMapSettings.save();
        }) + GpsTheme.S2;
        y += toggleRow(g, "tmw", "Waypoint pe minimap", "Destinația apare și pe minimap", MiniMapSettings.showWaypoint, x, y, w, mx, my, () -> {
            MiniMapSettings.showWaypoint = !MiniMapSettings.showWaypoint;
            MiniMapSettings.save();
        }) + GpsTheme.S2;
        y += stepRow(g, "mms", "Dimensiune minimap", MiniMapSettings.size + " px", x, y, w, mx, my,
            () -> setMiniSize(-16), () -> setMiniSize(16)) + GpsTheme.S3;

        y = sectionHeader(g, "CATEGORII PE HARTĂ", y, null, null, mx, my);
        float chipY = y;
        float cxp = x;
        float ch = 24f * s;
        for (GpsData.Cat c : GpsData.cats) {
            if (!c.enabled) continue;
            boolean on = !MiniMapSettings.hiddenCats.contains(c.id);
            float cw = GpsUi.chipWidth(c.name, ch, true);
            if (cxp + cw > x + w) {
                cxp = x;
                chipY += ch + 6f;
            }
            GpsUi.chip(g, "cc" + c.id, c.name, cxp, chipY, cw, ch, on, c.color, mx, my);
            final String id = c.id;
            hitF(cxp, chipY, cw, ch, () -> {
                if (!MiniMapSettings.hiddenCats.remove(id)) MiniMapSettings.hiddenCats.add(id);
                MiniMapSettings.save();
                dirty = true;
            });
            cxp += cw + 6f;
        }
        y = chipY + ch + GpsTheme.S3;

        y = sectionHeader(g, "STRATURI", y, null, null, mx, my);
        float lxp = x;
        float lyp = y;
        for (String[] ly : LAYERS) {
            boolean on = GpsPrefs.layerOn(ly[0]);
            float cw = GpsUi.chipWidth(ly[1], ch, false);
            if (lxp + cw > x + w) {
                lxp = x;
                lyp += ch + 6f;
            }
            GpsUi.chip(g, "ly" + ly[0], ly[1], lxp, lyp, cw, ch, on, 0, mx, my);
            final String id = ly[0];
            hitF(lxp, lyp, cw, ch, () -> {
                GpsPrefs.toggleLayer(id);
                GpsPrefs.save();
                dirty = true;
            });
            lxp += cw + 6f;
        }
        y = lyp + ch + GpsTheme.S4;

        GpsUi.button(g, "reset", "Resetează setările GPS", "trash", x, y, Math.min(230f * s, w), 32f * s, GpsUi.DANGER, true, mx, my);
        hitF(x, y, Math.min(230f * s, w), 32f * s, () -> {
            GpsPrefs.resetSettings();
            zoom = (float) GpsData.defaultZoom;
            dirty = true;
            say("Setările GPS au fost resetate");
        });
        Gfx.text(g, "Favoritele, waypoint-urile și istoricul rămân.", x, y + 44f * s, 7.4f * s, GpsTheme.FAINT, 0, true, 0f);
        contentH = y + 56f * s - py0() + GpsTheme.S5;
    }

    private void setScale(float d) {
        GpsPrefs.uiScale = Math.max(0.85f, Math.min(1.25f, Math.round((GpsPrefs.uiScale + d) * 100f) / 100f));
        GpsPrefs.save();
    }

    private void setOpacity(float d) {
        GpsPrefs.opacity = Math.max(0.6f, Math.min(1f, Math.round((GpsPrefs.opacity + d) * 100f) / 100f));
        GpsPrefs.save();
    }

    private void setZoom(float z) {
        GpsPrefs.zoom = Math.max(0.3f, Math.min(6f, z));
        zoom = GpsPrefs.zoom;
        GpsPrefs.save();
    }

    private void setMiniSize(int d) {
        MiniMapSettings.size = Math.max(MiniMapSettings.SIZE_MIN, Math.min(MiniMapSettings.SIZE_MAX, MiniMapSettings.size + d));
        MiniMapSettings.save();
    }

    private float toggleRow(GuiGraphics g, String id, String title, String sub, boolean on, float x, float y, float w,
                            int mx, int my, Runnable run) {
        float h = 42f * s;
        boolean hv = GpsUi.in(mx, my, x, y, w, h) && my > cy0 && my < cy1;
        float t = GpsUi.hot(id, hv);
        GpsTheme.card(g, x, y, w, h, GpsTheme.R_MD, t * 0.7f);
        Gfx.text(g, title, x + 14f * s, y + (sub.isEmpty() ? h / 2f + 3f * s : 19f * s), 8.8f * s, GpsTheme.TEXT, 0, true, 0f);
        if (!sub.isEmpty()) Gfx.text(g, clip(sub, 7.2f * s, true, w - 70f * s), x + 14f * s, y + 31f * s, 7.2f * s, GpsTheme.MUTED, 0, true, 0f);
        GpsUi.toggle(g, id, x + w - 48f * s, y + (h - 18f) / 2f, on, mx, my);
        hitF(x, y, w, h, run);
        return h;
    }

    private float stepRow(GuiGraphics g, String id, String title, String value, float x, float y, float w,
                          int mx, int my, Runnable minus, Runnable plus) {
        float h = 42f * s;
        GpsTheme.card(g, x, y, w, h, GpsTheme.R_MD, 0f);
        Gfx.text(g, title, x + 14f * s, y + h / 2f + 3f * s, 8.8f * s, GpsTheme.TEXT, 0, true, 0f);
        float bs = 24f * s;
        float bx = x + w - 14f * s - bs;
        iconAction(g, id + "p", "plus", bx, y + (h - bs) / 2f, bs, mx, my, plus);
        float vx = bx - 8f;
        Gfx.text(g, value, vx, y + h / 2f + 3f * s, 8.4f * s, GpsTheme.MUTED, 2, true, 0.2f);
        float mxb = vx - Gfx.width(value, 8.4f * s, true) - 8f - bs;
        iconAction(g, id + "m", "minus", mxb, y + (h - bs) / 2f, bs, mx, my, minus);
        return h;
    }

    /** Culoarea traseului GPS (implicit magenta, ca pe harta de referinta). */
    private float routeRow(GuiGraphics g, float x, float y, float w, int mx, int my) {
        float h = 46f * s;
        GpsTheme.card(g, x, y, w, h, GpsTheme.R_MD, 0f);
        Gfx.text(g, "Culoare traseu", x + 14f * s, y + h / 2f, 8.8f * s, GpsTheme.TEXT, 0, true, 0f);
        Gfx.text(g, GpsPrefs.ROUTE_NAMES[GpsPrefs.routeIdx], x + 14f * s, y + h / 2f + 11f * s, 7.4f * s,
            GpsTheme.MUTED, 0, true, 0f);
        float bs = 20f * s;
        float bx = x + w - 14f * s - GpsPrefs.ROUTE_COLORS.length * (bs + 7f) + 7f;
        for (int i = 0; i < GpsPrefs.ROUTE_COLORS.length; i++) {
            int c = GpsPrefs.ROUTE_COLORS[i];
            float px = bx + i * (bs + 7f);
            boolean sel = GpsPrefs.routeIdx == i;
            boolean hv = GpsUi.in(mx, my, px, y + (h - bs) / 2f, bs, bs);
            float t = GpsUi.hot("rtc" + i, hv);
            if (sel) GpsUi.ringStroke(g, px + bs / 2f, y + h / 2f, bs / 2f + 3f, 1.6f, c);
            Gfx.disc(g, px + bs / 2f, y + h / 2f, bs / 2f * (1f + t * 0.08f), c);
            final int idx = i;
            hitF(px, y + (h - bs) / 2f, bs, bs, () -> {
                GpsPrefs.routeIdx = idx;
                GpsPrefs.save();
            });
        }
        return h;
    }

    private float themeRow(GuiGraphics g, float x, float y, float w, int mx, int my) {
        float h = 46f * s;
        GpsTheme.card(g, x, y, w, h, GpsTheme.R_MD, 0f);
        Gfx.text(g, "Culoare de accent", x + 14f * s, y + h / 2f + 3f * s, 8.8f * s, GpsTheme.TEXT, 0, true, 0f);
        float bs = 20f * s;
        float bx = x + w - 14f * s - GpsPrefs.ACCENTS.length * (bs + 7f) + 7f;
        for (int i = 0; i < GpsPrefs.ACCENTS.length; i++) {
            int c = GpsPrefs.ACCENTS[i];
            float px = bx + i * (bs + 7f);
            boolean sel = GpsPrefs.accentIdx == i;
            boolean hv = GpsUi.in(mx, my, px, y + (h - bs) / 2f, bs, bs);
            float t = GpsUi.hot("acc" + i, hv);
            if (sel) GpsUi.ringStroke(g, px + bs / 2f, y + h / 2f, bs / 2f + 3f, 1.6f, c);
            Gfx.disc(g, px + bs / 2f, y + h / 2f, bs / 2f * (1f + t * 0.08f), c);
            final int idx = i;
            hitF(px, y + (h - bs) / 2f, bs, bs, () -> {
                GpsPrefs.accentIdx = idx;
                GpsPrefs.save();
            });
        }
        return h;
    }

    // ------------------------------------------------------------------ panou de detalii

    private void drawDetail(GuiGraphics g, int mx, int my) {
        if (detail < 0.02f || selected == null) return;
        GpsData.Loc l = selected;
        float w = detailW;
        float right = menuOpen ? width : width - locBarW;
        float top = menuOpen ? cy0 : 70f * s;
        float x = right - w * detail;
        hitF(x, top, w, height - top, () -> {});
        GpsTheme.shadow(g, x, top, w, height - top, 0f);
        Gfx.rrect(g, x, top, w, height - top, 0, GpsTheme.BG2);
        Gfx.rrect(g, x, top, 1f, height - top, 0, GpsTheme.LINE);
        float px = x + GpsTheme.S4;
        float pwD = w - GpsTheme.S4 * 2f;
        float y = top + GpsTheme.S4;

        // antet card
        GpsUi.badge(g, l.icon, px + 20f * s, y + 20f * s, 19f * s, l.color);
        float cs = 22f * s;
        boolean hc = GpsUi.in(mx, my, x + w - cs - 10f, y, cs, cs);
        GpsUi.icon(g, "close", x + w - cs / 2f - 10f, y + cs / 2f, 11f * s, hc ? GpsTheme.TEXT : GpsTheme.FAINT);
        hitF(x + w - cs - 10f, y, cs, cs, () -> selected = null);
        y += 48f * s;
        Gfx.text(g, clip(l.name, 12.4f * s, true, pwD), px, y, 12.4f * s, GpsTheme.TEXT, 0, true, 0f);
        y += 16f * s;
        float cw = GpsUi.chipWidth(l.catName, 20f * s, true);
        GpsUi.chip(g, "dcat", l.catName, px, y, Math.min(cw, pwD), 20f * s, false, l.color, -1, -1);
        y += 30f * s;

        if (!l.desc.isEmpty()) {
            for (String line : wrap(l.desc, 8f * s, pwD, 4)) {
                Gfx.text(g, line, px, y, 8f * s, GpsTheme.MUTED, 0, true, 0f);
                y += 12f * s;
            }
            y += GpsTheme.S2;
        }

        // date
        y += GpsTheme.S1;
        double d = dist(l.x, l.z);
        float limit = height - GpsTheme.S4 - 32f * s - 36f * s - 26f * s;
        if (y < limit) y += infoRow(g, "DISTANȚĂ", d < 0 ? "—" : GpsNav.fmtDist(d), px, y, pwD);
        if (y < limit) y += infoRow(g, "COORDONATE", Math.round(l.x) + ", " + Math.round(l.z), px, y, pwD);
        if (d >= 0 && y < limit) infoRow(g, "TIMP ALERGÂND", GpsNav.fmtEta(d / 4.3), px, y, pwD);

        // actiuni
        float by = height - GpsTheme.S4 - 32f * s - 36f * s;
        boolean target = GpsNav.isTarget(l.id);
        GpsUi.button(g, "dgo", target ? "Oprește navigarea" : "Setează destinația", target ? "close" : "nav",
            px, by, pwD, 32f * s, target ? GpsUi.DANGER : GpsUi.PRIMARY, true, mx, my);
        hitF(px, by, pwD, 32f * s, this::activate);
        by += 36f * s;
        boolean fav = GpsPrefs.fav.contains(l.id);
        boolean isPin = PIN.equals(l.id);
        float half = (pwD - GpsTheme.S2) / 2f;
        if (isPin) {
            GpsUi.button(g, "dwp", "Salvează waypoint", "flag", px, by, half, 30f * s, GpsUi.SOFT, true, mx, my);
            hitF(px, by, half, 30f * s, () -> GpsWps.save("", "Waypoint " + (GpsWps.mine.size() + 1), l.x, l.z, l.dim, GpsTheme.GOLD, false));
        } else {
            GpsUi.button(g, "dfav", fav ? "Scoate" : "Favorit", "star-line", px, by, half, 30f * s, GpsUi.SOFT, true, mx, my);
            hitF(px, by, half, 30f * s, () -> toggleFav(l));
        }
        GpsUi.button(g, "dmap", "Pe hartă", "map", px + half + GpsTheme.S2, by, half, 30f * s, GpsUi.SOFT, true, mx, my);
        hitF(px + half + GpsTheme.S2, by, half, 30f * s, () -> {
            tgtX = l.x;
            tgtZ = l.z;
            anim = true;
            go(P_MAP);
        });
    }

    private float infoRow(GuiGraphics g, String label, String value, float x, float y, float w) {
        GpsTheme.caption(g, label, x, y, GpsTheme.FAINT);
        Gfx.text(g, value, x + w, y, 8.6f * s, GpsTheme.TEXT, 2, true, 0.1f);
        GpsTheme.divider(g, x, y + 7f, w);
        return 22f * s;
    }

    private List<String> wrap(String text, float size, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String probe = cur.length() == 0 ? word : cur + " " + word;
            if (Gfx.width(probe, size, true) > maxW && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
                if (out.size() >= maxLines) return out;
            } else {
                cur = new StringBuilder(probe);
            }
        }
        if (cur.length() > 0 && out.size() < maxLines) out.add(cur.toString());
        return out;
    }

    // ------------------------------------------------------------------ straturi peste harta

    private void mapOverlays(GuiGraphics g, Minecraft mc, int mx, int my) {
        float bs = 32f * s;
        float bx = mx1 - GpsTheme.S3 - bs;
        float by = my1 - GpsTheme.S3 - bs;
        if (GpsUi.iconButton(g, "mc", "target", bx, by, bs, false, mx, my)) tip(g, "Centrează pe tine (C)", bx - 128f, by + 4f);
        hitF(bx, by, bs, bs, this::centerOnPlayer);
        by -= bs + 6f;
        if (GpsUi.iconButton(g, "mf", "expand", bx, by, bs, false, mx, my)) tip(g, "Vezi tot ce ai explorat", bx - 136f, by + 4f);
        hitF(bx, by, bs, bs, this::fitAll);
        by -= bs + 10f;
        GpsUi.iconButton(g, "mzo", "minus", bx, by, bs, false, mx, my);
        hitF(bx, by, bs, bs, () -> zoomBy(1f / 1.3f, mapCx, mapCy));
        by -= bs + 2f;
        Gfx.text(g, String.format(Locale.ROOT, "%.1fx", zoom), bx + bs / 2f, by + 9f, 7.4f * s, GpsTheme.MUTED, 1, true, 0.2f);
        by -= bs - 4f;
        GpsUi.iconButton(g, "mzi", "plus", bx, by, bs, false, mx, my);
        hitF(bx, by, bs, bs, () -> zoomBy(1.3f, mapCx, mapCy));

        // straturi + busola, sus-dreapta peste harta
        float tx = mx1 - GpsTheme.S3 - bs;
        float ty = my0 + GpsTheme.S3;
        GpsUi.iconButton(g, "mly", "layers", tx, ty, bs, layersOpen, mx, my);
        hitF(tx, ty, bs, bs, () -> layersOpen = !layersOpen);
        compass(g, mx0 + GpsTheme.S3 + 20f, my0 + GpsTheme.S3 + 20f, mc);

        // coordonatele cursorului
        if (GpsPrefs.showCoords && GpsUi.in(mx, my, mx0, my0, mx1 - mx0, my1 - my0)) {
            long wx = Math.round(camX + (mx - mapCx) / zoom);
            long wz = Math.round(camZ + (my - mapCy) / zoom);
            String txt = wx + ", " + wz;
            float w = Gfx.width(txt, 7.6f, true) + 18f;
            GpsTheme.glass(g, mx0 + GpsTheme.S3, my1 - GpsTheme.S3 - 22f, w, 22f, GpsTheme.R_SM);
            Gfx.text(g, txt, mx0 + GpsTheme.S3 + 9f, my1 - GpsTheme.S3 - 7.5f, 7.6f, GpsTheme.MUTED, 0, true, 0.2f);
        }
    }

    private void compass(GuiGraphics g, float cx, float cy, Minecraft mc) {
        Gfx.disc(g, cx, cy, 19f, GpsTheme.alpha(0x0B0E14, 210));
        GpsUi.ringStroke(g, cx, cy, 19f, 1f, GpsTheme.LINE);
        Gfx.text(g, "N", cx, cy - 8f, 7.4f, GpsTheme.GOLD, 1, true, 0f);
        Gfx.text(g, "S", cx, cy + 16f, 6.4f, GpsTheme.FAINT, 1, true, 0f);
        Gfx.text(g, "V", cx - 13f, cy + 4f, 6.4f, GpsTheme.FAINT, 1, true, 0f);
        Gfx.text(g, "E", cx + 13f, cy + 4f, 6.4f, GpsTheme.FAINT, 1, true, 0f);
        if (mc.player != null) {
            double ang = Math.PI + Math.toRadians(mc.player.yRot);
            GpsIcons.arrow(g, cx, cy + 2f, 6.4f, ang, acc(), 0xFF0B0E14);
        }
    }

    // ------------------------------------------------------------------ bara de locatii, banner, hamburger

    /** Bara neagra din dreapta: locatiile serverului ca butoane, cu paginare. */
    private void drawLocBar(GuiGraphics g, int mx, int my) {
        float x = width - locBarW;
        Gfx.rrect(g, x, 0, locBarW, height, 0, 0xF2000000);
        Gfx.rrect(g, x, 0, 1f, height, 0, GpsTheme.alpha(0xFFFFFF, 26));

        float bh = 30f * s;
        float gap = 4f * s;
        float top = GpsTheme.S2;
        float pagerH = 26f * s;
        float avail = height - top - pagerH - GpsTheme.S3;
        int per = Math.max(1, (int) ((avail + gap) / (bh + gap)));
        int total = bar.size();
        int pages = Math.max(1, (total + per - 1) / per);
        locPage = Math.max(0, Math.min(pages - 1, locPage));
        int from = locPage * per;

        float y = top;
        for (int i = from; i < Math.min(total, from + per); i++) {
            GpsData.Loc l = bar.get(i);
            boolean target = GpsNav.isTarget(l.id);
            boolean hov = GpsUi.in(mx, my, x + 6f, y, locBarW - 12f, bh);
            float ht = GpsUi.hot("lb" + l.id, hov);
            int fill = target ? GpsTheme.mix(0xFF1B3A6B, 0xFF2E6BD0, 0.55f + 0.45f * ht)
                : GpsTheme.mix(0xFF16294A, 0xFF24467E, ht);
            Gfx.rrect(g, x + 6f, y, locBarW - 12f, bh, 3f, fill);
            Gfx.rrect(g, x + 6f, y, locBarW - 12f, 1f, 0, GpsTheme.alpha(0xFFFFFF, target ? 70 : 34));
            if (target) Gfx.rrect(g, x + 6f, y, 2.4f, bh, 1.2f, GpsNav.color);
            float iw = 16f * s;
            Gfx.text(g, clip(l.name.toUpperCase(Locale.ROOT), 8.4f * s, true, locBarW - 30f - iw),
                x + 13f, y + bh / 2f + 3f * s, 8.4f * s, 0xFFFFFFFF, 0, true, 0.6f);
            GpsIcons.glyph(g, l.icon, x + locBarW - 12f - iw / 2f, y + bh / 2f, iw * 0.62f, l.color, 1f);
            final GpsData.Loc loc = l;
            hitF(x + 6f, y, locBarW - 12f, bh, () -> {
                select(loc, true);
                activate();
            });
            y += bh + gap;
        }

        // paginare jos: „n/total" cu sageti
        float py = height - GpsTheme.S3 - pagerH;
        float pw = locBarW - 12f;
        Gfx.rrect(g, x + 6f, py, pw, pagerH, 3f, 0xFF16294A);
        Gfx.text(g, (pages == 0 ? 0 : locPage + 1) + "/" + pages, x + 6f + pw / 2f - 8f * s, py + pagerH / 2f + 3f * s,
            8.6f * s, 0xFFFFFFFF, 1, true, 0.4f);
        float ax = x + locBarW - 20f;
        boolean hUp = GpsUi.in(mx, my, ax - 8f, py, 22f, pagerH / 2f);
        boolean hDn = GpsUi.in(mx, my, ax - 8f, py + pagerH / 2f, 22f, pagerH / 2f);
        GpsUi.icon(g, "chev-left", ax, py + pagerH * 0.3f, 8f * s, hUp ? 0xFFFFFFFF : 0xFF9FB4D8);
        GpsUi.icon(g, "chev-right", ax, py + pagerH * 0.72f, 8f * s, hDn ? 0xFFFFFFFF : 0xFF9FB4D8);
        hitF(ax - 8f, py, 22f, pagerH / 2f, () -> locPage--);
        hitF(ax - 8f, py + pagerH / 2f, 22f, pagerH / 2f, () -> locPage++);
    }

    /** Bannerul de sus: coordonatele si destinatia activa. */
    private void drawTopBanner(GuiGraphics g, Minecraft mc) {
        float cxm = mx0 + (mx1 - mx0) / 2f;
        if (GpsPrefs.showCoords) {
            String co = "X: " + Math.round(mc.player.getX()) + "   Z: " + Math.round(mc.player.getZ());
            float w = Gfx.width(co, 9f * s, true) + 26f;
            Gfx.rrect(g, cxm - w / 2f, 0f, w, 20f * s, 0f, 0xB2101216);
            Gfx.text(g, co, cxm, 14f * s, 9f * s, 0xFFE8EDF5, 1, true, 0.4f);
        }
        if (!GpsNav.active) return;
        float h = 40f * s;
        String dest = GpsNav.name.toUpperCase(Locale.ROOT);
        double d = GpsNav.travelDistance(mc);
        String sub = "Destinație: " + dest;
        float w = Math.max(Gfx.width(sub, 8.6f * s, true), 120f) + 70f * s;
        float x = cxm - w / 2f;
        float y = 22f * s;
        Gfx.rrect(g, x, y, w, h, 4f, 0xD91A3B2E);
        Gfx.rrect(g, x, y, w, h, 4f, GpsTheme.alpha(GpsTheme.OK, 70));
        float bs = 24f * s;
        Gfx.rrect(g, x + 8f * s, y + (h - bs) / 2f, bs, bs, 3f, GpsTheme.alpha(GpsTheme.OK, 60));
        GpsUi.icon(g, "check", x + 8f * s + bs / 2f, y + h / 2f, bs * 0.52f, GpsTheme.OK);
        float tx = x + 8f * s + bs + 8f * s;
        Gfx.text(g, "GPS Setat", tx, y + 16f * s, 9.4f * s, 0xFFFFFFFF, 0, true, 0.2f);
        String line = sub;
        if (d >= 0) line += "  ·  " + GpsNav.fmtDist(d);
        RoadRouteProvider.Step nx = RoadRouteProvider.I.next();
        if (nx != null && nx.turn != 9 && d >= 0) line += "  ·  " + nx.text();
        Gfx.text(g, clip(line, 8f * s, true, w - (tx - x) - 10f), tx, y + 29f * s, 8f * s, 0xFFC9E6D4, 0, true, 0.1f);
    }

    /** „[ X ] ANULARE GPS" jos-stanga, cand exista ruta activa. */
    private void drawCancelGps(GuiGraphics g, int mx, int my) {
        if (!GpsNav.active) return;
        float h = 28f * s;
        float x = GpsTheme.S3;
        float y = height - GpsTheme.S3 - h;
        String txt = "Anulare GPS";
        float w = Gfx.width(txt, 9.4f * s, true) + 54f * s;
        boolean hov = GpsUi.in(mx, my, x, y, w, h);
        float ht = GpsUi.hot("cancelgps", hov);
        Gfx.rrect(g, x, y, w, h, 3f, GpsTheme.mix(0xCC101216, 0xE62A1016, ht));
        Gfx.rrect(g, x, y, w, h, 3f, GpsTheme.alpha(GpsTheme.DANGER, (int) (60 + 90 * ht)));
        Gfx.text(g, "[", x + 9f * s, y + h / 2f + 3.4f * s, 9.4f * s, GpsTheme.DANGER, 0, true, 0f);
        GpsUi.icon(g, "close", x + 20f * s, y + h / 2f, 9f * s, GpsTheme.DANGER);
        Gfx.text(g, "]", x + 29f * s, y + h / 2f + 3.4f * s, 9.4f * s, GpsTheme.DANGER, 0, true, 0f);
        Gfx.text(g, txt, x + 40f * s, y + h / 2f + 3.4f * s, 9.4f * s, GpsTheme.mix(0xFFE8EDF5, 0xFFFFFFFF, ht), 0, true, 0.2f);
        hitF(x, y, w, h, this::cancel);
    }

    /** Butonul hamburger din stanga sus, care deschide sertarul. */
    private void drawMenuButton(GuiGraphics g, int mx, int my) {
        float sz = 36f * s;
        float x = GpsTheme.S3;
        float y = GpsTheme.S3;
        boolean hov = GpsUi.in(mx, my, x, y, sz, sz);
        float ht = GpsUi.hot("burger", hov);
        Gfx.rrect(g, x, y, sz, sz, 3f, GpsTheme.mix(0xFF2E6BD0, 0xFF4C8AF0, ht));
        Gfx.rrect(g, x, y, sz, sz, 3f, GpsTheme.alpha(0xFFFFFF, (int) (40 + 50 * ht)));
        float lw = sz * 0.46f;
        for (int i = 0; i < 3; i++) {
            Gfx.rrect(g, x + (sz - lw) / 2f, y + sz * 0.33f + i * sz * 0.17f, lw, 2f * s, 1f, 0xFFFFFFFF);
        }
        hitF(x, y, sz, sz, () -> {
            menuOpen = true;
            if (page == P_MAP) go(P_HOME);
        });
    }

    private void drawLayers(GuiGraphics g, int mx, int my) {
        if (!layersOpen) return;
        float w = 196f;
        float rowH = 25f;
        float h = LAYERS.length * rowH + 34f;
        float x = mx1 - GpsTheme.S3 - w;
        float y = my0 + GpsTheme.S3 + 36f;
        GpsTheme.glass(g, x, y, w, h, GpsTheme.R_MD);
        GpsTheme.caption(g, "STRATURI HARTĂ", x + 14f, y + 18f, GpsTheme.FAINT);
        hitF(x, y, w, h, () -> {});
        for (int i = 0; i < LAYERS.length; i++) {
            final String id = LAYERS[i][0];
            float ry = y + 26f + i * rowH;
            boolean on = GpsPrefs.layerOn(id);
            boolean hv = GpsUi.in(mx, my, x + 5f, ry, w - 10f, rowH - 2f);
            float t = GpsUi.hot("lay" + id, hv);
            if (t > 0.01f) Gfx.rrect(g, x + 5f, ry, w - 10f, rowH - 2f, 6f, GpsTheme.alpha(0xFFFFFF, (int) (16 * t)));
            Gfx.rrect(g, x + 13f, ry + 5f, 13f, 13f, 4f, on ? acc() : GpsTheme.PANEL_HI);
            if (on) GpsUi.icon(g, "check", x + 19.5f, ry + 11.5f, 10f, 0xFF0B0E14);
            Gfx.text(g, LAYERS[i][1], x + 34f, ry + 15f, 8.2f, on ? GpsTheme.TEXT : GpsTheme.MUTED, 0, true, 0f);
            hitF(x + 5f, ry, w - 10f, rowH - 2f, () -> {
                GpsPrefs.toggleLayer(id);
                GpsPrefs.save();
                dirty = true;
            });
        }
    }

    private void drawNote(GuiGraphics g) {
        if (System.currentTimeMillis() >= noteUntil) return;
        float left = noteUntil - System.currentTimeMillis();
        float a = Math.min(1f, left / 320f);
        float w = Gfx.width(note, 8.6f, true) + 42f;
        float x = mx0 + (mx1 - mx0 - w) / 2f;
        float y = my0 + 14f + (1f - a) * 6f;
        Gfx.rrect(g, x, y, w, 28f, 14f, GpsTheme.fade(GpsTheme.PANEL, a));
        Gfx.rrect(g, x, y, w, 28f, 14f, GpsTheme.fade(GpsTheme.alpha(acc(), 60), a));
        GpsUi.icon(g, "check", x + 17f, y + 14f, 11f, GpsTheme.fade(acc(), a));
        Gfx.text(g, note, x + 29f, y + 17.6f, 8.6f, GpsTheme.fade(GpsTheme.TEXT, a), 0, true, 0f);
    }
}
