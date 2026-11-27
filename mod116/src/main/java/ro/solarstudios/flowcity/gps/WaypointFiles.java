package ro.solarstudios.flowcity.gps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Cauta si citeste fisiere de waypoint-uri din folderul de joc al jucatorului, ca sa poata importa in GPS
 * locatiile pe care le are deja la el pe calculator, fara sa mai urce ceva pe server.
 *
 * <p>Locuri cautate:
 * <ul>
 *   <li>{@code <joc>/XaeroWaypoints/&#42;&#42;/waypoints.txt} (Xaero's Minimap / World Map), cautat recursiv;</li>
 *   <li>{@code <joc>/flowcity/waypoints.txt}, varianta manuala.</li>
 * </ul>
 *
 * <p>Formatul liniei e cel al fisierelor Xaero: {@code waypoint:nume:initiala:x:y:z:culoare:...}. Numele
 * se pastreaza exact cum e scris (inclusiv literele mici-majuscule Unicode). Clientul doar citeste si trimite
 * datele; serverul re-verifica permisiunea, valideaza si decide ce intra in harta.
 */
public final class WaypointFiles {
    private WaypointFiles() {}

    /** Cate linii se citesc dintr-un fisier, ca sa nu se blocheze jocul pe un fisier gresit. */
    private static final int MAX_LINES = 4000;
    /** Cate fisiere se iau in seama (foldere de servere multe). */
    private static final int MAX_FILES = 40;
    /** Cat de adanc se cauta sub XaeroWaypoints/. */
    private static final int MAX_DEPTH = 4;

    /** Un waypoint citit din fisier, cu toate campurile pe care le putem folosi. */
    public static final class Entry {
        public final String name;
        public final double x;
        public final double y;
        public final double z;
        /** Indicele de culoare din fisier (0-15) sau -1 daca lipseste. */
        public final int color;
        /** Initialele din fisier (eticheta scurta). */
        public final String label;
        /** {@code disabled} din fisier. */
        public final boolean disabled;

        Entry(String name, double x, double y, double z, int color, String label, boolean disabled) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.color = color;
            this.label = label;
            this.disabled = disabled;
        }
    }

    /** Un fisier gasit, cu waypoint-urile din el. */
    public static final class Source {
        /** Eticheta aratata in lista, ex. {@code Multiplayer_node1.capitalrp.ro/dim%0}. */
        public final String label;
        public final Path path;
        public final List<Entry> entries;

        Source(String label, Path path, List<Entry> entries) {
            this.label = label;
            this.path = path;
            this.entries = entries;
        }
    }

    /** Folderul Xaero asteptat, pentru mesajul de ajutor cand nu gaseste nimic. */
    public static Path xaeroFolder() {
        return FMLPaths.GAMEDIR.get().resolve("XaeroWaypoints");
    }

    /** Fisierul manual, pentru mesajul de ajutor. */
    public static Path manualFile() {
        return FMLPaths.GAMEDIR.get().resolve("flowcity").resolve("waypoints.txt");
    }

    /**
     * Cauta fisierele de waypoint-uri si le citeste. Lista vine sortata descrescator dupa numarul de
     * waypoint-uri, deci fisierul cel mai bogat e primul. Fisierele fara waypoint-uri valide sunt sarite.
     */
    public static List<Source> find() {
        List<Source> out = new ArrayList<>();
        Path xaero = xaeroFolder();
        if (Files.isDirectory(xaero)) {
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.walk(xaero, MAX_DEPTH)) {
                s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.equals("waypoints.txt") || (n.startsWith("waypoints") && n.endsWith(".txt"));
                    })
                    .limit(MAX_FILES)
                    .forEach(files::add);
            } catch (IOException | RuntimeException ignored) {
                // folderul poate fi inaccesibil; continuam cu ce avem
            }
            for (Path f : files) {
                List<Entry> es = read(f);
                if (!es.isEmpty()) out.add(new Source(label(xaero, f), f, es));
            }
        }
        Path manual = manualFile();
        if (Files.isRegularFile(manual)) {
            List<Entry> es = read(manual);
            if (!es.isEmpty()) out.add(new Source("flowcity/waypoints.txt", manual, es));
        }
        out.sort(Comparator.comparingInt((Source s) -> s.entries.size()).reversed());
        return out;
    }

    /** Eticheta = calea relativa la XaeroWaypoints, fara numele fisierului daca e chiar „waypoints.txt". */
    private static String label(Path root, Path file) {
        String rel;
        try {
            rel = root.relativize(file).toString().replace('\\', '/');
        } catch (RuntimeException e) {
            rel = file.getFileName().toString();
        }
        if (rel.toLowerCase(Locale.ROOT).endsWith("/waypoints.txt")) rel = rel.substring(0, rel.length() - "/waypoints.txt".length());
        if (rel.isEmpty()) rel = file.getFileName().toString();
        return rel.length() > 64 ? rel.substring(rel.length() - 64) : rel;
    }

    /** Citeste un fisier; liniile gresite se sar in liniste. */
    private static List<Entry> read(Path f) {
        List<Entry> out = new ArrayList<>();
        try {
            int n = 0;
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (n++ >= MAX_LINES) break;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.toLowerCase(Locale.ROOT).startsWith("waypoint:")) continue;
                String[] p = line.split(":", -1);
                // waypoint : nume : initiala : x : y : z : culoare : ...
                if (p.length < 6) continue;
                String name = p[1].trim();
                if (name.isEmpty()) name = p[2].trim();
                if (name.isEmpty()) continue;
                if (name.length() > 48) name = name.substring(0, 48).trim();
                double x;
                double y;
                double z;
                try {
                    x = Double.parseDouble(p[3].trim());
                    y = Double.parseDouble(p[4].trim());
                    z = Double.parseDouble(p[5].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (!ok(x) || !ok(z) || Double.isNaN(y) || Double.isInfinite(y)) continue;
                int color = -1;
                if (p.length > 6) {
                    try {
                        color = Integer.parseInt(p[6].trim());
                    } catch (NumberFormatException ignored) {
                        color = -1;
                    }
                }
                boolean disabled = p.length > 7 && "true".equalsIgnoreCase(p[7].trim());
                String label = p[2].trim();
                if (label.length() > 4) label = label.substring(0, 4);
                out.add(new Entry(name, x, y, z, color, label, disabled));
            }
        } catch (IOException | RuntimeException ignored) {
            // fisier ilizibil: il tratam ca gol
        }
        return out;
    }

    private static boolean ok(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) <= 30000000d;
    }
}
