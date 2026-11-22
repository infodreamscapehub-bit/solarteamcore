package ro.solarstudios.flowcity.bridge.gps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Transforma o grila de esantioane "e drum" intr-un graf rutier simplificat: subtiere (Zhang-Suen) la axa drumului, taierea
 * cioturilor scurte, lanturi de pixeli simplificate cu Douglas-Peucker. Fara Bukkit: ruleaza pe un fir separat, pe date simple.
 */
final class RoadScan {
    private RoadScan() {}

    static final class Result {
        final List<int[]> nodes = new ArrayList<>();
        final List<int[]> edges = new ArrayList<>();
    }

    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

    /** road: nx*nz (0/1), originea lumii (x0, z0), pasul grilei. */
    static Result extract(byte[] road, int nx, int nz, int x0, int z0, int step) {
        int w = nx + 2;
        int h = nz + 2;
        boolean[] a = new boolean[w * h];
        for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) if (road[z * nx + x] != 0) a[(z + 1) * w + x + 1] = true;
        thin(a, w, h);
        prune(a, w, h, 3);
        prune(a, w, h, 3);
        dropTiny(a, w, h, 3);
        return graph(a, w, h, x0 - step, z0 - step, step);
    }

    private static void thin(boolean[] a, int w, int h) {
        List<Integer> del = new ArrayList<>();
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 200) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                del.clear();
                for (int z = 1; z < h - 1; z++) {
                    for (int x = 1; x < w - 1; x++) {
                        int i = z * w + x;
                        if (!a[i]) continue;
                        boolean[] p = new boolean[8];
                        int b = 0;
                        for (int k = 0; k < 8; k++) {
                            p[k] = a[(z + DZ[k]) * w + x + DX[k]];
                            if (p[k]) b++;
                        }
                        if (b < 2 || b > 6) continue;
                        int tr = 0;
                        for (int k = 0; k < 8; k++) if (!p[k] && p[(k + 1) % 8]) tr++;
                        if (tr != 1) continue;
                        // p[0]=P2(N) p[2]=P4(E) p[4]=P6(S) p[6]=P8(W)
                        boolean ok = pass == 0 ? !(p[0] && p[2] && p[4]) && !(p[2] && p[4] && p[6]) : !(p[0] && p[2] && p[6]) && !(p[0] && p[4] && p[6]);
                        if (ok) del.add(i);
                    }
                }
                if (!del.isEmpty()) changed = true;
                for (int i : del) a[i] = false;
            }
        }
    }

    private static int deg(boolean[] a, int w, int i) {
        int x = i % w;
        int z = i / w;
        int d = 0;
        for (int k = 0; k < 8; k++) {
            int nx = x + DX[k];
            int nz = z + DZ[k];
            int j = nz * w + nx;
            if (!a[j]) continue;
            if ((k & 1) == 1) {
                // diagonala conteaza doar daca nu exista un drum ortogonal echivalent
                if (a[z * w + nx] || a[nz * w + x]) continue;
            }
            d++;
        }
        return d;
    }

    private static List<Integer> nbrs(boolean[] a, int w, int i) {
        int x = i % w;
        int z = i / w;
        List<Integer> out = new ArrayList<>(4);
        for (int k = 0; k < 8; k++) {
            int nx = x + DX[k];
            int nz = z + DZ[k];
            int j = nz * w + nx;
            if (!a[j]) continue;
            if ((k & 1) == 1 && (a[z * w + nx] || a[nz * w + x])) continue;
            out.add(j);
        }
        return out;
    }

    /** Taie cioturile: capete (grad 1) care ajung intr-o intersectie in cel mult maxLen pasi. */
    private static void prune(boolean[] a, int w, int h, int maxLen) {
        for (int z = 1; z < h - 1; z++) {
            for (int x = 1; x < w - 1; x++) {
                int i = z * w + x;
                if (!a[i] || deg(a, w, i) != 1) continue;
                List<Integer> chain = new ArrayList<>();
                int prev = -1;
                int cur = i;
                boolean junction = false;
                while (chain.size() <= maxLen) {
                    chain.add(cur);
                    List<Integer> ns = nbrs(a, w, cur);
                    int next = -1;
                    for (int n : ns) if (n != prev) next = n;
                    if (next < 0) break;
                    int dn = deg(a, w, next);
                    if (dn >= 3) {
                        junction = true;
                        break;
                    }
                    if (dn == 1) break;
                    prev = cur;
                    cur = next;
                }
                if (junction && chain.size() <= maxLen) for (int c : chain) a[c] = false;
            }
        }
    }

    /** Sterge componentele cu mai putin de minPix pixeli. */
    private static void dropTiny(boolean[] a, int w, int h, int minPix) {
        boolean[] seen = new boolean[a.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        List<Integer> comp = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i] || seen[i]) continue;
            comp.clear();
            q.add(i);
            seen[i] = true;
            while (!q.isEmpty()) {
                int c = q.poll();
                comp.add(c);
                for (int n : nbrs(a, w, c)) {
                    if (!seen[n]) {
                        seen[n] = true;
                        q.add(n);
                    }
                }
            }
            if (comp.size() < minPix) for (int c : comp) a[c] = false;
        }
    }

    private static Result graph(boolean[] a, int w, int h, int ox, int oz, int step) {
        Result res = new Result();
        int[] nodeOf = new int[a.length];
        java.util.Arrays.fill(nodeOf, -1);
        java.util.Set<Long> visited = new java.util.HashSet<>();
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < a.length; i++) if (a[i] && deg(a, w, i) != 2) keys.add(i);
        for (int k : keys) walkFrom(a, w, k, ox, oz, step, nodeOf, visited, res);
        // bucle fara intersectii
        for (int i = 0; i < a.length; i++) {
            if (!a[i] || nodeOf[i] >= 0) continue;
            boolean used = false;
            for (int n : nbrs(a, w, i)) if (visited.contains(key(i, n))) used = true;
            if (!used) walkFrom(a, w, i, ox, oz, step, nodeOf, visited, res);
        }
        return res;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }

    private static int nodeFor(int pix, int w, int ox, int oz, int step, int[] nodeOf, Result res) {
        if (nodeOf[pix] >= 0) return nodeOf[pix];
        int x = ox + (pix % w) * step;
        int z = oz + (pix / w) * step;
        res.nodes.add(new int[] {x, z});
        nodeOf[pix] = res.nodes.size() - 1;
        return nodeOf[pix];
    }

    private static void walkFrom(boolean[] a, int w, int start, int ox, int oz, int step, int[] nodeOf, java.util.Set<Long> visited, Result res) {
        for (int first : nbrs(a, w, start)) {
            if (visited.contains(key(start, first))) continue;
            List<Integer> chain = new ArrayList<>();
            chain.add(start);
            int prev = start;
            int cur = first;
            visited.add(key(start, first));
            visited.add(key(first, start));
            while (true) {
                chain.add(cur);
                if (cur == start) break;
                if (deg(a, w, cur) != 2) break;
                int next = -1;
                for (int n : nbrs(a, w, cur)) if (n != prev) next = n;
                if (next < 0) break;
                visited.add(key(cur, next));
                visited.add(key(next, cur));
                prev = cur;
                cur = next;
                if (chain.size() > 200000) break;
            }
            List<Integer> keep = simplify(chain, w, 0.75);
            for (int i = 0; i + 1 < keep.size(); i++) {
                int na = nodeFor(keep.get(i), w, ox, oz, step, nodeOf, res);
                int nb = nodeFor(keep.get(i + 1), w, ox, oz, step, nodeOf, res);
                if (na != nb) res.edges.add(new int[] {na, nb});
            }
        }
    }

    /** Douglas-Peucker iterativ pe indicii de pixeli; pastreaza capetele. */
    private static List<Integer> simplify(List<Integer> pts, int w, double tol) {
        int n = pts.size();
        if (n <= 2) return pts;
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        ArrayDeque<int[]> st = new ArrayDeque<>();
        st.push(new int[] {0, n - 1});
        while (!st.isEmpty()) {
            int[] r = st.pop();
            int s = r[0];
            int e = r[1];
            if (e <= s + 1) continue;
            double ax = pts.get(s) % w;
            double az = pts.get(s) / w;
            double bx = pts.get(e) % w;
            double bz = pts.get(e) / w;
            double dx = bx - ax;
            double dz = bz - az;
            double len2 = dx * dx + dz * dz;
            double best = -1;
            int bi = -1;
            for (int i = s + 1; i < e; i++) {
                double px = pts.get(i) % w;
                double pz = pts.get(i) / w;
                double d;
                if (len2 < 1e-9) {
                    d = Math.sqrt((px - ax) * (px - ax) + (pz - az) * (pz - az));
                } else {
                    d = Math.abs(dz * px - dx * pz + bx * az - bz * ax) / Math.sqrt(len2);
                }
                if (d > best) {
                    best = d;
                    bi = i;
                }
            }
            if (best > tol && bi > 0) {
                keep[bi] = true;
                st.push(new int[] {s, bi});
                st.push(new int[] {bi, e});
            }
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }
}
