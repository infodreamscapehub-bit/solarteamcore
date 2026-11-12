package ro.solarstudios.flowcity.client.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Canalul "ids": serverul trimite ID-urile jucatorilor online; clientul le afiseaza deasupra capului (#ID Nume).
 * Tot de aici pleaca cererea de schimbare a ID-ului din panoul eAdmin.
 */
public final class IdsClient extends JsonClientModule {
    private static IdsClient instance;
    private static final Map<UUID, Integer> IDS = new ConcurrentHashMap<>();
    private static volatile boolean overhead = true;

    private IdsClient() {
        on("map", d -> {
            IDS.clear();
            overhead = bool(d, "enabled", true);
            if (d.has("ids") && d.get("ids").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : d.getAsJsonObject("ids").entrySet()) put(e.getKey(), e.getValue().getAsInt());
            }
        });
        on("upd", d -> put(str(d, "uuid", ""), integer(d, "id", 0)));
    }

    private static void put(String uuid, int id) {
        try {
            UUID u = UUID.fromString(uuid);
            if (id > 0) IDS.put(u, id);
            else IDS.remove(u);
        } catch (IllegalArgumentException ignored) {
            // uuid invalid
        }
    }

    @Override
    public String id() {
        return "ids";
    }

    @Override
    public void onLogout() {
        IDS.clear();
    }

    /** Apelat din ClientInit.register(). */
    public static void register() {
        instance = new IdsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Nameplates());
    }

    public static void set(String uuid, int id, boolean swap) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("id", id);
        o.addProperty("swap", swap);
        instance.send("set", o);
    }

    /** Adauga "#ID " in fata numelui afisat deasupra capului altor jucatori. */
    public static final class Nameplates {
        @SubscribeEvent
        public void onNameplate(RenderNameTagEvent e) {
            if (!overhead) return;
            Entity en = e.getEntity();
            if (!(en instanceof Player)) return;
            Integer id = IDS.get(en.getUUID());
            if (id == null) return;
            e.setContent(Component.literal("#" + id + " ").withStyle(ChatFormatting.AQUA).append(e.getContent()));
        }
    }
}
