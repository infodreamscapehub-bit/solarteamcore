package ro.solarstudios.flowcity.bridge.module;

import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.net.Wire;

/**
 * Un modul de server (Hype, eMaps, ePhone...). Mesajele client->server cu acest id ajung in {@link #onMessage}.
 * Raspunsurile se trimit cu {@code plugin.channel().sendModule(player, id(), action, body)}.
 * Toate metodele sunt apelate pe thread-ul principal al serverului.
 */
public interface BridgeModule {
    /** Id scurt, identic cu cel din modul client (ex. "hype", "maps", "phone"). */
    String id();

    /** Inregistreaza listeners/comenzi/tabele DB. */
    void enable(FlowCityBridge plugin);

    void disable();

    /** Mesaj de la client. Valideaza permisiunile si datele - clientul nu e de incredere. */
    void onMessage(Player player, String action, Wire.In in);

    /** Clientul a facut handshake (are modul instalat). Bun moment pentru sincronizarea initiala. */
    default void onClientReady(Player player) {}

    /** Jucatorul a iesit. */
    default void onQuit(Player player) {}
}
