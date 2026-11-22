package ro.solarstudios.flowcity.shop;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "shop": primeste continutul magazinului de la plugin (deschis de un NPC), actualizarile dupa editare si rezultatul cumpararii. */
public final class ShopClient extends JsonClientModule {
    private static ShopClient instance;

    private ShopClient() {
        on("open", d -> Minecraft.getInstance().setScreen(new ShopScreen(d)));
        on("state", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ShopScreen) ((ShopScreen) mc.screen).state(d);
        });
        on("update", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ShopScreen) ((ShopScreen) mc.screen).refresh(d);
        });
        on("editres", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ShopScreen) ((ShopScreen) mc.screen).editResult(bool(d, "ok", false), str(d, "msg", ""));
        });
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

    /** Operatie de editare a magazinului (doar op-ii; serverul re-verifica permisiunea). */
    static void edit(JsonObject o) {
        if (instance != null) instance.send("edit", o);
    }

    static void buy(int cat, int idx, int qty) {
        JsonObject o = new JsonObject();
        o.addProperty("cat", cat);
        o.addProperty("idx", idx);
        o.addProperty("qty", qty);
        if (instance != null) instance.send("buy", o);
    }
}
