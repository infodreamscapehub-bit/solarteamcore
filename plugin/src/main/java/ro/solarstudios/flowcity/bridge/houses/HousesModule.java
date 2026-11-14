package ro.solarstudios.flowcity.bridge.houses;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Case si chirii. Admin defineste regiuni (/casa admin pos1|pos2|spawn|create), jucatorii cumpara sau inchiriaza (chirie saptamanala
 * automata din banca, cu zile de gratie), pot vinde inapoi, adauga colegi si se pot teleporta in casa. Regiunile sunt protejate
 * (doar proprietar/chirias/colegi si staff cu flowcity.houses.bypass). Canal "houses".
 */
public final class HousesModule extends JsonModule implements Listener {
    private static final long DAY = 86_400_000L;

    private static final class House {
        String id;
        String name;
        double buy;
        double rent;
        String world;
        int x1;
        int y1;
        int z1;
        int x2;
        int y2;
        int z2;
        double sx;
        double sy;
        double sz;
        float syaw;
        float spitch;
        UUID owner;
        String ownerName = "";
        UUID tenant;
        String tenantName = "";
        long due;
        long unpaid;
        final Map<UUID, String> mates = new LinkedHashMap<>();

        boolean free() {
            return owner == null && tenant == null;
        }

        UUID holder() {
            return owner != null ? owner : tenant;
        }

        String holderName() {
            return owner != null ? ownerName : tenantName;
        }

        boolean member(UUID u) {
            return u.equals(owner) || u.equals(tenant) || mates.containsKey(u);
        }

        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX();
            int y = l.getBlockY();
            int z = l.getBlockZ();
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    private final Map<String, House> houses = new LinkedHashMap<>();
    private final Map<UUID, Location[]> picks = new HashMap<>();
    private final Map<UUID, Long> tpCooldown = new HashMap<>();
    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;

    @Override
    public String id() {
        return "houses";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "houses");
        dataFile = new File(plugin.getDataFolder(), "houses-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Commands.permission("flowcity.houses.admin", "Administreaza casele", PermissionDefault.OP);
        Commands.permission("flowcity.houses.bypass", "Ocoleste protectia caselor", PermissionDefault.OP);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::rentTick, 20L * 60, 20L * 60);

        on("refresh", (p, j) -> sendList(p));
        on("buy", (p, j) -> buy(p, str(j, "id", "")));
        on("rent", (p, j) -> rent(p, str(j, "id", "")));
        on("sell", (p, j) -> sell(p, str(j, "id", "")));
        on("tp", (p, j) -> teleport(p, str(j, "id", "")));
        on("mate_add", (p, j) -> mateAddNearest(p, str(j, "id", "")));
        on("mate_remove", (p, j) -> mateRemove(p, str(j, "id", ""), str(j, "name", "")));

        Commands.register(plugin, "casa", null, (s, c, l, a) -> command(s, a), (s, c, l, a) -> {
            List<String> o = new ArrayList<>();
            if (a.length == 1) {
                o.add("lista");
                o.add("cumpara");
                o.add("inchiriaza");
                o.add("vinde");
                o.add("coleg");
                o.add("teleport");
                if (s.hasPermission("flowcity.houses.admin")) {
                    o.add("admin");
                    o.add("open");
                }
                return Commands.filter(o, a[0]);
            }
            if (a.length == 2 && ("cumpara".equalsIgnoreCase(a[0]) || "inchiriaza".equalsIgnoreCase(a[0]) || "vinde".equalsIgnoreCase(a[0]) || "teleport".equalsIgnoreCase(a[0]))) {
                o.addAll(houses.keySet());
                return Commands.filter(o, a[1]);
            }
            if (a.length == 2 && "coleg".equalsIgnoreCase(a[0])) {
                o.add("add");
                o.add("remove");
                return Commands.filter(o, a[1]);
            }
            if (a.length == 2 && "admin".equalsIgnoreCase(a[0])) {
                o.add("pos1");
                o.add("pos2");
                o.add("spawn");
                o.add("create");
                o.add("delete");
                o.add("list");
                return Commands.filter(o, a[1]);
            }
            if (a.length == 3 && "admin".equalsIgnoreCase(a[0]) && "delete".equalsIgnoreCase(a[1])) {
                o.addAll(houses.keySet());
                return Commands.filter(o, a[2]);
            }
            return o;
        }, "house", "chirie");
    }

