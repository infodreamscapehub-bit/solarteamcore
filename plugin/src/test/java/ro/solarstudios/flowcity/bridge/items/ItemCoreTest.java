package ro.solarstudios.flowcity.bridge.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Teste pentru nucleul framework-ului FlowCityItemActions (fara server Bukkit). */
class ItemCoreTest {
    @TempDir
    File dir;

    // ---------------------------------------------------------------- serialuri (duplicare / exploatare / persistenta)

    @Test
    void serialValidAfterIssue() {
        Serials s = new Serials(new File(dir, "s.tsv"));
        String id = s.issue("bodycam", UUID.randomUUID(), -1, 1L);
        assertEquals(Serials.Check.OK, s.validate(id, "bodycam"));
    }

    @Test
    void forgedOrMissingSerialIsRejected() {
        Serials s = new Serials(new File(dir, "s.tsv"));
        assertEquals(Serials.Check.UNKNOWN, s.validate("inventat123", "bodycam"));
        assertEquals(Serials.Check.UNKNOWN, s.validate(null, "bodycam"));
    }

    @Test
    void serialOfAnotherItemIsRejected() {
        Serials s = new Serials(new File(dir, "s.tsv"));
        String id = s.issue("radio", UUID.randomUUID(), -1, 1L);
        assertEquals(Serials.Check.WRONG_ITEM, s.validate(id, "defibrilator"));
    }

    @Test
    void consumableRunsOutAndIsRevoked() {
        Serials s = new Serials(new File(dir, "s.tsv"));
        String id = s.issue("trusa_medicala", UUID.randomUUID(), 2, 1L);
        assertEquals(1, s.consume(id));
        assertEquals(0, s.consume(id));
        assertEquals(Serials.Check.REVOKED, s.validate(id, "trusa_medicala"));
        assertEquals(-2, s.consume(id));
    }

    @Test
    void unlimitedItemNeverRunsOut() {
        Serials s = new Serials(new File(dir, "s.tsv"));
        String id = s.issue("catuse", UUID.randomUUID(), -1, 1L);
        for (int i = 0; i < 50; i++) assertEquals(-1, s.consume(id));
        assertEquals(Serials.Check.OK, s.validate(id, "catuse"));
    }

    @Test
    void duplicatedStacksAreDetected() {
        List<String> inv = Arrays.asList("aaa", null, "bbb", "aaa", "ccc", "bbb", "aaa");
        Set<Integer> dup = Serials.duplicates(inv);
        assertEquals(3, dup.size());
        assertTrue(dup.contains(3) && dup.contains(5) && dup.contains(6));
        assertFalse(dup.contains(0));
    }

    @Test
    void serialsSurviveRestart() {
        File f = new File(dir, "s.tsv");
        Serials a = new Serials(f);
        String keep = a.issue("radio", UUID.randomUUID(), -1, 5L);
        String used = a.issue("kit_reparatii", UUID.randomUUID(), 1, 6L);
        a.consume(used);
        Serials b = new Serials(f);
        assertEquals(Serials.Check.OK, b.validate(keep, "radio"));
        assertEquals(Serials.Check.REVOKED, b.validate(used, "kit_reparatii"));
        assertEquals(2, b.size());
    }

    // ---------------------------------------------------------------- pauze

    @Test
    void cooldownBlocksUntilTimePasses() {
        AtomicLong now = new AtomicLong(1000);
        Cooldowns c = new Cooldowns(now::get);
        assertTrue(c.tryUse("p:radio", 500));
        assertFalse(c.tryUse("p:radio", 500));
        assertEquals(500, c.remaining("p:radio"));
        now.set(1499);
        assertFalse(c.tryUse("p:radio", 500));
        now.set(1500);
        assertTrue(c.tryUse("p:radio", 500));
        assertTrue(c.tryUse("alt", 500));
    }

    // ---------------------------------------------------------------- permisiuni / distanta

    @Test
    void missingPermissionIsDenied() {
        List<String> need = Arrays.asList("flowcity.politie.officer");
        assertFalse(Rules.hasAny(p -> false, need));
        assertTrue(Rules.hasAny(p -> p.equals("flowcity.politie.officer"), need));
        assertTrue(Rules.hasAny(p -> false, Collections.<String>emptyList()));
        assertTrue(Rules.hasAny(p -> p.equals("b"), Arrays.asList("a", "b")));
    }

    @Test
    void distanceChecks() {
        assertTrue(Rules.within("w", 0, 64, 0, "w", 2, 64, 0, 3));
        assertFalse(Rules.within("w", 0, 64, 0, "w", 10, 64, 0, 3));
        assertFalse(Rules.within("w", 0, 64, 0, "nether", 0, 64, 0, 3));
        assertNull(Rules.distance("w", 0, 0, 0, "x", 0, 0, 0));
    }

