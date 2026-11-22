package ro.solarstudios.flowcity.bridge.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * GPS FlowCity: configuratia (categorii, locatii, zone RP) traieste in gps.yml; ce adauga/muta/sterge staff-ul cu comenzi se
 * pastreaza separat in gps-data.yml (peste gps.yml, ca sa nu pierdem comentariile din gps.yml). Configul se trimite modului
 * la intrare si la fiecare modificare/reload; ecranul si navigarea (directie directa, fara ruta rutiera) sunt in mod.
 *
 * Comenzi: /gps (deschide harta), /gps cancel, /gps lista, /gps categorii, /gps adauga, /gps sterge, /gps muta,
 * /gps activeaza|dezactiveaza, /gps set <jucator> <x> <z> [nume], /gps reload.
 */
public final class GpsModule extends JsonModule {
    private static final String TAG = "§b[GPS] §7";
    private static final String OVERWORLD = "minecraft:overworld";

    /** O locatie efectiva (din gps.yml + modificarile din gps-data.yml). */
    private static final class Loc {
        String id;
        String name;
        String cat;
        String icon = "";
        String color = "";
        String desc = "";
        String dim = OVERWORLD;
        double x;
        double z;
        boolean enabled = true;
        boolean added;
    }

    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;
    private final List<Loc> locs = new ArrayList<>();

    @Override
    public String id() {
        return "gps";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.gps.use", "Poate folosi harta GPS", PermissionDefault.TRUE);
        Commands.permission("flowcity.gps.admin", "Administreaza locatiile GPS (adauga, sterge, muta, reload, set)", PermissionDefault.OP);
        load();
        on("request", (p, j) -> send(p, "config", build()));
        Commands.register(plugin, "gps", "flowcity.gps.use", (s, c, l, a) -> command(s, a), (s, c, l, a) -> complete(s, a), "harta");
    }

    @Override
    public void onClientReady(Player p) {
        send(p, "config", build());
    }

    // ------------------------------------------------------------------ date

    private void load() {
        cfg = ModConfig.load(plugin, "gps");
        dataFile = new File(plugin.getDataFolder(), "gps-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        rebuild();
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva gps-data.yml: " + e);
        }
    }

