package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * ELECTRICIAN: primesti comenzi la tablourile electrice (puncte "panel" setate de admin). La tablou: 1) DIAGNOSTIC (mini-joc "pick":
 * citesti masuratorile si identifici defectul), 2) REPARATIE, diferita dupa defect: siguranta arsa -> alegi amperajul corect (pick),
 * cablu desprins -> conectezi firele (wires), suprasarcina -> resetezi intrerupatoarele in ordine (seq), instalatie noua -> circuit
 * complet (wires mai mare). Dificultatea (usor/mediu/greu) schimba numarul de fire, secventa, timpul, incercarile si plata.
 */
public final class ElectricianActivity extends BaseActivity<ElectricianActivity.St> {
    static final class Fault {
        String id;
        String name;
        double pay;
        long xp;
        int minLevel;
        String symptom;
    }

    static final class St {
        FJobDef.Point panel;
        Fault fault;
        int stage;          // 0 = deplasare, 1 = diagnostic, 2 = reparatie
        long assignedAt;
        long nextAt;        // cand se poate da o comanda noua
        final Map<String, Long> lock = new HashMap<>();
        int doneShift;
        boolean mg;
    }

    public ElectricianActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "electrician";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"panel", "Tablouri electrice"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        return jd.stationList("panel").isEmpty() ? "adminul trebuie sa seteze cel putin un tablou electric (puncte 'panel')." : null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    private Map<String, Fault> faults(FJobDef jd) {
        Map<String, Fault> m = new LinkedHashMap<>();
        ConfigurationSection s = jd.conf == null ? null : jd.conf.getConfigurationSection("faults");
        String[][] def = {{"fuse", "Siguranta arsa", "30", "10", "0", "Tabloul nu mai da curent; indicatorul F2 e stins."},
            {"wire", "Cablu desprins", "40", "14", "0", "Tensiune instabila; un fir atarna in dreptul clemei."},
            {"overload", "Suprasarcina", "55", "18", "3", "Intrerupatorul general declanseaza la scurt timp dupa resetare."},
            {"install", "Circuit nou de conectat", "80", "26", "6", "Circuit nou, neracordat; trebuie conectate toate liniile."}};
        if (s != null && !s.getKeys(false).isEmpty()) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) continue;
                Fault f = new Fault();
                f.id = id;
                f.name = c.getString("name", id);
                f.pay = Math.max(0, c.getDouble("pay", 30));
                f.xp = Math.max(0, c.getLong("xp", 10));
                f.minLevel = Math.max(0, c.getInt("min-level", 0));
                f.symptom = c.getString("symptom", "Tabloul are o problema.");
                m.put(id, f);
            }
        } else {
            for (String[] d : def) {
                Fault f = new Fault();
                f.id = d[0];
                f.name = d[1];
                f.pay = Double.parseDouble(d[2]);
                f.xp = Long.parseLong(d[3]);
                f.minLevel = Integer.parseInt(d[4]);
                f.symptom = d[5];
                m.put(f.id, f);
            }
        }
        return m;
    }

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        s.nextAt = System.currentTimeMillis() + 2000;
        Fx.title(p, "§e⚡ " + jd.name, "§7Asteapta prima comanda...");
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.panel != null;
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        if (s.panel == null) {
            if (now >= s.nextAt) assign(p, jd, s, now);
            return;
        }
        long timeout = (long) (jd.cd("order-timeout-seconds", 360) * 1000);
        if (now - s.assignedAt > timeout && !s.mg) {
            api.toast(p, jd.name, "Comanda a expirat.");
            clear(s, now, 5000);
            return;
        }
        if (!s.mg) mark(p, Collections.singletonList(s.panel), "END_ROD", 48);
    }

    private void clear(St s, long now, long delay) {
        s.panel = null;
        s.fault = null;
        s.stage = 0;
        s.mg = false;
        s.nextAt = now + delay;
    }

    private void assign(Player p, FJobDef jd, St s, long now) {
        int lvl = api.jobLevel(p, jd.id);
        List<FJobDef.Point> panels = new ArrayList<>();
        for (FJobDef.Point pt : pts(jd, "panel")) {
            Long lk = s.lock.get(pt.name);
            if (lk == null || lk < now) panels.add(pt);
        }
        if (panels.isEmpty()) return;
        List<Fault> fs = new ArrayList<>();
        for (Fault f : faults(jd).values()) if (f.minLevel <= lvl) fs.add(f);
        if (fs.isEmpty()) return;
        s.panel = panels.get(api.rnd().nextInt(panels.size()));
        s.fault = fs.get(api.rnd().nextInt(fs.size()));
        s.stage = 0;
        s.assignedAt = now;
        api.gps(p, s.panel);
        api.toast(p, jd.name, "Comanda noua: " + s.panel.name + ". Mergi la tablou si da click dreapta pe el.");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_BELL", 0.8f, 1.2f);
        api.refreshPanel(p);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        St s = state(p);
        if (s.panel == null || s.mg || api.challenges().has(p)) return;
        if (!nearLoc(e.getClickedBlock().getLocation(), s.panel, jd.cd("interact-radius", 3.5))) return;
        e.setCancelled(true);
        if (cooling(p, "open", 1500)) return;
        diagnose(p, jd, s);
    }

    private void diagnose(Player p, FJobDef jd, St s) {
        Diff d = diff(p, jd);
        Map<String, Fault> all = faults(jd);
        List<Fault> opts = new ArrayList<>(all.values());
        Collections.shuffle(opts, api.rnd());
        int n = Math.max(3, Math.min(opts.size(), d.size + 1));
        List<Fault> shown = new ArrayList<>();
        shown.add(s.fault);
        for (Fault f : opts) if (shown.size() < n && f != s.fault) shown.add(f);
        Collections.shuffle(shown, api.rnd());
        List<String> names = new ArrayList<>();
        for (Fault f : shown) names.add(f.name);
        Challenges.Spec sp = Challenges.pick("Diagnostic - " + s.panel.name, "Citeste masuratorile si identifica defectul", "Masuratori: " + s.fault.symptom + " Care e defectul?",
            names, shown.indexOf(s.fault));
        sp.attempts = d.attempts;
        sp.limitMs = d.seconds * 1000L;
        s.mg = true;
        s.stage = 1;
        if (!api.challenges().start(p, sp, (pl, ok, q) -> afterDiagnose(pl, jd, ok, q))) s.mg = false;
    }

    private void afterDiagnose(Player p, FJobDef jd, boolean ok, double q) {
        St s = state(p);
        long now = System.currentTimeMillis();
        if (s.panel == null) return;
        if (!ok) {
            fail(p, jd, s, "Diagnostic gresit. Tabloul a fost blocat un timp.");
            return;
        }
        Diff d = diff(p, jd);
        s.stage = 2;
        Challenges.Spec sp;
        String id = s.fault.id;
        if ("fuse".equals(id)) {
            int[] amps = {6, 10, 16, 20, 25, 32};
            int correct = api.rnd().nextInt(amps.length);
            List<String> o = new ArrayList<>();
            for (int a : amps) o.add(a + " A");
            sp = Challenges.pick("Reparatie - siguranta", "Inlocuieste siguranta", "Circuitul are sarcina de " + amps[correct] + " A (limita nominala). Ce siguranta montezi?", o, correct);
        } else if ("overload".equals(id)) {
            sp = Challenges.seq(api.rnd(), d.size + 2, Math.max(1500, 3400L - d.size * 220L), "Reparatie - suprasarcina", "Memoreaza ordinea, apoi reseteaza intrerupatoarele");
        } else if ("install".equals(id)) {
            sp = Challenges.wires(api.rnd(), d.size + 2, "Instalare circuit", "Leaga fiecare faza de borna cu aceeasi culoare");
        } else {
            sp = Challenges.wires(api.rnd(), d.size, "Reparatie - cablaj", "Leaga firele de aceeasi culoare");
        }
        sp.attempts = d.attempts;
        sp.limitMs = d.seconds * 1000L;
        final double diagQ = q;
        if (!api.challenges().start(p, sp, (pl, ok2, q2) -> afterRepair(pl, jd, ok2, (diagQ + q2) / 2.0))) {
            s.mg = false;
        }
    }

    private void afterRepair(Player p, FJobDef jd, boolean ok, double q) {
        St s = state(p);
        long now = System.currentTimeMillis();
        if (s.panel == null || s.fault == null) return;
        if (!ok) {
            fail(p, jd, s, "Reparatia a esuat. Tabloul e blocat un timp.");
            return;
        }
        Diff d = diff(p, jd);
        double streakBonus = Math.min(0.25, s.doneShift * 0.03);
        double pay = payQ(p, jd, s.fault.pay * (1 + streakBonus), s.fault.xp, d, q, true);
        s.doneShift++;
        api.addCounter(p, "electrician.done", 1);
        api.addCounter(p, "electrician.fault." + s.fault.id, 1);
        Fx.particle(p, "FIREWORKS_SPARK", p.getLocation().add(0, 1.5, 0), 25, 0.5);
        Fx.sound(p, "ENTITY_FIREWORK_ROCKET_BLAST", 0.7f, 1.2f);
        api.toast(p, "Reparat: " + s.fault.name, pay >= 0 ? "+" + money(pay) + " (calitate " + Math.round(q * 100) + "%)" : "Limita de castig atinsa.");
        s.lock.put(s.panel.name, now + (long) (jd.cd("panel-cooldown-seconds", 90) * 1000));
        clear(s, now, (long) (jd.cd("order-delay-seconds", 6) * 1000));
        api.refreshPanel(p);
    }

    private void fail(Player p, FJobDef jd, St s, String why) {
        long now = System.currentTimeMillis();
        api.addCounter(p, "electrician.failed", 1);
        api.toast(p, jd.name, why);
        if (s.panel != null) s.lock.put(s.panel.name, now + (long) (jd.cd("fail-lock-seconds", 120) * 1000));
        s.doneShift = 0;
        clear(s, now, 8000);
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.panel == null) {
            lines.add("Se asteapta o comanda...");
            lines.add("Comenzi in tura: " + s.doneShift);
            return -1;
        }
        lines.add("Tablou: " + s.panel.name);
        lines.add(s.stage == 0 ? "Mergi la tablou (" + Math.round(dist(p, s.panel)) + " m)" : s.stage == 1 ? "Diagnostic" : "Reparatie: " + s.fault.name);
        lines.add("Comenzi in tura: " + s.doneShift);
        long timeout = (long) (jd.cd("order-timeout-seconds", 360) * 1000);
        return 1.0 - Math.min(1.0, (double) (System.currentTimeMillis() - s.assignedAt) / Math.max(1, timeout));
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.panel == null ? "Astepti o comanda" : "Comanda activa: " + s.panel.name);
        if (s.panel != null) {
            pn.obj("Mergi la tabloul " + s.panel.name + " si da click dreapta pe el.");
            pn.obj("Diagnosticheaza defectul, apoi repara-l cu mini-jocul potrivit.");
        } else {
            pn.obj("Porneste tura si asteapta o comanda de interventie.");
        }
        pn.stat("Interventii reusite", String.valueOf(api.counter(p, "electrician.done")));
        pn.stat("Esecuri", String.valueOf(api.counter(p, "electrician.failed")));
        pn.stat("Serie in tura", String.valueOf(s.doneShift));
        pn.stat("Tablouri setate", String.valueOf(pts(jd, "panel").size()));
        pn.btn("gps", "Navigheaza la tablou", s.panel != null);
        pn.btn("skip", "Schimba comanda", s.panel != null && !s.mg);
        int lvl = api.jobLevel(p, jd.id);
        for (Fault f : faults(jd).values()) pn.reward(f.name + ": " + money(f.pay) + " / " + f.xp + " XP" + (f.minLevel > lvl ? "  (nivel " + f.minLevel + ")" : ""));
        pn.reward("Bonus de serie: +3% per interventie consecutiva (max 25%)");
        pn.tip("Diagnosticul gresit blocheaza tabloul pentru un timp.");
        pn.tip("Calitatea depinde de timp si de numarul de greseli.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, com.google.gson.JsonObject j) {
        St s = state(p);
        long now = System.currentTimeMillis();
        if ("gps".equals(act) && s.panel != null) {
            api.gps(p, s.panel);
            return true;
        }
        if ("skip".equals(act) && s.panel != null && !s.mg) {
            if (cooling(p, "skip", 60_000)) {
                api.msg(p, false, "Poti schimba comanda o data pe minut.");
                return false;
            }
            clear(s, now, 2000);
            return true;
        }
        return false;
    }
}
