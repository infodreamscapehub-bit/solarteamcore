package ro.solarstudios.flowcity.client.ui;

import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Editorul de HUD: tragi elementele cu mouse-ul (se lipesc de margini si de centru), rotita schimba marimea, poti ascunde
 * sau reseta fiecare element. Pozitiile se salveaza in config/flowcity-hud.json. HUD-ul real ramane vizibil dedesubt.
 */
public final class HudEditorScreen extends FlowScreen {
    private static final int GOLD = 0xFFF6C15A;
    private static final int GOLD2 = 0xFFD98E2B;
    private static final int WHITE = 0xFFF4F1EA;
    private static final int MUTED = 0xFF8E8C86;
    private static final int PW = 420;
    private static final int PH = 62;

    private HudLayout.El sel;
    private HudLayout.El drag;
    private float rawX;
    private float rawY;
    private float guideX = Float.NaN;
    private float guideY = Float.NaN;
    private final long born = System.currentTimeMillis();

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

    private boolean inPanel(double mx, double my) {
        int px0 = (width - PW) / 2;
        int py0 = height - PH - 10;
        return mx >= px0 && mx < px0 + PW && my >= py0 && my < py0 + PH;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = easeOut((now - born) / 250f);
        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0x66050608, open));

        // ghidaje de aliniere cand te lipesti de margine / centru
        if (!Float.isNaN(guideX)) Gfx.rrect(ms, guideX - 0.5f, 0, 1, height, 0, 0x99F6C15A);
        if (!Float.isNaN(guideY)) Gfx.rrect(ms, 0, guideY - 0.5f, width, 1, 0, 0x99F6C15A);

        // elementele
        List<HudLayout.El> all = new ArrayList<>(HudLayout.ELS.values());
        for (HudLayout.El e : all) {
            float[] r = rectOf(e);
            boolean s = e == sel;
            boolean h = mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
            boolean live = HudLayout.live(e);
            if (!e.visible) {
                Gfx.rrect(ms, r[0], r[1], r[2], r[3], 8, 0x33E2453F);
            } else if (!live) {
                Gfx.rrect(ms, r[0], r[1], r[2], r[3], 8, Gfx.fade(0xFF1F2228, 0.85f), Gfx.fade(0xFF14161A, 0.85f));
                Gfx.text(ms, "previzualizare", r[0] + r[2] / 2f, r[1] + r[3] / 2f + 3, 7, 0xFF6F6D68, 1);
            }
            int bc = s ? GOLD : (h ? 0xFFFFFFFF : 0x88FFFFFF);
            outline(ms, r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 2, s ? 1.6f : 1f, bc);
            // eticheta
            String lab = e.label + (e.visible ? "" : " (ascuns)");
            float lw = Gfx.width(lab, 7.4f, false) + 14;
            float ly = r[1] - 15 < 2 ? r[1] + r[3] + 3 : r[1] - 15;
            Gfx.rrect(ms, r[0], ly, lw, 13, 6.5f, s ? GOLD : 0xDD22252C, s ? GOLD2 : 0xDD14161A);
            Gfx.text(ms, lab, r[0] + lw / 2f, ly + 9.2f, 7.4f, s ? 0xFF2A1A06 : WHITE, 1);
        }

        panel(ms, mx, my, open);
    }

    private static void outline(GuiGraphics ms, float x, float y, float w, float h, float t, int c) {
        Gfx.rrect(ms, x, y, w, t, 0, c);
        Gfx.rrect(ms, x, y + h - t, w, t, 0, c);
        Gfx.rrect(ms, x, y, t, h, 0, c);
        Gfx.rrect(ms, x + w - t, y, t, h, 0, c);
    }

    private void panel(GuiGraphics ms, int mx, int my, float open) {
        int x = (width - PW) / 2;
        int y = height - PH - 10 + Math.round((1f - open) * 20);
        Gfx.rrect(ms, x - 3, y + 2, PW + 6, PH + 6, 18, 0x55000000);
        Gfx.rrect(ms, x, y, PW, PH, 15, 0xF01D2026, 0xF0121418);
        Gfx.rrect(ms, x + 0.5f, y + 0.5f, PW - 1, 1, 0, 0x14FFFFFF);

        if (sel == null) {
            Gfx.text(ms, "EDITOR HUD", x + 18, y + 24, 11, WHITE, 0, true, 0.8f);
            Gfx.text(ms, "Apasă pe un element ca să-l muți. Rotița schimbă mărimea.", x + 18, y + 40, 7.6f, MUTED, 0);
            Gfx.text(ms, "Se salvează automat.", x + 18, y + 51, 6.8f, 0xFF6F6D68, 0);
        } else {
            Gfx.text(ms, sel.label.toUpperCase(), x + 18, y + 22, 9.5f, GOLD, 0, true, 0.6f);
            // vizibil
            toggle(ms, x + 18, y + 33, sel.visible, mx, my);
            Gfx.text(ms, "Vizibil", x + 50, y + 44, 7.8f, sel.visible ? WHITE : MUTED, 0);
            // marime
            Gfx.text(ms, "MĂRIME", x + 108, y + 22, 6.2f, 0xFF6F6D68, 0, false, 0.9f);
            small(ms, "-", x + 108, y + 29, 22, mx, my, () -> scaleBy(sel, -0.05f));
            Gfx.text(ms, Math.round(sel.scale * 100) + "%", x + 108 + 22 + 20, y + 45, 9, WHITE, 1, true, 0f);
            small(ms, "+", x + 108 + 22 + 40, y + 29, 22, mx, my, () -> scaleBy(sel, 0.05f));
            small2(ms, "Resetează", x + 224, y + 29, 74, mx, my, () -> {
                sel.reset();
                HudLayout.save();
            });
        }
        small2(ms, "Resetează tot", x + PW - 18 - 74 - 6 - 74, y + 29 - (sel == null ? 0 : 0), 74, mx, my, () -> {
            for (HudLayout.El e : HudLayout.ELS.values()) e.reset();
            HudLayout.save();
        });
        gold(ms, "GATA", x + PW - 18 - 74, y + 29, 74, mx, my, this::onClose);
    }

    private void scaleBy(HudLayout.El e, float d) {
        e.scale = Math.max(0.5f, Math.min(2f, Math.round((e.scale + d) * 100f) / 100f));
        HudLayout.save();
    }

    private void toggle(GuiGraphics ms, int x, int y, boolean on, int mx, int my) {
        boolean h = mx >= x && mx < x + 28 && my >= y && my < y + 16;
        Gfx.rrect(ms, x, y + 2, 26, 14, 7, on ? Gfx.mixArgb(0xFF5FDC8F, 0xFF000000, h ? 0.05f : 0.15f) : 0xFF2A2D34);
        Gfx.disc(ms, on ? x + 19 : x + 7, y + 9, 5.2f, 0xFFFFFFFF);
        hit(x, y, 28, 18, () -> {
            sel.visible = !sel.visible;
            HudLayout.save();
        });
    }

    private void small(GuiGraphics ms, String label, int x, int y, int w, int mx, int my, Runnable r) {
        boolean h = mx >= x && mx < x + w && my >= y && my < y + 22;
        Gfx.rrect(ms, x, y, w, 22, 11, h ? 0xFF2E333C : 0xFF23272E, h ? 0xFF252931 : 0xFF1B1E24);
        Gfx.text(ms, label, x + w / 2f, y + 15, 12, h ? GOLD : WHITE, 1, true, 0f);
        hit(x, y, w, 22, r);
    }

    private void small2(GuiGraphics ms, String label, int x, int y, int w, int mx, int my, Runnable r) {
        boolean h = mx >= x && mx < x + w && my >= y && my < y + 22;
        Gfx.rrect(ms, x, y, w, 22, 11, h ? 0xFF2E333C : 0xFF23272E, h ? 0xFF252931 : 0xFF1B1E24);
        Gfx.text(ms, label, x + w / 2f, y + 14.6f, 7.8f, h ? GOLD : 0xFFD4D2CC, 1);
        hit(x, y, w, 22, r);
    }

    private void gold(GuiGraphics ms, String label, int x, int y, int w, int mx, int my, Runnable r) {
        boolean h = mx >= x && mx < x + w && my >= y && my < y + 22;
        Gfx.rrect(ms, x, y, w, 22, 11, Gfx.mixArgb(GOLD, 0xFFFFFFFF, h ? 0.18f : 0f), Gfx.mixArgb(GOLD2, 0xFFFFFFFF, h ? 0.1f : 0f));
        Gfx.text(ms, label, x + w / 2f, y + 15, 9, 0xFF2A1A06, 1, true, 0.6f);
        hit(x, y, w, 22, r);
    }

    // ------------------------------------------------------------------ mouse

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !inPanel(mx, my)) {
            HudLayout.El pick = null;
            for (HudLayout.El e : HudLayout.ELS.values()) {
                float[] r = rectOf(e);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) pick = e;
            }
            sel = pick;
            drag = pick;
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
        if (drag == null) return super.mouseDragged(mx, my, button, dx, dy);
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
        }
        if (!Float.isNaN(sy)) {
            drag.dy = rawY + sy;
            guideY = findGuide(r, sy, false);
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
        if (drag != null) HudLayout.save();
        drag = null;
        guideX = Float.NaN;
        guideY = Float.NaN;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        HudLayout.El target = sel;
        if (target == null) {
            for (HudLayout.El e : HudLayout.ELS.values()) {
                float[] r = rectOf(e);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) target = e;
            }
        }
        if (target != null) scaleBy(target, (float) Math.signum(delta) * 0.05f);
        return true;
    }

    @Override
    public void onClose() {
        HudLayout.save();
        super.onClose();
    }
}
