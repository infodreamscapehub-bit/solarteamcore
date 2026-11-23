package ro.solarstudios.flowcity.bridge.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Waypoint-uri personale (cate un fisier per jucator in {@code gps/waypoints/<uuid>.json}) si waypoint-uri partajate
 * (gps/waypoints-shared.json, create doar de admini). Serverul valideaza tot (nume, culoare, iconita, coordonate, limita).
 * Clientul primeste lista completa in {@code wp.list} dupa fiecare schimbare.
 */
final class GpsWaypoints {
    private static final int MAX_NAME = 32;

    static final class Wp {
        String id = "";
        String name = "";
        double x;
        double z;
        String dim = GpsStore.OVERWORLD;
        String color = "#FFC857";
        String icon = "pin";
    }

    private final GpsModule mod;
    private final Logger log;
    private final File dir;
    private final File sharedFile;
    private final int maxPersonal;
    private final int maxShared;
    private final Map<UUID, List<Wp>> mine = new HashMap<>();
    private final Map<UUID, Long> lastOp = new HashMap<>();
    private List<Wp> shared = new ArrayList<>();

    GpsWaypoints(GpsModule mod, Logger log, File gpsDir, YamlConfiguration cfg) {
        this.mod = mod;
        this.log = log;
        this.dir = new File(gpsDir, "waypoints");
        this.sharedFile = new File(gpsDir, "waypoints-shared.json");
        this.maxPersonal = Math.max(1, Math.min(500, cfg.getInt("waypoints.max-personal", 50)));
        this.maxShared = Math.max(0, Math.min(500, cfg.getInt("waypoints.max-shared", 100)));
        shared = readFile(sharedFile);
    }

    // ------------------------------------------------------------------ disc

    private List<Wp> readFile(File f) {
        List<Wp> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try {
            JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (!e.isJsonArray()) return out;
            for (JsonElement el : e.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Wp w = new Wp();
                w.id = o.has("id") ? o.get("id").getAsString() : "";
                w.name = o.has("name") ? o.get("name").getAsString() : "";
                w.x = o.has("x") ? o.get("x").getAsDouble() : 0;
                w.z = o.has("z") ? o.get("z").getAsDouble() : 0;
                w.dim = o.has("dim") ? o.get("dim").getAsString() : GpsStore.OVERWORLD;
                w.color = o.has("color") ? o.get("color").getAsString() : "#FFC857";
                w.icon = o.has("icon") ? o.get("icon").getAsString() : "pin";
                if (!w.id.isEmpty() && !w.name.isEmpty()) out.add(w);
            }
        } catch (IOException | RuntimeException ex) {
            log.warning("[GPS] Nu pot citi " + f.getName() + ": " + ex);
        }
        return out;
    }

