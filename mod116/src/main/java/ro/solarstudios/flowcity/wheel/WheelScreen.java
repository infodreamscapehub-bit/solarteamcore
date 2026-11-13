package ro.solarstudios.flowcity.wheel;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Vector3f;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Roata norocului, desenata neted (Gfx): felii cu gradient radial, becuri care alearga pe margine, cuie la fiecare
 * separator care fac fanionul sa tresara, rotire cu pornire "inapoi" si incetinire pana la premiul ales de server,
 * clicuri la fiecare felie, iar la final felia castigatoare straluceste si cade confetti.
 */
public final class WheelScreen extends FlowScreen {
    private static final int W = 470;
    private static final int H = 290;
    private static final float R = 96f;
    private static final int GOLD = 0xFFF6C15A;
    private static final int GOLD2 = 0xFFD98E2B;
    private static final int WHITE = 0xFFF4F1EA;
    private static final int MUTED = 0xFF8E8C86;
    private static final int FAINT = 0xFF6F6D68;

    private double rotation;           // grade
    private boolean spinning;
    private long spinStart;
    private double fromRot;
    private double toRot;
    private int lastTick = Integer.MIN_VALUE;
    private long doneAt;
    private final long born = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float pd;                  // deflexia fanionului (0..1)
    private float bh;                  // hover pe buton
    private final float[][] conf = new float[90][7]; // x, y, vx, vy, rot, vrot, culoare
    private long confAt;
    private int[] confColors = {GOLD, 0xFFFFFFFF, 0xFF5FDC8F, 0xFF5BA8FF, 0xFFE2453F};

    private float offX;
    private float offY;
    private float scale = 1f;

