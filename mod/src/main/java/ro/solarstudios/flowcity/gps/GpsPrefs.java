package ro.solarstudios.flowcity.gps;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.minecraftforge.fml.loading.FMLPaths;

/** Preferintele jucatorului pentru GPS (config/flowcity-gps.json): favorite, zone RP afisate, zoom. */
public final class GpsPrefs {
    private GpsPrefs() {}

    public static final Set<String> fav = new HashSet<>();
    /** null = implicit de pe server. */
    public static Boolean showZones;
    public static float zoom;
    /** Straturile hartii ascunse de jucator (locations, legal, illegal, special, police, hospital, shop, waypoints, other). */
    public static final Set<String> hiddenLayers = new HashSet<>();
    /** Grila optionala pe harta (editor si GPS). */
    public static boolean grid;
    /** In editor: afiseaza si elementele dezactivate (estompate). */
    public static boolean showDisabled = true;
    /** Anunturi sonore + mesaj la viraje (GPS pe drumuri). */
    public static boolean navSound = true;
    /** Notificare + sunet cand ajungi la destinatie. */
    public static boolean notify = true;
    /** Sageata GPS pe ecran in timpul jocului. */
    public static boolean hudOn = true;
    /** Clic scurt cand alegi o destinatie. */
    public static boolean pickSound = true;
    /** Destinatia ramane activa si dupa reconectare. */
    public static boolean keepLast = true;
    /** Coordonatele in antet / bara laterala / pe harta. */
    public static boolean showCoords = true;
    /** Distantele pe carduri si in liste. */
    public static boolean showDist = true;
    /** Scalarea interfetei GPS (0.85 - 1.25). */
    public static float uiScale = 1f;
    /** Opacitatea panourilor peste harta (0.6 - 1). */
    public static float opacity = 1f;

    /** Culorile de accent disponibile (violet, albastru, verde, chihlimbar). */
    public static final int[] ACCENTS = {0xFFFF7A1A, 0xFFFFC53D, 0xFF38BDF8, 0xFF22C55E};
    public static int accentIdx;

    /** Culorile traseului GPS pe harta (magenta, cyan, verde, chihlimbar, alb). */
    public static final int[] ROUTE_COLORS = {0xFFFF7A1A, 0xFFFF3FD0, 0xFF38BDF8, 0xFF22C55E, 0xFFE4ECFF};
    public static final String[] ROUTE_NAMES = {"Portocaliu", "Magenta", "Cyan", "Verde", "Alb"};
    public static int routeIdx;

    /** Culoarea cu care se deseneaza traseul pe harta si pe minimap. */
    public static int routeColor() {
        return ROUTE_COLORS[Math.max(0, Math.min(ROUTE_COLORS.length - 1, routeIdx))];
    }

