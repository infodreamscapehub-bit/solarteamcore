package ro.solarstudios.flowcity.bridge.wheel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.battlepass.BattlepassModule;
import ro.solarstudios.flowcity.bridge.claims.ClaimStore;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.hype.HypeMath;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Roata norocului 2.0: premiul se alege si se acorda pe SERVER inainte de animatie, clientul doar anima pana la rezultat.
 * Premiile (tip, cantitate, sansa, descriere, iconita, activ/inactiv) si setarile rotii (cooldown, cost, rotiri gratuite,
 * maxim pe zi, pornit/oprit) se editeaza din GUI (eAdmin &gt; Roata) si se salveaza in baza de date.
 * Daca inventarul e plin, premiul merge in lista de revendicare ({@link ClaimStore}) si nu se pierde.
 * Canal "wheel": client -&gt; server: info, spin, claim, admin_*; server -&gt; client: info, result, msg, admin, stats.
 */
public final class WheelModule extends JsonModule {
    /** Tipurile de premiu care chiar pot fi acordate cu sistemele existente. */
    private static final List<String> TYPES = java.util.Arrays.asList("money", "bank", "item", "xp", "bpxp", "bplevel", "command");

    private static final class Prize {
        long id;
        int pos;
        String name;
        String descr = "";
        String type;      // money | bank | item | xp | bpxp | bplevel | command
        double amount;
        Material material;
        Material icon;
        String command = "";
        int weight;
        String color;
        boolean announce;
        boolean enabled = true;
    }

    private YamlConfiguration cfg;
    private Db db;
    private ClaimStore claims;
    private final List<Prize> prizes = new ArrayList<>();
    private final Map<String, String> settings = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSpin = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> todaySpins = new ConcurrentHashMap<>();  // [zi-epoca, rotiri]
    private final Random rnd = new Random();

    @Override
    public String id() {
        return "wheel";
    }

