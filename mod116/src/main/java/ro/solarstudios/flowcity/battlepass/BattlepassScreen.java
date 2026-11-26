package ro.solarstudios.flowcity.battlepass;

import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Meniul Battlepass: sezonul curent, nivel si bara de XP, misiunile zilnice si saptamanale (progres din joc)
 * si grila de recompense pe doua trasee - FREE (randul de sus) si PREMIUM (randul de jos). Click = revendica.
 */
public final class BattlepassScreen extends FlowScreen {
    private int scroll;

    public BattlepassScreen() {
        super("Battlepass", 560, 312);
    }

    /** Eticheta progresului, pe intelesul misiunii (metri -> km, minute -> ore). */
    private static String progress(BattlepassClient.Obj o) {
        if ("drive".equals(o.type)) return fmtKm(o.n) + " / " + fmtKm(o.t);
        if ("hours".equals(o.type)) return o.n + " / " + o.t + " min";
        if ("earn".equals(o.type)) return o.n + " / " + o.t + "$";
        return o.n + " / " + o.t;
    }

    private static String fmtKm(long meters) {
        if (meters < 1000) return meters + " m";
        return String.format(java.util.Locale.ROOT, "%.1f km", meters / 1000.0);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        String sub = BattlepassClient.season.isEmpty() ? "misiuni si recompense" : BattlepassClient.season;
        header(g, "Battlepass", sub, mx, my);
        int x0 = px + 12;
        int y = py + HEAD + 10;
        int w = pw - 24;
        if (!BattlepassClient.enabled) {
            Ui.text(g, "Battlepass-ul este dezactivat pe server.", x0, y, Theme.MUTED);
            return;
        }
        if (BattlepassClient.isAdmin) {
            int bw = 74;
            boolean hov = Ui.in(mx, my, px + pw - bw - 34, py + 12, bw, 14);
            Ui.round(g, px + pw - bw - 34, py + 12, bw, 14, hov ? Theme.ACCENT : Theme.PANEL_3);
            Ui.textCenter(g, "EDITOR", px + pw - bw - 34 + bw / 2, py + 15, Theme.TEXT);
            hit(px + pw - bw - 34, py + 12, bw, 14, () -> {
                BattlepassClient.requestAdmin();
                minecraft.setScreen(new BattlepassAdminScreen());
            });
        }

        int lvl = BattlepassClient.level;
        long inLevel = lvl >= BattlepassClient.max ? BattlepassClient.per : BattlepassClient.xp % BattlepassClient.per;
        Ui.text(g, "Nivel " + lvl + " / " + BattlepassClient.max, x0, y, Theme.TEXT);
        String right = inLevel + " / " + BattlepassClient.per + " XP";
        if (!BattlepassClient.running) right = "sezon incheiat · " + right;
        Ui.textRight(g, right, x0 + w, y, BattlepassClient.running ? Theme.MUTED : Theme.WARN);
        Ui.round(g, x0, y + 12, w, 8, Theme.PANEL_3);
        int fill = (int) (w * Math.min(1.0, inLevel / (double) BattlepassClient.per));
        if (fill > 0) Ui.round(g, x0, y + 12, fill, 8, Theme.ACCENT);
        y += 30;

        int colW = 268;
        Ui.text(g, "Misiuni", x0, y, Theme.ACCENT);
        int oy = y + 14;
        String last = "";
        for (BattlepassClient.Obj o : BattlepassClient.objectives) {
            if (oy + 30 > py + ph - 12) break;
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
            Ui.textRight(g, progress(o), x0 + colW - 6, oy + 15, Theme.FAINT);
            oy += 30;
        }
        if (BattlepassClient.objectives.isEmpty()) Ui.text(g, "Nu sunt misiuni configurate.", x0, oy, Theme.FAINT);

        drawRewards(g, mx, my, x0 + colW + 14, y, w - colW - 14, lvl);
    }

    private void drawRewards(GuiGraphics g, int mx, int my, int rx, int y, int rw, int lvl) {
        // bara de premium
        boolean prem = BattlepassClient.premium;
        Ui.text(g, "Recompense", rx, y, Theme.ACCENT);
        int by = y + 12;
        if (prem) {
            Ui.round(g, rx, by, rw, 16, Theme.PANEL_2);
            Ui.textCenter(g, "PREMIUM ACTIV", rx + rw / 2, by + 4, Theme.OK);
        } else if (BattlepassClient.premiumBuyable) {
            boolean hov = Ui.in(mx, my, rx, by, rw, 16);
            Ui.round(g, rx, by, rw, 16, hov ? Theme.ACCENT : Theme.PANEL_3);
            Ui.textCenter(g, "CUMPARA PREMIUM · " + (long) BattlepassClient.premiumCost + "$", rx + rw / 2, by + 4, Theme.TEXT);
            hit(rx, by, rw, 16, BattlepassClient::buyPremium);
        } else {
            Ui.round(g, rx, by, rw, 16, Theme.PANEL_2);
            Ui.textCenter(g, "Premium indisponibil", rx + rw / 2, by + 4, Theme.FAINT);
        }
        int top = by + 22;
        Ui.text(g, "FREE", rx, top, Theme.MUTED);
        Ui.text(g, "PREMIUM", rx, top + 36, prem ? Theme.WARN : Theme.FAINT);

        // coloane de nivel, derulabile
        int cw = 30;
        int gap = 3;
        int cols = Math.max(1, (rw - 34) / (cw + gap));
        int startX = rx + 34;
        int maxScroll = Math.max(0, BattlepassClient.rewards.size() - cols);
        if (scroll > maxScroll) scroll = maxScroll;
        for (int i = 0; i < cols && i + scroll < BattlepassClient.rewards.size(); i++) {
            BattlepassClient.Reward r = BattlepassClient.rewards.get(i + scroll);
            int cx = startX + i * (cw + gap);
            boolean unlocked = r.level <= lvl;
            Ui.textCenter(g, String.valueOf(r.level), cx + cw / 2, top + 20, unlocked ? Theme.TEXT : Theme.FAINT);
            cell(g, mx, my, cx, top + 10, cw, "free", r.free, r.freeClaimed, unlocked, true, r.level);
            cell(g, mx, my, cx, top + 46, cw, "premium", r.premium, r.premiumClaimed, unlocked, prem, r.level);
        }
        if (maxScroll > 0) {
            Ui.text(g, "roata mouse = niveluri " + (scroll + 1) + "-" + Math.min(BattlepassClient.rewards.size(), scroll + cols),
                rx, top + 84, Theme.FAINT);
        }
    }

    private void cell(GuiGraphics g, int mx, int my, int cx, int cy, int cw, String track,
                      String label, boolean claimed, boolean unlocked, boolean trackOk, int level) {
        int ch = 24;
        if (label == null || label.isEmpty()) {
            Ui.round(g, cx, cy, cw, ch, Theme.PANEL_3);
            return;
        }
        boolean can = unlocked && trackOk && !claimed;
        boolean hov = can && Ui.in(mx, my, cx, cy, cw, ch);
        int bg = claimed ? Theme.PANEL_3 : can ? (hov ? Theme.ACCENT : Theme.ACCENT_DIM) : Theme.PANEL_2;
        Ui.round(g, cx, cy, cw, ch, bg);
        Ui.textCenter(g, claimed ? "luat" : Ui.clip(label, cw - 4), cx + cw / 2, cy + 8,
            claimed ? Theme.OK : unlocked ? Theme.TEXT : Theme.FAINT);
        if (can) hit(cx, cy, cw, ch, () -> BattlepassClient.claim(level, track));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }
}
