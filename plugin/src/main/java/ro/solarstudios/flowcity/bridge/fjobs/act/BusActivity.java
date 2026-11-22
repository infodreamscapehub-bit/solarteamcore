package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * SOFER AUTOBUZ: o linie = statiile ("stop") cu acelasi prefix, in ordinea setarii ("Linia 1 - Piata", "Linia 1 - Gara"...). Pornesti de
 * la prima statie, apoi le servesti IN ORDINE: opresti in raza statiei (viteza ~0) cateva secunde; pasagerii coboara/urca (numar aleator,
 * plata per pasager urcat). Nu poti servi o statie din fata celei urmatoare (traseu respectat). Orarul: fiecare statie are un moment-tinta
 * calculat din distanta; ajungi la timp (+/- toleranta) pentru bonus de orar; daca ajungi mai devreme, astepti pana se deschide fereastra.
 * Linia terminata da bonus de finalizare (mai mare fara statii sarite). Ca la taxi, cerinta de vehicul e optionala (require-vehicle).
 */
public final class BusActivity extends BaseActivity<BusActivity.St> {
    static final class St {
        String line;
        List<FJobDef.Point> stops = new ArrayList<>();
        long[] target;           // ms de la plecare
        int next;
        boolean started;
        long departAt;
        int onboard;
        int boardedTotal;
        int missed;
        int onTime;
        int served;
        long dwellSince;
        Location last;
        double speed;
        long nextAt;
        int runsShift;
        String lastLine = "";
    }

