package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Mini-joc 2: sigurante. 1) gasesti siguranta defecta (cu multimetrul: masuratoare ceruta serverului; fara el ghicesti),
 * 2) alegi amperajul corect pentru sarcina circuitului, 3) verifici functionarea. Serverul stie care e defecta si ce amperaj e corect.
 */
final class ElecFuseScreen extends ElecGameScreen {
    private final int k;
    private final double nominal;
    private final JsonArray amps;
    private final boolean multi;
    private int phase;
    private int bad = -1;
    private int fuses;
    private boolean measureMode;
    private final String[] reading;
    private final boolean[] open;
    private String lcd = "----";
    private int lcdColor = MUT;
    private int sel = -1;
    private long phaseAt = System.currentTimeMillis();

    ElecFuseScreen(JsonObject spec) {
        super(spec, "Siguranțe");
        this.k = (int) num(spec, "k");
        this.nominal = num(spec, "nominal");
        this.amps = arr(spec, "amps");
        this.multi = bool(spec, "multimeter");
        this.phase = (int) num(spec, "phase");
        this.fuses = (int) num(spec, "fuses");
        this.reading = new String[k];
        this.open = new boolean[k];
        this.measureMode = multi;
    }

    private static final int[] AMP_COL = {0xFF22C55E, 0xFFEF4444, 0xFF9CA3AF, 0xFF3B82F6, 0xFFFACC15, 0xFFE5E7EB, 0xFFF97316, 0xFFA855F7};

