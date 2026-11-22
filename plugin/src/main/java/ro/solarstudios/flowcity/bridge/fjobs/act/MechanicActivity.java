package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * MECANIC: comenzi de service la boxe ("bay"). Masina e virtuala (model + numar generate), defectul e ales la intamplare:
 * 1) DIAGNOSTIC la box (mini-joc "pick": simptome -> componenta defecta); 2) PIESA: o cumperi de la magazinul de piese ("parts", platesti
 * din banii tai) si primesti un obiect marcat; 3) INLOCUIRE la box (consuma piesa) printr-un mini-joc dependent de componenta (wires / seq).
 * Comenzile de SERVICE (revizie) nu cer piesa: sortezi piesele pe sisteme (mini-joc "sort"). Plata depaseste costul piesei; piesa e pierduta
 * daca repari gresit. Nu foloseste sistemul de mecanic al masinilor din mod (acela ramane separat).
 */
public final class MechanicActivity extends BaseActivity<MechanicActivity.St> {
    static final class Comp {
        String id;
        String name;
        String part;
        double cost;
        double pay;
        long xp;
        int minLevel;
        String symptom;
        String game;
    }

    static final class St {
        FJobDef.Point bay;
        Comp comp;          // null = service
        String car = "";
        int stage;          // 0 = la box (diagnostic), 1 = trebuie piesa, 2 = piesa cumparata, 3 = in reparatie
        String orderId = "";
        long assignedAt;
        long nextAt;
        long lockUntil;
        int done;
        int seq;
        boolean mg;
    }

    private static final String[] CARS = {"Dacia Logan", "Dacia Duster", "VW Golf", "BMW Seria 3", "Audi A4", "Skoda Octavia", "Ford Focus", "Renault Clio", "Opel Astra",
        "Mercedes C-Class"};

