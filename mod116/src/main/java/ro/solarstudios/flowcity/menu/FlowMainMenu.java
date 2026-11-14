package ro.solarstudios.flowcity.menu;

import com.mojang.blaze3d.systems.RenderSystem;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.OptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Xmas;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Meniu principal FlowCity, in tema de iarna: fundal cu orasul (zoom lent), dungi de lumina, zapada si ghirlanda; stanga
 * titlul FLOW CITY cu luminа care trece prin litere si meniul vertical (mouse sau sus/jos + Enter); dreapta-sus un
 * dashboard cu statusul serverului (jucatori + grafic de ping), regulamentul si ceasul; jos banda cu versiunea si Discord.
 */
public final class FlowMainMenu extends Screen {
    private static final ResourceLocation BG = new ResourceLocation("flowcitycore", "textures/gui/menu_bg.png");
    private static final ResourceLocation LOGO = new ResourceLocation("flowcitycore", "textures/gui/logo.png");
    private static final int BG_W = 1280;
    private static final int BG_H = 678;
    private static final int NAVY = 0xFF061426;
    private static final int NAVY_SUB = 0xFF1F4F85;
    private static final int CARD = 0xDA081018;
    private static final String[] DAYS = {"duminică", "luni", "marți", "miercuri", "joi", "vineri", "sâmbătă"};
    private static final String[] MONTHS = {"ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec"};

    private static final class Btn {
        final String label;
        final String hint;
        final Runnable action;
        final boolean primary;
        float hover;
        int x;
        int y;
        int w;
        int h;

        Btn(String label, String hint, boolean primary, Runnable action) {
            this.label = label;
            this.hint = hint;
            this.primary = primary;
            this.action = action;
        }
    }

    private final List<Btn> buttons = new ArrayList<>();
    private final long openedAt = System.currentTimeMillis();
    private long last = System.currentTimeMillis();
    private final MenuConfig cfg = MenuConfig.get();
    private Btn discordBtn;
    private int sel;

    // istoric de ping pentru mini-grafic
    private final long[] pingHist = new long[28];
    private int pingCount;
    private long lastSample;

    // ceas (recalculat o data pe secunda)
    private long clockSec = -1;
    private String clockText = "";
    private String dateText = "";

    private final float[] quad = new float[8];
    private final int[] rulesBox = new int[4];
    private final int[] updBox = new int[4];

    public FlowMainMenu() {
        super(new StringTextComponent("FlowCity"));
    }

