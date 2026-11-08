package ro.solarstudios.flowcity.bridge.eadmin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.net.Wire;

/** Toata logica eAdmin: loguri, sanctiuni, note, flag-uri, interogari pentru panou. */
public final class EAdminService {
    public enum SType { WARN, MUTE, KICK, BAN }

    public static final int PLAYERS_PER_PAGE = 12;
    public static final int LOGS_PER_PAGE = 14;
    public static final int MAX_TEXT = 200;
    private static final int MAX_LIST = 30;

    /** Definitia unui tip de log: tabela + SELECT care intoarce (timestamp, a, b, c). */
    private static final class LogDef {
        final String table;
        final String select;

        LogDef(String table, String select) {
            this.table = table;
            this.select = select;
        }
    }

    private static final Map<String, LogDef> LOGS = new LinkedHashMap<>();

    static {
        LOGS.put("CHAT", new LogDef("eadmin_chat_logs", "SELECT timestamp, message, '', '' FROM eadmin_chat_logs"));
        LOGS.put("CMD", new LogDef("eadmin_command_logs", "SELECT timestamp, command, '', '' FROM eadmin_command_logs"));
        LOGS.put("CONN", new LogDef("eadmin_connection_logs",
            "SELECT timestamp, event_type, ip_address, coords || ' ' || dimension FROM eadmin_connection_logs"));
        LOGS.put("CREATIVE", new LogDef("eadmin_creative_logs",
            "SELECT timestamp, action_type, item_id || ' x' || count, details FROM eadmin_creative_logs"));
        LOGS.put("INV", new LogDef("eadmin_inventory_logs",
            "SELECT timestamp, action, item_id || ' x' || count, container_type || ' ' || coords FROM eadmin_inventory_logs"));
        LOGS.put("TX", new LogDef("eadmin_transactions",
            "SELECT timestamp, type, printf('%.2f', amount), target_name || ' ' || COALESCE(location, '') FROM eadmin_transactions"));
    }

    public interface Result {
        void done(boolean ok, String message);
    }

    private final FlowCityBridge plugin;
    private final Db db;
    private final VaultHook vault;
    private final Map<UUID, Long> mutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();

    public EAdminService(FlowCityBridge plugin, Db db, VaultHook vault) {
        this.plugin = plugin;
        this.db = db;
        this.vault = vault;
    }

    // ------------------------------------------------------------------ utilitare

    private static void bind(PreparedStatement ps, Object... a) throws SQLException {
        for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
    }

