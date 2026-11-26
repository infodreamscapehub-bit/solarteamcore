package ro.solarstudios.flowcity.bridge.battlepass;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.claims.ClaimStore;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Battlepass 2.0: sezoane cu perioada si numar de niveluri, doua trasee de recompense (FREE si PREMIUM),
 * misiuni zilnice si saptamanale al caror progres vine din evenimente REALE (joburi FJobs, kilometri condusi,
 * alimentari, cumparaturi, telefon) si editor de administrare in GUI.
 * Progresul si recompensele revendicate se salveaza in battlepass-data.yml, pe sezon; configurarea in battlepass.yml.
 * Ce nu incape in inventar ajunge in {@link ClaimStore}, deci nu se pierde si nu se poate dubla.
 */
public final class BattlepassModule extends JsonModule {
    private static BattlepassModule instance;

    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;
    private boolean dirty;
    private ClaimStore claims;

    /** Tipurile de misiune care au o sursa reala de progres. */
    private static final String MISSION_TYPES = "missions|actions|earn|xp|hours|drive|fuel|shop|phone";
    /** Tipurile de recompensa care chiar pot fi acordate. */
    private static final List<String> REWARD_TYPES = java.util.Arrays.asList("money", "bank", "item", "xp", "command");

    private static final class Obj {
        String id;
        String type;
        String job;
        long target;
        int xp;
        String label;
    }

    @Override
    public String id() {
        return "battlepass";
    }

    @Override
    protected void onEnable() {
        instance = this;
        cfg = ModConfig.load(plugin, "battlepass");
        claims = new ClaimStore(plugin);
        dataFile = new File(plugin.getDataFolder(), "battlepass-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        on("open", (p, j) -> sendState(p));
        on("claim", (p, j) -> claim(p, integer(j, "level", 0), str(j, "track", "free")));
        on("claimAll", (p, j) -> claims.claimAll(p, "battlepass", () -> sendState(p)));
        on("buyPremium", (p, j) -> buyPremium(p));
        on("admin", (p, j) -> {
            if (admin(p)) sendAdmin(p);
        });
        on("admin_season", this::adminSeason);
        on("admin_reward", this::adminReward);
        on("admin_mission", this::adminMission);
        Commands.permission("flowcity.battlepass.admin", "Editeaza battlepass-ul", PermissionDefault.OP);
        registerDriveCommand();
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 20L * 60, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::hoursTick, 20L * 60, 20L * 60);
    }

    @Override
    public void disable() {
        flush();
        instance = null;
    }

    @Override
    public void onClientReady(Player p) {
        sendState(p);
    }

