package ro.solarstudios.flowcity.bank;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.FScreen;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Banca: solduri separate CASH / BANCA, credit activ (datorat, scadenta, progres, rambursare din cont sau cash), oferte de
 * credit si seiful de economii cu dobanda. Aceleasi actiuni ca inainte; serverul valideaza tot.
 */
public final class BankScreen extends FScreen {
    private static final int W = 580;
    private static final int H = 330;

    BankScreen() {
        super("Banca", W, H);
    }

    void update() {
        // starea se citeste direct din BankClient.state la fiecare cadru
    }

    void result(boolean ok, String m) {
        toast(m, !ok);
    }

    private void quick(GuiGraphics g, String id, String label, float x, float y, float w, boolean enabled, int mx, int my, Runnable r) {
        btn(g, id, label, null, x, y, w, 20, FUi.SOFT, enabled, mx, my, r);
    }

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        JsonObject s = BankClient.state;
        double bank = JsonClientModule.num(s, "bank", 0);
        double cash = JsonClientModule.num(s, "cash", 0);
        double savings = JsonClientModule.num(s, "savings", 0);
        double rate = JsonClientModule.num(s, "savingsRate", 0);
        double hours = JsonClientModule.num(s, "hours", 0);

        shell(g);
        top(g, "Banca", "FlowCity Bank", mx, my);
        balance(g, "CASH", cash, W - 40, 16);
        float bx = W - 40 - (Gfx.width(lei(cash), 8f, true) + Gfx.width("CASH", 5.6f, true) + 26f) - 8;
        balance(g, "BANCĂ", bank, bx, 16);

        float y0 = 60;
        float lw = 320;
        float rx = 16 + lw + 16;
        float rw = W - 16 - rx;
        float ch = H - 16 - y0;

