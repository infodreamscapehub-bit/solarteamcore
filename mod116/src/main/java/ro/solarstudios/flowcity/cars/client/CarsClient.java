package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "cars" (prin bridge): catalogul dealerului si cumpararea (plata prin Vault, pe plugin). */
public final class CarsClient extends JsonClientModule {
    private static CarsClient instance;
    public static JsonArray catalog = new JsonArray();
    public static double money;
    public static double poundFee = 500;

    private CarsClient() {
        on("catalog", d -> {
            if (d.has("vehicles") && d.get("vehicles").isJsonArray()) catalog = d.getAsJsonArray("vehicles");
            money = num(d, "money", money);
        });
        on("pound_info", d -> {
            poundFee = num(d, "fee", poundFee);
            money = num(d, "money", money);
        });
        on("pound_result", d -> {
            money = num(d, "money", money);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof GarageScreen) ((GarageScreen) mc.screen).payResult(bool(d, "ok", false), str(d, "msg", ""));
        });
        on("police_result", d -> {
            Minecraft mc = Minecraft.getInstance();
            boolean ok = bool(d, "ok", false);
            if (mc.screen instanceof PoliceVehicleScreen) {
                ((PoliceVehicleScreen) mc.screen).result(ok, str(d, "msg", ""));
                if (ok && bool(d, "close", false)) mc.setScreen(null);
            }
        });
        on("buy_result", d -> {
            money = num(d, "money", money);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof DealerScreen) ((DealerScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        });
    }

    @Override
    public String id() {
        return "cars";
    }

    public static void register() {
        instance = new CarsClient();
        ClientModules.register(instance);
    }

    public static void request(String action, JsonObject data) {
        if (instance != null) instance.send(action, data);
    }
}
