package ro.solarstudios.flowcity.client.ui;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Editorul de HUD (stil iarna): tragi elementele cu mouse-ul (se lipesc de margini, de centru si, optional, de grila de 8 px),
 * rotita schimba marimea, lista din stanga alege/ascunde elementele, panoul de proprietati are marime, opacitate, culoare si parti.
 * Taste: sageti = 1 px (Shift = 8 px), Delete = ascunde, Ctrl+Z = anuleaza, G = grila, P = previzualizare, Tab = urmatorul element.
 * Pozitiile se salveaza in config/flowcity-hud.json. HUD-ul real ramane vizibil dedesubt.
 */
public final class HudEditorScreen extends FlowScreen {
    private static final int[] PALETTE = {0x4DA3FF, 0x62D98F, 0xF6C15A, 0xF0584C, 0x9B6BFF, 0xFF6BB5, 0x2FD5D0, 0xFF8A3C, 0xF2F7FF};
    private static final int TOP_H = 26;
    private static final int LIST_W = 152;
    private static final int PROP_H = 118;
    private static final int GRID = 8;

    /** Slider-ul tras acum: 0 = marime, 1 = opacitate, -1 = niciunul. */
    private int slider = -1;
    private final float[][] sliderBox = new float[2][3];

    private HudLayout.El sel;
    private HudLayout.El drag;
    private HudLayout.El hoverRow;
    private float rawX;
    private float rawY;
    private float guideX = Float.NaN;
    private float guideY = Float.NaN;
    private boolean dragPushed;

    private final long born = System.currentTimeMillis();
    private long lastFrame;
    private float listSlide = 1f;
    private float propSlide;
    private boolean listOpen = true;
    private boolean grid;
    private boolean preview;
    private boolean confirmReset;

    private final List<String> undo = new ArrayList<>();
    private long lastNudge;
    private HudLayout.El lastNudgeEl;
    private long lastScroll;

    /** Dreptunghiuri ale interfetei din ultimul frame (x, y, w, h), ca sa nu tragem elemente prin ele. */
    private final float[] topBox = new float[4];
    private final float[] listBox = new float[4];
    private final float[] propBox = new float[4];
    private final float[] modalBox = new float[4];
    private final float[] tabBox = new float[4];
    private boolean propShown;

    private String tip;

    public HudEditorScreen() {
        super("Editor HUD", 100, 100);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private float[] rectOf(HudLayout.El e) {
        float[] d = HudLayout.live(e) ? new float[] {e.rx, e.ry, e.rw, e.rh} : HudLayout.defaults(e.id, width, height);
        return HudLayout.rect(e, d[0], d[1], d[2], d[3]);
    }

    private static boolean in(double mx, double my, float[] b) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    private boolean overUi(double mx, double my) {
        if (in(mx, my, topBox)) return true;
        if (listSlide > 0.02f && in(mx, my, listBox)) return true;
        if (in(mx, my, tabBox)) return true;
        return propShown && in(mx, my, propBox);
    }

    private static void tx(GuiGraphics ms, String s, float x, float cy, float size, int color, int align, boolean bold) {
        Gfx.text(ms, s, x, cy + size * 0.36f, size, color, align, bold, 0f);
    }

    private void tipIf(boolean hover, String t) {
        if (hover) tip = t;
    }

    // ------------------------------------------------------------------ undo

    private void pushUndo() {
        String s = HudLayout.snapshot();
        if (undo.isEmpty() || !undo.get(undo.size() - 1).equals(s)) {
            undo.add(s);
            if (undo.size() > 60) undo.remove(0);
        }
    }

    private void doUndo() {
        String cur = HudLayout.snapshot();
        while (!undo.isEmpty()) {
            String s = undo.remove(undo.size() - 1);
            if (!s.equals(cur)) {
                HudLayout.restore(s);
                return;
            }
        }
    }

    private void doResetAll() {
        pushUndo();
        HudLayout.resetAll();
        confirmReset = false;
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        tip = null;
        long now = System.currentTimeMillis();
        float open = easeOut((now - born) / 300f);
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        listSlide += ((listOpen ? 1f : 0f) - listSlide) * Math.min(1f, dt * 12f);
        propSlide += ((sel != null ? 1f : 0f) - propSlide) * Math.min(1f, dt * 12f);
        if (Math.abs(propSlide - (sel != null ? 1f : 0f)) < 0.01f) propSlide = sel != null ? 1f : 0f;
        propShown = !preview && propSlide > 0.02f;

        if (preview) {
            topBox[2] = 0;
            listBox[2] = 0;
            tabBox[2] = 0;
            propBox[2] = 0;
            drawElements(ms, mx, my, false);
            String t = "PREVIZUALIZARE  ·  apasă orice tastă sau click ca să revii";
            float w = Gfx.width(t, 7.6f, false) + 24;
            Sk.slant(ms, (width - w) / 2f, height - 30, w, 18, 5f, 0xD9081018);
            tx(ms, t, width / 2f, height - 21, 7.6f, Sk.WHITE, 1, false);
            return;
        }

        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0xFF050A14, 0.34f * open));
        if (grid) drawGrid(ms, open);
        if (!Float.isNaN(guideX)) Gfx.rrect(ms, guideX - 0.5f, 0, 1, height, 0, Gfx.fade(Sk.ACC, 0.8f));
        if (!Float.isNaN(guideY)) Gfx.rrect(ms, 0, guideY - 0.5f, width, 1, 0, Gfx.fade(Sk.ACC, 0.8f));
        drawElements(ms, mx, my, true);

