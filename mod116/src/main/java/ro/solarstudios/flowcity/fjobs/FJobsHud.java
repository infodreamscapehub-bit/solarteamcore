package ro.solarstudios.flowcity.fjobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Cardul turei din HUD (element mutabil "fjobs" din editorul de HUD): jobul, dificultatea, liniile obiectivului curent si bara de progres. Datele vin de la server. */
public final class FJobsHud {
    private FJobsHud() {}

    private static final int ACCENT = 0xFF8B5CF6;
    private static final float W = 176f;
    private static JsonObject hud;

    static void apply(JsonObject d) {
        hud = d != null && d.has("title") ? d : null;
    }

    static void clear() {
        hud = null;
    }

    private static String clip(String s, float size, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, false) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, false) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            JsonObject h = hud;
            Minecraft mc = Minecraft.getInstance();
            if (h == null || mc.player == null || mc.options.hideGui) return;
            if (mc.screen != null && !(mc.screen instanceof ro.solarstudios.flowcity.client.ui.HudEditorScreen)) return;
            JsonArray lines = h.has("lines") && h.get("lines").isJsonArray() ? h.getAsJsonArray("lines") : new JsonArray();
            int n = Math.min(5, lines.size());
            double prog = h.has("p") ? h.get("p").getAsDouble() : -1;
            float height = 26f + n * 9.5f + (prog >= 0 ? 10f : 4f);
            float x = 10f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f - 110f;
            GuiGraphics g = new GuiGraphics(e.getMatrixStack());
            if (!HudLayout.begin(g, "fjobs", x, y, W, height)) return;
            try {
                int acc = HudLayout.accent("fjobs", ACCENT);
                Gfx.rrect(g, x - 1, y + 2, W + 2, height + 2, 10, 0x55000000);
                Gfx.rrect(g, x, y, W, height, 9, 0xEE15162B, 0xEE0A0B1A);
                Gfx.rrect(g, x, y + 8, 3, height - 16, 0, acc);
                Gfx.text(g, clip(h.get("title").getAsString().toUpperCase(java.util.Locale.ROOT), 6.4f, 104f), x + 12, y + 14, 6.4f, acc, 0, true, 1.1f);
                if (h.has("sub")) Gfx.text(g, h.get("sub").getAsString(), x + W - 8, y + 14, 6f, 0xFF9AA7BA, 2);
                for (int i = 0; i < n; i++) {
                    Gfx.text(g, clip(lines.get(i).getAsString(), 6.8f, W - 22f), x + 12, y + 28 + i * 9.5f, 6.8f, i == 0 ? 0xFFFFFFFF : 0xFFC9D4E4, 0);
                }
                if (prog >= 0) Gfx.bar(g, x + 12, y + height - 10, W - 24, 4, (float) Math.max(0, Math.min(1, prog)), 0xFF1F2547, acc);
            } finally {
                HudLayout.end(g);
            }
        }
    }
}
