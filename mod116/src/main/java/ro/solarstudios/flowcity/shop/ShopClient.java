package ro.solarstudios.flowcity.shop;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "shop": primeste continutul magazinului de la plugin (deschis de un NPC) si rezultatul cumpararii. */
public final class ShopClient extends JsonClientModule {
    private static ShopClient instance;

    private ShopClient() {
        on("open", d -> Minecraft.getInstance().setScreen(new ShopScreen(d)));
        on("result", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ShopScreen) ((ShopScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        });
    }

    @Override
    public String id() {
        return "shop";
    }

    public static void register() {
        instance = new ShopClient();
        ClientModules.register(instance);
    }

    static void buy(int cat, int idx, int qty) {
        JsonObject o = new JsonObject();
        o.addProperty("cat", cat);
        o.addProperty("idx", idx);
        o.addProperty("qty", qty);
        if (instance != null) instance.send("buy", o);
    }
}