    @Override
    protected void play(GuiGraphics ms, float t, float x, float y, float w, float h) {
        String head = phase == 0 ? "1 · IDENTIFICĂ SIGURANȚA DEFECTĂ" : phase == 1 ? "2 · ALEGE AMPERAJUL DE ÎNLOCUIRE" : "3 · VERIFICĂ FUNCȚIONAREA";
        Gfx.text(ms, head, x + 8, y + 14, 8, YEL, 0, true, 1f);
        // tablou cu sina DIN
        float bx = x + 6;
        float by = y + 34;
        float bw = 372;
        float bh = 150;
        Gfx.rrect(ms, bx, by, bw, bh, 6, 0xFF05070A);
        Gfx.rrect(ms, bx + 1, by + 1, bw - 2, bh - 2, 5, 0xFF1B222B, 0xFF12171D);
        Gfx.rrect(ms, bx + 8, by + 72, bw - 16, 7, 2, 0xFF59636F);
        Gfx.text(ms, "TABLOU DE SIGURANȚE", bx + 10, by + 14, 5.6f, MUT, 0, true, 0.8f);
        float gap = (bw - 30) / k;
        for (int i = 0; i < k; i++) {
            float fx = bx + 15 + i * gap + gap / 2f;
            boolean removed = phase == 1 && i == bad;
            boolean fresh = phase == 2 && i == bad;
            boolean hot = !locked() && over(fx - gap / 2f + 2, by + 24, gap - 4, 104);
            Gfx.rrect(ms, fx - 15, by + 28, 30, 92, 4, 0xFF05070A);
            Gfx.rrect(ms, fx - 14, by + 29, 28, 90, 3, hot ? 0xFF2A3440 : 0xFF222B35);
            if (!removed) {
                int glass = fresh ? 0x8845E08F : 0x66B8D6E8;
                Gfx.rrect(ms, fx - 7, by + 38, 14, 62, 4, 0xFF59636F);
                Gfx.rrect(ms, fx - 6, by + 44, 12, 50, 3, glass);
                Gfx.line(ms, fx, by + 47, fx, by + 91, 1.2f, fresh ? GOOD : 0xFFE9A23B);
                Gfx.rrect(ms, fx - 7, by + 96, 14, 6, 2, 0xFFB8C0CA);
                Gfx.rrect(ms, fx - 7, by + 36, 14, 6, 2, 0xFFB8C0CA);
                if (fresh) Gfx.ring(ms, fx, by + 70, 16, 22 + 2 * (float) Math.sin(t * 5), 0x3345E08F, 0x0045E08F);
            } else {
                Gfx.rrect(ms, fx - 6, by + 60, 12, 20, 2, 0xFF0B0E12);
                if (((int) (t * 6)) % 2 == 0) Gfx.disc(ms, fx, by + 66, 2.4f, 0xFFFFD34D);
            }
            Gfx.text(ms, "F" + (i + 1), fx, by + 112, 6.4f, TXT, 1, true, 0.4f);
            if (reading[i] != null) {
                Gfx.rrect(ms, fx - 18, by + 126, 36, 14, 3, 0xFF05070A);
                Gfx.text(ms, reading[i], fx, by + 136, 5.8f, open[i] ? BAD : GOOD, 1, true, 0.2f);
            }
            final int idx = i;
            if (phase == 0 && !locked()) hitL(fx - gap / 2f + 2, by + 24, gap - 4, 104, () -> clickFuse(idx));
        }
        // multimetru
        float mx = x + 396;
        float my = y + 30;
        Gfx.rrect(ms, mx, my, 188, 108, 8, 0xFF05070A);
        Gfx.rrect(ms, mx + 1, my + 1, 186, 106, 7, multi ? 0xFFE8B21A : 0xFF3A3F46, multi ? 0xFFC98F0A : 0xFF2B3036);
        Gfx.rrect(ms, mx + 10, my + 10, 168, 38, 4, 0xFF9FC4A8);
        Gfx.rrect(ms, mx + 12, my + 12, 164, 34, 3, 0xFFB6D6BC);
        Gfx.text(ms, multi ? lcd : "NU AI MULTIMETRU", mx + 170, my + 38, multi ? 16 : 8, multi ? (lcdColor == MUT ? 0xFF2A3A2E : lcdColor == BAD ? 0xFF9B1C12 : 0xFF14532D) : 0xFF3A4A3E, 2, true, 0.6f);
        Gfx.disc(ms, mx + 94, my + 80, 18, 0xFF05070A);
        Gfx.disc(ms, mx + 94, my + 80, 15, 0xFF2A3038);
        double ang = multi ? (measureMode ? -0.9 : 0.4) : 0;
        Gfx.line(ms, mx + 94, my + 80, mx + 94 + (float) Math.sin(ang) * 13, my + 80 - (float) Math.cos(ang) * 13, 2.4f, YEL);
        Gfx.text(ms, "Ω", mx + 60, my + 98, 7, 0xFF3A2A06, 1, true, 0);
        Gfx.text(ms, "A", mx + 128, my + 98, 7, 0xFF3A2A06, 1, true, 0);
        Gfx.disc(ms, mx + 24, my + 96, 4, BAD);
        Gfx.disc(ms, mx + 40, my + 96, 4, 0xFF05070A);
        // actiuni dupa faza
        float ay = y + 146;
        if (phase == 0) {
            btn(ms, "MĂSOARĂ (MULTIMETRU)", mx, ay, 188, 22, multi && !locked(), measureMode, () -> measureMode = true);
            btn(ms, "DECLARĂ DEFECTĂ", mx, ay + 28, 188, 22, !locked(), !measureMode, () -> measureMode = false);
            wrap(ms, multi ? "Cu multimetrul masori rezistenta: o siguranta buna arata sub 1 ohm, cea arsa arata OL. Apoi declar-o defecta."
                : "Fara multimetru nu poti masura: ghicesti. Cumpara-l din Echipament pentru siguranta.", mx, ay + 66, 188, 6.2f, MUT, 4);
        } else if (phase == 1) {
            Gfx.text(ms, "SARCINĂ CIRCUIT: " + nominal + " A", mx, ay + 8, 7.4f, TXT, 0, true, 0.5f);
            Gfx.text(ms, "Siguranțe în inventar: " + fuses, mx, ay + 20, 6f, fuses > 0 ? MUT : BAD, 0);
            for (int i = 0; i < amps.size() && i < 8; i++) {
                float cx = mx + (i % 4) * 48;
                float cy = ay + 28 + (i / 4) * 40;
                int a = amps.get(i).getAsInt();
                boolean sl = sel == a;
                boolean hot = over(cx, cy, 44, 36);
                Gfx.rrect(ms, cx, cy, 44, 36, 5, sl ? YEL : 0xFF05070A);
                Gfx.rrect(ms, cx + 1, cy + 1, 42, 34, 4, hot ? 0xFF2A3440 : PANEL2);
                Gfx.rrect(ms, cx + 14, cy + 5, 16, 18, 3, AMP_COL[i % AMP_COL.length]);
                Gfx.text(ms, a + " A", cx + 22, cy + 32, 6.2f, TXT, 1, true, 0.2f);
                final int av = a;
                if (!locked()) hitL(cx, cy, 44, 36, () -> {
                    sel = av;
                    message = "";
                    send("amp", "v", String.valueOf(av));
                });
            }
        } else {
            Gfx.text(ms, "SIGURANȚĂ MONTATĂ", mx, ay + 8, 7.4f, GOOD, 0, true, 0.5f);
            double v = Math.min(1, (System.currentTimeMillis() - phaseAt) / 1800.0);
            Gfx.text(ms, "TENSIUNE: " + Math.round(230 * v) + " V", mx, ay + 22, 6.6f, TXT, 0, true, 0.4f);
            Gfx.bar(ms, mx, ay + 28, 188, 8, (float) v, 0xFF05070A, v >= 1 ? GOOD : YEL);
            btn(ms, "VERIFICĂ FUNCȚIONAREA", mx, ay + 46, 188, 26, !locked() && v >= 1, true, () -> send("verify"));
        }
        // legenda jos
        Gfx.text(ms, phase == 0 ? (measureMode ? "Mod: MĂSURARE — apasă pe o siguranță" : "Mod: DECLARARE — apasă siguranța pe care o consideri arsă") : "",
            x + 8, y + h - 10, 6.4f, MUT, 0);
    }

    private void clickFuse(int i) {
        if (phase != 0 || locked()) return;
        if (measureMode) {
            send("measure", "i", String.valueOf(i));
            sndTick(1.3f);
        } else {
            send("pick", "i", String.valueOf(i));
        }
    }

    @Override
    protected void onResult(JsonObject d) {
        String op = str(d, "op");
        boolean ok = bool(d, "ok");
        if (d.has("fuses")) fuses = (int) num(d, "fuses");
        if ("measure".equals(op) && ok) {
            int i = (int) num(d, "i");
            if (i >= 0 && i < k) {
                reading[i] = str(d, "reading");
                open[i] = bool(d, "open");
                lcd = reading[i];
                lcdColor = open[i] ? BAD : GOOD;
                if (open[i]) sndBad();
                else sndBeep(1.8f);
            }
        } else if ("pick".equals(op) && ok) {
            phase = 1;
            bad = (int) num(d, "bad");
            phaseAt = System.currentTimeMillis();
        } else if ("amp".equals(op) && ok) {
            phase = 2;
            phaseAt = System.currentTimeMillis();
        }
    }
}
