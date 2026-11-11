package ro.solarstudios.flowcity.maps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.Heightmap;

/**
 * Ruta GPS: A* pe terenul incarcat de client (celule de 2x2 blocuri). Cladirile si apa sunt obstacole (diferente mari de inaltime),
 * deci ruta merge pe strazi. Calculul e impartit pe tick-uri. Daca tinta e in afara zonei incarcate, ruta duce spre marginea ei.
 */
public final class Router {
    private Router() {}

    private static final int CELL = 2;
    private static final int RADIUS = 64; // celule (=128 blocuri)
    private static final int MAX_EXPANSIONS = 16000;

    private static final class Node implements Comparable<Node> {
        final int cx;
        final int cz;
        float g;
        float f;
        Node parent;

        Node(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }

        @Override
        public int compareTo(Node o) {
            return Float.compare(f, o.f);
        }
    }

    private static boolean active;
    private static int ox;
    private static int oz;
    private static int gcx;
    private static int gcz;
    private static boolean goalInside;
    private static PriorityQueue<Node> open;
    private static Map<Long, Node> seen;
    private static Map<Long, Integer> heights;
    private static Map<Long, Boolean> chunks;
    private static int expansions;
    private static Node best;
    private static float bestH;
    private static double targetX;
    private static double targetZ;
    public static long startedAt;
    public static double startX;
    public static double startZ;

    public static boolean busy() {
        return active;
    }

    public static void cancel() {
        active = false;
        open = null;
        seen = null;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public static void start(double px, double pz, double tx, double tz) {
        ox = (int) Math.floor(px);
        oz = (int) Math.floor(pz);
        targetX = tx;
        targetZ = tz;
        startX = px;
        startZ = pz;
        startedAt = System.currentTimeMillis();
        int dx = (int) Math.floor((tx - ox) / CELL);
        int dz = (int) Math.floor((tz - oz) / CELL);
        goalInside = Math.abs(dx) <= RADIUS && Math.abs(dz) <= RADIUS;
        if (!goalInside) {
            double len = Math.max(Math.abs(dx), Math.abs(dz));
            dx = (int) Math.round(dx * RADIUS / len);
            dz = (int) Math.round(dz * RADIUS / len);
        }
        gcx = dx;
        gcz = dz;
        open = new PriorityQueue<>();
        seen = new HashMap<>();
        heights = new HashMap<>();
        chunks = new HashMap<>();
        expansions = 0;
        Node s = new Node(0, 0);
        s.g = 0;
        s.f = h(0, 0);
        best = s;
        bestH = s.f;
        open.add(s);
        seen.put(key(0, 0), s);
        active = true;
    }

    private static float h(int cx, int cz) {
        float dx = cx - gcx;
        float dz = cz - gcz;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    private static int height(ClientWorld w, int cx, int cz) {
        long k = key(cx, cz);
        Integer cached = heights.get(k);
        if (cached != null) return cached;
        int wx = ox + cx * CELL;
        int wz = oz + cz * CELL;
        long ck = ((long) (wx >> 4) << 32) ^ ((wz >> 4) & 0xFFFFFFFFL);
        Boolean ok = chunks.get(ck);
        if (ok == null) {
            ok = w.getChunkProvider().getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false) != null;
            chunks.put(ck, ok);
        }
        int res = -1;
        if (ok) {
            int hh = w.getHeight(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, wx, wz);
            if (hh > 0 && w.getFluidState(new BlockPos(wx, hh - 1, wz)).isEmpty()) res = hh;
        }
        heights.put(k, res);
        return res;
    }

    /** Continua calculul; intoarce ruta (lista de {x,z}) cand e gata, altfel null. */
    public static List<double[]> step(int budget) {
        if (!active) return null;
        ClientWorld w = Minecraft.getInstance().world;
        if (w == null) {
            cancel();
            return null;
        }
        int n = 0;
        while (n < budget && !open.isEmpty() && expansions < MAX_EXPANSIONS) {
            Node cur = open.poll();
            n++;
            expansions++;
            float hc = h(cur.cx, cur.cz);
            if (hc < bestH) {
                bestH = hc;
                best = cur;
            }
            if (cur.cx == gcx && cur.cz == gcz) return finish(cur);
            if (hc <= 1.5f && goalInside) return finish(cur);
            int h0 = height(w, cur.cx, cur.cz);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cur.cx + dx;
                    int nz = cur.cz + dz;
                    if (Math.abs(nx) > RADIUS || Math.abs(nz) > RADIUS) continue;
                    int h1 = height(w, nx, nz);
                    if (h1 < 0 || Math.abs(h1 - h0) > 2) continue;
                    if (dx != 0 && dz != 0) {
                        // fara taiat colturi prin obstacole
                        int a = height(w, cur.cx + dx, cur.cz);
                        int b = height(w, cur.cx, cur.cz + dz);
                        if (a < 0 || b < 0 || Math.abs(a - h0) > 2 || Math.abs(b - h0) > 2) continue;
                    }
                    float g = cur.g + (dx != 0 && dz != 0 ? 1.414f : 1f) + Math.abs(h1 - h0) * 0.3f;
                    long k = key(nx, nz);
                    Node ex = seen.get(k);
                    if (ex == null) {
                        ex = new Node(nx, nz);
                        ex.g = g;
                        ex.f = g + h(nx, nz);
                        ex.parent = cur;
                        seen.put(k, ex);
                        open.add(ex);
                    } else if (g < ex.g) {
                        open.remove(ex);
                        ex.g = g;
                        ex.f = g + h(nx, nz);
                        ex.parent = cur;
                        open.add(ex);
                    }
                }
            }
        }
        if (open.isEmpty() || expansions >= MAX_EXPANSIONS) return finish(best);
        return null;
    }

    private static List<double[]> finish(Node end) {
        List<double[]> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) out.add(new double[] {ox + n.cx * CELL + 1.0, oz + n.cz * CELL + 1.0});
        Collections.reverse(out);
        // netezire usoara: scoatem punctele intermediare coliniare
        List<double[]> s = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (i == 0 || i == out.size() - 1) {
                s.add(out.get(i));
                continue;
            }
            double[] a = out.get(i - 1);
            double[] b = out.get(i);
            double[] c = out.get(i + 1);
            if (Math.abs((b[0] - a[0]) - (c[0] - b[0])) > 0.01 || Math.abs((b[1] - a[1]) - (c[1] - b[1])) > 0.01) s.add(b);
        }
        if (goalInside) s.add(new double[] {targetX, targetZ});
        active = false;
        open = null;
        seen = null;
        heights = null;
        chunks = null;
        return s;
    }
}
