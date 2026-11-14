package ro.solarstudios.flowcity.fines;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "fines": primeste amenzile jucatorului (deschide ecranul) si rezultatul platii. */
public final class FinesClient extends JsonClientModule {
    private static FinesClient instance;

    private FinesClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FinesScreen) ((FinesScreen) mc.screen).update(d);
            else mc.setScreen(new FinesScreen(d));
        });
        on("result", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FinesScreen) ((FinesScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        });
    }

    @Override
    public String id() {
        return "fines";
    }

    public static void register() {
        instance = new FinesClient();
        ClientModules.register(instance);
    }

    static void pay(long id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (instance != null) instance.send("pay", o);
    }
}
