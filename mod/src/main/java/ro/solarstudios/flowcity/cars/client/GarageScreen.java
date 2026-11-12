package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.cars.CarsNet;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Meniul garajului (deschis cu E la marker). Garaj public / de factiune: scoti masinile parcate.
 * Pound: vezi masinile ridicate sau pierdute si le scoti platind taxa (Vault, prin plugin).
 */
public final class GarageScreen extends FlowScreen {
    private static final int ROW = 36;
    private JsonObject data;
    private int selected;
    private int scroll;
    private boolean waiting;

    GarageScreen(JsonObject data) {
        super("Garaj", 400, 236);
        update(data);
        if (pound()) CarsClient.request("pound_info", new JsonObject());
    }

    void update(JsonObject o) {
        this.data = o;
        waiting = false;
        String msg = JsonClientModule.str(o, "msg", "");
        if (!msg.isEmpty()) toast(msg, !JsonClientModule.bool(o, "ok", true));
    }

    void payResult(boolean ok, String msg) {
        waiting = false;
        if (!msg.isEmpty()) toast(msg, !ok);
    }

    private boolean pound() {
        return "pound".equals(JsonClientModule.str(data, "type", ""));
    }

    private int gid() {
        return JsonClientModule.integer(data, "id", 0);
    }

    /** La pound apar doar masinile ridicate sau pierdute (scoase, dar care nu mai sunt nicaieri in lume). */
    private boolean listed(JsonObject c) {
        if (!pound()) return true;
        return JsonClientModule.bool(c, "pound", false) || (JsonClientModule.bool(c, "out", false) && !JsonClientModule.bool(c, "loaded", false));
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        String type = JsonClientModule.str(data, "type", "public");
        String sub = pound() ? "pound" : ("factiune".equals(type) ? "factiune: " + JsonClientModule.str(data, "faction", "") : "");
        header(ms, JsonClientModule.str(data, "name", "Garaj"), sub, mx, my);
        JsonArray all = data.has("cars") ? data.getAsJsonArray("cars") : new JsonArray();
        JsonArray cars = new JsonArray();
        for (int i = 0; i < all.size(); i++) if (listed(all.get(i).getAsJsonObject())) cars.add(all.get(i));

        int x = px + 10;
        int top = py + HEAD + 8;
        int listW = 180;
        int listH = py + ph - 10 - top;
        if (cars.size() == 0) {
            Ui.text(ms, pound() ? "Nu ai nicio masina la pound." : "Nu ai nicio masina aici.", x, top + 4, Theme.MUTED);
            if (!pound()) Ui.text(ms, "Cumpara una de la dealerul auto.", x, top + 16, Theme.FAINT);
            return;
        }
        JsonObject sel = null;
        for (int i = 0; i < cars.size(); i++) if (JsonClientModule.integer(cars.get(i).getAsJsonObject(), "id", 0) == selected) sel = cars.get(i).getAsJsonObject();
        if (sel == null) {
            sel = cars.get(0).getAsJsonObject();
            selected = JsonClientModule.integer(sel, "id", 0);
        }

        // lista
        int visible = Math.max(1, listH / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cars.size() - visible)));
        int y = top;
        for (int i = scroll; i < Math.min(cars.size(), scroll + visible); i++) {
            JsonObject c = cars.get(i).getAsJsonObject();
            int id = JsonClientModule.integer(c, "id", 0);
            boolean s = id == selected;
            boolean h = Ui.in(mx, my, x, y, listW, ROW - 2);
            if (s || h) Ui.rect(ms, x, y, listW, ROW - 2, s ? Theme.PANEL_3 : 0xFF22252B);
            if (s) Ui.rect(ms, x, y, 2, ROW - 2, Theme.ACCENT);
            Ui.round(ms, x + 8, y + 9, 14, 14, 0xFF000000 | JsonClientModule.integer(c, "color", 0xFFFFFF));
            Ui.text(ms, Ui.clip(JsonClientModule.str(c, "name", "?"), listW - 36), x + 28, y + 6, s ? Theme.TEXT : 0xFFD4D2CC);
            plate(ms, JsonClientModule.str(c, "plate", ""), x + 28, y + 18);
            String st;
            int col;
            if (JsonClientModule.bool(c, "pound", false)) {
                st = "la pound";
                col = Theme.BAD;
            } else if (JsonClientModule.bool(c, "out", false)) {
                st = JsonClientModule.bool(c, "loaded", false) ? "in oras" : "pierduta";
                col = Theme.WARN;
            } else {
                st = "parcata";
                col = Theme.OK;
            }
            Ui.textRight(ms, st, x + listW - 6, y + 19, col);
            Ui.rect(ms, x + 8, y + ROW - 2, listW - 16, 1, Theme.LINE);
            hit(x, y, listW, ROW - 2, () -> selected = id);
            y += ROW;
        }

        // detalii
        int dx = x + listW + 12;
        int dw = px + pw - 10 - dx;
        Ui.rect(ms, dx - 6, top, 1, py + ph - 10 - top, Theme.LINE);
        int dy = top + 2;
        Ui.textShadow(ms, JsonClientModule.str(sel, "name", "?"), dx, dy, Theme.TEXT);
        dy += 13;
        plate(ms, JsonClientModule.str(sel, "plate", "-"), dx, dy);
        dy += 18;
        if (JsonClientModule.bool(sel, "needsFuel", true)) {
            float cap = (float) JsonClientModule.num(sel, "fuelCap", 0);
            float f = cap <= 0 ? 0 : (float) JsonClientModule.num(sel, "fuel", 0) / cap;
            stat(ms, "Benzina", f, dx, dy, dw, f < 0.15f ? Theme.BAD : Theme.ACCENT);
            dy += 22;
        }
        float mh = (float) JsonClientModule.num(sel, "maxHealth", 0);
        float hp = mh <= 0 ? 1 : (float) JsonClientModule.num(sel, "health", 0) / mh;
        stat(ms, "Caroserie", hp, dx, dy, dw, hp < 0.3f ? Theme.BAD : Theme.OK);
        dy += 26;

        final int id = selected;
        int by = py + ph - 28;
        boolean out = JsonClientModule.bool(sel, "out", false);
        if (pound()) {
            String reason = JsonClientModule.str(sel, "reason", "");
            Ui.text(ms, JsonClientModule.bool(sel, "pound", false) ? "Ridicata de politie" : "Gasita abandonata in oras", dx, dy, Theme.MUTED);
            if (!reason.isEmpty()) Ui.text(ms, Ui.clip("Motiv: " + reason, dw), dx, dy + 11, Theme.FAINT);
            double fee = CarsClient.poundFee;
            boolean afford = CarsClient.money >= fee;
            String label = waiting ? "Se proceseaza..." : (afford ? "Plateste " + Ui.money(fee) + " lei" : "Nu ai " + Ui.money(fee) + " lei");
            button(ms, label, dx, by, dw, 18, mx, my, !waiting && afford, Theme.ACCENT, () -> {
                waiting = true;
                JsonObject o = new JsonObject();
                o.addProperty("car", id);
                o.addProperty("garage", gid());
                CarsClient.request("pound_pay", o);
            });
        } else if (JsonClientModule.bool(sel, "pound", false)) {
            Ui.text(ms, "Masina a fost ridicata.", dx, dy, Theme.BAD);
            Ui.text(ms, "Mergi la pound sa o scoti.", dx, dy + 11, Theme.FAINT);
        } else if (out) {
            if (JsonClientModule.bool(sel, "loaded", false)) {
                Ui.text(ms, "E in oras, la X " + JsonClientModule.integer(sel, "lx", 0) + "  Z " + JsonClientModule.integer(sel, "lz", 0), dx, dy, Theme.MUTED);
                Ui.text(ms, "Intra cu ea in zona de parcare (P)", dx, dy + 11, Theme.FAINT);
                Ui.text(ms, "si apasa E ca s-o parchezi.", dx, dy + 22, Theme.FAINT);
            } else {
                Ui.text(ms, "Nu mai e in oras.", dx, dy, Theme.WARN);
                Ui.text(ms, "O gasesti la pound.", dx, dy + 11, Theme.FAINT);
            }
        } else {
            Ui.text(ms, "Parcata aici.", dx, dy, Theme.FAINT);
            button(ms, waiting ? "Se scoate..." : "Scoate masina", dx, by, dw, 18, mx, my, !waiting, Theme.ACCENT, () -> {
                waiting = true;
                CarsNet.send("spawn", gid(), id);
            });
        }
    }

    /** Numar de inmatriculare: placuta alba cu banda albastra in stanga. */
    private static void plate(GuiGraphics ms, String plate, int x, int y) {
        if (plate == null || plate.isEmpty()) return;
        int w = Ui.width(plate) + 12;
        Ui.round(ms, x, y - 1, w, 10, 0xFFEDEDE8);
        Ui.rect(ms, x + 1, y, 4, 8, 0xFF2747A0);
        Ui.text(ms, plate, x + 8, y, 0xFF16181D);
    }

    private static void stat(GuiGraphics ms, String label, float f, int x, int y, int w, int color) {
        Ui.text(ms, label, x, y, Theme.MUTED);
        Ui.textRight(ms, Math.round(Math.max(0, f) * 100) + "%", x + w, y, Theme.TEXT);
        Ui.bar(ms, x, y + 10, w, 4, f, color);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }
}
