package ro.solarstudios.flowcity.maps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

import java.util.List;

/** eMaps pe client: minimap, GPS cu ruta, tasta M pentru harta mare. */
public final class MapsClient extends JsonClientModule {
    private static MapsClient instance;
    public static KeyBinding openKey;
    public static boolean needRoute;
    private static final int MM = 96;
    private static MapView mini;
    private static long lastRoute;

    private MapsClient() {
        on("pois", d -> {
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
        on("gps", d -> {
            if (bool(d, "stop", false)) {
                MapsState.clearTarget();
                return;
            }
            MapsState.setTarget(str(d, "name", "Destinatie"), num(d, "x", 0), num(d, "z", 0));
            NotifyToasts.add("INFO", "GPS pornit", "Spre " + MapsState.targetName + ".");
        });
        on("open", d -> Minecraft.getInstance().displayGuiScreen(new MapScreen()));
    }

    @Override
    public String id() {
        return "maps";
    }

    @Override
    public void onLogout() {
        MapsState.reset();
        closeMini();
    }

    private static void closeMini() {
        if (mini != null) {
            mini.close();
            mini = null;
        }
    }

    public static void register() {
        instance = new MapsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys() {
        openKey = new KeyBinding("key.flowcity.map", GLFW.GLFW_KEY_M, "key.categories.flowcity");
        ClientRegistry.registerKeyBinding(openKey);
    }

    public static void requestPois() {
        if (instance != null) instance.send("pois", new JsonObject());
    }

    public static void gpsTo(String name, double x, double z) {
        MapsState.setTarget(name, x, z);
        NotifyToasts.add("INFO", "GPS pornit", "Spre " + name + ".");
    }

    // ------------------------------------------------------------------ desen comun

    /** Deseneaza ruta ca puncte pe o vedere (minimap sau harta mare). */
    public static void drawRoute(MatrixStack ms, MapView v, int x, int y, int w, int h, int clipX0, int clipY0, int clipX1, int clipY1) {
        List<double[]> r = MapsState.route;
        Minecraft mc = Minecraft.getInstance();
        if (!MapsState.hasTarget || mc.player == null) return;
        double px = mc.player.getPosX();
        double pz = mc.player.getPosZ();
        double[] prev = new double[] {px, pz};
        int color = 0xFF3DDC84;
        for (double[] p : r) {
            segment(ms, v, x, y, w, h, prev, p, color, clipX0, clipY0, clipX1, clipY1);
            prev = p;
        }
        if (r.isEmpty()) segment(ms, v, x, y, w, h, prev, new double[] {MapsState.tx, MapsState.tz}, 0xFFFFC83D, clipX0, clipY0, clipX1, clipY1);
    }

    private static void segment(MatrixStack ms, MapView v, int x, int y, int w, int h, double[] a, double[] b, int color,
                                int c0, int c1, int c2, int c3) {
        double len = Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]));
        int steps = (int) Math.max(1, Math.ceil(len / 2.0));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            int sx = Math.round(v.sx(a[0] + (b[0] - a[0]) * t, x, w));
            int sy = Math.round(v.sy(a[1] + (b[1] - a[1]) * t, y, h));
            if (sx < c0 || sy < c1 || sx >= c2 || sy >= c3) continue;
            Ui.rect(ms, sx - 1, sy - 1, 2, 2, color);
        }
    }

    public static void drawPlayer(MatrixStack ms, float cx, float cy, float yaw) {
        ms.push();
        ms.translate(cx, cy, 0);
        ms.rotate(Vector3f.ZP.rotationDegrees(yaw - 180f));
        for (int i = 0; i < 6; i++) Ui.rect(ms, -i / 2 - 1 + (i % 2 == 0 ? 0 : 0), -4 + i, 1 + i / 2 * 2 + 1, 1, 0xFFFFFFFF);
        Ui.rect(ms, -1, 2, 2, 2, 0xFF3FA0FF);
        ms.pop();
    }

    public static void drawArrow(MatrixStack ms, float cx, float cy, float rotDeg, int color) {
        ms.push();
        ms.translate(cx, cy, 0);
        ms.rotate(Vector3f.ZP.rotationDegrees(rotDeg));
        for (int i = 0; i < 6; i++) Ui.rect(ms, -i / 2, -4 + i, 1 + (i / 2) * 2, 1, color);
        Ui.rect(ms, -1, 2, 2, 3, color);
        ms.pop();
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
            if (mc.player == null || mc.world == null) return;
            if (openKey != null && openKey.isPressed() && mc.currentScreen == null) mc.displayGuiScreen(new MapScreen());
            tick++;
            if (tick == 40) requestPois();
            if (!MapsState.hasTarget) return;
            double px = mc.player.getPosX();
            double pz = mc.player.getPosZ();
            double d = Math.sqrt((MapsState.tx - px) * (MapsState.tx - px) + (MapsState.tz - pz) * (MapsState.tz - pz));
            if (d < 5) {
                NotifyToasts.add("OK", "GPS", "Ai ajuns la " + MapsState.targetName + ".");
                FlowSounds.play("money", 0.6F);
                MapsState.clearTarget();
                return;
            }
            long now = System.currentTimeMillis();
            if (Router.busy()) {
                List<double[]> r = Router.step(1200);
                if (r != null) {
                    MapsState.route = r;
                    MapsState.routeIsStraight = false;
                    lastRoute = now;
                }
            } else {
                boolean moved = Math.hypot(px - Router.startX, pz - Router.startZ) > 8;
                if (needRoute || (now - lastRoute > 5000 && moved)) {
                    needRoute = false;
                    lastRoute = now;
                    Router.start(px, pz, MapsState.tx, MapsState.tz);
                }
            }
        }

        @SubscribeEvent
        public void onRender(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (!MapsState.minimap || mc.player == null || mc.world == null || mc.gameSettings.hideGUI) return;
            if (mc.currentScreen instanceof MapScreen) return;
            try {
                MatrixStack ms = e.getMatrixStack();
                int x = 10;
                int y = 10;
                int scale = MapsState.miniScale;
                if (mini == null) mini = new MapView(MM, "flowcity_minimap");
                int pcx = MathHelper.floor(mc.player.getPosX());
                int pcz = MathHelper.floor(mc.player.getPosZ());
                if (mini.needsRefresh(pcx, pcz, scale, 700)) mini.refresh(mc.world, pcx, pcz, scale);

                Ui.rect(ms, x - 2, y - 2, MM + 4, MM + 4, 0xCC0A1220);
                Ui.rect(ms, x - 2, y - 2, MM + 4, 2, 0xFF2F8CFF);
                mini.draw(ms, x, y, MM, MM);
                drawRoute(ms, mini, x, y, MM, MM, x, y, x + MM, y + MM);
                // puncte de interes
                for (MapsState.Poi p : MapsState.pois) {
                    float sx = mini.sx(p.x, x, MM);
                    float sy = mini.sy(p.z, y, MM);
                    boolean out = sx < x + 3 || sy < y + 3 || sx > x + MM - 3 || sy > y + MM - 3;
                    sx = Math.max(x + 3, Math.min(x + MM - 3, sx));
                    sy = Math.max(y + 3, Math.min(y + MM - 3, sy));
                    int c = MapsState.typeColor(p.type);
                    Ui.rect(ms, Math.round(sx) - 2, Math.round(sy) - 2, 5, 5, 0xFF000000);
                    Ui.rect(ms, Math.round(sx) - 1, Math.round(sy) - 1, out ? 2 : 3, out ? 2 : 3, c);
                }
                if (MapsState.hasTarget) {
                    float sx = Math.max(x + 4, Math.min(x + MM - 4, mini.sx(MapsState.tx, x, MM)));
                    float sy = Math.max(y + 4, Math.min(y + MM - 4, mini.sy(MapsState.tz, y, MM)));
                    float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 220.0);
                    Ui.rect(ms, Math.round(sx) - 3, Math.round(sy) - 3, 7, 7, Theme.alpha(0xFFFFC83D, (int) (160 + 95 * pulse)));
                    Ui.rect(ms, Math.round(sx) - 1, Math.round(sy) - 1, 3, 3, 0xFF000000);
                }
                drawPlayer(ms, x + MM / 2f, y + MM / 2f, mc.player.rotationYaw);
                Ui.textCenter(ms, "N", x + MM / 2, y + 2, 0xFFFFFFFF);

                // bara de jos: coordonate sau GPS
                int by = y + MM + 4;
                Ui.rect(ms, x - 2, by, MM + 4, 16, 0xCC0A1220);
                if (MapsState.hasTarget) {
                    double dx = MapsState.tx - mc.player.getPosX();
                    double dz = MapsState.tz - mc.player.getPosZ();
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    // directia spre urmatorul punct al rutei (sau tinta)
                    double nx = MapsState.tx;
                    double nz = MapsState.tz;
                    for (double[] p : MapsState.route) {
                        if (Math.hypot(p[0] - mc.player.getPosX(), p[1] - mc.player.getPosZ()) > 6) {
                            nx = p[0];
                            nz = p[1];
                            break;
                        }
                    }
                    float targetYaw = (float) (Math.atan2(-(nx - mc.player.getPosX()), nz - mc.player.getPosZ()) * 180.0 / Math.PI);
                    float rel = MathHelper.wrapDegrees(targetYaw - mc.player.rotationYaw);
                    drawArrow(ms, x + 8, by + 8, rel, 0xFF3DDC84);
                    Ui.text(ms, Ui.clip(MapsState.targetName, MM - 56), x + 18, by + 4, Theme.TEXT);
                    Ui.textRight(ms, meters(dist), x + MM, by + 4, 0xFFFFC83D);
                } else {
                    Ui.textCenter(ms, pcx + ", " + pcz, x + MM / 2, by + 4, Theme.MUTED);
                }
            } catch (RuntimeException ex) {
                closeMini();
                MapsState.minimap = false;
            }
        }
    }
}
