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
    private static final int PH = 150;
    private static final int[] PALETTE = {0xF6C15A, 0x2F8CFF, 0x5FDC8F, 0xE2453F, 0x9B6BFF, 0xFF6BB5, 0x2FD5D0, 0xFF8A3C, 0xF4F1EA};
    /** Slider-ul tras acum: 0 = marime, 1 = opacitate, -1 = niciunul. */
    private int slider = -1;
    private final float[][] sliderBox = new float[2][3];

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

    private int pw() {
        return Math.min(540, width - 12);
    }

    private static String shortLabel(HudLayout.El e) {
        switch (e.id) {
            case "hud": return "Panou";
            case "speedo": return "Bord mașină";
            case "toasts": return "Notificări";
            case "news": return "Știri";
            default: return "Mesaj";
        }
    }

    private boolean inPanel(double mx, double my) {
        int PW = pw();
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
        final int PW = pw();
        int x = (width - PW) / 2;
        int y = height - PH - 10 + Math.round((1f - open) * 24);
        Gfx.rrect(ms, x - 3, y + 2, PW + 6, PH + 6, 20, 0x55000000);
        Gfx.rrect(ms, x, y, PW, PH, 16, 0xF21D2026, 0xF2121418);
        Gfx.rrect(ms, x + 0.5f, y + 0.5f, PW - 1, 1, 0, 0x14FFFFFF);

        // elementele (chip-uri), ca sa poti alege si unul ascuns
        float cx = x + 16;
        for (HudLayout.El e : HudLayout.ELS.values()) {
            String lab = shortLabel(e);
            float w = Gfx.width(lab, 7.6f, false) + 18;
            boolean s = e == sel;
            boolean h = mx >= cx && mx < cx + w && my >= y + 12 && my < y + 30;
            if (s) Gfx.rrect(ms, cx, y + 12, w, 18, 9, GOLD, GOLD2);
            else Gfx.rrect(ms, cx, y + 12, w, 18, 9, h ? 0xFF2A2E36 : 0xFF1C1F25);
            Gfx.text(ms, lab, cx + w / 2f, y + 24.2f, 7.6f, s ? 0xFF2A1A06 : (e.visible ? (h ? WHITE : 0xFFA19F98) : 0xFFE2453F), 1);
            final HudLayout.El fe = e;
            hit(Math.round(cx), y + 12, Math.round(w), 18, () -> sel = fe);
            cx += w + 5;
        }
        small2(ms, "Resetează tot", x + PW - 16 - 62 - 6 - 76, y + 10, 76, mx, my, () -> {
            for (HudLayout.El e : HudLayout.ELS.values()) e.reset();
            HudLayout.save();
        });
        gold(ms, "GATA", x + PW - 16 - 62, y + 10, 62, mx, my, this::onClose);
        Gfx.rrect(ms, x + 14, y + 38, PW - 28, 0.8f, 0, 0x18FFFFFF);

        if (sel == null) {
            Gfx.text(ms, "EDITOR HUD", x + 18, y + 66, 11, WHITE, 0, true, 0.8f);
            Gfx.text(ms, "Apasă pe un element (sau alege-l sus) ca să-l muți, să-i schimbi mărimea,", x + 18, y + 84, 7.6f, MUTED, 0);
            Gfx.text(ms, "transparența, culoarea sau părțile afișate. Trage cu mouse-ul, rotița = mărime.", x + 18, y + 96, 7.6f, MUTED, 0);
            Gfx.text(ms, "Se salvează automat.", x + 18, y + 112, 6.8f, 0xFF6F6D68, 0);
            return;
        }

        // slidere: marime + opacitate
        slider(ms, 0, "MĂRIME", x + 18, y + 54, 168, (sel.scale - 0.5f) / 1.5f, Math.round(sel.scale * 100) + "%", mx, my);
        slider(ms, 1, "OPACITATE", x + 212, y + 54, 168, (sel.alpha - 0.2f) / 0.8f, Math.round(sel.alpha * 100) + "%", mx, my);

        // vizibil + reset element
        toggle(ms, x + PW - 18 - 26 - 70, y + 48, sel.visible, mx, my);
        Gfx.text(ms, "Vizibil", x + PW - 18 - 70 + 6, y + 60, 7.8f, sel.visible ? WHITE : MUTED, 0);
        small2(ms, "Resetează", x + PW - 18 - 88, y + 70, 88, mx, my, () -> {
            sel.reset();
            HudLayout.save();
        });

        // culoare
        Gfx.text(ms, "CULOARE", x + 18, y + 96, 6.2f, 0xFF6F6D68, 0, false, 0.9f);
        float sx = x + 18 + 9;
        boolean none = sel.color == 0;
        boolean hn = mx >= sx - 10 && mx < sx + 10 && my >= y + 102 && my < y + 122;
        Gfx.disc(ms, sx, y + 112, 7.4f, 0xFF2A2D34);
        Gfx.line(ms, sx - 4.5f, y + 116.5f, sx + 4.5f, y + 107.5f, 1.4f, none ? GOLD : 0xFF8E8C86);
        if (none) Gfx.ring(ms, sx, y + 112, 8.6f, 10f, GOLD, GOLD2);
        hit(Math.round(sx - 10), y + 102, 20, 20, () -> {
            sel.color = 0;
            HudLayout.save();
        });
        sx += 21;
        for (int c : PALETTE) {
            boolean s = sel.color == c;
            boolean h = mx >= sx - 10 && mx < sx + 10 && my >= y + 102 && my < y + 122;
            int col = 0xFF000000 | c;
            if (s) Gfx.ring(ms, sx, y + 112, 8.6f, 10f, GOLD, GOLD2);
            Gfx.disc(ms, sx, y + 112, h || s ? 7.6f : 7f, Gfx.mixArgb(col, 0xFFFFFFFF, 0.3f), Gfx.mixArgb(col, 0xFF000000, 0.15f), -2, -2.4f);
            final int cc = c;
            hit(Math.round(sx - 10), y + 102, 20, 20, () -> {
                sel.color = cc;
                HudLayout.save();
            });
            sx += 21;
        }

        // parti ale elementului (comutatoare)
        String[][] parts = HudLayout.PARTS.get(sel.id);
        if (parts != null) {
            float px1 = x + 18 + 21 * (PALETTE.length + 1) + 22;
            Gfx.text(ms, "PĂRȚI AFIȘATE", px1, y + 96, 6.2f, 0xFF6F6D68, 0, false, 0.9f);
            float cx2 = px1;
            float cy2 = y + 104;
            for (String[] p : parts) {
                float w = Gfx.width(p[1], 7f, false) + 16;
                if (cx2 + w > x + PW - 14) {
                    cx2 = px1;
                    cy2 += 19;
                }
                boolean on = !sel.off.contains(p[0]);
                boolean h = mx >= cx2 && mx < cx2 + w && my >= cy2 && my < cy2 + 16;
                if (on) Gfx.rrect(ms, cx2, cy2, w, 16, 8, Gfx.mixArgb(0xFF2E9D5B, 0xFFFFFFFF, h ? 0.12f : 0f), 0xFF277F4B);
                else Gfx.rrect(ms, cx2, cy2, w, 16, 8, h ? 0xFF2A2E36 : 0xFF1C1F25);
                Gfx.text(ms, p[1], cx2 + w / 2f, cy2 + 11f, 7f, on ? 0xFFFFFFFF : 0xFF8E8C86, 1);
                final String key = p[0];
                hit(Math.round(cx2), Math.round(cy2), Math.round(w), 16, () -> {
                    if (!sel.off.remove(key)) sel.off.add(key);
                    HudLayout.save();
                });
                cx2 += w + 4;
            }
        }
    }

    private void slider(GuiGraphics ms, int idx, String label, float x, float y, float w, float frac, String val, int mx, int my) {
        frac = Math.max(0f, Math.min(1f, frac));
        Gfx.text(ms, label, x, y + 4, 6.2f, 0xFF6F6D68, 0, false, 0.9f);
        Gfx.text(ms, val, x + w, y + 4, 8.4f, WHITE, 2, true, 0f);
        float ty = y + 14;
        sliderBox[idx][0] = x;
        sliderBox[idx][1] = ty;
        sliderBox[idx][2] = w;
        Gfx.rrect(ms, x, ty, w, 5, 2.5f, 0xFF23262D);
        Gfx.rrect(ms, x, ty, Math.max(5, w * frac), 5, 2.5f, Gfx.mixArgb(GOLD, 0xFFFFFFFF, 0.1f), GOLD2);
        boolean hot = slider == idx || (mx >= x + w * frac - 9 && mx < x + w * frac + 9 && my >= ty - 6 && my < ty + 12);
        Gfx.disc(ms, x + w * frac, ty + 2.5f, hot ? 6.6f : 5.6f, 0xFFFFFFFF, 0xFFD9D6CE, -1, -1.2f);
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
        if (button == 0 && sel != null && inPanel(mx, my)) {
            for (int i = 0; i < 2; i++) {
                float[] b = sliderBox[i];
                if (b[2] > 0 && mx >= b[0] - 8 && mx <= b[0] + b[2] + 8 && my >= b[1] - 8 && my <= b[1] + 14) {
                    slider = i;
                    sliderTo(i, mx);
                    return true;
                }
            }
        }
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
        if (slider >= 0) {
            sliderTo(slider, mx);
            return true;
        }
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
        if (drag != null || slider >= 0) HudLayout.save();
        slider = -1;
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
