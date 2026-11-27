package ro.solarstudios.flowcity.battlepass;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Battlepass (client): primeste starea de la server (nivel, obiective, recompense) si deschide meniul cu tasta B. */
public final class BattlepassClient extends JsonClientModule {
    private static BattlepassClient instance;
    public static KeyMapping openKey;

    static boolean enabled = true;
    static boolean running = true;
    static boolean premium;
    static boolean premiumBuyable = true;
    static boolean isAdmin;
    static double premiumCost;
    static String season = "";
    static String seasonEnd = "";
    static long xp;
    static int per = 100;
    static int max = 30;
    static int level;

    static final class Obj {
        String period;
        String label;
        String type = "";
        long n;
        long t;
        int xp;
        boolean done;
    }

    static final class Reward {
        int level;
        String free = "";
        String premium = "";
        boolean freeClaimed;
        boolean premiumClaimed;
    }

    /** Datele editorului de administrare. */
    static final class Mission {
        String id = "";
        String type = "";
        String job = "";
        long target = 1;
        int xp;
        String label = "";
    }

    static final class AdminSeason {
        String id = "";
        String name = "";
        String descr = "";
        String start = "";
        String end = "";
        int levels = 30;
        int xpPerLevel = 100;
        double premiumCost;
        boolean enabled = true;
        boolean running = true;
    }

    static final AdminSeason adminSeason = new AdminSeason();
    static final List<Reward> adminRewards = new ArrayList<>();
    static final List<Mission> adminDaily = new ArrayList<>();
    static final List<Mission> adminWeekly = new ArrayList<>();
    static boolean adminLoaded;

    static final List<Obj> objectives = new ArrayList<>();
    static final List<Reward> rewards = new ArrayList<>();

    private BattlepassClient() {
        on("state", d -> {
            enabled = bool(d, "enabled", true);
            running = bool(d, "running", true);
            premium = bool(d, "premium", false);
            premiumBuyable = bool(d, "premiumBuyable", true);
            premiumCost = num(d, "premiumCost", 0);
            isAdmin = bool(d, "admin", false);
            season = str(d, "season", "");
            seasonEnd = str(d, "seasonEnd", "");
            xp = (long) num(d, "xp", 0);
            per = Math.max(1, integer(d, "per", 100));
            max = integer(d, "max", 30);
            level = integer(d, "level", 0);
            objectives.clear();
            rewards.clear();
            if (d.has("objectives") && d.get("objectives").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("objectives")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    Obj x = new Obj();
                    x.period = str(o, "p", "daily");
                    x.label = str(o, "label", "?");
                    x.type = str(o, "type", "");
                    x.n = (long) num(o, "n", 0);
                    x.t = Math.max(1, (long) num(o, "t", 1));
                    x.xp = integer(o, "xp", 0);
                    x.done = bool(o, "done", false);
                    objectives.add(x);
                }
            }
            if (d.has("rewards") && d.get("rewards").isJsonArray()) {
                JsonArray a = d.getAsJsonArray("rewards");
                for (JsonElement e : a) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    Reward r = new Reward();
                    r.level = integer(o, "l", 0);
                    r.free = str(o, "free", "");
                    r.premium = str(o, "premium", "");
                    r.freeClaimed = bool(o, "freeClaimed", false);
                    r.premiumClaimed = bool(o, "premiumClaimed", false);
                    rewards.add(r);
                }
            }
        });
        on("admin", d -> {
            if (d.has("season") && d.get("season").isJsonObject()) {
                JsonObject o = d.getAsJsonObject("season");
                adminSeason.id = str(o, "id", "");
                adminSeason.name = str(o, "name", "");
                adminSeason.descr = str(o, "descr", "");
                adminSeason.start = str(o, "start", "");
                adminSeason.end = str(o, "end", "");
                adminSeason.levels = integer(o, "levels", 30);
                adminSeason.xpPerLevel = integer(o, "xpPerLevel", 100);
                adminSeason.premiumCost = num(o, "premiumCost", 0);
                adminSeason.enabled = bool(o, "enabled", true);
                adminSeason.running = bool(o, "running", true);
            }
            adminRewards.clear();
            if (d.has("rewards") && d.get("rewards").isJsonArray()) {
                for (JsonElement e : d.getAsJsonArray("rewards")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    Reward r = new Reward();
                    r.level = integer(o, "level", 0);
                    r.free = str(o, "free", "");
                    r.premium = str(o, "premium", "");
                    adminRewards.add(r);
                }
            }
            readMissions(d, "daily", adminDaily);
            readMissions(d, "weekly", adminWeekly);
            adminLoaded = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof BattlepassAdminScreen) ((BattlepassAdminScreen) mc.screen).refreshed();
        });
    }

    private void readMissions(JsonObject d, String key, List<Mission> out) {
        out.clear();
        if (!d.has(key) || !d.get(key).isJsonArray()) return;
        for (JsonElement e : d.getAsJsonArray(key)) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Mission m = new Mission();
            m.id = str(o, "id", "");
            m.type = str(o, "type", "");
            m.job = str(o, "job", "");
            m.target = (long) num(o, "target", 1);
            m.xp = integer(o, "xp", 0);
            m.label = str(o, "label", "");
            out.add(m);
        }
    }

    @Override
    public String id() {
        return "battlepass";
    }

    public static void register() {
        instance = new BattlepassClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        openKey = new KeyMapping("key.flowcity.battlepass", GLFW.GLFW_KEY_B, "key.categories.flowcity");
        e.register(openKey);
    }

    static void claim(int level, String track) {
        JsonObject o = new JsonObject();
        o.addProperty("level", level);
        o.addProperty("track", track);
        if (instance != null) instance.send("claim", o);
    }

    static void buyPremium() {
        if (instance != null) instance.send("buyPremium", new JsonObject());
    }

    static void claimPending() {
        if (instance != null) instance.send("claimAll", new JsonObject());
    }

    static void requestAdmin() {
        if (instance != null) instance.send("admin", new JsonObject());
    }

    /** Trimite o actiune de administrare catre server. */
    static void action(String name, JsonObject o) {
        if (instance != null) instance.send(name, o);
    }

    static void refresh() {
        if (instance != null) instance.send("open", new JsonObject());
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || openKey == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (openKey.consumeClick()) {
                if (mc.screen != null) continue;
                refresh();
                mc.setScreen(new BattlepassScreen());
            }
        }
    }
}
