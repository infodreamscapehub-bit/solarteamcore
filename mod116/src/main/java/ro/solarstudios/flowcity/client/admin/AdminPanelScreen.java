package ro.solarstudios.flowcity.client.admin;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.StringTextComponent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ClientBridge;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Panoul de admin FlowCity, refacut in tema de iarna: ecran scalat 520x290, sidebar cu iconite, lista de jucatori cu fete skin,
 * card de detalii cu actiuni grupate, dialog de confirmare, toast-uri si scurtaturi de tastatura.
 * Toate datele si actiunile vin/pleaca prin ClientBridge / AdminX / IdsClient (protocol neschimbat).
 */
public class AdminPanelScreen extends Screen {
    private enum Tab {
        DASH("PANOU"), PROFILE("PROFIL"), LOGS("JURNAL"), SANCTIONS("SANCTIUNI"), NOTES("NOTE"), JOBS("JOBURI"), IPS("IP"), SS("SS");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private static final class Hit {
        final float x;
        final float y;
        final float w;
        final float h;
        final Runnable action;

        Hit(float x, float y, float w, float h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }
    }

    private static final class Modal {
        String title;
        String body;
        String okLabel;
        int color;
        Runnable ok;
    }

    private static final float W = 520f;
    private static final float H = 290f;
    private static final float S = 7.4f;
    private static final float XS = 6.2f;
    private static final int LINE = 0xFF22324A;
    private static final int PAGE = 12;

    private static final String[] LOG_TYPES = {"CHAT", "CMD", "CONN", "CREATIVE", "INV", "TX"};
    private static final String[] LOG_LABELS = {"CHAT", "COMENZI", "CONEX.", "CREATIV", "INVENTAR", "BANI"};
    private static final String[] S_TYPES = {"WARN", "MUTE", "KICK", "BAN"};
    private static final int[] DUR_MIN = {15, 60, 1440, 10080, 43200, 0};
    private static final String[] DUR_LABEL = {"15m", "1h", "1d", "7d", "30d", "PERM"};

    private final String staffName;
    private final String serverName;
    private final List<Hit> hits = new ArrayList<>();
    private final Map<TextFieldWidget, float[]> fieldRects = new HashMap<>();

    private TextFieldWidget search;
    private TextFieldWidget reason;
    private TextFieldWidget note;
    private TextFieldWidget idField;
    private TextFieldWidget cashField;

    private AdminData.PlayerList list;
    private AdminData.Profile profile;
    private AdminData.Logs logs;
    private String selectedUuid;
    private String selectedName = "";
    private Tab tab = Tab.DASH;
    private int listPage;
    private String logType = "CHAT";
    private int logPage;
    private String sType = "WARN";
    private int sMinutes = 60;
    private int scroll;
    private boolean cashOpen;

    private String toastText = "";
    private boolean toastError;
    private long toastAt;
    private long toastUntil;
    private boolean searchDirty;
    private long searchTypedAt;
    private String lastQuery = "";
    private boolean swallowChar;

    private Modal modal;
    private boolean drawingModal;
    private String tooltip;
    private float tipX;
    private float tipY;

    private final long openedAt = System.currentTimeMillis();
    private long tabAt = openedAt;
    private float offX;
    private float offY;
    private float scale = 1f;
    private float lmx;
    private float lmy;

    public AdminPanelScreen(String staffName, String serverName) {
        super(new StringTextComponent("Admin"));
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
        f.setTextColor(Sk.WHITE);
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
        idField.tick();
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
        this.toastAt = System.currentTimeMillis();
        this.toastUntil = toastAt + 4000;
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
        if (tab == Tab.DASH) {
            tab = Tab.PROFILE;
            tabAt = System.currentTimeMillis();
        }
        if (tab == Tab.JOBS) AdminX.requestJobs(e.uuid);
        if (tab == Tab.IPS) AdminX.requestIps(e.uuid);
        if (tab == Tab.LOGS) ClientBridge.requestLogs(e.uuid, logType, 0);
    }

    private boolean tabEnabled(Tab t) {
        return t == Tab.DASH || selectedUuid != null;
    }

    private void switchTab(Tab t) {
        if (!tabEnabled(t)) return;
        tab = t;
        tabAt = System.currentTimeMillis();
        scroll = 0;
        focus(reason, false);
        focus(note, false);
        focus(search, false);
        focus(idField, false);
        focus(cashField, false);
        if (t == Tab.DASH) DashPanel.forceRefresh();
        if (t == Tab.JOBS && selectedUuid != null) AdminX.requestJobs(selectedUuid);
        if (t == Tab.IPS && selectedUuid != null) AdminX.requestIps(selectedUuid);
        if (t == Tab.LOGS && selectedUuid != null && logs == null) ClientBridge.requestLogs(selectedUuid, logType, logPage);
    }

    private void cycleTab(int dir) {
        Tab[] all = Tab.values();
        int i = tab.ordinal();
        for (int n = 0; n < all.length; n++) {
            i = (i + dir + all.length) % all.length;
            if (tabEnabled(all[i])) {
                switchTab(all[i]);
                return;
            }
        }
    }

    private void applySanction() {
        if (selectedUuid == null) return;
        final String type = sType;
        final boolean timed = type.equals("MUTE") || type.equals("BAN");
        final int minutes = timed ? sMinutes : 0;
        final String why = reason.getValue().trim();
        final String uuid = selectedUuid;
        Runnable run = () -> {
            ClientBridge.sanction(uuid, type, minutes, why);
            reason.setValue("");
            focus(reason, false);
        };
        if (type.equals("BAN") || type.equals("KICK")) {
            String who = profile != null ? profile.name : selectedName;
            String dur = timed ? (minutes == 0 ? "PERMANENT" : Ui.minutesLabel(minutes)) : "";
            confirm(type.equals("BAN") ? "CONFIRMA BAN" : "CONFIRMA KICK",
                who + (dur.isEmpty() ? "" : "  -  " + dur) + (why.isEmpty() ? "" : "\nMotiv: " + why),
                type.equals("BAN") ? "DA, BAN" : "DA, KICK", Sk.BAD, run);
        } else {
            run.run();
        }
    }

    private void confirm(String title, String body, String okLabel, int color, Runnable ok) {
        Modal m = new Modal();
        m.title = title;
        m.body = body;
        m.okLabel = okLabel;
        m.color = color;
        m.ok = ok;
        modal = m;
        focus(reason, false);
        focus(note, false);
    }

