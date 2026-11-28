package ro.solarstudios.flowcity.bridge.items;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Starea medicala RP a jucatorilor la pamant: BLEEDING (sangereaza, timpul curge) -> STABLE (stabilizat de EMS, timpul sta)
 * sau, daca timpul expira, ARREST (stop cardiac: doar defibrilatorul il mai poate salva) -> DEAD. Salvat in fisier (persistenta).
 * Logica e pura: durata si trecerea timpului vin din exterior, deci se poate testa.
 */
public final class DownedRegistry {
    public enum State { BLEEDING, STABLE, ARREST }

    public static final class Entry {
        public final UUID who;
        public State state;
        /** Momentul in care a intrat in starea curenta. */
        public long since;
        /** Cat a durat sangerarea pana la oprire (se foloseste la sansa de resuscitare). */
        public long bledMs;
        public int shocks;

        Entry(UUID who, State state, long since, long bledMs, int shocks) {
            this.who = who;
            this.state = state;
            this.since = since;
            this.bledMs = bledMs;
            this.shocks = shocks;
        }
    }

    /** O trecere de stare produsa de {@link #advance}. */
    public static final class Change {
        public final UUID who;
        public final State from;
        /** Noua stare, sau null daca jucatorul a murit. */
        public final State to;

        Change(UUID who, State from, State to) {
            this.who = who;
            this.from = from;
            this.to = to;
        }
    }

    private final Map<UUID, Entry> map = new LinkedHashMap<UUID, Entry>();
    private final File file;

    public DownedRegistry(File file) {
        this.file = file;
        load();
    }

    public synchronized boolean down(UUID who, long now) {
        if (map.containsKey(who)) return false;
        map.put(who, new Entry(who, State.BLEEDING, now, 0, 0));
        save();
        return true;
    }

    public synchronized Entry get(UUID who) {
        return map.get(who);
    }

    public synchronized boolean isDown(UUID who) {
        return map.containsKey(who);
    }

    public synchronized State state(UUID who) {
        Entry e = map.get(who);
        return e == null ? null : e.state;
    }

    /** EMS stabilizeaza un pacient care sangereaza: timpul se opreste. */
    public synchronized boolean stabilize(UUID who, long now) {
        Entry e = map.get(who);
        if (e == null || e.state != State.BLEEDING) return false;
        e.bledMs = Math.max(0, now - e.since);
        e.state = State.STABLE;
        e.since = now;
        save();
        return true;
    }

    /** Pacientul se ridica (tratat complet / resuscitat). */
    public synchronized boolean recover(UUID who) {
        boolean had = map.remove(who) != null;
        if (had) save();
        return had;
    }

    public synchronized void addShock(UUID who) {
        Entry e = map.get(who);
        if (e != null) {
            e.shocks++;
            save();
        }
    }

    /** Trece timpul: BLEEDING -> ARREST dupa {@code bleedMs}, ARREST -> DEAD dupa {@code arrestMs}. STABLE nu expira. */
    public synchronized List<Change> advance(long now, long bleedMs, long arrestMs) {
        List<Change> out = new ArrayList<Change>();
        List<UUID> dead = new ArrayList<UUID>();
        for (Entry e : map.values()) {
            if (e.state == State.BLEEDING && now - e.since >= bleedMs) {
                e.bledMs = bleedMs;
                e.state = State.ARREST;
                e.since = now;
                out.add(new Change(e.who, State.BLEEDING, State.ARREST));
            } else if (e.state == State.ARREST && now - e.since >= arrestMs) {
                dead.add(e.who);
                out.add(new Change(e.who, State.ARREST, null));
            }
        }
        for (UUID u : dead) map.remove(u);
        if (!out.isEmpty()) save();
        return out;
    }

    /**
     * Sansa de reusita a unui soc: scade cu timpul scurs in stop cardiac si cu numarul de socuri deja date.
     * Intre 0.15 si 0.9.
     */
    public static double shockChance(long arrestElapsedMs, long arrestMs, int shocksBefore) {
        double frac = arrestMs <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, arrestElapsedMs / (double) arrestMs));
        double c = 0.9 - 0.55 * frac - 0.1 * shocksBefore;
        return Math.max(0.15, Math.min(0.9, c));
    }

    public synchronized List<UUID> all() {
        return new ArrayList<UUID>(map.keySet());
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 5) continue;
                UUID u = UUID.fromString(p[0]);
                map.put(u, new Entry(u, State.valueOf(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), Integer.parseInt(p[4])));
            }
        } catch (IOException | RuntimeException ignored) {
            // fisier corupt
        }
    }

    private void save() {
        if (file == null) return;
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            List<String> lines = new ArrayList<String>();
            for (Entry e : map.values()) lines.add(e.who + "\t" + e.state + "\t" + e.since + "\t" + e.bledMs + "\t" + e.shocks);
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // la urmatoarea salvare
        }
    }
}
