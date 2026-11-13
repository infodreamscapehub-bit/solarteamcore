package ro.solarstudios.flowcity.menu;

import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Inlocuieste meniul principal Minecraft cu cel FlowCity. */
public final class MenuEvents {
    @SubscribeEvent
    public void onOpen(GuiOpenEvent e) {
        if (e.getGui() instanceof MainMenuScreen) e.setGui(new FlowMainMenu());
        else if (e.getGui() != null && e.getGui().getClass() == net.minecraft.client.gui.screen.inventory.InventoryScreen.class) {
            // inventarul vanilla -> inventarul FlowCity (aceleasi sloturi, alt desen)
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && !mc.player.isCreative()) {
                try {
                    e.setGui(new ro.solarstudios.flowcity.inventory.FlowInventoryScreen(mc.player));
                } catch (RuntimeException | LinkageError ignored) {
                    // ramane inventarul jocului
                }
            }
        }
        else if (e.getGui() != null && "com.tac.guns.client.screen.AttachmentScreen".equals(e.getGui().getClass().getName())) {
            // meniul de atasamente TaC -> meniul FlowCity (acelasi container, alt desen)
            try {
                e.setGui(new ro.solarstudios.flowcity.guns.FlowAttachScreen((net.minecraft.client.gui.screen.inventory.ContainerScreen<?>) e.getGui()));
            } catch (RuntimeException | LinkageError ignored) {
                // daca TaC s-a schimbat, ramane ecranul lor
            }
        }
        else if (e.getGui() instanceof net.minecraftforge.fml.client.gui.screen.ModListScreen) {
            // lista de moduri vanilla de la Forge -> lista FlowCity (revine la ecranul de dinainte)
            net.minecraft.client.gui.screen.Screen back = net.minecraft.client.Minecraft.getInstance().screen;
            e.setGui(new FlowModsScreen(back instanceof FlowModsScreen ? null : back));
        }
    }
}
