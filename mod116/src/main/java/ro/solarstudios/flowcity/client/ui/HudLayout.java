package ro.solarstudios.flowcity.client.ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraftforge.fml.loading.FMLPaths;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Pozitiile elementelor de HUD alese de jucator (editorul de HUD): mutare, marime si vizibilitate pentru fiecare element.
 * Se pastreaza in config/flowcity-hud.json. Fiecare element de HUD isi deseneaza continutul intre begin() si end().
 */
public final class HudLayout {
    private HudLayout() {}

    public static final class El {
        public final String id;
        public final String label;
        public float dx;
        public float dy;
        public float scale = 1f;
        public boolean visible = true;
        /** Ultimul dreptunghi desenat (inainte de transformare) si cand a fost vazut. */
        public float rx;
        public float ry;
        public float rw;
        public float rh;
        public long seen;

        El(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public boolean isDefault() {
            return dx == 0f && dy == 0f && scale == 1f && visible;
        }

        public void reset() {
            dx = 0f;
            dy = 0f;
            scale = 1f;
            visible = true;
        }
    }

    public static final Map<String, El> ELS = new LinkedHashMap<>();

    static {
        add("hud", "Panou principal");
        add("speedo", "Bord mașină");
        add("toasts", "Notificări");
        add("news", "Știri CNN");
        add("prompt", "Mesaj interacțiune");
        load();
    }

    private static void add(String id, String label) {
        ELS.put(id, new El(id, label));
    }

    public static El get(String id) {
        return ELS.get(id);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("flowcity-hud.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonObject root = new Gson().fromJson(new String(Files.readAllBytes(f), StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) return;
            for (El e : ELS.values()) {
                JsonElement je = root.get(e.id);
                if (je == null || !je.isJsonObject()) continue;
                JsonObject o = je.getAsJsonObject();
                e.dx = o.has("dx") ? o.get("dx").getAsFloat() : 0f;
                e.dy = o.has("dy") ? o.get("dy").getAsFloat() : 0f;
                e.scale = Math.max(0.5f, Math.min(2f, o.has("scale") ? o.get("scale").getAsFloat() : 1f));
                e.visible = !o.has("visible") || o.get("visible").getAsBoolean();
            }
        } catch (IOException | RuntimeException ignored) {
            // fisier lipsa sau stricat: raman pozitiile implicite
        }
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            for (El e : ELS.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("dx", Math.round(e.dx * 10f) / 10f);
                o.addProperty("dy", Math.round(e.dy * 10f) / 10f);
                o.addProperty("scale", Math.round(e.scale * 100f) / 100f);
                o.addProperty("visible", e.visible);
                root.add(e.id, o);
            }
            Files.write(file(), new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ignored) {
            // nu se poate scrie: pozitiile ramn doar pentru sesiunea curenta
        }
    }

    /**
     * Aplica pozitia aleasa de jucator: muta si scaleaza fata de centrul dreptunghiului implicit (x, y, w, h).
     * Intoarce false daca elementul e ascuns (atunci nu se deseneaza si nu se apeleaza end()).
     */
    public static boolean begin(GuiGraphics g, String id, float x, float y, float w, float h) {
        El e = ELS.get(id);
        if (e == null) return true;
        e.rx = x;
        e.ry = y;
        e.rw = w;
        e.rh = h;
        e.seen = System.currentTimeMillis();
        if (!e.visible) return false;
        g.pose().pushPose();
        if (!e.isDefault()) {
            float cx = x + w / 2f;
            float cy = y + h / 2f;
            g.pose().translate(cx + e.dx, cy + e.dy, 0);
            g.pose().scale(e.scale, e.scale, 1f);
            g.pose().translate(-cx, -cy, 0);
        }
        return true;
    }

    public static void end(GuiGraphics g) {
        g.pose().popPose();
    }

    /** Dreptunghiul de pe ecran al elementului dupa mutare + scalare (x, y, w, h), pornind de la dreptunghiul desenat. */
    public static float[] rect(El e, float x, float y, float w, float h) {
        float cx = x + w / 2f + e.dx;
        float cy = y + h / 2f + e.dy;
        float nw = w * e.scale;
        float nh = h * e.scale;
        return new float[] {cx - nw / 2f, cy - nh / 2f, nw, nh};
    }

    public static float[] rect(String id) {
        El e = ELS.get(id);
        return rect(e, e.rx, e.ry, e.rw, e.rh);
    }

    /** True daca elementul a fost desenat chiar acum (are continut real pe ecran). */
    public static boolean live(El e) {
        return System.currentTimeMillis() - e.seen < 1500;
    }

    /** Dreptunghiul implicit al unui element pe care nu l-am vazut desenat (pentru editor). */
    public static float[] defaults(String id, int sw, int sh) {
        switch (id) {
            case "hud":
                return new float[] {sw - 244f, 3f, 244f, 110f};
            case "speedo": {
                float d = Math.max(90f, Math.min(128f, sh * 0.24f));
                return new float[] {sw - 17f - d, sh - 13f - d, d, d};
            }
            case "toasts":
                return new float[] {sw - 218f, Math.max(8f, ro.solarstudios.flowcity.hype.HypeHud.occupiedBottom), 208f, 64f};
            case "news": {
                float w = Math.min(372f, sw - 20f);
                return new float[] {(sw - w) / 2f, 6f, w, 54f};
            }
            default:
                return new float[] {sw / 2f - 90f, sh - 92f, 180f, 24f};
        }
    }
}
