package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonObject;

/** Starea Hype primita de la server (citita de HUD si ecrane). Accesata doar de pe thread-ul clientului. */
public final class HypeState {
    private HypeState() {}

    public static JsonObject state = new JsonObject();
    public static JsonObject atm = new JsonObject();
    public static JsonObject jobs = new JsonObject();
    public static boolean hudOn = true;
    public static String earnText = "";
    public static long earnUntil;
    public static long jobsAt;

    public static void reset() {
        state = new JsonObject();
        atm = new JsonObject();
        jobs = new JsonObject();
        earnText = "";
        earnUntil = 0;
    }
}
