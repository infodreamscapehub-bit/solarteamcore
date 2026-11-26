package ro.solarstudios.flowcity.bridge.claims;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Recompense nerevendicate, comune pentru Roata si Battlepass: cand inventarul e plin (sau jucatorul e offline),
 * premiul se pune aici si se ia mai tarziu din ecranul sursei. Nimic nu se pierde si nimic nu se poate dubla:
 * randul se marcheaza "luat" inainte de a da itemul, intr-o singura tranzactie pe thread-ul principal.
 */
public final class ClaimStore {
    /** Un premiu care asteapta sa fie luat. */
    public static final class Entry {
        public long id;
        public String source = "";
        public String label = "";
        public String material = "";
        public int amount;
        public long ts;
    }

    private final FlowCityBridge plugin;
    private final Db db;

    public ClaimStore(FlowCityBridge plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_claims (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, "
                        + "source TEXT NOT NULL, label TEXT NOT NULL, material TEXT NOT NULL, amount INTEGER NOT NULL, "
                        + "ts INTEGER NOT NULL, taken INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_claims ON core_claims(uuid, taken)");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Claims: nu pot pregati tabelul: " + e);
        }
    }

    /**
     * Incearca sa dea itemul direct; ce nu incape ajunge in lista de revendicare.
     * Intoarce numarul de bucati puse deoparte (0 = totul a intrat in inventar).
     */
    public int giveOrStore(Player p, String source, String label, Material mat, int amount) {
        int left = Math.max(0, amount);
        while (left > 0 && hasRoom(p, mat)) {
            int n = Math.min(left, mat.getMaxStackSize());
            java.util.Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(mat, n));
            int back = 0;
            for (ItemStack rest : over.values()) back += rest.getAmount();
            left -= (n - back);
            if (back > 0) break;
        }
        if (left > 0) store(p.getUniqueId(), source, label, mat, left);
        return left;
    }

    private static boolean hasRoom(Player p, Material mat) {
        if (p.getInventory().firstEmpty() >= 0) return true;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == mat && it.getAmount() < mat.getMaxStackSize()) return true;
        }
        return false;
    }

    /** Pune un premiu deoparte (jucator offline sau inventar plin). */
    public void store(UUID u, String source, String label, Material mat, int amount) {
        if (amount <= 0 || mat == null) return;
        final String key = u.toString();
        final String src = source;
        final String lb = label;
        final String mt = mat.name();
        final int am = amount;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_claims(uuid, source, label, material, amount, ts, taken) VALUES (?,?,?,?,?,?,0)")) {
                ps.setString(1, key);
                ps.setString(2, src);
                ps.setString(3, lb);
                ps.setString(4, mt);
                ps.setInt(5, am);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
        Player p = Bukkit.getPlayer(u);
        if (p != null) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Inventar plin",
                lb + " te asteapta - elibereaza loc si apasa REVENDICA.");
        }
    }

    /** Lista premiilor neluate (apelata asincron, rezultatul se foloseste pe thread-ul principal). */
    public java.util.concurrent.CompletableFuture<List<Entry>> pending(UUID u) {
        final String key = u.toString();
        return db.async(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, source, label, material, amount, ts FROM core_claims WHERE uuid=? AND taken=0 ORDER BY id LIMIT 50")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Entry e = new Entry();
                        e.id = rs.getLong(1);
                        e.source = rs.getString(2);
                        e.label = rs.getString(3);
                        e.material = rs.getString(4);
                        e.amount = rs.getInt(5);
                        e.ts = rs.getLong(6);
                        out.add(e);
                    }
                }
            }
            return out;
        });
    }

    /**
     * Da jucatorului tot ce poate din lista. Marcheaza randul ca luat DOAR daca itemul a intrat in inventar,
     * deci un inventar inca plin nu pierde premiul.
     */
    public void claimAll(Player p, String source, Runnable after) {
        pending(p.getUniqueId()).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || list == null || !p.isOnline()) {
                if (after != null) after.run();
                return;
            }
            int given = 0;
            for (Entry e : list) {
                if (source != null && !source.isEmpty() && !source.equalsIgnoreCase(e.source)) continue;
                Material m = Material.matchMaterial(e.material);
                if (m == null || !m.isItem()) {
                    take(e.id);
                    continue;
                }
                int left = e.amount;
                while (left > 0 && hasRoom(p, m)) {
                    int n = Math.min(left, m.getMaxStackSize());
                    java.util.Map<Integer, ItemStack> over = p.getInventory().addItem(new ItemStack(m, n));
                    int back = 0;
                    for (ItemStack rest : over.values()) back += rest.getAmount();
                    left -= (n - back);
                    if (back > 0) break;
                }
                if (left <= 0) {
                    take(e.id);
                    given++;
                } else if (left != e.amount) {
                    setAmount(e.id, left);
                }
            }
            if (given > 0) {
                plugin.notifications().send(p, NotifyModule.Type.OK, "Recompense primite", given + " premii au ajuns in inventar.");
            } else if (!list.isEmpty()) {
                plugin.notifications().send(p, NotifyModule.Type.WARN, "Inventar plin", "Elibereaza loc si incearca din nou.");
            }
            if (after != null) after.run();
        }));
    }

    private void take(long id) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_claims SET taken=1 WHERE id=? AND taken=0")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void setAmount(long id, int left) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_claims SET amount=? WHERE id=? AND taken=0")) {
                ps.setInt(1, left);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Numele frumos al unui material, pentru etichete. */
    public static String nice(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
