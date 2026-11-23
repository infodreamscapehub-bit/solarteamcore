package ro.solarstudios.flowcity.bridge.news;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;
import ro.solarstudios.flowcity.bridge.phone.NumberLogic;
import ro.solarstudios.flowcity.bridge.phone.SimStore;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * CNN Anunturi: anunturi platite ale jucatorilor (vand, cumpar, inchiriez, servicii, joburi, recrutare, diverse).
 * Toata logica (taxe prin Vault/economia serverului, cooldown, limite, moderare, expirare) ruleaza aici, pe server; clientul doar
 * cere operatii si afiseaza. Numarul de telefon al anuntului vine ALWAYS din cartela SIM activa a autorului (nu din client).
 * Config: ads.yml. Permisiuni: flowcity.ads.use (toti), flowcity.ads.moderate (staff CNN, implicit OP).
 * Canal "ads": client -> server: info, list, publish, delete, extend, mod, suspend, history; server -> client: info, list, res, history.
 */
public final class AdsModule extends JsonModule {
    private static final String PERM_USE = "flowcity.ads.use";
    private static final String PERM_MOD = "flowcity.ads.moderate";
    private static final String PERM_ADMIN = "flowcity.ads.admin";

    private static final class Ad {
        long id;
        UUID owner;
        String author = "";
        String cat = "";
        String title = "";
        String body = "";
        long price = -1;
        String phone = "";
        boolean promoted;
        String status = "ACTIVE";
        long created;
        long expires;
        int durationH;
        int extendsUsed;
        double fee;
        String hash = "";
        String reason = "";
        int edits;
        long lastEdit;
        boolean edited;
    }

    private YamlConfiguration cfg;
    private Db db;
    private final Map<Long, Ad> ads = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAny = new HashMap<>();
    private final Map<UUID, Long> lastPromo = new HashMap<>();
    private final Map<UUID, Long> suspendedUntil = new HashMap<>();
    private final Map<UUID, String> suspendReason = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Long> lastOp = new HashMap<>();

