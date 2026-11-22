package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Panoul electric al interventiei (se deschide la locatie): schema circuitului cu siguranteuri, cabluri, conectori si regulator,
 * indicator de tensiune, indicator de functionare, starea fiecarei componente si etapele (mini-jocurile) in ordine.
 */
public final class ElecBoardScreen extends ElecScreen {
    private boolean confirmQuit;
    private long confirmAt;

    public ElecBoardScreen() {
        super("Panou electric");
    }

    private static String state(JsonObject b, String g) {
        for (int i = 0; i < arr(b, "stages").size(); i++) {
            JsonObject s = arr(b, "stages").get(i).getAsJsonObject();
            if (g.equals(str(s, "g"))) return str(s, "st");
        }
        return "none";
    }

    @Override
    protected void content(GuiGraphics ms, float t) {
        JsonObject b = ElecClient.board;
        if (b == null) {
            Minecraft.getInstance().setScreen(null);
            return;
        }
        JsonArray stages = arr(b, "stages");
        int n = stages.size();
        int idx = (int) num(b, "idx");
        double volt = num(b, "volt");
        boolean working = n > 0 && idx >= n;
        double frac = n == 0 ? 0 : (double) idx / n;
        head(ms, "PANOU ELECTRIC", str(b, "name") + "  ·  " + str(b, "loc"), true);
        diffMeter(ms, W - 150, 20, (int) num(b, "diff"), 10);
        if (System.currentTimeMillis() - confirmAt > 3000) confirmQuit = false;
        btnC(ms, confirmQuit ? "SIGUR?" : "RENUNȚĂ", W - 118, 12, 70, 18, true, confirmQuit ? BAD : PANEL2, TXT, () -> {
            if (confirmQuit) {
                op("el_abandon");
                ElecClient.board = null;
                Minecraft.getInstance().setScreen(null);
            } else {
                confirmQuit = true;
                confirmAt = System.currentTimeMillis();
            }
        });
        schematic(ms, b, t, 14, 44, 410, 202);
        equipStrip(ms, b, 14, 250, 410, 18);
        gaugeBox(ms, b, t, 434, 44, 192, 106, volt, frac, working);
        progressBox(ms, b, 434, 154, 192, 114, n, idx);
        // etape
        float gap = 8;
        float cw = (W - 28 - gap * Math.max(0, n - 1)) / Math.max(1, n);
        for (int i = 0; i < n; i++) {
            JsonObject s = stages.get(i).getAsJsonObject();
            float cx = 14 + i * (cw + gap);
            stageCard(ms, s, i, cx, 276, cw, 76, bool(b, "busy"));
        }
    }

    private void stageCard(GuiGraphics ms, JsonObject s, int i, float x, float y, float w, float h, boolean busy) {
        String st = str(s, "st");
        boolean done = "done".equals(st);
        boolean next = "next".equals(st) || "active".equals(st);
        int col = done ? GOOD : next ? YEL : FAINT;
        Gfx.rrect(ms, x, y, w, h, 6, next ? YEL : LINE);
        Gfx.rrect(ms, x + 1, y + 1, w - 2, h - 2, 5, done ? 0xFF0E1D14 : PANEL, done ? 0xFF0A140E : 0xFF0F1318);
        Gfx.disc(ms, x + 20, y + 22, 12, 0xFF05070A);
        Gfx.disc(ms, x + 20, y + 22, 10, done ? GOOD : next ? YEL : 0xFF2C343F);
        if (done) {
            Gfx.line(ms, x + 15, y + 22, x + 19, y + 26, 2.4f, 0xFF04170B);
            Gfx.line(ms, x + 19, y + 26, x + 26, y + 17, 2.4f, 0xFF04170B);
        } else {
            Gfx.text(ms, String.valueOf(i + 1), x + 20, y + 26, 10, next ? 0xFF1A1405 : MUT, 1, true, 0);
        }
        Gfx.text(ms, clip(str(s, "name").toUpperCase(), w - 52, 7.4f), x + 38, y + 20, 7.4f, col == FAINT ? MUT : TXT, 0, true, 0.5f);
        String lab = done ? "COMPLETĂ" + (s.has("q") ? "  ·  " + Math.round(num(s, "q") * 100) + "%" : "") : next ? (busy ? "ÎN CURS" : "URMĂTOAREA ETAPĂ") : "BLOCATĂ";
        Gfx.text(ms, lab, x + 38, y + 32, 5.8f, col, 0, true, 0.5f);
        if (next) {
            btn(ms, busy ? "CONTINUĂ" : "PORNEȘTE", x + 10, y + h - 30, w - 20, 22, true, true, () -> op("el_stage"));
        } else {
            Gfx.rrect(ms, x + 10, y + h - 26, w - 20, 4, 2, 0xFF05070A);
            if (done) Gfx.rrect(ms, x + 10, y + h - 26, w - 20, 4, 2, GOOD);
        }
    }

