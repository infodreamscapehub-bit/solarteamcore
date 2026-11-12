package ro.solarstudios.flowcity.vehicle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.content.ModContent;

/** Client: inregistrare model/renderer, taste soferului si bordul (viteza). */
public final class CarClient {
    private CarClient() {}

    public static void register() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CarClient::layers);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CarClient::renderers);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    private static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) {
        e.registerLayerDefinition(CarModel.LAYER, CarModel::createLayer);
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModContent.CAR.get(), CarRenderer::new);
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.player.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == mc.player && mc.screen == null) {
                car.setInput(mc.options.keyLeft.isDown(), mc.options.keyRight.isDown(), mc.options.keyUp.isDown(), mc.options.keyDown.isDown(), mc.options.keyJump.isDown());
            } else if (mc.player.getVehicle() instanceof CarEntity car2 && car2.getControllingPassenger() == mc.player) {
                car2.setInput(false, false, false, false, true);
            }
        }

        @SubscribeEvent
        public void onHud(RenderGuiEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui || !(mc.player.getVehicle() instanceof CarEntity car)) return;
            GuiGraphics g = e.getGuiGraphics();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            int pw = 112;
            int ph = 40;
            int x = w - pw - 14;
            int y = h - ph - 14;
            Ui.rect(g, x - 2, y - 2, pw + 4, ph + 4, 0xFF05070C);
            Ui.rect(g, x - 1, y - 1, pw + 2, ph + 2, 0xFF8892A6);
            Ui.rect(g, x, y, pw, ph, 0xEE0E1627);
            int kmh = (int) Math.round(car.speedKmh());
            Ui.textShadow(g, String.valueOf(kmh), x + 10, y + 8, Theme.TEXT);
            Ui.text(g, "km/h", x + 10 + Ui.width(String.valueOf(kmh)) + 4, y + 8, Theme.MUTED);
            float frac = (float) Math.min(1.0, Math.abs(car.speedKmh()) / (CarEntity.MAX_FORWARD * 72.0));
            Ui.bar(g, x + 10, y + 24, pw - 20, 5, frac, frac > 0.85f ? Theme.BAD : (frac > 0.6f ? Theme.WARN : Theme.ACCENT));
            String gear = car.reversing() ? "R" : (car.speedKmh() < 1 ? "N" : "D");
            Ui.textRight(g, gear, x + pw - 10, y + 8, car.reversing() ? Theme.WARN : Theme.ACCENT);
            if (car.getControllingPassenger() != mc.player) Ui.text(g, "pasager", x + 10, y + 31, Theme.FAINT);
        }
    }
}
