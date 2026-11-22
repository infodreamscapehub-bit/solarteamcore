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

/**
 * Setarile minimap-ului, salvate local pentru fiecare jucator (config/flowcity-minimap.json pe clientul lui).
 * Pozitia pe ecran se tine in HudLayout (elementul "minimap"), ca sa se poata muta si din editorul de HUD.
 */
public final class MiniMapSettings {
    private MiniMapSettings() {}

    public static final int SIZE_MIN = 80;
    public static final int SIZE_MAX = 220;
    public static final float ZOOM_MIN = 0.6f;
    public static final float ZOOM_MAX = 5f;
    public static final float OPACITY_MIN = 0.3f;

    public static boolean enabled = true;
    /** false = patrat (cu colturi taiate), true = circular. */
    public static boolean circular = false;
    /** true = harta se roteste dupa jucator; false = North Lock (nordul ramane sus). */
    public static boolean rotate = true;
    public static boolean showCoords = true;
    public static boolean showCompass = true;
    public static boolean showLocations = true;
    public static boolean showZones = false;
    public static boolean showWaypoint = true;
    public static boolean hideInventory = true;
    public static boolean hideChat = false;
    public static boolean hideOther = true;
    public static boolean lowPerf = false;
    /** Dimensiunea in pixeli GUI (latura patratului / diametrul cercului). */
    public static int size = 112;
    public static float opacity = 0.96f;
    /** Pixeli GUI pe bloc (mai mare = mai aproape). */
    public static float zoom = 1.6f;
    /** Categoriile de locatii ascunse doar pe minimap (id-urile din GpsData.cats). */
    public static final Set<String> hiddenCats = new HashSet<>();
    /** Ascundere temporara (tasta Toggle); nu se salveaza. */
    public static boolean tempHidden;

    private static boolean loaded;

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("flowcity-minimap.json");
    }

    public static void validate() {
        size = Math.max(SIZE_MIN, Math.min(SIZE_MAX, size));
        zoom = Float.isNaN(zoom) ? 1.6f : Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        opacity = Float.isNaN(opacity) ? 0.96f : Math.max(OPACITY_MIN, Math.min(1f, opacity));
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject o = new Gson().fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), JsonObject.class);
            if (o == null) return;
            enabled = bool(o, "enabled", enabled);
            circular = bool(o, "circular", circular);
            rotate = bool(o, "rotate", rotate);
            showCoords = bool(o, "coords", showCoords);
            showCompass = bool(o, "compass", showCompass);
            showLocations = bool(o, "locations", showLocations);
            showZones = bool(o, "zones", showZones);
            showWaypoint = bool(o, "waypoint", showWaypoint);
            hideInventory = bool(o, "hideInventory", hideInventory);
            hideChat = bool(o, "hideChat", hideChat);
            hideOther = bool(o, "hideOther", hideOther);
            lowPerf = bool(o, "lowPerf", lowPerf);
            if (o.has("size")) size = o.get("size").getAsInt();
            if (o.has("opacity")) opacity = o.get("opacity").getAsFloat();
            if (o.has("zoom")) zoom = o.get("zoom").getAsFloat();
            hiddenCats.clear();
            if (o.has("hiddenCats") && o.get("hiddenCats").isJsonArray()) {
                for (JsonElement e : o.getAsJsonArray("hiddenCats")) hiddenCats.add(e.getAsString());
            }
        } catch (IOException | RuntimeException ignored) {
            // fisier lipsa sau stricat: raman valorile implicite
        }
        validate();
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    public static void save() {
        validate();
        try {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("circular", circular);
            o.addProperty("rotate", rotate);
            o.addProperty("coords", showCoords);
            o.addProperty("compass", showCompass);
            o.addProperty("locations", showLocations);
            o.addProperty("zones", showZones);
            o.addProperty("waypoint", showWaypoint);
            o.addProperty("hideInventory", hideInventory);
            o.addProperty("hideChat", hideChat);
            o.addProperty("hideOther", hideOther);
            o.addProperty("lowPerf", lowPerf);
            o.addProperty("size", size);
            o.addProperty("opacity", Math.round(opacity * 100f) / 100f);
            o.addProperty("zoom", Math.round(zoom * 100f) / 100f);
            JsonArray hc = new JsonArray();
            for (String s : hiddenCats) hc.add(s);
            o.add("hiddenCats", hc);
            Files.write(file(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nu putem salva: setarile raman doar in sesiune
        }
    }

    public static void zoomBy(float f) {
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom * f));
    }

    public static void resetZoom() {
        zoom = 1.6f;
    }
}
