package ro.solarstudios.flowcity.cars.client;

import net.minecraft.util.text.StringTextComponent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.util.InputMappings;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.EntityRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.util.math.vector.Matrix4f;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.CarsNet;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Garajele pe client, ca pe FiveM: cercuri luminoase pe sol cu numele deasupra, mesaj "[E] ..." cand esti aproape,
 * tasta E (aceeasi cu inventarul: langa un garaj E deschide garajul in loc de inventar).
 */
public final class GaragesClient {
    private GaragesClient() {}

    public static final class G {
        public int id;
        public String name;
        public String type;
        public String faction;
        public String dim;
        public double mx, my, mz, sx, sy, sz;
        public boolean store;
        public float r;
        public boolean access;
    }

    /** Benzinarie (zona: din masina apesi E si alimentezi). */
    public static final class S {
        public int id;
        public String name;
        public String dim;
        public double x, y, z;
        public float r;
    }

    public static final List<G> garages = new ArrayList<>();
    public static final List<S> stations = new ArrayList<>();
    public static KeyBinding lockKey;
    /** Lumini care clipesc la incuiere: id entitate -> {momentul, incuiat (1/0)}. */
    private static final java.util.Map<Integer, long[]> flashes = new java.util.HashMap<>();
    public static final Set<String> factions = new HashSet<>();
    public static KeyBinding interact;

    private static String prompt;
    private static Runnable action;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        interact = new KeyBinding("key.flowcity.interact", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_E, "key.categories.flowcity");
        e.register(interact);
        lockKey = new KeyBinding("key.flowcity.carlock", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.flowcity");
        e.register(lockKey);
    }

    public static boolean police() {
        return factions.contains("politie") || factions.contains("admin");
    }

