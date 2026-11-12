package ro.solarstudios.flowcity.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Initializare client: tasta de deschidere, handshake cu pluginul. Rulat doar pe Dist.CLIENT. */
public final class ClientInit {
    private ClientInit() {}

    public static KeyBinding openAdmin;
    private static int helloTicks = -1;
    private static int helloTries;

    public static void register() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientInit::setup);
        MinecraftForge.EVENT_BUS.register(new Events());
        MinecraftForge.EVENT_BUS.register(new ro.solarstudios.flowcity.menu.MenuEvents());
        ro.solarstudios.flowcity.client.admin.IdsClient.register();
        ro.solarstudios.flowcity.client.admin.DashPanel.register();
        ro.solarstudios.flowcity.client.admin.AdminX.register();
        ro.solarstudios.flowcity.update.UpdateClient.register();
        ro.solarstudios.flowcity.notify.NotifyClient.register();
        ro.solarstudios.flowcity.news.NewsClient.register();
        ro.solarstudios.flowcity.wheel.WheelClient.register();
        ro.solarstudios.flowcity.hype.HypeClient.register();
        ro.solarstudios.flowcity.maps.MapsClient.register();
        ro.solarstudios.flowcity.mdt.MdtClient.register();
        ro.solarstudios.flowcity.phone.PhoneClient.register();
    }

    private static void setup(FMLClientSetupEvent event) {
        openAdmin = new KeyBinding("key.flowcity.admin", GLFW.GLFW_KEY_F9, "key.categories.flowcity");
        event.enqueueWork(() -> {
            ClientRegistry.registerKeyBinding(openAdmin);
            ro.solarstudios.flowcity.phone.PhoneClient.registerKeys();
            ro.solarstudios.flowcity.maps.MapsClient.registerKeys();
            ro.solarstudios.flowcity.mdt.MdtClient.registerKeys();
            ClientBridge.registerHandler();
        });
    }

    public static final class Events {
        @SubscribeEvent
        public void onLogin(ClientPlayerNetworkEvent.LoggedInEvent e) {
            helloTicks = 40; // trimitem HELLO la ~2s dupa intrare, cand conexiunea e stabila
            helloTries = 0;
            ClientBridge.connected = false;
        }

        @SubscribeEvent
        public void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent e) {
            helloTicks = -1;
            ClientBridge.connected = false;
            ClientBridge.pendingOpenAt = 0;
            ro.solarstudios.flowcity.module.ClientModules.logout();
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (helloTicks >= 0 && --helloTicks < 0) {
                if (!ClientBridge.connected) {
                    helloTries++;
                    ClientBridge.sendHello();
                    if (helloTries < 12) {
                        helloTicks = 60; // reincearca la 3s pana raspunde serverul
                    } else {
                        mc.player.sendStatusMessage(new StringTextComponent(
                            "\u00a7e[FlowCity] \u00a77Serverul nu raspunde: lipseste pluginul FlowCityBridge sau are alta versiune."), false);
                    }
                }
            }
            if (openAdmin != null) {
                while (openAdmin.isPressed()) {
                    if (mc.currentScreen == null) ClientBridge.sendOpenRequest();
                }
            }
            long pending = ClientBridge.pendingOpenAt;
            if (pending > 0 && System.currentTimeMillis() - pending > 3000) {
                ClientBridge.pendingOpenAt = 0;
                mc.player.sendStatusMessage(
                    new StringTextComponent("§cServerul nu a raspuns. §7Pluginul FlowCityBridge este instalat?"), true);
            }
        }
    }
}
