package ro.solarstudios.flowcity.menu;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.OptionsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.text.StringTextComponent;
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

    @Override
    protected void init() {
        buttons.clear();
        String ip = cfg.serverIp == null || cfg.serverIp.trim().isEmpty() ? MenuConfig.DEFAULT_IP : cfg.serverIp.trim();
        buttons.add(new Btn("INTRARE IN ORAS", "conectare directa la " + cfg.serverName, true, () -> {
            minecraft.displayGuiScreen(new ConnectingScreen(this, minecraft, new ServerData(cfg.serverName, ip, false)));
        }));
        buttons.add(new Btn("MODURI", ModList.get().size() + " instalate", false, () -> minecraft.displayGuiScreen(new ModListScreen(this))));
        String dc = cfg.discord == null ? "" : cfg.discord.trim();
        buttons.add(new Btn("DISCORD", dc.isEmpty() ? "link neconfigurat" : "comunitatea noastra", false, () -> {
            if (!dc.isEmpty() && (dc.startsWith("https://") || dc.startsWith("http://"))) Util.getOSType().openURI(dc);
        }));
        buttons.add(new Btn("SETARI", "video, sunet, controale", false, () -> minecraft.displayGuiScreen(new OptionsScreen(this, minecraft.gameSettings))));
        buttons.add(new Btn("IESIRE", "inchide jocul", false, () -> minecraft.shutdown()));
        layout();
    }

    private void layout() {
        int x = Math.max(24, width / 14);
        int w = Math.min(230, width / 3);
        int total = 0;
        for (Btn b : buttons) total += (b.primary ? 34 : 24) + 5;
        int y = Math.max(96, height / 2 - total / 2 + 24);
        for (Btn b : buttons) {
            b.x = x;
            b.y = y;
            b.w = w;
            b.h = b.primary ? 34 : 24;
            y += b.h + 5;
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
    public void render(MatrixStack ms, int mx, int my, float pt) {
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
        drawFooter(ms, t);
    }

    private void drawBackground(MatrixStack ms, double t, float ease) {
        RenderSystem.enableBlend();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        minecraft.getTextureManager().bindTexture(BG);
        float cover = Math.max(width / (float) BG_W, height / (float) BG_H);
        float scale = cover * (1.12f + 0.02f * (float) Math.sin(t * 0.07));
        float dx = (float) Math.sin(t * 0.045) * width * 0.03f;
        float dy = (float) Math.cos(t * 0.037) * height * 0.015f;
        ms.push();
        ms.translate(width / 2f + dx, height / 2f + dy, 0);
        ms.scale(scale, scale, 1f);
        AbstractGui.blit(ms, -BG_W / 2, -BG_H / 2, 0f, 0f, BG_W, BG_H, BG_W, BG_H);
        ms.pop();
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

    private void drawHeader(MatrixStack ms, double t, float ease) {
        int x = Math.max(24, width / 14);
        int slide = Math.round((1f - ease) * -30f);
        int logoS = 64;
        RenderSystem.enableBlend();
        minecraft.getTextureManager().bindTexture(LOGO);
        RenderSystem.color4f(1f, 1f, 1f, ease);
        AbstractGui.blit(ms, x + slide, 22, logoS, logoS, 0f, 0f, 128, 128, 128, 128);
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
            ms.push();
            ms.translate(cx, 30 + (float) wave * 0.8f, 0);
            ms.scale(ts, ts, 1f);
            font.drawStringWithShadow(ms, ch, 0, 0, color);
            ms.pop();
            cx += font.getStringWidth(ch) * ts;
        }
        Ui.text(ms, cfg.tagline, x + logoS + 14 + slide, 58, Theme.alpha(Theme.MUTED, (int) (255 * ease)));
    }

    private void drawButton(MatrixStack ms, Btn b, int idx, float ease, double t) {
        // aparitie esalonata
        float local = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - openedAt - 250 - idx * 70L) / 350f));
        if (local <= 0f) return;
        float e = 1f - (1f - local) * (1f - local) * (1f - local);
        int ox = Math.round((1f - e) * -40f) + Math.round(b.hover * 8f);
        int a = (int) (255 * e);
        int x = b.x + ox;
        int base = b.primary ? BLUE : 0xFF0B1426;
        int fill = Theme.alpha(Theme.mix(base, b.primary ? ICE : BLUE, b.hover * (b.primary ? 0.25f : 0.35f)), (int) ((b.primary ? 235 : 170 + 40 * b.hover) * e));
        Ui.round(ms, x, b.y, b.w, b.h, fill);
        int edge = Theme.alpha(b.primary ? ICE : Theme.mix(0xFF2A3B5E, BLUE, b.hover), a);
        Ui.rect(ms, x, b.y, b.w, 1, edge);
        Ui.rect(ms, x, b.y + b.h - 1, b.w, 1, edge);
        Ui.rect(ms, x, b.y, 3, b.h, Theme.alpha(b.primary ? 0xFFFFFFFF : BLUE, a));
        if (b.primary) {
            // luciu care trece peste buton
            int sweep = (int) (((t * 0.35) % 1.0) * (b.w + 40)) - 20;
            Ui.rect(ms, x + Math.max(3, sweep), b.y + 1, Math.min(14, Math.max(0, b.w - sweep)), b.h - 2, Theme.alpha(0xFFFFFFFF, 26));
        }
        int ty = b.y + (b.h - 8) / 2 - (b.primary ? 4 : 0);
        ms.push();
        float s = b.primary ? 1.25f : 1f;
        ms.translate(x + 12, ty + (b.primary ? 3 : 0), 0);
        ms.scale(s, s, 1f);
        font.drawStringWithShadow(ms, b.label, 0, 0, Theme.alpha(0xFFFFFFFF, a));
        ms.pop();
        if (b.primary || b.hover > 0.05f) {
            Ui.text(ms, b.hint, x + 12, b.y + b.h - 11, Theme.alpha(b.primary ? 0xFFDDF1FF : Theme.MUTED, (int) (a * (b.primary ? 0.9f : b.hover))));
        }
    }

    private void drawFooter(MatrixStack ms, double t) {
        int y = height - 14;
        Ui.text(ms, "Minecraft 1.16.5  |  " + ModList.get().size() + " moduri  |  FlowCity", 10, y, Theme.alpha(Theme.FAINT, 220));
        String name = minecraft.getSession().getUsername();
        Ui.textRight(ms, "Salut, " + name, width - 10, y, Theme.alpha(Theme.MUTED, 230));
        Ui.textRight(ms, "(c) SolarStudios", width - 10, y - 11, Theme.alpha(Theme.FAINT, 180));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : buttons) {
                if (Ui.in((int) mx, (int) my, b.x, b.y, b.w, b.h)) {
                    minecraft.getSoundHandler().play(net.minecraft.client.audio.SimpleSound.master(net.minecraft.util.SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    b.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }
}
