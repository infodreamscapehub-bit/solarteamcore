package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Mini-joc 3: secventa electrica cu dificultate progresiva. Intreruptoarele se aprind intr-o ordine; o memorezi si o repeti.
 * Serverul trimite doar prefixul rundei curente (3, 4, 5... semnale) si verifica fiecare runda, cu timp minim si greseli limitate.
 */
final class ElecSeqScreen extends ElecGameScreen {
    private final int symbols;
    private final int rounds;
    private final long showMs;
    private int round;
    private int[] show;
    private long showStart;
    private final List<Integer> input = new ArrayList<>();
    private int mode;            // 0 = arata, 1 = repeta, 2 = se verifica
    private int lastBeep = -1;
    private final long[] litAt;

    ElecSeqScreen(JsonObject spec) {
        super(spec, "Secvență electrică");
        this.symbols = (int) num(spec, "symbols");
        this.rounds = (int) num(spec, "rounds");
        this.showMs = (long) num(spec, "showMs");
        this.litAt = new long[Math.max(6, symbols)];
        startRound((int) num(spec, "round"), arr(spec, "show"));
    }

    private void startRound(int r, JsonArray s) {
        round = r;
        show = new int[s.size()];
        for (int i = 0; i < show.length; i++) show[i] = s.get(i).getAsInt();
        input.clear();
        mode = 0;
        lastBeep = -1;
        showStart = System.currentTimeMillis() + 900;
    }

    private long slot() {
        return showMs + 220;
    }

    @Override
    protected void play(GuiGraphics ms, float t, float x, float y, float w, float h) {
        long now = System.currentTimeMillis();
        int lit = -1;
        if (mode == 0) {
            long el = now - showStart;
            if (el >= 0) {
                int idx = (int) (el / slot());
                if (idx >= show.length) {
                    mode = 1;
                    sndTick(1.6f);
                } else if (el % slot() < showMs) {
                    lit = show[idx];
                    if (lastBeep != idx) {
                        lastBeep = idx;
                        sndBeep(0.6f + 0.17f * lit);
                    }
                }
            }
        }
        for (int i = 0; i < litAt.length; i++) if (now - litAt[i] < 260) lit = lit < 0 ? i : lit;
        String st = mode == 0 ? "MEMOREAZĂ ORDINEA" : mode == 1 ? "REPETĂ SECVENȚA" : "SE VERIFICĂ...";
        Gfx.text(ms, "RUNDA " + round + " / " + rounds, x + 8, y + 16, 9, YEL, 0, true, 1f);
        Gfx.text(ms, st, x + w - 8, y + 16, 9, mode == 0 ? BLUE : mode == 1 ? TXT : MUT, 2, true, 1f);
        // progres rundei: cate puncte are secventa
        float dw = Math.min(26f, (w - 40) / Math.max(1, round));
        float sx = x + w / 2f - dw * round / 2f + dw / 2f;
        for (int i = 0; i < round; i++) {
            boolean done = i < input.size();
            Gfx.disc(ms, sx + i * dw, y + 40, 6.5f, 0xFF05070A);
            Gfx.disc(ms, sx + i * dw, y + 40, 5.2f, done ? WIRE[input.get(i) % WIRE.length] : 0xFF2C343F);
        }
        // panou cu intreruptoare
        int cols = symbols <= 4 ? 4 : 3;
        int rows = (symbols + cols - 1) / cols;
        float cw = 120;
        float ch = rows == 1 ? 130 : 88;
        float gx = x + w / 2f - (cols * cw + (cols - 1) * 14) / 2f;
        float gy = y + 64;
        // bara de alimentare deasupra
        Gfx.rrect(ms, gx - 10, gy - 12, cols * cw + (cols - 1) * 14 + 20, 5, 2, 0xFF59636F);
        for (int i = 0; i < symbols; i++) {
            int c = i % cols;
            int r = i / cols;
            float bx = gx + c * (cw + 14);
            float by = gy + r * (ch + 14);
            boolean on = lit == i;
            int col = WIRE[i % WIRE.length];
            boolean hot = mode == 1 && !locked() && over(bx, by, cw, ch);
            Gfx.rrect(ms, bx, by, cw, ch, 8, on ? col : 0xFF05070A);
            Gfx.rrect(ms, bx + 2, by + 2, cw - 4, ch - 4, 7, hot ? 0xFF2A3440 : 0xFF1A2028, 0xFF12171D);
            if (on) Gfx.ring(ms, bx + cw / 2f, by + ch / 2f - 8, 22, 40, (col & 0x00FFFFFF) | 0x55000000, col & 0x00FFFFFF);
            Gfx.disc(ms, bx + cw / 2f, by + ch / 2f - 8, 17, 0xFF05070A);
            Gfx.disc(ms, bx + cw / 2f, by + ch / 2f - 8, 14, on ? col : Gfx.mixArgb(col, 0xFF000000, 0.68f));
            if (on) Gfx.disc(ms, bx + cw / 2f - 4, by + ch / 2f - 12, 4, 0x99FFFFFF);
            // maneta
            Gfx.rrect(ms, bx + cw / 2f - 6, by + ch - 26, 12, 18, 3, 0xFF05070A);
            Gfx.rrect(ms, bx + cw / 2f - 5, by + ch - (on ? 25 : 18), 10, 10, 2, on ? col : 0xFF59636F);
            Gfx.text(ms, "CB" + (i + 1), bx + cw / 2f, by + ch - 30 + (rows == 1 ? 0 : 0), 6.2f, TXT, 1, true, 0.4f);
            final int idx = i;
            if (mode == 1 && !locked()) hitL(bx, by, cw, ch, () -> press(idx));
        }
        Gfx.text(ms, mode == 1 ? "Apasă intreruptoarele în aceeași ordine (" + input.size() + "/" + round + ")" : mode == 0 ? "Urmărește cu atenție semnalele." : "Serverul verifică secvența.",
            x + w / 2f, y + h - 10, 6.8f, MUT, 1);
        btn(ms, "ȘTERGE ULTIMUL", x + w - 130, y + h - 28, 124, 20, mode == 1 && !input.isEmpty() && !locked(), false, () -> input.remove(input.size() - 1));
    }

    private void press(int i) {
        if (mode != 1 || locked() || input.size() >= round) return;
        input.add(i);
        litAt[i] = System.currentTimeMillis();
        sndBeep(0.6f + 0.17f * i);
        if (input.size() >= round) {
            mode = 2;
            int[] a = new int[round];
            for (int k = 0; k < round; k++) a[k] = input.get(k);
            sendAns("ans", a);
        }
    }

    @Override
    protected void onResult(JsonObject d) {
        if (bool(d, "fin")) {
            mode = 2;
            return;
        }
        if (d.has("show") && d.has("round")) {
            startRound((int) num(d, "round"), arr(d, "show"));
            if (!bool(d, "ok")) litAt[0] = 0;
        } else {
            mode = 1;
        }
    }
}
