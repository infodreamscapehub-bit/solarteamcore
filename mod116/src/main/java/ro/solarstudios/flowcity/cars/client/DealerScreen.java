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
    private static final int W = 500;
    private static final int H = 290;
    private static final int ROW = 46;
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
        scale = Math.min(1f, Math.min((width - 12f) / W, (height - 12f) / H)) * (0.94f + 0.06f * open);
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;

        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0xC0050608, open));
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);

        Gfx.rrect(ms, -6, 2, W + 12, H + 10, 22, 0x55000000);
        Gfx.rrect(ms, 0, 0, W, H, 18, 0xFF1D2026, 0xFF121418);
        Gfx.rrect(ms, 0.5f, 0.5f, W - 1, 1, 0, 0x14FFFFFF);
        Gfx.rrect(ms, 10, 10, 216, H - 20, 14, 0xFF101216);

        // antet
        Gfx.text(ms, "DEALER AUTO", 24, 36, 12, WHITE, 0, true, 0.8f);
        Gfx.text(ms, "ALEGE-ȚI MAȘINA", 24, 49, 6.5f, GOLD, 0, false, 1f);

        // sold + inchidere
        String bal = price(CarsClient.money) + " lei";
        float bw = Gfx.width(bal, 8.5f, true) + 22;
        Gfx.rrect(ms, W - 40 - bw, 12, bw, 18, 9, Gfx.fade(0xFF5FDC8F, 0.16f));
        Gfx.disc(ms, W - 40 - bw + 9, 21, 2.6f, 0xFF5FDC8F);
        Gfx.text(ms, bal, W - 40 - 8, 24.2f, 8.5f, 0xFF7BE3A0, 2, true, 0f);
        boolean hx = inside(lmx, lmy, W - 30, 12, 18, 18);
        if (hx) Gfx.disc(ms, W - 21, 21, 9, 0x22FFFFFF);
        int xc = hx ? 0xFFE2453F : 0xFF8E8C86;
        Gfx.line(ms, W - 25, 17, W - 17, 25, 1.6f, xc);
        Gfx.line(ms, W - 17, 17, W - 25, 25, 1.6f, xc);
        hitL(W - 30, 12, 18, 18, this::onClose);

        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : CarsClient.catalog) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (exists(JsonClientModule.str(o, "id", ""))) all.add(o);
        }
        if (all.isEmpty()) {
            boolean loading = CarsClient.catalog.size() == 0;
            int dots = (int) ((now / 400) % 4);
            Gfx.text(ms, loading ? "Se încarcă catalogul" + "...".substring(0, dots) : "Modul de vehicule nu e instalat.", W / 2f, H / 2f, 10, MUTED, 1);
            ms.pose().popPose();
            return;
        }

        // ---- categorii
        Set<String> cats = new LinkedHashSet<>();
        for (JsonObject o : all) cats.add(JsonClientModule.str(o, "category", "Diverse"));
        float cx = 22f;
        cx += chip(ms, "Toate", cx, 58, category.isEmpty(), lmx, lmy, () -> {
            category = "";
            scroll = 0;
        }) + 5;
        for (String c : cats) {
            float w = Gfx.width(c, 7.2f, false) + 16;
            if (cx + w > 218) break;
            final String cat = c;
            cx += chip(ms, c, cx, 58, c.equals(category), lmx, lmy, () -> {
                category = cat;
                scroll = 0;
            }) + 5;
        }
        List<JsonObject> rows = new ArrayList<>();
        for (JsonObject o : all) if (category.isEmpty() || category.equals(JsonClientModule.str(o, "category", "Diverse"))) rows.add(o);
        if (rows.stream().noneMatch(r -> selected.equals(JsonClientModule.str(r, "id", "")))) selected = rows.isEmpty() ? "" : JsonClientModule.str(rows.get(0), "id", "");
        if (!selected.equals(lastSelected)) {
            lastSelected = selected;
            switchAt = now;
            resetOpts = true;
        }

        // ---- lista
        float top = 84f;
        float bottom = H - 18f;
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
            boolean h = inside(lmx, lmy, 18, y, 196, ROW - 4);
            if (s) Gfx.rrect(ms, 18, y, 196, ROW - 4, 11, Gfx.mixArgb(GOLD, 0xFF000000, 0.15f), GOLD2);
            Gfx.rrect(ms, s ? 19 : 18, s ? y + 1 : y, s ? 194 : 196, s ? ROW - 6 : ROW - 4, s ? 10 : 11, s ? 0xFF262A32 : (h ? 0xFF22262D : 0xFF1A1D23), s ? 0xFF1E2127 : (h ? 0xFF1C1F25 : 0xFF16181D));
            Gfx.text(ms, clipG(JsonClientModule.str(o, "name", id), 9f, false, 196 - 24 - 72), 30, y + 17, 9f, s ? WHITE : 0xFFD4D2CC, 0);
            String catTxt = clipG(JsonClientModule.str(o, "category", ""), 6.6f, false, 70);
            Gfx.text(ms, catTxt, 30, y + 29, 6.6f, FAINT, 0);
            int stk = JsonClientModule.integer(o, "stock", -1);
            if (stk >= 0) {
                String st = stk == 0 ? "EPUIZAT" : "Stoc " + stk;
                Gfx.text(ms, st, 30 + Gfx.width(catTxt, 6.6f, false) + 8, y + 29, 6.6f, stk == 0 ? 0xFFE2453F : (stk <= 2 ? 0xFFE9A23B : 0xFF7BE3A0), 0);
            }
            double p = JsonClientModule.num(o, "price", 0);
            boolean can = CarsClient.money >= p;
            Gfx.text(ms, price(p), 204, y + 20, 9f, can ? GOLD : 0xFFF08A82, 2, true, 0f);
            Gfx.text(ms, "lei", 204, y + 30, 6.2f, FAINT, 2);
            hitL(18, y, 196, ROW - 4, () -> selected = id);
            y += ROW;
        }
        if (rows.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(16f, trackH * visible / rows.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, rows.size() - visible);
            Gfx.rrect(ms, 220, top, 2, trackH, 1, 0xFF262A31);
            Gfx.rrect(ms, 220, ty, 2, th, 1, GOLD);
        }
        if (sel == null) {
            ms.pose().popPose();
            return;
        }

        if (sel != null && resetOpts) {
            resetOpts = false;
            engine = JsonClientModule.str(sel, "engine", "-");
            wheels = JsonClientModule.str(sel, "wheels", "-");
            extra = 0;
        }

        // ---- showroom (dreapta)
        float rx = 242f;
        float rw = W - rx - 16f;
        float py0 = 42f;
        float ph0 = 150f;
        Gfx.rrect(ms, rx, py0, rw, ph0, 14, 0xFF0E1013, 0xFF15181D);
        int paint = 0xFF000000 | color;
        float flash = Math.max(0f, 1f - (now - switchAt) / 500f);
        Gfx.scrim(ms, rx + rw / 2f, py0 + 70, 120, 64, Gfx.fade(paint, 0.16f + 0.14f * flash));
        // podium: elipse luminoasa sub masina
        Gfx.scrim(ms, rx + rw / 2f, py0 + ph0 - 28, 104, 12, 0x55F6C15A);
        Gfx.scrim(ms, rx + rw / 2f, py0 + ph0 - 28, 70, 7, 0x66FFFFFF);
        renderPreview(ms, JsonClientModule.str(sel, "id", ""), rx + rw / 2f, py0 + ph0 - 30, rw, ph0, dt, easeOut((now - switchAt) / 380f));
        Gfx.text(ms, clipG(JsonClientModule.str(sel, "name", ""), 15, true, rw - 28), rx + 14, py0 + 26, 15, WHITE, 0, true, 0.3f);
        String cat = JsonClientModule.str(sel, "category", "").toUpperCase();
        if (!cat.isEmpty()) {
            float cw = Gfx.width(cat, 6.2f, false) + 16;
            Gfx.rrect(ms, rx + 14, py0 + 33, cw, 12, 6, Gfx.fade(GOLD, 0.16f));
            Gfx.text(ms, cat, rx + 14 + cw / 2f, py0 + 41.4f, 6.2f, GOLD, 1, false, 0.7f);
        }
        int selStock = JsonClientModule.integer(sel, "stock", -1);
        if (selStock >= 0) {
            String stl = selStock == 0 ? "STOC EPUIZAT" : "STOC: " + selStock;
            int stc = selStock == 0 ? 0xFFE2453F : (selStock <= 2 ? 0xFFE9A23B : 0xFF7BE3A0);
            float sw = Gfx.width(stl, 6.2f, false) + 16;
            Gfx.rrect(ms, rx + rw - 14 - sw, py0 + 14, sw, 12, 6, Gfx.fade(stc, 0.16f));
            Gfx.text(ms, stl, rx + rw - 14 - sw / 2f, py0 + 22.4f, 6.2f, stc, 1, false, 0.7f);
        }
        Gfx.text(ms, "trage pentru a roti", rx + rw - 12, py0 + ph0 - 8, 5.8f, 0xFF4A4E58, 2);
        hitL(rx, py0, rw, ph0, () -> dragging = true);

        // descriere
        String desc = JsonClientModule.str(sel, "desc", "");
        List<String> dl = NotifyUi.wrap(desc, 7.8f, rw, 2);
        float dy = py0 + ph0 + 16;
        for (String l : dl) {
            Gfx.text(ms, l, rx, dy, 7.8f, 0xFFA19F98, 0);
            dy += 10;
        }

        // vopsea
        float cy = 232f;
        Gfx.text(ms, "VOPSEA", rx, cy - 8, 6.2f, FAINT, 0, false, 0.9f);
        String cname = "";
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == color) cname = COLOR_NAMES[i];
        Gfx.text(ms, cname, rx + rw, cy - 8, 6.6f, MUTED, 2);
        float sx = rx + 8f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 300.0);
        for (int c : COLORS) {
            boolean s = c == color;
            boolean h = inside(lmx, lmy, sx - 9, cy - 2, 18, 18);
            int col = 0xFF000000 | c;
            if (s) Gfx.ring(ms, sx, cy + 7, 8.6f, 10.2f + pulse * 0.6f, GOLD, GOLD2);
            Gfx.disc(ms, sx, cy + 7, h || s ? 7.6f : 7f, Gfx.mixArgb(col, 0xFFFFFFFF, 0.3f), Gfx.mixArgb(col, 0xFF000000, 0.15f), -2, -2.4f);
            final int cc = c;
            hitL(sx - 9, cy - 2, 18, 18, () -> color = cc);
            sx += 23.8f;
        }

        // pret + tuning + cumparare
        double pr = JsonClientModule.num(sel, "price", 0) + extra;
        boolean afford = CarsClient.money >= pr;
        Gfx.text(ms, extra > 0 ? "PREȚ CU TUNING" : "PREȚ", rx, 256, 6.2f, FAINT, 0, false, 0.9f);
        Gfx.text(ms, price(pr) + " lei", rx, 272, 12.5f, afford ? GOLD : 0xFFF08A82, 0, true, 0.1f);
        if (extra > 0) Gfx.text(ms, "+" + price(extra) + " tuning", rx, 282, 6.2f, MUTED, 0);
        final JsonObject chosen = sel;
        boolean th = inside(lmx, lmy, rx + 100, 254, 56, 28);
        Gfx.rrect(ms, rx + 100, 254, 56, 28, 12, th ? 0xFF2E333C : 0xFF23272E, th ? 0xFF252931 : 0xFF1B1E24);
        Gfx.text(ms, "TUNING", rx + 128, 272.2f, 8, th ? GOLD : 0xFFD4D2CC, 1, true, 0.5f);
        hitL(rx + 100, 254, 56, 28, () -> TuningScreen.forDealer(this, chosen, color, engine, wheels, (c, e, w, ex) -> {
            color = c;
            engine = e;
            wheels = w;
            extra = ex;
        }));
        boolean inStock = selStock != 0;
        String label = waiting ? "Se cumpără..." : (!inStock ? "EPUIZAT" : (afford ? "CUMPĂRĂ" : "FĂRĂ BANI"));
        button(ms, label, rx + 162, 254, rw - 162, 28, !waiting && afford && inStock, lmx, lmy, dt, () -> buy(chosen));
        ms.pose().popPose();
    }

    private float chip(GuiGraphics ms, String label, float x, float y, boolean selected, float lmx, float lmy, Runnable r) {
        float w = Gfx.width(label, 7.2f, false) + 16;
        boolean h = inside(lmx, lmy, x, y, w, 15);
        if (selected) Gfx.rrect(ms, x, y, w, 15, 7.5f, GOLD, GOLD2);
        else Gfx.rrect(ms, x, y, w, 15, 7.5f, h ? 0xFF2A2E36 : 0xFF1C1F25);
        Gfx.text(ms, label, x + w / 2f, y + 10.4f, 7.2f, selected ? 0xFF2A1A06 : (h ? WHITE : 0xFFA19F98), 1);
        hitL(x, y, w, 15, r);
        return w;
    }

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, float lmx, float lmy, float dt, Runnable r) {
        boolean hov = enabled && inside(lmx, lmy, x, y, w, h);
        bh += ((hov ? 1f : 0f) - bh) * Math.min(1f, dt * 14f);
        if (enabled) {
            float p = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 500.0);
            Gfx.rrect(ms, x - 2 - p * 1.5f, y - 2 - p * 1.5f, w + 4 + p * 3, h + 4 + p * 3, 13, Gfx.fade(0x2EF6C15A, 1f - p * 0.6f));
        }
        int t1 = enabled ? Gfx.mixArgb(GOLD, 0xFFFFFFFF, bh * 0.18f) : 0xFF24272D;
        int t2 = enabled ? Gfx.mixArgb(GOLD2, 0xFFFFFFFF, bh * 0.10f) : 0xFF1E2126;
        Gfx.rrect(ms, x, y, w, h, 12, t1, t2);
        if (enabled) Gfx.rrect(ms, x + 6, y + 1, w - 12, 1.5f, 0.75f, 0x66FFFFFF);
        Gfx.text(ms, label, x + w / 2f, y + h / 2f + 3.8f, 9.5f, enabled ? 0xFF2A1A06 : FAINT, 1, true, 0.6f);
        if (enabled) hitL(x, y, w, h, r);
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
                    if (!"-".equals(engine)) t.put("EngineStack", stk(engine));
                    if (!"-".equals(wheels)) t.put("WheelStack", stk(wheels));
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
