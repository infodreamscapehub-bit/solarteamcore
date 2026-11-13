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

    private static final float[] STOP_POS = {0f, 0.55f, 0.85f, 1f};
    private static final int[] STOP_COL = {0xFFF6C15A, 0xFFEE9A3A, 0xFFE86A3C, 0xFFE2453F};
    private static final int GREEN = 0xFF6DBE6F;

    /**
     * Bordul FlowCity: cadran rotund cu arc de viteza neted si stralucitor, gradatii dupa viteza maxima a masinii,
     * viteza mare cu fontul Inter, motor/boost, benzina si stare, nume + numar deasupra.
     * Totul se deseneaza in coordonatele machetei (cadran de 236) si se scaleaza dupa ecran.
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
        double speed = Math.abs(num(v, "getSpeed", 0));
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

        // asezare: cadranul de 236 scalat la ~24% din inaltimea ecranului, dreapta jos
        float d = Math.max(90f, Math.min(128f, h * 0.24f));
        float k = d / 236f;
        if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(g, "speedo", w - 17 - d, h - 13 - d, d, d)) return;
        g.pose().pushPose();
        g.pose().translate(w - 17 - d, h - 13 - d, 0);
        g.pose().scale(k, k, 1f);

        float cx = 118;
        float cy = 118;
        float r = 94;

        // corp: umbra, fond cu gradient radial, contur fin
        Gfx.ring(g, cx, cy + 6, 104, 128, 0x80000000, 0x00000000);
        Gfx.disc(g, cx, cy, 112, 0xFF22252C, 0xFF0D0E11, 0, -19);
        Gfx.ring(g, cx, cy, 111, 112.5f, 0x14FFFFFF, 0x10FFFFFF);

        // gradatii + cifre
        int minor = dialMax / 18;
        for (int i = 0; i <= 18; i++) {
            int val = i * minor;
            float t = val / (float) dialMax;
            double a = Math.toRadians(135 + 270.0 * t);
            float c = (float) Math.cos(a);
            float sn = (float) Math.sin(a);
            boolean maj = i % 3 == 0;
            boolean lit = t <= shownFrac + 0.001f;
            float r0 = r - 10;
            float r1 = r - (maj ? 17 : 13);
            Gfx.line(g, cx + c * r0, cy + sn * r0, cx + c * r1, cy + sn * r1, maj ? 2f : 1.2f, lit ? 0xFFC9C7C0 : 0xFF4A4E57);
            if (maj && i < 18) {
                float tr = r - 28;
                Gfx.text(g, String.valueOf(val), cx + c * tr, cy + sn * tr + 3.5f, 10, lit ? 0xFFD4D2CC : 0xFF5C606A, 1);
            }
        }

        // arcul de viteza: sina + stralucire + umplere cu gradient
        Gfx.arc(g, cx, cy, r, 10, 135, 405, (x, y) -> 0xFF262A31, 1f);
        float end = 135 + 270 * shownFrac;
        final int acc = ro.solarstudios.flowcity.client.ui.HudLayout.accent("speedo", 0);
        Gfx.Paint paint = acc == 0 ? (x, y) -> Gfx.stops((x - (cx - r)) / (2 * r), STOP_POS, STOP_COL) : (x, y) -> acc;
        if (shownFrac > 0.004f) {
            Gfx.arc(g, cx, cy, r, 24, 135, end, paint, 0.10f);
            Gfx.arc(g, cx, cy, r, 17, 135, end, paint, 0.18f);
            Gfx.arc(g, cx, cy, r, 10, 135, end, paint, 1f);
        }

        // motor
        int dot = engine ? GREEN : 0xFF4A4D55;
        if (engine) Gfx.disc(g, cx, 64, 7, 0x2E6DBE6F);
        Gfx.disc(g, cx, 64, 3.5f, dot);

        // viteza
        Gfx.text(g, String.valueOf(Math.round(shownSpeed)), cx, 122, 58, 0xFFF4F1EA, 1, true, -1f);
        Gfx.text(g, boost ? "BOOST" : "KM/H", cx, 140, 10.5f, boost ? Theme.ACCENT : 0xFF8E8C86, 1, false, 2.4f);

        // benzina + stare
        float by = 138;
        if (fuel) {
            boolean low = f < 0.12f;
            boolean blink = low && (now / 400) % 2 == 0;
            int col = low ? Theme.BAD : Theme.ACCENT;
            int icon = blink ? 0xFFFF6A5E : col;
            Gfx.rrect(g, 84, by + 11, 6, 9, 1.2f, icon);
            Gfx.rrect(g, 85.3f, by + 12.3f, 3.4f, 2.6f, 0.6f, 0xFF15171B);
            Gfx.line(g, 90, by + 13, 92.5f, by + 15, 1.4f, icon);
            Gfx.line(g, 92.5f, by + 15, 92.5f, by + 19, 1.4f, icon);
            Gfx.bar(g, 98, by + 14, 36, 4, f, 0xFF262A31, col);
            Gfx.text(g, Math.round(f * 100) + "%", 139, by + 19, 10, blink ? 0xFFFF6A5E : 0xFFA19F98, 0);
            by += 13;
        } else {
            by += 6;
        }
        int hc = hp < 0.3f ? Theme.BAD : GREEN;
        float[] zz = {83, by + 19, 86, by + 16, 89, by + 19, 92, by + 16, 95, by + 19};
        for (int i = 0; i + 3 < zz.length; i += 2) Gfx.line(g, zz[i], zz[i + 1], zz[i + 2], zz[i + 3], 1.6f, hc);
        Gfx.bar(g, 98, by + 14, 36, 4, hp, 0xFF262A31, hc);
        Gfx.text(g, Math.round(hp * 100) + "%", 139, by + 19, 10, hp < 0.3f ? Theme.BAD : 0xFFA19F98, 0);

        // nume + numar deasupra cadranului
        String name = v.getDisplayName().getString();
        if (name.length() > 24) name = name.substring(0, 23) + "...";
        float nw = Gfx.width(name, 13, false);
        float pw = pl.isEmpty() ? 0 : Gfx.width(pl, 11, false) + 0.66f * (pl.length() - 1) + 12;
        float tw = 24 + nw + (pl.isEmpty() ? 0 : 8 + pw);
        float tx = cx - tw / 2;
        float ty = -37;
        Gfx.rrect(g, tx - 1, ty - 1, tw + 2, 29, 10, 0x12FFFFFF);
        Gfx.rrect(g, tx, ty, tw, 27, 9, 0xD10E0F12);
        Gfx.text(g, name, tx + 12, ty + 18.5f, 13, 0xFFEDEDEA, 0);
        if (!pl.isEmpty()) {
            float px = tx + 12 + nw + 8;
            Gfx.rrect(g, px, ty + 5.5f, pw, 16, 4, 0xFFF4F1EA);
            Gfx.text(g, pl, px + 6, ty + 17.5f, 11, 0xFF14161A, 0, false, 0.66f);
        }
        g.pose().popPose();
        ro.solarstudios.flowcity.client.ui.HudLayout.end(g);

        if (fuel && f <= 0f) Gfx.text(g, "Rezervorul e gol", w / 2f, h - 64, 10, Theme.BAD, 1);
    }
}
