package ro.solarstudios.flowcity.hype;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** HUD FlowCity: bani, banca, rang, job + XP, sete. Se ascunde cu F1 sau /hud. */
public final class HypeHud {
    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL || !HypeState.hudOn) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameSettings.hideGUI) return;
        JsonObject s = HypeState.state;
        if (!s.has("cash")) return; // serverul nu a trimis inca starea
        MatrixStack ms = e.getMatrixStack();
        int x = 6;
        int y = 6;
        int w = 132;
        boolean hasJob = !JsonClientModule.str(s, "job", "").isEmpty();
        boolean thirstOn = JsonClientModule.bool(s, "thirstOn", false);
        int h = 36 + (hasJob ? 20 : 0) + (thirstOn ? 14 : 0);
        Ui.frame(ms, x, y, w, h, Theme.alpha(Theme.PANEL, 0xCC), Theme.LINE);
        Ui.rect(ms, x + 1, y + 1, 2, h - 2, Theme.ACCENT);
        Ui.textShadow(ms, JsonClientModule.str(s, "name", mc.player.getName().getString()), x + 8, y + 5, Theme.TEXT);
        Ui.textRight(ms, JsonClientModule.str(s, "rank", ""), x + w - 5, y + 5, Theme.VIOLET);
        Ui.text(ms, "$" + Ui.money(JsonClientModule.num(s, "cash", 0)), x + 8, y + 17, Theme.OK);
        Ui.textRight(ms, "Banca $" + Ui.money(JsonClientModule.num(s, "bank", 0)), x + w - 5, y + 17, Theme.MUTED);
        int cy = y + 30;
        if (hasJob) {
            int lvl = JsonClientModule.integer(s, "level", 1);
            long need = JsonClientModule.lng(s, "xpNeed", 0);
            float frac = need <= 0 ? 1f : (float) JsonClientModule.lng(s, "xp", 0) / need;
            Ui.text(ms, Ui.clip(JsonClientModule.str(s, "job", ""), w - 50), x + 8, cy, Theme.TEXT);
            Ui.textRight(ms, "Niv " + lvl, x + w - 5, cy, Theme.ACCENT);
            Ui.bar(ms, x + 8, cy + 10, w - 16, 6, frac, Theme.ACCENT);
            cy += 20;
        }
        if (thirstOn) {
            int t = JsonClientModule.integer(s, "thirst", 100);
            int col = t < 20 ? Theme.BAD : (t < 50 ? Theme.WARN : 0xFF38BDF8);
            Ui.text(ms, "Sete", x + 8, cy, Theme.MUTED);
            Ui.bar(ms, x + 38, cy + 1, w - 46, 6, t / 100f, col);
        }
        if (System.currentTimeMillis() < HypeState.earnUntil) {
            Ui.textShadow(ms, HypeState.earnText, x + 8, y + h + 4, Theme.OK);
        }
    }
}
