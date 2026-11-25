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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Battlepass legat de FJobs: FJobsModule.payOut() anunta fiecare actiune platita (misiune / actiune / bani / XP); obiectivele zilnice si
 * saptamanale (alese determinist pe zi/saptamana din battlepass.yml) se completeaza pe server, dau XP de battlepass, iar nivelurile
 * se revendica din meniu (bani + optional comanda de consola). Progresul se salveaza in battlepass-data.yml.
 */
public final class BattlepassModule extends JsonModule {
    private static BattlepassModule instance;

    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;
    private boolean dirty;

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
        dataFile = new File(plugin.getDataFolder(), "battlepass-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        on("open", (p, j) -> sendState(p));
        on("claim", (p, j) -> claim(p, integer(j, "level", 0)));
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 20L * 60, 20L * 60);
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

    // ------------------------------------------------------------------ config

    private boolean enabled() {
        return cfg.getBoolean("enabled", true);
    }

    private int xpPerLevel() {
        return Math.max(10, Math.min(100000, cfg.getInt("xp-per-level", 100)));
    }

    private int maxLevel() {
        return Math.max(1, Math.min(200, cfg.getInt("max-level", 30)));
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(cfg.getString("timezone", "Europe/Bucharest"));
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Bucharest");
        }
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
                if (!o.type.matches("missions|actions|earn|xp")) continue;
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

    // ------------------------------------------------------------------ date jucator

    private String base(UUID u) {
        return "players." + u + ".";
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

    /** Apelat din FJobsModule.payOut la fiecare actiune platita in tura. */
    public static void progress(Player p, String jobId, boolean mission, double pay, long xp) {
        if (instance != null && instance.enabled()) instance.apply(p, jobId, mission, pay, xp);
    }

    private void apply(Player p, String jobId, boolean mission, double pay, long xp) {
        UUID u = p.getUniqueId();
        rollover(u);
        boolean changed = false;
        changed |= applyTo(p, "daily", "day", pick("daily", dayKey(), cfg.getInt("daily-count", 3)), jobId, mission, pay, xp);
        changed |= applyTo(p, "weekly", "week", pick("weekly", weekKey(), cfg.getInt("weekly-count", 3)), jobId, mission, pay, xp);
        if (changed) {
            dirty = true;
            sendState(p);
        }
    }

    private boolean applyTo(Player p, String section, String store, List<Obj> objs, String jobId, boolean mission, double pay, long xp) {
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
                    add = 1;
                    break;
                case "earn":
                    add = Math.round(pay);
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
        if (la > lb) plugin.notifications().send(p, NotifyModule.Type.OK, "Battlepass nivel " + la, "Revendica recompensa din meniul Battlepass.");
    }

    // ------------------------------------------------------------------ recompense

    private double rewardMoney(int level) {
        ConfigurationSection r = cfg.getConfigurationSection("rewards." + level);
        if (r != null) return Math.max(0, r.getDouble("money", 0));
        return Math.max(0, cfg.getDouble("reward-base", 250)) * level;
    }

    private String rewardLabel(int level) {
        ConfigurationSection r = cfg.getConfigurationSection("rewards." + level);
        if (r != null && !r.getString("label", "").isEmpty()) return r.getString("label");
        return (long) rewardMoney(level) + "$";
    }

    private void claim(Player p, int level) {
        UUID u = p.getUniqueId();
        long xp = data.getLong(base(u) + "xp", 0);
        List<Integer> claimed = data.getIntegerList(base(u) + "claimed");
        if (level < 1 || level > maxLevel() || levelOf(xp) < level) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Nu ai ajuns la nivelul " + level + ".");
            return;
        }
        if (claimed.contains(level)) {
            plugin.notifications().send(p, NotifyModule.Type.WARN, "Battlepass", "Recompensa nivelului " + level + " e deja luata.");
            return;
        }
        claimed.add(level);
        data.set(base(u) + "claimed", claimed);
        dirty = true;
        double money = rewardMoney(level);
        if (money > 0) plugin.money().deposit(u, money);
        String cmd = cfg.getString("rewards." + level + ".command", "");
        if (cmd != null && !cmd.trim().isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        plugin.notifications().send(p, NotifyModule.Type.MONEY, "Battlepass nivel " + level, "Recompensa: " + rewardLabel(level));
        sendState(p);
    }

    // ------------------------------------------------------------------ stare

    private JsonArray objJson(Player p, String section, String store, List<Obj> objs) {
        JsonArray a = new JsonArray();
        String b = base(p.getUniqueId()) + store + ".";
        for (Obj o : objs) {
            JsonObject j = new JsonObject();
            j.addProperty("p", section);
            j.addProperty("label", o.label);
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
        long xp = data.getLong(base(u) + "xp", 0);
        o.addProperty("xp", xp);
        o.addProperty("per", xpPerLevel());
        o.addProperty("max", maxLevel());
        o.addProperty("level", levelOf(xp));
        JsonArray obj = objJson(p, "daily", "day", pick("daily", dayKey(), cfg.getInt("daily-count", 3)));
        obj.addAll(objJson(p, "weekly", "week", pick("weekly", weekKey(), cfg.getInt("weekly-count", 3))));
        o.add("objectives", obj);
        List<Integer> claimed = data.getIntegerList(base(u) + "claimed");
        JsonArray rw = new JsonArray();
        for (int l = 1; l <= maxLevel(); l++) {
            JsonObject r = new JsonObject();
            r.addProperty("l", l);
            r.addProperty("label", rewardLabel(l));
            r.addProperty("claimed", claimed.contains(l));
            rw.add(r);
        }
        o.add("rewards", rw);
        send(p, "state", o);
    }
}
