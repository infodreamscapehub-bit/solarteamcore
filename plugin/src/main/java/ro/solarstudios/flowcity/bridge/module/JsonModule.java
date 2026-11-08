package ro.solarstudios.flowcity.bridge.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.net.Wire;

/**
 * Baza pentru module care comunica cu clientul prin JSON (mai simplu decat pachete binare).
 *
 * <pre>
 *   public final class HypeModule extends JsonModule {
 *     public String id() { return "hype"; }
 *     protected void onEnable() {
 *       on("atm.deposit", (player, json) -> { double a = num(json, "amount", 0); ... send(player, "atm.result", reply); });
 *     }
 *     public void onClientReady(Player p) { send(p, "state", buildState(p)); }
 *   }
 * </pre>
 * Limite: client -> server ~32 KB (gzip automat), server -> client ~900 KB. Handler-ele ruleaza pe thread-ul principal.
 */
public abstract class JsonModule implements BridgeModule {
    private static final int GZIP_FROM = 512;
    private static final int MAX_IN = 30 * 1024;
    private static final int MAX_IN_UNPACKED = 256 * 1024;

    protected FlowCityBridge plugin;
    private final Map<String, BiConsumer<Player, JsonObject>> handlers = new HashMap<>();

    /** Apelat dupa ce {@link #plugin} e setat: inregistreaza handler-e, comenzi, listeners, tabele. */
    protected abstract void onEnable();

    @Override
    public final void enable(FlowCityBridge plugin) {
        this.plugin = plugin;
        onEnable();
    }

    @Override
    public void disable() {}

    /** Inregistreaza handler pentru o actiune venita de la client. */
    protected final void on(String action, BiConsumer<Player, JsonObject> handler) {
        handlers.put(action, handler);
    }

    @Override
    public final void onMessage(Player player, String action, Wire.In in) {
        BiConsumer<Player, JsonObject> h = handlers.get(action);
        if (h == null) return;
        try {
            int flags = in.u8();
            byte[] data = in.byteArray(MAX_IN);
            if ((flags & 1) != 0) data = Gz.unpack(data, MAX_IN_UNPACKED);
            JsonElement e = new JsonParser().parse(new String(data, StandardCharsets.UTF_8));
            h.accept(player, e.isJsonObject() ? e.getAsJsonObject() : new JsonObject());
        } catch (IOException | RuntimeException ex) {
            plugin.getLogger().warning("Mesaj " + id() + "/" + action + " invalid de la " + player.getName() + ": " + ex);
        }
    }

    /** Trimite JSON unui jucator (ignorat daca jucatorul nu are modul). */
    public final void send(Player p, String action, JsonObject data) {
        if (p == null || !p.isOnline() || !plugin.channel().hasMod(p)) return;
        try {
            byte[] bytes = (data == null ? "{}" : data.toString()).getBytes(StandardCharsets.UTF_8);
            int flags = 0;
            if (bytes.length >= GZIP_FROM) {
                bytes = Gz.pack(bytes);
                flags = 1;
            }
            plugin.channel().sendModule(p, id(), action, new Wire.Out().u8(flags).byteArray(bytes));
        } catch (IOException e) {
            plugin.getLogger().warning("Nu pot trimite " + id() + "/" + action + ": " + e);
        }
    }

    /** Trimite tuturor jucatorilor online cu modul. */
    public final void broadcast(String action, JsonObject data) {
        for (Player p : Bukkit.getOnlinePlayers()) send(p, action, data);
    }

    // ------------------------------------------------------------------ helperi pentru citirea JSON-ului primit

    protected static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    protected static double num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    protected static int integer(JsonObject o, String key, int def) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, num(o, key, def)));
    }

    protected static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
    }
}
