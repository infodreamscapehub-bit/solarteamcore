package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
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
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Atelierul de tuning: vopsea (presetate + RGB liber), motor pe niveluri si roti. Se deschide din garaj, de la mecanicul
 * NPC sau de la dealer (inainte sa cumperi). Previzualizarea 3D se schimba pe loc. La garaj / mecanic se plateste prin
 * Vault (pe plugin) si modificarea se aplica masinii parcate; la dealer alegerile se intorc in meniul de cumparare.
 */
public final class TuningScreen extends FlowScreen {
    /** Cine a cerut lista de masini ("garage" / "npc"): primul raspuns "tune_cars" deschide ecranul. */
    static String wantOpen = "";

    public interface Done {
        void apply(int color, String engine, String wheels, double extra);
    }

    private static final int[] PRESETS = {0xF2F2F0, 0x1B1F2A, 0x8E959E, 0xC8283C, 0x2F6FDB, 0x2E9D5B, 0xF2C230, 0xE9A23B, 0x6B4A2E, 0x5B3E8A};
    private static final String[] TIERS = {"wood", "stone", "iron", "gold", "diamond"};
    private static final String[] TIER_NAMES = {"Lemn", "Piatră", "Fier", "Aur", "Diamant"};
    private static final Pattern ENGINE = Pattern.compile("^vehicle:(wood_|stone_|iron_|gold_|diamond_)?(small|large|electric)_engine$");
    private static final int W = 500;
    private static final int H = 290;
    private static final int GOLD = 0xFFF6C15A;
    private static final int GOLD2 = 0xFFD98E2B;
    private static final int WHITE = 0xFFF4F1EA;
    private static final int MUTED = 0xFF8E8C86;
    private static final int FAINT = 0xFF6F6D68;

    private final String mode;
    private final Screen parent;
    private final Done done;
    private JsonArray cars = new JsonArray();
    private int carId = -1;
    private String carName = "";
    private String carPlate = "";
    private String carType = "";
    private int fromColor = 0xF2F2F0;
    private String fromEngine = "-";
    private String fromWheels = "-";
    private int color = 0xF2F2F0;
    private String engine = "-";
    private String wheels = "-";
    private int tab;
    private int slider = -1;
    private boolean waiting;
    private boolean loaded;

    private Entity preview;
    private String previewKey = "";
    private float spin = 215f;
    private boolean dragging;
    private final long born = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float bh;
    private float offX;
    private float offY;
    private float scale = 1f;

    // ------------------------------------------------------------------ deschidere

    /** Garaj / mecanic: cere lista masinilor parcate si deschide ecranul la raspuns. */
    public static void requestOpen(String mode) {
        wantOpen = mode;
        CarsNet.send("tunelist", 0, 0);
    }

    /** Raspunsul serverului cu masinile parcate (deschide ecranul sau il actualizeaza). */
    static void cars(JsonObject o) {
        Minecraft mc = Minecraft.getInstance();
        JsonArray arr = o.has("cars") && o.get("cars").isJsonArray() ? o.getAsJsonArray("cars") : new JsonArray();
        if (mc.screen instanceof TuningScreen) {
            ((TuningScreen) mc.screen).setCars(arr);
        } else if (!wantOpen.isEmpty()) {
            TuningScreen s = new TuningScreen(wantOpen, null, null);
            s.setCars(arr);
            mc.setScreen(s);
        }
        wantOpen = "";
    }

    /** Dealer: alegi vopsea / motor / roti pentru vehiculul ales; Done primeste alegerile si extra-costul. */
    public static void forDealer(Screen parent, JsonObject vehicle, int color, String engine, String wheels, Done done) {
        TuningScreen s = new TuningScreen("dealer", parent, done);
        s.carType = JsonClientModule.str(vehicle, "id", "");
        s.carName = JsonClientModule.str(vehicle, "name", "");
        s.carPlate = "";
        s.fromEngine = JsonClientModule.str(vehicle, "engine", "-");
        s.fromWheels = JsonClientModule.str(vehicle, "wheels", "-");
        s.fromColor = color;
        s.color = color;
        s.engine = engine;
        s.wheels = wheels;
        s.loaded = true;
        Minecraft.getInstance().setScreen(s);
    }

    private TuningScreen(String mode, Screen parent, Done done) {
        super("Tuning", W, H);
        this.mode = mode;
        this.parent = parent;
        this.done = done;
        if (!"dealer".equals(mode) && CarsClient.tuning.size() == 0) CarsClient.request("catalog", new JsonObject());
    }

