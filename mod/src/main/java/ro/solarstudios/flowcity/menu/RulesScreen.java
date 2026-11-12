package ro.solarstudios.flowcity.menu;

import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;

/** Fereastra de regulament: termeni si conditii FlowCity. Cu bifa de acceptare si buton ACCEPT. */
public final class RulesScreen extends FlowScreen {
    private final Screen parent;
    private final Runnable onAccept;
    private boolean checked;
    private int scroll;
    private int scrollMax;
    private boolean reachedEnd;
    private final long born = System.currentTimeMillis();

    /** @param onAccept ce se intampla dupa ACCEPT (de obicei conectarea); null = doar inchide. */
    public RulesScreen(Screen parent, Runnable onAccept) {
        super("Regulament", 380, 300);
        this.parent = parent;
        this.onAccept = onAccept;
        this.checked = Rules.accepted();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float in = Math.min(1f, (System.currentTimeMillis() - born) / 250f);
        int slide = Math.round((1f - in) * 18f);
        int y0 = py + slide;
        Ui.rect(ms, px, y0, pw, 3, Theme.ACCENT);
        Ui.textShadow(ms, "REGULAMENT FLOWCITY", px + 14, y0 + 12, Theme.TEXT);
        Ui.text(ms, "Termeni si conditii - citeste pana la capat", px + 14, y0 + 24, Theme.MUTED);
        button(ms, "X", px + pw - 26, y0 + 10, 16, 16, mx, my, true, Theme.PANEL_3, this::onClose);

        int top = y0 + 42;
        int bottom = y0 + ph - 76;
        int textW = pw - 44;
        List<String[]> rows = new ArrayList<>(); // {type, text}
        int num = 0;
        for (String l : Rules.lines()) {
            if (l.startsWith("#")) {
                rows.add(new String[] {"h", l.replaceFirst("^#+\\s*", "")});
            } else {
                num++;
                for (String w : Wrap.lines(num + ". " + l, textW - 8)) rows.add(new String[] {"t", w});
                rows.add(new String[] {"g", ""});
            }
        }
        int total = 0;
        for (String[] r : rows) total += "h".equals(r[0]) ? 18 : ("g".equals(r[0]) ? 4 : 10);
        scrollMax = Math.max(0, total - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        if (scroll >= scrollMax) reachedEnd = true;

        Ui.frame(ms, px + 10, top, pw - 20, bottom - top, Theme.PANEL_2, Theme.LINE);
        Ui.scissor(px + 11, top + 1, pw - 22, bottom - top - 2);
        int yy = top + 6 - scroll;
        for (String[] r : rows) {
            int h = "h".equals(r[0]) ? 18 : ("g".equals(r[0]) ? 4 : 10);
            if (yy + h > top && yy < bottom) {
                if ("h".equals(r[0])) {
                    Ui.rect(ms, px + 16, yy + 2, 3, 10, Theme.ACCENT);
                    Ui.textShadow(ms, r[1], px + 24, yy + 3, Theme.ACCENT);
                } else if ("t".equals(r[0])) {
                    Ui.text(ms, r[1], px + 22, yy, Theme.TEXT);
                }
            }
            yy += h;
        }
        Ui.endScissor();
        if (scrollMax > 0) {
            int trackH = bottom - top - 8;
            int thumb = Math.max(16, trackH * (bottom - top) / (total + 1));
            int ty = top + 4 + (trackH - thumb) * scroll / scrollMax;
            Ui.round(ms, px + pw - 18, top + 4, 4, trackH, Theme.PANEL_3);
            Ui.round(ms, px + pw - 18, ty, 4, thumb, Theme.ACCENT);
        }

        // bifa
        int cy = bottom + 10;
        boolean canCheck = reachedEnd || scrollMax == 0;
        Ui.frame(ms, px + 14, cy, 12, 12, canCheck ? Theme.PANEL_2 : Theme.DISABLED, checked ? Theme.OK : Theme.LINE);
        if (checked) Ui.rect(ms, px + 17, cy + 3, 6, 6, Theme.OK);
        Ui.text(ms, "Am citit regulamentul si accept termenii si conditiile FlowCity.", px + 32, cy + 2, canCheck ? Theme.TEXT : Theme.FAINT);
        if (canCheck) hit(px + 14, cy - 2, pw - 28, 16, () -> checked = !checked);
        if (!canCheck) Ui.text(ms, "Deruleaza pana jos ca sa poti accepta.", px + 32, cy + 14, Theme.WARN);

        int by = y0 + ph - 34;
        button(ms, "REFUZ", px + 14, by, 90, 22, mx, my, true, Theme.PANEL_3, this::onClose);
        button(ms, onAccept != null ? "ACCEPT SI INTRA" : "ACCEPT", px + pw - 154, by, 140, 22, mx, my, checked && canCheck, Theme.OK, () -> {
            Rules.accept();
            if (onAccept != null) onAccept.run();
            else closeScreen();
        });
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) (delta * 16);
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        return true;
    }
}
