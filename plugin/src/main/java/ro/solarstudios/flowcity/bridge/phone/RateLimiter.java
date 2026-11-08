package ro.solarstudios.flowcity.bridge.phone;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Rate-limit cu fereastra glisanta (pur, fara thread-uri: se apeleaza doar pe thread-ul principal). */
public final class RateLimiter {
    private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();

    /** Inregistreaza o actiune si intoarce true daca e permisa (maxim {@code max} in {@code windowMs}). */
    public boolean allow(String key, long nowMs, int max, long windowMs) {
        ArrayDeque<Long> q = hits.get(key);
        if (q == null) {
            q = new ArrayDeque<>();
            hits.put(key, q);
        }
        while (!q.isEmpty() && nowMs - q.peekFirst() >= windowMs) q.pollFirst();
        if (q.size() >= max) return false;
        q.addLast(nowMs);
        return true;
    }

    /** Elimina toate cheile care incep cu prefixul (la iesirea jucatorului). */
    public void clear(String prefix) {
        hits.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public int size() {
        return hits.size();
    }
}
