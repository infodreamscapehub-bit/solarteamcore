package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Detectie server-side a vehiculelor MrCrayfish Vehicle Mod pe Arclight 1.16.5, fara dependenta de compilare de mod.
 * Metoda: vehiculul jucatorului ({@code getVehicle()}, ca in CarsServer/TaxiModule) trebuie sa fie o entitate a carei clasa NMS
 * (obtinuta prin {@code CraftEntity#getHandle()}) are in ierarhie un pachet {@code com.mrcrayfish.vehicle.} - numele claselor din moduri
 * NU sunt remapate de Arclight, deci verificarea e stabila. Rezerva: namespace-ul cheii tipului de entitate ("vehicle"). Soferul este
 * primul pasager (ca in {@code getControllingPassenger()} al modului). Rezultatul pe clasa e tinut in cache.
 */
public final class VehicleLink {
    private static final Map<Class<?>, Boolean> CACHE = new ConcurrentHashMap<Class<?>, Boolean>();
    private static final Map<Class<?>, Method> HANDLE = new ConcurrentHashMap<Class<?>, Method>();
    private static volatile String[] prefixes = {"com.mrcrayfish.vehicle."};
    private static volatile String[] namespaces = {"vehicle"};

    private VehicleLink() {
    }

    /** Schimba prefixele de clasa / namespace-urile acceptate (din fjobs.yml). */
    public static void configure(List<String> classPrefixes, List<String> ns) {
        if (classPrefixes != null && !classPrefixes.isEmpty()) prefixes = classPrefixes.toArray(new String[0]);
        if (ns != null && !ns.isEmpty()) namespaces = ns.toArray(new String[0]);
        CACHE.clear();
    }

    /** Radacina lantului de vehicule al jucatorului (vehiculul in care sta), sau null. */
    public static Entity vehicleOf(Player p) {
        Entity v = p.getVehicle();
        int guard = 0;
        while (v != null && v.getVehicle() != null && guard++ < 4) v = v.getVehicle();
        return v;
    }

    /** Vehiculul Vehicle Mod in care sta jucatorul sau null (nu e in vehicul / nu e din Vehicle Mod). */
    public static Entity modVehicle(Player p) {
        Entity v = vehicleOf(p);
        return v != null && isVehicleMod(v) ? v : null;
    }

    /** Jucatorul este SOFERUL vehiculului (primul pasager, locul care controleaza masina). */
    public static boolean isDriver(Player p, Entity v) {
        if (v == null) return false;
        List<Entity> ps = v.getPassengers();
        return !ps.isEmpty() && ps.get(0).getUniqueId().equals(p.getUniqueId());
    }

    /** Vehiculul Vehicle Mod condus de jucator, sau null. */
    public static Entity drivenBy(Player p) {
        Entity v = modVehicle(p);
        return v != null && isDriver(p, v) ? v : null;
    }

    /** Un alt jucator asezat in acelasi vehicul (pasagerul), sau null. */
    public static Player otherPlayer(Entity v, Player driver) {
        if (v == null) return null;
        for (Entity e : v.getPassengers()) {
            if (e instanceof Player && !e.getUniqueId().equals(driver.getUniqueId())) return (Player) e;
        }
        return null;
    }

    public static boolean isVehicleMod(Entity e) {
        Class<?> k = e.getClass();
        Boolean c = CACHE.get(k);
        if (c != null && !c) return viaKey(e);
        if (c != null) return true;
        boolean ok = viaHandle(e);
        if (ok) CACHE.put(k, Boolean.TRUE);
        else {
            CACHE.put(k, Boolean.FALSE);
            ok = viaKey(e);
        }
        return ok;
    }

    private static boolean viaHandle(Entity e) {
        try {
            Method m = HANDLE.get(e.getClass());
            if (m == null) {
                m = e.getClass().getMethod("getHandle");
                HANDLE.put(e.getClass(), m);
            }
            Object h = m.invoke(e);
            for (Class<?> c = h == null ? null : h.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                String n = c.getName();
                for (String pre : prefixes) if (n.startsWith(pre)) return true;
            }
        } catch (Throwable ignored) {
            // CraftBukkit/Arclight fara getHandle (nu se poate verifica pe aceasta cale)
        }
        return false;
    }

    private static boolean viaKey(Entity e) {
        try {
            String key = e.getType().getKey().toString();
            for (String ns : namespaces) if (key.startsWith(ns + ":")) return true;
        } catch (Throwable ignored) {
            // tip necunoscut (UNKNOWN) sau cheie indisponibila
        }
        return false;
    }
}
