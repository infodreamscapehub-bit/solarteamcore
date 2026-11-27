package ro.solarstudios.flowcity.hudeditor;

import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.HudEditorScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "hudeditor": serverul (comanda /hudeditor) cere deschiderea editorului de HUD. */
public final class HudEditorClient extends JsonClientModule {
    private HudEditorClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof HudEditorScreen)) mc.setScreen(new HudEditorScreen());
        });
    }

    @Override
    public String id() {
        return "hudeditor";
    }

    public static void register() {
        ClientModules.register(new HudEditorClient());
    }
}