    public WheelScreen() {
        super("Roata norocului", W, H);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected void init() {
        WheelClient.requestInfo();
    }

    /** Pornit cand serverul trimite rezultatul. */
    public void startSpin() {
        List<WheelState.Prize> ps = WheelState.prizes;
        if (ps.isEmpty() || WheelState.resultIndex < 0 || WheelState.resultIndex >= ps.size()) return;
        double seg = 360.0 / ps.size();
        double jitter = (Math.random() - 0.5) * seg * 0.6;
        double finalRot = -90 - (WheelState.resultIndex + 0.5) * seg + jitter;
        double cur = ((rotation % 360) + 360) % 360;
        double delta = ((finalRot - cur) % 360 + 360) % 360 + 360 * (5 + (int) (Math.random() * 2));
        fromRot = rotation;
        toRot = rotation + delta;
        spinStart = System.currentTimeMillis();
        spinning = true;
        doneAt = 0;
        confAt = 0;
        lastTick = Integer.MIN_VALUE;
    }

    private static double ease(double t) {
        double k = 1 - Math.min(1, Math.max(0, t));
        return 1 - k * k * k * k; // incetinire puternica spre final
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Zona clicabila data in coordonate locale (0..W, 0..H), convertita in coordonate de ecran. */
    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    private static String clipG(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        List<WheelState.Prize> ps = WheelState.prizes;
        int n = ps.size();

        // ---- avans animatie
        if (spinning) {
            double t = (now - spinStart) / (WheelState.animSeconds * 1000.0);
            double ant = t < 0.07 ? -10 * Math.sin(t / 0.07 * Math.PI) : 0;
            rotation = fromRot + (toRot - fromRot) * ease(t) + ant;
            if (n > 0) {
                double seg = 360.0 / n;
                int tick = (int) Math.floor((-90 - rotation) / seg);
                if (tick != lastTick) {
                    if (lastTick != Integer.MIN_VALUE) tickSound(t);
                    lastTick = tick;
                }
            }
            if (t >= 1.0) {
                spinning = false;
                doneAt = now;
                rotation = toRot;
                initConfetti(now);
                Minecraft.getInstance().getSoundManager().play(SimpleSound.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
                WheelClient.requestInfo();
            }
        } else if (n > 0 && doneAt == 0) {
            rotation += 6.0 * dt; // se invarte lent cand sta
        }

        // fanionul tresare cand un cui trece pe sub el
        float target = 0f;
        if (spinning && n > 0) {
            double seg = 360.0 / n;
            double rel = (((rotation + 90) % seg) + seg) % seg;
            double toNext = seg - rel;
            target = (float) Math.max(0, 1 - toNext / (seg * 0.45));
        }
        pd += (target - pd) * Math.min(1f, dt * (target > pd ? 40f : 16f));

        float open = easeOut((now - born) / 300f);
        scale = Math.min(1f, Math.min((width - 12f) / W, (height - 12f) / H)) * (0.93f + 0.07f * open);
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;

        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0xC0050608, open));
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, 1f);

        // ---- panou
        Gfx.rrect(ms, -6, 2, W + 12, H + 10, 22, 0x55000000);
        Gfx.rrect(ms, 0, 0, W, H, 18, 0xFF1D2026, 0xFF121418);
        Gfx.rrect(ms, 0.5f, 0.5f, W - 1, 1, 0, 0x14FFFFFF);
        Gfx.rrect(ms, 10, 10, 250, H - 20, 14, 0xFF101216);

        float cx = 135f;
        float cy = 150f;
        Gfx.scrim(ms, cx, cy, R + 50, R + 50, 0x1CF6C15A);
        drawWheel(ms, cx, cy, now, ps);
        drawPointer(ms, cx, cy - R - 20);
        if (confAt > 0) drawConfetti(ms, now);
        if (n == 0) Gfx.text(ms, "Se încarcă roata...", cx, cy + 3, 9, MUTED, 1);

        drawRight(ms, now, dt, lmx, lmy);

        // inchidere
        boolean hx = inside(lmx, lmy, W - 30, 10, 18, 18);
        if (hx) Gfx.disc(ms, W - 21, 19, 9, 0x22FFFFFF);
        int xc = hx ? 0xFFE2453F : 0xFF8E8C86;
        Gfx.line(ms, W - 25, 15, W - 17, 23, 1.6f, xc);
        Gfx.line(ms, W - 17, 15, W - 25, 23, 1.6f, xc);
        hitL(W - 30, 10, 18, 18, this::onClose);

        ms.pose().popPose();
    }

