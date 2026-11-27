package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.List;

/**
 * Sursa traseului afisat pe harta. Implementarea actuala ({@link #STRAIGHT}) e doar directia in linie dreapta: NU urmeaza strazile.
 * Cand va exista un graf rutier, se implementeaza aceasta interfata (cu {@link #roads()} = true) si se pune in {@link GpsNav#route}.
 */
public interface RouteProvider {
    /** Punctele traseului {x, z} de la start la destinatie (inclusiv). */
    List<double[]> path(double fromX, double fromZ, double toX, double toZ);

    /** True doar daca traseul urmeaza drumuri reale. */
    boolean roads();

    RouteProvider STRAIGHT = new RouteProvider() {
        @Override
        public List<double[]> path(double fromX, double fromZ, double toX, double toZ) {
            List<double[]> p = new ArrayList<>(2);
            p.add(new double[] {fromX, fromZ});
            p.add(new double[] {toX, toZ});
            return p;
        }

        @Override
        public boolean roads() {
            return false;
        }
    };
}
