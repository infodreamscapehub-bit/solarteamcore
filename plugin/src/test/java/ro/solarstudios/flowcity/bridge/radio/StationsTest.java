package ro.solarstudios.flowcity.bridge.radio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StationsTest {
    private Stations st;
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();
    private final Set<UUID> allowedOfficial = new HashSet<UUID>();
    private long now = 1_000_000L;

    @BeforeEach
    void setUp() {
        Stations.Config cfg = new Stations.Config();
        cfg.pbkdfIterations = 50; // teste rapide
        st = new Stations(cfg);
        st.setAccess((u, s) -> allowedOfficial.contains(u));
        st.registerOfficial("politie", "Politie", 1011);
    }

    private Stations.Station mk(UUID by, String name, int freq, Stations.Type t, String pass) {
        Stations.Result r = st.create(by, name, freq, t, pass, 10, now);
        assertTrue(r.ok, r.msg);
        now += 60_000L;
        return r.station;
    }

    @Test
    void creareNormalaSiCreatorulEOwnerConectat() {
        Stations.Station s = mk(a, "Prietenii", 1234, Stations.Type.PUBLIC, null);
        assertEquals(Stations.Role.OWNER, s.roleOf(a));
        assertEquals(s, st.activeOf(a));
        assertTrue(st.publicList("priet", 10).contains(s));
        assertEquals("123.4", Stations.fmtFreq(s.freq));
    }

    @Test
    void frecventaUnicaSiRezervataDepartamentelor() {
        mk(a, "Unu", 1234, Stations.Type.PUBLIC, null);
        assertFalse(st.create(b, "Doi", 1234, Stations.Type.PUBLIC, null, 10, now).ok);
        Stations.Result r = st.create(b, "Politia mea", 1011, Stations.Type.PUBLIC, null, 10, now);
        assertFalse(r.ok);
        assertTrue(r.msg.contains("rezervata"));
        assertFalse(st.create(b, "Prea mica", 10, Stations.Type.PUBLIC, null, 10, now).ok);
        assertFalse(st.create(b, "Prea mare", 99999, Stations.Type.PUBLIC, null, 10, now).ok);
        assertEquals(-1, Stations.parseFreq("abc"));
        assertEquals(1015, Stations.parseFreq("101,5"));
    }

    @Test
    void jucatorulNormalNuPoateCreaSauInchideStatiiDepartament() {
        assertFalse(st.create(a, "Dept", 2000, Stations.Type.DEPARTMENT, null, 10, now).ok);
        Stations.Station pol = st.byOfficialKey("politie");
        assertFalse(st.close(a, pol.id, now, true).ok);
        assertFalse(st.rename(a, pol.id, "Altceva").ok);
        assertFalse(st.setFreq(a, pol.id, 3000).ok);
        assertFalse(st.setPassword(a, pol.id, "parola1").ok);
    }

    @Test
    void accesLaStatiaOficialaDoarCuPermisiune() {
        Stations.Station pol = st.byOfficialKey("politie");
        assertFalse(st.join(a, pol.id, null, now).ok);
        assertNull(st.activeOf(a));
        allowedOfficial.add(a);
        assertTrue(st.join(a, pol.id, null, now).ok);
        assertEquals(pol, st.activeOf(a));
        // permisiunea se pierde (iese din tura): nu poate reveni pe canal
        allowedOfficial.clear();
        assertFalse(st.tune(a, pol.id, now).ok);
    }

    @Test
    void limitaDeStatiiCreateSiCooldown() {
        mk(a, "Unu", 1200, Stations.Type.PUBLIC, null);
        mk(a, "Doi", 1300, Stations.Type.PUBLIC, null);
        Stations.Result r = st.create(a, "Trei", 1400, Stations.Type.PUBLIC, null, 10, now);
        assertFalse(r.ok);
        assertTrue(r.msg.contains("cel mult"));
        // anti-spam: a doua creare imediat dupa prima
        Stations.Config cfg = new Stations.Config();
        cfg.pbkdfIterations = 50;
        Stations s2 = new Stations(cfg);
        assertTrue(s2.create(b, "Alfa", 1500, Stations.Type.PUBLIC, null, 10, 5000).ok);
        Stations.Result q = s2.create(b, "Beta", 1600, Stations.Type.PUBLIC, null, 10, 6000);
        assertFalse(q.ok);
        assertTrue(q.msg.contains("Asteapta"));
        assertTrue(s2.create(b, "Beta", 1600, Stations.Type.PUBLIC, null, 10, 5000 + cfg.createCooldownMs).ok);
    }

    @Test
    void numeInvalideSiDuplicate() {
        assertFalse(st.create(a, "ab", 1500, Stations.Type.PUBLIC, null, 10, now).ok);
        assertFalse(st.create(a, "<b>x</b>", 1500, Stations.Type.PUBLIC, null, 10, now).ok);
        mk(a, "Banda", 1500, Stations.Type.PUBLIC, null);
        assertFalse(st.create(b, "banda", 1600, Stations.Type.PUBLIC, null, 10, now).ok);
    }

    @Test
    void parolaSeStocheazaHashuitSiSeVerifica() {
        Stations.Station s = mk(a, "Secret", 1700, Stations.Type.PASSWORD, "parola123");
        assertFalse(s.hash.contains("parola123"));
        assertFalse(s.hash.isEmpty());
        assertFalse(st.toJson().contains("parola123"));
        assertFalse(st.join(b, s.id, "gresita", now).ok);
        assertFalse(st.join(b, s.id, null, now).ok);
        assertTrue(st.join(b, s.id, "parola123", now).ok);
        assertEquals(Stations.Role.MEMBER, s.roleOf(b));
        assertFalse(st.create(c, "FaraParola", 1800, Stations.Type.PASSWORD, "", 10, now).ok);
    }

    @Test
    void blocareDupaParoleGresiteRepetate() {
        Stations.Station s = mk(a, "Secret", 1700, Stations.Type.PASSWORD, "parola123");
        long t = now;
        for (int i = 0; i < st.cfg.maxWrongPass; i++) assertFalse(st.join(b, s.id, "x" + i, t).ok);
        Stations.Result locked = st.join(b, s.id, "parola123", t + 1000);
        assertFalse(locked.ok);
        assertTrue(locked.msg.contains("Prea multe"));
        assertTrue(st.join(b, s.id, "parola123", t + st.cfg.wrongPassLockMs + 1).ok);
    }

    @Test
    void statiePrivataCereInvitatieSiInvitatiaExpira() {
        Stations.Station s = mk(a, "Privat", 1900, Stations.Type.PRIVATE, null);
        assertFalse(st.join(b, s.id, null, now).ok);
        assertTrue(st.invite(a, b, s.id, now).ok);
        assertEquals(1, st.invitesFor(b, now).size());
        assertTrue(st.acceptInvite(b, s.id, now + 1000).ok);
        assertEquals(Stations.Role.MEMBER, s.roleOf(b));
        // expirare
        Stations.Station g = mk(a, "Grup", 2100, Stations.Type.GROUP, null);
        assertTrue(st.invite(a, c, g.id, now).ok);
        assertFalse(st.acceptInvite(c, g.id, now + st.cfg.inviteTtlMs + 1).ok);
        assertFalse(st.join(c, g.id, null, now + st.cfg.inviteTtlMs + 1).ok);
    }

    @Test
    void invitaDoarOwnerSiCoOwnerSiCuAntiSpam() {
        Stations.Station s = mk(a, "Privat", 1900, Stations.Type.PRIVATE, null);
        st.invite(a, b, s.id, now);
        st.acceptInvite(b, s.id, now);
        assertFalse(st.invite(b, c, s.id, now + 10_000).ok); // membru simplu
        st.promote(a, b, s.id);
        assertTrue(st.invite(b, c, s.id, now + 10_000).ok);
        UUID d = UUID.randomUUID();
        assertFalse(st.invite(b, d, s.id, now + 10_500).ok); // prea repede
        assertFalse(st.invite(a, a, s.id, now + 20_000).ok);
    }

    @Test
    void rolurilOwnerCoOwnerMember() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.join(b, s.id, null, now);
        st.join(c, s.id, null, now);
        assertFalse(st.promote(b, c, s.id).ok); // nu e owner
        assertTrue(st.promote(a, b, s.id).ok);
        assertEquals(Stations.Role.CO_OWNER, s.roleOf(b));
        assertFalse(st.kick(b, a, s.id, now).ok); // ownerul nu poate fi eliminat
        assertTrue(st.kick(b, c, s.id, now).ok);  // co-owner elimina membru
        assertNull(s.roleOf(c));
        assertFalse(st.rename(b, s.id, "Alt nume").ok); // doar ownerul
        assertFalse(st.setFreq(b, s.id, 2300).ok);
        assertFalse(st.close(b, s.id, now, false).ok);
        assertTrue(st.demote(a, b, s.id).ok);
        assertEquals(Stations.Role.MEMBER, s.roleOf(b));
    }

    @Test
    void transferDeProprietate() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.join(b, s.id, null, now);
        assertFalse(st.transfer(b, a, s.id, now).ok);
        assertFalse(st.transfer(a, c, s.id, now).ok); // c nu e membru
        assertTrue(st.transfer(a, b, s.id, now).ok);
        assertEquals(Stations.Role.OWNER, s.roleOf(b));
        assertEquals(Stations.Role.CO_OWNER, s.roleOf(a));
        assertTrue(st.setFreq(b, s.id, 2400).ok);
        assertFalse(st.setFreq(a, s.id, 2500).ok);
    }

    @Test
    void schimbareNumeFrecventaParola() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        assertTrue(st.rename(a, s.id, "Banda Noua").ok);
        assertFalse(st.setFreq(a, s.id, 1011).ok); // rezervata
        assertTrue(st.setFreq(a, s.id, 2201).ok);
        assertTrue(st.setPassword(a, s.id, "noua1234").ok);
        assertEquals(Stations.Type.PASSWORD, s.type);
        assertTrue(st.setPassword(a, s.id, "").ok);
        assertEquals(Stations.Type.PUBLIC, s.type);
        assertTrue(st.setMax(a, s.id, 1000).ok);
        assertEquals(st.cfg.maxMembersCap, s.maxMembers);
    }

    @Test
    void statiaPlinaNuMaiPrimesteMembri() {
        Stations.Result r = st.create(a, "Mica", 2600, Stations.Type.PUBLIC, null, 2, now);
        assertTrue(r.ok);
        assertTrue(st.join(b, r.station.id, null, now).ok);
        assertFalse(st.join(c, r.station.id, null, now).ok);
    }

    @Test
    void inchidereaScoateMembriiSiElibereazaFrecventa() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.join(b, s.id, null, now);
        assertFalse(st.close(b, s.id, now, false).ok);
        assertTrue(st.close(a, s.id, now, false).ok);
        assertNull(st.activeOf(a));
        assertNull(st.activeOf(b));
        assertNull(st.get(s.id));
        assertTrue(st.create(c, "Alta", 2200, Stations.Type.PUBLIC, null, 10, now).ok);
        assertTrue(st.historyOf(b).get(0).action.equals("CLOSED"));
    }

    @Test
    void ownerulCarePleacaDaProprietateaSauInchide() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.join(b, s.id, null, now);
        assertTrue(st.leave(a, s.id, now).ok);
        assertEquals(b, s.owner);
        assertNull(s.roleOf(a));
        assertTrue(st.leave(b, s.id, now).ok); // ultimul: statia se inchide
        assertNull(st.get(s.id));
    }

    @Test
    void ownerOfflineTransferAutomatDupaGratie() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.join(b, s.id, null, now);
        st.ownerOffline(a, now);
        st.tick(now + st.cfg.ownerOfflineGraceMs - 1, u -> true);
        assertEquals(a, s.owner); // inca in perioada de gratie
        st.tick(now + st.cfg.ownerOfflineGraceMs + 1, u -> true);
        assertEquals(b, s.owner);
        assertEquals(Stations.Role.CO_OWNER, s.roleOf(a));
    }

    @Test
    void ownerRevineInGratieSiPastreazaStatia() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.join(b, s.id, null, now);
        st.ownerOffline(a, now);
        st.ownerOnline(a);
        st.tick(now + st.cfg.ownerOfflineGraceMs * 5, u -> true);
        assertEquals(a, s.owner);
    }

    @Test
    void ownerSingurOfflineInchideStatiaDupaGratie() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PUBLIC, null);
        st.ownerOffline(a, now);
        st.tick(now + st.cfg.ownerOfflineGraceMs + 1, u -> false);
        assertNull(st.get(s.id));
    }

    @Test
    void persistentaDupaRestart() {
        Stations.Station s = mk(a, "Banda", 2200, Stations.Type.PASSWORD, "parola123");
        st.join(b, s.id, "parola123", now);
        st.promote(a, b, s.id);
        Stations.Station p = mk(c, "Privat", 2300, Stations.Type.PRIVATE, null);
        st.invite(c, a, p.id, now);
        String json = st.toJson();

        Stations.Config cfg = new Stations.Config();
        cfg.pbkdfIterations = 50;
        Stations back = new Stations(cfg);
        back.registerOfficial("politie", "Politie", 1011);
        back.loadJson(json);
        Stations.Station s2 = back.byFreq(2200);
        assertNotNull(s2);
        assertEquals("Banda", s2.name);
        assertEquals(a, s2.owner);
        assertEquals(Stations.Role.CO_OWNER, s2.roleOf(b));
        assertEquals(Stations.Type.PASSWORD, s2.type);
        assertTrue(back.passOk(s2, "parola123"));
        assertFalse(back.passOk(s2, "alta"));
        assertEquals(1, back.invitesFor(a, now).size());
        assertEquals(back.historyOf(a).size(), st.historyOf(a).size());
        // statia oficiala a ramas, iar frecventa ei tot rezervata
        assertNotNull(back.byOfficialKey("politie"));
        assertFalse(back.create(UUID.randomUUID(), "Furata", 1011, Stations.Type.PUBLIC, null, 10, now).ok);
        // id-urile noi nu se suprapun cu cele incarcate
        Stations.Station nou = back.create(UUID.randomUUID(), "Noua", 2400, Stations.Type.PUBLIC, null, 10, now).station;
        assertTrue(nou.id > s2.id && nou.id > back.byFreq(2300).id);
    }

    @Test
    void dateStricateNuOpresc() {
        st.loadJson("nu e json");
        st.loadJson("{\"stations\":[{\"id\":1}],\"history\":{\"xx\":[]}}");
        assertEquals(0, st.personalCount());
    }

    @Test
    void listeSiCautare() {
        mk(a, "Taxi Noapte", 2000, Stations.Type.PUBLIC, null);
        mk(b, "Politia Fan", 2100, Stations.Type.PRIVATE, null);
        mk(c, "Taxi Zi", 2200, Stations.Type.PUBLIC, null);
        List<Stations.Station> r = st.publicList("taxi", 10);
        assertEquals(2, r.size());
        assertEquals(2, st.publicList("", 10).size()); // cea privata nu apare
        assertEquals(1, st.publicList("", 1).size());
        assertEquals(1, st.joined(a).size());
        assertEquals(Arrays.asList(a), st.tunedIn(st.joined(a).get(0).id, Arrays.asList(a, b, c)));
    }

    @Test
    void esteConectatPeOSingurastatieOdata() {
        Stations.Station s1 = mk(a, "Unu", 2000, Stations.Type.PUBLIC, null);
        Stations.Station s2 = mk(b, "Doi", 2100, Stations.Type.PUBLIC, null);
        assertEquals(s1, st.activeOf(a));
        assertTrue(st.join(a, s2.id, null, now).ok);
        assertEquals(s2, st.activeOf(a));
        assertTrue(st.tune(a, s1.id, now).ok);
        assertEquals(s1, st.activeOf(a));
        assertTrue(st.disconnect(a).ok);
        assertNull(st.activeOf(a));
        assertFalse(st.disconnect(a).ok);
        assertFalse(st.tune(c, s1.id, now).ok); // nu e membru
        // iesirea unui jucator il scoate de pe statie
        st.join(c, s1.id, null, now);
        st.playerOffline(c);
        assertNull(st.activeOf(c));
    }
}
