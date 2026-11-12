package ro.solarstudios.flowcity;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import ro.solarstudios.flowcity.client.ClientInit;
import ro.solarstudios.flowcity.net.Bridge;

/**
 * FlowCityCore - SolarStudios (Forge 1.20.1). Modul pentru serverul FlowCity (Arclight/Mohist 1.20.1).
 * Trebuie pus si pe server (Arclight), altfel clientul pierde itemele din registry. Pachetele ajung la pluginul
 * Bukkit pe canalul flowcity:bridge.
 */
@Mod(FlowCity.MODID)
public class FlowCity {
    public static final String MODID = "flowcitycore";

    public FlowCity() {
        ro.solarstudios.flowcity.content.ModContent.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ro.solarstudios.flowcity.content.GpsEvents());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ro.solarstudios.flowcity.content.CityMapScanner.Events());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> Bridge::init);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::register);
    }
}
