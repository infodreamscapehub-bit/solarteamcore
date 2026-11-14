package ro.solarstudios.flowcity.taxi;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Taximetrul si sageata de preluare din HUD (element mutabil "taxi" din editorul de HUD). Datele vin la ~0,5 s de la server. */
public final class TaxiHud {
    private TaxiHud() {}

    private static final int YELLOW = 0xFFF6C15A;
    private static final float W = 156f;
    private static final float H = 64f;

    private static String mode = "none";
    private static String role = "";
    private static String name = "";
    private static long lastAt;
    private static int fare;
    private static double km;
    private static long secs;
    private static long wait;
    private static double tx;
    private static double tz;
    private static int dist = -1;
    private static boolean same = true;

    static void apply(JsonObject d) {
        mode = JsonClientModule.str(d, "mode", "none");
        lastAt = System.currentTimeMillis();
        if ("none".equals(mode)) return;
        role = JsonClientModule.str(d, "role", "");
        name = JsonClientModule.str(d, "name", "");
        if ("ride".equals(mode)) {
            fare = JsonClientModule.integer(d, "fare", 0);
            km = JsonClientModule.num(d, "km", 0);
            secs = (long) JsonClientModule.num(d, "secs", 0);
            wait = (long) JsonClientModule.num(d, "wait", 0);
        } else {
            tx = JsonClientModule.num(d, "x", 0);
            tz = JsonClientModule.num(d, "z", 0);
            dist = JsonClientModule.integer(d, "dist", -1);
            same = JsonClientModule.bool(d, "same", true);
        }
    }

    static void clear() {
        mode = "none";
    }

    private static boolean active() {
        return !"none".equals(mode) && System.currentTimeMillis() - lastAt < 3500L;
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (!active() || mc.player == null || mc.options.hideGui) return;
            if (mc.screen != null && !(mc.screen instanceof ro.solarstudios.flowcity.client.ui.HudEditorScreen)) return;
            GuiGraphics g = new GuiGraphics(e.getMatrixStack());
            float x = 10f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f - 34f;
            if (!HudLayout.begin(g, "taxi", x, y, W, H)) return;
            try {
                int acc = HudLayout.accent("taxi", YELLOW);
                Gfx.rrect(g, x - 1, y + 2, W + 2, H + 2, 10, 0x55000000);
                Gfx.rrect(g, x, y, W, H, 9, 0xEE15202F, 0xEE0A101A);
                Gfx.rrect(g, x, y + 8, 3, H - 16, 0, acc);
                if ("ride".equals(mode)) ride(g, x, y, acc);
                else pickup(g, mc, x, y, acc);
            } finally {
                HudLayout.end(g);
            }
        }
    }

    private static void ride(GuiGraphics g, float x, float y, int acc) {
        Gfx.text(g, "TAXIMETRU", x + 12, y + 14, 6.4f, acc, 0, true, 1.2f);
        String who = ("driver".equals(role) ? "Client: " : "Șofer: ") + name;
        Gfx.text(g, clip(who, 6.4f, false, 84f), x + W - 8, y + 14, 6.4f, 0xFF9AA7BA, 2);
        Gfx.text(g, fare + " lei", x + 12, y + 41, 17f, 0xFFFFFFFF, 0, true, 0f);
        Gfx.text(g, String.format(java.util.Locale.ROOT, "%.2f km", km), x + W - 8, y + 36, 8f, 0xFFFFFFFF, 2, true, 0f);
        Gfx.text(g, String.format(java.util.Locale.ROOT, "%02d:%02d", secs / 60, secs % 60), x + W - 8, y + 48, 8f, 0xFFC9D4E4, 2, true, 0f);
        Gfx.text(g, "așteptare " + wait + " s", x + 12, y + 57, 6.4f, 0xFF7B8AA0, 0);
    }

    private static void pickup(GuiGraphics g, Minecraft mc, float x, float y, int acc) {
        boolean driver = "driver".equals(role);
        Gfx.text(g, driver ? "PRELUARE CLIENT" : "TAXI-UL VINE", x + 12, y + 14, 6.4f, acc, 0, true, 1.2f);
        Gfx.text(g, clip(name, 8.4f, true, 92f), x + 12, y + 28, 8.4f, 0xFFFFFFFF, 0, true, 0f);
        double dx = tx - mc.player.getX();
        double dz = tz - mc.player.getZ();
        int d = same ? (int) Math.round(Math.sqrt(dx * dx + dz * dz)) : -1;
        String ds = d >= 0 ? d + " m" : "altă lume";
        Gfx.text(g, ds, x + 12, y + 50, 15f, 0xFFFFFFFF, 0, true, 0f);
        float cx = x + W - 32f;
        float cy = y + H / 2f;
        Gfx.disc(g, cx, cy, 21f, 0xFF0C1522);
        Gfx.disc(g, cx, cy, 19f, 0xFF1A2A40);
        if (d < 0) return;
        if (d <= 3) {
            Gfx.disc(g, cx, cy, 8f, 0xFF4CD08A);
            return;
        }
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = target - mc.player.yRot;
        rel = ((rel % 360.0) + 540.0) % 360.0 - 180.0;
        double a = Math.toRadians(rel);
        float ux = (float) Math.sin(a);
        float uy = (float) -Math.cos(a);
        float px = -uy;
        float py = ux;
        float hx = cx - ux * 3f;
        float hy = cy - uy * 3f;
        Gfx.line(g, hx, hy, cx - ux * 13f, cy - uy * 13f, 5f, 0xFF9AA7BA);
        Gfx.poly(g, new float[] {cx + ux * 15f, cy + uy * 15f, hx + px * 10f, hy + py * 10f, hx - px * 10f, hy - py * 10f}, acc);
    }
}
