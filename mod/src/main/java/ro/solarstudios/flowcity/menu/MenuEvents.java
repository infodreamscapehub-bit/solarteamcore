package ro.solarstudios.flowcity.menu;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Inlocuieste meniul principal Minecraft, inventarul, meniul de atasamente TaC si lista de moduri cu cele FlowCity. */
public final class MenuEvents {
    @SubscribeEvent
    public void onOpen(ScreenEvent.Opening e) {
        net.minecraft.client.gui.screens.Screen s = e.getNewScreen();
        if (s instanceof TitleScreen) e.setNewScreen(new FlowMainMenu());
        else if (s != null && s.getClass() == net.minecraft.client.gui.screens.inventory.InventoryScreen.class) {
            // inventarul vanilla -> inventarul FlowCity (aceleasi sloturi, alt desen)
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && !mc.player.isCreative()) {
                try {
                    e.setNewScreen(new ro.solarstudios.flowcity.inventory.FlowInventoryScreen(mc.player));
                } catch (RuntimeException | LinkageError ignored) {
                    // ramane inventarul jocului
                }
            }
        } else if (s != null && "com.tac.guns.client.screen.AttachmentScreen".equals(s.getClass().getName())) {
            // meniul de atasamente TaC -> meniul FlowCity (acelasi container, alt desen)
            try {
                e.setNewScreen(new ro.solarstudios.flowcity.guns.FlowAttachScreen((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) s));
            } catch (RuntimeException | LinkageError ignored) {
                // daca TaC s-a schimbat, ramane ecranul lor
            }
        } else if (s instanceof net.minecraftforge.client.gui.ModListScreen) {
            // lista de moduri vanilla de la Forge -> lista FlowCity (revine la ecranul de dinainte)
            net.minecraft.client.gui.screens.Screen back = net.minecraft.client.Minecraft.getInstance().screen;
            e.setNewScreen(new FlowModsScreen(back instanceof FlowModsScreen ? null : back));
        }
    }
}
