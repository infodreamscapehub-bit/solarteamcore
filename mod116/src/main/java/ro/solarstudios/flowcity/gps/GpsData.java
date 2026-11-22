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
    }

    public static final class Zone {
        public String id = "";
        public String name = "";
        public String dim = "minecraft:overworld";
        public boolean legal = true;
        public int color;
        public float[] px = new float[0];
        public float[] pz = new float[0];
        /** Triunghiuri (x0,z0,x1,z1,x2,z2 ...) pentru umplere, si cutia de incadrare. */
        public float[] tris = new float[0];
        public float minX;
        public float minZ;
        public float maxX;
        public float maxZ;
    }

    public static final List<Cat> cats = new ArrayList<>();
    public static final Map<String, Cat> catById = new LinkedHashMap<>();
    public static final List<String> groups = new ArrayList<>();
    public static final List<Loc> locs = new ArrayList<>();
    public static final List<Zone> zones = new ArrayList<>();
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
            l.icon = JsonClientModule.str(o, "icon", c == null ? "pin" : c.icon);
            l.color = color(JsonClientModule.str(o, "color", ""), c == null ? 0xFF4DA3FF : c.color);
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
            z.legal = !"ILEGAL".equals(JsonClientModule.str(o, "type", "LEGAL"));
            z.color = color(JsonClientModule.str(o, "color", ""), z.legal ? 0xFF2FBF71 : 0xFFE5484D);
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
        List<Loc> out = new ArrayList<>(locs);
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
