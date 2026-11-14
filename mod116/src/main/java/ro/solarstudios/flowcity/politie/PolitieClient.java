package ro.solarstudios.flowcity.politie;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "politie": hub-ul Politiei (amenzi, documente, cazier, sesizari, angajare). */
public final class PolitieClient extends JsonClientModule {
    private static PolitieClient instance;

    private PolitieClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PolitieScreen) ((PolitieScreen) mc.screen).update(d);
            else mc.setScreen(new PolitieScreen(d));
        });
        on("result", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PolitieScreen) ((PolitieScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        });
        on("cazier", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PolitieScreen) ((PolitieScreen) mc.screen).certificate(d);
        });
    }

    @Override
    public String id() {
        return "politie";
    }

    public static void register() {
        instance = new PolitieClient();
        ClientModules.register(instance);
    }

    static void act(String action, JsonObject o) {
        if (instance != null) instance.send(action, o);
    }

    static void act(String action) {
        act(action, new JsonObject());
    }
}
