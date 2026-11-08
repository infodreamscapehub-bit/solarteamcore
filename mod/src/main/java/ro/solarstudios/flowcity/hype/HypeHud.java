package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.matrix.MatrixStack;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * HUD FlowCity (stil "ceas + titlu + nume + bani"): ceas de joc si data, titlu cu stralucire, emblema care orbiteaza,
 * nume cu ID si ore jucate, bani cu numarare animata si clipire la castig/pierdere, job/XP si sete, texte "+$" care urca.
 */
public final class HypeHud {
    private static final int W = 236;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("d/M/yyyy");

    // stare de animatie (doar thread-ul de randare)
    private double shownMoney = Double.NaN;
    private double lastTarget = Double.NaN;
    private long flashAt;
    private int flashSign;
    private float slide;
    private float xpShown;
    private float thirstShown = 100;
    private long lastNs = System.nanoTime();

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameSettings.hideGUI) return;
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1_000_000_000f);
        lastNs = ns;

        // glisare la pornire/oprire (/hud)
        float target = HypeState.hudOn && HypeState.state.has("cash") ? 1f : 0f;
        slide += (target - slide) * Math.min(1f, dt * 8f);
        if (slide < 0.01f) return;

        JsonObject s = HypeState.state;
        MatrixStack ms = e.getMatrixStack();
        int x = 8 - Math.round((1f - slide) * (W + 16));
        int y = 6;
        double t = now / 1000.0;

        animateMoney(JsonClientModule.num(s, "cash", 0), dt, now);

        // fundal discret pentru lizibilitate
        Ui.round(ms, x - 4, y - 3, W + 8, bottom(s) + 6, Theme.alpha(0xFF050912, 0x58));

        drawClock(ms, mc, x, y, t);
        Ui.rect(ms, x + 84, y + 1, 1, 28, Theme.alpha(Theme.LINE, 0xAA));
        drawTitle(ms, x + 92, y + 1, t, now);
        drawEmblem(ms, x + W - 26, y + 1, t);
        Ui.rect(ms, x, y + 33, W, 1, Theme.alpha(Theme.LINE, 0xAA));
        drawIdentity(ms, mc, s, x, y + 39, t);
        drawMoney(ms, s, x, y + 60, now);
        int cy = y + 78;
        cy = drawJob(ms, s, x, cy, dt, t, now);
        drawThirst(ms, s, x, cy, dt, t);
        drawPopups(ms, x, y + bottom(s) + 6, now);
    }

    private static int bottom(JsonObject s) {
        int h = 74;
        if (!JsonClientModule.str(s, "job", "").isEmpty()) h += 16;
        if (JsonClientModule.bool(s, "thirstOn", false)) h += 12;
        return h;
    }

    // ------------------------------------------------------------------ elemente

    private static void scaled(MatrixStack ms, String text, float x, float y, int color, float scale) {
        ms.push();
        ms.translate(x, y, 0);
        ms.scale(scale, scale, 1f);
        Ui.textShadow(ms, text, 0, 0, color);
        ms.pop();
    }

    private static int scaledWidth(String text, float scale) {
        return Math.round(Ui.width(text) * scale);
    }

    private void drawClock(MatrixStack ms, Minecraft mc, int x, int y, double t) {
        long day = mc.world == null ? 0 : mc.world.getDayTime();
        long hour = (day / 1000 + 6) % 24;
        long min = (day % 1000) * 60 / 1000;
        boolean colon = ((long) (t * 2)) % 2 == 0; // clipeste secunda
        String time = String.format("%02d%s%02d", hour, colon ? ":" : " ", min);
        // cronometru mic
        Ui.round(ms, x + 1, y + 6, 8, 9, Theme.mix(Theme.PANEL, Theme.WARN, 0.6f));
        Ui.rect(ms, x + 4, y + 4, 2, 2, Theme.WARN);
        Ui.rect(ms, x + 5, y + 8, 1, 4, 0xFF3B2A06);
        scaled(ms, time, x + 13, y + 1, Theme.TEXT, 2.0f);
        scaled(ms, DATE.format(new Date()), x + 13, y + 20, Theme.MUTED, 1.0f);
    }

    /** Titlu cu o unda de lumina care trece peste litere. */
    private void drawTitle(MatrixStack ms, int x, int y, double t, long now) {
        String title = "FLOWCITY";
        float scale = 2.2f;
        boolean lvl = now - HypeState.levelUpAt < 2500;
        float cx = x;
        for (int i = 0; i < title.length(); i++) {
            String ch = title.substring(i, i + 1);
            double wave = Math.sin(t * 2.2 - i * 0.55);
            float k = (float) Math.pow(Math.max(0, wave), 6);
            int base = lvl ? Theme.WARN : Theme.ACCENT;
            int color = Theme.mix(base, 0xFFFFFFFF, k * 0.85f);
            float bob = (float) Math.sin(t * 2.2 - i * 0.55) * 0.6f;
            scaled(ms, ch, cx, y + bob, color, scale);
            cx += Ui.width(ch) * scale;
        }
        double pulse = 0.55 + 0.25 * Math.sin(t * 2.0);
        scaled(ms, "ROLEPLAY", x + 1, y + 21, Theme.mix(Theme.FAINT, Theme.MUTED, (float) pulse), 1.15f);
    }

    /** Emblema FlowCity: disc cu "FC" si un punct care orbiteaza. */
    private void drawEmblem(MatrixStack ms, int x, int y, double t) {
        int s = 26;
        Ui.round(ms, x, y, s, s, Theme.alpha(Theme.PANEL_3, 0xE0));
        Ui.frame(ms, x + 2, y + 2, s - 4, s - 4, Theme.mix(Theme.PANEL, Theme.ACCENT, 0.18f), Theme.ACCENT_DIM);
        Ui.textCenter(ms, "FC", x + s / 2, y + 9, Theme.TEXT);
        for (int i = 0; i < 3; i++) {
            double a = t * 1.6 - i * 0.35;
            int dx = x + s / 2 + (int) Math.round(Math.cos(a) * 11) - 1;
            int dy = y + s / 2 + (int) Math.round(Math.sin(a) * 11) - 1;
            Ui.rect(ms, dx, dy, 2, 2, Theme.alpha(Theme.ACCENT, 255 - i * 90));
        }
    }

    private void drawIdentity(MatrixStack ms, Minecraft mc, JsonObject s, int x, int y, double t) {
        // punct de stare (pulseaza cat timp pluginul raspunde)
        int dot = Theme.mix(Theme.OK, 0xFFFFFFFF, (float) (0.25 + 0.25 * Math.sin(t * 3.0)));
        Ui.rect(ms, x + 1, y + 4, 5, 5, dot);
        String name = JsonClientModule.str(s, "name", mc.player.getName().getString());
        float ns = 1.6f;
        scaled(ms, name, x + 12, y, Theme.TEXT, ns);
        int cx = x + 12 + scaledWidth(name, ns) + 8;
        int id = JsonClientModule.integer(s, "id", 0);
        if (id > 0) cx = chip(ms, "#" + id, cx, y - 1, Theme.ACCENT);
        long min = JsonClientModule.lng(s, "playMin", 0);
        cx = chip(ms, min >= 60 ? (min / 60) + "h" : min + "m", cx + 4, y - 1, Theme.VIOLET);
        Ui.textRight(ms, JsonClientModule.str(s, "rank", ""), x + W, y + 2, Theme.MUTED);
    }

    private static int chip(MatrixStack ms, String text, int x, int y, int color) {
        int w = Ui.width(text) + 10;
        Ui.frame(ms, x, y, w, 14, Theme.mix(0xFF0A1020, color, 0.14f), Theme.mix(Theme.PANEL, color, 0.55f));
        Ui.text(ms, text, x + 5, y + 3, color);
        return x + w;
    }

    private void animateMoney(double target, float dt, long now) {
        if (Double.isNaN(shownMoney)) {
            shownMoney = target;
            lastTarget = target;
            return;
        }
        if (target != lastTarget) {
            flashAt = now;
            flashSign = target > lastTarget ? 1 : -1;
            lastTarget = target;
        }
        double diff = target - shownMoney;
        shownMoney = Math.abs(diff) < 0.01 ? target : shownMoney + diff * Math.min(1.0, dt * 6.0);
    }

    /** Suma in format romanesc/german: 30.171 sau 1.234,50. */
    private static String germanMoney(double v) {
        boolean whole = Math.abs(v - Math.rint(v)) < 0.005;
        String s = whole ? String.format("%,d", (long) Math.rint(v)) : String.format("%,.2f", v);
        return s.replace(',', '#').replace('.', ',').replace('#', '.');
    }

    private void drawMoney(MatrixStack ms, JsonObject s, int x, int y, long now) {
        long since = now - flashAt;
        int color = Theme.TEXT;
        if (since < 900) {
            float k = 1f - since / 900f;
            color = Theme.mix(Theme.TEXT, flashSign > 0 ? Theme.OK : Theme.BAD, k);
        }
        String money = germanMoney(shownMoney);
        float sc = 1.5f;
        int coin = 12;
        int right = x + W - coin - 6;
        scaled(ms, money, right - scaledWidth(money, sc), y, color, sc);
        // moneda
        int cx = x + W - coin;
        Ui.round(ms, cx, y, coin, coin, Theme.alpha(Theme.OK, 0x55));
        Ui.round(ms, cx + 1, y + 1, coin - 2, coin - 2, Theme.OK);
        Ui.textCenter(ms, "$", cx + coin / 2, y + 2, 0xFF053B2A);
        // banca (stanga)
        Ui.text(ms, "Banca", x + 1, y + 1, Theme.FAINT);
        Ui.text(ms, "$" + germanMoney(JsonClientModule.num(s, "bank", 0)), x + 1, y + 11, Theme.MUTED);
    }

    private int drawJob(MatrixStack ms, JsonObject s, int x, int y, float dt, double t, long now) {
        String job = JsonClientModule.str(s, "job", "");
        if (job.isEmpty()) return y;
        long need = JsonClientModule.lng(s, "xpNeed", 0);
        float frac = need <= 0 ? 1f : Math.min(1f, (float) JsonClientModule.lng(s, "xp", 0) / need);
        xpShown += (frac - xpShown) * Math.min(1f, dt * 5f);
        if (Math.abs(frac - xpShown) < 0.002f) xpShown = frac;
        boolean lvl = now - HypeState.levelUpAt < 1800;
        Ui.text(ms, Ui.clip(job, 110), x + 1, y, Theme.TEXT);
        Ui.textRight(ms, "Niv " + JsonClientModule.integer(s, "level", 1), x + W, y, lvl ? Theme.WARN : Theme.ACCENT);
        int bx = x + 1;
        int bw = W - 2;
        Ui.bar(ms, bx, y + 10, bw, 5, xpShown, lvl ? Theme.WARN : Theme.ACCENT);
        // luciu care aluneca pe bara
        int gx = bx + 1 + (int) (((t * 0.6) % 1.0) * (bw - 2) * xpShown);
        if (xpShown > 0.02f) Ui.rect(ms, gx, y + 11, 2, 3, Theme.alpha(0xFFFFFFFF, 0x90));
        return y + 16;
    }

    private void drawThirst(MatrixStack ms, JsonObject s, int x, int y, float dt, double t) {
        if (!JsonClientModule.bool(s, "thirstOn", false)) return;
        float v = JsonClientModule.integer(s, "thirst", 100);
        thirstShown += (v - thirstShown) * Math.min(1f, dt * 5f);
        int col = v < 20 ? Theme.BAD : (v < 50 ? Theme.WARN : 0xFF38BDF8);
        if (v < 20) col = Theme.mix(Theme.BAD, 0xFFFFFFFF, (float) (0.2 + 0.2 * Math.sin(t * 7.0))); // pulseaza cand e critic
        Ui.text(ms, "Sete", x + 1, y, v < 20 ? col : Theme.MUTED);
        Ui.bar(ms, x + 32, y + 1, W - 34, 5, thirstShown / 100f, col);
    }

    private void drawPopups(MatrixStack ms, int x, int y, long now) {
        Iterator<HypeState.Popup> it = HypeState.popups.iterator();
        while (it.hasNext()) {
            HypeState.Popup p = it.next();
            long age = now - p.at;
            if (age > 2200) {
                it.remove();
                continue;
            }
            float k = age / 2200f;
            int alpha = (int) (255 * (k < 0.7f ? 1f : (1f - k) / 0.3f));
            int py = y - (int) (k * 18f);
            Ui.textShadow(ms, p.text, x + W - Ui.width(p.text) - 2, py, Theme.alpha(Theme.OK, Math.max(8, alpha)));
        }
    }
}
