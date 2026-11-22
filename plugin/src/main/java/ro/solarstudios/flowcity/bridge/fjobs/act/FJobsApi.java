package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Random;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/** Ce ofera modulul FJobs activitatilor (joburile cu gameplay propriu). Toate platile si XP-ul trec DOAR pe aici (autoritar pe server). */
public interface FJobsApi {
    FlowCityBridge plugin();

    YamlConfiguration cfg();

    Random rnd();

    /** Nivelul jobului pentru jucator. */
    int jobLevel(Player p, String jobId);

    /** Plateste (cu bonus de nivel + multiplicator) si acorda XP. Intoarce banii primiti, sau -1 daca limita pe minut a blocat plata. */
    double pay(Player p, FJobDef jd, double rawPay, long rawXp, boolean mission);

    List<FJobDef.Point> stations(FJobDef jd, String kind);

    void toast(Player p, String title, String text);

    void msg(Player p, boolean ok, String text);

    void send(Player p, String action, JsonObject data);

    /** Trimite cardul de HUD al turei (titlu, linii, progres). Un obiect gol il ascunde. */
    void hud(Player p, JsonObject hud);

    void gps(Player p, FJobDef.Point pt);

    /** Blocul a fost pus de un jucator (anti-farm). */
    boolean isPlaced(Block b);

    Challenges challenges();

    /** Optiune per jucator si job (ex. dificultatea). */
    String opt(Player p, FJobDef jd, String key, String def);

    void setOpt(Player p, FJobDef jd, String key, String value);

    /** Contoare persistente per jucator (registre de capturi, productie etc.). */
    long counter(Player p, String key);

    void addCounter(Player p, String key, long delta);

    void clearCounters(Player p, String prefix);

    /** Jobul activ in tura cu logica proprie sau null. */
    FJobDef working(Player p);

    /** Retrimite panoul jobului (dupa schimbari de stare). */
    void refreshPanel(Player p);
}
