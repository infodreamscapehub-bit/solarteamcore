package ro.solarstudios.flowcity.bridge.mdt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * MDT - terminalul de politie (amenzi, cazier, vehicule, mandate, unitati, backup, panica, ranguri).
 * Se deschide cu tableta MDT (item) sau /mdt (cu tableta in mana). Permisiuni:
 * flowcity.mdt.use (acces), flowcity.mdt.admin (ranguri/oficeri/configurare).
 * Datele sunt tinute in memorie si scrise in SQLite la fiecare schimbare.
 */
public final class MdtModule extends JsonModule implements Listener {
    private static final int SENIOR_RANK = 4;
    private static final int MAX_RANK = 6;
    private static final long BACKUP_COOLDOWN = 10_000L;
    private static final long PANIC_COOLDOWN = 30_000L;
    private static final long BACKUP_TTL = 10 * 60_000L;
    private static final String[] DEFAULT_RANKS = {"Agent", "Agent principal", "Sergent", "Locotenent", "Comisar", "Chestor"};
    private static final String[] UNIT_TYPES = {"RUTIERA", "RAZIE", "PATRULA"};

    // ------------------------------------------------------------------ model

    private static final class Rec {
        long id;
        String text;
        String by;
        UUID byUuid;
        long ts;
    }

    private static final class Fine {
        long id;
        int amount;
        String reason;
        long ts;
        boolean paid;
        String by;
        UUID byUuid;
    }

    private static final class Veh {
        long id;
        String model;
        String plate;
        String status;
    }

    private static final class Prop {
        long id;
        String addr;
    }

    private static final class Citizen {
        UUID uuid;
        String name;
        boolean wanted;
        String reason = "";
        String by = "";
        UUID byUuid;
        final List<Rec> records = new ArrayList<>();
        final List<Fine> fines = new ArrayList<>();
        final List<Veh> vehicles = new ArrayList<>();
        final List<Prop> props = new ArrayList<>();

        int unpaid() {
            int n = 0;
            for (Fine f : fines) if (!f.paid) n++;
            return n;
        }
    }

    private static final class Officer {
        UUID uuid;
        String name;
        int rank;
    }

    private static final class Rank {
        int num;
        String name;
        String up = "";
        String down = "";
    }

    private static final class Unit {
        String id;
        String name;
        String type;
        String status = "Available";
        final List<UUID> members = new ArrayList<>();
    }

    private static final class Backup {
        UUID uuid;
        String name;
        int code;
        double x;
        double y;
        double z;
        long ts;
    }

    private final Map<UUID, Citizen> citizens = new ConcurrentHashMap<>();
    private final Map<UUID, Officer> officers = new ConcurrentHashMap<>();
    private final Map<Integer, Rank> ranks = new LinkedHashMap<>();
    private final Map<String, Unit> units = new LinkedHashMap<>();
    private final Map<UUID, Backup> backups = new LinkedHashMap<>();
    private final Map<UUID, UUID> viewers = new ConcurrentHashMap<>(); // jucator -> cetateanul selectat (sau null marcat cu ZERO)
    private static final UUID NONE = new UUID(0, 0);
    private final Map<UUID, Long> lastBackup = new HashMap<>();
    private final Map<UUID, Long> lastPanic = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private int unitSeq = 1;

    @Override
    public String id() {
        return "mdt";
    }

    // ------------------------------------------------------------------ pornire / DB

