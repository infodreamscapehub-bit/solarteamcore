package ro.solarstudios.flowcity.bridge.eadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.eadmin.FAdminLevel.Cap;

/**
 * Partea noua a FAdmin (spec 31): moderare (lista de ban/mute/warn, inchisoare, istoric), rapoartele jucatorilor, staff (tura/duty,
 * activitate), grupurile si permisiunile LuckPerms, anunturi. Modulul {@link FAdminModule} inregistreaza operatiile si ii da
 * helperii; toate verificarile de nivel se fac pe server, la fiecare cerere.
 */
final class FAdminExt implements Listener {
    private static final int PAGE = 12;

    private final FlowCityBridge plugin;
    private final FAdminModule host;
    private final FAdminLog log;

    /** Inchisorile active: jucator -> detalii. Persistente in tabela fadm_jail. */
    private final Map<UUID, Jail> jails = new ConcurrentHashMap<>();
    /** Staff in tura (duty): jucator -> momentul intrarii in tura. */
    private final Map<UUID, Long> duty = new ConcurrentHashMap<>();
    private final Map<UUID, Long> reportCooldown = new ConcurrentHashMap<>();

    private long totalsAt;
    private double totalCash;
    private double totalBank;
    private int totalCount;

    static final class Jail {
        long until;
        String reason = "";
        String admin = "";
        String world = "";
        double x;
        double y;
        double z;
    }

