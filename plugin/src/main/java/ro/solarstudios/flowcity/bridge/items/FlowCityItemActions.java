package ro.solarstudios.flowcity.bridge.items;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.fjobs.FJobsModule;
import ro.solarstudios.flowcity.bridge.fjobs.act.Items;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Framework comun pentru itemele FlowCity cu functie reala. Itemele sunt emise doar de server (serial unic), iar fiecare utilizare
 * e validata pe server: serial valid si neduplicat, permisiune sau job (in tura), pauza, distanta, activitate cu durata care se
 * anuleaza daca te misti / schimbi itemul / iesi, apoi rezultatul e salvat si scris in jurnalul persistent.
 * Modulul din client afiseaza doar bara de progres, panourile si sunetele; nimic nu se decide acolo.
 */
public final class FlowCityItemActions implements Listener {
    public static FlowCityItemActions instance;

    /** Un item cu comportament. Metodele sunt apelate doar dupa ce serialul, permisiunea si pauza au fost verificate. */
    public interface Handler {
        String id();

        /** Pentru iteme care exista deja fara serial (ex. aparatul de taxat din FJobs): se recunosc dupa material. */
        default String legacyMaterial() {
            return null;
        }

        /** Click dreapta in aer sau pe bloc fara alta tinta. */
        default boolean onUse(Ctx c, Block block) {
            return false;
        }

        default boolean onUsePlayer(Ctx c, Player target) {
            return false;
        }

        default boolean onUseEntity(Ctx c, Entity target) {
            return false;
        }

        /** Buton apasat intr-un panou deschis de acest handler (deja validat ca exista in panou). */
        default void onPanel(Ctx c, String panelId, String button) {}

        default void enable(FlowCityItemActions fx) {}

        default void disable() {}

        default void onQuit(Player p) {}
    }

    /** Contextul unei utilizari. */
    public final class Ctx {
        public final Player player;
        public final ItemStack item;
        public final String id;
        public final String serial;

        Ctx(Player player, ItemStack item, String id, String serial) {
            this.player = player;
            this.item = item;
            this.id = id;
            this.serial = serial;
        }

        public FlowCityItemActions fx() {
            return FlowCityItemActions.this;
        }

        public boolean sneaking() {
            return player.isSneaking();
        }

        public ConfigurationSection sec() {
            return section(id);
        }

        public double dbl(String key, double def) {
            return sec() == null ? def : sec().getDouble(key, def);
        }

        public int num(String key, int def) {
            return sec() == null ? def : sec().getInt(key, def);
        }

        public String str(String key, String def) {
            return sec() == null ? def : sec().getString(key, def);
        }

        public boolean flag(String key, boolean def) {
            return sec() == null ? def : sec().getBoolean(key, def);
        }

        /** Pauza intre utilizari ale aceleiasi actiuni (pe jucator). */
        public boolean ready(String action, long ms) {
            if (cooldowns.tryUse(player.getUniqueId() + ":" + id + ":" + action, ms)) return true;
            err("Asteapta putin (" + Math.max(1, (cooldowns.remaining(player.getUniqueId() + ":" + id + ":" + action) + 999) / 1000) + "s).");
            return false;
        }

        public void ok(String msg) {
            say("§a" + msg);
            snd(player, "minecraft:entity.experience_orb.pickup", 0.6f, 1.2f);
        }

        public void err(String msg) {
            say("§c" + msg);
            snd(player, "minecraft:block.note_block.bass", 0.8f, 0.7f);
        }

        public void info(String msg) {
            say("§e" + msg);
        }

        private void say(String msg) {
            player.sendMessage(msg);
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(msg));
        }

        public void log(String action, Player target, String detail) {
            FlowCityItemActions.this.log(player, id, action, target == null ? null : target.getUniqueId(), target == null ? null : target.getName(), detail);
        }

        /** Porneste o activitate cu durata. {@code targetCheck} intoarce motivul anularii (sau null). Executa {@code done} la final. */
        public boolean start(String label, long ms, Supplier<String> targetCheck, final Runnable done) {
            final Player p = player;
            boolean ok = activities.start(new BukkitActor(p), id + ":" + label, System.currentTimeMillis(), ms,
                sec() == null ? 0.7 : sec().getDouble("move-tolerance", 0.7), targetCheck, new Runnable() {
                    @Override
                    public void run() {
                        fx.progressEnd(p);
                        done.run();
                    }
                }, why -> {
                    fx.progressEnd(p);
                    p.sendMessage("§cAnulat: " + why + ".");
                    snd(p, "minecraft:block.note_block.bass", 0.8f, 0.7f);
                    FlowCityItemActions.this.log(p, id, "CANCEL", null, null, label + ": " + why);
                });
            if (!ok) {
                err("Ai deja o actiune in desfasurare.");
                return false;
            }
            fx.progress(p, label, ms);
            snd(p, "minecraft:block.note_block.hat", 0.7f, 1.4f);
            return true;
        }

