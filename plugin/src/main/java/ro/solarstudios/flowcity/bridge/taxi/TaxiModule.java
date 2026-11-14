package ro.solarstudios.flowcity.bridge.taxi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.docs.DocsModule;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Taxi cu jucatori: soferii se pun "la datorie" (/taxi duty), clientii cheama un taxi (/taxi call), soferul accepta,
 * porneste cursa (/taxi start) si taximetrul numara km + asteptarea; la final clientul plateste prin PayModule.
 * Datele (curse, castig) se salveaza in plugins/FlowCityBridge/taxi-data.yml (salvare grupata). Config: taxi.yml.
 */
public final class TaxiModule extends JsonModule {
    /** O cerere de taxi: clientul, mesajul si (dupa accept) soferul care vine dupa el. */
    private static final class Req {
        UUID client;
        String name;
        String msg;
        long at;
        UUID driver;
        boolean arrived;
    }

    /** O cursa in desfasurare. */
    private static final class Ride {
        UUID driver;
        UUID client;
        long startAt;
        long lastAt;
        double km;
        double wait;
        int away;
        Location last;
    }

    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;
    private String tag = "§e[Taxi] §7";
    private String driverPerm = "flowcity.taxi.driver";

    private final Map<UUID, Req> reqs = new LinkedHashMap<>();
    private final Map<UUID, Ride> rides = new HashMap<>();
    private final Map<UUID, UUID> clientToDriver = new HashMap<>();
    private final Set<UUID> duty = new LinkedHashSet<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private boolean saveScheduled;

