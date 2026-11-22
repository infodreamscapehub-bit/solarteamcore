package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Reteaua de drumuri (client): datele primite de la server ("roads"), graful pentru rutare (A*) si cautarea celui mai apropiat
 * drum. Reteaua e desenata de un admin in editorul de harta (DRUMURI) sau scanata pe server si salvata in gps/roads.json.
 * Fara retea (sau cand destinatia nu e legata de drumuri) GPS-ul cade pe directia in linie dreapta, marcat clar in interfata.
 */
public final class RoadNet {
    private RoadNet() {}

    /** Limitele (oglindite pe server). */
    public static final int MAX_NODES = 30000;
    public static final int MAX_EDGES = 60000;

    /** Reteaua mutabila: folosita atat pentru datele "live" cat si pentru ciorna din editor. */
    public static final class Data {
        /** id -> {x, z} */
        public final Map<Integer, int[]> nodes = new LinkedHashMap<>();
        /** {a, b, flags (bit0 = sens unic a->b), indexNume (-1 = fara)} */
        public final List<int[]> edges = new ArrayList<>();
        public final List<String> names = new ArrayList<>();
        public int nextId = 1;

        public Data copy() {
            Data d = new Data();
            for (Map.Entry<Integer, int[]> e : nodes.entrySet()) d.nodes.put(e.getKey(), e.getValue().clone());
            for (int[] e : edges) d.edges.add(e.clone());
            d.names.addAll(names);
            d.nextId = nextId;
            return d;
        }

        public int addNode(int x, int z) {
            int id = nextId++;
            nodes.put(id, new int[] {x, z});
            return id;
        }

        public int edgeIndex(int a, int b) {
            for (int i = 0; i < edges.size(); i++) {
                int[] e = edges.get(i);
                if ((e[0] == a && e[1] == b) || (e[0] == b && e[1] == a)) return i;
            }
            return -1;
        }

        public boolean addEdge(int a, int b, int flags, int name) {
            if (a == b || !nodes.containsKey(a) || !nodes.containsKey(b) || edgeIndex(a, b) >= 0) return false;
            edges.add(new int[] {a, b, flags, name});
            return true;
        }

        public void removeNode(int id) {
            nodes.remove(id);
            edges.removeIf(e -> e[0] == id || e[1] == id);
        }

        public int nameIndex(String n) {
            if (n == null || n.trim().isEmpty()) return -1;
            String t = n.trim();
            for (int i = 0; i < names.size(); i++) if (names.get(i).equalsIgnoreCase(t)) return i;
            if (names.size() >= 2000) return -1;
            names.add(t.length() > 32 ? t.substring(0, 32) : t);
            return names.size() - 1;
        }

        public String nameOf(int idx) {
            return idx >= 0 && idx < names.size() ? names.get(idx) : "";
        }

        /** Elimina numele nefolosite si reindexeaza (inainte de trimiterea la server). */
        public void compactNames() {
            Map<Integer, Integer> map = new HashMap<>();
            List<String> out = new ArrayList<>();
            for (int[] e : edges) {
                if (e[3] < 0 || e[3] >= names.size()) {
                    e[3] = -1;
                    continue;
                }
                Integer m = map.get(e[3]);
                if (m == null) {
                    m = out.size();
                    out.add(names.get(e[3]));
                    map.put(e[3], m);
                }
                e[3] = m;
            }
            names.clear();
            names.addAll(out);
        }

        /** Numarul de componente conexe (noduri izolate incluse). */
        public int components() {
            Map<Integer, Integer> par = new HashMap<>();
            for (Integer id : nodes.keySet()) par.put(id, id);
            for (int[] e : edges) {
                int ra = find(par, e[0]);
                int rb = find(par, e[1]);
                if (ra != rb) par.put(ra, rb);
            }
            int c = 0;
            for (Integer id : nodes.keySet()) if (find(par, id) == id) c++;
            return c;
        }

        /** Id-urile nodurilor fara nicio muchie. */
        public int isolated() {
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (int[] e : edges) {
                used.add(e[0]);
                used.add(e[1]);
            }
            int c = 0;
            for (Integer id : nodes.keySet()) if (!used.contains(id)) c++;
            return c;
        }

