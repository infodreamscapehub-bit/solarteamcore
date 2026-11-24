package ro.solarstudios.flowcity.cars.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.cars.CarsNet;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Bordul FlowCity pentru vehiculele MrCrayfish (1.16.5, 0.44 / 0.45). Valorile se citesc prin reflectie, deci merge
 * cu orice versiune a modului de vehicule fara sa depinda de ea la compilare. Inlocuieste textul "BPS / Fuel".
 */
public final class CarHud {
    private CarHud() {}

    private static Class<?> powered;
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static boolean portHudOff;
    private static float shownSpeed;
    private static float shownFrac;
    private static double kmhPerUnit = 3.6;
    private static int plateFor = -1;
    private static int askedFor = -1;
    private static long askedAt;
    private static String plate = "";
    private static int odoFor = -1;
    private static String odoDigits = "0000000";
    private static String tripStr = "0.0";
    private static String journeyStr = "0.0";
    private static String metaModel = "";
    private static String metaPlate = "";
    private static String metaOwner = "";
    private static String metaReg = "";
    private static String metaId = "";
    /** Panoul de informatii (tasta "Informatii vehicul"). */
    public static boolean showInfo;
    private static float infoAnim;

    /** Datele fixe ale vehiculului (trimise o data, cand urci in el). */
    public static void setMeta(String model, String plate, String owner, String reg, String vid) {
        metaModel = model == null ? "" : model;
        metaPlate = plate == null ? "" : plate;
        metaOwner = owner == null ? "" : owner;
        metaReg = reg == null ? "" : reg;
        metaId = vid == null ? "" : vid;
    }

    public static void setJourney(double m) {
        long tt = (long) (Math.max(0, m) / 100.0);
        journeyStr = (tt / 10) + "." + (tt % 10);
    }

    /** Chilometrajul trimis de server: metri totali si metri de calatorie pentru vehiculul cu id-ul dat. */
    public static void setOdo(int entityId, double odoM, double tripM) {
        if (odoFor != entityId) setMeta("", "", "", "", "");
        odoFor = entityId;
        long t = (long) (Math.max(0, odoM) / 100.0);
        StringBuilder sb = new StringBuilder(Long.toString(Math.min(9999999L, t)));
        while (sb.length() < 7) sb.insert(0, '0');
        odoDigits = sb.toString();
        long tt = (long) (Math.max(0, tripM) / 100.0);
        tripStr = (tt / 10) + "." + (tt % 10);
    }

    /** Raspunsul serverului cu numarul de inmatriculare. */
    public static void setPlate(int entityId, String p) {
        plateFor = entityId;
        plate = p == null ? "" : p;
    }

    public static void register() {
        try {
            powered = Class.forName("com.mrcrayfish.vehicle.entity.PoweredVehicleEntity");
        } catch (ClassNotFoundException | LinkageError e) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(new CarHud.Events());
    }

