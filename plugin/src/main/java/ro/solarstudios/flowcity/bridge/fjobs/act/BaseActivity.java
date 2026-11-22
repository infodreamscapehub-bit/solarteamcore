package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/** Schelet comun: stare per jucator, dificultati din config, puncte, plata, HUD si panou. Fiecare job isi pune propria logica. */
public abstract class BaseActivity<S> implements JobActivity {
    /** O treapta de dificultate (din fjobs.yml: jobs.X.difficulty.Y). */
    public static final class Diff {
        public String id;
        public String name;
        public int minLevel;
        public double pay = 1;
        public double xp = 1;
        public int size = 3;
        public int seconds = 60;
        public int attempts = 3;
    }

    /** Panoul unui job, construit de activitate si serializat pentru client. */
    public static final class Panel {
        final List<String> objectives = new ArrayList<>();
        final List<String[]> stats = new ArrayList<>();
        final List<String[]> buttons = new ArrayList<>();
        final List<String> rewards = new ArrayList<>();
        final List<String> tips = new ArrayList<>();
        String status = "";

        public Panel obj(String s) {
            objectives.add(s);
            return this;
        }

        public Panel stat(String l, String v) {
            stats.add(new String[] {l, v});
            return this;
        }

        /** Buton: act = actiunea trimisa inapoi; on=false il arata dezactivat. */
        public Panel btn(String act, String label, boolean on) {
            buttons.add(new String[] {act, label, on ? "1" : "0"});
            return this;
        }

        public Panel reward(String s) {
            rewards.add(s);
            return this;
        }

        public Panel tip(String s) {
            tips.add(s);
            return this;
        }

        public Panel status(String s) {
            status = s;
            return this;
        }
    }

    protected final FJobsApi api;
    protected final Map<UUID, S> states = new HashMap<>();
    private final Map<UUID, String> lastHud = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();

    protected BaseActivity(FJobsApi api) {
        this.api = api;
    }

    protected abstract S newState();

    protected S state(Player p) {
        S s = states.get(p.getUniqueId());
        if (s == null) {
            s = newState();
            states.put(p.getUniqueId(), s);
        }
        return s;
    }

    // ------------------------------------------------------------------ ciclu de viata

    @Override
    public void start(Player p, FJobDef jd) {
        states.put(p.getUniqueId(), newState());
        lastHud.remove(p.getUniqueId());
        onStart(p, jd, state(p));
    }

    @Override
    public void end(Player p, FJobDef jd) {
        S s = states.remove(p.getUniqueId());
        if (s != null) onEnd(p, jd, s);
        api.challenges().drop(p);
        lastHud.remove(p.getUniqueId());
        api.hud(p, new JsonObject());
    }

    @Override
    public void quit(Player p) {
        S s = states.remove(p.getUniqueId());
        if (s != null) onQuit(p, s);
        api.challenges().drop(p);
        lastHud.remove(p.getUniqueId());
        String pre = p.getUniqueId() + "|";
        cooldowns.keySet().removeIf(k -> k.startsWith(pre));
    }

    @Override
    public void shutdown() {
        for (UUID u : new ArrayList<>(states.keySet())) {
            S s = states.remove(u);
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(u);
            if (s != null) onQuit(p, s);
        }
    }

    protected void onStart(Player p, FJobDef jd, S s) {}

    protected void onEnd(Player p, FJobDef jd, S s) {}

    /** p poate fi null la oprirea pluginului. */
    protected void onQuit(Player p, S s) {}

    @Override
    public void tick(Player p, FJobDef jd, long now) {
        S s = state(p);
        tickJob(p, jd, s, now);
        List<String> lines = new ArrayList<>();
        double prog = hud(p, jd, s, lines);
        JsonObject h = new JsonObject();
        h.addProperty("title", jd.name);
        h.addProperty("sub", diff(p, jd).name);
        JsonArray la = new JsonArray();
        for (String l : lines) la.add(l);
        h.add("lines", la);
        h.addProperty("p", prog);
        String key = h.toString();
        if (!key.equals(lastHud.get(p.getUniqueId()))) {
            lastHud.put(p.getUniqueId(), key);
            api.hud(p, h);
        }
    }

    protected abstract void tickJob(Player p, FJobDef jd, S s, long now);

    /** Liniile cardului de HUD; intoarce progresul 0..1 sau -1 daca nu are bara. */
    protected abstract double hud(Player p, FJobDef jd, S s, List<String> lines);

    @Override
    public void onBreak(Player p, FJobDef jd, BlockBreakEvent e) {}

