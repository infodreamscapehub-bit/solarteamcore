package ro.solarstudios.flowcity.client.fadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * FAdmin 2.0: panoul de administrare. Bara laterala (Dashboard, Jucatori, Vehicule, Economie, Joburi, Loguri, Sistem, Editor,
 * Permisiuni) cu doar sectiunile permise nivelului de staff; fiecare actiune se re-verifica pe server si ramane in jurnal.
 * Design: FTheme/FUi (paleta violet/albastru), desenat integral din cod.
 */
public final class FAdminScreen extends FlowScreen {
    private static final int NAVW = 112;
    private static final int S_DASH = 0;
    private static final int S_PLAYERS = 1;
    private static final int S_MOD = 2;
    private static final int S_STAFF = 3;
    private static final int S_REP = 4;
    private static final int S_VEH = 5;
    private static final int S_ECON = 6;
    private static final int S_JOBS = 7;
    private static final int S_PERM = 8;
    private static final int S_LOGS = 9;
    private static final int S_SYS = 10;
    private static final int S_EDIT = 11;
    private static final int S_SEARCH = 12;
    private static final int S_RP = 13;
    private static final String[] NAME = {"Dashboard", "Jucători", "Moderare", "Staff", "Rapoarte", "Vehicule", "Economie", "Joburi", "Permisiuni", "Loguri", "Server", "Editor", "Căutare globală", "Sisteme RP"};
    private static final String[] ICON = {"grid", "eye", "flag", "star-line", "bell", "route", "star", "layers", "gear", "clock", "compass", "pencil", "search", "pin"};
    private static final String[] NEED = {"dash", "players", "mod_view", "staff_view", "report_view", "veh_view", "econ_view", "job_view", "perm_view", "logs", "server_view", "cfg_basic", "dash", "dash"};
    /** Grupurile din bara laterala, in ordinea din spec; fiecare grup afiseaza doar modulele permise. */
    private static final String[] GROUP = {"GENERAL", "PLAYER MANAGEMENT", "MODERATION", "STAFF", "ECONOMY", "ROLEPLAY SYSTEMS", "SERVER", "REPORTS"};
    private static final int[][] GROUP_SEC = {{S_DASH, S_SEARCH}, {S_PLAYERS, S_VEH}, {S_MOD}, {S_STAFF, S_PERM}, {S_ECON}, {S_JOBS, S_RP}, {S_SYS, S_LOGS, S_EDIT}, {S_REP}};
    /** Accentul FAdmin: portocaliu cu galben (spec). Se aplica si pe componentele comune prin FTheme.accent. */
    private static final int ORANGE = 0xFFFF8A1F;
    private static final int ORANGE_DIM = 0xFFC66A12;
    private static final int YELLOW = 0xFFFFC53D;
    private static final String[] MOD_ID = {"bans", "mutes", "warns", "jail", "history"};
    private static final String[] MOD_L = {"Ban-uri", "Mute-uri", "Avertismente", "Închisoare", "Istoric"};
    private static final String[] REP_ID = {"OPEN", "ACCEPTED", "RESOLVED", "REJECTED", "ALL"};
    private static final String[] REP_L = {"Deschise", "Preluate", "Rezolvate", "Respinse", "Toate"};
    private static final int[] DUR = {30, 60, 1440, 10080, 0};
    private static final String[] DUR_L = {"30 min", "1 oră", "1 zi", "7 zile", "Permanent"};
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM HH:mm");

    // confirmari si tooltip-uri
    private Runnable confirmRun;
    private String confirmTitle = "";
    private String confirmText = "";
    private String confirmOk = "Confirmă";
    private boolean confirmDanger;
    private String tip;

    private int sec = S_DASH;
    private long lastPoll;
    private long refreshAt;
    private boolean asked;

    private final EditBox search = field("Caută (nume, ID)...", 24, null);
    private final EditBox reason = field("Motiv (obligatoriu)", 100, null);
    private final EditBox amount = field("Sumă / nivel", 10, null);
    private final EditBox value = field("Valoare nouă", 80, null);
    private final EditBox pname = field("Nume jucător", 16, null);

    // jucatori
    private String selUuid;
    private int pTab;
    private int durIdx = 2;
    // vehicule
    private long vehSel = -1;
    // economie
    private String econUuid;
    private int econMode;
    private int econAcc;
    private int econOp;
    // joburi
    private String jobSel = "";
    private String jobPlayer;
    private int jobTarget;
    private int jobScroll;
    // editor
    private String cat = "";
    private String cfgSel = "";
    private int cfgScroll;
    // permisiuni
    private int permLevel = 1;
    private int permTab;
    private String lpPick;
    private String lpGroup = "";
    private int lpScroll;
    // moderare
    private int modTab;
    private String modSel;
    // staff
    private String staffSel = "";
    // rapoarte
    private int repTab;
    private long repSel = -1;
    // profil
    private boolean swapId;
    private int annMode;

    // geometrie curenta (se recalculeaza la fiecare cadru)
    private int x0;
    private int y0;
    private int cw;
    private int bh;

