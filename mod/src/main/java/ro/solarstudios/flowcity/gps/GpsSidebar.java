package ro.solarstudios.flowcity.gps;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Bara laterala din stanga a GPS-ului: logo FlowCity, zona curenta, sectiunea SERVER (locatiile serverului pe grupuri) si
 * sectiunea PERSONAL (markere personale, favorite, recente, salvate). Se restrange la o coloana de iconite.
 * Desenul e original; doar logo-ul e o textura PNG.
 */
final class GpsSidebar {
    /** O intrare din bara: locatie de server, waypoint sau destinatie recenta. */
    static final class Entry {
        String key = "";
        String name = "";
        String sub = "";
        String icon = "pin";
        int color = 0xFFFF7A1A;
        double x;
        double z;
        String dim = "minecraft:overworld";
        /** Locatia de server (sau punctul ales pe harta); null pentru waypoint-uri si recente. */
        GpsData.Loc loc;
        GpsWps.Wp wp;
        GpsPrefs.Hist hist;
    }

    private static final ResourceLocation LOGO = new ResourceLocation("flowcitycore", "textures/gui/logo.png");

    private final GpsScreen host;
    boolean open = true;
    private float anim = 1f;
    private String expanded = "";
    private float scroll;
    private float contentH;
    private float viewH;
    private final List<float[]> rects = new ArrayList<>();
    private final List<Entry> rectEntries = new ArrayList<>();

    GpsSidebar(GpsScreen host) {
        this.host = host;
    }

    /** Latimea curenta (animata) a barii. */
    float width(float s, int screenW) {
        float full = Math.max(112f, Math.min(138f, screenW * 0.16f)) * s;
        float slim = 40f * s;
        return slim + (full - slim) * anim;
    }

    void scrollBy(double delta, float s) {
        scroll = Math.max(0f, Math.min(Math.max(0f, contentH - viewH), scroll - (float) delta * 30f * s));
    }

    /** Click dreapta pe un rand din bara: intoarce intrarea (null daca nu e niciuna). */
    Entry entryAt(double mx, double my) {
        for (int i = 0; i < rects.size(); i++) {
            float[] r = rects.get(i);
            if (GpsUi.in(mx, my, r[0], r[1], r[2], r[3])) return rectEntries.get(i);
        }
        return null;
    }

    // ------------------------------------------------------------------ date

    private List<String> groupNames(Map<String, List<GpsData.Loc>> by) {
        List<String> out = new ArrayList<>();
        for (String g : GpsData.groups) if (by.containsKey(g)) out.add(g);
        for (String g : by.keySet()) if (!out.contains(g)) out.add(g);
        return out;
    }

    private Map<String, List<GpsData.Loc>> byGroup() {
        Map<String, List<GpsData.Loc>> by = new LinkedHashMap<>();
        for (GpsData.Loc l : host.nearby()) {
            String g = l.group == null || l.group.isEmpty() ? "ALTELE" : l.group;
            by.computeIfAbsent(g, k -> new ArrayList<>()).add(l);
        }
        return by;
    }

    Entry of(GpsData.Loc l) {
        Entry e = new Entry();
        e.key = "l" + l.id;
        e.name = l.name;
        e.sub = host.distText(l.x, l.z);
        e.icon = l.icon;
        e.color = l.color;
        e.x = l.x;
        e.z = l.z;
        e.dim = l.dim;
        e.loc = l;
        return e;
    }

    Entry of(GpsWps.Wp w) {
        Entry e = new Entry();
        e.key = "w" + w.id;
        e.name = w.name;
        e.sub = host.distText(w.x, w.z);
        e.icon = w.icon;
        e.color = w.color;
        e.x = w.x;
        e.z = w.z;
        e.dim = w.dim;
        e.wp = w;
        return e;
    }

    Entry of(GpsPrefs.Hist h) {
        Entry e = new Entry();
        e.key = "h" + h.id + h.name;
        e.name = h.name;
        e.sub = host.distText(h.x, h.z);
        e.icon = "route";
        e.color = h.color;
        e.x = h.x;
        e.z = h.z;
        e.dim = h.dim;
        e.hist = h;
        return e;
    }

