package ro.solarstudios.flowcity.voice;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Legatura de voce pentru apeluri. NU depinde de PlasmoVoice, deci poate fi apelata (si prin reflexie, din pluginul Bukkit)
 * chiar daca PlasmoVoice lipseste. Addonul {@link PhoneVoiceAddon} citeste perechile de aici.
 */
public final class CallVoice {
    private CallVoice() {}

    private static final Map<UUID, UUID> PARTNERS = new ConcurrentHashMap<>();
    static volatile Consumer<UUID> onUnlink;

    /** Porneste conversatia vocala privata intre a si b. */
    public static void link(UUID a, UUID b) {
        PARTNERS.put(a, b);
        PARTNERS.put(b, a);
    }

    /** Opreste conversatia vocala a jucatorului (si a partenerului). */
    public static void unlink(UUID a) {
        UUID b = PARTNERS.remove(a);
        if (b != null) PARTNERS.remove(b);
        Consumer<UUID> hook = onUnlink;
        if (hook != null) {
            hook.accept(a);
            if (b != null) hook.accept(b);
        }
    }

    static UUID partner(UUID a) {
        return PARTNERS.get(a);
    }
}
