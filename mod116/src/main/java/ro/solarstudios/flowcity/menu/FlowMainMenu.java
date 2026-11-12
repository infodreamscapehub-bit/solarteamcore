package ro.solarstudios.flowcity.menu;

import net.minecraft.util.text.StringTextComponent;

import ro.solarstudios.flowcity.compat.GuiGraphics;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.OptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.client.gui.screen.ModListScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Xmas;

/**
 * Meniu principal FlowCity: fundal cu orasul (zoom lent), zapada, ghirlanda de Craciun, titlu albastru
 * si butoane mari: INTRARE IN ORAS, MODURI, DISCORD, SETARI, IESIRE.
 */
public final class FlowMainMenu extends Screen {
    private static final ResourceLocation BG = new ResourceLocation("flowcitycore", "textures/gui/menu_bg.png");
    private static final ResourceLocation LOGO = new ResourceLocation("flowcitycore", "textures/gui/logo.png");
    private static final int BG_W = 1280;
    private static final int BG_H = 678;
    private static final int BLUE = 0xFF2F8CFF;
    private static final int ICE = 0xFFBFE9FF;

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

    public FlowMainMenu() {
        super(new StringTextComponent("FlowCity"));
    }

    private Btn discordBtn;

    @Override
    protected void init() {
        buttons.clear();
        buttons.add(new Btn("INTRA PE SERVER", "conectare directa la " + cfg.serverName, true, this::joinServer));
        buttons.add(new Btn("MODURI", ModList.get().size() + " instalate", false, () -> minecraft.setScreen(new ModListScreen(this))));
        buttons.add(new Btn("SETARI JOC", "video, sunet, controale", false, () -> minecraft.setScreen(new OptionsScreen(this, minecraft.options))));
        buttons.add(new Btn("REGULAMENT", Rules.accepted() ? "acceptat" : "de acceptat", false, () -> minecraft.setScreen(new RulesScreen(this, null))));
        buttons.add(new Btn("IESIRE DIN JOC", "inchide jocul", false, () -> minecraft.stop()));
        String dc = cfg.discord == null || cfg.discord.trim().isEmpty() ? MenuConfig.DEFAULT_DISCORD : cfg.discord.trim();
        discordBtn = new Btn("DISCORD", "", false, () -> {
            if (dc.startsWith("https://") || dc.startsWith("http://")) Util.getPlatform().openUri(dc);
        });
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
    }

    private int rightX() {
        return width - rightW() - Math.max(18, width / 40);
    }

    private int rightW() {
        return Math.max(190, Math.min(270, width * 3 / 10));
    }

