package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "mecanic": preturile serviciilor si rezultatul platii; lista masinilor vine pe canalul garajelor ("mech"). */
public final class MecanicClient extends JsonClientModule {
    private static MecanicClient instance;
    static int repair = 800;
    static int itp = 500;
    static int rca7 = 400;
    static int rca30 = 1200;

    private MecanicClient() {
        on("prices", d -> {
            repair = integer(d, "repair", repair);
            itp = integer(d, "itp", itp);
            rca7 = integer(d, "rca7", rca7);
            rca30 = integer(d, "rca30", rca30);
        });
        on("result", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof MecanicScreen) ((MecanicScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        }));
    }

    @Override
    public String id() {
        return "mecanic";
    }

    public static void register() {
        instance = new MecanicClient();
        ClientModules.register(instance);
    }

    static void service(int car, String svc) {
        JsonObject o = new JsonObject();
        o.addProperty("car", car);
        o.addProperty("svc", svc);
        if (instance != null) instance.send("service", o);
    }
}
