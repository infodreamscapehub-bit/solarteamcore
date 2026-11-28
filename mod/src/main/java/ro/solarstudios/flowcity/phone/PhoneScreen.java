package ro.solarstudios.flowcity.phone;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;
import ro.solarstudios.flowcity.client.ui.Xmas;
import ro.solarstudios.flowcity.bank.FlowBankScreen;
import ro.solarstudios.flowcity.hype.HypeClient;
import ro.solarstudios.flowcity.hype.HypeState;
import ro.solarstudios.flowcity.hype.JobsScreen;
import ro.solarstudios.flowcity.news.NewsClient;
import ro.solarstudios.flowcity.news.NewsScreen;
import ro.solarstudios.flowcity.notify.NotifyClient;
import ro.solarstudios.flowcity.notify.NotifyScreen;
import ro.solarstudios.flowcity.wheel.WheelClient;
import ro.solarstudios.flowcity.wheel.WheelScreen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Telefonul ePhone: ecran principal cu aplicatii + Mesaje, Contacte, Apeluri, Banca, Setari. */
public final class PhoneScreen extends FlowScreen {
    private enum Page { HOME, MSG, THREAD, CONTACTS, CALLS, BANK, SETTINGS, SIM, STORE, STORE_APP, CALC, NOTES, NOTE_EDIT, CLOCK, WEATHER, SNAKE }

    private static final int[] AVATAR = {0xFF2F6FB0, 0xFF3F7E8C, 0xFF4E7A8A, 0xFF5E5C9A, 0xFF8C7A3A, 0xFF4B5D7A, 0xFF2E8F66};
    /** 3 fundaluri intunecate + aceleasi 3 in varianta luminoasa (mod luminos). */
    private static final int[][] WALL = {{0xFF0E1A2E, 0xFF060A14}, {0xFF12283C, 0xFF08121E}, {0xFF1B2433, 0xFF0A0F18},
        {0xFFCFE6FA, 0xFF8FB8E0}, {0xFFE2EEF8, 0xFFA9C8E4}, {0xFFD6DEEA, 0xFF9AA8BD}};
    private static final String[] WALL_NAMES = {"Noapte", "Gheata", "Fiord"};
    /** Deschide telefonul direct intr-o conversatie SMS (de ex. cu autorul unui anunt CNN). Fara SIM activ se deschide telefonul cu mesajul de eroare. */
    public static void openThreadWith(String uuid, String name) {
        PhoneScreen ps = new PhoneScreen();
        Minecraft.getInstance().setScreen(ps);
        if (!PhoneState.simActive()) {
            ps.toast("Fara SIM activ nu poti trimite mesaje. Introdu o cartela din aplicatia SIM.", true);
            return;
        }
        ps.openThread(uuid, name);
    }