    // ---------------------------------------------------------------- activitati

    private static final class FakeActor implements Activities.Actor {
        final UUID id = UUID.randomUUID();
        boolean online = true;
        boolean alive = true;
        String world = "w";
        double x;
        double y = 64;
        double z;
        String held = "ser1";

        public UUID id() { return id; }
        public boolean online() { return online; }
        public boolean alive() { return alive; }
        public String world() { return world; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public String heldSerial() { return held; }
    }

    private static java.util.function.Function<UUID, Activities.Actor> look(final FakeActor a) {
        return u -> u.equals(a.id) ? a : null;
    }

    @Test
    void activityCompletesAfterDuration() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        AtomicBoolean done = new AtomicBoolean();
        assertTrue(eng.start(a, "defib", 0, 3000, 0.6, null, () -> done.set(true), r -> { throw new AssertionError(r); }));
        eng.tick(look(a), 1000);
        assertFalse(done.get());
        assertEquals(1f / 3f, eng.progress(a.id, 1000), 0.01f);
        eng.tick(look(a), 3000);
        assertTrue(done.get());
        assertFalse(eng.busy(a.id));
    }

    @Test
    void onlyOneActivityPerPlayer() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        assertTrue(eng.start(a, "x", 0, 1000, 1, null, null, null));
        assertFalse(eng.start(a, "y", 0, 1000, 1, null, null, null));
    }

    @Test
    void movingCancelsActivity() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        AtomicReference<String> why = new AtomicReference<String>();
        AtomicBoolean done = new AtomicBoolean();
        eng.start(a, "scan", 0, 2000, 0.6, null, () -> done.set(true), why::set);
        a.x = 3;
        eng.tick(look(a), 500);
        assertEquals("te-ai miscat", why.get());
        eng.tick(look(a), 5000);
        assertFalse(done.get());
    }

    @Test
    void swappingTheItemCancelsActivity() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        AtomicReference<String> why = new AtomicReference<String>();
        eng.start(a, "heal", 0, 2000, 1, null, null, why::set);
        a.held = null;
        eng.tick(look(a), 100);
        assertEquals("ai schimbat itemul", why.get());
    }

    @Test
    void disconnectCancelsActivityAndNeverCompletes() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        AtomicReference<String> why = new AtomicReference<String>();
        AtomicBoolean done = new AtomicBoolean();
        eng.start(a, "cuff", 0, 1000, 1, null, () -> done.set(true), why::set);
        a.online = false;
        eng.tick(look(a), 5000);
        assertEquals("deconectat", why.get());
        assertFalse(done.get());
        // jucatorul nici nu mai e gasit pe server
        FakeActor b = new FakeActor();
        AtomicReference<String> why2 = new AtomicReference<String>();
        eng.start(b, "cuff", 0, 1000, 1, null, null, why2::set);
        eng.tick(u -> null, 10);
        assertEquals("deconectat", why2.get());
    }

    @Test
    void deathCancelsActivity() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        AtomicReference<String> why = new AtomicReference<String>();
        eng.start(a, "x", 0, 1000, 1, null, null, why::set);
        a.alive = false;
        eng.tick(look(a), 10);
        assertEquals("ai murit", why.get());
    }

    @Test
    void invalidTargetDistanceCancelsActivity() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        final double[] targetX = {1};
        AtomicReference<String> why = new AtomicReference<String>();
        eng.start(a, "defib", 0, 3000, 1, () -> Rules.within("w", a.x, a.y, a.z, "w", targetX[0], 64, 0, 3) ? null : "pacientul e prea departe",
            null, why::set);
        eng.tick(look(a), 500);
        assertNull(why.get());
        targetX[0] = 20;
        eng.tick(look(a), 900);
        assertEquals("pacientul e prea departe", why.get());
    }

    @Test
    void manualCancelCallsHandler() {
        Activities eng = new Activities();
        FakeActor a = new FakeActor();
        AtomicReference<String> why = new AtomicReference<String>();
        eng.start(a, "x", 0, 1000, 1, null, null, why::set);
        assertTrue(eng.cancel(a.id, "anulat"));
        assertEquals("anulat", why.get());
        assertFalse(eng.cancel(a.id, "anulat"));
    }

    // ---------------------------------------------------------------- catuse (persistenta)

    @Test
    void cuffsSurviveRestartAndEscortDoesNot() {
        File f = new File(dir, "cuffs.tsv");
        CuffRegistry r = new CuffRegistry(f);
        UUID t = UUID.randomUUID();
        UUID o = UUID.randomUUID();
        assertTrue(r.cuff(t, o, 100));
        assertFalse(r.cuff(t, o, 200));
        assertTrue(r.setEscort(t, o));
        assertEquals(1, r.escortedBy(o).size());
        CuffRegistry again = new CuffRegistry(f);
        assertTrue(again.isCuffed(t));
        assertEquals(o, again.get(t).by);
        assertNull(again.get(t).escort);
        assertTrue(again.release(t));
        assertFalse(new CuffRegistry(f).isCuffed(t));
    }

    @Test
    void cuffsExpireAutomatically() {
        CuffRegistry r = new CuffRegistry(null);
        UUID t = UUID.randomUUID();
        r.cuff(t, UUID.randomUUID(), 1000);
        assertTrue(r.expired(1500, 1000).isEmpty());
        assertEquals(1, r.expired(2000, 1000).size());
        assertTrue(r.expired(999999, 0).isEmpty());
    }

    // ---------------------------------------------------------------- stare medicala

    @Test
    void downedFlowBleedingToArrestToDead() {
        DownedRegistry d = new DownedRegistry(null);
        UUID u = UUID.randomUUID();
        assertTrue(d.down(u, 0));
        assertFalse(d.down(u, 1));
        assertTrue(d.advance(1000, 5000, 3000).isEmpty());
        List<DownedRegistry.Change> c = d.advance(5000, 5000, 3000);
        assertEquals(1, c.size());
        assertEquals(DownedRegistry.State.ARREST, c.get(0).to);
        assertEquals(DownedRegistry.State.ARREST, d.state(u));
        c = d.advance(8000, 5000, 3000);
        assertEquals(1, c.size());
        assertNull(c.get(0).to);
        assertFalse(d.isDown(u));
    }

    @Test
    void stabilizedPatientDoesNotBleedOut() {
        DownedRegistry d = new DownedRegistry(null);
        UUID u = UUID.randomUUID();
        d.down(u, 0);
        assertTrue(d.stabilize(u, 2000));
        assertEquals(2000, d.get(u).bledMs);
        assertTrue(d.advance(10_000_000, 5000, 3000).isEmpty());
        assertEquals(DownedRegistry.State.STABLE, d.state(u));
        assertFalse(d.stabilize(u, 3));
        assertTrue(d.recover(u));
    }

    @Test
    void downedStateSurvivesRestart() {
        File f = new File(dir, "down.tsv");
        DownedRegistry d = new DownedRegistry(f);
        UUID u = UUID.randomUUID();
        d.down(u, 777);
        d.addShock(u);
        DownedRegistry again = new DownedRegistry(f);
        assertEquals(DownedRegistry.State.BLEEDING, again.state(u));
        assertEquals(777, again.get(u).since);
        assertEquals(1, again.get(u).shocks);
    }

    @Test
    void shockChanceDropsWithTimeAndShocks() {
        double early = DownedRegistry.shockChance(0, 100_000, 0);
        double late = DownedRegistry.shockChance(100_000, 100_000, 0);
        double afterShocks = DownedRegistry.shockChance(0, 100_000, 3);
        assertTrue(early > late);
        assertTrue(early > afterShocks);
        assertTrue(late >= 0.15 && early <= 0.9);
    }

    // ---------------------------------------------------------------- amprente

    @Test
    void fingerprintHashIsStablePerPlayerAndDifferent() {
        Prints p = new Prints("sare", 4);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(p.hash(a), p.hash(a));
        assertNotEquals(p.hash(a), p.hash(b));
        assertEquals(12, p.hash(a).length());
        assertFalse(p.hash(a).contains(a.toString().substring(0, 6)));
        assertNotEquals(p.hash(a), new Prints("alta", 4).hash(a));
    }

    @Test
    void tracesAreLeftCollectedAndExpire() {
        Prints p = new Prints("s", 3);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        p.leave("w:1:64:1", a, 1000);
        p.leave("w:1:64:1", b, 2000);
        List<Prints.Trace> t = p.at("w:1:64:1", 3000, 10_000);
        assertEquals(2, t.size());
        assertEquals(p.hash(b), t.get(0).hash);
        assertTrue(Prints.matches(t.get(1).hash, p.hash(a)));
        assertTrue(p.at("w:1:64:1", 20_000, 10_000).isEmpty());
        assertEquals(2, p.purge(20_000, 10_000));
        assertTrue(p.at("altundeva", 0, 1).isEmpty());
    }

    @Test
    void tracesPerPlaceAreCappedAndRefreshed() {
        Prints p = new Prints("s", 2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        p.leave("x", a, 1);
        p.leave("x", b, 2);
        p.leave("x", c, 3);
        assertEquals(2, p.at("x", 4, 100).size());
        p.leave("x", c, 5);
        assertEquals(2, p.at("x", 6, 100).size());
        p.wipe("x");
        assertTrue(p.at("x", 6, 100).isEmpty());
    }
}
