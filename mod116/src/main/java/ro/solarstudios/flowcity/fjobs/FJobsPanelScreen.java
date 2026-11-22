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
