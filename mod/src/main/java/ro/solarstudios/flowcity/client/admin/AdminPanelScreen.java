package ro.solarstudios.flowcity.client.admin;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.StringTextComponent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ClientBridge;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/**
 * Panoul eAdmin FlowCity. Desenat in mod imediat: fiecare frame reconstruieste lista de zone clicabile.
 * Taburi: PROFIL, LOGURI, SANCTIUNI, NOTE. Toate datele vin de la pluginul FlowCityBridge.
 */
public class AdminPanelScreen extends Screen {
    private enum Tab {
        PROFILE("PROFIL"), LOGS("LOGURI"), SANCTIONS("SANCTIUNI"), NOTES("NOTE");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final class Hit {
        final int x;
        final int y;
        final int w;
        final int h;
        final Runnable action;

        Hit(int x, int y, int w, int h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }
    }

    private static final String[] LOG_TYPES = {"CHAT", "CMD", "CONN", "CREATIVE", "INV", "TX"};
    private static final String[] LOG_LABELS = {"CHAT", "COMENZI", "CONEX.", "CREATIV", "INVENTAR", "BANI"};
    private static final String[] S_TYPES = {"WARN", "MUTE", "KICK", "BAN"};
    private static final int[] DUR_MIN = {15, 60, 1440, 10080, 43200, 0};
    private static final String[] DUR_LABEL = {"15m", "1h", "1d", "7d", "30d", "PERM"};
    private static final int HEAD = 24;
    private static final int LIST_W = 134;

    private final String staffName;
    private final String serverName;
    private final List<Hit> hits = new ArrayList<>();

    private TextFieldWidget search;
    private TextFieldWidget reason;
    private TextFieldWidget note;

    private AdminData.PlayerList list;
    private AdminData.Profile profile;
    private AdminData.Logs logs;
    private String selectedUuid;
    private String selectedName = "";
    private Tab tab = Tab.PROFILE;
    private int listPage;
    private String logType = "CHAT";
    private int logPage;
    private String sType = "WARN";
    private int sMinutes = 60;
    private int scroll;

    private String toastText = "";
    private boolean toastError;
    private long toastUntil;
    private boolean searchDirty;
    private long searchTypedAt;
    private String lastQuery = "";

    // layout
    private int px;
    private int py;
    private int pw;
    private int ph;

    public AdminPanelScreen(String staffName, String serverName) {
        super(new StringTextComponent("eAdmin"));
        this.staffName = staffName;
        this.serverName = serverName;
    }

    // ------------------------------------------------------------------ ciclu de viata

    @Override
    protected void init() {
        search = field("Cauta jucator...", 32, s -> {
            searchDirty = true;
            searchTypedAt = System.currentTimeMillis();
        });
        reason = field("Motiv sanctiune...", 120, null);
        note = field("Scrie o nota...", 190, null);
        if (list == null) ClientBridge.requestList("", 0);
    }

    private TextFieldWidget field(String hint, int max, Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(font, 0, 0, 100, 14, new StringTextComponent(hint));
        f.setMaxStringLength(max);
        f.setEnableBackgroundDrawing(false);
        f.setTextColor(Theme.TEXT);
        f.setSuggestion(hint);
        f.setResponder(s -> {
            f.setSuggestion(s.isEmpty() ? hint : null);
            if (onChange != null) onChange.accept(s);
        });
        return f;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        search.tick();
        reason.tick();
        note.tick();
        if (searchDirty && System.currentTimeMillis() - searchTypedAt > 300) {
            searchDirty = false;
            String q = search.getText().trim();
            if (!q.equals(lastQuery)) {
                lastQuery = q;
                listPage = 0;
                ClientBridge.requestList(q, 0);
            }
        }
    }

    // ------------------------------------------------------------------ primire date

    public void onList(AdminData.PlayerList l) {
        this.list = l;
        this.listPage = l.page;
    }

    public void onProfile(AdminData.Profile p) {
        if (selectedUuid == null || selectedUuid.equals(p.uuid)) {
            this.profile = p;
            this.selectedUuid = p.uuid;
            this.selectedName = p.name;
        }
    }

    public void onLogs(AdminData.Logs l) {
        if (l.type.equals(logType)) this.logs = l;
    }

