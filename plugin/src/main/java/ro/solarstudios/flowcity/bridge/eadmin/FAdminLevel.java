package ro.solarstudios.flowcity.bridge.eadmin;

import java.util.Locale;
import org.bukkit.entity.Player;

/**
 * Nivelurile de staff FAdmin si ce poate face fiecare. Nivelul vine din sistemul de permisiuni existent (LuckPerms / Bukkit):
 * nodurile {@code flowcity.fadmin.<nivel>}; operatorii sunt Owner. Staff-ul vechi (doar flowcity.eadmin.*) e mapat automat,
 * ca sa nu se piarda accesul dupa trecerea de la FAdmin la FAdmin.
 */
public enum FAdminLevel {
    HELPER("helper", "Helper"),
    MODERATOR("moderator", "Moderator"),
    ADMINISTRATOR("administrator", "Administrator"),
    MANAGER("manager", "Manager"),
    DEVELOPER("developer", "Developer"),
    OWNER("owner", "Owner");

    public final String id;
    public final String label;

    FAdminLevel(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String node() {
        return "flowcity.fadmin." + id;
    }

    /** Actiunile din panou, fiecare cu nivelul minim. */
    public enum Cap {
        DASH(HELPER), PLAYERS(HELPER), KICK(HELPER), MUTE(HELPER), TP(HELPER), BRING(HELPER), HISTORY(HELPER),
        BAN(MODERATOR), UNBAN(MODERATOR), HEAL(MODERATOR), INV(MODERATOR), VEH_VIEW(MODERATOR), JOB_VIEW(MODERATOR), LOGS(MODERATOR),
        SET_JOB(ADMINISTRATOR), JOB_GRADE(ADMINISTRATOR), VEH_STATUS(ADMINISTRATOR), ECON_VIEW(ADMINISTRATOR),
        ECON_MODIFY(MANAGER), VEH_DELETE(MANAGER), CFG_BASIC(MANAGER), PERM_VIEW(MANAGER),
        SYSTEM(DEVELOPER), CFG_SYSTEM(DEVELOPER),
        PERM_EDIT(OWNER);

        public final FAdminLevel min;

        Cap(FAdminLevel min) {
            this.min = min;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Nivelul jucatorului, sau null daca nu e staff. */
    public static FAdminLevel of(Player p) {
        if (p == null) return null;
        if (p.isOp() || p.hasPermission(OWNER.node())) return OWNER;
        FAdminLevel[] all = values();
        for (int i = all.length - 2; i >= 0; i--) if (p.hasPermission(all[i].node())) return all[i];
        // staff vechi: doar nodurile flowcity.eadmin.*
        if (!p.hasPermission("flowcity.eadmin") && !p.hasPermission("flowcity.fadmin")) return null;
        if (p.hasPermission("flowcity.eadmin.money") || p.hasPermission("flowcity.eadmin.jobs") || p.hasPermission("flowcity.eadmin.inv.edit")) return ADMINISTRATOR;
        if (p.hasPermission("flowcity.eadmin.sanction")) return MODERATOR;
        return HELPER;
    }

    public boolean atLeast(FAdminLevel other) {
        return ordinal() >= other.ordinal();
    }

    public boolean can(Cap c) {
        return atLeast(c.min);
    }

    public static FAdminLevel parse(String s) {
        if (s == null) return null;
        for (FAdminLevel l : values()) if (l.id.equalsIgnoreCase(s)) return l;
        return null;
    }

    /** Suma maxima pe operatiune pe care o poate adauga/scoate nivelul (0 = nu are voie). */
    public double moneyLimit() {
        switch (this) {
            case MANAGER: return 50_000;
            case DEVELOPER: return 1_000_000;
            case OWNER: return 100_000_000;
            default: return 0;
        }
    }
}