    private void layout() {
        int x = Math.max(24, width / 16);
        int w = Math.min(290, Math.max(180, width * 37 / 100));
        int total = 0;
        for (Btn b : buttons) total += (b.primary ? 40 : 30) + 6;
        int y = Math.max(112, height / 2 - total / 2 + 30);
        for (Btn b : buttons) {
            b.x = x;
            b.y = y;
            b.w = w;
            b.h = b.primary ? 40 : 30;
            y += b.h + 6;
        }
        if (discordBtn != null) {
            discordBtn.w = rightW();
            discordBtn.h = 26;
            discordBtn.x = rightX();
            discordBtn.y = height - 48;
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

    @Override
    public void render(com.mojang.blaze3d.matrix.MatrixStack poseIn, int mx, int my, float pt) {
        GuiGraphics ms = new GuiGraphics(poseIn);
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        double t = now / 1000.0;
        float in = Math.min(1f, (now - openedAt) / 700f);
        float ease = 1f - (1f - in) * (1f - in) * (1f - in);

        drawBackground(ms, t, ease);
        Xmas.snow(ms, 0, 0, width, height, t, 90, 200);

        // ghirlanda pe toata latimea, in doua randuri
        Xmas.garland(ms, 0, 0, width, t, 6, 26);
        Xmas.garland(ms, 13, 8, width - 26, t + 1.3, 5, 30);

        drawHeader(ms, t, ease);

        for (Btn b : buttons) {
            boolean hov = Ui.in(mx, my, b.x, b.y, b.w, b.h);
            b.hover += ((hov ? 1f : 0f) - b.hover) * Math.min(1f, dt * 14f);
            drawButton(ms, b, buttons.indexOf(b), ease, t);
        }
        drawStatus(ms, t, ease);
        drawRulesCard(ms, mx, my, ease);
        drawDiscord(ms, dt, mx, my, ease);
        drawFooter(ms, t);
    }

    private void drawBackground(GuiGraphics ms, double t, float ease) {
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
        // intunecare + tent albastru, mai puternica in stanga (zona butoanelor)
        Ui.rect(ms, 0, 0, width, height, Theme.alpha(0xFF050B1A, 70));
        int bands = 48;
        for (int i = 0; i < bands; i++) {
            int x0 = i * width / bands;
            int x1 = (i + 1) * width / bands;
            float k = 1f - i / (float) bands;
            Ui.rect(ms, x0, 0, x1 - x0 + 1, height, Theme.alpha(0xFF050B1A, (int) (190 * k * k)));
        }
        int vb = 24;
        for (int i = 0; i < vb; i++) {
            float k = 1f - i / (float) vb;
            int a = (int) (110 * k * k);
            Ui.rect(ms, 0, height - (i + 1) * 3, width, 3, Theme.alpha(0xFF02060F, a));
        }
    }

    private void drawHeader(GuiGraphics ms, double t, float ease) {
        int x = Math.max(24, width / 14);
        int slide = Math.round((1f - ease) * -30f);
        int logoS = 64;
        RenderSystem.enableBlend();
        Minecraft.getInstance().getTextureManager().bind(LOGO);
        RenderSystem.color4f(1f, 1f, 1f, ease);
        ms.blit(LOGO, x + slide, 22, logoS, logoS, 0f, 0f, 128, 128, 128, 128);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        Xmas.hat(ms, x + slide + 30, 17, 26);

        // FLOW CITY - litere albastre cu val luminos
        String title = "FLOW CITY";
        float ts = 2.6f;
        float cx = x + logoS + 12 + slide;
        for (int i = 0; i < title.length(); i++) {
            String ch = title.substring(i, i + 1);
            double wave = Math.sin(t * 2.0 - i * 0.55);
            double w2 = Math.max(0, wave);
            float k = (float) Math.pow(w2, 6);
            int color = Theme.alpha(Theme.mix(BLUE, ICE, k * 0.9f), (int) (255 * ease));
            ms.pose().pushPose();
            ms.pose().translate(cx, 30 + (float) wave * 0.8f, 0);
            ms.pose().scale(ts, ts, 1f);
            ms.drawString(font, ch, 0, 0, color, true);
            ms.pose().popPose();
            cx += font.width(ch) * ts;
        }
        Ui.text(ms, cfg.tagline, x + logoS + 14 + slide, 58, Theme.alpha(Theme.MUTED, (int) (255 * ease)));
        Ui.text(ms, "Un oras viu: joburi, economie, telefon si poveste.", x + slide, 96, Theme.alpha(0xFFD6E4FF, (int) (255 * ease)));
        Ui.text(ms, "Intra, accepta regulamentul si incepe-ti viata in FlowCity.", x + slide, 108, Theme.alpha(Theme.MUTED, (int) (255 * ease)));
    }

    private void drawButton(GuiGraphics ms, Btn b, int idx, float ease, double t) {
        float local = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - openedAt - 250 - idx * 70L) / 350f));
        if (local <= 0f) return;
        float e = 1f - (1f - local) * (1f - local) * (1f - local);
        int ox = Math.round((1f - e) * -40f) + Math.round(b.hover * 6f);
        int a = (int) (255 * e);
        int x = b.x + ox;
        boolean danger = b.label.startsWith("IESIRE");
        int accent = danger ? 0xFFE5334F : BLUE;
        int fill = Theme.alpha(Theme.mix(0xFF0B1426, accent, (b.primary ? 0.45f : 0.0f) + b.hover * 0.3f), (int) ((b.primary ? 225 : 165 + 60 * b.hover) * e));
        Ui.rect(ms, x, b.y, b.w, b.h, fill);
        Ui.rect(ms, x, b.y, b.w, 1, Theme.alpha(Theme.mix(0xFF22314F, accent, b.hover), (int) (200 * e)));
        Ui.rect(ms, x, b.y + b.h - 1, b.w, 1, Theme.alpha(Theme.mix(0xFF22314F, accent, b.hover), (int) (200 * e)));
        Ui.rect(ms, x, b.y, 3 + Math.round(b.hover * 2f), b.h, Theme.alpha(accent, a));
        if (b.primary) {
            int sweep = (int) (((t * 0.35) % 1.0) * (b.w + 40)) - 20;
            Ui.rect(ms, x + Math.max(3, sweep), b.y + 1, Math.min(14, Math.max(0, b.w - sweep)), b.h - 2, Theme.alpha(0xFFFFFFFF, 22));
        }
        float sc = b.primary ? 1.2f : 1f;
        ms.pose().pushPose();
        ms.pose().translate(x + 14, b.y + (b.h - 8 * sc) / 2f, 0);
        ms.pose().scale(sc, sc, 1f);
        ms.drawString(font, b.label, 0, 0, Theme.alpha(0xFFFFFFFF, a), true);
        ms.pose().popPose();
        if (b.primary || b.hover > 0.05f) {
            Ui.textRight(ms, b.hint, x + b.w - 10, b.y + (b.h - 8) / 2, Theme.alpha(b.primary ? 0xFFDDF1FF : Theme.MUTED, (int) (a * (b.primary ? 0.8f : b.hover))));
        }
    }

    private void card(GuiGraphics ms, int x, int y, int w, int h, int accent, float ease) {
        Ui.rect(ms, x, y, w, h, Theme.alpha(0xFF08101F, (int) (205 * ease)));
        Ui.rect(ms, x, y, w, 1, Theme.alpha(0xFF2A3B5E, (int) (230 * ease)));
        Ui.rect(ms, x, y + h - 1, w, 1, Theme.alpha(0xFF2A3B5E, (int) (230 * ease)));
        Ui.rect(ms, x + w - 1, y, 1, h, Theme.alpha(0xFF2A3B5E, (int) (230 * ease)));
        Ui.rect(ms, x, y, w, 3, Theme.alpha(accent, (int) (255 * ease)));
    }

    private void drawStatus(GuiGraphics ms, double t, float ease) {
        int x = rightX() + Math.round((1f - ease) * 40f);
        int w = rightW();
        int y = 40;
        card(ms, x, y, w, 70, BLUE, ease);
        Ui.textShadow(ms, "STATUS SERVER", x + 12, y + 10, Theme.TEXT);
        String st = !StatusPing.known ? "Se verifica..." : (StatusPing.online ? "ONLINE" : "OFFLINE");
        int sc = !StatusPing.known ? Theme.MUTED : (StatusPing.online ? Theme.OK : Theme.BAD);
        Ui.text(ms, "Status:", x + 12, y + 24, Theme.MUTED);
        float pulse = 0.6f + 0.4f * (float) Math.sin(t * 3.0);
        Ui.rect(ms, x + 12 + Ui.width("Status: ") + 1, y + 26, 5, 5, Theme.alpha(sc, (int) (140 + 115 * pulse)));
        Ui.text(ms, st, x + 12 + Ui.width("Status: ") + 10, y + 24, sc);
        Ui.text(ms, "Jucatori:", x + 12, y + 36, Theme.MUTED);
        Ui.text(ms, StatusPing.players >= 0 ? StatusPing.players + " / " + StatusPing.max : "-", x + 12 + Ui.width("Jucatori: "), y + 36, Theme.TEXT);
        Ui.text(ms, "Ping:", x + 12, y + 48, Theme.MUTED);
        long p = StatusPing.ping;
        Ui.text(ms, p >= 0 ? p + " ms" : "-", x + 12 + Ui.width("Ping: "), y + 48, p < 0 ? Theme.MUTED : (p < 80 ? Theme.OK : (p < 160 ? Theme.WARN : Theme.BAD)));
        if (StatusPing.players >= 0 && StatusPing.max > 0) Ui.bar(ms, x + 12, y + 60, w - 24, 4, Math.min(1f, StatusPing.players / (float) StatusPing.max), BLUE);
    }

    private final int[] rulesBox = new int[4];

    private void drawRulesCard(GuiGraphics ms, int mx, int my, float ease) {
        int x = rightX() + Math.round((1f - ease) * 40f);
        int w = rightW();
        int y = 120;
        List<String> titles = Rules.titles();
        int rows = Math.min(5, titles.size());
        int h = 58 + rows * 12;
        boolean ok = Rules.accepted();
        card(ms, x, y, w, h, ok ? Theme.OK : 0xFFFFB020, ease);
        Ui.textShadow(ms, "REGULAMENT", x + 12, y + 10, Theme.TEXT);
        Ui.textRight(ms, ok ? "ACCEPTAT" : "NEACCEPTAT", x + w - 10, y + 10, ok ? Theme.OK : Theme.WARN);
        Ui.text(ms, "Termeni si conditii FlowCity", x + 12, y + 22, Theme.MUTED);
        int yy = y + 36;
        for (int i = 0; i < rows; i++) {
            Ui.rect(ms, x + 12, yy + 3, 3, 3, BLUE);
            Ui.text(ms, Ui.clip(titles.get(i), w - 36), x + 20, yy, Theme.TEXT);
            yy += 12;
        }
        int by = y + h - 20;
        boolean hov = Ui.in(mx, my, x + 12, by, w - 24, 14);
        Ui.round(ms, x + 12, by, w - 24, 14, Theme.alpha(Theme.mix(0xFF16325C, BLUE, hov ? 0.6f : 0.2f), 230));
        Ui.textCenter(ms, ok ? "CITESTE REGULAMENTUL" : "CITESTE SI ACCEPTA", x + w / 2, by + 3, 0xFFFFFFFF);
        rulesBox[0] = x + 12;
        rulesBox[1] = by;
        rulesBox[2] = w - 24;
        rulesBox[3] = 14;
    }

    private void drawDiscord(GuiGraphics ms, float dt, int mx, int my, float ease) {
        Btn d = discordBtn;
        if (d == null) return;
        boolean hov = Ui.in(mx, my, d.x, d.y, d.w, d.h);
        d.hover += ((hov ? 1f : 0f) - d.hover) * Math.min(1f, dt * 14f);
        int x = d.x + Math.round((1f - ease) * 40f);
        Ui.rect(ms, x, d.y, d.w, d.h, Theme.alpha(Theme.mix(0xFF4752C4, 0xFF6A75E8, d.hover), (int) (235 * ease)));
        Ui.rect(ms, x, d.y, d.w, 1, Theme.alpha(0xFF9AA3FF, (int) (200 * ease)));
        Ui.textCenter(ms, "DISCORD  -  dsc.gg/solarteam12", x + d.w / 2, d.y + 9, 0xFFFFFFFF);
    }

    private final int[] updBox = new int[4];

    private void drawUpdateBadge(GuiGraphics ms, double t) {
        ro.solarstudios.flowcity.update.UpdateChecker.checkAsync(false);
        if (!ro.solarstudios.flowcity.update.UpdateChecker.updateAvailable()) {
            updBox[2] = 0;
            return;
        }
        String text = "UPDATE DISPONIBIL  #" + ro.solarstudios.flowcity.update.UpdateChecker.latestBuild;
        int w = Ui.width(text) + 22;
        int x = width - w - 12;
        int y = 14;
        float pulse = 0.6f + 0.4f * (float) Math.sin(t * 3.0);
        Ui.round(ms, x, y, w, 18, Theme.alpha(Theme.mix(0xFF0B3D2A, Theme.OK, pulse * 0.5f), 230));
        Ui.rect(ms, x, y, 3, 18, Theme.OK);
        Ui.rect(ms, x + 9, y + 7, 4, 4, Theme.alpha(Theme.OK, (int) (140 + 115 * pulse)));
        Ui.text(ms, text, x + 17, y + 5, 0xFFFFFFFF);
        updBox[0] = x;
        updBox[1] = y;
        updBox[2] = w;
        updBox[3] = 18;
    }

    private void drawFooter(GuiGraphics ms, double t) {
        drawUpdateBadge(ms, t);
        int y = height - 14;
        Ui.text(ms, "Minecraft 1.16.5  |  " + ModList.get().size() + " moduri  |  FlowCity", 10, y, Theme.alpha(Theme.FAINT, 220));
        String name = minecraft.getUser().getName();
        Ui.textRight(ms, "Salut, " + name + "  |  (c) SolarStudios", width - 10, y, Theme.alpha(Theme.MUTED, 230));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (updBox[2] > 0 && Ui.in((int) mx, (int) my, updBox[0], updBox[1], updBox[2], updBox[3])) {
                minecraft.setScreen(new ro.solarstudios.flowcity.update.UpdateScreen(this));
                return true;
            }
            if (Ui.in((int) mx, (int) my, rulesBox[0], rulesBox[1], rulesBox[2], rulesBox[3])) {
                minecraft.setScreen(new RulesScreen(this, null));
                return true;
            }
            if (discordBtn != null && Ui.in((int) mx, (int) my, discordBtn.x, discordBtn.y, discordBtn.w, discordBtn.h)) {
                minecraft.getSoundManager().play(net.minecraft.client.audio.SimpleSound.forUI(net.minecraft.util.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                discordBtn.action.run();
                return true;
            }
            for (Btn b : buttons) {
                if (Ui.in((int) mx, (int) my, b.x, b.y, b.w, b.h)) {
                    minecraft.getSoundManager().play(net.minecraft.client.audio.SimpleSound.forUI(net.minecraft.util.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    b.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
