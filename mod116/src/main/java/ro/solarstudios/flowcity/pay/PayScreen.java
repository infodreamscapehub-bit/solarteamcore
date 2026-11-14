package ro.solarstudios.flowcity.pay;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * "Cum platesti?": doua carduri mari, CASH (bancnote din inventar) si CARD (soldul din banca, cere cardul bancar in
 * inventar). Alegerea se trimite la plugin, iar ecranul din care ai cerut plata (dealer, garaj etc.) revine imediat.
 */
public final class PayScreen extends FlowScreen {
    private static final int W = 320;
    private static final int H = 182;

    private final Screen parent;
    private final int id;
    private final String label;
    private final double amount;
    private final double cash;
    private final double bank;
    private final boolean card;
    private boolean done;
    private final long born = System.currentTimeMillis();
    private float offX;
    private float offY;
    private float scale = 1f;
    private final float[] hover = new float[2];
    private long lastNs = System.nanoTime();

    PayScreen(Screen parent, JsonObject d) {
        super("Plata", W, H);
        this.parent = parent;
        this.id = JsonClientModule.integer(d, "id", 0);
        this.label = JsonClientModule.str(d, "label", "");
        this.amount = JsonClientModule.num(d, "amount", 0);
        this.cash = JsonClientModule.num(d, "cash", 0);
        this.bank = JsonClientModule.num(d, "bank", 0);
        this.card = JsonClientModule.bool(d, "card", false);
    }

    Screen parent() {
        return parent;
    }

    @Override
    protected boolean framed() {
        return false;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static String money(double v) {
        String s = String.valueOf(Math.round(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private void pick(String method) {
        if (done) return;
        done = true;
        PayClient.choose(id, method);
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        if (!done) {
            done = true;
            PayClient.cancel(id);
        }
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float open = easeOut((now - born) / 260f);
        scale = Math.min(1f, Math.min((width - 12f) / W, (height - 12f) / H)) * (0.94f + 0.06f * open);
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;

        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0xC0040810, open));
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy, dt);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy, float dt) {
        Sk.cut(ms, -2, -2, W + 4, H + 4, 20, Sk.ACC);
        Sk.cut(ms, 0, 0, W, H, 18, 0xFF0A1422);
        Gfx.rrect(ms, 20, 0, W - 58, 2, 0, Sk.ACC);

        Gfx.text(ms, "PLATĂ", 22, 36, 14, Sk.WHITE, 0, true, 0f);
        String lb = label;
        while (lb.length() > 1 && Gfx.width(lb, 7.6f, false) > 150) lb = lb.substring(0, lb.length() - 1);
        Gfx.text(ms, lb, 23, 48, 7.6f, Sk.MUTED, 0);
        Gfx.text(ms, money(amount), W - 64, 40, 16, Sk.WHITE, 2, true, 0f);
        Gfx.text(ms, "lei", W - 56, 40, 8, Sk.MUTED, 0);

        Sk.close(ms, W - 28, 6, lmx, lmy);
        hitL(W - 28, 6, 20, 20, this::onClose);

        boolean canCash = cash + 1e-6 >= amount;
        boolean canCard = card && bank + 1e-6 >= amount;
        option(ms, 0, 22, 66, 134, 92, "CASH", "În inventar: " + money(cash) + " lei", canCash,
            canCash ? "" : "Nu ai destui bani", lmx, lmy, dt, () -> pick("cash"));
        option(ms, 1, 164, 66, 134, 92, "CARD", "În cont: " + money(bank) + " lei", canCard,
            !card ? "Ai nevoie de card în inventar" : (canCard ? "" : "Nu ai destui bani în cont"), lmx, lmy, dt, () -> pick("card"));
    }

    private void option(GuiGraphics ms, int idx, float x, float y, float w, float h, String title, String sub, boolean ok, String warn,
                        float lmx, float lmy, float dt, Runnable r) {
        boolean hov = ok && Sk.in(lmx, lmy, x, y, w, h);
        hover[idx] += ((hov ? 1f : 0f) - hover[idx]) * Math.min(1f, dt * 14f);
        float k = hover[idx];
        if (ok && k > 0.02f) Sk.cut(ms, x - 2, y - 2, w + 4, h + 4, 12, Gfx.fade(Sk.ACC, 0.35f + 0.65f * k));
        Sk.cut(ms, x, y, w, h, 10, ok ? Gfx.mixArgb(Sk.ROW | 0xFF000000, Sk.ROW_HOT | 0xFF000000, k) : 0xFF0C1522);
        if (ok) Gfx.rrect(ms, x + 10, y, w - 20, 2, 0, Gfx.fade(Sk.ACC, 0.5f + 0.5f * k));
        float cx = x + w / 2f;
        float a = ok ? 1f : 0.4f;
        if (idx == 0) bill(ms, cx, y + 26, a); else cardIcon(ms, cx, y + 26, a);
        Gfx.text(ms, title, cx, y + 56, 11, ok ? Sk.WHITE : Sk.MUTED, 1, true, 1f);
        Gfx.text(ms, sub, cx, y + 69, 6.8f, ok ? Sk.MUTED : Sk.FAINT, 1);
        if (!warn.isEmpty()) Gfx.text(ms, warn, cx, y + 81, 6.4f, Sk.BAD, 1);
        if (ok) hitL(x, y, w, h, r);
    }

    /** Bancnota desenata (plata, colturi drepte). */
    private static void bill(GuiGraphics ms, float cx, float cy, float a) {
        Gfx.rrect(ms, cx - 21, cy - 12, 42, 24, 0, Gfx.fade(0xFF2E9D5B, a));
        Gfx.rrect(ms, cx - 18, cy - 9, 36, 18, 0, Gfx.fade(0xFF3DB872, a));
        Gfx.disc(ms, cx, cy, 6.2f, Gfx.fade(0xFF1F6B3F, a));
        Gfx.text(ms, "$", cx, cy + 3.6f, 9, Gfx.fade(0xFFBFF3D3, a), 1, true, 0f);
        Gfx.rrect(ms, cx - 16, cy - 7, 4, 4, 0, Gfx.fade(0xFF1F6B3F, a));
        Gfx.rrect(ms, cx + 12, cy + 3, 4, 4, 0, Gfx.fade(0xFF1F6B3F, a));
    }

    /** Cardul bancar desenat (plat, ice-blue). */
    private static void cardIcon(GuiGraphics ms, float cx, float cy, float a) {
        Gfx.rrect(ms, cx - 21, cy - 13, 42, 26, 0, Gfx.fade(Sk.ACC, a));
        Gfx.rrect(ms, cx - 21, cy - 7, 42, 6, 0, Gfx.fade(0xFF061426, a * 0.9f));
        Gfx.rrect(ms, cx - 16, cy + 3, 12, 8, 0, Gfx.fade(Sk.WARN, a));
        Gfx.rrect(ms, cx + 4, cy + 8, 12, 2, 0, Gfx.fade(0xFF061426, a * 0.6f));
    }
}
