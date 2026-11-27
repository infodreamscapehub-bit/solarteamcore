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
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
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
    private static final int S_VEH = 2;
    private static final int S_ECON = 3;
    private static final int S_JOBS = 4;
    private static final int S_LOGS = 5;
    private static final int S_SYS = 6;
    private static final int S_EDIT = 7;
    private static final int S_PERM = 8;
    private static final String[] NAME = {"Dashboard", "Jucători", "Vehicule", "Economie", "Joburi", "Loguri", "Sistem", "Editor", "Permisiuni"};
    private static final String[] ICON = {"grid", "eye", "route", "star", "flag", "clock", "gear", "pencil", "layers"};
    private static final String[] NEED = {"dash", "players", "veh_view", "econ_view", "job_view", "logs", "system", "cfg_basic", "perm_view"};
    private static final int[] DUR = {30, 60, 1440, 10080, 0};
    private static final String[] DUR_L = {"30 min", "1 oră", "1 zi", "7 zile", "Permanent"};
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM HH:mm");

    private int sec = S_DASH;
    private long lastPoll;
    private long refreshAt;
    private boolean asked;

    private final TextFieldWidget search = field("Caută (nume, ID)...", 24, null);
    private final TextFieldWidget reason = field("Motiv (obligatoriu)", 100, null);
    private final TextFieldWidget amount = field("Sumă / nivel", 10, null);
    private final TextFieldWidget value = field("Valoare nouă", 80, null);
    private final TextFieldWidget pname = field("Nume jucător", 16, null);

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

    // geometrie curenta (se recalculeaza la fiecare cadru)
    private int x0;
    private int y0;
    private int cw;
    private int bh;

    public FAdminScreen() {
        super("FAdmin", 590, 346);
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
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> f = new ArrayList<>();
        switch (sec) {
            case S_PLAYERS:
                f.add(selUuid == null ? search : reason);
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
                if (has("perm_edit")) {
                    f.add(pname);
                    f.add(reason);
                }
                break;
            default:
                break;
        }
        return f;
    }

    @Override
    protected void onEnter(TextFieldWidget field) {
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
        boolean hov = FUi.button(ms, id, label, ic, x, y, w, h, style, enabled, mx, my);
        if (enabled) hit((int) x, (int) y, (int) w, (int) h, action);
        return hov;
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
        for (TextFieldWidget f : new TextFieldWidget[] {search, reason, amount, value, pname}) focus(f, false);
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
            case S_EDIT:
                FAdminClient.ask("cfg_cats");
                if (!cat.isEmpty()) FAdminClient.ask("cfg_list", "cat", cat);
                break;
            case S_PERM:
                FAdminClient.ask("perm_list");
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
            case S_VEH:
                FAdminClient.ask("vehicles", "q", q, "page", 0);
                break;
            case S_LOGS:
                FAdminClient.ask("logs", "q", q, "page", 0);
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ cadru

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        FUi.frame(0.016f);
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
        tx(ms, NAME[sec], x0, y0 + 14, 13f, FTheme.TEXT);
        tr(ms, sub, x0 + cw, y0 + 12, 7.4f, FTheme.MUTED);
        FTheme.divider(ms, x0, y0 + 22, cw);
        int by = y0 + 32;
        switch (sec) {
            case S_DASH: drawDash(ms, by, mx, my); break;
            case S_PLAYERS: drawPlayers(ms, by, mx, my); break;
            case S_VEH: drawVehicles(ms, by, mx, my); break;
            case S_ECON: drawEcon(ms, by, mx, my); break;
            case S_JOBS: drawJobs(ms, by, mx, my); break;
            case S_LOGS: drawLogs(ms, by, mx, my); break;
            case S_SYS: drawSystem(ms, by, mx, my); break;
            case S_EDIT: drawEditor(ms, by, mx, my); break;
            default: drawPerm(ms, by, mx, my); break;
        }
    }

    private void sidebar(GuiGraphics ms, int mx, int my) {
        Gfx.rrect(ms, px + 1, py + 1, NAVW, ph - 2, 13f, FTheme.BG2);
        Gfx.rrect(ms, px + NAVW, py + 8, 1, ph - 16, 0, FTheme.LINE);
        FTheme.caption(ms, "FLOWCITY", px + 16, py + 24, FTheme.MUTED);
        tx(ms, "FAdmin", px + 16, py + 42, 15f, FTheme.TEXT);
        int y = py + 56;
        for (int i = 0; i < NAME.length; i++) {
            if (!has(NEED[i])) continue;
            boolean sel = sec == i;
            boolean hot = in(mx, my, px + 8, y, NAVW - 16, 26);
            float h = FUi.hot("fa-nav" + i, hot);
            if (sel) {
                Gfx.rrect(ms, px + 8, y, NAVW - 16, 26, 8f, FTheme.alpha(FTheme.ACCENT, 46));
                Gfx.rrect(ms, px + 8, y + 6, 3, 14, 1.5f, FTheme.ACCENT);
            } else if (h > 0.01f) {
                Gfx.rrect(ms, px + 8, y, NAVW - 16, 26, 8f, FTheme.alpha(0xFFFFFFFF, (int) (14 * h)));
            }
            FUi.icon(ms, ICON[i], px + 24, y + 13, 14, sel ? FTheme.ACCENT : FTheme.mix(FTheme.MUTED, FTheme.TEXT, h));
            Gfx.text(ms, NAME[i], px + 38, mid(y, 26, 7.8f), 7.8f, sel ? FTheme.TEXT : FTheme.mix(FTheme.MUTED, FTheme.TEXT, h), 0, true, 0f);
            final int target = i;
            hit(px + 8, y, NAVW - 16, 26, () -> go(target));
            y += 28;
        }
        // jos: nivel + inchide
        if (allowed()) {
            Gfx.rrect(ms, px + 10, py + ph - 62, NAVW - 20, 24, 8f, FTheme.PANEL);
            tx(ms, clip(s(me(), "name"), NAVW - 36, 7.2f, true), px + 18, py + ph - 54, 7.2f, FTheme.TEXT);
            Gfx.text(ms, s(me(), "label"), px + 18, py + ph - 45, 6.4f, FTheme.ACCENT, 0, true, 0f);
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

    private void drawDash(GuiGraphics ms, int y, int mx, int my) {
        JsonObject d = FAdminClient.dash;
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 80, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
            return;
        }
        float gap = 8f;
        float w = (cw - gap * 3) / 4f;
        double tps = n(d, "tps");
        statCard(ms, x0, y, w, "ONLINE", (int) n(d, "online") + " / " + (int) n(d, "max"), (int) n(d, "staff") + " staff conectați", FTheme.OK);
        statCard(ms, x0 + (w + gap), y, w, "ACTIVI 24H", String.valueOf((int) n(d, "active24")), "+" + (int) n(d, "newToday") + " noi · " + (int) n(d, "total") + " total", FTheme.SKY);
        statCard(ms, x0 + (w + gap) * 2, y, w, "VEHICULE", String.valueOf((int) n(d, "vehicles")), (int) n(d, "bans") + " ban-uri · " + (int) n(d, "mutes") + " mute", FTheme.ACCENT);
        statCard(ms, x0 + (w + gap) * 3, y, w, "SERVER", tps < 0 ? "—" : String.format(Locale.US, "%.1f TPS", tps),
            (int) n(d, "ramUsed") + " / " + (int) n(d, "ramMax") + " MB RAM", tps >= 18 || tps < 0 ? FTheme.OK : FTheme.WARN);
        int ly = y + 62;
        float half = (cw - gap) / 2f;
        card(ms, x0, ly, half, 118f);
        card(ms, x0 + half + gap, ly, half, 118f);
        FTheme.caption(ms, "CONECTAȚI RECENT", x0 + 10, ly + 14, FTheme.MUTED);
        FTheme.caption(ms, "ACȚIUNI ADMINISTRATIVE RECENTE", x0 + half + gap + 10, ly + 14, FTheme.MUTED);
        int i = 0;
        for (JsonObject r : arr(d, "recent")) {
            if (i >= 6) break;
            float ry = ly + 22 + i * 15;
            Gfx.disc(ms, x0 + 14, ry + 6, 2.6f, b(r, "online") ? FTheme.OK : FTheme.FAINT);
            t(ms, clip(s(r, "name"), half - 100, 7.6f, false), x0 + 24, mid(ry, 12, 7.6f), 7.6f, FTheme.TEXT);
            tr(ms, b(r, "online") ? "online" : ago((long) n(r, "ts")), x0 + half - 10, mid(ry, 12, 7f), 7f, b(r, "online") ? FTheme.OK : FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nimic încă.", x0 + 14, ly + 40, 7.4f, FTheme.FAINT);
        i = 0;
        for (JsonObject r : arr(d, "actions")) {
            if (i >= 6) break;
            float ry = ly + 22 + i * 15;
            String line = s(r, "admin") + " · " + s(r, "action") + (s(r, "target").isEmpty() ? "" : " → " + s(r, "target"));
            t(ms, clip(line, half - 84, 7.4f, false), x0 + half + gap + 10, mid(ry, 12, 7.4f), 7.4f, FTheme.TEXT);
            tr(ms, ago((long) n(r, "ts")), x0 + cw - 10, mid(ry, 12, 7f), 7f, FTheme.FAINT);
            i++;
        }
        if (i == 0) t(ms, "Nicio acțiune înregistrată.", x0 + half + gap + 14, ly + 40, 7.4f, FTheme.FAINT);
        int ny = ly + 126;
        float nh = Math.max(48f, y + bh - ny);
        card(ms, x0, ny, cw, nh);
        FTheme.caption(ms, "NOTIFICĂRI ADMINISTRATIVE", x0 + 10, ny + 14, FTheme.MUTED);
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

    // ------------------------------------------------------------------ liste de jucatori

    private void drawPlayerRows(GuiGraphics ms, int y, int mx, int my, java.util.function.Consumer<JsonObject> pick) {
        JsonObject d = FAdminClient.players;
        float[] col = {0, 0.27f, 0.35f, 0.52f, 0.67f, 0.82f, 0.9f};
        String[] head = {"JUCĂTOR", "ID", "JOB", "CASH", "BANCĂ", "AUTO", "STARE"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), y + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
            return;
        }
        List<JsonObject> rows = arr(d, "rows");
        if (rows.isEmpty()) {
            FUi.empty(ms, "search", "Niciun jucător", "Încearcă alt nume sau alt ID.", x0 + cw / 2f, y + 90, 240f);
            return;
        }
        int ry = y + 14;
        for (JsonObject r : rows) {
            boolean hov = in(mx, my, x0, ry, cw, 18);
            float h = FUi.hot("fa-pr" + s(r, "uuid"), hov);
            Gfx.rrect(ms, x0, ry, cw, 18, 6f, FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
            float w = cw - 20;
            float rx = x0 + 10;
            boolean on = b(r, "online");
            Gfx.disc(ms, rx - 4, ry + 9, 2.4f, on ? FTheme.OK : FTheme.FAINT);
            t(ms, clip(s(r, "name"), col[1] * w - 12, 7.8f, false), rx + 2, mid(ry, 18, 7.8f), 7.8f, FTheme.TEXT);
            t(ms, "#" + (int) n(r, "id"), rx + col[1] * w, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            t(ms, clip(s(r, "job").isEmpty() ? "—" : s(r, "job"), (col[3] - col[2]) * w - 6, 7.4f, false), rx + col[2] * w, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            t(ms, clip(fm(n(r, "cash")), (col[4] - col[3]) * w - 4, 7.2f, false), rx + col[3] * w, mid(ry, 18, 7.2f), 7.2f, FTheme.TEXT);
            t(ms, clip(fm(n(r, "bank")), (col[5] - col[4]) * w - 4, 7.2f, false), rx + col[4] * w, mid(ry, 18, 7.2f), 7.2f, FTheme.TEXT);
            t(ms, String.valueOf((int) n(r, "cars")), rx + col[5] * w, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            if (b(r, "banned")) pill(ms, "BAN", rx + col[6] * w - 2, ry + 3, FTheme.DANGER);
            else if (b(r, "muted")) pill(ms, "MUTE", rx + col[6] * w - 2, ry + 3, FTheme.WARN);
            else if (on) pill(ms, "ONLINE", rx + col[6] * w - 2, ry + 3, FTheme.OK);
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
            FUi.loading(ms, x0 + cw / 2f, y + 100, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
            return;
        }
        int top = y + 22;
        float lw = 196f;
        card(ms, x0, top, lw, y + bh - top);
        boolean on = b(p, "online");
        Gfx.disc(ms, x0 + 28, top + 28, 16f, FTheme.ACCENT_DIM);
        String nm = s(p, "name");
        Gfx.text(ms, nm.isEmpty() ? "?" : nm.substring(0, 1).toUpperCase(Locale.ROOT), x0 + 28, top + 34, 15f, FTheme.TEXT, 1, true, 0f);
        tx(ms, clip(nm, lw - 70, 11f, true), x0 + 52, top + 24, 11f, FTheme.TEXT);
        t(ms, "#" + (int) n(p, "id"), x0 + 52, top + 36, 7.4f, FTheme.MUTED);
        pill(ms, on ? "ONLINE" : "OFFLINE", x0 + 52 + Gfx.width("#" + (int) n(p, "id"), 7.4f, false) + 8, top + 28, on ? FTheme.OK : FTheme.FAINT);
        String[][] kv = {
            {"Job", s(p, "job").isEmpty() ? "—" : s(p, "job")},
            {"Cash", fm(n(p, "cash"))},
            {"Bancă", fm(n(p, "bank"))},
            {"Vehicule", String.valueOf(arr(p, "cars").size())},
            {"Poziție", on ? s(p, "pos") : "—"},
            {"Viață", on ? (int) n(p, "health") + " HP" : "—"},
            {"Ultima intrare", on ? "acum" : ago((long) n(p, "last"))},
            {"Timp jucat", (int) (n(p, "playSeconds") / 3600) + " h"}};
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
            {"ban", "Ban", "trash", "ban"}, {"unban", "Unban", "check", "unban"}, {"tp", "Teleport la el", "target", "tp"},
            {"bring", "Adu la mine", "pin", "bring"}, {"heal", "Heal", "plus", "heal"}, {"inv", "Inventar", "layers", "inv"}};
        int i = 0;
        int ay = top + 26;
        for (String[] a : acts) {
            if (!has(a[3])) continue;
            float bx = rx + (i % 2) * (bw + 8);
            float by = ay + (i / 2) * 26;
            final String action = a[0];
            boolean danger = action.equals("kick") || action.equals("ban");
            btn(ms, "fa-act" + action, a[1], a[2], bx, by, bw, 22, danger ? FUi.DANGER : FUi.SOFT, true, mx, my, () -> doAct(action));
            i++;
        }
        int rows = (i + 1) / 2;
        int fy = ay + rows * 26 + 4;
        float half = (rw - 8) / 2f;
        float fx = rx;
        if (has("job_view")) {
            btn(ms, "fa-pjob", "Job și grad", "flag", fx, fy, half, 22, FUi.GHOST, true, mx, my, () -> {
                String u = selUuid;
                go(S_JOBS);
                jobPlayer = u;
                FAdminClient.jobProgress = null;
                FAdminClient.ask("job_progress", "uuid", u);
            });
            fx += half + 8;
        }
        if (has("econ_view")) {
            btn(ms, "fa-pecon", "Economie", "star", fx, fy, half, 22, FUi.GHOST, true, mx, my, () -> {
                String u = selUuid;
                go(S_ECON);
                econUuid = u;
                econMode = 0;
                FAdminClient.econ = null;
                FAdminClient.ask("econ", "uuid", u);
            });
        }
        fy += 28;
        btn(ms, "fa-pclassic", "Fișă completă (note, IP, loguri)", "eye", rx, fy, rw, 22, FUi.GHOST, true, mx, my, () -> FAdminClient.ask("classic", "uuid", selUuid));
        fy += 30;
        t(ms, "Durată ban / mute", rx, fy + 6, 6.8f, FTheme.FAINT);
        int dx = rx;
        for (int k = 0; k < DUR_L.length; k++) {
            final int kk = k;
            chip(ms, "fa-dur" + k, DUR_L[k], dx, fy + 10, 16, durIdx == k, mx, my, () -> durIdx = kk);
            dx += chipW(DUR_L[k], 16);
        }
        drawField(ms, reason, rx, fy + 32, rw, 20, mx, my);
    }

    private void doAct(String action) {
        if (selUuid == null) return;
        FAdminClient.ask("act", "uuid", selUuid, "action", action, "reason", reason.getValue().trim(), "minutes", DUR[durIdx]);
        refreshAt = System.currentTimeMillis() + 900;
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
        float[] col = {0, 0.1f, 0.34f, 0.52f, 0.78f, 0.9f};
        String[] head = {"ID", "MODEL", "NUMĂR", "PROPRIETAR", "STARE", "KM"};
        for (int i = 0; i < head.length; i++) FTheme.caption(ms, head[i], x0 + 10 + col[i] * (cw - 20), hy + 8, FTheme.FAINT);
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            Gfx.rrect(ms, x0, ry, cw, 18, 6f, sel ? FTheme.alpha(FTheme.ACCENT, 50) : FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
            float w = cw - 20;
            float rx = x0 + 10;
            t(ms, "#" + id, rx, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            t(ms, clip(s(r, "model"), (col[2] - col[1]) * w - 6, 7.8f, false), rx + col[1] * w, mid(ry, 18, 7.8f), 7.8f, FTheme.TEXT);
            t(ms, s(r, "plate"), rx + col[2] * w, mid(ry, 18, 7.6f), 7.6f, FTheme.TEXT);
            t(ms, clip(s(r, "owner"), (col[4] - col[3]) * w - 6, 7.4f, false), rx + col[3] * w, mid(ry, 18, 7.4f), 7.4f, FTheme.MUTED);
            pill(ms, statusLabel(s(r, "status")), rx + col[4] * w - 2, ry + 3, statusColor(s(r, "status")));
            t(ms, "—", rx + col[5] * w, mid(ry, 18, 7.4f), 7.4f, FTheme.FAINT);
            hit(x0, ry, cw, 18, () -> {
                vehSel = vehSel == id ? -1 : id;
                reason.setValue("");
            });
            ry += 20;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 9, mx, my,
            pg -> FAdminClient.ask("vehicles", "q", s(FAdminClient.vehicles, "q"), "page", pg));
        if (vehSel >= 0) {
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
        FAdminClient.ask("veh_act", "id", vehSel, "action", action, "reason", reason.getValue().trim());
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
                FUi.loading(ms, x0 + cw / 2f, top + 80, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            FUi.loading(ms, x0 + cw / 2f, top + 90, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            btn(ms, "fa-eapply", "Aplică", "check", x0 + 10, oy + 126, lw - 20, 22, FUi.PRIMARY, true, mx, my, () ->
                FAdminClient.ask("econ_op", "uuid", econUuid, "account", econAcc == 0 ? "CASH" : "BANK", "op", econOp == 0 ? "add" : "remove",
                    "amount", parse(amount.getValue()), "reason", reason.getValue().trim()));
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
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            Gfx.rrect(ms, x0 + 4, ry, lw - 8, 20, 6f, sel ? FTheme.alpha(FTheme.ACCENT, 50) : FTheme.alpha(0xFFFFFFFF, (int) (16 * h)));
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
            FUi.loading(ms, rx + rw / 2f, y + 100, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            t(ms, String.valueOf((int) n(r, "level")), rx + 10 + col[1] * w, mid(ry, 18, 7.6f), 7.6f, FTheme.ACCENT);
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
            FUi.loading(ms, x0 + cw / 2f, y + 100, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            tr(ms, "Nivel " + (int) n(r, "level"), x0 + lw - 16, ry + 11, 7.4f, FTheme.ACCENT);
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
            FUi.loading(ms, x0 + cw / 2f, hy + 80, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            t(ms, clip(s(r, "action"), (col[3] - col[2]) * w - 4, 7f, false), rx + col[2] * w, mid(ry, 16, 7f), 7f, FTheme.ACCENT);
            t(ms, clip(s(r, "target"), (col[4] - col[3]) * w - 4, 7f, false), rx + col[3] * w, mid(ry, 16, 7f), 7f, FTheme.MUTED);
            String ch = s(r, "old").isEmpty() && s(r, "new").isEmpty() ? "" : s(r, "old") + " → " + s(r, "new");
            t(ms, clip(ch, (col[5] - col[4]) * w - 4, 6.8f, false), rx + col[4] * w, mid(ry, 16, 6.8f), 6.8f, FTheme.SKY);
            t(ms, clip(s(r, "reason"), (1 - col[5]) * w - 2, 6.8f, false), rx + col[5] * w, mid(ry, 16, 6.8f), 6.8f, FTheme.MUTED);
            ry += 17;
        }
        pager(ms, x0 + cw, y + bh - 20, (int) n(d, "page"), (int) n(d, "total"), 14, mx, my,
            pg -> FAdminClient.ask("logs", "q", s(FAdminClient.logs, "q"), "page", pg));
    }

    // ------------------------------------------------------------------ sistem

    private void drawSystem(GuiGraphics ms, int y, int mx, int my) {
        JsonObject d = FAdminClient.system;
        if (d == null) {
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
            return;
        }
        float gap = 8f;
        float w = (cw - gap * 3) / 4f;
        double tps = n(d, "tps");
        statCard(ms, x0, y, w, "TPS", tps < 0 ? "—" : String.format(Locale.US, "%.1f", tps), "ținta: 20", tps >= 18 || tps < 0 ? FTheme.OK : FTheme.WARN);
        double ram = n(d, "ramMax") <= 0 ? 0 : n(d, "ramUsed") / n(d, "ramMax");
        statCard(ms, x0 + (w + gap), y, w, "RAM", (int) n(d, "ramUsed") + " MB", "din " + (int) n(d, "ramMax") + " MB", ram > 0.85 ? FTheme.WARN : FTheme.SKY);
        statCard(ms, x0 + (w + gap) * 2, y, w, "ECONOMIE", s(d, "economy").startsWith("Vault") ? "Vault" : "Intern", s(d, "economy"), FTheme.TEXT);
        statCard(ms, x0 + (w + gap) * 3, y, w, "PLUGIN", "v" + s(d, "version"), b(d, "luckperms") ? "LuckPerms activ" : "fără LuckPerms", b(d, "luckperms") ? FTheme.OK : FTheme.WARN);
        int my0 = y + 62;
        card(ms, x0, my0, cw, 54);
        FTheme.caption(ms, "MODULE PORNITE", x0 + 10, my0 + 14, FTheme.MUTED);
        float mxp = x0 + 10;
        float myp = my0 + 20;
        for (JsonElement e : d.has("modules") && d.get("modules").isJsonArray() ? d.getAsJsonArray("modules") : new JsonArray()) {
            String m = e.getAsString();
            float cwid = Gfx.width(m, 6.6f, true) + 12;
            if (mxp + cwid > x0 + cw - 10) {
                mxp = x0 + 10;
                myp += 14;
            }
            if (myp + 12 > my0 + 54) break;
            Gfx.rrect(ms, mxp, myp, cwid, 12, 6f, FTheme.PANEL);
            Gfx.text(ms, m, mxp + 6, myp + 8.6f, 6.6f, FTheme.MUTED, 0, true, 0f);
            mxp += cwid + 4;
        }
        int ey = my0 + 62;
        card(ms, x0, ey, cw, y + bh - ey - 4);
        FTheme.caption(ms, "ERORI ȘI AVERTISMENTE RECENTE", x0 + 10, ey + 14, FTheme.MUTED);
        int i = 0;
        for (JsonObject r : arr(d, "errors")) {
            float ry = ey + 22 + i * 14;
            if (ry + 12 > y + bh - 8) break;
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
            FUi.loading(ms, x0 + cw / 2f, y + 90, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            FUi.loading(ms, x0 + cw / 2f, top + 70, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
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
            Gfx.rrect(ms, x0, ry, cw, 18, 6f, sel ? FTheme.alpha(FTheme.ACCENT, 50) : FTheme.mix(FTheme.BG2, FTheme.PANEL_HI, h));
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
            default: return id;
        }
    }

    private void drawPerm(GuiGraphics ms, int y, int mx, int my) {
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
            tx(ms, s(l, "label"), x0 + 14, ry + 12, 8.4f, mine ? FTheme.ACCENT : FTheme.TEXT);
            if (mine) pill(ms, "TU", x0 + lw - 36, ry + 3, FTheme.ACCENT);
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
            FUi.loading(ms, rx + rw / 2f, y + 60, System.currentTimeMillis() / 1000f, FTheme.ACCENT);
        } else {
            int k = 0;
            for (JsonObject r : arr(pd, "staff")) {
                float ry = y + 22 + k * 16;
                if (ry + 14 > y + listH - 4) break;
                t(ms, clip(s(r, "name"), rw - 96, 7.6f, false), rx + 10, mid(ry, 14, 7.6f), 7.6f, FTheme.TEXT);
                pill(ms, s(r, "label") + (b(r, "op") ? " · OP" : ""), rx + rw - 10 - (Gfx.width(s(r, "label") + (b(r, "op") ? " · OP" : ""), 6.6f, true) + 10), ry + 1, FTheme.ACCENT);
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
                FAdminClient.ask("perm_set", "name", pname.getValue().trim(), "level", ids[Math.max(0, Math.min(6, permLevel))], "reason", reason.getValue().trim());
            });
        } else {
            t(ms, "Doar Owner poate modifica nivelurile.", rx + 10, y + listH + 14, 7f, FTheme.FAINT);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = delta > 0 ? -1 : 1;
        if (sec == S_EDIT) cfgScroll += d;
        else if (sec == S_JOBS) jobScroll += d;
        else return super.mouseScrolled(mx, my, delta);
        return true;
    }
}
