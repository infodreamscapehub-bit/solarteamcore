package ro.solarstudios.flowcity.wheel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "wheel": primeste premiile si rezultatul spin-ului (acordat deja de server). */
public final class WheelClient extends JsonClientModule {
    private static WheelClient instance;

    private WheelClient() {
        on("info", d -> {
            WheelState.prizes.clear();
            WheelState.recent.clear();
            if (d.has("prizes") && d.get("prizes").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("prizes")) {
                    if (!e.isJsonObject()) continue;
                    WheelState.Prize p = new WheelState.Prize();
                    p.name = str(e.getAsJsonObject(), "name", "?");
                    p.color = 0xFF000000 | integer(e.getAsJsonObject(), "color", 0x22D3EE);
                    WheelState.prizes.add(p);
                }
            }
            if (d.has("recent") && d.get("recent").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("recent")) if (e.isJsonObject()) WheelState.recent.add(str(e.getAsJsonObject(), "name", ""));
            }
            WheelState.cooldownMs = lng(d, "cooldownMs", 0);
            WheelState.cooldownAt = System.currentTimeMillis();
            WheelState.animSeconds = Math.max(3, integer(d, "anim", 6));
        });
        on("result", d -> {
            WheelState.resultIndex = integer(d, "index", -1);
            WheelState.resultName = str(d, "name", "");
            WheelState.resultDetail = str(d, "detail", "");
            WheelState.cooldownMs = lng(d, "cooldownMs", 0);
            WheelState.cooldownAt = System.currentTimeMillis();
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof WheelScreen)) mc.setScreen(new WheelScreen());
            if (mc.screen instanceof WheelScreen) ((WheelScreen) mc.screen).startSpin();
        });
        on("msg", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FlowScreen) ((FlowScreen) mc.screen).toast(str(d, "text", ""), !bool(d, "ok", true));
        });
        on("admin", d -> {
            WheelState.admin.clear();
            WheelState.adminMax = Math.max(2, integer(d, "max", 16));
            if (d.has("prizes") && d.get("prizes").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("prizes")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    WheelState.AdminPrize p = new WheelState.AdminPrize();
                    p.id = lng(o, "id", -1);
                    p.name = str(o, "name", "?");
                    p.type = str(o, "type", "item");
                    p.amount = o.has("amount") ? o.get("amount").getAsDouble() : 0;
                    p.material = str(o, "material", "");
                    p.weight = integer(o, "weight", 1);
                    p.color = str(o, "color", "#22D3EE");
                    p.announce = bool(o, "announce", false);
                    p.pct = o.has("pct") ? o.get("pct").getAsDouble() : 0;
                    WheelState.admin.add(p);
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof WheelAdminScreen) ((WheelAdminScreen) mc.screen).refreshed();
        });
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if ("admin".equals(str(d, "screen", ""))) {
                if (!(mc.screen instanceof WheelAdminScreen)) mc.setScreen(new WheelAdminScreen());
                return;
            }
            if (!(mc.screen instanceof WheelScreen)) mc.setScreen(new WheelScreen());
        });
    }

    @Override
    public String id() {
        return "wheel";
    }

    @Override
    public void onLogout() {
        WheelState.reset();
    }

    public static void register() {
        instance = new WheelClient();
        ClientModules.register(instance);
    }

    public static void requestInfo() {
        if (instance != null) instance.send("info", new JsonObject());
    }

    public static void sendAction(String action, JsonObject o) {
        if (instance != null) instance.send(action, o);
    }

    public static void spin() {
        if (instance != null) instance.send("spin", new JsonObject());
    }
}
