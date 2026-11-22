package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Mini-joc 1: conectarea circuitelor prin tragere. Fiecare faza (stanga) se trage la sarcina cu aceeasi culoare (dreapta). Serverul tine solutia. */
final class ElecCircuitScreen extends ElecGameScreen {
    private final JsonArray left;
    private final JsonArray right;
    private final int n;
    private final int[] ans;
    private int drag = -1;
    private float dx;
    private float dy;
    private boolean sent;
    private long flashAt;

    ElecCircuitScreen(JsonObject spec) {
        super(spec, "Conectare circuit");
        this.left = arr(spec, "left");
        this.right = arr(spec, "right");
        this.n = left.size();
        this.ans = new int[n];
        Arrays.fill(ans, -1);
    }

    private float rowY(int i, float y, float h) {
        float gap = Math.min(54f, (h - 90f) / Math.max(1, n));
        return y + 52 + i * gap + gap / 2f - 8;
    }

    private static final float LX = 60;
    private static final float RX = 520;

    @Override
    protected void play(GuiGraphics ms, float t, float x, float y, float w, float h) {
        Gfx.text(ms, "TRAGE FIECARE FAZĂ DE LA BORNĂ LA SARCINA CU ACEEAȘI CULOARE", x + w / 2f, y + 14, 6.6f, YEL, 1, true, 1f);
        Gfx.text(ms, "BORNE ALIMENTARE", LX + 40, y + 36, 5.6f, MUT, 1, true, 0.8f);
        Gfx.text(ms, "SARCINI", RX + 40, y + 36, 5.6f, MUT, 1, true, 0.8f);
        // sina de alimentare (bara galbena in stanga)
        Gfx.rrect(ms, x + 6, y + 46, 8, h - 100, 3, 0xFF2A2208);
        Gfx.rrect(ms, x + 8, y + 46, 4, h - 100, 2, YEL);
        // fire
        for (int i = 0; i < n; i++) {
            if (ans[i] < 0) continue;
            wire(ms, LX + 80, rowY(i, y, h) + 17, RX, rowY(ans[i], y, h) + 17, WIRE[(int) left.get(i).getAsJsonObject().get("c").getAsInt() % WIRE.length], t, false);
        }
        if (drag >= 0) {
            wire(ms, LX + 80, rowY(drag, y, h) + 17, dx, dy, WIRE[left.get(drag).getAsJsonObject().get("c").getAsInt() % WIRE.length], t, true);
        }
        for (int i = 0; i < n; i++) {
            float ry = rowY(i, y, h);
            JsonObject l = left.get(i).getAsJsonObject();
            int col = WIRE[l.get("c").getAsInt() % WIRE.length];
            // bornă stânga
            Gfx.rrect(ms, LX, ry, 80, 34, 5, 0xFF05070A);
            Gfx.rrect(ms, LX + 1, ry + 1, 78, 32, 4, PANEL2);
            Gfx.rrect(ms, LX + 4, ry + 5, 8, 24, 2, col);
            Gfx.text(ms, l.get("l").getAsString(), LX + 24, ry + 21, 10, TXT, 0, true, 0.6f);
            Gfx.disc(ms, LX + 70, ry + 17, 6.5f, 0xFF05070A);
            Gfx.disc(ms, LX + 70, ry + 17, 5.2f, ans[i] >= 0 || drag == i ? col : 0xFF3A4350);
            Gfx.line(ms, LX + 67, ry + 17, LX + 73, ry + 17, 1.2f, 0xFF05070A);
            // sarcină dreapta
            JsonObject r = right.get(i).getAsJsonObject();
            int rc = WIRE[r.get("c").getAsInt() % WIRE.length];
            boolean linked = false;
            for (int k = 0; k < n; k++) if (ans[k] == i) linked = true;
            boolean hot = drag >= 0 && over(RX - 8, ry - 2, 96, 38);
            Gfx.rrect(ms, RX, ry, 80, 34, 5, hot ? YEL : 0xFF05070A);
            Gfx.rrect(ms, RX + 1, ry + 1, 78, 32, 4, PANEL2);
            Gfx.disc(ms, RX + 10, ry + 17, 6.5f, 0xFF05070A);
            Gfx.disc(ms, RX + 10, ry + 17, 5.2f, linked ? rc : 0xFF3A4350);
            Gfx.ring(ms, RX + 10, ry + 17, 7f, 8.2f, rc, rc);
            device(ms, r.get("l").getAsString(), RX + 36, ry + 17, linked ? rc : MUT, t, linked);
            Gfx.text(ms, r.get("l").getAsString().toUpperCase(), RX + 56, ry + 20, 5.4f, TXT, 0, true, 0.3f);
        }
        int cnt = 0;
        for (int v : ans) if (v >= 0) cnt++;
        Gfx.text(ms, "CONECTATE: " + cnt + " / " + n, x + 8, y + h - 12, 7f, cnt == n ? GOOD : MUT, 0, true, 0.6f);
        if (bool(spec, "pliers")) Gfx.text(ms, "CLEȘTE IZOLAT: +1 GREȘEALĂ PERMISĂ", x + w / 2f, y + h - 12, 5.8f, YEL, 1, true, 0.5f);
        btn(ms, "RESETEAZĂ", x + w - 250, y + h - 24, 110, 22, !locked() && !sent, false, () -> {
            Arrays.fill(ans, -1);
            message = "";
        });
        btn(ms, "VERIFICĂ CIRCUITUL", x + w - 132, y + h - 24, 130, 22, !locked() && !sent && cnt == n, true, () -> {
            sent = true;
            message = "Se verifică circuitul...";
            messageColor = MUT;
            sndTick(1.4f);
            sendAns("submit", ans);
        });
    }

