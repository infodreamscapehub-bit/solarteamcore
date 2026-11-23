package ro.solarstudios.flowcity.news;

import java.util.ArrayList;
import java.util.List;

/** Starea CNN Anunturi pe client (doar thread-ul clientului). Datele vin de la server; clientul nu valideaza nimic de incredere. */
public final class AdsState {
    private AdsState() {}

    public static final class Ad {
        public long id;
        public String cat = "";
        public String title = "";
        public String body = "";
        public long price = -1;
        public String phone = "";
        public String author = "";
        public String uuid = "";
        public boolean promoted;
        public String status = "";
        public long created;
        public long expires;
        public boolean own;
        public int extendsLeft;
        public String reason = "";
    }

    public static final class Cat {
        public String id = "";
        public String label = "";
        public boolean price;
    }

    public static final class Log {
        public long ts;
        public String actor = "";
        public String action = "";
        public long id;
        public String detail = "";
    }

    public static final List<Ad> items = new ArrayList<>();
    public static final List<Ad> own = new ArrayList<>();
    public static final List<Ad> pending = new ArrayList<>();
    public static final List<Cat> cats = new ArrayList<>();
    public static final List<Integer> durations = new ArrayList<>();
    public static final List<Log> history = new ArrayList<>();

    public static boolean loaded;
    public static boolean enabled = true;
    public static double balance;
    public static double feeStandard;
    public static double feePromoted;
    public static double feeExtend;
    public static boolean promotedEnabled = true;
    public static long cooldownMs;
    public static long cooldownPromoMs;
    public static long suspendedMs;
    public static String suspendReason = "";
    public static long infoAt;
    public static int titleMin = 5;
    public static int titleMax = 48;
    public static int descMin = 10;
    public static int descMax = 240;
    public static long maxPrice = 1_000_000_000L;
    public static int maxActive = 3;
    public static int active;
    public static boolean moderation;
    public static boolean extendAllowed = true;
    public static boolean isMod;
    public static String phone = "";
    public static boolean requireSim = true;

    public static String lastMsg = "";
    public static boolean lastOk;
    public static long lastMsgAt;

    public static void reset() {
        items.clear();
        own.clear();
        pending.clear();
        cats.clear();
        durations.clear();
        history.clear();
        loaded = false;
        isMod = false;
        phone = "";
        lastMsg = "";
    }

    public static String catLabel(String id) {
        for (Cat c : cats) if (c.id.equals(id)) return c.label;
        return id;
    }

    public static boolean catHasPrice(String id) {
        for (Cat c : cats) if (c.id.equals(id)) return c.price;
        return false;
    }

    /** Culoarea unei categorii (ARGB), stabila dupa pozitia in lista. */
    public static int catColor(String id) {
        int[] pal = {0xFF5BA8FF, 0xFF4CD39A, 0xFFF5C451, 0xFFB58CFF, 0xFFFF8A5B, 0xFF4FD1D9, 0xFF9AA7BD};
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).id.equals(id)) return pal[i % pal.length];
        return pal[6];
    }

    public static String statusLabel(String s) {
        switch (s) {
            case "ACTIVE": return "ACTIV";
            case "PENDING": return "IN ASTEPTARE";
            case "REJECTED": return "RESPINS";
            case "EXPIRED": return "EXPIRAT";
            case "DELETED": return "STERS";
            default: return s;
        }
    }

    public static int statusColor(String s) {
        switch (s) {
            case "ACTIVE": return 0xFF4CD39A;
            case "PENDING": return 0xFFF5C451;
            case "REJECTED": return 0xFFF0584C;
            default: return 0xFF7A8AA3;
        }
    }
}
