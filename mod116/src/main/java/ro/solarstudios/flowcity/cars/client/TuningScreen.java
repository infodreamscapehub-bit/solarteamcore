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
    private static final int W = 520;
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

    // ------------------------------------------------------------------ date pentru statistici (folosit si de dealer)

    /** Puterea motorului 0..5 (0 = fara motor). */
    static int enginePower(String e) {
        if (e == null || "-".equals(e) || e.isEmpty()) return 0;
        int t = tierOf(e);
        if (t == -2) return 3;
        return t < 0 ? 1 : Math.max(1, Math.min(5, tierPower(t)));
    }

    /** {aderenta, viteza, teren} 0..5 pentru roata data (0 = fara roti). */
    static int[] wheelStats(String id) {
        if (id == null || "-".equals(id) || id.isEmpty()) return new int[] {0, 0, 0};
        for (JsonElement e : wheelList()) {
            if (e.isJsonObject() && id.equals(JsonClientModule.str(e.getAsJsonObject(), "id", ""))) {
                JsonObject o = e.getAsJsonObject();
                return new int[] {JsonClientModule.integer(o, "grip", 3), JsonClientModule.integer(o, "speed", 3), JsonClientModule.integer(o, "terrain", 3)};
            }
        }
        return new int[] {3, 3, 3};
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

    private static final float RX = 230f;
    private static final float RR = 504f;
    private static final float OY = 162f;

    private void content(GuiGraphics ms, float lmx, float lmy, long now, float dt) {
        Gfx.text(ms, "TUNING", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "dealer".equals(mode) ? "ÎNAINTE SĂ CUMPERI" : "ATELIER AUTO", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        boolean noCars = !"dealer".equals(mode) && (cars.size() == 0 || carId < 0);
        if (noCars) {
            Gfx.text(ms, loaded ? "Nu ai nicio mașină parcată în garaj." : "Se încarcă...", W / 2f, H / 2f - 4, 10, Sk.WHITE, 1);
            if (loaded) Gfx.text(ms, "Parchează-ți mașina la un garaj și revino.", W / 2f, H / 2f + 12, 7.8f, Sk.MUTED, 1);
            return;
        }
        if (CarsClient.tuning.size() == 0) {
            Gfx.text(ms, "Se încarcă prețurile...", W / 2f, H / 2f, 9, Sk.MUTED, 1);
            return;
        }

        // ---- meniu (stanga)
        String[] tabs = {"VOPSEA", "MOTOR", "ROȚI"};
        String[] vals = {hex(color), engineLabel(engine), wheelLabel(wheels)};
        for (int i = 0; i < 3; i++) {
            float y = 66f + i * 32f;
            boolean s = tab == i;
            boolean h = Sk.in(lmx, lmy, 12, y, 196, 28);
            Sk.slant(ms, s ? 18 : 12, y, 196, 28, 7f, s ? Sk.ACC : (h ? Sk.ROW_HOT : Sk.ROW));
            Gfx.text(ms, tabs[i], s ? 32 : 26, y + 18, 9.4f, s ? 0xFF061426 : Sk.WHITE, 0, true, 0.4f);
            if (i == 0) Gfx.rrect(ms, 190, y + 8, 12, 12, 0, 0xFF000000 | color);
            else Gfx.text(ms, clipG(vals[i], 6.6f, false, 70), 202, y + 17, 6.6f, s ? 0xFF1F4F85 : Sk.MUTED, 2);
            final int ti = i;
            hitL(12, y, 196, 28, () -> tab = ti);
        }

        // ---- statistici ale configuratiei alese
        int pw = enginePower(engine);
        int[] ws = wheelStats(wheels);
        stat(ms, "VITEZĂ MAX", 16, 172, Math.min(10, Math.round(pw * 1.2f + ws[1] * 0.8f)));
        stat(ms, "ACCELERAȚIE", 16, 187, Math.min(10, pw * 2));
        stat(ms, "ADERENȚĂ", 16, 202, Math.min(10, ws[0] * 2));

        // ---- rezumat costuri
        float sy = 228f;
        sy = line(ms, "Vopsea", color != fromColor, paintCost(), sy);
        sy = line(ms, "Motor", !engine.equals(fromEngine), engineCost(), sy);
        sy = line(ms, "Roți", !wheels.equals(fromWheels), wheelsCost(), sy);

        // ---- buton
        double tot = total();
        String label;
        boolean enabled;
        if ("dealer".equals(mode)) {
            label = "CONFIRMĂ";
            enabled = true;
        } else if (waiting) {
            label = "SE APLICĂ";
            enabled = false;
        } else if (!changed()) {
            label = "NICIO SCHIMBARE";
            enabled = false;
        } else if (!afford(tot)) {
            label = "FĂRĂ BANI";
            enabled = false;
        } else {
            label = tot <= 0 ? "APLICĂ" : "APLICĂ  " + price(tot);
            enabled = true;
        }
        boolean hb = enabled && Sk.in(lmx, lmy, 12, 258, 196, 24);
        Sk.button(ms, label, 12, 258, 196, 24, enabled, true, hb);
        if (enabled) hitL(12, 258, 196, 24, this::apply);

        // ---- masina (dreapta)
        int paint = 0xFF000000 | color;
        Sk.floor(ms, RX, RR, 96f, 148f, paint);
        renderPreview(ms, (RX + RR) / 2f, 148f, 300f, 150f, dt);
        hitL(RX, 90, RR - RX, 70, () -> dragging = true);
        boolean multi = !"dealer".equals(mode) && cars.size() > 1;
        if (!carPlate.isEmpty()) Gfx.text(ms, carPlate, RX, 56, 6.8f, Sk.ACC, 0, false, 1.2f);
        Gfx.text(ms, clipG(carName, 18, true, RR - RX - (multi ? 50 : 0)), RX, 76, 18, Sk.WHITE, 0, true, 0f);
        if (multi) {
            arrow(ms, RR - 40, 56, true, lmx, lmy);
            arrow(ms, RR - 18, 56, false, lmx, lmy);
        }
        Gfx.text(ms, "trage pentru a roti", RR, 156, 5.8f, Sk.FAINT, 2);

        // ---- optiuni
        Gfx.rrect(ms, RX, OY, RR - RX, 120, 0, Sk.PANEL);
        Gfx.rrect(ms, RX, OY, 40, 2, 0, Sk.ACC);
        if (tab == 0) paintTab(ms, RX, OY, RR - RX, lmx, lmy);
        else if (tab == 1) engineTab(ms, RX, OY, RR - RX, lmx, lmy);
        else wheelsTab(ms, RX, OY, RR - RX, lmx, lmy);
    }

    private static void stat(GuiGraphics ms, String label, float x, float y, int v) {
        Gfx.text(ms, label, x, y + 6, 6.4f, Sk.MUTED, 0, false, 0.6f);
        Sk.segs(ms, x + 66, y, 124, 6, v, 10, Sk.ACC);
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

    private float line(GuiGraphics ms, String name, boolean ch, double cost, float y) {
        Gfx.text(ms, name, 16, y, 6.8f, ch ? Sk.WHITE : Sk.FAINT, 0);
        if (ch) Gfx.text(ms, cost <= 0 ? "gratis" : "+" + price(cost) + " lei", 204, y, 6.8f, cost <= 0 ? Sk.GOOD : Sk.ACC, 2);
        else Gfx.text(ms, "—", 204, y, 6.8f, Sk.FAINT, 2);
        return y + 10.5f;
    }

    private void arrow(GuiGraphics ms, float x, float y, boolean left, float lmx, float lmy) {
        boolean h = Sk.in(lmx, lmy, x, y, 18, 18);
        Gfx.rrect(ms, x, y, 18, 18, 0, h ? Sk.ACC : 0xFF14202F);
        float cx = x + 9f;
        float cy = y + 9f;
        float d = left ? -1 : 1;
        int c = h ? 0xFF061426 : Sk.MUTED;
        Gfx.line(ms, cx + 2 * d, cy - 4.5f, cx - 2 * d, cy, 1.7f, c);
        Gfx.line(ms, cx - 2 * d, cy, cx + 2 * d, cy + 4.5f, 1.7f, c);
        hitL(x, y, 18, 18, () -> {
            int i = carIndex();
            int n = cars.size();
            if (n > 0) selectCar(((i + (left ? -1 : 1)) % n + n) % n);
        });
    }

    // ------------------------------------------------------------------ taburi

    private void paintTab(GuiGraphics ms, float x, float y, float w, float lmx, float lmy) {
        Gfx.text(ms, "CULORI", x + 10, y + 16, 6.2f, Sk.FAINT, 0, false, 1f);
        for (int i = 0; i < PRESETS.length; i++) {
            int c = PRESETS[i];
            float sx = x + 10 + i * 26f;
            boolean sl = c == color;
            boolean h = Sk.in(lmx, lmy, sx, y + 22, 20, 20);
            if (sl) Gfx.rrect(ms, sx - 2.5f, y + 19.5f, 25, 25, 0, Sk.ACC);
            Gfx.rrect(ms, sx, y + 22, 20, 20, 0, Gfx.mixArgb(0xFF000000 | c, 0xFFFFFFFF, h ? 0.18f : 0f));
            hitL(sx, y + 22, 20, 20, () -> color = c);
        }
        Gfx.text(ms, "RGB", x + 10, y + 62, 6.2f, Sk.FAINT, 0, false, 1f);
        String[] names = {"R", "G", "B"};
        int[] vals = {(color >> 16) & 255, (color >> 8) & 255, color & 255};
        int[] cols = {0xFFE2453F, 0xFF5FDC8F, 0xFF5BA8FF};
        for (int i = 0; i < 3; i++) {
            float ty = y + 72 + i * 15f;
            Gfx.text(ms, names[i], x + 10, ty + 5.5f, 7.4f, cols[i], 0, true, 0f);
            Gfx.rrect(ms, x + 28, ty + 2, 150, 4, 0, 0xFF1B2839);
            float fw = 150 * vals[i] / 255f;
            Gfx.rrect(ms, x + 28, ty + 2, Math.max(2, fw), 4, 0, cols[i]);
            boolean hot = slider == i || Sk.in(lmx, lmy, x + 28 + fw - 6, ty - 2, 12, 12);
            Gfx.rrect(ms, x + 28 + fw - 3, ty - 1, 6, 10, 0, hot ? Sk.ACC : 0xFFFFFFFF);
            Gfx.text(ms, String.valueOf(vals[i]), x + 198, ty + 6, 7, Sk.WHITE, 2, true, 0f);
        }
        Gfx.rrect(ms, x + w - 56, y + 70, 44, 30, 0, 0xFF000000 | color);
        Gfx.text(ms, hex(color), x + w - 34, y + 110, 6.2f, Sk.MUTED, 1);
        double pc = "dealer".equals(mode) ? 0 : (CarsClient.tuning.has("paint") ? CarsClient.tuning.get("paint").getAsDouble() : 0);
        Gfx.text(ms, "dealer".equals(mode) ? "gratis la cumpărare" : (pc > 0 ? price(pc) + " lei" : "gratis"), x + w - 12, y + 16, 6.4f, pc > 0 ? Sk.ACC : Sk.GOOD, 2);
    }

    private void engineTab(GuiGraphics ms, float x, float y, float w, float lmx, float lmy) {
        if ("-".equals(fromEngine) || tierOf(fromEngine) == -2) {
            Gfx.text(ms, "Acest vehicul nu are motor care poate fi îmbunătățit.", x + w / 2f, y + 64, 8.4f, Sk.MUTED, 1);
            return;
        }
        int cur = tierOf(fromEngine);
        int chosen = tierOf(engine);
        Gfx.text(ms, "NIVEL MOTOR", x + 10, y + 16, 6.2f, Sk.FAINT, 0, false, 1f);
        java.util.List<Integer> avail = new java.util.ArrayList<>();
        for (int i = 0; i < TIERS.length; i++) {
            // doar nivelurile care exista cu adevarat in modul de vehicule (altfel motorul ar aparea lipsa)
            if (net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(new net.minecraft.util.ResourceLocation(engineId(i)))) avail.add(i);
        }
        int n = Math.max(1, avail.size());
        float gap = 6f;
        float cw = Math.min(70f, (w - 20 - (n - 1) * gap) / n);
        for (int k = 0; k < avail.size(); k++) {
            int i = avail.get(k);
            float cx = x + 10 + k * (cw + gap);
            float cy = y + 24;
            boolean s = chosen == i;
            boolean h = Sk.in(lmx, lmy, cx, cy, cw, 88);
            Sk.slant(ms, cx, cy, cw, 88, 6f, s ? Sk.ACC : (h ? Sk.ROW_HOT : Sk.ROW));
            int fg = s ? 0xFF061426 : Sk.WHITE;
            Gfx.text(ms, TIER_NAMES[i], cx + cw / 2f + 3, cy + 20, 8.6f, fg, 1, true, 0f);
            int pw = tierPower(i);
            for (int q = 0; q < 5; q++) Gfx.rrect(ms, cx + cw / 2f - 14 + 3, cy + 54 - q * 7, 22, 4, 0, q < pw ? (s ? 0xFF061426 : Sk.ACC) : (s ? 0xFF2E78C9 : 0xFF1B2839));
            String lab;
            int lc;
            if (i == cur) {
                lab = "INSTALAT";
                lc = s ? 0xFF061426 : Sk.GOOD;
            } else {
                double d = Math.max(0, tierPrice(i) - tierPrice(cur));
                lab = d <= 0 ? "gratis" : "+" + price(d);
                lc = s ? 0xFF061426 : (d <= 0 ? Sk.GOOD : (afford(d) ? Sk.WHITE : Sk.BAD));
            }
            Gfx.text(ms, lab, cx + cw / 2f + 3, cy + 80, 6.8f, lc, 1);
            final int ti = i;
            hitL(cx, cy, cw, 88, () -> engine = engineId(ti));
        }
    }

    private void wheelsTab(GuiGraphics ms, float x, float y, float w, float lmx, float lmy) {
        if ("-".equals(fromWheels)) {
            Gfx.text(ms, "Acest vehicul nu are roți care pot fi schimbate.", x + w / 2f, y + 64, 8.4f, Sk.MUTED, 1);
            return;
        }
        Gfx.text(ms, "ROȚI", x + 10, y + 16, 6.2f, Sk.FAINT, 0, false, 1f);
        JsonArray list = wheelList();
        float gap = 6f;
        float tw = (w - 20 - 3 * gap) / 4f;
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isJsonObject()) continue;
            JsonObject o = list.get(i).getAsJsonObject();
            String id = JsonClientModule.str(o, "id", "");
            float cx = x + 10 + (i % 4) * (tw + gap);
            float cy = y + 24 + (i / 4) * 48f;
            boolean s = id.equals(wheels);
            boolean h = Sk.in(lmx, lmy, cx, cy, tw, 42);
            Sk.slant(ms, cx, cy, tw, 42, 5f, s ? Sk.ACC : (h ? Sk.ROW_HOT : Sk.ROW));
            Gfx.text(ms, clipG(JsonClientModule.str(o, "name", id), 7.8f, true, tw - 12), cx + tw / 2f + 2, cy + 17, 7.8f, s ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
            String lab;
            int lc;
            if (id.equals(fromWheels)) {
                lab = "INSTALATE";
                lc = s ? 0xFF061426 : Sk.GOOD;
            } else {
                double p = JsonClientModule.num(o, "price", 0);
                lab = p <= 0 ? "gratis" : "+" + price(p);
                lc = s ? 0xFF061426 : (p <= 0 ? Sk.GOOD : (afford(p) ? Sk.MUTED : Sk.BAD));
            }
            Gfx.text(ms, lab, cx + tw / 2f + 2, cy + 33, 6.6f, lc, 1);
            hitL(cx, cy, tw, 42, () -> wheels = id);
        }
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
        float x = RX + 28f;
        float w = 150f;
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
                float ty = OY + 72 + i * 15f;
                if (inside(lmx, lmy, RX + 20, ty - 4, 166, 14)) {
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
