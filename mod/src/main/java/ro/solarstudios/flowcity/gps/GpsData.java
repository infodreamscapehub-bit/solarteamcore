package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.GaragesClient;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modelul GPS de pe client: categorii, locatii si zone primite de la server (gps.yml) + garajele/benzinariile din mod. */
public final class GpsData {
    private GpsData() {}

    public static final class Cat {
        public String id = "";
        public String name = "";
        public String group = "";
        public String icon = "pin";
        public int color = 0xFF4DA3FF;
        public boolean enabled = true;
    }

    public static final class Loc {
        public String id = "";
        public String name = "";
        public String cat = "";
        public String catName = "";
        public String icon = "pin";
        public String desc = "";
        public String dim = "minecraft:overworld";
        public String group = "";
        public String key = "";
        public double x;
        public double z;
        public int color = 0xFF4DA3FF;
        /** Mărimea insemnului pe hartă (50-200 %). */
        public int size = 100;
        public boolean enabled = true;
        /** Ascunsă pe hartă (rămâne în listă). */
        public boolean hidden;
        /** Valorile brute din config (gol = moștenește de la categorie), folosite de editor. */
        public String iconRaw = "";
        public String colorRaw = "";
        /** Inaltimea Y (optionala, pentru teleportare) si permisiunea de vizibilitate (doar pentru admini). */
        public double y;
        public boolean hasY;
        public String perm = "";
    }

    public static final class Zone {
        public String id = "";
        public String name = "";
        public String dim = "minecraft:overworld";
        public boolean legal = true;
        public String type = "LEGAL";
        public String desc = "";
        public boolean enabled = true;
        public int alpha = 22;
        /** Culoarea interiorului si a conturului (ARGB opac). */
        public int color;
        public int outline;
        public String colorRaw = "";
        public String outlineRaw = "";
        public float[] px = new float[0];
        public float[] pz = new float[0];
        /** Triunghiuri (x0,z0,x1,z1,x2,z2 ...) pentru umplere, si cutia de incadrare. */
        public float[] tris = new float[0];
        public float minX;
        public float minZ;
        public float maxX;
        public float maxZ;
    }

    public static final class Shape {
        public String t = "poly";
        public float[] p = new float[0];
        public float r = 0.5f;
        public float w = 0.2f;
        public boolean dark;
        /** Triunghiurile unui poligon (umplere corecta si pentru poligoane concave). */
        public float[] tris = new float[0];
    }

    public static final class CIcon {
        public String id = "";
        public String name = "";
        public String category = "Alte locații";
        public final List<Shape> shapes = new ArrayList<>();
    }

    public static final String[] ZONE_TYPES = {"LEGAL", "ILEGAL", "POLITIE", "SPITAL", "PROTEJATA", "PERICULOASA", "PERSONALIZATA"};

    public static String typeLabel(String t) {
        switch (t) {
            case "LEGAL":
                return "Zonă legală";
            case "ILEGAL":
                return "Zonă ilegală";
            case "POLITIE":
                return "Zonă poliție";
            case "SPITAL":
                return "Zonă spital";
            case "PROTEJATA":
                return "Zonă protejată";
            case "PERICULOASA":
                return "Zonă periculoasă";
            default:
                return "Zonă personalizată";
        }
    }

    public static int typeColor(String t) {
        switch (t) {
            case "LEGAL":
                return 0xFF2FBF71;
            case "ILEGAL":
                return 0xFFE5484D;
            case "POLITIE":
                return 0xFF4DA3FF;
            case "SPITAL":
                return 0xFFFF8FB1;
            case "PROTEJATA":
                return 0xFF8B7BFF;
            case "PERICULOASA":
                return 0xFFF5A524;
            default:
                return 0xFFC9D4E4;
        }
    }

    /** Stratul hartii (comutabil independent) in care intra o zona. */
    public static String zoneLayer(Zone z) {
        if ("LEGAL".equals(z.type)) return "legal";
        if ("ILEGAL".equals(z.type)) return "illegal";
        return "special";
    }

    /** Stratul hartii al unei locatii: police / hospital / shop / other. */
    public static String locLayer(Loc l) {
        String c = l.cat == null ? "" : l.cat;
        if (c.contains("POLITIE") || "police".equals(l.icon)) return "police";
        if (c.contains("SPITAL") || "hospital".equals(l.icon)) return "hospital";
        if (c.contains("MAGAZIN") || c.equals("MALL") || "shop".equals(l.icon) || "mall".equals(l.icon) || "MAGAZINE".equals(l.group)) return "shop";
        return "other";
    }

