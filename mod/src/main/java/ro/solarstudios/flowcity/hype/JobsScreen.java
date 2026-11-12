package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.maps.MapsClient;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Centrul de joburi: lista (filtrata pe categorie) in stanga, detaliile jobului ales in dreapta. */
public final class JobsScreen extends FlowScreen {
    private static final int LIST_W = 150;
    private static final int ROW_H = 30;

    private String category = "";
    private String selected = "";
    private int scroll;

    public JobsScreen() {
        super("Joburi", 430, 250);
    }

    private void request(String action, String id) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        HypeClient.request(action, o);
    }

    private static int difficultyColor(String d) {
        String s = d == null ? "" : d.toLowerCase();
        if (s.startsWith("gre") || s.startsWith("hard")) return Theme.BAD;
        if (s.startsWith("med")) return Theme.WARN;
        return Theme.OK;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        JsonObject d = HypeState.jobs;
        String current = JsonClientModule.str(d, "current", "");
        long cd = JsonClientModule.lng(d, "cooldownMs", 0) - (System.currentTimeMillis() - HypeState.jobsAt);
        header(ms, "Centrul de joburi", "", mx, my);

        if (!d.has("jobs") || !d.get("jobs").isJsonArray()) {
            Ui.text(ms, "Se incarca...", px + 14, py + HEAD + 14, Theme.FAINT);
            return;
        }
        JsonArray all = d.getAsJsonArray("jobs");
        Set<String> cats = new LinkedHashSet<>();
        for (JsonElement el : all) if (el.isJsonObject()) cats.add(JsonClientModule.str(el.getAsJsonObject(), "category", "Diverse"));

        int top = py + HEAD + 6;
        int lx = px + 8;

        // filtre pe categorie
        int cx = lx;
        cx += chip(ms, "Toate", cx, top, Ui.width("Toate") + 10, 14, mx, my, category.isEmpty(), Theme.ACCENT, () -> {
            category = "";
            scroll = 0;
        }) + 3;
        for (String c : cats) {
            int w = Ui.width(c) + 10;
            if (cx + w > lx + LIST_W) break;
            final String cat = c;
            cx += chip(ms, c, cx, top, w, 14, mx, my, c.equals(category), Theme.VIOLET, () -> {
                category = cat;
                scroll = 0;
            }) + 3;
        }

        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement el : all) {
            if (!el.isJsonObject()) continue;
            JsonObject j = el.getAsJsonObject();
            if (category.isEmpty() || category.equals(JsonClientModule.str(j, "category", "Diverse"))) rows.add(j);
        }
        if (selected.isEmpty() || rows.stream().noneMatch(r -> selected.equals(JsonClientModule.str(r, "id", "")))) {
            selected = rows.isEmpty() ? "" : JsonClientModule.str(current.isEmpty() ? rows.get(0) : findOr(rows, current), "id", "");
        }

        // lista
        int listTop = top + 20;
        int listH = py + ph - 8 - listTop;
        int visible = Math.max(1, listH / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        Ui.round(ms, lx, listTop, LIST_W, listH, Theme.PANEL_2);
        int y = listTop + 2;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            JsonObject j = rows.get(i);
            String id = JsonClientModule.str(j, "id", "");
            boolean sel = id.equals(selected);
            boolean active = id.equals(current);
            boolean hover = Ui.in(mx, my, lx + 2, y, LIST_W - 4, ROW_H - 2);
            if (sel || hover) Ui.rect(ms, lx + 2, y, LIST_W - 4, ROW_H - 2, sel ? Theme.PANEL_3 : 0xFF22252B);
            if (sel) Ui.rect(ms, lx + 2, y, 2, ROW_H - 2, Theme.ACCENT);
            Ui.rect(ms, lx + 8, y + ROW_H - 1, LIST_W - 16, 1, Theme.LINE);
            Ui.text(ms, Ui.clip(JsonClientModule.str(j, "name", id), LIST_W - 60), lx + 9, y + 5, sel ? Theme.TEXT : 0xFFD4D2CC);
            Ui.text(ms, JsonClientModule.str(j, "category", ""), lx + 9, y + 16, Theme.FAINT);
            Ui.textRight(ms, "niv. " + JsonClientModule.integer(j, "level", 1), lx + LIST_W - 8, y + 5, Theme.MUTED);
            if (active) Ui.textRight(ms, "activ", lx + LIST_W - 8, y + 16, Theme.OK);
            hit(lx + 2, y, LIST_W - 4, ROW_H - 2, () -> selected = id);
            y += ROW_H;
        }
        if (rows.size() > visible) {
            int th = Math.max(10, listH * visible / rows.size());
            int ty = listTop + (listH - th) * scroll / Math.max(1, rows.size() - visible);
            Ui.rect(ms, lx + LIST_W - 3, ty, 2, th, Theme.LINE);
        }

        // detalii
        int dx = lx + LIST_W + 8;
        int dw = px + pw - 8 - dx;
        Ui.rect(ms, dx - 4, top, 1, py + ph - 8 - top, Theme.LINE);
        JsonObject j = null;
        for (JsonObject r : rows) if (selected.equals(JsonClientModule.str(r, "id", ""))) j = r;
        if (j == null) {
            Ui.text(ms, "Niciun job in aceasta categorie.", dx + 10, top + 12, Theme.FAINT);
            return;
        }
        String id = JsonClientModule.str(j, "id", "");
        boolean active = id.equals(current);
        int ix = dx + 10;
        int iy = top + 8;
        Ui.textShadow(ms, JsonClientModule.str(j, "name", id), ix, iy, Theme.TEXT);
        int tx = ix;
        iy += 14;
        tx = tag(ms, JsonClientModule.str(j, "category", "Diverse"), tx, iy, Theme.VIOLET);
        tx = tag(ms, JsonClientModule.str(j, "difficulty", "Usor"), tx, iy, difficultyColor(JsonClientModule.str(j, "difficulty", "")));
        iy += 18;
        iy = paragraph(ms, JsonClientModule.str(j, "desc", ""), ix, iy, dw - 20, Theme.MUTED);

        iy += 4;
        Ui.smallCaps(ms, "Sarcini", ix, iy, Theme.FAINT);
        iy += 11;
        if (j.has("tasks") && j.get("tasks").isJsonArray()) {
            for (JsonElement t : j.getAsJsonArray("tasks")) {
                Ui.rect(ms, ix + 1, iy + 3, 3, 3, Theme.ACCENT);
                iy = paragraph(ms, t.getAsString(), ix + 8, iy, dw - 28, Theme.TEXT);
            }
        }

        // progres + castig
        int by = py + ph - 8 - 62;
        Ui.rect(ms, dx + 6, by - 6, dw - 12, 1, Theme.LINE);
        int lvl = JsonClientModule.integer(j, "level", 1);
        long need = JsonClientModule.lng(j, "xpNeed", 0);
        float frac = need <= 0 ? 1f : (float) JsonClientModule.lng(j, "xp", 0) / need;
        Ui.text(ms, "Nivel " + lvl, ix, by, Theme.ACCENT);
        Ui.bar(ms, ix + 56, by + 2, dw - 76 - 56, 5, frac, Theme.ACCENT);
        Ui.textRight(ms, "$" + Ui.money(JsonClientModule.num(j, "earned", 0)) + " castigati", dx + dw - 10, by, Theme.OK);
        Ui.text(ms, "+" + Math.round(JsonClientModule.num(d, "bonus", 0) * 100) + "% plata pe nivel", ix, by + 13, Theme.FAINT);

        // butoane
        int bx = ix;
        int bw = (dw - 20 - 8) / 2;
        boolean hasLoc = JsonClientModule.bool(j, "hasLoc", false);
        final JsonObject jj = j;
        if (hasLoc) {
            ghost(ms, "Arata pe harta", bx, by + 28, bw, 16, mx, my, () -> MapsClient.gpsTo(
                JsonClientModule.str(jj, "locName", JsonClientModule.str(jj, "name", "Job")), "job",
                JsonClientModule.num(jj, "lx", 0), JsonClientModule.num(jj, "lz", 0)));
        } else {
            Ui.text(ms, "Fara locatie setata", bx + 4, by + 32, Theme.FAINT);
        }
        int rx = bx + bw + 8;
        if (active) {
            button(ms, cd > 0 ? "Paraseste (" + Ui.duration(cd / 1000 + 1) + ")" : "Paraseste jobul", rx, by + 28, bw, 16, mx, my, cd <= 0, 0xFF6B2F2B, () -> request("job_leave", null));
        } else {
            boolean can = current.isEmpty() || cd <= 0;
            button(ms, can ? "Alege jobul" : "Schimbi peste " + Ui.duration(cd / 1000 + 1), rx, by + 28, bw, 16, mx, my, can, Theme.ACCENT, () -> request("job_select", id));
        }
    }

    private static JsonObject findOr(List<JsonObject> rows, String id) {
        for (JsonObject r : rows) if (id.equals(JsonClientModule.str(r, "id", ""))) return r;
        return rows.get(0);
    }

    /** Text cu rupere la latime; intoarce y-ul urmator. */
    private int paragraph(GuiGraphics ms, String text, int x, int y, int w, int color) {
        if (text == null || text.isEmpty()) return y;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (Ui.width(next) > w && line.length() > 0) {
                Ui.text(ms, line.toString(), x, y, color);
                y += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            Ui.text(ms, line.toString(), x, y, color);
            y += 10;
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }
}
