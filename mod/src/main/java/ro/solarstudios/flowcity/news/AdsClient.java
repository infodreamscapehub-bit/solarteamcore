package ro.solarstudios.flowcity.news;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** Modulul client "ads" (CNN Anunturi): cereri catre server (publicare, stergere, prelungire, moderare) si primirea listelor. */
public final class AdsClient extends JsonClientModule {
    private static AdsClient instance;

    private AdsClient() {
        on("info", d -> {
            AdsState.enabled = bool(d, "enabled", true);
            AdsState.balance = d.has("balance") ? d.get("balance").getAsDouble() : 0;
            AdsState.feeStandard = dbl(d, "feeStandard");
            AdsState.feePromoted = dbl(d, "feePromoted");
            AdsState.feeExtend = dbl(d, "feeExtend");
            AdsState.promotedEnabled = bool(d, "promotedEnabled", true);
            AdsState.cooldownMs = lng(d, "cooldownMs", 0);
            AdsState.cooldownPromoMs = lng(d, "cooldownPromoMs", 0);
            AdsState.suspendedMs = lng(d, "suspendedMs", 0);
            AdsState.suspendReason = str(d, "suspendReason", "");
            AdsState.infoAt = System.currentTimeMillis();
            AdsState.titleMin = integer(d, "titleMin", 5);
            AdsState.titleMax = integer(d, "titleMax", 48);
            AdsState.descMin = integer(d, "descMin", 10);
            AdsState.descMax = integer(d, "descMax", 240);
            AdsState.maxPrice = lng(d, "maxPrice", 1_000_000_000L);
            AdsState.maxActive = integer(d, "maxActive", 3);
            AdsState.active = integer(d, "active", 0);
            AdsState.moderation = bool(d, "moderation", false);
            AdsState.extendAllowed = bool(d, "extendAllowed", true);
            AdsState.isMod = bool(d, "isMod", false);
            AdsState.phone = str(d, "phone", "");
            AdsState.requireSim = bool(d, "requireSim", true);
            AdsState.defaultDuration = integer(d, "defaultDuration", 24);
            AdsState.editAllowed = bool(d, "editAllowed", true);
            AdsState.feeEdit = dbl(d, "feeEdit");
            AdsState.editMax = integer(d, "editMax", 3);
            AdsState.promoteAllowed = bool(d, "promoteAllowed", true);
            AdsState.feePromote = dbl(d, "feePromote");
            AdsState.isAdmin = bool(d, "isAdmin", false);
            AdsState.cooldownMin = integer(d, "cooldownMin", 30);
            AdsState.extendHours = integer(d, "extendHours", 24);
            AdsState.durations.clear();
            if (d.has("durations")) for (JsonElement e : d.getAsJsonArray("durations")) AdsState.durations.add(e.getAsInt());
            AdsState.cats.clear();
            if (d.has("cats")) {
                for (JsonElement e : d.getAsJsonArray("cats")) {
                    JsonObject o = e.getAsJsonObject();
                    AdsState.Cat c = new AdsState.Cat();
                    c.id = str(o, "id", "");
                    c.label = str(o, "label", c.id);
                    c.price = bool(o, "price", false);
                    c.color = hex(str(o, "color", "#A0AEC0"));
                    c.icon = str(o, "icon", "puncte");
                    AdsState.cats.add(c);
                }
            }
            AdsState.loaded = true;
        });
        on("list", d -> {
            fill(AdsState.items, d, "items");
            fill(AdsState.own, d, "own");
            fill(AdsState.pending, d, "pending");
        });
        on("all", d -> fill(AdsState.all, d, "items"));
        on("res", d -> {
            AdsState.lastOk = bool(d, "ok", false);
            AdsState.lastMsg = str(d, "msg", "");
            AdsState.lastMsgAt = System.currentTimeMillis();
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof AdsScreen)) NotifyToasts.add(AdsState.lastOk ? "OK" : "BAD", "Anunturi", AdsState.lastMsg);
            requestInfo();
        });
        on("history", d -> {
            AdsState.history.clear();
            if (d.has("items")) {
                for (JsonElement e : d.getAsJsonArray("items")) {
                    JsonObject o = e.getAsJsonObject();
                    AdsState.Log l = new AdsState.Log();
                    l.ts = lng(o, "ts", 0);
                    l.actor = str(o, "actor", "");
                    l.action = str(o, "action", "");
                    l.id = lng(o, "id", 0);
                    l.detail = str(o, "detail", "");
                    AdsState.history.add(l);
                }
            }
        });
    }

    private static int hex(String s) {
        try {
            return 0xFF000000 | Integer.parseInt(s.replace("#", "").trim(), 16);
        } catch (Exception e) {
            return 0xFFA0AEC0;
        }
    }

    private static double dbl(JsonObject d, String k) {
        return d.has(k) ? d.get(k).getAsDouble() : 0;
    }

    private static void fill(java.util.List<AdsState.Ad> out, JsonObject d, String key) {
        out.clear();
        if (!d.has(key)) return;
        for (JsonElement e : d.getAsJsonArray(key)) {
            JsonObject o = e.getAsJsonObject();
            AdsState.Ad a = new AdsState.Ad();
            a.id = lng(o, "id", 0);
            a.cat = str(o, "cat", "");
            a.title = str(o, "title", "");
            a.body = str(o, "body", "");
            a.price = lng(o, "price", -1);
            a.phone = str(o, "phone", "");
            a.author = str(o, "author", "");
            a.uuid = str(o, "uuid", "");
            a.promoted = bool(o, "promoted", false);
            a.status = str(o, "status", "");
            a.created = lng(o, "created", 0);
            a.expires = lng(o, "expires", 0);
            a.own = bool(o, "own", false);
            a.extendsLeft = integer(o, "extendsLeft", 0);
            a.reason = str(o, "reason", "");
            a.catLabel = str(o, "catLabel", "");
            a.edits = integer(o, "edits", 0);
            a.editsLeft = integer(o, "editsLeft", 0);
            a.fee = dbl(o, "fee");
            out.add(a);
        }
    }

    @Override
    public String id() {
        return "ads";
    }

    @Override
    public void onLogout() {
        AdsState.reset();
    }

    public static void register() {
        instance = new AdsClient();
        ClientModules.register(instance);
    }

    private static void req(String action, JsonObject o) {
        if (instance != null) instance.send(action, o == null ? new JsonObject() : o);
    }

    public static void requestInfo() {
        req("info", null);
    }

    public static void requestList() {
        req("list", null);
    }

    public static void requestHistory() {
        req("history", null);
    }

    public static void publish(String cat, String title, String body, long price, int duration, boolean promoted) {
        JsonObject o = new JsonObject();
        o.addProperty("cat", cat);
        o.addProperty("title", title);
        o.addProperty("body", body);
        o.addProperty("price", price);
        o.addProperty("duration", duration);
        o.addProperty("promoted", promoted);
        req("publish", o);
    }

    public static void delete(long id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        req("delete", o);
    }

    public static void extend(long id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        req("extend", o);
    }

    public static void mod(long id, String action, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("action", action);
        o.addProperty("reason", reason);
        req("mod", o);
    }

    public static void suspend(long adId, long minutes, String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("id", adId);
        o.addProperty("minutes", minutes);
        o.addProperty("reason", reason);
        req("suspend", o);
    }

    public static void requestAll() {
        req("all", null);
    }

    public static void edit(long id, String title, String body, long price) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("body", body);
        o.addProperty("price", price);
        req("edit", o);
    }

    public static void promote(long id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        req("promote", o);
    }

    public static void cfgSet(String key, double value) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("value", value);
        req("cfg.set", o);
    }
}
