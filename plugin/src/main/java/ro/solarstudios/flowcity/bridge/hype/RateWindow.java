package ro.solarstudios.flowcity.bridge.hype;

/** Limita de actiuni pe fereastra fixa (ex. 40/minut). Un obiect per jucator si job. Thread-ul principal. */
public final class RateWindow {
    private final int max;
    private final long windowMs;
    private long start;
    private int count;
    private boolean notified;

    public RateWindow(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    /** True daca actiunea e permisa (si o numara). max <= 0 inseamna fara limita. */
    public boolean tryAcquire(long now) {
        if (max <= 0) return true;
        if (now - start >= windowMs) {
            start = now;
            count = 0;
            notified = false;
        }
        if (count >= max) return false;
        count++;
        return true;
    }

    /** True o singura data pe fereastra, cand limita e depasita (pentru un mesaj, nu spam). */
    public boolean firstBlock() {
        if (notified) return false;
        notified = true;
        return true;
    }
}
