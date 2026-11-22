package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.StringTextComponent;

/**
 * Traseul GPS pe drumuri: A* pe reteaua {@link RoadNet}, recalculare cand iesi de pe traseu, manevre (viraje) cu nume de strada,
 * distanta/ETA ramase si anunturi sonore. Daca reteaua lipseste sau punctele nu se leaga de ea, traseul ramane in linie dreapta
 * (starea se vede in {@link #status} si in interfata: "direcție directă").
 */
public final class RoadRouteProvider implements RouteProvider {
    public static final RoadRouteProvider I = new RoadRouteProvider();

    public static final int ST_NONE = 0;
    public static final int ST_OK = 1;
    public static final int ST_NO_ROADS = 2;
    public static final int ST_FAR_START = 3;
    public static final int ST_FAR_END = 4;
    public static final int ST_UNREACHABLE = 5;

    /** O manevra: la distanta {@link #at} de la inceputul traseului. turn: -3..3 (negativ = stanga), 4 = intoarcere, 9 = sosire. */
    public static final class Step {
        public double at;
        public double x;
        public double z;
        public int turn;
        public String street = "";
        int announced;

        /** Forma scurta pentru HUD ("dreapta", "ușor stânga"...). */
        public String shortText() {
            switch (turn) {
                case -3:
                    return "strâns stânga";
                case -2:
                    return "stânga";
                case -1:
                    return "ușor stânga";
                case 1:
                    return "ușor dreapta";
                case 2:
                    return "dreapta";
                case 3:
                    return "strâns dreapta";
                case 4:
                    return "întoarcere";
                case 9:
                    return "sosire";
                default:
                    return "drept";
            }
        }

        /** Unghiul sagetii in grade (0 = in sus). */
        public double arrowDeg() {
            switch (turn) {
                case -3:
                    return -135;
                case -2:
                    return -90;
                case -1:
                    return -45;
                case 1:
                    return 45;
                case 2:
                    return 90;
                case 3:
                    return 135;
                case 4:
                    return 180;
                default:
                    return 0;
            }
        }

        public String text() {
            switch (turn) {
                case -3:
                    return "Virează strâns la stânga";
                case -2:
                    return "Virează la stânga";
                case -1:
                    return "Virează ușor la stânga";
                case 1:
                    return "Virează ușor la dreapta";
                case 2:
                    return "Virează la dreapta";
                case 3:
                    return "Virează strâns la dreapta";
                case 4:
                    return "Întoarce-te";
                case 9:
                    return "Ai ajuns la destinație";
                default:
                    return "Continuă drept";
            }
        }
    }

    private List<double[]> full = new ArrayList<>();
    private double[] cum = new double[0];
    private final List<Step> steps = new ArrayList<>();
    private List<double[]> shown = new ArrayList<>();
    private double total;
    private double along;
    private double offDist;
    private int lastSeg;
    private int status = ST_NONE;
    private int revisionSeen = -1;
    private long computedAt;
    private long offSince;
    private long lastCompute;
    private boolean computedOnce;
    private double startX;
    private double startZ;

    public int status() {
        return status;
    }

    public List<Step> steps() {
        return steps;
    }

    /** Traseul de desenat (de la pozitia ta, restul drumului pana la destinatie). Nu-l modifica. */
    @Override
    public List<double[]> path(double fromX, double fromZ, double toX, double toZ) {
        if (!GpsNav.active) return STRAIGHT.path(fromX, fromZ, toX, toZ);
        if (!computedOnce) compute(Minecraft.getInstance(), fromX, fromZ);
        if (status != ST_OK || shown.size() < 2) return STRAIGHT.path(fromX, fromZ, toX, toZ);
        return shown;
    }

    @Override
    public boolean roads() {
        return GpsNav.active && status == ST_OK && shown.size() >= 2;
    }

    /** Distanta ramasa pe drum (metri) sau -1 daca nu exista traseu pe drumuri. */
    public double remaining() {
        return roads() ? Math.max(0, total - along) : -1;
    }

    /** Distanta parcursa pe traseu pana la proiectia ta. */
    public double along() {
        return along;
    }

    public double offRoute() {
        return offDist;
    }

    /** Urmatoarea manevra (sau null) si distanta pana la ea in {@link #nextDist()}. */
    public Step next() {
        if (!roads()) return null;
        for (Step s : steps) if (s.at > along + 1.5) return s;
        return null;
    }

    public double nextDist() {
        Step s = next();
        return s == null ? -1 : Math.max(0, s.at - along);
    }