    private List<Entry> personal(String kind) {
        List<Entry> out = new ArrayList<>();
        String dim = GpsData.dimOf(Minecraft.getInstance());
        switch (kind) {
            case "mine":
                for (GpsWps.Wp w : GpsWps.mine) out.add(of(w));
                break;
            case "fav":
                for (GpsData.Loc l : host.favoriteLocs()) out.add(of(l));
                break;
            case "recent":
                for (GpsPrefs.Hist h : GpsPrefs.history) if (h.dim.equals(dim)) out.add(of(h));
                break;
            default:
                for (GpsWps.Wp w : GpsWps.shared) out.add(of(w));
                break;
        }
        return out;
    }

    private static String zoneName(Minecraft mc) {
        if (mc.player == null) return "";
        String dim = GpsData.dimOf(mc);
        float px = (float) mc.player.getX();
        float pz = (float) mc.player.getZ();
        for (GpsData.Zone z : GpsData.zones) {
            if (!z.enabled || !z.dim.equals(dim) || z.px.length < 3) continue;
            if (px < z.minX || px > z.maxX || pz < z.minZ || pz > z.maxZ) continue;
            if (GpsData.inside(z.px, z.pz, px, pz)) return z.name;
        }
        return "";
    }

    // ------------------------------------------------------------------ desen

