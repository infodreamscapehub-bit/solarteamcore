package ro.solarstudios.flowcity.taxi;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "taxi": ecranul de taxi + taximetrul din HUD. Serverul: TaxiModule din pluginul FlowCityBridge. */
public final class TaxiClient extends JsonClientModule {
    private static TaxiClient instance;

    private TaxiClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TaxiScreen) ((TaxiScreen) mc.screen).update(d);
            else mc.setScreen(new TaxiScreen(d));
        });
        on("state", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TaxiScreen) ((TaxiScreen) mc.screen).update(d);
        });
        on("result", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TaxiScreen) ((TaxiScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        });
        on("hud", TaxiHud::apply);
    }

    @Override
    public String id() {
        return "taxi";
    }

    @Override
    public void onLogout() {
        TaxiHud.clear();
    }

    public static void register() {
        instance = new TaxiClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new TaxiHud.Events());
    }

    static void act(String action, JsonObject o) {
        if (instance != null) instance.send(action, o);
    }

    static void act(String action) {
        act(action, new JsonObject());
    }
}
