package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * POSTAS: la oficiul postal ("post", click dreapta) ridici un sac de corespondenta: scrisori fizice (iteme marcate) cu destinatar si
 * cutie postala. Le SORTEZI pe strazi (mini-joc "sort": cutiile "mailbox" cu acelasi prefix = o strada); sortare reusita = bonus, esuata =
 * corespondenta amestecata (plata redusa). Apoi le duci pe jos la cutiile postale (click dreapta): scrisorile obisnuite se depun direct,
 * cele RECOMANDATE cer verificarea destinatarului (mini-joc "pick"). Sacul terminat da bonus de finalizare dupa timp. Fata de curier:
 * lucru in lot, sortare, cutii pe strazi, fara cronometru de livrare.
 */
public final class PostmanActivity extends BaseActivity<PostmanActivity.St> {
    static final class Letter {
        String id;
        FJobDef.Point box;
        String recipient;
        boolean registered;
    }

    static final class St {
        final List<Letter> letters = new ArrayList<>();
        boolean sorted;
        double sortQ;
        long startedAt;
        int total;
        int delivered;
        int seq;
        boolean mg;
        long nextAt;
        double earned;
    }

    private static final String[] NAMES = {"Ion Popescu", "Maria Ionescu", "Andrei Dumitru", "Elena Stan", "Vlad Marin", "Ana Radu", "Mihai Georgescu", "Ioana Matei",
        "Radu Constantin", "Cristina Popa", "Bogdan Ene", "Laura Dinu"};

