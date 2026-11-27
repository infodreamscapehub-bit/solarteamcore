package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;

/**
 * FLOWCITY FUEL STATION: terminalul de plata al benzinariei.
 *
 * <p>Pasul 1 alegi metoda de plata (cash sau card, cardul scoate banii din contul bancar), pasul 2 alegi litrii
 * (5 / 10 / 20 / plin sau o cantitate proprie de la sageti) si confirmi. Dupa plata, pompa toarna benzina pe durata
 * configurata pe server si bara de progres urmeaza raportul real al serverului, nu o animatie inventata.
 * Toate verificarile (bani, card, capacitate, pompa libera) se fac pe server; ecranul doar cere si arata.
 */
public final class FuelScreen extends FlowScreen {
    private static final int W = 320;
    private static final int H = 208;

    private static final int BG = 0xFF14161B;
    private static final int PANEL = 0xFF1C1F26;
    private static final int LINE = 0x14FFFFFF;
    private static final int TXT = 0xFFF2F0EA;
    private static final int MUTED = 0xFF8C8A84;
    private static final int FAINT = 0xFF63615D;
    private static final int ACC = 0xFFE9A23B;
    private static final int OKC = 0xFF5BC47F;
    private static final int BADC = 0xFFE2453F;
    private static final int CARDC = 0xFF4DA3FF;

    private final String stationName;
    private final Entity vehicle;
    private final String vehicleName;
    private final float capL;
    private final long born = System.currentTimeMillis();

    // date de la plugin
    private static double price = -1;
    private static double cash;
    private static double bank;
    private static boolean hasCard;
    private static boolean cashAllowed = true;
    private static boolean cardAllowed = true;

    private boolean card;          // metoda aleasa
    private float liters;
    private float shownLiters;
    private boolean waiting;       // am trimis plata, astept raspuns
    private boolean pumping;       // plata acceptata, benzina curge
    private float bought;
    private float delivered;
    private String status = "";
    private int statusKind;        // 0 neutru, 1 bun, 2 rau
    private long lastNs = System.nanoTime();

    public FuelScreen(String stationName, Entity vehicle, String vehicleName, float capL) {
        super("Benzinarie", W, H);
        this.stationName = stationName == null || stationName.isEmpty() ? "Benzinarie" : stationName;
        this.vehicle = vehicle;
        this.vehicleName = vehicleName == null || vehicleName.isEmpty() ? "Vehicul" : vehicleName;
        this.capL = capL;
        this.card = !cashAllowed && cardAllowed;
        CarsClient.request("fuel_info", new JsonObject());
    }

    @Override
    protected boolean framed() {
        return false;
    }

    // ------------------------------------------------------------------ date

    public static void info(double p, double cashNow, double bankNow, boolean cardNow, boolean cashOk, boolean cardOk) {
        price = p;
        cash = cashNow;
        bank = bankNow;
        hasCard = cardNow;
        cashAllowed = cashOk;
        cardAllowed = cardOk;
    }

    /** Raspuns la plata: ok+end=false inseamna "a inceput sa curga". */
    public void result(boolean ok, String msg, double litersPaid, boolean end) {
        waiting = false;
        status = msg;
        statusKind = ok ? 1 : 2;
        if (ok && !end) {
            pumping = true;
            bought = (float) litersPaid;
            delivered = 0;
            liters = 0;
        } else if (end) {
            pumping = false;
            delivered = (float) litersPaid;
        }
    }

    /** Progresul raportat de server in timpul alimentarii. */
    public void progress(double boughtL, double doneL, boolean end) {
        bought = (float) boughtL;
        delivered = (float) doneL;
        if (end) pumping = false;
        else pumping = true;
    }

    private float curL() {
        float v = CarsClient.fuelOf(vehicle);
        return v < 0 ? 0 : v;
    }

    private float freeL() {
        return Math.max(0, capL - curL());
    }

