package ro.solarstudios.flowcity.news;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;

/** CNN: lista de stiri (stanga), articolul (dreapta); staff-ul poate publica si sterge. */
public final class NewsScreen extends FlowScreen {
    private static final int LIST_W = 156;
    private static final int RED = 0xFFE0222D;
    private final long openedAt = System.currentTimeMillis();
    private String filter = "";

    private long selected = -1;
    private int listScroll;
    private int bodyScroll;
    private boolean composing;
    private String cat = "";
    private final TextFieldWidget titleField = field("Titlul stirii", 80, null);
    private final TextFieldWidget bodyField = field("Textul stirii (optional)", 600, null);

    public NewsScreen() {
        super("CNN", 470, 262);
    }

    @Override
    protected void init() {
        NewsClient.requestList();
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        if (composing) {
            l.add(titleField);
            l.add(bodyField);
        }
        return l;
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        if (f == titleField) focus(bodyField, true);
        else publish();
    }

    private void publish() {
        String c = cat.isEmpty() && !NewsState.cats.isEmpty() ? NewsState.cats.get(0) : cat;
        if (titleField.getText().trim().length() < 3) {
            toast("Titlul e prea scurt.", true);
            return;
        }
        NewsClient.publish(c, titleField.getText().trim(), bodyField.getText().trim());
        titleField.setText("");
        bodyField.setText("");
        composing = false;
    }

    private NewsState.Item current() {
        for (NewsState.Item it : NewsState.items) if (it.id == selected) return it;
        return NewsState.items.isEmpty() ? null : NewsState.items.get(0);
    }

    private static String ago(long ts) {
        long sec = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (ts <= 0) return "-";
        if (sec < 60) return sec + "s";
        if (sec < 3600) return (sec / 60) + "m";
        if (sec < 86400) return (sec / 3600) + "h";
        return (sec / 86400) + "z";
    }

