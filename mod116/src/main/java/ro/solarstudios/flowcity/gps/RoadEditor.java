package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Sectiunea DRUMURI din editorul de harta: se deseneaza reteaua de drumuri (polilinii cu noduri), cu mutare/stergere/insertie,
 * sens unic, nume de strazi, undo/redo, conectarea locatiilor la drum, validare si scanare optionala pe server (cu previzualizare).
 * Lucreaza pe o ciorna locala; "SALVEAZA" trimite reteaua serverului (in bucati), care o valideaza si o scrie in gps/roads.json.
 */
final class RoadEditor {
    static final int TOOL_DRAW = 0;
    static final int TOOL_MOVE = 1;
    static final int TOOL_DEL = 2;
    static final int TOOL_ONEWAY = 3;
    static final int TOOL_NAME = 4;
    static final int TOOL_LOC = 5;
    private static final int VIOLET = 0xFF8B7BFF;
    private static final int GREEN = 0xFF2FBF71;

    private final MapEditorScreen s;

    RoadNet.Data d = new RoadNet.Data();
    boolean loaded;
    boolean dirty;
    int tool = TOOL_DRAW;
    int lastNode = -1;
    int dragNode = -1;
    private boolean dragPushed;
    boolean snap = true;
    int scroll;
    private float contentH;
    private final ArrayDeque<RoadNet.Data> undo = new ArrayDeque<>();
    private final ArrayDeque<RoadNet.Data> redo = new ArrayDeque<>();
    private int rev;
    private int statRev = -1;
    private int sComps;
    private int sIso;
    private final List<String> report = new ArrayList<>();

    // scanare
    final List<String> blocks = new ArrayList<>();
    int step = 3;
    int pickStage;
    double[] areaA;
    double[] areaB;
    boolean scanning;
    int scanPct;
    private final List<int[]> pvN = new ArrayList<>();
    private final List<int[]> pvE = new ArrayList<>();
    boolean hasPreview;
    private int pvLoaded;

    RoadEditor(MapEditorScreen s) {
        this.s = s;
    }

    // ------------------------------------------------------------------ date / istoric

