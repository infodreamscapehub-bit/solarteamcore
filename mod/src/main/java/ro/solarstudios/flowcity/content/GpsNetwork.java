package ro.solarstudios.flowcity.content;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Reteaua rutiera GPS, ca in EGK eMaps: blocurile gps_* sunt noduri, iar legaturile (dublu sens / sens unic) se fac explicit
 * cu Cheia GPS (click pe nod A, apoi pe nod B). Ruta = A* pe graful orientat, cu proiectie pe cel mai apropiat drum.
 * Doar overworld. Persistata in flowcity-gps.json. Metodele publice statice sunt apelabile prin reflexie din pluginul Bukkit.
 */
public final class GpsNetwork {
    private GpsNetwork() {}

    public static final int PATH = 0;
    public static final int ONEWAY = 1;
    public static final int TWOWAY = 2;
    public static final int INTERSECTION = 3;

    public static final class Node {
        public final int x;
        public final int y;
        public final int z;
        public final int type;
        public final int dx;
        public final int dz;
        /** Nodurile spre care se poate merge de aici (chei BlockPos.toLong). */
        public final Set<Long> out = ConcurrentHashMap.newKeySet();

        Node(int x, int y, int z, int type, int dx, int dz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.dx = dx;
            this.dz = dz;
        }

        public long key() {
            return new BlockPos(x, y, z).asLong();
        }
    }

