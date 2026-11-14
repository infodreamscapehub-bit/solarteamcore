package ro.solarstudios.flowcity.notify;

import java.util.List;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Istoricul notificarilor (cele mai noi sus): panou rotunjit, randuri care intra pe rand, scroll si buton de stergere. */
public final class NotifyScreen extends FlowScreen {
    private static final int ROW = 38;
    private int scroll;
    private final long born = System.currentTimeMillis();

    public NotifyScreen() {
        super("Notificari", 380, 250);
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

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = easeOut((now - born) / 300f);
        Sk.background(ms, 0, 0, width, height, open);
        Gfx.alpha = open;
        try {
            content(ms, mx, my, now);
        } finally {
            Gfx.alpha = 1f;
        }
    }

    private void content(GuiGraphics ms, int mx, int my, long now) {
        List<NotifyState.Item> items = NotifyState.history;

        // antet
        Gfx.text(ms, "NOTIFICĂRI", px + 16, py + 36, 17, Sk.WHITE, 0, true, 0f);
        int unread = 0;
        for (NotifyState.Item it : items) if (!it.read) unread++;
        String sub = items.size() + " în istoric" + (unread > 0 ? "  ·  " + unread + " necitite" : "");
        Gfx.text(ms, sub.toUpperCase(), px + 17, py + 48, 6.4f, Sk.MUTED, 0, false, 1.2f);

        Sk.close(ms, px + pw - 34, py + 14, mx, my);
        hit(px + pw - 34, py + 14, 20, 20, this::onClose);

        // sterge tot
        int bw = 84;
        int bx = px + pw - 34 - 10 - bw;
        int by = py + 14;
        boolean can = !items.isEmpty();
        boolean hb = can && Ui.in(mx, my, bx, by, bw, 20);
        Sk.button(ms, "ȘTERGE TOT", bx, by, bw, 20, can, false, hb);
        if (can) hit(bx, by, bw, 20, NotifyClient::clearAll);

        // lista
        int x = px + 12;
        int w = pw - 36;
        int top = py + 62;
        int bottom = py + ph - 12;
        int visible = Math.max(1, (bottom - top) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) {
            Gfx.text(ms, "Nicio notificare.", px + pw / 2f, py + ph / 2f + 14, 9, Sk.MUTED, 1);
            NotifyUi.icon(ms, "INFO", px + pw / 2f, py + ph / 2f - 10, 13, Sk.ACC, 0.8f);
        }
        int y = top;
        for (int i = scroll; i < Math.min(items.size(), scroll + visible); i++) {
            NotifyState.Item it = items.get(i);
            int idx = i - scroll;
            float a = easeOut((now - born - idx * 45L) / 260f);
            float slide = (1f - a) * 14f;
            int c = NotifyState.color(it.type) | 0xFF000000;
            float rx = x + slide;
            boolean hov = Ui.in(mx, my, x, y, w, ROW - 5);
            Sk.slant(ms, rx, y, w, ROW - 5, 7f, Gfx.fade(hov ? Sk.ROW_HOT : Sk.ROW, a));
            Gfx.rrect(ms, rx + 9, y + 6, 3, ROW - 17, 0, Gfx.fade(c, a));
            NotifyUi.icon(ms, it.type, rx + 28, y + (ROW - 5) / 2f, 10f, c, a);
            Gfx.text(ms, NotifyUi.clip(it.title, 8.8f, w - 150), rx + 48, y + 14, 8.8f, Gfx.fade(Sk.WHITE, a), 0);
            List<String> l = NotifyUi.wrap(it.text, 7.6f, w - 70, 1);
            if (!l.isEmpty()) Gfx.text(ms, l.get(0), rx + 48, y + 26, 7.6f, Gfx.fade(Sk.MUTED, a), 0);
            Gfx.text(ms, Ui.time(it.ts), rx + w - 18, y + 14, 6.8f, Gfx.fade(Sk.FAINT, a), 2);
            if (!it.read) Gfx.rrect(ms, rx + w - 22, y + 21, 5, 5, 0, Gfx.fade(c, a));
            y += ROW;
        }
        if (items.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(16f, trackH * visible / items.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, items.size() - visible);
            Gfx.rrect(ms, px + pw - 14, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, px + pw - 14, ty, 2, th, 0, Sk.ACC);
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
