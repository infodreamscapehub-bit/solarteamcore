package ro.solarstudios.flowcity.gps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.StringTextComponent;

/** Destinatia GPS activa: traseu pe drumuri (daca exista reteaua, vezi {@link RoadRouteProvider}) sau directie directa, distanta + ETA. */
public final class GpsNav {
    private GpsNav() {}

    public static boolean active;
    public static String id = "";
    public static String name = "";
    public static double x;
    public static double z;
    public static String dim = "minecraft:overworld";
    public static int color = 0xFF4DA3FF;
    public static long since;
    public static RouteProvider route = RoadRouteProvider.I;

    private static double lastX;
    private static double lastZ;
    private static long lastAt;
    /** Viteza netezata in blocuri/secunda. */
    private static double speed;
    private static int tickN;

    public static void set(String id, String name, double x, double z, String dim, int color) {
        GpsNav.id = id == null ? "" : id;
        GpsNav.name = name == null || name.isEmpty() ? "Destinație" : name;
        GpsNav.x = x;
        GpsNav.z = z;
        GpsNav.dim = dim == null || dim.isEmpty() ? "minecraft:overworld" : dim;
        GpsNav.color = color;
        GpsNav.since = System.currentTimeMillis();
        GpsNav.active = true;
        lastAt = 0;
        speed = 0;
        RoadRouteProvider.I.reset();
        GpsPrefs.load();
        GpsPrefs.addHistory(GpsNav.id, GpsNav.name, x, z, GpsNav.dim, color);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.getSoundManager().play(SimpleSound.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
    }

    public static void clear() {
        active = false;
        id = "";
        RoadRouteProvider.I.reset();
    }

    public static boolean isTarget(String locId) {
        return active && id.equals(locId);
    }

    /** Distanta in linie dreapta pana la destinatie, sau -1 daca esti in alta dimensiune / nu exista GPS. */
    public static double distance(Minecraft mc) {
        if (!active || mc.player == null || !dim.equals(GpsData.dimOf(mc))) return -1;
        double dx = x - mc.player.getX();
        double dz = z - mc.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Distanta de parcurs: pe drumuri (daca traseul urmeaza strazile), altfel in linie dreapta; -1 daca nu exista. */
    public static double travelDistance(Minecraft mc) {
        double d = distance(mc);
        if (d < 0) return d;
        double r = RoadRouteProvider.I.remaining();
        return r >= 0 ? r : d;
    }

    /** Timpul estimat in secunde (pe jos ~5 blocuri/s, cu vehicul ~13, sau viteza reala daca e mai mare). */
    public static double etaSeconds(Minecraft mc) {
        double d = travelDistance(mc);
        if (d < 0 || mc.player == null) return -1;
        double base = mc.player.getRootVehicle() != mc.player ? 13.0 : 5.0;
        return d / Math.max(base, speed);
    }

    public static String fmtDist(double d) {
        if (d < 0) return "altă lume";
        if (d < 1000) return Math.round(d) + " m";
        return String.format(java.util.Locale.ROOT, "%.1f km", d / 1000.0);
    }

    public static String fmtEta(double sec) {
        if (sec < 0) return "-";
        if (sec < 60) return "<1 min";
        long m = Math.round(sec / 60.0);
        if (m < 60) return m + " min";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    /** La fiecare tick de client: viteza si verificarea sosirii. */
    public static void tick(Minecraft mc) {
        if (!active || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (lastAt == 0) {
            lastAt = now;
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
        } else if (now - lastAt >= 1000) {
            double dx = mc.player.getX() - lastX;
            double dz = mc.player.getZ() - lastZ;
            double v = Math.sqrt(dx * dx + dz * dz) / ((now - lastAt) / 1000.0);
            speed = speed == 0 ? v : speed * 0.6 + v * 0.4;
            lastAt = now;
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
        }
        RoadRouteProvider.I.tick(mc);
        if (++tickN % 5 != 0) return;
        double d = distance(mc);
        if (d >= 0 && d <= GpsData.arrive) {
            String n = name;
            clear();
            if (GpsPrefs.notify) {
                mc.player.displayClientMessage(new StringTextComponent("§b[GPS] §fAi ajuns la destinație: §b" + n), true);
                mc.getSoundManager().play(SimpleSound.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
            }
        }
    }
}
