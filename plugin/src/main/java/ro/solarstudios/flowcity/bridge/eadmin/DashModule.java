package ro.solarstudios.flowcity.bridge.eadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** Dashboard-ul eAdmin: statistici live (jucatori, TPS, RAM) si activitate recenta. Modul "dash". */
public final class DashModule extends JsonModule {
    @Override
    public String id() {
        return "dash";
    }

    @Override
    protected void onEnable() {
        on("stats", (p, j) -> {
            if (!p.hasPermission("flowcity.eadmin")) return;
            stats(p);
        });
    }

    private static double tps() {
        try {
            Object r = Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            if (r instanceof double[] && ((double[]) r).length > 0) return Math.min(20.0, ((double[]) r)[0]);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // implementare fara getTPS
        }
        return -1;
    }

    private void stats(Player viewer) {
        final long now = System.currentTimeMillis();
        final long day = now - TimeUnit.DAYS.toMillis(1);
        final JsonObject o = new JsonObject();
        int staff = 0;
        int mods = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("flowcity.eadmin")) staff++;
            if (plugin.channel().hasMod(p)) mods++;
        }
        Runtime rt = Runtime.getRuntime();
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("max", Bukkit.getMaxPlayers());
        o.addProperty("staff", staff);
        o.addProperty("mods", mods);
        o.addProperty("tps", tps());
        o.addProperty("ramUsed", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
        o.addProperty("ramMax", rt.maxMemory() / 1048576L);
        plugin.db().async(c -> {
            JsonArray recent = new JsonArray();
            JsonArray flags = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("total", rs.getInt(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players WHERE first_seen>?")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("newToday", rs.getInt(1));
                }
            }
            for (String t : new String[] {"BAN", "MUTE"}) {
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM eadmin_sanctions WHERE type=? AND active=1 AND deleted=0 AND expires_at>?")) {
                    ps.setString(1, t);
                    ps.setLong(2, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) o.addProperty(t.equals("BAN") ? "bans" : "mutes", rs.getInt(1));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_sanctions WHERE timestamp>? AND deleted=0")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("sanctions24", rs.getInt(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_flags WHERE timestamp>?")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("flags24", rs.getInt(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.type, s.admin_name, COALESCE(p.name, '?'), s.reason, s.timestamp FROM eadmin_sanctions s "
                    + "LEFT JOIN eadmin_players p ON p.uuid = s.player_uuid WHERE s.deleted=0 ORDER BY s.id DESC LIMIT 7");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("type", rs.getString(1));
                    r.addProperty("by", rs.getString(2));
                    r.addProperty("target", rs.getString(3));
                    r.addProperty("reason", rs.getString(4));
                    r.addProperty("ts", rs.getLong(5));
                    recent.add(r);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT f.severity, f.trigger_type, COALESCE(p.name, '?'), f.timestamp FROM eadmin_flags f "
                    + "LEFT JOIN eadmin_players p ON p.uuid = f.player_uuid ORDER BY f.id DESC LIMIT 5");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("sev", rs.getString(1));
                    r.addProperty("trigger", rs.getString(2));
                    r.addProperty("target", rs.getString(3));
                    r.addProperty("ts", rs.getLong(4));
                    flags.add(r);
                }
            }
            o.add("recent", recent);
            o.add("flags", flags);
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.getLogger().warning("Dashboard eAdmin: " + err);
                return;
            }
            send(viewer, "stats", o);
        }));
    }
}