        private static int find(Map<Integer, Integer> par, int x) {
            int r = x;
            while (par.get(r) != r) r = par.get(r);
            int c = x;
            while (par.get(c) != r) {
                int nx = par.get(c);
                par.put(c, r);
                c = nx;
            }
            return r;
        }
    }

    /** Reteaua primita de la server (nu o modifica; ciorna din editor e o copie). */
    public static Data live = new Data();
    public static long ver = -1;
    /** Creste la fiecare retea noua primita (traseele active se recalculeaza). */
    public static int revision;

    private static Data asm;
    private static long asmVer;
    private static int asmGot;

    public static boolean hasRoads() {
        return !live.edges.isEmpty();
    }

    public static void clear() {
        live = new Data();
        ver = -1;
        asm = null;
        graph = null;
        revision++;
    }

    /** Mesajul "roads" al serverului (in bucati, in ordine). */
    public static void onRoads(JsonObject d) {
        long v = (long) num(d, "ver");
        int part = (int) num(d, "part");
        int parts = Math.max(1, (int) num(d, "parts"));
        if (part == 0 || asm == null || asmVer != v) {
            if (part != 0) return;
            asm = new Data();
            asmVer = v;
            asmGot = 0;
            if (d.has("names") && d.get("names").isJsonArray()) for (int i = 0; i < d.getAsJsonArray("names").size(); i++) asm.names.add(d.getAsJsonArray("names").get(i).getAsString());
        }
        if (d.has("n") && d.get("n").isJsonArray()) {
            JsonArray a = d.getAsJsonArray("n");
            for (int i = 0; i + 2 < a.size(); i += 3) {
                int id = a.get(i).getAsInt();
                asm.nodes.put(id, new int[] {a.get(i + 1).getAsInt(), a.get(i + 2).getAsInt()});
                if (id >= asm.nextId) asm.nextId = id + 1;
            }
        }
        if (d.has("e") && d.get("e").isJsonArray()) {
            JsonArray a = d.getAsJsonArray("e");
            for (int i = 0; i + 3 < a.size(); i += 4) {
                asm.edges.add(new int[] {a.get(i).getAsInt(), a.get(i + 1).getAsInt(), a.get(i + 2).getAsInt(), a.get(i + 3).getAsInt()});
            }
        }
        asmGot++;
        if (asmGot >= parts) {
            live = asm;
            ver = asmVer;
            asm = null;
            graph = null;
            revision++;
        }
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
    }

    // ------------------------------------------------------------------ graf de rutare

    public static final class Graph {
        final int n;
        final float[] x;
        final float[] z;
        final int[][] adj;
        final float[][] w;
        final int[][] nm;
        final int[] ea;
        final int[] eb;
        final int[] ef;
        final int[] en;
        final Map<Long, int[]> grid = new HashMap<>();
        final List<String> names;

