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

    private static final int WHITE = 0xFFF2F1EC;
    private static final int MUTED = 0xFF8C8A84;
    private static final int MINT = 0xFF52E3A4;
    private static final int AMBER = 0xFFF2B544;
    private static final int RED = 0xFFE5534B;
    /** Arcul vitezometrului: ~285 grade, gol jos-dreapta (0 = dreapta, sensul acelor de ceasornic). */
    private static final float A0 = 115f;
    private static final float SWEEP = 285f;

    private static long lastFrame = System.currentTimeMillis();
    private static float appear;
    private static boolean snap;
    private static int dialMax = 60;
    private static boolean sEngine;
    private static boolean sBoost;
    private static boolean sFuel;
    private static float sFrac = 1f;
    private static float sHp = 1f;
    private static boolean sHpKnown;
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
        sHpKnown = maxHp > 0;
        sHp = maxHp <= 0 ? 1f : (float) Math.max(0.0, Math.min(1.0, num(v, "getHealth", maxHp) / maxHp));
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
     * Bord in stil „NEO”: arc deschis (~285 grade) cu viteza in alb, scala de viteza cu repere si cifre, viteza mare in centru,
     * treapta si kilometrajul text dedesubt, combustibil in stanga-jos, 3 indicatori reali sus-dreapta si un arc mic de stare a
     * vehiculului (doar daca modul da valoarea reala). Coordonatele sunt ale unei machete de 190 x 150, scalabila din editorul de HUD.
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
        g.pose().translate(d[0], d[1] + (1f - ap) * 10f, 0);
        g.pose().scale(k, k, 1f);
        final int acc = ro.solarstudios.flowcity.client.ui.HudLayout.accent("speedo", 0);
        final int main = acc != 0 ? acc : WHITE;

        boolean fuel = sFuel;
        boolean low = fuel && sFrac < 0.15f;
        boolean blink = low && (now / 400) % 2 == 0;
        float cx = 96f;
        float cy = 74f;

        // umbra foarte discreta, doar ca sa se citeasca pe cer deschis
        Gfx.disc(g, cx, cy, 67f, 0x26000000);

        // inelul exterior subtire + scala (repere si cifre)
        Gfx.arc(g, cx, cy, 65f, 1.3f, A0, A0 + SWEEP, (x, y) -> 0x44FFFFFF, 1f);
        int step = dialMax > 300 ? 60 : 30;
        int sub = step / 3;
        for (int val = 0; val <= dialMax; val += sub) {
            boolean major = val % step == 0;
            double a = Math.toRadians(A0 + SWEEP * val / dialMax);
            float c = (float) Math.cos(a);
            float sn = (float) Math.sin(a);
            boolean on = val <= shownSpeed + 0.5f;
            float r0 = major ? 59f : 60.2f;
            float r1 = major ? 62.8f : 62f;
            int col = on ? (major ? 0xF0F2F1EC : 0x99F2F1EC) : (major ? 0x66F2F1EC : 0x33F2F1EC);
            Gfx.line(g, cx + c * r0, cy + sn * r0, cx + c * r1, cy + sn * r1, major ? 1.25f : 0.75f, col);
            if (major) Gfx.text(g, String.valueOf(val), cx + c * 54f, cy + sn * 54f + 1.9f, 5.2f, on ? 0xE6F2F1EC : 0x66F2F1EC, 1);
        }

        // arcul vitezei: pista slaba + parte alba (animata lin)
        Gfx.arc(g, cx, cy, 45.5f, 5f, A0, A0 + SWEEP, (x, y) -> 0x1FFFFFFF, 1f);
        float lit = SWEEP * Math.max(0f, Math.min(1f, shownFrac));
        if (lit > 0.6f) Gfx.arc(g, cx, cy, 45.5f, 5f, A0, A0 + lit, (x, y) -> main, 1f);

        // starea vehiculului (arc exterior stanga) - doar cu valoare reala de la mod
        if (sHpKnown) {
            Gfx.arc(g, cx, cy, 69.5f, 2.4f, 195f, 285f, (x, y) -> 0x22FFFFFF, 1f);
            int hc = sHp > 0.6f ? MINT : (sHp > 0.3f ? AMBER : RED);
            if (sHp > 0.02f) Gfx.arc(g, cx, cy, 69.5f, 2.4f, 195f, 195f + 90f * sHp, (x, y) -> hc, 1f);
        }

        // viteza: cifre pe celule fixe (tabular), ca numarul sa nu salte
        int spd = Math.round(shownSpeed);
        String sp = String.valueOf(Math.min(spd, 999));
        float fs = 34f;
        float cell = Gfx.width("0", fs, true) + 0.8f;
        float x0 = cx - cell * sp.length() / 2f;
        for (int i = 0; i < sp.length(); i++) Gfx.text(g, String.valueOf(sp.charAt(i)), x0 + cell * (i + 0.5f), cy + 12f, fs, 0xFFF7F6F2, 1, true, 0f);
        Gfx.text(g, sBoost ? "BOOST" : "KM/H", cx, cy + 23f, 6.2f, sBoost ? main : MUTED, 1, false, 1.8f);
        Gfx.text(g, sGear, cx, cy + 35f, 9f, shownSpeed < 1.5f ? MUTED : main, 1, true, 0f);

        // kilometraj (text curat) + trip A/B
        advanceOdo(now);
        if (sHas) {
            Gfx.text(g, fmtKm(dispOdo, true) + " km", cx, cy + 47f, 7.8f, 0xFFE9E8E3, 1);
            Gfx.text(g, "TRIP " + (tripSel == 0 ? "A" : "B") + "   " + fmtKm(tripSel == 0 ? dispA : dispB, false) + " km", cx, cy + 56f, 6.2f, MUTED, 1, false, 0.4f);
            if (resetHeld > 0) {
                float prog = Math.min(1f, resetHeld / 1000f);
                Gfx.rrect(g, cx - 26f, cy + 59f, 52f, 1.8f, 0.9f, 0x33FFFFFF);
                Gfx.rrect(g, cx - 26f, cy + 59f, Math.max(1f, 52f * prog), 1.8f, 0.9f, RED);
            }
        } else {
            Gfx.text(g, "- km", cx, cy + 47f, 7.8f, MUTED, 1);
        }

        // indicatori reali sus-dreapta: motor, stare, boost (stinsi cand nu sunt active)
        float iy = 13f;
        iconEngine(g, 141f, iy, sEngine ? 0xFFF2F1EC : 0x44F2F1EC);
        Gfx.text(g, "MOTOR", 141f, iy + 11f, 4.4f, MUTED, 1, false, 0.8f);
        if (sHpKnown) {
            iconWarn(g, 157f, iy, sHp < 0.3f ? RED : (sHp < 0.6f ? AMBER : 0x44F2F1EC));
            Gfx.text(g, "STARE", 157f, iy + 11f, 4.4f, MUTED, 1, false, 0.8f);
        }
        iconBolt(g, 173f, iy, sBoost ? main : 0x44F2F1EC);
        Gfx.text(g, "BOOST", 173f, iy + 11f, 4.4f, MUTED, 1, false, 0.8f);

        // combustibil: cerc mic stanga-jos
        if (fuel) {
            float fx = 21f;
            float fy = 114f;
            int fc = low ? (blink ? 0xFFFF7468 : RED) : main;
            Gfx.arc(g, fx, fy, 11f, 2.4f, 135f, 405f, (x, y) -> 0x26FFFFFF, 1f);
            if (sFrac > 0.01f) Gfx.arc(g, fx, fy, 11f, 2.4f, 135f, 135f + 270f * Math.min(1f, sFrac), (x, y) -> fc, 1f);
            iconPump(g, fx, fy - 0.5f, low ? fc : 0xFFF2F1EC);
            Gfx.text(g, Math.round(Math.min(1f, sFrac) * 100f) + "%", fx, fy + 20f, 5.6f, low ? fc : MUTED, 1, false, 0.4f);
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
                Gfx.text(g, fmtKm(dispOdo, true) + " km", tx + 34f, oy + 9f, 7.8f, 0xFFEDEDEA, 0);
                Gfx.alpha = prevA;
            }
        }
        g.pose().popPose();
        ro.solarstudios.flowcity.client.ui.HudLayout.end(g);

        if (fuel && sFrac <= 0f && appear > 0.9f) Gfx.text(g, "Rezervorul e gol", w / 2f, h - 64, 10, Theme.BAD, 1);
    }

    /** Metri -> "123.456,7" (grupare cu punct, o zecimala cu virgula) sau, fara grupare, "12,3". */
    private static String fmtKm(double meters, boolean group) {
        long tenths = Math.max(0L, Math.round(Math.min(meters, 999_999_999.0) / 100.0));
        long ip = tenths / 10L;
        long dp = tenths % 10L;
        String s = Long.toString(ip);
        if (group && s.length() > 3) {
            StringBuilder b = new StringBuilder();
            int first = s.length() % 3;
            if (first > 0) b.append(s, 0, first);
            for (int i = first; i < s.length(); i += 3) {
                if (b.length() > 0) b.append('.');
                b.append(s, i, i + 3);
            }
            s = b.toString();
        }
        return s + "," + dp;
    }

    private static void iconEngine(GuiGraphics g, float x, float y, int c) {
        Gfx.rrect(g, x - 3.6f, y - 2.2f, 7.2f, 5.2f, 1f, c);
        Gfx.rrect(g, x - 1.6f, y - 4.4f, 3.2f, 2.2f, 0.6f, c);
        Gfx.rrect(g, x - 5.8f, y - 0.9f, 2.2f, 2.6f, 0.5f, c);
        Gfx.rrect(g, x + 3.6f, y - 1.8f, 2.2f, 3.8f, 0.5f, c);
    }

    private static void iconWarn(GuiGraphics g, float x, float y, int c) {
        Gfx.poly(g, new float[] {x, y - 4.6f, x + 5f, y + 3.8f, x - 5f, y + 3.8f}, c);
        Gfx.line(g, x, y - 1.4f, x, y + 1f, 1.1f, 0xFF101214);
        Gfx.disc(g, x, y + 2.4f, 0.7f, 0xFF101214);
    }

    private static void iconBolt(GuiGraphics g, float x, float y, int c) {
        Gfx.poly(g, new float[] {x + 1.6f, y - 5f, x - 3f, y + 0.8f, x - 0.1f, y + 0.8f}, c);
        Gfx.poly(g, new float[] {x - 1.6f, y + 5f, x + 3f, y - 0.8f, x + 0.1f, y - 0.8f}, c);
    }

    private static void iconPump(GuiGraphics g, float x, float y, int c) {
        Gfx.rrect(g, x - 4f, y - 5f, 6.4f, 9.4f, 1.2f, c);
        Gfx.rrect(g, x - 2.8f, y - 3.7f, 4f, 3f, 0.6f, 0xCC0B0D10);
        Gfx.rrect(g, x - 5f, y + 3.9f, 8.4f, 1.4f, 0.5f, c);
        Gfx.line(g, x + 2.4f, y - 2.6f, x + 4.7f, y - 1.2f, 1f, c);
        Gfx.line(g, x + 4.7f, y - 1.2f, x + 4.7f, y + 2.8f, 1f, c);
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
}
