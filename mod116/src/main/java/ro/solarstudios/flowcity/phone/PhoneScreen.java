package ro.solarstudios.flowcity.phone;

import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
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
    private static final int[] GRID = {4, 5, 6, 7, 8, 9};
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
            case 8: mc.setScreen(new ro.solarstudios.flowcity.maps.MapScreen()); break;
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

    private static int avatarColor(String name) {
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

    private void calls(GuiGraphics ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Apeluri", mx, my, true);
        PhoneState.missed = 0;
        int top = y + 40;
        int bottom = y + h - 14;
        if (PhoneState.calls.isEmpty()) Ui.textCenter(ms, "Niciun apel.", x + w / 2, top + 20, Theme.MUTED);
        int rowH = 26;
        scrollMax = Math.max(0, PhoneState.calls.size() * rowH - (bottom - top));
        scroll = Math.max(0, Math.min(scroll, scrollMax));
        int yy = top - scroll;
        for (PhoneState.CallLog c : PhoneState.calls) {
            if (yy >= top - 2 && yy + rowH <= bottom + 2) {
                boolean missed = "MISSED".equals(c.status) || "DECLINED".equals(c.status);
                Ui.rect(ms, x + 34, yy + rowH - 1, w - 34, 1, 0x22FFFFFF);
                Ui.text(ms, Ui.clip(c.name, w - 80), x + 12, yy + 4, missed && !c.out ? Theme.BAD : Theme.TEXT);
                String st = (c.out ? "Efectuat" : "Primit") + (missed ? " - " + ("MISSED".equals(c.status) ? "pierdut" : "respins") : " - " + Ui.duration(c.dur));
                Ui.text(ms, Ui.clip(st, w - 70), x + 12, yy + 14, Theme.MUTED);
                Ui.textRight(ms, Ui.time(c.ts), x + w - 12, yy + 4, Theme.FAINT);
                if (!c.uuid.isEmpty() && my >= top && my < bottom) {
                    final String u = c.uuid;
                    hit(x + 6, yy, w - 12, rowH - 2, () -> PhoneClient.call(u));
                }
            }
            yy += rowH;
        }
    }

    private void callScreen(GuiGraphics ms, int x, int y, int w, int h, int mx, int my, PhoneState.Call c, long now) {
        Ui.rect(ms, x, y, w, h, 0xCC000000);
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 220.0);
        int ring = c.active ? 0 : (int) (pulse * 6);
        Ui.round(ms, x + w / 2 - 26 - ring, y + 60 - ring, 52 + ring * 2, 52 + ring * 2, Theme.alpha(Theme.OK, c.active ? 60 : 90));
        avatar(ms, c.with.name, x + w / 2 - 24, y + 62, 48);
        Ui.textCenter(ms, Ui.clip(c.with.name, w - 20), x + w / 2, y + 124, Theme.TEXT);
        String status;
        if (c.active) status = Ui.duration((now - c.since) / 1000);
        else if (c.incoming) status = "Apel primit...";
        else status = "Suna...";
        Ui.textCenter(ms, status, x + w / 2, y + 138, Theme.MUTED);
        if (c.incoming && !c.active) {
            button(ms, "Respinge", x + 14, y + h - 70, 70, 22, mx, my, true, Theme.BAD, PhoneClient::decline);
            button(ms, "Raspunde", x + w - 84, y + h - 70, 70, 22, mx, my, true, Theme.OK, PhoneClient::answer);
        } else {
            button(ms, "Inchide", x + w / 2 - 40, y + h - 70, 80, 22, mx, my, true, Theme.BAD, PhoneClient::hangup);
        }
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
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = (page == Page.THREAD && msgField.isFocused()) || (page == Page.CONTACTS && contactField.isFocused());
        if (!typing && PhoneClient.openKey != null && PhoneClient.openKey.matches(key, scan)) {
            onClose();
            return true;
        }
        PhoneState.Call c = PhoneState.call;
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
