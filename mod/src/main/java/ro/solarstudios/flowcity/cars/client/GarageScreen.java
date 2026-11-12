package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import ro.solarstudios.flowcity.cars.CarsNet;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Garajul: lista masinilor tale, cu benzina si starea; le scoti, le parchezi sau le recuperezi. */
public final class GarageScreen extends FlowScreen {
    private static final int ROW = 34;
    private final BlockPos pos;
    private JsonObject data = new JsonObject();
    private int selected;
    private int scroll;
    private boolean waiting = true;

    private GarageScreen(BlockPos pos) {
        super("Garaj", 380, 230);
        this.pos = pos;
        CarsNet.send(pos, "list", 0);
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new GarageScreen(pos));
    }

    /** Raspunsul serverului (lista + mesaj dupa actiune). */
    public static void receive(String json) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof GarageScreen gs)) return;
        try {
            gs.data = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException ex) {
            return;
        }
        gs.waiting = false;
        String msg = JsonClientModule.str(gs.data, "msg", "");
        if (!msg.isEmpty()) gs.toast(msg, !JsonClientModule.bool(gs.data, "ok", true));
    }

    private void act(String a, int id) {
        waiting = true;
        CarsNet.send(pos, a, id);
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        header(ms, "Garaj", "", mx, my);
        JsonArray cars = data.has("cars") ? data.getAsJsonArray("cars") : new JsonArray();
        int x = px + 10;
        int top = py + HEAD + 8;
        int listW = 170;
        int listH = py + ph - 34 - top;

        if (cars.size() == 0) {
            Ui.text(ms, waiting ? "Se incarca..." : "Nu ai nicio masina.", x, top + 4, Theme.MUTED);
            if (!waiting) Ui.text(ms, "Cumpara una de la dealerul auto.", x, top + 16, Theme.FAINT);
        }
        // lista
        int visible = Math.max(1, listH / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cars.size() - visible)));
        JsonObject sel = null;
        for (int i = 0; i < cars.size(); i++) {
            JsonObject c = cars.get(i).getAsJsonObject();
            if (selected == 0 || JsonClientModule.integer(c, "id", 0) == selected) {
                if (sel == null || JsonClientModule.integer(c, "id", 0) == selected) sel = c;
            }
        }
        if (sel != null) selected = JsonClientModule.integer(sel, "id", 0);
        int y = top;
        for (int i = scroll; i < Math.min(cars.size(), scroll + visible); i++) {
            JsonObject c = cars.get(i).getAsJsonObject();
            int id = JsonClientModule.integer(c, "id", 0);
            boolean s = id == selected;
            boolean h = Ui.in(mx, my, x, y, listW, ROW - 2);
            if (s || h) Ui.rect(ms, x, y, listW, ROW - 2, s ? Theme.PANEL_3 : 0xFF22252B);
            if (s) Ui.rect(ms, x, y, 2, ROW - 2, Theme.ACCENT);
            Ui.round(ms, x + 7, y + 8, 14, 14, 0xFF000000 | JsonClientModule.integer(c, "color", 0xFFFFFF));
            Ui.text(ms, Ui.clip(JsonClientModule.str(c, "name", "?"), listW - 34), x + 27, y + 6, s ? Theme.TEXT : 0xFFD4D2CC);
            boolean out = JsonClientModule.bool(c, "out", false);
            Ui.text(ms, JsonClientModule.str(c, "plate", ""), x + 27, y + 18, Theme.FAINT);
            Ui.textRight(ms, out ? "in oras" : "parcata", x + listW - 6, y + 18, out ? Theme.WARN : Theme.OK);
            Ui.rect(ms, x + 8, y + ROW - 2, listW - 16, 1, Theme.LINE);
            hit(x, y, listW, ROW - 2, () -> selected = id);
            y += ROW;
        }

        // detalii
        int dx = x + listW + 12;
        int dw = px + pw - 10 - dx;
        Ui.rect(ms, dx - 6, top, 1, py + ph - 10 - top, Theme.LINE);
        if (sel == null) return;
        int dy = top + 2;
        Ui.textShadow(ms, JsonClientModule.str(sel, "name", "?"), dx, dy, Theme.TEXT);
        dy += 12;
        Ui.text(ms, "Nr. " + JsonClientModule.str(sel, "plate", "-"), dx, dy, Theme.MUTED);
        dy += 18;
        if (JsonClientModule.bool(sel, "needsFuel", true)) {
            float cap = (float) JsonClientModule.num(sel, "fuelCap", 0);
            float fuel = (float) JsonClientModule.num(sel, "fuel", 0);
            float f = cap <= 0 ? 0 : fuel / cap;
            Ui.text(ms, "Benzina", dx, dy, Theme.MUTED);
            Ui.textRight(ms, Math.round(f * 100) + "%", dx + dw, dy, f < 0.15f ? Theme.BAD : Theme.TEXT);
            Ui.bar(ms, dx, dy + 10, dw, 4, f, f < 0.15f ? Theme.BAD : Theme.ACCENT);
            dy += 22;
        }
        float mh = (float) JsonClientModule.num(sel, "maxHealth", 0);
        float hp = mh <= 0 ? 1 : (float) JsonClientModule.num(sel, "health", 0) / mh;
        Ui.text(ms, "Stare", dx, dy, Theme.MUTED);
        Ui.textRight(ms, Math.round(hp * 100) + "%", dx + dw, dy, hp < 0.3f ? Theme.BAD : Theme.TEXT);
        Ui.bar(ms, dx, dy + 10, dw, 4, hp, hp < 0.3f ? Theme.BAD : Theme.OK);
        dy += 26;

        boolean out = JsonClientModule.bool(sel, "out", false);
        boolean loaded = JsonClientModule.bool(sel, "loaded", false);
        final int id = selected;
        int by = py + ph - 28;
        if (!out) {
            Ui.text(ms, "Masina e parcata aici.", dx, dy, Theme.FAINT);
            button(ms, "Scoate masina", dx, by, dw, 18, mx, my, !waiting, Theme.ACCENT, () -> act("spawn", id));
        } else if (loaded) {
            int dist = JsonClientModule.integer(sel, "dist", 0);
            Ui.text(ms, "In oras, la " + dist + " m de garaj.", dx, dy, Theme.FAINT);
            Ui.text(ms, "Adu-o pana la 24 m si parcheaz-o.", dx, dy + 11, Theme.FAINT);
            button(ms, "Parcheaza", dx, by, dw, 18, mx, my, !waiting && dist <= 24, Theme.ACCENT, () -> act("store", 0));
        } else {
            Ui.text(ms, "Nu e prin preajma (sau s-a pierdut).", dx, dy, Theme.FAINT);
            ghost(ms, "Recupereaza in garaj", dx, by, dw, 18, mx, my, () -> act("recover", id));
        }
        // parcheaza masina in care stai, indiferent de selectie
        if (Minecraft.getInstance().player.isPassenger()) {
            ghost(ms, "Parcheaza masina in care esti", x, py + ph - 28, listW, 18, mx, my, () -> act("store", 0));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }
}