    public void onToast(String msg, boolean error) {
        this.toastText = msg;
        this.toastError = error;
        this.toastUntil = System.currentTimeMillis() + 4000;
    }

    // ------------------------------------------------------------------ actiuni

    private void select(AdminData.PlayerEntry e) {
        selectedUuid = e.uuid;
        selectedName = e.name;
        profile = null;
        logs = null;
        scroll = 0;
        logPage = 0;
        ClientBridge.requestProfile(e.uuid);
        if (tab == Tab.LOGS) ClientBridge.requestLogs(e.uuid, logType, 0);
    }

    private void switchTab(Tab t) {
        tab = t;
        scroll = 0;
        focus(reason, false);
        focus(note, false);
        focus(search, false);
        if (t == Tab.LOGS && selectedUuid != null && logs == null) ClientBridge.requestLogs(selectedUuid, logType, logPage);
    }

    private void applySanction() {
        if (selectedUuid == null) return;
        boolean timed = sType.equals("MUTE") || sType.equals("BAN");
        ClientBridge.sanction(selectedUuid, sType, timed ? sMinutes : 0, reason.getText().trim());
        reason.setText("");
        focus(reason, false);
    }

    private void addNote() {
        if (selectedUuid == null || note.getText().trim().isEmpty()) return;
        ClientBridge.noteAdd(selectedUuid, note.getText().trim());
        note.setText("");
        focus(note, false);
    }

    /** Focus fara dependenta de numele metodei: TextFieldWidget isi seteaza singur focusul la click. */
    private void focus(TextFieldWidget f, boolean on) {
        f.mouseClicked(on ? f.x + 1 : -10000, on ? f.y + 1 : -10000, 0);
    }

