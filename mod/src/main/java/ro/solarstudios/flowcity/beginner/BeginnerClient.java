package ro.solarstudios.flowcity.beginner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.admin.IdsClient;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Jucatori INCEPATORI (client): primeste de la server setarile si minutele jucate ale celor sub prag, si deseneaza badge-ul deasupra
 * capului (ascuns cand jucatorul e invizibil, ghemuit, in spectator, prea departe sau - optional - cand se afiseaza #ID).
 * Meniul de configurare (staff) se deschide cu tasta "Incepatori (staff)" si salveaza prin server (validat acolo).
 */
public final class BeginnerClient extends JsonClientModule {
    private static BeginnerClient instance;
    public static KeyMapping openKey;

    static volatile boolean canAdmin;
    static boolean enabled = true;
    static double hours = 10.0;
    static String text = "INCEPATOR";
    static String icon = "star";
    static boolean showTime = true;
    static boolean hideWithId;
    static String color = "#4DD2FF";
    static String timeColor = "#B8C4D6";
    static int distance = 24;
    static double offset = 0.55;
    private static final Map<UUID, Integer> PLAYED = new ConcurrentHashMap<>();

    static final class Row {
        String name;
        int min;
        boolean beginner;
    }

    static final List<Row> rows = new ArrayList<>();

    private BeginnerClient() {
        on("state", d -> canAdmin = bool(d, "canAdmin", false));
        on("cfg", d -> {
            enabled = bool(d, "enabled", true);
            hours = num(d, "hours", 10.0);
            text = str(d, "text", "INCEPATOR");
            icon = str(d, "icon", "star");
            showTime = bool(d, "showTime", true);
            color = str(d, "color", "#4DD2FF");
            timeColor = str(d, "timeColor", "#B8C4D6");
            distance = integer(d, "distance", 24);
            offset = num(d, "offset", 0.55);
            hideWithId = bool(d, "hideWithId", false);
        });
        on("map", d -> {
            PLAYED.clear();
            merge(d);
        });
        on("upd", BeginnerClient::merge);
        on("list", d -> {
            rows.clear();
            if (d.has("players") && d.get("players").isJsonArray()) {
                JsonArray a = d.getAsJsonArray("players");
                for (JsonElement e : a) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    Row r = new Row();
                    r.name = str(o, "name", "?");
                    r.min = integer(o, "min", 0);
                    r.beginner = bool(o, "beginner", false);
                    rows.add(r);
                }
            }
        });
        on("res", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof BeginnerScreen) ((BeginnerScreen) mc.screen).toast(str(d, "msg", ""), !bool(d, "ok", false));
        });
    }

    private static void merge(JsonObject d) {
        if (!d.has("p") || !d.get("p").isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : d.getAsJsonObject("p").entrySet()) {
            try {
                UUID u = UUID.fromString(e.getKey());
                int v = e.getValue().getAsInt();
                if (v < 0) PLAYED.remove(u);
                else PLAYED.put(u, v);
            } catch (RuntimeException ignored) {
                // intrare invalida
            }
        }
    }

    @Override
    public String id() {
        return "beginner";
    }

    @Override
    public void onLogout() {
        PLAYED.clear();
        rows.clear();
        canAdmin = false;
    }

    public static void register() {
        instance = new BeginnerClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        openKey = new KeyMapping("key.flowcity.beginner", GLFW.GLFW_KEY_F10, "key.categories.flowcity");
        e.register(openKey);
    }

    static void sendCfg(JsonObject o) {
        if (instance != null) instance.send("cfg.set", o);
    }

    static void requestList() {
        if (instance != null) instance.send("list", new JsonObject());
    }

    // ------------------------------------------------------------------ utilitare

    public static int parseColor(String hex, int def) {
        try {
            if (hex != null && hex.length() == 7 && hex.charAt(0) == '#') return Integer.parseInt(hex.substring(1), 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            // hex invalid
        }
        return def;
    }

    public static String glyph(String icon) {
        switch (icon) {
            case "star":
                return "★";
            case "spark":
                return "✦";
            case "diamond":
                return "◆";
            case "clover":
                return "✿";
            case "cross":
                return "✚";
            case "arrow":
                return "➤";
            default:
                return "";
        }
    }

    public static String label(String icon, String text) {
        String g = glyph(icon);
        if (g.isEmpty()) return text;
        return text.isEmpty() ? g : g + " " + text;
    }

    public static String remaining(int playedMin, double hoursThr) {
        int left = Math.max(0, (int) Math.ceil(hoursThr * 60.0) - playedMin);
        int h = left / 60;
        int m = left % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    // ------------------------------------------------------------------ evenimente

    public static final class Events {
        @SubscribeEvent
        public void onRender(RenderPlayerEvent.Post e) {
            if (!enabled) return;
            Minecraft mc = Minecraft.getInstance();
            Player pl = e.getEntity();
            if (mc.player == null || pl == mc.player || mc.options.hideGui) return;
            Integer played = PLAYED.get(pl.getUUID());
            if (played == null || played < 0) return;
            if (pl.isInvisible() || pl.isCrouching() || pl.isSpectator() || !pl.isAlive()) return;
            double dist = distance;
            if (mc.player.distanceToSqr(pl) > dist * dist) return;
            if (hideWithId && IdsClient.showsId(pl.getUUID())) return;
            String line = label(icon, text);
            if (line.isEmpty()) return;
            Font font = mc.font;
            PoseStack ms = e.getGuiGraphics().pose();
            ms.pushPose();
            ms.translate(0.0, pl.getBbHeight() + 0.5 + offset, 0.0);
            ms.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            ms.scale(-0.025f, -0.025f, 0.025f);
            Matrix4f m = ms.last().pose();
            int bg = (int) (mc.options.getBackgroundOpacity(0.25f) * 255f) << 24;
            float w = font.width(line);
            font.drawInBatch(Component.literal(line), -w / 2f, 0f, 0xFF000000 | parseColor(color, 0x4DD2FF), false, m, e.getBuffers(), false, bg, e.getLight());
            if (showTime) {
                String t = remaining(played, hours);
                float tw = font.width(t);
                font.drawInBatch(Component.literal(t), -tw / 2f, 10f, 0xFF000000 | parseColor(timeColor, 0xB8C4D6), false, m, e.getBuffers(), false, 0, e.getLight());
            }
            ms.popPose();
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || openKey == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (openKey.consumeClick()) {
                if (mc.screen != null) continue;
                if (!canAdmin) {
                    mc.player.displayClientMessage(Component.literal("§c[Incepatori] §fNu ai permisiunea."), true);
                    continue;
                }
                mc.setScreen(new BeginnerScreen());
                requestList();
            }
        }
    }
}
