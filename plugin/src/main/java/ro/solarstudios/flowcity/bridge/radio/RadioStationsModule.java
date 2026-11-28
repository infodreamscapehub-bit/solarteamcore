package ro.solarstudios.flowcity.bridge.radio;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.items.RadioHandler;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;
import ro.solarstudios.flowcity.bridge.radio.Stations.Result;
import ro.solarstudios.flowcity.bridge.radio.Stations.Station;
import ro.solarstudios.flowcity.bridge.radio.Stations.Type;

/**
 * Statii radio personale. Logica sta in {@link Stations} (testata fara Bukkit); aici sunt reteaua cu clientul, persistenta,
 * accesul la statiile oficiale, radioul text si legatura cu Plasmo Voice ({@link VoiceLink}). Pasii care ating Plasmo Voice sunt
 * izolati in clasele din pachetul {@code pv}, incarcate doar daca Plasmo Voice exista.
 */
public final class RadioStationsModule extends JsonModule implements Listener {
    public static RadioStationsModule instance;
    private static final String ADMIN_PERM = "flowcity.radio.admin";

    private Stations st;
    private YamlConfiguration cfg;
    private File dataFile;
    private final VoiceLink link = new VoiceLink();
    private VoiceBackend backend;
    private long snapVersion;
    private final Set<UUID> talking = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastSearch = new HashMap<UUID, String>();

    @Override
    public String id() {
        return "radio";
    }

    @Override
    protected void onEnable() {
        instance = this;
        cfg = ModConfig.load(plugin, "radio");
        st = new Stations(readConfig());
        dataFile = new File(plugin.getDataFolder(), "radio-stations.json");
        if (dataFile.exists()) {
            try {
                st.loadJson(new String(Files.readAllBytes(dataFile.toPath()), StandardCharsets.UTF_8));
            } catch (Exception e) {
                plugin.getLogger().warning("radio-stations.json nu s-a putut citi: " + e);
            }
        }
        st.setAccess((u, s) -> {
            Player p = Bukkit.getPlayer(u);
            return p != null && officialAllowed(p, s.officialKey);
        });
        registerOfficial();
        Commands.permission(ADMIN_PERM, "Radio: administreaza statiile personale", PermissionDefault.OP);
        Commands.permission("flowcity.radio.official.*", "Radio: acces la toate frecventele oficiale", PermissionDefault.OP);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        on("refresh", (p, j) -> pushState(p));
        on("search", (p, j) -> {
            lastSearch.put(p.getUniqueId(), str(j, "q", ""));
            pushState(p);
        });
        on("create", (p, j) -> run(p, create(p, j)));
        on("join", (p, j) -> run(p, st.join(p.getUniqueId(), (long) num(j, "id", -1), str(j, "pass", ""), now())));
        on("joinfreq", (p, j) -> run(p, joinFreq(p, j)));
        on("tune", (p, j) -> run(p, tune(p, (long) num(j, "id", -1))));
        on("power", (p, j) -> {
            RadioHandler rh = RadioHandler.instance;
            boolean want = bool(j, "on", true);
            run(p, rh != null && rh.setPower(p, want) ? Result.ok(want ? "Radio pornit." : "Radio oprit.", null) : Result.err("Nu ai radio la tine."));
        });
        on("off", (p, j) -> run(p, st.disconnect(p.getUniqueId())));
        on("leave", (p, j) -> run(p, st.leave(p.getUniqueId(), (long) num(j, "id", -1), now())));
        on("invite", (p, j) -> run(p, invite(p, j)));
        on("accept", (p, j) -> run(p, st.acceptInvite(p.getUniqueId(), (long) num(j, "id", -1), now())));
        on("decline", (p, j) -> run(p, st.declineInvite(p.getUniqueId(), (long) num(j, "id", -1))));
        on("kick", (p, j) -> run(p, withTarget(p, j, (t, id) -> st.kick(p.getUniqueId(), t, id, now()))));
        on("promote", (p, j) -> run(p, withTarget(p, j, (t, id) -> st.promote(p.getUniqueId(), t, id))));
        on("demote", (p, j) -> run(p, withTarget(p, j, (t, id) -> st.demote(p.getUniqueId(), t, id))));
        on("transfer", (p, j) -> run(p, withTarget(p, j, (t, id) -> st.transfer(p.getUniqueId(), t, id, now()))));
        on("rename", (p, j) -> run(p, st.rename(p.getUniqueId(), (long) num(j, "id", -1), str(j, "name", ""))));
        on("setfreq", (p, j) -> run(p, setFreq(p, j)));
        on("setpass", (p, j) -> run(p, st.setPassword(p.getUniqueId(), (long) num(j, "id", -1), str(j, "pass", ""))));
        on("settype", (p, j) -> run(p, setType(p, j)));
        on("setmax", (p, j) -> run(p, st.setMax(p.getUniqueId(), (long) num(j, "id", -1), integer(j, "max", 0))));
        on("close", (p, j) -> run(p, st.close(p.getUniqueId(), (long) num(j, "id", -1), now(), false)));
        on("admin.close", (p, j) -> {
            if (!p.hasPermission(ADMIN_PERM)) return;
            Station s = st.get((long) num(j, "id", -1));
            if (s == null || s.official) return;
            run(p, st.close(s.owner, s.id, now(), true));
        });
        on("admin.limit", (p, j) -> {
            if (!p.hasPermission(ADMIN_PERM)) return;
            int v = Math.max(0, Math.min(50, integer(j, "value", st.cfg.maxOwnedPerPlayer)));
            st.cfg.maxOwnedPerPlayer = v;
            cfg.set("limits.max-owned-per-player", v);
            ModConfig.save(cfg, plugin, "radio");
            run(p, Result.ok("Limita statii/jucator: " + v, null));
        });

        link.onTalk((u, on) -> Bukkit.getScheduler().runTask(plugin, () -> talkChanged(u, on)));
        startVoice();

        Commands.register(plugin, "radiostatii", null, (s, c, l, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("reload") && s.hasPermission(ADMIN_PERM)) {
                reloadConfig();
                s.sendMessage("§aRadio statii: config reincarcat.");
            } else if (s instanceof Player) {
                Player p = (Player) s;
                s.sendMessage("§7Statii radio: deschide radioul (click dreapta) -> butonul STATII. " + link.status());
                pushState(p);
            }
            return true;
        }, null, "rstatii");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @Override
    public void disable() {
        saveNow();
        if (backend != null) {
            try {
                backend.shutdown();
            } catch (Throwable ignored) {
                // oprire
            }
        }
    }

