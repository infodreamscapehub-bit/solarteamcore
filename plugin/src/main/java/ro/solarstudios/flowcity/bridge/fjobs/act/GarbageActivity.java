package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * GUNOIER: o ruta = grup de containere ("bin") cu acelasi prefix ("Ruta 1 - Str. Florilor"). La fiecare container iei sacii cu click
 * dreapta (cativa saci per container); containerele grele (marcate) se ridica tinand SHIFT cateva secunde. Cat porti saci esti
 * incetinit. Sacii se incarca in camion (punctul "truck", click dreapta); cand camionul e plin sau ruta e terminata il descarci la
 * depozit (punctul "depot", click dreapta). Plata: per sac + bonus de ruta completa, dupa timp.
 */
public final class GarbageActivity extends BaseActivity<GarbageActivity.St> {
    static final class Bin {
        FJobDef.Point pt;
        int bags;
        boolean heavy;
        int total;
    }

    static final class St {
        String route;
        final List<Bin> bins = new ArrayList<>();
        int carried;
        int truck;
        int collected;
        int totalBags;
        int delivered;
        long startedAt;
        long nextAt;
        long lastTake;
        Bin lifting;
        long liftStart;
        int routesDone;
        String lastRoute = "";
    }

    public GarbageActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "garbage";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"bin", "Containere de gunoi (prefix = ruta)"});
        l.add(new String[] {"truck", "Punct de incarcare camion"});
        l.add(new String[] {"depot", "Depozit (descarcare)"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("bin").isEmpty()) return "seteaza containerele de gunoi (puncte 'bin', prefix = numele rutei).";
        if (jd.stationList("truck").isEmpty()) return "seteaza punctul de incarcare al camionului ('truck').";
        if (jd.stationList("depot").isEmpty()) return "seteaza depozitul ('depot').";
        return null;
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
    protected void onEnd(Player p, FJobDef jd, St s) {
        p.removePotionEffect(PotionEffectType.SLOW);
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) p.removePotionEffect(PotionEffectType.SLOW);
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.route != null;
    }

    private void assign(Player p, FJobDef jd, St s, long now) {
        Map<String, List<FJobDef.Point>> routes = new LinkedHashMap<>();
        for (FJobDef.Point b : jd.stationList("bin")) routes.computeIfAbsent(group(b), k -> new ArrayList<>()).add(b);
        if (routes.isEmpty()) return;
        List<String> names = new ArrayList<>(routes.keySet());
        if (names.size() > 1) names.remove(s.lastRoute);
        s.route = names.get(api.rnd().nextInt(names.size()));
        s.lastRoute = s.route;
        Diff d = diff(p, jd);
        s.bins.clear();
        s.totalBags = 0;
        int maxBags = Math.max(1, (int) jd.cd("bags-per-bin", 2) + (d.size > 4 ? 1 : 0));
        double heavyChance = jd.cd("heavy-chance", 0.2) * (d.size > 3 ? 1.5 : 1);
        for (FJobDef.Point pt : routes.get(s.route)) {
            Bin b = new Bin();
            b.pt = pt;
            b.heavy = api.rnd().nextDouble() < heavyChance;
            b.bags = b.heavy ? 3 : 1 + api.rnd().nextInt(maxBags);
            b.total = b.bags;
            s.totalBags += b.bags;
            s.bins.add(b);
        }
        s.carried = 0;
        s.truck = 0;
        s.collected = 0;
        s.delivered = 0;
        s.startedAt = now;
        gpsNext(p, s);
        api.toast(p, jd.name, "Ruta: " + s.route + " (" + s.bins.size() + " containere, " + s.totalBags + " saci).");
        api.refreshPanel(p);
    }

    private void gpsNext(Player p, St s) {
        Bin best = null;
        double bd = Double.MAX_VALUE;
        for (Bin b : s.bins) {
            if (b.bags <= 0) continue;
            double d = dist(p, b.pt);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best != null) api.gps(p, best.pt);
    }

    private int remaining(St s) {
        int n = 0;
        for (Bin b : s.bins) n += b.bags;
        return n;
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        if (s.route == null) {
            if (now >= s.nextAt) assign(p, jd, s, now);
            return;
        }
        if (s.carried >= 2 && jd.cb("slow-when-carrying", true)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 50, s.carried >= 4 ? 1 : 0, false, false, false));
        }
        List<FJobDef.Point> binPts = new ArrayList<>();
        for (Bin b : s.bins) if (b.bags > 0) binPts.add(b.pt);
        mark(p, binPts, "VILLAGER_HAPPY", 40);
        mark(p, pts(jd, "truck"), "END_ROD", 50);
        if (s.lifting != null) {
            if (!p.isSneaking() || !near(p, s.lifting.pt, jd.cd("bin-radius", 3.5))) {
                Fx.bar(p, "§cRidicare anulata.");
                s.lifting = null;
            } else {
                double need = jd.cd("lift-seconds", 3) * 1000;
                double f = (now - s.liftStart) / need;
                Fx.bar(p, "§eSe ridica containerul... " + (int) Math.min(100, f * 100) + "%");
                Fx.sound(p, "BLOCK_CHAIN_PLACE", 0.3f, 0.8f);
                if (f >= 1) {
                    Bin b = s.lifting;
                    s.lifting = null;
                    take(p, jd, s, b, b.bags);
                }
            }
        }
        if (remaining(s) == 0 && s.carried == 0 && s.truck > 0 && s.delivered < s.collected) {
            // totul e adunat: spre depozit
        }
    }

    private void take(Player p, FJobDef jd, St s, Bin b, int n) {
        n = Math.min(n, b.bags);
        if (n <= 0) return;
        b.bags -= n;
        s.carried += n;
        s.collected += n;
        Fx.sound(p, "BLOCK_WOOL_PLACE", 0.8f, 0.9f);
        Fx.particle(p, "CLOUD", new Location(p.getWorld(), b.pt.x, b.pt.y + 1, b.pt.z), 6, 0.3);
        if (b.bags == 0) api.gps(p, firstTruck(jd, p));
    }

    private FJobDef.Point firstTruck(FJobDef jd, Player p) {
        FJobDef.Point best = null;
        double bd = Double.MAX_VALUE;
        for (FJobDef.Point t : pts(jd, "truck")) {
            double d = dist(p, t);
            if (d < bd) {
                bd = d;
                best = t;
            }
        }
        return best == null ? pts(jd, "truck").get(0) : best;
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        St s = state(p);
        if (s.route == null) return;
        long now = System.currentTimeMillis();
        double br = jd.cd("bin-radius", 3.5);
        double tr = jd.cd("truck-radius", 6);
        double dr = jd.cd("depot-radius", 6);
        FJobDef.Point depot = nearest(p, pts(jd, "depot"), dr);
        if (depot != null) {
            if (s.truck > 0) {
                e.setCancelled(true);
                if (cooling(p, "unload", 1500)) return;
                unload(p, jd, s, now);
            }
            return;
        }
        FJobDef.Point truck = nearest(p, pts(jd, "truck"), tr);
        if (truck != null && s.carried > 0) {
            e.setCancelled(true);
            if (cooling(p, "load", 700)) return;
            int cap = (int) jd.cd("truck-capacity", 18);
            int room = cap - s.truck;
            if (room <= 0) {
                Fx.bar(p, "§cCamionul e plin. Du-l la depozit.");
                return;
            }
            int n = Math.min(room, s.carried);
            s.carried -= n;
            s.truck += n;
            Fx.sound(p, "BLOCK_PISTON_EXTEND", 0.6f, 1.2f);
            Fx.particle(p, "CLOUD", p.getLocation().add(0, 1, 0), 8, 0.4);
            Fx.bar(p, "§aAi incarcat " + n + " saci. Camion: " + s.truck + "/" + cap);
            if (s.truck >= cap || (remaining(s) == 0 && s.carried == 0)) {
                api.toast(p, jd.name, "Mergi la depozit si descarca (click dreapta).");
                FJobDef.Point dp = pts(jd, "depot").get(0);
                api.gps(p, dp);
            }
            return;
        }
        for (Bin b : s.bins) {
            if (b.bags <= 0 || !nearLoc(e.getClickedBlock() == null ? p.getLocation() : e.getClickedBlock().getLocation(), b.pt, br)) continue;
            e.setCancelled(true);
            int cap = (int) jd.cd("carry-capacity", 4);
            if (s.carried >= cap) {
                Fx.bar(p, "§cNu mai poti duce saci. Du-i la camion.");
                return;
            }
            if (b.heavy) {
                if (!p.isSneaking()) {
                    Fx.bar(p, "§eContainer greu: tine SHIFT apasat ca sa-l ridici.");
                    return;
                }
                if (s.carried + b.bags > cap) {
                    Fx.bar(p, "§cContainerul greu are " + b.bags + " saci; mai intai incarca ce duci.");
                    return;
                }
                if (s.lifting == null) {
                    s.lifting = b;
                    s.liftStart = now;
                }
                return;
            }
            if (now - s.lastTake < jd.cd("take-cooldown-ms", 600)) return;
            s.lastTake = now;
            take(p, jd, s, b, 1);
            return;
        }
    }

    private void unload(Player p, FJobDef jd, St s, long now) {
        Diff d = diff(p, jd);
        int n = s.truck;
        s.truck = 0;
        s.delivered += n;
        long el = now - s.startedAt;
        boolean routeDone = remaining(s) == 0 && s.carried == 0 && s.delivered >= s.collected && s.collected > 0;
        double par = s.totalBags * jd.cd("par-seconds-per-bag", 9) * 1000 + 30_000;
        double perf = routeDone ? Math.max(0.7, Math.min(1.3, par / Math.max(1, el))) : 1.0;
        double pay = payQ(p, jd, n * jd.cd("bag-pay", 3.0), Math.round(n * jd.cd("bag-xp", 1.0)), d, 1.0, false);
        double bonus = 0;
        if (routeDone) {
            bonus = payQ(p, jd, jd.cd("route-bonus", 25), Math.round(jd.cd("route-xp", 10)), d, perf, true);
            s.routesDone++;
            api.addCounter(p, "garbage.routes", 1);
        }
        api.addCounter(p, "garbage.bags", n);
        Fx.sound(p, "BLOCK_PISTON_CONTRACT", 0.7f, 0.9f);
        api.toast(p, "Descarcat: " + n + " saci", "+" + money(Math.max(0, pay)) + (routeDone ? " + bonus ruta " + money(Math.max(0, bonus)) : ""));
        if (routeDone) {
            s.route = null;
            s.nextAt = now + 5000;
            p.removePotionEffect(PotionEffectType.SLOW);
        } else {
            gpsNext(p, s);
        }
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.route == null) {
            lines.add("Se alege o ruta...");
            return -1;
        }
        int cap = (int) jd.cd("truck-capacity", 18);
        lines.add("Ruta: " + s.route);
        lines.add("Saci adunati: " + s.collected + "/" + s.totalBags);
        lines.add("In mana: " + s.carried + "/" + (int) jd.cd("carry-capacity", 4) + "   Camion: " + s.truck + "/" + cap);
        if (remaining(s) == 0 && s.carried == 0 && s.truck > 0) lines.add("Mergi la depozit!");
        return s.totalBags == 0 ? 1 : (double) s.delivered / s.totalBags;
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.route == null ? "Astepti o ruta" : "Ruta: " + s.route);
        if (s.route != null) {
            pn.obj("Strange sacii de la fiecare container (click dreapta).");
            pn.obj("Containerele grele: tine SHIFT cateva secunde.");
            pn.obj("Incarca in camion, apoi descarca la depozit.");
            pn.obj("Ramase: " + remaining(s) + " saci | in mana " + s.carried + " | camion " + s.truck);
        } else {
            pn.obj("Porneste tura pentru a primi o ruta predefinita.");
        }
        pn.stat("Rute terminate", String.valueOf(api.counter(p, "garbage.routes")));
        pn.stat("Saci colectati", String.valueOf(api.counter(p, "garbage.bags")));
        pn.stat("Rute setate", String.valueOf(routeCount(jd)));
        pn.btn("gps", "Navigheaza la urmatorul punct", s.route != null);
        pn.btn("skip", "Alta ruta", s.route != null && s.collected == 0);
        pn.reward("Per sac: " + money(jd.cd("bag-pay", 3.0)) + "  |  bonus ruta: " + money(jd.cd("route-bonus", 25)) + " (dupa timp)");
        pn.tip("Cu 2+ saci in mana esti incetinit; incarca des camionul.");
    }

    private int routeCount(FJobDef jd) {
        java.util.Set<String> g = new java.util.HashSet<>();
        for (FJobDef.Point b : jd.stationList("bin")) g.add(group(b));
        return g.size();
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, com.google.gson.JsonObject j) {
        St s = state(p);
        if ("gps".equals(act) && s.route != null) {
            if (s.carried > 0 || remaining(s) == 0) api.gps(p, firstTruck(jd, p));
            else gpsNext(p, s);
            return true;
        }
        if ("skip".equals(act) && s.route != null && s.collected == 0) {
            if (cooling(p, "skip", 45_000)) {
                api.msg(p, false, "Poti schimba ruta o data la 45 s.");
                return false;
            }
            s.route = null;
            s.nextAt = System.currentTimeMillis() + 1500;
            return true;
        }
        return false;
    }
}
