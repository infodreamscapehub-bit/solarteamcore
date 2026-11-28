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

/** Cine e incatusat, de cine si de cand. Se salveaza in fisier: jucatorul ramane incatusat si dupa restart sau deconectare. */
public final class CuffRegistry {
    public static final class Cuff {
        public final UUID target;
        public final UUID by;
        public final long since;
        /** Ofiterul care il escorteaza acum (null = nu e escortat). */
        public UUID escort;

        Cuff(UUID target, UUID by, long since, UUID escort) {
            this.target = target;
            this.by = by;
            this.since = since;
            this.escort = escort;
        }
    }

    private final Map<UUID, Cuff> map = new LinkedHashMap<UUID, Cuff>();
    private final File file;

    public CuffRegistry(File file) {
        this.file = file;
        load();
    }

    public synchronized boolean cuff(UUID target, UUID by, long now) {
        if (map.containsKey(target)) return false;
        map.put(target, new Cuff(target, by, now, null));
        save();
        return true;
    }

    public synchronized boolean release(UUID target) {
        boolean had = map.remove(target) != null;
        if (had) save();
        return had;
    }

    public synchronized boolean isCuffed(UUID target) {
        return map.containsKey(target);
    }

    public synchronized Cuff get(UUID target) {
        return map.get(target);
    }

    /** Porneste sau opreste escortarea. Intoarce false daca tinta nu e incatusata. */
    public synchronized boolean setEscort(UUID target, UUID officerOrNull) {
        Cuff c = map.get(target);
        if (c == null) return false;
        c.escort = officerOrNull;
        save();
        return true;
    }

    /** Persoanele escortate de un ofiter. */
    public synchronized List<Cuff> escortedBy(UUID officer) {
        List<Cuff> out = new ArrayList<Cuff>();
        for (Cuff c : map.values()) if (officer.equals(c.escort)) out.add(c);
        return out;
    }

    /** Cei incatusati de mai mult de {@code maxMs} (eliberare automata, ca sa nu ramana cineva blocat pe veci). */
    public synchronized List<UUID> expired(long now, long maxMs) {
        List<UUID> out = new ArrayList<UUID>();
        for (Cuff c : map.values()) if (maxMs > 0 && now - c.since >= maxMs) out.add(c.target);
        return out;
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
                if (p.length < 3) continue;
                UUID t = UUID.fromString(p[0]);
                // escortarea nu se pastreaza peste restart (ofiterul trebuie sa o reia)
                map.put(t, new Cuff(t, UUID.fromString(p[1]), Long.parseLong(p[2]), null));
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
            for (Cuff c : map.values()) lines.add(c.target + "\t" + c.by + "\t" + c.since);
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // la urmatoarea salvare
        }
    }
}