    /** Raspunsurile serverului (CarsNet). */
    public static void receive(String kind, String json) {
        JsonObject o;
        try {
            o = new com.google.gson.JsonParser().parse(json).getAsJsonObject();
        } catch (RuntimeException ex) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case "garages":
                garages.clear();
                for (JsonElement e : o.getAsJsonArray("garages")) {
                    JsonObject j = e.getAsJsonObject();
                    G g = new G();
                    g.id = JsonClientModule.integer(j, "id", 0);
                    g.name = JsonClientModule.str(j, "name", "Garaj");
                    g.type = JsonClientModule.str(j, "type", "public");
                    g.faction = JsonClientModule.str(j, "faction", "");
                    g.dim = JsonClientModule.str(j, "dim", "");
                    g.mx = JsonClientModule.num(j, "mx", 0);
                    g.my = JsonClientModule.num(j, "my", 0);
                    g.mz = JsonClientModule.num(j, "mz", 0);
                    g.store = JsonClientModule.bool(j, "store", false);
                    g.sx = JsonClientModule.num(j, "sx", 0);
                    g.sy = JsonClientModule.num(j, "sy", 0);
                    g.sz = JsonClientModule.num(j, "sz", 0);
                    g.r = (float) JsonClientModule.num(j, "r", 4);
                    g.access = JsonClientModule.bool(j, "access", true);
                    garages.add(g);
                }
                ro.solarstudios.flowcity.maps.MapsState.garagePois.clear();
                for (G g : garages) {
                    if (!g.access) continue;
                    ro.solarstudios.flowcity.maps.MapsState.Poi p = new ro.solarstudios.flowcity.maps.MapsState.Poi();
                    p.id = -g.id;
                    p.name = g.name;
                    p.type = "garaj";
                    p.x = g.mx;
                    p.y = g.my;
                    p.z = g.mz;
                    ro.solarstudios.flowcity.maps.MapsState.garagePois.add(p);
                }
                stations.clear();
                JsonArray sa = o.getAsJsonArray("stations");
                if (sa != null) {
                    for (JsonElement e : sa) {
                        JsonObject j = e.getAsJsonObject();
                        S st = new S();
                        st.id = JsonClientModule.integer(j, "id", 0);
                        st.name = JsonClientModule.str(j, "name", "Benzinarie");
                        st.dim = JsonClientModule.str(j, "dim", "");
                        st.x = JsonClientModule.num(j, "x", 0);
                        st.y = JsonClientModule.num(j, "y", 0);
                        st.z = JsonClientModule.num(j, "z", 0);
                        st.r = (float) JsonClientModule.num(j, "r", 6);
                        stations.add(st);
                        ro.solarstudios.flowcity.maps.MapsState.Poi p = new ro.solarstudios.flowcity.maps.MapsState.Poi();
                        p.id = -10000 - st.id;
                        p.name = st.name;
                        p.type = "benzinarie";
                        p.x = st.x;
                        p.y = st.y;
                        p.z = st.z;
                        ro.solarstudios.flowcity.maps.MapsState.garagePois.add(p);
                    }
                }
                factions.clear();
                JsonArray f = o.getAsJsonArray("factions");
                if (f != null) for (JsonElement e : f) factions.add(e.getAsString());
                break;
            case "flash":
                flashes.put(JsonClientModule.integer(o, "id", -1), new long[] {System.currentTimeMillis(), JsonClientModule.bool(o, "locked", false) ? 1 : 0});
                break;
            case "garage":
                if (mc.screen instanceof GarageScreen) ((GarageScreen) mc.screen).update(o);
                else mc.setScreen(new GarageScreen(o));
                break;
            case "plate":
                CarHud.setPlate(o.has("id") ? o.get("id").getAsInt() : -1, o.has("plate") ? o.get("plate").getAsString() : "");
                break;
            case "inspect":
                if (mc.screen instanceof PoliceVehicleScreen) ((PoliceVehicleScreen) mc.screen).update(o);
                else mc.setScreen(new PoliceVehicleScreen(o));
                break;
            default:
                break;
        }
    }

    private static int color(G g) {
        if ("pound".equals(g.type)) return 0xE0473E;
        if ("factiune".equals(g.type)) return 0x3F7FE0;
        return 0xE9A23B;
    }

    private static String dim(Minecraft mc) {
        return mc.level.dimension().location().toString();
    }

    /** Ce se poate face acum (cel mai apropiat lucru): garaj, parcare sau control de politie. */
    private static void findPrompt(Minecraft mc) {
        prompt = null;
        action = null;
        Vector3d pos = mc.player.position();
        Entity root = mc.player.getRootVehicle();
        boolean inVehicle = root != mc.player;
        String d = dim(mc);
        double best = Double.MAX_VALUE;
        for (G g : garages) {
            if (!g.dim.equals(d) || !g.access) continue;
            if (inVehicle && !"pound".equals(g.type)) {
                Vector3d z = g.store ? new Vector3d(g.sx, g.sy, g.sz) : new Vector3d(g.mx, g.my, g.mz);
                double dist = root.position().distanceTo(z);
                if (dist <= (g.store ? g.r : 6) && dist < best) {
                    best = dist;
                    final int id = g.id;
                    prompt = "Parcheaza vehiculul (" + g.name + ")";
                    action = () -> CarsNet.send("store", id, 0);
                }
            }
            if (!inVehicle) {
                double dist = pos.distanceTo(new Vector3d(g.mx, g.my, g.mz));
                if (dist <= 2.5 && dist < best) {
                    best = dist;
                    final int id = g.id;
                    prompt = "pound".equals(g.type) ? "Pound: scoate masina ridicata" : "Deschide " + g.name;
                    action = () -> CarsNet.send("open", id, 0);
                }
            }
        }
        if (inVehicle) {
            for (S st : stations) {
                if (!st.dim.equals(d)) continue;
                double dist = root.position().distanceTo(new Vector3d(st.x, st.y, st.z));
                if (dist <= st.r && dist < best) {
                    best = dist;
                    final S fs = st;
                    final Entity veh = root;
                    prompt = "Alimenteaza (" + st.name + ")";
                    action = () -> Minecraft.getInstance().setScreen(new FuelScreen(fs.id, fs.name, veh));
                }
            }
        }
        if (prompt == null && !inVehicle && police()) {
            RayTraceResult hr = mc.hitResult;
            EntityRayTraceResult eh = hr instanceof EntityRayTraceResult ? (EntityRayTraceResult) hr : null;
            if (eh != null && !(eh.getEntity() instanceof LivingEntity) && eh.getEntity().distanceTo(mc.player) < 6
                && eh.getEntity().getType().getRegistryName() != null && eh.getEntity().getType().getRegistryName().getNamespace().equals("vehicle")) {
                final int eid = eh.getEntity().getId();
                prompt = "Politie: verifica vehiculul";
                action = () -> CarsNet.send("inspect", eid, 0);
            }
        }
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.START) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) {
                prompt = null;
                return;
            }
            if (mc.screen != null) return;
            findPrompt(mc);
            if (lockKey != null) {
                boolean lk = false;
                while (lockKey.consumeClick()) lk = true;
                if (lk) CarsNet.send("lock", 0, 0);
            }
            if (interact == null) return;
            boolean pressed = false;
            while (interact.consumeClick()) pressed = true;
            if (pressed && action != null) {
                // E e si tasta inventarului: daca am folosit-o aici, inventarul nu se mai deschide
                if (interact.same(mc.options.keyInventory)) while (mc.options.keyInventory.consumeClick()) { }
                action.run();
            }
        }

        @SubscribeEvent
        public void onHud(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (prompt == null || mc.screen != null || mc.options.hideGui) return;
            GuiGraphics g = new GuiGraphics(e.getMatrixStack());
            String key = interact == null ? "E" : interact.getTranslatedKeyMessage().getString().toUpperCase();
            int kw = Ui.width(key) + 8;
            int w = kw + 8 + Ui.width(prompt) + 12;
            int x = mc.getWindow().getGuiScaledWidth() / 2 - w / 2;
            int y = mc.getWindow().getGuiScaledHeight() - 92;
            Ui.round(g, x, y, w, 18, 0xD016181D);
            Ui.round(g, x + 4, y + 3, kw, 12, Theme.ACCENT);
            Ui.textCenter(g, key, x + 4 + kw / 2, y + 5, 0xFF1A1A1A);
            Ui.text(g, prompt, x + kw + 12, y + 5, Theme.TEXT);
        }

        @SubscribeEvent
        public void onWorld(RenderWorldLastEvent e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            ActiveRenderInfo cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vector3d c = cam.getPosition();
            MatrixStack ps = e.getMatrixStack();
            IRenderTypeBuffer.Impl buf = mc.renderBuffers().bufferSource();
            String d = dim(mc);
            float t = (System.currentTimeMillis() % 2000L) / 2000f;
            float pulse = 0.75f + 0.25f * (float) Math.sin(t * Math.PI * 2);
            boolean inVehicle = mc.player.getRootVehicle() != mc.player;
            for (G g : garages) {
                if (!g.dim.equals(d) || !g.access) continue;
                if (c.distanceToSqr(g.mx, g.my, g.mz) > 64 * 64) continue;
                int col = color(g);
                ring(ps, buf, c, g.mx, g.my, g.mz, 1.0f, 0.12f, col, (int) (170 * pulse));
                ring(ps, buf, c, g.mx, g.my, g.mz, 0.85f, 0.85f, col, 40);
                if (g.store && !"pound".equals(g.type)) {
                    ring(ps, buf, c, g.sx, g.sy, g.sz, g.r, 0.18f, col, inVehicle ? 200 : 110);
                    ring(ps, buf, c, g.sx, g.sy, g.sz, g.r - 0.18f, g.r - 0.18f, col, inVehicle ? 45 : 22);
                    label(ps, buf, cam, c, g.sx, g.sy + 2.2, g.sz, "P  Parcare " + g.name, col);
                }
                label(ps, buf, cam, c, g.mx, g.my + 1.9, g.mz, g.name, col);
            }
            for (S st : stations) {
                if (!st.dim.equals(d) || c.distanceToSqr(st.x, st.y, st.z) > 80 * 80) continue;
                int col = 0x4CC878;
                ring(ps, buf, c, st.x, st.y, st.z, st.r, 0.2f, col, (int) ((inVehicle ? 210 : 120) * pulse));
                ring(ps, buf, c, st.x, st.y, st.z, st.r - 0.2f, st.r - 0.2f, col, inVehicle ? 40 : 18);
                label(ps, buf, cam, c, st.x, st.y + 2.4, st.z, "Benzinarie  " + st.name, col);
            }
            long now = System.currentTimeMillis();
            for (java.util.Iterator<java.util.Map.Entry<Integer, long[]>> it = flashes.entrySet().iterator(); it.hasNext();) {
                java.util.Map.Entry<Integer, long[]> en = it.next();
                long age = now - en.getValue()[0];
                boolean locked = en.getValue()[1] == 1;
                if (age > 900) {
                    it.remove();
                    continue;
                }
                Entity v = mc.level.getEntity(en.getKey());
                if (v == null) continue;
                // incuiere: doua clipiri; descuiere: una lunga
                boolean on = locked ? (age < 180 || (age > 320 && age < 500)) : age < 420;
                if (!on) continue;
                lights(ps, buf, cam, c, v, e.getPartialTicks());
            }
            buf.endBatch();
        }
    }

    /** Semnalizarile: patru lumini portocalii la colturile vehiculului (spre camera). */
    private static void lights(MatrixStack ps, IRenderTypeBuffer buf, ActiveRenderInfo cam, Vector3d c, Entity v, float pt) {
        double vx = v.xo + (v.getX() - v.xo) * pt;
        double vy = v.yo + (v.getY() - v.yo) * pt;
        double vz = v.zo + (v.getZ() - v.zo) * pt;
        double yaw = Math.toRadians(v.yRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = Math.cos(yaw), rz = Math.sin(yaw);
        double hw = v.getBbWidth() * 0.55 + 0.15;
        double hl = Math.max(hw * 1.4, 1.1);
        double y = vy + Math.max(0.45, v.getBbHeight() * 0.45);
        double[][] pts = {{hl, hw}, {hl, -hw}, {-hl, hw}, {-hl, -hw}};
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        for (double[] p : pts) {
            double x = vx + fx * p[0] + rx * p[1];
            double z = vz + fz * p[0] + rz * p[1];
            for (int layer = 0; layer < 2; layer++) {
                float s = layer == 0 ? 0.42f : 0.16f;
                int a = layer == 0 ? 90 : 255;
                int cr = 255, cg = layer == 0 ? 150 : 210, cb = layer == 0 ? 30 : 120;
                ps.pushPose();
                ps.translate(x - c.x, y - c.y, z - c.z);
                ps.mulPose(cam.rotation());
                Matrix4f m = ps.last().pose();
                vc.vertex(m, -s, -s, 0).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, -s, s, 0).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, s, s, 0).color(cr, cg, cb, a).endVertex();
                vc.vertex(m, s, -s, 0).color(cr, cg, cb, a).endVertex();
                ps.popPose();
            }
        }
    }

    /** Inel orizontal pe sol (thick = latimea inelului; thick >= r = disc plin). */
    private static void ring(MatrixStack ps, IRenderTypeBuffer buf, Vector3d c, double x, double y, double z, float r, float thick, int rgb, int alpha) {
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.translate(x - c.x, y - c.y + 0.03, z - c.z);
        Matrix4f m = ps.last().pose();
        int seg = 48;
        float in = Math.max(0f, r - thick);
        int cr = (rgb >> 16) & 255, cg = (rgb >> 8) & 255, cb = rgb & 255;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            vc.vertex(m, c0 * in, 0, s0 * in).color(cr, cg, cb, alpha).endVertex();
            vc.vertex(m, c0 * r, 0, s0 * r).color(cr, cg, cb, alpha).endVertex();
            vc.vertex(m, c1 * r, 0, s1 * r).color(cr, cg, cb, alpha).endVertex();
            vc.vertex(m, c1 * in, 0, s1 * in).color(cr, cg, cb, alpha).endVertex();
        }
        ps.popPose();
    }

    /** Text deasupra markerului, mereu cu fata spre camera (ca numele jucatorilor). */
    private static void label(MatrixStack ps, IRenderTypeBuffer buf, ActiveRenderInfo cam, Vector3d c, double x, double y, double z, String text, int rgb) {
        FontRenderer font = Minecraft.getInstance().font;
        ps.pushPose();
        ps.translate(x - c.x, y - c.y, z - c.z);
        ps.mulPose(cam.rotation());
        ps.scale(-0.025f, -0.025f, 0.025f);
        Matrix4f m = ps.last().pose();
        ITextComponent comp = new StringTextComponent(text);
        float w = -font.width(comp) / 2f;
        font.drawInBatch(comp, w, 0, 0xFFFFFFFF, false, m, buf, true, 0x90000000, 15728880);
        ps.popPose();
        // linie colorata sub text
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.translate(x - c.x, y - c.y, z - c.z);
        ps.mulPose(cam.rotation());
        ps.scale(-0.025f, -0.025f, 0.025f);
        Matrix4f m2 = ps.last().pose();
        float hw = font.width(comp) / 2f + 1;
        int cr = (rgb >> 16) & 255, cg = (rgb >> 8) & 255, cb = rgb & 255;
        vc.vertex(m2, -hw, 9, 0).color(cr, cg, cb, 230).endVertex();
        vc.vertex(m2, -hw, 10, 0).color(cr, cg, cb, 230).endVertex();
        vc.vertex(m2, hw, 10, 0).color(cr, cg, cb, 230).endVertex();
        vc.vertex(m2, hw, 9, 0).color(cr, cg, cb, 230).endVertex();
        ps.popPose();
    }
}
