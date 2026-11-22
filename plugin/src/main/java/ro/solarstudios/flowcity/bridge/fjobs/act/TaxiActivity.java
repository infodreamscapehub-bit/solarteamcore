package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * TAXIMETRIST: la fiecare cateva secunde apar comenzi (pana la 3) intre un "stand" si o "destination"; le accepti din panou (sau cu
 * click dreapta langa un stand). La ridicare apare un pasager real (villager fara AI) pe care il iei cand ajungi la el; apoi mergi la
 * destinatie. Aparatul masoara distanta PARCURSA (nu doar linia dreapta, fara teleportari) si plata = tarif de pornire + pret/100 m,
 * cu penalizare daca pasagerul asteapta prea mult. Limita onesta: masina vine din Vehicle Mod si nu e detectata sigur ca vehicul Bukkit,
 * de aceea cerinta "sa fii intr-un vehicul" e optionala (require-vehicle: true in fjobs.yml).
 */
public final class TaxiActivity extends BaseActivity<TaxiActivity.St> {
    static final class Offer {
        FJobDef.Point from;
        FJobDef.Point to;
        String who;
        long expires;
        double straight;
    }

    static final class St {
        final List<Offer> offers = new ArrayList<>();
        Offer cur;
        int stage;            // 0 = spre pasager, 1 = in cursa
        java.util.UUID passenger;
        long nearSince;
        long acceptedAt;
        long boardedAt;
        double traveled;
        Location last;
        long nextOffer;
        int doneShift;
    }

    private static final String[] WHO = {"Dl. Popescu", "Doamna Ionescu", "Un turist", "Studentul Andrei", "Doamna Elena", "Dl. Marin", "Ioana", "Un om de afaceri",
        "Bunica Maria", "Mihai"};

