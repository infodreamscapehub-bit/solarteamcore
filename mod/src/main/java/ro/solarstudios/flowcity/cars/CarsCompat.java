package ro.solarstudios.flowcity.cars;

import net.minecraftforge.fml.ModList;

/** Legatura cu modul de vehicule MrCrayfish (port neoficial 1.20.1, modId "vehicle"). Totul e optional. */
public final class CarsCompat {
    private CarsCompat() {}

    public static final String MOD = "vehicle";

    public static boolean loaded() {
        return ModList.get() != null && ModList.get().isLoaded(MOD);
    }
}
