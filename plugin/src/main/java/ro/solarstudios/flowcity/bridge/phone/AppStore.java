package ro.solarstudios.flowcity.bridge.phone;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;

/**
 * App Store-ul telefonului (server): ce aplicatii are fiecare jucator instalate, in ce ordine pe ecranul principal, ce a
 * cumparat si notitele aplicatiei Note. Totul se valideaza aici (ID-uri cunoscute, aplicatii oprite din config, plata prin
 * economia serverului), clientul doar afiseaza. Tabela: core_phone_apps.
 */
public final class AppStore {
    /** Aplicatiile de sistem: mereu instalate, nu se pot sterge. */
    public static final List<String> CORE = Collections.unmodifiableList(Arrays.asList("phone", "messages", "contacts", "store", "settings", "sim"));
    /** Aplicatiile din magazin (doar cele care functioneaza real in mod). */
    public static final List<String> OPTIONAL = Collections.unmodifiableList(Arrays.asList(
        "cnn", "ads", "bank", "notify", "gps", "jobs", "wheel", "calc", "notes", "clock", "weather"));
    private static final List<String> DEFAULT_EXTRA = Arrays.asList("cnn", "bank", "notify", "gps", "jobs", "ads");

    public static final int MAX_NOTES = 30;
    public static final int MAX_NOTE = 400;

    public static final class Data {
        public final List<String> apps = new ArrayList<>();
        public final Set<String> owned = new LinkedHashSet<>();
        public JsonArray notes = new JsonArray();
    }

    private final FlowCityBridge plugin;
    private final Map<UUID, Data> cache = new HashMap<>();

    public AppStore(FlowCityBridge plugin) {
        this.plugin = plugin;
        try {
            plugin.db().async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_phone_apps (uuid TEXT PRIMARY KEY, apps TEXT NOT NULL, owned TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '[]')");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("App Store: nu pot crea tabela: " + e);
        }
    }

