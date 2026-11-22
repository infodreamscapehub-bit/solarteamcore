package ro.solarstudios.flowcity.fjobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Panoul jobului curent (Electrician, Curier, Pescar...): obiectivele active, statistici, dificultatea, butoane de actiune si tabelul de
 * recompense. TOT continutul vine de la server (FJobsClient.panel) si se actualizeaza live; clientul trimite doar intentii (panel_act, opt).
 */
public final class FJobsPanelScreen extends FlowScreen {
    private static final int W = 640;
    private static final int H = 360;
    private static final int VIOLET = 0xFF8B5CF6;
    private static final int CARD = 0xFF141A30;

    private float scale = 1f;
    private float offX;
    private float offY;
    private final long born = System.currentTimeMillis();
    private boolean gearPage;
    private float gearScroll;

    public FJobsPanelScreen() {
        super("Panou job", W, H);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public void removed() {
        FJobsClient.request("panel_close", new JsonObject());
    }

    private static String s(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static double n(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean b(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float open = Math.min(1f, (System.currentTimeMillis() - born) / 240f);
        scale = Math.max(0.5f, Math.min(2.6f, Math.min(width / (float) W, height / (float) H)));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        float l = -offX / scale;
        float t = -offY / scale;
        float r = (width - offX) / scale;
        float bt = (height - offY) / scale;
        Gfx.alpha = open;
        try {
            Gfx.rrect(ms, l, t, r - l, bt - t, 0, 0xFF0F1226, 0xFF070914);
            Gfx.scrim(ms, 200, 0, 380, 190, 0x338B5CF6);
            JsonObject p = FJobsClient.panel;
            if (p == null) {
                Gfx.text(ms, "Se încarcă panoul...", W / 2f, H / 2f, 10, Sk.MUTED, 1, true, 0.5f);
            } else {
                body(ms, p, lmx, lmy);
            }
            Sk.close(ms, W - 34, 8, lmx, lmy);
            hitL(W - 34, 8, 20, 20, this::onClose);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void body(GuiGraphics ms, JsonObject p, float lmx, float lmy) {
        if (gearPage && arr(p, "gear").size() > 0) {
            gearBody(ms, p, lmx, lmy);
            return;
        }
        Gfx.text(ms, s(p, "job"), 24, 30, 16, Sk.WHITE, 0, true, 0f);
        String status = s(p, "status");
        boolean shift = b(p, "inShift");
        Gfx.disc(ms, 27, 41, 2.5f, shift ? Sk.GOOD : Sk.WARN);
        Gfx.text(ms, (shift ? "ÎN TURĂ" : "ÎN AFARA TUREI") + (status.isEmpty() ? "" : "  ·  " + status), 34, 44, 6.6f, shift ? Sk.GOOD : Sk.WARN, 0, true, 0.4f);
        JsonObject me = FJobsClient.data != null && FJobsClient.data.has("me") && FJobsClient.data.get("me").isJsonObject() ? FJobsClient.data.getAsJsonObject("me") : new JsonObject();
        double need = n(me, "jobXpNeed");
        Gfx.text(ms, "NIVEL " + (int) n(p, "level"), W - 200, 42, 8, Sk.WHITE, 0, true, 0.3f);
        if (need > 0) {
            Gfx.text(ms, (long) n(me, "jobXp") + " / " + (long) need + " XP", W - 50, 42, 6.2f, Sk.MUTED, 2);
            Gfx.bar(ms, W - 200, 47, 150, 5, (float) (n(me, "jobXp") / need), 0xFF1F2547, VIOLET);
        } else {
            Gfx.bar(ms, W - 200, 47, 150, 5, 1f, 0xFF1F2547, VIOLET);
        }
        // coloana stanga: obiective + statistici
        Gfx.rrect(ms, 16, 62, 312, 284, 10, CARD);
        Gfx.text(ms, "OBIECTIVE", 28, 78, 6.2f, Sk.FAINT, 0, false, 1.3f);
        JsonArray obj = arr(p, "objectives");
        float y = 92;
        for (int i = 0; i < obj.size() && y < 224; i++) {
            Gfx.disc(ms, 32, y - 2.5f, 1.8f, VIOLET);
            y += wrap(ms, obj.get(i).getAsString(), 40, y, 276, 6.8f, Sk.WHITE, 3) + 3;
        }
        if (!b(p, "ready") && !s(p, "note").isEmpty()) {
            wrap(ms, s(p, "note"), 28, Math.min(y + 4, 218), 288, 6.4f, Sk.WARN, 3);
        }
        Gfx.rrect(ms, 24, 228, 296, 1, 0, 0xFF262E55);
        Gfx.text(ms, "STATISTICI", 28, 243, 6.2f, Sk.FAINT, 0, false, 1.3f);
        JsonArray st = arr(p, "stats");
        for (int i = 0; i < st.size() && i < 8; i++) {
            JsonArray kv = st.get(i).getAsJsonArray();
            float cx = 28 + (i % 2) * 150;
            float cy = 258 + (i / 2) * 22;
            Gfx.text(ms, clip(kv.get(0).getAsString().toUpperCase(java.util.Locale.ROOT), 138, 5.2f), cx, cy, 5.2f, Sk.FAINT, 0, false, 0.6f);
            Gfx.text(ms, clip(kv.get(1).getAsString(), 138, 7.6f), cx, cy + 10, 7.6f, Sk.WHITE, 0, true, 0f);
        }
        // coloana dreapta: dificultate, actiuni, recompense
        float rx = 338;
        float rw = 286;
        float ry = 62;
        JsonArray ds = arr(p, "diffs");
        if (ds.size() > 0) {
            Gfx.rrect(ms, rx, ry, rw, 52, 10, CARD);
            Gfx.text(ms, "DIFICULTATE", rx + 12, ry + 15, 6.2f, Sk.FAINT, 0, false, 1.3f);
            float cw = (rw - 24 - (ds.size() - 1) * 6) / ds.size();
            for (int i = 0; i < ds.size(); i++) {
                JsonObject d = ds.get(i).getAsJsonObject();
                final String id = s(d, "id");
                boolean locked = b(d, "locked");
                boolean sel = b(d, "sel");
                String label = s(d, "name") + (locked ? "  (niv " + (int) n(d, "minLevel") + ")" : "  x" + String.format(java.util.Locale.ROOT, "%.1f", n(d, "pay")));
                chip(ms, label, rx + 12 + i * (cw + 6), ry + 24, cw, 20, lmx, lmy, !locked, sel, () -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("key", "difficulty");
                    o.addProperty("value", id);
                    FJobsClient.request("opt", o);
                });
            }
            ry += 60;
        }
        JsonArray bs = arr(p, "buttons");
        if (bs.size() > 0) {
            int rows = (bs.size() + 1) / 2;
            float bh = 24 + rows * 26;
            Gfx.rrect(ms, rx, ry, rw, bh, 10, CARD);
            Gfx.text(ms, "ACȚIUNI", rx + 12, ry + 15, 6.2f, Sk.FAINT, 0, false, 1.3f);
            float bw = (rw - 30) / 2f;
            for (int i = 0; i < bs.size(); i++) {
                JsonObject bo = bs.get(i).getAsJsonObject();
                final String act = s(bo, "act");
                boolean on = b(bo, "on");
                float bx = rx + 12 + (i % 2) * (bw + 6);
                float by = ry + 22 + (i / 2) * 26;
                button(ms, s(bo, "label"), bx, by, bw, 22, lmx, lmy, on, i == 0, () -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("act", act);
                    FJobsClient.request("panel_act", o);
                });
            }
            ry += bh + 8;
        }
        JsonArray rw2 = arr(p, "rewards");
        float rh = Math.max(40, H - 14 - ry);
        Gfx.rrect(ms, rx, ry, rw, rh, 10, CARD);
        Gfx.text(ms, "RECOMPENSE", rx + 12, ry + 15, 6.2f, Sk.FAINT, 0, false, 1.3f);
        float yy = ry + 28;
        int maxRows = (int) ((rh - 30) / 9.5f);
        JsonArray tips = arr(p, "tips");
        int tipRows = Math.min(2, tips.size());
        int rewardRows = Math.max(1, maxRows - tipRows);
        for (int i = 0; i < rw2.size() && i < rewardRows; i++) {
            Gfx.text(ms, clip(rw2.get(i).getAsString(), rw - 24, 6.2f), rx + 12, yy + i * 9.5f, 6.2f, Sk.WHITE, 0);
        }
        float ty = yy + Math.min(rw2.size(), rewardRows) * 9.5f + 4;
        for (int i = 0; i < tipRows; i++) {
            JsonElement te = tips.get(i);
            Gfx.text(ms, clip("· " + te.getAsString(), rw - 24, 5.8f), rx + 12, ty + i * 9f, 5.8f, Sk.MUTED, 0);
        }
        button(ms, "JOB CENTER", W - 160, 8, 118, 18, lmx, lmy, true, false, FJobsClient::open);
        if (arr(p, "gear").size() > 0) button(ms, "ECHIPAMENT", W - 290, 8, 120, 18, lmx, lmy, true, true, () -> {
            gearPage = true;
            gearScroll = 0;
        });
    }

    private static final float ROW_H = 44f;

    private void gearAct(String act, String id, double value) {
        JsonObject o = new JsonObject();
        o.addProperty("act", act);
        o.addProperty("id", id);
        o.addProperty("qty", 1);
        o.addProperty("value", value);
        FJobsClient.request("job_op", o);
    }

    /** Pagina ECHIPAMENT: lista derulabila de unelte si consumabile (cumparare cu bani; admin: pret si activare). Totul e validat pe server. */
    private void gearBody(GuiGraphics ms, JsonObject p, float lmx, float lmy) {
        Gfx.text(ms, "ECHIPAMENT  ·  " + s(p, "job"), 24, 30, 14, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "Itemele sunt reale (in inventar), legate de job; efectele se aplica pe server.", 24, 44, 6.2f, Sk.MUTED, 0);
        button(ms, "INAPOI", W - 160, 8, 118, 18, lmx, lmy, true, false, () -> gearPage = false);
        JsonArray g = arr(p, "gear");
        boolean admin = b(p, "admin");
        float top = 62;
        float bottom = H - 14;
        gearScroll = Math.max(0, Math.min(Math.max(0, g.size() * ROW_H - (bottom - top)), gearScroll));
        Gfx.rrect(ms, 16, top - 4, W - 32, bottom - top + 8, 10, CARD);
        for (int i = 0; i < g.size(); i++) {
            float y = top + i * ROW_H - gearScroll;
            if (y < top - 2 || y + ROW_H - 4 > bottom + 2) continue;
            JsonObject it = g.get(i).getAsJsonObject();
            final String id = s(it, "id");
            boolean locked = b(it, "locked");
            boolean off = !b(it, "enabled");
            int owned = (int) n(it, "owned");
            int max = (int) n(it, "max");
            Gfx.rrect(ms, 24, y, W - 48, ROW_H - 6, 8, 0xFF1B2140);
            // pictograma vectoriala (fara texturi): mic cub cu stralucire
            int ic = b(it, "required") ? Sk.WARN : b(it, "consumable") ? Sk.GOOD : VIOLET;
            Gfx.rrect(ms, 32, y + 6, 26, 26, 6, 0xFF10152B);
            Gfx.rrect(ms, 38, y + 12, 14, 14, 4, ic);
            Gfx.line(ms, 40, y + 24, 50, y + 14, 1.4f, 0xFF0B0720);
            StringBuilder tag = new StringBuilder();
            if (b(it, "required")) tag.append("OBLIGATORIU  ");
            if (b(it, "consumable")) tag.append("CONSUMABIL  ");
            if (n(it, "uses") > 0) tag.append((int) n(it, "uses")).append(" utilizari  ");
            if (b(it, "soulbound")) tag.append("LEGAT  ");
            if (n(it, "bonusPay") > 0) tag.append("+").append(Math.round(n(it, "bonusPay") * 100)).append("% plata  ");
            if (n(it, "bonusXp") > 0) tag.append("+").append(Math.round(n(it, "bonusXp") * 100)).append("% XP");
            Gfx.text(ms, clip(s(it, "name") + (off ? "  (dezactivat)" : ""), 220, 8f), 68, y + 15, 8f, off ? Sk.FAINT : Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, clip(s(it, "desc"), 250, 5.8f), 68, y + 25, 5.8f, Sk.MUTED, 0);
            Gfx.text(ms, clip(tag.toString().trim(), admin ? 250 : 300, 5.2f), 68, y + 34, 5.2f, Sk.FAINT, 0, false, 0.5f);
            float bx = W - 48 - 96;
            String price = n(it, "price") <= 0 ? "GRATIS" : (long) n(it, "price") + "$" + (n(it, "pack") > 1 ? " /" + (int) n(it, "pack") : "");
            Gfx.text(ms, "ai " + owned + "/" + max, bx - 10, y + 15, 6.2f, owned > 0 ? Sk.GOOD : Sk.MUTED, 2);
            boolean can = !locked && !off && owned < max;
            String lab = locked ? "NIV " + (int) n(it, "minLevel") : owned >= max ? "AI DEJA" : "CUMPARA " + price;
            button(ms, lab, bx, y + 8, 96, 20, lmx, lmy, can, can, () -> gearAct("gear_buy", id, 0));
            if (admin) {
                final double pr = n(it, "price");
                button(ms, "-10", bx - 120, y + 22, 28, 14, lmx, lmy, true, false, () -> gearAct("gear_admin_price", id, Math.max(0, pr - 10)));
                button(ms, "+10", bx - 88, y + 22, 28, 14, lmx, lmy, true, false, () -> gearAct("gear_admin_price", id, pr + 10));
                button(ms, off ? "ACTIV" : "OPRIT", bx - 56, y + 22, 44, 14, lmx, lmy, true, false, () -> gearAct("gear_admin_toggle", id, 0));
                button(ms, b(it, "required") ? "OBL:DA" : "OBL:NU", bx - 166, y + 22, 42, 14, lmx, lmy, true, false, () -> gearAct("gear_admin_required", id, 0));
            }
        }
        if (g.size() * ROW_H > bottom - top) {
            float frac = gearScroll / Math.max(1f, g.size() * ROW_H - (bottom - top));
            Gfx.rrect(ms, W - 22, top, 3, bottom - top, 1.5f, 0xFF262E55);
            Gfx.rrect(ms, W - 22, top + frac * (bottom - top - 30), 3, 30, 1.5f, VIOLET);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (gearPage) gearScroll -= (float) delta * 30f;
        return true;
    }

    private void chip(GuiGraphics ms, String label, float x, float y, float w, float h, float lmx, float lmy, boolean enabled, boolean sel, Runnable action) {
        boolean hot = enabled && Sk.in(lmx, lmy, x, y, w, h);
        int bg = !enabled ? 0xFF141A2C : sel ? VIOLET : hot ? 0xFF262E55 : 0xFF1B2140;
        int fg = !enabled ? Sk.FAINT : sel ? 0xFF0B0720 : hot ? Sk.WHITE : 0xFFC9CBE0;
        Gfx.rrect(ms, x, y, w, h, 7, bg);
        Gfx.text(ms, clip(label, w - 6, 6.2f), x + w / 2f, y + h / 2f + 2.4f, 6.2f, fg, 1, true, 0.3f);
        if (enabled && !sel) hitL(x, y, w, h, action);
    }

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, float lmx, float lmy, boolean enabled, boolean filled, Runnable action) {
        if (w <= 0) return;
        boolean hot = enabled && Sk.in(lmx, lmy, x, y, w, h);
        int bg = !enabled ? 0xFF141A2C : filled ? (hot ? 0xFFA78BFA : VIOLET) : (hot ? 0xFF262E55 : 0xFF1B2140);
        int fg = !enabled ? Sk.FAINT : filled ? 0xFF0B0720 : (hot ? Sk.WHITE : 0xFFC9CBE0);
        Gfx.rrect(ms, x, y, w, h, 7, bg);
        Gfx.text(ms, clip(label, w - 8, 6.2f), x + w / 2f, y + h / 2f + 2.4f, 6.2f, fg, 1, true, 0.3f);
        if (enabled) hitL(x, y, w, h, action);
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String clip(String s, float max, float size) {
        if (Gfx.width(s, size, false) <= max) return s;
        String r = s;
        while (r.length() > 1 && Gfx.width(r + "…", size, false) > max) r = r.substring(0, r.length() - 1);
        return r.trim() + "…";
    }

    /** Scrie text pe randuri; intoarce inaltimea folosita. */
    private static float wrap(GuiGraphics ms, String text, float x, float y, float w, float size, int color, int maxLines) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int n = 0;
        for (String word : words) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (Gfx.width(t, size, false) > w && line.length() > 0) {
                Gfx.text(ms, line.toString(), x, y + n * (size + 3.5f), size, color, 0);
                line = new StringBuilder(word);
                if (++n >= maxLines) return n * (size + 3.5f);
            } else {
                line = new StringBuilder(t);
            }
        }
        if (line.length() > 0) {
            Gfx.text(ms, line.toString(), x, y + n * (size + 3.5f), size, color, 0);
            n++;
        }
        return n * (size + 3.5f);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
