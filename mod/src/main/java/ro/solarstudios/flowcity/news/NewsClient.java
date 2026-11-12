package ro.solarstudios.flowcity.news;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "news" (CNN): lista, banner animat la publicare, publicare/stergere pentru staff. */
public final class NewsClient extends JsonClientModule {
    private static NewsClient instance;

    private NewsClient() {
        on("list", d -> {
            NewsState.items.clear();
            NewsState.cats.clear();
            if (d.has("items") && d.get("items").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("items")) if (e.isJsonObject()) NewsState.items.add(parse(e.getAsJsonObject()));
            }
            if (d.has("cats") && d.get("cats").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("cats")) NewsState.cats.add(e.getAsString());
            }
            NewsState.canPublish = bool(d, "canPublish", false);
            NewsState.canDelete = bool(d, "canDelete", false);
            Minecraft mc = Minecraft.getInstance();
            if (bool(d, "open", false) && !(mc.screen instanceof NewsScreen)) mc.setScreen(new NewsScreen());
        });
        on("banner", d -> {
            NewsState.Item it = parse(d);
            NewsBanner.show(it, Math.max(3, integer(d, "seconds", 9)));
            NewsState.items.add(0, it); // fara corp: se completeaza la urmatoarea cerere de lista
        });
    }

    private static NewsState.Item parse(JsonObject d) {
        NewsState.Item it = new NewsState.Item();
        it.id = lng(d, "id", 0);
        it.cat = str(d, "cat", "");
        it.title = str(d, "title", "");
        it.body = str(d, "body", "");
        it.author = str(d, "author", "");
        it.ts = lng(d, "ts", 0);
        return it;
    }

    @Override
    public String id() {
        return "news";
    }

    @Override
    public void onLogout() {
        NewsState.reset();
        NewsBanner.clear();
    }

    /** Apelat din ClientInit.register(). */
    public static void register() {
        instance = new NewsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new NewsBanner());
    }

    public static void requestList() {
        if (instance != null) instance.send("list", new JsonObject());
    }

    public static void publish(String cat, String title, String body) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("cat", cat);
        o.addProperty("title", title);
        o.addProperty("body", body);
        instance.send("publish", o);
    }

    public static void delete(long id) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        instance.send("delete", o);
    }
}
