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
    /** Valorile primite de la server (metri) si cele afisate (interpolate local intre doua trimiteri). */
    private static double srvOdo;
    private static double srvA;
    private static double srvB;
    private static double dispOdo;
    private static double dispA;
    private static double dispB;
    /** Trip-ul afisat: 0 = A, 1 = B (doar pe client). */
    public static int tripSel;
    /** Cat timp e tinuta apasata tasta de resetare (ms). */
    private static long resetHeld;
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

    /** Kilometrajul trimis de server: metri totali + trip A + trip B pentru vehiculul cu id-ul dat. */
    public static void setOdo(int entityId, double odoM, double aM, double bM) {
        boolean fresh = odoFor != entityId;
        if (fresh) setMeta("", "", "", "", "");
        odoFor = entityId;
        boolean resetA = aM < srvA - 1;
        boolean resetB = bM < srvB - 1;
        srvOdo = Math.max(0, odoM);
        srvA = Math.max(0, aM);
        srvB = Math.max(0, bM);
        if (fresh) {
            dispOdo = srvOdo;
            dispA = srvA;
            dispB = srvB;
        }
        if (resetA) dispA = srvA;
        if (resetB) dispB = srvB;
    }

    /** Tasta de resetare tinuta apasata: la ~1 s trimite cererea (validata pe server). */
    public static void tickKeys(Minecraft mc, boolean resetDown, boolean vehicle) {
        if (!vehicle || !sHas) {
            resetHeld = 0;
            return;
        }
        if (resetDown) {
            resetHeld += 50;
            if (resetHeld >= 1000) {
                CarsNet.send("trip", tripSel, 0);
                resetHeld = -600; // pauza scurta ca sa nu repete imediat
            }
        } else {
            resetHeld = 0;
        }
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

        // odometru mecanic (tambur) + trip A/B, sub vitezometru
        advanceOdo(now);
        if (sHas) {
            drawOdometer(g, cx, 123f, 8.6f, 12f, dispOdo, now);
            drawTrip(g, cx, 138f);
        } else {
            Gfx.text(g, "- km", cx, 133f, 8.5f, MUTED, 1);
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
            float th = (own.isEmpty() ? 22f : 34f) + (sHas ? 16f : 0f);
            ty -= th;
            Gfx.rrect(g, tx, ty, tw, th, 8f, (Math.round(ia * 0xD0) << 24) | 0x0E1118);
            Gfx.text(g, nm, tx + 10f, ty + 15f, 11f, (Math.round(ia * 255) << 24) | 0xEDEDEA, 0);
            if (!pl.isEmpty()) {
                float qx = tx + 10f + nw + 8f;
                Gfx.rrect(g, qx, ty + 4.5f, pw, 13f, 3f, (Math.round(ia * 255) << 24) | 0xF4F1EA);
                Gfx.text(g, pl, qx + 5f, ty + 14f, 9f, (Math.round(ia * 255) << 24) | 0x14161A, 0, false, 0.5f);
            }
            if (!own.isEmpty()) Gfx.text(g, own.length() > 30 ? own.substring(0, 29) + "..." : own, tx + 10f, ty + 28f, 7.5f, (Math.round(ia * 255) << 24) | 0x9AA5B5, 0);
            if (sHas) {
                float oy = ty + th - 16f;
                float prevA = Gfx.alpha;
                Gfx.alpha *= ia;
                Gfx.text(g, "ODO", tx + 10f, oy + 9f, 6.5f, MUTED, 0, false, 1.4f);
                drawOdometer(g, tx + 10f + 18f + 36f, oy, 6.8f, 10f, dispOdo, now);
                Gfx.alpha = prevA;
            }
        }
        g.pose().popPose();
        ro.solarstudios.flowcity.client.ui.HudLayout.end(g);

        if (fuel && sFrac <= 0f && appear > 0.9f) Gfx.text(g, "Rezervorul e gol", w / 2f, h - 64, 10, Theme.BAD, 1);
    }

    private static long lastAdv = System.currentTimeMillis();

    /** Intre doua trimiteri ale serverului, valorile cresc local dupa viteza reala (cel mult 55 m peste ultima valoare primita). */
    private static void advanceOdo(long now) {
        double dt = Math.min(0.2, Math.max(0.0, (now - lastAdv) / 1000.0));
        lastAdv = now;
        double step = Math.max(0f, shownSpeed) / 3.6 * dt;
        dispOdo = Math.max(srvOdo, Math.min(dispOdo + step, srvOdo + 55.0));
        dispA = Math.max(srvA, Math.min(dispA + step, srvA + 55.0));
        dispB = Math.max(srvB, Math.min(dispB + step, srvB + 55.0));
    }

    private static final int DRUM_BG = 0xFF0A0E15;
    private static final int DRUM_EDGE = 0xFF2B3546;
    private static final int DRUM_TXT = 0xFFF1F5FA;
    private static final int DEC_BG = 0xFFE9E4D6;
    private static final int DEC_TXT = 0xFF14171C;

    /** Decupare dreptunghiulara in coordonatele locale ale matricei curente (tine cont de mutarea/scalarea din editorul HUD). */
    private static void clipBegin(GuiGraphics g, float x, float y, float w, float h) {
        net.minecraft.util.math.vector.Matrix4f m = g.pose().last().pose();
        net.minecraft.util.math.vector.Vector4f a = new net.minecraft.util.math.vector.Vector4f(x, y, 0f, 1f);
        net.minecraft.util.math.vector.Vector4f b = new net.minecraft.util.math.vector.Vector4f(x + w, y + h, 0f, 1f);
        a.transform(m);
        b.transform(m);
        int x0 = (int) Math.floor(Math.min(a.x(), b.x()));
        int y0 = (int) Math.floor(Math.min(a.y(), b.y()));
        int x1 = (int) Math.ceil(Math.max(a.x(), b.x()));
        int y1 = (int) Math.ceil(Math.max(a.y(), b.y()));
        Ui.scissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    /** O cifra de tambur: se rostogoleste vertical cand cifra de mai jos trece de la 9 la 0 (ultima cifra se misca continuu). */
    private static void drum(GuiGraphics g, float x, float y, float cw, float ch, double pos, int i, boolean dec) {
        double t = pos / Math.pow(10.0, i);
        double base = Math.floor(t);
        double f = t - base;
        int digit = (int) (((long) base) % 10L);
        float r = (float) (i == 0 ? f : Math.max(0.0, (f - 0.9) / 0.1));
        r = r * r * (3f - 2f * r);
        Gfx.rrect(g, x - 0.8f, y - 0.8f, cw + 1.6f, ch + 1.6f, 2.6f, DRUM_EDGE);
        Gfx.rrect(g, x, y, cw, ch, 2f, dec ? DEC_BG : DRUM_BG);
        clipBegin(g, x, y, cw, ch);
        float size = ch * 0.78f;
        int col = dec ? DEC_TXT : DRUM_TXT;
        Gfx.text(g, String.valueOf(digit), x + cw / 2f, y + ch * 0.80f - r * ch, size, col, 1);
        if (r > 0.001f) Gfx.text(g, String.valueOf((digit + 1) % 10), x + cw / 2f, y + ch * 0.80f + (1f - r) * ch, size, col, 1);
        // umbra de cilindru sus/jos
        Gfx.rrect(g, x, y, cw, ch * 0.30f, 0f, dec ? 0x55000000 : 0xAA000000, 0x00000000);
        Gfx.rrect(g, x, y + ch * 0.70f, cw, ch * 0.30f, 0f, 0x00000000, dec ? 0x55000000 : 0xAA000000);
        Ui.endScissor();
    }

    /** Odometru 000.000,0 (6 cifre + o zecimala pe fundal deschis), centrat la cx. */
    private static void drawOdometer(GuiGraphics g, float cx, float y, float cw, float ch, double meters, long now) {
        double pos = Math.min(9_999_999.0, Math.max(0.0, meters / 100.0)); // zecimi de km
        float gap = 1.3f;
        float sep = cw * 0.45f;
        float total = 7 * cw + 6 * gap + 2 * sep;
        float x = cx - total / 2f;
        for (int i = 6; i >= 0; i--) {
            drum(g, x, y, cw, ch, pos, i, i == 0);
            x += cw + gap;
            if (i == 4 || i == 1) {
                Gfx.text(g, i == 4 ? "." : ",", x - gap / 2f + sep / 2f - 0.3f, y + ch * 0.92f, ch * 0.7f, 0xFFB8C2D1, 1);
                x += sep;
            }
        }
    }

    /** Trip curent (A/B) langa odometru: selector A|B + 4 cifre si o zecimala; la resetare apare o bara de progres. */
    private static void drawTrip(GuiGraphics g, float cx, float y) {
        double meters = tripSel == 0 ? dispA : dispB;
        double pos = Math.min(99_999.0, Math.max(0.0, meters / 100.0));
        float cw = 6.2f;
        float ch = 9f;
        float gap = 1.1f;
        float sep = 3f;
        float pill = 7.5f;
        float drumW = 5 * cw + 4 * gap + sep;
        float total = 2 * pill + 1.5f + 5f + drumW + 4f + 8f;
        float x = cx - total / 2f;
        for (int k = 0; k < 2; k++) {
            boolean on = tripSel == k;
            Gfx.rrect(g, x, y, pill, ch, 2.4f, on ? 0xFF22D3EE : 0xFF1B2430);
            Gfx.text(g, k == 0 ? "A" : "B", x + pill / 2f, y + ch * 0.80f, 7f, on ? 0xFF06222A : MUTED, 1);
            x += pill + 1.5f;
        }
        x += 5f;
        for (int i = 4; i >= 0; i--) {
            drum(g, x, y, cw, ch, pos, i, i == 0);
            x += cw + gap;
            if (i == 1) {
                Gfx.text(g, ",", x - gap / 2f + sep / 2f - 0.3f, y + ch * 0.95f, 6.5f, 0xFFB8C2D1, 1);
                x += sep;
            }
        }
        Gfx.text(g, "km", x + 3f, y + ch * 0.80f, 6.5f, MUTED, 0);
        if (resetHeld > 0) {
            float prog = Math.min(1f, resetHeld / 1000f);
            Gfx.rrect(g, cx - total / 2f, y + ch + 2f, total, 2f, 1f, 0xFF1B2430);
            Gfx.rrect(g, cx - total / 2f, y + ch + 2f, Math.max(1f, total * prog), 2f, 1f, 0xFFFF6A5E);
        }
    }
}