    @Override
    protected boolean framed() {
        return false;
    }

    private void setCars(JsonArray arr) {
        cars = arr;
        loaded = true;
        waiting = false;
        int keep = carId;
        selectCar(-1);
        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i).isJsonObject() && JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", -1) == keep) selectCar(i);
        }
        if (carId < 0 && cars.size() > 0) selectCar(0);
    }

    private void selectCar(int idx) {
        if (idx < 0 || idx >= cars.size() || !cars.get(idx).isJsonObject()) {
            carId = -1;
            return;
        }
        JsonObject c = cars.get(idx).getAsJsonObject();
        carId = JsonClientModule.integer(c, "id", -1);
        carName = JsonClientModule.str(c, "name", "");
        carPlate = JsonClientModule.str(c, "plate", "");
        carType = JsonClientModule.str(c, "type", "");
        fromColor = JsonClientModule.integer(c, "color", 0xF2F2F0) & 0xFFFFFF;
        fromEngine = JsonClientModule.str(c, "engine", "-");
        fromWheels = JsonClientModule.str(c, "wheels", "-");
        color = fromColor;
        engine = fromEngine;
        wheels = fromWheels;
    }

    private int carIndex() {
        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i).isJsonObject() && JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", -1) == carId) return i;
        }
        return -1;
    }

    void result(boolean ok, String msg) {
        waiting = false;
        toast(msg, !ok);
        if (ok) CarsNet.send("tunelist", 0, 0);
    }

    // ------------------------------------------------------------------ preturi

    private static String family(String e) {
        return e.replaceFirst("^vehicle:(wood_|stone_|iron_|gold_|diamond_)", "vehicle:");
    }

    private static int tierOf(String e) {
        Matcher m = ENGINE.matcher(e == null ? "" : e);
        if (!m.matches()) return -2;
        if (m.group(1) == null) return -1;
        String t = m.group(1).replace("_", "");
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(t)) return i;
        return -2;
    }

    private String engineId(int tier) {
        return "vehicle:" + TIERS[tier] + "_" + family(fromEngine).substring(8);
    }

    private static double tierPrice(int tier) {
        if (tier < 0) return 0;
        JsonArray a = CarsClient.tuning.has("engines") ? CarsClient.tuning.getAsJsonArray("engines") : new JsonArray();
        for (JsonElement e : a) {
            if (e.isJsonObject() && TIERS[tier].equals(JsonClientModule.str(e.getAsJsonObject(), "tier", ""))) return JsonClientModule.num(e.getAsJsonObject(), "price", 0);
        }
        return 0;
    }

    private static int tierPower(int tier) {
        JsonArray a = CarsClient.tuning.has("engines") ? CarsClient.tuning.getAsJsonArray("engines") : new JsonArray();
        for (JsonElement e : a) {
            if (e.isJsonObject() && tier >= 0 && TIERS[tier].equals(JsonClientModule.str(e.getAsJsonObject(), "tier", ""))) return JsonClientModule.integer(e.getAsJsonObject(), "power", tier + 1);
        }
        return tier + 1;
    }

    private static JsonArray wheelList() {
        return CarsClient.tuning.has("wheels") ? CarsClient.tuning.getAsJsonArray("wheels") : new JsonArray();
    }

    private static double wheelPrice(String id) {
        for (JsonElement e : wheelList()) {
            if (e.isJsonObject() && id.equals(JsonClientModule.str(e.getAsJsonObject(), "id", ""))) return JsonClientModule.num(e.getAsJsonObject(), "price", 0);
        }
        return 0;
    }

    private double engineCost() {
        if (engine.equals(fromEngine)) return 0;
        return Math.max(0, tierPrice(tierOf(engine)) - tierPrice(tierOf(fromEngine)));
    }

    private double wheelsCost() {
        return wheels.equals(fromWheels) ? 0 : wheelPrice(wheels);
    }

    private double paintCost() {
        return "dealer".equals(mode) || color == fromColor ? 0 : CarsClient.tuning.has("paint") ? CarsClient.tuning.get("paint").getAsDouble() : 0;
    }

    private double total() {
        return engineCost() + wheelsCost() + paintCost();
    }

    private boolean changed() {
        return color != fromColor || !engine.equals(fromEngine) || !wheels.equals(fromWheels);
    }

    // ------------------------------------------------------------------ utilitare de desen

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

    private static String price(double v) {
        String s = String.valueOf(Math.round(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
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
        Gfx.rrect(ms, 10, 10, 226, H - 20, 14, 0xFF101216);

        // antet
        Gfx.text(ms, "TUNING", 24, 36, 12, WHITE, 0, true, 0.8f);
        String sub = "dealer".equals(mode) ? "PERSONALIZEAZĂ ÎNAINTE SĂ CUMPERI" : "ATELIER AUTO";
        Gfx.text(ms, sub, 24, 49, 6.5f, GOLD, 0, false, 1f);

        // inchidere
        boolean hx = inside(lmx, lmy, W - 30, 12, 18, 18);
        if (hx) Gfx.disc(ms, W - 21, 21, 9, 0x22FFFFFF);
        int xc = hx ? 0xFFE2453F : 0xFF8E8C86;
        Gfx.line(ms, W - 25, 17, W - 17, 25, 1.6f, xc);
        Gfx.line(ms, W - 17, 17, W - 25, 25, 1.6f, xc);
        hitL(W - 30, 12, 18, 18, this::onClose);

        boolean noCars = !"dealer".equals(mode) && (cars.size() == 0 || carId < 0);
        if (noCars) {
            String t1 = loaded ? "Nu ai nicio mașină parcată în garaj." : "Se încarcă...";
            Gfx.text(ms, t1, W / 2f, H / 2f - 4, 10, WHITE, 1);
            if (loaded) Gfx.text(ms, "Parchează-ți mașina la un garaj și revino.", W / 2f, H / 2f + 12, 7.8f, MUTED, 1);
            ms.pose().popPose();
            return;
        }
        if (CarsClient.tuning.size() == 0) {
            Gfx.text(ms, "Se încarcă prețurile...", W / 2f, H / 2f, 9, MUTED, 1);
            ms.pose().popPose();
            return;
        }

        // ---- stanga: previzualizare
        float px0 = 18f;
        float pw0 = 210f;
        Gfx.rrect(ms, px0, 60, pw0, 118, 14, 0xFF0E1013, 0xFF15181D);
        Gfx.scrim(ms, px0 + pw0 / 2f, 112, 90, 46, Gfx.fade(0xFF000000 | color, 0.26f));
        Gfx.scrim(ms, px0 + pw0 / 2f, 150, 78, 9, 0x55F6C15A);
        renderPreview(ms, px0 + pw0 / 2f, 148f, pw0, 118f, dt);
        hitL(px0, 60, pw0, 118, () -> dragging = true);
        Gfx.text(ms, "trage pentru a roti", px0 + pw0 - 8, 172, 5.8f, 0xFF4A4E58, 2);

        // numele masinii (+ sageti daca sunt mai multe masini)
        float ny = 196f;
        boolean multi = !"dealer".equals(mode) && cars.size() > 1;
        float nx = multi ? 36f : 24f;
        Gfx.text(ms, clipG(carName, 11, true, multi ? 160 : 196), nx, ny, 11, WHITE, 0, true, 0.2f);
        if (!carPlate.isEmpty()) Gfx.text(ms, carPlate, nx, ny + 11, 7, GOLD, 0, false, 0.8f);
        if (multi) {
            arrow(ms, 22, ny - 4, true, lmx, lmy);
            arrow(ms, 214, ny - 4, false, lmx, lmy);
        }

        // rezumat modificari
        float sy = 220f;
        sy = line(ms, "Vopsea", hex(color), paintCost(), color != fromColor, sy);
        sy = line(ms, "Motor", engineLabel(engine), engineCost(), !engine.equals(fromEngine), sy);
        sy = line(ms, "Roți", wheelLabel(wheels), wheelsCost(), !wheels.equals(fromWheels), sy);
        Gfx.rrect(ms, 24, 262, 196, 0.8f, 0, 0x22FFFFFF);
        double tot = total();
        Gfx.text(ms, "TOTAL", 24, 277, 7, FAINT, 0, false, 1f);
        Gfx.text(ms, tot <= 0 ? "gratis" : price(tot) + " lei", 220, 277, 11, tot <= 0 ? MUTED : (afford(tot) ? GOLD : 0xFFF08A82), 2, true, 0.2f);

        // ---- dreapta: taburi
        float rx = 248f;
        float rw = W - rx - 16f;
        String[] tabs = {"Vopsea", "Motor", "Roți"};
        float tx = rx;
        for (int i = 0; i < tabs.length; i++) {
            float w = Gfx.width(tabs[i], 8f, false) + 24;
            boolean s = tab == i;
            boolean h = inside(lmx, lmy, tx, 14, w, 17);
            if (s) Gfx.rrect(ms, tx, 14, w, 17, 8.5f, GOLD, GOLD2);
            else Gfx.rrect(ms, tx, 14, w, 17, 8.5f, h ? 0xFF2A2E36 : 0xFF1C1F25);
            Gfx.text(ms, tabs[i], tx + w / 2f, 25.6f, 8f, s ? 0xFF2A1A06 : (h ? WHITE : 0xFFA19F98), 1);
            final int ti = i;
            hitL(tx, 14, w, 17, () -> tab = ti);
            tx += w + 6;
        }
        Gfx.rrect(ms, rx, 40, rw, 206, 14, 0xFF171A1F, 0xFF121418);
        if (tab == 0) paintTab(ms, rx, 40, rw, lmx, lmy, now);
        else if (tab == 1) engineTab(ms, rx, 40, rw, lmx, lmy);
        else wheelsTab(ms, rx, 40, rw, lmx, lmy);

        // ---- buton
        String label;
        boolean enabled;
        if ("dealer".equals(mode)) {
            label = "CONFIRMĂ";
            enabled = true;
        } else if (waiting) {
            label = "Se aplică...";
            enabled = false;
        } else if (!changed()) {
            label = "NICIO SCHIMBARE";
            enabled = false;
        } else if (!afford(tot)) {
            label = "NU AI DESTUI BANI";
            enabled = false;
        } else {
            label = tot <= 0 ? "APLICĂ" : "APLICĂ · " + price(tot) + " LEI";
            enabled = true;
        }
        gold(ms, label, rx, 254, rw, 28, enabled, lmx, lmy, dt, this::apply);
        ms.pose().popPose();
    }

    private boolean afford(double v) {
        return "dealer".equals(mode) || CarsClient.money >= v;
    }

    private String engineLabel(String e) {
        int t = tierOf(e);
        if ("-".equals(e)) return "fără motor";
        return t < 0 ? "standard" : TIER_NAMES[t];
    }

    private String wheelLabel(String id) {
        for (JsonElement e : wheelList()) {
            if (e.isJsonObject() && id.equals(JsonClientModule.str(e.getAsJsonObject(), "id", ""))) return JsonClientModule.str(e.getAsJsonObject(), "name", id);
        }
        return "-".equals(id) ? "fără roți" : id.replace("vehicle:", "");
    }

    private float line(GuiGraphics ms, String name, String value, double cost, boolean ch, float y) {
        Gfx.text(ms, name, 24, y, 7.2f, FAINT, 0);
        Gfx.text(ms, value, 76, y, 7.8f, ch ? WHITE : MUTED, 0);
        if (ch) Gfx.text(ms, cost <= 0 ? "gratis" : "+" + price(cost), 220, y, 7.6f, cost <= 0 ? 0xFF7BE3A0 : GOLD, 2);
        else Gfx.text(ms, "neschimbat", 220, y, 6.6f, 0xFF4A4E58, 2);
        return y + 13;
    }

    private void arrow(GuiGraphics ms, float x, float y, boolean left, float lmx, float lmy) {
        boolean h = inside(lmx, lmy, x - 6, y - 8, 20, 22);
        Gfx.rrect(ms, x - 4, y - 2, 14, 16, 8, h ? 0xFF2A2E36 : 0xFF1C1F25);
        float cx = x + 3f;
        float cy = y + 6f;
        float d = left ? -1 : 1;
        Gfx.line(ms, cx + 2 * d, cy - 4, cx - 2 * d, cy, 1.6f, h ? WHITE : MUTED);
        Gfx.line(ms, cx - 2 * d, cy, cx + 2 * d, cy + 4, 1.6f, h ? WHITE : MUTED);
        hitL(x - 6, y - 8, 20, 22, () -> {
            int i = carIndex();
            int n = cars.size();
            if (n > 0) selectCar(((i + (left ? -1 : 1)) % n + n) % n);
        });
    }

    // ------------------------------------------------------------------ taburi

    private void paintTab(GuiGraphics ms, float x, float y, float w, float lmx, float lmy, long now) {
        Gfx.text(ms, "CULORI", x + 14, y + 20, 6.4f, FAINT, 0, false, 0.9f);
        float sx = x + 20f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 300.0);
        for (int c : PRESETS) {
            boolean s = c == color;
            boolean h = inside(lmx, lmy, sx - 9, y + 28, 18, 18);
            int col = 0xFF000000 | c;
            if (s) Gfx.ring(ms, sx, y + 37, 8.6f, 10.2f + pulse * 0.6f, GOLD, GOLD2);
            Gfx.disc(ms, sx, y + 37, h || s ? 7.6f : 7f, Gfx.mixArgb(col, 0xFFFFFFFF, 0.3f), Gfx.mixArgb(col, 0xFF000000, 0.15f), -2, -2.4f);
            final int cc = c;
            hitL(sx - 9, y + 28, 18, 18, () -> color = cc);
            sx += 22.4f;
        }
        Gfx.text(ms, "RGB LIBER", x + 14, y + 70, 6.4f, FAINT, 0, false, 0.9f);
        String[] names = {"R", "G", "B"};
        int[] vals = {(color >> 16) & 255, (color >> 8) & 255, color & 255};
        int[] cols = {0xFFE2453F, 0xFF5FDC8F, 0xFF5BA8FF};
        for (int i = 0; i < 3; i++) {
            float ty = y + 84 + i * 24;
            Gfx.text(ms, names[i], x + 16, ty + 5.5f, 8, cols[i], 0, true, 0f);
            Gfx.rrect(ms, x + 34, ty + 1, w - 90, 6, 3, 0xFF23262D);
            float fw = (w - 90) * vals[i] / 255f;
            Gfx.rrect(ms, x + 34, ty + 1, Math.max(4, fw), 6, 3, Gfx.mixArgb(cols[i], 0xFFFFFFFF, 0.2f), cols[i]);
            boolean hot = slider == i || inside(lmx, lmy, x + 34 + fw - 8, ty - 4, 16, 16);
            Gfx.disc(ms, x + 34 + fw, ty + 4, hot ? 6.4f : 5.4f, 0xFFFFFFFF, 0xFFD9D6CE, -1, -1.2f);
            Gfx.text(ms, String.valueOf(vals[i]), x + w - 14, ty + 6, 7.8f, WHITE, 2, true, 0f);
        }
        float hy = y + 164;
        Gfx.disc(ms, x + 28, hy + 6, 11, Gfx.mixArgb(0xFF000000 | color, 0xFFFFFFFF, 0.28f), 0xFF000000 | color, -3, -3.4f);
        Gfx.text(ms, hex(color), x + 48, hy + 4, 11, WHITE, 0, true, 0.5f);
        double pc = "dealer".equals(mode) ? 0 : (CarsClient.tuning.has("paint") ? CarsClient.tuning.get("paint").getAsDouble() : 0);
        String note = "dealer".equals(mode) ? "Vopseaua e gratis la cumpărare." : (pc > 0 ? "Vopsea nouă: " + price(pc) + " lei" : "Vopseaua e gratis.");
        Gfx.text(ms, note, x + 48, hy + 15, 7.2f, MUTED, 0);
    }

    private void engineTab(GuiGraphics ms, float x, float y, float w, float lmx, float lmy) {
        if ("-".equals(fromEngine) || tierOf(fromEngine) == -2) {
            Gfx.text(ms, "Acest vehicul nu are motor", x + w / 2f, y + 96, 9, WHITE, 1);
            Gfx.text(ms, "care poate fi îmbunătățit.", x + w / 2f, y + 110, 9, WHITE, 1);
            return;
        }
        int cur = tierOf(fromEngine);
        int chosen = tierOf(engine);
        Gfx.text(ms, "NIVEL MOTOR · viteză maximă și accelerație", x + 14, y + 18, 6.4f, FAINT, 0, false, 0.6f);
        int row = 0;
        for (int i = 0; i < TIERS.length; i++) {
            // doar nivelurile care exista cu adevarat in modul de vehicule (altfel motorul ar aparea lipsa)
            if (!net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(new net.minecraft.util.ResourceLocation(engineId(i)))) continue;
            float ry = y + 28 + row * 34;
            row++;
            boolean s = chosen == i;
            boolean h = inside(lmx, lmy, x + 10, ry, w - 20, 30);
            if (s) Gfx.rrect(ms, x + 10, ry, w - 20, 30, 10, GOLD, GOLD2);
            Gfx.rrect(ms, x + (s ? 11 : 10), ry + (s ? 1 : 0), w - (s ? 22 : 20), s ? 28 : 30, s ? 9 : 10, s ? 0xFF262A32 : (h ? 0xFF22262D : 0xFF1A1D23), s ? 0xFF1E2127 : (h ? 0xFF1C1F25 : 0xFF16181D));
            Gfx.text(ms, TIER_NAMES[i], x + 24, ry + 19, 9.5f, s ? WHITE : 0xFFD4D2CC, 0, true, 0.2f);
            int pw = tierPower(i);
            for (int k = 0; k < 5; k++) {
                float cx = x + 96 + k * 11;
                Gfx.disc(ms, cx, ry + 15, 3.6f, k < pw ? (s ? GOLD : 0xFFB08A3C) : 0xFF2A2D34);
            }
            String lab;
            int lc;
            if (i == cur) {
                lab = "INSTALAT";
                lc = 0xFF7BE3A0;
            } else {
                double d = Math.max(0, tierPrice(i) - tierPrice(cur));
                lab = d <= 0 ? "gratis" : "+" + price(d) + " lei";
                lc = d <= 0 ? 0xFF7BE3A0 : (afford(d) ? GOLD : 0xFFF08A82);
            }
            Gfx.text(ms, lab, x + w - 22, ry + 19, 8.4f, lc, 2, true, 0f);
            final int ti = i;
            hitL(x + 10, ry, w - 20, 30, () -> engine = engineId(ti));
        }
    }

    private void wheelsTab(GuiGraphics ms, float x, float y, float w, float lmx, float lmy) {
        if ("-".equals(fromWheels)) {
            Gfx.text(ms, "Acest vehicul nu are roți", x + w / 2f, y + 96, 9, WHITE, 1);
            Gfx.text(ms, "care pot fi schimbate.", x + w / 2f, y + 110, 9, WHITE, 1);
            return;
        }
        JsonArray list = wheelList();
        float gx = x + 100;
        Gfx.text(ms, "ADERENȚĂ", gx, y + 18, 5.6f, FAINT, 0, false, 0.5f);
        Gfx.text(ms, "VITEZĂ", gx + 38, y + 18, 5.6f, FAINT, 0, false, 0.5f);
        Gfx.text(ms, "TEREN", gx + 76, y + 18, 5.6f, FAINT, 0, false, 0.5f);
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) continue;
            JsonObject o = list.get(i).getAsJsonObject();
            String id = JsonClientModule.str(o, "id", "");
            float ry = y + 24 + i * 25;
            boolean s = id.equals(wheels);
            boolean h = inside(lmx, lmy, x + 10, ry, w - 20, 22);
            if (s) Gfx.rrect(ms, x + 10, ry, w - 20, 22, 9, GOLD, GOLD2);
            Gfx.rrect(ms, x + (s ? 11 : 10), ry + (s ? 1 : 0), w - (s ? 22 : 20), s ? 20 : 22, s ? 8 : 9, s ? 0xFF262A32 : (h ? 0xFF22262D : 0xFF1A1D23), s ? 0xFF1E2127 : (h ? 0xFF1C1F25 : 0xFF16181D));
            Gfx.text(ms, clipG(JsonClientModule.str(o, "name", id), 8.4f, true, 70), x + 22, ry + 14.4f, 8.4f, s ? WHITE : 0xFFD4D2CC, 0, true, 0f);
            miniBar(ms, gx, ry + 9, JsonClientModule.integer(o, "grip", 3), s);
            miniBar(ms, gx + 38, ry + 9, JsonClientModule.integer(o, "speed", 3), s);
            miniBar(ms, gx + 76, ry + 9, JsonClientModule.integer(o, "terrain", 3), s);
            String lab;
            int lc;
            if (id.equals(fromWheels)) {
                lab = "INSTALATE";
                lc = 0xFF7BE3A0;
            } else {
                double p = JsonClientModule.num(o, "price", 0);
                lab = p <= 0 ? "gratis" : "+" + price(p);
                lc = p <= 0 ? 0xFF7BE3A0 : (afford(p) ? GOLD : 0xFFF08A82);
            }
            Gfx.text(ms, lab, x + w - 20, ry + 14.4f, 7.8f, lc, 2, true, 0f);
            hitL(x + 10, ry, w - 20, 22, () -> wheels = id);
        }
    }

    private static void miniBar(GuiGraphics ms, float x, float y, int v, boolean sel) {
        for (int k = 0; k < 5; k++) Gfx.rrect(ms, x + k * 7, y, 5.4f, 4, 1.5f, k < v ? (sel ? GOLD : 0xFFB08A3C) : 0xFF2A2D34);
    }

    private void gold(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, float lmx, float lmy, float dt, Runnable r) {
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

    // ------------------------------------------------------------------ actiuni

    private void apply() {
        if ("dealer".equals(mode)) {
            double extra = engineCost() + wheelsCost();
            if (done != null) done.apply(color, engine, wheels, extra);
            Minecraft.getInstance().setScreen(parent);
            return;
        }
        if (waiting || carId < 0 || !changed()) return;
        JsonObject o = new JsonObject();
        o.addProperty("car", carId);
        o.addProperty("mode", mode);
        o.addProperty("fromEngine", fromEngine);
        o.addProperty("fromWheels", fromWheels);
        o.addProperty("fromColor", fromColor);
        o.addProperty("color", color);
        o.addProperty("engine", engine);
        o.addProperty("wheels", wheels);
        waiting = true;
        CarsClient.request("tune", o);
    }

    @Override
    public void onClose() {
        if ("dealer".equals(mode) && parent != null) Minecraft.getInstance().setScreen(parent);
        else super.onClose();
    }

    // ------------------------------------------------------------------ previzualizare 3D

    private static CompoundNBT stack(String id) {
        CompoundNBT s = new CompoundNBT();
        s.putString("id", id);
        s.putByte("Count", (byte) 1);
        return s;
    }

    private void renderPreview(GuiGraphics g, float cx, float groundY, float w, float h, float dt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || carType.isEmpty()) return;
        String key = carType + "|" + color + "|" + engine + "|" + wheels;
        if (!key.equals(previewKey) || preview == null) {
            previewKey = key;
            preview = EntityType.byString(carType).map(t -> t.create(mc.level)).orElse(null);
            if (preview != null) {
                try {
                    CompoundNBT t = preview.saveWithoutId(new CompoundNBT());
                    t.put("Color", new IntArrayNBT(new int[] {(color >> 16) & 255, (color >> 8) & 255, color & 255}));
                    if (!"-".equals(engine) && !engine.equals(fromEngine)) t.put("EngineStack", stack(engine));
                    if (!"-".equals(wheels) && !wheels.equals(fromWheels)) t.put("WheelStack", stack(wheels));
                    if (!"dealer".equals(mode)) {
                        if (!"-".equals(fromEngine) && engine.equals(fromEngine)) t.put("EngineStack", stack(engine));
                        if (!"-".equals(fromWheels) && wheels.equals(fromWheels)) t.put("WheelStack", stack(wheels));
                    }
                    preview.load(t);
                } catch (RuntimeException ignored) {
                    // unele vehicule nu accepta culoare / piese
                }
            }
        }
        if (preview == null) return;
        if (!dragging) spin += 26f * dt;
        float size = Math.max(preview.getBbWidth(), preview.getBbHeight());
        float sc = Math.min(w * 0.30f, (h - 30) * 0.95f) / Math.max(1f, size);
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

    // ------------------------------------------------------------------ mouse

    private void sliderTo(double mx) {
        float lmx = (float) ((mx - offX) / scale);
        float x = 248f + 34f;
        float w = (W - 248f - 16f) - 90f;
        int v = Math.max(0, Math.min(255, Math.round((lmx - x) / w * 255f)));
        int r = (color >> 16) & 255;
        int gg = (color >> 8) & 255;
        int b = color & 255;
        if (slider == 0) r = v;
        else if (slider == 1) gg = v;
        else if (slider == 2) b = v;
        color = (r << 16) | (gg << 8) | b;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tab == 0 && button == 0 && CarsClient.tuning.size() > 0) {
            float lmx = (float) ((mx - offX) / scale);
            float lmy = (float) ((my - offY) / scale);
            for (int i = 0; i < 3; i++) {
                float ty = 40 + 84 + i * 24;
                if (inside(lmx, lmy, 248 + 28, ty - 5, (W - 248 - 16) - 78, 18)) {
                    slider = i;
                    sliderTo(mx);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (slider >= 0) {
            sliderTo(mx);
            return true;
        }
        if (dragging) {
            spin += (float) dx * 1.5f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        slider = -1;
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }
}
