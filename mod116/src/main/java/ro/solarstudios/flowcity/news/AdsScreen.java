package ro.solarstudios.flowcity.news;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.phone.PhoneClient;
import ro.solarstudios.flowcity.phone.PhoneScreen;

/**
 * CNN > Anunturi: lista cu cautare/filtre/sortare, formular de publicare cu confirmarea platii, anunturile mele (stergere, prelungire)
 * si moderare pentru staff (aproba, respinge, sterge, suspenda, istoric). Clientul doar afiseaza si cere operatii: taxele, cooldown-ul,
 * limitele si numarul de telefon sunt hotarate si verificate pe server.
 */
public final class AdsScreen extends FlowScreen {
    private enum View { BROWSE, CREATE, MINE, MOD, LOG }

    private static final int LIST_W = 200;
    private static final int TOP = 76;
    private final long openedAt = System.currentTimeMillis();
    private long lastRefresh = openedAt;

    private View view = View.BROWSE;
    private String filterCat = "";
    private int sort; // 0 recente, 1 pret crescator, 2 pret descrescator
    private long selected = -1;
    private int listScroll;
    private int bodyScroll;

    // formular
    private String cCat = "";
    private int cDur = -1;
    private boolean cPromo;
    private boolean confirming;
    private long confirmDelete = -1;

    private final TextFieldWidget searchField = field("Cauta...", 40, null);
    private final TextFieldWidget titleField = field("Titlul anuntului", 48, null);
    private final TextFieldWidget bodyField = field("Descriere (ce, unde, detalii)", 240, null);
    private final TextFieldWidget priceField = field("Pret ($)", 11, null);
    private final TextFieldWidget reasonField = field("Motiv (optional)", 80, null);

    public AdsScreen() {
        super("Anunturi", 470, 262);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected void init() {
        AdsClient.requestInfo();
        AdsClient.requestList();
        if (AdsState.isMod) AdsClient.requestHistory();
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        if (confirming) return l;
        if (view == View.BROWSE) l.add(searchField);
        else if (view == View.CREATE) {
            l.add(titleField);
            l.add(bodyField);
            if (AdsState.catHasPrice(cCat)) l.add(priceField);
        } else if (view == View.MOD) l.add(reasonField);
        return l;
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (now - lastRefresh > 15_000L) {
            lastRefresh = now;
            AdsClient.requestInfo();
            AdsClient.requestList();
            if (AdsState.isMod && (view == View.LOG || view == View.MOD)) AdsClient.requestHistory();
        }
    }

    // ------------------------------------------------------------------ utilitare desen

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

    private static int pillW(String label) {
        return Math.round(Gfx.width(label, 6.6f, true)) + 14;
    }

    private void h(int x, int y, int w, int hh, Runnable r) {
        if (!confirming) hit(x, y, w, hh, r);
    }

    private int pill(GuiGraphics ms, String label, int x, int y, int w, int hh, int mx, int my, boolean active, int color, Runnable r) {
        boolean hot = !confirming && Sk.in(mx, my, x, y, w, hh);
        Sk.slant(ms, x, y, w, hh, 4f, active ? color : (hot ? Sk.ROW_HOT : Sk.ROW));
        Gfx.text(ms, label, x + w / 2f + 1, y + hh / 2f + 2.6f, 6.6f, active ? 0xFF061426 : (hot ? Sk.WHITE : Sk.MUTED), 1, true, 0.4f);
        h(x, y, w, hh, r);
        return w;
    }

    private void btn(GuiGraphics ms, String label, int x, int y, int w, int hh, int mx, int my, boolean enabled, boolean filled, Runnable r) {
        boolean hot = enabled && !confirming && Sk.in(mx, my, x, y, w, hh);
        Sk.button(ms, label, x, y, w, hh, enabled, filled, hot);
        if (enabled) h(x, y, w, hh, r);
    }

    private static String left(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 86_400) return (s / 86_400) + "z " + ((s % 86_400) / 3600) + "h";
        if (s >= 3600) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }

    private static String priceText(AdsState.Ad a) {
        return a.price < 0 ? "-" : Ui.money(a.price);
    }