    @Override
    protected void init() {
        buttons.clear();
        buttons.add(new Btn("INTRĂ PE SERVER", "conectare directă la " + cfg.serverName, true, this::joinServer));
        buttons.add(new Btn("MODURI", ModList.get().size() + " instalate", false, () -> minecraft.setScreen(new FlowModsScreen(this))));
        buttons.add(new Btn("SETĂRI JOC", "video, sunet, controale", false, () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))));
        buttons.add(new Btn("REGULAMENT", Rules.accepted() ? "acceptat" : "de acceptat", false, () -> minecraft.setScreen(new RulesScreen(this, null))));
        buttons.add(new Btn("IEȘIRE DIN JOC", "închide jocul", false, () -> minecraft.stop()));
        String dc = cfg.discord == null || cfg.discord.trim().isEmpty() ? MenuConfig.DEFAULT_DISCORD : cfg.discord.trim();
        discordBtn = new Btn("DISCORD", "", false, () -> {
            if (dc.startsWith("https://") || dc.startsWith("http://")) Util.getPlatform().openUri(dc);
        });
        sel = Math.max(0, Math.min(sel, buttons.size() - 1));
        layout();
    }

    private String ip() {
        return cfg.serverIp == null || cfg.serverIp.trim().isEmpty() ? MenuConfig.DEFAULT_IP : cfg.serverIp.trim();
    }

    private void connect() {
        minecraft.setScreen(new ConnectingScreen(this, minecraft, new ServerData(cfg.serverName, ip(), false)));
    }

    /** Prima intrare: fereastra de regulament; dupa acceptare conectarea porneste direct. */
    private void joinServer() {
        if (Rules.accepted()) connect();
        else minecraft.setScreen(new RulesScreen(this, this::connect));
    }

    @Override
    public void tick() {
        StatusPing.tick(ip());
        long now = System.currentTimeMillis();
        if (StatusPing.ping >= 0 && now - lastSample > 1500) {
            lastSample = now;
            if (pingCount < pingHist.length) {
                pingHist[pingCount++] = StatusPing.ping;
            } else {
                System.arraycopy(pingHist, 1, pingHist, 0, pingHist.length - 1);
                pingHist[pingHist.length - 1] = StatusPing.ping;
            }
        }
    }

    private int rightW() {
        return Math.max(200, Math.min(262, width * 27 / 100));
    }

    private int rightX() {
        return width - rightW() - Math.max(18, width / 40);
    }

    private void layout() {
        int x = Math.max(28, width / 16);
        int w = Math.min(300, Math.max(190, width * 36 / 100));
        int total = 0;
        for (Btn b : buttons) total += (b.primary ? 44 : 32) + 7;
        int y = Math.max(124, height / 2 - total / 2 + 44);
        for (Btn b : buttons) {
            b.x = x;
            b.y = y;
            b.w = w;
            b.h = b.primary ? 44 : 32;
            y += b.h + 7;
        }
        if (discordBtn != null) {
            discordBtn.w = rightW();
            discordBtn.h = 28;
            discordBtn.x = rightX();
            discordBtn.y = height - 56;
        }
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        layout();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private static float ease3(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    // ------------------------------------------------------------------ randare

    @Override
    public void render(com.mojang.blaze3d.matrix.MatrixStack poseIn, int mx, int my, float pt) {
        GuiGraphics ms = new GuiGraphics(poseIn);
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        float t = now / 1000f;
        float in = ease3((now - openedAt) / 800f);

        drawBackground(ms, t, in);
        Xmas.snow(ms, 0, 0, width, height, t, 80, 190);
        Xmas.garland(ms, 0, 0, width, t, 5, 26);

        try {
            drawHeader(ms, t, in);
            for (int i = 0; i < buttons.size(); i++) {
                Btn b = buttons.get(i);
                boolean over = Sk.in(mx, my, b.x, b.y, b.w, b.h);
                if (over) sel = i;
                b.hover += (((over || i == sel) ? 1f : 0f) - b.hover) * Math.min(1f, dt * 14f);
                drawButton(ms, b, i, t);
            }
            Gfx.alpha = 1f;
            drawDashboard(ms, mx, my, t, in);
            drawDiscord(ms, dt, mx, my, in);
            drawFooter(ms, t);
        } finally {
            Gfx.alpha = 1f;
            RenderSystem.color4f(1f, 1f, 1f, 1f);
        }
    }

    private void drawBackground(GuiGraphics ms, float t, float in) {
        RenderSystem.enableBlend();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        Minecraft.getInstance().getTextureManager().bind(BG);
        float cover = Math.max(width / (float) BG_W, height / (float) BG_H);
        float scale = cover * (1.12f + 0.02f * (float) Math.sin(t * 0.07));
        float dx = (float) Math.sin(t * 0.045) * width * 0.03f;
        float dy = (float) Math.cos(t * 0.037) * height * 0.015f;
        ms.pose().pushPose();
        ms.pose().translate(width / 2f + dx, height / 2f + dy, 0);
        ms.pose().scale(scale, scale, 1f);
        ms.blit(BG, -BG_W / 2, -BG_H / 2, 0f, 0f, BG_W, BG_H, BG_W, BG_H);
        ms.pose().popPose();

        // tent albastru-navy, mai puternic in stanga (zona meniului)
        Gfx.rrect(ms, 0, 0, width, height, 0, 0x46050B1A);
        int bands = 40;
        for (int i = 0; i < bands; i++) {
            float x0 = i * width / (float) bands;
            float x1 = (i + 1) * width / (float) bands;
            float k = 1f - i / (float) bands;
            Gfx.rrect(ms, x0, 0, x1 - x0 + 1, height, 0, (((int) (200 * k * k)) << 24) | 0x050B1A);
        }
        // vignette sus / jos
        Gfx.rrect(ms, 0, 0, width, 80, 0, 0xB0020610, 0x00020610);
        Gfx.rrect(ms, 0, height - 110, width, 110, 0, 0x00020610, 0xD0020610);

        // dungi de lumina diagonale, foarte lente
        for (int k = 0; k < 3; k++) {
            float span = width + 500f;
            float x = ((t * (11f + k * 4f) + k * span / 3f) % span) - 250f;
            float wd = 46f + k * 26f;
            quad[0] = x + 220f;
            quad[1] = 0;
            quad[2] = x + 220f + wd;
            quad[3] = 0;
            quad[4] = x + wd;
            quad[5] = height;
            quad[6] = x;
            quad[7] = height;
            Gfx.poly(ms, quad, 0x0C9BCBFF);
        }
    }

    private void drawHeader(GuiGraphics ms, float t, float in) {
        int x = Math.max(28, width / 16);
        int slide = Math.round((1f - in) * -34f);
        Gfx.alpha = in;
        int logoS = 64;
        RenderSystem.enableBlend();
        Minecraft.getInstance().getTextureManager().bind(LOGO);
        RenderSystem.color4f(1f, 1f, 1f, in);
        ms.blit(LOGO, x + slide, 28, logoS, logoS, 0f, 0f, 128, 128, 128, 128);
        RenderSystem.color4f(1f, 1f, 1f, 1f);

        // FLOW CITY: litere albastre cu o lumina care le traverseaza
        String title = "FLOW CITY";
        float size = 36f;
        float spacing = 1.6f;
        float tx = x + logoS + 16 + slide;
        float base = 66f;
        float sweep = ((t * 0.42f) % 1.7f) - 0.35f;
        int n = title.length();
        for (int i = 0; i < n; i++) {
            String ch = title.substring(i, i + 1);
            float pos = i / (float) (n - 1);
            float shine = Math.max(0f, 1f - Math.abs(pos - sweep) * 4.2f);
            int col = Gfx.mixArgb(Sk.ACC, 0xFFFFFFFF, shine * 0.95f);
            Gfx.text(ms, ch, tx, base, size, col, 0, true, 0f);
            tx += Gfx.width(ch, size, true) + spacing;
        }
        // subtitlu + linie de accent
        float lx = x + logoS + 17 + slide;
        Gfx.text(ms, cfg.tagline == null ? "" : cfg.tagline.toUpperCase(java.util.Locale.ROOT), lx, 84, 7f, Sk.MUTED, 0, false, 2f);
        Sk.slant(ms, lx, 90, 120, 2f, 2f, Sk.ACC);
        Sk.slant(ms, lx + 126, 90, 26, 2f, 2f, Gfx.fade(Sk.ACC, 0.35f));
        Gfx.text(ms, "Un oraș viu: joburi, economie, telefon și poveste.", x + slide, 112, 8.2f, Gfx.fade(0xFFD6E4FF, 0.92f), 0);
        Gfx.alpha = 1f;
    }

    private void drawButton(GuiGraphics ms, Btn b, int idx, float t) {
        float local = ease3((System.currentTimeMillis() - openedAt - 260 - idx * 80L) / 380f);
        if (local <= 0f) return;
        Gfx.alpha = local;
        float ox = (1f - local) * -46f + b.hover * 9f;
        float x = b.x + ox;
        boolean danger = b.label.startsWith("IEȘIRE");
        int accent = danger ? Sk.BAD : Sk.ACC;
        boolean sel = idx == this.sel;
        int fg;
        if (b.primary) {
            Sk.slant(ms, x, b.y, b.w, b.h, 12f, Gfx.mixArgb(Sk.ACC, 0xFF7DBBFF, b.hover));
            // luminita care trece pe butonul principal
            float sw = ((t * 0.38f) % 1.4f) - 0.2f;
            float sx = x + 12f + sw * (b.w - 20f);
            quad[0] = sx + 9f;
            quad[1] = b.y;
            quad[2] = sx + 20f;
            quad[3] = b.y;
            quad[4] = sx + 11f;
            quad[5] = b.y + b.h;
            quad[6] = sx;
            quad[7] = b.y + b.h;
            if (sx > x + 8f && sx + 20f < x + b.w) Gfx.poly(ms, quad, 0x30FFFFFF);
            fg = NAVY;
        } else {
            int bg = Gfx.mixArgb(0xB0111A28, 0xD81B2A40, b.hover);
            if (danger) bg = Gfx.mixArgb(0xB0111A28, 0xD8401B20, b.hover);
            Sk.slant(ms, x, b.y, b.w, b.h, 12f, bg);
            Sk.slant(ms, x, b.y, 4f + b.hover * 5f, b.h, 12f, Gfx.fade(accent, 0.55f + 0.45f * b.hover));
            fg = Gfx.mixArgb(0xFFC9D3E0, Sk.WHITE, b.hover);
        }
        float size = b.primary ? 14f : 11f;
        float base = b.y + b.h / 2f + size * 0.34f;
        Gfx.text(ms, String.format("%02d", idx + 1), x + 24, base - (b.primary ? 0.5f : 0f), 6.6f, b.primary ? NAVY_SUB : Gfx.fade(Sk.FAINT, 1f), 0, true, 1f);
        Gfx.text(ms, b.label, x + 48, base, size, fg, 0, true, b.primary ? 1.2f : 0.8f);
        if (b.primary || b.hover > 0.1f) {
            int hc = b.primary ? NAVY_SUB : Gfx.fade(Sk.MUTED, b.hover);
            Gfx.text(ms, clip(b.hint, 6.6f, false, b.w - 150f), x + b.w - 22, base - 0.5f, 6.6f, hc, 2, false, 0.4f);
        }
        Gfx.alpha = 1f;
    }

    // ------------------------------------------------------------------ dashboard (dreapta-sus)

    private void card(GuiGraphics ms, float x, float y, float w, float h, int accent) {
        Sk.cut(ms, x, y, w, h, 10f, CARD);
        Gfx.rrect(ms, x, y, w - 10f, 2f, 0, accent);
        Sk.cut(ms, x, y, w, h, 10f, 0x0A9BCBFF);
    }

    private void drawDashboard(GuiGraphics ms, int mx, int my, float t, float in) {
        int w = rightW();
        float slide = (1f - in) * 46f;
        float x = rightX() + slide;
        Gfx.alpha = in;
        drawUpdateBadge(ms, t, slide);

        float y = 48f;
        float limit = height - 66f;
        // STATUS SERVER
        float h1 = 92f;
        if (y + h1 <= limit) {
            drawStatus(ms, x, y, w, h1, t);
            y += h1 + 8f;
        }
        // REGULAMENT
        List<String> titles = Rules.titles();
        int rows = Math.min(3, titles.size());
        float h2 = 36f + rows * 13f + 34f;
        rulesBox[2] = 0;
        if (y + h2 <= limit) {
            drawRules(ms, x, y, w, h2, titles, rows, mx, my);
            y += h2 + 8f;
        }
        // CEAS
        float h3 = 50f;
        if (y + h3 <= limit) {
            drawClock(ms, x, y, w, h3);
        }
        Gfx.alpha = 1f;
    }

    private void drawStatus(GuiGraphics ms, float x, float y, float w, float h, float t) {
        int accent = !StatusPing.known ? Sk.MUTED : (StatusPing.online ? Sk.ACC : Sk.BAD);
        card(ms, x, y, w, h, accent);
        Gfx.text(ms, "STATUS SERVER", x + 14, y + 20, 6.6f, Sk.MUTED, 0, true, 1.6f);
        String st = !StatusPing.known ? "SE VERIFICĂ" : (StatusPing.online ? "ONLINE" : "OFFLINE");
        int sc = !StatusPing.known ? Sk.MUTED : (StatusPing.online ? Sk.GOOD : Sk.BAD);
        float stw = Gfx.width(st, 7.4f, true) + 1f * (st.length() - 1);
        float pulse = 0.55f + 0.45f * (float) Math.sin(t * 3.2f);
        float dotX = x + w - 14 - stw - 9;
        Gfx.disc(ms, dotX, y + 17.5f, 5.5f, Gfx.fade(sc, 0.18f * pulse));
        Gfx.disc(ms, dotX, y + 17.5f, 2.6f, Gfx.fade(sc, 0.65f + 0.35f * pulse));
        Gfx.text(ms, st, x + w - 14, y + 20, 7.4f, sc, 2, true, 1f);

        Gfx.text(ms, "JUCĂTORI", x + 14, y + 36, 5.8f, Sk.FAINT, 0, true, 1.4f);
        String num = StatusPing.players >= 0 ? String.valueOf(StatusPing.players) : "-";
        Gfx.text(ms, num, x + 14, y + 58, 22f, Sk.WHITE, 0, true, 0f);
        if (StatusPing.players >= 0 && StatusPing.max > 0) {
            Gfx.text(ms, "/ " + StatusPing.max, x + 14 + Gfx.width(num, 22f, true) + 6, y + 58, 9f, Sk.MUTED, 0, true, 0f);
        }

        // mini-grafic de ping
        float gx = x + w - 14 - 78;
        float gy = y + 34;
        float gh = 22;
        Gfx.rrect(ms, gx, gy + gh, 78, 1, 0, 0xFF22324A);
        long p = StatusPing.ping;
        int pc = p < 0 ? Sk.MUTED : (p < 80 ? Sk.GOOD : (p < 160 ? Sk.WARN : Sk.BAD));
        if (pingCount >= 2) {
            long mxv = 60;
            for (int i = 0; i < pingCount; i++) mxv = Math.max(mxv, pingHist[i]);
            float step = 78f / (pingHist.length - 1);
            float px = gx;
            float py = gy + gh - (pingHist[0] / (float) mxv) * gh;
            for (int i = 1; i < pingCount; i++) {
                float nx = gx + i * step;
                float ny = gy + gh - (pingHist[i] / (float) mxv) * gh;
                Gfx.line(ms, px, py, nx, ny, 1.3f, Gfx.fade(pc, 0.95f));
                px = nx;
                py = ny;
            }
            Gfx.disc(ms, px, py, 2f, pc);
        }
        Gfx.text(ms, "PING", gx, y + 68, 5.8f, Sk.FAINT, 0, true, 1.4f);
        Gfx.text(ms, p >= 0 ? p + " ms" : "-", x + w - 14, y + 68, 8f, pc, 2, true, 0f);

        int v = 0;
        if (StatusPing.players >= 0 && StatusPing.max > 0) v = Math.round(Math.min(1f, StatusPing.players / (float) StatusPing.max) * 24f);
        Sk.segs(ms, x + 14, y + h - 14, w - 28, 4f, v, 24, accent);
    }

    private void drawRules(GuiGraphics ms, float x, float y, float w, float h, List<String> titles, int rows, int mx, int my) {
        boolean ok = Rules.accepted();
        int accent = ok ? Sk.GOOD : Sk.WARN;
        card(ms, x, y, w, h, accent);
        Gfx.text(ms, "REGULAMENT", x + 14, y + 20, 6.6f, Sk.MUTED, 0, true, 1.6f);
        String chip = ok ? "ACCEPTAT" : "NEACCEPTAT";
        float cw = Gfx.width(chip, 6.2f, true) + 1f * (chip.length() - 1) + 18f;
        Sk.slant(ms, x + w - 14 - cw, y + 11, cw, 13f, 5f, Gfx.fade(accent, 0.22f));
        Gfx.text(ms, chip, x + w - 14 - cw / 2f, y + 20.5f, 6.2f, accent, 1, true, 1f);
        float yy = y + 38;
        for (int i = 0; i < rows; i++) {
            Gfx.rrect(ms, x + 14, yy - 5, 3, 3, 0, Sk.ACC);
            Gfx.text(ms, clip(titles.get(i), 7.4f, false, w - 44), x + 23, yy, 7.4f, Sk.WHITE, 0);
            yy += 13;
        }
        float by = y + h - 28;
        boolean hov = Sk.in(mx, my, x + 14, by, w - 28, 20);
        Sk.button(ms, ok ? "CITEȘTE REGULAMENTUL" : "CITEȘTE ȘI ACCEPTĂ", x + 14, by, w - 28, 20, true, !ok, hov);
        rulesBox[0] = Math.round(x + 14);
        rulesBox[1] = Math.round(by);
        rulesBox[2] = Math.round(w - 28);
        rulesBox[3] = 20;
    }

    private void drawClock(GuiGraphics ms, float x, float y, float w, float h) {
        long sec = System.currentTimeMillis() / 1000L;
        if (sec != clockSec) {
            clockSec = sec;
            LocalDateTime n = LocalDateTime.now();
            clockText = String.format("%02d:%02d", n.getHour(), n.getMinute());
            dateText = DAYS[n.getDayOfWeek().getValue() % 7] + ", " + n.getDayOfMonth() + " " + MONTHS[n.getMonthValue() - 1];
        }
        card(ms, x, y, w, h, Sk.ACC_DARK);
        Gfx.text(ms, clockText, x + 14, y + 33, 21f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, dateText.toUpperCase(java.util.Locale.ROOT), x + 14, y + 44, 5.8f, Sk.FAINT, 0, true, 1.2f);
        String name = minecraft.getUser().getName();
        Gfx.text(ms, "SALUT,", x + w - 14, y + 22, 5.8f, Sk.FAINT, 2, true, 1.4f);
        Gfx.text(ms, clip(name, 10f, true, w - 110), x + w - 14, y + 36, 10f, Sk.ACC, 2, true, 0f);
    }

    private void drawUpdateBadge(GuiGraphics ms, float t, float slide) {
        ro.solarstudios.flowcity.update.UpdateChecker.checkAsync(false);
        if (!ro.solarstudios.flowcity.update.UpdateChecker.updateAvailable()) {
            updBox[2] = 0;
            return;
        }
        String text = "UPDATE DISPONIBIL  #" + ro.solarstudios.flowcity.update.UpdateChecker.latestBuild;
        float tw = Gfx.width(text, 6.8f, true) + 1f * (text.length() - 1);
        float w = tw + 36f;
        float x = width - w - 12f + slide;
        float y = 26f;
        float pulse = 0.55f + 0.45f * (float) Math.sin(t * 3.4f);
        Sk.cut(ms, x, y, w, 18f, 8f, Gfx.mixArgb(0xE00B2E22, 0xE0145A3A, pulse));
        Gfx.rrect(ms, x, y, w - 8f, 2f, 0, Sk.GOOD);
        Gfx.disc(ms, x + 13f, y + 9.5f, 4.5f, Gfx.fade(Sk.GOOD, 0.2f * pulse));
        Gfx.disc(ms, x + 13f, y + 9.5f, 2.2f, Gfx.fade(Sk.GOOD, 0.7f + 0.3f * pulse));
        Gfx.text(ms, text, x + 24f, y + 12.4f, 6.8f, Sk.WHITE, 0, true, 1f);
        updBox[0] = Math.round(x);
        updBox[1] = Math.round(y);
        updBox[2] = Math.round(w);
        updBox[3] = 18;
    }

    private void drawDiscord(GuiGraphics ms, float dt, int mx, int my, float in) {
        Btn d = discordBtn;
        if (d == null) return;
        boolean hov = Sk.in(mx, my, d.x, d.y, d.w, d.h);
        d.hover += ((hov ? 1f : 0f) - d.hover) * Math.min(1f, dt * 14f);
        float x = d.x + (1f - in) * 46f;
        Gfx.alpha = in;
        Sk.slant(ms, x, d.y, d.w, d.h, 9f, Gfx.mixArgb(0xFF4752C4, 0xFF6A75E8, d.hover));
        Gfx.rrect(ms, x + 9f, d.y, d.w - 9f, 1.5f, 0, 0x889AA3FF);
        Gfx.text(ms, "DISCORD", x + 26, d.y + d.h / 2f + 3.4f, 9.6f, 0xFFFFFFFF, 0, true, 1.2f);
        Gfx.text(ms, "dsc.gg/solarteam12", x + d.w - 20, d.y + d.h / 2f + 3f, 7f, 0xFFDDE1FF, 2, false, 0.4f);
        Gfx.alpha = 1f;
    }

    private void drawFooter(GuiGraphics ms, float t) {
        float y = height - 20f;
        Gfx.rrect(ms, 0, y, width, 20f, 0, 0xB0050A14);
        Gfx.rrect(ms, 0, y, width, 1f, 0, 0x5522324A);
        float by = y + 13f;
        Gfx.text(ms, "MINECRAFT 1.16.5  ·  " + ModList.get().size() + " MODURI  ·  FLOWCITY", 12, by, 6.2f, Sk.FAINT, 0, true, 1.2f);
        float hint = 0.55f + 0.2f * (float) Math.sin(t * 2.2f);
        Gfx.text(ms, "↑↓ NAVIGHEAZĂ   ·   ENTER = INTRĂ", width / 2f, by, 6.2f, Gfx.fade(Sk.MUTED, hint + 0.25f), 1, true, 1.2f);
        Gfx.text(ms, "© SOLARSTUDIOS", width - 12, by, 6.2f, Sk.FAINT, 2, true, 1.2f);
    }

    // ------------------------------------------------------------------ input

    private void click() {
        minecraft.getSoundManager().play(net.minecraft.client.audio.SimpleSound.forUI(net.minecraft.util.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_TAB) {
            sel = (sel + 1) % buttons.size();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP) {
            sel = (sel - 1 + buttons.size()) % buttons.size();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            click();
            buttons.get(sel).action.run();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (updBox[2] > 0 && Sk.in((float) mx, (float) my, updBox[0], updBox[1], updBox[2], updBox[3])) {
                minecraft.setScreen(new ro.solarstudios.flowcity.update.UpdateScreen(this));
                return true;
            }
            if (rulesBox[2] > 0 && Sk.in((float) mx, (float) my, rulesBox[0], rulesBox[1], rulesBox[2], rulesBox[3])) {
                click();
                minecraft.setScreen(new RulesScreen(this, null));
                return true;
            }
            if (discordBtn != null && Sk.in((float) mx, (float) my, discordBtn.x, discordBtn.y, discordBtn.w, discordBtn.h)) {
                click();
                discordBtn.action.run();
                return true;
            }
            for (Btn b : buttons) {
                if (Sk.in((float) mx, (float) my, b.x, b.y, b.w, b.h)) {
                    click();
                    b.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
