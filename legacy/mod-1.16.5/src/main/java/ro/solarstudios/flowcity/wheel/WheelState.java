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

    /** Premiu din editorul de admin (eAdmin > Roata). */
    public static final class AdminPrize {
        public long id;
        public String name = "";
        public String type = "item";
        public double amount;
        public String material = "";
        public int weight = 1;
        public String color = "#22D3EE";
        public boolean announce;
        public double pct;
    }

    public static final List<AdminPrize> admin = new ArrayList<>();
    public static int adminMax = 16;

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
        admin.clear();
        cooldownMs = 0;
        resultIndex = -1;
    }

    public static long cooldownLeft() {
        return Math.max(0, cooldownMs - (System.currentTimeMillis() - cooldownAt));
    }
}
