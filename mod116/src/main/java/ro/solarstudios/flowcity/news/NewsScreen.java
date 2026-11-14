package ro.solarstudios.flowcity.news;

import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Stiri (tema de iarna FlowCity): lista de stiri (stanga), articolul (dreapta); staff-ul poate publica si sterge. */
public final class NewsScreen extends FlowScreen {
    private static final int LIST_W = 156;
    private static final int TOP = 70;
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
        super("Stiri", 470, 262);
    }

    @Override
    protected boolean framed() {
        return false;
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
        if (titleField.getValue().trim().length() < 3) {
            toast("Titlul e prea scurt.", true);
            return;
        }
        NewsClient.publish(c, titleField.getValue().trim(), bodyField.getValue().trim());
        titleField.setValue("");
        bodyField.setValue("");
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

    private static List<String> wrap(String text, float size, boolean xl, float maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String t = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && Gfx.width(t, size, xl) > maxW) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(t);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String clipTo(String s, float size, boolean xl, float maxW) {
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    /** Pastila inclinata cu text; returneaza latimea. */
    private int pill(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my, boolean active, int color, Runnable r) {
        boolean hot = Sk.in(mx, my, x, y, w, h);
        Sk.slant(ms, x, y, w, h, 4f, active ? color : (hot ? Sk.ROW_HOT : Sk.ROW));
        Gfx.text(ms, label, x + w / 2f + 1, y + h / 2f + 2.6f, 6.6f, active ? 0xFF061426 : (hot ? Sk.WHITE : Sk.MUTED), 1, true, 0.4f);
        hit(x, y, w, h, r);
        return w;
    }

    private static int pillW(String label) {
        return Math.round(Gfx.width(label, 6.6f, true)) + 14;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = ease((now - openedAt) / 300f);
        Sk.background(ms, 0, 0, width, height, open);
        Gfx.alpha = open;
        try {
            drawBar(ms, mx, my, now);
            float in = ease((now - openedAt) / 380f);
            int slideX = Math.round((1f - in) * 18f);
            drawList(ms, mx, my, now);
            Gfx.rrect(ms, px + LIST_W + 18, py + TOP, 1, ph - TOP - 10, 0, 0xFF1B2839);
            int rx = px + LIST_W + 28 + slideX;
            int rw = pw - LIST_W - 46;
            if (composing) drawCompose(ms, mx, my, rx, rw);
            else drawArticle(ms, mx, my, rx, rw);
        } finally {
            Gfx.alpha = 1f;
        }
    }

    /** Antet: titlu, buton de inchidere si ticker cu titlurile care ruleaza (LIVE clipitor). */
    private void drawBar(GuiGraphics ms, int mx, int my, long now) {
        Gfx.text(ms, "ȘTIRI", px + 16, py + 32, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOWCITY NEWS", px + 17, py + 44, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, px + pw - 34, py + 12, mx, my);
        hit(px + pw - 34, py + 12, 20, 20, this::onClose);

        int ty = py + 12;
        int tx = px + 128;
        int tw = pw - 128 - 46;
        Sk.slant(ms, tx, ty, tw + 8, 20, 6f, Sk.ROW);
        boolean blink = (now / 500) % 2 == 0;
        Gfx.rrect(ms, tx + 14, ty + 8, 5, 5, 0, blink ? Sk.BAD : Gfx.fade(Sk.BAD, 0.35f));
        Gfx.text(ms, "LIVE", tx + 24, ty + 13.5f, 6.6f, Sk.WHITE, 0, true, 0.6f);
        StringBuilder sb = new StringBuilder();
        for (NewsState.Item it : NewsState.items) sb.append(NewsState.label(it.cat)).append(": ").append(it.title).append("     +++     ");
        String text = sb.length() == 0 ? "Nicio stire momentan" : sb.toString();
        int cx0 = tx + 56;
        int cw = tw - 56;
        int full = Math.max(1, Ui.width(text));
        int off = (int) ((now / 28) % full);
        Ui.scissor(cx0, ty, cw, 20);
        try {
            Ui.text(ms, text, cx0 - off, ty + 6, Sk.MUTED);
            if (full - off < cw) Ui.text(ms, text, cx0 - off + full, ty + 6, Sk.MUTED);
        } finally {
            Ui.endScissor();
        }
    }

    private List<NewsState.Item> filtered() {
        if (filter.isEmpty()) return NewsState.items;
        List<NewsState.Item> l = new ArrayList<>();
        for (NewsState.Item it : NewsState.items) if (filter.equals(it.cat)) l.add(it);
        return l;
    }

    private void drawList(GuiGraphics ms, int mx, int my, long now) {
        int x = px + 12;
        int fx = x;
        int fy = py + TOP - 2;
        fx += pill(ms, "TOATE", fx, fy, pillW("TOATE"), 14, mx, my, filter.isEmpty(), Sk.ACC, () -> filter = "") + 3;
        for (String c : NewsState.cats) {
            String lab = NewsState.label(c).toUpperCase();
            int cw = pillW(lab);
            if (fx + cw > x + LIST_W) break;
            final String cc = c;
            fx += pill(ms, lab, fx, fy, cw, 14, mx, my, c.equals(filter), NewsState.color(c) | 0xFF000000, () -> filter = cc) + 3;
        }
        int top = py + TOP + 18;
        int bottom = py + ph - (NewsState.canPublish ? 32 : 10);
        int rowH = 30;
        int visible = Math.max(1, (bottom - top) / rowH);
        List<NewsState.Item> items = filtered();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) Gfx.text(ms, "Nicio stire.", x + 4, top + 10, 8, Sk.MUTED, 0);
        NewsState.Item cur = current();
        int y = top;
        for (int i = listScroll; i < Math.min(items.size(), listScroll + visible); i++) {
            NewsState.Item it = items.get(i);
            boolean sel = cur != null && cur.id == it.id && !composing;
            boolean hover = Sk.in(mx, my, x, y, LIST_W, rowH - 3);
            int c = NewsState.color(it.cat) | 0xFF000000;
            float rowIn = ease((now - openedAt - (i - listScroll) * 45L) / 260f);
            if (rowIn <= 0f) {
                y += rowH;
                continue;
            }
            float ox = (1f - rowIn) * -14f;
            Sk.slant(ms, x + ox, y, LIST_W, rowH - 3, 6f, sel ? Sk.ROW_HOT : (hover ? Sk.ROW_HOT : Sk.ROW));
            Gfx.rrect(ms, x + ox + 9, y + 5, 3, rowH - 13, 0, c);
            String lab = NewsState.label(it.cat).toUpperCase();
            Gfx.text(ms, lab, x + 18 + ox, y + 10, 6.2f, c, 0, true, 0.6f);
            if (now - it.ts < 600_000L && it.ts > 0) {
                float lw = Gfx.width(lab, 6.2f, true) + 6;
                Gfx.rrect(ms, x + 18 + ox + lw, y + 3, 22, 9, 0, Sk.BAD);
                Gfx.text(ms, "NOU", x + 18 + ox + lw + 11, y + 10, 5.6f, Sk.WHITE, 1, true, 0.4f);
            }
            Gfx.text(ms, ago(it.ts), x + LIST_W - 12 + ox, y + 10, 6.2f, Sk.FAINT, 2);
            Gfx.text(ms, clipTo(it.title, 7.8f, false, LIST_W - 34), x + 18 + ox, y + 22, 7.8f, sel ? Sk.WHITE : 0xFFC9D6E8, 0);
            final long id = it.id;
            hit(x, y, LIST_W, rowH - 3, () -> {
                selected = id;
                bodyScroll = 0;
                composing = false;
            });
            y += rowH;
        }
        if (NewsState.canPublish) {
            int bx = x;
            int by = py + ph - 28;
            boolean hb = Sk.in(mx, my, bx, by, LIST_W, 20);
            Sk.button(ms, composing ? "ANULEAZĂ" : "+ PUBLICĂ ȘTIRE", bx, by, LIST_W, 20, true, !composing, hb);
            hit(bx, by, LIST_W, 20, () -> {
                composing = !composing;
                if (composing && cat.isEmpty() && !NewsState.cats.isEmpty()) cat = NewsState.cats.get(0);
            });
        }
    }

    private void drawArticle(GuiGraphics ms, int mx, int my, int x, int w) {
        NewsState.Item it = current();
        int y = py + TOP;
        if (it == null) {
            Gfx.text(ms, "Nicio stire de afisat.", x + w / 2f, py + ph / 2f, 9, Sk.MUTED, 1);
            return;
        }
        int c = NewsState.color(it.cat) | 0xFF000000;
        String lab = NewsState.label(it.cat).toUpperCase();
        int bw = Math.round(Gfx.width(lab, 6.6f, true)) + 14;
        Sk.slant(ms, x, y, bw, 14, 4f, c);
        Gfx.text(ms, lab, x + bw / 2f + 1, y + 9.6f, 6.6f, 0xFF061426, 1, true, 0.4f);
        Gfx.text(ms, "de " + it.author + "  ·  " + Ui.time(it.ts), x + w, y + 10, 6.4f, Sk.FAINT, 2);
        y += 32;
        for (String l : wrap(it.title, 12f, true, w)) {
            Gfx.text(ms, l, x, y, 12f, Sk.WHITE, 0, true, 0f);
            y += 15;
        }
        y += 2;
        Gfx.rrect(ms, x, y, 36, 2, 0, c);
        Gfx.rrect(ms, x + 36, y, w - 36, 1, 0, 0xFF1B2839);
        y += 14;
        int bottom = py + ph - (NewsState.canDelete ? 34 : 14);
        List<String> lines = wrap(it.body.isEmpty() ? "(fara text suplimentar)" : it.body, 7.8f, false, w - 8);
        int lh = 11;
        int visible = Math.max(1, (bottom - y) / lh);
        bodyScroll = Math.max(0, Math.min(bodyScroll, Math.max(0, lines.size() - visible)));
        int trackTop = y - 8;
        for (int i = bodyScroll; i < Math.min(lines.size(), bodyScroll + visible); i++) {
            Gfx.text(ms, lines.get(i), x, y, 7.8f, it.body.isEmpty() ? Sk.FAINT : 0xFFC9D6E8, 0);
            y += lh;
        }
        if (lines.size() > visible) {
            int trackH = visible * lh;
            Gfx.rrect(ms, x + w - 2, trackTop, 2, trackH, 0, 0xFF14202F);
            int thumb = Math.max(10, trackH * visible / lines.size());
            int thumbY = trackTop + (trackH - thumb) * bodyScroll / Math.max(1, lines.size() - visible);
            Gfx.rrect(ms, x + w - 2, thumbY, 2, thumb, 0, Sk.ACC);
        }
        if (NewsState.canDelete) {
            final long id = it.id;
            int dx = x + w - 104;
            int dy = py + ph - 28;
            boolean hd = Sk.in(mx, my, dx, dy, 104, 20);
            Sk.button(ms, "ȘTERGE ȘTIREA", dx, dy, 104, 20, true, false, hd);
            hit(dx, dy, 104, 20, () -> NewsClient.delete(id));
        }
    }

    private void drawCompose(GuiGraphics ms, int mx, int my, int x, int w) {
        int y = py + TOP + 8;
        Gfx.text(ms, "PUBLICĂ O ȘTIRE", x, y, 10, Sk.WHITE, 0, true, 0f);
        y += 16;
        Gfx.text(ms, "CATEGORIE", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        y += 6;
        int cx = x;
        for (String c : NewsState.cats) {
            String lab = NewsState.label(c).toUpperCase();
            int cw = pillW(lab) + 4;
            final String cc = c;
            pill(ms, lab, cx, y, cw, 14, mx, my, c.equals(cat), NewsState.color(c) | 0xFF000000, () -> cat = cc);
            cx += cw + 4;
        }
        y += 28;
        Gfx.text(ms, "TITLU", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        drawField(ms, titleField, x, y + 6, w, 16, mx, my);
        y += 36;
        Gfx.text(ms, "TEXT", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        drawField(ms, bodyField, x, y + 6, w, 16, mx, my);
        y += 38;
        boolean hp = Sk.in(mx, my, x, y, 120, 20);
        Sk.button(ms, "PUBLICĂ ACUM", x, y, 120, 20, true, true, hp);
        hit(x, y, 120, 20, this::publish);
        Gfx.text(ms, "Apare ca banner la toti jucatorii.", x + 130, y + 13, 6.8f, Sk.FAINT, 0);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = -(int) Math.signum(delta);
        if (mx < px + LIST_W + 16) listScroll += d;
        else bodyScroll += d;
        return true;
    }
}
