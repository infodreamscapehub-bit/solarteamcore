package ro.solarstudios.flowcity.items;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Modulul client "itemfx": afiseaza bara de progres a activitatilor cu durata, panourile itemelor (diagnoza, probe, bodycam, radio...)
 * si blitzul camerei/defibrilatorului. Clientul nu decide nimic: butoanele doar trimit serverului "panel + buton", iar serverul
 * verifica din nou ca panoul e deschis, butonul exista si itemul e inca la jucator.
 */
public final class ItemFxClient extends JsonClientModule {
    private static ItemFxClient instance;
    static String progLabel = "";
    static long progStart;
    static long progMs;
    static boolean progOn;
    static long flashAt;

    private ItemFxClient() {
        on("progress", d -> {
            progLabel = str(d, "label", "");
            progMs = lng(d, "ms", 0);
            progStart = System.currentTimeMillis();
            progOn = progMs > 0;
        });
        on("progress_end", d -> progOn = false);
        on("flash", d -> flashAt = System.currentTimeMillis());
        on("panel", d -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new ItemFxScreen(d));
        });
    }

    @Override
    public String id() {
        return "itemfx";
    }

    @Override
    public void onLogout() {
        progOn = false;
        flashAt = 0;
    }

    public static void register() {
        instance = new ItemFxClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new FxEvents());
    }

    static void act(String panelId, String btn) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("panel", panelId);
        o.addProperty("btn", btn);
        instance.send("act", o);
    }

    static void closed() {
        if (instance != null) instance.send("close", new JsonObject());
    }

    public static final class FxEvents {
        @SubscribeEvent
        public void onHud(RenderGuiEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;
            long now = System.currentTimeMillis();
            GuiGraphics g = e.getGuiGraphics();
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();
            if (flashAt > 0) {
                float t = (now - flashAt) / 450f;
                if (t >= 1f) {
                    flashAt = 0;
                } else {
                    int a = Math.max(0, Math.min(255, Math.round(230f * (1f - t) * (1f - t))));
                    Gfx.rrect(g, 0, 0, sw, sh, 0, (a << 24) | 0xFFFFFF);
                }
            }
            if (progOn) {
                float f = progMs <= 0 ? 1f : Math.max(0f, Math.min(1f, (now - progStart) / (float) progMs));
                float w = 190f;
                float x = (sw - w) / 2f;
                float y = sh - 74f;
                Gfx.rrect(g, x - 1, y + 2, w + 2, 30, 8, 0x66000000);
                Gfx.rrect(g, x, y, w, 28, 8, 0xEE15202F, 0xEE0A101A);
                Gfx.text(g, progLabel, x + 10, y + 11, 7.4f, FTheme.TEXT, 0, true, 0f);
                Gfx.text(g, Math.round(f * 100f) + "%", x + w - 10, y + 11, 7.4f, FTheme.MUTED, 2, true, 0f);
                Gfx.bar(g, x + 10, y + 16, w - 20, 5, f, 0xFF2B313C, FTheme.accent);
                if (f >= 1.5f) progOn = false;
            }
        }
    }
}
