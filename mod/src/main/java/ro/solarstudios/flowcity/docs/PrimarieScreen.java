package ro.solarstudios.flowcity.docs;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Primaria: cerere de buletin in 3 pasi (date personale, nastere si adresa, confirmare) cu previzualizare live a cartelei,
 * plus reinnoire si duplicat pentru cine are deja buletin. Serverul valideaza tot si ia taxa prin popup-ul de plata.
 * Tema de iarna FlowCity.
 */
public final class PrimarieScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final Pattern BIRTH = Pattern.compile("\\d{1,2}\\.\\d{1,2}\\.\\d{4}");
    private static final String[] STEPS = {"1  DATE", "2  NAȘTERE", "3  CONFIRMARE"};

    private final EditBox fSurname = field("Nume (de familie)", 40, null);
    private final EditBox fName = field("Prenume", 40, null);
    private final EditBox fBirth = field("Data nașterii zz.ll.aaaa", 10, null);
    private final EditBox fPlace = field("Loc naștere (oraș)", 30, null);
    private final EditBox fAddr = field("Adresa (strada, număr)", 60, null);
    private final long born = System.currentTimeMillis();
    private JsonObject state;
    private String sex = "M";
    private int step;
    private String dTitle = "";
    private String dBody = "";
    private String dOk = "";
    private Runnable dAct;
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    PrimarieScreen(JsonObject d) {
        super("Primaria", W, H);
        this.state = d;
    }

    void update(JsonObject d) {
        this.state = d;
        dAct = null;
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
        dAct = null;
    }

    private boolean hasId() {
        return state.has("buletin") && state.get("buletin").isJsonObject();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<EditBox> fields() {
        List<EditBox> l = new ArrayList<>();
        if (!hasId() && dAct == null) {
            if (step == 0) {
                l.add(fSurname);
                l.add(fName);
            } else if (step == 1) {
                l.add(fBirth);
                l.add(fPlace);
                l.add(fAddr);
            }
        }
        return l;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String money(double v) {
        String s = String.valueOf(Math.round(v));
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

    private static void frost(GuiGraphics ms, float x, float y, float w, float h) {
        Gfx.rrect(ms, x, y, w, h, 7, Gfx.fade(0xFFBFD9F5, 0.11f), Gfx.fade(0xFF8FB5E0, 0.05f));
        Gfx.rrect(ms, x + 5, y, w - 10, 1f, 0, 0x40FFFFFF);
        Gfx.rrect(ms, x, y + 5, 1f, h - 10, 0, 0x22FFFFFF);
        Gfx.rrect(ms, x + w - 1, y + 5, 1f, h - 10, 0, 0x16FFFFFF);
        Gfx.rrect(ms, x + 5, y + h - 1, w - 10, 1f, 0, 0x16FFFFFF);
    }

    private void placeField(EditBox f, float x, float y, float w, float h) {
        f.setX(Math.round(offX + (x + 6) * scale));
        f.setY(Math.round(offY + (y + (h - 8) / 2f) * scale));
        f.setWidth(Math.max(10, Math.round((w - 12) * scale)));
    }

    private void fieldBox(GuiGraphics ms, EditBox f, String label, float x, float y, float w, float h) {
        Gfx.text(ms, label, x, y - 4, 5.6f, Sk.FAINT, 0, false, 1.2f);
        Sk.slant(ms, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, f.isFocused() ? Sk.ACC : 0xFF22344D);
        placeField(f, x, y, w, h);
    }

    private void btn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean filled, float lmx, float lmy, Runnable r) {
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, enabled, filled, hov);
        if (enabled) hitL(x, y, w, h, r);
    }

    // ------------------------------------------------------------------ logica formularului

    private boolean valid(int s) {
        if (s == 0) return !fSurname.getValue().trim().isEmpty() && !fName.getValue().trim().isEmpty();
        if (s == 1) {
            return BIRTH.matcher(fBirth.getValue().trim()).matches() && !fPlace.getValue().trim().isEmpty() && !fAddr.getValue().trim().isEmpty();
        }
        return true;
    }

    private String hint(int s) {
        if (s == 0) return valid(0) ? "" : "Completează numele și prenumele.";
        if (s == 1) {
            if (!BIRTH.matcher(fBirth.getValue().trim()).matches()) return "Data nașterii: zz.ll.aaaa (ex. 07.03.1998).";
            return valid(1) ? "" : "Completează locul nașterii și adresa.";
        }
        return "";
    }

    private boolean reachable(int s) {
        for (int k = 0; k < s; k++) if (!valid(k)) return false;
        return true;
    }

    private void go(int s) {
        for (EditBox f : fields()) focus(f, false);
        step = Math.max(0, Math.min(2, s));
    }

    private void submit() {
        DocsClient.apply(fSurname.getValue().trim(), fName.getValue().trim(), fBirth.getValue().trim(), fPlace.getValue().trim(), fAddr.getValue().trim(), sex);
    }

    private void ask(String title, String body, String ok, Runnable action) {
        for (EditBox f : fields()) focus(f, false);
        dTitle = title;
        dBody = body;
        dOk = ok;
        dAct = action;
    }

    private double priceNew() {
        return JsonClientModule.num(prices(), "buletin", 150);
    }

    private JsonObject prices() {
        return state.has("prices") && state.get("prices").isJsonObject() ? state.getAsJsonObject("prices") : new JsonObject();
    }

    private void askApply() {
        ask("CONFIRMI CEREREA?", "Taxa de " + money(priceNew()) + " lei se plătește în fereastra următoare.", "CERE BULETIN", this::submit);
    }

    @Override
    protected void onEnter(EditBox f) {
        if (hasId() || dAct != null) return;
        if (step < 2) {
            if (valid(step)) go(step + 1);
        } else {
            askApply();
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (dAct != null && key == GLFW.GLFW_KEY_ESCAPE) {
            dAct = null;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s.trim();
    }

    /** Datele din formular ca un document, pentru previzualizarea cartelei (valori de umplere, nu date reale). */
    private JsonObject preview() {
        JsonObject o = new JsonObject();
        Minecraft mc = Minecraft.getInstance();
        o.addProperty("type", "buletin");
        o.addProperty("owner", mc.player != null ? mc.player.getName().getString() : "");
        o.addProperty("serie", "--");
        o.addProperty("numar", "------");
        o.addProperty("cnp", "-------------");
        o.addProperty("surname", orDash(fSurname.getValue()));
        o.addProperty("name", orDash(fName.getValue()));
        o.addProperty("sex", sex);
        o.addProperty("birth", orDash(fBirth.getValue()));
        o.addProperty("birthPlace", orDash(fPlace.getValue()));
        o.addProperty("address", orDash(fAddr.getValue()));
        o.addProperty("issuer", "Primăria FlowCity");
        o.addProperty("expiresStr", "la emitere");
        o.addProperty("expired", false);
        return o;
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = easeOut((now - born) / 300f);
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
            content(ms, lmx, lmy, now);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        // campurile de text se deseneaza in coordonate de ecran, dupa restaurarea transformarii
        for (EditBox f : fields()) f.render(ms, mx, my, 0);
    }

    private void content(GuiGraphics ms, float lmx, float lmy, long now) {
        double pDup = JsonClientModule.num(prices(), "duplicat", 100);
        double pRen = JsonClientModule.num(prices(), "reinnoire", 100);

        Gfx.text(ms, "PRIMĂRIA", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOW CITY · EVIDENȚA POPULAȚIEI", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        Gfx.text(ms, "CASH", W - 48, 24, 6f, Sk.FAINT, 2, false, 1f);
        Gfx.text(ms, money(JsonClientModule.num(state, "money", 0)) + " lei", W - 48, 38, 11.5f, Sk.GOOD, 2, true, 0f);

        if (hasId()) {
            JsonObject b = state.getAsJsonObject("buletin");
            boolean expired = JsonClientModule.bool(b, "expired", false);
            Gfx.scrim(ms, 14 + 154, 62 + 195 + 3, 130, 10, 0x55000000);
            ms.pose().pushPose();
            try {
                ms.pose().translate(14, 62, 0);
                ms.pose().scale(1.1f, 1.1f, 1f);
                DocViewScreen.card(ms, b, 0, 0, now);
            } finally {
                ms.pose().popPose();
            }
            frost(ms, 336, 62, 172, 216);
            Gfx.rrect(ms, 350, 66, 28, 2, 0, Sk.ACC);
            Gfx.text(ms, "ACȚIUNI", 350, 84, 5.6f, Sk.FAINT, 0, false, 1.2f);
            btn(ms, "REÎNNOIEȘTE", 348, 94, 148, 26, expired, expired, lmx, lmy,
                    () -> ask("REÎNNOIEȘTI BULETINUL?", "Taxa de " + money(pRen) + " lei se plătește în fereastra următoare.", "REÎNNOIEȘTE", DocsClient::renew));
            Gfx.text(ms, expired ? money(pRen) + " lei · valabilitate nouă" : "disponibil după expirare", 350, 131, 6.2f, expired ? Sk.MUTED : Sk.FAINT, 0);
            btn(ms, "DUPLICAT", 348, 148, 148, 26, true, false, lmx, lmy,
                    () -> ask("CERI UN DUPLICAT?", "Taxa de " + money(pDup) + " lei. Seria buletinului se schimbă.", "CERE DUPLICAT", DocsClient::duplicate));
            Gfx.text(ms, money(pDup) + " lei · buletin pierdut", 350, 185, 6.2f, Sk.MUTED, 0);
            Gfx.rrect(ms, 350, 200, 144, 1f, 0, 0x30FFFFFF);
            Gfx.text(ms, "Seria se schimbă la duplicat.", 350, 216, 6.2f, Sk.FAINT, 0);
            Gfx.text(ms, "Arată actele cu /buletin", 350, 226, 6.2f, Sk.FAINT, 0);
        } else {
            form(ms, lmx, lmy, now);
        }

        if (dAct != null) dialog(ms, lmx, lmy);
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    /** Formularul in 3 pasi (stanga) si previzualizarea cartelei + taxa (dreapta). */
    private void form(GuiGraphics ms, float lmx, float lmy, long now) {
        double pNew = priceNew();

        // bara de pasi
        for (int i = 0; i < 3; i++) {
            final int si = i;
            float cx = 12 + i * 86;
            boolean on = i == step;
            boolean done = i < step;
            boolean ok = reachable(i);
            boolean hov = ok && Sk.in(lmx, lmy, cx, 58, 80, 18);
            Sk.slant(ms, cx, 58, 80, 18, 4f, on ? Sk.ACC : done || hov ? Sk.ROW_HOT : Sk.ROW);
            if (done) Gfx.rrect(ms, cx + 4, 74, 72, 1.5f, 0, Sk.ACC);
            Gfx.text(ms, STEPS[i], cx + 40 + 2, 70, 6.4f, on ? 0xFF061426 : ok ? Sk.WHITE : Sk.FAINT, 1, true, 0.3f);
            if (ok) hitL(cx, 58, 80, 18, () -> go(si));
        }

        // panoul formularului
        frost(ms, 12, 82, 252, 196);
        if (step == 0) {
            fieldBox(ms, fSurname, "NUME DE FAMILIE", 24, 104, 228, 22);
            fieldBox(ms, fName, "PRENUME", 24, 144, 228, 22);
            Gfx.text(ms, "SEX", 24, 180, 5.6f, Sk.FAINT, 0, false, 1.2f);
            for (int i = 0; i < 2; i++) {
                final String v = i == 0 ? "M" : "F";
                btn(ms, i == 0 ? "MASCULIN" : "FEMININ", 24 + i * 116, 184, 110, 22, true, v.equals(sex), lmx, lmy, () -> sex = v);
            }
        } else if (step == 1) {
            fieldBox(ms, fBirth, "DATA NAȘTERII (ZZ.LL.AAAA)", 24, 104, 228, 22);
            fieldBox(ms, fPlace, "LOCUL NAȘTERII", 24, 144, 228, 22);
            fieldBox(ms, fAddr, "DOMICILIU (STRADA, NUMĂR)", 24, 184, 228, 22);
        } else {
            String[][] rows = {
                    {"NUME", (fSurname.getValue().trim() + " " + fName.getValue().trim()).trim()},
                    {"SEX", "M".equals(sex) ? "Masculin" : "Feminin"},
                    {"NĂSCUT", fBirth.getValue().trim()},
                    {"LOCUL", fPlace.getValue().trim()},
                    {"DOMICILIU", fAddr.getValue().trim()},
                    {"TAXĂ", money(pNew) + " lei"}};
            float y = 106;
            for (int i = 0; i < rows.length; i++) {
                Gfx.text(ms, rows[i][0], 24, y, 5.6f, Sk.FAINT, 0, false, 1.2f);
                Gfx.text(ms, clip(rows[i][1], 8f, true, 140), 100, y, 8f, i == rows.length - 1 ? Sk.GOOD : Sk.WHITE, 0, true, 0f);
                Gfx.rrect(ms, 24, y + 5, 228, 1f, 0, 0x18FFFFFF);
                y += 20;
            }
        }
        String hint = step < 2 ? hint(step) : "";
        if (!hint.isEmpty()) Gfx.text(ms, clip(hint, 6.2f, false, 228), 24, 224, 6.2f, Sk.WARN, 0);
        if (step == 2) Gfx.text(ms, "Verifică datele înainte de a trimite cererea.", 24, 232, 6.2f, Sk.MUTED, 0);

        // navigare
        if (step > 0) btn(ms, "ÎNAPOI", 24, 244, 100, 26, true, false, lmx, lmy, () -> go(step - 1));
        if (step < 2) btn(ms, "ÎNAINTE", 142, 244, 110, 26, valid(step), true, lmx, lmy, () -> go(step + 1));
        else btn(ms, "CERE BULETIN", 132, 244, 120, 26, true, true, lmx, lmy, this::askApply);

        // previzualizare live
        Gfx.scrim(ms, 278 + 115, 62 + 145 + 3, 100, 8, 0x55000000);
        ms.pose().pushPose();
        try {
            ms.pose().translate(278, 62, 0);
            ms.pose().scale(0.82f, 0.82f, 1f);
            DocViewScreen.card(ms, preview(), 0, 0, now);
        } finally {
            ms.pose().popPose();
        }
        Gfx.text(ms, "PREVIZUALIZARE LIVE", 278 + 115, 218, 5.4f, Sk.FAINT, 1, false, 1.4f);

        // taxa
        frost(ms, 278, 226, 230, 52);
        Gfx.text(ms, "TAXĂ BULETIN", 292, 243, 5.6f, Sk.FAINT, 0, false, 1.2f);
        Gfx.text(ms, money(pNew) + " lei", 292, 262, 13, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "emis pe loc, după plată", 494, 262, 6.2f, Sk.MUTED, 2);
    }

    /** Dialog de confirmare (blocheaza clickurile de dedesubt). */
    private void dialog(GuiGraphics ms, float lmx, float lmy) {
        hit(0, 0, width, height, () -> { });
        Gfx.rrect(ms, -3000, -3000, 7000, 7000, 0, 0xB0050A14);
        float bw = 300f;
        float bh = 112f;
        float bx = W / 2f - bw / 2f;
        float by = H / 2f - bh / 2f;
        Gfx.rrect(ms, bx - 1, by - 1, bw + 2, bh + 2, 9, 0x40FFFFFF);
        Gfx.rrect(ms, bx, by, bw, bh, 8, 0xFF14202F, 0xFF0B1320);
        Gfx.rrect(ms, bx + 16, by + 1, 36, 2, 0, Sk.ACC);
        Gfx.text(ms, dTitle, bx + 16, by + 24, 11, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, clip(dBody, 7f, false, bw - 32), bx + 16, by + 44, 7f, Sk.MUTED, 0);
        final Runnable act = dAct;
        btn(ms, "ANULEAZĂ", bx + 16, by + 72, 124, 28, true, false, lmx, lmy, () -> dAct = null);
        btn(ms, dOk, bx + bw - 140, by + 72, 124, 28, true, true, lmx, lmy, () -> {
            dAct = null;
            if (act != null) act.run();
        });
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
