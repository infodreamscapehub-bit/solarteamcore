package ro.solarstudios.flowcity.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Initializare client: handshake cu pluginul, taste. Rulat doar pe Dist.CLIENT. */
public final class ClientInit {
    private ClientInit() {}

    private static int helloTicks = -1;
    private static int helloTries;

    public static void register() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientInit::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(ClientInit::keys);
        MinecraftForge.EVENT_BUS.register(new Events());
        ro.solarstudios.flowcity.notify.NotifyClient.register();
        ro.solarstudios.flowcity.mdt.MdtClient.register();
    }

    private static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientBridge::registerHandler);
    }

    private static void keys(RegisterKeyMappingsEvent event) {
        ro.solarstudios.flowcity.mdt.MdtClient.registerKeys(event);
    }

    public static final class Events {
        @SubscribeEvent
        public void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
            helloTicks = 40; // trimitem HELLO la ~2s dupa intrare, cand conexiunea e stabila
            helloTries = 0;
            ClientBridge.connected = false;
        }

        @SubscribeEvent
        public void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
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
                        mc.player.displayClientMessage(Component.literal(
                            "§e[FlowCity] §7Serverul nu raspunde: lipseste pluginul FlowCityBridge sau are alta versiune."), false);
                    }
                }
            }
        }
    }
}
