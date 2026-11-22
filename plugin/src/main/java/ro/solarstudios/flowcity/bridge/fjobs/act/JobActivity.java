package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/** Gameplay propriu al unui job. Evenimentele ajung doar cand jucatorul e in tura pe acest job si jobul e functional. */
public interface JobActivity {
    /** Id-ul din fjobs.yml (campul "kind"). */
    String kind();

    /** Tipurile de puncte pe care adminul le seteaza din GUI (cheie -> eticheta). */
    List<String[]> stationKinds();

    /** Gol daca jobul are tot ce-i trebuie (puncte setate); altfel motivul pentru "in pregatire". */
    String missing(FJobDef jd);

    void start(Player p, FJobDef jd);

    void end(Player p, FJobDef jd);

    /** O data pe secunda, cat timp jucatorul e in tura. */
    void tick(Player p, FJobDef jd, long now);

    void onBreak(Player p, FJobDef jd, BlockBreakEvent e);

    void onPlace(Player p, FJobDef jd, BlockPlaceEvent e);

    void onInteract(Player p, FJobDef jd, PlayerInteractEvent e);

    void onFish(Player p, FJobDef jd, PlayerFishEvent e);

    void onPickup(Player p, FJobDef jd, EntityPickupItemEvent e);

    /** Panoul jobului (obiective, statistici, optiuni, recompense) pentru ecranul din mod. */
    JsonObject panel(Player p, FJobDef jd);

    /** Buton din panou (act = actiunea ceruta de client); intoarce true daca a facut ceva. */
    boolean action(Player p, FJobDef jd, String act, JsonObject j);

    /** Schimba o optiune a jucatorului (validata aici). */
    boolean setOption(Player p, FJobDef jd, String key, String value);

    /** Jucatorul a iesit din tura/server: curata starea si efectele. */
    void quit(Player p);

    /** Oprirea pluginului: curata lucrurile puse in lume. */
    void shutdown();
}
