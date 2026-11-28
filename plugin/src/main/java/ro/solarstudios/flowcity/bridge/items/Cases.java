package ro.solarstudios.flowcity.bridge.items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Dosarele de cercetare: fiecare ofiter are un dosar activ in care se atasaza probele, inregistrarile bodycam si pozele.
 * Probele atasate se vad si de colegi (aceeasi baza), iar potrivirile de amprente se scriu si in MDT.
 */
public final class Cases {
    private final ItemDb idb;
    private final Map<UUID, Long> active = new HashMap<UUID, Long>();

    public Cases(ItemDb idb) {
        this.idb = idb;
    }

    /** Dosarul activ al ofiterului; daca nu are, deschide unul nou. */
    public long ensure(Player p) {
        Long id = active.get(p.getUniqueId());
        if (id != null) return id;
        return open(p);
    }

    public long open(Player p) {
        long id = idb.next("fc_case");
        active.put(p.getUniqueId(), id);
        idb.exec("INSERT INTO fc_case(id,title,officer,officer_name,ts,open) VALUES (?,?,?,?,?,1)", id, "Dosar #" + id, p.getUniqueId().toString(), p.getName(), System.currentTimeMillis());
        return id;
    }

    public long current(Player p) {
        Long id = active.get(p.getUniqueId());
        return id == null ? 0 : id;
    }

    /** Muta ofiterul pe un dosar existent (colegii pot lucra in acelasi dosar). */
    public void join(Player p, long caseId) {
        active.put(p.getUniqueId(), caseId);
    }

    public void close(Player p) {
        Long id = active.remove(p.getUniqueId());
        if (id != null) idb.exec("UPDATE fc_case SET open=0 WHERE id=?", id);
    }

    public void attach(Player by, long caseId, String kind, long ref, String summary) {
        if (caseId <= 0) return;
        idb.exec("INSERT INTO fc_evidence(id,case_id,kind,ref,summary,by_name,ts) VALUES (?,?,?,?,?,?,?)", idb.next("fc_evidence"), caseId, kind, ref,
            summary.length() > 200 ? summary.substring(0, 200) : summary, by.getName(), System.currentTimeMillis());
    }
}
