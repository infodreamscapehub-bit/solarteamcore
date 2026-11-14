package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.IntArrayNBT;
import net.minecraft.util.math.vector.Vector3f;
import ro.solarstudios.flowcity.cars.CarsNet;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Meniul garajului (deschis cu E la marker), in stilul FlowCity: lista de masini cu stare, masina selectata in 3D care se
 * roteste (o poti trage cu mouse-ul), benzina si caroseria ca bare animate si butonul de scoatere. La pound apar masinile
 * ridicate sau pierdute si le scoti platind taxa (Vault, prin plugin).
 */
public final class GarageScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int ROW = 36;
    private static final int GOLD = 0xFFF6C15A;
    private static final int GOLD2 = 0xFFD98E2B;
    private static final int WHITE = 0xFFF4F1EA;
    private static final int MUTED = 0xFF8E8C86;
    private static final int FAINT = 0xFF6F6D68;

    private JsonObject data;
    private int selected;
    private int scroll;
    private boolean waiting;
    private final long born = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float spin = 30f;
    private boolean dragging;
    private float bf;
    private float bh;
    private float shownFuel;
    private float shownHp;
    private int shownFor = -1;

    private Entity preview;
    private String previewType = "";
    private int previewColor = -1;

    private float offX;
    private float offY;
    private float scale = 1f;

    GarageScreen(JsonObject data) {
        super("Garaj", W, H);
        update(data);
        if (pound()) CarsClient.request("pound_info", new JsonObject());
    }

    @Override
    protected boolean framed() {
        return false;
    }

    void update(JsonObject o) {
        this.data = o;
        waiting = false;
        String msg = JsonClientModule.str(o, "msg", "");
        if (!msg.isEmpty()) toast(msg, !JsonClientModule.bool(o, "ok", true));
    }

    void payResult(boolean ok, String msg) {
        waiting = false;
        if (!msg.isEmpty()) toast(msg, !ok);
    }

    private boolean pound() {
        return "pound".equals(JsonClientModule.str(data, "type", ""));
    }

    private int gid() {
        return JsonClientModule.integer(data, "id", 0);
    }

    /** La pound apar doar masinile ridicate sau pierdute (scoase, dar care nu mai sunt nicaieri in lume). */
    private boolean listed(JsonObject c) {
        if (!pound()) return true;
        return JsonClientModule.bool(c, "pound", false) || (JsonClientModule.bool(c, "out", false) && !JsonClientModule.bool(c, "loaded", false));
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

    private static int carColor(JsonObject c) {
        return 0xFF000000 | JsonClientModule.integer(c, "color", 0xFFFFFF);
    }

    /** Eticheta + culoarea starii unei masini. */
    private static String statusLabel(JsonObject c) {
        if (JsonClientModule.bool(c, "pound", false)) return "LA POUND";
        if (JsonClientModule.bool(c, "out", false)) return JsonClientModule.bool(c, "loaded", false) ? "ÎN ORAȘ" : "PIERDUTĂ";
        return "PARCATĂ";
    }

    private static int statusColor(JsonObject c) {
        if (JsonClientModule.bool(c, "pound", false)) return 0xFFE2453F;
        if (JsonClientModule.bool(c, "out", false)) return 0xFFE9A23B;
        return 0xFF5FDC8F;
    }

    /** Numar de inmatriculare: placuta alba cu banda albastra in stanga. Intoarce latimea. */
    private static float plate(GuiGraphics g, String plate, float x, float y, float size) {
        if (plate == null || plate.isEmpty()) return 0;
        float tw = Gfx.width(plate, size, false);
        float w = tw + size * 2.2f;
        float h = size * 1.7f;
        Gfx.rrect(g, x, y, w, h, 3, 0xFFF1F0EA, 0xFFD9D7CF);
        Gfx.rrect(g, x, y, size * 1.2f, h, 3, 0xFF2F55C8, 0xFF1F3A94);
        Gfx.text(g, plate, x + size * 1.2f + (w - size * 1.2f) / 2f, y + h * 0.72f, size, 0xFF16181D, 1);
        return w;
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
            content(ms, lmx, lmy, dt);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy, float dt) {
        String type = JsonClientModule.str(data, "type", "public");
        String sub = pound() ? "POUND" : ("factiune".equals(type) ? "FACȚIUNE · " + JsonClientModule.str(data, "faction", "").toUpperCase() : "GARAJ");
        Gfx.text(ms, clipG(JsonClientModule.str(data, "name", "Garaj"), 17, true, 190), 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, sub, 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        JsonArray all = data.has("cars") ? data.getAsJsonArray("cars") : new JsonArray();
        JsonArray cars = new JsonArray();
        for (int i = 0; i < all.size(); i++) if (listed(all.get(i).getAsJsonObject())) cars.add(all.get(i));

        if (cars.size() == 0) {
            Gfx.text(ms, pound() ? "Nu ai nicio mașină la pound." : "Nu ai nicio mașină aici.", 16, 84, 9, Sk.WHITE, 0);
            if (!pound()) Gfx.text(ms, "Cumpără una de la dealerul auto.", 16, 98, 7.6f, Sk.MUTED, 0);
            return;
        }

        JsonObject sel = null;
        for (int i = 0; i < cars.size(); i++) if (JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", 0) == selected) sel = cars.get(i).getAsJsonObject();
        if (sel == null) {
            sel = cars.get(0).getAsJsonObject();
            selected = JsonClientModule.integer(sel, "id", 0);
        }

        // ---- lista (paralelograme, cea aleasa plina cu accent)
        Gfx.text(ms, cars.size() + (cars.size() == 1 ? " MAȘINĂ" : " MAȘINI"), 208, 50, 6.2f, Sk.FAINT, 2, false, 1f);
        float top = 64f;
        float bottom = H - 12f;
        int visible = Math.max(1, (int) ((bottom - top) / ROW));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cars.size() - visible)));
        float y = top;
        for (int i = scroll; i < Math.min(cars.size(), scroll + visible); i++) {
            JsonObject c = cars.get(i).getAsJsonObject();
            final int id = JsonClientModule.integer(c, "id", 0);
            boolean s = id == selected;
            boolean h = Sk.in(lmx, lmy, 12, y, 196, ROW - 4);
            Sk.slant(ms, s ? 18 : 12, y, 196, ROW - 4, 7f, s ? Sk.ACC : (h ? Sk.ROW_HOT : Sk.ROW));
            float tx = s ? 38f : 32f;
            Gfx.rrect(ms, tx - 10, y + 7, 4, ROW - 18, 0, carColor(c));
            Gfx.text(ms, clipG(JsonClientModule.str(c, "name", "?"), 8.6f, true, 100), tx, y + 13, 8.6f, s ? 0xFF061426 : Sk.WHITE, 0, true, 0f);
            plate(ms, JsonClientModule.str(c, "plate", ""), tx, y + 17, 5.6f);
            Gfx.text(ms, statusLabel(c), 202, y + 13, 6f, s ? 0xFF061426 : statusColor(c), 2, false, 0.6f);
            hitL(12, y, 196, ROW - 4, () -> selected = id);
            y += ROW;
        }
        if (cars.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(14f, trackH * visible / cars.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, cars.size() - visible);
            Gfx.rrect(ms, 212, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, 212, ty, 2, th, 0, Sk.ACC);
        }

        // ---- masina selectata
        float rx = 230f;
        float rr = W - 16f;
        float rw = rr - rx;
        int paint = carColor(sel);
        Sk.floor(ms, rx, rr, 112f, 170f, paint);
        renderPreview(ms, JsonClientModule.str(sel, "type", ""), JsonClientModule.integer(sel, "color", 0xFFFFFF), rx + rw / 2f, 170, 340f, 190f, dt);
        Gfx.text(ms, statusLabel(sel), rr, 56, 6.8f, statusColor(sel), 2, false, 1.2f);
        Gfx.text(ms, clipG(JsonClientModule.str(sel, "name", "?"), 20, true, rw), rx, 76, 20, Sk.WHITE, 0, true, 0f);
        plate(ms, JsonClientModule.str(sel, "plate", "-"), rx, 83, 7.2f);
        Gfx.text(ms, "trage pentru a roti", rr, 196, 5.8f, Sk.FAINT, 2);

        // ---- bare benzina / caroserie
        float fuel = 0f;
        boolean needsFuel = JsonClientModule.bool(sel, "needsFuel", true);
        if (needsFuel) {
            float cap = (float) JsonClientModule.num(sel, "fuelCap", 0);
            fuel = cap <= 0 ? 0 : (float) JsonClientModule.num(sel, "fuel", 0) / cap;
        }
        float mh = (float) JsonClientModule.num(sel, "maxHealth", 0);
        float hp = mh <= 0 ? 1 : (float) JsonClientModule.num(sel, "health", 0) / mh;
        if (shownFor != selected) {
            shownFor = selected;
            shownFuel = 0f;
            shownHp = 0f;
        }
        shownFuel += (fuel - shownFuel) * Math.min(1f, dt * 6f);
        shownHp += (hp - shownHp) * Math.min(1f, dt * 6f);
        float by0 = 208f;
        if (needsFuel) {
            stat(ms, "BENZINĂ", shownFuel, fuel, rx, by0, rw, fuel < 0.15f ? Sk.BAD : Sk.WARN);
            stat(ms, "CAROSERIE", shownHp, hp, rx, by0 + 16, rw, hp < 0.3f ? Sk.BAD : Sk.GOOD);
        } else {
            stat(ms, "CAROSERIE", shownHp, hp, rx, by0 + 8, rw, hp < 0.3f ? Sk.BAD : Sk.GOOD);
        }

        // ---- actiune
        final int id = selected;
        float ay = 246f;
        boolean out = JsonClientModule.bool(sel, "out", false);
        if (pound()) {
            String reason = JsonClientModule.str(sel, "reason", "");
            Gfx.text(ms, JsonClientModule.bool(sel, "pound", false) ? "Ridicată de poliție" : "Găsită abandonată în oraș", rx, ay + 6, 7.6f, Sk.MUTED, 0);
            if (!reason.isEmpty()) Gfx.text(ms, clipG("Motiv: " + reason, 6.8f, false, rw), rx, ay + 16, 6.8f, Sk.FAINT, 0);
            double fee = CarsClient.poundFee;
            boolean afford = CarsClient.money >= fee;
            String label = waiting ? "SE PROCESEAZĂ" : (afford ? "PLĂTEȘTE " + Ui.money(fee) + " LEI" : "FĂRĂ BANI");
            boolean en = !waiting && afford;
            Sk.button(ms, label, rx, ay + 20, rw, 26, en, true, en && Sk.in(lmx, lmy, rx, ay + 20, rw, 26));
            if (en) hitL(rx, ay + 20, rw, 26, () -> {
                waiting = true;
                JsonObject o = new JsonObject();
                o.addProperty("car", id);
                o.addProperty("garage", gid());
                CarsClient.request("pound_pay", o);
            });
        } else if (JsonClientModule.bool(sel, "pound", false)) {
            Gfx.text(ms, "Mașina a fost ridicată.", rx, ay + 12, 9, Sk.BAD, 0);
            Gfx.text(ms, "Mergi la pound s-o scoți.", rx, ay + 25, 7.4f, Sk.FAINT, 0);
        } else if (out) {
            if (JsonClientModule.bool(sel, "loaded", false)) {
                Gfx.text(ms, "E în oraș, la X " + JsonClientModule.integer(sel, "lx", 0) + "   Z " + JsonClientModule.integer(sel, "lz", 0), rx, ay + 8, 8, Sk.MUTED, 0);
                Gfx.text(ms, "Intră cu ea în zona de parcare (P)", rx, ay + 20, 7, Sk.FAINT, 0);
                Gfx.text(ms, "și apasă tasta de interacțiune ca s-o parchezi.", rx, ay + 30, 7, Sk.FAINT, 0);
            } else {
                Gfx.text(ms, "Nu mai e în oraș.", rx, ay + 12, 9, Sk.WARN, 0);
                Gfx.text(ms, "O găsești la pound.", rx, ay + 25, 7.4f, Sk.FAINT, 0);
            }
        } else {
            boolean personal = !"factiune".equals(JsonClientModule.str(data, "type", ""));
            float bw = personal ? rw - 84 : rw;
            boolean en = !waiting;
            Sk.button(ms, waiting ? "SE SCOATE" : "SCOATE MAȘINA", rx, ay, bw, 30, en, true, en && Sk.in(lmx, lmy, rx, ay, bw, 30));
            if (en) hitL(rx, ay, bw, 30, () -> {
                waiting = true;
                CarsNet.send("spawn", gid(), id);
            });
            if (personal) {
                float tx = rx + bw + 8;
                Sk.button(ms, "TUNING", tx, ay, 76, 30, true, false, Sk.in(lmx, lmy, tx, ay, 76, 30));
                hitL(tx, ay, 76, 30, () -> TuningScreen.requestOpen("garage"));
            }
        }
    }

    private static void stat(GuiGraphics ms, String label, float shown, float real, float x, float y, float w, int color) {
        Gfx.text(ms, label, x, y + 7, 6.4f, Sk.MUTED, 0, false, 0.6f);
        Sk.segs(ms, x + 66, y, w - 66 - 34, 7, Math.round(Math.max(0f, Math.min(1f, shown)) * 20), 20, color);
        Gfx.text(ms, Math.round(Math.max(0, real) * 100) + "%", x + w, y + 8, 8, Sk.WHITE, 2, true, 0f);
    }

    // ------------------------------------------------------------------ masina 3D

    /** Vehiculul se deseneaza cu renderer-ul lui normal, intr-o entitate locala care nu exista in lume. */
    private void renderPreview(GuiGraphics g, String id, int color, float cx, float groundY, float w, float h, float dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || id == null || id.isEmpty()) return;
        if (!id.equals(previewType) || color != previewColor || preview == null) {
            previewType = id;
            previewColor = color;
            preview = EntityType.byString(id).map(t -> t.create(mc.level)).orElse(null);
            if (preview != null) {
                try {
                    CompoundNBT t = preview.saveWithoutId(new CompoundNBT());
                    t.put("Color", new IntArrayNBT(new int[] {(color >> 16) & 255, (color >> 8) & 255, color & 255}));
                    preview.load(t);
                } catch (RuntimeException ignored) {
                    // unele vehicule nu accepta culoare
                }
            }
        }
        if (preview == null) return;
        if (!dragging) spin += 26f * dt;
        float size = Math.max(preview.getBbWidth(), preview.getBbHeight());
        float sc = Math.min(w * 0.30f, (h - 44) * 0.95f) / Math.max(1f, size);
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

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            float lx = (float) ((mx - offX) / scale);
            float ly = (float) ((my - offY) / scale);
            if (inside(lx, ly, 230, 96, W - 246, 100)) dragging = true;
        }
        return super.mouseClicked(mx, my, button);
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
