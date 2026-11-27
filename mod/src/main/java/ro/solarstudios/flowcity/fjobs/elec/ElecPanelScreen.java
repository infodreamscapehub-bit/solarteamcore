package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.fjobs.FJobsClient;

/**
 * Panoul jobului Electrician: angajat (nume, nivel profesional, XP), butoane INCEPE TURA / INTERVENTII DISPONIBILE / ECHIPAMENT /
 * STATISTICI / TERMINA TURA, interventia activa, istoric, statistici personale si (pentru admini) administrare. Datele vin de la server.
 */
public final class ElecPanelScreen extends ElecScreen {
    private static int tab;
    private int scroll;
    private long nextRefresh;
    private long refreshAt;

    public ElecPanelScreen() {
        super("Electrician");
        nextRefresh = System.currentTimeMillis() + 2500;
    }

    private JsonObject el() {
        return obj(FJobsClient.panelData(), "el");
    }

    private boolean inShift() {
        JsonObject p = FJobsClient.panelData();
        return p != null && bool(p, "inShift");
    }

    private void refreshSoon(long ms) {
        refreshAt = System.currentTimeMillis() + ms;
    }

    @Override
    public void removed() {
        FJobsClient.req("panel_close", new JsonObject());
    }

    @Override
    protected void content(GuiGraphics ms, float t) {
        long now = System.currentTimeMillis();
        if (refreshAt > 0 && now >= refreshAt) {
            refreshAt = 0;
            FJobsClient.req("panel", new JsonObject());
        }
        if (now >= nextRefresh) {
            nextRefresh = now + 2500;
            FJobsClient.req("panel", new JsonObject());
        }
        JsonObject p = FJobsClient.panelData();
        if (p == null) {
            Gfx.text(ms, "Se încarcă panoul...", W / 2f, H / 2f, 9, MUT, 1);
            return;
        }
        JsonObject el = el();
        head(ms, "ELECTRICIAN", "PANOU PROFESIONAL  ·  REPARAȚII ELECTRICE", true);
        Gfx.text(ms, "$ " + Ui.money(num(el, "cash")), W - 150, 24, 8, YEL, 2, true, 0.5f);
        btn(ms, "JOB CENTER", W - 142, 12, 84, 18, true, false, FJobsClient::open);
        if (!bool(p, "ready")) {
            box(ms, 14, 44, W - 28, 150, "JOB ÎN PREGĂTIRE");
            wrap(ms, str(p, "note").isEmpty() ? "Adminul trebuie să seteze punctele de intervenție." : str(p, "note"), 30, 84, W - 60, 8, TXT, 4);
            return;
        }
        left(ms, el);
        switch (tab) {
            case 1:
                offers(ms, el);
                break;
            case 2:
                equip(ms, el);
                break;
            case 3:
                stats(ms, el);
                break;
            case 4:
                admin(ms, el);
                break;
            default:
                home(ms, el);
        }
    }

    // ------------------------------------------------------------------ coloana stanga

