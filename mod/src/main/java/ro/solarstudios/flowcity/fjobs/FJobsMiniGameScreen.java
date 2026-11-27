package ro.solarstudios.flowcity.fjobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Mini-jocurile joburilor (wires, pick, sort, seq). Serverul genereaza problema si verifica raspunsul (timp minim, incercari, limita de timp);
 * aici doar se afiseaza si se aduna raspunsul. ESC / X renunta (serverul inchide sarcina ca esuata).
 */
public final class FJobsMiniGameScreen extends FlowScreen {
    private static final int W = 640;
    private static final int H = 360;
    private static final int VIOLET = 0xFF8B5CF6;
    private static final int[] PAL = {0xFFEF4444, 0xFF3B82F6, 0xFF22C55E, 0xFFFACC15, 0xFFA855F7, 0xFFF97316, 0xFF06B6D4, 0xFFEC4899};

    static FJobsMiniGameScreen current;

    private final JsonObject spec;
    private final String id;
    private final String type;
    private final JsonObject data;
    private final long opened = System.currentTimeMillis();
    private final double limitSec;
    private int attemptsLeft;
    private String message = "";
    private int messageColor = Sk.MUTED;
    private long closeAt;
    private boolean done;

    // stare specifica
    private int[] ans;           // wires: pentru stanga i -> dreapta j; sort: bin per element; pick: [idx]; seq: secventa introdusa
    private int selLeft = -1;
    private int seqLen;
    private int seqCount;
    private long showMs;

    private float scale = 1f;
    private float offX;
    private float offY;

