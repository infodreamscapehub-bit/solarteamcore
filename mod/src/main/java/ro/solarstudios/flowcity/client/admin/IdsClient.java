package ro.solarstudios.flowcity.client.admin;

import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Canalul "ids": cereri catre server pentru schimbarea ID-urilor (raspunsul vine ca toast + profil actualizat). */
public final class IdsClient extends JsonClientModule {
    private static IdsClient instance;

    public IdsClient() {
        instance = this;
    }

    @Override
    public String id() {
        return "ids";
    }

    public static void set(String uuid, int id, boolean swap) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("id", id);
        o.addProperty("swap", swap);
        instance.send("set", o);
    }
}
