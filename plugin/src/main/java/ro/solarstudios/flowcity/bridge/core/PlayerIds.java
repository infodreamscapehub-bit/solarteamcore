package ro.solarstudios.flowcity.bridge.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.Db;

/**
 * Sistemul de ID-uri: fiecare jucator primeste la prima intrare un numar unic si permanent (#1, #2, ...).
 * Folosit de HUD, /id si disponibil pentru restul modulelor: {@code plugin.ids().idOf(uuid)}.
 * Cache complet in memorie, deci citirile sunt instantanee pe thread-ul principal.
 */
public final class PlayerIds implements Listener {
    private final FlowCityBridge plugin;
    private final Db db;
    private final Map<UUID, Integer> byUuid = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> byId = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public PlayerIds(FlowCityBridge plugin, Db db) {
        this.plugin = plugin;
        this.db = db;
    }

    public void enable() {
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_ids (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL)");
                    try (ResultSet rs = st.executeQuery("SELECT id, uuid FROM core_ids")) {
                        while (rs.next()) {
                            try {
                                UUID u = UUID.fromString(rs.getString(2));
                                byUuid.put(u, rs.getInt(1));
                                byId.put(rs.getInt(1), u);
                            } catch (IllegalArgumentException ignored) {
                                // rand corupt: ignorat
                            }
                        }
                    }
                }
                return null;
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("ID-uri: nu pot incarca tabela: " + e);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) assign(p.getUniqueId());
        registerCommand();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        assign(e.getPlayer().getUniqueId());
    }

    /** ID-ul jucatorului, sau 0 daca inca nu a fost atribuit. */
    public int idOf(UUID u) {
        Integer v = byUuid.get(u);
        return v == null ? 0 : v;
    }

    /** UUID-ul jucatorului cu ID-ul dat, sau null. */
    public UUID uuidOf(int id) {
        return byId.get(id);
    }

    public int count() {
        return byUuid.size();
    }

    /** Atribuie un ID (idempotent, asincron). */
    public void assign(UUID u) {
        if (byUuid.containsKey(u) || !pending.add(u)) return;
        final String key = u.toString();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO core_ids(uuid) VALUES (?)")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM core_ids WHERE uuid=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).whenComplete((id, err) -> {
            pending.remove(u);
            if (err != null || id == null || id <= 0) return;
            byUuid.put(u, id);
            byId.put(id, u);
        });
    }

    private void registerCommand() {
        Commands.register(plugin, "id", null, (s, c, l, a) -> {
            if (a.length == 0) {
                if (!(s instanceof Player)) {
                    s.sendMessage("Folosire: /id <jucator|numar>");
                    return true;
                }
                int id = idOf(((Player) s).getUniqueId());
                s.sendMessage(id > 0 ? "§bID-ul tau: §f#" + id : "§eID-ul se atribuie, mai incearca.");
                return true;
            }
            String q = a[0].trim();
            UUID u = null;
            String digits = q.startsWith("#") ? q.substring(1) : q;
            if (digits.matches("\\d{1,9}")) {
                u = uuidOf(Integer.parseInt(digits));
            } else if (q.length() <= 16) {
                OfflinePlayer off = Bukkit.getPlayerExact(q);
                if (off == null) {
                    OfflinePlayer o2 = Bukkit.getOfflinePlayer(q);
                    if (o2.hasPlayedBefore()) off = o2;
                }
                if (off != null) u = off.getUniqueId();
            }
            if (u == null || idOf(u) == 0) {
                s.sendMessage("§cNu am gasit niciun jucator pentru '" + q + "'.");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(u);
            String name = target.getName() == null ? u.toString().substring(0, 8) : target.getName();
            s.sendMessage("§b#" + idOf(u) + " §7= §f" + name + (target.isOnline() ? " §a(online)" : " §8(offline)"));
            return true;
        }, (s, c, l, a) -> a.length == 1
            ? Commands.filter(playersOnline(), a[0])
            : new java.util.ArrayList<String>(), "idjucator");
    }

    private static java.util.List<String> playersOnline() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName().toLowerCase(Locale.ROOT).equals(p.getName()) ? p.getName() : p.getName());
        return out;
    }
}
