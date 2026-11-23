package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Meniul lateral al GPS-ului (stanga): Acasa, Locatii, Categorii, Favorite, Trasee, Istoric, Setari.
 * Fiecare sectiune are pagina ei (lista de randuri cu derulare); la schimbarea sectiunii pagina intra cu o tranzitie scurta.
 * Desenul e imediat (ca in restul ecranului); zonele clicabile se inregistreaza prin {@link GpsScreen#hitF}.
 */
final class GpsSidebar {
    static final String[] TABS = {"ACASĂ", "LOCAȚII", "CATEGORII", "FAVORITE", "TRASEE", "ISTORIC", "SETĂRI"};
    private static final float W = 270f;
    private static final float ROW = 36f;

    private final GpsScreen s;
    int section;
    private int scroll;
    private long pageAt = System.currentTimeMillis();
    float anim;
    private float x0;
    private float y0;
    private float h0;

    /** Un rand al paginii. */
    private static final class Row {
        String title = "";
        String sub = "";
        int dot;
        boolean header;
        boolean toggle;
        boolean on;
        boolean danger;
        Runnable click;
        String[] act = new String[0];
        Runnable[] actRun = new Runnable[0];
    }

    GpsSidebar(GpsScreen s) {
        this.s = s;
    }

    void goTo(int sec) {
        if (sec == section) return;
        section = sec;
        scroll = 0;
        pageAt = System.currentTimeMillis();
    }

    boolean over(double mx, double my) {
        return anim > 0.05f && mx >= x0 && mx <= x0 + W && my >= y0 && my <= y0 + h0;
    }

    void scrollBy(int d) {
        scroll = Math.max(0, scroll + d);
    }

    // ------------------------------------------------------------------ randuri

    private static Row header(String t) {
        Row r = new Row();
        r.header = true;
        r.title = t;
        return r;
    }

    private static Row item(String t, String sub, int dot, Runnable click, String[] act, Runnable[] run) {
        Row r = new Row();
        r.title = t;
        r.sub = sub;
        r.dot = dot;
        r.click = click;
        r.act = act;
        r.actRun = run;
        return r;
    }

    private static Row toggle(String t, boolean on, Runnable click) {
        Row r = new Row();
        r.title = t;
        r.toggle = true;
        r.on = on;
        r.click = click;
        return r;
    }

    private static String coords(double x, double z) {
        return Math.round(x) + " , " + Math.round(z);
    }

    private List<Row> build(Minecraft mc) {
        List<Row> l = new ArrayList<>();
        String dim = GpsData.dimOf(mc);
        switch (section) {
            case 0:
                home(l, mc, dim);
                break;
            case 1:
                locations(l, dim);
                break;
            case 2:
                categories(l);
                break;
            case 3:
                favorites(l, mc, dim);
                break;
            case 4:
                routes(l, mc, dim);
                break;
            case 5:
                history(l, mc);
                break;
            default:
                settings(l, mc);
                break;
        }
        return l;
    }

    private void home(List<Row> l, Minecraft mc, String dim) {
        l.add(header("DESTINAȚIE"));
        if (GpsNav.active) {
            double d = GpsNav.travelDistance(mc);
            String info = d < 0 ? "în altă lume" : GpsNav.fmtDist(d) + "  ·  ~" + GpsNav.fmtEta(GpsNav.etaSeconds(mc));
            l.add(item(GpsNav.name, info, GpsNav.color, () -> s.showAt(GpsNav.x, GpsNav.z), new String[] {"ANULEAZĂ"}, new Runnable[] {s::cancelNav}));
        } else {
            l.add(item("Nicio destinație", "Alege din Locații sau click-dreapta pe hartă", Sk.MUTED, null, new String[0], new Runnable[0]));
        }
        if (mc.player != null && mc.player.getRootVehicle() != mc.player) {
            l.add(item("În vehicul", GpsNav.active ? "Navigarea rămâne activă în timpul condusului" : "Setează o destinație ca să navighezi", Sk.ACC, null, new String[0], new Runnable[0]));
        }
        l.add(header("ACȚIUNI RAPIDE"));
        l.add(item("Centrează pe mine", coords(mc.player.getX(), mc.player.getZ()), Sk.ACC, s::centerOnPlayer, new String[0], new Runnable[0]));
        l.add(item("Vezi tot ce ai explorat", "Potrivește harta pe zona explorată", Sk.ACC, s::fitAll, new String[0], new Runnable[0]));
        l.add(item("Waypoint la poziția mea", "Îl salvează la Trasee", 0xFFFFC857, () -> addWpHere(mc, false), new String[0], new Runnable[0]));
        List<GpsData.Loc> favs = favLocs(dim);
        if (!favs.isEmpty()) {
            l.add(header("FAVORITE"));
            for (int i = 0; i < Math.min(3, favs.size()); i++) l.add(locRow(favs.get(i), mc));
        }
        if (!GpsPrefs.history.isEmpty()) {
            l.add(header("RECENT"));
            for (int i = 0; i < Math.min(2, GpsPrefs.history.size()); i++) l.add(histRow(GpsPrefs.history.get(i), mc));
        }
    }

    private void locations(List<Row> l, String dim) {
        l.add(header("GRUPURI DE LOCAȚII"));
        java.util.Map<String, Integer> cnt = new java.util.LinkedHashMap<>();
        int total = 0;
        for (GpsData.Loc o : GpsData.all()) {
            if (!o.dim.equals(dim) || o.hidden) continue;
            total++;
            String g = o.group == null || o.group.isEmpty() ? "ALTELE" : o.group;
            cnt.merge(g, 1, Integer::sum);
        }
        final int tot = total;
        l.add(item("TOATE", tot + " locații", Sk.ACC, () -> s.openList("TOATE"), new String[0], new Runnable[0]));
        for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
            final String g = e.getKey();
            l.add(item(g, e.getValue() + " locații", Sk.ACC, () -> s.openList(g), new String[0], new Runnable[0]));
        }
        l.add(item("★ Favorite", GpsPrefs.fav.size() + " salvate", 0xFFFFC857, () -> s.openList("FAV"), new String[0], new Runnable[0]));
    }

    private void categories(List<Row> l) {
        l.add(header("CATEGORII (hartă + minimap)"));
        l.add(item("Activează toate", "", Sk.GOOD, () -> {
            MiniMapSettings.hiddenCats.clear();
            MiniMapSettings.save();
            s.markDirty();
        }, new String[0], new Runnable[0]));
        l.add(item("Dezactivează toate", "", Sk.BAD, () -> {
            for (GpsData.Cat c : GpsData.cats) MiniMapSettings.hiddenCats.add(c.id);
            MiniMapSettings.save();
            s.markDirty();
        }, new String[0], new Runnable[0]));
        for (GpsData.Cat c : GpsData.cats) {
            final String id = c.id;
            Row r = toggle(c.name, !MiniMapSettings.hiddenCats.contains(id), () -> {
                if (!MiniMapSettings.hiddenCats.remove(id)) MiniMapSettings.hiddenCats.add(id);
                MiniMapSettings.save();
                s.markDirty();
            });
            r.dot = c.color;
            l.add(r);
        }
        l.add(header("STRATURI HARTĂ"));
        for (String[] ly : GpsScreen.LAYERS) {
            final String id = ly[0];
            l.add(toggle(ly[1], GpsPrefs.layerOn(id), () -> {
                GpsPrefs.toggleLayer(id);
                GpsPrefs.save();
                s.markDirty();
            }));
        }
    }

    private List<GpsData.Loc> favLocs(String dim) {
        List<GpsData.Loc> out = new ArrayList<>();
        for (GpsData.Loc o : GpsData.all()) if (GpsPrefs.fav.contains(o.id) && o.dim.equals(dim) && !o.hidden) out.add(o);
        return out;
    }

    private Row locRow(GpsData.Loc o, Minecraft mc) {
        double d = Math.hypot(o.x - mc.player.getX(), o.z - mc.player.getZ());
        return item(o.name, o.catName + "  ·  " + GpsNav.fmtDist(d), o.color, () -> s.selectLoc(o),
            new String[] {"GO"}, new Runnable[] {() -> {
                s.selectLoc(o);
                s.activateSel();
            }});
    }

    private void favorites(List<Row> l, Minecraft mc, String dim) {
        l.add(header("LOCAȚII FAVORITE"));
        List<GpsData.Loc> favs = favLocs(dim);
        if (favs.isEmpty()) l.add(item("Nicio favorită", "Apasă ★ lângă o locație în panoul Locații", Sk.MUTED, null, new String[0], new Runnable[0]));
        for (GpsData.Loc o : favs) {
            double d = Math.hypot(o.x - mc.player.getX(), o.z - mc.player.getZ());
            l.add(item(o.name, o.catName + "  ·  " + GpsNav.fmtDist(d), o.color, () -> s.selectLoc(o),
                new String[] {"GO", "★"}, new Runnable[] {() -> {
                    s.selectLoc(o);
                    s.activateSel();
                }, () -> {
                    GpsPrefs.fav.remove(o.id);
                    GpsPrefs.save();
                    s.markDirty();
                }}));
        }
    }

    private void addWpHere(Minecraft mc, boolean shared) {
        if (mc.player == null) return;
        int n = (shared ? GpsWps.shared.size() : GpsWps.mine.size()) + 1;
        GpsWps.save("", (shared ? "Punct " : "Waypoint ") + n, Math.round(mc.player.getX()), Math.round(mc.player.getZ()), GpsData.dimOf(mc), shared ? 0xFF4DA3FF : 0xFFFFC857, shared);
    }

    private Row wpRow(GpsWps.Wp w, Minecraft mc, boolean canDelete) {
        double d = Math.hypot(w.x - mc.player.getX(), w.z - mc.player.getZ());
        String sub = coords(w.x, w.z) + "  ·  " + GpsNav.fmtDist(d);
        Runnable go = () -> {
            GpsNav.set("wp:" + w.id, w.name, w.x, w.z, w.dim, w.color);
            s.say("GPS setat: " + w.name);
        };
        if (canDelete) return item(w.name, sub, w.color, () -> s.showAt(w.x, w.z), new String[] {"GO", "X"}, new Runnable[] {go, () -> GpsWps.delete(w)});
        return item(w.name, sub, w.color, () -> s.showAt(w.x, w.z), new String[] {"GO"}, new Runnable[] {go});
    }

    private void routes(List<Row> l, Minecraft mc, String dim) {
        l.add(header("RUTA ACTIVĂ"));
        if (GpsNav.active) {
            double d = GpsNav.travelDistance(mc);
            l.add(item(GpsNav.name, (d < 0 ? "în altă lume" : GpsNav.fmtDist(d) + "  ·  ~" + GpsNav.fmtEta(GpsNav.etaSeconds(mc))) + "  ·  " + RoadRouteProvider.I.statusText(),
                GpsNav.color, () -> s.showAt(GpsNav.x, GpsNav.z), new String[] {"ANULEAZĂ"}, new Runnable[] {s::cancelNav}));
        } else {
            l.add(item("Nicio rută activă", "Apasă GO la un waypoint sau o locație", Sk.MUTED, null, new String[0], new Runnable[0]));
        }
        l.add(header("WAYPOINT-URILE MELE (" + GpsWps.mine.size() + "/" + GpsWps.max + ")"));
        for (GpsWps.Wp w : GpsWps.mine) l.add(wpRow(w, mc, true));
        l.add(item("+ Waypoint la poziția mea", "", 0xFFFFC857, () -> addWpHere(mc, false), new String[0], new Runnable[0]));
        GpsData.Loc pin = s.pinLoc();
        if (pin != null) {
            l.add(item("+ Salvează punctul de pe hartă", coords(pin.x, pin.z), 0xFFFFC857,
                () -> GpsWps.save("", "Waypoint " + (GpsWps.mine.size() + 1), pin.x, pin.z, pin.dim, 0xFFFFC857, false), new String[0], new Runnable[0]));
        }
        if (!GpsWps.shared.isEmpty() || GpsData.admin) {
            l.add(header("PARTAJATE (SERVER)"));
            for (GpsWps.Wp w : GpsWps.shared) l.add(wpRow(w, mc, GpsData.admin));
            if (GpsData.admin) l.add(item("+ Partajat la poziția mea", "doar admin", 0xFF4DA3FF, () -> addWpHere(mc, true), new String[0], new Runnable[0]));
        }
        if (!GpsWps.err.isEmpty() && System.currentTimeMillis() - GpsWps.errAt < 5000L) {
            l.add(item(GpsWps.err, "", Sk.BAD, null, new String[0], new Runnable[0]));
        }
    }

    private Row histRow(GpsPrefs.Hist h, Minecraft mc) {
        double d = Math.hypot(h.x - mc.player.getX(), h.z - mc.player.getZ());
        return item(h.name, coords(h.x, h.z) + "  ·  " + GpsNav.fmtDist(d), h.color, () -> s.showAt(h.x, h.z),
            new String[] {"GO"}, new Runnable[] {() -> {
                GpsNav.set(h.id, h.name, h.x, h.z, h.dim, h.color);
                s.say("GPS setat: " + h.name);
            }});
    }

    private void history(List<Row> l, Minecraft mc) {
        l.add(header("ISTORIC DESTINAȚII"));
        if (GpsPrefs.history.isEmpty()) l.add(item("Istoric gol", "Destinațiile tale apar aici", Sk.MUTED, null, new String[0], new Runnable[0]));
        for (GpsPrefs.Hist h : new ArrayList<>(GpsPrefs.history)) l.add(histRow(h, mc));
        if (!GpsPrefs.history.isEmpty()) {
            Row r = item("Șterge istoricul", "", Sk.BAD, () -> {
                GpsPrefs.history.clear();
                GpsPrefs.save();
            }, new String[0], new Runnable[0]);
            r.danger = true;
            l.add(r);
        }
    }

    private void settings(List<Row> l, Minecraft mc) {
        l.add(header("HARTĂ"));
        l.add(toggle("Zone RP pe hartă", s.zonesOn(), s::toggleZones));
        l.add(toggle("Grilă", GpsPrefs.grid, () -> {
            GpsPrefs.grid = !GpsPrefs.grid;
            GpsPrefs.save();
        }));
        l.add(toggle("Waypoint pe hartă", GpsPrefs.layerOn("waypoints"), () -> {
            GpsPrefs.toggleLayer("waypoints");
            GpsPrefs.save();
        }));
        l.add(toggle("Panou locații", s.panelIsOpen(), s::togglePanel));
        float z = GpsPrefs.zoom > 0f ? GpsPrefs.zoom : (float) GpsData.defaultZoom;
        l.add(item("Zoom implicit", String.format(java.util.Locale.ROOT, "%.1fx", z), Sk.ACC, null, new String[] {"-", "+"}, new Runnable[] {
            () -> setZoom(z / 1.2f), () -> setZoom(z * 1.2f)}));
        l.add(header("NAVIGARE"));
        l.add(toggle("Notificare la sosire", GpsPrefs.notify, () -> {
            GpsPrefs.notify = !GpsPrefs.notify;
            GpsPrefs.save();
        }));
        l.add(toggle("Sunete la viraje", GpsPrefs.navSound, () -> {
            GpsPrefs.navSound = !GpsPrefs.navSound;
            GpsPrefs.save();
        }));
        l.add(header("MINIMAP"));
        l.add(toggle("Waypoint pe minimap", MiniMapSettings.showWaypoint, () -> {
            MiniMapSettings.showWaypoint = !MiniMapSettings.showWaypoint;
            MiniMapSettings.save();
        }));
        l.add(item("Dimensiune minimap", MiniMapSettings.size + " px", Sk.ACC, null, new String[] {"-", "+"}, new Runnable[] {
            () -> setMiniSize(-16), () -> setMiniSize(16)}));
        l.add(item("Toate setările minimap…", "", Sk.ACC, () -> mc.setScreen(new MiniMapSettingsScreen()), new String[0], new Runnable[0]));
        Row r = item("Resetează setările GPS", "favoritele și istoricul rămân", Sk.BAD, () -> {
            GpsPrefs.resetSettings();
            s.afterReset();
        }, new String[0], new Runnable[0]);
        r.danger = true;
        l.add(r);
    }

    private void setZoom(float z) {
        GpsPrefs.zoom = Math.max(0.3f, Math.min(6f, z));
        GpsPrefs.save();
    }

    private void setMiniSize(int d) {
        MiniMapSettings.size = Math.max(MiniMapSettings.SIZE_MIN, Math.min(MiniMapSettings.SIZE_MAX, MiniMapSettings.size + d));
        MiniMapSettings.save();
    }

    // ------------------------------------------------------------------ desen

    void draw(GuiGraphics g, int mx, int my, float dt, boolean open, float top, float bottom) {
        anim += ((open ? 1f : 0f) - anim) * Math.min(1f, dt * 10f);
        if (anim < 0.02f) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float m = GpsScreen.M;
        x0 = m - (1f - anim) * (W + m + 12f);
        y0 = top;
        h0 = Math.max(120f, bottom - top);
        GpsScreen.box(g, x0, y0, W, h0, 18f, 0xF20A1220, 0xFF2A3A55);
        s.hitF(x0, y0, W, h0, () -> {});
        Gfx.rrect(g, x0 + 14, y0 + 15, 3.5f, 17, 1.7f, Sk.ACC);
        Gfx.text(g, "MENIU GPS", x0 + 24, y0 + 29, 12f, Sk.WHITE, 0, true, 0.6f);
        // taburi (2 randuri)
        float cx = x0 + 12;
        float cy = y0 + 42;
        for (int i = 0; i < TABS.length; i++) {
            float cw = Gfx.width(TABS[i], 6.6f, true) + 18f;
            if (cx + cw > x0 + W - 12) {
                cx = x0 + 12;
                cy += 24f;
            }
            boolean sel = i == section;
            boolean hv = Sk.in(mx, my, cx, cy, cw, 20);
            GpsScreen.box(g, cx, cy, cw, 20, 8f, sel ? Sk.ACC : hv ? 0xFF22344D : 0xE00D1626, sel ? Sk.ACC : 0xFF22344D);
            Gfx.text(g, TABS[i], cx + cw / 2f + 1, cy + 13.5f, 6.6f, sel ? 0xFF061426 : Sk.MUTED, 1, true, 0.4f);
            final int idx = i;
            s.hitF(cx, cy, cw, 20, () -> goTo(idx));
            cx += cw + 5f;
        }
        float listTop = cy + 30f;
        float listBottom = y0 + h0 - 12f;
        Gfx.rrect(g, x0 + 14, listTop - 6, W - 28, 1.5f, 0.7f, 0xFF22344D);
        List<Row> rows = build(mc);
        int vis = Math.max(1, (int) ((listBottom - listTop) / ROW));
        int max = Math.max(0, rows.size() - vis);
        if (scroll > max) scroll = max;
        float slide = (1f - ease((System.currentTimeMillis() - pageAt) / 260f)) * 14f;
        for (int i = 0; i < vis && scroll + i < rows.size(); i++) {
            drawRow(g, rows.get(scroll + i), x0 + 10, listTop + i * ROW + slide, W - 20, mx, my);
        }
        if (rows.size() > vis) {
            float th = listBottom - listTop;
            float bh = Math.max(24f, th * vis / rows.size());
            float by = listTop + (th - bh) * (max == 0 ? 0f : scroll / (float) max);
            Gfx.rrect(g, x0 + W - 7, listTop, 2.5f, th, 1.2f, 0xFF14202F);
            Gfx.rrect(g, x0 + W - 7, by, 2.5f, bh, 1.2f, Sk.ACC);
        }
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void drawRow(GuiGraphics g, Row r, float x, float y, float w, int mx, int my) {
        if (r.header) {
            Gfx.text(g, r.title, x + 6, y + 24, 7f, Sk.ACC, 0, true, 1.2f);
            return;
        }
        float h = ROW - 3f;
        boolean hv = Sk.in(mx, my, x, y, w, h);
        int bg = r.danger ? (hv ? 0xFF2A1518 : 0xFF1A1014) : hv && r.click != null ? 0xFF1B2E47 : 0xE00D1626;
        GpsScreen.box(g, x, y, w, h, 10f, bg, hv && r.click != null ? Sk.ACC : r.danger ? 0xFF6B2A2E : 0xFF22344D);
        float tx = x + 12;
        if (r.dot != 0) {
            Gfx.disc(g, x + 14, y + h / 2f, 4.2f, r.dot);
            tx = x + 26;
        }
        float actW = 0;
        for (String a : r.act) actW += Gfx.width(a, 6.8f, true) + 18f + 4f;
        float maxW = w - (tx - x) - actW - (r.toggle ? 46f : 8f);
        boolean two = !r.sub.isEmpty();
        Gfx.text(g, GpsScreen.clip(r.title, 8.8f, true, maxW), tx, y + (two ? 14f : h / 2f + 3.4f), 8.8f, r.danger ? Sk.BAD : Sk.WHITE, 0, true, 0f);
        if (two) Gfx.text(g, GpsScreen.clip(r.sub, 6.8f, true, maxW), tx, y + 25f, 6.8f, Sk.MUTED, 0, true, 0f);
        if (r.click != null) s.hitF(x, y, w, h, r.click);
        if (r.toggle) {
            float px = x + w - 38f;
            float py = y + h / 2f - 8f;
            Gfx.rrect(g, px, py, 28f, 16f, 8f, r.on ? Sk.ACC : 0xFF22344D);
            Gfx.disc(g, r.on ? px + 20f : px + 8f, py + 8f, 5.4f, r.on ? 0xFF061426 : Sk.MUTED);
            return;
        }
        float ax = x + w - 8f;
        for (int i = r.act.length - 1; i >= 0; i--) {
            float aw = Gfx.width(r.act[i], 6.8f, true) + 18f;
            ax -= aw;
            float ay = y + h / 2f - 10f;
            boolean ah = Sk.in(mx, my, ax, ay, aw, 20);
            boolean red = "ANULEAZĂ".equals(r.act[i]) || "X".equals(r.act[i]);
            GpsScreen.box(g, ax, ay, aw, 20, 8f, ah ? (red ? Sk.BAD : 0xFF7DBBFF) : (red ? 0xFF3A1A1E : 0xFF14202F), red ? 0xFF6B2A2E : Sk.ACC);
            Gfx.text(g, r.act[i], ax + aw / 2f + 1, ay + 13.5f, 6.8f, ah ? 0xFF061426 : Sk.WHITE, 1, true, 0.3f);
            s.hitF(ax, ay, aw, 20, r.actRun[i]);
            ax -= 4f;
        }
    }
}
