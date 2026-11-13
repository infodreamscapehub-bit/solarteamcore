package ro.solarstudios.flowcity.maps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** eMaps pe client, in stil GTA V: radar rotit, puncte de interes, zone, harta mare (tasta M). */
public final class MapsClient extends JsonClientModule {
    private static MapsClient instance;
    public static KeyBinding openKey;
    public static KeyBinding waypointKey;
    public static final int MM = 108;
    private static MapView radar;
    private static byte[][] parts;
    private static String partVersion = "";
    private static int lastZoneId = -2;
    /** Zona curenta a jucatorului (null = in afara zonelor). */
    public static MapsState.Zone currentZone;

    private MapsClient() {
        on("pois", d -> {
            MapsState.admin = bool(d, "admin", false);
            MapsState.pois.clear();
            if (d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonObject o = e.getAsJsonObject();
                    MapsState.Poi p = new MapsState.Poi();
                    p.id = integer(o, "id", 0);
                    p.name = str(o, "name", "?");
                    p.type = str(o, "type", "altele");
                    p.x = num(o, "x", 0);
                    p.y = num(o, "y", 0);
                    p.z = num(o, "z", 0);
                    MapsState.pois.add(p);
                }
            }
        });
        on("citymap_info", d -> {
            String v = str(d, "v", "");
            if (v.isEmpty()) return;
            if (!v.equals(cityVersion()) || MapCache.chunkCount() < 50) {
                JsonObject o = new JsonObject();
                o.addProperty("have", MapCache.chunkCount() < 50 ? "" : cityVersion());
                send("citymap_req", o);
            }
        });
        on("citymap_part", d -> {
            String v = str(d, "v", "");
            int n = integer(d, "n", 0);
            int i = integer(d, "i", 0);
            if (n <= 0 || n > 400 || i < 0 || i >= n) return;
            if (!v.equals(partVersion) || parts == null || parts.length != n) {
                partVersion = v;
                parts = new byte[n][];
            }
            parts[i] = java.util.Base64.getDecoder().decode(str(d, "d", ""));
            for (byte[] p : parts) if (p == null) return;
            int len = 0;
            for (byte[] p : parts) len += p.length;
            byte[] all = new byte[len];
            int off = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, all, off, p.length);
                off += p.length;
            }
            parts = null;
            MapCache.mergeServer(all);
            saveCityVersion(v);
            NotifyToasts.add("OK", "Harta orasului", "Harta orasului a fost actualizata.");
        });
        on("wps", d -> {
            MapsState.wps.clear();
            if (d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonObject o = e.getAsJsonObject();
                    MapsState.Wp w = new MapsState.Wp();
                    w.id = integer(o, "id", 0);
                    w.name = str(o, "name", "?");
                    w.color = integer(o, "color", 0xFF33CCFF);
                    w.type = str(o, "type", "");
                    w.x = num(o, "x", 0);
                    w.y = num(o, "y", 0);
                    w.z = num(o, "z", 0);
                    MapsState.wps.add(w);
                }
            }
        });
        on("zones", d -> {
            MapsState.zones.clear();
            if (d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonObject o = e.getAsJsonObject();
                    MapsState.Zone z = new MapsState.Zone();
                    z.id = integer(o, "id", 0);
                    z.name = str(o, "name", "?");
                    z.type = integer(o, "type", 3);
                    if (o.has("pts")) {
                        for (JsonElement pe : o.getAsJsonArray("pts")) {
                            com.google.gson.JsonArray a = pe.getAsJsonArray();
                            z.pts.add(new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()});
                        }
                    }
                    if (z.pts.size() >= 3) MapsState.zones.add(z);
                }
            }
            lastZoneId = -2;
        });
        on("open", d -> Minecraft.getInstance().setScreen(new MapScreen(bool(d, "editor", false))));
    }

    @Override
    public String id() {
        return "maps";
    }

    @Override
    public void onLogout() {
        MapsState.reset();
        MapCache.unload();
        closeRadar();
    }

    private static void closeRadar() {
        if (radar != null) {
            radar.close();
            radar = null;
        }
    }

    public static void register() {
        MapsState.loadSettings();
        instance = new MapsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        openKey = new KeyBinding("key.flowcity.map", GLFW.GLFW_KEY_M, "key.categories.flowcity");
        event.register(openKey);
        waypointKey = new KeyBinding("key.flowcity.waypoint", GLFW.GLFW_KEY_B, "key.categories.flowcity");
        event.register(waypointKey);
    }

    private static java.nio.file.Path cityVerFile() {
        return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("flowcity-mapcache").resolve("citymap.ver");
    }

    private static String cityVersion() {
        try {
            return new String(java.nio.file.Files.readAllBytes(cityVerFile()), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void saveCityVersion(String v) {
        try {
            java.nio.file.Files.createDirectories(cityVerFile().getParent());
            java.nio.file.Files.write(cityVerFile(), v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ignored) {
            // nu e critic
        }
    }

    /** Admin: scaneaza orasul in jurul tau pe server (raza in blocuri). */
    public static void scanCity(int radius) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("r", radius);
        instance.send("citymap_scan", o);
    }

    public static void requestPois() {
        if (instance != null) instance.send("pois", new JsonObject());
    }

    // ------------------------------------------------------------------ editor (admin)

    public static void savePoi(int id, String name, String type, double x, double y, double z) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("type", type);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        instance.send("poi_save", o);
    }

    public static void deletePoi(int id) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        instance.send("poi_del", o);
    }

    public static void saveWp(int id, String name, int color, String type, double x, double y, double z) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("color", color);
        o.addProperty("type", type == null ? "" : type);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        instance.send("wp_save", o);
    }

    public static void deleteWp(int id) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        instance.send("wp_del", o);
    }

    public static void saveZone(int id, String name, int type, java.util.List<double[]> pts) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("type", type);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (double[] p : pts) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            a.add(Math.round(p[0]));
            a.add(Math.round(p[1]));
            arr.add(a);
        }
        o.add("pts", arr);
        instance.send("zone_save", o);
    }

    public static void deleteZone(int id) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        instance.send("zone_del", o);
    }

    public static void give(String item) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        instance.send("give", o);
    }

    // ------------------------------------------------------------------ desen comun

    public static void drawArrow(GuiGraphics ms, float cx, float cy, float rotDeg, int color) {
        ms.pose().pushPose();
        ms.pose().translate(cx, cy, 0);
        ms.pose().mulPose(Vector3f.ZP.rotationDegrees(rotDeg));
        for (int i = 0; i < 6; i++) Ui.rect(ms, -i / 2 - 0, -4 + i, 1 + (i / 2) * 2, 1, color);
        Ui.rect(ms, -1, 2, 2, 3, color);
        ms.pose().popPose();
    }

    /** Sageata jucatorului pe harta mare: orientata dupa privire (yaw MC). */
    public static void drawPlayer(GuiGraphics ms, float cx, float cy, float yaw) {
        drawArrow(ms, cx, cy, yaw - 180f, 0xFFFFFFFF);
        Ui.rect(ms, Math.round(cx) - 1, Math.round(cy), 2, 2, 0xFF2F8CFF);
    }

    public static String meters(double d) {
        return d >= 1000 ? String.format("%.1f km", d / 1000.0) : Math.round(d) + " m";
    }

    // ------------------------------------------------------------------ evenimente

    public static final class Events {
        private int tick;

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            try {
                MapCache.tick(mc.level, mc.player);
            } catch (RuntimeException ignored) {
                // harta nu trebuie sa strice jocul
            }
            if (openKey != null && openKey.consumeClick() && mc.screen == null) mc.setScreen(new MapScreen());
            if (waypointKey != null && waypointKey.consumeClick() && mc.screen == null) mc.setScreen(MapScreen.quickWaypoint());

            tick++;
            if (tick == 40) requestPois();
            if (tick % 10 == 0) {
                MapsState.Zone z = MapsState.zoneAt(mc.player.getX(), mc.player.getZ());
                int zid = z == null ? -1 : z.id;
                currentZone = z;
                if (zid != lastZoneId) {
                    boolean first = lastZoneId == -2;
                    lastZoneId = zid;
                    if (z != null && !first) {
                        Object[] t = MapsState.zoneType(z.type);
                        NotifyToasts.add(z.type == 1 ? "BAD" : (z.type == 2 ? "OK" : "INFO"), "Zona: " + z.name, t[1] + " - " + t[2]);
                    } else if (z == null && !first) {
                        NotifyToasts.add("INFO", "Ai iesit din zona", "Esti in afara zonelor marcate.");
                    }
                }
            }
        }

        @SubscribeEvent
        public void onRender(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.options.hideGui) return;
            if (mc.screen instanceof MapScreen) return;
            try {
                WorldMarkers.draw(new GuiGraphics(e.getMatrixStack()), mc);
            } catch (RuntimeException ex) {
                MapsState.worldMarkers = false;
            }
            if (!MapsState.minimap) return;
            try {
                drawRadar(new GuiGraphics(e.getMatrixStack()), mc);
            } catch (RuntimeException ex) {
                closeRadar();
                MapsState.minimap = false;
            }
        }

        private void drawRadar(GuiGraphics ms, Minecraft mc) {
            int scale = MapsState.miniScale;
            if (radar == null) radar = new MapView(160, "flowcity_radar", true);
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            if (radar.needsRefresh(px, pz, scale, 140, 140, 1500)) radar.refresh(MathHelper.floor(px), MathHelper.floor(pz), scale);

            int x = 14;
            int y = MapsState.radarPos == 0 ? mc.getWindow().getGuiScaledHeight() - 14 - MM - 14 : 12;
            int cx = x + MM / 2;
            int cy = y + MM / 2;
            float rot = 180f - mc.player.yRot;
            double rad = Math.toRadians(rot);
            final float cos = (float) Math.cos(rad);
            final float sin = (float) Math.sin(rad);

            // rama
            Ui.rect(ms, x - 3, y - 3, MM + 6, MM + 6, 0xFF05070C);
            Ui.rect(ms, x - 2, y - 2, MM + 4, MM + 4, 0xFF8892A6);
            Ui.rect(ms, x - 1, y - 1, MM + 2, MM + 2, 0xFF05070C);
            Ui.scissor(x, y, MM, MM);
            radar.drawRotated(ms, cx, cy, px, pz, rot);
            Ui.endScissor();


            // puncte de interes
            for (MapsState.Poi p : MapsState.allPois()) {
                if (MapsState.hidden.contains(p.type)) continue;
                float[] r = rel(p.x, p.z, px, pz, scale, cos, sin);
                boolean out = Math.abs(r[0]) > MM / 2f - 6 || Math.abs(r[1]) > MM / 2f - 6;
                if (out) {
                    float k = (MM / 2f - 6) / Math.max(Math.abs(r[0]), Math.abs(r[1]));
                    r[0] *= k;
                    r[1] *= k;
                }
                Ui.rect(ms, Math.round(cx + r[0]) - 1, Math.round(cy + r[1]) - 1, 3, 3, 0xFFFFFFFF);
                if (!out) Blips.draw(ms, p.type, Math.round(cx + r[0]), Math.round(cy + r[1]), 8, false);
            }
            // N
            float nx = sin;
            float ny = -cos;
            float kn = (MM / 2f - 7) / Math.max(Math.abs(nx), Math.abs(ny));
            Ui.rect(ms, Math.round(cx + nx * kn) - 5, Math.round(cy + ny * kn) - 5, 11, 11, 0xCC05070C);
            Ui.textCenter(ms, "N", Math.round(cx + nx * kn), Math.round(cy + ny * kn) - 4, 0xFFFF5050);
            // jucator
            drawArrow(ms, cx, cy, 0f, 0xFFFFFFFF);
            Ui.rect(ms, cx - 1, cy, 2, 2, 0xFF2F8CFF);

            // bare viata / armura
            float hp = Math.max(0f, Math.min(1f, mc.player.getHealth() / Math.max(1f, mc.player.getMaxHealth())));
            float ar = Math.max(0f, Math.min(1f, mc.player.getArmorValue() / 20f));
            Ui.rect(ms, x, y + MM + 4, MM, 4, 0xAA05070C);
            Ui.rect(ms, x, y + MM + 4, Math.round(MM * hp), 4, 0xFF4CD964);
            Ui.rect(ms, x, y + MM + 9, MM, 3, 0xAA05070C);
            Ui.rect(ms, x, y + MM + 9, Math.round(MM * ar), 3, 0xFF3FA0FF);

            // zona curenta
            if (currentZone != null) {
                int zy = MapsState.radarPos == 0 ? y - 12 : y + MM + 16;
                int rgb = 0xFF000000 | MapsState.zoneRgb(currentZone.type);
                Ui.rect(ms, x, zy, MM, 11, 0xCC05070C);
                Ui.rect(ms, x, zy, 3, 11, rgb);
                Ui.text(ms, Ui.clip(currentZone.name, MM - 10), x + 7, zy + 2, Theme.TEXT);
            }
        }

        private float[] rel(double wx, double wz, double px, double pz, float scale, float cos, float sin) {
            double dx = (wx - px) / scale;
            double dz = (wz - pz) / scale;
            return new float[] {(float) (dx * cos - dz * sin), (float) (dx * sin + dz * cos)};
        }

    }
}
