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
        clearTarget();
    }
}
