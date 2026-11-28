package ro.solarstudios.flowcity.bridge.gps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Stocarea datelor GPS in fisiere JSON separate (plugins/FlowCityBridge/gps/): locations, zones, categories, icons, map_settings.
 * Scriere atomica (fisier temporar + mutare), backup automat inainte de modificari (ultimele {@link #BACKUPS} pe fisier, in
 * backups/), protectie la fisiere corupte (fisierul stricat se redenumeste si se restaureaza ultimul backup valid).
 * La prima pornire datele se migreaza din gps.yml + gps-data.yml (fisierele vechi raman neatinse).
 */
final class GpsStore {
    static final String OVERWORLD = "minecraft:overworld";
    static final List<String> ZONE_TYPES = Collections.unmodifiableList(Arrays.asList("LEGAL", "ILEGAL", "POLITIE", "SPITAL", "PROTEJATA", "PERICULOASA", "PERSONALIZATA"));
    static final int MAX_LOCS = 1000;
    static final int MAX_ZONES = 300;
    static final int MAX_CATS = 60;
    static final int MAX_GROUPS = 24;
    static final int MAX_ICONS = 200;
    static final int MAX_PTS = 96;
    static final int BACKUPS = 15;
    static final int MAX_ROAD_NODES = 30000;
    static final int MAX_ROAD_EDGES = 60000;
    static final int MAX_ROAD_NAMES = 2000;

    static final class Loc {
        String id = "";
        String name = "";
        String cat = "";
        String icon = "";
        String color = "";
        String desc = "";
        String dim = OVERWORLD;
        double x;
        double z;
        int size = 100;
        boolean enabled = true;
        boolean hidden;
        /** Inaltimea Y (optional, folosita la teleportare). */
        int y;
        boolean hasY;
        /** Permisiunea necesara ca sa vezi locatia (gol = toti jucatorii). */
        String perm = "";

        Loc copy() {
            Loc c = new Loc();
            c.set(this);
            return c;
        }

        void set(Loc o) {
            id = o.id;
            name = o.name;
            cat = o.cat;
            icon = o.icon;
            color = o.color;
            desc = o.desc;
            dim = o.dim;
            x = o.x;
            z = o.z;
            size = o.size;
            enabled = o.enabled;
            hidden = o.hidden;
            y = o.y;
            hasY = o.hasY;
            perm = o.perm == null ? "" : o.perm;
        }
    }

    static final class Zone {
        String id = "";
        String name = "";
        String type = "LEGAL";
        String dim = OVERWORLD;
        String color = "";
        String outline = "";
        String desc = "";
        int alpha = 22;
        boolean enabled = true;
        List<double[]> pts = new ArrayList<>();

        Zone copy() {
            Zone c = new Zone();
            c.set(this);
            return c;
        }

        void set(Zone o) {
            id = o.id;
            name = o.name;
            type = o.type;
            dim = o.dim;
            color = o.color;
            outline = o.outline;
            desc = o.desc;
            alpha = o.alpha;
            enabled = o.enabled;
            pts = new ArrayList<>();
            for (double[] p : o.pts) pts.add(new double[] {p[0], p[1]});
        }
    }

    static final class Cat {
        String id = "";
        String name = "";
        String group = "";
        String icon = "pin";
        String color = "#4DA3FF";
        boolean enabled = true;

        Cat copy() {
            Cat c = new Cat();
            c.id = id;
            c.name = name;
            c.group = group;
            c.icon = icon;
            c.color = color;
            c.enabled = enabled;
            return c;
        }
    }

    static final class CatFile {
        List<String> groups = new ArrayList<>();
        List<Cat> cats = new ArrayList<>();
    }

    static final class Settings {
        double arrive = 8;
        boolean showZones = true;
        double zoom = 1.2;
    }

    /** Reteaua de drumuri (GPS pe strazi). nodes: {id, x, z}; edges: {a, b, flags(bit0 = sens unic a->b), indexNume(-1 = fara)}. */
    static final class RoadFile {
        long ver;
        int[][] nodes = new int[0][];
        int[][] edges = new int[0][];
        String[] names = new String[0];
    }

    private final File dir;

    private final File backups;
    private final Logger log;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, Long> lastBackup = new HashMap<>();

    final List<Loc> locs = new ArrayList<>();
    final List<Zone> zones = new ArrayList<>();
    final List<Cat> cats = new ArrayList<>();
    final List<String> groups = new ArrayList<>();
    JsonArray icons = new JsonArray();
    Settings settings = new Settings();
    RoadFile roads = new RoadFile();
    private final Gson compact = new Gson();

    /** Folderul gps/ al pluginului (acolo stau si fisierele de import). */
    File folder() {
        return dir;
    }

    GpsStore(File pluginDir, Logger log) {
        this.dir = new File(pluginDir, "gps");
        this.backups = new File(dir, "backups");
        this.log = log;
    }

    // ------------------------------------------------------------------ incarcare

    /** Incarca din fisierele JSON; la prima pornire migreaza din gps.yml (cfg) si gps-data.yml (legacy). */
    void load(YamlConfiguration cfg, YamlConfiguration legacy) {
        //noinspection ResultOfMethodCallIgnored
        backups.mkdirs();
        boolean fresh = !new File(dir, "locations.json").exists() && !new File(dir, "zones.json").exists() && !new File(dir, "categories.json").exists();
        if (fresh) {
            migrate(cfg, legacy);
            saveAll(true);
            log.info("[GPS] Date migrate din gps.yml in " + dir.getPath() + " (de acum se editeaza din joc: EDITOR HARTA).");
            return;
        }
        reloadFromDisk();
    }

    /** Reciteste fisierele (cu restaurare din backup daca sunt corupte). */
    void reloadFromDisk() {
        Loc[] l = read("locations.json", Loc[].class);
        locs.clear();
        if (l != null) {
            for (Loc x : l) if (x != null && fixLoc(x)) locs.add(x);
        }
        Zone[] z = read("zones.json", Zone[].class);
        zones.clear();
        if (z != null) {
            for (Zone x : z) if (x != null && fixZone(x)) zones.add(x);
        }
        CatFile cf = read("categories.json", CatFile.class);
        cats.clear();
        groups.clear();
        if (cf != null) {
            if (cf.cats != null) {
                for (Cat c : cf.cats) if (c != null && fixCat(c)) cats.add(c);
            }
            if (cf.groups != null) for (String g : cf.groups) if (g != null && !g.trim().isEmpty() && !groups.contains(g.trim())) groups.add(g.trim());
        }
        refreshGroups();
        Settings s = read("map_settings.json", Settings.class);
        settings = s == null ? new Settings() : s;
        settings.arrive = clamp(settings.arrive, 2, 100, 8);
        settings.zoom = clamp(settings.zoom, 0.2, 6, 1.2);
        JsonArray ic = readIcons();
        icons = ic;
        roads = fixRoads(read("roads.json", RoadFile.class));
    }

    /** Valideaza o retea: noduri unice si in limite, muchii valide fara duplicate/bucle, nume taiate. Niciodata null. */
    static RoadFile fixRoads(RoadFile f) {
        RoadFile o = new RoadFile();
        if (f == null) return o;
        o.ver = Math.max(0, f.ver);
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        List<int[]> nl = new ArrayList<>();
        if (f.nodes != null) {
            for (int[] n : f.nodes) {
                if (n == null || n.length < 3 || n[0] < 0 || Math.abs(n[1]) > 30000000 || Math.abs(n[2]) > 30000000) continue;
                if (nl.size() >= MAX_ROAD_NODES || !ids.add(n[0])) continue;
                nl.add(new int[] {n[0], n[1], n[2]});
            }
        }
        List<String> nm = new ArrayList<>();
        if (f.names != null) {
            for (String s : f.names) {
                if (nm.size() >= MAX_ROAD_NAMES) break;
                nm.add(cut(s == null ? "" : s.trim(), 32));
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<int[]> el = new ArrayList<>();
        if (f.edges != null) {
            for (int[] e : f.edges) {
                if (e == null || e.length < 2 || e[0] == e[1] || !ids.contains(e[0]) || !ids.contains(e[1])) continue;
                if (el.size() >= MAX_ROAD_EDGES) break;
                long key = ((long) Math.min(e[0], e[1]) << 32) | (Math.max(e[0], e[1]) & 0xFFFFFFFFL);
                if (!seen.add(key)) continue;
                int flags = e.length > 2 ? (e[2] & 1) : 0;
                int name = e.length > 3 && e[3] >= 0 && e[3] < nm.size() ? e[3] : -1;
                el.add(new int[] {e[0], e[1], flags, name});
            }
        }
        o.nodes = nl.toArray(new int[0][]);
        o.edges = el.toArray(new int[0][]);
        o.names = nm.toArray(new String[0]);
        return o;
    }

    /** Numarul de componente conexe ale retelei (noduri izolate inclusiv). */
    static int components(RoadFile r) {
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < r.nodes.length; i++) idx.put(r.nodes[i][0], i);
        int[] par = new int[r.nodes.length];
        for (int i = 0; i < par.length; i++) par[i] = i;
        for (int[] e : r.edges) {
            Integer a = idx.get(e[0]);
            Integer b = idx.get(e[1]);
            if (a == null || b == null) continue;
            int ra = a;
            while (par[ra] != ra) ra = par[ra] = par[par[ra]];
            int rb = b;
            while (par[rb] != rb) rb = par[rb] = par[par[rb]];
            if (ra != rb) par[ra] = rb;
        }
        int c = 0;
        for (int i = 0; i < par.length; i++) if (par[i] == i) c++;
        return c;
    }

    /** Readuce ultima versiune salvata anterior (cea mai noua copie din backup). */
    boolean restoreRoads() {
        for (File b : backupsFor("roads.json")) {
            RoadFile r = tryParse(b, RoadFile.class);
            if (r == null) continue;
            RoadFile fixed = fixRoads(r);
            fixed.ver = roads.ver + 1;
            roads = fixed;
            saveRoads(true);
            return true;
        }
        return false;
    }

    private static double clamp(double v, double lo, double hi, double def) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return def;
        return Math.max(lo, Math.min(hi, v));
    }

    private <T> T read(String name, Class<T> type) {
        File f = new File(dir, name);
        T v = tryParse(f, type);
        if (v != null) return v;
        if (f.exists()) {
            File bad = new File(dir, name + ".corupt-" + stamp());
            if (f.renameTo(bad)) log.warning("[GPS] " + name + " era corupt; l-am mutat in " + bad.getName() + ".");
            else log.warning("[GPS] " + name + " este corupt si nu l-am putut muta.");
        }
        for (File b : backupsFor(name)) {
            T r = tryParse(b, type);
            if (r != null) {
                try {
                    Files.copy(b.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.warning("[GPS] " + name + " restaurat din backup " + b.getName() + ".");
                } catch (IOException e) {
                    log.warning("[GPS] Nu pot copia backup-ul " + b.getName() + ": " + e);
                }
                return r;
            }
        }
        if (f.exists() || !backupsFor(name).isEmpty()) log.warning("[GPS] " + name + ": nu am gasit un backup valid, pornesc cu date goale.");
        return null;
    }

    private <T> T tryParse(File f, Class<T> type) {
        if (!f.isFile()) return null;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) return null;
            return gson.fromJson(text, type);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private JsonArray readIcons() {
        File f = new File(dir, "icons.json");
        if (!f.exists()) return new JsonArray();
        JsonArray a = null;
        try {
            JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (e.isJsonArray()) a = e.getAsJsonArray();
        } catch (IOException | RuntimeException ex) {
            a = null;
        }
        if (a == null) {
            File bad = new File(dir, "icons.json.corupt-" + stamp());
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(bad);
            log.warning("[GPS] icons.json invalid; mutat in " + bad.getName() + ". Pornesc fara iconite personalizate.");
            for (File b : backupsFor("icons.json")) {
                try {
                    JsonElement e = new JsonParser().parse(new String(Files.readAllBytes(b.toPath()), StandardCharsets.UTF_8));
                    if (e.isJsonArray()) {
                        Files.copy(b.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        a = e.getAsJsonArray();
                        log.warning("[GPS] icons.json restaurat din " + b.getName());
                        break;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // incercam urmatorul backup
                }
            }
        }
        return a == null ? new JsonArray() : sanitizeIcons(a);
    }

    /** Pastreaza doar iconitele valide: id [a-z0-9_]{1,32}, forme poly/line/circle/ring/rect cu coordonate finite. */
    private JsonArray sanitizeIcons(JsonArray in) {
        JsonArray out = new JsonArray();
        List<String> seen = new ArrayList<>();
        for (JsonElement e : in) {
            if (out.size() >= MAX_ICONS) break;
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = str(o, "id");
            if (!id.matches("[a-z0-9_]{1,32}") || seen.contains(id)) continue;
            JsonElement se = o.get("shapes");
            if (se == null || !se.isJsonArray()) continue;
            JsonArray shapes = new JsonArray();
            for (JsonElement s : se.getAsJsonArray()) {
                if (shapes.size() >= 64) break;
                JsonObject so;
                try {
                    so = shapeOf(s);
                } catch (RuntimeException ex) {
                    so = null;
                }
                if (so != null) shapes.add(so);
            }
            if (shapes.size() == 0) continue;
            JsonObject r = new JsonObject();
            r.addProperty("id", id);
            r.addProperty("name", cut(str(o, "name").isEmpty() ? id : str(o, "name"), 32));
            r.addProperty("category", cut(str(o, "category").isEmpty() ? "Alte locații" : str(o, "category"), 32));
            r.add("shapes", shapes);
            out.add(r);
            seen.add(id);
        }
        return out;
    }

    private static JsonObject shapeOf(JsonElement s) {
        if (!s.isJsonObject()) return null;
        JsonObject o = s.getAsJsonObject();
        String t = str(o, "t");
        JsonElement pe = o.get("p");
        if (pe == null || !pe.isJsonArray()) return null;
        JsonArray p = pe.getAsJsonArray();
        int need;
        switch (t) {
            case "poly":
                need = -1;
                break;
            case "line":
                need = 4;
                break;
            case "circle":
            case "ring":
                need = 2;
                break;
            case "rect":
                need = 4;
                break;
            default:
                return null;
        }
        if (p.size() > 96 || p.size() % 2 != 0 || (need > 0 && p.size() != need) || (need < 0 && p.size() < 6)) return null;
        JsonArray np = new JsonArray();
        for (JsonElement n : p) {
            double v;
            try {
                v = n.getAsDouble();
            } catch (RuntimeException ex) {
                return null;
            }
            if (Double.isNaN(v) || Double.isInfinite(v) || Math.abs(v) > 4) return null;
            np.add(v);
        }
        JsonObject r = new JsonObject();
        r.addProperty("t", t);
        r.add("p", np);
        if (o.has("r")) r.addProperty("r", clamp(o.get("r").getAsDouble(), 0.01, 4, 0.5));
        if (o.has("w")) r.addProperty("w", clamp(o.get("w").getAsDouble(), 0.01, 4, 0.2));
        if (o.has("dark") && o.get("dark").isJsonPrimitive()) r.addProperty("dark", o.get("dark").getAsBoolean());
        return r;
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString().trim() : "";
    }

    // ------------------------------------------------------------------ curatare dupa citire

    static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\p{Cntrl}", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private boolean fixLoc(Loc l) {
        if (l.id == null || l.id.trim().isEmpty()) return false;
        l.id = cut(l.id, 48);
        l.name = cut(l.name == null || l.name.isEmpty() ? l.id : l.name, 40);
        l.cat = l.cat == null ? "" : l.cat.trim().toUpperCase(Locale.ROOT);
        l.icon = l.icon == null ? "" : l.icon;
        l.color = l.color == null ? "" : l.color;
        l.desc = cut(l.desc, 120);
        l.dim = l.dim == null || l.dim.isEmpty() ? OVERWORLD : l.dim;
        if (Double.isNaN(l.x) || Double.isInfinite(l.x) || Double.isNaN(l.z) || Double.isInfinite(l.z)) return false;
        l.size = (int) clamp(l.size, 50, 200, 100);
        return true;
    }

    private boolean fixZone(Zone z) {
        if (z.id == null || z.id.trim().isEmpty() || z.pts == null) return false;
        z.id = cut(z.id, 48);
        z.name = cut(z.name == null || z.name.isEmpty() ? z.id : z.name, 40);
        if (z.type == null || !ZONE_TYPES.contains(z.type)) z.type = "LEGAL";
        z.dim = z.dim == null || z.dim.isEmpty() ? OVERWORLD : z.dim;
        z.color = z.color == null ? "" : z.color;
        z.outline = z.outline == null ? "" : z.outline;
        z.desc = cut(z.desc, 120);
        z.alpha = (int) clamp(z.alpha, 5, 90, 22);
        List<double[]> ok = new ArrayList<>();
        for (double[] p : z.pts) {
            if (p == null || p.length < 2 || Double.isNaN(p[0]) || Double.isNaN(p[1]) || Double.isInfinite(p[0]) || Double.isInfinite(p[1])) continue;
            ok.add(new double[] {p[0], p[1]});
        }
        if (ok.size() < 3 || ok.size() > MAX_PTS) return false;
        z.pts = ok;
        return true;
    }

    private boolean fixCat(Cat c) {
        if (c.id == null || c.id.trim().isEmpty()) return false;
        c.id = c.id.trim().toUpperCase(Locale.ROOT);
        c.name = cut(c.name == null || c.name.isEmpty() ? c.id : c.name, 32);
        c.group = cut(c.group, 24);
        c.icon = c.icon == null || c.icon.isEmpty() ? "pin" : c.icon;
        c.color = c.color == null || c.color.isEmpty() ? "#4DA3FF" : c.color;
        return true;
    }

    /** Grupurile (filtrele din panou): cele definite + cele folosite de categorii, fara cele nefolosite. */
    void refreshGroups() {
        List<String> used = new ArrayList<>();
        for (Cat c : cats) if (!c.group.isEmpty() && !used.contains(c.group)) used.add(c.group);
        List<String> out = new ArrayList<>();
        for (String g : groups) if (used.contains(g) && !out.contains(g)) out.add(g);
        for (String g : used) if (!out.contains(g) && out.size() < MAX_GROUPS) out.add(g);
        groups.clear();
        groups.addAll(out);
    }

    Cat cat(String id) {
        for (Cat c : cats) if (c.id.equalsIgnoreCase(id)) return c;
        return null;
    }

    Loc loc(String id) {
        for (Loc l : locs) if (l.id.equalsIgnoreCase(id)) return l;
        return null;
    }

    Zone zone(String id) {
        for (Zone z : zones) if (z.id.equalsIgnoreCase(id)) return z;
        return null;
    }

    // ------------------------------------------------------------------ salvare

    void saveAll(boolean force) {
        saveLocs(force);
        saveZones(force);
        saveCats(force);
        saveSettings(force);
        saveIcons(force);
    }

    void saveLocs(boolean force) {
        write("locations.json", gson.toJson(locs.toArray(new Loc[0])), force);
    }

    void saveZones(boolean force) {
        write("zones.json", gson.toJson(zones.toArray(new Zone[0])), force);
    }

    void saveCats(boolean force) {
        refreshGroups();
        CatFile cf = new CatFile();
        cf.groups = new ArrayList<>(groups);
        cf.cats = new ArrayList<>(cats);
        write("categories.json", gson.toJson(cf), force);
    }

    void saveSettings(boolean force) {
        write("map_settings.json", gson.toJson(settings), force);
    }

    void saveRoads(boolean force) {
        write("roads.json", compact.toJson(roads), force);
    }

    void saveIcons(boolean force) {
        write("icons.json", gson.toJson(icons), force);
    }

    private synchronized void write(String name, String json, boolean forceBackup) {
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            backup(name, forceBackup);
            File tmp = new File(dir, name + ".tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            File f = new File(dir, name);
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warning("[GPS] Nu pot salva " + name + ": " + e);
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
    }

    private static String base(String name) {
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }

    /** Copie a fisierului inainte de scriere. Fara forta, cel mult una la 30 de secunde (mutarile repetate nu umplu discul). */
    private void backup(String name, boolean force) {
        File f = new File(dir, name);
        if (!f.isFile() || f.length() == 0) return;
        long now = System.currentTimeMillis();
        Long last = lastBackup.get(name);
        if (!force && last != null && now - last < 30000L) return;
        lastBackup.put(name, now);
        try {
            //noinspection ResultOfMethodCallIgnored
            backups.mkdirs();
            Files.copy(f.toPath(), new File(backups, base(name) + "." + stamp() + ".json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("[GPS] Backup esuat pentru " + name + ": " + e);
            return;
        }
        List<File> all = backupsFor(name);
        for (int i = BACKUPS; i < all.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            all.get(i).delete();
        }
    }

    /** Backup-urile unui fisier, cele mai noi primele. */
    private List<File> backupsFor(String name) {
        List<File> out = new ArrayList<>();
        File[] fs = backups.listFiles();
        if (fs == null) return out;
        String pre = base(name) + ".";
        for (File f : fs) if (f.isFile() && f.getName().startsWith(pre) && f.getName().endsWith(".json")) out.add(f);
        Collections.sort(out, Collections.reverseOrder());
        return out;
    }

    // ------------------------------------------------------------------ migrare din gps.yml / gps-data.yml

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

    private Loc locFromMap(Map<?, ?> m) {
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
        return fixLoc(l) ? l : null;
    }

    private void migrate(YamlConfiguration cfg, YamlConfiguration legacy) {
        locs.clear();
        zones.clear();
        cats.clear();
        groups.clear();
        for (String g : cfg.getStringList("groups")) if (!g.trim().isEmpty() && !groups.contains(g.trim())) groups.add(g.trim());
        ConfigurationSection cs = cfg.getConfigurationSection("categories");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(key);
                if (c == null) continue;
                Cat k = new Cat();
                k.id = key;
                k.name = c.getString("name", key);
                k.group = c.getString("group", "");
                k.icon = c.getString("icon", "pin");
                k.color = c.getString("color", "#4DA3FF");
                if (fixCat(k)) cats.add(k);
            }
        }
        refreshGroups();
        List<String> deleted = legacy.getStringList("deleted");
        for (Map<?, ?> m : cfg.getMapList("locations")) {
            Loc l = locFromMap(m);
            if (l == null || deleted.contains(l.id)) continue;
            ConfigurationSection ov = legacy.getConfigurationSection("overrides." + l.id);
            if (ov != null) {
                if (ov.contains("x")) l.x = ov.getDouble("x");
                if (ov.contains("z")) l.z = ov.getDouble("z");
                if (ov.contains("dimension")) l.dim = ov.getString("dimension", l.dim);
                if (ov.contains("enabled")) l.enabled = ov.getBoolean("enabled", l.enabled);
            }
            locs.add(l);
        }
        for (Map<?, ?> m : legacy.getMapList("added")) {
            Loc l = locFromMap(m);
            if (l != null && loc(l.id) == null) locs.add(l);
        }
        for (Map<?, ?> m : cfg.getMapList("zones")) {
            Zone z = new Zone();
            z.id = s(m.get("id"), "").trim();
            if (z.id.isEmpty()) continue;
            z.name = s(m.get("name"), z.id);
            String t = s(m.get("type"), "LEGAL").toUpperCase(Locale.ROOT);
            z.type = "ILLEGAL".equals(t) ? "ILEGAL" : (ZONE_TYPES.contains(t) ? t : "LEGAL");
            z.dim = s(m.get("dimension"), OVERWORLD);
            z.color = s(m.get("color"), "");
            z.enabled = b(m.get("enabled"), true);
            Object po = m.get("points");
            if (po instanceof List) {
                for (Object e : (List<?>) po) {
                    if (e instanceof List && ((List<?>) e).size() >= 2) z.pts.add(new double[] {d(((List<?>) e).get(0), 0), d(((List<?>) e).get(1), 0)});
                }
            } else if (m.get("x1") != null && m.get("z1") != null && m.get("x2") != null && m.get("z2") != null) {
                double x1 = d(m.get("x1"), 0), z1 = d(m.get("z1"), 0), x2 = d(m.get("x2"), 0), z2 = d(m.get("z2"), 0);
                z.pts.add(new double[] {x1, z1});
                z.pts.add(new double[] {x2, z1});
                z.pts.add(new double[] {x2, z2});
                z.pts.add(new double[] {x1, z2});
            }
            if (fixZone(z)) zones.add(z);
        }
        settings = new Settings();
        settings.arrive = clamp(cfg.getDouble("settings.arrive-distance", 8), 2, 100, 8);
        settings.showZones = cfg.getBoolean("settings.show-zones", true);
        settings.zoom = clamp(cfg.getDouble("settings.default-zoom", 1.2), 0.2, 6, 1.2);
        icons = new JsonArray();
    }

    // ------------------------------------------------------------------ utilitare

    /** Id unic dintr-un nume (slug), cu sufix numeric la coliziune. */
    String uniqueId(String name, boolean zone) {
        String t = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (t.isEmpty()) t = zone ? "zona" : "loc";
        if (t.length() > 36) t = t.substring(0, 36);
        String id = t;
        int i = 2;
        while (zone ? zone(id) != null : loc(id) != null) id = t + "_" + (i++);
        return id;
    }

}
