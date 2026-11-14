package ro.solarstudios.flowcity.news;

import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.notify.NotifyUi;

/** Banner CNN (tema de iarna FlowCity) sus, la mijloc: coboara animat, eticheta pulseaza (breaking), luciu care trece, bara de timp. Stirile vin pe rand. */
public final class NewsBanner {
    private static final long IN = 450;
    private static final long OUT = 400;

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

    private static int lum(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    private boolean failed;
    private static boolean broken;

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
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

    private void doRender(RenderGameOverlayEvent.Post e) {
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
        float inT = Math.min(1f, age / (float) IN);
        float outT = age > IN + en.dur ? (age - IN - en.dur) / (float) OUT : 0f;
        float u = inT - 1f;
        float back = 1f + 2.70158f * u * u * u + 1.70158f * u * u; // revenire usoara la intrare
        float slide = Math.max(0f, back) * (1f - outT * outT);
        GuiGraphics g = new GuiGraphics(e.getMatrixStack());
        int sw = mc.getWindow().getGuiScaledWidth();
        float w = Math.min(372f, sw - 20f);
        float h = 54f;
        float x = (sw - w) / 2f;
        float y = -h - 6f + slide * (h + 16f);
        int c = NewsState.color(en.item.cat) | 0xFF000000;
        double t = now / 1000.0;
        boolean breaking = "breaking".equals(en.item.cat);
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * (breaking ? 7.0 : 2.5));
        float a = 1f - outT;

        if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(g, "news", x, 6f, w, h)) return;
        // umbra, cadru colorat (accent de categorie), fundal navy cu colturi taiate
        Sk.cut(g, x - 1, y + 3, w + 2, h + 2, 11, Gfx.fade(0x66000000, a));
        Sk.cut(g, x - 1, y - 1, w + 2, h + 2, 11, Gfx.fade(Gfx.mixArgb(0xFF22344D, c, breaking ? 0.35f + 0.4f * pulse : 0.45f), a));
        Sk.cut(g, x, y, w, h, 10, Gfx.fade(0xF2081018, a));
        Gfx.rrect(g, x + 14, y, w - 28, 2, 0, Gfx.fade(Sk.ACC, a * 0.85f));

        // eticheta categoriei (breaking pulseaza)
        float tw = 74f;
        int tagCol = Gfx.mixArgb(c, 0xFFFFFFFF, breaking ? 0.10f * pulse : 0f);
        int tagFg = lum(tagCol) > 140 ? 0xFF061426 : 0xFFF2F7FF;
        if (breaking) Sk.slant(g, x + 4 - 2 * pulse, y + 5 - 2 * pulse, tw + 4 * pulse, h - 10 + 4 * pulse, 9f, Gfx.fade(Gfx.fade(c, 0.28f), a));
        Sk.slant(g, x + 6, y + 6, tw, h - 12, 9f, Gfx.fade(tagCol, a));
        Gfx.disc(g, x + 6 + 18, y + 19, 2.4f, Gfx.fade(tagFg, a * (breaking ? 0.5f + 0.5f * pulse : 1f)));
        Gfx.text(g, "CNN", x + 6 + 25, y + 22, 8.5f, Gfx.fade(tagFg, a), 0, true, 1.2f);
        Gfx.text(g, NotifyUi.clip(NewsState.label(en.item.cat).toUpperCase(), 7.2f, tw - 22), x + 6 + tw / 2f + 3, y + h - 15, 7.2f, Gfx.fade(tagFg, a * 0.9f), 1, false, 0.8f);

        // titlu, rezumat, autor
        float tx = x + tw + 20f;
        float mw = w - tw - 34f;
        Gfx.text(g, NotifyUi.clip(en.item.title, 10f, mw), tx, y + 20, 10f, Gfx.fade(Sk.WHITE, a), 0, true, 0f);
        java.util.List<String> body = NotifyUi.wrap(en.item.body, 7.8f, mw, 1);
        if (!body.isEmpty()) Gfx.text(g, body.get(0), tx, y + 32, 7.8f, Gfx.fade(Sk.MUTED, a), 0);
        Gfx.text(g, NotifyUi.clip("de " + en.item.author, 6.8f, mw), tx, y + (body.isEmpty() ? 32 : 43), 6.8f, Gfx.fade(Sk.FAINT, a), 0);

        // luciu care trece peste banner
        float p = (float) ((t * 0.45) % 1.0);
        float sx = x + 8 + p * (w - 16);
        float x0 = Math.max(x + 8, sx - 6);
        float x1 = Math.min(x + w - 8, sx + 10);
        if (x1 > x0) Gfx.poly(g, new float[] {x1, y + 6, Math.min(x + w - 8, x1 + 8), y + 6, x1, y + h - 6, x0, y + h - 6}, Gfx.fade(0x12FFFFFF, a));

        // bara de timp
        float left = age < IN ? 1f : Math.max(0f, 1f - (age - IN) / (float) en.dur);
        Gfx.bar(g, tx, y + h - 7, mw, 2f, left, Gfx.fade(0xFF1B2839, a), Gfx.fade(c, a * 0.9f));
        ro.solarstudios.flowcity.client.ui.HudLayout.end(g);
    }
}
