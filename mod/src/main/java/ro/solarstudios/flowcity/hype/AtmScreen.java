package ro.solarstudios.flowcity.hype;

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
 * ATM: solduri separate CASH si BANK (acelasi cont ca in banca si in telefon, nu un sold nou), depunere, retragere,
 * transfer cu taxa afisata inainte si istoricul tranzactiilor. Serverul valideaza tot (card, limite, sold).
 */
public final class AtmScreen extends FScreen {
    private static final int W = 580;
    private static final int H = 330;
    private static final int LW = 268;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM HH:mm");
    private static final int[] QUICK = {100, 500, 1000, 5000};

    private int mode;
    private int scroll;

    public AtmScreen() {
        super("ATM", W, H);
    }

    private static String label(String type) {
        switch (type) {
            case "DEPOSIT": return "Depunere";
            case "WITHDRAW": return "Retragere";
            case "TRANSFER_OUT": return "Transfer către";
            case "TRANSFER_IN": return "Primit de la";
            case "PAYDAY": return "Salariu";
            case "FUEL": return "Benzinărie (card)";
            case "FUEL_CASH": return "Benzinărie (cash)";
            default: return type;
        }
    }

    private static boolean plus(String type) {
        return type.equals("TRANSFER_IN") || type.equals("DEPOSIT") || type.equals("PAYDAY");
    }