    // ------------------------------------------------------------------ config / voce

    private Stations.Config readConfig() {
        Stations.Config c = new Stations.Config();
        ConfigurationSection l = cfg.getConfigurationSection("limits");
        if (l == null) return c;
        c.maxOwnedPerPlayer = l.getInt("max-owned-per-player", c.maxOwnedPerPlayer);
        c.freqMinTenths = (int) Math.round(l.getDouble("freq-min", 100.0) * 10);
        c.freqMaxTenths = (int) Math.round(l.getDouble("freq-max", 999.9) * 10);
        c.maxMembersCap = l.getInt("max-members-cap", c.maxMembersCap);
        c.defaultMembers = l.getInt("default-members", c.defaultMembers);
        c.nameMin = l.getInt("name-min", c.nameMin);
        c.nameMax = l.getInt("name-max", c.nameMax);
        c.passMin = l.getInt("password-min", c.passMin);
        c.passMax = l.getInt("password-max", c.passMax);
        c.createCooldownMs = l.getInt("create-cooldown-seconds", 30) * 1000L;
        c.inviteCooldownMs = l.getInt("invite-cooldown-seconds", 3) * 1000L;
        c.inviteTtlMs = l.getInt("invite-ttl-minutes", 10) * 60_000L;
        c.ownerOfflineGraceMs = l.getInt("owner-offline-grace-minutes", 10) * 60_000L;
        c.maxWrongPass = l.getInt("max-wrong-passwords", c.maxWrongPass);
        c.wrongPassLockMs = l.getInt("wrong-password-lock-minutes", 5) * 60_000L;
        return c;
    }

    private void registerOfficial() {
        st.clearOfficial();
        for (Map<?, ?> m : cfg.getMapList("official")) {
            Object key = m.get("key");
            Object name = m.get("name");
            Object freq = m.get("freq");
            if (key == null || freq == null) continue;
            int f = Stations.parseFreq(String.valueOf(freq));
            if (f > 0) st.registerOfficial(String.valueOf(key), name == null ? String.valueOf(key) : String.valueOf(name), f);
        }
    }

    private void reloadConfig() {
        cfg = ModConfig.load(plugin, "radio");
        Stations.Config n = readConfig();
        st.cfg.maxOwnedPerPlayer = n.maxOwnedPerPlayer;
        registerOfficial();
    }

