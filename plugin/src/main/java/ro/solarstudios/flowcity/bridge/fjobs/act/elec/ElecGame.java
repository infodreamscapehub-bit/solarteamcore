package ro.solarstudios.flowcity.bridge.fjobs.act.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Logica serverului pentru cele patru mini-jocuri ale electricianului. Serverul genereaza problema si solutia, tine faza curenta,
 * greselile si timpii, iar clientul doar afiseaza si trimite intentii (op). Un client modificat nu poate sari etape, nu poate
 * termina mai repede decat minimul plauzibil si nu poate depasi numarul de greseli. Limita (documentata): datele puzzle-ului trebuie
 * sa ajunga la client ca sa fie afisate, deci un client special facut le-ar putea rezolva automat; timpii minimi si greselile
 * limitate opresc rezolvarea instantanee, iar la calibrare serverul reface curba tinta si respinge urmele "perfecte".
 */
public final class ElecGame {
    /** Ce are jucatorul la el (verificat pe server la fiecare operatie care conteaza). */
    public interface Ctx {
        boolean hasMultimeter();

        int fuses();

        /** Consuma o siguranta din inventar. */
        void useFuse();
    }

    /** Raspunsul unei operatii. */
    public static final class Reply {
        public boolean ok;
        public String text = "";
        public boolean fin;
        public boolean win;
        public final JsonObject extra = new JsonObject();

        static Reply of(boolean ok, String text) {
            Reply r = new Reply();
            r.ok = ok;
            r.text = text;
            return r;
        }
    }

    public static final int[] AMPS = {6, 10, 16, 20, 25, 32, 40, 50};
    public static final String[] LEFT = {"L1", "L2", "L3", "N", "PE", "T1"};
    public static final String[] RIGHT = {"Priza", "Lumina", "Pompa", "Motor", "Senzor", "Releu"};

    public final String kind;
    public final long start = System.currentTimeMillis();
    public final int diff;
    public int mistakes;
    public int maxMistakes;
    public long limitMs;
    public boolean over;
    public double quality = 1;

    private final Random r;
    private final String id;
    // circuit
    private int n;
    private int[] sol;
    // fuse
    private int k;
    private int bad;
    private int phase;
    private int correctAmp;
    private double nominal;
    private long[] seedRes;
    // seq
    private int[] seq;
    private int round;
    private int symbols;
    private int showPer;
    private long roundStart;
    // calib
    private double dur;
    private double a1;
    private double a2;
    private double p1;
    private double p2;
    private double amp1;
    private double amp2;
    private double half;
    private double need;
    private long runStart;

    public ElecGame(String kind, int diff, boolean pliers, Random r, String id) {
        this.kind = kind;
        this.diff = Math.max(1, Math.min(5, diff));
        this.r = r;
        this.id = id;
        limitMs = 150_000L;
        switch (kind) {
            case "circuit":
                maxMistakes = 2 + (pliers ? 1 : 0);
                break;
            case "fuse":
                maxMistakes = 3;
                break;
            case "seq":
                maxMistakes = 3;
                break;
            default:
                maxMistakes = 1;
        }
        init();
    }

    public String id() {
        return id;
    }

    private void init() {
        switch (kind) {
            case "circuit":
                n = 3 + diff / 2;
                List<Integer> cols = new ArrayList<>();
                for (int i = 0; i < 6; i++) cols.add(i);
                Collections.shuffle(cols, r);
                List<Integer> right = new ArrayList<>(cols.subList(0, n));
                Collections.shuffle(right, r);
                this.rightCols = right;
                this.leftCols = new ArrayList<>(cols.subList(0, n));
                sol = new int[n];
                for (int i = 0; i < n; i++) sol[i] = right.indexOf(leftCols.get(i));
                break;
            case "fuse":
                k = 4 + diff / 2;
                bad = r.nextInt(k);
                int ci = 1 + r.nextInt(5);
                correctAmp = AMPS[ci];
                int prev = AMPS[ci - 1];
                nominal = Math.round((prev + (correctAmp - prev) * (0.3 + r.nextDouble() * 0.62)) * 10) / 10.0;
                if (nominal <= prev) nominal = prev + 0.5;
                phase = 0;
                seedRes = new long[k];
                for (int i = 0; i < k; i++) seedRes[i] = 15 + r.nextInt(41);
                break;
            case "seq":
                symbols = diff >= 4 ? 6 : 4;
                int len = 4 + diff;
                seq = new int[len];
                for (int i = 0; i < len; i++) seq[i] = r.nextInt(symbols);
                round = 3;
                showPer = Math.max(380, 760 - 70 * diff);
                roundStart = System.currentTimeMillis();
                break;
            default:
                newCalib();
        }
    }

