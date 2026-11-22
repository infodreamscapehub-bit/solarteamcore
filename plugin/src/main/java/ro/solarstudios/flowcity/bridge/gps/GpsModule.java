package ro.solarstudios.flowcity.bridge.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * GPS FlowCity (server): date in fisiere JSON separate (vezi {@link GpsStore}), trimise modulului la intrare si dupa fiecare
 * modificare. Editarea se face din joc (GPS > EDITOR HARTA, doar flowcity.gps.admin): clientul trimite mesajul "edit", iar
 * serverul RE-VERIFICA permisiunea la fiecare operatie, valideaza tot si salveaza (backup + scriere atomica).
 * Comenzile /gps raman ca optiune suplimentara (nu mai sunt necesare pentru administrare).
 */
public final class GpsModule extends JsonModule {
    private static final String TAG = "§b[GPS] §7";
    private static final String PERM_ADMIN = "flowcity.gps.admin";
    private static final String OVERWORLD = GpsStore.OVERWORLD;

    private GpsStore store;
    private final Map<UUID, Long> lastEdit = new HashMap<>();
    private String okMsg = "";

    @Override
    public String id() {
        return "gps";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.gps.use", "Poate folosi harta GPS", PermissionDefault.TRUE);
        Commands.permission(PERM_ADMIN, "Editeaza harta GPS (locatii, zone, categorii) din interfata", PermissionDefault.OP);
        load();
        on("request", (p, j) -> send(p, "config", build(p)));
        on("edit", this::edit);
        Commands.register(plugin, "gps", "flowcity.gps.use", (s, c, l, a) -> command(s, a), (s, c, l, a) -> complete(s, a), "harta");
    }

    @Override
    public void onClientReady(Player p) {
        send(p, "config", build(p));
    }

