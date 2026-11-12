package ro.solarstudios.flowcity.vehicle;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "vehicle" (prin bridge): pretul benzinei si plata la pompa. */
public final class VehicleClient extends JsonClientModule {
    private static VehicleClient instance;
    public static double price = 7;
    public static double cash;
    public static double bank;

    private VehicleClient() {
        on("pump", d -> {
            price = num(d, "price", price);
            cash = num(d, "cash", cash);
            bank = num(d, "bank", bank);
        });
        on("fuel_result", d -> {
            boolean ok = bool(d, "ok", false);
            cash = num(d, "cash", cash);
            bank = num(d, "bank", bank);
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof GasPumpScreen ps) ps.result(ok, str(d, "msg", ok ? "Plata acceptata." : "Plata refuzata."), (float) num(d, "liters", 0));
            else if (mc.screen instanceof FlowScreen fs) fs.toast(str(d, "msg", ""), !ok);
        });
    }

    @Override
    public String id() {
        return "vehicle";
    }

    public static void register() {
        instance = new VehicleClient();
        ClientModules.register(instance);
    }

    public static void request(String action, JsonObject data) {
        if (instance != null) instance.send(action, data);
    }
}
