package ro.solarstudios.flowcity.maps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.util.Mth;
import com.mojang.math.Axis;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** eMaps pe client, in stil GTA V: radar rotit, waypoint cu ruta pe reteaua GPS a serverului, harta mare (tasta M). */
public final class MapsClient extends JsonClientModule {
    private static MapsClient instance;
    public static KeyMapping openKey;
    public static KeyMapping waypointKey;
    public static boolean needRoute;
    public static final int MM = 108;
    public static final int ROUTE_COLOR = 0xFFE056FD;
    public static final int OUTLINE = 0xFF2A0E36;
    private static MapView radar;
    private static long lastRoute;
    private static long waitingSince;
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
        on("gps", d -> {
            if (bool(d, "stop", false)) {
                MapsState.clearTarget();
                return;
            }
            gpsTo(str(d, "name", "Destinatie"), str(d, "type", null), num(d, "x", 0), num(d, "z", 0));
        });
        on("route", d -> {
            waitingSince = 0;
            if (!MapsState.hasTarget) return;
            List<double[]> r = new ArrayList<>();
            if (bool(d, "ok", false) && d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonArray a = e.getAsJsonArray();
                    r.add(new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()});
                }
            }
            MapsState.route = r;
            MapsState.routeNetwork = !r.isEmpty();
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
        openKey = new KeyMapping("key.flowcity.map", GLFW.GLFW_KEY_M, "key.categories.flowcity");
        event.register(openKey);
        waypointKey = new KeyMapping("key.flowcity.waypoint", GLFW.GLFW_KEY_B, "key.categories.flowcity");
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

    /** Pune waypoint / porneste GPS. type = tipul iconitei (null = waypoint personal). */
    public static void gpsTo(String name, String type, double x, double z) {
        MapsState.setTarget(name, type, x, z);
        FlowSounds.play("notify", 0.5F);
        NotifyToasts.add("INFO", "Waypoint setat", name + " - ruta pe strazi se calculeaza.");
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

    private static void requestRoute(double tx, double tz) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("tx", tx);
        o.addProperty("tz", tz);
        waitingSince = System.currentTimeMillis();
        instance.send("route", o);
    }

    // ------------------------------------------------------------------ desen comun

    /** Ruta ca linie groasa, pe o vedere data prin functiile de transformare (lume -> ecran). */
    public interface Proj {
        float x(double wx);

        float y(double wz);
    }

    public static void drawRoute(GuiGraphics ms, Proj pr, int thick, int x0, int y0, int x1, int y1) {
        Minecraft mc = Minecraft.getInstance();
        if (!MapsState.hasTarget || mc.player == null) return;
        double[] prev = new double[] {mc.player.getX(), mc.player.getZ()};
        List<double[]> pts = MapsState.route;
        int color = MapsState.routeNetwork ? ROUTE_COLOR : 0x99E056FD;
        if (pts.isEmpty()) {
            double[] to = new double[] {MapsState.tx, MapsState.tz};
            segment(ms, pr, prev, to, thick + 2, OUTLINE, x0, y0, x1, y1, true);
            segment(ms, pr, prev, to, thick, color, x0, y0, x1, y1, true);
            return;
        }
        double[] start = prev;
        for (double[] p : pts) {
            segment(ms, pr, prev, p, thick + 2, OUTLINE, x0, y0, x1, y1, false);
            prev = p;
        }
        prev = start;
        for (double[] p : pts) {
            segment(ms, pr, prev, p, thick, color, x0, y0, x1, y1, false);
            prev = p;
        }
    }

    private static void segment(GuiGraphics ms, Proj pr, double[] a, double[] b, int thick, int color, int x0, int y0, int x1, int y1, boolean dashed) {
        float ax = pr.x(a[0]);
        float ay = pr.y(a[1]);
        float bx = pr.x(b[0]);
        float by = pr.y(b[1]);
        float len = (float) Math.hypot(bx - ax, by - ay);
        int steps = (int) Math.max(1, Math.ceil(len / Math.max(1, thick - 0.5f)));
        for (int i = 0; i <= steps; i++) {
            if (dashed && (i / 3) % 2 == 1) continue;
            float t = i / (float) steps;
            int sx = Math.round(ax + (bx - ax) * t);
            int sy = Math.round(ay + (by - ay) * t);
            if (sx < x0 || sy < y0 || sx + thick > x1 || sy + thick > y1) continue;
            Ui.rect(ms, sx - thick / 2, sy - thick / 2, thick, thick, color);
        }
    }