    public BusActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "bus";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"stop", "Statii (prefix = linia, in ordinea traseului)"});
        return l;
    }

    private Map<String, List<FJobDef.Point>> lines(FJobDef jd) {
        Map<String, List<FJobDef.Point>> m = new LinkedHashMap<>();
        for (FJobDef.Point p : jd.stationList("stop")) m.computeIfAbsent(group(p), k -> new ArrayList<>()).add(p);
        m.values().removeIf(l -> l.size() < 2);
        return m;
    }

    @Override
    public String missing(FJobDef jd) {
        return lines(jd).isEmpty() ? "seteaza o linie cu cel putin 2 statii ('stop', acelasi prefix, ex. 'Linia 1 - Piata', 'Linia 1 - Gara')." : null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        s.nextAt = System.currentTimeMillis() + 1500;
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.line != null;
    }

    private void assign(Player p, FJobDef jd, St s, long now) {
        Map<String, List<FJobDef.Point>> ls = lines(jd);
        if (ls.isEmpty()) return;
        List<String> names = new ArrayList<>(ls.keySet());
        if (names.size() > 1) names.remove(s.lastLine);
        s.line = names.get(api.rnd().nextInt(names.size()));
        s.lastLine = s.line;
        s.stops = new ArrayList<>(ls.get(s.line));
        s.target = new long[s.stops.size()];
        double sp = Math.max(3, jd.cd("scheduled-speed", 9));
        double dwell = jd.cd("dwell-seconds", 3) + 4;
        long t = 0;
        for (int i = 1; i < s.stops.size(); i++) {
            t += (long) ((s.stops.get(i - 1).dist(s.stops.get(i)) / sp + dwell) * 1000);
            s.target[i] = t;
        }
        s.next = 0;
        s.started = false;
        s.onboard = 0;
        s.boardedTotal = 0;
        s.missed = 0;
        s.onTime = 0;
        s.served = 0;
        s.dwellSince = 0;
        api.gps(p, s.stops.get(0));
        api.toast(p, jd.name, s.line + ": mergi la capatul de linie (" + s.stops.get(0).name + ").");
        api.refreshPanel(p);
    }

    private boolean vehicleOk(Player p, FJobDef jd) {
        return !jd.cb("require-vehicle", false) || p.isInsideVehicle();
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        Location cur = p.getLocation();
        if (s.last != null && s.last.getWorld() == cur.getWorld()) {
            double dx = cur.getX() - s.last.getX();
            double dz = cur.getZ() - s.last.getZ();
            s.speed = Math.sqrt(dx * dx + dz * dz);
        }
        s.last = cur;
        if (s.line == null) {
            if (now >= s.nextAt) assign(p, jd, s, now);
            return;
        }
        FJobDef.Point tg = s.stops.get(s.next);
        List<FJobDef.Point> rest = new ArrayList<>(s.stops.subList(s.next, s.stops.size()));
        mark(p, rest, "END_ROD", 60);
        double r = jd.cd("stop-radius", 6);
        // statie din fata celei urmatoare
        for (int i = s.next + 1; i < s.stops.size(); i++) {
            if (near(p, s.stops.get(i), r) && s.speed < 1.0 && !cooling(p, "order", 8000)) {
                Fx.bar(p, "§cRespecta traseul: urmeaza statia " + tg.name + ".");
                s.missed++;
                break;
            }
        }
        if (!near(p, tg, r) || !vehicleOk(p, jd)) {
            s.dwellSince = 0;
            return;
        }
        if (s.speed > 1.5) {
            s.dwellSince = 0;
            Fx.bar(p, "§eOpreste autobuzul in statie.");
            return;
        }
        boolean opens = !s.started || now - s.departAt >= s.target[s.next] - (long) (jd.cd("grace-seconds", 15) * 1000);
        if (!opens) {
            Fx.bar(p, "§7Asteapta ora de plecare/sosire (" + time((s.target[s.next] - (now - s.departAt)) / 1000) + ").");
            s.dwellSince = 0;
            return;
        }
        if (s.dwellSince == 0) s.dwellSince = now;
        Fx.bar(p, "§aPasagerii urca/coboara...");
        if (now - s.dwellSince >= jd.cd("dwell-seconds", 3) * 1000) {
            serve(p, jd, s, now);
            s.dwellSince = 0;
        }
    }

    private void serve(Player p, FJobDef jd, St s, long now) {
        Diff d = diff(p, jd);
        FJobDef.Point tg = s.stops.get(s.next);
        boolean first = s.next == 0;
        boolean last = s.next == s.stops.size() - 1;
        int off = first ? 0 : (last ? s.onboard : api.rnd().nextInt(s.onboard + 1));
        s.onboard -= off;
        int cap = (int) jd.cd("capacity", 24);
        int on = last ? 0 : Math.min(cap - s.onboard, 1 + api.rnd().nextInt(2 + d.size));
        s.onboard += on;
        s.boardedTotal += on;
        s.served++;
        double pay = payQ(p, jd, on * jd.cd("fare", 2.0), Math.round(jd.cd("stop-xp", 2)), d, 1.0, false);
        String note = "";
        if (first) {
            s.started = true;
            s.departAt = now;
        } else {
            long dev = now - (s.departAt + s.target[s.next]);
            if (Math.abs(dev) <= jd.cd("grace-seconds", 15) * 1000) {
                s.onTime++;
                double b = payQ(p, jd, jd.cd("schedule-bonus", 8), 0, d, 1.0, false);
                note = " + orar respectat";
                if (b < 0) note = "";
            } else {
                note = dev > 0 ? " (intarziat)" : "";
            }
        }
        api.addCounter(p, "bus.passengers", on);
        api.addCounter(p, "bus.stops", 1);
        Fx.sound(p, "BLOCK_NOTE_BLOCK_CHIME", 0.7f, 1.0f);
        api.toast(p, tg.name, "Coborati " + off + ", urcati " + on + " (+" + money(Math.max(0, pay)) + ")" + note);
        s.next++;
        if (s.next >= s.stops.size()) {
            double perfect = s.missed == 0 ? 1.5 : 1.0;
            double ratio = s.stops.size() <= 1 ? 1 : (double) s.onTime / (s.stops.size() - 1);
            double bonus = payQ(p, jd, jd.cd("complete-bonus", 30) * perfect, Math.round(jd.cd("complete-xp", 12)), d, 0.6 + 0.4 * ratio, true);
            s.runsShift++;
            api.addCounter(p, "bus.runs", 1);
            api.toast(p, "Linie terminata", s.line + ": bonus " + money(Math.max(0, bonus)) + ", la timp " + s.onTime + "/" + (s.stops.size() - 1));
            s.line = null;
            s.nextAt = now + 5000;
        } else {
            api.gps(p, s.stops.get(s.next));
        }
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.line == null) {
            lines.add("Se alege o linie...");
            return -1;
        }
        FJobDef.Point tg = s.stops.get(s.next);
        lines.add(s.line + (s.started ? "" : " (inainte de plecare)"));
        lines.add("Urmeaza: " + tg.name + " (" + Math.round(dist(p, tg)) + " m)");
        lines.add("Pasageri: " + s.onboard + "  |  Statii " + s.served + "/" + s.stops.size());
        if (s.started) {
            long dev = (System.currentTimeMillis() - s.departAt) - s.target[s.next];
            lines.add(dev > 0 ? "Intarziere: " + time(dev / 1000) : "In avans: " + time(-dev / 1000));
        }
        return (double) s.served / s.stops.size();
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.line == null ? "Astepti o linie" : s.line);
        if (s.line != null) {
            for (int i = 0; i < s.stops.size(); i++) {
                String mark = i < s.next ? "[x] " : i == s.next ? "[>] " : "[ ] ";
                pn.obj(mark + s.stops.get(i).name + (s.started && i > 0 ? "  (" + time(s.target[i] / 1000) + ")" : ""));
            }
        } else {
            pn.obj("Porneste tura pentru a primi o linie predefinita.");
        }
        pn.stat("Linii terminate", String.valueOf(api.counter(p, "bus.runs")));
        pn.stat("Pasageri transportati", String.valueOf(api.counter(p, "bus.passengers")));
        pn.stat("Linii setate", String.valueOf(lines(jd).size()));
        pn.stat("Cerinta vehicul", jd.cb("require-vehicle", false) ? "da" : "nu");
        pn.btn("gps", "Navigheaza la statie", s.line != null);
        pn.btn("skip", "Alta linie", s.line != null && !s.started);
        pn.reward(money(jd.cd("fare", 2.0)) + " per pasager urcat, bonus de orar " + money(jd.cd("schedule-bonus", 8)) + " / statie");
        pn.reward("Bonus de linie " + money(jd.cd("complete-bonus", 30)) + " (x1.5 fara statii sarite)");
        pn.tip("Opreste in raza statiei; sosirea timpurie te face sa astepti fereastra orarului.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        St s = state(p);
        if ("gps".equals(act) && s.line != null) {
            api.gps(p, s.stops.get(s.next));
            return true;
        }
        if ("skip".equals(act) && s.line != null && !s.started) {
            s.line = null;
            s.nextAt = System.currentTimeMillis() + 1500;
            return true;
        }
        return false;
    }
}