    // ------------------------------------------------------------------ persistenta

    private void load() {
        houses.clear();
        ConfigurationSection sec = data.getConfigurationSection("houses");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection o = sec.getConfigurationSection(k);
            if (o == null) continue;
            House h = new House();
            h.id = k;
            h.name = o.getString("name", k);
            h.buy = o.getDouble("buy", 0);
            h.rent = o.getDouble("rent", 0);
            h.world = o.getString("world", "world");
            h.x1 = o.getInt("x1");
            h.y1 = o.getInt("y1");
            h.z1 = o.getInt("z1");
            h.x2 = o.getInt("x2");
            h.y2 = o.getInt("y2");
            h.z2 = o.getInt("z2");
            h.sx = o.getDouble("sx");
            h.sy = o.getDouble("sy");
            h.sz = o.getDouble("sz");
            h.syaw = (float) o.getDouble("syaw");
            h.spitch = (float) o.getDouble("spitch");
            h.owner = uuid(o.getString("owner", ""));
            h.ownerName = o.getString("ownerName", "");
            h.tenant = uuid(o.getString("tenant", ""));
            h.tenantName = o.getString("tenantName", "");
            h.due = o.getLong("due", 0);
            h.unpaid = o.getLong("unpaid", 0);
            for (String m : o.getStringList("mates")) {
                int i = m.indexOf(':');
                UUID u = i > 0 ? uuid(m.substring(0, i)) : null;
                if (u != null) h.mates.put(u, m.substring(i + 1));
            }
            houses.put(h.id, h);
        }
    }

    private static UUID uuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void save() {
        data.set("houses", null);
        for (House h : houses.values()) {
            String b = "houses." + h.id + ".";
            data.set(b + "name", h.name);
            data.set(b + "buy", h.buy);
            data.set(b + "rent", h.rent);
            data.set(b + "world", h.world);
            data.set(b + "x1", h.x1);
            data.set(b + "y1", h.y1);
            data.set(b + "z1", h.z1);
            data.set(b + "x2", h.x2);
            data.set(b + "y2", h.y2);
            data.set(b + "z2", h.z2);
            data.set(b + "sx", h.sx);
            data.set(b + "sy", h.sy);
            data.set(b + "sz", h.sz);
            data.set(b + "syaw", (double) h.syaw);
            data.set(b + "spitch", (double) h.spitch);
            data.set(b + "owner", h.owner == null ? "" : h.owner.toString());
            data.set(b + "ownerName", h.ownerName);
            data.set(b + "tenant", h.tenant == null ? "" : h.tenant.toString());
            data.set(b + "tenantName", h.tenantName);
            data.set(b + "due", h.due);
            data.set(b + "unpaid", h.unpaid);
            List<String> ms = new ArrayList<>();
            for (Map.Entry<UUID, String> e : h.mates.entrySet()) ms.add(e.getKey() + ":" + e.getValue());
            data.set(b + "mates", ms);
        }
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva houses-data.yml: " + e);
        }
    }

    // ------------------------------------------------------------------ utilitare

    private long periodMs() {
        return Math.max(1, cfg.getInt("rent.period-days", 7)) * DAY;
    }

    private List<House> mine(UUID u) {
        List<House> l = new ArrayList<>();
        for (House h : houses.values()) if (u.equals(h.owner) || u.equals(h.tenant)) l.add(h);
        return l;
    }

    private List<House> accessible(UUID u) {
        List<House> l = new ArrayList<>();
        for (House h : houses.values()) if (h.member(u)) l.add(h);
        return l;
    }

    /** Casa indicata de id, sau singura casa a jucatorului cand id e gol. */
    private House pick(Player p, String id, boolean ownOnly) {
        if (id != null && !id.isEmpty()) return houses.get(id.toLowerCase(java.util.Locale.ROOT));
        List<House> l = ownOnly ? mine(p.getUniqueId()) : accessible(p.getUniqueId());
        return l.size() == 1 ? l.get(0) : null;
    }

    private String fmtDate(long t) {
        return new SimpleDateFormat("dd.MM HH:mm").format(new Date(t));
    }

    private void notifyPlayer(UUID u, String msg) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) p.sendMessage(msg);
    }

    private void result(Player p, boolean ok, String msg) {
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("msg", msg);
            send(p, "result", o);
        } else {
            p.sendMessage((ok ? "§a" : "§c") + msg);
        }
    }

    private String state(House h, UUID u) {
        if (h.free()) return "free";
        if (u.equals(h.owner)) return "owner";
        if (u.equals(h.tenant)) return "tenant";
        if (h.mates.containsKey(u)) return "mate";
        return "taken";
    }

    private int sellValue(House h) {
        return (int) Math.round(h.buy * Math.max(0, Math.min(100, cfg.getDouble("sell-percent", 70))) / 100.0);
    }

    private void sendList(Player p) {
        if (!plugin.channel().hasMod(p)) return;
        UUID u = p.getUniqueId();
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (House h : houses.values()) {
            JsonObject j = new JsonObject();
            String st = state(h, u);
            j.addProperty("id", h.id);
            j.addProperty("name", h.name);
            j.addProperty("buy", (int) Math.round(h.buy));
            j.addProperty("rent", (int) Math.round(h.rent));
            j.addProperty("state", st);
            j.addProperty("by", h.free() ? "" : h.holderName());
            j.addProperty("sell", sellValue(h));
            j.addProperty("due", h.tenant != null && "tenant".equals(st) ? fmtDate(h.due) : "");
            j.addProperty("late", h.unpaid > 0 && "tenant".equals(st));
            JsonArray ms = new JsonArray();
            if ("owner".equals(st) || "tenant".equals(st)) for (String n : h.mates.values()) ms.add(new com.google.gson.JsonPrimitive(n));
            j.add("mates", ms);
            arr.add(j);
        }
        o.add("houses", arr);
        o.addProperty("maxMates", cfg.getInt("max-roommates", 4));
        send(p, "open", o);
    }

    private void open(Player p) {
        if (plugin.channel().hasMod(p)) {
            sendList(p);
            return;
        }
        listChat(p);
    }

    private void listChat(Player p) {
        if (houses.isEmpty()) {
            p.sendMessage("§7Nu exista case definite.");
            return;
        }
        p.sendMessage("§9[Case] §fLista caselor:");
        for (House h : houses.values()) {
            String st = state(h, p.getUniqueId());
            String t = "free".equals(st) ? "§aLiberă §7(/casa cumpara " + h.id + " | /casa inchiriaza " + h.id + ")"
                : "taken".equals(st) ? "§cOcupată" : "§eA ta §7(" + st + ")";
            p.sendMessage("§f" + h.name + " §7[" + h.id + "] §e" + (int) h.buy + " lei §7| chirie §e" + (int) h.rent + " lei/" + cfg.getInt("rent.period-days", 7) + "z §7- " + t);
        }
    }

    // ------------------------------------------------------------------ comenzi

    private boolean command(CommandSender s, String[] a) {
        if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
            if (s instanceof Player && !s.hasPermission("flowcity.houses.admin")) {
                s.sendMessage("§cNu ai permisiunea.");
                return true;
            }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t != null) open(t);
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Folosire: casa open <jucator>");
            return true;
        }
        Player p = (Player) s;
        String sub = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
        String arg1 = a.length >= 2 ? a[1] : "";
        switch (sub) {
            case "":
                open(p);
                return true;
            case "lista":
                listChat(p);
                return true;
            case "cumpara":
                buy(p, arg1);
                return true;
            case "inchiriaza":
                rent(p, arg1);
                return true;
            case "vinde":
                sell(p, arg1);
                return true;
            case "teleport":
            case "tp":
                teleport(p, arg1);
                return true;
            case "coleg":
                if (a.length >= 3 && "add".equalsIgnoreCase(a[1])) {
                    Player t = Bukkit.getPlayerExact(a[2]);
                    if (t == null) {
                        result(p, false, "Jucatorul trebuie sa fie online.");
                        return true;
                    }
                    mateAdd(p, a.length >= 4 ? a[3] : "", t);
                } else if (a.length >= 3 && "remove".equalsIgnoreCase(a[1])) {
                    mateRemove(p, a.length >= 4 ? a[3] : "", a[2]);
                } else {
                    p.sendMessage("§7Folosire: /casa coleg add|remove <jucator> [casa]");
                }
                return true;
            case "admin":
                admin(p, a);
                return true;
            default:
                p.sendMessage("§7/casa [lista|cumpara <id>|inchiriaza <id>|vinde <id>|coleg add|remove <nume> [id]|teleport [id]]");
                return true;
        }
    }

    private void admin(Player p, String[] a) {
        if (!p.hasPermission("flowcity.houses.admin")) {
            p.sendMessage("§cNu ai permisiunea.");
            return;
        }
        String sub = a.length >= 2 ? a[1].toLowerCase(java.util.Locale.ROOT) : "";
        Location[] pk = picks.computeIfAbsent(p.getUniqueId(), k -> new Location[3]);
        switch (sub) {
            case "pos1":
                pk[0] = p.getLocation().getBlock().getLocation();
                p.sendMessage("§aPos1 setat: " + pk[0].getBlockX() + " " + pk[0].getBlockY() + " " + pk[0].getBlockZ());
                return;
            case "pos2":
                pk[1] = p.getLocation().getBlock().getLocation();
                p.sendMessage("§aPos2 setat: " + pk[1].getBlockX() + " " + pk[1].getBlockY() + " " + pk[1].getBlockZ());
                return;
            case "spawn":
                pk[2] = p.getLocation().clone();
                p.sendMessage("§aPunctul de intrare setat aici.");
                return;
            case "list":
                if (houses.isEmpty()) p.sendMessage("§7Nicio casa.");
                for (House h : houses.values()) {
                    p.sendMessage("§f" + h.id + " §7- " + h.name + " | " + (int) h.buy + " / " + (int) h.rent + " | "
                        + (h.free() ? "libera" : (h.owner != null ? "proprietar " + h.ownerName : "chirias " + h.tenantName)));
                }
                return;
            case "delete":
                if (a.length < 3 || houses.remove(a[2].toLowerCase(java.util.Locale.ROOT)) == null) {
                    p.sendMessage("§cCasa nu exista.");
                    return;
                }
                save();
                p.sendMessage("§aCasa stearsa.");
                return;
            case "create":
                create(p, a, pk);
                return;
            default:
                p.sendMessage("§7/casa admin pos1|pos2|spawn|create <id> <pret> <chirieSaptamana> [nume]|delete <id>|list");
        }
    }

    private void create(Player p, String[] a, Location[] pk) {
        if (a.length < 5) {
            p.sendMessage("§7Folosire: /casa admin create <id> <pretCumparare> <chirieSaptamana> [nume]");
            return;
        }
        String id = a[2].toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty() || houses.containsKey(id)) {
            p.sendMessage("§cId invalid sau deja folosit.");
            return;
        }
        if (pk[0] == null || pk[1] == null || pk[0].getWorld() == null || !pk[0].getWorld().equals(pk[1].getWorld())) {
            p.sendMessage("§cSeteaza pos1 si pos2 (aceeasi lume) inainte.");
            return;
        }
        double buy;
        double rent;
        try {
            buy = Double.parseDouble(a[3]);
            rent = Double.parseDouble(a[4]);
        } catch (NumberFormatException e) {
            p.sendMessage("§cPret/chirie invalide.");
            return;
        }
        House h = new House();
        h.id = id;
        StringBuilder nm = new StringBuilder();
        for (int i = 5; i < a.length; i++) nm.append(i > 5 ? " " : "").append(a[i]);
        h.name = nm.length() > 0 ? nm.toString() : id.replace('_', ' ');
        h.buy = Math.max(0, buy);
        h.rent = Math.max(0, rent);
        h.world = pk[0].getWorld().getName();
        h.x1 = Math.min(pk[0].getBlockX(), pk[1].getBlockX());
        h.y1 = Math.min(pk[0].getBlockY(), pk[1].getBlockY());
        h.z1 = Math.min(pk[0].getBlockZ(), pk[1].getBlockZ());
        h.x2 = Math.max(pk[0].getBlockX(), pk[1].getBlockX());
        h.y2 = Math.max(pk[0].getBlockY(), pk[1].getBlockY());
        h.z2 = Math.max(pk[0].getBlockZ(), pk[1].getBlockZ());
        Location sp = pk[2] != null ? pk[2] : p.getLocation();
        h.sx = sp.getX();
        h.sy = sp.getY();
        h.sz = sp.getZ();
        h.syaw = sp.getYaw();
        h.spitch = sp.getPitch();
        houses.put(id, h);
        save();
        p.sendMessage("§aCasa §f" + h.name + " §acreata (" + id + ").");
    }

    // ------------------------------------------------------------------ actiuni jucator

    private boolean overLimit(Player p) {
        return mine(p.getUniqueId()).size() >= Math.max(1, cfg.getInt("max-houses-per-player", 1));
    }

    private void buy(Player p, String id) {
        final House h = houses.get(id.toLowerCase(java.util.Locale.ROOT));
        if (h == null) {
            result(p, false, "Casa nu exista.");
            return;
        }
        if (!h.free()) {
            result(p, false, "Casa e ocupata.");
            return;
        }
        if (overLimit(p)) {
            result(p, false, "Ai deja numarul maxim de case.");
            return;
        }
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, "Casa " + h.name, h.buy, () -> {
            if (!h.free() || overLimit(p)) return false;
            h.owner = p.getUniqueId();
            h.ownerName = p.getName();
            h.tenant = null;
            h.tenantName = "";
            h.mates.clear();
            h.unpaid = 0;
            save();
            result(p, true, "Ai cumparat " + h.name + ".");
            sendList(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    private void rent(Player p, String id) {
        final House h = houses.get(id.toLowerCase(java.util.Locale.ROOT));
        if (h == null) {
            result(p, false, "Casa nu exista.");
            return;
        }
        if (!h.free()) {
            result(p, false, "Casa e ocupata.");
            return;
        }
        if (overLimit(p)) {
            result(p, false, "Ai deja numarul maxim de case.");
            return;
        }
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, "Chirie " + h.name + " (prima perioada)", h.rent, () -> {
            if (!h.free() || overLimit(p)) return false;
            h.tenant = p.getUniqueId();
            h.tenantName = p.getName();
            h.owner = null;
            h.ownerName = "";
            h.mates.clear();
            h.due = System.currentTimeMillis() + periodMs();
            h.unpaid = 0;
            save();
            result(p, true, "Ai inchiriat " + h.name + ". Chiria se retrage automat din cont la " + fmtDate(h.due) + ".");
            sendList(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    private void sell(Player p, String id) {
        House h = pick(p, id, true);
        if (h == null) {
            result(p, false, "Specifica casa ta (/casa vinde <id>).");
            return;
        }
        UUID u = p.getUniqueId();
        if (u.equals(h.owner)) {
            int v = sellValue(h);
            if (v > 0 && !plugin.money().deposit(u, v)) {
                result(p, false, "Nu am putut depune banii.");
                return;
            }
            clear(h);
            save();
            result(p, true, "Ai vandut " + h.name + " pentru " + v + " lei.");
        } else if (u.equals(h.tenant)) {
            clear(h);
            save();
            result(p, true, "Ai renuntat la chiria casei " + h.name + ".");
        } else {
            result(p, false, "Casa nu e a ta.");
            return;
        }
        sendList(p);
    }

    private void clear(House h) {
        h.owner = null;
        h.ownerName = "";
        h.tenant = null;
        h.tenantName = "";
        h.mates.clear();
        h.due = 0;
        h.unpaid = 0;
    }

    private void teleport(Player p, String id) {
        House h = pick(p, id, false);
        if (h == null) {
            result(p, false, "Specifica casa (/casa teleport <id>).");
            return;
        }
        if (!h.member(p.getUniqueId())) {
            result(p, false, "Nu ai acces la aceasta casa.");
            return;
        }
        long now = System.currentTimeMillis();
        long cd = Math.max(0, cfg.getInt("teleport-cooldown-seconds", 30)) * 1000L;
        Long last = tpCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            result(p, false, "Mai asteapta " + ((cd - (now - last)) / 1000 + 1) + " secunde.");
            return;
        }
        World w = Bukkit.getWorld(h.world);
        if (w == null) {
            result(p, false, "Lumea casei nu e incarcata.");
            return;
        }
        tpCooldown.put(p.getUniqueId(), now);
        p.teleport(new Location(w, h.sx, h.sy, h.sz, h.syaw, h.spitch));
        result(p, true, "Bine ai venit acasa!");
    }

    private void mateAddNearest(Player p, String id) {
        Player best = null;
        double bd = 10 * 10;
        for (Player q : p.getWorld().getPlayers()) {
            if (q.equals(p)) continue;
            double d = q.getLocation().distanceSquared(p.getLocation());
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) {
            result(p, false, "Niciun jucator aproape (10 blocuri).");
            return;
        }
        mateAdd(p, id, best);
    }

    private void mateAdd(Player p, String id, Player t) {
        House h = pick(p, id, true);
        if (h == null || !(p.getUniqueId().equals(h.owner) || p.getUniqueId().equals(h.tenant))) {
            result(p, false, "Doar proprietarul/chiriasul poate adauga colegi.");
            return;
        }
        if (h.member(t.getUniqueId())) {
            result(p, false, t.getName() + " are deja acces.");
            return;
        }
        if (h.mates.size() >= cfg.getInt("max-roommates", 4)) {
            result(p, false, "Ai atins numarul maxim de colegi.");
            return;
        }
        h.mates.put(t.getUniqueId(), t.getName());
        save();
        t.sendMessage("§9[Case] §f" + p.getName() + " ti-a dat acces in " + h.name + ".");
        result(p, true, t.getName() + " a fost adaugat.");
        sendList(p);
    }

    private void mateRemove(Player p, String id, String name) {
        House h = pick(p, id, true);
        if (h == null || !(p.getUniqueId().equals(h.owner) || p.getUniqueId().equals(h.tenant))) {
            result(p, false, "Doar proprietarul/chiriasul poate scoate colegi.");
            return;
        }
        UUID found = null;
        for (Map.Entry<UUID, String> e : h.mates.entrySet()) if (e.getValue().equalsIgnoreCase(name)) found = e.getKey();
        if (found == null) {
            result(p, false, "Nu e coleg: " + name);
            return;
        }
        h.mates.remove(found);
        save();
        result(p, true, name + " a fost scos.");
        sendList(p);
    }

    // ------------------------------------------------------------------ chirii automate

    private void rentTick() {
        long now = System.currentTimeMillis();
        long period = periodMs();
        long grace = Math.max(0, cfg.getInt("rent.grace-days", 2)) * DAY;
        boolean dirty = false;
        for (House h : houses.values()) {
            if (h.tenant == null || now < h.due) continue;
            if (h.rent <= 0 || plugin.bank().withdraw(h.tenant, h.rent)) {
                h.due += period;
                if (h.due < now) h.due = now + period;
                h.unpaid = 0;
                dirty = true;
                notifyPlayer(h.tenant, "§9[Case] §fChiria casei §e" + h.name + " §fa fost platita din cont: §a-" + (int) h.rent + " lei§f. Urmatoarea: " + fmtDate(h.due));
            } else if (h.unpaid == 0) {
                h.unpaid = now;
                dirty = true;
                notifyPlayer(h.tenant, "§c[Case] Nu ai destui bani in cont pentru chiria casei " + h.name + " (" + (int) h.rent + " lei). Ai "
                    + cfg.getInt("rent.grace-days", 2) + " zile sa depui, altfel pierzi casa.");
            } else if (now - h.unpaid >= grace) {
                notifyPlayer(h.tenant, "§c[Case] Ai pierdut casa " + h.name + " pentru ca nu ai platit chiria.");
                clear(h);
                dirty = true;
            }
        }
        if (dirty) save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (House h : houses.values()) {
                if (p.getUniqueId().equals(h.tenant) && h.unpaid > 0) {
                    p.sendMessage("§c[Case] Chiria casei " + h.name + " e restanta (" + (int) h.rent + " lei). Depune bani in cont!");
                }
            }
        }, 80L);
    }

    // ------------------------------------------------------------------ protectie

    private House at(Location l) {
        for (House h : houses.values()) if (h.contains(l)) return h;
        return null;
    }

    private boolean denied(Player p, Location l) {
        House h = at(l);
        if (h == null) return false;
        if (p.hasPermission("flowcity.houses.bypass")) return false;
        return !h.member(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cAceasta proprietate nu e a ta.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denied(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cAceasta proprietate nu e a ta.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (denied(e.getPlayer(), e.getClickedBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§cAceasta proprietate nu e a ta.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (denied(e.getPlayer(), e.getBlockClicked().getRelative(e.getBlockFace()).getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denied(e.getPlayer(), e.getBlockClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> at(b.getLocation()) != null);
    }
}