    /** Porneste Plasmo Voice doar daca exista; altfel ramane radio text, cu un motiv clar in status. */
    private void startVoice() {
        if (!cfg.getBoolean("voice.enabled", true)) {
            link.setStatus(false, "Voce: dezactivata in radio.yml (radio text).");
            return;
        }
        try {
            Class.forName("su.plo.voice.api.server.PlasmoVoiceServer");
        } catch (Throwable t) {
            link.setStatus(false, "Voce: Plasmo Voice nu a fost gasit pe server (radio text).");
            plugin.getLogger().info("Radio: Plasmo Voice nu e prezent - statiile merg doar cu radio text.");
            return;
        }
        try {
            backend = (VoiceBackend) Class.forName("ro.solarstudios.flowcity.bridge.radio.pv.PlasmoVoiceBoot").getDeclaredConstructor().newInstance();
            backend.load(link, plugin.getLogger());
        } catch (Throwable t) {
            backend = null;
            link.setStatus(false, "Voce: Plasmo Voice nu s-a putut lega (" + t.getClass().getSimpleName() + "); radio text.");
            plugin.getLogger().warning("Radio: legarea la Plasmo Voice a esuat: " + t);
        }
    }

    /** Deschide ecranul de statii pe client; false daca jucatorul nu are modul (atunci ramane panoul din chat). */
    public boolean openFor(Player p) {
        if (!plugin.channel().hasMod(p)) return false;
        send(p, "open", new JsonObject());
        pushState(p);
        return true;
    }

    public String voiceStatus() {
        return link.status();
    }

    // ------------------------------------------------------------------ acces

