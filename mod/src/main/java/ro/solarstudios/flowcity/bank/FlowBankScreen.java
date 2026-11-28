package ro.solarstudios.flowcity.bank;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.admin.IdsClient;
import ro.solarstudios.flowcity.client.ui.FScreen;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.hype.HypeClient;
import ro.solarstudios.flowcity.hype.HypeState;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * FLOWCITY BANK: aplicatia bancara (ATM si /banca deschid acelasi ecran). Sectiuni: Acasa, Retragere, Depunere, Transfer,
 * Istoric tranzactii, Card bancar, Setari cont (+ pagina Credit si economii, din Acasa). Serverul valideaza tot (card,
 * limite, sold, destinatar); aici se afiseaza doar ce raspunde el. Soldurile sunt cele reale (cont bancar + cash).
 * Nu exista PIN in sistem, deci ecranul nu are nicio functie de PIN.
 */
public final class FlowBankScreen extends FScreen {
    static final int ORANGE = 0xFFFF7A1A;
    private static final int W = 660;
    private static final int H = 380;
    private static final int SB = 164;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd.MM.yyyy HH:mm");
    private static final int[] QUICK = {100, 500, 1000, 5000};

    private static final int HOME = 0;
    private static final int WITHDRAW = 1;
    private static final int DEPOSIT = 2;
    private static final int TRANSFER = 3;
    private static final int HISTORY = 4;
    private static final int CARD = 5;
    private static final int SETTINGS = 6;
    private static final int PRODUCTS = 7;
    private static final String[] NAV = {"Acasă", "Retragere", "Depunere", "Transfer", "Istoric tranzacții", "Card bancar", "Setări cont"};
    private static final String[] NAV_ICON = {"home", "chev-up", "chev-down", "send", "list", "card", "gear"};

    // preferinte (client): salvate in flowcity/bank-prefs.json
    private static boolean confirmOps = true;
    private static boolean hideBalance = false;
    private static boolean prefsLoaded;

    private int page = HOME;
    private float pageT = 1f;
    private int histPage;
    private int histFilter;
    private long pendingUntil;
    private JsonObject pendingRef;
    private long openedAt = System.currentTimeMillis();

    // verificare destinatar
    private String lookedFor = "";
    private String lookedName = "";
    private int lookState; // 0 nimic, 1 in curs, 2 gasit, 3 negasit, 4 tu insuti, 5 ID necunoscut
    private long lookAt;
    private String resolved = "";

    public FlowBankScreen() {
        super("FlowCity Bank", W, H);
        loadPrefs();
        requestData();
    }

    /** Cere datele pentru ambele surse (cont/card prin Hype, credit/economii prin Banca). */
    public static void requestData() {
        HypeClient.request("atm_info", new JsonObject());
        BankClient.refresh();
    }

    // ------------------------------------------------------------------ preferinte

    private static File prefsFile() {
        File d = new File(Minecraft.getInstance().gameDirectory, "flowcity");
        d.mkdirs();
        return new File(d, "bank-prefs.json");
    }

    private static void loadPrefs() {
        if (prefsLoaded) return;
        prefsLoaded = true;
        try {
            File f = prefsFile();
            if (!f.isFile()) return;
            try (FileReader r = new FileReader(f)) {
                JsonObject o = new com.google.gson.JsonParser().parse(r).getAsJsonObject();
                if (o.has("confirm")) confirmOps = o.get("confirm").getAsBoolean();
                if (o.has("hide")) hideBalance = o.get("hide").getAsBoolean();
            }
        } catch (Exception ignored) {
            // preferinte lipsa sau corupte: raman valorile implicite
        }
    }

    private static void savePrefs() {
        try (FileWriter w = new FileWriter(prefsFile())) {
            JsonObject o = new JsonObject();
            o.addProperty("confirm", confirmOps);
            o.addProperty("hide", hideBalance);
            w.write(o.toString());
        } catch (Exception ignored) {
            // nu se poate scrie: preferinta ramane doar pe sesiune
        }
    }

    // ------------------------------------------------------------------ date

    void update() {
        // datele se citesc direct din HypeState.atm si BankClient.state la fiecare cadru
    }

    void result(boolean ok, String m) {
        toast(m, !ok);
    }

    private static double bank() {
        return JsonClientModule.num(HypeState.atm, "bank", JsonClientModule.num(BankClient.state, "bank", 0));
    }

    private static double cash() {
        return JsonClientModule.num(HypeState.atm, "cash", JsonClientModule.num(BankClient.state, "cash", 0));
    }

    private static JsonObject card() {
        JsonObject a = HypeState.atm;
        return a.has("card") && a.get("card").isJsonObject() ? a.getAsJsonObject("card") : new JsonObject();
    }

    private static JsonArray history() {
        JsonObject a = HypeState.atm;
        return a.has("history") && a.get("history").isJsonArray() ? a.getAsJsonArray("history") : new JsonArray();
    }

    private static boolean loaded() {
        return HypeState.atm.has("bank");
    }

    private boolean pending() {
        return System.currentTimeMillis() < pendingUntil && HypeState.atm == pendingRef;
    }

    private void send(String action, JsonObject o) {
        pendingRef = HypeState.atm;
        pendingUntil = System.currentTimeMillis() + 2500;
        HypeClient.request(action, o);
    }

    private static String label(String type) {
        switch (type) {
            case "DEPOSIT": return "Depunere";
            case "WITHDRAW": return "Retragere";
            case "TRANSFER_OUT": return "Transfer trimis";
            case "TRANSFER_IN": return "Transfer primit";
            case "PAYDAY": return "Salariu";
            case "FUEL": return "Benzinărie (card)";
            case "FUEL_CASH": return "Benzinărie (cash)";
            default: return type;
        }
    }

    private static boolean plus(String type) {
        return type.equals("TRANSFER_IN") || type.equals("DEPOSIT") || type.equals("PAYDAY");
    }

    private static String sign(double v) {
        return (v < 0 ? "-" : "") + num(Math.abs(v));
    }

