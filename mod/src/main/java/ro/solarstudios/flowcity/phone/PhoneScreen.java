package ro.solarstudios.flowcity.phone;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.matrix.MatrixStack;
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
    private static final int[] APP_COLORS = {0xFF34C759, 0xFF8E8E93, 0xFF30D158, 0xFF0A84FF, 0xFFFF453A, 0xFFFF9F0A, 0xFF5E5CE6, 0xFFBF5AF2, 0xFF64D2FF, 0xFF636366};
    private static final int[][] WALL = {{0xFF0B1B3A, 0xFF3A1C71}, {0xFF0F2027, 0xFF2C5364}, {0xFF200122, 0xFF6F0000}};
    private static final String[] WALL_NAMES = {"Noapte", "Gheata", "Rubin"};
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
        String t = msgField.getText().trim();
        if (t.isEmpty() || threadUuid.isEmpty()) return;
        PhoneClient.sendMessage(threadUuid, t);
        msgField.setText("");
        PhoneState.Msg m = new PhoneState.Msg();
        m.mine = true;
        m.text = t;
        m.ts = System.currentTimeMillis();
        PhoneState.thread.add(m);
        stickBottom = true;
    }

    private void addContact() {
        String q = contactField.getText().trim();
        if (q.isEmpty()) return;
        PhoneClient.addContact(q);
        contactField.setText("");
        toast("Se adauga contactul...", false);
    }

    private void openThread(String uuid, String name) {
        threadUuid = uuid;
        threadName = name;
        PhoneState.thread.clear();
        PhoneClient.requestThread(uuid);
        msgField.setText("");
        go(Page.THREAD);
        stickBottom = true;
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        long now = System.currentTimeMillis();
        Ui.rect(ms, 0, 0, width, height, Theme.DIM);
        float appear = Math.min(1f, (now - born) / 220f);
        int ph2 = Math.min(height - 16, 350);
        int x = (width - W) / 2;
        int y = (height - ph2) / 2 + Math.round((1f - appear) * 40f);
        // corp telefon
        Ui.round(ms, x - 4, y - 4, W + 8, ph2 + 8, 0xFF05070C);
        Ui.round(ms, x - 3, y - 3, W + 6, ph2 + 6, 0xFF1C2230);
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

    private void wallpaper(MatrixStack ms, int x, int y, int w, int h, long now) {
        int[] c = WALL[wallpaper % WALL.length];
        int steps = 28;
        for (int i = 0; i < steps; i++) {
            int y0 = y + h * i / steps;
            int y1 = y + h * (i + 1) / steps;
            Ui.rect(ms, x, y0, w, Math.max(1, y1 - y0), Theme.mix(c[0], c[1], i / (float) (steps - 1)));
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

    private void statusBar(MatrixStack ms, int x, int y, int w, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        Ui.textShadow(ms, t, x + 10, y + 3, Theme.TEXT);
        for (int i = 0; i < 4; i++) Ui.rect(ms, x + w - 44 + i * 3, y + 9 - (i + 1) * 2, 2, (i + 1) * 2, Theme.TEXT);
        Ui.frame(ms, x + w - 28, y + 3, 16, 7, 0x00000000, Theme.TEXT);
        Ui.rect(ms, x + w - 27, y + 4, 12, 5, Theme.OK);
    }

    private void home(MatrixStack ms, int x, int y, int w, int h, int mx, int my, long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        String t = String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
        ms.push();
        ms.translate(x + w / 2f, y + 26, 0);
        ms.scale(2.4f, 2.4f, 1f);
        Ui.textCenter(ms, t, 0, 0, Theme.TEXT);
        ms.pop();
        String[] days = {"Duminica", "Luni", "Marti", "Miercuri", "Joi", "Vineri", "Sambata"};
        Ui.textCenter(ms, days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] + ", " + cal.get(java.util.Calendar.DAY_OF_MONTH) + "." + (cal.get(java.util.Calendar.MONTH) + 1), x + w / 2, y + 50, Theme.MUTED);
        if (Xmas.enabled) Ui.textCenter(ms, "Sarbatori fericite!", x + w / 2, y + 62, 0xFFFFD27F);

        int cols = 3;
        int cell = 58;
        int gx = x + (w - cols * cell) / 2;
        int gy = y + 84;
        for (int i = 0; i < APP_NAMES.length; i++) {
            int cx = gx + (i % cols) * cell;
            int cy = gy + (i / cols) * 62;
            int ix = cx + (cell - 34) / 2;
            boolean hover = Ui.in(mx, my, cx, cy, cell, 56);
            int size = hover ? 36 : 34;
            int ox = ix - (size - 34) / 2;
            int oy = cy - (size - 34) / 2;
            float open = 0;
            if (openApp == i) open = Math.min(1f, (now - pageSince) / 160f);
            Ui.round(ms, ox, oy, size, size, APP_COLORS[i]);
            icon(ms, i, ox + size / 2, oy + size / 2);
            Ui.textCenter(ms, APP_NAMES[i], cx + cell / 2, cy + 40, Theme.TEXT);
            int badge = i == 0 ? PhoneState.unread : (i == 2 ? PhoneState.missed : 0);
            if (badge > 0) {
                String b = badge > 9 ? "9+" : String.valueOf(badge);
                Ui.round(ms, ox + size - 8, oy - 4, 12, 12, Theme.BAD);
                Ui.textCenter(ms, b, ox + size - 2, oy - 2, Theme.TEXT);
            }
            final int app = i;
            hit(cx, cy, cell, 56, () -> openApp(app));
        }
        // dock decor
        Ui.round(ms, x + 8, y + h - 36, w - 16, 22, 0x55FFFFFF);
        Ui.textCenter(ms, "ePhone   P = deschide/inchide", x + w / 2, y + h - 29, Theme.MUTED);
    }

    private void icon(MatrixStack ms, int app, int cx, int cy) {
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
            case 4: NewsClient.requestList(); mc.displayGuiScreen(new NewsScreen()); break;
            case 5: NotifyClient.requestHistory(false); mc.displayGuiScreen(new NotifyScreen()); break;
            case 6: HypeClient.request("jobs_req", new JsonObject()); mc.displayGuiScreen(new JobsScreen()); break;
            case 7: WheelClient.requestInfo(); mc.displayGuiScreen(new WheelScreen()); break;
            case 8: toast("Harta: in curand (eMaps)", false); break;
            default: go(Page.SETTINGS); break;
        }
    }

    private void appHeader(MatrixStack ms, int x, int y, int w, String title, int mx, int my, boolean back) {
        Ui.rect(ms, x, y + 12, w, 24, 0x88000000);
        Ui.textShadow(ms, title, x + 10, y + 20, Theme.TEXT);
        if (back) {
            Ui.textRight(ms, "< Inapoi", x + w - 8, y + 20, Theme.ACCENT);
            hit(x + w - 60, y + 12, 60, 24, () -> go(Page.HOME));
        }
    }

    // ------------------------------------------------------------------ Mesaje

    private void messages(MatrixStack ms, int x, int y, int w, int h, int mx, int my) {
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
                Ui.round(ms, x + 6, yy, w - 12, rowH - 2, hover ? 0x99223355 : 0x77000000);
                Ui.round(ms, x + 10, yy + 5, 18, 18, Theme.mix(Theme.ACCENT, Theme.VIOLET, (t.name.length() % 5) / 4f));
                Ui.textCenter(ms, t.name.isEmpty() ? "?" : t.name.substring(0, 1).toUpperCase(), x + 19, yy + 10, Theme.TEXT);
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

    private void thread(MatrixStack ms, int x, int y, int w, int h, int mx, int my) {
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
                Ui.round(ms, bx, yy, bw, bh, m.mine ? 0xFF0A84FF : 0xFF2C2C2E);
                for (int i = 0; i < ls.size(); i++) Ui.text(ms, ls.get(i), bx + 6, yy + 3 + i * 10, Theme.TEXT);
            }
            yy += bh + 4;
        }
        if (PhoneState.thread.isEmpty()) Ui.textCenter(ms, "Scrie primul mesaj.", x + w / 2, top + 20, Theme.MUTED);
        // input
        Ui.round(ms, x + 6, y + h - 40, w - 50, 18, 0xCC10141C);
        drawField(ms, msgField, x + 6, y + h - 40, w - 50, 18, mx, my);
        button(ms, ">", x + w - 40, y + h - 40, 34, 18, mx, my, true, 0xFF0A84FF, this::sendMsg);
    }

    // ------------------------------------------------------------------ Contacte

    private void contacts(MatrixStack ms, int x, int y, int w, int h, int mx, int my) {
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
                Ui.round(ms, x + 6, yy, w - 12, rowH - 2, 0x77000000);
                Ui.round(ms, x + 10, yy + 5, 18, 18, Theme.mix(Theme.ACCENT, Theme.VIOLET, (p.name.length() % 5) / 4f));
                Ui.textCenter(ms, p.name.isEmpty() ? "?" : p.name.substring(0, 1).toUpperCase(), x + 19, yy + 10, Theme.TEXT);
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

    private void calls(MatrixStack ms, int x, int y, int w, int h, int mx, int my) {
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
                Ui.round(ms, x + 6, yy, w - 12, rowH - 2, 0x77000000);
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

    private void callScreen(MatrixStack ms, int x, int y, int w, int h, int mx, int my, PhoneState.Call c, long now) {
        Ui.rect(ms, x, y, w, h, 0xCC000000);
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 220.0);
        int ring = c.active ? 0 : (int) (pulse * 6);
        Ui.round(ms, x + w / 2 - 26 - ring, y + 60 - ring, 52 + ring * 2, 52 + ring * 2, Theme.alpha(Theme.OK, c.active ? 60 : 90));
        Ui.round(ms, x + w / 2 - 24, y + 62, 48, 48, Theme.mix(Theme.ACCENT, Theme.VIOLET, 0.5f));
        Ui.textCenter(ms, c.with.name.isEmpty() ? "?" : c.with.name.substring(0, 1).toUpperCase(), x + w / 2, y + 82, Theme.TEXT);
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

    private void bank(MatrixStack ms, int x, int y, int w, int h, int mx, int my) {
        appHeader(ms, x, y, w, "Banca", mx, my, true);
        double cash = HypeState.state.has("cash") ? HypeState.state.get("cash").getAsDouble() : 0;
        double bank = HypeState.state.has("bank") ? HypeState.state.get("bank").getAsDouble() : 0;
        Ui.round(ms, x + 8, y + 44, w - 16, 52, 0xFF0A84FF);
        Ui.round(ms, x + 8, y + 44 + 26, w - 16, 26, 0x33000000);
        Ui.text(ms, "Cash", x + 16, y + 50, 0xFFD6E9FF);
        Ui.textShadow(ms, Ui.money(cash), x + 16, y + 62, Theme.TEXT);
        Ui.text(ms, "In banca", x + 16, y + 76, 0xFFD6E9FF);
        Ui.textRight(ms, Ui.money(bank), x + w - 16, y + 76, Theme.TEXT);
        button(ms, "Deschide ATM", x + 8, y + 104, w - 16, 18, mx, my, true, Theme.ACCENT_DIM, () -> {
            HypeClient.request("atm_req", new JsonObject());
            Minecraft.getInstance().displayGuiScreen(new AtmScreen());
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
                Ui.round(ms, x + 8, yy, w - 16, 18, 0x77000000);
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

    private void settings(MatrixStack ms, int x, int y, int w, int h, int mx, int my) {
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

    private int row(MatrixStack ms, int x, int yy, int w, String label, String value, int mx, int my, Runnable r) {
        boolean hover = Ui.in(mx, my, x + 8, yy, w - 16, 24);
        Ui.round(ms, x + 8, yy, w - 16, 24, hover ? 0x99223355 : 0x77000000);
        Ui.text(ms, label, x + 16, yy + 8, Theme.TEXT);
        Ui.textRight(ms, value, x + w - 16, yy + 8, Theme.ACCENT);
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
        if (!typing && PhoneClient.openKey != null && PhoneClient.openKey.matchesKey(key, scan)) {
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