    public static void drawArrow(GuiGraphics ms, float cx, float cy, float rotDeg, int color) {
        ms.pose().pushPose();
        ms.pose().translate(cx, cy, 0);
        ms.pose().mulPose(Axis.ZP.rotationDegrees(rotDeg));
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
            if (!MapsState.hasTarget) return;
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            double d = Math.hypot(MapsState.tx - px, MapsState.tz - pz);
            if (d < 6) {
                NotifyToasts.add("OK", "Waypoint", "Ai ajuns la " + MapsState.targetName + ".");
                FlowSounds.play("money", 0.6F);
                MapsState.clearTarget();
                return;
            }
            long now = System.currentTimeMillis();
            if (waitingSince != 0 && now - waitingSince > 4000) waitingSince = 0;
            if (waitingSince == 0 && (needRoute || now - lastRoute > 6000)) {
                needRoute = false;
                lastRoute = now;
                requestRoute(MapsState.tx, MapsState.tz);
            }
        }

        @SubscribeEvent
        public void onRender(RenderGuiEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.options.hideGui) return;
            if (mc.screen instanceof MapScreen) return;
            try {
                WorldMarkers.draw(e.getGuiGraphics(), mc);
            } catch (RuntimeException ex) {
                MapsState.worldMarkers = false;
            }
            if (!MapsState.minimap) return;
            try {
                drawRadar(e.getGuiGraphics(), mc);
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
            if (radar.needsRefresh(px, pz, scale, 140, 140, 1500)) radar.refresh(Mth.floor(px), Mth.floor(pz), scale);

            int x = 14;
            int y = MapsState.radarPos == 0 ? mc.getWindow().getGuiScaledHeight() - 14 - MM - 14 : 12;
            int cx = x + MM / 2;
            int cy = y + MM / 2;
            float rot = 180f - mc.player.getYRot();
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

            // transformare completa (x si z impreuna): o facem manual, nu prin Proj
            drawRouteRotated(ms, px, pz, scale, cx, cy, cos, sin, x, y);

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
            // waypoint
            if (MapsState.hasTarget) {
                float[] r = rel(MapsState.tx, MapsState.tz, px, pz, scale, cos, sin);
                boolean out = Math.abs(r[0]) > MM / 2f - 8 || Math.abs(r[1]) > MM / 2f - 8;
                if (out) {
                    float k = (MM / 2f - 8) / Math.max(Math.abs(r[0]), Math.abs(r[1]));
                    r[0] *= k;
                    r[1] *= k;
                }
                Blips.pin(ms, Math.round(cx + r[0]), Math.round(cy + r[1]) + 4, 8, ROUTE_COLOR);
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
                int zy = MapsState.radarPos == 0 ? y - 12 - (MapsState.hasTarget ? 12 : 0) : y + MM + 16 + (MapsState.hasTarget ? 12 : 0);
                int rgb = 0xFF000000 | MapsState.zoneRgb(currentZone.type);
                Ui.rect(ms, x, zy, MM, 11, 0xCC05070C);
                Ui.rect(ms, x, zy, 3, 11, rgb);
                Ui.text(ms, Ui.clip(currentZone.name, MM - 10), x + 7, zy + 2, Theme.TEXT);
            }
            // pulsul waypoint-ului pe radar (ca in GTA V)
            if (MapsState.hasTarget) {
                float[] r = rel(MapsState.tx, MapsState.tz, px, pz, scale, cos, sin);
                if (Math.abs(r[0]) < MM / 2f - 8 && Math.abs(r[1]) < MM / 2f - 8) {
                    float ph = (System.currentTimeMillis() % 1400L) / 1400f;
                    int rr = 4 + Math.round(ph * 9);
                    int al = Math.round((1f - ph) * 120);
                    Ui.round(ms, Math.round(cx + r[0]) - rr, Math.round(cy + r[1]) - rr + 2, rr * 2, rr * 2, Theme.alpha(ROUTE_COLOR, al));
                }
            }
            // urmatoarea manevra (GPS)
            if (MapsState.hasTarget) {
                float[] nt = nextTurn(px, pz);
                if (nt != null) {
                    int rows = (currentZone != null ? 1 : 0) + 1;
                    int by = MapsState.radarPos == 0 ? y - 12 * rows - 26 : y + MM + 16 + 12 * rows;
                    Ui.rect(ms, x, by, MM, 24, 0xDD05070C);
                    Ui.rect(ms, x, by, 3, 24, ROUTE_COLOR);
                    float turnRot = nt[1] == 1 ? 90f : (nt[1] == -1 ? -90f : (nt[1] == 2 ? 180f : 0f));
                    drawArrow(ms, x + 18, by + 12, turnRot, 0xFFFFFFFF);
                    String label = nt[1] == 1 ? "Vireaza la dreapta" : (nt[1] == -1 ? "Vireaza la stanga" : (nt[1] == 2 ? "Intoarce-te" : "Mergi inainte"));
                    Ui.text(ms, label, x + 32, by + 4, Theme.TEXT);
                    Ui.text(ms, nt[0] < 0 ? "pana la destinatie" : "peste " + meters(nt[0]), x + 32, by + 14, ROUTE_COLOR);
                }
            }
            // info waypoint
            if (MapsState.hasTarget) {
                double dist = Math.hypot(MapsState.tx - px, MapsState.tz - pz);
                int ty = MapsState.radarPos == 0 ? y - 12 : y + MM + 16;
                Ui.rect(ms, x, ty, MM, 11, 0xCC05070C);
                Ui.text(ms, Ui.clip(MapsState.targetName, MM - 46), x + 3, ty + 2, Theme.TEXT);
                Ui.textRight(ms, meters(dist), x + MM - 3, ty + 2, ROUTE_COLOR);
            }
        }