    private boolean officialAllowed(Player p, String key) {
        if (p.hasPermission("flowcity.radio.official.*") || p.hasPermission("flowcity.radio.official." + key)) return true;
        RadioHandler rh = RadioHandler.instance;
        return rh != null && rh.channelOk(p, key);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private boolean hasRadio(Player p) {
        RadioHandler rh = RadioHandler.instance;
        return rh != null && rh.hasRadio(p);
    }

    private boolean radioReady(Player p) {
        RadioHandler rh = RadioHandler.instance;
        return rh != null && rh.radioOn(p);
    }

    // ------------------------------------------------------------------ operatii

    private interface Op {
        Result go(UUID target, long id);
    }

    private Result withTarget(Player p, JsonObject j, Op op) {
        Player t = Bukkit.getPlayerExact(str(j, "player", ""));
        if (t == null) return Result.err("Jucatorul nu e online.");
        return op.go(t.getUniqueId(), (long) num(j, "id", -1));
    }

    private Result create(Player p, JsonObject j) {
        if (!hasRadio(p)) return Result.err("Ai nevoie de un radio la tine.");
        Type t = Type.parse(str(j, "type", "PUBLIC"));
        if (t == null || t == Type.DEPARTMENT) return Result.err("Tip de statie invalid.");
        int f = Stations.parseFreq(str(j, "freq", ""));
        return st.create(p.getUniqueId(), str(j, "name", ""), f, t, str(j, "pass", ""), integer(j, "max", st.cfg.defaultMembers), now());
    }

    private Result joinFreq(Player p, JsonObject j) {
        return st.joinByFreq(p.getUniqueId(), Stations.parseFreq(str(j, "freq", "")), str(j, "pass", ""), now());
    }

    private Result tune(Player p, long id) {
        if (!hasRadio(p)) return Result.err("Ai nevoie de un radio la tine.");
        return st.tune(p.getUniqueId(), id, now());
    }

    private Result invite(Player p, JsonObject j) {
        Player t = Bukkit.getPlayerExact(str(j, "player", ""));
        if (t == null || t.equals(p)) return Result.err("Jucator invalid sau offline.");
        return st.invite(p.getUniqueId(), t.getUniqueId(), (long) num(j, "id", -1), now());
    }

    private Result setFreq(Player p, JsonObject j) {
        return st.setFreq(p.getUniqueId(), (long) num(j, "id", -1), Stations.parseFreq(str(j, "freq", "")));
    }

    private Result setType(Player p, JsonObject j) {
        Type t = Type.parse(str(j, "type", ""));
        if (t == null || t == Type.DEPARTMENT) return Result.err("Tip invalid.");
        return st.setType(p.getUniqueId(), (long) num(j, "id", -1), t, str(j, "pass", ""));
    }

    /** Raspunsul unei operatii: mesaj catre jucator, evenimente catre ceilalti, stare noua. */
    private void run(Player p, Result r) {
        JsonObject m = new JsonObject();
        m.addProperty("ok", r.ok);
        m.addProperty("text", r.msg);
        send(p, "result", m);
        afterChange();
        pushState(p);
    }

    private void afterChange() {
        Set<UUID> refresh = new HashSet<UUID>();
        for (Stations.Event e : st.drainEvents()) {
            Station s = st.get(e.station);
            String sn = s == null ? "statia" : s.name;
            if (e.who != null) refresh.add(e.who);
            Player w = e.who == null ? null : Bukkit.getPlayer(e.who);
            if (s != null) {
                for (UUID u : s.members) refresh.add(u);
            }
            if (w == null) continue;
            NotifyModule n = plugin.notifications();
            if ("invited".equals(e.kind) && n != null) {
                n.send(w, NotifyModule.Type.INFO, "Invitatie radio", "Esti invitat pe statia " + sn + " (" + (s == null ? "" : Stations.fmtFreq(s.freq)) + "). Deschide radioul > Invitatii.");
            } else if ("kicked".equals(e.kind)) {
                w.sendMessage("§cAi fost scos de pe statia radio " + sn + ".");
            } else if ("closed".equals(e.kind)) {
                w.sendMessage("§eStatia radio " + sn + " a fost inchisa.");
            } else if ("role".equals(e.kind)) {
                w.sendMessage("§bRolul tau pe statia " + sn + " s-a schimbat.");
            } else if ("owner".equals(e.kind)) {
                w.sendMessage("§bEsti noul owner al statiei " + sn + ".");
            }
        }
        for (UUID u : refresh) {
            Player q = Bukkit.getPlayer(u);
            if (q != null) pushState(q);
        }
        publishVoice();
        if (st.dirty()) saveSoon();
    }

    private boolean saveQueued;

    private void saveSoon() {
        if (saveQueued) return;
        saveQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveQueued = false;
            saveNow();
        }, 100L);
    }

    private void saveNow() {
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(dataFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), st.toJson().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), dataFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            st.clearDirty();
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva radio-stations.json: " + e);
        }
    }

    // ------------------------------------------------------------------ tick / evenimente Bukkit

    private void tick() {
        st.tick(now(), u -> Bukkit.getPlayer(u) != null);
        afterChange();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        st.ownerOnline(e.getPlayer().getUniqueId());
    }

    @Override
    public void onQuit(Player p) {
        UUID u = p.getUniqueId();
        st.ownerOffline(u, now());
        st.playerOffline(u);
        talking.remove(u);
        lastSearch.remove(u);
        afterChange();
    }

    @Override
    public void onClientReady(Player p) {
        pushState(p);
    }

    // ------------------------------------------------------------------ voce / text

    private void talkChanged(UUID u, boolean on) {
        Player p = Bukkit.getPlayer(u);
        Station s = st.activeOf(u);
        if (on && (p == null || s == null || !radioReady(p))) return;
        if (on) talking.add(u);
        else talking.remove(u);
        if (s != null) pushTalk(s);
    }

    private void pushTalk(Station s) {
        JsonObject m = new JsonObject();
        m.addProperty("station", s.id);
        m.add("talking", names(talkingIn(s)));
        for (Player q : tunedPlayers(s)) send(q, "talk", m);
    }

    private List<UUID> talkingIn(Station s) {
        List<UUID> out = new ArrayList<UUID>();
        for (UUID u : talking) {
            Station a = st.activeOf(u);
            if (a != null && a.id == s.id) out.add(u);
        }
        return out;
    }

    private List<Player> tunedPlayers(Station s) {
        List<Player> out = new ArrayList<Player>();
        for (Player q : Bukkit.getOnlinePlayers()) {
            Station a = st.activeOf(q.getUniqueId());
            if (a != null && a.id == s.id) out.add(q);
        }
        return out;
    }

    /** Instantaneul pentru Plasmo Voice: doar cei cu radio pornit la ei si acordati pe o statie. */
    private void publishVoice() {
        Map<UUID, Long> speakers = new HashMap<UUID, Long>();
        Map<Long, List<UUID>> listeners = new HashMap<Long, List<UUID>>();
        for (Player q : Bukkit.getOnlinePlayers()) {
            Station s = st.activeOf(q.getUniqueId());
            if (s == null || !radioReady(q)) continue;
            speakers.put(q.getUniqueId(), s.id);
            List<UUID> l = listeners.get(s.id);
            if (l == null) {
                l = new ArrayList<UUID>();
                listeners.put(s.id, l);
            }
            l.add(q.getUniqueId());
        }
        VoiceLink.Snapshot old = link.snapshot();
        if (old.speakerStation.equals(speakers) && old.listeners.equals(listeners)) return;
        link.publish(new VoiceLink.Snapshot(speakers, listeners, ++snapVersion));
    }

    /** /radio &lt;mesaj&gt; pe statia activa. Intoarce false daca jucatorul nu e pe nicio statie (se foloseste canalul clasic). */
    public boolean textSend(Player p, String msg) {
        Station s = st.activeOf(p.getUniqueId());
        if (s == null) return false;
        String line = "§8[§bRadio §7" + s.name + " §8" + Stations.fmtFreq(s.freq) + "] §f" + p.getName() + "§7: §f" + msg;
        int heard = 0;
        for (Player q : tunedPlayers(s)) {
            if (!radioReady(q)) continue;
            q.sendMessage(line);
            if (!q.equals(p)) heard++;
        }
        if (heard == 0) p.sendMessage("§7(Nimeni altcineva nu te aude pe aceasta statie.)");
        return true;
    }

    // ------------------------------------------------------------------ stare catre client

    private static JsonArray names(List<UUID> ids) {
        JsonArray a = new JsonArray();
        for (UUID u : ids) a.add(nameOf(u));
        return a;
    }

    private static String nameOf(UUID u) {
        if (u == null) return "-";
        Player p = Bukkit.getPlayer(u);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(u).getName();
        return n == null ? u.toString().substring(0, 8) : n;
    }

    private JsonObject stationJson(Station s, UUID viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id);
        o.addProperty("name", s.name);
        o.addProperty("freq", Stations.fmtFreq(s.freq));
        o.addProperty("type", s.type.name());
        o.addProperty("official", s.official);
        o.addProperty("locked", s.locked());
        o.addProperty("members", s.members.size());
        o.addProperty("max", s.maxMembers);
        o.addProperty("owner", nameOf(s.owner));
        Stations.Role r = s.roleOf(viewer);
        o.addProperty("role", r == null ? "" : r.name());
        return o;
    }

    private void pushState(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID u = p.getUniqueId();
        long t = now();
        JsonObject o = new JsonObject();
        o.addProperty("voice", link.voiceActive());
        o.addProperty("voiceText", link.status());
        o.addProperty("radio", hasRadio(p));
        o.addProperty("power", radioReady(p));
        o.addProperty("admin", p.hasPermission(ADMIN_PERM));
        o.addProperty("maxOwned", st.cfg.maxOwnedPerPlayer);
        o.addProperty("owned", st.ownedCount(u));
        Station act = st.activeOf(u);
        if (act != null) {
            JsonObject a = stationJson(act, u);
            JsonArray mem = new JsonArray();
            List<UUID> tuned = new ArrayList<UUID>();
            for (Player q : tunedPlayers(act)) tuned.add(q.getUniqueId());
            if (!act.official) {
                for (UUID m : act.members) {
                    JsonObject mo = new JsonObject();
                    mo.addProperty("name", nameOf(m));
                    Stations.Role r = act.roleOf(m);
                    mo.addProperty("role", r == null ? "MEMBER" : r.name());
                    mo.addProperty("online", Bukkit.getPlayer(m) != null);
                    mo.addProperty("tuned", tuned.contains(m));
                    mem.add(mo);
                }
            }
            a.add("memberList", mem);
            a.addProperty("tunedCount", tuned.size());
            a.add("talking", names(talkingIn(act)));
            o.add("active", a);
            a.add("tuned", names(tuned));
        }
        JsonArray mine = new JsonArray();
        for (Station s : st.joined(u)) mine.add(stationJson(s, u));
        o.add("mine", mine);
        JsonArray off = new JsonArray();
        for (Station s : st.all()) {
            if (s.official && officialAllowed(p, s.officialKey)) off.add(stationJson(s, u));
        }
        o.add("official", off);
        JsonArray pub = new JsonArray();
        for (Station s : st.publicList(lastSearch.getOrDefault(u, ""), 40)) pub.add(stationJson(s, u));
        o.add("public", pub);
        JsonArray inv = new JsonArray();
        for (Station s : st.invitesFor(u, t)) {
            JsonObject io = stationJson(s, u);
            io.addProperty("from", nameOf(st.inviterOf(s, u)));
            inv.add(io);
        }
        o.add("invites", inv);
        JsonArray his = new JsonArray();
        for (Stations.HistoryEntry h : st.historyOf(u)) {
            JsonObject ho = new JsonObject();
            ho.addProperty("ts", h.ts);
            ho.addProperty("action", h.action);
            ho.addProperty("station", h.station);
            ho.addProperty("freq", Stations.fmtFreq(h.freq));
            his.add(ho);
        }
        o.add("history", his);
        if (p.hasPermission(ADMIN_PERM)) {
            JsonArray all = new JsonArray();
            List<Station> list = st.all();
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
            for (Station s : list) if (!s.official && all.size() < 80) all.add(stationJson(s, u));
            o.add("all", all);
        }
        send(p, "state", o);
    }
}