    private static Object call(Object o, String name) {
        try {
            Method m = METHODS.get(name);
            if (m == null && !METHODS.containsKey(name)) {
                Class<?> c = o.getClass();
                while (c != null && m == null) {
                    for (Method x : c.getDeclaredMethods()) {
                        if (x.getName().equals(name) && x.getParameterCount() == 0) {
                            x.setAccessible(true);
                            m = x;
                            break;
                        }
                    }
                    c = c.getSuperclass();
                }
                METHODS.put(name, m);
            }
            return m == null ? null : m.invoke(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static double num(Object o, String name, double def) {
        Object v = call(o, name);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    private static boolean bool(Object o, String name, boolean def) {
        Object v = call(o, name);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    /** Opreste vitezometrul text al modului (Config.CLIENT.enabledSpeedometer = false). */
    private static void disablePortHud() {
        portHudOff = true;
        try {
            Class<?> cfg = Class.forName("com.mrcrayfish.vehicle.Config");
            Field client = cfg.getField("CLIENT");
            Object c = client.get(null);
            Field sp = c.getClass().getField("enabledSpeedometer");
            Object value = sp.get(c);
            value.getClass().getMethod("set", Object.class).invoke(value, Boolean.FALSE);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // versiunea modului nu are optiunea: ramane si textul lor
        }
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            Entity v = mc.player.getVehicle();
            if (v == null || !powered.isInstance(v)) return;
            if (!portHudOff) disablePortHud();
            try {
                draw(new GuiGraphics(e.getMatrixStack()), mc, v);
            } catch (RuntimeException ignored) {
                // un vehicul ciudat nu trebuie sa strice HUD-ul
            }
        }
    }

    private static final int SEGMENTS = 24;
    private static final float[] SEG_POS = {0f, 0.5f, 0.78f, 1f};
    private static final int[] SEG_COL = {0xFF4DA3FF, 0xFFEAF4FF, 0xFFFFB84D, 0xFFE2453F};
    private static final int GREEN = 0xFF6DBE6F;
    private static final int OFF = 0xFF1E2733;
    private static final int MUTED = 0xFF7F8A99;

    /**
     * Bordul FlowCity (design nou): arc segmentat de viteza albastru -> alb -> rosu, viteza mare in centru, treapta D/N/R,
     * iar in dreapta chilometrajul cu cifre in casute, tripul, combustibilul si starea. Coordonatele sunt ale unei machete
     * de 300 x 145 care se scaleaza dupa ecran.
     */
    private static void draw(GuiGraphics g, Minecraft mc, Entity v) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();

        // numarul de inmatriculare: il cerem o data pe vehicul
        int id = v.getId();
        if (plateFor != id && (askedFor != id || now - askedAt > 5000)) {
            askedFor = id;
            askedAt = now;
            CarsNet.send("plate", id, 0);
        }
        String pl = plateFor == id ? plate : "";

        // valori
        double rawSpeed = num(v, "getSpeed", 0);
        double speed = Math.abs(rawSpeed);
        float kmh = (float) Math.abs(num(v, "getKilometersPreHour", 0));
        if (speed > 0.5 && kmh > 1) kmhPerUnit = kmh / speed;
        double max = num(v, "getActualMaxSpeed", num(v, "getMaxSpeed", 0));
        int dialMax = (int) Math.max(60, Math.ceil(max * kmhPerUnit / 30.0) * 30);
        shownSpeed += (kmh - shownSpeed) * 0.25f;
        float frac = Math.min(1f, shownSpeed / dialMax);
        shownFrac += (frac - shownFrac) * 0.3f;
        boolean engine = bool(v, "isEnginePowered", true);
        boolean boost = bool(v, "isBoosting", false);
        double maxHp = num(v, "getMaxHealth", 0);
        float hp = maxHp <= 0 ? 1f : (float) (num(v, "getHealth", maxHp) / maxHp);
        boolean fuel = bool(v, "requiresFuel", false);
        float f = 1f;
        if (fuel) {
            double cap = num(v, "getFuelCapacity", 0);
            f = cap <= 0 ? 0 : (float) (num(v, "getCurrentFuel", 0) / cap);
        }
        boolean low = fuel && f < 0.12f;
        boolean blink = low && (now / 400) % 2 == 0;
        String gear = shownSpeed < 1.5f ? "N" : (rawSpeed < -0.01 ? "R" : "D");

        // asezare: panou lat 300 x 145, dreapta jos
        float rw = Math.max(140f, Math.min(210f, h * 0.5f));
        float k = rw / 300f;
        float rh = 145f * k;
        float px = w - 17 - rw;
        float py = h - 13 - rh;
        if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(g, "speedo", px, py, rw, rh)) return;
        g.pose().pushPose();
        g.pose().translate(px, py, 0);
        g.pose().scale(k, k, 1f);

        final int acc = ro.solarstudios.flowcity.client.ui.HudLayout.accent("speedo", 0);

        // panou
        Gfx.rrect(g, 0, 8, 300, 145, 16, 0x50000000);
        Gfx.rrect(g, -1, -1, 302, 147, 15, 0x22FFFFFF);
        Gfx.rrect(g, 0, 0, 300, 145, 14, 0xF0101A28, 0xF0070B12);
        Gfx.line(g, 178, 16, 178, 129, 1f, 0x1FFFFFFF);

        // arcul segmentat
        float cx = 96;
        float cy = 84;
        float r = 66;
        float span = 270f / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            float t = (i + 0.5f) / SEGMENTS;
            float d0 = 135 + i * span + 1.6f;
            float d1 = 135 + (i + 1) * span - 1.6f;
            boolean lit = t <= shownFrac + 0.002f;
            final int col = acc != 0 ? acc : Gfx.stops(t, SEG_POS, SEG_COL);
            if (lit) {
                Gfx.arc(g, cx, cy, r, 13, d0, d1, (x, y) -> col, 0.13f);
                Gfx.arc(g, cx, cy, r, 7, d0, d1, (x, y) -> col, 1f);
            } else {
                Gfx.arc(g, cx, cy, r, 7, d0, d1, (x, y) -> OFF, 1f);
            }
        }
        // cifre in interiorul arcului
        for (int i = 0; i <= 6; i++) {
            float t = i / 6f;
            double a = Math.toRadians(135 + 270.0 * t);
            float tr = r - 15;
            Gfx.text(g, String.valueOf(Math.round(dialMax * t)), cx + (float) Math.cos(a) * tr, cy + (float) Math.sin(a) * tr + 3f, 8f,
                t <= shownFrac + 0.001f ? 0xFFC9D3E0 : 0xFF4A5666, 1);
        }

        // lumini: motor + rezerva
        Gfx.disc(g, cx - 14, 50, 3.5f, engine ? GREEN : 0xFF3A4350);
        if (engine) Gfx.disc(g, cx - 14, 50, 6.5f, 0x2A6DBE6F);
        Gfx.disc(g, cx + 14, 50, 3.5f, low ? (blink ? 0xFFFF6A5E : 0xFF8A2F2A) : 0xFF2A323D);

        // viteza + treapta
        Gfx.text(g, String.valueOf(Math.round(shownSpeed)), cx, 94, 44, 0xFFF4F7FB, 1, true, -1f);
        Gfx.text(g, boost ? "BOOST" : "KM/H", cx, 108, 9, boost ? Theme.ACCENT : MUTED, 1, false, 2.4f);
        Gfx.text(g, gear, cx, 131, 13, acc != 0 ? acc : 0xFF4DA3FF, 1, true, 0f);

        // chilometraj: cifre in casute
        boolean has = odoFor == id;
        Gfx.text(g, "ODOMETRU", 188, 29, 8.5f, MUTED, 0, false, 1.6f);
        for (int i = 0; i < 7; i++) {
            float bx = 188 + i * 14 + (i == 6 ? 5 : 0);
            Gfx.rrect(g, bx, 35, 12, 18, 3, 0xFF141E2C, 0xFF0A1018);
            Gfx.text(g, has ? odoDigits.substring(i, i + 1) : "-", bx + 6, 48.5f, 14, i == 6 ? 0xFF4DA3FF : 0xFFF4F7FB, 1);
        }
        Gfx.disc(g, 188 + 6 * 14 + 1.5f, 50.5f, 1.4f, 0xFF4DA3FF);
        Gfx.text(g, "km", 290, 62, 8, MUTED, 2);

        // trip
        Gfx.text(g, "TRIP", 188, 78, 8.5f, MUTED, 0, false, 1.6f);
        Gfx.text(g, has ? tripStr + " km" : "- km", 290, 79, 15, 0xFFF4F7FB, 2, true, 0f);

        // combustibil + stare
        float ry = 98;
        if (fuel) {
            int col = low ? Theme.BAD : Theme.ACCENT;
            Gfx.text(g, "COMB.", 188, ry + 5.5f, 8, blink ? 0xFFFF6A5E : MUTED, 0, false, 1.2f);
            Gfx.bar(g, 222, ry + 1, 46, 5, f, OFF, col);
            Gfx.text(g, Math.round(f * 100) + "%", 290, ry + 6, 8.5f, blink ? 0xFFFF6A5E : 0xFFB5BFCC, 2);
            ry += 16;
        } else {
            ry += 8;
        }
        int hc = hp < 0.3f ? Theme.BAD : GREEN;
        Gfx.text(g, "STARE", 188, ry + 5.5f, 8, MUTED, 0, false, 1.2f);
        Gfx.bar(g, 222, ry + 1, 46, 5, hp, OFF, hc);
        Gfx.text(g, Math.round(hp * 100) + "%", 290, ry + 6, 8.5f, hp < 0.3f ? Theme.BAD : 0xFFB5BFCC, 2);

        // nume + numar deasupra panoului
        String name = v.getDisplayName().getString();
        if (name.length() > 24) name = name.substring(0, 23) + "...";
        float nw = Gfx.width(name, 13, false);
        float pw = pl.isEmpty() ? 0 : Gfx.width(pl, 11, false) + 0.66f * (pl.length() - 1) + 12;
        float tw = 24 + nw + (pl.isEmpty() ? 0 : 8 + pw);
        float tx = 300 - tw;
        float ty = -37;
        Gfx.rrect(g, tx - 1, ty - 1, tw + 2, 29, 10, 0x12FFFFFF);
        Gfx.rrect(g, tx, ty, tw, 27, 9, 0xD10E0F12);
        Gfx.text(g, name, tx + 12, ty + 18.5f, 13, 0xFFEDEDEA, 0);
        if (!pl.isEmpty()) {
            float qx = tx + 12 + nw + 8;
            Gfx.rrect(g, qx, ty + 5.5f, pw, 16, 4, 0xFFF4F1EA);
            Gfx.text(g, pl, qx + 6, ty + 17.5f, 11, 0xFF14161A, 0, false, 0.66f);
        }
        // panoul de informatii (tasta "Informatii vehicul"), deasupra numelui
        infoAnim += ((showInfo ? 1f : 0f) - infoAnim) * 0.2f;
        if (infoAnim > 0.02f) drawInfo(g, has, pl, ty - 6);
        g.pose().popPose();
        ro.solarstudios.flowcity.client.ui.HudLayout.end(g);

        if (fuel && f <= 0f) Gfx.text(g, "Rezervorul e gol", w / 2f, h - 64, 10, Theme.BAD, 1);
    }

    private static void drawInfo(GuiGraphics g, boolean has, String pl, float bottom) {
        float a = infoAnim;
        float ph = 104;
        float y0 = bottom - ph + (1 - a) * 10;
        int al = Math.round(a * 255) << 24;
        int bg = (Math.round(a * 0xEE) << 24) | 0x0B111B;
        Gfx.rrect(g, -1, y0 - 1, 302, ph + 2, 13, (Math.round(a * 0x22) << 24) | 0xFFFFFF);
        Gfx.rrect(g, 0, y0, 300, ph, 12, bg);
        Gfx.rrect(g, 0, y0, 4, ph, 2, al | 0x4DA3FF);
        String model = metaModel.isEmpty() ? "Vehicul" : metaModel;
        Gfx.text(g, "INFORMATII VEHICUL", 14, y0 + 14, 7.5f, al | 0x4DA3FF, 0, false, 1.6f);
        if (!metaId.isEmpty()) Gfx.text(g, metaId, 288, y0 + 14, 7.5f, al | 0x7F8A99, 2);
        Gfx.text(g, model.length() > 30 ? model.substring(0, 29) + "..." : model, 14, y0 + 31, 13, al | 0xF4F7FB, 0, true, 0f);
        String plate = !pl.isEmpty() ? pl : (metaPlate.isEmpty() ? "-" : metaPlate);
        String[] lab = {"KM TOTAL", "TRIP", "CALATORIE"};
        String[] val = {has ? kmStr() : "-", has ? tripStr + " km" : "-", has ? journeyStr + " km" : "-"};
        for (int i = 0; i < 3; i++) {
            float cx = 14 + i * 94;
            Gfx.rrect(g, cx, y0 + 40, 86, 30, 6, al | 0x141E2C);
            Gfx.text(g, lab[i], cx + 7, y0 + 50, 6.5f, al | 0x7F8A99, 0, false, 1f);
            Gfx.text(g, val[i], cx + 7, y0 + 64, 10, al | 0xF4F7FB, 0, true, 0f);
        }
        Gfx.text(g, "Nr: " + plate, 14, y0 + 84, 8.5f, al | 0xC9D3E0, 0);
        Gfx.text(g, "Proprietar: " + (metaOwner.isEmpty() ? "-" : metaOwner), 14, y0 + 96, 8.5f, al | 0xC9D3E0, 0);
        if (!metaReg.isEmpty()) Gfx.text(g, "Inregistrat " + metaReg, 288, y0 + 96, 8, al | 0x7F8A99, 2);
    }

    private static String kmStr() {
        long t = 0;
        for (char ch : odoDigits.toCharArray()) t = t * 10 + (ch - '0');
        long kmInt = t / 10;
        String s = String.format(java.util.Locale.ROOT, "%,d", kmInt).replace(',', '.');
        return s + " km";
    }
}
