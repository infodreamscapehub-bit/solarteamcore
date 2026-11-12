package ro.solarstudios.flowcity.cars.client;

import com.mrcrayfish.vehicle.Config;
import com.mrcrayfish.vehicle.entity.PoweredVehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/**
 * Bordul FlowCity pentru vehiculele MrCrayfish: viteza mare in km/h, bara de turatie, benzina, starea masinii si
 * indicatori (motor, boost). Inlocuieste textul simplu "BPS / Fuel" din stanga sus.
 * Clasa se incarca doar daca modul "vehicle" e instalat.
 */
public final class CarHud {
    private CarHud() {}

    private static boolean portHudOff;
    private static float shownSpeed;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new CarHud.Events());
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGuiEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui || !(mc.player.getVehicle() instanceof PoweredVehicleEntity v)) return;
            if (!portHudOff) {
                // oprim vitezometrul text al portului, ca sa nu apara doua borduri
                try {
                    if (Config.CLIENT.enabledSpeedometer.get()) Config.CLIENT.enabledSpeedometer.set(false);
                } catch (RuntimeException ignored) {
                    // config inca neincarcat: incercam la urmatorul cadru
                    return;
                }
                portHudOff = true;
            }
            try {
                draw(e.getGuiGraphics(), mc, v);
            } catch (RuntimeException ignored) {
                // un vehicul ciudat nu trebuie sa strice HUD-ul
            }
        }
    }

    private static void draw(GuiGraphics g, Minecraft mc, PoweredVehicleEntity v) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int pw = 150;
        boolean fuel = v.requiresFuel();
        int ph = fuel ? 74 : 58;
        int x = w - pw - 12;
        int y = h - ph - 12;
        Ui.round(g, x + 1, y + 2, pw, ph, 0x60000000);
        Ui.round(g, x, y, pw, ph, 0xE016181D);

        // nume vehicul
        String name = v.getDisplayName().getString();
        Ui.text(g, Ui.clip(name, pw - 50), x + 10, y + 7, Theme.MUTED);
        boolean engine = v.isEnginePowered();
        Ui.textRight(g, engine ? "motor pornit" : "motor oprit", x + pw - 10, y + 7, engine ? Theme.OK : Theme.FAINT);

        // viteza (netezita, ca cifrele sa nu sara)
        float kmh = (float) Math.abs(v.getKilometersPreHour());
        shownSpeed += (kmh - shownSpeed) * 0.25f;
        String sp = String.valueOf(Math.round(shownSpeed));
        g.pose().pushPose();
        g.pose().translate(x + 10, y + 19, 0);
        g.pose().scale(2.4f, 2.4f, 1f);
        Ui.text(g, sp, 0, 0, Theme.TEXT);
        g.pose().popPose();
        Ui.text(g, "km/h", x + 14 + Math.round(Ui.width(sp) * 2.4f), y + 31, Theme.FAINT);
        if (v.isBoosting()) Ui.textRight(g, "BOOST", x + pw - 10, y + 31, Theme.ACCENT);

        // bara de turatie: segmente care se aprind
        float frac = Math.min(1f, Math.abs(v.getActualSpeed()));
        int segs = 18;
        int sw = (pw - 20 - (segs - 1)) / segs;
        int by = y + 44;
        for (int i = 0; i < segs; i++) {
            boolean on = i < Math.round(frac * segs);
            int col = i >= segs - 3 ? Theme.BAD : (i >= segs - 7 ? Theme.WARN : Theme.ACCENT);
            Ui.rect(g, x + 10 + i * (sw + 1), by, sw, 4, on ? col : 0xFF2A2D33);
        }

        // stare + benzina
        float hp = v.getMaxHealth() <= 0 ? 1f : v.getHealth() / v.getMaxHealth();
        int ry = by + 9;
        Ui.text(g, "Stare", x + 10, ry, Theme.MUTED);
        Ui.textRight(g, Math.round(hp * 100) + "%", x + pw - 10, ry, hp < 0.3f ? Theme.BAD : Theme.TEXT);
        if (fuel) {
            float cap = v.getFuelCapacity();
            float f = cap <= 0 ? 0 : v.getCurrentFuel() / cap;
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
