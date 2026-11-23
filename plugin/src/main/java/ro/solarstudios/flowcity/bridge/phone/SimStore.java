package ro.solarstudios.flowcity.bridge.phone;

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
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;

/**
 * Cartelele SIM (server). Sursa adevarului e baza de date (core_sim): numarul, operatorul, proprietarul, statusul si telefonul in care
 * e instalata. Itemul din inventar e doar un "bilet": poarta in PersistentDataContainer ID-ul cartelei si o generatie; la fiecare
 * introducere/extragere generatia creste, deci copiile itemului (dupe, NBT copiat din creative) devin invalide imediat.
 * Clientul nu poate seta PDC-ul si nu trimite niciodata numarul: serverul cauta totul dupa ID in DB.
 */
public final class SimStore {
    public static final class Sim {
        public final String id;
        public final String number;
        public final String operator;
        public volatile UUID owner;
        public volatile String status;
        public final long activated;
        public volatile int gen;
        public volatile UUID installed;

        Sim(String id, String number, String operator, UUID owner, String status, long activated, int gen, UUID installed) {
            this.id = id;
            this.number = number;
            this.operator = operator;
            this.owner = owner;
            this.status = status;
            this.activated = activated;
            this.gen = gen;
            this.installed = installed;
        }

        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static SimStore instance;

    /** Instanta activa (setata de PhoneModule); folosita de CNN Anunturi pentru numarul de telefon al autorului. */
    public static SimStore get() {
        return instance;
    }

    private final FlowCityBridge plugin;
    private final Map<String, Sim> byId = new ConcurrentHashMap<>();
    private final Map<String, Sim> byNumber = new ConcurrentHashMap<>();
    private final Map<UUID, Sim> byPlayer = new ConcurrentHashMap<>();
    private final NamespacedKey keyId;
    private final NamespacedKey keyGen;
    private final Random rnd = new Random();

    public SimStore(FlowCityBridge plugin) {
        this.plugin = plugin;
        instance = this;
        this.keyId = new NamespacedKey(plugin, "sim_id");
        this.keyGen = new NamespacedKey(plugin, "sim_gen");
        try {
            plugin.db().async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_sim (id TEXT PRIMARY KEY, number TEXT NOT NULL UNIQUE, operator TEXT NOT NULL, owner TEXT, status TEXT NOT NULL DEFAULT 'ACTIVE', activated INTEGER NOT NULL, gen INTEGER NOT NULL DEFAULT 1, installed TEXT)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_sim_installed ON core_sim(installed)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_phone_prefs (uuid TEXT PRIMARY KEY, wallpaper INTEGER NOT NULL DEFAULT 0, light INTEGER NOT NULL DEFAULT 0)");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
            List<Sim> all = plugin.db().async(c -> {
                List<Sim> l = new ArrayList<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, number, operator, owner, status, activated, gen, installed FROM core_sim")) {
                    while (rs.next()) {
                        l.add(new Sim(rs.getString(1), rs.getString(2), rs.getString(3), uuid(rs.getString(4)), rs.getString(5), rs.getLong(6), rs.getInt(7), uuid(rs.getString(8))));
                    }
                }
                return l;
            }).get(8, TimeUnit.SECONDS);
            for (Sim s : all) index(s);
        } catch (Exception e) {
            plugin.getLogger().warning("SIM: nu pot incarca cartelele: " + e);
        }
    }

    private static UUID uuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void index(Sim s) {
        byId.put(s.id, s);
        byNumber.put(s.number, s);
        if (s.installed != null) byPlayer.put(s.installed, s);
    }

    // ------------------------------------------------------------------ interogari

    /** Cartela instalata in telefonul jucatorului (orice status) sau null. */
    public Sim installed(UUID u) {
        return byPlayer.get(u);
    }

    /** Cartela instalata si ACTIVA: doar cu ea merg SMS-urile si apelurile. */
    public Sim activeOf(UUID u) {
        Sim s = byPlayer.get(u);
        return s != null && s.active() ? s : null;
    }

    /** Jucatorul care are instalata cartela cu acest numar (doar cifre) sau null. */
    public UUID holderOfNumber(String digits) {
        Sim s = byNumber.get(digits);
        return s != null && s.active() ? s.installed : null;
    }

    public Sim get(String id) {
        return byId.get(id);
    }

    // ------------------------------------------------------------------ itemul

