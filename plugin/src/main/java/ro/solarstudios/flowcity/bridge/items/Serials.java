package ro.solarstudios.flowcity.bridge.items;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registrul serialelor itemelor FlowCity. Fiecare item de echipament primeste la emitere un serial unic (marcat pe item cu PDC);
 * doar serialele emise de server sunt valide: un item luat din tab-ul creativ, copiat cu NBT sau duplicat nu poate fi folosit.
 * Serialele se salveaza in fisier (persistenta dupa restart). Logica e pura (fara Bukkit), ca sa poata fi testata.
 */
public final class Serials {
    public enum Check { OK, UNKNOWN, REVOKED, WRONG_ITEM }

    public static final class Entry {
        public final String serial;
        public final String item;
        public final String owner;
        public final long created;
        /** Utilizari ramase; -1 = nelimitat. */
        public int uses;
        public boolean revoked;

        Entry(String serial, String item, String owner, long created, int uses, boolean revoked) {
            this.serial = serial;
            this.item = item;
            this.owner = owner;
            this.created = created;
            this.uses = uses;
            this.revoked = revoked;
        }
    }

    private final Map<String, Entry> map = new LinkedHashMap<String, Entry>();
    private final File file;

    public Serials(File file) {
        this.file = file;
        load();
    }

    /** Emite un serial nou pentru un item. {@code uses < 0} = fara limita de utilizari. */
    public synchronized String issue(String item, UUID owner, int uses, long now) {
        String s;
        do {
            s = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        } while (map.containsKey(s));
        map.put(s, new Entry(s, item, owner == null ? "-" : owner.toString(), now, uses, false));
        save();
        return s;
    }

    public synchronized Check validate(String serial, String item) {
        if (serial == null) return Check.UNKNOWN;
        Entry e = map.get(serial);
        if (e == null) return Check.UNKNOWN;
        if (e.revoked) return Check.REVOKED;
        if (!e.item.equals(item)) return Check.WRONG_ITEM;
        return Check.OK;
    }

    public synchronized Entry get(String serial) {
        return map.get(serial);
    }

    /** Consuma o utilizare. Intoarce utilizarile ramase (-1 = nelimitat); la 0 serialul se revoca. -2 daca serialul nu exista. */
    public synchronized int consume(String serial) {
        Entry e = map.get(serial);
        if (e == null || e.revoked) return -2;
        if (e.uses < 0) return -1;
        e.uses--;
        if (e.uses <= 0) {
            e.uses = 0;
            e.revoked = true;
        }
        save();
        return e.uses;
    }

    /** Adauga utilizari (reincarcare), cu plafon. Intoarce noul numar sau -2 daca serialul nu exista / e nelimitat. */
    public synchronized int addUses(String serial, int n, int cap) {
        Entry e = map.get(serial);
        if (e == null || e.uses < 0) return -2;
        e.uses = Math.min(cap, e.uses + n);
        if (e.uses > 0) e.revoked = false;
        save();
        return e.uses;
    }

    public synchronized void revoke(String serial) {
        Entry e = map.get(serial);
        if (e != null && !e.revoked) {
            e.revoked = true;
            save();
        }
    }

    public synchronized int size() {
        return map.size();
    }

    /** Indicii (in lista data) unde un serial apare a doua oara sau mai tarziu: acelea sunt copiile. */
    public static Set<Integer> duplicates(List<String> serials) {
        Set<String> seen = new HashSet<String>();
        Set<Integer> dup = new HashSet<Integer>();
        for (int i = 0; i < serials.size(); i++) {
            String s = serials.get(i);
            if (s == null) continue;
            if (!seen.add(s)) dup.add(i);
        }
        return dup;
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 6) continue;
                map.put(p[0], new Entry(p[0], p[1], p[2], Long.parseLong(p[3]), Integer.parseInt(p[4]), "1".equals(p[5])));
            }
        } catch (IOException | RuntimeException ignored) {
            // fisier corupt: pornim cu ce s-a citit
        }
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            List<String> lines = new ArrayList<String>();
            for (Entry e : map.values()) {
                lines.add(e.serial + "\t" + e.item + "\t" + e.owner + "\t" + e.created + "\t" + e.uses + "\t" + (e.revoked ? "1" : "0"));
            }
            File tmp = new File(file.getPath() + ".tmp");
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // la urmatoarea salvare
        }
    }
}