    public static int accent() {
        return ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, accentIdx))];
    }

    /** Ultimele cautari din GPS (cele mai recente primele). */
    public static final java.util.List<String> searches = new java.util.ArrayList<>();
    public static final int SEARCH_MAX = 8;

    public static void addSearch(String q) {
        if (q == null) return;
        String t = q.trim();
        if (t.length() < 2) return;
        searches.removeIf(x -> x.equalsIgnoreCase(t));
        searches.add(0, t);
        while (searches.size() > SEARCH_MAX) searches.remove(searches.size() - 1);
        save();
    }

    /** Intrare in istoricul destinatiilor. */
    public static final class Hist {
        public String id = "";
        public String name = "";
        public double x;
        public double z;
        public String dim = "minecraft:overworld";
        public int color = 0xFF4DA3FF;
        public long at;
    }

    public static final int HIST_MAX = 20;
    public static final java.util.List<Hist> history = new java.util.ArrayList<>();
    private static boolean loaded;

    /** Adauga in istoric (cea mai recenta prima; aceeasi destinatie nu se repeta). */
    public static void addHistory(String id, String name, double x, double z, String dim, int color) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Hist h = history.get(i);
            if (h.name.equals(name) && Math.abs(h.x - x) < 1 && Math.abs(h.z - z) < 1) history.remove(i);
        }
        Hist h = new Hist();
        h.id = id == null ? "" : id;
        h.name = name;
        h.x = x;
        h.z = z;
        h.dim = dim;
        h.color = color;
        h.at = System.currentTimeMillis();
        history.add(0, h);
        while (history.size() > HIST_MAX) history.remove(history.size() - 1);
        save();
    }

    /** Reface setarile GPS la valorile implicite (favoritele si istoricul raman). */
    public static void resetSettings() {
        showZones = null;
        zoom = 0f;
        hiddenLayers.clear();
        hiddenLayers.add("roads");
        grid = false;
        showDisabled = true;
        navSound = true;
        notify = true;
        hudOn = true;
        pickSound = true;
        keepLast = true;
        showCoords = true;
        showDist = true;
        uiScale = 1f;
        opacity = 1f;
        accentIdx = 0;
        routeIdx = 0;
        ro.solarstudios.flowcity.gps.MiniMapSettings.hiddenCats.clear();
        save();
    }

    static {
        // reteaua de drumuri e ascunsa implicit pe harta jucatorilor (se vede doar traseul)
        hiddenLayers.add("roads");
    }

    public static boolean layerOn(String id) {
        return !hiddenLayers.contains(id);
    }

    public static void toggleLayer(String id) {
        if (!hiddenLayers.remove(id)) hiddenLayers.add(id);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("flowcity-gps.json");
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject o = new Gson().fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), JsonObject.class);
            if (o == null) return;
            fav.clear();
            if (o.has("fav") && o.get("fav").isJsonArray()) for (JsonElement e : o.getAsJsonArray("fav")) fav.add(e.getAsString());
            if (o.has("zones")) showZones = o.get("zones").getAsBoolean();
            if (o.has("zoom")) zoom = o.get("zoom").getAsFloat();
            hiddenLayers.clear();
            if (o.has("layersOff") && o.get("layersOff").isJsonArray()) for (JsonElement e : o.getAsJsonArray("layersOff")) hiddenLayers.add(e.getAsString());
            if (!o.has("roadsInit")) hiddenLayers.add("roads");
            if (o.has("navSound")) navSound = o.get("navSound").getAsBoolean();
            if (o.has("grid")) grid = o.get("grid").getAsBoolean();
            if (o.has("showDisabled")) showDisabled = o.get("showDisabled").getAsBoolean();
            if (o.has("notify")) notify = o.get("notify").getAsBoolean();
            if (o.has("hudOn")) hudOn = o.get("hudOn").getAsBoolean();
            if (o.has("pickSound")) pickSound = o.get("pickSound").getAsBoolean();
            if (o.has("keepLast")) keepLast = o.get("keepLast").getAsBoolean();
            if (o.has("showCoords")) showCoords = o.get("showCoords").getAsBoolean();
            if (o.has("showDist")) showDist = o.get("showDist").getAsBoolean();
            if (o.has("uiScale")) uiScale = Math.max(0.85f, Math.min(1.25f, o.get("uiScale").getAsFloat()));
            if (o.has("opacity")) opacity = Math.max(0.6f, Math.min(1f, o.get("opacity").getAsFloat()));
            if (o.has("accent")) accentIdx = Math.max(0, Math.min(ACCENTS.length - 1, o.get("accent").getAsInt()));
            if (o.has("route")) routeIdx = Math.max(0, Math.min(ROUTE_COLORS.length - 1, o.get("route").getAsInt()));
            searches.clear();
            if (o.has("searches") && o.get("searches").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("searches")) {
                    if (searches.size() < SEARCH_MAX) searches.add(e.getAsString());
                }
            }
            history.clear();
            if (o.has("history") && o.get("history").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("history")) {
                    if (!e.isJsonObject() || history.size() >= HIST_MAX) continue;
                    JsonObject h = e.getAsJsonObject();
                    Hist x = new Hist();
                    x.id = h.has("id") ? h.get("id").getAsString() : "";
                    x.name = h.has("name") ? h.get("name").getAsString() : "";
                    x.x = h.has("x") ? h.get("x").getAsDouble() : 0;
                    x.z = h.has("z") ? h.get("z").getAsDouble() : 0;
                    x.dim = h.has("dim") ? h.get("dim").getAsString() : "minecraft:overworld";
                    x.color = h.has("color") ? h.get("color").getAsInt() : 0xFF4DA3FF;
                    x.at = h.has("at") ? h.get("at").getAsLong() : 0L;
                    if (!x.name.isEmpty()) history.add(x);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fisier lipsa sau stricat: preferinte implicite
        }
    }

    public static void save() {
        try {
            JsonObject o = new JsonObject();
            JsonArray a = new JsonArray();
            for (String s : fav) a.add(s);
            o.add("fav", a);
            if (showZones != null) o.addProperty("zones", showZones);
            if (zoom > 0f) o.addProperty("zoom", Math.round(zoom * 100f) / 100f);
            JsonArray lo = new JsonArray();
            for (String l : hiddenLayers) lo.add(l);
            o.add("layersOff", lo);
            o.addProperty("roadsInit", true);
            o.addProperty("navSound", navSound);
            o.addProperty("grid", grid);
            o.addProperty("showDisabled", showDisabled);
            o.addProperty("notify", notify);
            o.addProperty("hudOn", hudOn);
            o.addProperty("pickSound", pickSound);
            o.addProperty("keepLast", keepLast);
            o.addProperty("showCoords", showCoords);
            o.addProperty("showDist", showDist);
            o.addProperty("uiScale", uiScale);
            o.addProperty("opacity", opacity);
            o.addProperty("accent", accentIdx);
            o.addProperty("route", routeIdx);
            JsonArray sa = new JsonArray();
            for (String q : searches) sa.add(q);
            o.add("searches", sa);
            JsonArray ha = new JsonArray();
            for (Hist x : history) {
                JsonObject h = new JsonObject();
                h.addProperty("id", x.id);
                h.addProperty("name", x.name);
                h.addProperty("x", x.x);
                h.addProperty("z", x.z);
                h.addProperty("dim", x.dim);
                h.addProperty("color", x.color);
                h.addProperty("at", x.at);
                ha.add(h);
            }
            o.add("history", ha);
            Files.write(file(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nu putem salva: preferintele raman doar in sesiune
        }
    }
}
