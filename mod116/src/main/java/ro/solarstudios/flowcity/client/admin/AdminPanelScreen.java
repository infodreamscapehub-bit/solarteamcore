package ro.solarstudios.flowcity.client.admin;

import net.minecraft.util.text.StringTextComponent;

import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.ITextComponent;
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
        PROFILE("PROFIL"), LOGS("LOGURI"), SANCTIONS("SANCTIUNI"), NOTES("NOTE"), JOBS("JOBURI"), IPS("IP"), SS("SS");

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

    private TextFieldWidget idField;
    private TextFieldWidget cashField;
    private boolean cashOpen;
    private final long openedAt = System.currentTimeMillis();

    public AdminPanelScreen(String staffName, String serverName) {
        super(new StringTextComponent("eAdmin"));
        this.staffName = staffName;
        this.serverName = serverName;
    }

    // ------------------------------------------------------------------ ciclu de viata

    @Override
    protected void init() {
        search = field("Cauta nume sau #ID...", 32, s -> {
            searchDirty = true;
            searchTypedAt = System.currentTimeMillis();
        });
        reason = field("Motiv sanctiune...", 120, null);
        note = field("Scrie o nota...", 190, null);
        idField = field("ID nou", 9, null);
        cashField = field("suma", 12, null);
        if (list == null) ClientBridge.requestList("", 0);
    }

    private TextFieldWidget field(String hint, int max, Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(font, 0, 0, 100, 14, new StringTextComponent(hint));
        f.setMaxLength(max);
        f.setBordered(false);
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
        DashPanel.poll();
        search.tick();
        reason.tick();
        note.tick();
        cashField.tick();
        if (searchDirty && System.currentTimeMillis() - searchTypedAt > 300) {
            searchDirty = false;
            String q = search.getValue().trim();
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
        AdminX.requestFrozen(e.uuid);
        if (tab == Tab.JOBS) AdminX.requestJobs(e.uuid);
        if (tab == Tab.IPS) AdminX.requestIps(e.uuid);
        if (tab == Tab.LOGS) ClientBridge.requestLogs(e.uuid, logType, 0);
    }

    private void switchTab(Tab t) {
        tab = t;
        scroll = 0;
        focus(reason, false);
        focus(note, false);
        focus(search, false);
        if (t == Tab.JOBS && selectedUuid != null) AdminX.requestJobs(selectedUuid);
        if (t == Tab.IPS && selectedUuid != null) AdminX.requestIps(selectedUuid);
        if (t == Tab.LOGS && selectedUuid != null && logs == null) ClientBridge.requestLogs(selectedUuid, logType, logPage);
    }

    private void applySanction() {
        if (selectedUuid == null) return;
        boolean timed = sType.equals("MUTE") || sType.equals("BAN");
        ClientBridge.sanction(selectedUuid, sType, timed ? sMinutes : 0, reason.getValue().trim());
        reason.setValue("");
        focus(reason, false);
    }

    private void addNote() {
        if (selectedUuid == null || note.getValue().trim().isEmpty()) return;
        ClientBridge.noteAdd(selectedUuid, note.getValue().trim());
        note.setValue("");
        focus(note, false);
    }

    /** Focus fara dependenta de numele metodei: TextFieldWidget isi seteaza singur focusul la click. */
    private void focus(TextFieldWidget f, boolean on) {
        f.mouseClicked(on ? f.x + 1 : -10000, on ? f.y + 1 : -10000, 0);
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSound.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ------------------------------------------------------------------ desenare

    @Override
    public void render(com.mojang.blaze3d.matrix.MatrixStack poseIn, int mx, int my, float pt) {
        GuiGraphics ms = new GuiGraphics(poseIn);
        hits.clear();
        pw = Math.min(width - 16, 476);
        ph = Math.min(height - 16, 274);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        float open = Math.min(1f, (System.currentTimeMillis() - openedAt) / 260f);
        float ease = 1f - (1f - open) * (1f - open) * (1f - open);
        py += Math.round((1f - ease) * 14f); // panoul urca usor la deschidere

        Ui.rect(ms, 0, 0, width, height, Theme.alpha(Theme.DIM, Math.max(1, (int) (((Theme.DIM >>> 24) & 0xFF) * ease))));
        Ui.frame(ms, px, py, pw, ph, Theme.PANEL, Theme.LINE);
        drawHeader(ms, mx, my);
        drawList(ms, mx, my);
        drawRight(ms, mx, my);
        drawToast(ms);
        super.render(ms.pose(), mx, my, pt);
    }

    private void drawHeader(GuiGraphics ms, int mx, int my) {
        Ui.rect(ms, px + 1, py + 1, pw - 2, HEAD - 1, Theme.PANEL_2);
        Ui.rect(ms, px + 1, py + HEAD, pw - 2, 1, Theme.ACCENT_DIM);
        Ui.rect(ms, px + 8, py + 8, 3, 9, Theme.ACCENT);
        Ui.textShadow(ms, serverName.toUpperCase(), px + 15, py + 8, Theme.ACCENT);
        Ui.text(ms, "eAdmin", px + 15 + Ui.width(serverName.toUpperCase()) + 6, py + 8, Theme.TEXT);
        Ui.textRight(ms, "staff: " + staffName, px + pw - 142, py + 8, Theme.MUTED);
        button(ms, "REFRESH", px + pw - 56, py + 5, 34, 14, mx, my, true, Theme.PANEL_3, () -> {
            ClientBridge.requestList(lastQuery, listPage);
            DashPanel.forceRefresh();
            if (selectedUuid != null) ClientBridge.requestProfile(selectedUuid);
        });
        button(ms, "PANOU", px + pw - 136, py + 5, 36, 14, mx, my, selectedUuid != null, Theme.ACCENT_DIM, () -> {
            selectedUuid = null;
            profile = null;
            logs = null;
            DashPanel.forceRefresh();
        });
        button(ms, "ROATA", px + pw - 96, py + 5, 36, 14, mx, my, true, Theme.VIOLET, () ->
            Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.wheel.WheelAdminScreen()));
        button(ms, "X", px + pw - 18, py + 5, 12, 14, mx, my, true, 0xFF6B2F2B, this::onClose);
    }

    private void drawList(GuiGraphics ms, int mx, int my) {
        int x = px + 6;
        int top = py + HEAD + 6;
        // casuta de cautare
        Ui.frame(ms, x, top, LIST_W, 16, Theme.PANEL_2, search.isFocused() ? Theme.ACCENT : Theme.LINE);
        search.x = x + 5;
        search.y = top + 4;
        search.setWidth(LIST_W - 10);
        search.render(ms.pose(), mx, my, 0);

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
                int right = x + LIST_W - 4;
                if (e.banned) {
                    Ui.textRight(ms, "B", right, y + (rh - 9) / 2, Theme.BAD);
                    right -= 9;
                } else if (e.muted) {
                    Ui.textRight(ms, "M", right, y + (rh - 9) / 2, Theme.WARN);
                    right -= 9;
                }
                String idLabel = e.id > 0 ? "#" + e.id : "";
                if (!idLabel.isEmpty()) Ui.textRight(ms, idLabel, right, y + (rh - 9) / 2, sel ? Theme.TEXT : Theme.FAINT);
                Ui.text(ms, Ui.clip(e.name, LIST_W - 22 - Ui.width(idLabel)), x + 11, y + (rh - 9) / 2, nameColor);
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

    private void drawRight(GuiGraphics ms, int mx, int my) {
        int x = px + LIST_W + 16;
        int w = pw - (x - px) - 6;
        int y = py + HEAD + 6;
        if (selectedUuid == null) {
            DashPanel.draw(ms, x, y, w, py + ph - y - 6, System.currentTimeMillis());
            return;
        }
        // nume + stare
        String name = profile != null ? profile.name : selectedName;
        Ui.textShadow(ms, name, x, y + 1, Theme.TEXT);
        int cx = x + Ui.width(name) + 8;
        if (profile != null) {
            if (profile.id > 0) cx = chipStatic(ms, "#" + profile.id, cx, y - 1, Theme.ACCENT);
            cx = chipStatic(ms, profile.online ? "ONLINE" : "OFFLINE", cx, y - 1, profile.online ? Theme.OK : Theme.FAINT);
            if (profile.banned) cx = chipStatic(ms, "BAN ACTIV", cx, y - 1, Theme.BAD);
            if (profile.muted) cx = chipStatic(ms, "MUTE ACTIV", cx, y - 1, Theme.WARN);
        }
        // actiuni rapide (stil eAdmin EGK)
        int ay = y + 15;
        drawActions(ms, mx, my, x, ay, w);
        int tabsY = ay + 17;
        if (cashOpen) {
            drawCashBar(ms, mx, my, x, tabsY, w);
            tabsY += 18;
        }
        // taburi
        int ty = tabsY;
        int tx = x;
        for (Tab t : Tab.values()) {
            int tw = Ui.width(t.label) + 10;
            boolean sel = t == tab;
            boolean hover = Ui.in(mx, my, tx, ty, tw, 15);
            Ui.round(ms, tx, ty, tw, 15, sel ? Theme.ACCENT_DIM : (hover ? Theme.PANEL_3 : Theme.PANEL_2));
            if (sel) Ui.rect(ms, tx + 2, ty + 14, tw - 4, 1, Theme.ACCENT);
            Ui.textCenter(ms, t.label, tx + tw / 2, ty + 3, sel ? Theme.TEXT : Theme.MUTED);
            final Tab target = t;
            hits.add(new Hit(tx, ty, tw, 15, () -> switchTab(target)));
            tx += tw + 2;
        }
        int cy = ty + 18;
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
            case JOBS:
                drawJobs(ms, x + 8, cy + 7, w - 16, ch - 14);
                break;
            case IPS:
                drawIps(ms, x + 8, cy + 7, w - 16, ch - 14);
                break;
            case SS:
                drawSs(ms, mx, my, x + 8, cy + 7, w - 16, ch - 14);
                break;
            default:
                drawNotes(ms, mx, my, x + 8, cy + 7, w - 16, ch - 14);
                break;
        }
    }

    // ---- actiuni rapide

    private void drawActions(GuiGraphics ms, int mx, int my, int x, int y, int w) {
        AdminData.Profile p = profile;
        boolean on = p != null && p.online;
        boolean frz = p != null && AdminX.frozen.contains(p.uuid);
        String[] labels = {"TP", "AICI", "INV", "SPEC", frz ? "DEZG." : "FRZ", "CASH", "WARN", "KICK", "BAN"};
        int[] colors = {Theme.ACCENT_DIM, Theme.ACCENT_DIM, Theme.ACCENT_DIM, Theme.VIOLET, frz ? 0xFF2F8CFF : 0xFF2A5E8A, 0xFF1F7A4D,
            0xFF8A6A1F, 0xFF7A4B1F, 0xFF8A2A35};
        boolean[] enabled = {on, on, on, on || AdminX.frozen == null, on, p != null, p != null, p != null, p != null};
        int gap = 3;
        int bw = (w - gap * (labels.length - 1)) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            button(ms, labels[i], x + i * (bw + gap), y, bw, 14, mx, my, p != null && (enabled[i] || i == 3), colors[i], () -> runAction(k));
        }
    }

    private void runAction(int k) {
        if (profile == null) return;
        String u = profile.uuid;
        switch (k) {
            case 0: ClientBridge.action("TP_TO", u); break;
            case 1: ClientBridge.action("TP_HERE", u); break;
            case 2: AdminX.inv(u); break;
            case 3: AdminX.spec(u); break;
            case 4: AdminX.freeze(u); break;
            case 5:
                cashOpen = !cashOpen;
                break;
            case 6: quickSanction("WARN"); break;
            case 7: quickSanction("KICK"); break;
            default: quickSanction("BAN"); break;
        }
    }

    private void quickSanction(String type) {
        sType = type;
        if (type.equals("BAN")) sMinutes = 1440;
        switchTab(Tab.SANCTIONS);
        focus(reason, true);
    }

    private void drawCashBar(GuiGraphics ms, int mx, int my, int x, int y, int w) {
        Ui.frame(ms, x, y, w, 16, Theme.PANEL, Theme.alpha(Theme.OK, 0xAA));
        Ui.text(ms, "CASH $", x + 5, y + 4, Theme.OK);
        int fx = x + 44;
        int fw = 80;
        Ui.frame(ms, fx, y + 2, fw, 12, Theme.PANEL_2, cashField.isFocused() ? Theme.ACCENT : Theme.LINE);
        cashField.x = fx + 4;
        cashField.y = y + 4;
        cashField.setWidth(fw - 8);
        cashField.render(ms.pose(), mx, my, 0);
        int bx = fx + fw + 6;
        int bw = (x + w - bx - 8) / 3;
        button(ms, "SETEAZA", bx, y + 2, bw, 12, mx, my, true, Theme.ACCENT_DIM, () -> sendCash("set"));
        button(ms, "+ ADAUGA", bx + bw + 2, y + 2, bw, 12, mx, my, true, 0xFF1F7A4D, () -> sendCash("add"));
        button(ms, "- SCADE", bx + 2 * (bw + 2), y + 2, bw, 12, mx, my, true, 0xFF8A2A35, () -> sendCash("take"));
    }

    private void sendCash(String mode) {
        if (profile == null) return;
        double a;
        try {
            a = Double.parseDouble(cashField.getValue().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            onToast("Scrie o suma valida.", true);
            return;
        }
        AdminX.cash(profile.uuid, mode, a);
        cashField.setValue("");
        ClientBridge.requestProfile(profile.uuid);
    }

    // ---- JOBURI

    private void drawJobs(GuiGraphics ms, int x, int y, int w, int h) {
        Ui.text(ms, "JOB", x, y, Theme.FAINT);
        Ui.text(ms, "XP", x + w * 45 / 100, y, Theme.FAINT);
        Ui.textRight(ms, "CASTIGAT TOTAL", x + w, y, Theme.FAINT);
        Ui.rect(ms, x, y + 10, w, 1, Theme.LINE);
        if (!profile.uuid.equals(AdminX.jobsUuid)) {
            Ui.text(ms, "Se incarca...", x, y + 16, Theme.MUTED);
            return;
        }
        if (AdminX.jobs.isEmpty()) {
            Ui.text(ms, "Fara istoric de joburi.", x, y + 16, Theme.MUTED);
            return;
        }
        int ry = y + 16;
        for (AdminX.JobRow r : AdminX.jobs) {
            if (ry > y + h - 12) break;
            boolean cur = r.job.equalsIgnoreCase(AdminX.jobsCurrent);
            Ui.rect(ms, x, ry + 1, 3, 8, cur ? Theme.OK : Theme.FAINT);
            Ui.text(ms, Ui.clip(r.job, w * 40 / 100), x + 7, ry, cur ? Theme.TEXT : Theme.MUTED);
            Ui.text(ms, r.xp + " XP", x + w * 45 / 100, ry, Theme.ACCENT);
            Ui.textRight(ms, "$" + Ui.money(r.earned), x + w, ry, Theme.OK);
            ry += 13;
        }
        if (!AdminX.jobsCurrent.isEmpty()) Ui.text(ms, "Job curent: " + AdminX.jobsCurrent, x, y + h - 9, Theme.FAINT);
    }

    // ---- IP / conturi multiple

    private void drawIps(GuiGraphics ms, int x, int y, int w, int h) {
        Ui.text(ms, "VERIFICARE IP / CONTURI MULTIPLE", x, y, Theme.FAINT);
        Ui.rect(ms, x, y + 10, w, 1, Theme.LINE);
        if (!profile.uuid.equals(AdminX.ipsUuid)) {
            Ui.text(ms, profile.ip.isEmpty() && !profile.uuid.equals(AdminX.ipsUuid) ? "Se incarca (necesita flowcity.eadmin.ip)..." : "Se incarca...", x, y + 16, Theme.MUTED);
            return;
        }
        int ry = y + 16;
        Ui.text(ms, "Istoric IP-uri", x, ry, Theme.MUTED);
        ry += 11;
        if (AdminX.ipHistory.isEmpty()) Ui.text(ms, "Niciun IP inregistrat.", x, ry, Theme.FAINT);
        int shown = 0;
        for (AdminX.IpRow r : AdminX.ipHistory) {
            if (shown++ >= 5) break;
            Ui.text(ms, r.ip, x + 4, ry, Theme.TEXT);
            Ui.textRight(ms, Ui.time(r.ts), x + w, ry, Theme.FAINT);
            ry += 10;
        }
        ry += 8;
        boolean multi = !AdminX.ipOthers.isEmpty();
        Ui.text(ms, multi ? "CONTURI PE ACELEASI IP-URI (" + AdminX.ipOthers.size() + ")" : "Niciun alt cont pe aceste IP-uri.", x, ry, multi ? Theme.WARN : Theme.OK);
        ry += 11;
        for (AdminX.Other o : AdminX.ipOthers) {
            if (ry > y + h - 10) break;
            Ui.rect(ms, x, ry + 1, 3, 7, Theme.WARN);
            Ui.text(ms, Ui.clip(o.name, w / 2), x + 7, ry, Theme.TEXT);
            Ui.textRight(ms, o.ip, x + w, ry, Theme.FAINT);
            ry += 10;
        }
    }

    // ---- SS

    private void drawSs(GuiGraphics ms, int mx, int my, int x, int y, int w, int h) {
        button(ms, "SOLICITA SS", x, y, 80, 14, mx, my, profile.online, Theme.ACCENT_DIM, () -> AdminX.requestSs(profile.uuid));
        button(ms, "STERGE", x + 84, y, 50, 14, mx, my, AdminX.ssTex != null, 0xFF6B2F2B, AdminX::clearSs);
        Ui.text(ms, Ui.clip(AdminX.ssStatus, w - 142), x + 140, y + 3, Theme.MUTED);
        int fy = y + 20;
        int fh = h - 22;
        Ui.frame(ms, x, fy, w, fh, Theme.PANEL, Theme.LINE);
        if (AdminX.ssTex == null || AdminX.ssW <= 0) {
            Ui.textCenter(ms, "Nicio imagine disponibila", x + w / 2, fy + fh / 2 - 8, Theme.MUTED);
            Ui.textCenter(ms, "Apasa SOLICITA SS (jucatorul trebuie sa aiba modul).", x + w / 2, fy + fh / 2 + 3, Theme.FAINT);
            return;
        }
        float k = Math.min((w - 4) / (float) AdminX.ssW, (fh - 4) / (float) AdminX.ssH);
        int dw = Math.round(AdminX.ssW * k);
        int dh = Math.round(AdminX.ssH * k);
        int dx = x + (w - dw) / 2;
        int dy = fy + (fh - dh) / 2;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
        ms.blit(AdminX.ssTex, dx, dy, dw, dh, 0f, 0f, AdminX.ssW, AdminX.ssH, AdminX.ssW, AdminX.ssH);
    }

    // ---- PROFIL

    private void drawProfile(GuiGraphics ms, int mx, int my, int x, int y, int w, int h) {
        AdminData.Profile p = profile;
        int col = w / 2;
        String[][] rows = {
            {"Status", p.online ? "Online" : "Offline"},
            {"ID jucator", p.id > 0 ? "#" + p.id : "-"},
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
        // schimbare ID (serverul verifica permisiunea flowcity.id.admin)
        int fx = x;
        int fw = Math.min(90, Math.max(40, x + w - fx - 78));
        Ui.frame(ms, fx, actionsY, fw, 14, Theme.PANEL, idField.isFocused() ? Theme.ACCENT : Theme.LINE);
        idField.x = fx + 4;
        idField.y = actionsY + 3;
        idField.setWidth(fw - 8);
        idField.render(ms.pose(), mx, my, 0);
        button(ms, "SETEAZA ID", fx + fw + 4, actionsY, 74, 14, mx, my, true, 0xFF4B4FA8, this::setId);
    }

    /** Trimite cererea de schimbare a ID-ului; cu "swap" in camp (ex. "12 swap") schimba ID-urile intre doi jucatori. */
    private void setId() {
        if (profile == null) return;
        String[] parts = idField.getValue().trim().replace("#", "").split("\\s+");
        int n;
        try {
            n = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            onToast("Scrie un numar, ex. 12 (sau '12 swap').", true);
            return;
        }
        IdsClient.set(profile.uuid, n, parts.length > 1 && parts[1].equalsIgnoreCase("swap"));
        idField.setValue("");
    }

    private int counter(GuiGraphics ms, String label, int value, int x, int y, int color) {
        int w = Math.max(46, Ui.width(label) + 22);
        Ui.frame(ms, x, y, w, 18, Theme.PANEL, Theme.alpha(color, 0xAA));
        Ui.text(ms, String.valueOf(value), x + 5, y + 5, color);
        Ui.textRight(ms, label, x + w - 5, y + 5, Theme.MUTED);
        return x + w + 5;
    }

    // ---- LOGURI

    private void drawLogs(GuiGraphics ms, int mx, int my, int x, int y, int w, int h) {
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

    private void drawSanctions(GuiGraphics ms, int mx, int my, int x, int y, int w, int h) {
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
        reason.render(ms.pose(), mx, my, 0);
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
                button(ms, "x", x + w - 11, ly - 2, 11, 11, mx, my, true, 0xFF6B2F2B, () -> ClientBridge.revoke(s.id));
            }
            ly += rowH;
        }
        if (max > 0) Ui.textRight(ms, "scroll", x + w, y + h - 8, Theme.FAINT);
    }

    // ---- NOTE

    private void drawNotes(GuiGraphics ms, int mx, int my, int x, int y, int w, int h) {
        int addW = 60;
        int fw = w - addW - 4;
        Ui.frame(ms, x, y, fw, 16, Theme.PANEL, note.isFocused() ? Theme.ACCENT : Theme.LINE);
        note.x = x + 5;
        note.y = y + 4;
        note.setWidth(fw - 10);
        note.render(ms.pose(), mx, my, 0);
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
            button(ms, "x", x + w - 11, ly - 1, 11, 11, mx, my, true, 0xFF6B2F2B, () -> ClientBridge.noteDel(n.id));
            ly += rowH;
        }
    }

    // ------------------------------------------------------------------ componente

    private void button(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                        boolean enabled, int color, Runnable action) {
        boolean hover = enabled && Ui.in(mx, my, x, y, w, h);
        int bg = !enabled ? Theme.DISABLED : (hover ? Theme.lighten(color, 28) : color);
        Ui.round(ms, x, y, w, h, bg);
        Ui.textCenter(ms, label, x + w / 2, y + (h - 8) / 2, enabled ? Theme.TEXT : Theme.FAINT);
        if (enabled) {
            hits.add(new Hit(x, y, w, h, action));
        }
    }

    private int chip(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                     boolean selected, Runnable action) {
        return chip(ms, label, x, y, w, h, mx, my, selected, Theme.ACCENT, action);
    }

    private int chip(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
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
    private int chipStatic(GuiGraphics ms, String label, int x, int y, int color) {
        int w = Ui.width(label) + 10;
        Ui.round(ms, x, y, w, 12, Theme.alpha(color, 0x33));
        Ui.text(ms, label, x + 5, y + 2, color);
        return x + w + 4;
    }

    private void drawToast(GuiGraphics ms) {
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
        if (tab == Tab.PROFILE && selectedUuid != null && profile != null) f.add(idField);
        if (cashOpen && selectedUuid != null) f.add(cashField);
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
                    else if (f == idField) setId();
                    else if (f == cashField) sendCash("set");
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
