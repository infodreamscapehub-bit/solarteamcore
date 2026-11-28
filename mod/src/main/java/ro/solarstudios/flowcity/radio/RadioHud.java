package ro.solarstudios.flowcity.radio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** HUD-ul statiei radio active, pe dreapta: nume, frecventa, nr. de membri, rolul, CONNECTED si cine vorbeste (element "radio" din editorul de HUD). */
public final class RadioHud {
    private RadioHud() {}

    private static final float W = 150f;
    private static final float H = 66f;
    private static final int GREEN = 0xFF4CD08A;

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGuiEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            JsonObject st = RadioClient.state;
            if (!st.has("active") || !st.get("active").isJsonObject()) return;
            if (!JsonClientModule.bool(st, "power", false)) return;
            if (mc.screen != null && !(mc.screen instanceof ro.solarstudios.flowcity.client.ui.HudEditorScreen)) return;
            JsonObject a = st.getAsJsonObject("active");
            GuiGraphics g = e.getGuiGraphics();
            float x = mc.getWindow().getGuiScaledWidth() - W - 8f;
            float y = mc.getWindow().getGuiScaledHeight() / 2f - 40f;
            if (!HudLayout.begin(g, "radio", x, y, W, H)) return;
            try {
                int acc = HudLayout.accent("radio", GREEN);
                Gfx.rrect(g, x - 1, y + 2, W + 2, H + 2, 10, 0x55000000);
                Gfx.rrect(g, x, y, W, H, 9, 0xEE15202F, 0xEE0A101A);
                Gfx.rrect(g, x, y + 8, 3, H - 16, 0, acc);
                Gfx.text(g, clip(JsonClientModule.str(a, "name", ""), 8.2f, true, W - 70f), x + 12, y + 15, 8.2f, 0xFFFFFFFF, 0, true, 0f);
                Gfx.text(g, JsonClientModule.str(a, "freq", "") + " MHz", x + W - 8, y + 15, 7f, acc, 2, true, 0f);
                String role = JsonClientModule.str(a, "role", "");
                String line = JsonClientModule.integer(a, "tunedCount", 0) + "/" + JsonClientModule.integer(a, "members", 0) + " conectați";
                Gfx.text(g, line, x + 12, y + 28, 6.4f, 0xFF9AA7BA, 0);
                if ("OWNER".equals(role)) Gfx.text(g, "OWNER", x + W - 8, y + 28, 6.2f, 0xFFF6C15A, 2, true, 1f);
                else if ("CO_OWNER".equals(role)) Gfx.text(g, "CO-OWNER", x + W - 8, y + 28, 6.2f, 0xFFF6C15A, 2, true, 1f);
                Gfx.text(g, "CONNECTED", x + 12, y + 41, 6.4f, GREEN, 0, true, 1.2f);
                StringBuilder who = new StringBuilder();
                if (a.has("talking") && a.get("talking").isJsonArray()) {
                    for (JsonElement n : (JsonArray) a.get("talking")) {
                        if (who.length() > 0) who.append(", ");
                        who.append(n.getAsString());
                    }
                }
                if (who.length() > 0) Gfx.text(g, clip("Vorbește: " + who, 6.6f, false, W - 24f), x + 12, y + 56, 6.6f, 0xFFFFFFFF, 0);
                else Gfx.text(g, "liniște", x + 12, y + 56, 6.4f, 0xFF5E6B80, 0);
            } finally {
                HudLayout.end(g);
            }
        }
    }
}
