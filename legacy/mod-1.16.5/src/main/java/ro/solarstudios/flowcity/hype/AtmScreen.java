package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** ATM: depunere, retragere, transfer bancar + istoric. Serverul valideaza tot; aici doar cerem actiuni. */
public final class AtmScreen extends FlowScreen {
    private final TextFieldWidget amount = field("Suma", 12, null);
    private final TextFieldWidget target = field("Jucator", 16, null);

    public AtmScreen() {
        super("ATM", 340, 200);
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        l.add(amount);
        l.add(target);
        return l;
    }

    private double parseAmount() {
        try {
            return Double.parseDouble(amount.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void act(String action) {
        double a = parseAmount();
        if (a <= 0) {
            toast("Introdu o suma valida.", true);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("amount", a);
        if ("atm_transfer".equals(action)) {
            String t = target.getText().trim();
            if (t.isEmpty()) {
                toast("Alege un jucator.", true);
                return;
            }
            o.addProperty("to", t);
        }
        click();
        HypeClient.request(action, o);
    }

    private static String label(String type) {
        switch (type) {
            case "DEPOSIT": return "Depunere";
            case "WITHDRAW": return "Retragere";
            case "TRANSFER_OUT": return "Transfer catre";
            case "TRANSFER_IN": return "Primit de la";
            default: return type;
        }
    }

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        JsonObject d = HypeState.atm;
        header(ms, "ATM", "FlowCity Bank", mx, my);
        int lx = px + 10;
        int top = py + HEAD + 10;
        int colW = 150;

        // solduri
        Ui.frame(ms, lx, top, colW, 40, Theme.PANEL_2, Theme.LINE);
        Ui.text(ms, "Cash", lx + 8, top + 6, Theme.MUTED);
        Ui.text(ms, "$" + Ui.money(JsonClientModule.num(d, "cash", 0)), lx + 8, top + 18, Theme.OK);
        Ui.text(ms, "Banca", lx + 80, top + 6, Theme.MUTED);
        Ui.text(ms, "$" + Ui.money(JsonClientModule.num(d, "bank", 0)), lx + 80, top + 18, Theme.ACCENT);

        // suma + depunere/retragere
        int y = top + 48;
        drawField(ms, amount, lx, y, colW, 16, mx, my);
        y += 22;
        button(ms, "Depune", lx, y, 72, 16, mx, my, true, Theme.ACCENT_DIM, () -> act("atm_deposit"));
        button(ms, "Retrage", lx + 78, y, 72, 16, mx, my, true, Theme.ACCENT_DIM, () -> act("atm_withdraw"));
        y += 26;

        // transfer
        Ui.text(ms, "Transfer bancar", lx, y, Theme.MUTED);
        y += 11;
        drawField(ms, target, lx, y, colW, 16, mx, my);
        y += 22;
        button(ms, "Transfera", lx, y, colW, 16, mx, my, true, 0xFF4B4FA8, () -> act("atm_transfer"));
        y += 20;
        double fp = JsonClientModule.num(d, "feePercent", 0);
        Ui.text(ms, "Taxa transfer: " + fp + "% (min $" + JsonClientModule.num(d, "feeMin", 0) + ")", lx, y, Theme.FAINT);
        double wl = JsonClientModule.num(d, "withdrawLeft", -1);
        if (wl >= 0) Ui.text(ms, "Retragere ramasa azi: $" + Ui.money(wl), lx, y + 10, Theme.FAINT);

        // istoric
        int hx = px + 10 + colW + 12;
        int hw = pw - (hx - px) - 10;
        Ui.text(ms, "Ultimele tranzactii", hx, top, Theme.MUTED);
        int hy = top + 14;
        if (d.has("history") && d.get("history").isJsonArray()) {
            JsonArray arr = d.getAsJsonArray("history");
            int shown = 0;
            for (JsonElement el : arr) {
                if (shown >= 11 || !el.isJsonObject()) break;
                JsonObject h = el.getAsJsonObject();
                String type = JsonClientModule.str(h, "t", "");
                boolean plus = type.equals("TRANSFER_IN");
                String who = JsonClientModule.str(h, "o", "");
                String left = label(type) + (who.isEmpty() ? "" : " " + who);
                Ui.text(ms, Ui.clip(left, hw - 60), hx, hy, Theme.TEXT);
                Ui.textRight(ms, (plus ? "+" : "") + Ui.money(JsonClientModule.num(h, "a", 0)), hx + hw, hy, plus ? Theme.OK : Theme.MUTED);
                hy += 12;
                shown++;
            }
            if (shown == 0) Ui.text(ms, "Nicio tranzactie.", hx, hy, Theme.FAINT);
        }
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        act(f == target ? "atm_transfer" : "atm_deposit");
    }
}