    private double amount(String id) {
        try {
            return Double.parseDouble(text(id).trim().replace(',', '.').replace(" ", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void go(int p) {
        if (page == p) return;
        page = p;
        pageT = 0f;
        blur();
        closeModal();
        if (p == HISTORY) histPage = 0;
    }

    // ------------------------------------------------------------------ cadru

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        FTheme.accent = ORANGE;
        pageT = Math.min(1f, pageT + 0.09f);
        shell(g);
        sidebar(g, mx, my);

        float cx = SB + 20;
        float cw = W - SB - 40;
        if (!loaded()) {
            FUi.loading(g, SB + (W - SB) / 2f, H / 2f - 6, (System.currentTimeMillis() - openedAt) / 1000f, ORANGE);
            tc(g, System.currentTimeMillis() - openedAt > 5000 ? "Nu am primit datele contului. Verifică cardul și conexiunea." : "Se încarcă contul…", SB + (W - SB) / 2f, H / 2f + 24, 7.6f, FTheme.MUTED);
            iconBtn(g, "x-close", "close", W - 32, 14, 18, false, mx, my, this::onClose);
            return;
        }
        // tranzitie scurta: continutul urca usor si apare
        float e = FTheme.ease(pageT);
        float slide = (1f - e) * 8f;
        g.pose().pushPose();
        g.pose().translate(0, slide, 0);
        try {
            switch (page) {
                case HOME: home(g, cx, 24, cw, mx, my); break;
                case WITHDRAW: money(g, true, cx, 24, cw, mx, my); break;
                case DEPOSIT: money(g, false, cx, 24, cw, mx, my); break;
                case TRANSFER: transfer(g, cx, 24, cw, mx, my); break;
                case HISTORY: history(g, cx, 24, cw, mx, my); break;
                case CARD: cardPage(g, cx, 24, cw, mx, my); break;
                case SETTINGS: settings(g, cx, 24, cw, mx, my); break;
                case PRODUCTS: products(g, cx, 24, cw, mx, my); break;
                default: break;
            }
        } finally {
            g.pose().popPose();
        }
        iconBtn(g, "x-close", "close", W - 32, 12, 18, false, mx, my, this::onClose);
        if (pageT < 1f) Gfx.rrect(g, SB, 0, W - SB, H, 0, FTheme.alpha(FTheme.BG, (int) ((1f - e) * 120)));
        drawModal(g, modalTitle, modalText, modalOk, "Anulează", false, mx, my);
    }

    private String modalTitle = "";
    private String modalText = "";
    private String modalOk = "Confirmă";

    private void ask(String title, String text, String ok, Runnable run) {
        if (!confirmOps && !"Blochează cardul".equals(ok)) {
            run.run();
            return;
        }
        modalTitle = title;
        modalText = text;
        modalOk = ok;
        openModal(run);
    }

    // ------------------------------------------------------------------ bara laterala

    private void sidebar(GuiGraphics g, int mx, int my) {
        Gfx.rrect(g, 0, 0, SB, H, 14f, 0xFF0B0D10);
        Gfx.rrect(g, SB - 16, 0, 16, H, 0f, 0xFF0B0D10);
        Gfx.rrect(g, SB - 1, 14, 1, H - 28, 0f, FTheme.LINE);
        // marca: patrat portocaliu cu F + cuvinte
        Gfx.rrect(g, 18, 20, 22, 22, 5f, ORANGE);
        tc(g, "F", 29, 36.4f, 12f, 0xFF0B0D10);
        tx(g, "FLOWCITY", 48, 30, 9.4f, FTheme.TEXT);
        FTheme.caption(g, "BANK", 49, 40, ORANGE);

        float y = 66;
        for (int i = 0; i < NAV.length; i++) {
            final int pi = i;
            boolean on = page == i || (page == PRODUCTS && i == HOME);
            boolean hov = inr(mx, my, 8, y, SB - 16, 28);
            float t = FUi.hot("bnav" + i, hov && !on);
            if (on) Gfx.rrect(g, 8, y, SB - 16, 28, 6f, FTheme.alpha(ORANGE, 30));
            else if (t > 0.01f) Gfx.rrect(g, 8, y, SB - 16, 28, 6f, FTheme.alpha(0xFFFFFF, (int) (12 * t)));
            if (on) Gfx.rrect(g, 8, y + 6, 2.5f, 16, 1.2f, ORANGE);
            FUi.icon(g, NAV_ICON[i], 26, y + 14, 12f, on ? ORANGE : FTheme.MUTED);
            tx(g, NAV[i], 42, y + 17.6f, 8.2f, on ? FTheme.TEXT : FTheme.MUTED);
            hit(8, (int) y, SB - 16, 28, () -> go(pi));
            y += 31;
        }
        // titular
        JsonObject c = card();
        String holder = JsonClientModule.str(c, "holder", "");
        Gfx.rrect(g, 12, H - 62, SB - 24, 1, 0, FTheme.LINE);
        FTheme.caption(g, "TITULAR", 18, H - 46, FTheme.FAINT);
        tx(g, clip(holder, SB - 40, 8.2f, true), 18, H - 32, 8.2f, FTheme.TEXT);
        int id = myId();
        t(g, id > 0 ? "ID FlowCity  #" + id : "ID indisponibil", 18, H - 20, 6.6f, FTheme.MUTED);
    }

    private static int myId() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? 0 : IdsClient.idOf(mc.player.getUUID());
    }

    private void head(GuiGraphics g, String title, String sub, float x, float y) {
        tx(g, title, x, y + 14, 13f, FTheme.TEXT);
        t(g, sub, x, y + 26, 7f, FTheme.MUTED);
    }

    // ------------------------------------------------------------------ acasa

    private void home(GuiGraphics g, float x, float y, float w, int mx, int my) {
        head(g, "Acasă", "Contul tău FlowCity Bank", x, y);
        double bank = bank();
        double cash = cash();

        // sold: bloc mare, fara decor
        float by = y + 40;
        float lw = w - 236;
        FTheme.panel(g, x, by, lw, 96, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "SOLD BANCAR", x + 16, by + 20, FTheme.FAINT);
        String bs = hideBalance ? "••••••" : lei(bank);
        tx(g, bs, x + 16, by + 52, 22f, FTheme.TEXT);
        Gfx.rrect(g, x + 16, by + 66, lw - 32, 1, 0, FTheme.LINE);
        FTheme.caption(g, "CASH LA TINE", x + 16, by + 82, FTheme.FAINT);
        tr(g, hideBalance ? "••••" : lei(cash), x + lw - 16, by + 83, 9f, FTheme.MUTED);
        iconBtn(g, "hide", hideBalance ? "eye" : "eye", x + lw - 34, by + 10, 18, hideBalance, mx, my, () -> {
            hideBalance = !hideBalance;
            savePrefs();
        });

        // card miniatura
        cardArt(g, x + lw + 12, by, 212, 96, mx, my);
        hit((int) (x + lw + 12), (int) by, 212, 96, () -> go(CARD));

        // acces rapid
        float qy = by + 110;
        FTheme.caption(g, "ACCES RAPID", x, qy, FTheme.FAINT);
        String[] ql = {"Retragere", "Depunere", "Transfer", "Credit și economii"};
        String[] qi = {"chev-up", "chev-down", "send", "layers"};
        int[] qp = {WITHDRAW, DEPOSIT, TRANSFER, PRODUCTS};
        float qw = (w - 3 * 8) / 4f;
        for (int i = 0; i < 4; i++) {
            final int target = qp[i];
            float qx = x + i * (qw + 8);
            boolean hv = FUi.card(g, "bq" + i, qx, qy + 6, qw, 34, true, mx, my);
            FUi.icon(g, qi[i], qx + 18, qy + 23, 12f, hv ? ORANGE : FTheme.MUTED);
            tx(g, clip(ql[i], qw - 40, 7.6f, true), qx + 32, qy + 26.6f, 7.6f, FTheme.TEXT);
            hit((int) qx, (int) (qy + 6), (int) qw, 34, () -> go(target));
        }

        // ultimele tranzactii
        float ty = qy + 54;
        FTheme.caption(g, "ULTIMELE TRANZACȚII", x, ty, FTheme.FAINT);
        iconBtnText(g, "Vezi tot", x + w - 54, ty - 9, mx, my, () -> go(HISTORY));
        JsonArray arr = history();
        float rowH = 22;
        int shown = 0;
        float yy = ty + 6;
        for (JsonElement el : arr) {
            if (!el.isJsonObject() || shown >= 4) break;
            txRow(g, el.getAsJsonObject(), x, yy, w, rowH);
            yy += rowH;
            shown++;
        }
        if (shown == 0) t(g, "Nicio tranzacție încă. Depunerile, retragerile și transferurile apar aici.", x, yy + 14, 7f, FTheme.FAINT);
    }

    private void iconBtnText(GuiGraphics g, String s, float x, float y, int mx, int my, Runnable r) {
        boolean hv = inr(mx, my, x - 4, y, 58, 14);
        tx(g, s, x, y + 9, 7f, hv ? ORANGE : FTheme.MUTED);
        hit((int) x - 4, (int) y, 58, 14, r);
    }

    private void txRow(GuiGraphics g, JsonObject h, float x, float y, float w, float rowH) {
        String type = JsonClientModule.str(h, "t", "");
        boolean pl = plus(type);
        String who = JsonClientModule.str(h, "o", "");
        boolean detail = type.startsWith("FUEL") || type.equals("PAYDAY");
        long ts = (long) JsonClientModule.num(h, "ts", 0);
        double amt = JsonClientModule.num(h, "a", 0);
        Gfx.rrect(g, x, y + rowH - 1, w, 1, 0, FTheme.LINE_SOFT);
        FUi.icon(g, pl ? "chev-down" : "chev-up", x + 8, y + rowH / 2f, 9f, pl ? FTheme.OK : FTheme.MUTED);
        tx(g, clip(label(type) + (who.isEmpty() || detail ? "" : "  ·  " + who), w - 190, 7.6f, true), x + 22, y + 10, 7.6f, FTheme.TEXT);
        t(g, ts > 0 ? DATE.format(new Date(ts)) : "", x + 22, y + 19, 6f, FTheme.FAINT);
        tr(g, (pl ? "+" : "-") + num(Math.abs(amt)) + " lei", x + w - 6, y + 14, 8f, pl ? FTheme.OK : FTheme.TEXT);
    }

    // ------------------------------------------------------------------ card (desen original)

    private void cardArt(GuiGraphics g, float x, float y, float w, float h, int mx, int my) {
        JsonObject c = card();
        String status = JsonClientModule.str(c, "status", "ACTIV");
        boolean blocked = "BLOCAT".equals(status);
        int body = blocked ? 0xFF2A2020 : 0xFF1B1F27;
        Gfx.rrect(g, x, y, w, h, 8f, FTheme.LINE);
        Gfx.rrect(g, x + 1, y + 1, w - 2, h - 2, 7f, body);
        // banda portocalie in partea de sus, plata
        Gfx.rrect(g, x + 1, y + 1, w - 2, 6, 7f, blocked ? FTheme.DANGER : ORANGE);
        Gfx.rrect(g, x + 1, y + 4, w - 2, 3, 0f, blocked ? FTheme.DANGER : ORANGE);
        tx(g, "FLOWCITY BANK", x + 14, y + 26, 8f, FTheme.TEXT);
        // cip
        Gfx.rrect(g, x + 14, y + 36, 26, 19, 4f, 0xFFB8903A);
        Gfx.rrect(g, x + 14, y + 44, 26, 1, 0, 0xFF8A6B22);
        Gfx.rrect(g, x + 26, y + 36, 1, 19, 0, 0xFF8A6B22);
        String last4 = JsonClientModule.str(c, "last4", "0000");
        tx(g, "••••  ••••  ••••  " + last4, x + 14, y + h - 24, 8.6f, FTheme.TEXT);
        t(g, clip(JsonClientModule.str(c, "holder", "").toUpperCase(Locale.ROOT), w - 90, 6.6f, false), x + 14, y + h - 10, 6.6f, FTheme.MUTED);
        pill(g, status, x + w - 14 - (Gfx.width(status, 6.4f, true) + 10f), y + h - 22, blocked ? FTheme.DANGER : ("LIPSA".equals(status) ? FTheme.WARN : FTheme.OK));
    }

    private void cardPage(GuiGraphics g, float x, float y, float w, int mx, int my) {
        head(g, "Card bancar", "Cardul FlowCity Bank asociat contului tău", x, y);
        JsonObject c = card();
        String status = JsonClientModule.str(c, "status", "ACTIV");
        boolean blocked = "BLOCAT".equals(status);
        cardArt(g, x, y + 44, 262, 160, mx, my);

        float rx = x + 262 + 20;
        float rw = w - 262 - 20;
        FTheme.panel(g, rx, y + 44, rw, 160, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "DETALII", rx + 14, y + 64, FTheme.FAINT);
        row(g, "Titular", JsonClientModule.str(c, "holder", "-"), rx + 14, y + 76, rw - 28);
        row(g, "Număr", "•••• •••• •••• " + JsonClientModule.str(c, "last4", "0000"), rx + 14, y + 94, rw - 28);
        row(g, "Stare", blocked ? "Blocat" : ("LIPSA".equals(status) ? "Card lipsă din inventar" : "Activ"), rx + 14, y + 112, rw - 28);
        boolean req = JsonClientModule.bool(c, "required", true);
        row(g, "Cerut la ATM", req ? "Da" : "Nu (permisiune staff)", rx + 14, y + 130, rw - 28);
        boolean inv = JsonClientModule.bool(c, "inInventory", false);
        row(g, "În inventar", inv ? "Da" : "Nu", rx + 14, y + 148, rw - 28);

        float by = y + 218;
        if (blocked) {
            btn(g, "unblock", "Deblochează cardul", "unlock", x, by, 200, 30, FUi.PRIMARY, !pending(), mx, my, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("blocked", false);
                send("atm_card", o);
            });
            FUi.message(g, FUi.MSG_ERROR, "Cardul e blocat: ATM-ul refuză orice operațiune.", x + 212, by - 1, w - 212);
        } else {
            btn(g, "block", "Blochează cardul", "lock", x, by, 200, 30, FUi.DANGER, !pending(), mx, my, () ->
                ask("Blochezi cardul?", "ATM-ul nu va mai accepta cardul până îl deblochezi de aici.", "Blochează cardul", () -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("blocked", true);
                    send("atm_card", o);
                }));
            FUi.message(g, FUi.MSG_INFO, "Dacă ți-ai pierdut cardul, blochează-l până îl găsești.", x + 212, by - 1, w - 212);
        }
        if (pending()) FUi.loading(g, x + 220, by + 15, (System.currentTimeMillis() % 2000) / 1000f, ORANGE);
    }

