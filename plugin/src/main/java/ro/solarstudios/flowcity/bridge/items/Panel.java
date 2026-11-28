package ro.solarstudios.flowcity.bridge.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Descrierea unui panou generic (afisat de ecranul "itemfx" din mod): titlu, randuri cheie/valoare (optional cu bara de procent)
 * si butoane. Click-ul pe un buton ajunge inapoi pe server ca "act" cu id-ul panoului si al butonului, unde e validat din nou.
 */
public final class Panel {
    public static final String OK = "ok";
    public static final String WARN = "warn";
    public static final String BAD = "bad";
    public static final String INFO = "info";

    private final String id;
    private final String title;
    private String sub = "";
    private final List<String[]> rows = new ArrayList<String[]>();
    private final List<String[]> buttons = new ArrayList<String[]>();

    public Panel(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String id() {
        return id;
    }

    public Panel sub(String s) {
        this.sub = s == null ? "" : s;
        return this;
    }

    public Panel row(String k, String v) {
        return row(k, v, INFO);
    }

    public Panel row(String k, String v, String tone) {
        rows.add(new String[] {k, v, tone, ""});
        return this;
    }

    /** Rand cu bara: pct 0..100. */
    public Panel bar(String k, String v, int pct, String tone) {
        rows.add(new String[] {k, v, tone, Integer.toString(Math.max(0, Math.min(100, pct)))});
        return this;
    }

    public Panel button(String btnId, String label, String tone) {
        buttons.add(new String[] {btnId, label, tone});
        return this;
    }

    public boolean hasButton(String btnId) {
        for (String[] b : buttons) if (b[0].equals(btnId)) return true;
        return false;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("title", title);
        o.addProperty("sub", sub);
        JsonArray r = new JsonArray();
        for (String[] x : rows) {
            JsonObject j = new JsonObject();
            j.addProperty("k", x[0]);
            j.addProperty("v", x[1]);
            j.addProperty("tone", x[2]);
            if (!x[3].isEmpty()) j.addProperty("pct", Integer.parseInt(x[3]));
            r.add(j);
        }
        o.add("rows", r);
        JsonArray b = new JsonArray();
        for (String[] x : buttons) {
            JsonObject j = new JsonObject();
            j.addProperty("id", x[0]);
            j.addProperty("label", x[1]);
            j.addProperty("tone", x[2]);
            b.add(j);
        }
        o.add("buttons", b);
        return o;
    }

    /** Varianta text pentru jucatorii fara mod. */
    public List<String> toChat() {
        List<String> out = new ArrayList<String>();
        out.add("§6§l" + title + (sub.isEmpty() ? "" : " §7- " + sub));
        for (String[] x : rows) out.add("§7" + x[0] + ": §f" + x[1]);
        return out;
    }
}