    FAdminExt(FlowCityBridge plugin, FAdminModule host, FAdminLog log) {
        this.plugin = plugin;
        this.host = host;
        this.log = log;
        plugin.db().run(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS fadm_reports (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, reporter_uuid TEXT NOT NULL, "
                    + "reporter_name TEXT NOT NULL, target_uuid TEXT NOT NULL, target_name TEXT NOT NULL, reason TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN', "
                    + "staff_uuid TEXT NOT NULL DEFAULT '', staff_name TEXT NOT NULL DEFAULT '', closed_ts INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')");
                st.execute("CREATE INDEX IF NOT EXISTS idx_fadm_rep_status ON fadm_reports(status)");
                st.execute("CREATE TABLE IF NOT EXISTS fadm_jail (uuid TEXT PRIMARY KEY, until INTEGER NOT NULL, reason TEXT NOT NULL, admin TEXT NOT NULL, "
                    + "world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL)");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, until, reason, admin, world, x, y, z FROM fadm_jail");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        Jail j = new Jail();
                        j.until = rs.getLong(2);
                        j.reason = rs.getString(3);
                        j.admin = rs.getString(4);
                        j.world = rs.getString(5);
                        j.x = rs.getDouble(6);
                        j.y = rs.getDouble(7);
                        j.z = rs.getDouble(8);
                        jails.put(UUID.fromString(rs.getString(1)), j);
                    } catch (IllegalArgumentException ignored) {
                        // rand corupt
                    }
                }
            }
            return null;
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickJail, 60L, 40L);

        // comanda jucatorilor: raport catre staff. Administrarea rapoartelor se face doar din panou.
        Commands.register(plugin, "report", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Doar din joc.");
                return true;
            }
            report((Player) s, a);
            return true;
        }, null, "raport");
    }

    // ------------------------------------------------------------------ utilitare

    private static String sv(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : d;
    }

    private static double nv(JsonObject o, String k, double d) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsDouble() : d;
        } catch (NumberFormatException ex) {
            return d;
        }
    }

    private static UUID uid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void main(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    int jailedCount() {
        return jails.size();
    }

    boolean isJailed(UUID u) {
        return jails.containsKey(u);
    }

    boolean onDuty(UUID u) {
        return duty.containsKey(u);
    }

    int onlineWith(String perm) {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission(perm)) n++;
        return n;
    }

    // ------------------------------------------------------------------ inchisoare

    private Location cell() {
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        String w = cfg.getString("fadmin.jail.world", "");
        World world = w.isEmpty() ? null : Bukkit.getWorld(w);
        if (world == null) return null;
        return new Location(world, cfg.getDouble("fadmin.jail.x"), cfg.getDouble("fadmin.jail.y"), cfg.getDouble("fadmin.jail.z"));
    }

    boolean cellSet() {
        return cell() != null;
    }

    private double radius() {
        return Math.max(2, plugin.getConfig().getDouble("fadmin.jail.radius", 6));
    }

    /** Seteaza celula la pozitia staff-ului. */
    void jailSet(Player p, JsonObject j) {
        FAdminLevel lv = host.level(p, Cap.JAIL_SET);
        if (lv == null) return;
        Location l = p.getLocation();
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        String old = cfg.getString("fadmin.jail.world", "") + " " + Math.round(cfg.getDouble("fadmin.jail.x")) + "," + Math.round(cfg.getDouble("fadmin.jail.y")) + ","
            + Math.round(cfg.getDouble("fadmin.jail.z"));
        cfg.set("fadmin.jail.world", l.getWorld().getName());
        cfg.set("fadmin.jail.x", l.getX());
        cfg.set("fadmin.jail.y", l.getY());
        cfg.set("fadmin.jail.z", l.getZ());
        double r = nv(j, "radius", 0);
        if (r >= 2 && r <= 50) cfg.set("fadmin.jail.radius", r);
        plugin.saveConfig();
        String now = l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
        host.log.add(p.getName(), lv, "jail_set", null, "celula", old, now, "");
        host.res(p, true, "Celula de închisoare setată aici (" + now + ").");
    }

    /** Pune un jucator in inchisoare. Chemat din act() dupa verificarea nivelului si a tintei. */
    void jail(Player p, FAdminLevel lv, UUID u, String name, int minutes, String reason) {
        Location cell = cell();
        if (cell == null) {
            host.res(p, false, "Nu există celulă. Un Administrator o setează din Moderare → Închisoare.");
            return;
        }
        if (jails.containsKey(u)) {
            host.res(p, false, name + " este deja în închisoare.");
            return;
        }
        Player t = Bukkit.getPlayer(u);
        Jail jl = new Jail();
        jl.until = minutes > 0 ? System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes) : 0;
        jl.reason = reason;
        jl.admin = p.getName();
        Location back = t != null ? t.getLocation() : cell;
        jl.world = back.getWorld().getName();
        jl.x = back.getX();
        jl.y = back.getY();
        jl.z = back.getZ();
        jails.put(u, jl);
        persist(u, jl);
        if (t != null) {
            t.teleport(cell);
            t.sendMessage("§cAi fost băgat în închisoare" + (minutes > 0 ? " pentru " + EAdminService.describeMinutes(minutes) : "") + ". Motiv: " + reason);
        }
        host.log.add(p.getName(), lv, "jail", u, name, "", minutes > 0 ? EAdminService.describeMinutes(minutes) : "permanent", reason);
        host.res(p, true, name + " a fost băgat în închisoare.");
    }

    void unjail(Player p, FAdminLevel lv, UUID u, String name, String reason) {
        if (!jails.containsKey(u)) {
            host.res(p, false, name + " nu este în închisoare.");
            return;
        }
        release(u, p.getName(), lv, reason);
        host.res(p, true, name + " a fost eliberat.");
    }

    private void release(UUID u, String by, FAdminLevel lv, String reason) {
        Jail jl = jails.remove(u);
        if (jl == null) return;
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM fadm_jail WHERE uuid=?")) {
                ps.setString(1, u.toString());
                ps.executeUpdate();
            }
            return null;
        });
        Player t = Bukkit.getPlayer(u);
        if (t != null) {
            World w = Bukkit.getWorld(jl.world);
            t.teleport(w == null ? t.getWorld().getSpawnLocation() : new Location(w, jl.x, jl.y, jl.z));
            t.sendMessage("§aAi fost eliberat din închisoare.");
        }
        host.log.add(by, lv, "unjail", u, host.nameOf(u), "", "", reason);
    }

    private void persist(UUID u, Jail jl) {
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO fadm_jail(uuid, until, reason, admin, world, x, y, z) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, u.toString());
                ps.setLong(2, jl.until);
                ps.setString(3, jl.reason);
                ps.setString(4, jl.admin);
                ps.setString(5, jl.world);
                ps.setDouble(6, jl.x);
                ps.setDouble(7, jl.y);
                ps.setDouble(8, jl.z);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void tickJail() {
        if (jails.isEmpty()) return;
        long now = System.currentTimeMillis();
        Location cell = cell();
        double r = radius();
        for (Map.Entry<UUID, Jail> e : new ArrayList<>(jails.entrySet())) {
            Jail jl = e.getValue();
            if (jl.until > 0 && now >= jl.until) {
                release(e.getKey(), "sistem", null, "pedeapsa a expirat");
                continue;
            }
            Player t = Bukkit.getPlayer(e.getKey());
            if (t == null || cell == null) continue;
            if (!t.getWorld().equals(cell.getWorld()) || t.getLocation().distanceSquared(cell) > r * r) t.teleport(cell);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!jails.containsKey(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location cell = cell();
            if (p.isOnline() && cell != null && jails.containsKey(p.getUniqueId())) p.teleport(cell);
        }, 20L);
    }

    // ------------------------------------------------------------------ rapoarte

    private void report(Player p, String[] a) {
        if (a.length < 2) {
            p.sendMessage("§eFolosire: /report <jucător> <motiv>");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = reportCooldown.get(p.getUniqueId());
        if (last != null && now - last < 60_000) {
            p.sendMessage("§cAșteaptă puțin înainte de un nou raport.");
            return;
        }
        final String targetName = a[0];
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < a.length; i++) sb.append(i > 1 ? " " : "").append(a[i]);
        final String reason = FAdminModule.clip(sb.toString(), 200);
        if (reason.length() < 5) {
            p.sendMessage("§cScrie un motiv de cel puțin 5 caractere.");
            return;
        }
        plugin.service().findUuid(targetName).whenComplete((u, err) -> main(() -> {
            if (err != null || u == null) {
                p.sendMessage("§cJucătorul " + targetName + " nu există.");
                return;
            }
            if (u.equals(p.getUniqueId())) {
                p.sendMessage("§cNu te poți raporta pe tine.");
                return;
            }
            reportCooldown.put(p.getUniqueId(), System.currentTimeMillis());
            final String tn = host.nameOf(u);
            plugin.db().async(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO fadm_reports(ts, reporter_uuid, reporter_name, target_uuid, target_name, reason) VALUES (?,?,?,?,?,?)")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, p.getUniqueId().toString());
                    ps.setString(3, p.getName());
                    ps.setString(4, u.toString());
                    ps.setString(5, tn);
                    ps.setString(6, reason);
                    ps.executeUpdate();
                }
                return null;
            }).whenComplete((v, e2) -> main(() -> {
                if (e2 != null) {
                    p.sendMessage("§cRaportul nu s-a putut trimite.");
                    return;
                }
                p.sendMessage("§aRaportul împotriva lui " + tn + " a fost trimis staff-ului.");
                for (Player s : Bukkit.getOnlinePlayers()) {
                    if (s.equals(p) || !FAdminLevel.canSee(s, Cap.REPORT_VIEW)) continue;
                    if (plugin.channel().hasMod(s)) plugin.channel().toast(s, "Raport nou: " + p.getName() + " → " + tn, false);
                    else s.sendMessage("§e[FAdmin] Raport nou: " + p.getName() + " → " + tn);
                }
            }));
        }));
    }

    void reports(Player p, JsonObject j) {
        if (host.level(p, Cap.REPORT_VIEW) == null) return;
        final String st = sv(j, "status", "OPEN").toUpperCase(Locale.ROOT);
        final int page = Math.max(0, (int) nv(j, "page", 0));
        final String q = FAdminModule.clip(sv(j, "q", ""), 24);
        plugin.db().async(c -> {
            JsonObject o = new JsonObject();
            String where = " WHERE " + (st.equals("ALL") ? "1=1" : "status=?") + (q.isEmpty() ? "" : " AND (reporter_name LIKE ? OR target_name LIKE ? OR reason LIKE ?)");
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fadm_reports" + where)) {
                int i = 1;
                if (!st.equals("ALL")) ps.setString(i++, st);
                if (!q.isEmpty()) for (int k = 0; k < 3; k++) ps.setString(i++, "%" + q + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("total", rs.getInt(1));
                }
            }
            JsonArray rows = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, ts, reporter_name, target_uuid, target_name, reason, status, staff_name, closed_ts, note FROM fadm_reports" + where
                    + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                int i = 1;
                if (!st.equals("ALL")) ps.setString(i++, st);
                if (!q.isEmpty()) for (int k = 0; k < 3; k++) ps.setString(i++, "%" + q + "%");
                ps.setInt(i++, PAGE);
                ps.setInt(i, page * PAGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("id", rs.getLong(1));
                        r.addProperty("ts", rs.getLong(2));
                        r.addProperty("reporter", rs.getString(3));
                        r.addProperty("targetUuid", rs.getString(4));
                        r.addProperty("target", rs.getString(5));
                        r.addProperty("reason", rs.getString(6));
                        r.addProperty("status", rs.getString(7));
                        r.addProperty("staff", rs.getString(8));
                        r.addProperty("closed", rs.getLong(9));
                        r.addProperty("note", rs.getString(10));
                        rows.add(r);
                    }
                }
            }
            o.add("rows", rows);
            return o;
        }).whenComplete((o, err) -> main(() -> {
            if (err != null || o == null) {
                host.res(p, false, "Nu am putut încărca rapoartele.");
                return;
            }
            o.addProperty("status", st);
            o.addProperty("page", page);
            o.addProperty("q", q);
            host.snd(p, "reports", o);
        }));
    }

    void reportAct(Player p, JsonObject j) {
        final FAdminLevel lv = host.level(p, Cap.REPORT_HANDLE);
        if (lv == null) return;
        final long id = (long) nv(j, "id", -1);
        final String action = sv(j, "action", "");
        final String note = FAdminModule.clip(sv(j, "reason", ""), 160);
        final String to;
        switch (action) {
            case "accept": to = "ACCEPTED"; break;
            case "reject": to = "REJECTED"; break;
            case "resolve": to = "RESOLVED"; break;
            case "reopen": to = "OPEN"; break;
            default:
                host.res(p, false, "Acțiune necunoscută.");
                return;
        }
        if (to.equals("REJECTED") && note.length() < 3) {
            host.res(p, false, "Scrie un motiv pentru respingere (minim 3 caractere).");
            return;
        }
        plugin.db().async(c -> {
            String reporter = null;
            String oldSt = null;
            String tname = "";
            String tuuid = "";
            try (PreparedStatement ps = c.prepareStatement("SELECT reporter_uuid, status, target_name, target_uuid, staff_uuid FROM fadm_reports WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        reporter = rs.getString(1);
                        oldSt = rs.getString(2);
                        tname = rs.getString(3);
                        tuuid = rs.getString(4);
                    }
                }
            }
            if (oldSt == null) return null;
            boolean open = to.equals("OPEN");
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE fadm_reports SET status=?, staff_uuid=?, staff_name=?, closed_ts=?, note=? WHERE id=?")) {
                ps.setString(1, to);
                ps.setString(2, open ? "" : p.getUniqueId().toString());
                ps.setString(3, open ? "" : p.getName());
                ps.setLong(4, to.equals("RESOLVED") || to.equals("REJECTED") ? System.currentTimeMillis() : 0);
                ps.setString(5, note);
                ps.setLong(6, id);
                ps.executeUpdate();
            }
            return new String[] {reporter, oldSt, tname, tuuid};
        }).whenComplete((r, err) -> main(() -> {
            if (err != null || r == null) {
                host.res(p, false, "Raportul nu mai există.");
                return;
            }
            host.log.add(p.getName(), lv, "report_" + action, uid(r[3]), r[2], r[1], to, note.isEmpty() ? "raport #" + id : "raport #" + id + ": " + note);
            UUID rep = uid(r[0]);
            Player rp = rep == null ? null : Bukkit.getPlayer(rep);
            if (rp != null && (to.equals("RESOLVED") || to.equals("REJECTED") || to.equals("ACCEPTED"))) {
                String msg = to.equals("ACCEPTED") ? "Raportul tău #" + id + " a fost preluat de staff." : to.equals("RESOLVED")
                    ? "Raportul tău #" + id + " a fost rezolvat." : "Raportul tău #" + id + " a fost respins.";
                if (plugin.channel().hasMod(rp)) plugin.channel().toast(rp, msg, to.equals("REJECTED"));
                else rp.sendMessage("§e[Raport] " + msg);
            }
            host.res(p, true, "Raport #" + id + " → " + to);
            JsonObject q = new JsonObject();
            q.addProperty("status", sv(j, "filter", "OPEN"));
            q.addProperty("page", nv(j, "page", 0));
            reports(p, q);
        }));
    }

    // ------------------------------------------------------------------ moderare

    void moderation(Player p, JsonObject j) {
        if (host.level(p, Cap.MOD_VIEW) == null) return;
        final String tab = sv(j, "tab", "bans");
        final int page = Math.max(0, (int) nv(j, "page", 0));
        final long now = System.currentTimeMillis();
        if (tab.equals("jail")) {
            JsonObject o = new JsonObject();
            o.addProperty("tab", tab);
            o.addProperty("cell", cellSet());
            JsonArray rows = new JsonArray();
            for (Map.Entry<UUID, Jail> e : jails.entrySet()) {
                JsonObject r = new JsonObject();
                r.addProperty("uuid", e.getKey().toString());
                r.addProperty("name", host.nameOf(e.getKey()));
                r.addProperty("admin", e.getValue().admin);
                r.addProperty("reason", e.getValue().reason);
                r.addProperty("until", e.getValue().until);
                r.addProperty("online", Bukkit.getPlayer(e.getKey()) != null);
                rows.add(r);
            }
            o.add("rows", rows);
            o.addProperty("total", rows.size());
            o.addProperty("page", 0);
            host.snd(p, "mod", o);
            return;
        }
        plugin.db().async(c -> {
            JsonObject o = new JsonObject();
            JsonArray rows = new JsonArray();
            String sql;
            String cnt;
            if (tab.equals("history")) {
                String u = "SELECT ts, type AS kind, admin_name AS adm, player_uuid AS pu, reason, duration_minutes AS dur FROM eadmin_sanctions WHERE deleted=0 "
                    + "UNION ALL SELECT ts, UPPER(action), admin, target_uuid, reason, 0 FROM fadm_log WHERE action IN ('jail','unjail','freeze','spectate')";
                sql = "SELECT x.ts, x.kind, x.adm, x.pu, x.reason, x.dur, COALESCE(p.name,'?') FROM (" + u + ") x LEFT JOIN eadmin_players p ON p.uuid=x.pu "
                    + "ORDER BY x.ts DESC LIMIT ? OFFSET ?";
                cnt = "SELECT (SELECT COUNT(*) FROM eadmin_sanctions WHERE deleted=0) + (SELECT COUNT(*) FROM fadm_log WHERE action IN ('jail','unjail','freeze','spectate'))";
            } else {
                String type = tab.equals("bans") ? "BAN" : tab.equals("mutes") ? "MUTE" : "WARN";
                boolean act = !type.equals("WARN");
                String w = " WHERE s.type='" + type + "' AND s.deleted=0" + (act ? " AND s.active=1 AND s.expires_at>" + now : "");
                sql = "SELECT s.ts, s.type, s.admin_name, s.player_uuid, s.reason, s.duration_minutes, COALESCE(p.name,'?'), s.expires_at FROM eadmin_sanctions s "
                    + "LEFT JOIN eadmin_players p ON p.uuid=s.player_uuid" + w + " ORDER BY s.id DESC LIMIT ? OFFSET ?";
                cnt = "SELECT COUNT(*) FROM eadmin_sanctions s" + w;
            }
            try (PreparedStatement ps = c.prepareStatement(cnt); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) o.addProperty("total", rs.getInt(1));
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, PAGE);
                ps.setInt(2, page * PAGE);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean hist = tab.equals("history");
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("ts", rs.getLong(1));
                        r.addProperty("kind", rs.getString(2));
                        r.addProperty("admin", rs.getString(3));
                        r.addProperty("uuid", rs.getString(4));
                        r.addProperty("reason", rs.getString(5));
                        r.addProperty("minutes", rs.getInt(6));
                        r.addProperty("name", rs.getString(7));
                        if (!hist) r.addProperty("until", rs.getLong(8));
                        rows.add(r);
                    }
                }
            }
            o.add("rows", rows);
            return o;
        }).whenComplete((o, err) -> main(() -> {
            if (err != null || o == null) {
                host.res(p, false, "Nu am putut încărca moderarea.");
                return;
            }
            o.addProperty("tab", tab);
            o.addProperty("page", page);
            o.addProperty("cell", cellSet());
            host.snd(p, "mod", o);
        }));
    }

    // ------------------------------------------------------------------ staff

    void staff(Player p, JsonObject j) {
        if (host.level(p, Cap.STAFF_VIEW) == null) return;
        final long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        plugin.db().async(c -> {
            Map<String, JsonObject> by = new LinkedHashMap<>();
            for (Player s : Bukkit.getOnlinePlayers()) {
                FAdminLevel l = FAdminLevel.of(s);
                if (l == null) continue;
                JsonObject e = new JsonObject();
                e.addProperty("name", s.getName());
                e.addProperty("uuid", s.getUniqueId().toString());
                e.addProperty("level", l.label);
                e.addProperty("online", true);
                String g = LpApi.primary(s.getUniqueId());
                e.addProperty("group", g == null ? "-" : g);
                e.addProperty("duty", duty.containsKey(s.getUniqueId()));
                e.addProperty("dutySince", duty.getOrDefault(s.getUniqueId(), 0L));
                e.addProperty("actions", 0);
                e.addProperty("reports", 0);
                e.addProperty("last", 0);
                by.put(s.getName(), e);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT admin, admin_uuid, level, COUNT(*), MAX(ts) FROM fadm_log WHERE ts>? GROUP BY admin")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String n = rs.getString(1);
                        JsonObject e = by.get(n);
                        if (e == null) {
                            e = new JsonObject();
                            e.addProperty("name", n);
                            e.addProperty("uuid", rs.getString(2));
                            e.addProperty("level", rs.getString(3));
                            e.addProperty("online", false);
                            e.addProperty("group", "-");
                            e.addProperty("duty", false);
                            e.addProperty("dutySince", 0L);
                            e.addProperty("reports", 0);
                            by.put(n, e);
                        }
                        e.addProperty("actions", rs.getInt(4));
                        e.addProperty("last", rs.getLong(5));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT staff_name, COUNT(*) FROM fadm_reports WHERE staff_name<>'' GROUP BY staff_name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject e = by.get(rs.getString(1));
                    if (e != null) e.addProperty("reports", rs.getInt(2));
                }
            }
            JsonArray rows = new JsonArray();
            for (JsonObject e : by.values()) rows.add(e);
            JsonObject o = new JsonObject();
            o.add("rows", rows);
            return o;
        }).whenComplete((o, err) -> main(() -> {
            if (err != null || o == null) {
                host.res(p, false, "Nu am putut încărca staff-ul.");
                return;
            }
            host.snd(p, "staff", o);
        }));
    }

    /** Istoricul actiunilor unui staff (ultimele din jurnal). */
    void staffHist(Player p, JsonObject j) {
        if (host.level(p, Cap.STAFF_VIEW) == null) return;
        final String name = FAdminModule.clip(sv(j, "name", ""), 16);
        plugin.db().async(c -> {
            JsonArray rows = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT ts, action, target_name, old_val, new_val, reason FROM fadm_log WHERE admin=? ORDER BY id DESC LIMIT 14")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("ts", rs.getLong(1));
                        r.addProperty("action", rs.getString(2));
                        r.addProperty("target", rs.getString(3));
                        r.addProperty("old", rs.getString(4));
                        r.addProperty("new", rs.getString(5));
                        r.addProperty("reason", rs.getString(6));
                        rows.add(r);
                    }
                }
            }
            return rows;
        }).whenComplete((rows, err) -> main(() -> {
            if (err != null || rows == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.add("rows", rows);
            host.snd(p, "staff_hist", o);
        }));
    }

    void dutyToggle(Player p, JsonObject j) {
        FAdminLevel lv = host.level(p, Cap.DUTY);
        if (lv == null) return;
        boolean on;
        if (duty.remove(p.getUniqueId()) != null) {
            on = false;
        } else {
            duty.put(p.getUniqueId(), System.currentTimeMillis());
            on = true;
        }
        host.log.add(p.getName(), lv, on ? "duty_on" : "duty_off", p.getUniqueId(), p.getName(), on ? "off" : "on", on ? "on" : "off", "");
        host.res(p, true, on ? "Ai intrat în tură." : "Ai ieșit din tură.");
        staff(p, new JsonObject());
    }

    void quit(Player p) {
        Long since = duty.remove(p.getUniqueId());
        if (since != null) host.log.add(p.getName(), FAdminLevel.of(p), "duty_off", p.getUniqueId(), p.getName(), "on", "off", "a ieșit de pe server");
        reportCooldown.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ dashboard / economie

    void dashNow(JsonObject o) {
        o.addProperty("jailed", jails.size());
        int onDuty = 0;
        for (UUID u : duty.keySet()) if (Bukkit.getPlayer(u) != null) onDuty++;
        o.addProperty("staffDuty", onDuty);
        o.addProperty("police", onlineWith("flowcity.politie.officer"));
        o.addProperty("ems", onlineWith("flowcity.ems.officer"));
    }

    void dashDb(JsonObject o, java.sql.Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fadm_reports WHERE status IN ('OPEN','ACCEPTED')");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) o.addProperty("reports", rs.getInt(1));
        }
    }

    /** Suma banilor ai jucatorilor activi recent (cache 60 s; maxim 2000 de conturi, ca sa nu incarce serverul). */
    void totals(JsonObject o, List<UUID> uuids) {
        long now = System.currentTimeMillis();
        if (now - totalsAt > 60_000) {
            double cash = 0;
            double bank = 0;
            int n = 0;
            for (UUID u : uuids) {
                cash += plugin.money().balance(u);
                bank += plugin.bank().balance(u);
                n++;
            }
            totalCash = cash;
            totalBank = bank;
            totalCount = n;
            totalsAt = now;
        }
        o.addProperty("cashTotal", totalCash);
        o.addProperty("bankTotal", totalBank);
        o.addProperty("econAccounts", totalCount);
    }

    // ------------------------------------------------------------------ server

    void announce(Player p, JsonObject j) {
        FAdminLevel lv = host.level(p, Cap.ANNOUNCE);
        if (lv == null) return;
        String text = FAdminModule.clip(sv(j, "text", ""), 160);
        if (text.length() < 3) {
            host.res(p, false, "Scrie anunțul (minim 3 caractere).");
            return;
        }
        boolean title = sv(j, "mode", "chat").equals("title");
        String clean = text.replace('§', ' ').replace('&', ' ');
        Bukkit.broadcastMessage("§6§l[FlowCity] §e" + clean);
        if (title) for (Player t : Bukkit.getOnlinePlayers()) t.sendTitle("§6§lFlowCity", "§e" + clean, 10, 70, 20);
        host.log.add(p.getName(), lv, "announce", null, title ? "titlu" : "chat", "", clean, "");
        host.res(p, true, "Anunț trimis (" + Bukkit.getOnlinePlayers().size() + " jucători).");
    }

    void recentAnnouncements(JsonObject out, java.sql.Connection c) throws Exception {
        JsonArray a = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement("SELECT ts, admin, new_val FROM fadm_log WHERE action='announce' ORDER BY id DESC LIMIT 4");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JsonObject r = new JsonObject();
                r.addProperty("ts", rs.getLong(1));
                r.addProperty("admin", rs.getString(2));
                r.addProperty("text", rs.getString(3));
                a.add(r);
            }
        }
        out.add("announcements", a);
    }

    // ------------------------------------------------------------------ LuckPerms

    void lpGroups(Player p, JsonObject j) {
        if (host.level(p, Cap.PERM_VIEW) == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("available", LpApi.available());
        JsonArray g = new JsonArray();
        for (String s : LpApi.groups()) g.add(s);
        o.add("groups", g);
        final String sel = FAdminModule.clip(sv(j, "group", ""), 32);
        if (!sel.isEmpty()) {
            o.addProperty("group", sel);
            JsonArray perms = new JsonArray();
            List<String[]> ns = LpApi.groupNodes(sel);
            for (String[] n : ns) {
                if (perms.size() >= 60) break;
                JsonObject e = new JsonObject();
                e.addProperty("key", n[0]);
                e.addProperty("value", n[1].equals("true"));
                perms.add(e);
            }
            o.add("perms", perms);
            o.addProperty("permTotal", ns.size());
        }
        host.snd(p, "lp_groups", o);
    }

    void lpUser(Player p, JsonObject j) {
        if (host.level(p, Cap.PERM_VIEW) == null) return;
        final UUID u = uid(sv(j, "uuid", ""));
        if (u == null) return;
        if (!LpApi.available()) {
            host.res(p, false, "LuckPerms nu este instalat.");
            return;
        }
        LpApi.withUser(u, user -> {
            JsonObject o = new JsonObject();
            JsonArray g = new JsonArray();
            for (String s : LpApi.userGroups(user)) g.add(s);
            o.add("groups", g);
            JsonArray perms = new JsonArray();
            List<String[]> ps = LpApi.userPerms(user);
            for (String[] n : ps) {
                if (perms.size() >= 60) break;
                JsonObject e = new JsonObject();
                e.addProperty("key", n[0]);
                e.addProperty("value", n[1].equals("true"));
                perms.add(e);
            }
            o.add("perms", perms);
            o.addProperty("permTotal", ps.size());
            return o;
        }).whenComplete((o, err) -> main(() -> {
            if (err != null || o == null) {
                host.res(p, false, "Nu am putut citi datele din LuckPerms pentru acest jucător.");
                return;
            }
            o.addProperty("uuid", u.toString());
            o.addProperty("name", host.nameOf(u));
            host.snd(p, "lp_user", o);
        }));
    }

    /** Atribuie / scoate un grup LuckPerms. Cere nivelul RANK; grupurile care dau acces la FAdmin sau "*" le poate gestiona doar Owner. */
    void lpGroupSet(Player p, FAdminLevel lv, UUID u, String name, String group, boolean add, String reason) {
        if (!LpApi.available()) {
            host.res(p, false, "LuckPerms nu este instalat.");
            return;
        }
        if (!LpApi.groups().contains(group)) {
            host.res(p, false, "Grupul „" + group + "” nu există.");
            return;
        }
        if (lv != FAdminLevel.OWNER) {
            for (String[] n : LpApi.groupNodes(group)) {
                if (n[1].equals("true") && (n[0].equals("*") || n[0].startsWith("flowcity.fadmin") || n[0].startsWith("flowcity.eadmin"))) {
                    host.res(p, false, "Grupul „" + group + "” dă acces administrativ: îl poate gestiona doar Owner-ul.");
                    return;
                }
            }
        }
        LpApi.withUser(u, user -> {
            List<String> before = LpApi.userGroups(user);
            boolean changed = LpApi.setGroup(user, group, add);
            return new Object[] {changed, before, LpApi.userGroups(user)};
        }).whenComplete((r, err) -> main(() -> {
            if (err != null || r == null) {
                host.res(p, false, "LuckPerms a refuzat operația.");
                return;
            }
            if (!(Boolean) r[0]) {
                host.res(p, false, add ? name + " are deja grupul „" + group + "”." : name + " nu are grupul „" + group + "”.");
                return;
            }
            host.log.add(p.getName(), lv, add ? "rank_add" : "rank_remove", u, name, String.join(",", castList(r[1])), String.join(",", castList(r[2])), reason);
            host.res(p, true, name + ": " + (add ? "+" : "−") + " " + group);
            JsonObject q = new JsonObject();
            q.addProperty("uuid", u.toString());
            lpUser(p, q);
        }));
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }

    /** Grupul principal pentru fiecare jucator (cache LuckPerms; offline se incarca). */
    CompletableFuture<Map<UUID, String>> ranks(List<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        List<CompletableFuture<?>> pend = new ArrayList<>();
        for (UUID u : ids) {
            String g = LpApi.primary(u);
            if (g != null) {
                out.put(u, g);
            } else if (LpApi.available()) {
                pend.add(LpApi.withUser(u, user -> LpApi.userPrimary(user)).handle((v, e) -> {
                    synchronized (out) {
                        if (v != null) out.put(u, v);
                    }
                    return null;
                }));
            }
        }
        return CompletableFuture.allOf(pend.toArray(new CompletableFuture<?>[0])).handle((v, e) -> out);
    }

    // ------------------------------------------------------------------ profil: stari

    void profileFlags(JsonObject o, UUID u) {
        AdminPlusModule ap = AdminPlusModule.instance;
        o.addProperty("frozen", ap != null && ap.isFrozen(u));
        Jail jl = jails.get(u);
        o.addProperty("jailed", jl != null);
        if (jl != null) {
            o.addProperty("jailUntil", jl.until);
            o.addProperty("jailReason", jl.reason);
        }
    }

    int reportsAgainst(java.sql.Connection c, UUID u) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fadm_reports WHERE target_uuid=?")) {
            ps.setString(1, u.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