    private static float ease(float t) {
        float k = 1f - Math.max(0f, Math.min(1f, t));
        return 1f - k * k * k;
    }

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        long now = System.currentTimeMillis();
        drawBar(ms, mx, my, now);
        float in = ease((now - openedAt) / 380f);
        int slideX = Math.round((1f - in) * 18f);
        drawList(ms, mx, my, now);
        Ui.rect(ms, px + LIST_W + 14, py + HEAD + 18, 1, ph - HEAD - 24, Theme.LINE);
        int rx = px + LIST_W + 22 + slideX;
        int rw = pw - LIST_W - 30;
        if (composing) drawCompose(ms, mx, my, rx, rw);
        else drawArticle(ms, mx, my, rx, rw, now);
    }

    /** Bara de sus: sigla CNN rosie, LIVE clipitor si ticker cu titlurile care ruleaza. */
    private void drawBar(MatrixStack ms, int mx, int my, long now) {
        Ui.rect(ms, px + 1, py + 1, pw - 2, HEAD - 1, Theme.PANEL_2);
        Ui.rect(ms, px + 1, py + HEAD, pw - 2, 1, RED);
        Ui.rect(ms, px + 1, py + 1, 44, HEAD - 1, RED);
        ms.push();
        ms.translate(px + 7, py + 6, 0);
        ms.scale(1.3f, 1.3f, 1f);
        Ui.textShadow(ms, "CNN", 0, 0, 0xFFFFFFFF);
        ms.pop();
        boolean blink = (now / 500) % 2 == 0;
        Ui.rect(ms, px + 52, py + 9, 5, 5, blink ? Theme.BAD : Theme.alpha(Theme.BAD, 0x55));
        Ui.text(ms, "LIVE", px + 61, py + 8, Theme.TEXT);
        // ticker
        int tx = px + 92;
        int tw = pw - 92 - 26;
        StringBuilder sb = new StringBuilder();
        for (NewsState.Item it : NewsState.items) sb.append(NewsState.label(it.cat)).append(": ").append(it.title).append("     +++     ");
        String text = sb.length() == 0 ? "Nicio stire momentan" : sb.toString();
        int full = Math.max(1, Ui.width(text));
        int off = (int) ((now / 28) % full);
        Minecraft mc = Minecraft.getInstance();
        double sc = mc.getMainWindow().getGuiScaleFactor();
        RenderSystem.enableScissor((int) (tx * sc), (int) (mc.getMainWindow().getFramebufferHeight() - (py + HEAD) * sc), (int) (tw * sc), (int) ((HEAD - 2) * sc));
        try {
            Ui.text(ms, text, tx - off, py + 8, Theme.MUTED);
            if (full - off < tw) Ui.text(ms, text, tx - off + full, py + 8, Theme.MUTED);
        } finally {
            RenderSystem.disableScissor();
        }
        button(ms, "X", px + pw - 18, py + 5, 12, 14, mx, my, true, 0xFF5B2A35, this::onClose);
    }

    private List<NewsState.Item> filtered() {
        if (filter.isEmpty()) return NewsState.items;
        List<NewsState.Item> l = new ArrayList<>();
        for (NewsState.Item it : NewsState.items) if (filter.equals(it.cat)) l.add(it);
        return l;
    }

    private void drawList(MatrixStack ms, int mx, int my, long now) {
        int x = px + 8;
        // filtre pe categorii
        int fx = x;
        int fy = py + HEAD + 7;
        fx += chip(ms, "TOATE", fx, fy, Ui.width("TOATE") + 10, 12, mx, my, filter.isEmpty(), Theme.ACCENT, () -> filter = "") + 3;
        for (String c : NewsState.cats) {
            int cw = Ui.width(NewsState.label(c)) + 10;
            if (fx + cw > x + LIST_W) break;
            final String cc = c;
            fx += chip(ms, NewsState.label(c), fx, fy, cw, 12, mx, my, c.equals(filter), NewsState.color(c), () -> filter = cc) + 3;
        }
        int top = py + HEAD + 24;
        int bottom = py + ph - (NewsState.canPublish ? 28 : 10);
        int rowH = 28;
        int visible = Math.max(1, (bottom - top) / rowH);
        List<NewsState.Item> items = filtered();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) Ui.text(ms, "Nicio stire.", x + 4, top + 4, Theme.MUTED);
        NewsState.Item cur = current();
        int y = top;
        for (int i = listScroll; i < Math.min(items.size(), listScroll + visible); i++) {
            NewsState.Item it = items.get(i);
            boolean sel = cur != null && cur.id == it.id && !composing;
            boolean hover = Ui.in(mx, my, x, y, LIST_W, rowH - 2);
            int c = NewsState.color(it.cat);
            // aparitie esalonata a randurilor
            float rowIn = ease((now - openedAt - (i - listScroll) * 45L) / 260f);
            if (rowIn <= 0f) {
                y += rowH;
                continue;
            }
            int ox = Math.round((1f - rowIn) * -14f);
            Ui.round(ms, x + ox, y, LIST_W, rowH - 2, sel ? Theme.mix(Theme.PANEL, c, 0.25f) : (hover ? Theme.PANEL_3 : Theme.PANEL_2));
            Ui.rect(ms, x + ox, y + 2, 2, rowH - 6, c);
            Ui.text(ms, NewsState.label(it.cat), x + 7 + ox, y + 4, c);
            if (now - it.ts < 600_000L && it.ts > 0) {
                int nw = Ui.width("NOU") + 8;
                Ui.round(ms, x + 12 + Ui.width(NewsState.label(it.cat)) + ox, y + 3, nw, 10, RED);
                Ui.text(ms, "NOU", x + 16 + Ui.width(NewsState.label(it.cat)) + ox, y + 4, 0xFFFFFFFF);
            }
            Ui.textRight(ms, ago(it.ts), x + LIST_W - 4 + ox, y + 4, Theme.FAINT);
            Ui.text(ms, Ui.clip(it.title, LIST_W - 14), x + 7 + ox, y + 15, Theme.TEXT);
            final long id = it.id;
            hit(x, y, LIST_W, rowH - 2, () -> {
                selected = id;
                bodyScroll = 0;
                composing = false;
            });
            y += rowH;
        }
        if (NewsState.canPublish) {
            button(ms, composing ? "Anuleaza" : "+ Publica stire", x, py + ph - 22, LIST_W, 15, mx, my, true,
                composing ? 0xFF5B2A35 : Theme.ACCENT_DIM, () -> {
                    composing = !composing;
                    if (composing && cat.isEmpty() && !NewsState.cats.isEmpty()) cat = NewsState.cats.get(0);
                });
        }
    }

    private void drawArticle(MatrixStack ms, int mx, int my, int x, int w, long now) {
        NewsState.Item it = current();
        int y = py + HEAD + 12;
        if (it == null) {
            Ui.textCenter(ms, "Nicio stire de afisat.", x + w / 2, py + ph / 2 - 4, Theme.MUTED);
            return;
        }
        int c = NewsState.color(it.cat);
        tag(ms, NewsState.label(it.cat), x, y, c);
        Ui.textRight(ms, "de " + it.author + "  -  " + Ui.time(it.ts), x + w, y + 2, Theme.FAINT);
        y += 18;
        int ty = y;
        float ts = 1.35f;
        for (String l : Wrap.lines(it.title, (int) (w / ts))) {
            ms.push();
            ms.translate(x, ty, 0);
            ms.scale(ts, ts, 1f);
            Ui.textShadow(ms, l, 0, 0, Theme.TEXT);
            ms.pop();
            ty += 13;
        }
        y = ty + 5;
        Ui.rect(ms, x, y, 36, 2, c);
        Ui.rect(ms, x + 36, y, w - 36, 1, Theme.LINE);
        y += 7;
        int bottom = py + ph - (NewsState.canDelete ? 30 : 12);
        List<String> lines = Wrap.lines(it.body.isEmpty() ? "(fara text suplimentar)" : it.body, w);
        int visible = Math.max(1, (bottom - y) / 10);
        bodyScroll = Math.max(0, Math.min(bodyScroll, Math.max(0, lines.size() - visible)));
        for (int i = bodyScroll; i < Math.min(lines.size(), bodyScroll + visible); i++) {
            Ui.text(ms, lines.get(i), x, y, it.body.isEmpty() ? Theme.FAINT : Theme.MUTED);
            y += 10;
        }
        if (lines.size() > visible) {
            int trackTop = y - visible * 10;
            int trackH = visible * 10;
            Ui.rect(ms, x + w + 3, trackTop, 2, trackH, Theme.PANEL_3);
            int thumb = Math.max(10, trackH * visible / lines.size());
            int thumbY = trackTop + (trackH - thumb) * bodyScroll / Math.max(1, lines.size() - visible);
            Ui.rect(ms, x + w + 3, thumbY, 2, thumb, Theme.ACCENT);
        }
        if (NewsState.canDelete) {
            final long id = it.id;
            button(ms, "Sterge stirea", x + w - 84, py + ph - 22, 84, 14, mx, my, true, 0xFF5B2A35, () -> NewsClient.delete(id));
        }
    }

    private void drawCompose(MatrixStack ms, int mx, int my, int x, int w) {
        int y = py + HEAD + 12;
        Ui.textShadow(ms, "Publica o stire", x, y, Theme.TEXT);
        y += 16;
        Ui.text(ms, "CATEGORIE", x, y, Theme.FAINT);
        y += 11;
        int cx = x;
        for (String c : NewsState.cats) {
            int cw = Ui.width(NewsState.label(c)) + 14;
            final String cc = c;
            chip(ms, NewsState.label(c), cx, y, cw, 14, mx, my, c.equals(cat), NewsState.color(c), () -> cat = cc);
            cx += cw + 4;
        }
        y += 24;
        Ui.text(ms, "TITLU", x, y, Theme.FAINT);
        drawField(ms, titleField, x, y + 10, w, 16, mx, my);
        y += 34;
        Ui.text(ms, "TEXT", x, y, Theme.FAINT);
        drawField(ms, bodyField, x, y + 10, w, 16, mx, my);
        y += 38;
        button(ms, "PUBLICA ACUM", x, y, 110, 16, mx, my, true, 0xFF4B4FA8, this::publish);
        Ui.text(ms, "Apare ca banner la toti jucatorii.", x + 118, y + 4, Theme.FAINT);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = -(int) Math.signum(delta);
        if (mx < px + LIST_W + 12) listScroll += d;
        else bodyScroll += d;
        return true;
    }
}