    private void addNote() {
        if (selectedUuid == null || note.getValue().trim().isEmpty()) return;
        ClientBridge.noteAdd(selectedUuid, note.getValue().trim());
        note.setValue("");
        focus(note, false);
    }

    /** Focus fara dependenta de numele metodei: TextFieldWidget isi seteaza singur focusul la click. */
    private void focus(TextFieldWidget f, boolean on) {
        if (f == null) return;
        f.mouseClicked(on ? f.x + 1 : -10000, on ? f.y + 1 : -10000, 0);
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSound.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
            case 5: cashOpen = !cashOpen; break;
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

    private void sendCash(String mode) {
        if (profile == null) return;
        double a;
        try {
            a = Double.parseDouble(cashField.getValue().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            onToast("Scrie o suma valida.", true);
            return;
        }
        final double amount = a;
        final String uuid = profile.uuid;
        Runnable run = () -> {
            AdminX.cash(uuid, mode, amount);
            cashField.setValue("");
            ClientBridge.requestProfile(uuid);
        };
        if (mode.equals("take")) {
            confirm("CONFIRMA SCADEREA", "Scazi $" + Ui.money(amount) + " de la " + profile.name, "DA, SCADE", Sk.BAD, run);
        } else {
            run.run();
        }
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

    // ------------------------------------------------------------------ primitive de desen

    private static float easeOut(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - c) * (1f - c) * (1f - c);
    }

    private void tx(GuiGraphics ms, String s, float x, float top, float size, int color) {
        Gfx.text(ms, s, x, top + size * 0.9f, size, color, 0, false, 0f);
    }

    private void txb(GuiGraphics ms, String s, float x, float top, float size, int color) {
        Gfx.text(ms, s, x, top + size * 0.9f, size, color, 0, true, 0f);
    }

    private void txr(GuiGraphics ms, String s, float xRight, float top, float size, int color) {
        Gfx.text(ms, s, xRight, top + size * 0.9f, size, color, 2, false, 0f);
    }

    private void txc(GuiGraphics ms, String s, float cx, float top, float size, int color, boolean bold) {
        Gfx.text(ms, s, cx, top + size * 0.9f, size, color, 1, bold, 0f);
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        if (modal != null && !drawingModal) return;
        hits.add(new Hit(x, y, w, h, r));
    }

    private void card(GuiGraphics ms, float x, float y, float w, float h) {
        Sk.cut(ms, x, y, w, h, 6f, Sk.ROW);
    }

    private int pill(GuiGraphics ms, String label, float x, float y, int color) {
        float w = Gfx.width(label, 6.2f, true) + 10f;
        Sk.slant(ms, x, y, w, 11f, 3f, Theme.alpha(color, 0x40));
        txc(ms, label, x + w / 2f + 1f, y + 2.2f, 6.2f, color, true);
        return Math.round(x + w + 4f);
    }

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean filled, Runnable r) {
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, enabled, filled, hov);
        if (enabled) hitL(x, y, w, h, r);
    }

    /** Buton de actiune: kind 0 neutru, 1 succes, 2 pericol, 3 accent, 4 avertisment. */
    private void abtn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, int kind, Runnable r) {
        int col = kind == 1 ? Sk.GOOD : kind == 2 ? Sk.BAD : kind == 3 ? Sk.ACC : kind == 4 ? Sk.WARN : Sk.MUTED;
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        int bg = !enabled ? 0xFF121C2A : hov ? Theme.alpha(col, 0x70) : Theme.alpha(col, 0x30);
        Sk.slant(ms, x, y, w, h, 4f, bg);
        if (enabled) Gfx.rrect(ms, x + 2, y + h - 2, w - 6, 1.5f, 0, col);
        txc(ms, label, x + w / 2f + 1.5f, y + h / 2f - 3f, 6.6f, !enabled ? Sk.FAINT : hov ? 0xFFFFFFFF : Sk.WHITE, true);
        if (enabled) hitL(x, y, w, h, r);
    }

    private void chip(GuiGraphics ms, String label, float x, float y, float w, float h, boolean on, int color, Runnable r) {
        boolean hov = Sk.in(lmx, lmy, x, y, w, h);
        Sk.slant(ms, x, y, w, h, 4f, on ? color : hov ? Sk.ROW_HOT : Sk.ROW);
        txc(ms, label, x + w / 2f + 1.5f, y + h / 2f - 3f, 6.4f, on ? 0xFF061426 : Sk.WHITE, true);
        hitL(x, y, w, h, r);
    }

    private float chipW(String label) {
        return Gfx.width(label, 6.4f, true) + 14f;
    }

    private void fieldBox(GuiGraphics ms, TextFieldWidget f, float x, float y, float w, float h) {
        Sk.slant(ms, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, f.isFocused() ? Sk.ACC : LINE);
        f.x = Math.round(offX + (x + 7f) * scale);
        f.y = Math.round(offY + (y + (h - 8f) / 2f) * scale);
        f.setWidth(Math.max(10, Math.round((w - 14f) * scale)));
        fieldRects.put(f, new float[] {x, y, w, h});
    }

    /** Fata jucatorului (skin) cu strat de palarie; daca nu e in joc, o silueta. */
    private void face(GuiGraphics ms, String uuid, int x, int y, int size, boolean online) {
        Ui.rect(ms, x - 1, y - 1, size + 2, size + 2, online ? Gfx.fade(Sk.GOOD, 0.85f) : 0xFF2A3547);
        try {
            net.minecraft.client.network.play.ClientPlayNetHandler conn = Minecraft.getInstance().getConnection();
            net.minecraft.client.network.play.NetworkPlayerInfo info = conn == null || uuid == null ? null
                : conn.getPlayerInfo(java.util.UUID.fromString(uuid));
            if (info == null) {
                Ui.rect(ms, x, y, size, size, 0xFF182638);
                int u = size / 5;
                Ui.rect(ms, x + u * 2, y + u, u, u, 0xFF3A4A60);
                Ui.rect(ms, x + u * 2 - 1, y + u * 2, u + 2, u * 2, 0xFF3A4A60);
                return;
            }
            Minecraft.getInstance().getTextureManager().bind(info.getSkinLocation());
            com.mojang.blaze3d.systems.RenderSystem.enableTexture();
            com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
            net.minecraft.client.gui.AbstractGui.blit(ms.pose(), x, y, size, size, 8f, 8f, 8, 8, 64, 64);
            net.minecraft.client.gui.AbstractGui.blit(ms.pose(), x, y, size, size, 40f, 8f, 8, 8, 64, 64);
        } catch (RuntimeException ex) {
            Ui.rect(ms, x, y, size, size, 0xFF182638);
        }
    }

