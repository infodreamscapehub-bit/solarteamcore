package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Mini-joc 4: calibrarea tensiunii. Tii cursorul (miscarea mouse-ului pe verticala) in intervalul tinta care se deplaseaza, pe un osciloscop.
 * Scorul = procentul de timp petrecut in interval; clientul trimite doar esantioanele (10/s), serverul reface curba tinta din parametrii lui
 * si calculeaza scorul, verifica durata reala si respinge urmele suspect de perfecte.
 */
final class ElecCalibScreen extends ElecGameScreen {
    private double dur;
    private double a1;
    private double a2;
    private double p1;
    private double p2;
    private double amp1;
    private double amp2;
    private double half;
    private double need;
    private int attempt;
    private int attempts;
    private int state;                 // 0 pregatit, 1 numaratoare, 2 ruleaza, 3 trimis
    private long stateAt = System.currentTimeMillis();
    private final List<Integer> samples = new ArrayList<>();
    private double value = 0.5;

    private static final float SX = 34;
    private static final float SY = 92;
    private static final float SW = 420;
    private static final float SH = 176;

    ElecCalibScreen(JsonObject spec) {
        super(spec, "Calibrare tensiune");
        load(spec);
    }

    private void load(JsonObject d) {
        dur = num(d, "dur");
        a1 = num(d, "a1");
        a2 = num(d, "a2");
        p1 = num(d, "p1");
        p2 = num(d, "p2");
        amp1 = num(d, "amp1");
        amp2 = num(d, "amp2");
        half = num(d, "half");
        need = num(d, "need");
        attempt = (int) num(d, "attempt");
        attempts = (int) num(d, "attempts");
        samples.clear();
        state = 0;
        stateAt = System.currentTimeMillis();
    }

    private double center(double t) {
        double c = 0.5 + amp1 * Math.sin(a1 * t + p1) + amp2 * Math.sin(a2 * t + p2);
        return Math.max(half + 0.02, Math.min(1 - half - 0.02, c));
    }

    private float yOf(double v) {
        return (float) (SY + SH - v * SH);
    }

