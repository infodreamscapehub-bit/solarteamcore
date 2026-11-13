package ro.solarstudios.flowcity.phone;

import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;
import ro.solarstudios.flowcity.client.ui.Xmas;
import ro.solarstudios.flowcity.hype.AtmScreen;
import ro.solarstudios.flowcity.hype.HypeClient;
import ro.solarstudios.flowcity.hype.HypeState;
import ro.solarstudios.flowcity.hype.JobsScreen;
import ro.solarstudios.flowcity.news.NewsClient;
import ro.solarstudios.flowcity.news.NewsScreen;
import ro.solarstudios.flowcity.notify.NotifyClient;
import ro.solarstudios.flowcity.notify.NotifyScreen;
import ro.solarstudios.flowcity.wheel.WheelClient;
import ro.solarstudios.flowcity.wheel.WheelScreen;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Telefonul ePhone: ecran principal cu aplicatii + Mesaje, Contacte, Apeluri, Banca, Setari. */
public final class PhoneScreen extends FlowScreen {
    private enum Page { HOME, MSG, THREAD, CONTACTS, CALLS, BANK, SETTINGS }

    private static final String[] APP_NAMES = {"Mesaje", "Contacte", "Apeluri", "Banca", "CNN", "Notificari", "Joburi", "Roata", "Harta", "Setari"};
    private static final int[] APP_COLORS = {0xFF3E8E5A, 0xFF5B6270, 0xFF3E8E5A, 0xFF2F5D8A, 0xFFB0413B, 0xFFC27C2C, 0xFF6A5A3E, 0xFF8A4F6B, 0xFF2F7A80, 0xFF4A4E57};
    /** Aplicatiile din dock (jos), restul stau in grila. */
    private static final int[] DOCK = {2, 0, 1, 3};
    private static final int[] GRID = {4, 5, 6, 7, 9};
    private static final int[] AVATAR = {0xFF8A5A3C, 0xFF3F6E8C, 0xFF5E7A44, 0xFF7C4A62, 0xFF8C7A3A, 0xFF4B5D7A, 0xFF7A4E3A};
    private static final int[][] WALL = {{0xFF1A1C21, 0xFF2B2722}, {0xFF14201F, 0xFF23302B}, {0xFF221A1A, 0xFF33251F}};
    private static final String[] WALL_NAMES = {"Grafit", "Padure", "Caramida"};
    private static int wallpaper = 0;
    private static int volumeStep = 2; // 0, 1(50%), 2(100%)

    private static final int W = 190;

    private Page page = Page.HOME;
    private long pageSince = System.currentTimeMillis();
    private int openApp = -1;
    private int scroll;
    private int scrollMax;
    private String threadUuid = "";
    private String threadName = "";
    private final TextFieldWidget msgField;
    private final TextFieldWidget contactField;
    private boolean stickBottom;
    private final long born = System.currentTimeMillis();

