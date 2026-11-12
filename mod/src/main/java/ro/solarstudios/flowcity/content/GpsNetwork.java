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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Reteaua rutiera GPS (ca in EGK eMaps): adminii pun blocuri gps_* pe strazi, iar ruta se calculeaza pe graful lor.
 * Doar overworld. Persistata in flowcity-gps.json. Apelabila si prin reflexie din pluginul Bukkit (metodele publice statice).
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

        Node(int x, int y, int z, int type, int dx, int dz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.dx = dx;
            this.dz = dz;
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
            for (JsonElement e : new JsonParser().parse(txt).getAsJsonArray()) {
                JsonArray a = e.getAsJsonArray();
                Node n = new Node(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt());
                NODES.put(new BlockPos(n.x, n.y, n.z).toLong(), n);
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
                arr.add(a);
            }
            Files.write(file(), new Gson().toJson(arr).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nu e critic
        }
    }

    public static void add(BlockPos pos, int type, int dx, int dz) {
        ensureLoaded();
        NODES.put(pos.toLong(), new Node(pos.getX(), pos.getY(), pos.getZ(), type, dx, dz));
        save();
    }

    public static void remove(BlockPos pos) {
        ensureLoaded();
        if (NODES.remove(pos.toLong()) != null) save();
    }

    public static boolean has(BlockPos pos) {
        ensureLoaded();
        return NODES.containsKey(pos.toLong());
    }

    public static int count() {
        ensureLoaded();
        return NODES.size();
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

    // ------------------------------------------------------------------ rutare

    private static boolean linked(Node a, Node b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double reach = (a.type == INTERSECTION || b.type == INTERSECTION) ? 9.5 : 6.5;
        if (dx * dx + dz * dz > reach * reach || Math.abs(b.y - a.y) > 5) return false;
        if (a.type == ONEWAY && dx * a.dx + dz * a.dz <= 0) return false;
        if (b.type == ONEWAY && dx * b.dx + dz * b.dz <= 0) return false;
        return true;
    }

    private static Node nearest(List<Node> all, double x, double z, double max) {
        Node best = null;
        double bd = max * max;
        for (Node n : all) {
            double d = (n.x + 0.5 - x) * (n.x + 0.5 - x) + (n.z + 0.5 - z) * (n.z + 0.5 - z);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    /** Ruta {x,z}[] de la (sx,sz) la (tx,tz) pe retea, sau null daca nu exista. */
    public static double[][] route(double sx, double sz, double tx, double tz) {
        ensureLoaded();
        List<Node> all = new ArrayList<>(NODES.values());
        if (all.size() < 2) return null;
        Node start = nearest(all, sx, sz, 100);
        Node end = nearest(all, tx, tz, 100);
        if (start == null || end == null) return null;
        int n = all.size();
        Map<Node, Integer> idx = new HashMap<>();
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Node a = all.get(i);
            idx.put(a, i);
            grid.computeIfAbsent(cell(a.x >> 3, a.z >> 3), k -> new ArrayList<>()).add(i);
        }
        double[] dist = new double[n];
        int[] prev = new int[n];
        java.util.Arrays.fill(dist, Double.MAX_VALUE);
        java.util.Arrays.fill(prev, -1);
        int s = idx.get(start);
        int e = idx.get(end);
        dist[s] = 0;
        PriorityQueue<double[]> pq = new PriorityQueue<>((p, q) -> Double.compare(p[0], q[0]));
        pq.add(new double[] {0, s});
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            int u = (int) cur[1];
            if (cur[0] > dist[u]) continue;
            if (u == e) break;
            Node a = all.get(u);
            for (int cx = (a.x >> 3) - 2; cx <= (a.x >> 3) + 2; cx++) {
                for (int cz = (a.z >> 3) - 2; cz <= (a.z >> 3) + 2; cz++) {
                    List<Integer> l = grid.get(cell(cx, cz));
                    if (l == null) continue;
                    for (int v : l) {
                        if (v == u) continue;
                        Node b = all.get(v);
                        if (!linked(a, b)) continue;
                        double w = Math.sqrt((b.x - a.x) * (double) (b.x - a.x) + (b.z - a.z) * (double) (b.z - a.z));
                        double nd = dist[u] + w;
                        if (nd < dist[v]) {
                            dist[v] = nd;
                            prev[v] = u;
                            pq.add(new double[] {nd, v});
                        }
                    }
                }
            }
        }
        if (dist[e] == Double.MAX_VALUE) return null;
        List<double[]> pts = new ArrayList<>();
        for (int v = e; v != -1; v = prev[v]) {
            Node a = all.get(v);
            pts.add(new double[] {a.x + 0.5, a.z + 0.5});
        }
        java.util.Collections.reverse(pts);
        pts.add(new double[] {tx, tz});
        return pts.toArray(new double[0][]);
    }

    private static long cell(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