    // ------------------------------------------------------------------ config

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("phone.store");
    }

    public double price(String id) {
        ConfigurationSection c = cfg();
        return c == null ? 0 : Math.max(0, c.getDouble("prices." + id, 0));
    }

    public boolean enabled(String id) {
        if (CORE.contains(id)) return true;
        if (!OPTIONAL.contains(id)) return false;
        ConfigurationSection c = cfg();
        return c == null || !c.getStringList("disabled").contains(id);
    }

    private List<String> defaults() {
        ConfigurationSection c = cfg();
        List<String> l = c == null ? DEFAULT_EXTRA : c.getStringList("default-installed");
        if (c != null && !c.isList("default-installed")) l = DEFAULT_EXTRA;
        return l;
    }

    // ------------------------------------------------------------------ date

    public Data cached(UUID u) {
        return cache.get(u);
    }

    public void forget(UUID u) {
        cache.remove(u);
    }

    /** Incarca (async) datele jucatorului; callback pe thread-ul principal. Jucatorii noi primesc aplicatiile implicite. */
    public void load(UUID u, Consumer<Data> done) {
        Data have = cache.get(u);
        if (have != null) {
            done.accept(have);
            return;
        }
        final String key = u.toString();
        plugin.db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT apps, owned, notes FROM core_phone_apps WHERE uuid=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new String[] {rs.getString(1), rs.getString(2), rs.getString(3)};
                }
            }
            return null;
        }).whenComplete((r, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            Data d = new Data();
            if (r != null) {
                for (String s : r[0].split(",")) if (!s.isEmpty() && (CORE.contains(s) || OPTIONAL.contains(s)) && !d.apps.contains(s)) d.apps.add(s);
                for (String s : r[1].split(",")) if (!s.isEmpty()) d.owned.add(s);
                try {
                    JsonElement e = new JsonParser().parse(r[2] == null ? "[]" : r[2]);
                    if (e.isJsonArray()) d.notes = e.getAsJsonArray();
                } catch (RuntimeException ignored) {
                    // notite corupte: se pornesc goale
                }
            } else {
                d.apps.addAll(Arrays.asList("phone", "messages", "contacts", "store"));
                for (String s : defaults()) if (OPTIONAL.contains(s) && !d.apps.contains(s)) d.apps.add(s);
                d.apps.add("settings");
                d.apps.add("sim");
            }
            for (String s : CORE) if (!d.apps.contains(s)) d.apps.add(s);
            cache.put(u, d);
            if (r == null) save(u, d);
            done.accept(d);
        }));
    }

    public void save(UUID u, Data d) {
        final String key = u.toString();
        final String apps = String.join(",", d.apps);
        final String owned = String.join(",", d.owned);
        final String notes = d.notes.toString();
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_phone_apps(uuid, apps, owned, notes) VALUES (?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET apps=excluded.apps, owned=excluded.owned, notes=excluded.notes")) {
                ps.setString(1, key);
                ps.setString(2, apps);
                ps.setString(3, owned);
                ps.setString(4, notes);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Pachetul trimis clientului: aplicatii instalate (in ordine), catalog cu pret/stare, cumparate. */
    public JsonObject json(UUID u, Data d) {
        JsonObject o = new JsonObject();
        JsonArray apps = new JsonArray();
        for (String s : d.apps) if (enabled(s)) apps.add(s);
        o.add("apps", apps);
        JsonArray owned = new JsonArray();
        for (String s : d.owned) owned.add(s);
        o.add("owned", owned);
        JsonArray cat = new JsonArray();
        for (String s : OPTIONAL) {
            JsonObject a = new JsonObject();
            a.addProperty("id", s);
            a.addProperty("price", price(s));
            a.addProperty("enabled", enabled(s));
            cat.add(a);
        }
        o.add("catalog", cat);
        o.addProperty("balance", plugin.money().balance(u));
        return o;
    }

    // ------------------------------------------------------------------ operatii (thread-ul principal, datele deja incarcate)

    /** Instaleaza; intoarce null daca a reusit, altfel mesajul de eroare. Banii se retrag o singura data, dupa toate verificarile. */
    public String install(UUID u, Data d, String id) {
        if (!OPTIONAL.contains(id)) return "Aplicatie necunoscuta.";
        if (!enabled(id)) return "Aplicatia nu este disponibila pe acest server.";
        if (d.apps.contains(id)) return "Aplicatia este deja instalata.";
        if (d.apps.size() >= 60) return "Prea multe aplicatii instalate.";
        double price = price(id);
        if (price > 0 && !d.owned.contains(id)) {
            if (!plugin.money().has(u, price)) return "Fonduri insuficiente (" + String.format("%,.0f", price) + "$).";
            if (!plugin.money().withdraw(u, price)) return "Plata a esuat. Nu s-au retras bani.";
        }
        d.owned.add(id);
        d.apps.add(id);
        save(u, d);
        return null;
    }

    public String uninstall(UUID u, Data d, String id) {
        if (CORE.contains(id)) return "Aplicatiile de sistem nu se pot sterge.";
        if (!d.apps.remove(id)) return "Aplicatia nu este instalata.";
        save(u, d);
        return null;
    }

    /** Noua ordine e acceptata doar daca e o permutare a aplicatiilor instalate. */
    public boolean reorder(UUID u, Data d, JsonArray order) {
        List<String> n = new ArrayList<>();
        for (JsonElement e : order) {
            if (!e.isJsonPrimitive()) return false;
            String s = e.getAsString();
            if (n.contains(s) || !d.apps.contains(s)) return false;
            n.add(s);
        }
        // aplicatiile oprite din config nu ajung la client; se pastreaza la coada
        for (String s : d.apps) if (!n.contains(s)) n.add(s);
        if (n.size() != d.apps.size()) return false;
        d.apps.clear();
        d.apps.addAll(n);
        save(u, d);
        return true;
    }

    /** Notitele: max 30, max 400 caractere, doar text. */
    public void saveNotes(UUID u, Data d, JsonArray in) {
        JsonArray out = new JsonArray();
        for (JsonElement e : in) {
            if (out.size() >= MAX_NOTES || !e.isJsonObject()) continue;
            JsonObject n = e.getAsJsonObject();
            String t = n.has("t") && n.get("t").isJsonPrimitive() ? n.get("t").getAsString() : "";
            t = t.replaceAll("[\\p{Cntrl}§]", " ").trim();
            if (t.isEmpty()) continue;
            if (t.length() > MAX_NOTE) t = t.substring(0, MAX_NOTE);
            long ts = n.has("ts") && n.get("ts").isJsonPrimitive() ? n.get("ts").getAsLong() : System.currentTimeMillis();
            JsonObject o = new JsonObject();
            o.addProperty("t", t);
            o.addProperty("ts", ts);
            out.add(o);
        }
        d.notes = out;
        save(u, d);
    }
}