        topBar(ms, mx, my, open);
        elementList(ms, mx, my, open);
        properties(ms, mx, my, open);
        if (sel == null && propSlide < 0.05f) {
            String t = "Apasă pe un element sau alege-l din listă ca să-l editezi";
            float w = Gfx.width(t, 7.4f, false) + 24;
            Sk.slant(ms, (width - w) / 2f, height - 30, w, 18, 5f, Gfx.fade(0xFF081018, 0.85f * open));
            tx(ms, t, width / 2f, height - 21, 7.4f, Sk.MUTED, 1, false);
        }
        if (confirmReset) modal(ms, mx, my);
        else modalBox[2] = 0;
        drawTip(ms, mx, my);
    }

    private void drawGrid(GuiGraphics ms, float open) {
        for (int x = 0; x < width; x += GRID) {
            boolean major = x % (GRID * 8) == 0;
            Gfx.rrect(ms, x, 0, 1, height, 0, Gfx.fade(0xFFFFFFFF, (major ? 0.10f : 0.045f) * open));
        }
        for (int y = 0; y < height; y += GRID) {
            boolean major = y % (GRID * 8) == 0;
            Gfx.rrect(ms, 0, y, width, 1, 0, Gfx.fade(0xFFFFFFFF, (major ? 0.10f : 0.045f) * open));
        }
    }

    private void drawElements(GuiGraphics ms, int mx, int my, boolean ui) {
        if (!ui) return;
        List<HudLayout.El> all = new ArrayList<>(HudLayout.ELS.values());
        for (HudLayout.El e : all) {
            float[] r = rectOf(e);
            boolean s = e == sel;
            boolean h = !overUi(mx, my) && Sk.in(mx, my, r[0], r[1], r[2], r[3]);
            boolean hot = h || e == hoverRow;
            boolean live = HudLayout.live(e);
            if (!e.visible) {
                Gfx.rrect(ms, r[0], r[1], r[2], r[3], 0, Gfx.fade(Sk.BAD, 0.16f));
            } else if (!live) {
                Gfx.rrect(ms, r[0], r[1], r[2], r[3], 0, 0xC0111A28);
                tx(ms, "previzualizare", r[0] + r[2] / 2f, r[1] + r[3] / 2f, 7f, Sk.FAINT, 1, false);
            }
            int bc = s ? Sk.ACC : (hot ? Sk.WHITE : Gfx.fade(Sk.WHITE, 0.45f));
            outline(ms, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, s ? 1.6f : 1f, bc);
            if (s) corners(ms, r[0] - 3, r[1] - 3, r[2] + 6, r[3] + 6);
            String lab = e.label + (e.visible ? "" : " (ascuns)");
            float lw = Gfx.width(lab, 7.2f, false) + 16;
            float ly = r[1] - 16 < TOP_H + 2 ? r[1] + r[3] + 4 : r[1] - 16;
            Sk.slant(ms, r[0], ly, lw, 13, 4f, s ? Sk.ACC : 0xE0081018);
            tx(ms, lab, r[0] + lw / 2f + 1, ly + 6.5f, 7.2f, s ? 0xFF061426 : Sk.WHITE, 1, false);
        }
    }

    private static void outline(GuiGraphics ms, float x, float y, float w, float h, float t, int c) {
        Gfx.rrect(ms, x, y, w, t, 0, c);
        Gfx.rrect(ms, x, y + h - t, w, t, 0, c);
        Gfx.rrect(ms, x, y, t, h, 0, c);
        Gfx.rrect(ms, x + w - t, y, t, h, 0, c);
    }

    /** Colturi de selectie (L-uri) in jurul elementului ales. */
    private static void corners(GuiGraphics ms, float x, float y, float w, float h) {
        float l = 7f;
        float t = 1.8f;
        int c = Sk.ACC;
        Gfx.rrect(ms, x, y, l, t, 0, c);
        Gfx.rrect(ms, x, y, t, l, 0, c);
        Gfx.rrect(ms, x + w - l, y, l, t, 0, c);
        Gfx.rrect(ms, x + w - t, y, t, l, 0, c);
        Gfx.rrect(ms, x, y + h - t, l, t, 0, c);
        Gfx.rrect(ms, x, y + h - l, t, l, 0, c);
        Gfx.rrect(ms, x + w - l, y + h - t, l, t, 0, c);
        Gfx.rrect(ms, x + w - t, y + h - l, t, l, 0, c);
    }

    // ------------------------------------------------------------------ bara de sus

    private void topBar(GuiGraphics ms, int mx, int my, float open) {
        float oy = -(1f - open) * (TOP_H + 4);
        topBox[0] = 0;
        topBox[1] = oy;
        topBox[2] = width;
        topBox[3] = TOP_H;
        Gfx.rrect(ms, 0, oy, width, TOP_H, 0, 0xEA081018);
        Gfx.rrect(ms, 0, oy + TOP_H - 1.5f, width, 1.5f, 0, Gfx.fade(Sk.ACC, 0.55f));
        Sk.slant(ms, 0, oy + TOP_H - 4f, 96, 4f, 3f, Sk.ACC);
        Gfx.text(ms, "EDITOR HUD", 12, oy + 17.5f, 10f, Sk.WHITE, 0, true, 1f);

        String[] full = {"GRILĂ", "ANULEAZĂ", "RESETEAZĂ TOT", "PREVIZUALIZARE", "SALVEAZĂ ȘI ÎNCHIDE"};
        String[] shortL = {"GRILĂ", "ANULEAZĂ", "RESET", "PREVIZ.", "SALVEAZĂ"};
        float total = 0f;
        for (String s : full) total += Gfx.width(s, 7.4f, false) + 18 + 5;
        String[] lab = total > width - 150 ? shortL : full;
        float[] ws = new float[5];
        float x = width - 8;
        for (int i = 4; i >= 0; i--) {
            ws[i] = Gfx.width(lab[i], 7.4f, false) + 18;
            x -= ws[i];
            float by = oy + 4;
            boolean hover = Sk.in(mx, my, x, by, ws[i], 17);
            switch (i) {
                case 0:
                    tbtn(ms, lab[i], x, by, ws[i], 17, true, grid, hover, false);
                    tipIf(hover, "Grilă de 8 px: elementele se lipesc de ea (tasta G)");
                    hit(Math.round(x), Math.round(by), Math.round(ws[i]), 17, () -> grid = !grid);
                    break;
                case 1:
                    tbtn(ms, lab[i], x, by, ws[i], 17, !undo.isEmpty(), false, hover, false);
                    tipIf(hover, "Anulează ultima schimbare (Ctrl+Z)");
                    if (!undo.isEmpty()) hit(Math.round(x), Math.round(by), Math.round(ws[i]), 17, this::doUndo);
                    break;
                case 2:
                    tbtn(ms, lab[i], x, by, ws[i], 17, true, false, hover, false);
                    tipIf(hover, "Readuce toate elementele la starea implicită");
                    hit(Math.round(x), Math.round(by), Math.round(ws[i]), 17, () -> confirmReset = true);
                    break;
                case 3:
                    tbtn(ms, lab[i], x, by, ws[i], 17, true, false, hover, false);
                    tipIf(hover, "Ascunde panourile ca să vezi HUD-ul curat (tasta P)");
                    hit(Math.round(x), Math.round(by), Math.round(ws[i]), 17, () -> preview = true);
                    break;
                default:
                    tbtn(ms, lab[i], x, by, ws[i], 17, true, false, hover, true);
                    tipIf(hover, "Salvează pozitiile și închide (Esc)");
                    hit(Math.round(x), Math.round(by), Math.round(ws[i]), 17, this::onClose);
                    break;
            }
            x -= 5;
        }
        float tw = Gfx.width("EDITOR HUD", 10f, true) + 12 + 24;
        if (x - tw > 220) {
            tx(ms, "trage = mută  ·  rotița = mărime  ·  săgeți = mișcare fină  ·  Del = ascunde", tw, oy + 13, 6.8f, Sk.FAINT, 0, false);
        }
    }

    private void tbtn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean active, boolean hover, boolean primary) {
        int bg;
        int fg;
        if (!enabled) {
            bg = 0xFF111A28;
            fg = Sk.FAINT;
        } else if (primary || active) {
            bg = hover ? 0xFF7DBBFF : Sk.ACC;
            fg = 0xFF061426;
        } else {
            bg = hover ? 0xFF22344D : 0xFF14202F;
            fg = hover ? Sk.WHITE : 0xFFC9CBD0;
        }
        Sk.slant(ms, x, y, w, h, 4f, bg);
        tx(ms, label, x + w / 2f + 1, y + h / 2f, 7.4f, fg, 1, true);
    }

    // ------------------------------------------------------------------ lista de elemente

    private void elementList(GuiGraphics ms, int mx, int my, float open) {
        hoverRow = null;
        List<HudLayout.El> all = new ArrayList<>(HudLayout.ELS.values());
        float rowH = 22f;
        float h = 30f + all.size() * rowH + 6f;
        float x = 8 - (1f - listSlide) * (LIST_W + 16) - (1f - open) * 30f;
        float y = TOP_H + 8;
        listBox[0] = x;
        listBox[1] = y;
        listBox[2] = LIST_W;
        listBox[3] = h;

        // limba de deschis/inchis lipita de panou
        float tabX = x + LIST_W;
        tabBox[0] = tabX;
        tabBox[1] = y;
        tabBox[2] = 14;
        tabBox[3] = 38;
        boolean tabHover = Sk.in(mx, my, tabX, y, 14, 38);
        Sk.slant(ms, tabX - 2, y, 16, 38, 0f, tabHover ? 0xFF22344D : 0xE6081018);
        float ax = tabX + 7;
        float ay = y + 19;
        if (listOpen) Gfx.poly(ms, new float[] {ax + 2, ay - 4.5f, ax + 2, ay + 4.5f, ax - 3, ay}, tabHover ? Sk.ACC : Sk.MUTED);
        else Gfx.poly(ms, new float[] {ax - 2, ay - 4.5f, ax - 2, ay + 4.5f, ax + 3, ay}, tabHover ? Sk.ACC : Sk.MUTED);
        tipIf(tabHover, listOpen ? "Ascunde lista" : "Arată lista de elemente");
        hit(Math.round(tabX - 2), Math.round(y), 16, 38, () -> listOpen = !listOpen);
        if (listSlide < 0.02f) return;

        Sk.cut(ms, x, y, LIST_W, h, 8, Gfx.fade(0xFF081018, 0.88f));
        Gfx.rrect(ms, x, y, LIST_W - 8, 1.5f, 0, Gfx.fade(Sk.ACC, 0.7f));
        Gfx.text(ms, "ELEMENTE", x + 12, y + 17, 7f, Sk.MUTED, 0, true, 1.2f);
        Gfx.text(ms, String.valueOf(all.size()), x + LIST_W - 12, y + 17, 7f, Sk.FAINT, 2, true, 0f);

        float ry = y + 26;
        for (HudLayout.El e : all) {
            boolean s = e == sel;
            boolean hover = Sk.in(mx, my, x + 6, ry, LIST_W - 12, rowH - 2);
            if (hover) hoverRow = e;
            Gfx.rrect(ms, x + 6, ry, LIST_W - 12, rowH - 2, 0, s ? Sk.ROW_HOT : (hover ? 0xC0172335 : Sk.ROW));
            if (s) Gfx.rrect(ms, x + 6, ry, 3, rowH - 2, 0, Sk.ACC);
            float cy = ry + (rowH - 2) / 2f;
            boolean eyeHover = Sk.in(mx, my, x + 8, ry, 24, rowH - 2);
            eye(ms, x + 22, cy, e.visible, eyeHover);
            tx(ms, e.label, x + 36, cy, 7.6f, e.visible ? (s ? Sk.WHITE : 0xFFC9D4E4) : Sk.FAINT, 0, s);
            if (!e.visible) tx(ms, "ascuns", x + LIST_W - 14, cy, 6.2f, Sk.BAD, 2, false);
            else if (!e.isDefault()) Gfx.disc(ms, x + LIST_W - 16, cy, 2f, Gfx.fade(Sk.ACC, 0.85f));
            final HudLayout.El fe = e;
            hit(Math.round(x + 6), Math.round(ry), LIST_W - 12, Math.round(rowH - 2), () -> sel = fe);
            hit(Math.round(x + 8), Math.round(ry), 24, Math.round(rowH - 2), () -> {
                pushUndo();
                fe.visible = !fe.visible;
                HudLayout.save();
            });
            tipIf(eyeHover, e.visible ? "Ascunde elementul" : "Arată elementul");
            ry += rowH;
        }
    }

    private static void eye(GuiGraphics ms, float cx, float cy, boolean on, boolean hot) {
        int c = on ? (hot ? Sk.WHITE : Sk.ACC) : (hot ? Sk.MUTED : Sk.FAINT);
        Gfx.rrect(ms, cx - 7, cy - 3.5f, 14, 7, 3.5f, c);
        Gfx.rrect(ms, cx - 5, cy - 2f, 10, 4, 2f, 0xFF0A1422);
        if (on) {
            Gfx.disc(ms, cx, cy, 2.2f, c);
        } else {
            Gfx.line(ms, cx - 6.5f, cy + 4.5f, cx + 6.5f, cy - 4.5f, 1.5f, Sk.BAD);
        }
    }

    // ------------------------------------------------------------------ proprietati

    private void properties(GuiGraphics ms, int mx, int my, float open) {
        propBox[2] = 0;
        if (!propShown || sel == null) return;
        final int PW = Math.min(520, width - 16);
        float x = (width - PW) / 2f;
        float[] er = rectOf(sel);
        float yBottom = height - PROP_H - 8;
        boolean overlaps = er[1] + er[3] + 18 > yBottom - 6 && er[1] < yBottom + PROP_H + 6
            && er[0] < x + PW + 6 && er[0] + er[2] > x - 6;
        float yTop = TOP_H + 8;
        float yy = overlaps && yBottom > yTop + 10 ? yTop : yBottom;
        float slideOff = (1f - propSlide) * (PROP_H + 12) * (yy == yTop ? -1f : 1f);
        float y = yy + slideOff;
        propBox[0] = x;
        propBox[1] = y;
        propBox[2] = PW;
        propBox[3] = PROP_H;

        Sk.cut(ms, x, y, PW, PROP_H, 10, Gfx.fade(0xFF081018, 0.92f));
        Gfx.rrect(ms, x, y, PW - 10, 1.5f, 0, Gfx.fade(Sk.ACC, 0.7f));

        // antet: nume + vizibil + reseteaza
        Gfx.text(ms, sel.label.toUpperCase(java.util.Locale.ROOT), x + 14, y + 22, 9f, Sk.WHITE, 0, true, 0.8f);
        float bw = 84;
        float bx = x + PW - 14 - bw;
        boolean rh = Sk.in(mx, my, bx, y + 8, bw, 17);
        tbtn(ms, "RESETEAZĂ", bx, y + 8, bw, 17, true, false, rh, false);
        tipIf(rh, "Readuce doar acest element la implicit");
        hit(Math.round(bx), Math.round(y + 8), Math.round(bw), 17, () -> {
            pushUndo();
            sel.reset();
            HudLayout.save();
        });
        float vw = 76;
        float vx = bx - 6 - vw;
        boolean vh = Sk.in(mx, my, vx, y + 8, vw, 17);
        tbtn(ms, sel.visible ? "VIZIBIL" : "ASCUNS", vx, y + 8, vw, 17, true, sel.visible, vh, false);
        tipIf(vh, sel.visible ? "Ascunde elementul (Del)" : "Arată elementul");
        hit(Math.round(vx), Math.round(y + 8), Math.round(vw), 17, () -> {
            pushUndo();
            sel.visible = !sel.visible;
            HudLayout.save();
        });
        Gfx.rrect(ms, x + 14, y + 31, PW - 28, 1, 0, Gfx.fade(0xFFFFFFFF, 0.08f));

        // slidere: marime + opacitate
        float sw = PW >= 480 ? 176f : 130f;
        slider(ms, 0, "MĂRIME", x + 14, y + 40, sw, (sel.scale - 0.5f) / 1.5f, Math.round(sel.scale * 100) + "%", mx, my);
        slider(ms, 1, "OPACITATE", x + 14, y + 72, sw, (sel.alpha - 0.2f) / 0.8f, Math.round(sel.alpha * 100) + "%", mx, my);

        float xr = x + 14 + sw + 24;
        // culoare
        Gfx.text(ms, "CULOARE ACCENT", xr, y + 44, 6.2f, Sk.FAINT, 0, false, 0.9f);
        float sx = xr + 9;
        float sy = y + 58;
        boolean none = sel.color == 0;
        boolean hn = Sk.in(mx, my, sx - 10, sy - 10, 20, 20);
        Gfx.disc(ms, sx, sy, 7f, 0xFF14202F);
        Gfx.line(ms, sx - 4.5f, sy + 4.5f, sx + 4.5f, sy - 4.5f, 1.4f, none ? Sk.ACC : Sk.MUTED);
        if (none) Gfx.ring(ms, sx, sy, 8.4f, 9.8f, Sk.ACC, Sk.ACC);
        tipIf(hn, "Culoarea implicită");
        hit(Math.round(sx - 10), Math.round(sy - 10), 20, 20, () -> {
            pushUndo();
            sel.color = 0;
            HudLayout.save();
        });
        sx += 20;
        for (int c : PALETTE) {
            boolean s = sel.color == c;
            boolean h = Sk.in(mx, my, sx - 10, sy - 10, 20, 20);
            int col = 0xFF000000 | c;
            if (s) Gfx.ring(ms, sx, sy, 8.4f, 9.8f, Sk.ACC, Sk.ACC);
            Gfx.disc(ms, sx, sy, h || s ? 7.2f : 6.6f, Gfx.mixArgb(col, 0xFFFFFFFF, 0.25f), Gfx.mixArgb(col, 0xFF000000, 0.15f), -2, -2.4f);
            tipIf(h, "Culoare accent");
            final int cc = c;
            hit(Math.round(sx - 10), Math.round(sy - 10), 20, 20, () -> {
                pushUndo();
                sel.color = cc;
                HudLayout.save();
            });
            sx += 20;
        }

        // parti ale elementului (comutatoare)
        String[][] parts = HudLayout.PARTS.get(sel.id);
        if (parts != null) {
            Gfx.text(ms, "PĂRȚI AFIȘATE", xr, y + 82, 6.2f, Sk.FAINT, 0, false, 0.9f);
            float cx2 = xr;
            float cy2 = y + 88;
            for (String[] p : parts) {
                float w = Gfx.width(p[1], 7f, false) + 20;
                if (cx2 + w > x + PW - 12) {
                    cx2 = xr;
                    cy2 += 14;
                }
                boolean on = !sel.off.contains(p[0]);
                boolean h = Sk.in(mx, my, cx2, cy2, w, 12);
                int bg = on ? Gfx.mixArgb(Sk.GOOD, 0xFF000000, h ? 0.45f : 0.55f) : (h ? 0xFF22344D : Sk.ROW);
                Sk.slant(ms, cx2, cy2, w, 12, 3f, bg);
                Gfx.disc(ms, cx2 + 8, cy2 + 6, 2f, on ? Sk.GOOD : Sk.FAINT);
                tx(ms, p[1], cx2 + 13, cy2 + 6, 6.6f, on ? Sk.WHITE : Sk.MUTED, 0, false);
                tipIf(h, on ? "Ascunde partea" : "Arată partea");
                final String key = p[0];
                hit(Math.round(cx2), Math.round(cy2), Math.round(w), 12, () -> {
                    pushUndo();
                    if (!sel.off.remove(key)) sel.off.add(key);
                    HudLayout.save();
                });
                cx2 += w + 3;
            }
        } else {
            tx(ms, "Rotița mouse-ului = mărime", xr, y + 84, 6.8f, Sk.FAINT, 0, false);
            tx(ms, "Săgeți = 1 px (Shift = 8 px)  ·  Tab = următorul", xr, y + 96, 6.8f, Sk.FAINT, 0, false);
        }
    }

    private void slider(GuiGraphics ms, int idx, String label, float x, float y, float w, float frac, String val, int mx, int my) {
        frac = Math.max(0f, Math.min(1f, frac));
        Gfx.text(ms, label, x, y + 4, 6.2f, Sk.FAINT, 0, false, 0.9f);
        Gfx.text(ms, val, x + w, y + 5, 8.4f, Sk.WHITE, 2, true, 0f);
        float ty = y + 14;
        sliderBox[idx][0] = x;
        sliderBox[idx][1] = ty;
        sliderBox[idx][2] = w;
        Gfx.rrect(ms, x, ty, w, 4, 0, 0xFF1B2839);
        Gfx.rrect(ms, x, ty, Math.max(4, w * frac), 4, 0, Sk.ACC);
        boolean hot = slider == idx || Sk.in(mx, my, x + w * frac - 9, ty - 7, 18, 18);
        Gfx.rrect(ms, x + w * frac - (hot ? 4f : 3.2f), ty - (hot ? 3.5f : 2.6f), hot ? 8f : 6.4f, hot ? 11f : 9.2f, 0, hot ? Sk.WHITE : 0xFFDCE8FA);
        tipIf(hot, idx == 0 ? "Trage sau folosește rotița pe element" : "Cât de transparent e elementul");
    }

    private void sliderTo(int idx, double mx) {
        if (sel == null) return;
        float f = (float) Math.max(0, Math.min(1, (mx - sliderBox[idx][0]) / sliderBox[idx][2]));
        if (idx == 0) sel.scale = Math.round((0.5f + f * 1.5f) * 100f) / 100f;
        else sel.alpha = Math.round((0.2f + f * 0.8f) * 100f) / 100f;
    }

    private void scaleBy(HudLayout.El e, float d) {
        e.scale = Math.max(0.5f, Math.min(2f, Math.round((e.scale + d) * 100f) / 100f));
        HudLayout.save();
    }

    // ------------------------------------------------------------------ confirmare + tooltip

    private void modal(GuiGraphics ms, int mx, int my) {
        Gfx.rrect(ms, 0, 0, width, height, 0, 0x88050A14);
        float w = 270;
        float h = 92;
        float x = (width - w) / 2f;
        float y = (height - h) / 2f;
        modalBox[0] = x;
        modalBox[1] = y;
        modalBox[2] = w;
        modalBox[3] = h;
        hit(0, 0, width, height, () -> confirmReset = false);
        Sk.cut(ms, x, y, w, h, 10, 0xF4081018);
        Gfx.rrect(ms, x, y, w - 10, 2, 0, Sk.BAD);
        Gfx.text(ms, "RESETEZI TOT?", x + 16, y + 26, 10f, Sk.WHITE, 0, true, 0.8f);
        tx(ms, "Toate elementele revin la poziția și setările implicite.", x + 16, y + 42, 7f, Sk.MUTED, 0, false);
        tx(ms, "Poți anula cu Ctrl+Z.", x + 16, y + 54, 7f, Sk.FAINT, 0, false);
        float by = y + h - 28;
        boolean yh = Sk.in(mx, my, x + 16, by, 120, 18);
        Sk.slant(ms, x + 16, by, 120, 18, 5f, yh ? 0xFFFF7B6E : Sk.BAD);
        tx(ms, "DA, RESETEAZĂ", x + 16 + 61, by + 9, 7.6f, 0xFF2A0A06, 1, true);
        hit(Math.round(x + 16), Math.round(by), 120, 18, this::doResetAll);
        boolean nh = Sk.in(mx, my, x + 144, by, 110, 18);
        Sk.slant(ms, x + 144, by, 110, 18, 5f, nh ? 0xFF22344D : 0xFF14202F);
        tx(ms, "NU", x + 144 + 56, by + 9, 7.6f, Sk.WHITE, 1, true);
        hit(Math.round(x + 144), Math.round(by), 110, 18, () -> confirmReset = false);
    }

    private void drawTip(GuiGraphics ms, int mx, int my) {
        if (tip == null || confirmReset) return;
        float w = Gfx.width(tip, 7f, false) + 16;
        float x = Math.min(mx + 10, width - w - 4);
        float y = my + 16;
        if (y + 16 > height) y = my - 22;
        Sk.cut(ms, x, y, w, 16, 4, 0xF2060C16);
        Gfx.rrect(ms, x, y, 2, 16, 0, Sk.ACC);
        tx(ms, tip, x + 9, y + 8, 7f, Sk.WHITE, 0, false);
    }

    // ------------------------------------------------------------------ mouse

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (preview) {
            preview = false;
            return true;
        }
        if (confirmReset) return super.mouseClicked(mx, my, button) || true;
        if (button == 0 && sel != null && propShown && in(mx, my, propBox)) {
            for (int i = 0; i < 2; i++) {
                float[] b = sliderBox[i];
                if (b[2] > 0 && mx >= b[0] - 8 && mx <= b[0] + b[2] + 8 && my >= b[1] - 8 && my <= b[1] + 14) {
                    pushUndo();
                    slider = i;
                    sliderTo(i, mx);
                    return true;
                }
            }
        }
        if (button == 0 && !overUi(mx, my)) {
            HudLayout.El pick = null;
            for (HudLayout.El e : HudLayout.ELS.values()) {
                float[] r = rectOf(e);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) pick = e;
            }
            sel = pick;
            drag = pick;
            dragPushed = false;
            if (pick != null) {
                rawX = pick.dx;
                rawY = pick.dy;
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (slider >= 0) {
            sliderTo(slider, mx);
            return true;
        }
        if (drag == null) return super.mouseDragged(mx, my, button, dx, dy);
        if (!dragPushed) {
            pushUndo();
            dragPushed = true;
        }
        rawX += (float) dx;
        rawY += (float) dy;
        drag.dx = rawX;
        drag.dy = rawY;
        float[] r = rectOf(drag);
        float sx = snap(new float[] {r[0], r[0] + r[2], r[0] + r[2] / 2f}, new float[] {8f, width - 8f, width / 2f});
        float sy = snap(new float[] {r[1], r[1] + r[3], r[1] + r[3] / 2f}, new float[] {8f, height - 8f, height / 2f});
        guideX = Float.NaN;
        guideY = Float.NaN;
        if (!Float.isNaN(sx)) {
            drag.dx = rawX + sx;
            guideX = findGuide(r, sx, true);
        } else if (grid) {
            drag.dx = rawX + (Math.round(r[0] / GRID) * GRID - r[0]);
        }
        if (!Float.isNaN(sy)) {
            drag.dy = rawY + sy;
            guideY = findGuide(r, sy, false);
        } else if (grid) {
            drag.dy = rawY + (Math.round(r[1] / GRID) * GRID - r[1]);
        }
        return true;
    }

    /** Cea mai mica corectie (sub 5 px) care aliniaza una din marginile/centrul elementului la o tinta; NaN daca nu e niciuna. */
    private static float snap(float[] have, float[] want) {
        float best = Float.NaN;
        for (float h : have) {
            for (float w : want) {
                float d = w - h;
                if (Math.abs(d) < 5f && (Float.isNaN(best) || Math.abs(d) < Math.abs(best))) best = d;
            }
        }
        return best;
    }

    private float findGuide(float[] r, float shift, boolean horiz) {
        float[] have = horiz ? new float[] {r[0], r[0] + r[2], r[0] + r[2] / 2f} : new float[] {r[1], r[1] + r[3], r[1] + r[3] / 2f};
        float[] want = horiz ? new float[] {8f, width - 8f, width / 2f} : new float[] {8f, height - 8f, height / 2f};
        for (float h : have) for (float w : want) if (Math.abs(w - h - shift) < 0.01f) return w;
        return Float.NaN;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (drag != null || slider >= 0) HudLayout.save();
        slider = -1;
        drag = null;
        guideX = Float.NaN;
        guideY = Float.NaN;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (preview || confirmReset) return true;
        HudLayout.El target = sel;
        if (target == null) {
            for (HudLayout.El e : HudLayout.ELS.values()) {
                float[] r = rectOf(e);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) target = e;
            }
        }
        if (target != null) {
            long now = System.currentTimeMillis();
            if (now - lastScroll > 600) pushUndo();
            lastScroll = now;
            scaleBy(target, (float) Math.signum(delta) * 0.05f);
        }
        return true;
    }

    // ------------------------------------------------------------------ tastatura

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (preview) {
            preview = false;
            return true;
        }
        if (confirmReset) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) doResetAll();
            else confirmReset = false;
            return true;
        }
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_Z) {
            doUndo();
            return true;
        }
        if (key == GLFW.GLFW_KEY_G) {
            grid = !grid;
            return true;
        }
        if (key == GLFW.GLFW_KEY_P) {
            preview = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            List<HudLayout.El> all = new ArrayList<>(HudLayout.ELS.values());
            if (!all.isEmpty()) {
                int i = sel == null ? -1 : all.indexOf(sel);
                int n = shift ? (i <= 0 ? all.size() - 1 : i - 1) : (i + 1) % all.size();
                sel = all.get(n);
            }
            return true;
        }
        if (sel != null) {
            float step = shift ? 8f : 1f;
            float ddx = 0f;
            float ddy = 0f;
            if (key == GLFW.GLFW_KEY_LEFT) ddx = -step;
            else if (key == GLFW.GLFW_KEY_RIGHT) ddx = step;
            else if (key == GLFW.GLFW_KEY_UP) ddy = -step;
            else if (key == GLFW.GLFW_KEY_DOWN) ddy = step;
            if (ddx != 0f || ddy != 0f) {
                long now = System.currentTimeMillis();
                if (lastNudgeEl != sel || now - lastNudge > 500) pushUndo();
                lastNudge = now;
                lastNudgeEl = sel;
                sel.dx += ddx;
                sel.dy += ddy;
                HudLayout.save();
                return true;
            }
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                pushUndo();
                sel.visible = false;
                HudLayout.save();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        HudLayout.save();
        super.onClose();
    }
}