    /** Punct in poligon (ray casting), corect si pentru poligoane concave. */
    public static boolean inside(float[] px, float[] pz, float x, float z) {
        boolean in = false;
        for (int i = 0, j = px.length - 1; i < px.length; j = i++) {
            if ((pz[i] > z) != (pz[j] > z) && x < (px[j] - px[i]) * (z - pz[i]) / (pz[j] - pz[i]) + px[i]) in = !in;
        }
        return in;
    }

    public static final List<Cat> cats = new ArrayList<>();
    public static final Map<String, Cat> catById = new LinkedHashMap<>();
    public static final List<String> groups = new ArrayList<>();
    public static final List<Loc> locs = new ArrayList<>();
    public static final List<Zone> zones = new ArrayList<>();
    /** Iconite vectoriale personalizate (icons.json de pe server): id -> forme normalizate (-1..1). */
    public static final Map<String, CIcon> customIcons = new LinkedHashMap<>();
    /** Adevarat daca serverul ne-a marcat ca admin GPS (afiseaza EDITOR HARTA; serverul re-verifica oricum). */
    public static boolean admin;
    /** Serverul a marcat jucatorul ca autorizat sa foloseasca Teleportare (OP + permisiune); serverul re-verifica oricum. */
    public static boolean canTp;
    public static double arrive = 8;
    public static boolean showZonesDefault = true;
    public static double defaultZoom = 1.2;
    public static boolean loaded;

