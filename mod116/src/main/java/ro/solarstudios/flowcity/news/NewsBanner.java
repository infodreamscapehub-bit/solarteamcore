package ro.solarstudios.flowcity.news;

import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Banner CNN sus, la mijloc: coboara animat, eticheta pulseaza (breaking), luciu care trece, bara de timp. Stirile vin pe rand. */
public final class NewsBanner {
    private static final long IN = 450;
    private static final long OUT = 400;
    private static final int H = 34;

    private static final class Entry {
        final NewsState.Item item;
        final long dur;
        long start;

        Entry(NewsState.Item item, long dur) {
            this.item = item;
            this.dur = dur;
        }
    }

    private static final List<Entry> QUEUE = new ArrayList<>();

    public static void show(NewsState.Item item, int seconds) {
        QUEUE.add(new Entry(item, seconds * 1000L));
        while (QUEUE.size() > 5) QUEUE.remove(1);
    }

    public static void clear() {
        QUEUE.clear();
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
            QUEUE.clear();
        }
    }

    private void doRender(RenderGuiEvent.Post e) {
        if (QUEUE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Entry en = QUEUE.get(0);
        long now = System.currentTimeMillis();
        if (en.start == 0) {
            en.start = now;
            ro.solarstudios.flowcity.client.ui.FlowSounds.play("message", 0.9F);
        }
        long age = now - en.start;
        if (age > IN + en.dur + OUT) {
            QUEUE.remove(0);
            return;
        }
        float slide;
        if (age < IN) {
            float k = 1f - age / (float) IN;
            slide = 1f - k * k * k;
        } else if (age > IN + en.dur) {
            float k = (age - IN - en.dur) / (float) OUT;
            slide = 1f - k * k;
        } else {
            slide = 1f;
        }
        GuiGraphics ms = e.getGuiGraphics();
        int sw = mc.getWindow().getGuiScaledWidth();
        int w = Math.min(330, sw - 20);
        int x = (sw - w) / 2;
        int y = -H + Math.round(slide * (H + 8));
        int c = NewsState.color(en.item.cat);
        double t = now / 1000.0;

        Ui.round(ms, x, y, w, H, Theme.alpha(0xFF0A1020, 0xF0));
        Ui.frame(ms, x, y, w, H, Theme.alpha(0xFF0A1020, 0xF0), Theme.mix(Theme.LINE, c, 0.6f));
        // eticheta categoriei (breaking clipeste)
        int tag = 70;
        float blink = "breaking".equals(en.item.cat) ? 0.65f + 0.35f * (float) Math.sin(t * 7.0) : 1f;
        Ui.rect(ms, x + 1, y + 1, tag, H - 2, Theme.mix(Theme.PANEL, c, 0.55f * blink));
        Ui.textCenter(ms, "CNN", x + 1 + tag / 2, y + 6, Theme.alpha(0xFFFFFFFF, 0xC0));
        Ui.textCenter(ms, NewsState.label(en.item.cat), x + 1 + tag / 2, y + 18, 0xFFFFFFFF);
        // titlu + autor
        int tx = x + tag + 10;
        Ui.textShadow(ms, Ui.clip(en.item.title, w - tag - 18), tx, y + 7, Theme.TEXT);
        Ui.text(ms, Ui.clip("de " + en.item.author, w - tag - 18), tx, y + 19, Theme.MUTED);
        // luciu care trece peste banner
        int shineW = 22;
        int sx = x + 1 + (int) (((t * 0.45) % 1.0) * (w - 2 + shineW)) - shineW;
        int from = Math.max(x + 1, sx);
        int to = Math.min(x + w - 1, sx + shineW);
        if (to > from) Ui.rect(ms, from, y + 1, to - from, H - 2, Theme.alpha(0xFFFFFFFF, 0x14));
        // bara de timp
        float left = age < IN ? 1f : Math.max(0f, 1f - (age - IN) / (float) en.dur);
        Ui.rect(ms, x + 3, y + H - 3, Math.round((w - 6) * left), 1, c);
    }
}
