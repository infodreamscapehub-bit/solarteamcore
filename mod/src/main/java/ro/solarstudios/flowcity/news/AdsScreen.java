package ro.solarstudios.flowcity.news;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.EditBox;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.phone.PhoneClient;
import ro.solarstudios.flowcity.phone.PhoneScreen;

/**
 * CNN 2.0: platforma de anunturi a jucatorilor (fara stiri). Sidebar (Acasa, Anunturi, Categorii, Anunturile mele, Publica; staff:
 * Moderare, Toate anunturile, Istoric, Setari), carduri, cautare, filtre, pagina de detalii, formular cu tip standard/promovat si
 * pagina de confirmare a platii, editare/promovare/prelungire, panel admin. Clientul doar afiseaza si cere operatii: taxele,
 * cooldown-ul, limitele si numarul de telefon (SIM) sunt hotarate si verificate pe server (AdsModule).
 */
public final class AdsScreen extends FlowScreen {
    private enum View { HOME, BROWSE, CATS, DETAIL, CREATE, CONFIRM, MINE, MOD, ALL, LOG, CFG }

    private static final int RED = 0xFFE5232B;
    private static final int RED_DARK = 0xFF9E1419;
    private static final int BG = 0xF60D0E12;
    private static final int SIDE = 0xFF08080B;
    private static final int CARD = 0xFF15161C;
    private static final int CARD_HOT = 0xFF1E2028;
    private static final int LINE = 0xFF23252D;
    private static final int TXT = 0xFFF3F4F7;
    private static final int MUTED = 0xFF9CA0AD;
    private static final int FAINT = 0xFF5F6370;
    private static final int GREEN = 0xFF4CD39A;
    private static final int GOLD = 0xFFF5C451;
    private static final int BAD = 0xFFF0584C;
    private static final int SIDE_W = 108;

    private final long openedAt = System.currentTimeMillis();
    private long lastRefresh = openedAt;
    private long viewAt = openedAt;
    private View view = View.HOME;
    private View back = View.HOME;
    private long detailId = -1;
    private String filterCat = "";
    private int sort; // 0 recente, 1 pret crescator, 2 pret descrescator
    private int scroll;
    private boolean modal;
    private long confirmDelete = -1;

    // formular (publicare sau editare)
    private long editId = -1;
    private String cCat = "";
    private int cDur = -1;
    private boolean cPromo;

    private final EditBox searchField = field("Cauta anunturi, autori...", 40, s -> scroll = 0);
    private final EditBox titleField = field("Titlul anuntului", 48, null);
    private final EditBox bodyField = field("Descriere: ce oferi, unde, detalii", 240, null);
    private final EditBox priceField = field("Pret ($)", 11, null);
    private final EditBox reasonField = field("Motiv (optional)", 80, null);

    public AdsScreen() {
        super("CNN", 540, 300);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected void init() {
        AdsClient.requestInfo();
        AdsClient.requestList();
    }

    @Override
    protected List<EditBox> fields() {
        List<EditBox> l = new ArrayList<>();
        if (modal) return l;
        switch (view) {
            case HOME:
            case BROWSE:
                l.add(searchField);
                break;
            case CREATE:
                l.add(titleField);
                l.add(bodyField);
                if (AdsState.catHasPrice(cCat)) l.add(priceField);
                break;
            case MOD:
            case DETAIL:
                if (AdsState.isMod) l.add(reasonField);
                break;
            default:
                break;
        }
        return l;
    }

    @Override
    protected void onEnter(EditBox f) {
        if (f == searchField && view == View.HOME) go(View.BROWSE);
        else if (f == titleField) focus(bodyField, true);
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (now - lastRefresh > 15_000L) {
            lastRefresh = now;
            AdsClient.requestInfo();
            AdsClient.requestList();
            if (view == View.ALL) AdsClient.requestAll();
            if (view == View.LOG) AdsClient.requestHistory();
        }
    }

    private void go(View v) {
        if (v == view) return;
        if (v != View.CONFIRM && v != View.DETAIL) back = v;
        view = v;
        viewAt = System.currentTimeMillis();
        scroll = 0;
        confirmDelete = -1;
        if (v == View.CREATE && editId < 0) {
            if (cCat.isEmpty() && !AdsState.cats.isEmpty()) cCat = AdsState.cats.get(0).id;
        }
        if (v == View.ALL) AdsClient.requestAll();
        if (v == View.LOG) AdsClient.requestHistory();
        AdsClient.requestInfo();
        AdsClient.requestList();
    }

    private void openDetail(AdsState.Ad a, View from) {
        detailId = a.id;
        back = from;
        view = View.DETAIL;
        viewAt = System.currentTimeMillis();
        scroll = 0;
        confirmDelete = -1;
    }

    private void startCreate() {
        editId = -1;
        titleField.setValue("");
        bodyField.setValue("");
        priceField.setValue("");
        cPromo = false;
        cDur = AdsState.durations.contains(AdsState.defaultDuration) ? AdsState.defaultDuration : -1;
        go(View.CREATE);
    }

    private void startEdit(AdsState.Ad a) {
        editId = a.id;
        cCat = a.cat;
        cPromo = a.promoted;
        titleField.setValue(a.title);
        bodyField.setValue(a.body);
        priceField.setValue(a.price >= 0 ? String.valueOf(a.price) : "");
        view = View.CREATE;
        viewAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ utilitare

    private static float ease(float t) {
        float k = 1f - Math.max(0f, Math.min(1f, t));
        return 1f - k * k * k;
    }

    /** Text cu y = marginea de sus. */
    private static void t(GuiGraphics ms, String s, float x, float y, float size, int color, int align, boolean bold) {
        Gfx.text(ms, s, x, y + size * 0.82f, size, color, align, bold, bold ? 0.2f : 0f);
    }

    private static List<String> wrap(String text, float size, float maxW) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String c = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && Gfx.width(c, size, false) > maxW) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(c);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static String clip(String s, float size, boolean bold, float maxW) {
        if (Gfx.width(s, size, bold) <= maxW) return s;
        String c = s;
        while (c.length() > 1 && Gfx.width(c + "...", size, bold) > maxW) c = c.substring(0, c.length() - 1);
        return c + "...";
    }

    private static String left(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 86_400) return (s / 86_400) + "z " + ((s % 86_400) / 3600) + "h";
        if (s >= 3600) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }

