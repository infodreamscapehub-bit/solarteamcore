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

    public static final List<Person> contacts = new ArrayList<>();
    public static final List<Thread> threads = new ArrayList<>();
    public static final List<Msg> thread = new ArrayList<>();
    public static Person threadWith;
    public static final List<CallLog> calls = new ArrayList<>();
    public static int unread;
    public static int missed;
    public static Call call;
    public static long lastRing;
    public static boolean contactsLoaded;

    public static void reset() {
        contacts.clear();
        threads.clear();
        thread.clear();
        calls.clear();
        threadWith = null;
        unread = 0;
        missed = 0;
        call = null;
        contactsLoaded = false;
    }
}