    private void click() {
        Minecraft.getInstance().getSoundHandler().play(SimpleSound.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ------------------------------------------------------------------ desenare

    @Override
    public void render(MatrixStack ms, int mx, int my, float pt) {
        hits.clear();
        pw = Math.min(width - 16, 476);
        ph = Math.min(height - 16, 274);
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        Ui.rect(ms, 0, 0, width, height, Theme.DIM);
        Ui.frame(ms, px, py, pw, ph, Theme.PANEL, Theme.LINE);
        drawHeader(ms, mx, my);
        drawList(ms, mx, my);
        drawRight(ms, mx, my);
        drawToast(ms);
        super.render(ms, mx, my, pt);
    }

    private void drawHeader(MatrixStack ms, int mx, int my) {
        Ui.rect(ms, px + 1, py + 1, pw - 2, HEAD - 1, Theme.PANEL_2);
        Ui.rect(ms, px + 1, py + HEAD, pw - 2, 1, Theme.ACCENT_DIM);
        Ui.rect(ms, px + 8, py + 8, 3, 9, Theme.ACCENT);
        Ui.textShadow(ms, serverName.toUpperCase(), px + 15, py + 8, Theme.ACCENT);
        Ui.text(ms, "eAdmin", px + 15 + Ui.width(serverName.toUpperCase()) + 6, py + 8, Theme.TEXT);
        Ui.textRight(ms, "staff: " + staffName, px + pw - 62, py + 8, Theme.MUTED);
        button(ms, "REFRESH", px + pw - 56, py + 5, 34, 14, mx, my, true, Theme.PANEL_3, () -> {
            ClientBridge.requestList(lastQuery, listPage);
            if (selectedUuid != null) ClientBridge.requestProfile(selectedUuid);
        });
        button(ms, "X", px + pw - 18, py + 5, 12, 14, mx, my, true, 0xFF5B2A35, this::onClose);
    }

    private void drawList(MatrixStack ms, int mx, int my) {
        int x = px + 6;
        int top = py + HEAD + 6;
        // casuta de cautare
        Ui.frame(ms, x, top, LIST_W, 16, Theme.PANEL_2, search.isFocused() ? Theme.ACCENT : Theme.LINE);
        search.x = x + 5;
        search.y = top + 4;
        search.setWidth(LIST_W - 10);
        search.render(ms, mx, my, 0);

        int rowsTop = top + 20;
        int bottom = py + ph - 22;
        int rh = Math.max(11, Math.min(16, (bottom - rowsTop) / 12));
        if (list == null) {
            Ui.text(ms, "Se incarca...", x + 4, rowsTop + 4, Theme.MUTED);
        } else if (list.entries.isEmpty()) {
            Ui.text(ms, "Niciun jucator.", x + 4, rowsTop + 4, Theme.MUTED);
        } else {
            int y = rowsTop;
            for (AdminData.PlayerEntry e : list.entries) {
                boolean sel = e.uuid.equals(selectedUuid);
                boolean hover = Ui.in(mx, my, x, y, LIST_W, rh - 1);
                if (sel) Ui.round(ms, x, y, LIST_W, rh - 1, Theme.ACCENT_DIM);
                else if (hover) Ui.round(ms, x, y, LIST_W, rh - 1, Theme.PANEL_3);
                Ui.rect(ms, x + 3, y + rh / 2 - 2, 4, 4, e.online ? Theme.OK : Theme.FAINT);
                int nameColor = e.banned ? Theme.BAD : (e.muted ? Theme.WARN : Theme.TEXT);
                Ui.text(ms, Ui.clip(e.name, LIST_W - 30), x + 11, y + (rh - 9) / 2, nameColor);
                if (e.banned) Ui.textRight(ms, "B", x + LIST_W - 4, y + (rh - 9) / 2, Theme.BAD);
                else if (e.muted) Ui.textRight(ms, "M", x + LIST_W - 4, y + (rh - 9) / 2, Theme.WARN);
                final AdminData.PlayerEntry entry = e;
                hits.add(new Hit(x, y, LIST_W, rh - 1, () -> select(entry)));
                y += rh;
            }
        }
        // paginare
        int pagerY = py + ph - 19;
        int pages = list == null ? 1 : Math.max(1, (list.total + 11) / 12);
        button(ms, "<", x, pagerY, 16, 14, mx, my, listPage > 0, Theme.PANEL_3, () -> ClientBridge.requestList(lastQuery, listPage - 1));
        button(ms, ">", x + LIST_W - 16, pagerY, 16, 14, mx, my, listPage + 1 < pages, Theme.PANEL_3, () -> ClientBridge.requestList(lastQuery, listPage + 1));
        Ui.textCenter(ms, (listPage + 1) + "/" + pages + "  (" + (list == null ? 0 : list.total) + ")", x + LIST_W / 2, pagerY + 3, Theme.MUTED);
        // separator
        Ui.rect(ms, px + LIST_W + 10, py + HEAD + 4, 1, ph - HEAD - 8, Theme.LINE);
    }

    private void drawRight(MatrixStack ms, int mx, int my) {
        int x = px + LIST_W + 16;
        int w = pw - (x - px) - 6;
        int y = py + HEAD + 6;
        if (selectedUuid == null) {
            Ui.textCenter(ms, "Selecteaza un jucator din lista", x + w / 2, py + ph / 2 - 4, Theme.MUTED);
            return;
        }
        // nume + stare
        String name = profile != null ? profile.name : selectedName;
        Ui.textShadow(ms, name, x, y + 1, Theme.TEXT);
        int cx = x + Ui.width(name) + 8;
        if (profile != null) {
            cx = chipStatic(ms, profile.online ? "ONLINE" : "OFFLINE", cx, y - 1, profile.online ? Theme.OK : Theme.FAINT);
            if (profile.banned) cx = chipStatic(ms, "BAN ACTIV", cx, y - 1, Theme.BAD);
            if (profile.muted) cx = chipStatic(ms, "MUTE ACTIV", cx, y - 1, Theme.WARN);
        }
        // taburi
        int ty = y + 16;
        int tx = x;
        for (Tab t : Tab.values()) {
            int tw = Ui.width(t.label) + 14;
            boolean sel = t == tab;
            boolean hover = Ui.in(mx, my, tx, ty, tw, 15);
            Ui.round(ms, tx, ty, tw, 15, sel ? Theme.ACCENT_DIM : (hover ? Theme.PANEL_3 : Theme.PANEL_2));
            if (sel) Ui.rect(ms, tx + 2, ty + 14, tw - 4, 1, Theme.ACCENT);
            Ui.textCenter(ms, t.label, tx + tw / 2, ty + 3, sel ? Theme.TEXT : Theme.MUTED);
            final Tab target = t;
            hits.add(new Hit(tx, ty, tw, 15, () -> switchTab(target)));
            tx += tw + 3;
        }
        int cy = ty + 21;
        int ch = py + ph - cy - 6;
        // fundal continut
        Ui.frame(ms, x, cy, w, ch, Theme.PANEL_2, Theme.LINE);
        if (profile == null) {
            Ui.text(ms, "Se incarca profilul...", x + 8, cy + 8, Theme.MUTED);
            return;
        }
        switch (tab) {
            case PROFILE:
                drawProfile(ms, mx, my, x + 8, cy + 7, w - 16, ch - 14);
                break;
            case LOGS:
                drawLogs(ms, mx, my, x + 8, cy + 7, w - 16, ch - 14);
                break;
            case SANCTIONS:
                drawSanctions(ms, mx, my, x + 8, cy + 7, w - 16, ch - 14);
                break;
            default:
                drawNotes(ms, mx, my, x + 8, cy + 7, w - 16, ch - 14);
                break;
        }
    }

    // ---- PROFIL

    private void drawProfile(MatrixStack ms, int mx, int my, int x, int y, int w, int h) {
        AdminData.Profile p = profile;
        int col = w / 2;
        String[][] rows = {
            {"Status", p.online ? "Online" : "Offline"},
            {"UUID", p.uuid.length() > 13 ? p.uuid.substring(0, 8) + "..." + p.uuid.substring(p.uuid.length() - 4) : p.uuid},
            {"Prima conectare", Ui.time(p.first)},
            {"Ultima conectare", p.online ? "acum" : Ui.time(p.last)},
            {"Timp jucat", Ui.duration(p.playSeconds)},
            {"Sold", p.balance.isEmpty() ? "-" : "$" + p.balance},
            {"IP", p.ip.isEmpty() ? "ascuns" : p.ip}
        };
        int ry = y;
        for (int i = 0; i < rows.length; i++) {
            int cx = x + (i % 2) * col;
            if (i % 2 == 0 && i > 0) ry += 20;
            Ui.text(ms, rows[i][0].toUpperCase(), cx, ry, Theme.FAINT);
            Ui.text(ms, Ui.clip(rows[i][1], col - 8), cx, ry + 9, Theme.TEXT);
        }
        ry += 24;
        // contoare sanctiuni
        int bx = x;
        bx = counter(ms, "WARN", p.warns, bx, ry, Theme.WARN);
        bx = counter(ms, "MUTE", p.mutes, bx, ry, Theme.WARN);
        bx = counter(ms, "KICK", p.kicks, bx, ry, Theme.VIOLET);
        counter(ms, "BAN", p.bans, bx, ry, Theme.BAD);
        ry += 22;
        // flag-uri
        Ui.text(ms, "FLAG-URI AUTOMATE (" + p.flags.size() + ")", x, ry, Theme.FAINT);
        ry += 11;
        int actionsY = y + h - 16;
        int maxFlags = Math.max(0, (actionsY - ry - 2) / 11);
        if (p.flags.isEmpty()) {
            Ui.text(ms, "Niciun flag.", x, ry, Theme.MUTED);
        }
        for (int i = 0; i < Math.min(maxFlags, p.flags.size()); i++) {
            AdminData.Flag f = p.flags.get(i);
            int sc = "HIGH".equals(f.severity) ? Theme.BAD : ("MEDIUM".equals(f.severity) ? Theme.WARN : Theme.MUTED);
            Ui.rect(ms, x, ry + 2, 3, 6, sc);
            Ui.text(ms, Ui.clip(f.trigger + " - " + f.details, w - 70), x + 7, ry, Theme.TEXT);
            Ui.textRight(ms, Ui.time(f.ts), x + w, ry, Theme.FAINT);
            ry += 11;
        }
        // actiuni rapide
        button(ms, "TP LA EL", x, actionsY, 64, 14, mx, my, p.online, Theme.ACCENT_DIM, () -> ClientBridge.action("TP_TO", p.uuid));
        button(ms, "ADU-L AICI", x + 68, actionsY, 70, 14, mx, my, p.online, Theme.ACCENT_DIM, () -> ClientBridge.action("TP_HERE", p.uuid));
    }

    private int counter(MatrixStack ms, String label, int value, int x, int y, int color) {
        int w = Math.max(46, Ui.width(label) + 22);
        Ui.frame(ms, x, y, w, 18, Theme.PANEL, Theme.alpha(color, 0xAA));
        Ui.text(ms, String.valueOf(value), x + 5, y + 5, color);
        Ui.textRight(ms, label, x + w - 5, y + 5, Theme.MUTED);
        return x + w + 5;
    }

    // ---- LOGURI

    private void drawLogs(MatrixStack ms, int mx, int my, int x, int y, int w, int h) {
        int cx = x;
        for (int i = 0; i < LOG_TYPES.length; i++) {
            final String type = LOG_TYPES[i];
            int cw = Ui.width(LOG_LABELS[i]) + 10;
            cx += chip(ms, LOG_LABELS[i], cx, y, cw, 13, mx, my, type.equals(logType), () -> {
                logType = type;
                logPage = 0;
                logs = null;
                ClientBridge.requestLogs(selectedUuid, type, 0);
            }) + 3;
        }
        int ry = y + 19;
        int footer = y + h - 14;
        if (logs == null) {
            Ui.text(ms, "Se incarca...", x, ry, Theme.MUTED);
        } else if (logs.rows.isEmpty()) {
            Ui.text(ms, "Nicio inregistrare.", x, ry, Theme.MUTED);
        } else {
            int tw = 78;
            for (AdminData.LogRow r : logs.rows) {
                if (ry + 10 > footer) break;
                Ui.text(ms, Ui.time(r.ts), x, ry, Theme.FAINT);
                String main = r.a;
                String rest = (r.b.isEmpty() ? "" : r.b) + (r.c.isEmpty() ? "" : "  " + r.c);
                int aw = logType.equals("CHAT") || logType.equals("CMD") ? w - tw : 62;
                Ui.text(ms, Ui.clip(main, aw - 4), x + tw, ry, logType.equals("CMD") ? Theme.VIOLET : Theme.TEXT);
                if (!rest.isEmpty() && aw < w - tw) {
                    Ui.text(ms, Ui.clip(rest, w - tw - aw), x + tw + aw, ry, Theme.MUTED);
                }
                ry += 10;
            }
        }
        int pages = logs == null ? 1 : Math.max(1, logs.pages);
        button(ms, "<", x, footer, 16, 13, mx, my, logPage > 0, Theme.PANEL_3, () -> {
            logPage--;
            logs = null;
            ClientBridge.requestLogs(selectedUuid, logType, logPage);
        });
        button(ms, ">", x + 20, footer, 16, 13, mx, my, logPage + 1 < pages, Theme.PANEL_3, () -> {
            logPage++;
            logs = null;
            ClientBridge.requestLogs(selectedUuid, logType, logPage);
        });
        Ui.text(ms, "Pagina " + (logPage + 1) + "/" + pages + (logs == null ? "" : "  -  " + logs.total + " inregistrari"), x + 42, footer + 3, Theme.MUTED);
    }

    // ---- SANCTIUNI

    private void drawSanctions(MatrixStack ms, int mx, int my, int x, int y, int w, int h) {
        // formular
        int cx = x;
        for (String t : S_TYPES) {
            int cw = Ui.width(t) + 12;
            int col = t.equals("BAN") ? Theme.BAD : (t.equals("KICK") ? Theme.VIOLET : Theme.WARN);
            cx += chip(ms, t, cx, y, cw, 14, mx, my, t.equals(sType), col, () -> sType = t) + 3;
        }
        boolean timed = sType.equals("MUTE") || sType.equals("BAN");
        if (timed) {
            cx += 6;
            for (int i = 0; i < DUR_LABEL.length; i++) {
                final int minutes = DUR_MIN[i];
                int cw = Ui.width(DUR_LABEL[i]) + 8;
                cx += chip(ms, DUR_LABEL[i], cx, y, cw, 14, mx, my, sMinutes == minutes, Theme.ACCENT, () -> sMinutes = minutes) + 2;
            }
        }
        int fy = y + 19;
        int applyW = 54;
        int fw = w - applyW - 4;
        Ui.frame(ms, x, fy, fw, 16, Theme.PANEL, reason.isFocused() ? Theme.ACCENT : Theme.LINE);
        reason.x = x + 5;
        reason.y = fy + 4;
        reason.setWidth(fw - 10);
        reason.render(ms, mx, my, 0);
        button(ms, "APLICA", x + fw + 4, fy, applyW, 16, mx, my, true, sType.equals("BAN") ? 0xFF8A2D3A : Theme.ACCENT_DIM, this::applySanction);

        // istoric
        int ly = fy + 24;
        Ui.text(ms, "ISTORIC (" + profile.sanctions.size() + ")", x, ly - 1, Theme.FAINT);
        ly += 11;
        int rowH = 12;
        int fit = Math.max(1, (y + h - ly) / rowH);
        int max = Math.max(0, profile.sanctions.size() - fit);
        scroll = Math.max(0, Math.min(scroll, max));
        if (profile.sanctions.isEmpty()) Ui.text(ms, "Fara sanctiuni.", x, ly, Theme.MUTED);
        for (int i = scroll; i < Math.min(profile.sanctions.size(), scroll + fit); i++) {
            final AdminData.Sanction s = profile.sanctions.get(i);
            int tc = "BAN".equals(s.type) ? Theme.BAD : ("KICK".equals(s.type) ? Theme.VIOLET : Theme.WARN);
            String status = s.deleted ? "REVOCAT" : (s.active ? "ACTIV" : ("MUTE".equals(s.type) || "BAN".equals(s.type) ? "EXPIRAT" : ""));
            int sc = s.deleted ? Theme.FAINT : (s.active ? Theme.BAD : Theme.MUTED);
            Ui.text(ms, s.type, x, ly, tc);
            String dur = ("MUTE".equals(s.type) || "BAN".equals(s.type)) ? " " + Ui.minutesLabel(s.minutes) : "";
            Ui.text(ms, dur, x + 30, ly, Theme.MUTED);
            int reasonX = x + 62;
            int right = x + w - 56;
            Ui.text(ms, Ui.clip(s.reason + "  (" + s.admin + ")", right - reasonX - 4), reasonX, ly, s.deleted ? Theme.FAINT : Theme.TEXT);
            Ui.textRight(ms, status, x + w - (s.active && !s.deleted ? 14 : 2), ly, sc);
            if (s.active && !s.deleted) {
                button(ms, "x", x + w - 11, ly - 2, 11, 11, mx, my, true, 0xFF5B2A35, () -> ClientBridge.revoke(s.id));
            }
            ly += rowH;
        }
        if (max > 0) Ui.textRight(ms, "scroll", x + w, y + h - 8, Theme.FAINT);
    }

    // ---- NOTE

    private void drawNotes(MatrixStack ms, int mx, int my, int x, int y, int w, int h) {
        int addW = 60;
        int fw = w - addW - 4;
        Ui.frame(ms, x, y, fw, 16, Theme.PANEL, note.isFocused() ? Theme.ACCENT : Theme.LINE);
        note.x = x + 5;
        note.y = y + 4;
        note.setWidth(fw - 10);
        note.render(ms, mx, my, 0);
        button(ms, "ADAUGA", x + fw + 4, y, addW, 16, mx, my, true, Theme.ACCENT_DIM, this::addNote);

        int ly = y + 24;
        Ui.text(ms, "NOTE STAFF (" + profile.notes.size() + ")", x, ly - 1, Theme.FAINT);
        ly += 11;
        int rowH = 22;
        int fit = Math.max(1, (y + h - ly) / rowH);
        int max = Math.max(0, profile.notes.size() - fit);
        scroll = Math.max(0, Math.min(scroll, max));
        if (profile.notes.isEmpty()) Ui.text(ms, "Nicio nota.", x, ly, Theme.MUTED);
        for (int i = scroll; i < Math.min(profile.notes.size(), scroll + fit); i++) {
            final AdminData.Note n = profile.notes.get(i);
            Ui.text(ms, n.admin, x, ly, Theme.ACCENT);
            Ui.textRight(ms, Ui.time(n.ts), x + w - 14, ly, Theme.FAINT);
            Ui.text(ms, Ui.clip(n.text, w - 16), x, ly + 10, Theme.TEXT);
            button(ms, "x", x + w - 11, ly - 1, 11, 11, mx, my, true, 0xFF5B2A35, () -> ClientBridge.noteDel(n.id));
            ly += rowH;
        }
    }

    // ------------------------------------------------------------------ componente

    private void button(MatrixStack ms, String label, int x, int y, int w, int h, int mx, int my,
                        boolean enabled, int color, Runnable action) {
        boolean hover = enabled && Ui.in(mx, my, x, y, w, h);
        int bg = !enabled ? Theme.DISABLED : (hover ? Theme.lighten(color, 28) : color);
        Ui.round(ms, x, y, w, h, bg);
        Ui.textCenter(ms, label, x + w / 2, y + (h - 8) / 2, enabled ? Theme.TEXT : Theme.FAINT);
        if (enabled) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    private int chip(MatrixStack ms, String label, int x, int y, int w, int h, int mx, int my,
                     boolean selected, Runnable action) {
        return chip(ms, label, x, y, w, h, mx, my, selected, Theme.ACCENT, action);
    }

    private int chip(MatrixStack ms, String label, int x, int y, int w, int h, int mx, int my,
                     boolean selected, int color, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        int border = selected ? color : Theme.LINE;
        int fill = selected ? Theme.mix(Theme.PANEL, color, 0.30f) : (hover ? Theme.PANEL_3 : Theme.PANEL);
        Ui.frame(ms, x, y, w, h, fill, border);
        Ui.textCenter(ms, label, x + w / 2, y + (h - 8) / 2, selected ? Theme.TEXT : Theme.MUTED);
        hits.add(new Hit(x, y, w, h, action));
        return w;
    }

    /** Eticheta de stare (nu e clicabila). Intoarce pozitia x urmatoare. */
    private int chipStatic(MatrixStack ms, String label, int x, int y, int color) {
        int w = Ui.width(label) + 10;
        Ui.round(ms, x, y, w, 12, Theme.alpha(color, 0x33));
        Ui.text(ms, label, x + 5, y + 2, color);
        return x + w + 4;
    }

    private void drawToast(MatrixStack ms) {
        if (System.currentTimeMillis() > toastUntil || toastText.isEmpty()) return;
        int w = Math.min(pw - 20, Ui.width(toastText) + 20);
        int x = px + (pw - w) / 2;
        int y = py + ph - 22;
        int color = toastError ? Theme.BAD : Theme.OK;
        Ui.frame(ms, x, y, w, 16, Theme.PANEL_3, color);
        Ui.textCenter(ms, Ui.clip(toastText, w - 12), x + w / 2, y + 4, color);
    }

    // ------------------------------------------------------------------ input

    private List<TextFieldWidget> fields() {
        List<TextFieldWidget> f = new ArrayList<>();
        f.add(search);
        if (tab == Tab.SANCTIONS && selectedUuid != null) f.add(reason);
        if (tab == Tab.NOTES && selectedUuid != null) f.add(note);
        return f;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            // intai campurile de text (isi gestioneaza singure focusul)
            boolean onField = false;
            for (TextFieldWidget f : fields()) {
                boolean inside = Ui.in((int) mx, (int) my, f.x - 4, f.y - 4, f.getWidth() + 8, 16);
                f.mouseClicked(mx, my, button);
                if (inside) {
                    focus(f, true);
                    onField = true;
                } else {
                    focus(f, false);
                }
            }
            if (onField) return true;
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in((int) mx, (int) my, h.x, h.y, h.w, h.h)) {
                    click();
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (tab == Tab.SANCTIONS || tab == Tab.NOTES) {
            scroll -= (int) Math.signum(delta);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (TextFieldWidget f : fields()) {
            if (f.isFocused()) {
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    focus(f, false);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                    if (f == reason) applySanction();
                    else if (f == note) addNote();
                    return true;
                }
                if (f.keyPressed(key, scan, mods)) return true;
                // nu lasam tasta de deschidere sa inchida panoul cat timp scrii
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        for (TextFieldWidget f : fields()) {
            if (f.isFocused() && f.charTyped(c, mods)) return true;
        }
        return super.charTyped(c, mods);
    }
}
