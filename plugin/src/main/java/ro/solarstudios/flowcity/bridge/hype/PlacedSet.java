package ro.solarstudios.flowcity.bridge.hype;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Set de locatii cu TTL (anti-farm: blocurile puse de jucatori nu platesc). Se foloseste doar pe thread-ul principal. */
public final class PlacedSet {
    private final Map<String, Long> map = new HashMap<>();
    private final long ttlMs;
    private final int maxSize;

    public PlacedSet(long ttlMs, int maxSize) {
        this.ttlMs = ttlMs;
        this.maxSize = maxSize;
    }

    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public void mark(String key, long now) {
        if (map.size() >= maxSize) purge(now);
        if (map.size() >= maxSize) {
            // plin: scoatem cea mai veche intrare
            String oldest = null;
            long ot = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : map.entrySet()) {
                if (e.getValue() < ot) {
                    ot = e.getValue();
                    oldest = e.getKey();
                }
            }
            if (oldest != null) map.remove(oldest);
        }
        map.put(key, now);
    }

    public boolean contains(String key, long now) {
        Long t = map.get(key);
        if (t == null) return false;
        if (now - t > ttlMs) {
            map.remove(key);
            return false;
        }
        return true;
    }

    public void remove(String key) {
        map.remove(key);
    }

    public void purge(long now) {
        Iterator<Map.Entry<String, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > ttlMs) it.remove();
        }
    }

    public int size() {
        return map.size();
    }
}
