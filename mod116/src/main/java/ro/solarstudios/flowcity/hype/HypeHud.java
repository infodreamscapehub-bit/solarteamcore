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
    private static final int W2 = 232;
    private static final int W3 = 214;
    private static final int ORANGE = 0xFFFF9A3C;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm");
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
    private String realStr = "";
    private long realAt;
    private String bank2Str = "";
    private long bank2Key = Long.MIN_VALUE;
    private String cashPill = "";
    private String bankPill = "";
    private String cOnline = "0";
    private String cId = "-";
    private String cIdHash = "";
    private String cHours = "0:00";
    private String cHoursShort = "0m";
    private String cOnlineLong = "0 online";
    private JsonObject piecesState;
    private java.util.List<Piece> pieces = new java.util.ArrayList<>();

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
        refreshCache(s, mc, now);
        int style = HudLayout.styleOf();
        int w = widthOf(style);
        int bh = bottom(s, style);
        GuiGraphics ms = new GuiGraphics(e.getMatrixStack());
        int sw = mc.getWindow().getGuiScaledWidth();
        int x = sw - w - 8 + Math.round((1f - slide) * (w + 16)); // dreapta sus, glisare dinspre dreapta
        int y = 6;
        occupiedBottom = slide > 0.5f ? y + bh + 8 : 0;
        double t = now / 1000.0;

        animateMoney(JsonClientModule.num(s, "cash", 0), dt, now);

        if (!ro.solarstudios.flowcity.client.ui.HudLayout.begin(ms, "hud", x - 4, y - 3, w + 8, bh + 6)) {
            occupiedBottom = 0;
            return;
        }
        try {
            // notificarile se aseaza sub panou doar daca panoul e in coltul lor (dreapta sus)
            float[] hr = ro.solarstudios.flowcity.client.ui.HudLayout.rect("hud");
            occupiedBottom = slide > 0.5f && hr[0] + hr[2] > sw - 230 ? Math.round(hr[1] + hr[3]) + 5 : 0;
            // fundal discret pentru lizibilitate
            if (HypeState.hudBg > 0 && style != 3) Ui.round(ms, x - 4, y - 3, w + 8, bh + 6, Theme.alpha(0xFF050912, HypeState.hudBg));
            if (style == 2) drawStyle2(ms, mc, s, x, y, dt, t, now);
            else if (style == 3) drawStyle3(ms, mc, s, x, y, dt, t, now);
            else drawStyle1(ms, mc, s, x, y, bh, dt, t, now);
            drawPopups(ms, x, y + bh + 6, now, w);
        } finally {
            ro.solarstudios.flowcity.client.ui.HudLayout.end(ms);
        }
    }

    /** Stilul 1 (cel original): ceas + titlu + nume + bani. */
    private void drawStyle1(GuiGraphics ms, Minecraft mc, JsonObject s, int x, int y, int bh, float dt, double t, long now) {
        if (HudLayout.part("hud", "clock")) drawClock(ms, mc, x, y, t);
        if (HudLayout.part("hud", "clock") || HudLayout.part("hud", "title")) Ui.rect(ms, x + 84, y + 1, 1, 28, Theme.alpha(Theme.LINE, 0xAA));
        if (HudLayout.part("hud", "title")) {
            drawTitle(ms, x + 92, y + 1, t, now);
            drawEmblem(ms, x + W - 34, y - 1, t);
        }
        if (Xmas.enabled) {
            Xmas.garland(ms, x - 4, y - 6, W + 8, t, 3, 22);
            Xmas.hat(ms, x + W - 30, y - 8, 14);
            Xmas.snow(ms, x - 4, y - 6, W + 8, bh + 10, t, 14, 190);
        }
        if (HudLayout.part("hud", "title")) drawBell(ms, x + W - 6, y - 3, t);
        Ui.rect(ms, x, y + 33, W, 1, Theme.alpha(Theme.LINE, 0xAA));
        if (HudLayout.part("hud", "identity")) drawIdentity(ms, mc, s, x, y + 39, t);
        if (HudLayout.part("hud", "money")) drawMoney(ms, s, x, y + 60, now);
        int cy = y + 78;
        if (HudLayout.part("hud", "job")) cy = drawJob(ms, s, x, cy, dt, t, now, W);
        if (HudLayout.part("hud", "thirst")) drawThirst(ms, s, x, cy, dt, t, W);
    }

    private static int bottom(JsonObject s, int style) {
        int h = style == 2 ? 140 : (style == 3 ? 78 : 74);
        if (!JsonClientModule.str(s, "job", "").isEmpty()) h += 16;
        if (JsonClientModule.bool(s, "thirstOn", false)) h += 12;
        return h;
    }

    /** Latimea panoului pentru un stil (folosita si de editorul de HUD). */
    public static int widthOf(int style) {
        return style == 2 ? W2 : (style == 3 ? W3 : W);
    }

    /** Inaltimea aproximativa (fara job/sete) pentru un stil, ca sa se poata afisa chenarul in editor. */
    public static int heightOf(int style) {
        return style == 2 ? 140 : (style == 3 ? 78 : 74);
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
        java.util.List<Piece> np = pieces;
        float ns = fitScale(np, 1.6f, 112);
        int cx = x + 12 + drawPieces(ms, np, x + 12, y + Math.round((1.6f - ns) * 3f), ns) + 8;
        int id = JsonClientModule.integer(s, "id", 0);
        if (id > 0) cx = chip(ms, "#" + id, cx, y - 1, Theme.ACCENT);
        long min = JsonClientModule.lng(s, "playMin", 0);
        cx = chip(ms, min >= 60 ? (min / 60) + "h" : min + "m", cx + 4, y - 1, Theme.VIOLET);
        if (HudLayout.part("hud", "rank")) rankChip(ms, s, cx + 4, y - 1);
    }

    /** Casuta de rank (ca cea cu id-ul si orele): textul in culorile din LuckPerms/birdflop (rankSegs) sau simplu. */
    private static int rankChip(GuiGraphics ms, JsonObject s, int x, int y) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        java.util.List<Integer> cols = new java.util.ArrayList<>();
        int total = 0;
        if (s.has("rankSegs") && s.get("rankSegs").isJsonArray() && s.getAsJsonArray("rankSegs").size() > 0) {
            for (com.google.gson.JsonElement e : s.getAsJsonArray("rankSegs")) {
                JsonObject o = e.getAsJsonObject();
                String seg = (JsonClientModule.bool(o, "b", false) ? "\u00a7l" : "") + JsonClientModule.str(o, "t", "");
                parts.add(seg);
                cols.add(0xFF000000 | JsonClientModule.integer(o, "c", 0xFFFFFF));
                total += Ui.width(seg);
            }
        } else {
            String r = JsonClientModule.str(s, "rank", "");
            if (r.isEmpty()) return x;
            parts.add(r);
            cols.add(Theme.TEXT);
            total = Ui.width(r);
        }
        int w = total + 10;
        int edge = cols.get(0);
        Ui.frame(ms, x, y, w, 14, Theme.alpha(Theme.mix(0xFF0A1020, edge, 0.14f), 0x50), Theme.alpha(Theme.mix(Theme.PANEL, edge, 0.55f), 0xA0));
        int rx = x + 5;
        for (int i = 0; i < parts.size(); i++) {
            Ui.text(ms, parts.get(i), rx, y + 3, cols.get(i));
            rx += Ui.width(parts.get(i));
        }
        return x + w;
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

    private int drawJob(GuiGraphics ms, JsonObject s, int x, int y, float dt, double t, long now, int ww) {
        String job = JsonClientModule.str(s, "job", "");
        if (job.isEmpty()) return y;
        long need = JsonClientModule.lng(s, "xpNeed", 0);
        float frac = need <= 0 ? 1f : Math.min(1f, (float) JsonClientModule.lng(s, "xp", 0) / need);
        xpShown += (frac - xpShown) * Math.min(1f, dt * 5f);
        if (Math.abs(frac - xpShown) < 0.002f) xpShown = frac;
        boolean lvl = now - HypeState.levelUpAt < 1800;
        Ui.text(ms, Ui.clip(job, 110), x + 1, y, Theme.TEXT);
        Ui.textRight(ms, "Niv " + JsonClientModule.integer(s, "level", 1), x + ww, y, lvl ? Theme.WARN : Theme.ACCENT);
        int bx = x + 1;
        int bw = ww - 2;
        Ui.bar(ms, bx, y + 10, bw, 5, xpShown, lvl ? Theme.WARN : Theme.ACCENT);
        // luciu care aluneca pe bara
        int gx = bx + 1 + (int) (((t * 0.6) % 1.0) * (bw - 2) * xpShown);
        if (xpShown > 0.02f) Ui.rect(ms, gx, y + 11, 2, 3, Theme.alpha(0xFFFFFFFF, 0x90));
        return y + 16;
    }

    private void drawThirst(GuiGraphics ms, JsonObject s, int x, int y, float dt, double t, int ww) {
        if (!JsonClientModule.bool(s, "thirstOn", false)) return;
        float v = JsonClientModule.integer(s, "thirst", 100);
        thirstShown += (v - thirstShown) * Math.min(1f, dt * 5f);
        int col = v < 20 ? Theme.BAD : (v < 50 ? Theme.WARN : 0xFF38BDF8);
        if (v < 20) col = Theme.mix(Theme.BAD, 0xFFFFFFFF, (float) (0.2 + 0.2 * Math.sin(t * 7.0))); // pulseaza cand e critic
        Ui.text(ms, "Sete", x + 1, y, v < 20 ? col : Theme.MUTED);
        Ui.bar(ms, x + 32, y + 1, ww - 34, 5, thirstShown / 100f, col);
    }

    private void drawPopups(GuiGraphics ms, int x, int y, long now, int w) {
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
            Ui.textShadow(ms, p.text, x + w - Ui.width(p.text) - 2, py, Theme.alpha(Theme.OK, Math.max(8, alpha)));
        }
    }

    // ------------------------------------------------------------------ nume cu prefix / sufix (LuckPerms)

    /** O bucata de text colorata din numele afisat (prefix, nume, sufix). */
    private static final class Piece {
        final String t;
        final int c;

        Piece(String t, int c) {
            this.t = t;
            this.c = c;
        }
    }

    private static void addSegs(java.util.List<Piece> out, JsonObject s, String segKey, String plainKey, int def) {
        if (s.has(segKey) && s.get(segKey).isJsonArray() && s.getAsJsonArray(segKey).size() > 0) {
            for (com.google.gson.JsonElement e : s.getAsJsonArray(segKey)) {
                JsonObject o = e.getAsJsonObject();
                String seg = (JsonClientModule.bool(o, "b", false) ? "§l" : "") + JsonClientModule.str(o, "t", "");
                out.add(new Piece(seg, 0xFF000000 | JsonClientModule.integer(o, "c", 0xFFFFFF)));
            }
        } else {
            String p = JsonClientModule.str(s, plainKey, "");
            if (!p.isEmpty()) out.add(new Piece(p, def));
        }
    }

    /** Numele jucatorului dupa formatul din hype.yml ({prefix}{name}{suffix}), cu culorile din LuckPerms. */
    private static java.util.List<Piece> namePieces(JsonObject s, Minecraft mc, int nameColor) {
        String name = JsonClientModule.str(s, "name", mc.player == null ? "" : mc.player.getName().getString());
        String fmt = JsonClientModule.str(s, "nameFormat", "{prefix}{name}{suffix}");
        java.util.List<Piece> out = new java.util.ArrayList<>();
        boolean hasName = false;
        int i = 0;
        while (i < fmt.length()) {
            int j = fmt.indexOf('{', i);
            if (j < 0) {
                out.add(new Piece(fmt.substring(i), nameColor));
                break;
            }
            if (j > i) out.add(new Piece(fmt.substring(i, j), nameColor));
            int k = fmt.indexOf('}', j);
            if (k < 0) {
                out.add(new Piece(fmt.substring(j), nameColor));
                break;
            }
            String key = fmt.substring(j + 1, k);
            if (key.equals("prefix")) {
                int before = out.size();
                addSegs(out, s, "prefixSegs", "prefix", nameColor);
                if (out.size() > before && k + 1 < fmt.length() && fmt.charAt(k + 1) == '{') out.add(new Piece(" ", nameColor));
            } else if (key.equals("name")) {
                out.add(new Piece(name, nameColor));
                hasName = true;
            } else if (key.equals("suffix")) {
                int before = out.size();
                if (j > 0 && fmt.charAt(j - 1) == '}') out.add(new Piece(" ", nameColor));
                addSegs(out, s, "suffixSegs", "suffix", nameColor);
                if (out.size() == before + 1 && out.get(before).t.equals(" ")) out.remove(before);
            } else {
                out.add(new Piece(fmt.substring(j, k + 1), nameColor));
            }
            i = k + 1;
        }
        if (!hasName) out.add(new Piece(name, nameColor));
        return out;
    }

    private static float fitScale(java.util.List<Piece> ps, float desired, int maxW) {
        int total = 0;
        for (Piece p : ps) total += Ui.width(p.t);
        if (total <= 0) return desired;
        return total * desired > maxW ? Math.max(1.0f, maxW / (float) total) : desired;
    }

    private static int drawPieces(GuiGraphics ms, java.util.List<Piece> ps, float x, float y, float scale) {
        float cx = x;
        for (Piece p : ps) {
            scaled(ms, p.t, cx, y, p.c, scale);
            cx += Ui.width(p.t) * scale;
        }
        return Math.round(cx - x);
    }

    private static int rankChipWidth(JsonObject s) {
        int total = 0;
        if (s.has("rankSegs") && s.get("rankSegs").isJsonArray() && s.getAsJsonArray("rankSegs").size() > 0) {
            for (com.google.gson.JsonElement e : s.getAsJsonArray("rankSegs")) {
                JsonObject o = e.getAsJsonObject();
                total += Ui.width((JsonClientModule.bool(o, "b", false) ? "§l" : "") + JsonClientModule.str(o, "t", ""));
            }
        } else {
            String r = JsonClientModule.str(s, "rank", "");
            if (r.isEmpty()) return 0;
            total = Ui.width(r);
        }
        return total + 10;
    }

    /** Textele care se schimba rar (nume, ID, jucatori, ore) se refac doar cand vine o stare noua de la server. */
    private void refreshCache(JsonObject s, Minecraft mc, long now) {
        if (now - realAt > 1000) {
            realAt = now;
            realStr = TIME.format(new Date(now));
        }
        if (s == piecesState) return;
        piecesState = s;
        pieces = namePieces(s, mc, Theme.TEXT);
        int online = JsonClientModule.integer(s, "online", 1);
        int id = JsonClientModule.integer(s, "id", 0);
        long min = JsonClientModule.lng(s, "playMin", 0);
        cOnline = String.valueOf(online);
        cOnlineLong = online + " online";
        cId = id > 0 ? String.valueOf(id) : "-";
        cIdHash = id > 0 ? "#" + id : "";
        long m = min % 60;
        cHours = (min / 60) + ":" + (m < 10 ? "0" : "") + m;
        cHoursShort = min >= 60 ? (min / 60) + "h " + m + "m" : min + "m";
    }

    private int moneyColor(long now) {
        long since = now - flashAt;
        if (since < 900) return Theme.mix(Theme.TEXT, flashSign > 0 ? Theme.OK : Theme.BAD, 1f - since / 900f);
        return Theme.TEXT;
    }

    private void refreshMoney2(JsonObject s) {
        long mk = Math.round(shownMoney * 100);
        if (mk != moneyKey) {
            moneyKey = mk;
            moneyStr = germanMoney(shownMoney);
            cashPill = "$" + moneyStr;
        } else if (cashPill.isEmpty()) {
            cashPill = "$" + moneyStr;
        }
        double bank = JsonClientModule.num(s, "bank", 0);
        long bk = Math.round(bank * 100);
        if (bk != bank2Key) {
            bank2Key = bk;
            bank2Str = germanMoney(bank);
            bankPill = "$" + bank2Str;
        }
    }

    // ------------------------------------------------------------------ stilul 2 (antet mare + casute + cash / card)

    private static void icon2(GuiGraphics ms, int kind, float cx, float cy) {
        if (kind == 0) { // jucatori
            int c = 0xFF4A8FD8;
            Gfx.disc(ms, cx - 2.5f, cy - 3f, 2.4f, c);
            Gfx.rrect(ms, cx - 6.5f, cy, 8f, 5.5f, 2.6f, c);
            Gfx.disc(ms, cx + 3.6f, cy - 2f, 2f, 0xFF2D6199);
            Gfx.rrect(ms, cx + 0.8f, cy + 1.5f, 6.4f, 4.5f, 2.2f, 0xFF2D6199);
        } else if (kind == 1) { // #
            int c = 0xFF3FA0D8;
            Gfx.line(ms, cx - 2f, cy - 6f, cx - 4f, cy + 6f, 1.5f, c);
            Gfx.line(ms, cx + 4f, cy - 6f, cx + 2f, cy + 6f, 1.5f, c);
            Gfx.line(ms, cx - 6f, cy - 2f, cx + 6f, cy - 2f, 1.5f, c);
            Gfx.line(ms, cx - 6f, cy + 3f, cx + 6f, cy + 3f, 1.5f, c);
        } else if (kind == 2) { // calendar
            Gfx.rrect(ms, cx - 6f, cy - 5f, 12f, 11f, 2f, 0xFFD8584E);
            Gfx.rrect(ms, cx - 5f, cy - 1f, 10f, 6f, 1f, 0xFFF1ECE8);
            Gfx.rrect(ms, cx - 3.5f, cy - 7f, 1.6f, 3.5f, 0f, 0xFFB8C4D4);
            Gfx.rrect(ms, cx + 2f, cy - 7f, 1.6f, 3.5f, 0f, 0xFFB8C4D4);
            Gfx.disc(ms, cx - 2f, cy + 2f, 1f, 0xFFD8584E);
            Gfx.disc(ms, cx + 2f, cy + 2f, 1f, 0xFFD8584E);
        } else { // ceas
            Gfx.disc(ms, cx, cy, 6.2f, 0xFFE9D8A8);
            Gfx.disc(ms, cx, cy, 4.8f, 0xFF16263C);
            Gfx.line(ms, cx, cy, cx, cy - 3.4f, 1.3f, 0xFFE9D8A8);
            Gfx.line(ms, cx, cy, cx + 2.6f, cy + 1f, 1.3f, 0xFFE9D8A8);
        }
    }

    private void moneyRow2(GuiGraphics ms, int x, int y, int w, String label, String text, int color, int kind) {
        // casuta cu iconita (dreapta)
        int bx = x + w - 26;
        Ui.frame(ms, bx, y, 24, 18, Theme.alpha(0xFF0B1A2E, 0xA0), Theme.alpha(Theme.LINE, 0xCC));
        if (kind == 0) { // bancnota
            Gfx.rrect(ms, bx + 3f, y + 4f, 18f, 10f, 2f, 0xFF3FA36B, 0xFF2A7A4D);
            Gfx.disc(ms, bx + 12f, y + 9f, 2.6f, 0xFFB8F0CE);
            Gfx.disc(ms, bx + 6f, y + 9f, 1f, 0xFFB8F0CE);
            Gfx.disc(ms, bx + 18f, y + 9f, 1f, 0xFFB8F0CE);
        } else { // card
            Gfx.rrect(ms, bx + 3f, y + 3f, 18f, 12f, 2f, 0xFF3B7FE0, 0xFF1B3F8F);
            Gfx.rrect(ms, bx + 3f, y + 6f, 18f, 3f, 0f, 0xFF0B1230);
            Gfx.rrect(ms, bx + 5f, y + 11f, 5f, 2f, 0.6f, 0xFFD9E6FF);
        }
        Ui.rect(ms, x + w - 31, y + 1, 1, 16, Theme.alpha(Theme.LINE, 0xCC));
        // eticheta + suma mare (dreapta)
        Ui.textRight(ms, label, x + w - 36, y + 8, Theme.MUTED);
        float sc = 1.6f;
        int right = x + w - 36 - Ui.width(label) - 6;
        int tw = scaledWidth(text, sc);
        scaled(ms, text, right - tw, y + 1, color, sc);
        scaled(ms, "$", right - tw - scaledWidth("$", sc) - 2, y + 1, Theme.OK, sc);
    }

    private void drawStyle2(GuiGraphics ms, Minecraft mc, JsonObject s, int x, int y, float dt, double t, long now) {
        final int w = W2;
        int accent = HudLayout.accent("hud", ORANGE);
        if (HudLayout.part("hud", "title")) {
            String big = "FLOWCITY";
            float sc = Math.min(2.2f, (w - 46) / (float) Math.max(1, Ui.width(big)));
            int tw = scaledWidth(big, sc);
            int tx = x + Math.max(0, (w - 40 - tw) / 2);
            boolean lvl = now - HypeState.levelUpAt < 2500;
            scaled(ms, big, tx, y + 2, lvl ? Theme.WARN : accent, sc);
            Ui.textCenter(ms, "ROLEPLAY", tx + tw / 2, y + 2 + Math.round(9 * sc) + 3, Theme.MUTED);
            drawEmblem(ms, x + w - 36, y - 1, t);
            drawBell(ms, x + w - 4, y - 3, t);
        }
        boolean ident = HudLayout.part("hud", "identity");
        boolean clk = HudLayout.part("hud", "clock");
        int cy = y + 40;
        int[] cw = {54, 44, 68, 66};
        String[] num = {cOnline, cId, realStr, cHours};
        String[] lab = {"ONLINE", "ID", "ORA", "ORE"};
        int cx = x;
        for (int i = 0; i < 4; i++) {
            if (i > 0) Ui.rect(ms, cx, cy + 1, 1, 24, Theme.alpha(Theme.LINE, 0xAA));
            if (i == 2 ? clk : ident) {
                icon2(ms, i, cx + 10f, cy + 8f);
                scaled(ms, num[i], cx + 20, cy, Theme.TEXT, 1.3f);
                scaled(ms, lab[i], cx + 20, cy + 15, Theme.MUTED, 0.75f);
            }
            cx += cw[i];
        }
        // linia de accent cu punct la capat
        int ly = y + 70;
        Ui.rect(ms, x + 2, ly, w - 12, 1, Theme.alpha(accent, 0xE0));
        Gfx.disc(ms, x + w - 6f, ly + 0.5f, 2.6f, accent);
        // nume (cu prefix / sufix) + rank
        int rw = HudLayout.part("hud", "rank") ? rankChipWidth(s) : 0;
        if (ident) {
            float ns = fitScale(pieces, 1.5f, w - 12 - (rw > 0 ? rw + 6 : 0));
            drawPieces(ms, pieces, x + 4, y + 77 + Math.round((1.5f - ns) * 3f), ns);
        }
        if (rw > 0) rankChip(ms, s, x + w - 4 - rw, y + 75);
        if (HudLayout.part("hud", "money")) {
            refreshMoney2(s);
            moneyRow2(ms, x, y + 94, w, "CASH", moneyStr, moneyColor(now), 0);
            Ui.rect(ms, x + 6, y + 114, w - 6, 1, Theme.alpha(Theme.LINE, 0x88));
            moneyRow2(ms, x, y + 117, w, "CARD", bank2Str, Theme.TEXT, 1);
        }
        int jy = y + 140;
        if (HudLayout.part("hud", "job")) jy = drawJob(ms, s, x, jy, dt, t, now, w);
        if (HudLayout.part("hud", "thirst")) drawThirst(ms, s, x, jy, dt, t, w);
    }

    // ------------------------------------------------------------------ stilul 3 (pastile, sticla)

    private static int pill(GuiGraphics ms, String text, int x, int y, int color) {
        int w = Ui.width(text) + 12;
        Gfx.rrect(ms, x, y, w, 13, 6.5f, Gfx.fade(Theme.mix(0xFF0A1424, color, 0.30f), 0.92f), Gfx.fade(Theme.mix(0xFF050B16, color, 0.18f), 0.92f));
        Ui.text(ms, text, x + 6, y + 3, color);
        return w;
    }

    /** Pastila mare pentru bani: kind 0 = cash (fara iconita, $ in text), 1 = card (cu iconita de card). */
    private static int bigPill(GuiGraphics ms, String text, int x, int y, int color, int kind, boolean draw) {
        float sc = 1.1f;
        int iw = kind == 1 ? 13 : 0;
        int w = scaledWidth(text, sc) + 14 + iw;
        if (!draw) return w;
        Gfx.rrect(ms, x, y, w, 16, 8f, Gfx.fade(Theme.mix(0xFF0A1424, color, 0.28f), 0.94f), Gfx.fade(Theme.mix(0xFF050B16, color, 0.16f), 0.94f));
        if (kind == 1) {
            Gfx.rrect(ms, x + 7f, y + 4.5f, 9f, 7f, 1.4f, 0xFF3B7FE0, 0xFF1B3F8F);
            Gfx.rrect(ms, x + 7f, y + 6.5f, 9f, 1.6f, 0f, 0xFF0B1230);
        }
        scaled(ms, text, x + 7 + iw, y + 3, color, sc);
        return w;
    }

    private void drawStyle3(GuiGraphics ms, Minecraft mc, JsonObject s, int x, int y, float dt, double t, long now) {
        final int w = W3;
        int accent = HudLayout.accent("hud", BLUE);
        int h = bottom(s, 3);
        Gfx.rrect(ms, x - 1, y - 1, w + 2, h + 2, 10f, Gfx.fade(accent, 0.5f), Gfx.fade(accent, 0.12f));
        Gfx.rrect(ms, x, y, w, h, 9f, Gfx.fade(0xFF173A66, 0.82f), Gfx.fade(0xFF06101D, 0.9f));
        Gfx.rrect(ms, x + 8, y + 1, w - 16, 1, 0f, Gfx.fade(0xFFFFFFFF, 0.28f));
        boolean ident = HudLayout.part("hud", "identity");
        if (HudLayout.part("hud", "title")) {
            Gfx.disc(ms, x + 9f, y + 11f, 2.4f, accent);
            Ui.text(ms, "FLOWCITY", x + 15, y + 7, accent);
            Ui.text(ms, "ROLEPLAY", x + 15 + Ui.width("FLOWCITY") + 4, y + 7, Theme.FAINT);
            drawBell(ms, x + w - 2, y - 4, t);
        }
        if (HudLayout.part("hud", "clock")) {
            int pw = Ui.width(realStr) + 12;
            pill(ms, realStr, x + w - 6 - pw, y + 5, Theme.MUTED);
        }
        int rw = HudLayout.part("hud", "rank") ? rankChipWidth(s) : 0;
        if (ident) {
            float ns = fitScale(pieces, 1.4f, w - 16 - (rw > 0 ? rw + 6 : 0));
            drawPieces(ms, pieces, x + 8, y + 23 + Math.round((1.4f - ns) * 3f), ns);
        }
        if (rw > 0) rankChip(ms, s, x + w - 8 - rw, y + 22);
        if (HudLayout.part("hud", "money")) {
            refreshMoney2(s);
            int cw = bigPill(ms, cashPill, 0, 0, 0, 0, false);
            int bw = bigPill(ms, bankPill, 0, 0, 0, 1, false);
            int bx = Math.max(x + 8 + cw + 4, x + w - 8 - bw);
            bigPill(ms, cashPill, x + 8, y + 42, moneyColor(now) == Theme.TEXT ? Theme.OK : moneyColor(now), 0, true);
            bigPill(ms, bankPill, bx, y + 42, 0xFF5FA8FF, 1, true);
        }
        if (ident) {
            int px = x + 8;
            if (!cIdHash.isEmpty()) px += pill(ms, cIdHash, px, y + 62, accent) + 4;
            px += pill(ms, cOnlineLong, px, y + 62, Theme.OK) + 4;
            pill(ms, cHoursShort, px, y + 62, Theme.VIOLET);
        }
        int jy = y + 78;
        if (HudLayout.part("hud", "job")) jy = drawJob(ms, s, x + 6, jy, dt, t, now, w - 12);
        if (HudLayout.part("hud", "thirst")) drawThirst(ms, s, x + 6, jy, dt, t, w - 12);
    }
}