    private void row(GuiGraphics g, String k, String v, float x, float y, float w) {
        t(g, k, x, y + 8, 7f, FTheme.MUTED);
        tr(g, clip(v, w - 90, 7.6f, true), x + w, y + 8, 7.6f, FTheme.TEXT);
    }

    // ------------------------------------------------------------------ retragere / depunere

    private void money(GuiGraphics g, boolean withdraw, float x, float y, float w, int mx, int my) {
        head(g, withdraw ? "Retragere" : "Depunere", withdraw ? "Retragi din contul bancar; bancnotele ajung în inventar" : "Depui cash în contul bancar", x, y);
        JsonObject d = HypeState.atm;
        double avail = withdraw ? bank() : cash();
        String id = withdraw ? "w-amount" : "d-amount";
        float lw = 300;

        FTheme.panel(g, x, y + 40, lw, 92, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, withdraw ? "SOLD DISPONIBIL (BANCĂ)" : "CASH DISPONIBIL", x + 16, y + 60, FTheme.FAINT);
        tx(g, hideBalance ? "••••••" : lei(avail), x + 16, y + 88, 18f, FTheme.TEXT);
        double left = JsonClientModule.num(d, "withdrawLeft", -1);
        if (withdraw && left >= 0) t(g, "Mai poți retrage azi: " + lei(left), x + 16, y + 112, 6.8f, FTheme.MUTED);
        else t(g, "Minim " + num(JsonClientModule.num(d, "minAmount", 1)) + " lei per operațiune", x + 16, y + 112, 6.8f, FTheme.MUTED);

        FTheme.caption(g, "SUMA", x, y + 152, FTheme.FAINT);
        input(g, id, "ex. 500", x, y + 158, lw, 24, 12, mx, my);
        float qx = x;
        for (int q : QUICK) {
            final int qv = q;
            qx += chipAt(g, id + "q" + q, num(q), qx, y + 190, 17, false, mx, my, () -> setText(id, String.valueOf(qv)));
        }
        final long allv = (long) Math.floor(avail);
        chipAt(g, id + "qa", "Tot", qx, y + 190, 17, false, mx, my, () -> setText(id, String.valueOf(allv)));

        double a = amount(id);
        boolean enough = a <= avail;
        boolean can = a > 0 && enough && !pending() && !modalActive();
        float by = y + 222;
        String msg = null;
        if (a > 0 && !enough) msg = withdraw ? "Fonduri insuficiente în cont." : "Nu ai atât cash la tine.";
        else if (withdraw && a > 0 && left >= 0 && a > left) msg = "Depășești limita zilnică de retragere.";
        if (msg != null) FUi.message(g, FUi.MSG_ERROR, msg, x, by - 32, lw);
        final double fa = a;
        btn(g, id + "go", withdraw ? "Retrage" : "Depune", withdraw ? "chev-up" : "chev-down", x, by, lw, 30, FUi.PRIMARY, can, mx, my, () ->
            ask(withdraw ? "Confirmă retragerea" : "Confirmă depunerea", (withdraw ? "Retragi " : "Depui ") + lei(fa) + (withdraw ? " din cont." : " în cont."), "Confirmă", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("amount", fa);
                send(withdraw ? "atm_withdraw" : "atm_deposit", o);
                setText(id, "");
            }));
        if (pending()) FUi.loading(g, x + lw / 2f + 50, by + 15, (System.currentTimeMillis() % 2000) / 1000f, FTheme.TEXT);