    private void left(GuiGraphics ms, JsonObject el) {
        boolean shift = inShift();
        JsonObject rk = obj(el, "rank");
        box(ms, 14, 44, 176, 112, null);
        boolean hot = over(14, 44, 176, 112);
        hitL(14, 44, 176, 112, () -> {
            tab = 0;
            scroll = 0;
        });
        Gfx.disc(ms, 44, 78, 21, 0xFF05070A);
        Gfx.disc(ms, 44, 78, 18, 0xFF2A2208);
        bolt(ms, 44, 78, 11, YEL);
        Gfx.text(ms, clip(str(el, "name"), 110, 9), 72, 72, 9, TXT, 0, true, 0.4f);
        Gfx.text(ms, clip(str(rk, "name").toUpperCase(), 110, 6.2f), 72, 84, 6.2f, YEL, 0, true, 0.5f);
        Gfx.text(ms, "NIVEL " + (int) num(FJobsClient.panelData(), "level"), 72, 95, 5.8f, MUT, 0, true, 0.5f);
        double need = num(el, "xpNeed");
        double xp = num(el, "xp");
        Gfx.text(ms, "XP", 24, 120, 5.6f, MUT, 0, true, 0.6f);
        Gfx.text(ms, need > 0 ? (long) xp + " / " + (long) need : "NIVEL MAXIM", 182, 120, 5.8f, TXT, 2, true, 0.3f);
        Gfx.bar(ms, 24, 124, 156, 6, need > 0 ? (float) (xp / need) : 1f, 0xFF05070A, YEL);
        if (rk.has("next")) Gfx.text(ms, "URMĂTORUL: " + str(rk, "next") + " (niv. " + (int) num(rk, "nextLevel") + ")", 24, 146, 5.4f, FAINT, 0);
        else Gfx.text(ms, "RANG MAXIM", 24, 146, 5.4f, GOOD, 0, true, 0.4f);
        Gfx.rrect(ms, 150, 52, 34, 12, 3, shift ? 0xFF0E3A20 : 0xFF2C343F);
        Gfx.text(ms, shift ? "TURĂ" : "OFF", 167, 61, 5.6f, shift ? GOOD : MUT, 1, true, 0.4f);
        // butoane
        float y = 162;
        btn(ms, "ÎNCEPE TURĂ", 14, y, 176, 24, !shift, true, () -> {
            FJobsClient.req("start", new JsonObject());
            refreshSoon(500);
        });
        y += 29;
        navBtn(ms, "INTERVENȚII DISPONIBILE", 14, y, 1, shift);
        y += 29;
        navBtn(ms, "ECHIPAMENT", 14, y, 2, true);
        y += 29;
        navBtn(ms, "STATISTICI", 14, y, 3, true);
        y += 29;
        btnC(ms, "TERMINĂ TURĂ", 14, y, 176, 24, shift, 0xFF5A1E18, TXT, () -> {
            FJobsClient.req("end", new JsonObject());
            refreshSoon(500);
        });
        if (bool(el, "admin")) {
            y += 29;
            navBtn(ms, "ADMINISTRARE", 14, y, 4, true);
        }
    }

    private void navBtn(GuiGraphics ms, String label, float x, float y, int idx, boolean enabled) {
        boolean sel = tab == idx;
        btnC(ms, label, x, y, 176, 24, enabled, sel ? 0xFF3A2E08 : PANEL2, sel ? YEL : TXT, () -> {
            tab = idx;
            scroll = 0;
        });
        if (sel) Gfx.rrect(ms, x, y + 4, 3, 16, 1, YEL);
    }

    // ------------------------------------------------------------------ prezentare: interventie activa + istoric