        // ---- credit
        card(g, 16, y0, lw, ch);
        boolean hasLoan = s.has("loan") && s.get("loan").isJsonObject();
        if (hasLoan) {
            JsonObject l = s.getAsJsonObject("loan");
            double owed = JsonClientModule.num(l, "owed", 0);
            double total = Math.max(1, JsonClientModule.num(l, "total", owed));
            long due = (long) JsonClientModule.num(l, "due", 0);
            boolean overdue = JsonClientModule.bool(l, "overdue", false);
            FTheme.caption(g, "CREDIT ACTIV", 32, y0 + 22, FTheme.FAINT);
            if (overdue) pill(g, "RESTANT", 16 + lw - 16 - 50, y0 + 12, FTheme.DANGER);
            tx(g, lei(owed), 32, y0 + 48, 17f, overdue ? FTheme.DANGER : FTheme.TEXT);
            t(g, overdue ? "Scadența a trecut" : "Scadent " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(due)), 32, y0 + 62, 7f, overdue ? FTheme.DANGER : FTheme.MUTED);
            float paid = (float) Math.max(0, Math.min(1, 1 - owed / total));
            FUi.progress(g, "RAMBURSAT", Math.round(paid * 100) + "%", 32, y0 + 76, lw - 32, paid, FTheme.OK);
            float y = y0 + 108;
            FTheme.caption(g, "RAMBURSEAZĂ DIN CONT", 32, y, FTheme.FAINT);
            float qw = (lw - 32 - 16) / 3f;
            quick(g, "rb1", "1.000", 32, y + 6, qw, bank >= 1, mx, my, () -> BankClient.repay(1000, false, false));
            quick(g, "rb2", "5.000", 32 + qw + 8, y + 6, qw, bank >= 1, mx, my, () -> BankClient.repay(5000, false, false));
            quick(g, "rb3", "Tot", 32 + 2 * (qw + 8), y + 6, qw, bank >= 1, mx, my, () -> BankClient.repay(0, true, false));
            y += 44;
            FTheme.caption(g, "RAMBURSEAZĂ CASH", 32, y, FTheme.FAINT);
            quick(g, "rc1", "1.000", 32, y + 6, qw, cash >= 1, mx, my, () -> BankClient.repay(1000, false, true));
            quick(g, "rc2", "5.000", 32 + qw + 8, y + 6, qw, cash >= 1, mx, my, () -> BankClient.repay(5000, false, true));
            quick(g, "rc3", "Tot", 32 + 2 * (qw + 8), y + 6, qw, cash >= 1, mx, my, () -> BankClient.repay(0, true, true));
        } else {
            FTheme.caption(g, "OFERTE DE CREDIT", 32, y0 + 22, FTheme.FAINT);
            JsonArray offers = s.has("offers") && s.get("offers").isJsonArray() ? s.getAsJsonArray("offers") : new JsonArray();
            float y = y0 + 34;
            for (JsonElement e : offers) {
                JsonObject o = e.getAsJsonObject();
                final int idx = JsonClientModule.integer(o, "i", 0);
                double minH = JsonClientModule.num(o, "minHours", 0);
                boolean ok = hours + 1e-9 >= minH;
                FUi.card(g, "of" + idx, 28, y, lw - 24, 50, false, mx, my);
                tx(g, lei(JsonClientModule.num(o, "amount", 0)), 42, y + 20, 10f, FTheme.TEXT);
                t(g, clip(JsonClientModule.integer(o, "days", 0) + " zile · dobândă " + JsonClientModule.num(o, "rate", 0) + "% · de returnat " + num(JsonClientModule.num(o, "total", 0)), lw - 150, 6.4f, false), 42, y + 32, 6.4f, FTheme.MUTED);
                if (!ok) t(g, "necesită " + Math.round(minH) + " ore jucate", 42, y + 43, 5.8f, FTheme.WARN);
                btn(g, "tk" + idx, ok ? "Împrumută" : "Blocat", null, 16 + lw - 16 - 76, y + 11, 76, 28, FUi.PRIMARY, ok, mx, my, () -> BankClient.take(idx));
                y += 56;
            }
            if (offers.size() == 0) FUi.empty(g, "check", "Nicio ofertă", "Nu ai oferte de credit disponibile acum.", 16 + lw / 2f, y0 + ch / 2f - 10, lw - 40);
        }

        // ---- economii
        card(g, rx, y0, rw, ch);
        FTheme.caption(g, "ECONOMII", rx + 16, y0 + 22, FTheme.FAINT);
        tx(g, lei(savings), rx + 16, y0 + 48, 15f, FTheme.TEXT);
        t(g, "dobândă " + rate + "% / zi", rx + 16, y0 + 62, 7f, FTheme.SKY);
        float qw2 = (rw - 32 - 16) / 3f;
        float y = y0 + 84;
        FTheme.caption(g, "PUNE LA ECONOMII", rx + 16, y, FTheme.FAINT);
        quick(g, "si1", "1.000", rx + 16, y + 6, qw2, bank >= 1, mx, my, () -> BankClient.saveIn(1000, false));
        quick(g, "si2", "5.000", rx + 16 + qw2 + 8, y + 6, qw2, bank >= 1, mx, my, () -> BankClient.saveIn(5000, false));
        quick(g, "si3", "Tot", rx + 16 + 2 * (qw2 + 8), y + 6, qw2, bank >= 1, mx, my, () -> BankClient.saveIn(0, true));
        y += 44;
        FTheme.caption(g, "RETRAGE ÎN CONT", rx + 16, y, FTheme.FAINT);
        quick(g, "so1", "1.000", rx + 16, y + 6, qw2, savings >= 1, mx, my, () -> BankClient.saveOut(1000, false));
        quick(g, "so2", "5.000", rx + 16 + qw2 + 8, y + 6, qw2, savings >= 1, mx, my, () -> BankClient.saveOut(5000, false));
        quick(g, "so3", "Tot", rx + 16 + 2 * (qw2 + 8), y + 6, qw2, savings >= 1, mx, my, () -> BankClient.saveOut(0, true));
        FUi.message(g, FUi.MSG_INFO, "Economiile cresc în fiecare zi reală.", rx + 12, y0 + ch - 38, rw - 24);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
