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
import ro.solarstudios.flowcity.client.ui.FScreen;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Dealerul auto: catalog cu cautare si categorii (doar vehiculele care exista in joc), previzualizare 3D a masinii
 * (se roteste, o poti trage cu mouse-ul), date tehnice, vopsea, tuning, pret si confirmarea cumpararii.
 * Cumpararea trece prin sistemul de plata existent (cash sau card), iar masina ajunge in garajul tau.
 * Testul de drum nu exista in sistem, deci nu e afisat.
 */
public final class DealerScreen extends FScreen {
    private static final int[] COLORS = {0xF2F2F0, 0x1B1F2A, 0x8E959E, 0xC8283C, 0x2F6FDB, 0x2E9D5B, 0xF2C230, 0xE9A23B, 0x6B4A2E, 0x5B3E8A};
    private static final String[] COLOR_NAMES = {"Alb", "Negru", "Gri", "Roșu", "Albastru", "Verde", "Galben", "Portocaliu", "Maro", "Mov"};
    private static final int W = 600;
    private static final int H = 346;
    private static final int LISTW = 214;
    private static final float CARD = 46f;

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
    private String engine = "-";
    private String wheels = "-";
    private double extra;
    private boolean resetOpts = true;
    private String previewOpts = "";
    private boolean tuned;
    private JsonObject pending;

    private DealerScreen(BlockPos pos) {
        super("Dealer auto", W, H);
        this.pos = pos;
        CarsClient.request("catalog", new JsonObject());
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new DealerScreen(pos));
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