    private void flush() {
        if (!dirty) return;
        dirty = false;
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Battlepass: nu pot salva progresul: " + e);
        }
    }

    private void saveCfg() {
        ModConfig.save(cfg, plugin, "battlepass");
    }

    // ------------------------------------------------------------------ sezon si config

    private boolean enabled() {
        return cfg.getBoolean("enabled", true);
    }

    private String seasonId() {
        String s = cfg.getString("season.id", "s1");
        return s == null || s.trim().isEmpty() ? "s1" : s.trim();
    }

    private String seasonName() {
        return cfg.getString("season.name", "Sezonul 1");
    }

    private int xpPerLevel() {
        return Math.max(10, Math.min(100000, cfg.getInt("season.xp-per-level", cfg.getInt("xp-per-level", 100))));
    }

    private int maxLevel() {
        return Math.max(1, Math.min(200, cfg.getInt("season.levels", cfg.getInt("max-level", 30))));
    }

    private double premiumCost() {
        return Math.max(0, cfg.getDouble("season.premium-cost", 50000));
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(cfg.getString("timezone", "Europe/Bucharest"));
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Bucharest");
        }
    }

    private LocalDate dateOf(String key) {
        String v = cfg.getString("season." + key, "");
        if (v == null || v.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(v.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sezonul e in desfasurare? In afara perioadei progresul se opreste, dar recompensele deja deblocate se pot lua. */
    private boolean seasonRunning() {
        LocalDate now = LocalDate.now(zone());
        LocalDate s = dateOf("start");
        LocalDate e = dateOf("end");
        if (s != null && now.isBefore(s)) return false;
        return e == null || !now.isAfter(e);
    }

    private String dayKey() {
        return LocalDate.now(zone()).toString();
    }

    private String weekKey() {
        LocalDate d = LocalDate.now(zone());
        return String.format(Locale.ROOT, "%d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private List<Obj> pool(String section) {
        List<Obj> out = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList(section)) {
            try {
                Obj o = new Obj();
                o.id = String.valueOf(m.get("id"));
                o.type = String.valueOf(m.get("type")).toLowerCase(Locale.ROOT);
                o.job = m.get("job") == null ? "" : String.valueOf(m.get("job"));
                o.target = Math.max(1, Long.parseLong(String.valueOf(m.get("target"))));
                o.xp = Math.max(0, Integer.parseInt(String.valueOf(m.get("xp"))));
                o.label = m.get("label") == null ? o.id : String.valueOf(m.get("label"));
                if (!o.type.matches(MISSION_TYPES)) continue;
                out.add(o);
            } catch (RuntimeException ignored) {
                // intrare invalida in config: o sar
            }
        }
        return out;
    }

    /** Aceleasi obiective pentru toti jucatorii in aceeasi zi/saptamana (seed = cheia perioadei). */
    private List<Obj> pick(String section, String key, int count) {
        List<Obj> all = pool(section);
        Collections.sort(all, (a, b) -> a.id.compareTo(b.id));
        Collections.shuffle(all, new Random(key.hashCode() * 31L + section.hashCode()));
        return new ArrayList<>(all.subList(0, Math.min(Math.max(0, count), all.size())));
    }

    private List<Obj> daily() {
        return pick("daily", dayKey(), cfg.getInt("daily-count", 3));
    }

    private List<Obj> weekly() {
        return pick("weekly", weekKey(), cfg.getInt("weekly-count", 3));
    }

    // ------------------------------------------------------------------ date jucator (pe sezon)

    private String base(UUID u) {
        return "players." + u + "." + seasonId() + ".";
    }

    /** Reseteaza progresul perioadei cand s-a schimbat ziua / saptamana. */
    private void rollover(UUID u) {
        String b = base(u);
        if (!dayKey().equals(data.getString(b + "dayKey", ""))) {
            data.set(b + "dayKey", dayKey());
            data.set(b + "day", null);
            dirty = true;
        }
        if (!weekKey().equals(data.getString(b + "weekKey", ""))) {
            data.set(b + "weekKey", weekKey());
            data.set(b + "week", null);
            dirty = true;
        }
    }

    private int levelOf(long xp) {
        return (int) Math.min(maxLevel(), xp / xpPerLevel());
    }

    private boolean premium(UUID u) {
        return data.getBoolean(base(u) + "premium", false);
    }

    // ------------------------------------------------------------------ surse reale de progres

    /** Apelat din FJobsModule.payOut la fiecare actiune platita in tura. */
    public static void progress(Player p, String jobId, boolean mission, double pay, long xp) {
        if (instance != null && instance.enabled()) instance.apply(p, jobId, mission, pay, xp, 0, 0, 0, 0, 0);
    }

    /** Metri condusi, raportati de mod prin comanda de consola {@code fcbp drive}. */
    public static void drove(Player p, long meters) {
        if (instance != null && instance.enabled() && meters > 0) instance.apply(p, "", false, 0, 0, 0, meters, 0, 0, 0);
    }

    /** Litri alimentati la benzinarie (apelat din CarsModule dupa plata). */
    public static void fueled(Player p, long liters) {
        if (instance != null && instance.enabled() && liters > 0) instance.apply(p, "", false, 0, 0, 0, 0, liters, 0, 0);
    }

    /** O cumparatura din magazin. */
    public static void bought(Player p, int items) {
        if (instance != null && instance.enabled() && items > 0) instance.apply(p, "", false, 0, 0, 0, 0, 0, items, 0);
    }

    /** Telefonul a fost deschis. */
    public static void phoneUsed(Player p) {
        if (instance != null && instance.enabled()) instance.apply(p, "", false, 0, 0, 0, 0, 0, 0, 1);
    }

    /** Minute lucrate: la fiecare minut, pentru jucatorii aflati in tura. */
    private void hoursTick() {
        if (!enabled() || !seasonRunning()) return;
        if (!hasType("hours")) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (ro.solarstudios.flowcity.bridge.fjobs.FJobsModule.inShift(p)) apply(p, "", false, 0, 0, 1, 0, 0, 0, 0);
        }
    }

    private boolean hasType(String type) {
        for (Obj o : daily()) if (o.type.equals(type)) return true;
        for (Obj o : weekly()) if (o.type.equals(type)) return true;
        return false;
    }

    /** XP de battlepass din alta sursa (ex. premiu la roata). Intoarce false daca sistemul e oprit. */
    public static boolean grantXp(Player p, int xp, String why) {
        if (instance == null || !instance.enabled() || xp <= 0) return false;
        instance.addXp(p, xp, why);
        instance.dirty = true;
        instance.sendState(p);
        return true;
    }

    /** Niveluri intregi de battlepass (se traduc in XP-ul necesar). */
    public static boolean grantLevels(Player p, int levels, String why) {
        if (instance == null || !instance.enabled() || levels <= 0) return false;
        return grantXp(p, levels * instance.xpPerLevel(), why);
    }

    /** XP-ul necesar unui nivel, pentru etichetele altor sisteme. */
    public static int xpForOneLevel() {
        return instance == null ? 100 : instance.xpPerLevel();
    }

    private void apply(Player p, String jobId, boolean mission, double pay, long xp,
                       long minutes, long meters, long liters, long items, long phone) {
        if (!seasonRunning()) return;
        UUID u = p.getUniqueId();
        rollover(u);
        boolean changed = false;
        changed |= applyTo(p, "day", daily(), jobId, mission, pay, xp, minutes, meters, liters, items, phone);
        changed |= applyTo(p, "week", weekly(), jobId, mission, pay, xp, minutes, meters, liters, items, phone);
        if (changed) {
            dirty = true;
            sendState(p);
        }
    }

    private boolean applyTo(Player p, String store, List<Obj> objs, String jobId, boolean mission, double pay, long xp,
                            long minutes, long meters, long liters, long items, long phone) {
        boolean changed = false;
        String b = base(p.getUniqueId()) + store + ".";
        for (Obj o : objs) {
            if (!o.job.isEmpty() && !o.job.equalsIgnoreCase(jobId)) continue;
            if (data.getBoolean(b + o.id + ".done", false)) continue;
            long add;
            switch (o.type) {
                case "missions":
                    add = mission ? 1 : 0;
                    break;
                case "actions":
                    add = jobId.isEmpty() ? 0 : 1;
                    break;
                case "earn":
                    add = Math.round(pay);
                    break;
                case "hours":
                    add = minutes;
                    break;
                case "drive":
                    add = meters;
                    break;
                case "fuel":
                    add = liters;
                    break;
                case "shop":
                    add = items;
                    break;
                case "phone":
                    add = phone;
                    break;
                default:
                    add = Math.max(0, xp);
                    break;
            }
            if (add <= 0) continue;
            long now = Math.min(o.target, data.getLong(b + o.id + ".n", 0) + add);
            data.set(b + o.id + ".n", now);
            changed = true;
            if (now >= o.target) {
                data.set(b + o.id + ".done", true);
                plugin.notifications().send(p, NotifyModule.Type.OK, "Misiune terminata", o.label + " (+" + o.xp + " XP)");
                addXp(p, o.xp, o.label);
            }
        }
        return changed;
    }

    private void addXp(Player p, int xp, String why) {
        String k = base(p.getUniqueId()) + "xp";
        long before = data.getLong(k, 0);
        long after = before + xp;
        data.set(k, after);
        plugin.notifications().send(p, NotifyModule.Type.JOB, "Battlepass +" + xp + " XP", why);
        int lb = levelOf(before);
        int la = levelOf(after);
        if (la > lb) {
            plugin.notifications().send(p, NotifyModule.Type.OK, "Battlepass nivel " + la,
                "Ai o recompensa de revendicat in meniul Battlepass.");
        }
    }

    // ------------------------------------------------------------------ premium

    private void buyPremium(Player p) {
        UUID u = p.getUniqueId();
        if (premium(u)) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Ai deja pass-ul premium in acest sezon.");
            return;
        }
        if (!cfg.getBoolean("season.premium-buyable", true)) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Pass-ul premium nu se poate cumpara acum.");
            return;
        }
        double cost = premiumCost();
        if (cost > 0) {
            if (plugin.money().balance(u) < cost) {
                plugin.notifications().send(p, NotifyModule.Type.BAD, "Fonduri insuficiente",
                    "Pass-ul premium costa " + (long) cost + "$.");
                return;
            }
            if (!plugin.money().withdraw(u, cost)) {
                plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "Plata nu a reusit.");
                return;
            }
            plugin.service().logTransaction(u, "BP_PREMIUM", -cost, "BATTLEPASS", p.getWorld().getName());
        }
        data.set(base(u) + "premium", true);
        dirty = true;
        flush();
        plugin.notifications().send(p, NotifyModule.Type.OK, "Premium activat",
            "Ai deblocat traseul premium pentru " + seasonName() + ".");
        sendState(p);
    }

    // ------------------------------------------------------------------ recompense

    private ConfigurationSection rewardSec(int level, String track) {
        ConfigurationSection r = cfg.getConfigurationSection("rewards." + level + "." + track);
        if (r != null) return r;
        // compatibilitate cu formatul vechi: rewards.<nivel>.money / .label / .command = traseul FREE
        if ("free".equals(track)) return cfg.getConfigurationSection("rewards." + level);
        return null;
    }

    private String rewardLabel(int level, String track) {
        ConfigurationSection r = rewardSec(level, track);
        if (r == null) return "";
        String l = r.getString("label", "");
        if (l != null && !l.isEmpty()) return l;
        String type = r.getString("type", r.contains("money") ? "money" : "");
        double amount = r.getDouble("amount", r.getDouble("money", 0));
        switch (type) {
            case "money":
                return (long) amount + "$";
            case "bank":
                return (long) amount + "$ banca";
            case "item":
                return (long) amount + "x " + String.valueOf(r.getString("material", "")).toLowerCase(Locale.ROOT).replace('_', ' ');
            case "xp":
                return (long) amount + " XP";
            case "command":
                return "premiu special";
            default:
                return amount > 0 ? (long) amount + "$" : "";
        }
    }

    private boolean hasReward(int level, String track) {
        return !rewardLabel(level, track).isEmpty();
    }

    private void claim(Player p, int level, String track) {
        if (!"free".equals(track) && !"premium".equals(track)) return;
        UUID u = p.getUniqueId();
        long xp = data.getLong(base(u) + "xp", 0);
        if (level < 1 || level > maxLevel() || levelOf(xp) < level) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Nu ai ajuns la nivelul " + level + ".");
            return;
        }
        if ("premium".equals(track) && !premium(u)) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Recompensa e pe traseul premium.");
            return;
        }
        if (!hasReward(level, track)) return;
        String key = base(u) + "claimed_" + track;
        List<Integer> claimed = data.getIntegerList(key);
        if (claimed.contains(level)) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Recompensa nivelului " + level + " e deja luata.");
            return;
        }
        // marcam INAINTE de acordare: nu se poate revendica de doua ori
        claimed.add(level);
        data.set(key, claimed);
        dirty = true;
        flush();
        String what = give(p, rewardSec(level, track), rewardLabel(level, track));
        plugin.notifications().send(p, NotifyModule.Type.MONEY, "Battlepass nivel " + level, what);
        sendState(p);
    }

    /** Acorda o recompensa si intoarce descrierea ei. */
    private String give(Player p, ConfigurationSection r, String label) {
        if (r == null) return label;
        UUID u = p.getUniqueId();
        String type = r.getString("type", r.contains("money") ? "money" : "money");
        double amount = r.getDouble("amount", r.getDouble("money", 0));
        switch (type) {
            case "bank":
                if (amount > 0) plugin.bank().deposit(u, amount);
                break;
            case "item": {
                Material m = Material.matchMaterial(String.valueOf(r.getString("material", "")));
                int n = (int) Math.max(1, amount);
                if (m != null && m.isItem()) {
                    int stored = claims.giveOrStore(p, "battlepass", n + "x " + ClaimStore.nice(m), m, n);
                    if (stored > 0) return label + " (" + stored + " te asteapta)";
                } else {
                    plugin.getLogger().warning("Battlepass: material invalid la o recompensa: " + r.getString("material", ""));
                }
                break;
            }
            case "xp":
                p.giveExp((int) Math.max(1, amount));
                break;
            case "command": {
                String cmd = String.valueOf(r.getString("command", "")).trim();
                while (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
                if (!cmd.isEmpty()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
                }
                break;
            }
            default:
                if (amount > 0) plugin.money().deposit(u, amount);
                break;
        }
        // formatul vechi putea avea si o comanda pe langa bani
        String extra = r.getString("command", "");
        if (!"command".equals(type) && extra != null && !extra.trim().isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), extra.trim().replace("%player%", p.getName()));
        }
        return label;
    }

    // ------------------------------------------------------------------ administrare (GUI)

    private boolean admin(Player p) {
        if (p.hasPermission("flowcity.battlepass.admin")) return true;
        plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "Nu ai permisiunea de editare.");
        return false;
    }

    private void sendAdmin(Player p) {
        JsonObject o = new JsonObject();
        JsonObject s = new JsonObject();
        s.addProperty("id", seasonId());
        s.addProperty("name", seasonName());
        s.addProperty("descr", cfg.getString("season.descr", ""));
        s.addProperty("start", cfg.getString("season.start", ""));
        s.addProperty("end", cfg.getString("season.end", ""));
        s.addProperty("levels", maxLevel());
        s.addProperty("xpPerLevel", xpPerLevel());
        s.addProperty("premiumCost", premiumCost());
        s.addProperty("enabled", enabled());
        s.addProperty("running", seasonRunning());
        o.add("season", s);
        JsonArray rw = new JsonArray();
        for (int l = 1; l <= maxLevel(); l++) {
            JsonObject r = new JsonObject();
            r.addProperty("level", l);
            r.addProperty("free", rewardLabel(l, "free"));
            r.addProperty("premium", rewardLabel(l, "premium"));
            rw.add(r);
        }
        o.add("rewards", rw);
        o.add("daily", missionsJson("daily"));
        o.add("weekly", missionsJson("weekly"));
        o.addProperty("types", MISSION_TYPES);
        send(p, "admin", o);
    }

    private JsonArray missionsJson(String section) {
        JsonArray a = new JsonArray();
        for (Map<?, ?> m : cfg.getMapList(section)) {
            JsonObject j = new JsonObject();
            j.addProperty("id", String.valueOf(m.get("id")));
            j.addProperty("type", String.valueOf(m.get("type")));
            j.addProperty("job", m.get("job") == null ? "" : String.valueOf(m.get("job")));
            j.addProperty("target", m.get("target") == null ? 1 : Long.parseLong(String.valueOf(m.get("target"))));
            j.addProperty("xp", m.get("xp") == null ? 0 : Integer.parseInt(String.valueOf(m.get("xp"))));
            j.addProperty("label", m.get("label") == null ? "" : String.valueOf(m.get("label")));
            a.add(j);
        }
        return a;
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : s.replaceAll("[\\p{Cntrl}§]", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Sezonul: nume, descriere, perioada, numar de niveluri, XP pe nivel, cost premium, pornit/oprit. */
    private void adminSeason(Player p, JsonObject j) {
        if (!admin(p)) return;
        if (j.has("newId")) {
            // sezon nou: progresul vechi ramane in fisier sub cheia lui, deci istoricul nu se pierde
            String nid = clean(str(j, "newId", ""), 16).replaceAll("[^A-Za-z0-9_-]", "");
            if (nid.isEmpty()) {
                plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "ID de sezon invalid.");
                return;
            }
            cfg.set("season.id", nid);
            plugin.getLogger().info("Battlepass: " + p.getName() + " a creat sezonul " + nid);
        }
        if (j.has("name")) cfg.set("season.name", clean(str(j, "name", seasonName()), 32));
        if (j.has("descr")) cfg.set("season.descr", clean(str(j, "descr", ""), 96));
        if (j.has("start")) cfg.set("season.start", cleanDate(str(j, "start", "")));
        if (j.has("end")) cfg.set("season.end", cleanDate(str(j, "end", "")));
        if (j.has("levels")) cfg.set("season.levels", (int) Math.max(1, Math.min(200, num(j, "levels", 30))));
        if (j.has("xpPerLevel")) cfg.set("season.xp-per-level", (int) Math.max(10, Math.min(100000, num(j, "xpPerLevel", 100))));
        if (j.has("premiumCost")) cfg.set("season.premium-cost", Math.max(0, Math.min(10_000_000, num(j, "premiumCost", 0))));
        if (j.has("enabled")) cfg.set("enabled", bool(j, "enabled", true));
        saveCfg();
        plugin.notifications().send(p, NotifyModule.Type.OK, "Battlepass", "Sezon salvat.");
        sendAdmin(p);
        for (Player o : Bukkit.getOnlinePlayers()) sendState(o);
    }

    private static String cleanDate(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return "";
        try {
            return LocalDate.parse(t).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Recompensa unui nivel, pe un traseu. Tip gol = sterge recompensa. */
    private void adminReward(Player p, JsonObject j) {
        if (!admin(p)) return;
        int level = integer(j, "level", 0);
        String track = str(j, "track", "free");
        if (level < 1 || level > maxLevel() || (!"free".equals(track) && !"premium".equals(track))) return;
        String path = "rewards." + level + "." + track;
        String type = str(j, "type", "").toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            cfg.set(path, null);
            saveCfg();
            sendAdmin(p);
            return;
        }
        if (!REWARD_TYPES.contains(type)) {
            plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "Tip de recompensa invalid.");
            return;
        }
        double amount = Math.max(0, Math.min(10_000_000, num(j, "amount", 0)));
        String material = clean(str(j, "material", ""), 48);
        String command = clean(str(j, "command", ""), 180);
        while (command.startsWith("/")) command = command.substring(1).trim();
        if ("item".equals(type)) {
            Material m = Material.matchMaterial(material);
            if (m == null || !m.isItem()) {
                plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "Material invalid: " + material);
                return;
            }
            material = m.name();
            if (amount < 1) amount = 1;
        }
        if ("command".equals(type) && command.isEmpty()) {
            plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "Scrie comanda recompensei.");
            return;
        }
        cfg.set(path + ".type", type);
        cfg.set(path + ".amount", amount);
        cfg.set(path + ".material", "item".equals(type) ? material : null);
        cfg.set(path + ".command", "command".equals(type) ? command : null);
        cfg.set(path + ".label", clean(str(j, "label", ""), 32));
        saveCfg();
        plugin.notifications().send(p, NotifyModule.Type.OK, "Battlepass", "Recompensa nivelului " + level + " salvata.");
        sendAdmin(p);
    }

    /** Adauga / modifica / sterge o misiune zilnica sau saptamanala. */
    @SuppressWarnings("unchecked")
    private void adminMission(Player p, JsonObject j) {
        if (!admin(p)) return;
        String section = "weekly".equals(str(j, "section", "daily")) ? "weekly" : "daily";
        String id = clean(str(j, "id", ""), 24).replaceAll("[^A-Za-z0-9_-]", "");
        if (id.isEmpty()) return;
        List<Map<?, ?>> cur = cfg.getMapList(section);
        List<Map<String, Object>> out = new ArrayList<>();
        boolean remove = bool(j, "remove", false);
        boolean found = false;
        for (Map<?, ?> m : cur) {
            if (id.equals(String.valueOf(m.get("id")))) {
                found = true;
                if (remove) continue;
                out.add(missionMap(j, id, section));
            } else {
                out.add((Map<String, Object>) m);
            }
        }
        if (!found && !remove) {
            if (out.size() >= 24) {
                plugin.notifications().send(p, NotifyModule.Type.BAD, "Battlepass", "Maxim 24 de misiuni pe sectiune.");
                return;
            }
            out.add(missionMap(j, id, section));
        }
        cfg.set(section, out);
        saveCfg();
        plugin.notifications().send(p, NotifyModule.Type.OK, "Battlepass", remove ? "Misiune stearsa." : "Misiune salvata.");
        sendAdmin(p);
    }

    private Map<String, Object> missionMap(JsonObject j, String id, String section) {
        String type = str(j, "type", "actions").toLowerCase(Locale.ROOT);
        if (!type.matches(MISSION_TYPES)) type = "actions";
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        String job = clean(str(j, "job", ""), 24);
        if (!job.isEmpty()) m.put("job", job);
        m.put("target", (long) Math.max(1, Math.min(10_000_000L, num(j, "target", 1))));
        m.put("xp", (int) Math.max(0, Math.min(100000, num(j, "xp", 10))));
        String label = clean(str(j, "label", ""), 48);
        m.put("label", label.isEmpty() ? id : label);
        return m;
    }

    /** Comanda doar pentru consola: modul raporteaza metrii condusi. */
    private void registerDriveCommand() {
        Commands.register(plugin, "fcbp", null, (s, c, l, a) -> {
            if (s instanceof Player) return true;   // doar consola (modul)
            if (a.length >= 3 && a[0].equalsIgnoreCase("drive")) {
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) return true;
                try {
                    drove(p, Long.parseLong(a[2]));
                } catch (NumberFormatException ignored) {
                    // raport invalid
                }
            }
            return true;
        }, (s, c, l, a) -> new ArrayList<String>());
    }

    // ------------------------------------------------------------------ stare

    private JsonArray objJson(Player p, String section, String store, List<Obj> objs) {
        JsonArray a = new JsonArray();
        String b = base(p.getUniqueId()) + store + ".";
        for (Obj o : objs) {
            JsonObject j = new JsonObject();
            j.addProperty("p", section);
            j.addProperty("label", o.label);
            j.addProperty("type", o.type);
            j.addProperty("n", data.getLong(b + o.id + ".n", 0));
            j.addProperty("t", o.target);
            j.addProperty("xp", o.xp);
            j.addProperty("done", data.getBoolean(b + o.id + ".done", false));
            a.add(j);
        }
        return a;
    }

    private void sendState(Player p) {
        UUID u = p.getUniqueId();
        rollover(u);
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        o.addProperty("running", seasonRunning());
        o.addProperty("season", seasonName());
        o.addProperty("seasonEnd", cfg.getString("season.end", ""));
        o.addProperty("premium", premium(u));
        o.addProperty("premiumCost", premiumCost());
        o.addProperty("premiumBuyable", cfg.getBoolean("season.premium-buyable", true));
        o.addProperty("admin", p.hasPermission("flowcity.battlepass.admin"));
        long xp = data.getLong(base(u) + "xp", 0);
        o.addProperty("xp", xp);
        o.addProperty("per", xpPerLevel());
        o.addProperty("max", maxLevel());
        o.addProperty("level", levelOf(xp));
        JsonArray obj = objJson(p, "daily", "day", daily());
        obj.addAll(objJson(p, "weekly", "week", weekly()));
        o.add("objectives", obj);
        List<Integer> freeC = data.getIntegerList(base(u) + "claimed_free");
        List<Integer> premC = data.getIntegerList(base(u) + "claimed_premium");
        // formatul vechi: o singura lista "claimed" = traseul FREE
        if (freeC.isEmpty()) freeC = data.getIntegerList(base(u) + "claimed");
        JsonArray rw = new JsonArray();
        for (int l = 1; l <= maxLevel(); l++) {
            JsonObject r = new JsonObject();
            r.addProperty("l", l);
            r.addProperty("free", rewardLabel(l, "free"));
            r.addProperty("premium", rewardLabel(l, "premium"));
            r.addProperty("freeClaimed", freeC.contains(l));
            r.addProperty("premiumClaimed", premC.contains(l));
            rw.add(r);
        }
        o.add("rewards", rw);
        send(p, "state", o);
    }
}
