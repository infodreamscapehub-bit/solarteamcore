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

    // ---- numere fictive 07xx xxx xxx, derivate reversibil din ID-ul jucatorului (fara tabel in DB)

    private static final long PHONE_MOD = 100_000_000L;
    private static final long PHONE_MUL = 7919L;
    private static final long PHONE_ADD = 1_234_567L;

    /** Numarul de telefon (10 cifre, incepe cu 07) al jucatorului cu acest ID; ID-urile <= 0 nu au numar. */
    public static String phoneNumber(int id) {
        if (id <= 0) return "";
        long v = ((long) id * PHONE_MUL + PHONE_ADD) % PHONE_MOD;
        return String.format(java.util.Locale.ROOT, "07%08d", v);
    }

    /** "0712 345 678" pentru afisare. */
    public static String phoneDisplay(String number) {
        if (number == null || number.length() != 10) return number == null ? "" : number;
        return number.substring(0, 4) + " " + number.substring(4, 7) + " " + number.substring(7);
    }

    /** ID-ul jucatorului pentru un numar 07xxxxxxxx (doar cifre) sau -1 daca nu e un numar valid. */
    public static int idOfPhone(String digits) {
        if (digits == null || digits.length() != 10 || !digits.startsWith("07")) return -1;
        try {
            long v = Long.parseLong(digits.substring(2));
            java.math.BigInteger mod = java.math.BigInteger.valueOf(PHONE_MOD);
            java.math.BigInteger inv = java.math.BigInteger.valueOf(PHONE_MUL).modInverse(mod);
            long id = java.math.BigInteger.valueOf(v - PHONE_ADD).mod(mod).multiply(inv).mod(mod).longValue();
            return id <= 0 || id > Integer.MAX_VALUE ? -1 : (int) id;
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }
}
