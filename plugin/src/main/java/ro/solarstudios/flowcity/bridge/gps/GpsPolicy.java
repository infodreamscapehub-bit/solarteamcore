package ro.solarstudios.flowcity.bridge.gps;

import java.util.function.Predicate;

/**
 * Regulile de acces ale GPS-ului, fara dependente de Bukkit (ca sa poata fi testate direct). GpsModule le foloseste la fiecare
 * cerere venita de la client: clientul nu e niciodata de incredere, deci verificarea se face aici, pe server.
 */
final class GpsPolicy {
    private GpsPolicy() {}

    /** Teleportarea cere TOATE: functia pornita in config, statut de operator si permisiunea dedicata. */
    static boolean canTeleport(boolean enabled, boolean op, boolean hasPerm) {
        return enabled && op && hasPerm;
    }

    /** O coordonata acceptata: finita si in limitele lumii Minecraft. */
    static boolean validCoord(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= 30000000d;
    }

    /** Inaltimea Y acceptata pentru o locatie. */
    static boolean validY(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= -64 && v <= 320;
    }

    /** O locatie cu permisiune de acces o vad doar cei care o au (adminii o vad mereu, ca sa o poata edita). */
    static boolean canSee(String perm, boolean admin, Predicate<String> has) {
        if (admin) return true;
        if (perm == null || perm.isEmpty()) return true;
        return has.test(perm);
    }

    /** Numele unui nod de permisiune acceptat in editor (gol = fara restrictie). */
    static boolean validPermNode(String pm) {
        return pm != null && (pm.isEmpty() || pm.matches("[A-Za-z0-9_.*-]{1,64}"));
    }
}