    private void exec(String sql, Object... args) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private void toMain(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    public static boolean isLogType(String t) {
        return LOGS.containsKey(t);
    }

    // ------------------------------------------------------------------ loguri

    public void logChat(UUID u, String msg) {
        exec("INSERT INTO eadmin_chat_logs(player_uuid, message, timestamp) VALUES (?,?,?)", u.toString(), clip(msg, 500), now());
    }

    public void logCommand(UUID u, String cmd) {
        exec("INSERT INTO eadmin_command_logs(player_uuid, command, timestamp) VALUES (?,?,?)", u.toString(), clip(cmd, 500), now());
    }

    public void logConnection(UUID u, String ip, String event, String coords, String dim) {
        exec("INSERT INTO eadmin_connection_logs(player_uuid, ip_address, event_type, coords, dimension, timestamp) VALUES (?,?,?,?,?,?)",
            u.toString(), ip, event, coords, dim, now());
    }

    public void logCreative(UUID u, String action, String item, int count, String details) {
        exec("INSERT INTO eadmin_creative_logs(player_uuid, action_type, item_id, count, details, timestamp) VALUES (?,?,?,?,?,?)",
            u.toString(), action, item, count, clip(details, 200), now());
    }

    public void logInventory(UUID u, String action, String item, int count, String container, String coords) {
        exec("INSERT INTO eadmin_inventory_logs(player_uuid, action, item_id, count, container_type, coords, timestamp) VALUES (?,?,?,?,?,?,?)",
            u.toString(), action, item, count, container, coords, now());
    }

    /** API pentru alte plugin-uri: inregistreaza o tranzactie si marcheaza sumele mari. */
    public void logTransaction(UUID u, String type, double amount, String target, String location) {
        exec("INSERT INTO eadmin_transactions(player_uuid, type, amount, target_name, timestamp, location) VALUES (?,?,?,?,?,?)",
            u.toString(), type, amount, clip(target, 64), now(), location == null ? "" : location);
        double limit = plugin.getConfig().getDouble("eadmin.big-transaction-threshold", 1000000);
        if (limit > 0 && Math.abs(amount) >= limit) {
            flag(u, "MEDIUM", "BIG_TRANSACTION", type + " " + String.format("%.2f", amount) + " -> " + target);
        }
    }

    public void flag(UUID u, String severity, String trigger, String details) {
        exec("INSERT INTO eadmin_flags(player_uuid, severity, trigger_type, details, timestamp) VALUES (?,?,?,?,?)",
            u.toString(), severity, trigger, clip(details, 300), now());
        final String msg = "§c[eAdmin] §f" + severity + " §7" + trigger + " §8- §f" + clip(details, 120);
        toMain(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("flowcity.eadmin")) p.sendMessage(msg);
            }
        });
    }

    public void purgeOld(int days) {
        if (days <= 0) return;
        final long limit = now() - TimeUnit.DAYS.toMillis(days);
        for (LogDef d : LOGS.values()) {
            exec("DELETE FROM " + d.table + " WHERE timestamp < ?", limit);
        }
    }

    static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ sesiuni jucatori

    public void recordJoin(Player p) {
        final UUID u = p.getUniqueId();
        final String name = p.getName();
        final String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        final long t = now();
        sessionStart.put(u, t);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO eadmin_players(uuid, name, first_seen, last_seen, last_ip) VALUES (?,?,?,?,?)")) {
                bind(ps, u.toString(), name, t, t, ip);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE eadmin_players SET name=?, last_seen=?, last_ip=? WHERE uuid=?")) {
                bind(ps, name, t, ip, u.toString());
                ps.executeUpdate();
            }
            // multi-account: alte conturi cu acelasi IP
            List<String> others = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM eadmin_players WHERE last_ip=? AND uuid<>? AND last_ip<>'' LIMIT 6")) {
                bind(ps, ip, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) others.add(rs.getString(1));
                }
            }
            if (!others.isEmpty()) {
                boolean recent = false;
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM eadmin_flags WHERE player_uuid=? AND trigger_type='MULTI_ACCOUNT' AND timestamp>?")) {
                    bind(ps, u.toString(), t - TimeUnit.HOURS.toMillis(24));
                    try (ResultSet rs = ps.executeQuery()) {
                        recent = rs.next();
                    }
                }
                if (!recent) flag(u, "MEDIUM", "MULTI_ACCOUNT", "IP comun cu: " + String.join(", ", others));
            }
            return null;
        });
    }

    public void recordQuit(Player p) {
        final UUID u = p.getUniqueId();
        final long t = now();
        Long start = sessionStart.remove(u);
        final long played = start == null ? 0 : Math.max(0, (t - start) / 1000L);
        exec("UPDATE eadmin_players SET last_seen=?, play_seconds=play_seconds+? WHERE uuid=?", t, played, u.toString());
    }

    // ------------------------------------------------------------------ sanctiuni (verificari)

    /** Ruleaza pe thread-ul de pre-login (asincron). Intoarce motivul de refuz sau null. */
    public String checkLogin(UUID u) {
        try {
            final long t = now();
            String[] ban = db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT reason, expires_at, admin_name FROM eadmin_sanctions WHERE player_uuid=? AND type='BAN' AND active=1 AND expires_at>? ORDER BY expires_at DESC LIMIT 1")) {
                    bind(ps, u.toString(), t);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return new String[] {rs.getString(1), String.valueOf(rs.getLong(2)), rs.getString(3)};
                    }
                }
                return null;
            }).get(4, TimeUnit.SECONDS);
            loadMute(u);
            if (ban == null) return null;
            long exp = Long.parseLong(ban[1]);
            String until = exp == Long.MAX_VALUE ? "permanent" : formatDate(exp);
            return "§c§lAi fost banat pe " + serverName() + "\n\n§7Motiv: §f" + ban[0]
                + "\n§7Expira: §f" + until + "\n§7Staff: §f" + ban[2];
        } catch (Exception e) {
            plugin.getLogger().warning("Nu am putut verifica ban-ul pentru " + u + ": " + e);
            return null; // nu blocam jucatorii daca DB-ul cade
        }
    }

    private void loadMute(UUID u) throws Exception {
        final long t = now();
        Long exp = db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(expires_at) FROM eadmin_sanctions WHERE player_uuid=? AND type='MUTE' AND active=1 AND expires_at>?")) {
                bind(ps, u.toString(), t);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? null : v;
                    }
                }
            }
            return null;
        }).get(4, TimeUnit.SECONDS);
        if (exp != null) mutes.put(u, exp);
        else mutes.remove(u);
    }

    /** Intoarce expirarea mute-ului (millis) sau 0 daca jucatorul nu e mutat. */
    public long muteExpiry(UUID u) {
        Long exp = mutes.get(u);
        if (exp == null) return 0;
        if (exp < now()) {
            mutes.remove(u);
            return 0;
        }
        return exp;
    }

    public String serverName() {
        return plugin.getConfig().getString("server-name", "FlowCity");
    }

    static String formatDate(long ms) {
        return new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm").format(new java.util.Date(ms));
    }

    // ------------------------------------------------------------------ sanctiuni (aplicare)

    public void applySanction(String adminName, UUID target, String targetName, SType type, int minutes, String reason, Result cb) {
        final String why = clip(reason, MAX_TEXT).isEmpty() ? "Fara motiv" : clip(reason, MAX_TEXT);
        final boolean timed = (type == SType.MUTE || type == SType.BAN);
        final int mins = timed ? Math.max(0, minutes) : 0;
        final long t = now();
        final long expires = !timed ? 0 : (mins == 0 ? Long.MAX_VALUE : t + TimeUnit.MINUTES.toMillis(mins));
        final int active = timed ? 1 : 0;
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO eadmin_sanctions(player_uuid, type, admin_name, reason, duration_minutes, timestamp, expires_at, active) VALUES (?,?,?,?,?,?,?,?)")) {
                bind(ps, target.toString(), type.name(), adminName, why, mins, t, expires, active);
                ps.executeUpdate();
            }
            return Boolean.TRUE;
        }).whenComplete((ok, err) -> toMain(() -> {
            if (err != null) {
                cb.done(false, "Eroare la salvarea sanctiunii (vezi consola).");
                return;
            }
            Player online = Bukkit.getPlayer(target);
            String dur = !timed ? "" : (mins == 0 ? " (permanent)" : " (" + describeMinutes(mins) + ")");
            switch (type) {
                case MUTE:
                    mutes.put(target, expires);
                    if (online != null) online.sendMessage("§cAi primit mute" + dur + ". §7Motiv: §f" + why);
                    break;
                case WARN:
                    if (online != null) online.sendMessage("§eAi primit un avertisment. §7Motiv: §f" + why);
                    break;
                case KICK:
                    if (online != null) online.kickPlayer("§cAi fost dat afara de pe " + serverName() + "\n§7Motiv: §f" + why);
                    break;
                case BAN:
                    if (online != null) {
                        online.kickPlayer("§c§lAi fost banat pe " + serverName() + "\n\n§7Motiv: §f" + why
                            + "\n§7Expira: §f" + (expires == Long.MAX_VALUE ? "permanent" : formatDate(expires)));
                    }
                    break;
                default:
                    break;
            }
            cb.done(true, type.name() + " aplicat lui " + targetName + dur);
        }));
    }

    public static String describeMinutes(int m) {
        if (m % 1440 == 0) return (m / 1440) + "z";
        if (m % 60 == 0) return (m / 60) + "h";
        return m + "m";
    }

    /** Revoca o sanctiune dupa id. Intoarce uuid-ul jucatorului prin callback (null daca nu exista). */
    public void revoke(int id, String adminName, java.util.function.Consumer<UUID> cb) {
        db.async(c -> {
            String uuid = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM eadmin_sanctions WHERE id=?")) {
                bind(ps, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) uuid = rs.getString(1);
                }
            }
            if (uuid == null) return null;
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE eadmin_sanctions SET active=0, deleted=1, deleted_by=? WHERE id=?")) {
                bind(ps, adminName, id);
                ps.executeUpdate();
            }
            return UUID.fromString(uuid);
        }).whenComplete((u, err) -> toMain(() -> {
            if (u != null) {
                try {
                    loadMute(u);
                } catch (Exception ignored) {
                    mutes.remove(u);
                }
            }
            cb.accept(err == null ? u : null);
        }));
    }

    /** unmute / unban din comanda. */
    public void revokeActive(UUID target, SType type, String adminName, Result cb) {
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE eadmin_sanctions SET active=0, deleted=1, deleted_by=? WHERE player_uuid=? AND type=? AND active=1")) {
                bind(ps, adminName, target.toString(), type.name());
                return ps.executeUpdate();
            }
        }).whenComplete((n, err) -> toMain(() -> {
            if (err != null) {
                cb.done(false, "Eroare DB.");
                return;
            }
            if (type == SType.MUTE) mutes.remove(target);
            cb.done(n > 0, n > 0 ? "Sanctiune revocata." : "Jucatorul nu are " + type.name() + " activ.");
        }));
    }

    // ------------------------------------------------------------------ note

    public void addNote(UUID target, String admin, String text, Runnable then) {
        exec("INSERT INTO eadmin_notes(player_uuid, admin_name, note_text, timestamp) VALUES (?,?,?,?)",
            target.toString(), admin, clip(text, MAX_TEXT), now());
        db.async(c -> null).whenComplete((a, b) -> toMain(then)); // dupa insert (acelasi thread DB, ordonat)
    }

    public void deleteNote(int id, java.util.function.Consumer<UUID> cb) {
        db.async(c -> {
            String uuid = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid FROM eadmin_notes WHERE id=?")) {
                bind(ps, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) uuid = rs.getString(1);
                }
            }
            if (uuid == null) return null;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM eadmin_notes WHERE id=?")) {
                bind(ps, id);
                ps.executeUpdate();
            }
            return UUID.fromString(uuid);
        }).whenComplete((u, err) -> toMain(() -> cb.accept(err == null ? u : null)));
    }

    // ------------------------------------------------------------------ cautare jucator dupa nume

    public CompletableFuture<UUID> findUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(online.getUniqueId());
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid FROM eadmin_players WHERE name=? COLLATE NOCASE ORDER BY last_seen DESC LIMIT 1")) {
                bind(ps, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return UUID.fromString(rs.getString(1));
                }
            }
            return null;
        });
    }

    public CompletableFuture<String> nameOf(UUID u) {
        Player online = Bukkit.getPlayer(u);
        if (online != null) return CompletableFuture.completedFuture(online.getName());
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM eadmin_players WHERE uuid=?")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ raspunsuri pentru panou (payload gata codificat)

    /** UUID-ul jucatorului al carui ID e scris in cautare ("12" sau "#12"), altfel un sir care nu se potriveste. */
    private String idMatch(String q) {
        String d = q.trim();
        if (d.startsWith("#")) d = d.substring(1);
        if (d.matches("\\d{1,9}")) {
            UUID u = plugin.ids().uuidOf(Integer.parseInt(d));
            if (u != null) return u.toString();
        }
        return "-";
    }

    private int idOfUuid(String uuid) {
        try {
            return plugin.ids().idOf(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** Apelat pe thread-ul principal. */
    public CompletableFuture<Wire.Out> playerList(String query, int page) {
        final String q = clip(query, 32);
        final int pg = Math.max(0, page);
        final Set<String> online = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId().toString());
        final long t = now();
        final String idUuid = idMatch(q);
        return db.async(c -> {
            StringBuilder where = new StringBuilder(" WHERE (p.name LIKE ? ESCAPE '\\' OR p.uuid = ?)");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players p" + where)) {
                bind(ps, like, idUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            StringBuilder order = new StringBuilder();
            List<Object> args = new ArrayList<>();
            args.add(t);
            args.add(t);
            args.add(like);
            args.add(idUuid);
            if (!online.isEmpty()) {
                order.append("CASE WHEN p.uuid IN (");
                int i = 0;
                for (String u : online) {
                    order.append(i++ == 0 ? "?" : ",?");
                    args.add(u);
                }
                order.append(") THEN 0 ELSE 1 END, ");
            }
            args.add(PLAYERS_PER_PAGE);
            args.add(pg * PLAYERS_PER_PAGE);
            String sql = "SELECT p.uuid, p.name, p.last_seen, "
                + "(SELECT COUNT(*) FROM eadmin_sanctions s WHERE s.player_uuid=p.uuid AND s.type='BAN' AND s.active=1 AND s.expires_at>?) AS banned, "
                + "(SELECT COUNT(*) FROM eadmin_sanctions s WHERE s.player_uuid=p.uuid AND s.type='MUTE' AND s.active=1 AND s.expires_at>?) AS muted "
                + "FROM eadmin_players p" + where + " ORDER BY " + order + "p.last_seen DESC LIMIT ? OFFSET ?";
            List<Object[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, args.toArray());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Object[] {rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) > 0, rs.getInt(5) > 0});
                    }
                }
            }
            Wire.Out out = new Wire.Out();
            out.i32(total).i32(pg).i32(rows.size());
            for (Object[] r : rows) {
                String uuid = (String) r[0];
                out.utf(uuid).utf((String) r[1]).i32(idOfUuid(uuid)).bool(online.contains(uuid)).i64((Long) r[2]).bool((Boolean) r[3]).bool((Boolean) r[4]);
            }
            return out;
        });
    }

    /** Apelat pe thread-ul principal (citeste date Bukkit), restul in thread-ul DB. */
    public CompletableFuture<Wire.Out> profile(UUID u, boolean showIp) {
        final OfflinePlayer off = Bukkit.getOfflinePlayer(u);
        final Player on = Bukkit.getPlayer(u);
        final double balance = vault.balance(off);
        final Long started = sessionStart.get(u);
        final long t = now();
        return db.async(c -> {
            String name = off.getName() == null ? "?" : off.getName();
            long first = 0;
            long last = 0;
            long play = 0;
            String ip = "";
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, first_seen, last_seen, play_seconds, last_ip FROM eadmin_players WHERE uuid=?")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        name = rs.getString(1);
                        first = rs.getLong(2);
                        last = rs.getLong(3);
                        play = rs.getLong(4);
                        ip = rs.getString(5);
                    }
                }
            }
            if (on != null && started != null) play += Math.max(0, (t - started) / 1000L);
            int warns = 0;
            int mutesN = 0;
            int kicks = 0;
            int bans = 0;
            boolean muted = false;
            boolean banned = false;
            Wire.Out sanc = new Wire.Out();
            int sancCount = 0;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, type, admin_name, reason, duration_minutes, timestamp, expires_at, active, deleted FROM eadmin_sanctions WHERE player_uuid=? ORDER BY id DESC")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String type = rs.getString(2);
                        boolean deleted = rs.getInt(9) != 0;
                        boolean act = rs.getInt(8) != 0 && rs.getLong(7) > t;
                        if (!deleted) {
                            if ("WARN".equals(type)) warns++;
                            else if ("MUTE".equals(type)) mutesN++;
                            else if ("KICK".equals(type)) kicks++;
                            else if ("BAN".equals(type)) bans++;
                        }
                        if (act && "MUTE".equals(type)) muted = true;
                        if (act && "BAN".equals(type)) banned = true;
                        if (sancCount < MAX_LIST) {
                            sanc.i32(rs.getInt(1)).utf(type).utf(rs.getString(3)).utf(rs.getString(4)).i32(rs.getInt(5))
                                .i64(rs.getLong(6)).i64(rs.getLong(7)).bool(act).bool(deleted);
                            sancCount++;
                        }
                    }
                }
            }
            Wire.Out notes = new Wire.Out();
            int noteCount = 0;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, admin_name, note_text, timestamp FROM eadmin_notes WHERE player_uuid=? ORDER BY id DESC LIMIT " + MAX_LIST)) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        notes.i32(rs.getInt(1)).utf(rs.getString(2)).utf(rs.getString(3)).i64(rs.getLong(4));
                        noteCount++;
                    }
                }
            }
            Wire.Out flags = new Wire.Out();
            int flagCount = 0;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT severity, trigger_type, details, timestamp FROM eadmin_flags WHERE player_uuid=? ORDER BY id DESC LIMIT 20")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        flags.utf(rs.getString(1)).utf(rs.getString(2)).utf(rs.getString(3)).i64(rs.getLong(4));
                        flagCount++;
                    }
                }
            }
            Wire.Out out = new Wire.Out();
            out.utf(u.toString()).utf(name).i32(plugin.ids().idOf(u)).bool(on != null).i64(first).i64(last).i64(play)
                .utf(showIp ? ip : "").utf(balance < 0 ? "" : String.format(java.util.Locale.US, "%.2f", balance))
                .bool(muted).bool(banned).i32(warns).i32(mutesN).i32(kicks).i32(bans);
            out.i32(sancCount);
            appendRaw(out, sanc);
            out.i32(noteCount);
            appendRaw(out, notes);
            out.i32(flagCount);
            appendRaw(out, flags);
            return out;
        });
    }

    private static void appendRaw(Wire.Out target, Wire.Out src) {
        for (byte b : src.bytes()) target.u8(b);
    }

    public CompletableFuture<Wire.Out> logs(UUID u, String type, int page) {
        final LogDef def = LOGS.get(type);
        final int pg = Math.max(0, page);
        return db.async(c -> {
            if (def == null) throw new IllegalArgumentException("tip log necunoscut");
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + def.table + " WHERE player_uuid=?")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }
            int pages = Math.max(1, (total + LOGS_PER_PAGE - 1) / LOGS_PER_PAGE);
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                def.select + " WHERE player_uuid=? ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?")) {
                bind(ps, u.toString(), LOGS_PER_PAGE, pg * LOGS_PER_PAGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {String.valueOf(rs.getLong(1)), rs.getString(2), rs.getString(3), rs.getString(4)});
                    }
                }
            }
            Wire.Out out = new Wire.Out();
            out.utf(type).i32(pg).i32(pages).i32(total).i32(rows.size());
            for (String[] r : rows) out.i64(Long.parseLong(r[0])).utf(r[1]).utf(r[2]).utf(r[3]);
            return out;
        });
    }

    // ------------------------------------------------------------------ rezumat text (pentru /eadmin info)

    public void summary(UUID u, java.util.function.Consumer<List<String>> cb) {
        final long t = now();
        db.async(c -> {
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, first_seen, last_seen, play_seconds FROM eadmin_players WHERE uuid=?")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        lines.add("\u00a7b" + rs.getString(1) + " \u00a78| \u00a77prima data: \u00a7f" + formatDate(rs.getLong(2))
                            + " \u00a77ultima: \u00a7f" + formatDate(rs.getLong(3)) + " \u00a77timp jucat: \u00a7f" + (rs.getLong(4) / 3600) + "h");
                    } else {
                        lines.add("\u00a7cJucatorul nu este in baza de date eAdmin.");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, COUNT(*) FROM eadmin_sanctions WHERE player_uuid=? AND deleted=0 GROUP BY type")) {
                bind(ps, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    StringBuilder sb = new StringBuilder("\u00a77Sanctiuni: ");
                    boolean any = false;
                    while (rs.next()) {
                        sb.append("\u00a7f").append(rs.getString(1)).append("\u00a78=\u00a7e").append(rs.getInt(2)).append(' ');
                        any = true;
                    }
                    lines.add(any ? sb.toString() : "\u00a77Sanctiuni: \u00a7fniciuna");
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, reason, expires_at FROM eadmin_sanctions WHERE player_uuid=? AND active=1 AND expires_at>?")) {
                bind(ps, u.toString(), t);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long exp = rs.getLong(3);
                        lines.add("\u00a7c" + rs.getString(1) + " activ \u00a77(" + (exp == Long.MAX_VALUE ? "permanent" : "pana la " + formatDate(exp))
                            + ") \u00a78- \u00a7f" + rs.getString(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM eadmin_notes WHERE player_uuid=?), (SELECT COUNT(*) FROM eadmin_flags WHERE player_uuid=?)")) {
                bind(ps, u.toString(), u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) lines.add("\u00a77Note: \u00a7f" + rs.getInt(1) + " \u00a78| \u00a77Flag-uri: \u00a7f" + rs.getInt(2));
                }
            }
            return lines;
        }).whenComplete((lines, err) -> toMain(() -> cb.accept(err == null ? lines : java.util.Collections.singletonList("\u00a7cEroare DB."))));
    }
}
