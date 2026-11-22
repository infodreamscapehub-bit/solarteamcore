package ro.solarstudios.flowcity.bridge.fjobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.scheduler.BukkitTask;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.hype.HypeMath;
import ro.solarstudios.flowcity.bridge.hype.JobRules;
import ro.solarstudios.flowcity.bridge.hype.PlacedSet;
import ro.solarstudios.flowcity.bridge.hype.RateWindow;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * FlowCity Jobs (FJobs): Job Center, joburi cu tura, XP si niveluri, plati si progres. TOT e validat pe server:
 * clientul trimite doar intentii (hire, quit, start, end, req) si, pentru admini, modificari de configurare.
 *
 * Canal "fjobs". Client -> server: req, close, hire, quit, start, end, adm_set, adm_point, adm_reload.
 * Server -> client: open, data, prof, msg, earn.
 *
 * Activitati (campul "activity" din fjobs.yml):
 *  - rules: evenimente Bukkit (break / place / fish / trash) cu plata + XP per material (miner, padurar, pescar, fermier, gunoier, constructor);
 *  - delivery: ridici de la un punct si livrezi la altul (curier, livrator, postas, camionagiu, muncitor portuar, manipulant marfa);
 *  - route: treci prin checkpoint-uri in ordine (sofer autobuz, electrician, spalator geamuri, pompier, salvamar, salubritate);
 *  - external: lucrul se face in alt sistem (taxi, mecanic, politie, EMS); FJobs tine doar evidenta angajarii;
 *  - none: fara logica inca -> status "in pregatire", jobul nu se poate lua.
 * Joburile cu delivery/route sunt "READY" doar cand adminul a setat punctele (din GUI, stand in joc).
 */
public final class FJobsModule extends JsonModule implements Listener {
    private static final class Trip {
        final String job;
        final FJobDef.Point from;
        final FJobDef.Point to;
        final long startedAt = System.currentTimeMillis();

        Trip(String job, FJobDef.Point from, FJobDef.Point to) {
            this.job = job;
            this.from = from;
            this.to = to;
        }
    }

    private static final class RouteRun {
        final String job;
        int next;
        long lastAt;
        int shownFor = -1;

        RouteRun(String job) {
            this.job = job;
            this.lastAt = System.currentTimeMillis();
        }
    }

    private static volatile FJobsModule instance;

    public static final String PERM_USE = "flowcity.fjobs.use";
    public static final String PERM_ADMIN = "flowcity.fjobs.admin";
    public static final String PERM_PREMIUM = "flowcity.fjobs.premium";

    private YamlConfiguration cfg;
    private YamlConfiguration over;
    private File overFile;
    private FJobsData store;
    private PlacedSet placed;
    private final Map<String, FJobDef> jobs = new LinkedHashMap<>();
    private final Map<UUID, Trip> trips = new HashMap<>();
    private final Map<UUID, RouteRun> routes = new HashMap<>();
    private final Map<UUID, Map<String, RateWindow>> rates = new HashMap<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final Map<UUID, Long> warnedLevel = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final java.util.Random rnd = new java.util.Random();

    @Override
    public String id() {
        return "fjobs";
    }

    // ------------------------------------------------------------------ pornire

