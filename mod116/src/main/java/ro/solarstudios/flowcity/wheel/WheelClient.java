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
                    p.descr = str(e.getAsJsonObject(), "descr", "");
                    p.icon = str(e.getAsJsonObject(), "icon", "");
                    p.color = 0xFF000000 | integer(e.getAsJsonObject(), "color", 0x22D3EE);
                    WheelState.prizes.add(p);
                }
            }
            if (d.has("recent") && d.get("recent").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("recent")) {
                    if (!e.isJsonObject()) continue;
                    WheelState.Won w = new WheelState.Won();
                    w.name = str(e.getAsJsonObject(), "name", "");
                    w.ts = lng(e.getAsJsonObject(), "ts", 0);
                    WheelState.recent.add(w);
                }
            }
            WheelState.cooldownMs = lng(d, "cooldownMs", 0);
            WheelState.cooldownAt = System.currentTimeMillis();
            WheelState.animSeconds = Math.max(3, integer(d, "anim", 6));
            WheelState.enabled = bool(d, "enabled", true);
            WheelState.cost = d.has("cost") ? d.get("cost").getAsDouble() : 0;
            WheelState.freeLeft = integer(d, "freeLeft", 0);
            WheelState.dayLeft = integer(d, "dayLeft", 0);
            WheelState.cash = d.has("cash") ? d.get("cash").getAsDouble() : 0;
            WheelState.totalSpins = integer(d, "total", 0);
            WheelState.claims = integer(d, "claims", 0);
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
                    p.descr = str(o, "descr", "");
                    p.type = str(o, "type", "item");
                    p.amount = o.has("amount") ? o.get("amount").getAsDouble() : 0;
                    p.material = str(o, "material", "");
                    p.icon = str(o, "icon", "");
                    p.command = str(o, "command", "");
                    p.weight = integer(o, "weight", 1);
                    p.color = str(o, "color", "#22D3EE");
                    p.announce = bool(o, "announce", false);
                    p.enabled = bool(o, "enabled", true);
                    p.pct = o.has("pct") ? o.get("pct").getAsDouble() : 0;
                    WheelState.admin.add(p);
                }
            }
            if (d.has("cfg") && d.get("cfg").isJsonObject()) {
                JsonObject c = d.getAsJsonObject("cfg");
                WheelState.cfg.enabled = bool(c, "enabled", true);
                WheelState.cfg.cooldownHours = c.has("cooldown-hours") ? c.get("cooldown-hours").getAsDouble() : 24;
                WheelState.cfg.cost = c.has("cost") ? c.get("cost").getAsDouble() : 0;
                WheelState.cfg.freeSpins = integer(c, "free-spins", 1);
                WheelState.cfg.maxPerDay = integer(c, "max-per-day", 1);
                WheelState.cfg.animSeconds = integer(c, "animation-seconds", 6);
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof WheelAdminScreen) ((WheelAdminScreen) mc.screen).refreshed();
        });
        on("stats", d -> {
            WheelState.statTop.clear();
            WheelState.statLast.clear();
            WheelState.statSpins = lng(d, "spins", 0);
            WheelState.statPlayers = lng(d, "players", 0);
            if (d.has("top") && d.get("top").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("top")) {
                    if (!e.isJsonObject()) continue;
                    WheelState.Stat s = new WheelState.Stat();
                    s.name = str(e.getAsJsonObject(), "name", "");
                    s.n = lng(e.getAsJsonObject(), "n", 0);
                    WheelState.statTop.add(s);
                }
            }
            if (d.has("last") && d.get("last").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("last")) {
                    if (!e.isJsonObject()) continue;
                    WheelState.Stat s = new WheelState.Stat();
                    s.who = str(e.getAsJsonObject(), "who", "");
                    s.name = str(e.getAsJsonObject(), "name", "");
                    s.ts = lng(e.getAsJsonObject(), "ts", 0);
                    WheelState.statLast.add(s);
                }
            }
            WheelState.statsLoaded = true;
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

    /** Cere premiile puse deoparte (inventar plin la castig). */
    public static void claim() {
        if (instance != null) instance.send("claim", new JsonObject());
    }
}
