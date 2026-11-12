package ro.solarstudios.flowcity.bridge.maps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** eMaps (server): puncte de interes (primarie, banca, ATM...) si GPS. Harta/minimap/ruta se calculeaza pe client. */
public final class MapsModule extends JsonModule {
    private static final List<String> TYPES = Arrays.asList("banca", "atm", "garaj", "casa", "benzinarie", "restaurant", "primarie", "spital", "politie", "dealer", "magazin", "supermarket", "mecanic", "job", "special", "biserica", "autobuz", "armurier", "casino", "camion", "aeroport", "pescuit", "colete", "electric", "fermier", "gunoi", "lemnar", "pizza", "santier", "altele");

    private static final class Poi {
        int id;
        String name;
        String type;
        String world;
        double x;
        double y;
        double z;
    }

    private final List<Poi> pois = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
        return "maps";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.maps", "Foloseste harta si GPS", PermissionDefault.TRUE);
        Commands.permission("flowcity.maps.admin", "Administreaza punctele de interes", PermissionDefault.OP);
        try {
            plugin.db().async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_pois (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type TEXT NOT NULL, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL)");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("eMaps: nu pot crea tabela: " + e);
        }
        reload(false);

        on("pois", (p, j) -> sendPois(p));
        on("route", (p, j) -> {
            double tx = num(j, "tx", 0);
            double tz = num(j, "tz", 0);
            JsonObject o = new JsonObject();
            double[][] r = gpsRoute(p, tx, tz);
            o.addProperty("ok", r != null);
            if (r != null) {
                JsonArray arr = new JsonArray();
                for (double[] pt : r) {
                    JsonArray a = new JsonArray();
                    a.add(pt[0]);
                    a.add(pt[1]);
                    arr.add(a);
                }
                o.add("list", arr);
            }
            send(p, "route", o);
        });

        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onWorld(PlayerChangedWorldEvent e) {
                sendPois(e.getPlayer());
            }
        }, plugin);

        Commands.register(plugin, "harta", "flowcity.maps", (s, c, l, a) -> {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            if (a.length >= 1 && a[0].equalsIgnoreCase("poi")) {
                poiCommand(p, a);
                return true;
            }
            if (a.length >= 1 && a[0].equalsIgnoreCase("gps")) {
                if (!p.hasPermission("flowcity.maps.admin")) {
                    p.sendMessage("\u00a7cNu ai permisiunea.");
                    return true;
                }
                try {
                    Object n = Class.forName("ro.solarstudios.flowcity.content.GpsNetwork").getMethod("count").invoke(null);
                    p.sendMessage("\u00a7aReteaua GPS are \u00a7f" + n + "\u00a7a noduri. Pune blocuri gps_* pe strazi (cu Cheia GPS in mana le vezi ca particule).");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    p.sendMessage("\u00a7cModul FlowCityCore nu e vizibil pe server.");
                }
                return true;
            }
            if (plugin.channel().hasMod(p)) send(p, "open", new JsonObject());
            else p.sendMessage("§cHarta necesita modul FlowCity pe client.");
            return true;
        }, (s, c, l, a) -> {
            List<String> out = new ArrayList<>();
            if (a.length == 1) out.addAll(Arrays.asList("poi", "gps"));
            else if (a.length == 2) out.addAll(Arrays.asList("add", "del", "list"));
            else if (a.length == 3 && a[1].equalsIgnoreCase("add")) out.addAll(TYPES);
            out.removeIf(x -> !x.startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)));
            return out;
        }, "map", "emaps");

        Commands.register(plugin, "gps", "flowcity.maps", (s, c, l, a) -> {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            if (!plugin.channel().hasMod(p)) {
                p.sendMessage("§cGPS necesita modul FlowCity pe client.");
                return true;
            }
            JsonObject o = new JsonObject();
            if (a.length == 0 || a[0].equalsIgnoreCase("stop")) {
                o.addProperty("stop", true);
                send(p, "gps", o);
                p.sendMessage("§7GPS oprit.");
                return true;
            }
            String q = String.join(" ", a).toLowerCase(Locale.ROOT);
            Poi best = null;
            double bestD = Double.MAX_VALUE;
            for (Poi poi : pois) {
                if (!poi.world.equals(p.getWorld().getName())) continue;
                if (!(poi.name.toLowerCase(Locale.ROOT).contains(q) || poi.type.equals(q))) continue;
                double d = p.getLocation().distanceSquared(new Location(p.getWorld(), poi.x, poi.y, poi.z));
                if (d < bestD) {
                    bestD = d;
                    best = poi;
                }
            }
            if (best == null) {
                p.sendMessage("§cNiciun punct gasit pentru \"" + q + "\". Tipuri: " + String.join(", ", TYPES));
                return true;
            }
            o.addProperty("name", best.name);
            o.addProperty("type", best.type);
            o.addProperty("x", best.x);
            o.addProperty("y", best.y);
            o.addProperty("z", best.z);
            send(p, "gps", o);
            p.sendMessage("§aGPS pornit spre §f" + best.name + "§a.");
            return true;
        }, (s, c, l, a) -> {
            List<String> out = new ArrayList<>(TYPES);
            out.add("stop");
            for (Poi poi : pois) out.add(poi.name.toLowerCase(Locale.ROOT).replace(' ', '_'));
            out.removeIf(x -> !x.startsWith(a[a.length - 1].toLowerCase(Locale.ROOT)));
            return out;
        });
    }

    /** Ruta pe reteaua GPS din mod (prin reflexie, ca pluginul sa nu depinda de el). null = fara ruta. */
    private double[][] gpsRoute(Player p, double tx, double tz) {
        if (p.getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) return null;
        try {
            Object r = Class.forName("ro.solarstudios.flowcity.content.GpsNetwork")
                .getMethod("route", double.class, double.class, double.class, double.class)
                .invoke(null, p.getLocation().getX(), p.getLocation().getZ(), tx, tz);
            return (double[][]) r;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private void poiCommand(Player p, String[] a) {
        if (!p.hasPermission("flowcity.maps.admin")) {
            p.sendMessage("§cNu ai permisiunea.");
            return;
        }
        String sub = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "list";
        if (sub.equals("list")) {
            if (pois.isEmpty()) p.sendMessage("§7Niciun punct de interes. Adauga: /harta poi add <tip> <nume>");
            for (Poi poi : pois) p.sendMessage("§7#" + poi.id + " §f" + poi.name + " §8(" + poi.type + ", " + poi.world + " " + (int) poi.x + " " + (int) poi.y + " " + (int) poi.z + ")");
        } else if (sub.equals("add")) {
            if (a.length < 4 || !TYPES.contains(a[2].toLowerCase(Locale.ROOT))) {
                p.sendMessage("§7Folosire: /harta poi add <tip> <nume>   Tipuri: " + String.join(", ", TYPES));
                return;
            }
            final String type = a[2].toLowerCase(Locale.ROOT);
            final String name = String.join(" ", Arrays.copyOfRange(a, 3, a.length));
            final Location loc = p.getLocation();
            plugin.db().async(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_pois(name, type, world, x, y, z) VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, name);
                    ps.setString(2, type);
                    ps.setString(3, loc.getWorld().getName());
                    ps.setDouble(4, loc.getX());
                    ps.setDouble(5, loc.getY());
                    ps.setDouble(6, loc.getZ());
                    ps.executeUpdate();
                }
                return null;
            }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                reload(true);
                p.sendMessage("§aPunct adaugat: §f" + name + " §7(" + type + ")");
            }));
        } else if (sub.equals("del")) {
            int id;
            try {
                id = Integer.parseInt(a.length >= 3 ? a[2].replace("#", "") : "");
            } catch (NumberFormatException e) {
                p.sendMessage("§7Folosire: /harta poi del <id>");
                return;
            }
            final int fid = id;
            plugin.db().async(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_pois WHERE id = ?")) {
                    ps.setInt(1, fid);
                    ps.executeUpdate();
                }
                return null;
            }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                reload(true);
                p.sendMessage("§aPunct sters (#" + fid + ").");
            }));
        } else {
            p.sendMessage("§7Folosire: /harta poi <add|del|list>");
        }
    }

    /** Reincarca lista din DB (la pornire blocant, altfel async) si, optional, o retrimite jucatorilor. */
    private void reload(boolean push) {
        try {
            List<Poi> fresh = plugin.db().async(c -> {
                List<Poi> l = new ArrayList<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, type, world, x, y, z FROM core_pois ORDER BY id")) {
                    while (rs.next()) {
                        Poi p = new Poi();
                        p.id = rs.getInt(1);
                        p.name = rs.getString(2);
                        p.type = rs.getString(3);
                        p.world = rs.getString(4);
                        p.x = rs.getDouble(5);
                        p.y = rs.getDouble(6);
                        p.z = rs.getDouble(7);
                        l.add(p);
                    }
                }
                return l;
            }).get(8, TimeUnit.SECONDS);
            pois.clear();
            pois.addAll(fresh);
        } catch (Exception e) {
            plugin.getLogger().warning("eMaps: nu pot citi punctele: " + e);
        }
        if (push) for (Player p : Bukkit.getOnlinePlayers()) if (plugin.channel().hasMod(p)) sendPois(p);
    }

    private void sendPois(Player p) {
        JsonArray arr = new JsonArray();
        String w = p.getWorld().getName();
        for (Poi poi : pois) {
            if (!poi.world.equals(w)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", poi.id);
            o.addProperty("name", poi.name);
            o.addProperty("type", poi.type);
            o.addProperty("x", poi.x);
            o.addProperty("y", poi.y);
            o.addProperty("z", poi.z);
            arr.add(o);
        }
        JsonObject d = new JsonObject();
        d.add("list", arr);
        send(p, "pois", d);
    }

    @Override
    public void onClientReady(Player p) {
        sendPois(p);
    }
}