    public FAdminScreen() {
        super("FAdmin", 590, 420);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected void init() {
        FAdminClient.ask("me");
        asked = false;
    }

    @Override
    protected List<EditBox> fields() {
        List<EditBox> f = new ArrayList<>();
        if (confirmRun != null) return f;
        switch (sec) {
            case S_SEARCH:
                f.add(search);
                break;
            case S_PLAYERS:
                if (selUuid == null) {
                    f.add(search);
                } else {
                    f.add(reason);
                    if (pTab == 2 && has("rpid")) f.add(amount);
                }
                break;
            case S_MOD:
                if (modSel != null) f.add(reason);
                break;
            case S_REP:
                f.add(search);
                if (repSel >= 0 && has("report_handle")) f.add(reason);
                break;
            case S_VEH:
                f.add(search);
                if (vehSel >= 0) f.add(reason);
                break;
            case S_ECON:
                if (econMode == 0 && econUuid == null) f.add(search);
                if (econMode == 0 && econUuid != null && has("econ_modify")) {
                    f.add(amount);
                    f.add(reason);
                }
                break;
            case S_JOBS:
                if (jobPlayer != null) {
                    f.add(amount);
                    f.add(reason);
                }
                break;
            case S_LOGS:
                f.add(search);
                break;
            case S_EDIT:
                if (!cfgSel.isEmpty()) {
                    if (!"bool".equals(selType())) f.add(value);
                    f.add(reason);
                }
                break;
            case S_PERM:
                if (permTab == 0 && has("perm_edit")) {
                    f.add(pname);
                    f.add(reason);
                } else if (permTab == 2) {
                    if (lpPick == null) f.add(search);
                    else if (has("rank")) f.add(reason);
                }
                break;
            case S_SYS:
                if (has("announce")) f.add(value);
                break;
            default:
                break;
        }
        return f;
    }

    @Override
    protected void onEnter(EditBox field) {
        if (field == search) submitSearch();
    }

    // ------------------------------------------------------------------ date si utilitare

    private static JsonObject me() {
        return FAdminClient.me;
    }

    private static String s(JsonObject o, String k) {
        return o == null ? "" : JsonClientModule.str(o, k, "");
    }

    private static double n(JsonObject o, String k) {
        return o == null ? 0 : JsonClientModule.num(o, k, 0);
    }

    private static boolean b(JsonObject o, String k) {
        return o != null && JsonClientModule.bool(o, k, false);
    }

    private static List<JsonObject> arr(JsonObject o, String k) {
        List<JsonObject> out = new ArrayList<>();
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray(k)) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private boolean allowed() {
        return b(me(), "allowed");
    }

    private boolean has(String cap) {
        if (me() == null || !me().has("caps") || !me().get("caps").isJsonArray()) return false;
        for (JsonElement e : me().getAsJsonArray("caps")) if (e.isJsonPrimitive() && cap.equals(e.getAsString())) return true;
        return false;
    }

    private static String fm(double v) {
        return String.format(Locale.US, "%,.0f", v) + " lei";
    }

    private static String ago(long ts) {
        if (ts <= 0) return "—";
        long d = Math.max(0, System.currentTimeMillis() - ts) / 1000;
        if (d < 60) return "acum " + d + "s";
        if (d < 3600) return "acum " + d / 60 + " min";
        if (d < 86400) return "acum " + d / 3600 + " h";
        return "acum " + d / 86400 + " z";
    }

    private static String when(long ts) {
        return ts <= 0 ? "—" : DATE.format(new Date(ts));
    }

    private static String clip(String str, float max, float size, boolean xl) {
        if (str == null) return "";
        if (Gfx.width(str, size, xl) <= max) return str;
        String r = str;
        while (r.length() > 1 && Gfx.width(r + "…", size, xl) > max) r = r.substring(0, r.length() - 1);
        return r.trim() + "…";
    }

    /** Text cu linia de baza in y. */
    private static void t(GuiGraphics ms, String str, float x, float base, float size, int color) {
        Gfx.text(ms, str, x, base, size, color, 0, false, 0f);
    }

    private static void tr(GuiGraphics ms, String str, float x, float base, float size, int color) {
        Gfx.text(ms, str, x, base, size, color, 2, false, 0f);
    }

    private static void tx(GuiGraphics ms, String str, float x, float base, float size, int color) {
        Gfx.text(ms, str, x, base, size, color, 0, true, 0f);
    }

    /** Text centrat vertical intr-un rand de inaltime h care incepe la y. */
    private static float mid(float y, float h, float size) {
        return y + h / 2f + size * 0.36f;
    }

    private static boolean in(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int pill(GuiGraphics ms, String label, float x, float y, int color) {
        float w = Gfx.width(label, 6.6f, true) + 10f;
        Gfx.rrect(ms, x, y, w, 12f, 6f, FTheme.alpha(color, 46));
        Gfx.text(ms, label, x + 5f, y + 8.6f, 6.6f, color, 0, true, 0f);
        return (int) w;
    }

    private void card(GuiGraphics ms, float x, float y, float w, float h) {
        FTheme.panel(ms, x, y, w, h, 10f, FTheme.BG2, FTheme.LINE);
    }

    private boolean btn(GuiGraphics ms, String id, String label, String ic, float x, float y, float w, float h, int style, boolean enabled, int mx, int my, Runnable action) {
        FUi.button(ms, id, label, ic, x, y, w, h, style, enabled, mx, my);
        if (enabled) hit((int) x, (int) y, (int) w, (int) h, action);
        return in(mx, my, x, y, w, h);
    }

    private boolean chip(GuiGraphics ms, String id, String label, float x, float y, float h, boolean sel, int mx, int my, Runnable action) {
        float w = FUi.chipWidth(label, h, false);
        boolean hov = FUi.chip(ms, id, label, x, y, w, h, sel, 0, mx, my);
        hit((int) x, (int) y, (int) w, (int) h, action);
        return hov;
    }

    private int chipW(String label, float h) {
        return (int) FUi.chipWidth(label, h, false) + 6;
    }

    private void pager(GuiGraphics ms, float right, float y, int page, int total, int per, int mx, int my, IntConsumer go) {
        int pages = Math.max(1, (total + per - 1) / per);
        String lab = (page + 1) + " / " + pages;
        float lw = Gfx.width(lab, 7.4f, true);
        float x = right - 16 - lw - 8 - 16;
        FUi.iconButton(ms, "pg-l" + sec, "chev-left", x, y, 16, false, mx, my);
        if (page > 0) hit((int) x, (int) y, 16, 16, () -> go.accept(page - 1));
        Gfx.text(ms, lab, x + 16 + 4 + lw / 2f, y + 11.4f, 7.4f, FTheme.MUTED, 1, true, 0f);
        float x2 = x + 16 + 8 + lw;
        FUi.iconButton(ms, "pg-r" + sec, "chev-right", x2, y, 16, false, mx, my);
        if (page + 1 < pages) hit((int) x2, (int) y, 16, 16, () -> go.accept(page + 1));
    }

    // ------------------------------------------------------------------ navigare

    private void go(int target) {
        sec = target;
        search.setValue("");
        reason.setValue("");
        amount.setValue("");
        value.setValue("");
        selUuid = null;
        vehSel = -1;
        cfgSel = "";
        modSel = null;
        repSel = -1;
        lpPick = null;
        staffSel = "";
        pTab = 0;
        for (EditBox f : new EditBox[] {search, reason, amount, value, pname}) focus(f, false);
        enter();
    }

    private void enter() {
        lastPoll = System.currentTimeMillis();
        switch (sec) {
            case S_DASH:
                FAdminClient.ask("dash");
                break;
            case S_PLAYERS:
                FAdminClient.ask("players", "q", "", "page", 0);
                break;
            case S_MOD:
                FAdminClient.ask("mod", "tab", MOD_ID[modTab], "page", 0);
                break;
            case S_STAFF:
                FAdminClient.ask("staff");
                break;
            case S_REP:
                FAdminClient.ask("reports", "status", REP_ID[repTab], "page", 0, "q", "");
                break;
            case S_VEH:
                FAdminClient.ask("vehicles", "q", "", "page", 0);
                break;
            case S_ECON:
                if (econMode == 1) FAdminClient.ask("susp");
                else if (econUuid == null) FAdminClient.ask("players", "q", "", "page", 0);
                else FAdminClient.ask("econ", "uuid", econUuid);
                break;
            case S_JOBS:
                FAdminClient.ask("jobs");
                if (!jobSel.isEmpty()) FAdminClient.ask("job_players", "id", jobSel);
                if (jobPlayer != null) FAdminClient.ask("job_progress", "uuid", jobPlayer);
                break;
            case S_LOGS:
                FAdminClient.ask("logs", "q", "", "page", 0);
                break;
            case S_SYS:
                FAdminClient.ask("system");
                break;
            case S_RP:
                FAdminClient.ask("rp");
                break;
            case S_SEARCH:
                break;
            case S_EDIT:
                FAdminClient.ask("cfg_cats");
                if (!cat.isEmpty()) FAdminClient.ask("cfg_list", "cat", cat);
                break;
            case S_PERM:
                FAdminClient.ask("perm_list");
                if (permTab == 1) FAdminClient.ask("lp_groups", "group", lpGroup);
                if (permTab == 2 && lpPick == null) FAdminClient.ask("players", "q", "", "page", 0);
                break;
            default:
                break;
        }
    }

    private void submitSearch() {
        String q = search.getValue().trim();
        switch (sec) {
            case S_PLAYERS:
            case S_ECON:
                FAdminClient.ask("players", "q", q, "page", 0);
                break;
            case S_REP:
                FAdminClient.ask("reports", "status", REP_ID[repTab], "page", 0, "q", q);
                break;
            case S_PERM:
                FAdminClient.ask("players", "q", q, "page", 0);
                break;
            case S_VEH:
                FAdminClient.ask("vehicles", "q", q, "page", 0);
                break;
            case S_LOGS:
                FAdminClient.ask("logs", "q", q, "page", 0);
                break;
            case S_SEARCH:
                FAdminClient.searchRes = null;
                searchAsked = q.length() >= 2;
                if (searchAsked) FAdminClient.ask("search", "q", q);
                break;
            default:
                break;
        }
    }

    private boolean searchAsked;

    // ------------------------------------------------------------------ cadru

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        FUi.frame(0.016f);
        FTheme.accent = ORANGE;
        long now = System.currentTimeMillis();
        if (!asked && allowed()) {
            asked = true;
            enter();
        }
        if (now > 0 && refreshAt > 0 && now >= refreshAt) {
            refreshAt = 0;
            if (selUuid != null) FAdminClient.ask("player", "uuid", selUuid);
            FAdminClient.ask("players", "q", search.getValue().trim(), "page", (int) n(FAdminClient.players, "page"));
        }
        if (sec == S_DASH && now - lastPoll > 6000) {
            lastPoll = now;
            FAdminClient.ask("dash");
        }
        if (sec == S_SYS && now - lastPoll > 8000) {
            lastPoll = now;
            FAdminClient.ask("system");
        }
        if (sec == S_STAFF && now - lastPoll > 8000) {
            lastPoll = now;
            FAdminClient.ask("staff");
        }
        if (sec == S_REP && now - lastPoll > 10000) {
            lastPoll = now;
            FAdminClient.ask("reports", "status", REP_ID[repTab], "page", (int) n(FAdminClient.reports, "page"), "q", search.getValue().trim());
        }
        if (sec == S_RP && now - lastPoll > 8000) {
            lastPoll = now;
            FAdminClient.ask("rp");
        }
        tip = null;
        Gfx.rrect(ms, 0, 0, width, height, 0, FTheme.SCRIM);
        FTheme.shadow(ms, px, py, pw, ph, 14f);
        FTheme.panel(ms, px, py, pw, ph, 14f, FTheme.BG, FTheme.LINE);
        sidebar(ms, mx, my);
        x0 = px + NAVW + 14;
        y0 = py + 14;
        cw = pw - NAVW - 28;
        bh = ph - 62;
        if (!allowed()) {
            FUi.empty(ms, "close", asked ? "Fără acces" : "Se încarcă...", asked ? "Nu ai un nivel de staff FAdmin." : "", x0 + cw / 2f, py + ph / 2f, 260f);
            return;
        }
        String sub = "Nivel: " + s(me(), "label");
        if (sec == S_DASH && FAdminClient.dash != null && !s(FAdminClient.dash, "version").isEmpty()) {
            sub = "v" + s(FAdminClient.dash, "version") + " · uptime " + uptime((long) n(FAdminClient.dash, "uptime")) + " · " + sub;
        }
        tx(ms, NAME[sec], x0, y0 + 14, 13f, FTheme.TEXT);
        tr(ms, sub, x0 + cw, y0 + 12, 7.4f, FTheme.MUTED);
        FTheme.divider(ms, x0, y0 + 22, cw);
        int by = y0 + 32;
        switch (sec) {
            case S_DASH: drawDash(ms, by, mx, my); break;
            case S_PLAYERS: drawPlayers(ms, by, mx, my); break;
            case S_MOD: drawMod(ms, by, mx, my); break;
            case S_STAFF: drawStaff(ms, by, mx, my); break;
            case S_REP: drawReports(ms, by, mx, my); break;
            case S_VEH: drawVehicles(ms, by, mx, my); break;
            case S_ECON: drawEcon(ms, by, mx, my); break;
            case S_JOBS: drawJobs(ms, by, mx, my); break;
            case S_LOGS: drawLogs(ms, by, mx, my); break;
            case S_SYS: drawSystem(ms, by, mx, my); break;
            case S_EDIT: drawEditor(ms, by, mx, my); break;
            case S_SEARCH: drawGlobalSearch(ms, by, mx, my); break;
            case S_RP: drawRp(ms, by, mx, my); break;
            default: drawPerm(ms, by, mx, my); break;
        }
        drawTip(ms, mx, my);
        if (confirmRun != null) drawConfirm(ms, mx, my);
    }

    // ------------------------------------------------------------------ confirmari, tooltip

    private static String uptime(long sec) {
        long d = sec / 86400;
        long h = sec % 86400 / 3600;
        long m = sec % 3600 / 60;
        return d > 0 ? d + "z " + h + "h" : h > 0 ? h + "h " + m + "m" : m + " min";
    }

    /** Cere confirmare. Cand {@code twice} e true (actiuni periculoase) se cere o a doua confirmare, separata. */
    private void confirm(String title, String text, String ok, boolean danger, boolean twice, Runnable run) {
        confirmTitle = title;
        confirmText = text;
        confirmOk = ok;
        confirmDanger = danger;
        confirmRun = twice
            ? () -> confirm("Ești sigur?", "Acțiune periculoasă: " + title.toLowerCase(Locale.ROOT) + ". Rămâne în jurnalul de audit.", "Da, execută", true, false, run)
            : run;
        confirmArmed = false;
    }

    private boolean confirmArmed;

    private void drawConfirm(GuiGraphics ms, int mx, int my) {
        FUi.modal(ms, "fa-cf", confirmTitle, confirmText, confirmOk, "Anulează", confirmDanger, 0, 0, width, height, 1f, mx, my);
        float[] ok = FUi.modalPrimary();
        float[] no = FUi.modalSecondary();
        hit(0, 0, width, height, () -> {
        });
        hit((int) no[0], (int) no[1], (int) no[2], (int) no[3], () -> confirmRun = null);
        hit((int) ok[0], (int) ok[1], (int) ok[2], (int) ok[3], () -> {
            Runnable r = confirmRun;
            confirmRun = null;
            if (r != null) r.run();
        });
    }

    private void tipIf(boolean hover, String text) {
        if (hover && confirmRun == null) tip = text;
    }

    private void drawTip(GuiGraphics ms, int mx, int my) {
        if (tip == null) return;
        float w = Gfx.width(tip, 7f, false) + 14;
        float x = Math.min(mx + 10, width - w - 4);
        float y = my + 16 + 14 > height ? my - 22 : my + 16;
        Gfx.rrect(ms, x, y, w, 15, 3f, 0xF0101010);
        Gfx.rrect(ms, x, y, 2, 15, 0, YELLOW);
        t(ms, tip, x + 8, y + 10.2f, 7f, FTheme.TEXT);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (confirmRun != null) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) confirmRun = null;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void sidebar(GuiGraphics ms, int mx, int my) {
        Gfx.rrect(ms, px + 1, py + 1, NAVW, ph - 2, 13f, FTheme.BG2);
        Gfx.rrect(ms, px + NAVW, py + 8, 1, ph - 16, 0, FTheme.LINE);
        FTheme.caption(ms, "FLOWCITY", px + 16, py + 24, FTheme.MUTED);
        tx(ms, "FAdmin", px + 16, py + 42, 15f, FTheme.TEXT);
        int cnt = 0;
        int groups = 0;
        for (int[] g : GROUP_SEC) {
            boolean any = false;
            for (int i : g) if (has(NEED[i])) {
                cnt++;
                any = true;
            }
            if (any) groups++;
        }
        int avail = ph - 52 - 66;
        int cap = groups * 11;
        int step = Math.max(15, Math.min(24, (avail - cap) / Math.max(1, cnt)));
        int ih = step - 2;
        int y = py + 52;
        for (int g = 0; g < GROUP_SEC.length; g++) {
            boolean any = false;
            for (int i : GROUP_SEC[g]) if (has(NEED[i])) any = true;
            if (!any) continue;
            Gfx.text(ms, GROUP[g], px + 14, y + 7, 5.6f, FTheme.FAINT, 0, true, 0.4f);
            y += 11;
            for (int i : GROUP_SEC[g]) {
                if (!has(NEED[i])) continue;
                boolean sel = sec == i;
                boolean hot = in(mx, my, px + 8, y, NAVW - 16, ih);
                float h = FUi.hot("fa-nav" + i, hot);
                if (sel) {
                    Gfx.rrect(ms, px + 8, y, NAVW - 16, ih, 4f, FTheme.alpha(ORANGE, 46));
                    Gfx.rrect(ms, px + 8, y + (ih - 10) / 2, 2, 10, 1f, ORANGE);
                } else if (h > 0.01f) {
                    Gfx.rrect(ms, px + 8, y, NAVW - 16, ih, 4f, FTheme.alpha(0xFFFFFFFF, (int) (14 * h)));
                }
                FUi.icon(ms, ICON[i], px + 22, y + ih / 2, 11, sel ? ORANGE : FTheme.mix(FTheme.MUTED, FTheme.TEXT, h));
                Gfx.text(ms, NAME[i], px + 34, mid(y, ih, 7.2f), 7.2f, sel ? FTheme.TEXT : FTheme.mix(FTheme.MUTED, FTheme.TEXT, h), 0, true, 0f);
                final int target = i;
                hit(px + 8, y, NAVW - 16, ih, () -> go(target));
                y += step;
            }
        }
        // jos: nivel + inchide
        if (allowed()) {
            Gfx.rrect(ms, px + 10, py + ph - 62, NAVW - 20, 24, 8f, FTheme.PANEL);
            tx(ms, clip(s(me(), "name"), NAVW - 36, 7.2f, true), px + 18, py + ph - 54, 7.2f, FTheme.TEXT);
            Gfx.text(ms, s(me(), "label"), px + 18, py + ph - 45, 6.4f, ORANGE, 0, true, 0f);
        }
        btn(ms, "fa-close", "Închide", "close", px + 10, py + ph - 32, NAVW - 20, 22, FUi.GHOST, true, mx, my, this::onClose);
    }

    // ------------------------------------------------------------------ dashboard

    private void statCard(GuiGraphics ms, float x, float y, float w, String cap, String val, String sub, int color) {
        card(ms, x, y, w, 52f);
        FTheme.caption(ms, cap, x + 10, y + 14, FTheme.MUTED);
        tx(ms, val, x + 10, y + 34, 15f, color);
        t(ms, clip(sub, w - 16, 6.8f, false), x + 10, y + 46, 6.8f, FTheme.FAINT);
    }

    /** Mini-grafic cu mostrele reale primite de la server (online / TPS); nu se deseneaza pana nu sunt cel putin 2 mostre. */
    private void spark(GuiGraphics ms, float x, float y, float w, float h, int idx, int color) {
        List<float[]> hs = FAdminClient.hist;
        if (hs.size() < 2) return;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        for (float[] v : hs) {
            lo = Math.min(lo, v[idx]);
            hi = Math.max(hi, v[idx]);
        }
        if (idx == 1) {
            lo = Math.min(lo, 15f);
            hi = Math.max(hi, 20f);
        }
        float span = Math.max(1f, hi - lo);
        for (int i = 1; i < hs.size(); i++) {
            float x1 = x + (i - 1) * w / 29f;
            float x2 = x + i * w / 29f;
            float y1 = y + h - (hs.get(i - 1)[idx] - lo) / span * h;
            float y2 = y + h - (hs.get(i)[idx] - lo) / span * h;
            Gfx.line(ms, x1, y1, x2, y2, 1f, color);
        }
    }

    private void drawDash(GuiGraphics ms, int y, int mx, int my) {
        JsonObject d = FAdminClient.dash;
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        float gap = 8f;
        float w = (cw - gap * 3) / 4f;
        double tps = n(d, "tps");
        statCard(ms, x0, y, w, "JUCĂTORI ONLINE", (int) n(d, "online") + " / " + (int) n(d, "max"), (int) n(d, "active24") + " activi în 24h", FTheme.OK);
        statCard(ms, x0 + (w + gap), y, w, "ÎNREGISTRAȚI", String.valueOf((int) n(d, "total")), "+" + (int) n(d, "newToday") + " noi azi", YELLOW);
        statCard(ms, x0 + (w + gap) * 2, y, w, "STAFF ONLINE", String.valueOf((int) n(d, "staff")), (int) n(d, "staffDuty") + " în tură", ORANGE);
        statCard(ms, x0 + (w + gap) * 3, y, w, "SERVER", tps < 0 ? "—" : String.format(Locale.US, "%.1f TPS", tps),
            (int) n(d, "ramUsed") + " / " + (int) n(d, "ramMax") + " MB RAM", tps >= 18 || tps < 0 ? FTheme.OK : FTheme.WARN);
        spark(ms, x0 + w - 54, y + 8, 44, 14, 0, FTheme.OK);
        spark(ms, x0 + (w + gap) * 3 + w - 54, y + 8, 44, 14, 1, tps >= 18 || tps < 0 ? FTheme.OK : FTheme.WARN);
        int r2 = y + 60;
        statCard(ms, x0, r2, w, "ÎN ÎNCHISOARE", String.valueOf((int) n(d, "jailed")), "jucători închiși acum", n(d, "jailed") > 0 ? FTheme.WARN : FTheme.MUTED);
        statCard(ms, x0 + (w + gap), r2, w, "RAPOARTE ACTIVE", String.valueOf((int) n(d, "reports")), "deschise sau preluate", n(d, "reports") > 0 ? FTheme.WARN : FTheme.OK);
        statCard(ms, x0 + (w + gap) * 2, r2, w, "POLIȚIE ACTIVĂ", String.valueOf((int) n(d, "police")), "ofițeri online", FTheme.SKY);
        statCard(ms, x0 + (w + gap) * 3, r2, w, "EMS ACTIV", String.valueOf((int) n(d, "ems")), "medici online", FTheme.SKY);
        int r3 = y + 120;
        boolean econ = d.has("cashTotal");
        statCard(ms, x0, r3, w, "VEHICULE", String.valueOf((int) n(d, "vehicles")), "în evidența MDT", ORANGE);
        statCard(ms, x0 + (w + gap), r3, w, "SANCȚIUNI ACTIVE", (int) n(d, "bans") + " / " + (int) n(d, "mutes"), "ban-uri / mute-uri", FTheme.DANGER);
        statCard(ms, x0 + (w + gap) * 2, r3, w, "CASH ÎN CIRCULAȚIE", econ ? fm(n(d, "cashTotal")) : "—", econ ? (int) n(d, "econAccounts") + " conturi active" : "fără acces la economie", YELLOW);
        statCard(ms, x0 + (w + gap) * 3, r3, w, "BANI ÎN BANCĂ", econ ? fm(n(d, "bankTotal")) : "—", econ ? "conturi active recent" : "fără acces la economie", YELLOW);
        int ly = y + 180;
        float half = (cw - gap) / 2f;
        float lh = Math.max(80f, Math.min(118f, y + bh - ly - 4));
        card(ms, x0, ly, half, lh);
        card(ms, x0 + half + gap, ly, half, lh);
        FTheme.caption(ms, "CONECTAȚI RECENT", x0 + 10, ly + 14, FTheme.MUTED);
        FTheme.caption(ms, "ACȚIUNI ADMINISTRATIVE RECENTE", x0 + half + gap + 10, ly + 14, FTheme.MUTED);
        int rowsMax = (int) ((lh - 24) / 15);
        int i = 0;
        for (JsonObject r : arr(d, "recent")) {
            if (i >= rowsMax) break;
            float ry = ly + 22 + i * 15;
            Gfx.disc(ms, x0 + 14, ry + 6, 2.6f, b(r, "online") ? FTheme.OK : FTheme.FAINT);
            t(ms, clip(s(r, "name"), half - 100, 7.6f, false), x0 + 24, mid(ry, 12, 7.6f), 7.6f, FTheme.TEXT);
            tr(ms, b(r, "online") ? "online" : ago((long) n(r, "ts")), x0 + half - 10, mid(ry, 12, 7f), 7f, b(r, "online") ? FTheme.OK : FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nimic încă.", x0 + 14, ly + 40, 7.4f, FTheme.FAINT);
        i = 0;
        for (JsonObject r : arr(d, "actions")) {
            if (i >= rowsMax) break;
            float ry = ly + 22 + i * 15;
            String line = s(r, "admin") + " · " + s(r, "action") + (s(r, "target").isEmpty() ? "" : " → " + s(r, "target"));
            t(ms, clip(line, half - 84, 7.4f, false), x0 + half + gap + 10, mid(ry, 12, 7.4f), 7.4f, FTheme.TEXT);
            tr(ms, ago((long) n(r, "ts")), x0 + cw - 10, mid(ry, 12, 7f), 7f, FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nicio acțiune înregistrată.", x0 + half + gap + 14, ly + 40, 7.4f, FTheme.FAINT);
        int ny = (int) (ly + lh + 8);
        float nh = y + bh - ny;
        if (nh >= 40) {
            card(ms, x0, ny, cw, nh);
            FTheme.caption(ms, "NOTIFICĂRI ADMINISTRATIVE (24H)", x0 + 10, ny + 14, FTheme.MUTED);
            i = 0;
            for (JsonObject r : arr(d, "notices")) {
                float ry = ny + 22 + i * 14;
                if (ry + 12 > ny + nh) break;
                int c = "HIGH".equals(s(r, "sev")) || "CRITICAL".equals(s(r, "sev")) ? FTheme.DANGER : FTheme.WARN;
                Gfx.disc(ms, x0 + 14, ry + 6, 2.6f, c);
                t(ms, clip(s(r, "text"), cw - 90, 7.4f, false), x0 + 24, mid(ry, 12, 7.4f), 7.4f, FTheme.TEXT);
                tr(ms, ago((long) n(r, "ts")), x0 + cw - 10, mid(ry, 12, 7f), 7f, FTheme.FAINT);
                i++;
            }
            if (i == 0) t(ms, "Nicio alertă în ultimele 24 de ore.", x0 + 14, ny + 36, 7.4f, FTheme.FAINT);
        }
    }

    // ------------------------------------------------------------------ cautare globala, sisteme RP

    private void drawGlobalSearch(GuiGraphics ms, int y, int mx, int my) {
        boolean can = search.getValue().trim().length() >= 2;
        drawSearch(ms, y, cw - 70, mx, my);
        boolean hv = btn(ms, "fa-gs", "Caută", "search", x0 + cw - 64, y, 64, 20, FUi.PRIMARY, can, mx, my, this::submitSearch);
        if (!can) tipIf(hv, "Scrie cel puțin 2 caractere");
        t(ms, "Nume, ID RP, UUID, număr de înmatriculare, ID vehicul, ID tranzacție", x0 + 4, y + 34, 6.8f, FTheme.FAINT);
        int ry = y + 44;
        JsonObject d = FAdminClient.searchRes;
        if (!searchAsked) {
            FUi.empty(ms, "search", "Caută oriunde", "Scrie și apasă Enter. Vezi doar ce permite nivelul tău.", x0 + cw / 2f, ry + 70, 260f);
            return;
        }
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, ry + 70, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) {
            FUi.empty(ms, "search", "Niciun rezultat", "Verifică scrierea sau încearcă alt criteriu.", x0 + cw / 2f, ry + 70, 260f);
            return;
        }
        for (JsonObject r : rows) {
            if (ry + 24 > y + bh) break;
            String kind = s(r, "kind");
            boolean clickable = !kind.equals("tx");
            boolean hov = clickable && in(mx, my, x0, ry, cw, 22);
            float h = FUi.hot("fa-sr" + ry, hov);
            Gfx.rrect(ms, x0, ry, cw, 22, 5f, FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
            int c = kind.equals("player") ? FTheme.SKY : kind.equals("vehicle") ? ORANGE : YELLOW;
            String lab = kind.equals("player") ? "JUCĂTOR" : kind.equals("vehicle") ? "VEHICUL" : "TRANZACȚIE";
            int pw2 = pill(ms, lab, x0 + 8, ry + 5, c);
            t(ms, clip(s(r, "title"), cw * 0.4f, 7.8f, true), x0 + 16 + pw2, mid(ry, 22, 7.8f), 7.8f, FTheme.TEXT);
            tr(ms, clip(s(r, "sub"), cw * 0.45f, 7f, false), x0 + cw - 10, mid(ry, 22, 7f), 7f, FTheme.MUTED);
            if (clickable) {
                final JsonObject fr = r;
                hit(x0, ry, cw, 22, () -> openResult(fr));
            }
            ry += 24;
        }
    }

    private void openResult(JsonObject r) {
        if ("player".equals(s(r, "kind"))) {
            String u = s(r, "uuid");
            go(S_PLAYERS);
            selUuid = u;
            pTab = 0;
            FAdminClient.player = null;
            FAdminClient.ask("player", "uuid", u);
        } else if ("vehicle".equals(s(r, "kind"))) {
            long id = (long) n(r, "id");
            String plate = s(r, "plate");
            go(S_VEH);
            search.setValue(plate);
            vehSel = id;
            FAdminClient.vehHist = null;
            FAdminClient.ask("vehicles", "q", plate, "page", 0);
            FAdminClient.ask("veh_hist", "plate", plate);
        }
    }

    private void drawRp(GuiGraphics ms, int y, int mx, int my) {
        JsonObject d = FAdminClient.rpData;
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        float gap = 8f;
        float w = (cw - gap) / 2f;
        int i = 0;
        for (JsonObject r : rows) {
            float x = x0 + (i % 2) * (w + gap);
            float yy = y + (i / 2) * 66;
            card(ms, x, yy, w, 58f);
            FTheme.caption(ms, s(r, "name").toUpperCase(Locale.ROOT), x + 10, yy + 14, FTheme.MUTED);
            int v = (int) n(r, "value");
            tx(ms, String.valueOf(v), x + 10, yy + 36, 16f, v > 0 ? FTheme.TEXT : FTheme.FAINT);
            t(ms, clip(s(r, "note"), w - 20, 6.8f, false), x + 10, yy + 50, 6.8f, FTheme.FAINT);
            float dy = yy + 18;
            if (r.has("detail") && r.get("detail").isJsonArray()) {
                int k = 0;
                for (JsonElement e : r.getAsJsonArray("detail")) {
                    if (k >= 4) break;
                    tr(ms, clip(e.getAsString(), w * 0.5f, 6.8f, false), x + w - 10, dy + k * 9, 6.8f, FTheme.MUTED);
                    k++;
                }
            }
            i++;
        }
        float ny = y + ((i + 1) / 2) * 66 + 2;
        t(ms, "Doar date reale din modulele pornite. Pentru Poliție și EMS se numără jucătorii online cu permisiunea de serviciu.", x0 + 2, ny + 8, 6.8f, FTheme.FAINT);
    }

    // ------------------------------------------------------------------ liste de jucatori

    private void drawPlayerRows(GuiGraphics ms, int y, int mx, int my, java.util.function.Consumer<JsonObject> pick) {
        JsonObject d = FAdminClient.players;
        float[] col = {0, 0.2f, 0.28f, 0.41f, 0.54f, 0.67f, 0.8f, 0.9f};
        String[] head = {"NUME", "ID RP", "RANG", "JOB", "CASH", "BANCĂ", "JUCAT", "STARE"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), y + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) {
            FUi.empty(ms, "search", "Niciun jucător", "Încearcă alt nume sau alt ID.", x0 + cw / 2f, y + 90, 240f);
            return;
        }
        int ry = y + 14;
        float w = cw - 20;
        for (JsonObject r : rows) {
            boolean hov = in(mx, my, x0, ry, cw, 18);
            float h = FUi.hot("fa-pr" + s(r, "uuid"), hov);
            Gfx.rrect(ms, x0, ry, cw, 18, 6f, FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
            float rx = x0 + 10;
            boolean on = b(r, "online");
            Gfx.disc(ms, rx - 4, ry + 9, 2.4f, on ? FTheme.OK : FTheme.FAINT);
            t(ms, clip(s(r, "name"), col[1] * w - 10, 7.6f, false), rx + 2, mid(ry, 18, 7.6f), 7.6f, FTheme.TEXT);
            t(ms, "#" + (int) n(r, "id"), rx + col[1] * w, mid(ry, 18, 7.2f), 7.2f, FTheme.MUTED);
            t(ms, clip(s(r, "rank"), (col[3] - col[2]) * w - 4, 7f, false), rx + col[2] * w, mid(ry, 18, 7f), 7f, YELLOW);
            t(ms, clip(s(r, "job").isEmpty() ? "—" : s(r, "job"), (col[4] - col[3]) * w - 4, 7f, false), rx + col[3] * w, mid(ry, 18, 7f), 7f, FTheme.MUTED);
            t(ms, clip(fm(n(r, "cash")), (col[5] - col[4]) * w - 3, 6.8f, false), rx + col[4] * w, mid(ry, 18, 6.8f), 6.8f, FTheme.TEXT);
            t(ms, clip(fm(n(r, "bank")), (col[6] - col[5]) * w - 3, 6.8f, false), rx + col[5] * w, mid(ry, 18, 6.8f), 6.8f, FTheme.TEXT);
            t(ms, (int) (n(r, "play") / 3600) + " h", rx + col[6] * w, mid(ry, 18, 7f), 7f, FTheme.MUTED);
            if (b(r, "banned")) pill(ms, "BAN", rx + col[7] * w - 2, ry + 3, FTheme.DANGER);
            else if (b(r, "jailed")) pill(ms, "ÎNCHIS", rx + col[7] * w - 2, ry + 3, FTheme.WARN);
            else if (b(r, "muted")) pill(ms, "MUTE", rx + col[7] * w - 2, ry + 3, FTheme.WARN);
            else if (on) pill(ms, "ONLINE", rx + col[7] * w - 2, ry + 3, FTheme.OK);
            else pill(ms, "OFFLINE", rx + col[7] * w - 2, ry + 3, FTheme.FAINT);
            final JsonObject fr = r;
            hit(x0, ry, cw, 18, () -> pick.accept(fr));
            ry += 20;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 10, mx, my,
            pg -> FAdminClient.ask("players", "q", s(FAdminClient.players, "q"), "page", pg));
    }

    private void drawSearch(GuiGraphics ms, int y, int w, int mx, int my) {
        FUi.icon(ms, "search", x0 + 12, y + 10, 12, FTheme.MUTED);
        drawField(ms, search, x0 + 22, y, w - 22, 20, mx, my);
    }

    // ------------------------------------------------------------------ jucatori

    private void drawPlayers(GuiGraphics ms, int y, int mx, int my) {
        if (selUuid == null) {
            drawSearch(ms, y, cw, mx, my);
            drawPlayerRows(ms, y + 28, mx, my, r -> {
                selUuid = s(r, "uuid");
                pTab = 0;
                reason.setValue("");
                FAdminClient.player = null;
                FAdminClient.ask("player", "uuid", selUuid);
            });
            return;
        }
        JsonObject p = FAdminClient.player;
        btn(ms, "fa-pback", "Înapoi la listă", "chev-left", x0, y - 4, 112, 20, FUi.GHOST, true, mx, my, () -> {
            selUuid = null;
            FAdminClient.player = null;
        });
        if (p == null || !selUuid.equals(s(p, "uuid"))) {
            FUi.loading(ms, x0 + cw / 2f, y + 100, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        int top = y + 22;
        float lw = 196f;
        card(ms, x0, top, lw, y + bh - top);
        boolean on = b(p, "online");
        Gfx.disc(ms, x0 + 28, top + 28, 16f, ORANGE_DIM);
        String nm = s(p, "name");
        Gfx.text(ms, nm.isEmpty() ? "?" : nm.substring(0, 1).toUpperCase(Locale.ROOT), x0 + 28, top + 34, 15f, FTheme.TEXT, 1, true, 0f);
        tx(ms, clip(nm, lw - 70, 11f, true), x0 + 52, top + 24, 11f, FTheme.TEXT);
        t(ms, "#" + (int) n(p, "id"), x0 + 52, top + 36, 7.4f, FTheme.MUTED);
        float pxp = x0 + 52 + Gfx.width("#" + (int) n(p, "id"), 7.4f, false) + 8;
        pxp += pill(ms, on ? "ONLINE" : "OFFLINE", pxp, top + 28, on ? FTheme.OK : FTheme.FAINT) + 4;
        if (b(p, "frozen")) pxp += pill(ms, "ÎNGHEȚAT", pxp, top + 28, FTheme.SKY) + 4;
        if (b(p, "jailed")) pill(ms, "ÎNCHIS", pxp, top + 28, FTheme.WARN);
        String[][] kv = {
            {"Rang LuckPerms", s(p, "rank").isEmpty() ? "—" : s(p, "rank")},
            {"Job", s(p, "job").isEmpty() ? "—" : s(p, "job")},
            {"Cash", fm(n(p, "cash"))},
            {"Bancă", fm(n(p, "bank"))},
            {"Vehicule", String.valueOf(arr(p, "cars").size())},
            {"Poziție", on ? s(p, "pos") : "—"},
            {"Ultima intrare", on ? "acum" : ago((long) n(p, "last"))},
            {"Timp jucat", (int) (n(p, "playSeconds") / 3600) + " h"},
            {"Rapoarte primite", String.valueOf((int) n(p, "reports"))}};
        int ky = top + 52;
        for (String[] e : kv) {
            t(ms, e[0], x0 + 12, mid(ky, 13, 7.2f), 7.2f, FTheme.FAINT);
            tr(ms, clip(e[1], lw - 80, 7.4f, false), x0 + lw - 12, mid(ky, 13, 7.4f), 7.4f, FTheme.TEXT);
            ky += 14;
        }
        FTheme.divider(ms, x0 + 12, ky + 2, lw - 24);
        FTheme.caption(ms, "VEHICULE", x0 + 12, ky + 16, FTheme.MUTED);
        int vi = 0;
        for (JsonObject v : arr(p, "cars")) {
            if (vi >= 3) break;
            t(ms, clip(s(v, "model") + " · " + s(v, "plate"), lw - 64, 7.2f, false), x0 + 12, mid(ky + 20 + vi * 13, 13, 7.2f), 7.2f, FTheme.TEXT);
            tr(ms, statusLabel(s(v, "status")), x0 + lw - 12, mid(ky + 20 + vi * 13, 13, 6.6f), 6.6f, statusColor(s(v, "status")));
            vi++;
        }
        if (vi == 0) t(ms, "Niciun vehicul.", x0 + 12, ky + 32, 7.2f, FTheme.FAINT);

        int rx = x0 + (int) lw + 10;
        int rw = cw - (int) lw - 10;
        int cx = rx;
        chip(ms, "fa-ptab0", "Acțiuni", cx, top, 18, pTab == 0, mx, my, () -> pTab = 0);
        cx += chipW("Acțiuni", 18);
        chip(ms, "fa-ptab1", "Istoric", cx, top, 18, pTab == 1, mx, my, () -> pTab = 1);
        cx += chipW("Istoric", 18);
        if (has("rank") || has("rpid")) {
            chip(ms, "fa-ptab2", "Grup și ID", cx, top, 18, pTab == 2, mx, my, () -> {
                pTab = 2;
                amount.setValue("");
                FAdminClient.lpUser = null;
                if (has("rank")) {
                    FAdminClient.ask("lp_user", "uuid", selUuid);
                    FAdminClient.ask("lp_groups");
                }
            });
        }
        if (pTab == 2) {
            drawProfileRank(ms, p, rx, top + 26, rw, y + bh - top - 26, mx, my);
            return;
        }
        if (pTab == 1) {
            int hy = top + 26;
            int i = 0;
            for (JsonObject h : arr(p, "history")) {
                if (i >= 13) break;
                float ry = hy + i * 17;
                int c = "BAN".equals(s(h, "kind")) ? FTheme.DANGER : "MUTE".equals(s(h, "kind")) || "KICK".equals(s(h, "kind")) ? FTheme.WARN : FTheme.SKY;
                Gfx.disc(ms, rx + 4, ry + 7, 2.6f, c);
                t(ms, clip(s(h, "text"), rw - 96, 7.2f, false), rx + 12, mid(ry, 14, 7.2f), 7.2f, FTheme.TEXT);
                tr(ms, when((long) n(h, "ts")), rx + rw, mid(ry, 14, 6.8f), 6.8f, FTheme.FAINT);
                i++;
            }
            if (i == 0) t(ms, "Nicio înregistrare.", rx + 4, hy + 12, 7.4f, FTheme.FAINT);
            return;
        }
        // actiuni
        float bw = (rw - 8) / 2f;
        String[][] acts = {
            {"kick", "Kick", "close", "kick"}, {"mute", "Mute", "bell", "mute"}, {"unmute", "Unmute", "check", "mute"},
            {"warn", "Warn", "flag", "warn"}, {"ban", "Ban", "trash", "ban"}, {"unban", "Unban", "check", "unban"},
            {"freeze", "Freeze / Unfreeze", "target", "freeze"}, {"spectate", "Spectate", "eye", "spectate"},
            {"jail", "Închisoare", "flag", "jail"}, {"unjail", "Eliberează", "check", "jail"},
            {"tp", "Teleport la el", "target", "tp"}, {"bring", "Adu la mine", "pin", "bring"},
            {"heal", "Heal", "plus", "heal"}, {"inv", "Inventar", "layers", "inv"}};
        int i = 0;
        int ay = top + 26;
        for (String[] a : acts) {
            if (!has(a[3])) continue;
            float bx = rx + (i % 2) * (bw + 8);
            float by = ay + (i / 2) * 23;
            final String action = a[0];
            boolean danger = action.equals("kick") || action.equals("ban") || action.equals("jail");
            boolean en = !needsReason(action) || reasonOk();
            boolean hv = btn(ms, "fa-act" + action, a[1], a[2], bx, by, bw, 20, danger ? FUi.DANGER : FUi.SOFT, en, mx, my, () -> doAct(action));
            if (!en) tipIf(hv, "Completează motivul (minim 3 caractere)");
            else if (action.equals("ban")) tipIf(hv, "Cere confirmare; ban-ul permanent cere două");
            i++;
        }
        int rows = (i + 1) / 2;
        int fy = ay + rows * 23 + 3;
        float half = (rw - 8) / 2f;
        float fx = rx;
        if (has("job_view")) {
            btn(ms, "fa-pjob", "Job și grad", "flag", fx, fy, half, 20, FUi.GHOST, true, mx, my, () -> {
                String u = selUuid;
                go(S_JOBS);
                jobPlayer = u;
                FAdminClient.jobProgress = null;
                FAdminClient.ask("job_progress", "uuid", u);
            });
            fx += half + 8;
        }
        if (has("econ_view")) {
            btn(ms, "fa-pecon", "Economie", "star", fx, fy, half, 20, FUi.GHOST, true, mx, my, () -> {
                String u = selUuid;
                go(S_ECON);
                econUuid = u;
                econMode = 0;
                FAdminClient.econ = null;
                FAdminClient.ask("econ", "uuid", u);
            });
        }
        fy += 24;
        btn(ms, "fa-pclassic", "Fișă completă (note, IP, loguri)", "eye", rx, fy, rw, 20, FUi.GHOST, true, mx, my, () -> FAdminClient.ask("classic", "uuid", selUuid));
        fy += 26;
        t(ms, "Durată ban / mute / închisoare", rx, fy + 6, 6.8f, FTheme.FAINT);
        int dx = rx;
        for (int k = 0; k < DUR_L.length; k++) {
            final int kk = k;
            chip(ms, "fa-dur" + k, DUR_L[k], dx, fy + 10, 16, durIdx == k, mx, my, () -> durIdx = kk);
            dx += chipW(DUR_L[k], 16);
        }
        drawField(ms, reason, rx, fy + 32, rw, 20, mx, my);
    }

    /** Fila de grup si ID din profil: schimba ID-ul RP si grupurile LuckPerms ale jucatorului. */
    private void drawProfileRank(GuiGraphics ms, JsonObject p, int rx, int top, int rw, int h, int mx, int my) {
        int ty = top;
        if (has("rpid")) {
            FTheme.caption(ms, "ID RP (acum #" + (int) n(p, "id") + ")", rx, ty + 8, FTheme.MUTED);
            drawField(ms, amount, rx, ty + 14, 96, 20, mx, my);
            chip(ms, "fa-swap", "Schimb dacă e ocupat", rx + 104, ty + 16, 16, swapId, mx, my, () -> swapId = !swapId);
            btn(ms, "fa-setid", "Setează ID", "check", rx + rw - 84, ty + 14, 84, 20, FUi.PRIMARY, true, mx, my, () -> {
                int id = (int) parse(amount.getValue());
                FAdminClient.ask("act", "uuid", selUuid, "action", "setid", "id", id, "swap", swapId, "reason", reason.getValue().trim());
                refreshAt = System.currentTimeMillis() + 900;
            });
            ty += 44;
        }
        if (has("rank")) {
            drawLpUser(ms, selUuid, rx, ty, rw, h - (ty - top) - 26, mx, my, false);
            drawField(ms, reason, rx, top + h - 20, rw, 20, mx, my);
        } else {
            t(ms, "Nu ai nivelul pentru a schimba grupurile.", rx, ty + 10, 7.2f, FTheme.FAINT);
        }
    }

    /** Grupurile LuckPerms ale unui jucator (click pe un grup al lui = scoate, click pe unul liber = adauga) si, optional, permisiunile directe. */
    private void drawLpUser(GuiGraphics ms, String uuid, float x, float y, float w, float h, int mx, int my, boolean perms) {
        JsonObject u = FAdminClient.lpUser;
        JsonObject g = FAdminClient.lpGroups;
        if (u == null || !uuid.equals(s(u, "uuid")) || g == null) {
            FUi.loading(ms, x + w / 2f, y + 50, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        if (g.has("available") && !b(g, "available")) {
            t(ms, "LuckPerms nu este instalat pe server.", x, y + 14, 7.4f, FTheme.WARN);
            return;
        }
        List<String> owned = new ArrayList<>();
        if (u.has("groups") && u.get("groups").isJsonArray()) for (JsonElement e : u.getAsJsonArray("groups")) owned.add(e.getAsString());
        boolean edit = has("rank");
        FTheme.caption(ms, "GRUPURILE LUI" + (edit ? " (click = scoate)" : ""), x, y + 8, FTheme.MUTED);
        float cx = x;
        float cy = y + 14;
        for (String gr : owned) {
            String lab = (edit ? "− " : "") + gr;
            int cwid = chipW(lab, 16);
            if (cx + cwid > x + w) {
                cx = x;
                cy += 18;
            }
            final String fg = gr;
            chip(ms, "fa-gr-" + gr, lab, cx, cy, 16, true, mx, my, () -> {
                if (edit) lpAct("grp_remove", fg);
            });
            cx += cwid;
        }
        if (owned.isEmpty()) t(ms, "Niciun grup.", x + 2, cy + 11, 7.2f, FTheme.FAINT);
        cy += 22;
        if (edit) {
            FTheme.caption(ms, "ADAUGĂ GRUP (click)", x, cy + 8, FTheme.MUTED);
            cx = x;
            cy += 14;
            if (g.has("groups") && g.get("groups").isJsonArray()) {
                for (JsonElement e : g.getAsJsonArray("groups")) {
                    String gr = e.getAsString();
                    if (owned.contains(gr)) continue;
                    String lab = "+ " + gr;
                    int cwid = chipW(lab, 16);
                    if (cx + cwid > x + w) {
                        cx = x;
                        cy += 18;
                    }
                    if (cy + 16 > y + h) break;
                    final String fg = gr;
                    chip(ms, "fa-ga-" + gr, lab, cx, cy, 16, false, mx, my, () -> lpAct("grp_add", fg));
                    cx += cwid;
                }
            }
            cy += 22;
        }
        if (perms) {
            FTheme.caption(ms, "PERMISIUNI DIRECTE (" + (int) n(u, "permTotal") + ")", x, cy + 8, FTheme.MUTED);
            int k = 0;
            for (JsonObject r : arr(u, "perms")) {
                float ry = cy + 14 + k * 12;
                if (ry + 11 > y + h) break;
                t(ms, clip(s(r, "key"), w - 40, 6.8f, false), x, mid(ry, 11, 6.8f), 6.8f, FTheme.TEXT);
                tr(ms, b(r, "value") ? "da" : "nu", x + w, mid(ry, 11, 6.8f), 6.8f, b(r, "value") ? FTheme.OK : FTheme.DANGER);
                k++;
            }
            if (k == 0) t(ms, "Fără permisiuni directe.", x + 2, cy + 26, 7.2f, FTheme.FAINT);
        }
    }

    private void lpAct(String action, String group) {
        String u = permLikeUuid();
        if (u == null) return;
        FAdminClient.ask("act", "uuid", u, "action", action, "group", group, "reason", reason.getValue().trim());
    }

    private String permLikeUuid() {
        return sec == S_PERM ? lpPick : selUuid;
    }

    private static boolean needsReason(String action) {
        return action.equals("kick") || action.equals("mute") || action.equals("ban") || action.equals("warn") || action.equals("jail");
    }

    private boolean reasonOk() {
        return reason.getValue().trim().length() >= 3;
    }

    private void doAct(String action) {
        if (selUuid == null) return;
        if (needsReason(action) && !reasonOk()) {
            toast("Motivul este obligatoriu (minim 3 caractere).", true);
            return;
        }
        final String uid = selUuid;
        final String why = reason.getValue().trim();
        final int mins = DUR[durIdx];
        Runnable run = () -> {
            FAdminClient.ask("act", "uuid", uid, "action", action, "reason", why, "minutes", mins);
            refreshAt = System.currentTimeMillis() + 900;
        };
        String who = FAdminClient.player != null ? s(FAdminClient.player, "name") : "jucător";
        String dur = durIdx == 4 ? "permanent" : DUR_L[durIdx];
        String info = clip("Motiv: " + why, 220f, 7f, false);
        switch (action) {
            case "ban":
                confirm("Ban " + who, "Durată: " + dur + ". " + info, "Banează", true, durIdx == 4, run);
                break;
            case "kick":
                confirm("Kick " + who, info, "Dă kick", true, false, run);
                break;
            case "jail":
                confirm("Închisoare " + who, "Durată: " + dur + ". " + info, "Închide", true, false, run);
                break;
            case "mute":
                confirm("Mute " + who, "Durată: " + dur + ". " + info, "Mute", false, false, run);
                break;
            case "warn":
                confirm("Avertisment " + who, info, "Trimite", false, false, run);
                break;
            default:
                run.run();
                break;
        }
    }

    // ------------------------------------------------------------------ vehicule

    private static String statusLabel(String st) {
        if ("Stolen".equalsIgnoreCase(st)) return "Furat";
        if ("Confiscated".equalsIgnoreCase(st)) return "Confiscat";
        return "Liber";
    }

    private static int statusColor(String st) {
        if ("Stolen".equalsIgnoreCase(st)) return FTheme.WARN;
        if ("Confiscated".equalsIgnoreCase(st)) return FTheme.DANGER;
        return FTheme.OK;
    }

    private void drawVehicles(GuiGraphics ms, int y, int mx, int my) {
        drawSearch(ms, y, cw, mx, my);
        JsonObject d = FAdminClient.vehicles;
        int hy = y + 28;
        float[] col = {0, 0.1f, 0.38f, 0.58f, 0.86f};
        String[] head = {"ID", "MODEL", "NUMĂR", "PROPRIETAR", "STARE"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), hy + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) FUi.empty(ms, "route", "Niciun vehicul", "Nu există vehicule pentru căutarea asta.", x0 + cw / 2f, hy + 80, 240f);
        int ry = hy + 14;
        for (JsonObject r : rows) {
            long id = (long) n(r, "id");
            boolean sel = vehSel == id;
            boolean hov = in(mx, my, x0, ry, cw, 18);
            float h = FUi.hot("fa-vr" + id, hov || sel);
            Gfx.rrect(ms, x0, ry, cw, 18, 6f, sel ? FTheme.alpha(ORANGE, 50) : FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
            float w = cw - 20;
            float rx = x0 + 10;
            t(ms, "#" + id, rx, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            t(ms, clip(s(r, "model"), (col[2] - col[1]) * w - 6, 7.8f, false), rx + col[1] * w, mid(ry, 18, 7.8f), 7.8f, FTheme.TEXT);
            t(ms, s(r, "plate"), rx + col[2] * w, mid(ry, 18, 7.6f), 7.6f, FTheme.TEXT);
            t(ms, clip(s(r, "owner"), (col[4] - col[3]) * w - 6, 7.4f, false), rx + col[3] * w, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            pill(ms, statusLabel(s(r, "status")), rx + col[4] * w - 2, ry + 3, statusColor(s(r, "status")));
            final String plate = s(r, "plate");
            hit(x0, ry, cw, 18, () -> {
                vehSel = vehSel == id ? -1 : id;
                reason.setValue("");
                FAdminClient.vehHist = null;
                if (vehSel >= 0) FAdminClient.ask("veh_hist", "plate", plate);
            });
            ry += 20;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 9, mx, my,
            pg -> FAdminClient.ask("vehicles", "q", s(FAdminClient.vehicles, "q"), "page", pg));
        if (vehSel >= 0) {
            int hy2 = hy + 14 + 9 * 20 + 2;
            int hh = y + bh - 26 - hy2;
            if (hh >= 36) {
                card(ms, x0, hy2, cw, hh);
                FTheme.caption(ms, "ISTORIC ACȚIUNI STAFF PE ACEST VEHICUL", x0 + 10, hy2 + 13, FTheme.MUTED);
                JsonObject vh = FAdminClient.vehHist;
                int k = 0;
                if (vh != null) {
                    for (JsonObject r : arr(vh, "rows")) {
                        float hry = hy2 + 20 + k * 12;
                        if (hry + 11 > hy2 + hh) break;
                        String line = when((long) n(r, "ts")) + " · " + s(r, "admin") + " · " + s(r, "action").replace("veh_", "") + " "
                            + (s(r, "old").isEmpty() ? "" : s(r, "old") + " → " + s(r, "new")) + (s(r, "reason").isEmpty() ? "" : " (" + s(r, "reason") + ")");
                        t(ms, clip(line, cw - 20, 6.8f, false), x0 + 10, mid(hry, 11, 6.8f), 6.8f, FTheme.MUTED);
                        k++;
                    }
                    if (k == 0) t(ms, "Nicio acțiune de staff înregistrată pentru acest număr.", x0 + 10, hy2 + 32, 7f, FTheme.FAINT);
                }
            }
            int by = y + bh - 22;
            drawField(ms, reason, x0, by, 190, 20, mx, my);
            float bx = x0 + 198;
            if (has("veh_status")) {
                btn(ms, "fa-vconf", "Confiscă", "flag", bx, by, 82, 20, FUi.DANGER, true, mx, my, () -> vehAct("confiscate"));
                bx += 88;
                btn(ms, "fa-vrel", "Eliberează", "check", bx, by, 82, 20, FUi.SOFT, true, mx, my, () -> vehAct("release"));
                bx += 88;
            }
            if (has("veh_delete")) btn(ms, "fa-vdel", "Șterge", "trash", bx, by, 64, 20, FUi.DANGER, true, mx, my, () -> vehAct("delete"));
        }
    }

    private void vehAct(String action) {
        final long id = vehSel;
        final String why = reason.getValue().trim();
        Runnable run = () -> FAdminClient.ask("veh_act", "id", id, "action", action, "reason", why);
        if (action.equals("delete")) confirm("Șterge vehiculul #" + id, "Ștergerea nu poate fi anulată ușor.", "Șterge", true, true, run);
        else if (action.equals("confiscate")) confirm("Confiscă vehiculul #" + id, "Proprietarul nu îl mai poate folosi.", "Confiscă", true, false, run);
        else run.run();
    }

    // ------------------------------------------------------------------ economie

    private void drawEcon(GuiGraphics ms, int y, int mx, int my) {
        int cx = x0;
        chip(ms, "fa-em0", "Cont jucător", cx, y - 4, 18, econMode == 0, mx, my, () -> {
            econMode = 0;
            enter();
        });
        cx += chipW("Cont jucător", 18);
        chip(ms, "fa-em1", "Tranzacții suspecte", cx, y - 4, 18, econMode == 1, mx, my, () -> {
            econMode = 1;
            FAdminClient.susp = null;
            enter();
        });
        int top = y + 22;
        if (econMode == 1) {
            JsonObject d = FAdminClient.susp;
            if (d == null) {
                FUi.loading(ms, x0 + cw / 2f, top + 80, System.currentTimeMillis() / 1000f, ORANGE);
                return;
            }
            t(ms, "Tranzacții de peste " + fm(n(d, "threshold")) + " în ultimele 7 zile (cele mai mari primele).", x0, top + 6, 7.2f, FTheme.MUTED);
            int ry = top + 14;
            int i = 0;
            for (JsonObject r : arr(d, "rows")) {
                if (i >= 12) break;
                boolean hov = in(mx, my, x0, ry, cw, 18);
                float h = FUi.hot("fa-sr" + i, hov);
                Gfx.rrect(ms, x0, ry, cw, 18, 6f, FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
                t(ms, clip(s(r, "name"), 110, 7.8f, false), x0 + 10, mid(ry, 18, 7.8f), 7.8f, FTheme.TEXT);
                t(ms, clip(s(r, "type"), 130, 7.2f, false), x0 + 130, mid(ry, 18, 7.2f), 7.2f, FTheme.MUTED);
                double am = n(r, "amount");
                t(ms, (am > 0 ? "+" : "") + fm(am), x0 + 270, mid(ry, 18, 7.6f), 7.6f, am >= 0 ? FTheme.OK : FTheme.DANGER);
                tr(ms, ago((long) n(r, "ts")), x0 + cw - 10, mid(ry, 18, 7f), 7f, FTheme.FAINT);
                final String u = s(r, "uuid");
                hit(x0, ry, cw, 18, () -> {
                    econMode = 0;
                    econUuid = u;
                    FAdminClient.econ = null;
                    enter();
                });
                ry += 20;
                i++;
            }
            if (i == 0) FUi.empty(ms, "check", "Nimic suspect", "Nicio tranzacție mare în ultima săptămână.", x0 + cw / 2f, top + 90, 240f);
            return;
        }
        if (econUuid == null) {
            drawSearch(ms, top, cw, mx, my);
            drawPlayerRows(ms, top + 28, mx, my, r -> {
                econUuid = s(r, "uuid");
                FAdminClient.econ = null;
                FAdminClient.ask("econ", "uuid", econUuid);
            });
            return;
        }
        JsonObject d = FAdminClient.econ;
        btn(ms, "fa-eback", "Alt jucător", "chev-left", x0 + cw - 100, y - 4, 100, 20, FUi.GHOST, true, mx, my, () -> {
            econUuid = null;
            FAdminClient.econ = null;
            FAdminClient.ask("players", "q", "", "page", 0);
        });
        if (d == null || !econUuid.equals(s(d, "uuid"))) {
            FUi.loading(ms, x0 + cw / 2f, top + 90, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        float cwid = (cw - 8) / 2f;
        card(ms, x0, top, cwid, 46);
        card(ms, x0 + cwid + 8, top, cwid, 46);
        FTheme.caption(ms, "CASH · " + s(d, "name").toUpperCase(Locale.ROOT), x0 + 10, top + 14, FTheme.MUTED);
        tx(ms, fm(n(d, "cash")), x0 + 10, top + 36, 14f, FTheme.OK);
        FTheme.caption(ms, "BANCĂ", x0 + cwid + 18, top + 14, FTheme.MUTED);
        tx(ms, fm(n(d, "bank")), x0 + cwid + 18, top + 36, 14f, FTheme.SKY);
        int oy = top + 56;
        int lw = 214;
        if (has("econ_modify")) {
            card(ms, x0, oy, lw, y + bh - oy);
            FTheme.caption(ms, "MODIFICĂ SOLD", x0 + 10, oy + 14, FTheme.MUTED);
            chip(ms, "fa-ea0", "CASH", x0 + 10, oy + 22, 18, econAcc == 0, mx, my, () -> econAcc = 0);
            chip(ms, "fa-ea1", "BANCĂ", x0 + 10 + chipW("CASH", 18), oy + 22, 18, econAcc == 1, mx, my, () -> econAcc = 1);
            chip(ms, "fa-eo0", "Adaugă", x0 + 10, oy + 46, 18, econOp == 0, mx, my, () -> econOp = 0);
            chip(ms, "fa-eo1", "Elimină", x0 + 10 + chipW("Adaugă", 18), oy + 46, 18, econOp == 1, mx, my, () -> econOp = 1);
            drawField(ms, amount, x0 + 10, oy + 72, lw - 20, 20, mx, my);
            drawField(ms, reason, x0 + 10, oy + 98, lw - 20, 20, mx, my);
            boolean eok = reasonOk() && parse(amount.getValue()) > 0;
            boolean ehv = btn(ms, "fa-eapply", "Aplică", "check", x0 + 10, oy + 126, lw - 20, 22, FUi.PRIMARY, eok, mx, my, () -> {
                final String eu = econUuid;
                final String acc = econAcc == 0 ? "CASH" : "BANK";
                final String op = econOp == 0 ? "add" : "remove";
                final double amt = parse(amount.getValue());
                final String why = reason.getValue().trim();
                confirm((econOp == 0 ? "Adaugă " : "Elimină ") + fm(amt), "Cont " + (econAcc == 0 ? "cash" : "bancă") + ". Motiv: " + clip(why, 160f, 7f, false), "Aplică", econOp == 1, amt >= 100000,
                    () -> FAdminClient.ask("econ_op", "uuid", eu, "account", acc, "op", op, "amount", amt, "reason", why));
            });
            if (!eok) tipIf(ehv, "Introdu o sumă și un motiv (minim 3 caractere)");
            t(ms, "Limită: " + fm(n(me(), "moneyLimit")) + " / operațiune", x0 + 10, oy + 162, 6.8f, FTheme.FAINT);
        } else {
            card(ms, x0, oy, lw, 52);
            t(ms, "Nivelul tău poate doar vedea soldurile.", x0 + 10, oy + 30, 7.2f, FTheme.MUTED);
        }
        int hx = x0 + lw + 8;
        int hw = cw - lw - 8;
        card(ms, hx, oy, hw, y + bh - oy);
        FTheme.caption(ms, "ISTORIC TRANZACȚII", hx + 10, oy + 14, FTheme.MUTED);
        int i = 0;
        for (JsonObject r : arr(d, "rows")) {
            float ry = oy + 22 + i * 14;
            if (ry + 13 > y + bh - 2) break;
            double am = n(r, "amount");
            t(ms, clip(s(r, "type"), hw - 150, 6.8f, false), hx + 10, mid(ry, 13, 6.8f), 6.8f, FTheme.MUTED);
            tr(ms, (am > 0 ? "+" : "") + String.format(Locale.US, "%,.0f", am), hx + hw - 62, mid(ry, 13, 7.2f), 7.2f, am >= 0 ? FTheme.OK : FTheme.DANGER);
            tr(ms, when((long) n(r, "ts")), hx + hw - 10, mid(ry, 13, 6.6f), 6.6f, FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nicio tranzacție înregistrată.", hx + 10, oy + 40, 7.2f, FTheme.FAINT);
    }

    private static double parse(String v) {
        try {
            return Double.parseDouble(v.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ joburi

    private void drawJobs(GuiGraphics ms, int y, int mx, int my) {
        JsonObject jd = FAdminClient.jobs;
        if (jd == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> jobs = arr(jd, "jobs");
        if (jobPlayer != null) {
            drawJobPlayer(ms, y, mx, my, jobs);
            return;
        }
        float lw = 178f;
        int rows = (bh - 8) / 22;
        card(ms, x0, y, lw, bh - 6);
        FTheme.caption(ms, "JOBURI", x0 + 10, y + 14, FTheme.MUTED);
        int max = Math.max(0, jobs.size() - (rows - 1));
        jobScroll = Math.max(0, Math.min(max, jobScroll));
        for (int i = 0; i < rows - 1 && i + jobScroll < jobs.size(); i++) {
            JsonObject j = jobs.get(i + jobScroll);
            float ry = y + 20 + i * 22;
            boolean sel = s(j, "id").equals(jobSel);
            boolean hov = in(mx, my, x0 + 4, ry, lw - 8, 20);
            float h = FUi.hot("fa-jr" + s(j, "id"), hov || sel);
            Gfx.rrect(ms, x0 + 4, ry, lw - 8, 20, 6f, sel ? FTheme.alpha(ORANGE, 50) : FTheme.alpha(0xFFFFFFFF, (int) (16 * h)));
            Gfx.disc(ms, x0 + 14, ry + 10, 2.6f, b(j, "active") ? FTheme.OK : FTheme.FAINT);
            t(ms, clip(s(j, "name"), lw - 66, 7.8f, false), x0 + 22, mid(ry, 20, 7.8f), 7.8f, FTheme.TEXT);
            tr(ms, String.valueOf((int) n(j, "employees")), x0 + lw - 12, mid(ry, 20, 7.4f), 7.4f, FTheme.MUTED);
            final String id = s(j, "id");
            hit(x0 + 4, (int) ry, (int) lw - 8, 20, () -> {
                jobSel = id;
                FAdminClient.jobPlayers = null;
                FAdminClient.ask("job_players", "id", id);
            });
        }
        int rx = x0 + (int) lw + 8;
        int rw = cw - (int) lw - 8;
        card(ms, rx, y, rw, bh - 6);
        if (jobSel.isEmpty()) {
            FUi.empty(ms, "flag", "Alege un job", "Vezi angajații, nivelul și progresul lor.", rx + rw / 2f, y + 90, 220f);
            return;
        }
        JsonObject pd = FAdminClient.jobPlayers;
        FTheme.caption(ms, "ANGAJAȚI", rx + 10, y + 14, FTheme.MUTED);
        float[] col = {0, 0.46f, 0.6f, 0.78f};
        String[] head = {"JUCĂTOR", "NIV", "CÂȘTIG", "TURĂ"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], rx + 10 + col[i] * (rw - 20), y + 26, FTheme.FAINT);
        if (pd == null || !jobSel.equals(s(pd, "id"))) {
            FUi.loading(ms, rx + rw / 2f, y + 100, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        int i = 0;
        for (JsonObject r : arr(pd, "rows")) {
            float ry = y + 32 + i * 19;
            if (ry + 18 > y + bh - 8) break;
            boolean hov = in(mx, my, rx + 4, ry, rw - 8, 18);
            float h = FUi.hot("fa-er" + s(r, "uuid"), hov);
            Gfx.rrect(ms, rx + 4, ry, rw - 8, 18, 6f, FTheme.alpha(0xFFFFFFFF, (int) (16 * h)));
            float w = rw - 20;
            t(ms, clip(s(r, "name"), col[1] * w - 8, 7.8f, false), rx + 10, mid(ry, 18, 7.8f), 7.8f, FTheme.TEXT);
            t(ms, String.valueOf((int) n(r, "level")), rx + 10 + col[1] * w, mid(ry, 18, 7.6f), 7.6f, ORANGE);
            t(ms, clip(fm(n(r, "earned")), (col[3] - col[2]) * w - 4, 7.2f, false), rx + 10 + col[2] * w, mid(ry, 18, 7.2f), 7.2f, FTheme.MUTED);
            if (b(r, "shift")) pill(ms, "ÎN TURĂ", rx + 10 + col[3] * w, ry + 3, FTheme.OK);
            final String u = s(r, "uuid");
            hit(rx + 4, (int) ry, rw - 8, 18, () -> {
                jobPlayer = u;
                jobTarget = 0;
                FAdminClient.jobProgress = null;
                FAdminClient.ask("job_progress", "uuid", u);
            });
            i++;
        }
        if (i == 0) t(ms, "Niciun angajat la jobul acesta.", rx + 10, y + 52, 7.4f, FTheme.FAINT);
    }

    private void drawJobPlayer(GuiGraphics ms, int y, int mx, int my, List<JsonObject> jobs) {
        btn(ms, "fa-jback", "Înapoi", "chev-left", x0, y - 4, 84, 20, FUi.GHOST, true, mx, my, () -> {
            jobPlayer = null;
            FAdminClient.jobProgress = null;
        });
        JsonObject d = FAdminClient.jobProgress;
        if (d == null || !jobPlayer.equals(s(d, "uuid"))) {
            FUi.loading(ms, x0 + cw / 2f, y + 100, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        int top = y + 22;
        float lw = cw * 0.52f;
        card(ms, x0, top, lw, y + bh - top - 6);
        tx(ms, clip(s(d, "name"), lw - 24, 11f, true), x0 + 12, top + 20, 11f, FTheme.TEXT);
        t(ms, "Job curent: " + (s(d, "job").isEmpty() ? "—" : s(d, "job")), x0 + 12, top + 34, 7.4f, FTheme.MUTED);
        FTheme.caption(ms, "PROGRES PE JOBURI", x0 + 12, top + 52, FTheme.MUTED);
        int i = 0;
        for (JsonObject r : arr(d, "rows")) {
            float ry = top + 58 + i * 28;
            if (ry + 26 > y + bh - 8) break;
            Gfx.rrect(ms, x0 + 8, ry, lw - 16, 25, 7f, FTheme.PANEL);
            t(ms, clip(s(r, "name"), lw - 100, 7.8f, false), x0 + 16, ry + 11, 7.8f, FTheme.TEXT);
            tr(ms, "Nivel " + (int) n(r, "level"), x0 + lw - 16, ry + 11, 7.4f, ORANGE);
            t(ms, (long) n(r, "xp") + " XP · " + fm(n(r, "earned")) + " · " + (int) n(r, "shifts") + " ture · " + (int) n(r, "missions") + " misiuni",
                x0 + 16, ry + 21, 6.6f, FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nu are progres la niciun job.", x0 + 12, top + 70, 7.4f, FTheme.FAINT);
        int rx = x0 + (int) lw + 8;
        int rw = cw - (int) lw - 8;
        card(ms, rx, top, rw, y + bh - top - 6);
        FTheme.caption(ms, "MODIFICĂ", rx + 10, top + 14, FTheme.MUTED);
        String target = jobs.isEmpty() ? "—" : s(jobs.get(Math.floorMod(jobTarget, jobs.size())), "name");
        FUi.iconButton(ms, "fa-jt-l", "chev-left", rx + 10, top + 22, 18, false, mx, my);
        hit(rx + 10, top + 22, 18, 18, () -> jobTarget--);
        Gfx.text(ms, clip(target, rw - 80, 8f, true), rx + rw / 2f, top + 34, 8f, FTheme.TEXT, 1, true, 0f);
        FUi.iconButton(ms, "fa-jt-r", "chev-right", rx + rw - 28, top + 22, 18, false, mx, my);
        hit(rx + rw - 28, top + 22, 18, 18, () -> jobTarget++);
        final String targetId = jobs.isEmpty() ? "" : s(jobs.get(Math.floorMod(jobTarget, jobs.size())), "id");
        float half = (rw - 28) / 2f;
        if (has("set_job")) {
            btn(ms, "fa-jset", "Setează job", "check", rx + 10, top + 46, half, 22, FUi.PRIMARY, !targetId.isEmpty(), mx, my, () ->
                FAdminClient.ask("job_set", "uuid", jobPlayer, "job", targetId, "reason", reason.getValue().trim()));
            btn(ms, "fa-jfire", "Concediază", "trash", rx + 18 + half, top + 46, half, 22, FUi.DANGER, true, mx, my, () ->
                FAdminClient.ask("job_set", "uuid", jobPlayer, "job", "", "reason", reason.getValue().trim()));
        }
        if (has("job_grade")) {
            drawField(ms, amount, rx + 10, top + 76, rw - 20, 20, mx, my);
            btn(ms, "fa-jgrade", "Setează nivelul la jobul ales", "star", rx + 10, top + 102, rw - 20, 22, FUi.SOFT, !targetId.isEmpty(), mx, my, () ->
                FAdminClient.ask("job_grade", "uuid", jobPlayer, "job", targetId, "level", (int) parse(amount.getValue()), "reason", reason.getValue().trim()));
        }
        drawField(ms, reason, rx + 10, top + 134, rw - 20, 20, mx, my);
        t(ms, "Schimbarea ocolește cooldown-ul și se înregistrează în jurnal.", rx + 10, top + 170, 6.6f, FTheme.FAINT);
    }

    // ------------------------------------------------------------------ loguri

    private void drawLogs(GuiGraphics ms, int y, int mx, int my) {
        drawSearch(ms, y, cw, mx, my);
        JsonObject d = FAdminClient.logs;
        int hy = y + 28;
        float[] col = {0, 0.14f, 0.3f, 0.43f, 0.58f, 0.76f};
        String[] head = {"CÂND", "ADMIN", "ACȚIUNE", "TINTĂ", "SCHIMBARE", "MOTIV"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), hy + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) FUi.empty(ms, "clock", "Jurnal gol", "Nicio acțiune de staff înregistrată.", x0 + cw / 2f, hy + 80, 240f);
        int ry = hy + 14;
        float w = cw - 20;
        for (JsonObject r : rows) {
            boolean hov = in(mx, my, x0, ry, cw, 16);
            Gfx.rrect(ms, x0, ry, cw, 16, 5f, hov ? FTheme.PANEL_HI : FTheme.BG2);
            float rx = x0 + 10;
            t(ms, when((long) n(r, "ts")), rx, mid(ry, 16, 6.6f), 6.6f, FTheme.FAINT);
            t(ms, clip(s(r, "admin"), (col[2] - col[1]) * w - 4, 7f, false), rx + col[1] * w, mid(ry, 16, 7f), 7f, FTheme.TEXT);
            t(ms, clip(s(r, "action"), (col[3] - col[2]) * w - 4, 7f, false), rx + col[2] * w, mid(ry, 16, 7f), 7f, ORANGE);
            t(ms, clip(s(r, "target"), (col[4] - col[3]) * w - 4, 7f, false), rx + col[3] * w, mid(ry, 16, 7f), 7f, FTheme.MUTED);
            String ch = s(r, "old").isEmpty() && s(r, "new").isEmpty() ? "" : s(r, "old") + " → " + s(r, "new");
            t(ms, clip(ch, (col[5] - col[4]) * w - 4, 6.8f, false), rx + col[4] * w, mid(ry, 16, 6.8f), 6.8f, FTheme.SKY);
            t(ms, clip(s(r, "reason"), (1 - col[5]) * w - 2, 6.8f, false), rx + col[5] * w, mid(ry, 16, 6.8f), 6.8f, FTheme.MUTED);
            ry += 17;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 14, mx, my,
            pg -> FAdminClient.ask("logs", "q", s(FAdminClient.logs, "q"), "page", pg));
    }

    // ------------------------------------------------------------------ server

    private void drawSystem(GuiGraphics ms, int y, int mx, int my) {
        JsonObject d = FAdminClient.system;
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        float gap = 8f;
        float w = (cw - gap * 3) / 4f;
        double tps = n(d, "tps");
        statCard(ms, x0, y, w, "TPS", tps < 0 ? "—" : String.format(Locale.US, "%.1f", tps), "ținta: 20", tps >= 18 || tps < 0 ? FTheme.OK : FTheme.WARN);
        double ram = n(d, "ramMax") <= 0 ? 0 : n(d, "ramUsed") / n(d, "ramMax");
        statCard(ms, x0 + (w + gap), y, w, "RAM", (int) n(d, "ramUsed") + " MB", "din " + (int) n(d, "ramMax") + " MB", ram > 0.85 ? FTheme.WARN : FTheme.SKY);
        statCard(ms, x0 + (w + gap) * 2, y, w, "JUCĂTORI", (int) n(d, "online") + " / " + (int) n(d, "max"), (int) n(d, "worlds") + " lumi · " + (int) n(d, "chunks") + " chunk-uri", FTheme.OK);
        statCard(ms, x0 + (w + gap) * 3, y, w, "ENTITĂȚI", String.valueOf((int) n(d, "entities")), (int) n(d, "modulesOn") + " module pornite", YELLOW);
        // stare configurare
        int cy = y + 60;
        card(ms, x0, cy, cw, 40);
        FTheme.caption(ms, "STARE CONFIGURARE", x0 + 10, cy + 13, FTheme.MUTED);
        float px2 = x0 + 10;
        px2 += pill(ms, b(d, "luckperms") ? "LuckPerms activ" : "fără LuckPerms", px2, cy + 20, b(d, "luckperms") ? FTheme.OK : FTheme.WARN) + 5;
        px2 += pill(ms, b(d, "vault") ? "Vault activ" : "economie internă", px2, cy + 20, b(d, "vault") ? FTheme.OK : FTheme.SKY) + 5;
        pill(ms, b(d, "jailCell") ? "celulă setată" : "celulă nesetată", px2, cy + 20, b(d, "jailCell") ? FTheme.OK : FTheme.WARN);
        // anunturi
        int ay = cy + 48;
        float half = (cw - gap) / 2f;
        float ah = 92f;
        card(ms, x0, ay, half, ah);
        card(ms, x0 + half + gap, ay, half, ah);
        FTheme.caption(ms, "ANUNȚ PENTRU TOȚI JUCĂTORII", x0 + 10, ay + 13, FTheme.MUTED);
        FTheme.caption(ms, "ANUNȚURI RECENTE", x0 + half + gap + 10, ay + 13, FTheme.MUTED);
        if (has("announce")) {
            drawField(ms, value, x0 + 10, ay + 20, (int) (half - 20), 20, mx, my);
            chip(ms, "fa-an0", "În chat", x0 + 10, ay + 46, 16, annMode == 0, mx, my, () -> annMode = 0);
            chip(ms, "fa-an1", "Pe ecran", x0 + 10 + chipW("În chat", 16), ay + 46, 16, annMode == 1, mx, my, () -> annMode = 1);
            btn(ms, "fa-annsend", "Trimite", "bell", x0 + 10, ay + 68, half - 20, 18, FUi.PRIMARY, true, mx, my,
                () -> FAdminClient.ask("announce", "text", value.getValue().trim(), "mode", annMode == 1 ? "title" : "chat"));
            if (has("announce")) btn(ms, "fa-tablet", "Primește Tableta FAdmin", "star", x0 + half + gap + 10, ay + ah - 24, half - 20, 18, FUi.GHOST, true, mx, my,
                () -> FAdminClient.ask("tablet"));
        } else {
            t(ms, "Doar Administrator+ poate trimite anunțuri.", x0 + 10, ay + 36, 7.2f, FTheme.FAINT);
        }
        int k = 0;
        for (JsonObject r : arr(d, "announcements")) {
            float ry = ay + 20 + k * 14;
            if (ry + 12 > ay + ah - 26) break;
            t(ms, clip(s(r, "admin") + ": " + s(r, "text"), half - 60, 7f, false), x0 + half + gap + 10, mid(ry, 12, 7f), 7f, FTheme.TEXT);
            tr(ms, ago((long) n(r, "ts")), x0 + cw - 10, mid(ry, 12, 6.8f), 6.8f, FTheme.FAINT);
            k++;
        }
        if (k == 0) t(ms, "Niciun anunț încă.", x0 + half + gap + 12, ay + 34, 7.2f, FTheme.FAINT);
        // detalii tehnice (doar Developer+)
        int ey = ay + (int) ah + 8;
        float eh = y + bh - ey - 4;
        card(ms, x0, ey, cw, eh);
        if (!b(d, "sensitive")) {
            FTheme.caption(ms, "DETALII TEHNICE", x0 + 10, ey + 14, FTheme.MUTED);
            t(ms, "Modulele, versiunea și erorile serverului sunt vizibile doar pentru Developer și Owner.", x0 + 10, ey + 32, 7.2f, FTheme.FAINT);
            return;
        }
        FTheme.caption(ms, "ERORI ȘI AVERTISMENTE RECENTE · v" + s(d, "version") + " · " + s(d, "economy"), x0 + 10, ey + 14, FTheme.MUTED);
        int i = 0;
        for (JsonObject r : arr(d, "errors")) {
            float ry = ey + 22 + i * 13;
            if (ry + 12 > ey + eh - 4) break;
            Gfx.disc(ms, x0 + 14, ry + 6, 2.6f, "SEVERE".equals(s(r, "level")) ? FTheme.DANGER : FTheme.WARN);
            t(ms, clip(s(r, "text"), cw - 100, 7f, false), x0 + 24, mid(ry, 12, 7f), 7f, FTheme.TEXT);
            tr(ms, ago((long) n(r, "ts")), x0 + cw - 10, mid(ry, 12, 6.8f), 6.8f, FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nicio eroare de la pornire.", x0 + 14, ey + 38, 7.4f, FTheme.OK);
    }

    // ------------------------------------------------------------------ editor

    private JsonObject selRow() {
        for (JsonObject r : arr(FAdminClient.cfgList, "rows")) if (cfgSel.equals(s(r, "key"))) return r;
        return null;
    }

    private String selType() {
        JsonObject r = selRow();
        return r == null ? "" : s(r, "type");
    }

    private void drawEditor(GuiGraphics ms, int y, int mx, int my) {
        JsonObject cats = FAdminClient.cfgCats;
        if (cats == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> cl = arr(cats, "cats");
        if (cat.isEmpty() && !cl.isEmpty()) {
            cat = s(cl.get(0), "id");
            FAdminClient.ask("cfg_list", "cat", cat);
        }
        int cx = x0;
        for (JsonObject c : cl) {
            final String id = s(c, "id");
            String label = s(c, "label");
            chip(ms, "fa-cat" + id, label, cx, y - 4, 18, id.equals(cat), mx, my, () -> {
                cat = id;
                cfgSel = "";
                cfgScroll = 0;
                FAdminClient.cfgList = null;
                FAdminClient.ask("cfg_list", "cat", id);
            });
            cx += chipW(label, 18);
        }
        // scurtaturi catre editoarele grafice existente
        int sy = y + 20;
        float bw = 128f;
        btn(ms, "fa-sc-wheel", "Roata (recompense)", "star", x0 + cw - bw * 2 - 8, sy, bw, 18, FUi.GHOST, true, mx, my, () ->
            Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.wheel.WheelAdminScreen()));
        btn(ms, "fa-sc-gar", "Garaje (locații)", "pin", x0 + cw - bw, sy, bw, 18, FUi.GHOST, true, mx, my, () ->
            Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.cars.client.GarageAdminScreen()));
        JsonObject d = FAdminClient.cfgList;
        int top = y + 44;
        if (d == null || !cat.equals(s(d, "cat"))) {
            FUi.loading(ms, x0 + cw / 2f, top + 70, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        int editH = cfgSel.isEmpty() ? 0 : 54;
        int avail = y + bh - top - editH - (editH > 0 ? 6 : 0) - 4;
        int per = Math.max(3, avail / 19);
        cfgScroll = Math.max(0, Math.min(Math.max(0, rows.size() - per), cfgScroll));
        if (rows.isEmpty()) FUi.empty(ms, "gear", "Nicio valoare", "Categoria nu are valori editabile.", x0 + cw / 2f, top + 60, 240f);
        for (int i = 0; i < per && i + cfgScroll < rows.size(); i++) {
            JsonObject r = rows.get(i + cfgScroll);
            float ry = top + i * 19;
            boolean sel = cfgSel.equals(s(r, "key"));
            boolean hov = in(mx, my, x0, ry, cw, 18);
            float h = FUi.hot("fa-cr" + s(r, "key"), hov || sel);
            Gfx.rrect(ms, x0, ry, cw, 18, 6f, sel ? FTheme.alpha(ORANGE, 50) : FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
            String path = s(r, "path");
            t(ms, clip(s(r, "file") + " · " + path, cw - 130, 7.2f, false), x0 + 10, mid(ry, 18, 7.2f), 7.2f, FTheme.MUTED);
            tr(ms, clip(s(r, "value"), 110, 7.8f, false), x0 + cw - 10, mid(ry, 18, 7.8f), 7.8f, FTheme.TEXT);
            final String key = s(r, "key");
            final String val = s(r, "value");
            hit(x0, (int) ry, cw, 18, () -> {
                cfgSel = key;
                value.setValue(val);
                reason.setValue("");
            });
        }
        if (rows.size() > per) FUi.scrollbar(ms, x0 + cw + 2, top, per * 19, rows.size() * 19, per * 19, cfgScroll * 19);
        if (!cfgSel.isEmpty()) {
            int ey = y + bh - editH - 4;
            card(ms, x0, ey, cw, editH);
            JsonObject r = selRow();
            String type = r == null ? "" : s(r, "type");
            t(ms, clip(cfgSel, cw - 20, 7f, false), x0 + 10, ey + 12, 7f, FTheme.MUTED);
            if ("bool".equals(type)) {
                boolean on = "true".equalsIgnoreCase(value.getValue());
                chip(ms, "fa-bt", "Da", x0 + 10, ey + 20, 20, on, mx, my, () -> value.setValue("true"));
                chip(ms, "fa-bf", "Nu", x0 + 10 + chipW("Da", 20), ey + 20, 20, !on, mx, my, () -> value.setValue("false"));
                drawField(ms, reason, x0 + 100, ey + 24, cw - 200, 20, mx, my);
            } else {
                drawField(ms, value, x0 + 10, ey + 24, 130, 20, mx, my);
                drawField(ms, reason, x0 + 148, ey + 24, cw - 258, 20, mx, my);
            }
            final String catNow = cat;
            btn(ms, "fa-csave", "Salvează", "check", x0 + cw - 98, ey + 24, 88, 20, FUi.PRIMARY, true, mx, my, () ->
                FAdminClient.ask("cfg_set", "cat", catNow, "key", cfgSel, "value", value.getValue().trim(), "reason", reason.getValue().trim()));
        }
    }

    // ------------------------------------------------------------------ permisiuni

    private static String capLabel(String id) {
        switch (id) {
            case "dash": return "dashboard";
            case "players": return "jucători";
            case "kick": return "kick";
            case "mute": return "mute";
            case "tp": return "teleport";
            case "bring": return "bring";
            case "history": return "istoric";
            case "ban": return "ban";
            case "unban": return "unban";
            case "heal": return "heal";
            case "inv": return "inventar";
            case "veh_view": return "vehicule";
            case "job_view": return "joburi";
            case "logs": return "loguri";
            case "set_job": return "schimbă job";
            case "job_grade": return "grad job";
            case "veh_status": return "confiscare";
            case "econ_view": return "economie (citire)";
            case "econ_modify": return "economie (modificare)";
            case "veh_delete": return "ștergere vehicule";
            case "cfg_basic": return "editor taxe/prețuri";
            case "perm_view": return "permisiuni";
            case "system": return "sistem";
            case "cfg_system": return "editor setări";
            case "perm_edit": return "gestionare permisiuni";
            case "warn": return "warn";
            case "mod_view": return "moderare";
            case "report_view": return "rapoarte";
            case "report_handle": return "gestionare rapoarte";
            case "duty": return "tură (duty)";
            case "freeze": return "freeze";
            case "spectate": return "spectate";
            case "jail": return "închisoare";
            case "staff_view": return "staff";
            case "rpid": return "schimbare ID RP";
            case "jail_set": return "setare celulă";
            case "announce": return "anunțuri";
            case "server_view": return "server";
            case "rank": return "schimbare grup";
            default: return id;
        }
    }

    private void drawPerm(GuiGraphics ms, int y, int mx, int my) {
        String[] tl = {"Niveluri FAdmin", "Grupuri LuckPerms", "Inspectează jucător"};
        int cx = x0;
        for (int i = 0; i < tl.length; i++) {
            final int ii = i;
            chip(ms, "fa-pm" + i, tl[i], cx, y - 4, 18, permTab == i, mx, my, () -> {
                permTab = ii;
                lpPick = null;
                search.setValue("");
                enter();
            });
            cx += chipW(tl[i], 18);
        }
        int by = y + 22;
        int hh = bh - 22;
        if (permTab == 0) {
            // vechiul ecran de niveluri, mutat mai jos cu inaltimea tab-urilor
            int saveBh = bh;
            bh = hh;
            drawPermLevels(ms, by, mx, my);
            bh = saveBh;
        } else if (permTab == 1) {
            drawPermGroups(ms, by, hh, mx, my);
        } else {
            drawPermInspect(ms, by, hh, mx, my);
        }
    }

    private void drawPermGroups(GuiGraphics ms, int y, int hh, int mx, int my) {
        JsonObject g = FAdminClient.lpGroups;
        if (g == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 60, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        if (!b(g, "available")) {
            FUi.empty(ms, "gear", "LuckPerms nu este instalat", "Grupurile și permisiunile vin din LuckPerms.", x0 + cw / 2f, y + 70, 260f);
            return;
        }
        float lw = cw * 0.34f;
        card(ms, x0, y, lw, hh - 6);
        FTheme.caption(ms, "GRUPURI", x0 + 10, y + 14, FTheme.MUTED);
        List<String> groups = new ArrayList<>();
        if (g.has("groups") && g.get("groups").isJsonArray()) for (JsonElement e : g.getAsJsonArray("groups")) groups.add(e.getAsString());
        int per = (int) ((hh - 40) / 16);
        lpScroll = Math.max(0, Math.min(lpScroll, Math.max(0, groups.size() - per)));
        for (int i = 0; i < per && i + lpScroll < groups.size(); i++) {
            final String gr = groups.get(i + lpScroll);
            float ry = y + 22 + i * 16;
            boolean sel = gr.equals(lpGroup);
            boolean hov = in(mx, my, x0 + 6, ry, lw - 12, 14);
            Gfx.rrect(ms, x0 + 6, ry, lw - 12, 14, 5f, sel ? FTheme.alpha(ORANGE, 50) : hov ? FTheme.PANEL_HI : FTheme.PANEL);
            t(ms, clip(gr, lw - 28, 7.4f, false), x0 + 12, mid(ry, 14, 7.4f), 7.4f, sel ? FTheme.TEXT : FTheme.MUTED);
            hit((int) x0 + 6, (int) ry, (int) lw - 12, 14, () -> {
                lpGroup = gr;
                FAdminClient.ask("lp_groups", "group", gr);
            });
        }
        float rx = x0 + lw + 8;
        float rw = cw - lw - 8;
        card(ms, rx, y, rw, hh - 6);
        if (lpGroup.isEmpty() || !lpGroup.equals(s(g, "group"))) {
            t(ms, "Alege un grup ca să-i vezi permisiunile.", rx + 12, y + 30, 7.4f, FTheme.FAINT);
            return;
        }
        FTheme.caption(ms, "PERMISIUNILE GRUPULUI " + lpGroup.toUpperCase(Locale.ROOT) + " (" + (int) n(g, "permTotal") + ")", rx + 10, y + 14, FTheme.MUTED);
        int k = 0;
        for (JsonObject r : arr(g, "perms")) {
            float ry = y + 22 + k * 12;
            if (ry + 11 > y + hh - 10) break;
            t(ms, clip(s(r, "key"), rw - 50, 6.8f, false), rx + 10, mid(ry, 11, 6.8f), 6.8f, FTheme.TEXT);
            tr(ms, b(r, "value") ? "da" : "nu", rx + rw - 10, mid(ry, 11, 6.8f), 6.8f, b(r, "value") ? FTheme.OK : FTheme.DANGER);
            k++;
        }
    }

    private void drawPermInspect(GuiGraphics ms, int y, int hh, int mx, int my) {
        if (lpPick == null) {
            drawSearch(ms, y, cw, mx, my);
            drawPlayerRows(ms, y + 28, mx, my, r -> {
                lpPick = s(r, "uuid");
                reason.setValue("");
                FAdminClient.lpUser = null;
                FAdminClient.ask("lp_user", "uuid", lpPick);
                FAdminClient.ask("lp_groups");
            });
            return;
        }
        btn(ms, "fa-lpback", "Înapoi la listă", "chev-left", x0, y - 2, 112, 20, FUi.GHOST, true, mx, my, () -> {
            lpPick = null;
            FAdminClient.lpUser = null;
            FAdminClient.ask("players", "q", search.getValue().trim(), "page", 0);
        });
        JsonObject u = FAdminClient.lpUser;
        if (u != null && lpPick.equals(s(u, "uuid"))) tx(ms, s(u, "name"), x0 + 124, y + 12, 10f, FTheme.TEXT);
        card(ms, x0, y + 24, cw, hh - 30 - (has("rank") ? 26 : 0));
        drawLpUser(ms, lpPick, x0 + 12, y + 30, cw - 24, hh - 36 - (has("rank") ? 26 : 0), mx, my, true);
        if (has("rank")) drawField(ms, reason, x0, y + hh - 24, cw, 20, mx, my);
    }

    private void drawPermLevels(GuiGraphics ms, int y, int mx, int my) {
        float lw = cw * 0.55f;
        card(ms, x0, y, lw, bh - 6);
        FTheme.caption(ms, "CE POATE FIECARE NIVEL (CUMULATIV)", x0 + 10, y + 14, FTheme.MUTED);
        int i = 0;
        List<JsonObject> lv = arr(me(), "levels");
        float rowH = Math.max(34f, (bh - 30f) / Math.max(1, lv.size()));
        for (JsonObject l : lv) {
            float ry = y + 20 + i * rowH;
            Gfx.rrect(ms, x0 + 6, ry, lw - 12, rowH - 4, 7f, FTheme.PANEL);
            boolean mine = s(l, "id").equals(s(me(), "level"));
            tx(ms, s(l, "label"), x0 + 14, ry + 12, 8.4f, mine ? ORANGE : FTheme.TEXT);
            if (mine) pill(ms, "TU", x0 + lw - 36, ry + 3, ORANGE);
            StringBuilder sb = new StringBuilder();
            JsonArray adds = l.has("adds") && l.get("adds").isJsonArray() ? l.getAsJsonArray("adds") : new JsonArray();
            for (JsonElement e : adds) sb.append(sb.length() == 0 ? "" : ", ").append(capLabel(e.getAsString()));
            t(ms, clip("+ " + sb, lw - 28, 6.6f, false), x0 + 14, ry + 23, 6.6f, FTheme.MUTED);
            i++;
        }
        int rx = x0 + (int) lw + 8;
        int rw = cw - (int) lw - 8;
        JsonObject pd = FAdminClient.permList;
        boolean edit = has("perm_edit");
        int listH = edit ? bh - 6 - 112 : bh - 6;
        card(ms, rx, y, rw, listH);
        FTheme.caption(ms, "STAFF ONLINE", rx + 10, y + 14, FTheme.MUTED);
        if (pd == null) {
            FUi.loading(ms, rx + rw / 2f, y + 60, System.currentTimeMillis() / 1000f, ORANGE);
        } else {
            int k = 0;
            for (JsonObject r : arr(pd, "staff")) {
                float ry = y + 22 + k * 16;
                if (ry + 14 > y + listH - 4) break;
                t(ms, clip(s(r, "name"), rw - 96, 7.6f, false), rx + 10, mid(ry, 14, 7.6f), 7.6f, FTheme.TEXT);
                pill(ms, s(r, "label") + (b(r, "op") ? " · OP" : ""), rx + rw - 10 - (Gfx.width(s(r, "label") + (b(r, "op") ? " · OP" : ""), 6.6f, true) + 10), ry + 1, ORANGE);
                k++;
            }
        }
        if (edit) {
            int ey = y + listH + 6;
            card(ms, rx, ey, rw, 100);
            FTheme.caption(ms, "SETEAZĂ NIVEL (LUCKPERMS)", rx + 10, ey + 14, FTheme.MUTED);
            drawField(ms, pname, rx + 10, ey + 20, rw - 20, 20, mx, my);
            String[] lab = {"Helper", "Mod", "Admin", "Manager", "Dev", "Owner", "Nimic"};
            int cx = rx + 10;
            int cy = ey + 44;
            for (int k = 0; k < lab.length; k++) {
                final int kk = k;
                int w = chipW(lab[k], 16);
                if (cx + w > rx + rw - 6) {
                    cx = rx + 10;
                    cy += 18;
                }
                chip(ms, "fa-pl" + k, lab[k], cx, cy, 16, permLevel == k, mx, my, () -> permLevel = kk);
                cx += w;
            }
            drawField(ms, reason, rx + 10, ey + 78, rw - 106, 18, mx, my);
            btn(ms, "fa-pset", "Setează", "check", rx + rw - 90, ey + 77, 80, 20, FUi.PRIMARY, true, mx, my, () -> {
                String[] ids = {"helper", "moderator", "administrator", "manager", "developer", "owner", "none"};
                final String nm = pname.getValue().trim();
                final String lvl = ids[Math.max(0, Math.min(6, permLevel))];
                final String why = reason.getValue().trim();
                confirm("Nivel staff: " + lvl, "Pentru " + nm + ". Se aplică imediat.", "Setează", true, lvl.equals("owner") || lvl.equals("none"),
                    () -> FAdminClient.ask("perm_set", "name", nm, "level", lvl, "reason", why));
            });
        } else {
            t(ms, "Doar Owner poate modifica nivelurile.", rx + 10, y + listH + 14, 7f, FTheme.FAINT);
        }
    }

    // ------------------------------------------------------------------ moderare

    private void drawMod(GuiGraphics ms, int y, int mx, int my) {
        int cx = x0;
        for (int i = 0; i < MOD_L.length; i++) {
            final int ii = i;
            chip(ms, "fa-md" + i, MOD_L[i], cx, y - 4, 18, modTab == i, mx, my, () -> {
                modTab = ii;
                modSel = null;
                reason.setValue("");
                FAdminClient.mod = null;
                FAdminClient.ask("mod", "tab", MOD_ID[ii], "page", 0);
            });
            cx += chipW(MOD_L[i], 18);
        }
        JsonObject d = FAdminClient.mod;
        int hy = y + 22;
        boolean jail = modTab == 3;
        boolean hist = modTab == 4;
        if (jail) {
            boolean cell = d != null && b(d, "cell");
            t(ms, cell ? "Celula de închisoare este setată." : "Nu există celulă de închisoare setată.", x0, hy + 10, 7.4f, cell ? FTheme.OK : FTheme.WARN);
            if (has("jail_set")) btn(ms, "fa-jset", "Setează celula aici", "pin", x0 + cw - 150, hy - 2, 150, 20, FUi.SOFT, true, mx, my,
                () -> FAdminClient.ask("jail_set"));
            hy += 22;
            t(ms, "Băgarea în închisoare se face din profilul jucătorului (Jucători → Închisoare).", x0, hy + 6, 6.8f, FTheme.FAINT);
            hy += 12;
        }
        float[] col = hist ? new float[] {0, 0.14f, 0.3f, 0.5f, 0.72f} : new float[] {0, 0.2f, 0.52f, 0.68f, 0.84f};
        String[] head = hist ? new String[] {"CÂND", "TIP", "JUCĂTOR", "DE LA", "MOTIV"}
            : modTab == 2 ? new String[] {"JUCĂTOR", "MOTIV", "DE LA", "CÂND", ""}
            : jail ? new String[] {"JUCĂTOR", "MOTIV", "DE LA", "EXPIRĂ", "STARE"} : new String[] {"JUCĂTOR", "MOTIV", "DE LA", "CÂND", "EXPIRĂ"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), hy + 8, FTheme.FAINT);
        if (d == null || !MOD_ID[modTab].equals(s(d, "tab"))) {
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) FUi.empty(ms, "flag", "Nimic aici", "Nicio înregistrare în această listă.", x0 + cw / 2f, hy + 70, 240f);
        int ry = hy + 14;
        float w = cw - 20;
        for (JsonObject r : rows) {
            boolean sel = !hist && s(r, "uuid").equals(modSel);
            boolean hov = in(mx, my, x0, ry, cw, 17);
            Gfx.rrect(ms, x0, ry, cw, 17, 5f, sel ? FTheme.alpha(ORANGE, 50) : hov ? FTheme.PANEL_HI : FTheme.BG2);
            float rx = x0 + 10;
            if (hist) {
                t(ms, when((long) n(r, "ts")), rx, mid(ry, 17, 6.8f), 6.8f, FTheme.FAINT);
                String k = s(r, "kind");
                int c = "BAN".equals(k) ? FTheme.DANGER : "MUTE".equals(k) || "KICK".equals(k) || "JAIL".equals(k) ? FTheme.WARN : "WARN".equals(k) ? YELLOW : FTheme.SKY;
                t(ms, k, rx + col[1] * w, mid(ry, 17, 7f), 7f, c);
                t(ms, clip(s(r, "name"), (col[3] - col[2]) * w - 4, 7.2f, false), rx + col[2] * w, mid(ry, 17, 7.2f), 7.2f, FTheme.TEXT);
                t(ms, clip(s(r, "admin"), (col[4] - col[3]) * w - 4, 7f, false), rx + col[3] * w, mid(ry, 17, 7f), 7f, FTheme.MUTED);
                t(ms, clip(s(r, "reason"), (1 - col[4]) * w - 2, 6.8f, false), rx + col[4] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.MUTED);
            } else {
                t(ms, clip(s(r, "name"), col[1] * w - 8, 7.6f, false), rx, mid(ry, 17, 7.6f), 7.6f, FTheme.TEXT);
                t(ms, clip(s(r, "reason"), (col[2] - col[1]) * w - 6, 7f, false), rx + col[1] * w, mid(ry, 17, 7f), 7f, FTheme.MUTED);
                t(ms, clip(s(r, "admin"), (col[3] - col[2]) * w - 4, 7f, false), rx + col[2] * w, mid(ry, 17, 7f), 7f, FTheme.MUTED);
                if (jail) {
                    long until = (long) n(r, "until");
                    t(ms, until <= 0 ? "permanent" : when(until), rx + col[3] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.FAINT);
                    pill(ms, b(r, "online") ? "ONLINE" : "OFFLINE", rx + col[4] * w - 2, ry + 2.5f, b(r, "online") ? FTheme.OK : FTheme.FAINT);
                } else {
                    t(ms, when((long) n(r, "ts")), rx + col[3] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.FAINT);
                    if (modTab != 2) {
                        int m = (int) n(r, "minutes");
                        t(ms, m <= 0 ? "permanent" : when((long) n(r, "until")), rx + col[4] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.FAINT);
                    }
                }
                final String uu = s(r, "uuid");
                hit(x0, ry, cw, 17, () -> {
                    modSel = uu.equals(modSel) ? null : uu;
                    reason.setValue("");
                });
            }
            ry += 18;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 12, mx, my,
            pg -> FAdminClient.ask("mod", "tab", MOD_ID[modTab], "page", pg));
        if (modSel != null && !hist) {
            int by = y + bh - 22;
            drawField(ms, reason, x0, by, 200, 20, mx, my);
            float bx = x0 + 208;
            final String u = modSel;
            if (modTab == 0 && has("unban")) btn(ms, "fa-mod-unban", "Unban", "check", bx, by, 80, 20, FUi.SOFT, true, mx, my, () -> modAct("unban", u));
            if (modTab == 1 && has("mute")) btn(ms, "fa-mod-unmute", "Unmute", "check", bx, by, 80, 20, FUi.SOFT, true, mx, my, () -> modAct("unmute", u));
            if (jail && has("jail")) btn(ms, "fa-mod-unjail", "Eliberează", "check", bx, by, 90, 20, FUi.SOFT, true, mx, my, () -> modAct("unjail", u));
            btn(ms, "fa-mod-prof", "Profil", "eye", x0 + cw - 84, by, 84, 20, FUi.GHOST, true, mx, my, () -> {
                go(S_PLAYERS);
                selUuid = u;
                FAdminClient.player = null;
                FAdminClient.ask("player", "uuid", u);
            });
        }
    }

    private void modAct(String action, String uuid) {
        FAdminClient.ask("act", "uuid", uuid, "action", action, "reason", reason.getValue().trim());
        FAdminClient.ask("mod", "tab", MOD_ID[modTab], "page", 0);
        modSel = null;
    }

    // ------------------------------------------------------------------ staff

    private void drawStaff(GuiGraphics ms, int y, int mx, int my) {
        JsonObject d = FAdminClient.staff;
        String me = s(me(), "name");
        boolean myDuty = false;
        for (JsonObject r : arr(d, "rows")) if (me.equals(s(r, "name")) && b(r, "duty")) myDuty = true;
        if (has("duty")) {
            btn(ms, "fa-duty", myDuty ? "Ieși din tură" : "Intră în tură", "clock", x0 + cw - 130, y - 6, 130, 20, myDuty ? FUi.SOFT : FUi.PRIMARY, true, mx, my,
                () -> FAdminClient.ask("duty"));
        }
        t(ms, "Tura (duty) arată cine e activ acum; doar staff-ul din listă o poate porni sau opri pentru el.", x0, y + 4, 6.8f, FTheme.FAINT);
        int hy = y + 18;
        float[] col = {0, 0.2f, 0.34f, 0.5f, 0.64f, 0.78f, 0.9f};
        String[] head = {"STAFF", "NIVEL", "GRUP LP", "TURĂ", "ACȚIUNI 7Z", "RAPOARTE", "ULTIMA"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), hy + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) FUi.empty(ms, "star-line", "Niciun staff", "Nu e nimeni din staff conectat și nicio acțiune în ultimele 7 zile.", x0 + cw / 2f, hy + 70, 280f);
        int ry = hy + 14;
        float w = cw - 20;
        int shown = 0;
        for (JsonObject r : rows) {
            if (shown >= 8) break;
            boolean sel = s(r, "name").equals(staffSel);
            boolean hov = in(mx, my, x0, ry, cw, 17);
            Gfx.rrect(ms, x0, ry, cw, 17, 5f, sel ? FTheme.alpha(ORANGE, 50) : hov ? FTheme.PANEL_HI : FTheme.BG2);
            float rx = x0 + 10;
            Gfx.disc(ms, rx - 4, ry + 8.5f, 2.4f, b(r, "online") ? FTheme.OK : FTheme.FAINT);
            t(ms, clip(s(r, "name"), col[1] * w - 8, 7.6f, false), rx + 2, mid(ry, 17, 7.6f), 7.6f, FTheme.TEXT);
            t(ms, clip(s(r, "level"), (col[2] - col[1]) * w - 4, 7f, false), rx + col[1] * w, mid(ry, 17, 7f), 7f, ORANGE);
            t(ms, clip(s(r, "group"), (col[3] - col[2]) * w - 4, 7f, false), rx + col[2] * w, mid(ry, 17, 7f), 7f, YELLOW);
            if (b(r, "duty")) t(ms, "în tură " + ago((long) n(r, "dutySince")).replace("acum ", ""), rx + col[3] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.OK);
            else t(ms, b(r, "online") ? "în afara turei" : "offline", rx + col[3] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.FAINT);
            t(ms, String.valueOf((int) n(r, "actions")), rx + col[4] * w, mid(ry, 17, 7.2f), 7.2f, FTheme.TEXT);
            t(ms, String.valueOf((int) n(r, "reports")), rx + col[5] * w, mid(ry, 17, 7.2f), 7.2f, FTheme.TEXT);
            t(ms, n(r, "last") > 0 ? ago((long) n(r, "last")).replace("acum ", "") : "—", rx + col[6] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.FAINT);
            final String nm = s(r, "name");
            hit(x0, ry, cw, 17, () -> {
                staffSel = nm.equals(staffSel) ? "" : nm;
                FAdminClient.staffHist = null;
                if (!staffSel.isEmpty()) FAdminClient.ask("staff_hist", "name", nm);
            });
            ry += 18;
            shown++;
        }
        int hy2 = ry + 6;
        float hh = y + bh - hy2 - 2;
        if (hh < 50) return;
        card(ms, x0, hy2, cw, hh);
        if (staffSel.isEmpty()) {
            FTheme.caption(ms, "ISTORIC STAFF", x0 + 10, hy2 + 14, FTheme.MUTED);
            t(ms, "Alege un membru din listă ca să-i vezi ultimele acțiuni.", x0 + 10, hy2 + 32, 7.2f, FTheme.FAINT);
            return;
        }
        FTheme.caption(ms, "ULTIMELE ACȚIUNI ALE LUI " + staffSel.toUpperCase(Locale.ROOT), x0 + 10, hy2 + 14, FTheme.MUTED);
        JsonObject sh = FAdminClient.staffHist;
        if (sh == null || !staffSel.equals(s(sh, "name"))) {
            FUi.loading(ms, x0 + cw / 2f, hy2 + hh / 2f, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        int k = 0;
        for (JsonObject r : arr(sh, "rows")) {
            float yy = hy2 + 22 + k * 12;
            if (yy + 11 > hy2 + hh - 3) break;
            String ch = s(r, "old").isEmpty() && s(r, "new").isEmpty() ? "" : " [" + s(r, "old") + " → " + s(r, "new") + "]";
            String line = when((long) n(r, "ts")) + " · " + s(r, "action") + (s(r, "target").isEmpty() ? "" : " → " + s(r, "target")) + ch
                + (s(r, "reason").isEmpty() ? "" : " (" + s(r, "reason") + ")");
            t(ms, clip(line, cw - 20, 6.8f, false), x0 + 10, mid(yy, 11, 6.8f), 6.8f, FTheme.MUTED);
            k++;
        }
        if (k == 0) t(ms, "Nicio acțiune înregistrată.", x0 + 10, hy2 + 36, 7.2f, FTheme.FAINT);
    }

    // ------------------------------------------------------------------ rapoarte

    private static int repColor(String st) {
        if ("RESOLVED".equals(st)) return FTheme.OK;
        if ("REJECTED".equals(st)) return FTheme.DANGER;
        if ("ACCEPTED".equals(st)) return FTheme.SKY;
        return ORANGE;
    }

    private static String repLabel(String st) {
        if ("RESOLVED".equals(st)) return "REZOLVAT";
        if ("REJECTED".equals(st)) return "RESPINS";
        if ("ACCEPTED".equals(st)) return "PRELUAT";
        return "DESCHIS";
    }

    private JsonObject repRow(long id) {
        for (JsonObject r : arr(FAdminClient.reports, "rows")) if ((long) n(r, "id") == id) return r;
        return null;
    }

    private void drawReports(GuiGraphics ms, int y, int mx, int my) {
        int cx = x0;
        for (int i = 0; i < REP_L.length; i++) {
            final int ii = i;
            chip(ms, "fa-rp" + i, REP_L[i], cx, y - 4, 18, repTab == i, mx, my, () -> {
                repTab = ii;
                repSel = -1;
                FAdminClient.reports = null;
                FAdminClient.ask("reports", "status", REP_ID[ii], "page", 0, "q", search.getValue().trim());
            });
            cx += chipW(REP_L[i], 18);
        }
        int sy = y + 22;
        drawSearch(ms, sy, cw, mx, my);
        JsonObject d = FAdminClient.reports;
        int hy = sy + 26;
        float[] col = {0, 0.07f, 0.27f, 0.47f, 0.78f, 0.9f};
        String[] head = {"ID", "RAPORTOR", "ȚINTĂ", "MOTIV", "DATA", "STARE"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), hy + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, ORANGE);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) FUi.empty(ms, "bell", "Niciun raport", "Jucătorii trimit rapoarte cu /report <jucător> <motiv>.", x0 + cw / 2f, hy + 70, 280f);
        int ry = hy + 14;
        float w = cw - 20;
        for (JsonObject r : rows) {
            long id = (long) n(r, "id");
            boolean sel = id == repSel;
            boolean hov = in(mx, my, x0, ry, cw, 17);
            Gfx.rrect(ms, x0, ry, cw, 17, 5f, sel ? FTheme.alpha(ORANGE, 50) : hov ? FTheme.PANEL_HI : FTheme.BG2);
            float rx = x0 + 10;
            t(ms, "#" + id, rx, mid(ry, 17, 7f), 7f, FTheme.MUTED);
            t(ms, clip(s(r, "reporter"), (col[2] - col[1]) * w - 4, 7.2f, false), rx + col[1] * w, mid(ry, 17, 7.2f), 7.2f, FTheme.TEXT);
            t(ms, clip(s(r, "target"), (col[3] - col[2]) * w - 4, 7.2f, false), rx + col[2] * w, mid(ry, 17, 7.2f), 7.2f, FTheme.TEXT);
            t(ms, clip(s(r, "reason"), (col[4] - col[3]) * w - 4, 6.8f, false), rx + col[3] * w, mid(ry, 17, 6.8f), 6.8f, FTheme.MUTED);
            t(ms, when((long) n(r, "ts")), rx + col[4] * w, mid(ry, 17, 6.6f), 6.6f, FTheme.FAINT);
            pill(ms, repLabel(s(r, "status")), rx + col[5] * w - 4, ry + 2.5f, repColor(s(r, "status")));
            hit(x0, ry, cw, 17, () -> {
                repSel = repSel == id ? -1 : id;
                reason.setValue("");
            });
            ry += 18;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 12, mx, my,
            pg -> FAdminClient.ask("reports", "status", REP_ID[repTab], "page", pg, "q", s(FAdminClient.reports, "q")));
        JsonObject sel = repSel >= 0 ? repRow(repSel) : null;
        if (sel != null) {
            float ch = 56f;
            float cy = y + bh - ch - 2;
            card(ms, x0, cy, cw, ch);
            String info = "Raport #" + repSel + ": " + s(sel, "reason");
            t(ms, clip(info, cw - 20, 7.2f, false), x0 + 10, cy + 12, 7.2f, FTheme.TEXT);
            String who = s(sel, "staff").isEmpty() ? "neasignat" : "staff: " + s(sel, "staff");
            t(ms, who + (s(sel, "note").isEmpty() ? "" : " · notă: " + s(sel, "note")), x0 + 10, cy + 23, 6.8f, FTheme.MUTED);
            if (has("report_handle")) {
                drawField(ms, reason, x0 + 8, (int) (cy + 30), 190, 20, mx, my);
                float bx = x0 + 206;
                final long id = repSel;
                btn(ms, "fa-rep-acc", "Preia", "check", bx, cy + 30, 58, 20, FUi.SOFT, true, mx, my, () -> repAct("accept", id));
                bx += 62;
                btn(ms, "fa-rep-res", "Rezolvă", "check", bx, cy + 30, 66, 20, FUi.PRIMARY, true, mx, my, () -> repAct("resolve", id));
                bx += 70;
                btn(ms, "fa-rep-rej", "Respinge", "close", bx, cy + 30, 70, 20, FUi.DANGER, true, mx, my, () -> repAct("reject", id));
                bx += 74;
                btn(ms, "fa-rep-re", "Redeschide", "chev-up", bx, cy + 30, 80, 20, FUi.GHOST, true, mx, my, () -> repAct("reopen", id));
            }
            if (has("players")) {
                final String tu = s(sel, "targetUuid");
                btn(ms, "fa-rep-prof", "Profil țintă", "eye", x0 + cw - 92, cy + 4, 84, 16, FUi.GHOST, true, mx, my, () -> {
                    go(S_PLAYERS);
                    selUuid = tu;
                    FAdminClient.player = null;
                    FAdminClient.ask("player", "uuid", tu);
                });
            }
        }
    }

    private void repAct(String action, long id) {
        FAdminClient.ask("rep_act", "id", id, "action", action, "reason", reason.getValue().trim(), "filter", REP_ID[repTab],
            "page", (int) n(FAdminClient.reports, "page"));
        reason.setValue("");
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = delta > 0 ? -1 : 1;
        if (sec == S_EDIT) cfgScroll += d;
        else if (sec == S_JOBS) jobScroll += d;
        else if (sec == S_PERM && permTab == 1) lpScroll += d;
        else return super.mouseScrolled(mx, my, delta);
        return true;
    }
}
