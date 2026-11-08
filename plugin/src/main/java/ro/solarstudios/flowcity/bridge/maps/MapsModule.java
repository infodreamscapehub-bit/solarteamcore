package ro.solarstudios.flowcity.bridge.maps;

import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.module.BridgeModule;
import ro.solarstudios.flowcity.bridge.net.Wire;

/** STUB - se inlocuieste cu implementarea reala. */
public final class MapsModule implements BridgeModule {
    @Override
    public String id() {
        return "maps";
    }

    @Override
    public void enable(FlowCityBridge plugin) {}

    @Override
    public void disable() {}

    @Override
    public void onMessage(Player player, String action, Wire.In in) {}
}