    public MechanicActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "mechanic";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"bay", "Boxe de service"});
        l.add(new String[] {"parts", "Magazin de piese"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("bay").isEmpty()) return "seteaza cel putin un box de service ('bay').";
        if (jd.stationList("parts").isEmpty() && !jd.cb("parts-at-bay", false)) return "seteaza magazinul de piese ('parts') sau porneste parts-at-bay in fjobs.yml.";
        return null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    private Map<String, Comp> comps(FJobDef jd) {
        Map<String, Comp> m = new LinkedHashMap<>();
        ConfigurationSection s = jd.conf == null ? null : jd.conf.getConfigurationSection("components");
        if (s != null && !s.getKeys(false).isEmpty()) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) continue;
                Comp x = new Comp();
                x.id = id;
                x.name = c.getString("name", id);
                x.part = c.getString("part", "Piesa " + x.name);
                x.cost = Math.max(0, c.getDouble("cost", 10));
                x.pay = Math.max(0, c.getDouble("pay", 40));
                x.xp = Math.max(0, c.getLong("xp", 10));
                x.minLevel = Math.max(0, c.getInt("min-level", 0));
                x.symptom = c.getString("symptom", "Masina are o problema.");
                x.game = c.getString("game", "seq");
                m.put(id, x);
            }
        } else {
            String[][] def = {{"spark", "Bujii uzate", "Set bujii", "12", "45", "12", "0", "Motorul trepideaza si porneste greu.", "seq"},
                {"battery", "Baterie descarcata", "Baterie noua", "25", "55", "14", "0", "Farurile slabe, rotitele de pornire abia se aud.", "wires"},
                {"brakes", "Placute de frana uzate", "Set placute", "30", "70", "18", "3", "Frana scartaie si distanta de franare a crescut.", "seq"},
                {"alternator", "Alternator defect", "Alternator", "55", "110", "26", "6", "Bateria se descarca in mers, martorul de incarcare e aprins.", "wires"}};
            for (String[] d : def) {
                Comp x = new Comp();
                x.id = d[0];
                x.name = d[1];
                x.part = d[2];
                x.cost = Double.parseDouble(d[3]);
                x.pay = Double.parseDouble(d[4]);
                x.xp = Long.parseLong(d[5]);
                x.minLevel = Integer.parseInt(d[6]);
                x.symptom = d[7];
                x.game = d[8];
                m.put(x.id, x);
            }
        }
        return m;
    }

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        s.nextAt = System.currentTimeMillis() + 2000;
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        Items.removeTagged(api.plugin(), p, "fj_part");
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) Items.removeTagged(api.plugin(), p, "fj_part");
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.bay != null;
    }

    private void clear(Player p, St s, long delay) {
        s.bay = null;
        s.comp = null;
        s.stage = 0;
        s.mg = false;
        s.nextAt = System.currentTimeMillis() + delay;
        Items.removeTagged(api.plugin(), p, "fj_part");
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        if (s.bay == null) {
            if (now >= s.nextAt) assign(p, jd, s, now);
            return;
        }
        if (now - s.assignedAt > jd.cd("order-timeout-seconds", 480) * 1000 && !s.mg) {
            api.toast(p, jd.name, "Clientul a plecat: comanda a expirat.");
            clear(p, s, 5000);
            return;
        }
        mark(p, java.util.Collections.singletonList(s.bay), "END_ROD", 40);
        if (s.stage == 1) mark(p, pts(jd, "parts"), "VILLAGER_HAPPY", 40);
    }

    private void assign(Player p, FJobDef jd, St s, long now) {
        List<FJobDef.Point> bays = pts(jd, "bay");
        if (bays.isEmpty()) return;
        s.bay = bays.get(api.rnd().nextInt(bays.size()));
        int lvl = api.jobLevel(p, jd.id);
        List<Comp> cs = new ArrayList<>();
        for (Comp c : comps(jd).values()) if (c.minLevel <= lvl) cs.add(c);
        boolean service = cs.isEmpty() || api.rnd().nextDouble() < jd.cd("service-chance", 0.25);
        s.comp = service ? null : cs.get(api.rnd().nextInt(cs.size()));
        s.car = CARS[api.rnd().nextInt(CARS.length)] + " (B-" + (100 + api.rnd().nextInt(900)) + "-" + (char) ('A' + api.rnd().nextInt(26)) + (char) ('A' + api.rnd().nextInt(26)) + (char) ('A' + api.rnd().nextInt(26)) + ")";
        s.orderId = Integer.toString(++s.seq, 36) + api.rnd().nextInt(99);
        s.stage = 0;
        s.assignedAt = now;
        api.gps(p, s.bay);
        api.toast(p, jd.name, (service ? "Service" : "Defect") + ": " + s.car + " la " + s.bay.name + ". Click dreapta la box.");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_BELL", 0.8f, 1.0f);
        api.refreshPanel(p);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        St s = state(p);
        if (s.bay == null || s.mg || api.challenges().has(p)) return;
        long now = System.currentTimeMillis();
        if (s.stage == 1 && (nearest(p, pts(jd, "parts"), jd.cd("parts-radius", 4)) != null)) {
            e.setCancelled(true);
            if (cooling(p, "buy", 1500)) return;
            buy(p, jd, s);
            return;
        }
        if (!near(p, s.bay, jd.cd("bay-radius", 4)) || now < s.lockUntil) return;
        e.setCancelled(true);
        if (cooling(p, "bay", 1500)) return;
        if (s.stage == 0) {
            if (s.comp == null) service(p, jd, s);
            else diagnose(p, jd, s);
        } else if (s.stage == 1 && jd.cb("parts-at-bay", false)) {
            buy(p, jd, s);
        } else if (s.stage == 2) {
            swap(p, jd, s);
        } else if (s.stage == 1) {
            Fx.bar(p, "§eAi nevoie de piesa: " + s.comp.part + ". Cumpara-o de la magazinul de piese.");
        }
    }

    private void service(Player p, FJobDef jd, St s) {
        Diff d = diff(p, jd);
        String[][] pool = {{"Filtru ulei", "0"}, {"Ulei motor", "0"}, {"Bujie", "0"}, {"Curea distributie", "0"}, {"Placute frana", "1"}, {"Disc frana", "1"}, {"Lichid frana", "1"},
            {"Baterie", "2"}, {"Alternator", "2"}, {"Sigurante", "2"}, {"Far", "2"}, {"Amortizor", "3"}, {"Arc suspensie", "3"}};
        List<String[]> l = new ArrayList<>(java.util.Arrays.asList(pool));
        Collections.shuffle(l, api.rnd());
        int n = Math.min(l.size(), 3 + d.size);
        List<String> items = new ArrayList<>();
        int[] bin = new int[n];
        for (int i = 0; i < n; i++) {
            items.add(l.get(i)[0]);
            bin[i] = Integer.parseInt(l.get(i)[1]);
        }
        List<String> bins = java.util.Arrays.asList("Motor", "Frane", "Electric", "Suspensie");
        Challenges.Spec sp = Challenges.sort("Service - " + s.car, "Pune fiecare piesa in sistemul corect", items, bins, bin);
        sp.attempts = d.attempts;
        sp.limitMs = d.seconds * 1000L + 20_000;
        s.mg = true;
        if (!api.challenges().start(p, sp, (pl, ok, q) -> finish(pl, jd, ok, q, jd.cd("service-pay", 35), Math.round(jd.cd("service-xp", 9)), 0))) s.mg = false;
    }

    private void diagnose(Player p, FJobDef jd, St s) {
        Diff d = diff(p, jd);
        List<Comp> all = new ArrayList<>(comps(jd).values());
        Collections.shuffle(all, api.rnd());
        List<Comp> shown = new ArrayList<>();
        shown.add(s.comp);
        for (Comp c : all) if (shown.size() < Math.max(3, Math.min(all.size(), d.size + 1)) && c != s.comp) shown.add(c);
        Collections.shuffle(shown, api.rnd());
        List<String> names = new ArrayList<>();
        for (Comp c : shown) names.add(c.name);
        Challenges.Spec sp = Challenges.pick("Diagnostic - " + s.car, "Ce componenta e defecta?", "Simptome: " + s.comp.symptom, names, shown.indexOf(s.comp));
        sp.attempts = d.attempts;
        sp.limitMs = d.seconds * 1000L;
        s.mg = true;
        if (!api.challenges().start(p, sp, (pl, ok, q) -> afterDiag(pl, jd, ok, q))) s.mg = false;
    }

    private void afterDiag(Player p, FJobDef jd, boolean ok, double q) {
        St s = state(p);
        s.mg = false;
        if (s.bay == null) return;
        if (!ok) {
            api.toast(p, jd.name, "Diagnostic gresit. Boxul e blocat 30 s.");
            s.lockUntil = System.currentTimeMillis() + 30_000;
            api.addCounter(p, "mechanic.failed", 1);
            return;
        }
        s.stage = 1;
        if (!jd.cb("parts-at-bay", false)) {
            api.gps(p, pts(jd, "parts").get(0));
        }
        api.toast(p, jd.name, "Defect: " + s.comp.name + ". Cumpara piesa: " + s.comp.part + " (" + money(s.comp.cost) + ").");
        api.refreshPanel(p);
    }

    private void buy(Player p, FJobDef jd, St s) {
        if (s.comp == null || s.stage != 1) return;
        if (!api.plugin().money().withdraw(p.getUniqueId(), s.comp.cost)) {
            api.msg(p, false, "Nu ai destui bani pentru piesa (" + money(s.comp.cost) + ").");
            return;
        }
        Items.give(p, Items.make(api.plugin(), Material.IRON_INGOT, "§b" + s.comp.part, Items.lore("§7Piesa pentru " + s.car, "§8Comanda " + s.orderId), "fj_part", s.orderId));
        s.stage = 2;
        Fx.sound(p, "ENTITY_ITEM_PICKUP", 0.8f, 0.9f);
        api.gps(p, s.bay);
        api.toast(p, jd.name, "Ai cumparat " + s.comp.part + ". Mergi la box si monteaza-o.");
        api.refreshPanel(p);
    }

    private void swap(Player p, FJobDef jd, St s) {
        if (!Items.has(api.plugin(), p, "fj_part", s.orderId)) {
            Fx.bar(p, "§cNu ai piesa comenzii la tine.");
            s.stage = 1;
            return;
        }
        Diff d = diff(p, jd);
        Challenges.Spec sp;
        if ("wires".equals(s.comp.game)) sp = Challenges.wires(api.rnd(), d.size, "Montaj - " + s.comp.part, "Reconecteaza cablajul");
        else sp = Challenges.seq(api.rnd(), d.size + 2, Math.max(1500, 3300L - d.size * 200L), "Montaj - " + s.comp.part, "Memoreaza ordinea de strangere si reproduc-o");
        sp.attempts = d.attempts;
        sp.limitMs = d.seconds * 1000L;
        s.stage = 3;
        s.mg = true;
        if (!api.challenges().start(p, sp, (pl, ok, q) -> finish(pl, jd, ok, q, s.comp.pay, s.comp.xp, s.comp.cost))) {
            s.mg = false;
            s.stage = 2;
        }
    }

    private void finish(Player p, FJobDef jd, boolean ok, double q, double pay, long xp, double cost) {
        St s = state(p);
        s.mg = false;
        if (s.bay == null) return;
        Diff d = diff(p, jd);
        if (!ok) {
            api.toast(p, jd.name, "Reparatie esuata" + (cost > 0 ? ": piesa e stricata." : "."));
            api.addCounter(p, "mechanic.failed", 1);
            clear(p, s, 6000);
            api.refreshPanel(p);
            return;
        }
        if (cost > 0) Items.removeOne(api.plugin(), p, "fj_part", s.orderId);
        double got = payQ(p, jd, pay, xp, d, q, true);
        s.done++;
        api.addCounter(p, "mechanic.done", 1);
        api.toast(p, "Masina gata: " + s.car, "+" + money(Math.max(0, got)) + (cost > 0 ? " (piesa " + money(cost) + " dedusa la cumparare)" : ""));
        Fx.sound(p, "BLOCK_ANVIL_USE", 0.6f, 1.3f);
        clear(p, s, (long) (jd.cd("order-delay-seconds", 6) * 1000));
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.bay == null) {
            lines.add("Se asteapta o masina...");
            lines.add("Terminate in tura: " + s.done);
            return -1;
        }
        lines.add(s.car);
        String[] st = {"Diagnostic la box", "Cumpara piesa: " + (s.comp == null ? "" : s.comp.part), "Monteaza piesa la box", "Reparatie"};
        lines.add(s.comp == null ? "Service: sorteaza piesele" : st[Math.min(3, s.stage)]);
        lines.add("Box: " + s.bay.name + " (" + Math.round(dist(p, s.bay)) + " m)");
        return 1.0 - Math.min(1.0, (double) (System.currentTimeMillis() - s.assignedAt) / Math.max(1, jd.cd("order-timeout-seconds", 480) * 1000));
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.bay == null ? "Astepti o masina" : s.car);
        if (s.bay != null) {
            if (s.comp == null) {
                pn.obj("Service de rutina la " + s.bay.name + ": sorteaza piesele pe sisteme.");
            } else {
                pn.obj((s.stage > 0 ? "[x] " : "[ ] ") + "Diagnostic la box (" + s.bay.name + ")");
                pn.obj((s.stage > 1 ? "[x] " : "[ ] ") + "Cumpara piesa: " + s.comp.part + " (" + money(s.comp.cost) + ")" + (s.stage >= 1 ? " - defect: " + s.comp.name : ""));
                pn.obj((s.stage > 2 ? "[x] " : "[ ] ") + "Monteaza piesa la box");
            }
        } else {
            pn.obj("Porneste tura: primesti o masina pentru service.");
        }
        pn.stat("Masini reparate", String.valueOf(api.counter(p, "mechanic.done")));
        pn.stat("Esecuri", String.valueOf(api.counter(p, "mechanic.failed")));
        pn.stat("Boxuri setate", String.valueOf(pts(jd, "bay").size()));
        pn.btn("gps", s.stage == 1 && !pts(jd, "parts").isEmpty() ? "Navigheaza la piese" : "Navigheaza la box", s.bay != null);
        pn.btn("skip", "Alta masina", s.bay != null && !s.mg && s.stage < 2);
        int lvl = api.jobLevel(p, jd.id);
        for (Comp c : comps(jd).values()) pn.reward(c.name + ": " + money(c.pay) + " (piesa " + money(c.cost) + ")" + (c.minLevel > lvl ? "  [nivel " + c.minLevel + "]" : ""));
        pn.reward("Service: " + money(jd.cd("service-pay", 35)));
        pn.tip("Piesa se cumpara din banii tai si se pierde daca montajul esueaza.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        St s = state(p);
        if ("gps".equals(act) && s.bay != null) {
            if (s.stage == 1 && !pts(jd, "parts").isEmpty()) api.gps(p, pts(jd, "parts").get(0));
            else api.gps(p, s.bay);
            return true;
        }
        if ("skip".equals(act) && s.bay != null && !s.mg && s.stage < 2) {
            if (cooling(p, "skip", 45_000)) {
                api.msg(p, false, "Poti schimba masina o data la 45 s.");
                return false;
            }
            clear(p, s, 1500);
            return true;
        }
        return false;
    }
}