    private double amount() {
        try {
            return Double.parseDouble(text("amount").trim().replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void act(String action) {
        double a = amount();
        if (a <= 0) {
            toast("Introdu o sumă validă.", true);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("amount", a);
        if ("atm_transfer".equals(action)) {
            String t = text("to").trim();
            if (t.isEmpty()) {
                toast("Alege un jucător.", true);
                return;
            }
            o.addProperty("to", t);
        }
        HypeClient.request(action, o);
        setText("amount", "");
    }

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        JsonObject d = HypeState.atm;
        shell(g);
        top(g, "ATM", "FlowCity Bank", mx, my);
        double cash = JsonClientModule.num(d, "cash", 0);
        double bank = JsonClientModule.num(d, "bank", 0);

        float x0 = 16;
        float y0 = 60;
        // solduri
        float hw = (LW - 8) / 2f;
        balanceCard(g, "CASH", cash, x0, y0, hw, FTheme.OK);
        balanceCard(g, "BANCĂ", bank, x0 + hw + 8, y0, hw, FTheme.accent);

        // mod
        float y = y0 + 58;
        String[] modes = {"Depunere", "Retragere", "Transfer"};
        float cx = x0;
        for (int i = 0; i < modes.length; i++) {
            final int mi = i;
            cx += chipAt(g, "am" + i, modes[i], cx, y, 18, mode == i, mx, my, () -> mode = mi);
        }
        y += 28;
        FTheme.caption(g, "SUMA", x0 + 2, y, FTheme.FAINT);
        input(g, "amount", "ex. 500", x0, y + 6, LW, 22, 12, mx, my);
        y += 36;
        // sume rapide
        double all = mode == 0 ? cash : bank;
        float qx = x0;
        for (int q : QUICK) {
            final int qv = q;
            qx += chipAt(g, "aq" + q, num(q), qx, y, 16, false, mx, my, () -> setText("amount", String.valueOf(qv)));
        }
        final double allv = Math.floor(all);
        chipAt(g, "aq-all", "Tot", qx, y, 16, false, mx, my, () -> setText("amount", String.valueOf((long) allv)));
        y += 24;

        double a = amount();
        double fp = JsonClientModule.num(d, "feePercent", 0);
        double fmin = JsonClientModule.num(d, "feeMin", 0);
        if (mode == 2) {
            FTheme.caption(g, "DESTINATAR", x0 + 2, y, FTheme.FAINT);
            input(g, "to", "Numele jucătorului", x0, y + 6, LW, 22, 16, mx, my);
            y += 36;
            double fee = a > 0 ? Math.max(fmin, Math.round(a * fp) / 100.0) : 0;
            t(g, "Taxă transfer: " + fp + "% (min " + fmin + " lei)", x0 + 2, y + 4, 6.6f, FTheme.MUTED);
            if (a > 0) t(g, "Se scad din bancă: " + lei(a + fee), x0 + 2, y + 15, 6.6f, a + fee > bank ? FTheme.DANGER : FTheme.MUTED);
            y += 24;
        } else {
            y += 6;
        }
        double wl = JsonClientModule.num(d, "withdrawLeft", -1);
        double tl = JsonClientModule.num(d, "transferLeft", -1);
        if (mode == 1 && wl >= 0) t(g, "Mai poți retrage azi: " + lei(wl), x0 + 2, y, 6.6f, FTheme.MUTED);
        if (mode == 2 && tl >= 0) t(g, "Mai poți transfera azi: " + lei(tl), x0 + 2, y, 6.6f, FTheme.MUTED);

        // buton
        String label = mode == 0 ? "Depune" : (mode == 1 ? "Retrage" : "Transferă");
        float by = H - 16 - 30;
        boolean enough = mode == 0 ? a <= cash : a <= bank;
        boolean en = a > 0 && enough && !modalActive();
        if (a > 0 && !enough) FUi.message(g, FUi.MSG_ERROR, mode == 0 ? "Nu ai atât cash la tine." : "Fonduri insuficiente în bancă.", x0, by - 32, LW);
        btn(g, "atm-go", label, mode == 0 ? "chev-down" : (mode == 1 ? "chev-up" : "chev-right"), x0, by, LW, 30, FUi.PRIMARY, en, mx, my, () -> {
            if (mode == 0) act("atm_deposit");
            else if (mode == 1) act("atm_withdraw");
            else openModal(() -> act("atm_transfer"));
        });

        // istoric
        float hx = x0 + LW + 16;
        float hwid = W - 16 - hx;
        card(g, hx, y0, hwid, H - 16 - y0);
        FTheme.caption(g, "ISTORIC TRANZACȚII", hx + 14, y0 + 16, FTheme.FAINT);
        JsonArray arr = d.has("history") && d.get("history").isJsonArray() ? d.getAsJsonArray("history") : new JsonArray();
        float ly = y0 + 26;
        float rowH = 26f;
        int per = Math.max(1, (int) ((H - 16 - 8 - ly) / rowH));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, arr.size() - per)));
        if (arr.size() == 0) {
            FUi.empty(g, "clock", "Nicio tranzacție", "Depunerile, retragerile și transferurile apar aici.", hx + hwid / 2f, ly + 80, hwid - 40);
        }
        int i = 0;
        float yy = ly;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            if (i++ < scroll) continue;
            if (i - 1 >= scroll + per) break;
            JsonObject h = el.getAsJsonObject();
            String type = JsonClientModule.str(h, "t", "");
            boolean pl = plus(type);
            String who = JsonClientModule.str(h, "o", "");
            boolean detail = type.startsWith("FUEL") || type.equals("PAYDAY");
            Gfx.disc(g, hx + 24, yy + 11, 8f, FTheme.alpha(pl ? FTheme.OK : FTheme.MUTED, 46));
            FUi.icon(g, pl ? "chev-down" : "chev-up", hx + 24, yy + 11, 9f, pl ? FTheme.OK : FTheme.MUTED);
            tx(g, clip(label(type) + (who.isEmpty() || detail ? "" : " " + who), hwid - 150, 7.6f, true), hx + 40, yy + 10, 7.6f, FTheme.TEXT);
            long ts = (long) JsonClientModule.num(h, "ts", 0);
            double fee = JsonClientModule.num(h, "f", 0);
            t(g, (ts > 0 ? DATE.format(new Date(ts)) : "") + (fee > 0 ? "  ·  taxă " + fee + " lei" : ""), hx + 40, yy + 20, 6.2f, FTheme.FAINT);
            double amt = JsonClientModule.num(h, "a", 0);
            tr(g, (pl ? "+" : "-") + num(Math.abs(amt)) + " lei", hx + hwid - 14, yy + 14, 8f, pl ? FTheme.OK : FTheme.TEXT);
            yy += rowH;
        }
        if (arr.size() > per) FUi.scrollbar(g, hx + hwid - 6, ly, per * rowH, arr.size(), per, scroll);

        drawModal(g, "Confirmă transferul", lei(a) + " către " + text("to").trim(), "Transferă", "Anulează", false, mx, my);
    }

    private void balanceCard(GuiGraphics g, String label, double v, float x, float y, float w, int color) {
        card(g, x, y, w, 50);
        Gfx.rrect(g, x + 10, y + 12, 3, 26, 1.5f, color);
        FTheme.caption(g, label, x + 20, y + 16, FTheme.FAINT);
        tx(g, clip(num(v) + " lei", w - 30, 10.5f, true), x + 20, y + 36, 10.5f, FTheme.TEXT);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