    public PostmanActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "postman";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"post", "Oficii postale (ridicare + sortare)"});
        l.add(new String[] {"mailbox", "Cutii postale (prefix = strada)"});
        return l;
    }

    private int streets(FJobDef jd) {
        Set<String> g = new LinkedHashSet<>();
        for (FJobDef.Point m : jd.stationList("mailbox")) g.add(group(m));
        return g.size();
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("post").isEmpty()) return "seteaza oficiul postal ('post').";
        if (streets(jd) < 2) return "seteaza cutii postale pe cel putin 2 strazi ('mailbox', prefix = strada, ex. 'Str. Florilor - nr 3').";
        return null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        Items.removeTagged(api.plugin(), p, "fj_mail");
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) Items.removeTagged(api.plugin(), p, "fj_mail");
    }

    @Override
    protected boolean busy(Player p, St s) {
        return !s.letters.isEmpty();
    }

    private void pickup(Player p, FJobDef jd, St s) {
        if (!s.letters.isEmpty()) {
            Fx.bar(p, "§eMai ai scrisori de livrat.");
            return;
        }
        if (System.currentTimeMillis() < s.nextAt) {
            Fx.bar(p, "§7Sacul urmator se pregateste...");
            return;
        }
        Diff d = diff(p, jd);
        List<String> streetNames = new ArrayList<>();
        for (FJobDef.Point m : jd.stationList("mailbox")) if (!streetNames.contains(group(m))) streetNames.add(group(m));
        Collections.shuffle(streetNames, api.rnd());
        List<String> chosen = new ArrayList<>(streetNames.subList(0, Math.min(streetNames.size(), Math.min(4, 2 + d.size / 3))));
        List<FJobDef.Point> boxes = new ArrayList<>();
        for (FJobDef.Point m : jd.stationList("mailbox")) if (chosen.contains(group(m))) boxes.add(m);
        int n = Math.max(3, d.size + 3);
        s.total = n;
        s.delivered = 0;
        s.earned = 0;
        s.startedAt = System.currentTimeMillis();
        List<String> items = new ArrayList<>();
        int[] bin = new int[n];
        for (int i = 0; i < n; i++) {
            Letter l = new Letter();
            l.id = Integer.toString(++s.seq, 36) + api.rnd().nextInt(99);
            l.box = boxes.get(api.rnd().nextInt(boxes.size()));
            l.recipient = NAMES[api.rnd().nextInt(NAMES.length)];
            l.registered = api.rnd().nextDouble() < jd.cd("registered-chance", 0.25);
            s.letters.add(l);
            items.add((l.registered ? "[R] " : "") + l.recipient + " - " + l.box.name.substring(Math.max(0, l.box.name.indexOf(" - ") + 3)) + "");
            bin[i] = chosen.indexOf(group(l.box));
            Items.give(p, Items.make(api.plugin(), Material.PAPER, (l.registered ? "§cScrisoare recomandata" : "§fScrisoare") + " #" + l.id,
                Items.lore("§7Destinatar: §f" + l.recipient, "§7Adresa: §f" + l.box.name, l.registered ? "§cVerifica destinatarul" : "§8Depune in cutia postala"), "fj_mail", p.getUniqueId() + ":" + l.id));
        }
        s.sorted = false;
        s.sortQ = 0;
        Challenges.Spec sp = Challenges.sort("Sortare corespondenta", "Pune fiecare scrisoare pe strada ei", items, chosen, bin);
        sp.attempts = d.attempts;
        sp.limitMs = d.seconds * 1000L + 25_000;
        s.mg = true;
        Fx.sound(p, "ENTITY_ITEM_PICKUP", 0.8f, 1f);
        if (!api.challenges().start(p, sp, (pl, ok, q) -> afterSort(pl, jd, ok, q))) s.mg = false;
        api.refreshPanel(p);
    }

    private void afterSort(Player p, FJobDef jd, boolean ok, double q) {
        St s = state(p);
        s.mg = false;
        s.sorted = ok;
        s.sortQ = ok ? q : 0;
        if (s.letters.isEmpty()) return;
        api.toast(p, jd.name, ok ? "Corespondenta sortata. Du scrisorile la cutii." : "Sortare esuata: corespondenta amestecata (plata redusa).");
        gpsNext(p, jd, s);
        api.refreshPanel(p);
    }

    private void gpsNext(Player p, FJobDef jd, St s) {
        Letter best = null;
        double bd = Double.MAX_VALUE;
        for (Letter l : s.letters) {
            double d = dist(p, l.box);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        if (best != null) api.gps(p, best.box);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        St s = state(p);
        if (s.mg || api.challenges().has(p)) return;
        if (nearest(p, pts(jd, "post"), jd.cd("post-radius", 5)) != null) {
            e.setCancelled(true);
            if (!cooling(p, "pickup", 2000)) pickup(p, jd, s);
            return;
        }
        for (Letter l : s.letters) {
            if (!near(p, l.box, jd.cd("mailbox-radius", 3.5))) continue;
            if (!Items.has(api.plugin(), p, "fj_mail", p.getUniqueId() + ":" + l.id)) continue;
            e.setCancelled(true);
            if (cooling(p, "deliver", 1000)) return;
            if (l.registered) verify(p, jd, s, l);
            else deliver(p, jd, s, l, 1.0);
            return;
        }
    }

    private void verify(Player p, FJobDef jd, St s, Letter l) {
        List<String> pool = new ArrayList<>(java.util.Arrays.asList(NAMES));
        pool.remove(l.recipient);
        Collections.shuffle(pool, api.rnd());
        List<String> opts = new ArrayList<>(pool.subList(0, 3));
        opts.add(l.recipient);
        Collections.shuffle(opts, api.rnd());
        Challenges.Spec sp = Challenges.pick("Scrisoare recomandata", l.box.name, "Cine semneaza de primire? Citeste destinatarul de pe plic.", opts, opts.indexOf(l.recipient));
        sp.attempts = 2;
        sp.limitMs = 40_000;
        s.mg = true;
        if (!api.challenges().start(p, sp, (pl, ok, q) -> {
            state(pl).mg = false;
            if (ok) deliver(pl, jd, state(pl), l, 1.0 + jd.cd("registered-bonus", 0.6));
            else api.toast(pl, jd.name, "Destinatar gresit: plicul a ramas la tine. Reincearca.");
        })) s.mg = false;
    }

    private void deliver(Player p, FJobDef jd, St s, Letter l, double mult) {
        if (!s.letters.contains(l)) return;
        if (!Items.removeOne(api.plugin(), p, "fj_mail", p.getUniqueId() + ":" + l.id)) return;
        s.letters.remove(l);
        s.delivered++;
        Diff d = diff(p, jd);
        double f = s.sorted ? 1.0 : 0.8;
        double pay = payQ(p, jd, jd.cd("letter-pay", 5) * mult * f, Math.round(jd.cd("letter-xp", 2)), d, 1.0, false);
        s.earned += Math.max(0, pay);
        api.addCounter(p, "postman.letters", 1);
        Fx.sound(p, "BLOCK_CHEST_CLOSE", 0.6f, 1.4f);
        Fx.bar(p, "§aScrisoare depusa (" + s.delivered + "/" + s.total + ") +" + money(Math.max(0, pay)));
        if (s.letters.isEmpty()) {
            long el = System.currentTimeMillis() - s.startedAt;
            double par = s.total * jd.cd("par-seconds-per-letter", 25) * 1000 + 30_000;
            double perf = Math.max(0.7, Math.min(1.35, par / Math.max(1, el)));
            double bonus = payQ(p, jd, jd.cd("complete-bonus", 35) * (s.total / 6.0), Math.round(jd.cd("complete-xp", 12)), d, perf * (s.sorted ? 1.0 + 0.2 * s.sortQ : 0.85), true);
            api.addCounter(p, "postman.bags", 1);
            api.toast(p, "Sac terminat", "Bonus " + money(Math.max(0, bonus)) + " | timp " + time(el / 1000) + (s.sorted ? " | sortare " + Math.round(s.sortQ * 100) + "%" : ""));
            s.nextAt = System.currentTimeMillis() + (long) (jd.cd("bag-delay-seconds", 8) * 1000);
            if (!pts(jd, "post").isEmpty()) api.gps(p, pts(jd, "post").get(0));
        } else {
            gpsNext(p, jd, s);
        }
        api.refreshPanel(p);
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        mark(p, pts(jd, "post"), "END_ROD", 40);
        List<FJobDef.Point> tg = new ArrayList<>();
        for (Letter l : s.letters) tg.add(l.box);
        mark(p, tg, "VILLAGER_HAPPY", 35);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.letters.isEmpty()) {
            lines.add("Mergi la oficiul postal pentru un sac");
            return -1;
        }
        lines.add("Scrisori ramase: " + s.letters.size() + "/" + s.total);
        lines.add(s.sorted ? "Sortate: da (" + Math.round(s.sortQ * 100) + "%)" : "Sortare: " + (s.mg ? "in curs" : "esuata"));
        Letter n = null;
        double bd = Double.MAX_VALUE;
        for (Letter l : s.letters) {
            double d = dist(p, l.box);
            if (d < bd) {
                bd = d;
                n = l;
            }
        }
        if (n != null) lines.add("Cea mai apropiata: " + n.box.name + " (" + Math.round(bd) + " m)");
        return (double) s.delivered / Math.max(1, s.total);
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.letters.isEmpty() ? "Fara sac" : s.letters.size() + " scrisori de livrat");
        if (s.letters.isEmpty()) {
            pn.obj("Mergi la oficiul postal si da click dreapta: primesti si sortezi corespondenta.");
        } else {
            for (Letter l : s.letters) pn.obj((l.registered ? "[R] " : "") + l.recipient + " -> " + l.box.name);
        }
        pn.stat("Scrisori livrate", String.valueOf(api.counter(p, "postman.letters")));
        pn.stat("Saci terminati", String.valueOf(api.counter(p, "postman.bags")));
        pn.stat("Strazi setate", String.valueOf(streets(jd)));
        pn.btn("gps", "Navigheaza la urmatoarea cutie", !s.letters.isEmpty());
        pn.reward(money(jd.cd("letter-pay", 5)) + " per scrisoare; recomandata +" + (int) (jd.cd("registered-bonus", 0.6) * 100) + "%; sortare esuata -20%");
        pn.reward("Bonus de sac: " + money(jd.cd("complete-bonus", 35)) + " (dupa timp si calitatea sortarii)");
        pn.tip("Scrisorile recomandate cer verificarea destinatarului.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        St s = state(p);
        if ("gps".equals(act)) {
            if (!s.letters.isEmpty()) gpsNext(p, jd, s);
            else if (!pts(jd, "post").isEmpty()) api.gps(p, pts(jd, "post").get(0));
            return true;
        }
        return false;
    }
}
