package ro.solarstudios.flowcity;

import org.apache.commons.lang3.tuple.Pair;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import ro.solarstudios.flowcity.client.ClientInit;
import ro.solarstudios.flowcity.net.Bridge;

/**
 * FlowCityCore - SolarStudios. Modul CLIENT pentru serverul FlowCity (Mohist/Arclight).
 * Inregistreaza bancomatul, telefoanele, cardurile si bancnotele (din EGK). Modul trebuie pus si pe server (Mohist).
 * Pachetele ajung la pluginul Bukkit pe canalul flowcity:bridge.
 */
@Mod(FlowCity.MODID)
public class FlowCity {
    public static final String MODID = "flowcitycore";

    public FlowCity() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
            () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        ro.solarstudios.flowcity.content.ModContent.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ro.solarstudios.flowcity.content.GpsEvents());
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ro.solarstudios.flowcity.content.CityMapScanner.Events());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> Bridge::init);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::register);
    }
}
