package ro.solarstudios.flowcity.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "notify": toast-uri la primire, istoric, contor de necitite. */
public final class NotifyClient extends JsonClientModule {
    private static NotifyClient instance;

    private NotifyClient() {
        on("push", d -> {
            NotifyState.Item it = parse(d);
            NotifyState.history.add(0, it);
            while (NotifyState.history.size() > 100) NotifyState.history.remove(NotifyState.history.size() - 1);
            NotifyState.unread = Math.max(NotifyState.unread + 1, integer(d, "unread", 0));
            NotifyToasts.add(it.type, it.title, it.text);
        });
        on("unread", d -> {
            NotifyState.unread = integer(d, "n", 0);
            if (NotifyState.unread > 0 && !NotifyState.announced) {
                NotifyToasts.add("INFO", "Notificari", "Ai " + NotifyState.unread + " necitite. Scrie /notificari");
            }
            NotifyState.announced = true;
        });
        on("history", d -> {
            NotifyState.history.clear();
            if (d.has("items") && d.get("items").isJsonArray()) {
                JsonArray arr = d.getAsJsonArray("items");
                for (JsonElement e : arr) if (e.isJsonObject()) NotifyState.history.add(parse(e.getAsJsonObject()));
            }
            NotifyState.unread = 0;
            Minecraft mc = Minecraft.getInstance();
            if (bool(d, "open", false) && !(mc.currentScreen instanceof NotifyScreen)) mc.displayGuiScreen(new NotifyScreen());
        });
    }

    private static NotifyState.Item parse(JsonObject d) {
        NotifyState.Item it = new NotifyState.Item();
        it.id = lng(d, "id", 0);
        it.type = str(d, "t", "INFO");
        it.title = str(d, "title", "");
        it.text = str(d, "text", "");
        it.ts = lng(d, "ts", 0);
        it.read = bool(d, "read", false);
        return it;
    }

    @Override
    public String id() {
        return "notify";
    }

    @Override
    public void onLogout() {
        NotifyState.reset();
        NotifyToasts.clear();
    }

    /** Apelat din ClientInit.register(). */
    public static void register() {
        instance = new NotifyClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new NotifyToasts());
    }

    public static void requestHistory(boolean open) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("open", open);
        instance.send("history", o);
    }

    public static void clearAll() {
        if (instance == null) return;
        instance.send("clear", new JsonObject());
        NotifyState.history.clear();
        NotifyState.unread = 0;
    }
}