    public PhoneScreen() {
        super("ePhone", W, 350);
        msgField = field("Scrie un mesaj...", 200, null);
        contactField = field("Nume sau #ID", 40, null);
        PhoneClient.requestThreads();
        PhoneClient.requestContacts();
        PhoneClient.requestCalls();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<TextFieldWidget> fields() {
        if (page == Page.THREAD) return Collections.singletonList(msgField);
        if (page == Page.CONTACTS) return Collections.singletonList(contactField);
        return Collections.emptyList();
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        if (f == msgField) sendMsg();
        else if (f == contactField) addContact();
    }

    public boolean viewingThread(String uuid) {
        return page == Page.THREAD && threadUuid.equals(uuid);
    }

    public void threadLoaded() {
        stickBottom = true;
    }

    private void go(Page p) {
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

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        Ui.rect(ms, 0, 0, width, height, Theme.DIM);
        float appear = Math.min(1f, (now - born) / 220f);
        int ph2 = Math.min(height - 16, 350);
        int x = (width - W) / 2;
        int y = (height - ph2) / 2 + Math.round((1f - appear) * 40f);
        // corp telefon
        Ui.round(ms, x - 4, y - 4, W + 8, ph2 + 8, 0xFF05070C);
        Ui.round(ms, x - 3, y - 3, W + 6, ph2 + 6, 0xFF2A2B2E);
        int sx = x, sy = y, sw = W, sh = ph2;
        wallpaper(ms, sx, sy, sw, sh, now);
        pw = sw;
        ph = sh;
        px = sx;
        py = sy;

        PhoneState.Call call = PhoneState.call;
        if (call != null) {
            callScreen(ms, sx, sy, sw, sh, mx, my, call, now);
        } else {
            switch (page) {
                case HOME: home(ms, sx, sy, sw, sh, mx, my, now); break;
                case MSG: messages(ms, sx, sy, sw, sh, mx, my); break;
                case THREAD: thread(ms, sx, sy, sw, sh, mx, my); break;
                case CONTACTS: contacts(ms, sx, sy, sw, sh, mx, my); break;
                case CALLS: calls(ms, sx, sy, sw, sh, mx, my); break;
                case BANK: bank(ms, sx, sy, sw, sh, mx, my); break;
                case SETTINGS: settings(ms, sx, sy, sw, sh, mx, my); break;
                default: break;
            }
        }
        statusBar(ms, sx, sy, sw, now);
        // notch
        Ui.round(ms, sx + sw / 2 - 22, sy + 2, 44, 9, 0xFF000000);
        // bara home
        Ui.round(ms, sx + sw / 2 - 28, sy + sh - 6, 56, 3, 0xAAFFFFFF);
        if (call == null && page != Page.HOME) hit(sx + sw / 2 - 30, sy + sh - 10, 60, 10, () -> go(Page.HOME));
    }

    private void wallpaper(GuiGraphics ms, int x, int y, int w, int h, long now) {
        int[] c = WALL[wallpaper % WALL.length];
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            int y0 = y + h * i / steps;
            int y1 = y + h * (i + 1) / steps;
            Ui.rect(ms, x, y0, w, Math.max(1, y1 - y0), Theme.mix(c[0], c[1], i / (float) (steps - 1)));
        }
        // granulatie fina, fixa (nu se misca), ca sa nu para un gradient "de program"
        int seed = 7 + wallpaper * 131;
        for (int i = 0; i < 260; i++) {
            seed = seed * 1103515245 + 12345;
            int gx = x + ((seed >>> 8) & 0x7FFF) % w;
            seed = seed * 1103515245 + 12345;
            int gy = y + ((seed >>> 8) & 0x7FFF) % h;
            Ui.rect(ms, gx, gy, 1, 1, (i & 1) == 0 ? 0x0CFFFFFF : 0x14000000);
        }
        if (Xmas.enabled) {
            for (int i = 0; i < 18; i++) {
                float t = ((now / 40f) + i * 53f) % h;
                int fx = x + (int) ((i * 37 + Math.sin((now / 700.0) + i) * 6) % w);
                if (fx < x) fx += w;
                Ui.rect(ms, fx, y + (int) t, 1, 1, 0xCCFFFFFF);
            }
        }
    }

    private void statusBar(GuiGraphics ms, int x, int y, int w, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        Ui.textShadow(ms, t, x + 10, y + 3, Theme.TEXT);
        for (int i = 0; i < 4; i++) Ui.rect(ms, x + w - 44 + i * 3, y + 9 - (i + 1) * 2, 2, (i + 1) * 2, Theme.TEXT);
        Ui.frame(ms, x + w - 28, y + 3, 16, 7, 0x00000000, Theme.TEXT);
        Ui.rect(ms, x + w - 27, y + 4, 12, 5, Theme.OK);
    }

    private void home(GuiGraphics ms, int x, int y, int w, int h, int mx, int my, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        ms.pose().pushPose();
        ms.pose().translate(x + w / 2f, y + 28, 0);
        ms.pose().scale(2.2f, 2.2f, 1f);
        Ui.textCenter(ms, t, 0, 0, Theme.TEXT);
        ms.pose().popPose();
        String[] days = {"Duminica", "Luni", "Marti", "Miercuri", "Joi", "Vineri", "Sambata"};
        Ui.textCenter(ms, days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(java.util.Calendar.DAY_OF_MONTH) + "." + (cal.get(java.util.Calendar.MONTH) + 1), x + w / 2, y + 50, Theme.MUTED);

        int cols = 3;
        int cell = 56;
        int gx = x + (w - cols * cell) / 2;
        int gy = y + 84;
        for (int k = 0; k < GRID.length; k++) {
            appIcon(ms, GRID[k], gx + (k % cols) * cell, gy + (k / cols) * 58, cell, mx, my, true);
        }
        // dock: telefon, mesaje, contacte, banca
        int dw = w - 16;
        int dy = y + h - 52;
        Ui.round(ms, x + 8, dy, dw, 40, 0x40000000);
        int dc = dw / DOCK.length;
        for (int k = 0; k < DOCK.length; k++) {
            appIcon(ms, DOCK[k], x + 8 + k * dc, dy + 4, dc, mx, my, false);
        }
    }

