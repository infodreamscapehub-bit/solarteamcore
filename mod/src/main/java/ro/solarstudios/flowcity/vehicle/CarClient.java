package ro.solarstudios.flowcity.vehicle;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.content.ModContent;

/** Client: model/renderer, tastele din masina (claxon, faruri, sirena, incuiere, loc) si bordul. */
public final class CarClient {
    private CarClient() {}

    public static KeyMapping hornKey;
    public static KeyMapping lightsKey;
    public static KeyMapping sirenKey;
    public static KeyMapping lockKey;
    public static KeyMapping seatKey;
    private static boolean hornDown;

    public static void register() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CarClient::layers);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CarClient::renderers);
        MinecraftForge.EVENT_BUS.register(new Events());
        VehicleClient.register();
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        hornKey = key(e, "horn", GLFW.GLFW_KEY_H);
        lightsKey = key(e, "lights", GLFW.GLFW_KEY_J);
        sirenKey = key(e, "siren", GLFW.GLFW_KEY_N);
        lockKey = key(e, "lock", GLFW.GLFW_KEY_U);
        seatKey = key(e, "seat", GLFW.GLFW_KEY_G);
    }

    private static KeyMapping key(RegisterKeyMappingsEvent e, String name, int code) {
        KeyMapping k = new KeyMapping("key.flowcity.car_" + name, InputConstants.Type.KEYSYM, code, "key.categories.flowcity_car");
        e.register(k);
        return k;
    }

    private static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) {
        for (int i = 0; i < CarModel.LAYERS.length; i++) {
            final int body = i;
            e.registerLayerDefinition(CarModel.LAYERS[i], () -> CarModel.createLayer(body));
        }
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModContent.CAR.get(), CarRenderer::new);
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            VehicleSounds.tick(mc);
            if (mc.player == null) return;
            CarEntity car = mc.player.getVehicle() instanceof CarEntity c ? c : null;
            boolean driver = car != null && car.getControllingPassenger() == mc.player;
            if (driver && mc.screen == null) {
                car.setInput(mc.options.keyLeft.isDown(), mc.options.keyRight.isDown(), mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyJump.isDown());
            } else if (driver) {
                car.setInput(false, false, false, false, true);
            }
            // claxon: tinut apasat
            boolean horn = driver && mc.screen == null && hornKey != null && hornKey.isDown();
            if (horn != hornDown && car != null) VehicleNet.send(car.getId(), VehicleNet.HORN, horn ? 1 : 0);
            hornDown = horn;
            while (lightsKey != null && lightsKey.consumeClick()) if (driver) VehicleNet.send(car.getId(), VehicleNet.LIGHTS, 0);
            while (sirenKey != null && sirenKey.consumeClick()) if (driver) VehicleNet.send(car.getId(), VehicleNet.SIREN, 0);
            while (seatKey != null && seatKey.consumeClick()) if (car != null) VehicleNet.send(car.getId(), VehicleNet.SEAT, 0);
            while (lockKey != null && lockKey.consumeClick()) {
                CarEntity target = car != null ? car : nearest(mc);
                if (target != null) VehicleNet.send(target.getId(), VehicleNet.LOCK, 0);
            }
        }

        private static CarEntity nearest(Minecraft mc) {
            CarEntity best = null;
            double bd = 10 * 10;
            for (CarEntity c : mc.level.getEntitiesOfClass(CarEntity.class, new AABB(mc.player.blockPosition()).inflate(10))) {
                double d = c.distanceToSqr(mc.player);
                if (d < bd) {
                    bd = d;
                    best = c;
                }
            }
            return best;
        }

        @SubscribeEvent
        public void onHud(RenderGuiEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui || !(mc.player.getVehicle() instanceof CarEntity car)) return;
            drawDash(e.getGuiGraphics(), mc, car);
        }
    }

    /** Bordul: viteza mare, treapta, benzina, indicatori (faruri, incuiat, girofar). */
    static void drawDash(GuiGraphics g, Minecraft mc, CarEntity car) {
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int pw = 128;
        int ph = 64;
        int x = w - pw - 12;
        int y = h - ph - 12;
        Ui.round(g, x + 1, y + 2, pw, ph, 0x60000000);
        Ui.round(g, x, y, pw, ph, 0xE016181D);

        int kmh = (int) Math.round(car.speedKmh());
        String sp = String.valueOf(kmh);
        g.pose().pushPose();
        g.pose().translate(x + 10, y + 8, 0);
        // nume vehicul deasupra bordului
        g.pose().scale(2f, 2f, 1f);
        Ui.text(g, sp, 0, 0, Theme.TEXT);
        g.pose().popPose();
        Ui.text(g, "km/h", x + 12 + Ui.width(sp) * 2, y + 15, Theme.FAINT);
        String gear = car.reversing() ? "R" : (car.speedKmh() < 1 ? "N" : "D");
        Ui.textRight(g, gear, x + pw - 10, y + 8, car.reversing() ? Theme.WARN : Theme.ACCENT);

        // benzina
        float fuel = car.getFuel();
        float frac = fuel / CarEntity.TANK;
        boolean low = frac < 0.12f;
        boolean blink = low && (System.currentTimeMillis() / 400) % 2 == 0;
        int by = y + 30;
        Ui.text(g, "Benzina", x + 10, by, low ? Theme.BAD : Theme.MUTED);
        Ui.textRight(g, Math.round(fuel) + " L", x + pw - 10, by, blink ? Theme.BAD : Theme.TEXT);
        Ui.bar(g, x + 10, by + 10, pw - 20, 4, frac, low ? Theme.BAD : Theme.ACCENT);

        // indicatori
        int ix = x + 10;
        int iy = y + ph - 2;
        if (car.getControllingPassenger() != mc.player) {
            Ui.text(g, "pasager", x + pw - 10 - Ui.width("pasager"), y + 20, Theme.FAINT);
        }
        ix = indicator(g, ix, iy, "faruri", car.flag(CarEntity.F_LIGHTS), Theme.WARN);
        ix = indicator(g, ix, iy, "incuiat", car.flag(CarEntity.F_LOCKED), Theme.BAD);
        if (car.hasSiren()) indicator(g, ix, iy, car.flag(CarEntity.F_SIREN) ? "sirena" : "girofar", car.flag(CarEntity.F_BEACON),
            (System.currentTimeMillis() / 250) % 2 == 0 ? 0xFFE0473E : 0xFF3F7FE0);
        if (fuel <= 0f) Ui.textCenter(g, "Rezervorul e gol", w / 2, h - 70, Theme.BAD);
    }

    private static int indicator(GuiGraphics g, int x, int y, String label, boolean on, int color) {
        if (!on) return x;
        Ui.rect(g, x, y - 9, 3, 3, color);
        Ui.text(g, label, x + 5, y - 11, color);
        return x + 5 + Ui.width(label) + 8;
    }
}
