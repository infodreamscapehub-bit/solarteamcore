package ro.solarstudios.flowcity.bridge.hype;

import java.util.Random;
import java.util.TimeZone;

/** Logica pura (fara Bukkit): rotunjire, selectie ponderata, niveluri de job, cooldown, taxe. Testata separat. */
public final class HypeMath {
    private HypeMath() {}

    public static final long DAY_MS = 86_400_000L;

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Suma valida: finita, > 0, <= max; rotunjita la 2 zecimale. Intoarce -1 daca e invalida. */
    public static double cleanAmount(double v, double max) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
        double r = round2(v);
        if (r <= 0 || r > max) return -1;
        return r;
    }

    /** Alege un index dupa greutati (greutatile <= 0 sunt sarite). -1 daca nu exista niciuna pozitiva. */
    public static int pickWeighted(int[] weights, Random rnd) {
        long total = 0;
        for (int w : weights) if (w > 0) total += w;
        if (total <= 0) return -1;
        long roll = (long) (rnd.nextDouble() * total);
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0) continue;
            if (roll < weights[i]) return i;
            roll -= weights[i];
        }
        for (int i = weights.length - 1; i >= 0; i--) if (weights[i] > 0) return i; // siguranta numerica
        return -1;
    }

    /** XP necesar pentru a trece de la nivelul {@code level} la {@code level+1} (nivelul minim e 1). */
    public static long xpForLevel(int level, double base, double growth) {
        return Math.max(1L, Math.round(base * Math.pow(growth, Math.max(0, level - 1))));
    }

    /** Nivelul pentru XP-ul total (incepe de la 1, plafonat la max). */
    public static int levelFor(long totalXp, double base, double growth, int max) {
        int level = 1;
        long left = Math.max(0, totalXp);
        while (level < max) {
            long need = xpForLevel(level, base, growth);
            if (left < need) break;
            left -= need;
            level++;
        }
        return level;
    }

    /** XP acumulat in nivelul curent (0 la nivel maxim). */
    public static long xpIntoLevel(long totalXp, double base, double growth, int max) {
        int level = 1;
        long left = Math.max(0, totalXp);
        while (level < max) {
            long need = xpForLevel(level, base, growth);
            if (left < need) return left;
            left -= need;
            level++;
        }
        return 0;
    }

    /** Plata de baza cu bonus de nivel: base * (1 + bonusPerLevel*(level-1)), 2 zecimale. */
    public static double payWithBonus(double base, int level, double bonusPerLevel) {
        return round2(base * (1.0 + bonusPerLevel * Math.max(0, level - 1)));
    }

    /** Milisecunde ramase din cooldown (0 = disponibil). */
    public static long cooldownLeft(long lastMs, long cooldownMs, long now) {
        if (lastMs <= 0) return 0;
        long left = lastMs + cooldownMs - now;
        return left > 0 ? left : 0;
    }

    /** Taxa de transfer: procent din suma, cu minim; 2 zecimale. */
    public static double fee(double amount, double percent, double min) {
        if (percent <= 0 && min <= 0) return 0;
        return round2(Math.max(min, amount * percent / 100.0));
    }

    /** Numarul zilei locale (pentru limite zilnice). */
    public static long dayNumber(long ms) {
        return (ms + TimeZone.getDefault().getOffset(ms)) / DAY_MS;
    }

    /** Inceputul zilei locale (ms) pentru un moment dat. */
    public static long dayStart(long ms) {
        return dayNumber(ms) * DAY_MS - TimeZone.getDefault().getOffset(ms);
    }

    /** "1h 05m" / "12m 03s" / "45s" dintr-un numar de milisecunde. */
    public static String formatLeft(long ms) {
        long s = (ms + 999) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m";
        if (m > 0) return m + "m " + String.format("%02d", sec) + "s";
        return sec + "s";
    }
}
