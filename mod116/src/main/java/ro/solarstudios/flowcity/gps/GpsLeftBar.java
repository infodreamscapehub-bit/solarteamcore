package ro.solarstudios.flowcity.gps;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Bara ingusta din stanga a hartii mari (stil harta de roleplay): sectiunile pliabile SERVER (categorii cu bifa de vizibilitate)
 * si PERSONAL (Roata, Casa mea, Destinatie, waypoint-urile mele) si, jos, SETARI HARTA (zoom -/valoare/+, Limita, Minimap, Zone).
 * Foloseste datele existente: GpsData (categorii/locatii), GpsWps (waypoint-uri, serverul valideaza), GpsNav, MiniMapSettings.
 */
final class GpsLeftBar {
    private static final float ROW = 17f;
    private static final int CYAN = 0xFF22D3EE;
    private static final int BG = 0xF4060D17;
    private static final int EDGE = 0xFF1E2F46;

    private final GpsScreen s;
    private boolean open = true;
    private float anim = 1f;
    private boolean srvOpen = true;
    private boolean perOpen = true;
    private int scroll;
    private float contentH;
    private float w = 160f;
    private float listTop;
    private float listBottom;

    GpsLeftBar(GpsScreen s) {
        this.s = s;
    }

    /** Latimea ocupata acum (cu animatia de inchidere/deschidere). */
    float width() {
        return w * anim;
    }

    boolean over(double mx, double my) {
        return anim > 0.04f && mx < w * anim;
    }

    void scrollBy(int d) {
        scroll = Math.max(0, scroll + d * 22);
    }

    void toggle() {
        open = !open;
    }

    private static boolean in(int mx, int my, float x, float y, float ww, float hh) {
        return mx >= x && mx < x + ww && my >= y && my < y + hh;
    }