    /** Punct la `ahead` metri in fata pe traseu (pentru sageata de directie), {x, z} sau null. */
    public double[] lookahead(double ahead) {
        if (!roads() || full.size() < 2) return null;
        double target = Math.min(total, along + ahead);
        for (int i = 1; i < full.size(); i++) {
            if (cum[i] >= target) {
                double seg = cum[i] - cum[i - 1];
                double t = seg < 1e-6 ? 1 : (target - cum[i - 1]) / seg;
                double[] a = full.get(i - 1);
                double[] b = full.get(i);
                return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
        }
        double[] l = full.get(full.size() - 1);
        return new double[] {l[0], l[1]};
    }

    public String statusText() {
        switch (status) {
            case ST_OK:
                return "pe străzi";
            case ST_NO_ROADS:
                return "rețea de drumuri lipsă";
            case ST_FAR_START:
                return "ești departe de un drum";
            case ST_FAR_END:
                return "destinație fără drum";
            case ST_UNREACHABLE:
                return "fără legătură pe drum";
            default:
                return "direcție directă";
        }
    }

    public void reset() {
        full = new ArrayList<>();
        cum = new double[0];
        steps.clear();
        shown = new ArrayList<>();
        total = 0;
        along = 0;
        offDist = 0;
        lastSeg = 0;
        status = ST_NONE;
        computedOnce = false;
        offSince = 0;
        revisionSeen = -1;
    }

    // ------------------------------------------------------------------ calcul

    private void compute(Minecraft mc, double px, double pz) {
        computedOnce = true;
        lastCompute = System.currentTimeMillis();
        revisionSeen = RoadNet.revision;
        full = new ArrayList<>();
        steps.clear();
        shown = new ArrayList<>();
        total = 0;
        along = 0;
        lastSeg = 0;
        offSince = 0;
        offDist = 0;
        startX = px;
        startZ = pz;
        if (!GpsNav.active || !"minecraft:overworld".equals(GpsData.dimOf(mc)) || !GpsNav.dim.equals("minecraft:overworld")) {
            status = ST_NO_ROADS;
            return;
        }
        RoadNet.Graph g = RoadNet.graph();
        if (g == null) {
            status = ST_NO_ROADS;
            return;
        }
        double[] s = g.nearest(px, pz, 160);
        if (s == null) {
            status = ST_FAR_START;
            return;
        }
        double[] e = g.nearest(GpsNav.x, GpsNav.z, 260);
        if (e == null) {
            status = ST_FAR_END;
            return;
        }
        RoadNet.Found f = RoadNet.route(g, s, e);
        if (f == null) {
            status = ST_UNREACHABLE;
            return;
        }
        List<double[]> pts = new ArrayList<>();
        List<Integer> names = new ArrayList<>();
        pts.add(new double[] {px, pz});
        names.add(-1);
        double[] f0 = f.pts.get(0);
        if (Math.hypot(f0[0] - px, f0[1] - pz) > 0.6) {
            pts.add(f0);
            names.add(-1);
        }
        for (int i = 1; i < f.pts.size(); i++) {
            pts.add(f.pts.get(i));
            names.add(f.segName.get(i));
        }
        double[] fl = f.pts.get(f.pts.size() - 1);
        if (Math.hypot(GpsNav.x - fl[0], GpsNav.z - fl[1]) > 0.6) {
            pts.add(new double[] {GpsNav.x, GpsNav.z});
            names.add(-1);
        }
        full = pts;
        cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) cum[i] = cum[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        total = cum[cum.length - 1];
        buildSteps(g, names);
        status = ST_OK;
        shown = new ArrayList<>(pts);
    }

    private void buildSteps(RoadNet.Graph g, List<Integer> names) {
        steps.clear();
        int n = full.size();
        for (int i = 1; i + 1 < n; i++) {
            double[] a = full.get(i - 1);
            double[] b = full.get(i);
            double[] c = full.get(i + 1);
            double l1 = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double l2 = Math.hypot(c[0] - b[0], c[1] - b[1]);
            if (l1 < 0.5 || l2 < 0.5) continue;
            double h1 = Math.atan2(b[1] - a[1], b[0] - a[0]);
            double h2 = Math.atan2(c[1] - b[1], c[0] - b[0]);
            double d = Math.toDegrees(h2 - h1);
            d = ((d % 360.0) + 540.0) % 360.0 - 180.0;
            double ad = Math.abs(d);
            if (ad < 28) continue;
            Step s = new Step();
            s.at = cum[i];
            s.x = b[0];
            s.z = b[1];
            // pe ecran (z in jos), unghi pozitiv = viraj spre dreapta
            int t = ad < 55 ? 1 : ad < 125 ? 2 : ad < 160 ? 3 : 4;
            s.turn = t == 4 ? 4 : (d > 0 ? t : -t);
            s.street = g.name(names.get(i + 1));
            steps.add(s);
        }
        // coboara manevrele consecutive aproape identice (zig-zag din scanare): pastreaza-le doar daca sunt la > 6 m
        for (int i = steps.size() - 1; i > 0; i--) {
            Step a = steps.get(i - 1);
            Step b = steps.get(i);
            if (b.at - a.at < 6 && Math.signum(a.turn) == Math.signum(b.turn) && Math.abs(a.turn) + Math.abs(b.turn) <= 3) {
                a.turn = Math.max(-3, Math.min(3, a.turn + b.turn));
                steps.remove(i);
            }
        }
        Step end = new Step();
        end.at = total;
        end.x = full.get(n - 1)[0];
        end.z = full.get(n - 1)[1];
        end.turn = 9;
        steps.add(end);
    }

    // ------------------------------------------------------------------ tick

    /** La fiecare tick de client cat timp exista o destinatie. */
    public void tick(Minecraft mc) {
        if (mc.player == null || !GpsNav.active) return;
        long now = System.currentTimeMillis();
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        if (!computedOnce) {
            compute(mc, px, pz);
        } else if (revisionSeen != RoadNet.revision) {
            compute(mc, px, pz);
        } else if (status != ST_OK && status != ST_NO_ROADS && now - lastCompute > 5000 && Math.hypot(px - startX, pz - startZ) > 15) {
            compute(mc, px, pz);
        }
        if (status != ST_OK || full.size() < 2) return;
        // pozitia pe traseu
        int from = Math.max(0, lastSeg - 3);
        int to = Math.min(full.size() - 1, lastSeg + 40);
        double[] best = project(px, pz, from, to);
        if (best[3] > 12) best = project(px, pz, 0, full.size() - 1);
        lastSeg = (int) best[0];
        along = Math.max(along - 30, best[1]);
        offDist = best[3];
        shown = new ArrayList<>();
        shown.add(new double[] {px, pz});
        for (int i = lastSeg + 1; i < full.size(); i++) shown.add(full.get(i));
        if (shown.size() < 2) shown.add(new double[] {GpsNav.x, GpsNav.z});
        // iesire de pe traseu: recalculeaza
        if (offDist > 20) {
            if (offSince == 0) offSince = now;
            if (now - offSince > 1500 && now - lastCompute > 3000) {
                compute(mc, px, pz);
                mc.player.displayClientMessage(new StringTextComponent("§b[GPS] §fRecalculez traseul…"), true);
                if (GpsPrefs.navSound) mc.getSoundManager().play(SimpleSound.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.8f));
            }
        } else {
            offSince = 0;
        }
        // anunturi
        if (GpsPrefs.navSound) {
            Step s = next();
            if (s != null && s.turn != 9) {
                double d = s.at - along;
                int level = d <= 35 ? 3 : d <= 110 ? 2 : d <= 300 ? 1 : 0;
                if (level > s.announced) {
                    s.announced = level;
                    float pitch = level == 1 ? 0.9f : level == 2 ? 1.2f : 1.6f;
                    mc.getSoundManager().play(SimpleSound.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch));
                    String st = s.street.isEmpty() ? "" : " pe " + s.street;
                    mc.player.displayClientMessage(new StringTextComponent("§b[GPS] §fÎn " + Math.round(d / 10.0) * 10 + " m: §b" + s.text() + st), true);
                }
            }
        }
    }

    /** Proiectia punctului pe segmentele [from, to): {segment, distantaPeTraseu, t, distanta}. */
    private double[] project(double px, double pz, int from, int to) {
        double bestD = Double.MAX_VALUE;
        double[] r = {from, 0, 0, Double.MAX_VALUE};
        for (int i = from; i < to && i + 1 < full.size(); i++) {
            double[] a = full.get(i);
            double[] b = full.get(i + 1);
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double l2 = vx * vx + vz * vz;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * vx + (pz - a[1]) * vz) / l2));
            double qx = a[0] + vx * t;
            double qz = a[1] + vz * t;
            double d = Math.hypot(px - qx, pz - qz);
            if (d < bestD) {
                bestD = d;
                r = new double[] {i, cum[i] + Math.sqrt(l2) * t, t, d};
            }
        }
        return r;
    }
}
