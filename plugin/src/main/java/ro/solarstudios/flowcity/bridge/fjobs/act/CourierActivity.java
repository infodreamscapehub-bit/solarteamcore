package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * CURIER: la depozit ("depot", click dreapta) primesti un lot de colete fizice (iteme marcate) cu adresa si destinatarul scrise pe
 * eticheta. Duci coletele la adresele diferite ("address"); la usa verifici destinatarul (mini-joc: alegi persoana corecta dupa eticheta).
 * Cronometrul e optional (buton in panou): cu cronometru primesti bonus de livrare rapida. Plata = baza + pret pe distanta, validata pe
 * server (viteza maxima plauzibila, coletul trebuie sa fie in inventar).
 */
public final class CourierActivity extends BaseActivity<CourierActivity.St> {
    static final class Parcel {
        String id;
        FJobDef.Point to;
        String recipient;
        double dist;
        long deadline;   // 0 = fara cronometru
    }

    static final class St {
        final List<Parcel> parcels = new ArrayList<>();
        FJobDef.Point from;
        long loadedAt;
        int seq;
        int deliveredShift;
        final java.util.Map<String, Long> refused = new java.util.HashMap<>();
        boolean mg;
    }

    private static final String[] DEFAULT_NAMES = {"Ion Popescu", "Maria Ionescu", "Andrei Dumitru", "Elena Stan", "Vlad Marin", "Ana Radu", "Mihai Georgescu",
        "Ioana Matei", "Radu Constantin", "Cristina Popa", "Bogdan Ene", "Laura Dinu"};

