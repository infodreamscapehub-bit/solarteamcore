package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.fjobs.FJobsClient;

/** Rezultatul final al interventiei: reusit/esuat, stele, bani si XP (validate de server), calitate, timp, greseli, etape, rang nou. */
public final class ElecResultScreen extends ElecScreen {
    private boolean played;

    public ElecResultScreen() {
        super("Rezultat interventie");
    }

    private void leave(boolean panel) {
        ElecClient.result = null;
        ElecClient.board = null;
        Minecraft.getInstance().setScreen(null);
        if (panel) FJobsClient.openPanel();
    }

    @Override
    public void onClose() {
        leave(false);
    }

    @Override
    protected void content(GuiGraphics ms, float t) {
        JsonObject r = ElecClient.result;
        if (r == null) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        boolean ok = bool(r, "ok");
        if (!played) {
            played = true;
            if (ok) sndWin();
            else sndBad();
        }
        int col = ok ? GOOD : BAD;
        float x = 140;
        float y = 48;
        float w = 360;
        float h = 270;
        Gfx.rrect(ms, x - 2, y - 2, w + 4, h + 4, 8, col);
        Gfx.rrect(ms, x, y, w, h, 7, PANEL, 0xFF0D1015);
        hazard(ms, x + 1, y + 1, w - 2, 5);
        float k = Math.min(1f, t / 1.1f);
        bolt(ms, x + w / 2f, y + 34, 15 * (0.7f + 0.3f * k), col);
        Gfx.text(ms, str(r, "title"), x + w / 2f, y + 66, 15, col, 1, true, 1.6f);
        Gfx.text(ms, str(r, "name") + "  ·  " + str(r, "loc"), x + w / 2f, y + 80, 6.6f, MUT, 1);
        float cy = y + 98;
        if (ok) {
            stars(ms, x + w / 2f, cy + 8, (int) num(r, "stars"), 9);
            cy += 26;
            Gfx.text(ms, "+" + Ui.money(num(r, "pay") * k) + "$", x + 90, cy + 20, 20, YEL, 1, true, 0.4f);
            Gfx.text(ms, "+" + Math.round(num(r, "xp") * k) + " XP", x + 270, cy + 20, 20, BLUE, 1, true, 0.4f);
            Gfx.text(ms, "BANI", x + 90, cy + 32, 5.6f, MUT, 1, true, 0.8f);
            Gfx.text(ms, "EXPERIENȚĂ", x + 270, cy + 32, 5.6f, MUT, 1, true, 0.8f);
            cy += 46;
            Gfx.text(ms, "CALITATE", x + 20, cy + 6, 5.8f, MUT, 0, true, 0.6f);
            Gfx.bar(ms, x + 90, cy, 160, 7, (float) (num(r, "q") * k), 0xFF05070A, num(r, "q") > 0.8 ? GOOD : YEL);
            Gfx.text(ms, Math.round(num(r, "q") * 100) + "%", x + 258, cy + 7, 7f, TXT, 0, true, 0.3f);
            cy += 16;
            Gfx.text(ms, "TIMP " + mmss(num(r, "sec")) + " / " + mmss(num(r, "est")) + "   ·   GREȘELI " + (int) num(r, "mistakes") + "   ·   SERIE " + (int) num(r, "streak"),
                x + w / 2f, cy + 8, 6.4f, TXT, 1, true, 0.4f);
            cy += 20;
        } else {
            wrap(ms, str(r, "text"), x + 24, cy + 14, w - 48, 7.4f, TXT, 3);
            cy += 50;
            Gfx.text(ms, "TIMP " + mmss(num(r, "sec")) + "   ·   GREȘELI " + (int) num(r, "mistakes"), x + w / 2f, cy + 8, 6.4f, MUT, 1, true, 0.4f);
            cy += 24;
        }
        // etape
        JsonArray st = arr(r, "stages");
        for (int i = 0; i < st.size() && i < 6; i++) {
            JsonObject s = st.get(i).getAsJsonObject();
            boolean so = bool(s, "ok");
            Gfx.disc(ms, x + 26, cy + 4 + i * 12, 3, so ? GOOD : BAD);
            Gfx.text(ms, str(s, "name"), x + 36, cy + 7 + i * 12, 6.2f, so ? TXT : MUT, 0);
            Gfx.text(ms, so ? Math.round(num(s, "q") * 100) + "%" : "—", x + w - 24, cy + 7 + i * 12, 6.2f, so ? GOOD : BAD, 2);
        }
        if (r.has("rankUp")) {
            Gfx.rrect(ms, x + 20, y + h - 62, w - 40, 20, 4, 0xFF3A2E08);
            Gfx.rrect(ms, x + 20, y + h - 62, 3, 20, 1, YEL);
            Gfx.text(ms, "RANG NOU: " + str(r, "rankUp").toUpperCase(), x + w / 2f, y + h - 48, 8, YEL, 1, true, 1f);
        }
        btn(ms, "PANOU JOB", x + 20, y + h - 34, 150, 24, true, false, () -> leave(true));
        btn(ms, "CONTINUĂ", x + w - 170, y + h - 34, 150, 24, true, true, () -> leave(false));
    }
}