    void draw(GuiGraphics g, int mx, int my, float dt, float s, int W, int H) {
        Minecraft mc = Minecraft.getInstance();
        anim = GpsTheme.to(anim, open ? 1f : 0f, dt, 12f);
        float w = width(s, W);
        float textA = Math.max(0f, (anim - 0.55f) / 0.45f);
        rects.clear();
        rectEntries.clear();

        Gfx.rrect(g, 0, 0, w, H, 0, 0xEC0B0D11);
        Gfx.rrect(g, w - 1f, 0, 1f, H, 0, GpsTheme.alpha(GpsTheme.ORANGE, 64));
        host.hitF(0, 0, w, H, () -> {});

        // logo + titlu
        float ls = 30f * s;
        float lx = w < 100f * s ? (w - ls) / 2f : 12f * s;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(LOGO, Math.round(lx), Math.round(10f * s), Math.round(ls), Math.round(ls), 0f, 0f, 128, 128, 128, 128);
        if (textA > 0.02f) {
            Gfx.text(g, "FLOWCITY", lx + ls + 8f * s, 22f * s, 10f * s, GpsTheme.fade(GpsTheme.TEXT, textA), 0, true, 1.6f);
            Gfx.text(g, "GPS", lx + ls + 8f * s, 33f * s, 8f * s, GpsTheme.fade(GpsTheme.ORANGE, textA), 0, true, 2.6f);
        }
        // restrange / extinde
        float cb = 22f * s;
        float cbx = open ? w - cb - 8f * s : (w - cb) / 2f;
        float cby = open ? 14f * s : 46f * s;
        boolean hc = GpsUi.in(mx, my, cbx, cby, cb, cb);
        float ht = GpsUi.hot("sbtoggle", hc);
        Gfx.rrect(g, cbx, cby, cb, cb, GpsTheme.R_SM, GpsTheme.alpha(0xFFFFFF, (int) (14 + 26 * ht)));
        GpsUi.icon(g, open ? "chev-left" : "chev-right", cbx + cb / 2f, cby + cb / 2f, 10f * s, GpsTheme.mix(GpsTheme.MUTED, GpsTheme.TEXT, ht));
        host.hitF(cbx, cby, cb, cb, () -> open = !open);

        // zona curenta
        float top = open ? 76f * s : 80f * s;
        if (textA > 0.02f) {
            String zone = zoneName(mc);
            float zy = 56f * s;
            Gfx.rrect(g, 10f * s, zy - 9f * s, w - 20f * s, 20f * s, GpsTheme.R_SM, GpsTheme.fade(GpsTheme.alpha(0xFFFFFF, 14), textA));
            Gfx.disc(g, 20f * s, zy + 1f * s, 3f * s, GpsTheme.fade(zone.isEmpty() ? GpsTheme.FAINT : GpsTheme.OK, textA));
            String txt = zone.isEmpty() ? "Server · " + dimLabel(GpsData.dimOf(mc)) : zone;
            Gfx.text(g, GpsScreen.clip(txt, 8f * s, true, w - 44f * s), 29f * s, zy + 3.4f * s, 8f * s,
                GpsTheme.fade(GpsTheme.MUTED, textA), 0, true, 0.1f);
        }

        // subsol: actiuni rapide + blocul SETARI HARTA (zoom, limita, harta, mini, GPS)
        float rowH = 26f * s;
        float setH = (textA > 0.5f ? 92f : 0f) * s;
        float footH = rowH + setH + 10f * s;
        float bottom = H - footH;
        Gfx.rrect(g, 8f * s, bottom, w - 16f * s, 1f, 0, GpsTheme.LINE);
        float fy = bottom + 5f * s;
        quickRow(g, w, fy, rowH - 2f * s, s, mx, my);
        if (textA > 0.5f) settings(g, w, fy + rowH + 2f * s, s, mx, my, textA);

        viewH = Math.max(40f, bottom - top - 4f * s);
        if (anim < 0.5f) {
            slimBody(g, w, top, s, mx, my);
            contentH = 0f;
            return;
        }

        // corp derulabil
        Ui.scissor(0, Math.round(top), Math.round(w), Math.round(viewH));
        float y = top - scroll;
        float x0 = 8f * s;
        float rw = w - 16f * s;
        y = label(g, "SERVER", y, x0, s, textA);
        Map<String, List<GpsData.Loc>> by = byGroup();
        if (by.isEmpty()) {
            Gfx.text(g, "Nicio locație în această lume", x0 + 6f * s, y + 12f * s, 7.8f * s, GpsTheme.fade(GpsTheme.FAINT, textA), 0, true, 0f);
            y += 24f * s;
        }
        for (String gn : groupNames(by)) {
            List<GpsData.Loc> list = by.get(gn);
            List<Entry> es = new ArrayList<>();
            for (GpsData.Loc l : list) es.add(of(l));
            GpsData.Loc first = list.get(0);
            y = group(g, "g:" + gn, gn, first.icon, first.color, es, y, x0, rw, s, mx, my, top, top + viewH, textA);
        }
        y += 6f * s;
        y = label(g, "PERSONAL", y, x0, s, textA);
        y = group(g, "p:mine", "Markere personale", "pin", GpsTheme.GOLD, personal("mine"), y, x0, rw, s, mx, my, top, top + viewH, textA);
        y = group(g, "p:fav", "Favorite", "star", GpsTheme.GOLD, personal("fav"), y, x0, rw, s, mx, my, top, top + viewH, textA);
        y = group(g, "p:recent", "Destinații recente", "clock", GpsTheme.MUTED, personal("recent"), y, x0, rw, s, mx, my, top, top + viewH, textA);
        y = group(g, "p:saved", "Salvate", "flag", GpsTheme.SKY, personal("saved"), y, x0, rw, s, mx, my, top, top + viewH, textA);
        contentH = y + scroll - top + 8f * s;
        scroll = Math.max(0f, Math.min(Math.max(0f, contentH - viewH), scroll));
        Ui.endScissor();
        if (contentH > viewH) GpsUi.scrollbar(g, w - 5f, top, viewH, contentH, viewH, scroll);
    }

    private static String dimLabel(String dim) {
        if (dim.endsWith("the_nether")) return "Nether";
        if (dim.endsWith("the_end")) return "End";
        return "Overworld";
    }

