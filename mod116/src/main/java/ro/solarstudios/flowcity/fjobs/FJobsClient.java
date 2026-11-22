package ro.solarstudios.flowcity.fjobs;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ClientBridge;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.hype.HypeState;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client FlowCity Jobs: ecranul Job Center, tasta configurabila si mesajele de la server. Serverul e autoritar pentru tot. */
public final class FJobsClient extends JsonClientModule {
    private static FJobsClient instance;
    private static KeyBinding key;
    /** Ultimul pachet "data" primit (profil, joburi, istoric). */
    static JsonObject data;

    private FJobsClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof FJobsScreen)) mc.setScreen(new FJobsScreen());
        });
        on("data", d -> data = d);
        on("prof", d -> {
            if (data != null) data.add("me", d);
        });
        on("msg", d -> {
            String text = str(d, "text", "");
            if (text.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FlowScreen) ((FlowScreen) mc.screen).toast(text, !bool(d, "ok", true));
        });
        on("earn", d -> HypeState.popup("+$" + ro.solarstudios.flowcity.client.ui.Ui.money(num(d, "pay", 0)) + "  +" + integer(d, "xp", 0) + " XP"));
    }

    @Override
    public String id() {
        return "fjobs";
    }

    @Override
    public void onLogout() {
        data = null;
    }

    public static void register() {
        instance = new FJobsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        key = new KeyBinding("key.flowcity.fjobs", GLFW.GLFW_KEY_J, "key.categories.flowcity");
        e.register(key);
    }

    /** Cere datele si deschide Job Center (serverul raspunde cu "open" + "data"; daca modul lipseste, nu se intampla nimic). */
    public static void open() {
        request("req", new JsonObject());
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof FJobsScreen)) mc.setScreen(new FJobsScreen());
    }

    static void request(String action, JsonObject o) {
        if (instance != null && ClientBridge.connected) instance.send(action, o == null ? new JsonObject() : o);
    }

    static void closed() {
        request("close", new JsonObject());
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || key == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (key.consumeClick()) {
                if (mc.screen == null) open();
            }
        }
    }
}