    private void appIcon(GuiGraphics ms, int i, int cx, int cy, int cell, int mx, int my, boolean label) {
        int size = 30;
        int ox = cx + (cell - size) / 2;
        boolean hover = Ui.in(mx, my, cx, cy, cell, label ? 50 : 32);
        int col = hover ? Theme.lighten(APP_COLORS[i], 12) : APP_COLORS[i];
        Ui.round(ms, ox, cy, size, size, col);
        Ui.round(ms, ox + 1, cy + 1, size - 2, 2, Theme.lighten(col, 10)); // muchie de sus
        Ui.rect(ms, ox + 1, cy + size - 1, size - 2, 1, Theme.mix(col, 0xFF000000, 0.4f));
        icon(ms, i, ox + size / 2, cy + size / 2);
        if (label) Ui.textCenter(ms, APP_NAMES[i], cx + cell / 2, cy + size + 4, 0xFFE4E2DC);
        int badge = i == 0 ? PhoneState.unread : (i == 2 ? PhoneState.missed : 0);
        if (badge > 0) {
            String b = badge > 9 ? "9+" : String.valueOf(badge);
            int bw = Math.max(10, Ui.width(b) + 4);
            Ui.round(ms, ox + size - bw + 3, cy - 3, bw, 10, Theme.BAD);
            Ui.textCenter(ms, b, ox + size - bw / 2 + 3, cy - 2, Theme.TEXT);
        }
        final int app = i;
        hit(cx, cy, cell, label ? 50 : 32, () -> openApp(app));
    }

    private void icon(GuiGraphics ms, int app, int cx, int cy) {
        int c = Theme.TEXT;
        switch (app) {
            case 0: // balon
                Ui.round(ms, cx - 9, cy - 7, 18, 12, c);
                Ui.rect(ms, cx - 6, cy + 5, 4, 3, c);
                break;
            case 1: // persoana
                Ui.round(ms, cx - 3, cy - 9, 7, 7, c);
                Ui.round(ms, cx - 8, cy, 17, 8, c);
                break;
            case 2: // receptor
                Ui.round(ms, cx - 8, cy - 8, 6, 16, c);
                Ui.round(ms, cx - 8, cy + 3, 16, 5, c);
                Ui.round(ms, cx + 2, cy - 8, 6, 16, c);
                break;
            case 3: // card / dolar
                Ui.round(ms, cx - 10, cy - 7, 20, 14, c);
                Ui.rect(ms, cx - 10, cy - 3, 20, 3, APP_COLORS[3]);
                break;
            case 4: // CNN
                Ui.textCenter(ms, "CNN", cx, cy - 4, c);
                break;
            case 5: // clopotel
                Ui.round(ms, cx - 6, cy - 8, 12, 12, c);
                Ui.rect(ms, cx - 8, cy + 3, 16, 2, c);
                Ui.rect(ms, cx - 1, cy + 6, 3, 2, c);
                break;
            case 6: // servieta
                Ui.round(ms, cx - 9, cy - 4, 18, 12, c);
                Ui.rect(ms, cx - 3, cy - 8, 7, 4, c);
                break;
            case 7: // roata
                Ui.round(ms, cx - 9, cy - 9, 18, 18, c);
                Ui.round(ms, cx - 3, cy - 3, 6, 6, APP_COLORS[7]);
                break;
            case 8: // pin
                Ui.round(ms, cx - 5, cy - 9, 10, 10, c);
                Ui.rect(ms, cx - 1, cy + 1, 3, 8, c);
                break;
            default: // setari
                Ui.round(ms, cx - 8, cy - 8, 16, 16, c);
                Ui.round(ms, cx - 3, cy - 3, 6, 6, APP_COLORS[9]);
                break;
        }
    }