    @Override
    protected void onInputChanged(String id) {
        scroll = 0;
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        shell(g);
        top(g, "Dealer auto", "FlowCity Auto", mx, my);
        balance(g, "SOLD", CarsClient.money, W - 40, 16);

        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : CarsClient.catalog) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (exists(JsonClientModule.str(o, "id", ""))) all.add(o);
        }
        if (all.isEmpty()) {
            if (CarsClient.catalog.size() == 0) FUi.loading(g, W / 2f, H / 2f, now / 1000f, FTheme.accent);
            else FUi.empty(g, "close", "Catalog indisponibil", "Modulul de vehicule nu e instalat.", W / 2f, H / 2f - 10, 260f);
            return;
        }

        float x0 = 16f;
        float y0 = 60f;
        // cautare
        input(g, "q", "Caută model...", x0, y0, LISTW, 20f, 20, mx, my);
        // categorii
        Set<String> cats = new LinkedHashSet<>();
        for (JsonObject o : all) cats.add(JsonClientModule.str(o, "category", "Diverse"));
        float cx = x0;
        float cy = y0 + 26f;
        cx += chipAt(g, "cat-all", "Toate", cx, cy, 16, category.isEmpty(), mx, my, () -> {
            category = "";
            scroll = 0;
        });
        for (String c : cats) {
            float w = FUi.chipWidth(c, 16, false) + 5f;
            if (cx + w > x0 + LISTW + 1) {
                cx = x0;
                cy += 20f;
            }
            final String cat = c;
            cx += chipAt(g, "cat-" + c, c, cx, cy, 16, c.equals(category), mx, my, () -> {
                category = cat;
                scroll = 0;
            });
        }

        String q = text("q").trim().toLowerCase();
        List<JsonObject> rows = new ArrayList<>();
        for (JsonObject o : all) {
            if (!category.isEmpty() && !category.equals(JsonClientModule.str(o, "category", "Diverse"))) continue;
            if (!q.isEmpty() && !JsonClientModule.str(o, "name", "").toLowerCase().contains(q)) continue;
            rows.add(o);
        }
        if (rows.stream().noneMatch(r -> selected.equals(JsonClientModule.str(r, "id", "")))) {
            selected = rows.isEmpty() ? "" : JsonClientModule.str(rows.get(0), "id", "");
        }
        if (!selected.equals(lastSelected)) {
            lastSelected = selected;
            switchAt = now;
            resetOpts = true;
        }

        // lista de carduri
        float ly = cy + 24f;
        float lh = H - 16f - ly;
        int per = Math.max(1, (int) (lh / (CARD + 5f)));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - per)));
        JsonObject sel = null;
        if (rows.isEmpty()) FUi.empty(g, "search", "Niciun vehicul", "Încearcă altă căutare.", x0 + LISTW / 2f, ly + lh / 2f - 14, LISTW);
        float y = ly;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject o = rows.get(i);
            String id = JsonClientModule.str(o, "id", "");
            if (id.equals(selected)) sel = o;
            if (i < scroll || i >= scroll + per) continue;
            boolean s = id.equals(selected);
            FUi.card(g, "dc-" + id, x0, y, LISTW, CARD, true, mx, my);
            if (s) FTheme.panel(g, x0, y, LISTW, CARD, FTheme.R_MD, FTheme.alpha(FTheme.accent, 30), FTheme.accent);
            double p = JsonClientModule.num(o, "price", 0);
            int stk = JsonClientModule.integer(o, "stock", -1);
            tx(g, clip(JsonClientModule.str(o, "name", id), LISTW - 28, 8.6f, true), x0 + 12, y + 17, 8.6f, FTheme.TEXT);
            String sub = JsonClientModule.str(o, "category", "");
            if (stk >= 0) sub += (sub.isEmpty() ? "" : "  ·  ") + (stk == 0 ? "epuizat" : stk + " în stoc");
            t(g, clip(sub, 110, 6.4f, false), x0 + 12, y + 29, 6.4f, stk == 0 ? FTheme.DANGER : FTheme.MUTED);
            tr(g, num(p) + " lei", x0 + LISTW - 12, y + 40, 8f, CarsClient.money >= p ? FTheme.TEXT : FTheme.DANGER);
            hit((int) x0, (int) y, LISTW, (int) CARD, () -> selected = id);
            y += CARD + 5f;
        }
        if (rows.size() > per) FUi.scrollbar(g, x0 + LISTW + 3, ly, lh, rows.size(), per, scroll);
        if (sel == null) return;

        if (resetOpts) {
            resetOpts = false;
            engine = JsonClientModule.str(sel, "engine", "-");
            wheels = JsonClientModule.str(sel, "wheels", "-");
            extra = 0;
            tuned = false;
        }

        // ---- panoul din dreapta
        float rx = x0 + LISTW + 18f;
        float rr = W - 16f;
        float rw = rr - rx;
        card(g, rx, y0, rw, H - 16f - y0);

        // scena: podium colorat cu vopsea aleasa
        float sh = 142f;
        float flash = Math.max(0f, 1f - (now - switchAt) / 450f);
        int paint = 0xFF000000 | color;
        Gfx.rrect(g, rx + 6, y0 + 6, rw - 12, sh, 8f, FTheme.mix(FTheme.BG, paint, 0.10f + 0.10f * flash));
        Gfx.rrect(g, rx + 6, y0 + 6 + sh - 34, rw - 12, 34, 8f, FTheme.mix(FTheme.BG, paint, 0.22f));
        String selId = JsonClientModule.str(sel, "id", "");
        renderPreview(g, selId, rx + rw / 2f, y0 + sh - 6f, rw, sh, dt, easeOut((now - switchAt) / 380f));
        t(g, "trage pentru a roti", rx + rw - 14, y0 + sh - 2f, 5.8f, FTheme.FAINT);
        hit((int) (rx + 6), (int) (y0 + 6), (int) (rw - 12), (int) sh, () -> dragging = true);

        // titlu + stoc
        float ty = y0 + sh + 24f;
        tx(g, clip(JsonClientModule.str(sel, "name", ""), rw - 130, 14f, true), rx + 14, ty, 14f, FTheme.TEXT);
        int selStock = JsonClientModule.integer(sel, "stock", -1);
        float px2 = rr - 14;
        if (selStock >= 0) {
            String stl = selStock == 0 ? "EPUIZAT" : "ÎN STOC " + selStock;
            float pw2 = Gfx.width(stl, 6.4f, true) + 10f;
            pill(g, stl, px2 - pw2, ty - 11, selStock == 0 ? FTheme.DANGER : (selStock <= 2 ? FTheme.WARN : FTheme.OK));
            px2 -= pw2 + 6;
        }
        String cat = JsonClientModule.str(sel, "category", "");
        if (!cat.isEmpty()) {
            float pw3 = Gfx.width(cat.toUpperCase(), 6.4f, true) + 10f;
            pill(g, cat.toUpperCase(), px2 - pw3, ty - 11, FTheme.SKY);
        }
        String desc = JsonClientModule.str(sel, "desc", "");
        if (!desc.isEmpty()) {
            int line = 0;
            for (String l : wrap(desc, rw - 28, 7f, 2)) {
                t(g, l, rx + 14, ty + 12 + line * 9f, 7f, FTheme.MUTED);
                line++;
            }
        }

        // date tehnice (dupa motor si roti) + kilometraj
        float sy = ty + 36f;
        int pwr = TuningScreen.enginePower(engine);
        int[] ws = TuningScreen.wheelStats(wheels);
        float colW = (rw - 28 - 16) / 2f;
        FUi.progress(g, "VITEZĂ MAX", Math.min(10, Math.round(pwr * 1.2f + ws[1] * 0.8f)) + "/10", rx + 14, sy, colW,
            Math.min(10, Math.round(pwr * 1.2f + ws[1] * 0.8f)) / 10f, FTheme.accent);
        FUi.progress(g, "ACCELERAȚIE", Math.min(10, pwr * 2) + "/10", rx + 14 + colW + 16, sy, colW, Math.min(10, pwr * 2) / 10f, FTheme.SKY);
        FUi.progress(g, "ADERENȚĂ", Math.min(10, ws[0] * 2) + "/10", rx + 14, sy + 24, colW, Math.min(10, ws[0] * 2) / 10f, FTheme.OK);
        t(g, "Kilometraj", rx + 14 + colW + 16, sy + 30, 6.2f, FTheme.FAINT);
        tx(g, "0 km · nou", rx + 14 + colW + 16, sy + 41, 8f, FTheme.TEXT);

        // culoare
        float cy2 = H - 16f - 66f;
        String cname = "";
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == color) cname = COLOR_NAMES[i];
        FTheme.caption(g, "CULOARE  ·  " + cname.toUpperCase(), rx + 14, cy2 + 5, FTheme.FAINT);
        for (int i = 0; i < COLORS.length; i++) {
            float sx = rx + 14 + i * 20f;
            boolean s = COLORS[i] == color;
            boolean hv = inr(mx, my, sx, cy2 + 10, 16, 16);
            if (s) Gfx.disc(g, sx + 8, cy2 + 18, 10f, FTheme.accent);
            Gfx.disc(g, sx + 8, cy2 + 18, 8f, FTheme.mix(0xFF000000 | COLORS[i], 0xFFFFFFFF, hv ? 0.2f : 0f));
            final int cc = COLORS[i];
            hit((int) sx - 2, (int) cy2 + 8, 20, 20, () -> color = cc);
        }

        // pret + actiuni
        double pr = JsonClientModule.num(sel, "price", 0) + extra;
        boolean afford = CarsClient.money >= pr;
        float by = H - 16f - 28f - 2f;
        tx(g, num(pr), rx + 14, by + 20, 15f, afford ? FTheme.TEXT : FTheme.DANGER);
        t(g, "lei", rx + 14 + Gfx.width(num(pr), 15f, true) + 4, by + 20, 7f, FTheme.MUTED);
        if (extra > 0) t(g, "cu tuning +" + num(extra) + " lei", rx + 14, by + 6, 6.2f, FTheme.accent);
        else if (!afford) t(g, "Fonduri insuficiente", rx + 14, by + 6, 6.2f, FTheme.DANGER);
        final JsonObject chosen = sel;
        float bw2 = 96f;
        float bw1 = 132f;
        btn(g, "dl-tune", "Tuning", "gear", rr - 14 - bw1 - 8 - bw2, by, bw2, 28, FUi.SOFT, !waiting && !modalActive(), mx, my,
            () -> TuningScreen.forDealer(this, chosen, color, engine, wheels, (c, e, w, ex) -> {
                color = c;
                engine = e;
                wheels = w;
                extra = ex;
                tuned = true;
            }));
        boolean inStock = selStock != 0;
        boolean can = !waiting && afford && inStock && !modalActive();
        String label = waiting ? "Se cumpără..." : (!inStock ? "Epuizat" : "Cumpără");
        btn(g, "dl-buy", label, "check", rr - 14 - bw1, by, bw1, 28, FUi.PRIMARY, can, mx, my, () -> {
            pending = chosen;
            openModal(() -> buy(chosen));
        });
        if (pending != null) {
            drawModal(g, "Confirmă cumpărarea",
                JsonClientModule.str(pending, "name", "") + " · " + lei(JsonClientModule.num(pending, "price", 0) + extra),
                "Cumpără", "Anulează", false, mx, my);
        }
    }

    private static List<String> wrap(String text, float maxW, float size, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String t = cur.length() == 0 ? word : cur + " " + word;
            if (Gfx.width(t, size, false) > maxW && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
                if (out.size() >= maxLines) break;
            } else {
                cur = new StringBuilder(t);
            }
        }
        if (out.size() < maxLines && cur.length() > 0) out.add(cur.toString());
        if (!out.isEmpty()) out.set(out.size() - 1, clip(out.get(out.size() - 1), maxW, size, false));
        return out;
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
