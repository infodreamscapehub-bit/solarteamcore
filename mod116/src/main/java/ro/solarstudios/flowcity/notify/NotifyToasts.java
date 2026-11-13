package ro.solarstudios.flowcity.notify;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Toast-uri desenate neted in dreapta-sus: intra cu o usoara revenire, insigna cu pictograma face "pop" si un val, o
 * raza de lumina trece peste card, bara de timp se goleste, apoi iese glisand. Cardurile de dedesubt se muta lin cand
 * unul dispare. Maxim 5 odata.
 */
public final class NotifyToasts {
    private static final float W = 208f;
    private static final long IN = 420;
    private static final long HOLD = 5200;
    private static final long OUT = 320;
    private static final float TEXT_X = 46f;

    private static final class Toast {
        final String type;
        final String title;
        final List<String> lines;
        final long at = System.currentTimeMillis();
        final float h;
        float y = Float.NaN;

        Toast(String type, String title, List<String> lines) {
            this.type = type;
            this.title = title;
            this.lines = lines;
            this.h = 38f + 9f * lines.size();
        }
    }

    private static final List<Toast> TOASTS = new ArrayList<>();
    private static long lastNs = System.nanoTime();

    public static void add(String type, String title, String text) {
        List<String> ls = NotifyUi.wrap(text, 7.6f, W - TEXT_X - 12, 2);
        TOASTS.add(new Toast(type, NotifyUi.clip(title, 8.8f, W - TEXT_X - 12), ls));
        while (TOASTS.size() > 5) TOASTS.remove(0);
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

    private static float easeOutBack(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
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
            TOASTS.clear();
        }
    }

    private void doRender(RenderGameOverlayEvent.Post e) {
        if (TOASTS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        GuiGraphics g = new GuiGraphics(e.getMatrixStack());
        int sw = mc.getWindow().getGuiScaledWidth();
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float slotY = Math.max(8, ro.solarstudios.flowcity.hype.HypeHud.occupiedBottom);
        boolean vis = ro.solarstudios.flowcity.client.ui.HudLayout.begin(g, "toasts", sw - 10f - W, slotY, W, 64f);
        Iterator<Toast> it = TOASTS.iterator();
        while (it.hasNext()) {
            Toast t = it.next();
            long age = now - t.at;
            if (age > IN + HOLD + OUT) {
                it.remove();
                continue;
            }
            if (Float.isNaN(t.y)) t.y = slotY;
            else t.y += (slotY - t.y) * Math.min(1f, dt * 12f);
            slotY += t.h + 6f;
            if (vis) draw(g, t, age, sw);
        }
        if (vis) ro.solarstudios.flowcity.client.ui.HudLayout.end(g);
    }

    private void draw(GuiGraphics g, Toast t, long age, int sw) {
        int c = NotifyState.color(t.type) | 0xFF000000;
        float in = Math.min(1f, age / (float) IN);
        float outT = age > IN + HOLD ? (age - IN - HOLD) / (float) OUT : 0f;
        float off = (1f - easeOutBack(in)) * (W + 16f) + outT * outT * (W + 16f);
        float a = Math.min(1f, age / 160f) * (1f - outT);
        float x = sw - 10f - W + off;
        float y = t.y;
        float h = t.h;

        // umbra, cadru subtire colorat, fundal cu gradient
        Gfx.rrect(g, x - 1, y + 2, W + 2, h + 2, 13, Gfx.fade(0x55000000, a));
        Gfx.rrect(g, x, y, W, h, 12, Gfx.fade(Gfx.mixArgb(0xFF2A2E36, c, 0.38f), a));
        Gfx.rrect(g, x + 1, y + 1, W - 2, h - 2, 11, Gfx.fade(0xFF22252C, a), Gfx.fade(0xFF14161A, a));
        Gfx.scrim(g, x + 27, y + h / 2f, 30, h / 2f - 3, Gfx.fade(Gfx.fade(c, 0.16f), a));

        // raza de lumina care trece o data peste card
        if (age < 800) {
            float p = easeOut(age / 800f);
            float sx = x + 8 + (W - 30) * p;
            float x0 = Math.max(x + 8, sx);
            float x1 = Math.min(x + W - 8, sx + 14);
            if (x1 > x0) {
                float sk = 6f;
                float l0 = Math.max(x + 8, x0 - sk);
                float l1 = Math.min(x + W - 8, x1 - sk);
                Gfx.poly(g, new float[] {x0, y + 5, x1, y + 5, Math.max(l1, l0), y + h - 5, l0, y + h - 5}, Gfx.fade(0x14FFFFFF, a * (1f - p)));
            }
        }

        // insigna cu pop + val
        float ix = x + 25f;
        float iy = y + h / 2f - 1f;
        float pop = age < 420 ? 0.55f + 0.45f * easeOutBack(age / 420f) : 1f;
        if (age < 800) {
            float rp = easeOut(age / 800f);
            Gfx.ring(g, ix, iy, 12 + rp * 12, 13.6f + rp * 12, Gfx.fade(c, a * (1f - rp) * 0.55f), Gfx.fade(c, 0f));
        }
        NotifyUi.icon(g, t.type, ix, iy, 11f * pop, c, a);

        // text
        Gfx.text(g, t.title, x + TEXT_X, y + 17, 8.8f, Gfx.fade(0xFFF4F1EA, a), 0);
        float ty = y + 28;
        for (String l : t.lines) {
            Gfx.text(g, l, x + TEXT_X, ty, 7.6f, Gfx.fade(0xFFA19F98, a), 0);
            ty += 9;
        }

        // bara de timp
        float left = age < IN ? 1f : Math.max(0f, 1f - (age - IN) / (float) HOLD);
        Gfx.bar(g, x + 14, y + h - 7, W - 28, 2f, left, Gfx.fade(0x22FFFFFF, a), Gfx.fade(c, a * 0.85f));
    }
}