    private static final Map<Long, Node> NODES = new ConcurrentHashMap<>();
    private static boolean loaded;

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("flowcity-gps.json");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            String txt = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            JsonArray root = new JsonParser().parse(txt).getAsJsonArray();
            for (JsonElement e : root) {
                JsonArray a = e.getAsJsonArray();
                Node n = new Node(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt());
                NODES.put(n.key(), n);
            }
            for (JsonElement e : root) {
                JsonArray a = e.getAsJsonArray();
                if (a.size() < 7) continue;
                Node n = NODES.get(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()).asLong());
                for (JsonElement c : a.get(6).getAsJsonArray()) {
                    JsonArray p = c.getAsJsonArray();
                    long k = new BlockPos(p.get(0).getAsInt(), p.get(1).getAsInt(), p.get(2).getAsInt()).asLong();
                    if (n != null && NODES.containsKey(k)) n.out.add(k);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // retea goala
        }
    }

    private static synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Node n : NODES.values()) {
                JsonArray a = new JsonArray();
                a.add(n.x);
                a.add(n.y);
                a.add(n.z);
                a.add(n.type);
                a.add(n.dx);
                a.add(n.dz);
                JsonArray cs = new JsonArray();
                for (long k : n.out) {
                    BlockPos p = BlockPos.of(k);
                    JsonArray c = new JsonArray();
                    c.add(p.getX());
                    c.add(p.getY());
                    c.add(p.getZ());
                    cs.add(c);
                }
                a.add(cs);
                arr.add(a);
            }
            Files.write(file(), new Gson().toJson(arr).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nu e critic
        }
    }

    // ------------------------------------------------------------------ noduri si legaturi

    public static void add(BlockPos pos, int type, int dx, int dz) {
        ensureLoaded();
        NODES.put(pos.asLong(), new Node(pos.getX(), pos.getY(), pos.getZ(), type, dx, dz));
        save();
    }

    public static void remove(BlockPos pos) {
        ensureLoaded();
        long k = pos.asLong();
        if (NODES.remove(k) != null) {
            for (Node o : NODES.values()) o.out.remove(k);
            save();
        }
    }

    public static boolean has(BlockPos pos) {
        ensureLoaded();
        return NODES.containsKey(pos.asLong());
    }

    public static int count() {
        ensureLoaded();
        return NODES.size();
    }

    public static int connections() {
        ensureLoaded();
        int c = 0;
        for (Node n : NODES.values()) c += n.out.size();
        return c;
    }

    public static Node get(BlockPos pos) {
        ensureLoaded();
        return NODES.get(pos.asLong());
    }

    public static Node byKey(long key) {
        return NODES.get(key);
    }

    /** a -> b (si b -> a daca biDirectional). */
    public static boolean connect(BlockPos a, BlockPos b, boolean biDirectional) {
        ensureLoaded();
        Node na = NODES.get(a.asLong());
        Node nb = NODES.get(b.asLong());
        if (na == null || nb == null) return false;
        na.out.add(b.asLong());
        if (biDirectional) nb.out.add(a.asLong());
        save();
        return true;
    }

    /** Sterge toate legaturile nodului (si pe cele care intra in el). */
    public static boolean disconnectAll(BlockPos pos) {
        ensureLoaded();
        long k = pos.asLong();
        Node n = NODES.get(k);
        boolean changed = false;
        if (n != null && !n.out.isEmpty()) {
            n.out.clear();
            changed = true;
        }
        for (Node o : NODES.values()) if (o.out.remove(k)) changed = true;
        if (changed) save();
        return changed;
    }

    public static List<Node> near(double x, double y, double z, double r) {
        ensureLoaded();
        List<Node> out = new ArrayList<>();
        double r2 = r * r;
        for (Node n : NODES.values()) {
            double dx = n.x - x;
            double dz = n.z - z;
            double dy = n.y - y;
            if (dx * dx + dz * dz + dy * dy <= r2) out.add(n);
        }
        return out;
    }

    // ------------------------------------------------------------------ rutare (ca GPSPathfinder din EGK)

    private static final class RoadPoint {
        double x;
        double z;
        Node a;
        Node b;
    }

    private static double dist(Node a, Node b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Node> aStar(Node start, Node end) {
        PriorityQueue<double[]> open = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        Map<Long, Long> came = new HashMap<>();
        Map<Long, Double> g = new HashMap<>();
        Set<Long> closed = new HashSet<>();
        long sk = start.key();
        long ek = end.key();
        g.put(sk, 0.0);
        List<Long> ids = new ArrayList<>();
        ids.add(sk);
        open.add(new double[] {dist(start, end), 0});
        int iter = 0;
        while (!open.isEmpty() && ++iter <= 60000) {
            Node cur = NODES.get(ids.get((int) open.poll()[1]));
            if (cur == null) continue;
            long ck = cur.key();
            if (!closed.add(ck)) continue;
            if (ck == ek) {
                List<Node> path = new ArrayList<>();
                Long c = ck;
                while (c != null) {
                    Node n = NODES.get(c);
                    if (n != null) path.add(n);
                    c = came.get(c);
                }
                Collections.reverse(path);
                return path;
            }
            double cg = g.getOrDefault(ck, Double.MAX_VALUE);
            for (long nk : cur.out) {
                Node nb = NODES.get(nk);
                if (nb == null || closed.contains(nk)) continue;
                double t = cg + dist(cur, nb);
                if (t < g.getOrDefault(nk, Double.MAX_VALUE)) {
                    came.put(nk, ck);
                    g.put(nk, t);
                    ids.add(nk);
                    open.add(new double[] {t + dist(nb, end), ids.size() - 1});
                }
            }
        }
        return null;
    }

    private static double length(List<Node> p) {
        double l = 0;
        for (int i = 0; i + 1 < p.size(); i++) l += dist(p.get(i), p.get(i + 1));
        return l;
    }

    /** Ruta {x,z}[] de la (sx,sz) la (tx,tz) pe retea, sau null daca nu merita/nu exista (clientul foloseste atunci linie dreapta). */
    public static double[][] route(double sx, double sz, double tx, double tz) {
        ensureLoaded();
        List<Node> all = new ArrayList<>(NODES.values());
        if (all.size() < 2) return null;
        double direct = Math.hypot(tx - sx, tz - sz);
        if (direct < 12) return null;

        // cele mai apropiate 5 noduri conectate de jucator
        List<Node> conn = new ArrayList<>();
        for (Node n : all) if (!n.out.isEmpty()) conn.add(n);
        if (conn.isEmpty()) return null;
        final double fx = sx;
        final double fz = sz;
        Collections.sort(conn, Comparator.comparingDouble(n -> (n.x + 0.5 - fx) * (n.x + 0.5 - fx) + (n.z + 0.5 - fz) * (n.z + 0.5 - fz)));
        List<Node> starts = conn.subList(0, Math.min(5, conn.size()));

        // cel mai apropiat punct de drum fata de destinatie (pe nod sau pe o muchie)
        RoadPoint end = null;
        double best = Double.MAX_VALUE;
        for (Node a : all) {
            double ax = a.x + 0.5;
            double az = a.z + 0.5;
            double d = (ax - tx) * (ax - tx) + (az - tz) * (az - tz);
            if (d < best) {
                best = d;
                end = new RoadPoint();
                end.x = ax;
                end.z = az;
                end.a = a;
            }
            for (long k : a.out) {
                Node b = NODES.get(k);
                if (b == null) continue;
                double bx = b.x + 0.5;
                double bz = b.z + 0.5;
                double vx = bx - ax;
                double vz = bz - az;
                double len = vx * vx + vz * vz;
                if (len <= 0) continue;
                double t = Math.max(0, Math.min(1, ((tx - ax) * vx + (tz - az) * vz) / len));
                double px = ax + t * vx;
                double pz = az + t * vz;
                double pd = (px - tx) * (px - tx) + (pz - tz) * (pz - tz);
                if (pd < best) {
                    best = pd;
                    end = new RoadPoint();
                    end.x = px;
                    end.z = pz;
                    end.a = a;
                    end.b = b;
                }
            }
        }
        if (end == null) return null;
        // daca drumul nu te apropie de destinatie fata de linia dreapta, nu are rost sa mergem pe strazi
        if (Math.sqrt(best) >= direct - 1) return null;

        List<Node> path = null;
        for (Node s : starts) {
            double d2 = (s.x + 0.5 - sx) * (s.x + 0.5 - sx) + (s.z + 0.5 - sz) * (s.z + 0.5 - sz);
            if (d2 > 250000) break;
            List<Node> p;
            if (end.b == null) {
                p = aStar(s, end.a);
            } else {
                List<Node> pa = aStar(s, end.a);
                List<Node> pb = aStar(s, end.b);
                double la = pa != null ? length(pa) + Math.hypot(end.a.x + 0.5 - end.x, end.a.z + 0.5 - end.z) : Double.MAX_VALUE;
                double lb = pb != null ? length(pb) + Math.hypot(end.b.x + 0.5 - end.x, end.b.z + 0.5 - end.z) : Double.MAX_VALUE;
                p = pa != null && la <= lb ? pa : pb;
            }
            if (p != null && !p.isEmpty()) {
                path = new ArrayList<>(p);
                break;
            }
        }
        if (path == null) return null;

        // taie nodurile din spatele jucatorului
        while (path.size() > 1) {
            Node n0 = path.get(0);
            Node n1 = path.get(1);
            double dStart = (n1.x + 0.5 - sx) * (n1.x + 0.5 - sx) + (n1.z + 0.5 - sz) * (n1.z + 0.5 - sz);
            double dEdge = (double) (n1.x - n0.x) * (n1.x - n0.x) + (double) (n1.z - n0.z) * (n1.z - n0.z);
            if (dStart < dEdge) path.remove(0);
            else break;
        }
        List<double[]> pts = new ArrayList<>();
        if (path.size() >= 2) {
            Node n0 = path.get(0);
            Node n1 = path.get(1);
            double vx = n1.x - n0.x;
            double vz = n1.z - n0.z;
            double len = vx * vx + vz * vz;
            if (len > 0) {
                double t = Math.max(0, Math.min(1, ((sx - n0.x - 0.5) * vx + (sz - n0.z - 0.5) * vz) / len));
                double px = n0.x + 0.5 + t * vx;
                double pz = n0.z + 0.5 + t * vz;
                if ((px - sx) * (px - sx) + (pz - sz) * (pz - sz) <= 36) {
                    pts.add(new double[] {px, pz});
                    path.remove(0);
                }
            }
        }
        for (Node n : path) pts.add(new double[] {n.x + 0.5, n.z + 0.5});
        pts.add(new double[] {end.x, end.z});
        pts.add(new double[] {tx, tz});
        return pts.toArray(new double[0][]);
    }
}
