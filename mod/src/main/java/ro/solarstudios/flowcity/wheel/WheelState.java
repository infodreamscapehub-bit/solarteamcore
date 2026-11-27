package ro.solarstudios.flowcity.wheel;

import java.util.ArrayList;
import java.util.List;

/** Starea rotii pe client. Doar thread-ul clientului. */
public final class WheelState {
    private WheelState() {}

    public static final class Prize {
        public String name = "";
        public String descr = "";
        public String icon = "";
        public int color = 0x22D3EE;
    }

    /** O rotire din istoricul jucatorului. */
    public static final class Won {
        public String name = "";
        public long ts;
    }

    /** Premiu din editorul de admin (FAdmin > Roata). */
    public static final class AdminPrize {
        public long id;
        public String name = "";
        public String descr = "";
        public String type = "item";
        public double amount;
        public String material = "";
        public String icon = "";
        public String command = "";
        public int weight = 1;
        public String color = "#22D3EE";
        public boolean announce;
        public boolean enabled = true;
        public double pct;
    }

    /** Setarile rotii, editabile din GUI. */
    public static final class Cfg {
        public boolean enabled = true;
        public double cooldownHours = 24;
        public double cost;
        public int freeSpins = 1;
        public int maxPerDay = 1;
        public int animSeconds = 6;
    }

    /** O linie din statisticile de admin. */
    public static final class Stat {
        public String who = "";
        public String name = "";
        public long n;
        public long ts;
    }

    public static final List<AdminPrize> admin = new ArrayList<>();
    public static final Cfg cfg = new Cfg();
    public static int adminMax = 16;

    public static final List<Stat> statTop = new ArrayList<>();
    public static final List<Stat> statLast = new ArrayList<>();
    public static long statSpins;
    public static long statPlayers;
    public static boolean statsLoaded;

    public static final List<Prize> prizes = new ArrayList<>();
    public static final List<Won> recent = new ArrayList<>();
    public static long cooldownMs;
    public static long cooldownAt;
    public static int animSeconds = 6;
    public static boolean enabled = true;
    public static double cost;
    public static int freeLeft;
    public static int dayLeft;
    public static double cash;
    public static int totalSpins;
    public static int claims;

    // rezultat in curs de animatie
    public static int resultIndex = -1;
    public static String resultName = "";
    public static String resultDetail = "";

    public static void reset() {
        prizes.clear();
        recent.clear();
        admin.clear();
        statTop.clear();
        statLast.clear();
        statsLoaded = false;
        cooldownMs = 0;
        claims = 0;
        totalSpins = 0;
        resultIndex = -1;
    }

    public static long cooldownLeft() {
        return Math.max(0, cooldownMs - (System.currentTimeMillis() - cooldownAt));
    }
}
