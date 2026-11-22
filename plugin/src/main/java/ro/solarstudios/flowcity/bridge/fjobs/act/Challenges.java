package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Mini-jocuri validate pe server. Serverul genereaza puzzle-ul si solutia, clientul doar afiseaza si trimite raspunsul;
 * serverul verifica: acelasi jucator, id-ul curent, timpul minim plauzibil, numarul de incercari si limita de timp.
 * Tipuri: wires (leaga perechile de culori), pick (alege o varianta), sort (pune elementele in cosuri), seq (repeta o secventa).
 * Limita: puzzle-ul trebuie sa ajunga la client ca sa fie afisat, deci un client modificat il poate rezolva automat; timpul minim
 * si incercarile limitate opresc doar rezolvarea instantanee, nu un bot care asteapta.
 */
public final class Challenges {
    public interface Done {
        /** quality in [0,1] (timp + greseli); apelat o singura data. */
        void done(Player p, boolean ok, double quality);
    }

    public static final class Spec {
        public String type;
        public String title = "";
        public String sub = "";
        public JsonObject data = new JsonObject();
        public int[] solution = new int[0];
        public long minMs = 1500;
        public long limitMs = 60_000;
        public int attempts = 3;
    }

    private static final class Ch {
        String id;
        Spec spec;
        Done cb;
        long opened;
        int left;
    }

    private final FJobsApi api;
    private final Map<UUID, Ch> open = new HashMap<>();
    private long seq;

    public Challenges(FJobsApi api) {
        this.api = api;
    }

    public boolean has(Player p) {
        return open.containsKey(p.getUniqueId());
    }