    private static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) sb.append(s.charAt(i));
        return sb.toString();
    }

    private long cooldownLeft() {
        long base = cPromo && AdsState.promotedEnabled ? AdsState.cooldownPromoMs : AdsState.cooldownMs;
        return Math.max(0, base - (System.currentTimeMillis() - AdsState.infoAt));
    }

    private long suspendedLeft() {
        return Math.max(0, AdsState.suspendedMs - (System.currentTimeMillis() - AdsState.infoAt));
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = ease((now - openedAt) / 300f);
        Sk.background(ms, 0, 0, width, height, open);
        Gfx.alpha = open;
        try {
            drawBar(ms, mx, my);
            switch (view) {
                case BROWSE: drawBrowse(ms, mx, my, now); break;
                case CREATE: drawCreate(ms, mx, my); break;
                case MINE: drawMine(ms, mx, my, now); break;
                case MOD: drawMod(ms, mx, my, now); break;
                default: drawLog(ms); break;
            }
            drawMsg(ms);
            if (confirming) drawConfirm(ms, mx, my);
        } finally {
            Gfx.alpha = 1f;
        }
    }

    private void drawBar(GuiGraphics ms, int mx, int my) {
        Gfx.text(ms, "ANUNȚURI", px + 16, py + 32, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOWCITY CNN", px + 17, py + 44, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, px + pw - 34, py + 12, mx, my);
        hit(px + pw - 34, py + 12, 20, 20, this::onClose);
        String sold = "SOLD " + Ui.money(AdsState.balance);
        Gfx.text(ms, sold, px + pw - 44, py + 25, 7.4f, 0xFF4CD39A, 2, true, 0.4f);

        int x = px + 130;
        int y = py + 12;
        x += pill(ms, "ȘTIRI", x, y, pillW("ȘTIRI"), 16, mx, my, false, Sk.ACC, () -> {
            NewsClient.requestList();
            Minecraft.getInstance().setScreen(new NewsScreen());
        }) + 4;
        x += pill(ms, "ANUNȚURI", x, y, pillW("ANUNȚURI"), 16, mx, my, true, Sk.ACC, () -> {}) + 4;

        int tx = px + 12;
        int ty = py + 50;
        tx += tab(ms, "TOATE", View.BROWSE, tx, ty, mx, my) + 3;
        tx += tab(ms, "+ PUBLICĂ", View.CREATE, tx, ty, mx, my) + 3;
        tx += tab(ms, "ALE MELE", View.MINE, tx, ty, mx, my) + 3;
        if (AdsState.isMod) {
            String mod = "MODERARE" + (AdsState.pending.isEmpty() ? "" : " (" + AdsState.pending.size() + ")");
            tx += tab(ms, mod, View.MOD, tx, ty, mx, my) + 3;
            tab(ms, "ISTORIC", View.LOG, tx, ty, mx, my);
        }
    }

    private int tab(GuiGraphics ms, String label, View v, int x, int y, int mx, int my) {
        int w = pillW(label);
        pill(ms, label, x, y, w, 16, mx, my, view == v, Sk.ACC, () -> {
            view = v;
            listScroll = 0;
            bodyScroll = 0;
            selected = -1;
            confirmDelete = -1;
            if (v == View.CREATE && cCat.isEmpty() && !AdsState.cats.isEmpty()) cCat = AdsState.cats.get(0).id;
            if (v == View.LOG || v == View.MOD) AdsClient.requestHistory();
            AdsClient.requestInfo();
            AdsClient.requestList();
        });
        return w;
    }

    private void drawMsg(GuiGraphics ms) {
        long age = System.currentTimeMillis() - AdsState.lastMsgAt;
        if (AdsState.lastMsg.isEmpty() || age > 6000) return;
        int w = Math.round(Gfx.width(AdsState.lastMsg, 7.4f, false)) + 24;
        w = Math.min(w, pw - 24);
        int x = px + (pw - w) / 2;
        int y = py + ph - 24;
        Sk.slant(ms, x, y, w, 16, 5f, 0xF0081018);
        Gfx.rrect(ms, x + 6, y + 4, 3, 8, 0, AdsState.lastOk ? Sk.GOOD : Sk.BAD);
        Gfx.text(ms, clipTo(AdsState.lastMsg, 7.4f, false, w - 24), x + 14, y + 11, 7.4f, Sk.WHITE, 0);
    }

    // ------------------------------------------------------------------ lista + detaliu

    private List<AdsState.Ad> filtered() {
        List<AdsState.Ad> l = new ArrayList<>();
        String q = searchField.getValue().trim().toLowerCase(Locale.ROOT);
        for (AdsState.Ad a : AdsState.items) {
            if (!filterCat.isEmpty() && !filterCat.equals(a.cat)) continue;
            if (!q.isEmpty() && !(a.title.toLowerCase(Locale.ROOT).contains(q) || a.body.toLowerCase(Locale.ROOT).contains(q)
                || a.author.toLowerCase(Locale.ROOT).contains(q))) continue;
            l.add(a);
        }
        final int s = sort;
        l.sort((a, b) -> {
            if (a.promoted != b.promoted) return a.promoted ? -1 : 1;
            if (s == 1 || s == 2) {
                long pa = a.price < 0 ? (s == 1 ? Long.MAX_VALUE : -1) : a.price;
                long pb = b.price < 0 ? (s == 1 ? Long.MAX_VALUE : -1) : b.price;
                int c = s == 1 ? Long.compare(pa, pb) : Long.compare(pb, pa);
                if (c != 0) return c;
            }
            return Long.compare(b.created, a.created);
        });
        return l;
    }

    private AdsState.Ad find(List<AdsState.Ad> l) {
        for (AdsState.Ad a : l) if (a.id == selected) return a;
        return l.isEmpty() ? null : l.get(0);
    }

    private void drawBrowse(GuiGraphics ms, int mx, int my, long now) {
        int x = px + 12;
        int y = py + TOP;
        drawField(ms, searchField, x, y, LIST_W, 16, mx, my);
        y += 22;
        int fx = x;
        fx += pill(ms, "TOATE", fx, y, pillW("TOATE"), 14, mx, my, filterCat.isEmpty(), Sk.ACC, () -> filterCat = "") + 3;
        for (AdsState.Cat c : AdsState.cats) {
            String lab = c.label.toUpperCase(Locale.ROOT);
            int cw = pillW(lab);
            if (fx + cw > x + LIST_W) {
                fx = x;
                y += 17;
            }
            final String id = c.id;
            fx += pill(ms, lab, fx, y, cw, 14, mx, my, id.equals(filterCat), AdsState.catColor(id), () -> {
                filterCat = id;
                listScroll = 0;
            }) + 3;
        }
        y += 18;
        int sx = x;
        String[] names = {"RECENTE", "PRET +", "PRET -"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            sx += pill(ms, names[i], sx, y, pillW(names[i]), 13, mx, my, sort == i, 0xFF9AA7BD, () -> sort = k) + 3;
        }
        y += 18;
        List<AdsState.Ad> items = filtered();
        drawAdList(ms, items, x, y, py + ph - 14, mx, my, now, "Niciun anunt.");
        Gfx.rrect(ms, px + LIST_W + 18, py + TOP, 1, ph - TOP - 10, 0, 0xFF1B2839);
        drawDetail(ms, find(items), px + LIST_W + 28, pw - LIST_W - 46, mx, my, true);
    }

    private void drawAdList(GuiGraphics ms, List<AdsState.Ad> items, int x, int top, int bottom, int mx, int my, long now, String empty) {
        int rowH = 28;
        int visible = Math.max(1, (bottom - top) / rowH);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) Gfx.text(ms, empty, x + 4, top + 10, 8, Sk.MUTED, 0);
        AdsState.Ad cur = find(items);
        int y = top;
        for (int i = listScroll; i < Math.min(items.size(), listScroll + visible); i++) {
            AdsState.Ad a = items.get(i);
            boolean sel = cur != null && cur.id == a.id;
            boolean hover = !confirming && Sk.in(mx, my, x, y, LIST_W, rowH - 3);
            float in = ease((now - openedAt - (i - listScroll) * 40L) / 240f);
            float ox = (1f - in) * -12f;
            Sk.slant(ms, x + ox, y, LIST_W, rowH - 3, 6f, sel || hover ? Sk.ROW_HOT : Sk.ROW);
            int cc = a.promoted ? 0xFFF5C451 : AdsState.catColor(a.cat);
            Gfx.rrect(ms, x + ox + 9, y + 5, 3, rowH - 13, 0, cc);
            String lab = (a.promoted ? "* " : "") + AdsState.catLabel(a.cat).toUpperCase(Locale.ROOT);
            Gfx.text(ms, lab, x + 18 + ox, y + 10, 6.2f, cc, 0, true, 0.6f);
            Gfx.text(ms, priceText(a), x + LIST_W - 10 + ox, y + 10, 6.8f, 0xFF4CD39A, 2, true, 0.2f);
            Gfx.text(ms, clipTo(a.title, 7.8f, false, LIST_W - 34), x + 18 + ox, y + 21, 7.8f, sel ? Sk.WHITE : 0xFFC9D6E8, 0);
            final long id = a.id;
            h(x, y, LIST_W, rowH - 3, () -> {
                selected = id;
                bodyScroll = 0;
                confirmDelete = -1;
            });
            y += rowH;
        }
        if (items.size() > visible) {
            float trackH = bottom - top;
            float th = Math.max(14f, trackH * visible / items.size());
            float ty = top + (trackH - th) * listScroll / Math.max(1, items.size() - visible);
            Gfx.rrect(ms, x + LIST_W + 6, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, x + LIST_W + 6, ty, 2, th, 0, Sk.ACC);
        }
    }

    /** Detaliul unui anunt; jos, butoanele de contact (si de moderare pentru staff). Returneaza y-ul de jos al zonei de text. */
    private void drawDetail(GuiGraphics ms, AdsState.Ad a, int x, int w, int mx, int my, boolean contact) {
        int y = py + TOP;
        if (a == null) {
            Gfx.text(ms, "Niciun anunt de afisat.", x + w / 2f, py + ph / 2f, 9, Sk.MUTED, 1);
            return;
        }
        int c = AdsState.catColor(a.cat);
        String lab = AdsState.catLabel(a.cat).toUpperCase(Locale.ROOT);
        int bw = pillW(lab);
        Sk.slant(ms, x, y, bw, 14, 4f, c);
        Gfx.text(ms, lab, x + bw / 2f + 1, y + 9.6f, 6.6f, 0xFF061426, 1, true, 0.4f);
        int tx = x + bw + 6;
        if (a.promoted) {
            Sk.slant(ms, tx, y, 64, 14, 4f, 0xFFF5C451);
            Gfx.text(ms, "PROMOVAT", tx + 33, y + 9.6f, 6.2f, 0xFF061426, 1, true, 0.4f);
        }
        Gfx.text(ms, AdsState.statusLabel(a.status), x + w, y + 10, 6.6f, AdsState.statusColor(a.status), 2, true, 0.5f);
        y += 30;
        for (String l : wrap(a.title, 12f, true, w)) {
            Gfx.text(ms, l, x, y, 12f, Sk.WHITE, 0, true, 0f);
            y += 15;
        }
        if (a.price >= 0) {
            Gfx.text(ms, Ui.money(a.price), x, y + 3, 11f, 0xFF4CD39A, 0, true, 0.3f);
            y += 16;
        }
        Gfx.rrect(ms, x, y, 36, 2, 0, c);
        Gfx.rrect(ms, x + 36, y, w - 36, 1, 0, 0xFF1B2839);
        y += 12;
        boolean mod = AdsState.isMod && view != View.MINE;
        int footer = contact ? (mod ? 56 : 34) : (view == View.MINE ? 34 : 14);
        if (view == View.MOD) footer = 80;
        int bottom = py + ph - footer;
        // coloana de metadate (autor, telefon, date)
        int metaY = bottom - 46;
        List<String> lines = wrap(a.body.isEmpty() ? "(fara descriere)" : a.body, 7.8f, false, w - 8);
        int lh = 11;
        int visible = Math.max(1, (metaY - 4 - y) / lh);
        bodyScroll = Math.max(0, Math.min(bodyScroll, Math.max(0, lines.size() - visible)));
        for (int i = bodyScroll; i < Math.min(lines.size(), bodyScroll + visible); i++) {
            Gfx.text(ms, lines.get(i), x, y, 7.8f, 0xFFC9D6E8, 0);
            y += lh;
        }
        if (lines.size() > visible) {
            int th = visible * lh;
            Gfx.rrect(ms, x + w - 2, py + TOP + 60, 2, th, 0, 0xFF14202F);
        }
        Gfx.rrect(ms, x, metaY - 2, w, 1, 0, 0xFF1B2839);
        Gfx.text(ms, "Autor", x, metaY + 9, 6.6f, Sk.FAINT, 0);
        Gfx.text(ms, a.author, x + 44, metaY + 9, 7.4f, Sk.WHITE, 0);
        Gfx.text(ms, "Telefon", x, metaY + 21, 6.6f, Sk.FAINT, 0);
        boolean hasPhone = !a.phone.isEmpty();
        boolean overPhone = hasPhone && !a.own && !confirming && Sk.in(mx, my, x + 44, metaY + 12, 90, 11);
        Gfx.text(ms, hasPhone ? a.phone : "-", x + 44, metaY + 21, 7.4f, overPhone ? Sk.WHITE : Sk.ACC, 0, hasPhone, 0.2f);
        if (hasPhone && !a.own) h(x + 44, metaY + 12, 90, 11, () -> PhoneScreen.openThreadWith(a.uuid, a.author));
        Gfx.text(ms, "Publicat", x, metaY + 33, 6.6f, Sk.FAINT, 0);
        Gfx.text(ms, Ui.time(a.created), x + 44, metaY + 33, 7.2f, Sk.MUTED, 0);
        Gfx.text(ms, "Expira", x + w / 2f + 8, metaY + 33, 6.6f, Sk.FAINT, 0);
        Gfx.text(ms, "ACTIVE".equals(a.status) ? Ui.time(a.expires) + " (" + left(a.expires - System.currentTimeMillis()) + ")" : Ui.time(a.expires),
            x + w / 2f + 44, metaY + 33, 7.2f, Sk.MUTED, 0);
        if (!a.reason.isEmpty() && ("REJECTED".equals(a.status) || "DELETED".equals(a.status))) {
            Gfx.text(ms, "Motiv: " + clipTo(a.reason, 7.2f, false, w - 50), x + w / 2f + 8, metaY + 21, 7.2f, Sk.BAD, 0);
        }
        int by = py + ph - 28;
        if (contact) {
            if (a.own) {
                Gfx.text(ms, "Acesta este anuntul tau.", x, by + 13, 7.6f, Sk.MUTED, 0);
            } else {
                final String num = digits(a.phone);
                btn(ms, "SUNĂ", x, by, 70, 20, mx, my, hasPhone, true, () -> PhoneClient.callQuery(num));
                btn(ms, "TRIMITE SMS", x + 76, by, 96, 20, mx, my, hasPhone, false, () -> PhoneScreen.openThreadWith(a.uuid, a.author));
            }
            if (mod) {
                btn(ms, "ȘTERGE (MOD)", x + w - 100, by, 100, 20, mx, my, true, false, () -> AdsClient.mod(a.id, "delete", ""));
                int sy = by - 24;
                Gfx.text(ms, "Suspenda autorul:", x, sy + 11, 6.6f, Sk.FAINT, 0);
                int px2 = x + 74;
                px2 += pill(ms, "1H", px2, sy + 2, 28, 14, mx, my, false, Sk.BAD, () -> AdsClient.suspend(a.id, 60, "")) + 3;
                px2 += pill(ms, "24H", px2, sy + 2, 32, 14, mx, my, false, Sk.BAD, () -> AdsClient.suspend(a.id, 1440, "")) + 3;
                px2 += pill(ms, "7Z", px2, sy + 2, 28, 14, mx, my, false, Sk.BAD, () -> AdsClient.suspend(a.id, 10080, "")) + 3;
                pill(ms, "RIDICĂ", px2, sy + 2, 44, 14, mx, my, false, Sk.GOOD, () -> AdsClient.suspend(a.id, 0, ""));
            }
        }
    }

    // ------------------------------------------------------------------ publicare

    private long priceValue() {
        String d = digits(priceField.getValue());
        if (d.isEmpty() || d.length() > 10) return -1;
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Motivul pentru care formularul nu poate fi trimis (doar pentru UX; serverul valideaza din nou) sau null. */
    private String formProblem() {
        if (!AdsState.enabled) return "Anunturile sunt dezactivate.";
        if (suspendedLeft() > 0) return "Publicarea ta e suspendata inca " + left(suspendedLeft()) + ".";
        if (AdsState.requireSim && AdsState.phone.isEmpty()) return "Ai nevoie de o cartela SIM activa in telefon.";
        if (cCat.isEmpty()) return "Alege o categorie.";
        String t = titleField.getValue().trim();
        if (t.length() < AdsState.titleMin) return "Titlul e prea scurt (min " + AdsState.titleMin + ").";
        String b = bodyField.getValue().trim();
        if (b.length() < AdsState.descMin) return "Descrierea e prea scurta (min " + AdsState.descMin + ").";
        if (AdsState.catHasPrice(cCat)) {
            long p = priceValue();
            if (p < 1 || p > AdsState.maxPrice) return "Introdu un pret valid.";
        }
        if (cDur <= 0) return "Alege durata.";
        if (AdsState.active >= AdsState.maxActive) return "Ai deja " + AdsState.active + "/" + AdsState.maxActive + " anunturi active.";
        if (cooldownLeft() > 0) return "Cooldown: " + left(cooldownLeft());
        double fee = fee();
        if (AdsState.balance + 1e-9 < fee) return "Fonduri insuficiente pentru taxa.";
        return null;
    }

    private double fee() {
        return cPromo && AdsState.promotedEnabled ? AdsState.feePromoted : AdsState.feeStandard;
    }

    private void drawCreate(GuiGraphics ms, int mx, int my) {
        if (cCat.isEmpty() && !AdsState.cats.isEmpty()) cCat = AdsState.cats.get(0).id;
        if (cDur <= 0 && !AdsState.durations.isEmpty()) cDur = AdsState.durations.contains(24) ? 24 : AdsState.durations.get(0);
        int x = px + 12;
        int w = 250;
        int y = py + TOP;
        Gfx.text(ms, "CATEGORIE", x, y + 4, 6.2f, Sk.FAINT, 0, false, 1f);
        y += 8;
        int fx = x;
        for (AdsState.Cat c : AdsState.cats) {
            String lab = c.label.toUpperCase(Locale.ROOT);
            int cw = pillW(lab);
            if (fx + cw > x + w) {
                fx = x;
                y += 17;
            }
            final String id = c.id;
            fx += pill(ms, lab, fx, y, cw, 14, mx, my, id.equals(cCat), AdsState.catColor(id), () -> cCat = id) + 3;
        }
        y += 22;
        Gfx.text(ms, "TITLU", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        drawField(ms, titleField, x, y + 4, w, 16, mx, my);
        y += 28;
        Gfx.text(ms, "DESCRIERE", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        drawField(ms, bodyField, x, y + 4, w, 16, mx, my);
        y += 28;
        if (AdsState.catHasPrice(cCat)) {
            Gfx.text(ms, "PRET ($)", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
            drawField(ms, priceField, x, y + 4, 110, 16, mx, my);
            y += 28;
        }
        Gfx.text(ms, "DURATA", x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        int dx = x;
        for (int d : AdsState.durations) {
            String lab = d + "H";
            dx += pill(ms, lab, dx, y + 4, pillW(lab), 14, mx, my, d == cDur, Sk.ACC, () -> cDur = d) + 3;
        }
        if (AdsState.promotedEnabled) {
            String lab = cPromo ? "PROMOVAT: DA" : "PROMOVAT: NU";
            pill(ms, lab, x + w - pillW("PROMOVAT: DA") - 2, y + 4, pillW("PROMOVAT: DA"), 14, mx, my, cPromo, 0xFFF5C451, () -> cPromo = !cPromo);
        }

        // rezumat
        int rx = px + 12 + w + 16;
        int rw = pw - w - 52;
        int ry = py + TOP;
        Sk.slant(ms, rx, ry, rw, 140, 8f, Sk.ROW);
        Gfx.text(ms, "REZUMAT", rx + 12, ry + 16, 7f, Sk.MUTED, 0, true, 1f);
        row(ms, "Taxa", Ui.money(fee()), rx + 12, ry + 30, rw - 24, Sk.WHITE);
        row(ms, "Soldul tau", Ui.money(AdsState.balance), rx + 12, ry + 44, rw - 24, AdsState.balance + 1e-9 >= fee() ? 0xFF4CD39A : Sk.BAD);
        row(ms, "Dupa plata", Ui.money(Math.max(0, AdsState.balance - fee())), rx + 12, ry + 58, rw - 24, Sk.MUTED);
        long cd = cooldownLeft();
        row(ms, "Cooldown", cd > 0 ? left(cd) : "disponibil", rx + 12, ry + 72, rw - 24, cd > 0 ? Sk.BAD : 0xFF4CD39A);
        row(ms, "Active", AdsState.active + " / " + AdsState.maxActive, rx + 12, ry + 86, rw - 24, Sk.WHITE);
        row(ms, "Telefon", AdsState.phone.isEmpty() ? "FARA SIM" : AdsState.phone, rx + 12, ry + 100, rw - 24, AdsState.phone.isEmpty() ? Sk.BAD : Sk.WHITE);
        Gfx.text(ms, "Numarul vine din cartela SIM.", rx + 12, ry + 124, 6.2f, Sk.FAINT, 0);
        String problem = formProblem();
        btn(ms, "PUBLICĂ", rx, ry + 150, rw, 22, mx, my, problem == null, true, () -> confirming = true);
        if (problem != null) {
            int ty = ry + 178;
            for (String l : wrap(problem, 6.8f, false, rw)) {
                Gfx.text(ms, l, rx, ty, 6.8f, Sk.BAD, 0);
                ty += 9;
            }
        }
    }

    private void row(GuiGraphics ms, String label, String value, int x, int y, int w, int color) {
        Gfx.text(ms, label, x, y, 7f, Sk.FAINT, 0);
        Gfx.text(ms, value, x + w, y, 7.4f, color, 2, true, 0.2f);
    }

    private void drawConfirm(GuiGraphics ms, int mx, int my) {
        Ui.rect(ms, px, py, pw, ph, 0xB0050A12);
        int w = 250;
        int hh = 126;
        int x = px + (pw - w) / 2;
        int y = py + (ph - hh) / 2;
        Sk.slant(ms, x, y, w, hh, 10f, 0xFF0C1624);
        Gfx.rrect(ms, x, y, w, 2, 0, Sk.ACC);
        Gfx.text(ms, "CONFIRMI PUBLICAREA?", x + w / 2f, y + 22, 10, Sk.WHITE, 1, true, 0.6f);
        Gfx.text(ms, clipTo(titleField.getValue().trim(), 7.8f, false, w - 24), x + w / 2f, y + 36, 7.8f, Sk.MUTED, 1);
        row(ms, "Taxa de publicare", Ui.money(fee()), x + 16, y + 54, w - 32, Sk.WHITE);
        row(ms, "Soldul tau", Ui.money(AdsState.balance), x + 16, y + 68, w - 32, Sk.WHITE);
        row(ms, "Sold dupa plata", Ui.money(Math.max(0, AdsState.balance - fee())), x + 16, y + 82, w - 32, 0xFF4CD39A);
        modalDraw(() -> {
            Sk.button(ms, "CONFIRMĂ ȘI PLĂTEȘTE", x + 16, y + 98, 140, 20, true, true, Sk.in(mx, my, x + 16, y + 98, 140, 20));
            hit(x + 16, y + 98, 140, 20, this::doPublish);
            Sk.button(ms, "ANULEAZĂ", x + 162, y + 98, 72, 20, true, false, Sk.in(mx, my, x + 162, y + 98, 72, 20));
            hit(x + 162, y + 98, 72, 20, () -> confirming = false);
        });
    }

    private void modalDraw(Runnable r) {
        r.run();
    }

    private void doPublish() {
        confirming = false;
        if (formProblem() != null) return;
        AdsClient.publish(cCat, titleField.getValue().trim(), bodyField.getValue().trim(),
            AdsState.catHasPrice(cCat) ? priceValue() : -1, cDur, cPromo && AdsState.promotedEnabled);
        titleField.setValue("");
        bodyField.setValue("");
        priceField.setValue("");
        cPromo = false;
        view = View.MINE;
        selected = -1;
    }

    // ------------------------------------------------------------------ anunturile mele

    private void drawMine(GuiGraphics ms, int mx, int my, long now) {
        int x = px + 12;
        Gfx.text(ms, "ANUNȚURILE MELE", x, py + TOP + 6, 7f, Sk.MUTED, 0, true, 1f);
        List<AdsState.Ad> items = AdsState.own;
        drawAdList(ms, items, x, py + TOP + 14, py + ph - 14, mx, my, now, "Nu ai niciun anunt.");
        Gfx.rrect(ms, px + LIST_W + 18, py + TOP, 1, ph - TOP - 10, 0, 0xFF1B2839);
        int dx = px + LIST_W + 28;
        int dw = pw - LIST_W - 46;
        AdsState.Ad a = find(items);
        drawDetail(ms, a, dx, dw, mx, my, false);
        if (a == null) return;
        int by = py + ph - 28;
        final long id = a.id;
        boolean canDelete = !"DELETED".equals(a.status);
        boolean sure = confirmDelete == id;
        btn(ms, sure ? "CONFIRMĂ ȘTERGEREA" : "ȘTERGE", dx, by, sure ? 130 : 80, 20, mx, my, canDelete, false, () -> {
            if (confirmDelete == id) {
                AdsClient.delete(id);
                confirmDelete = -1;
            } else {
                confirmDelete = id;
            }
        });
        boolean canExtend = AdsState.extendAllowed && a.extendsLeft > 0 && ("ACTIVE".equals(a.status) || "EXPIRED".equals(a.status));
        String lab = "PRELUNGEȘTE " + Ui.money(AdsState.feeExtend);
        int ew = pillW(lab) + 40;
        btn(ms, lab, dx + dw - ew, by, ew, 20, mx, my, canExtend, true, () -> AdsClient.extend(id));
        if (a.extendsLeft > 0 && canExtend) Gfx.text(ms, a.extendsLeft + " prelungiri ramase", dx + dw - ew, by - 4, 6.2f, Sk.FAINT, 0);
    }

    // ------------------------------------------------------------------ moderare

    private void drawMod(GuiGraphics ms, int mx, int my, long now) {
        int x = px + 12;
        Gfx.text(ms, "ÎN AȘTEPTARE", x, py + TOP + 6, 7f, Sk.MUTED, 0, true, 1f);
        List<AdsState.Ad> items = AdsState.pending;
        drawAdList(ms, items, x, py + TOP + 14, py + ph - 14, mx, my, now, "Nimic de aprobat.");
        Gfx.rrect(ms, px + LIST_W + 18, py + TOP, 1, ph - TOP - 10, 0, 0xFF1B2839);
        int dx = px + LIST_W + 28;
        int dw = pw - LIST_W - 46;
        AdsState.Ad a = find(items);
        drawDetail(ms, a, dx, dw, mx, my, false);
        if (a == null) return;
        final long id = a.id;
        int by = py + ph - 28;
        drawField(ms, reasonField, dx, by - 26, dw, 16, mx, my);
        btn(ms, "APROBĂ", dx, by, 80, 20, mx, my, true, true, () -> AdsClient.mod(id, "approve", ""));
        btn(ms, "RESPINGE", dx + 86, by, 80, 20, mx, my, true, false, () -> {
            AdsClient.mod(id, "reject", reasonField.getValue().trim());
            reasonField.setValue("");
        });
        btn(ms, "ȘTERGE", dx + 172, by, 70, 20, mx, my, true, false, () -> AdsClient.mod(id, "delete", reasonField.getValue().trim()));
        int sx = dx + 248;
        Gfx.text(ms, "Suspenda:", sx, by + 6, 6.2f, Sk.FAINT, 0);
        pill(ms, "24H", sx, by + 9, 30, 12, mx, my, false, Sk.BAD, () -> AdsClient.suspend(id, 1440, reasonField.getValue().trim()));
    }

    private void drawLog(GuiGraphics ms) {
        int x = px + 16;
        int y = py + TOP + 6;
        Gfx.text(ms, "ISTORIC MODERARE / ACTIVITATE", x, y, 7f, Sk.MUTED, 0, true, 1f);
        y += 14;
        if (AdsState.history.isEmpty()) Gfx.text(ms, "Nicio activitate.", x, y + 8, 8, Sk.MUTED, 0);
        int rowH = 14;
        int visible = Math.max(1, (py + ph - 14 - y) / rowH);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, AdsState.history.size() - visible)));
        for (int i = listScroll; i < Math.min(AdsState.history.size(), listScroll + visible); i++) {
            AdsState.Log l = AdsState.history.get(i);
            Gfx.text(ms, Ui.time(l.ts), x, y + 9, 6.6f, Sk.FAINT, 0);
            Gfx.text(ms, l.action, x + 78, y + 9, 6.8f, l.action.contains("REJECT") || l.action.contains("DELETE") || l.action.contains("SUSPEND") ? Sk.BAD : 0xFF4CD39A, 0, true, 0.2f);
            Gfx.text(ms, l.actor + "  #" + l.id, x + 150, y + 9, 6.8f, Sk.WHITE, 0);
            Gfx.text(ms, clipTo(l.detail, 6.8f, false, pw - 280), x + 250, y + 9, 6.8f, Sk.MUTED, 0);
            y += rowH;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = -(int) Math.signum(delta);
        if (view == View.LOG || mx < px + LIST_W + 16) listScroll += d;
        else bodyScroll += d;
        return true;
    }
}