    private void drawRight(GuiGraphics ms, long now, float dt, float lmx, float lmy) {
        List<WheelState.Prize> ps = WheelState.prizes;
        float rx = 280f;
        float rw = 172f;
        Gfx.text(ms, "ROATA NOROCULUI", rx, 38, 12, WHITE, 0, true, 0.6f);
        Gfx.text(ms, "Revino în fiecare zi pentru un premiu.", rx, 52, 7.5f, MUTED, 0);

        long left = WheelState.cooldownLeft();
        boolean can = !spinning && left <= 0 && !ps.isEmpty();

        Gfx.text(ms, "STARE", rx, 80, 6.5f, FAINT, 0, false, 0.6f);
        int sc;
        String st;
        if (spinning) {
            sc = 0xFFE9A23B;
            st = "Se învârte...";
        } else if (left > 0) {
            sc = 0xFF8E8C86;
            st = "Următorul spin: " + Ui.duration((left + 999) / 1000);
        } else {
            sc = 0xFF5FDC8F;
            st = "Spin disponibil!";
        }
        float pl = 0.5f + 0.5f * (float) Math.sin(now / 300.0);
        Gfx.disc(ms, rx + 3, 93, 5, Gfx.fade(sc, 0.25f + 0.25f * pl), 0x00000000, 0, 0);
        Gfx.disc(ms, rx + 3, 93, 2.6f, sc);
        Gfx.text(ms, clipG(st, 8.5f, false, rw - 14), rx + 12, 96, 8.5f, WHITE, 0);

        // buton
        float by = 108f;
        float bhh = 32f;
        boolean hov = can && inside(lmx, lmy, rx, by, rw, bhh);
        bh += ((hov ? 1f : 0f) - bh) * Math.min(1f, dt * 14f);
        if (can) {
            float p = 0.5f + 0.5f * (float) Math.sin(now / 400.0);
            Gfx.rrect(ms, rx - 3 - p * 2, by - 3 - p * 2, rw + 6 + p * 4, bhh + 6 + p * 4, 14, Gfx.fade(0x33F6C15A, 1f - p * 0.6f));
        }
        int t1 = can ? Gfx.mixArgb(GOLD, 0xFFFFFFFF, bh * 0.18f) : 0xFF24272D;
        int t2 = can ? Gfx.mixArgb(GOLD2, 0xFFFFFFFF, bh * 0.10f) : 0xFF1E2126;
        Gfx.rrect(ms, rx, by, rw, bhh, 12, t1, t2);
        if (can) Gfx.rrect(ms, rx + 6, by + 1, rw - 12, 1.5f, 0.75f, 0x66FFFFFF);
        String label = can ? "ÎNVÂRTE" : (spinning ? "SE ÎNVÂRTE" : "INDISPONIBIL");
        Gfx.text(ms, label, rx + rw / 2f, by + 20, 11, can ? 0xFF2A1A06 : FAINT, 1, true, 0.8f);
        if (can) hitL(rx, by, rw, bhh, WheelClient::spin);

        // premiul castigat
        boolean win = !spinning && doneAt > 0 && !WheelState.resultName.isEmpty();
        float y0 = 158f;
        if (win) {
            float a = easeOut((now - doneAt) / 400f);
            float cy0 = y0 + (1f - a) * 8f;
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 220.0);
            Gfx.rrect(ms, rx - 1, cy0 - 1, rw + 2, 56, 13, Gfx.fade(Gfx.mixArgb(GOLD, 0xFFFFFFFF, pulse * 0.3f), a));
            Gfx.rrect(ms, rx, cy0, rw, 54, 12, Gfx.fade(0xFF2A2214, a), Gfx.fade(0xFF1A150C, a));
            Gfx.text(ms, "AI CÂȘTIGAT", rx + rw / 2f, cy0 + 15, 6.5f, Gfx.fade(GOLD, a), 1, false, 1.2f);
            Gfx.text(ms, clipG(WheelState.resultName, 14, true, rw - 16), rx + rw / 2f, cy0 + 33, 14, Gfx.fade(WHITE, a), 1, true, 0);
            Gfx.text(ms, clipG(WheelState.resultDetail, 7.5f, false, rw - 16), rx + rw / 2f, cy0 + 46, 7.5f, Gfx.fade(MUTED, a), 1);
        }

