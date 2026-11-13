package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Xmas;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * HUD FlowCity (stil "ceas + titlu + nume + bani"): ceas de joc si data, titlu cu stralucire, emblema care orbiteaza,
 * nume cu ID si ore jucate, bani cu numarare animata si clipire la castig/pierdere, job/XP si sete, texte "+$" care urca.
 */
public final class HypeHud {
    private static final int W = 236;
    private static final ResourceLocation LOGO = new ResourceLocation("flowcitycore", "textures/gui/logo.png");
    private static final int BLUE = 0xFF2F8CFF;      // albastru FlowCity (ca pe logo)
    private static final int ICE = 0xFFC8EBFF;       // sclipirea de gheata
    private static final SimpleDateFormat DATE = new SimpleDateFormat("d/M/yyyy");
    private static final String TITLE = "FLOW CITY";
    private static final float TITLE_SCALE = 1.9f;
    private static final String[] TITLE_CH = new String[TITLE.length()];
    private static final float[] TITLE_X = new float[TITLE.length()];

    private static boolean titleReady;

    /** Latimile literelor se calculeaza la primul cadru (fontul nu exista la incarcarea clasei). */
    private static void initTitle() {
        float cx = 0;
        for (int i = 0; i < TITLE.length(); i++) {
            TITLE_CH[i] = TITLE.substring(i, i + 1);
            TITLE_X[i] = cx;
            cx += Ui.width(TITLE_CH[i]) * TITLE_SCALE;
        }
        titleReady = true;
    }

    /** Marginea de jos a HUD-ului (0 daca e ascuns): notificarile se aseaza sub el. */
    public static volatile int occupiedBottom;

    // cache-uri: evita formatari de text la fiecare cadru
    private String dateStr = "";
    private long dateCheckedAt;
    private String timeStr = "";
    private int timeKey = -1;
    private String moneyStr = "";
    private long moneyKey = Long.MIN_VALUE;
    private String bankStr = "";
    private long bankKey = Long.MIN_VALUE;

    // stare de animatie (doar thread-ul de randare)
    private double shownMoney = Double.NaN;
    private double lastTarget = Double.NaN;
    private long flashAt;
    private int flashSign;
    private float slide;
    private float xpShown;
    private float thirstShown = 100;
    private long lastNs = System.nanoTime();

    private boolean failed;
    private static boolean broken;

    @SubscribeEvent
    public void onRender(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (broken) return;
        try {
            doRender(e);
        } catch (RuntimeException ex) {
            // un bug de desenare nu are voie sa dea crash jocului: logam o data si oprim elementul
            if (!failed) {
                failed = true;
                org.apache.logging.log4j.LogManager.getLogger("FlowCity").error("Eroare la desenarea overlay-ului " + getClass().getSimpleName(), ex);
            }
            broken = true;
        }
    }

    private void doRender(RenderGameOverlayEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1_000_000_000f);
        lastNs = ns;

        // glisare la pornire/oprire (/hud)
        float target = HypeState.hudOn && HypeState.state.has("cash") ? 1f : 0f;
        slide += (target - slide) * Math.min(1f, dt * 8f);
        if (slide < 0.01f) {
            occupiedBottom = 0;
            return;
        }

        JsonObject s = HypeState.state;
        GuiGraphics ms = new GuiGraphics(e.getMatrixStack());
        int sw = mc.getWindow().getGuiScaledWidth();
        int x = sw - W - 8 + Math.round((1f - slide) * (W + 16)); // dreapta sus, glisare dinspre dreapta
        int y = 6;
        occupiedBottom = slide > 0.5f ? y + bottom(s) + 8 : 0;
        double t = now / 1000.0;

        animateMoney(JsonClientModule.num(s, "cash", 0), dt, now);

