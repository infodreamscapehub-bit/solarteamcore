package ro.solarstudios.flowcity.bridge.items;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Pauze intre utilizari (pe jucator + actiune). Ceasul e injectat ca sa poata fi testat. */
public final class Cooldowns {
    private final Map<String, Long> ready = new HashMap<String, Long>();
    private final LongSupplier clock;

    public Cooldowns(LongSupplier clock) {
        this.clock = clock;
    }

    public Cooldowns() {
        this(System::currentTimeMillis);
    }

    /** Daca pauza a trecut, o porneste pe {@code ms} si intoarce true; altfel false. */
    public synchronized boolean tryUse(String key, long ms) {
        long now = clock.getAsLong();
        Long r = ready.get(key);
        if (r != null && r > now) return false;
        ready.put(key, now + Math.max(0, ms));
        return true;
    }

    /** Milisecundele ramase pana se poate folosi din nou (0 = acum). */
    public synchronized long remaining(String key) {
        Long r = ready.get(key);
        long left = r == null ? 0 : r - clock.getAsLong();
        return Math.max(0, left);
    }

    public synchronized void clearPrefix(String prefix) {
        ready.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
