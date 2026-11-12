package ro.solarstudios.flowcity.compat;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;

/** Pe 1.16.5 tastele se inregistreaza in FMLClientSetupEvent; aceeasi forma ca evenimentul din 1.20.1. */
public final class RegisterKeyMappingsEvent {
    public void register(KeyBinding key) {
        ClientRegistry.registerKeyBinding(key);
    }
}
