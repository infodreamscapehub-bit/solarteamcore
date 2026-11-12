package ro.solarstudios.flowcity.notify;

import net.minecraft.client.gui.GuiGraphics;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;

/** Istoricul notificarilor (cele mai noi sus), cu scroll si buton de stergere. */
public final class NotifyScreen extends FlowScreen {
    private int scroll;

    public NotifyScreen() {
        super("Notificari", 340, 230);
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        header(ms, "Notificari", NotifyState.history.size() + " in istoric", mx, my);
        int x = px + 8;
        int top = py + HEAD + 8;
        int w = pw - 16;
        int bottom = py + ph - 26;
        List<NotifyState.Item> items = NotifyState.history;
        if (items.isEmpty()) {
            Ui.textCenter(ms, "Nicio notificare.", px + pw / 2, py + ph / 2 - 4, Theme.MUTED);
        }
        int rowH = 30;
        int visible = Math.max(1, (bottom - top) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - visible)));
        int y = top;
        for (int i = scroll; i < Math.min(items.size(), scroll + visible); i++) {
            NotifyState.Item it = items.get(i);
            int c = NotifyState.color(it.type);
            Ui.frame(ms, x, y, w, rowH - 3, Theme.PANEL_2, Theme.LINE);
            Ui.rect(ms, x + 1, y + 1, 3, rowH - 5, c);
            Ui.round(ms, x + 9, y + 4, 18, 18, Theme.mix(Theme.PANEL, c, 0.28f));
            Ui.textCenter(ms, NotifyState.icon(it.type), x + 18, y + 9, c);
            Ui.textShadow(ms, Ui.clip(it.title, w - 130), x + 33, y + 4, Theme.TEXT);
            List<String> l = Wrap.lines(it.text, w - 44);
            if (!l.isEmpty()) Ui.text(ms, Ui.clip(l.get(0) + (l.size() > 1 ? "..." : ""), w - 44), x + 33, y + 14, Theme.MUTED);
            Ui.textRight(ms, Ui.time(it.ts), x + w - 6, y + 4, Theme.FAINT);
            if (!it.read) Ui.rect(ms, x + w - 10, y + 15, 4, 4, c);
            y += rowH;
        }
        if (items.size() > visible) {
            int th = Math.max(12, (bottom - top) * visible / items.size());
            int ty = top + (bottom - top - th) * scroll / Math.max(1, items.size() - visible);
            Ui.rect(ms, px + pw - 5, top, 1, bottom - top, Theme.LINE);
            Ui.rect(ms, px + pw - 6, ty, 3, th, Theme.ACCENT_DIM);
        }
        button(ms, "Sterge tot", px + 8, py + ph - 20, 70, 14, mx, my, !items.isEmpty(), 0xFF6B2F2B, NotifyClient::clearAll);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) Math.signum(delta);
        return true;
    }
}
