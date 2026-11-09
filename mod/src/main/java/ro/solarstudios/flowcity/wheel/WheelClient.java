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
            if (!(mc.currentScreen instanceof WheelScreen)) mc.displayGuiScreen(new WheelScreen());
            if (mc.currentScreen instanceof WheelScreen) ((WheelScreen) mc.currentScreen).startSpin();
        });
        on("msg", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.currentScreen instanceof FlowScreen) ((FlowScreen) mc.currentScreen).toast(str(d, "text", ""), !bool(d, "ok", true));
        });
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.currentScreen instanceof WheelScreen)) mc.displayGuiScreen(new WheelScreen());
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

    public static void spin() {
        if (instance != null) instance.send("spin", new JsonObject());
    }
}