    void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        d = RoadNet.live.copy();
        rev++;
    }

    /** Reteaua de pe server s-a schimbat. */
    void liveChanged() {
        if (!dirty) {
            d = RoadNet.live.copy();
            undo.clear();
            redo.clear();
            lastNode = -1;
            rev++;
        } else {
            s.say("Rețeaua de pe server s-a schimbat; ciorna ta a rămas neatinsă.", false);
        }
    }

    private void push() {
        undo.push(d.copy());
        while (undo.size() > 40) undo.removeLast();
        redo.clear();
        dirty = true;
        rev++;
    }

    void undo() {
        if (undo.isEmpty()) return;
        redo.push(d.copy());
        d = undo.pop();
        lastNode = -1;
        dirty = true;
        rev++;
    }

    void redo() {
        if (redo.isEmpty()) return;
        undo.push(d.copy());
        d = redo.pop();
        lastNode = -1;
        dirty = true;
        rev++;
    }

    void discard() {
        d = RoadNet.live.copy();
        undo.clear();
        redo.clear();
        dirty = false;
        lastNode = -1;
        rev++;
    }

    void onResult(String op, boolean ok, String msg) {
        if (op.equals("road.commit")) {
            if (ok) {
                dirty = false;
                undo.clear();
                redo.clear();
            }
        } else if (op.equals("scan.start")) {
            scanning = ok && msg.startsWith("Scanare pornită");
            if (!ok) scanning = false;
            scanPct = 0;
        } else if (op.equals("scan.cancel")) {
            scanning = false;
        }
    }

    // ------------------------------------------------------------------ geometrie

    private int nodeAt(double wx, double wz, double r) {
        int best = -1;
        double bd = r * r;
        for (java.util.Map.Entry<Integer, int[]> e : d.nodes.entrySet()) {
            double dx = e.getValue()[0] + 0.5 - wx;
            double dz = e.getValue()[1] + 0.5 - wz;
            double q = dx * dx + dz * dz;
            if (q < bd) {
                bd = q;
                best = e.getKey();
            }
        }
        return best;
    }

    /** Cea mai apropiata muchie: {index, t, px, pz, dist} sau null. */
    private double[] edgeAt(double wx, double wz, double r) {
        double[] best = null;
        for (int i = 0; i < d.edges.size(); i++) {
            int[] e = d.edges.get(i);
            int[] a = d.nodes.get(e[0]);
            int[] b = d.nodes.get(e[1]);
            if (a == null || b == null) continue;
            double ax = a[0] + 0.5;
            double az = a[1] + 0.5;
            double vx = b[0] - a[0];
            double vz = b[1] - a[1];
            double l2 = vx * vx + vz * vz;
            double t = l2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((wx - ax) * vx + (wz - az) * vz) / l2));
            double qx = ax + vx * t;
            double qz = az + vz * t;
            double dist = Math.hypot(wx - qx, wz - qz);
            if (dist <= r && (best == null || dist < best[4])) best = new double[] {i, t, qx, qz, dist};
        }
        return best;
    }

    /** Imparte muchia in doua intr-un nod nou (sau intoarce un capat daca punctul e langa el). */
    private int splitEdge(int ei, double px, double pz) {
        int[] e = d.edges.get(ei);
        int[] a = d.nodes.get(e[0]);
        int[] b = d.nodes.get(e[1]);
        int x = (int) Math.floor(px);
        int z = (int) Math.floor(pz);
        if (Math.abs(a[0] - x) + Math.abs(a[1] - z) <= 1) return e[0];
        if (Math.abs(b[0] - x) + Math.abs(b[1] - z) <= 1) return e[1];
        if (d.nodes.size() >= RoadNet.MAX_NODES) return -1;
        int id = d.addNode(x, z);
        d.edges.remove(ei);
        d.edges.add(new int[] {e[0], id, e[2], e[3]});
        d.edges.add(new int[] {id, e[1], e[2], e[3]});
        return id;
    }

    private int degree(int node) {
        int c = 0;
        for (int[] e : d.edges) if (e[0] == node || e[1] == node) c++;
        return c;
    }

    /** Muchiile din acelasi drum (prin noduri cu exact 2 muchii), pornind de la muchia data; orientate dupa a->b al muchiei de start. */
    private List<int[]> chain(int ei) {
        List<int[]> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int[] e0 = d.edges.get(ei);
        out.add(new int[] {ei, 1});
        seen.add(ei);
        for (int dir = 0; dir < 2; dir++) {
            int node = dir == 0 ? e0[1] : e0[0];
            int prevEdge = ei;
            boolean fwd = dir == 0;
            int guard = 0;
            while (degree(node) == 2 && guard++ < 5000) {
                int next = -1;
                for (int i = 0; i < d.edges.size(); i++) {
                    int[] e = d.edges.get(i);
                    if (i != prevEdge && (e[0] == node || e[1] == node)) next = i;
                }
                if (next < 0 || seen.contains(next)) break;
                seen.add(next);
                int[] e = d.edges.get(next);
                boolean along = e[0] == node;
                int other = along ? e[1] : e[0];
                // orientarea fata de directia de mers (inainte = a0->b0, inapoi = b0->a0)
                boolean sameAsStart = fwd ? along : !along;
                if (fwd) out.add(new int[] {next, sameAsStart ? 1 : 0});
                else out.add(0, new int[] {next, sameAsStart ? 1 : 0});
                prevEdge = next;
                node = other;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ interactiune pe harta

    double hitR() {
        return Math.max(1.5, 9.0 / s.zoom);
    }

    /** Apasare stanga pe harta. Intoarce true daca editorul preia gestul (ex. start de tragere a unui nod). */
    boolean press(double mx, double my) {
        ensureLoaded();
        double wx = s.wx(mx);
        double wz = s.wz(my);
        if (tool == TOOL_MOVE && pickStage == 0) {
            int n = nodeAt(wx, wz, hitR());
            if (n >= 0) {
                dragNode = n;
                dragPushed = false;
                return true;
            }
        }
        return false;
    }

    void drag(double mx, double my) {
        if (dragNode < 0) return;
        int[] n = d.nodes.get(dragNode);
        if (n == null) return;
        if (!dragPushed) {
            // copia de istoric se face inainte de prima mutare, dar cu nodul inca la locul lui
            push();
            dragPushed = true;
            n = d.nodes.get(dragNode);
        }
        n[0] = (int) Math.floor(s.wx(mx));
        n[1] = (int) Math.floor(s.wz(my));
        rev++;
    }

    void release() {
        dragNode = -1;
    }

    /** Click scurt (fara tragere) pe harta. */
    void click(double mx, double my) {
        ensureLoaded();
        double wx = s.wx(mx);
        double wz = s.wz(my);
        if (pickStage > 0) {
            double[] p = {Math.floor(wx), Math.floor(wz)};
            if (pickStage == 1) {
                areaA = p;
                areaB = null;
                pickStage = 2;
                s.say("Acum alege colțul opus.", true);
            } else {
                areaB = p;
                pickStage = 0;
                s.say("Zonă aleasă.", true);
            }
            return;
        }
        double r = hitR();
        switch (tool) {
            case TOOL_DRAW:
                draw(wx, wz, r);
                break;
            case TOOL_DEL: {
                int n = nodeAt(wx, wz, r);
                if (n >= 0) {
                    push();
                    d.removeNode(n);
                    if (lastNode == n) lastNode = -1;
                    return;
                }
                double[] e = edgeAt(wx, wz, r);
                if (e != null) {
                    push();
                    d.edges.remove((int) e[0]);
                }
                break;
            }
            case TOOL_ONEWAY: {
                double[] e = edgeAt(wx, wz, r);
                if (e == null) return;
                cycleOneWay((int) e[0], e[1]);
                break;
            }
            case TOOL_NAME: {
                double[] e = edgeAt(wx, wz, r);
                if (e == null) return;
                String name = s.fStreet.getValue().trim();
                push();
                int idx = d.nameIndex(name);
                for (int[] c : chain((int) e[0])) d.edges.get(c[0])[3] = idx;
                s.say(name.isEmpty() ? "Nume șters de pe drum." : "Nume aplicat pe tot drumul: " + name, true);
                break;
            }
            case TOOL_LOC: {
                GpsData.Loc l = s.locNear((float) mx, (float) my);
                if (l == null) {
                    s.say("Click pe o locație de pe hartă pentru a o conecta la drum.", false);
                    return;
                }
                push();
                String err = connectLoc(l);
                if (err != null) {
                    undo.pop();
                    s.say(err, false);
                } else {
                    s.say("Locația „" + l.name + "” e conectată la drum.", true);
                }
                break;
            }
            default:
                break;
        }
    }

    private void draw(double wx, double wz, double r) {
        int n = nodeAt(wx, wz, r);
        if (n < 0 && snap) {
            double[] e = edgeAt(wx, wz, r);
            if (e != null) {
                push();
                n = splitEdge((int) e[0], e[2], e[3]);
                if (n < 0) {
                    undo.pop();
                    s.say("Limita de noduri atinsă.", false);
                    return;
                }
                connectFrom(n);
                return;
            }
        }
        if (n >= 0) {
            if (n == lastNode) return;
            push();
            connectFrom(n);
            return;
        }
        if (d.nodes.size() >= RoadNet.MAX_NODES) {
            s.say("Limita de " + RoadNet.MAX_NODES + " noduri atinsă.", false);
            return;
        }
        push();
        int id = d.addNode((int) Math.floor(wx), (int) Math.floor(wz));
        connectFrom(id);
    }

    private void connectFrom(int node) {
        if (lastNode >= 0 && lastNode != node && d.nodes.containsKey(lastNode)) {
            if (d.edges.size() >= RoadNet.MAX_EDGES) {
                s.say("Limita de " + RoadNet.MAX_EDGES + " segmente atinsă.", false);
            } else {
                d.addEdge(lastNode, node, 0, d.nameIndex(s.fStreet.getValue()));
            }
        }
        lastNode = node;
    }

    /** Click dreapta: termina lantul, iar daca nu e niciunul, sterge nodul/muchia de sub cursor. */
    void rightClick(double mx, double my) {
        ensureLoaded();
        if (pickStage > 0) {
            pickStage = 0;
            return;
        }
        if (lastNode >= 0) {
            lastNode = -1;
            return;
        }
        double wx = s.wx(mx);
        double wz = s.wz(my);
        double r = hitR();
        int n = nodeAt(wx, wz, r);
        if (n >= 0) {
            push();
            d.removeNode(n);
            return;
        }
        double[] e = edgeAt(wx, wz, r);
        if (e != null) {
            push();
            d.edges.remove((int) e[0]);
        }
    }

    boolean escape() {
        if (pickStage > 0) {
            pickStage = 0;
            return true;
        }
        if (lastNode >= 0) {
            lastNode = -1;
            return true;
        }
        return false;
    }

    private void cycleOneWay(int ei, double t) {
        List<int[]> ch = chain(ei);
        int[] e0 = d.edges.get(ei);
        // stare curenta a muchiei apasate: 0 = dublu sens, 1 = a->b, 2 = b->a (relativ la directia lantului a0->b0)
        int cur = (e0[2] & 1) == 0 ? 0 : 1;
        // click: dublu sens <-> sens unic; Shift+click: inverseaza sensul unui drum cu sens unic
        int nextState = cur == 0 ? 1 : (net.minecraft.client.gui.screen.Screen.hasShiftDown() ? 2 : 0);
        push();
        for (int[] c : ch) {
            int[] e = d.edges.get(c[0]);
            boolean sameDir = c[1] == 1;
            if (nextState == 0) {
                e[2] &= ~1;
                continue;
            }
            // vrem sensul lantului (nextState 1) sau invers (2)
            boolean wantAB = nextState == 1 ? sameDir : !sameDir;
            if (!wantAB) {
                int tmp = e[0];
                e[0] = e[1];
                e[1] = tmp;
            }
            e[2] |= 1;
        }
        s.say(nextState == 0 ? "Drum cu dublu sens." : "Sens unic" + (ch.size() > 1 ? " pe " + ch.size() + " segmente" : "") + " (săgețile arată sensul).", true);
    }

    /** Adauga un nod la locatie si il leaga de cel mai apropiat drum. Intoarce un mesaj de eroare sau null. */
    String connectLoc(GpsData.Loc l) {
        if (d.edges.isEmpty()) return "Nu există încă drumuri; desenează întâi rețeaua.";
        double[] e = edgeAt(l.x, l.z, 300);
        if (e == null) return "Nu e niciun drum la mai puțin de 300 de blocuri de locație.";
        if (d.nodes.size() + 2 > RoadNet.MAX_NODES) return "Limita de noduri atinsă.";
        int near = nodeAt(l.x, l.z, 1.2);
        int ln = near >= 0 ? near : d.addNode((int) Math.floor(l.x), (int) Math.floor(l.z));
        int pn = splitEdge((int) e[0], e[2], e[3]);
        if (pn < 0) return "Limita de noduri atinsă.";
        if (pn != ln) d.addEdge(ln, pn, 0, -1);
        return null;
    }

    void connectAllLocs() {
        ensureLoaded();
        if (d.edges.isEmpty()) {
            s.say("Desenează întâi drumurile.", false);
            return;
        }
        push();
        int done = 0;
        String dim = s.dim();
        for (GpsData.Loc l : GpsData.all()) {
            if (!l.dim.equals(dim)) continue;
            double[] e = edgeAt(l.x, l.z, 6);
            if (e != null) continue;
            if (connectLoc(l) == null) done++;
        }
        if (done == 0) undo.pop();
        s.say(done == 0 ? "Toate locațiile au deja drum aproape (≤ 6 blocuri)." : done + " locații conectate la drum.", done > 0);
    }

    // ------------------------------------------------------------------ validare

    private void stats() {
        if (statRev == rev) return;
        statRev = rev;
        sComps = d.components();
        sIso = d.isolated();
    }

    private void verify() {
        report.clear();
        if (d.edges.isEmpty()) {
            report.add("Rețeaua e goală.");
            return;
        }
        RoadNet.Graph g = new RoadNet.Graph(d);
        String dim = s.dim();
        int far = 0;
        int total = 0;
        StringBuilder names = new StringBuilder();
        for (GpsData.Loc l : GpsData.all()) {
            if (!l.dim.equals(dim) || l.hidden || !l.enabled) continue;
            total++;
            if (g.nearest(l.x, l.z, 40) == null) {
                far++;
                if (names.length() < 90) names.append(names.length() == 0 ? "" : ", ").append(l.name);
            }
        }
        report.add(far == 0 ? "Toate cele " + total + " locații au drum la ≤ 40 blocuri." : far + " din " + total + " locații nu au drum la ≤ 40 blocuri: " + names + (far > 3 ? "…" : ""));
        report.add(sComps <= 1 ? "Rețeaua e conexă." : "Rețea cu " + sComps + " părți separate: rutele nu trec între ele.");
        if (sIso > 0) report.add(sIso + " noduri fără niciun segment.");
    }

    // ------------------------------------------------------------------ salvare

    void save() {
        ensureLoaded();
        if (d.nodes.size() > RoadNet.MAX_NODES || d.edges.size() > RoadNet.MAX_EDGES) {
            s.say("Rețea prea mare.", false);
            return;
        }
        stats();
        final RoadNet.Data c = d.copy();
        c.compactNames();
        Runnable go = () -> {
            JsonObject b = new JsonObject();
            b.addProperty("nodes", c.nodes.size());
            b.addProperty("edges", c.edges.size());
            JsonArray nm = new JsonArray();
            for (String n : c.names) nm.add(n);
            b.add("names", nm);
            GpsClient.sendEdit("road.begin", b);
            JsonArray a = new JsonArray();
            int cnt = 0;
            for (java.util.Map.Entry<Integer, int[]> e : c.nodes.entrySet()) {
                a.add(e.getKey());
                a.add(e.getValue()[0]);
                a.add(e.getValue()[1]);
                if (++cnt >= 600) {
                    JsonObject o = new JsonObject();
                    o.add("a", a);
                    GpsClient.sendEdit("road.nodes", o);
                    a = new JsonArray();
                    cnt = 0;
                }
            }
            if (cnt > 0) {
                JsonObject o = new JsonObject();
                o.add("a", a);
                GpsClient.sendEdit("road.nodes", o);
            }
            a = new JsonArray();
            cnt = 0;
            for (int[] e : c.edges) {
                a.add(e[0]);
                a.add(e[1]);
                a.add(e[2]);
                a.add(e[3]);
                if (++cnt >= 450) {
                    JsonObject o = new JsonObject();
                    o.add("a", a);
                    GpsClient.sendEdit("road.edges", o);
                    a = new JsonArray();
                    cnt = 0;
                }
            }
            if (cnt > 0) {
                JsonObject o = new JsonObject();
                o.add("a", a);
                GpsClient.sendEdit("road.edges", o);
            }
            s.send("road.commit", new JsonObject());
        };
        if (sComps > 1 || sIso > 0) s.confirm("Rețeaua are " + sComps + " părți separate. Salvezi oricum?", "DA, SALVEAZĂ", go);
        else go.run();
    }

    // ------------------------------------------------------------------ scanare

    void addBlockUnderPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        BlockPos p = mc.player.blockPosition().below();
        BlockState st = mc.level.getBlockState(p);
        if (st.isAir()) st = mc.level.getBlockState(p.below());
        addBlock(st);
    }

    void addBlockTargeted() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        RayTraceResult hr = mc.hitResult;
        if (!(hr instanceof BlockRayTraceResult) || hr.getType() != RayTraceResult.Type.BLOCK) {
            s.say("Nu te uiți la niciun bloc (închide editorul și privește drumul, sau folosește „bloc de sub mine”).", false);
            return;
        }
        addBlock(mc.level.getBlockState(((BlockRayTraceResult) hr).getBlockPos()));
    }

    private void addBlock(BlockState st) {
        ResourceLocation rl = st.getBlock().getRegistryName();
        if (rl == null || st.isAir()) {
            s.say("Bloc invalid.", false);
            return;
        }
        // blocurile vanilla merg dupa nume (STONE); cele din alte moduri (Saro's Road Blocks etc.) dupa cheia completa
        String name = "minecraft".equals(rl.getNamespace())
            ? rl.getPath().toUpperCase(java.util.Locale.ROOT)
            : rl.toString();
        if (blocks.contains(name)) {
            s.say(name + " e deja în listă.", false);
            return;
        }
        if (blocks.size() >= 24) {
            s.say("Maxim 24 de tipuri de bloc.", false);
            return;
        }
        blocks.add(name);
        s.say("Adăugat: " + name, true);
    }

    void areaFromScreen() {
        double x0 = s.wx(0);
        double x1 = s.wx(s.areaRight);
        double z0 = s.wz(0);
        double z1 = s.wz(s.height);
        double cx = (x0 + x1) / 2.0;
        double cz = (z0 + z1) / 2.0;
        double hw = Math.min(300, (x1 - x0) / 2.0);
        double hh = Math.min(300, (z1 - z0) / 2.0);
        areaA = new double[] {Math.floor(cx - hw), Math.floor(cz - hh)};
        areaB = new double[] {Math.floor(cx + hw), Math.floor(cz + hh)};
        pickStage = 0;
    }

    private long areaPoints() {
        if (areaA == null || areaB == null) return 0;
        long nx = (long) (Math.abs(areaB[0] - areaA[0]) / step) + 1;
        long nz = (long) (Math.abs(areaB[1] - areaA[1]) / step) + 1;
        return nx * nz;
    }

    void startScan() {
        if (areaA == null || areaB == null) {
            s.say("Alege întâi zona de scanat.", false);
            return;
        }
        if (blocks.isEmpty()) {
            s.say("Alege tipurile de bloc ale drumului (bloc de sub mine / țintit).", false);
            return;
        }
        if (areaPoints() > 250000) {
            s.say("Zonă prea mare pentru pasul " + step + ".", false);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("x1", areaA[0]);
        o.addProperty("z1", areaA[1]);
        o.addProperty("x2", areaB[0]);
        o.addProperty("z2", areaB[1]);
        o.addProperty("step", step);
        JsonArray b = new JsonArray();
        for (String n : blocks) b.add(n);
        o.add("blocks", b);
        hasPreview = false;
        pvN.clear();
        pvE.clear();
        s.send("scan.start", o);
    }

    void cancelScan() {
        scanning = false;
        s.send("scan.cancel", new JsonObject());
    }

    void scanProgress(int pct) {
        scanPct = pct;
    }

    void scanResult(JsonObject o) {
        int part = (int) JsonClientModule.num(o, "part", 0);
        int parts = (int) JsonClientModule.num(o, "parts", 1);
        if (part == 0) {
            pvN.clear();
            pvE.clear();
        }
        if (o.has("n")) {
            JsonArray a = o.getAsJsonArray("n");
            for (int i = 0; i + 1 < a.size(); i += 2) pvN.add(new int[] {a.get(i).getAsInt(), a.get(i + 1).getAsInt()});
        }
        if (o.has("e")) {
            JsonArray a = o.getAsJsonArray("e");
            for (int i = 0; i + 1 < a.size(); i += 2) pvE.add(new int[] {a.get(i).getAsInt(), a.get(i + 1).getAsInt()});
        }
        pvLoaded = (int) JsonClientModule.num(o, "loaded", 0);
        if (part >= parts - 1) {
            scanning = false;
            hasPreview = true;
            s.say("Previzualizare: " + pvN.size() + " noduri, " + pvE.size() + " segmente (" + pvLoaded + "% din zonă era încărcată).", pvLoaded >= 50);
        }
    }

    private void acceptPreview() {
        ensureLoaded();
        if (d.nodes.size() + pvN.size() > RoadNet.MAX_NODES || d.edges.size() + pvE.size() > RoadNet.MAX_EDGES) {
            s.say("Prea mare pentru limita rețelei; micșorează zona.", false);
            return;
        }
        push();
        int[] map = new int[pvN.size()];
        // noduri apropiate de cele existente se reutilizeaza (2.5 blocuri), ca scanarea sa se lipeasca de ce ai desenat
        for (int i = 0; i < pvN.size(); i++) {
            int[] p = pvN.get(i);
            int ex = nodeAt(p[0] + 0.5, p[1] + 0.5, 2.5);
            map[i] = ex >= 0 ? ex : d.addNode(p[0], p[1]);
        }
        int added = 0;
        for (int[] e : pvE) {
            if (e[0] < 0 || e[1] < 0 || e[0] >= map.length || e[1] >= map.length) continue;
            if (d.addEdge(map[e[0]], map[e[1]], 0, -1)) added++;
        }
        hasPreview = false;
        pvN.clear();
        pvE.clear();
        s.say("Adăugat în ciornă: " + added + " segmente. Verifică, corectează și SALVEAZĂ.", true);
    }

    // ------------------------------------------------------------------ desen pe harta

    void drawMap(GuiGraphics g, int mx, int my, float t) {
        boolean editing = s.tab == MapEditorScreen.T_ROAD;
        if (!editing && !(GpsPrefs.layerOn("roads") && RoadNet.hasRoads())) return;
        RoadNet.Data data = editing ? d : RoadNet.live;
        if (editing) ensureLoaded();
        float z = s.zoom;
        float th = Math.max(1.4f, Math.min(4.2f, z * 1.6f));
        Set<Integer> hot = null;
        RoadDraw.network(g, data, s.camX, s.camZ, z, s.mapCx, s.mapCy, s.width, s.height, editing ? 0xFF4F9BE6 : 0x887F93AD, th, z >= 0.6f, hot);
        RoadDraw.names(g, data, s.camX, s.camZ, z, s.mapCx, s.mapCy, s.width, s.height);
        if (!editing) return;
        boolean mapHover = !s.overPanelPublic(mx, my);
        double wx = s.wx(mx);
        double wz = s.wz(my);
        int hoverNode = mapHover ? nodeAt(wx, wz, hitR()) : -1;
        // noduri
        if (z >= 0.3f) {
            float r = Math.max(2.4f, Math.min(5f, z * 2f));
            int n = 0;
            for (java.util.Map.Entry<Integer, int[]> e : d.nodes.entrySet()) {
                float x = s.sx(e.getValue()[0] + 0.5);
                float y = s.sy(e.getValue()[1] + 0.5);
                if (x < -8 || y < -8 || x > s.width + 8 || y > s.height + 8) continue;
                int id = e.getKey();
                int col = id == lastNode ? 0xFFFFC857 : id == hoverNode ? 0xFFFFFFFF : degree1(id) ? 0xFFF0584C : 0xFFBFE0FF;
                Gfx.disc(g, x, y, r + 1f, 0xFF0A1220);
                Gfx.disc(g, x, y, r, col);
                if (++n > 6000) break;
            }
        }
        // banda elastica de la ultimul nod
        if (tool == TOOL_DRAW && lastNode >= 0 && mapHover) {
            int[] ln = d.nodes.get(lastNode);
            if (ln != null) Gfx.line(g, s.sx(ln[0] + 0.5), s.sy(ln[1] + 0.5), mx, my, 2f, 0xAAFFC857);
        }
        // muchia de sub cursor
        if (mapHover && (tool == TOOL_DEL || tool == TOOL_ONEWAY || tool == TOOL_NAME || (tool == TOOL_DRAW && snap && hoverNode < 0))) {
            double[] e = edgeAt(wx, wz, hitR());
            if (e != null) {
                int[] ed = d.edges.get((int) e[0]);
                int[] a = d.nodes.get(ed[0]);
                int[] b = d.nodes.get(ed[1]);
                if (a != null && b != null) {
                    if (tool == TOOL_DRAW) Gfx.disc(g, s.sx(e[2]), s.sy(e[3]), 4.5f, 0xFFFFC857);
                    else Gfx.line(g, s.sx(a[0] + 0.5), s.sy(a[1] + 0.5), s.sx(b[0] + 0.5), s.sy(b[1] + 0.5), th + 2f, tool == TOOL_DEL ? 0xCCE5484D : 0xCCFFC857);
                }
            }
        }
        // previzualizarea scanarii
        if (hasPreview) {
            for (int[] e : pvE) {
                if (e[0] >= pvN.size() || e[1] >= pvN.size()) continue;
                int[] a = pvN.get(e[0]);
                int[] b = pvN.get(e[1]);
                float ax = s.sx(a[0] + 0.5);
                float ay = s.sy(a[1] + 0.5);
                float bx = s.sx(b[0] + 0.5);
                float by = s.sy(b[1] + 0.5);
                if ((ax < 0 && bx < 0) || (ay < 0 && by < 0) || (ax > s.width && bx > s.width) || (ay > s.height && by > s.height)) continue;
                Gfx.line(g, ax, ay, bx, by, th, 0xFF35E0A0);
            }
        }
        // zona de scanat
        if (areaA != null) {
            double[] b = areaB != null ? areaB : (pickStage == 2 && mapHover ? new double[] {Math.floor(wx), Math.floor(wz)} : null);
            if (b != null) {
                float x0 = s.sx(Math.min(areaA[0], b[0]));
                float y0 = s.sy(Math.min(areaA[1], b[1]));
                float x1 = s.sx(Math.max(areaA[0], b[0]) + 1);
                float y1 = s.sy(Math.max(areaA[1], b[1]) + 1);
                g.fill(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), 0x1835E0A0);
                Gfx.line(g, x0, y0, x1, y0, 1.6f, 0xFF35E0A0);
                Gfx.line(g, x1, y0, x1, y1, 1.6f, 0xFF35E0A0);
                Gfx.line(g, x1, y1, x0, y1, 1.6f, 0xFF35E0A0);
                Gfx.line(g, x0, y1, x0, y0, 1.6f, 0xFF35E0A0);
            }
        }
        if (pickStage > 0 && mapHover) {
            Gfx.line(g, mx - 10, my, mx + 10, my, 1.4f, 0xFF35E0A0);
            Gfx.line(g, mx, my - 10, mx, my + 10, 1.4f, 0xFF35E0A0);
        }
    }

    private final java.util.HashMap<Integer, Integer> degMap = new java.util.HashMap<>();
    private int degRev = -1;

    private boolean degree1(int id) {
        if (degRev != rev) {
            degRev = rev;
            degMap.clear();
            for (int[] e : d.edges) {
                degMap.merge(e[0], 1, Integer::sum);
                degMap.merge(e[1], 1, Integer::sum);
            }
        }
        Integer c = degMap.get(id);
        return c != null && c == 1;
    }

    String hint() {
        if (pickStage == 1) return "Click pe hartă: primul colț al zonei de scanat  (ESC: renunță)";
        if (pickStage == 2) return "Click pe hartă: colțul opus al zonei  (ESC: renunță)";
        switch (tool) {
            case TOOL_DRAW:
                return "Click: nod nou legat de precedentul  ·  click pe drum: îl împarte  ·  click dreapta: termină lanțul";
            case TOOL_MOVE:
                return "Trage un nod ca să-l muți  ·  click dreapta pe nod/segment: șterge";
            case TOOL_DEL:
                return "Click pe un nod (șterge și segmentele lui) sau pe un segment";
            case TOOL_ONEWAY:
                return "Click pe un segment: dublu sens ↔ sens unic (pe tot drumul)  ·  Shift+click: inversează sensul";
            case TOOL_NAME:
                return "Scrie numele străzii în panou, apoi click pe un segment: îl pune pe tot drumul";
            default:
                return "Click pe o locație: îi adaugă un nod și o leagă de cel mai apropiat drum";
        }
    }

    // ------------------------------------------------------------------ panou

    private boolean vis(float y, float h, float top, float lim) {
        return y >= top && y + h <= lim;
    }

    void drawPanel(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        ensureLoaded();
        stats();
        float top = y;
        float lim = bottom - 86f;
        s.formLimit = lim;
        float maxScroll = Math.max(0f, contentH - (lim - top));
        scroll = (int) Math.max(0, Math.min(scroll, maxScroll));
        float cy = y - scroll;
        final float x0 = x;
        float half = (w - 6f) / 2f;
        float third = (w - 8f) / 3f;

        if (vis(cy, 12, top, lim)) Gfx.text(g, "UNELTE", x, cy + 8, 7.4f, Sk.ACC, 0, true, 1.4f);
        cy += 16;
        String[] t1 = {"DESENEAZĂ", "MUTĂ", "ȘTERGE"};
        String[] t2 = {"SENS UNIC", "NUME STRADĂ", "CONECTEAZĂ LOC."};
        for (int i = 0; i < 3; i++) {
            final int ti = i;
            if (vis(cy, 26, top, lim)) s.btn(g, t1[i], x0 + i * (third + 4), cy, third, 26, tool == i ? 4 : 1, mx, my, () -> setTool(ti));
        }
        cy += 30;
        for (int i = 0; i < 3; i++) {
            final int ti = i + 3;
            if (vis(cy, 26, top, lim)) s.btn(g, t2[i], x0 + i * (third + 4), cy, third, 26, tool == ti ? 4 : 1, mx, my, () -> setTool(ti));
        }
        cy += 30;
        if (vis(cy, 22, top, lim)) s.toggle(g, "Snap la drum/nod", x0, cy, half, snap, mx, my, () -> snap = !snap);
        if (vis(cy, 22, top, lim)) {
            s.btn(g, "↶ ANULEAZĂ", x0 + half + 6, cy, half / 2f - 2, 22, undo.isEmpty() ? 3 : 1, mx, my, this::undo);
            s.btn(g, "REFĂ ↷", x0 + half + 6 + half / 2f + 2, cy, half / 2f - 2, 22, redo.isEmpty() ? 3 : 1, mx, my, this::redo);
        }
        cy += 28;
        if (vis(cy, 36, top, lim)) {
            s.label(g, "NUME STRADĂ (pentru segmentele noi / unealta NUME)", x0, cy);
            s.fld(g, s.fStreet, x0, cy + 10, w, 22, mx, my);
        } else {
            s.fStreet.x = -1000;
            s.fStreet.y = -1000;
        }
        cy += 40;

        // statistici + avertismente
        if (vis(cy, 40, top, lim)) {
            Gfx.text(g, d.nodes.size() + " noduri  ·  " + d.edges.size() + " segmente  ·  " + sComps + (sComps == 1 ? " parte" : " părți"), x0, cy + 9, 7.8f, Sk.WHITE, 0, true, 0f);
            Gfx.text(g, "Pe server: " + RoadNet.live.nodes.size() + " noduri, " + RoadNet.live.edges.size() + " segmente" + (dirty ? "  ·  MODIFICAT NESALVAT" : ""), x0, cy + 21, 6.8f, dirty ? Sk.WARN : Sk.FAINT, 0);
            if (sComps > 1) Gfx.text(g, "Atenție: rețea în " + sComps + " părți separate", x0, cy + 33, 6.8f, Sk.WARN, 0);
            else if (sIso > 0) Gfx.text(g, sIso + " noduri izolate (fără segmente)", x0, cy + 33, 6.8f, Sk.WARN, 0);
        }
        cy += 44;
        if (vis(cy, 24, top, lim)) s.btn(g, "VERIFICĂ REȚEAUA ȘI LOCAȚIILE", x0, cy, w, 24, 1, mx, my, this::verify);
        cy += 28;
        for (String r : report) {
            if (vis(cy, 11, top, lim)) Gfx.text(g, MapEditorScreen.clip(r, 6.8f, false, w), x0, cy + 8, 6.8f, r.startsWith("Toate") || r.endsWith("conexă.") ? GREEN : Sk.WARN, 0);
            cy += 11;
        }
        if (vis(cy, 24, top, lim)) s.btn(g, "CONECTEAZĂ LOCAȚIILE NECONECTATE", x0, cy, w, 24, 1, mx, my, () -> s.confirm("Conectez toate locațiile fără drum aproape?", "DA, CONECTEAZĂ", this::connectAllLocs));
        cy += 32;

        // scanare
        if (vis(cy, 12, top, lim)) Gfx.text(g, "SCANARE AUTOMATĂ (opțional)", x0, cy + 8, 7.4f, Sk.ACC, 0, true, 1.4f);
        cy += 14;
        if (vis(cy, 20, top, lim)) {
            Gfx.text(g, "Serverul caută drumul după blocurile alese, în chunk-urile încărcate;", x0, cy + 7, 6.4f, Sk.FAINT, 0);
            Gfx.text(g, "tu verifici previzualizarea, apoi o accepți și corectezi.", x0, cy + 16, 6.4f, Sk.FAINT, 0);
        }
        cy += 22;
        if (vis(cy, 24, top, lim)) {
            s.btn(g, "+ BLOC DE SUB MINE", x0, cy, half, 24, 1, mx, my, this::addBlockUnderPlayer);
            s.btn(g, "+ BLOC ȚINTIT", x0 + half + 6, cy, half, 24, 1, mx, my, this::addBlockTargeted);
        }
        cy += 28;
        // lista de blocuri (chips pe randuri)
        float cx = x0;
        float rowH = 20f;
        int removeIdx = -1;
        boolean any = false;
        for (int i = 0; i < blocks.size(); i++) {
            String name = blocks.get(i).toLowerCase(java.util.Locale.ROOT);
            float cw = Gfx.width(name, 6.8f, false) + 28f;
            if (cx + cw > x0 + w) {
                cx = x0;
                cy += rowH + 3;
            }
            if (vis(cy, rowH, top, lim)) {
                final int idx = i;
                boolean hv = Sk.in(mx, my, cx, cy, cw, rowH);
                Gfx.rrect(g, cx, cy, cw, rowH, 5, hv ? 0xFF22344D : 0xFF14202F);
                Gfx.text(g, name, cx + 7, cy + 13.4f, 6.8f, Sk.WHITE, 0);
                Gfx.text(g, "×", cx + cw - 11, cy + 14f, 9f, Sk.BAD, 1, true, 0f);
                s.hitF(cx, cy, cw, rowH, () -> blocks.remove(idx));
            }
            cx += cw + 4;
            any = true;
        }
        if (!any && vis(cy, 12, top, lim)) Gfx.text(g, "Niciun bloc ales (ex. beton negru + linii albe).", x0, cy + 9, 6.8f, Sk.MUTED, 0);
        cy += rowH + 6;
        if (vis(cy, 32, top, lim)) {
            s.selector(g, "PAS DE EȘANTIONARE (blocuri; drumuri mai înguste decât pasul se pot pierde)", String.valueOf(step), x0, cy, w, mx, my, () -> step = Math.max(2, step - 1), () -> step = Math.min(8, step + 1));
        }
        cy += 38;
        if (vis(cy, 24, top, lim)) {
            s.btn(g, "ZONA = CE SE VEDE", x0, cy, half, 24, 1, mx, my, this::areaFromScreen);
            s.btn(g, pickStage > 0 ? "ALEGE COLȚURILE…" : "ALEGE 2 COLȚURI", x0 + half + 6, cy, half, 24, pickStage > 0 ? 4 : 1, mx, my, () -> {
                pickStage = 1;
                areaA = null;
                areaB = null;
                s.say("Click pe hartă: primul colț.", true);
            });
        }
        cy += 28;
        if (vis(cy, 12, top, lim)) {
            if (areaA != null && areaB != null) {
                long pts = areaPoints();
                Gfx.text(g, Math.round(Math.abs(areaB[0] - areaA[0])) + "×" + Math.round(Math.abs(areaB[1] - areaA[1])) + " blocuri  ·  " + pts + " puncte", x0, cy + 9, 7f, pts > 250000 ? Sk.BAD : Sk.WHITE, 0);
            } else {
                Gfx.text(g, "Zonă nealeasă", x0, cy + 9, 7f, Sk.MUTED, 0);
            }
        }
        cy += 16;
        if (vis(cy, 26, top, lim)) {
            if (scanning) s.btn(g, "OPREȘTE SCANAREA (" + scanPct + "%)", x0, cy, w, 26, 2, mx, my, this::cancelScan);
            else s.btn(g, "SCANEAZĂ ZONA", x0, cy, w, 26, 0, mx, my, this::startScan);
        }
        cy += 30;
        if (hasPreview) {
            if (vis(cy, 26, top, lim)) {
                s.btn(g, "ACCEPTĂ (" + pvN.size() + " noduri)", x0, cy, half, 26, 0, mx, my, this::acceptPreview);
                s.btn(g, "RESPINGE", x0 + half + 6, cy, half, 26, 1, mx, my, () -> {
                    hasPreview = false;
                    pvN.clear();
                    pvE.clear();
                });
            }
            cy += 30;
        }
        contentH = cy - (y - scroll) + 6;

        // zona fixa de jos: salvare
        float by = bottom - 80f;
        Gfx.rrect(g, x0, by - 4, w, 1, 0, 0xFF22344D);
        s.formLimit = Float.MAX_VALUE;
        s.btn(g, dirty ? "SALVEAZĂ REȚEAUA PE SERVER" : "FĂRĂ MODIFICĂRI", x0, by + 2, w, 34, dirty && !s.pending ? 0 : 3, mx, my, this::save);
        float tw = (w - 8f) / 3f;
        s.btn(g, "REVINO", x0, by + 42, tw, 28, 1, mx, my, () -> {
            if (dirty) s.confirm("Renunți la modificările nesalvate?", "DA, RENUNȚ", this::discard);
            else discard();
        });
        s.btn(g, "ANULEAZĂ SALVAREA", x0 + tw + 4, by + 42, tw, 28, 1, mx, my, () -> s.confirm("Revii la versiunea salvată anterior pe server?", "DA, REVINO", () -> s.send("road.restore", new JsonObject())));
        s.btn(g, "ȘTERGE TOT", x0 + 2 * (tw + 4), by + 42, tw, 28, 2, mx, my, () -> s.confirm("Ștergi TOATĂ rețeaua de pe server?", "DA, ȘTERGE", () -> s.send("road.clear", new JsonObject())));
    }

    private void setTool(int t) {
        tool = t;
        lastNode = -1;
        pickStage = 0;
    }

    void scrollBy(int d) {
        scroll = Math.max(0, scroll + d);
    }

    /** Folosit de {@link MapEditorScreen} pentru taste (Ctrl+Z / Ctrl+Y). */
    boolean key(int key, int mods) {
        boolean ctrl = (mods & org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL) != 0;
        if (!ctrl) return false;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
            if ((mods & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) redo();
            else undo();
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
            redo();
            return true;
        }
        return false;
    }
}