    // ------------------------------------------------------------------ schema

    private void schematic(GuiGraphics ms, JsonObject b, float t, float x, float y, float w, float h) {
        box(ms, x, y, w, h, "SCHEMA CIRCUITULUI");
        String sFuse = state(b, "fuse");
        String sCirc = state(b, "circuit");
        String sSeq = state(b, "seq");
        String sCal = state(b, "calib");
        boolean fuseOk = "done".equals(sFuse) || "none".equals(sFuse);
        boolean circOk = "done".equals(sCirc) || "none".equals(sCirc);
        boolean seqOk = "done".equals(sSeq) || "none".equals(sSeq);
        boolean calOk = "done".equals(sCal) || "none".equals(sCal);
        int seed = (int) num(b, "seed");
        int badFuse = seed % 4;
        float busY = y + 78;
        // sursa
        Gfx.disc(ms, x + 38, busY, 19, 0xFF05070A);
        Gfx.disc(ms, x + 38, busY, 16, 0xFF1A2028);
        for (int i = -10; i < 10; i++) {
            float a0 = x + 38 + i * 1.4f;
            float a1 = x + 38 + (i + 1) * 1.4f;
            Gfx.line(ms, a0, busY - (float) Math.sin(i * 0.45 + t * 4) * 6, a1, busY - (float) Math.sin((i + 1) * 0.45 + t * 4) * 6, 1.6f, YEL);
        }
        Gfx.text(ms, volts(num(b, "volt")), x + 38, busY + 31, 6f, YEL, 1, true, 0.4f);
        // bara principala
        boolean live = fuseOk;
        wireLine(ms, x + 57, busY, x + w - 30, busY, live ? BLUE : 0xFF3A4350, t, live);
        // sigurante
        for (int i = 0; i < 4; i++) {
            float fx = x + 96 + i * 42;
            boolean blown = !fuseOk && i == badFuse;
            boolean pending = !fuseOk;
            int c = blown ? ((int) (t * 3) % 2 == 0 ? BAD : 0xFF7A241C) : (pending ? 0xFF59636F : GOOD);
            Gfx.rrect(ms, fx - 8, busY - 16, 16, 32, 3, 0xFF05070A);
            Gfx.rrect(ms, fx - 7, busY - 15, 14, 30, 2, 0xFF222B35);
            Gfx.rrect(ms, fx - 4, busY - 11, 8, 22, 2, c);
            Gfx.text(ms, "F" + (i + 1), fx, busY - 20, 5.2f, MUT, 1, true, 0.2f);
        }
        // releu (secventa)
        float rx = x + w - 96;
        Gfx.rrect(ms, rx - 22, y + 24, 44, 28, 4, 0xFF05070A);
        Gfx.rrect(ms, rx - 21, y + 25, 42, 26, 3, 0xFF1A2028);
        Gfx.rrect(ms, rx - 15, y + 31, 30, 14, 2, seqOk ? 0xFF0E3A20 : 0xFF3A1210);
        Gfx.line(ms, rx - 15, y + 38, rx + 15, y + 38, 1.4f, seqOk ? GOOD : 0xFF7A241C);
        Gfx.disc(ms, rx + 14, y + 29, 2.4f, seqOk ? GOOD : (int) (t * 4) % 2 == 0 ? BAD : 0xFF3A1210);
        Gfx.text(ms, "RELEU", rx, y + 62, 5.2f, MUT, 1, true, 0.3f);
        Gfx.line(ms, rx, y + 52, rx, busY, 1.4f, seqOk ? BLUE : 0xFF3A4350);
        // ramificatii spre sarcini
        for (int i = 0; i < 4; i++) {
            float bx = x + 84 + i * 76;
            boolean faulty = !circOk && (i == seed % 4 || i == (seed / 4) % 4);
            int c = circOk ? BLUE : (faulty ? 0xFF3A4350 : 0xFF59636F);
            wireLine(ms, bx, busY, bx, y + 138, c, t, circOk);
            Gfx.disc(ms, bx, busY, 4, 0xFF05070A);
            Gfx.disc(ms, bx, busY, 2.8f, circOk ? GOOD : faulty ? BAD : 0xFF59636F);
            if (faulty) {
                Gfx.line(ms, bx - 6, y + 104, bx + 6, y + 116, 2f, BAD);
                Gfx.line(ms, bx + 6, y + 104, bx - 6, y + 116, 2f, BAD);
            }
            // sarcina
            boolean on = circOk && fuseOk;
            Gfx.disc(ms, bx, y + 150, 11, 0xFF05070A);
            Gfx.disc(ms, bx, y + 150, 9, on ? 0xFFFFE27A : 0xFF2A323C);
            if (on) Gfx.ring(ms, bx, y + 150, 10, 16 + 1.6f * (float) Math.sin(t * 5 + i), 0x44FFE27A, 0x00FFE27A);
        }
        // regulator (calibrare)
        float dx = x + w - 52;
        float dy = y + 132;
        Gfx.disc(ms, dx, dy, 22, 0xFF05070A);
        Gfx.disc(ms, dx, dy, 19, 0xFF1A2028);
        Gfx.sector(ms, dx, dy, 14, 17, 150, 390, 0xFF2C343F, 0xFF2C343F);
        Gfx.sector(ms, dx, dy, 14, 17, 330, 390, GOOD, GOOD);
        double ang = calOk ? -90 : -90 + Math.sin(t * 7) * 55 + Math.sin(t * 3.1) * 25;
        Gfx.line(ms, dx, dy, dx + (float) Math.cos(Math.toRadians(ang)) * 13, dy + (float) Math.sin(Math.toRadians(ang)) * 13, 2f, calOk ? GOOD : BAD);
        Gfx.disc(ms, dx, dy, 2.4f, TXT);
        Gfx.text(ms, "REGULATOR", dx, dy + 33, 5.2f, MUT, 1, true, 0.3f);
        // legenda componentelor
        float ly = y + 176;
        JsonArray st = arr(b, "stages");
        float lx = x + 12;
        for (int i = 0; i < st.size(); i++) {
            JsonObject s = st.get(i).getAsJsonObject();
            String g = str(s, "g");
            String name = "circuit".equals(g) ? "Conectori" : "fuse".equals(g) ? "Siguranțe" : "seq".equals(g) ? "Releu" : "Regulator";
            String stt = str(s, "st");
            boolean done = "done".equals(stt);
            boolean act = "active".equals(stt);
            Gfx.disc(ms, lx + 3, ly + 3, 3, done ? GOOD : act ? YEL : BAD);
            Gfx.text(ms, name + ": " + (done ? "OK" : act ? "ÎN LUCRU" : "DEFECT"), lx + 10, ly + 6, 5.8f, done ? GOOD : act ? YEL : BAD, 0, true, 0.3f);
            lx += 94;
        }
    }