        /** Consuma o utilizare a itemului din mana (sau din inventar). Intoarce false daca nu mai are utilizari. */
        public boolean charge() {
            return FlowCityItemActions.this.charge(player, id, serial);
        }

        /** Distanta maxima de folosire pe alt jucator/vehicul. */
        public double range() {
            return dbl("range", 3.0);
        }

        public boolean near(Entity e) {
            return e != null && e.isValid() && Rules.within(player.getWorld().getName(), player.getLocation().getX(), player.getLocation().getY(),
                player.getLocation().getZ(), e.getWorld().getName(), e.getLocation().getX(), e.getLocation().getY(), e.getLocation().getZ(), range());
        }
    }

    /** Adaptor Bukkit -> {@link Activities.Actor}. */
    public static final class BukkitActor implements Activities.Actor {
        private final Player p;

        public BukkitActor(Player p) {
            this.p = p;
        }

        public UUID id() { return p.getUniqueId(); }
        public boolean online() { return p.isOnline(); }
        public boolean alive() { return !p.isDead(); }
        public String world() { return p.getWorld().getName(); }
        public double x() { return p.getLocation().getX(); }
        public double y() { return p.getLocation().getY(); }
        public double z() { return p.getLocation().getZ(); }
        public String heldSerial() { return ItemKit.serialOf(p.getInventory().getItemInMainHand()); }
    }

    private static final class PanelState {
        final Panel panel;
        final String handler;
        final String serial;

        PanelState(Panel panel, String handler, String serial) {
            this.panel = panel;
            this.handler = handler;
            this.serial = serial;
        }
    }

    public final FlowCityBridge plugin;
    public final Serials serials;
    public final Cooldowns cooldowns = new Cooldowns();
    public final Activities activities = new Activities();
    public final ItemLog log;
    public final ItemDb idb;
    public final Cases cases;
    public final ItemsModule fx;
    public YamlConfiguration cfg;
    private final Map<String, Handler> handlers = new LinkedHashMap<String, Handler>();
    private final Map<String, Handler> byMaterial = new HashMap<String, Handler>();
    private final Map<UUID, PanelState> panels = new HashMap<UUID, PanelState>();
    /** Cine e dus de cine (catuse sau transport medical): tinta -> persoana care o escorteaza. */
    private final Map<UUID, UUID> escorts = new HashMap<UUID, UUID>();
    private BodycamHandler bodycam;

    public FlowCityItemActions(FlowCityBridge plugin, ItemsModule fx) {
        this.plugin = plugin;
        this.fx = fx;
        instance = this;
        ItemKit.init(plugin);
        this.cfg = ModConfig.load(plugin, "items");
        this.serials = new Serials(new File(plugin.getDataFolder(), "items/serials.tsv"));
        this.log = new ItemLog(plugin.db(), cfg.getInt("log-retention-days", 60));
        this.idb = new ItemDb(plugin);
        this.cases = new Cases(idb);
    }

    public void register(Handler h) {
        handlers.put(h.id(), h);
        if (h.legacyMaterial() != null) byMaterial.put(h.legacyMaterial().toUpperCase(Locale.ROOT), h);
        if (h instanceof BodycamHandler) bodycam = (BodycamHandler) h;
    }

    public Handler handler(String id) {
        return handlers.get(id);
    }

    public List<String> ids() {
        return new ArrayList<String>(handlers.keySet());
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Commands.permission("flowcity.items.admin", "Administreaza itemele FlowCity (emite, revoca, elibereaza, revive)", PermissionDefault.OP);
        for (Handler h : new ArrayList<Handler>(handlers.values())) {
            try {
                h.enable(this);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Item " + h.id() + " nu a pornit: " + ex);
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                activities.tick(u -> {
                    Player p = Bukkit.getPlayer(u);
                    return p == null ? null : new BukkitActor(p);
                }, System.currentTimeMillis());
                pullEscorts();
            }
        }, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                scanDuplicates();
            }
        }, 1200L, 1200L);
    }

    public void disable() {
        for (Handler h : handlers.values()) {
            try {
                h.disable();
            } catch (RuntimeException ignored) {
                // la oprire
            }
        }
        serials.save();
    }

    public void reload() {
        cfg = ModConfig.load(plugin, "items");
    }

    // ------------------------------------------------------------------ config

    public ConfigurationSection section(String id) {
        return cfg.getConfigurationSection("items." + id);
    }

    public String name(String id) {
        ConfigurationSection s = section(id);
        return s == null ? id : s.getString("name", id);
    }

    public long seconds(String id, String key, double defSeconds) {
        ConfigurationSection s = section(id);
        return Math.round((s == null ? defSeconds : s.getDouble(key, defSeconds)) * 1000.0);
    }

    // ------------------------------------------------------------------ emitere

    /** Creeaza un item nou, cu serial emis acum. Intoarce null daca id-ul nu exista. */
    public ItemStack create(String id, UUID owner) {
        return create(id, owner, 1);
    }

    public ItemStack create(String id, UUID owner, int amount) {
        if (id.startsWith("_") || !handlers.containsKey(id)) return null;
        ConfigurationSection s = section(id);
        int uses = s == null ? -1 : s.getInt("uses", -1);
        String serial = serials.issue(id, owner, uses, System.currentTimeMillis());
        List<String> lore = new ArrayList<String>();
        if (s != null) for (String l : s.getStringList("lore")) lore.add(l.replace('&', '§'));
        lore.add("§8Serial: " + serial);
        ItemStack it = ItemKit.make(id, name(id).replace('&', '§'), lore, serial, amount);
        refreshLore(it, id, serial);
        return it;
    }

    /** Rescrie linia cu utilizarile ramase. */
    public void refreshLore(ItemStack it, String id, String serial) {
        Serials.Entry e = serials.get(serial);
        ConfigurationSection s = section(id);
        List<String> lore = new ArrayList<String>();
        if (s != null) for (String l : s.getStringList("lore")) lore.add(l.replace('&', '§'));
        if (e != null && e.uses >= 0) lore.add("§7Utilizari ramase: §f" + e.uses);
        lore.add("§8Serial: " + serial);
        ItemKit.setLore(it, lore);
    }

    public boolean give(Player p, String id) {
        ItemStack it = create(id, p.getUniqueId());
        if (it == null) return false;
        Items.give(p, it);
        log(p, id, "ISSUE", p.getUniqueId(), p.getName(), "serial " + ItemKit.serialOf(it));
        return true;
    }

    // ------------------------------------------------------------------ verificari

    /** Poate jucatorul folosi acest item (permisiune sau job in tura)? Fara nicio cerinta in config = oricine. */
    public boolean allowed(Player p, String id) {
        ConfigurationSection s = section(id);
        if (s == null) return true;
        List<String> perms = s.getStringList("permissions");
        List<String> jobs = s.getStringList("jobs");
        if (perms.isEmpty() && jobs.isEmpty()) return true;
        if (Rules.hasAny(p::hasPermission, perms)) return true;
        if (!jobs.isEmpty()) {
            String job = FJobsModule.shiftJob(p);
            for (String j : jobs) if (!job.isEmpty() && j.equalsIgnoreCase(job)) return true;
        }
        return false;
    }

    /** Consuma o utilizare din serial; actualizeaza sau scoate itemul. */
    public boolean charge(Player p, String id, String serial) {
        if (serial == null) return true;
        int left = serials.consume(serial);
        if (left == -2) return false;
        ItemStack held = findBySerial(p, serial);
        if (held != null) {
            if (left == 0) {
                held.setAmount(0);
                p.sendMessage("§e" + name(id).replace('&', '§') + " §esa terminat si a fost scos din inventar.");
            } else if (left > 0) {
                refreshLore(held, id, serial);
            }
        }
        return true;
    }

    public ItemStack findBySerial(Player p, String serial) {
        for (ItemStack it : p.getInventory().getContents()) if (serial.equals(ItemKit.serialOf(it))) return it;
        return null;
    }

    /** Cauta printre iteme un item de un anumit tip (de ex. proba sau consumabile). */
    public ItemStack findById(Player p, String id) {
        for (ItemStack it : p.getInventory().getContents()) if (id.equals(ItemKit.idOf(it))) return it;
        return null;
    }

    /** Verifica serialul pe toti jucatorii online; la copii sterge totul si revoca. Intoarce true daca s-a gasit duplicat. */
    private boolean duplicated(String serial) {
        int count = 0;
        List<Player> holders = new ArrayList<Player>();
        for (Player q : Bukkit.getOnlinePlayers()) {
            int n = countIn(q.getInventory().getContents(), serial) + countIn(q.getEnderChest().getContents(), serial);
            if (n > 0) {
                count += n;
                holders.add(q);
            }
        }
        if (count <= 1) return false;
        wipeSerial(serial, holders, "duplicat");
        return true;
    }

    private static int countIn(ItemStack[] arr, String serial) {
        int n = 0;
        for (ItemStack it : arr) if (it != null && serial.equals(ItemKit.serialOf(it))) n++;
        return n;
    }

    private void wipeSerial(String serial, List<Player> holders, String why) {
        serials.revoke(serial);
        StringBuilder who = new StringBuilder();
        for (Player q : holders) {
            for (ItemStack it : q.getInventory().getContents()) if (it != null && serial.equals(ItemKit.serialOf(it))) it.setAmount(0);
            for (ItemStack it : q.getEnderChest().getContents()) if (it != null && serial.equals(ItemKit.serialOf(it))) it.setAmount(0);
            q.sendMessage("§cItem " + why + ": a fost confiscat.");
            if (who.length() > 0) who.append(", ");
            who.append(q.getName());
        }
        log(null, "-", "DUPLICATE", null, null, "serial " + serial + " la: " + who);
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.hasPermission("flowcity.items.admin")) plugin.notifications().send(a.getUniqueId(), NotifyModule.Type.WARN, "Item duplicat", "Serial " + serial + " la " + who + " - sters si revocat.");
        }
    }

    private void scanDuplicates() {
        Map<String, List<Player>> seen = new HashMap<String, List<Player>>();
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Player q : Bukkit.getOnlinePlayers()) {
            for (ItemStack[] arr : new ItemStack[][] {q.getInventory().getContents(), q.getEnderChest().getContents()}) {
                for (ItemStack it : arr) {
                    String s = ItemKit.serialOf(it);
                    if (s == null) continue;
                    Integer c = counts.get(s);
                    counts.put(s, c == null ? 1 : c + 1);
                    List<Player> l = seen.get(s);
                    if (l == null) {
                        l = new ArrayList<Player>();
                        seen.put(s, l);
                    }
                    if (!l.contains(q)) l.add(q);
                }
            }
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > 1) wipeSerial(e.getKey(), seen.get(e.getKey()), "duplicat");
    }

    // ------------------------------------------------------------------ jurnal

    public void log(Player actor, String item, String action, UUID target, String targetName, String detail) {
        Location l = actor == null ? null : actor.getLocation();
        log.add(actor == null ? null : actor.getUniqueId(), actor == null ? "sistem" : actor.getName(), item, action, target, targetName, detail,
            l == null ? "" : l.getWorld().getName(), l == null ? 0 : l.getBlockX(), l == null ? 0 : l.getBlockY(), l == null ? 0 : l.getBlockZ());
        if (actor != null && bodycam != null && !"CANCEL".equals(action) && !"DENIED".equals(action) && !"ISSUE".equals(action)) {
            bodycam.autoMark(actor, item + " " + action + (targetName == null ? "" : " -> " + targetName));
        }
    }

    // ------------------------------------------------------------------ panouri

    /** Deschide un panou pentru jucator si retine ce butoane sunt valide. {@code serial} (optional) = itemul care trebuie sa ramana la jucator. */
    public void openPanel(Player p, String handler, Panel panel, String serial) {
        panels.put(p.getUniqueId(), new PanelState(panel, handler, serial));
        if (plugin.channel().hasMod(p)) fx.sendPanel(p, panel);
        else for (String l : panel.toChat()) p.sendMessage(l);
    }

    public void closePanel(Player p) {
        panels.remove(p.getUniqueId());
    }

    /** Buton primit de la client: e acceptat doar daca panoul e deschis, butonul exista in el si itemul e inca la jucator. */
    void panelAction(Player p, String panelId, String button) {
        PanelState st = panels.get(p.getUniqueId());
        if (st == null || !st.panel.id().equals(panelId) || !st.panel.hasButton(button)) return;
        if (st.serial != null && findBySerial(p, st.serial) == null) {
            closePanel(p);
            return;
        }
        Handler h = handlers.get(st.handler);
        if (h == null) return;
        ItemStack held = st.serial == null ? null : findBySerial(p, st.serial);
        Ctx c = new Ctx(p, held, st.handler, st.serial);
        if (st.serial != null && !allowed(p, st.handler)) {
            c.err("Nu mai ai dreptul sa folosesti acest echipament.");
            return;
        }
        try {
            h.onPanel(c, panelId, button);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Panou " + panelId + "/" + button + ": " + ex);
        }
    }

    // ------------------------------------------------------------------ escorta

    /** Un jucator il duce pe altul (catuse sau transport medical). */
    public void setEscort(UUID target, UUID leaderOrNull) {
        if (leaderOrNull == null) escorts.remove(target);
        else escorts.put(target, leaderOrNull);
    }

    public UUID escortOf(UUID target) {
        return escorts.get(target);
    }

    private void pullEscorts() {
        if (escorts.isEmpty()) return;
        List<UUID> drop = new ArrayList<UUID>();
        for (Map.Entry<UUID, UUID> e : escorts.entrySet()) {
            Player t = Bukkit.getPlayer(e.getKey());
            Player l = Bukkit.getPlayer(e.getValue());
            if (t == null || l == null || !t.isOnline() || !l.isOnline() || t.isDead() || l.isDead()) {
                drop.add(e.getKey());
                continue;
            }
            if (!t.getWorld().equals(l.getWorld()) || t.getLocation().distance(l.getLocation()) > 40) {
                t.teleport(l.getLocation());
                continue;
            }
            double d = t.getLocation().distance(l.getLocation());
            if (d > 2.5) {
                org.bukkit.util.Vector v = l.getLocation().toVector().subtract(t.getLocation().toVector()).normalize().multiply(Math.min(0.9, 0.25 + d * 0.06));
                v.setY(Math.max(-0.1, Math.min(0.3, v.getY())));
                t.setVelocity(v);
            }
        }
        for (UUID u : drop) escorts.remove(u);
    }

    // ------------------------------------------------------------------ sunete

    public static void snd(Player p, String sound, float vol, float pitch) {
        try {
            p.playSound(p.getLocation(), sound, vol, pitch);
        } catch (RuntimeException ignored) {
            // sunet necunoscut
        }
    }

    // ------------------------------------------------------------------ evenimente

    private boolean dispatch(Player p, ItemStack it, Block block, Entity target, boolean playerTarget) {
        if (it == null || it.getType() == Material.AIR) return false;
        String id = ItemKit.idOf(it);
        String serial = null;
        Handler h;
        if (id != null) {
            h = handlers.get(id);
            if (h == null) return false;
            serial = ItemKit.serialOf(it);
        } else {
            h = byMaterial.get(it.getType().name());
            if (h == null) return false;
            id = h.id();
        }
        Ctx c = new Ctx(p, it, id, serial);
        if (h.legacyMaterial() == null || serial != null) {
            Serials.Check chk = serials.validate(serial, id);
            if (chk != Serials.Check.OK) {
                c.err(chk == Serials.Check.REVOKED ? "Acest echipament nu mai este valid." : "Echipament neautorizat (neemis de server).");
                log(p, id, chk == Serials.Check.REVOKED ? "REVOKED_USE" : "FORGED_USE", null, null, "serial " + serial);
                return true;
            }
            if (duplicated(serial)) return true;
        }
        if (!allowed(p, id)) {
            c.err("Nu ai permisiunea sau jobul necesar pentru " + ChatStrip.strip(name(id)) + ".");
            log(p, id, "DENIED", target == null ? null : target.getUniqueId(), target == null ? null : target.getName(), "fara permisiune/job");
            return true;
        }
        if (!ItemsModule.sane(p)) return true;
        try {
            if (target != null) {
                if (playerTarget && target instanceof Player) return h.onUsePlayer(c, (Player) target) || true;
                return h.onUseEntity(c, target) || true;
            }
            return h.onUse(c, block) || true;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Item " + id + ": " + ex);
            c.err("Eroare interna la folosirea itemului.");
            return true;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        if (dispatch(e.getPlayer(), it, e.getClickedBlock(), null, false)) {
            if (ItemKit.idOf(it) != null || byMaterial.containsKey(it.getType().name())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack it = e.getPlayer().getInventory().getItemInMainHand();
        if (e.isCancelled() || it == null || it.getType() == Material.AIR) return;
        boolean ours = ItemKit.idOf(it) != null || byMaterial.containsKey(it.getType().name());
        if (!ours) return;
        e.setCancelled(true);
        dispatch(e.getPlayer(), it, null, e.getRightClicked(), e.getRightClicked() instanceof Player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        activities.cancel(p.getUniqueId(), "deconectat");
        panels.remove(p.getUniqueId());
        escorts.remove(p.getUniqueId());
        for (Handler h : handlers.values()) {
            try {
                h.onQuit(p);
            } catch (RuntimeException ignored) {
                // la iesire
            }
        }
    }

    /** Date simple pentru fx: jucatorul tine un item FlowCity. */
    public static JsonObject json(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }
}
