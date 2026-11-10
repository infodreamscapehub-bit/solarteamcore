package ro.solarstudios.flowcity.bridge.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
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
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.hype.HypeDb.PlayerData;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Hype-lite: HUD (stare), ATM, joburi si sete. Logica de server; clientul doar afiseaza si cere actiuni.
 * Actiuni client -> server: state_req, atm_req, atm_deposit, atm_withdraw, atm_transfer, jobs_req, job_select, job_leave.
 * Server -> client: state, atm, jobs, msg, earn, open, hud_toggle.
 */
public final class HypeModule extends JsonModule implements Listener {
    private static final class JobDef {
        String id;
        String name;
        String desc;
        String summary;
        int limitPerMinute;
        final JobRules rules = new JobRules();
    }

    private YamlConfiguration cfg;
    private HypeDb hdb;
    private AtmService atm;
    private PlacedSet placed;
    private final Map<String, JobDef> jobs = new LinkedHashMap<>();
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final Set<UUID> ready = new HashSet<>();
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public String id() {
        return "hype";
    }

    // ------------------------------------------------------------------ pornire

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "hype");
        try {
            Db db = new Db(new File(plugin.getDataFolder(), "hype.db"), plugin.getLogger());
            hdb = new HypeDb(db);
            hdb.init();
        } catch (Exception e) {
            plugin.getLogger().severe("Hype: nu pot porni baza de date: " + e);
            return;
        }
        atm = new AtmService(plugin, hdb, cfg);
        atm.loadBlocks();
        loadJobs();

        on("state_req", (p, j) -> pushState(p, true));
        on("atm_req", (p, j) -> sendAtm(p, null, true));
        on("atm_deposit", (p, j) -> atmOp(p, "deposit", j));
        on("atm_withdraw", (p, j) -> atmOp(p, "withdraw", j));
        on("atm_transfer", (p, j) -> atmOp(p, "transfer", j));
        on("jobs_req", (p, j) -> sendJobs(p));
        on("job_select", (p, j) -> selectJob(p, str(j, "id", "")));
        on("job_leave", (p, j) -> leaveJob(p));

        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();

        // push de stare la 2s, drain de sete la 5s, salvare la 3 minute, curatare blocuri puse la 1 minut
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickState, 40L, 40L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tickThirst, 100L, 100L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, 3600L, 3600L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> placed.purge(System.currentTimeMillis()), 1200L, 1200L));

        for (Player p : Bukkit.getOnlinePlayers()) loadPlayer(p);
    }

    @Override
    public void disable() {
        for (BukkitTask t : tasks) t.cancel();
        tasks.clear();
        if (hdb != null) {
            for (PlayerData d : data.values()) if (d.loaded) hdb.save(d);
            hdb.raw().close();
        }
        data.clear();
        ready.clear();
    }

    private void loadJobs() {
        jobs.clear();
        long ttl = (long) (cfg.getDouble("jobs.placed-ttl-minutes", 180) * 60_000L);
        placed = new PlacedSet(ttl, cfg.getInt("jobs.placed-max", 60000));
        ConfigurationSection list = cfg.getConfigurationSection("jobs.list");
        if (list == null) return;
        for (String id : list.getKeys(false)) {
            ConfigurationSection s = list.getConfigurationSection(id);
            if (s == null) continue;
            JobDef j = new JobDef();
            j.id = id.toLowerCase(Locale.ROOT);
            j.name = s.getString("name", id);
            j.desc = s.getString("desc", "");
            j.summary = s.getString("summary", "");
            j.limitPerMinute = s.getInt("limit-per-minute", 0);
            for (Map<?, ?> m : s.getMapList("rules")) {
                Object ev = m.get("event");
                Object mats = m.get("materials");
                if (ev == null || !(mats instanceof List)) continue;
                List<String> ms = new ArrayList<>();
                for (Object o : (List<?>) mats) ms.add(String.valueOf(o));
                double pay = m.get("pay") instanceof Number ? ((Number) m.get("pay")).doubleValue() : 0;
                long xp = m.get("xp") instanceof Number ? ((Number) m.get("xp")).longValue() : 0;
                boolean mature = Boolean.TRUE.equals(m.get("mature"));
                boolean under = Boolean.TRUE.equals(m.get("underwater"));
                boolean pc = !Boolean.FALSE.equals(m.get("placed-check"));
                j.rules.add(new JobRules.Rule(String.valueOf(ev), ms, pay, xp, mature, under, pc));
            }
            jobs.put(j.id, j);
        }
    }

    // ------------------------------------------------------------------ date jucator

    private void loadPlayer(Player p) {
        final UUID u = p.getUniqueId();
        if (data.containsKey(u)) return;
        PlayerData placeholder = new PlayerData(u);
        data.put(u, placeholder);
        hdb.load(u, HypeMath.dayStart(System.currentTimeMillis())).whenComplete((d, err) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player pl = Bukkit.getPlayer(u);
                if (err != null || d == null || pl == null) {
                    data.remove(u);
                    if (err != null) plugin.getLogger().warning("Hype: nu pot incarca " + u + ": " + err);
                    return;
                }
                d.loaded = true;
                data.put(u, d);
                if (ready.contains(u)) pushState(pl, true);
            }));
    }

    private PlayerData dataOf(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        return d != null && d.loaded ? d : null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        loadPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuitEvent(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        PlayerData d = data.remove(u);
        if (d != null && d.loaded) hdb.save(d);
        ready.remove(u);
        lastAction.remove(u);
    }

    @Override
    public void onClientReady(Player p) {
        ready.add(p.getUniqueId());
        if (dataOf(p) != null) pushState(p, true);
    }

    @Override
    public void onQuit(Player p) {
        ready.remove(p.getUniqueId());
    }

    private void saveDirty() {
        for (PlayerData d : data.values()) if (d.loaded && d.dirty) hdb.save(d);
    }

    // ------------------------------------------------------------------ stare / HUD

    private String rankOf(Player p) {
        for (Map<?, ?> m : cfg.getMapList("hud.ranks")) {
            Object perm = m.get("perm");
            Object name = m.get("name");
            if (perm != null && name != null && p.hasPermission(String.valueOf(perm))) return String.valueOf(name);
        }
        return cfg.getString("hud.default-rank", "Jucator");
    }

    private int levelOf(PlayerData d, String job) {
        Long xp = d.xp.get(job);
        return HypeMath.levelFor(xp == null ? 0 : xp, cfg.getDouble("jobs.xp-base", 100), cfg.getDouble("jobs.xp-growth", 1.35), cfg.getInt("jobs.max-level", 50));
    }

    private JsonObject buildState(Player p, PlayerData d) {
        JsonObject o = new JsonObject();
        UUID u = p.getUniqueId();
        o.addProperty("name", p.getName());
        o.addProperty("cash", plugin.money().balance(u));
        o.addProperty("bank", plugin.bank().balance(u));
        o.addProperty("rank", rankOf(p));
        o.addProperty("id", plugin.ids().idOf(u));
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("playMin", p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200);
        JobDef jd = d.job == null ? null : jobs.get(d.job);
        o.addProperty("job", jd == null ? "" : jd.name);
        int max = cfg.getInt("jobs.max-level", 50);
        if (jd != null) {
            int lvl = levelOf(d, jd.id);
            double base = cfg.getDouble("jobs.xp-base", 100);
            double growth = cfg.getDouble("jobs.xp-growth", 1.35);
            Long xp = d.xp.get(jd.id);
            long total = xp == null ? 0 : xp;
            o.addProperty("level", lvl);
            o.addProperty("xp", HypeMath.xpIntoLevel(total, base, growth, max));
            o.addProperty("xpNeed", lvl >= max ? 0 : HypeMath.xpForLevel(lvl, base, growth));
        }
        boolean thirst = cfg.getBoolean("thirst.enabled", true);
        o.addProperty("thirstOn", thirst);
        o.addProperty("thirst", (int) Math.round(Math.max(0, Math.min(100, d.thirst))));
        return o;
    }

    private void pushState(Player p, boolean force) {
        PlayerData d = dataOf(p);
        if (d == null) return;
        JsonObject s = buildState(p, d);
        String str = s.toString();
        if (!force && str.equals(d.lastState)) return;
        d.lastState = str;
        send(p, "state", s);
    }

    private void tickState() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (ready.contains(p.getUniqueId())) pushState(p, false);
        }
    }

    private void msg(Player p, boolean ok, String text) {
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("text", text);
            send(p, "msg", o);
        } else {
            p.sendMessage((ok ? "§a" : "§c") + text);
        }
    }

    private boolean throttled(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < 250) return true;
        lastAction.put(p.getUniqueId(), now);
        return false;
    }

    // ------------------------------------------------------------------ ATM

    private void sendAtm(Player p, AtmService.Result r, boolean open) {
        PlayerData d = dataOf(p);
        if (d == null) return;
        JsonObject o = atm.data(p, d);
        if (r != null) {
            o.addProperty("ok", r.ok);
            o.addProperty("message", r.message);
        }
        o.addProperty("open", open);
        send(p, "atm", o);
    }

    private void atmOp(Player p, String kind, JsonObject j) {
        PlayerData d = dataOf(p);
        if (d == null || throttled(p)) return;
        double amount = num(j, "amount", -1);
        AtmService.Result r;
        if ("deposit".equals(kind)) r = atm.deposit(p, d, amount);
        else if ("withdraw".equals(kind)) r = atm.withdraw(p, d, amount);
        else {
            String to = str(j, "to", "").trim();
            Player tp = Bukkit.getPlayerExact(to);
            r = atm.transfer(p, d, to, amount, tp == null ? null : dataOf(tp));
        }
        d.lastState = "";
        sendAtm(p, r, false);
    }

    /** Varianta text (fara modul pe client): /atm, /atm deposit|withdraw <suma>, /atm transfer <jucator> <suma>. */
    private void atmCommand(Player p, String[] a) {
        PlayerData d = dataOf(p);
        if (d == null) {
            p.sendMessage("§cDatele tale se incarca, mai incearca.");
            return;
        }
        if (a.length == 0) {
            if (plugin.channel().hasMod(p)) {
                if (!atm.canUse(p)) {
                    p.sendMessage("§cTrebuie sa fii langa un ATM.");
                    return;
                }
                sendAtm(p, null, true);
            } else {
                p.sendMessage("§bCash: §f$" + Fmt.money(plugin.money().balance(p.getUniqueId()))
                    + " §7| §bBanca: §f$" + Fmt.money(plugin.bank().balance(p.getUniqueId())));
                p.sendMessage("§7Folosire: /atm <deposit|withdraw> <suma> | /atm transfer <jucator> <suma>");
            }
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        try {
            AtmService.Result r = null;
            if (("deposit".equals(sub) || "withdraw".equals(sub)) && a.length >= 2) {
                double amt = Double.parseDouble(a[1]);
                r = "deposit".equals(sub) ? atm.deposit(p, d, amt) : atm.withdraw(p, d, amt);
            } else if ("transfer".equals(sub) && a.length >= 3) {
                Player tp = Bukkit.getPlayerExact(a[1]);
                r = atm.transfer(p, d, a[1], Double.parseDouble(a[2]), tp == null ? null : dataOf(tp));
            }
            if (r == null) {
                p.sendMessage("§7Folosire: /atm <deposit|withdraw> <suma> | /atm transfer <jucator> <suma>");
                return;
            }
            d.lastState = "";
            p.sendMessage((r.ok ? "§a" : "§c") + r.message);
        } catch (NumberFormatException ex) {
            p.sendMessage("§cSuma invalida.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (atm != null && atm.isAtmBlock(b.getLocation())) {
            e.setCancelled(true);
            if (!p.hasPermission("flowcity.hype.atm")) {
                p.sendMessage("§cNu ai acces la ATM.");
                return;
            }
            atmCommand(p, new String[0]);
            return;
        }
        // Gunoier: click dreapta cu un obiect pe cazan
        if (b.getType().name().equals(cfg.getString("jobs.trash-block", "CAULDRON"))) {
            PlayerData d = dataOf(p);
            JobDef jd = d == null || d.job == null ? null : jobs.get(d.job);
            ItemStack it = e.getItem();
            if (jd == null || it == null || it.getType() == Material.AIR || !jd.rules.handles("trash")) return;
            JobRules.Rule r = jd.rules.find("trash", it.getType().name());
            if (r == null) return;
            e.setCancelled(true);
            if (reward(p, d, jd, r)) {
                it.setAmount(it.getAmount() - 1);
                p.getInventory().setItemInMainHand(it.getAmount() <= 0 ? null : it);
            }
        }
    }

    // ------------------------------------------------------------------ joburi

    private boolean creative(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.CREATIVE || g == GameMode.SPECTATOR;
    }

    private static boolean underwater(Block b) {
        if (b.getBlockData() instanceof Waterlogged && ((Waterlogged) b.getBlockData()).isWaterlogged()) return true;
        Material up = b.getRelative(BlockFace.UP).getType();
        return up == Material.WATER || up == Material.KELP_PLANT || up == Material.KELP || up == Material.SEAGRASS || up == Material.TALL_SEAGRASS;
    }

    /** Plateste o actiune de job (rata limitata, bonus de nivel). Intoarce true daca s-a platit. */
    private boolean reward(Player p, PlayerData d, JobDef jd, JobRules.Rule r) {
        long now = System.currentTimeMillis();
        RateWindow w = d.rates.get(jd.id);
        if (w == null) {
            w = new RateWindow(jd.limitPerMinute, 60_000L);
            d.rates.put(jd.id, w);
        }
        if (!w.tryAcquire(now)) {
            if (w.firstBlock()) p.sendMessage("§eAi atins limita de castig pe minut pentru acest job. Mai asteapta putin.");
            return false;
        }
        double base = cfg.getDouble("jobs.xp-base", 100);
        double growth = cfg.getDouble("jobs.xp-growth", 1.35);
        int max = cfg.getInt("jobs.max-level", 50);
        Long old = d.xp.get(jd.id);
        long before = old == null ? 0 : old;
        int lvlBefore = HypeMath.levelFor(before, base, growth, max);
        double pay = HypeMath.payWithBonus(r.pay, lvlBefore, cfg.getDouble("jobs.bonus-per-level", 0.03));
        if (pay > 0) plugin.money().deposit(p.getUniqueId(), pay);
        long after = before + r.xp;
        d.xp.put(jd.id, after);
        Double e = d.earned.get(jd.id);
        d.earned.put(jd.id, HypeMath.round2((e == null ? 0 : e) + pay));
        d.dirty = true;
        d.lastState = "";
        int lvlAfter = HypeMath.levelFor(after, base, growth, max);
        if (lvlAfter > lvlBefore) {
            plugin.notifications().send(p, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.JOB, "Nivel nou!", jd.name + " - nivel " + lvlAfter);
        }
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("pay", pay);
            o.addProperty("xp", r.xp);
            send(p, "earn", o);
        } else {
            p.sendMessage("§7+§a$" + Fmt.money(pay) + " §8| §b+" + r.xp + " XP");
        }
        return true;
    }

    private JobDef activeJob(Player p, PlayerData d, String event) {
        if (d == null || d.job == null || creative(p)) return null;
        JobDef jd = jobs.get(d.job);
        return jd != null && jd.rules.handles(event) ? jd : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        PlayerData d = dataOf(p);
        Block b = e.getBlock();
        JobDef jd = activeJob(p, d, "break");
        if (jd == null) return;
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
        reward(p, d, jd, r);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        long now = System.currentTimeMillis();
        String key = PlacedSet.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        boolean already = placed.contains(key, now);
        placed.mark(key, now); // anti-farm: blocurile puse de jucatori nu platesc la spargere
        Player p = e.getPlayer();
        PlayerData d = dataOf(p);
        JobDef jd = activeJob(p, d, "place");
        if (jd == null || already) return;
        JobRules.Rule r = jd.rules.find("place", b.getType().name());
        if (r != null) reward(p, d, jd, r);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        Player p = e.getPlayer();
        PlayerData d = dataOf(p);
        JobDef jd = activeJob(p, d, "fish");
        Entity c = e.getCaught();
        if (jd == null || !(c instanceof Item)) return;
        JobRules.Rule r = jd.rules.find("fish", ((Item) c).getItemStack().getType().name());
        if (r != null) reward(p, d, jd, r);
    }

    private void sendJobs(Player p) {
        PlayerData d = dataOf(p);
        if (d == null) return;
        double base = cfg.getDouble("jobs.xp-base", 100);
        double growth = cfg.getDouble("jobs.xp-growth", 1.35);
        int max = cfg.getInt("jobs.max-level", 50);
        JsonObject o = new JsonObject();
        o.addProperty("current", d.job == null ? "" : d.job);
        long cdMs = (long) (cfg.getDouble("jobs.change-cooldown-minutes", 60) * 60_000L);
        o.addProperty("cooldownMs", HypeMath.cooldownLeft(d.changedAt, cdMs, System.currentTimeMillis()));
        o.addProperty("bonus", cfg.getDouble("jobs.bonus-per-level", 0.03));
        JsonArray arr = new JsonArray();
        for (JobDef jd : jobs.values()) {
            JsonObject j = new JsonObject();
            Long xp = d.xp.get(jd.id);
            long total = xp == null ? 0 : xp;
            int lvl = HypeMath.levelFor(total, base, growth, max);
            j.addProperty("id", jd.id);
            j.addProperty("name", jd.name);
            j.addProperty("desc", jd.desc);
            j.addProperty("summary", jd.summary);
            j.addProperty("level", lvl);
            j.addProperty("xp", HypeMath.xpIntoLevel(total, base, growth, max));
            j.addProperty("xpNeed", lvl >= max ? 0 : HypeMath.xpForLevel(lvl, base, growth));
            Double er = d.earned.get(jd.id);
            j.addProperty("earned", er == null ? 0 : er);
            arr.add(j);
        }
        o.add("jobs", arr);
        send(p, "jobs", o);
    }

    private void selectJob(Player p, String id) {
        PlayerData d = dataOf(p);
        if (d == null || throttled(p)) return;
        JobDef jd = jobs.get(id.toLowerCase(Locale.ROOT));
        if (jd == null) {
            msg(p, false, "Job necunoscut.");
            return;
        }
        if (jd.id.equals(d.job)) {
            msg(p, false, "Ai deja acest job.");
            return;
        }
        long cdMs = (long) (cfg.getDouble("jobs.change-cooldown-minutes", 60) * 60_000L);
        long left = d.job == null ? 0 : HypeMath.cooldownLeft(d.changedAt, cdMs, System.currentTimeMillis());
        if (left > 0) {
            msg(p, false, "Poti schimba jobul peste " + HypeMath.formatLeft(left) + ".");
            sendJobs(p);
            return;
        }
        d.job = jd.id;
        d.changedAt = System.currentTimeMillis();
        d.dirty = true;
        d.lastState = "";
        hdb.save(d);
        msg(p, true, "Acum lucrezi ca " + jd.name + ".");
        sendJobs(p);
    }

    private void leaveJob(Player p) {
        PlayerData d = dataOf(p);
        if (d == null || throttled(p)) return;
        if (d.job == null) {
            msg(p, false, "Nu ai niciun job.");
            return;
        }
        long cdMs = (long) (cfg.getDouble("jobs.change-cooldown-minutes", 60) * 60_000L);
        long left = HypeMath.cooldownLeft(d.changedAt, cdMs, System.currentTimeMillis());
        if (left > 0) {
            msg(p, false, "Poti parasi jobul peste " + HypeMath.formatLeft(left) + ".");
            return;
        }
        d.job = null;
        d.changedAt = System.currentTimeMillis();
        d.dirty = true;
        d.lastState = "";
        hdb.save(d);
        msg(p, true, "Ai parasit jobul.");
        sendJobs(p);
    }

    private void jobCommand(Player p, String[] a) {
        PlayerData d = dataOf(p);
        if (d == null) {
            p.sendMessage("§cDatele tale se incarca, mai incearca.");
            return;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (sub.isEmpty() && plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("screen", "jobs");
            send(p, "open", o);
            sendJobs(p);
        } else if ("select".equals(sub) || "alege".equals(sub)) {
            if (a.length < 2) p.sendMessage("§7Folosire: /job select <id>");
            else selectJob(p, a[1]);
        } else if ("leave".equals(sub) || "paraseste".equals(sub)) {
            leaveJob(p);
        } else {
            p.sendMessage("§bJoburi disponibile:");
            for (JobDef jd : jobs.values()) {
                p.sendMessage("§8- §f" + jd.id + " §7(" + jd.name + ", nivel " + levelOf(d, jd.id) + ") §8" + jd.summary);
            }
            JobDef cur = d.job == null ? null : jobs.get(d.job);
            p.sendMessage("§7Job curent: §f" + (cur == null ? "niciunul" : cur.name) + " §8| §7/job select <id> | /job leave");
        }
    }

    // ------------------------------------------------------------------ sete

    private void tickThirst() {
        if (!cfg.getBoolean("thirst.enabled", true)) return;
        long now = System.currentTimeMillis();
        double perMin = cfg.getDouble("thirst.drain-per-minute", 4.0);
        double sprint = cfg.getDouble("thirst.sprint-multiplier", 2.5);
        double swim = cfg.getDouble("thirst.swim-multiplier", 1.5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = dataOf(p);
            if (d == null || p.isDead() || creative(p)) continue;
            double mult = 1.0;
            if (p.isSprinting()) mult = Math.max(mult, sprint);
            if (p.isSwimming()) mult = Math.max(mult, swim);
            double before = d.thirst;
            d.thirst = Math.max(0, d.thirst - perMin * mult * 5.0 / 60.0);
            if (Math.round(before) != Math.round(d.thirst)) d.dirty = true;
            double warn = cfg.getDouble("thirst.warn-below", 20);
            if (d.thirst < warn && d.thirst > 0 && now - d.lastThirstWarn > 30_000L) {
                d.lastThirstWarn = now;
                p.sendMessage("§bTi-e sete! §7Bea apa sau mananca fructe (/sete).");
            }
            if (d.thirst <= 0) {
                long interval = cfg.getInt("thirst.damage-interval-seconds", 5) * 1000L;
                if (now - d.lastThirstDamage >= interval) {
                    d.lastThirstDamage = now;
                    boolean lethal = cfg.getBoolean("thirst.lethal", false);
                    double minHp = cfg.getDouble("thirst.min-health", 2.0);
                    double dmg = cfg.getDouble("thirst.damage-at-zero", 1.0);
                    if (lethal || p.getHealth() - dmg >= minHp) p.damage(dmg);
                }
            }
        }
    }

    private static boolean isWaterBottle(ItemStack it) {
        if (it.getType() != Material.POTION || !(it.getItemMeta() instanceof PotionMeta)) return false;
        PotionMeta pm = (PotionMeta) it.getItemMeta();
        return pm.getBasePotionData() != null && pm.getBasePotionData().getType() == PotionType.WATER;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        PlayerData d = dataOf(p);
        if (d == null || !cfg.getBoolean("thirst.enabled", true)) return;
        ItemStack it = e.getItem();
        String key = isWaterBottle(it) ? "WATER_BOTTLE" : it.getType().name();
        double add = cfg.getDouble("thirst.items." + key, 0);
        if (add <= 0) return;
        d.thirst = Math.min(100, d.thirst + add);
        d.dirty = true;
        d.lastState = "";
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = dataOf(e.getPlayer());
        if (d == null) return;
        d.thirst = cfg.getDouble("thirst.respawn-value", 60);
        d.dirty = true;
        d.lastState = "";
    }

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.permission("flowcity.hype.atm", "Foloseste ATM-ul", PermissionDefault.TRUE);
        Commands.permission("flowcity.hype.job", "Foloseste joburile", PermissionDefault.TRUE);
        Commands.permission("flowcity.hype.admin", "Administrare Hype", PermissionDefault.OP);

        Commands.register(plugin, "atm", "flowcity.hype.atm", (s, c, l, a) -> playerOnly(s, p -> atmCommand(p, a)), null);
        Commands.register(plugin, "job", "flowcity.hype.job", (s, c, l, a) -> playerOnly(s, p -> jobCommand(p, a)),
            (s, c, l, a) -> a.length == 1 ? Commands.filter(java.util.Arrays.asList("select", "leave", "list"), a[0])
                : a.length == 2 && "select".equalsIgnoreCase(a[0]) ? Commands.filter(new ArrayList<>(jobs.keySet()), a[1])
                : new ArrayList<String>(), "joburi");
        Commands.register(plugin, "sete", null, (s, c, l, a) -> playerOnly(s, p -> {
            PlayerData d = dataOf(p);
            if (d == null) return;
            p.sendMessage("§bSete: §f" + Math.round(d.thirst) + "§7/100");
        }), null, "thirst");
        Commands.register(plugin, "hud", null, (s, c, l, a) -> playerOnly(s, p -> {
            if (plugin.channel().hasMod(p)) {
                JsonObject o = new JsonObject();
                if (a.length > 0 && a[0].equalsIgnoreCase("fundal")) o.addProperty("mode", "bg");
                else if (a.length > 0 && a[0].equalsIgnoreCase("craciun")) o.addProperty("mode", "xmas");
                send(p, "hud_toggle", o);
            } else p.sendMessage("§cHUD-ul necesita modul FlowCity instalat pe client.");
        }), (s, c, l, a) -> a.length == 1 ? Commands.filter(java.util.Arrays.asList("fundal", "craciun"), a[0]) : java.util.Collections.<String>emptyList());
        Commands.register(plugin, "hype", "flowcity.hype.admin", (s, c, l, a) -> {
            hypeAdmin(s, a);
            return true;
        }, (s, c, l, a) -> a.length == 1 ? Commands.filter(java.util.Arrays.asList("atm", "reload"), a[0])
            : a.length == 2 && "atm".equalsIgnoreCase(a[0]) ? Commands.filter(java.util.Arrays.asList("set", "remove", "list"), a[1])
            : new ArrayList<String>());
    }

    private interface PlayerCmd {
        void run(Player p);
    }

    private boolean playerOnly(CommandSender s, PlayerCmd cmd) {
        if (!(s instanceof Player)) {
            s.sendMessage("Doar jucatorii pot folosi aceasta comanda.");
            return true;
        }
        cmd.run((Player) s);
        return true;
    }

    private void hypeAdmin(CommandSender s, String[] a) {
        if (a.length >= 1 && "reload".equalsIgnoreCase(a[0])) {
            cfg = ModConfig.load(plugin, "hype");
            loadJobs();
            atm.reload(cfg);
            s.sendMessage("§aHype reincarcat.");
            return;
        }
        if (a.length >= 2 && "atm".equalsIgnoreCase(a[0])) {
            String sub = a[1].toLowerCase(Locale.ROOT);
            if ("list".equals(sub)) {
                s.sendMessage("§bATM-uri inregistrate: §f" + atm.atmCount());
                return;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatorii pot folosi aceasta comanda.");
                return;
            }
            Player p = (Player) s;
            Block b = p.getTargetBlockExact(6);
            if (b == null) {
                p.sendMessage("§cPriveste un bloc (max 6 blocuri).");
                return;
            }
            if ("set".equals(sub)) {
                p.sendMessage(atm.setAtmBlock(b.getLocation(), true) ? "§aATM adaugat pe " + b.getType().name().toLowerCase(Locale.ROOT) + "." : "§eBlocul e deja un ATM.");
            } else if ("remove".equals(sub)) {
                p.sendMessage(atm.setAtmBlock(b.getLocation(), false) ? "§aATM sters." : "§eBlocul nu e un ATM.");
            }
            return;
        }
        s.sendMessage("§7/hype atm <set|remove|list> | /hype reload");
    }
}