    private void wireLine(GuiGraphics ms, float x0, float y0, float x1, float y1, int col, float t, boolean flowing) {
        Gfx.line(ms, x0, y0, x1, y1, 4f, 0xFF05070A);
        Gfx.line(ms, x0, y0, x1, y1, 2.4f, col);
        if (!flowing) return;
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        for (int k = 0; k < 4; k++) {
            float u = ((t * 0.5f) + k * 0.25f) % 1f;
            Gfx.disc(ms, x0 + (x1 - x0) * u, y0 + (y1 - y0) * u, 1.8f, 0xCCFFFFFF);
        }
    }

    private void equipStrip(GuiGraphics ms, JsonObject b, float x, float y, float w, float h) {
        JsonObject e = obj(b, "equip");
        String[][] items = {{"trusa", "TRUSĂ"}, {"multimetru", "MULTIMETRU"}, {"cleste", "CLEȘTE"}, {"protectie", "PROTECȚIE"}, {"cablu", "CABLU"}, {"siguranta", "SIGURANȚE"}};
        float cw = (w - 5 * 4) / 6f;
        for (int i = 0; i < items.length; i++) {
            int n = (int) num(e, items[i][0]);
            float cx = x + i * (cw + 4);
            Gfx.rrect(ms, cx, y, cw, h, 4, n > 0 ? 0xFF3A2E08 : 0xFF161B22);
            Gfx.rrect(ms, cx, y + h - 2, cw, 2, 1, n > 0 ? YEL : 0xFF2C343F);
            Gfx.text(ms, items[i][1] + (n > 1 || "cablu".equals(items[i][0]) || "siguranta".equals(items[i][0]) ? " x" + n : ""), cx + cw / 2f, y + 11, 5.4f, n > 0 ? YEL : FAINT, 1, true, 0.2f);
        }
    }

