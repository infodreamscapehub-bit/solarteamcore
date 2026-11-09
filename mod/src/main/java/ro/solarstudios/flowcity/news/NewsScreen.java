package ro.solarstudios.flowcity.news;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;

/** CNN: lista de stiri (stanga), articolul (dreapta); staff-ul poate publica si sterge. */
public final class NewsScreen extends FlowScreen {
    private static final int LIST_W = 150;

    private long selected = -1;
    private int listScroll;
    private int bodyScroll;
    private boolean composing;
    private String cat = "";
    private final TextFieldWidget titleField = field("Titlul stirii", 80, null);
    private final TextFieldWidget bodyField = field("Textul stirii (optional)", 600, null);

    public NewsScreen() {
        super("CNN", 440, 250);
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

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        header(ms, "CNN", "stirile orasului", mx, my);
        drawList(ms, mx, my);
        Ui.rect(ms, px + LIST_W + 12, py + HEAD + 6, 1, ph - HEAD - 12, Theme.LINE);
        int rx = px + LIST_W + 20;
        int rw = pw - LIST_W - 28;
        if (composing) drawCompose(ms, mx, my, rx, rw);
        else drawArticle(ms, mx, my, rx, rw);
    }

    private void drawList(MatrixStack ms, int mx, int my) {
        int x = px + 8;
        int top = py + HEAD + 8;
        int bottom = py + ph - (NewsState.canPublish ? 28 : 10);
        int rowH = 24;
        int visible = Math.max(1, (bottom - top) / rowH);
        List<NewsState.Item> items = NewsState.items;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) Ui.text(ms, "Nicio stire.", x + 4, top + 4, Theme.MUTED);
        NewsState.Item cur = current();
        int y = top;
        for (int i = listScroll; i < Math.min(items.size(), listScroll + visible); i++) {
            NewsState.Item it = items.get(i);
            boolean sel = cur != null && cur.id == it.id && !composing;
            boolean hover = Ui.in(mx, my, x, y, LIST_W, rowH - 2);
            int c = NewsState.color(it.cat);
            Ui.round(ms, x, y, LIST_W, rowH - 2, sel ? Theme.mix(Theme.PANEL, c, 0.25f) : (hover ? Theme.PANEL_3 : Theme.PANEL_2));
            Ui.rect(ms, x, y + 2, 2, rowH - 6, c);
            Ui.text(ms, NewsState.label(it.cat), x + 7, y + 3, c);
            Ui.textRight(ms, Ui.time(it.ts), x + LIST_W - 4, y + 3, Theme.FAINT);
            Ui.text(ms, Ui.clip(it.title, LIST_W - 12), x + 7, y + 13, Theme.TEXT);
            final long id = it.id;
            hit(x, y, LIST_W, rowH - 2, () -> {
                selected = id;
                bodyScroll = 0;
                composing = false;
            });
            y += rowH;
        }
        if (NewsState.canPublish) {
            button(ms, composing ? "Anuleaza" : "+ Publica stire", x, py + ph - 22, LIST_W, 14, mx, my, true,
                composing ? 0xFF5B2A35 : Theme.ACCENT_DIM, () -> {
                    composing = !composing;
                    if (composing && cat.isEmpty() && !NewsState.cats.isEmpty()) cat = NewsState.cats.get(0);
                });
        }
    }

    private void drawArticle(MatrixStack ms, int mx, int my, int x, int w) {
        NewsState.Item it = current();
        int y = py + HEAD + 10;
        if (it == null) {
            Ui.textCenter(ms, "Nicio stire de afisat.", x + w / 2, py + ph / 2 - 4, Theme.MUTED);
            return;
        }
        int c = NewsState.color(it.cat);
        tag(ms, NewsState.label(it.cat), x, y, c);
        Ui.textRight(ms, "de " + it.author + "  -  " + Ui.time(it.ts), x + w, y + 2, Theme.FAINT);
        y += 18;
        int ty = y;
        for (String l : Wrap.lines(it.title, w)) {
            Ui.textShadow(ms, l, x, ty, Theme.TEXT);
            ty += 11;
        }
        y = ty + 6;
        Ui.rect(ms, x, y, w, 1, Theme.LINE);
        y += 7;
        int bottom = py + ph - (NewsState.canDelete ? 30 : 12);
        List<String> lines = Wrap.lines(it.body.isEmpty() ? "(fara text suplimentar)" : it.body, w);
        int visible = Math.max(1, (bottom - y) / 10);
        bodyScroll = Math.max(0, Math.min(bodyScroll, Math.max(0, lines.size() - visible)));
        for (int i = bodyScroll; i < Math.min(lines.size(), bodyScroll + visible); i++) {
            Ui.text(ms, lines.get(i), x, y, it.body.isEmpty() ? Theme.FAINT : Theme.MUTED);
            y += 10;
        }
        if (NewsState.canDelete) {
            final long id = it.id;
            button(ms, "Sterge stirea", x + w - 84, py + ph - 22, 84, 14, mx, my, true, 0xFF5B2A35, () -> NewsClient.delete(id));
        }
    }

    private void drawCompose(MatrixStack ms, int mx, int my, int x, int w) {
        int y = py + HEAD + 10;
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
