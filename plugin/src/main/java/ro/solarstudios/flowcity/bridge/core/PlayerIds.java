package ro.solarstudios.flowcity.bridge.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.Db;

/**
 * Sistemul de ID-uri: fiecare jucator primeste la prima intrare un numar unic si permanent (#1, #2, ...).
 * Comenzi: /id, /setid, /listid, /delid. API pentru restul modulelor: {@code plugin.ids().idOf(uuid)}.
 * Tot ce se citeste vine din cache in memorie, deci e instantaneu pe thread-ul principal.
 * Config (config.yml): ids.start (primul ID, implicit 1), ids.tab-prefix (afiseaza #ID in lista de jucatori).
 */
public final class PlayerIds implements Listener {
    private final FlowCityBridge plugin;
    private final Db db;
    private final Map<UUID, Integer> byUuid = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> byId = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public PlayerIds(FlowCityBridge plugin, Db db) {
        this.plugin = plugin;
        this.db = db;
    }

    // ------------------------------------------------------------------ pornire

    public void enable() {
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_ids (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL)");
                    try {
                        st.execute("ALTER TABLE core_ids ADD COLUMN name TEXT NOT NULL DEFAULT ''");
                    } catch (Exception exists) {
                        // coloana exista deja
                    }
                    try (ResultSet rs = st.executeQuery("SELECT id, uuid, name FROM core_ids")) {
                        while (rs.next()) {
                            try {
                                UUID u = UUID.fromString(rs.getString(2));
                                byUuid.put(u, rs.getInt(1));
                                byId.put(rs.getInt(1), u);
                                String n = rs.getString(3);
                                if (n != null && !n.isEmpty()) names.put(u, n);
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
        for (Player p : Bukkit.getOnlinePlayers()) assign(p.getUniqueId(), p.getName());
        registerCommands();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        assign(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }

    // ------------------------------------------------------------------ API

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

    /** Numele salvat pentru un UUID (merge si offline). */
    public String nameOf(UUID u) {
        String n = names.get(u);
        if (n != null) return n;
        OfflinePlayer off = Bukkit.getOfflinePlayer(u);
        return off.getName() == null ? u.toString().substring(0, 8) : off.getName();
    }

    /** Cauta un jucator dupa nume exact (online, salvat sau cunoscut de server). */
    public UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off.getUniqueId() : null;
    }

    /** Rezolva "#12", "12" sau un nume de jucator. */
    public UUID resolveAny(String q) {
        String digits = q.startsWith("#") ? q.substring(1) : q;
        if (digits.matches("\\d{1,9}")) return uuidOf(Integer.parseInt(digits));
        return q.length() <= 16 ? resolve(q) : null;
    }

    // ------------------------------------------------------------------ atribuire / modificare

    /** Atribuie un ID (idempotent, asincron). */
    public void assign(UUID u, String name) {
        if (name != null && !name.isEmpty()) names.put(u, name);
        if (byUuid.containsKey(u)) {
            if (name != null) saveName(u, name);
            applyTab(u);
            return;
        }
        if (!pending.add(u)) return;
        final String key = u.toString();
        final String nm = name == null ? "" : name;
        final int start = Math.max(1, plugin.getConfig().getInt("ids.start", 1));
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO core_ids(id, uuid, name) VALUES ((SELECT COALESCE(MAX(id), ?) + 1 FROM core_ids), ?, ?)")) {
                ps.setInt(1, start - 1);
                ps.setString(2, key);
                ps.setString(3, nm);
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
            Bukkit.getScheduler().runTask(plugin, () -> applyTab(u));
        });
    }

    private void saveName(UUID u, String name) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_ids SET name=? WHERE uuid=?")) {
                ps.setString(1, name);
                ps.setString(2, u.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Prefix #ID in lista de jucatori (TAB), daca ids.tab-prefix e activ. */
    private void applyTab(UUID u) {
        if (!plugin.getConfig().getBoolean("ids.tab-prefix", false)) return;
        Player p = Bukkit.getPlayer(u);
        int id = idOf(u);
        if (p != null && id > 0) p.setPlayerListName("§7#" + id + " §r" + p.getName());
    }

    /**
     * Schimba ID-ul unui jucator. Daca ID-ul e ocupat si swap=true, cei doi isi schimba ID-urile.
     * Rezultatul se intoarce pe thread-ul principal: (ok, mesaj).
     */
    public void setId(UUID u, int newId, boolean swap, BiConsumer<Boolean, String> done) {
        if (newId < 1 || newId > 999_999_999) {
            done.accept(false, "ID invalid (1 - 999999999).");
            return;
        }
        final int oldId = idOf(u);
        if (oldId == newId) {
            done.accept(false, "Jucatorul are deja ID-ul #" + newId + ".");
            return;
        }
        final UUID other = byId.get(newId);
        if (other != null && !swap) {
            done.accept(false, "ID-ul #" + newId + " este al lui " + nameOf(other) + ". Foloseste 'swap' ca sa le schimbi intre ei.");
            return;
        }
        if (other != null && oldId == 0) {
            done.accept(false, "Jucatorul nu are inca ID, nu se poate face swap.");
            return;
        }
        final String me = u.toString();
        final String name = nameOf(u);
        db.async(c -> {
            c.setAutoCommit(false);
            try {
                if (other != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE core_ids SET id=-1 WHERE uuid=?")) {
                        ps.setString(1, other.toString());
                        ps.executeUpdate();
                    }
                }
                if (oldId == 0) {
                    try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO core_ids(id, uuid, name) VALUES (?,?,?)")) {
                        ps.setInt(1, newId);
                        ps.setString(2, me);
                        ps.setString(3, name);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE core_ids SET id=? WHERE uuid=?")) {
                        ps.setInt(1, newId);
                        ps.setString(2, me);
                        ps.executeUpdate();
                    }
                }
                if (other != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE core_ids SET id=? WHERE uuid=?")) {
                        ps.setInt(1, oldId);
                        ps.setString(2, other.toString());
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.getLogger().warning("setid: " + err);
                done.accept(false, "Eroare la salvare.");
                return;
            }
            byUuid.put(u, newId);
            byId.put(newId, u);
            if (other != null) {
                byUuid.put(other, oldId);
                byId.put(oldId, other);
            } else if (oldId != 0) {
                byId.remove(oldId);
            }
            applyTab(u);
            if (other != null) applyTab(other);
            done.accept(true, other == null
                ? name + " are acum ID-ul #" + newId + "."
                : name + " (#" + newId + ") si " + nameOf(other) + " (#" + oldId + ") si-au schimbat ID-urile.");
        }));
    }

