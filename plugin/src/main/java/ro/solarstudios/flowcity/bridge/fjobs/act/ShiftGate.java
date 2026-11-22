package ro.solarstudios.flowcity.bridge.fjobs.act;

import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/** Optional pentru o activitate: conditie verificata pe server INAINTE ca tura sa inceapa (ex. taxi: sofer intr-un vehicul Vehicle Mod). */
public interface ShiftGate {
    /** Intoarce null daca tura poate incepe, altfel motivul (afisat jucatorului). */
    String canStart(Player p, FJobDef jd);
}
