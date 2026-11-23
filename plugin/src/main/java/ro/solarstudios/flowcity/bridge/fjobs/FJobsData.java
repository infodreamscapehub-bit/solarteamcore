package ro.solarstudios.flowcity.bridge.fjobs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Datele jucatorilor FJobs (profil, XP pe job, istoric) si salvarea lor. Totul tinut in memorie; salvare atomica
 * (fisier temporar + mutare), serializarea se face pe thread-ul principal, scrierea pe disc pe un thread separat.
 */
public final class FJobsData {
    /** O intrare din istoricul de activitate. */
    public static final class Entry {
        public long at;
        public String type = "";   // shift | hire | quit | level | reward
        public String job = "";
        public String text = "";
        public double money;
    }

    /** Profilul profesional al unui jucator. */
    public static final class Profile {
        public final UUID uuid;
        public String name = "";
        public String job = "";
        public long changedAt;
        public long shiftStart;          // 0 = nu e in tura
        public double shiftEarned;
        public long shiftXp;
        public int shiftMissions;
        public double totalEarned;
        public long totalSeconds;
        public int totalShifts;
        public int totalMissions;
        public final Map<String, Long> xp = new HashMap<>();
        public final Map<String, Double> earned = new HashMap<>();
        public final Map<String, Integer> shifts = new HashMap<>();
        public final Map<String, Integer> missions = new HashMap<>();
        public final Set<String> rewarded = new HashSet<>();   // "job:nivel" deja incasate
        public final Map<String, String> opts = new HashMap<>();      // optiuni per job ("job.cheie" -> valoare), ex. dificultatea
        public final Map<String, Long> counters = new HashMap<>();    // contoare persistente ale activitatilor (capturi, productie, alocatii de predare)
        public final List<Entry> history = new ArrayList<>();
        public boolean dirty;
        public boolean loadedOnline;
        /** Pauza turei (doar in memorie: tura nu supravietuieste unei reporniri). 0 = nu e in pauza. */
        public long pausedAt;
        /** Timpul total (ms) petrecut in pauza in tura curenta; nu se plateste si nu se numara la timpul lucrat. */
        public long pausedMs;

        /** Timpul lucrat efectiv (ms) in tura curenta, fara pauze. */
        public long activeMs(long now) {
            if (shiftStart <= 0) return 0;
            long paused = pausedMs + (pausedAt > 0 ? now - pausedAt : 0);
            return Math.max(0, now - shiftStart - paused);
        }

        Profile(UUID uuid) {
            this.uuid = uuid;
        }

        public long totalXp() {
            long t = 0;
            for (Long v : xp.values()) t += v == null ? 0 : v;
            return t;
        }

        public boolean inShift() {
            return shiftStart > 0 && !job.isEmpty();
        }
    }

    private final File file;
    private final Logger log;
    private final Map<UUID, Profile> profiles = new HashMap<>();
    private final Object ioLock = new Object();
    private final int historyMax;

    public FJobsData(File file, Logger log, int historyMax) {
        this.file = file;
        this.log = log;
        this.historyMax = Math.max(10, historyMax);
    }

    public void load() {
        profiles.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            UUID u;
            try {
                u = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = ps.getConfigurationSection(key);
            if (s == null) continue;
            Profile p = new Profile(u);
            p.name = s.getString("name", "");
            p.job = s.getString("job", "");
            p.changedAt = s.getLong("changed-at", 0);
            p.shiftStart = s.getLong("shift-start", 0);
            p.shiftEarned = s.getDouble("shift-earned", 0);
            p.shiftXp = s.getLong("shift-xp", 0);
            p.shiftMissions = s.getInt("shift-missions", 0);
            p.totalEarned = s.getDouble("total-earned", 0);
            p.totalSeconds = s.getLong("total-seconds", 0);
            p.totalShifts = s.getInt("total-shifts", 0);
            p.totalMissions = s.getInt("total-missions", 0);
            readLong(s.getConfigurationSection("xp"), p.xp);
            readDouble(s.getConfigurationSection("earned"), p.earned);
            readInt(s.getConfigurationSection("shifts"), p.shifts);
            readInt(s.getConfigurationSection("missions"), p.missions);
            p.rewarded.addAll(s.getStringList("rewarded"));
            ConfigurationSection os = s.getConfigurationSection("opts");
            if (os != null) for (String k : os.getKeys(false)) p.opts.put(k.replace('~', '.'), os.getString(k, ""));
            ConfigurationSection cs = s.getConfigurationSection("counters");
            if (cs != null) for (String k : cs.getKeys(false)) p.counters.put(k.replace('~', '.'), cs.getLong(k));
            for (Map<?, ?> m : s.getMapList("history")) {
                Entry e = new Entry();
                e.at = m.get("at") instanceof Number ? ((Number) m.get("at")).longValue() : 0;
                e.type = String.valueOf(m.get("type") == null ? "" : m.get("type"));
                e.job = String.valueOf(m.get("job") == null ? "" : m.get("job"));
                e.text = String.valueOf(m.get("text") == null ? "" : m.get("text"));
                e.money = m.get("money") instanceof Number ? ((Number) m.get("money")).doubleValue() : 0;
                p.history.add(e);
            }
            profiles.put(u, p);
        }
    }