    /** Itemul "Card SIM" din mod (creative tab) cand Arclight il expune ca material; altfel null. */
    public Material blankMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("phone.sim.blank-material", "FLOWCITYCORE_SIM_CARD"));
        return m;
    }

    private Material material() {
        String cfg = plugin.getConfig().getString("phone.sim.material", "AUTO");
        if (cfg == null || cfg.equalsIgnoreCase("AUTO") || cfg.equalsIgnoreCase("PAPER")) {
            Material b = blankMaterial();
            if (b != null) return b;
            return Material.PAPER;
        }
        Material m = Material.matchMaterial(cfg);
        return m == null ? Material.PAPER : m;
    }

    /** Cartele noi (itemul din mod, fara ID server) din inventar: nr. de iteme. */
    public int blankCount(Player p) {
        Material b = blankMaterial();
        if (b == null) return 0;
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == b && idOf(it) == null) n += it.getAmount();
        }
        return n;
    }

    /** Consuma o cartela noua din inventar; true daca a gasit una. */
    public boolean takeBlank(Player p) {
        Material b = blankMaterial();
        if (b == null) return false;
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack it = c[i];
            if (it != null && it.getType() == b && idOf(it) == null) {
                if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1);
                else p.getInventory().setItem(i, null);
                return true;
            }
        }
        return false;
    }

    public ItemStack makeItem(Sim s) {
        ItemStack it = new ItemStack(material(), 1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.setDisplayName("§bCartela SIM §7" + s.operator);
        List<String> lore = new ArrayList<>();
        lore.add("§7Numar: §f" + NumberLogic.phoneDisplay(s.number));
        lore.add("§7Operator: §f" + s.operator);
        lore.add("§8Se introduce din Telefon > SIM");
        meta.setLore(lore);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyId, PersistentDataType.STRING, s.id);
        pdc.set(keyGen, PersistentDataType.INTEGER, s.gen);
        it.setItemMeta(meta);
        return it;
    }

    /** ID-ul din item (doar PDC-ul scris de server) sau null. */
    public String idOf(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    private int genOf(ItemStack it) {
        Integer g = it.getItemMeta().getPersistentDataContainer().get(keyGen, PersistentDataType.INTEGER);
        return g == null ? -1 : g;
    }

    /** Itemul valid din inventar pentru cartela (ID + generatie corecta) sau null. Cautare doar pe thread-ul principal. */
    public ItemStack findValidItem(Player p, Sim s) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && s.id.equals(idOf(it)) && genOf(it) == s.gen) return it;
        }
        return null;
    }

    /** Cartelele (ID-uri) din inventarul jucatorului, in ordinea sloturilor. */
    public List<Sim> inInventory(Player p) {
        List<Sim> out = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) {
            String id = idOf(it);
            if (id == null) continue;
            Sim s = byId.get(id);
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ operatii (thread-ul principal)

    /** Creeaza o cartela noua (numar unic) pentru jucator. Intoarce null daca nu gaseste numar liber. */
    public Sim create(UUID owner, String operator, String format) {
        String number = NumberLogic.generate(format, rnd, n -> byNumber.containsKey(n), 400);
        if (number == null) return null;
        String id;
        int tries = 0;
        do {
            StringBuilder sb = new StringBuilder("SIM-");
            for (int i = 0; i < 8; i++) sb.append(ALPHA.charAt(rnd.nextInt(ALPHA.length())));
            id = sb.toString();
        } while (byId.containsKey(id) && ++tries < 50);
        if (byId.containsKey(id)) return null;
        Sim s = new Sim(id, number, operator, owner, "ACTIVE", System.currentTimeMillis(), 1, null);
        index(s);
        persist(s);
        return s;
    }

    /** Instaleaza cartela in telefonul jucatorului (dupa ce itemul a fost scos din inventar). Creste generatia. */
    public void install(Sim s, UUID player) {
        s.installed = player;
        s.owner = player;
        s.gen++;
        byPlayer.put(player, s);
        persist(s);
    }

    /** Scoate cartela din telefon; generatia creste, deci itemul vechi nu mai e valid. */
    public void eject(Sim s) {
        UUID who = s.installed;
        if (who != null) byPlayer.remove(who, s);
        s.installed = null;
        s.gen++;
        persist(s);
    }

    public void setStatus(Sim s, String status) {
        s.status = status;
        persist(s);
    }

    private void persist(Sim s) {
        final String id = s.id;
        final String number = s.number;
        final String op = s.operator;
        final String owner = s.owner == null ? null : s.owner.toString();
        final String st = s.status;
        final long act = s.activated;
        final int gen = s.gen;
        final String inst = s.installed == null ? null : s.installed.toString();
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_sim(id, number, operator, owner, status, activated, gen, installed) VALUES (?,?,?,?,?,?,?,?) "
                    + "ON CONFLICT(id) DO UPDATE SET owner=excluded.owner, status=excluded.status, gen=excluded.gen, installed=excluded.installed")) {
                ps.setString(1, id);
                ps.setString(2, number);
                ps.setString(3, op);
                ps.setString(4, owner);
                ps.setString(5, st);
                ps.setLong(6, act);
                ps.setInt(7, gen);
                ps.setString(8, inst);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ preferinte telefon (fundal, mod luminos)

    public void savePrefs(UUID u, int wallpaper, boolean light) {
        final String key = u.toString();
        plugin.db().run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO core_phone_prefs(uuid, wallpaper, light) VALUES (?,?,?) ON CONFLICT(uuid) DO UPDATE SET wallpaper=excluded.wallpaper, light=excluded.light")) {
                ps.setString(1, key);
                ps.setInt(2, wallpaper);
                ps.setInt(3, light ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Fundal si mod luminos (async); callback pe thread-ul principal. */
    public void loadPrefs(UUID u, java.util.function.BiConsumer<Integer, Boolean> done) {
        final String key = u.toString();
        plugin.db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT wallpaper, light FROM core_phone_prefs WHERE uuid=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new int[] {rs.getInt(1), rs.getInt(2)};
                }
            }
            return new int[] {0, 0};
        }).whenComplete((r, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || r == null) done.accept(0, false);
            else done.accept(r[0], r[1] == 1);
        }));
    }

    public static String statusLabel(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