    @Override
    protected void onEnable() {
        instance = this;
        try {
            plugin.db().async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_citizen (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, wanted INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', by_name TEXT NOT NULL DEFAULT '', by_uuid TEXT NOT NULL DEFAULT '')");
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_record (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, text TEXT NOT NULL, by_name TEXT NOT NULL, by_uuid TEXT NOT NULL, ts INTEGER NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_fine (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, amount INTEGER NOT NULL, reason TEXT NOT NULL, ts INTEGER NOT NULL, paid INTEGER NOT NULL DEFAULT 0, by_name TEXT NOT NULL, by_uuid TEXT NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_vehicle (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, model TEXT NOT NULL, plate TEXT NOT NULL, status TEXT NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_prop (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, addr TEXT NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_officer (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, rank INTEGER NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS mdt_rank (rank INTEGER PRIMARY KEY, name TEXT NOT NULL, up TEXT NOT NULL DEFAULT '', down TEXT NOT NULL DEFAULT '')");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
            load();
        } catch (Exception e) {
            plugin.getLogger().severe("MDT: nu pot porni baza de date: " + e);
            return;
        }
        for (int i = 1; i <= MAX_RANK; i++) {
            if (!ranks.containsKey(i)) {
                Rank r = new Rank();
                r.num = i;
                r.name = DEFAULT_RANKS[i - 1];
                ranks.put(i, r);
            }
        }
        Commands.permission("flowcity.mdt.use", "Foloseste terminalul MDT de politie", PermissionDefault.OP);
        Commands.permission("flowcity.mdt.admin", "Administreaza MDT (ranguri, oficeri)", PermissionDefault.OP);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        on("open", (p, j) -> open(p));
        on("close", (p, j) -> viewers.remove(p.getUniqueId()));
        on("find", (p, j) -> find(p, str(j, "q", "")));
        on("select", (p, j) -> select(p, uuid(j, "u")));
        on("wanted", (p, j) -> wanted(p, uuid(j, "u"), bool(j, "on", false), str(j, "reason", "")));
        on("record_add", (p, j) -> recordAdd(p, uuid(j, "u"), str(j, "text", "")));
        on("record_del", (p, j) -> recordDel(p, uuid(j, "u"), (long) num(j, "id", -1)));
        on("fine_add", (p, j) -> fineAdd(p, uuid(j, "u"), integer(j, "amount", 0), str(j, "reason", "")));
        on("fine_del", (p, j) -> fineDel(p, uuid(j, "u"), (long) num(j, "id", -1)));
        on("fine_pay", (p, j) -> finePay(p, uuid(j, "u"), (long) num(j, "id", -1), bool(j, "charge", false)));
        on("veh_add", (p, j) -> vehAdd(p, uuid(j, "u"), str(j, "model", ""), str(j, "plate", "")));
        on("veh_status", (p, j) -> vehStatus(p, uuid(j, "u"), (long) num(j, "id", -1)));
        on("veh_del", (p, j) -> vehDel(p, uuid(j, "u"), (long) num(j, "id", -1)));
        on("prop_add", (p, j) -> propAdd(p, uuid(j, "u"), str(j, "addr", "")));
        on("prop_del", (p, j) -> propDel(p, uuid(j, "u"), (long) num(j, "id", -1)));
        on("plate", (p, j) -> plate(p, str(j, "plate", "")));
        on("unit_create", (p, j) -> unitCreate(p, str(j, "name", ""), str(j, "type", "PATRULA")));
        on("unit_join", (p, j) -> unitJoin(p, str(j, "id", "")));
        on("unit_leave", (p, j) -> unitLeave(p, true));
        on("unit_status", (p, j) -> unitStatus(p, str(j, "status", "")));
        on("backup", (p, j) -> backup(p, integer(j, "code", 1)));
        on("panic", (p, j) -> backup(p, 99));
        on("backup_resolve", (p, j) -> backupResolve(p, uuid(j, "id")));
        on("officer_add", (p, j) -> officerAdd(p, str(j, "name", ""), integer(j, "rank", 1)));
        on("officer_set", (p, j) -> officerSet(p, uuid(j, "u"), integer(j, "rank", 1)));
        on("officer_fire", (p, j) -> officerFire(p, uuid(j, "u")));
        on("rank_save", (p, j) -> rankSave(p, integer(j, "rank", 0), str(j, "name", ""), str(j, "up", ""), str(j, "down", "")));
        registerCommands();
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireBackups, 200L, 200L);
        for (Player p : Bukkit.getOnlinePlayers()) register(p);
    }

    private void load() throws Exception {
        plugin.db().async(c -> {
            long max = 0;
            try (Statement st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT uuid,name,wanted,reason,by_name,by_uuid FROM mdt_citizen")) {
                    while (rs.next()) {
                        UUID u = parse(rs.getString(1));
                        if (u == null) continue;
                        Citizen ct = new Citizen();
                        ct.uuid = u;
                        ct.name = rs.getString(2);
                        ct.wanted = rs.getInt(3) != 0;
                        ct.reason = rs.getString(4);
                        ct.by = rs.getString(5);
                        ct.byUuid = parse(rs.getString(6));
                        citizens.put(u, ct);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT id,uuid,text,by_name,by_uuid,ts FROM mdt_record ORDER BY id")) {
                    while (rs.next()) {
                        Citizen ct = citizens.get(parse(rs.getString(2)));
                        max = Math.max(max, rs.getLong(1));
                        if (ct == null) continue;
                        Rec r = new Rec();
                        r.id = rs.getLong(1);
                        r.text = rs.getString(3);
                        r.by = rs.getString(4);
                        r.byUuid = parse(rs.getString(5));
                        r.ts = rs.getLong(6);
                        ct.records.add(r);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT id,uuid,amount,reason,ts,paid,by_name,by_uuid FROM mdt_fine ORDER BY id")) {
                    while (rs.next()) {
                        Citizen ct = citizens.get(parse(rs.getString(2)));
                        max = Math.max(max, rs.getLong(1));
                        if (ct == null) continue;
                        Fine f = new Fine();
                        f.id = rs.getLong(1);
                        f.amount = rs.getInt(3);
                        f.reason = rs.getString(4);
                        f.ts = rs.getLong(5);
                        f.paid = rs.getInt(6) != 0;
                        f.by = rs.getString(7);
                        f.byUuid = parse(rs.getString(8));
                        ct.fines.add(f);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT id,uuid,model,plate,status FROM mdt_vehicle ORDER BY id")) {
                    while (rs.next()) {
                        Citizen ct = citizens.get(parse(rs.getString(2)));
                        max = Math.max(max, rs.getLong(1));
                        if (ct == null) continue;
                        Veh v = new Veh();
                        v.id = rs.getLong(1);
                        v.model = rs.getString(3);
                        v.plate = rs.getString(4);
                        v.status = rs.getString(5);
                        ct.vehicles.add(v);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT id,uuid,addr FROM mdt_prop ORDER BY id")) {
                    while (rs.next()) {
                        Citizen ct = citizens.get(parse(rs.getString(2)));
                        max = Math.max(max, rs.getLong(1));
                        if (ct == null) continue;
                        Prop pr = new Prop();
                        pr.id = rs.getLong(1);
                        pr.addr = rs.getString(3);
                        ct.props.add(pr);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT uuid,name,rank FROM mdt_officer")) {
                    while (rs.next()) {
                        UUID u = parse(rs.getString(1));
                        if (u == null) continue;
                        Officer o = new Officer();
                        o.uuid = u;
                        o.name = rs.getString(2);
                        o.rank = clampRank(rs.getInt(3));
                        officers.put(u, o);
                    }
                }
                try (ResultSet rs = st.executeQuery("SELECT rank,name,up,down FROM mdt_rank")) {
                    while (rs.next()) {
                        Rank r = new Rank();
                        r.num = rs.getInt(1);
                        r.name = rs.getString(2);
                        r.up = rs.getString(3);
                        r.down = rs.getString(4);
                        if (r.num >= 1 && r.num <= MAX_RANK) ranks.put(r.num, r);
                    }
                }
            }
            return null;
        }).get(15, TimeUnit.SECONDS);
        long m = 0;
        for (Citizen ct : citizens.values()) {
            for (Rec r : ct.records) m = Math.max(m, r.id);
            for (Fine f : ct.fines) m = Math.max(m, f.id);
            for (Veh v : ct.vehicles) m = Math.max(m, v.id);
            for (Prop p : ct.props) m = Math.max(m, p.id);
        }
        ids.set(m + 1);
    }

    private void sql(final String statement, final Object... args) {
        plugin.db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement(statement)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private void saveCitizen(Citizen ct) {
        sql("INSERT OR REPLACE INTO mdt_citizen(uuid,name,wanted,reason,by_name,by_uuid) VALUES (?,?,?,?,?,?)",
            ct.uuid.toString(), ct.name, ct.wanted ? 1 : 0, ct.reason == null ? "" : ct.reason, ct.by == null ? "" : ct.by,
            ct.byUuid == null ? "" : ct.byUuid.toString());
    }

    // ------------------------------------------------------------------ utilitare

    private static UUID parse(String s) {
        try {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID uuid(JsonObject j, String key) {
        return parse(str(j, key, ""));
    }

    private static int clampRank(int r) {
        return Math.max(1, Math.min(MAX_RANK, r));
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", " ").replace("§", "").replace("&", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String date(long ts) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(ts));
    }

    private boolean access(Player p) {
        return p.hasPermission("flowcity.mdt.use") || officers.containsKey(p.getUniqueId());
    }

    private boolean admin(Player p) {
        return p.hasPermission("flowcity.mdt.admin");
    }

    private int rankOf(Player p) {
        Officer o = officers.get(p.getUniqueId());
        if (o != null) return o.rank;
        return admin(p) ? MAX_RANK : 1;
    }

    private int rankOf(UUID u) {
        Officer o = officers.get(u);
        return o == null ? 1 : o.rank;
    }

    private String rankName(int r) {
        Rank k = ranks.get(r);
        return k == null || k.name == null || k.name.isEmpty() ? "Grad " + r : k.name;
    }

    private boolean senior(Player p) {
        return admin(p) || rankOf(p) >= SENIOR_RANK;
    }

    private void tell(Player p, String msg, boolean error) {
        if (plugin.channel().hasMod(p)) plugin.channel().toast(p, msg, error);
        else p.sendMessage((error ? "§c[MDT] " : "§a[MDT] ") + msg);
    }

    /** Verifica accesul; altfel anunta jucatorul. */
    private boolean guard(Player p) {
        if (access(p)) return true;
        tell(p, "Nu ai acces la terminalul MDT.", true);
        return false;
    }

    private Citizen citizen(UUID u) {
        return u == null ? null : citizens.get(u);
    }

    private void register(Player p) {
        Citizen ct = citizens.get(p.getUniqueId());
        if (ct == null) {
            ct = new Citizen();
            ct.uuid = p.getUniqueId();
            ct.name = p.getName();
            citizens.put(ct.uuid, ct);
            saveCitizen(ct);
        } else if (!ct.name.equals(p.getName())) {
            ct.name = p.getName();
            saveCitizen(ct);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        register(e.getPlayer());
        Citizen ct = citizens.get(e.getPlayer().getUniqueId());
        if (ct != null && ct.unpaid() > 0) {
            int sum = 0;
            for (Fine f : ct.fines) if (!f.paid) sum += f.amount;
            final String msg = "Ai " + ct.unpaid() + " amenzi neplatite (" + sum + " lei).";
            final Player pl = e.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (pl.isOnline()) pl.sendMessage("§e[Politie] " + msg);
            }, 60L);
        }
    }

    @Override
    public void onQuit(Player p) {
        viewers.remove(p.getUniqueId());
        lastBackup.remove(p.getUniqueId());
        lastPanic.remove(p.getUniqueId());
        boolean changed = unitLeave(p, false);
        if (backups.remove(p.getUniqueId()) != null || changed) pushAll();
    }

    // ------------------------------------------------------------------ construire JSON

    private JsonObject brief(Citizen ct) {
        JsonObject o = new JsonObject();
        o.addProperty("u", ct.uuid.toString());
        o.addProperty("n", ct.name);
        o.addProperty("w", ct.wanted);
        o.addProperty("f", ct.unpaid());
        Player on = Bukkit.getPlayer(ct.uuid);
        o.addProperty("on", on != null);
        return o;
    }

    private JsonObject detail(Citizen ct) {
        JsonObject o = brief(ct);
        o.addProperty("reason", ct.reason == null ? "" : ct.reason);
        o.addProperty("by", ct.by == null ? "" : ct.by);
        JsonArray rec = new JsonArray();
        for (int i = ct.records.size() - 1; i >= 0; i--) {
            Rec r = ct.records.get(i);
            JsonObject x = new JsonObject();
            x.addProperty("id", r.id);
            x.addProperty("t", r.text);
            x.addProperty("by", r.by);
            x.addProperty("d", date(r.ts));
            rec.add(x);
        }
        o.add("records", rec);
        JsonArray fin = new JsonArray();
        for (int i = ct.fines.size() - 1; i >= 0; i--) {
            Fine f = ct.fines.get(i);
            JsonObject x = new JsonObject();
            x.addProperty("id", f.id);
            x.addProperty("a", f.amount);
            x.addProperty("r", f.reason);
            x.addProperty("by", f.by);
            x.addProperty("d", date(f.ts));
            x.addProperty("paid", f.paid);
            fin.add(x);
        }
        o.add("fines", fin);
        JsonArray veh = new JsonArray();
        for (Veh v : ct.vehicles) {
            JsonObject x = new JsonObject();
            x.addProperty("id", v.id);
            x.addProperty("m", v.model);
            x.addProperty("p", v.plate);
            x.addProperty("s", v.status);
            veh.add(x);
        }
        o.add("vehicles", veh);
        JsonArray pr = new JsonArray();
        for (Prop v : ct.props) {
            JsonObject x = new JsonObject();
            x.addProperty("id", v.id);
            x.addProperty("a", v.addr);
            pr.add(x);
        }
        o.add("props", pr);
        return o;
    }

    private JsonObject state(Player p) {
        JsonObject o = new JsonObject();
        JsonObject me = new JsonObject();
        me.addProperty("rank", rankOf(p));
        me.addProperty("rankName", rankName(rankOf(p)));
        me.addProperty("admin", admin(p));
        me.addProperty("senior", senior(p));
        me.addProperty("name", p.getName());
        Unit mine = unitOf(p.getUniqueId());
        me.addProperty("unit", mine == null ? "" : mine.id);
        o.add("me", me);

        JsonArray wanted = new JsonArray();
        List<Citizen> w = new ArrayList<>();
        for (Citizen ct : citizens.values()) if (ct.wanted) w.add(ct);
        Collections.sort(w, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (Citizen ct : w) {
            JsonObject b = brief(ct);
            b.addProperty("reason", ct.reason == null ? "" : ct.reason);
            b.addProperty("by", ct.by == null ? "" : ct.by);
            wanted.add(b);
        }
        o.add("wanted", wanted);

        JsonArray online = new JsonArray();
        int n = 0;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            Citizen ct = citizens.get(pl.getUniqueId());
            if (ct == null) continue;
            online.add(brief(ct));
            if (++n >= 80) break;
        }
        o.add("online", online);

        JsonArray us = new JsonArray();
        for (Unit u : units.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", u.id);
            x.addProperty("name", u.name);
            x.addProperty("type", u.type);
            x.addProperty("status", u.status);
            JsonArray m = new JsonArray();
            for (UUID id : u.members) {
                Player pl = Bukkit.getPlayer(id);
                m.add(pl != null ? pl.getName() : id.toString().substring(0, 6));
            }
            x.add("members", m);
            us.add(x);
        }
        o.add("units", us);

        JsonArray bk = new JsonArray();
        long now = System.currentTimeMillis();
        for (Backup b : backups.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", b.uuid.toString());
            x.addProperty("name", b.name);
            x.addProperty("code", b.code);
            x.addProperty("x", b.x);
            x.addProperty("y", b.y);
            x.addProperty("z", b.z);
            x.addProperty("age", (now - b.ts) / 1000);
            bk.add(x);
        }
        o.add("backups", bk);

        JsonArray of = new JsonArray();
        List<Officer> list = new ArrayList<>(officers.values());
        Collections.sort(list, (a, b) -> b.rank != a.rank ? b.rank - a.rank : a.name.compareToIgnoreCase(b.name));
        for (Officer ofc : list) {
            JsonObject x = new JsonObject();
            x.addProperty("u", ofc.uuid.toString());
            x.addProperty("n", ofc.name);
            x.addProperty("rank", ofc.rank);
            x.addProperty("on", Bukkit.getPlayer(ofc.uuid) != null);
            of.add(x);
        }
        o.add("officers", of);

        JsonArray rk = new JsonArray();
        for (Rank r : ranks.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("r", r.num);
            x.addProperty("name", r.name);
            if (admin(p)) {
                x.addProperty("up", r.up == null ? "" : r.up);
                x.addProperty("down", r.down == null ? "" : r.down);
            }
            rk.add(x);
        }
        o.add("ranks", rk);
        return o;
    }

    // ------------------------------------------------------------------ sincronizare

    private void open(Player p) {
        if (!guard(p)) return;
        register(p);
        viewers.put(p.getUniqueId(), NONE);
        JsonObject s = state(p);
        s.addProperty("open", true);
        send(p, "state", s);
    }

    private void pushAll() {
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || !access(p)) {
                viewers.remove(id);
                continue;
            }
            send(p, "state", state(p));
            pushDetail(p);
        }
    }

    private void pushDetail(Player p) {
        UUID sel = viewers.get(p.getUniqueId());
        if (sel == null || sel.equals(NONE)) return;
        Citizen ct = citizens.get(sel);
        if (ct != null) send(p, "citizen", detail(ct));
    }

    private void select(Player p, UUID u) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        viewers.put(p.getUniqueId(), u);
        send(p, "citizen", detail(ct));
    }

    private void find(Player p, String q) {
        if (!guard(p)) return;
        String needle = clean(q, 32).toLowerCase(Locale.ROOT);
        List<Citizen> hits = new ArrayList<>();
        for (Citizen ct : citizens.values()) {
            if (needle.isEmpty() || ct.name.toLowerCase(Locale.ROOT).contains(needle)) hits.add(ct);
        }
        Collections.sort(hits, (a, b) -> {
            boolean oa = Bukkit.getPlayer(a.uuid) != null;
            boolean ob = Bukkit.getPlayer(b.uuid) != null;
            if (a.wanted != b.wanted) return a.wanted ? -1 : 1;
            if (oa != ob) return oa ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        JsonArray arr = new JsonArray();
        for (int i = 0; i < Math.min(40, hits.size()); i++) arr.add(brief(hits.get(i)));
        JsonObject o = new JsonObject();
        o.addProperty("q", q);
        o.add("items", arr);
        send(p, "found", o);
    }

    // ------------------------------------------------------------------ mandate, cazier

    private void wanted(Player p, UUID u, boolean on, String reasonIn) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        String reason = clean(reasonIn, 160);
        if (on) {
            if (reason.isEmpty()) {
                tell(p, "Scrie motivul urmaririi.", true);
                return;
            }
            ct.wanted = true;
            ct.reason = reason;
            ct.by = p.getName();
            ct.byUuid = p.getUniqueId();
            Player target = Bukkit.getPlayer(ct.uuid);
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (access(o)) o.sendMessage("§c[MDT] §e" + p.getName() + " §fa emis mandat pe numele lui §e" + ct.name + "§f: " + reason);
            }
            if (target != null && !access(target)) target.sendMessage("§c[Politie] Ai fost declarat urmarit: " + reason);
        } else {
            if (ct.wanted && ct.byUuid != null && !admin(p) && rankOf(p) < rankOf(ct.byUuid) && !p.getUniqueId().equals(ct.byUuid)) {
                tell(p, "Mandatul a fost emis de un ofiter cu grad mai mare (" + ct.by + ").", true);
                return;
            }
            ct.wanted = false;
            ct.reason = "";
            ct.by = "";
            ct.byUuid = null;
        }
        saveCitizen(ct);
        tell(p, on ? "Mandat emis pentru " + ct.name + "." : "Mandat anulat pentru " + ct.name + ".", false);
        pushAll();
    }

    private void recordAdd(Player p, UUID u, String textIn) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        String text = clean(textIn, 220);
        if (ct == null || text.isEmpty()) return;
        Rec r = new Rec();
        r.id = ids.getAndIncrement();
        r.text = text;
        r.by = p.getName();
        r.byUuid = p.getUniqueId();
        r.ts = System.currentTimeMillis();
        ct.records.add(r);
        sql("INSERT INTO mdt_record(id,uuid,text,by_name,by_uuid,ts) VALUES (?,?,?,?,?,?)", r.id, ct.uuid.toString(), r.text, r.by, r.byUuid.toString(), r.ts);
        pushAll();
    }

    private void recordDel(Player p, UUID u, long id) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        for (Rec r : ct.records) {
            if (r.id != id) continue;
            if (!senior(p) && !p.getUniqueId().equals(r.byUuid)) {
                tell(p, "Doar cel care a scris fapta sau un grad superior o poate sterge.", true);
                return;
            }
            ct.records.remove(r);
            sql("DELETE FROM mdt_record WHERE id=?", id);
            pushAll();
            return;
        }
    }

    // ------------------------------------------------------------------ amenzi

    private void fineAdd(Player p, UUID u, int amount, String reasonIn) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        if (amount <= 0 || amount > 1_000_000) {
            tell(p, "Suma amenzii trebuie sa fie intre 1 si 1.000.000 lei.", true);
            return;
        }
        String reason = clean(reasonIn, 160);
        if (reason.isEmpty()) reason = "Fara motiv specificat";
        Fine f = new Fine();
        f.id = ids.getAndIncrement();
        f.amount = amount;
        f.reason = reason;
        f.ts = System.currentTimeMillis();
        f.by = p.getName();
        f.byUuid = p.getUniqueId();
        ct.fines.add(f);
        sql("INSERT INTO mdt_fine(id,uuid,amount,reason,ts,paid,by_name,by_uuid) VALUES (?,?,?,?,?,0,?,?)", f.id, ct.uuid.toString(), amount, reason, f.ts, f.by, f.byUuid.toString());
        Player target = Bukkit.getPlayer(ct.uuid);
        if (target != null) {
            target.sendMessage("§c[Politie] §fAi primit o amenda de §e" + amount + " lei §fde la §e" + p.getName() + "§f: " + reason);
            if (plugin.channel().hasMod(target)) plugin.channel().toast(target, "Amenda primita: " + amount + " lei - " + reason, true);
        }
        tell(p, "Amenda de " + amount + " lei a fost emisa lui " + ct.name + ".", false);
        pushAll();
    }

    private void fineDel(Player p, UUID u, long id) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        for (Fine f : ct.fines) {
            if (f.id != id) continue;
            if (!senior(p) && !p.getUniqueId().equals(f.byUuid)) {
                tell(p, "Doar ofiterul care a dat amenda (" + f.by + ") sau un grad superior o poate sterge.", true);
                return;
            }
            ct.fines.remove(f);
            sql("DELETE FROM mdt_fine WHERE id=?", id);
            pushAll();
            return;
        }
    }

    private void finePay(Player p, UUID u, long id, boolean charge) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        for (Fine f : ct.fines) {
            if (f.id != id) continue;
            if (f.paid) return;
            if (!senior(p) && !p.getUniqueId().equals(f.byUuid)) {
                tell(p, "Doar ofiterul care a dat amenda (" + f.by + ") sau un grad superior o poate incasa.", true);
                return;
            }
            if (charge) {
                Player target = Bukkit.getPlayer(ct.uuid);
                if (target == null) {
                    tell(p, ct.name + " nu e online - marcheaz-o platita manual.", true);
                    return;
                }
                if (!plugin.money().withdraw(ct.uuid, f.amount)) {
                    tell(p, ct.name + " nu are " + f.amount + " lei in mana.", true);
                    return;
                }
                target.sendMessage("§a[Politie] §fAi platit amenda de §e" + f.amount + " lei §f(" + f.reason + ").");
            }
            f.paid = true;
            sql("UPDATE mdt_fine SET paid=1 WHERE id=?", id);
            tell(p, "Amenda de " + f.amount + " lei a fost incasata.", false);
            pushAll();
            return;
        }
    }

    // ------------------------------------------------------------------ vehicule, locuinte

    private static String normPlate(String s) {
        return clean(s, 12).toUpperCase(Locale.ROOT).replace(" ", "-");
    }

    private Citizen plateOwner(String plate) {
        for (Citizen ct : citizens.values()) for (Veh v : ct.vehicles) if (v.plate.equalsIgnoreCase(plate)) return ct;
        return null;
    }

    private org.bukkit.configuration.file.YamlConfiguration hypeCfg;
    private long hypeCfgAt;

    private org.bukkit.configuration.file.YamlConfiguration hype() {
        long n = System.currentTimeMillis();
        if (hypeCfg == null || n - hypeCfgAt > 60000L) {
            hypeCfg = ro.solarstudios.flowcity.bridge.core.ModConfig.load(plugin, "hype");
            hypeCfgAt = n;
        }
        return hypeCfg;
    }

    /** {suma de plata, penalizare %, zile intarziere, termen (ms, 0 = fara termen)} pentru o amenda; fara termen configurat = suma initiala. */
    private long[] fineDue(Fine f) {
        int days = hype().getInt("mdt.fine-deadline-days", 0);
        if (days <= 0) return new long[] {f.amount, 0, 0, 0};
        long day = 86400000L;
        long deadline = f.ts + days * day;
        long now = System.currentTimeMillis();
        if (now <= deadline) return new long[] {f.amount, 0, 0, deadline};
        long late = (now - deadline) / day + 1;
        double perDay = hype().getDouble("mdt.fine-penalty-percent-per-day", 0);
        double max = hype().getDouble("mdt.fine-penalty-max-percent", 100);
        double pct = Math.max(0, Math.min(max, perDay * late));
        return new long[] {Math.round(f.amount * (1 + pct / 100.0)), Math.round(pct), late, deadline};
    }

    /** Amenzile neplatite ale unui jucator, pentru ecranul "Amenzile mele" (cu termen si penalizare). */
    public com.google.gson.JsonArray unpaidFines(UUID u) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        Citizen ct = citizen(u);
        if (ct == null) return arr;
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm");
        java.text.SimpleDateFormat dd = new java.text.SimpleDateFormat("dd.MM.yyyy");
        for (Fine f : ct.fines) {
            if (f.paid) continue;
            long[] due = fineDue(f);
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("id", f.id);
            o.addProperty("amount", f.amount);
            o.addProperty("amountDue", due[0]);
            o.addProperty("penaltyPct", due[1]);
            o.addProperty("overdue", due[2] > 0);
            if (due[3] > 0) o.addProperty("dueDate", dd.format(new java.util.Date(due[3])));
            o.addProperty("reason", f.reason);
            o.addProperty("by", f.by);
            o.addProperty("date", df.format(new java.util.Date(f.ts)));
            arr.add(o);
        }
        return arr;
    }

    /** Suma de plata (cu penalizare de intarziere) a unei amenzi neplatite, sau -1 daca nu exista / e deja platita. */
    public int fineAmount(UUID u, long id) {
        Citizen ct = citizen(u);
        if (ct == null) return -1;
        for (Fine f : ct.fines) if (f.id == id && !f.paid) return (int) fineDue(f)[0];
        return -1;
    }

    /** Plata facuta de jucatorul amendat (banii au fost deja luati): marcheaza amenda platita si anunta ofiterul. */
    public boolean payFineByCitizen(UUID u, long id) {
        Citizen ct = citizen(u);
        if (ct == null) return false;
        for (Fine f : ct.fines) {
            if (f.id != id || f.paid) continue;
            f.paid = true;
            sql("UPDATE mdt_fine SET paid=1 WHERE id=?", id);
            Player officer = f.byUuid == null ? null : Bukkit.getPlayer(f.byUuid);
            if (officer != null) officer.sendMessage("\u00a7a[Politie] \u00a7f" + ct.name + " si-a platit amenda de \u00a7e" + fineDue(f)[0] + " lei\u00a7f (" + f.reason + ").");
            pushAll();
            return true;
        }
        return false;
    }

    /** Folosit de dealerul auto: inregistreaza masina cumparata pe numele jucatorului. */
    public static MdtModule instance;

    /** Politist cu acces la MDT (folosit de garaje: ridicari, amenzi de parcare, tag-ul fc_politie). */
    public boolean isOfficer(Player p) {
        return access(p);
    }

    /** Amenda de parcare: proprietarul se afla dupa numar. Intoarce numele lui sau null daca numarul nu e inregistrat. */
    public String fineByPlate(Player officer, String plate, int amount, String reason) {
        Citizen ct = plateOwner(normPlate(plate));
        if (ct == null) return null;
        fineAdd(officer, ct.uuid, amount, reason + " (" + normPlate(plate) + ")");
        return ct.name;
    }

    public boolean plateTaken(String plate) {
        return plateOwner(normPlate(plate)) != null;
    }

    public void registerVehicle(Player p, String modelIn, String plateIn) {
        if (citizens.get(p.getUniqueId()) == null) register(p);
        Citizen ct = citizens.get(p.getUniqueId());
        String plate = normPlate(plateIn);
        if (ct == null || plateOwner(plate) != null) return;
        Veh v = new Veh();
        v.id = ids.getAndIncrement();
        v.model = clean(modelIn, 32);
        v.plate = plate;
        v.status = "Clean";
        ct.vehicles.add(v);
        sql("INSERT INTO mdt_vehicle(id,uuid,model,plate,status) VALUES (?,?,?,?,?)", v.id, ct.uuid.toString(), v.model, plate, v.status);
        pushAll();
    }

    private void vehAdd(Player p, UUID u, String modelIn, String plateIn) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        String model = clean(modelIn, 32);
        String plate = normPlate(plateIn);
        if (ct == null || model.isEmpty() || plate.length() < 3) {
            tell(p, "Completeaza modelul si numarul (minim 3 caractere).", true);
            return;
        }
        if (plateOwner(plate) != null) {
            tell(p, "Numarul " + plate + " este deja inregistrat.", true);
            return;
        }
        Veh v = new Veh();
        v.id = ids.getAndIncrement();
        v.model = model;
        v.plate = plate;
        v.status = "Clean";
        ct.vehicles.add(v);
        sql("INSERT INTO mdt_vehicle(id,uuid,model,plate,status) VALUES (?,?,?,?,?)", v.id, ct.uuid.toString(), model, plate, v.status);
        pushAll();
    }

    private static String nextStatus(String s) {
        if ("Clean".equalsIgnoreCase(s)) return "Stolen";
        if ("Stolen".equalsIgnoreCase(s)) return "Confiscated";
        return "Clean";
    }

    private void vehStatus(Player p, UUID u, long id) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        for (Veh v : ct.vehicles) {
            if (v.id != id) continue;
            v.status = nextStatus(v.status);
            sql("UPDATE mdt_vehicle SET status=? WHERE id=?", v.status, id);
            pushAll();
            return;
        }
    }

