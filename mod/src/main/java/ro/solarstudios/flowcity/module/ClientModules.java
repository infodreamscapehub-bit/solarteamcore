package ro.solarstudios.flowcity.module;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.PacketBuffer;

/** Registrul modulelor client. */
public final class ClientModules {
    private static final Map<String, ClientModule> MODULES = new LinkedHashMap<>();

    private ClientModules() {}

    public static void register(ClientModule m) {
        MODULES.put(m.id(), m);
    }

    public static void dispatch(String id, String action, PacketBuffer buf) {
        ClientModule m = MODULES.get(id);
        if (m == null) return;
        try {
            m.onMessage(action, buf);
        } catch (RuntimeException e) {
            // mesaj corupt: ignoram, nu blocam clientul
        }
    }

    public static void logout() {
        for (ClientModule m : MODULES.values()) {
            try {
                m.onLogout();
            } catch (RuntimeException ignored) {
                // la iesire
            }
        }
    }
}
