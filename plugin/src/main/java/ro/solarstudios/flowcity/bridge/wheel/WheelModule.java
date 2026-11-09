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
        loadConfig();
        on("info", (p, j) -> sendInfo(p));
        on("spin", (p, j) -> spin(p, true));
        registerCommands();
    }

    private void loadConfig() {
        cfg = ModConfig.load(plugin, "hype");
        prizes.clear();
        for (Map<?, ?> m : cfg.getMapList("wheel.prizes")) {
            Prize p = new Prize();
            p.name = String.valueOf(m.get("name"));
            p.type = m.get("type") == null ? "money" : String.valueOf(m.get("type")).toLowerCase(Locale.ROOT);
            p.amount = m.get("amount") instanceof Number ? ((Number) m.get("amount")).doubleValue() : 0;
            p.weight = m.get("weight") instanceof Number ? ((Number) m.get("weight")).intValue() : 1;
            p.color = m.get("color") == null ? "#22D3EE" : String.valueOf(m.get("color"));
            p.announce = Boolean.TRUE.equals(m.get("announce"));
            if ("item".equals(p.type)) {
                p.material = m.get("material") == null ? null : Material.matchMaterial(String.valueOf(m.get("material")));
                if (p.material == null || p.amount < 1) {
                    plugin.getLogger().warning("Roata: premiu item invalid '" + p.name + "', ignorat.");
                    continue;
                }
            }
            if (p.weight < 1 || p.amount < 0) continue;
            prizes.add(p);
        }
        if (prizes.isEmpty()) plugin.getLogger().warning("Roata: niciun premiu valid in hype.yml (wheel.prizes).");
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

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.permission("flowcity.hype.wheel", "Foloseste roata norocului", PermissionDefault.TRUE);
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
            ? Commands.filter(java.util.Arrays.asList("reset"), a[0]) : new ArrayList<String>(), "wheel");
    }
}
