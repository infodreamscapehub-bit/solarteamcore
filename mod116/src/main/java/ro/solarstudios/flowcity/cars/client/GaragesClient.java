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
import ro.solarstudios.flowcity.client.ui.Gfx;
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
        public int slots;
        public int boatSlots;
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
    public static KeyBinding infoKey;
    public static KeyBinding tripSwitchKey;
    public static KeyBinding tripResetKey;
    /** Lumini care clipesc la incuiere: id entitate -> {momentul, incuiat (1/0)}. */
    private static final java.util.Map<Integer, long[]> flashes = new java.util.HashMap<>();
    public static final Set<String> factions = new HashSet<>();
    public static KeyBinding interact;

    private static String prompt;
    private static String lastPrompt = "";
    private static long promptSince;
    private static Runnable action;

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        interact = new KeyBinding("key.flowcity.interact", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_E, "key.categories.flowcity");
        e.register(interact);
        lockKey = new KeyBinding("key.flowcity.carlock", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_U, "key.categories.flowcity");
        e.register(lockKey);
        infoKey = new KeyBinding("key.flowcity.carinfo", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_I, "key.categories.flowcity");
        e.register(infoKey);
        tripSwitchKey = new KeyBinding("key.flowcity.tripswitch", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, "key.categories.flowcity");
        e.register(tripSwitchKey);
        tripResetKey = new KeyBinding("key.flowcity.tripreset", InputMappings.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "key.categories.flowcity");
        e.register(tripResetKey);
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
                    g.slots = JsonClientModule.integer(j, "slots", 0);
                    g.boatSlots = JsonClientModule.integer(j, "bslots", 0);
                    g.sx = JsonClientModule.num(j, "sx", 0);
                    g.sy = JsonClientModule.num(j, "sy", 0);
                    g.sz = JsonClientModule.num(j, "sz", 0);
                    g.r = (float) JsonClientModule.num(j, "r", 4);
                    g.access = JsonClientModule.bool(j, "access", true);
                    garages.add(g);
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
            case "tune_cars":
                TuningScreen.cars(o);
                break;
            case "mech":
                if (mc.screen instanceof MecanicScreen) ((MecanicScreen) mc.screen).update(o);
                else mc.setScreen(new MecanicScreen(o));
                break;
            case "plate":
                CarHud.setPlate(o.has("id") ? o.get("id").getAsInt() : -1, o.has("plate") ? o.get("plate").getAsString() : "");
                break;
            case "odo":
                CarHud.setOdo(JsonClientModule.integer(o, "eid", -1), JsonClientModule.num(o, "odo", 0), JsonClientModule.num(o, "tripA", 0),
                    JsonClientModule.num(o, "tripB", 0));
                if (JsonClientModule.bool(o, "meta", false)) CarHud.setMeta(JsonClientModule.str(o, "model", ""), JsonClientModule.str(o, "plate", ""),
                    JsonClientModule.str(o, "owner", ""), JsonClientModule.str(o, "reg", ""), JsonClientModule.str(o, "vid", ""));
                break;
            case "inspect":
                if (mc.screen instanceof PoliceVehicleScreen) ((PoliceVehicleScreen) mc.screen).update(o);
                else mc.setScreen(new PoliceVehicleScreen(o));
                break;
            case "fuel": {
                // serverul a validat pompa si vehiculul: deschidem terminalul
                net.minecraft.entity.Entity v = mc.level == null ? null : mc.level.getEntity(JsonClientModule.integer(o, "entity", -1));
                if (v != null) {
                    mc.setScreen(new FuelScreen(JsonClientModule.str(o, "name", "Benzinarie"), v,
                        JsonClientModule.str(o, "vehicle", ""), (float) JsonClientModule.num(o, "cap", 0)));
                }
                break;
            }
            case "fuelprog":
                if (mc.screen instanceof FuelScreen) {
                    ((FuelScreen) mc.screen).progress(JsonClientModule.num(o, "bought", 0),
                        JsonClientModule.num(o, "done", 0), JsonClientModule.bool(o, "end", false));
                }
                break;
            default:
                break;
        }
    }

    private static int color(G g) {
        if ("pound".equals(g.type)) return 0xE5484D;
        if ("factiune".equals(g.type)) return 0x7FD6FF;
        return 0x4DA3FF;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static String typeLabel(G g) {
        if ("pound".equals(g.type)) return "POUND";
        if ("factiune".equals(g.type)) {
            String f = g.faction == null ? "" : g.faction.toUpperCase();
            return f.isEmpty() ? "GARAJ FACTIUNE" : "FACTIUNE " + f;
        }
        return "GARAJ PUBLIC";
    }

    private static Object dimLevel;
    private static String dimName = "";

    /** Numele dimensiunii: se calculeaza o singura data pe lume, nu in fiecare tick/cadru. */
    private static String dim(Minecraft mc) {
        if (mc.level != dimLevel) {
            dimLevel = mc.level;
            dimName = mc.level.dimension().location().toString();
        }
        return dimName;
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
                Vector3d rp = root.position();
                double dist = g.store ? rp.distanceTo(new Vector3d(g.sx, g.sy, g.sz)) : rp.distanceTo(new Vector3d(g.mx, g.my, g.mz));
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
                    prompt = "pound".equals(g.type) ? "Pound: scoate masina ridicata" : "Deschide garajul (" + g.name + ")";
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
                    action = () -> CarsNet.send("fuelopen", veh.getId(), fs.id);
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
            if (mc.screen != null) {
                CarHud.tickKeys(mc, false, false);
                return;
            }
            boolean inVeh = mc.player.getVehicle() != null;
            if (tripSwitchKey != null) {
                boolean sw = false;
                while (tripSwitchKey.consumeClick()) sw = true;
                if (sw && inVeh) CarHud.tripSel ^= 1;
            }
            CarHud.tickKeys(mc, tripResetKey != null && tripResetKey.isDown(), inVeh);
            findPrompt(mc);
            if (lockKey != null) {
                boolean lk = false;
                while (lockKey.consumeClick()) lk = true;
                if (lk) CarsNet.send("lock", 0, 0);
            }
            if (infoKey != null) {
                boolean ik = false;
                while (infoKey.consumeClick()) ik = true;
                if (ik && mc.player.getVehicle() != null) CarHud.showInfo = !CarHud.showInfo;
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
            long now = System.currentTimeMillis();
            if (!prompt.equals(lastPrompt)) {
                lastPrompt = prompt;
                promptSince = now;
            }
            float a = Math.min(1f, (now - promptSince) / 220f);
            a = 1f - (1f - a) * (1f - a) * (1f - a);
            float size = 8.6f;
            float kw = Math.max(16f, Gfx.width(key, 9f, true) + 12f);
            float tw = Gfx.width(prompt, size, false);
            float w = kw + tw + 32f;
            float h = 24f;
            float x = mc.getWindow().getGuiScaledWidth() / 2f - w / 2f;
            float y = mc.getWindow().getGuiScaledHeight() - 92f + (1f - a) * 8f;
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 350.0);
            if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(g, "prompt", x, y, w, h)) return;
            Gfx.rrect(g, x - 1, y + 2, w + 2, h + 2, 8, Gfx.fade(0x55000000, a));
            Gfx.rrect(g, x, y, w, h, 7, Gfx.fade(0xEE0B1E36, a), Gfx.fade(0xEE102A4A, a));
            int acc1 = ro.solarstudios.flowcity.client.ui.HudLayout.accent("prompt", 0xFF7FBFFF);
            int acc2 = ro.solarstudios.flowcity.client.ui.HudLayout.accent("prompt", 0xFF4DA3FF);
            Gfx.rrect(g, x, y + 4, 2.5f, h - 8, 1, Gfx.fade(acc2, a));
            Gfx.rrect(g, x + 5 - pulse, y + 3 - pulse, kw + 2 + 2 * pulse, h - 6 + 2 * pulse, 7, Gfx.fade(Gfx.fade(acc1, 0.18f), a * (1f - pulse * 0.5f)));
            Gfx.rrect(g, x + 6, y + 6, kw, h - 8, 5, Gfx.fade(0xFF061426, a));
            Gfx.rrect(g, x + 6, y + 4, kw, h - 8, 5, Gfx.fade(0xFFFFFFFF, a), Gfx.fade(0xFFD4E3F5, a));
            Gfx.text(g, key, x + 6 + kw / 2f, y + h / 2f + 3.1f, 9f, Gfx.fade(0xFF061426, a), 1, true, 0f);
            Gfx.text(g, prompt, x + kw + 16f, y + h / 2f + 3.1f, size, Gfx.fade(0xFFFFFFFF, a), 0);
            ro.solarstudios.flowcity.client.ui.HudLayout.end(g);
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
            long now = System.currentTimeMillis();
            boolean inVehicle = mc.player.getRootVehicle() != mc.player;
            int[] cards = {0};
            for (G g : garages) {
                if (!g.dim.equals(d)) continue;
                double dsq = c.distanceToSqr(g.mx, g.my, g.mz);
                if (dsq > 64 * 64) continue;
                float dist = (float) Math.sqrt(dsq);
                boolean lock = !g.access;
                boolean pound = "pound".equals(g.type);
                int col = lock ? 0x8A97A8 : color(g);
                float fade = clamp01((64f - dist) / 16f);
                marker(ps, buf, c, g.mx, g.my, g.mz, 1.0f, col, fade * (lock ? 0.55f : 1f), now, dist < 48f);
                if (g.store && !pound && !lock) {
                    double sd = Math.sqrt(c.distanceToSqr(g.sx, g.sy, g.sz));
                    float sf = clamp01((64f - (float) sd) / 16f);
                    ring(ps, buf, c, g.sx, g.sy, g.sz, g.r, 0.07f, col, (int) ((inVehicle ? 190 : 100) * sf));
                    ring(ps, buf, c, g.sx, g.sy, g.sz, g.r - 0.07f, g.r - 0.07f, col, (int) ((inVehicle ? 34 : 14) * sf));
                    if (sd < 40 && cards[0] < 8) {
                        cards[0]++;
                        card(ps, buf, cam, c, g.sx, g.sy + 2.0, g.sz, "PARCARE", g.name, (float) sd, col, 0, sf);
                    }
                }
                if (dist < 46f && cards[0] < 8) {
                    cards[0]++;
                    card(ps, buf, cam, c, g.mx, g.my + 2.2, g.mz, g.name.toUpperCase(), typeLabel(g), dist, col, lock ? 3 : (pound ? 2 : 0), fade);
                }
            }
            for (S st : stations) {
                if (!st.dim.equals(d)) continue;
                double dsq = c.distanceToSqr(st.x, st.y, st.z);
                if (dsq > 72 * 72) continue;
                float dist = (float) Math.sqrt(dsq);
                int col = 0x2FBF71;
                float fade = clamp01((72f - dist) / 16f);
                ring(ps, buf, c, st.x, st.y, st.z, st.r, 0.07f, col, (int) ((inVehicle ? 200 : 110) * fade));
                ring(ps, buf, c, st.x, st.y, st.z, st.r - 0.07f, st.r - 0.07f, col, (int) ((inVehicle ? 34 : 14) * fade));
                float ph = (now % 3000L) / 3000f;
                ring(ps, buf, c, st.x, st.y, st.z, st.r * (0.55f + 0.45f * ph), 0.05f, col, (int) (110 * (1f - ph) * fade));
                if (dist < 46f && cards[0] < 8) {
                    cards[0]++;
                    card(ps, buf, cam, c, st.x, st.y + 2.4, st.z, st.name.toUpperCase(), "BENZINARIE", dist, col, 1, fade);
                }
            }
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

    /** Marker de sol: inel fin, val care se extinde, disc slab si fascicul vertical care se stinge in sus. */
    private static void marker(MatrixStack ps, IRenderTypeBuffer buf, Vector3d c, double x, double y, double z, float r, int rgb, float fade, long now, boolean beam) {
        float slow = 0.5f + 0.5f * (float) Math.sin(now / 900.0);
        ring(ps, buf, c, x, y, z, r, 0.07f, rgb, (int) ((130 + 80 * slow) * fade));
        float ph = (now % 2600L) / 2600f;
        ring(ps, buf, c, x, y, z, r * (0.55f + 0.75f * ph), 0.04f, rgb, (int) (120 * (1f - ph) * fade));
        ring(ps, buf, c, x, y, z, r - 0.07f, r - 0.07f, rgb, (int) (26 * fade));
        if (beam) beam(ps, buf, c, x, y, z, 5.5f, rgb, (int) (140 * fade));
    }

    /** Fascicul subtire: doua plane incrucisate, alfa maxim jos si 0 sus. */
    private static void beam(MatrixStack ps, IRenderTypeBuffer buf, Vector3d c, double x, double y, double z, float h, int rgb, int alpha) {
        if (alpha < 4) return;
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        ps.pushPose();
        ps.translate(x - c.x, y - c.y + 0.03, z - c.z);
        Matrix4f m = ps.last().pose();
        int cr = (rgb >> 16) & 255, cg = (rgb >> 8) & 255, cb = rgb & 255;
        float w = 0.05f;
        vc.vertex(m, -w, 0, 0).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, w, 0, 0).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, w, h, 0).color(cr, cg, cb, 0).endVertex();
        vc.vertex(m, -w, h, 0).color(cr, cg, cb, 0).endVertex();
        vc.vertex(m, 0, 0, -w).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, 0, 0, w).color(cr, cg, cb, alpha).endVertex();
        vc.vertex(m, 0, h, w).color(cr, cg, cb, 0).endVertex();
        vc.vertex(m, 0, h, -w).color(cr, cg, cb, 0).endVertex();
        ps.popPose();
    }

    private static void q(IVertexBuilder vc, Matrix4f m, float x0, float y0, float x1, float y1, int r, int g, int b, int a) {
        vc.vertex(m, x0, y0, 0).color(r, g, b, a).endVertex();
        vc.vertex(m, x0, y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y1, 0).color(r, g, b, a).endVertex();
        vc.vertex(m, x1, y0, 0).color(r, g, b, a).endVertex();
    }

    /**
     * Card mic deasupra markerului (mereu cu fata spre camera): fundal inchis translucid cu colturi taiate,
     * iconita, nume + tip + distanta. icon: 0 masina, 1 pompa, 2 pound, 3 lacat (fara acces).
     */
    private static void card(MatrixStack ps, IRenderTypeBuffer.Impl buf, ActiveRenderInfo cam, Vector3d c, double x, double y, double z, String title, String sub, float dist, int rgb, int icon, float fade) {
        int ba = (int) (205 * fade);
        if (ba < 8) return;
        FontRenderer font = Minecraft.getInstance().font;
        String line2 = sub + "  ·  " + (int) dist + " m";
        float k = Math.max(1f, Math.min(2.2f, dist / 10f));
        float tw = font.width(title);
        float sw = font.width(line2) * 0.8f;
        float iconW = 20f;
        float w = Math.max(tw, sw) + iconW + 14f;
        float h = 26f;
        float x0 = -w / 2f, x1 = w / 2f, y0 = 0f, y1 = h, cut = 4f;
        int cr = (rgb >> 16) & 255, cg = (rgb >> 8) & 255, cb = rgb & 255;
        boolean locked = icon == 3;
        ps.pushPose();
        ps.translate(x - c.x, y - c.y, z - c.z);
        ps.mulPose(cam.rotation());
        ps.scale(-0.025f * k, -0.025f * k, 0.025f * k);
        Matrix4f m = ps.last().pose();
        IVertexBuilder vc = buf.getBuffer(RenderType.lightning());
        // corp: colt stanga-sus si dreapta-jos taiate
        q(vc, m, x0 + cut, y0, x1 - cut, y1, 8, 22, 40, ba);
        vc.vertex(m, x0, y0 + cut, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x0, y1, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x0 + cut, y1, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x0 + cut, y0, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x1 - cut, y0, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x1 - cut, y1, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x1, y1 - cut, 0).color(8, 22, 40, ba).endVertex();
        vc.vertex(m, x1, y0, 0).color(8, 22, 40, ba).endVertex();
        // dunga de accent sus + bloc de iconita
        q(vc, m, x0 + cut, y0, x1, y0 + 1.6f, cr, cg, cb, (int) (230 * fade));
        q(vc, m, x0 + 3f, y0 + 6f, x0 + iconW - 2f, y1 - 5f, cr, cg, cb, (int) (46 * fade));
        // iconita (alb, sau gri cand e blocat)
        int ia = (int) (240 * fade);
        int ir = locked ? 150 : 240, ig = locked ? 160 : 247, ib = locked ? 175 : 255;
        float ix = x0 + 5f, iy = y0 + 9f;
        if (icon == 1) {
            q(vc, m, ix + 1f, iy, ix + 7f, iy + 11f, ir, ig, ib, ia);
            q(vc, m, ix + 2.2f, iy + 1.4f, ix + 5.8f, iy + 4.4f, 8, 22, 40, ia);
            q(vc, m, ix + 7f, iy + 3f, ix + 9f, iy + 4.2f, ir, ig, ib, ia);
            q(vc, m, ix + 8.2f, iy + 4.2f, ix + 9.4f, iy + 9f, ir, ig, ib, ia);
        } else if (locked) {
            q(vc, m, ix + 1f, iy + 5f, ix + 10f, iy + 12f, ir, ig, ib, ia);
            q(vc, m, ix + 2.4f, iy, ix + 3.8f, iy + 5f, ir, ig, ib, ia);
            q(vc, m, ix + 7.2f, iy, ix + 8.6f, iy + 5f, ir, ig, ib, ia);
            q(vc, m, ix + 2.4f, iy, ix + 8.6f, iy + 1.4f, ir, ig, ib, ia);
            q(vc, m, ix + 4.8f, iy + 7.6f, ix + 6.2f, iy + 10f, 8, 22, 40, ia);
        } else {
            q(vc, m, ix + 3f, iy, ix + 8f, iy + 3f, ir, ig, ib, ia);
            q(vc, m, ix, iy + 3f, ix + 11f, iy + 8f, ir, ig, ib, ia);
            q(vc, m, ix + 1.4f, iy + 7f, ix + 4f, iy + 10f, 8, 22, 40, ia);
            q(vc, m, ix + 7f, iy + 7f, ix + 9.6f, iy + 10f, 8, 22, 40, ia);
        }
        buf.endBatch(RenderType.lightning());
        // text: dupa ce fundalul e trimis, ca sa nu fie acoperit
        if (fade > 0.3f) {
            int ta = Math.max(5, (int) (255 * fade)) << 24;
            Matrix4f mt = ps.last().pose();
            font.drawInBatch(new StringTextComponent(title), x0 + iconW + 3f, y0 + 6f, ta | (locked ? 0xB4BDC9 : 0xFFFFFF), false, mt, buf, true, 0, 15728880);
            ps.pushPose();
            ps.scale(0.8f, 0.8f, 1f);
            Matrix4f ms = ps.last().pose();
            font.drawInBatch(new StringTextComponent(line2), (x0 + iconW + 3f) / 0.8f, (y0 + 16.5f) / 0.8f, ta | 0xA9C4E4, false, ms, buf, true, 0, 15728880);
            ps.popPose();
            buf.endBatch();
        }
        ps.popPose();
    }
}