    private void home(GuiGraphics ms, JsonObject el) {
        JsonObject run = obj(el, "run");
        boolean has = el.has("run");
        box(ms, 198, 44, 428, 122, "INTERVENȚIE ACTIVĂ");
        if (has) {
            Gfx.text(ms, str(run, "name"), 212, 70, 11, TXT, 0, true, 0.5f);
            Gfx.text(ms, str(run, "loc") + (bool(run, "arrived") ? "  ·  LA LOCAȚIE" : num(run, "dist") >= 0 ? "  ·  " + (int) num(run, "dist") + " m" : ""), 212, 82, 6.4f, MUT, 0);
            diffMeter(ms, 556, 68, (int) num(run, "diff"), 10);
            Gfx.text(ms, "RECOMPENSĂ ~" + Ui.money(num(run, "reward")) + "$  ·  " + (long) num(run, "xp") + " XP  ·  ESTIMAT " + mmss(num(run, "est")), 212, 96, 6.2f, YEL, 0, true, 0.3f);
            int pct = (int) num(run, "percent");
            Gfx.bar(ms, 212, 104, 300, 7, pct / 100f, 0xFF05070A, YEL);
            Gfx.text(ms, pct + "%", 520, 111, 7, TXT, 0, true, 0.3f);
            JsonArray sg = arr(run, "stages");
            for (int i = 0; i < sg.size(); i++) {
                JsonObject s = sg.get(i).getAsJsonObject();
                boolean d = "done".equals(str(s, "st"));
                Gfx.rrect(ms, 212 + i * 98, 118, 94, 14, 3, d ? 0xFF0E3A20 : 0xFF161B22);
                Gfx.text(ms, clip(str(s, "name"), 88, 5.2f), 212 + i * 98 + 47, 128, 5.2f, d ? GOOD : MUT, 1, true, 0.2f);
            }
            btn(ms, "NAVIGHEAZĂ (GPS)", 212, 138, 130, 20, true, false, () -> op("el_gps"));
            btn(ms, "DESCHIDE PANOUL", 348, 138, 130, 20, bool(run, "near"), true, () -> {
                op("el_open");
                refreshSoon(400);
            });
            btnC(ms, "RENUNȚĂ", 484, 138, 130, 20, true, 0xFF3A1612, TXT, () -> {
                op("el_abandon");
                refreshSoon(300);
            });
        } else {
            Gfx.text(ms, inShift() ? "Nicio intervenție activă." : "Pornește tura ca să primești intervenții.", 212, 80, 9, TXT, 0, true, 0.4f);
            wrap(ms, "Alege o intervenție din lista disponibilă, mergi la locație, deschide panoul electric (click dreapta pe punct) și rezolvă etapele.", 212, 96, 396, 6.4f, MUT, 3);
            btn(ms, "VEZI INTERVENȚII DISPONIBILE", 212, 134, 200, 22, inShift(), true, () -> tab = 1);
        }
        box(ms, 198, 172, 428, 180, "ISTORIC INTERVENȚII");
        JsonArray h = arr(el, "history");
        if (h.size() == 0) Gfx.text(ms, "Nicio intervenție încă.", 212, 210, 7, FAINT, 0);
        for (int i = 0; i < h.size() && i < 8; i++) {
            JsonObject e = h.get(i).getAsJsonObject();
            float ry = 188 + i * 20;
            boolean ok = bool(e, "ok");
            Gfx.rrect(ms, 208, ry, 408, 18, 3, i % 2 == 0 ? 0xFF141920 : 0xFF11151B);
            Gfx.disc(ms, 220, ry + 9, 4, ok ? GOOD : BAD);
            Gfx.text(ms, clip(str(e, "name"), 150, 6.2f), 230, ry + 12, 6.2f, TXT, 0, true, 0.2f);
            Gfx.text(ms, clipN(str(e, "loc"), 110, 5.6f), 384, ry + 12, 5.6f, MUT, 0);
            Gfx.text(ms, ok ? "+" + Ui.money(num(e, "pay")) + "$" : "ESUAT", 530, ry + 12, 6.2f, ok ? YEL : BAD, 2, true, 0.2f);
            Gfx.text(ms, ok ? Math.round(num(e, "q") * 100) + "%" : mmss(num(e, "sec")), 608, ry + 12, 6.2f, ok ? GOOD : MUT, 2, true, 0.2f);
        }
    }

    // ------------------------------------------------------------------ interventii disponibile

