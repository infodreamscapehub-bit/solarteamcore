package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Vector3f;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Dealerul auto: categorii, lista vehiculelor din catalog (configurat pe plugin), previzualizare 3D care se roteste,
 * alegerea culorii si cumpararea. Masina cumparata ajunge in garajul tau.
 */
public final class DealerScreen extends FlowScreen {
    private static final int[] COLORS = {0xF2F2F0, 0x1B1F2A, 0x8E959E, 0xC8283C, 0x2F6FDB, 0x2E9D5B, 0xF2C230, 0xE9A23B, 0x6B4A2E, 0x5B3E8A};
    private static final int LIST_W = 150;
    private final BlockPos pos;
    private String category = "";
    private String selected = "";
    private int color = COLORS[0];
    private int scroll;
    private boolean waiting;
    private Entity preview;
    private String previewType = "";
    private int previewColor = -1;
    private float spin = 200f;
    private boolean dragging;

    private DealerScreen(BlockPos pos) {
        super("Dealer auto", 460, 270);
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

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        header(ms, "Dealer auto", "", mx, my);
        Ui.textRight(ms, Ui.money(CarsClient.money) + " lei", px + pw - 28, py + 8, Theme.OK);
        List<JsonObject> all = new ArrayList<>();
        for (JsonElement e : CarsClient.catalog) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (exists(JsonClientModule.str(o, "id", ""))) all.add(o);
        }
        int x = px + 10;
        int top = py + HEAD + 6;
        if (all.isEmpty()) {
            Ui.text(ms, CarsClient.catalog.size() == 0 ? "Se incarca catalogul..." : "Modul de vehicule nu e instalat.", x, top + 6, Theme.MUTED);
            return;
        }
        Set<String> cats = new LinkedHashSet<>();
        for (JsonObject o : all) cats.add(JsonClientModule.str(o, "category", "Diverse"));
        int cx = x;
        cx += chip(ms, "Toate", cx, top, Ui.width("Toate") + 10, 14, mx, my, category.isEmpty(), Theme.ACCENT, () -> {
            category = "";
            scroll = 0;
        }) + 2;
        for (String c : cats) {
            int w = Ui.width(c) + 10;
            if (cx + w > px + pw - 10) break;
            final String cat = c;
            cx += chip(ms, c, cx, top, w, 14, mx, my, c.equals(category), Theme.ACCENT, () -> {
                category = cat;
                scroll = 0;
            }) + 2;
        }
        List<JsonObject> rows = new ArrayList<>();
        for (JsonObject o : all) if (category.isEmpty() || category.equals(JsonClientModule.str(o, "category", "Diverse"))) rows.add(o);
        if (rows.stream().noneMatch(r -> selected.equals(JsonClientModule.str(r, "id", "")))) selected = rows.isEmpty() ? "" : JsonClientModule.str(rows.get(0), "id", "");

