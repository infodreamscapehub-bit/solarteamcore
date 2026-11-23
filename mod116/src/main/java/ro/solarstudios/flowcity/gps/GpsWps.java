package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Waypoint-urile jucatorului (personale) si cele partajate (create de admini), primite de la server in {@code wp.list}.
 * Serverul e autoritar (validare, limite, permisiuni); aici doar le retinem si trimitem cererile {@code wp.set} / {@code wp.del}.
 */
public final class GpsWps {
    private GpsWps() {}

    public static final class Wp {
        public String id = "";
        public String name = "";
        public double x;
        public double z;
        public String dim = "minecraft:overworld";
        public int color = 0xFFFFC857;
        public String icon = "pin";
        public boolean shared;
    }

    public static final List<Wp> mine = new ArrayList<>();
    public static final List<Wp> shared = new ArrayList<>();
    public static int max = 50;
    public static boolean loaded;
    public static String err = "";
    public static long errAt;

    public static void clear() {
        mine.clear();
        shared.clear();
        loaded = false;
        err = "";
    }

    private static void read(JsonArray a, List<Wp> out, boolean sh) {
        out.clear();
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            Wp w = new Wp();
            w.id = GpsClient.str(o, "id", "");
            w.name = GpsClient.str(o, "name", "");
            w.x = GpsClient.num(o, "x", 0);
            w.z = GpsClient.num(o, "z", 0);
            w.dim = GpsClient.str(o, "dim", "minecraft:overworld");
            w.color = GpsData.color(GpsClient.str(o, "color", "#FFC857"), 0xFFFFC857);
            w.icon = GpsClient.str(o, "icon", "pin");
            w.shared = sh;
            if (!w.id.isEmpty()) out.add(w);
        }
    }

    public static void onList(JsonObject d) {
        if (d.has("mine") && d.get("mine").isJsonArray()) read(d.getAsJsonArray("mine"), mine, false);
        if (d.has("shared") && d.get("shared").isJsonArray()) read(d.getAsJsonArray("shared"), shared, true);
        max = (int) GpsClient.num(d, "max", 50);
        loaded = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof GpsScreen) ((GpsScreen) mc.screen).dataChanged();
    }

    public static void onErr(JsonObject d) {
        err = GpsClient.str(d, "msg", "Eroare waypoint.");
        errAt = System.currentTimeMillis();
    }

    /** Creeaza (id gol) sau modifica un waypoint. {@code color} ARGB. */
    public static void save(String id, String name, double x, double z, String dim, int color, boolean sh) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id == null ? "" : id);
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("dim", dim);
        o.addProperty("color", String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF));
        o.addProperty("icon", "pin");
        o.addProperty("shared", sh);
        GpsClient.sendWp("wp.set", o);
    }

    public static void delete(Wp w) {
        JsonObject o = new JsonObject();
        o.addProperty("id", w.id);
        o.addProperty("shared", w.shared);
        GpsClient.sendWp("wp.del", o);
    }

    public static List<Wp> all() {
        List<Wp> l = new ArrayList<>(shared);
        l.addAll(mine);
        return l;
    }
}
