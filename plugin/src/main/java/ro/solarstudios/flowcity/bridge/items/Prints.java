package ro.solarstudios.flowcity.bridge.items;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Amprente: fiecare jucator are o amprenta (hash SHA-256 al UUID-ului cu o sare a serverului; UUID-ul nu poate fi dedus din ea).
 * Jucatorii lasa urme pe obiectele atinse (usi, containere, vehicule); un scanner colecteaza urmele, iar compararea cu o proba
 * luata de la o persoana dovedeste cine a atins obiectul. Aici e doar logica (hash + urme), fara Bukkit.
 */
public final class Prints {
    public static final class Trace {
        public final String hash;
        public final long ts;

        Trace(String hash, long ts) {
            this.hash = hash;
            this.ts = ts;
        }
    }

    private final Map<String, List<Trace>> traces = new HashMap<String, List<Trace>>();
    private final int perPlace;
    private final String salt;

    public Prints(String salt, int perPlace) {
        this.salt = salt == null ? "" : salt;
        this.perPlace = Math.max(1, perPlace);
    }

    public String hash(UUID subject) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((salt + ":" + subject).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lasa o urma pe un obiect. Daca acelasi jucator a atins deja, doar reimprospateaza ora. */
    public synchronized void leave(String place, UUID who, long now) {
        String h = hash(who);
        List<Trace> l = traces.get(place);
        if (l == null) {
            l = new ArrayList<Trace>();
            traces.put(place, l);
        }
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).hash.equals(h)) {
                l.remove(i);
                break;
            }
        }
        l.add(new Trace(h, now));
        while (l.size() > perPlace) l.remove(0);
    }

    /** Urmele inca vizibile (mai noi de {@code maxAgeMs}), cele mai noi primele. */
    public synchronized List<Trace> at(String place, long now, long maxAgeMs) {
        List<Trace> out = new ArrayList<Trace>();
        List<Trace> l = traces.get(place);
        if (l == null) return out;
        for (int i = l.size() - 1; i >= 0; i--) if (now - l.get(i).ts <= maxAgeMs) out.add(l.get(i));
        return out;
    }

    /** Sterge urmele (de ex. dupa ce au fost colectate sau cand obiectul dispare). */
    public synchronized void wipe(String place) {
        traces.remove(place);
    }

    /** Curata urmele expirate (la rulare periodica). */
    public synchronized int purge(long now, long maxAgeMs) {
        int n = 0;
        java.util.Iterator<Map.Entry<String, List<Trace>>> it = traces.entrySet().iterator();
        while (it.hasNext()) {
            List<Trace> l = it.next().getValue();
            int before = l.size();
            l.removeIf(t -> now - t.ts > maxAgeMs);
            n += before - l.size();
            if (l.isEmpty()) it.remove();
        }
        return n;
    }

    public static boolean matches(String a, String b) {
        return a != null && a.equals(b);
    }
}
