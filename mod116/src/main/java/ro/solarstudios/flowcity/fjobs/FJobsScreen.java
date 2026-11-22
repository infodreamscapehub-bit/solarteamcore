package ro.solarstudios.flowcity.fjobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * FlowCity Job Center: ecran complet, tema inchisa violet/albastru, bara laterala (Acasa, Toate joburile, Joburile mele, Joburi disponibile,
 * Joburi premium, Istoric activitate, Statistici personale), carduri cu ilustratii vectoriale, fereastra de detalii si administrare
 * (doar pentru cei cu flowcity.fjobs.admin; serverul re-verifica). Clientul nu decide nimic: toate datele vin de la server (FJobsClient.data).
 * Layout propriu (design pe 640 x 360, scalat), inspirat doar ca structura de aplicatii de joburi, fara asset-uri copiate.
 */
public final class FJobsScreen extends FlowScreen {
    private static final int W = 640;
    private static final int H = 360;
    private static final int SIDE = 128;
    private static final int CX = SIDE + 14;           // inceputul zonei de continut
    private static final int CW = W - CX - 12;
    private static final int COLS = 3;
    private static final int GAP = 10;
    private static final int CARD_W = (CW - GAP * (COLS - 1)) / COLS;
    private static final int CARD_H = 128;
    private static final int TOP = 62;                 // sub antet
    private static final int VIEW_H = H - TOP - 10;

    private static final String[] NAV = {"Acasă", "Toate joburile", "Joburile mele", "Joburi disponibile", "Joburi premium", "Istoric activitate", "Statistici personale"};
    private static final int VIOLET = 0xFF8B5CF6;
    private static final int VIOLET_D = 0xFF5B35C9;
    private static final int BG_SIDE = 0xF00A0D1C;
    private static final int CARD = 0xFF141A30;
    private static final int CARD_HOT = 0xFF1B2342;

    private int page;
    private String detail = "";
    private boolean admin;
    private float scroll;
    private float scrollTarget;
    private float scale = 1f;
    private float offX;
    private float offY;
    private final long born = System.currentTimeMillis();