    private void load() {
        YamlConfiguration cfg = ModConfig.load(plugin, "gps");
        YamlConfiguration legacy = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "gps-data.yml"));
        store = new GpsStore(plugin.getDataFolder(), plugin.getLogger());
        store.load(cfg, legacy);
    }

    private static boolean isAdmin(Player p) {
        return p != null && p.hasPermission(PERM_ADMIN);
    }

    // ------------------------------------------------------------------ configul trimis clientilor

    private JsonObject build(Player viewer) {
        boolean admin = isAdmin(viewer);
        JsonObject o = new JsonObject();
        o.addProperty("admin", admin);
        JsonArray groups = new JsonArray();
        for (String g : store.groups) groups.add(g);
        o.add("groups", groups);
        JsonArray cats = new JsonArray();
        for (GpsStore.Cat c : store.cats) {
            if (!admin && !c.enabled) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id);
            j.addProperty("name", c.name);
            j.addProperty("group", c.group);
            j.addProperty("icon", c.icon);
            j.addProperty("color", c.color);
            j.addProperty("enabled", c.enabled);
            cats.add(j);
        }
        o.add("cats", cats);
        JsonArray la = new JsonArray();
        for (GpsStore.Loc l : store.locs) {
            if (!admin) {
                if (!l.enabled) continue;
                GpsStore.Cat c = store.cat(l.cat);
                if (c != null && !c.enabled) continue;
            }
            JsonObject j = new JsonObject();
            j.addProperty("id", l.id);
            j.addProperty("name", l.name);
            j.addProperty("cat", l.cat);
            j.addProperty("x", l.x);
            j.addProperty("z", l.z);
            j.addProperty("dim", l.dim);
            j.addProperty("size", l.size);
            j.addProperty("enabled", l.enabled);
            j.addProperty("hidden", l.hidden);
            if (!l.icon.isEmpty()) j.addProperty("icon", l.icon);
            if (!l.color.isEmpty()) j.addProperty("color", l.color);
            if (!l.desc.isEmpty()) j.addProperty("desc", l.desc);
            la.add(j);
        }
        o.add("locs", la);
        JsonArray za = new JsonArray();
        for (GpsStore.Zone z : store.zones) {
            if (!admin && !z.enabled) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", z.id);
            j.addProperty("name", z.name);
            j.addProperty("type", z.type);
            j.addProperty("dim", z.dim);
            j.addProperty("alpha", z.alpha);
            j.addProperty("enabled", z.enabled);
            if (!z.color.isEmpty()) j.addProperty("color", z.color);
            if (!z.outline.isEmpty()) j.addProperty("outline", z.outline);
            if (!z.desc.isEmpty()) j.addProperty("desc", z.desc);
            JsonArray pts = new JsonArray();
            for (double[] q : z.pts) {
                JsonArray p = new JsonArray();
                p.add(q[0]);
                p.add(q[1]);
                pts.add(p);
            }
            j.add("pts", pts);
            za.add(j);
        }
        o.add("zones", za);
        o.add("icons", store.icons);
        JsonObject set = new JsonObject();
        set.addProperty("arrive", store.settings.arrive);
        set.addProperty("showZones", store.settings.showZones);
        set.addProperty("zoom", store.settings.zoom);
        o.add("settings", set);
        return o;
    }

    private void pushAll() {
        for (Player p : Bukkit.getOnlinePlayers()) send(p, "config", build(p));
    }

    // ------------------------------------------------------------------ editare din interfata

    private void reply(Player p, String op, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("op", op);
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "editres", o);
    }

    private void edit(Player p, JsonObject j) {
        String op = str(j, "op", "");
        if (!isAdmin(p)) {
            plugin.getLogger().warning("[GPS] " + p.getName() + " a incercat '" + op + "' fara permisiunea " + PERM_ADMIN + ".");
            reply(p, op, false, "Nu ai permisiunea.");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastEdit.get(p.getUniqueId());
        if (last != null && now - last < 60L) {
            reply(p, op, false, "Prea repede, mai încearcă o dată.");
            return;
        }
        lastEdit.put(p.getUniqueId(), now);
        String err;
        okMsg = "";
        try {
            err = doEdit(op, j);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("[GPS] Eroare la '" + op + "' de la " + p.getName() + ": " + e);
            err = "Eroare internă (vezi consola).";
        }
        if (err != null) {
            reply(p, op, false, err);
            return;
        }
        plugin.getLogger().info("[GPS] " + p.getName() + " " + op + ": " + okMsg);
        reply(p, op, true, okMsg);
        pushAll();
    }

    private static boolean coord(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= 30000000d;
    }

    private static boolean hex(String s) {
        return s != null && s.matches("#[0-9A-Fa-f]{6}");
    }

    private static boolean dimOk(String d) {
        return d != null && d.length() <= 64 && d.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    }

    private static double area(List<double[]> pts) {
        double a = 0;
        for (int i = 0, k = pts.size() - 1; i < pts.size(); k = i++) a += pts.get(k)[0] * pts.get(i)[1] - pts.get(i)[0] * pts.get(k)[1];
        return a / 2d;
    }

    private String applyLoc(GpsStore.Loc l, JsonObject j, boolean create) {
        if (create) {
            for (String k : new String[] {"name", "cat", "x", "z"}) if (!j.has(k)) return "Lipsește câmpul '" + k + "'.";
        }
        if (j.has("name")) {
            String n = GpsStore.cut(str(j, "name", ""), 40);
            if (n.isEmpty()) return "Nume invalid.";
            l.name = n;
        }
        if (j.has("cat")) {
            GpsStore.Cat c = store.cat(str(j, "cat", ""));
            if (c == null) return "Categorie inexistentă.";
            l.cat = c.id;
        }
        if (j.has("desc")) {
            String d = str(j, "desc", "");
            if (d.length() > 120) return "Descriere prea lungă (max 120).";
            l.desc = GpsStore.cut(d, 120);
        }
        if (j.has("icon")) {
            String ic = str(j, "icon", "");
            if (!ic.isEmpty() && !ic.matches("[a-z0-9_]{1,32}")) return "Iconiță invalidă.";
            l.icon = ic;
        }
        if (j.has("color")) {
            String c = str(j, "color", "");
            if (!c.isEmpty() && !hex(c)) return "Culoare invalidă (#RRGGBB).";
            l.color = c.toUpperCase(Locale.ROOT);
        }
        if (j.has("x")) {
            double v = num(j, "x", Double.NaN);
            if (!coord(v)) return "Coordonata X invalidă.";
            l.x = v;
        }
        if (j.has("z")) {
            double v = num(j, "z", Double.NaN);
            if (!coord(v)) return "Coordonata Z invalidă.";
            l.z = v;
        }
        if (j.has("dim")) {
            String d = str(j, "dim", OVERWORLD);
            if (!dimOk(d)) return "Dimensiune invalidă.";
            l.dim = d;
        }
        if (j.has("size")) {
            int s = integer(j, "size", 100);
            if (s < 50 || s > 200) return "Mărime invalidă (50-200).";
            l.size = s;
        }
        if (j.has("enabled")) l.enabled = bool(j, "enabled", true);
        if (j.has("hidden")) l.hidden = bool(j, "hidden", false);
        return null;
    }

    private String applyZone(GpsStore.Zone z, JsonObject j, boolean create) {
        if (create) {
            for (String k : new String[] {"name", "pts"}) if (!j.has(k)) return "Lipsește câmpul '" + k + "'.";
        }
        if (j.has("name")) {
            String n = GpsStore.cut(str(j, "name", ""), 40);
            if (n.isEmpty()) return "Nume invalid.";
            z.name = n;
        }
        if (j.has("type")) {
            String t = str(j, "type", "").toUpperCase(Locale.ROOT);
            if (!GpsStore.ZONE_TYPES.contains(t)) return "Tip de zonă invalid.";
            z.type = t;
        }
        if (j.has("desc")) {
            String d = str(j, "desc", "");
            if (d.length() > 120) return "Descriere prea lungă (max 120).";
            z.desc = GpsStore.cut(d, 120);
        }
        if (j.has("color")) {
            String c = str(j, "color", "");
            if (!c.isEmpty() && !hex(c)) return "Culoare invalidă (#RRGGBB).";
            z.color = c.toUpperCase(Locale.ROOT);
        }
        if (j.has("outline")) {
            String c = str(j, "outline", "");
            if (!c.isEmpty() && !hex(c)) return "Culoare contur invalidă (#RRGGBB).";
            z.outline = c.toUpperCase(Locale.ROOT);
        }
        if (j.has("alpha")) {
            int a = integer(j, "alpha", 22);
            if (a < 5 || a > 90) return "Transparență invalidă (5-90).";
            z.alpha = a;
        }
        if (j.has("dim")) {
            String d = str(j, "dim", OVERWORLD);
            if (!dimOk(d)) return "Dimensiune invalidă.";
            z.dim = d;
        }
        if (j.has("enabled")) z.enabled = bool(j, "enabled", true);
        if (j.has("pts")) {
            JsonElement pe = j.get("pts");
            if (pe == null || !pe.isJsonArray()) return "Puncte invalide.";
            JsonArray a = pe.getAsJsonArray();
            if (a.size() < 3 || a.size() > GpsStore.MAX_PTS) return "Zona are nevoie de 3-" + GpsStore.MAX_PTS + " puncte.";
            List<double[]> np = new ArrayList<>();
            for (JsonElement e : a) {
                if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 2) return "Punct invalid.";
                double x;
                double zz;
                try {
                    x = e.getAsJsonArray().get(0).getAsDouble();
                    zz = e.getAsJsonArray().get(1).getAsDouble();
                } catch (RuntimeException ex) {
                    return "Punct invalid.";
                }
                if (!coord(x) || !coord(zz)) return "Coordonate invalide în zonă.";
                np.add(new double[] {x, zz});
            }
            if (Math.abs(area(np)) < 1.0) return "Zona e degenerată (arie aproape zero).";
            z.pts = np;
        }
        return null;
    }

    private static String slugUpper(String s) {
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        t = t.replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (t.isEmpty()) t = "CAT";
        return t.length() > 22 ? t.substring(0, 22) : t;
    }

    private String applyCat(GpsStore.Cat c, JsonObject j, boolean create) {
        if (create && !j.has("name")) return "Lipsește numele.";
        if (j.has("name")) {
            String n = GpsStore.cut(str(j, "name", ""), 32);
            if (n.isEmpty()) return "Nume invalid.";
            c.name = n;
        }
        if (j.has("group")) {
            String g = GpsStore.cut(str(j, "group", ""), 24).toUpperCase(Locale.ROOT);
            if (!g.isEmpty() && !store.groups.contains(g) && store.groups.size() >= GpsStore.MAX_GROUPS) return "Prea multe grupuri.";
            c.group = g;
        }
        if (j.has("icon")) {
            String ic = str(j, "icon", "");
            if (!ic.matches("[a-z0-9_]{1,32}")) return "Iconiță invalidă.";
            c.icon = ic;
        }
        if (j.has("color")) {
            String col = str(j, "color", "");
            if (!hex(col)) return "Culoare invalidă (#RRGGBB).";
            c.color = col.toUpperCase(Locale.ROOT);
        }
        if (j.has("enabled")) c.enabled = bool(j, "enabled", true);
        return null;
    }

    private String doEdit(String op, JsonObject j) {
        switch (op) {
            case "loc.add": {
                if (store.locs.size() >= GpsStore.MAX_LOCS) return "Prea multe locații (max " + GpsStore.MAX_LOCS + ").";
                GpsStore.Loc l = new GpsStore.Loc();
                String err = applyLoc(l, j, true);
                if (err != null) return err;
                l.id = store.uniqueId(l.name, false);
                store.locs.add(l);
                store.saveLocs(false);
                okMsg = "Locație adăugată: " + l.name;
                return null;
            }
            case "loc.set": {
                GpsStore.Loc l = store.loc(str(j, "id", ""));
                if (l == null) return "Locația nu există.";
                GpsStore.Loc c = l.copy();
                String err = applyLoc(c, j, false);
                if (err != null) return err;
                l.set(c);
                store.saveLocs(false);
                okMsg = "Locație salvată: " + l.name;
                return null;
            }
            case "loc.del": {
                GpsStore.Loc l = store.loc(str(j, "id", ""));
                if (l == null) return "Locația nu există.";
                store.locs.remove(l);
                store.saveLocs(true);
                okMsg = "Locație ștearsă: " + l.name;
                return null;
            }
            case "zone.add": {
                if (store.zones.size() >= GpsStore.MAX_ZONES) return "Prea multe zone (max " + GpsStore.MAX_ZONES + ").";
                GpsStore.Zone z = new GpsStore.Zone();
                String err = applyZone(z, j, true);
                if (err != null) return err;
                z.id = store.uniqueId(z.name, true);
                store.zones.add(z);
                store.saveZones(false);
                okMsg = "Zonă adăugată: " + z.name;
                return null;
            }
            case "zone.set": {
                GpsStore.Zone z = store.zone(str(j, "id", ""));
                if (z == null) return "Zona nu există.";
                GpsStore.Zone c = z.copy();
                String err = applyZone(c, j, false);
                if (err != null) return err;
                z.set(c);
                store.saveZones(false);
                okMsg = "Zonă salvată: " + z.name;
                return null;
            }
            case "zone.del": {
                GpsStore.Zone z = store.zone(str(j, "id", ""));
                if (z == null) return "Zona nu există.";
                store.zones.remove(z);
                store.saveZones(true);
                okMsg = "Zonă ștearsă: " + z.name;
                return null;
            }
            case "cat.add": {
                if (store.cats.size() >= GpsStore.MAX_CATS) return "Prea multe categorii (max " + GpsStore.MAX_CATS + ").";
                GpsStore.Cat c = new GpsStore.Cat();
                String err = applyCat(c, j, true);
                if (err != null) return err;
                String base = slugUpper(c.name);
                String id = base;
                int i = 2;
                while (store.cat(id) != null) id = base + "_" + (i++);
                c.id = id;
                store.cats.add(c);
                store.saveCats(false);
                okMsg = "Categorie adăugată: " + c.name;
                return null;
            }
            case "cat.set": {
                GpsStore.Cat c = store.cat(str(j, "id", ""));
                if (c == null) return "Categoria nu există.";
                GpsStore.Cat copy = c.copy();
                String err = applyCat(copy, j, false);
                if (err != null) return err;
                c.name = copy.name;
                c.group = copy.group;
                c.icon = copy.icon;
                c.color = copy.color;
                c.enabled = copy.enabled;
                store.saveCats(false);
                okMsg = "Categorie salvată: " + c.name;
                return null;
            }
            case "cat.del": {
                GpsStore.Cat c = store.cat(str(j, "id", ""));
                if (c == null) return "Categoria nu există.";
                int n = 0;
                for (GpsStore.Loc l : store.locs) if (l.cat.equalsIgnoreCase(c.id)) n++;
                if (n > 0) return "Categoria are " + n + " locații. Mută-le sau șterge-le întâi.";
                store.cats.remove(c);
                store.saveCats(true);
                okMsg = "Categorie ștearsă: " + c.name;
                return null;
            }
            case "settings.set": {
                if (j.has("arrive")) {
                    double v = num(j, "arrive", Double.NaN);
                    if (Double.isNaN(v) || v < 2 || v > 100) return "Distanța de sosire trebuie să fie 2-100.";
                    store.settings.arrive = v;
                }
                if (j.has("zoom")) {
                    double v = num(j, "zoom", Double.NaN);
                    if (Double.isNaN(v) || v < 0.2 || v > 6) return "Zoom invalid (0.2-6).";
                    store.settings.zoom = v;
                }
                if (j.has("showZones")) store.settings.showZones = bool(j, "showZones", true);
                store.saveSettings(false);
                okMsg = "Setări hartă salvate.";
                return null;
            }
            case "reload": {
                store.reloadFromDisk();
                okMsg = "Reîncărcat din fișiere: " + store.locs.size() + " locații, " + store.zones.size() + " zone, " + store.cats.size() + " categorii, " + store.icons.size() + " iconițe.";
                return null;
            }
            default:
                return "Operație necunoscută.";
        }
    }

    // ------------------------------------------------------------------ comenzi (optional)

    private boolean admin(CommandSender s) {
        if (s.hasPermission(PERM_ADMIN)) return true;
        s.sendMessage("§cNu ai permisiunea.");
        return false;
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

    private boolean command(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (!(s instanceof Player)) {
                s.sendMessage("Comanda /gps deschide harta in joc. Admin: /gps lista | reload");
                return true;
            }
            send((Player) s, "open", new JsonObject());
            s.sendMessage(TAG + "Se deschide harta... (dacă nu apare: tasta M sau §f.gps§7 în chat). Editarea: GPS > EDITOR HARTĂ.");
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
                for (GpsStore.Loc l : store.locs) {
                    if (!filter.isEmpty() && !l.cat.equals(filter)) continue;
                    if (n++ >= 60) {
                        s.sendMessage("§7... (filtrează cu /gps lista <CATEGORIE>)");
                        break;
                    }
                    s.sendMessage("§f" + l.id + " §7" + l.name + " [" + l.cat + "] " + (int) l.x + " " + (int) l.z + (l.enabled ? "" : " §c(inactiv)"));
                }
                if (n == 0) s.sendMessage(TAG + "Nu există locații" + (filter.isEmpty() ? "" : " în " + filter) + ". Adaugă-le din joc: GPS > EDITOR HARTĂ.");
                return true;
            }
            case "categorii": {
                List<String> ids = new ArrayList<>();
                for (GpsStore.Cat c : store.cats) ids.add(c.id);
                s.sendMessage(TAG + "Categorii: §f" + (ids.isEmpty() ? "-" : String.join(", ", ids)));
                return true;
            }
            case "reload": {
                if (!admin(s)) return true;
                store.reloadFromDisk();
                pushAll();
                s.sendMessage(TAG + "Reîncărcat: " + store.locs.size() + " locații, " + store.zones.size() + " zone.");
                return true;
            }
            case "adauga": {
                if (!admin(s)) return true;
                if (!(s instanceof Player) || a.length < 3) {
                    s.sendMessage("Folosire: /gps adauga <CATEGORIE> <nume...> (poziția ta). Mai simplu: GPS > EDITOR HARTĂ.");
                    return true;
                }
                Player p = (Player) s;
                GpsStore.Cat c = store.cat(a[1]);
                if (c == null) {
                    s.sendMessage("§cCategorie necunoscută. §7Vezi /gps categorii");
                    return true;
                }
                if (store.locs.size() >= GpsStore.MAX_LOCS) {
                    s.sendMessage("§cPrea multe locații.");
                    return true;
                }
                GpsStore.Loc l = new GpsStore.Loc();
                l.name = GpsStore.cut(String.join(" ", Arrays.copyOfRange(a, 2, a.length)), 40);
                if (l.name.isEmpty()) {
                    s.sendMessage("§cNume invalid.");
                    return true;
                }
                l.cat = c.id;
                l.x = Math.round(p.getLocation().getX());
                l.z = Math.round(p.getLocation().getZ());
                l.dim = dimOf(p);
                l.id = store.uniqueId(l.name, false);
                store.locs.add(l);
                store.saveLocs(false);
                pushAll();
                s.sendMessage(TAG + "Adăugat §f" + l.id + "§7 la " + (long) l.x + " " + (long) l.z + ".");
                return true;
            }
            case "sterge": {
                if (!admin(s)) return true;
                GpsStore.Loc l = a.length >= 2 ? store.loc(a[1]) : null;
                if (l == null) {
                    s.sendMessage("Folosire: /gps sterge <id>  (vezi /gps lista)");
                    return true;
                }
                store.locs.remove(l);
                store.saveLocs(true);
                pushAll();
                s.sendMessage(TAG + "Șters: §f" + l.id);
                return true;
            }
            case "muta": {
                if (!admin(s)) return true;
                GpsStore.Loc l = a.length >= 2 ? store.loc(a[1]) : null;
                if (!(s instanceof Player) || l == null) {
                    s.sendMessage("Folosire: /gps muta <id> (la poziția ta)");
                    return true;
                }
                Player p = (Player) s;
                l.x = Math.round(p.getLocation().getX());
                l.z = Math.round(p.getLocation().getZ());
                l.dim = dimOf(p);
                store.saveLocs(false);
                pushAll();
                s.sendMessage(TAG + "Mutat §f" + l.id + "§7 la " + (long) l.x + " " + (long) l.z + ".");
                return true;
            }
            case "activeaza":
            case "dezactiveaza": {
                if (!admin(s)) return true;
                GpsStore.Loc l = a.length >= 2 ? store.loc(a[1]) : null;
                if (l == null) {
                    s.sendMessage("Folosire: /gps " + sub + " <id>");
                    return true;
                }
                l.enabled = sub.equals("activeaza");
                store.saveLocs(false);
                pushAll();
                s.sendMessage(TAG + l.id + (l.enabled ? " activat." : " dezactivat."));
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
                if (!coord(x) || !coord(z)) {
                    s.sendMessage("§cCoordonate invalide.");
                    return true;
                }
                JsonObject o = new JsonObject();
                o.addProperty("name", a.length > 4 ? GpsStore.cut(String.join(" ", Arrays.copyOfRange(a, 4, a.length)), 40) : "Destinație");
                o.addProperty("x", x);
                o.addProperty("z", z);
                o.addProperty("dim", dimOf(t));
                send(t, "set", o);
                s.sendMessage(TAG + "GPS setat pentru " + t.getName() + ".");
                return true;
            }
            default:
                s.sendMessage(TAG + "/gps §8- §7harta | §f/gps cancel §8- §7anulează navigarea");
                if (s.hasPermission(PERM_ADMIN)) {
                    s.sendMessage("§7Admin: editează din joc (GPS > EDITOR HARTĂ). Comenzi: §f/gps lista [CAT] §8| §f/gps categorii §8| §f/gps set <jucător> <x> <z> [nume] §8| §f/gps reload");
                }
                return true;
        }
    }

    private List<String> complete(CommandSender s, String[] a) {
        List<String> out = new ArrayList<>();
        boolean admin = s.hasPermission(PERM_ADMIN);
        if (a.length == 1) {
            out.add("cancel");
            out.add("categorii");
            if (admin) out.addAll(Arrays.asList("lista", "adauga", "sterge", "muta", "activeaza", "dezactiveaza", "set", "reload"));
        } else if (a.length == 2 && admin) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (sub.equals("lista") || sub.equals("adauga")) {
                for (GpsStore.Cat c : store.cats) out.add(c.id);
            } else if (sub.equals("sterge") || sub.equals("muta") || sub.equals("activeaza") || sub.equals("dezactiveaza")) {
                for (GpsStore.Loc l : store.locs) out.add(l.id);
            } else if (sub.equals("set") || sub.equals("cancel")) {
                for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            }
        }
        return Commands.filter(out, a.length == 0 ? "" : a[a.length - 1]);
    }
}