    // ------------------------------------------------------------------ iconite (desenate cu Gfx)

    private void icon(GuiGraphics ms, Tab t, float cx, float cy, int c) {
        switch (t) {
            case DASH:
                Gfx.rrect(ms, cx - 5, cy - 5, 4, 4, 0, c);
                Gfx.rrect(ms, cx + 1, cy - 5, 4, 4, 0, c);
                Gfx.rrect(ms, cx - 5, cy + 1, 4, 4, 0, c);
                Gfx.rrect(ms, cx + 1, cy + 1, 4, 4, 0, c);
                break;
            case PROFILE:
                Gfx.disc(ms, cx, cy - 2.5f, 3f, c);
                Gfx.rrect(ms, cx - 5, cy + 1.5f, 10, 4.5f, 2f, c);
                break;
            case LOGS:
                Gfx.rrect(ms, cx - 5, cy - 4.5f, 10, 1.6f, 0, c);
                Gfx.rrect(ms, cx - 5, cy - 0.8f, 7, 1.6f, 0, c);
                Gfx.rrect(ms, cx - 5, cy + 2.9f, 10, 1.6f, 0, c);
                break;
            case SANCTIONS:
                Gfx.poly(ms, new float[] {cx, cy - 5.5f, cx + 5.5f, cy + 4.5f, cx - 5.5f, cy + 4.5f}, c);
                Gfx.rrect(ms, cx - 0.7f, cy - 2f, 1.4f, 3.6f, 0, 0xFF0B1422);
                Gfx.rrect(ms, cx - 0.7f, cy + 2.2f, 1.4f, 1.4f, 0, 0xFF0B1422);
                break;
            case NOTES:
                Gfx.rrect(ms, cx - 4.5f, cy - 5.5f, 9, 11, 1.5f, c);
                Gfx.rrect(ms, cx - 2.5f, cy - 2.5f, 5, 1.2f, 0, 0xFF0B1422);
                Gfx.rrect(ms, cx - 2.5f, cy + 0.5f, 5, 1.2f, 0, 0xFF0B1422);
                Gfx.rrect(ms, cx - 2.5f, cy + 3f, 3, 1.2f, 0, 0xFF0B1422);
                break;
            case JOBS:
                Gfx.rrect(ms, cx - 5.5f, cy - 2.5f, 11, 8, 1.5f, c);
                Gfx.rrect(ms, cx - 2.5f, cy - 5f, 5, 2, 0.8f, c);
                Gfx.rrect(ms, cx - 5.5f, cy + 0.4f, 11, 1, 0, 0xFF0B1422);
                break;
            case IPS:
                Gfx.ring(ms, cx, cy, 4f, 5.4f, c, c);
                Gfx.rrect(ms, cx - 5.4f, cy - 0.5f, 10.8f, 1f, 0, c);
                Gfx.rrect(ms, cx - 0.5f, cy - 5.4f, 1f, 10.8f, 0, c);
                break;
            default:
                Gfx.rrect(ms, cx - 5.5f, cy - 3.5f, 11, 8, 1.5f, c);
                Gfx.disc(ms, cx, cy + 0.5f, 2.4f, 0xFF0B1422);
                Gfx.disc(ms, cx, cy + 0.5f, 1.2f, c);
                Gfx.rrect(ms, cx - 2.5f, cy - 5.3f, 5, 2f, 0.8f, c);
                break;
        }
    }

    // ------------------------------------------------------------------ desenare

    @Override
    public void render(MatrixStack poseIn, int mx, int my, float pt) {
        GuiGraphics ms = new GuiGraphics(poseIn);
        hits.clear();
        fieldRects.clear();
        tooltip = null;
        float open = easeOut((System.currentTimeMillis() - openedAt) / 300f);
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        lmx = (mx - offX) / scale;
        lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        // campurile de text se deseneaza in coordonate de ecran, dupa restaurarea transformarii
        for (TextFieldWidget f : fields()) f.render(ms.pose(), mx, my, 0);
    }

    private void content(GuiGraphics ms) {
        g = ms;
        // cadru de gheata cu margine luminoasa si colt taiat
        Sk.cut(ms, 8, 8, 504, 274, 12f, Gfx.fade(Sk.ACC, 0.40f));
        Sk.cut(ms, 9, 9, 502, 272, 12f, 0xF20A121D);
        Gfx.scrim(ms, 260, 22, 250, 36, Gfx.fade(0xFF9FD0FF, 0.07f));
        Gfx.rrect(ms, 22, 9, 96, 2, 0, Sk.ACC);

        header();
        rail();
        playerList();
        right();

        // subsol: scurtaturi
        txc(ms, "ESC INCHIDE   /  CAUTA   SUS-JOS  JUCATOR   TAB  SECTIUNE   1-8  SECTIUNI", 260, 274f, 5.8f, Sk.FAINT, false);

        toast(ms);
        drawModal(ms);
        if (tooltip != null && modal == null) {
            float tw = Gfx.width(tooltip, 6.6f, true) + 12f;
            Sk.slant(ms, tipX, tipY, tw, 14f, 4f, 0xFF14202F);
            Gfx.rrect(ms, tipX + 2, tipY + 12, tw - 6, 1.5f, 0, Sk.ACC);
            txc(ms, tooltip, tipX + tw / 2f + 1.5f, tipY + 3.4f, 6.6f, Sk.WHITE, true);
        }
    }

    private GuiGraphics g;

