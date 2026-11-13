package ro.solarstudios.flowcity.pay;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "pay": primeste cererea de plata de la plugin si deschide meniul "cash sau card?". */
public final class PayClient extends JsonClientModule {
    private static PayClient instance;

    private PayClient() {
        on("prompt", d -> {
            Minecraft mc = Minecraft.getInstance();
            Screen parent = mc.screen instanceof PayScreen ? ((PayScreen) mc.screen).parent() : mc.screen;
            mc.setScreen(new PayScreen(parent, d));
        });
    }

    @Override
    public String id() {
        return "pay";
    }

    public static void register() {
        instance = new PayClient();
        ClientModules.register(instance);
    }

    static void choose(int id, String method) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("method", method);
        if (instance != null) instance.send("choose", o);
    }

    static void cancel(int id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (instance != null) instance.send("cancel", o);
    }
}
