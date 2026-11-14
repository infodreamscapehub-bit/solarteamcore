package ro.solarstudios.flowcity.bank;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Banca: sold, credit activ (datorat, scadenta, progres), oferte de credit, rambursare si seif de economii. Tema de iarna FlowCity. */
public final class BankScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;

    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    BankScreen() {
        super("Banca", W, H);
    }

    void update() {
        // starea se citeste direct din BankClient.state la fiecare cadru
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
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

    private static String money(double v) {
        String s = String.valueOf(Math.round(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
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
    }

    private void quick(GuiGraphics ms, float lmx, float lmy, String label, float x, float y, float w, boolean enabled, Runnable r) {
        boolean h = enabled && Sk.in(lmx, lmy, x, y, w, 20);
        Sk.button(ms, label, x, y, w, 20, enabled, false, h);
        if (enabled) hitL(x, y, w, 20, r);
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        JsonObject s = BankClient.state;
        double bank = JsonClientModule.num(s, "bank", 0);
        double cash = JsonClientModule.num(s, "cash", 0);
        double savings = JsonClientModule.num(s, "savings", 0);
        double rate = JsonClientModule.num(s, "savingsRate", 0);
        double hours = JsonClientModule.num(s, "hours", 0);

        Gfx.text(ms, "BANCA", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOWCITY BANK", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        Gfx.text(ms, "ÎN CONT", W - 48, 24, 6f, Sk.FAINT, 2, false, 1f);
        Gfx.text(ms, money(bank) + " lei", W - 48, 38, 11.5f, Sk.GOOD, 2, true, 0f);
        Gfx.text(ms, "cash " + money(cash), W - 48, 50, 6.6f, Sk.MUTED, 2);

        // stanga: credit
        Sk.slant(ms, 12, 62, 306, 218, 8f, Sk.ROW);
        boolean hasLoan = s.has("loan") && s.get("loan").isJsonObject();
        if (hasLoan) {
            JsonObject l = s.getAsJsonObject("loan");
            double owed = JsonClientModule.num(l, "owed", 0);
            double total = Math.max(1, JsonClientModule.num(l, "total", owed));
            long due = (long) JsonClientModule.num(l, "due", 0);
            boolean overdue = JsonClientModule.bool(l, "overdue", false);
            Gfx.text(ms, "CREDIT ACTIV", 26, 80, 7f, Sk.MUTED, 0, false, 1.2f);
            Gfx.text(ms, money(owed) + " lei", 26, 100, 17, overdue ? Sk.BAD : Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, overdue ? "RESTANT" : "scadent " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(due)), 26, 114, 7f, overdue ? Sk.BAD : Sk.MUTED, 0);
            float paid = (float) Math.max(0, Math.min(1, 1 - owed / total));
            Sk.segs(ms, 26, 124, 278, 6, Math.round(paid * 20), 20, Sk.GOOD);
            Gfx.text(ms, "rambursat " + Math.round(paid * 100) + "%", 26, 142, 6.6f, Sk.FAINT, 0);
            float y = 154;
            Gfx.text(ms, "RAMBURSEAZĂ DIN CONT", 26, y, 6.4f, Sk.FAINT, 0, false, 1f);
            quick(ms, lmx, lmy, "1 000", 26, y + 8, 64, bank >= 1, () -> BankClient.repay(1000, false, false));
            quick(ms, lmx, lmy, "5 000", 96, y + 8, 64, bank >= 1, () -> BankClient.repay(5000, false, false));
            quick(ms, lmx, lmy, "TOT", 166, y + 8, 64, bank >= 1, () -> BankClient.repay(0, true, false));
            Gfx.text(ms, "RAMBURSEAZĂ CASH", 26, y + 40, 6.4f, Sk.FAINT, 0, false, 1f);
            quick(ms, lmx, lmy, "1 000", 26, y + 48, 64, true, () -> BankClient.repay(1000, false, true));
            quick(ms, lmx, lmy, "5 000", 96, y + 48, 64, true, () -> BankClient.repay(5000, false, true));
            quick(ms, lmx, lmy, "TOT", 166, y + 48, 64, true, () -> BankClient.repay(0, true, true));
        } else {
            Gfx.text(ms, "OFERTE DE CREDIT", 26, 78, 7f, Sk.MUTED, 0, false, 1.2f);
            JsonArray offers = s.has("offers") && s.get("offers").isJsonArray() ? s.getAsJsonArray("offers") : new JsonArray();
            float y = 88;
            for (JsonElement e : offers) {
                JsonObject o = e.getAsJsonObject();
                final int idx = JsonClientModule.integer(o, "i", 0);
                double minH = JsonClientModule.num(o, "minHours", 0);
                boolean ok = hours + 1e-9 >= minH;
                Sk.slant(ms, 20, y, 290, 40, 6f, 0xB0182638);
                Gfx.text(ms, money(JsonClientModule.num(o, "amount", 0)) + " lei", 32, y + 17, 11, Sk.WHITE, 0, true, 0f);
                Gfx.text(ms, JsonClientModule.integer(o, "days", 0) + " zile  ·  dobândă " + JsonClientModule.num(o, "rate", 0) + "%  ·  de returnat " + money(JsonClientModule.num(o, "total", 0)), 32, y + 29, 6.4f, Sk.MUTED, 0);
                if (!ok) Gfx.text(ms, "necesită " + Math.round(minH) + " ore jucate", 32, y + 36, 5.6f, Sk.WARN, 0);
                boolean h = ok && Sk.in(lmx, lmy, 226, y + 8, 76, 24);
                Sk.button(ms, ok ? "ÎMPRUMUTĂ" : "BLOCAT", 226, y + 8, 76, 24, ok, ok, h);
                if (ok) hitL(226, y + 8, 76, 24, () -> BankClient.take(idx));
                y += 46;
            }
            if (offers.size() == 0) Gfx.text(ms, "Nicio ofertă disponibilă.", 26, 100, 8, Sk.MUTED, 0);
        }

        // dreapta: economii
        Sk.slant(ms, 326, 62, 182, 218, 8f, Sk.ROW);
        Gfx.text(ms, "ECONOMII", 340, 80, 7f, Sk.MUTED, 0, false, 1.2f);
        Gfx.text(ms, money(savings) + " lei", 340, 100, 15, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "dobândă " + rate + "% / zi", 340, 114, 6.8f, Sk.ACC, 0);
        Gfx.text(ms, "PUNE LA ECONOMII", 340, 134, 6.4f, Sk.FAINT, 0, false, 1f);
        quick(ms, lmx, lmy, "1 000", 340, 142, 46, bank >= 1, () -> BankClient.saveIn(1000, false));
        quick(ms, lmx, lmy, "5 000", 392, 142, 46, bank >= 1, () -> BankClient.saveIn(5000, false));
        quick(ms, lmx, lmy, "TOT", 444, 142, 50, bank >= 1, () -> BankClient.saveIn(0, true));
        Gfx.text(ms, "RETRAGE ÎN CONT", 340, 182, 6.4f, Sk.FAINT, 0, false, 1f);
        quick(ms, lmx, lmy, "1 000", 340, 190, 46, savings >= 1, () -> BankClient.saveOut(1000, false));
        quick(ms, lmx, lmy, "5 000", 392, 190, 46, savings >= 1, () -> BankClient.saveOut(5000, false));
        quick(ms, lmx, lmy, "TOT", 444, 190, 50, savings >= 1, () -> BankClient.saveOut(0, true));
        Gfx.text(ms, "Banii din economii cresc", 340, 232, 6.2f, Sk.FAINT, 0);
        Gfx.text(ms, "în fiecare zi reală.", 340, 242, 6.2f, Sk.FAINT, 0);

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
