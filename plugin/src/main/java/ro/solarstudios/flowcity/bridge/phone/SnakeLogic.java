package ro.solarstudios.flowcity.bridge.phone;

/** Verificari de plauzibilitate pentru scorurile Snake trimise de client (anti-trisare de baza). */
public final class SnakeLogic {
    private SnakeLogic() {}

    /**
     * @param score         scorul raportat (mancare mancata)
     * @param durationMs    durata raportata a jocului
     * @param minMsPerPoint timpul minim credibil pentru un punct
     * @param maxScore      plafon absolut
     */
    public static boolean plausible(int score, long durationMs, int minMsPerPoint, int maxScore) {
        if (score < 0 || score > maxScore) return false;
        if (durationMs < 0 || durationMs > 3_600_000L) return false;
        return durationMs >= (long) score * minMsPerPoint;
    }
}
