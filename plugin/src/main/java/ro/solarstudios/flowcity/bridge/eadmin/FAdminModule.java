package ro.solarstudios.flowcity.bridge.eadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminService.SType;
import ro.solarstudios.flowcity.bridge.eadmin.FAdminLevel.Cap;
import ro.solarstudios.flowcity.bridge.fjobs.FJobsModule;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.module.Modules;

/**
 * FAdmin 2.0 (modul "fadm"): panoul de administrare din joc. Dashboard, jucatori, vehicule, economie, joburi, loguri, sistem, editor de
 * configurari si permisiuni. Toate datele vin de la server; clientul doar afiseaza. Fiecare actiune re-verifica nivelul de staff pe server,
 * cere motiv unde conteaza si se inregistreaza in jurnalul fadm_log.
 */
public final class FAdminModule extends JsonModule {
    private static final int PAGE = 10;
    private static final int VPAGE = 9;
    private static final int LPAGE = 14;

    FAdminLog log;
    private FAdminExt ext;
    private FAdminConfig config;
    private final Deque<String[]> errors = new ConcurrentLinkedDeque<>();
    private Handler handler;

    @Override
    public String id() {
        return "fadm";
    }

    @Override
    protected void onEnable() {
        log = new FAdminLog(plugin, plugin.db());
        config = new FAdminConfig(plugin);
        ext = new FAdminExt(plugin, this, log);
        handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r == null || r.getLevel().intValue() < Level.WARNING.intValue()) return;
                errors.addFirst(new String[] {String.valueOf(r.getMillis()), r.getLevel().getName(), String.valueOf(r.getMessage())});
                while (errors.size() > 40) errors.pollLast();
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        plugin.getLogger().addHandler(handler);