    @Override
    protected void onEnable() {
        instance = this;
        Commands.permission(PERM_USE, "Foloseste FlowCity Jobs", PermissionDefault.TRUE);
        Commands.permission(PERM_ADMIN, "Administreaza FlowCity Jobs din interfata", PermissionDefault.OP);
        Commands.permission(PERM_PREMIUM, "Acces la joburile premium", PermissionDefault.FALSE);

        cfg = ModConfig.load(plugin, "fjobs");
        overFile = new File(plugin.getDataFolder(), "fjobs-admin.yml");
        loadAll();
        store = new FJobsData(new File(plugin.getDataFolder(), "fjobs-data.yml"), plugin.getLogger(), cfg.getInt("settings.history-max", 40));
        store.load();

        on("req", (p, j) -> sendData(p, true));
        on("close", (p, j) -> viewers.remove(p.getUniqueId()));
        on("hire", (p, j) -> hire(p, str(j, "id", "")));
        on("quit", (p, j) -> quit(p));
        on("start", (p, j) -> startShift(p));
        on("end", (p, j) -> endShift(p, "Tura incheiata."));
        on("adm_set", this::admSet);
        on("adm_point", this::admPoint);
        on("adm_reload", (p, j) -> {
            if (!isAdmin(p)) return;
            loadAll();
            msg(p, true, "Joburile au fost reincarcate din fjobs.yml.");
            pushToViewers();
        });

        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> store.save(false, plugin), 3600L, 3600L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> placed.purge(System.currentTimeMillis()), 1200L, 1200L));
        plugin.getLogger().info("FJobs: " + jobs.size() + " joburi (" + countReady() + " functionale, restul extern sau in pregatire).");
    }

    @Override
    public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (store != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                FJobsData.Profile pr = store.peek(p.getUniqueId());
                if (pr != null && pr.inShift()) closeShift(p, pr, false, "Tura incheiata (oprire server).");
            }
            store.save(true, null);
        }
        jobs.clear();
        trips.clear();
        routes.clear();
        viewers.clear();
        instance = null;
    }

    private int countReady() {
        int n = 0;
        for (FJobDef j : jobs.values()) if (j.status() == FJobDef.Status.READY) n++;
        return n;
    }

    private void loadAll() {
        cfg = ModConfig.load(plugin, "fjobs");
        over = overFile.exists() ? YamlConfiguration.loadConfiguration(overFile) : new YamlConfiguration();
        long ttl = (long) (cfg.getDouble("settings.placed-ttl-minutes", 180) * 60_000L);
        placed = new PlacedSet(ttl, cfg.getInt("settings.placed-max", 60000));
        jobs.clear();
        ConfigurationSection list = cfg.getConfigurationSection("jobs");
        if (list == null) return;
        for (String id : list.getKeys(false)) {
            ConfigurationSection s = list.getConfigurationSection(id);
            if (s == null) continue;
            FJobDef j = FJobDef.parse(id, s);
            applyOverrides(j);
            jobs.put(j.id, j);
        }
    }

    private void applyOverrides(FJobDef j) {
        ConfigurationSection o = over.getConfigurationSection("jobs." + j.id);
        if (o == null) return;
        if (o.contains("active")) j.active = o.getBoolean("active");
        if (o.contains("premium")) j.premium = o.getBoolean("premium");
        if (o.contains("est-pay")) j.estPay = clamp(o.getDouble("est-pay"), 0, 100000);
        if (o.contains("est-minutes")) j.estMinutes = (int) clamp(o.getInt("est-minutes"), 1, 600);
        if (o.contains("min-level")) j.minLevel = (int) clamp(o.getInt("min-level"), 0, 100);
        if (o.contains("req-xp")) j.reqXp = (long) clamp(o.getLong("req-xp"), 0, 10_000_000);
        if (o.contains("max-employees")) j.maxEmployees = (int) clamp(o.getInt("max-employees"), 0, 500);
        if (o.contains("pay-multiplier")) j.payMultiplier = clamp(o.getDouble("pay-multiplier"), 0, 10);
        if (o.contains("pickups")) {
            ensureDelivery(j);
            j.delivery.pickups.clear();
            FJobDef.readPoints(o.getMapList("pickups"), j.delivery.pickups);
        }
        if (o.contains("drops")) {
            ensureDelivery(j);
            j.delivery.drops.clear();
            FJobDef.readPoints(o.getMapList("drops"), j.delivery.drops);
        }
        if (o.contains("route")) {
            ensureRoute(j);
            j.route.points.clear();
            FJobDef.readPoints(o.getMapList("route"), j.route.points);
        }
    }

    private static void ensureDelivery(FJobDef j) {
        if (j.delivery == null) j.delivery = new FJobDef.Delivery();
    }

    private static void ensureRoute(FJobDef j) {
        if (j.route == null) j.route = new FJobDef.Route();
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private void saveOver() {
        try {
            over.save(overFile);
        } catch (Exception e) {
            plugin.getLogger().warning("FJobs: nu pot salva fjobs-admin.yml: " + e);
        }
    }

    // ------------------------------------------------------------------ setari / progres

    private double xpBase() {
        return cfg.getDouble("settings.xp-base", 100);
    }

    private double xpGrowth() {
        return cfg.getDouble("settings.xp-growth", 1.35);
    }

    private int maxLevel() {
        return cfg.getInt("settings.max-level", 50);
    }

    private double bonusPerLevel() {
        return cfg.getDouble("settings.bonus-per-level", 0.03);
    }

    private int jobLevel(FJobsData.Profile pr, String job) {
        Long xp = pr.xp.get(job);
        return HypeMath.levelFor(xp == null ? 0 : xp, xpBase(), xpGrowth(), maxLevel());
    }

    private int profileLevel(FJobsData.Profile pr) {
        return HypeMath.levelFor(pr.totalXp(), cfg.getDouble("settings.profile-xp-base", 150), cfg.getDouble("settings.profile-xp-growth", 1.3),
            cfg.getInt("settings.profile-max-level", 100));
    }

    private boolean isAdmin(Player p) {
        return p.hasPermission(PERM_ADMIN);
    }

    private boolean creative(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.CREATIVE || g == GameMode.SPECTATOR;
    }

    private boolean throttled(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < 400) return true;
        lastAction.put(p.getUniqueId(), now);
        return false;
    }

    private FJobsData.Profile prof(Player p) {
        FJobsData.Profile pr = store.get(p.getUniqueId(), p.getName());
        if (!pr.loadedOnline) {
            pr.loadedOnline = true;
            if (pr.shiftStart > 0) {   // tura ramasa dintr-o oprire neasteptata: nu se credita
                pr.shiftStart = 0;
                pr.shiftEarned = 0;
                pr.shiftXp = 0;
                pr.shiftMissions = 0;
                pr.dirty = true;
            }
        }
        return pr;
    }

    // ------------------------------------------------------------------ angajare / tura

    /** Motivul pentru care jucatorul nu poate lua jobul acum (sau "" daca poate). */
    private String whyNot(Player p, FJobsData.Profile pr, FJobDef j) {
        if (!j.active) return "Jobul este dezactivat.";
        if (j.premium) {
            if (!cfg.getBoolean("settings.premium-enabled", false)) return "Joburile premium nu sunt active pe server.";
            if (!p.hasPermission(PERM_PREMIUM)) return "Necesita acces premium.";
        }
        if (j.status() == FJobDef.Status.PREPARING) return j.statusNote();
        if (!j.requiresPermission.isEmpty() && !p.hasPermission(j.requiresPermission)) {
            return j.requiresNote.isEmpty() ? "Necesita o facțiune sau o permisiune." : j.requiresNote;
        }
        int pl = profileLevel(pr);
        if (pl < j.minLevel) return "Nivel profesional " + j.minLevel + " necesar (ai " + pl + ").";
        if (pr.totalXp() < j.reqXp) return "Experienta necesara: " + j.reqXp + " XP (ai " + pr.totalXp() + ").";
        if (j.maxEmployees > 0 && !j.id.equals(pr.job) && store.employees(j.id) >= j.maxEmployees) {
            return "Locuri ocupate (" + store.employees(j.id) + "/" + j.maxEmployees + ").";
        }
        return "";
    }

    private long cooldownLeft(FJobsData.Profile pr) {
        long cd = (long) (cfg.getDouble("settings.change-cooldown-minutes", 30) * 60_000L);
        if (pr.job.isEmpty()) return 0;
        return HypeMath.cooldownLeft(pr.changedAt, cd, System.currentTimeMillis());
    }

    private void hire(Player p, String id) {
        if (throttled(p)) return;
        FJobsData.Profile pr = prof(p);
        FJobDef j = jobs.get(id.toLowerCase(Locale.ROOT));
        if (j == null) {
            msg(p, false, "Job necunoscut.");
            return;
        }
        if (j.id.equals(pr.job)) {
            msg(p, false, "Ai deja acest job.");
            return;
        }
        String why = whyNot(p, pr, j);
        if (!why.isEmpty()) {
            msg(p, false, why);
            sendData(p, false);
            return;
        }
        if (pr.inShift()) {
            msg(p, false, "Termina tura inainte sa schimbi jobul.");
            return;
        }
        long left = cooldownLeft(pr);
        if (left > 0) {
            msg(p, false, "Poti schimba jobul peste " + HypeMath.formatLeft(left) + ".");
            return;
        }
        if (!pr.job.isEmpty()) {
            FJobDef old = jobs.get(pr.job);
            store.addHistory(pr, "quit", pr.job, "Ai plecat de la " + (old == null ? pr.job : old.name), 0);
        }
        pr.job = j.id;
        pr.changedAt = System.currentTimeMillis();
        pr.dirty = true;
        trips.remove(p.getUniqueId());
        routes.remove(p.getUniqueId());
        store.addHistory(pr, "hire", j.id, "Te-ai angajat ca " + j.name, 0);
        msg(p, true, "Acum lucrezi ca " + j.name + ".");
        plugin.notifications().send(p, NotifyModule.Type.JOB, "Angajat", j.name);
        sendData(p, false);
    }

    private void quit(Player p) {
        if (throttled(p)) return;
        FJobsData.Profile pr = prof(p);
        if (pr.job.isEmpty()) {
            msg(p, false, "Nu ai niciun job.");
            return;
        }
        if (pr.inShift()) {
            msg(p, false, "Termina tura inainte sa demisionezi.");
            return;
        }
        long left = cooldownLeft(pr);
        if (left > 0) {
            msg(p, false, "Poti demisiona peste " + HypeMath.formatLeft(left) + ".");
            return;
        }
        FJobDef old = jobs.get(pr.job);
        store.addHistory(pr, "quit", pr.job, "Ai demisionat de la " + (old == null ? pr.job : old.name), 0);
        pr.job = "";
        pr.changedAt = System.currentTimeMillis();
        pr.dirty = true;
        trips.remove(p.getUniqueId());
        routes.remove(p.getUniqueId());
        msg(p, true, "Ai demisionat.");
        sendData(p, false);
    }

    private void startShift(Player p) {
        if (throttled(p)) return;
        FJobsData.Profile pr = prof(p);
        FJobDef j = pr.job.isEmpty() ? null : jobs.get(pr.job);
        if (j == null) {
            msg(p, false, "Nu ai niciun job.");
            return;
        }
        if (pr.inShift()) {
            msg(p, false, "Esti deja in tura.");
            return;
        }
        if (j.status() == FJobDef.Status.EXTERNAL) {
            msg(p, false, j.statusNote());
            return;
        }
        if (j.status() != FJobDef.Status.READY || !j.active) {
            msg(p, false, j.active ? j.statusNote() : "Jobul este dezactivat.");
            return;
        }
        if (creative(p)) {
            msg(p, false, "Nu poti incepe tura in creative.");
            return;
        }
        pr.shiftStart = System.currentTimeMillis();
        pr.shiftEarned = 0;
        pr.shiftXp = 0;
        pr.shiftMissions = 0;
        pr.dirty = true;
        trips.remove(p.getUniqueId());
        routes.remove(p.getUniqueId());
        msg(p, true, "Tura a inceput. Spor!");
        plugin.notifications().send(p, NotifyModule.Type.JOB, "Tura inceputa", j.name);
        sendData(p, false);
    }

    private void endShift(Player p, String note) {
        FJobsData.Profile pr = prof(p);
        if (!pr.inShift()) {
            if (note != null && !note.isEmpty() && "Tura incheiata.".equals(note)) msg(p, false, "Nu esti in tura.");
            return;
        }
        closeShift(p, pr, true, note);
        sendData(p, false);
    }

    private void closeShift(Player p, FJobsData.Profile pr, boolean notify, String note) {
        long now = System.currentTimeMillis();
        long sec = Math.max(0, (now - pr.shiftStart) / 1000);
        FJobDef j = jobs.get(pr.job);
        double earned = pr.shiftEarned;
        // bonus de performanta: castig/ora peste tinta, dupa un minim de timp lucrat
        double bonus = 0;
        long minMin = cfg.getLong("settings.shift-bonus.min-minutes", 10);
        double target = cfg.getDouble("settings.shift-bonus.per-hour-target", 300);
        double pct = cfg.getDouble("settings.shift-bonus.percent", 10);
        if (sec >= minMin * 60 && sec > 0 && pct > 0 && earned > 0) {
            double perHour = earned / (sec / 3600.0);
            if (perHour >= target) bonus = HypeMath.round2(earned * pct / 100.0);
        }
        if (bonus > 0) {
            plugin.money().deposit(p.getUniqueId(), bonus);
            pr.totalEarned = HypeMath.round2(pr.totalEarned + bonus);
            Double e = pr.earned.get(pr.job);
            pr.earned.put(pr.job, HypeMath.round2((e == null ? 0 : e) + bonus));
        }
        pr.totalSeconds += sec;
        pr.totalShifts++;
        pr.shifts.merge(pr.job, 1, Integer::sum);
        String name = j == null ? pr.job : j.name;
        String text = name + ": " + HypeMath.formatLeft(sec * 1000L) + ", " + pr.shiftMissions + " misiuni, +" + (long) pr.shiftEarned + "$"
            + (bonus > 0 ? " (+" + (long) bonus + "$ bonus)" : "");
        store.addHistory(pr, "shift", pr.job, text, HypeMath.round2(earned + bonus));
        pr.shiftStart = 0;
        pr.shiftEarned = 0;
        pr.shiftXp = 0;
        pr.shiftMissions = 0;
        pr.dirty = true;
        trips.remove(p.getUniqueId());
        routes.remove(p.getUniqueId());
        if (notify) {
            plugin.notifications().send(p, NotifyModule.Type.JOB, "Tura incheiata", text);
            if (note != null && !note.isEmpty()) msg(p, true, note);
        }
    }

    // ------------------------------------------------------------------ plata / XP

    /** Plateste o actiune (cu bonus de nivel) si acorda XP. Doar in tura. Intoarce banii primiti. */
    private double payOut(Player p, FJobsData.Profile pr, FJobDef jd, double rawPay, long rawXp, boolean mission) {
        long before = pr.xp.getOrDefault(jd.id, 0L);
        int lvlBefore = HypeMath.levelFor(before, xpBase(), xpGrowth(), maxLevel());
        int plvlBefore = profileLevel(pr);
        double pay = HypeMath.round2(HypeMath.payWithBonus(rawPay, lvlBefore, bonusPerLevel()) * jd.payMultiplier);
        if (pay > 0) plugin.money().deposit(p.getUniqueId(), pay);
        long after = before + Math.max(0, rawXp);
        pr.xp.put(jd.id, after);
        pr.earned.merge(jd.id, pay, (a, b) -> HypeMath.round2(a + b));
        pr.totalEarned = HypeMath.round2(pr.totalEarned + pay);
        pr.shiftEarned = HypeMath.round2(pr.shiftEarned + pay);
        pr.shiftXp += Math.max(0, rawXp);
        if (mission) {
            pr.totalMissions++;
            pr.shiftMissions++;
            pr.missions.merge(jd.id, 1, Integer::sum);
        }
        pr.dirty = true;
        int lvlAfter = HypeMath.levelFor(after, xpBase(), xpGrowth(), maxLevel());
        if (lvlAfter > lvlBefore) levelUp(p, pr, jd, lvlAfter);
        int plvlAfter = profileLevel(pr);
        if (plvlAfter > plvlBefore) {
            plugin.notifications().send(p, NotifyModule.Type.JOB, "Nivel profesional " + plvlAfter, "Ai deblocat joburi noi in Job Center.");
            store.addHistory(pr, "level", "", "Nivel profesional " + plvlAfter, 0);
        }
        JsonObject o = new JsonObject();
        o.addProperty("pay", pay);
        o.addProperty("xp", rawXp);
        o.addProperty("job", jd.id);
        send(p, "earn", o);
        sendProf(p, pr);
        return pay;
    }

    private void levelUp(Player p, FJobsData.Profile pr, FJobDef jd, int newLevel) {
        String key = jd.id + ":" + newLevel;
        double money = 0;
        ConfigurationSection lr = cfg.getConfigurationSection("settings.level-rewards");
        if (lr != null && lr.contains(String.valueOf(newLevel)) && !pr.rewarded.contains(key)) {
            money = Math.max(0, lr.getDouble(String.valueOf(newLevel)));
            pr.rewarded.add(key);
            if (money > 0) plugin.money().deposit(p.getUniqueId(), money);
        }
        String text = jd.name + " - nivel " + newLevel + (money > 0 ? " (+" + (long) money + "$)" : "");
        plugin.notifications().send(p, NotifyModule.Type.JOB, "Nivel nou!", text);
        store.addHistory(pr, "level", jd.id, text, money);
        pr.dirty = true;
    }

    /** Verificari comune: jucatorul e in tura, jobul e READY si e activ. */
    private FJobDef working(Player p, FJobsData.Profile pr, String activity) {
        if (pr == null || !pr.inShift() || creative(p)) return null;
        FJobDef jd = jobs.get(pr.job);
        if (jd == null || !jd.active || jd.status() != FJobDef.Status.READY || !activity.equals(jd.activity)) return null;
        if (cfg.getBoolean("settings.no-fly", true) && p.isFlying()) return null;
        return jd;
    }

    private boolean reward(Player p, FJobsData.Profile pr, FJobDef jd, JobRules.Rule r) {
        int lvl = jobLevel(pr, jd.id);
        int need = jd.minLevelOf(r);
        if (lvl < need) {
            long now = System.currentTimeMillis();
            Long last = warnedLevel.get(p.getUniqueId());
            if (last == null || now - last > 8000) {
                warnedLevel.put(p.getUniqueId(), now);
                p.sendMessage("§eAceasta activitate se deblocheaza la nivelul " + need + " al jobului " + jd.name + ".");
            }
            return false;
        }
        long now = System.currentTimeMillis();
        Map<String, RateWindow> m = rates.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>());
        RateWindow w = m.get(jd.id);
        if (w == null) {
            w = new RateWindow(jd.limitPerMinute, 60_000L);
            m.put(jd.id, w);
        }
        if (!w.tryAcquire(now)) {
            if (w.firstBlock()) p.sendMessage("§eAi atins limita de castig pe minut pentru acest job. Mai asteapta putin.");
            return false;
        }
        payOut(p, pr, jd, r.pay, r.xp, false);
        return true;
    }

    private static boolean underwater(Block b) {
        if (b.getBlockData() instanceof Waterlogged && ((Waterlogged) b.getBlockData()).isWaterlogged()) return true;
        Material up = b.getRelative(BlockFace.UP).getType();
        return up == Material.WATER || up == Material.KELP_PLANT || up == Material.KELP || up == Material.SEAGRASS || up == Material.TALL_SEAGRASS;
    }

    // ------------------------------------------------------------------ evenimente (activitatea "rules")

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        FJobsData.Profile pr = store.peek(p.getUniqueId());
        FJobDef jd = working(p, pr, "rules");
        if (jd == null || !jd.rules.handles("break")) return;
        Block b = e.getBlock();
        JobRules.Rule r = jd.rules.find("break", b.getType().name());
        if (r == null) return;
        long now = System.currentTimeMillis();
        String key = PlacedSet.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (r.placedCheck && placed.contains(key, now)) return;
        if (r.mature) {
            if (!(b.getBlockData() instanceof Ageable)) return;
            Ageable a = (Ageable) b.getBlockData();
            if (a.getAge() < a.getMaximumAge()) return;
        }
        if (r.underwater && !underwater(b)) return;
        reward(p, pr, jd, r);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        long now = System.currentTimeMillis();
        String key = PlacedSet.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        boolean already = placed.contains(key, now);
        placed.mark(key, now);   // anti-farm: blocurile puse de jucatori nu platesc la spargere, iar repunerea pe aceeasi pozitie nu plateste
        Player p = e.getPlayer();
        FJobsData.Profile pr = store.peek(p.getUniqueId());
        FJobDef jd = working(p, pr, "rules");
        if (jd == null || already || !jd.rules.handles("place")) return;
        JobRules.Rule r = jd.rules.find("place", b.getType().name());
        if (r != null) reward(p, pr, jd, r);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        FJobsData.Profile pr = store.peek(p.getUniqueId());
        FJobDef jd = working(p, pr, "rules");
        Entity c = e.getCaught();
        if (jd == null || !(c instanceof Item) || !jd.rules.handles("fish")) return;
        JobRules.Rule r = jd.rules.find("fish", ((Item) c).getItemStack().getType().name());
        if (r != null) reward(p, pr, jd, r);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (!e.getClickedBlock().getType().name().equals(cfg.getString("settings.trash-block", "CAULDRON"))) return;
        FJobsData.Profile pr = store.peek(p.getUniqueId());
        FJobDef jd = working(p, pr, "rules");
        ItemStack it = e.getItem();
        if (jd == null || it == null || it.getType() == Material.AIR || !jd.rules.handles("trash")) return;
        JobRules.Rule r = jd.rules.find("trash", it.getType().name());
        if (r == null) return;
        e.setCancelled(true);
        if (reward(p, pr, jd, r)) {
            it.setAmount(it.getAmount() - 1);
            p.getInventory().setItemInMainHand(it.getAmount() <= 0 ? null : it);
        }
    }

    // ------------------------------------------------------------------ livrari si rute (tick la 1s)

    private static boolean near(Player p, FJobDef.Point pt, double radius) {
        if (!p.getWorld().getName().equals(pt.world)) return false;
        double dx = p.getLocation().getX() - pt.x;
        double dz = p.getLocation().getZ() - pt.z;
        double dy = p.getLocation().getY() - pt.y;
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= 6;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long maxShift = (long) cfg.getDouble("settings.max-shift-minutes", 120) * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            FJobsData.Profile pr = store.peek(p.getUniqueId());
            if (pr == null || !pr.inShift()) continue;
            if (maxShift > 0 && now - pr.shiftStart > maxShift) {
                closeShift(p, pr, true, "Tura a durat prea mult si a fost incheiata automat.");
                sendData(p, false);
                continue;
            }
            if (creative(p)) continue;
            FJobDef jd = jobs.get(pr.job);
            if (jd == null || !jd.active) continue;
            if ("delivery".equals(jd.activity)) tickDelivery(p, pr, jd, now);
            else if ("route".equals(jd.activity)) tickRoute(p, pr, jd, now);
        }
    }

    private void tickDelivery(Player p, FJobsData.Profile pr, FJobDef jd, long now) {
        FJobDef.Delivery dv = jd.delivery;
        if (dv == null || jd.status() != FJobDef.Status.READY) return;
        Trip t = trips.get(p.getUniqueId());
        if (t != null && !t.job.equals(jd.id)) {
            trips.remove(p.getUniqueId());
            t = null;
        }
        if (t == null) {
            for (FJobDef.Point from : dv.pickups) {
                if (!near(p, from, dv.radius)) continue;
                FJobDef.Point to = pickDrop(dv, from);
                if (to == null) break;
                trips.put(p.getUniqueId(), new Trip(jd.id, from, to));
                plugin.notifications().send(p, NotifyModule.Type.JOB, "Livrare noua",
                    "Du " + dv.item + " la " + to.name + " (X " + (int) to.x + ", Z " + (int) to.z + ").");
                gpsTo(p, to.x, to.z, to.name);
                break;
            }
        } else if (near(p, t.to, dv.radius)) {
            trips.remove(p.getUniqueId());
            double dist = t.from.dist(t.to);
            long minMs = (long) (dist / dv.minSpeed * 1000);
            if (now - t.startedAt < minMs) {
                msg(p, false, "Livrare prea rapida: nu a fost platita.");
                return;
            }
            double pay = HypeMath.round2(dv.basePay + dv.per100m * (dist / 100.0));
            payOut(p, pr, jd, pay, dv.xp, true);
            plugin.notifications().send(p, NotifyModule.Type.JOB, "Livrare incheiata", t.to.name + " - " + Math.round(dist) + " m");
        }
    }

    private FJobDef.Point pickDrop(FJobDef.Delivery dv, FJobDef.Point from) {
        List<FJobDef.Point> pool = new ArrayList<>();
        for (FJobDef.Point pt : dv.drops) if (pt.world.equals(from.world) && pt.dist(from) >= 40) pool.add(pt);
        if (pool.isEmpty()) for (FJobDef.Point pt : dv.drops) if (pt.world.equals(from.world)) pool.add(pt);
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    private void tickRoute(Player p, FJobsData.Profile pr, FJobDef jd, long now) {
        FJobDef.Route r = jd.route;
        if (r == null || jd.status() != FJobDef.Status.READY) return;
        RouteRun run = routes.get(p.getUniqueId());
        if (run == null || !run.job.equals(jd.id)) {
            run = new RouteRun(jd.id);
            routes.put(p.getUniqueId(), run);
        }
        if (run.next >= r.points.size()) run.next = 0;
        FJobDef.Point target = r.points.get(run.next);
        if (run.shownFor != run.next) {
            run.shownFor = run.next;
            gpsTo(p, target.x, target.z, target.name);
            plugin.notifications().send(p, NotifyModule.Type.JOB, jd.name, "Mergi la " + target.name + " (" + (run.next + 1) + "/" + r.points.size() + ").");
        }
        if (!near(p, target, r.radius)) return;
        boolean first = run.next == 0;
        double sec = (now - run.lastAt) / 1000.0;
        run.lastAt = now;
        if (!first && sec < r.minSecondsPerPoint) {
            msg(p, false, "Ai ajuns prea repede la punct: nu a fost platit.");
        } else {
            payOut(p, pr, jd, r.payPerPoint, r.xpPerPoint, false);
        }
        run.next++;
        if (run.next >= r.points.size()) {
            payOut(p, pr, jd, r.completeBonus, r.completeXp, true);
            plugin.notifications().send(p, NotifyModule.Type.JOB, "Ruta terminata", jd.name + ": bonus de finalizare. Ruta o ia de la capat.");
            run.next = 0;
        }
    }

    private void gpsTo(Player p, double x, double z, String name) {
        if (!cfg.getBoolean("settings.gps-integration", true)) return;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gps set " + p.getName() + " " + (int) x + " " + (int) z + " " + name.replaceAll("[^A-Za-z0-9 _.-]", ""));
        } catch (RuntimeException ignored) {
            // GPS-ul e optional
        }
    }

    // ------------------------------------------------------------------ date catre client

    private JsonObject profJson(Player p, FJobsData.Profile pr) {
        JsonObject m = new JsonObject();
        int pl = profileLevel(pr);
        int pmax = cfg.getInt("settings.profile-max-level", 100);
        double pb = cfg.getDouble("settings.profile-xp-base", 150);
        double pg = cfg.getDouble("settings.profile-xp-growth", 1.3);
        m.addProperty("name", p.getName());
        m.addProperty("job", pr.job);
        m.addProperty("level", pl);
        m.addProperty("xp", HypeMath.xpIntoLevel(pr.totalXp(), pb, pg, pmax));
        m.addProperty("xpNeed", pl >= pmax ? 0 : HypeMath.xpForLevel(pl, pb, pg));
        m.addProperty("totalXp", pr.totalXp());
        m.addProperty("totalEarned", pr.totalEarned);
        m.addProperty("shifts", pr.totalShifts);
        m.addProperty("missions", pr.totalMissions);
        m.addProperty("seconds", pr.totalSeconds);
        m.addProperty("inShift", pr.inShift());
        m.addProperty("shiftSec", pr.inShift() ? (System.currentTimeMillis() - pr.shiftStart) / 1000 : 0);
        m.addProperty("shiftEarned", pr.shiftEarned);
        m.addProperty("shiftMissions", pr.shiftMissions);
        m.addProperty("cooldownMs", cooldownLeft(pr));
        m.addProperty("cash", plugin.money().balance(p.getUniqueId()));
        if (!pr.job.isEmpty()) {
            int jl = jobLevel(pr, pr.job);
            long total = pr.xp.getOrDefault(pr.job, 0L);
            m.addProperty("jobLevel", jl);
            m.addProperty("jobXp", HypeMath.xpIntoLevel(total, xpBase(), xpGrowth(), maxLevel()));
            m.addProperty("jobXpNeed", jl >= maxLevel() ? 0 : HypeMath.xpForLevel(jl, xpBase(), xpGrowth()));
        }
        return m;
    }

    private void sendProf(Player p, FJobsData.Profile pr) {
        if (viewers.contains(p.getUniqueId())) send(p, "prof", profJson(p, pr));
    }

    private void sendData(Player p, boolean register) {
        FJobsData.Profile pr = prof(p);
        if (register) viewers.add(p.getUniqueId());
        JsonObject o = new JsonObject();
        o.add("me", profJson(p, pr));
        o.addProperty("admin", isAdmin(p));
        o.addProperty("premiumOn", cfg.getBoolean("settings.premium-enabled", false));
        o.addProperty("bonus", bonusPerLevel());
        o.addProperty("maxShiftMin", cfg.getDouble("settings.max-shift-minutes", 120));
        JsonArray arr = new JsonArray();
        List<FJobDef> hireable = new ArrayList<>();
        for (FJobDef j : jobs.values()) {
            JsonObject e = new JsonObject();
            int lvl = jobLevel(pr, j.id);
            long total = pr.xp.getOrDefault(j.id, 0L);
            String why = whyNot(p, pr, j);
            e.addProperty("id", j.id);
            e.addProperty("name", j.name);
            e.addProperty("icon", j.icon);
            e.addProperty("category", j.category);
            e.addProperty("group", j.group);
            e.addProperty("desc", j.desc);
            e.addProperty("difficulty", j.difficulty);
            e.addProperty("estPay", j.estPay);
            e.addProperty("estMinutes", j.estMinutes);
            e.addProperty("minLevel", j.minLevel);
            e.addProperty("reqXp", j.reqXp);
            e.addProperty("max", j.maxEmployees);
            e.addProperty("employees", store.employees(j.id));
            e.addProperty("active", j.active);
            e.addProperty("premium", j.premium);
            e.addProperty("payMult", j.payMultiplier);
            e.addProperty("status", j.status().name());
            e.addProperty("note", j.statusNote());
            e.addProperty("activity", j.activity);
            e.addProperty("level", lvl);
            e.addProperty("xp", HypeMath.xpIntoLevel(total, xpBase(), xpGrowth(), maxLevel()));
            e.addProperty("xpNeed", lvl >= maxLevel() ? 0 : HypeMath.xpForLevel(lvl, xpBase(), xpGrowth()));
            e.addProperty("earned", pr.earned.getOrDefault(j.id, 0.0));
            e.addProperty("shifts", pr.shifts.getOrDefault(j.id, 0));
            e.addProperty("missions", pr.missions.getOrDefault(j.id, 0));
            e.addProperty("canHire", why.isEmpty() && !j.id.equals(pr.job));
            e.addProperty("why", why);
            if (j.delivery != null) {
                e.addProperty("pickups", j.delivery.pickups.size());
                e.addProperty("drops", j.delivery.drops.size());
            }
            if (j.route != null) e.addProperty("route", j.route.points.size());
            JsonArray ts = new JsonArray();
            for (String t : j.tasks) ts.add(t);
            e.add("tasks", ts);
            arr.add(e);
            if (why.isEmpty() && !j.id.equals(pr.job)) hireable.add(j);
        }
        o.add("jobs", arr);
        JsonArray hist = new JsonArray();
        long now = System.currentTimeMillis();
        for (int i = pr.history.size() - 1; i >= 0 && hist.size() < 40; i--) {
            FJobsData.Entry en = pr.history.get(i);
            JsonObject h = new JsonObject();
            h.addProperty("ageSec", Math.max(0, (now - en.at) / 1000));
            h.addProperty("type", en.type);
            h.addProperty("job", en.job);
            h.addProperty("text", en.text);
            h.addProperty("money", en.money);
            hist.add(h);
        }
        o.add("history", hist);
        List<FJobDef> rec = new ArrayList<>(hireable);
        Collections.sort(rec, (a, b) -> Double.compare(b.estPay, a.estPay));
        JsonArray ra = new JsonArray();
        for (int i = 0; i < Math.min(3, rec.size()); i++) ra.add(rec.get(i).id);
        o.add("recommended", ra);
        List<FJobDef> pop = new ArrayList<>(jobs.values());
        Collections.sort(pop, (a, b) -> Integer.compare(store.employees(b.id), store.employees(a.id)));
        JsonArray pa = new JsonArray();
        for (int i = 0; i < pop.size() && pa.size() < 3; i++) {
            if (store.employees(pop.get(i).id) > 0) pa.add(pop.get(i).id);
        }
        o.add("popular", pa);
        send(p, "data", o);
    }

    private void pushToViewers() {
        for (UUID u : new ArrayList<>(viewers)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) viewers.remove(u);
            else sendData(p, false);
        }
    }

    private void msg(Player p, boolean ok, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("text", text);
        send(p, "msg", o);
        if (!plugin.channel().hasMod(p)) p.sendMessage((ok ? "§a" : "§c") + text);
    }

    private void openFor(Player p) {
        if (!plugin.channel().hasMod(p)) {
            p.sendMessage("§cJob Center necesita modul FlowCity instalat pe client.");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("page", "home");
        send(p, "open", o);
        sendData(p, true);
    }

    // ------------------------------------------------------------------ administrare (din GUI)

    private void admSet(Player p, JsonObject j) {
        if (!isAdmin(p)) {
            msg(p, false, "Nu ai permisiunea de administrare.");
            return;
        }
        if (throttled(p)) return;
        FJobDef jd = jobs.get(str(j, "id", "").toLowerCase(Locale.ROOT));
        String field = str(j, "field", "");
        if (jd == null) {
            msg(p, false, "Job necunoscut.");
            return;
        }
        String base = "jobs." + jd.id + ".";
        switch (field) {
            case "active":
                jd.active = bool(j, "value", jd.active);
                over.set(base + "active", jd.active);
                break;
            case "premium":
                jd.premium = bool(j, "value", jd.premium);
                over.set(base + "premium", jd.premium);
                break;
            case "estPay":
                jd.estPay = clamp(Math.round(num(j, "value", jd.estPay) * 100) / 100.0, 0, 100000);
                over.set(base + "est-pay", jd.estPay);
                break;
            case "estMinutes":
                jd.estMinutes = (int) clamp(integer(j, "value", jd.estMinutes), 1, 600);
                over.set(base + "est-minutes", jd.estMinutes);
                break;
            case "minLevel":
                jd.minLevel = (int) clamp(integer(j, "value", jd.minLevel), 0, 100);
                over.set(base + "min-level", jd.minLevel);
                break;
            case "reqXp":
                jd.reqXp = (long) clamp(num(j, "value", jd.reqXp), 0, 10_000_000);
                over.set(base + "req-xp", jd.reqXp);
                break;
            case "max":
                jd.maxEmployees = (int) clamp(integer(j, "value", jd.maxEmployees), 0, 500);
                over.set(base + "max-employees", jd.maxEmployees);
                break;
            case "payMult":
                jd.payMultiplier = clamp(Math.round(num(j, "value", jd.payMultiplier) * 100) / 100.0, 0, 10);
                over.set(base + "pay-multiplier", jd.payMultiplier);
                break;
            default:
                msg(p, false, "Camp necunoscut.");
                return;
        }
        saveOver();
        plugin.getLogger().info("[fjobs] " + p.getName() + " a setat " + jd.id + "." + field);
        msg(p, true, jd.name + ": " + field + " actualizat.");
        pushToViewers();
    }

    private void admPoint(Player p, JsonObject j) {
        if (!isAdmin(p)) {
            msg(p, false, "Nu ai permisiunea de administrare.");
            return;
        }
        if (throttled(p)) return;
        FJobDef jd = jobs.get(str(j, "id", "").toLowerCase(Locale.ROOT));
        String kind = str(j, "kind", "");
        String op = str(j, "op", "");
        if (jd == null) {
            msg(p, false, "Job necunoscut.");
            return;
        }
        List<FJobDef.Point> list;
        String key;
        if ("pickups".equals(kind) || "drops".equals(kind)) {
            if (!"delivery".equals(jd.activity)) {
                msg(p, false, "Jobul nu foloseste livrari.");
                return;
            }
            ensureDelivery(jd);
            list = "pickups".equals(kind) ? jd.delivery.pickups : jd.delivery.drops;
            key = kind;
        } else if ("route".equals(kind)) {
            if (!"route".equals(jd.activity)) {
                msg(p, false, "Jobul nu foloseste rute.");
                return;
            }
            ensureRoute(jd);
            list = jd.route.points;
            key = "route";
        } else {
            msg(p, false, "Tip de punct necunoscut.");
            return;
        }
        if ("add".equals(op)) {
            if (list.size() >= 64) {
                msg(p, false, "Maxim 64 de puncte.");
                return;
            }
            FJobDef.Point pt = new FJobDef.Point();
            String nm = str(j, "name", "").trim().replaceAll("[^\\p{L}\\p{N} _.-]", "");
            pt.name = nm.isEmpty() ? "Punct " + (list.size() + 1) : nm.substring(0, Math.min(32, nm.length()));
            pt.world = p.getWorld().getName();
            pt.x = p.getLocation().getX();
            pt.y = p.getLocation().getY();
            pt.z = p.getLocation().getZ();
            list.add(pt);
        } else if ("remove".equals(op)) {
            int idx = integer(j, "index", -1);
            if (idx < 0 || idx >= list.size()) {
                msg(p, false, "Index invalid.");
                return;
            }
            list.remove(idx);
        } else if ("clear".equals(op)) {
            list.clear();
        } else {
            msg(p, false, "Operatie necunoscuta.");
            return;
        }
        over.set("jobs." + jd.id + "." + key, FJobDef.writePoints(list));
        saveOver();
        routes.clear();
        trips.clear();
        plugin.getLogger().info("[fjobs] " + p.getName() + " " + op + " " + key + " la " + jd.id + " (acum " + list.size() + ")");
        msg(p, true, jd.name + ": " + key + " = " + list.size() + " puncte.");
        pushToViewers();
    }

    // ------------------------------------------------------------------ comenzi (doar deschiderea; administrarea e in GUI)

    private void registerCommands() {
        Commands.register(plugin, "fjobs", PERM_USE, (s, c, l, a) -> {
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {   // pentru NPC-uri: /npc command add fjobs open <p> -c
                if (s instanceof Player && !s.hasPermission(PERM_ADMIN)) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage("§cJucator offline.");
                    return true;
                }
                openFor(t);
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatorii pot deschide Job Center. NPC: fjobs open <jucator>");
                return true;
            }
            openFor((Player) s);
            return true;
        }, (s, c, l, a) -> a.length == 1 ? Commands.filter(Arrays.asList("open"), a[0]) : new ArrayList<String>(), "job", "joburi");
    }

    // ------------------------------------------------------------------ ciclu de viata jucator

    @Override
    public void onClientReady(Player p) {
        prof(p);
    }

    @Override
    public void onQuit(Player p) {
        viewers.remove(p.getUniqueId());
        trips.remove(p.getUniqueId());
        routes.remove(p.getUniqueId());
        rates.remove(p.getUniqueId());
        lastAction.remove(p.getUniqueId());
        warnedLevel.remove(p.getUniqueId());
        FJobsData.Profile pr = store.peek(p.getUniqueId());
        if (pr != null) {
            if (pr.inShift()) closeShift(p, pr, false, null);
            pr.loadedOnline = false;
        }
    }

    public static JsonObject hud(Player p) {
        FJobsModule m = instance;
        return m == null ? null : m.hudInfo(p);
    }

    /** Pentru HUD (HypeModule): jobul, nivelul si XP-ul curent al jucatorului, sau null daca nu are job. */
    public JsonObject hudInfo(Player p) {
        if (store == null) return null;
        FJobsData.Profile pr = store.peek(p.getUniqueId());
        if (pr == null || pr.job.isEmpty()) return null;
        FJobDef j = jobs.get(pr.job);
        if (j == null) return null;
        int lvl = jobLevel(pr, j.id);
        long total = pr.xp.getOrDefault(j.id, 0L);
        JsonObject o = new JsonObject();
        o.addProperty("job", j.name);
        o.addProperty("level", lvl);
        o.addProperty("xp", HypeMath.xpIntoLevel(total, xpBase(), xpGrowth(), maxLevel()));
        o.addProperty("xpNeed", lvl >= maxLevel() ? 0 : HypeMath.xpForLevel(lvl, xpBase(), xpGrowth()));
        return o;
    }
}
