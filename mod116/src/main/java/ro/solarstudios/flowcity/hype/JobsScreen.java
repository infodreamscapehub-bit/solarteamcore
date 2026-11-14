package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Centrul de joburi: lista (filtrata pe categorie) in stanga, detaliile jobului ales in dreapta. Tema de iarna FlowCity. */
public final class JobsScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int LIST_W = 196;
    private static final int ROW_H = 34;

    private String category = "";
    private String selected = "";
    private int scroll;
    private final long born = System.currentTimeMillis();
    private float offX;
    private float offY;
    private float scale = 1f;

    public JobsScreen() {
        super("Joburi", W, H);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    private void request(String action, String id) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        HypeClient.request(action, o);
    }

    private static int difficultyColor(String d) {
        String s = d == null ? "" : d.toLowerCase();
        if (s.startsWith("gre") || s.startsWith("hard")) return Sk.BAD;
        if (s.startsWith("med")) return Sk.WARN;
        return Sk.GOOD;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static String money(double v) {
        long n = Math.round(v);
        String s = String.valueOf(Math.abs(n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + b;
    }

    private static String duration(long sec) {
        long m = sec / 60;
        long s = sec % 60;
        return m > 0 ? m + "m " + s + "s" : s + "s";
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

    private void content(GuiGraphics ms, float lmx, float lmy) {
        JsonObject d = HypeState.jobs;
        String current = JsonClientModule.str(d, "current", "");
        long cd = JsonClientModule.lng(d, "cooldownMs", 0) - (System.currentTimeMillis() - HypeState.jobsAt);

        Gfx.text(ms, "JOBURI", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "CENTRUL DE JOBURI FLOWCITY", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        if (!d.has("jobs") || !d.get("jobs").isJsonArray()) {
            Gfx.text(ms, "Se încarcă...", W / 2f, H / 2f, 9, Sk.MUTED, 1);
            return;
        }
        JsonArray all = d.getAsJsonArray("jobs");
        Set<String> cats = new LinkedHashSet<>();
        for (JsonElement el : all) if (el.isJsonObject()) cats.add(JsonClientModule.str(el.getAsJsonObject(), "category", "Diverse"));

        float top = 64;
        float lx = 12;

        // filtre pe categorie
        float cx = lx;
        float tw = Gfx.width("Toate", 6.6f, false) + 16;
        boolean all0 = category.isEmpty();
        Sk.slant(ms, cx, top, tw, 16, 4f, all0 ? Sk.ACC : Sk.ROW);
        Gfx.text(ms, "Toate", cx + tw / 2f, top + 5, 6.6f, all0 ? 0xFF061426 : Sk.MUTED, 1);
        hitL(cx, top, tw, 16, () -> {
            category = "";
            scroll = 0;
        });
        cx += tw + 4;
        for (String c : cats) {
            float w = Gfx.width(c, 6.6f, false) + 16;
            if (cx + w > lx + LIST_W) break;
            final String cat = c;
            boolean on = c.equals(category);
            Sk.slant(ms, cx, top, w, 16, 4f, on ? Sk.ACC : Sk.ROW);
            Gfx.text(ms, c, cx + w / 2f, top + 5, 6.6f, on ? 0xFF061426 : Sk.MUTED, 1);
            hitL(cx, top, w, 16, () -> {
                category = cat;
                scroll = 0;
            });
            cx += w + 4;
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
        float listTop = top + 22;
        float listH = H - 12 - listTop;
        int visible = Math.max(1, (int) (listH / ROW_H));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));
        float y = listTop;
        for (int i = scroll; i < Math.min(rows.size(), scroll + visible); i++) {
            JsonObject j = rows.get(i);
            String id = JsonClientModule.str(j, "id", "");
            boolean sel = id.equals(selected);
            boolean active = id.equals(current);
            boolean hover = Sk.in(lmx, lmy, lx, y, LIST_W, ROW_H - 4);
            Sk.slant(ms, lx, y, LIST_W - 8, ROW_H - 4, 6f, sel ? Sk.ACC_DARK : hover ? Sk.ROW_HOT : Sk.ROW);
            if (sel) Gfx.rrect(ms, lx + 8, y + 6, 3, ROW_H - 16, 0, Sk.ACC);
            Gfx.text(ms, clip(JsonClientModule.str(j, "name", id), 8.6f, true, LIST_W - 100), lx + 18, y + 8, 8.6f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, JsonClientModule.str(j, "category", ""), lx + 18, y + 20, 6.2f, sel ? Sk.WHITE : Sk.MUTED, 0);
            Gfx.text(ms, "niv. " + JsonClientModule.integer(j, "level", 1), lx + LIST_W - 20, y + 9, 6.6f, Sk.MUTED, 2);
            if (active) Gfx.text(ms, "ACTIV", lx + LIST_W - 20, y + 20, 6.2f, Sk.GOOD, 2, false, 1f);
            hitL(lx, y, LIST_W - 8, ROW_H - 4, () -> selected = id);
            y += ROW_H;
        }
        if (rows.size() > visible) {
            float th = Math.max(14f, listH * visible / rows.size());
            float ty = listTop + (listH - th) * scroll / Math.max(1, rows.size() - visible);
            Gfx.rrect(ms, lx + LIST_W - 4, listTop, 2, listH, 0, 0xFF14202F);
            Gfx.rrect(ms, lx + LIST_W - 4, ty, 2, th, 0, Sk.ACC);
        }

        // detalii
        float dx = lx + LIST_W + 8;
        float dw = W - 12 - dx;
        Sk.slant(ms, dx, top, dw, H - 12 - top, 8f, Sk.PANEL);
        JsonObject j = null;
        for (JsonObject r : rows) if (selected.equals(JsonClientModule.str(r, "id", ""))) j = r;
        if (j == null) {
            Gfx.text(ms, "Niciun job în această categorie.", dx + dw / 2f, top + 40, 8f, Sk.FAINT, 1);
            return;
        }
        String id = JsonClientModule.str(j, "id", "");
        boolean active = id.equals(current);
        float ix = dx + 16;
        float iy = top + 24;
        Gfx.text(ms, clip(JsonClientModule.str(j, "name", id), 13f, true, dw - 32), ix, iy, 13f, Sk.WHITE, 0, true, 0f);
        iy += 10;
        float tx = ix;
        tx = tag(ms, JsonClientModule.str(j, "category", "Diverse"), tx, iy, Sk.ACC_DARK, Sk.WHITE);
        tag(ms, JsonClientModule.str(j, "difficulty", "Usor"), tx, iy, difficultyColor(JsonClientModule.str(j, "difficulty", "")), 0xFF061426);
        iy += 28;
        iy = paragraph(ms, JsonClientModule.str(j, "desc", ""), ix, iy, dw - 32, Sk.MUTED);

        iy += 4;
        Gfx.text(ms, "SARCINI", ix, iy, 6.2f, Sk.FAINT, 0, false, 1.2f);
        iy += 10;
        if (j.has("tasks") && j.get("tasks").isJsonArray()) {
            for (JsonElement t : j.getAsJsonArray("tasks")) {
                Gfx.rrect(ms, ix + 1, iy - 1, 3, 3, 0, Sk.ACC);
                iy = paragraph(ms, t.getAsString(), ix + 9, iy - 4, dw - 44, Sk.WHITE);
            }
        }

        // progres + castig
        float by = H - 12 - 74;
        Gfx.rrect(ms, dx + 12, by - 8, dw - 24, 1, 0, 0xFF22344D);
        int lvl = JsonClientModule.integer(j, "level", 1);
        long need = JsonClientModule.lng(j, "xpNeed", 0);
        float frac = need <= 0 ? 1f : Math.min(1f, (float) JsonClientModule.lng(j, "xp", 0) / need);
        Gfx.text(ms, "NIVEL " + lvl, ix, by + 2, 8f, Sk.ACC, 0, true, 0f);
        Sk.segs(ms, ix + 54, by, dw - 32 - 54 - 100, 8, Math.round(frac * 20), 20, Sk.ACC);
        Gfx.text(ms, money(JsonClientModule.num(j, "earned", 0)) + " lei câștigați", dx + dw - 16, by + 2, 7f, Sk.GOOD, 2);
        Gfx.text(ms, "+" + Math.round(JsonClientModule.num(d, "bonus", 0) * 100) + "% plată pe nivel", ix, by + 16, 6.4f, Sk.FAINT, 0);

        // butoane
        boolean hasLoc = JsonClientModule.bool(j, "hasLoc", false);
        float bw = (dw - 32 - 8) / 2f;
        float bty = H - 12 - 36;
        if (hasLoc) Gfx.text(ms, clip("Locație: " + JsonClientModule.str(j, "locName", "pe hartă"), 6.8f, false, bw), ix, bty + 11, 6.8f, Sk.MUTED, 0);
        else Gfx.text(ms, "Fără locație setată", ix, bty + 11, 6.8f, Sk.FAINT, 0);
        float rx = ix + bw + 8;
        boolean hov = Sk.in(lmx, lmy, rx, bty, bw, 26);
        if (active) {
            boolean can = cd <= 0;
            Sk.button(ms, can ? "PĂRĂSEȘTE JOBUL" : "PĂRĂSEȘTE (" + duration(cd / 1000 + 1) + ")", rx, bty, bw, 26, can, false, hov);
            if (can) hitL(rx, bty, bw, 26, () -> request("job_leave", null));
        } else {
            boolean can = current.isEmpty() || cd <= 0;
            Sk.button(ms, can ? "ALEGE JOBUL" : "SCHIMBI PESTE " + duration(cd / 1000 + 1), rx, bty, bw, 26, can, true, hov);
            if (can) hitL(rx, bty, bw, 26, () -> request("job_select", id));
        }
    }

    private float tag(GuiGraphics ms, String label, float x, float y, int bg, int fg) {
        float w = Gfx.width(label, 6.4f, false) + 14;
        Sk.slant(ms, x, y, w, 13, 4f, bg);
        Gfx.text(ms, label, x + w / 2f, y + 4, 6.4f, fg, 1);
        return x + w + 5;
    }

    private static JsonObject findOr(List<JsonObject> rows, String id) {
        for (JsonObject r : rows) if (id.equals(JsonClientModule.str(r, "id", ""))) return r;
        return rows.get(0);
    }

    /** Text cu rupere la latime; intoarce y-ul urmator. */
    private float paragraph(GuiGraphics ms, String text, float x, float y, float w, int color) {
        if (text == null || text.isEmpty()) return y;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (Gfx.width(next, 7.4f, false) > w && line.length() > 0) {
                Gfx.text(ms, line.toString(), x, y, 7.4f, color, 0);
                y += 10;
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            Gfx.text(ms, line.toString(), x, y, 7.4f, color, 0);
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

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