    private static double d(Object o, double def) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return o == null ? def : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String s(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static boolean b(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        return o == null ? def : "true".equalsIgnoreCase(String.valueOf(o));
    }

    private Loc fromMap(Map<?, ?> m, boolean added) {
        String id = s(m.get("id"), "").trim();
        if (id.isEmpty()) return null;
        Loc l = new Loc();
        l.id = id;
        l.name = s(m.get("name"), id);
        l.cat = s(m.get("category"), "").trim().toUpperCase(Locale.ROOT);
        l.icon = s(m.get("icon"), "");
        l.color = s(m.get("color"), "");
        l.desc = s(m.get("description"), "");
        l.dim = s(m.get("dimension"), OVERWORLD);
        l.x = d(m.get("x"), 0);
        l.z = d(m.get("z"), 0);
        l.enabled = b(m.get("enabled"), true);
        l.added = added;
        return l;
    }

    private void rebuild() {
        locs.clear();
        List<String> deleted = data.getStringList("deleted");
        for (Map<?, ?> m : cfg.getMapList("locations")) {
            Loc l = fromMap(m, false);
            if (l == null || deleted.contains(l.id)) continue;
            ConfigurationSection ov = data.getConfigurationSection("overrides." + l.id);
            if (ov != null) {
                if (ov.contains("x")) l.x = ov.getDouble("x");
                if (ov.contains("z")) l.z = ov.getDouble("z");
                if (ov.contains("dimension")) l.dim = ov.getString("dimension", l.dim);
                if (ov.contains("enabled")) l.enabled = ov.getBoolean("enabled", l.enabled);
            }
            locs.add(l);
        }
        for (Map<?, ?> m : data.getMapList("added")) {
            Loc l = fromMap(m, true);
            if (l != null) locs.add(l);
        }
    }

    private Loc find(String id) {
        for (Loc l : locs) if (l.id.equalsIgnoreCase(id)) return l;
        return null;
    }

    private JsonObject build() {
        JsonObject o = new JsonObject();
        JsonArray groups = new JsonArray();
        for (String g : cfg.getStringList("groups")) groups.add(g);
        o.add("groups", groups);
        JsonArray cats = new JsonArray();
        ConfigurationSection cs = cfg.getConfigurationSection("categories");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(key);
                if (c == null) continue;
                JsonObject j = new JsonObject();
                j.addProperty("id", key.toUpperCase(Locale.ROOT));
                j.addProperty("name", c.getString("name", key));
                j.addProperty("group", c.getString("group", ""));
                j.addProperty("icon", c.getString("icon", "pin"));
                j.addProperty("color", c.getString("color", "#4DA3FF"));
                cats.add(j);
            }
        }
        o.add("cats", cats);
        JsonArray la = new JsonArray();
        for (Loc l : locs) {
            if (!l.enabled) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", l.id);
            j.addProperty("name", l.name);
            j.addProperty("cat", l.cat);
            j.addProperty("x", l.x);
            j.addProperty("z", l.z);
            j.addProperty("dim", l.dim);
            if (!l.icon.isEmpty()) j.addProperty("icon", l.icon);
            if (!l.color.isEmpty()) j.addProperty("color", l.color);
            if (!l.desc.isEmpty()) j.addProperty("desc", l.desc);
            la.add(j);
        }
        o.add("locs", la);
        JsonArray za = new JsonArray();
        for (Map<?, ?> m : cfg.getMapList("zones")) {
            if (!b(m.get("enabled"), true)) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", s(m.get("id"), "zona"));
            j.addProperty("name", s(m.get("name"), "Zona"));
            j.addProperty("type", "ILEGAL".equalsIgnoreCase(s(m.get("type"), "LEGAL")) || "ILLEGAL".equalsIgnoreCase(s(m.get("type"), "")) ? "ILEGAL" : "LEGAL");
            j.addProperty("dim", s(m.get("dimension"), OVERWORLD));
            String color = s(m.get("color"), "");
            if (!color.isEmpty()) j.addProperty("color", color);
            JsonArray pts = new JsonArray();
            Object po = m.get("points");
            if (po instanceof List) {
                for (Object e : (List<?>) po) {
                    if (e instanceof List && ((List<?>) e).size() >= 2) {
                        JsonArray p = new JsonArray();
                        p.add(d(((List<?>) e).get(0), 0));
                        p.add(d(((List<?>) e).get(1), 0));
                        pts.add(p);
                    }
                }
            } else if (m.get("x1") != null && m.get("z1") != null && m.get("x2") != null && m.get("z2") != null) {
                double x1 = d(m.get("x1"), 0), z1 = d(m.get("z1"), 0), x2 = d(m.get("x2"), 0), z2 = d(m.get("z2"), 0);
                double[][] c = {{x1, z1}, {x2, z1}, {x2, z2}, {x1, z2}};
                for (double[] q : c) {
                    JsonArray p = new JsonArray();
                    p.add(q[0]);
                    p.add(q[1]);
                    pts.add(p);
                }
            }
            if (pts.size() < 3) continue;
            j.add("pts", pts);
            za.add(j);
        }
        o.add("zones", za);
        JsonObject set = new JsonObject();
        set.addProperty("arrive", cfg.getDouble("settings.arrive-distance", 8));
        set.addProperty("showZones", cfg.getBoolean("settings.show-zones", true));
        set.addProperty("zoom", cfg.getDouble("settings.default-zoom", 1.2));
        o.add("settings", set);
        return o;
    }

    private void pushAll() {
        JsonObject o = build();
        for (Player p : Bukkit.getOnlinePlayers()) send(p, "config", o);
    }

    // ------------------------------------------------------------------ comenzi

    private boolean admin(CommandSender s) {
        if (s.hasPermission("flowcity.gps.admin")) return true;
        s.sendMessage("§cNu ai permisiunea.");
        return false;
    }

    private static String slug(String s) {
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return t.isEmpty() ? "loc" : t;
    }

    private boolean command(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player)) {
                s.sendMessage("Comanda /gps deschide harta in joc. Admin: /gps lista | adauga | sterge | muta | reload");
                return true;
            }
            Player p = (Player) s;
            send(p, "open", new JsonObject());
            s.sendMessage(TAG + "Se deschide harta... (dacă nu apare: tasta M sau §f.gps§7 în chat)");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "cancel":
            case "anuleaza": {
                Player t = s instanceof Player ? (Player) s : null;
                if (a.length >= 2) {
                    if (!admin(s)) return true;
                    t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) {
                        s.sendMessage("§cJucătorul nu e online.");
                        return true;
                    }
                }
                if (t == null) return true;
                send(t, "cancel", new JsonObject());
                if (t != s) s.sendMessage(TAG + "GPS anulat pentru " + t.getName() + ".");
                return true;
            }
            case "lista": {
                if (!admin(s)) return true;
                String filter = a.length >= 2 ? a[1].toUpperCase(Locale.ROOT) : "";
                int n = 0;
                for (Loc l : locs) {
                    if (!filter.isEmpty() && !l.cat.equals(filter)) continue;
                    if (n++ >= 60) {
                        s.sendMessage("§7... (filtrează cu /gps lista <CATEGORIE>)");
                        break;
                    }
                    s.sendMessage("§f" + l.id + " §7" + l.name + " [" + l.cat + "] " + (int) l.x + " " + (int) l.z + (l.enabled ? "" : " §c(inactiv)") + (l.added ? " §8(adăugat)" : ""));
                }
                if (n == 0) s.sendMessage(TAG + "Nu există locații" + (filter.isEmpty() ? "" : " în " + filter) + ". Adaugă: /gps adauga <CATEGORIE> <nume>");
                return true;
            }
            case "categorii": {
                ConfigurationSection cs = cfg.getConfigurationSection("categories");
                s.sendMessage(TAG + "Categorii: §f" + (cs == null ? "-" : String.join(", ", cs.getKeys(false))));
                return true;
            }
            case "reload": {
                if (!admin(s)) return true;
                load();
                pushAll();
                s.sendMessage(TAG + "Reîncărcat: " + locs.size() + " locații.");
                return true;
            }
            case "adauga": {
                if (!admin(s)) return true;
                if (!(s instanceof Player) || a.length < 3) {
                    s.sendMessage("Folosire: /gps adauga <CATEGORIE> <nume...> (poziția ta)");
                    return true;
                }
                Player p = (Player) s;
                String cat = a[1].toUpperCase(Locale.ROOT);
                ConfigurationSection cs = cfg.getConfigurationSection("categories");
                boolean known = false;
                if (cs != null) for (String k : cs.getKeys(false)) if (k.equalsIgnoreCase(cat)) known = true;
                if (!known) {
                    s.sendMessage("§cCategorie necunoscută. §7Vezi /gps categorii");
                    return true;
                }
                String name = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                String base = slug(name);
                String id = base;
                int i = 2;
                while (find(id) != null) id = base + "_" + (i++);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", name);
                m.put("category", cat);
                m.put("x", Math.round(p.getLocation().getX()));
                m.put("z", Math.round(p.getLocation().getZ()));
                m.put("dimension", dimOf(p));
                m.put("enabled", true);
                List<Map<?, ?>> added = new ArrayList<>(data.getMapList("added"));
                added.add(m);
                data.set("added", added);
                saveData();
                rebuild();
                pushAll();
                s.sendMessage(TAG + "Adăugat §f" + id + "§7 la " + (long) p.getLocation().getX() + " " + (long) p.getLocation().getZ() + ".");
                return true;
            }
            case "sterge": {
                if (!admin(s)) return true;
                if (a.length < 2) {
                    s.sendMessage("Folosire: /gps sterge <id>");
                    return true;
                }
                Loc l = find(a[1]);
                if (l == null) {
                    s.sendMessage("§cNu există locația §f" + a[1] + "§c.");
                    return true;
                }
                if (l.added) {
                    List<Map<?, ?>> added = new ArrayList<>();
                    for (Map<?, ?> m : data.getMapList("added")) if (!l.id.equalsIgnoreCase(s(m.get("id"), ""))) added.add(m);
                    data.set("added", added);
                } else {
                    List<String> del = new ArrayList<>(data.getStringList("deleted"));
                    if (!del.contains(l.id)) del.add(l.id);
                    data.set("deleted", del);
                }
                saveData();
                rebuild();
                pushAll();
                s.sendMessage(TAG + "Șters: §f" + l.id);
                return true;
            }
            case "muta": {
                if (!admin(s)) return true;
                if (!(s instanceof Player) || a.length < 2) {
                    s.sendMessage("Folosire: /gps muta <id> (la poziția ta)");
                    return true;
                }
                Player p = (Player) s;
                Loc l = find(a[1]);
                if (l == null) {
                    s.sendMessage("§cNu există locația §f" + a[1] + "§c.");
                    return true;
                }
                long x = Math.round(p.getLocation().getX());
                long z = Math.round(p.getLocation().getZ());
                if (l.added) {
                    List<Map<?, ?>> added = new ArrayList<>();
                    for (Map<?, ?> m : data.getMapList("added")) {
                        if (l.id.equalsIgnoreCase(s(m.get("id"), ""))) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), e.getValue());
                            c.put("x", x);
                            c.put("z", z);
                            c.put("dimension", dimOf(p));
                            added.add(c);
                        } else {
                            added.add(m);
                        }
                    }
                    data.set("added", added);
                } else {
                    data.set("overrides." + l.id + ".x", x);
                    data.set("overrides." + l.id + ".z", z);
                    data.set("overrides." + l.id + ".dimension", dimOf(p));
                }
                saveData();
                rebuild();
                pushAll();
                s.sendMessage(TAG + "Mutat §f" + l.id + "§7 la " + x + " " + z + ".");
                return true;
            }
            case "activeaza":
            case "dezactiveaza": {
                if (!admin(s)) return true;
                if (a.length < 2) {
                    s.sendMessage("Folosire: /gps " + sub + " <id>");
                    return true;
                }
                Loc l = find(a[1]);
                if (l == null) {
                    s.sendMessage("§cNu există locația §f" + a[1] + "§c.");
                    return true;
                }
                boolean on = sub.equals("activeaza");
                if (l.added) {
                    List<Map<?, ?>> added = new ArrayList<>();
                    for (Map<?, ?> m : data.getMapList("added")) {
                        Map<String, Object> c = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), e.getValue());
                        if (l.id.equalsIgnoreCase(s(m.get("id"), ""))) c.put("enabled", on);
                        added.add(c);
                    }
                    data.set("added", added);
                } else {
                    data.set("overrides." + l.id + ".enabled", on);
                }
                saveData();
                rebuild();
                pushAll();
                s.sendMessage(TAG + l.id + (on ? " activat." : " dezactivat."));
                return true;
            }
            case "set": {
                if (!admin(s)) return true;
                if (a.length < 4) {
                    s.sendMessage("Folosire: /gps set <jucător> <x> <z> [nume...]");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage("§cJucătorul nu e online.");
                    return true;
                }
                double x;
                double z;
                try {
                    x = Double.parseDouble(a[2]);
                    z = Double.parseDouble(a[3]);
                } catch (NumberFormatException e) {
                    s.sendMessage("§cCoordonate invalide.");
                    return true;
                }
                JsonObject o = new JsonObject();
                o.addProperty("name", a.length > 4 ? String.join(" ", Arrays.copyOfRange(a, 4, a.length)) : "Destinație");
                o.addProperty("x", x);
                o.addProperty("z", z);
                o.addProperty("dim", dimOf(t));
                send(t, "set", o);
                s.sendMessage(TAG + "GPS setat pentru " + t.getName() + ".");
                return true;
            }
            default:
                s.sendMessage(TAG + "/gps §8- §7harta | §f/gps cancel §8- §7anulează navigarea");
                if (s.hasPermission("flowcity.gps.admin")) {
                    s.sendMessage("§7Admin: §f/gps lista [CAT] §8| §f/gps categorii §8| §f/gps adauga <CAT> <nume> §8| §f/gps sterge <id> §8| §f/gps muta <id> §8| §f/gps activeaza|dezactiveaza <id> §8| §f/gps set <jucător> <x> <z> [nume] §8| §f/gps reload");
                }
                return true;
        }
    }

    private static String dimOf(Player p) {
        switch (p.getWorld().getEnvironment()) {
            case NETHER:
                return "minecraft:the_nether";
            case THE_END:
                return "minecraft:the_end";
            default:
                return OVERWORLD;
        }
    }

    private List<String> complete(CommandSender s, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission("flowcity.gps.admin");
        if (a.length == 1) {
            out.add("cancel");
            out.add("categorii");
            if (admin) out.addAll(Arrays.asList("lista", "adauga", "sterge", "muta", "activeaza", "dezactiveaza", "set", "reload"));
        } else if (a.length == 2 && admin) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("adauga") || sub.equals("lista")) {
                ConfigurationSection cs = cfg.getConfigurationSection("categories");
                if (cs != null) out.addAll(cs.getKeys(false));
            } else if (sub.equals("sterge") || sub.equals("muta") || sub.equals("activeaza") || sub.equals("dezactiveaza")) {
                for (Loc l : locs) out.add(l.id);
            } else if (sub.equals("set") || sub.equals("cancel")) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
        }
        return Commands.filter(out, a.length == 0 ? "" : a[a.length - 1]);
    }
}