    private void header() {
        GuiGraphics ms = gx();
        txb(ms, "ADMIN", 22, 15, 12.5f, Sk.ACC);
        tx(ms, clip(serverName.toUpperCase() + "  /  STAFF: " + staffName, 6.6f, false, 300f), 22, 29, 6.6f, Sk.MUTED);
        Gfx.rrect(ms, 18, 42, 484, 1, 0, LINE);
        button(ms, "ROATA", 352, 14, 62, 20, true, false, () ->
            Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.wheel.WheelAdminScreen()));
        button(ms, "REFRESH", 418, 14, 62, 20, true, false, () -> {
            ClientBridge.requestList(lastQuery, listPage);
            DashPanel.forceRefresh();
            if (selectedUuid != null) ClientBridge.requestProfile(selectedUuid);
        });
        Sk.close(ms, 484, 14, lmx, lmy);
        hitL(484, 14, 20, 20, this::onClose);
    }

    private GuiGraphics gx() {
        return g;
    }

    // ---- sidebar

    private void rail() {
        GuiGraphics ms = gx();
        Tab[] all = Tab.values();
        for (int i = 0; i < all.length; i++) {
            final Tab t = all[i];
            float x = 16;
            float y = 48 + i * 28f;
            boolean en = tabEnabled(t);
            boolean sel = tab == t;
            boolean hov = en && Sk.in(lmx, lmy, x, y, 34, 26);
            if (sel) {
                Sk.cut(ms, x, y, 34, 26, 5f, Sk.ROW_HOT);
                Gfx.rrect(ms, x, y + 3, 2.5f, 20, 0, Sk.ACC);
                Gfx.scrim(ms, x + 17, y + 13, 18, 14, Gfx.fade(Sk.ACC, 0.20f));
            } else if (hov) {
                Sk.cut(ms, x, y, 34, 26, 5f, Sk.ROW);
            }
            icon(ms, t, x + 17, y + 13, !en ? 0xFF2D3B50 : sel ? Sk.ACC : hov ? Sk.WHITE : Sk.MUTED);
            if (hov) {
                tooltip = t.label;
                tipX = x + 38;
                tipY = y + 6;
            }
            if (en) hitL(x, y, 34, 26, () -> switchTab(t));
        }
    }

    // ---- lista de jucatori

    private void playerList() {
        GuiGraphics ms = gx();
        float x = 58;
        float w = 140;
        fieldBox(ms, search, x, 48, w, 18);
        float rowsTop = 70;
        float rh = 15f;
        if (list == null) {
            tx(ms, "Se incarca...", x + 4, rowsTop + 4, S, Sk.MUTED);
        } else if (list.entries.isEmpty()) {
            tx(ms, "Niciun jucator.", x + 4, rowsTop + 4, S, Sk.MUTED);
        } else {
            float y = rowsTop;
            int shown = 0;
            for (AdminData.PlayerEntry e : list.entries) {
                if (shown++ >= PAGE) break;
                boolean sel = e.uuid.equals(selectedUuid);
                boolean hov = Sk.in(lmx, lmy, x, y, w, rh - 1);
                if (sel) {
                    Sk.slant(ms, x, y, w, rh - 1, 4f, Sk.ROW_HOT);
                    Gfx.rrect(ms, x, y + 1, 2f, rh - 3, 0, Sk.ACC);
                } else if (hov) {
                    Sk.slant(ms, x, y, w, rh - 1, 4f, Sk.ROW);
                }
                face(ms, e.uuid, Math.round(x + 6), Math.round(y + 1.5f), 11, e.online);
                int nameColor = e.banned ? Sk.BAD : e.muted ? Sk.WARN : Sk.WHITE;
                float right = x + w - 5;
                if (e.banned) {
                    txr(ms, "B", right, y + 3.4f, 6.4f, Sk.BAD);
                    right -= 9;
                } else if (e.muted) {
                    txr(ms, "M", right, y + 3.4f, 6.4f, Sk.WARN);
                    right -= 9;
                }
                String idLabel = e.id > 0 ? "#" + e.id : "";
                if (!idLabel.isEmpty()) {
                    txr(ms, idLabel, right, y + 3.4f, 6.4f, sel ? Sk.WHITE : Sk.FAINT);
                    right -= Gfx.width(idLabel, 6.4f, false) + 4;
                }
                tx(ms, clip(e.name, 7f, false, right - (x + 21)), x + 21, y + 3.2f, 7f, nameColor);
                final AdminData.PlayerEntry entry = e;
                hitL(x, y, w, rh - 1, () -> select(entry));
                y += rh;
            }
        }
        float pagerY = 254;
        int pages = list == null ? 1 : Math.max(1, (list.total + PAGE - 1) / PAGE);
        button(ms, "<", x, pagerY, 22, 18, listPage > 0, false, () -> ClientBridge.requestList(lastQuery, listPage - 1));
        button(ms, ">", x + w - 22, pagerY, 22, 18, listPage + 1 < pages, false, () -> ClientBridge.requestList(lastQuery, listPage + 1));
        txc(ms, (listPage + 1) + "/" + pages + "  (" + (list == null ? 0 : list.total) + ")", x + w / 2f, pagerY + 5f, 6.6f, Sk.MUTED, false);
        Gfx.rrect(ms, 202, 48, 1, 224, 0, LINE);
    }

    // ---- zona din dreapta

    private void right() {
        GuiGraphics ms = gx();
        float x = 208;
        float w = 296;
        float fadeIn = easeOut((System.currentTimeMillis() - tabAt) / 220f);
        float prev = Gfx.alpha;
        Gfx.alpha = prev * fadeIn;
        try {
            if (tab == Tab.DASH || selectedUuid == null) {
                DashPanel.draw(ms, Math.round(x), 52, Math.round(w), 220, System.currentTimeMillis());
                return;
            }
            detail(ms, x, 48, w);
        } finally {
            Gfx.alpha = prev;
        }
    }

    private void detail(GuiGraphics ms, float x, float y, float w) {
        AdminData.Profile p = profile;
        // card antet
        card(ms, x, y, w, 34);
        face(ms, selectedUuid, Math.round(x + 7), Math.round(y + 4), 26, p != null && p.online);
        String name = p != null ? p.name : selectedName;
        txb(ms, clip(name, 10.5f, true, w - 50), x + 42, y + 4, 10.5f, Sk.WHITE);
        float cx = x + 42;
        if (p != null) {
            if (p.id > 0) cx = pill(ms, "#" + p.id, cx, y + 20, Sk.ACC);
            cx = pill(ms, p.online ? "ONLINE" : "OFFLINE", cx, y + 20, p.online ? Sk.GOOD : Sk.FAINT);
            if (p.banned) cx = pill(ms, "BAN ACTIV", cx, y + 20, Sk.BAD);
            if (p.muted) cx = pill(ms, "MUTE ACTIV", cx, y + 20, Sk.WARN);
        } else {
            tx(ms, "Se incarca profilul...", cx, y + 21, 6.6f, Sk.MUTED);
        }

        actions(ms, x, y + 40, w);
        float cy = y + 62;
        if (cashOpen && p != null) {
            cashBar(ms, x, cy, w);
            cy += 24;
        }
        float ch = 272 - cy;
        card(ms, x, cy, w, ch);
        if (p == null) {
            tx(ms, "Se incarca profilul...", x + 10, cy + 10, S, Sk.MUTED);
            return;
        }
        float ix = x + 10;
        float iy = cy + 8;
        float iw = w - 20;
        float ih = ch - 16;
        switch (tab) {
            case LOGS: drawLogs(ms, ix, iy, iw, ih); break;
            case SANCTIONS: drawSanctions(ms, ix, iy, iw, ih); break;
            case NOTES: drawNotes(ms, ix, iy, iw, ih); break;
            case JOBS: drawJobs(ms, ix, iy, iw, ih); break;
            case IPS: drawIps(ms, ix, iy, iw, ih); break;
            case SS: drawSs(ms, ix, iy, iw, ih); break;
            default: drawProfile(ms, ix, iy, iw, ih); break;
        }
    }

    // ---- actiuni grupate

    private void actions(GuiGraphics ms, float x, float y, float w) {
        AdminData.Profile p = profile;
        boolean on = p != null && p.online;
        boolean has = p != null;
        boolean frz = has && AdminX.frozen.contains(p.uuid);
        float inner = 3f;
        float groupGap = 8f;
        float bw = (w - 5 * inner - 3 * groupGap) / 9f;
        float h = 18f;
        float cx = x;
        // grup 1: teleport
        abtn(ms, "TP", cx, y, bw, h, on, 3, () -> runAction(0));
        cx += bw + inner;
        abtn(ms, "AICI", cx, y, bw, h, on, 3, () -> runAction(1));
        cx += bw + groupGap;
        // grup 2: vizualizare
        abtn(ms, "INV", cx, y, bw, h, on, 0, () -> runAction(2));
        cx += bw + inner;
        abtn(ms, "SPEC", cx, y, bw, h, on, 0, () -> runAction(3));
        cx += bw + inner;
        abtn(ms, frz ? "DEZG." : "FRZ", cx, y, bw, h, on || frz, 3, () -> runAction(4));
        cx += bw + groupGap;
        // grup 3: economie
        abtn(ms, "CASH", cx, y, bw, h, has, 1, () -> runAction(5));
        cx += bw + groupGap;
        // grup 4: sanctiuni
        abtn(ms, "WARN", cx, y, bw, h, has, 4, () -> runAction(6));
        cx += bw + inner;
        abtn(ms, "KICK", cx, y, bw, h, has, 2, () -> runAction(7));
        cx += bw + inner;
        abtn(ms, "BAN", cx, y, bw, h, has, 2, () -> runAction(8));
    }

    private void cashBar(GuiGraphics ms, float x, float y, float w) {
        card(ms, x, y, w, 20);
        Gfx.rrect(ms, x, y + 3, 2.5f, 14, 0, Sk.GOOD);
        txb(ms, "CASH $", x + 9, y + 6.5f, 7f, Sk.GOOD);
        fieldBox(ms, cashField, x + 52, y + 2, 84, 16);
        float bx = x + 142;
        float bw = (x + w - bx - 6 - 2 * 3) / 3f;
        abtn(ms, "SETEAZA", bx, y + 2, bw, 16, true, 3, () -> sendCash("set"));
        abtn(ms, "+ ADAUGA", bx + bw + 3, y + 2, bw, 16, true, 1, () -> sendCash("add"));
        abtn(ms, "- SCADE", bx + 2 * (bw + 3), y + 2, bw, 16, true, 2, () -> sendCash("take"));
    }

    // ---- PROFIL

    private void drawProfile(GuiGraphics ms, float x, float y, float w, float h) {
        AdminData.Profile p = profile;
        float col = w / 3f;
        String[][] rows = {
            {"STATUS", p.online ? "Online" : "Offline"},
            {"ID JUCATOR", p.id > 0 ? "#" + p.id : "-"},
            {"UUID", p.uuid.length() > 13 ? p.uuid.substring(0, 8) + "..." + p.uuid.substring(p.uuid.length() - 4) : p.uuid},
            {"PRIMA CONECTARE", Ui.time(p.first)},
            {"ULTIMA CONECTARE", p.online ? "acum" : Ui.time(p.last)},
            {"TIMP JUCAT", Ui.duration(p.playSeconds)},
            {"SOLD", p.balance.isEmpty() ? "-" : "$" + p.balance},
            {"IP", p.ip.isEmpty() ? "ascuns" : p.ip}
        };
        for (int i = 0; i < rows.length; i++) {
            float cx = x + (i % 3) * col;
            float ry = y + (i / 3) * 20f;
            tx(ms, rows[i][0], cx, ry, 5.8f, Sk.FAINT);
            tx(ms, clip(rows[i][1], S, false, col - 6), cx, ry + 8f, S, Sk.WHITE);
        }
        // contoare sanctiuni
        float ty = y + 64;
        float tw = (w - 3 * 4f) / 4f;
        counter(ms, "WARN", p.warns, x, ty, tw, Sk.WARN);
        counter(ms, "MUTE", p.mutes, x + (tw + 4), ty, tw, Sk.WARN);
        counter(ms, "KICK", p.kicks, x + 2 * (tw + 4), ty, tw, Sk.ACC);
        counter(ms, "BAN", p.bans, x + 3 * (tw + 4), ty, tw, Sk.BAD);
        // flag-uri
        float fy = ty + 30;
        float idY = y + h - 17;
        tx(ms, "FLAG-URI AUTOMATE (" + p.flags.size() + ")", x, fy, 5.8f, Sk.FAINT);
        fy += 10;
        int maxFlags = Math.max(0, (int) ((idY - 4 - fy) / 10.5f));
        if (p.flags.isEmpty()) tx(ms, "Niciun flag.", x, fy, 6.8f, Sk.MUTED);
        for (int i = 0; i < Math.min(maxFlags, p.flags.size()); i++) {
            AdminData.Flag f = p.flags.get(i);
            int sc = "HIGH".equals(f.severity) ? Sk.BAD : "MEDIUM".equals(f.severity) ? Sk.WARN : Sk.MUTED;
            Gfx.rrect(ms, x, fy + 1.5f, 2.5f, 6, 0, sc);
            tx(ms, clip(f.trigger + " - " + f.details, 6.8f, false, w - 80), x + 7, fy, 6.8f, Sk.WHITE);
            txr(ms, Ui.time(f.ts), x + w, fy, 6.2f, Sk.FAINT);
            fy += 10.5f;
        }
        if (p.flags.size() > maxFlags && maxFlags > 0) txr(ms, "+" + (p.flags.size() - maxFlags) + " ascunse", x + w, idY - 11, 5.8f, Sk.FAINT);
        // schimbare ID (serverul verifica permisiunea flowcity.id.admin)
        float fw = Math.min(110f, w - 96f);
        fieldBox(ms, idField, x, idY, fw, 17);
        abtn(ms, "SETEAZA ID", x + fw + 5, idY, 84, 17, true, 3, this::setId);
    }

    private void counter(GuiGraphics ms, String label, int value, float x, float y, float w, int color) {
        Sk.cut(ms, x, y, w, 24, 4f, 0xB0152133);
        Gfx.rrect(ms, x + 4, y, w - 8, 1.5f, 0, Theme.alpha(color, 0xC0));
        txb(ms, String.valueOf(value), x + 7, y + 4, 10f, value > 0 ? color : Sk.MUTED);
        txr(ms, label, x + w - 6, y + 9, 6f, Sk.FAINT);
    }

    // ---- JURNAL

    private void drawLogs(GuiGraphics ms, float x, float y, float w, float h) {
        float cx = x;
        for (int i = 0; i < LOG_TYPES.length; i++) {
            final String type = LOG_TYPES[i];
            float cw = chipW(LOG_LABELS[i]);
            chip(ms, LOG_LABELS[i], cx, y, cw, 14, type.equals(logType), Sk.ACC, () -> {
                logType = type;
                logPage = 0;
                logs = null;
                ClientBridge.requestLogs(selectedUuid, type, 0);
            });
            cx += cw + 3;
        }
        float ry = y + 20;
        float footer = y + h - 16;
        if (logs == null) {
            tx(ms, "Se incarca...", x, ry, S, Sk.MUTED);
        } else if (logs.rows.isEmpty()) {
            tx(ms, "Nicio inregistrare.", x, ry, S, Sk.MUTED);
        } else {
            float tw = 80;
            for (AdminData.LogRow r : logs.rows) {
                if (ry + 10 > footer) break;
                tx(ms, Ui.time(r.ts), x, ry, 6.2f, Sk.FAINT);
                String rest = (r.b.isEmpty() ? "" : r.b) + (r.c.isEmpty() ? "" : "  " + r.c);
                boolean wide = logType.equals("CHAT") || logType.equals("CMD");
                float aw = wide ? w - tw : 70;
                tx(ms, clip(r.a, 6.8f, false, aw - 4), x + tw, ry, 6.8f, logType.equals("CMD") ? Sk.ACC : Sk.WHITE);
                if (!rest.isEmpty() && aw < w - tw) {
                    tx(ms, clip(rest, 6.8f, false, w - tw - aw), x + tw + aw, ry, 6.8f, Sk.MUTED);
                }
                ry += 10;
            }
        }
        int pages = logs == null ? 1 : Math.max(1, logs.pages);
        button(ms, "<", x, footer, 22, 16, logPage > 0, false, () -> {
            logPage--;
            logs = null;
            ClientBridge.requestLogs(selectedUuid, logType, logPage);
        });
        button(ms, ">", x + 26, footer, 22, 16, logPage + 1 < pages, false, () -> {
            logPage++;
            logs = null;
            ClientBridge.requestLogs(selectedUuid, logType, logPage);
        });
        tx(ms, "Pagina " + (logPage + 1) + "/" + pages + (logs == null ? "" : "  -  " + logs.total + " inregistrari"), x + 56, footer + 4, 6.6f, Sk.MUTED);
    }

    // ---- SANCTIUNI

    private void drawSanctions(GuiGraphics ms, float x, float y, float w, float h) {
        float cx = x;
        for (String t : S_TYPES) {
            float cw = chipW(t);
            int col = t.equals("BAN") ? Sk.BAD : t.equals("KICK") ? Sk.ACC : Sk.WARN;
            chip(ms, t, cx, y, cw, 15, t.equals(sType), col, () -> sType = t);
            cx += cw + 3;
        }
        boolean timed = sType.equals("MUTE") || sType.equals("BAN");
        if (timed) {
            cx += 6;
            for (int i = 0; i < DUR_LABEL.length; i++) {
                final int minutes = DUR_MIN[i];
                float cw = chipW(DUR_LABEL[i]) - 4f;
                chip(ms, DUR_LABEL[i], cx, y, cw, 15, sMinutes == minutes, Sk.ACC, () -> sMinutes = minutes);
                cx += cw + 2;
            }
        }
        float fy = y + 21;
        float applyW = 62;
        float fw = w - applyW - 5;
        fieldBox(ms, reason, x, fy, fw, 17);
        abtn(ms, "APLICA", x + fw + 5, fy, applyW, 17, true, sType.equals("BAN") || sType.equals("KICK") ? 2 : 4, this::applySanction);

        float ly = fy + 25;
        tx(ms, "ISTORIC (" + profile.sanctions.size() + ")", x, ly, 5.8f, Sk.FAINT);
        ly += 10;
        float rowH = 12f;
        int fit = Math.max(1, (int) ((y + h - ly) / rowH));
        int max = Math.max(0, profile.sanctions.size() - fit);
        scroll = Math.max(0, Math.min(scroll, max));
        if (profile.sanctions.isEmpty()) tx(ms, "Fara sanctiuni.", x, ly, 6.8f, Sk.MUTED);
        for (int i = scroll; i < Math.min(profile.sanctions.size(), scroll + fit); i++) {
            final AdminData.Sanction s = profile.sanctions.get(i);
            boolean tm = "MUTE".equals(s.type) || "BAN".equals(s.type);
            int tc = "BAN".equals(s.type) ? Sk.BAD : "KICK".equals(s.type) ? Sk.ACC : Sk.WARN;
            String status = s.deleted ? "REVOCAT" : s.active ? "ACTIV" : tm ? "EXPIRAT" : "";
            int sc = s.deleted ? Sk.FAINT : s.active ? Sk.BAD : Sk.MUTED;
            Sk.slant(ms, x, ly, 30, 10, 3f, Theme.alpha(tc, 0x40));
            txc(ms, s.type, x + 15, ly + 2.2f, 5.8f, tc, true);
            if (tm) tx(ms, Ui.minutesLabel(s.minutes), x + 34, ly + 1.4f, 6.4f, Sk.MUTED);
            float reasonX = x + 74;
            float right = x + w - 56;
            tx(ms, clip(s.reason + "  (" + s.admin + ")", 6.6f, false, right - reasonX - 4), reasonX, ly + 1.4f, 6.6f, s.deleted ? Sk.FAINT : Sk.WHITE);
            txr(ms, status, x + w - (s.active && !s.deleted ? 16 : 2), ly + 1.6f, 6f, sc);
            if (s.active && !s.deleted) {
                abtn(ms, "x", x + w - 12, ly - 1, 12, 12, true, 2, () -> ClientBridge.revoke(s.id));
            }
            ly += rowH;
        }
        if (max > 0) txr(ms, "scroll", x + w, y + h - 7, 5.8f, Sk.FAINT);
    }

    // ---- NOTE

    private void drawNotes(GuiGraphics ms, float x, float y, float w, float h) {
        float addW = 66;
        float fw = w - addW - 5;
        fieldBox(ms, note, x, y, fw, 17);
        abtn(ms, "ADAUGA", x + fw + 5, y, addW, 17, true, 3, this::addNote);
        float ly = y + 25;
        tx(ms, "NOTE STAFF (" + profile.notes.size() + ")", x, ly, 5.8f, Sk.FAINT);
        ly += 10;
        float rowH = 22f;
        int fit = Math.max(1, (int) ((y + h - ly) / rowH));
        int max = Math.max(0, profile.notes.size() - fit);
        scroll = Math.max(0, Math.min(scroll, max));
        if (profile.notes.isEmpty()) tx(ms, "Nicio nota.", x, ly, 6.8f, Sk.MUTED);
        for (int i = scroll; i < Math.min(profile.notes.size(), scroll + fit); i++) {
            final AdminData.Note n = profile.notes.get(i);
            tx(ms, n.admin, x, ly, 6.6f, Sk.ACC);
            txr(ms, Ui.time(n.ts), x + w - 18, ly, 6f, Sk.FAINT);
            tx(ms, clip(n.text, 7f, false, w - 20), x, ly + 9.5f, 7f, Sk.WHITE);
            abtn(ms, "x", x + w - 12, ly - 1, 12, 12, true, 2, () -> ClientBridge.noteDel(n.id));
            ly += rowH;
        }
    }

    // ---- JOBURI

    private void drawJobs(GuiGraphics ms, float x, float y, float w, float h) {
        tx(ms, "JOB", x, y, 5.8f, Sk.FAINT);
        tx(ms, "XP", x + w * 0.45f, y, 5.8f, Sk.FAINT);
        txr(ms, "CASTIGAT TOTAL", x + w, y, 5.8f, Sk.FAINT);
        Gfx.rrect(ms, x, y + 10, w, 1, 0, LINE);
        if (!profile.uuid.equals(AdminX.jobsUuid)) {
            tx(ms, "Se incarca...", x, y + 17, S, Sk.MUTED);
            return;
        }
        if (AdminX.jobs.isEmpty()) {
            tx(ms, "Fara istoric de joburi.", x, y + 17, S, Sk.MUTED);
            return;
        }
        float ry = y + 16;
        for (AdminX.JobRow r : AdminX.jobs) {
            if (ry > y + h - 24) break;
            boolean cur = r.job.equalsIgnoreCase(AdminX.jobsCurrent);
            Sk.slant(ms, x, ry, w, 13, 4f, cur ? Sk.ROW_HOT : Sk.ROW);
            Gfx.rrect(ms, x + 1, ry + 2, 2.5f, 9, 0, cur ? Sk.GOOD : Sk.FAINT);
            tx(ms, clip(r.job, 7f, false, w * 0.40f), x + 9, ry + 3, 7f, cur ? Sk.WHITE : Sk.MUTED);
            tx(ms, r.xp + " XP", x + w * 0.45f, ry + 3, 7f, Sk.ACC);
            txr(ms, "$" + Ui.money(r.earned), x + w - 6, ry + 3, 7f, Sk.GOOD);
            ry += 15;
        }
        if (!AdminX.jobsCurrent.isEmpty()) tx(ms, "Job curent: " + AdminX.jobsCurrent, x, y + h - 9, 6.4f, Sk.FAINT);
    }

    // ---- IP / conturi multiple

    private void drawIps(GuiGraphics ms, float x, float y, float w, float h) {
        tx(ms, "VERIFICARE IP / CONTURI MULTIPLE", x, y, 5.8f, Sk.FAINT);
        Gfx.rrect(ms, x, y + 10, w, 1, 0, LINE);
        if (!profile.uuid.equals(AdminX.ipsUuid)) {
            tx(ms, "Se incarca (necesita flowcity.eadmin.ip)...", x, y + 17, S, Sk.MUTED);
            return;
        }
        float ry = y + 16;
        tx(ms, "Istoric IP-uri", x, ry, 6.6f, Sk.MUTED);
        ry += 11;
        if (AdminX.ipHistory.isEmpty()) tx(ms, "Niciun IP inregistrat.", x, ry, 6.8f, Sk.FAINT);
        int shown = 0;
        for (AdminX.IpRow r : AdminX.ipHistory) {
            if (shown++ >= 5) break;
            tx(ms, r.ip, x + 4, ry, 7f, Sk.WHITE);
            txr(ms, Ui.time(r.ts), x + w, ry, 6.2f, Sk.FAINT);
            ry += 10;
        }
        ry += 6;
        boolean multi = !AdminX.ipOthers.isEmpty();
        tx(ms, multi ? "CONTURI PE ACELEASI IP-URI (" + AdminX.ipOthers.size() + ")" : "Niciun alt cont pe aceste IP-uri.", x, ry, 6.8f, multi ? Sk.WARN : Sk.GOOD);
        ry += 11;
        for (AdminX.Other o : AdminX.ipOthers) {
            if (ry > y + h - 10) break;
            Gfx.rrect(ms, x, ry + 1, 2.5f, 7, 0, Sk.WARN);
            tx(ms, clip(o.name, 7f, false, w / 2f), x + 7, ry, 7f, Sk.WHITE);
            txr(ms, o.ip, x + w, ry, 6.4f, Sk.FAINT);
            ry += 10;
        }
    }

    // ---- SS

    private void drawSs(GuiGraphics ms, float x, float y, float w, float h) {
        abtn(ms, "SOLICITA SS", x, y, 84, 16, profile.online, 3, () -> AdminX.requestSs(profile.uuid));
        abtn(ms, "STERGE", x + 88, y, 54, 16, AdminX.ssTex != null, 2, AdminX::clearSs);
        tx(ms, clip(AdminX.ssStatus, 6.6f, false, w - 150), x + 148, y + 4, 6.6f, Sk.MUTED);
        float fy = y + 22;
        float fh = h - 22;
        Sk.cut(ms, x, fy, w, fh, 5f, 0xFF0A111B);
        if (AdminX.ssTex == null || AdminX.ssW <= 0) {
            txc(ms, "Nicio imagine disponibila", x + w / 2f, fy + fh / 2f - 10, 7.4f, Sk.MUTED, false);
            txc(ms, "Apasa SOLICITA SS (jucatorul trebuie sa aiba modul).", x + w / 2f, fy + fh / 2f + 2, 6.2f, Sk.FAINT, false);
            return;
        }
        float k = Math.min((w - 4) / (float) AdminX.ssW, (fh - 4) / (float) AdminX.ssH);
        int dw = Math.round(AdminX.ssW * k);
        int dh = Math.round(AdminX.ssH * k);
        int dx = Math.round(x + (w - dw) / 2f);
        int dy = Math.round(fy + (fh - dh) / 2f);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
        ms.blit(AdminX.ssTex, dx, dy, dw, dh, 0f, 0f, AdminX.ssW, AdminX.ssH, AdminX.ssW, AdminX.ssH);
    }

    // ---- toast + dialog

    private void toast(GuiGraphics ms) {
        long now = System.currentTimeMillis();
        if (now > toastUntil || toastText.isEmpty()) return;
        float in = easeOut((now - toastAt) / 220f);
        float out = Math.min(1f, (toastUntil - now) / 300f);
        float tw = Math.min(380f, Gfx.width(toastText, 7f, true) + 34f);
        float x = (W - tw) / 2f;
        float y = 282f - 30f * in;
        int col = toastError ? Sk.BAD : Sk.GOOD;
        float prev = Gfx.alpha;
        Gfx.alpha = prev * out;
        try {
            Sk.cut(ms, x - 1, y - 1, tw + 2, 22, 6f, Theme.alpha(col, 0xA0));
            Sk.cut(ms, x, y, tw, 20, 6f, 0xFF0B1422);
            Gfx.rrect(ms, x + 8, y + 7, 6, 6, 0, col);
            tx(ms, clip(toastText, 7f, true, tw - 34), x + 20, y + 5.4f, 7f, Sk.WHITE);
        } finally {
            Gfx.alpha = prev;
        }
    }

    private void drawModal(GuiGraphics ms) {
        if (modal == null) return;
        drawingModal = true;
        try {
            Gfx.rrect(ms, 9, 9, 502, 272, 0, 0xC4040810);
            float w = 280;
            float h = 112;
            float x = (W - w) / 2f;
            float y = (H - h) / 2f;
            Sk.cut(ms, x - 1, y - 1, w + 2, h + 2, 10f, Theme.alpha(modal.color, 0xB0));
            Sk.cut(ms, x, y, w, h, 10f, 0xFF0B1422);
            Gfx.scrim(ms, x + w / 2f, y + 8, w * 0.4f, 22, Gfx.fade(modal.color, 0.16f));
            txb(ms, modal.title, x + 16, y + 13, 10f, modal.color);
            Gfx.rrect(ms, x + 16, y + 29, w - 32, 1, 0, LINE);
            float ty = y + 38;
            for (String line : modal.body.split("\n")) {
                tx(ms, clip(line, 7.4f, false, w - 32), x + 16, ty, 7.4f, Sk.WHITE);
                ty += 11;
            }
            button(ms, "ANULEAZA", x + 16, y + h - 30, 112, 20, true, false, () -> modal = null);
            button(ms, modal.okLabel, x + w - 128, y + h - 30, 112, 20, true, true, () -> {
                Runnable r = modal.ok;
                modal = null;
                if (r != null) r.run();
            });
        } finally {
            drawingModal = false;
        }
    }

    // ------------------------------------------------------------------ input

    private List<TextFieldWidget> fields() {
        List<TextFieldWidget> f = new ArrayList<>();
        if (modal != null) return f;
        f.add(search);
        if (selectedUuid != null && tab != Tab.DASH) {
            if (tab == Tab.SANCTIONS) f.add(reason);
            if (tab == Tab.NOTES) f.add(note);
            if (tab == Tab.PROFILE && profile != null) f.add(idField);
            if (cashOpen && profile != null) f.add(cashField);
        }
        return f;
    }

    private boolean anyFocused() {
        for (TextFieldWidget f : fields()) if (f.isFocused()) return true;
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            float lx = (float) ((mx - offX) / scale);
            float ly = (float) ((my - offY) / scale);
            if (modal == null) {
                boolean onField = false;
                for (TextFieldWidget f : fields()) {
                    float[] r = fieldRects.get(f);
                    boolean inside = r != null && Sk.in(lx, ly, r[0], r[1], r[2], r[3]);
                    if (inside) {
                        f.mouseClicked(mx, my, button);
                        focus(f, true);
                        onField = true;
                    } else {
                        focus(f, false);
                    }
                }
                if (onField) return true;
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Sk.in(lx, ly, h.x, h.y, h.w, h.h)) {
                    click();
                    h.action.run();
                    return true;
                }
            }
            if (modal != null) return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (modal != null) return true;
        if (tab == Tab.SANCTIONS || tab == Tab.NOTES) {
            scroll -= (int) Math.signum(delta);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (modal != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                modal = null;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                Runnable r = modal.ok;
                modal = null;
                if (r != null) r.run();
            }
            return true;
        }
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
        // scurtaturi (nimic nu e in curs de tastare)
        if (key == GLFW.GLFW_KEY_SLASH) {
            swallowChar = true;
            focus(search, true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            cycleTab((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            return true;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_8) {
            switchTab(Tab.values()[key - GLFW.GLFW_KEY_1]);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && list != null && !list.entries.isEmpty()) {
            int idx = -1;
            for (int i = 0; i < list.entries.size(); i++) if (list.entries.get(i).uuid.equals(selectedUuid)) idx = i;
            int n = list.entries.size();
            int next = key == GLFW.GLFW_KEY_DOWN ? (idx + 1) % n : (idx <= 0 ? n - 1 : idx - 1);
            select(list.entries.get(next));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        if (modal != null) return true;
        for (TextFieldWidget f : fields()) {
            if (f.isFocused() && f.charTyped(c, mods)) return true;
        }
        return super.charTyped(c, mods);
    }
}
