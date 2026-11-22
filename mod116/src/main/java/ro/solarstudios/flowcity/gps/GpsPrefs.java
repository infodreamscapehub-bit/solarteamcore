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
    private static boolean loaded;

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
            Files.write(file(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nu putem salva: preferintele raman doar in sesiune
        }
    }
}
