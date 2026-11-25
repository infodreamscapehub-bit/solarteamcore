package ro.solarstudios.flowcity.battlepass;

import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Meniul Battlepass: nivel + bara XP, obiective zilnice/saptamanale (legate de FJobs) si grila de recompense (click = revendica). */
public final class BattlepassScreen extends FlowScreen {
    public BattlepassScreen() {
        super("Battlepass", 560, 312);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        header(g, "Battlepass", "legat de joburile FJobs", mx, my);
        int x0 = px + 12;
        int y = py + HEAD + 10;
        int w = pw - 24;
        if (!BattlepassClient.enabled) {
            Ui.text(g, "Battlepass-ul este dezactivat pe server.", x0, y, Theme.MUTED);
            return;
        }
        int lvl = BattlepassClient.level;
        long inLevel = lvl >= BattlepassClient.max ? BattlepassClient.per : BattlepassClient.xp % BattlepassClient.per;
        Ui.text(g, "Nivel " + lvl + " / " + BattlepassClient.max, x0, y, Theme.TEXT);
        Ui.textRight(g, inLevel + " / " + BattlepassClient.per + " XP", x0 + w, y, Theme.MUTED);
        Ui.round(g, x0, y + 12, w, 8, Theme.PANEL_3);
        int fill = (int) (w * Math.min(1.0, inLevel / (double) BattlepassClient.per));
        if (fill > 0) Ui.round(g, x0, y + 12, fill, 8, Theme.ACCENT);
        y += 30;

        int colW = 268;
        Ui.text(g, "Obiective", x0, y, Theme.ACCENT);
        int oy = y + 14;
        String last = "";
        for (BattlepassClient.Obj o : BattlepassClient.objectives) {
            if (!o.period.equals(last)) {
                last = o.period;
                Ui.text(g, "daily".equals(o.period) ? "Zilnice (se reseteaza la miezul noptii)" : "Saptamanale (luni)", x0, oy, Theme.MUTED);
                oy += 12;
            }
            Ui.round(g, x0, oy, colW, 26, Theme.PANEL_2);
            Ui.text(g, Ui.clip(o.label, colW - 70), x0 + 6, oy + 4, o.done ? Theme.OK : Theme.TEXT);
            Ui.textRight(g, "+" + o.xp + " XP", x0 + colW - 6, oy + 4, o.done ? Theme.OK : Theme.ACCENT);
            Ui.round(g, x0 + 6, oy + 17, colW - 12, 4, Theme.PANEL_3);
            int f = (int) ((colW - 12) * Math.min(1.0, o.n / (double) o.t));
            if (f > 0) Ui.round(g, x0 + 6, oy + 17, f, 4, o.done ? Theme.OK : Theme.ACCENT_DIM);
            oy += 30;
        }
        if (BattlepassClient.objectives.isEmpty()) Ui.text(g, "Nu sunt obiective configurate.", x0, oy, Theme.FAINT);

        int rx = x0 + colW + 14;
        int rw = w - colW - 14;
        Ui.text(g, "Recompense (click pe nivel deblocat)", rx, y, Theme.ACCENT);
        int cols = 5;
        int gap = 4;
        int cw = (rw - gap * (cols - 1)) / cols;
        int ch = 30;
        int i = 0;
        for (BattlepassClient.Reward r : BattlepassClient.rewards) {
            int cx = rx + (i % cols) * (cw + gap);
            int cy = y + 14 + (i / cols) * (ch + gap);
            i++;
            if (cy + ch > py + ph - 8) break;
            boolean unlocked = r.level <= lvl;
            boolean can = unlocked && !r.claimed;
            boolean hov = can && Ui.in(mx, my, cx, cy, cw, ch);
            int bg = r.claimed ? Theme.PANEL_3 : can ? (hov ? Theme.ACCENT : Theme.ACCENT_DIM) : Theme.PANEL_2;
            Ui.round(g, cx, cy, cw, ch, bg);
            Ui.textCenter(g, String.valueOf(r.level), cx + cw / 2, cy + 5, unlocked ? Theme.TEXT : Theme.FAINT);
            Ui.textCenter(g, r.claimed ? "luat" : Ui.clip(r.label, cw - 4), cx + cw / 2, cy + 17, r.claimed ? Theme.OK : unlocked ? Theme.TEXT : Theme.FAINT);
            if (can) {
                final int lv = r.level;
                hit(cx, cy, cw, ch, () -> BattlepassClient.claim(lv));
            }
        }
    }
}
