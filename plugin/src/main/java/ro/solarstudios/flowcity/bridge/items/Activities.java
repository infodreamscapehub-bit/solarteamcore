package ro.solarstudios.flowcity.bridge.items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Activitati cu durata (resuscitare, scanare, incatusare...): pornesc, ruleaza cateva secunde si se termina cu succes sau se anuleaza
 * (jucatorul s-a miscat, a schimbat itemul, a iesit, a murit, tinta a plecat). Un jucator are cel mult o activitate. Motorul nu foloseste
 * Bukkit: jucatorul e vazut printr-un {@link Actor}, deci logica se poate testa fara server.
 */
public final class Activities {
    /** Instantaneu al jucatorului, citit de pe server la fiecare tick. */
    public interface Actor {
        UUID id();

        boolean online();

        boolean alive();

        String world();

        double x();

        double y();

        double z();

        /** Serialul itemului din mana (null daca nu tine un item marcat). */
        String heldSerial();
    }

    public static final class Activity {
        public final UUID who;
        public final String key;
        public final long start;
        public final long duration;
        final String world;
        final double sx;
        final double sy;
        final double sz;
        final double maxMove;
        final String serial;
        final Supplier<String> targetCheck;
        final Runnable onDone;
        final Consumer<String> onCancel;

        Activity(UUID who, String key, long start, long duration, Actor a, double maxMove, Supplier<String> targetCheck,
                 Runnable onDone, Consumer<String> onCancel) {
            this.who = who;
            this.key = key;
            this.start = start;
            this.duration = duration;
            this.world = a.world();
            this.sx = a.x();
            this.sy = a.y();
            this.sz = a.z();
            this.maxMove = maxMove;
            this.serial = a.heldSerial();
            this.targetCheck = targetCheck;
            this.onDone = onDone;
            this.onCancel = onCancel;
        }
    }

    private final Map<UUID, Activity> running = new HashMap<UUID, Activity>();

    public synchronized boolean busy(UUID who) {
        return running.containsKey(who);
    }

    public synchronized Activity get(UUID who) {
        return running.get(who);
    }

    /**
     * Porneste o activitate. {@code targetCheck} (poate fi null) intoarce motivul anularii sau null daca totul e in regula
     * (tinta online, la distanta buna etc.). Intoarce false daca jucatorul are deja o activitate.
     */
    public synchronized boolean start(Actor a, String key, long now, long durationMs, double maxMove, Supplier<String> targetCheck,
                                      Runnable onDone, Consumer<String> onCancel) {
        if (running.containsKey(a.id())) return false;
        running.put(a.id(), new Activity(a.id(), key, now, Math.max(0, durationMs), a, maxMove, targetCheck, onDone, onCancel));
        return true;
    }

    /** Anuleaza activitatea jucatorului; intoarce true daca exista una. */
    public boolean cancel(UUID who, String reason) {
        Activity a;
        synchronized (this) {
            a = running.remove(who);
        }
        if (a == null) return false;
        if (a.onCancel != null) a.onCancel.accept(reason);
        return true;
    }

    /** Un pas: anuleaza ce nu mai e valid, termina ce a ajuns la capat. {@code lookup} intoarce jucatorul sau null daca s-a deconectat. */
    public void tick(Function<UUID, Actor> lookup, long now) {
        List<Activity> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<Activity>(running.values());
        }
        for (Activity a : snapshot) {
            Actor p = lookup.apply(a.who);
            String bad = null;
            if (p == null || !p.online()) bad = "deconectat";
            else if (!p.alive()) bad = "ai murit";
            else if (Rules.distance(a.world, a.sx, a.sy, a.sz, p.world(), p.x(), p.y(), p.z()) == null
                || Rules.distance(a.world, a.sx, a.sy, a.sz, p.world(), p.x(), p.y(), p.z()) > a.maxMove) bad = "te-ai miscat";
            else if (a.serial != null && !a.serial.equals(p.heldSerial())) bad = "ai schimbat itemul";
            else if (a.targetCheck != null) bad = a.targetCheck.get();
            if (bad != null) {
                cancel(a.who, bad);
                continue;
            }
            if (now - a.start >= a.duration) {
                synchronized (this) {
                    if (running.get(a.who) != a) continue;
                    running.remove(a.who);
                }
                if (a.onDone != null) a.onDone.run();
            }
        }
    }

    /** Progres 0..1 pentru bara din HUD. */
    public synchronized float progress(UUID who, long now) {
        Activity a = running.get(who);
        if (a == null || a.duration <= 0) return 0f;
        return Math.max(0f, Math.min(1f, (now - a.start) / (float) a.duration));
    }
}
