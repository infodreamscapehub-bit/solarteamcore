package ro.solarstudios.flowcity.battlepass;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Battlepass (client): primeste starea de la server (nivel, obiective, recompense) si deschide meniul cu tasta B. */
public final class BattlepassClient extends JsonClientModule {
    private static BattlepassClient instance;
    public static KeyBinding openKey;

    static boolean enabled = true;
    static long xp;
    static int per = 100;
    static int max = 30;
    static int level;

    static final class Obj {
        String period;
        String label;
        long n;
        long t;
        int xp;
        boolean done;
    }

    static final class Reward {
        int level;
        String label;
        boolean claimed;
    }

    static final List<Obj> objectives = new ArrayList<>();
    static final List<Reward> rewards = new ArrayList<>();

    private BattlepassClient() {
        on("state", d -> {
            enabled = bool(d, "enabled", true);
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
                    r.label = str(o, "label", "");
                    r.claimed = bool(o, "claimed", false);
                    rewards.add(r);
                }
            }
        });
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
        openKey = new KeyBinding("key.flowcity.battlepass", GLFW.GLFW_KEY_B, "key.categories.flowcity");
        e.register(openKey);
    }

    static void claim(int level) {
        JsonObject o = new JsonObject();
        o.addProperty("level", level);
        if (instance != null) instance.send("claim", o);
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
