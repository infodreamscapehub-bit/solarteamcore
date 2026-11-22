package ro.solarstudios.flowcity.gps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.HudEditorScreen;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;

/**
 * Minimap-ul FlowCity pe HUD: element "minimap" din HudLayout (se muta/scaleaza si din editorul de HUD), taste configurabile
 * (Controls > FlowCity) si ascunderea in interfete. Harta, locatiile, zonele si destinatia vin din sistemul GPS.
 */
public final class MiniMapClient {
    private MiniMapClient() {}

    public static KeyBinding toggleKey;
    public static KeyBinding zoomInKey;
    public static KeyBinding zoomOutKey;
    public static KeyBinding centerKey;
    public static KeyBinding northKey;
    public static KeyBinding settingsKey;

    public static void register() {
        MiniMapSettings.load();
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        toggleKey = new KeyBinding("key.flowcity.minimap.toggle", GLFW.GLFW_KEY_N, "key.categories.flowcity");
        zoomInKey = new KeyBinding("key.flowcity.minimap.zoomin", GLFW.GLFW_KEY_EQUAL, "key.categories.flowcity");
        zoomOutKey = new KeyBinding("key.flowcity.minimap.zoomout", GLFW.GLFW_KEY_MINUS, "key.categories.flowcity");
        centerKey = new KeyBinding("key.flowcity.minimap.center", GLFW.GLFW_KEY_HOME, "key.categories.flowcity");
        northKey = new KeyBinding("key.flowcity.minimap.north", GLFW.GLFW_KEY_UNKNOWN, "key.categories.flowcity");
        settingsKey = new KeyBinding("key.flowcity.minimap.settings", GLFW.GLFW_KEY_UNKNOWN, "key.categories.flowcity");
        e.register(toggleKey);
        e.register(zoomInKey);
        e.register(zoomOutKey);
        e.register(centerKey);
        e.register(northKey);
        e.register(settingsKey);
    }

    /** Daca minimap-ul trebuie ascuns din cauza ecranului deschis (setarile de ascundere in interfete). */
    static boolean hiddenByScreen(Screen s) {
        if (s == null || s instanceof HudEditorScreen || s instanceof MiniMapSettingsScreen) return false;
        if (s instanceof ChatScreen) return MiniMapSettings.hideChat;
        if (s instanceof ContainerScreen) return MiniMapSettings.hideInventory;
        return MiniMapSettings.hideOther;
    }

    public static final class Events {
        @SubscribeEvent
        public void onHud(RenderGameOverlayEvent.Post e) {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.options.hideGui) return;
            boolean editor = mc.screen instanceof HudEditorScreen;
            if (!MiniMapSettings.enabled && !editor) return;
            if (MiniMapSettings.tempHidden && !editor) return;
            if (hiddenByScreen(mc.screen)) return;
            // ecranul de setari isi deseneaza propria previzualizare
            if (mc.screen instanceof MiniMapSettingsScreen) return;
            GuiGraphics g = new GuiGraphics(e.getMatrixStack());
            float[] d = MiniMapRenderer.defaults(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            float size = MiniMapSettings.size;
            if (!HudLayout.begin(g, "minimap", d[0], d[1], size, MiniMapRenderer.heightFor(size))) return;
            try {
                MiniMapRenderer.render(g, d[0], d[1], size);
            } finally {
                HudLayout.end(g);
            }
        }

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || toggleKey == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            while (toggleKey.consumeClick()) {
                if (!MiniMapSettings.enabled) {
                    MiniMapSettings.enabled = true;
                    MiniMapSettings.tempHidden = false;
                } else {
                    MiniMapSettings.tempHidden = !MiniMapSettings.tempHidden;
                }
                msg(mc, MiniMapSettings.tempHidden ? "Minimap ascuns" : "Minimap afișat");
            }
            while (zoomInKey.consumeClick()) {
                MiniMapSettings.zoomBy(1.25f);
                MiniMapRenderer.noteZoom();
                MiniMapSettings.save();
            }
            while (zoomOutKey.consumeClick()) {
                MiniMapSettings.zoomBy(1f / 1.25f);
                MiniMapRenderer.noteZoom();
                MiniMapSettings.save();
            }
            while (centerKey.consumeClick()) {
                MiniMapSettings.resetZoom();
                MiniMapRenderer.noteZoom();
                MiniMapSettings.save();
            }
            while (northKey.consumeClick()) {
                MiniMapSettings.rotate = !MiniMapSettings.rotate;
                MiniMapSettings.save();
                msg(mc, MiniMapSettings.rotate ? "Minimap: rotire după jucător" : "Minimap: North Lock (nord sus)");
            }
            while (settingsKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new MiniMapSettingsScreen());
            }
        }

        private static void msg(Minecraft mc, String s) {
            mc.player.displayClientMessage(new net.minecraft.util.text.StringTextComponent("§b[Minimap] §f" + s), true);
        }
    }
}
