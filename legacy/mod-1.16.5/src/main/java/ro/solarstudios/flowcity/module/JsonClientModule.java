package ro.solarstudios.flowcity.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.network.PacketBuffer;
import ro.solarstudios.flowcity.client.ClientBridge;

/**
 * Baza pentru module client care vorbesc JSON cu modulul de server corespondent ({@code JsonModule} din plugin).
 *
 * <pre>
 *   public final class HypeJson extends JsonClientModule {
 *     public String id() { return "hype"; }
 *     HypeJson() { on("state", d -> HypeState.apply(d)); }
 *   }
 *   // la init: ClientModules.register(new HypeJson());
 * </pre>
 * Handler-ele ruleaza pe thread-ul principal al clientului. Limita client -> server este ~30 KB per mesaj.
 */
public abstract class JsonClientModule implements ClientModule {
    private static final int GZIP_FROM = 512;
    private static final int MAX_OUT = 30 * 1024;
    private static final int MAX_IN_UNPACKED = 1024 * 1024;

    private final Map<String, Consumer<JsonObject>> handlers = new HashMap<>();

    protected final void on(String action, Consumer<JsonObject> handler) {
        handlers.put(action, handler);
    }

    @Override
    public final void onMessage(String action, PacketBuffer buf) {
        Consumer<JsonObject> h = handlers.get(action);
        if (h == null) return;
        try {
            int flags = buf.readUnsignedByte();
            byte[] data = buf.readByteArray();
            if ((flags & 1) != 0) data = Gz.unpack(data, MAX_IN_UNPACKED);
            JsonElement e = new JsonParser().parse(new String(data, StandardCharsets.UTF_8));
            h.accept(e.isJsonObject() ? e.getAsJsonObject() : new JsonObject());
        } catch (IOException | RuntimeException ex) {
            // mesaj corupt: il ignoram
        }
    }

    /** Trimite JSON catre modulul de server. Mesajele peste ~30 KB sunt omise (trimite pe bucati). */
    public final void send(String action, JsonObject data) {
        try {
            byte[] bytes = (data == null ? "{}" : data.toString()).getBytes(StandardCharsets.UTF_8);
            final int flags;
            if (bytes.length >= GZIP_FROM) {
                bytes = Gz.pack(bytes);
                flags = 1;
            } else {
                flags = 0;
            }
            if (bytes.length > MAX_OUT) return;
            final byte[] payload = bytes;
            ClientBridge.sendModule(id(), action, b -> {
                b.writeByte(flags);
                b.writeByteArray(payload);
            });
        } catch (IOException ignored) {
            // gzip in memorie: nu esueaza practic
        }
    }

    // ------------------------------------------------------------------ helperi de citire JSON

    public static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    public static double num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def;
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public static int integer(JsonObject o, String key, int def) {
        return (int) num(o, key, def);
    }

    public static long lng(JsonObject o, String key, long def) {
        return (long) num(o, key, def);
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
    }
}
