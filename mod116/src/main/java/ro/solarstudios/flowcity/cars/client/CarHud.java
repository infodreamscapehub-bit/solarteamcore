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

    /** Disc plin (cerc) desenat din linii orizontale. */
    private static void disc(GuiGraphics g, int cx, int cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.round(Math.sqrt((double) r * r - dy * dy));
            Ui.rect(g, cx - dx, cy + dy, dx * 2 + 1, 1, col);
        }
    }

    /** Vitezometru rotund: arc de 270 de grade, viteza mare la mijloc, benzina si starea jos. */
    private static void draw(GuiGraphics g, Minecraft mc, Entity v) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int r = 46;
        int cx = w - r - 22;
        int cy = h - r - 18;
        long now = System.currentTimeMillis();

        disc(g, cx + 1, cy + 2, r + 9, 0x50000000);
        disc(g, cx, cy, r + 9, 0xE816181D);
        disc(g, cx, cy, r - 9, 0xFF1A1C21);

        double max = num(v, "getActualMaxSpeed", num(v, "getMaxSpeed", 0));
        double speed = Math.abs(num(v, "getSpeed", 0));
        float frac = max <= 0 ? 0 : (float) Math.min(1.0, speed / max);
        int n = 48;
        int lit = Math.round(frac * n);
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(135 + 270.0 * i / (n - 1));
            int px = (int) Math.round(cx + Math.cos(a) * r);
            int py = (int) Math.round(cy + Math.sin(a) * r);
            float t = i / (float) (n - 1);
            int col = t > 0.82f ? Theme.BAD : (t > 0.62f ? Theme.WARN : Theme.ACCENT);
            Ui.rect(g, px - 1, py - 1, 3, 3, i < lit ? col : 0xFF2C3037);
            if (i % 8 == 0) {
                int ix = (int) Math.round(cx + Math.cos(a) * (r - 6));
                int iy = (int) Math.round(cy + Math.sin(a) * (r - 6));
                Ui.rect(g, ix, iy, 1, 1, Theme.FAINT);
            }
        }

        // motor + boost
        boolean engine = bool(v, "isEnginePowered", true);
        Ui.round(g, cx - 2, cy - 30, 5, 5, engine ? Theme.OK : 0xFF4A4D55);

        float kmh = (float) Math.abs(num(v, "getKilometersPreHour", 0));
        shownSpeed += (kmh - shownSpeed) * 0.25f;
        String sp = String.valueOf(Math.round(shownSpeed));
        g.pose().pushPose();
        g.pose().translate(cx, cy - 18, 0);
        g.pose().scale(2.6f, 2.6f, 1f);
        Ui.textCenter(g, sp, 0, 0, Theme.TEXT);
        g.pose().popPose();
        boolean boost = bool(v, "isBoosting", false);
        Ui.textCenter(g, boost ? "BOOST" : "km/h", cx, cy + 6, boost ? Theme.ACCENT : Theme.FAINT);

        // jos, in deschiderea arcului: starea si benzina
        double maxHp = num(v, "getMaxHealth", 0);
        float hp = maxHp <= 0 ? 1f : (float) (num(v, "getHealth", maxHp) / maxHp);
        boolean fuel = bool(v, "requiresFuel", false);
        int bw = 34;
        int by = cy + 20;
        if (fuel) {
            double cap = num(v, "getFuelCapacity", 0);
            float f = cap <= 0 ? 0 : (float) (num(v, "getCurrentFuel", 0) / cap);
            boolean low = f < 0.12f;
            boolean blink = low && (now / 400) % 2 == 0;
            Ui.text(g, "B", cx - bw / 2 - 8, by - 2, blink ? Theme.BAD : Theme.MUTED);
            Ui.rect(g, cx - bw / 2, by, bw, 3, 0xFF2C3037);
            Ui.rect(g, cx - bw / 2, by, Math.round(bw * Math.max(0f, Math.min(1f, f))), 3, low ? Theme.BAD : Theme.ACCENT);
            Ui.text(g, Math.round(f * 100) + "", cx + bw / 2 + 3, by - 2, blink ? Theme.BAD : Theme.MUTED);
            if (f <= 0f) Ui.textCenter(g, "Rezervorul e gol", w / 2, h - 70, Theme.BAD);
            by += 8;
        }
        Ui.text(g, "S", cx - bw / 2 - 8, by - 2, hp < 0.3f ? Theme.BAD : Theme.MUTED);
        Ui.rect(g, cx - bw / 2, by, bw, 3, 0xFF2C3037);
        Ui.rect(g, cx - bw / 2, by, Math.round(bw * Math.max(0f, Math.min(1f, hp))), 3, hp < 0.3f ? Theme.BAD : Theme.OK);
        Ui.text(g, Math.round(hp * 100) + "", cx + bw / 2 + 3, by - 2, hp < 0.3f ? Theme.BAD : Theme.MUTED);

        // numele vehiculului deasupra
        String name = Ui.clip(v.getDisplayName().getString(), 110);
        int nw = Ui.width(name) + 12;
        Ui.round(g, cx - nw / 2, cy - r - 24, nw, 13, 0xD816181D);
        Ui.textCenter(g, name, cx, cy - r - 21, Theme.MUTED);
    }
}