    private List<Integer> leftCols;
    private List<Integer> rightCols;

    private void newCalib() {
        dur = 12 + diff;
        a1 = 0.55 + 0.13 * diff + r.nextDouble() * 0.2;
        a2 = a1 * (1.7 + r.nextDouble() * 0.5);
        p1 = r.nextDouble() * Math.PI * 2;
        p2 = r.nextDouble() * Math.PI * 2;
        amp1 = 0.24 + 0.01 * diff;
        amp2 = 0.08 + 0.02 * diff;
        half = 0.20 - 0.025 * diff;
        need = 0.50 + 0.06 * diff;
        runStart = 0;
    }

    /** Pozitia tintei (0..1) la momentul t (secunde) de la inceputul rularii; clientul foloseste aceeasi formula. */
    public static double center(double a1, double p1, double amp1, double a2, double p2, double amp2, double half, double t) {
        double c = 0.5 + amp1 * Math.sin(a1 * t + p1) + amp2 * Math.sin(a2 * t + p2);
        return Math.max(half + 0.02, Math.min(1 - half - 0.02, c));
    }

    public int stageCountForProgress() {
        return 1;
    }

    // ------------------------------------------------------------------ date trimise clientului la deschidere

    public JsonObject openData() {
        JsonObject o = new JsonObject();
        o.addProperty("game", kind);
        o.addProperty("token", id);
        o.addProperty("diff", diff);
        o.addProperty("limitSec", limitMs / 1000);
        o.addProperty("maxMistakes", maxMistakes);
        switch (kind) {
            case "circuit": {
                JsonArray l = new JsonArray();
                JsonArray rr = new JsonArray();
                for (int i = 0; i < n; i++) {
                    JsonObject a = new JsonObject();
                    a.addProperty("c", leftCols.get(i));
                    a.addProperty("l", LEFT[leftCols.get(i) % LEFT.length]);
                    l.add(a);
                    JsonObject b = new JsonObject();
                    b.addProperty("c", rightCols.get(i));
                    b.addProperty("l", RIGHT[rightCols.get(i) % RIGHT.length]);
                    rr.add(b);
                }
                o.add("left", l);
                o.add("right", rr);
                break;
            }
            case "fuse": {
                o.addProperty("k", k);
                o.addProperty("nominal", nominal);
                JsonArray am = new JsonArray();
                for (int a : AMPS) am.add(a);
                o.add("amps", am);
                o.addProperty("phase", phase);
                break;
            }
            case "seq": {
                o.addProperty("symbols", symbols);
                o.addProperty("rounds", seq.length);
                o.addProperty("showMs", showPer);
                o.addProperty("round", round);
                o.add("show", prefix(round));
                break;
            }
            default:
                calibData(o);
        }
        return o;
    }

