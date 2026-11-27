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
        public double y;
        public double z;
        public String dim = "minecraft:overworld";
        public int color = 0xFFFFC857;
        public String icon = "pin";
        /** Eticheta scurta de pe marker (initialele din fisierele de waypoint-uri). */
        public String label = "";
        /** Dezactivat: ramane salvat, dar nu apare pe harta si pe minimap. */
        public boolean enabled = true;
        public boolean shared;
    }

    /** Paleta de 16 culori, aceeasi cu indicii din fisierele de waypoint-uri. */
    public static final int[] PALETTE = {
        0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
        0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF
    };

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
            w.y = GpsClient.num(o, "y", 0);
            w.z = GpsClient.num(o, "z", 0);
            w.dim = GpsClient.str(o, "dim", "minecraft:overworld");
            w.color = GpsData.color(GpsClient.str(o, "color", "#FFC857"), 0xFFFFC857);
            w.icon = GpsClient.str(o, "icon", "pin");
            w.label = GpsClient.str(o, "label", "");
            w.enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
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

    /** Creeaza (id gol) sau modifica un waypoint, cu toate campurile. {@code color} ARGB. */
    public static void save(String id, String name, double x, double y, double z, String dim, int color,
                            String icon, String label, boolean enabled, boolean sh) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id == null ? "" : id);
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("dim", dim);
        o.addProperty("color", String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF));
        o.addProperty("icon", icon == null || icon.isEmpty() ? "pin" : icon);
        o.addProperty("label", label == null ? "" : label);
        o.addProperty("enabled", enabled);
        o.addProperty("shared", sh);
        GpsClient.sendWp("wp.set", o);
    }

    /** Varianta scurta: waypoint nou, cu iconita implicita si activ. */
    public static void save(String id, String name, double x, double z, String dim, int color, boolean sh) {
        save(id, name, x, 0d, z, dim, color, "pin", "", true, sh);
    }

    /** Salveaza din nou un waypoint existent, cu modificarile facute pe obiect. */
    public static void save(Wp w) {
        save(w.id, w.name, w.x, w.y, w.z, w.dim, w.color, w.icon, w.label, w.enabled, w.shared);
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
