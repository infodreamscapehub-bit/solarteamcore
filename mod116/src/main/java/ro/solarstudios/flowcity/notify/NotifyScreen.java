package ro.solarstudios.flowcity.notify;

import java.util.List;
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
        float open = easeOut((now - born) / 260f);
        List<NotifyState.Item> items = NotifyState.history;

        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0xB0050608, open));
        Gfx.rrect(ms, px - 5, py + 2, pw + 10, ph + 8, 20, Gfx.fade(0x55000000, open));
        Gfx.rrect(ms, px, py, pw, ph, 16, Gfx.fade(0xFF1D2026, open), Gfx.fade(0xFF121418, open));
        Gfx.rrect(ms, px + 0.5f, py + 0.5f, pw - 1, 1, 0, Gfx.fade(0x14FFFFFF, open));

        // antet
        Gfx.text(ms, "NOTIFICĂRI", px + 18, py + 30, 12, Gfx.fade(0xFFF4F1EA, open), 0, true, 0.7f);
        int unread = 0;
        for (NotifyState.Item it : items) if (!it.read) unread++;
        String sub = items.size() + (items.size() == 1 ? " în istoric" : " în istoric") + (unread > 0 ? "  ·  " + unread + " necitite" : "");
        Gfx.text(ms, sub, px + 18, py + 43, 7.5f, Gfx.fade(0xFF8E8C86, open), 0);

        // inchidere
        boolean hx = Ui.in(mx, my, px + pw - 30, py + 12, 18, 18);
        if (hx) Gfx.disc(ms, px + pw - 21, py + 21, 9, 0x22FFFFFF);
        int xc = hx ? 0xFFE2453F : 0xFF8E8C86;
        Gfx.line(ms, px + pw - 25, py + 17, px + pw - 17, py + 25, 1.6f, xc);
        Gfx.line(ms, px + pw - 17, py + 17, px + pw - 25, py + 25, 1.6f, xc);
        hit(px + pw - 30, py + 12, 18, 18, this::onClose);

        // sterge tot
        int bw = 74;
        int bx = px + pw - 30 - 8 - bw;
        int by = py + 13;
        boolean can = !items.isEmpty();
        boolean hb = can && Ui.in(mx, my, bx, by, bw, 18);
        Gfx.rrect(ms, bx, by, bw, 18, 9, Gfx.fade(can ? (hb ? 0xFF6B2F2B : 0xFF4A2522) : 0xFF24272D, open));
        Gfx.text(ms, "Șterge tot", bx + bw / 2f, by + 12, 7.5f, Gfx.fade(can ? 0xFFF4F1EA : 0xFF6F6D68, open), 1);
        if (can) hit(bx, by, bw, 18, NotifyClient::clearAll);

        // lista
        int x = px + 12;
        int w = pw - 24;
        int top = py + 56;
        int bottom = py + ph - 12;
        int visible = Math.max(1, (bottom - top) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) {
            Gfx.text(ms, "Nicio notificare.", px + pw / 2f, py + ph / 2f + 14, 9, Gfx.fade(0xFF8E8C86, open), 1);
            NotifyUi.icon(ms, "INFO", px + pw / 2f, py + ph / 2f - 10, 13, 0xFF5BA8FF, open * 0.8f);
        }
        int y = top;
        for (int i = scroll; i < Math.min(items.size(), scroll + visible); i++) {
            NotifyState.Item it = items.get(i);
            int idx = i - scroll;
            float a = open * easeOut((now - born - idx * 45L) / 260f);
            float slide = (1f - easeOut((now - born - idx * 45L) / 260f)) * 14f;
            int c = NotifyState.color(it.type) | 0xFF000000;
            float rx = x + slide;
            boolean hov = Ui.in(mx, my, x, y, w, ROW - 5);
            Gfx.rrect(ms, rx, y, w, ROW - 5, 10, Gfx.fade(hov ? 0xFF272B33 : 0xFF1B1E24, a));
            NotifyUi.icon(ms, it.type, rx + 20, y + (ROW - 5) / 2f, 10f, c, a);
            Gfx.text(ms, NotifyUi.clip(it.title, 8.8f, w - 150), rx + 40, y + 14, 8.8f, Gfx.fade(0xFFF4F1EA, a), 0);
            List<String> l = NotifyUi.wrap(it.text, 7.6f, w - 62, 1);
            if (!l.isEmpty()) Gfx.text(ms, l.get(0), rx + 40, y + 26, 7.6f, Gfx.fade(0xFFA19F98, a), 0);
            Gfx.text(ms, Ui.time(it.ts), rx + w - 12, y + 14, 6.8f, Gfx.fade(0xFF6F6D68, a), 2);
            if (!it.read) {
                Gfx.disc(ms, rx + w - 14, y + 25, 6, Gfx.fade(c, a * 0.4f), 0x00000000, 0, 0);
                Gfx.disc(ms, rx + w - 14, y + 25, 2.6f, Gfx.fade(c, a));
            }
            y += ROW;
        }
        if (items.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(16f, trackH * visible / items.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, items.size() - visible);
            Gfx.rrect(ms, px + pw - 7, top, 2, trackH, 1, Gfx.fade(0xFF262A31, open));
            Gfx.rrect(ms, px + pw - 7, ty, 2, th, 1, Gfx.fade(0xFFE9A23B, open));
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