    private void writeFile(File f, List<Wp> list) {
        JsonArray a = new JsonArray();
        for (Wp w : list) a.add(toJson(w));
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warning("[GPS] Nu pot salva " + f.getName() + ": " + e);
        }
    }

    private static JsonObject toJson(Wp w) {
        JsonObject o = new JsonObject();
        o.addProperty("id", w.id);
        o.addProperty("name", w.name);
        o.addProperty("x", w.x);
        o.addProperty("z", w.z);
        o.addProperty("dim", w.dim);
        o.addProperty("color", w.color);
        o.addProperty("icon", w.icon);
        return o;
    }

    private List<Wp> of(Player p) {
        List<Wp> l = mine.get(p.getUniqueId());
        if (l == null) {
            l = readFile(new File(dir, p.getUniqueId() + ".json"));
            mine.put(p.getUniqueId(), l);
        }
        return l;
    }

    // ------------------------------------------------------------------ protocol

    void onQuit(Player p) {
        mine.remove(p.getUniqueId());
        lastOp.remove(p.getUniqueId());
    }

    void sendList(Player p) {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (Wp w : of(p)) a.add(toJson(w));
        o.add("mine", a);
        JsonArray s = new JsonArray();
        for (Wp w : shared) s.add(toJson(w));
        o.add("shared", s);
        o.addProperty("max", maxPersonal);
        mod.send(p, "wp.list", o);
    }

    private boolean throttled(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastOp.get(p.getUniqueId());
        if (last != null && now - last < 120L) return true;
        lastOp.put(p.getUniqueId(), now);
        return false;
    }

    private void err(Player p, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("msg", msg);
        mod.send(p, "wp.err", o);
    }

    private static boolean coord(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= 30000000d;
    }

    /** Creeaza sau modifica un waypoint ({@code shared:true} doar pentru admini). */
    void set(Player p, JsonObject j, boolean admin) {
        if (throttled(p)) return;
        boolean sh = j.has("shared") && j.get("shared").getAsBoolean();
        if (sh && !admin) {
            err(p, "Doar adminii pot crea waypoint-uri partajate.");
            return;
        }
        String name = GpsStore.cut(j.has("name") ? j.get("name").getAsString().trim() : "", MAX_NAME);
        if (name.isEmpty()) {
            err(p, "Nume invalid.");
            return;
        }
        double x = j.has("x") ? j.get("x").getAsDouble() : Double.NaN;
        double z = j.has("z") ? j.get("z").getAsDouble() : Double.NaN;
        if (!coord(x) || !coord(z)) {
            err(p, "Coordonate invalide.");
            return;
        }
        String color = j.has("color") ? j.get("color").getAsString() : "#FFC857";
        if (!color.matches("#[0-9A-Fa-f]{6}")) {
            err(p, "Culoare invalidă.");
            return;
        }
        String icon = j.has("icon") ? j.get("icon").getAsString() : "pin";
        if (!icon.matches("[a-z0-9_]{1,32}")) {
            err(p, "Iconiță invalidă.");
            return;
        }
        String dim = j.has("dim") ? j.get("dim").getAsString() : GpsStore.OVERWORLD;
        if (dim.length() > 64 || !dim.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
            err(p, "Dimensiune invalidă.");
            return;
        }
        String id = j.has("id") ? j.get("id").getAsString() : "";
        if (!id.isEmpty() && !id.matches("[a-z0-9]{1,16}")) {
            err(p, "Id invalid.");
            return;
        }
        List<Wp> list = sh ? shared : of(p);
        Wp w = null;
        for (Wp e : list) if (e.id.equals(id)) w = e;
        if (w == null) {
            if (list.size() >= (sh ? maxShared : maxPersonal)) {
                err(p, "Ai atins limita de waypoint-uri (" + (sh ? maxShared : maxPersonal) + ").");
                return;
            }
            w = new Wp();
            w.id = id.isEmpty() || exists(list, id) ? newId(list) : id;
            list.add(w);
        }
        w.name = name;
        w.x = Math.round(x * 10d) / 10d;
        w.z = Math.round(z * 10d) / 10d;
        w.dim = dim;
        w.color = color.toUpperCase(Locale.ROOT);
        w.icon = icon;
        persist(p, sh);
    }

    void delete(Player p, JsonObject j, boolean admin) {
        if (throttled(p)) return;
        boolean sh = j.has("shared") && j.get("shared").getAsBoolean();
        if (sh && !admin) {
            err(p, "Doar adminii pot șterge waypoint-uri partajate.");
            return;
        }
        String id = j.has("id") ? j.get("id").getAsString() : "";
        List<Wp> list = sh ? shared : of(p);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                persist(p, sh);
                return;
            }
        }
    }

    private void persist(Player p, boolean sh) {
        if (sh) {
            writeFile(sharedFile, shared);
            for (Player o : Bukkit.getOnlinePlayers()) sendList(o);
        } else {
            writeFile(new File(dir, p.getUniqueId() + ".json"), of(p));
            sendList(p);
        }
    }

    private static boolean exists(List<Wp> l, String id) {
        for (Wp w : l) if (w.id.equals(id)) return true;
        return false;
    }

    private static String newId(List<Wp> l) {
        String id;
        do {
            id = Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()), 36);
            if (id.length() > 10) id = id.substring(0, 10);
        } while (exists(l, id));
        return id;
    }
}