    void draw(GuiGraphics g, int mx, int my, float dt, int height) {
        anim += ((open ? 1f : 0f) - anim) * Math.min(1f, dt * 10f);
        w = Math.max(138f, Math.min(172f, s.screenW() * 0.17f));
        float x0 = -(1f - anim) * w;
        // buton de plierea barei (lipit de margine)
        float tx = x0 + w;
        boolean hvT = in(mx, my, tx, height / 2f - 20f, 14f, 40f);
        GpsScreen.box(g, tx - 1f, height / 2f - 20f, 15f, 40f, 6f, hvT ? 0xF0223656 : 0xE80B1424, hvT ? Sk.ACC : EDGE);
        float ax = tx + 7f;
        float ay = height / 2f;
        float dir = open ? -1f : 1f;
        Gfx.line(g, ax - 2f * dir, ay - 5f, ax + 2f * dir, ay, 1.8f, Sk.WHITE);
        Gfx.line(g, ax + 2f * dir, ay, ax - 2f * dir, ay + 5f, 1.8f, Sk.WHITE);
        s.hitF(tx, height / 2f - 20f, 14f, 40f, this::toggle);
        if (anim < 0.03f) return;

        Minecraft mc = Minecraft.getInstance();
        Gfx.rrect(g, x0, 0, w, height, 0, BG);
        Gfx.rrect(g, x0 + w - 1f, 0, 1f, height, 0, EDGE);
        s.hitF(x0, 0, w, height, () -> {});

        // titlu
        Gfx.rrect(g, x0 + 10, 11, 3f, 16f, 1.5f, CYAN);
        Gfx.text(g, "FLOWMAP", x0 + 19, 24, 11f, Sk.WHITE, 0, true, 1.2f);
        Gfx.rrect(g, x0 + 8, 34f, w - 16f, 1f, 0, EDGE);

        float settingsH = 84f;
        listTop = 40f;
        listBottom = height - settingsH - 8f;
        float y = listTop - scroll;
        float rw = w - 14f;
        float rx = x0 + 7f;

        // ---------------------------------------------------------- SERVER
        y = section(g, "SERVER", srvOpen, rx, y, rw, mx, my, () -> srvOpen = !srvOpen);
        if (srvOpen) {
            String dim = GpsData.dimOf(mc);
            int total = 0;
            for (GpsData.Loc l : GpsData.all()) if (l.dim.equals(dim) && !l.hidden) total++;
            final int tot = total;
            y = row(g, "Toate locațiile", String.valueOf(tot), 0xFF8FB4E8, -1, rx, y, rw, mx, my, () -> s.openList("TOATE"), null);
            for (GpsData.Cat c : GpsData.cats) {
                if (!c.enabled) continue;
                int n = 0;
                for (GpsData.Loc l : GpsData.all()) if (l.cat.equals(c.id) && l.dim.equals(dim) && !l.hidden) n++;
                if (n == 0) continue;
                final GpsData.Cat cat = c;
                boolean vis = !MiniMapSettings.hiddenCats.contains(c.id);
                y = row(g, c.name, String.valueOf(n), c.color, vis ? 1 : 0, rx, y, rw, mx, my,
                    () -> s.openList(cat.group.isEmpty() ? "TOATE" : cat.group),
                    () -> {
                        if (MiniMapSettings.hiddenCats.contains(cat.id)) MiniMapSettings.hiddenCats.remove(cat.id);
                        else MiniMapSettings.hiddenCats.add(cat.id);
                        MiniMapSettings.save();
                        s.markDirty();
                    });
            }
        }

        // ---------------------------------------------------------- PERSONAL
        y = section(g, "PERSONAL", perOpen, rx, y + 4f, rw, mx, my, () -> perOpen = !perOpen);
        if (perOpen) {
            GpsData.Loc wheel = null;
            for (GpsData.Loc l : GpsData.all()) {
                if (l.key != null && l.key.contains("roata") && l.dim.equals(GpsData.dimOf(mc))) {
                    wheel = l;
                    break;
                }
            }
            if (wheel != null) {
                final GpsData.Loc wl = wheel;
                y = row(g, "ROATA", "", wl.color, -1, rx, y, rw, mx, my, () -> goTo(wl.x, wl.z, wl.name, wl.id), null);
            }
            GpsWps.Wp home = null;
            for (GpsWps.Wp wp : GpsWps.mine) {
                if (wp.name.equalsIgnoreCase("Casa") || wp.name.equalsIgnoreCase("Casa mea")) {
                    home = wp;
                    break;
                }
            }
            if (home != null) {
                final GpsWps.Wp hw = home;
                y = row(g, "CASA MEA", "", 0xFFFFC857, -1, rx, y, rw, mx, my, () -> goTo(hw.x, hw.z, hw.name, "wp:" + hw.id), null);
            } else {
                y = row(g, "CASA MEA: setează aici", "", 0xFFFFC857, -1, rx, y, rw, mx, my, () -> addWp("Casa"), null);
            }
            String dest = GpsNav.active ? GpsScreen.clip(GpsNav.name, 7f, true, 62f) : "-";
            y = row(g, "DESTINAȚIE", dest, GpsNav.active ? GpsNav.color : 0xFF5D6B80, -1, rx, y, rw, mx, my,
                () -> {
                    if (GpsNav.active) s.showAt(GpsNav.x, GpsNav.z);
                }, null);
            for (GpsWps.Wp wp : GpsWps.shared) {
                final GpsWps.Wp ww = wp;
                y = row(g, wp.name, "*", wp.color, -1, rx, y, rw, mx, my, () -> goTo(ww.x, ww.z, ww.name, "wp:" + ww.id), null);
            }
            for (GpsWps.Wp wp : GpsWps.mine) {
                if (wp == home) continue;
                final GpsWps.Wp ww = wp;
                y = row(g, wp.name, "x", wp.color, -1, rx, y, rw, mx, my, () -> goTo(ww.x, ww.z, ww.name, "wp:" + ww.id), () -> GpsWps.delete(ww));
            }
            y = row(g, "+ Waypoint aici", "", CYAN, -1, rx, y, rw, mx, my, () -> addWp("Waypoint " + (GpsWps.mine.size() + 1)), null);
        }
        contentH = y + scroll - listTop;
        float vis = listBottom - listTop;
        int maxScroll = (int) Math.max(0f, contentH - vis + 6f);
        if (scroll > maxScroll) scroll = maxScroll;
        if (contentH > vis) {
            float bh = Math.max(20f, vis * vis / contentH);
            float by = listTop + (vis - bh) * (maxScroll == 0 ? 0f : scroll / (float) maxScroll);
            Gfx.rrect(g, x0 + w - 4f, by, 2.2f, bh, 1.1f, 0xFF2A4566);
        }
        // mascheaza randurile care ies din lista (sus/jos)
        Gfx.rrect(g, x0, 36f, w - 1f, listTop - 36f, 0, BG);
        Gfx.rrect(g, x0, listBottom, w - 1f, height - listBottom, 0, BG);

        // ---------------------------------------------------------- SETARI HARTA
        float sy = height - settingsH;
        Gfx.rrect(g, x0 + 8, sy - 4f, w - 16f, 1f, 0, EDGE);
        Gfx.text(g, "SETĂRI HARTĂ", x0 + 12, sy + 9f, 6.6f, Sk.ACC, 0, true, 1.4f);
        float zy = sy + 15f;
        float bw = 22f;
        miniBtn(g, "-", x0 + 10, zy, bw, 20f, mx, my, () -> s.zoomStep(1f / 1.25f));
        String zv = String.format(Locale.ROOT, "%.2f", s.zoomValue());
        GpsScreen.box(g, x0 + 10 + bw + 4f, zy, w - 20f - 2f * bw - 8f, 20f, 6f, 0xE80B1424, EDGE);
        Gfx.text(g, zv, x0 + 10 + bw + 4f + (w - 20f - 2f * bw - 8f) / 2f, zy + 13.5f, 8.6f, Sk.WHITE, 1, true, 0f);
        miniBtn(g, "+", x0 + w - 10 - bw, zy, bw, 20f, mx, my, () -> s.zoomStep(1.25f));
        float ty = zy + 25f;
        float tw = (w - 20f - 8f) / 3f;
        toggleBtn(g, "Limită", s.limitOn(), x0 + 10, ty, tw, mx, my, s::toggleLimit);
        toggleBtn(g, "Minimap", MiniMapSettings.enabled, x0 + 10 + tw + 4f, ty, tw, mx, my, () -> {
            MiniMapSettings.enabled = !MiniMapSettings.enabled;
            MiniMapSettings.save();
        });
        toggleBtn(g, "Zone", s.zonesOn(), x0 + 10 + 2f * (tw + 4f), ty, tw, mx, my, s::toggleZones);
    }

