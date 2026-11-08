package ro.solarstudios.flowcity.bridge.phone;

import java.util.Random;
import java.util.function.Predicate;

/** Logica pura a numerelor de telefon (fara Bukkit): generare, validare, formatare. Numerele se stocheaza doar cu cifre. */
public final class NumberLogic {
    private NumberLogic() {}

    /** Pastreaza doar cifrele (max 32 de caractere). */
    public static String digits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 32; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    /** Cate cifre are un numar pe formatul dat ('#' = cifra aleatoare, cifrele literale raman fixe). */
    public static int digitCount(String format) {
        int n = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '#' || (c >= '0' && c <= '9')) n++;
        }
        return n;
    }

    /** Genereaza un numar liber (doar cifre) sau null daca nu gaseste dupa maxTries incercari. */
    public static String generate(String format, Random rnd, Predicate<String> taken, int maxTries) {
        for (int t = 0; t < maxTries; t++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < format.length(); i++) {
                char c = format.charAt(i);
                if (c == '#') sb.append((char) ('0' + rnd.nextInt(10)));
                else if (c >= '0' && c <= '9') sb.append(c);
            }
            String n = sb.toString();
            if (!n.isEmpty() && !taken.test(n)) return n;
        }
        return null;
    }

    /** Numarul (doar cifre) respecta formatul: lungime corecta si cifrele fixe la locul lor. */
    public static boolean matches(String digits, String format) {
        if (digits == null || digits.length() != digitCount(format)) return false;
        int d = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '#') {
                if (digits.charAt(d) < '0' || digits.charAt(d) > '9') return false;
                d++;
            } else if (c >= '0' && c <= '9') {
                if (digits.charAt(d) != c) return false;
                d++;
            }
        }
        return true;
    }

    /** Afisare cu separatori din format (ex. 5551234 -> 555-1234). Daca nu se potriveste, intoarce cifrele. */
    public static String display(String digits, String format) {
        if (digits == null) return "";
        if (digits.length() != digitCount(format)) return digits;
        StringBuilder sb = new StringBuilder();
        int d = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '#' || (c >= '0' && c <= '9')) sb.append(digits.charAt(d++));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Numar format din tastatura: 3..15 cifre. */
    public static boolean validDial(String digits) {
        return digits != null && digits.length() >= 3 && digits.length() <= 15;
    }
}