    private static void readLong(ConfigurationSection s, Map<String, Long> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k, s.getLong(k));
    }

    private static void readDouble(ConfigurationSection s, Map<String, Double> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k, s.getDouble(k));
    }

    private static void readInt(ConfigurationSection s, Map<String, Integer> out) {
        if (s == null) return;
        for (String k : s.getKeys(false)) out.put(k, s.getInt(k));
    }

    public Profile get(UUID u, String name) {
        Profile p = profiles.get(u);
        if (p == null) {
            p = new Profile(u);
            p.dirty = true;
            profiles.put(u, p);
        }
        if (name != null && !name.isEmpty() && !name.equals(p.name)) {
            p.name = name;
            p.dirty = true;
        }
        return p;
    }

    public Profile peek(UUID u) {
        return profiles.get(u);
    }

    public Iterable<Profile> all() {
        return profiles.values();
    }

    /** Cati angajati are jobul (include jucatorii offline). */
    public int employees(String jobId) {
        int n = 0;
        for (Profile p : profiles.values()) if (jobId.equals(p.job)) n++;
        return n;
    }

    public void addHistory(Profile p, String type, String job, String text, double money) {
        Entry e = new Entry();
        e.at = System.currentTimeMillis();
        e.type = type;
        e.job = job;
        e.text = text;
        e.money = money;
        p.history.add(e);
        while (p.history.size() > historyMax) p.history.remove(0);
        p.dirty = true;
    }

    /** Serializeaza pe thread-ul curent (principal) si scrie pe disc asincron. force = scriere sincrona (la oprire). */
    public void save(boolean force, org.bukkit.plugin.Plugin plugin) {
        boolean any = false;
        for (Profile p : profiles.values()) if (p.dirty) any = true;
        if (!any && !force) return;
        final String text = serialize();
        for (Profile p : profiles.values()) p.dirty = false;
        if (force || plugin == null || !plugin.isEnabled()) {
            write(text);
        } else {
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text));
        }
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        for (Profile p : profiles.values()) {
            String b = "players." + p.uuid + ".";
            y.set(b + "name", p.name);
            y.set(b + "job", p.job);
            y.set(b + "changed-at", p.changedAt);
            y.set(b + "shift-start", p.shiftStart);
            y.set(b + "shift-earned", p.shiftEarned);
            y.set(b + "shift-xp", p.shiftXp);
            y.set(b + "shift-missions", p.shiftMissions);
            y.set(b + "total-earned", p.totalEarned);
            y.set(b + "total-seconds", p.totalSeconds);
            y.set(b + "total-shifts", p.totalShifts);
            y.set(b + "total-missions", p.totalMissions);
            for (Map.Entry<String, Long> e : p.xp.entrySet()) y.set(b + "xp." + e.getKey(), e.getValue());
            for (Map.Entry<String, Double> e : p.earned.entrySet()) y.set(b + "earned." + e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : p.shifts.entrySet()) y.set(b + "shifts." + e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : p.missions.entrySet()) y.set(b + "missions." + e.getKey(), e.getValue());
            y.set(b + "rewarded", new ArrayList<>(p.rewarded));
            for (Map.Entry<String, String> e : p.opts.entrySet()) y.set(b + "opts." + e.getKey().replace('.', '~'), e.getValue());
            for (Map.Entry<String, Long> e : p.counters.entrySet()) if (e.getValue() != 0) y.set(b + "counters." + e.getKey().replace('.', '~'), e.getValue());
            List<Map<String, Object>> hs = new ArrayList<>();
            for (Entry e : p.history) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", e.at);
                m.put("type", e.type);
                m.put("job", e.job);
                m.put("text", e.text);
                m.put("money", e.money);
                hs.add(m);
            }
            y.set(b + "history", hs);
        }
        return y.saveToString();
    }

    private void write(String text) {
        synchronized (ioLock) {
            try {
                File dir = file.getParentFile();
                if (dir != null) dir.mkdirs();
                File tmp = new File(file.getPath() + ".tmp");
                Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
                if (file.exists()) Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.warning("FJobs: nu pot salva " + file.getName() + ": " + e);
            }
        }
    }
}