    @Override
    public String id() {
        return "ads";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "ads");
        db = plugin.db();
        Commands.permission(PERM_USE, "Poate publica si vedea anunturi CNN", PermissionDefault.TRUE);
        Commands.permission(PERM_ADMIN, "Configureaza CNN Anunturi din interfata (taxe, cooldown, durata)", PermissionDefault.OP);
        Commands.permission(PERM_MOD, "Modereaza anunturile CNN (aproba, respinge, sterge, suspenda)", PermissionDefault.OP);
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_ads (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, author TEXT NOT NULL, cat TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '', price INTEGER NOT NULL DEFAULT -1, phone TEXT NOT NULL DEFAULT '', promoted INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL, duration INTEGER NOT NULL DEFAULT 24, extends INTEGER NOT NULL DEFAULT 0, fee REAL NOT NULL DEFAULT 0, hash TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '')");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_ads_owner ON core_ads(owner, status)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_ads_state (uuid TEXT PRIMARY KEY, last_any INTEGER NOT NULL DEFAULT 0, last_promo INTEGER NOT NULL DEFAULT 0, suspended_until INTEGER NOT NULL DEFAULT 0, suspend_reason TEXT NOT NULL DEFAULT '')");
                    try {
                        st.execute("ALTER TABLE core_ads ADD COLUMN edits INTEGER NOT NULL DEFAULT 0");
                    } catch (Exception ignored) {
                        // coloana exista deja
                    }
                    st.execute("CREATE TABLE IF NOT EXISTS core_ads_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, ad_id INTEGER NOT NULL DEFAULT 0, detail TEXT NOT NULL DEFAULT '')");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
            load();
        } catch (Exception e) {
            plugin.getLogger().severe("CNN Anunturi: nu pot porni: " + e);
            return;
        }
        on("info", (p, j) -> sendInfo(p));
        on("list", (p, j) -> sendList(p));
        on("publish", this::publish);
        on("delete", (p, j) -> delete(p, (long) num(j, "id", -1)));
        on("extend", (p, j) -> extend(p, (long) num(j, "id", -1)));
        on("mod", this::moderate);
        on("suspend", this::suspend);
        on("history", (p, j) -> sendHistory(p));
        on("edit", this::edit);
        on("promote", (p, j) -> promote(p, (long) num(j, "id", -1)));
        on("all", (p, j) -> sendAll(p));
        on("cfg.set", this::cfgSet);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickExpire, 20L * 30, 20L * 60);
    }

    // ------------------------------------------------------------------ config

    private boolean enabled() {
        return cfg.getBoolean("enabled", true);
    }

    private double feeStandard() {
        return Math.max(0, cfg.getDouble("fees.standard", 500.0));
    }

    private double feePromoted() {
        return Math.max(0, cfg.getDouble("fees.promoted", 1500.0));
    }

    private double feeExtend() {
        return Math.max(0, cfg.getDouble("fees.extend", 300.0));
    }

    private List<Integer> durations() {
        List<Integer> l = new ArrayList<>();
        for (Integer i : cfg.getIntegerList("durations-hours")) if (i != null && i > 0 && i <= 24 * 30) l.add(i);
        if (l.isEmpty()) {
            l.add(12);
            l.add(24);
            l.add(48);
            l.add(72);
        }
        return l;
    }

    /** Categoriile CNN 2.0 (id -> eticheta), din categories-v2. */
    private Map<String, String> categories() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        ConfigurationSection v2 = cfg.getConfigurationSection("categories-v2");
        if (v2 != null) {
            for (String k : v2.getKeys(false)) m.put(k.toLowerCase(Locale.ROOT), v2.getString(k + ".label", k));
            if (!m.isEmpty()) return m;
        }
        ConfigurationSection sec = cfg.getConfigurationSection("categories");
        if (sec != null) for (String k : sec.getKeys(false)) m.put(k.toLowerCase(Locale.ROOT), sec.getString(k, k));
        if (m.isEmpty()) m.put("diverse", "Diverse");
        return m;
    }

    private String catColor(String cat) {
        return cfg.getString("categories-v2." + cat + ".color", "#A0AEC0");
    }

    private String catIcon(String cat) {
        return cfg.getString("categories-v2." + cat + ".icon", "puncte");
    }

    /** Eticheta oricarei categorii (si a celor vechi, pentru anunturile publicate inainte de CNN 2.0). */
    private String anyLabel(String cat) {
        String l = categories().get(cat);
        if (l != null) return l;
        return cfg.getString("categories." + cat, cat);
    }

    private boolean priceCat(String cat) {
        if (cfg.isConfigurationSection("categories-v2." + cat)) return cfg.getBoolean("categories-v2." + cat + ".price", false);
        for (String s : cfg.getStringList("price-categories")) if (s.equalsIgnoreCase(cat)) return true;
        return false;
    }

    private int stdCooldownMin() {
        return Math.max(0, cfg.getInt("cooldown-minutes", 30));
    }

    /** -1 = acelasi cooldown ca anunturile standard; 0 = fara cooldown (explicit); >0 = cooldown propriu. */
    private int promoCooldownMin() {
        return cfg.getInt("promoted.cooldown-minutes", -1);
    }

    // ------------------------------------------------------------------ date

    private void load() throws Exception {
        final long since = System.currentTimeMillis() - Math.max(1, cfg.getInt("history-days", 7)) * 86_400_000L;
        List<Ad> rows = db.async(c -> {
            List<Ad> l = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, owner, author, cat, title, body, price, phone, promoted, status, created, expires, duration, extends, fee, hash, reason, edits FROM core_ads WHERE status IN ('ACTIVE','PENDING') OR created>?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Ad a = new Ad();
                        a.id = rs.getLong(1);
                        try {
                            a.owner = UUID.fromString(rs.getString(2));
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        a.author = rs.getString(3);
                        a.cat = rs.getString(4);
                        a.title = rs.getString(5);
                        a.body = rs.getString(6);
                        a.price = rs.getLong(7);
                        a.phone = rs.getString(8);
                        a.promoted = rs.getInt(9) == 1;
                        a.status = rs.getString(10);
                        a.created = rs.getLong(11);
                        a.expires = rs.getLong(12);
                        a.durationH = rs.getInt(13);
                        a.extendsUsed = rs.getInt(14);
                        a.fee = rs.getDouble(15);
                        a.hash = rs.getString(16);
                        a.reason = rs.getString(17);
                        a.edits = rs.getInt(18);
                        l.add(a);
                    }
                }
            }
            return l;
        }).get(8, TimeUnit.SECONDS);
        for (Ad a : rows) ads.put(a.id, a);
        db.async(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, last_any, last_promo, suspended_until, suspend_reason FROM core_ads_state")) {
                while (rs.next()) {
                    try {
                        UUID u = UUID.fromString(rs.getString(1));
                        lastAny.put(u, rs.getLong(2));
                        lastPromo.put(u, rs.getLong(3));
                        if (rs.getLong(4) > 0) {
                            suspendedUntil.put(u, rs.getLong(4));
                            suspendReason.put(u, rs.getString(5));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // rand invalid
                    }
                }
            }
            return null;
        }).get(8, TimeUnit.SECONDS);
    }

    private void saveState(UUID u) {
        final String key = u.toString();
        final long a = lastAny.getOrDefault(u, 0L);
        final long pr = lastPromo.getOrDefault(u, 0L);
        final long su = suspendedUntil.getOrDefault(u, 0L);
        final String re = suspendReason.getOrDefault(u, "");
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO core_ads_state(uuid, last_any, last_promo, suspended_until, suspend_reason) VALUES (?,?,?,?,?)")) {
                ps.setString(1, key);
                ps.setLong(2, a);
                ps.setLong(3, pr);
                ps.setLong(4, su);
                ps.setString(5, re);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void saveAd(Ad a) {
        final long id = a.id;
        final String st = a.status;
        final long exp = a.expires;
        final int ext = a.extendsUsed;
        final String re = a.reason;
        final String ti = a.title;
        final String bo = a.body;
        final long pr = a.price;
        final int pro = a.promoted ? 1 : 0;
        final int ed = a.edits;
        final String ha = a.hash;
        final double fe = a.fee;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_ads SET status=?, expires=?, extends=?, reason=?, title=?, body=?, price=?, promoted=?, edits=?, hash=?, fee=? WHERE id=?")) {
                ps.setString(1, st);
                ps.setLong(2, exp);
                ps.setInt(3, ext);
                ps.setString(4, re);
                ps.setString(5, ti);
                ps.setString(6, bo);
                ps.setLong(7, pr);
                ps.setInt(8, pro);
                ps.setInt(9, ed);
                ps.setString(10, ha);
                ps.setDouble(11, fe);
                ps.setLong(12, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void log(String actor, String action, long adId, String detail) {
        final long ts = System.currentTimeMillis();
        final String d = detail == null ? "" : detail;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_ads_log(ts, actor, action, ad_id, detail) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, actor);
                ps.setString(3, action);
                ps.setLong(4, adId);
                ps.setString(5, d.length() > 120 ? d.substring(0, 120) : d);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ utilitare

    private boolean isMod(Player p) {
        return p.hasPermission(PERM_MOD);
    }

    private boolean rate(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastOp.get(p.getUniqueId());
        if (last != null && now - last < 600) return false;
        lastOp.put(p.getUniqueId(), now);
        return true;
    }

    private void res(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "res", o);
        if (plugin.channel().hasMod(p)) plugin.channel().toast(p, msg, !ok);
        else p.sendMessage((ok ? "§a" : "§c") + msg);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]", " ").replace("§", "").replaceAll("\\s{2,}", " ").trim();
        return t.length() > max ? t.substring(0, max).trim() : t;
    }

    private static String fold(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT);
    }

    /** Intoarce un motiv de refuz daca textul e abuziv, altfel null. */
    private String abuse(String text) {
        String f = fold(text);
        for (String w : cfg.getStringList("blocked-words")) {
            String x = fold(w).trim();
            if (!x.isEmpty() && f.contains(x)) return "Textul contine cuvinte interzise.";
        }
        if (cfg.getBoolean("block-links", true) && f.matches("(?s).*(https?://|www\\.|discord\\.gg|\\.(com|ro|net|gg|io)(/|\\b)).*")) return "Linkurile nu sunt permise in anunturi.";
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetter(ch)) {
                letters++;
                if (Character.isUpperCase(ch)) upper++;
            }
        }
        if (letters >= 10 && upper * 100 / letters > 80) return "Foloseste litere mici (fara majuscule excesive).";
        if (text.matches("(?s).*(.)\\1{6,}.*")) return "Text cu caractere repetate (spam).";
        return null;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static boolean live(Ad a) {
        return "ACTIVE".equals(a.status) || "PENDING".equals(a.status);
    }

    private int liveCount(UUID u) {
        int n = 0;
        for (Ad a : ads.values()) if (a.owner.equals(u) && live(a)) n++;
        return n;
    }

    private long suspendedLeft(UUID u) {
        Long t = suspendedUntil.get(u);
        if (t == null) return 0;
        long left = t - System.currentTimeMillis();
        if (left <= 0) {
            suspendedUntil.remove(u);
            suspendReason.remove(u);
            saveState(u);
            return 0;
        }
        return left;
    }

    private long cooldownLeft(UUID u, boolean promoted) {
        long now = System.currentTimeMillis();
        int pc = promoCooldownMin();
        long cdMin;
        long ref;
        if (promoted && pc >= 0) {
            cdMin = pc;
            ref = lastPromo.getOrDefault(u, 0L);
        } else {
            cdMin = stdCooldownMin();
            ref = lastAny.getOrDefault(u, 0L);
        }
        return Math.max(0, ref + cdMin * 60_000L - now);
    }

    // ------------------------------------------------------------------ info / lista

    private JsonObject adJson(Ad a, UUID viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("id", a.id);
        o.addProperty("cat", a.cat);
        o.addProperty("title", a.title);
        o.addProperty("body", a.body);
        o.addProperty("price", a.price);
        o.addProperty("phone", a.phone.isEmpty() ? "" : NumberLogic.phoneDisplay(a.phone));
        o.addProperty("author", a.author);
        o.addProperty("uuid", a.owner.toString());
        o.addProperty("promoted", a.promoted);
        o.addProperty("status", a.status);
        o.addProperty("created", a.created);
        o.addProperty("expires", a.expires);
        o.addProperty("own", a.owner.equals(viewer));
        o.addProperty("extendsLeft", Math.max(0, cfg.getInt("extend.max-extends", 2) - a.extendsUsed));
        o.addProperty("reason", a.reason);
        o.addProperty("catLabel", anyLabel(a.cat));
        o.addProperty("edits", a.edits);
        o.addProperty("editsLeft", Math.max(0, cfg.getInt("edit.max-edits", 3) - a.edits));
        o.addProperty("fee", a.fee);
        return o;
    }

    private void sendInfo(Player p) {
        UUID u = p.getUniqueId();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        o.addProperty("balance", plugin.money() == null ? 0 : plugin.money().balance(u));
        o.addProperty("feeStandard", feeStandard());
        o.addProperty("feePromoted", feePromoted());
        o.addProperty("feeExtend", feeExtend());
        o.addProperty("promotedEnabled", cfg.getBoolean("promoted.enabled", true));
        o.addProperty("cooldownMs", cooldownLeft(u, false));
        o.addProperty("cooldownPromoMs", cooldownLeft(u, true));
        o.addProperty("suspendedMs", suspendedLeft(u));
        o.addProperty("suspendReason", suspendReason.getOrDefault(u, ""));
        JsonArray du = new JsonArray();
        for (int d : durations()) du.add(d);
        o.add("durations", du);
        JsonArray cats = new JsonArray();
        for (Map.Entry<String, String> e : categories().entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", e.getKey());
            c.addProperty("label", e.getValue());
            c.addProperty("price", priceCat(e.getKey()));
            c.addProperty("color", catColor(e.getKey()));
            c.addProperty("icon", catIcon(e.getKey()));
            cats.add(c);
        }
        o.add("cats", cats);
        o.addProperty("titleMin", cfg.getInt("limits.title-min", 5));
        o.addProperty("titleMax", cfg.getInt("limits.title-max", 48));
        o.addProperty("descMin", cfg.getInt("limits.description-min", 10));
        o.addProperty("descMax", cfg.getInt("limits.description-max", 240));
        o.addProperty("maxPrice", cfg.getLong("limits.max-price", 1_000_000_000L));
        o.addProperty("maxActive", cfg.getInt("limits.max-active-per-player", 3));
        o.addProperty("active", liveCount(u));
        o.addProperty("moderation", cfg.getBoolean("moderation.enabled", false));
        o.addProperty("extendAllowed", cfg.getBoolean("extend.enabled", true));
        o.addProperty("isMod", isMod(p));
        SimStore sims = SimStore.get();
        SimStore.Sim sim = sims == null ? null : sims.activeOf(u);
        o.addProperty("phone", sim == null ? "" : NumberLogic.phoneDisplay(sim.number));
        o.addProperty("requireSim", cfg.getBoolean("require-sim", true));
        o.addProperty("defaultDuration", cfg.getInt("default-duration-hours", 24));
        o.addProperty("editAllowed", cfg.getBoolean("edit.enabled", true));
        o.addProperty("feeEdit", Math.max(0, cfg.getDouble("edit.fee", 0)));
        o.addProperty("editMax", cfg.getInt("edit.max-edits", 3));
        o.addProperty("promoteAllowed", cfg.getBoolean("promote.enabled", true) && cfg.getBoolean("promoted.enabled", true));
        o.addProperty("feePromote", Math.max(0, cfg.getDouble("promote.fee", 1000)));
        o.addProperty("isAdmin", p.hasPermission(PERM_ADMIN));
        o.addProperty("cooldownMin", stdCooldownMin());
        o.addProperty("extendHours", cfg.getInt("extend.hours", 24));
        send(p, "info", o);
    }

    private void sendList(Player p) {
        UUID u = p.getUniqueId();
        boolean mod = isMod(p);
        List<Ad> pub = new ArrayList<>();
        List<Ad> own = new ArrayList<>();
        List<Ad> pend = new ArrayList<>();
        for (Ad a : ads.values()) {
            if ("ACTIVE".equals(a.status)) pub.add(a);
            if (a.owner.equals(u)) own.add(a);
            if (mod && "PENDING".equals(a.status)) pend.add(a);
        }
        Comparator<Ad> cmp = Comparator.<Ad>comparingInt(a -> a.promoted ? 0 : 1).thenComparing((a, b) -> Long.compare(b.created, a.created));
        pub.sort(cmp);
        own.sort((a, b) -> Long.compare(b.created, a.created));
        pend.sort((a, b) -> Long.compare(a.created, b.created));
        int cap = Math.max(20, cfg.getInt("list-max", 150));
        JsonObject o = new JsonObject();
        JsonArray pa = new JsonArray();
        for (int i = 0; i < Math.min(cap, pub.size()); i++) pa.add(adJson(pub.get(i), u));
        o.add("items", pa);
        JsonArray oa = new JsonArray();
        for (int i = 0; i < Math.min(40, own.size()); i++) oa.add(adJson(own.get(i), u));
        o.add("own", oa);
        JsonArray qa = new JsonArray();
        for (int i = 0; i < Math.min(60, pend.size()); i++) qa.add(adJson(pend.get(i), u));
        o.add("pending", qa);
        send(p, "list", o);
    }

    @Override
    public void onClientReady(Player p) {
        // aprobari/respingeri aparute cat timp era offline vin ca notificari (NotifyModule); lista se cere la deschidere
    }

    @Override
    public void onQuit(Player p) {
        busy.remove(p.getUniqueId());
        lastOp.remove(p.getUniqueId());
    }

    // ------------------------------------------------------------------ publicare

    private void publish(Player p, JsonObject j) {
        if (!rate(p)) return;
        UUID u = p.getUniqueId();
        if (!enabled()) {
            res(p, false, "Anunturile sunt dezactivate.");
            return;
        }
        if (!p.hasPermission(PERM_USE)) {
            res(p, false, "Nu ai permisiunea de a publica anunturi.");
            return;
        }
        if (busy.contains(u)) return;
        long susp = suspendedLeft(u);
        if (susp > 0) {
            res(p, false, "Publicarea ta este suspendata inca " + fmt(susp) + ". " + suspendReason.getOrDefault(u, ""));
            return;
        }
        String phone = "";
        if (cfg.getBoolean("require-sim", true)) {
            SimStore sims = SimStore.get();
            SimStore.Sim sim = sims == null ? null : sims.activeOf(u);
            if (sim == null) {
                res(p, false, "Ai nevoie de o cartela SIM activa in telefon: numarul tau apare in anunt.");
                return;
            }
            phone = sim.number;
        }
        String cat = str(j, "cat", "").toLowerCase(Locale.ROOT);
        if (!categories().containsKey(cat)) {
            res(p, false, "Categorie invalida.");
            return;
        }
        String title = clean(str(j, "title", ""), 200);
        String body = clean(str(j, "body", ""), 2000);
        int tMin = cfg.getInt("limits.title-min", 5);
        int tMax = cfg.getInt("limits.title-max", 48);
        int dMin = cfg.getInt("limits.description-min", 10);
        int dMax = cfg.getInt("limits.description-max", 240);
        if (title.length() < tMin) {
            res(p, false, "Titlul e prea scurt (minim " + tMin + " caractere).");
            return;
        }
        if (title.length() > tMax) {
            res(p, false, "Titlul e prea lung (maxim " + tMax + " caractere).");
            return;
        }
        if (body.length() < dMin) {
            res(p, false, "Descrierea e prea scurta (minim " + dMin + " caractere).");
            return;
        }
        if (body.length() > dMax) {
            res(p, false, "Descrierea e prea lunga (maxim " + dMax + " caractere).");
            return;
        }
        String bad = abuse(title + " " + body);
        if (bad != null) {
            res(p, false, bad);
            return;
        }
        long price = -1;
        if (priceCat(cat)) {
            double raw = num(j, "price", -1);
            long maxPrice = cfg.getLong("limits.max-price", 1_000_000_000L);
            if (Double.isNaN(raw) || raw < 1 || raw > maxPrice || raw != Math.floor(raw)) {
                res(p, false, "Pret invalid (intre 1 si " + maxPrice + ").");
                return;
            }
            price = (long) raw;
        }
        int dur = (int) num(j, "duration", 24);
        if (!durations().contains(dur)) {
            res(p, false, "Durata aleasa nu este permisa.");
            return;
        }
        boolean promoted = bool(j, "promoted", false);
        if (promoted && !cfg.getBoolean("promoted.enabled", true)) {
            res(p, false, "Anunturile promovate sunt dezactivate.");
            return;
        }
        long cd = cooldownLeft(u, promoted);
        if (cd > 0) {
            res(p, false, "Cooldown activ: poti publica din nou peste " + fmt(cd) + ".");
            return;
        }
        if (liveCount(u) >= cfg.getInt("limits.max-active-per-player", 3)) {
            res(p, false, "Ai deja numarul maxim de anunturi active/in asteptare.");
            return;
        }
        String h = hash(cat + "|" + fold(title) + "|" + fold(body));
        long dupMs = Math.max(0, cfg.getInt("duplicate-hours", 24)) * 3_600_000L;
        for (Ad a : ads.values()) {
            if (a.owner.equals(u) && a.hash.equals(h) && (live(a) || System.currentTimeMillis() - a.created < dupMs)) {
                res(p, false, "Ai publicat deja acest anunt.");
                return;
            }
        }
        if (plugin.money() == null) {
            res(p, false, "Economia nu este disponibila.");
            return;
        }
        double fee = promoted ? feePromoted() : feeStandard();
        if (fee > 0 && !plugin.money().has(u, fee)) {
            res(p, false, "Fonduri insuficiente: taxa este $" + Math.round(fee) + ". Nu s-a retras nimic.");
            return;
        }
        // toate conditiile sunt validate: retragem banii O SINGURA DATA
        if (fee > 0 && !plugin.money().withdraw(u, fee)) {
            res(p, false, "Plata a esuat. Nu s-a retras nimic.");
            return;
        }
        long now = System.currentTimeMillis();
        final long prevAny = lastAny.getOrDefault(u, 0L);
        final long prevPromo = lastPromo.getOrDefault(u, 0L);
        lastAny.put(u, now);
        if (promoted) lastPromo.put(u, now);
        saveState(u);
        busy.add(u);
        final Ad a = new Ad();
        a.owner = u;
        a.author = p.getName();
        a.cat = cat;
        a.title = title;
        a.body = body;
        a.price = price;
        a.phone = phone;
        a.promoted = promoted;
        a.created = now;
        a.durationH = dur;
        a.expires = now + dur * 3_600_000L;
        a.fee = fee;
        a.hash = h;
        boolean needsApproval = cfg.getBoolean("moderation.enabled", false) && !isMod(p);
        a.status = needsApproval ? "PENDING" : "ACTIVE";
        if (needsApproval) a.expires = now + dur * 3_600_000L; // se recalculeaza la aprobare
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_ads(owner, author, cat, title, body, price, phone, promoted, status, created, expires, duration, extends, fee, hash, reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,'')")) {
                ps.setString(1, a.owner.toString());
                ps.setString(2, a.author);
                ps.setString(3, a.cat);
                ps.setString(4, a.title);
                ps.setString(5, a.body);
                ps.setLong(6, a.price);
                ps.setString(7, a.phone);
                ps.setInt(8, a.promoted ? 1 : 0);
                ps.setString(9, a.status);
                ps.setLong(10, a.created);
                ps.setLong(11, a.expires);
                ps.setInt(12, a.durationH);
                ps.setDouble(13, a.fee);
                ps.setString(14, a.hash);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }).whenComplete((id, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(u);
            if (err != null || id == null || id <= 0) {
                if (fee > 0) plugin.money().deposit(u, fee); // ramburs: anuntul nu s-a salvat
                lastAny.put(u, prevAny);
                lastPromo.put(u, prevPromo);
                saveState(u);
                Player pp = Bukkit.getPlayer(u);
                if (pp != null) res(pp, false, "Eroare la salvare. Banii au fost returnati.");
                return;
            }
            a.id = id;
            ads.put(id, a);
            log(a.author, "PUBLISH", id, a.cat + " | " + a.title + (promoted ? " | promovat" : ""));
            Player pp = Bukkit.getPlayer(u);
            if (pp != null) {
                res(pp, true, needsApproval ? "Anunt trimis spre aprobare (taxa $" + Math.round(fee) + ")." : "Anunt publicat (taxa $" + Math.round(fee) + ").");
                plugin.notifications().send(u, needsApproval ? NotifyModule.Type.INFO : NotifyModule.Type.OK, needsApproval ? "Anunt in asteptare" : "Anunt publicat",
                    "\"" + a.title + "\" - taxa $" + Math.round(fee) + (needsApproval ? ", asteapta aprobarea." : ", expira in " + a.durationH + " h."), null, "ads");
                sendInfo(pp);
                sendList(pp);
            }
            if (needsApproval) {
                for (Player m : Bukkit.getOnlinePlayers()) {
                    if (isMod(m)) plugin.notifications().send(m.getUniqueId(), NotifyModule.Type.NEWS, "Anunt de aprobat", a.author + ": " + a.title, null, "ads");
                }
            } else if (promoted && cfg.getBoolean("promoted.broadcast", false)) {
                for (Player m : Bukkit.getOnlinePlayers()) {
                    if (!m.getUniqueId().equals(u)) plugin.notifications().send(m.getUniqueId(), NotifyModule.Type.NEWS, "Anunt promovat", a.title, null, "ads");
                }
            }
        }));
    }

    private static String fmt(long ms) {
        long s = Math.max(1, ms / 1000);
        if (s >= 86_400) return (s / 86_400) + " zile " + ((s % 86_400) / 3600) + " h";
        if (s >= 3600) return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
        if (s >= 60) return (s / 60) + " min " + (s % 60) + " s";
        return s + " s";
    }

    // ------------------------------------------------------------------ stergere / prelungire

    private void delete(Player p, long id) {
        if (!rate(p)) return;
        Ad a = ads.get(id);
        if (a == null || "DELETED".equals(a.status)) {
            res(p, false, "Anuntul nu exista.");
            return;
        }
        boolean own = a.owner.equals(p.getUniqueId());
        if (!own && !isMod(p)) {
            res(p, false, "Nu poti sterge anuntul altui jucator.");
            return;
        }
        a.status = "DELETED";
        saveAd(a);
        log(p.getName(), own ? "DELETE_OWN" : "DELETE_MOD", id, a.title);
        if (!own) plugin.notifications().send(a.owner, NotifyModule.Type.BAD, "Anunt sters", "Anuntul \"" + a.title + "\" a fost sters de un moderator.", null, "ads");
        res(p, true, "Anunt sters.");
        sendList(p);
    }

    private void extend(Player p, long id) {
        if (!rate(p)) return;
        UUID u = p.getUniqueId();
        if (!cfg.getBoolean("extend.enabled", true)) {
            res(p, false, "Prelungirea nu este permisa.");
            return;
        }
        Ad a = ads.get(id);
        if (a == null || !a.owner.equals(u)) {
            res(p, false, "Anuntul nu exista sau nu este al tau.");
            return;
        }
        if (!"ACTIVE".equals(a.status) && !"EXPIRED".equals(a.status)) {
            res(p, false, "Acest anunt nu poate fi prelungit.");
            return;
        }
        if (suspendedLeft(u) > 0) {
            res(p, false, "Publicarea ta este suspendata.");
            return;
        }
        int max = cfg.getInt("extend.max-extends", 2);
        if (a.extendsUsed >= max) {
            res(p, false, "Ai folosit toate prelungirile (" + max + ").");
            return;
        }
        if ("EXPIRED".equals(a.status) && liveCount(u) >= cfg.getInt("limits.max-active-per-player", 3)) {
            res(p, false, "Ai deja numarul maxim de anunturi active.");
            return;
        }
        double fee = feeExtend();
        if (plugin.money() == null || (fee > 0 && !plugin.money().has(u, fee))) {
            res(p, false, "Fonduri insuficiente: prelungirea costa $" + Math.round(fee) + ". Nu s-a retras nimic.");
            return;
        }
        if (fee > 0 && !plugin.money().withdraw(u, fee)) {
            res(p, false, "Plata a esuat. Nu s-a retras nimic.");
            return;
        }
        int hours = Math.max(1, cfg.getInt("extend.hours", 24));
        long base = Math.max(System.currentTimeMillis(), a.expires);
        a.expires = base + hours * 3_600_000L;
        a.extendsUsed++;
        a.status = "ACTIVE";
        saveAd(a);
        log(p.getName(), "EXTEND", id, "+" + hours + "h");
        res(p, true, "Anunt prelungit cu " + hours + " h (taxa $" + Math.round(fee) + ").");
        sendInfo(p);
        sendList(p);
    }

    // ------------------------------------------------------------------ moderare

    private void moderate(Player p, JsonObject j) {
        if (!isMod(p)) {
            res(p, false, "Nu ai permisiunea de moderare.");
            return;
        }
        if (!rate(p)) return;
        long id = (long) num(j, "id", -1);
        String action = str(j, "action", "");
        String reason = clean(str(j, "reason", ""), 80);
        Ad a = ads.get(id);
        if (a == null) {
            res(p, false, "Anuntul nu exista.");
            return;
        }
        if ("approve".equals(action)) {
            if (!"PENDING".equals(a.status)) {
                res(p, false, "Anuntul nu este in asteptare.");
                return;
            }
            a.status = "ACTIVE";
            if (!a.edited) a.expires = System.currentTimeMillis() + a.durationH * 3_600_000L;
            a.edited = false;
            a.reason = "";
            saveAd(a);
            log(p.getName(), "APPROVE", id, a.title);
            plugin.notifications().send(a.owner, NotifyModule.Type.OK, "Anunt aprobat", "\"" + a.title + "\" este acum public.", null, "ads");
            res(p, true, "Anunt aprobat.");
        } else if ("reject".equals(action)) {
            if (!"PENDING".equals(a.status)) {
                res(p, false, "Anuntul nu este in asteptare.");
                return;
            }
            a.status = "REJECTED";
            a.reason = reason.isEmpty() ? "Nerespectarea regulilor" : reason;
            saveAd(a);
            boolean refund = !a.edited && cfg.getBoolean("moderation.refund-on-reject", true) && a.fee > 0 && plugin.money() != null;
            if (refund) plugin.money().deposit(a.owner, a.fee);
            log(p.getName(), "REJECT", id, a.title + " | " + a.reason);
            plugin.notifications().send(a.owner, NotifyModule.Type.BAD, "Anunt respins", "\"" + a.title + "\": " + a.reason + (refund ? " (taxa returnata)" : ""), null, "ads");
            res(p, true, "Anunt respins.");
        } else if ("delete".equals(action)) {
            if ("DELETED".equals(a.status)) {
                res(p, false, "Anuntul e deja sters.");
                return;
            }
            a.status = "DELETED";
            a.reason = reason;
            saveAd(a);
            log(p.getName(), "DELETE_MOD", id, a.title + (reason.isEmpty() ? "" : " | " + reason));
            plugin.notifications().send(a.owner, NotifyModule.Type.BAD, "Anunt sters", "\"" + a.title + "\" a fost sters de un moderator." + (reason.isEmpty() ? "" : " Motiv: " + reason), null, "ads");
            res(p, true, "Anunt sters.");
        } else {
            res(p, false, "Actiune necunoscuta.");
            return;
        }
        sendList(p);
    }

    /** Suspenda (minute > 0) sau ridica suspendarea (minute = 0) publicarii autorului unui anunt. */
    private void suspend(Player p, JsonObject j) {
        if (!isMod(p)) {
            res(p, false, "Nu ai permisiunea de moderare.");
            return;
        }
        if (!rate(p)) return;
        Ad a = ads.get((long) num(j, "id", -1));
        if (a == null) {
            res(p, false, "Anuntul nu exista.");
            return;
        }
        long minutes = (long) Math.max(0, Math.min(60L * 24 * 90, num(j, "minutes", 0)));
        String reason = clean(str(j, "reason", ""), 80);
        UUID u = a.owner;
        if (minutes <= 0) {
            suspendedUntil.remove(u);
            suspendReason.remove(u);
            log(p.getName(), "UNSUSPEND", a.id, a.author);
            plugin.notifications().send(u, NotifyModule.Type.OK, "Publicare reactivata", "Poti publica din nou anunturi.", null, "ads");
            res(p, true, "Suspendare ridicata pentru " + a.author + ".");
        } else {
            suspendedUntil.put(u, System.currentTimeMillis() + minutes * 60_000L);
            suspendReason.put(u, reason);
            log(p.getName(), "SUSPEND", a.id, a.author + " | " + minutes + " min" + (reason.isEmpty() ? "" : " | " + reason));
            plugin.notifications().send(u, NotifyModule.Type.BAD, "Publicare suspendata", fmt(minutes * 60_000L) + (reason.isEmpty() ? "" : ": " + reason), null, "ads");
            res(p, true, a.author + " nu mai poate publica " + fmt(minutes * 60_000L) + ".");
        }
        saveState(u);
    }

    private void sendHistory(Player p) {
        if (!isMod(p)) return;
        db.async(c -> {
            JsonArray arr = new JsonArray();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT ts, actor, action, ad_id, detail FROM core_ads_log ORDER BY id DESC LIMIT 80")) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("ts", rs.getLong(1));
                    o.addProperty("actor", rs.getString(2));
                    o.addProperty("action", rs.getString(3));
                    o.addProperty("id", rs.getLong(4));
                    o.addProperty("detail", rs.getString(5));
                    arr.add(o);
                }
            }
            return arr;
        }).whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || arr == null || !p.isOnline()) return;
            JsonObject o = new JsonObject();
            o.add("items", arr);
            send(p, "history", o);
        }));
    }

    // ------------------------------------------------------------------ editare / promovare

    /**
     * Editare: doar titlul, descrierea si pretul. Categoria si tipul (standard/promovat) nu se schimba (pentru asta se publica un anunt
     * nou, cu taxa si cooldown). Editarea nu reseteaza expirarea, are limita per anunt si pauza intre editari; cu moderarea pornita
     * anuntul reintra in asteptare (fara ramburs la respingere: taxa a fost pentru publicarea deja facuta).
     */
    private void edit(Player p, JsonObject j) {
        if (!rate(p)) return;
        UUID u = p.getUniqueId();
        if (!cfg.getBoolean("edit.enabled", true)) {
            res(p, false, "Editarea anunturilor este dezactivata.");
            return;
        }
        Ad a = ads.get((long) num(j, "id", -1));
        if (a == null || !a.owner.equals(u)) {
            res(p, false, "Anuntul nu exista sau nu este al tau.");
            return;
        }
        if (!live(a)) {
            res(p, false, "Doar anunturile active sau in asteptare pot fi editate.");
            return;
        }
        if (suspendedLeft(u) > 0) {
            res(p, false, "Publicarea ta este suspendata.");
            return;
        }
        int max = cfg.getInt("edit.max-edits", 3);
        if (a.edits >= max) {
            res(p, false, "Ai folosit toate editarile acestui anunt (" + max + ").");
            return;
        }
        long gap = Math.max(0, cfg.getInt("edit.cooldown-minutes", 10)) * 60_000L;
        long left = a.lastEdit + gap - System.currentTimeMillis();
        if (left > 0) {
            res(p, false, "Poti edita din nou acest anunt peste " + fmt(left) + ".");
            return;
        }
        String title = clean(str(j, "title", ""), 200);
        String body = clean(str(j, "body", ""), 2000);
        int tMin = cfg.getInt("limits.title-min", 5);
        int tMax = cfg.getInt("limits.title-max", 48);
        int dMin = cfg.getInt("limits.description-min", 10);
        int dMax = cfg.getInt("limits.description-max", 240);
        if (title.length() < tMin || title.length() > tMax) {
            res(p, false, "Titlul trebuie sa aiba intre " + tMin + " si " + tMax + " caractere.");
            return;
        }
        if (body.length() < dMin || body.length() > dMax) {
            res(p, false, "Descrierea trebuie sa aiba intre " + dMin + " si " + dMax + " caractere.");
            return;
        }
        String bad = abuse(title + " " + body);
        if (bad != null) {
            res(p, false, bad);
            return;
        }
        long price = a.price;
        if (priceCat(a.cat)) {
            double raw = num(j, "price", -1);
            long maxPrice = cfg.getLong("limits.max-price", 1_000_000_000L);
            if (Double.isNaN(raw) || raw < 1 || raw > maxPrice || raw != Math.floor(raw)) {
                res(p, false, "Pret invalid (intre 1 si " + maxPrice + ").");
                return;
            }
            price = (long) raw;
        }
        String h = hash(a.cat + "|" + fold(title) + "|" + fold(body));
        if (h.equals(a.hash) && price == a.price) {
            res(p, false, "Nu ai schimbat nimic.");
            return;
        }
        for (Ad o : ads.values()) {
            if (o.id != a.id && o.owner.equals(u) && o.hash.equals(h) && live(o)) {
                res(p, false, "Ai deja un anunt identic.");
                return;
            }
        }
        double fee = Math.max(0, cfg.getDouble("edit.fee", 0));
        if (fee > 0) {
            if (plugin.money() == null || !plugin.money().has(u, fee)) {
                res(p, false, "Fonduri insuficiente: editarea costa $" + Math.round(fee) + ". Nu s-a retras nimic.");
                return;
            }
            if (!plugin.money().withdraw(u, fee)) {
                res(p, false, "Plata a esuat. Nu s-a retras nimic.");
                return;
            }
        }
        a.title = title;
        a.body = body;
        a.price = price;
        a.hash = h;
        a.edits++;
        a.lastEdit = System.currentTimeMillis();
        a.fee += fee;
        boolean recheck = cfg.getBoolean("moderation.enabled", false) && !isMod(p) && "ACTIVE".equals(a.status);
        if (recheck) {
            a.status = "PENDING";
            a.edited = true;
        }
        saveAd(a);
        log(p.getName(), "EDIT", a.id, a.title);
        res(p, true, recheck ? "Anunt modificat, trimis din nou spre aprobare." : "Anunt modificat" + (fee > 0 ? " (taxa $" + Math.round(fee) + ")." : "."));
        sendInfo(p);
        sendList(p);
    }

    /** Promovarea unui anunt standard activ, contra taxa (nu prelungeste si nu ocoleste limitele: anuntul exista deja). */
    private void promote(Player p, long id) {
        if (!rate(p)) return;
        UUID u = p.getUniqueId();
        if (!cfg.getBoolean("promote.enabled", true) || !cfg.getBoolean("promoted.enabled", true)) {
            res(p, false, "Promovarea nu este permisa.");
            return;
        }
        Ad a = ads.get(id);
        if (a == null || !a.owner.equals(u)) {
            res(p, false, "Anuntul nu exista sau nu este al tau.");
            return;
        }
        if (!"ACTIVE".equals(a.status) || a.promoted) {
            res(p, false, a.promoted ? "Anuntul este deja promovat." : "Doar anunturile active pot fi promovate.");
            return;
        }
        if (suspendedLeft(u) > 0) {
            res(p, false, "Publicarea ta este suspendata.");
            return;
        }
        int pc = promoCooldownMin();
        if (pc > 0) {
            long left = lastPromo.getOrDefault(u, 0L) + pc * 60_000L - System.currentTimeMillis();
            if (left > 0) {
                res(p, false, "Poti promova din nou peste " + fmt(left) + ".");
                return;
            }
        }
        double fee = Math.max(0, cfg.getDouble("promote.fee", 1000));
        if (plugin.money() == null || (fee > 0 && !plugin.money().has(u, fee))) {
            res(p, false, "Fonduri insuficiente: promovarea costa $" + Math.round(fee) + ". Nu s-a retras nimic.");
            return;
        }
        if (fee > 0 && !plugin.money().withdraw(u, fee)) {
            res(p, false, "Plata a esuat. Nu s-a retras nimic.");
            return;
        }
        a.promoted = true;
        a.fee += fee;
        lastPromo.put(u, System.currentTimeMillis());
        saveState(u);
        saveAd(a);
        log(p.getName(), "PROMOTE", a.id, a.title);
        plugin.notifications().send(u, NotifyModule.Type.OK, "Anunt promovat", "\"" + a.title + "\" apare acum primul.", null, "ads");
        res(p, true, "Anunt promovat (taxa $" + Math.round(fee) + ").");
        sendInfo(p);
        sendList(p);
    }

    // ------------------------------------------------------------------ panel admin

    /** Toate anunturile (inclusiv expirate/sterse/respinse din ultimele zile) pentru moderatori. */
    private void sendAll(Player p) {
        if (!isMod(p)) return;
        List<Ad> l = new ArrayList<>(ads.values());
        l.sort((a, b) -> Long.compare(b.created, a.created));
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(250, l.size()); i++) arr.add(adJson(l.get(i), p.getUniqueId()));
        JsonObject o = new JsonObject();
        o.add("items", arr);
        send(p, "all", o);
    }

    /** Setarile CNN din panelul admin (permisiunea flowcity.ads.admin); validate si salvate in ads.yml. */
    private void cfgSet(Player p, JsonObject j) {
        if (!p.hasPermission(PERM_ADMIN)) {
            res(p, false, "Nu ai permisiunea de a configura CNN.");
            return;
        }
        if (!rate(p)) return;
        String key = str(j, "key", "");
        double v = num(j, "value", Double.NaN);
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            res(p, false, "Valoare invalida.");
            return;
        }
        switch (key) {
            case "fees.standard":
            case "fees.promoted":
            case "fees.extend":
            case "edit.fee":
            case "promote.fee":
                if (v < 0 || v > 100_000_000) {
                    res(p, false, "Taxa trebuie sa fie intre 0 si 100.000.000.");
                    return;
                }
                cfg.set(key, Math.round(v * 100) / 100.0);
                break;
            case "cooldown-minutes":
                if (v < 0 || v > 1440) {
                    res(p, false, "Cooldown intre 0 si 1440 minute.");
                    return;
                }
                cfg.set(key, (int) v);
                break;
            case "default-duration-hours":
                if (!durations().contains((int) v)) {
                    res(p, false, "Durata trebuie sa fie una din: " + durations());
                    return;
                }
                cfg.set(key, (int) v);
                break;
            case "limits.max-active-per-player":
                if (v < 1 || v > 20) {
                    res(p, false, "Maxim anunturi active: intre 1 si 20.");
                    return;
                }
                cfg.set(key, (int) v);
                break;
            case "moderation.enabled":
            case "promoted.enabled":
            case "extend.enabled":
            case "edit.enabled":
                cfg.set(key, v != 0);
                break;
            default:
                res(p, false, "Setare necunoscuta.");
                return;
        }
        ModConfig.save(cfg, plugin, "ads");
        log(p.getName(), "CONFIG", 0, key + " = " + cfg.get(key));
        res(p, true, "Setare salvata: " + key + " = " + cfg.get(key));
        sendInfo(p);
    }

    // ------------------------------------------------------------------ expirare

    private void tickExpire() {
        long now = System.currentTimeMillis();
        long purge = now - Math.max(1, cfg.getInt("history-days", 7)) * 86_400_000L;
        for (Ad a : new ArrayList<>(ads.values())) {
            if ("ACTIVE".equals(a.status) && a.expires <= now) {
                a.status = "EXPIRED";
                saveAd(a);
                log("sistem", "EXPIRE", a.id, a.title);
                plugin.notifications().send(a.owner, NotifyModule.Type.INFO, "Anunt expirat", "\"" + a.title + "\" a expirat. Il poti prelungi din CNN > Anunturi.", null, "ads");
            } else if (!live(a) && a.created < purge) {
                ads.remove(a.id);
            }
        }
    }
}