    private void offers(GuiGraphics ms, JsonObject el) {
        box(ms, 198, 44, 428, 308, "INTERVENȚII DISPONIBILE");
        boolean shift = inShift();
        boolean busy = el.has("run");
        btn(ms, "REÎMPROSPĂTEAZĂ", 500, 50, 118, 16, shift && !busy, false, () -> {
            op("el_reroll");
            refreshSoon(300);
        });
        JsonArray of = arr(el, "offers");
        if (!shift) {
            Gfx.text(ms, "Pornește tura ca să primești intervenții.", 412, 190, 8, MUT, 1);
            return;
        }
        if (of.size() == 0) {
            Gfx.text(ms, "Se caută intervenții... (adminul trebuie să fi setat puncte)", 412, 190, 7.4f, MUT, 1);
            return;
        }
        int rows = 5;
        scroll = Math.max(0, Math.min(Math.max(0, of.size() - rows), scroll));
        double age = (System.currentTimeMillis() - FJobsClient.panelAt()) / 1000.0;
        for (int i = 0; i < rows && i + scroll < of.size(); i++) {
            JsonObject o = of.get(i + scroll).getAsJsonObject();
            float ry = 70 + i * 55;
            float rx = 206;
            float rw = 412;
            boolean why = !str(o, "why").isEmpty();
            int d = (int) num(o, "diff");
            Gfx.rrect(ms, rx, ry, rw, 51, 5, LINE);
            Gfx.rrect(ms, rx + 1, ry + 1, rw - 2, 49, 4, 0xFF161B22, 0xFF10141A);
            Gfx.rrect(ms, rx + 1, ry + 6, 4, 39, 2, d >= 4 ? ORG : YEL);
            Gfx.text(ms, clip(str(o, "name"), 220, 8.4f), rx + 14, ry + 15, 8.4f, TXT, 0, true, 0.4f);
            double dist = num(o, "dist");
            Gfx.text(ms, clipN(str(o, "loc"), 160, 6f) + (dist >= 0 ? "  ·  " + (int) dist + " m" : ""), rx + 14, ry + 26, 6f, MUT, 0);
            if (why) Gfx.text(ms, clipN(str(o, "why"), 250, 5.4f), rx + 14, ry + 44, 5.4f, BAD, 0);
            else {
                JsonArray gs = arr(o, "stages");
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < gs.size(); k++) sb.append(k > 0 ? " › " : "").append(gs.get(k).getAsString());
                Gfx.text(ms, clipN(sb.toString(), 250, 5.4f), rx + 14, ry + 44, 5.4f, FAINT, 0);
            }
            diffMeter(ms, rx + 14, ry + 35, d, 8);
            Gfx.text(ms, "$" + Ui.money(num(o, "reward")), rx + 262, ry + 20, 10, YEL, 2, true, 0.3f);
            Gfx.text(ms, (long) num(o, "xp") + " XP  ·  ~" + mmss(num(o, "sec")), rx + 262, ry + 32, 5.8f, MUT, 2, true, 0.2f);
            Gfx.text(ms, mmss(Math.max(0, num(o, "left") - age)), rx + 262, ry + 44, 5.6f, FAINT, 2, true, 0.2f);
            String id = str(o, "id");
            btn(ms, why ? "LIPSĂ ECHIP." : "ACCEPTĂ", rx + rw - 134, ry + 12, 126, 26, !busy && !why, true, () -> {
                op("el_accept", "id", id);
                refreshSoon(300);
            });
        }
        if (of.size() > rows) Gfx.text(ms, "Derulează cu rotița (" + (scroll + 1) + "-" + Math.min(of.size(), scroll + rows) + " din " + of.size() + ")", 412, 346, 5.6f, FAINT, 1);
    }

    @Override
    protected void onScroll(double delta) {
        scroll += delta > 0 ? -1 : 1;
        if (scroll < 0) scroll = 0;
    }

    // ------------------------------------------------------------------ echipament

    private void equip(GuiGraphics ms, JsonObject el) {
        box(ms, 198, 44, 428, 308, "ECHIPAMENT");
        Gfx.text(ms, "Echipamentul e real: stă în inventar și e folosit în intervenții.", 212, 72, 6.4f, MUT, 0);
        JsonArray ea = arr(el, "equip");
        for (int i = 0; i < ea.size(); i++) {
            JsonObject e = ea.get(i).getAsJsonObject();
            float cx = 208 + (i % 3) * 138;
            float cy = 84 + (i / 3) * 132;
            float cw = 132;
            float ch = 124;
            boolean locked = bool(e, "locked");
            int have = (int) num(e, "have");
            Gfx.rrect(ms, cx, cy, cw, ch, 6, have > 0 ? YEL : LINE);
            Gfx.rrect(ms, cx + 1, cy + 1, cw - 2, ch - 2, 5, 0xFF161B22, 0xFF0F1318);
            icon(ms, str(e, "id"), cx + cw / 2f, cy + 26, locked);
            Gfx.text(ms, clip(str(e, "name"), cw - 12, 7.2f), cx + cw / 2f, cy + 54, 7.2f, locked ? FAINT : TXT, 1, true, 0.3f);
            wrap(ms, str(e, "desc"), cx + 8, cy + 64, cw - 16, 5.2f, MUT, 3);
            Gfx.text(ms, "ÎN INVENTAR: " + have, cx + 8, cy + 98, 5.8f, have > 0 ? GOOD : FAINT, 0, true, 0.4f);
            boolean cons = bool(e, "consumable");
            int pack = (int) num(e, "pack");
            String id = str(e, "id");
            if (locked) {
                btnC(ms, "RANG: " + str(e, "rankName"), cx + 8, cy + 104, cw - 16, 16, false, PANEL2, TXT, () -> {});
            } else {
                btn(ms, (cons ? "+" + pack + "  " : "") + "$" + Ui.money(num(e, "price")), cx + 8, cy + 104, cw - 16, 16, have < (int) num(e, "max"), true, () -> {
                    op("el_buy", "id", id, "qty", "1");
                    refreshSoon(300);
                });
            }
        }
    }

    /** Pictograme vectoriale simple pentru echipament. */
    private void icon(GuiGraphics ms, String id, float cx, float cy, boolean dim) {
        int c = dim ? FAINT : YEL;
        switch (id) {
            case "trusa":
                Gfx.rrect(ms, cx - 16, cy - 8, 32, 20, 3, c);
                Gfx.rrect(ms, cx - 6, cy - 13, 12, 6, 2, c);
                Gfx.rrect(ms, cx - 3, cy - 1, 6, 5, 1, 0xFF0F1318);
                break;
            case "multimetru":
                Gfx.rrect(ms, cx - 11, cy - 14, 22, 28, 3, c);
                Gfx.rrect(ms, cx - 8, cy - 11, 16, 9, 1, 0xFF0F1318);
                Gfx.disc(ms, cx, cy + 6, 5, 0xFF0F1318);
                Gfx.line(ms, cx, cy + 6, cx + 3, cy + 3, 1.4f, c);
                break;
            case "cleste":
                Gfx.line(ms, cx - 12, cy + 12, cx + 6, cy - 8, 4, c);
                Gfx.line(ms, cx + 12, cy + 12, cx - 6, cy - 8, 4, c);
                Gfx.disc(ms, cx, cy + 1, 3, 0xFF0F1318);
                break;
            case "cablu":
                for (int i = 0; i < 3; i++) Gfx.ring(ms, cx, cy, 6 + i * 3.5f, 8.4f + i * 3.5f, c, c);
                break;
            case "siguranta":
                Gfx.rrect(ms, cx - 6, cy - 14, 12, 28, 3, dim ? FAINT : 0xFFB8D6E8);
                Gfx.rrect(ms, cx - 7, cy - 15, 14, 5, 2, c);
                Gfx.rrect(ms, cx - 7, cy + 10, 14, 5, 2, c);
                Gfx.line(ms, cx, cy - 8, cx, cy + 8, 1.2f, ORG);
                break;
            default:
                Gfx.disc(ms, cx, cy - 4, 11, c);
                Gfx.rrect(ms, cx - 11, cy - 4, 22, 14, 3, c);
                Gfx.rrect(ms, cx - 7, cy, 14, 5, 1, 0xFF0F1318);
        }
    }

    // ------------------------------------------------------------------ statistici

    private void stats(GuiGraphics ms, JsonObject el) {
        JsonObject s = obj(el, "stats");
        box(ms, 198, 44, 428, 308, "STATISTICI PERSONALE");
        int done = (int) num(s, "done");
        int failed = (int) num(s, "failed");
        String[][] cells = {{"INTERVENȚII REUȘITE", String.valueOf(done)}, {"EȘUATE", String.valueOf(failed)},
            {"RATA DE SUCCES", done + failed == 0 ? "—" : Math.round(100.0 * done / (done + failed)) + "%"},
            {"BANI CÂȘTIGAȚI", "$" + Ui.money(num(s, "money"))}, {"XP CÂȘTIGAT", String.valueOf((long) num(s, "xp"))},
            {"TIMP LUCRAT", Math.round(num(s, "seconds") / 60.0) + " min"}, {"CALITATE MEDIE", Math.round(num(s, "avgQ") * 100) + "%"},
            {"CEA MAI BUNĂ SERIE", String.valueOf((int) num(s, "bestStreak"))}};
        for (int i = 0; i < cells.length; i++) {
            float cx = 208 + (i % 4) * 103;
            float cy = 72 + (i / 4) * 46;
            Gfx.rrect(ms, cx, cy, 98, 40, 5, 0xFF161B22);
            Gfx.rrect(ms, cx, cy + 36, 98, 4, 2, i % 2 == 0 ? YEL : ORG);
            Gfx.text(ms, cells[i][1], cx + 8, cy + 20, 12, TXT, 0, true, 0.3f);
            Gfx.text(ms, cells[i][0], cx + 8, cy + 31, 5f, MUT, 0, true, 0.4f);
        }
        Gfx.text(ms, "ÎN TURA CURENTĂ: " + (int) num(s, "doneShift") + " reușite · " + (int) num(s, "failShift") + " eșuate · $" + Ui.money(num(s, "earnedShift")), 208, 174, 6.2f, YEL, 0, true, 0.4f);
        // pe tipuri
        JsonArray bt = arr(s, "byType");
        int max = 1;
        for (int i = 0; i < bt.size(); i++) max = Math.max(max, (int) num(bt.get(i).getAsJsonObject(), "n"));
        Gfx.text(ms, "PE TIPURI", 208, 192, 5.8f, MUT, 0, true, 0.8f);
        for (int i = 0; i < bt.size() && i < 8; i++) {
            JsonObject b = bt.get(i).getAsJsonObject();
            float by = 198 + i * 12;
            Gfx.text(ms, clip(str(b, "name"), 120, 5.4f), 208, by + 8, 5.4f, TXT, 0, true, 0.1f);
            Gfx.bar(ms, 332, by + 2, 90, 6, (float) num(b, "n") / max, 0xFF05070A, YEL);
            Gfx.text(ms, String.valueOf((int) num(b, "n")), 428, by + 8, 5.6f, MUT, 0);
        }
        // trepte de rang
        Gfx.text(ms, "NIVELURI PROFESIONALE", 452, 192, 5.8f, MUT, 0, true, 0.8f);
        JsonArray ra = arr(el, "ranks");
        int cur = (int) num(obj(el, "rank"), "idx");
        for (int i = 0; i < ra.size() && i < 6; i++) {
            JsonObject r = ra.get(i).getAsJsonObject();
            float ry = 198 + i * 24;
            boolean me = i == cur;
            boolean got = i <= cur;
            Gfx.rrect(ms, 452, ry, 166, 21, 4, me ? 0xFF3A2E08 : 0xFF161B22);
            Gfx.disc(ms, 463, ry + 10, 4, got ? YEL : 0xFF2C343F);
            Gfx.text(ms, clip(str(r, "name"), 100, 6f), 472, ry + 9, 6f, got ? TXT : MUT, 0, true, 0.2f);
            Gfx.text(ms, "niv. " + (int) num(r, "minLevel") + " · dif " + (int) num(r, "maxDiff"), 472, ry + 18, 5f, FAINT, 0);
        }
    }

    // ------------------------------------------------------------------ administrare

    private void admin(GuiGraphics ms, JsonObject el) {
        box(ms, 198, 44, 428, 308, "ADMINISTRARE ELECTRICIAN");
        JsonArray ta = arr(el, "adm");
        JsonArray ea = arr(el, "equip");
        String jobId = str(FJobsClient.panelData(), "id");
        int total = ta.size() + ea.size() + 2;
        int rows = 12;
        scroll = Math.max(0, Math.min(Math.max(0, total - rows), scroll));
        for (int r = 0; r < rows && r + scroll < total; r++) {
            int idx = r + scroll;
            float ry = 68 + r * 23;
            Gfx.rrect(ms, 206, ry, 412, 21, 3, r % 2 == 0 ? 0xFF141920 : 0xFF11151B);
            if (idx == 0) {
                Gfx.text(ms, "TIPURI DE INTERVENȚII (recompensă de bază, puncte pe tip)", 214, ry + 14, 6f, YEL, 0, true, 0.5f);
            } else if (idx <= ta.size()) {
                JsonObject t = ta.get(idx - 1).getAsJsonObject();
                String id = str(t, "id");
                boolean on = bool(t, "enabled");
                btnC(ms, on ? "ON" : "OFF", 210, ry + 2, 30, 17, true, on ? 0xFF0E3A20 : 0xFF3A1612, on ? GOOD : BAD, () -> {
                    admOp("toggle", id, 0);
                });
                Gfx.text(ms, clip(str(t, "name"), 112, 6f), 246, ry + 14, 6f, TXT, 0, true, 0.2f);
                btn(ms, "−", 362, ry + 2, 16, 17, true, false, () -> admOp("reward", id, Math.max(0, num(t, "reward") - 5)));
                Gfx.text(ms, "$" + Ui.money(num(t, "reward")), 398, ry + 14, 6.2f, YEL, 1, true, 0.2f);
                btn(ms, "+", 418, ry + 2, 16, 17, true, false, () -> admOp("reward", id, num(t, "reward") + 5));
                Gfx.text(ms, (int) num(t, "points") + " pct", 456, ry + 14, 5.8f, num(t, "points") > 0 ? MUT : BAD, 0);
                btn(ms, "+ AICI", 488, ry + 2, 62, 17, true, true, () -> admPoint(jobId, id, "add"));
                btn(ms, "− ULTIMUL", 554, ry + 2, 60, 17, num(t, "points") > 0, false, () -> admPoint(jobId, id, "pop"));
            } else if (idx == ta.size() + 1) {
                Gfx.text(ms, "ECHIPAMENT (prețuri) · puncte generale: " + (int) num(el, "admGeneral"), 214, ry + 14, 6f, YEL, 0, true, 0.5f);
                btn(ms, "+ PUNCT GENERAL", 478, ry + 2, 82, 17, true, true, () -> admPoint(jobId, "panel", "add"));
                btn(ms, "− ULTIMUL", 564, ry + 2, 50, 17, num(el, "admGeneral") > 0, false, () -> admPoint(jobId, "panel", "pop"));
            } else {
                JsonObject e = ea.get(idx - ta.size() - 2).getAsJsonObject();
                String id = str(e, "id");
                Gfx.text(ms, clip(str(e, "name"), 160, 6f), 214, ry + 14, 6f, TXT, 0, true, 0.2f);
                btn(ms, "−", 392, ry + 2, 16, 17, true, false, () -> admOp("price", id, Math.max(0, num(e, "price") - 5)));
                Gfx.text(ms, "$" + Ui.money(num(e, "price")), 438, ry + 14, 6.2f, YEL, 1, true, 0.2f);
                btn(ms, "+", 468, ry + 2, 16, 17, true, false, () -> admOp("price", id, num(e, "price") + 5));
            }
        }
        Gfx.text(ms, "Punctele se pun unde stai (poziția ta). Tipurile fără puncte proprii folosesc punctele generale.", 412, 346, 5.4f, FAINT, 1);
    }

    private void admOp(String op, String id, double value) {
        JsonObject o = new JsonObject();
        o.addProperty("act", "el_adm");
        o.addProperty("op", op);
        o.addProperty("id", id);
        o.addProperty("value", value);
        FJobsClient.requestOp(o);
        refreshSoon(300);
    }

    private void admPoint(String jobId, String kind, String op) {
        JsonObject o = new JsonObject();
        o.addProperty("id", jobId);
        o.addProperty("kind", kind);
        o.addProperty("op", op);
        FJobsClient.req("adm_point", o);
        refreshSoon(500);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
