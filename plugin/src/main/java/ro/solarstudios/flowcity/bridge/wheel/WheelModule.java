package ro.solarstudios.flowcity.bridge.wheel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.hype.HypeMath;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Roata norocului: un spin la fiecare {@code wheel.cooldown-hours}; premiul se alege si se acorda pe server,
 * clientul doar anima roata pana la rezultat. Config: sectiunea "wheel" din hype.yml.
 * Comenzi: /roata (ecran sau, fara mod, invarte direct), /roata reset <jucator> (admin).
 * Permisiuni: flowcity.hype.wheel (implicit true), flowcity.hype.admin.
 * Canal "wheel": client -> server: info, spin; server -> client: info, result, msg.
 */
public final class WheelModule extends JsonModule {
    private static final class Prize {
        long id;
        int pos;
        String name;
        String type;      // money | bank | item
        double amount;
        Material material;
        int weight;
        String color;
        boolean announce;
    }

    private YamlConfiguration cfg;
    private Db db;
    private final List<Prize> prizes = new ArrayList<>();
    private final Map<UUID, Long> lastSpin = new ConcurrentHashMap<>();
    private final Random rnd = new Random();

    @Override
    public String id() {
        return "wheel";
    }

    @Override
    protected void onEnable() {
        db = plugin.db();
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel (uuid TEXT PRIMARY KEY, last_spin INTEGER NOT NULL DEFAULT 0, spins INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel_log (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, name TEXT NOT NULL, prize TEXT NOT NULL, ts INTEGER NOT NULL)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_wheel_log ON core_wheel_log(uuid, id)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_wheel_prizes (id INTEGER PRIMARY KEY AUTOINCREMENT, pos INTEGER NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, material TEXT NOT NULL DEFAULT '', weight INTEGER NOT NULL, color TEXT NOT NULL, announce INTEGER NOT NULL DEFAULT 0)");
                    try (ResultSet rs = st.executeQuery("SELECT uuid, last_spin FROM core_wheel")) {
                        while (rs.next()) {
                            try {
                                lastSpin.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                            } catch (IllegalArgumentException ignored) {
                                // rand corupt
                            }
                        }
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
        on("admin_list", (p, j) -> {
            if (admin(p)) sendAdmin(p);
        });
        on("admin_add", this::adminAdd);
        on("admin_update", this::adminUpdate);
        on("admin_delete", this::adminDelete);
        on("admin_move", this::adminMove);
        on("spin", (p, j) -> spin(p, true));
        registerCommands();
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
                 ResultSet rs = st.executeQuery("SELECT id, pos, name, type, amount, material, weight, color, announce FROM core_wheel_prizes ORDER BY pos, id")) {
                while (rs.next()) {
                    Prize p = new Prize();
                    p.id = rs.getLong(1);
                    p.pos = rs.getInt(2);
                    p.name = rs.getString(3);
                    p.type = rs.getString(4);
                    p.amount = rs.getDouble(5);
                    String mat = rs.getString(6);
                    p.weight = rs.getInt(7);
                    p.color = rs.getString(8);
                    p.announce = rs.getInt(9) == 1;
                    if ("item".equals(p.type)) {
                        p.material = mat == null || mat.isEmpty() ? null : Material.matchMaterial(mat);
                        if (p.material == null || p.amount < 1) {
                            plugin.getLogger().warning("Roata: premiu item invalid '" + p.name + "' (#" + p.id + "), ignorat.");
                            continue;
                        }
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
            if (prizes.isEmpty()) plugin.getLogger().warning("Roata: niciun premiu valid (adauga din eAdmin > Roata).");
            if (done != null) done.run();
        }));
    }

    private long cooldownMs() {
        return (long) (cfg.getDouble("wheel.cooldown-hours", 24) * 3_600_000L);
    }

    private long leftFor(UUID u) {
        Long last = lastSpin.get(u);
        return last == null ? 0 : HypeMath.cooldownLeft(last, cooldownMs(), System.currentTimeMillis());
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
            JsonArray recent = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT prize, ts FROM core_wheel_log WHERE uuid=? ORDER BY id DESC LIMIT 6")) {
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
            return recent;
        }).whenComplete((recent, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || recent == null || !p.isOnline()) return;
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Prize pr : prizes) {
                JsonObject j = new JsonObject();
                j.addProperty("name", pr.name);
                j.addProperty("color", parseColor(pr.color));
                arr.add(j);
            }
            o.add("prizes", arr);
            o.addProperty("cooldownMs", leftFor(p.getUniqueId()));
            o.addProperty("anim", Math.max(3, cfg.getInt("wheel.animation-seconds", 6)));
            o.add("recent", recent);
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
        if (prizes.isEmpty()) {
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
        int[] w = new int[prizes.size()];
        for (int i = 0; i < w.length; i++) w[i] = prizes.get(i).weight;
        int idx = HypeMath.pickWeighted(w, rnd);
        if (idx < 0) return;
        Prize pr = prizes.get(idx);

        // marcam folosirea INAINTE de orice altceva (atomic pe thread-ul principal): nu se poate dubla
        final long now = System.currentTimeMillis();
        lastSpin.put(u, now);
        final String key = u.toString();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_wheel(uuid, last_spin, spins) VALUES (?,?,1) ON CONFLICT(uuid) DO UPDATE SET last_spin=excluded.last_spin, spins=spins+1")) {
                ps.setString(1, key);
                ps.setLong(2, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_wheel_log(uuid, name, prize, ts) VALUES (?,?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, p.getName());
                ps.setString(3, pr.name);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        });