    public FJobsMiniGameScreen(JsonObject spec) {
        super("Mini-joc", W, H);
        this.spec = spec;
        this.id = str(spec, "id");
        this.type = str(spec, "type");
        this.data = spec.has("data") && spec.get("data").isJsonObject() ? spec.getAsJsonObject("data") : new JsonObject();
        this.limitSec = spec.has("limitSec") ? spec.get("limitSec").getAsDouble() : 60;
        this.attemptsLeft = spec.has("attempts") ? spec.get("attempts").getAsInt() : 1;
        reset();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private void reset() {
        selLeft = -1;
        if ("wires".equals(type)) {
            ans = new int[arr(data, "left").size()];
            java.util.Arrays.fill(ans, -1);
        } else if ("sort".equals(type)) {
            ans = new int[arr(data, "items").size()];
            java.util.Arrays.fill(ans, -1);
        } else if ("pick".equals(type)) {
            ans = new int[] {-1};
        } else if ("seq".equals(type)) {
            seqLen = arr(data, "seq").size();
            ans = new int[seqLen];
            seqCount = 0;
            showMs = data.has("showMs") ? data.get("showMs").getAsLong() : 3000;
        }
    }

    /** Rezultatul de la server (mg_result). */
    void result(JsonObject r) {
        if (!id.equals(str(r, "id"))) return;
        boolean ok = r.has("ok") && r.get("ok").getAsBoolean();
        boolean fin = r.has("final") && r.get("final").getAsBoolean();
        message = str(r, "text");
        messageColor = ok ? Sk.GOOD : fin ? Sk.BAD : Sk.WARN;
        attemptsLeft = r.has("left") ? r.get("left").getAsInt() : attemptsLeft;
        if (fin) {
            done = true;
            closeAt = System.currentTimeMillis() + 900;
        } else {
            reset();
        }
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public void removed() {
        if (current == this) current = null;
        if (!done) {
            JsonObject o = new JsonObject();
            FJobsClient.request("mg_cancel", o);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    private void submit() {
        if (done) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        JsonArray a = new JsonArray();
        for (int v : ans) a.add(v);
        o.add("ans", a);
        FJobsClient.request("mg_answer", o);
        message = "Se verifică...";
        messageColor = Sk.MUTED;
    }

    private boolean complete() {
        for (int v : ans) if (v < 0) return false;
        return true;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        if (done && System.currentTimeMillis() > closeAt) {
            onClose();
            return;
        }
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
        try {
            Gfx.rrect(ms, l, t, r - l, bt - t, 0, 0xCC050712);
            float cx = 110;
            float cy = 40;
            float cw = 420;
            float ch = 280;
            Gfx.rrect(ms, cx, cy, cw, ch, 14, 0xFF141A30, 0xFF0E1226);
            Gfx.text(ms, str(spec, "title"), cx + 18, cy + 28, 13, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, str(spec, "sub"), cx + 18, cy + 42, 6.8f, Sk.MUTED, 0);
            Sk.close(ms, cx + cw - 30, cy + 8, lmx, lmy);
            hitL(cx + cw - 30, cy + 8, 20, 20, this::onClose);
            // timp si incercari
            double el = (System.currentTimeMillis() - opened) / 1000.0;
            float frac = (float) Math.max(0, 1 - el / Math.max(1, limitSec));
            Gfx.bar(ms, cx + 18, cy + 52, cw - 36, 4, frac, 0xFF1F2547, frac < 0.25f ? Sk.BAD : VIOLET);
            Gfx.text(ms, "Încercări: " + attemptsLeft, cx + cw - 18, cy + 42, 6.4f, Sk.MUTED, 2);
            float ay = cy + 70;
            float ah = ch - 70 - 40;
            switch (type) {
                case "wires":
                    wires(ms, cx + 18, ay, cw - 36, ah, lmx, lmy);
                    break;
                case "pick":
                    pick(ms, cx + 18, ay, cw - 36, ah, lmx, lmy);
                    break;
                case "sort":
                    sort(ms, cx + 18, ay, cw - 36, ah, lmx, lmy);
                    break;
                case "seq":
                    seq(ms, cx + 18, ay, cw - 36, ah, lmx, lmy, el);
                    break;
                default:
                    Gfx.text(ms, "Tip de mini-joc necunoscut.", cx + cw / 2f, ay + 40, 8, Sk.WARN, 1);
            }
            if (!message.isEmpty()) Gfx.text(ms, message, cx + 18, cy + ch - 16, 7.2f, messageColor, 0, true, 0.2f);
            boolean can = !done && !"seq".equals(type) && complete();
            if (!"seq".equals(type)) button(ms, "VERIFICĂ", cx + cw - 118, cy + ch - 32, 100, 22, lmx, lmy, can, this::submit);
        } finally {
            ms.pose().popPose();
        }
    }

    private void wires(GuiGraphics ms, float x, float y, float w, float h, float lmx, float lmy) {
        JsonArray left = arr(data, "left");
        JsonArray right = arr(data, "right");
        int n = left.size();
        float gap = Math.min(34f, h / Math.max(1, n));
        float lx = x + 40;
        float rx = x + w - 40;
        for (int i = 0; i < n; i++) {
            float ly = y + 14 + i * gap;
            if (ans[i] >= 0) {
                float ry = y + 14 + ans[i] * gap;
                int col = PAL[left.get(i).getAsInt() % PAL.length];
                Gfx.line(ms, lx, ly, rx, ry, 3.5f, col);
            }
        }
        for (int i = 0; i < n; i++) {
            final int li = i;
            float ly = y + 14 + i * gap;
            int col = PAL[left.get(i).getAsInt() % PAL.length];
            Gfx.disc(ms, lx, ly, selLeft == i ? 11f : 9f, selLeft == i ? 0xFFFFFFFF : 0xFF0B0F1E);
            Gfx.disc(ms, lx, ly, 7.5f, col);
            hitL(lx - 12, ly - 12, 24, 24, () -> {
                selLeft = li;
                ans[li] = -1;
            });
            float ry = y + 14 + i * gap;
            final int ri = i;
            int rcol = PAL[right.get(i).getAsInt() % PAL.length];
            Gfx.disc(ms, rx, ry, 9f, 0xFF0B0F1E);
            Gfx.disc(ms, rx, ry, 7.5f, rcol);
            hitL(rx - 12, ry - 12, 24, 24, () -> {
                if (selLeft < 0) return;
                for (int k = 0; k < ans.length; k++) if (ans[k] == ri) ans[k] = -1;
                ans[selLeft] = ri;
                selLeft = -1;
            });
        }
        Gfx.text(ms, "Alege un capăt din stânga, apoi capătul de aceeași culoare din dreapta.", x + w / 2f, y + h + 4, 6.2f, Sk.FAINT, 1);
    }

    private void pick(GuiGraphics ms, float x, float y, float w, float h, float lmx, float lmy) {
        wrap(ms, str(data, "prompt"), x, y + 6, w, 7.4f, Sk.WHITE, 3);
        JsonArray opts = arr(data, "options");
        float oy = y + 42;
        int cols = 2;
        float bw = (w - 8) / cols;
        for (int i = 0; i < opts.size() && i < 8; i++) {
            final int idx = i;
            float bx = x + (i % cols) * (bw + 8);
            float by = oy + (i / cols) * 26;
            boolean sel = ans[0] == i;
            boolean hot = Sk.in(lmx, lmy, bx, by, bw, 22);
            Gfx.rrect(ms, bx, by, bw, 22, 7, sel ? VIOLET : hot ? 0xFF262E55 : 0xFF1B2140);
            Gfx.text(ms, clip(opts.get(i).getAsString(), bw - 12, 6.6f), bx + bw / 2f, by + 14.5f, 6.6f, sel ? 0xFF0B0720 : Sk.WHITE, 1, true, 0.2f);
            hitL(bx, by, bw, 22, () -> ans[0] = idx);
        }
    }

    private void sort(GuiGraphics ms, float x, float y, float w, float h, float lmx, float lmy) {
        JsonArray items = arr(data, "items");
        JsonArray bins = arr(data, "bins");
        float rowH = Math.min(20f, h / Math.max(1, items.size()));
        float chipW = Math.min(78f, (w * 0.55f) / Math.max(1, bins.size()));
        float chipsX = x + w - chipW * bins.size() - 4 * (bins.size() - 1);
        for (int i = 0; i < bins.size(); i++) {
            Gfx.text(ms, clip(bins.get(i).getAsString(), chipW, 5.4f), chipsX + i * (chipW + 4) + chipW / 2f, y + 2, 5.4f, PAL[i % PAL.length], 1, true, 0.3f);
        }
        for (int i = 0; i < items.size(); i++) {
            final int ii = i;
            float ry = y + 8 + i * rowH;
            Gfx.rrect(ms, x, ry, w, rowH - 2, 5, 0xFF0F1428);
            Gfx.text(ms, clip(items.get(i).getAsString(), w - (chipW + 4) * bins.size() - 14, 6.2f), x + 8, ry + rowH / 2f + 1.5f, 6.2f, Sk.WHITE, 0);
            for (int k = 0; k < bins.size(); k++) {
                final int kk = k;
                float bx = chipsX + k * (chipW + 4);
                boolean sel = ans[i] == k;
                boolean hot = Sk.in(lmx, lmy, bx, ry, chipW, rowH - 2);
                Gfx.rrect(ms, bx, ry + 1, chipW, rowH - 4, 5, sel ? PAL[k % PAL.length] : hot ? 0xFF262E55 : 0xFF1B2140);
                hitL(bx, ry, chipW, rowH - 2, () -> ans[ii] = kk);
            }
        }
    }

    private void seq(GuiGraphics ms, float x, float y, float w, float h, float lmx, float lmy, double el) {
        boolean showing = el * 1000 < showMs;
        JsonArray s = arr(data, "seq");
        float gap = Math.min(40f, (w - 20) / Math.max(1, seqLen));
        float sx = x + (w - gap * seqLen) / 2f + gap / 2f;
        if (showing) {
            Gfx.text(ms, "MEMOREAZĂ ORDINEA", x + w / 2f, y + 6, 7, VIOLET, 1, true, 1f);
            for (int i = 0; i < seqLen; i++) symbol(ms, s.get(i).getAsInt(), sx + i * gap, y + 40, 13);
            Gfx.bar(ms, x + w / 2f - 60, y + 72, 120, 4, (float) (1 - el * 1000 / showMs), 0xFF1F2547, VIOLET);
            return;
        }
        Gfx.text(ms, "REPRODU SECVENȚA (" + seqCount + "/" + seqLen + ")", x + w / 2f, y + 6, 7, VIOLET, 1, true, 1f);
        for (int i = 0; i < seqLen; i++) {
            if (i < seqCount) symbol(ms, ans[i], sx + i * gap, y + 30, 9);
            else Gfx.disc(ms, sx + i * gap, y + 30, 3f, 0xFF2A3156);
        }
        float bx = x + w / 2f - (4 * 44) / 2f + 22;
        for (int k = 0; k < 4; k++) {
            final int kk = k;
            float cx = bx + k * 44;
            boolean hot = Sk.in(lmx, lmy, cx - 18, y + 54, 36, 36);
            Gfx.rrect(ms, cx - 18, y + 54, 36, 36, 9, hot ? 0xFF262E55 : 0xFF1B2140);
            symbol(ms, k, cx, y + 72, 10);
            hitL(cx - 18, y + 54, 36, 36, () -> {
                if (done || seqCount >= seqLen) return;
                ans[seqCount++] = kk;
                if (seqCount >= seqLen) submit();
            });
        }
        button(ms, "ȘTERGE ULTIMUL", x + w / 2f - 56, y + 100, 112, 20, lmx, lmy, seqCount > 0 && !done, () -> seqCount = Math.max(0, seqCount - 1));
    }

    private void symbol(GuiGraphics ms, int k, float cx, float cy, float r) {
        int col = PAL[(k * 2 + 1) % PAL.length];
        switch (k) {
            case 0:
                Gfx.poly(ms, new float[] {cx, cy - r, cx + r, cy + r * 0.8f, cx - r, cy + r * 0.8f}, col);
                break;
            case 1:
                Gfx.disc(ms, cx, cy, r, col);
                break;
            case 2:
                Gfx.rrect(ms, cx - r * 0.85f, cy - r * 0.85f, r * 1.7f, r * 1.7f, 2, col);
                break;
            default:
                Gfx.poly(ms, new float[] {cx, cy - r, cx + r, cy, cx, cy + r, cx - r, cy}, col);
        }
    }

    // ------------------------------------------------------------------ utilitare

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, float lmx, float lmy, boolean enabled, Runnable action) {
        boolean hot = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Gfx.rrect(ms, x, y, w, h, 7, !enabled ? 0xFF141A2C : hot ? 0xFFA78BFA : VIOLET);
        Gfx.text(ms, label, x + w / 2f, y + h / 2f + 2.4f, 6.4f, enabled ? 0xFF0B0720 : Sk.FAINT, 1, true, 0.3f);
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

    private static void wrap(GuiGraphics ms, String text, float x, float y, float w, float size, int color, int maxLines) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int n = 0;
        for (String word : words) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (Gfx.width(t, size, false) > w && line.length() > 0) {
                Gfx.text(ms, line.toString(), x, y + n * (size + 3.5f), size, color, 0);
                line = new StringBuilder(word);
                if (++n >= maxLines) return;
            } else {
                line = new StringBuilder(t);
            }
        }
        if (line.length() > 0) Gfx.text(ms, line.toString(), x, y + n * (size + 3.5f), size, color, 0);
    }
}