    private void gaugeBox(GuiGraphics ms, JsonObject b, float t, float x, float y, float w, float h, double volt, double frac, boolean working) {
        box(ms, x, y, w, h, "TENSIUNE · STARE");
        float cx = x + 72;
        float cy = y + 82;
        Gfx.sector(ms, cx, cy, 44, 52, 180, 360, 0xFF05070A, 0xFF05070A);
        Gfx.sector(ms, cx, cy, 45, 51, 180, 360, 0xFF1F262F, 0xFF1F262F);
        float f = (float) Math.max(0, Math.min(1, frac));
        if (f > 0.01f) Gfx.sector(ms, cx, cy, 45, 51, 180, 180 + 180 * f, working ? GOOD : YEL, working ? GOOD : ORG);
        double ang = Math.toRadians(180 + 180 * f + (working ? 0 : Math.sin(t * 9) * 2.5));
        Gfx.line(ms, cx, cy, cx + (float) Math.cos(ang) * 44, cy + (float) Math.sin(ang) * 44, 2.2f, TXT);
        Gfx.disc(ms, cx, cy, 4, 0xFF05070A);
        Gfx.disc(ms, cx, cy, 2.6f, YEL);
        Gfx.text(ms, volts(volt * frac), cx, cy - 16, 8.4f, working ? GOOD : TXT, 1, true, 0.5f);
        // LED functionare
        float lx = x + w - 52;
        boolean blink = (int) (t * 2.5) % 2 == 0;
        Gfx.disc(ms, lx, y + 44, 12, 0xFF05070A);
        Gfx.disc(ms, lx, y + 44, 9.5f, working ? GOOD : (blink ? BAD : 0xFF6B1F18));
        if (working) Gfx.ring(ms, lx, y + 44, 10, 15 + (float) Math.sin(t * 5) * 1.5f, 0x5545E08F, 0x0045E08F);
        Gfx.text(ms, working ? "FUNCȚIONAL" : "DEFECT", lx, y + 70, 5.6f, working ? GOOD : BAD, 1, true, 0.5f);
    }

    private void progressBox(GuiGraphics ms, JsonObject b, float x, float y, float w, float h, int n, int idx) {
        box(ms, x, y, w, h, "PROGRES INTERVENȚIE");
        int pct = (int) num(b, "percent");
        Gfx.text(ms, pct + "%", x + 12, y + 44, 24, pct >= 100 ? GOOD : YEL, 0, true, 0.4f);
        Gfx.text(ms, "ETAPE " + idx + " / " + n, x + w - 12, y + 44, 7.2f, TXT, 2, true, 0.5f);
        Gfx.bar(ms, x + 12, y + 52, w - 24, 8, pct / 100f, 0xFF05070A, pct >= 100 ? GOOD : YEL);
        double el = num(b, "elapsed") + (System.currentTimeMillis() - ElecClient.boardAt) / 1000.0;
        double est = num(b, "est");
        Gfx.text(ms, "TIMP", x + 12, y + 76, 5.6f, MUT, 0, true, 0.6f);
        Gfx.text(ms, mmss(el) + " / " + mmss(est), x + w - 12, y + 76, 7f, el > est ? ORG : TXT, 2, true, 0.4f);
        Gfx.text(ms, "GREȘELI", x + 12, y + 92, 5.6f, MUT, 0, true, 0.6f);
        pips(ms, x + w - 12 - 3 * 11 + 5, y + 89, 3, Math.min(3, (int) num(b, "mistakes")));
        Gfx.text(ms, "REÎNCERCĂRI ETAPĂ", x + 12, y + 106, 5.6f, MUT, 0, true, 0.6f);
        Gfx.text(ms, String.valueOf((int) num(b, "retriesLeft")), x + w - 12, y + 106, 7f, num(b, "retriesLeft") > 0 ? GOOD : BAD, 2, true, 0.2f);
    }
}