    private void openApp(int app) {
        openApp = app;
        pageSince = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        switch (app) {
            case 0: PhoneClient.requestThreads(); go(Page.MSG); break;
            case 1: PhoneClient.requestContacts(); go(Page.CONTACTS); break;
            case 2: PhoneClient.requestCalls(); go(Page.CALLS); break;
            case 3: HypeClient.request("atm_req", new JsonObject()); go(Page.BANK); break;
            case 4: NewsClient.requestList(); mc.setScreen(new NewsScreen()); break;
            case 5: NotifyClient.requestHistory(false); mc.setScreen(new NotifyScreen()); break;
            case 6: HypeClient.request("jobs_req", new JsonObject()); mc.setScreen(new JobsScreen()); break;
            case 7: WheelClient.requestInfo(); mc.setScreen(new WheelScreen()); break;
            default: go(Page.SETTINGS); break;
        }
    }

    private void appHeader(GuiGraphics ms, int x, int y, int w, String title, int mx, int my, boolean back) {
        Ui.rect(ms, x, y + 12, w, 24, 0xE6141619);
        Ui.rect(ms, x, y + 36, w, 1, 0xFF2A2D33);
        if (back) {
            boolean hover = Ui.in(mx, my, x, y + 12, 40, 24);
            int c = hover ? Theme.TEXT : Theme.ACCENT;
            // sageata "<" din pixeli
            for (int i = 0; i < 4; i++) {
                Ui.rect(ms, x + 12 - i, y + 20 + i, 2, 1, c);
                Ui.rect(ms, x + 12 - i, y + 27 - i, 2, 1, c);
            }
            hit(x, y + 12, 40, 24, () -> go(Page.HOME));
        }
        Ui.textCenter(ms, title, x + w / 2, y + 20, Theme.TEXT);
    }

    static int avatarColor(String name) {
        return AVATAR[Math.floorMod(name == null ? 0 : name.hashCode(), AVATAR.length)];
    }

