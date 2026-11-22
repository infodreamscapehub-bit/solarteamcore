package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ClientBridge;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Modulul client "gps": primeste de la plugin configul (categorii, locatii, zone), deschide harta (tasta M, /gps, .gps),
 * seteaza/anuleaza destinatia si tine harta lumii la zi. Serverul: GpsModule din pluginul FlowCityBridge.
 */
public final class GpsClient extends JsonClientModule {
    public static KeyBinding key;
    private static GpsClient instance;
    private static boolean wantOpen;
    private static long lastRequest;
    private static int lowTick;

    private GpsClient() {
        on("config", d -> {
            GpsData.apply(d);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof GpsScreen) ((GpsScreen) mc.screen).dataChanged();
            if (mc.screen instanceof MapEditorScreen) ((MapEditorScreen) mc.screen).dataChanged();
        });
        on("editres", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MapEditorScreen) ((MapEditorScreen) mc.screen).onResult(str(d, "op", ""), bool(d, "ok", false), str(d, "msg", ""));
        });
        on("roads", d -> {
            int r0 = RoadNet.revision;
            RoadNet.onRoads(d);
            Minecraft mc = Minecraft.getInstance();
            if (RoadNet.revision != r0 && mc.screen instanceof MapEditorScreen) ((MapEditorScreen) mc.screen).roadsChanged();
        });
        on("scanprog", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MapEditorScreen) ((MapEditorScreen) mc.screen).scanProgress((int) num(d, "pct", 0));
        });
        on("scanres", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MapEditorScreen) ((MapEditorScreen) mc.screen).scanResult(d);
        });
        on("open", d -> wantOpen = true);
        on("set", d -> GpsNav.set("srv", str(d, "name", "Destinație"), num(d, "x", 0), num(d, "z", 0), str(d, "dim", "minecraft:overworld"), 0xFF4DA3FF));
        on("cancel", d -> GpsNav.clear());
    }

    @Override
    public String id() {
        return "gps";
    }

    @Override
    public void onLogout() {
        GpsData.clear();
        RoadNet.clear();
        GpsNav.clear();
        GpsMap.shutdown();
        wantOpen = false;
    }

    public static void register() {
        instance = new GpsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
        MinecraftForge.EVENT_BUS.register(new GpsHud.Events());
        MiniMapClient.register();
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        key = new KeyBinding("key.flowcity.gps", GLFW.GLFW_KEY_M, "key.categories.flowcity");
        e.register(key);
        MiniMapClient.registerKeys(e);
    }

    /** Cere configul de la server (cel mult o data la 5 secunde). */
    static void requestConfig() {
        long now = System.currentTimeMillis();
        if (instance == null || !ClientBridge.connected || now - lastRequest < 5000) return;
        lastRequest = now;
        instance.send("request", new JsonObject());
    }

    /** Trimite o editare serverului (autoritar: el re-verifica permisiunea si valideaza tot). */
    public static void sendEdit(String op, JsonObject o) {
        if (instance == null) return;
        o.addProperty("op", op);
        instance.send("edit", o);
    }

    public static void open() {
        wantOpen = true;
    }

    public static final class Events {
        @SubscribeEvent
        public void onChat(ClientChatEvent e) {
            String m = e.getMessage() == null ? "" : e.getMessage().trim().toLowerCase();
            if (m.equals(".gps")) {
                e.setCanceled(true);
                Minecraft.getInstance().gui.getChat().addRecentChat(e.getMessage());
                wantOpen = true;
            }
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            int budget = mc.screen instanceof GpsScreen ? 10 : (MiniMapSettings.lowPerf ? 1 : 2);
            // mod performanta redusa: scanam harta doar la fiecare al doilea tick
            if (budget > 1 || (++lowTick & 1) == 0) GpsMap.tick(mc, budget);
            GpsNav.tick(mc);
            if (key != null) {
                while (key.consumeClick()) {
                    if (mc.screen == null) wantOpen = true;
                }
            }
            if (wantOpen && (mc.screen == null || mc.screen instanceof ChatScreen)) {
                wantOpen = false;
                mc.setScreen(new GpsScreen());
            }
        }
    }
}