    // ------------------------------------------------------------------ pasi

    private void goTo(double x, double z, String name, String id) {
        s.showAt(x, z);
        GpsNav.set(id, name, x, z, GpsData.dimOf(Minecraft.getInstance()), CYAN);
    }

    private void addWp(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (GpsWps.mine.size() >= GpsWps.max) {
            s.say("Ai atins limita de waypoint-uri.");
            return;
        }
        GpsWps.save("", name, Math.round(mc.player.getX()), Math.round(mc.player.getZ()), GpsData.dimOf(mc), CYAN, false);
        s.say("Waypoint salvat: " + name);
    }

    // ------------------------------------------------------------------ desen

    /** Antet de sectiune pliabil cu sageata. Intoarce y-ul urmator. */
    private float section(GuiGraphics g, String title, boolean opened, float x, float y, float rw, int mx, int my, Runnable r) {
        boolean vis = y >= listTop - 1f && y + ROW <= listBottom;
        if (vis) {
            boolean hv = in(mx, my, x, y, rw, ROW);
            Gfx.rrect(g, x, y, rw, ROW, 5f, hv ? 0xFF14233A : 0xFF0D1828);
            float cx = x + 9f;
            float cy = y + ROW / 2f;
            if (opened) Gfx.poly(g, new float[] {cx - 3.2f, cy - 1.6f, cx + 3.2f, cy - 1.6f, cx, cy + 2.4f}, CYAN);
            else Gfx.poly(g, new float[] {cx - 1.6f, cy - 3.2f, cx - 1.6f, cy + 3.2f, cx + 2.4f, cy}, CYAN);
            Gfx.text(g, title, x + 19f, y + 12f, 7.2f, Sk.WHITE, 0, true, 1.4f);
            s.hitF(x, y, rw, ROW, r);
        }
        return y + ROW + 2f;
    }

