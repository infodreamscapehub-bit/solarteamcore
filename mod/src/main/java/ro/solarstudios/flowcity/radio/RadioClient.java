package ro.solarstudios.flowcity.radio;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "radio": ecranul de statii personale + HUD-ul statiei. Serverul: RadioStationsModule din FlowCityBridge. */
public final class RadioClient extends JsonClientModule {
    private static RadioClient instance;
    private static KeyMapping key;
    /** Ultima stare primita de la server (statii, invitatii, statia activa). */
    static JsonObject state = new JsonObject();
    static long stateAt;

    private RadioClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof RadioScreen)) mc.setScreen(new RadioScreen());
        });
        on("state", d -> {
            state = d;
            stateAt = System.currentTimeMillis();
        });
        on("talk", d -> {
            if (!state.has("active") || !state.get("active").isJsonObject()) return;
            JsonObject a = state.getAsJsonObject("active");
            if (JsonClientModule.num(a, "id", -1) == JsonClientModule.num(d, "station", -2) && d.has("talking")) a.add("talking", d.get("talking"));
        });
        on("result", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof RadioScreen) ((RadioScreen) mc.screen).result(bool(d, "ok", false), str(d, "text", ""));
        });
    }

    @Override
    public String id() {
        return "radio";
    }

    @Override
    public void onLogout() {
        state = new JsonObject();
    }

    public static void register() {
        instance = new RadioClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new RadioHud.Events());
        MinecraftForge.EVENT_BUS.register(new KeyEvents());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        key = new KeyMapping("key.flowcity.radio", GLFW.GLFW_KEY_SEMICOLON, "key.categories.flowcity");
        e.register(key);
    }

    static void act(String action, JsonObject o) {
        if (instance != null) instance.send(action, o);
    }

    static void act(String action) {
        act(action, new JsonObject());
    }

    public static final class KeyEvents {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || key == null) return;
            Minecraft mc = Minecraft.getInstance();
            while (key.consumeClick()) {
                if (mc.player != null && mc.screen == null) {
                    act("refresh");
                    mc.setScreen(new RadioScreen());
                }
            }
        }
    }
}