    /** Fir cu contur, culoare si luciu; curba orizontala. */
    private void wire(GuiGraphics ms, float x0, float y0, float x1, float y1, int col, float t, boolean live) {
        int seg = 20;
        float px = x0;
        float py = y0;
        float k = Math.max(40f, Math.abs(x1 - x0) * 0.45f);
        for (int s = 1; s <= seg; s++) {
            float u = s / (float) seg;
            float a = (1 - u) * (1 - u) * (1 - u);
            float b = 3 * (1 - u) * (1 - u) * u;
            float c = 3 * (1 - u) * u * u;
            float d = u * u * u;
            float x = a * x0 + b * (x0 + k) + c * (x1 - k) + d * x1;
            float y = a * y0 + b * y0 + c * y1 + d * y1;
            Gfx.line(ms, px, py, x, y, 6.2f, 0xFF05070A);
            px = x;
            py = y;
        }
        px = x0;
        py = y0;
        for (int s = 1; s <= seg; s++) {
            float u = s / (float) seg;
            float a = (1 - u) * (1 - u) * (1 - u);
            float b = 3 * (1 - u) * (1 - u) * u;
            float c = 3 * (1 - u) * u * u;
            float d = u * u * u;
            float x = a * x0 + b * (x0 + k) + c * (x1 - k) + d * x1;
            float y = a * y0 + b * y0 + c * y1 + d * y1;
            Gfx.line(ms, px, py, x, y, 4f, col);
            Gfx.line(ms, px, py - 0.8f, x, y - 0.8f, 1f, 0x55FFFFFF);
            px = x;
            py = y;
        }
        if (!live) {
            float u = (t * 0.6f) % 1f;
            float a = (1 - u) * (1 - u) * (1 - u);
            float b = 3 * (1 - u) * (1 - u) * u;
            float c = 3 * (1 - u) * u * u;
            float d = u * u * u;
            Gfx.disc(ms, a * x0 + b * (x0 + k) + c * (x1 - k) + d * x1, a * y0 + b * y0 + c * y1 + d * y1, 2.2f, 0xCCFFFFFF);
        }
    }

