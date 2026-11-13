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
    /** Preturi de tuning (paint, engines, wheels) trimise de plugin odata cu catalogul. */
    public static JsonObject tuning = new JsonObject();

    private CarsClient() {
        on("catalog", d -> {
            if (d.has("vehicles") && d.get("vehicles").isJsonArray()) catalog = d.getAsJsonArray("vehicles");
            if (d.has("tuning") && d.get("tuning").isJsonObject()) tuning = d.getAsJsonObject("tuning");
            money = num(d, "money", money);
        });
        on("tuning_open", d -> {
            tuning = d;
            money = num(d, "money", money);
            if (!(Minecraft.getInstance().screen instanceof TuningScreen)) TuningScreen.requestOpen("npc");
        });
        on("tune_result", d -> {
            money = num(d, "money", money);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TuningScreen) ((TuningScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
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
        on("fuel_info", d -> {
            FuelScreen.info(num(d, "price", 8));
            money = num(d, "money", money);
        });
        on("fuel_result", d -> {
            money = num(d, "money", money);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FuelScreen) ((FuelScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""), num(d, "liters", 0));
        });
        on("dealer_open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof DealerScreen)) DealerScreen.open(null);
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
