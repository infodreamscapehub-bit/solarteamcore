package ro.solarstudios.flowcity.bridge.module;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.net.Wire;

/** Registrul modulelor de server. */
public final class Modules {
    private static final Map<String, BridgeModule> MODULES = new LinkedHashMap<>();

    private Modules() {}

    public static void register(FlowCityBridge plugin, BridgeModule m) {
        try {
            m.enable(plugin);
            MODULES.put(m.id(), m);
            plugin.getLogger().info("Modul pornit: " + m.id());
        } catch (Throwable t) {
            plugin.getLogger().severe("Modulul " + m.id() + " nu a pornit: " + t);
            t.printStackTrace();
        }
    }

    public static void disableAll() {
        for (BridgeModule m : MODULES.values()) {
            try {
                m.disable();
            } catch (Throwable ignored) {
                // la oprire
            }
        }
        MODULES.clear();
    }

    public static BridgeModule get(String id) {
        return MODULES.get(id);
    }

    public static void dispatch(Player p, String id, String action, Wire.In in) {
        BridgeModule m = MODULES.get(id);
        if (m == null) return;
        try {
            m.onMessage(p, action, in);
        } catch (RuntimeException e) {
            FlowCityBridge.getInstance().getLogger().warning("Mesaj invalid " + id + "/" + action + " de la " + p.getName() + ": " + e);
        }
    }

    public static void ready(Player p) {
        for (BridgeModule m : MODULES.values()) {
            try {
                m.onClientReady(p);
            } catch (RuntimeException e) {
                FlowCityBridge.getInstance().getLogger().warning("onClientReady " + m.id() + ": " + e);
            }
        }
    }

    public static void quit(Player p) {
        for (BridgeModule m : MODULES.values()) {
            try {
                m.onQuit(p);
            } catch (RuntimeException ignored) {
                // la iesire
            }
        }
    }
}
