package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.IntArrayNBT;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3f;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyUi;

/**
 * Dealerul auto (showroom): categorii, catalogul din plugin ca niste carduri, masina in 3D pe un podium luminat in
 * culoarea aleasa (se roteste, o poti trage cu mouse-ul), vopsea cu cercuri, pretul mare si butonul de cumparare.
 * Masina cumparata ajunge in garajul tau.
 */
public final class DealerScreen extends FlowScreen {
    private static final int[] COLORS = {0xF2F2F0, 0x1B1F2A, 0x8E959E, 0xC8283C, 0x2F6FDB, 0x2E9D5B, 0xF2C230, 0xE9A23B, 0x6B4A2E, 0x5B3E8A};
    private static final String[] COLOR_NAMES = {"Alb", "Negru", "Gri", "Roșu", "Albastru", "Verde", "Galben", "Portocaliu", "Maro", "Mov"};
    private static final int W = 520;
    private static final int H = 290;
    private static final int ROW = 30;
    private static final int GOLD = 0xFFF6C15A;
    private static final int GOLD2 = 0xFFD98E2B;
    private static final int WHITE = 0xFFF4F1EA;
    private static final int MUTED = 0xFF8E8C86;
    private static final int FAINT = 0xFF6F6D68;

    private final BlockPos pos;
    private String category = "";
    private String selected = "";
    private String lastSelected = "";
    private int color = COLORS[0];
    private int scroll;
    private boolean waiting;
    private Entity preview;
    private String previewType = "";
    private int previewColor = -1;
    private float spin = 200f;
    private boolean dragging;
    private final long born = System.currentTimeMillis();
    private long switchAt = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float bh;
    private String engine = "-";
    private String wheels = "-";
    private double extra;
    private boolean resetOpts = true;
    private String previewOpts = "";

    private float offX;
    private float offY;
    private float scale = 1f;