        Graph(Data d) {
            names = d.names;
            Map<Integer, Integer> idx = new HashMap<>();
            n = d.nodes.size();
            x = new float[n];
            z = new float[n];
            int i = 0;
            for (Map.Entry<Integer, int[]> e : d.nodes.entrySet()) {
                idx.put(e.getKey(), i);
                x[i] = e.getValue()[0] + 0.5f;
                z[i] = e.getValue()[1] + 0.5f;
                i++;
            }
            int m = d.edges.size();
            ea = new int[m];
            eb = new int[m];
            ef = new int[m];
            en = new int[m];
            int[] deg = new int[n];
            int k = 0;
            for (int[] e : d.edges) {
                Integer a = idx.get(e[0]);
                Integer b = idx.get(e[1]);
                if (a == null || b == null) continue;
                ea[k] = a;
                eb[k] = b;
                ef[k] = e[2] & 1;
                en[k] = e[3];
                deg[a]++;
                if (ef[k] == 0) deg[b]++;
                k++;
            }
            adj = new int[n][];
            w = new float[n][];
            nm = new int[n][];
            for (i = 0; i < n; i++) {
                adj[i] = new int[deg[i]];
                w[i] = new float[deg[i]];
                nm[i] = new int[deg[i]];
            }
            int[] fill = new int[n];
            Map<Long, List<Integer>> cells = new HashMap<>();
            for (int q = 0; q < k; q++) {
                int a = ea[q];
                int b = eb[q];
                float len = (float) Math.hypot(x[a] - x[b], z[a] - z[b]);
                adj[a][fill[a]] = b;
                w[a][fill[a]] = len;
                nm[a][fill[a]++] = en[q];
                if (ef[q] == 0) {
                    adj[b][fill[b]] = a;
                    w[b][fill[b]] = len;
                    nm[b][fill[b]++] = en[q];
                }
                int cx0 = (int) Math.floor(Math.min(x[a], x[b]) / 32f);
                int cx1 = (int) Math.floor(Math.max(x[a], x[b]) / 32f);
                int cz0 = (int) Math.floor(Math.min(z[a], z[b]) / 32f);
                int cz1 = (int) Math.floor(Math.max(z[a], z[b]) / 32f);
                if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 400) continue;
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cz = cz0; cz <= cz1; cz++) cells.computeIfAbsent(ck(cx, cz), kk -> new ArrayList<>()).add(q);
                }
            }
            for (Map.Entry<Long, List<Integer>> e : cells.entrySet()) {
                int[] arr = new int[e.getValue().size()];
                for (int q = 0; q < arr.length; q++) arr[q] = e.getValue().get(q);
                grid.put(e.getKey(), arr);
            }
        }

        private static long ck(int cx, int cz) {
            return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        }

        public String name(int idx) {
            return idx >= 0 && idx < names.size() ? names.get(idx) : "";
        }

        /** Cel mai apropiat punct de pe un drum: {muchie, t(0..1 de la a spre b), px, pz, distanta} sau null. */
        public double[] nearest(double px, double pz, double maxDist) {
            int r = (int) Math.ceil(maxDist / 32.0);
            int cx = (int) Math.floor(px / 32.0);
            int cz = (int) Math.floor(pz / 32.0);
            double[] best = null;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int[] es = grid.get(ck(cx + dx, cz + dz));
                    if (es == null) continue;
                    for (int q : es) {
                        double ax = x[ea[q]];
                        double az = z[ea[q]];
                        double bx = x[eb[q]];
                        double bz = z[eb[q]];
                        double vx = bx - ax;
                        double vz = bz - az;
                        double l2 = vx * vx + vz * vz;
                        double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l2));
                        double qx = ax + vx * t;
                        double qz = az + vz * t;
                        double d = Math.hypot(px - qx, pz - qz);
                        if (d <= maxDist && (best == null || d < best[4])) best = new double[] {q, t, qx, qz, d};
                    }
                }
            }
            return best;
        }
    }

    private static Graph graph;

    public static Graph graph() {
        Graph g = graph;
        if (g == null && !live.edges.isEmpty()) {
            g = new Graph(live);
            graph = g;
        }
        return g;
    }

    // ------------------------------------------------------------------ A*

    /** Rezultatul rutarii: punctele {x, z} de pe drumuri (fara capetele de acces) si numele segmentelor intre ele. */
    public static final class Found {
        public final List<double[]> pts = new ArrayList<>();
        /** nameIdx[i] = numele segmentului dintre pts[i-1] si pts[i] (-1 = fara); [0] = -1. */
        public final List<Integer> segName = new ArrayList<>();
        public double length;
    }

    /**
     * Cel mai scurt drum intre doua puncte proiectate pe retea (respecta sensurile unice). start/end = rezultate {@link Graph#nearest}.
     * Intoarce null daca nu exista drum.
     */
    public static Found route(Graph g, double[] s, double[] e) {
        int se = (int) s[0];
        int ee = (int) e[0];
        double sx = s[2];
        double sz = s[3];
        double ex = e[2];
        double ez = e[3];
        double slen = Math.hypot(g.x[g.ea[se]] - g.x[g.eb[se]], g.z[g.ea[se]] - g.z[g.eb[se]]);
        double elen = Math.hypot(g.x[g.ea[ee]] - g.x[g.eb[ee]], g.z[g.ea[ee]] - g.z[g.eb[ee]]);
        // acelasi segment: mergi direct, daca sensul permite
        if (se == ee) {
            boolean fwd = e[1] >= s[1];
            if (g.ef[se] == 0 || fwd) {
                Found f = new Found();
                f.pts.add(new double[] {sx, sz});
                f.segName.add(-1);
                f.pts.add(new double[] {ex, ez});
                f.segName.add(g.en[se]);
                f.length = Math.hypot(ex - sx, ez - sz);
                return f;
            }
        }
        int n = g.n;
        double[] dist = new double[n + 1];
        int[] prev = new int[n + 1];
        int[] prevName = new int[n + 1];
        java.util.Arrays.fill(dist, Double.MAX_VALUE);
        java.util.Arrays.fill(prev, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        int a = g.ea[se];
        int b = g.eb[se];
        // iesirea din punctul de start: spre b (mereu), spre a (doar daca nu e sens unic)
        relax(dist, prev, prevName, pq, b, (1 - s[1]) * slen, -2, g.en[se], g, ex, ez);
        if (g.ef[se] == 0) relax(dist, prev, prevName, pq, a, s[1] * slen, -3, g.en[se], g, ex, ez);
        int ca = g.ea[ee];
        int cb = g.eb[ee];
        // intrarea in punctul final: din a (mereu), din b (doar daca nu e sens unic)
        double extraA = e[1] * elen;
        double extraB = (1 - e[1]) * elen;
        int sink = n;
        int expansions = 0;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[1];
            if (top[2] > dist[u] + 1e-9 && u != sink) continue;
            if (u == sink) break;
            if (++expansions > 400000) return null;
            double gu = dist[u];
            if (u == ca) relaxSink(dist, prev, prevName, pq, sink, gu + extraA, u, g.en[ee]);
            if (u == cb && g.ef[ee] == 0) relaxSink(dist, prev, prevName, pq, sink, gu + extraB, u, g.en[ee]);
            int[] ad = g.adj[u];
            float[] wt = g.w[u];
            for (int k = 0; k < ad.length; k++) relax(dist, prev, prevName, pq, ad[k], gu + wt[k], u, g.nm[u][k], g, ex, ez);
        }
        if (dist[sink] == Double.MAX_VALUE) return null;
        // reconstruieste
        List<Integer> seq = new ArrayList<>();
        List<Integer> nms = new ArrayList<>();
        int cur = sink;
        int guard = 0;
        while (cur >= 0 && guard++ < n + 5) {
            seq.add(cur);
            nms.add(prevName[cur]);
            int p = prev[cur];
            if (p == -2 || p == -3) break;
            cur = p;
        }
        Found f = new Found();
        f.pts.add(new double[] {sx, sz});
        f.segName.add(-1);
        for (int i = seq.size() - 1; i >= 0; i--) {
            int node = seq.get(i);
            if (node == sink) continue;
            f.pts.add(new double[] {g.x[node], g.z[node]});
            f.segName.add(nms.get(i));
        }
        f.pts.add(new double[] {ex, ez});
        f.segName.add(g.en[ee]);
        double len = 0;
        for (int i = 1; i < f.pts.size(); i++) len += Math.hypot(f.pts.get(i)[0] - f.pts.get(i - 1)[0], f.pts.get(i)[1] - f.pts.get(i - 1)[1]);
        f.length = len;
        return f;
    }

    private static void relax(double[] dist, int[] prev, int[] prevName, PriorityQueue<double[]> pq, int v, double gv, int from, int name, Graph g, double ex, double ez) {
        if (gv >= dist[v]) return;
        dist[v] = gv;
        prev[v] = from;
        prevName[v] = name;
        double h = Math.hypot(g.x[v] - ex, g.z[v] - ez);
        pq.add(new double[] {gv + h, v, gv});
    }

    private static void relaxSink(double[] dist, int[] prev, int[] prevName, PriorityQueue<double[]> pq, int sink, double gv, int from, int name) {
        if (gv >= dist[sink]) return;
        dist[sink] = gv;
        prev[sink] = from;
        prevName[sink] = name;
        pq.add(new double[] {gv, sink, gv});
    }
}
