package ro.solarstudios.flowcity.cars.client;

import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "garaje": serverul (comanda /garaje din Bridge) cere deschiderea ecranului de administrare a garajelor. */
public final class GarageAdminClient extends JsonClientModule {
    private GarageAdminClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof GarageAdminScreen)) mc.setScreen(new GarageAdminScreen());
        });
    }

    @Override
    public String id() {
        return "garaje";
    }

    public static void register() {
        ClientModules.register(new GarageAdminClient());
    }
}