        // lista
        int lt = top + 20;
        int lh = py + ph - 10 - lt;
        int rowH = 24;
        int visible = Math.max(1, lh / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        Ui.round(ms, x, lt, LIST_W, lh, Theme.PANEL_2);
        JsonObject sel = null;
        int y = lt + 2;
        for (int i = 0; i < rows.size(); i++) {
            JsonObject o = rows.get(i);
            String id = JsonClientModule.str(o, "id", "");
            if (id.equals(selected)) sel = o;
            if (i < scroll || i >= scroll + visible) continue;
            boolean s = id.equals(selected);
            boolean h = Ui.in(mx, my, x + 2, y, LIST_W - 4, rowH - 2);
            if (s || h) Ui.rect(ms, x + 2, y, LIST_W - 4, rowH - 2, s ? Theme.PANEL_3 : 0xFF22252B);
            if (s) Ui.rect(ms, x + 2, y, 2, rowH - 2, Theme.ACCENT);
            Ui.text(ms, Ui.clip(JsonClientModule.str(o, "name", id), LIST_W - 70), x + 9, y + 7, s ? Theme.TEXT : 0xFFD4D2CC);
            Ui.textRight(ms, Ui.money(JsonClientModule.num(o, "price", 0)), x + LIST_W - 8, y + 7, Theme.MUTED);
            hit(x + 2, y, LIST_W - 4, rowH - 2, () -> selected = id);
            y += rowH;
        }
        if (sel == null) return;

        // previzualizare
        int vx = x + LIST_W + 10;
        int vw = px + pw - 10 - vx;
        int vh = 130;
        Ui.round(ms, vx, lt, vw, vh, 0xFF1A1D22);
        Ui.rect(ms, vx + 10, lt + vh - 26, vw - 20, 1, 0xFF2A2E35);
        renderPreview(ms, JsonClientModule.str(sel, "id", ""), vx + vw / 2, lt + vh - 30, vw, vh);
        Ui.textCenter(ms, "trage cu mouse-ul ca s-o rotesti", vx + vw / 2, lt + vh - 12, Theme.FAINT);
        hit(vx, lt, vw, vh, () -> dragging = true);

        int dy = lt + vh + 8;
        Ui.textShadow(ms, JsonClientModule.str(sel, "name", ""), vx, dy, Theme.TEXT);
        double price = JsonClientModule.num(sel, "price", 0);
        Ui.textRight(ms, Ui.money(price) + " lei", vx + vw, dy, Theme.ACCENT);
        dy += 12;
        String desc = JsonClientModule.str(sel, "desc", "");
        if (!desc.isEmpty()) Ui.text(ms, Ui.clip(desc, vw), vx, dy, Theme.MUTED);
        dy += 16;
        // culori
        Ui.text(ms, "Culoare", vx, dy + 3, Theme.MUTED);
        int sx = vx + 50;
        for (int c : COLORS) {
            boolean s = c == color;
            if (s) Ui.round(ms, sx - 2, dy - 1, 16, 16, 0xFFFFFFFF);
            else if (Ui.in(mx, my, sx, dy + 1, 12, 12)) Ui.round(ms, sx - 1, dy, 14, 14, 0x88FFFFFF);
            Ui.round(ms, sx, dy + 1, 12, 12, 0xFF000000 | c);
            final int cc = c;
            hit(sx, dy + 1, 12, 12, () -> color = cc);
            sx += 16;
        }
        boolean afford = CarsClient.money >= price;
        String label = waiting ? "Se proceseaza..." : (afford ? "Cumpara" : "Nu ai destui bani");
        final JsonObject chosen = sel;
        button(ms, label, px + pw - 10 - 110, py + ph - 28, 110, 18, mx, my, !waiting && afford, Theme.ACCENT, () -> buy(chosen));
        Ui.text(ms, "Masina ajunge in garajul tau.", vx, py + ph - 23, Theme.FAINT);
    }

    private void buy(JsonObject sel) {
        JsonObject o = new JsonObject();
        o.addProperty("id", JsonClientModule.str(sel, "id", ""));
        o.addProperty("color", color);
        waiting = true;
        CarsClient.request("buy", o);
    }

    /** Vehiculul se deseneaza cu renderer-ul lui normal, intr-o entitate locala care nu exista in lume. */
    private void renderPreview(GuiGraphics g, String id, int cx, int groundY, int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (!id.equals(previewType) || color != previewColor || preview == null) {
            previewType = id;
            previewColor = color;
            preview = EntityType.byString(id).map(t -> t.create(mc.level)).orElse(null);
            if (preview != null) {
                try {
                    CompoundTag t = preview.saveWithoutId(new CompoundTag());
                    t.put("Color", new IntArrayTag(new int[] {(color >> 16) & 255, (color >> 8) & 255, color & 255}));
                    preview.load(t);
                } catch (RuntimeException ignored) {
                    // unele vehicule nu accepta culoare
                }
            }
        }
        if (preview == null) return;
        if (!dragging) spin += 0.4f;
        float size = Math.max(preview.getBbWidth(), preview.getBbHeight());
        float scale = Math.min(w * 0.28f, (h - 40) * 0.9f) / Math.max(1f, size);
        g.pose().pushPose();
        g.pose().translate(cx, groundY, 120);
        g.pose().scale(scale, -scale, scale);
        g.pose().mulPose(Vector3f.XP.rotationDegrees(-18f));
        g.pose().mulPose(Vector3f.YP.rotationDegrees(spin));
        Lighting.setupFor3DItems();
        EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
        d.setRenderShadow(false);
        try {
            RenderSystem.runAsFancy(() -> d.render(preview, 0, 0, 0, 0f, 1f, g.pose(), g.bufferSource(), 0xF000F0));
            g.flush();
        } catch (RuntimeException ex) {
            preview = null;
        }
        d.setRenderShadow(true);
        Lighting.setupFor3DItems();
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