    @Override
    protected void onEnable() {
        db = plugin.db();
        claims = new ClaimStore(plugin);
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel (uuid TEXT PRIMARY KEY, last_spin INTEGER NOT NULL DEFAULT 0, spins INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel_log (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, name TEXT NOT NULL, prize TEXT NOT NULL, ts INTEGER NOT NULL)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_wheel_log ON core_wheel_log(uuid, id)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel_prizes (id INTEGER PRIMARY KEY AUTOINCREMENT, pos INTEGER NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, material TEXT NOT NULL DEFAULT '', weight INTEGER NOT NULL, color TEXT NOT NULL, announce INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel_cfg (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel_day (uuid TEXT PRIMARY KEY, day INTEGER NOT NULL, n INTEGER NOT NULL)");
                    // coloane adaugate in 2.0 (serverele vechi nu le au)
                    addColumn(st, "core_wheel_prizes", "descr", "TEXT NOT NULL DEFAULT ''");
                    addColumn(st, "core_wheel_prizes", "icon", "TEXT NOT NULL DEFAULT ''");
                    addColumn(st, "core_wheel_prizes", "command", "TEXT NOT NULL DEFAULT ''");
                    addColumn(st, "core_wheel_prizes", "enabled", "INTEGER NOT NULL DEFAULT 1");
                    try (ResultSet rs = st.executeQuery("SELECT uuid, last_spin FROM core_wheel")) {
                        while (rs.next()) {
                            try {
                                lastSpin.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                            } catch (IllegalArgumentException ignored) {
                                // rand corupt
                            }
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT uuid, day, n FROM core_wheel_day")) {
                        while (rs.next()) {
                            try {
                                todaySpins.put(UUID.fromString(rs.getString(1)), new int[] {rs.getInt(2), rs.getInt(3)});
                            } catch (IllegalArgumentException ignored) {
                                // rand corupt
                            }
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT k, v FROM core_wheel_cfg")) {
                        while (rs.next()) settings.put(rs.getString(1), rs.getString(2));
                    }
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Roata: nu pot porni baza de date: " + e);
            return;
        }
        cfg = ModConfig.load(plugin, "hype");
        seedFromConfig();
        reloadPrizes(null);
        on("info", (p, j) -> sendInfo(p));
        on("claim", (p, j) -> claims.claimAll(p, "roata", () -> sendInfo(p)));
        on("admin_list", (p, j) -> {
            if (admin(p)) sendAdmin(p);
        });
        on("admin_stats", (p, j) -> {
            if (admin(p)) sendStats(p);
        });
        on("admin_add", this::adminAdd);
        on("admin_update", this::adminUpdate);
        on("admin_delete", this::adminDelete);
        on("admin_move", this::adminMove);
        on("admin_cfg", this::adminCfg);
        on("spin", (p, j) -> spin(p, true));
        registerCommands();
    }

    private static void addColumn(Statement st, String table, String col, String def) {
        try {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + def);
        } catch (java.sql.SQLException ignored) {
            // coloana exista deja
        }
    }

    /** Prima pornire: premiile din hype.yml devin premii editabile in baza de date. */
    private void seedFromConfig() {
        try {
            db.async(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM core_wheel_prizes")) {
                    if (rs.next() && rs.getInt(1) > 0) return null;
                }
                int pos = 0;
                try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO core_wheel_prizes(pos, name, type, amount, material, weight, color, announce) VALUES (?,?,?,?,?,?,?,?)")) {
                    for (Map<?, ?> m : cfg.getMapList("wheel.prizes")) {
                        String type = m.get("type") == null ? "money" : String.valueOf(m.get("type")).toLowerCase(Locale.ROOT);
                        ps.setInt(1, pos++);
                        ps.setString(2, String.valueOf(m.get("name")));
                        ps.setString(3, type);
                        ps.setDouble(4, m.get("amount") instanceof Number ? ((Number) m.get("amount")).doubleValue() : 1);
                        ps.setString(5, m.get("material") == null ? "" : String.valueOf(m.get("material")));
                        ps.setInt(6, m.get("weight") instanceof Number ? ((Number) m.get("weight")).intValue() : 1);
                        ps.setString(7, m.get("color") == null ? "#22D3EE" : String.valueOf(m.get("color")));
                        ps.setInt(8, Boolean.TRUE.equals(m.get("announce")) ? 1 : 0);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Roata: nu am putut importa premiile din hype.yml: " + e);
        }
    }

    /** Reincarca premiile din DB in memorie (rezultatul se aplica pe thread-ul principal). */
    private void reloadPrizes(Runnable done) {
        db.async(c -> {
            List<Prize> out = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, pos, name, type, amount, material, weight, color, announce, descr, icon, command, enabled "
                     + "FROM core_wheel_prizes ORDER BY pos, id")) {
                while (rs.next()) {
                    Prize p = new Prize();
                    p.id = rs.getLong(1);
                    p.pos = rs.getInt(2);
                    p.name = rs.getString(3);
                    p.type = rs.getString(4) == null ? "money" : rs.getString(4).toLowerCase(Locale.ROOT);
                    p.amount = rs.getDouble(5);
                    String mat = rs.getString(6);
                    p.weight = rs.getInt(7);
                    p.color = rs.getString(8);
                    p.announce = rs.getInt(9) == 1;
                    p.descr = rs.getString(10) == null ? "" : rs.getString(10);
                    String ic = rs.getString(11);
                    p.command = rs.getString(12) == null ? "" : rs.getString(12);
                    p.enabled = rs.getInt(13) == 1;
                    p.icon = ic == null || ic.isEmpty() ? null : Material.matchMaterial(ic);
                    if ("item".equals(p.type)) {
                        p.material = mat == null || mat.isEmpty() ? null : Material.matchMaterial(mat);
                        if (p.material == null || !p.material.isItem() || p.amount < 1) {
                            plugin.getLogger().warning("Roata: premiu item invalid '" + p.name + "' (#" + p.id + "), ignorat.");
                            continue;
                        }
                    } else if ("command".equals(p.type)) {
                        if (p.command.trim().isEmpty()) {
                            plugin.getLogger().warning("Roata: premiul '" + p.name + "' (#" + p.id + ") nu are comanda, ignorat.");
                            continue;
                        }
                    } else if (!TYPES.contains(p.type)) {
                        plugin.getLogger().warning("Roata: tip necunoscut '" + p.type + "' la premiul #" + p.id + ", ignorat.");
                        continue;
                    }
                    if (p.weight < 1 || p.amount < 0) continue;
                    out.add(p);
                }
            }
            return out;
        }).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || list == null) {
                plugin.getLogger().warning("Roata: nu pot incarca premiile: " + err);
                return;
            }
            prizes.clear();
            prizes.addAll(list);
            if (active().isEmpty()) plugin.getLogger().warning("Roata: niciun premiu activ (adauga din eAdmin > Roata).");
            if (done != null) done.run();
        }));
    }

    /** Premiile care chiar pot pica (cele dezactivate raman in editor, dar nu intra in tragere). */
    private List<Prize> active() {
        List<Prize> out = new ArrayList<>();
        for (Prize p : prizes) if (p.enabled) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ setari (DB, editabile din GUI)

    private double setD(String k, double def) {
        String v = settings.get(k);
        if (v == null) return cfg.getDouble("wheel." + k, def);
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private int setI(String k, int def) {
        return (int) Math.round(setD(k, def));
    }

    private boolean setB(String k, boolean def) {
        String v = settings.get(k);
        return v == null ? def : "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private long cooldownMs() {
        return (long) (setD("cooldown-hours", 24) * 3_600_000L);
    }

    private int animSeconds() {
        return Math.max(3, Math.min(20, setI("animation-seconds", 6)));
    }

    private double spinCost() {
        return Math.max(0, setD("cost", 0));
    }

    private int freeSpins() {
        return Math.max(0, Math.min(50, setI("free-spins", 1)));
    }

    private int maxPerDay() {
        return Math.max(1, Math.min(100, setI("max-per-day", 1)));
    }

    private boolean wheelOn() {
        return setB("enabled", true);
    }

    private static int today() {
        return (int) LocalDate.now(ZoneId.systemDefault()).toEpochDay();
    }

    private int spinsToday(UUID u) {
        int[] v = todaySpins.get(u);
        return v == null || v[0] != today() ? 0 : v[1];
    }

    private void bumpToday(UUID u) {
        int d = today();
        int[] v = todaySpins.get(u);
        int n = (v == null || v[0] != d) ? 1 : v[1] + 1;
        todaySpins.put(u, new int[] {d, n});
        final String key = u.toString();
        final int fd = d;
        final int fn = n;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_wheel_day(uuid, day, n) VALUES (?,?,?) ON CONFLICT(uuid) DO UPDATE SET day=excluded.day, n=excluded.n")) {
                ps.setString(1, key);
                ps.setInt(2, fd);
                ps.setInt(3, fn);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private long leftFor(UUID u) {
        Long last = lastSpin.get(u);
        return last == null ? 0 : HypeMath.cooldownLeft(last, cooldownMs(), System.currentTimeMillis());
    }

    /** Rotirea curenta e gratuita (din cele {@code free-spins} pe zi) sau se plateste? */
    private boolean isFree(UUID u) {
        return spinsToday(u) < freeSpins();
    }

    private static int parseColor(String c) {
        try {
            return Integer.parseInt(c.startsWith("#") ? c.substring(1) : c, 16);
        } catch (NumberFormatException e) {
            return 0x22D3EE;
        }
    }

    // ------------------------------------------------------------------ info

    private void sendInfo(Player p) {
        final String key = p.getUniqueId().toString();
        db.async(c -> {
            JsonObject res = new JsonObject();
            JsonArray recent = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT prize, ts FROM core_wheel_log WHERE uuid=? ORDER BY id DESC LIMIT 8")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", rs.getString(1));
                        o.addProperty("ts", rs.getLong(2));
                        recent.add(o);
                    }
                }
            }
            res.add("recent", recent);
            try (PreparedStatement ps = c.prepareStatement("SELECT spins FROM core_wheel WHERE uuid=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    res.addProperty("total", rs.next() ? rs.getInt(1) : 0);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_claims WHERE uuid=? AND taken=0 AND source='roata'")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    res.addProperty("claims", rs.next() ? rs.getInt(1) : 0);
                }
            }
            return res;
        }).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || res == null || !p.isOnline()) return;
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Prize pr : active()) {
                JsonObject j = new JsonObject();
                j.addProperty("name", pr.name);
                j.addProperty("descr", pr.descr);
                j.addProperty("color", parseColor(pr.color));
                j.addProperty("icon", pr.icon == null ? "" : pr.icon.name());
                arr.add(j);
            }
            o.add("prizes", arr);
            o.addProperty("cooldownMs", leftFor(p.getUniqueId()));
            o.addProperty("anim", animSeconds());
            o.addProperty("enabled", wheelOn());
            o.addProperty("cost", isFree(p.getUniqueId()) ? 0 : spinCost());
            o.addProperty("freeLeft", Math.max(0, freeSpins() - spinsToday(p.getUniqueId())));
            o.addProperty("dayLeft", Math.max(0, maxPerDay() - spinsToday(p.getUniqueId())));
            o.addProperty("cash", plugin.money().balance(p.getUniqueId()));
            o.add("recent", res.getAsJsonArray("recent"));
            o.addProperty("total", res.get("total").getAsInt());
            o.addProperty("claims", res.get("claims").getAsInt());
            send(p, "info", o);
        }));
    }

    private void msg(Player p, boolean ok, String text) {
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("text", text);
            send(p, "msg", o);
        } else {
            p.sendMessage((ok ? "§a" : "§c") + text);
        }
    }

    // ------------------------------------------------------------------ spin

    private void spin(Player p, boolean viaScreen) {
        if (!p.hasPermission("flowcity.hype.wheel")) {
            msg(p, false, "Nu ai acces la roata norocului.");
            return;
        }
        if (!wheelOn()) {
            msg(p, false, "Roata e oprita momentan.");
            return;
        }
        List<Prize> pool = active();
        if (pool.isEmpty()) {
            msg(p, false, "Roata nu e configurata.");
            return;
        }
        UUID u = p.getUniqueId();
        long left = leftFor(u);
        if (left > 0) {
            msg(p, false, "Poti invarti din nou peste " + HypeMath.formatLeft(left) + ".");
            if (viaScreen) sendInfo(p);
            return;
        }
        if (spinsToday(u) >= maxPerDay()) {
            msg(p, false, "Ai atins limita de " + maxPerDay() + " rotiri pe zi.");
            if (viaScreen) sendInfo(p);
            return;
        }
        // plata (daca nu e rotire gratuita): se ia INAINTE de a alege premiul
        boolean free = isFree(u);
        double cost = free ? 0 : spinCost();
        if (cost > 0) {
            if (plugin.money().balance(u) < cost) {
                msg(p, false, "Ai nevoie de " + (long) cost + "$ pentru o rotire.");
                if (viaScreen) sendInfo(p);
                return;
            }
            if (!plugin.money().withdraw(u, cost)) {
                msg(p, false, "Plata nu a reusit.");
                return;
            }
            plugin.service().logTransaction(u, "WHEEL_COST", -cost, "ROATA", p.getWorld().getName());
        }

        int[] w = new int[pool.size()];
        for (int i = 0; i < w.length; i++) w[i] = pool.get(i).weight;
        int idx = HypeMath.pickWeighted(w, rnd);
        if (idx < 0) {
            if (cost > 0) plugin.money().deposit(u, cost);
            return;
        }
        Prize pr = pool.get(idx);

        // marcam folosirea INAINTE de orice altceva (atomic pe thread-ul principal): nu se poate dubla
        final long now = System.currentTimeMillis();
        lastSpin.put(u, now);
        bumpToday(u);
        final String key = u.toString();
        final String prizeName = pr.name;
        final String playerName = p.getName();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_wheel(uuid, last_spin, spins) VALUES (?,?,1) ON CONFLICT(uuid) DO UPDATE SET last_spin=excluded.last_spin, spins=spins+1")) {
                ps.setString(1, key);
                ps.setLong(2, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_wheel_log(uuid, name, prize, ts) VALUES (?,?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, playerName);
                ps.setString(3, prizeName);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        });

        String detail = award(p, pr);
        final int anim = animSeconds();
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("index", idx);
            o.addProperty("name", pr.name);
            o.addProperty("detail", detail);
            o.addProperty("cooldownMs", cooldownMs());
            send(p, "result", o);
        } else {
            p.sendMessage("§6§lROATA §r§f-> §e" + pr.name + " §7(" + detail + ")");
        }
        // notificarea apare dupa ce se opreste animatia
        final String fdetail = detail;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.notifications().send(u, NotifyModule.Type.MONEY, "Roata norocului", "Ai castigat: " + prizeName + " (" + fdetail + ")");
            Player on = Bukkit.getPlayer(u);
            if (on != null && on.isOnline()) sendInfo(on);
        }, anim * 20L + 10L);
        if (pr.announce) {
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                Bukkit.broadcastMessage("§6§l[ROATA] §r§f" + playerName + " §ea castigat §6§l" + prizeName + "§e la roata norocului!"), anim * 20L);
        }
    }

    /** Acorda premiul si intoarce o descriere scurta. Un premiu se acorda o singura data, aici. */
    private String award(Player p, Prize pr) {
        UUID u = p.getUniqueId();
        switch (pr.type) {
            case "money":
                plugin.money().deposit(u, pr.amount);
                plugin.service().logTransaction(u, "WHEEL", pr.amount, "ROATA", p.getWorld().getName());
                return "+$" + HypeMath.round2(pr.amount) + " cash";
            case "bank":
                plugin.bank().deposit(u, pr.amount);
                plugin.service().logTransaction(u, "WHEEL_BANK", pr.amount, "ROATA", p.getWorld().getName());
                return "+$" + HypeMath.round2(pr.amount) + " in banca";
            case "xp": {
                int n = (int) Math.max(1, pr.amount);
                p.giveExp(n);
                return "+" + n + " XP";
            }
            case "bpxp": {
                int n = (int) Math.max(1, pr.amount);
                return BattlepassModule.grantXp(p, n, "Roata norocului") ? "+" + n + " XP battlepass" : "battlepass oprit";
            }
            case "bplevel": {
                int n = (int) Math.max(1, pr.amount);
                return BattlepassModule.grantLevels(p, n, "Roata norocului") ? "+" + n + " nivel battlepass" : "battlepass oprit";
            }
            case "command": {
                String cmd = pr.command.replace("%player%", p.getName()).replace("%amount%", String.valueOf((long) pr.amount));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                plugin.getLogger().info("Roata: premiu special pentru " + p.getName() + " -> " + cmd);
                return pr.descr.isEmpty() ? "premiu special" : pr.descr;
            }
            default: {
                int total = (int) pr.amount;
                int stored = claims.giveOrStore(p, "roata", total + "x " + ClaimStore.nice(pr.material), pr.material, total);
                String base = total + "x " + ClaimStore.nice(pr.material);
                return stored > 0 ? base + " (" + stored + " te asteapta)" : base;
            }
        }
    }

    // ------------------------------------------------------------------ administrare (eAdmin > Roata)

    private static final int MAX_PRIZES = 16;
    private static final String[] PALETTE = {"#34D399", "#22D3EE", "#7C83FF", "#FBBF24", "#F59E0B", "#38BDF8", "#F472B6", "#F87171"};

    private boolean admin(Player p) {
        if (p.hasPermission("flowcity.eadmin.wheel")) return true;
        msg(p, false, "Nu ai permisiunea de a edita roata (flowcity.eadmin.wheel).");
        return false;
    }

    private Prize byId(long id) {
        for (Prize p : prizes) if (p.id == id) return p;
        return null;
    }

    private void sendAdmin(Player p) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        long total = 0;
        for (Prize pr : prizes) if (pr.enabled) total += pr.weight;
        for (Prize pr : prizes) {
            JsonObject j = new JsonObject();
            j.addProperty("id", pr.id);
            j.addProperty("name", pr.name);
            j.addProperty("descr", pr.descr);
            j.addProperty("type", pr.type);
            j.addProperty("amount", pr.amount);
            j.addProperty("material", pr.material == null ? "" : pr.material.name());
            j.addProperty("icon", pr.icon == null ? "" : pr.icon.name());
            j.addProperty("command", pr.command);
            j.addProperty("weight", pr.weight);
            j.addProperty("color", pr.color);
            j.addProperty("announce", pr.announce);
            j.addProperty("enabled", pr.enabled);
            j.addProperty("pct", (!pr.enabled || total <= 0) ? 0 : pr.weight * 100.0 / total);
            arr.add(j);
        }
        o.add("prizes", arr);
        o.addProperty("max", MAX_PRIZES);
        JsonObject s = new JsonObject();
        s.addProperty("enabled", wheelOn());
        s.addProperty("cooldown-hours", setD("cooldown-hours", 24));
        s.addProperty("cost", spinCost());
        s.addProperty("free-spins", freeSpins());
        s.addProperty("max-per-day", maxPerDay());
        s.addProperty("animation-seconds", animSeconds());
        o.add("cfg", s);
        send(p, "admin", o);
    }

    /** Statistici pentru admin: cate rotiri, ce premii pica cel mai des, cine a castigat. */
    private void sendStats(Player p) {
        db.async(c -> {
            JsonObject o = new JsonObject();
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COUNT(DISTINCT uuid) FROM core_wheel_log")) {
                    if (rs.next()) {
                        o.addProperty("spins", rs.getLong(1));
                        o.addProperty("players", rs.getLong(2));
                    }
                }
                JsonArray top = new JsonArray();
                try (ResultSet rs = st.executeQuery("SELECT prize, COUNT(*) n FROM core_wheel_log GROUP BY prize ORDER BY n DESC LIMIT 8")) {
                    while (rs.next()) {
                        JsonObject j = new JsonObject();
                        j.addProperty("name", rs.getString(1));
                        j.addProperty("n", rs.getInt(2));
                        top.add(j);
                    }
                }
                o.add("top", top);
                JsonArray last = new JsonArray();
                try (ResultSet rs = st.executeQuery("SELECT name, prize, ts FROM core_wheel_log ORDER BY id DESC LIMIT 10")) {
                    while (rs.next()) {
                        JsonObject j = new JsonObject();
                        j.addProperty("who", rs.getString(1));
                        j.addProperty("name", rs.getString(2));
                        j.addProperty("ts", rs.getLong(3));
                        last.add(j);
                    }
                }
                o.add("last", last);
            }
            return o;
        }).whenComplete((o, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || o == null || !p.isOnline()) return;
            send(p, "stats", o);
        }));
    }

    private static String cleanColor(String c, int fallbackIdx) {
        String t = c == null ? "" : c.trim();
        if (t.matches("#?[0-9a-fA-F]{6}")) return (t.startsWith("#") ? t : "#" + t).toUpperCase(Locale.ROOT);
        return PALETTE[Math.floorMod(fallbackIdx, PALETTE.length)];
    }

    private static String cleanName(String n) {
        String t = n == null ? "" : n.replaceAll("[\\p{Cntrl}§]", "").trim();
        return t.length() > 24 ? t.substring(0, 24) : t;
    }

    private static String cleanDescr(String n) {
        String t = n == null ? "" : n.replaceAll("[\\p{Cntrl}§]", "").trim();
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    /** Comanda de premiu: o singura linie, fara "/" la inceput si fara caractere de control. */
    private static String cleanCommand(String c) {
        String t = c == null ? "" : c.replaceAll("[\\p{Cntrl}§]", " ").trim();
        while (t.startsWith("/")) t = t.substring(1).trim();
        return t.length() > 180 ? t.substring(0, 180) : t;
    }

    private static String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Setarile rotii, dintr-un singur ecran de admin. */
    private void adminCfg(Player p, JsonObject j) {
        if (!admin(p)) return;
        Map<String, String> next = new HashMap<>();
        if (j.has("enabled")) next.put("enabled", bool(j, "enabled", true) ? "1" : "0");
        if (j.has("cooldown-hours")) next.put("cooldown-hours", String.valueOf(clamp(num(j, "cooldown-hours", 24), 0, 720)));
        if (j.has("cost")) next.put("cost", String.valueOf(clamp(num(j, "cost", 0), 0, 10_000_000)));
        if (j.has("free-spins")) next.put("free-spins", String.valueOf((int) clamp(num(j, "free-spins", 1), 0, 50)));
        if (j.has("max-per-day")) next.put("max-per-day", String.valueOf((int) clamp(num(j, "max-per-day", 1), 1, 100)));
        if (j.has("animation-seconds")) next.put("animation-seconds", String.valueOf((int) clamp(num(j, "animation-seconds", 6), 3, 20)));
        if (next.isEmpty()) return;
        settings.putAll(next);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_wheel_cfg(k, v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                for (Map.Entry<String, String> e : next.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
        plugin.getLogger().info("Roata: " + p.getName() + " a schimbat setarile " + next);
        msg(p, true, "Setari salvate.");
        sendAdmin(p);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void adminAdd(Player p, JsonObject j) {
        if (!admin(p)) return;
        if (prizes.size() >= MAX_PRIZES) {
            msg(p, false, "Maxim " + MAX_PRIZES + " premii pe roata.");
            return;
        }
        String type = str(j, "type", "item").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            msg(p, false, "Tip invalid.");
            return;
        }
        int weight = Math.max(1, Math.min(1000, integer(j, "weight", 10)));
        double amount = num(j, "amount", 0);
        String name = cleanName(str(j, "name", ""));
        String command = cleanCommand(str(j, "command", ""));
        Material mat = null;
        if ("item".equals(type)) {
            String want = str(j, "material", "").trim();
            if (!want.isEmpty()) {
                mat = Material.matchMaterial(want);
            } else {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand != null && hand.getType() != Material.AIR) {
                    mat = hand.getType();
                    if (amount < 1) amount = hand.getAmount();
                }
            }
            if (mat == null || mat == Material.AIR || !mat.isItem()) {
                msg(p, false, "Tine un item in mana sau scrie un material valid (ex. DIAMOND).");
                return;
            }
            if (amount < 1) amount = 1;
            amount = Math.min(2304, Math.floor(amount));
            if (name.isEmpty()) name = (int) amount + "x " + nice(mat);
        } else if ("money".equals(type) || "bank".equals(type)) {
            if (amount <= 0 || amount > 10_000_000) {
                msg(p, false, "Suma trebuie sa fie intre 1 si 10.000.000.");
                return;
            }
            amount = HypeMath.round2(amount);
            if (name.isEmpty()) name = (amount == Math.rint(amount) ? String.valueOf((long) amount) : String.valueOf(amount)) + ("bank".equals(type) ? "$ banca" : "$");
        } else if ("xp".equals(type) || "bpxp".equals(type)) {
            if (amount < 1 || amount > 1_000_000) {
                msg(p, false, "XP-ul trebuie sa fie intre 1 si 1.000.000.");
                return;
            }
            amount = Math.floor(amount);
            if (name.isEmpty()) name = (int) amount + (type.equals("bpxp") ? " XP pass" : " XP");
        } else if ("bplevel".equals(type)) {
            if (amount < 1 || amount > 50) {
                msg(p, false, "Nivelurile de battlepass trebuie sa fie intre 1 si 50.");
                return;
            }
            amount = Math.floor(amount);
            if (name.isEmpty()) name = (int) amount + " nivel pass";
        } else {
            // command
            if (command.isEmpty()) {
                msg(p, false, "Scrie comanda premiului (ex. fccars give %player% \"logan\" ...).");
                return;
            }
            if (amount < 0) amount = 0;
            if (name.isEmpty()) name = "Premiu special";
        }
        final String fname = name;
        final double famount = amount;
        final String fmat = mat == null ? "" : mat.name();
        final String ficon = cleanIcon(str(j, "icon", ""));
        final String fdescr = cleanDescr(str(j, "descr", ""));
        final String fcmd = command;
        final String color = cleanColor(str(j, "color", ""), prizes.size());
        final boolean announce = bool(j, "announce", false);
        final String ftype = type;
        final int fweight = weight;
        final String actor = p.getName();
        db.async(c -> {
            int pos = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(pos), -1) + 1 FROM core_wheel_prizes")) {
                if (rs.next()) pos = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_wheel_prizes(pos, name, type, amount, material, weight, color, announce, descr, icon, command, enabled) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,1)")) {
                ps.setInt(1, pos);
                ps.setString(2, fname);
                ps.setString(3, ftype);
                ps.setDouble(4, famount);
                ps.setString(5, fmat);
                ps.setInt(6, fweight);
                ps.setString(7, color);
                ps.setInt(8, announce ? 1 : 0);
                ps.setString(9, fdescr);
                ps.setString(10, ficon);
                ps.setString(11, fcmd);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                msg(p, false, "Eroare la salvare.");
                return;
            }
            plugin.getLogger().info("Roata: " + actor + " a adaugat premiul '" + fname + "' (" + ftype + ", greutate " + fweight + ").");
            reloadPrizes(() -> {
                msg(p, true, "Premiu adaugat: " + fname);
                sendAdmin(p);
            });
        }));
    }

    private static String cleanIcon(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return "";
        Material m = Material.matchMaterial(t);
        return m == null || !m.isItem() ? "" : m.name();
    }

    private void adminUpdate(Player p, JsonObject j) {
        if (!admin(p)) return;
        Prize pr = byId((long) num(j, "id", -1));
        if (pr == null) {
            msg(p, false, "Premiul nu exista.");
            return;
        }
        String name = j.has("name") ? cleanName(str(j, "name", pr.name)) : pr.name;
        if (name.isEmpty()) name = pr.name;
        int weight = j.has("weight") ? Math.max(1, Math.min(1000, integer(j, "weight", pr.weight))) : pr.weight;
        double amount = pr.amount;
        if (j.has("amount")) {
            double a = num(j, "amount", pr.amount);
            if ("item".equals(pr.type)) amount = Math.max(1, Math.min(2304, Math.floor(a)));
            else if ("bplevel".equals(pr.type)) amount = Math.max(1, Math.min(50, Math.floor(a)));
            else if ("xp".equals(pr.type) || "bpxp".equals(pr.type)) amount = Math.max(1, Math.min(1_000_000, Math.floor(a)));
            else if (a > 0 && a <= 10_000_000) amount = HypeMath.round2(a);
        }
        String command = j.has("command") ? cleanCommand(str(j, "command", pr.command)) : pr.command;
        if ("command".equals(pr.type) && command.isEmpty()) {
            msg(p, false, "Premiul special are nevoie de o comanda.");
            return;
        }
        String color = j.has("color") ? cleanColor(str(j, "color", ""), 0) : pr.color;
        boolean announce = j.has("announce") ? bool(j, "announce", pr.announce) : pr.announce;
        boolean enabled = j.has("enabled") ? bool(j, "enabled", pr.enabled) : pr.enabled;
        String descr = j.has("descr") ? cleanDescr(str(j, "descr", pr.descr)) : pr.descr;
        String icon = j.has("icon") ? cleanIcon(str(j, "icon", "")) : (pr.icon == null ? "" : pr.icon.name());
        final long id = pr.id;
        final String fn = name;
        final int fw = weight;
        final double fa = amount;
        final String fc = color;
        final boolean fan = announce;
        final boolean fen = enabled;
        final String fd = descr;
        final String fi = icon;
        final String fcmd = command;
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE core_wheel_prizes SET name=?, weight=?, amount=?, color=?, announce=?, descr=?, icon=?, command=?, enabled=? WHERE id=?")) {
                ps.setString(1, fn);
                ps.setInt(2, fw);
                ps.setDouble(3, fa);
                ps.setString(4, fc);
                ps.setInt(5, fan ? 1 : 0);
                ps.setString(6, fd);
                ps.setString(7, fi);
                ps.setString(8, fcmd);
                ps.setInt(9, fen ? 1 : 0);
                ps.setLong(10, id);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                msg(p, false, "Eroare la salvare.");
                return;
            }
            reloadPrizes(() -> {
                msg(p, true, "Premiu salvat.");
                sendAdmin(p);
            });
        }));
    }

    private void adminDelete(Player p, JsonObject j) {
        if (!admin(p)) return;
        final long id = (long) num(j, "id", -1);
        if (byId(id) == null) return;
        if (prizes.size() <= 2) {
            msg(p, false, "Roata trebuie sa aiba cel putin 2 premii.");
            return;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_wheel_prizes WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                msg(p, false, "Eroare la stergere.");
                return;
            }
            plugin.getLogger().info("Roata: " + p.getName() + " a sters premiul #" + id + ".");
            reloadPrizes(() -> {
                msg(p, true, "Premiu sters.");
                sendAdmin(p);
            });
        }));
    }

    private void adminMove(Player p, JsonObject j) {
        if (!admin(p)) return;
        long id = (long) num(j, "id", -1);
        int dir = integer(j, "dir", 0) < 0 ? -1 : 1;
        int idx = -1;
        for (int i = 0; i < prizes.size(); i++) if (prizes.get(i).id == id) idx = i;
        int other = idx + dir;
        if (idx < 0 || other < 0 || other >= prizes.size()) return;
        final List<Long> order = new ArrayList<>();
        for (Prize pr : prizes) order.add(pr.id);
        java.util.Collections.swap(order, idx, other);
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_wheel_prizes SET pos=? WHERE id=?")) {
                for (int i = 0; i < order.size(); i++) {
                    ps.setInt(1, i);
                    ps.setLong(2, order.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> reloadPrizes(() -> sendAdmin(p))));
    }

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.permission("flowcity.hype.wheel", "Foloseste roata norocului", PermissionDefault.TRUE);
        Commands.permission("flowcity.eadmin.wheel", "Editeaza premiile rotii (eAdmin)", PermissionDefault.OP);
        Commands.register(plugin, "roata", "flowcity.hype.wheel", (s, c, l, a) -> {
            if (a.length >= 1 && a[0].equalsIgnoreCase("reset")) {
                if (!s.hasPermission("flowcity.hype.admin")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                if (a.length < 2) {
                    s.sendMessage("§7Folosire: /roata reset <jucator>");
                    return true;
                }
                UUID u = plugin.ids().resolveAny(a[1]);
                if (u == null) {
                    s.sendMessage("§cJucator negasit.");
                    return true;
                }
                lastSpin.remove(u);
                todaySpins.remove(u);
                db.run(cn -> {
                    try (PreparedStatement ps = cn.prepareStatement("UPDATE core_wheel SET last_spin=0 WHERE uuid=?")) {
                        ps.setString(1, u.toString());
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = cn.prepareStatement("DELETE FROM core_wheel_day WHERE uuid=?")) {
                        ps.setString(1, u.toString());
                        ps.executeUpdate();
                    }
                    return null;
                });
                s.sendMessage("§aCooldown-ul rotii a fost resetat.");
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatorii pot folosi aceasta comanda.");
                return true;
            }
            Player p = (Player) s;
            if (a.length >= 1 && a[0].equalsIgnoreCase("admin")) {
                if (!admin(p)) return true;
                if (plugin.channel().hasMod(p)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("screen", "admin");
                    send(p, "open", o);
                    sendAdmin(p);
                } else {
                    p.sendMessage("§7Premii (" + prizes.size() + "): editarea necesita modul FlowCity (eAdmin > Roata).");
                }
                return true;
            }
            if (plugin.channel().hasMod(p)) {
                JsonObject o = new JsonObject();
                o.addProperty("screen", "wheel");
                send(p, "open", o);
                sendInfo(p);
            } else {
                spin(p, false);
            }
            return true;
        }, (s, c, l, a) -> a.length == 1 && s.hasPermission("flowcity.hype.admin")
            ? Commands.filter(java.util.Arrays.asList("reset", "admin"), a[0]) : new ArrayList<String>(), "wheel");
    }
}
