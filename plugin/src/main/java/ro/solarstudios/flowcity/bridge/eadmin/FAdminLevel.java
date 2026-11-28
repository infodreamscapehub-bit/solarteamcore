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

    /** Sectiunile panoului. Fiecare are nodul LuckPerms propriu flowcity.fadmin.<id>, verificat independent pe server. */
    public enum Sec {
        DASHBOARD("dashboard"), PLAYERS("players"), MODERATION("moderation"), STAFF("staff"), ECONOMY("economy"), VEHICLES("vehicles"),
        JOBS("jobs"), PERMISSIONS("permissions"), REPORTS("reports"), LOGS("logs"), SERVER("server");

        public final String id;

        Sec(String id) {
            this.id = id;
        }

        public String node() {
            return "flowcity.fadmin." + id;
        }
    }

    /**
     * Actiunile din panou, fiecare cu nivelul minim. Cele de citire (cu sectiune) pot fi acordate si direct prin nodul sectiunii
     * (flowcity.fadmin.&lt;sectiune&gt;); cele care modifica ceva cer mereu nivelul.
     */
    public enum Cap {
        DASH(HELPER, Sec.DASHBOARD), PLAYERS(HELPER, Sec.PLAYERS), KICK(HELPER), MUTE(HELPER), TP(HELPER), BRING(HELPER), HISTORY(HELPER),
        WARN(HELPER), MOD_VIEW(HELPER, Sec.MODERATION), REPORT_VIEW(HELPER, Sec.REPORTS), REPORT_HANDLE(HELPER), DUTY(HELPER),
        BAN(MODERATOR), UNBAN(MODERATOR), HEAL(MODERATOR), INV(MODERATOR), VEH_VIEW(MODERATOR, Sec.VEHICLES), JOB_VIEW(MODERATOR, Sec.JOBS),
        LOGS(MODERATOR, Sec.LOGS), FREEZE(MODERATOR), SPECTATE(MODERATOR), JAIL(MODERATOR), STAFF_VIEW(MODERATOR, Sec.STAFF),
        SET_JOB(ADMINISTRATOR), JOB_GRADE(ADMINISTRATOR), VEH_STATUS(ADMINISTRATOR), ECON_VIEW(ADMINISTRATOR, Sec.ECONOMY), RPID(ADMINISTRATOR),
        JAIL_SET(ADMINISTRATOR), ANNOUNCE(ADMINISTRATOR), SERVER_VIEW(ADMINISTRATOR, Sec.SERVER),
        ECON_MODIFY(MANAGER), VEH_DELETE(MANAGER), CFG_BASIC(MANAGER), PERM_VIEW(MANAGER, Sec.PERMISSIONS), RANK(MANAGER),
        SYSTEM(DEVELOPER), CFG_SYSTEM(DEVELOPER),
        PERM_EDIT(OWNER);

        public final FAdminLevel min;
        public final Sec sec;

        Cap(FAdminLevel min) {
            this(min, null);
        }

        Cap(FAdminLevel min, Sec sec) {
            this.min = min;
            this.sec = sec;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Are jucatorul acces (citire) la sectiune: prin nodul sectiunii sau prin nivelul de staff. */
    public static boolean canSee(Player p, Cap c) {
        if (p == null) return false;
        if (p.isOp()) return true;
        FAdminLevel lv = of(p);
        if (lv != null && lv.can(c)) return true;
        return c.sec != null && p.hasPermission(c.sec.node());
    }

    /** Nivelul jucatorului, sau null daca nu e staff. */
    public static FAdminLevel of(Player p) {
        if (p == null) return null;
        if (p.isOp() || p.hasPermission(OWNER.node())) return OWNER;
        FAdminLevel[] all = values();
        for (int i = all.length - 2; i >= 0; i--) if (p.hasPermission(all[i].node())) return all[i];
        // staff vechi: doar nodurile flowcity.eadmin.*
        if (!p.hasPermission("flowcity.eadmin") && !p.hasPermission("flowcity.fadmin")) {
            for (Sec sc : Sec.values()) if (p.hasPermission(sc.node())) return HELPER; // doar noduri de sectiune: nivel minim, citire
            return null;
        }
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
