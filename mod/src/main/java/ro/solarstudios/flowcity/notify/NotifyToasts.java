package ro.solarstudios.flowcity.notify;

import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;

/** Toast-uri animate in dreapta-sus: intra glisand, stau 5s cu bara de timp, ies glisand. Maxim 4 odata. */
public final class NotifyToasts {
    private static final int W = 176;
    private static final int H = 38;
    private static final long IN = 320;
    private static final long HOLD = 5000;
    private static final long OUT = 300;

    private static final class Toast {
        final String type;
        final String title;
        final List<String> lines;
        final long at = System.currentTimeMillis();

        Toast(String type, String title, List<String> lines) {
            this.type = type;
            this.title = title;
            this.lines = lines;
        }
    }

    private static final List<Toast> TOASTS = new ArrayList<>();

    public static void add(String type, String title, String text) {
        List<String> ls = Wrap.lines(text, W - 38);
        if (ls.size() > 2) {
            ls = new ArrayList<>(ls.subList(0, 2));
            ls.set(1, Ui.clip(ls.get(1) + "...", W - 38));
        }
        TOASTS.add(new Toast(type, title, ls));
        while (TOASTS.size() > 4) TOASTS.remove(0);
        sound(type);
    }

    public static void clear() {
        TOASTS.clear();
    }

    private static void sound(String type) {
        String name = "BAD".equals(type) || "WARN".equals(type) ? "alert"
            : ("MONEY".equals(type) || "JOB".equals(type) || "OK".equals(type) ? "money" : "notify");
        ro.solarstudios.flowcity.client.ui.FlowSounds.play(name, 0.8F);
    }

    private static float ease(float t) {
        float k = 1f - Math.max(0f, Math.min(1f, t));
        return 1f - k * k * k;
    }

    private boolean failed;
    private static boolean broken;

    @SubscribeEvent
    public void onRender(RenderGuiEvent.Post e) {
        if (broken) return;
        try {
            doRender(e);
        } catch (RuntimeException ex) {
            // un bug de desenare nu are voie sa dea crash jocului: logam o data si oprim elementul
            if (!failed) {
                failed = true;
                org.apache.logging.log4j.LogManager.getLogger("FlowCity").error("Eroare la desenarea overlay-ului " + getClass().getSimpleName(), ex);
            }
            TOASTS.clear();
        }
    }

    private void doRender(RenderGuiEvent.Post e) {
        if (TOASTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics ms = e.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        long now = System.currentTimeMillis();
        int slot = 0;
        Iterator<Toast> it = TOASTS.iterator();
        while (it.hasNext()) {
            Toast t = it.next();
            long age = now - t.at;
            if (age > IN + HOLD + OUT) {
                it.remove();
                continue;
            }
            float slide = age < IN ? ease(age / (float) IN) : (age > IN + HOLD ? 1f - ease((age - IN - HOLD) / (float) OUT) : 1f);
            int x = sw - 8 - Math.round(slide * (W + 8));
            int y = 8 + slot * (H + 4);
            slot++;
            int c = NotifyState.color(t.type);
            Ui.round(ms, x, y, W, H, Theme.alpha(Theme.PANEL, 0xEE));
            Ui.frame(ms, x, y, W, H, Theme.alpha(Theme.PANEL, 0xEE), Theme.mix(Theme.LINE, c, 0.5f));
            Ui.rect(ms, x + 1, y + 1, 3, H - 2, c);
            // pictograma pulseaza usor la aparitie
            float pop = age < 500 ? 1f + (float) Math.sin(age / 500f * Math.PI) * 0.12f : 1f;
            int box = Math.round(22 * pop);
            Ui.round(ms, x + 10 + (22 - box) / 2, y + 8 + (22 - box) / 2, box, box, Theme.mix(Theme.PANEL, c, 0.28f));
            Ui.textCenter(ms, NotifyState.icon(t.type), x + 21, y + 15, c);
            Ui.textShadow(ms, Ui.clip(t.title, W - 44), x + 38, y + 6, Theme.TEXT);
            int ty = y + 17;
            for (String l : t.lines) {
                Ui.text(ms, l, x + 38, ty, Theme.MUTED);
                ty += 10;
            }
            // bara de timp
            float left = age < IN ? 1f : Math.max(0f, 1f - (age - IN) / (float) HOLD);
            Ui.rect(ms, x + 4, y + H - 3, Math.round((W - 8) * left), 1, Theme.alpha(c, 0xB0));
        }
    }
}