    public static int color(String hex, int def) {
        if (hex == null || hex.isEmpty()) return def;
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | (Integer.parseInt(h, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void clear() {
        cats.clear();
        catById.clear();
        groups.clear();
        locs.clear();
        zones.clear();
        customIcons.clear();
        admin = false;
        canTp = false;
        loaded = false;
    }

    public static void apply(JsonObject d) {
        List<Cat> nc = new ArrayList<>();
        Map<String, Cat> nm = new LinkedHashMap<>();
        List<String> ng = new ArrayList<>();
        List<Loc> nl = new ArrayList<>();
        List<Zone> nz = new ArrayList<>();
        for (JsonElement e : arr(d, "groups")) ng.add(e.getAsString());
        for (JsonElement e : arr(d, "cats")) {
            JsonObject o = e.getAsJsonObject();
            Cat c = new Cat();
            c.id = JsonClientModule.str(o, "id", "");
            c.name = JsonClientModule.str(o, "name", c.id);
            c.group = JsonClientModule.str(o, "group", "");
            c.icon = JsonClientModule.str(o, "icon", "pin");
            c.color = color(JsonClientModule.str(o, "color", ""), 0xFF4DA3FF);
            c.enabled = JsonClientModule.bool(o, "enabled", true);
            nc.add(c);
            nm.put(c.id, c);
        }
        for (JsonElement e : arr(d, "locs")) {
            JsonObject o = e.getAsJsonObject();
            Loc l = new Loc();
            l.id = JsonClientModule.str(o, "id", "");
            l.name = JsonClientModule.str(o, "name", l.id);
            l.cat = JsonClientModule.str(o, "cat", "");
            l.x = JsonClientModule.num(o, "x", 0);
            l.z = JsonClientModule.num(o, "z", 0);
            l.dim = JsonClientModule.str(o, "dim", "minecraft:overworld");
            l.desc = JsonClientModule.str(o, "desc", "");
            Cat c = nm.get(l.cat);
            l.catName = c == null ? "" : c.name;
            l.group = c == null ? "" : c.group;
            l.iconRaw = JsonClientModule.str(o, "icon", "");
            l.colorRaw = JsonClientModule.str(o, "color", "");
            l.icon = l.iconRaw.isEmpty() ? (c == null ? "pin" : c.icon) : l.iconRaw;
            l.color = color(l.colorRaw, c == null ? 0xFF4DA3FF : c.color);
            l.size = Math.max(50, Math.min(200, JsonClientModule.integer(o, "size", 100)));
            l.enabled = JsonClientModule.bool(o, "enabled", true);
            l.hidden = JsonClientModule.bool(o, "hidden", false);
            l.hasY = o.has("y");
            l.y = JsonClientModule.num(o, "y", 0);
            l.perm = JsonClientModule.str(o, "perm", "");
            index(l);
            nl.add(l);
        }
        for (JsonElement e : arr(d, "zones")) {
            JsonObject o = e.getAsJsonObject();
            JsonArray pts = arr(o, "pts");
            int n = pts.size();
            if (n < 3) continue;
            Zone z = new Zone();
            z.id = JsonClientModule.str(o, "id", "");
            z.name = JsonClientModule.str(o, "name", "Zonă");
            z.dim = JsonClientModule.str(o, "dim", "minecraft:overworld");
            String zt = JsonClientModule.str(o, "type", "LEGAL");
            boolean known = false;
            for (String k : ZONE_TYPES) if (k.equals(zt)) known = true;
            z.type = known ? zt : "LEGAL";
            z.legal = !"ILEGAL".equals(z.type);
            z.desc = JsonClientModule.str(o, "desc", "");
            z.enabled = JsonClientModule.bool(o, "enabled", true);
            z.alpha = Math.max(5, Math.min(90, JsonClientModule.integer(o, "alpha", 22)));
            z.colorRaw = JsonClientModule.str(o, "color", "");
            z.outlineRaw = JsonClientModule.str(o, "outline", "");
            z.color = color(z.colorRaw, typeColor(z.type));
            z.outline = color(z.outlineRaw, z.color);
            z.px = new float[n];
            z.pz = new float[n];
            z.minX = Float.MAX_VALUE;
            z.minZ = Float.MAX_VALUE;
            z.maxX = -Float.MAX_VALUE;
            z.maxZ = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                JsonArray p = pts.get(i).getAsJsonArray();
                z.px[i] = p.get(0).getAsFloat();
                z.pz[i] = p.get(1).getAsFloat();
                z.minX = Math.min(z.minX, z.px[i]);
                z.maxX = Math.max(z.maxX, z.px[i]);
                z.minZ = Math.min(z.minZ, z.pz[i]);
                z.maxZ = Math.max(z.maxZ, z.pz[i]);
            }
            z.tris = triangulate(z.px, z.pz);
            nz.add(z);
        }
        Map<String, CIcon> ni = new LinkedHashMap<>();
        for (JsonElement e : arr(d, "icons")) {
            try {
                CIcon ci = parseIcon(e.getAsJsonObject());
                if (ci != null) ni.put(ci.id, ci);
            } catch (RuntimeException ignored) {
                // iconita stricata: o sarim
            }
        }
        customIcons.clear();
        customIcons.putAll(ni);
        admin = JsonClientModule.bool(d, "admin", false);
        canTp = JsonClientModule.bool(d, "canTp", false);
        JsonObject set = d.has("settings") && d.get("settings").isJsonObject() ? d.getAsJsonObject("settings") : new JsonObject();
        arrive = Math.max(2, JsonClientModule.num(set, "arrive", 8));
        showZonesDefault = JsonClientModule.bool(set, "showZones", true);
        defaultZoom = Math.max(0.2, Math.min(6, JsonClientModule.num(set, "zoom", 1.2)));
        cats.clear();
        cats.addAll(nc);
        catById.clear();
        catById.putAll(nm);
        groups.clear();
        groups.addAll(ng);
        locs.clear();
        locs.addAll(nl);
        zones.clear();
        zones.addAll(nz);
        loaded = true;
    }

    private static CIcon parseIcon(JsonObject o) {
        CIcon ci = new CIcon();
        ci.id = JsonClientModule.str(o, "id", "");
        if (ci.id.isEmpty()) return null;
        ci.name = JsonClientModule.str(o, "name", ci.id);
        ci.category = JsonClientModule.str(o, "category", "Alte locații");
        for (JsonElement se : arr(o, "shapes")) {
            JsonObject so = se.getAsJsonObject();
            Shape sh = new Shape();
            sh.t = JsonClientModule.str(so, "t", "poly");
            JsonArray pa = arr(so, "p");
            sh.p = new float[pa.size()];
            for (int i = 0; i < sh.p.length; i++) sh.p[i] = pa.get(i).getAsFloat();
            sh.r = (float) JsonClientModule.num(so, "r", 0.5);
            sh.w = (float) JsonClientModule.num(so, "w", 0.2);
            sh.dark = JsonClientModule.bool(so, "dark", false);
            if ("poly".equals(sh.t) && sh.p.length >= 6) {
                int n = sh.p.length / 2;
                float[] xs = new float[n];
                float[] zs = new float[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = sh.p[i * 2];
                    zs[i] = sh.p[i * 2 + 1];
                }
                sh.tris = triangulate(xs, zs);
            }
            ci.shapes.add(sh);
        }
        return ci.shapes.isEmpty() ? null : ci;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    /** Cheia de cautare: fara diacritice, litere mici. */
    public static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static void index(Loc l) {
        l.key = norm(l.name + " " + l.catName + " " + l.desc);
    }

    /** Locatiile din config + garajele si benzinariile cunoscute de mod (fara cele cu acces interzis). Lista noua la fiecare apel. */
    public static List<Loc> all() {
        List<Loc> out = new ArrayList<>(locs.size() + 8);
        for (Loc l : locs) {
            if (!l.enabled) continue;
            Cat lc = catById.get(l.cat);
            if (lc != null && !lc.enabled) continue;
            out.add(l);
        }
        try {
            Cat g = catById.get("GARAJ");
            Cat s = catById.get("BENZINARIE");
            for (GaragesClient.G ga : GaragesClient.garages) {
                if (!ga.access || "pound".equals(ga.type)) continue;
                Loc l = new Loc();
                l.id = "garaj_" + ga.id;
                l.name = ga.name == null ? "Garaj" : ga.name;
                l.cat = "GARAJ";
                l.catName = g == null ? "Garaj" : g.name;
                l.group = g == null ? "AUTO" : g.group;
                l.icon = g == null ? "garage" : g.icon;
                l.color = g == null ? 0xFF4DA3FF : g.color;
                l.dim = ga.dim == null ? "minecraft:overworld" : ga.dim;
                l.x = ga.mx;
                l.z = ga.mz;
                l.desc = "Garaj public";
                index(l);
                out.add(l);
            }
            for (GaragesClient.S st : GaragesClient.stations) {
                Loc l = new Loc();
                l.id = "benzinarie_" + st.id;
                l.name = st.name == null ? "Benzinărie" : st.name;
                l.cat = "BENZINARIE";
                l.catName = s == null ? "Benzinărie" : s.name;
                l.group = s == null ? "AUTO" : s.group;
                l.icon = s == null ? "fuel" : s.icon;
                l.color = s == null ? 0xFF62D98F : s.color;
                l.dim = st.dim == null ? "minecraft:overworld" : st.dim;
                l.x = st.x;
                l.z = st.z;
                l.desc = "Alimentezi din mașină (E)";
                index(l);
                out.add(l);
            }
        } catch (RuntimeException ignored) {
            // listele de garaje sunt modificate de alt fir: sarim peste in acest cadru
        }
        return out;
    }

    public static String dimOf(Minecraft mc) {
        return mc.level == null ? "minecraft:overworld" : mc.level.dimension().location().toString();
    }

    // ------------------------------------------------------------------ triunghiulare (ear clipping, poligoane concave)

    private static float area(float[] x, float[] z) {
        float a = 0;
        for (int i = 0, j = x.length - 1; i < x.length; j = i++) a += (x[j] * z[i]) - (x[i] * z[j]);
        return a / 2f;
    }

    private static boolean inTri(float ax, float az, float bx, float bz, float cx, float cz, float px, float pz) {
        float d1 = (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
        float d2 = (px - cx) * (bz - cz) - (bx - cx) * (pz - cz);
        float d3 = (px - ax) * (cz - az) - (cx - ax) * (pz - az);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    /** Intoarce triunghiurile poligonului ca x0,z0,x1,z1,x2,z2,... (gol daca poligonul e degenerat). */
    public static float[] triangulate(float[] x, float[] z) {
        int n = x.length;
        if (n < 3) return new float[0];
        int[] idx = new int[n];
        boolean ccw = area(x, z) > 0;
        for (int i = 0; i < n; i++) idx[i] = ccw ? i : n - 1 - i;
        List<Float> out = new ArrayList<>();
        int left = n;
        int guard = 0;
        int v = 0;
        while (left > 2 && guard++ < n * n + 8) {
            int a = idx[(v + left - 1) % left];
            int b = idx[v % left];
            int c = idx[(v + 1) % left];
            float cross = (x[b] - x[a]) * (z[c] - z[a]) - (z[b] - z[a]) * (x[c] - x[a]);
            boolean ear = cross > 1e-6f;
            if (ear) {
                for (int k = 0; k < left; k++) {
                    int p = idx[k];
                    if (p == a || p == b || p == c) continue;
                    if (inTri(x[a], z[a], x[b], z[b], x[c], z[c], x[p], z[p])) {
                        ear = false;
                        break;
                    }
                }
            }
            if (ear) {
                out.add(x[a]);
                out.add(z[a]);
                out.add(x[b]);
                out.add(z[b]);
                out.add(x[c]);
                out.add(z[c]);
                for (int k = v % left; k < left - 1; k++) idx[k] = idx[k + 1];
                left--;
                v = v % Math.max(1, left);
                guard = 0;
            } else {
                v = (v + 1) % left;
            }
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
}