    /** Pictograma unei sarcini, desenata din forme. */
    private void device(GuiGraphics ms, String name, float cx, float cy, int col, float t, boolean on) {
        int glow = on ? YEL : col;
        switch (name) {
            case "Lumina":
                Gfx.disc(ms, cx, cy - 2, 7, on ? 0xFFFFE27A : 0xFF3A4350);
                Gfx.rrect(ms, cx - 3, cy + 4, 6, 5, 1, 0xFF8D99A6);
                if (on) Gfx.ring(ms, cx, cy - 2, 8, 12 + 1.5f * (float) Math.sin(t * 6), 0x44FFE27A, 0x00FFE27A);
                break;
            case "Pompa":
            case "Motor":
                Gfx.disc(ms, cx, cy, 8, 0xFF2A323C);
                for (int k = 0; k < 3; k++) {
                    double a = (on ? t * 6 : 0) + k * Math.PI * 2 / 3;
                    Gfx.line(ms, cx, cy, cx + (float) Math.cos(a) * 7, cy + (float) Math.sin(a) * 7, 2.2f, glow);
                }
                Gfx.disc(ms, cx, cy, 2, TXT);
                break;
            case "Senzor":
                Gfx.rrect(ms, cx - 7, cy - 6, 14, 12, 2, 0xFF2A323C);
                for (int k = 0; k < 3; k++) Gfx.ring(ms, cx + 4, cy, 3 + k * 3, 3.8f + k * 3, on ? glow : 0xFF3A4350, on ? glow : 0xFF3A4350);
                break;
            case "Releu":
                Gfx.rrect(ms, cx - 8, cy - 7, 16, 14, 2, 0xFF2A323C);
                Gfx.line(ms, cx - 5, cy + 3, cx + 5, cy - 3, 2f, glow);
                Gfx.disc(ms, cx - 5, cy + 3, 1.6f, TXT);
                Gfx.disc(ms, cx + 5, cy - 3, 1.6f, TXT);
                break;
            default:
                Gfx.rrect(ms, cx - 8, cy - 8, 16, 16, 3, 0xFF2A323C);
                Gfx.rrect(ms, cx - 5, cy - 3, 3, 6, 1, glow);
                Gfx.rrect(ms, cx + 2, cy - 3, 3, 6, 1, glow);
        }
    }

    @Override
    protected boolean onPress(float px, float py, int button) {
        if (locked() || sent) return false;
        for (int i = 0; i < n; i++) {
            float ry = rowY(i, 52, 266);
            if (px >= LX && px <= LX + 84 && py >= ry && py <= ry + 34) {
                if (button == 1) {
                    ans[i] = -1;
                    return true;
                }
                ans[i] = -1;
                drag = i;
                dx = px;
                dy = py;
                sndTick(1.8f);
                return true;
            }
            if (px >= RX - 4 && px <= RX + 84 && py >= ry && py <= ry + 34) {
                for (int k = 0; k < n; k++) {
                    if (ans[k] == i) {
                        ans[k] = -1;
                        drag = k;
                        dx = px;
                        dy = py;
                        sndTick(1.6f);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected void onDrag(float px, float py) {
        if (drag >= 0) {
            dx = px;
            dy = py;
        }
    }

    @Override
    protected void onRelease(float px, float py) {
        if (drag < 0) return;
        for (int j = 0; j < n; j++) {
            float ry = rowY(j, 52, 266);
            if (px >= RX - 8 && px <= RX + 90 && py >= ry - 2 && py <= ry + 36) {
                for (int k = 0; k < n; k++) if (ans[k] == j) ans[k] = -1;
                ans[drag] = j;
                sndBeep(1.7f);
                flashAt = System.currentTimeMillis();
                break;
            }
        }
        drag = -1;
    }

    @Override
    protected void onResult(JsonObject d) {
        sent = false;
    }
}
