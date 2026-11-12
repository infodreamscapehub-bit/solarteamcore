package ro.solarstudios.flowcity.menu;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Inlocuieste meniul principal Minecraft cu cel FlowCity. */
public final class MenuEvents {
    @SubscribeEvent
    public void onOpen(ScreenEvent.Opening e) {
        if (e.getNewScreen() instanceof TitleScreen) e.setNewScreen(new FlowMainMenu());
    }
}