    public FJobsScreen() {
        super("Job Center", W, H);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    public void removed() {
        FJobsClient.closed();
    }

    // ------------------------------------------------------------------ date

    private static JsonObject data() {
        return FJobsClient.data;
    }

    private static JsonObject me() {
        JsonObject d = data();
        return d != null && d.has("me") && d.get("me").isJsonObject() ? d.getAsJsonObject("me") : new JsonObject();
    }

    private static List<JsonObject> jobs() {
        List<JsonObject> out = new ArrayList<>();
        JsonObject d = data();
        if (d == null || !d.has("jobs") || !d.get("jobs").isJsonArray()) return out;
        for (JsonElement e : d.getAsJsonArray("jobs")) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private static JsonObject jobById(String id) {
        for (JsonObject j : jobs()) if (id.equals(JsonClientModule.str(j, "id", ""))) return j;
        return null;
    }

    private static String s(JsonObject o, String k) {
        return JsonClientModule.str(o, k, "");
    }

    private static double n(JsonObject o, String k) {
        return JsonClientModule.num(o, k, 0);
    }

    private static boolean b(JsonObject o, String k) {
        return JsonClientModule.bool(o, k, false);
    }

    private List<JsonObject> pageJobs() {
        List<JsonObject> out = new ArrayList<>();
        String cur = s(me(), "job");
        for (JsonObject j : jobs()) {
            switch (page) {
                case 1:
                    out.add(j);
                    break;
                case 2:
                    if (s(j, "id").equals(cur) || n(j, "xp") > 0 || n(j, "level") > 0 || n(j, "shifts") > 0 || n(j, "earned") > 0) out.add(j);
                    break;
                case 3:
                    if (b(j, "canHire")) out.add(j);
                    break;
                case 4:
                    if (b(j, "premium")) out.add(j);
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    private static int groupColor(String group, String category) {
        if ("special".equals(category)) return 0xFFF472B6;
        switch (group) {
            case "Resurse": return 0xFF34D399;
            case "Transport": return 0xFF4DA3FF;
            case "Servicii": return 0xFFA78BFA;
            case "Urgente": return 0xFFF87171;
            case "Constructii": return 0xFFFBBF24;
            case "Educatie": return 0xFF2DD4BF;
            default: return VIOLET;
        }
    }

    private static String statusLabel(JsonObject j) {
        if (!b(j, "active")) return "DEZACTIVAT";
        switch (s(j, "status")) {
            case "READY": return "FUNCȚIONAL";
            case "EXTERNAL": return "SISTEM EXTERN";
            default: return "ÎN PREGĂTIRE";
        }
    }

    private static int statusColor(JsonObject j) {
        if (!b(j, "active")) return Sk.BAD;
        switch (s(j, "status")) {
            case "READY": return Sk.GOOD;
            case "EXTERNAL": return Sk.ACC;
            default: return Sk.WARN;
        }
    }

    // ------------------------------------------------------------------ randare

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float open = Math.min(1f, (System.currentTimeMillis() - born) / 280f);
        scale = Math.min(width / (float) W, height / (float) H);
        scale = Math.max(0.5f, Math.min(2.6f, scale));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;
        admin = data() != null && b(data(), "admin");
        scroll += (scrollTarget - scroll) * 0.35f;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        float l = -offX / scale;
        float t = -offY / scale;
        float r = (width - offX) / scale;
        float bt = (height - offY) / scale;
        Gfx.alpha = open;
        try {
            background(ms, l, t, r, bt);
            if (data() == null) {
                Gfx.text(ms, "Se încarcă Job Center...", W / 2f, H / 2f, 10, Sk.MUTED, 1, true, 0.5f);
                sidebar(ms, lmx, lmy, t, bt);
            } else {
                content(ms, lmx, lmy);
                sidebar(ms, lmx, lmy, t, bt);
                if (!detail.isEmpty()) detailModal(ms, lmx, lmy, l, t, r, bt);
            }
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void background(GuiGraphics ms, float l, float t, float r, float b) {
        Gfx.rrect(ms, l, t, r - l, b - t, 0, 0xFF0F1226, 0xFF070914);
        Gfx.scrim(ms, SIDE + 200, 0, 380, 190, 0x338B5CF6);
        Gfx.scrim(ms, W - 40, H, 300, 140, 0x224DA3FF);
        float tt = System.currentTimeMillis() / 1000f;
        for (int i = 0; i < 26; i++) {
            float x = l + ((i * 0.618f + 0.1f * (float) Math.sin(tt * 0.4f + i)) % 1f) * (r - l);
            float y = t + ((i * 0.37f + tt * (6f + (i % 5) * 3f) / (b - t)) % 1f) * (b - t);
            Gfx.disc(ms, x, y, 0.8f + (i % 3) * 0.5f, 0x22FFFFFF);
        }
    }

    private void sidebar(GuiGraphics ms, float lmx, float lmy, float t, float bt) {
        Gfx.rrect(ms, -offX / scale, t, SIDE + offX / scale, bt - t, 0, BG_SIDE);
        Gfx.rrect(ms, SIDE, t, 1, bt - t, 0, 0xFF1F2547);
        Gfx.text(ms, "FLOWCITY", 16, 30, 6.4f, Sk.MUTED, 0, false, 1.6f);
        Gfx.text(ms, "Job Center", 16, 48, 15, Sk.WHITE, 0, true, 0f);
        for (int i = 0; i < NAV.length; i++) {
            float y = 66 + i * 30;
            boolean sel = page == i;
            boolean hot = Sk.in(lmx, lmy, 8, y, SIDE - 16, 26);
            if (sel) {
                Gfx.rrect(ms, 8, y, SIDE - 16, 26, 7, 0xFF241B52, 0xFF1A1440);
                Gfx.rrect(ms, 8, y + 5, 3, 16, 1.5f, VIOLET);
            } else if (hot) {
                Gfx.rrect(ms, 8, y, SIDE - 16, 26, 7, 0xFF151A33);
            }
            navIcon(ms, i, 24, y + 13, sel ? VIOLET : (hot ? Sk.WHITE : Sk.MUTED));
            Gfx.text(ms, NAV[i], 38, y + 16.5f, 7.2f, sel ? Sk.WHITE : (hot ? Sk.WHITE : Sk.MUTED), 0);
            final int pg = i;
            hitL(8, y, SIDE - 16, 26, () -> {
                page = pg;
                scroll = 0;
                scrollTarget = 0;
                detail = "";
            });
        }
        boolean hc = Sk.in(lmx, lmy, 12, H - 34, SIDE - 24, 22);
        Gfx.rrect(ms, 12, H - 34, SIDE - 24, 22, 7, hc ? 0xFF2A1F5E : 0xFF171C38);
        Gfx.text(ms, "ÎNCHIDE", SIDE / 2f, H - 19, 7.2f, hc ? Sk.WHITE : Sk.MUTED, 1, true, 0.8f);
        hitL(12, H - 34, SIDE - 24, 22, this::onClose);
    }

    private void navIcon(GuiGraphics ms, int i, float cx, float cy, int c) {
        switch (i) {
            case 0: // casa
                Gfx.poly(ms, new float[] {cx - 6, cy, cx, cy - 6, cx + 6, cy}, c);
                Gfx.rrect(ms, cx - 4, cy, 8, 6, 1, c);
                break;
            case 1: // grila
                for (int a = 0; a < 2; a++) for (int k = 0; k < 2; k++) Gfx.rrect(ms, cx - 6 + a * 7, cy - 6 + k * 7, 5, 5, 1, c);
                break;
            case 2: // servieta
                Gfx.rrect(ms, cx - 6, cy - 3, 12, 8, 1.5f, c);
                Gfx.rrect(ms, cx - 2.5f, cy - 6, 5, 3, 1, c);
                break;
            case 3: // bifa
                Gfx.line(ms, cx - 5, cy, cx - 1.5f, cy + 4, 1.8f, c);
                Gfx.line(ms, cx - 1.5f, cy + 4, cx + 6, cy - 5, 1.8f, c);
                break;
            case 4: // stea
                Gfx.poly(ms, new float[] {cx, cy - 7, cx + 2, cy - 2, cx + 7, cy - 2, cx + 3, cy + 1.5f, cx + 4.5f, cy + 6.5f, cx, cy + 3.5f, cx - 4.5f, cy + 6.5f, cx - 3, cy + 1.5f, cx - 7, cy - 2, cx - 2, cy - 2}, c);
                break;
            case 5: // ceas
                Gfx.ring(ms, cx, cy, 5, 7, c, c);
                Gfx.line(ms, cx, cy, cx, cy - 4, 1.4f, c);
                Gfx.line(ms, cx, cy, cx + 3, cy + 1, 1.4f, c);
                break;
            default: // grafic
                Gfx.rrect(ms, cx - 6, cy, 3, 6, 0.5f, c);
                Gfx.rrect(ms, cx - 1.5f, cy - 4, 3, 10, 0.5f, c);
                Gfx.rrect(ms, cx + 3, cy - 7, 3, 13, 0.5f, c);
                break;
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        JsonObject m = me();
        Gfx.text(ms, NAV[page], CX, 36, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOWCITY  ›  " + NAV[page].toUpperCase(), CX, 20, 6.2f, Sk.FAINT, 0, false, 1.3f);
        // solduri
        float bx = W - 12;
        String cash = "$" + Ui.money(n(m, "cash"));
        float cw = Gfx.width(cash, 8.5f, true) + 28;
        Gfx.rrect(ms, bx - cw, 14, cw, 24, 8, 0xFF171C38);
        Gfx.disc(ms, bx - cw + 12, 26, 4.5f, 0xFF34D399);
        Gfx.text(ms, cash, bx - cw + 22, 29.5f, 8.5f, Sk.WHITE, 0, true, 0f);
        String lv = "NIVEL " + (int) n(m, "level");
        float lw = Gfx.width(lv, 7.5f, true) + 20;
        Gfx.rrect(ms, bx - cw - 8 - lw, 14, lw, 24, 8, 0xFF241B52);
        Gfx.text(ms, lv, bx - cw - 8 - lw / 2f, 29, 7.5f, 0xFFC4B5FD, 1, true, 0.5f);
        switch (page) {
            case 0:
                home(ms, lmx, lmy);
                break;
            case 5:
                history(ms);
                break;
            case 6:
                stats(ms);
                break;
            default:
                grid(ms, lmx, lmy);
                break;
        }
    }

    // ---- Acasa

    private void home(GuiGraphics ms, float lmx, float lmy) {
        JsonObject m = me();
        String cur = s(m, "job");
        JsonObject cj = cur.isEmpty() ? null : jobById(cur);
        Gfx.text(ms, "Bun venit, " + s(m, "name") + "!", CX, 62, 9.5f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "Alege un job, începe tura și crește în nivel. Totul se validează pe server.", CX, 75, 6.8f, Sk.MUTED, 0);
        int avail = 0;
        for (JsonObject j : jobs()) if (b(j, "canHire")) avail++;
        float tw = (CW - GAP * 3) / 4f;
        tile(ms, CX, 86, tw, "JOBURI DISPONIBILE", String.valueOf(avail), VIOLET);
        tile(ms, CX + (tw + GAP), 86, tw, "JOB ACTUAL", cj == null ? "Niciunul" : s(cj, "name"), 0xFF4DA3FF);
        tile(ms, CX + (tw + GAP) * 2, 86, tw, "BANI CÂȘTIGAȚI", "$" + Ui.money(n(m, "totalEarned")), 0xFF34D399);
        tile(ms, CX + (tw + GAP) * 3, 86, tw, "XP TOTAL", String.valueOf((long) n(m, "totalXp")), 0xFFFBBF24);
        // job curent + tura
        Gfx.rrect(ms, CX, 142, CW, 52, 10, CARD);
        if (cj == null) {
            Gfx.text(ms, "Nu ai niciun job.", CX + 14, 164, 9, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "Alege din Toate joburile sau din recomandările de mai jos.", CX + 14, 178, 6.8f, Sk.MUTED, 0);
        } else {
            int col = groupColor(s(cj, "group"), s(cj, "category"));
            art(ms, cj, CX + 8, 148, 40, 40, col);
            Gfx.text(ms, s(cj, "name"), CX + 56, 162, 9.5f, Sk.WHITE, 0, true, 0f);
            double need = n(m, "jobXpNeed");
            Gfx.text(ms, "Nivel " + (int) n(m, "jobLevel") + (need > 0 ? "  ·  " + (long) n(m, "jobXp") + " / " + (long) need + " XP" : "  ·  MAX"), CX + 56, 175, 6.8f, Sk.MUTED, 0);
            Gfx.bar(ms, CX + 56, 181, 170, 5, need > 0 ? (float) (n(m, "jobXp") / need) : 1f, 0xFF1F2547, VIOLET);
            boolean shift = b(m, "inShift");
            if (shift) {
                Gfx.text(ms, "TURĂ ACTIVĂ  " + Ui.duration((long) n(m, "shiftSec")), CX + CW - 200, 160, 7, Sk.GOOD, 0, true, 0.4f);
                Gfx.text(ms, "+$" + Ui.money(n(m, "shiftEarned")) + "  ·  " + (int) n(m, "shiftMissions") + " misiuni", CX + CW - 200, 172, 6.8f, Sk.MUTED, 0);
            }
            smallButton(ms, shift ? "ÎNCHEIE TURA" : "ÎNCEPE TURA", CX + CW - 108, 156, 96, 24, lmx, lmy, true, !shift, () -> FJobsClient.request(shift ? "end" : "start", new JsonObject()));
        }
        // recomandate + populare
        List<JsonObject> rec = byIds("recommended");
        List<JsonObject> pop = byIds("popular");
        Gfx.text(ms, "RECOMANDATE", CX, 210, 6.4f, Sk.MUTED, 0, false, 1.4f);
        miniRow(ms, lmx, lmy, rec, 216, "Nimic de recomandat acum.");
        Gfx.text(ms, "POPULARE", CX, 286, 6.4f, Sk.MUTED, 0, false, 1.4f);
        miniRow(ms, lmx, lmy, pop, 292, "Încă nu lucrează nimeni.");
    }

    private List<JsonObject> byIds(String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonObject d = data();
        if (d == null || !d.has(key) || !d.get(key).isJsonArray()) return out;
        for (JsonElement e : d.getAsJsonArray(key)) {
            JsonObject j = jobById(e.getAsString());
            if (j != null) out.add(j);
        }
        return out;
    }

    private void tile(GuiGraphics ms, float x, float y, float w, String label, String value, int color) {
        Gfx.rrect(ms, x, y, w, 46, 10, CARD);
        Gfx.rrect(ms, x, y + 10, 3, 26, 1.5f, color);
        Gfx.text(ms, label, x + 12, y + 16, 5.8f, Sk.FAINT, 0, false, 1.1f);
        Gfx.text(ms, clip(value, w - 20, 10.5f, true), x + 12, y + 35, 10.5f, Sk.WHITE, 0, true, 0f);
    }

    private void miniRow(GuiGraphics ms, float lmx, float lmy, List<JsonObject> list, float y, String empty) {
        if (list.isEmpty()) {
            Gfx.rrect(ms, CX, y, CW, 56, 10, CARD);
            Gfx.text(ms, empty, CX + CW / 2f, y + 32, 7, Sk.FAINT, 1);
            return;
        }
        for (int i = 0; i < list.size() && i < COLS; i++) {
            JsonObject j = list.get(i);
            float x = CX + i * (CARD_W + GAP);
            boolean hot = Sk.in(lmx, lmy, x, y, CARD_W, 56);
            Gfx.rrect(ms, x, y, CARD_W, 56, 10, hot ? CARD_HOT : CARD);
            art(ms, j, x + 8, y + 8, 40, 40, groupColor(s(j, "group"), s(j, "category")));
            Gfx.text(ms, clip(s(j, "name"), CARD_W - 64, 8.5f, true), x + 56, y + 24, 8.5f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "≈ $" + (long) n(j, "estPay") + "/oră", x + 56, y + 37, 6.8f, Sk.GOOD, 0);
            Gfx.text(ms, s(j, "difficulty"), x + 56, y + 48, 6.2f, Sk.MUTED, 0);
            final String id = s(j, "id");
            hitL(x, y, CARD_W, 56, () -> detail = id);
        }
    }

    // ---- Grila de carduri

    private float maxScroll(int count) {
        int rows = (count + COLS - 1) / COLS;
        return Math.max(0, rows * (CARD_H + GAP) - VIEW_H);
    }

    private void grid(GuiGraphics ms, float lmx, float lmy) {
        List<JsonObject> list = pageJobs();
        if (page == 4 && data() != null && !b(data(), "premiumOn")) {
            Gfx.text(ms, "Joburile premium nu sunt active pe acest server.", CX, 54, 7, Sk.WARN, 0);
        }
        if (list.isEmpty()) {
            Gfx.rrect(ms, CX, TOP, CW, 80, 12, CARD);
            String msg = page == 2 ? "Nu ai încă niciun job. Alege unul din Toate joburile." : page == 3 ? "Nu ai joburi disponibile acum (nivel, permisiuni sau locuri)." : "Nu există joburi în această categorie.";
            Gfx.text(ms, msg, CX + CW / 2f, TOP + 44, 7.5f, Sk.MUTED, 1);
            return;
        }
        scrollTarget = Math.max(0, Math.min(maxScroll(list.size()), scrollTarget));
        Ui.scissor(Math.round(offX + CX * scale - 2), Math.round(offY + TOP * scale), Math.round((CW + 4) * scale), Math.round(VIEW_H * scale));
        try {
            for (int i = 0; i < list.size(); i++) {
                float x = CX + (i % COLS) * (CARD_W + GAP);
                float y = TOP + (i / COLS) * (CARD_H + GAP) - scroll;
                if (y + CARD_H < TOP || y > TOP + VIEW_H) continue;
                card(ms, list.get(i), x, y, lmx, lmy);
            }
        } finally {
            Ui.endScissor();
        }
        float ms2 = maxScroll(list.size());
        if (ms2 > 0) {
            float th = Math.max(24, VIEW_H * VIEW_H / (VIEW_H + ms2));
            float ty = TOP + (VIEW_H - th) * (scroll / ms2);
            Gfx.rrect(ms, W - 8, TOP, 2.5f, VIEW_H, 1.2f, 0xFF171C38);
            Gfx.rrect(ms, W - 8, ty, 2.5f, th, 1.2f, VIOLET);
        }
    }

    private void card(GuiGraphics ms, JsonObject j, float x, float y, float lmx, float lmy) {
        boolean inView = lmy >= TOP && lmy <= TOP + VIEW_H;
        boolean hot = inView && Sk.in(lmx, lmy, x, y, CARD_W, CARD_H);
        int col = groupColor(s(j, "group"), s(j, "category"));
        String cur = s(me(), "job");
        boolean mine = s(j, "id").equals(cur);
        Gfx.rrect(ms, x, y, CARD_W, CARD_H, 11, hot ? CARD_HOT : CARD);
        if (mine) Gfx.rrect(ms, x, y, CARD_W, 2.5f, 1.2f, VIOLET);
        art(ms, j, x + 6, y + 6, CARD_W - 12, 46, col);
        // status pe ilustratie
        String st = statusLabel(j);
        float sw = Gfx.width(st, 5.2f, true) + 10;
        Gfx.rrect(ms, x + CARD_W - 6 - sw - 4, y + 10, sw, 11, 5.5f, 0xCC0A0D1C);
        Gfx.disc(ms, x + CARD_W - 6 - sw + 1, y + 15.5f, 2, statusColor(j));
        Gfx.text(ms, st, x + CARD_W - 6 - sw / 2f - 1, y + 18.5f, 5.2f, Sk.WHITE, 1, true, 0.3f);
        if (b(j, "premium")) Gfx.text(ms, "PREMIUM", x + 12, y + 18.5f, 5.4f, 0xFFFBBF24, 0, true, 0.5f);
        Gfx.text(ms, clip(s(j, "name"), CARD_W - 16, 9, true), x + 8, y + 65, 9, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, s(j, "group") + "  ·  " + s(j, "difficulty"), x + 8, y + 76, 6.2f, Sk.MUTED, 0);
        Gfx.text(ms, "≈ $" + (long) n(j, "estPay") + "/oră  ·  " + (int) n(j, "estMinutes") + " min", x + 8, y + 87, 6.4f, Sk.GOOD, 0);
        int max = (int) n(j, "max");
        Gfx.text(ms, "Nivel " + (int) n(j, "minLevel") + "+  ·  " + (int) n(j, "employees") + (max > 0 ? "/" + max : "") + " angajați", x + 8, y + 97, 6.2f, Sk.FAINT, 0);
        float by = y + CARD_H - 24;
        final String id = s(j, "id");
        float half = (CARD_W - 20) / 2f;
        smallButton(ms, "VEZI DETALII", x + 8, by, half, 18, lmx, lmy, inView, false, () -> detail = id);
        if (mine) {
            smallButton(ms, "DEMISIONEAZĂ", x + 12 + half, by, half, 18, lmx, lmy, inView, false, () -> FJobsClient.request("quit", new JsonObject()));
        } else {
            boolean can = b(j, "canHire");
            smallButton(ms, "ANGAJEAZĂ-TE", x + 12 + half, by, half, 18, lmx, lmy, inView && can, true, () -> hire(id));
        }
    }

    private static void hire(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        FJobsClient.request("hire", o);
    }

    // ---- Istoric / Statistici

    private void history(GuiGraphics ms) {
        JsonArray h = data().has("history") && data().get("history").isJsonArray() ? data().getAsJsonArray("history") : new JsonArray();
        if (h.size() == 0) {
            Gfx.rrect(ms, CX, TOP, CW, 80, 12, CARD);
            Gfx.text(ms, "Nicio activitate încă. Angajează-te și începe o tură.", CX + CW / 2f, TOP + 44, 7.5f, Sk.MUTED, 1);
            return;
        }
        int rowH = 26;
        for (int i = 0; i < h.size() && i < 10; i++) {
            JsonObject e = h.get(i).getAsJsonObject();
            float y = TOP + i * (rowH + 4);
            Gfx.rrect(ms, CX, y, CW, rowH, 8, CARD);
            String type = s(e, "type");
            int c = "shift".equals(type) ? Sk.GOOD : "level".equals(type) ? 0xFFFBBF24 : "hire".equals(type) ? VIOLET : Sk.MUTED;
            Gfx.rrect(ms, CX, y + 5, 3, rowH - 10, 1.5f, c);
            Gfx.text(ms, clip(s(e, "text"), CW - 150, 7.2f, false), CX + 12, y + 16.5f, 7.2f, Sk.WHITE, 0);
            Gfx.text(ms, ago((long) n(e, "ageSec")), CX + CW - 10, y + 11, 6, Sk.FAINT, 2);
            if (n(e, "money") > 0) Gfx.text(ms, "+$" + Ui.money(n(e, "money")), CX + CW - 10, y + 21, 7, Sk.GOOD, 2, true, 0f);
        }
    }

    private static String ago(long sec) {
        if (sec < 60) return "acum";
        if (sec < 3600) return (sec / 60) + " min";
        if (sec < 86400) return (sec / 3600) + " h";
        return (sec / 86400) + " zile";
    }

    private void stats(GuiGraphics ms) {
        JsonObject m = me();
        float tw = (CW - GAP * 2) / 3f;
        tile(ms, CX, TOP - 2, tw, "TURE FĂCUTE", String.valueOf((int) n(m, "shifts")), VIOLET);
        tile(ms, CX + tw + GAP, TOP - 2, tw, "MISIUNI", String.valueOf((int) n(m, "missions")), 0xFF4DA3FF);
        tile(ms, CX + (tw + GAP) * 2, TOP - 2, tw, "TIMP LUCRAT", Ui.duration((long) n(m, "seconds")), 0xFFFBBF24);
        Gfx.rrect(ms, CX, TOP + 52, CW, 46, 10, CARD);
        Gfx.text(ms, "NIVEL PROFESIONAL " + (int) n(m, "level"), CX + 12, TOP + 70, 7.5f, Sk.WHITE, 0, true, 0.4f);
        double need = n(m, "xpNeed");
        Gfx.text(ms, need > 0 ? (long) n(m, "xp") + " / " + (long) need + " XP" : "NIVEL MAXIM", CX + CW - 12, TOP + 70, 7, Sk.MUTED, 2);
        Gfx.bar(ms, CX + 12, TOP + 78, CW - 24, 7, need > 0 ? (float) (n(m, "xp") / need) : 1f, 0xFF1F2547, VIOLET);
        Gfx.text(ms, "CÂȘTIGURI PE JOB", CX, TOP + 114, 6.4f, Sk.MUTED, 0, false, 1.4f);
        List<JsonObject> js = new ArrayList<>();
        double top = 1;
        for (JsonObject j : jobs()) {
            if (n(j, "earned") > 0) {
                js.add(j);
                top = Math.max(top, n(j, "earned"));
            }
        }
        if (js.isEmpty()) {
            Gfx.text(ms, "Nu ai câștiguri înregistrate încă.", CX, TOP + 134, 7, Sk.FAINT, 0);
            return;
        }
        js.sort((a, c) -> Double.compare(n(c, "earned"), n(a, "earned")));
        for (int i = 0; i < js.size() && i < 6; i++) {
            JsonObject j = js.get(i);
            float y = TOP + 120 + i * 20;
            Gfx.text(ms, clip(s(j, "name"), 110, 7, false), CX, y + 9, 7, Sk.WHITE, 0);
            Gfx.bar(ms, CX + 120, y + 2, CW - 240, 7, (float) (n(j, "earned") / top), 0xFF1F2547, groupColor(s(j, "group"), s(j, "category")));
            Gfx.text(ms, "$" + Ui.money(n(j, "earned")) + "  ·  niv " + (int) n(j, "level"), CX + CW, y + 9, 6.8f, Sk.MUTED, 2);
        }
    }

    // ------------------------------------------------------------------ fereastra de detalii + administrare

    private boolean adminOpen;
    private int group = 1;

    private void detailModal(GuiGraphics ms, float lmx, float lmy, float l, float t, float r, float bt) {
        JsonObject j = jobById(detail);
        if (j == null) {
            detail = "";
            return;
        }
        Gfx.rrect(ms, l, t, r - l, bt - t, 0, 0xB0050712);
        hitL(l, t, r - l, bt - t, () -> {}); // blocheaza click-urile din spate
        float mw = 420;
        float mh = adminOpen && admin ? 300 : 262;
        float mx0 = (W - mw) / 2f;
        float my0 = (H - mh) / 2f;
        Gfx.rrect(ms, mx0, my0, mw, mh, 14, 0xFF141A30, 0xFF0E1226);
        int col = groupColor(s(j, "group"), s(j, "category"));
        art(ms, j, mx0 + 12, my0 + 12, 96, 70, col);
        Gfx.text(ms, s(j, "name"), mx0 + 120, my0 + 30, 14, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, s(j, "group") + "  ·  " + ("special".equals(s(j, "category")) ? "Job special" : "Job civil") + "  ·  " + s(j, "difficulty"), mx0 + 120, my0 + 43, 6.8f, Sk.MUTED, 0);
        String st = statusLabel(j);
        Gfx.disc(ms, mx0 + 123, my0 + 53, 2.5f, statusColor(j));
        Gfx.text(ms, st, mx0 + 130, my0 + 56, 6.6f, statusColor(j), 0, true, 0.4f);
        wrap(ms, s(j, "desc"), mx0 + 120, my0 + 69, mw - 132, 6.8f, Sk.MUTED, 2);
        boolean close = Sk.close(ms, mx0 + mw - 30, my0 + 8, lmx, lmy);
        hitL(mx0 + mw - 30, my0 + 8, 20, 20, () -> {
            detail = "";
            adminOpen = false;
        });
        if (adminOpen && admin && j.has("kind")) {
            adminCustom(ms, j, mx0, my0 + 86, mw, lmx, lmy);
            actionButtons(ms, j, mx0, my0 + mh - 34, mw, lmx, lmy);
            return;
        }
        // statistici
        float sy = my0 + 92;
        float cw = (mw - 24 - 8 * 3) / 4f;
        int max = (int) n(j, "max");
        miniStat(ms, mx0 + 12, sy, cw, "SALARIU EST.", "$" + (long) n(j, "estPay") + "/h");
        miniStat(ms, mx0 + 12 + (cw + 8), sy, cw, "DURATĂ", (int) n(j, "estMinutes") + " min");
        miniStat(ms, mx0 + 12 + (cw + 8) * 2, sy, cw, "NIVEL MINIM", String.valueOf((int) n(j, "minLevel")));
        miniStat(ms, mx0 + 12 + (cw + 8) * 3, sy, cw, "ANGAJAȚI", (int) n(j, "employees") + (max > 0 ? "/" + max : ""));
        // taskuri
        float ty = sy + 44;
        Gfx.text(ms, "CE FACI", mx0 + 12, ty, 6.2f, Sk.FAINT, 0, false, 1.3f);
        JsonArray tasks = j.has("tasks") && j.get("tasks").isJsonArray() ? j.getAsJsonArray("tasks") : new JsonArray();
        for (int i = 0; i < tasks.size() && i < 4; i++) {
            Gfx.disc(ms, mx0 + 16, ty + 11 + i * 11 - 2.5f, 1.8f, col);
            Gfx.text(ms, clip(tasks.get(i).getAsString(), mw - 40, 6.8f, false), mx0 + 24, ty + 11 + i * 11, 6.8f, Sk.WHITE, 0);
        }
        // progres in job
        float py2 = ty + 58;
        double need = n(j, "xpNeed");
        Gfx.text(ms, "NIVELUL TĂU: " + (int) n(j, "level") + (need > 0 ? "   " + (long) n(j, "xp") + " / " + (long) need + " XP" : "   MAX"), mx0 + 12, py2, 6.6f, Sk.MUTED, 0, true, 0.3f);
        Gfx.bar(ms, mx0 + 12, py2 + 5, mw - 24, 5, need > 0 ? (float) (n(j, "xp") / need) : 1f, 0xFF1F2547, VIOLET);
        String why = s(j, "why");
        if (!why.isEmpty() && !s(j, "id").equals(s(me(), "job"))) Gfx.text(ms, clip(why, mw - 24, 6.6f, false), mx0 + 12, py2 + 21, 6.6f, Sk.WARN, 0);
        else if (!s(j, "note").isEmpty()) Gfx.text(ms, clip(s(j, "note"), mw - 24, 6.6f, false), mx0 + 12, py2 + 21, 6.6f, Sk.MUTED, 0);
        // butoane
        float by = my0 + (adminOpen && admin ? 0 : mh) - 34;
        if (!(adminOpen && admin)) {
            actionButtons(ms, j, mx0, by, mw, lmx, lmy);
        } else {
            adminPanel(ms, j, mx0, my0 + 198, mw, lmx, lmy);
            actionButtons(ms, j, mx0, my0 + mh - 34, mw, lmx, lmy);
        }
    }

    private void actionButtons(GuiGraphics ms, JsonObject j, float mx0, float by, float mw, float lmx, float lmy) {
        final String id = s(j, "id");
        JsonObject m = me();
        boolean mine = id.equals(s(m, "job"));
        boolean shift = b(m, "inShift");
        float x = mx0 + 12;
        if (mine) {
            smallButton(ms, shift ? "ÎNCHEIE TURA" : "ÎNCEPE TURA", x, by, 112, 24, lmx, lmy, true, !shift, () -> FJobsClient.request(shift ? "end" : "start", new JsonObject()));
            smallButton(ms, "DEMISIONEAZĂ", x + 120, by, 96, 24, lmx, lmy, !shift, false, () -> FJobsClient.request("quit", new JsonObject()));
            if ((j.has("kind") || b(j, "hasGear")) && "READY".equals(s(j, "status"))) smallButton(ms, "PANOU JOB", x + 224, by, 78, 24, lmx, lmy, true, false, FJobsClient::openPanel);
        } else {
            smallButton(ms, "ANGAJEAZĂ-TE", x, by, 118, 24, lmx, lmy, b(j, "canHire"), true, () -> hire(id));
        }
        if (admin) smallButton(ms, adminOpen ? "ASCUNDE ADMIN" : "ADMINISTRARE", mx0 + mw - 12 - 90, by, 90, 24, lmx, lmy, true, false, () -> adminOpen = !adminOpen);
    }

    /** Administrare pentru joburile cu activitate proprie: parametri generali + puncte pe tipuri (statii), adaugate unde stai in joc. */
    private void adminCustom(GuiGraphics ms, JsonObject j, float x0, float y0, float mw, float lmx, float lmy) {
        final String id = s(j, "id");
        Gfx.rrect(ms, x0 + 8, y0 - 2, mw - 16, 176, 8, 0xFF0A0D1C);
        Gfx.text(ms, "ADMINISTRARE (server validează)", x0 + 14, y0 + 8, 5.8f, 0xFFFBBF24, 0, false, 1.1f);
        float x = x0 + 14;
        float y = y0 + 12;
        stepper(ms, "Salariu", (long) n(j, "estPay") + "", x, y, lmx, lmy, "estPay", id, n(j, "estPay"), 50);
        stepper(ms, "Nivel min", (int) n(j, "minLevel") + "", x + 100, y, lmx, lmy, "minLevel", id, n(j, "minLevel"), 1);
        stepper(ms, "Locuri", (int) n(j, "max") + "", x + 200, y, lmx, lmy, "max", id, n(j, "max"), 1);
        stepper(ms, "Mult. plata", String.format(java.util.Locale.ROOT, "%.2f", n(j, "payMult")), x + 300, y, lmx, lmy, "payMult", id, n(j, "payMult"), 0.1);
        float ty = y + 34;
        toggle(ms, "Activ", b(j, "active"), x, ty, lmx, lmy, "active", id);
        toggle(ms, "Premium", b(j, "premium"), x + 70, ty, lmx, lmy, "premium", id);
        Gfx.text(ms, "GRUP / LINIE / RUTĂ NR.", x + 150, ty + 9, 5.2f, Sk.FAINT, 0, false, 0.7f);
        smallButton(ms, "-", x + 262, ty - 4, 16, 18, lmx, lmy, group > 1, false, () -> group = Math.max(1, group - 1));
        Gfx.text(ms, String.valueOf(group), x + 292, ty + 9, 8, Sk.WHITE, 1, true, 0f);
        smallButton(ms, "+", x + 306, ty - 4, 16, 18, lmx, lmy, group < 99, false, () -> group = Math.min(99, group + 1));
        JsonArray sk = j.has("stations") && j.get("stations").isJsonArray() ? j.getAsJsonArray("stations") : new JsonArray();
        float ry = ty + 26;
        Gfx.text(ms, "PUNCTE (stai în joc unde vrei punctul, apoi apasă ADAUGĂ AICI)", x, ry, 5.4f, Sk.FAINT, 0, false, 0.6f);
        ry += 6;
        for (int i = 0; i < sk.size() && i < 4; i++) {
            JsonObject so = sk.get(i).getAsJsonObject();
            final String kind = s(so, "k");
            float yy = ry + i * 22;
            Gfx.text(ms, clip(s(so, "label") + " (" + (int) n(so, "n") + ")", 150, 6.6f, false), x, yy + 12, 6.6f, Sk.WHITE, 0);
            smallButton(ms, "+ ADAUGĂ AICI", x + 156, yy, 96, 18, lmx, lmy, true, true, () -> point(id, kind, "add", group));
            smallButton(ms, "- ULTIMUL", x + 256, yy, 70, 18, lmx, lmy, n(so, "n") > 0, false, () -> point(id, kind, "pop", group));
            smallButton(ms, "GOLEȘTE", x + 330, yy, 64, 18, lmx, lmy, n(so, "n") > 0, false, () -> point(id, kind, "clear", group));
        }
        String note = s(j, "note");
        if (!note.isEmpty()) Gfx.text(ms, clip(note, mw - 36, 5.8f, false), x, y0 + 166, 5.8f, Sk.WARN, 0);
    }

    private void adminPanel(GuiGraphics ms, JsonObject j, float x0, float y0, float mw, float lmx, float lmy) {
        final String id = s(j, "id");
        Gfx.rrect(ms, x0 + 8, y0 - 2, mw - 16, 66, 8, 0xFF0A0D1C);
        Gfx.text(ms, "ADMINISTRARE (server validează)", x0 + 14, y0 + 8, 5.8f, 0xFFFBBF24, 0, false, 1.1f);
        float x = x0 + 14;
        float y = y0 + 14;
        stepper(ms, "Salariu", (long) n(j, "estPay") + "", x, y, lmx, lmy, "estPay", id, n(j, "estPay"), 50);
        stepper(ms, "Nivel min", (int) n(j, "minLevel") + "", x + 100, y, lmx, lmy, "minLevel", id, n(j, "minLevel"), 1);
        stepper(ms, "Locuri", (int) n(j, "max") + "", x + 200, y, lmx, lmy, "max", id, n(j, "max"), 1);
        stepper(ms, "Mult. plata", String.format(java.util.Locale.ROOT, "%.2f", n(j, "payMult")), x + 300, y, lmx, lmy, "payMult", id, n(j, "payMult"), 0.1);
        float ty = y + 30;
        toggle(ms, "Activ", b(j, "active"), x, ty, lmx, lmy, "active", id);
        toggle(ms, "Premium", b(j, "premium"), x + 70, ty, lmx, lmy, "premium", id);
        String act = s(j, "activity");
        if ("delivery".equals(act)) {
            smallButton(ms, "+RIDICARE (" + (int) n(j, "pickups") + ")", x + 150, ty - 4, 92, 18, lmx, lmy, true, false, () -> point(id, "pickups", "add"));
            smallButton(ms, "+LIVRARE (" + (int) n(j, "drops") + ")", x + 246, ty - 4, 92, 18, lmx, lmy, true, false, () -> point(id, "drops", "add"));
            smallButton(ms, "ȘTERGE", x + 342, ty - 4, 52, 18, lmx, lmy, true, false, () -> {
                point(id, "pickups", "clear");
                point(id, "drops", "clear");
            });
        } else if ("route".equals(act)) {
            smallButton(ms, "+CHECKPOINT (" + (int) n(j, "route") + ")", x + 150, ty - 4, 120, 18, lmx, lmy, true, false, () -> point(id, "route", "add"));
            smallButton(ms, "ȘTERGE TOATE", x + 276, ty - 4, 96, 18, lmx, lmy, true, false, () -> point(id, "route", "clear"));
        }
    }

    private void stepper(GuiGraphics ms, String label, String value, float x, float y, float lmx, float lmy, String field, String id, double cur, double step) {
        Gfx.text(ms, label.toUpperCase(), x, y + 8, 5.4f, Sk.FAINT, 0, false, 0.8f);
        smallButton(ms, "-", x, y + 11, 16, 14, lmx, lmy, true, false, () -> set(id, field, Math.max(0, cur - step)));
        Gfx.text(ms, value, x + 34, y + 22, 7.5f, Sk.WHITE, 1, true, 0f);
        smallButton(ms, "+", x + 52, y + 11, 16, 14, lmx, lmy, true, false, () -> set(id, field, cur + step));
    }

    private void toggle(GuiGraphics ms, String label, boolean on, float x, float y, float lmx, float lmy, String field, String id) {
        smallButton(ms, label + ": " + (on ? "DA" : "NU"), x, y - 4, 64, 18, lmx, lmy, true, on, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("field", field);
            o.addProperty("value", !on);
            FJobsClient.request("adm_set", o);
        });
    }

    private static void set(String id, String field, double value) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("field", field);
        o.addProperty("value", value);
        FJobsClient.request("adm_set", o);
    }

    private static void point(String id, String kind, String op) {
        point(id, kind, op, 1);
    }

    private static void point(String id, String kind, String op, int group) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        o.addProperty("op", op);
        o.addProperty("group", group);
        FJobsClient.request("adm_point", o);
    }

    private void miniStat(GuiGraphics ms, float x, float y, float w, String label, String value) {
        Gfx.rrect(ms, x, y, w, 36, 8, 0xFF0F1428);
        Gfx.text(ms, label, x + 8, y + 13, 5.4f, Sk.FAINT, 0, false, 0.9f);
        Gfx.text(ms, clip(value, w - 12, 9, true), x + 8, y + 28, 9, Sk.WHITE, 0, true, 0f);
    }

    // ------------------------------------------------------------------ componente comune

    private void smallButton(GuiGraphics ms, String label, float x, float y, float w, float h, float lmx, float lmy, boolean enabled, boolean filled, Runnable action) {
        boolean hot = enabled && Sk.in(lmx, lmy, x, y, w, h);
        int bg = !enabled ? 0xFF141A2C : filled ? (hot ? 0xFFA78BFA : VIOLET) : (hot ? 0xFF262E55 : 0xFF1B2140);
        int fg = !enabled ? Sk.FAINT : filled ? 0xFF0B0720 : (hot ? Sk.WHITE : 0xFFC9CBE0);
        Gfx.rrect(ms, x, y, w, h, Math.min(7, h / 2.5f), bg);
        Gfx.text(ms, label, x + w / 2f, y + h / 2f + 2.6f, Math.min(6.4f, h * 0.36f + 1.2f), fg, 1, true, 0.3f);
        if (enabled) hitL(x, y, w, h, action);
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String clip(String s, float max, float size, boolean xl) {
        if (Gfx.width(s, size, xl) <= max) return s;
        String r = s;
        while (r.length() > 1 && Gfx.width(r + "…", size, xl) > max) r = r.substring(0, r.length() - 1);
        return r.trim() + "…";
    }

    private void wrap(GuiGraphics ms, String text, float x, float y, float w, float size, int color, int maxLines) {
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int n = 0;
        for (String word : words) {
            String t = line.length() == 0 ? word : line + " " + word;
            if (Gfx.width(t, size, false) > w && line.length() > 0) {
                Gfx.text(ms, line.toString(), x, y + n * (size + 3.5f), size, color, 0);
                line = new StringBuilder(word);
                if (++n >= maxLines) return;
            } else {
                line = new StringBuilder(t);
            }
        }
        if (line.length() > 0 && n < maxLines) Gfx.text(ms, line.toString(), x, y + n * (size + 3.5f), size, color, 0);
    }

    // ------------------------------------------------------------------ ilustratii vectoriale (desenate din forme, fara texturi)

    private void art(GuiGraphics ms, JsonObject j, float x, float y, float w, float h, int col) {
        Gfx.rrect(ms, x, y, w, h, 9, Gfx.mixArgb(0xFF11162C, col, 0.38f), Gfx.mixArgb(0xFF0B0F20, col, 0.12f));
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        Gfx.disc(ms, cx, cy, Math.min(w, h) * 0.46f, Gfx.fade(col, 0.22f));
        Gfx.disc(ms, x + w * 0.14f, y + h * 0.2f, 2.2f, 0x33FFFFFF);
        Gfx.disc(ms, x + w * 0.86f, y + h * 0.78f, 1.6f, 0x33FFFFFF);
        glyph(ms, s(j, "icon"), cx, cy, Math.min(w, h) * 0.32f, 0xFFFFFFFF, col);
    }

    private void glyph(GuiGraphics ms, String icon, float cx, float cy, float u, int c, int acc) {
        switch (icon) {
            case "pickaxe":
                Gfx.line(ms, cx - u * 0.8f, cy + u * 0.9f, cx + u * 0.7f, cy - u * 0.7f, u * 0.22f, 0xFFD6B58A);
                Gfx.poly(ms, new float[] {cx - u, cy - u * 0.3f, cx, cy - u * 1.1f, cx + u * 1.1f, cy - u * 0.3f, cx + u * 0.9f, cy - u * 0.6f, cx, cy - u * 0.8f, cx - u * 0.8f, cy - u * 0.6f}, c);
                break;
            case "axe":
                Gfx.line(ms, cx - u * 0.7f, cy + u, cx + u * 0.5f, cy - u * 0.8f, u * 0.22f, 0xFFD6B58A);
                Gfx.poly(ms, new float[] {cx + u * 0.1f, cy - u * 1.0f, cx + u * 1.1f, cy - u * 0.7f, cx + u * 1.0f, cy + u * 0.2f, cx + u * 0.3f, cy - u * 0.3f}, c);
                break;
            case "fish":
                Gfx.poly(ms, new float[] {cx - u * 1.1f, cy, cx - u * 0.3f, cy - u * 0.7f, cx + u * 0.7f, cy - u * 0.2f, cx + u * 0.7f, cy + u * 0.2f, cx - u * 0.3f, cy + u * 0.7f}, c);
                Gfx.poly(ms, new float[] {cx + u * 0.6f, cy, cx + u * 1.3f, cy - u * 0.6f, cx + u * 1.3f, cy + u * 0.6f}, c);
                Gfx.disc(ms, cx - u * 0.6f, cy - u * 0.15f, u * 0.12f, acc);
                break;
            case "wheat":
                Gfx.line(ms, cx, cy + u, cx, cy - u * 0.9f, u * 0.14f, c);
                for (int i = 0; i < 3; i++) {
                    float yy = cy - u * 0.6f + i * u * 0.5f;
                    Gfx.poly(ms, new float[] {cx, yy, cx - u * 0.6f, yy - u * 0.4f, cx - u * 0.3f, yy + u * 0.1f}, c);
                    Gfx.poly(ms, new float[] {cx, yy, cx + u * 0.6f, yy - u * 0.4f, cx + u * 0.3f, yy + u * 0.1f}, c);
                }
                break;
            case "trash":
                Gfx.rrect(ms, cx - u * 0.7f, cy - u * 0.5f, u * 1.4f, u * 1.5f, u * 0.15f, c);
                Gfx.rrect(ms, cx - u * 0.9f, cy - u * 0.8f, u * 1.8f, u * 0.25f, u * 0.1f, c);
                Gfx.rrect(ms, cx - u * 0.3f, cy - u * 1.0f, u * 0.6f, u * 0.2f, u * 0.1f, c);
                for (int i = -1; i <= 1; i++) Gfx.line(ms, cx + i * u * 0.35f, cy - u * 0.2f, cx + i * u * 0.35f, cy + u * 0.7f, u * 0.1f, acc);
                break;
            case "hammer":
                Gfx.line(ms, cx - u * 0.8f, cy + u * 0.9f, cx + u * 0.5f, cy - u * 0.4f, u * 0.22f, 0xFFD6B58A);
                Gfx.rrect(ms, cx - u * 0.1f, cy - u * 1.0f, u * 1.3f, u * 0.6f, u * 0.1f, c);
                break;
            case "bolt":
                Gfx.poly(ms, new float[] {cx + u * 0.3f, cy - u * 1.1f, cx - u * 0.7f, cy + u * 0.1f, cx - u * 0.05f, cy + u * 0.1f, cx - u * 0.3f, cy + u * 1.1f, cx + u * 0.7f, cy - u * 0.2f, cx + u * 0.05f, cy - u * 0.2f}, 0xFFFBBF24);
                break;
            case "box":
            case "pallet":
                Gfx.rrect(ms, cx - u * 0.9f, cy - u * 0.6f, u * 1.8f, u * 1.5f, u * 0.12f, c);
                Gfx.rrect(ms, cx - u * 0.9f, cy - u * 0.6f, u * 1.8f, u * 0.4f, u * 0.1f, acc);
                Gfx.rrect(ms, cx - u * 0.12f, cy - u * 0.6f, u * 0.24f, u * 1.5f, 0, acc);
                break;
            case "mail":
                Gfx.rrect(ms, cx - u, cy - u * 0.6f, u * 2f, u * 1.3f, u * 0.14f, c);
                Gfx.line(ms, cx - u, cy - u * 0.55f, cx, cy + u * 0.15f, u * 0.14f, acc);
                Gfx.line(ms, cx + u, cy - u * 0.55f, cx, cy + u * 0.15f, u * 0.14f, acc);
                break;
            case "pizza":
                Gfx.poly(ms, new float[] {cx - u * 0.95f, cy - u * 0.7f, cx + u * 0.95f, cy - u * 0.7f, cx, cy + u * 1.0f}, 0xFFFBBF24);
                Gfx.rrect(ms, cx - u * 0.95f, cy - u * 0.85f, u * 1.9f, u * 0.25f, u * 0.12f, 0xFFD6935A);
                Gfx.disc(ms, cx - u * 0.2f, cy - u * 0.2f, u * 0.16f, 0xFFF87171);
                Gfx.disc(ms, cx + u * 0.25f, cy + u * 0.05f, u * 0.16f, 0xFFF87171);
                break;
            case "truck":
                Gfx.rrect(ms, cx - u * 1.1f, cy - u * 0.7f, u * 1.4f, u * 1.1f, u * 0.1f, c);
                Gfx.poly(ms, new float[] {cx + u * 0.4f, cy - u * 0.3f, cx + u * 0.8f, cy - u * 0.3f, cx + u * 1.1f, cy + u * 0.1f, cx + u * 1.1f, cy + u * 0.4f, cx + u * 0.4f, cy + u * 0.4f}, c);
                Gfx.disc(ms, cx - u * 0.6f, cy + u * 0.55f, u * 0.28f, acc);
                Gfx.disc(ms, cx + u * 0.7f, cy + u * 0.55f, u * 0.28f, acc);
                break;
            case "bus":
            case "taxi":
                Gfx.rrect(ms, cx - u * 1.1f, cy - u * 0.7f, u * 2.2f, u * 1.4f, u * 0.25f, "taxi".equals(icon) ? 0xFFFBBF24 : c);
                Gfx.rrect(ms, cx - u * 0.9f, cy - u * 0.5f, u * 1.8f, u * 0.55f, u * 0.1f, 0xFF1B2A4A);
                Gfx.disc(ms, cx - u * 0.6f, cy + u * 0.75f, u * 0.26f, 0xFF0B0F20);
                Gfx.disc(ms, cx + u * 0.6f, cy + u * 0.75f, u * 0.26f, 0xFF0B0F20);
                break;
            case "anchor":
                Gfx.ring(ms, cx, cy - u * 0.8f, u * 0.2f, u * 0.38f, c, c);
                Gfx.line(ms, cx, cy - u * 0.5f, cx, cy + u * 0.9f, u * 0.2f, c);
                Gfx.line(ms, cx - u * 0.5f, cy - u * 0.2f, cx + u * 0.5f, cy - u * 0.2f, u * 0.2f, c);
                Gfx.line(ms, cx - u * 0.9f, cy + u * 0.3f, cx, cy + u * 0.95f, u * 0.2f, c);
                Gfx.line(ms, cx + u * 0.9f, cy + u * 0.3f, cx, cy + u * 0.95f, u * 0.2f, c);
                break;
            case "window":
                Gfx.rrect(ms, cx - u * 0.8f, cy - u * 0.9f, u * 1.6f, u * 1.8f, u * 0.15f, c);
                Gfx.rrect(ms, cx - u * 0.65f, cy - u * 0.75f, u * 1.3f, u * 1.5f, u * 0.1f, 0xFF4DA3FF);
                Gfx.line(ms, cx, cy - u * 0.75f, cx, cy + u * 0.75f, u * 0.1f, c);
                Gfx.line(ms, cx - u * 0.65f, cy, cx + u * 0.65f, cy, u * 0.1f, c);
                break;
            case "flame":
                Gfx.poly(ms, new float[] {cx, cy - u * 1.2f, cx + u * 0.7f, cy - u * 0.1f, cx + u * 0.6f, cy + u * 0.7f, cx, cy + u * 1.0f, cx - u * 0.6f, cy + u * 0.7f, cx - u * 0.7f, cy - u * 0.1f, cx - u * 0.2f, cy - u * 0.4f}, 0xFFF87171);
                Gfx.poly(ms, new float[] {cx, cy - u * 0.3f, cx + u * 0.35f, cy + u * 0.3f, cx, cy + u * 0.8f, cx - u * 0.35f, cy + u * 0.3f}, 0xFFFBBF24);
                break;
            case "lifebuoy":
                Gfx.ring(ms, cx, cy, u * 0.45f, u * 1.0f, 0xFFF87171, 0xFFF87171);
                for (int i = 0; i < 4; i++) {
                    double a = Math.PI / 4 + i * Math.PI / 2;
                    Gfx.line(ms, cx + (float) Math.cos(a) * u * 0.5f, cy + (float) Math.sin(a) * u * 0.5f, cx + (float) Math.cos(a) * u * 0.95f, cy + (float) Math.sin(a) * u * 0.95f, u * 0.3f, i % 2 == 0 ? c : 0xFFF87171);
                }
                break;
            case "broom":
                Gfx.line(ms, cx + u * 0.9f, cy - u * 0.9f, cx - u * 0.2f, cy + u * 0.3f, u * 0.18f, 0xFFD6B58A);
                Gfx.poly(ms, new float[] {cx - u * 0.1f, cy + u * 0.1f, cx - u * 0.9f, cy + u * 0.9f, cx + u * 0.3f, cy + u * 0.8f, cx + u * 0.4f, cy + u * 0.3f}, c);
                break;
            case "wrench":
                Gfx.line(ms, cx - u * 0.8f, cy + u * 0.8f, cx + u * 0.4f, cy - u * 0.4f, u * 0.26f, c);
                Gfx.ring(ms, cx + u * 0.6f, cy - u * 0.6f, u * 0.28f, u * 0.55f, c, c);
                break;
            case "cross":
                Gfx.rrect(ms, cx - u * 0.35f, cy - u * 1.0f, u * 0.7f, u * 2.0f, u * 0.12f, 0xFFF87171);
                Gfx.rrect(ms, cx - u * 1.0f, cy - u * 0.35f, u * 2.0f, u * 0.7f, u * 0.12f, 0xFFF87171);
                break;
            case "shield":
                Gfx.poly(ms, new float[] {cx - u * 0.9f, cy - u * 0.9f, cx + u * 0.9f, cy - u * 0.9f, cx + u * 0.9f, cy + u * 0.1f, cx, cy + u * 1.1f, cx - u * 0.9f, cy + u * 0.1f}, 0xFF4DA3FF);
                Gfx.poly(ms, new float[] {cx, cy - u * 0.5f, cx + u * 0.25f, cy, cx + u * 0.6f, cy, cx + u * 0.3f, cy + u * 0.3f, cx + u * 0.4f, cy + u * 0.7f, cx, cy + u * 0.45f, cx - u * 0.4f, cy + u * 0.7f, cx - u * 0.3f, cy + u * 0.3f, cx - u * 0.6f, cy, cx - u * 0.25f, cy}, 0xFFFBBF24);
                break;
            case "steering":
                Gfx.ring(ms, cx, cy, u * 0.8f, u * 1.0f, c, c);
                Gfx.line(ms, cx - u * 0.8f, cy, cx + u * 0.8f, cy, u * 0.16f, c);
                Gfx.line(ms, cx, cy, cx, cy + u * 0.9f, u * 0.16f, c);
                Gfx.disc(ms, cx, cy, u * 0.25f, acc);
                break;
            case "plane":
                Gfx.poly(ms, new float[] {cx - u * 1.1f, cy, cx + u * 1.1f, cy - u * 0.15f, cx + u * 1.1f, cy + u * 0.15f}, c);
                Gfx.poly(ms, new float[] {cx - u * 0.1f, cy, cx - u * 0.6f, cy - u * 0.9f, cx + u * 0.1f, cy - u * 0.9f, cx + u * 0.5f, cy}, c);
                Gfx.poly(ms, new float[] {cx - u * 0.1f, cy, cx - u * 0.6f, cy + u * 0.9f, cx + u * 0.1f, cy + u * 0.9f, cx + u * 0.5f, cy}, c);
                break;
            case "fuel":
                Gfx.rrect(ms, cx - u * 0.7f, cy - u * 0.9f, u * 1.1f, u * 1.8f, u * 0.14f, c);
                Gfx.rrect(ms, cx - u * 0.5f, cy - u * 0.7f, u * 0.7f, u * 0.5f, u * 0.08f, acc);
                Gfx.line(ms, cx + u * 0.4f, cy - u * 0.4f, cx + u * 0.9f, cy - u * 0.1f, u * 0.14f, c);
                Gfx.line(ms, cx + u * 0.9f, cy - u * 0.1f, cx + u * 0.9f, cy + u * 0.6f, u * 0.14f, c);
                break;
            default: // servieta generica
                Gfx.rrect(ms, cx - u * 1.0f, cy - u * 0.5f, u * 2.0f, u * 1.4f, u * 0.18f, c);
                Gfx.rrect(ms, cx - u * 0.35f, cy - u * 0.9f, u * 0.7f, u * 0.4f, u * 0.1f, c);
                Gfx.rrect(ms, cx - u * 1.0f, cy + u * 0.05f, u * 2.0f, u * 0.14f, 0, acc);
                break;
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!detail.isEmpty() || page == 0 || page > 4) return true;
        scrollTarget = Math.max(0, Math.min(maxScroll(pageJobs().size()), scrollTarget - (float) delta * 40f));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && !detail.isEmpty()) {
            detail = "";
            adminOpen = false;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
