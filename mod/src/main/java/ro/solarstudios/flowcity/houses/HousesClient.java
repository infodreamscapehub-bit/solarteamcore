package ro.solarstudios.flowcity.houses;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "houses": lista caselor (deschide ecranul) si rezultatul actiunilor. */
public final class HousesClient extends JsonClientModule {
    private static HousesClient instance;

    private HousesClient() {
        on("open", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof HousesScreen) ((HousesScreen) mc.screen).update(d);
            else mc.setScreen(new HousesScreen(d));
        }));
        on("result", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof HousesScreen) ((HousesScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        }));
    }

    @Override
    public String id() {
        return "houses";
    }

    public static void register() {
        instance = new HousesClient();
        ClientModules.register(instance);
    }

    static void act(String action, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (instance != null) instance.send(action, o);
    }

    static void removeMate(String id, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        if (instance != null) instance.send("mate_remove", o);
    }
}
