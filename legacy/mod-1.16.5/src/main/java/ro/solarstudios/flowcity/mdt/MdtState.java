package ro.solarstudios.flowcity.mdt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Starea clientului MDT: ultimele date primite de la server. */
final class MdtState {
    private MdtState() {}

    static JsonObject state = new JsonObject();
    static JsonObject citizen = null;
    static JsonArray found = new JsonArray();
    static String foundQuery = "";
    static JsonObject plate = null;
    static boolean officer;
    static String lastAlertName = "";
    static double lastAlertX;
    static double lastAlertZ;

    static void reset() {
        state = new JsonObject();
        citizen = null;
        found = new JsonArray();
        foundQuery = "";
        plate = null;
        officer = false;
        lastAlertName = "";
    }
}