    /** Trei butoane-iconita: marker aici, meniu complet, editor harta (admin). */
    private void quickRow(GuiGraphics g, float w, float y, float h, float s, int mx, int my) {
        String[][] it = GpsData.admin
            ? new String[][] {{"flag", "Marker nou aici"}, {"gear", "Meniu complet"}, {"pencil", "Editor hartă"}}
            : new String[][] {{"flag", "Marker nou aici"}, {"gear", "Meniu complet"}};
        Runnable[] act = {host::addMarkerHere, host::openDrawer, host::openEditor};
        boolean slim = w < 80f * s;
        if (slim) {
            // coloana ingusta: doar primele doua, una peste alta nu incap; ramane meniul complet
            float x = (w - h) / 2f;
            boolean hov = GpsUi.in(mx, my, x, y, h, h);
            float t = GpsUi.hot("sbq1", hov);
            Gfx.rrect(g, x, y, h, h, GpsTheme.R_SM, GpsTheme.alpha(GpsTheme.ORANGE, (int) (18 + 50 * t)));
            GpsUi.icon(g, "gear", x + h / 2f, y + h / 2f, 12f * s, GpsTheme.mix(GpsTheme.MUTED, GpsTheme.ORANGE, t));
            host.hitF(x, y, h, h, host::openDrawer);
            return;
        }
        float gap = 4f * s;
        float bw = (w - 16f * s - gap * (it.length - 1)) / it.length;
        for (int i = 0; i < it.length; i++) {
            float x = 8f * s + i * (bw + gap);
            boolean hov = GpsUi.in(mx, my, x, y, bw, h);
            float t = GpsUi.hot("sbq" + i, hov);
            Gfx.rrect(g, x, y, bw, h, GpsTheme.R_SM, GpsTheme.alpha(GpsTheme.ORANGE, (int) (16 + 54 * t)));
            GpsUi.icon(g, it[i][0], x + bw / 2f, y + h / 2f, 12f * s, GpsTheme.mix(GpsTheme.MUTED, GpsTheme.ORANGE, t));
            host.hitF(x, y, bw, h, act[i]);
            if (hov) host.tip(g, it[i][1], x, y - 14f * s);
        }
    }

    /** SETARI HARTA: [-] zoom [+] si comutatoarele Limita / Harta / Mini / GPS. */
    private void settings(GuiGraphics g, float w, float y, float s, int mx, int my, float a) {
        float x = 8f * s;
        float rw = w - 16f * s;
        Gfx.text(g, "SETĂRI HARTĂ", w / 2f, y + 7f * s, 6.4f * s, GpsTheme.fade(GpsTheme.FAINT, a), 1, true, 1.6f);
        y += 12f * s;
        float h = 20f * s;
        float bw = 22f * s;
        boolean hm = GpsUi.in(mx, my, x, y, bw, h);
        boolean hp = GpsUi.in(mx, my, x + rw - bw, y, bw, h);
        Gfx.rrect(g, x, y, rw, h, GpsTheme.R_SM, GpsTheme.alpha(0xFFFFFF, 14));
        Gfx.rrect(g, x, y, bw, h, GpsTheme.R_SM, GpsTheme.alpha(GpsTheme.ORANGE, (int) (30 + 60 * GpsUi.hot("sbzm", hm))));
        Gfx.rrect(g, x + rw - bw, y, bw, h, GpsTheme.R_SM, GpsTheme.alpha(GpsTheme.ORANGE, (int) (30 + 60 * GpsUi.hot("sbzp", hp))));
        GpsUi.icon(g, "minus", x + bw / 2f, y + h / 2f, 9f * s, GpsTheme.TEXT);
        GpsUi.icon(g, "plus", x + rw - bw / 2f, y + h / 2f, 9f * s, GpsTheme.TEXT);
        Gfx.text(g, String.format(Locale.ROOT, "%.2f", host.zoomLevel()), x + rw / 2f, y + h / 2f + 3f * s, 8.4f * s, GpsTheme.TEXT, 1, true, 0.2f);
        host.hitF(x, y, bw, h, () -> host.zoomStep(1f / 1.3f));
        host.hitF(x + rw - bw, y, bw, h, () -> host.zoomStep(1.3f));
        y += h + 4f * s;
        float cw = (rw - 4f * s) / 2f;
        toggle(g, "Limită", host.borderOn, x, y, cw, h, s, mx, my, () -> host.borderOn = !host.borderOn);
        toggle(g, "Harta", host.baseOn, x + cw + 4f * s, y, cw, h, s, mx, my, () -> host.baseOn = !host.baseOn);
        y += h + 4f * s;
        toggle(g, "Mini", MiniMapSettings.enabled, x, y, cw, h, s, mx, my, host::toggleMini);
        toggle(g, "GPS", GpsNav.active, x + cw + 4f * s, y, cw, h, s, mx, my, host::toggleGps);
    }