    private static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "acum";
        if (s < 3600) return "acum " + (s / 60) + " min";
        if (s < 86_400) return "acum " + (s / 3600) + " h";
        return "acum " + (s / 86_400) + " zile";
    }

    private static String digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) sb.append(s.charAt(i));
        return sb.toString();
    }

    private void h(int x, int y, int w, int hh, Runnable r) {
        if (!modal) hit(x, y, w, hh, r);
    }

    private boolean hot(int mx, int my, int x, int y, int w, int hh) {
        return !modal && Ui.in(mx, my, x, y, w, hh);
    }

    private void btn(GuiGraphics ms, String label, int x, int y, int w, int hh, int mx, int my, boolean enabled, int color, Runnable r) {
        boolean hv = enabled && hot(mx, my, x, y, w, hh);
        int bg = !enabled ? 0xFF22242B : (color == 0 ? (hv ? 0xFF2A2D36 : 0xFF1C1E25) : (hv ? Gfx.mixArgb(color, 0xFFFFFFFF, 0.12f) : color));
        Gfx.rrect(ms, x, y, w, hh, 4, bg);
        if (color == 0 && enabled) Gfx.rrect(ms, x, y + hh - 1, w, 1, 0, hv ? 0xFF3A3D48 : LINE);
        t(ms, label, x + w / 2f, y + (hh - 7) / 2f, 7f, enabled ? TXT : FAINT, 1, true);
        if (enabled) h(x, y, w, hh, r);
    }

    private int chipW(String label) {
        return Math.round(Gfx.width(label, 6.6f, true)) + 16;
    }

    private int chip(GuiGraphics ms, String label, int x, int y, int mx, int my, boolean on, int color, Runnable r) {
        int w = chipW(label);
        boolean hv = hot(mx, my, x, y, w, 14);
        Gfx.rrect(ms, x, y, w, 14, 7, on ? color : (hv ? 0xFF262831 : 0xFF1A1B22));
        t(ms, label, x + w / 2f, y + 3.6f, 6.6f, on ? 0xFF0B0B0E : (hv ? TXT : MUTED), 1, true);
        h(x, y, w, 14, r);
        return w;
    }

    private void kv(GuiGraphics ms, String k, String v, int x, int y, int w, int color) {
        t(ms, k, x, y, 7f, FAINT, 0, false);
        t(ms, v, x + w, y, 7.2f, color, 2, true);
    }

    // ------------------------------------------------------------------ iconite (desenate din cod)

    static void icon(GuiGraphics ms, String id, float cx, float cy, float s, int c) {
        float u = s / 8f;
        switch (id) {
            case "masina":
                Gfx.rrect(ms, cx - 7 * u, cy - 1 * u, 14 * u, 4 * u, u, c);
                Gfx.poly(ms, new float[] {cx - 4.5f * u, cy - 1 * u, cx - 3 * u, cy - 4.5f * u, cx + 3 * u, cy - 4.5f * u, cx + 4.5f * u, cy - 1 * u}, c);
                Gfx.disc(ms, cx - 4 * u, cy + 3.2f * u, 1.8f * u, 0xFF0B0B0E);
                Gfx.disc(ms, cx + 4 * u, cy + 3.2f * u, 1.8f * u, 0xFF0B0B0E);
                Gfx.disc(ms, cx - 4 * u, cy + 3.2f * u, 1.1f * u, c);
                Gfx.disc(ms, cx + 4 * u, cy + 3.2f * u, 1.1f * u, c);
                break;
            case "casa":
                Gfx.poly(ms, new float[] {cx - 6 * u, cy - 0.5f * u, cx, cy - 6 * u, cx + 6 * u, cy - 0.5f * u}, c);
                Gfx.rrect(ms, cx - 4.5f * u, cy - 0.5f * u, 9 * u, 6 * u, 0, c);
                Gfx.rrect(ms, cx - 1.2f * u, cy + 1.5f * u, 2.4f * u, 4 * u, 0, 0xFF0B0B0E);
                break;
            case "cheie":
                Gfx.ring(ms, cx - 3 * u, cy, 1.4f * u, 3 * u, c, c);
                Gfx.rrect(ms, cx - 0.2f * u, cy - 0.8f * u, 7 * u, 1.6f * u, 0, c);
                Gfx.rrect(ms, cx + 4.2f * u, cy, 1.4f * u, 2.6f * u, 0, c);
                Gfx.rrect(ms, cx + 2 * u, cy, 1.2f * u, 2 * u, 0, c);
                break;
            case "cutie":
                Gfx.rrect(ms, cx - 5.5f * u, cy - 3.5f * u, 11 * u, 9 * u, u, c);
                Gfx.rrect(ms, cx - 5.5f * u, cy - 3.5f * u, 11 * u, 2.4f * u, 0, Gfx.mixArgb(c, 0xFF000000, 0.35f));
                Gfx.rrect(ms, cx - 1 * u, cy - 3.5f * u, 2 * u, 4 * u, 0, 0xFF0B0B0E);
                break;
            case "unealta":
                Gfx.line(ms, cx - 5 * u, cy + 5 * u, cx + 2.5f * u, cy - 2.5f * u, 2f * u, c);
                Gfx.ring(ms, cx + 3.5f * u, cy - 3.5f * u, 1.3f * u, 3 * u, c, c);
                Gfx.rrect(ms, cx + 3.2f * u, cy - 7 * u, 3 * u, 3 * u, 0, 0x00000000);
                break;
            case "servieta":
                Gfx.rrect(ms, cx - 6 * u, cy - 2.5f * u, 12 * u, 8 * u, u, c);
                Gfx.rrect(ms, cx - 2.5f * u, cy - 5 * u, 5 * u, 1.4f * u, 0, c);
                Gfx.rrect(ms, cx - 2.5f * u, cy - 5 * u, 1.3f * u, 3 * u, 0, c);
                Gfx.rrect(ms, cx + 1.2f * u, cy - 5 * u, 1.3f * u, 3 * u, 0, c);
                Gfx.rrect(ms, cx - 6 * u, cy + 0.6f * u, 12 * u, 0.9f * u, 0, 0xFF0B0B0E);
                break;
            case "oameni":
                Gfx.disc(ms, cx - 2.6f * u, cy - 2.6f * u, 2 * u, c);
                Gfx.disc(ms, cx + 3 * u, cy - 2 * u, 1.7f * u, Gfx.mixArgb(c, 0xFF000000, 0.25f));
                Gfx.rrect(ms, cx - 6 * u, cy + 0.4f * u, 7 * u, 5 * u, 2.5f * u, c);
                Gfx.rrect(ms, cx + 0.8f * u, cy + 0.8f * u, 4.6f * u, 4.2f * u, 2 * u, Gfx.mixArgb(c, 0xFF000000, 0.25f));
                break;
            case "home":
                Gfx.poly(ms, new float[] {cx - 5 * u, cy, cx, cy - 5 * u, cx + 5 * u, cy}, c);
                Gfx.rrect(ms, cx - 3.5f * u, cy, 7 * u, 4.5f * u, 0, c);
                break;
            case "list":
                for (int i = 0; i < 3; i++) {
                    Gfx.rrect(ms, cx - 5 * u, cy - 4 * u + i * 3.5f * u, 1.6f * u, 1.6f * u, 0, c);
                    Gfx.rrect(ms, cx - 2.2f * u, cy - 4 * u + i * 3.5f * u, 7 * u, 1.6f * u, 0, c);
                }
                break;
            case "grid":
                for (int i = 0; i < 4; i++) Gfx.rrect(ms, cx - 5 * u + (i % 2) * 5.6f * u, cy - 5 * u + (i / 2) * 5.6f * u, 4.4f * u, 4.4f * u, u, c);
                break;
            case "user":
                Gfx.disc(ms, cx, cy - 2.5f * u, 2.4f * u, c);
                Gfx.rrect(ms, cx - 4.5f * u, cy + 1 * u, 9 * u, 4.5f * u, 2.5f * u, c);
                break;
            case "plus":
                Gfx.rrect(ms, cx - 5 * u, cy - 0.9f * u, 10 * u, 1.8f * u, 0, c);
                Gfx.rrect(ms, cx - 0.9f * u, cy - 5 * u, 1.8f * u, 10 * u, 0, c);
                break;
            case "shield":
                Gfx.poly(ms, new float[] {cx - 5 * u, cy - 4 * u, cx, cy - 6 * u, cx + 5 * u, cy - 4 * u, cx + 4 * u, cy + 2 * u, cx, cy + 6 * u, cx - 4 * u, cy + 2 * u}, c);
                break;
            case "clock":
                Gfx.ring(ms, cx, cy, 4 * u, 5.4f * u, c, c);
                Gfx.rrect(ms, cx - 0.7f * u, cy - 3.2f * u, 1.4f * u, 3.6f * u, 0, c);
                Gfx.rrect(ms, cx - 0.7f * u, cy - 0.7f * u, 3 * u, 1.4f * u, 0, c);
                break;
            case "gear":
                Gfx.ring(ms, cx, cy, 1.8f * u, 4.2f * u, c, c);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    Gfx.disc(ms, cx + (float) Math.cos(a) * 4.6f * u, cy + (float) Math.sin(a) * 4.6f * u, 1.2f * u, c);
                }
                break;
            case "phone":
                Gfx.rrect(ms, cx - 3 * u, cy - 5.5f * u, 6 * u, 11 * u, 1.2f * u, c);
                Gfx.rrect(ms, cx - 2 * u, cy - 4 * u, 4 * u, 7 * u, 0, 0xFF0B0B0E);
                break;
            default: // puncte
                for (int i = -1; i <= 1; i++) Gfx.disc(ms, cx + i * 4 * u, cy, 1.4f * u, c);
                break;
        }
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = ease((now - openedAt) / 280f);
        Ui.rect(ms, 0, 0, width, height, Gfx.fade(0xB0000000, open));
        modal = view == View.CONFIRM;
        Gfx.alpha = open;
        try {
            Gfx.rrect(ms, px - 2, py + 3, pw + 4, ph, 10, 0x66000000);
            Gfx.rrect(ms, px, py, pw, ph, 9, BG);
            Gfx.rrect(ms, px, py, SIDE_W, ph, 9, SIDE);
            Gfx.rrect(ms, px + SIDE_W - 9, py, 9, ph, 0, SIDE);
            Gfx.rrect(ms, px + SIDE_W, py, 1, ph, 0, LINE);
            sidebar(ms, mx, my);
            float in = ease((now - viewAt) / 240f);
            Gfx.alpha = open * (0.35f + 0.65f * in);
            int cx = px + SIDE_W + 14;
            int cw = pw - SIDE_W - 26;
            int top = py + 12;
            switch (view) {
                case HOME: home(ms, cx, top, cw, mx, my, now); break;
                case BROWSE: browse(ms, cx, top, cw, mx, my); break;
                case CATS: cats(ms, cx, top, cw, mx, my); break;
                case DETAIL: detail(ms, cx, top, cw, mx, my, now); break;
                case CREATE: create(ms, cx, top, cw, mx, my); break;
                case CONFIRM: create(ms, cx, top, cw, mx, my); break;
                case MINE: mine(ms, cx, top, cw, mx, my, now); break;
                case MOD: modView(ms, cx, top, cw, mx, my); break;
                case ALL: allView(ms, cx, top, cw, mx, my); break;
                case LOG: logView(ms, cx, top, cw); break;
                default: cfgView(ms, cx, top, cw, mx, my); break;
            }
            Gfx.alpha = open;
            if (view == View.CONFIRM) confirm(ms, mx, my);
            message(ms);
        } finally {
            Gfx.alpha = 1f;
        }
    }

    private void sidebar(GuiGraphics ms, int mx, int my) {
        int x = px + 12;
        int y = py + 14;
        Gfx.rrect(ms, x, y, 34, 18, 3, RED);
        t(ms, "CNN", x + 17, y + 4.5f, 9.5f, 0xFFFFFFFF, 1, true);
        t(ms, "ANUNȚURI", x + 40, y + 2, 7.2f, TXT, 0, true);
        t(ms, "FlowCity", x + 40, y + 11, 6f, FAINT, 0, false);
        y += 34;
        y = side(ms, "home", "Acasă", View.HOME, x, y, mx, my, () -> go(View.HOME));
        y = side(ms, "list", "Anunțuri", View.BROWSE, x, y, mx, my, () -> {
            filterCat = "";
            go(View.BROWSE);
        });
        y = side(ms, "grid", "Categorii", View.CATS, x, y, mx, my, () -> go(View.CATS));
        y = side(ms, "user", "Anunțurile mele", View.MINE, x, y, mx, my, () -> go(View.MINE));
        y += 4;
        boolean hv = hot(mx, my, x - 2, y, SIDE_W - 20, 20);
        Gfx.rrect(ms, x - 2, y, SIDE_W - 20, 20, 5, hv ? Gfx.mixArgb(RED, 0xFFFFFFFF, 0.12f) : RED);
        icon(ms, "plus", x + 9, y + 10, 7, 0xFFFFFFFF);
        t(ms, "PUBLICĂ", x + 19, y + 6.5f, 7.2f, 0xFFFFFFFF, 0, true);
        h(x - 2, y, SIDE_W - 20, 20, this::startCreate);
        y += 30;
        if (AdsState.isMod) {
            t(ms, "STAFF", x, y, 6f, FAINT, 0, true);
            y += 11;
            String m = "Moderare" + (AdsState.pending.isEmpty() ? "" : " (" + AdsState.pending.size() + ")");
            y = side(ms, "shield", m, View.MOD, x, y, mx, my, () -> go(View.MOD));
            y = side(ms, "list", "Toate anunțurile", View.ALL, x, y, mx, my, () -> go(View.ALL));
            y = side(ms, "clock", "Istoric", View.LOG, x, y, mx, my, () -> go(View.LOG));
            if (AdsState.isAdmin) y = side(ms, "gear", "Setări", View.CFG, x, y, mx, my, () -> go(View.CFG));
        }
        int by = py + ph - 34;
        Gfx.rrect(ms, x - 2, by, SIDE_W - 20, 24, 5, 0xFF121318);
        t(ms, "SOLD", x + 4, by + 4, 5.8f, FAINT, 0, true);
        t(ms, Ui.money(AdsState.balance), x + 4, by + 12, 7.4f, GREEN, 0, true);
        // inchidere
        int cx = px + pw - 22;
        boolean ch = hot(mx, my, cx - 2, py + 8, 16, 16);
        Gfx.rrect(ms, cx - 2, py + 8, 16, 16, 8, ch ? RED : 0xFF1C1E25);
        Gfx.line(ms, cx + 2, py + 12, cx + 10, py + 20, 1.4f, TXT);
        Gfx.line(ms, cx + 10, py + 12, cx + 2, py + 20, 1.4f, TXT);
        hit(cx - 2, py + 8, 16, 16, this::onClose);
    }

    private int side(GuiGraphics ms, String ic, String label, View v, int x, int y, int mx, int my, Runnable r) {
        boolean on = view == v || (view == View.DETAIL && back == v) || (view == View.CONFIRM && v == View.CREATE);
        boolean hv = hot(mx, my, x - 2, y, SIDE_W - 20, 18);
        if (on || hv) Gfx.rrect(ms, x - 2, y, SIDE_W - 20, 18, 4, on ? 0xFF1D1013 : 0xFF15161B);
        if (on) Gfx.rrect(ms, x - 2, y + 4, 2, 10, 0, RED);
        icon(ms, ic, x + 8, y + 9, 6.5f, on ? RED : MUTED);
        t(ms, label, x + 18, y + 5.5f, 7f, on ? TXT : MUTED, 0, on);
        h(x - 2, y, SIDE_W - 20, 18, r);
        return y + 20;
    }

    private void title(GuiGraphics ms, String s, String sub, int x, int y) {
        t(ms, s, x, y, 12f, TXT, 0, true);
        if (sub != null) t(ms, sub, x, y + 15, 6.6f, FAINT, 0, false);
    }

    private void message(GuiGraphics ms) {
        long age = System.currentTimeMillis() - AdsState.lastMsgAt;
        if (AdsState.lastMsg.isEmpty() || age > 6000) return;
        int w = Math.min(pw - SIDE_W - 30, Math.round(Gfx.width(AdsState.lastMsg, 7.2f, false)) + 26);
        int x = px + SIDE_W + (pw - SIDE_W - w) / 2;
        int y = py + ph - 26;
        Gfx.rrect(ms, x, y, w, 18, 5, 0xF0121318);
        Gfx.rrect(ms, x + 6, y + 5, 3, 8, 0, AdsState.lastOk ? GREEN : BAD);
        t(ms, clip(AdsState.lastMsg, 7.2f, false, w - 22), x + 14, y + 5.5f, 7.2f, TXT, 0, false);
    }

    // ------------------------------------------------------------------ carduri

    private void card(GuiGraphics ms, AdsState.Ad a, int x, int y, int w, int hh, int mx, int my, View from) {
        boolean hv = hot(mx, my, x, y, w, hh);
        int c = AdsState.catColor(a.cat);
        Gfx.rrect(ms, x, y, w, hh, 6, hv ? CARD_HOT : CARD);
        if (a.promoted) Gfx.rrect(ms, x, y, w, 1.5f, 0, GOLD);
        Gfx.rrect(ms, x + 8, y + 8, 18, 18, 5, Gfx.mixArgb(c, 0xFF000000, 0.65f));
        icon(ms, AdsState.catIcon(a.cat), x + 17, y + 17, 6.5f, c);
        t(ms, clip(AdsState.label(a).toUpperCase(Locale.ROOT), 5.8f, true, w - 90), x + 31, y + 9, 5.8f, c, 0, true);
        t(ms, clip(a.title, 7.8f, true, w - 40), x + 31, y + 17, 7.8f, TXT, 0, true);
        if (a.promoted) {
            Gfx.rrect(ms, x + w - 54, y + 7, 46, 11, 3, GOLD);
            t(ms, "PROMOVAT", x + w - 31, y + 9.3f, 5.6f, 0xFF0B0B0E, 1, true);
        }
        t(ms, clip(a.body, 6.8f, false, w - 18), x + 9, y + 32, 6.8f, MUTED, 0, false);
        t(ms, a.price >= 0 ? Ui.money(a.price) : "—", x + 9, y + hh - 22, 9f, a.price >= 0 ? GREEN : FAINT, 0, true);
        t(ms, clip(a.author + (a.phone.isEmpty() ? "" : "  •  " + a.phone), 6.2f, false, w - 18), x + 9, y + hh - 11, 6.2f, FAINT, 0, false);
        String tl = "ACTIVE".equals(a.status) ? "mai are " + left(a.expires - System.currentTimeMillis()) : AdsState.statusLabel(a.status);
        t(ms, ago(a.created), x + w - 9, y + hh - 21, 6.2f, FAINT, 2, false);
        t(ms, tl, x + w - 9, y + hh - 11, 6.2f, "ACTIVE".equals(a.status) ? MUTED : AdsState.statusColor(a.status), 2, false);
        h(x, y, w, hh, () -> openDetail(a, from));
    }

    private List<AdsState.Ad> filtered() {
        List<AdsState.Ad> l = new ArrayList<>();
        String q = searchField.getValue().trim().toLowerCase(Locale.ROOT);
        for (AdsState.Ad a : AdsState.items) {
            if (!filterCat.isEmpty() && !filterCat.equals(a.cat)) continue;
            if (!q.isEmpty() && !(a.title.toLowerCase(Locale.ROOT).contains(q) || a.body.toLowerCase(Locale.ROOT).contains(q)
                || a.author.toLowerCase(Locale.ROOT).contains(q) || a.phone.contains(q))) continue;
            l.add(a);
        }
        final int s = sort;
        l.sort((a, b) -> {
            if (s == 0 && a.promoted != b.promoted) return a.promoted ? -1 : 1;
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

    /** Grila de carduri cu derulare (pe randuri). */
    private void grid(GuiGraphics ms, List<AdsState.Ad> items, int x, int y, int w, int bottom, int mx, int my, View from, String empty) {
        int cols = 2;
        int gap = 8;
        int cw = (w - gap) / cols;
        int chh = 62;
        int rows = (items.size() + cols - 1) / cols;
        int visible = Math.max(1, (bottom - y + gap) / (chh + gap));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - visible)));
        if (items.isEmpty()) {
            t(ms, empty, x + w / 2f, y + 30, 8f, FAINT, 1, false);
            return;
        }
        for (int r = scroll; r < Math.min(rows, scroll + visible); r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                if (i >= items.size()) break;
                card(ms, items.get(i), x + c * (cw + gap), y + (r - scroll) * (chh + gap), cw, chh, mx, my, from);
            }
        }
        if (rows > visible) {
            float trackH = bottom - y;
            float th = Math.max(14f, trackH * visible / rows);
            float ty = y + (trackH - th) * scroll / Math.max(1, rows - visible);
            Gfx.rrect(ms, x + w + 4, y, 2, trackH, 1, 0xFF17181E);
            Gfx.rrect(ms, x + w + 4, ty, 2, th, 1, RED);
        }
    }

    // ------------------------------------------------------------------ pagini

    private void home(GuiGraphics ms, int x, int y, int w, int mx, int my, long now) {
        title(ms, "Bun venit pe CNN", "Anunțurile orașului: mașini, proprietăți, servicii, joburi.", x, y);
        btn(ms, "+  PUBLICĂ ANUNȚ", x + w - 112, y, 112, 20, mx, my, true, RED, this::startCreate);
        y += 30;
        drawField(ms, searchField, x, y, w, 18, mx, my);
        y += 26;
        List<AdsState.Ad> promo = new ArrayList<>();
        List<AdsState.Ad> recent = new ArrayList<>(AdsState.items);
        recent.sort((a, b) -> Long.compare(b.created, a.created));
        for (AdsState.Ad a : AdsState.items) if (a.promoted) promo.add(a);
        int cw = (w - 8) / 2;
        if (!promo.isEmpty()) {
            t(ms, "PROMOVATE", x, y, 6.6f, GOLD, 0, true);
            y += 11;
            for (int i = 0; i < Math.min(2, promo.size()); i++) card(ms, promo.get(i), x + i * (cw + 8), y, cw, 62, mx, my, View.HOME);
            y += 70;
        }
        t(ms, "RECENTE", x, y, 6.6f, MUTED, 0, true);
        t(ms, "vezi toate >", x + w, y, 6.6f, hot(mx, my, x + w - 60, y - 2, 60, 11) ? TXT : RED, 2, true);
        h(x + w - 60, y - 2, 60, 11, () -> {
            filterCat = "";
            go(View.BROWSE);
        });
        y += 11;
        int n = promo.isEmpty() ? 4 : 2;
        int shown = 0;
        for (AdsState.Ad a : recent) {
            if (shown >= n) break;
            card(ms, a, x + (shown % 2) * (cw + 8), y + (shown / 2) * 70, cw, 62, mx, my, View.HOME);
            shown++;
        }
        if (shown == 0) t(ms, "Nu exista anunturi inca. Fii primul!", x + w / 2f, y + 20, 8f, FAINT, 1, false);
        y += ((shown + 1) / 2) * 70 + (shown == 0 ? 40 : 0);
        int bottom = py + ph - 12;
        if (y + 30 < bottom) {
            t(ms, "CATEGORII", x, y, 6.6f, MUTED, 0, true);
            y += 11;
            int fx = x;
            for (AdsState.Cat c : AdsState.cats) {
                int cwid = chipW(c.label.toUpperCase(Locale.ROOT)) + 10;
                if (fx + cwid > x + w) {
                    fx = x;
                    y += 18;
                    if (y + 14 > bottom - 22) break;
                }
                final String id = c.id;
                boolean hv = hot(mx, my, fx, y, cwid, 14);
                Gfx.rrect(ms, fx, y, cwid, 14, 7, hv ? 0xFF262831 : 0xFF1A1B22);
                icon(ms, c.icon, fx + 9, y + 7, 4.5f, c.color);
                t(ms, c.label.toUpperCase(Locale.ROOT), fx + 16, y + 3.6f, 6.2f, hv ? TXT : MUTED, 0, true);
                h(fx, y, cwid, 14, () -> {
                    filterCat = id;
                    go(View.BROWSE);
                });
                fx += cwid + 4;
            }
            y += 22;
        }
        if (y + 18 < bottom) {
            int act = 0;
            for (AdsState.Ad a : AdsState.own) if ("ACTIVE".equals(a.status) || "PENDING".equals(a.status)) act++;
            boolean hv = hot(mx, my, x, y, w, 18);
            Gfx.rrect(ms, x, y, w, 18, 5, hv ? CARD_HOT : CARD);
            icon(ms, "user", x + 10, y + 9, 6f, RED);
            t(ms, "Anunțurile mele: " + act + " active din " + AdsState.maxActive, x + 20, y + 5.5f, 7f, TXT, 0, false);
            t(ms, "deschide >", x + w - 8, y + 5.5f, 6.6f, RED, 2, true);
            h(x, y, w, 18, () -> go(View.MINE));
        }
    }

    private void browse(GuiGraphics ms, int x, int y, int w, int mx, int my) {
        String sub = filterCat.isEmpty() ? "Toate categoriile" : AdsState.catLabel(filterCat);
        title(ms, "Anunțuri", sub, x, y);
        btn(ms, "+  PUBLICĂ", x + w - 80, y, 80, 20, mx, my, true, RED, this::startCreate);
        y += 30;
        drawField(ms, searchField, x, y, w - 150, 18, mx, my);
        String[] names = {"RECENTE", "PREȚ ↑", "PREȚ ↓"};
        int sx = x + w - 146;
        for (int i = 0; i < 3; i++) {
            final int k = i;
            sx += chip(ms, names[i], sx, y + 2, mx, my, sort == i, RED, () -> sort = k) + 3;
        }
        y += 24;
        int fx = x;
        fx += chip(ms, "TOATE", fx, y, mx, my, filterCat.isEmpty(), RED, () -> {
            filterCat = "";
            scroll = 0;
        }) + 3;
        for (AdsState.Cat c : AdsState.cats) {
            String lab = c.label.toUpperCase(Locale.ROOT);
            if (fx + chipW(lab) > x + w) {
                fx = x;
                y += 17;
            }
            final String id = c.id;
            fx += chip(ms, lab, fx, y, mx, my, id.equals(filterCat), c.color, () -> {
                filterCat = id;
                scroll = 0;
            }) + 3;
        }
        y += 22;
        grid(ms, filtered(), x, y, w - 8, py + ph - 12, mx, my, View.BROWSE, "Niciun anunt gasit.");
    }

    private void cats(GuiGraphics ms, int x, int y, int w, int mx, int my) {
        title(ms, "Categorii", "Alege o categorie ca sa vezi anunturile ei.", x, y);
        y += 32;
        int cols = 3;
        int gap = 8;
        int tw = (w - gap * (cols - 1)) / cols;
        int th = 44;
        int i = 0;
        for (AdsState.Cat c : AdsState.cats) {
            int tx = x + (i % cols) * (tw + gap);
            int ty = y + (i / cols) * (th + gap);
            int n = 0;
            for (AdsState.Ad a : AdsState.items) if (a.cat.equals(c.id)) n++;
            boolean hv = hot(mx, my, tx, ty, tw, th);
            Gfx.rrect(ms, tx, ty, tw, th, 6, hv ? CARD_HOT : CARD);
            Gfx.rrect(ms, tx, ty + th - 2, tw, 2, 0, c.color);
            Gfx.rrect(ms, tx + 8, ty + 8, 26, 26, 7, Gfx.mixArgb(c.color, 0xFF000000, 0.65f));
            icon(ms, c.icon, tx + 21, ty + 21, 9f, c.color);
            t(ms, clip(c.label, 7.6f, true, tw - 48), tx + 40, ty + 11, 7.6f, TXT, 0, true);
            t(ms, n + (n == 1 ? " anunt" : " anunturi"), tx + 40, ty + 23, 6.6f, MUTED, 0, false);
            final String id = c.id;
            h(tx, ty, tw, th, () -> {
                filterCat = id;
                go(View.BROWSE);
            });
            i++;
        }
    }

    private AdsState.Ad findAd(long id) {
        for (AdsState.Ad a : AdsState.items) if (a.id == id) return a;
        for (AdsState.Ad a : AdsState.own) if (a.id == id) return a;
        for (AdsState.Ad a : AdsState.pending) if (a.id == id) return a;
        for (AdsState.Ad a : AdsState.all) if (a.id == id) return a;
        return null;
    }

    private void detail(GuiGraphics ms, int x, int y, int w, int mx, int my, long now) {
        AdsState.Ad a = findAd(detailId);
        boolean bh = hot(mx, my, x, y, 60, 14);
        t(ms, "< ÎNAPOI", x, y + 2, 7f, bh ? TXT : RED, 0, true);
        h(x, y, 60, 14, () -> go(back == View.DETAIL ? View.HOME : back));
        if (a == null) {
            t(ms, "Anuntul nu mai este disponibil.", x + w / 2f, py + ph / 2f, 8.5f, FAINT, 1, false);
            return;
        }
        y += 20;
        int c = AdsState.catColor(a.cat);
        int lw = w - 168;
        Gfx.rrect(ms, x, y, 30, 30, 8, Gfx.mixArgb(c, 0xFF000000, 0.65f));
        icon(ms, AdsState.catIcon(a.cat), x + 15, y + 15, 10f, c);
        t(ms, AdsState.label(a).toUpperCase(Locale.ROOT), x + 38, y + 2, 6.4f, c, 0, true);
        int ty = y + 12;
        for (String l : wrap(a.title, 11f, lw - 40)) {
            t(ms, l, x + 38, ty, 11f, TXT, 0, true);
            ty += 14;
        }
        if (a.promoted) {
            Gfx.rrect(ms, x + 38, ty + 1, 50, 11, 3, GOLD);
            t(ms, "PROMOVAT", x + 63, ty + 3.3f, 5.6f, 0xFF0B0B0E, 1, true);
            ty += 15;
        }
        y = Math.max(y + 36, ty + 4);
        if (a.price >= 0) {
            t(ms, Ui.money(a.price), x, y, 13f, GREEN, 0, true);
            y += 20;
        }
        Gfx.rrect(ms, x, y, lw, 1, 0, LINE);
        y += 8;
        int bottom = py + ph - 40;
        List<String> lines = wrap(a.body.isEmpty() ? "(fara descriere)" : a.body, 7.6f, lw);
        int visible = Math.max(1, (bottom - y) / 11);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        for (int i = scroll; i < Math.min(lines.size(), scroll + visible); i++) {
            t(ms, lines.get(i), x, y, 7.6f, 0xFFD3D6DE, 0, false);
            y += 11;
        }

        // coloana dreapta: informatii + contact
        int rx = x + w - 158;
        int ry = py + 32;
        Gfx.rrect(ms, rx, ry, 158, 112, 6, CARD);
        t(ms, "DETALII", rx + 10, ry + 8, 6.2f, FAINT, 0, true);
        kv(ms, "Autor", a.author, rx + 10, ry + 22, 138, TXT);
        kv(ms, "Telefon", a.phone.isEmpty() ? "-" : a.phone, rx + 10, ry + 34, 138, a.phone.isEmpty() ? FAINT : TXT);
        kv(ms, "Publicat", Ui.time(a.created), rx + 10, ry + 46, 138, MUTED);
        kv(ms, "Expira", Ui.time(a.expires), rx + 10, ry + 58, 138, MUTED);
        kv(ms, "Timp ramas", "ACTIVE".equals(a.status) ? left(a.expires - now) : "-", rx + 10, ry + 70, 138, TXT);
        kv(ms, "Status", AdsState.statusLabel(a.status), rx + 10, ry + 82, 138, AdsState.statusColor(a.status));
        if (!a.reason.isEmpty() && !"ACTIVE".equals(a.status)) t(ms, clip("Motiv: " + a.reason, 6.4f, false, 138), rx + 10, ry + 96, 6.4f, BAD, 0, false);
        int by = ry + 120;
        boolean hasPhone = !a.phone.isEmpty();
        if (!a.own) {
            final String num = digits(a.phone);
            btn(ms, "SUNĂ", rx, by, 76, 18, mx, my, hasPhone, RED, () -> PhoneClient.callQuery(num));
            btn(ms, "SMS", rx + 82, by, 76, 18, mx, my, hasPhone, 0, () -> PhoneScreen.openThreadWith(a.uuid, a.author));
            by += 22;
            btn(ms, "ADAUGĂ ÎN CONTACTE", rx, by, 158, 18, mx, my, true, 0, () -> {
                PhoneClient.addContact(a.author);
                toast("Cerere trimisa: " + a.author + " in contacte.", false);
            });
            by += 22;
        } else {
            final long id = a.id;
            boolean live = "ACTIVE".equals(a.status) || "PENDING".equals(a.status);
            btn(ms, "EDITEAZĂ (" + a.editsLeft + ")", rx, by, 76, 18, mx, my, AdsState.editAllowed && live && a.editsLeft > 0, 0, () -> startEdit(a));
            boolean canPromo = AdsState.promoteAllowed && "ACTIVE".equals(a.status) && !a.promoted;
            btn(ms, "PROMOVEAZĂ", rx + 82, by, 76, 18, mx, my, canPromo, RED_DARK, () -> AdsClient.promote(id));
            by += 22;
            boolean canExtend = AdsState.extendAllowed && a.extendsLeft > 0 && ("ACTIVE".equals(a.status) || "EXPIRED".equals(a.status));
            btn(ms, "PRELUNGEȘTE +" + AdsState.extendHours + "H", rx, by, 158, 18, mx, my, canExtend, 0, () -> AdsClient.extend(id));
            by += 22;
            boolean sure = confirmDelete == id;
            btn(ms, sure ? "CONFIRMĂ ȘTERGEREA" : "ȘTERGE", rx, by, 158, 18, mx, my, !"DELETED".equals(a.status), sure ? BAD : 0, () -> {
                if (confirmDelete == id) {
                    AdsClient.delete(id);
                    confirmDelete = -1;
                } else {
                    confirmDelete = id;
                }
            });
            by += 22;
            if (canPromo) t(ms, "Promovare: " + Ui.money(AdsState.feePromote) + "  •  Prelungire: " + Ui.money(AdsState.feeExtend), rx, by, 5.8f, FAINT, 0, false);
        }
        if (AdsState.isMod && !a.own) {
            int my2 = py + ph - 30;
            drawField(ms, reasonField, x, my2 - 22, lw, 16, mx, my);
            final long id = a.id;
            int bx = x;
            if ("PENDING".equals(a.status)) {
                btn(ms, "APROBĂ", bx, my2, 60, 18, mx, my, true, 0xFF2F8F5B, () -> AdsClient.mod(id, "approve", ""));
                bx += 64;
                btn(ms, "RESPINGE", bx, my2, 64, 18, mx, my, true, 0, () -> AdsClient.mod(id, "reject", reasonField.getValue().trim()));
                bx += 68;
            }
            btn(ms, "ȘTERGE", bx, my2, 56, 18, mx, my, !"DELETED".equals(a.status), BAD, () -> AdsClient.mod(id, "delete", reasonField.getValue().trim()));
            bx += 62;
            t(ms, "Suspenda:", bx, my2 + 5, 6.2f, FAINT, 0, false);
            bx += 38;
            bx += chip(ms, "1H", bx, my2 + 2, mx, my, false, BAD, () -> AdsClient.suspend(id, 60, reasonField.getValue().trim())) + 3;
            bx += chip(ms, "24H", bx, my2 + 2, mx, my, false, BAD, () -> AdsClient.suspend(id, 1440, reasonField.getValue().trim())) + 3;
            bx += chip(ms, "7Z", bx, my2 + 2, mx, my, false, BAD, () -> AdsClient.suspend(id, 10080, reasonField.getValue().trim())) + 3;
            chip(ms, "RIDICĂ", bx, my2 + 2, mx, my, false, GREEN, () -> AdsClient.suspend(id, 0, ""));
        }
    }

    // ------------------------------------------------------------------ publicare / editare

    private long priceValue() {
        String d = digits(priceField.getValue());
        if (d.isEmpty() || d.length() > 10) return -1;
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private long since() {
        return System.currentTimeMillis() - AdsState.infoAt;
    }

    private long cooldownLeft() {
        long base = cPromo && AdsState.promotedEnabled ? AdsState.cooldownPromoMs : AdsState.cooldownMs;
        return Math.max(0, base - since());
    }

    private double fee() {
        if (editId >= 0) return AdsState.feeEdit;
        return cPromo && AdsState.promotedEnabled ? AdsState.feePromoted : AdsState.feeStandard;
    }

    /** Motivul pentru care formularul nu poate fi trimis (doar UX; serverul valideaza tot din nou) sau null. */
    private String problem() {
        if (!AdsState.enabled) return "Anunturile sunt dezactivate.";
        long susp = Math.max(0, AdsState.suspendedMs - since());
        if (susp > 0) return "Publicarea ta e suspendata inca " + left(susp) + ".";
        if (AdsState.requireSim && AdsState.phone.isEmpty() && editId < 0) return "Ai nevoie de o cartela SIM activa in telefon.";
        if (cCat.isEmpty()) return "Alege o categorie.";
        String t = titleField.getValue().trim();
        if (t.length() < AdsState.titleMin) return "Titlul e prea scurt (minim " + AdsState.titleMin + ").";
        String b = bodyField.getValue().trim();
        if (b.length() < AdsState.descMin) return "Descrierea e prea scurta (minim " + AdsState.descMin + ").";
        if (AdsState.catHasPrice(cCat)) {
            long p = priceValue();
            if (p < 1 || p > AdsState.maxPrice) return "Introdu un pret valid.";
        }
        if (editId < 0) {
            if (cDur <= 0) return "Alege durata.";
            if (AdsState.active >= AdsState.maxActive) return "Ai deja " + AdsState.active + "/" + AdsState.maxActive + " anunturi active.";
            if (cooldownLeft() > 0) return "Cooldown: mai ai de asteptat " + left(cooldownLeft()) + ".";
        }
        if (AdsState.balance + 1e-9 < fee()) return "Fonduri insuficiente pentru taxa.";
        return null;
    }

    private void create(GuiGraphics ms, int x, int y, int w, int mx, int my) {
        boolean editing = editId >= 0;
        if (cCat.isEmpty() && !AdsState.cats.isEmpty()) cCat = AdsState.cats.get(0).id;
        if (cDur <= 0 && !AdsState.durations.isEmpty()) cDur = AdsState.durations.contains(AdsState.defaultDuration) ? AdsState.defaultDuration : AdsState.durations.get(0);
        title(ms, editing ? "Editează anunțul" : "Publică un anunț", editing
            ? "Poti schimba titlul, descrierea si pretul. Categoria si tipul raman aceleasi; expirarea nu se schimba."
            : "Completeaza formularul. Taxa se retrage doar dupa confirmare.", x, y);
        y += 30;
        int fw = w - 172;
        t(ms, "CATEGORIE", x, y, 6f, FAINT, 0, true);
        y += 10;
        int fx = x;
        for (AdsState.Cat c : AdsState.cats) {
            String lab = c.label.toUpperCase(Locale.ROOT);
            if (fx + chipW(lab) > x + fw) {
                fx = x;
                y += 17;
            }
            final String id = c.id;
            boolean on = id.equals(cCat);
            if (editing && !on) {
                int cw = chipW(lab);
                Gfx.rrect(ms, fx, y, cw, 14, 7, 0xFF141519);
                t(ms, lab, fx + cw / 2f, y + 3.6f, 6.6f, 0xFF3A3D47, 1, true);
                fx += cw + 3;
            } else {
                fx += chip(ms, lab, fx, y, mx, my, on, c.color, () -> {
                    if (!editing) cCat = id;
                }) + 3;
            }
        }
        y += 22;
        t(ms, "TITLU", x, y, 6f, FAINT, 0, true);
        t(ms, titleField.getValue().trim().length() + "/" + AdsState.titleMax, x + fw, y, 6f, FAINT, 2, false);
        drawField(ms, titleField, x, y + 8, fw, 17, mx, my);
        y += 30;
        t(ms, "DESCRIERE", x, y, 6f, FAINT, 0, true);
        t(ms, bodyField.getValue().trim().length() + "/" + AdsState.descMax, x + fw, y, 6f, FAINT, 2, false);
        drawField(ms, bodyField, x, y + 8, fw, 17, mx, my);
        y += 30;
        if (AdsState.catHasPrice(cCat)) {
            t(ms, "PREȚ ($)", x, y, 6f, FAINT, 0, true);
            drawField(ms, priceField, x, y + 8, 120, 17, mx, my);
            y += 30;
        }
        t(ms, "TELEFON (DIN SIM)", x, y, 6f, FAINT, 0, true);
        t(ms, AdsState.phone.isEmpty() ? "FARA SIM ACTIV" : AdsState.phone, x, y + 10, 8f, AdsState.phone.isEmpty() ? BAD : TXT, 0, true);
        y += 26;
        if (!editing) {
            t(ms, "TIPUL ANUNȚULUI", x, y, 6f, FAINT, 0, true);
            y += 10;
            int tw = (fw - 6) / 2;
            typeCard(ms, false, x, y, tw, mx, my);
            if (AdsState.promotedEnabled) typeCard(ms, true, x + tw + 6, y, tw, mx, my);
            y += 40;
            t(ms, "DURATA PUBLICĂRII", x, y, 6f, FAINT, 0, true);
            y += 10;
            int dx = x;
            for (int d : AdsState.durations) {
                dx += chip(ms, d + " ORE", dx, y, mx, my, d == cDur, RED, () -> cDur = d) + 3;
            }
        }

        // rezumat
        int rx = x + w - 160;
        int ry = py + 42;
        Gfx.rrect(ms, rx, ry, 160, 132, 6, CARD);
        t(ms, "REZUMAT", rx + 10, ry + 8, 6.2f, FAINT, 0, true);
        double fee = fee();
        kv(ms, editing ? "Taxa editare" : "Taxa", Ui.money(fee), rx + 10, ry + 22, 140, TXT);
        kv(ms, "Sold disponibil", Ui.money(AdsState.balance), rx + 10, ry + 34, 140, AdsState.balance + 1e-9 >= fee ? GREEN : BAD);
        kv(ms, "Dupa plata", Ui.money(Math.max(0, AdsState.balance - fee)), rx + 10, ry + 46, 140, MUTED);
        if (!editing) {
            long cd = cooldownLeft();
            kv(ms, "Cooldown", cd > 0 ? left(cd) : "disponibil", rx + 10, ry + 58, 140, cd > 0 ? BAD : GREEN);
            kv(ms, "Durata", cDur > 0 ? cDur + " ore" : "-", rx + 10, ry + 70, 140, TXT);
            kv(ms, "Anunturi active", AdsState.active + " / " + AdsState.maxActive, rx + 10, ry + 82, 140, TXT);
            kv(ms, "Tip", cPromo ? "Promovat" : "Standard", rx + 10, ry + 94, 140, cPromo ? GOLD : TXT);
            t(ms, AdsState.moderation ? "Anuntul va fi verificat de staff." : "Anuntul apare imediat.", rx + 10, ry + 112, 6f, FAINT, 0, false);
        } else {
            AdsState.Ad a = findAd(editId);
            kv(ms, "Editari ramase", a == null ? "-" : String.valueOf(a.editsLeft), rx + 10, ry + 58, 140, TXT);
            kv(ms, "Expira", a == null ? "-" : Ui.time(a.expires), rx + 10, ry + 70, 140, MUTED);
            t(ms, AdsState.moderation ? "Cu moderarea pornita, anuntul" : "Editarea nu consuma cooldown-ul", rx + 10, ry + 94, 6f, FAINT, 0, false);
            t(ms, AdsState.moderation ? "reintra in asteptare." : "si nu prelungeste anuntul.", rx + 10, ry + 103, 6f, FAINT, 0, false);
        }
        String pr = problem();
        btn(ms, editing ? "CONTINUĂ" : "CONTINUĂ LA PLATĂ", rx, ry + 140, 160, 22, mx, my, pr == null, RED, () -> {
            view = View.CONFIRM;
            viewAt = System.currentTimeMillis();
        });
        if (editing) btn(ms, "ANULEAZĂ", rx, ry + 166, 160, 18, mx, my, true, 0, () -> {
            editId = -1;
            go(View.MINE);
        });
        if (pr != null) {
            int ty = ry + (editing ? 190 : 168);
            for (String l : wrap(pr, 6.6f, 160)) {
                t(ms, l, rx, ty, 6.6f, BAD, 0, false);
                ty += 9;
            }
        }
    }

    private void typeCard(GuiGraphics ms, boolean promo, int x, int y, int w, int mx, int my) {
        boolean on = cPromo == promo;
        boolean hv = hot(mx, my, x, y, w, 34);
        int c = promo ? GOLD : RED;
        Gfx.rrect(ms, x, y, w, 34, 6, on ? Gfx.mixArgb(c, 0xFF000000, 0.78f) : (hv ? CARD_HOT : CARD));
        if (on) Gfx.rrect(ms, x, y + 33, w, 1, 0, c);
        Gfx.ring(ms, x + 12, y + 17, 3, 5, on ? c : FAINT, on ? c : FAINT);
        if (on) Gfx.disc(ms, x + 12, y + 17, 2.2f, c);
        t(ms, promo ? "PROMOVAT" : "STANDARD", x + 22, y + 7, 7.4f, TXT, 0, true);
        t(ms, promo ? "Apare primul, evidentiat" : "Listare normala", x + 22, y + 19, 6f, MUTED, 0, false);
        t(ms, Ui.money(promo ? AdsState.feePromoted : AdsState.feeStandard), x + w - 8, y + 7, 7.4f, promo ? GOLD : GREEN, 2, true);
        h(x, y, w, 34, () -> cPromo = promo);
    }

    private void confirm(GuiGraphics ms, int mx, int my) {
        boolean editing = editId >= 0;
        Gfx.rrect(ms, px, py, pw, ph, 9, 0xC0050507);
        int w = 280;
        int hh = 176;
        int x = px + (pw - w) / 2;
        int y = py + (ph - hh) / 2;
        float in = ease((System.currentTimeMillis() - viewAt) / 220f);
        y += Math.round((1f - in) * 12);
        Gfx.rrect(ms, x, y, w, hh, 8, 0xFF111217);
        Gfx.rrect(ms, x, y, w, 3, 0, RED);
        t(ms, editing ? "CONFIRMI MODIFICAREA?" : "CONFIRMĂ PLATA", x + w / 2f, y + 14, 10f, TXT, 1, true);
        t(ms, clip(titleField.getValue().trim(), 7.4f, false, w - 30), x + w / 2f, y + 30, 7.4f, MUTED, 1, false);
        int ky = y + 48;
        kv(ms, "Categorie", AdsState.catLabel(cCat), x + 18, ky, w - 36, TXT);
        if (AdsState.catHasPrice(cCat)) kv(ms, "Pret anunt", Ui.money(priceValue()), x + 18, ky + 12, w - 36, GREEN);
        if (!editing) {
            kv(ms, "Tip", cPromo ? "Promovat" : "Standard", x + 18, ky + 24, w - 36, cPromo ? GOLD : TXT);
            kv(ms, "Durata", cDur + " ore", x + 18, ky + 36, w - 36, TXT);
        }
        Gfx.rrect(ms, x + 18, ky + 50, w - 36, 1, 0, LINE);
        kv(ms, "Taxa", Ui.money(fee()), x + 18, ky + 58, w - 36, TXT);
        kv(ms, "Sold actual", Ui.money(AdsState.balance), x + 18, ky + 70, w - 36, MUTED);
        kv(ms, "Sold dupa plata", Ui.money(Math.max(0, AdsState.balance - fee())), x + 18, ky + 82, w - 36, GREEN);
        int by = y + hh - 28;
        boolean h1 = Ui.in(mx, my, x + 18, by, 150, 20);
        Gfx.rrect(ms, x + 18, by, 150, 20, 4, h1 ? Gfx.mixArgb(RED, 0xFFFFFFFF, 0.12f) : RED);
        t(ms, editing ? "CONFIRMĂ" : "CONFIRMĂ ȘI PLĂTEȘTE", x + 93, by + 6.5f, 7f, 0xFFFFFFFF, 1, true);
        hit(x + 18, by, 150, 20, this::submit);
        boolean h2 = Ui.in(mx, my, x + 174, by, w - 192, 20);
        Gfx.rrect(ms, x + 174, by, w - 192, 20, 4, h2 ? 0xFF2A2D36 : 0xFF1C1E25);
        t(ms, "ÎNAPOI", x + 174 + (w - 192) / 2f, by + 6.5f, 7f, TXT, 1, true);
        hit(x + 174, by, w - 192, 20, () -> {
            view = View.CREATE;
            viewAt = System.currentTimeMillis();
        });
    }

    private void submit() {
        view = View.CREATE;
        if (problem() != null) return;
        long price = AdsState.catHasPrice(cCat) ? priceValue() : -1;
        if (editId >= 0) {
            AdsClient.edit(editId, titleField.getValue().trim(), bodyField.getValue().trim(), price);
            detailId = editId;
            editId = -1;
            back = View.MINE;
            view = View.DETAIL;
        } else {
            AdsClient.publish(cCat, titleField.getValue().trim(), bodyField.getValue().trim(), price, cDur, cPromo && AdsState.promotedEnabled);
            titleField.setValue("");
            bodyField.setValue("");
            priceField.setValue("");
            cPromo = false;
            back = View.MINE;
            view = View.MINE;
        }
        viewAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ anunturile mele

    private void rowList(GuiGraphics ms, List<AdsState.Ad> items, int x, int y, int w, int mx, int my, View from, String empty) {
        int rowH = 26;
        int bottom = py + ph - 12;
        int visible = Math.max(1, (bottom - y) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, items.size() - visible)));
        if (items.isEmpty()) t(ms, empty, x + w / 2f, y + 26, 8f, FAINT, 1, false);
        long now = System.currentTimeMillis();
        for (int i = scroll; i < Math.min(items.size(), scroll + visible); i++) {
            AdsState.Ad a = items.get(i);
            int ry = y + (i - scroll) * rowH;
            boolean hv = hot(mx, my, x, ry, w, rowH - 3);
            Gfx.rrect(ms, x, ry, w, rowH - 3, 5, hv ? CARD_HOT : CARD);
            int c = AdsState.catColor(a.cat);
            Gfx.rrect(ms, x + 6, ry + 5, 13, 13, 4, Gfx.mixArgb(c, 0xFF000000, 0.65f));
            icon(ms, AdsState.catIcon(a.cat), x + 12.5f, ry + 11.5f, 5f, c);
            t(ms, clip(a.title, 7.4f, true, w * 0.42f), x + 25, ry + 4, 7.4f, TXT, 0, true);
            t(ms, clip(AdsState.label(a) + "  •  " + a.author, 6f, false, w * 0.42f), x + 25, ry + 13.5f, 6f, FAINT, 0, false);
            t(ms, a.price >= 0 ? Ui.money(a.price) : "-", x + w * 0.62f, ry + 8, 7.2f, GREEN, 1, true);
            String st = AdsState.statusLabel(a.status) + (a.promoted ? " • PROMO" : "");
            t(ms, st, x + w - 8, ry + 4, 6.2f, AdsState.statusColor(a.status), 2, true);
            t(ms, "ACTIVE".equals(a.status) ? "mai are " + left(a.expires - now) : Ui.time(a.created), x + w - 8, ry + 13.5f, 6f, FAINT, 2, false);
            h(x, ry, w, rowH - 3, () -> openDetail(a, from));
        }
    }

    private void mine(GuiGraphics ms, int x, int y, int w, int mx, int my, long now) {
        int act = 0;
        for (AdsState.Ad a : AdsState.own) if ("ACTIVE".equals(a.status) || "PENDING".equals(a.status)) act++;
        title(ms, "Anunțurile mele", act + " / " + AdsState.maxActive + " active  •  include istoricul (expirate, sterse, respinse)", x, y);
        btn(ms, "+  PUBLICĂ", x + w - 80, y, 80, 20, mx, my, true, RED, this::startCreate);
        rowList(ms, AdsState.own, x, y + 30, w, mx, my, View.MINE, "Nu ai publicat niciun anunt.");
    }

    private void modView(GuiGraphics ms, int x, int y, int w, int mx, int my) {
        title(ms, "Moderare", AdsState.moderation ? "Anunturi care asteapta aprobarea (deschide unul pentru actiuni)." : "Aprobarea manuala e oprita (Setari). Lista contine doar editari in asteptare.", x, y);
        rowList(ms, AdsState.pending, x, y + 30, w, mx, my, View.MOD, "Nimic de aprobat.");
    }

    private void allView(GuiGraphics ms, int x, int y, int w, int mx, int my) {
        title(ms, "Toate anunțurile", "Active, in asteptare, expirate, sterse si respinse (ultimele zile).", x, y);
        rowList(ms, AdsState.all, x, y + 30, w, mx, my, View.ALL, "Niciun anunt.");
    }

    private void logView(GuiGraphics ms, int x, int y, int w) {
        title(ms, "Istoric", "Publicari, editari, moderare, suspendari si setari.", x, y);
        y += 30;
        int rowH = 13;
        int visible = Math.max(1, (py + ph - 12 - y) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, AdsState.history.size() - visible)));
        if (AdsState.history.isEmpty()) t(ms, "Nicio activitate.", x + w / 2f, y + 20, 8f, FAINT, 1, false);
        for (int i = scroll; i < Math.min(AdsState.history.size(), scroll + visible); i++) {
            AdsState.Log l = AdsState.history.get(i);
            if ((i & 1) == 0) Gfx.rrect(ms, x, y - 2, w, rowH, 2, 0xFF111216);
            t(ms, Ui.time(l.ts), x + 4, y + 1, 6.4f, FAINT, 0, false);
            boolean bad = l.action.contains("REJECT") || l.action.contains("DELETE") || l.action.contains("SUSPEND");
            t(ms, l.action, x + 76, y + 1, 6.4f, bad ? BAD : GREEN, 0, true);
            t(ms, clip(l.actor + (l.id > 0 ? "  #" + l.id : ""), 6.4f, false, 90), x + 144, y + 1, 6.4f, TXT, 0, false);
            t(ms, clip(l.detail, 6.4f, false, w - 244), x + 240, y + 1, 6.4f, MUTED, 0, false);
            y += rowH;
        }
    }

    // ------------------------------------------------------------------ setari (admin)

    private void cfgView(GuiGraphics ms, int x, int y, int w, int mx, int my) {
        title(ms, "Setări CNN", "Se salveaza in ads.yml si se aplica imediat.", x, y);
        y += 32;
        y = num(ms, "Taxa anunt standard", "fees.standard", AdsState.feeStandard, 50, x, y, w, mx, my, true);
        y = num(ms, "Taxa anunt promovat", "fees.promoted", AdsState.feePromoted, 100, x, y, w, mx, my, true);
        y = num(ms, "Taxa prelungire", "fees.extend", AdsState.feeExtend, 50, x, y, w, mx, my, true);
        y = num(ms, "Taxa promovare ulterioara", "promote.fee", AdsState.feePromote, 100, x, y, w, mx, my, true);
        y = num(ms, "Taxa editare", "edit.fee", AdsState.feeEdit, 25, x, y, w, mx, my, true);
        y = num(ms, "Cooldown publicare (minute)", "cooldown-minutes", AdsState.cooldownMin, 5, x, y, w, mx, my, false);
        y = num(ms, "Maxim anunturi active / jucator", "limits.max-active-per-player", AdsState.maxActive, 1, x, y, w, mx, my, false);
        // durata implicita: una din duratele permise
        t(ms, "Durata implicita", x + 8, y + 5, 7.2f, TXT, 0, false);
        int dx = x + w - 8;
        for (int i = AdsState.durations.size() - 1; i >= 0; i--) {
            final int d = AdsState.durations.get(i);
            String lab = d + "H";
            dx -= chipW(lab);
            chip(ms, lab, dx, y + 2, mx, my, d == AdsState.defaultDuration, RED, () -> AdsClient.cfgSet("default-duration-hours", d));
            dx -= 3;
        }
        y += 20;
        y = tog(ms, "Aprobare manuala (moderare)", "moderation.enabled", AdsState.moderation, x, y, w, mx, my);
        y = tog(ms, "Anunturi promovate", "promoted.enabled", AdsState.promotedEnabled, x, y, w, mx, my);
        y = tog(ms, "Prelungire", "extend.enabled", AdsState.extendAllowed, x, y, w, mx, my);
        tog(ms, "Editare anunturi", "edit.enabled", AdsState.editAllowed, x, y, w, mx, my);
    }

    private int num(GuiGraphics ms, String label, String key, double value, double step, int x, int y, int w, int mx, int my, boolean money) {
        Gfx.rrect(ms, x, y, w, 18, 4, (y / 18) % 2 == 0 ? 0xFF111216 : 0xFF13141A);
        t(ms, label, x + 8, y + 5, 7.2f, TXT, 0, false);
        String v = money ? Ui.money(value) : String.valueOf((long) value);
        t(ms, v, x + w - 52, y + 5, 7.4f, money ? GREEN : TXT, 2, true);
        btn(ms, "-", x + w - 44, y + 2, 16, 14, mx, my, value - step >= 0, 0, () -> AdsClient.cfgSet(key, Math.max(0, value - step)));
        btn(ms, "+", x + w - 24, y + 2, 16, 14, mx, my, true, 0, () -> AdsClient.cfgSet(key, value + step));
        return y + 20;
    }

    private int tog(GuiGraphics ms, String label, String key, boolean on, int x, int y, int w, int mx, int my) {
        Gfx.rrect(ms, x, y, w, 18, 4, 0xFF111216);
        t(ms, label, x + 8, y + 5, 7.2f, TXT, 0, false);
        int sx = x + w - 34;
        boolean hv = hot(mx, my, sx, y + 3, 26, 12);
        Gfx.rrect(ms, sx, y + 3, 26, 12, 6, on ? RED : (hv ? 0xFF33353F : 0xFF26282F));
        Gfx.disc(ms, on ? sx + 20 : sx + 6, y + 9, 4.2f, 0xFFFFFFFF);
        h(sx, y + 3, 26, 12, () -> AdsClient.cfgSet(key, on ? 0 : 1));
        return y + 20;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (modal) return true;
        scroll += -(int) Math.signum(delta);
        if (scroll < 0) scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && view == View.CONFIRM) {
            view = View.CREATE;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
