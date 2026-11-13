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
    private static final int W = 480;
    private static final int H = 272;
    private static final int ROW = 46;
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
        Gfx.rrect(ms, 10, 10, 196, H - 20, 14, 0xFF101216);

        // antet stanga
        String type = JsonClientModule.str(data, "type", "public");
        String sub = pound() ? "POUND" : ("factiune".equals(type) ? "FACȚIUNE · " + JsonClientModule.str(data, "faction", "").toUpperCase() : "GARAJ");
        Gfx.text(ms, clipG(JsonClientModule.str(data, "name", "Garaj"), 12, true, 170), 24, 36, 12, WHITE, 0, true, 0.4f);
        Gfx.text(ms, sub, 24, 49, 6.5f, GOLD, 0, false, 1f);

        JsonArray all = data.has("cars") ? data.getAsJsonArray("cars") : new JsonArray();
        JsonArray cars = new JsonArray();
        for (int i = 0; i < all.size(); i++) if (listed(all.get(i).getAsJsonObject())) cars.add(all.get(i));

        // inchidere
        boolean hx = inside(lmx, lmy, W - 30, 10, 18, 18);
        if (hx) Gfx.disc(ms, W - 21, 19, 9, 0x22FFFFFF);
        int xc = hx ? 0xFFE2453F : 0xFF8E8C86;
        Gfx.line(ms, W - 25, 15, W - 17, 23, 1.6f, xc);
        Gfx.line(ms, W - 17, 15, W - 25, 23, 1.6f, xc);
        hitL(W - 30, 10, 18, 18, this::onClose);

        if (cars.size() == 0) {
            Gfx.text(ms, pound() ? "Nu ai nicio mașină la pound." : "Nu ai nicio mașină aici.", 24, 84, 8.5f, 0xFFA19F98, 0);
            if (!pound()) Gfx.text(ms, "Cumpără una de la dealerul auto.", 24, 97, 7.5f, FAINT, 0);
            Gfx.text(ms, "Nicio mașină de afișat", 210 + (W - 210 - 14) / 2f, H / 2f + 4, 9, FAINT, 1);
            ms.pose().popPose();
            return;
        }

        JsonObject sel = null;
        for (int i = 0; i < cars.size(); i++) if (JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", 0) == selected) sel = cars.get(i).getAsJsonObject();
        if (sel == null) {
            sel = cars.get(0).getAsJsonObject();
            selected = JsonClientModule.integer(sel, "id", 0);
        }

        // ---- lista (stanga)
        Gfx.text(ms, cars.size() + (cars.size() == 1 ? " mașină" : " mașini"), 192, 49, 6.5f, FAINT, 2);
        float top = 60f;
        float bottom = H - 18f;
        int visible = Math.max(1, (int) ((bottom - top) / ROW));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cars.size() - visible)));
        float y = top;
        for (int i = scroll; i < Math.min(cars.size(), scroll + visible); i++) {
            JsonObject c = cars.get(i).getAsJsonObject();
            final int id = JsonClientModule.integer(c, "id", 0);
            boolean s = id == selected;
            boolean h = inside(lmx, lmy, 16, y, 184, ROW - 4);
            float cw = 184f;
            if (s) Gfx.rrect(ms, 16, y, cw, ROW - 4, 11, Gfx.mixArgb(GOLD, 0xFF000000, 0.15f), GOLD2);
            Gfx.rrect(ms, s ? 17 : 16, s ? y + 1 : y, s ? cw - 2 : cw, s ? ROW - 6 : ROW - 4, s ? 10 : 11, s ? 0xFF262A32 : (h ? 0xFF22262D : 0xFF1A1D23), s ? 0xFF1E2127 : (h ? 0xFF1C1F25 : 0xFF16181D));
            int col = carColor(c);
            Gfx.rrect(ms, 24, y + 8, 5, ROW - 20, 2.5f, col);
            Gfx.text(ms, clipG(JsonClientModule.str(c, "name", "?"), 8.8f, false, cw - 40), 36, y + 15, 8.8f, s ? WHITE : 0xFFD4D2CC, 0);
            plate(ms, JsonClientModule.str(c, "plate", ""), 36, y + 22, 6.4f);
            String st = statusLabel(c);
            int sc = statusColor(c);
            float sw = Gfx.width(st, 5.8f, false) + 12;
            Gfx.rrect(ms, 16 + cw - sw - 8, y + 24, sw, 11, 5.5f, Gfx.fade(sc, 0.18f));
            Gfx.text(ms, st, 16 + cw - sw / 2f - 8, y + 32, 5.8f, sc, 1, false, 0.4f);
            hitL(16, y, cw, ROW - 4, () -> selected = id);
            y += ROW;
        }
        if (cars.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(16f, trackH * visible / cars.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, cars.size() - visible);
            Gfx.rrect(ms, 202, top, 2, trackH, 1, 0xFF262A31);
            Gfx.rrect(ms, 202, ty, 2, th, 1, GOLD);
        }

        // ---- masina selectata (dreapta)
        float rx = 220f;
        float rw = W - rx - 16f;
        Gfx.rrect(ms, rx, 18, rw, 134, 14, 0xFF0E1013, 0xFF14171C);
        Gfx.scrim(ms, rx + rw / 2f, 128, 96, 12, 0x40F6C15A);
        renderPreview(ms, JsonClientModule.str(sel, "type", ""), JsonClientModule.integer(sel, "color", 0xFFFFFF), rx + rw / 2f, 128, rw, 134, dt);
        Gfx.text(ms, clipG(JsonClientModule.str(sel, "name", "?"), 13, true, rw - 120), rx + 14, 40, 13, WHITE, 0, true, 0.3f);
        plate(ms, JsonClientModule.str(sel, "plate", "-"), rx + 14, 47, 7.2f);
        String stl = statusLabel(sel);
        int stc = statusColor(sel);
        float stw = Gfx.width(stl, 6.4f, false) + 16;
        Gfx.rrect(ms, rx + rw - stw - 12, 28, stw, 14, 7, Gfx.fade(stc, 0.18f));
        Gfx.text(ms, stl, rx + rw - stw / 2f - 12, 38, 6.4f, stc, 1, false, 0.5f);
        Gfx.text(ms, "trage pentru a roti", rx + rw - 12, 146, 5.8f, 0xFF4A4E58, 2);

        // ---- bare
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
        float by0 = 166f;
        if (needsFuel) {
            stat(ms, "BENZINĂ", shownFuel, fuel, rx, by0, rw, fuel < 0.15f ? 0xFFE2453F : 0xFFE9A23B);
            stat(ms, "CAROSERIE", shownHp, hp, rx, by0 + 26, rw, hp < 0.3f ? 0xFFE2453F : 0xFF5FDC8F);
        } else {
            stat(ms, "CAROSERIE", shownHp, hp, rx, by0 + 12, rw, hp < 0.3f ? 0xFFE2453F : 0xFF5FDC8F);
        }

        // ---- actiune
        final int id = selected;
        float ay = 222f;
        boolean out = JsonClientModule.bool(sel, "out", false);
        if (pound()) {
            String reason = JsonClientModule.str(sel, "reason", "");
            Gfx.text(ms, JsonClientModule.bool(sel, "pound", false) ? "Ridicată de poliție" : "Găsită abandonată în oraș", rx, ay + 6, 7.6f, 0xFFA19F98, 0);
            if (!reason.isEmpty()) Gfx.text(ms, clipG("Motiv: " + reason, 7f, false, rw), rx, ay + 17, 7f, FAINT, 0);
            double fee = CarsClient.poundFee;
            boolean afford = CarsClient.money >= fee;
            String label = waiting ? "Se procesează..." : (afford ? "PLĂTEȘTE " + Ui.money(fee) + " LEI" : "NU AI " + Ui.money(fee) + " LEI");
            button(ms, label, rx, ay + 24, rw, 26, !waiting && afford, lmx, lmy, dt, () -> {
                waiting = true;
                JsonObject o = new JsonObject();
                o.addProperty("car", id);
                o.addProperty("garage", gid());
                CarsClient.request("pound_pay", o);
            });
        } else if (JsonClientModule.bool(sel, "pound", false)) {
            Gfx.text(ms, "Mașina a fost ridicată.", rx, ay + 8, 8.5f, 0xFFF08A82, 0);
            Gfx.text(ms, "Mergi la pound s-o scoi.", rx, ay + 21, 7.5f, FAINT, 0);
        } else if (out) {
            if (JsonClientModule.bool(sel, "loaded", false)) {
                Gfx.text(ms, "E în oraș, la X " + JsonClientModule.integer(sel, "lx", 0) + "   Z " + JsonClientModule.integer(sel, "lz", 0), rx, ay + 8, 8, 0xFFA19F98, 0);
                Gfx.text(ms, "Intră cu ea în zona de parcare (P)", rx, ay + 21, 7, FAINT, 0);
                Gfx.text(ms, "și apasă tasta de interacțiune ca s-o parchezi.", rx, ay + 32, 7, FAINT, 0);
            } else {
                Gfx.text(ms, "Nu mai e în oraș.", rx, ay + 8, 8.5f, 0xFFE9A23B, 0);
                Gfx.text(ms, "O găsești la pound.", rx, ay + 21, 7.5f, FAINT, 0);
            }
        } else {
            boolean personal = !"factiune".equals(JsonClientModule.str(data, "type", ""));
            float bw = personal ? rw - 74 : rw;
            button(ms, waiting ? "Se scoate..." : "SCOATE MAȘINA", rx, ay + 14, bw, 30, !waiting, lmx, lmy, dt, () -> {
                waiting = true;
                CarsNet.send("spawn", gid(), id);
            });
            if (personal) {
                float tx = rx + bw + 6;
                boolean th = inside(lmx, lmy, tx, ay + 14, 68, 30);
                Gfx.rrect(ms, tx, ay + 14, 68, 30, 12, th ? 0xFF2E333C : 0xFF23272E, th ? 0xFF252931 : 0xFF1B1E24);
                Gfx.text(ms, "TUNING", tx + 34, ay + 33, 8.5f, th ? GOLD : 0xFFD4D2CC, 1, true, 0.6f);
                hitL(tx, ay + 14, 68, 30, () -> TuningScreen.requestOpen("garage"));
            }
        }
        ms.pose().popPose();
    }

    /** Buton auriu mare; cand e dezactivat devine gri. */
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
        Gfx.text(ms, label, x + w / 2f, y + h / 2f + 4f, 10, enabled ? 0xFF2A1A06 : FAINT, 1, true, 0.7f);
        if (enabled) hitL(x, y, w, h, r);
    }

    private static void stat(GuiGraphics ms, String label, float shown, float real, float x, float y, float w, int color) {
        Gfx.text(ms, label, x, y + 6, 6.5f, FAINT, 0, false, 0.8f);
        Gfx.text(ms, Math.round(Math.max(0, real) * 100) + "%", x + w, y + 6, 8.5f, WHITE, 2, true, 0f);
        Gfx.rrect(ms, x, y + 11, w, 7, 3.5f, 0xFF262A31);
        float f = Math.max(0f, Math.min(1f, shown));
        if (f > 0) {
            float fw = Math.max(7f, w * f);
            Gfx.rrect(ms, x, y + 11, fw, 7, 3.5f, Gfx.mixArgb(color, 0xFFFFFFFF, 0.18f), Gfx.mixArgb(color, 0xFF000000, 0.2f));
            Gfx.rrect(ms, x + 3, y + 11.8f, Math.max(2f, fw - 6), 1.4f, 0.7f, 0x44FFFFFF);
        }
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
            if (inside(lx, ly, 220, 18, W - 236, 134)) dragging = true;
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
