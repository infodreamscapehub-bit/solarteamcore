package ro.solarstudios.flowcity.politie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Hub "POLITIE": categorii in stanga, continut in dreapta. Tema de iarna FlowCity (520x290, scalata). */
public final class PolitieScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int CX = 134;
    private static final int CW = W - CX - 14;
    private static final int TOP = 64;
    private static final int BOT = H - 12;
    private static final int ROW = 40;

    private static final int C_AMENZI = 0;
    private static final int C_DOC = 1;
    private static final int C_CAZIER = 2;
    private static final int C_PLANGERI = 3;
    private static final int C_MASINI = 4;
    private static final int C_ANGAJARE = 5;
    private static final int C_SESIZARI = 6;

    private JsonObject d = new JsonObject();
    private JsonObject cert;
    private int cat;
    private int scroll;
    private final Set<String> picked = new LinkedHashSet<>();
    private int roleIdx;
    private final TextFieldWidget title = field("Titlu sesizare", 60, null);
    private final TextFieldWidget text = field("Descrie pe scurt ce s-a intamplat", 200, null);
    private final TextFieldWidget jobMsg = field("Mesaj pentru conducerea politiei", 200, null);
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    PolitieScreen(JsonObject data) {
        super("Politie", W, H);
        update(data);
    }

    void update(JsonObject data) {
        d = data;
        if (cat >= cats().size()) cat = 0;
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
        if (ok && m.contains("a fost trimisa")) {
            title.setValue("");
            text.setValue("");
            jobMsg.setValue("");
        }
    }

    void certificate(JsonObject c) {
        cert = c;
        cat = C_CAZIER;
    }

    private boolean officer() {
        return JsonClientModule.bool(d, "officer", false);
    }

    private List<Integer> cats() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i <= C_ANGAJARE; i++) l.add(i);
        if (officer()) l.add(C_SESIZARI);
        return l;
    }

    private static String catName(int c) {
        switch (c) {
            case C_AMENZI: return "AMENZI";
            case C_DOC: return "DOCUMENTE";
            case C_CAZIER: return "CAZIER JUDICIAR";
            case C_PLANGERI: return "PLÂNGERI";
            case C_MASINI: return "MAȘINILE MELE";
            case C_ANGAJARE: return "ANGAJARE";
            default: return "SESIZĂRI PRIMITE";
        }
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private void select(int c) {
        cat = c;
        scroll = 0;
        cert = null;
        for (TextFieldWidget f : new TextFieldWidget[] {title, text, jobMsg}) focus(f, false);
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        int c = cats().get(Math.min(cat, cats().size() - 1));
        if (c == C_PLANGERI) {
            l.add(title);
            l.add(text);
        } else if (c == C_ANGAJARE) {
            l.add(jobMsg);
        }
        return l;
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        submit(f == jobMsg);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String money(long v) {
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private void placeField(TextFieldWidget f, float x, float y, float w, float h) {
        f.x = Math.round(offX + (x + 6) * scale);
        f.y = Math.round(offY + (y + (h - 8) / 2f) * scale);
        f.setWidth(Math.max(10, Math.round((w - 12) * scale)));
    }

    private void fieldBox(GuiGraphics ms, TextFieldWidget f, float x, float y, float w, float h) {
        Sk.slant(ms, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, f.isFocused() ? Sk.ACC : 0xFF22344D);
        placeField(f, x, y, w, h);
    }

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean filled, float lmx, float lmy, Runnable r) {
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, enabled, filled, hov);
        if (enabled) hitL(x, y, w, h, r);
    }

    private void chip(GuiGraphics ms, String label, float x, float y, float w, float h, boolean on, float lmx, float lmy, Runnable r) {
        boolean hov = Sk.in(lmx, lmy, x, y, w, h);
        Sk.slant(ms, x, y, w, h, 4f, on ? Sk.ACC : hov ? Sk.ROW_HOT : Sk.ROW);
        Gfx.text(ms, label, x + w / 2f, y + h / 2f + 3f, 7f, on ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
        hitL(x, y, w, h, r);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float open = easeOut((System.currentTimeMillis() - born) / 300f);
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        // campurile de text se deseneaza in coordonate de ecran, dupa restaurarea transformarii
        for (TextFieldWidget f : fields()) f.render(ms.pose(), mx, my, 0);
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        Gfx.text(ms, "POLIȚIE", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "SERVICII PENTRU CETĂȚENI", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        // sine de categorii
        List<Integer> cs = cats();
        int unpaid = arr(d, "fines").size();
        int fresh = 0;
        for (JsonElement e : arr(d, "inbox")) if ("NOUA".equals(JsonClientModule.str(e.getAsJsonObject(), "status", ""))) fresh++;
        for (int i = 0; i < cs.size(); i++) {
            int c = cs.get(i);
            float y = TOP + i * 33f;
            boolean sel = i == cat;
            boolean hov = Sk.in(lmx, lmy, 12, y, 112, 29);
            Sk.slant(ms, 12, y, 112, 29, 6f, sel ? 0xFF1B2E47 : hov ? Sk.ROW_HOT : Sk.ROW);
            if (sel) Gfx.rrect(ms, 12, y + 4, 3, 21, 0, Sk.ACC);
            Gfx.text(ms, clip(catName(c), 7.4f, true, 84), 24, y + 18, 7.4f, sel ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            int badge = c == C_AMENZI ? unpaid : c == C_SESIZARI ? fresh : 0;
            if (badge > 0) {
                Gfx.rrect(ms, 106, y + 8, 14, 12, 0, c == C_AMENZI ? Sk.BAD : Sk.ACC);
                Gfx.text(ms, String.valueOf(badge), 113, y + 17.5f, 6.4f, 0xFF061426, 1, true, 0f);
            }
            final int idx = i;
            hitL(12, y, 112, 29, () -> select(idx));
        }

        int c = cs.get(Math.min(cat, cs.size() - 1));
        Gfx.text(ms, catName(c), CX, 56, 7f, Sk.FAINT, 0, false, 1.4f);
        switch (c) {
            case C_AMENZI: amenzi(ms, lmx, lmy); break;
            case C_DOC: documente(ms, lmx, lmy); break;
            case C_CAZIER: cazier(ms, lmx, lmy); break;
            case C_PLANGERI: plangeri(ms, lmx, lmy); break;
            case C_MASINI: masini(ms, lmx, lmy); break;
            case C_ANGAJARE: angajare(ms, lmx, lmy); break;
            default: sesizari(ms, lmx, lmy);
        }

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, clip(msg, 8f, false, W - 90), W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    private void scrollbar(GuiGraphics ms, int total, int visible) {
        if (total <= visible) return;
        float trackH = BOT - TOP - 8;
        float th = Math.max(14f, trackH * visible / total);
        float ty = TOP + 8 + (trackH - th) * scroll / Math.max(1, total - visible);
        Gfx.rrect(ms, W - 10, TOP + 8, 2, trackH, 0, 0xFF14202F);
        Gfx.rrect(ms, W - 10, ty, 2, th, 0, Sk.ACC);
    }

    // ------------------------------------------------------------------ AMENZI

    private void amenzi(GuiGraphics ms, float lmx, float lmy) {
        JsonArray fines = arr(d, "fines");
        long total = 0;
        for (JsonElement e : fines) total += JsonClientModule.integer(e.getAsJsonObject(), "amountDue", JsonClientModule.integer(e.getAsJsonObject(), "amount", 0));
        if (fines.size() == 0) {
            Gfx.text(ms, "Nu ai nicio amendă neplătită.", CX + CW / 2f, H / 2f + 4, 11, Sk.WHITE, 1, true, 0f);
            Gfx.text(ms, "Condu cu grijă!", CX + CW / 2f, H / 2f + 20, 8, Sk.MUTED, 1);
            return;
        }
        Gfx.text(ms, "DE PLATĂ  " + money(total) + " lei", CX + CW, 56, 8.5f, Sk.BAD, 2, true, 0f);
        int visible = (BOT - TOP - 8) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, fines.size() - visible)));
        float y = TOP + 8;
        for (int i = scroll; i < Math.min(fines.size(), scroll + visible); i++) {
            JsonObject f = fines.get(i).getAsJsonObject();
            int amount = JsonClientModule.integer(f, "amount", 0);
            int due = JsonClientModule.integer(f, "amountDue", amount);
            int pct = JsonClientModule.integer(f, "penaltyPct", 0);
            boolean over = JsonClientModule.bool(f, "overdue", false);
            String dueDate = JsonClientModule.str(f, "dueDate", "");
            Sk.slant(ms, CX, y, CW - 8, ROW - 6, 8f, Sk.ROW);
            Gfx.rrect(ms, CX + 12, y + 8, 3, ROW - 22, 0, over ? Sk.BAD : Sk.WARN);
            Gfx.text(ms, clip(JsonClientModule.str(f, "reason", ""), 9f, true, 170), CX + 24, y + 15, 9f, Sk.WHITE, 0, true, 0f);
            String sub = JsonClientModule.str(f, "date", "");
            if (over) sub += "  ·  ÎNTÂRZIAT +" + pct + "%";
            else if (!dueDate.isEmpty()) sub += "  ·  termen " + dueDate;
            Gfx.text(ms, clip(sub, 6.4f, false, 170), CX + 24, y + 27, 6.4f, over ? Sk.BAD : Sk.MUTED, 0);
            Gfx.text(ms, money(due) + " lei", CX + CW - 112, y + 21, 10, over ? Sk.BAD : Sk.WHITE, 2, true, 0f);
            final long id = (long) JsonClientModule.num(f, "id", 0);
            button(ms, "PLĂTEȘTE", CX + CW - 104, y + 5, 90, 24, true, true, lmx, lmy, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                PolitieClient.act("pay", o);
            });
            y += ROW;
        }
        scrollbar(ms, fines.size(), visible);
    }

    // ------------------------------------------------------------------ DOCUMENTE

    private void docCard(GuiGraphics ms, String name, JsonObject doc, String missing, float y, float h) {
        boolean valid = JsonClientModule.bool(doc, "valid", false);
        boolean has = JsonClientModule.bool(doc, "has", false);
        Sk.slant(ms, CX, y, CW - 8, h, 8f, Sk.ROW);
        Gfx.rrect(ms, CX + 12, y + 7, 3, 20, 0, valid ? Sk.GOOD : Sk.BAD);
        Gfx.text(ms, name, CX + 24, y + 15, 9f, Sk.WHITE, 0, true, 0f);
        String sub = valid ? "VALID până la " + JsonClientModule.str(doc, "expires", "") + "  ·  " + JsonClientModule.str(doc, "serie", "")
            : has ? "EXPIRAT" : missing;
        String cats = JsonClientModule.str(doc, "categories", "");
        if (valid && !cats.isEmpty()) sub += "  ·  cat. " + cats;
        Gfx.text(ms, clip(sub, 6.4f, false, CW - 160), CX + 24, y + 27, 6.4f, valid ? Sk.GOOD : Sk.MUTED, 0);
    }

    private void documente(GuiGraphics ms, float lmx, float lmy) {
        JsonObject docs = obj(d, "docs");
        JsonObject fees = obj(d, "fees");
        JsonObject buletin = obj(docs, "buletin");
        JsonObject permis = obj(docs, "permis");
        JsonObject arma = obj(docs, "portarma");
        float y = TOP + 8;
        docCard(ms, "BULETIN", buletin, "Se eliberează la Primărie", y, 34);
        Gfx.text(ms, "la Primărie", CX + CW - 18, y + 20, 7f, Sk.FAINT, 2);
        y += 40;

        boolean permValid = JsonClientModule.bool(permis, "valid", false);
        docCard(ms, "PERMIS DE CONDUCERE", permis, "Nu ai permis", y, 34);
        if (permValid) {
            button(ms, "DUPLICAT " + JsonClientModule.integer(fees, "duplicat", 0) + " lei", CX + CW - 140, y + 6, 126, 22, true, false, lmx, lmy, () -> PolitieClient.act("duplicat"));
            y += 40;
        } else {
            y += 38;
            JsonArray cats = arr(d, "cats");
            float cw = 36;
            float gap = 4;
            for (int i = 0; i < cats.size(); i++) {
                String c = cats.get(i).getAsString();
                float x = CX + (i % 9) * (cw + gap);
                float cy = y + (i / 9) * 20;
                chip(ms, c, x, cy, cw, 17, picked.contains(c), lmx, lmy, () -> {
                    if (!picked.remove(c)) picked.add(c);
                });
            }
            y += 42;
            button(ms, "CERE PERMIS · " + JsonClientModule.integer(fees, "permis", 0) + " lei", CX, y, 180, 24, !picked.isEmpty(), true, lmx, lmy, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("categories", String.join(",", picked));
                PolitieClient.act("permis", o);
            });
            y += 32;
        }
        docCard(ms, "PORT-ARMĂ", arma, "Nu ai port-armă", y, 34);
        if (!JsonClientModule.bool(arma, "valid", false)) {
            button(ms, "CERE · " + JsonClientModule.integer(fees, "portarma", 0) + " lei", CX + CW - 140, y + 6, 126, 22, true, true, lmx, lmy, () -> PolitieClient.act("portarma"));
        }
        y += 40;
        Gfx.text(ms, "Condiții: buletin valid, minim " + JsonClientModule.integer(d, "minAge", 18) + " ani, fără amenzi neplătite; port-armă: și cazier curat.", CX, Math.min(y + 4, BOT - 2), 6.2f, Sk.FAINT, 0);
    }

    // ------------------------------------------------------------------ CAZIER

    private void cazier(GuiGraphics ms, float lmx, float lmy) {
        if (cert != null) {
            float y = TOP + 8;
            Sk.slant(ms, CX, y, CW - 8, BOT - y, 8f, Sk.ROW);
            Gfx.text(ms, "CERTIFICAT DE CAZIER JUDICIAR", CX + (CW - 8) / 2f, y + 20, 10, Sk.WHITE, 1, true, 0f);
            Gfx.text(ms, "Nume: " + JsonClientModule.str(cert, "name", ""), CX + 16, y + 42, 8.5f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "Data eliberării: " + JsonClientModule.str(cert, "date", ""), CX + 16, y + 56, 7.5f, Sk.MUTED, 0);
            JsonArray lines = arr(cert, "lines");
            if (JsonClientModule.bool(cert, "clean", false)) {
                Gfx.text(ms, "FĂRĂ ANTECEDENTE PENALE", CX + (CW - 8) / 2f, y + 100, 11, Sk.GOOD, 1, true, 0f);
            } else {
                float ly = y + 76;
                for (JsonElement e : lines) {
                    Gfx.text(ms, clip("• " + e.getAsString(), 7f, false, CW - 40), CX + 16, ly, 7f, Sk.BAD, 0);
                    ly += 11;
                    if (ly > BOT - 34) break;
                }
            }
            button(ms, "ÎNAPOI", CX + CW - 108, BOT - 32, 90, 22, true, false, lmx, lmy, () -> cert = null);
            return;
        }
        JsonObject c = obj(d, "cazier");
        float y = TOP + 8;
        int unpaid = JsonClientModule.integer(c, "unpaidCount", 0);
        boolean wanted = JsonClientModule.bool(c, "wanted", false);
        JsonArray recs = arr(c, "records");
        Sk.slant(ms, CX, y, CW - 8, 84, 8f, Sk.ROW);
        Gfx.text(ms, "Amenzi plătite: " + JsonClientModule.integer(c, "paidCount", 0) + "  (" + money((long) JsonClientModule.num(c, "paidSum", 0)) + " lei)", CX + 16, y + 18, 8, Sk.WHITE, 0);
        Gfx.text(ms, "Amenzi neplătite: " + unpaid, CX + 16, y + 33, 8, unpaid > 0 ? Sk.BAD : Sk.GOOD, 0);
        Gfx.text(ms, "Mandat de urmărire: " + (wanted ? "DA - " + clip(JsonClientModule.str(c, "reason", ""), 8f, false, 220) : "nu"), CX + 16, y + 48, 8, wanted ? Sk.BAD : Sk.GOOD, 0);
        Gfx.text(ms, "Înregistrări / arestări: " + recs.size(), CX + 16, y + 63, 8, recs.size() > 0 ? Sk.WARN : Sk.GOOD, 0);
        y += 92;
        for (int i = 0; i < Math.min(3, recs.size()); i++) {
            JsonObject r = recs.get(i).getAsJsonObject();
            Gfx.text(ms, clip(JsonClientModule.str(r, "date", "") + " · " + JsonClientModule.str(r, "text", ""), 6.8f, false, CW - 24), CX + 8, y, 6.8f, Sk.MUTED, 0);
            y += 11;
        }
        button(ms, "CERE CERTIFICAT · " + JsonClientModule.integer(obj(d, "fees"), "cazier", 0) + " lei", CX, BOT - 32, 200, 24, true, true, lmx, lmy, () -> PolitieClient.act("cazier"));
    }

    // ------------------------------------------------------------------ PLANGERI

    private void mineList(GuiGraphics ms, String type, float y) {
        Gfx.text(ms, "TRIMISE DE TINE", CX, y, 6.4f, Sk.FAINT, 0, false, 1.2f);
        y += 6;
        int n = 0;
        for (JsonElement e : arr(d, "mine")) {
            JsonObject o = e.getAsJsonObject();
            if (!type.equals(JsonClientModule.str(o, "type", ""))) continue;
            if (y + 16 > BOT) break;
            Sk.slant(ms, CX, y, CW - 8, 16, 4f, Sk.ROW);
            Gfx.text(ms, clip("#" + JsonClientModule.integer(o, "id", 0) + "  " + JsonClientModule.str(o, "title", ""), 7f, false, CW - 160), CX + 8, y + 11, 7f, Sk.WHITE, 0);
            String st = JsonClientModule.str(o, "status", "");
            Gfx.text(ms, JsonClientModule.str(o, "date", "") + "  " + st, CX + CW - 16, y + 11, 6.6f, "NOUA".equals(st) ? Sk.WARN : "PREASA".equals(st) ? Sk.ACC : Sk.FAINT, 2);
            y += 19;
            n++;
        }
        if (n == 0) Gfx.text(ms, "Nimic trimis încă.", CX, y + 8, 7f, Sk.FAINT, 0);
    }

    private void plangeri(GuiGraphics ms, float lmx, float lmy) {
        float y = TOP + 8;
        fieldBox(ms, title, CX, y, CW - 8, 22);
        y += 28;
        fieldBox(ms, text, CX, y, CW - 8, 22);
        y += 28;
        button(ms, "TRIMITE SESIZAREA", CX, y, 170, 24, true, true, lmx, lmy, () -> submit(false));
        mineList(ms, "PLANGERE", y + 40);
    }

    private void submit(boolean job) {
        JsonObject o = new JsonObject();
        if (job) {
            JsonArray roles = arr(d, "roles");
            o.addProperty("type", "ANGAJARE");
            o.addProperty("role", roles.size() > 0 ? roles.get(Math.min(roleIdx, roles.size() - 1)).getAsString() : "");
            o.addProperty("text", jobMsg.getValue());
        } else {
            o.addProperty("type", "PLANGERE");
            o.addProperty("title", title.getValue());
            o.addProperty("text", text.getValue());
        }
        PolitieClient.act("submit", o);
    }

    // ------------------------------------------------------------------ MASINI

    private void masini(GuiGraphics ms, float lmx, float lmy) {
        float y = TOP + 12;
        Gfx.text(ms, "Vezi lista mașinilor tale înmatriculate:", CX, y + 8, 9, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "model, număr și starea fiecărei mașini.", CX, y + 22, 7.5f, Sk.MUTED, 0);
        Gfx.text(ms, "Lista se deschide într-un ecran separat.", CX, y + 34, 7.5f, Sk.MUTED, 0);
        button(ms, "VEZI MAȘINILE MELE", CX, y + 52, 190, 26, true, true, lmx, lmy, () -> {
            PolitieClient.act("mechlist");
            onClose();
        });
    }

    // ------------------------------------------------------------------ ANGAJARE

    private void angajare(GuiGraphics ms, float lmx, float lmy) {
        float y = TOP + 8;
        Gfx.text(ms, "Rolul dorit", CX, y + 6, 7f, Sk.MUTED, 0);
        y += 12;
        JsonArray roles = arr(d, "roles");
        for (int i = 0; i < roles.size(); i++) {
            final int idx = i;
            float x = CX + (i % 4) * 92;
            float cy = y + (i / 4) * 21;
            chip(ms, clip(roles.get(i).getAsString(), 7f, true, 84), x, cy, 88, 18, i == roleIdx, lmx, lmy, () -> roleIdx = idx);
        }
        y += ((roles.size() + 3) / 4) * 21 + 6;
        fieldBox(ms, jobMsg, CX, y, CW - 8, 22);
        y += 28;
        button(ms, "TRIMITE CEREREA", CX, y, 170, 24, true, true, lmx, lmy, () -> submit(true));
        mineList(ms, "ANGAJARE", y + 40);
    }

    // ------------------------------------------------------------------ SESIZARI (ofiteri)

    private void sesizari(GuiGraphics ms, float lmx, float lmy) {
        JsonArray in = arr(d, "inbox");
        if (in.size() == 0) {
            Gfx.text(ms, "Nu sunt sesizări active.", CX + CW / 2f, H / 2f + 4, 10, Sk.WHITE, 1, true, 0f);
            return;
        }
        int visible = (BOT - TOP - 8) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, in.size() - visible)));
        float y = TOP + 8;
        for (int i = scroll; i < Math.min(in.size(), scroll + visible); i++) {
            JsonObject o = in.get(i).getAsJsonObject();
            String st = JsonClientModule.str(o, "status", "");
            Sk.slant(ms, CX, y, CW - 8, ROW - 6, 8f, Sk.ROW);
            Gfx.rrect(ms, CX + 12, y + 8, 3, ROW - 22, 0, "NOUA".equals(st) ? Sk.WARN : Sk.ACC);
            String head = ("ANGAJARE".equals(JsonClientModule.str(o, "type", "")) ? "[ANGAJARE] " : "") + JsonClientModule.str(o, "title", "");
            Gfx.text(ms, clip(head, 8.6f, true, 225), CX + 24, y + 13, 8.6f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, clip("#" + JsonClientModule.integer(o, "id", 0) + " · " + JsonClientModule.str(o, "author", "") + " · " + JsonClientModule.str(o, "date", "") + " · " + st, 6.2f, false, 225), CX + 24, y + 22, 6.2f, Sk.MUTED, 0);
            Gfx.text(ms, clip(JsonClientModule.str(o, "text", ""), 6.6f, false, 225), CX + 24, y + 31, 6.6f, Sk.FAINT, 0);
            final long id = (long) JsonClientModule.num(o, "id", 0);
            button(ms, "PREIA", CX + CW - 132, y + 6, 56, 22, !"PREASA".equals(st), true, lmx, lmy, () -> act("take", id));
            button(ms, "ARHIVEAZĂ", CX + CW - 72, y + 6, 62, 22, true, false, lmx, lmy, () -> act("archive", id));
            y += ROW;
        }
        scrollbar(ms, in.size(), visible);
    }

    private static void act(String action, long id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        PolitieClient.act(action, o);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
