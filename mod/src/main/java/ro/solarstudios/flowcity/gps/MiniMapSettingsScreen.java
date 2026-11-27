package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudEditorScreen;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Meniul grafic de setari al minimap-ului (stil iarna FlowCity): comutatoare, forma, glisoare, pozitie pe ecran,
 * ascundere in interfete si categorii, cu previzualizare reala (acelasi desen ca pe HUD). Se deschide din butonul "MINIMAP"
 * din harta GPS sau cu tasta "Setari minimap" din Controls. Setarile se salveaza local, pentru fiecare jucator.
 */
public final class MiniMapSettingsScreen extends FlowScreen {
    private static final float W = 560f;
    private static final float H = 436f;
    private static final int VIOLET = 0xFF8B7BFF;
    private static final int OFF = 0xFF2A3A52;
    private static final int CATS_PER_PAGE = 6;

    private float scale = 1f;
    private float offX;
    private float offY;
    private int dragId = -1;
    private final float[] slX = new float[3];
    private final float[] slY = new float[3];
    private final float[] slW = new float[3];
    private int catPage;

    public MiniMapSettingsScreen() {
        super("Minimap", 100, 100);
        MiniMapSettings.load();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public void removed() {
        MiniMapSettings.save();
        super.removed();
    }

    // ------------------------------------------------------------------ componente

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static boolean hov(float lx, float ly, float x, float y, float w, float h) {
        return lx >= x && lx < x + w && ly >= y && ly < y + h;
    }

    private void section(GuiGraphics g, String label, float x, float y) {
        Gfx.rrect(g, x, y, 2.5f, 9f, 0f, VIOLET);
        Gfx.text(g, label, x + 8f, y + 8f, 6.4f, Sk.MUTED, 0, true, 1.4f);
    }

    private void toggle(GuiGraphics g, String label, boolean on, float x, float y, float w, float lx, float ly, Runnable flip) {
        if (hov(lx, ly, x - 4f, y, w + 8f, 20f)) Gfx.rrect(g, x - 4f, y, w + 8f, 19f, 4f, 0x22FFFFFF);
        Gfx.text(g, label, x, y + 13f, 8.2f, on ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
        float sx = x + w - 30f;
        Gfx.rrect(g, sx, y + 3f, 28f, 13f, 6.5f, on ? Sk.ACC : OFF);
        Gfx.disc(g, on ? sx + 21f : sx + 7f, y + 9.5f, 4.6f, 0xFFFFFFFF);
        hitL(x - 4f, y, w + 8f, 19f, flip);
    }

    private void button(GuiGraphics g, String label, float x, float y, float w, float h, boolean active, float lx, float ly, Runnable r) {
        boolean hover = hov(lx, ly, x, y, w, h);
        int bg = active ? Sk.ACC : (hover ? 0xFF22344F : 0xFF16233A);
        Sk.cut(g, x, y, w, h, 5f, bg);
        Gfx.text(g, label, x + w / 2f, y + h / 2f + 3f, 7.6f, active ? 0xFF061426 : Sk.WHITE, 1, true, 0.2f);
        hitL(x, y, w, h, r);
    }

    private void slider(GuiGraphics g, int id, String label, String value, float frac, float x, float y, float w) {
        slX[id] = x;
        slY[id] = y + 15f;
        slW[id] = w;
        Gfx.text(g, label, x, y + 8f, 8.2f, Sk.WHITE, 0, true, 0f);
        Gfx.text(g, value, x + w, y + 8f, 8.2f, Sk.ACC, 2, true, 0f);
        Gfx.rrect(g, x, y + 13f, w, 4f, 2f, 0xFF22324A);
        Gfx.rrect(g, x, y + 13f, Math.max(4f, w * frac), 4f, 2f, Sk.ACC);
        Gfx.disc(g, x + w * frac, y + 15f, 5.8f, 0xFF061426);
        Gfx.disc(g, x + w * frac, y + 15f, 4.4f, 0xFFFFFFFF);
    }

    private static String clip(String s, float size, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, true) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "..", size, true) > maxW) t = t.substring(0, t.length() - 1);
        return t + "..";
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        scale = Math.max(0.5f, Math.min(1.8f, Math.min((width - 12f) / W, (height - 12f) / H)));
        offX = (width - W * scale) / 2f;
        offY = (height - H * scale) / 2f;
        g.fill(0, 0, width, height, 0xB0060A14);
        float lx = (mx - offX) / scale;
        float ly = (my - offY) / scale;
        g.pose().pushPose();
        g.pose().translate(offX, offY, 0f);
        g.pose().scale(scale, scale, 1f);
        try {
            Sk.cut(g, 0f, 0f, W, H, 14f, 0xF20B1626);
            Gfx.rrect(g, 0f, 0f, W - 14f, 3f, 0f, Sk.ACC);
            Gfx.text(g, "FLOWCITY › GPS › MINIMAP", 20f, 20f, 6.4f, Sk.MUTED, 0, true, 1.4f);
            Gfx.text(g, "Setări minimap", 20f, 38f, 15f, Sk.WHITE, 0, true, 0f);
            button(g, "RESETEAZĂ", W - 206f, 16f, 92f, 22f, false, lx, ly, this::reset);
            button(g, "ÎNCHIDE", W - 106f, 16f, 86f, 22f, true, lx, ly, this::onClose);
            left(g, lx, ly);
            right(g, lx, ly);
            categories(g, lx, ly);
        } finally {
            g.pose().popPose();
        }
    }

    private void left(GuiGraphics g, float lx, float ly) {
        float x = 20f;
        float w = 250f;
        section(g, "AFIȘARE", x, 52f);
        float y = 64f;
        toggle(g, "Minimap activ", MiniMapSettings.enabled, x, y, w, lx, ly, () -> {
            MiniMapSettings.enabled = !MiniMapSettings.enabled;
            MiniMapSettings.tempHidden = false;
        });
        y += 21f;
        toggle(g, "Rotire după jucător (oprit = North Lock)", MiniMapSettings.rotate, x, y, w, lx, ly, () -> MiniMapSettings.rotate = !MiniMapSettings.rotate);
        y += 21f;
        toggle(g, "Coordonate X / Z", MiniMapSettings.showCoords, x, y, w, lx, ly, () -> MiniMapSettings.showCoords = !MiniMapSettings.showCoords);
        y += 21f;
        toggle(g, "Busolă N / E / S / V", MiniMapSettings.showCompass, x, y, w, lx, ly, () -> MiniMapSettings.showCompass = !MiniMapSettings.showCompass);
        y += 21f;
        toggle(g, "Locații pe hartă", MiniMapSettings.showLocations, x, y, w, lx, ly, () -> MiniMapSettings.showLocations = !MiniMapSettings.showLocations);
        y += 21f;
        toggle(g, "Zone RP (legal / ilegal)", MiniMapSettings.showZones, x, y, w, lx, ly, () -> MiniMapSettings.showZones = !MiniMapSettings.showZones);
        y += 21f;
        toggle(g, "Destinație GPS (waypoint)", MiniMapSettings.showWaypoint, x, y, w, lx, ly, () -> MiniMapSettings.showWaypoint = !MiniMapSettings.showWaypoint);

        section(g, "FORMĂ", x, 220f);
        button(g, "PĂTRAT", x, 232f, 120f, 20f, !MiniMapSettings.circular, lx, ly, () -> MiniMapSettings.circular = false);
        button(g, "CIRCULAR", x + 130f, 232f, 120f, 20f, MiniMapSettings.circular, lx, ly, () -> MiniMapSettings.circular = true);

        section(g, "DIMENSIUNE ȘI ASPECT", x, 262f);
        float zf = (float) (Math.log(MiniMapSettings.zoom / MiniMapSettings.ZOOM_MIN) / Math.log(MiniMapSettings.ZOOM_MAX / MiniMapSettings.ZOOM_MIN));
        slider(g, 0, "Dimensiune", MiniMapSettings.size + " px", (MiniMapSettings.size - MiniMapSettings.SIZE_MIN) / (float) (MiniMapSettings.SIZE_MAX - MiniMapSettings.SIZE_MIN), x, 274f, w);
        slider(g, 1, "Zoom", "x" + String.format(java.util.Locale.ROOT, "%.1f", MiniMapSettings.zoom), zf, x, 300f, w);
        slider(g, 2, "Opacitate", Math.round(MiniMapSettings.opacity * 100f) + " %", (MiniMapSettings.opacity - MiniMapSettings.OPACITY_MIN) / (1f - MiniMapSettings.OPACITY_MIN), x, 326f, w);
        toggle(g, "Mod performanță redusă", MiniMapSettings.lowPerf, x, 356f, w, lx, ly, () -> MiniMapSettings.lowPerf = !MiniMapSettings.lowPerf);
    }

    private void right(GuiGraphics g, float lx, float ly) {
        float x = 290f;
        float w = 250f;
        section(g, "PREVIZUALIZARE", x, 52f);
        Sk.cut(g, x, 64f, w, 160f, 8f, 0x88081018);
        float s = MiniMapSettings.size;
        float ps = Math.min(1f, 140f / Math.max(s, MiniMapRenderer.heightFor(s)));
        float pw = s * ps;
        float phh = MiniMapRenderer.heightFor(s) * ps;
        g.pose().pushPose();
        g.pose().translate(x + (w - pw) / 2f, 64f + (160f - phh) / 2f, 0f);
        g.pose().scale(ps, ps, 1f);
        try {
            MiniMapRenderer.render(g, 0f, 0f, s);
        } finally {
            g.pose().popPose();
        }

        section(g, "POZIȚIE PE ECRAN", x, 234f);
        button(g, "DREAPTA SUS", x, 246f, 121f, 20f, false, lx, ly, () -> preset(0));
        button(g, "STÂNGA SUS", x + 129f, 246f, 121f, 20f, false, lx, ly, () -> preset(1));
        button(g, "DREAPTA JOS", x, 270f, 121f, 20f, false, lx, ly, () -> preset(2));
        button(g, "STÂNGA JOS", x + 129f, 270f, 121f, 20f, false, lx, ly, () -> preset(3));
        button(g, "MUTĂ CU MOUSE-UL (EDITOR HUD)", x, 294f, w, 20f, false, lx, ly, () -> Minecraft.getInstance().setScreen(new HudEditorScreen()));

        section(g, "ASCUNDE ÎN INTERFEȚE", x, 324f);
        button(g, "INVENTAR", x, 336f, 80f, 20f, MiniMapSettings.hideInventory, lx, ly, () -> MiniMapSettings.hideInventory = !MiniMapSettings.hideInventory);
        button(g, "CHAT", x + 85f, 336f, 80f, 20f, MiniMapSettings.hideChat, lx, ly, () -> MiniMapSettings.hideChat = !MiniMapSettings.hideChat);
        button(g, "ALTE ECRANE", x + 170f, 336f, 80f, 20f, MiniMapSettings.hideOther, lx, ly, () -> MiniMapSettings.hideOther = !MiniMapSettings.hideOther);
        Gfx.text(g, "Albastru = se ascunde când interfața e deschisă.", x, 370f, 6.4f, Sk.FAINT, 0, true, 0f);
    }

    private void categories(GuiGraphics g, float lx, float ly) {
        float x = 20f;
        float y = 384f;
        section(g, "CATEGORII AFIȘATE PE MINIMAP", x, y);
        List<GpsData.Cat> cats = new ArrayList<>(GpsData.cats.size());
        for (GpsData.Cat c : GpsData.cats) if (c.enabled) cats.add(c);
        if (cats.isEmpty()) {
            Gfx.text(g, "Categoriile se încarcă de pe server (deschide o dată harta GPS).", x, y + 28f, 7.6f, Sk.MUTED, 0, true, 0f);
        } else {
            int pages = Math.max(1, (cats.size() + CATS_PER_PAGE - 1) / CATS_PER_PAGE);
            catPage = Math.max(0, Math.min(catPage, pages - 1));
            float cw = 78f;
            float cx = x + 22f;
            for (int i = 0; i < CATS_PER_PAGE; i++) {
                int idx = catPage * CATS_PER_PAGE + i;
                if (idx >= cats.size()) break;
                GpsData.Cat c = cats.get(idx);
                boolean shown = !MiniMapSettings.hiddenCats.contains(c.id);
                float bx = cx + i * (cw + 5f);
                boolean hover = hov(lx, ly, bx, y + 12f, cw, 20f);
                Sk.cut(g, bx, y + 12f, cw, 20f, 5f, shown ? 0xFF1B2D48 : (hover ? 0xFF1A2434 : 0xFF111A28));
                Gfx.rrect(g, bx, y + 12f, 3f, 20f, 0f, shown ? c.color : Sk.FAINT);
                Gfx.text(g, clip(c.name, 7.2f, cw - 12f), bx + 8f, y + 25f, 7.2f, shown ? Sk.WHITE : Sk.FAINT, 0, true, 0f);
                final String id = c.id;
                hitL(bx, y + 12f, cw, 20f, () -> {
                    if (!MiniMapSettings.hiddenCats.remove(id)) MiniMapSettings.hiddenCats.add(id);
                });
            }
            final int fp = pages;
            button(g, "‹", x, y + 12f, 18f, 20f, false, lx, ly, () -> catPage = (catPage + fp - 1) % fp);
            button(g, "›", W - 38f, y + 12f, 18f, 20f, false, lx, ly, () -> catPage = (catPage + 1) % fp);
            Gfx.text(g, (catPage + 1) + " / " + pages, W - 48f, y + 8f, 6.4f, Sk.MUTED, 2, true, 0f);
        }
        Gfx.text(g, "Dacă mai vezi o hartă mică în stânga-sus, vine de la alt mod: oprește-o din setările acelui mod.", x, 428f, 6.4f, Sk.FAINT, 0, true, 0f);
    }

    // ------------------------------------------------------------------ actiuni

    private void reset() {
        MiniMapSettings.enabled = true;
        MiniMapSettings.circular = false;
        MiniMapSettings.rotate = true;
        MiniMapSettings.showCoords = true;
        MiniMapSettings.showCompass = true;
        MiniMapSettings.showLocations = true;
        MiniMapSettings.showZones = false;
        MiniMapSettings.showWaypoint = true;
        MiniMapSettings.hideInventory = true;
        MiniMapSettings.hideChat = false;
        MiniMapSettings.hideOther = true;
        MiniMapSettings.lowPerf = false;
        MiniMapSettings.size = 112;
        MiniMapSettings.opacity = 0.96f;
        MiniMapSettings.resetZoom();
        MiniMapSettings.hiddenCats.clear();
        MiniMapSettings.tempHidden = false;
        preset(0);
        MiniMapSettings.save();
    }

    /** Pozitia pe ecran: 0 dreapta-sus (implicit, sub HUD), 1 stanga-sus, 2 dreapta-jos, 3 stanga-jos. */
    private void preset(int which) {
        HudLayout.El e = HudLayout.get("minimap");
        if (e == null) return;
        float[] d = MiniMapRenderer.defaults(width, height);
        float w = d[2];
        float h = d[3];
        float tx = d[0];
        float ty = d[1];
        if (which == 1) {
            tx = 8f;
            ty = 8f;
        } else if (which == 2) {
            tx = width - w - 8f;
            ty = height - h - 8f;
        } else if (which == 3) {
            tx = 8f;
            ty = height - h - 64f;
        }
        e.scale = 1f;
        e.visible = true;
        e.dx = tx - d[0];
        e.dy = ty - d[1];
        HudLayout.save();
    }

    // ------------------------------------------------------------------ glisoare

    private void applySlider(int id, float lx) {
        float f = Math.max(0f, Math.min(1f, (lx - slX[id]) / Math.max(1f, slW[id])));
        if (id == 0) {
            int v = MiniMapSettings.SIZE_MIN + Math.round(f * (MiniMapSettings.SIZE_MAX - MiniMapSettings.SIZE_MIN) / 4f) * 4;
            MiniMapSettings.size = v;
        } else if (id == 1) {
            MiniMapSettings.zoom = (float) (MiniMapSettings.ZOOM_MIN * Math.exp(f * Math.log(MiniMapSettings.ZOOM_MAX / MiniMapSettings.ZOOM_MIN)));
        } else {
            MiniMapSettings.opacity = MiniMapSettings.OPACITY_MIN + f * (1f - MiniMapSettings.OPACITY_MIN);
        }
        MiniMapSettings.validate();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            float lx = (float) ((mx - offX) / scale);
            float ly = (float) ((my - offY) / scale);
            for (int i = 0; i < 3; i++) {
                if (slW[i] > 0f && lx >= slX[i] - 8f && lx <= slX[i] + slW[i] + 8f && ly >= slY[i] - 9f && ly <= slY[i] + 9f) {
                    dragId = i;
                    applySlider(i, lx);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragId >= 0 && button == 0) {
            applySlider(dragId, (float) ((mx - offX) / scale));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragId >= 0 && button == 0) {
            dragId = -1;
            MiniMapSettings.save();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }
}
