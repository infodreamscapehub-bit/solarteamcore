package ro.solarstudios.flowcity.bridge.maps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graful strazilor (clasa pura, fara Bukkit): noduri + muchii. O muchie intre a si b (a &lt; b) are un mod:
 * {@link #BOTH} (dus-intors), {@link #FWD} (doar a -&gt; b, a fiind id-ul mai mic) sau {@link #BWD} (doar b -&gt; a).
 */
public final class StreetGraph {
    public static final int BOTH = 0;
    public static final int FWD = 1;
    public static final int BWD = 2;

    /** Nod: id stabil, lume (nume), coordonate de bloc, flag de intersectie. */
    public static final class Node {
        public final int id;
        public final String world;
        public final int x;
        public final int y;
        public final int z;
        public boolean junction;

        public Node(int id, String world, int x, int y, int z, boolean junction) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.junction = junction;
        }
    }

    private final Map<Integer, Node> nodes = new LinkedHashMap<>();
    /** cheie (lo,hi) -> mod */
    private final Map<Long, Integer> edges = new LinkedHashMap<>();
    private int nextId = 1;

    private static long key(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public Node node(int id) {
        return nodes.get(id);
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    /** Nod nou cu id automat. */
    public Node addNode(String world, int x, int y, int z, boolean junction) {
        Node n = new Node(nextId++, world, x, y, z, junction);
        nodes.put(n.id, n);
        return n;
    }

    /** Nod cu id dat (la incarcare din DB / import). */
    public Node putNode(int id, String world, int x, int y, int z, boolean junction) {
        Node n = new Node(id, world, x, y, z, junction);
        nodes.put(id, n);
        if (id >= nextId) nextId = id + 1;
        return n;
    }

    /** Sterge nodul si muchiile lui; intoarce idurile vecinilor afectati. */
    public List<Integer> removeNode(int id) {
        List<Integer> affected = new ArrayList<>();
        if (nodes.remove(id) == null) return affected;
        List<Long> dead = new ArrayList<>();
        for (Long k : edges.keySet()) {
            int lo = (int) (k >> 32);
            int hi = (int) (long) k;
            if (lo == id || hi == id) {
                dead.add(k);
                affected.add(lo == id ? hi : lo);
            }
        }
        for (Long k : dead) edges.remove(k);
        return affected;
    }

    /** Conecteaza from -&gt; to. oneWay: doar de la from la to (suprascrie sensul opus); altfel dus-intors. */
    public boolean connect(int from, int to, boolean oneWay) {
        if (from == to || !nodes.containsKey(from) || !nodes.containsKey(to)) return false;
        int mode;
        if (!oneWay) {
            mode = BOTH;
        } else {
            mode = from < to ? FWD : BWD;
        }
        edges.put(key(from, to), mode);
        return true;
    }

    /** Muchie existenta intre a si b? Intoarce modul sau -1. */
    public int mode(int a, int b) {
        Integer m = edges.get(key(a, b));
        return m == null ? -1 : m;
    }

    /** Se poate merge de la a la b? */
    public boolean canTravel(int a, int b) {
        int m = mode(a, b);
        if (m < 0) return false;
        if (m == BOTH) return true;
        return (a < b) == (m == FWD);
    }

    public boolean disconnect(int a, int b) {
        return edges.remove(key(a, b)) != null;
    }

    public int degree(int id) {
        int d = 0;
        for (Long k : edges.keySet()) {
            if ((int) (k >> 32) == id || (int) (long) k == id) d++;
        }
        return d;
    }

    /** Intersectie = flag explicit sau cel putin 3 muchii. */
    public boolean isJunction(int id) {
        Node n = nodes.get(id);
        return n != null && (n.junction || degree(id) >= 3);
    }

    /** Cel mai apropiat nod din aceeasi lume in raza data (distanta 3D), sau null. */
    public Node nearest(String world, double x, double y, double z, double maxDist) {
        Node best = null;
        double bd = maxDist * maxDist;
        for (Node n : nodes.values()) {
            if (!n.world.equals(world)) continue;
            double dx = n.x - x;
            double dy = n.y - y;
            double dz = n.z - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    public void clear() {
        nodes.clear();
        edges.clear();
        nextId = 1;
    }

    /** Toate muchiile ca triplete {lo, hi, mod}. */
    public List<int[]> edgeList() {
        List<int[]> out = new ArrayList<>(edges.size());
        for (Map.Entry<Long, Integer> e : edges.entrySet()) {
            long k = e.getKey();
            out.add(new int[] {(int) (k >> 32), (int) k, e.getValue()});
        }
        return out;
    }

    // ------------------------------------------------------------------ sincronizare client (bucati)

    /**
     * Imparte graful in bucati JSON pentru client: {rev, part, parts, worlds:[dim...], n:[[id,w,x,y,z,j]], e:[[a,b,mode]]}.
     * @param dimOf transforma numele lumii in cheia de dimensiune ("overworld","nether","end")
     */
    public List<JsonObject> toParts(int rev, int maxNodes, int maxEdges, Map<String, String> dimOf) {
        List<String> worldList = new ArrayList<>();
        Map<String, Integer> widx = new HashMap<>();
        List<Node> nl = new ArrayList<>(nodes.values());
        for (Node n : nl) {
            String dim = dimOf.containsKey(n.world) ? dimOf.get(n.world) : "overworld";
            if (!widx.containsKey(n.world)) {
                widx.put(n.world, worldList.size());
                worldList.add(dim);
            }
        }
        List<int[]> el = edgeList();
        int parts = Math.max(1, Math.max((nl.size() + maxNodes - 1) / maxNodes, (el.size() + maxEdges - 1) / maxEdges));
        List<JsonObject> out = new ArrayList<>(parts);
        for (int p = 0; p < parts; p++) {
            JsonObject o = new JsonObject();
            o.addProperty("rev", rev);
            o.addProperty("part", p);
            o.addProperty("parts", parts);
            JsonArray ws = new JsonArray();
            for (String w : worldList) ws.add(w);
            o.add("worlds", ws);
            JsonArray na = new JsonArray();
            for (int i = p * maxNodes; i < Math.min(nl.size(), (p + 1) * maxNodes); i++) {
                Node n = nl.get(i);
                JsonArray a = new JsonArray();
                a.add(n.id);
                a.add(widx.get(n.world));
                a.add(n.x);
                a.add(n.y);
                a.add(n.z);
                a.add(isJunction(n.id) ? 1 : 0);
                na.add(a);
            }
            o.add("n", na);
            JsonArray ea = new JsonArray();
            for (int i = p * maxEdges; i < Math.min(el.size(), (p + 1) * maxEdges); i++) {
                int[] e = el.get(i);
                JsonArray a = new JsonArray();
                a.add(e[0]);
                a.add(e[1]);
                a.add(e[2]);
                ea.add(a);
            }
            o.add("e", ea);
            out.add(o);
        }
        return out;
    }

    // ------------------------------------------------------------------ export / import

    public String toJson() {
        JsonObject o = new JsonObject();
        JsonArray na = new JsonArray();
        for (Node n : nodes.values()) {
            JsonArray a = new JsonArray();
            a.add(n.id);
            a.add(n.world);
            a.add(n.x);
            a.add(n.y);
            a.add(n.z);
            a.add(n.junction ? 1 : 0);
            na.add(a);
        }
        o.add("nodes", na);
        JsonArray ea = new JsonArray();
        for (int[] e : edgeList()) {
            JsonArray a = new JsonArray();
            a.add(e[0]);
            a.add(e[1]);
            a.add(e[2]);
            ea.add(a);
        }
        o.add("edges", ea);
        return o.toString();
    }

    /** Parseaza un export; arunca IllegalArgumentException daca e invalid (graful curent nu se atinge). */
    public static StreetGraph fromJson(String json) {
        try {
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            StreetGraph g = new StreetGraph();
            for (JsonElement el : o.getAsJsonArray("nodes")) {
                JsonArray a = el.getAsJsonArray();
                g.putNode(a.get(0).getAsInt(), a.get(1).getAsString(), a.get(2).getAsInt(), a.get(3).getAsInt(),
                    a.get(4).getAsInt(), a.size() > 5 && a.get(5).getAsInt() != 0);
            }
            for (JsonElement el : o.getAsJsonArray("edges")) {
                JsonArray a = el.getAsJsonArray();
                int x = a.get(0).getAsInt();
                int y = a.get(1).getAsInt();
                int mode = a.get(2).getAsInt();
                if (mode < 0 || mode > 2 || x == y || !g.nodes.containsKey(x) || !g.nodes.containsKey(y)) {
                    throw new IllegalArgumentException("muchie invalida " + x + "-" + y);
                }
                g.edges.put(key(x, y), mode);
            }
            return g;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("export invalid: " + e.getMessage(), e);
        }
    }
}