    /** Sterge ID-ul (cel online primeste imediat unul nou, urmatorul liber). */
    public void delete(UUID u, BiConsumer<Boolean, String> done) {
        final int old = idOf(u);
        if (old == 0) {
            done.accept(false, "Jucatorul nu are ID.");
            return;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_ids WHERE uuid=?")) {
                ps.setString(1, u.toString());
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                done.accept(false, "Eroare la stergere.");
                return;
            }
            byUuid.remove(u);
            byId.remove(old);
            Player p = Bukkit.getPlayer(u);
            if (p != null) assign(u, p.getName());
            done.accept(true, "ID-ul #" + old + " a fost sters" + (p != null ? " (jucatorul online primeste unul nou)." : "."));
        }));
    }

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.permission("flowcity.id.admin", "Seteaza / sterge ID-uri", PermissionDefault.OP);
        Commands.permission("flowcity.id.list", "Vede lista de ID-uri", PermissionDefault.OP);

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
            UUID u = resolveAny(a[0].trim());
            if (u == null || idOf(u) == 0) {
                s.sendMessage("§cNu am gasit niciun jucator pentru '" + a[0] + "'.");
                return true;
            }
            boolean on = Bukkit.getPlayer(u) != null;
            s.sendMessage("§b#" + idOf(u) + " §7= §f" + nameOf(u) + (on ? " §a(online)" : " §8(offline)"));
            return true;
        }, (s, c, l, a) -> a.length == 1 ? Commands.filter(onlineNames(), a[0]) : new ArrayList<String>(), "idjucator");

        Commands.register(plugin, "setid", "flowcity.id.admin", (s, c, l, a) -> {
            if (a.length < 2) {
                s.sendMessage("§7Folosire: /setid <jucator> <numar> [swap]");
                return true;
            }
            UUID u = resolve(a[0]);
            if (u == null) {
                s.sendMessage("§cJucatorul '" + a[0] + "' nu a mai intrat pe server.");
                return true;
            }
            int n;
            try {
                n = Integer.parseInt(a[1].startsWith("#") ? a[1].substring(1) : a[1]);
            } catch (NumberFormatException ex) {
                s.sendMessage("§cNumar invalid.");
                return true;
            }
            boolean swap = a.length >= 3 && a[2].equalsIgnoreCase("swap");
            setId(u, n, swap, (ok, msg) -> s.sendMessage((ok ? "§a" : "§c") + msg));
            return true;
        }, (s, c, l, a) -> a.length == 1 ? Commands.filter(onlineNames(), a[0])
            : a.length == 3 ? Commands.filter(java.util.Arrays.asList("swap"), a[2]) : new ArrayList<String>());

        Commands.register(plugin, "delid", "flowcity.id.admin", (s, c, l, a) -> {
            if (a.length < 1) {
                s.sendMessage("§7Folosire: /delid <jucator|#numar>");
                return true;
            }
            UUID u = resolveAny(a[0]);
            if (u == null) {
                s.sendMessage("§cNu am gasit jucatorul.");
                return true;
            }
            delete(u, (ok, msg) -> s.sendMessage((ok ? "§a" : "§c") + msg));
            return true;
        }, (s, c, l, a) -> a.length == 1 ? Commands.filter(onlineNames(), a[0]) : new ArrayList<String>());

        Commands.register(plugin, "listid", "flowcity.id.list", (s, c, l, a) -> {
            String q = a.length > 0 ? a[0] : "";
            int page = 1;
            if (q.matches("\\d{1,4}") && a.length == 1) {
                page = Math.max(1, Integer.parseInt(q));
                q = "";
            } else if (a.length > 1 && a[1].matches("\\d{1,4}")) {
                page = Math.max(1, Integer.parseInt(a[1]));
            }
            List<Integer> all = new ArrayList<>();
            for (Map.Entry<Integer, UUID> e : byId.entrySet()) {
                if (q.isEmpty() || nameOf(e.getValue()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))) all.add(e.getKey());
            }
            Collections.sort(all);
            int per = 10;
            int pages = Math.max(1, (all.size() + per - 1) / per);
            page = Math.min(page, pages);
            s.sendMessage("§b== ID-uri" + (q.isEmpty() ? "" : " pentru '" + q + "'") + " §7(" + all.size() + ", pagina " + page + "/" + pages + ") §b==");
            for (int i = (page - 1) * per; i < Math.min(all.size(), page * per); i++) {
                UUID u = byId.get(all.get(i));
                boolean on = Bukkit.getPlayer(u) != null;
                s.sendMessage("§8#" + all.get(i) + " §f" + nameOf(u) + (on ? " §a*" : ""));
            }
            if (all.isEmpty()) s.sendMessage("§7Niciun rezultat.");
            return true;
        }, (s, c, l, a) -> new ArrayList<String>());
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }
}
