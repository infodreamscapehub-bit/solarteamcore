package ro.solarstudios.flowcity.notify;

import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.Theme;

/** Starea notificarilor pe client + stilul pe tipuri. Doar thread-ul clientului. */
public final class NotifyState {
    private NotifyState() {}

    public static final class Item {
        public long id;
        public String type = "INFO";
        public String title = "";
        public String text = "";
        public long ts;
        public boolean read;
    }

    public static final List<Item> history = new ArrayList<>();
    public static int unread;
    public static boolean announced;

    public static void reset() {
        history.clear();
        unread = 0;
        announced = false;
    }

    public static int color(String type) {
        switch (type) {
            case "OK": return Theme.OK;
            case "WARN": return Theme.WARN;
            case "BAD": return Theme.BAD;
            case "MONEY": return 0xFFF5C451;
            case "JOB": return Theme.VIOLET;
            case "NEWS": return 0xFF5BA8FF;
            default: return Theme.ACCENT;
        }
    }

    public static String icon(String type) {
        switch (type) {
            case "OK": return "+";
            case "WARN": return "!";
            case "BAD": return "x";
            case "MONEY": return "$";
            case "JOB": return "J";
            case "NEWS": return "N";
            default: return "i";
        }
    }
}
