package ro.solarstudios.flowcity.taxi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Ecranul "TAXI": cheama un taxi, panoul soferului (duty + cereri) si clasament. Tema de iarna FlowCity (520x290, scalata). */
public final class TaxiScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int CX = 134;
    private static final int CW = W - CX - 14;
    private static final int TOP = 64;
    private static final int BOT = H - 12;

    private static final int T_CLIENT = 0;
    private static final int T_DRIVER = 1;
    private static final int T_TOP = 2;
    private static final String[] TABS = {"CHEAMĂ TAXI", "ȘOFER", "CLASAMENT"};

    private JsonObject d = new JsonObject();
    private int tab;
    private int scroll;
    private int ticks;
    private final TextFieldWidget note = field("Mesaj pentru șofer (opțional)", 80, null);
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    TaxiScreen(JsonObject data) {
        super("Taxi", W, H);
        update(data);
    }

    void update(JsonObject data) {
        d = data;
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
        if (ok && m.startsWith("Cerere trimisa")) note.setValue("");
    }

    private boolean flag(String k) {
        return JsonClientModule.bool(d, k, false);
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 20 == 0) TaxiClient.act("refresh");
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        if (tab == T_CLIENT && !flag("calling") && !flag("inRide") && !flag("duty")) l.add(note);
        return l;
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        callTaxi();
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

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static String money(long v) {
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private void placeField(TextFieldWidget f, float x, float y, float w, float h) {
        f.x = Math.round(offX + (x + 6) * scale);
        f.y = Math.round(offY + (y + (h - 8) / 2f) * scale);
        f.setWidth(Math.max(10, Math.round((w - 12) * scale)));
    }

    private void fieldBox(GuiGraphics ms, TextFieldWidget f, float x, float y, float w, float h) {
        Sk.slant(ms, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, f.isFocused() ? Sk.ACC : 0xFF22344D);
        placeField(f, x, y, w, h);
    }

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean filled, float lmx, float lmy, Runnable r) {
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, enabled, filled, hov);
        if (enabled) hitL(x, y, w, h, r);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }

    // ------------------------------------------------------------------ actiuni

    private void callTaxi() {
        if (flag("calling") || flag("inRide") || flag("duty")) return;
        JsonObject o = new JsonObject();
        o.addProperty("msg", note.getValue());
        TaxiClient.act("call", o);
    }

    private void toggleDuty() {
        JsonObject o = new JsonObject();
        o.addProperty("on", !flag("duty"));
        TaxiClient.act("duty", o);
    }

    private void accept(String client) {
        JsonObject o = new JsonObject();
        o.addProperty("client", client);
        TaxiClient.act("accept", o);
    }

    // ------------------------------------------------------------------ desen

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float open = easeOut((System.currentTimeMillis() - born) / 300f);
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        // campurile de text se deseneaza in coordonate de ecran, dupa restaurarea transformarii
        for (TextFieldWidget f : fields()) f.render(ms.pose(), mx, my, 0);
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        Gfx.text(ms, "TAXI", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "TAXIMETRIȘTI FLOWCITY", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        int pending = 0;
        for (JsonElement e : arr(d, "requests")) if (!JsonClientModule.bool(e.getAsJsonObject(), "taken", false)) pending++;
        for (int i = 0; i < TABS.length; i++) {
            float y = TOP + i * 33f;
            boolean sel = i == tab;
            boolean hov = Sk.in(lmx, lmy, 12, y, 112, 29);
            Sk.slant(ms, 12, y, 112, 29, 6f, sel ? 0xFF1B2E47 : hov ? Sk.ROW_HOT : Sk.ROW);
            if (sel) Gfx.rrect(ms, 12, y + 4, 3, 21, 0, Sk.ACC);
            Gfx.text(ms, clip(TABS[i], 7.4f, true, 84), 24, y + 18, 7.4f, sel ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            if (i == T_DRIVER && flag("duty") && pending > 0) {
                Gfx.rrect(ms, 106, y + 8, 14, 12, 0, Sk.ACC);
                Gfx.text(ms, String.valueOf(Math.min(99, pending)), 113, y + 17.5f, 6.4f, 0xFF061426, 1, true, 0f);
            }
            final int idx = i;
            hitL(12, y, 112, 29, () -> {
                tab = idx;
                scroll = 0;
                focus(note, false);
            });
        }
        Gfx.text(ms, TABS[Math.min(tab, TABS.length - 1)], CX, 56, 7f, Sk.FAINT, 0, false, 1.4f);
        switch (tab) {
            case T_CLIENT: client(ms, lmx, lmy); break;
            case T_DRIVER: driver(ms, lmx, lmy); break;
            default: top(ms);
        }

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, clip(msg, 8f, false, W - 90), W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    private void tariff(GuiGraphics ms) {
        String t = "Tarif: pornire " + Math.round(JsonClientModule.num(d, "startFee", 0)) + " lei  ·  " + Math.round(JsonClientModule.num(d, "perKm", 0))
            + " lei/km  ·  " + Math.round(JsonClientModule.num(d, "perMin", 0)) + " lei/min așteptare  ·  minim " + Math.round(JsonClientModule.num(d, "minFare", 0)) + " lei";
        Gfx.text(ms, clip(t, 6.4f, false, CW - 8), CX, BOT - 2, 6.4f, Sk.MUTED, 0);
    }

    // ------------------------------------------------------------------ CLIENT

    private void client(GuiGraphics ms, float lmx, float lmy) {
        boolean calling = flag("calling");
        boolean inRide = flag("inRide");
        boolean duty = flag("duty");
        float y = TOP;
        float cw = CW - 8;
        Sk.slant(ms, CX, y, cw, 64, 8f, Sk.ROW);
        if (inRide) {
            Gfx.rrect(ms, CX + 12, y + 12, 3, 40, 0, Sk.GOOD);
            Gfx.text(ms, "EȘTI ÎNTR-O CURSĂ", CX + 24, y + 26, 10, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "Prețul curent îl vezi pe taximetrul din HUD.", CX + 24, y + 42, 7, Sk.MUTED, 0);
            button(ms, "OPREȘTE CURSA", CX + cw - 124, y + 20, 112, 24, true, true, lmx, lmy, () -> TaxiClient.act("stop"));
        } else if (calling) {
            Gfx.rrect(ms, CX + 12, y + 12, 3, 40, 0, Sk.WARN);
            Gfx.text(ms, "CERERE ACTIVĂ  ·  " + JsonClientModule.integer(d, "callAge", 0) + " s", CX + 24, y + 26, 10, Sk.WHITE, 0, true, 0f);
            String dn = JsonClientModule.str(d, "driverName", "");
            int dd = JsonClientModule.integer(d, "driverDist", -1);
            String sub = dn.isEmpty() ? "Aștept să accepte un șofer..." : "Taxi-ul vine: " + dn + (dd >= 0 ? " la " + dd + " m" : "");
            Gfx.text(ms, clip(sub, 7f, false, cw - 170), CX + 24, y + 42, 7, dn.isEmpty() ? Sk.MUTED : Sk.GOOD, 0);
            button(ms, "ANULEAZĂ", CX + cw - 124, y + 20, 112, 24, true, false, lmx, lmy, () -> TaxiClient.act("cancel"));
        } else if (duty) {
            Gfx.rrect(ms, CX + 12, y + 12, 3, 40, 0, Sk.ACC);
            Gfx.text(ms, "EȘTI LA DATORIE CA ȘOFER", CX + 24, y + 26, 10, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "Ieși de la datorie (tab ȘOFER) ca să poți chema un taxi.", CX + 24, y + 42, 7, Sk.MUTED, 0);
        } else {
            fieldBox(ms, note, CX + 12, y + 8, cw - 24, 22);
            boolean can = JsonClientModule.integer(d, "onlineDrivers", 0) > 0;
            button(ms, "CHEAMĂ TAXI", CX + 12, y + 36, 140, 22, can, true, lmx, lmy, this::callTaxi);
            Gfx.text(ms, can ? "Cererea ajunge la toți șoferii liberi." : "Niciun taximetrist la datorie acum.", CX + 164, y + 51, 7, can ? Sk.MUTED : Sk.WARN, 0);
        }

        Gfx.text(ms, "ȘOFERI LA DATORIE  ·  " + JsonClientModule.integer(d, "onlineDrivers", 0), CX, y + 84, 7f, Sk.FAINT, 0, false, 1.2f);
        JsonArray ds = arr(d, "drivers");
        float ly = y + 92;
        int visible = Math.max(1, (int) ((BOT - 16 - ly) / 30f));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, ds.size() - visible)));
        if (ds.size() == 0) {
            Gfx.text(ms, "Nu e niciun alt șofer la datorie.", CX + cw / 2f, ly + 28, 8, Sk.MUTED, 1);
        }
        for (int i = scroll; i < Math.min(ds.size(), scroll + visible); i++) {
            JsonObject e = ds.get(i).getAsJsonObject();
            boolean busy = JsonClientModule.bool(e, "busy", false);
            int dist = JsonClientModule.integer(e, "dist", -1);
            Sk.slant(ms, CX, ly, cw, 25, 6f, Sk.ROW);
            Gfx.rrect(ms, CX + 10, ly + 6, 3, 13, 0, busy ? Sk.WARN : Sk.GOOD);
            Gfx.text(ms, clip(JsonClientModule.str(e, "name", ""), 8.6f, true, 150), CX + 22, ly + 16, 8.6f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, dist >= 0 ? dist + " m" : "altă lume", CX + cw - 96, ly + 16, 7.4f, Sk.MUTED, 2);
            Gfx.text(ms, busy ? "OCUPAT" : "LIBER", CX + cw - 12, ly + 16, 7f, busy ? Sk.WARN : Sk.GOOD, 2, true, 0.6f);
            ly += 29;
        }
        tariff(ms);
    }

    // ------------------------------------------------------------------ SOFER

    private void driver(GuiGraphics ms, float lmx, float lmy) {
        float cw = CW - 8;
        float y = TOP;
        boolean duty = flag("duty");
        boolean inRide = flag("inRide");
        boolean pickup = flag("pickup");
        Sk.slant(ms, CX, y, cw, 40, 8f, Sk.ROW);
        if (!flag("canDrive") && !duty) {
            Gfx.rrect(ms, CX + 12, y + 8, 3, 24, 0, Sk.BAD);
            Gfx.text(ms, "NU AI VOIE SĂ LUCREZI CA TAXIMETRIST", CX + 24, y + 19, 9, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "Cere permisiunea sau permisul de conducere (cat. B).", CX + 24, y + 31, 6.8f, Sk.MUTED, 0);
            return;
        }
        Gfx.rrect(ms, CX + 12, y + 8, 3, 24, 0, duty ? Sk.GOOD : Sk.FAINT);
        Gfx.text(ms, duty ? "LA DATORIE" : "ÎN AFARA SERVICIULUI", CX + 24, y + 19, 10, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "Curse: " + JsonClientModule.integer(d, "myRides", 0) + "  ·  Câștig: " + money(Math.round(JsonClientModule.num(d, "myEarned", 0)))
            + " lei  ·  Comision companie " + Math.round(JsonClientModule.num(d, "cut", 0)) + "%", CX + 24, y + 32, 6.6f, Sk.MUTED, 0);
        button(ms, duty ? "IEȘI DE LA DATORIE" : "INTRĂ LA DATORIE", CX + cw - 160, y + 8, 148, 24, true, !duty, lmx, lmy, this::toggleDuty);
        if (!duty) return;

        y += 48;
        if (inRide) {
            button(ms, "OPREȘTE CURSA", CX, y, 132, 24, true, true, lmx, lmy, () -> TaxiClient.act("stop"));
        } else {
            button(ms, "PORNEȘTE CURSA", CX, y, 132, 24, true, true, lmx, lmy, () -> TaxiClient.act("start"));
        }
        if (pickup && !inRide) button(ms, "RENUNȚĂ LA CLIENT", CX + 140, y, 132, 24, true, false, lmx, lmy, () -> TaxiClient.act("cancel"));
        Gfx.text(ms, "Clientul trebuie să fie în vehicul sau la ≤ 4 blocuri.", CX + cw, y + 15, 6.6f, Sk.FAINT, 2);

        Gfx.text(ms, "CERERI DE TAXI", CX, y + 40, 7f, Sk.FAINT, 0, false, 1.2f);
        JsonArray rs = arr(d, "requests");
        float ly = y + 48;
        int visible = Math.max(1, (int) ((BOT - 4 - ly) / 32f));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rs.size() - visible)));
        if (rs.size() == 0) {
            Gfx.text(ms, "Nicio cerere activă acum.", CX + cw / 2f, ly + 22, 8, Sk.MUTED, 1);
        }
        for (int i = scroll; i < Math.min(rs.size(), scroll + visible); i++) {
            JsonObject e = rs.get(i).getAsJsonObject();
            boolean taken = JsonClientModule.bool(e, "taken", false);
            boolean mine = JsonClientModule.bool(e, "mine", false);
            int dist = JsonClientModule.integer(e, "dist", -1);
            String cm = JsonClientModule.str(e, "msg", "");
            Sk.slant(ms, CX, ly, cw, 27, 6f, Sk.ROW);
            Gfx.rrect(ms, CX + 10, ly + 6, 3, 15, 0, mine ? Sk.GOOD : taken ? Sk.FAINT : Sk.ACC);
            Gfx.text(ms, clip(JsonClientModule.str(e, "name", ""), 8.6f, true, 110), CX + 22, ly + 12, 8.6f, Sk.WHITE, 0, true, 0f);
            String sub = (dist >= 0 ? dist + " m" : "altă lume") + (cm.isEmpty() ? "" : "  ·  " + cm);
            Gfx.text(ms, clip(sub, 6.4f, false, cw - 170), CX + 22, ly + 22, 6.4f, Sk.MUTED, 0);
            final String cl = JsonClientModule.str(e, "client", "");
            if (mine) {
                Gfx.text(ms, "LA TINE", CX + cw - 12, ly + 17, 7.4f, Sk.GOOD, 2, true, 0.6f);
            } else if (taken) {
                Gfx.text(ms, "PRELUAT", CX + cw - 12, ly + 17, 7.4f, Sk.FAINT, 2, true, 0.6f);
            } else {
                button(ms, "ACCEPTĂ", CX + cw - 92, ly + 2, 84, 23, !inRide && !pickup, true, lmx, lmy, () -> accept(cl));
            }
            ly += 31;
        }
    }

    // ------------------------------------------------------------------ CLASAMENT

    private void top(GuiGraphics ms) {
        float cw = CW - 8;
        JsonArray rows = arr(d, "top");
        if (rows.size() == 0) {
            Gfx.text(ms, "Nu există încă nicio cursă înregistrată.", CX + cw / 2f, H / 2f + 4, 10, Sk.WHITE, 1, true, 0f);
            return;
        }
        Gfx.text(ms, "CURSE", CX + cw - 100, TOP + 4, 6.4f, Sk.FAINT, 2, false, 1f);
        Gfx.text(ms, "CÂȘTIG", CX + cw - 12, TOP + 4, 6.4f, Sk.FAINT, 2, false, 1f);
        float y = TOP + 10;
        for (int i = 0; i < rows.size() && i < 5; i++) {
            JsonObject e = rows.get(i).getAsJsonObject();
            Sk.slant(ms, CX, y, cw, 30, 6f, Sk.ROW);
            Gfx.rrect(ms, CX + 10, y + 7, 3, 16, 0, i == 0 ? Sk.WARN : Sk.ACC);
            Gfx.text(ms, "#" + (i + 1), CX + 24, y + 20, 9, i == 0 ? Sk.WARN : Sk.MUTED, 0, true, 0f);
            Gfx.text(ms, clip(JsonClientModule.str(e, "name", ""), 9f, true, 190), CX + 52, y + 20, 9f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, String.valueOf(JsonClientModule.integer(e, "rides", 0)), CX + cw - 100, y + 20, 8.6f, Sk.MUTED, 2, true, 0f);
            Gfx.text(ms, money((long) JsonClientModule.num(e, "earned", 0)) + " lei", CX + cw - 12, y + 20, 9f, Sk.WHITE, 2, true, 0f);
            y += 34;
        }
        Gfx.text(ms, "Tu: " + JsonClientModule.integer(d, "myRides", 0) + " curse  ·  " + money(Math.round(JsonClientModule.num(d, "myEarned", 0))) + " lei",
            CX, BOT - 2, 7f, Sk.MUTED, 0);
    }
}