    public TaxiActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "taxi";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"stand", "Statii de taxi (ridicare pasageri)"});
        l.add(new String[] {"destination", "Destinatii posibile"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("stand").isEmpty()) return "seteaza cel putin o statie de taxi ('stand').";
        if (jd.stationList("destination").size() < 2) return "seteaza cel putin 2 destinatii ('destination').";
        return null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        s.nextOffer = System.currentTimeMillis() + 3000;
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        removePassenger(s);
    }

    @Override
    protected void onQuit(Player p, St s) {
        removePassenger(s);
    }

    private void removePassenger(St s) {
        if (s.passenger != null) {
            Entity e = org.bukkit.Bukkit.getEntity(s.passenger);
            if (e != null) e.remove();
            s.passenger = null;
        }
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.cur != null;
    }

    private boolean vehicleOk(Player p, FJobDef jd) {
        return !jd.cb("require-vehicle", false) || p.isInsideVehicle();
    }

    private void makeOffers(Player p, FJobDef jd, St s, long now) {
        for (Iterator<Offer> it = s.offers.iterator(); it.hasNext(); ) if (it.next().expires < now) it.remove();
        if (s.cur != null || s.offers.size() >= 3 || now < s.nextOffer) return;
        s.nextOffer = now + (long) (jd.cd("offer-interval-seconds", 20) * 1000);
        Diff d = diff(p, jd);
        List<FJobDef.Point> from = new ArrayList<>(pts(jd, "stand"));
        Collections.shuffle(from, api.rnd());
        FJobDef.Point f = from.get(0);
        double minD = jd.cd("min-distance", 80) + 25 * d.size;
        List<FJobDef.Point> dests = new ArrayList<>();
        for (FJobDef.Point t : pts(jd, "destination")) if (t.world.equals(f.world) && t.dist(f) >= minD) dests.add(t);
        if (dests.isEmpty()) for (FJobDef.Point t : pts(jd, "destination")) if (t.world.equals(f.world) && t.dist(f) >= 40) dests.add(t);
        if (dests.isEmpty()) return;
        Offer o = new Offer();
        o.from = f;
        o.to = dests.get(api.rnd().nextInt(dests.size()));
        o.who = WHO[api.rnd().nextInt(WHO.length)];
        o.straight = f.dist(o.to);
        o.expires = now + (long) (jd.cd("offer-ttl-seconds", 60) * 1000);
        s.offers.add(o);
        api.toast(p, "Comanda taxi", o.who + ": " + o.from.name + " -> " + o.to.name + " (" + Math.round(o.straight) + " m). Accepta din panou.");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_CHIME", 0.8f, 1.4f);
        api.refreshPanel(p);
    }

    private void accept(Player p, FJobDef jd, St s, int idx) {
        if (s.cur != null || idx < 0 || idx >= s.offers.size()) return;
        Offer o = s.offers.remove(idx);
        s.cur = o;
        s.stage = 0;
        s.acceptedAt = System.currentTimeMillis();
        s.traveled = 0;
        s.last = p.getLocation();
        World w = org.bukkit.Bukkit.getWorld(o.from.world);
        if (w != null) {
            Location at = new Location(w, o.from.x, o.from.y, o.from.z);
            Villager v = w.spawn(at, Villager.class, vv -> {
                vv.setAI(false);
                vv.setInvulnerable(true);
                vv.setSilent(true);
                vv.setPersistent(false);
                vv.setCollidable(false);
                vv.setCustomName("§e" + o.who);
                vv.setCustomNameVisible(true);
            });
            s.passenger = v.getUniqueId();
        }
        api.gps(p, o.from);
        api.toast(p, jd.name, "Mergi la " + o.from.name + " dupa " + o.who + ".");
        api.refreshPanel(p);
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        makeOffers(p, jd, s, now);
        if (s.cur == null) {
            mark(p, pts(jd, "stand"), "END_ROD", 40);
            return;
        }
        Offer o = s.cur;
        if (s.stage == 0) {
            Entity pe = s.passenger == null ? null : org.bukkit.Bukkit.getEntity(s.passenger);
            boolean near = near(p, o.from, jd.cd("pickup-radius", 5));
            if (near && vehicleOk(p, jd)) {
                if (s.nearSince == 0) s.nearSince = now;
                Fx.bar(p, "§eSe urca pasagerul...");
                if (now - s.nearSince >= jd.cd("board-seconds", 2) * 1000) {
                    if (pe != null) pe.remove();
                    s.passenger = null;
                    s.stage = 1;
                    s.boardedAt = now;
                    s.traveled = 0;
                    s.last = p.getLocation();
                    s.nearSince = 0;
                    api.gps(p, o.to);
                    Fx.sound(p, "ENTITY_VILLAGER_YES", 0.8f, 1f);
                    api.toast(p, jd.name, o.who + " a urcat. Du-l la " + o.to.name + ".");
                    api.refreshPanel(p);
                }
            } else {
                s.nearSince = 0;
                if (near && !vehicleOk(p, jd)) Fx.bar(p, "§cTrebuie sa fii intr-un vehicul.");
            }
            long maxWait = (long) (jd.cd("pickup-timeout-seconds", 240) * 1000);
            if (now - s.acceptedAt > maxWait) {
                removePassenger(s);
                api.toast(p, jd.name, "Pasagerul a plecat: ai intarziat prea mult.");
                s.cur = null;
                s.nextOffer = now + 5000;
                api.refreshPanel(p);
            }
            return;
        }
        // in cursa: aparatul numara doar deplasarea plauzibila
        Location cur = p.getLocation();
        if (s.last != null && s.last.getWorld() == cur.getWorld() && vehicleOk(p, jd)) {
            double dx = cur.getX() - s.last.getX();
            double dz = cur.getZ() - s.last.getZ();
            double step = Math.sqrt(dx * dx + dz * dz);
            if (step <= jd.cd("max-speed", 40) * 1.2) s.traveled += step;
        }
        s.last = cur;
        if (near(p, o.to, jd.cd("dropoff-radius", 6)) && vehicleOk(p, jd)) finish(p, jd, s, now);
    }

    private void finish(Player p, FJobDef jd, St s, long now) {
        Offer o = s.cur;
        Diff d = diff(p, jd);
        s.cur = null;
        s.nextOffer = now + 6000;
        double min = o.straight * jd.cd("min-route-ratio", 0.6);
        if (s.traveled < min) {
            api.toast(p, jd.name, "Cursa nu a fost platita: distanta parcursa (" + Math.round(s.traveled) + " m) e prea mica fata de traseu.");
            api.refreshPanel(p);
            return;
        }
        double meter = Math.min(s.traveled, o.straight * jd.cd("max-route-ratio", 1.6));
        double fare = jd.cd("start-fare", 8) + jd.cd("per-100m", 6) * (meter / 100.0);
        double expectSec = o.straight / Math.max(1, jd.cd("expected-speed", 11));
        double sec = (now - s.boardedAt) / 1000.0;
        double q = 1.0;
        String note = "";
        if (sec > expectSec * jd.cd("patience", 2.2)) {
            q = 0.7;
            note = " (pasager nemultumit de durata)";
        } else if (sec < expectSec) {
            q = 1.0 + jd.cd("fast-tip", 0.15);
            note = " (+bacsis)";
        }
        double pay = payQ(p, jd, fare, Math.round(jd.cd("xp", 8)), d, q, true);
        s.doneShift++;
        api.addCounter(p, "taxi.rides", 1);
        api.addCounter(p, "taxi.meters", Math.round(meter));
        Fx.sound(p, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.8f, 1.2f);
        api.toast(p, "Cursa terminata: " + Math.round(meter) + " m", "+" + money(Math.max(0, pay)) + note);
        api.refreshPanel(p);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        St s = state(p);
        if (s.cur != null) return;
        FJobDef.Point stand = nearest(p, pts(jd, "stand"), jd.cd("stand-radius", 6));
        if (stand == null) return;
        for (int i = 0; i < s.offers.size(); i++) {
            if (s.offers.get(i).from == stand) {
                if (cooling(p, "accept", 1500)) return;
                e.setCancelled(true);
                accept(p, jd, s, i);
                return;
            }
        }
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        long now = System.currentTimeMillis();
        if (s.cur == null) {
            lines.add(s.offers.isEmpty() ? "Se asteapta comenzi..." : "Comenzi disponibile: " + s.offers.size());
            if (!s.offers.isEmpty()) lines.add("Deschide panoul job pentru a accepta");
            lines.add("Curse in tura: " + s.doneShift);
            return -1;
        }
        if (s.stage == 0) {
            lines.add("Ridica: " + s.cur.who);
            lines.add(s.cur.from.name + " (" + Math.round(dist(p, s.cur.from)) + " m)");
            return -1;
        }
        lines.add("Pasager: " + s.cur.who);
        lines.add("Spre: " + s.cur.to.name + " (" + Math.round(dist(p, s.cur.to)) + " m)");
        lines.add("Aparat: " + Math.round(s.traveled) + " m  |  " + money(jd.cd("start-fare", 8) + jd.cd("per-100m", 6) * s.traveled / 100.0));
        return Math.max(0, Math.min(1.0, 1.0 - dist(p, s.cur.to) / Math.max(1, s.cur.straight)));
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        long now = System.currentTimeMillis();
        pn.status(s.cur == null ? "Liber" : s.stage == 0 ? "Mergi dupa pasager" : "In cursa");
        if (s.cur != null) {
            pn.obj((s.stage == 0 ? "Ridica pasagerul la " + s.cur.from.name : "Du pasagerul la " + s.cur.to.name));
        } else if (s.offers.isEmpty()) {
            pn.obj("Nu sunt comenzi acum. Apar la cateva secunde.");
        } else {
            pn.obj("Alege o comanda:");
            for (int i = 0; i < s.offers.size(); i++) {
                Offer o = s.offers.get(i);
                double est = jd.cd("start-fare", 8) + jd.cd("per-100m", 6) * o.straight / 100.0;
                pn.obj((i + 1) + ". " + o.who + ": " + o.from.name + " -> " + o.to.name + " (" + Math.round(o.straight) + " m, ~" + money(est) + ", expira in " + Math.max(0, (o.expires - now) / 1000) + " s)");
                pn.btn("accept" + i, "Accepta comanda " + (i + 1), s.cur == null);
            }
        }
        pn.stat("Curse", String.valueOf(api.counter(p, "taxi.rides")));
        pn.stat("Distanta totala", api.counter(p, "taxi.meters") + " m");
        pn.stat("Cerinta vehicul", jd.cb("require-vehicle", false) ? "da" : "nu");
        pn.btn("gps", "Navigheaza", s.cur != null);
        pn.btn("cancel", "Anuleaza cursa", s.cur != null && s.stage == 0);
        pn.reward("Pornire " + money(jd.cd("start-fare", 8)) + " + " + money(jd.cd("per-100m", 6)) + " / 100 m parcursi");
        pn.reward("Bacsis la cursa rapida, penalizare daca pasagerul asteapta mult");
        pn.tip("Aparatul numara kilometrii reali parcursi, nu linia dreapta.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        St s = state(p);
        if (act.startsWith("accept")) {
            try {
                accept(p, jd, s, Integer.parseInt(act.substring(6)));
            } catch (NumberFormatException e) {
                return false;
            }
            return true;
        }
        if ("gps".equals(act) && s.cur != null) {
            api.gps(p, s.stage == 0 ? s.cur.from : s.cur.to);
            return true;
        }
        if ("cancel".equals(act) && s.cur != null && s.stage == 0) {
            removePassenger(s);
            s.cur = null;
            s.nextOffer = System.currentTimeMillis() + 8000;
            return true;
        }
        return false;
    }
}
