package ro.solarstudios.flowcity.client.ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Baza pentru ecranele FlowCity in designul nou (FTheme/FUi): ajutoare de desen (text, pastile, carduri, butoane,
 * fila, fereastra de confirmare) care inregistreaza si zonele de click, ca fiecare ecran sa nu le rescrie.
 * Zonele se inregistreaza in ordinea desenului; ultima inregistrata are prioritate (fereastra de confirmare se
 * deseneaza ultima, deci acopera restul).
 */
public abstract class FScreen extends FlowScreen {
    private final int baseW;
    private final int baseH;
    protected float sc = 1f;
    protected float ox;
    protected float oy;

    protected FScreen(String title, int w, int h) {
        super(title, w, h);
        baseW = w;
        baseH = h;
    }

    @Override
    protected boolean framed() {
        return false;
    }

    /** Deseneaza continutul in coordonate logice (0..W, 0..H), scalat sa incapa pe ecran. */
    protected abstract void paint(GuiGraphics g, int mx, int my);

    @Override
    protected final void draw(GuiGraphics g, int mx, int my) {
        FUi.frame(0.016f);
        Gfx.rrect(g, 0, 0, width, height, 0, FTheme.SCRIM);
        sc = Math.min(1f, Math.min((width - 8f) / baseW, (height - 8f) / baseH));
        ox = (width - baseW * sc) / 2f;
        oy = (height - baseH * sc) / 2f;
        px = 0;
        py = 0;
        pw = baseW;
        ph = baseH;
        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.pose().scale(sc, sc, 1f);
        try {
            paint(g, Math.round((mx - ox) / sc), Math.round((my - oy) / sc));
        } finally {
            g.pose().popPose();
        }
    }