        String detail = award(p, pr);
        final int anim = Math.max(3, cfg.getInt("wheel.animation-seconds", 6));
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
        Bukkit.getScheduler().runTaskLater(plugin, () ->
            plugin.notifications().send(u, NotifyModule.Type.MONEY, "Roata norocului", "Ai castigat: " + pr.name), anim * 20L + 10L);
        if (pr.announce) {
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                Bukkit.broadcastMessage("§6§l[ROATA] §r§f" + p.getName() + " §ea castigat §6§l" + pr.name + "§e la roata norocului!"), anim * 20L);
        }
    }

    /** Acorda premiul si intoarce o descriere scurta. */
    private String award(Player p, Prize pr) {
        UUID u = p.getUniqueId();
        if ("money".equals(pr.type)) {
            plugin.money().deposit(u, pr.amount);
            plugin.service().logTransaction(u, "WHEEL", pr.amount, "ROATA", p.getWorld().getName());
            return "+$" + HypeMath.round2(pr.amount) + " cash";
        }
        if ("bank".equals(pr.type)) {
            plugin.bank().deposit(u, pr.amount);
            plugin.service().logTransaction(u, "WHEEL_BANK", pr.amount, "ROATA", p.getWorld().getName());
            return "+$" + HypeMath.round2(pr.amount) + " in banca";
        }
        int total = (int) pr.amount;
        int left = total;
        while (left > 0) {
            int n = Math.min(left, pr.material.getMaxStackSize());
            Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(pr.material, n));
            for (ItemStack rest : over.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
            left -= n;
        }
        return total + "x " + pr.material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
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
        for (Prize pr : prizes) total += pr.weight;
        for (Prize pr : prizes) {
            JsonObject j = new JsonObject();
            j.addProperty("id", pr.id);
            j.addProperty("name", pr.name);
            j.addProperty("type", pr.type);
            j.addProperty("amount", pr.amount);
            j.addProperty("material", pr.material == null ? "" : pr.material.name());
            j.addProperty("weight", pr.weight);
            j.addProperty("color", pr.color);
            j.addProperty("announce", pr.announce);
            j.addProperty("pct", total <= 0 ? 0 : pr.weight * 100.0 / total);
            arr.add(j);
        }
        o.add("prizes", arr);
        o.addProperty("max", MAX_PRIZES);
        send(p, "admin", o);
    }

    private static String cleanColor(String c, int fallbackIdx) {
        String t = c == null ? "" : c.trim();
        if (t.matches("#?[0-9a-fA-F]{6}")) return (t.startsWith("#") ? t : "#" + t).toUpperCase(Locale.ROOT);
        return PALETTE[Math.floorMod(fallbackIdx, PALETTE.length)];
    }

    private static String cleanName(String n) {
        String t = n == null ? "" : n.replaceAll("[\\p{Cntrl}\u00a7]", "").trim();
        return t.length() > 24 ? t.substring(0, 24) : t;
    }

    private static String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private void adminAdd(Player p, JsonObject j) {
        if (!admin(p)) return;
        if (prizes.size() >= MAX_PRIZES) {
            msg(p, false, "Maxim " + MAX_PRIZES + " premii pe roata.");
            return;
        }
        String type = str(j, "type", "item").toLowerCase(Locale.ROOT);
        int weight = Math.max(1, Math.min(1000, integer(j, "weight", 10)));
        double amount = num(j, "amount", 0);
        String name = cleanName(str(j, "name", ""));
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
        } else {
            msg(p, false, "Tip invalid.");
            return;
        }
        final String fname = name;
        final double famount = amount;
        final String fmat = mat == null ? "" : mat.name();
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
                "INSERT INTO core_wheel_prizes(pos, name, type, amount, material, weight, color, announce) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setInt(1, pos);
                ps.setString(2, fname);
                ps.setString(3, ftype);
                ps.setDouble(4, famount);
                ps.setString(5, fmat);
                ps.setInt(6, fweight);
                ps.setString(7, color);
                ps.setInt(8, announce ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                msg(p, false, "Eroare la salvare.");
                return;
            }
            plugin.getLogger().info("Roata: " + actor + " a adaugat premiul '" + fname + "' (greutate " + fweight + ").");
            reloadPrizes(() -> {
                msg(p, true, "Premiu adaugat: " + fname);
                sendAdmin(p);
            });
        }));
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
            else if (a > 0 && a <= 10_000_000) amount = HypeMath.round2(a);
        }
        String color = j.has("color") ? cleanColor(str(j, "color", ""), 0) : pr.color;
        boolean announce = j.has("announce") ? bool(j, "announce", pr.announce) : pr.announce;
        final long id = pr.id;
        final String fn = name;
        final int fw = weight;
        final double fa = amount;
        final String fc = color;
        final boolean fan = announce;
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_wheel_prizes SET name=?, weight=?, amount=?, color=?, announce=? WHERE id=?")) {
                ps.setString(1, fn);
                ps.setInt(2, fw);
                ps.setDouble(3, fa);
                ps.setString(4, fc);
                ps.setInt(5, fan ? 1 : 0);
                ps.setLong(6, id);
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
                db.run(cn -> {
                    try (PreparedStatement ps = cn.prepareStatement("UPDATE core_wheel SET last_spin=0 WHERE uuid=?")) {
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
                    p.sendMessage("\u00a77Premii (" + prizes.size() + "): editarea necesita modul FlowCity (eAdmin > Roata).");
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
