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

    private static void draw(GuiGraphics g, Minecraft mc, Entity v) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int pw = 150;
        boolean fuel = bool(v, "requiresFuel", false);
        int ph = fuel ? 74 : 58;
        int x = w - pw - 12;
        int y = h - ph - 12;
        Ui.round(g, x + 1, y + 2, pw, ph, 0x60000000);
        Ui.round(g, x, y, pw, ph, 0xE016181D);

        Ui.text(g, Ui.clip(v.getDisplayName().getString(), pw - 50), x + 10, y + 7, Theme.MUTED);
        boolean engine = bool(v, "isEnginePowered", true);
        Ui.textRight(g, engine ? "motor pornit" : "motor oprit", x + pw - 10, y + 7, engine ? Theme.OK : Theme.FAINT);

        float kmh = (float) Math.abs(num(v, "getKilometersPreHour", 0));
        shownSpeed += (kmh - shownSpeed) * 0.25f;
        String sp = String.valueOf(Math.round(shownSpeed));
        g.pose().pushPose();
        g.pose().translate(x + 10, y + 19, 0);
        g.pose().scale(2.4f, 2.4f, 1f);
        Ui.text(g, sp, 0, 0, Theme.TEXT);
        g.pose().popPose();
        Ui.text(g, "km/h", x + 14 + Math.round(Ui.width(sp) * 2.4f), y + 31, Theme.FAINT);
        if (bool(v, "isBoosting", false)) Ui.textRight(g, "BOOST", x + pw - 10, y + 31, Theme.ACCENT);

        double max = num(v, "getActualMaxSpeed", num(v, "getMaxSpeed", 0));
        double speed = Math.abs(num(v, "getSpeed", 0));
        float frac = max <= 0 ? 0 : (float) Math.min(1.0, speed / max);
        int segs = 18;
        int sw = (pw - 20 - (segs - 1)) / segs;
        int by = y + 44;
        for (int i = 0; i < segs; i++) {
            boolean on = i < Math.round(frac * segs);
            int col = i >= segs - 3 ? Theme.BAD : (i >= segs - 7 ? Theme.WARN : Theme.ACCENT);
            Ui.rect(g, x + 10 + i * (sw + 1), by, sw, 4, on ? col : 0xFF2A2D33);
        }

        double maxHp = num(v, "getMaxHealth", 0);
        float hp = maxHp <= 0 ? 1f : (float) (num(v, "getHealth", maxHp) / maxHp);
        int ry = by + 9;
        Ui.text(g, "Stare", x + 10, ry, Theme.MUTED);
        Ui.textRight(g, Math.round(hp * 100) + "%", x + pw - 10, ry, hp < 0.3f ? Theme.BAD : Theme.TEXT);
        if (fuel) {
            double cap = num(v, "getFuelCapacity", 0);
            float f = cap <= 0 ? 0 : (float) (num(v, "getCurrentFuel", 0) / cap);
            boolean low = f < 0.12f;
            boolean blink = low && (System.currentTimeMillis() / 400) % 2 == 0;
            int fy = ry + 11;
            Ui.text(g, "Benzina", x + 10, fy, low ? Theme.BAD : Theme.MUTED);
            Ui.textRight(g, Math.round(f * 100) + "%", x + pw - 10, fy, blink ? Theme.BAD : Theme.TEXT);
            Ui.bar(g, x + 58, fy + 2, pw - 58 - 38, 4, f, low ? Theme.BAD : Theme.ACCENT);
            if (f <= 0f) Ui.textCenter(g, "Rezervorul e gol", w / 2, h - 70, Theme.BAD);
        }
    }
}
