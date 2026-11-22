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
import org.lwjgl.glfw.GLFW;

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

    // ---- vanzare (modal): 0 = inchis, 1 = deschis
    private int sellMode;
    private int sellTab;
    private JsonObject sellCar;
    private int sellId;
    private boolean sellBusy;
    private String sellNote = "";
    private boolean sellNoteBad;
    private boolean qReady;
    private boolean qOk;
    private double qPrice;
    private double qBase;
    private double qBonus;
    private double qWear;
    private double qTax = 3;
    private String qMsg = "";
    private String buyerBuf = "";
    private String priceBuf = "";
    private int sellField;
    private boolean confirmSell;

    private Entity preview;
    private String previewType = "";
    private int previewColor = -1;

    private float offX;
    private float offY;
    private float scale = 1f;

    GarageScreen(JsonObject data) {
        super("Garaj", W, H);
        update(data);
        prefill();
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

    /** Oferta pregatita cu /vindmasina: completeaza cumparatorul si pretul (se aplica la deschiderea ferestrei VINDE). */
    void prefill() {
        if (!CarsClient.sellBuyer.isEmpty()) {
            buyerBuf = CarsClient.sellBuyer;
            priceBuf = CarsClient.sellPrice > 0 ? String.valueOf(Math.round(CarsClient.sellPrice)) : "";
            sellTab = 1;
        }
    }

    void quote(JsonObject d) {
        if (sellMode == 0 || JsonClientModule.integer(d, "car", 0) != sellId) return;
        qReady = true;
        qOk = JsonClientModule.bool(d, "ok", false);
        qPrice = JsonClientModule.num(d, "price", 0);
        qBase = JsonClientModule.num(d, "base", 0);
        qBonus = JsonClientModule.num(d, "bonus", 0);
        qWear = JsonClientModule.num(d, "wear", 0);
        qTax = JsonClientModule.num(d, "tax", 3);
        qMsg = JsonClientModule.str(d, "msg", "");
    }

    void sellResult(JsonObject d) {
        sellBusy = false;
        boolean ok = JsonClientModule.bool(d, "ok", false);
        String msg = JsonClientModule.str(d, "msg", "");
        if (ok && JsonClientModule.bool(d, "done", false)) {
            sellMode = 0;
            if (!msg.isEmpty()) toast(msg, false);
            return;
        }
        sellNote = msg;
        sellNoteBad = !ok;
    }

    private void openSell(JsonObject car) {
        sellMode = 1;
        confirmSell = false;
        sellCar = car;
        sellId = JsonClientModule.integer(car, "id", 0);
        sellBusy = false;
        sellNote = "";
        qReady = false;
        qMsg = "";
        if (sellField < 0 || sellField > 1) sellField = 0;
        CarsClient.request("sell_quote", saleObj());
    }

    private JsonObject saleObj() {
        JsonObject o = new JsonObject();
        o.addProperty("car", sellId);
        o.addProperty("type", JsonClientModule.str(sellCar, "type", "-"));
        o.addProperty("plate", JsonClientModule.str(sellCar, "plate", ""));
        o.addProperty("engine", JsonClientModule.str(sellCar, "engine", "-"));
        o.addProperty("wheels", JsonClientModule.str(sellCar, "wheels", "-"));
        float mh = (float) JsonClientModule.num(sellCar, "maxHealth", 0);
        int hp = mh <= 0 ? 100 : Math.max(0, Math.min(100, Math.round(100f * (float) JsonClientModule.num(sellCar, "health", 0) / mh)));
        o.addProperty("hp", hp);
        return o;
    }

    private double typedPrice() {
        try {
            return priceBuf.isEmpty() ? 0 : Double.parseDouble(priceBuf);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void submitSell() {
        if (sellBusy || sellMode == 0) return;
        if (sellTab == 0) {
            if (!qReady || !qOk) return;
            if (!confirmSell) {
                confirmSell = true;
                return;
            }
            confirmSell = false;
            sellBusy = true;
            sellNote = "";
            CarsClient.request("sell_dealer", saleObj());
        } else {
            double price = typedPrice();
            if (buyerBuf.isEmpty() || price < 1) {
                sellNote = "Completează numele cumpărătorului și prețul.";
                sellNoteBad = true;
                return;
            }
            sellBusy = true;
            sellNote = "";
            JsonObject o = saleObj();
            o.addProperty("buyer", buyerBuf);
            o.addProperty("price", price);
            CarsClient.request("sell_player", o);
        }
    }

    private void sellModal(GuiGraphics ms, float lmx, float lmy) {
        final float mx0 = 110f;
        final float my0 = 54f;
        final float mw = 300f;
        final float mh = 188f;
        // blocheaza click-urile de sub fereastra
        hit(0, 0, width, height, () -> { });
        Gfx.rrect(ms, 0, 0, W, H, 0, 0xAA02070E);
        Gfx.rrect(ms, mx0, my0, mw, mh, 6, 0xFF1E3350);
        Gfx.rrect(ms, mx0 + 1, my0 + 1, mw - 2, mh - 2, 5, 0xFF0B1626);
        Gfx.text(ms, "VINDE MAȘINA", mx0 + 14, my0 + 22, 11f, Sk.WHITE, 0, true, 1f);
        Gfx.text(ms, clipG(JsonClientModule.str(sellCar, "name", "?") + " · " + JsonClientModule.str(sellCar, "plate", ""), 7f, false, mw - 28), mx0 + 14, my0 + 34, 7f, Sk.MUTED, 0);
        Sk.close(ms, mx0 + mw - 30, my0 + 8, lmx, lmy);
        hitL(mx0 + mw - 30, my0 + 8, 20, 20, () -> sellMode = 0);

        // tab-uri
        float tw = (mw - 28 - 6) / 2f;
        for (int i = 0; i < 2; i++) {
            final int tab = i;
            float tx = mx0 + 14 + i * (tw + 6);
            boolean on = sellTab == i;
            Sk.button(ms, i == 0 ? "LA DEALER" : "LA JUCĂTOR", tx, my0 + 42, tw, 20, true, on, !on && Sk.in(lmx, lmy, tx, my0 + 42, tw, 20));
            hitL(tx, my0 + 42, tw, 20, () -> {
                sellTab = tab;
                sellNote = "";
            });
        }

        float cy = my0 + 78;
        float lx = mx0 + 14;
        float rx = mx0 + mw - 14;
        if (sellTab == 0) {
            if (!qReady) {
                Gfx.text(ms, "Se calculează oferta...", lx, cy + 10, 8f, Sk.MUTED, 0);
            } else if (!qOk) {
                Gfx.text(ms, clipG(qMsg.isEmpty() ? "Dealerul nu cumpără această mașină." : qMsg, 8f, false, mw - 28), lx, cy + 10, 8f, Sk.BAD, 0);
            } else {
                Gfx.text(ms, "Preț de catalog", lx, cy + 6, 7.4f, Sk.MUTED, 0);
                Gfx.text(ms, Ui.money(qBase) + " lei", rx, cy + 6, 7.4f, Sk.WHITE, 2);
                Gfx.text(ms, "Uzură caroserie", lx, cy + 18, 7.4f, Sk.MUTED, 0);
                Gfx.text(ms, "-" + Math.round(qWear) + "%", rx, cy + 18, 7.4f, qWear > 0 ? Sk.WARN : Sk.WHITE, 2);
                Gfx.text(ms, "Piese de tuning", lx, cy + 30, 7.4f, Sk.MUTED, 0);
                Gfx.text(ms, "+" + Ui.money(qBonus) + " lei", rx, cy + 30, 7.4f, qBonus > 0 ? Sk.GOOD : Sk.WHITE, 2);
                Gfx.text(ms, "OFERTA DEALERULUI", lx, cy + 54, 6.6f, Sk.FAINT, 0, false, 1f);
                Gfx.text(ms, Ui.money(qPrice) + " lei", lx, cy + 70, 16f, Sk.ACC, 0, true, 0f);
            }
        } else {
            Gfx.text(ms, "CUMPĂRĂTOR (NUME)", lx, cy + 4, 6.4f, Sk.FAINT, 0, false, 1f);
            sellInput(ms, lx, cy + 8, mw - 28, buyerBuf, 0, lmx, lmy, "ex: Alex");
            Gfx.text(ms, "PREȚ (LEI)", lx, cy + 44, 6.4f, Sk.FAINT, 0, false, 1f);
            sellInput(ms, lx, cy + 48, mw - 28, priceBuf, 1, lmx, lmy, "ex: 25000");
            double price = typedPrice();
            double net = Math.round(price * (1 - qTax / 100.0) * 100.0) / 100.0;
            Gfx.text(ms, price >= 1 ? "Primești " + Ui.money(net) + " lei (taxă " + Math.round(qTax) + "%)" : "Cumpărătorul trebuie să fie lângă tine.", lx, cy + 84, 7f, price >= 1 ? Sk.GOOD : Sk.FAINT, 0);
        }

        if (!sellNote.isEmpty()) Gfx.text(ms, clipG(sellNote, 7f, false, mw - 28), lx, my0 + mh - 42, 7f, sellNoteBad ? Sk.BAD : Sk.GOOD, 0);

        float by = my0 + mh - 32;
        float bw = (mw - 28 - 8) / 2f;
        Sk.button(ms, "ANULEAZĂ", lx, by, bw, 24, true, false, Sk.in(lmx, lmy, lx, by, bw, 24));
        hitL(lx, by, bw, 24, () -> sellMode = 0);
        boolean can = !sellBusy && (sellTab == 1 || (qReady && qOk));
        String label = sellBusy ? "SE PROCESEAZĂ" : (sellTab == 0 ? "VINDE ACUM" : "TRIMITE OFERTA");
        float sx = lx + bw + 8;
        Sk.button(ms, label, sx, by, bw, 24, can, true, can && Sk.in(lmx, lmy, sx, by, bw, 24));
        if (can) hitL(sx, by, bw, 24, this::submitSell);
        if (confirmSell) {
            hit(0, 0, width, height, () -> { });
            Gfx.rrect(ms, 0, 0, W, H, 0, 0xB002070E);
            float cx0 = 150f;
            float cy0 = 96f;
            float cw = 220f;
            float ch = 100f;
            Sk.cut(ms, cx0 - 1, cy0 - 1, cw + 2, ch + 2, 9f, Sk.ACC);
            Sk.cut(ms, cx0, cy0, cw, ch, 9f, 0xFF0B1626);
            Gfx.text(ms, "CONFIRMI VÂNZAREA?", cx0 + cw / 2f, cy0 + 22, 10f, Sk.WHITE, 1, true, 0.8f);
            Gfx.text(ms, clipG(JsonClientModule.str(sellCar, "name", "?") + " · " + JsonClientModule.str(sellCar, "plate", ""), 7.4f, false, cw - 24), cx0 + cw / 2f, cy0 + 38, 7.4f, Sk.MUTED, 1);
            Gfx.text(ms, Ui.money(qPrice) + " lei", cx0 + cw / 2f, cy0 + 58, 14f, Sk.ACC, 1, true, 0f);
            float bw2 = (cw - 36f) / 2f;
            Sk.button(ms, "NU", cx0 + 14, cy0 + 68, bw2, 24, true, false, Sk.in(lmx, lmy, cx0 + 14, cy0 + 68, bw2, 24));
            hitL(cx0 + 14, cy0 + 68, bw2, 24, () -> confirmSell = false);
            Sk.button(ms, "DA, VINDE", cx0 + 22 + bw2, cy0 + 68, bw2, 24, true, true, Sk.in(lmx, lmy, cx0 + 22 + bw2, cy0 + 68, bw2, 24));
            hitL(cx0 + 22 + bw2, cy0 + 68, bw2, 24, this::submitSell);
        }
    }

    private void sellInput(GuiGraphics ms, float x, float y, float w, String val, int idx, float lmx, float lmy, String hint) {
        boolean act = sellField == idx;
        Gfx.rrect(ms, x, y, w, 22, 4, act ? Sk.ACC : 0xFF1E3350);
        Gfx.rrect(ms, x + 1, y + 1, w - 2, 20, 3, 0xFF0F1D31);
        boolean blink = act && (System.currentTimeMillis() / 500) % 2 == 0;
        if (val.isEmpty() && !act) Gfx.text(ms, hint, x + 8, y + 14.5f, 8f, Sk.FAINT, 0);
        else Gfx.text(ms, val + (blink ? "|" : ""), x + 8, y + 14.5f, 8.6f, Sk.WHITE, 0);
        hitL(x, y, w, 22, () -> sellField = idx);
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

    private JsonArray listedCars() {
        JsonArray all = data.has("cars") ? data.getAsJsonArray("cars") : new JsonArray();
        JsonArray cars = new JsonArray();
        for (int i = 0; i < all.size(); i++) if (listed(all.get(i).getAsJsonObject())) cars.add(all.get(i));
        return cars;
    }

    private JsonObject selectedCar(JsonArray cars) {
        for (int i = 0; i < cars.size(); i++) if (JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", 0) == selected) return cars.get(i).getAsJsonObject();
        return null;
    }

    private static String engineLabel(String id) {
        if (id == null || id.isEmpty() || "-".equals(id)) return "FĂRĂ MOTOR";
        String s = id.toLowerCase();
        String tier = s.contains("netherite") ? "NETHERITE" : s.contains("diamond") ? "DIAMANT" : s.contains("gold") ? "AUR" : s.contains("iron") ? "FIER" : s.contains("stone") ? "PIATRĂ" : s.contains("wood") ? "LEMN" : "STANDARD";
        String fam = s.contains("electric") ? "ELECTRIC" : s.contains("large") ? "MARE" : s.contains("small") ? "MIC" : "";
        return fam.isEmpty() ? tier : tier + " · " + fam;
    }

    private static String km(double meters) {
        return String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000.0);
    }

    private static void badge(GuiGraphics ms, String text, float rightX, float y, int color) {
        float w = Gfx.width(text, 5.6f, false) + 10f;
        Sk.cut(ms, rightX - w, y, w, 11, 3f, Gfx.fade(color, 0.22f));
        Gfx.text(ms, text, rightX - w / 2f, y + 7.6f, 5.6f, color, 1, true, 0.6f);
    }

    private static String badgeText(JsonObject c) {
        if (JsonClientModule.bool(c, "pound", false)) return "POUND";
        if (JsonClientModule.bool(c, "out", false)) return "AFARĂ";
        return "STA";
    }

    private void trySpawn(JsonObject sel) {
        if (waiting || sel == null || pound() || sellMode != 0) return;
        if (JsonClientModule.bool(sel, "out", false) || JsonClientModule.bool(sel, "pound", false)) return;
        waiting = true;
        CarsNet.send("spawn", gid(), JsonClientModule.integer(sel, "id", 0));
    }

    private void content(GuiGraphics ms, float lmx, float lmy, float dt) {
        long now = System.currentTimeMillis();
        String type = JsonClientModule.str(data, "type", "public");
        String crumb = pound() ? "FLOWCITY › POUND" : ("factiune".equals(type) ? "FLOWCITY › GARAJ › " + JsonClientModule.str(data, "faction", "").toUpperCase() : "FLOWCITY › GARAJ");
        Gfx.text(ms, crumb, 17, 24, 6.2f, Sk.MUTED, 0, false, 1.4f);
        Gfx.text(ms, clipG(JsonClientModule.str(data, "name", "Garaj"), 17, true, 300), 16, 44, 17, Sk.WHITE, 0, true, 0f);
        Sk.slant(ms, 16, 50, 46, 2, 0f, Sk.ACC);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        JsonArray cars = listedCars();
        if (cars.size() == 0) {
            Sk.cut(ms, 16, 70, 300, 70, 8f, 0x99081018);
            Gfx.text(ms, pound() ? "Nu ai nicio mașină la pound." : "Nu ai nicio mașină aici.", 32, 100, 10, Sk.WHITE, 0, true, 0f);
            if (!pound()) Gfx.text(ms, "Cumpără una de la dealerul auto.", 32, 118, 7.6f, Sk.MUTED, 0);
            return;
        }

        JsonObject sel = selectedCar(cars);
        if (sel == null) {
            sel = cars.get(0).getAsJsonObject();
            selected = JsonClientModule.integer(sel, "id", 0);
        }

        // ---- lista: carduri cu bara de benzina si insigna de stare
        Gfx.text(ms, cars.size() + (cars.size() == 1 ? " MAȘINĂ" : " MAȘINI"), 16, 66, 6.2f, Sk.FAINT, 0, false, 1.2f);
        float top = 72f;
        float bottom = H - 12f;
        int visible = Math.max(1, (int) ((bottom - top) / ROW));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cars.size() - visible)));
        float y = top;
        for (int i = scroll; i < Math.min(cars.size(), scroll + visible); i++) {
            JsonObject c = cars.get(i).getAsJsonObject();
            final int id = JsonClientModule.integer(c, "id", 0);
            boolean s = id == selected;
            boolean h = Sk.in(lmx, lmy, 12, y, 200, ROW - 4);
            float ro = easeOut((now - born - (i - scroll) * 55L) / 280f);
            float shift = (1f - ro) * -26f + (s ? 6f : 0f);
            float prevA = Gfx.alpha;
            Gfx.alpha = prevA * ro;
            Sk.slant(ms, 12 + shift, y, 200, ROW - 4, 7f, s ? Sk.ACC : (h ? Sk.ROW_HOT : Sk.ROW));
            int fg = s ? 0xFF061426 : Sk.WHITE;
            float tx = 30f + shift;
            Gfx.rrect(ms, tx - 10, y + 6, 3, ROW - 16, 0, carColor(c));
            Gfx.text(ms, clipG(JsonClientModule.str(c, "name", "?"), 8.4f, true, 96), tx, y + 13, 8.4f, fg, 0, true, 0f);
            plate(ms, JsonClientModule.str(c, "plate", ""), tx, y + 17, 5.2f);
            badge(ms, badgeText(c), 204 + shift, y + 5, s ? 0xFF061426 : statusColor(c));
            if (JsonClientModule.bool(c, "needsFuel", true) && !JsonClientModule.bool(c, "pound", false)) {
                float cap = (float) JsonClientModule.num(c, "fuelCap", 0);
                float f = cap <= 0 ? 0 : Math.max(0f, Math.min(1f, (float) JsonClientModule.num(c, "fuel", 0) / cap));
                Gfx.rrect(ms, 150 + shift, y + 24, 54, 3, 0, s ? 0x55061426 : 0xFF14202F);
                Gfx.rrect(ms, 150 + shift, y + 24, 54 * f, 3, 0, s ? 0xFF061426 : (f < 0.15f ? Sk.BAD : Sk.WARN));
            }
            Gfx.alpha = prevA;
            hitL(12, y, 200, ROW - 4, () -> selected = id);
            y += ROW;
        }
        if (cars.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(14f, trackH * visible / cars.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, cars.size() - visible);
            Gfx.rrect(ms, 216, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, 216, ty, 2, th, 0, Sk.ACC);
        }

        // ---- panoul masinii selectate
        float px = 226f;
        float pw = W - 12f - px;
        float pAnim = easeOut((now - born - 120L) / 320f);
        float prevA = Gfx.alpha;
        Gfx.alpha = prevA * pAnim;
        Sk.cut(ms, px, 58, pw, H - 70, 9f, 0x99081018);
        Sk.cut(ms, px, 58, 70, 2, 0f, Sk.ACC);
        float rx = px + 12f;
        float rr = px + pw - 12f;
        float rw = rr - rx;
        int paint = carColor(sel);
        Sk.floor(ms, px + 6, px + pw - 6, 100f, 150f, paint);
        renderPreview(ms, JsonClientModule.str(sel, "type", ""), JsonClientModule.integer(sel, "color", 0xFFFFFF), px + pw / 2f, 150, pw, 128f, dt);
        Gfx.text(ms, clipG(JsonClientModule.str(sel, "name", "?"), 15, true, rw - 70), rx, 80, 15, Sk.WHITE, 0, true, 0f);
        plate(ms, JsonClientModule.str(sel, "plate", "-"), rx, 87, 6.4f);
        badge(ms, statusLabel(sel), rr, 68, statusColor(sel));
        Gfx.text(ms, "trage pentru a roti", rr, 144, 5.6f, Sk.FAINT, 2);

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
        float by0 = 160f;
        if (needsFuel) {
            stat(ms, "BENZINĂ", shownFuel, fuel, rx, by0, rw, fuel < 0.15f ? Sk.BAD : Sk.WARN);
            stat(ms, "CAROSERIE", shownHp, hp, rx, by0 + 14, rw, hp < 0.3f ? Sk.BAD : Sk.GOOD);
        } else {
            stat(ms, "CAROSERIE", shownHp, hp, rx, by0 + 7, rw, hp < 0.3f ? Sk.BAD : Sk.GOOD);
        }

        // ---- km total / trip / motor
        boolean hasOdo = sel.has("odo");
        float cw = (rw - 12f) / 3f;
        float cy = 192f;
        String[] lab = {"KM TOTAL", "TRIP", "MOTOR"};
        String[] val = {hasOdo ? km(JsonClientModule.num(sel, "odo", 0)) : "-", hasOdo ? km(JsonClientModule.num(sel, "trip", 0)) : "-", engineLabel(JsonClientModule.str(sel, "engine", "-"))};
        for (int i = 0; i < 3; i++) {
            float cx = rx + i * (cw + 6f);
            Sk.cut(ms, cx, cy, cw, 28, 4f, 0x90111A28);
            Gfx.text(ms, lab[i], cx + 7, cy + 10, 5.4f, Sk.FAINT, 0, false, 0.8f);
            Gfx.text(ms, clipG(val[i], 7.4f, true, cw - 12), cx + 7, cy + 22, 7.4f, Sk.WHITE, 0, true, 0f);
        }

        // ---- actiuni
        final int id = selected;
        float ay = 226f;
        boolean out = JsonClientModule.bool(sel, "out", false);
        if (pound()) {
            String reason = JsonClientModule.str(sel, "reason", "");
            Gfx.text(ms, JsonClientModule.bool(sel, "pound", false) ? "Ridicată de poliție" : "Găsită abandonată în oraș", rx, ay + 8, 7.4f, Sk.MUTED, 0);
            if (!reason.isEmpty()) Gfx.text(ms, clipG("Motiv: " + reason, 6.6f, false, rw), rx, ay + 18, 6.6f, Sk.FAINT, 0);
            double fee = CarsClient.poundFee;
            boolean afford = CarsClient.money >= fee;
            String label = waiting ? "SE PROCESEAZĂ" : (afford ? "PLĂTEȘTE " + Ui.money(fee) + " LEI" : "FĂRĂ BANI");
            boolean en = !waiting && afford;
            Sk.button(ms, label, rx, ay + 22, rw, 28, en, true, en && Sk.in(lmx, lmy, rx, ay + 22, rw, 28));
            if (en) hitL(rx, ay + 22, rw, 28, () -> {
                waiting = true;
                JsonObject o = new JsonObject();
                o.addProperty("car", id);
                o.addProperty("garage", gid());
                CarsClient.request("pound_pay", o);
            });
        } else if (JsonClientModule.bool(sel, "pound", false)) {
            Gfx.text(ms, "Mașina a fost ridicată.", rx, ay + 18, 9, Sk.BAD, 0);
            Gfx.text(ms, "Mergi la pound s-o scoți.", rx, ay + 32, 7.4f, Sk.FAINT, 0);
        } else if (out) {
            if (JsonClientModule.bool(sel, "loaded", false)) {
                Gfx.text(ms, "E în oraș, la X " + JsonClientModule.integer(sel, "lx", 0) + "   Z " + JsonClientModule.integer(sel, "lz", 0), rx, ay + 8, 7.6f, Sk.MUTED, 0);
                Gfx.text(ms, "Intră cu ea în zona de parcare (P) și apasă E.", rx, ay + 19, 6.8f, Sk.FAINT, 0);
                Sk.button(ms, "PARCHEAZĂ", rx, ay + 26, rw, 26, true, false, Sk.in(lmx, lmy, rx, ay + 26, rw, 26));
                hitL(rx, ay + 26, rw, 26, () -> toast("Intră cu mașina în zona de parcare (P) și apasă E.", false));
            } else {
                Gfx.text(ms, "Nu mai e în oraș.", rx, ay + 18, 9, Sk.WARN, 0);
                Gfx.text(ms, "O găsești la pound.", rx, ay + 32, 7.4f, Sk.FAINT, 0);
            }
        } else {
            boolean personal = !"factiune".equals(JsonClientModule.str(data, "type", ""));
            float bw = personal ? rw - 160 : rw;
            boolean en = !waiting;
            Sk.button(ms, waiting ? "SE SCOATE" : "SCOATE", rx, ay + 6, bw, 34, en, true, en && Sk.in(lmx, lmy, rx, ay + 6, bw, 34));
            if (en) hitL(rx, ay + 6, bw, 34, () -> {
                waiting = true;
                CarsNet.send("spawn", gid(), id);
            });
            if (personal) {
                float tx = rx + bw + 8;
                Sk.button(ms, "TUNING", tx, ay + 6, 76, 34, true, false, Sk.in(lmx, lmy, tx, ay + 6, 76, 34));
                hitL(tx, ay + 6, 76, 34, () -> TuningScreen.requestOpen("garage"));
                float vx = tx + 84;
                final JsonObject sellSel = sel;
                Sk.button(ms, "VINDE", vx, ay + 6, 68, 34, true, false, Sk.in(lmx, lmy, vx, ay + 6, 68, 34));
                hitL(vx, ay + 6, 68, 34, () -> openSell(sellSel));
            }
            Gfx.text(ms, "SUS/JOS alege · ENTER scoate", rr, ay + 49, 5.4f, Sk.FAINT, 2);
        }
        Gfx.alpha = prevA;
        if (sellMode != 0 && sellCar != null) sellModal(ms, lmx, lmy);
    }

    private static void stat(GuiGraphics ms, String label, float shown, float real, float x, float y, float w, int color) {
        Gfx.text(ms, label, x, y + 7, 6.2f, Sk.MUTED, 0, false, 0.6f);
        Sk.segs(ms, x + 62, y, w - 62 - 32, 7, Math.round(Math.max(0f, Math.min(1f, shown)) * 20), 20, color);
        Gfx.text(ms, Math.round(Math.max(0, real) * 100) + "%", x + w, y + 7.5f, 7.6f, Sk.WHITE, 2, true, 0f);
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
            if (sellMode == 0 && inside(lx, ly, 232, 62, W - 252, 96)) dragging = true;
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
        if (sellMode != 0) return true;
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (sellMode != 0) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (confirmSell) confirmSell = false;
                else sellMode = 0;
                return true;
            }
            if (sellTab == 1) {
                if (key == GLFW.GLFW_KEY_TAB) {
                    sellField = 1 - sellField;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_BACKSPACE) {
                    if (sellField == 0 && !buyerBuf.isEmpty()) buyerBuf = buyerBuf.substring(0, buyerBuf.length() - 1);
                    if (sellField == 1 && !priceBuf.isEmpty()) priceBuf = priceBuf.substring(0, priceBuf.length() - 1);
                    return true;
                }
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                submitSell();
                return true;
            }
            return true; // fereastra de vanzare e modala: tastele nu ajung la restul ecranului
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            JsonArray cars = listedCars();
            int idx = 0;
            for (int i = 0; i < cars.size(); i++) if (JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", 0) == selected) idx = i;
            idx = Math.max(0, Math.min(cars.size() - 1, idx + (key == GLFW.GLFW_KEY_UP ? -1 : 1)));
            if (cars.size() > 0) {
                selected = JsonClientModule.integer(cars.get(idx).getAsJsonObject(), "id", 0);
                int vis = Math.max(1, (int) ((H - 12f - 72f) / ROW));
                if (idx < scroll) scroll = idx;
                if (idx >= scroll + vis) scroll = idx - vis + 1;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            trySpawn(selectedCar(listedCars()));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (sellMode != 0) {
            if (sellTab != 1) return true;
            if (sellField == 0 && buyerBuf.length() < 16 && (Character.isLetterOrDigit(c) || c == '_') && c < 128) buyerBuf += c;
            if (sellField == 1 && priceBuf.length() < 9 && c >= '0' && c <= '9') priceBuf += c;
            return true;
        }
        return super.charTyped(c, mods);
    }
}
