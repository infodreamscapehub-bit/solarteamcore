package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.FScreen;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Pound-ul (vehicule ridicate): lista masinilor tale ridicate sau pierdute, cu detalii (model, numar, proprietar, motiv, data,
 * taxa, stare) si recuperare cu confirmarea platii. Plata trece prin sistemul de plata existent (cash sau card), iar
 * serverul verifica din nou taxa si soldul. Administrarea (ridicare, eliberare) ramane in FAdmin si in tableta politiei.
 */
final class PoundScreen extends FScreen {
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM.yyyy HH:mm");
    private static final int LISTW = 210;

    private JsonObject data;
    private int filter;
    private int selected = -1;
    private int scroll;
    private boolean waiting;

    PoundScreen(JsonObject data) {
        super("Pound", 560, 330);
        update(data);
        CarsClient.request("pound_info", new JsonObject());
    }

    void update(JsonObject o) {
        data = o;
        waiting = false;
        String msg = JsonClientModule.str(o, "msg", "");
        if (!msg.isEmpty()) toast(msg, !JsonClientModule.bool(o, "ok", true));
    }

    void payResult(boolean ok, String msg) {
        waiting = false;
        if (!msg.isEmpty()) toast(msg, !ok);
    }

    private int gid() {
        return JsonClientModule.integer(data, "id", 0);
    }

