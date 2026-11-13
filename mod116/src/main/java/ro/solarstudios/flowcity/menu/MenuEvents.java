package ro.solarstudios.flowcity.menu;

import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Inlocuieste meniul principal Minecraft cu cel FlowCity. */
public final class MenuEvents {
    @SubscribeEvent
    public void onOpen(GuiOpenEvent e) {
        if (e.getGui() instanceof MainMenuScreen) e.setGui(new FlowMainMenu());
        else if (e.getGui() instanceof net.minecraftforge.fml.client.gui.screen.ModListScreen) {
            // lista de moduri vanilla de la Forge -> lista FlowCity (revine la ecranul de dinainte)
            net.minecraft.client.gui.screen.Screen back = net.minecraft.client.Minecraft.getInstance().screen;
            e.setGui(new FlowModsScreen(back instanceof FlowModsScreen ? null : back));
        }
    }
}
