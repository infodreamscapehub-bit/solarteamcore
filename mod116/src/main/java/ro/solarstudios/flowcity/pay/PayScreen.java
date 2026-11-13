package ro.solarstudios.flowcity.pay;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
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
    private static final int GOLD = 0xFFF6C15A;
    private static final int GOLD2 = 0xFFD98E2B;
    private static final int WHITE = 0xFFF4F1EA;
    private static final int MUTED = 0xFF8E8C86;
    private static final int FAINT = 0xFF6F6D68;

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

    private static boolean inside(float mx, float my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
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

        Gfx.rrect(ms, 0, 0, width, height, 0, Gfx.fade(0xB0050608, open));
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Gfx.rrect(ms, -6, 2, W + 12, H + 10, 22, 0x55000000);
        Gfx.rrect(ms, 0, 0, W, H, 18, 0xFF1D2026, 0xFF121418);
        Gfx.rrect(ms, 0.5f, 0.5f, W - 1, 1, 0, 0x14FFFFFF);

        Gfx.text(ms, "PLATĂ", 22, 34, 12, WHITE, 0, true, 0.8f);
        String lb = label;
        while (lb.length() > 1 && Gfx.width(lb, 7.6f, false) > 170) lb = lb.substring(0, lb.length() - 1);
        Gfx.text(ms, lb, 22, 47, 7.6f, MUTED, 0);
        Gfx.text(ms, money(amount), W - 56, 40, 17, GOLD, 2, true, 0.3f);
        Gfx.text(ms, "lei", W - 22, 40, 8, FAINT, 2);

        // inchidere
        boolean hx = inside(lmx, lmy, W - 32, 8, 18, 18);
        if (hx) Gfx.disc(ms, W - 23, 17, 9, 0x22FFFFFF);
        int xc = hx ? 0xFFE2453F : 0xFF8E8C86;
        Gfx.line(ms, W - 27, 13, W - 19, 21, 1.6f, xc);
        Gfx.line(ms, W - 19, 13, W - 27, 21, 1.6f, xc);
        hitL(W - 32, 8, 18, 18, this::onClose);

        boolean canCash = cash + 1e-6 >= amount;
        boolean canCard = card && bank + 1e-6 >= amount;
        option(ms, 0, 22, 66, 134, 92, "CASH", "În inventar: " + money(cash) + " lei", canCash,
            canCash ? "" : "Nu ai destui bani", lmx, lmy, dt, () -> pick("cash"));
        option(ms, 1, 164, 66, 134, 92, "CARD", "În cont: " + money(bank) + " lei", canCard,
            !card ? "Ai nevoie de card în inventar" : (canCard ? "" : "Nu ai destui bani în cont"), lmx, lmy, dt, () -> pick("card"));
        ms.pose().popPose();
    }

    private void option(GuiGraphics ms, int idx, float x, float y, float w, float h, String title, String sub, boolean ok, String warn,
                        float lmx, float lmy, float dt, Runnable r) {
        boolean hov = ok && inside(lmx, lmy, x, y, w, h);
        hover[idx] += ((hov ? 1f : 0f) - hover[idx]) * Math.min(1f, dt * 14f);
        float k = hover[idx];
        if (ok) {
            float p = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 600.0 + idx);
            Gfx.rrect(ms, x - 2 - p, y - 2 - p, w + 4 + 2 * p, h + 4 + 2 * p, 16, Gfx.fade(0x26F6C15A, 0.4f + k * 0.6f));
        }
        Gfx.rrect(ms, x, y, w, h, 14, ok ? Gfx.mixArgb(0xFF2A2E36, GOLD, k * 0.16f) : 0xFF191B20, ok ? Gfx.mixArgb(0xFF1C1F25, GOLD2, k * 0.12f) : 0xFF15171B);
        if (ok) Gfx.rrect(ms, x + 8, y + 1, w - 16, 1.2f, 0.6f, Gfx.fade(0xFFFFFFFF, 0.12f + k * 0.2f));
        float cx = x + w / 2f;
        float a = ok ? 1f : 0.4f;
        if (idx == 0) bill(ms, cx, y + 26, a); else cardIcon(ms, cx, y + 26, a);
        Gfx.text(ms, title, cx, y + 56, 11, Gfx.fade(ok ? WHITE : MUTED, 1f), 1, true, 1f);
        Gfx.text(ms, sub, cx, y + 69, 6.8f, ok ? 0xFFA19F98 : FAINT, 1);
        if (!warn.isEmpty()) Gfx.text(ms, warn, cx, y + 81, 6.4f, 0xFFF08A82, 1);
        if (ok) hitL(x, y, w, h, r);
    }

    /** Bancnota desenata. */
    private static void bill(GuiGraphics ms, float cx, float cy, float a) {
        Gfx.rrect(ms, cx - 21, cy - 12, 42, 24, 4, Gfx.fade(0xFF5FDC8F, a), Gfx.fade(0xFF2E9D5B, a));
        Gfx.rrect(ms, cx - 18, cy - 9, 36, 18, 2.5f, Gfx.fade(0x22FFFFFF, a));
        Gfx.disc(ms, cx, cy, 6.2f, Gfx.fade(0xFF1F6B3F, a));
        Gfx.text(ms, "$", cx, cy + 3.6f, 9, Gfx.fade(0xFFBFF3D3, a), 1, true, 0f);
        Gfx.disc(ms, cx - 14, cy - 5, 1.6f, Gfx.fade(0xFF1F6B3F, a));
        Gfx.disc(ms, cx + 14, cy + 5, 1.6f, Gfx.fade(0xFF1F6B3F, a));
    }

    /** Cardul bancar desenat. */
    private static void cardIcon(GuiGraphics ms, float cx, float cy, float a) {
        Gfx.rrect(ms, cx - 21, cy - 13, 42, 26, 5, Gfx.fade(0xFF5BA8FF, a), Gfx.fade(0xFF2F6FDB, a));
        Gfx.rrect(ms, cx - 21, cy - 7, 42, 6, 0, Gfx.fade(0xFF14161A, a * 0.85f));
        Gfx.rrect(ms, cx - 16, cy + 3, 12, 8, 2, Gfx.fade(0xFFF6C15A, a));
        Gfx.rrect(ms, cx + 4, cy + 8, 12, 2, 1, Gfx.fade(0x88FFFFFF, a));
    }
}
