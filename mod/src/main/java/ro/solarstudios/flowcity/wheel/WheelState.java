package ro.solarstudios.flowcity.wheel;

import java.util.ArrayList;
import java.util.List;

/** Starea rotii pe client. Doar thread-ul clientului. */
public final class WheelState {
    private WheelState() {}

    public static final class Prize {
        public String name = "";
        public int color = 0x22D3EE;
    }

    public static final List<Prize> prizes = new ArrayList<>();
    public static final List<String> recent = new ArrayList<>();
    public static long cooldownMs;
    public static long cooldownAt;
    public static int animSeconds = 6;

    // rezultat in curs de animatie
    public static int resultIndex = -1;
    public static String resultName = "";
    public static String resultDetail = "";

    public static void reset() {
        prizes.clear();
        recent.clear();
        cooldownMs = 0;
        resultIndex = -1;
    }

    public static long cooldownLeft() {
        return Math.max(0, cooldownMs - (System.currentTimeMillis() - cooldownAt));
    }
}
