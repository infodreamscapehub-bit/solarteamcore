package ro.solarstudios.flowcity.bridge.beginner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Jucatori INCEPATORI: serverul calculeaza timpul jucat (statistica PLAY_ONE_MINUTE, timp real, persistent), il trimite
 * clientilor (doar pentru cei sub prag) si clientul deseneaza badge-ul deasupra capului. Setarile (prag, text, culori, distanta...)
 * se editeaza din meniul grafic (permisiunea flowcity.beginner.admin); toate valorile se valideaza aici si se salveaza in beginner.yml.
 */
public final class BeginnerModule extends JsonModule {
    private static final String PERM_ADMIN = "flowcity.beginner.admin";
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final List<String> ICONS = Arrays.asList("star", "spark", "diamond", "clover", "cross", "arrow", "none");

    private YamlConfiguration cfg;
    /** Ultimul minut trimis clientilor pentru fiecare jucator (-1 = nu e incepator). */
    private final Map<UUID, Integer> sent = new HashMap<>();
    private final Map<UUID, Long> lastOp = new HashMap<>();

    @Override
    public String id() {
        return "beginner";
    }

    @Override
    protected void onEnable() {
        Commands.permission(PERM_ADMIN, "Configureaza badge-ul de incepator si verifica statutul jucatorilor din meniu", PermissionDefault.OP);
        cfg = ModConfig.load(plugin, "beginner");
        on("cfg.set", this::cfgSet);
        on("list", (p, j) -> list(p));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 20, 20L * 60);
    }

    // ------------------------------------------------------------------ calcul

    private static int playedMinutes(Player p) {
        return Math.max(0, p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200);
    }

    private double hours() {
        return Math.max(0.1, Math.min(2000.0, cfg.getDouble("hours", 10.0)));
    }

    private boolean enabled() {
        return cfg.getBoolean("enabled", true);
    }

    /** Minutele jucate daca jucatorul e incepator, altfel -1. */
    private int value(Player p) {
        int m = playedMinutes(p);
        return enabled() && m < hours() * 60.0 ? m : -1;
    }

    private JsonObject cfgJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        o.addProperty("hours", hours());
        o.addProperty("text", cfg.getString("text", "INCEPATOR"));
        o.addProperty("icon", cfg.getString("icon", "star"));
        o.addProperty("showTime", cfg.getBoolean("show-time", true));
        o.addProperty("color", cfg.getString("color", "#4DD2FF"));
        o.addProperty("timeColor", cfg.getString("time-color", "#B8C4D6"));
        o.addProperty("distance", cfg.getInt("distance", 24));
        o.addProperty("offset", cfg.getDouble("offset", 0.55));
        o.addProperty("hideWithId", cfg.getBoolean("hide-with-id", false));
        return o;
    }

    private JsonObject mapJson(boolean all) {
        JsonObject m = new JsonObject();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int v = value(p);
            Integer prev = sent.get(p.getUniqueId());
            if (all || prev == null || prev != v) {
                m.addProperty(p.getUniqueId().toString(), v);
                sent.put(p.getUniqueId(), v);
            }
        }
        JsonObject o = new JsonObject();
        o.add("p", m);
        return o;
    }

    private void tick() {
        Set<UUID> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        sent.keySet().retainAll(online);
        lastOp.keySet().retainAll(online);
        JsonObject d = mapJson(false);
        if (d.getAsJsonObject("p").size() > 0) broadcast("upd", d);
    }

    // ------------------------------------------------------------------ client

    @Override
    public void onClientReady(Player p) {
        JsonObject st = new JsonObject();
        st.addProperty("canAdmin", p.hasPermission(PERM_ADMIN));
        send(p, "state", st);
        send(p, "cfg", cfgJson());
        send(p, "map", mapJson(true));
        // ceilalti clienti afla de noul jucator
        JsonObject one = new JsonObject();
        JsonObject m = new JsonObject();
        int v = value(p);
        m.addProperty(p.getUniqueId().toString(), v);
        sent.put(p.getUniqueId(), v);
        one.add("p", m);
        broadcast("upd", one);
    }

    @Override
    public void onQuit(Player p) {
        sent.remove(p.getUniqueId());
        lastOp.remove(p.getUniqueId());
    }

    private boolean rate(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastOp.get(p.getUniqueId());
        if (last != null && now - last < 400) return false;
        lastOp.put(p.getUniqueId(), now);
        return true;
    }

    private void res(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "res", o);
    }

    private void list(Player p) {
        if (!p.hasPermission(PERM_ADMIN)) {
            res(p, false, "Nu ai permisiunea.");
            return;
        }
        if (!rate(p)) return;
        double thr = hours() * 60.0;
        JsonArray arr = new JsonArray();
        for (Player q : Bukkit.getOnlinePlayers()) {
            int m = playedMinutes(q);
            JsonObject e = new JsonObject();
            e.addProperty("name", q.getName());
            e.addProperty("min", m);
            e.addProperty("beginner", enabled() && m < thr);
            arr.add(e);
        }
        JsonObject o = new JsonObject();
        o.add("players", arr);
        send(p, "list", o);
    }

    private static String cleanText(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_' || c == '.' || c == '!') b.append(c);
        }
        return b.toString().trim();
    }

    /** Salveaza toate setarile trimise din meniu (validate); doar staff. */
    private void cfgSet(Player p, JsonObject j) {
        if (!p.hasPermission(PERM_ADMIN)) {
            res(p, false, "Nu ai permisiunea de a configura badge-ul.");
            return;
        }
        if (!rate(p)) return;
        double hours = num(j, "hours", Double.NaN);
        if (Double.isNaN(hours) || hours < 0.1 || hours > 2000) {
            res(p, false, "Pragul trebuie sa fie intre 0.1 si 2000 de ore.");
            return;
        }
        String text = cleanText(str(j, "text", ""));
        if (text.length() > 16) text = text.substring(0, 16).trim();
        String icon = str(j, "icon", "star");
        if (!ICONS.contains(icon)) {
            res(p, false, "Iconita necunoscuta.");
            return;
        }
        if (text.isEmpty() && "none".equals(icon)) {
            res(p, false, "Pune un text sau o iconita.");
            return;
        }
        String color = str(j, "color", "");
        String timeColor = str(j, "timeColor", "");
        if (!HEX.matcher(color).matches() || !HEX.matcher(timeColor).matches()) {
            res(p, false, "Culori invalide (format #RRGGBB).");
            return;
        }
        int distance = integer(j, "distance", 24);
        double offset = num(j, "offset", 0.55);
        if (distance < 4 || distance > 64 || Double.isNaN(offset) || offset < -1.0 || offset > 2.0) {
            res(p, false, "Distanta 4-64 blocuri, inaltimea -1 .. 2.");
            return;
        }
        cfg.set("enabled", bool(j, "enabled", true));
        cfg.set("hours", Math.round(hours * 10.0) / 10.0);
        cfg.set("text", text);
        cfg.set("icon", icon);
        cfg.set("show-time", bool(j, "showTime", true));
        cfg.set("color", color.toUpperCase());
        cfg.set("time-color", timeColor.toUpperCase());
        cfg.set("distance", distance);
        cfg.set("offset", Math.round(offset * 100.0) / 100.0);
        cfg.set("hide-with-id", bool(j, "hideWithId", false));
        ModConfig.save(cfg, plugin, "beginner");
        plugin.getLogger().info("[Incepatori] " + p.getName() + " a schimbat setarile (prag " + hours + "h, activ=" + enabled() + ")");
        broadcast("cfg", cfgJson());
        sent.clear();
        broadcast("upd", mapJson(true));
        res(p, true, "Setari salvate.");
    }
}