        Commands.register(plugin, "fadmin", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("FAdmin se deschide din joc. Din consola: eadmin info|warn|kick|ban|mute <jucator>");
                return true;
            }
            Player p = (Player) s;
            if (FAdminLevel.of(p) == null) {
                p.sendMessage("§cNu ai acces la FAdmin.");
                return true;
            }
            if (!plugin.channel().hasMod(p)) {
                p.sendMessage("§eNu am detectat modul FlowCityCore în client. Instalează-l pentru panoul FAdmin.");
                return true;
            }
            openHub(p);
            return true;
        }, null, "fa");

        on("me", (p, j) -> me(p));
        on("dash", (p, j) -> dash(p));
        on("players", this::players);
        on("player", this::player);
        on("act", this::act);
        on("vehicles", this::vehicles);
        on("veh_act", this::vehAct);
        on("econ", this::econ);
        on("econ_op", this::econOp);
        on("susp", (p, j) -> susp(p));
        on("jobs", (p, j) -> jobs(p));
        on("job_players", this::jobPlayers);
        on("job_progress", this::jobProgress);
        on("job_set", this::jobSet);
        on("job_grade", this::jobGrade);
        on("logs", this::logs);
        on("system", (p, j) -> system(p));
        on("cfg_cats", (p, j) -> cfgCats(p));
        on("cfg_list", this::cfgList);
        on("cfg_set", this::cfgSet);
        on("perm_list", (p, j) -> permList(p));
        on("perm_set", this::permSet);
        on("classic", this::classic);
        on("mod", (p, j) -> ext.moderation(p, j));
        on("jail_set", (p, j) -> ext.jailSet(p, j));
        on("reports", (p, j) -> ext.reports(p, j));
        on("rep_act", (p, j) -> ext.reportAct(p, j));
        on("staff", (p, j) -> ext.staff(p, j));
        on("staff_hist", (p, j) -> ext.staffHist(p, j));
        on("duty", (p, j) -> ext.dutyToggle(p, j));
        on("announce", (p, j) -> ext.announce(p, j));
        on("lp_groups", (p, j) -> ext.lpGroups(p, j));
        on("lp_user", (p, j) -> ext.lpUser(p, j));
        on("veh_hist", this::vehHist);
        on("tablet", this::tablet);
    }

    @Override
    public void onQuit(Player player) {
        if (ext != null) ext.quit(player);
    }

    @Override
    public void disable() {
        if (handler != null) plugin.getLogger().removeHandler(handler);
    }

    /** Deschide panoul FAdmin pe client (daca jucatorul e staff). */
    public void openHub(Player p) {
        if (FAdminLevel.of(p) == null) {
            plugin.channel().toast(p, "Nu ai acces la FAdmin.", true);
            return;
        }
        send(p, "open", new JsonObject());
    }

    // ------------------------------------------------------------------ helperi

    FAdminLevel level(Player p, Cap need) {
        FAdminLevel lv = FAdminLevel.of(p);
        if (lv == null || (need != null && !FAdminLevel.canSee(p, need))) {
            res(p, false, "Nu ai nivelul necesar pentru această acțiune.");
            return null;
        }
        return lv;
    }

    void snd(Player p, String op, JsonObject o) {
        send(p, op, o);
    }

    void res(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "res", o);
    }

    private static UUID uuid(JsonObject j, String key) {
        try {
            return UUID.fromString(str(j, key, ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    String nameOf(UUID u) {
        String n = plugin.ids().nameOf(u);
        if (n == null || n.isEmpty() || n.equals("?")) {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            n = op.getName() == null ? u.toString().substring(0, 8) : op.getName();
        }
        return n;
    }

    private static String money(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private String jobName(UUID u) {
        FJobsModule fj = FJobsModule.instance;
        if (fj == null) return "";
        String id = fj.adminJobOf(u);
        if (id == null || id.isEmpty()) return "";
        for (String[] d : fj.adminJobs()) if (d[0].equals(id)) return d[1];
        return id;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** Staff-ul nu poate lovi un jucator online cu nivel egal sau mai mare (doar Owner poate). */
    private boolean canTarget(Player actor, FAdminLevel lv, Player target) {
        if (target == null) return true;
        if (actor.getUniqueId().equals(target.getUniqueId())) return true;
        FAdminLevel tl = FAdminLevel.of(target);
        if (lv == FAdminLevel.OWNER) return true;
        if (target.hasPermission("flowcity.eadmin.immune")) {
            res(actor, false, target.getName() + " este imun.");
            return false;
        }
        if (tl != null && tl.atLeast(lv)) {
            res(actor, false, "Nu poți acționa asupra unui staff de nivel egal sau mai mare.");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ me / dashboard

    private void me(Player p) {
        FAdminLevel lv = FAdminLevel.of(p);
        JsonObject o = new JsonObject();
        o.addProperty("allowed", lv != null);
        if (lv == null) {
            send(p, "me", o);
            return;
        }
        o.addProperty("level", lv.id);
        o.addProperty("label", lv.label);
        o.addProperty("rank", lv.ordinal());
        o.addProperty("name", p.getName());
        o.addProperty("moneyLimit", lv.moneyLimit());
        JsonArray caps = new JsonArray();
        for (Cap c : Cap.values()) if (FAdminLevel.canSee(p, c)) caps.add(c.id());
        o.add("caps", caps);
        JsonArray lvls = new JsonArray();
        for (FAdminLevel l : FAdminLevel.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", l.id);
            e.addProperty("label", l.label);
            JsonArray add = new JsonArray();
            for (Cap c : Cap.values()) if (c.min == l) add.add(c.id());
            e.add("adds", add);
            lvls.add(e);
        }
        o.add("levels", lvls);
        o.addProperty("vault", plugin.vault().available());
        send(p, "me", o);
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

    private void dash(Player p) {
        if (level(p, Cap.DASH) == null) return;
        final long now = System.currentTimeMillis();
        final long day = now - TimeUnit.DAYS.toMillis(1);
        final JsonObject o = new JsonObject();
        int staff = 0;
        for (Player q : Bukkit.getOnlinePlayers()) if (FAdminLevel.of(q) != null) staff++;
        Runtime rt = Runtime.getRuntime();
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("max", Bukkit.getMaxPlayers());
        o.addProperty("staff", staff);
        o.addProperty("tps", tps());
        o.addProperty("ramUsed", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
        o.addProperty("ramMax", rt.maxMemory() / 1048576L);
        o.addProperty("vehicles", MdtModule.instance == null ? 0 : MdtModule.instance.adminVehicleCount());
        ext.dashNow(o);
        final List<UUID> econIds = new ArrayList<>();
        plugin.db().async(c -> {
            ext.dashDb(o, c);
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM eadmin_players ORDER BY last_seen DESC LIMIT 2000");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        econIds.add(UUID.fromString(rs.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // uuid corupt
                    }
                }
            }
            JsonArray recent = new JsonArray();
            JsonArray acts = new JsonArray();
            JsonArray notes = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) o.addProperty("total", rs.getInt(1));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players WHERE first_seen>?")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("newToday", rs.getInt(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players WHERE last_seen>?")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) o.addProperty("active24", rs.getInt(1));
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
            try (PreparedStatement ps = c.prepareStatement("SELECT name, last_seen, uuid FROM eadmin_players ORDER BY last_seen DESC LIMIT 8");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("name", rs.getString(1));
                    r.addProperty("ts", rs.getLong(2));
                    UUID u = null;
                    try {
                        u = UUID.fromString(rs.getString(3));
                    } catch (IllegalArgumentException ignored) {
                        // uuid corupt
                    }
                    r.addProperty("online", u != null && Bukkit.getPlayer(u) != null);
                    recent.add(r);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, admin, action, target_name FROM fadm_log ORDER BY id DESC LIMIT 7");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("ts", rs.getLong(1));
                    r.addProperty("admin", rs.getString(2));
                    r.addProperty("action", rs.getString(3));
                    r.addProperty("target", rs.getString(4));
                    acts.add(r);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT f.severity, f.trigger_type, COALESCE(p.name, '?'), f.details, f.timestamp FROM eadmin_flags f "
                    + "LEFT JOIN eadmin_players p ON p.uuid = f.player_uuid WHERE f.timestamp>? ORDER BY f.id DESC LIMIT 5")) {
                ps.setLong(1, day);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("sev", rs.getString(1));
                        r.addProperty("text", rs.getString(2) + " — " + rs.getString(3) + ": " + clip(rs.getString(4), 60));
                        r.addProperty("ts", rs.getLong(5));
                        notes.add(r);
                    }
                }
            }
            o.add("recent", recent);
            o.add("actions", acts);
            o.add("notices", notes);
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                res(p, false, "Nu am putut încărca dashboard-ul.");
                return;
            }
            if (FAdminLevel.canSee(p, Cap.ECON_VIEW)) ext.totals(o, econIds);
            send(p, "dash", o);
        }));
    }

    // ------------------------------------------------------------------ jucatori

    private void players(Player p, JsonObject j) {
        if (level(p, Cap.PLAYERS) == null) return;
        final String q = clip(str(j, "q", ""), 24);
        final int page = Math.max(0, (int) num(j, "page", 0));
        plugin.db().async(c -> {
            List<UUID> ids = new ArrayList<>();
            int[] total = {0};
            String like = "%" + q.replace("%", "") + "%";
            UUID byId = null;
            if (q.matches("\\d{1,6}")) byId = plugin.ids().uuidOf(Integer.parseInt(q));
            String where = q.isEmpty() ? "" : " WHERE name LIKE ?" + (byId != null ? " OR uuid=?" : "");
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM eadmin_players" + where)) {
                int i = 1;
                if (!q.isEmpty()) ps.setString(i++, like);
                if (!q.isEmpty() && byId != null) ps.setString(i, byId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total[0] = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM eadmin_players" + where + " ORDER BY last_seen DESC LIMIT ? OFFSET ?")) {
                int i = 1;
                if (!q.isEmpty()) ps.setString(i++, like);
                if (!q.isEmpty() && byId != null) ps.setString(i++, byId.toString());
                ps.setInt(i++, PAGE);
                ps.setInt(i, page * PAGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            ids.add(UUID.fromString(rs.getString(1)));
                        } catch (IllegalArgumentException ignored) {
                            // uuid corupt
                        }
                    }
                }
            }
            Set<String> banned = new HashSet<>();
            Set<String> muted = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT player_uuid, type FROM eadmin_sanctions WHERE active=1 AND deleted=0 AND expires_at>? AND type IN ('BAN','MUTE')")) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) (rs.getString(2).equals("BAN") ? banned : muted).add(rs.getString(1));
                }
            }
            Map<UUID, Long> play = new HashMap<>();
            for (UUID u : ids) {
                try (PreparedStatement ps = c.prepareStatement("SELECT play_seconds FROM eadmin_players WHERE uuid=?")) {
                    ps.setString(1, u.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) play.put(u, rs.getLong(1));
                    }
                }
            }
            Object[] r = {ids, total[0], banned, muted, play};
            return r;
        }).whenComplete((r, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || r == null) {
                res(p, false, "Nu am putut încărca jucătorii.");
                return;
            }
            @SuppressWarnings("unchecked") List<UUID> ids = (List<UUID>) r[0];
            @SuppressWarnings("unchecked") Set<String> banned = (Set<String>) r[2];
            @SuppressWarnings("unchecked") Set<String> muted = (Set<String>) r[3];
            @SuppressWarnings("unchecked") Map<UUID, Long> play = (Map<UUID, Long>) r[4];
            final int totalRows = (Integer) r[1];
            ext.ranks(ids).whenComplete((ranks, rerr) -> Bukkit.getScheduler().runTask(plugin, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("q", q);
                o.addProperty("page", page);
                o.addProperty("total", totalRows);
                JsonArray rows = new JsonArray();
                Map<UUID, Integer> carCount = new HashMap<>();
                if (MdtModule.instance != null) for (MdtModule.AdminVeh v : MdtModule.instance.adminVehicles()) carCount.merge(v.owner, 1, Integer::sum);
                for (UUID u : ids) {
                    JsonObject row = playerRow(u, carCount, banned, muted);
                    String rk = ranks == null ? null : ranks.get(u);
                    row.addProperty("rank", rk == null ? "-" : rk);
                    Player on = Bukkit.getPlayer(u);
                    long sec = on != null ? on.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20L : play.getOrDefault(u, 0L);
                    row.addProperty("play", sec);
                    row.addProperty("jailed", ext.isJailed(u));
                    rows.add(row);
                }
                o.add("rows", rows);
                send(p, "players", o);
            }));
        }));
    }

    private JsonObject playerRow(UUID u, Map<UUID, Integer> cars, Set<String> banned, Set<String> muted) {
        JsonObject e = new JsonObject();
        Player on = Bukkit.getPlayer(u);
        e.addProperty("uuid", u.toString());
        e.addProperty("name", nameOf(u));
        e.addProperty("id", plugin.ids().idOf(u));
        e.addProperty("online", on != null);
        e.addProperty("job", jobName(u));
        e.addProperty("cash", plugin.money().balance(u));
        e.addProperty("bank", plugin.bank().balance(u));
        e.addProperty("cars", cars.getOrDefault(u, 0));
        e.addProperty("banned", banned.contains(u.toString()));
        e.addProperty("muted", muted.contains(u.toString()));
        return e;
    }

    private void player(Player p, JsonObject j) {
        if (level(p, Cap.PLAYERS) == null) return;
        final UUID u = uuid(j, "uuid");
        if (u == null) return;
        plugin.db().async(c -> {
            JsonObject o = new JsonObject();
            JsonArray hist = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT first_seen, last_seen, play_seconds FROM eadmin_players WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        o.addProperty("first", rs.getLong(1));
                        o.addProperty("last", rs.getLong(2));
                        o.addProperty("playSeconds", rs.getLong(3));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, admin_name, reason, timestamp, duration_minutes FROM eadmin_sanctions WHERE player_uuid=? AND deleted=0 ORDER BY id DESC LIMIT 8")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject h = new JsonObject();
                        h.addProperty("ts", rs.getLong(4));
                        h.addProperty("kind", rs.getString(1));
                        int m = rs.getInt(5);
                        h.addProperty("text", rs.getString(2) + (m > 0 ? " (" + EAdminService.describeMinutes(m) + ")" : "") + ": " + clip(rs.getString(3), 60));
                        hist.add(h);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, admin, action, old_val, new_val, reason FROM fadm_log WHERE target_uuid=? ORDER BY id DESC LIMIT 8")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject h = new JsonObject();
                        h.addProperty("ts", rs.getLong(1));
                        h.addProperty("kind", rs.getString(3).toUpperCase(Locale.ROOT));
                        String ch = rs.getString(4).isEmpty() ? "" : " [" + rs.getString(4) + " → " + rs.getString(5) + "]";
                        h.addProperty("text", rs.getString(2) + ch + (rs.getString(6).isEmpty() ? "" : ": " + clip(rs.getString(6), 50)));
                        hist.add(h);
                    }
                }
            }
            o.add("history", hist);
            o.addProperty("reports", ext.reportsAgainst(c, u));
            return o;
        }).whenComplete((o, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || o == null) {
                res(p, false, "Nu am putut încărca profilul.");
                return;
            }
            Player on = Bukkit.getPlayer(u);
            o.addProperty("uuid", u.toString());
            o.addProperty("name", nameOf(u));
            o.addProperty("id", plugin.ids().idOf(u));
            o.addProperty("online", on != null);
            o.addProperty("job", jobName(u));
            o.addProperty("cash", plugin.money().balance(u));
            o.addProperty("bank", plugin.bank().balance(u));
            ext.profileFlags(o, u);
            String rk = LpApi.primary(u);
            o.addProperty("rank", rk == null ? "-" : rk);
            o.addProperty("mySpec", AdminPlusModule.instance != null && AdminPlusModule.instance.isSpectating(p.getUniqueId()));
            JsonArray cars = new JsonArray();
            if (MdtModule.instance != null) {
                for (MdtModule.AdminVeh v : MdtModule.instance.adminVehicles()) {
                    if (!v.owner.equals(u)) continue;
                    JsonObject e = new JsonObject();
                    e.addProperty("id", v.id);
                    e.addProperty("model", v.model);
                    e.addProperty("plate", v.plate);
                    e.addProperty("status", v.status);
                    cars.add(e);
                }
            }
            o.add("cars", cars);
            if (on != null) {
                o.addProperty("health", Math.round(on.getHealth()));
                o.addProperty("world", on.getWorld().getName());
                o.addProperty("pos", on.getLocation().getBlockX() + ", " + on.getLocation().getBlockY() + ", " + on.getLocation().getBlockZ());
            }
            send(p, "player", o);
        }));
    }

    private void act(Player p, JsonObject j) {
        final String action = str(j, "action", "");
        final UUID u = uuid(j, "uuid");
        if (u == null) return;
        final String reasonIn = clip(str(j, "reason", ""), 160);
        // in moderare motivul e optional (spec): fara motiv se inregistreaza o valoare implicita
        final String reason = reasonIn.isEmpty() && java.util.Arrays.asList("kick", "mute", "ban", "warn", "jail").contains(action) ? "Fără motiv specificat" : reasonIn;
        final int minutes = Math.max(0, Math.min(525600, (int) num(j, "minutes", 0)));
        Cap need;
        switch (action) {
            case "kick": need = Cap.KICK; break;
            case "mute": case "unmute": need = Cap.MUTE; break;
            case "ban": need = Cap.BAN; break;
            case "unban": need = Cap.UNBAN; break;
            case "tp": need = Cap.TP; break;
            case "bring": need = Cap.BRING; break;
            case "heal": need = Cap.HEAL; break;
            case "inv": need = Cap.INV; break;
            case "warn": need = Cap.WARN; break;
            case "freeze": need = Cap.FREEZE; break;
            case "spectate": need = Cap.SPECTATE; break;
            case "jail": case "unjail": need = Cap.JAIL; break;
            case "setid": need = Cap.RPID; break;
            case "grp_add": case "grp_remove": need = Cap.RANK; break;
            default:
                res(p, false, "Acțiune necunoscută.");
                return;
        }
        FAdminLevel lv = level(p, need);
        if (lv == null) return;
        final String tname = nameOf(u);
        Player t = Bukkit.getPlayer(u);
        if (!action.equals("unban") && !action.equals("unmute") && !action.equals("unjail") && !canTarget(p, lv, t)) return;
        EAdminService svc = plugin.service();
        EAdminService.Result cb = (ok, msg) -> {
            if (ok) log.add(p.getName(), lv, action, u, tname, "", minutes > 0 ? EAdminService.describeMinutes(minutes) : "", reason);
            res(p, ok, msg);
        };
        switch (action) {
            case "kick":
                svc.applySanction(p.getName(), u, tname, SType.KICK, 0, reason, cb);
                break;
            case "mute":
                svc.applySanction(p.getName(), u, tname, SType.MUTE, minutes, reason, cb);
                break;
            case "ban":
                svc.applySanction(p.getName(), u, tname, SType.BAN, minutes, reason, cb);
                break;
            case "unmute":
                svc.revokeActive(u, SType.MUTE, p.getName(), cb);
                break;
            case "unban":
                svc.revokeActive(u, SType.BAN, p.getName(), cb);
                break;
            case "tp":
                if (t == null) {
                    res(p, false, "Jucătorul nu e online.");
                    return;
                }
                p.teleport(t.getLocation());
                log.add(p.getName(), lv, "tp", u, tname, "", "", reason);
                res(p, true, "Teleportat la " + tname + ".");
                break;
            case "bring":
                if (t == null) {
                    res(p, false, "Jucătorul nu e online.");
                    return;
                }
                t.teleport(p.getLocation());
                log.add(p.getName(), lv, "bring", u, tname, "", "", reason);
                res(p, true, tname + " a fost adus la tine.");
                break;
            case "heal":
                if (t == null) {
                    res(p, false, "Jucătorul nu e online.");
                    return;
                }
                double before = t.getHealth();
                t.setHealth(t.getMaxHealth());
                t.setFoodLevel(20);
                t.setFireTicks(0);
                log.add(p.getName(), lv, "heal", u, tname, String.valueOf(Math.round(before)), String.valueOf(Math.round(t.getHealth())), reason);
                res(p, true, tname + " a fost vindecat.");
                break;
            case "warn":
                svc.applySanction(p.getName(), u, tname, SType.WARN, 0, reason, cb);
                break;
            case "freeze": {
                AdminPlusModule ap = AdminPlusModule.instance;
                if (ap == null) {
                    res(p, false, "Modulul de moderare nu e pornit.");
                    return;
                }
                int r = ap.doFreeze(p, u);
                if (r < 0) return;
                log.add(p.getName(), lv, "freeze", u, tname, r == 1 ? "liber" : "înghețat", r == 1 ? "înghețat" : "liber", reason);
                res(p, true, tname + (r == 1 ? " a fost înghețat." : " a fost dezghețat."));
                break;
            }
            case "spectate": {
                AdminPlusModule ap = AdminPlusModule.instance;
                if (ap == null) {
                    res(p, false, "Modulul de moderare nu e pornit.");
                    return;
                }
                int r = ap.doSpec(p, u);
                if (r < 0) return;
                log.add(p.getName(), lv, "spectate", u, tname, "", r == 1 ? "spectate" : "ieșit", reason);
                res(p, true, r == 1 ? "Spectate " + tname + " (apasă din nou ca să te întorci)." : "Ai ieșit din spectate.");
                break;
            }
            case "jail":
                ext.jail(p, lv, u, tname, minutes, reason);
                break;
            case "unjail":
                ext.unjail(p, lv, u, tname, reason);
                break;
            case "setid": {
                final int newId = (int) num(j, "id", 0);
                final int oldId = plugin.ids().idOf(u);
                plugin.ids().setId(u, newId, bool(j, "swap", false), (ok, msg) -> {
                    if (ok) log.add(p.getName(), lv, "setid", u, tname, "#" + oldId, "#" + newId, reason);
                    res(p, ok, msg);
                });
                break;
            }
            case "grp_add":
            case "grp_remove":
                ext.lpGroupSet(p, lv, u, tname, clip(str(j, "group", ""), 32), action.equals("grp_add"), reason);
                break;
            default: // inv
                if (t == null) {
                    res(p, false, "Doar jucătorii online pot fi inspectați.");
                    return;
                }
                p.openInventory(t.getInventory());
                log.add(p.getName(), lv, "inv", u, tname, "", "", "");
                break;
        }
    }

    // ------------------------------------------------------------------ vehicule

    private void vehicles(Player p, JsonObject j) {
        if (level(p, Cap.VEH_VIEW) == null) return;
        final String q = clip(str(j, "q", ""), 24).toLowerCase(Locale.ROOT);
        final int page = Math.max(0, (int) num(j, "page", 0));
        JsonObject o = new JsonObject();
        JsonArray rows = new JsonArray();
        int total = 0;
        if (MdtModule.instance != null) {
            int skip = page * VPAGE;
            for (MdtModule.AdminVeh v : MdtModule.instance.adminVehicles()) {
                if (!q.isEmpty() && !(v.ownerName.toLowerCase(Locale.ROOT).contains(q) || v.plate.toLowerCase(Locale.ROOT).contains(q)
                    || v.model.toLowerCase(Locale.ROOT).contains(q) || String.valueOf(v.id).equals(q))) continue;
                total++;
                if (skip > 0) {
                    skip--;
                    continue;
                }
                if (rows.size() >= VPAGE) continue;
                JsonObject e = new JsonObject();
                e.addProperty("id", v.id);
                e.addProperty("owner", v.ownerName);
                e.addProperty("ownerUuid", v.owner.toString());
                e.addProperty("model", v.model);
                e.addProperty("plate", v.plate);
                e.addProperty("status", v.status);
                rows.add(e);
            }
        }
        o.addProperty("q", q);
        o.addProperty("page", page);
        o.addProperty("total", total);
        o.add("rows", rows);
        send(p, "vehicles", o);
    }

    /** Da staff-ului Admin Tabletul (item din mod; deschide FAdmin la click dreapta). Doar Administrator+, jurnalizat. */
    private void tablet(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.ANNOUNCE);
        if (lv == null) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + p.getName() + " flowcitycore:admin_tablet 1");
        log.add(p.getName(), lv, "tablet", p.getUniqueId(), p.getName(), "", "admin_tablet", "");
        res(p, true, "Ai primit Admin Tabletul.");
    }

    /** Istoricul actiunilor de staff asupra unui vehicul (din jurnalul persistent, dupa numarul de inmatriculare). */
    private void vehHist(Player p, JsonObject j) {
        if (level(p, Cap.VEH_VIEW) == null) return;
        final String plate = clip(str(j, "plate", ""), 16);
        if (plate.isEmpty()) return;
        plugin.db().async(c -> {
            JsonArray rows = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT ts, admin, action, old_val, new_val, reason FROM fadm_log WHERE action LIKE 'veh\\_%' ESCAPE '\\' AND target_name LIKE ? ORDER BY id DESC LIMIT 8")) {
                ps.setString(1, "%" + plate + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("ts", rs.getLong(1));
                        r.addProperty("admin", rs.getString(2));
                        r.addProperty("action", rs.getString(3));
                        r.addProperty("old", rs.getString(4));
                        r.addProperty("new", rs.getString(5));
                        r.addProperty("reason", rs.getString(6));
                        rows.add(r);
                    }
                }
            }
            return rows;
        }).whenComplete((rows, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || rows == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("plate", plate);
            o.add("rows", rows);
            send(p, "veh_hist", o);
        }));
    }

    private void vehAct(Player p, JsonObject j) {
        final String action = str(j, "action", "");
        final long id = (long) num(j, "id", -1);
        final String reason = clip(str(j, "reason", ""), 160);
        Cap need = action.equals("delete") ? Cap.VEH_DELETE : Cap.VEH_STATUS;
        FAdminLevel lv = level(p, need);
        if (lv == null) return;
        MdtModule mdt = MdtModule.instance;
        if (mdt == null) {
            res(p, false, "Evidența vehiculelor (MDT) nu e pornită.");
            return;
        }
        if (reason.length() < 3) {
            res(p, false, "Scrie un motiv (minim 3 caractere).");
            return;
        }
        String what;
        String old = "";
        for (MdtModule.AdminVeh v : mdt.adminVehicles()) if (v.id == id) old = v.status;
        switch (action) {
            case "confiscate": what = mdt.adminSetStatus(id, "Confiscated"); break;
            case "release": what = mdt.adminSetStatus(id, "Clean"); break;
            case "delete": what = mdt.adminDelete(id); break;
            default:
                res(p, false, "Acțiune necunoscută.");
                return;
        }
        if (what == null) {
            res(p, false, "Vehiculul nu mai există.");
            return;
        }
        log.add(p.getName(), lv, "veh_" + action, null, what, old, action.equals("delete") ? "șters" : action.equals("release") ? "Clean" : "Confiscated", reason);
        res(p, true, action.equals("delete") ? "Vehicul șters din evidență: " + what : "Stare actualizată: " + what);
        vehicles(p, new JsonObject());
    }

    // ------------------------------------------------------------------ economie

    private void econ(Player p, JsonObject j) {
        if (level(p, Cap.ECON_VIEW) == null) return;
        final UUID u = uuid(j, "uuid");
        if (u == null) return;
        plugin.db().async(c -> {
            JsonArray rows = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, amount, target_name, timestamp FROM eadmin_transactions WHERE player_uuid=? ORDER BY id DESC LIMIT 30")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("type", rs.getString(1));
                        r.addProperty("amount", rs.getDouble(2));
                        r.addProperty("target", rs.getString(3));
                        r.addProperty("ts", rs.getLong(4));
                        rows.add(r);
                    }
                }
            }
            return rows;
        }).whenComplete((rows, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", u.toString());
            o.addProperty("name", nameOf(u));
            o.addProperty("cash", plugin.money().balance(u));
            o.addProperty("bank", plugin.bank().balance(u));
            o.add("rows", rows == null ? new JsonArray() : rows);
            send(p, "econ", o);
        }));
    }

    private void econOp(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.ECON_MODIFY);
        if (lv == null) return;
        final UUID u = uuid(j, "uuid");
        if (u == null) return;
        final String account = str(j, "account", "CASH").toUpperCase(Locale.ROOT);
        final String op = str(j, "op", "add");
        final String reason = clip(str(j, "reason", ""), 160);
        double amount = Math.round(num(j, "amount", 0) * 100.0) / 100.0;
        if (!(account.equals("CASH") || account.equals("BANK")) || !(op.equals("add") || op.equals("remove"))) {
            res(p, false, "Cerere invalidă.");
            return;
        }
        if (Double.isNaN(amount) || amount <= 0) {
            res(p, false, "Suma trebuie să fie pozitivă.");
            return;
        }
        if (amount > lv.moneyLimit()) {
            res(p, false, "Nivelul tău poate muta cel mult " + (long) lv.moneyLimit() + " pe operațiune.");
            return;
        }
        if (reason.length() < 3) {
            res(p, false, "Scrie un motiv (minim 3 caractere).");
            return;
        }
        boolean cash = account.equals("CASH");
        double cur = cash ? plugin.money().balance(u) : plugin.bank().balance(u);
        double delta = op.equals("add") ? amount : -Math.min(amount, cur);
        if (delta == 0) {
            res(p, false, "Soldul este deja 0.");
            return;
        }
        boolean ok;
        if (cash) ok = delta > 0 ? plugin.money().deposit(u, delta) : plugin.money().withdraw(u, -delta);
        else ok = delta > 0 ? plugin.bank().deposit(u, delta) : plugin.bank().withdraw(u, -delta);
        if (!ok) {
            res(p, false, "Operațiunea a eșuat (economie).");
            return;
        }
        String tname = nameOf(u);
        plugin.service().logTransaction(u, "FADMIN_" + account + "_" + op.toUpperCase(Locale.ROOT), delta, p.getName(), "");
        log.add(p.getName(), lv, "econ_" + account.toLowerCase(Locale.ROOT) + "_" + op, u, tname, money(cur), money(cur + delta), reason);
        res(p, true, account + " " + (delta > 0 ? "+" : "") + money(delta) + " pentru " + tname + " (acum " + money(cur + delta) + ").");
        JsonObject again = new JsonObject();
        again.addProperty("uuid", u.toString());
        econ(p, again);
    }

    private void susp(Player p) {
        if (level(p, Cap.ECON_VIEW) == null) return;
        final double threshold = Math.max(1, plugin.getConfig().getDouble("fadmin.suspicious-threshold", 50000));
        final long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        plugin.db().async(c -> {
            JsonArray rows = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT t.player_uuid, COALESCE(p.name,'?'), t.type, t.amount, t.target_name, t.timestamp FROM eadmin_transactions t "
                    + "LEFT JOIN eadmin_players p ON p.uuid=t.player_uuid WHERE t.timestamp>? AND ABS(t.amount)>=? ORDER BY ABS(t.amount) DESC LIMIT 25")) {
                ps.setLong(1, since);
                ps.setDouble(2, threshold);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("uuid", rs.getString(1));
                        r.addProperty("name", rs.getString(2));
                        r.addProperty("type", rs.getString(3));
                        r.addProperty("amount", rs.getDouble(4));
                        r.addProperty("target", rs.getString(5));
                        r.addProperty("ts", rs.getLong(6));
                        rows.add(r);
                    }
                }
            }
            return rows;
        }).whenComplete((rows, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("threshold", threshold);
            o.add("rows", rows == null ? new JsonArray() : rows);
            send(p, "susp", o);
        }));
    }

    // ------------------------------------------------------------------ joburi

    private void jobs(Player p) {
        if (level(p, Cap.JOB_VIEW) == null) return;
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        FJobsModule fj = FJobsModule.instance;
        if (fj != null) {
            for (String[] d : fj.adminJobs()) {
                JsonObject e = new JsonObject();
                e.addProperty("id", d[0]);
                e.addProperty("name", d[1]);
                e.addProperty("active", "1".equals(d[2]));
                e.addProperty("employees", fj.adminEmployees(d[0]).size());
                a.add(e);
            }
        }
        o.add("jobs", a);
        send(p, "jobs", o);
    }

    private void jobPlayers(Player p, JsonObject j) {
        if (level(p, Cap.JOB_VIEW) == null || FJobsModule.instance == null) return;
        String id = str(j, "id", "");
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        JsonArray a = new JsonArray();
        for (String[] e : FJobsModule.instance.adminEmployees(id)) {
            JsonObject r = new JsonObject();
            r.addProperty("uuid", e[0]);
            r.addProperty("name", e[1]);
            r.addProperty("level", Integer.parseInt(e[2]));
            r.addProperty("xp", Long.parseLong(e[3]));
            r.addProperty("earned", Double.parseDouble(e[4]));
            r.addProperty("shift", "1".equals(e[5]));
            a.add(r);
        }
        o.add("rows", a);
        send(p, "job_players", o);
    }

    private void jobProgress(Player p, JsonObject j) {
        if (level(p, Cap.JOB_VIEW) == null || FJobsModule.instance == null) return;
        UUID u = uuid(j, "uuid");
        if (u == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", u.toString());
        o.addProperty("name", nameOf(u));
        JsonArray a = new JsonArray();
        for (String[] e : FJobsModule.instance.adminProgress(u)) {
            JsonObject r = new JsonObject();
            r.addProperty("id", e[0]);
            r.addProperty("name", e[1]);
            r.addProperty("level", Integer.parseInt(e[2]));
            r.addProperty("xp", Long.parseLong(e[3]));
            r.addProperty("earned", Double.parseDouble(e[4]));
            r.addProperty("shifts", Integer.parseInt(e[5]));
            r.addProperty("missions", Integer.parseInt(e[6]));
            a.add(r);
        }
        o.addProperty("job", jobName(u));
        o.add("rows", a);
        send(p, "job_progress", o);
    }

    private void jobSet(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.SET_JOB);
        if (lv == null || FJobsModule.instance == null) return;
        UUID u = uuid(j, "uuid");
        if (u == null) return;
        String job = str(j, "job", "");
        String reason = clip(str(j, "reason", ""), 160);
        if (reason.length() < 3) {
            res(p, false, "Scrie un motiv (minim 3 caractere).");
            return;
        }
        if (!canTarget(p, lv, Bukkit.getPlayer(u))) return;
        String old = jobName(u);
        String err = FJobsModule.instance.adminSetJob(u, nameOf(u), job, p.getName());
        if (err != null) {
            res(p, false, err);
            return;
        }
        log.add(p.getName(), lv, "job_set", u, nameOf(u), old, jobName(u), reason);
        res(p, true, nameOf(u) + ": " + (jobName(u).isEmpty() ? "fără job" : jobName(u)));
        JsonObject again = new JsonObject();
        again.addProperty("uuid", u.toString());
        jobProgress(p, again);
    }

    private void jobGrade(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.JOB_GRADE);
        if (lv == null || FJobsModule.instance == null) return;
        UUID u = uuid(j, "uuid");
        if (u == null) return;
        String job = str(j, "job", "");
        int grade = (int) num(j, "level", 1);
        String reason = clip(str(j, "reason", ""), 160);
        if (reason.length() < 3) {
            res(p, false, "Scrie un motiv (minim 3 caractere).");
            return;
        }
        if (!canTarget(p, lv, Bukkit.getPlayer(u))) return;
        int old = FJobsModule.instance.adminSetLevel(u, nameOf(u), job, grade);
        if (old < 0) {
            res(p, false, "Job necunoscut.");
            return;
        }
        log.add(p.getName(), lv, "job_grade", u, nameOf(u), job + " " + old, job + " " + Math.max(1, grade), reason);
        res(p, true, "Grad actualizat: " + job + " " + old + " → " + Math.max(1, grade));
        JsonObject again = new JsonObject();
        again.addProperty("uuid", u.toString());
        jobProgress(p, again);
    }

    // ------------------------------------------------------------------ loguri / sistem

    private void logs(Player p, JsonObject j) {
        if (level(p, Cap.LOGS) == null) return;
        final String q = clip(str(j, "q", ""), 24);
        final int page = Math.max(0, (int) num(j, "page", 0));
        final int[] total = {0};
        log.query(q, null, page * LPAGE, LPAGE, total).whenComplete((rows, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || rows == null) {
                res(p, false, "Nu am putut încărca jurnalul.");
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("q", q);
            o.addProperty("page", page);
            o.addProperty("total", total[0]);
            JsonArray a = new JsonArray();
            for (FAdminLog.Row r : rows) {
                JsonObject e = new JsonObject();
                e.addProperty("ts", r.ts);
                e.addProperty("admin", r.admin);
                e.addProperty("adminUuid", r.adminUuid);
                e.addProperty("level", r.level);
                e.addProperty("action", r.action);
                e.addProperty("target", r.target);
                e.addProperty("old", r.oldVal);
                e.addProperty("new", r.newVal);
                e.addProperty("reason", r.reason);
                a.add(e);
            }
            o.add("rows", a);
            send(p, "logs", o);
        }));
    }

    private void system(Player p) {
        FAdminLevel lv = level(p, Cap.SERVER_VIEW);
        if (lv == null) return;
        final boolean sensitive = FAdminLevel.canSee(p, Cap.SYSTEM);
        final JsonObject o = new JsonObject();
        o.addProperty("sensitive", sensitive);
        o.addProperty("tps", tps());
        Runtime rt = Runtime.getRuntime();
        o.addProperty("ramUsed", (rt.totalMemory() - rt.freeMemory()) / 1048576L);
        o.addProperty("ramMax", rt.maxMemory() / 1048576L);
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("max", Bukkit.getMaxPlayers());
        int chunks = 0;
        int entities = 0;
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            chunks += w.getLoadedChunks().length;
            entities += w.getEntities().size();
        }
        o.addProperty("chunks", chunks);
        o.addProperty("entities", entities);
        o.addProperty("worlds", Bukkit.getWorlds().size());
        o.addProperty("vault", plugin.vault().available());
        o.addProperty("luckperms", LpApi.available());
        o.addProperty("jailCell", ext.cellSet());
        o.addProperty("modulesOn", Modules.ids().size());
        if (sensitive) {
            o.addProperty("economy", plugin.vault().available() ? "Vault (" + plugin.vault().provider() + ")" : "registru intern");
            o.addProperty("version", plugin.getDescription().getVersion());
            JsonArray mods = new JsonArray();
            for (String m : Modules.ids()) mods.add(m);
            o.add("modules", mods);
            JsonArray errs = new JsonArray();
            for (String[] e : errors) {
                JsonObject r = new JsonObject();
                r.addProperty("ts", Long.parseLong(e[0]));
                r.addProperty("level", e[1]);
                r.addProperty("text", clip(e[2], 140));
                errs.add(r);
                if (errs.size() >= 12) break;
            }
            o.add("errors", errs);
        }
        plugin.db().async(c -> {
            ext.recentAnnouncements(o, c);
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> send(p, "system", o)));
    }

    // ------------------------------------------------------------------ editor

    private void cfgCats(Player p) {
        FAdminLevel lv = FAdminLevel.of(p);
        if (lv == null) return;
        JsonObject o = new JsonObject();
        o.add("cats", config.categories(lv));
        send(p, "cfg_cats", o);
    }

    private void cfgList(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.CFG_BASIC);
        if (lv == null) return;
        String cat = str(j, "cat", "");
        JsonObject o = new JsonObject();
        o.addProperty("cat", cat);
        o.add("rows", config.list(lv, cat));
        send(p, "cfg_list", o);
    }

    private void cfgSet(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.CFG_BASIC);
        if (lv == null) return;
        String key = str(j, "key", "");
        String value = str(j, "value", "");
        String reason = clip(str(j, "reason", ""), 160);
        if (reason.length() < 3) {
            res(p, false, "Scrie un motiv (minim 3 caractere).");
            return;
        }
        FAdminConfig.SetResult r = config.set(lv, key, value);
        if (r.error != null) {
            res(p, false, r.error);
            return;
        }
        log.add(p.getName(), lv, "cfg_set", null, key, r.oldValue, r.newValue, reason);
        res(p, true, key.substring(key.indexOf(':') + 1) + ": " + r.oldValue + " → " + r.newValue + ". " + r.note);
        JsonObject again = new JsonObject();
        again.addProperty("cat", str(j, "cat", ""));
        cfgList(p, again);
    }

    // ------------------------------------------------------------------ permisiuni

    private void permList(Player p) {
        if (level(p, Cap.PERM_VIEW) == null) return;
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        for (Player q : Bukkit.getOnlinePlayers()) {
            FAdminLevel l = FAdminLevel.of(q);
            if (l == null) continue;
            JsonObject e = new JsonObject();
            e.addProperty("name", q.getName());
            e.addProperty("level", l.id);
            e.addProperty("label", l.label);
            e.addProperty("op", q.isOp());
            a.add(e);
        }
        o.add("staff", a);
        o.addProperty("luckperms", Bukkit.getPluginManager().getPlugin("LuckPerms") != null);
        send(p, "perm_list", o);
    }

    private void permSet(Player p, JsonObject j) {
        FAdminLevel lv = level(p, Cap.PERM_EDIT);
        if (lv == null) return;
        final String name = clip(str(j, "name", ""), 16);
        final String levelId = str(j, "level", "");
        final FAdminLevel target = FAdminLevel.parse(levelId);
        if (!name.matches("[A-Za-z0-9_]{3,16}") || (target == null && !levelId.equals("none"))) {
            res(p, false, "Nume sau nivel invalid.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            res(p, false, "LuckPerms nu e instalat: dă nodul flowcity.fadmin.<nivel> manual.");
            return;
        }
        final String reason = clip(str(j, "reason", ""), 160);
        if (reason.length() < 3) {
            res(p, false, "Scrie un motiv (minim 3 caractere).");
            return;
        }
        plugin.service().findUuid(name).whenComplete((u, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || u == null) {
                res(p, false, "Jucătorul " + name + " nu a intrat niciodată pe server.");
                return;
            }
            CommandSender console = Bukkit.getConsoleSender();
            FAdminLevel old = null;
            Player on = Bukkit.getPlayer(u);
            if (on != null) old = FAdminLevel.of(on);
            for (FAdminLevel l : FAdminLevel.values()) Bukkit.dispatchCommand(console, "lp user " + name + " permission unset " + l.node());
            if (target != null) Bukkit.dispatchCommand(console, "lp user " + name + " permission set " + target.node() + " true");
            log.add(p.getName(), lv, "perm_set", u, name, old == null ? "-" : old.id, target == null ? "-" : target.id, reason);
            res(p, true, name + " → " + (target == null ? "fără nivel FAdmin" : target.label));
            permList(p);
        }));
    }

    // ------------------------------------------------------------------ panoul clasic

    private void classic(Player p, JsonObject j) {
        if (FAdminLevel.of(p) == null) return;
        plugin.channel().open(p);
        UUID u = uuid(j, "uuid");
        if (u != null) plugin.channel().sendProfile(p, u);
    }
}