    @Override
    public String id() {
        return "taxi";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "taxi");
        tag = ChatColor.translateAlternateColorCodes('&', cfg.getString("tag", "&e[Taxi] &7"));
        driverPerm = cfg.getString("driver-permission", "flowcity.taxi.driver");
        dataFile = new File(plugin.getDataFolder(), "taxi-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        Commands.permission(driverPerm, "Poate lucra ca taximetrist", PermissionDefault.TRUE);
        Commands.permission("flowcity.taxi.admin", "Taxi: deschide ecranul altui jucator", PermissionDefault.OP);

        on("refresh", (p, j) -> refresh(p));
        on("duty", (p, j) -> toggleDuty(p, bool(j, "on", !duty.contains(p.getUniqueId()))));
        on("call", (p, j) -> call(p, str(j, "msg", "")));
        on("cancel", (p, j) -> cancel(p));
        on("accept", (p, j) -> accept(p, str(j, "client", "")));
        on("start", (p, j) -> start(p, str(j, "player", "")));
        on("stop", (p, j) -> stop(p));

        Commands.register(plugin, "taxi", null, (s, c, l, a) -> command(s, a), (s, c, l, a) -> complete(a), "taximetru");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    @Override
    public void disable() {
        saveNow();
    }

    @Override
    public void onQuit(Player p) {
        UUID u = p.getUniqueId();
        Ride r = rideOf(u);
        if (r != null) endRide(r, false, p.getName() + " a iesit de pe server: cursa a fost anulata.");
        Req q = reqs.remove(u);
        if (q != null && q.driver != null) {
            Player d = Bukkit.getPlayer(q.driver);
            if (d != null) {
                say(d, "Clientul " + q.name + " a iesit de pe server.");
                hudNone(d);
            }
        }
        for (Req x : reqs.values()) {
            if (u.equals(x.driver)) {
                x.driver = null;
                x.arrived = false;
                Player c = Bukkit.getPlayer(x.client);
                if (c != null) {
                    say(c, "Taximetristul s-a retras. Cererea ta ramane activa.");
                    hudNone(c);
                }
            }
        }
        duty.remove(u);
        cooldown.remove(u);
        refreshDrivers();
    }

    // ------------------------------------------------------------------ comenzi

    private boolean command(org.bukkit.command.CommandSender s, String[] a) {
        if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
            if (s instanceof Player && !s.hasPermission("flowcity.taxi.admin")) {
                s.sendMessage("§cNu ai permisiunea.");
                return true;
            }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t != null) open(t);
            return true;
        }
        if (!(s instanceof Player)) {
            s.sendMessage("Folosire: taxi open <jucator>");
            return true;
        }
        Player p = (Player) s;
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        String rest = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
        switch (sub) {
            case "":
                open(p);
                break;
            case "duty":
                if (rest.equalsIgnoreCase("on")) toggleDuty(p, true);
                else if (rest.equalsIgnoreCase("off")) toggleDuty(p, false);
                else toggleDuty(p, !duty.contains(p.getUniqueId()));
                break;
            case "call":
                call(p, rest);
                break;
            case "accept":
                accept(p, rest);
                break;
            case "start":
                start(p, rest);
                break;
            case "stop":
                stop(p);
                break;
            case "cancel":
                cancel(p);
                break;
            case "top":
                top(p);
                break;
            default:
                help(p);
        }
        return true;
    }

    private List<String> complete(String[] a) {
        if (a.length == 1) return Commands.filter(Arrays.asList("duty", "call", "accept", "start", "stop", "cancel", "top"), a[0]);
        if (a.length == 2 && "accept".equalsIgnoreCase(a[0])) {
            List<String> names = new ArrayList<>();
            for (Req q : reqs.values()) names.add(q.name);
            return Commands.filter(names, a[1]);
        }
        if (a.length == 2 && "duty".equalsIgnoreCase(a[0])) return Commands.filter(Arrays.asList("on", "off"), a[1]);
        return Collections.emptyList();
    }

    private void help(Player p) {
        p.sendMessage("§e§lTAXI §7- comenzi:");
        p.sendMessage("§f/taxi §7- ecranul de taxi");
        p.sendMessage("§f/taxi call [mesaj] §7- cheama un taxi   §f/taxi cancel §7- anuleaza");
        p.sendMessage("§f/taxi duty [on|off] §7- intri/iesi de la datorie (sofer)");
        p.sendMessage("§f/taxi accept <jucator> §7- accepti o cerere   §f/taxi start §7- pornesti cursa   §f/taxi stop §7- o oprestii");
        p.sendMessage("§f/taxi top §7- clasamentul soferilor");
    }

    // ------------------------------------------------------------------ helperi

    private void say(Player p, String text) {
        if (p != null) p.sendMessage(tag + text);
    }

    private void note(Player p, NotifyModule.Type t, String title, String text) {
        if (p == null) return;
        NotifyModule n = plugin.notifications();
        if (n != null) n.send(p, t, title, text);
        else say(p, text);
    }

    /** Mesaj in chat + toast in ecranul de taxi. */
    private void result(Player p, boolean ok, String text) {
        if (p == null) return;
        say(p, (ok ? "§a" : "§c") + text);
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", text);
        send(p, "result", o);
    }

    private void hudNone(Player p) {
        if (p == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("mode", "none");
        send(p, "hud", o);
    }

    private static int dist(Player a, Player b) {
        if (a == null || b == null || a.getWorld() != b.getWorld()) return -1;
        return (int) Math.round(a.getLocation().distance(b.getLocation()));
    }

    private Ride rideOf(UUID u) {
        Ride r = rides.get(u);
        if (r != null) return r;
        UUID d = clientToDriver.get(u);
        return d == null ? null : rides.get(d);
    }

    private double fare(String key, double def) {
        return cfg.getDouble("fares." + key, def);
    }

    private boolean canDrive(Player p) {
        if (!driverPerm.isEmpty() && !p.hasPermission(driverPerm)) return false;
        if (cfg.getBoolean("require-licence", false)) {
            DocsModule docs = DocsModule.instance;
            if (docs == null) return true;
            DocsModule.Doc d = docs.get(p.getUniqueId(), "permis");
            return d != null && docs.valid(p.getUniqueId(), "permis") && d.categories != null && d.categories.toUpperCase(Locale.ROOT).contains("B");
        }
        return true;
    }

    private int fareOf(Ride r) {
        double f = fare("start-fee", 20) + r.km * fare("per-km", 15) + (r.wait / 60.0) * fare("per-minute-wait", 2);
        f = Math.max(fare("min-fare", 30), f);
        f = Math.min(fare("max-fare", 5000), f);
        return (int) Math.round(f);
    }

    private boolean freeDriver(UUID u) {
        if (!duty.contains(u) || rides.containsKey(u)) return false;
        for (Req q : reqs.values()) if (u.equals(q.driver)) return false;
        return true;
    }

    // ------------------------------------------------------------------ stare / ecran

    private JsonObject build(Player p) {
        UUID u = p.getUniqueId();
        JsonObject o = new JsonObject();
        boolean isDrv = duty.contains(u);
        o.addProperty("duty", isDrv);
        o.addProperty("canDrive", canDrive(p));
        o.addProperty("onlineDrivers", duty.size());
        JsonArray ds = new JsonArray();
        for (UUID du : duty) {
            Player dp = Bukkit.getPlayer(du);
            if (dp == null || du.equals(u)) continue;
            JsonObject e = new JsonObject();
            e.addProperty("name", dp.getName());
            e.addProperty("dist", dist(p, dp));
            e.addProperty("busy", !freeDriver(du));
            ds.add(e);
        }
        o.add("drivers", ds);
        Req mine = reqs.get(u);
        o.addProperty("calling", mine != null);
        if (mine != null) {
            o.addProperty("callAge", (System.currentTimeMillis() - mine.at) / 1000L);
            if (mine.driver != null) {
                Player dp = Bukkit.getPlayer(mine.driver);
                if (dp != null) {
                    o.addProperty("driverName", dp.getName());
                    o.addProperty("driverDist", dist(p, dp));
                }
            }
        }
        Ride r = rideOf(u);
        o.addProperty("inRide", r != null);
        if (r != null) o.addProperty("role", r.driver.equals(u) ? "driver" : "client");
        o.addProperty("pickup", isDrv && hasPickup(u));
        JsonArray rs = new JsonArray();
        if (isDrv) {
            for (Req q : reqs.values()) {
                Player cp = Bukkit.getPlayer(q.client);
                if (cp == null) continue;
                JsonObject e = new JsonObject();
                e.addProperty("client", q.client.toString());
                e.addProperty("name", q.name);
                e.addProperty("dist", dist(p, cp));
                e.addProperty("msg", q.msg);
                e.addProperty("taken", q.driver != null);
                e.addProperty("mine", u.equals(q.driver));
                e.addProperty("age", (System.currentTimeMillis() - q.at) / 1000L);
                rs.add(e);
            }
        }
        o.add("requests", rs);
        o.addProperty("startFee", fare("start-fee", 20));
        o.addProperty("perKm", fare("per-km", 15));
        o.addProperty("perMin", fare("per-minute-wait", 2));
        o.addProperty("minFare", fare("min-fare", 30));
        o.addProperty("cut", cfg.getDouble("company-cut-percent", 10));
        o.addProperty("myRides", data.getInt("drivers." + u + ".rides", 0));
        o.addProperty("myEarned", Math.round(data.getDouble("drivers." + u + ".earned", 0)));
        JsonArray top = new JsonArray();
        for (String[] row : topRows(5)) {
            JsonObject e = new JsonObject();
            e.addProperty("name", row[0]);
            e.addProperty("rides", Integer.parseInt(row[1]));
            e.addProperty("earned", Long.parseLong(row[2]));
            top.add(e);
        }
        o.add("top", top);
        return o;
    }

    private boolean hasPickup(UUID driver) {
        for (Req q : reqs.values()) if (driver.equals(q.driver)) return true;
        return false;
    }

    private void open(Player p) {
        if (!plugin.channel().hasMod(p)) {
            help(p);
            return;
        }
        send(p, "open", build(p));
    }

    private void refresh(Player p) {
        if (p != null) send(p, "state", build(p));
    }

    private void refreshDrivers() {
        for (UUID du : new ArrayList<>(duty)) refresh(Bukkit.getPlayer(du));
    }

    // ------------------------------------------------------------------ sofer: duty

    private void toggleDuty(Player p, boolean on) {
        UUID u = p.getUniqueId();
        if (on) {
            if (duty.contains(u)) {
                refresh(p);
                return;
            }
            if (!canDrive(p)) {
                result(p, false, cfg.getBoolean("require-licence", false)
                    ? "Ai nevoie de permis valid categoria B ca sa lucrezi ca taximetrist."
                    : "Nu ai voie sa lucrezi ca taximetrist.");
                return;
            }
            if (clientToDriver.containsKey(u) || reqs.containsKey(u)) {
                result(p, false, "Esti client acum: termina sau anuleaza cererea / cursa mai intai.");
                return;
            }
            duty.add(u);
            result(p, true, "Esti la datorie ca taximetrist. Cererile apar in ecranul /taxi.");
            int open = 0;
            for (Req q : reqs.values()) if (q.driver == null) open++;
            if (open > 0) note(p, NotifyModule.Type.INFO, "Taxi", open + " cereri de taxi asteapta un sofer.");
        } else {
            if (!duty.contains(u)) {
                refresh(p);
                return;
            }
            if (rides.containsKey(u)) {
                result(p, false, "Ai o cursa in desfasurare: opreste-o cu /taxi stop.");
                return;
            }
            duty.remove(u);
            for (Req q : reqs.values()) {
                if (u.equals(q.driver)) {
                    q.driver = null;
                    q.arrived = false;
                    Player c = Bukkit.getPlayer(q.client);
                    if (c != null) {
                        say(c, "Taximetristul s-a retras. Cererea ta ramane activa.");
                        hudNone(c);
                        refresh(c);
                    }
                }
            }
            hudNone(p);
            result(p, true, "Nu mai esti la datorie.");
        }
        refresh(p);
        refreshDrivers();
    }

    // ------------------------------------------------------------------ client: call / cancel

    private void call(Player p, String message) {
        UUID u = p.getUniqueId();
        if (duty.contains(u)) {
            result(p, false, "Esti la datorie ca sofer: iesi de la datorie ca sa chemi un taxi.");
            return;
        }
        if (clientToDriver.containsKey(u)) {
            result(p, false, "Esti deja intr-o cursa.");
            return;
        }
        if (reqs.containsKey(u)) {
            result(p, false, "Ai deja o cerere activa. Anuleaz-o cu /taxi cancel.");
            return;
        }
        long cd = (long) (cfg.getDouble("call-cooldown-seconds", 60) * 1000);
        Long last = cooldown.get(u);
        long now = System.currentTimeMillis();
        if (last != null && now - last < cd) {
            result(p, false, "Mai asteapta " + ((cd - (now - last) + 999) / 1000) + " s inainte sa chemi din nou.");
            return;
        }
        List<Player> free = new ArrayList<>();
        for (UUID du : duty) {
            Player dp = Bukkit.getPlayer(du);
            if (dp != null && freeDriver(du)) free.add(dp);
        }
        if (free.isEmpty()) {
            result(p, false, "Nu e niciun taximetrist liber acum.");
            return;
        }
        Req q = new Req();
        q.client = u;
        q.name = p.getName();
        q.msg = message == null ? "" : message.replaceAll("[§&]", "").trim();
        if (q.msg.length() > 80) q.msg = q.msg.substring(0, 80);
        q.at = now;
        reqs.put(u, q);
        cooldown.put(u, now);
        for (Player dp : free) {
            int d = dist(dp, p);
            note(dp, NotifyModule.Type.INFO, "Taxi", p.getName() + " cheama un taxi" + (d >= 0 ? " (" + d + " m)" : "") + (q.msg.isEmpty() ? "" : ": " + q.msg));
        }
        result(p, true, "Cerere trimisa la " + free.size() + " taximetristi. Asteapta sa o accepte cineva.");
        refresh(p);
        refreshDrivers();
    }

    private void cancel(Player p) {
        UUID u = p.getUniqueId();
        boolean did = false;
        Req mine = reqs.remove(u);
        if (mine != null) {
            did = true;
            if (mine.driver != null) {
                Player d = Bukkit.getPlayer(mine.driver);
                if (d != null) {
                    say(d, "§e" + mine.name + " a anulat cererea de taxi.");
                    hudNone(d);
                    refresh(d);
                }
            }
            hudNone(p);
        }
        for (Req q : reqs.values()) {
            if (u.equals(q.driver)) {
                q.driver = null;
                q.arrived = false;
                did = true;
                Player c = Bukkit.getPlayer(q.client);
                if (c != null) {
                    say(c, "Taximetristul a renuntat la cerere. Ea ramane activa pentru alti soferi.");
                    hudNone(c);
                    refresh(c);
                }
                hudNone(p);
            }
        }
        if (!did) {
            result(p, false, "Nu ai nicio cerere sau preluare de anulat.");
            return;
        }
        result(p, true, "Anulat.");
        refresh(p);
        refreshDrivers();
    }

    // ------------------------------------------------------------------ sofer: accept / start / stop

    private void accept(Player drv, String who) {
        UUID du = drv.getUniqueId();
        if (!duty.contains(du)) {
            result(drv, false, "Trebuie sa fii la datorie (/taxi duty).");
            return;
        }
        if (rides.containsKey(du)) {
            result(drv, false, "Ai deja o cursa in desfasurare.");
            return;
        }
        if (hasPickup(du)) {
            result(drv, false, "Ai deja un client de preluat (/taxi cancel ca sa renunti).");
            return;
        }
        Req q = null;
        String key = who == null ? "" : who.trim();
        for (Req x : reqs.values()) {
            if (x.client.toString().equalsIgnoreCase(key) || x.name.equalsIgnoreCase(key)) {
                q = x;
                break;
            }
        }
        if (q == null) {
            result(drv, false, key.isEmpty() ? "Spune cine: /taxi accept <jucator>." : "Nu exista o cerere de la " + key + ".");
            return;
        }
        Player cp = Bukkit.getPlayer(q.client);
        if (cp == null) {
            reqs.remove(q.client);
            result(drv, false, "Clientul nu mai e online.");
            refreshDrivers();
            return;
        }
        if (q.driver != null) {
            Player other = Bukkit.getPlayer(q.driver);
            result(drv, false, "Cererea a fost preluata deja" + (other != null ? " de " + other.getName() : "") + ".");
            refresh(drv);
            return;
        }
        q.driver = du;
        q.arrived = false;
        int d = dist(drv, cp);
        note(cp, NotifyModule.Type.OK, "Taxi", "Taxi-ul vine: " + drv.getName() + (d >= 0 ? " la " + d + " m" : ""));
        result(drv, true, "Ai acceptat cursa lui " + q.name + ". Urmareste sageata din HUD; /taxi start cand urca.");
        pushPickup(q);
        refresh(drv);
        refresh(cp);
        refreshDrivers();
    }

    private void start(Player drv, String targetName) {
        UUID du = drv.getUniqueId();
        if (!duty.contains(du)) {
            result(drv, false, "Trebuie sa fii la datorie (/taxi duty).");
            return;
        }
        if (rides.containsKey(du)) {
            result(drv, false, "Ai deja o cursa in desfasurare.");
            return;
        }
        double radius = cfg.getDouble("start-radius", 4);
        Player cp = null;
        if (targetName != null && !targetName.trim().isEmpty()) {
            cp = Bukkit.getPlayerExact(targetName.trim());
            if (cp == null) {
                result(drv, false, "Jucatorul nu e online.");
                return;
            }
        } else {
            for (Req q : reqs.values()) {
                if (du.equals(q.driver)) {
                    cp = Bukkit.getPlayer(q.client);
                    break;
                }
            }
            if (cp == null) {
                double best = Double.MAX_VALUE;
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (o.equals(drv) || o.getWorld() != drv.getWorld() || rideOf(o.getUniqueId()) != null || duty.contains(o.getUniqueId())) continue;
                    double dd = o.getLocation().distance(drv.getLocation());
                    if (dd <= radius && dd < best) {
                        best = dd;
                        cp = o;
                    }
                }
            }
        }
        if (cp == null) {
            result(drv, false, "Nu e niciun client langa tine. Clientul trebuie sa fie in vehicul sau la maxim " + (int) radius + " blocuri.");
            return;
        }
        UUID cu = cp.getUniqueId();
        if (cu.equals(du)) {
            result(drv, false, "Nu poti porni cursa pentru tine.");
            return;
        }
        if (clientToDriver.containsKey(cu) || rides.containsKey(cu)) {
            result(drv, false, cp.getName() + " e deja intr-o cursa.");
            return;
        }
        Entity v1 = drv.getVehicle();
        Entity v2 = cp.getVehicle();
        boolean sameVeh = v1 != null && v2 != null && v1.getUniqueId().equals(v2.getUniqueId());
        boolean close = cp.getWorld() == drv.getWorld() && cp.getLocation().distance(drv.getLocation()) <= radius;
        if (!sameVeh && !close) {
            result(drv, false, cp.getName() + " trebuie sa fie in acelasi vehicul sau la maxim " + (int) radius + " blocuri de tine.");
            return;
        }
        reqs.remove(cu);
        Ride r = new Ride();
        r.driver = du;
        r.client = cu;
        r.startAt = System.currentTimeMillis();
        r.lastAt = r.startAt;
        r.last = drv.getLocation();
        rides.put(du, r);
        clientToDriver.put(cu, du);
        result(drv, true, "Cursa a pornit cu " + cp.getName() + ". Taximetrul numara.");
        note(cp, NotifyModule.Type.INFO, "Taxi", "Cursa a pornit cu " + drv.getName() + ". La final platesti suma de pe taximetru.");
        pushRide(r, drv, cp);
        refresh(drv);
        refresh(cp);
        refreshDrivers();
    }

    private void stop(Player p) {
        Ride r = rideOf(p.getUniqueId());
        if (r == null) {
            result(p, false, "Nu esti intr-o cursa.");
            return;
        }
        endRide(r, true, p.getName() + " a oprit cursa.");
    }

    // ------------------------------------------------------------------ sfarsit cursa + plata

    private void endRide(Ride r, boolean charge, String reason) {
        if (rides.get(r.driver) != r) return;
        rides.remove(r.driver);
        clientToDriver.remove(r.client);
        Player drv = Bukkit.getPlayer(r.driver);
        Player cli = Bukkit.getPlayer(r.client);
        hudNone(drv);
        hudNone(cli);
        final int amount = fareOf(r);
        final double km = r.km;
        final long secs = Math.max(0L, (System.currentTimeMillis() - r.startAt) / 1000L);
        if (!charge || cli == null) {
            if (drv != null) note(drv, NotifyModule.Type.WARN, "Taxi", reason + " Cursa nu a fost incasata.");
            if (cli != null) note(cli, NotifyModule.Type.WARN, "Taxi", reason + " Nu ai de plata nimic.");
            refresh(drv);
            refresh(cli);
            refreshDrivers();
            return;
        }
        if (drv != null) note(drv, NotifyModule.Type.INFO, "Taxi", "Cursa terminata: " + String.format(Locale.ROOT, "%.2f", km) + " km, " + amount + " lei. Clientul trebuie sa plateasca.");
        final UUID du = r.driver;
        final UUID cu = r.client;
        final String dn = drv == null ? "taximetrist" : drv.getName();
        final String cn = cli.getName();
        Supplier<Boolean> fulfil = () -> payout(du, cu, cn, amount, km, secs);
        Consumer<String> fail = m -> unpaid(du, cu, amount, m);
        PayModule pm = PayModule.instance;
        if (pm == null) {
            if (plugin.money().has(cu, amount) && plugin.money().withdraw(cu, amount)) {
                if (!fulfil.get()) plugin.money().deposit(cu, amount);
            } else {
                fail.accept("Nu ai destui bani cash.");
            }
        } else {
            pm.request(cli, "Cursa taxi - " + dn, amount, fulfil, fail);
        }
        refresh(drv);
        refresh(cli);
        refreshDrivers();
    }

    private Boolean payout(UUID du, UUID cu, String clientName, int amount, double km, long secs) {
        double cut = Math.max(0, Math.min(100, cfg.getDouble("company-cut-percent", 10)));
        double net = Math.round(amount * (100.0 - cut)) / 100.0;
        if (net > 0) plugin.money().deposit(du, net);
        String base = "drivers." + du;
        data.set(base + ".rides", data.getInt(base + ".rides", 0) + 1);
        data.set(base + ".earned", data.getDouble(base + ".earned", 0) + net);
        data.set(base + ".km", data.getDouble(base + ".km", 0) + km);
        Player dp = Bukkit.getPlayer(du);
        if (dp != null) data.set(base + ".name", dp.getName());
        else if (!data.contains(base + ".name")) data.set(base + ".name", Bukkit.getOfflinePlayer(du).getName() == null ? du.toString().substring(0, 8) : Bukkit.getOfflinePlayer(du).getName());
        markDirty();
        note(dp, NotifyModule.Type.MONEY, "Taxi", "Ai incasat " + (long) Math.round(net) + " lei de la " + clientName + " (" + String.format(Locale.ROOT, "%.2f", km) + " km, " + secs / 60 + " min).");
        Player cp = Bukkit.getPlayer(cu);
        note(cp, NotifyModule.Type.OK, "Taxi", "Ai platit " + amount + " lei pentru cursa. Multumim!");
        refresh(dp);
        refresh(cp);
        return Boolean.TRUE;
    }

    private void unpaid(UUID du, UUID cu, int amount, String why) {
        Player dp = Bukkit.getPlayer(du);
        Player cp = Bukkit.getPlayer(cu);
        note(dp, NotifyModule.Type.BAD, "Taxi", "Clientul nu a platit cursa de " + amount + " lei.");
        note(cp, NotifyModule.Type.BAD, "Taxi", "Cursa de " + amount + " lei nu a fost platita: " + why);
    }

    // ------------------------------------------------------------------ taximetru (tick)

    private void tick() {
        long now = System.currentTimeMillis();
        long ttl = (long) (cfg.getDouble("request-ttl-seconds", 120) * 1000);
        boolean changed = false;
        for (Req q : new ArrayList<>(reqs.values())) {
            Player cp = Bukkit.getPlayer(q.client);
            if (cp == null || now - q.at > ttl) {
                reqs.remove(q.client);
                changed = true;
                if (cp != null) {
                    note(cp, NotifyModule.Type.WARN, "Taxi", "Cererea de taxi a expirat.");
                    hudNone(cp);
                    refresh(cp);
                }
                if (q.driver != null) {
                    Player dp = Bukkit.getPlayer(q.driver);
                    if (dp != null) {
                        say(dp, "Cererea lui " + q.name + " a expirat.");
                        hudNone(dp);
                    }
                }
            }
        }
        if (changed) refreshDrivers();
        for (Req q : new ArrayList<>(reqs.values())) {
            if (q.driver == null) continue;
            if (Bukkit.getPlayer(q.driver) == null || !duty.contains(q.driver)) {
                q.driver = null;
                q.arrived = false;
                Player cp = Bukkit.getPlayer(q.client);
                if (cp != null) {
                    say(cp, "Taximetristul nu mai e disponibil. Cererea ta ramane activa.");
                    hudNone(cp);
                }
                continue;
            }
            pushPickup(q);
        }
        double leave = cfg.getDouble("leave-radius", 15);
        for (Ride r : new ArrayList<>(rides.values())) {
            Player dp = Bukkit.getPlayer(r.driver);
            Player cp = Bukkit.getPlayer(r.client);
            if (dp == null || cp == null) {
                endRide(r, false, "Cineva a iesit de pe server.");
                continue;
            }
            double dt = Math.max(0.05, (now - r.lastAt) / 1000.0);
            r.lastAt = now;
            Location cur = dp.getLocation();
            if (r.last != null && r.last.getWorld() == cur.getWorld()) {
                double d = cur.distance(r.last);
                if (d <= 50.0) {
                    if (d < 0.35) r.wait += dt;
                    else r.km += d / 1000.0;
                }
            }
            r.last = cur;
            if (cp.getWorld() != dp.getWorld() || cp.getLocation().distance(cur) > leave) r.away++;
            else r.away = 0;
            if (r.away >= 4) {
                endRide(r, true, cp.getName() + " a coborat / s-a departat de taxi.");
                continue;
            }
            pushRide(r, dp, cp);
        }
    }

    private void pushPickup(Req q) {
        Player dp = Bukkit.getPlayer(q.driver);
        Player cp = Bukkit.getPlayer(q.client);
        if (dp == null || cp == null) return;
        int d = dist(dp, cp);
        Location cl = cp.getLocation();
        Location dl = dp.getLocation();
        JsonObject toDrv = new JsonObject();
        toDrv.addProperty("mode", "pickup");
        toDrv.addProperty("role", "driver");
        toDrv.addProperty("name", cp.getName());
        toDrv.addProperty("same", d >= 0);
        toDrv.addProperty("dist", d);
        toDrv.addProperty("x", cl.getX());
        toDrv.addProperty("z", cl.getZ());
        send(dp, "hud", toDrv);
        JsonObject toCli = new JsonObject();
        toCli.addProperty("mode", "pickup");
        toCli.addProperty("role", "client");
        toCli.addProperty("name", dp.getName());
        toCli.addProperty("same", d >= 0);
        toCli.addProperty("dist", d);
        toCli.addProperty("x", dl.getX());
        toCli.addProperty("z", dl.getZ());
        send(cp, "hud", toCli);
        if (d >= 0 && d <= 6 && !q.arrived) {
            q.arrived = true;
            note(cp, NotifyModule.Type.OK, "Taxi", "Taxi-ul a ajuns la tine (" + dp.getName() + ").");
            note(dp, NotifyModule.Type.INFO, "Taxi", "Ai ajuns la " + cp.getName() + ". Porneste cursa cu /taxi start.");
        }
    }

    private void pushRide(Ride r, Player dp, Player cp) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", "ride");
        o.addProperty("fare", fareOf(r));
        o.addProperty("km", Math.round(r.km * 100.0) / 100.0);
        o.addProperty("secs", Math.max(0L, (System.currentTimeMillis() - r.startAt) / 1000L));
        o.addProperty("wait", Math.round(r.wait));
        o.addProperty("role", "driver");
        o.addProperty("name", cp.getName());
        send(dp, "hud", o);
        JsonObject c = new JsonObject();
        c.addProperty("mode", "ride");
        c.addProperty("fare", fareOf(r));
        c.addProperty("km", Math.round(r.km * 100.0) / 100.0);
        c.addProperty("secs", Math.max(0L, (System.currentTimeMillis() - r.startAt) / 1000L));
        c.addProperty("wait", Math.round(r.wait));
        c.addProperty("role", "client");
        c.addProperty("name", dp.getName());
        send(cp, "hud", c);
    }

    // ------------------------------------------------------------------ statistici

    /** {nume, curse, castig} pentru primii n soferi dupa castig. */
    private List<String[]> topRows(int n) {
        List<String[]> rows = new ArrayList<>();
        ConfigurationSection sec = data.getConfigurationSection("drivers");
        if (sec == null) return rows;
        for (String k : sec.getKeys(false)) {
            String name = sec.getString(k + ".name", k.length() > 8 ? k.substring(0, 8) : k);
            rows.add(new String[] {name, String.valueOf(sec.getInt(k + ".rides", 0)), String.valueOf(Math.round(sec.getDouble(k + ".earned", 0)))});
        }
        Collections.sort(rows, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return Long.compare(Long.parseLong(b[2]), Long.parseLong(a[2]));
            }
        });
        return rows.size() > n ? new ArrayList<>(rows.subList(0, n)) : rows;
    }

    private void top(Player p) {
        List<String[]> rows = topRows(10);
        if (rows.isEmpty()) {
            say(p, "Nu exista inca nicio cursa inregistrata.");
            return;
        }
        p.sendMessage("§e§lTOP TAXIMETRISTI");
        int i = 1;
        for (String[] r : rows) {
            p.sendMessage("§7" + i + ". §f" + r[0] + " §7- " + r[1] + " curse, §a" + r[2] + " lei");
            i++;
        }
    }

    private void markDirty() {
        if (saveScheduled) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveScheduled = false;
            saveNow();
        }, 200L);
    }

    private void saveNow() {
        try {
            data.save(dataFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Nu pot salva taxi-data.yml: " + e);
        }
    }
}
