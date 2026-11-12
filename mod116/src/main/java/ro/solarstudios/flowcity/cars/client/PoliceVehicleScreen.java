package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Control de politie la un vehicul (E cand te uiti la el): numar, proprietar, amenda pentru parcare neregulamentara
 * (se trece in MDT la proprietar) si ridicare la pound. Verificarea de politist o face pluginul (MDT).
 */
public final class PoliceVehicleScreen extends FlowScreen {
    private static final int[] AMOUNTS = {100, 250, 500, 1000};
    private static final String[] REASONS = {"Parcare neregulamentara", "Parcat pe trotuar", "Parcat pe loc pentru persoane cu dizabilitati", "Blocare intrare / drum"};
    private JsonObject data;
    private int amount = AMOUNTS[1];
    private int reason;
    private final TextFieldWidget custom;
    private boolean waiting;

    PoliceVehicleScreen(JsonObject data) {
        super("Control vehicul", 330, 214);
        this.custom = field("Alt motiv (optional)", 60, null);
        update(data);
    }

    void update(JsonObject o) {
        this.data = o;
    }

    void result(boolean ok, String msg) {
        waiting = false;
        toast(msg, !ok);
    }

    @Override
    protected List<TextFieldWidget> fields() {
        return Collections.singletonList(custom);
    }

    private String reasonText() {
        String c = custom.getValue().trim();
        return c.isEmpty() ? REASONS[reason] : c;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        header(ms, "Control vehicul", "politie", mx, my);
        int x = px + 12;
        int y = py + HEAD + 10;
        int w = pw - 24;
        String plate = JsonClientModule.str(data, "plate", "");
        Ui.textShadow(ms, JsonClientModule.str(data, "name", JsonClientModule.str(data, "model", "Vehicul")), x, y, Theme.TEXT);
        if (!plate.isEmpty()) {
            int pwid = Ui.width(plate) + 12;
            Ui.round(ms, x + w - pwid, y - 1, pwid, 10, 0xFFEDEDE8);
            Ui.rect(ms, x + w - pwid + 1, y, 4, 8, 0xFF2747A0);
            Ui.text(ms, plate, x + w - pwid + 8, y, 0xFF16181D);
        }
        y += 13;
        Ui.text(ms, "Proprietar: " + JsonClientModule.str(data, "owner", "-"), x, y, Theme.MUTED);
        y += 18;
        Ui.rect(ms, x, y - 4, w, 1, Theme.LINE);

        Ui.smallCaps(ms, "Amenda", x, y, Theme.MUTED);
        y += 12;
        int cx = x;
        for (int a : AMOUNTS) {
            final int aa = a;
            String l = a + " lei";
            int cw = Ui.width(l) + 12;
            cx += chip(ms, l, cx, y, cw, 14, mx, my, amount == a, Theme.ACCENT, () -> amount = aa) + 4;
        }
        y += 20;
        for (int i = 0; i < REASONS.length; i++) {
            final int ii = i;
            boolean s = reason == i && custom.getValue().trim().isEmpty();
            boolean h = Ui.in(mx, my, x, y, w, 12);
            Ui.round(ms, x + 1, y + 2, 7, 7, s ? Theme.ACCENT : Theme.PANEL_3);
            Ui.text(ms, Ui.clip(REASONS[i], w - 14), x + 13, y + 2, s || h ? Theme.TEXT : Theme.MUTED);
            hit(x, y, w, 12, () -> {
                reason = ii;
                custom.setValue("");
            });
            y += 13;
        }
        drawField(ms, custom, x, y + 2, w, 16, mx, my);

        int by = py + ph - 28;
        int bw = (w - 8) / 2;
        boolean canFine = !waiting && !plate.isEmpty();
        button(ms, plate.isEmpty() ? "Fara numar - nu se poate" : "Da amenda (" + amount + " lei)", x, by, bw, 18, mx, my, canFine, Theme.ACCENT, () -> {
            waiting = true;
            JsonObject o = new JsonObject();
            o.addProperty("plate", plate);
            o.addProperty("amount", amount);
            o.addProperty("reason", reasonText());
            CarsClient.request("parking_fine", o);
        });
        button(ms, "Ridica la pound", x + bw + 8, by, bw, 18, mx, my, !waiting, 0xFF6B2F2B, () -> {
            waiting = true;
            JsonObject o = new JsonObject();
            o.addProperty("entity", JsonClientModule.str(data, "entity", ""));
            o.addProperty("reason", reasonText());
            CarsClient.request("impound", o);
        });
    }
}