    /**
     * Rand: punct colorat (sau bifa cand check >= 0), nume taiat, valoare/actiune la dreapta.
     * check: -1 fara bifa, 0 nebifat, 1 bifat; chk = actiunea bifei (sau, pentru valoarea "x", actiunea de stergere).
     */
    private float row(GuiGraphics g, String name, String val, int color, int check, float x, float y, float rw, int mx, int my, Runnable click, Runnable chk) {
        boolean vis = y >= listTop - 1f && y + ROW <= listBottom;
        float next = y + ROW;
        if (!vis) return next;
        boolean hv = in(mx, my, x, y, rw, ROW - 1f);
        if (hv) Gfx.rrect(g, x, y, rw, ROW - 1f, 5f, 0xFF14233A);
        float tx = x + 7f;
        if (check >= 0) {
            boolean hvC = in(mx, my, x + 2f, y + 2f, 14f, ROW - 5f);
            Gfx.rrect(g, x + 4f, y + 3.5f, 10f, 10f, 3f, check == 1 ? color : (hvC ? 0xFF2A4566 : 0xFF1B2E47));
            if (check == 1) {
                Gfx.line(g, x + 6.2f, y + 8.6f, x + 8.4f, y + 10.8f, 1.5f, 0xFF061426);
                Gfx.line(g, x + 8.4f, y + 10.8f, x + 12f, y + 6.2f, 1.5f, 0xFF061426);
            }
            tx = x + 20f;
        } else {
            Gfx.disc(g, x + 9f, y + ROW / 2f - 0.5f, 3.2f, color);
            tx = x + 18f;
        }
        boolean del = "x".equals(val);
        float vw = val.isEmpty() ? 0f : Gfx.width(val, 7f, true) + 8f;
        Gfx.text(g, GpsScreen.clip(name, 7.8f, true, rw - (tx - x) - vw - 4f), tx, y + 11.8f, 7.8f, hv ? Sk.WHITE : 0xFFC9D4E4, 0, true, 0f);
        if (!val.isEmpty()) {
            if (del) {
                boolean hvD = in(mx, my, x + rw - 16f, y, 16f, ROW - 1f);
                Gfx.text(g, val, x + rw - 8f, y + 12.2f, 9f, hvD ? Sk.BAD : Sk.MUTED, 1, true, 0f);
            } else {
                Gfx.text(g, val, x + rw - 5f, y + 11.8f, 7f, Sk.MUTED, 2, true, 0f);
            }
        }
        s.hitF(x, y, rw, ROW - 1f, click);
        if (check >= 0 && chk != null) s.hitF(x + 2f, y + 2f, 14f, ROW - 5f, chk);
        if (del && chk != null) s.hitF(x + rw - 16f, y, 16f, ROW - 1f, chk);
        return next;
    }

    private void miniBtn(GuiGraphics g, String label, float x, float y, float ww, float hh, int mx, int my, Runnable r) {
        boolean hv = in(mx, my, x, y, ww, hh);
        GpsScreen.box(g, x, y, ww, hh, 6f, hv ? 0xFF223656 : 0xE80B1424, hv ? Sk.ACC : EDGE);
        Gfx.text(g, label, x + ww / 2f, y + hh / 2f + 3.6f, 10f, Sk.WHITE, 1, true, 0f);
        s.hitF(x, y, ww, hh, r);
    }

    private void toggleBtn(GuiGraphics g, String label, boolean on, float x, float y, float ww, int mx, int my, Runnable r) {
        boolean hv = in(mx, my, x, y, ww, 30f);
        GpsScreen.box(g, x, y, ww, 30f, 6f, hv ? 0xFF14233A : 0xE80B1424, on ? CYAN : EDGE);
        Gfx.text(g, label, x + ww / 2f, y + 12f, 6.4f, Sk.WHITE, 1, true, 0f);
        Gfx.text(g, on ? "ON" : "OFF", x + ww / 2f, y + 23.5f, 7f, on ? CYAN : Sk.MUTED, 1, true, 0.4f);
        s.hitF(x, y, ww, 30f, r);
    }
}
