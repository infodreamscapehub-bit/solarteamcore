package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.SoundEvents;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.fjobs.FJobsClient;

/**
 * Baza ecranelor jobului Electrician: spatiu de design 640x360 scalat pe ecran, stil industrial modern (fundal inchis cu grila,
 * benzi galbene/negre de avertizare, accente galben/portocaliu, indicatoare). Toata grafica e vectoriala (fara PNG).
 */
public abstract class ElecScreen extends FlowScreen {
    public static final int W = 640;
    public static final int H = 360;

    public static final int BG = 0xFF090B0E;
    public static final int PANEL = 0xFF13171D;
    public static final int PANEL2 = 0xFF1A2028;
    public static final int LINE = 0xFF2B343F;
    public static final int YEL = 0xFFFFC21A;
    public static final int ORG = 0xFFFF7A18;
    public static final int TXT = 0xFFEEF2F6;
    public static final int MUT = 0xFF8D99A6;
    public static final int FAINT = 0xFF566170;
    public static final int GOOD = 0xFF3DDC84;
    public static final int BAD = 0xFFFF4D3D;
    public static final int BLUE = 0xFF39B6FF;
    public static final int[] WIRE = {0xFFEF4444, 0xFF3B82F6, 0xFF22C55E, 0xFFFACC15, 0xFFA855F7, 0xFFF97316};

    protected float scale = 1f;
    protected float offX;
    protected float offY;
    protected float lmx;
    protected float lmy;
    protected long born = System.currentTimeMillis();

