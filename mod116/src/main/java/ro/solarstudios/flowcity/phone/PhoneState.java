package ro.solarstudios.flowcity.phone;

import java.util.ArrayList;
import java.util.List;

/** Starea ePhone pe client (modificata doar pe thread-ul principal). */
public final class PhoneState {
    private PhoneState() {}

    public static final class Person {
        public String uuid = "";
        public String name = "?";
        public int id;
        public boolean online;
        public String number = "";
    }

    public static final class Thread {
        public String uuid = "";
        public String name = "?";
        public String last = "";
        public boolean mine;
        public boolean online;
        public long ts;
        public int unread;
    }

    public static final class Msg {
        public boolean mine;
        public String text = "";
        public long ts;
    }

    public static final class CallLog {
        public String uuid = "";
        public String name = "?";
        public boolean out;
        public long ts;
        public long dur;
        public String status = "";
    }

    public static final class Call {
        public long id;
        public Person with = new Person();
        public boolean incoming;
        public boolean active;
        public long since = System.currentTimeMillis();
        public long ringStart = System.currentTimeMillis();
    }

    /** O cartela SIM (instalata sau din inventar). */
    public static final class SimInfo {
        public String id = "";
        public String number = "";
        public String operator = "";
        public String status = "ACTIVE";
        public String state = "";
        public long since;
    }

    public static SimInfo simInstalled;
    public static int simBlank;
    public static final List<SimInfo> simInv = new ArrayList<>();
    public static final List<String> simOps = new ArrayList<>();
    public static double simPrice;
    public static int simOpSel;
    /** NONE / ACTIVE / BLOCKED. */
    public static String simStatus = "NONE";
    public static String operator = "";
    public static int signal = 4;
    public static int wallpaper;
    public static boolean light;

    public static boolean simActive() {
        return "ACTIVE".equals(simStatus) && signal > 0;
    }

    public static final List<Person> contacts = new ArrayList<>();
    public static final List<Thread> threads = new ArrayList<>();
    public static final List<Msg> thread = new ArrayList<>();
    public static Person threadWith;
    public static final List<CallLog> calls = new ArrayList<>();
    public static int unread;
    public static int missed;
    public static String myNumber = "";
    public static boolean callMuted;
    public static boolean callSpeaker;
    public static Call call;
    public static long lastRing;
    public static boolean contactsLoaded;

    // ------------------------------------------------------------------ App Store / home

    /** Aplicatiile instalate, in ordinea de pe ecran (primele 4 = dock). Pana raspunde serverul: toate. */
    public static final List<String> apps = new ArrayList<>(PhoneApps.fallbackOrder());
    public static boolean storeLoaded;
    public static final java.util.Set<String> owned = new java.util.HashSet<>();
    public static final java.util.Map<String, Double> prices = new java.util.HashMap<>();
    public static final java.util.Set<String> available = new java.util.HashSet<>();
    public static double balance;
    /** Instalari in curs (id -> momentul confirmarii de la server); pana la final aplicatia nu apare pe ecran. */
    public static final java.util.Map<String, Long> installing = new java.util.HashMap<>();
    /** Cereri trimise serverului, fara raspuns inca. */
    public static final java.util.Set<String> pending = new java.util.HashSet<>();

    public static long installMs(String id) {
        PhoneApps.App a = PhoneApps.get(id);
        return 900L + (a == null ? 10 : a.sizeMb) * 45L;
    }

    /** Progresul instalarii 0..1, sau -1 daca nu se instaleaza. */
    public static float installProgress(String id) {
        Long t = installing.get(id);
        if (t == null) return -1f;
        float f = (System.currentTimeMillis() - t) / (float) installMs(id);
        if (f >= 1f) {
            installing.remove(id);
            return -1f;
        }
        return f;
    }

    public static boolean visible(String id) {
        return apps.contains(id) && installProgress(id) < 0f;
    }

    // Note
    public static final class Note {
        public String text = "";
        public long ts;
    }

    public static final List<Note> notes = new ArrayList<>();
    public static boolean notesLoaded;

    // Ceas (raman active si cu telefonul inchis)
    public static long swStart;
    public static long swAccum;
    public static long timerEnd;
    public static long timerTotal;

    public static void reset() {
        apps.clear();
        apps.addAll(PhoneApps.fallbackOrder());
        storeLoaded = false;
        owned.clear();
        prices.clear();
        available.clear();
        installing.clear();
        pending.clear();
        notes.clear();
        notesLoaded = false;
        timerEnd = 0;
        contacts.clear();
        threads.clear();
        thread.clear();
        calls.clear();
        threadWith = null;
        unread = 0;
        missed = 0;
        myNumber = "";
        simInstalled = null;
        simInv.clear();
        simBlank = 0;
        simStatus = "NONE";
        operator = "";
        signal = 4;
        callMuted = false;
        callSpeaker = false;
        call = null;
        contactsLoaded = false;
    }
}
