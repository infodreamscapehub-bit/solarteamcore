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
 * Nu inregistreaza blocuri/iteme: itemele (telefon, cheie GPS, acte) sunt iteme vanilla cu tag, create de pluginul FlowCityBridge.
 * Serverul nu are nevoie de acest mod; pachetele ajung la pluginul Bukkit pe canalul flowcity:bridge.
 */
@Mod(FlowCity.MODID)
public class FlowCity {
    public static final String MODID = "flowcitycore";

    public FlowCity() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
            () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> Bridge::init);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientInit::register);
    }
}