        // rezumat dupa sold
        float rx = x + lw + 24;
        float rw = w - lw - 24;
        FTheme.panel(g, rx, y + 40, rw, 126, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "DUPĂ OPERAȚIUNE", rx + 14, y + 60, FTheme.FAINT);
        double nb = bank();
        double nc = cash();
        if (a > 0 && enough) {
            nb += withdraw ? -a : a;
            nc += withdraw ? a : -a;
        }
        row(g, "Sold bancar", hideBalance ? "••••" : lei(nb), rx + 14, y + 74, rw - 28);
        row(g, "Cash", hideBalance ? "••••" : lei(nc), rx + 14, y + 94, rw - 28);
        row(g, "Taxă", "0 lei", rx + 14, y + 114, rw - 28);
        t(g, "Serverul verifică din nou cardul, limitele și soldul.", rx + 14, y + 150, 6.2f, FTheme.FAINT);
    }

    // ------------------------------------------------------------------ transfer

    private void transfer(GuiGraphics g, float x, float y, float w, int mx, int my) {
        head(g, "Transfer", "Trimite bani din cont către alt jucător", x, y);
        JsonObject d = HypeState.atm;
        double bank = bank();
        double fp = JsonClientModule.num(d, "feePercent", 0);
        double fmin = JsonClientModule.num(d, "feeMin", 0);
        double tl = JsonClientModule.num(d, "transferLeft", -1);
        float lw = 300;

        FTheme.caption(g, "DESTINATAR (NUME SAU #ID)", x, y + 50, FTheme.FAINT);
        input(g, "t-to", "ex. Popescu sau #12", x, y + 56, lw, 24, 16, mx, my);
        recipientCheck(g, x, y + 84, lw);

        FTheme.caption(g, "SUMA", x, y + 112, FTheme.FAINT);
        input(g, "t-amount", "ex. 500", x, y + 118, lw, 24, 12, mx, my);
        float qx = x;
        for (int q : QUICK) {
            final int qv = q;
            qx += chipAt(g, "tq" + q, num(q), qx, y + 148, 17, false, mx, my, () -> setText("t-amount", String.valueOf(qv)));
        }
        final long allv = (long) Math.floor(bank);
        chipAt(g, "tqa", "Tot", qx, y + 148, 17, false, mx, my, () -> setText("t-amount", String.valueOf(allv)));

        FTheme.caption(g, "DESCRIERE (OPȚIONAL)", x, y + 182, FTheme.FAINT);
        input(g, "t-note", "ex. chirie", x, y + 188, lw, 24, 40, mx, my);

        double a = amount("t-amount");
        double fee = a > 0 ? Math.max(fmin, Math.round(a * fp) / 100.0) : 0;
        double total = a + fee;
        boolean enough = total <= bank;
        boolean ok = a > 0 && lookState == 2 && enough && !pending() && !modalActive();
        float by = y + 226;
        String msg = null;
        if (a > 0 && !enough) msg = "Ai nevoie de " + lei(total) + " în cont (suma + taxa).";
        else if (tl >= 0 && a > tl) msg = "Depășești limita zilnică de transfer.";
        if (msg != null) FUi.message(g, FUi.MSG_ERROR, msg, x, by - 34, lw);
        final double fa = a;
        final double ffee = fee;
        btn(g, "t-go", "Trimite", "send", x, by, lw, 30, FUi.PRIMARY, ok, mx, my, () ->
            ask("Confirmă transferul", lei(fa) + " către " + resolved + " · taxă " + (ffee == Math.floor(ffee) ? num(ffee) : String.valueOf(ffee)) + " lei", "Trimite", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("amount", fa);
                o.addProperty("to", resolved);
                String note = text("t-note").trim();
                if (!note.isEmpty()) o.addProperty("note", note);
                send("atm_transfer", o);
                setText("t-amount", "");
                setText("t-note", "");
            }));
        if (pending()) FUi.loading(g, x + lw / 2f + 40, by + 15, (System.currentTimeMillis() % 2000) / 1000f, FTheme.TEXT);

        // rezumat
        float rx = x + lw + 24;
        float rw = w - lw - 24;
        FTheme.panel(g, rx, y + 44, rw, 150, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "REZUMAT", rx + 14, y + 64, FTheme.FAINT);
        row(g, "Către", lookState == 2 ? resolved : "—", rx + 14, y + 76, rw - 28);
        row(g, "Sumă", a > 0 ? lei(a) : "—", rx + 14, y + 96, rw - 28);
        row(g, "Taxă (" + fp + "%, min " + fmin + ")", a > 0 ? (fee == Math.floor(fee) ? num(fee) : String.valueOf(fee)) + " lei" : "—", rx + 14, y + 116, rw - 28);
        Gfx.rrect(g, rx + 14, y + 138, rw - 28, 1, 0, FTheme.LINE);
        row(g, "Total din cont", a > 0 ? lei(total) : "—", rx + 14, y + 146, rw - 28);
        row(g, "Sold după", a > 0 && enough ? lei(bank - total) : "—", rx + 14, y + 166, rw - 28);
        if (tl >= 0) t(g, "Mai poți transfera azi: " + lei(tl), rx, y + 208, 6.8f, FTheme.MUTED);
    }

    /** Verifica destinatarul (server) cand se opreste din tastat; ID-urile online se rezolva local. */
    private void recipientCheck(GuiGraphics g, float x, float y, float w) {
        String q = text("t-to").trim();
        if (!q.equals(lookedFor)) {
            lookedFor = q;
            lookAt = System.currentTimeMillis();
            lookState = q.isEmpty() ? 0 : 1;
            resolved = "";
        }
        if (lookState == 1 && System.currentTimeMillis() - lookAt > 450) {
            String name = q;
            if (q.startsWith("#") || q.matches("\\d+")) {
                try {
                    int id = Integer.parseInt(q.replace("#", ""));
                    UUID u = IdsClient.uuidOfId(id);
                    Minecraft mc = Minecraft.getInstance();
                    net.minecraft.world.entity.player.Player pe = u == null || mc.level == null ? null : mc.level.getPlayerByUUID(u);
                    name = pe == null ? "" : pe.getGameProfile().getName();
                } catch (NumberFormatException e) {
                    name = "";
                }
                if (name.isEmpty()) {
                    lookState = 5;
                    lookAt = Long.MAX_VALUE;
                }
            }
            if (lookState == 1) {
                lookState = 6; // cerere trimisa
                JsonObject o = new JsonObject();
                o.addProperty("name", name);
                HypeClient.request("atm_lookup", o);
                lookAt = Long.MAX_VALUE;
                pendingLookup = name;
            }
        }
        if (lookState == 6 && lookResult != null && lookResult.q.equals(pendingLookup)) {
            if (lookResult.self) lookState = 4;
            else if (lookResult.found) {
                lookState = 2;
                resolved = lookResult.name;
            } else lookState = 3;
        }
        switch (lookState) {
            case 1:
            case 6:
                t(g, "Se verifică destinatarul…", x, y + 10, 6.8f, FTheme.MUTED);
                break;
            case 2:
                FUi.icon(g, "check", x + 5, y + 6, 9f, FTheme.OK);
                t(g, "Destinatar găsit: " + resolved, x + 14, y + 9, 6.8f, FTheme.OK);
                break;
            case 3:
                t(g, "Jucătorul nu există pe server.", x, y + 9, 6.8f, FTheme.DANGER);
                break;
            case 4:
                t(g, "Nu poți face transfer către tine.", x, y + 9, 6.8f, FTheme.WARN);
                break;
            case 5:
                t(g, "ID necunoscut (se rezolvă doar jucătorii online).", x, y + 9, 6.8f, FTheme.DANGER);
                break;
            default:
                break;
        }
    }

    private String pendingLookup = "";

    static final class Look {
        final String q;
        final boolean found;
        final boolean self;
        final String name;

        Look(String q, boolean found, boolean self, String name) {
            this.q = q;
            this.found = found;
            this.self = self;
            this.name = name;
        }
    }

    private static Look lookResult;

    /** Raspunsul serverului la verificarea destinatarului. */
    public static void onLookup(JsonObject d) {
        lookResult = new Look(JsonClientModule.str(d, "q", ""), JsonClientModule.bool(d, "found", false),
            JsonClientModule.bool(d, "self", false), JsonClientModule.str(d, "name", ""));
    }

    // ------------------------------------------------------------------ istoric

    private List<JsonObject> filtered() {
        String q = text("h-q").trim().toLowerCase(Locale.ROOT);
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement el : history()) {
            if (!el.isJsonObject()) continue;
            JsonObject h = el.getAsJsonObject();
            String type = JsonClientModule.str(h, "t", "");
            boolean pl = plus(type);
            if (histFilter == 1 && !pl) continue;
            if (histFilter == 2 && pl) continue;
            if (!q.isEmpty()) {
                String hay = (label(type) + " " + JsonClientModule.str(h, "o", "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            out.add(h);
        }
        return out;
    }

    @Override
    protected void onInputChanged(String id) {
        if ("h-q".equals(id)) histPage = 0;
    }

    private void history(GuiGraphics g, float x, float y, float w, int mx, int my) {
        head(g, "Istoric tranzacții", "Ultimele operațiuni de pe contul tău", x, y);
        input(g, "h-q", "Caută după tip sau persoană", x, y + 38, 230, 22, 24, mx, my);
        String[] fl = {"Toate", "Intrări", "Ieșiri"};
        float fx = x + 244;
        for (int i = 0; i < fl.length; i++) {
            final int fi = i;
            fx += chipAt(g, "hf" + i, fl[i], fx, y + 40, 18, histFilter == i, mx, my, () -> {
                histFilter = fi;
                histPage = 0;
            });
        }

        List<JsonObject> rows = filtered();
        int per = 8;
        int pages = Math.max(1, (rows.size() + per - 1) / per);
        histPage = Math.max(0, Math.min(histPage, pages - 1));

        float ty = y + 70;
        float[] cx = {0, 96, 196, 296, w - 150, w - 70};
        FTheme.panel(g, x, ty, w, 24 + per * 25 + 4, 6f, FTheme.BG2, FTheme.LINE);
        String[] cols = {"DATA ȘI ORA", "TIP", "CU / DE LA", "SUMĂ", "STATUS", "SOLD DUPĂ"};
        for (int i = 0; i < cols.length; i++) {
            if (i >= 3) FTheme.caption(g, cols[i], x + cx[i] + 14 + (i == 5 ? 68 : (i == 3 ? 78 : 0)), ty + 16, FTheme.FAINT);
            else FTheme.caption(g, cols[i], x + cx[i] + 14, ty + 16, FTheme.FAINT);
        }
        Gfx.rrect(g, x + 8, ty + 22, w - 16, 1, 0, FTheme.LINE);
        if (rows.isEmpty()) {
            FUi.empty(g, "clock", "Nicio tranzacție", "Nu există operațiuni pentru filtrul ales.", x + w / 2f, ty + 80, w - 60);
        }
        float yy = ty + 26;
        for (int i = histPage * per; i < Math.min(rows.size(), (histPage + 1) * per); i++) {
            JsonObject h = rows.get(i);
            String type = JsonClientModule.str(h, "t", "");
            boolean pl = plus(type);
            long ts = (long) JsonClientModule.num(h, "ts", 0);
            double amt = JsonClientModule.num(h, "a", 0);
            double fee = JsonClientModule.num(h, "f", 0);
            double bal = JsonClientModule.num(h, "b", -1);
            String who = JsonClientModule.str(h, "o", "");
            float ry = yy + (i - histPage * per) * 25;
            if ((i & 1) == 1) Gfx.rrect(g, x + 4, ry, w - 8, 25, 3f, FTheme.alpha(0xFFFFFF, 6));
            t(g, ts > 0 ? DATE.format(new Date(ts)) : "—", x + 14, ry + 15, 6.6f, FTheme.MUTED);
            tx(g, clip(label(type), 96, 7f, true), x + cx[1] + 14, ry + 15, 7f, FTheme.TEXT);
            t(g, clip(who.isEmpty() ? "—" : who, 90, 7f, false), x + cx[2] + 14, ry + 15, 7f, FTheme.MUTED);
            String amStr = (pl ? "+" : "-") + num(Math.abs(amt)) + (fee > 0 ? "  (-" + (fee == Math.floor(fee) ? num(fee) : String.valueOf(fee)) + ")" : "");
            tx(g, amStr, x + cx[3] + 14, ry + 15, 7.4f, pl ? FTheme.OK : FTheme.TEXT);
            pill(g, "Finalizat", x + cx[4] + 14, ry + 6, FTheme.OK);
            tr(g, bal >= 0 ? num(bal) : "—", x + w - 14, ry + 15, 7.2f, bal >= 0 ? FTheme.TEXT : FTheme.FAINT);
        }

        // paginare
        float py2 = ty + 24 + per * 25 + 12;
        t(g, rows.size() + " tranzacții", x, py2 + 11, 6.8f, FTheme.MUTED);
        float rx = x + w;
        iconBtn(g, "hp-next", "chev-right", rx - 22, py2, 18, false, mx, my, () -> histPage = Math.min(pages - 1, histPage + 1));
        tc(g, (histPage + 1) + " / " + pages, rx - 56, py2 + 12, 7.4f, FTheme.TEXT);
        iconBtn(g, "hp-prev", "chev-left", rx - 112, py2, 18, false, mx, my, () -> histPage = Math.max(0, histPage - 1));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (page == HISTORY) {
            histPage = Math.max(0, histPage - (int) Math.signum(delta));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ setari

    private void settings(GuiGraphics g, float x, float y, float w, int mx, int my) {
        head(g, "Setări cont", "Informații cont și preferințe ale acestui ecran", x, y);
        JsonObject d = HypeState.atm;
        JsonObject c = card();
        float lw = (w - 20) / 2f;

        FTheme.panel(g, x, y + 40, lw, 170, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "CONT", x + 14, y + 60, FTheme.FAINT);
        row(g, "Titular", JsonClientModule.str(c, "holder", "-"), x + 14, y + 72, lw - 28);
        int id = myId();
        row(g, "ID FlowCity", id > 0 ? "#" + id : "—", x + 14, y + 92, lw - 28);
        row(g, "Sold bancar", hideBalance ? "••••" : lei(bank()), x + 14, y + 112, lw - 28);
        row(g, "Cash", hideBalance ? "••••" : lei(cash()), x + 14, y + 132, lw - 28);
        row(g, "Card", JsonClientModule.str(c, "status", "—"), x + 14, y + 152, lw - 28);
        double wl = JsonClientModule.num(d, "withdrawLeft", -1);
        double tl = JsonClientModule.num(d, "transferLeft", -1);
        row(g, "Retragere azi", wl < 0 ? "fără limită" : lei(wl), x + 14, y + 172, lw - 28);
        row(g, "Transfer azi", tl < 0 ? "fără limită" : lei(tl), x + 14, y + 192, lw - 28);

        float rx = x + lw + 20;
        FTheme.panel(g, rx, y + 40, lw, 112, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "PREFERINȚE", rx + 14, y + 60, FTheme.FAINT);
        pref(g, "Confirmare înainte de operațiuni", "Cere confirmare la retragere, depunere, transfer.", rx + 14, y + 72, lw - 28, confirmOps, mx, my, () -> {
            confirmOps = !confirmOps;
            savePrefs();
        });
        pref(g, "Ascunde soldurile", "Înlocuiește sumele cu puncte în acest ecran.", rx + 14, y + 110, lw - 28, hideBalance, mx, my, () -> {
            hideBalance = !hideBalance;
            savePrefs();
        });
        FTheme.panel(g, rx, y + 162, lw, 48, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "TAXE", rx + 14, y + 180, FTheme.FAINT);
        double fp = JsonClientModule.num(d, "feePercent", 0);
        double fmin = JsonClientModule.num(d, "feeMin", 0);
        t(g, "Transfer: " + fp + "% (min " + fmin + " lei). Retragerea și depunerea nu au taxă.", rx + 14, y + 196, 6.6f, FTheme.MUTED);
    }

    private void pref(GuiGraphics g, String title, String sub, float x, float y, float w, boolean on, int mx, int my, Runnable r) {
        tx(g, title, x, y + 9, 7.6f, FTheme.TEXT);
        t(g, clip(sub, w - 50, 6.2f, false), x, y + 20, 6.2f, FTheme.MUTED);
        FUi.toggle(g, "pf-" + title, x + w - 28, y + 5, on, mx, my);
        hit((int) (x + w - 30), (int) (y + 3), 32, 20, r);
    }

    // ------------------------------------------------------------------ credit si economii (din Acasa)

    private void qbtn(GuiGraphics g, String id, String label, float x, float y, float w, boolean en, int mx, int my, Runnable r) {
        btn(g, id, label, null, x, y, w, 20, FUi.SOFT, en && !pending(), mx, my, r);
    }

    private void products(GuiGraphics g, float x, float y, float w, int mx, int my) {
        JsonObject s = BankClient.state;
        head(g, "Credit și economii", "Produsele contului tău", x, y);
        iconBtnText(g, "‹ Acasă", x + w - 60, y + 6, mx, my, () -> go(HOME));
        double bank = bank();
        double cash = cash();
        double savings = JsonClientModule.num(s, "savings", 0);
        double rate = JsonClientModule.num(s, "savingsRate", 0);
        double hours = JsonClientModule.num(s, "hours", 0);
        float lw = (w - 16) / 2f;
        float y0 = y + 40;
        float ch = 250;

        FTheme.panel(g, x, y0, lw, ch, 6f, FTheme.BG2, FTheme.LINE);
        boolean hasLoan = s.has("loan") && s.get("loan").isJsonObject();
        if (hasLoan) {
            JsonObject l = s.getAsJsonObject("loan");
            double owed = JsonClientModule.num(l, "owed", 0);
            double total = Math.max(1, JsonClientModule.num(l, "total", owed));
            long due = (long) JsonClientModule.num(l, "due", 0);
            boolean overdue = JsonClientModule.bool(l, "overdue", false);
            FTheme.caption(g, "CREDIT ACTIV", x + 14, y0 + 20, FTheme.FAINT);
            if (overdue) pill(g, "RESTANT", x + lw - 14 - 48, y0 + 10, FTheme.DANGER);
            tx(g, lei(owed), x + 14, y0 + 46, 16f, overdue ? FTheme.DANGER : FTheme.TEXT);
            t(g, overdue ? "Scadența a trecut" : "Scadent " + new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(due)), x + 14, y0 + 60, 7f, overdue ? FTheme.DANGER : FTheme.MUTED);
            float paid = (float) Math.max(0, Math.min(1, 1 - owed / total));
            FUi.progress(g, "RAMBURSAT", Math.round(paid * 100) + "%", x + 14, y0 + 74, lw - 28, paid, FTheme.OK);
            float qw = (lw - 28 - 16) / 3f;
            FTheme.caption(g, "RAMBURSEAZĂ DIN CONT", x + 14, y0 + 110, FTheme.FAINT);
            qbtn(g, "rb1", "1.000", x + 14, y0 + 116, qw, bank >= 1, mx, my, () -> BankClient.repay(1000, false, false));
            qbtn(g, "rb2", "5.000", x + 14 + qw + 8, y0 + 116, qw, bank >= 1, mx, my, () -> BankClient.repay(5000, false, false));
            qbtn(g, "rb3", "Tot", x + 14 + 2 * (qw + 8), y0 + 116, qw, bank >= 1, mx, my, () -> BankClient.repay(0, true, false));
            FTheme.caption(g, "RAMBURSEAZĂ CASH", x + 14, y0 + 152, FTheme.FAINT);
            qbtn(g, "rc1", "1.000", x + 14, y0 + 158, qw, cash >= 1, mx, my, () -> BankClient.repay(1000, false, true));
            qbtn(g, "rc2", "5.000", x + 14 + qw + 8, y0 + 158, qw, cash >= 1, mx, my, () -> BankClient.repay(5000, false, true));
            qbtn(g, "rc3", "Tot", x + 14 + 2 * (qw + 8), y0 + 158, qw, cash >= 1, mx, my, () -> BankClient.repay(0, true, true));
        } else {
            FTheme.caption(g, "OFERTE DE CREDIT", x + 14, y0 + 20, FTheme.FAINT);
            JsonArray offers = s.has("offers") && s.get("offers").isJsonArray() ? s.getAsJsonArray("offers") : new JsonArray();
            float oy = y0 + 28;
            for (JsonElement e : offers) {
                if (oy + 52 > y0 + ch) break;
                JsonObject o = e.getAsJsonObject();
                final int idx = JsonClientModule.integer(o, "i", 0);
                double minH = JsonClientModule.num(o, "minHours", 0);
                boolean ok = hours + 1e-9 >= minH;
                FUi.card(g, "of" + idx, x + 10, oy, lw - 20, 48, false, mx, my);
                tx(g, lei(JsonClientModule.num(o, "amount", 0)), x + 22, oy + 19, 9.6f, FTheme.TEXT);
                t(g, clip(JsonClientModule.integer(o, "days", 0) + " zile · dobândă " + JsonClientModule.num(o, "rate", 0) + "% · de returnat " + num(JsonClientModule.num(o, "total", 0)), lw - 130, 6.2f, false), x + 22, oy + 31, 6.2f, FTheme.MUTED);
                if (!ok) t(g, "necesită " + Math.round(minH) + " ore jucate", x + 22, oy + 42, 5.8f, FTheme.WARN);
                btn(g, "tk" + idx, ok ? "Împrumută" : "Blocat", null, x + lw - 10 - 74, oy + 10, 68, 28, FUi.PRIMARY, ok && !pending(), mx, my, () -> BankClient.take(idx));
                oy += 54;
            }
            if (offers.size() == 0) FUi.empty(g, "check", "Nicio ofertă", "Nu ai oferte de credit disponibile acum.", x + lw / 2f, y0 + ch / 2f - 10, lw - 40);
        }

        float rx = x + lw + 16;
        FTheme.panel(g, rx, y0, lw, ch, 6f, FTheme.BG2, FTheme.LINE);
        FTheme.caption(g, "ECONOMII", rx + 14, y0 + 20, FTheme.FAINT);
        tx(g, lei(savings), rx + 14, y0 + 46, 16f, FTheme.TEXT);
        t(g, "dobândă " + rate + "% / zi", rx + 14, y0 + 60, 7f, ORANGE);
        float qw2 = (lw - 28 - 16) / 3f;
        FTheme.caption(g, "PUNE LA ECONOMII", rx + 14, y0 + 90, FTheme.FAINT);
        qbtn(g, "si1", "1.000", rx + 14, y0 + 96, qw2, bank >= 1, mx, my, () -> BankClient.saveIn(1000, false));
        qbtn(g, "si2", "5.000", rx + 14 + qw2 + 8, y0 + 96, qw2, bank >= 1, mx, my, () -> BankClient.saveIn(5000, false));
        qbtn(g, "si3", "Tot", rx + 14 + 2 * (qw2 + 8), y0 + 96, qw2, bank >= 1, mx, my, () -> BankClient.saveIn(0, true));
        FTheme.caption(g, "RETRAGE ÎN CONT", rx + 14, y0 + 132, FTheme.FAINT);
        qbtn(g, "so1", "1.000", rx + 14, y0 + 138, qw2, savings >= 1, mx, my, () -> BankClient.saveOut(1000, false));
        qbtn(g, "so2", "5.000", rx + 14 + qw2 + 8, y0 + 138, qw2, savings >= 1, mx, my, () -> BankClient.saveOut(5000, false));
        qbtn(g, "so3", "Tot", rx + 14 + 2 * (qw2 + 8), y0 + 138, qw2, savings >= 1, mx, my, () -> BankClient.saveOut(0, true));
        FUi.message(g, FUi.MSG_INFO, "Economiile cresc în fiecare zi reală.", rx + 10, y0 + ch - 40, lw - 20);
    }

    @Override
    public void onClose() {
        FTheme.reset();
        Minecraft.getInstance().setScreen(null);
    }
}