        if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(ms, "hud", x - 4, y - 3, W + 8, bottom(s) + 6)) {
            occupiedBottom = 0;
            return;
        }
        try {
            // notificarile se aseaza sub panou doar daca panoul e in coltul lor (dreapta sus)
            float[] hr = ro.solarstudios.flowcity.client.ui.HudLayout.rect("hud");
            occupiedBottom = slide > 0.5f && hr[0] + hr[2] > sw - 230 ? Math.round(hr[1] + hr[3]) + 5 : 0;
            // fundal discret pentru lizibilitate
            if (HypeState.hudBg > 0) Ui.round(ms, x - 4, y - 3, W + 8, bottom(s) + 6, Theme.alpha(0xFF050912, HypeState.hudBg));

            if (HudLayout.part("hud", "clock")) drawClock(ms, mc, x, y, t);
            if (HudLayout.part("hud", "clock") || HudLayout.part("hud", "title")) Ui.rect(ms, x + 84, y + 1, 1, 28, Theme.alpha(Theme.LINE, 0xAA));
            if (HudLayout.part("hud", "title")) {
                drawTitle(ms, x + 92, y + 1, t, now);
                drawEmblem(ms, x + W - 34, y - 1, t);
            }
            if (Xmas.enabled) {
                Xmas.garland(ms, x - 4, y - 6, W + 8, t, 3, 22);
                Xmas.hat(ms, x + W - 30, y - 8, 14);
                Xmas.snow(ms, x - 4, y - 6, W + 8, bottom(s) + 10, t, 14, 190);
            }
            if (HudLayout.part("hud", "title")) drawBell(ms, x + W - 6, y - 3, t);
            Ui.rect(ms, x, y + 33, W, 1, Theme.alpha(Theme.LINE, 0xAA));
            if (HudLayout.part("hud", "identity")) drawIdentity(ms, mc, s, x, y + 39, t);
            if (HudLayout.part("hud", "money")) drawMoney(ms, s, x, y + 60, now);
            int cy = y + 78;
            if (HudLayout.part("hud", "job")) cy = drawJob(ms, s, x, cy, dt, t, now);
            if (HudLayout.part("hud", "thirst")) drawThirst(ms, s, x, cy, dt, t);
            drawPopups(ms, x, y + bottom(s) + 6, now);
        } finally {
            ro.solarstudios.flowcity.client.ui.HudLayout.end(ms);
        }
    }

    private static int bottom(JsonObject s) {
        int h = 74;
        if (!JsonClientModule.str(s, "job", "").isEmpty()) h += 16;
        if (JsonClientModule.bool(s, "thirstOn", false)) h += 12;
        return h;
    }

    // ------------------------------------------------------------------ elemente

    private static void scaled(GuiGraphics ms, String text, float x, float y, int color, float scale) {
        ms.pose().pushPose();
        ms.pose().translate(x, y, 0);
        ms.pose().scale(scale, scale, 1f);
        Ui.textShadow(ms, text, 0, 0, color);
        ms.pose().popPose();
    }

    private static int scaledWidth(String text, float scale) {
        return Math.round(Ui.width(text) * scale);
    }

    private void drawClock(GuiGraphics ms, Minecraft mc, int x, int y, double t) {
        long day = mc.level == null ? 0 : mc.level.getDayTime();
        long hour = (day / 1000 + 6) % 24;
        long min = (day % 1000) * 60 / 1000;
        boolean colon = ((long) (t * 2)) % 2 == 0; // clipeste secunda
        int key = (int) (hour * 120 + min * 2 + (colon ? 1 : 0));
        if (key != timeKey) {
            timeKey = key;
            timeStr = String.format("%02d%s%02d", hour, colon ? ":" : " ", min);
        }
        String time = timeStr;
        long nowMs = System.currentTimeMillis();
        if (nowMs - dateCheckedAt > 30_000) {
            dateCheckedAt = nowMs;
            dateStr = DATE.format(new Date(nowMs));
        }
        // cronometru mic
        Ui.round(ms, x + 1, y + 6, 8, 9, Theme.mix(Theme.PANEL, Theme.WARN, 0.6f));
        Ui.rect(ms, x + 4, y + 4, 2, 2, Theme.WARN);
        Ui.rect(ms, x + 5, y + 8, 1, 4, 0xFF3B2A06);
        scaled(ms, time, x + 13, y + 1, Theme.TEXT, 2.0f);
        scaled(ms, dateStr, x + 13, y + 20, Theme.MUTED, 1.0f);
    }

    /** Titlu cu o unda de lumina care trece peste litere. */
    private void drawTitle(GuiGraphics ms, int x, int y, double t, long now) {
        if (!titleReady) initTitle();
        boolean lvl = now - HypeState.levelUpAt < 2500;
        int base = lvl ? Theme.WARN : ro.solarstudios.flowcity.client.ui.HudLayout.accent("hud", BLUE);
        for (int i = 0; i < TITLE_CH.length; i++) {
            double wave = Math.sin(t * 2.2 - i * 0.55);
            double w2 = Math.max(0, wave);
            float k = (float) (w2 * w2 * w2 * w2 * w2 * w2);
            int color = Theme.mix(base, ICE, k * 0.9f);
            scaled(ms, TITLE_CH[i], x + TITLE_X[i], y + (float) wave * 0.6f, color, TITLE_SCALE);
        }
        double pulse = 0.55 + 0.25 * Math.sin(t * 2.0);
        scaled(ms, "ROLEPLAY", x + 1, y + 21, Theme.mix(Theme.FAINT, Theme.MUTED, (float) pulse), 1.15f);
    }

    /** Logo-ul FlowCity: plutire usoara + pulsatie, cu sclipiri de zapada in jur. */
    private void drawEmblem(GuiGraphics ms, int x, int y, double t) {
        int size = 34;
        float bob = (float) Math.sin(t * 1.6) * 1.2f;
        float pulse = 1f + (float) Math.sin(t * 2.0) * 0.025f;
        ms.pose().pushPose();
        ms.pose().translate(x + size / 2f, y + size / 2f + bob, 0);
        ms.pose().scale(pulse, pulse, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        ms.blit(LOGO, -size / 2, -size / 2, size, size, 0f, 0f, 128, 128, 128, 128);
        ms.pose().popPose();
        // sclipiri (fulgi) care palpaie in jurul logo-ului
        for (int i = 0; i < 4; i++) {
            double ph = t * 1.3 + i * 1.7;
            float a = (float) Math.max(0, Math.sin(ph));
            int fx = x + 2 + (int) ((i * 11 + Math.floor(ph / Math.PI) * 7) % (size - 4));
            int fy = y + 2 + (int) ((i * 17 + Math.floor(ph / Math.PI) * 5) % (size - 4));
            Ui.rect(ms, fx, fy, 1, 1, Theme.alpha(0xFFFFFFFF, (int) (30 + 200 * a)));
        }
    }

    /** Insigna rosie cu numarul de notificari necitite (pulseaza usor). */
    private void drawBell(GuiGraphics ms, int x, int y, double t) {
        int n = ro.solarstudios.flowcity.notify.NotifyState.unread;
        if (n <= 0) return;
        String s = n > 9 ? "9+" : String.valueOf(n);
        int w = Ui.width(s) + 8;
        int color = Theme.mix(Theme.BAD, 0xFFFFFFFF, (float) (0.1 + 0.1 * Math.sin(t * 4.0)));
        Ui.round(ms, x - w + 6, y, w, 11, color);
        Ui.textCenter(ms, s, x - w / 2 + 6, y + 2, 0xFFFFFFFF);
    }

    private void drawIdentity(GuiGraphics ms, Minecraft mc, JsonObject s, int x, int y, double t) {
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

    private static int chip(GuiGraphics ms, String text, int x, int y, int color) {
        int w = Ui.width(text) + 10;
        Ui.frame(ms, x, y, w, 14, Theme.alpha(Theme.mix(0xFF0A1020, color, 0.14f), 0x50), Theme.alpha(Theme.mix(Theme.PANEL, color, 0.55f), 0xA0));
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

    private void drawMoney(GuiGraphics ms, JsonObject s, int x, int y, long now) {
        long since = now - flashAt;
        int color = Theme.TEXT;
        if (since < 900) {
            float k = 1f - since / 900f;
            color = Theme.mix(Theme.TEXT, flashSign > 0 ? Theme.OK : Theme.BAD, k);
        }
        long mk = Math.round(shownMoney * 100);
        if (mk != moneyKey) {
            moneyKey = mk;
            moneyStr = germanMoney(shownMoney);
        }
        String money = moneyStr;
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
        double bank = JsonClientModule.num(s, "bank", 0);
        long bk = Math.round(bank * 100);
        if (bk != bankKey) {
            bankKey = bk;
            bankStr = "$" + germanMoney(bank);
        }
        Ui.text(ms, bankStr, x + 1, y + 11, Theme.MUTED);
    }

    private int drawJob(GuiGraphics ms, JsonObject s, int x, int y, float dt, double t, long now) {
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

    private void drawThirst(GuiGraphics ms, JsonObject s, int x, int y, float dt, double t) {
        if (!JsonClientModule.bool(s, "thirstOn", false)) return;
        float v = JsonClientModule.integer(s, "thirst", 100);
        thirstShown += (v - thirstShown) * Math.min(1f, dt * 5f);
        int col = v < 20 ? Theme.BAD : (v < 50 ? Theme.WARN : 0xFF38BDF8);
        if (v < 20) col = Theme.mix(Theme.BAD, 0xFFFFFFFF, (float) (0.2 + 0.2 * Math.sin(t * 7.0))); // pulseaza cand e critic
        Ui.text(ms, "Sete", x + 1, y, v < 20 ? col : Theme.MUTED);
        Ui.bar(ms, x + 32, y + 1, W - 34, 5, thirstShown / 100f, col);
    }

    private void drawPopups(GuiGraphics ms, int x, int y, long now) {
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
