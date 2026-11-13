package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Pompa de benzina FlowCity: rezervorul masinii (ce ai + ce adaugi), afisaje digitale pentru litri si pret, butoane
 * rapide (+5, +10, +20, plin) si plata prin Vault (pe plugin). Dupa plata, cifrele si rezervorul "curg" animat.
 * 1 litru = 1000 de unitati de benzina MrCrayfish.
 */
public final class FuelScreen extends FlowScreen {
    private static final int W = 300;
    private static final int H = 196;

    private final int stationId;
    private final String stationName;
    private final Entity vehicle;
    private final long born = System.currentTimeMillis();
    private static double price = -1;
    private float liters;
    private float shownLiters;
    private long pumpAt;
    private float pumped;
    private boolean waiting;
    private String status = "";
    private boolean statusOk;
    private long lastNs = System.nanoTime();

    public FuelScreen(int stationId, String stationName, Entity vehicle) {
        super("Benzinarie", W, H);
        this.stationId = stationId;
        this.stationName = stationName;
        this.vehicle = vehicle;
        CarsClient.request("fuel_info", new JsonObject());
    }

    @Override
    protected boolean framed() {
        return false;
    }

    // ------------------------------------------------------------------ date din vehicul (reflexie, MrCrayfish)

    private static Object call(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(o);
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private float capL() {
        Object v = call(vehicle, "getFuelCapacity");
        return v instanceof Number ? ((Number) v).floatValue() / 1000f : 0f;
    }

    private float curL() {
        Object v = call(vehicle, "getCurrentFuel");
        return v instanceof Number ? ((Number) v).floatValue() / 1000f : 0f;
    }

    private boolean needsFuel() {
        Object v = call(vehicle, "requiresFuel");
        return !(v instanceof Boolean) || (Boolean) v;
    }

    // ------------------------------------------------------------------ raspunsuri de la plugin

    public static void info(double p) {
        price = p;
    }

    public void result(boolean ok, String msg, double paidLiters) {
        waiting = false;
        status = msg;
        statusOk = ok;
        if (ok) {
            pumped = (float) paidLiters;
            pumpAt = System.currentTimeMillis();
            liters = 0;
        }
    }

    private void buy() {
        if (waiting || liters <= 0.05f || price < 0) return;
        waiting = true;
        status = "Se proceseaza plata...";
        statusOk = true;
        JsonObject o = new JsonObject();
        o.addProperty("station", stationId);
        o.addProperty("entity", vehicle.getId());
        o.addProperty("liters", Math.round(liters * 10) / 10.0);
        CarsClient.request("fuel_buy", o);
    }

    private void add(float l) {
        float free = Math.max(0, capL() - curL());
        liters = Math.max(0, Math.min(free, liters + l));
        liters = Math.round(liters * 10) / 10f;
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        double t = now / 1000.0;
        float open = ease((now - born) / 300f);
        if (!vehicle.isAlive() || vehicle.distanceTo(Minecraft.getInstance().player) > 12) {
            onClose();
            return;
        }
        shownLiters += (liters - shownLiters) * Math.min(1f, dt * 12f);

        Gfx.rrect(g, 0, 0, width, height, 0, Gfx.fade(0x99050608, open));
        int x = (width - W) / 2;
        int y = (height - H) / 2 + Math.round((1f - open) * 12);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        Gfx.rrect(g, -3, 2, W + 6, H + 4, 18, Gfx.fade(0x66000000, open));
        Gfx.rrect(g, 0, 0, W, H, 16, Gfx.fade(0xFF1C1F25, open), Gfx.fade(0xFF121418, open));
        Gfx.rrect(g, 0.5f, 0.5f, W - 1, 1, 0, Gfx.fade(0x14FFFFFF, open));

        // antet: pompa desenata + nume
        pumpIcon(g, 18, 14, open, now);
        Gfx.text(g, "BENZINARIE", 44, 24, 12, Gfx.fade(0xFFF4F1EA, open), 0, true, 1f);
        Gfx.text(g, stationName, 44, 35, 8.5f, Gfx.fade(0xFF8E8C86, open), 0);
        boolean ch = Ui.in(mx, my, x + W - 30, y + 10, 20, 20);
        Gfx.rrect(g, W - 30, 10, 20, 20, 10, Gfx.fade(ch ? 0xFF2A2E36 : 0xFF1F2228, open));
        Gfx.line(g, W - 24, 16, W - 16, 24, 1.4f, Gfx.fade(0xFFB5B3AC, open));
        Gfx.line(g, W - 16, 16, W - 24, 24, 1.4f, Gfx.fade(0xFFB5B3AC, open));
        hit(x + W - 30, y + 10, 20, 20, this::onClose);

        float cap = capL();
        float cur = curL();
        if (!needsFuel() || cap <= 0) {
            Gfx.text(g, "Vehiculul nu foloseste benzina.", W / 2f, H / 2f + 4, 11, Gfx.fade(0xFFC9C7C0, open), 1);
            g.pose().popPose();
            return;
        }

        // rezervor (vertical): ce ai + ce adaugi (+ animatia de pompare)
        float tx = 18;
        float ty = 50;
        float tw = 34;
        float th = 120;
        float pumpK = pumpAt == 0 ? 1f : ease((now - pumpAt) / 1600f);
        float before = pumpAt == 0 ? cur : Math.max(0, cur - pumped * (1f - pumpK));
        Gfx.rrect(g, tx, ty, tw, th, 10, Gfx.fade(0xFF0E1013, open));
        float fNow = Math.min(1f, before / cap);
        float fAdd = Math.min(1f - fNow, shownLiters / cap);
        float hNow = (th - 6) * fNow;
        float hAdd = (th - 6) * fAdd;
        int fuelCol = fNow < 0.15f ? 0xFFE2453F : 0xFF4CC878;
        if (hNow > 1) Gfx.rrect(g, tx + 3, ty + th - 3 - hNow, tw - 6, hNow, 7, Gfx.fade(Gfx.mixArgb(fuelCol, 0xFFFFFFFF, 0.12f), open), Gfx.fade(Gfx.mixArgb(fuelCol, 0xFF000000, 0.25f), open));
        if (hAdd > 1) {
            float pulse = 0.55f + 0.25f * (float) Math.sin(t * 5);
            Gfx.rrect(g, tx + 3, ty + th - 3 - hNow - hAdd, tw - 6, hAdd, 7, Gfx.fade(0xFFE9A23B, open * pulse));
        }
        // bule cand pompeaza
        if (pumpAt != 0 && pumpK < 1f) {
            for (int i = 0; i < 6; i++) {
                float ph = (float) ((t * 1.4 + i * 0.17) % 1.0);
                float bx = tx + 8 + (i * 7) % (tw - 14);
                float by = ty + th - 6 - ph * Math.max(8, hNow);
                Gfx.disc(g, bx, by, 1.4f, Gfx.fade(0x99FFFFFF, open * (1 - ph)));
            }
        }
        for (int i = 1; i < 4; i++) Gfx.rrect(g, tx + tw - 9, ty + th * i / 4f, 6, 1, 0, Gfx.fade(0x33FFFFFF, open));
        Gfx.text(g, "REZERVOR", tx + tw / 2, ty + th + 12, 7, Gfx.fade(0xFF8E8C86, open), 1, false, 0.6f);
        Gfx.text(g, String.format("%.1f / %.0f L", before, cap), tx + tw / 2, ty + th + 22, 7.5f, Gfx.fade(0xFFC9C7C0, open), 1);

        // afisaje digitale
        float dx = 70;
        float dw = W - dx - 18;
        float half = (dw - 8) / 2;
        float shownPaidL = pumpAt == 0 ? shownLiters : pumped * pumpK;
        display(g, dx, 50, half, "LITRI", String.format("%.1f", shownPaidL), 0xFFF4F1EA, open);
        double pr = price < 0 ? 0 : price;
        display(g, dx + half + 8, 50, half, "DE PLATA", String.format("%.0f lei", shownPaidL * pr), 0xFFE9A23B, open);
        Gfx.text(g, price < 0 ? "Se citeste pretul..." : String.format("Pret: %.2f lei / litru", price), dx, 104, 8.5f, Gfx.fade(0xFF8E8C86, open), 0);
        Gfx.text(g, "Ai: " + Ui.money(CarsClient.money) + " lei", dx + dw, 104, 8.5f, Gfx.fade(0xFF8E8C86, open), 2);

        // butoane rapide
        String[] labels = {"+5 L", "+10 L", "+20 L", "PLIN"};
        float bw = (dw - 3 * 6) / 4;
        for (int i = 0; i < 4; i++) {
            final int k = i;
            float bx = dx + i * (bw + 6);
            float by = 112;
            boolean h = Ui.in(mx, my, (int) (x + bx), (int) (y + by), (int) bw, 22);
            Gfx.rrect(g, bx, by, bw, 22, 9, Gfx.fade(h ? 0xFF3A3E47 : 0xFF2A2E36, open));
            Gfx.rrect(g, bx + 1, by + 1, bw - 2, 20, 8, Gfx.fade(h ? 0xFF262A32 : 0xFF1A1D22, open));
            Gfx.text(g, labels[i], bx + bw / 2, by + 14.5f, 9.5f, Gfx.fade(h ? 0xFFF4F1EA : 0xFFC9C7C0, open), 1, true, 0);
            hit((int) (x + bx), (int) (y + by), (int) bw, 22, () -> {
                if (k == 3) liters = Math.round(Math.max(0, cap - cur) * 10) / 10f;
                else add(k == 0 ? 5 : (k == 1 ? 10 : 20));
            });
        }

        // plata
        boolean can = !waiting && liters > 0.05f && price >= 0;
        double cost = liters * Math.max(0, price);
        String label = waiting ? "Se alimenteaza..." : (liters > 0.05f ? String.format("Alimenteaza  ·  %.0f lei", cost) : "Alege cati litri");
        float px = dx;
        float py = 142;
        boolean ph = can && Ui.in(mx, my, (int) (x + px), (int) (y + py), (int) dw, 28);
        if (ph) Gfx.rrect(g, px - 3, py - 3, dw + 6, 34, 15, Gfx.fade(0x40E9A23B, open));
        Gfx.rrect(g, px, py, dw, 28, 13, Gfx.fade(can ? (ph ? 0xFFF6C15A : 0xFFE9A23B) : 0xFF2A2E36, open),
            Gfx.fade(can ? (ph ? 0xFFEE9A3A : 0xFFD48A2C) : 0xFF22252B, open));
        Gfx.text(g, label, px + dw / 2, py + 18, 11, Gfx.fade(can ? 0xFF16181D : 0xFF6F6D68, open), 1, true, 0);
        if (can) hit((int) (x + px), (int) (y + py), (int) dw, 28, this::buy);
        if (liters > 0.05f && !waiting) {
            Gfx.text(g, "Reseteaza", dx + dw, 181, 8, Gfx.fade(0xFF8E8C86, open), 2);
            hit((int) (x + dx + dw - 44), y + 173, 44, 12, () -> liters = 0);
        }
        if (!status.isEmpty()) Gfx.text(g, status, dx, 181, 8.5f, Gfx.fade(statusOk ? 0xFF6DBE6F : 0xFFE2453F, open), 0);
        g.pose().popPose();
    }

    private static void display(GuiGraphics g, float x, float y, float w, String label, String value, int col, float a) {
        Gfx.rrect(g, x, y, w, 46, 10, Gfx.fade(0xFF0B0D10, a));
        Gfx.rrect(g, x + 1, y + 1, w - 2, 1, 0, Gfx.fade(0x0DFFFFFF, a));
        Gfx.text(g, label, x + 10, y + 13, 7, Gfx.fade(0xFF6F6D68, a), 0, false, 0.8f);
        Gfx.text(g, value, x + w - 10, y + 38, 20, Gfx.fade(col, a), 2, true, 0);
    }

    /** Pompa desenata: corp, afisaj, furtun; picaturile cad cand alimentezi. */
    private void pumpIcon(GuiGraphics g, float x, float y, float a, long now) {
        int c = Gfx.fade(0xFFE9A23B, a);
        Gfx.rrect(g, x, y, 16, 24, 4, c);
        Gfx.rrect(g, x + 3, y + 3, 10, 6, 1.5f, Gfx.fade(0xFF16181D, a));
        Gfx.line(g, x + 16, y + 6, x + 21, y + 9, 1.6f, c);
        Gfx.line(g, x + 21, y + 9, x + 21, y + 19, 1.6f, c);
        Gfx.line(g, x + 21, y + 19, x + 18, y + 22, 1.6f, c);
        if (pumpAt != 0 && now - pumpAt < 1600) {
            float ph = ((now - pumpAt) % 400) / 400f;
            Gfx.disc(g, x + 18, y + 23 + ph * 4, 1.3f, Gfx.fade(0xFFE9A23B, a * (1 - ph)));
        }
    }
}