    private JsonArray prefix(int len) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < len && i < seq.length; i++) a.add(seq[i]);
        return a;
    }

    private void calibData(JsonObject o) {
        o.addProperty("dur", dur);
        o.addProperty("a1", a1);
        o.addProperty("a2", a2);
        o.addProperty("p1", p1);
        o.addProperty("p2", p2);
        o.addProperty("amp1", amp1);
        o.addProperty("amp2", amp2);
        o.addProperty("half", half);
        o.addProperty("need", need);
        o.addProperty("attempt", mistakes + 1);
        o.addProperty("attempts", maxMistakes + 1);
    }

    public long minMs() {
        switch (kind) {
            case "circuit":
                return 900L * n;
            case "fuse":
                return 5000;
            case "seq":
                return (long) showPer * (seq.length + 2) / 2;
            default:
                return (long) (dur * 900);
        }
    }

    // ------------------------------------------------------------------ operatii

    public Reply op(String op, JsonObject j, Ctx ctx) {
        if (over) return Reply.of(false, "Mini-jocul s-a incheiat.");
        long now = System.currentTimeMillis();
        if (now - start > limitMs) {
            over = true;
            Reply f = Reply.of(false, "Timpul a expirat.");
            f.fin = true;
            return f;
        }
        switch (kind) {
            case "circuit":
                return circuit(op, j);
            case "fuse":
                return fuse(op, j, ctx);
            case "seq":
                return seq(op, j);
            default:
                return calib(op, j);
        }
    }

    private Reply wrong(String text) {
        mistakes++;
        Reply r = Reply.of(false, text);
        r.extra.addProperty("mistakes", mistakes);
        if (mistakes > maxMistakes) {
            over = true;
            r.fin = true;
            r.text = text + " Prea multe greseli.";
        }
        return r;
    }

    private Reply win(String text, double q) {
        over = true;
        quality = Math.max(0.3, Math.min(1.0, q));
        Reply r = Reply.of(true, text);
        r.fin = true;
        r.win = true;
        return r;
    }

    private static int[] ints(JsonObject j, String key, int max) {
        try {
            if (j.has(key) && j.get(key).isJsonArray()) {
                JsonArray a = j.getAsJsonArray(key);
                int[] out = new int[Math.min(a.size(), max)];
                for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
                return out;
            }
        } catch (RuntimeException ignored) {
            // format gresit
        }
        return new int[0];
    }

    private static int num(JsonObject j, String key, int def) {
        try {
            return j.has(key) ? j.get(key).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private Reply circuit(String op, JsonObject j) {
        if (!"submit".equals(op)) return Reply.of(false, "Operatie necunoscuta.");
        int[] ans = ints(j, "ans", 12);
        if (System.currentTimeMillis() - start < minMs()) return wrong("Prea repede. Verifica conexiunile.");
        if (ans.length != n || !Arrays.equals(ans, sol)) return wrong("Conexiuni gresite. Fiecare faza la borna cu aceeasi culoare.");
        return win("Circuit conectat corect.", 1.0 - 0.18 * mistakes - timePenalty(0.15));
    }

    private double timePenalty(double max) {
        double el = (System.currentTimeMillis() - start) / (double) limitMs;
        return Math.max(0, Math.min(max, (el - 0.35) * max));
    }

    private Reply fuse(String op, JsonObject j, Ctx ctx) {
        int i = num(j, "i", -1);
        switch (op) {
            case "measure": {
                if (!ctx.hasMultimeter()) return Reply.of(false, "Ai nevoie de multimetru (cumpara-l din Echipament).");
                if (i < 0 || i >= k) return Reply.of(false, "Siguranta inexistenta.");
                Reply r = Reply.of(true, "");
                r.extra.addProperty("i", i);
                r.extra.addProperty("reading", i == bad ? "OL" : (seedRes[i] / 100.0) + " ohm");
                r.extra.addProperty("open", i == bad);
                return r;
            }
            case "pick": {
                if (phase != 0) return Reply.of(false, "Siguranta defecta a fost deja identificata.");
                if (i < 0 || i >= k) return Reply.of(false, "Siguranta inexistenta.");
                if (i != bad) return wrong("Aceea functioneaza. Cauta alta (foloseste multimetrul).");
                phase = 1;
                Reply r = Reply.of(true, "Siguranta defecta identificata. Alege amperajul de inlocuire.");
                r.extra.addProperty("phase", phase);
                r.extra.addProperty("bad", bad);
                return r;
            }
            case "amp": {
                if (phase != 1) return Reply.of(false, "Identifica mai intai siguranta defecta.");
                int v = num(j, "v", -1);
                boolean valid = false;
                for (int a : AMPS) if (a == v) valid = true;
                if (!valid) return Reply.of(false, "Amperaj invalid.");
                if (ctx.fuses() <= 0) {
                    over = true;
                    Reply r = Reply.of(false, "Nu mai ai sigurante in inventar.");
                    r.fin = true;
                    return r;
                }
                ctx.useFuse();
                if (v != correctAmp) {
                    Reply r = wrong(v < correctAmp ? "Siguranta e prea mica: sare imediat." : "Siguranta e prea mare: nu protejeaza cablul.");
                    r.extra.addProperty("fuses", ctx.fuses());
                    return r;
                }
                phase = 2;
                Reply r = Reply.of(true, "Siguranta montata. Verifica functionarea.");
                r.extra.addProperty("phase", phase);
                r.extra.addProperty("fuses", ctx.fuses());
                return r;
            }
            case "verify": {
                if (phase != 2) return Reply.of(false, "Inca nu ai montat siguranta corecta.");
                if (System.currentTimeMillis() - start < minMs()) return Reply.of(false, "Asteapta stabilizarea tensiunii...");
                return win("Verificare reusita: circuitul functioneaza.", 1.0 - 0.17 * mistakes - timePenalty(0.15));
            }
            default:
                return Reply.of(false, "Operatie necunoscuta.");
        }
    }

    private Reply seq(String op, JsonObject j) {
        if (!"ans".equals(op)) return Reply.of(false, "Operatie necunoscuta.");
        int[] a = ints(j, "ans", 16);
        long now = System.currentTimeMillis();
        long min = (long) showPer * round + 120L * round;
        if (now - roundStart < min) {
            Reply r = wrong("Prea repede. Memoreaza intai secventa.");
            roundStart = now;
            r.extra.addProperty("round", round);
            r.extra.add("show", prefix(round));
            return r;
        }
        boolean okAns = a.length == round;
        for (int i = 0; okAns && i < round; i++) if (a[i] != seq[i]) okAns = false;
        if (!okAns) {
            Reply r = wrong("Secventa gresita. O vezi din nou.");
            roundStart = now;
            r.extra.addProperty("round", round);
            r.extra.add("show", prefix(round));
            return r;
        }
        if (round >= seq.length) return win("Secventa completa.", 1.0 - 0.14 * mistakes - timePenalty(0.1));
        round++;
        roundStart = now;
        Reply r = Reply.of(true, "Corect. Runda urmatoare: " + round + " semnale.");
        r.extra.addProperty("round", round);
        r.extra.add("show", prefix(round));
        return r;
    }

    private Reply calib(String op, JsonObject j) {
        long now = System.currentTimeMillis();
        if ("begin".equals(op)) {
            if (runStart == 0) runStart = now;
            return Reply.of(true, "");
        }
        if (!"submit".equals(op)) return Reply.of(false, "Operatie necunoscuta.");
        if (runStart == 0) return Reply.of(false, "Porneste calibrarea intai.");
        int[] s = ints(j, "ans", 400);
        int nWant = (int) Math.round(dur * 10);
        double el = (now - runStart) / 1000.0;
        runStart = 0;
        if (el < dur * 0.9 || s.length < nWant - 6 || s.length > nWant + 6) return calibFail("Calibrare invalida (timp sau date).");
        double hit = 0;
        double dev = 0;
        for (int i = 0; i < s.length; i++) {
            double v = Math.max(0, Math.min(100, s[i])) / 100.0;
            double c = center(a1, p1, amp1, a2, p2, amp2, half, i / 10.0);
            if (Math.abs(v - c) <= half) hit++;
            dev += Math.abs(v - c);
        }
        double score = hit / s.length;
        if (dev / s.length < 0.004) return calibFail("Urma suspecta (prea perfecta). Calibrarea a fost respinsa.");
        if (score < need) return calibFail("Calibrare slaba: " + Math.round(score * 100) + "% (minim " + Math.round(need * 100) + "%).");
        Reply r = win("Calibrare reusita: " + Math.round(score * 100) + "% in interval.", score - 0.1 * mistakes);
        r.extra.addProperty("score", score);
        return r;
    }

    private Reply calibFail(String text) {
        Reply r = wrong(text);
        if (!r.fin) {
            newCalib();
            calibData(r.extra);
            r.extra.addProperty("restart", true);
        }
        return r;
    }
}
