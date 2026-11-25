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
            boolean in = v != null && powered.isInstance(v);
            long now = System.currentTimeMillis();
            float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
            lastFrame = now;
            appear += ((in ? 1f : 0f) - appear) * Math.min(1f, dt * 9f);
            try {
                if (in) {
                    if (!portHudOff) disablePortHud();
                    read(mc, v, now);
                } else {
                    // iesit din vehicul: cifrele coboara spre 0 si bordul dispare animat
                    shownSpeed += (0f - shownSpeed) * 0.2f;
                    shownFrac += (0f - shownFrac) * 0.25f;
                }
                if (appear > 0.02f && snap) draw(new GuiGraphics(e.getMatrixStack()), mc, now);
            } catch (RuntimeException ignored) {
                // un vehicul ciudat nu trebuie sa strice HUD-ul
            }
        }
    }

    private static final int OFF = 0xFF1B2430;
    private static final int GREEN = 0xFF6DBE6F;
    private static final int MUTED = 0xFF7F8A99;
    private static final int CYAN = 0xFF22D3EE;
    private static final float[] SEG_POS = {0f, 0.62f, 0.82f, 1f};
    private static final int[] SEG_COL = {0xFF22D3EE, 0xFF22D3EE, 0xFFFFB84D, 0xFFE2453F};

    private static long lastFrame = System.currentTimeMillis();
    private static float appear;
    private static boolean snap;
    private static int dialMax = 60;
    private static boolean sEngine;
    private static boolean sBoost;
    private static boolean sFuel;
    private static float sFrac = 1f;
    private static float sHp = 1f;
    private static boolean sHas;
    private static String sGear = "N";
    private static String sName = "";
    private static String sPlate = "";

    /** Citeste datele REALE ale vehiculului (reflectie MrCrayfish) si le pastreaza pentru desen. */
    private static void read(Minecraft mc, Entity v, long now) {
        int id = v.getId();
        if (plateFor != id && (askedFor != id || now - askedAt > 5000)) {
            askedFor = id;
            askedAt = now;
            CarsNet.send("plate", id, 0);
        }
        sPlate = plateFor == id ? plate : "";
        double rawSpeed = num(v, "getSpeed", 0);
        double speed = Math.abs(rawSpeed);
        float kmh = (float) Math.abs(num(v, "getKilometersPreHour", 0));
        if (speed > 0.5 && kmh > 1) kmhPerUnit = kmh / speed;
        double max = num(v, "getActualMaxSpeed", num(v, "getMaxSpeed", 0));
        dialMax = (int) Math.max(60, Math.ceil(max * kmhPerUnit / 30.0) * 30);
        shownSpeed += (kmh - shownSpeed) * 0.25f;
        float frac = Math.min(1f, shownSpeed / dialMax);
        shownFrac += (frac - shownFrac) * 0.3f;
        sEngine = bool(v, "isEnginePowered", true);
        sBoost = bool(v, "isBoosting", false);
        double maxHp = num(v, "getMaxHealth", 0);
        sHp = maxHp <= 0 ? 1f : (float) (num(v, "getHealth", maxHp) / maxHp);
        sFuel = bool(v, "requiresFuel", false);
        sFrac = 1f;
        if (sFuel) {
            double cap = num(v, "getFuelCapacity", 0);
            sFrac = cap <= 0 ? 0 : (float) (num(v, "getCurrentFuel", 0) / cap);
        }
        sGear = shownSpeed < 1.5f ? "N" : (rawSpeed < -0.01 ? "R" : "D");
        sHas = odoFor == id;
        String name = v.getDisplayName().getString();
        sName = name.length() > 24 ? name.substring(0, 23) + "..." : name;
        snap = true;
    }

    /** Dimensiunea implicita a bordului (aceeasi formula in editorul de HUD). */
    public static float[] defaultRect(int sw, int sh) {
        float rw = Math.max(130f, Math.min(215f, sh * 0.40f));
        float rh = 150f * rw / 190f;
        return new float[] {sw - 14f - rw, sh - 10f - rh, rw, rh};
    }

    /**
     * Bord compact: vitezometru circular cu arc cyan (spre rosu la viteza mare), viteza si treapta in centru, un cadran mic de
     * combustibil in stanga, indicatori reali deasupra cifrei si kilometrajul total + trip discret dedesubt. Coordonatele sunt
     * ale unei machete de 190 x 150 care se scaleaza dupa ecran si se poate muta/scala din editorul de HUD.
     */
    private static void draw(GuiGraphics g, Minecraft mc, long now) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        float[] d = defaultRect(w, h);
        float k = d[2] / 190f;
        if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(g, "speedo", d[0], d[1], d[2], d[3])) return;
        float ap = Math.max(0f, Math.min(1f, appear));
        Gfx.alpha *= ap;
        g.pose().pushPose();
        g.pose().translate(d[0], d[1] + (1f - ap) * 14f, 0);
        g.pose().scale(k, k, 1f);
        final int acc = ro.solarstudios.flowcity.client.ui.HudLayout.accent("speedo", 0);

        boolean fuel = sFuel;
        boolean low = fuel && sFrac < 0.12f;
        boolean blink = low && (now / 400) % 2 == 0;
        float cx = 124f;
        float cy = 66f;
        float r = 56f;

        // fundal discret, ca cifrele sa se citeasca si pe cer deschis
        Gfx.disc(g, cx, cy, r + 9f, 0x55000000);
        Gfx.disc(g, cx, cy, r - 6f, 0x66070B12);

        // arcul: fundal intreg + parte aprinsa in felii (cyan -> portocaliu -> rosu)
        Gfx.arc(g, cx, cy, r, 7f, 135f, 405f, (x, y) -> OFF, 1f);
        float lit = 270f * Math.max(0f, Math.min(1f, shownFrac));
        int n = Math.max(0, (int) Math.ceil(lit / 9f));
        for (int i = 0; i < n; i++) {
            float a0 = 135f + i * 9f;
            float a1 = Math.min(135f + lit, 135f + (i + 1) * 9f + 0.5f);
            final int col = acc != 0 ? acc : Gfx.stops(Math.min(1f, (a0 - 135f + 4.5f) / 270f), SEG_POS, SEG_COL);
            Gfx.arc(g, cx, cy, r, 7f, a0, a1, (x, y) -> col, 1f);
        }
        if (lit > 1f) {
            final int tip = acc != 0 ? acc : Gfx.stops(shownFrac, SEG_POS, SEG_COL);
            Gfx.arc(g, cx, cy, r, 14f, 135f, 135f + lit, (x, y) -> tip, 0.12f);
        }

        // indicatori reali deasupra cifrei: motor, rezerva, boost, stare
        float dy = cy - 30f;
        Gfx.disc(g, cx - 13.5f, dy, 2.6f, sEngine ? GREEN : 0xFF2C3540);
        Gfx.disc(g, cx - 4.5f, dy, 2.6f, low ? (blink ? 0xFFFF6A5E : 0xFF8A2F2A) : 0xFF2C3540);
        Gfx.disc(g, cx + 4.5f, dy, 2.6f, sBoost ? CYAN : 0xFF2C3540);
        Gfx.disc(g, cx + 13.5f, dy, 2.6f, sHp < 0.3f ? 0xFFFFB84D : 0xFF2C3540);

        // viteza + unitate + treapta
        Gfx.text(g, String.valueOf(Math.round(shownSpeed)), cx, cy + 12f, 36f, 0xFFF4F7FB, 1, true, -1f);
        Gfx.text(g, sBoost ? "BOOST" : "KM/H", cx, cy + 25f, 7.5f, sBoost ? CYAN : MUTED, 1, false, 2.2f);
        Gfx.rrect(g, cx - 11f, cy + 33f, 22f, 12f, 4f, 0xB0101A28);
        Gfx.text(g, sGear, cx, cy + 42.5f, 9.5f, acc != 0 ? acc : CYAN, 1, true, 0f);

        // combustibil: cadran mic in stanga
        if (fuel) {
            float fx = 33f;
            float fy = 98f;
            float fr = 22f;
            int fc = low ? (blink ? 0xFFFF6A5E : Theme.BAD) : (acc != 0 ? acc : CYAN);
            Gfx.disc(g, fx, fy, fr + 5f, 0x55000000);
            Gfx.arc(g, fx, fy, fr, 4.5f, 135f, 405f, (x, y) -> OFF, 1f);
            if (sFrac > 0.01f) Gfx.arc(g, fx, fy, fr, 4.5f, 135f, 135f + 270f * Math.min(1f, sFrac), (x, y) -> fc, 1f);
            Gfx.text(g, Math.round(Math.min(1f, sFrac) * 100f) + "%", fx, fy + 3.5f, 10f, blink ? 0xFFFF6A5E : 0xFFF4F7FB, 1, true, 0f);
            Gfx.text(g, "COMB.", fx, fy + 17f, 5.8f, MUTED, 1, false, 1.2f);
        }

        // kilometraj total + trip, discret, sub vitezometru
        String a = sHas ? kmStr() : "- km";
        String b = sHas ? "TRIP " + tripStr : "";
        float wa = Gfx.width(a, 8.5f, false);
        float wb = b.isEmpty() ? 0f : Gfx.width(b, 7.5f, false) + 9f;
        float x0 = cx - (wa + wb) / 2f;
        Gfx.text(g, a, x0, 143f, 8.5f, 0xFFDCE4F0, 0);
        if (!b.isEmpty()) {
            Gfx.disc(g, x0 + wa + 4.5f, 140.8f, 1f, 0xFF4A5666);
            Gfx.text(g, b, x0 + wa + 9f, 143f, 7.5f, MUTED, 0);
        }

        // eticheta (tasta "Informatii vehicul"): nume + numar + proprietar, mica, deasupra bordului
        infoAnim += ((showInfo ? 1f : 0f) - infoAnim) * 0.2f;
        if (infoAnim > 0.02f) {
            float ia = infoAnim;
            String nm = sName.isEmpty() ? "Vehicul" : sName;
            String pl = !sPlate.isEmpty() ? sPlate : metaPlate;
            float nw = Gfx.width(nm, 11f, false);
            float pw = pl.isEmpty() ? 0f : Gfx.width(pl, 9f, false) + 0.5f * (pl.length() - 1) + 10f;
            float tw = 20f + nw + (pl.isEmpty() ? 0f : 8f + pw);
            float tx = 190f - tw;
            float ty = -4f;
            String own = metaOwner.isEmpty() ? "" : "Proprietar: " + metaOwner;
            float th = own.isEmpty() ? 22f : 34f;
            ty -= th;
            Gfx.rrect(g, tx, ty, tw, th, 8f, (Math.round(ia * 0xD0) << 24) | 0x0E1118);
            Gfx.text(g, nm, tx + 10f, ty + 15f, 11f, (Math.round(ia * 255) << 24) | 0xEDEDEA, 0);
            if (!pl.isEmpty()) {
                float qx = tx + 10f + nw + 8f;
                Gfx.rrect(g, qx, ty + 4.5f, pw, 13f, 3f, (Math.round(ia * 255) << 24) | 0xF4F1EA);
                Gfx.text(g, pl, qx + 5f, ty + 14f, 9f, (Math.round(ia * 255) << 24) | 0x14161A, 0, false, 0.5f);
            }
            if (!own.isEmpty()) Gfx.text(g, own.length() > 30 ? own.substring(0, 29) + "..." : own, tx + 10f, ty + 28f, 7.5f, (Math.round(ia * 255) << 24) | 0x9AA5B5, 0);
        }
        g.pose().popPose();
        ro.solarstudios.flowcity.client.ui.HudLayout.end(g);

        if (fuel && sFrac <= 0f && appear > 0.9f) Gfx.text(g, "Rezervorul e gol", w / 2f, h - 64, 10, Theme.BAD, 1);
    }

    private static String kmStr() {
        long t = 0;
        for (char ch : odoDigits.toCharArray()) t = t * 10 + (ch - '0');
        long kmInt = t / 10;
        String s = String.format(java.util.Locale.ROOT, "%,d", kmInt).replace(',', '.');
        return s + " km";
    }
}
