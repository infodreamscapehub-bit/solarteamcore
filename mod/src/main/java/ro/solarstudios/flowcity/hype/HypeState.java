package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Starea Hype primita de la server (citita de HUD si ecrane). Accesata doar de pe thread-ul clientului. */
public final class HypeState {
    private HypeState() {}

    /** Text care urca si se estompeaza deasupra HUD-ului (castiguri). */
    public static final class Popup {
        public final String text;
        public final long at;

        Popup(String text) {
            this.text = text;
            this.at = System.currentTimeMillis();
        }
    }

    public static JsonObject state = new JsonObject();
    public static JsonObject atm = new JsonObject();
    public static JsonObject jobs = new JsonObject();
    public static boolean hudOn = true;
    public static long jobsAt;
    public static long levelUpAt;
    public static final List<Popup> popups = new ArrayList<>();

    public static void popup(String text) {
        popups.add(new Popup(text));
        if (popups.size() > 6) popups.remove(0);
    }

    public static void reset() {
        state = new JsonObject();
        atm = new JsonObject();
        jobs = new JsonObject();
        popups.clear();
        levelUpAt = 0;
    }
}
