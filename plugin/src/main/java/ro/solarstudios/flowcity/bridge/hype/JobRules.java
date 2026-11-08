package ro.solarstudios.flowcity.bridge.hype;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Reguli de plata ale unui job (logica pura, fara Bukkit): eveniment + materiale (cu wildcard *_LOG / DIAMOND_*) -> plata + XP. */
public final class JobRules {
    public static final class Rule {
        public final String event;       // break | place | fish | trash
        public final Set<String> exact = new HashSet<>();
        public final List<String> prefix = new ArrayList<>();   // "DIAMOND_*"
        public final List<String> suffix = new ArrayList<>();   // "*_LOG"
        public final double pay;
        public final long xp;
        public final boolean mature;       // doar culturi coapte
        public final boolean underwater;   // doar sub apa
        public final boolean placedCheck;  // blocurile puse de jucatori nu platesc

        public Rule(String event, List<String> materials, double pay, long xp, boolean mature, boolean underwater, boolean placedCheck) {
            this.event = event.toLowerCase(Locale.ROOT);
            for (String m : materials) {
                String u = m.trim().toUpperCase(Locale.ROOT);
                if (u.isEmpty()) continue;
                if (u.startsWith("*") && u.length() > 1) suffix.add(u.substring(1));
                else if (u.endsWith("*") && u.length() > 1) prefix.add(u.substring(0, u.length() - 1));
                else exact.add(u);
            }
            this.pay = pay;
            this.xp = xp;
            this.mature = mature;
            this.underwater = underwater;
            this.placedCheck = placedCheck;
        }

        public boolean matches(String ev, String material) {
            if (!event.equals(ev)) return false;
            String m = material.toUpperCase(Locale.ROOT);
            if (exact.contains(m)) return true;
            for (String s : suffix) if (m.endsWith(s)) return true;
            for (String p : prefix) if (m.startsWith(p)) return true;
            return false;
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public void add(Rule r) {
        rules.add(r);
    }

    public int size() {
        return rules.size();
    }

    /** Prima regula care se potriveste (ordinea din config; regulile specifice se pun primele). */
    public Rule find(String event, String material) {
        for (Rule r : rules) if (r.matches(event, material)) return r;
        return null;
    }

    public boolean handles(String event) {
        for (Rule r : rules) if (r.event.equals(event)) return true;
        return false;
    }
}