    @Override
    protected void play(GuiGraphics ms, float t, float x, float y, float w, float h) {
        long now = System.currentTimeMillis();
        // valoarea cursorului = pozitia mouse-ului pe verticala in zona osciloscopului
        value = Math.max(0, Math.min(1, 1 - (lmy - SY) / SH));
        double el = 0;
        if (state == 1 && now - stateAt >= 3000) {
            state = 2;
            stateAt = now;
            send("begin");
        }
        if (state == 2) {
            el = (now - stateAt) / 1000.0;
            int want = (int) Math.floor(el * 10);
            int total = (int) Math.round(dur * 10);
            while (samples.size() < Math.min(want + 1, total)) samples.add((int) Math.round(value * 100));
            if (el >= dur && samples.size() >= total) {
                state = 3;
                int[] a = new int[samples.size()];
                for (int i = 0; i < a.length; i++) a[i] = samples.get(i);
                sendAns("submit", a);
                message = "Se verifică calibrarea...";
                messageColor = MUT;
            }
        }
        Gfx.text(ms, "ȚINE INDICATORUL ÎN INTERVALUL GALBEN  ·  ÎNCERCAREA " + attempt + " / " + attempts, x + 8, y + 14, 7.4f, YEL, 0, true, 0.8f);
        // osciloscop
        Gfx.rrect(ms, SX - 2, SY - 2, SW + 4, SH + 4, 6, 0xFF05070A);
        Gfx.rrect(ms, SX, SY, SW, SH, 5, 0xFF0B1410, 0xFF08100C);
        for (int i = 1; i < 8; i++) Gfx.line(ms, SX, SY + SH * i / 8f, SX + SW, SY + SH * i / 8f, 0.6f, 0x2245E08F);
        for (int i = 1; i < 10; i++) Gfx.line(ms, SX + SW * i / 10f, SY, SX + SW * i / 10f, SY + SH, 0.6f, 0x2245E08F);
        double nowT = state == 2 ? el : 0;
        float nowX = SX + SW * 0.72f;
        float pps = SW / 4.2f;
        // coridorul tinta
        for (float px = SX; px < SX + SW; px += 3f) {
            double tt = nowT + (px - nowX) / pps;
            if (tt < 0 || tt > dur) continue;
            double c = center(tt);
            float top = yOf(Math.min(1, c + half));
            float bot = yOf(Math.max(0, c - half));
            boolean future = px > nowX;
            Gfx.rrect(ms, px, top, 3.2f, bot - top, 0, future ? 0x40FFC21A : 0x66FFC21A);
        }
        // urma jucatorului
        float lx = -1;
        float ly = -1;
        for (int i = 0; i < samples.size(); i++) {
            double tt = i / 10.0;
            float px = nowX + (float) ((tt - nowT) * pps);
            if (px < SX) continue;
            float py = yOf(samples.get(i) / 100.0);
            if (lx >= 0) Gfx.line(ms, lx, ly, px, py, 1.8f, 0xFF45E08F);
            lx = px;
            ly = py;
        }
        float cy = yOf(value);
        if (lx >= 0) Gfx.line(ms, lx, ly, nowX, cy, 1.8f, 0xFF45E08F);
        Gfx.line(ms, nowX, SY, nowX, SY + SH, 1f, 0x66FFFFFF);
        boolean inBand = state == 2 && Math.abs(value - center(nowT)) <= half;
        Gfx.disc(ms, nowX, cy, 6, 0xFF05070A);
        Gfx.disc(ms, nowX, cy, 4.6f, state == 2 ? (inBand ? GOOD : BAD) : YEL);
        // indicator vertical (regulator)
        float gx = 486;
        Gfx.rrect(ms, gx, SY, 30, SH, 6, 0xFF05070A);
        Gfx.rrect(ms, gx + 2, SY + 2, 26, SH - 4, 5, 0xFF12171D);
        if (state == 2) {
            double c = center(nowT);
            Gfx.rrect(ms, gx + 3, yOf(Math.min(1, c + half)), 24, yOf(Math.max(0, c - half)) - yOf(Math.min(1, c + half)), 2, 0x66FFC21A);
        }
        Gfx.rrect(ms, gx - 4, cy - 4, 38, 8, 3, state == 2 ? (inBand ? GOOD : BAD) : YEL);
        Gfx.text(ms, Math.round(value * 260) + " V", gx + 15, SY + SH + 16, 7, TXT, 1, true, 0.4f);
        // scor live
        int hit = 0;
        for (int i = 0; i < samples.size(); i++) if (Math.abs(samples.get(i) / 100.0 - center(i / 10.0)) <= half) hit++;
        double live = samples.isEmpty() ? 0 : hit / (double) samples.size();
        Gfx.text(ms, "ÎN INTERVAL", 540, SY + 10, 5.6f, MUT, 0, true, 0.6f);
        Gfx.text(ms, Math.round(live * 100) + "%", 540, SY + 34, 22, live >= need ? GOOD : TXT, 0, true, 0.4f);
        Gfx.text(ms, "MINIM " + Math.round(need * 100) + "%", 540, SY + 48, 6.2f, YEL, 0, true, 0.5f);
        Gfx.bar(ms, 540, SY + 56, 70, 6, (float) live, 0xFF05070A, live >= need ? GOOD : ORG);
        Gfx.text(ms, "TIMP RĂMAS", 540, SY + 84, 5.6f, MUT, 0, true, 0.6f);
        Gfx.text(ms, state == 2 ? mmss(Math.max(0, dur - el)) : mmss(dur), 540, SY + 104, 16, TXT, 0, true, 0.4f);
        // stari
        if (state == 0) {
            Gfx.text(ms, "Mută mouse-ul în sus/jos ca să ții indicatorul în zona galbenă.", x + w / 2f, y + 44, 7f, MUT, 1);
            btn(ms, "ÎNCEPE CALIBRAREA", x + w / 2f - 80, y + h - 30, 160, 24, !locked(), true, () -> {
                state = 1;
                stateAt = System.currentTimeMillis();
            });
        } else if (state == 1) {
            long left = 3000 - (now - stateAt);
            int n = (int) Math.max(1, Math.ceil(left / 1000.0));
            Gfx.text(ms, String.valueOf(n), SX + SW / 2f, SY + SH / 2f + 20, 56, YEL, 1, true, 0f);
            Gfx.text(ms, "PREGĂTEȘTE-TE", x + w / 2f, y + 44, 8, YEL, 1, true, 1f);
        } else if (state == 2) {
            Gfx.bar(ms, SX, SY - 16, SW, 5, (float) Math.min(1, el / dur), 0xFF05070A, YEL);
        }
    }

    @Override
    protected void onResult(JsonObject d) {
        if (bool(d, "restart")) load(d);
        else if (!bool(d, "fin") && state == 3) state = 0;
    }
}