    @Override
    public void onPlace(Player p, FJobDef jd, BlockPlaceEvent e) {}

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {}

    @Override
    public void onFish(Player p, FJobDef jd, PlayerFishEvent e) {}

    @Override
    public void onPickup(Player p, FJobDef jd, EntityPickupItemEvent e) {}

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        return false;
    }

    // ------------------------------------------------------------------ dificultate

    protected List<Diff> diffs(FJobDef jd) {
        List<Diff> out = new ArrayList<>();
        ConfigurationSection ds = jd.conf == null ? null : jd.conf.getConfigurationSection("levels");
        if (ds != null) {
            for (String id : ds.getKeys(false)) {
                ConfigurationSection c = ds.getConfigurationSection(id);
                if (c == null) continue;
                Diff d = new Diff();
                d.id = id.toLowerCase(Locale.ROOT);
                d.name = c.getString("name", id);
                d.minLevel = Math.max(0, c.getInt("min-level", 0));
                d.pay = Math.max(0, Math.min(10, c.getDouble("pay", 1)));
                d.xp = Math.max(0, Math.min(10, c.getDouble("xp", 1)));
                d.size = Math.max(1, Math.min(50, c.getInt("size", 3)));
                d.seconds = Math.max(5, Math.min(1800, c.getInt("seconds", 60)));
                d.attempts = Math.max(1, Math.min(9, c.getInt("attempts", 3)));
                out.add(d);
            }
        }
        if (out.isEmpty()) {
            String[][] def = {{"usor", "Usor", "0", "1.0", "1.0", "3", "70", "3"}, {"mediu", "Mediu", "3", "1.5", "1.4", "4", "55", "3"},
                {"greu", "Greu", "7", "2.2", "2.0", "6", "40", "2"}};
            for (String[] r : def) {
                Diff d = new Diff();
                d.id = r[0];
                d.name = r[1];
                d.minLevel = Integer.parseInt(r[2]);
                d.pay = Double.parseDouble(r[3]);
                d.xp = Double.parseDouble(r[4]);
                d.size = Integer.parseInt(r[5]);
                d.seconds = Integer.parseInt(r[6]);
                d.attempts = Integer.parseInt(r[7]);
                out.add(d);
            }
        }
        return out;
    }

    /** Dificultatea aleasa de jucator, coborata la cea mai mare permisa de nivelul lui daca a scazut nivelul/config-ul. */
    protected Diff diff(Player p, FJobDef jd) {
        List<Diff> ds = diffs(jd);
        String want = api.opt(p, jd, "difficulty", ds.get(0).id);
        int lvl = api.jobLevel(p, jd.id);
        Diff pick = ds.get(0);
        for (Diff d : ds) {
            if (d.id.equals(want) && lvl >= d.minLevel) pick = d;
        }
        return pick;
    }

    protected boolean busy(Player p, S s) {
        return false;
    }

    @Override
    public boolean setOption(Player p, FJobDef jd, String key, String value) {
        if (!"difficulty".equals(key)) return false;
        S s = state(p);
        if (busy(p, s)) {
            api.msg(p, false, "Termina sarcina curenta inainte sa schimbi dificultatea.");
            return false;
        }
        int lvl = api.jobLevel(p, jd.id);
        for (Diff d : diffs(jd)) {
            if (d.id.equalsIgnoreCase(value)) {
                if (lvl < d.minLevel) {
                    api.msg(p, false, "Dificultatea " + d.name + " se deblocheaza la nivelul " + d.minLevel + ".");
                    return false;
                }
                api.setOpt(p, jd, "difficulty", d.id);
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ panou

    /** Completeaza panoul specific jobului. */
    protected abstract void fillPanel(Player p, FJobDef jd, S s, Panel pn);

    @Override
    public JsonObject panel(Player p, FJobDef jd) {
        S s = state(p);
        Panel pn = new Panel();
        fillPanel(p, jd, s, pn);
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind());
        o.addProperty("job", jd.name);
        o.addProperty("id", jd.id);
        o.addProperty("level", api.jobLevel(p, jd.id));
        o.addProperty("status", pn.status);
        o.add("objectives", strs(pn.objectives));
        JsonArray st = new JsonArray();
        for (String[] kv : pn.stats) {
            JsonArray r = new JsonArray();
            r.add(kv[0]);
            r.add(kv[1]);
            st.add(r);
        }
        o.add("stats", st);
        JsonArray bt = new JsonArray();
        for (String[] b : pn.buttons) {
            JsonObject bo = new JsonObject();
            bo.addProperty("act", b[0]);
            bo.addProperty("label", b[1]);
            bo.addProperty("on", "1".equals(b[2]));
            bt.add(bo);
        }
        o.add("buttons", bt);
        o.add("rewards", strs(pn.rewards));
        o.add("tips", strs(pn.tips));
        JsonArray da = new JsonArray();
        Diff cur = diff(p, jd);
        int lvl = api.jobLevel(p, jd.id);
        for (Diff d : diffs(jd)) {
            JsonObject dj = new JsonObject();
            dj.addProperty("id", d.id);
            dj.addProperty("name", d.name);
            dj.addProperty("minLevel", d.minLevel);
            dj.addProperty("locked", lvl < d.minLevel);
            dj.addProperty("sel", d.id.equals(cur.id));
            dj.addProperty("pay", d.pay);
            dj.addProperty("xp", d.xp);
            da.add(dj);
        }
        o.add("diffs", da);
        return o;
    }

    private static JsonArray strs(List<String> l) {
        JsonArray a = new JsonArray();
        for (String s : l) a.add(s);
        return a;
    }

    // ------------------------------------------------------------------ puncte

    protected List<FJobDef.Point> pts(FJobDef jd, String kind) {
        return api.stations(jd, kind);
    }

    protected static boolean near(Player p, FJobDef.Point pt, double r) {
        Location l = p.getLocation();
        if (l.getWorld() == null || !l.getWorld().getName().equals(pt.world)) return false;
        double dx = l.getX() - pt.x;
        double dz = l.getZ() - pt.z;
        return dx * dx + dz * dz <= r * r && Math.abs(l.getY() - pt.y) <= 8;
    }

    protected static boolean nearLoc(Location l, FJobDef.Point pt, double r) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(pt.world)) return false;
        double dx = l.getX() - pt.x;
        double dz = l.getZ() - pt.z;
        double dy = l.getY() - pt.y;
        return dx * dx + dz * dz <= r * r && Math.abs(dy) <= 8;
    }

    protected FJobDef.Point nearest(Player p, List<FJobDef.Point> l, double r) {
        FJobDef.Point best = null;
        double bd = Double.MAX_VALUE;
        for (FJobDef.Point pt : l) {
            if (!near(p, pt, r)) continue;
            double dx = p.getLocation().getX() - pt.x;
            double dz = p.getLocation().getZ() - pt.z;
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = pt;
            }
        }
        return best;
    }

    /** Grupare dupa prefixul numelui, "Linia 1 - Piata" -> "Linia 1"; punctele fara " - " raman intr-un grup cu numele intreg. */
    protected static String group(FJobDef.Point pt) {
        int i = pt.name.indexOf(" - ");
        return i > 0 ? pt.name.substring(0, i).trim() : pt.name.trim();
    }

    protected static double dist(Player p, FJobDef.Point pt) {
        Location l = p.getLocation();
        if (l.getWorld() == null || !l.getWorld().getName().equals(pt.world)) return Double.MAX_VALUE;
        double dx = l.getX() - pt.x;
        double dz = l.getZ() - pt.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Particule de marcare (doar pentru jucator) pe punctele apropiate. */
    protected void mark(Player p, List<FJobDef.Point> l, String particle, double maxDist) {
        for (FJobDef.Point pt : l) {
            double d = dist(p, pt);
            if (d > maxDist) continue;
            Location at = new Location(p.getWorld(), pt.x, pt.y + 1.2, pt.z);
            Fx.particle(p, particle, at, 3, 0.25);
            Fx.particle(p, particle, at.clone().add(0, 0.7, 0), 2, 0.15);
        }
    }

    // ------------------------------------------------------------------ plata

    /** Plateste cu dificultate si calitate. quality in [0,1]. */
    protected double payQ(Player p, FJobDef jd, double base, long xp, Diff d, double quality, boolean mission) {
        double q = Math.max(0, Math.min(1.2, quality));
        return api.pay(p, jd, base * d.pay * q, Math.round(xp * d.xp * (0.5 + 0.5 * Math.min(1.0, q))), mission);
    }

    protected boolean cooling(Player p, String key, long ms) {
        long now = System.currentTimeMillis();
        String k = p.getUniqueId() + "|" + key;
        Long last = cooldowns.get(k);
        if (last != null && now - last < ms) return true;
        cooldowns.put(k, now);
        return false;
    }

    protected static String money(double v) {
        return (Math.round(v * 100) / 100.0) + "$";
    }

    protected static String time(long sec) {
        sec = Math.max(0, sec);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    protected static List<String> list(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }
}