    /** Deschide telefonul direct in aplicatia indicata de o notificare (sms, call, news, ads, bank, jobs). */
    public static void openFor(String app) {
        if ("ads".equals(app) || "news".equals(app)) {
            ro.solarstudios.flowcity.news.AdsClient.requestInfo();
            ro.solarstudios.flowcity.news.AdsClient.requestList();
            Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.news.AdsScreen());
            return;
        }
        String id = "sms".equals(app) ? "messages" : "call".equals(app) ? "phone" : "bank".equals(app) ? "bank" : "news".equals(app) ? "cnn" : "jobs".equals(app) ? "jobs" : null;
        PhoneScreen ps = new PhoneScreen();
        Minecraft.getInstance().setScreen(ps);
        if (id == null) return;
        if (PhoneState.storeLoaded && !PhoneState.apps.contains(id)) {
            ps.toast("Aplicatia nu este instalata. O gasesti in App Store.", true);
            PhoneClient.requestStore();
            ps.openDetail(id);
            return;
        }
        ps.openApp(id);
    }

    private static int volumeStep = 2; // 0, 1(50%), 2(100%)

    private static final int W = 190;

    private Page page = Page.HOME;
    private long pageSince = System.currentTimeMillis();
    private int scroll;
    private int scrollMax;
    private String threadUuid = "";
    private String threadName = "";
    private final EditBox msgField;
    private final EditBox contactField;
    private boolean stickBottom;
    private final long born = System.currentTimeMillis();

    public PhoneScreen() {
        super("ePhone", W, 350);
        msgField = field("Scrie un mesaj...", 200, null);
        contactField = field("Nume sau #ID", 40, null);
        storeField = field("Cauta aplicatii", 30, v -> scroll = 0);
        noteField = field("Notita...", 400, null);
        PhoneClient.requestStore();
        if (lastPage != Page.HOME && PhoneState.apps.contains(appOf(lastPage))) {
            page = lastPage;
            if (page == Page.NOTES) PhoneClient.requestNotes();
        }
        PhoneClient.requestThreads();
        PhoneClient.requestContacts();
        PhoneClient.requestCalls();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<EditBox> fields() {
        if (page == Page.THREAD) return Collections.singletonList(msgField);
        if (page == Page.CONTACTS) return Collections.singletonList(contactField);
        if (page == Page.STORE) return Collections.singletonList(storeField);
        if (page == Page.NOTE_EDIT) return Collections.singletonList(noteField);
        return Collections.emptyList();
    }

    @Override
    protected void onEnter(EditBox f) {
        if (f == msgField) sendMsg();
        else if (f == contactField) addContact();
        else if (f == noteField) saveNote();
    }

    public boolean viewingThread(String uuid) {
        return page == Page.THREAD && threadUuid.equals(uuid);
    }

    public void threadLoaded() {
        stickBottom = true;
    }

    /** Ultima pagina "simpla" deschisa: telefonul se redeschide acolo (fara pierderea starii). */
    private static Page lastPage = Page.HOME;

    private void go(Page p) {
        lastPage = p == Page.STORE || p == Page.CALC || p == Page.NOTES || p == Page.CLOCK || p == Page.WEATHER || p == Page.SETTINGS ? p : Page.HOME;
        if (p != Page.HOME) homeFade = false;
        if (p != Page.HOME && editMode) exitEdit();
        page = p;
        pageSince = System.currentTimeMillis();
        scroll = 0;
    }

    private void sendMsg() {
        String t = msgField.getValue().trim();
        if (t.isEmpty() || threadUuid.isEmpty()) return;
        PhoneClient.sendMessage(threadUuid, t);
        msgField.setValue("");
        PhoneState.Msg m = new PhoneState.Msg();
        m.mine = true;
        m.text = t;
        m.ts = System.currentTimeMillis();
        PhoneState.thread.add(m);
        stickBottom = true;
    }

    private void addContact() {
        String q = contactField.getValue().trim();
        if (q.isEmpty()) return;
        PhoneClient.addContact(q);
        contactField.setValue("");
        toast("Se adauga contactul...", false);
    }

    private void openThread(String uuid, String name) {
        threadUuid = uuid;
        threadName = name;
        PhoneState.thread.clear();
        PhoneClient.requestThread(uuid);
        msgField.setValue("");
        go(Page.THREAD);
        stickBottom = true;
    }

    // ------------------------------------------------------------------ stil iarna

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    @Override
    protected void button(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                          boolean enabled, int color, Runnable action) {
        boolean hover = enabled && Ui.in(mx, my, x, y, w, h);
        int bg = !enabled ? 0xFF14202F : (hover ? Theme.lighten(color, 16) : color);
        Sk.slant(ms, x, y, w, h, Math.min(5f, h / 3f), bg);
        int fg = !enabled ? Sk.FAINT : (luma(bg) > 125 ? 0xFF061426 : Sk.WHITE);
        Gfx.text(ms, clipG(label, 8f, true, w - 10), x + w / 2f + 1, y + h / 2f + 3f, 8f, fg, 1, true, 0.2f);
        if (enabled) hit(x, y, w, h, action);
    }

    @Override
    protected void drawField(GuiGraphics ms, EditBox f, int x, int y, int w, int h, int mx, int my) {
        Sk.slant(ms, x, y, w, h, 4f, 0xFF0A1420);
        Ui.rect(ms, x + 4, y + h - 1, w - 8, 1, f.isFocused() ? Sk.ACC : 0xFF22324A);
        f.setX(x + 9);
        f.setY(y + (h - 8) / 2);
        f.setWidth(w - 16);
        f.render(ms, mx, my, 0);
    }

    private static void avatarBox(GuiGraphics ms, float x, float y, float size, int color) {
        Sk.cut(ms, x, y, size, size, size / 4f, color);
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float appear = Math.min(1f, (now - born) / 220f);
        float open = 1f - (1f - appear) * (1f - appear) * (1f - appear);
        Sk.background(ms, 0, 0, width, height, open);
        int ph2 = Math.min(height - 36, 356);
        int x = (width - W) / 2;
        int y = (height - ph2) / 2 + 2 + Math.round((1f - appear) * 40f);
        Gfx.alpha = open;
        try {
            chassis(ms, x, y, ph2);
            int sx = x, sy = y, sw = W, sh = ph2;
            wallpaper(ms, sx, sy, sw, sh, now);
            if (page != Page.HOME && page != Page.CALC && page != Page.CLOCK && page != Page.WEATHER && page != Page.SNAKE && PhoneState.call == null) {
                Ui.rect(ms, sx, sy, sw, sh, PhoneState.light ? 0xD0060B14 : 0x8C060A12); // paginile aplicatiilor raman lizibile
            }
            pw = sw;
            ph = sh;
            px = sx;
            py = sy;

            PhoneState.Call call = PhoneState.call;
            if (call != null) {
                callScreen(ms, sx, sy, sw, sh, mx, my, call, now);
            } else {
                float te = Math.min(1f, (now - pageSince) / 200f);
                float ease = 1f - (1f - te) * (1f - te) * (1f - te);
                boolean zoom = page != Page.HOME && openFromX >= 0 && ease < 1f;
                if (zoom) {
                    float sc = 0.15f + 0.85f * ease;
                    float zx = openFromX + (sx + sw / 2f - openFromX) * ease;
                    float zy = openFromY + (sy + sh / 2f - openFromY) * ease;
                    ms.pose().pushPose();
                    ms.pose().translate(zx, zy, 0);
                    ms.pose().scale(sc, sc, 1f);
                    ms.pose().translate(-(sx + sw / 2f), -(sy + sh / 2f), 0);
                } else if (page != Page.HOME) {
                    openFromX = -1f;
                }
                switch (page) {
                    case HOME: home(ms, sx, sy, sw, sh, mx, my, now); break;
                    case STORE: store(ms, sx, sy, sw, sh, mx, my); break;
                    case STORE_APP: storeApp(ms, sx, sy, sw, sh, mx, my); break;
                    case CALC: calc(ms, sx, sy, sw, sh, mx, my); break;
                    case NOTES: notes(ms, sx, sy, sw, sh, mx, my); break;
                    case NOTE_EDIT: noteEdit(ms, sx, sy, sw, sh, mx, my); break;
                    case CLOCK: clock(ms, sx, sy, sw, sh, mx, my); break;
                    case WEATHER: weather(ms, sx, sy, sw, sh, mx, my); break;
                    case SNAKE: snake(ms, sx, sy, sw, sh, mx, my, now); break;
                    case MSG: messages(ms, sx, sy, sw, sh, mx, my); break;
                    case THREAD: thread(ms, sx, sy, sw, sh, mx, my); break;
                    case CONTACTS: contacts(ms, sx, sy, sw, sh, mx, my); break;
                    case CALLS: calls(ms, sx, sy, sw, sh, mx, my); break;
                    case BANK: bank(ms, sx, sy, sw, sh, mx, my); break;
                    case SETTINGS: settings(ms, sx, sy, sw, sh, mx, my); break;
                    case SIM: sim(ms, sx, sy, sw, sh, mx, my); break;
                    default: break;
                }
                if (zoom) {
                    ms.pose().popPose();
                    Ui.rect(ms, sx, sy, sw, sh, ((int) ((1f - ease) * 200) << 24) | 0x060A12);
                }
                if (page == Page.HOME && te < 1f && homeFade) Ui.rect(ms, sx, sy, sw, sh, ((int) ((1f - ease) * 150) << 24) | 0x060A12);
                if (page == Page.HOME) closeAnim(ms, sx, sy, sw, sh, now);
                confirmDialog(ms, sx, sy, sw, sh, mx, my);
            }
            pollIsland(now);
            statusBar(ms, sx, sy, sw, now);
            island(ms, sx, sy, sw, now, call);
            // colturile ecranului (masca) si bara home
            screenCorners(ms, sx, sy, sw, sh, SCREEN_R, 0xFF07080B);
            boolean darkBar = PhoneState.light && page == Page.HOME && call == null;
            Gfx.rrect(ms, sx + sw / 2f - 27, sy + sh - 6, 54, 3, 1.5f, darkBar ? 0xCC10243C : 0xDDF2F7FF);
            if (call == null && page != Page.HOME) hit(sx + sw / 2 - 34, sy + sh - 12, 68, 12, this::goHome);
            if (call == null && page == Page.HOME && editMode) hit(sx + sw / 2 - 34, sy + sh - 12, 68, 12, this::exitEdit);
        } finally {
            Gfx.alpha = 1f;
        }
    }

    private boolean homeFade;

    private static String appOf(Page p) {
        switch (p) {
            case STORE: return "store";
            case CALC: return "calc";
            case NOTES: return "notes";
            case CLOCK: return "clock";
            case WEATHER: return "weather";
            case SNAKE: return "snake";
            default: return "settings";
        }
    }

    /** Iconita spre care se strange aplicatia la inchidere (animatia inversa deschiderii). */
    private float closeToX = -1f;
    private float closeToY = -1f;
    private String closeAppId;
    private long closeAt;

    private void goHome() {
        if (page != Page.HOME && closeToX >= 0f) closeAt = System.currentTimeMillis();
        else closeAt = 0L;
        go(Page.HOME);
        homeFade = true;
    }

    /** Cardul aplicatiei care se strange spre iconita ei, dupa inchidere. */
    private void closeAnim(GuiGraphics ms, int sx, int sy, int sw, int sh, long now) {
        if (closeAt == 0L || closeToX < 0f) return;
        float t = (now - closeAt) / 220f;
        if (t >= 1f) {
            closeAt = 0L;
            return;
        }
        float e = 1f - (1f - t) * (1f - t) * (1f - t);
        float k = 1f - e;
        float w = ICON + (sw - ICON) * k;
        float h = ICON + (sh - ICON) * k;
        float cx = closeToX + (sx + sw / 2f - closeToX) * k;
        float cy = closeToY + (sy + sh / 2f - closeToY) * k;
        PhoneApps.App a = closeAppId == null ? null : PhoneApps.get(closeAppId);
        int c1 = a == null ? 0xFF161C28 : a.color;
        int c2 = a == null ? 0xFF0B0F18 : a.color2;
        float al = 1f - e * 0.85f;
        float r = ICON * 0.235f + (SCREEN_R - ICON * 0.235f) * k;
        Gfx.rrect(ms, cx - w / 2f, cy - h / 2f, w, h, r, Gfx.fade(c1, al * 0.9f), Gfx.fade(c2, al * 0.9f));
    }

    /** Masca pentru colturile rotunjite ale ecranului (evantai din fiecare colt pana la arc). */
    private static void screenCorners(GuiGraphics ms, float x, float y, float w, float h, float r, int col) {
        float[][] c = {{x, y, x + r, y + r, 180}, {x + w, y, x + w - r, y + r, 270}, {x + w, y + h, x + w - r, y + h - r, 0}, {x, y + h, x + r, y + h - r, 90}};
        for (float[] k : c) {
            int n = 10;
            float[] pts = new float[(n + 2) * 2];
            pts[0] = k[0];
            pts[1] = k[1];
            for (int i = 0; i <= n; i++) {
                double a = Math.toRadians(k[4] + 90.0 * i / n);
                pts[2 + i * 2] = k[2] + (float) Math.cos(a) * r;
                pts[3 + i * 2] = k[3] + (float) Math.sin(a) * r;
            }
            Gfx.poly(ms, pts, col);
        }
    }

    private void wallpaper(GuiGraphics ms, int x, int y, int w, int h, long now) {
        int[] c = WALL[(PhoneState.wallpaper % 3) + (PhoneState.light ? 3 : 0)];
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            int y0 = y + h * i / steps;
            int y1 = y + h * (i + 1) / steps;
            Ui.rect(ms, x, y0, w, Math.max(1, y1 - y0), Theme.mix(c[0], c[1], i / (float) (steps - 1)));
        }
        // granulatie fina, fixa (nu se misca), ca sa nu para un gradient "de program"
        int seed = 7 + PhoneState.wallpaper * 131;
        for (int i = 0; i < 260; i++) {
            seed = seed * 1103515245 + 12345;
            int gx = x + ((seed >>> 8) & 0x7FFF) % w;
            seed = seed * 1103515245 + 12345;
            int gy = y + ((seed >>> 8) & 0x7FFF) % h;
            Ui.rect(ms, gx, gy, 1, 1, (i & 1) == 0 ? 0x0AB8D8FF : 0x14000000);
        }
        if (Xmas.enabled) {
            for (int i = 0; i < 18; i++) {
                float t = ((now / 40f) + i * 53f) % h;
                int fx = x + (int) ((i * 37 + Math.sin((now / 700.0) + i) * 6) % w);
                if (fx < x) fx += w;
                Ui.rect(ms, fx, y + (int) t, 1, 1, 0xCCEAF4FF);
            }
        }
    }

    private int homeText() {
        return PhoneState.light && page == Page.HOME && PhoneState.call == null ? 0xFF10243C : Sk.WHITE;
    }

    private String simLabel() {
        if ("NONE".equals(PhoneState.simStatus)) return "FARA SIM";
        if ("BLOCKED".equals(PhoneState.simStatus)) return "SIM BLOCAT";
        if (PhoneState.signal <= 0) return "FARA SEMNAL";
        return PhoneState.operator.isEmpty() ? "SIM ACTIV" : PhoneState.operator.toUpperCase();
    }

    /** Raza colturilor ecranului; rama urmeaza aceeasi curba, cu 4px mai mult. */
    private static final float SCREEN_R = 19f;
    /** Latimea ramei in jurul ecranului. */
    private static final float BEZEL = 4.5f;

    /**
     * Corpul telefonului: rama de titan gri inchis, mat, cu muchii drepte si colturi foarte rotunjite,
     * butoanele laterale (actiune, volum, pornire) si ecranul incastrat, aproape fara margini.
     */
    private void chassis(GuiGraphics ms, int x, int y, int sh) {
        float bx = x - BEZEL;
        float by = y - BEZEL;
        float bw = W + BEZEL * 2f;
        float bh = sh + BEZEL * 2f;
        float r = SCREEN_R + BEZEL;
        // butoane laterale: actiune + volum pe stanga, pornire pe dreapta
        sideButton(ms, bx - 2.2f, by + 34f, 12f);
        sideButton(ms, bx - 2.2f, by + 54f, 20f);
        sideButton(ms, bx - 2.2f, by + 78f, 20f);
        sideButton(ms, bx + bw - 0.8f, by + 62f, 32f);
        // umbra, muchia exterioara si fata de titan (gradient vertical fin, mat)
        Gfx.rrect(ms, bx + 1f, by + 3.5f, bw, bh, r, 0x55000000);
        Gfx.rrect(ms, bx - 1.2f, by - 1.2f, bw + 2.4f, bh + 2.4f, r + 1.2f, 0xFF0C0E12);
        Gfx.rrect(ms, bx, by, bw, bh, r, 0xFF5A6068, 0xFF2B2F36);
        Gfx.rrect(ms, bx + 1.1f, by + 1.1f, bw - 2.2f, bh - 2.2f, r - 1.1f, 0xFF474C55, 0xFF23262C);
        // luciu scurt pe muchia de sus si pe cea de jos (titan satinat, fara reflexii puternice)
        Gfx.rrect(ms, bx + r * 0.5f, by + 1.4f, bw - r, 1.1f, 0.55f, 0x4CFFFFFF);
        Gfx.rrect(ms, bx + r * 0.5f, by + bh - 2.4f, bw - r, 1.1f, 0.55f, 0x1AFFFFFF);
        // rama neagra din jurul ecranului
        Gfx.rrect(ms, x - 1.6f, y - 1.6f, W + 3.2f, sh + 3.2f, SCREEN_R + 1.6f, 0xFF07080B);
    }

    /** Buton lateral de titan (bara scurta, putin mai deschisa decat rama). */
    private static void sideButton(GuiGraphics ms, float x, float y, float h) {
        Gfx.rrect(ms, x, y, 3f, h, 1.4f, 0xFF6A7079, 0xFF3A3F47);
        Gfx.rrect(ms, x + 0.6f, y + 1f, 1f, h - 2f, 0.5f, 0x33FFFFFF);
    }

    private void statusBar(GuiGraphics ms, int x, int y, int w, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        int tc = homeText();
        Gfx.text(ms, t, x + 14, y + 11.5f, 7.8f, tc, 0, true, 0.2f);
        boolean ok = PhoneState.simActive();
        int bars = ok ? Math.max(1, Math.min(4, PhoneState.signal)) : 0;
        // semnal: patru bare cu colturi rotunde, ca in iOS
        for (int i = 0; i < 4; i++) {
            float bh = 2f + i * 1.6f;
            Gfx.rrect(ms, x + w - 36f + i * 3.4f, y + 9.4f - bh, 2.4f, bh, 1.1f, i < bars ? tc : Gfx.fade(tc, 0.28f));
        }
        // baterie (doar vizuala: modul nu are sistem de baterie)
        float bxp = x + w - 20f;
        Gfx.rrect(ms, bxp, y + 3.4f, 13.5f, 6.6f, 2.2f, Gfx.fade(tc, 0.45f));
        Gfx.rrect(ms, bxp + 1f, y + 4.4f, 9.6f, 4.6f, 1.5f, tc);
        Gfx.rrect(ms, bxp + 14.2f, y + 5.2f, 1.5f, 3f, 0.7f, Gfx.fade(tc, 0.45f));
    }

    // ------------------------------------------------------------------ Dynamic Island

    private float islW = 46f;
    private float islH = 11f;
    private int lastUnread = -1;
    private int lastMissed = -1;
    private int lastNotify = -1;
    private String islandMsg = "";
    private int islandMsgColor;
    private long islandMsgUntil;

    /** Arata o pastila scurta in insula (notificare). */
    private void islandFlash(String text, int color) {
        islandMsg = text;
        islandMsgColor = color;
        islandMsgUntil = System.currentTimeMillis() + 2600;
    }

    /** Urmareste contoarele reale si aprinde insula cand apare ceva nou cat telefonul e deschis. */
    private void pollIsland(long now) {
        int un = PhoneState.unread;
        int ms2 = PhoneState.missed;
        int nt = ro.solarstudios.flowcity.notify.NotifyState.unread;
        if (lastUnread >= 0 && un > lastUnread) islandFlash("Mesaj nou", 0xFF34C759);
        else if (lastMissed >= 0 && ms2 > lastMissed) islandFlash("Apel pierdut", 0xFFFF453A);
        else if (lastNotify >= 0 && nt > lastNotify) islandFlash("Notificare noua", Sk.ACC);
        lastUnread = un;
        lastMissed = ms2;
        lastNotify = nt;
    }

    /**
     * Pastila neagra din partea de sus a ecranului. Compacta cand nu se intampla nimic; se extinde pentru
     * apelul in curs (nume + cronometru) si pentru o notificare proaspata; lipsa cartelei apare ca punct rosu.
     */
    private void island(GuiGraphics ms, int sx, int sy, int sw, long now, PhoneState.Call call) {
        boolean flash = now < islandMsgUntil;
        float tw;
        float th;
        int mode;
        if (call != null) {
            mode = 3;
            tw = Math.min(sw - 26f, 152f);
            th = 26f;
        } else if (flash) {
            mode = 2;
            tw = Math.min(sw - 34f, Math.max(76f, Gfx.width(islandMsg, 6.4f, false) + 36f));
            th = 17f;
        } else {
            mode = PhoneState.simActive() ? 0 : 1;
            tw = 46f;
            th = 10f;
        }
        islW += (tw - islW) * 0.26f;
        islH += (th - islH) * 0.26f;
        if (Math.abs(tw - islW) < 0.2f) islW = tw;
        if (Math.abs(th - islH) < 0.2f) islH = th;
        float cx = sx + sw / 2f;
        float ix = cx - islW / 2f;
        float iy = sy + 2.5f;
        float r = islH / 2f;
        Gfx.rrect(ms, ix - 0.8f, iy - 0.8f, islW + 1.6f, islH + 1.6f, r + 0.8f, 0x66000000);
        Gfx.rrect(ms, ix, iy, islW, islH, r, 0xFF000000);
        float pad = r + 2f;
        float my2 = iy + islH / 2f;
        switch (mode) {
            case 3: {
                int ac = avatarColor(call.with.name);
                Gfx.rrect(ms, ix + 5f, my2 - 7f, 14f, 14f, 4.5f, Gfx.mixArgb(ac, 0xFFFFFFFF, 0.12f), Gfx.mixArgb(ac, 0xFF000000, 0.3f));
                String ini = call.with.name == null || call.with.name.isEmpty() ? "?" : call.with.name.substring(0, 1).toUpperCase();
                Gfx.text(ms, ini, ix + 12f, my2 + 3f, 8f, Sk.WHITE, 1, true, 0f);
                float tx = ix + 23f;
                float rw = islW - 29f - 34f;
                Gfx.text(ms, clipG(call.with.name, 7f, true, Math.max(10f, rw)), tx, my2 - 0.5f, 7f, Sk.WHITE, 0, true, 0.1f);
                Gfx.text(ms, call.active ? "Apel activ" : (call.incoming ? "Apel primit" : "Se formeaza"), tx, my2 + 8f, 5.8f,
                    call.active ? GREEN : Sk.MUTED, 0, false, 0.1f);
                if (call.active) {
                    Gfx.text(ms, clock((now - call.since) / 1000), ix + islW - 7f, my2 + 3f, 7.6f, GREEN, 2, true, 0.3f);
                } else {
                    // mic indicator care pulseaza cat timp suna
                    for (int i = 0; i < 3; i++) {
                        float ph3 = (float) Math.abs(Math.sin(now / 240.0 + i * 0.6));
                        Gfx.rrect(ms, ix + islW - 20f + i * 4.6f, my2 - 1f - ph3 * 3.4f, 2.6f, 2f + ph3 * 6.8f, 1.2f,
                            Gfx.fade(GREEN, 0.4f + 0.6f * ph3));
                    }
                }
                break;
            }
            case 2: {
                Gfx.disc(ms, ix + pad + 1f, my2, 3f, islandMsgColor);
                Gfx.text(ms, clipG(islandMsg, 6.4f, false, islW - pad * 2f - 12f), ix + pad + 8f, my2 + 2.4f, 6.4f, Sk.WHITE, 0, false, 0.1f);
                break;
            }
            case 1:
            default: {
                // lipsa cartelei: punct rosu in stanga pastilei (detaliile sunt in aplicatia SIM)
                if (mode == 1) Gfx.disc(ms, ix + 7f, my2, 2.3f, Sk.BAD);
                // obiectivul camerei, in dreapta pastilei
                Gfx.disc(ms, ix + islW - 6.5f, my2, 3.1f, 0xFF14181F);
                Gfx.disc(ms, ix + islW - 6.5f, my2, 1.9f, 0xFF1C2F52);
                Gfx.disc(ms, ix + islW - 7.2f, my2 - 0.7f, 0.7f, 0x99FFFFFF);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ ecran principal (launcher)

    private static final int COLS = 4;
    private static final int ROW_H = 54;
    private static final int ICON = 30;

    /** Pagina curenta a ecranului principal (static: ramane la redeschiderea telefonului). */
    private static int homePage;
    private float pageX = -1f;
    private boolean editMode;
    private boolean orderDirty;
    private String editSel;
    private String confirmRemove;
    private String confirmBuy;
    private boolean pressing;
    private double pressX;
    private double pressY;
    private long pressAt;
    private String pressApp;
    private float dragDx;
    private boolean dragged;
    private float openFromX = -1f;
    private float openFromY = -1f;
    private int homeX;
    private int homeW = W;
    private int gridTopY;
    private int gridBottomY;
    private int editDoneX;
    private int editDoneY;

    private static final class IconRect {
        final String id;
        final int x;
        final int y;
        final int w;
        final int h;
        final int ix;
        final int iy;

        IconRect(String id, int x, int y, int w, int h, int ix, int iy) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.ix = ix;
            this.iy = iy;
        }
    }

    private final List<IconRect> icons = new ArrayList<>();

    private static int gridTop(int page, int y) {
        return page == 0 ? y + 100 : y + 34;
    }

    private void home(GuiGraphics ms, int x, int y, int w, int h, int mx, int my, long now) {
        icons.clear();
        homeX = x;
        homeW = w;
        List<String> dock = new ArrayList<>();
        List<String> grid = new ArrayList<>();
        for (int i = 0; i < PhoneState.apps.size(); i++) (i < 4 ? dock : grid).add(PhoneState.apps.get(i));
        int dockY = y + h - 54;
        int dotsY = dockY - 9;
        List<List<String>> pages = new ArrayList<>();
        int idx = 0;
        int p = 0;
        do {
            int rows = Math.max(1, (dotsY - 4 - gridTop(p, y)) / ROW_H);
            int n = rows * COLS;
            pages.add(new ArrayList<>(grid.subList(Math.min(idx, grid.size()), Math.min(grid.size(), idx + n))));
            idx += n;
            p++;
        } while (idx < grid.size());
        if (editMode && !pages.get(pages.size() - 1).isEmpty()) pages.add(new ArrayList<>());
        homePage = Math.max(0, Math.min(homePage, pages.size() - 1));
        if (pageX < 0) pageX = homePage;
        pageX += (homePage - pageX) * 0.22f;
        if (Math.abs(homePage - pageX) < 0.002f) pageX = homePage;
        float off = pageX * w - dragDx;
        gridTopY = y + 16;
        gridBottomY = dotsY - 2;

        Ui.scissor(x, y + 16, w, dotsY - 2 - (y + 16));
        for (int pg = 0; pg < pages.size(); pg++) {
            int px0 = Math.round(x + pg * w - off);
            if (px0 >= x + w || px0 + w <= x) continue;
            if (pg == 0) clockWidget(ms, px0, y, w, now);
            int cellW = (w - 12) / COLS;
            int gy = gridTop(pg, y);
            List<String> list = pages.get(pg);
            for (int k = 0; k < list.size(); k++) {
                appIcon(ms, list.get(k), px0 + 6 + (k % COLS) * cellW, gy + (k / COLS) * ROW_H, cellW, mx, my, true, now);
            }
            if (editMode && list.isEmpty()) {
                Gfx.text(ms, editSel == null ? "Pagina noua" : "Apasa aici ca sa muti aplicatia", px0 + w / 2f, gy + 40, 6.6f, homeMuted(), 1, false, 0.3f);
            }
        }
        Ui.endScissor();

        // indicator pagini
        if (pages.size() > 1) {
            float dx0 = x + w / 2f - (pages.size() - 1) * 4f;
            for (int i = 0; i < pages.size(); i++) {
                boolean on = i == homePage;
                int dc2 = on ? homeText() : (PhoneState.light ? 0x5510243C : 0x55FFFFFF);
                if (on) Gfx.rrect(ms, dx0 + i * 8f - 3.4f, dotsY - 1.7f, 6.8f, 3.4f, 1.7f, dc2);
                else Gfx.disc(ms, dx0 + i * 8f, dotsY, 1.7f, dc2);
            }
        }

        // dock (sticla mata)
        Gfx.rrect(ms, x + 6, dockY, w - 12, 44, 17f, PhoneState.light ? 0x59FFFFFF : 0x33FFFFFF, PhoneState.light ? 0x3DFFFFFF : 0x1AFFFFFF);
        Gfx.rrect(ms, x + 6.8f, dockY + 0.8f, w - 13.6f, 1f, 0.5f, PhoneState.light ? 0x66FFFFFF : 0x3DFFFFFF);
        int dc = (w - 12) / 4;
        for (int k = 0; k < dock.size(); k++) appIcon(ms, dock.get(k), x + 6 + k * dc, dockY + 7, dc, mx, my, false, now);

        if (editMode) {
            Gfx.rrect(ms, x + 6, y + 16, w - 12, 15, 7f, 0xCC0A1220);
            Gfx.text(ms, editSel == null ? "Atinge o aplicatie ca s-o muti" : "Atinge locul unde o muti", x + 13, y + 26, 6.2f, Sk.MUTED, 0, false, 0.2f);
            editDoneX = x + w - 44;
            editDoneY = y + 18;
            boolean hv = Ui.in(mx, my, editDoneX, editDoneY, 34, 11);
            Gfx.rrect(ms, editDoneX, editDoneY, 34, 11, 5.5f, hv ? Theme.lighten(Sk.ACC, 20) : Sk.ACC);
            Gfx.text(ms, "GATA", editDoneX + 17, editDoneY + 8.2f, 6.2f, 0xFF061426, 1, true, 0.4f);
        }

        // apasare lunga -> mod editare
        if (pressing && !dragged && pressApp != null && !editMode && now - pressAt > 450) {
            pressing = false;
            editMode = true;
            editSel = null;
            FlowSounds.play("notify", 0.6F);
        }
    }

    private int homeMuted() {
        return PhoneState.light ? 0xFF2A4A6E : Sk.MUTED;
    }

    private void exitEdit() {
        editMode = false;
        editSel = null;
        if (orderDirty) {
            orderDirty = false;
            PhoneClient.saveOrder();
        }
    }

    private void clockWidget(GuiGraphics ms, int x, int y, int w, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        String[] days = {"Duminica", "Luni", "Marti", "Miercuri", "Joi", "Vineri", "Sambata"};
        String[] months = {"ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "noi", "dec"};
        int cx = x + 8;
        int cy = y + 34;
        int cw = w - 16;
        Gfx.rrect(ms, cx, cy, cw, 60, 14f, PhoneState.light ? 0x88FFFFFF : 0x30FFFFFF, PhoneState.light ? 0x55FFFFFF : 0x14FFFFFF);
        Gfx.text(ms, t, cx + 10, cy + 28, 22f, homeText(), 0, true, 0.6f);
        Gfx.text(ms, days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(java.util.Calendar.DAY_OF_MONTH) + " " + months[cal.get(java.util.Calendar.MONTH)],
            cx + 11, cy + 42, 6.6f, homeMuted(), 0, false, 0.3f);
        String line = PhoneState.simInstalled == null ? "Fara SIM" : PhoneState.myNumber;
        Gfx.text(ms, line, cx + 11, cy + 53, 6.2f, PhoneState.simInstalled == null ? Sk.BAD : (PhoneState.light ? 0xFF1E4C80 : Sk.ACC), 0, false, 0.3f);
        // vremea din lume, in dreapta
        Minecraft mc = Minecraft.getInstance();
        int cond = weatherCond(mc);
        weatherGlyph(ms, cx + cw - 26, cy + 22, cond, 1f);
        Gfx.text(ms, WEATHER_NAMES[cond], cx + cw - 26, cy + 46, 6.2f, homeMuted(), 1, false, 0.2f);
        String gt = gameTime(mc);
        Gfx.text(ms, gt, cx + cw - 26, cy + 55, 6f, homeMuted(), 1, false, 0.2f);
    }

    private int badge(String id) {
        switch (id) {
            case "messages": return PhoneState.unread;
            case "phone": return PhoneState.missed;
            case "notify": return ro.solarstudios.flowcity.notify.NotifyState.unread;
            default: return 0;
        }
    }

    private static boolean locked(PhoneApps.App a) {
        return a.needsSim && !PhoneState.simActive();
    }

    /** Iconita rotunjita cu gradient (desenata din cod), eticheta, badge, progres de instalare, mod editare. */
    private void appIcon(GuiGraphics ms, String id, int cx, int cy, int cell, int mx, int my, boolean label, long now) {
        PhoneApps.App a = PhoneApps.get(id);
        if (a == null) return;
        float ox = cx + (cell - ICON) / 2f;
        float oy = cy;
        if (editMode) {
            int hsh = id.hashCode();
            ox += (float) Math.sin(now / 55.0 + hsh) * 0.9f;
            oy += (float) Math.cos(now / 61.0 + hsh * 0.7) * 0.7f;
        }
        float prog = PhoneState.installProgress(id);
        boolean hover = !editMode && prog < 0 && Ui.in(mx, my, cx, cy, cell, label ? 46 : 32) && my >= gridTopY;
        boolean press = pressing && !dragged && id.equals(pressApp);
        if (press) {
            ox += 1.5f;
            oy += 1.5f;
        }
        float sz = press ? ICON - 3 : ICON;
        boolean lock = locked(a);
        float ir = sz * 0.235f; // raza de squircle, ca pe iOS
        Gfx.rrect(ms, ox + 0.4f, oy + 1.6f, sz, sz, ir, 0x4D000000); // umbra
        int c1 = hover ? Theme.lighten(a.color, 14) : a.color;
        Gfx.rrect(ms, ox, oy, sz, sz, ir, c1, a.color2);
        Gfx.rrect(ms, ox + 1.2f, oy + 0.9f, sz - 2.4f, sz * 0.46f, ir - 1.2f, 0x26FFFFFF, 0x00FFFFFF); // luciu
        Gfx.rrect(ms, ox + 0.5f, oy + 0.5f, sz - 1f, sz - 1f, ir - 0.5f, 0x1FFFFFFF, 0x0A000000); // muchie
        ms.pose().pushPose();
        float scl = sz / ICON;
        ms.pose().translate(ox + sz / 2f, oy + sz / 2f, 0);
        ms.pose().scale(scl, scl, 1f);
        glyph(ms, id, 0, 0, a);
        ms.pose().popPose();
        if (lock || prog >= 0) Gfx.rrect(ms, ox, oy, sz, sz, ir, 0x99060A12);
        if (lock) lockGlyph(ms, ox + sz / 2f, oy + sz / 2f);
        if (prog >= 0) {
            Gfx.ring(ms, ox + sz / 2f, oy + sz / 2f, 7f, 9f, 0x55FFFFFF, 0x55FFFFFF);
            Gfx.sector(ms, ox + sz / 2f, oy + sz / 2f, 7f, 9f, -90f, -90f + 360f * prog, Sk.WHITE, Sk.WHITE);
        }
        int b = badge(id);
        if (b > 0 && prog < 0) {
            String s = b > 9 ? "9+" : String.valueOf(b);
            Gfx.disc(ms, ox + sz - 1, oy + 1, 5.5f, 0xFFFF3B30);
            Gfx.text(ms, s, ox + sz - 1, oy + 3.6f, 6f, Sk.WHITE, 1, true, 0f);
        }
        if (editMode && !a.core) {
            Gfx.disc(ms, ox + 1, oy + 1, 5f, 0xEE3A404C);
            Gfx.line(ms, ox - 1.5f, oy + 1, ox + 3.5f, oy + 1, 1.4f, Sk.WHITE);
        }
        if (editMode && id.equals(editSel)) {
            Gfx.ring(ms, ox + sz / 2f, oy + sz / 2f, sz / 2f + 2f, sz / 2f + 3.2f, Sk.ACC, Sk.ACC);
        }
        if (label) {
            String n = prog >= 0 ? "Se instaleaza" : a.name;
            Gfx.textS(ms, n, cx + cell / 2f, oy + ICON + 9, 6.2f, lock ? (PhoneState.light ? 0xFF6A7A90 : Sk.FAINT) : homeText(), 1, false, 0.1f);
        }
        if (prog < 0) icons.add(new IconRect(id, cx, cy - 2, cell, label ? 48 : 34, Math.round(ox), Math.round(oy)));
    }

    private void lockGlyph(GuiGraphics ms, float cx, float cy) {
        Gfx.ring(ms, cx, cy - 2.5f, 2.6f, 4f, Sk.WHITE, Sk.WHITE);
        Gfx.rrect(ms, cx - 5f, cy - 1f, 10f, 8f, 2f, Sk.WHITE);
        Gfx.disc(ms, cx, cy + 2.5f, 1.2f, 0xFF39414E);
    }

    /** Simbolul alb al aplicatiei, centrat in (cx, cy), pentru o iconita de 30px. */
    private void glyph(GuiGraphics ms, String id, float cx, float cy, PhoneApps.App a) {
        int c = Sk.WHITE;
        switch (id) {
            case "phone":
                Gfx.line(ms, cx - 5f, cy - 5f, cx + 5f, cy + 5f, 3.6f, c);
                Gfx.disc(ms, cx - 5.5f, cy - 5.5f, 3.2f, c);
                Gfx.disc(ms, cx + 5.5f, cy + 5.5f, 3.2f, c);
                break;
            case "messages":
                Gfx.rrect(ms, cx - 9f, cy - 7f, 18f, 13f, 6f, c);
                Gfx.poly(ms, new float[] {cx - 6f, cy + 3f, cx - 2f, cy + 5f, cx - 8f, cy + 9f}, c);
                break;
            case "contacts":
                Gfx.disc(ms, cx, cy - 3.5f, 4f, c);
                Gfx.rrect(ms, cx - 7.5f, cy + 2f, 15f, 7f, 3.5f, c);
                break;
            case "store":
                Gfx.line(ms, cx - 6f, cy + 7f, cx, cy - 7f, 2.6f, c);
                Gfx.line(ms, cx + 6f, cy + 7f, cx, cy - 7f, 2.6f, c);
                Gfx.line(ms, cx - 8f, cy + 2.5f, cx + 8f, cy + 2.5f, 2.4f, c);
                break;
            case "settings":
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * i / 4.0;
                    Gfx.line(ms, cx + (float) Math.cos(ang) * 5f, cy + (float) Math.sin(ang) * 5f, cx + (float) Math.cos(ang) * 9f, cy + (float) Math.sin(ang) * 9f, 3f, c);
                }
                Gfx.disc(ms, cx, cy, 6.5f, c);
                Gfx.disc(ms, cx, cy, 2.6f, a.color2);
                break;
            case "sim":
                Gfx.poly(ms, new float[] {cx - 6f, cy - 9f, cx + 3f, cy - 9f, cx + 7f, cy - 5f, cx + 7f, cy + 9f, cx - 6f, cy + 9f}, c);
                Gfx.rrect(ms, cx - 3.5f, cy - 2f, 7f, 7f, 1.5f, 0xFFE8C25A);
                break;
            case "cnn":
                Gfx.text(ms, "CNN", cx, cy + 3f, 9f, c, 1, true, 0.2f);
                break;
            case "ads":
                Gfx.poly(ms, new float[] {cx - 8f, cy, cx - 3f, cy - 7f, cx + 8f, cy - 7f, cx + 8f, cy + 7f, cx - 3f, cy + 7f}, c);
                Gfx.disc(ms, cx - 2.5f, cy, 1.8f, a.color2);
                Gfx.text(ms, "$", cx + 3.5f, cy + 3f, 7.5f, a.color2, 1, true, 0f);
                break;
            case "bank":
                Gfx.poly(ms, new float[] {cx - 9f, cy - 3f, cx, cy - 9f, cx + 9f, cy - 3f}, c);
                for (int i = 0; i < 3; i++) Gfx.rrect(ms, cx - 6.5f + i * 5f, cy - 1.5f, 3f, 7f, 0.8f, c);
                Gfx.rrect(ms, cx - 9f, cy + 6f, 18f, 2.5f, 1f, c);
                break;
            case "notify":
                Gfx.disc(ms, cx, cy - 2f, 6f, c);
                Gfx.rrect(ms, cx - 6f, cy - 2f, 12f, 7f, 1f, c);
                Gfx.rrect(ms, cx - 8f, cy + 4.5f, 16f, 2.5f, 1.2f, c);
                Gfx.disc(ms, cx, cy + 8.5f, 1.8f, c);
                break;
            case "gps":
                Gfx.poly(ms, new float[] {cx - 5.2f, cy - 0.5f, cx, cy + 9.5f, cx + 5.2f, cy - 0.5f}, c);
                Gfx.disc(ms, cx, cy - 3f, 6f, c);
                Gfx.disc(ms, cx, cy - 3f, 2.3f, a.color2);
                break;
            case "jobs":
                Gfx.rrect(ms, cx - 4.5f, cy - 8f, 9f, 5f, 2f, c);
                Gfx.rrect(ms, cx - 2.5f, cy - 6.5f, 5f, 3f, 1f, a.color);
                Gfx.rrect(ms, cx - 9f, cy - 4f, 18f, 12f, 2.5f, c);
                Gfx.rrect(ms, cx - 9f, cy + 0.5f, 18f, 1.2f, 0f, a.color2);
                break;
            case "wheel":
                Gfx.ring(ms, cx, cy, 6.8f, 8.6f, c, c);
                for (int i = 0; i < 4; i++) {
                    double ang = Math.PI * i / 4.0;
                    Gfx.line(ms, cx - (float) Math.cos(ang) * 7f, cy - (float) Math.sin(ang) * 7f, cx + (float) Math.cos(ang) * 7f, cy + (float) Math.sin(ang) * 7f, 1.4f, c);
                }
                Gfx.disc(ms, cx, cy, 2.5f, c);
                break;
            case "calc":
                Gfx.rrect(ms, cx - 8f, cy - 8f, 7f, 7f, 2f, 0xFFB8BEC8);
                Gfx.rrect(ms, cx + 1f, cy - 8f, 7f, 7f, 2f, 0xFFFF9F0A);
                Gfx.rrect(ms, cx - 8f, cy + 1f, 7f, 7f, 2f, 0xFFB8BEC8);
                Gfx.rrect(ms, cx + 1f, cy + 1f, 7f, 7f, 2f, 0xFFFF9F0A);
                Gfx.line(ms, cx + 2.5f, cy - 4.5f, cx + 6.5f, cy - 4.5f, 1.2f, c);
                Gfx.line(ms, cx + 2.5f, cy + 3.5f, cx + 6.5f, cy + 3.5f, 1.2f, c);
                Gfx.line(ms, cx + 2.5f, cy + 5.5f, cx + 6.5f, cy + 5.5f, 1.2f, c);
                break;
            case "notes":
                Gfx.rrect(ms, cx - 7.5f, cy - 9f, 15f, 18f, 2.5f, c);
                Gfx.rrect(ms, cx - 7.5f, cy - 9f, 15f, 4f, 2f, 0xFFE0A52C);
                for (int i = 0; i < 3; i++) Gfx.rrect(ms, cx - 5f, cy - 1.5f + i * 3.5f, i == 2 ? 6f : 10f, 1.2f, 0f, 0xFFB0B6C0);
                break;
            case "clock":
                Gfx.disc(ms, cx, cy, 9f, c);
                Gfx.line(ms, cx, cy, cx, cy - 6f, 1.6f, 0xFF15181E);
                Gfx.line(ms, cx, cy, cx + 4.5f, cy + 1.5f, 1.6f, 0xFF15181E);
                Gfx.disc(ms, cx, cy, 1.4f, 0xFFFF9F0A);
                break;
            case "snake":
                Gfx.line(ms, cx - 7f, cy + 6f, cx - 7f, cy - 1f, 3.6f, c);
                Gfx.line(ms, cx - 7f, cy - 1f, cx + 1f, cy - 1f, 3.6f, c);
                Gfx.line(ms, cx + 1f, cy - 1f, cx + 1f, cy - 7f, 3.6f, c);
                Gfx.line(ms, cx + 1f, cy - 7f, cx + 7f, cy - 7f, 3.6f, c);
                Gfx.disc(ms, cx + 7.5f, cy - 7f, 3.2f, c);
                Gfx.disc(ms, cx + 8.2f, cy - 8.2f, 0.9f, a.color2);
                Gfx.disc(ms, cx + 7f, cy + 5f, 2.6f, 0xFFFF5A5A);
                break;
            case "weather":
                Gfx.disc(ms, cx - 2.5f, cy - 3f, 5.5f, 0xFFFFD34A);
                Gfx.disc(ms, cx + 1f, cy + 2.5f, 4f, c);
                Gfx.disc(ms, cx + 5.5f, cy + 3.5f, 3.2f, c);
                Gfx.rrect(ms, cx - 5f, cy + 3f, 13.5f, 4.5f, 2.2f, c);
                break;
            default:
                Gfx.disc(ms, cx, cy, 5f, c);
                break;
        }
    }

    /** Deschide aplicatia cu animatie din pozitia iconitei. */
    private void launch(IconRect r) {
        openFromX = r.ix + ICON / 2f;
        openFromY = r.iy + ICON / 2f;
        openApp(r.id);
        if (page != Page.HOME) {
            closeToX = openFromX;
            closeToY = openFromY;
            closeAppId = r.id;
        }
    }

    private void openApp(String id) {
        PhoneApps.App a = PhoneApps.get(id);
        if (a == null) return;
        if (locked(a)) {
            toast("NONE".equals(PhoneState.simStatus) ? "Fara SIM: introdu o cartela din aplicatia SIM." : (PhoneState.signal <= 0 ? "Fara semnal in aceasta zona." : "Cartela SIM este blocata."), true);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (id) {
            case "messages": PhoneClient.requestThreads(); go(Page.MSG); break;
            case "contacts": PhoneClient.requestContacts(); go(Page.CONTACTS); break;
            case "phone": PhoneClient.requestCalls(); go(Page.CALLS); break;
            case "bank": HypeClient.request("atm_req", new JsonObject()); go(Page.BANK); break;
            case "cnn":
                ro.solarstudios.flowcity.news.AdsClient.requestInfo();
                ro.solarstudios.flowcity.news.AdsClient.requestList();
                mc.setScreen(new ro.solarstudios.flowcity.news.AdsScreen());
                break;
            case "notify": NotifyClient.requestHistory(false); mc.setScreen(new NotifyScreen()); break;
            case "jobs": ro.solarstudios.flowcity.fjobs.FJobsClient.open(); break;
            case "wheel": WheelClient.requestInfo(); mc.setScreen(new WheelScreen()); break;
            case "gps": mc.setScreen(new ro.solarstudios.flowcity.gps.GpsScreen()); break;
            case "ads":
                ro.solarstudios.flowcity.news.AdsClient.requestInfo();
                ro.solarstudios.flowcity.news.AdsClient.requestList();
                mc.setScreen(new ro.solarstudios.flowcity.news.AdsScreen());
                break;
            case "sim": PhoneClient.requestSim(); go(Page.SIM); break;
            case "store": PhoneClient.requestStore(); go(Page.STORE); break;
            case "calc": go(Page.CALC); break;
            case "notes": PhoneClient.requestNotes(); go(Page.NOTES); break;
            case "clock": go(Page.CLOCK); break;
            case "weather": go(Page.WEATHER); break;
            case "snake": go(Page.SNAKE); break;
            default: go(Page.SETTINGS); break;
        }
    }

    private void appHeader(GuiGraphics ms, int x, int y, int w, String title, int mx, int my, boolean back) {
        appHeader(ms, x, y, w, title, mx, my, back ? (Runnable) this::goHome : null);
    }

    private void appHeader(GuiGraphics ms, int x, int y, int w, String title, int mx, int my, Runnable back) {
        Ui.rect(ms, x, y + 12, w, 24, 0xE60A1320);
        Ui.rect(ms, x, y + 36, w, 1, 0xFF22324A);
        Ui.rect(ms, x + w / 2 - 14, y + 36, 28, 1, Sk.ACC);
        if (back != null) {
            boolean hover = Ui.in(mx, my, x, y + 12, 40, 24);
            int c = hover ? Sk.WHITE : Sk.ACC;
            // sageata "<" din pixeli
            for (int i = 0; i < 4; i++) {
                Ui.rect(ms, x + 12 - i, y + 20 + i, 2, 1, c);
                Ui.rect(ms, x + 12 - i, y + 27 - i, 2, 1, c);
            }
            hit(x, y + 12, 40, 24, back);
        }
        Gfx.text(ms, title.toUpperCase(), x + w / 2f, y + 28, 8.4f, Sk.WHITE, 1, true, 0.9f);
    }

    static int avatarColor(String name) {
        return AVATAR[Math.floorMod(name == null ? 0 : name.hashCode(), AVATAR.length)];
    }

    private static void avatar(GuiGraphics ms, String name, int x, int y, int size) {
        avatarBox(ms, x, y, size, avatarColor(name));
        Ui.textCenter(ms, name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), x + size / 2 + 1, y + (size - 8) / 2 + 1, Sk.WHITE);
    }

    // ------------------------------------------------------------------ Mesaje

    private void messages(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Mesaje", mx, my, true);
        int top = y + 40;
        int bottom = y + h - 14;
        if (PhoneState.threads.isEmpty()) {
            Ui.textCenter(ms, "Nicio conversatie.", x + w / 2, top + 20, Sk.MUTED);
            Ui.textCenter(ms, "Alege un contact pentru SMS.", x + w / 2, top + 32, Sk.FAINT);
        }
        int rowH = 30;
        scrollMax = Math.max(0, PhoneState.threads.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.Thread t : PhoneState.threads) {
            if (yy + rowH > top && yy < bottom) {
                boolean hover = Ui.in(mx, my, x + 6, yy, w - 12, rowH - 2) && my >= top && my < bottom;
                Ui.rect(ms, x, yy, w, rowH, hover ? 0x331B2A40 : 0x00000000);
                if (hover) Ui.rect(ms, x, yy, 2, rowH, Sk.ACC);
                Ui.rect(ms, x + 34, yy + rowH - 1, w - 34, 1, 0x22FFFFFF);
                avatar(ms, t.name, x + 10, yy + 5, 18);
                if (t.online) Ui.rect(ms, x + 25, yy + 21, 4, 4, Sk.GOOD);
                Ui.text(ms, Ui.clip(t.name, w - 70), x + 34, yy + 5, Sk.WHITE);
                Ui.text(ms, Ui.clip((t.mine ? "Tu: " : "") + t.last, w - 60), x + 34, yy + 16, t.unread > 0 ? Sk.WHITE : Sk.MUTED);
                if (t.unread > 0) {
                    Sk.cut(ms, x + w - 24, yy + 9, 12, 12, 3f, Sk.ACC);
                    Ui.textCenter(ms, String.valueOf(Math.min(9, t.unread)), x + w - 18, yy + 11, 0xFF061426);
                }
                if (my >= top && my < bottom) {
                    final PhoneState.Thread tt = t;
                    hit(x + 6, yy, w - 12, rowH - 2, () -> {
                        PhoneState.unread = Math.max(0, PhoneState.unread - tt.unread);
                        tt.unread = 0;
                        openThread(tt.uuid, tt.name);
                    });
                }
            }
            yy += rowH;
        }
        button(ms, "+ Mesaj nou", x + 20, y + h - 36, w - 40, 16, mx, my, true, Sk.ACC_DARK, () -> {
            PhoneClient.requestContacts();
            go(Page.CONTACTS);
        });
    }

    private void thread(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(ms, x, y + 12, w, 24, 0xE60A1320);
        Ui.rect(ms, x, y + 36, w, 1, 0xFF22324A);
        Gfx.text(ms, clipG(threadName, 8.4f, true, w - 120), x + 10, y + 28, 8.4f, Sk.WHITE, 0, true, 0.3f);
        Ui.textRight(ms, "< Inapoi", x + w - 8, y + 20, Sk.ACC);
        hit(x + w - 60, y + 12, 60, 24, () -> {
            PhoneClient.requestThreads();
            go(Page.MSG);
        });
        button(ms, "Suna", x + w - 100, y + 16, 34, 16, mx, my, !threadUuid.isEmpty(), Sk.GOOD, () -> PhoneClient.call(threadUuid));

        int top = y + 40;
        int bottom = y + h - 44;
        int maxW = w - 50;
        // masuram inaltimea totala
        int total = 4;
        for (PhoneState.Msg m : PhoneState.thread) total += Wrap.lines(m.text, maxW - 12).size() * 10 + 10;
        scrollMax = Math.max(0, total - (bottom - top));
        if (stickBottom) {
            scroll = scrollMax;
            stickBottom = false;
        }
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top + 2 - scroll;
        for (PhoneState.Msg m : PhoneState.thread) {
            List<String> ls = Wrap.lines(m.text, maxW - 12);
            int bh = ls.size() * 10 + 6;
            int bw = 12;
            for (String l : ls) bw = Math.max(bw, Ui.width(l) + 12);
            if (yy + bh > top && yy < bottom) {
                int bx = m.mine ? x + w - 8 - bw : x + 8;
                Sk.cut(ms, bx, yy, bw, bh, 4f, m.mine ? 0xFF2F7FD6 : 0xFF14202F);
                for (int i = 0; i < ls.size(); i++) Ui.text(ms, ls.get(i), bx + 6, yy + 3 + i * 10, Sk.WHITE);
            }
            yy += bh + 4;
        }
        if (PhoneState.thread.isEmpty()) Ui.textCenter(ms, "Scrie primul mesaj.", x + w / 2, top + 20, Sk.MUTED);
        // input
        drawField(ms, msgField, x + 6, y + h - 40, w - 62, 18, mx, my);
        button(ms, "Trimite", x + w - 52, y + h - 40, 46, 18, mx, my, true, Sk.ACC, this::sendMsg);
    }

    // ------------------------------------------------------------------ Contacte

    private void contacts(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Contacte", mx, my, true);
        drawField(ms, contactField, x + 6, y + 40, w - 52, 18, mx, my);
        button(ms, "+", x + w - 42, y + 40, 36, 18, mx, my, true, Sk.GOOD, this::addContact);
        int top = y + 64;
        int bottom = y + h - 14;
        if (PhoneState.contacts.isEmpty()) Ui.textCenter(ms, "Niciun contact.", x + w / 2, top + 16, Sk.MUTED);
        int rowH = 30;
        scrollMax = Math.max(0, PhoneState.contacts.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.Person p : PhoneState.contacts) {
            if (yy >= top - 2 && yy + rowH <= bottom + 2) {
                Ui.rect(ms, x + 34, yy + rowH - 1, w - 34, 1, 0x22FFFFFF);
                avatar(ms, p.name, x + 10, yy + 5, 18);
                Ui.rect(ms, x + 25, yy + 21, 4, 4, p.online ? Sk.GOOD : Sk.FAINT);
                Ui.text(ms, Ui.clip(p.name, w - 118), x + 34, yy + 5, Sk.WHITE);
                Ui.text(ms, "#" + p.id + (p.online ? "  online" : ""), x + 34, yy + 16, p.online ? Sk.GOOD : Sk.FAINT);
                final PhoneState.Person pp = p;
                button(ms, "SMS", x + w - 80, yy + 7, 24, 14, mx, my, true, Sk.ACC_DARK, () -> openThread(pp.uuid, pp.name));
                button(ms, "Tel", x + w - 54, yy + 7, 24, 14, mx, my, pp.online, Sk.GOOD, () -> PhoneClient.call(pp.uuid));
                button(ms, "X", x + w - 28, yy + 7, 16, 14, mx, my, true, Sk.BAD, () -> {
                    PhoneClient.removeContact(pp.uuid);
                    PhoneState.contacts.remove(pp);
                });
            }
            yy += rowH;
        }
    }

    // ------------------------------------------------------------------ Apeluri

    private static final int GREEN = Sk.GOOD;
    private boolean dialTab;
    private final StringBuilder dial = new StringBuilder();

    private static String clock(long seconds) {
        long s = Math.max(0, seconds);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private static String shortTime(long ts) {
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        b.setTimeInMillis(ts);
        boolean today = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
            && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
        return today ? String.format("%02d:%02d", b.get(java.util.Calendar.HOUR_OF_DAY), b.get(java.util.Calendar.MINUTE))
            : String.format("%02d.%02d", b.get(java.util.Calendar.DAY_OF_MONTH), b.get(java.util.Calendar.MONTH) + 1);
    }

    private static String clipG(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    /** Receptor de telefon desenat din cod: arc gros cu doua capete. down = pozitia "inchis". */
    private static void handset(GuiGraphics g, float cx, float cy, float s, int color, boolean down) {
        float k = s / 12f;
        float r = 8f * k;
        float oy = down ? -4.5f * k : 4.5f * k;
        float d0 = down ? 35 : 215;
        float d1 = down ? 145 : 325;
        Gfx.arc(g, cx, cy + oy, r, 3.4f * k, d0, d1, (qx, qy) -> color, 1f);
        float[] ds = {d0, d1};
        for (float d : ds) {
            double a = Math.toRadians(d);
            Gfx.disc(g, cx + (float) Math.cos(a) * r, cy + oy + (float) Math.sin(a) * r, 3.3f * k, color);
        }
    }

    /** Sageata mica de apel: out = efectuat (sus-dreapta), altfel primit (jos-stanga). */
    private static void callArrow(GuiGraphics g, float cx, float cy, boolean out, int color) {
        if (out) {
            Gfx.line(g, cx - 2.5f, cy + 2.5f, cx + 2.5f, cy - 2.5f, 1.3f, color);
            Gfx.line(g, cx + 2.5f, cy - 2.5f, cx - 0.5f, cy - 2.5f, 1.3f, color);
            Gfx.line(g, cx + 2.5f, cy - 2.5f, cx + 2.5f, cy + 0.5f, 1.3f, color);
        } else {
            Gfx.line(g, cx + 2.5f, cy - 2.5f, cx - 2.5f, cy + 2.5f, 1.3f, color);
            Gfx.line(g, cx - 2.5f, cy + 2.5f, cx + 0.5f, cy + 2.5f, 1.3f, color);
            Gfx.line(g, cx - 2.5f, cy + 2.5f, cx - 2.5f, cy - 0.5f, 1.3f, color);
        }
    }

    private void calls(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        PhoneState.missed = 0;
        // antet: inapoi + titlu
        boolean hb = Ui.in(mx, my, x, y + 14, 44, 28);
        int cc = hb ? Sk.WHITE : Sk.ACC;
        Gfx.line(ms, x + 17, y + 22, x + 11, y + 28, 1.9f, cc);
        Gfx.line(ms, x + 11, y + 28, x + 17, y + 34, 1.9f, cc);
        hit(x, y + 14, 44, 28, this::goHome);
        Gfx.text(ms, "APELURI", x + w / 2f, y + 33, 8.4f, Sk.WHITE, 1, true, 0.9f);

        // taburi: Recente | Tastatura
        float ty = y + 46;
        float tw = w - 24;
        float half = tw / 2f;
        Sk.slant(ms, x + 12, ty, tw, 22, 6f, 0xFF0A1420);
        Sk.slant(ms, x + 12 + (dialTab ? half : 0) + 2, ty + 2, half - 4, 18, 5f, GREEN);
        Gfx.text(ms, "Recente", x + 12 + half / 2f, ty + 14.5f, 8, dialTab ? Sk.MUTED : 0xFF061426, 1, true, 0.2f);
        Gfx.text(ms, "Tastatură", x + 12 + half + half / 2f, ty + 14.5f, 8, dialTab ? 0xFF061426 : Sk.MUTED, 1, true, 0.2f);
        hit(x + 12, (int) ty, (int) half, 22, () -> {
            dialTab = false;
            scroll = 0;
        });
        hit(x + 12 + (int) half, (int) ty, (int) half, 22, () -> dialTab = true);

        if (dialTab) keypad(ms, x, y, w, h, mx, my);
        else recents(ms, x, y + 80, w, h - 80, mx, my);
    }

    private void recents(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        int top = y;
        int bottom = y + h - 16;
        int rowH = 36;
        if (PhoneState.calls.isEmpty()) {
            Gfx.text(ms, "Niciun apel.", x + w / 2f, top + 40, 9, Sk.MUTED, 1);
            Gfx.text(ms, "Folosește tastatura pentru a suna.", x + w / 2f, top + 54, 7, Sk.FAINT, 1);
        }
        scrollMax = Math.max(0, PhoneState.calls.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.CallLog c : PhoneState.calls) {
            if (yy >= top - 2 && yy + rowH <= bottom + 2) {
                boolean missed = "MISSED".equals(c.status) || "DECLINED".equals(c.status);
                boolean hov = !c.uuid.isEmpty() && Ui.in(mx, my, x + 8, yy, w - 16, rowH - 4);
                if (hov) Sk.slant(ms, x + 8, yy, w - 16, rowH - 4, 6f, 0xD01B2A40);
                float cy = yy + (rowH - 4) / 2f;
                int ac = avatarColor(c.name);
                avatarBox(ms, x + 12, cy - 12, 24, ac);
                String ini = c.name == null || c.name.isEmpty() ? "?" : c.name.substring(0, 1).toUpperCase();
                Gfx.text(ms, ini, x + 24, cy + 4f, 11, Sk.WHITE, 1, true, 0f);
                boolean bad = missed && !c.out;
                Gfx.text(ms, clipG(c.name, 8.8f, false, w - 42 - 52), x + 42, yy + 13, 8.8f, bad ? Sk.BAD : Sk.WHITE, 0);
                int sc = missed ? Sk.BAD : (c.out ? Sk.GOOD : Sk.ACC);
                callArrow(ms, x + 45, yy + 21.5f, c.out, sc);
                String st = missed ? ("MISSED".equals(c.status) ? "pierdut" : "respins") : clock(c.dur);
                Gfx.text(ms, st, x + 52, yy + 24, 7, Sk.MUTED, 0);
                Gfx.text(ms, shortTime(c.ts), x + w - 14, yy + 13, 6.8f, Sk.FAINT, 2);
                if (!c.uuid.isEmpty() && my >= top && my < bottom) {
                    final String u = c.uuid;
                    hit(x + 8, yy, w - 16, rowH - 4, () -> PhoneClient.call(u));
                }
            }
            yy += rowH;
        }
    }

    private void keypad(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        // numarul format
        String shown = dialShown();
        if (shown.isEmpty()) Gfx.text(ms, "ID, număr 07… sau 112", x + w / 2f, y + 101, 8, Sk.FAINT, 1);
        else Gfx.text(ms, shown, x + w / 2f, y + 103, shown.length() > 9 ? 14 : 19, Sk.WHITE, 1, true, 0.5f);
        Ui.rect(ms, x + w / 2 - 46, y + 110, 92, 1, 0xFF22324A);
        String who = "";
        if (dial.length() > 0) {
            String dd = dial.toString();
            for (PhoneState.Person p : PhoneState.contacts) {
                if (String.valueOf(p.id).equals(dd) || (!p.number.isEmpty() && p.number.replace(" ", "").equals(dd))) who = p.name;
            }
            if (dd.equals("112")) who = "Serviciul de urgență";
        }
        if (!who.isEmpty()) Gfx.text(ms, clipG(who, 8, false, w - 30), x + w / 2f, y + 124, 8, Sk.GOOD, 1);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "<", "0", "call"};
        for (int i = 0; i < keys.length; i++) {
            float cx = x + w / 2f + (i % 3 - 1) * 50f;
            float cy = y + 156 + (i / 3) * 44f;
            String k = keys[i];
            boolean hov = Ui.in(mx, my, (int) cx - 17, (int) cy - 17, 34, 34);
            if (k.equals("call")) {
                boolean can = dial.length() > 0;
                int c0 = can ? (hov ? 0xFF8CE8B2 : GREEN) : 0xFF14202F;
                Sk.cut(ms, cx - 17, cy - 17, 34, 34, 8f, c0);
                handset(ms, cx, cy, 13, can ? 0xFF061426 : Sk.FAINT, false);
                if (can) hit((int) cx - 17, (int) cy - 17, 34, 34, () -> PhoneClient.callQuery(dial.toString()));
            } else if (k.equals("<")) {
                Sk.cut(ms, cx - 17, cy - 17, 34, 34, 8f, hov ? 0xFF22344D : 0xFF14202F);
                int ic = dial.length() > 0 ? Sk.MUTED : Sk.FAINT;
                Gfx.poly(ms, new float[] {cx - 8, cy, cx - 2.5f, cy - 5.5f, cx + 7, cy - 5.5f, cx + 7, cy + 5.5f, cx - 2.5f, cy + 5.5f}, ic);
                Gfx.line(ms, cx - 1.5f, cy - 2.5f, cx + 3.5f, cy + 2.5f, 1.3f, 0xFF14202F);
                Gfx.line(ms, cx + 3.5f, cy - 2.5f, cx - 1.5f, cy + 2.5f, 1.3f, 0xFF14202F);
                hit((int) cx - 17, (int) cy - 17, 34, 34, () -> {
                    if (dial.length() > 0) dial.setLength(dial.length() - 1);
                });
            } else {
                Sk.cut(ms, cx - 17, cy - 17, 34, 34, 8f, hov ? 0xFF22344D : 0xFF14202F);
                Gfx.text(ms, k, cx, cy + 5.5f, 15, Sk.WHITE, 1, true, 0f);
                hit((int) cx - 17, (int) cy - 17, 34, 34, () -> {
                    if (dial.length() < 10) dial.append(k);
                });
            }
        }
    }

    /** Numarul introdus: #ID pentru cifre scurte, 07xx xxx xxx pentru numere de telefon, 112 simplu. */
    private String dialShown() {
        String d = dial.toString();
        if (d.isEmpty()) return "";
        if (d.startsWith("0")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length(); i++) {
                if (i == 4 || i == 7) sb.append(' ');
                sb.append(d.charAt(i));
            }
            return sb.toString();
        }
        return d.equals("112") ? d : "#" + d;
    }

    private void callScreen(GuiGraphics ms, int x, int y, int w, int h, int mx, int my, PhoneState.Call c, long now) {
        int ac = avatarColor(c.with.name);
        Gfx.rrect(ms, x, y, w, h, 0, Gfx.mixArgb(0xFF0F1D33, ac, 0.16f), 0xFF050810);
        float cx = x + w / 2f;
        // starea apelului sta in Dynamic Island (sus); aici ramane doar ecranul propriu-zis
        if (!c.active) {
            String body = c.incoming ? "Apel primit de la " + c.with.name
                : "Suna: " + (c.with.number.isEmpty() ? c.with.name : c.with.number);
            Gfx.text(ms, clipG(body, 6.4f, false, w - 28), cx, y + 40, 6.4f, Sk.MUTED, 1, false, 0.1f);
        }
        float cy = y + 92f;
        if (!c.active) {
            for (int k = 0; k < 3; k++) {
                float p = (float) (((now / 1700.0) + k / 3.0) % 1.0);
                Gfx.ring(ms, cx, cy, 22 + p * 34, 23.2f + p * 34, Gfx.fade(GREEN, (1f - p) * 0.32f), Gfx.fade(GREEN, 0f));
            }
        } else {
            Gfx.disc(ms, cx, cy, 44, Gfx.fade(GREEN, 0.14f), Gfx.fade(GREEN, 0f), 0, 0);
        }
        Gfx.rrect(ms, cx - 22, cy - 22, 44, 44, 11f, Gfx.mixArgb(ac, 0xFFFFFFFF, 0.12f), Gfx.mixArgb(ac, 0xFF000000, 0.3f));
        String ini = c.with.name == null || c.with.name.isEmpty() ? "?" : c.with.name.substring(0, 1).toUpperCase();
        Gfx.text(ms, ini, cx, cy + 8, 22, Sk.WHITE, 1, true, 0f);

        Gfx.text(ms, clipG(c.with.name, 13, true, w - 20), cx, y + 140, 13, Sk.WHITE, 1, true, 0.2f);
        if (c.with.id > 0) {
            String sub = "#" + c.with.id + (c.with.number.isEmpty() ? "" : "  -  " + c.with.number);
            Gfx.text(ms, sub, cx, y + 153, 7f, Sk.FAINT, 1);
        }
        if (c.active) {
            Gfx.text(ms, "Apel activ", cx, y + 172, 9.5f, GREEN, 1, true, 0.3f);
            Gfx.text(ms, clock((now - c.since) / 1000), cx, y + 190, 10.5f, Sk.WHITE, 1, true, 0.6f);
            for (int i = 0; i < 11; i++) {
                float bh = 3f + 8f * (float) Math.abs(Math.sin(now / 220.0 + i * 0.7));
                Gfx.rrect(ms, cx - 33 + i * 6.4f, y + 206 - bh / 2f, 3, bh, 1f, Gfx.fade(GREEN, 0.8f));
            }
        } else {
            int dots = (int) ((now / 400) % 4);
            String base = c.incoming ? "Apel primit" : "Se formează";
            if (!c.incoming) {
                float sh = (float) Math.sin(now / 90.0) * (((now / 1100) % 2 == 0) ? 1.4f : 0f);
                handset(ms, cx + sh, y + 166, 9, Sk.MUTED, true);
            }
            Gfx.text(ms, base + "...".substring(0, dots), cx, y + 182, 9, Sk.MUTED, 1);
        }

        float by = y + h - 62f;
        if (c.incoming && !c.active) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 200.0);
            callButton(ms, cx - 48, by, 0xFFE2453F, true, "RESPINGE", mx, my, PhoneClient::decline, 0f);
            callButton(ms, cx + 48, by, GREEN, false, "RASPUNDE", mx, my, PhoneClient::answer, pulse);
        } else {
            callButton(ms, cx, by, 0xFFE2453F, true, "INCHIDE", mx, my, PhoneClient::hangup, 0f);
            if (c.active) {
                toggle(ms, cx - 48, by - 50, "Mut", PhoneState.callMuted, mx, my, () -> PhoneState.callMuted = !PhoneState.callMuted);
                toggle(ms, cx + 48, by - 50, "Difuzor", PhoneState.callSpeaker, mx, my, () -> PhoneState.callSpeaker = !PhoneState.callSpeaker);
            }
        }
    }

    /** Comutator mic (doar indicator in UI): Mut / Difuzor. */
    private void toggle(GuiGraphics ms, float bx, float by, String label, boolean on, int mx, int my, Runnable r) {
        boolean hov = Ui.in(mx, my, (int) bx - 22, (int) by - 10, 44, 20);
        int bg = on ? GREEN : (hov ? 0xFF22344D : 0xFF14202F);
        Sk.cut(ms, bx - 22, by - 10, 44, 20, 6f, bg);
        Gfx.text(ms, label, bx, by + 3, 7.5f, on ? 0xFF061426 : Sk.MUTED, 1, true, 0.1f);
        hit((int) bx - 22, (int) by - 10, 44, 20, r);
    }

    /** Buton mare de apel (patrat rotunjit): X = inchide/respinge, altfel receptor (raspunde). */
    private void callButton(GuiGraphics ms, float bx, float by, int color, boolean cross, String label, int mx, int my, Runnable r, float pulse) {
        boolean hov = Ui.in(mx, my, (int) bx - 24, (int) by - 24, 48, 48);
        if (pulse > 0f) Gfx.disc(ms, bx, by, 27 + pulse * 6, Gfx.fade(color, 0.3f * (1f - pulse * 0.5f)), Gfx.fade(color, 0f), 0, 0);
        Gfx.rrect(ms, bx - 24, by - 22.5f, 48, 48, 10f, 0x55000000);
        Gfx.rrect(ms, bx - 24, by - 24, 48, 48, 10f, Gfx.mixArgb(color, 0xFFFFFFFF, hov ? 0.28f : 0.06f), Gfx.mixArgb(color, 0xFF000000, 0.28f));
        Gfx.rrect(ms, bx - 22.5f, by - 23f, 45, 17, 8f, 0x30FFFFFF, 0x00FFFFFF);
        if (cross) {
            Gfx.line(ms, bx - 9, by - 9, bx + 9, by + 9, 4.2f, 0xFFFFFFFF);
            Gfx.line(ms, bx - 9, by + 9, bx + 9, by - 9, 4.2f, 0xFFFFFFFF);
        } else {
            handset(ms, bx, by, 19, 0xFFFFFFFF, false);
        }
        Gfx.text(ms, label, bx, by + 39, 6.8f, Sk.MUTED, 1, true, 0.7f);
        hit((int) bx - 24, (int) by - 24, 48, 48, r);
    }

    // ------------------------------------------------------------------ Banca

    private void bank(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Banca", mx, my, true);
        double cash = HypeState.state.has("cash") ? HypeState.state.get("cash").getAsDouble() : 0;
        double bank = HypeState.state.has("bank") ? HypeState.state.get("bank").getAsDouble() : 0;
        Sk.cut(ms, x + 8, y + 44, w - 16, 52, 10f, 0xFF1B2F4E);
        Ui.rect(ms, x + 18, y + 44 + 26, w - 26, 1, 0xFF2F7FD6);
        Ui.rect(ms, x + 8, y + 44, 3, 26, Sk.ACC);
        Ui.text(ms, "Cash", x + 16, y + 50, Sk.MUTED);
        Ui.text(ms, Ui.money(cash), x + 16, y + 62, Sk.WHITE);
        Ui.text(ms, "In banca", x + 16, y + 76, Sk.MUTED);
        Ui.textRight(ms, Ui.money(bank), x + w - 16, y + 76, Sk.WHITE);
        button(ms, "Deschide ATM", x + 8, y + 104, w - 16, 18, mx, my, true, Sk.ACC_DARK, () -> {
            HypeClient.request("atm_req", new JsonObject());
            Minecraft.getInstance().setScreen(new FlowBankScreen());
        });
        Ui.text(ms, "Ultimele tranzactii", x + 10, y + 132, Sk.MUTED);
        int yy = y + 146;
        int n = 0;
        if (HypeState.atm.has("history") && HypeState.atm.get("history").isJsonArray()) {
            for (com.google.gson.JsonElement e : HypeState.atm.getAsJsonArray("history")) {
                if (!e.isJsonObject()) continue;
                if (yy + 24 > y + h - 8) break;
                JsonObject o = e.getAsJsonObject();
                // serverul trimite cheile scurte: t (tip), a (suma), f (taxa), o (celalalt jucator), ts (momentul)
                String type = o.has("t") ? o.get("t").getAsString() : "";
                double amt = o.has("a") ? o.get("a").getAsDouble() : 0;
                String who = o.has("o") ? o.get("o").getAsString() : "";
                long ts = o.has("ts") ? o.get("ts").getAsLong() : 0;
                boolean plus = "TRANSFER_IN".equals(type) || "DEPOSIT".equals(type) || "PAYDAY".equals(type);
                boolean detail = type.startsWith("FUEL") || "PAYDAY".equals(type);
                String name = bankLabel(type) + (who.isEmpty() || detail ? "" : " " + who);
                Ui.rect(ms, x + 8, yy + 22, w - 16, 1, 0x22FFFFFF);
                Ui.text(ms, Ui.clip(name, w - 96), x + 14, yy + 3, Sk.WHITE);
                if (ts > 0) Ui.text(ms, new java.text.SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date(ts)), x + 14, yy + 13, Sk.FAINT);
                Ui.textRight(ms, (plus ? "+" : "-") + Ui.money(Math.abs(amt)), x + w - 14, yy + 7, plus ? Sk.GOOD : Sk.WHITE);
                yy += 26;
                n++;
            }
        }
        if (n == 0) Ui.textCenter(ms, "Nicio tranzactie.", x + w / 2, yy + 8, Sk.FAINT);
    }

    private static String bankLabel(String type) {
        switch (type) {
            case "DEPOSIT": return "Depunere";
            case "WITHDRAW": return "Retragere";
            case "TRANSFER_OUT": return "Transfer catre";
            case "TRANSFER_IN": return "Primit de la";
            case "PAYDAY": return "Salariu";
            case "FUEL": return "Benzinarie (card)";
            case "FUEL_CASH": return "Benzinarie (cash)";
            default: return type;
        }
    }

    // ------------------------------------------------------------------ SIM

    private static String simDate(long ts) {
        return ts <= 0 ? "-" : new java.text.SimpleDateFormat("dd.MM.yyyy").format(new java.util.Date(ts));
    }

    private void sim(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "SIM", mx, my, true);
        int yy = y + 44;
        PhoneState.SimInfo in = PhoneState.simInstalled;
        Sk.cut(ms, x + 8, yy, w - 16, 62, 10f, 0xFF1B2F4E);
        Ui.rect(ms, x + 8, yy, 3, 62, in == null ? Sk.BAD : ("ACTIVE".equals(in.status) ? Sk.GOOD : Sk.BAD));
        if (in == null) {
            Ui.text(ms, "FARA SIM", x + 18, yy + 10, Sk.BAD);
            Ui.text(ms, "Telefonul nu are cartela.", x + 18, yy + 24, Sk.MUTED);
            Ui.text(ms, "SMS si apelurile sunt oprite.", x + 18, yy + 36, Sk.MUTED);
        } else {
            Ui.text(ms, in.operator, x + 18, yy + 8, Sk.MUTED);
            Gfx.text(ms, in.number, x + 18, yy + 28, 10f, Sk.WHITE, 0, true, 0.6f);
            Ui.text(ms, "ACTIVE".equals(in.status) ? "SIM ACTIV" : "SIM BLOCAT", x + 18, yy + 38, "ACTIVE".equals(in.status) ? Sk.GOOD : Sk.BAD);
            Ui.text(ms, "Activata: " + simDate(in.since), x + 18, yy + 50, Sk.FAINT);
            Ui.textRight(ms, in.id, x + w - 14, yy + 8, Sk.FAINT);
        }
        yy += 68;
        if (in != null) {
            button(ms, "SCOATE CARTELA", x + 8, yy, w - 16, 18, mx, my, true, Sk.BAD, PhoneClient::simEject);
            yy += 24;
        }
        Ui.text(ms, "Cartele in inventar", x + 10, yy + 2, Sk.MUTED);
        yy += 14;
        int shown = 0;
        if (PhoneState.simBlank > 0) {
            shown++;
            String bop = PhoneState.simOps.isEmpty() ? "" : PhoneState.simOps.get(PhoneState.simOpSel % PhoneState.simOps.size());
            Ui.rect(ms, x + 8, yy + 24, w - 16, 1, 0x22FFFFFF);
            Ui.text(ms, "Card SIM nou  x" + PhoneState.simBlank, x + 14, yy + 4, Sk.WHITE);
            if (in == null) button(ms, "ACTIVEAZA", x + w - 74, yy + 2, 66, 16, mx, my, true, Sk.ACC_DARK, () -> PhoneClient.simInsertNew(bop));
            else Ui.text(ms, "Scoate cartela actuala", x + 14, yy + 15, Sk.FAINT);
            yy += 28;
        }
        for (PhoneState.SimInfo s : PhoneState.simInv) {
            if (shown++ >= 3) break;
            Ui.rect(ms, x + 8, yy + 24, w - 16, 1, 0x22FFFFFF);
            Ui.text(ms, s.operator + "  " + s.number, x + 14, yy + 4, Sk.WHITE);
            boolean free = "FREE".equals(s.state) && in == null;
            String st = "FREE".equals(s.state) ? (in == null ? "" : "Scoate cartela actuala") : ("USED".equals(s.state) ? "Folosita de alt telefon" : ("BLOCKED".equals(s.state) ? "Blocata" : "Copie invalida"));
            if (free) {
                button(ms, "INTRODU", x + w - 66, yy + 2, 58, 16, mx, my, true, Sk.ACC_DARK, () -> PhoneClient.simInsert(s.id));
            } else {
                Ui.text(ms, st, x + 14, yy + 15, Sk.FAINT);
            }
            yy += 28;
        }
        if (shown == 0) Ui.textCenter(ms, "Nicio cartela in inventar.", x + w / 2, yy + 6, Sk.FAINT);
        yy = Math.max(yy + (shown == 0 ? 22 : 0), y + h - 86);
        Ui.text(ms, "Cumpara cartela noua", x + 10, yy, Sk.MUTED);
        String op = PhoneState.simOps.isEmpty() ? "-" : PhoneState.simOps.get(PhoneState.simOpSel % PhoneState.simOps.size());
        button(ms, "Operator: " + op, x + 8, yy + 12, w - 16, 18, mx, my, !PhoneState.simOps.isEmpty(), 0xFF22324A, () -> PhoneState.simOpSel = (PhoneState.simOpSel + 1) % PhoneState.simOps.size());
        button(ms, "CUMPARA  " + Ui.money(PhoneState.simPrice), x + 8, yy + 34, w - 16, 18, mx, my, !PhoneState.simOps.isEmpty(), Sk.ACC_DARK, () -> PhoneClient.simBuy(op));
    }

    // ------------------------------------------------------------------ Setari

    private void settings(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Setari", mx, my, true);
        int yy = y + 44;
        yy = row(ms, x, yy, w, "HUD", HypeState.hudOn ? "Pornit" : "Oprit", mx, my, () -> HypeState.hudOn = !HypeState.hudOn);
        yy = row(ms, x, yy, w, "Fundal HUD", HypeState.hudBg == 0 ? "Transparent" : (HypeState.hudBg * 100 / 150) + "%", mx, my, () -> {
            int[] steps = {0, 40, 90, 150};
            int next = 0;
            for (int i = 0; i < steps.length; i++) if (HypeState.hudBg == steps[i]) next = (i + 1) % steps.length;
            HypeState.hudBg = steps[next];
        });
        yy = row(ms, x, yy, w, "Decor Craciun", Xmas.enabled ? "Pornit" : "Oprit", mx, my, () -> Xmas.enabled = !Xmas.enabled);
        String[] vol = {"Mut", "50%", "100%"};
        yy = row(ms, x, yy, w, "Sunete", vol[volumeStep], mx, my, () -> {
            volumeStep = (volumeStep + 1) % 3;
            FlowSounds.volume = volumeStep == 0 ? 0f : (volumeStep == 1 ? 0.5f : 1f);
            FlowSounds.play("notify", 0.8F);
        });
        yy = row(ms, x, yy, w, "Mod luminos", PhoneState.light ? "Pornit" : "Oprit", mx, my, () -> {
            PhoneState.light = !PhoneState.light;
            PhoneClient.savePrefs();
        });
        row(ms, x, yy, w, "Fundal telefon", WALL_NAMES[PhoneState.wallpaper % 3], mx, my, () -> {
            PhoneState.wallpaper = (PhoneState.wallpaper + 1) % 3;
            PhoneClient.savePrefs();
        });
    }

    private int row(GuiGraphics ms, int x, int yy, int w, String label, String value, int mx, int my, Runnable r) {
        boolean hover = Ui.in(mx, my, x + 8, yy, w - 16, 24);
        Ui.rect(ms, x, yy - 2, w, 26, hover ? 0x22FFFFFF : 0x00000000);
        Ui.rect(ms, x + 8, yy + 23, w - 16, 1, 0x22FFFFFF);
        Ui.text(ms, label, x + 16, yy + 8, Sk.WHITE);
        Ui.textRight(ms, value + "  >", x + w - 12, yy + 8, Sk.MUTED);
        hit(x + 8, yy, w - 16, 24, r);
        return yy + 28;
    }

    // ------------------------------------------------------------------ App Store

    private static int storeCat;
    private String storeSel = "";
    private final EditBox storeField;
    private final EditBox noteField;

    private static String priceLabel(String id) {
        double p = PhoneState.prices.getOrDefault(id, 0.0);
        if (p <= 0 || PhoneState.owned.contains(id)) return "OBTINE";
        return Ui.money(p);
    }

    /** Butonul principal al unei aplicatii in magazin: OBTINE / pret / progres / DESCHIDE. */
    private void storeAction(GuiGraphics ms, PhoneApps.App a, int bx, int by, int bw, int bh, int mx, int my) {
        float prog = PhoneState.installProgress(a.id);
        boolean installed = PhoneState.apps.contains(a.id);
        if (prog >= 0) {
            Gfx.bar(ms, bx + 4, by + bh / 2f - 2, bw - 8, 4, prog, 0x33FFFFFF, Sk.ACC);
            return;
        }
        if (PhoneState.pending.contains(a.id)) {
            Gfx.rrect(ms, bx, by, bw, bh, bh / 2f, 0x33FFFFFF);
            Gfx.text(ms, "...", bx + bw / 2f, by + bh / 2f + 2.8f, 7f, Sk.MUTED, 1, true, 0.2f);
            return;
        }
        String label;
        int bg;
        int fg = Sk.ACC;
        Runnable act;
        if (installed) {
            label = "DESCHIDE";
            bg = 0x2A4DA3FF;
            act = () -> openApp(a.id);
        } else if (!PhoneState.available.contains(a.id) && PhoneState.storeLoaded) {
            Gfx.text(ms, "INDISPONIBIL", bx + bw / 2f, by + bh / 2f + 2.5f, 5.8f, Sk.FAINT, 1, false, 0.2f);
            return;
        } else {
            label = priceLabel(a.id);
            bg = 0x2A4DA3FF;
            act = () -> requestInstall(a.id);
        }
        boolean hv = Ui.in(mx, my, bx, by, bw, bh);
        Gfx.rrect(ms, bx, by, bw, bh, bh / 2f, hv ? Sk.ACC : bg);
        Gfx.text(ms, label, bx + bw / 2f, by + bh / 2f + 2.6f, 6.4f, hv ? 0xFF061426 : fg, 1, true, 0.4f);
        hit(bx, by, bw, bh, act);
    }

    private void requestInstall(String id) {
        double p = PhoneState.prices.getOrDefault(id, 0.0);
        if (p > 0 && !PhoneState.owned.contains(id)) {
            confirmBuy = id;
            return;
        }
        PhoneClient.install(id);
    }

    private void storeIcon(GuiGraphics ms, PhoneApps.App a, float x, float y, float size) {
        float r = size * 0.28f;
        Gfx.rrect(ms, x, y, size, size, r, a.color, a.color2);
        Gfx.rrect(ms, x + 1f, y + 0.8f, size - 2f, size * 0.42f, r * 0.8f, 0x2EFFFFFF, 0x00FFFFFF);
        ms.pose().pushPose();
        ms.pose().translate(x + size / 2f, y + size / 2f, 0);
        float s = size / ICON;
        ms.pose().scale(s, s, 1f);
        glyph(ms, a.id, 0, 0, a);
        ms.pose().popPose();
    }

    private List<PhoneApps.App> storeList() {
        String q = storeField.getValue().trim().toLowerCase(java.util.Locale.ROOT);
        String cat = PhoneApps.CATEGORIES.get(storeCat);
        List<PhoneApps.App> out = new ArrayList<>();
        for (PhoneApps.App a : PhoneApps.all()) {
            if (!a.core && PhoneState.storeLoaded && !PhoneState.available.contains(a.id) && !PhoneState.apps.contains(a.id)) continue;
            if (storeCat > 0 && !a.category.equals(cat)) continue;
            if (!q.isEmpty() && !a.name.toLowerCase(java.util.Locale.ROOT).contains(q) && !a.desc.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            out.add(a);
        }
        return out;
    }

    private void store(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "App Store", mx, my, true);
        int yy = y + 42;
        drawField(ms, storeField, x + 8, yy, w - 16, 16, mx, my);
        if (storeField.getValue().isEmpty() && !storeField.isFocused()) Gfx.text(ms, "Cauta aplicatii", x + 18, yy + 10.5f, 6.6f, Sk.FAINT, 0, false, 0.2f);
        yy += 21;
        // categorii (chip-uri pe mai multe randuri)
        int cx = x + 8;
        for (int i = 0; i < PhoneApps.CATEGORIES.size(); i++) {
            String c = PhoneApps.CATEGORIES.get(i);
            int cw = (int) Gfx.width(c, 6.2f, false) + 12;
            if (cx + cw > x + w - 8) {
                cx = x + 8;
                yy += 15;
            }
            boolean on = i == storeCat;
            boolean hv = Ui.in(mx, my, cx, yy, cw, 12);
            Gfx.rrect(ms, cx, yy, cw, 12, 6f, on ? Sk.ACC : (hv ? 0x44FFFFFF : 0x22FFFFFF));
            Gfx.text(ms, c, cx + cw / 2f, yy + 8.6f, 6.2f, on ? 0xFF061426 : Sk.WHITE, 1, false, 0.2f);
            final int ci = i;
            hit(cx, yy, cw, 12, () -> {
                storeCat = ci;
                scroll = 0;
            });
            cx += cw + 4;
        }
        yy += 17;
        int top = yy;
        int bottom = y + h - 12;
        List<PhoneApps.App> list = storeList();
        PhoneApps.App feat = null;
        if (storeCat == 0 && storeField.getValue().trim().isEmpty()) {
            for (PhoneApps.App a : list) {
                if (!a.core && !PhoneState.apps.contains(a.id)) {
                    feat = a;
                    break;
                }
            }
        }
        int rowH = 36;
        int content = (feat != null ? 68 : 0) + list.size() * rowH + 20;
        scrollMax = Math.max(0, content - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        Ui.scissor(x, top, w, bottom - top);
        int ry = top - scroll;
        if (feat != null) {
            final PhoneApps.App f = feat;
            Gfx.rrect(ms, x + 8, ry, w - 16, 62, 12f, Theme.lighten(f.color, -10) & 0xEEFFFFFF, f.color2 & 0xEEFFFFFF);
            Gfx.text(ms, "RECOMANDAT", x + 16, ry + 12, 5.8f, 0xCCFFFFFF, 0, true, 0.8f);
            storeIcon(ms, f, x + 16, ry + 20, 30);
            Gfx.text(ms, f.name, x + 52, ry + 29, 8f, Sk.WHITE, 0, true, 0.3f);
            Gfx.text(ms, Ui.clip(f.desc, w - 76), x + 52, ry + 40, 5.8f, 0xDDFFFFFF, 0, false, 0.1f);
            if (my >= top && my < bottom) {
                hit(x + 8, ry, w - 16, 62, () -> openDetail(f.id));
                storeAction(ms, f, x + w - 62, ry + 44, 48, 13, mx, my);
            }
            ry += 68;
        }
        if (list.isEmpty()) Gfx.text(ms, "Nicio aplicatie gasita.", x + w / 2f, ry + 20, 6.8f, Sk.MUTED, 1, false, 0.2f);
        for (PhoneApps.App a : list) {
            if (ry + rowH > top && ry < bottom) {
                boolean hv = Ui.in(mx, my, x + 4, ry, w - 8, rowH - 2) && my >= top && my < bottom;
                if (hv) Gfx.rrect(ms, x + 4, ry, w - 8, rowH - 2, 8f, 0x18FFFFFF);
                storeIcon(ms, a, x + 10, ry + 4, 26);
                Ui.text(ms, Ui.clip(a.name, w - 110), x + 42, ry + 7, Sk.WHITE);
                Gfx.text(ms, a.category + "  -  " + a.sizeMb + " MB", x + 42, ry + 24, 5.8f, Sk.MUTED, 0, false, 0.2f);
                Ui.rect(ms, x + 42, ry + rowH - 2, w - 50, 1, 0x18FFFFFF);
                if (my >= top && my < bottom) {
                    final String id = a.id;
                    hit(x + 4, ry, w - 8, rowH - 2, () -> openDetail(id));
                    if (a.core) Gfx.text(ms, "SISTEM", x + w - 36, ry + 18, 5.8f, Sk.FAINT, 1, true, 0.4f);
                    else storeAction(ms, a, x + w - 62, ry + 11, 52, 13, mx, my);
                } else if (!a.core) {
                    float pr = PhoneState.installProgress(a.id);
                    if (pr >= 0) Gfx.bar(ms, x + w - 58, ry + 16, 44, 4, pr, 0x33FFFFFF, Sk.ACC);
                }
            }
            ry += rowH;
        }
        Ui.endScissor();
    }

    private void openDetail(String id) {
        storeSel = id;
        go(Page.STORE_APP);
    }

    private void storeApp(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        PhoneApps.App a = PhoneApps.get(storeSel);
        appHeader(ms, x, y, w, "App Store", mx, my, () -> go(Page.STORE));
        if (a == null) return;
        int yy = y + 48;
        storeIcon(ms, a, x + 12, yy, 46);
        Gfx.text(ms, a.name, x + 66, yy + 14, 9.5f, Sk.WHITE, 0, true, 0.3f);
        Gfx.text(ms, a.category, x + 66, yy + 26, 6.2f, Sk.MUTED, 0, false, 0.2f);
        boolean installed = PhoneState.apps.contains(a.id);
        if (a.core) Gfx.text(ms, "Aplicatie de sistem", x + 66, yy + 40, 6f, Sk.FAINT, 0, false, 0.2f);
        else storeAction(ms, a, x + 66, yy + 32, 62, 14, mx, my);
        yy += 58;
        // info
        int cw = (w - 16) / 3;
        String[][] info = {{"MARIME", a.sizeMb + " MB"}, {"PRET", priceText(a)}, {"SIM", a.needsSim ? "Necesar" : "Nu"}};
        for (int i = 0; i < 3; i++) {
            int ix = x + 8 + i * cw;
            if (i > 0) Ui.rect(ms, ix, yy + 2, 1, 22, 0x22FFFFFF);
            Gfx.text(ms, info[i][0], ix + cw / 2f, yy + 8, 5.6f, Sk.FAINT, 1, true, 0.6f);
            Gfx.text(ms, info[i][1], ix + cw / 2f, yy + 21, 7f, Sk.WHITE, 1, false, 0.2f);
        }
        yy += 32;
        Ui.rect(ms, x + 8, yy, w - 16, 1, 0x22FFFFFF);
        yy += 8;
        Gfx.text(ms, "Descriere", x + 10, yy + 6, 7.4f, Sk.WHITE, 0, true, 0.3f);
        yy += 14;
        for (String line : Wrap.lines(a.desc, w - 22)) {
            Ui.text(ms, line, x + 10, yy, Sk.MUTED);
            yy += 10;
        }
        double p = PhoneState.prices.getOrDefault(a.id, 0.0);
        if (p > 0 && !PhoneState.owned.contains(a.id)) {
            yy += 6;
            Gfx.text(ms, "Soldul tau: " + Ui.money(PhoneState.balance), x + 10, yy + 6, 6.4f, Sk.MUTED, 0, false, 0.2f);
            yy += 12;
        }
        if (installed && !a.core) {
            int by = Math.max(yy + 10, y + h - 40);
            button(ms, "DEZINSTALEAZA", x + 10, by, w - 20, 18, mx, my, true, 0xFF5A2328, () -> confirmRemove = a.id);
        }
    }

    private static String priceText(PhoneApps.App a) {
        if (a.core) return "Inclus";
        double p = PhoneState.prices.getOrDefault(a.id, 0.0);
        if (p <= 0) return "Gratuit";
        return PhoneState.owned.contains(a.id) ? "Cumparat" : Ui.money(p);
    }

    /** Dialog de confirmare (dezinstalare / plata), peste orice pagina. */
    private void confirmDialog(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        final String rem = confirmRemove;
        final String buy = confirmBuy;
        if (rem == null && buy == null) return;
        PhoneApps.App a = PhoneApps.get(rem != null ? rem : buy);
        if (a == null) {
            confirmRemove = null;
            confirmBuy = null;
            return;
        }
        Ui.rect(ms, x, y, w, h, 0xAA000000);
        hit(x, y, w, h, () -> {});
        int cw = w - 30;
        int ch = 96;
        int cx = x + 15;
        int cy = y + (h - ch) / 2;
        Gfx.rrect(ms, cx, cy, cw, ch, 14f, 0xF21A2230, 0xF2121822);
        storeIcon(ms, a, cx + cw / 2f - 13, cy + 10, 26);
        String title = rem != null ? "Stergi " + a.name + "?" : "Cumperi " + a.name + "?";
        String sub = rem != null ? "O poti reinstala oricand din App Store." : "Pret: " + Ui.money(PhoneState.prices.getOrDefault(a.id, 0.0)) + "  |  Sold: " + Ui.money(PhoneState.balance);
        Gfx.text(ms, title, cx + cw / 2f, cy + 50, 7.6f, Sk.WHITE, 1, true, 0.2f);
        Gfx.text(ms, Ui.clip(sub, cw - 10), cx + cw / 2f, cy + 62, 5.8f, Sk.MUTED, 1, false, 0.1f);
        int bw = (cw - 24) / 2;
        button(ms, "ANULEAZA", cx + 8, cy + ch - 24, bw, 16, mx, my, true, 0xFF2A3342, () -> {
            confirmRemove = null;
            confirmBuy = null;
        });
        button(ms, rem != null ? "STERGE" : "PLATESTE", cx + 16 + bw, cy + ch - 24, bw, 16, mx, my, true, rem != null ? Sk.BAD : Sk.ACC_DARK, () -> {
            if (rem != null) {
                PhoneClient.uninstall(rem);
                toast(a.name + " a fost stearsa.", false);
                if (page == Page.STORE_APP) go(Page.STORE);
            } else {
                PhoneClient.install(buy);
            }
            confirmRemove = null;
            confirmBuy = null;
        });
    }

    // ------------------------------------------------------------------ Calculator

    private static String calcDisplay = "0";
    private static String calcExpr = "";
    private static double calcAcc;
    private static char calcOp;
    private static boolean calcNew = true;

    private static double calcVal() {
        try {
            return Double.parseDouble(calcDisplay);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String fmtNum(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "Eroare";
        if (v == Math.rint(v) && Math.abs(v) < 1e12) return String.valueOf((long) v);
        String s = new java.math.BigDecimal(v).round(new java.math.MathContext(10)).stripTrailingZeros().toPlainString();
        if (s.length() > 13) s = String.format(java.util.Locale.ROOT, "%.6g", v);
        return s;
    }

    private static char opChar(String k) {
        switch (k) {
            case "+": return '+';
            case "-": return '-';
            case "x": return '*';
            default: return '/';
        }
    }

    private static void calcPress(String k) {
        if ("Eroare".equals(calcDisplay) && !"C".equals(k)) calcPress("C");
        switch (k) {
            case "C":
                calcDisplay = "0";
                calcExpr = "";
                calcAcc = 0;
                calcOp = 0;
                calcNew = true;
                return;
            case "+/-":
                if (!"0".equals(calcDisplay)) calcDisplay = calcDisplay.startsWith("-") ? calcDisplay.substring(1) : "-" + calcDisplay;
                return;
            case "%":
                calcDisplay = fmtNum(calcVal() / 100.0);
                calcNew = true;
                return;
            case ".":
                if (calcNew) {
                    calcDisplay = "0.";
                    calcNew = false;
                } else if (!calcDisplay.contains(".") && calcDisplay.length() < 12) calcDisplay += ".";
                return;
            case "<":
                if (calcNew) return;
                calcDisplay = calcDisplay.length() <= 1 || (calcDisplay.length() == 2 && calcDisplay.startsWith("-")) ? "0" : calcDisplay.substring(0, calcDisplay.length() - 1);
                return;
            case "=":
                if (calcOp != 0) {
                    calcCompute();
                    calcOp = 0;
                    calcExpr = "";
                }
                calcNew = true;
                return;
            case "+":
            case "-":
            case "x":
            case "/":
                if (calcOp != 0 && !calcNew) calcCompute();
                else calcAcc = calcVal();
                calcOp = opChar(k);
                calcExpr = fmtNum(calcAcc) + " " + k;
                calcNew = true;
                return;
            default:
                if (k.length() == 1 && Character.isDigit(k.charAt(0))) {
                    if (calcNew) {
                        calcDisplay = k;
                        calcNew = false;
                    } else if (calcDisplay.replace("-", "").replace(".", "").length() < 12) {
                        calcDisplay = "0".equals(calcDisplay) ? k : ("-0".equals(calcDisplay) ? "-" + k : calcDisplay + k);
                    }
                }
        }
    }

    private static void calcCompute() {
        double b = calcVal();
        double r;
        switch (calcOp) {
            case '+': r = calcAcc + b; break;
            case '-': r = calcAcc - b; break;
            case '*': r = calcAcc * b; break;
            default: r = b == 0 ? Double.NaN : calcAcc / b; break;
        }
        calcAcc = r;
        calcDisplay = fmtNum(r);
        if ("Eroare".equals(calcDisplay)) {
            calcAcc = 0;
            calcOp = 0;
            calcExpr = "";
        }
    }

    private void calc(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(ms, x, y, w, h, 0xF2050608);
        appHeader(ms, x, y, w, "Calculator", mx, my, true);
        String[][] keys = {{"C", "+/-", "%", "/"}, {"7", "8", "9", "x"}, {"4", "5", "6", "-"}, {"1", "2", "3", "+"}, {"0", ".", "<", "="}};
        int gap = 6;
        int bs = Math.min((w - 16 - gap * 3) / 4, (h - 150) / 5 - gap);
        int gw = bs * 4 + gap * 3;
        int gx = x + (w - gw) / 2;
        int gy = y + h - 14 - (bs * 5 + gap * 4);
        // afisaj
        Gfx.text(ms, calcExpr, x + w - 14, gy - 40, 7f, Sk.MUTED, 2, false, 0.3f);
        float size = calcDisplay.length() > 9 ? 13f : (calcDisplay.length() > 6 ? 17f : 22f);
        Gfx.text(ms, calcDisplay, x + w - 14, gy - 12, size, Sk.WHITE, 2, true, 0.4f);
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 4; c++) {
                String k = keys[r][c];
                int bx = gx + c * (bs + gap);
                int by = gy + r * (bs + gap);
                boolean op = c == 3;
                boolean fn = r == 0 && c < 3;
                boolean active = op && calcNew && calcOp == opChar(k) && !"=".equals(k);
                int bg = op ? (active ? 0xFFFFFFFF : 0xFFFF9F0A) : (fn ? 0xFFA5ABB5 : 0xFF2C3038);
                boolean hv = Ui.in(mx, my, bx, by, bs, bs);
                if (hv) bg = Theme.lighten(bg, 18);
                Gfx.rrect(ms, bx, by, bs, bs, bs / 2f, bg);
                int fg = active ? 0xFFFF9F0A : (fn ? 0xFF101216 : Sk.WHITE);
                String lbl = "x".equals(k) ? "x" : ("/".equals(k) ? "/" : k);
                Gfx.text(ms, lbl, bx + bs / 2f, by + bs / 2f + 4f, k.length() > 1 ? 8f : 11f, fg, 1, true, 0.2f);
                hit(bx, by, bs, bs, () -> calcPress(k));
            }
        }
    }

    // ------------------------------------------------------------------ Snake

    private SnakeGame snake;

    private boolean snakeKey(int key) {
        if (snake == null) snake = new SnakeGame();
        long now = System.currentTimeMillis();
        int dx = 0;
        int dy = 0;
        switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_UP: case org.lwjgl.glfw.GLFW.GLFW_KEY_W: dy = -1; break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN: case org.lwjgl.glfw.GLFW.GLFW_KEY_S: dy = 1; break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT: case org.lwjgl.glfw.GLFW.GLFW_KEY_A: dx = -1; break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT: case org.lwjgl.glfw.GLFW.GLFW_KEY_D: dx = 1; break;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE: case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER: case org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER:
                if (snake.state != SnakeGame.State.RUNNING) snake.start(now);
                return true;
            default: return false;
        }
        if (snake.state == SnakeGame.State.OVER) return true;
        snake.turn(dx, dy);
        if (snake.state == SnakeGame.State.READY) snake.start(now);
        return true;
    }

    private void snakeDir(int dx, int dy) {
        if (snake == null) return;
        long now = System.currentTimeMillis();
        if (snake.state == SnakeGame.State.OVER) return;
        snake.turn(dx, dy);
        if (snake.state == SnakeGame.State.READY) snake.start(now);
    }

    private void snake(GuiGraphics ms, int x, int y, int w, int h, int mx, int my, long now) {
        if (snake == null) snake = new SnakeGame();
        SnakeGame g = snake;
        g.update(now);
        Gfx.rrect(ms, x, y, w, h, 0, 0xFF0B1A14, 0xFF050B09);
        appHeader(ms, x, y, w, "Snake", mx, my, true);

        int cell = Math.max(7, Math.min(11, (h - 172) / SnakeGame.ROWS));
        int fw = cell * SnakeGame.COLS;
        int fh = cell * SnakeGame.ROWS;
        int fx = x + (w - fw) / 2;
        int fy = y + 58;
        Gfx.text(ms, "SCOR " + g.score, x + 12, y + 51, 7.6f, Sk.WHITE, 0, true, 0.5f);
        Gfx.text(ms, "RECORD " + SnakeGame.best, x + w - 12, y + 51, 7.6f, 0xFFF6D365, 2, true, 0.5f);

        Gfx.rrect(ms, fx - 3, fy - 3, fw + 6, fh + 6, 6f, 0xFF1F4A33);
        Gfx.rrect(ms, fx - 1.5f, fy - 1.5f, fw + 3, fh + 3, 4.5f, 0xFF0C1F17);
        for (int cy = 0; cy < SnakeGame.ROWS; cy++) {
            for (int cx = 0; cx < SnakeGame.COLS; cx++) {
                if (((cx + cy) & 1) == 0) Ui.rect(ms, fx + cx * cell, fy + cy * cell, cell, cell, 0x14FFFFFF);
            }
        }
        // mar
        float fxm = fx + g.foodX * cell + cell / 2f;
        float fym = fy + g.foodY * cell + cell / 2f;
        float bob = (float) Math.sin(now / 260.0) * 0.5f;
        Gfx.disc(ms, fxm, fym + 0.4f + bob, cell * 0.42f, 0xFFFF6A5E, 0xFFC4271E, 0, 0);
        Gfx.line(ms, fxm, fym - cell * 0.35f + bob, fxm + cell * 0.22f, fym - cell * 0.55f + bob, 1.1f, 0xFF7BE07B);
        // sarpe
        int n = g.body.size();
        int i = 0;
        for (int[] sgm : g.body) {
            float t = n <= 1 ? 0f : i / (float) (n - 1);
            int col = Gfx.mixArgb(0xFF58F08A, 0xFF1B9E5A, t);
            float pad = i == 0 ? 0.3f : 0.7f;
            Gfx.rrect(ms, fx + sgm[0] * cell + pad, fy + sgm[1] * cell + pad, cell - pad * 2, cell - pad * 2, cell * 0.32f, col);
            if (i == 0) {
                float ex = fx + sgm[0] * cell + cell / 2f;
                float ey = fy + sgm[1] * cell + cell / 2f;
                float ox = g.dy != 0 ? cell * 0.22f : 0f;
                float oy = g.dx != 0 ? cell * 0.22f : 0f;
                float fxo = g.dx * cell * 0.18f;
                float fyo = g.dy * cell * 0.18f;
                Gfx.disc(ms, ex + fxo - ox, ey + fyo - oy, cell * 0.16f, 0xFFFFFFFF);
                Gfx.disc(ms, ex + fxo + ox, ey + fyo + oy, cell * 0.16f, 0xFFFFFFFF);
                Gfx.disc(ms, ex + fxo * 1.4f - ox, ey + fyo * 1.4f - oy, cell * 0.08f, 0xFF10241A);
                Gfx.disc(ms, ex + fxo * 1.4f + ox, ey + fyo * 1.4f + oy, cell * 0.08f, 0xFF10241A);
            }
            i++;
        }
        // atinge campul = start / repornire
        hit(fx, fy, fw, fh, () -> {
            long t = System.currentTimeMillis();
            if (g.state != SnakeGame.State.RUNNING) g.start(t);
        });
        if (g.state != SnakeGame.State.RUNNING) {
            Gfx.rrect(ms, fx, fy, fw, fh, 4f, 0xB0050B09);
            if (g.state == SnakeGame.State.OVER) {
                Gfx.text(ms, "JOC TERMINAT", fx + fw / 2f, fy + fh / 2f - 16, 10f, 0xFFFF6A5E, 1, true, 0.6f);
                Gfx.text(ms, "Scor " + g.score + (g.newBest ? "  -  RECORD NOU!" : ""), fx + fw / 2f, fy + fh / 2f - 2, 7.6f, g.newBest ? 0xFFF6D365 : Sk.WHITE, 1, true, 0.3f);
                Gfx.text(ms, "Atinge pentru a juca din nou", fx + fw / 2f, fy + fh / 2f + 14, 6.6f, Sk.MUTED, 1);
            } else {
                Gfx.text(ms, "SNAKE", fx + fw / 2f, fy + fh / 2f - 8, 13f, 0xFF58F08A, 1, true, 1f);
                Gfx.text(ms, "Atinge sau foloseste sagetile", fx + fw / 2f, fy + fh / 2f + 8, 6.6f, Sk.MUTED, 1);
            }
        }
        // sageti pe ecran
        int bs = 24;
        float pcx = x + w / 2f;
        float pcy = Math.min(fy + fh + 38, y + h - 34);
        dpad(ms, pcx, pcy - bs - 1, bs, 0, mx, my, 0, -1);
        dpad(ms, pcx, pcy + bs + 1, bs, 1, mx, my, 0, 1);
        dpad(ms, pcx - bs - 1, pcy, bs, 2, mx, my, -1, 0);
        dpad(ms, pcx + bs + 1, pcy, bs, 3, mx, my, 1, 0);
    }

    private void dpad(GuiGraphics ms, float bx, float by, int bs, int dir, int mx, int my, int dx, int dy) {
        boolean hov = Ui.in(mx, my, (int) (bx - bs / 2f), (int) (by - bs / 2f), bs, bs);
        Gfx.rrect(ms, bx - bs / 2f, by - bs / 2f, bs, bs, 6f, hov ? 0xFF2E7A52 : 0xFF1E5236, hov ? 0xFF1E5236 : 0xFF143826);
        float k = 4.2f;
        float[] tri;
        if (dir == 0) tri = new float[] {bx - k, by + k * 0.6f, bx, by - k * 0.8f, bx + k, by + k * 0.6f};
        else if (dir == 1) tri = new float[] {bx - k, by - k * 0.6f, bx, by + k * 0.8f, bx + k, by - k * 0.6f};
        else if (dir == 2) tri = new float[] {bx + k * 0.6f, by - k, bx - k * 0.8f, by, bx + k * 0.6f, by + k};
        else tri = new float[] {bx - k * 0.6f, by - k, bx + k * 0.8f, by, bx - k * 0.6f, by + k};
        Gfx.poly(ms, tri, 0xFFE8FFF1);
        hit((int) (bx - bs / 2f), (int) (by - bs / 2f), bs, bs, () -> snakeDir(dx, dy));
    }

    // ------------------------------------------------------------------ Note

    private int noteIdx = -1;

    private void notes(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Note", mx, my, true);
        boolean canAdd = PhoneState.notes.size() < 30;
        boolean hv = canAdd && Ui.in(mx, my, x + w - 32, y + 14, 24, 20);
        Gfx.text(ms, "+", x + w - 20, y + 30, 13f, canAdd ? (hv ? Sk.WHITE : 0xFFF6D365) : Sk.FAINT, 1, true, 0f);
        if (canAdd) hit(x + w - 32, y + 14, 24, 20, () -> editNote(-1));
        int top = y + 42;
        int bottom = y + h - 12;
        if (!PhoneState.notesLoaded) {
            Gfx.text(ms, "Se incarca...", x + w / 2f, top + 24, 6.8f, Sk.MUTED, 1, false, 0.2f);
            return;
        }
        if (PhoneState.notes.isEmpty()) {
            Gfx.text(ms, "Nicio notita.", x + w / 2f, top + 24, 7f, Sk.MUTED, 1, false, 0.2f);
            Gfx.text(ms, "Apasa + ca sa scrii una.", x + w / 2f, top + 36, 6.2f, Sk.FAINT, 1, false, 0.2f);
        }
        int rowH = 34;
        scrollMax = Math.max(0, PhoneState.notes.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        Ui.scissor(x, top, w, bottom - top);
        int yy = top - scroll;
        for (int i = 0; i < PhoneState.notes.size(); i++) {
            PhoneState.Note n = PhoneState.notes.get(i);
            if (yy + rowH > top && yy < bottom) {
                boolean rh = Ui.in(mx, my, x + 6, yy, w - 12, rowH - 4) && my >= top && my < bottom;
                Gfx.rrect(ms, x + 6, yy, w - 12, rowH - 4, 8f, rh ? 0x2AFFFFFF : 0x16FFFFFF);
                Ui.text(ms, Ui.clip(n.text, w - 28), x + 14, yy + 6, Sk.WHITE);
                Gfx.text(ms, simDate(n.ts), x + 14, yy + 24, 5.8f, Sk.FAINT, 0, false, 0.2f);
                final int idx = i;
                if (my >= top && my < bottom) hit(x + 6, yy, w - 12, rowH - 4, () -> editNote(idx));
            }
            yy += rowH;
        }
        Ui.endScissor();
    }

    private void editNote(int idx) {
        noteIdx = idx;
        noteField.setValue(idx >= 0 && idx < PhoneState.notes.size() ? PhoneState.notes.get(idx).text : "");
        go(Page.NOTE_EDIT);
        focus(noteField, true);
    }

    private void saveNote() {
        String t = noteField.getValue().trim();
        if (noteIdx >= 0 && noteIdx < PhoneState.notes.size()) {
            if (t.isEmpty()) PhoneState.notes.remove(noteIdx);
            else {
                PhoneState.Note n = PhoneState.notes.remove(noteIdx);
                n.text = t;
                n.ts = System.currentTimeMillis();
                PhoneState.notes.add(0, n);
            }
        } else if (!t.isEmpty() && PhoneState.notes.size() < 30) {
            PhoneState.Note n = new PhoneState.Note();
            n.text = t;
            n.ts = System.currentTimeMillis();
            PhoneState.notes.add(0, n);
        }
        PhoneClient.saveNotes();
        focus(noteField, false);
        go(Page.NOTES);
    }

    private void noteEdit(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, noteIdx < 0 ? "Notita noua" : "Notita", mx, my, this::saveNote);
        int yy = y + 44;
        drawField(ms, noteField, x + 8, yy, w - 16, 16, mx, my);
        yy += 22;
        int ph0 = y + h - 46 - yy;
        Gfx.rrect(ms, x + 8, yy, w - 16, ph0, 10f, 0xFFFFF4C7, 0xFFF6E39A);
        int ly = yy + 8;
        String txt = noteField.getValue();
        if (txt.isEmpty()) Gfx.text(ms, "Scrie notita in campul de sus.", x + 16, ly + 6, 6.4f, 0xFF8A7A40, 0, false, 0.2f);
        for (String line : Wrap.lines(txt, w - 34)) {
            if (ly > yy + ph0 - 12) break;
            Ui.text(ms, line, x + 16, ly, 0xFF2A2410);
            ly += 10;
        }
        Gfx.text(ms, txt.length() + "/" + 400, x + w - 14, yy + ph0 - 5, 5.6f, 0xFF8A7A40, 2, false, 0.2f);
        int bw = (w - 24) / 2;
        button(ms, "STERGE", x + 8, y + h - 36, bw, 18, mx, my, noteIdx >= 0, 0xFF5A2328, () -> {
            noteField.setValue("");
            saveNote();
        });
        button(ms, "SALVEAZA", x + 16 + bw, y + h - 36, bw, 18, mx, my, true, Sk.ACC_DARK, this::saveNote);
    }

    // ------------------------------------------------------------------ Ceas

    private static String gameTime(Minecraft mc) {
        if (mc.level == null) return "--:--";
        long t = mc.level.getDayTime() % 24000L;
        int hh = (int) ((t / 1000L + 6) % 24);
        int mm = (int) ((t % 1000L) * 60 / 1000);
        return String.format("%02d:%02d", hh, mm);
    }

    private static String dur(long ms, boolean cs) {
        long s = ms / 1000;
        String base = String.format("%02d:%02d", s / 60, s % 60);
        return cs ? base + String.format(".%02d", (ms % 1000) / 10) : base;
    }

    private void clock(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(ms, x, y, w, h, 0xF2050608);
        appHeader(ms, x, y, w, "Ceas", mx, my, true);
        long now = System.currentTimeMillis();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int yy = y + 48;
        // cadran analogic
        float ccx = x + w / 2f;
        float ccy = yy + 34;
        Gfx.disc(ms, ccx, ccy, 32f, 0xFF1A1E26);
        Gfx.ring(ms, ccx, ccy, 30.5f, 32f, 0xFF2E3440, 0xFF2E3440);
        for (int i = 0; i < 12; i++) {
            double ang = Math.PI * 2 * i / 12.0;
            float r0 = i % 3 == 0 ? 24f : 26.5f;
            Gfx.line(ms, ccx + (float) Math.sin(ang) * r0, ccy - (float) Math.cos(ang) * r0, ccx + (float) Math.sin(ang) * 28.5f, ccy - (float) Math.cos(ang) * 28.5f, i % 3 == 0 ? 1.6f : 0.9f, Sk.MUTED);
        }
        double sec = cal.get(java.util.Calendar.SECOND) + cal.get(java.util.Calendar.MILLISECOND) / 1000.0;
        double min = cal.get(java.util.Calendar.MINUTE) + sec / 60.0;
        double hr = cal.get(java.util.Calendar.HOUR) + min / 60.0;
        hand(ms, ccx, ccy, hr / 12.0, 15f, 2.4f, Sk.WHITE);
        hand(ms, ccx, ccy, min / 60.0, 22f, 1.8f, Sk.WHITE);
        hand(ms, ccx, ccy, sec / 60.0, 25f, 0.9f, 0xFFFF9F0A);
        Gfx.disc(ms, ccx, ccy, 2f, 0xFFFF9F0A);
        yy += 74;
        Gfx.text(ms, String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND)), x + w / 2f, yy + 6, 11f, Sk.WHITE, 1, true, 0.4f);
        Gfx.text(ms, "Ora in joc: " + gameTime(Minecraft.getInstance()), x + w / 2f, yy + 18, 6.4f, Sk.MUTED, 1, false, 0.3f);
        yy += 28;
        // cronometru
        Gfx.rrect(ms, x + 8, yy, w - 16, 44, 10f, 0x18FFFFFF);
        boolean run = PhoneState.swStart > 0;
        long sw = PhoneState.swAccum + (run ? now - PhoneState.swStart : 0);
        Gfx.text(ms, "CRONOMETRU", x + 16, yy + 11, 5.6f, Sk.FAINT, 0, true, 0.6f);
        Gfx.text(ms, dur(sw, true), x + 16, yy + 31, 11f, Sk.WHITE, 0, true, 0.3f);
        button(ms, run ? "STOP" : "START", x + w - 60, yy + 6, 44, 14, mx, my, true, run ? 0xFF5A2328 : 0xFF1E5E3A, () -> {
            if (PhoneState.swStart > 0) {
                PhoneState.swAccum += System.currentTimeMillis() - PhoneState.swStart;
                PhoneState.swStart = 0;
            } else PhoneState.swStart = System.currentTimeMillis();
        });
        button(ms, "RESET", x + w - 60, yy + 24, 44, 14, mx, my, sw > 0, 0xFF2A3342, () -> {
            PhoneState.swStart = run ? System.currentTimeMillis() : 0;
            PhoneState.swAccum = 0;
        });
        yy += 50;
        // temporizator
        Gfx.rrect(ms, x + 8, yy, w - 16, 48, 10f, 0x18FFFFFF);
        Gfx.text(ms, "TEMPORIZATOR", x + 16, yy + 11, 5.6f, Sk.FAINT, 0, true, 0.6f);
        if (PhoneState.timerEnd > 0) {
            long left = Math.max(0, PhoneState.timerEnd - now);
            Gfx.text(ms, dur(left, false), x + 16, yy + 30, 11f, 0xFFFF9F0A, 0, true, 0.3f);
            Gfx.bar(ms, x + 16, yy + 38, w - 90, 3, PhoneState.timerTotal <= 0 ? 0 : 1f - left / (float) PhoneState.timerTotal, 0x33FFFFFF, 0xFFFF9F0A);
            button(ms, "ANULEAZA", x + w - 66, yy + 17, 50, 14, mx, my, true, 0xFF5A2328, () -> PhoneState.timerEnd = 0);
        } else {
            int[] mins = {1, 3, 5, 10};
            int bw = (w - 40) / 4;
            for (int i = 0; i < 4; i++) {
                final int m = mins[i];
                button(ms, m + " min", x + 16 + i * (bw + 3), yy + 22, bw, 16, mx, my, true, 0xFF2A3342, () -> {
                    PhoneState.timerTotal = m * 60_000L;
                    PhoneState.timerEnd = System.currentTimeMillis() + PhoneState.timerTotal;
                });
            }
        }
    }

    private static void hand(GuiGraphics ms, float cx, float cy, double frac, float len, float th, int col) {
        double ang = Math.PI * 2 * frac;
        Gfx.line(ms, cx, cy, cx + (float) Math.sin(ang) * len, cy - (float) Math.cos(ang) * len, th, col);
    }

    // ------------------------------------------------------------------ Vremea (din lumea jocului)

    private static final String[] WEATHER_NAMES = {"Senin", "Noapte senina", "Ploaie", "Furtuna", "Ninsoare"};

    private static boolean night(Minecraft mc) {
        if (mc.level == null) return false;
        long t = mc.level.getDayTime() % 24000L;
        return t >= 12800L && t < 23200L;
    }

    private static int weatherCond(Minecraft mc) {
        if (mc.level == null || mc.player == null) return 0;
        if (mc.level.isThundering()) return 3;
        if (mc.level.isRaining()) {
            net.minecraft.world.level.biome.Biome b = mc.level.getBiome(mc.player.blockPosition()).value();
            net.minecraft.world.level.biome.Biome.Precipitation pr = b.getPrecipitationAt(mc.player.blockPosition());
            if (pr == net.minecraft.world.level.biome.Biome.Precipitation.SNOW) return 4;
            if (pr == net.minecraft.world.level.biome.Biome.Precipitation.NONE) return night(mc) ? 1 : 0;
            return 2;
        }
        return night(mc) ? 1 : 0;
    }

    private static void weatherGlyph(GuiGraphics ms, float cx, float cy, int cond, float s) {
        switch (cond) {
            case 0:
                for (int i = 0; i < 8; i++) {
                    double ang = Math.PI * i / 4.0;
                    Gfx.line(ms, cx + (float) Math.cos(ang) * 9f * s, cy + (float) Math.sin(ang) * 9f * s, cx + (float) Math.cos(ang) * 12f * s, cy + (float) Math.sin(ang) * 12f * s, 1.6f * s, 0xFFFFD34A);
                }
                Gfx.disc(ms, cx, cy, 7f * s, 0xFFFFD34A);
                break;
            case 1:
                Gfx.disc(ms, cx, cy, 8f * s, 0xFFE8EEF8);
                Gfx.disc(ms, cx + 4f * s, cy - 3f * s, 7f * s, 0xFF1A2230);
                break;
            default:
                Gfx.disc(ms, cx - 4f * s, cy - 1f * s, 6f * s, 0xFFDDE4EE);
                Gfx.disc(ms, cx + 3f * s, cy - 3f * s, 7.5f * s, 0xFFDDE4EE);
                Gfx.rrect(ms, cx - 10f * s, cy, 20f * s, 6f * s, 3f * s, 0xFFDDE4EE);
                if (cond == 2 || cond == 3) {
                    for (int i = 0; i < 3; i++) Gfx.line(ms, cx - 5f * s + i * 5f * s, cy + 9f * s, cx - 7f * s + i * 5f * s, cy + 13f * s, 1.4f * s, 0xFF5BA8FF);
                }
                if (cond == 3) Gfx.poly(ms, new float[] {cx + 1f * s, cy + 6f * s, cx - 2f * s, cy + 12f * s, cx + 1f * s, cy + 12f * s, cx - 1f * s, cy + 17f * s, cx + 4f * s, cy + 10f * s, cx + 1f * s, cy + 10f * s}, 0xFFFFD34A);
                if (cond == 4) {
                    for (int i = 0; i < 3; i++) Gfx.disc(ms, cx - 5f * s + i * 5f * s, cy + 11f * s, 1.3f * s, Sk.WHITE);
                }
                break;
        }
    }

    private static String biomeName(Minecraft mc, net.minecraft.world.level.biome.Biome b) {
        net.minecraft.resources.ResourceLocation rl = null;
        try {
            rl = mc.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME).getKey(b);
        } catch (RuntimeException ignored) {
            // registrul dinamic indisponibil
        }
        if (rl == null) rl = net.minecraftforge.registries.ForgeRegistries.BIOMES.getKey(b);
        if (rl == null) return "Necunoscut";
        String[] parts = rl.getPath().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private void weather(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        int cond = weatherCond(mc);
        int[] top = {0xFF3D8BD9, 0xFF0D1B33, 0xFF4A5A70, 0xFF2A3140, 0xFF8DA4BE};
        int[] bot = {0xFF7FC0F5, 0xFF1F3355, 0xFF2C3746, 0xFF161B24, 0xFFC8D6E6};
        Gfx.rrect(ms, x, y, w, h, 0f, top[cond], bot[cond]);
        appHeader(ms, x, y, w, "Vremea", mx, my, true);
        if (mc.level == null || mc.player == null) return;
        net.minecraft.core.BlockPos pos = mc.player.blockPosition();
        net.minecraft.world.level.biome.Biome b = mc.level.getBiome(pos).value();
        float temp = b.getBaseTemperature();
        int celsius = Math.round(temp * 25f - (night(mc) ? 4f : 0f));
        int yy = y + 52;
        Gfx.text(ms, biomeName(mc, b), x + w / 2f, yy + 6, 8f, Sk.WHITE, 1, true, 0.4f);
        weatherGlyph(ms, x + w / 2f, yy + 38, cond, 2.2f);
        Gfx.text(ms, celsius + "°", x + w / 2f + 4, yy + 96, 30f, Sk.WHITE, 1, true, 0.2f);
        Gfx.text(ms, WEATHER_NAMES[cond], x + w / 2f, yy + 110, 8f, 0xEEFFFFFF, 1, false, 0.4f);
        yy += 124;
        Gfx.rrect(ms, x + 8, yy, w - 16, 64, 12f, 0x30000000);
        String[][] rows = {
            {"Ora in joc", gameTime(mc)},
            {"Umiditate", (mc.level.isRaining() ? 90 : (b.hasPrecipitation() ? 45 : 12)) + "%"},
            {"Zi", String.valueOf(mc.level.getDayTime() / 24000L + 1)},
            {"Pozitie", pos.getX() + ", " + pos.getZ()}};
        for (int i = 0; i < rows.length; i++) {
            int ry = yy + 8 + i * 13;
            Gfx.text(ms, rows[i][0], x + 16, ry + 7, 6.4f, 0xCCFFFFFF, 0, false, 0.2f);
            Gfx.text(ms, rows[i][1], x + w - 16, ry + 7, 6.6f, Sk.WHITE, 2, false, 0.2f);
        }
        Gfx.text(ms, "Temperatura este estimata din biom.", x + w / 2f, y + h - 18, 5.6f, 0xAAFFFFFF, 1, false, 0.2f);
    }

    // ------------------------------------------------------------------ input

    @Override
    public void onClose() {
        if (orderDirty) {
            orderDirty = false;
            PhoneClient.saveOrder();
        }
        super.onClose();
    }

    private IconRect iconAt(double mx, double my) {
        for (IconRect r : icons) if (Ui.in((int) mx, (int) my, r.x, r.y, r.w, r.h)) return r;
        return null;
    }

    private boolean homeInput() {
        return page == Page.HOME && PhoneState.call == null && confirmRemove == null && confirmBuy == null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && homeInput()) {
            if (editMode) {
                if (Ui.in((int) mx, (int) my, editDoneX, editDoneY, 34, 11)) {
                    click();
                    exitEdit();
                    return true;
                }
                // butonul "-" (dezinstalare) din coltul iconitei
                for (IconRect r : icons) {
                    PhoneApps.App a = PhoneApps.get(r.id);
                    if (a != null && !a.core && Ui.in((int) mx, (int) my, r.ix - 5, r.iy - 5, 11, 11)) {
                        click();
                        confirmRemove = r.id;
                        return true;
                    }
                }
                IconRect r = iconAt(mx, my);
                if (r != null) {
                    click();
                    if (editSel == null) editSel = r.id;
                    else if (editSel.equals(r.id)) editSel = null;
                    else {
                        int i = PhoneState.apps.indexOf(editSel);
                        int j = PhoneState.apps.indexOf(r.id);
                        if (i >= 0 && j >= 0) {
                            java.util.Collections.swap(PhoneState.apps, i, j);
                            orderDirty = true;
                        }
                        editSel = null;
                    }
                    return true;
                }
                if (editSel != null && my >= gridTopY && my < gridBottomY) {
                    // loc gol pe o pagina: aplicatia se muta la sfarsit
                    PhoneState.apps.remove(editSel);
                    PhoneState.apps.add(editSel);
                    orderDirty = true;
                    editSel = null;
                    click();
                    return true;
                }
            }
            if (Ui.in((int) mx, (int) my, homeX, gridTopY, homeW, (int) (py + ph - gridTopY - 12))) {
                IconRect r = editMode ? null : iconAt(mx, my);
                pressing = true;
                dragged = false;
                dragDx = 0;
                pressX = mx;
                pressY = my;
                pressAt = System.currentTimeMillis();
                pressApp = r == null ? null : r.id;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pressing && page == Page.HOME) {
            dragDx = (float) (mx - pressX);
            if (Math.abs(dragDx) > 4 || Math.abs(my - pressY) > 6) dragged = true;
            if (!dragged) dragDx = 0;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (pressing && button == 0) {
            pressing = false;
            if (dragged) {
                if (pageX < 0) pageX = homePage;
                pageX -= dragDx / Math.max(1, homeW);
                if (dragDx < -homeW / 6f) homePage++;
                else if (dragDx > homeW / 6f) homePage = Math.max(0, homePage - 1);
                dragDx = 0;
                dragged = false;
                return true;
            }
            dragDx = 0;
            if (pressApp != null && page == Page.HOME && !editMode) {
                IconRect r = iconAt(mx, my);
                if (r != null && r.id.equals(pressApp)) {
                    click();
                    launch(r);
                }
            }
            pressApp = null;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (homeInput()) {
            if (delta < 0) homePage++;
            else if (delta > 0) homePage = Math.max(0, homePage - 1);
            return true;
        }
        scroll -= (int) (delta * 14);
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        return true;
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (page == Page.CALC && PhoneState.call == null) {
            if (Character.isDigit(ch) || ch == '.' || ch == '+' || ch == '-' || ch == '%' || ch == '=') {
                calcPress(String.valueOf(ch));
                return true;
            }
            if (ch == '*' || ch == 'x') {
                calcPress("x");
                return true;
            }
            if (ch == '/') {
                calcPress("/");
                return true;
            }
        }
        if (page == Page.CALLS && dialTab && PhoneState.call == null && ch >= '0' && ch <= '9') {
            if (dial.length() < 8) dial.append(ch);
            return true;
        }
        return super.charTyped(ch, mods);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = (page == Page.THREAD && msgField.isFocused()) || (page == Page.CONTACTS && contactField.isFocused())
            || (page == Page.STORE && storeField.isFocused()) || (page == Page.NOTE_EDIT && noteField.isFocused());
        if (!typing && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (confirmRemove != null || confirmBuy != null) {
                confirmRemove = null;
                confirmBuy = null;
                return true;
            }
            if (editMode) {
                exitEdit();
                return true;
            }
        }
        if (!typing && page == Page.CALC && PhoneState.call == null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                calcPress("<");
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                calcPress("=");
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_DELETE) {
                calcPress("C");
                return true;
            }
        }
        if (!typing && page == Page.SNAKE && PhoneState.call == null && snakeKey(key)) return true;
        if (!typing && PhoneClient.openKey != null && PhoneClient.openKey.matches(key, scan)) {
            onClose();
            return true;
        }
        PhoneState.Call c = PhoneState.call;
        if (!typing && c == null && page == Page.CALLS && dialTab) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                if (dial.length() > 0) dial.setLength(dial.length() - 1);
                return true;
            }
            if ((key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER || key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) && dial.length() > 0) {
                PhoneClient.callQuery(dial.toString());
                return true;
            }
        }
        if (!typing && c != null && c.incoming && !c.active) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
                PhoneClient.answer();
                return true;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_N) {
                PhoneClient.decline();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }
}
