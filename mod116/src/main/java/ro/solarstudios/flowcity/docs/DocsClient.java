package ro.solarstudios.flowcity.docs;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "docs": ecranul Primariei (buletin) si afisarea documentelor. */
public final class DocsClient extends JsonClientModule {
    private static DocsClient instance;

    private DocsClient() {
        on("primarie", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PrimarieScreen) ((PrimarieScreen) mc.screen).update(d);
            else mc.setScreen(new PrimarieScreen(d));
        });
        on("view", d -> Minecraft.getInstance().setScreen(new DocViewScreen(d)));
        on("result", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PrimarieScreen) ((PrimarieScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        });
    }

    @Override
    public String id() {
        return "docs";
    }

    public static void register() {
        instance = new DocsClient();
        ClientModules.register(instance);
    }

    static void apply(String name, String surname, String birth, String address, String sex) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("surname", surname);
        o.addProperty("birth", birth);
        o.addProperty("address", address);
        o.addProperty("sex", sex);
        if (instance != null) instance.send("apply", o);
    }

    static void renew() {
        if (instance != null) instance.send("renew", new JsonObject());
    }

    static void duplicate() {
        if (instance != null) instance.send("duplicate", new JsonObject());
    }

    static void showNearest(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if (instance != null) instance.send("show", o);
    }
}
