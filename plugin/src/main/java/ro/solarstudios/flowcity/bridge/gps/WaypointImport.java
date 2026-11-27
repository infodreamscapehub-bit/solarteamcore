package ro.solarstudios.flowcity.bridge.gps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Citeste un fisier de waypoint-uri (formatul Xaero: {@code waypoint:nume:initiala:x:y:z:culoare:...})
 * si il transforma in locatii GPS: numele se pastreaza exact ca in fisier (inclusiv literele mici-majuscule
 * Unicode, „small caps"), coordonatele se iau ca atare, iar categoria se ghiceste din numele normalizat.
 *
 * <p>Fisierul se pune pe server in {@code plugins/FlowCityBridge/gps/import/waypoints.txt}. Importul nu
 * sterge nimic: locatiile care exista deja (acelasi nume la mai putin de 24 de blocuri) sunt sarite.
 */
final class WaypointImport {
    private WaypointImport() {}

    /** Numarul maxim de linii citite dintr-un fisier, ca sa nu se blocheze serverul pe un fisier gresit. */
    private static final int MAX_LINES = 4000;
    /** Doua locatii cu acelasi nume mai apropiate de atat sunt considerate aceeasi locatie. */
    private static final double SAME_DIST = 24d;

    static final class Entry {
        final String name;
        final double x;
        final double z;
        final String cat;

        Entry(String name, double x, double z, String cat) {
            this.name = name;
            this.x = x;
            this.z = z;
            this.cat = cat;
        }
    }

    static final class Result {
        final List<Entry> entries = new ArrayList<>();
        int bad;
        int duplicates;
    }

    /** Folderul de import din gps/. */
    static File folder(GpsStore store) {
        return new File(store.folder(), "import");
    }

    /** Fisierul implicit de import. */
    static File file(GpsStore store) {
        return new File(folder(store), "waypoints.txt");
    }

    /** Citeste si analizeaza fisierul; arunca IOException daca nu poate fi citit. */
    static Result read(File f) throws IOException {
        Result r = new Result();
        List<String> seen = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(f.toPath()), StandardCharsets.UTF_8))) {
            String line;
            int n = 0;
            while ((line = br.readLine()) != null && n++ < MAX_LINES) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!line.toLowerCase(Locale.ROOT).startsWith("waypoint:")) continue;
                String[] p = line.split(":", -1);
                // waypoint : nume : initiala : x : y : z : culoare : ...
                if (p.length < 6) {
                    r.bad++;
                    continue;
                }
                // numele ramane cum e scris in fisier (small caps pastrate); normalizarea e doar pentru categorie
                String name = p[1].trim();
                if (name.isEmpty()) name = p[2].trim();
                if (name.isEmpty()) {
                    r.bad++;
                    continue;
                }
                if (name.length() > 48) name = name.substring(0, 48).trim();
                double x;
                double z;
                try {
                    x = Double.parseDouble(p[3].trim());
                    z = Double.parseDouble(p[5].trim());
                } catch (NumberFormatException e) {
                    r.bad++;
                    continue;
                }
                if (Double.isNaN(x) || Double.isNaN(z) || Math.abs(x) > 30000000d || Math.abs(z) > 30000000d) {
                    r.bad++;
                    continue;
                }
                String key = normalize(name).toLowerCase(Locale.ROOT) + "|" + Math.round(x / SAME_DIST) + "|" + Math.round(z / SAME_DIST);
                if (seen.contains(key)) {
                    r.duplicates++;
                    continue;
                }
                seen.add(key);
                r.entries.add(new Entry(name, x, z, guessCategory(name)));
            }
        }
        return r;
    }

    /**
     * Transforma literele mici-majuscule si alte variante Unicode in litere normale si scoate diacriticele,
     * ca numele sa poata fi citit si cautat. Primul caracter al fiecarui cuvant ramane majuscula.
     */
    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = SMALL_CAPS.indexOf(c);
            if (k >= 0) {
                b.append(SMALL_CAPS_PLAIN.charAt(k));
                continue;
            }
            k = DIACRITICS.indexOf(c);
            if (k >= 0) {
                b.append(DIACRITICS_PLAIN.charAt(k));
                continue;
            }
            if (c < 32 || c == 127) continue;
            if (c > 127) continue; // orice alt semn din afara setului latin de baza se scoate
            b.append(c);
        }
        // spatii multiple -> unul singur, apoi Majuscula la inceput de cuvant
        String t = b.toString().replaceAll("\\s+", " ").trim();
        StringBuilder out = new StringBuilder(t.length());
        boolean up = true;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            out.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = c == ' ' || c == '-' || c == '/';
        }
        return out.toString();
    }

    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱsᴛᴜᴠᴡʏᴢ";
    private static final String SMALL_CAPS_PLAIN = "abcdeffghijklmnopqrsstuvwyz";
    private static final String DIACRITICS = "ăâîșşțţĂÂÎȘŞȚŢáàäéèëíìïóòöúùüÁÀÄÉÈËÍÌÏÓÒÖÚÙÜçÇñÑ";
    private static final String DIACRITICS_PLAIN = "aaissttAAISSTTaaaeeeiiiooouuuAAAEEEIIIOOOUUUcCnN";

    /** Perechi (cuvant din nume, categorie). Prima potrivire castiga, deci ordinea conteaza. */
    private static final String[][] RULES = {
        {"benzinar", "Benzinarii"},
        {"politi", "Politie"},
        {"spital", "Spital"},
        {"banca", "Banca"},
        {"bank", "Banca"},
        {"dealership", "Dealership"},
        {"dealer", "Dealership"},
        {"mecanic", "Service"},
        {"service", "Service"},
        {"apartament", "Locuinte"},
        {"penthouse", "Locuinte"},
        {"conac", "Locuinte"},
        {"casa", "Locuinte"},
        {"case", "Locuinte"},
        {"vila", "Locuinte"},
        {"hotel", "Locuinte"},
        {"parcare", "Parcare"},
        {"parking", "Parcare"},
        {"garaj", "Parcare"},
        {"aeroport", "Transport"},
        {"port", "Transport"},
        {"metro", "Transport"},
        {"statie", "Transport"},
        {"taxi", "Transport"},
        {"kfc", "Restaurante"},
        {"restaurant", "Restaurante"},
        {"cafenea", "Restaurante"},
        {"pizza", "Restaurante"},
        {"shop", "Magazine"},
        {"magazin", "Magazine"},
        {"market", "Magazine"},
        {"depozit", "Magazine"},
        {"primarie", "Primarie"},
        {"inchisoare", "Politie"},
        {"vama", "Politie"},
    };

    /** Ghiceste categoria dupa cuvintele din nume; restul ajung la „Puncte de interes". */
    static String guessCategory(String name) {
        String n = normalize(name).toLowerCase(Locale.ROOT);
        for (String[] r : RULES) if (n.contains(r[0])) return r[1];
        return "Puncte de interes";
    }

    /** Culoarea implicita a unei categorii nou create (stabila, dupa nume). */
    static String colorFor(String cat) {
        String[] palette = {"#4DA3FF", "#22C55E", "#F59E0B", "#EF4444", "#8B5CF6", "#38BDF8", "#F472B6", "#A3E635"};
        int h = Math.abs(cat.toLowerCase(Locale.ROOT).hashCode());
        return palette[h % palette.length];
    }

    /** Iconita implicita a unei categorii nou create, din setul desenat in mod. */
    static String iconFor(String cat) {
        switch (cat) {
            case "Politie": return "flag";
            case "Spital": return "plus";
            case "Banca": return "star";
            case "Benzinarii": return "target";
            case "Service": return "gear";
            case "Dealership": return "nav";
            case "Locuinte": return "home";
            case "Parcare": return "grid";
            case "Transport": return "route";
            case "Restaurante": return "bell";
            case "Magazine": return "layers";
            case "Primarie": return "map";
            default: return "pin";
        }
    }
}