    private DealerScreen(BlockPos pos) {
        super("Dealer auto", W, H);
        this.pos = pos;
        CarsClient.request("catalog", new JsonObject());
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new DealerScreen(pos));
    }

    @Override
    protected boolean framed() {
        return false;
    }

    void result(boolean ok, String msg) {
        waiting = false;
        toast(msg, !ok);
    }

    private static boolean exists(String id) {
        return EntityType.byString(id).isPresent();
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String clipG(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    /** 12345 -> "12 345" (cu spatiu, ca in preturile de la dealer). */
    private static String price(double v) {
        String s = String.valueOf(Math.round(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float open = easeOut((now - born) / 300f);
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy, now, dt);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy, long now, float dt) {
        // antet
        Gfx.text(ms, "DEALER", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOW CITY AUTO", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Gfx.text(ms, "SOLD", W - 48, 24, 6f, Sk.FAINT, 2, false, 1f);
        Gfx.text(ms, price(CarsClient.money) + " lei", W - 48, 38, 11.5f, Sk.WHITE, 2, true, 0f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : CarsClient.catalog) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (exists(JsonClientModule.str(o, "id", ""))) all.add(o);
        }
        if (all.isEmpty()) {
            boolean loading = CarsClient.catalog.size() == 0;
            int dots = (int) ((now / 400) % 4);
            Gfx.text(ms, loading ? "Se încarcă catalogul" + "...".substring(0, dots) : "Modul de vehicule nu e instalat.", W / 2f, H / 2f, 10, Sk.MUTED, 1);
            return;
        }

        // categorii: text cu linie sub cea activa
        Set<String> cats = new LinkedHashSet<>();
        for (JsonObject o : all) cats.add(JsonClientModule.str(o, "category", "Diverse"));
        float cx = 16f;
        cx += tab(ms, "TOATE", cx, 72, category.isEmpty(), lmx, lmy, () -> {
            category = "";
            scroll = 0;
        });
        for (String c : cats) {
            float w = Gfx.width(c.toUpperCase(), 7f, false) + 14;
            if (cx + w > 206) break;
            final String cat = c;
            cx += tab(ms, c.toUpperCase(), cx, 72, c.equals(category), lmx, lmy, () -> {
                category = cat;
                scroll = 0;
            });
        }
        List<JsonObject> rows = new ArrayList<>();
        for (JsonObject o : all) if (category.isEmpty() || category.equals(JsonClientModule.str(o, "category", "Diverse"))) rows.add(o);
        if (rows.stream().noneMatch(r -> selected.equals(JsonClientModule.str(r, "id", "")))) selected = rows.isEmpty() ? "" : JsonClientModule.str(rows.get(0), "id", "");
        if (!selected.equals(lastSelected)) {
            lastSelected = selected;
            switchAt = now;
            resetOpts = true;
        }

        // lista (paralelograme; cel ales e plin cu accent)
        float top = 84f;
        float bottom = H - 12f;
        int visible = Math.max(1, (int) ((bottom - top) / ROW));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        JsonObject sel = null;
        float y = top;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject o = rows.get(i);
            String id = JsonClientModule.str(o, "id", "");
            if (id.equals(selected)) sel = o;
            if (i < scroll || i >= scroll + visible) continue;
            boolean s = id.equals(selected);
            boolean h = Sk.in(lmx, lmy, 12, y, 196, ROW - 4);
            Sk.slant(ms, s ? 18 : 12, y, 196, ROW - 4, 7f, s ? Sk.ACC : (h ? Sk.ROW_HOT : Sk.ROW));
            int fg = s ? 0xFF061426 : Sk.WHITE;
            int sub = s ? 0xFF1F4F85 : Sk.FAINT;
            float tx = s ? 32f : 26f;
            Gfx.text(ms, clipG(JsonClientModule.str(o, "name", id), 8.8f, true, 104), tx, y + 12, 8.8f, fg, 0, true, 0f);
            String line2 = JsonClientModule.str(o, "category", "");
            int stk = JsonClientModule.integer(o, "stock", -1);
            if (stk >= 0) line2 += (line2.isEmpty() ? "" : "  ·  ") + (stk == 0 ? "epuizat" : stk + " în stoc");
            Gfx.text(ms, clipG(line2, 6.2f, false, 120), tx, y + 21, 6.2f, stk == 0 && !s ? Sk.BAD : sub, 0);
            double p = JsonClientModule.num(o, "price", 0);
            Gfx.text(ms, price(p), 202, y + 16, 8.6f, s ? 0xFF061426 : (CarsClient.money >= p ? Sk.WHITE : Sk.BAD), 2, true, 0f);
            hitL(12, y, 196, ROW - 4, () -> selected = id);
            y += ROW;
        }
        if (rows.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(14f, trackH * visible / rows.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, rows.size() - visible);
            Gfx.rrect(ms, 212, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, 212, ty, 2, th, 0, Sk.ACC);
        }
        if (sel == null) return;

        if (resetOpts) {
            resetOpts = false;
            engine = JsonClientModule.str(sel, "engine", "-");
            wheels = JsonClientModule.str(sel, "wheels", "-");
            extra = 0;
        }

        // ---- zona masinii
        float rx = 230f;
        float rr = W - 16f;
        float rw = rr - rx;
        int paint = 0xFF000000 | color;
        float flash = Math.max(0f, 1f - (now - switchAt) / 450f);
        Sk.floor(ms, rx, rr, 112f, 172f, Gfx.mixArgb(paint, 0xFFFFFFFF, 0.1f * flash));
        renderPreview(ms, JsonClientModule.str(sel, "id", ""), rx + rw / 2f, 172f, 340f, 190f, dt, easeOut((now - switchAt) / 380f));
        String cat = JsonClientModule.str(sel, "category", "").toUpperCase();
        Gfx.text(ms, cat, rx, 56, 6.6f, Sk.ACC, 0, false, 1.2f);
        Gfx.text(ms, clipG(JsonClientModule.str(sel, "name", ""), 20, true, rw - 70), rx, 76, 20, Sk.WHITE, 0, true, 0f);
        int selStock = JsonClientModule.integer(sel, "stock", -1);
        if (selStock >= 0) {
            String stl = selStock == 0 ? "EPUIZAT" : "STOC " + selStock;
            Gfx.text(ms, stl, rr, 56, 6.8f, selStock == 0 ? Sk.BAD : (selStock <= 2 ? Sk.WARN : Sk.GOOD), 2, false, 1f);
        }
        List<String> dl = NotifyUi.wrap(JsonClientModule.str(sel, "desc", ""), 7.2f, 150, 2);
        float dy = 89f;
        for (String l : dl) {
            Gfx.text(ms, l, rx, dy, 7.2f, Sk.MUTED, 0);
            dy += 9.5f;
        }
        Gfx.text(ms, "trage pentru a roti", rr, 196, 5.8f, Sk.FAINT, 2);
        hitL(rx, 100, rw, 96, () -> dragging = true);

        // ---- statistici (dupa motor si roti)
        int pw = TuningScreen.enginePower(engine);
        int[] ws = TuningScreen.wheelStats(wheels);
        stat(ms, "VITEZĂ MAX", rx, 212, Math.min(10, Math.round(pw * 1.2f + ws[1] * 0.8f)));
        stat(ms, "ACCELERAȚIE", rx, 227, Math.min(10, pw * 2));
        stat(ms, "ADERENȚĂ", rx, 242, Math.min(10, ws[0] * 2));

        // ---- culoare: patrate
        String cname = "";
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == color) cname = COLOR_NAMES[i];
        Gfx.text(ms, "CULOARE", 404, 218, 6.2f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, cname.toUpperCase(), rr, 218, 6.4f, Sk.MUTED, 2, false, 0.8f);
        for (int i = 0; i < COLORS.length; i++) {
            float sx = 404f + (i % 5) * 21f;
            float sy = 224f + (i / 5) * 21f;
            boolean s = COLORS[i] == color;
            boolean h = Sk.in(lmx, lmy, sx, sy, 17, 17);
            if (s) Gfx.rrect(ms, sx - 2.5f, sy - 2.5f, 22, 22, 0, Sk.ACC);
            Gfx.rrect(ms, sx, sy, 17, 17, 0, Gfx.mixArgb(0xFF000000 | COLORS[i], 0xFFFFFFFF, h ? 0.18f : 0f));
            final int cc = COLORS[i];
            hitL(sx, sy, 17, 17, () -> color = cc);
        }

        // ---- pret + butoane
        double pr = JsonClientModule.num(sel, "price", 0) + extra;
        boolean afford = CarsClient.money >= pr;
        Gfx.text(ms, price(pr), rx, 278, 15, afford ? Sk.WHITE : Sk.BAD, 0, true, 0f);
        float pwid = Gfx.width(price(pr), 15, true);
        Gfx.text(ms, "lei", rx + pwid + 4, 278, 7, Sk.MUTED, 0);
        if (extra > 0) Gfx.text(ms, "cu tuning +" + price(extra), rx, 264, 6.4f, Sk.ACC, 0);
        final JsonObject chosen = sel;
        boolean ht = Sk.in(lmx, lmy, 352, 258, 76, 24);
        Sk.button(ms, "TUNING", 352, 258, 76, 24, true, false, ht);
        hitL(352, 258, 76, 24, () -> TuningScreen.forDealer(this, chosen, color, engine, wheels, (c, e, w, ex) -> {
            color = c;
            engine = e;
            wheels = w;
            extra = ex;
        }));
        boolean inStock = selStock != 0;
        boolean can = !waiting && afford && inStock;
        String label = waiting ? "SE CUMPĂRĂ" : (!inStock ? "EPUIZAT" : (afford ? "CUMPĂRĂ" : "FĂRĂ BANI"));
        boolean hb = can && Sk.in(lmx, lmy, 434, 258, 70, 24);
        Sk.button(ms, label, 434, 258, 70, 24, can, true, hb);
        if (can) hitL(434, 258, 70, 24, () -> buy(chosen));
    }

    private float tab(GuiGraphics ms, String label, float x, float y, boolean active, float lmx, float lmy, Runnable r) {
        float w = Gfx.width(label, 7f, false) + 14;
        boolean h = Sk.in(lmx, lmy, x, y - 10, w, 16);
        Gfx.text(ms, label, x, y, 7f, active ? Sk.WHITE : (h ? 0xFFC9CBD0 : Sk.MUTED), 0, false, 0.6f);
        if (active) Gfx.rrect(ms, x, y + 3, w - 14, 2, 0, Sk.ACC);
        hitL(x, y - 10, w, 16, r);
        return w;
    }

    private static void stat(GuiGraphics ms, String label, float x, float y, int v) {
        Gfx.text(ms, label, x, y + 6, 6.4f, Sk.MUTED, 0, false, 0.6f);
        Sk.segs(ms, x + 66, y, 96, 6, v, 10, Sk.ACC);
    }

    private static CompoundNBT stk(String id) {
        CompoundNBT s = new CompoundNBT();
        s.putString("id", id);
        s.putByte("Count", (byte) 1);
        return s;
    }

    private void buy(JsonObject sel) {
        JsonObject o = new JsonObject();
        o.addProperty("id", JsonClientModule.str(sel, "id", ""));
        o.addProperty("color", color);
        o.addProperty("engine", engine);
        o.addProperty("wheels", wheels);
        waiting = true;
        CarsClient.request("buy", o);
    }

    /** Vehiculul se deseneaza cu renderer-ul lui normal, intr-o entitate locala care nu exista in lume. */
    private void renderPreview(GuiGraphics g, String id, float cx, float groundY, float w, float h, float dt, float pop) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String opts = engine + "|" + wheels;
        if (!id.equals(previewType) || color != previewColor || preview == null || !opts.equals(previewOpts)) {
            previewType = id;
            previewColor = color;
            previewOpts = opts;
            preview = EntityType.byString(id).map(t -> t.create(mc.level)).orElse(null);
            if (preview != null) {
                try {
                    CompoundNBT t = preview.saveWithoutId(new CompoundNBT());
                    t.put("Color", new IntArrayNBT(new int[] {(color >> 16) & 255, (color >> 8) & 255, color & 255}));
                    ro.solarstudios.flowcity.cars.VehicleParts.apply(t, engine, wheels);
                    preview.load(t);
                } catch (RuntimeException ignored) {
                    // unele vehicule nu accepta culoare
                }
            }
        }
        if (preview == null) return;
        if (!dragging) spin += 26f * dt;
        float size = Math.max(preview.getBbWidth(), preview.getBbHeight());
        float sc = Math.min(w * 0.30f, (h - 56) * 0.95f) / Math.max(1f, size) * (0.82f + 0.18f * pop);
        g.pose().pushPose();
        g.pose().translate(cx, groundY, 120);
        g.pose().scale(sc, -sc, sc);
        g.pose().mulPose(Vector3f.XP.rotationDegrees(-16f));
        g.pose().mulPose(Vector3f.YP.rotationDegrees(spin));
        RenderHelper.setupFor3DItems();
        EntityRendererManager d = mc.getEntityRenderDispatcher();
        d.setRenderShadow(false);
        try {
            RenderSystem.runAsFancy(() -> d.render(preview, 0, 0, 0, 0f, 1f, g.pose(), g.bufferSource(), 0xF000F0));
            g.flush();
        } catch (RuntimeException ex) {
            preview = null;
        }
        d.setRenderShadow(true);
        RenderHelper.setupFor3DItems();
        g.pose().popPose();
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            spin += (float) dx * 1.5f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }
}