    /** Zonele de click se dau in coordonate logice; aici se transforma in coordonate de ecran. */
    @Override
    protected void hit(int x, int y, int w, int h, Runnable action) {
        super.hit(Math.round(ox + x * sc), Math.round(oy + y * sc), Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), action);
    }

    // ------------------------------------------------------------------ camp de text simplu (fara widget vanilla)

    private final Map<String, StringBuilder> buf = new HashMap<>();
    private String focusId;

    protected String text(String id) {
        StringBuilder b = buf.get(id);
        return b == null ? "" : b.toString();
    }

    protected void setText(String id, String v) {
        buf.put(id, new StringBuilder(v == null ? "" : v));
    }

    protected boolean focused(String id) {
        return id.equals(focusId);
    }

    protected void blur() {
        focusId = null;
    }

    protected void onInputChanged(String id) {}

    protected void onInputEnter(String id) {}

    /** Camp de cautare/introducere: pastila cu iconita si cursor; click il activeaza. */
    protected void input(GuiGraphics g, String id, String hint, float x, float y, float w, float h, int max, int mx, int my) {
        boolean f = id.equals(focusId);
        String t = text(id);
        boolean caret = f && (System.currentTimeMillis() / 500) % 2 == 0;
        FUi.searchBar(g, "in-" + id, f ? t + (caret ? "|" : " ") : t, hint, x, y, w, h, f, mx, my);
        hit((int) x, (int) y, (int) w, (int) h, () -> {
            focusId = id;
            maxLen.put(id, max);
        });
    }

    private final Map<String, Integer> maxLen = new HashMap<>();

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (focusId != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                focusId = null;
                return true;
            }
            StringBuilder b = buf.computeIfAbsent(focusId, k -> new StringBuilder());
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                onInputEnter(focusId);
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (b.length() > 0) {
                    b.setLength(b.length() - 1);
                    onInputChanged(focusId);
                }
                return true;
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (focusId != null) {
            StringBuilder b = buf.computeIfAbsent(focusId, k -> new StringBuilder());
            Integer m = maxLen.get(focusId);
            if (c >= 32 && c != 127 && b.length() < (m == null ? 24 : m)) {
                b.append(c);
                onInputChanged(focusId);
            }
            return true;
        }
        return super.charTyped(c, mods);
    }

    // ------------------------------------------------------------------ formatare

    /** 12345 -> "12.345 lei" (separator de mii cu punct). */
    public static String lei(double v) {
        return String.format(Locale.US, "%,.0f", v).replace(',', '.') + " lei";
    }

    public static String num(double v) {
        return String.format(Locale.US, "%,.0f", v).replace(',', '.');
    }

    // ------------------------------------------------------------------ text

    public static float mid(float y, float h, float size) {
        return y + h / 2f + size * 0.36f;
    }

    public static void t(GuiGraphics g, String s, float x, float base, float size, int color) {
        Gfx.text(g, s, x, base, size, color, 0, false, 0f);
    }

    public static void tx(GuiGraphics g, String s, float x, float base, float size, int color) {
        Gfx.text(g, s, x, base, size, color, 0, true, 0f);
    }

    public static void tr(GuiGraphics g, String s, float x, float base, float size, int color) {
        Gfx.text(g, s, x, base, size, color, 2, true, 0f);
    }

    public static void tc(GuiGraphics g, String s, float x, float base, float size, int color) {
        Gfx.text(g, s, x, base, size, color, 1, true, 0f);
    }

    public static String clip(String s, float max, float size, boolean xl) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= max) return s;
        String r = s;
        while (r.length() > 1 && Gfx.width(r + "…", size, xl) > max) r = r.substring(0, r.length() - 1);
        return r.trim() + "…";
    }

    // ------------------------------------------------------------------ componente

    /** Pastila mica de stare; intoarce latimea. */
    public static float pill(GuiGraphics g, String label, float x, float y, int color) {
        float w = Gfx.width(label, 6.4f, true) + 10f;
        Gfx.rrect(g, x, y, w, 12f, 6f, FTheme.alpha(color, 46));
        Gfx.text(g, label, x + 5f, y + 8.4f, 6.4f, color, 0, true, 0f);
        return w;
    }

    public static void card(GuiGraphics g, float x, float y, float w, float h) {
        FTheme.panel(g, x, y, w, h, 10f, FTheme.BG2, FTheme.LINE);
    }

    protected boolean btn(GuiGraphics g, String id, String label, String ic, float x, float y, float w, float h, int style,
                          boolean enabled, int mx, int my, Runnable action) {
        boolean hov = FUi.button(g, id, label, ic, x, y, w, h, style, enabled, mx, my);
        if (enabled) hit((int) x, (int) y, (int) w, (int) h, action);
        return hov;
    }

    protected float chipAt(GuiGraphics g, String id, String label, float x, float y, float h, boolean sel, int mx, int my, Runnable action) {
        float w = FUi.chipWidth(label, h, false);
        FUi.chip(g, id, label, x, y, w, h, sel, 0, mx, my);
        hit((int) x, (int) y, (int) w, (int) h, action);
        return w + 5f;
    }

    protected void iconBtn(GuiGraphics g, String id, String ic, float x, float y, float s, boolean active, int mx, int my, Runnable action) {
        FUi.iconButton(g, id, ic, x, y, s, active, mx, my);
        hit((int) x, (int) y, (int) s, (int) s, action);
    }

    /** Antet de ecran: titlu + subtitlu la stanga, buton de inchidere la dreapta. */
    protected void top(GuiGraphics g, String title, String sub, int mx, int my) {
        tx(g, title, px + 18, py + 30, 14f, FTheme.TEXT);
        if (sub != null && !sub.isEmpty()) t(g, sub, px + 19, py + 42, 7f, FTheme.MUTED);
        iconBtn(g, "x-close", "close", px + pw - 32, py + 14, 18, false, mx, my, this::onClose);
        FTheme.divider(g, px + 16, py + 50, pw - 32);
    }

    /** Panoul de fundal al ecranului (umbra si panoul; voalul e desenat deja de {@link #draw}). */
    protected void shell(GuiGraphics g) {
        FTheme.shadow(g, px, py, pw, ph, 14f);
        FTheme.panel(g, px, py, pw, ph, 14f, FTheme.BG, FTheme.LINE);
    }

    /** Soldul in coltul din dreapta-sus: o pastila cu eticheta. */
    protected void balance(GuiGraphics g, String label, double v, float right, float y) {
        String val = lei(v);
        float w = Gfx.width(val, 8f, true) + Gfx.width(label, 5.6f, true) + 26f;
        float x = right - w;
        Gfx.rrect(g, x, y, w, 18f, 9f, FTheme.BG2);
        t(g, label, x + 9, y + 12, 5.6f, FTheme.FAINT);
        tr(g, val, right - 9, y + 12.4f, 8f, FTheme.TEXT);
    }

    // ------------------------------------------------------------------ confirmare

    private boolean modalOpen;
    private Runnable modalOk;
    private float modalT;

    protected void openModal(Runnable onOk) {
        modalOk = onOk;
        modalOpen = true;
        modalT = 0f;
    }

    protected void closeModal() {
        modalOpen = false;
        modalOk = null;
    }

    protected boolean modalActive() {
        return modalOpen;
    }

    /** Deseneaza fereastra de confirmare (daca e deschisa) si inregistreaza zonele ei; se apeleaza ultima in cadru. */
    protected void drawModal(GuiGraphics g, String title, String text, String ok, String cancel, boolean danger, int mx, int my) {
        if (!modalOpen) return;
        modalT = Math.min(1f, modalT + 0.12f);
        hit(0, 0, pw, ph, () -> {});
        FUi.modal(g, "fs-modal", title, text, ok, cancel, danger, 0, 0, pw, ph, modalT, mx, my);
        float[] a = FUi.modalSecondary();
        float[] b = FUi.modalPrimary();
        hit((int) a[0], (int) a[1], (int) a[2], (int) a[3], this::closeModal);
        final Runnable run = modalOk;
        hit((int) b[0], (int) b[1], (int) b[2], (int) b[3], () -> {
            closeModal();
            if (run != null) run.run();
        });
    }

    protected static boolean inr(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
