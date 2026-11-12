package ro.solarstudios.flowcity.maps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Starea eMaps pe client: puncte de interes, waypoint/GPS, ruta, setari radar si filtre. */
public final class MapsState {
    private MapsState() {}

    public static final class Poi {
        public int id;
        public String name = "?";
        public String type = "altele";
        public double x;
        public double y;
        public double z;
    }

    public static final class Zone {
        public int id;
        public String name = "?";
        public int type = 3;
        public final List<double[]> pts = new ArrayList<>();

        public boolean contains(double x, double z) {
            return inside(pts, x, z);
        }
    }

    /** {id, nume, regula} + culoare RGB + opacitate (ca in EGK). */
    public static final Object[][] ZONE_TYPES = {
        {1, "Periculoasa", "Jaf permis", 0xE53935, 38},
        {2, "Sigura", "Jaf interzis", 0x43A047, 34},
        {3, "Neutra", "Reguli normale", 0xFDD835, 32},
        {4, "Libera", "Fara restrictii", 0x757575, 36}};

    public static Object[] zoneType(int id) {
        return ZONE_TYPES[Math.max(0, Math.min(ZONE_TYPES.length - 1, id - 1))];
    }

    public static int zoneRgb(int id) {
        return (Integer) zoneType(id)[3];
    }

    public static boolean inside(List<double[]> pts, double x, double z) {
        boolean in = false;
        for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
            double[] a = pts.get(i);
            double[] b = pts.get(j);
            if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) in = !in;
        }
        return in;
    }

    public static Zone zoneAt(double x, double z) {
        for (int i = zones.size() - 1; i >= 0; i--) if (zones.get(i).contains(x, z)) return zones.get(i);
        return null;
    }

    public static final List<Zone> zones = new ArrayList<>();
    public static boolean zonesVisible = true;
    /** Waypoint personal (salvat pe server, per jucator). */
    public static final class Wp {
        public int id;
        public String name = "?";
        public int color = 0xFF33CCFF;
        public String type = "";
        public double x;
        public double y;
        public double z;
    }

    public static final List<Wp> wps = new ArrayList<>();
    /** Culorile din paleta EGK. */
    public static final int[] PALETTE = {-13382401, -52429, -13369515, -4557, -3394561, -30669, -16777046, -16733696, -16733526, -8388608,
        -5635926, -5592406, -11184811, -11184641, -1, -16711809, -38476, -11861886, -16640, -2354116};
    public static final List<Poi> pois = new ArrayList<>();
    public static final Set<String> hidden = new HashSet<>();

    /** Are permisiunea de editor (flowcity.maps.admin). */
    public static boolean admin;

    public static boolean hasTarget;
    public static String targetName = "";
    /** Tipul iconitei tintei (null = waypoint personal). */
    public static String targetType;
    public static double tx;
    public static double tz;
    /** Ruta ca puncte lume {x, z} de la jucator spre tinta. */
    public static List<double[]> route = new ArrayList<>();
    /** true = ruta vine din reteaua GPS a serverului; false = doar linie dreapta. */
    public static boolean routeNetwork;
    public static boolean minimap = true;
    /** Blocuri per pixel in radar: 1, 2 sau 4. */
    public static int miniScale = 2;
    /** 0 = jos-stanga (ca in GTA), 1 = sus-stanga. */
    public static int radarPos = 0;

    public static void setTarget(String name, String type, double x, double z) {
        hasTarget = true;
        targetName = name;
        targetType = type;
        tx = x;
        tz = z;
        route = new ArrayList<>();
        routeNetwork = false;
        MapsClient.needRoute = true;
    }

    public static void clearTarget() {
        hasTarget = false;
        route = new ArrayList<>();
        routeNetwork = false;
    }

    public static void reset() {
        pois.clear();
        zones.clear();
        wps.clear();
        clearTarget();
    }
}
