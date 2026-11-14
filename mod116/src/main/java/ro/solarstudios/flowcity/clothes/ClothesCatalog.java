package ro.solarstudios.flowcity.clothes;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lista tinutelor cunoscute de client (id -> nume): vine de la server la intrare; pana atunci, tinutele implicite. */
public final class ClothesCatalog {
    private ClothesCatalog() {}

    public static final Map<String, String> OUTFITS = new LinkedHashMap<>();
    /** Piese suprapuse (id -> nume): sapca, ochelari, tricou etc. */
    public static final Map<String, String> PIECES = new LinkedHashMap<>();

    static {
        OUTFITS.put("casual_iarna", "Hanorac de iarnă");
        OUTFITS.put("sport", "Echipament sport");
        OUTFITS.put("politist", "Uniformă Poliție");
        OUTFITS.put("medic", "Uniformă Medic");
        OUTFITS.put("mecanic", "Salopetă Mecanic");
        OUTFITS.put("mos_craciun", "Moș Crăciun");
        PIECES.put("sapca_neagra", "Șapcă neagră");
        PIECES.put("caciula_iarna", "Căciulă de iarnă");
        PIECES.put("ochelari_soare", "Ochelari de soare");
        PIECES.put("esarfa", "Eșarfă");
        PIECES.put("tricou_rosu", "Tricou roșu");
        PIECES.put("pantaloni_jeans", "Blugi");
        PIECES.put("ghete", "Ghete");
    }
}
