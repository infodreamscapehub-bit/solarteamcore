package ro.solarstudios.flowcity.docs;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Primaria: cerere de buletin, reinnoire si duplicat. Serverul valideaza tot si ia taxa prin popup-ul de plata. */
public final class PrimarieScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;

    private final TextFieldWidget fSurname = field("Nume (de familie)", 40, null);
    private final TextFieldWidget fName = field("Prenume", 40, null);
    private final TextFieldWidget fBirth = field("Data nașterii zz.ll.aaaa", 10, null);
    private final TextFieldWidget fAddr = field("Adresa (strada, număr)", 60, null);
    private final long born = System.currentTimeMillis();
    private JsonObject state;
    private String sex = "M";
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
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
    }

    private boolean hasId() {
        return state.has("buletin") && state.get("buletin").isJsonObject();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        if (!hasId()) {
            l.add(fSurname);
            l.add(fName);
            l.add(fBirth);
            l.add(fAddr);
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
        for (TextFieldWidget f : fields()) f.render(ms.pose(), mx, my, 0);
    }

    private void submit() {
        DocsClient.apply(fSurname.getValue().trim(), fName.getValue().trim(), fBirth.getValue().trim(), fAddr.getValue().trim(), sex);
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        if (!hasId()) submit();
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        JsonObject pr = state.has("prices") && state.get("prices").isJsonObject() ? state.getAsJsonObject("prices") : new JsonObject();
        double pNew = JsonClientModule.num(pr, "buletin", 150);
        double pDup = JsonClientModule.num(pr, "duplicat", 100);
        double pRen = JsonClientModule.num(pr, "reinnoire", 100);

        Gfx.text(ms, "PRIMĂRIA", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOW CITY · EVIDENȚA POPULAȚIEI", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        Gfx.text(ms, "CASH", W - 48, 24, 6f, Sk.FAINT, 2, false, 1f);
        Gfx.text(ms, money(JsonClientModule.num(state, "money", 0)) + " lei", W - 48, 38, 11.5f, Sk.GOOD, 2, true, 0f);

        if (hasId()) {
            JsonObject b = state.getAsJsonObject("buletin");
            boolean expired = JsonClientModule.bool(b, "expired", false);
            DocViewScreen.card(ms, b, 12, 66);
            Sk.slant(ms, 326, 66, 182, 170, 8f, Sk.ROW);
            Gfx.text(ms, "ACȚIUNI", 340, 84, 7f, Sk.MUTED, 0, false, 1.2f);

            boolean h1 = expired && Sk.in(lmx, lmy, 340, 94, 154, 26);
            Sk.button(ms, "REÎNNOIEȘTE", 340, 94, 154, 26, expired, expired, h1);
            if (expired) hitL(340, 94, 154, 26, DocsClient::renew);
            Gfx.text(ms, expired ? money(pRen) + " lei · valabilitate nouă" : "disponibil după expirare", 340, 130, 6.2f, expired ? Sk.MUTED : Sk.FAINT, 0);

            boolean h2 = Sk.in(lmx, lmy, 340, 148, 154, 26);
            Sk.button(ms, "DUPLICAT", 340, 148, 154, 26, true, false, h2);
            hitL(340, 148, 154, 26, DocsClient::duplicate);
            Gfx.text(ms, money(pDup) + " lei · buletin pierdut", 340, 184, 6.2f, Sk.MUTED, 0);
            Gfx.text(ms, "Seria se schimbă la duplicat.", 340, 214, 6.2f, Sk.FAINT, 0);
            Gfx.text(ms, "Arată actele cu /buletin", 340, 224, 6.2f, Sk.FAINT, 0);
        } else {
            Sk.slant(ms, 12, 62, 306, 218, 8f, Sk.ROW);
            Gfx.text(ms, "CERERE BULETIN", 26, 80, 7f, Sk.MUTED, 0, false, 1.2f);
            fieldBox(ms, fSurname, 26, 90, 278, 22);
            fieldBox(ms, fName, 26, 118, 278, 22);
            fieldBox(ms, fBirth, 26, 146, 278, 22);
            fieldBox(ms, fAddr, 26, 174, 278, 22);

            Gfx.text(ms, "SEX", 26, 214, 6.4f, Sk.FAINT, 0, false, 1f);
            for (int i = 0; i < 2; i++) {
                final String v = i == 0 ? "M" : "F";
                float bx = 56 + i * 54;
                boolean sel = v.equals(sex);
                boolean h = Sk.in(lmx, lmy, bx, 203, 48, 20);
                Sk.button(ms, i == 0 ? "MASC." : "FEM.", bx, 203, 48, 20, true, sel, h);
                hitL(bx, 203, 48, 20, () -> sex = v);
            }
            boolean h = Sk.in(lmx, lmy, 26, 236, 278, 30);
            Sk.button(ms, "CERE BULETIN · " + money(pNew) + " lei", 26, 236, 278, 30, true, true, h);
            hitL(26, 236, 278, 30, this::submit);

            Sk.slant(ms, 326, 62, 182, 218, 8f, Sk.ROW);
            Gfx.text(ms, "CUM FUNCȚIONEAZĂ", 340, 80, 7f, Sk.MUTED, 0, false, 1.2f);
            String[] info = {"Completezi datele reale", "ale personajului tău.", "", "Plătești taxa, iar buletinul", "se emite pe loc.", "", "Fără buletin nu poți primi", "permis sau port-armă."};
            float y = 98;
            for (String s : info) {
                Gfx.text(ms, s, 340, y, 6.6f, Sk.FAINT, 0);
                y += 11;
            }
        }

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