    /** Masinile care apar la pound: ridicate de politie sau pierdute (scoase, dar nemaigasite in lume). */
    private List<JsonObject> cars() {
        List<JsonObject> out = new ArrayList<>();
        JsonArray a = data.has("cars") && data.get("cars").isJsonArray() ? data.getAsJsonArray("cars") : new JsonArray();
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            JsonObject c = e.getAsJsonObject();
            boolean pound = JsonClientModule.bool(c, "pound", false);
            boolean lost = JsonClientModule.bool(c, "out", false) && !JsonClientModule.bool(c, "loaded", false);
            if (!pound && !lost) continue;
            if (filter == 1 && !pound) continue;
            if (filter == 2 && !lost) continue;
            out.add(c);
        }
        return out;
    }

    private static String status(JsonObject c) {
        return JsonClientModule.bool(c, "pound", false) ? "CONFISCATĂ" : "PIERDUTĂ";
    }

    private static int statusColor(JsonObject c) {
        return JsonClientModule.bool(c, "pound", false) ? FTheme.DANGER : FTheme.WARN;
    }

    private static String when(JsonObject c) {
        long ts = (long) JsonClientModule.num(c, "poundAt", 0);
        return ts <= 0 ? "necunoscută" : DATE.format(new Date(ts));
    }

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        shell(g);
        top(g, "Pound", JsonClientModule.str(data, "name", "Vehicule ridicate"), mx, my);
        balance(g, "SOLD", CarsClient.money, px + pw - 40, py + 16);

        float x0 = px + 16;
        float y0 = py + 60;
        float h = ph - 76;
        List<JsonObject> rows = cars();
        if (selected >= 0 && rows.stream().noneMatch(r -> JsonClientModule.integer(r, "id", -1) == selected)) selected = -1;
        if (selected < 0 && !rows.isEmpty()) selected = JsonClientModule.integer(rows.get(0), "id", -1);

        // filtre
        String[] fl = {"Toate", "Confiscate", "Pierdute"};
        float fx = x0;
        for (int i = 0; i < fl.length; i++) {
            final int fi = i;
            fx += chipAt(g, "pf" + i, fl[i], fx, y0, 18, filter == i, mx, my, () -> {
                filter = fi;
                scroll = 0;
            });
        }

        // lista
        float ly = y0 + 26;
        float lh = h - 26;
        int per = 4;
        float rowH = 58f;
        per = Math.max(1, (int) (lh / (rowH + 6)));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - per)));
        if (rows.isEmpty()) {
            FUi.empty(g, "check", "Nicio mașină la pound", "Mașinile ridicate de poliție apar aici.", x0 + LISTW / 2f, ly + lh / 2f - 10, LISTW);
        }
        float y = ly;
        for (int i = scroll; i < Math.min(rows.size(), scroll + per); i++) {
            JsonObject c = rows.get(i);
            int id = JsonClientModule.integer(c, "id", -1);
            boolean sel = id == selected;
            boolean hov = FUi.card(g, "pc" + id, x0, y, LISTW, rowH, true, mx, my);
            if (sel) FTheme.panel(g, x0, y, LISTW, rowH, FTheme.R_MD, FTheme.alpha(FTheme.accent, 30), FTheme.accent);
            tx(g, clip(JsonClientModule.str(c, "name", "Vehicul"), LISTW - 24, 8.6f, true), x0 + 12, y + 18, 8.6f, FTheme.TEXT);
            plate(g, JsonClientModule.str(c, "plate", ""), x0 + 12, y + 26);
            pill(g, status(c), x0 + 12, y + 42, statusColor(c));
            hit((int) x0, (int) y, LISTW, (int) rowH, () -> selected = id);
            y += rowH + 6;
        }
        if (rows.size() > per) FUi.scrollbar(g, x0 + LISTW + 3, ly, lh, rows.size(), per, scroll);

        // detalii
        float dx = x0 + LISTW + 16;
        float dw = px + pw - 16 - dx;
        card(g, dx, y0, dw, h);
        JsonObject sel = null;
        for (JsonObject r : rows) if (JsonClientModule.integer(r, "id", -1) == selected) sel = r;
        if (sel == null) {
            FUi.empty(g, "route", "Alege o mașină", "Detaliile și recuperarea apar aici.", dx + dw / 2f, y0 + h / 2f - 10, dw - 40);
            return;
        }
        drawDetails(g, sel, dx, y0, dw, h, mx, my);
        drawModal(g, "Recuperare vehicul",
            "Plătești " + lei(CarsClient.poundFee) + " pentru " + JsonClientModule.str(sel, "name", "vehicul") + ".",
            "Plătește", "Anulează", false, mx, my);
    }

    private void plate(GuiGraphics g, String plate, float x, float y) {
        if (plate == null || plate.isEmpty()) return;
        float w = Gfx.width(plate, 6.8f, true) + 14f;
        Gfx.rrect(g, x, y, w, 12f, 3f, 0xFFF2F2F0);
        Gfx.rrect(g, x, y, 5f, 12f, 3f, 0xFF2F6FDB);
        Gfx.text(g, plate, x + 9.5f, y + 8.6f, 6.8f, 0xFF15181D, 0, true, 0f);
    }

    private void drawDetails(GuiGraphics g, JsonObject c, float x, float y, float w, float h, int mx, int my) {
        float cx = x + 16;
        float cw = w - 32;
        tx(g, clip(JsonClientModule.str(c, "name", "Vehicul"), cw - 90, 15f, true), cx, y + 28, 15f, FTheme.TEXT);
        pill(g, status(c), x + w - 16 - (Gfx.width(status(c), 6.4f, true) + 10f), y + 16, statusColor(c));
        plate(g, JsonClientModule.str(c, "plate", ""), cx, y + 38);
        int paint = 0xFF000000 | JsonClientModule.integer(c, "color", 0x8E959E);
        Gfx.disc(g, cx + 70f, y + 44f, 5f, paint);
        FTheme.divider(g, cx, y + 60, cw);

        String reason = JsonClientModule.str(c, "reason", "");
        boolean pound = JsonClientModule.bool(c, "pound", false);
        String[][] info = {
            {"PROPRIETAR", "Tu"},
            {"CONFISCATĂ LA", pound ? when(c) : "—"},
            {"MOTIV", pound ? (reason.isEmpty() ? "Nespecificat" : reason) : "Găsită abandonată în oraș"},
            {"TAXĂ RECUPERARE", lei(CarsClient.poundFee)},
        };
        float iy = y + 72;
        float colW = cw / 2f;
        for (int i = 0; i < info.length; i++) {
            float ix = cx + (i % 2) * colW;
            float yy = iy + (i / 2) * 34;
            FTheme.caption(g, info[i][0], ix, yy + 6, FTheme.FAINT);
            tx(g, clip(info[i][1], colW - 10, 8.4f, true), ix, yy + 19, 8.4f, FTheme.TEXT);
        }

        // stare tehnica
        float by = iy + 76;
        float fuel = (float) JsonClientModule.num(c, "fuel", 0);
        float cap = (float) JsonClientModule.num(c, "fuelCap", 0);
        float hp = (float) JsonClientModule.num(c, "health", 0);
        float mhp = (float) JsonClientModule.num(c, "maxHealth", 0);
        if (JsonClientModule.bool(c, "needsFuel", true) && cap > 0) {
            FUi.progress(g, "COMBUSTIBIL", Math.round(fuel) + " / " + Math.round(cap) + " L", cx, by, cw, fuel / cap, FTheme.WARN);
            by += 24;
        }
        if (mhp > 0) {
            FUi.progress(g, "CAROSERIE", Math.round(hp / mhp * 100f) + "%", cx, by, cw, hp / mhp, FTheme.OK);
            by += 24;
        }
        t(g, "Kilometraj  " + num(JsonClientModule.num(c, "odo", 0) / 1000.0) + " km", cx, by + 8, 7f, FTheme.MUTED);

        // actiune
        boolean afford = CarsClient.money >= CarsClient.poundFee;
        float bw = cw;
        float bh = 28f;
        float byy = y + h - 16 - bh;
        if (!afford) {
            FUi.message(g, FUi.MSG_ERROR, "Fonduri insuficiente pentru taxă.", cx, byy - 32, cw);
        }
        String label = waiting ? "Se procesează..." : "Recuperează · " + lei(CarsClient.poundFee);
        boolean en = !waiting && afford && !modalActive();
        final int id = JsonClientModule.integer(c, "id", 0);
        btn(g, "pound-pay", label, "check", cx, byy, bw, bh, FUi.PRIMARY, en, mx, my, () -> openModal(() -> {
            waiting = true;
            JsonObject o = new JsonObject();
            o.addProperty("car", id);
            o.addProperty("garage", gid());
            CarsClient.request("pound_pay", o);
        }));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }
}
