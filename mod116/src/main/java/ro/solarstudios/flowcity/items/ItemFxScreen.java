package ro.solarstudios.flowcity.items;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.FScreen;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Panou generic pentru itemele cu functie: titlu, randuri cheie/valoare (cu bara optionala) si butoane. Continutul vine integral de la
 * server; un buton trimite doar identificatorul lui, iar serverul valideaza din nou totul (item la jucator, buton existent, drepturi).
 */
public final class ItemFxScreen extends FScreen {
    private final String panelId;
    private final String title;
    private final String sub;
    private final List<String[]> rows = new ArrayList<String[]>();
    private final List<String[]> buttons = new ArrayList<String[]>();

    public ItemFxScreen(JsonObject d) {
        super("Iteme", 340, heightFor(d));
        this.panelId = JsonClientModule.str(d, "id", "");
        this.title = JsonClientModule.str(d, "title", "");
        this.sub = JsonClientModule.str(d, "sub", "");
        for (JsonElement e : arr(d, "rows")) {
            JsonObject o = e.getAsJsonObject();
            rows.add(new String[] {JsonClientModule.str(o, "k", ""), JsonClientModule.str(o, "v", ""), JsonClientModule.str(o, "tone", "info"),
                o.has("pct") ? Integer.toString(JsonClientModule.integer(o, "pct", 0)) : ""});
        }
        for (JsonElement e : arr(d, "buttons")) {
            JsonObject o = e.getAsJsonObject();
            buttons.add(new String[] {JsonClientModule.str(o, "id", ""), JsonClientModule.str(o, "label", ""), JsonClientModule.str(o, "tone", "info")});
        }
    }

    private static JsonArray arr(JsonObject d, String k) {
        return d.has(k) && d.get(k).isJsonArray() ? d.getAsJsonArray(k) : new JsonArray();
    }

    private static int heightFor(JsonObject d) {
        int n = Math.min(14, arr(d, "rows").size());
        int b = Math.min(18, arr(d, "buttons").size());
        return Math.max(150, Math.min(520, 70 + n * 22 + ((b + 1) / 2) * 28 + 20));
    }

    private static int tone(String t) {
        if ("ok".equals(t)) return FTheme.OK;
        if ("warn".equals(t)) return FTheme.WARN;
        if ("bad".equals(t)) return FTheme.DANGER;
        return FTheme.TEXT;
    }

    private static int style(String t) {
        if ("ok".equals(t)) return FUi.PRIMARY;
        if ("bad".equals(t)) return FUi.DANGER;
        if ("warn".equals(t)) return FUi.SOFT;
        return FUi.GHOST;
    }

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        shell(g);
        top(g, title, sub, mx, my);
        float x = px + 20;
        float w = pw - 40;
        float y = py + 62;
        int shown = 0;
        for (String[] r : rows) {
            if (shown++ >= 14) break;
            t(g, clip(r[0], w * 0.38f, 7.4f, false), x, y + 8, 7.4f, FTheme.MUTED);
            tr(g, clip(r[1], w * 0.60f, 8.2f, true), x + w, y + 8, 8.2f, tone(r[2]));
            if (!r[3].isEmpty()) {
                float frac = Math.max(0f, Math.min(1f, Integer.parseInt(r[3]) / 100f));
                Gfx.bar(g, x, y + 13, w, 4, frac, FTheme.LINE, tone(r[2]));
            }
            y += 22;
        }
        y += 8;
        float bw = (w - 8) / 2f;
        int i = 0;
        for (final String[] b : buttons) {
            if (i >= 18) break;
            float bx = x + (i % 2) * (bw + 8);
            float by = y + (i / 2) * 28;
            btn(g, "ifx-" + b[0], FScreen.clip(b[1], bw - 16, 7.6f, true), null, bx, by, bw, 22, style(b[2]), true, mx, my, () -> ItemFxClient.act(panelId, b[0]));
            i++;
        }
    }

    @Override
    public void onClose() {
        ItemFxClient.closed();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
