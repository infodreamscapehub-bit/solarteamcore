package ro.solarstudios.flowcity.bridge.items;

import java.util.List;
import java.util.function.Predicate;

/** Verificari comune (permisiuni, distanta) ale framework-ului de iteme, fara Bukkit (testabile). */
public final class Rules {
    private Rules() {}

    /** Permisiunea e indeplinita daca lista e goala sau jucatorul are cel putin una dintre permisiuni. */
    public static boolean hasAny(Predicate<String> has, List<String> perms) {
        if (perms == null || perms.isEmpty()) return true;
        for (String p : perms) if (p != null && !p.isEmpty() && has.test(p)) return true;
        return false;
    }

    /** Distanta 3D in blocuri, in aceeasi lume; null daca lumile difera. */
    public static Double distance(String worldA, double ax, double ay, double az, String worldB, double bx, double by, double bz) {
        if (worldA == null || !worldA.equals(worldB)) return null;
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static boolean within(String worldA, double ax, double ay, double az, String worldB, double bx, double by, double bz, double max) {
        Double d = distance(worldA, ax, ay, az, worldB, bx, by, bz);
        return d != null && d <= max;
    }
}
