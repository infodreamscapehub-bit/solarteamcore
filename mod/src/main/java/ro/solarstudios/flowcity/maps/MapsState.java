package ro.solarstudios.flowcity.maps;

import java.util.ArrayList;
import java.util.List;

/** Starea eMaps pe client: puncte de interes, tinta GPS, ruta calculata, setari minimap. */
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

    public static boolean hasTarget;
    public static String targetName = "";
    public static double tx;
    public static double tz;
    /** Ruta ca puncte lume {x, z} de la jucator spre tinta. */
    public static List<double[]> route = new ArrayList<>();
    public static boolean routeIsStraight;
    public static boolean minimap = true;
    /** Blocuri per pixel in minimap: 1 sau 2. */
    public static int miniScale = 1;

    public static void setTarget(String name, double x, double z) {
        hasTarget = true;
        targetName = name;
        tx = x;
        tz = z;
        route = new ArrayList<>();
        routeIsStraight = true;
        Router.cancel();
        MapsClient.needRoute = true;
    }

    public static void clearTarget() {
        hasTarget = false;
        route = new ArrayList<>();
        Router.cancel();
    }

    public static int typeColor(String type) {
        switch (type) {
            case "primarie": return 0xFFFFC83D;
            case "banca": return 0xFF39D98A;
            case "atm": return 0xFF8CF2B8;
            case "politie": return 0xFF4C8DFF;
            case "spital": return 0xFFFF5470;
            case "magazin": return 0xFFB075FF;
            case "job": return 0xFFFF9F43;
            case "benzinarie": return 0xFFB0B8C8;
            default: return 0xFFE8EEFF;
        }
    }

    public static String typeLetter(String type) {
        switch (type) {
            case "primarie": return "P";
            case "banca": return "B";
            case "atm": return "$";
            case "politie": return "!";
            case "spital": return "+";
            case "magazin": return "M";
            case "job": return "J";
            case "benzinarie": return "G";
            default: return "*";
        }
    }

    public static void reset() {
        pois.clear();
        clearTarget();
    }
}