    private void toggle(GuiGraphics g, String label, boolean on, float x, float y, float w, float h, float s, int mx, int my, Runnable r) {
        boolean hov = GpsUi.in(mx, my, x, y, w, h);
        float t = GpsUi.hot("sbt" + label, hov);
        int base = on ? GpsTheme.alpha(GpsTheme.ORANGE, 70) : GpsTheme.alpha(0xFFFFFF, 14);
        Gfx.rrect(g, x, y, w, h, GpsTheme.R_SM, GpsTheme.mix(base, GpsTheme.alpha(GpsTheme.ORANGE, 110), t * 0.6f));
        Gfx.text(g, label + ": " + (on ? "ON" : "OFF"), x + w / 2f, y + h / 2f + 3f * s, 7.2f * s,
            on ? GpsTheme.AMBER : GpsTheme.MUTED, 1, true, 0.1f);
        host.hitF(x, y, w, h, r);
    }

    private void slimBody(GuiGraphics g, float w, float top, float s, int mx, int my) {
        String[][] items = {{"map", "g", "Locațiile serverului"}, {"star-line", "p", "Personal"}};
        float y = top;
        for (String[] it : items) {
            float sz = 30f * s;
            float x = (w - sz) / 2f;
            boolean hov = GpsUi.in(mx, my, x, y, sz, sz);
            float t = GpsUi.hot("sbs" + it[1], hov);
            Gfx.rrect(g, x, y, sz, sz, GpsTheme.R_SM, GpsTheme.alpha(GpsTheme.ORANGE, (int) (18 + 50 * t)));
            GpsUi.icon(g, it[0], x + sz / 2f, y + sz / 2f, 14f * s, GpsTheme.mix(GpsTheme.MUTED, GpsTheme.ORANGE, t));
            final String key = it[1];
            host.hitF(x, y, sz, sz, () -> {
                open = true;
                expanded = key.equals("p") ? "p:fav" : "";
            });
            y += sz + 6f * s;
        }
    }

    private float label(GuiGraphics g, String text, float y, float x, float s, float a) {
        Gfx.text(g, text, x + 6f * s, y + 14f * s, 7.2f * s, GpsTheme.fade(GpsTheme.ORANGE, a), 0, true, 2.2f);
        return y + 22f * s;
    }