    protected ElecScreen(String title) {
        super(title, W, H);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Continutul, in coordonate de design (0..640 x 0..360). */
    protected abstract void content(GuiGraphics ms, float t);

    protected boolean onPress(float x, float y, int button) {
        return false;
    }

    protected void onDrag(float x, float y) {}

    protected void onRelease(float x, float y) {}

    protected void onScroll(double delta) {}

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        scale = Math.max(0.5f, Math.min(2.8f, Math.min(width / (float) W, height / (float) H)));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        lmx = (mx - offX) / scale;
        lmy = (my - offY) / scale;
        float t = (System.currentTimeMillis() - born) / 1000f;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        try {
            float l = -offX / scale;
            float tp = -offY / scale;
            float r = (width - offX) / scale;
            float b = (height - offY) / scale;
            background(ms, l, tp, r, b, t);
            content(ms, t);
        } finally {
            ms.pose().popPose();
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (onPress((float) ((mx - offX) / scale), (float) ((my - offY) / scale), button)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        onDrag((float) ((mx - offX) / scale), (float) ((my - offY) / scale));
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        onRelease((float) ((mx - offX) / scale), (float) ((my - offY) / scale));
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        onScroll(delta);
        return true;
    }

    protected void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    protected boolean over(float x, float y, float w, float h) {
        return lmx >= x && lmx < x + w && lmy >= y && lmy < y + h;
    }

    @Override
    public void onClose() {
        ElecClient.closeAll();
    }

    // ------------------------------------------------------------------ cereri catre server

    /** Operatie a jobului (serverul valideaza tot). */
    public static void op(String act, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("act", act);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        FJobsClient.requestOp(o);
    }

    public static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    public static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    public static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    public static double num(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static boolean bool(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ sunete (doar cele sigure in 1.16.5)

    public static void snd(SoundEvent e, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSound.forUI(e, pitch));
    }

    public static void sndOk() {
        snd(SoundEvents.NOTE_BLOCK_PLING, 1.6f);
    }

    public static void sndBad() {
        snd(SoundEvents.NOTE_BLOCK_BASS, 0.6f);
    }

    public static void sndTick(float pitch) {
        snd(SoundEvents.NOTE_BLOCK_HAT, pitch);
    }

    public static void sndBeep(float pitch) {
        snd(SoundEvents.NOTE_BLOCK_BIT, pitch);
    }

    public static void sndWin() {
        snd(SoundEvents.PLAYER_LEVELUP, 1.2f);
    }

    // ------------------------------------------------------------------ desen: fundal si cadre

    /** Fundal industrial: inchis, grila fina, benzi de avertizare sus/jos, puls de curent pe marginea de jos. */
    protected void background(GuiGraphics ms, float l, float t, float r, float b, float time) {
        Gfx.rrect(ms, l, t, r - l, b - t, 0, 0xFF0D1016, 0xFF07090C);
        for (float x = (float) (Math.floor(l / 24) * 24); x < r; x += 24) Gfx.line(ms, x, t, x, b, 0.5f, 0x14FFC21A);
        for (float y = (float) (Math.floor(t / 24) * 24); y < b; y += 24) Gfx.line(ms, l, y, r, y, 0.5f, 0x14FFC21A);
        Gfx.scrim(ms, (l + r) / 2f, (t + b) / 2f, (r - l) * 0.62f, (b - t) * 0.6f, 0x22FF7A18);
        hazard(ms, l, t, r - l, 5);
        hazard(ms, l, b - 5, r - l, 5);
        float pulse = (time * 140f) % (r - l + 120f);
        Gfx.rrect(ms, l + pulse - 120f, b - 7, 120, 2, 0, 0x00FFC21A, 0xCCFFC21A);
    }

    /** Banda galben/negru de avertizare. */
    protected void hazard(GuiGraphics ms, float x, float y, float w, float h) {
        Gfx.rrect(ms, x, y, w, h, 0, 0xFF14110A);
        float sw = h * 1.1f;
        for (float sx = x + h; sx + sw <= x + w; sx += sw * 2) {
            Gfx.poly(ms, new float[] {sx, y, sx + sw, y, sx + sw - h, y + h, sx - h, y + h}, YEL);
        }
    }

    /** Cadru de panou cu colturi accentuate si titlu. */
    protected void box(GuiGraphics ms, float x, float y, float w, float h, String title) {
        Gfx.rrect(ms, x, y, w, h, 5, LINE);
        Gfx.rrect(ms, x + 1, y + 1, w - 2, h - 2, 4, PANEL, 0xFF0F1318);
        float c = 9;
        Gfx.line(ms, x + 1, y + 1, x + 1 + c, y + 1, 1.6f, YEL);
        Gfx.line(ms, x + 1, y + 1, x + 1, y + 1 + c, 1.6f, YEL);
        Gfx.line(ms, x + w - 1, y + h - 1, x + w - 1 - c, y + h - 1, 1.6f, YEL);
        Gfx.line(ms, x + w - 1, y + h - 1, x + w - 1, y + h - 1 - c, 1.6f, YEL);
        if (title != null && !title.isEmpty()) Gfx.text(ms, title, x + 10, y + 13, 6.4f, YEL, 0, true, 0.8f);
    }

    protected void head(GuiGraphics ms, String title, String sub, boolean closeBtn) {
        Gfx.rrect(ms, 0, 5, W, 30, 0, 0xFF11151B, 0xFF0C0F14);
        Gfx.line(ms, 0, 35, W, 35, 1f, LINE);
        bolt(ms, 24, 20, 9, YEL);
        Gfx.text(ms, title, 40, 19, 11, TXT, 0, true, 1.2f);
        Gfx.text(ms, sub, 40, 30, 5.8f, MUT, 0);
        if (closeBtn) {
            boolean h = over(W - 34, 10, 22, 22);
            Gfx.rrect(ms, W - 34, 10, 22, 22, 4, h ? BAD : PANEL2);
            Gfx.line(ms, W - 28, 16, W - 18, 26, 1.8f, h ? 0xFFFFFFFF : MUT);
            Gfx.line(ms, W - 18, 16, W - 28, 26, 1.8f, h ? 0xFFFFFFFF : MUT);
            hitL(W - 34, 10, 22, 22, this::onClose);
        }
    }

    // ------------------------------------------------------------------ desen: componente

    /** Fulger (doua triunghiuri). */
    public static void bolt(GuiGraphics ms, float cx, float cy, float s, int color) {
        Gfx.poly(ms, new float[] {cx + 0.3f * s, cy - s, cx - 0.4f * s, cy + 0.15f * s, cx + 0.12f * s, cy + 0.15f * s}, color);
        Gfx.poly(ms, new float[] {cx - 0.3f * s, cy + s, cx + 0.4f * s, cy - 0.15f * s, cx - 0.12f * s, cy - 0.15f * s}, color);
    }

    /** Buton industrial; primary = galben. */
    protected void btn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean primary, Runnable action) {
        btnC(ms, label, x, y, w, h, enabled, primary ? YEL : PANEL2, primary ? 0xFF1A1405 : TXT, action);
    }

    protected void btnC(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, int bg, int fg, Runnable action) {
        boolean hot = enabled && over(x, y, w, h);
        int c = !enabled ? 0xFF161B22 : hot ? Gfx.mixArgb(bg, 0xFFFFFFFF, 0.18f) : bg;
        Gfx.rrect(ms, x, y + 1.5f, w, h, 4, 0xFF05070A);
        Gfx.rrect(ms, x, y, w, h, 4, c);
        if (enabled) Gfx.rrect(ms, x + 2, y + 1, w - 4, 1.5f, 1, 0x30FFFFFF);
        Gfx.text(ms, clip(label, w - 8, 6.6f), x + w / 2f, y + h / 2f + 2.4f, 6.6f, enabled ? fg : FAINT, 1, true, 0.5f);
        if (enabled) hitL(x, y, w, h, action);
    }

    /** Indicatori de greseli: n cercuri, primele "used" sunt rosii. */
    protected void pips(GuiGraphics ms, float x, float y, int n, int used) {
        for (int i = 0; i < n; i++) {
            boolean u = i < used;
            Gfx.disc(ms, x + i * 11, y, 4.2f, 0xFF05070A);
            Gfx.disc(ms, x + i * 11, y, 3.2f, u ? BAD : 0xFF2C343F);
            if (!u) Gfx.disc(ms, x + i * 11, y, 1.6f, GOOD);
        }
    }

    /** Dificultate: 5 fulgere, primele d pline. */
    protected void diffMeter(GuiGraphics ms, float x, float y, int d, float s) {
        for (int i = 0; i < 5; i++) bolt(ms, x + i * s * 1.3f, y, s * 0.5f, i < d ? (d >= 4 ? ORG : YEL) : 0xFF2C343F);
    }

    protected void stars(GuiGraphics ms, float cx, float cy, int n, float r) {
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * r * 2.6f;
            float[] p = new float[20];
            for (int k = 0; k < 10; k++) {
                double a = -Math.PI / 2 + k * Math.PI / 5;
                float rr = k % 2 == 0 ? r : r * 0.42f;
                p[k * 2] = x + (float) Math.cos(a) * rr;
                p[k * 2 + 1] = cy + (float) Math.sin(a) * rr;
            }
            Gfx.poly(ms, p, i < n ? YEL : 0xFF2C343F);
        }
    }

    protected static String clip(String s, float max, float size) {
        if (s == null) return "";
        if (Gfx.width(s, size, true) <= max) return s;
        String r = s;
        while (r.length() > 1 && Gfx.width(r + "…", size, true) > max) r = r.substring(0, r.length() - 1);
        return r.trim() + "…";
    }

    protected static String clipN(String s, float max, float size) {
        if (s == null) return "";
        if (Gfx.width(s, size, false) <= max) return s;
        String r = s;
        while (r.length() > 1 && Gfx.width(r + "…", size, false) > max) r = r.substring(0, r.length() - 1);
        return r.trim() + "…";
    }

    protected static void wrap(GuiGraphics ms, String text, float x, float y, float w, float size, int color, int maxLines) {
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

    protected static String mmss(double sec) {
        long s = Math.max(0, Math.round(sec));
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    protected static String volts(double v) {
        return v >= 1000 ? (Math.round(v / 100.0) / 10.0) + " kV" : Math.round(v) + " V";
    }

    protected static float ease(float a, float b, float k) {
        return a + (b - a) * Math.max(0f, Math.min(1f, k));
    }
}