    private static void avatar(GuiGraphics ms, String name, int x, int y, int size) {
        Ui.round(ms, x, y, size, size, avatarColor(name));
        Ui.textCenter(ms, name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), x + size / 2 + 1, y + (size - 8) / 2 + 1, 0xFFF4F1EA);
    }

    // ------------------------------------------------------------------ Mesaje

    private void messages(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Mesaje", mx, my, true);
        int top = y + 40;
        int bottom = y + h - 14;
        if (PhoneState.threads.isEmpty()) {
            Ui.textCenter(ms, "Nicio conversatie.", x + w / 2, top + 20, Theme.MUTED);
            Ui.textCenter(ms, "Alege un contact pentru SMS.", x + w / 2, top + 32, Theme.FAINT);
        }
        int rowH = 30;
        scrollMax = Math.max(0, PhoneState.threads.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.Thread t : PhoneState.threads) {
            if (yy + rowH > top && yy < bottom) {
                boolean hover = Ui.in(mx, my, x + 6, yy, w - 12, rowH - 2) && my >= top && my < bottom;
                Ui.rect(ms, x, yy, w, rowH, hover ? 0x22FFFFFF : 0x00000000);
                Ui.rect(ms, x + 34, yy + rowH - 1, w - 34, 1, 0x22FFFFFF);
                avatar(ms, t.name, x + 10, yy + 5, 18);
                if (t.online) Ui.rect(ms, x + 25, yy + 21, 4, 4, Theme.OK);
                Ui.text(ms, Ui.clip(t.name, w - 70), x + 34, yy + 5, Theme.TEXT);
                Ui.text(ms, Ui.clip((t.mine ? "Tu: " : "") + t.last, w - 60), x + 34, yy + 16, t.unread > 0 ? Theme.TEXT : Theme.MUTED);
                if (t.unread > 0) {
                    Ui.round(ms, x + w - 24, yy + 9, 12, 12, Theme.ACCENT);
                    Ui.textCenter(ms, String.valueOf(Math.min(9, t.unread)), x + w - 18, yy + 11, Theme.TEXT);
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
        button(ms, "+ Mesaj nou", x + 20, y + h - 36, w - 40, 16, mx, my, true, Theme.ACCENT_DIM, () -> {
            PhoneClient.requestContacts();
            go(Page.CONTACTS);
        });
    }

    private void thread(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(ms, x, y + 12, w, 24, 0x88000000);
        Ui.textShadow(ms, Ui.clip(threadName, w - 90), x + 10, y + 20, Theme.TEXT);
        Ui.textRight(ms, "< Inapoi", x + w - 8, y + 20, Theme.ACCENT);
        hit(x + w - 60, y + 12, 60, 24, () -> {
            PhoneClient.requestThreads();
            go(Page.MSG);
        });
        button(ms, "Suna", x + w - 100, y + 16, 34, 16, mx, my, !threadUuid.isEmpty(), Theme.OK, () -> PhoneClient.call(threadUuid));

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
                Ui.round(ms, bx, yy, bw, bh, m.mine ? 0xFF8C5A1F : 0xFF2B2E34);
                for (int i = 0; i < ls.size(); i++) Ui.text(ms, ls.get(i), bx + 6, yy + 3 + i * 10, Theme.TEXT);
            }
            yy += bh + 4;
        }
        if (PhoneState.thread.isEmpty()) Ui.textCenter(ms, "Scrie primul mesaj.", x + w / 2, top + 20, Theme.MUTED);
        // input
        drawField(ms, msgField, x + 6, y + h - 40, w - 62, 18, mx, my);
        button(ms, "Trimite", x + w - 52, y + h - 40, 46, 18, mx, my, true, Theme.ACCENT, this::sendMsg);
    }

    // ------------------------------------------------------------------ Contacte

    private void contacts(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Contacte", mx, my, true);
        drawField(ms, contactField, x + 6, y + 40, w - 52, 18, mx, my);
        button(ms, "+", x + w - 42, y + 40, 36, 18, mx, my, true, Theme.OK, this::addContact);
        int top = y + 64;
        int bottom = y + h - 14;
        if (PhoneState.contacts.isEmpty()) Ui.textCenter(ms, "Niciun contact.", x + w / 2, top + 16, Theme.MUTED);
        int rowH = 30;
        scrollMax = Math.max(0, PhoneState.contacts.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.Person p : PhoneState.contacts) {
            if (yy >= top - 2 && yy + rowH <= bottom + 2) {
                Ui.rect(ms, x + 34, yy + rowH - 1, w - 34, 1, 0x22FFFFFF);
                avatar(ms, p.name, x + 10, yy + 5, 18);
                Ui.rect(ms, x + 25, yy + 21, 4, 4, p.online ? Theme.OK : Theme.FAINT);
                Ui.text(ms, Ui.clip(p.name, w - 118), x + 34, yy + 5, Theme.TEXT);
                Ui.text(ms, "#" + p.id + (p.online ? "  online" : ""), x + 34, yy + 16, p.online ? Theme.OK : Theme.FAINT);
                final PhoneState.Person pp = p;
                button(ms, "SMS", x + w - 80, yy + 7, 24, 14, mx, my, true, Theme.ACCENT_DIM, () -> openThread(pp.uuid, pp.name));
                button(ms, "Tel", x + w - 54, yy + 7, 24, 14, mx, my, pp.online, Theme.OK, () -> PhoneClient.call(pp.uuid));
                button(ms, "X", x + w - 28, yy + 7, 16, 14, mx, my, true, Theme.BAD, () -> {
                    PhoneClient.removeContact(pp.uuid);
                    PhoneState.contacts.remove(pp);
                });
            }
            yy += rowH;
        }
    }

    // ------------------------------------------------------------------ Apeluri

    private static final int GREEN = 0xFF4CC878;
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
        int cc = hb ? 0xFFFFFFFF : 0xFFA9E7C1;
        Gfx.line(ms, x + 17, y + 22, x + 11, y + 28, 1.9f, cc);
        Gfx.line(ms, x + 11, y + 28, x + 17, y + 34, 1.9f, cc);
        hit(x, y + 14, 44, 28, () -> go(Page.HOME));
        Gfx.text(ms, "Apeluri", x + w / 2f, y + 33, 11, 0xFFF4F1EA, 1, true, 0.3f);

        // taburi: Recente | Tastatura
        float ty = y + 46;
        float tw = w - 24;
        float half = tw / 2f;
        Gfx.rrect(ms, x + 12, ty, tw, 22, 11, 0x66000000);
        Gfx.rrect(ms, x + 12 + (dialTab ? half : 0) + 2, ty + 2, half - 4, 18, 9, GREEN, 0xFF2F9E5E);
        Gfx.text(ms, "Recente", x + 12 + half / 2f, ty + 14.5f, 8, dialTab ? 0xFFA19F98 : 0xFF0E2A18, 1);
        Gfx.text(ms, "Tastatură", x + 12 + half + half / 2f, ty + 14.5f, 8, dialTab ? 0xFF0E2A18 : 0xFFA19F98, 1);
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
            Gfx.text(ms, "Niciun apel.", x + w / 2f, top + 40, 9, 0xFFA19F98, 1);
            Gfx.text(ms, "Folosește tastatura pentru a suna.", x + w / 2f, top + 54, 7, 0xFF6F6D68, 1);
        }
        scrollMax = Math.max(0, PhoneState.calls.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.CallLog c : PhoneState.calls) {
            if (yy >= top - 2 && yy + rowH <= bottom + 2) {
                boolean missed = "MISSED".equals(c.status) || "DECLINED".equals(c.status);
                boolean hov = !c.uuid.isEmpty() && Ui.in(mx, my, x + 8, yy, w - 16, rowH - 4);
                if (hov) Gfx.rrect(ms, x + 8, yy, w - 16, rowH - 4, 10, 0x33FFFFFF);
                float cy = yy + (rowH - 4) / 2f;
                int ac = avatarColor(c.name);
                Gfx.disc(ms, x + 24, cy, 12, Gfx.mixArgb(ac, 0xFFFFFFFF, 0.18f), Gfx.mixArgb(ac, 0xFF000000, 0.25f), -2, -3);
                String ini = c.name == null || c.name.isEmpty() ? "?" : c.name.substring(0, 1).toUpperCase();
                Gfx.text(ms, ini, x + 24, cy + 4.2f, 11, 0xFFF4F1EA, 1, true, 0f);
                boolean bad = missed && !c.out;
                Gfx.text(ms, clipG(c.name, 8.8f, false, w - 42 - 52), x + 42, yy + 13, 8.8f, bad ? 0xFFF08A82 : 0xFFF4F1EA, 0);
                int sc = missed ? 0xFFF08A82 : (c.out ? 0xFF7BE3A0 : 0xFF8FB8E8);
                callArrow(ms, x + 45, yy + 21.5f, c.out, sc);
                String st = missed ? ("MISSED".equals(c.status) ? "pierdut" : "respins") : clock(c.dur);
                Gfx.text(ms, st, x + 52, yy + 24, 7, 0xFF8E8C86, 0);
                Gfx.text(ms, shortTime(c.ts), x + w - 14, yy + 13, 6.8f, 0xFF6F6D68, 2);
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
        String shown = dial.length() == 0 ? "" : "#" + dial;
        if (shown.isEmpty()) Gfx.text(ms, "Introdu ID-ul jucatorului", x + w / 2f, y + 101, 8, 0xFF6F6D68, 1);
        else Gfx.text(ms, shown, x + w / 2f, y + 103, 19, 0xFFF4F1EA, 1, true, 0.5f);
        Gfx.rrect(ms, x + w / 2f - 46, y + 110, 92, 1, 0.5f, 0x33FFFFFF);
        String who = "";
        if (dial.length() > 0) {
            for (PhoneState.Person p : PhoneState.contacts) if (String.valueOf(p.id).equals(dial.toString())) who = p.name;
        }
        if (!who.isEmpty()) Gfx.text(ms, clipG(who, 8, false, w - 30), x + w / 2f, y + 124, 8, 0xFF7BE3A0, 1);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "<", "0", "call"};
        for (int i = 0; i < keys.length; i++) {
            float cx = x + w / 2f + (i % 3 - 1) * 50f;
            float cy = y + 156 + (i / 3) * 44f;
            String k = keys[i];
            boolean hov = Ui.in(mx, my, (int) cx - 17, (int) cy - 17, 34, 34);
            if (k.equals("call")) {
                boolean can = dial.length() > 0;
                int c0 = can ? (hov ? 0xFF6BE09A : GREEN) : 0xFF2A2E36;
                int c1 = can ? 0xFF2F9E5E : 0xFF1E2126;
                Gfx.disc(ms, cx, cy, 17, c0, c1, -3, -4);
                handset(ms, cx, cy, 13, can ? 0xFF0E2A18 : 0xFF5C606A, false);
                if (can) hit((int) cx - 17, (int) cy - 17, 34, 34, () -> PhoneClient.callQuery(dial.toString()));
            } else if (k.equals("<")) {
                Gfx.disc(ms, cx, cy, 17, hov ? 0xFF3A3F49 : 0xFF2A2E36, 0xFF1E2126, -3, -4);
                int ic = dial.length() > 0 ? 0xFFC9C7C0 : 0xFF5C606A;
                Gfx.poly(ms, new float[] {cx - 8, cy, cx - 2.5f, cy - 5.5f, cx + 7, cy - 5.5f, cx + 7, cy + 5.5f, cx - 2.5f, cy + 5.5f}, ic);
                Gfx.line(ms, cx - 1.5f, cy - 2.5f, cx + 3.5f, cy + 2.5f, 1.3f, 0xFF1E2126);
                Gfx.line(ms, cx + 3.5f, cy - 2.5f, cx - 1.5f, cy + 2.5f, 1.3f, 0xFF1E2126);
                hit((int) cx - 17, (int) cy - 17, 34, 34, () -> {
                    if (dial.length() > 0) dial.setLength(dial.length() - 1);
                });
            } else {
                Gfx.disc(ms, cx, cy, 17, hov ? 0xFF3A3F49 : 0xFF2A2E36, 0xFF1E2126, -3, -4);
                Gfx.text(ms, k, cx, cy + 5.5f, 15, 0xFFF4F1EA, 1, true, 0f);
                hit((int) cx - 17, (int) cy - 17, 34, 34, () -> {
                    if (dial.length() < 8) dial.append(k);
                });
            }
        }
    }

    private void callScreen(GuiGraphics ms, int x, int y, int w, int h, int mx, int my, PhoneState.Call c, long now) {
        int ac = avatarColor(c.with.name);
        Gfx.rrect(ms, x, y, w, h, 0, Gfx.fade(Gfx.mixArgb(ac, 0xFF000000, 0.6f), 0.98f), 0xFA0E1013);
        float cx = x + w / 2f;
        float cy = y + 112f;
        if (!c.active) {
            for (int k = 0; k < 3; k++) {
                float p = (float) (((now / 1700.0) + k / 3.0) % 1.0);
                Gfx.ring(ms, cx, cy, 34 + p * 44, 35.5f + p * 44, Gfx.fade(GREEN, (1f - p) * 0.35f), Gfx.fade(GREEN, 0f));
            }
        } else {
            Gfx.disc(ms, cx, cy, 62, Gfx.fade(GREEN, 0.16f), Gfx.fade(GREEN, 0f), 0, 0);
        }
        Gfx.disc(ms, cx, cy, 34, Gfx.mixArgb(ac, 0xFFFFFFFF, 0.2f), Gfx.mixArgb(ac, 0xFF000000, 0.25f), -7, -9);
        String ini = c.with.name == null || c.with.name.isEmpty() ? "?" : c.with.name.substring(0, 1).toUpperCase();
        Gfx.text(ms, ini, cx, cy + 10, 30, 0xFFF4F1EA, 1, true, 0f);

        Gfx.text(ms, clipG(c.with.name, 14, true, w - 24), cx, y + 178, 14, 0xFFF4F1EA, 1, true, 0.2f);
        if (c.with.id > 0) Gfx.text(ms, "#" + c.with.id, cx, y + 192, 7.5f, 0xFF8E8C86, 1);
        if (c.active) {
            Gfx.text(ms, clock((now - c.since) / 1000), cx, y + 212, 10, 0xFF7BE3A0, 1, true, 0.5f);
            for (int i = 0; i < 11; i++) {
                float bh = 3f + 9f * (float) Math.abs(Math.sin(now / 220.0 + i * 0.7));
                Gfx.rrect(ms, cx - 33 + i * 6.4f, y + 232 - bh / 2f, 3, bh, 1.5f, Gfx.fade(GREEN, 0.85f));
            }
        } else {
            int dots = (int) ((now / 400) % 4);
            String base = c.incoming ? "Apel primit" : "Se sună";
            Gfx.text(ms, base + "...".substring(0, dots), cx, y + 212, 9, 0xFFA19F98, 1);
        }

        float by = y + h - 76f;
        if (c.incoming && !c.active) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 200.0);
            callButton(ms, cx - 46, by, 0xFFE2453F, true, "Respinge", mx, my, PhoneClient::decline, 0f);
            callButton(ms, cx + 46, by, GREEN, false, "Răspunde", mx, my, PhoneClient::answer, pulse);
        } else {
            callButton(ms, cx, by, 0xFFE2453F, true, "Închide", mx, my, PhoneClient::hangup, 0f);
        }
    }

    private void callButton(GuiGraphics ms, float bx, float by, int color, boolean down, String label, int mx, int my, Runnable r, float pulse) {
        boolean hov = Ui.in(mx, my, (int) bx - 25, (int) by - 25, 50, 50);
        if (pulse > 0f) Gfx.disc(ms, bx, by, 25 + pulse * 6, Gfx.fade(color, 0.3f * (1f - pulse * 0.5f)), Gfx.fade(color, 0f), 0, 0);
        Gfx.disc(ms, bx, by, 25, Gfx.mixArgb(color, 0xFFFFFFFF, hov ? 0.35f : 0.2f), Gfx.mixArgb(color, 0xFF000000, 0.25f), -5, -6);
        handset(ms, bx, by, 19, 0xFFFFFFFF, down);
        Gfx.text(ms, label, bx, by + 40, 7, 0xFFA19F98, 1);
        hit((int) bx - 25, (int) by - 25, 50, 50, r);
    }

    // ------------------------------------------------------------------ Banca

    private void bank(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Banca", mx, my, true);
        double cash = HypeState.state.has("cash") ? HypeState.state.get("cash").getAsDouble() : 0;
        double bank = HypeState.state.has("bank") ? HypeState.state.get("bank").getAsDouble() : 0;
        Ui.round(ms, x + 8, y + 44, w - 16, 52, 0xFF2A4A6B);
        Ui.round(ms, x + 8, y + 44 + 26, w - 16, 26, 0x33000000);
        Ui.text(ms, "Cash", x + 16, y + 50, 0xFFB9C6D3);
        Ui.textShadow(ms, Ui.money(cash), x + 16, y + 62, Theme.TEXT);
        Ui.text(ms, "In banca", x + 16, y + 76, 0xFFB9C6D3);
        Ui.textRight(ms, Ui.money(bank), x + w - 16, y + 76, Theme.TEXT);
        button(ms, "Deschide ATM", x + 8, y + 104, w - 16, 18, mx, my, true, Theme.ACCENT_DIM, () -> {
            HypeClient.request("atm_req", new JsonObject());
            Minecraft.getInstance().setScreen(new AtmScreen());
        });
        Ui.text(ms, "Ultimele tranzactii", x + 10, y + 132, Theme.MUTED);
        int yy = y + 146;
        if (HypeState.atm.has("history") && HypeState.atm.get("history").isJsonArray()) {
            int n = 0;
            for (com.google.gson.JsonElement e : HypeState.atm.getAsJsonArray("history")) {
                if (n++ >= 8 || yy + 20 > y + h - 14) break;
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String type = o.has("type") ? o.get("type").getAsString() : "";
                double amt = o.has("amount") ? o.get("amount").getAsDouble() : 0;
                Ui.rect(ms, x + 8, yy + 18, w - 16, 1, 0x22FFFFFF);
                Ui.text(ms, Ui.clip(type, w - 90), x + 14, yy + 5, Theme.TEXT);
                Ui.textRight(ms, Ui.money(amt), x + w - 14, yy + 5, amt < 0 ? Theme.BAD : Theme.OK);
                yy += 20;
            }
            if (n == 0) Ui.textCenter(ms, "Nicio tranzactie.", x + w / 2, yy + 8, Theme.FAINT);
        } else {
            Ui.textCenter(ms, "Nicio tranzactie.", x + w / 2, yy + 8, Theme.FAINT);
        }
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
        row(ms, x, yy, w, "Fundal telefon", WALL_NAMES[wallpaper % WALL.length], mx, my, () -> wallpaper = (wallpaper + 1) % WALL.length);
    }

    private int row(GuiGraphics ms, int x, int yy, int w, String label, String value, int mx, int my, Runnable r) {
        boolean hover = Ui.in(mx, my, x + 8, yy, w - 16, 24);
        Ui.rect(ms, x, yy - 2, w, 26, hover ? 0x22FFFFFF : 0x00000000);
        Ui.rect(ms, x + 8, yy + 23, w - 16, 1, 0x22FFFFFF);
        Ui.text(ms, label, x + 16, yy + 8, Theme.TEXT);
        Ui.textRight(ms, value + "  >", x + w - 12, yy + 8, Theme.MUTED);
        hit(x + 8, yy, w - 16, 24, r);
        return yy + 28;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll -= (int) (delta * 14);
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        return true;
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (page == Page.CALLS && dialTab && PhoneState.call == null && ch >= '0' && ch <= '9') {
            if (dial.length() < 8) dial.append(ch);
            return true;
        }
        return super.charTyped(ch, mods);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = (page == Page.THREAD && msgField.isFocused()) || (page == Page.CONTACTS && contactField.isFocused());
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
