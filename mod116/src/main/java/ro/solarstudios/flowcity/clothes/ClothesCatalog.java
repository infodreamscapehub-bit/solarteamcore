package ro.solarstudios.flowcity.clothes;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lista tinutelor cunoscute de client (id -> nume): vine de la server la intrare; pana atunci, tinutele implicite. */
public final class ClothesCatalog {
    private ClothesCatalog() {}

    public static final Map<String, String> OUTFITS = new LinkedHashMap<>();

    static {
        OUTFITS.put("casual_iarna", "Hanorac de iarnă");
        OUTFITS.put("sport", "Echipament sport");
        OUTFITS.put("politist", "Uniformă Poliție");
        OUTFITS.put("medic", "Uniformă Medic");
        OUTFITS.put("mecanic", "Salopetă Mecanic");
        OUTFITS.put("mos_craciun", "Moș Crăciun");
    }
}
