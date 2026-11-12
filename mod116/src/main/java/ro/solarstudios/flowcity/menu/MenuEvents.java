package ro.solarstudios.flowcity.menu;

import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Inlocuieste meniul principal Minecraft cu cel FlowCity. */
public final class MenuEvents {
    @SubscribeEvent
    public void onOpen(GuiOpenEvent e) {
        if (e.getGui() instanceof MainMenuScreen) e.setGui(new FlowMainMenu());
    }
}
