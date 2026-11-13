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
import ro.solarstudios.flowcity.client.ui.Gfx;
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
    private long lastHour = -1;
    private long lastMin = -1;
    private String hhStr = "00";
    private String mmStr = "00";
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM.yyyy");
    private static final String TITLE = "FLOW CITY";
    private static final String[] TITLE_CHARS = new String[TITLE.length()];

    static {
        for (int i = 0; i < TITLE.length(); i++) TITLE_CHARS[i] = TITLE.substring(i, i + 1);
    }
    /** Latimea HUD-ului in unitatile machetei; se scaleaza cu K la ecran. */
    private static final float MW = 340f;
    private static final float K = W / MW;
    private static final int[] BULBS = {0xFFFF4B4B, 0xFF3DDC97, 0xFFFFC23D, 0xFF4FB6FF, 0xFFFF7AC6};

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
        float ease = 1f - (1f - slide) * (1f - slide) * (1f - slide);

        JsonObject s = HypeState.state;
        GuiGraphics g = new GuiGraphics(e.getMatrixStack());
        int sw = mc.getWindow().getGuiScaledWidth();
        float bottom = bottom(s);
        int x = sw - W - 10;
        int y = 8;
        occupiedBottom = slide > 0.5f ? y + Math.round(bottom * K) + 10 : 0;
        double t = now / 1000.0;
        animateMoney(JsonClientModule.num(s, "cash", 0), dt, now);

        g.pose().pushPose();
        g.pose().translate(x + (1f - ease) * (W + 30), y, 0);
        g.pose().scale(K, K, 1f);
        float a = Math.min(1f, slide * 1.4f);

        // umbra moale in spatele textului (fara panou)
        int scrim = HypeState.hudBg > 0 ? Math.min(0xC0, 0x40 + HypeState.hudBg / 2) : 0x40;
        Gfx.scrim(g, MW * 0.62f, bottom * 0.45f, MW * 0.78f, bottom * 0.95f, Gfx.fade(scrim << 24 | 0x050912, a));

        drawClock(g, mc, t, a);
        drawTitle(g, t, now, a);
        drawEmblem(g, t, a);
        if (Xmas.enabled) drawXmasTop(g, t, a);
        drawBell(g, t, a);
        drawIdentity(g, mc, s, t, a);
        drawMoney(g, s, now, a);
        float cy = 132;
        cy = drawJob(g, s, cy, dt, t, now, a);
        drawThirst(g, s, cy, dt, t, a);
        if (Xmas.enabled) drawSnow(g, t, bottom, a);
        drawPopups(g, bottom + 22, now);
        g.pose().popPose();
    }

    /** Inaltimea HUD-ului in unitatile machetei. */
    private static float bottom(JsonObject s) {
        float h = 128;
        if (!JsonClientModule.str(s, "job", "").isEmpty()) h += 26;
        if (JsonClientModule.bool(s, "thirstOn", false)) h += 20;
        return h;
    }

    // ------------------------------------------------------------------ elemente (coordonate de macheta, latime 340)

    private void drawClock(GuiGraphics g, Minecraft mc, double t, float a) {
        long day = mc.level == null ? 0 : mc.level.getDayTime();
        long hour = (day / 1000 + 6) % 24;
        long min = (day % 1000) * 60 / 1000;
        long nowMs = System.currentTimeMillis();
        if (nowMs - dateCheckedAt > 30_000) {
            dateCheckedAt = nowMs;
            dateStr = DATE.format(new Date(nowMs));
        }
        // cronometru desenat
        int amber = Gfx.fade(0xFFE9A23B, a);
        Gfx.rrect(g, 1, 12, 12, 13, 3.5f, amber);
        Gfx.rrect(g, 4.5f, 8, 5, 5, 1.5f, amber);
        Gfx.line(g, 7, 15, 7, 19.5f, 1.4f, Gfx.fade(0xFF3B2A06, a));
        // ora: "06" ":" "00", doua puncte care pulseaza
        if (hour != lastHour || min != lastMin) {
            lastHour = hour;
            lastMin = min;
            hhStr = (hour < 10 ? "0" : "") + hour;
            mmStr = (min < 10 ? "0" : "") + min;
        }
        String hh = hhStr;
        String mm = mmStr;
        float size = 34;
        float x0 = 21;
        Gfx.textS(g, hh, x0, 33, size, Gfx.fade(0xFFF4F1EA, a), 0, true, -0.5f);
        float wh = Gfx.width(hh, size, true) - 0.5f;
        float colon = 0.35f + 0.65f * (float) Math.max(0, Math.cos(t * Math.PI));
        Gfx.textS(g, ":", x0 + wh + 3, 31, size * 0.9f, Gfx.fade(0xFFF4F1EA, a * colon), 0, true, 0);
        Gfx.textS(g, mm, x0 + wh + 3 + Gfx.width(":", size * 0.9f, true) + 3, 33, size, Gfx.fade(0xFFF4F1EA, a), 0, true, -0.5f);
        Gfx.textS(g, dateStr, 22, 52, 12, Gfx.fade(0xFFD9D6CF, a), 0, false, 0.5f);
    }

    /** "FLOW CITY": o unda de gheata trece peste litere, care plutesc usor. Auriu la avansare in nivel. */
    private void drawTitle(GuiGraphics g, double t, long now, float a) {
        boolean lvl = now - HypeState.levelUpAt < 2500;
        int base = lvl ? 0xFFFFC23D : BLUE;
        float size = 23;
        float sp = 1.4f;
        float tw = Gfx.width(TITLE, size, false) + sp * (TITLE.length() - 1);
        float cx = 205;
        float x = cx - tw / 2;
        // halou albastru discret
        Gfx.scrim(g, cx, 20, tw * 0.62f, 13, Gfx.fade(Theme.alpha(base, 0x2A), a));
        double sweep = (t * 0.45) % 1.6 - 0.3; // pozitia undei 0..1 peste titlu, apoi pauza
        for (int i = 0; i < TITLE.length(); i++) {
            String ch = TITLE_CHARS[i];
            float cw = Gfx.width(ch, size, false);
            float u = (x - (cx - tw / 2) + cw / 2) / tw;
            float k = (float) Math.max(0, 1 - Math.abs(u - sweep) / 0.14);
            float bob = (float) Math.sin(t * 2.2 - i * 0.55) * 0.7f;
            int col = Gfx.mixArgb(base, ICE, k * 0.95f);
            Gfx.textS(g, ch, x, 28 + bob, size, Gfx.fade(col, a), 0, false, 0);
            x += cw + sp;
        }
        float pulse = 0.75f + 0.25f * (float) Math.sin(t * 2.0);
        Gfx.textS(g, "ROLEPLAY", cx, 48, 11, Gfx.fade(Gfx.mixArgb(0xFF8E9AAD, 0xFFC9D4E3, pulse), a), 1, false, 4.6f);
    }

    /** Logo-ul (singura imagine): plutire + pulsatie + halou, cu o steluta care il inconjoara. */
    private void drawEmblem(GuiGraphics g, double t, float a) {
        float size = 48;
        float cx = 316;
        float cy = 26 + (float) Math.sin(t * 1.6) * 1.4f;
        Gfx.disc(g, cx, cy, 30, Gfx.fade(0x3378C8FF, a), 0x0078C8FF, 0, 0);
        float pulse = 1f + (float) Math.sin(t * 2.0) * 0.025f;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(pulse, pulse, 1f);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.color4f(1f, 1f, 1f, a);
        Minecraft.getInstance().getTextureManager().bind(LOGO);
        RenderSystem.texParameter(3553, 10241, 9729);
        RenderSystem.texParameter(3553, 10240, 9729);
        g.blit(LOGO, (int) (-size / 2), (int) (-size / 2), (int) size, (int) size, 0f, 0f, 128, 128, 128, 128);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        g.pose().popPose();
        // steluta pe orbita eliptica (trece prin spatele logo-ului cand e "departe")
        double ang = t * 1.4;
        float ox = cx + (float) Math.cos(ang) * 30;
        float oy = cy + (float) Math.sin(ang) * 10 - 2;
        float depth = (float) (0.55 + 0.45 * Math.sin(ang));
        Gfx.disc(g, ox, oy, 7 * depth, Gfx.fade(0x6696D7FF, a * depth), 0x0096D7FF, 0, 0);
        sparkle(g, ox, oy, 4.5f * depth, Gfx.fade(0xFFE8F6FF, a * depth));
    }

    /** Sclipire cu 4 colturi (romb subtire + punct). */
    private static void sparkle(GuiGraphics g, float x, float y, float r, int col) {
        float w = r * 0.28f;
        Gfx.poly(g, new float[] {x, y - r, x + w, y, x, y + r, x - w, y}, col);
        Gfx.poly(g, new float[] {x - r, y, x, y - w, x + r, y, x, y + w}, col);
    }

    private void drawBell(GuiGraphics g, double t, float a) {
        int n = ro.solarstudios.flowcity.notify.NotifyState.unread;
        if (n <= 0) return;
        String s = n > 9 ? "9+" : String.valueOf(n);
        float pop = 1f + 0.08f * (float) Math.max(0, Math.sin(t * 4.0));
        float w = Math.max(17, Gfx.width(s, 10, false) + 9);
        g.pose().pushPose();
        g.pose().translate(336, 4, 0);
        g.pose().scale(pop, pop, 1f);
        Gfx.rrect(g, -w / 2 - 2, -10.5f, w + 4, 21, 10.5f, Gfx.fade(0xFF121419, a));
        Gfx.rrect(g, -w / 2, -8.5f, w, 17, 8.5f, Gfx.fade(0xFFE2453F, a));
        Gfx.text(g, s, 0, 3.6f, 10, Gfx.fade(0xFFFFFFFF, a), 1);
        g.pose().popPose();
    }

    private void drawIdentity(GuiGraphics g, Minecraft mc, JsonObject s, double t, float a) {
        float y = 82;
        float p = 0.5f + 0.5f * (float) Math.sin(t * 3.0);
        Gfx.disc(g, 4, y - 5.5f, 4 + 4 * p, Gfx.fade(0x664CC878, a * (1 - p * 0.6f)), 0x004CC878, 0, 0);
        Gfx.disc(g, 4, y - 5.5f, 4, Gfx.fade(0xFF4CC878, a));
        String name = JsonClientModule.str(s, "name", mc.player.getName().getString());
        Gfx.textS(g, name, 14, y, 16, Gfx.fade(0xFFF4F1EA, a), 0, false, 0);
        float cx = 14 + Gfx.width(name, 16, false) + 9;
        int id = JsonClientModule.integer(s, "id", 0);
        if (id > 0) cx = chip(g, "#" + id, cx, y, 0xFF5FE0D6, a) + 6;
        long min = JsonClientModule.lng(s, "playMin", 0);
        chip(g, min >= 60 ? (min / 60) + "h" : min + "m", cx, y, 0xFFA9B2FF, a);
        Gfx.textS(g, JsonClientModule.str(s, "rank", ""), MW, y - 1, 12, Gfx.fade(0xFFC9C7C0, a), 2, false, 0);
    }

    private static float chip(GuiGraphics g, String text, float x, float base, int color, float a) {
        // cifrele mari, centrate in pastila
        float w = Math.max(30, Gfx.width(text, 14, true) + 18);
        Gfx.rrect(g, x, base - 17, w, 23, 7, Gfx.fade(Theme.alpha(color, 0x8C), a));
        Gfx.rrect(g, x + 1, base - 16, w - 2, 21, 6, Gfx.fade(Gfx.mixArgb(0xFF10141C, color, 0.22f), a * 0.92f));
        Gfx.text(g, text, x + w / 2f, base, 14, Gfx.fade(color, a), 1, true, 0);
        return x + w;
    }

    private void drawMoney(GuiGraphics g, JsonObject s, long now, float a) {
        long since = now - flashAt;
        int color = 0xFFF4F1EA;
        float bump = 0;
        if (since < 900) {
            float k = 1f - since / 900f;
            color = Gfx.mixArgb(0xFFF4F1EA, flashSign > 0 ? 0xFF5FDC8F : 0xFFFF6A5E, k);
            bump = (float) Math.sin(Math.min(1f, since / 250f) * Math.PI) * 2.5f * flashSign;
        }
        long mk = Math.round(shownMoney * 100);
        if (mk != moneyKey) {
            moneyKey = mk;
            moneyStr = germanMoney(shownMoney);
        }
        // moneda verde
        float cx = 314;
        float cy = 96;
        Gfx.disc(g, cx + 13, cy + 13, 21, Gfx.fade(0x444CC878, a), 0x004CC878, 0, 0);
        Gfx.rrect(g, cx, cy, 26, 26, 7, Gfx.fade(0xFF5FE39A, a), Gfx.fade(0xFF148A4B, a));
        Gfx.text(g, "$", cx + 13, cy + 19, 15, Gfx.fade(0xFF053B2A, a), 1, true, 0);
        Gfx.textS(g, moneyStr, 304, 122 - bump, 32, Gfx.fade(color, a), 2, true, -0.3f);
        // banca
        Gfx.textS(g, "BANCA", 0, 104, 11, Gfx.fade(0xFFB5B3AC, a), 0, false, 0.66f);
        double bank = JsonClientModule.num(s, "bank", 0);
        long bk = Math.round(bank * 100);
        if (bk != bankKey) {
            bankKey = bk;
            bankStr = "$" + germanMoney(bank);
        }
        Gfx.textS(g, bankStr, 0, 120, 13, Gfx.fade(0xFFE8E5DE, a), 0, false, 0);
    }

    /** Bara cu umplere in gradient, stralucire si un luciu care aluneca. */
    private static void glowBar(GuiGraphics g, float x, float y, float w, float frac, int c0, int c1, double t, float a) {
        float h = 5;
        Gfx.rrect(g, x, y, w, h, 2.5f, Gfx.fade(0x730A0C10, a));
        float f = Math.max(0f, Math.min(1f, frac));
        if (f <= 0.001f) return;
        float fw = Math.max(h, w * f);
        Gfx.rrect(g, x - 2, y - 2, fw + 4, h + 4, 4.5f, Gfx.fade(Theme.alpha(c1, 0x30), a));
        // gradient orizontal din segmente
        int seg = 12;
        for (int i = 0; i < seg; i++) {
            float sx = x + fw * i / seg;
            float ex = x + fw * (i + 1) / seg;
            int col = Gfx.mixArgb(c0, c1, (i + 0.5f) / seg);
            if (i == 0 || i == seg - 1) Gfx.rrect(g, i == 0 ? sx : sx - 2.5f, y, ex - sx + 2.5f, h, 2.5f, Gfx.fade(col, a));
            else Gfx.rrect(g, sx, y, ex - sx + 0.4f, h, 0, Gfx.fade(col, a));
        }
        float gx = x + (float) ((t * 0.5) % 1.0) * fw;
        Gfx.rrect(g, gx - 6, y + 1, 12, h - 2, 1.5f, Gfx.fade(0x66FFFFFF, a * Math.min(1f, fw / 30f)));
    }

    private float drawJob(GuiGraphics g, JsonObject s, float y, float dt, double t, long now, float a) {
        String job = JsonClientModule.str(s, "job", "");
        if (job.isEmpty()) return y;
        long need = JsonClientModule.lng(s, "xpNeed", 0);
        float frac = need <= 0 ? 1f : Math.min(1f, (float) JsonClientModule.lng(s, "xp", 0) / need);
        xpShown += (frac - xpShown) * Math.min(1f, dt * 5f);
        if (Math.abs(frac - xpShown) < 0.002f) xpShown = frac;
        boolean lvl = now - HypeState.levelUpAt < 1800;
        Gfx.textS(g, job, 0, y + 11, 12, Gfx.fade(0xFFF4F1EA, a), 0, false, 0);
        Gfx.textS(g, "Niv " + JsonClientModule.integer(s, "level", 1), MW, y + 11, 12, Gfx.fade(lvl ? 0xFFFFC23D : 0xFFE9A23B, a), 2, false, 0);
        glowBar(g, 0, y + 16, MW, xpShown, lvl ? 0xFFFFE08A : 0xFFF6C15A, lvl ? 0xFFFFC23D : 0xFFEE9A3A, t, a);
        return y + 26;
    }

    private void drawThirst(GuiGraphics g, JsonObject s, float y, float dt, double t, float a) {
        if (!JsonClientModule.bool(s, "thirstOn", false)) return;
        float v = JsonClientModule.integer(s, "thirst", 100);
        thirstShown += (v - thirstShown) * Math.min(1f, dt * 5f);
        int c0 = 0xFF3BB8B0;
        int c1 = 0xFF4FC8E0;
        int lab = 0xFFB5B3AC;
        if (v < 50) {
            c0 = 0xFFF6C15A;
            c1 = 0xFFEE9A3A;
        }
        if (v < 20) {
            float p = 0.5f + 0.5f * (float) Math.sin(t * 7.0);
            c0 = Gfx.mixArgb(0xFFE2453F, 0xFFFF9A8F, p);
            c1 = 0xFFE2453F;
            lab = c0;
        }
        Gfx.textS(g, "SETE", 0, y + 12, 11, Gfx.fade(lab, a), 0, false, 0.66f);
        glowBar(g, 44, y + 6.5f, MW - 44 - 40, thirstShown / 100f, c0, c1, t, a);
        Gfx.textS(g, Math.round(thirstShown) + "%", MW, y + 12, 11, Gfx.fade(0xFFD9D6CF, a), 2, false, 0);
    }

    // ------------------------------------------------------------------ Craciun (desenat, fara imagini)

    /** Ghirlanda cu fir in valuri si beculete care sclipesc pe rand + caciula de mos pe logo + zapada pe titlu. */
    private void drawXmasTop(GuiGraphics g, double t, float a) {
        float x0 = -14;
        float x1 = MW + 10;
        float y0 = -6;
        float seg = 44;
        float sag = 6;
        float px = x0;
        float py = y0;
        for (float xx = x0 + 2; xx <= x1; xx += 2) {
            float u = ((xx - x0) % seg) / seg;
            float yy = y0 + (float) Math.sin(u * Math.PI) * sag;
            Gfx.line(g, px, py, xx, yy, 1.3f, Gfx.fade(0xFF1E4A33, a));
            px = xx;
            py = yy;
        }
        int i = 0;
        for (float bx = x0 + 11; bx < x1; bx += 22, i++) {
            float u = ((bx - x0) % seg) / seg;
            float by = y0 + (float) Math.sin(u * Math.PI) * sag + 3.5f;
            int c = BULBS[i % BULBS.length];
            // undita de lumina care trece pe ghirlanda + licarire proprie
            float wave = (float) Math.max(0, Math.cos((bx / 60.0) - t * 2.4));
            float on = 0.45f + 0.55f * Math.max(wave * wave, (float) Math.max(0, Math.sin(t * 3.1 + i * 1.9)) * 0.6f);
            Gfx.disc(g, bx, by + 1, 9 * on, Gfx.fade(Theme.alpha(c, 0x55), a * on), c & 0x00FFFFFF, 0, 0);
            Gfx.rrect(g, bx - 1.5f, by - 3.5f, 3, 2.5f, 0.8f, Gfx.fade(0xFF2F3A33, a));
            Gfx.disc(g, bx, by + 1.5f, 2.6f, Gfx.fade(Gfx.mixArgb(Gfx.mixArgb(c, 0xFF000000, 0.45f), c, on), a));
            Gfx.disc(g, bx - 0.8f, by + 0.6f, 0.9f, Gfx.fade(0xCCFFFFFF, a * on));
        }
        // zapada asezata pe titlu
        float cx = 205;
        for (int k = 0; k < 9; k++) {
            float sx = cx - 58 + k * 14.5f;
            float r = 2.2f + (k * 7 % 3) * 0.6f;
            Gfx.disc(g, sx, 3.5f - r * 0.3f, r, Gfx.fade(0xF2F7FBFF, a), Gfx.fade(0xE6E8F2FA, a), 0, -r * 0.4f);
        }
        // caciula de mos pe logo (usor inclinata, ciucurele se leagana)
        float hx = 316;
        float hy = 6 + (float) Math.sin(t * 1.6) * 1.4f;
        float sw = (float) Math.sin(t * 2.2) * 2.5f;
        Gfx.poly(g, new float[] {hx - 15, hy + 4, hx - 6, hy - 9, hx + 4, hy - 15, hx + 17 + sw, hy - 12, hx + 9, hy - 6, hx + 14, hy + 2}, Gfx.fade(0xFFD7263D, a));
        Gfx.poly(g, new float[] {hx - 6, hy - 9, hx + 4, hy - 15, hx + 2, hy - 8}, Gfx.fade(0xFFB01C30, a));
        Gfx.rrect(g, hx - 17, hy + 1, 33, 7, 3.5f, Gfx.fade(0xFFF7F9FC, a), Gfx.fade(0xFFDCE3EC, a));
        Gfx.disc(g, hx + 18 + sw, hy - 12, 4, Gfx.fade(0xFFF7F9FC, a), Gfx.fade(0xFFDCE3EC, a), -1, -1);
    }

    /** Fulgi netezi care cad prin zona HUD-ului (pozitii din timp, fara stare). */
    private static void drawSnow(GuiGraphics g, double t, float h, float a) {
        float top = -12;
        float hh = h + 30;
        for (int i = 0; i < 22; i++) {
            double seed = i * 12.9898;
            double speed = 10 + (i * 37 % 17);
            double fx = ((Math.sin(seed) * 43758.5453) % 1 + 1) % 1;
            double fy = ((t * speed / hh + ((Math.sin(seed * 1.7) * 9183.3) % 1 + 1) % 1) % 1);
            float x = (float) (fx * (MW + 30) - 15 + Math.sin(t * 0.8 + i) * 5);
            float y = top + (float) (fy * hh);
            float r = (i % 4 == 0) ? 1.9f : (i % 3 == 0 ? 1.4f : 1.0f);
            float edge = (float) Math.min(1, Math.min(fy * 6, (1 - fy) * 4));
            if (r > 1.8f) sparkle(g, x, y, r * 1.9f, Gfx.fade(0xD0FFFFFF, a * edge));
            else Gfx.disc(g, x, y, r, Gfx.fade(0xE6FFFFFF, a * edge));
        }
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

    /** "+$250" care urca sub HUD, cu o mica crestere la aparitie si stralucire verde. */
    private void drawPopups(GuiGraphics g, float y, long now) {
        Iterator<HypeState.Popup> it = HypeState.popups.iterator();
        int n = 0;
        while (it.hasNext()) {
            HypeState.Popup p = it.next();
            long age = now - p.at;
            if (age > 2200) {
                it.remove();
                continue;
            }
            float k = age / 2200f;
            float alpha = k < 0.7f ? 1f : (1f - k) / 0.3f;
            float pop = age < 180 ? 0.7f + 0.3f * age / 180f + 0.12f * (float) Math.sin(age / 180f * Math.PI) : 1f;
            float py = y + n * 20 - k * 26f;
            boolean minus = p.text.startsWith("-");
            int col = minus ? 0xFFFF6A5E : 0xFF5FDC8F;
            g.pose().pushPose();
            g.pose().translate(MW - 2, py, 0);
            g.pose().scale(pop, pop, 1f);
            float w = Gfx.width(p.text, 16, true);
            Gfx.scrim(g, -w / 2, -6, w * 0.8f, 12, Gfx.fade(Theme.alpha(col, 0x30), alpha));
            Gfx.textS(g, p.text, 0, 0, 16, Gfx.fade(col, alpha), 2, true, 0);
            g.pose().popPose();
            n++;
        }
    }
}