        /** {distanta pana la manevra (-1 = fara), tip: -1 stanga, 0 inainte, 1 dreapta, 2 intoarcere} */
        private float[] nextTurn(double px, double pz) {
            List<double[]> pts = MapsState.route;
            if (pts.isEmpty()) return new float[] {(float) Math.hypot(MapsState.tx - px, MapsState.tz - pz), 0f};
            int start = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                double d = Math.hypot(pts.get(i)[0] - px, pts.get(i)[1] - pz);
                if (d < best) {
                    best = d;
                    start = i;
                }
            }
            List<double[]> seq = new ArrayList<>();
            seq.add(new double[] {px, pz});
            for (int i = start; i < pts.size(); i++) seq.add(pts.get(i));
            seq.add(new double[] {MapsState.tx, MapsState.tz});
            double acc = 0;
            for (int k = 1; k + 1 < seq.size(); k++) {
                double[] a = seq.get(k - 1);
                double[] b = seq.get(k);
                double[] c = seq.get(k + 1);
                double hx = b[0] - a[0];
                double hz = b[1] - a[1];
                double nx = c[0] - b[0];
                double nz = c[1] - b[1];
                double l1 = Math.hypot(hx, hz);
                double l2 = Math.hypot(nx, nz);
                acc += l1;
                if (l1 < 0.5 || l2 < 0.5) continue;
                double cos = (hx * nx + hz * nz) / (l1 * l2);
                double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
                if (ang >= 35) {
                    double cross = hx * nz - hz * nx;
                    return new float[] {(float) acc, ang > 150 ? 2f : (cross > 0 ? 1f : -1f)};
                }
            }
            return new float[] {-1f, 0f};
        }

        private float[] rel(double wx, double wz, double px, double pz, float scale, float cos, float sin) {
            double dx = (wx - px) / scale;
            double dz = (wz - pz) / scale;
            return new float[] {(float) (dx * cos - dz * sin), (float) (dx * sin + dz * cos)};
        }

        private void drawRouteRotated(GuiGraphics ms, double px, double pz, float scale, int cx, int cy, float cos, float sin, int x, int y) {
            if (!MapsState.hasTarget) return;
            List<double[]> pts = MapsState.route;
            int color = MapsState.routeNetwork ? ROUTE_COLOR : 0x99E056FD;
            double[] prev = new double[] {px, pz};
            List<double[]> seq = new ArrayList<>();
            seq.add(prev);
            if (pts.isEmpty()) seq.add(new double[] {MapsState.tx, MapsState.tz});
            else seq.addAll(pts);
            for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i + 1 < seq.size(); i++) {
                float[] a = rel(seq.get(i)[0], seq.get(i)[1], px, pz, scale, cos, sin);
                float[] b = rel(seq.get(i + 1)[0], seq.get(i + 1)[1], px, pz, scale, cos, sin);
                float len = (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                int steps = (int) Math.max(1, Math.ceil(len / 1.5f));
                for (int s = 0; s <= steps; s++) {
                    if (pts.isEmpty() && (s / 3) % 2 == 1) continue;
                    float t = s / (float) steps;
                    int sx = Math.round(cx + a[0] + (b[0] - a[0]) * t);
                    int sy = Math.round(cy + a[1] + (b[1] - a[1]) * t);
                    if (sx < x + 1 || sy < y + 1 || sx > x + MM - 3 || sy > y + MM - 3) continue;
                    if (pass == 0) Ui.rect(ms, sx - 2, sy - 2, 5, 5, OUTLINE);
                    else Ui.rect(ms, sx - 1, sy - 1, 3, 3, color);
                }
            }
        }
    }
}