    private void vehDel(Player p, UUID u, long id) {
        if (!guard(p) || !senior(p)) {
            if (access(p)) tell(p, "Doar un grad superior poate scoate un vehicul din evidenta.", true);
            return;
        }
        Citizen ct = citizen(u);
        if (ct == null) return;
        for (Veh v : ct.vehicles) {
            if (v.id != id) continue;
            ct.vehicles.remove(v);
            sql("DELETE FROM mdt_vehicle WHERE id=?", id);
            pushAll();
            return;
        }
    }

    private void propAdd(Player p, UUID u, String addrIn) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        String addr = clean(addrIn, 80);
        if (ct == null || addr.isEmpty()) return;
        Prop pr = new Prop();
        pr.id = ids.getAndIncrement();
        pr.addr = addr;
        ct.props.add(pr);
        sql("INSERT INTO mdt_prop(id,uuid,addr) VALUES (?,?,?)", pr.id, ct.uuid.toString(), addr);
        pushAll();
    }

    private void propDel(Player p, UUID u, long id) {
        if (!guard(p)) return;
        Citizen ct = citizen(u);
        if (ct == null) return;
        for (Prop pr : ct.props) {
            if (pr.id != id) continue;
            ct.props.remove(pr);
            sql("DELETE FROM mdt_prop WHERE id=?", id);
            pushAll();
            return;
        }
    }

    private void plate(Player p, String plateIn) {
        if (!guard(p)) return;
        String plate = normPlate(plateIn);
        JsonObject o = new JsonObject();
        o.addProperty("plate", plate);
        Citizen owner = plate.isEmpty() ? null : plateOwner(plate);
        o.addProperty("found", owner != null);
        if (owner != null) {
            for (Veh v : owner.vehicles) {
                if (!v.plate.equalsIgnoreCase(plate)) continue;
                o.addProperty("model", v.model);
                o.addProperty("status", v.status);
            }
            o.addProperty("owner", owner.name);
            o.addProperty("ownerUuid", owner.uuid.toString());
            o.addProperty("wanted", owner.wanted);
        }
        send(p, "plate", o);
    }

    // ------------------------------------------------------------------ unitati

    private Unit unitOf(UUID u) {
        for (Unit un : units.values()) if (un.members.contains(u)) return un;
        return null;
    }

    private void unitCreate(Player p, String nameIn, String typeIn) {
        if (!guard(p)) return;
        String name = clean(nameIn, 20);
        String type = typeIn.toUpperCase(Locale.ROOT);
        boolean ok = false;
        for (String t : UNIT_TYPES) if (t.equals(type)) ok = true;
        if (!ok) type = "PATRULA";
        if (name.isEmpty()) {
            tell(p, "Da un nume unitatii.", true);
            return;
        }
        unitLeave(p, false);
        Unit u = new Unit();
        u.id = "U" + (unitSeq++);
        u.name = name;
        u.type = type;
        u.members.add(p.getUniqueId());
        units.put(u.id, u);
        pushAll();
    }

    private void unitJoin(Player p, String id) {
        if (!guard(p)) return;
        Unit u = units.get(id);
        if (u == null) return;
        if (u.members.contains(p.getUniqueId())) return;
        if (u.members.size() >= 6) {
            tell(p, "Unitatea e plina (6 membri).", true);
            return;
        }
        unitLeave(p, false);
        u.members.add(p.getUniqueId());
        pushAll();
    }

    /** Scoate jucatorul din unitatea sa; sterge unitatea daca ramane goala. Intoarce true daca s-a schimbat ceva. */
    private boolean unitLeave(Player p, boolean push) {
        Unit u = unitOf(p.getUniqueId());
        if (u == null) return false;
        u.members.remove(p.getUniqueId());
        if (u.members.isEmpty()) units.remove(u.id);
        if (push) pushAll();
        return true;
    }

    private void unitStatus(Player p, String status) {
        if (!guard(p)) return;
        Unit u = unitOf(p.getUniqueId());
        if (u == null) return;
        u.status = "Busy".equalsIgnoreCase(status) ? "Busy" : "Available";
        pushAll();
    }

    // ------------------------------------------------------------------ backup / panica

    private void backup(Player p, int code) {
        if (!guard(p)) return;
        boolean panic = code == 99;
        if (!panic && (code < 1 || code > 3)) return;
        Map<UUID, Long> cds = panic ? lastPanic : lastBackup;
        long cd = panic ? PANIC_COOLDOWN : BACKUP_COOLDOWN;
        long now = System.currentTimeMillis();
        Long last = cds.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            tell(p, "Asteapta " + ((cd - (now - last)) / 1000 + 1) + "s inainte sa mai ceri ajutor.", true);
            return;
        }
        cds.put(p.getUniqueId(), now);
        Backup b = new Backup();
        b.uuid = p.getUniqueId();
        b.name = p.getName();
        b.code = code;
        b.x = p.getLocation().getX();
        b.y = p.getLocation().getY();
        b.z = p.getLocation().getZ();
        b.ts = now;
        backups.put(b.uuid, b);
        String label = code == 1 ? "§c§lAJUTOR URGENT" : code == 2 ? "§6§lTRANSPORT" : code == 3 ? "§9§lAJUTOR IN URMARIRE" : "§4§lPANICA COD 99";
        String plain = code == 1 ? "Ajutor urgent" : code == 2 ? "Transport" : code == 3 ? "Ajutor in urmarire" : "PANICA COD 99";
        String chat = "§c[MDT " + (panic ? "COD 99" : "BK " + code) + "] §fOfiterul §e" + p.getName() + " §fsolicita " + label
            + " §fla §b[" + (int) b.x + ", " + (int) b.y + ", " + (int) b.z + "]§f!";
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!access(o)) continue;
            o.sendMessage(chat);
            if (!o.getUniqueId().equals(p.getUniqueId())) {
                if (plugin.channel().hasMod(o)) plugin.channel().toast(o, p.getName() + ": " + plain, true);
                final Player fo = o;
                final int c = code;
                for (int i = 0; i < 3; i++) {
                    final int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (fo.isOnline()) fo.playSound(fo.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, c == 1 || c == 99 ? 1.8f : (k % 2 == 0 ? 1.5f : 1.0f));
                    }, i * 10L);
                }
            }
        }
        // si clientii cu modul primesc coordonatele ca sa poata pune GPS
        JsonObject n = new JsonObject();
        n.addProperty("name", p.getName());
        n.addProperty("code", code);
        n.addProperty("x", b.x);
        n.addProperty("z", b.z);
        for (Player o : Bukkit.getOnlinePlayers()) if (access(o) && !o.getUniqueId().equals(p.getUniqueId())) send(o, "alert", n);
        tell(p, panic ? "Semnal de panica trimis." : "Cerere de backup trimisa.", false);
        pushAll();
    }

    private void backupResolve(Player p, UUID id) {
        if (!guard(p) || id == null) return;
        Backup b = backups.get(id);
        if (b == null) return;
        backups.remove(id);
        Player o = Bukkit.getPlayer(id);
        if (o != null && !o.getUniqueId().equals(p.getUniqueId())) o.sendMessage("§a[MDT] §fCererea ta de backup a fost rezolvata de §e" + p.getName() + "§f.");
        pushAll();
    }

    private void expireBackups() {
        long now = System.currentTimeMillis();
        boolean changed = backups.values().removeIf(b -> now - b.ts > BACKUP_TTL);
        if (changed) pushAll();
    }

    // ------------------------------------------------------------------ oficeri, ranguri

    private void officerAdd(Player p, String name, int rank) {
        if (!guard(p)) return;
        if (!admin(p)) {
            tell(p, "Doar administratorii pot angaja ofiteri.", true);
            return;
        }
        Player t = Bukkit.getPlayerExact(clean(name, 16));
        if (t == null) {
            tell(p, "Jucatorul trebuie sa fie online.", true);
            return;
        }
        setOfficer(t.getUniqueId(), t.getName(), clampRank(rank));
        register(t);
        t.sendMessage("§a[MDT] §fAi fost angajat in politie, grad §e" + rankName(clampRank(rank)) + "§f.");
        tell(p, t.getName() + " a fost angajat (" + rankName(clampRank(rank)) + ").", false);
        pushAll();
    }

    private void setOfficer(UUID u, String name, int rank) {
        Officer o = officers.get(u);
        if (o == null) {
            o = new Officer();
            o.uuid = u;
            o.name = name;
            officers.put(u, o);
        }
        o.rank = rank;
        sql("INSERT OR REPLACE INTO mdt_officer(uuid,name,rank) VALUES (?,?,?)", u.toString(), o.name, rank);
    }

    private void console(String template, String player) {
        if (template == null || template.trim().isEmpty()) return;
        String cmd = template.replace("%player%", player).replace("%name%", player);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        final String run = cmd;
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run));
    }

    private void officerSet(Player p, UUID u, int rank) {
        if (!guard(p)) return;
        Officer o = officers.get(u);
        if (o == null) return;
        int newRank = clampRank(rank);
        if (!admin(p)) {
            int mine = rankOf(p);
            if (mine < SENIOR_RANK || o.rank >= mine || newRank >= mine) {
                tell(p, "Nu poti schimba gradul acestui ofiter.", true);
                return;
            }
        }
        int old = o.rank;
        if (old == newRank) return;
        setOfficer(u, o.name, newRank);
        Rank cfg = ranks.get(newRank);
        if (cfg != null) console(newRank > old ? cfg.up : cfg.down, o.name);
        Player t = Bukkit.getPlayer(u);
        if (t != null) t.sendMessage("§e[MDT] §fGradul tau este acum §e" + rankName(newRank) + "§f.");
        tell(p, o.name + ": " + rankName(newRank) + ".", false);
        pushAll();
    }

    private void officerFire(Player p, UUID u) {
        if (!guard(p)) return;
        Officer o = officers.get(u);
        if (o == null) return;
        if (!admin(p) && (rankOf(p) < SENIOR_RANK || o.rank >= rankOf(p))) {
            tell(p, "Nu poti da afara acest ofiter.", true);
            return;
        }
        officers.remove(u);
        sql("DELETE FROM mdt_officer WHERE uuid=?", u.toString());
        Rank cfg = ranks.get(1);
        if (cfg != null) console(cfg.down, o.name);
        Player t = Bukkit.getPlayer(u);
        if (t != null) {
            unitLeave(t, false);
            backups.remove(u);
            t.sendMessage("§c[MDT] §fAi fost demis din politie.");
        }
        tell(p, o.name + " a fost dat afara.", false);
        pushAll();
    }

    private void rankSave(Player p, int num, String nameIn, String up, String down) {
        if (!guard(p)) return;
        if (!admin(p)) {
            tell(p, "Doar administratorii pot configura gradele.", true);
            return;
        }
        if (num < 1 || num > MAX_RANK) return;
        Rank r = ranks.get(num);
        if (r == null) {
            r = new Rank();
            r.num = num;
            ranks.put(num, r);
        }
        r.name = clean(nameIn, 24).isEmpty() ? DEFAULT_RANKS[num - 1] : clean(nameIn, 24);
        r.up = up == null ? "" : up.trim().replace("\n", " ");
        r.down = down == null ? "" : down.trim().replace("\n", " ");
        sql("INSERT OR REPLACE INTO mdt_rank(rank,name,up,down) VALUES (?,?,?,?)", num, r.name, r.up, r.down);
        tell(p, "Gradul " + num + " salvat.", false);
        pushAll();
    }

    // ------------------------------------------------------------------ comenzi (rezerva)

    private void registerCommands() {
        Commands.register(plugin, "mdt", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatorii pot folosi MDT.");
                return true;
            }
            Player p = (Player) s;
            if (!plugin.channel().hasMod(p)) {
                p.sendMessage("§cMDT necesita modul FlowCity instalat.");
                return true;
            }
            open(p);
            return true;
        }, null);
    }

    /** Pentru teste/rapoarte: cati cetateni cunoaste sistemul. */
    public int citizenCount() {
        return citizens.size();
    }

}