    public CourierActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "courier";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"depot", "Depozite de colete (ridicare)"});
        l.add(new String[] {"address", "Adrese de livrare"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("depot").isEmpty()) return "seteaza cel putin un depozit de colete ('depot').";
        if (jd.stationList("address").size() < 2) return "seteaza cel putin 2 adrese de livrare ('address').";
        return null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        Items.removeTagged(api.plugin(), p, "fj_parcel");
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) Items.removeTagged(api.plugin(), p, "fj_parcel");
    }

    @Override
    protected boolean busy(Player p, St s) {
        return !s.parcels.isEmpty();
    }

    private boolean timerOn(Player p, FJobDef jd) {
        return "on".equals(api.opt(p, jd, "timer", "off"));
    }

    private List<String> names(FJobDef jd) {
        List<String> n = jd.conf == null ? new ArrayList<String>() : jd.conf.getStringList("recipients");
        if (n.size() < 4) n = new ArrayList<>(java.util.Arrays.asList(DEFAULT_NAMES));
        return n;
    }

    private void load(Player p, FJobDef jd, St s, FJobDef.Point depot) {
        if (!s.parcels.isEmpty()) {
            Fx.bar(p, "§eMai ai colete de livrat.");
            return;
        }
        Diff d = diff(p, jd);
        List<FJobDef.Point> addr = new ArrayList<>();
        double minD = jd.cd("min-distance", 40);
        for (FJobDef.Point a : pts(jd, "address")) if (a.world.equals(depot.world) && a.dist(depot) >= minD) addr.add(a);
        if (addr.isEmpty()) for (FJobDef.Point a : pts(jd, "address")) if (a.world.equals(depot.world)) addr.add(a);
        if (addr.isEmpty()) {
            api.msg(p, false, "Nu exista adrese in aceeasi lume cu depozitul.");
            return;
        }
        Collections.shuffle(addr, api.rnd());
        int n = Math.min(addr.size(), Math.max(1, d.size));
        List<String> nm = names(jd);
        boolean timer = timerOn(p, jd);
        long now = System.currentTimeMillis();
        s.from = depot;
        s.loadedAt = now;
        for (int i = 0; i < n; i++) {
            Parcel c = new Parcel();
            c.id = Integer.toString(++s.seq, 36) + Integer.toString(api.rnd().nextInt(1296), 36);
            c.to = addr.get(i);
            c.recipient = nm.get(api.rnd().nextInt(nm.size()));
            c.dist = depot.dist(c.to);
            if (timer) c.deadline = now + (long) ((jd.cd("deadline-base-seconds", 45) + c.dist / 100.0 * jd.cd("deadline-seconds-per-100m", 14)) * 1000);
            s.parcels.add(c);
            Items.give(p, Items.make(api.plugin(), Material.PAPER, "§6Colet #" + c.id,
                Items.lore("§7Adresa: §f" + c.to.name, "§7Destinatar: §f" + c.recipient, "§8Verifica destinatarul la livrare"), "fj_parcel", p.getUniqueId() + ":" + c.id));
        }
        api.gps(p, s.parcels.get(0).to);
        Fx.sound(p, "ENTITY_ITEM_PICKUP", 0.8f, 1f);
        api.toast(p, jd.name, "Ai primit " + n + " colete" + (timer ? " (cu cronometru)" : "") + ". Livreaza-le la adrese.");
        api.refreshPanel(p);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        St s = state(p);
        if (s.mg || api.challenges().has(p)) return;
        FJobDef.Point depot = nearest(p, pts(jd, "depot"), jd.cd("depot-radius", 5));
        if (depot != null) {
            e.setCancelled(true);
            if (cooling(p, "load", 2000)) return;
            load(p, jd, s, depot);
            return;
        }
        long now = System.currentTimeMillis();
        for (Parcel c : s.parcels) {
            if (!near(p, c.to, jd.cd("address-radius", 4))) continue;
            e.setCancelled(true);
            if (cooling(p, "verify", 1500)) return;
            Long until = s.refused.get(c.id);
            if (until != null && until > now) {
                Fx.bar(p, "§cClientul a refuzat coletul. Reincearca peste " + ((until - now) / 1000 + 1) + " s.");
                return;
            }
            if (!Items.has(api.plugin(), p, "fj_parcel", p.getUniqueId() + ":" + c.id)) {
                Fx.bar(p, "§cNu ai coletul #" + c.id + " la tine.");
                return;
            }
            verify(p, jd, s, c);
            return;
        }
    }

    private void verify(Player p, FJobDef jd, St s, Parcel c) {
        Diff d = diff(p, jd);
        List<String> pool = new ArrayList<>(names(jd));
        pool.remove(c.recipient);
        Collections.shuffle(pool, api.rnd());
        List<String> opts = new ArrayList<>(pool.subList(0, Math.min(pool.size(), Math.max(2, d.size))));
        opts.add(c.recipient);
        Collections.shuffle(opts, api.rnd());
        Challenges.Spec sp = Challenges.pick("Verificare destinatar", c.to.name, "Cine e destinatarul de pe eticheta coletului #" + c.id + "? Citeste eticheta din inventar.", opts, opts.indexOf(c.recipient));
        sp.attempts = 2;
        sp.limitMs = 40_000;
        s.mg = true;
        if (!api.challenges().start(p, sp, (pl, ok, q) -> done(pl, jd, c, ok, q))) s.mg = false;
    }

    private void done(Player p, FJobDef jd, Parcel c, boolean ok, double q) {
        St s = state(p);
        s.mg = false;
        long now = System.currentTimeMillis();
        if (!s.parcels.contains(c)) return;
        if (!ok) {
            s.refused.put(c.id, now + 20_000);
            api.toast(p, jd.name, "Destinatar gresit: clientul a refuzat coletul.");
            api.addCounter(p, "courier.refused", 1);
            return;
        }
        if (!Items.removeOne(api.plugin(), p, "fj_parcel", p.getUniqueId() + ":" + c.id)) return;
        s.parcels.remove(c);
        Diff d = diff(p, jd);
        long minMs = (long) (c.dist / Math.max(5, jd.cd("max-speed", 30)) * 1000);
        if (now - s.loadedAt < minMs) {
            api.toast(p, jd.name, "Livrare prea rapida pentru distanta: nu a fost platita.");
            return;
        }
        double base = jd.cd("base-pay", 12) + jd.cd("per-100m", 5) * (c.dist / 100.0);
        double fast = 1.0;
        String note = "";
        if (c.deadline > 0) {
            if (now <= c.deadline) {
                double left = (double) (c.deadline - now) / Math.max(1, c.deadline - s.loadedAt);
                fast = 1.0 + Math.min(jd.cd("fast-bonus-max", 0.5), left * jd.cd("fast-bonus-max", 0.5) * 1.4);
                note = " (bonus rapid x" + (Math.round(fast * 100) / 100.0) + ")";
            } else {
                note = " (peste termen)";
            }
        }
        double pay = payQ(p, jd, base * fast, Math.round(jd.cd("xp", 6)), d, Math.max(0.5, q), true);
        s.deliveredShift++;
        api.addCounter(p, "courier.delivered", 1);
        Fx.sound(p, "ENTITY_PLAYER_LEVELUP", 0.6f, 1.5f);
        api.toast(p, "Colet livrat: " + c.to.name, "+" + money(Math.max(0, pay)) + note);
        if (s.parcels.isEmpty()) {
            api.toast(p, jd.name, "Lot terminat. Intoarce-te la depozit pentru unul nou.");
            if (!pts(jd, "depot").isEmpty()) api.gps(p, pts(jd, "depot").get(0));
        } else {
            api.gps(p, s.parcels.get(0).to);
        }
        api.refreshPanel(p);
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        List<FJobDef.Point> tg = new ArrayList<>();
        for (Parcel c : s.parcels) tg.add(c.to);
        mark(p, tg, "VILLAGER_HAPPY", 40);
        mark(p, pts(jd, "depot"), "END_ROD", 40);
        for (Iterator<Parcel> it = s.parcels.iterator(); it.hasNext(); ) {
            Parcel c = it.next();
            if (c.deadline > 0 && now > c.deadline + (long) (jd.cd("expire-after-seconds", 180) * 1000)) {
                it.remove();
                Items.removeOne(api.plugin(), p, "fj_parcel", p.getUniqueId() + ":" + c.id);
                api.toast(p, jd.name, "Coletul #" + c.id + " a expirat si a fost returnat.");
            }
        }
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.parcels.isEmpty()) {
            lines.add("Mergi la depozit pentru un lot");
            lines.add("Livrate in tura: " + s.deliveredShift);
            return -1;
        }
        long now = System.currentTimeMillis();
        lines.add("Colete de livrat: " + s.parcels.size());
        Parcel c = s.parcels.get(0);
        lines.add("Urmatorul: " + c.to.name + " (" + Math.round(dist(p, c.to)) + " m)");
        lines.add("Destinatar: " + c.recipient);
        if (c.deadline > 0) {
            long left = (c.deadline - now) / 1000;
            lines.add(left >= 0 ? "Timp ramas: " + time(left) : "Termen depasit");
            return Math.max(0, Math.min(1.0, (double) (c.deadline - now) / Math.max(1, c.deadline - s.loadedAt)));
        }
        return -1;
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        boolean t = timerOn(p, jd);
        pn.status(s.parcels.isEmpty() ? "Fara colete" : s.parcels.size() + " colete de livrat");
        if (s.parcels.isEmpty()) {
            pn.obj("Mergi la un depozit si da click dreapta pentru a primi un lot.");
        } else {
            for (Parcel c : s.parcels) pn.obj("#" + c.id + " -> " + c.to.name + " (" + c.recipient + ", " + Math.round(c.dist) + " m)");
        }
        pn.stat("Colete livrate", String.valueOf(api.counter(p, "courier.delivered")));
        pn.stat("Refuzate", String.valueOf(api.counter(p, "courier.refused")));
        pn.stat("Cronometru", t ? "pornit" : "oprit");
        pn.stat("Adrese setate", String.valueOf(pts(jd, "address").size()));
        pn.btn("gps", "Navigheaza la urmatoarea adresa", !s.parcels.isEmpty());
        pn.btn("timer", t ? "Opreste cronometrul" : "Porneste cronometrul (bonus rapid)", s.parcels.isEmpty());
        pn.reward("Baza " + money(jd.cd("base-pay", 12)) + " + " + money(jd.cd("per-100m", 5)) + " / 100 m");
        pn.reward("Bonus livrare rapida cu cronometru: pana la +" + (int) (jd.cd("fast-bonus-max", 0.5) * 100) + "%");
        pn.tip("Verifica destinatarul de pe eticheta coletului (inventar).");
        pn.tip("Un destinatar gresit inseamna refuz si 20 s de asteptare.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, com.google.gson.JsonObject j) {
        St s = state(p);
        if ("gps".equals(act) && !s.parcels.isEmpty()) {
            api.gps(p, s.parcels.get(0).to);
            return true;
        }
        if ("timer".equals(act) && s.parcels.isEmpty()) {
            api.setOpt(p, jd, "timer", timerOn(p, jd) ? "off" : "on");
            return true;
        }
        return false;
    }
}
