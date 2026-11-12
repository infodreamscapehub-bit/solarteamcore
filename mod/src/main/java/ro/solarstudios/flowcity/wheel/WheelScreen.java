package ro.solarstudios.flowcity.wheel;

import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.sounds.SoundEvents;
import org.joml.Matrix4f;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Roata norocului: segmente colorate desenate cu triunghiuri, becuri care alterneaza pe margine, fanion sus,
 * rotire cu incetinire pana la premiul ales de server, clicuri la fiecare segment trecut, confetti la final.
 */
public final class WheelScreen extends FlowScreen {
    private static final float R = 92f;

    private double rotation;           // grade
    private boolean spinning;
    private long spinStart;
    private double fromRot;
    private double toRot;
    private int lastTick = Integer.MIN_VALUE;
    private long doneAt;
    private final float[][] confetti = new float[50][5]; // x, y, vx, vy, color-index

    public WheelScreen() {
        super("Roata norocului", 420, 262);
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
        lastTick = Integer.MIN_VALUE;
    }

    private double ease(double t) {
        double k = 1 - Math.min(1, Math.max(0, t));
        return 1 - k * k * k * k; // incetinire puternica spre final
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        List<WheelState.Prize> ps = WheelState.prizes;
        header(ms, "Roata norocului", "revino zilnic", mx, my);

        // avans animatie
        if (spinning) {
            double t = (now - spinStart) / (WheelState.animSeconds * 1000.0);
            rotation = fromRot + (toRot - fromRot) * ease(t);
            if (!ps.isEmpty()) {
                double seg = 360.0 / ps.size();
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
                initConfetti();
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0F));
                WheelClient.requestInfo();
            }
        } else if (ps.size() > 0 && doneAt == 0) {
            rotation += 0.15; // se invarte lent cand sta
        }

        int cx = px + 120;
        int cy = py + HEAD + 10 + (int) R + 8;
        drawWheel(ms, cx, cy, now, ps);
        drawPointer(ms, cx, cy - (int) R - 12);
        if (doneAt > 0) drawConfetti(ms, cx, cy, now);

        // panou dreapta
        int rx = px + 238;
        int rw = pw - 238 - 10;
        int y = py + HEAD + 12;
        long left = WheelState.cooldownLeft();
        boolean canSpin = !spinning && left <= 0 && !ps.isEmpty();
        Ui.text(ms, "STARE", rx, y, Theme.FAINT);
        y += 11;
        if (spinning) Ui.text(ms, "Se invarte...", rx, y, Theme.WARN);
        else if (left > 0) Ui.text(ms, "Urmatorul spin: " + Ui.duration((left + 999) / 1000), rx, y, Theme.MUTED);
        else Ui.text(ms, "Spin disponibil!", rx, y, Theme.OK);
        y += 18;
        final boolean enabled = canSpin;
        button(ms, spinning ? "..." : "INVARTE", rx, y, rw, 20, mx, my, enabled, 0xFF4B4FA8, WheelClient::spin);
        y += 32;

        // premiul castigat
        if (!spinning && doneAt > 0 && !WheelState.resultName.isEmpty()) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 180.0);
            int c = Theme.mix(Theme.WARN, 0xFFFFFFFF, pulse * 0.5f);
            Ui.frame(ms, rx, y, rw, 38, Theme.PANEL_3, c);
            Ui.textCenter(ms, "AI CASTIGAT", rx + rw / 2, y + 6, Theme.MUTED);
            Ui.textCenter(ms, Ui.clip(WheelState.resultName, rw - 8), rx + rw / 2, y + 17, c);
            Ui.textCenter(ms, Ui.clip(WheelState.resultDetail, rw - 8), rx + rw / 2, y + 28, Theme.FAINT);
            y += 48;
        }
        Ui.text(ms, "ULTIMELE TALE PREMII", rx, y, Theme.FAINT);
        y += 11;
        if (WheelState.recent.isEmpty()) Ui.text(ms, "Inca niciunul.", rx, y, Theme.MUTED);
        for (int i = 0; i < Math.min(5, WheelState.recent.size()); i++) {
            Ui.text(ms, Ui.clip(WheelState.recent.get(i), rw - 4), rx, y, Theme.MUTED);
            y += 10;
        }
        if (ps.isEmpty()) Ui.textCenter(ms, "Se incarca roata...", cx, cy, Theme.MUTED);
    }

    private void tickSound(double t) {
        float pitch = 0.9f + (float) (1.0 - t) * 0.5f;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.get(), pitch));
    }

    // ------------------------------------------------------------------ desen roata

    private void drawWheel(GuiGraphics ms, int cx, int cy, long now, List<WheelState.Prize> ps) {
        // inel exterior + umbra
        disc(ms, cx, cy, R + 9, 0xFF0A1020, 64);
        disc(ms, cx, cy, R + 6, Theme.LINE, 64);
        int n = ps.size();
        if (n > 0) {
            double seg = 360.0 / n;
            for (int i = 0; i < n; i++) {
                int c = ps.get(i).color;
                boolean win = !spinning && doneAt > 0 && i == WheelState.resultIndex;
                if (win) c = Theme.mix(c, 0xFFFFFFFF, 0.25f + 0.2f * (float) Math.sin(now / 150.0));
                else if (i % 2 == 1) c = Theme.mix(c, 0xFF000000, 0.18f);
                sector(ms, cx, cy, R, rotation + i * seg, rotation + (i + 1) * seg, c);
            }
            // separatoare
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(rotation + i * seg);
                line(ms, cx, cy, cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, 0xFF0A1020);
            }
            // etichete
            for (int i = 0; i < n; i++) {
                double a = Math.toRadians(rotation + (i + 0.5) * seg);
                int tx = cx + (int) (Math.cos(a) * R * 0.66);
                int ty = cy + (int) (Math.sin(a) * R * 0.66);
                String name = Ui.clip(ps.get(i).name, 54);
                ms.pose().pushPose();
                ms.pose().translate(tx, ty, 0);
                ms.pose().scale(0.8f, 0.8f, 1f);
                Ui.textShadow(ms, name, -Ui.width(name) / 2, -4, 0xFFFFFFFF);
                ms.pose().popPose();
            }
        }
        // becuri pe margine (alterneaza)
        int bulbs = 24;
        for (int i = 0; i < bulbs; i++) {
            double a = Math.toRadians(i * 360.0 / bulbs + rotation * 0.2);
            int bx = cx + (int) Math.round(Math.cos(a) * (R + 3));
            int by = cy + (int) Math.round(Math.sin(a) * (R + 3));
            boolean on = ((now / (spinning ? 90 : 350)) + i) % 2 == 0;
            Ui.rect(ms, bx - 1, by - 1, 3, 3, on ? Theme.WARN : Theme.FAINT);
        }
        // butuc
        disc(ms, cx, cy, 14, Theme.LINE, 24);
        disc(ms, cx, cy, 11, Theme.PANEL, 24);
        Ui.textCenter(ms, "FC", cx, cy - 4, Theme.ACCENT);
    }

    private void drawPointer(GuiGraphics ms, int cx, int top) {
        // fanion triunghiular cu varful in jos, tresare usoara cand ticaie
        float wob = spinning ? (float) Math.sin(System.currentTimeMillis() / 40.0) * 1.5f : 0f;
        fan(ms, new float[] {cx - 8, top, cx + 8, top, cx + wob, top + 20}, Theme.WARN);
        fan(ms, new float[] {cx - 5, top + 1, cx + 5, top + 1, cx + wob, top + 15}, 0xFFFFE08A);
    }

    // ------------------------------------------------------------------ confetti

    private void initConfetti() {
        for (float[] c : confetti) {
            c[0] = (float) (Math.random() * 40 - 20);
            c[1] = (float) (Math.random() * 10);
            c[2] = (float) (Math.random() * 3 - 1.5);
            c[3] = (float) (-Math.random() * 3.5 - 1);
            c[4] = (float) Math.floor(Math.random() * 4);
        }
    }

    private void drawConfetti(GuiGraphics ms, int cx, int cy, long now) {
        double t = (now - doneAt) / 1000.0;
        if (t > 3.5) return;
        int[] colors = {Theme.WARN, Theme.ACCENT, Theme.OK, Theme.BAD};
        for (float[] c : confetti) {
            float x = cx + c[0] + c[2] * (float) t * 28f;
            float y = cy - (int) R + c[1] + c[3] * (float) t * 22f + 40f * (float) (t * t);
            Ui.rect(ms, (int) x, (int) y, 2, 2, Theme.alpha(colors[(int) c[4]], (int) (255 * Math.max(0, 1 - t / 3.5))));
        }
    }

    // ------------------------------------------------------------------ primitive GL

    private static void begin(VertexFormat.Mode mode) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Tesselator.getInstance().getBuilder().begin(mode, DefaultVertexFormat.POSITION_COLOR);
    }

    private static void end() {
        BufferUploader.drawWithShader(Tesselator.getInstance().getBuilder().end());
    }

    private static void vertex(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
        b.vertex(m, x, y, 0f).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255).endVertex();
    }

    private static void sector(GuiGraphics ms, float cx, float cy, float r, double fromDeg, double toDeg, int color) {
        Matrix4f m = ms.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        begin(VertexFormat.Mode.TRIANGLE_FAN);
        vertex(b, m, cx, cy, color);
        int steps = Math.max(2, (int) Math.ceil((toDeg - fromDeg) / 4.0));
        for (int i = 0; i <= steps; i++) {
            double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / steps);
            vertex(b, m, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, color);
        }
        end();
    }

    private static void disc(GuiGraphics ms, float cx, float cy, float r, int color, int steps) {
        sector(ms, cx, cy, r, 0, 360, color);
    }

    private static void fan(GuiGraphics ms, float[] pts, int color) {
        Matrix4f m = ms.pose().last().pose();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        begin(VertexFormat.Mode.TRIANGLE_FAN);
        for (int i = 0; i + 1 < pts.length; i += 2) vertex(b, m, pts[i], pts[i + 1], color);
        end();
    }

    private static void line(GuiGraphics ms, float x1, float y1, float x2, float y2, int color) {
        // linie subtire ca dreptunghi rotit (2 triunghiuri)
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float nx = -dy / len * 0.6f;
        float ny = dx / len * 0.6f;
        fan(ms, new float[] {x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny}, color);
    }
}