    /** Un grup (rand de antet + intrarile lui cand e deschis). Intoarce noul y. */
    private float group(GuiGraphics g, String id, String title, String icon, int color, List<Entry> entries, float y, float x, float w,
                        float s, int mx, int my, float vTop, float vBot, float a) {
        float h = 30f * s;
        boolean isOpen = expanded.equals(id);
        boolean inView = y >= vTop - 1f && y + h <= vBot + 1f;
        boolean hov = inView && GpsUi.in(mx, my, x, y, w, h);
        float t = GpsUi.hot("sbg" + id, hov);
        Gfx.rrect(g, x, y, w, h - 2f * s, GpsTheme.R_SM, GpsTheme.alpha(isOpen ? GpsTheme.ORANGE : 0xFFFFFF, isOpen ? 34 : (int) (10 + 22 * t)));
        if (isOpen) Gfx.rrect(g, x, y + h * 0.2f, 2.6f * s, h * 0.5f, 1.3f, GpsTheme.ORANGE);
        GpsUi.badge(g, icon, x + 18f * s, y + h / 2f - s, 8.4f * s, color);
        Gfx.text(g, GpsScreen.clip(title.toUpperCase(Locale.ROOT), 8.2f * s, true, w - 78f * s), x + 32f * s, y + h / 2f + 2.4f * s, 8.2f * s,
            GpsTheme.fade(isOpen ? GpsTheme.TEXT : GpsTheme.mix(0xFFD7DCE6, GpsTheme.TEXT, t), a), 0, true, 0.5f);
        Gfx.text(g, String.valueOf(entries.size()), x + w - 24f * s, y + h / 2f + 2.4f * s, 7.6f * s, GpsTheme.fade(GpsTheme.MUTED, a), 2, true, 0f);
        GpsUi.icon(g, isOpen ? "chev-down" : "chev-right", x + w - 12f * s, y + h / 2f - s, 8f * s, GpsTheme.MUTED);
        if (inView) host.hitF(x, y, w, h, () -> {
            expanded = isOpen ? "" : id;
            scroll = Math.min(scroll, 1e6f);
        });
        y += h;
        if (!isOpen) return y;
        if (entries.isEmpty()) {
            Gfx.text(g, emptyText(id), x + 14f * s, y + 14f * s, 7.6f * s, GpsTheme.fade(GpsTheme.FAINT, a), 0, true, 0f);
            return y + 24f * s;
        }
        float eh = 28f * s;
        for (Entry e : entries) {
            boolean vis = y >= vTop - 1f && y + eh <= vBot + 1f;
            boolean eh2 = vis && GpsUi.in(mx, my, x + 6f * s, y, w - 6f * s, eh);
            float et = GpsUi.hot("sbe" + e.key, eh2);
            boolean sel = host.isSelected(e);
            boolean tgt = host.isTarget(e);
            if (sel) Gfx.rrect(g, x + 6f * s, y, w - 6f * s, eh - 2f * s, GpsTheme.R_SM, GpsTheme.alpha(GpsTheme.ORANGE, 46));
            else if (et > 0.01f) Gfx.rrect(g, x + 6f * s, y, w - 6f * s, eh - 2f * s, GpsTheme.R_SM, GpsTheme.alpha(0xFFFFFF, (int) (20 * et)));
            GpsUi.badge(g, e.icon, x + 22f * s, y + eh / 2f - s, 7.2f * s, e.color);
            float rightPad = eh2 ? 34f * s : 8f * s;
            String sub = tgt ? "● GPS" : e.sub;
            float subW = sub.isEmpty() ? 0f : Gfx.width(sub, 7f * s, true) + 6f * s;
            Gfx.text(g, GpsScreen.clip(e.name, 8f * s, true, w - 42f * s - Math.max(subW, eh2 ? 26f * s : 0f) - rightPad + 8f * s),
                x + 36f * s, y + eh / 2f + 2.4f * s, 8f * s, GpsTheme.fade(sel ? GpsTheme.TEXT : 0xFFD7DCE6, a), 0, true, 0f);
            if (!eh2 && !sub.isEmpty()) {
                Gfx.text(g, sub, x + w - 6f * s, y + eh / 2f + 2.4f * s, 7f * s, GpsTheme.fade(tgt ? GpsTheme.ORANGE : GpsTheme.FAINT, a), 2, true, 0f);
            }
            if (vis) {
                rects.add(new float[] {x + 6f * s, y, w - 6f * s, eh});
                rectEntries.add(e);
                final Entry fe = e;
                host.hitF(x + 6f * s, y, w - 6f * s, eh, () -> host.pick(fe));
                if (eh2) {
                    float bs = 20f * s;
                    float bx = x + w - bs - 6f * s;
                    float by = y + (eh - 2f * s - bs) / 2f;
                    boolean hb = GpsUi.in(mx, my, bx, by, bs, bs);
                    Gfx.rrect(g, bx, by, bs, bs, GpsTheme.R_SM, hb ? GpsTheme.ORANGE : GpsTheme.alpha(GpsTheme.ORANGE, 90));
                    GpsUi.icon(g, tgt ? "close" : "nav", bx + bs / 2f, by + bs / 2f, 10f * s, 0xFFFFFFFF);
                    host.hitF(bx, by, bs, bs, () -> host.navEntry(fe));
                }
            }
            y += eh;
        }
        return y + 4f * s;
    }

    private static String emptyText(String id) {
        switch (id) {
            case "p:mine":
                return "Niciun marker personal";
            case "p:fav":
                return "Nicio favorită (tasta F)";
            case "p:recent":
                return "Nicio destinație recentă";
            case "p:saved":
                return "Nimic salvat";
            default:
                return "Nicio locație";
        }
    }
}