    public boolean start(Player p, Spec s, Done cb) {
        if (open.containsKey(p.getUniqueId())) return false;
        if (!api.plugin().channel().hasMod(p)) {
            p.sendMessage("§cMini-jocul necesita modul FlowCity instalat pe client.");
            return false;
        }
        Ch c = new Ch();
        c.id = Long.toString(++seq, 36);
        c.spec = s;
        c.cb = cb;
        c.opened = System.currentTimeMillis();
        c.left = Math.max(1, s.attempts);
        open.put(p.getUniqueId(), c);
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id);
        o.addProperty("type", s.type);
        o.addProperty("title", s.title);
        o.addProperty("sub", s.sub);
        o.addProperty("limitSec", s.limitMs / 1000.0);
        o.addProperty("attempts", c.left);
        o.add("data", s.data);
        api.send(p, "mg_open", o);
        Fx.sound(p, "BLOCK_NOTE_BLOCK_PLING", 0.6f, 1.4f);
        return true;
    }

    /** Raspuns de la client: {id, ans:[int..]}. */
    public void answer(Player p, JsonObject j) {
        Ch c = open.get(p.getUniqueId());
        if (c == null) return;
        if (!c.id.equals(j.has("id") && j.get("id").isJsonPrimitive() ? j.get("id").getAsString() : "")) return;
        long now = System.currentTimeMillis();
        long el = now - c.opened;
        int[] ans = new int[0];
        try {
            if (j.has("ans") && j.get("ans").isJsonArray()) {
                JsonArray a = j.getAsJsonArray("ans");
                ans = new int[Math.min(a.size(), 64)];
                for (int i = 0; i < ans.length; i++) ans[i] = a.get(i).getAsInt();
            }
        } catch (RuntimeException e) {
            ans = new int[0];
        }
        if (el < c.spec.minMs) {
            result(p, c, false, false, "Prea repede. Mai incearca, cu atentie.");
            c.left--;
            if (c.left <= 0) finish(p, c, false, 0);
            return;
        }
        if (el > c.spec.limitMs + 4000) {
            finish(p, c, false, 0);
            return;
        }
        if (Arrays.equals(ans, c.spec.solution)) {
            int used = Math.max(1, c.spec.attempts) - c.left;
            double timeQ = 1.0 - 0.5 * Math.min(1.0, (double) el / Math.max(1, c.spec.limitMs));
            double q = Math.max(0.2, timeQ * (1.0 - 0.2 * used));
            finish(p, c, true, q);
        } else {
            c.left--;
            if (c.left <= 0) {
                finish(p, c, false, 0);
            } else {
                result(p, c, false, false, "Gresit. Mai ai " + c.left + (c.left == 1 ? " incercare." : " incercari."));
                Fx.sound(p, "BLOCK_ANVIL_LAND", 0.4f, 1.6f);
            }
        }
    }

    public void cancel(Player p) {
        Ch c = open.get(p.getUniqueId());
        if (c == null) return;
        finish(p, c, false, 0);
    }

    /** Fara callback (jucatorul a iesit de pe server / s-a oprit pluginul). */
    public void drop(Player p) {
        Ch c = open.remove(p.getUniqueId());
        if (c != null && p.isOnline()) result(p, c, false, true, "Sarcina a fost anulata.");
    }

    public void tick(long now) {
        for (Iterator<Map.Entry<UUID, Ch>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ch> e = it.next();
            Ch c = e.getValue();
            if (now - c.opened > c.spec.limitMs + 4000) {
                it.remove();
                Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    result(p, c, false, true, "Timpul a expirat.");
                    c.cb.done(p, false, 0);
                }
            }
        }
    }

    private void finish(Player p, Ch c, boolean ok, double q) {
        open.remove(p.getUniqueId());
        result(p, c, ok, true, ok ? "Reusit!" : "Esuat.");
        Fx.sound(p, ok ? "ENTITY_PLAYER_LEVELUP" : "ENTITY_VILLAGER_NO", 0.6f, ok ? 1.3f : 1f);
        c.cb.done(p, ok, q);
    }

    private void result(Player p, Ch c, boolean ok, boolean fin, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.id);
        o.addProperty("ok", ok);
        o.addProperty("final", fin);
        o.addProperty("text", text);
        o.addProperty("left", c.left);
        api.send(p, "mg_result", o);
    }

    // ------------------------------------------------------------------ generatoare

    /** Leaga n perechi: stanga e in ordine, dreapta amestecata; solutia = pozitia culorii in dreapta. */
    public static Spec wires(Random r, int n, String title, String sub) {
        List<Integer> colors = new ArrayList<>();
        for (int i = 0; i < 8; i++) colors.add(i);
        Collections.shuffle(colors, r);
        n = Math.max(2, Math.min(7, n));
        List<Integer> left = new ArrayList<>(colors.subList(0, n));
        List<Integer> right = new ArrayList<>(left);
        Collections.shuffle(right, r);
        Spec s = new Spec();
        s.type = "wires";
        s.title = title;
        s.sub = sub;
        JsonArray l = new JsonArray();
        JsonArray rr = new JsonArray();
        for (int c : left) l.add(c);
        for (int c : right) rr.add(c);
        s.data.add("left", l);
        s.data.add("right", rr);
        s.solution = new int[n];
        for (int i = 0; i < n; i++) s.solution[i] = right.indexOf(left.get(i));
        s.minMs = 700L * n;
        return s;
    }

    public static Spec pick(String title, String sub, String prompt, List<String> options, int correct) {
        Spec s = new Spec();
        s.type = "pick";
        s.title = title;
        s.sub = sub;
        s.data.addProperty("prompt", prompt);
        JsonArray a = new JsonArray();
        for (String o : options) a.add(o);
        s.data.add("options", a);
        s.solution = new int[] {correct};
        s.minMs = 1200;
        return s;
    }

    /** Fiecare element apartine unui cos; solutia = indexul cosului pentru fiecare element. */
    public static Spec sort(String title, String sub, List<String> items, List<String> bins, int[] binOf) {
        Spec s = new Spec();
        s.type = "sort";
        s.title = title;
        s.sub = sub;
        JsonArray a = new JsonArray();
        for (String o : items) a.add(o);
        JsonArray b = new JsonArray();
        for (String o : bins) b.add(o);
        s.data.add("items", a);
        s.data.add("bins", b);
        s.solution = binOf;
        s.minMs = 900L * items.size();
        return s;
    }

    /** Repeta secventa afisata scurt (simboluri 0..3). */
    public static Spec seq(Random r, int len, long showMs, String title, String sub) {
        Spec s = new Spec();
        s.type = "seq";
        s.title = title;
        s.sub = sub;
        len = Math.max(3, Math.min(10, len));
        JsonArray a = new JsonArray();
        s.solution = new int[len];
        for (int i = 0; i < len; i++) {
            s.solution[i] = r.nextInt(4);
            a.add(s.solution[i]);
        }
        s.data.add("seq", a);
        s.data.addProperty("showMs", showMs);
        s.minMs = showMs + 350L * len;
        return s;
    }
}
