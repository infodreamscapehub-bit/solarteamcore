package ro.solarstudios.flowcity.client.fadmin;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ClientBridge;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Modulul client "fadm": datele panoului FAdmin 2.0. Serverul (FAdminModule din Bridge) decide tot: nivelul de staff, ce se vede, ce se
 * poate face. Clientul tine doar ultimele raspunsuri primite si le arata in {@link FAdminScreen}.
 */
public final class FAdminClient extends JsonClientModule {
    private static FAdminClient instance;

    static JsonObject me = new JsonObject();
    static JsonObject dash;
    static JsonObject players;
    static JsonObject player;
    static JsonObject vehicles;
    static JsonObject econ;
    static JsonObject susp;
    static JsonObject jobs;
    static JsonObject jobPlayers;
    static JsonObject jobProgress;
    static JsonObject logs;
    static JsonObject system;
    static JsonObject cfgCats;
    static JsonObject cfgList;
    static JsonObject permList;
    static JsonObject mod;
    static JsonObject reports;
    static JsonObject staff;
    static JsonObject staffHist;
    static JsonObject lpGroups;
    static JsonObject lpUser;
    static JsonObject vehHist;

    private FAdminClient() {
        on("open", d -> {
            ClientBridge.pendingOpenAt = 0;
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof FAdminScreen)) mc.setScreen(new FAdminScreen());
        });
        on("me", d -> me = d);
        on("dash", d -> dash = d);
        on("players", d -> players = d);
        on("player", d -> player = d);
        on("vehicles", d -> vehicles = d);
        on("econ", d -> econ = d);
        on("susp", d -> susp = d);
        on("jobs", d -> jobs = d);
        on("job_players", d -> jobPlayers = d);
        on("job_progress", d -> jobProgress = d);
        on("logs", d -> logs = d);
        on("system", d -> system = d);
        on("cfg_cats", d -> cfgCats = d);
        on("cfg_list", d -> cfgList = d);
        on("perm_list", d -> permList = d);
        on("mod", d -> mod = d);
        on("reports", d -> reports = d);
        on("staff", d -> staff = d);
        on("staff_hist", d -> staffHist = d);
        on("lp_groups", d -> lpGroups = d);
        on("lp_user", d -> lpUser = d);
        on("veh_hist", d -> vehHist = d);
        on("res", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FAdminScreen) ((FAdminScreen) mc.screen).toast(str(d, "msg", ""), !bool(d, "ok", false));
        });
    }

    @Override
    public String id() {
        return "fadm";
    }

    @Override
    public void onLogout() {
        me = new JsonObject();
        dash = players = player = vehicles = econ = susp = jobs = jobPlayers = jobProgress = logs = system = cfgCats = cfgList = permList = null;
        mod = reports = staff = staffHist = lpGroups = lpUser = vehHist = null;
    }

    public static void register() {
        instance = new FAdminClient();
        ClientModules.register(instance);
    }

    /** Trimite o cerere catre server. Perechi cheie, valoare (String / Number / Boolean). */
    static void ask(String action, Object... kv) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v instanceof Number) o.addProperty(k, (Number) v);
            else if (v instanceof Boolean) o.addProperty(k, (Boolean) v);
            else o.addProperty(k, String.valueOf(v));
        }
        instance.send(action, o);
    }
}
