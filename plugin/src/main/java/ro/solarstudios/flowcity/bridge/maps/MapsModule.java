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

    /** Tipuri de zone (ca in EGK): id, nume, regula. Culorile sunt pe client. */
    private static final String[][] ZONE_TYPES = {
        {"1", "Periculoasa", "Jaf permis"}, {"2", "Sigura", "Jaf interzis"}, {"3", "Neutra", "Reguli normale"}, {"4", "Libera", "Fara restrictii"}};

    private static final class Zone {
        int id;
        String name;
        int type;
        String world;
        double[] xs;
        double[] zs;
    }

    private final List<Zone> zones = new CopyOnWriteArrayList<>();

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
        try {
            plugin.db().async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_zones (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, type INTEGER NOT NULL, world TEXT NOT NULL, pts TEXT NOT NULL)");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("eMaps: nu pot crea tabela de zone: " + e);
        }
        reload(false);
        reloadZones(false);

        on("pois", (p, j) -> sendPois(p));
        on("zone_save", (p, j) -> {
            if (!p.hasPermission("flowcity.maps.admin")) {
                plugin.channel().toast(p, "Nu ai permisiunea.", true);
                return;
            }
            final int id = integer(j, "id", 0);
            final String name = str(j, "name", "").trim();
            final int type = integer(j, "type", 3);
            if (name.isEmpty() || name.length() > 40 || type < 1 || type > ZONE_TYPES.length || !j.has("pts") || !j.get("pts").isJsonArray()) {
                plugin.channel().toast(p, "Zona invalida (nume/tip/puncte).", true);
                return;
            }
            JsonArray arr = j.getAsJsonArray("pts");
            if (arr.size() < 3 || arr.size() > 64) {
                plugin.channel().toast(p, "O zona are intre 3 si 64 de colturi.", true);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JsonArray pt = arr.get(i).getAsJsonArray();
                int x = (int) Math.round(pt.get(0).getAsDouble());
                int z = (int) Math.round(pt.get(1).getAsDouble());
                if (Math.abs(x) > 30000000 || Math.abs(z) > 30000000) return;
                if (i > 0) sb.append(';');
                sb.append(x).append(',').append(z);
            }
            final String pts = sb.toString();
            final String world = p.getWorld().getName();
            plugin.db().async(c -> {
                if (id > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE core_zones SET name=?, type=?, pts=? WHERE id=?")) {
                        ps.setString(1, name);
                        ps.setInt(2, type);
                        ps.setString(3, pts);
                        ps.setInt(4, id);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_zones(name, type, world, pts) VALUES (?,?,?,?)")) {
                        ps.setString(1, name);
                        ps.setInt(2, type);
                        ps.setString(3, world);
                        ps.setString(4, pts);
                        ps.executeUpdate();
                    }
                }
                return null;
            }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                reloadZones(true);
                plugin.channel().toast(p, id > 0 ? "Zona actualizata." : "Zona adaugata.", false);
            }));
        });
        on("zone_del", (p, j) -> {
            if (!p.hasPermission("flowcity.maps.admin")) {
                plugin.channel().toast(p, "Nu ai permisiunea.", true);
                return;
            }
            final int id = integer(j, "id", 0);
            plugin.db().async(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_zones WHERE id = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                return null;
            }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                reloadZones(true);
                plugin.channel().toast(p, "Zona stearsa.", false);
            }));
        });
        on("poi_save", (p, j) -> {
            if (!p.hasPermission("flowcity.maps.admin")) {
                plugin.channel().toast(p, "Nu ai permisiunea.", true);
                return;
            }
            final int id = integer(j, "id", 0);
            String nm = str(j, "name", "").trim();
            final String type = str(j, "type", "altele").toLowerCase(Locale.ROOT);
            if (nm.isEmpty() || nm.length() > 40 || !TYPES.contains(type)) {
                plugin.channel().toast(p, "Nume sau tip invalid.", true);
                return;
            }
            final String name = nm;
            final double x = num(j, "x", p.getLocation().getX());
            final double y = num(j, "y", p.getLocation().getY());
            final double z = num(j, "z", p.getLocation().getZ());
            final String world = p.getWorld().getName();
            plugin.db().async(c -> {
                if (id > 0) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE core_pois SET name=?, type=?, x=?, y=?, z=? WHERE id=?")) {
                        ps.setString(1, name);
                        ps.setString(2, type);
                        ps.setDouble(3, x);
                        ps.setDouble(4, y);
                        ps.setDouble(5, z);
                        ps.setInt(6, id);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_pois(name, type, world, x, y, z) VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, name);
                        ps.setString(2, type);
                        ps.setString(3, world);
                        ps.setDouble(4, x);
                        ps.setDouble(5, y);
                        ps.setDouble(6, z);
                        ps.executeUpdate();
                    }
                }
                return null;
            }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                reload(true);
                plugin.channel().toast(p, id > 0 ? "Punct actualizat." : "Punct adaugat.", false);
            }));
        });
        on("poi_del", (p, j) -> {
            if (!p.hasPermission("flowcity.maps.admin")) {
                plugin.channel().toast(p, "Nu ai permisiunea.", true);
                return;
            }
            final int id = integer(j, "id", 0);
            plugin.db().async(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_pois WHERE id = ?")) {
                    ps.setInt(1, id);
                    ps.executeUpdate();
                }
                return null;
            }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                reload(true);
                plugin.channel().toast(p, "Punct sters.", false);
            }));
        });
        on("give", (p, j) -> {
            if (!p.hasPermission("flowcity.maps.admin")) return;
            String item = str(j, "item", "");
            if (!Arrays.asList("gps_wrench", "gps_path", "gps_oneway", "gps_twoway", "gps_intersection").contains(item)) return;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + p.getName() + " flowcitycore:" + item + (item.equals("gps_wrench") ? "" : " 16"));
            plugin.channel().toast(p, "Primit: " + item, false);
        });
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
            if (a.length >= 1 && a[0].equalsIgnoreCase("zona")) {
                String[] z = zoneAt(p.getWorld().getName(), p.getLocation().getX(), p.getLocation().getZ());
                p.sendMessage(z == null ? "\u00a77Esti in afara zonelor marcate." : "\u00a7aZona: \u00a7f" + z[0] + " \u00a77(" + z[1] + " - " + z[2] + ")");
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
            if (plugin.channel().hasMod(p)) {
                JsonObject o = new JsonObject();
                o.addProperty("editor", a.length >= 1 && a[0].equalsIgnoreCase("editor") && p.hasPermission("flowcity.maps.admin"));
                send(p, "open", o);
            }
            else p.sendMessage("§cHarta necesita modul FlowCity pe client.");
            return true;
        }, (s, c, l, a) -> {
            List<String> out = new ArrayList<>();
            if (a.length == 1) out.addAll(Arrays.asList("poi", "gps", "editor", "zona"));
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

    /** {nume, tip, regula} al zonei in care se afla punctul, sau null. Poate fi apelata si din alte module (ex. protectie jaf). */
    public String[] zoneAt(String world, double x, double z) {
        for (int k = zones.size() - 1; k >= 0; k--) {
            Zone zn = zones.get(k);
            if (!zn.world.equals(world)) continue;
            boolean in = false;
            for (int i = 0, j = zn.xs.length - 1; i < zn.xs.length; j = i++) {
                if ((zn.zs[i] > z) != (zn.zs[j] > z) && x < (zn.xs[j] - zn.xs[i]) * (z - zn.zs[i]) / (zn.zs[j] - zn.zs[i]) + zn.xs[i]) in = !in;
            }
            if (in) {
                String[] t = ZONE_TYPES[Math.max(0, Math.min(ZONE_TYPES.length - 1, zn.type - 1))];
                return new String[] {zn.name, t[1], t[2]};
            }
        }
        return null;
    }

    private void reloadZones(boolean push) {
        try {
            List<Zone> fresh = plugin.db().async(c -> {
                List<Zone> l = new ArrayList<>();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, name, type, world, pts FROM core_zones ORDER BY id")) {
                    while (rs.next()) {
                        try {
                            Zone zn = new Zone();
                            zn.id = rs.getInt(1);
                            zn.name = rs.getString(2);
                            zn.type = rs.getInt(3);
                            zn.world = rs.getString(4);
                            String[] pp = rs.getString(5).split(";");
                            zn.xs = new double[pp.length];
                            zn.zs = new double[pp.length];
                            for (int i = 0; i < pp.length; i++) {
                                String[] xz = pp[i].split(",");
                                zn.xs[i] = Double.parseDouble(xz[0]);
                                zn.zs[i] = Double.parseDouble(xz[1]);
                            }
                            if (pp.length >= 3) l.add(zn);
                        } catch (RuntimeException ignored) {
                            // rand stricat: il sarim
                        }
                    }
                }
                return l;
            }).get(8, TimeUnit.SECONDS);
            zones.clear();
            zones.addAll(fresh);
        } catch (Exception e) {
            plugin.getLogger().warning("eMaps: nu pot citi zonele: " + e);
        }
        if (push) for (Player p : Bukkit.getOnlinePlayers()) if (plugin.channel().hasMod(p)) sendZones(p);
    }

    private void sendZones(Player p) {
        JsonArray arr = new JsonArray();
        String w = p.getWorld().getName();
        for (Zone zn : zones) {
            if (!zn.world.equals(w)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", zn.id);
            o.addProperty("name", zn.name);
            o.addProperty("type", zn.type);
            JsonArray pts = new JsonArray();
            for (int i = 0; i < zn.xs.length; i++) {
                JsonArray pt = new JsonArray();
                pt.add(zn.xs[i]);
                pt.add(zn.zs[i]);
                pts.add(pt);
            }
            o.add("pts", pts);
            arr.add(o);
        }
        JsonObject d = new JsonObject();
        d.add("list", arr);
        send(p, "zones", d);
    }

    private void sendPois(Player p) {
        sendZones(p);
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
        d.addProperty("admin", p.hasPermission("flowcity.maps.admin"));
        send(p, "pois", d);
    }

    @Override
    public void onClientReady(Player p) {
        sendPois(p);
    }
}
