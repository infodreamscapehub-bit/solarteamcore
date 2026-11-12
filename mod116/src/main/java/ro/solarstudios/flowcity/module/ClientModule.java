package ro.solarstudios.flowcity.module;

import net.minecraft.network.FriendlyByteBuf;

/** Un modul client (Hype, eMaps, ePhone). Apelat pe thread-ul principal al clientului. */
public interface ClientModule {
    /** Identic cu id-ul modulului de server. */
    String id();

    /** Mesaj de la server. Buffer-ul e o copie proprie si se elibereaza dupa apel. */
    void onMessage(String action, FriendlyByteBuf buf);

    /** Jucatorul a iesit de pe server: curata starea. */
    default void onLogout() {}
}