        // ultimele premii
        float ly = win ? 226f : 166f;
        int rows = win ? 3 : 6;
        Gfx.text(ms, "ULTIMELE TALE PREMII", rx, ly, 6.5f, FAINT, 0, false, 0.6f);
        if (WheelState.recent.isEmpty()) Gfx.text(ms, "Încă niciunul.", rx, ly + 14, 8, MUTED, 0);
        for (int i = 0; i < Math.min(rows, WheelState.recent.size()); i++) {
            float yy = ly + 14 + i * 13;
            Gfx.disc(ms, rx + 3, yy - 3, 2f, Gfx.fade(GOLD, 1f - i * 0.15f));
            Gfx.text(ms, clipG(WheelState.recent.get(i), 8, false, rw - 14), rx + 11, yy, 8, 0xFFA19F98, 0);
        }
        Gfx.text(ms, "ESC închide", W - 16, H - 12, 6.5f, 0xFF5C606A, 2);
    }

    private void tickSound(double t) {
        float pitch = 0.9f + (float) (1.0 - t) * 0.5f;
        Minecraft.getInstance().getSoundManager().play(SimpleSound.forUI(SoundEvents.NOTE_BLOCK_HAT, pitch));
    }

    // ------------------------------------------------------------------ roata

    private void drawWheel(GuiGraphics ms, float cx, float cy, long now, List<WheelState.Prize> ps) {
        int n = ps.size();
        float pulse = 0.5f + 0.5f * (float) Math.sin(now / 150.0);
        boolean done = !spinning && doneAt > 0;

        // umbra + inel auriu
        Gfx.disc(ms, cx, cy + 5, R + 22, 0x77000000, 0x00000000, 0, 0);
        Gfx.ring(ms, cx, cy, R + 1, R + 14, 0xFFB9761F, GOLD);
        Gfx.ring(ms, cx, cy, R + 14, R + 16, 0xFF0E1013, 0xFF0E1013);
        Gfx.ring(ms, cx, cy, R - 0.5f, R + 2, 0xFF0E1013, 0xFF6B4A18);

        // felii
        if (n > 0) {
            double seg = 360.0 / n;
            for (int i = 0; i < n; i++) {
                int base = ps.get(i).color;
                if (i % 2 == 1) base = Gfx.mixArgb(base, 0xFF000000, 0.12f);
                if (done) {
                    base = i == WheelState.resultIndex ? Gfx.mixArgb(base, 0xFFFFFFFF, 0.2f + 0.12f * pulse) : Gfx.mixArgb(base, 0xFF0E1013, 0.55f);
                }
                int in = Gfx.mixArgb(base, 0xFF000000, 0.42f);
                int out = Gfx.mixArgb(base, 0xFFFFFFFF, 0.06f);
                Gfx.sector(ms, cx, cy, 12, R, (float) (rotation + i * seg), (float) (rotation + (i + 1) * seg), in, out);
            }
            // separatoare + cuie
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(rotation + i * seg);
                float c = (float) Math.cos(a);
                float s = (float) Math.sin(a);
                Gfx.line(ms, cx + c * 14, cy + s * 14, cx + c * (R - 3), cy + s * (R - 3), 1.2f, 0x66000000);
                Gfx.disc(ms, cx + c * (R - 1.5f), cy + s * (R - 1.5f), 2.6f, GOLD, GOLD2, -0.6f, -0.6f);
            }
            // etichete
            for (int i = 0; i < n; i++) {
                float mid = (float) (rotation + (i + 0.5) * seg);
                int base = ps.get(i).color;
                int fg = luma(base) > 165 ? 0xFF14161A : 0xFFFFFFFF;
                if (done && i != WheelState.resultIndex) fg = Gfx.fade(fg, 0.45f);
                float size = n > 12 ? 7f : 7.8f;
                String name = clipG(ps.get(i).name, size, false, R - 36);
                ms.pose().pushPose();
                ms.pose().translate(cx, cy, 0);
                boolean flip = Math.cos(Math.toRadians(mid)) < 0;
                ms.pose().mulPose(Vector3f.ZP.rotationDegrees(flip ? mid + 180f : mid));
                if (flip) Gfx.text(ms, name, -(R - 11), size * 0.4f, size, fg, 0);
                else Gfx.text(ms, name, R - 11, size * 0.4f, size, fg, 2);
                ms.pose().popPose();
            }
            // felia castigatoare straluceste
            if (done && WheelState.resultIndex >= 0 && WheelState.resultIndex < n) {
                float d0 = (float) (rotation + WheelState.resultIndex * seg) + 1.5f;
                float d1 = (float) (rotation + (WheelState.resultIndex + 1) * seg) - 1.5f;
                Gfx.arc(ms, cx, cy, R - 3, 3.2f, d0, d1, (x, y) -> GOLD, 0.5f + 0.5f * pulse);
            }
        }

        // reflex
        Gfx.arc(ms, cx, cy, R - 10, 3f, 200, 255, (x, y) -> 0x2AFFFFFF, 1f);

        // becuri pe margine
        int bulbs = 30;
        long phase = now / (spinning ? 70 : 260);
        for (int i = 0; i < bulbs; i++) {
            double a = Math.toRadians(i * 360.0 / bulbs);
            float bx = cx + (float) Math.cos(a) * (R + 7.5f);
            float by = cy + (float) Math.sin(a) * (R + 7.5f);
            boolean on = spinning ? (i + phase) % 3 == 0 : (i + phase) % 2 == 0;
            if (on) Gfx.disc(ms, bx, by, 5.5f, 0x99FFD27A, 0x00FFD27A, 0, 0);
            Gfx.disc(ms, bx, by, 1.9f, on ? 0xFFFFF1C2 : 0xFF7A5420);
        }

        // butuc
        Gfx.ring(ms, cx, cy, 14, 19, 0xFF2A2E36, 0xFF1C1F25);
        Gfx.disc(ms, cx, cy, 17, GOLD, GOLD2, -3, -4);
        Gfx.disc(ms, cx, cy, 13, 0xFF1A1D23, 0xFF111317, 0, 0);
        Gfx.text(ms, "FC", cx, cy + 3.6f, 9.5f, GOLD, 1, true, 0.3f);
    }

    private void drawPointer(GuiGraphics ms, float cx, float pivotY) {
        ms.pose().pushPose();
        ms.pose().translate(cx, pivotY, 0);
        ms.pose().mulPose(Vector3f.ZP.rotationDegrees(-pd * 22f));
        Gfx.poly(ms, new float[] {-6.5f, 3.5f, 6.5f, 3.5f, 0, 27f}, 0x66000000);
        Gfx.poly(ms, new float[] {-6, 1, 6, 1, 0, 24f}, GOLD2);
        Gfx.poly(ms, new float[] {-3.6f, 1, 3.6f, 1, 0, 19f}, GOLD);
        Gfx.disc(ms, 0, 0, 7.5f, GOLD, GOLD2, -1.5f, -2f);
        Gfx.disc(ms, 0, 0, 3f, 0xFF14161A);
        ms.pose().popPose();
    }

    // ------------------------------------------------------------------ confetti

    private void initConfetti(long now) {
        confAt = now;
        List<WheelState.Prize> ps = WheelState.prizes;
        int extra = Math.min(ps.size(), 6);
        confColors = new int[5 + extra];
        int[] base = {GOLD, 0xFFFFFFFF, 0xFF5FDC8F, 0xFF5BA8FF, 0xFFE2453F};
        System.arraycopy(base, 0, confColors, 0, 5);
        for (int i = 0; i < extra; i++) confColors[5 + i] = ps.get(i).color | 0xFF000000;
        for (float[] c : conf) {
            double ang = Math.toRadians(-90 + (Math.random() - 0.5) * 110);
            double sp = 90 + Math.random() * 170;
            c[0] = 135f + (float) (Math.random() * 30 - 15);
            c[1] = 150f - R + 6f;
            c[2] = (float) (Math.cos(ang) * sp);
            c[3] = (float) (Math.sin(ang) * sp);
            c[4] = (float) (Math.random() * Math.PI * 2);
            c[5] = (float) (Math.random() * 14 - 7);
            c[6] = (float) Math.floor(Math.random() * confColors.length);
        }
    }

    private void drawConfetti(GuiGraphics ms, long now) {
        float t = (now - confAt) / 1000f;
        if (t > 3.6f) return;
        float fade = 1f - Math.max(0f, (t - 2.5f) / 1.1f);
        for (float[] c : conf) {
            float x = c[0] + c[2] * t;
            float y = c[1] + c[3] * t + 0.5f * 260f * t * t;
            float r = c[4] + c[5] * t;
            float dx = (float) Math.cos(r) * 3f;
            float dy = (float) Math.sin(r) * 3f;
            Gfx.line(ms, x - dx, y - dy, x + dx, y + dy, 2.2f, Gfx.fade(confColors[(int) c[6]], fade));
        }
    }
}
