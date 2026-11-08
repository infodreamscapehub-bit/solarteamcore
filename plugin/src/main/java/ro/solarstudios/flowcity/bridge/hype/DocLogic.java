package ro.solarstudios.flowcity.bridge.hype;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/** Logica pura pentru documente: serie/numar unice, parsare date, categorii de permis. */
public final class DocLogic {
    private DocLogic() {}

    private static final String LETTERS = "ABCDEFGHJKLMNPRSTUVXZ"; // fara I, O, Q, W, Y (ambigue)
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
        "AM", "A1", "A2", "A", "B1", "B", "BE", "C1", "C1E", "C", "CE", "D1", "D1E", "D", "DE", "TR", "TB", "TV"));

    /** Genereaza {serie, numar} unice. Buletin: 2 litere + 6 cifre. Permis: "P"+litera + 7 cifre. Null daca nu gaseste in 200 de incercari. */
    public static String[] generate(boolean permit, Random r, Predicate<String> taken) {
        for (int i = 0; i < 200; i++) {
            String serie = permit
                ? "P" + LETTERS.charAt(r.nextInt(LETTERS.length()))
                : "" + LETTERS.charAt(r.nextInt(LETTERS.length())) + LETTERS.charAt(r.nextInt(LETTERS.length()));
            int digits = permit ? 7 : 6;
            StringBuilder num = new StringBuilder();
            num.append(1 + r.nextInt(9));
            for (int d = 1; d < digits; d++) num.append(r.nextInt(10));
            if (!taken.test(serie + " " + num)) return new String[] {serie, num.toString()};
        }
        return null;
    }

    /** dd.MM.yyyy -> ms (inceput de zi) sau -1 daca e invalida. */
    public static long parseDate(String s) {
        if (s == null) return -1;
        try {
            SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);
            f.setLenient(false);
            java.util.Date d = f.parse(s.trim());
            if (!s.trim().matches("\\d{2}\\.\\d{2}\\.\\d{4}")) return -1;
            return d.getTime();
        } catch (java.text.ParseException e) {
            return -1;
        }
    }

    public static String formatDate(long ms) {
        return new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(new java.util.Date(ms));
    }

    /** Varsta in ani impliniti. */
    public static int ageYears(long birthMs, long nowMs) {
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(birthMs);
        java.util.Calendar n = java.util.Calendar.getInstance();
        n.setTimeInMillis(nowMs);
        int age = n.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR);
        if (n.get(java.util.Calendar.DAY_OF_YEAR) < b.get(java.util.Calendar.DAY_OF_YEAR)) age--;
        return age;
    }

    /** Adauga ani calendaristici. */
    public static long addYears(long ms, int years) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.add(java.util.Calendar.YEAR, years);
        return c.getTimeInMillis();
    }

    /** Normalizeaza "b, c1,B" -> "B,C1" (unice, ordonate dupa aparitie); null daca exista o categorie necunoscuta/lipsa. */
    public static String normalizeCategories(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[,; ]+")) {
            if (part.isEmpty()) continue;
            String up = part.toUpperCase(Locale.ROOT);
            if (!CATEGORIES.contains(up)) return null;
            out.add(up.equals("TR") ? "Tr" : up.equals("TB") ? "Tb" : up.equals("TV") ? "Tv" : up);
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    public static boolean validSex(String s) {
        return "M".equalsIgnoreCase(s) || "F".equalsIgnoreCase(s);
    }

    /** Curata un nume: litere/spatii/liniuta/apostrof, max 40 caractere, litera mare la inceput de cuvant. */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.length() < 3 || s.length() > 40) return null;
        if (!s.matches("[\\p{L} '\\-]+")) return null;
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char c : s.toCharArray()) {
            sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
            up = c == ' ' || c == '-';
        }
        return sb.toString();
    }
}