    private void buy() {
        if (waiting || pumping || liters <= 0.05f || price < 0) return;
        if (card && !hasCard) {
            status = "Nu detii un card bancar valid.";
            statusKind = 2;
            return;
        }
        waiting = true;
        status = "Se proceseaza plata...";
        statusKind = 0;
        JsonObject o = new JsonObject();
        o.addProperty("liters", Math.round(liters * 10) / 10.0);
        o.addProperty("method", card ? "card" : "cash");
        CarsClient.request("fuel_buy", o);
    }

    private void add(float l) {
        liters = Math.max(0, Math.min(freeL(), liters + l));
        liters = Math.round(liters * 10) / 10f;
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private double cost() {
        return liters * Math.max(0, price);
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float open = ease((now - born) / 260f);
        Minecraft mc = Minecraft.getInstance();
        if (vehicle == null || !vehicle.isAlive() || mc.player == null || vehicle.distanceTo(mc.player) > 14) {
            onClose();
            return;
        }
        shownLiters += (liters - shownLiters) * Math.min(1f, dt * 14f);

        Gfx.rrect(g, 0, 0, width, height, 0, Gfx.fade(0xAA05060A, open));
        int x = (width - W) / 2;
        int y = (height - H) / 2 + Math.round((1f - open) * 10);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        Gfx.rrect(g, -3, 3, W + 6, H + 4, 18, Gfx.fade(0x66000000, open));
        Gfx.rrect(g, 0, 0, W, H, 16, Gfx.fade(PANEL, open), Gfx.fade(BG, open));
        Gfx.rrect(g, 0.5f, 0.5f, W - 1, 1, 0, Gfx.fade(LINE, open));

        header(g, x, y, mx, my, open, now);
        tank(g, open, now);

        float dx = 74;
        float dw = W - dx - 16;
        if (pumping || (delivered > 0 && !waiting && statusKind == 1 && liters <= 0.05f)) pumpingView(g, x, y, dx, dw, mx, my, open, now);
        else buyView(g, x, y, dx, dw, mx, my, open);

        if (!status.isEmpty()) {
            int c = statusKind == 1 ? OKC : (statusKind == 2 ? BADC : MUTED);
            Gfx.text(g, status, dx, H - 11, 8.2f, Gfx.fade(c, open), 0);
        }
        g.pose().popPose();
    }

    /** Antet: pompa desenata, titlul statiei si butonul de inchidere. */
    private void header(GuiGraphics g, int x, int y, int mx, int my, float open, long now) {
        pumpIcon(g, 16, 13, open, now);
        Gfx.text(g, "FLOWCITY FUEL STATION", 42, 22, 10.5f, Gfx.fade(TXT, open), 0, true, 1.1f);
        Gfx.text(g, stationName + "  ·  " + vehicleName, 42, 33, 8f, Gfx.fade(MUTED, open), 0);
        boolean ch = Ui.in(mx, my, x + W - 30, y + 10, 20, 20);
        Gfx.rrect(g, W - 30, 10, 20, 20, 10, Gfx.fade(ch ? 0xFF2E333C : 0xFF212429, open));
        Gfx.line(g, W - 24, 16, W - 16, 24, 1.4f, Gfx.fade(ch ? TXT : MUTED, open));
        Gfx.line(g, W - 16, 16, W - 24, 24, 1.4f, Gfx.fade(ch ? TXT : MUTED, open));
        hit(x + W - 30, y + 10, 20, 20, this::onClose);
        Gfx.rrect(g, 16, 42, W - 32, 1, 0, Gfx.fade(LINE, open));
    }

    /** Rezervorul vertical: ce ai, ce adaugi si ce a intrat deja in timpul alimentarii. */
    private void tank(GuiGraphics g, float open, long now) {
        float tx = 18;
        float ty = 52;
        float tw = 34;
        float th = 124;
        float cur = curL();
        Gfx.rrect(g, tx, ty, tw, th, 10, Gfx.fade(0xFF0C0E12, open));
        float fNow = capL <= 0 ? 0 : Math.min(1f, cur / capL);
        float fAdd = capL <= 0 ? 0 : Math.min(1f - fNow, (pumping ? Math.max(0, bought - delivered) : shownLiters) / capL);
        float hNow = (th - 6) * fNow;
        float hAdd = (th - 6) * fAdd;
        int col = fNow < 0.15f ? BADC : OKC;
        if (hNow > 1) {
            Gfx.rrect(g, tx + 3, ty + th - 3 - hNow, tw - 6, hNow, 7,
                Gfx.fade(Gfx.mixArgb(col, 0xFFFFFFFF, 0.12f), open), Gfx.fade(Gfx.mixArgb(col, 0xFF000000, 0.25f), open));
        }
        if (hAdd > 1) {
            float pulse = 0.5f + 0.3f * (float) Math.sin(now / 180.0);
            Gfx.rrect(g, tx + 3, ty + th - 3 - hNow - hAdd, tw - 6, hAdd, 7, Gfx.fade(ACC, open * pulse));
        }
        if (pumping) {
            for (int i = 0; i < 6; i++) {
                float ph = (float) ((now / 700.0 + i * 0.17) % 1.0);
                float bx = tx + 8 + (i * 7) % (tw - 14);
                float by = ty + th - 6 - ph * Math.max(10, hNow);
                Gfx.disc(g, bx, by, 1.4f, Gfx.fade(0x99FFFFFF, open * (1 - ph)));
            }
        }
        for (int i = 1; i < 4; i++) Gfx.rrect(g, tx + tw - 9, ty + th * i / 4f, 6, 1, 0, Gfx.fade(0x33FFFFFF, open));
        Gfx.text(g, "REZERVOR", tx + tw / 2, ty + th + 11, 6.6f, Gfx.fade(FAINT, open), 1, false, 0.8f);
        Gfx.text(g, String.format("%.1f / %.0f L", cur, capL), tx + tw / 2, ty + th + 21, 7.4f, Gfx.fade(MUTED, open), 1);
    }

    /** Pasul de cumparare: metode de plata, cantitati, total si butonul de confirmare. */
    private void buyView(GuiGraphics g, int x, int y, float dx, float dw, int mx, int my, float open) {
        float half = (dw - 8) / 2;
        // metode de plata (carduri)
        method(g, x, y, dx, 50, half, mx, my, open, false, "CASH", String.format("%.0f lei", cash), cashAllowed);
        method(g, x, y, dx + half + 8, 50, half, mx, my, open, true, "CARD BANCAR",
            hasCard ? String.format("%.0f lei", bank) : "fara card", cardAllowed && hasCard);

        // afisaje: litri si total
        display(g, dx, 92, half, "LITRI", String.format("%.1f", shownLiters), TXT, open);
        display(g, dx + half + 8, 92, half, "DE PLATA", String.format("%.0f lei", cost()), ACC, open);
        String pr = price < 0 ? "Se citeste pretul..." : String.format("%.2f lei / litru  ·  liber %.1f L", price, freeL());
        Gfx.text(g, pr, dx, 131, 7.6f, Gfx.fade(FAINT, open), 0);

        // cantitati rapide + cantitate proprie
        String[] labels = {"+5", "+10", "+20", "PLIN", "-1", "+1"};
        float bw = (dw - 5 * 5) / 6;
        for (int i = 0; i < 6; i++) {
            final int k = i;
            float bx = dx + i * (bw + 5);
            float by = 140;
            boolean h = Ui.in(mx, my, (int) (x + bx), (int) (y + by), (int) bw, 21);
            Gfx.rrect(g, bx, by, bw, 21, 9, Gfx.fade(h ? 0xFF3A404A : 0xFF2A2E36, open));
            Gfx.rrect(g, bx + 1, by + 1, bw - 2, 19, 8, Gfx.fade(h ? 0xFF272C34 : 0xFF1A1D23, open));
            Gfx.text(g, labels[k], bx + bw / 2, by + 14, 8.6f, Gfx.fade(h ? TXT : MUTED, open), 1, true, 0);
            hit((int) (x + bx), (int) (y + by), (int) bw, 21, () -> {
                if (k == 3) liters = Math.round(freeL() * 10) / 10f;
                else if (k == 4) add(-1);
                else if (k == 5) add(1);
                else add(k == 0 ? 5 : (k == 1 ? 10 : 20));
            });
        }

        // confirmare
        boolean can = !waiting && liters > 0.05f && price >= 0 && (card ? (cardAllowed && hasCard) : cashAllowed);
        String label = waiting ? "Se proceseaza..."
            : (liters > 0.05f ? String.format("CONFIRMA  ·  %.0f lei  ·  %s", cost(), card ? "card" : "cash") : "Alege cati litri");
        float py = 169;
        boolean ph = can && Ui.in(mx, my, (int) (x + dx), (int) (y + py), (int) dw, 26);
        if (ph) Gfx.rrect(g, dx - 3, py - 3, dw + 6, 32, 15, Gfx.fade(0x40E9A23B, open));
        int c1 = can ? (ph ? 0xFFF6C15A : ACC) : 0xFF2A2E36;
        int c2 = can ? (ph ? 0xFFEE9A3A : 0xFFD48A2C) : 0xFF22252B;
        Gfx.rrect(g, dx, py, dw, 26, 13, Gfx.fade(c1, open), Gfx.fade(c2, open));
        Gfx.text(g, label, dx + dw / 2, py + 17, 10, Gfx.fade(can ? 0xFF16181D : FAINT, open), 1, true, 0);
        if (can) hit((int) (x + dx), (int) (y + py), (int) dw, 26, this::buy);
        if (liters > 0.05f && !waiting) {
            Gfx.text(g, "Reseteaza", dx + dw, H - 11, 7.6f, Gfx.fade(FAINT, open), 2);
            hit((int) (x + dx + dw - 44), (int) (y + H - 15), 44, 12, () -> liters = 0);
        }
    }

    /** Cardul unei metode de plata; cel ales are chenar colorat. */
    private void method(GuiGraphics g, int x, int y, float bx, float by, float bw, int mx, int my, float open,
                        boolean isCard, String name, String sub, boolean usable) {
        boolean sel = card == isCard;
        boolean h = Ui.in(mx, my, (int) (x + bx), (int) (y + by), (int) bw, 34);
        int acc = isCard ? CARDC : OKC;
        Gfx.rrect(g, bx, by, bw, 34, 11, Gfx.fade(sel ? Gfx.mixArgb(0xFF2A2E36, acc, 0.45f) : (h ? 0xFF2E333C : 0xFF24272E), open));
        Gfx.rrect(g, bx + 1, by + 1, bw - 2, 32, 10, Gfx.fade(sel ? 0xFF1E232B : 0xFF181B21, open));
        float ix = bx + 16;
        float iy = by + 17;
        if (isCard) {
            Gfx.rrect(g, ix - 9, iy - 6, 18, 12, 2.5f, Gfx.fade(usable ? acc : 0xFF4A4D55, open));
            Gfx.rrect(g, ix - 9, iy - 3, 18, 2.5f, 0, Gfx.fade(0xFF14161B, open));
        } else {
            Gfx.rrect(g, ix - 9, iy - 6, 18, 12, 2.5f, Gfx.fade(usable ? acc : 0xFF4A4D55, open));
            Gfx.disc(g, ix, iy, 3.2f, Gfx.fade(0xFF14161B, open));
        }
        Gfx.text(g, name, bx + 30, by + 12, 8.4f, Gfx.fade(usable ? TXT : FAINT, open), 0, true, 0.3f);
        Gfx.text(g, sub, bx + 30, by + 23, 7.4f, Gfx.fade(usable ? MUTED : FAINT, open), 0);
        if (usable) {
            hit((int) (x + bx), (int) (y + by), (int) bw, 34, () -> {
                card = isCard;
                if (statusKind == 2) status = "";
            });
        }
    }

    /** In timpul alimentarii: cat a curs din cat s-a platit, cu bara de progres. */
    private void pumpingView(GuiGraphics g, int x, int y, float dx, float dw, int mx, int my, float open, long now) {
        float half = (dw - 8) / 2;
        display(g, dx, 50, half, "ALIMENTAT", String.format("%.1f L", delivered), OKC, open);
        display(g, dx + half + 8, 50, half, "PLATIT", String.format("%.0f lei", delivered * Math.max(0, price)), ACC, open);
        float p = bought <= 0 ? 1f : Math.min(1f, delivered / bought);
        Gfx.text(g, pumping ? "Pompa lucreaza..." : "Alimentare incheiata", dx, 104, 8.4f, Gfx.fade(pumping ? MUTED : OKC, open), 0);
        Gfx.text(g, String.format("%.1f / %.1f L", delivered, bought), dx + dw, 104, 8.4f, Gfx.fade(MUTED, open), 2);
        Gfx.rrect(g, dx, 118, dw, 10, 5, Gfx.fade(0xFF0C0E12, open));
        if (p > 0.01f) Gfx.rrect(g, dx + 1.5f, 119.5f, (dw - 3) * p, 7, 3.5f, Gfx.fade(ACC, open), Gfx.fade(0xFFD48A2C, open));
        if (pumping) {
            float sx = dx + 1.5f + (dw - 3) * p;
            Gfx.disc(g, sx, 123, 2.2f + 0.8f * (float) Math.sin(now / 120.0), Gfx.fade(0xFFFFF0D0, open));
        }
        Gfx.text(g, pumping ? "Nu te indeparta de pompa: alimentarea se opreste si primesti restul inapoi."
            : "Poti inchide terminalul.", dx, 140, 7.6f, Gfx.fade(FAINT, open), 0);
        if (!pumping) {
            float py = 169;
            boolean h = Ui.in(mx, my, (int) (x + dx), (int) (y + py), (int) dw, 26);
            Gfx.rrect(g, dx, py, dw, 26, 13, Gfx.fade(h ? 0xFF3A404A : 0xFF2A2E36, open));
            Gfx.text(g, "INCHIDE", dx + dw / 2, py + 17, 10, Gfx.fade(TXT, open), 1, true, 0);
            hit((int) (x + dx), (int) (y + py), (int) dw, 26, this::onClose);
        }
    }

    private static void display(GuiGraphics g, float x, float y, float w, String label, String value, int col, float a) {
        Gfx.rrect(g, x, y, w, 40, 10, Gfx.fade(0xFF0A0C10, a));
        Gfx.rrect(g, x + 1, y + 1, w - 2, 1, 0, Gfx.fade(0x0DFFFFFF, a));
        Gfx.text(g, label, x + 10, y + 12, 6.6f, Gfx.fade(FAINT, a), 0, false, 0.9f);
        Gfx.text(g, value, x + w - 10, y + 33, 17, Gfx.fade(col, a), 2, true, 0);
    }

    /** Pompa desenata: corp, afisaj, furtun; picaturile cad cand alimenteaza. */
    private void pumpIcon(GuiGraphics g, float x, float y, float a, long now) {
        int c = Gfx.fade(ACC, a);
        Gfx.rrect(g, x, y, 16, 24, 4, c);
        Gfx.rrect(g, x + 3, y + 3, 10, 6, 1.5f, Gfx.fade(0xFF16181D, a));
        Gfx.line(g, x + 16, y + 6, x + 21, y + 9, 1.6f, c);
        Gfx.line(g, x + 21, y + 9, x + 21, y + 19, 1.6f, c);
        Gfx.line(g, x + 21, y + 19, x + 18, y + 22, 1.6f, c);
        if (pumping) {
            float ph = (now % 500) / 500f;
            Gfx.disc(g, x + 18, y + 23 + ph * 4, 1.3f, Gfx.fade(ACC, a * (1 - ph)));
        }
    }
}
