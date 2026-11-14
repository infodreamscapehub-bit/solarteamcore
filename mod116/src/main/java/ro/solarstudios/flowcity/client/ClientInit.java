package ro.solarstudios.flowcity.client;

import net.minecraft.util.text.StringTextComponent;

import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Initializare client: handshake cu pluginul, taste. Rulat doar pe Dist.CLIENT. */
public final class ClientInit {
    private ClientInit() {}

    public static KeyBinding openAdmin;
    public static KeyBinding hudEdit;
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
        ro.solarstudios.flowcity.news.NewsClient.register();
        ro.solarstudios.flowcity.wheel.WheelClient.register();
        ro.solarstudios.flowcity.notify.NotifyClient.register();
        ro.solarstudios.flowcity.mdt.MdtClient.register();
        ro.solarstudios.flowcity.hype.HypeClient.register();
        ro.solarstudios.flowcity.phone.PhoneClient.register();
        ro.solarstudios.flowcity.cars.client.CarsClient.register();
        ro.solarstudios.flowcity.pay.PayClient.register();
        ro.solarstudios.flowcity.shop.ShopClient.register();
        ro.solarstudios.flowcity.fines.FinesClient.register();
        ro.solarstudios.flowcity.bank.BankClient.register();
        ro.solarstudios.flowcity.cars.client.MecanicClient.register();
        ro.solarstudios.flowcity.houses.HousesClient.register();
        ro.solarstudios.flowcity.docs.DocsClient.register();
        ro.solarstudios.flowcity.politie.PolitieClient.register();
        ro.solarstudios.flowcity.hudeditor.HudEditorClient.register();
        ro.solarstudios.flowcity.cars.client.GaragesClient.register();
        if (ro.solarstudios.flowcity.cars.CarsCompat.loaded()) ro.solarstudios.flowcity.cars.client.CarHud.register();
    }

    private static void setup(FMLClientSetupEvent event) {
        keys(new RegisterKeyMappingsEvent());
        event.enqueueWork(ClientBridge::registerHandler);
    }

    private static void keys(RegisterKeyMappingsEvent event) {
        openAdmin = new KeyBinding("key.flowcity.admin", GLFW.GLFW_KEY_F9, "key.categories.flowcity");
        event.register(openAdmin);
        hudEdit = new KeyBinding("key.flowcity.hudedit", GLFW.GLFW_KEY_F8, "key.categories.flowcity");
        event.register(hudEdit);
        ro.solarstudios.flowcity.mdt.MdtClient.registerKeys(event);
        ro.solarstudios.flowcity.phone.PhoneClient.registerKeys(event);
        ro.solarstudios.flowcity.cars.client.GaragesClient.registerKeys(event);
    }

    private static boolean openHudEditor;

    public static final class Events {
        @SubscribeEvent
        public void onChat(net.minecraftforge.client.event.ClientChatEvent e) {
            String m = e.getMessage() == null ? "" : e.getMessage().trim().toLowerCase();
            if (m.equals(".hudeditor") || m.equals(".hud")) {
                e.setCanceled(true);
                Minecraft.getInstance().gui.getChat().addRecentChat(e.getMessage());
                openHudEditor = true;
            }
        }

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
                        mc.player.displayClientMessage(new StringTextComponent(
                            "§e[FlowCity] §7Serverul nu raspunde: lipseste pluginul FlowCityBridge sau are alta versiune."), false);
                    }
                }
            }
            if (openHudEditor && (mc.screen == null || mc.screen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
                openHudEditor = false;
                mc.setScreen(new ro.solarstudios.flowcity.client.ui.HudEditorScreen());
            }
            if (hudEdit != null) {
                while (hudEdit.consumeClick()) {
                    if (mc.screen == null) mc.setScreen(new ro.solarstudios.flowcity.client.ui.HudEditorScreen());
                }
            }
            if (openAdmin != null) {
                while (openAdmin.consumeClick()) {
                    if (mc.screen == null) ClientBridge.sendOpenRequest();
                }
            }
            long pending = ClientBridge.pendingOpenAt;
            if (pending > 0 && System.currentTimeMillis() - pending > 3000) {
                ClientBridge.pendingOpenAt = 0;
                mc.player.displayClientMessage(new StringTextComponent("§cServerul nu a raspuns. §7Pluginul FlowCityBridge este instalat?"), true);
            }
        }
    }
}
