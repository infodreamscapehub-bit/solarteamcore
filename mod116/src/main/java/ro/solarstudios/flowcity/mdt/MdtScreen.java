package ro.solarstudios.flowcity.mdt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.math.vector.Vector3f;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Terminalul MDT de politie, desenat ca o tableta: rama, bara de stare, meniu lateral, dosare pe cetateni,
 * cautare dupa numar de inmatriculare, dispecerat (unitati + backup) si echipa.
 */
public final class MdtScreen extends FlowScreen {
    private static final int BG = 0xFF0C0D10;
    private static final int SIDE = 0xFF0A0B0E;
    private static final int CARD = 0xFF15171C;
    private static final int CARD2 = 0xFF1E2026;
    private static final int RULE = 0xFF24262C;
    private static final int INK = Sk.WHITE;
    private static final int DIM = Sk.MUTED;
    private static final int FAINT = Sk.FAINT;
    private static final int BLUE = Sk.ACC;
    private static final int AMBER = Sk.WARN;
    private static final int RED = Sk.BAD;
    private static final int GREEN = Sk.GOOD;
    private static final int DARK = 0xFF1A0B05;
    private static final int RAIL = 76;
    private static final int TOP = 20;
    private static final int LIST = 152;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] TABS = {"Cetateni", "Urmariti", "Vehicule", "Dispecerat", "Echipa"};
    private static final String[] SUBS = {"Cazier", "Amenzi", "Vehicule", "Locuinte"};
    private static final String[] SUB_KEYS = {"records", "fines", "vehicles", "props"};
    private static final String[] UNIT_TYPES = {"PATRULA", "RUTIERA", "RAZIE"};

    private int tab;
    private int sub;
    private int sel = -1;
    private String selUuid = "";
    private int listScroll;
    private int rowScroll;
    private int wantedScroll;
    private int unitScroll;
    private int backupScroll;
    private int officerScroll;
    private int selOfficer = -1;
    private boolean rankConfig;
    private int cfgRank = 1;
    private String unitType = "PATRULA";

    private int modal;
    private int newRank = 1;
    private TextFieldWidget pendingFocus;
    private boolean searchDirty;
    private long searchAt;
    private long openedAt = System.currentTimeMillis();

    private final TextFieldWidget search = field("Cauta cetatean...", 24, s -> {
        searchDirty = true;
        searchAt = System.currentTimeMillis() + 250;
    });
    private final TextFieldWidget plateField = field("AB-12-XYZ", 12, null);
    private final TextFieldWidget unitName = field("Nume unitate", 20, null);
    private final TextFieldWidget mRec = field("Descrie fapta", 200, null);
    private final TextFieldWidget mAmt = field("ex. 500", 7, null);
    private final TextFieldWidget mFineReason = field("Motivul amenzii", 150, null);
    private final TextFieldWidget mWanted = field("Motivul urmaririi", 150, null);
    private final TextFieldWidget mModel = field("ex. Dacia Logan", 30, null);
    private final TextFieldWidget mPlate = field("ex. B-123-ABC", 12, null);
    private final TextFieldWidget mAddr = field("Strada, numar", 70, null);
    private final TextFieldWidget mOff = field("Nume jucator online", 16, null);
    private final TextFieldWidget rkName = field("Nume grad", 24, null);
    private final TextFieldWidget rkUp = field("Comanda la promovare (%player%)", 120, null);
    private final TextFieldWidget rkDown = field("Comanda la retrogradare (%player%)", 120, null);

    public MdtScreen() {
        super("MDT", 540, 310);
        plateField.setTextColor(0xFF12161C);
    }

    @Override
    protected void init() {
        MdtState.citizen = null;
        MdtState.plate = null;
        MdtState.found = new JsonArray();
    }

    @Override
    public void onClose() {
        MdtClient.act("close");
        super.onClose();
    }

    @Override
    protected boolean framed() {
        return false;
    }

    // ------------------------------------------------------------------ stil joc (butoane, taburi, campuri)

    private void title(GuiGraphics ms, String text, int x, int y) {
        Gfx.text(ms, text.toUpperCase(), x, y + 8, 9f, INK, 0, true, 0.4f);
    }

    @Override
    protected void button(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                          boolean enabled, int color, Runnable action) {
        boolean hover = enabled && Ui.in(mx, my, x, y, w, h);
        boolean danger = color == 0xFF6B2F2B || color == 0xFF6A2A26 || color == 0xFF7A2A26 || color == 0xFF3A2328 || color == 0xFF4A1E22;
        boolean good = color == 0xFF1F4D38;
        boolean primary = color == 0xFF24467A;
        int bg;
        int fg;
        if (!enabled) {
            bg = 0xFF1C1E23;
            fg = FAINT;
        } else if (primary) {
            bg = hover ? 0xFFFF7448 : Sk.ACC;
            fg = DARK;
        } else if (good) {
            bg = Gfx.mixArgb(Sk.GOOD, 0xFFFFFFFF, hover ? 0.18f : 0f);
            fg = DARK;
        } else if (danger) {
            bg = Gfx.mixArgb(Sk.BAD, 0xFFFFFFFF, hover ? 0.18f : 0f);
            fg = DARK;
        } else {
            bg = hover ? 0xFF2C2F36 : 0xFF23252B;
            fg = hover ? INK : 0xFFC9CBD0;
        }
        Sk.slant(ms, x, y, w, h, Math.min(5f, h / 3f), bg);
        Ui.textCenter(ms, Ui.clip(label, w - 10), x + w / 2 + 1, y + (h - 8) / 2, fg);
        if (enabled) hit(x, y, w, h, action);
    }

    @Override
    protected int chip(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                       boolean selected, int color, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        Sk.slant(ms, x, y, w, h, Math.min(4f, h / 3f), selected ? Sk.ACC : (hover ? Sk.ROW_HOT : Sk.ROW));
        Ui.textCenter(ms, label, x + w / 2 + 1, y + (h - 8) / 2, selected ? DARK : (hover ? INK : DIM));
        hit(x, y, w, h, action);
        return w;
    }

    @Override
    protected void drawField(GuiGraphics ms, TextFieldWidget f, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(ms, x, y, w, h, CARD);
        Ui.rect(ms, x, y + h - 1, w, 1, f.isFocused() ? Sk.ACC : 0xFF2C2F36);
        f.x = x + 5;
        f.y = y + (h - 8) / 2;
        f.setWidth(w - 10);
        f.render(ms.pose(), mx, my, 0);
    }

    // ------------------------------------------------------------------ JSON helpers

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String s(JsonObject o, String k) {
        return JsonClientModule.str(o, k, "");
    }

    private static int i(JsonObject o, String k) {
        return JsonClientModule.integer(o, k, 0);
    }

    private static boolean b(JsonObject o, String k) {
        return JsonClientModule.bool(o, k, false);
    }

    private static List<JsonObject> list(JsonArray a) {
        List<JsonObject> l = new ArrayList<>();
        for (JsonElement e : a) if (e.isJsonObject()) l.add(e.getAsJsonObject());
        return l;
    }

    private JsonObject me() {
        return obj(MdtState.state, "me");
    }

    private static int clampScroll(int v, int total, int visible) {
        return Math.max(0, Math.min(v, Math.max(0, total - visible)));
    }

    // ------------------------------------------------------------------ campuri / taste

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        switch (modal) {
            case 1:
                l.add(mRec);
                break;
            case 2:
                l.add(mAmt);
                l.add(mFineReason);
                break;
            case 3:
                l.add(mWanted);
                break;
            case 4:
                l.add(mModel);
                l.add(mPlate);
                break;
            case 5:
                l.add(mAddr);
                break;
            case 6:
                l.add(mOff);
                break;
            default:
                break;
        }
        if (modal != 0) return l;
        if (tab == 0) l.add(search);
        else if (tab == 2) l.add(plateField);
        else if (tab == 3) l.add(unitName);
        else if (tab == 4 && rankConfig) {
            l.add(rkName);
            l.add(rkUp);
            l.add(rkDown);
        }
        return l;
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        if (modal != 0) {
            List<TextFieldWidget> fs = fields();
            int at = fs.indexOf(f);
            if (at >= 0 && at < fs.size() - 1) focus(fs.get(at + 1), true);
            else confirmModal();
        } else if (f == plateField) {
            lookupPlate();
        } else if (f == unitName) {
            createUnit();
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256 && modal != 0) { // ESC
            boolean typing = false;
            for (TextFieldWidget f : fields()) typing |= f.isFocused();
            if (!typing) {
                modal = 0;
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void tick() {
        super.tick();
        if (searchDirty && System.currentTimeMillis() >= searchAt) {
            searchDirty = false;
            String q = search.getValue().trim();
            if (!q.isEmpty()) MdtClient.act("find", "q", q);
        }
    }

    private void unfocusAll() {
        for (TextFieldWidget f : fields()) focus(f, false);
    }

    private void setTab(int t) {
        unfocusAll();
        tab = t;
        modal = 0;
        rankConfig = false;
    }

    private void selectCitizen(String uuid) {
        selUuid = uuid;
        sel = -1;
        rowScroll = 0;
        MdtState.citizen = null;
        MdtClient.act("select", "u", uuid);
    }

    private JsonObject dossier() {
        JsonObject c = MdtState.citizen;
        return c != null && s(c, "u").equals(selUuid) ? c : null;
    }

    private void openModal(int kind) {
        unfocusAll();
        modal = kind;
        TextFieldWidget[] all = {mRec, mAmt, mFineReason, mWanted, mModel, mPlate, mAddr, mOff};
        for (TextFieldWidget f : all) f.setValue("");
        newRank = 1;
        pendingFocus = fields().isEmpty() ? null : fields().get(0);
    }

    private void confirmModal() {
        switch (modal) {
            case 1:
                if (mRec.getValue().trim().isEmpty()) {
                    toast("Scrie fapta.", true);
                    return;
                }
                MdtClient.act("record_add", "u", selUuid, "text", mRec.getValue().trim());
                break;
            case 2: {
                int amt;
                try {
                    amt = Integer.parseInt(mAmt.getValue().trim());
                } catch (NumberFormatException e) {
                    amt = 0;
                }
                if (amt <= 0) {
                    toast("Suma trebuie sa fie un numar pozitiv.", true);
                    return;
                }
                MdtClient.act("fine_add", "u", selUuid, "amount", amt, "reason", mFineReason.getValue().trim());
                break;
            }
            case 3:
                if (mWanted.getValue().trim().isEmpty()) {
                    toast("Scrie motivul.", true);
                    return;
                }
                MdtClient.act("wanted", "u", selUuid, "on", true, "reason", mWanted.getValue().trim());
                break;
            case 4:
                if (mModel.getValue().trim().isEmpty() || mPlate.getValue().trim().length() < 3) {
                    toast("Completeaza modelul si numarul.", true);
                    return;
                }
                MdtClient.act("veh_add", "u", selUuid, "model", mModel.getValue().trim(), "plate", mPlate.getValue().trim());
                break;
            case 5:
                if (mAddr.getValue().trim().isEmpty()) {
                    toast("Scrie adresa.", true);
                    return;
                }
                MdtClient.act("prop_add", "u", selUuid, "addr", mAddr.getValue().trim());
                break;
            case 6:
                if (mOff.getValue().trim().isEmpty()) {
                    toast("Scrie numele jucatorului.", true);
                    return;
                }
                MdtClient.act("officer_add", "name", mOff.getValue().trim(), "rank", newRank);
                break;
            default:
                break;
        }
        modal = 0;
    }

    private void lookupPlate() {
        String p = plateField.getValue().trim();
        if (p.length() < 3) {
            toast("Scrie un numar de inmatriculare.", true);
            return;
        }
        MdtState.plate = null;
        MdtClient.act("plate", "plate", p);
    }

    private void createUnit() {
        String n = unitName.getValue().trim();
        if (n.isEmpty()) {
            toast("Da un nume unitatii.", true);
            return;
        }
        MdtClient.act("unit_create", "name", n, "type", unitType);
        unitName.setValue("");
    }

    // ------------------------------------------------------------------ desen general

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float open = Math.min(1f, (System.currentTimeMillis() - openedAt) / 260f);
        Sk.background(ms, 0, 0, width, height, open);
        Ui.rect(ms, px, py, pw, ph, 0xEE0A0B0E);
        Ui.rect(ms, px - 3, py, 3, ph, Sk.ACC);
        int cx = px + RAIL;
        int cy = py + TOP;
        int cw = pw - RAIL;
        int ch = ph - TOP;
        switch (tab) {
            case 0:
                drawCitizens(ms, mx, my, cx, cy, cw, ch);
                break;
            case 1:
                drawWanted(ms, mx, my, cx, cy, cw, ch);
                break;
            case 2:
                drawPlates(ms, mx, my, cx, cy, cw, ch);
                break;
            case 3:
                drawDispatch(ms, mx, my, cx, cy, cw, ch);
                break;
            default:
                drawTeam(ms, mx, my, cx, cy, cw, ch);
                break;
        }
        drawRail(ms, mx, my);
        drawTop(ms, mx, my);
        if (modal != 0) drawModal(ms, mx, my);
    }

    private void drawTop(GuiGraphics ms, int mx, int my) {
        Ui.rect(ms, px, py, pw, TOP, SIDE);
        Ui.rect(ms, px, py + TOP - 1, pw, 1, RULE);
        Sk.slant(ms, px + 8, py + 4, 11, 12, 4f, Sk.ACC);
        Gfx.text(ms, "POLIȚIA FLOWCITY", px + 26, py + 14, 8.6f, INK, 0, true, 0.4f);
        Ui.text(ms, "MDT", px + 26 + Math.round(Gfx.width("POLIȚIA FLOWCITY", 8.6f, true)) + 14, py + 6, FAINT);

        int xr = px + pw - 8;
        button(ms, "x", xr - 14, py + 3, 14, 14, mx, my, true, 0xFF3A2328, this::onClose);
        xr -= 22;
        Ui.textRight(ms, LocalTime.now().format(CLOCK), xr, py + 6, DIM);
        xr -= Ui.width("00:00") + 10;
        for (int k = 0; k < 4; k++) Ui.rect(ms, xr - 14 + k * 4, py + 14 - (k + 1) * 2 - 1, 3, (k + 1) * 2 + 1, k < 3 ? GREEN : FAINT);
        xr -= 22;
        JsonObject m = me();
        String rank = s(m, "rankName") + "  " + s(m, "name");
        Ui.textRight(ms, rank, xr, py + 6, DIM);
        xr -= Ui.width(rank) + 12;
        String unitId = s(m, "unit");
        JsonObject mine = null;
        for (JsonObject u : list(arr(MdtState.state, "units"))) if (s(u, "id").equals(unitId)) mine = u;
        if (mine != null) {
            boolean free = "Available".equals(s(mine, "status"));
            String t = s(mine, "name") + " " + (free ? "disponibil" : "ocupat");
            Ui.textRight(ms, t, xr, py + 6, free ? GREEN : AMBER);
        } else {
            Ui.textRight(ms, "fara unitate", xr, py + 6, FAINT);
        }
    }

    private void drawRail(GuiGraphics ms, int mx, int my) {
        int y0 = py + TOP;
        Ui.rect(ms, px, y0, RAIL, ph - TOP, SIDE);
        Ui.rect(ms, px + RAIL - 1, y0, 1, ph - TOP, RULE);
        int wantedN = arr(MdtState.state, "wanted").size();
        int bkN = arr(MdtState.state, "backups").size();
        for (int k = 0; k < TABS.length; k++) {
            int y = y0 + 8 + k * 27;
            boolean on = tab == k;
            boolean hover = Ui.in(mx, my, px, y, RAIL - 1, 25);
            if (on) Sk.slant(ms, px + 5, y, RAIL - 10, 25, 6f, Sk.ACC);
            else if (hover) Sk.slant(ms, px + 5, y, RAIL - 10, 25, 6f, Sk.ROW_HOT);
            glyph(ms, k, px + 13, y + 8, on ? DARK : DIM);
            Ui.smallCaps(ms, TABS[k], px + 27, y + 9, on ? DARK : DIM);
            int badge = k == 1 ? wantedN : k == 3 ? bkN : 0;
            if (badge > 0) {
                String t = String.valueOf(badge);
                int w = Ui.width(t) + 6;
                Ui.rect(ms, px + RAIL - w - 3, y + 7, w, 11, RED);
                Ui.text(ms, t, px + RAIL - w, y + 9, DARK);
            }
            final int kk = k;
            hit(px, y, RAIL - 1, 25, () -> setTab(kk));
        }
        int by = py + ph - 52;
        Ui.text(ms, "BK: Num 1-3", px + 9, by, FAINT);
        int pw2 = RAIL - 14;
        boolean hover = Ui.in(mx, my, px + 7, by + 12, pw2, 22);
        Sk.slant(ms, px + 7, by + 12, pw2, 22, 6f, hover ? 0xFFFF7A6E : Sk.BAD);
        Ui.smallCaps(ms, "Panica", px + 7 + (pw2 - Ui.smallCapsWidth("Panica")) / 2 + 2, by + 20, DARK);
        hit(px + 7, by + 12, pw2, 22, () -> MdtClient.act("panic"));
    }

    /** Pictograme simple (10x10) desenate din dreptunghiuri. */
    private void glyph(GuiGraphics ms, int kind, int x, int y, int c) {
        switch (kind) {
            case 0:
                Ui.rect(ms, x + 3, y, 4, 4, c);
                Ui.rect(ms, x + 1, y + 5, 8, 5, c);
                break;
            case 1:
                Ui.rect(ms, x + 4, y, 2, 10, c);
                Ui.rect(ms, x, y + 4, 10, 2, c);
                break;
            case 2:
                Ui.rect(ms, x, y + 4, 10, 4, c);
                Ui.rect(ms, x + 2, y + 1, 6, 3, c);
                Ui.rect(ms, x + 1, y + 8, 2, 2, c);
                Ui.rect(ms, x + 7, y + 8, 2, 2, c);
                break;
            case 3:
                Ui.rect(ms, x, y + 8, 10, 2, c);
                Ui.rect(ms, x + 1, y + 5, 2, 3, c);
                Ui.rect(ms, x + 4, y + 1, 2, 7, c);
                Ui.rect(ms, x + 7, y + 3, 2, 5, c);
                break;
            default:
                Ui.rect(ms, x, y + 2, 3, 3, c);
                Ui.rect(ms, x - 1, y + 6, 5, 4, c);
                Ui.rect(ms, x + 6, y + 2, 3, 3, c);
                Ui.rect(ms, x + 5, y + 6, 5, 4, c);
                break;
        }
    }

    /** Eticheta aliniata la dreapta; intoarce marginea stanga. */
    private int pill(GuiGraphics ms, String label, int xRight, int y, int color) {
        int w = Ui.width(label) + 10;
        Sk.slant(ms, xRight - w, y, w, 11, 3f, Theme.alpha(color, 0x40));
        Ui.text(ms, label, xRight - w + 5, y + 2, color);
        return xRight - w - 4;
    }

    private static String ago(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        return (seconds / 3600) + "h";
    }

    private static String shortId(String uuid) {
        String t = uuid.replace("-", "");
        return t.length() >= 6 ? t.substring(0, 6).toUpperCase() : t.toUpperCase();
    }

    // ------------------------------------------------------------------ tab 0: cetateni

    private void drawCitizens(GuiGraphics ms, int mx, int my, int cx, int cy, int cw, int ch) {
        // coloana de cautare
        Ui.rect(ms, cx + LIST, cy, 1, ch, RULE);
        drawField(ms, search, cx + 8, cy + 8, LIST - 16, 16, mx, my);
        boolean searching = !search.getValue().trim().isEmpty();
        Ui.smallCaps(ms, searching ? "Rezultate" : "Online acum", cx + 9, cy + 30, FAINT);
        List<JsonObject> items = searching ? list(MdtState.found) : list(arr(MdtState.state, "online"));
        int top = cy + 42;
        int rowH = 24;
        int visible = Math.max(1, (ch - 46) / rowH);
        listScroll = clampScroll(listScroll, items.size(), visible);
        if (items.isEmpty()) Ui.text(ms, searching ? "Niciun rezultat." : "Nimeni online.", cx + 9, top + 4, FAINT);
        for (int k = listScroll; k < Math.min(items.size(), listScroll + visible); k++) {
            JsonObject c = items.get(k);
            int y = top + (k - listScroll) * rowH;
            String u = s(c, "u");
            boolean on = u.equals(selUuid);
            boolean hover = Ui.in(mx, my, cx + 1, y, LIST - 1, rowH);
            if (on) Sk.slant(ms, cx + 4, y + 1, LIST - 8, rowH - 2, 5f, Sk.ACC);
            else if (hover) Sk.slant(ms, cx + 4, y + 1, LIST - 8, rowH - 2, 5f, Sk.ROW_HOT);
            Ui.rect(ms, cx + 11, y + 9, 4, 4, b(c, "on") ? (on ? DARK : GREEN) : 0xFF3A4455);
            Ui.text(ms, Ui.clip(s(c, "n"), LIST - 70), cx + 20, y + 8, on ? DARK : INK);
            if (b(c, "w")) pill(ms, "MANDAT", cx + LIST - 5, y + 6, RED);
            else if (i(c, "f") > 0) Ui.textRight(ms, i(c, "f") + " am.", cx + LIST - 6, y + 8, AMBER);
            
            hit(cx + 1, y, LIST - 1, rowH, () -> selectCitizen(u));
        }
        drawDossier(ms, mx, my, cx + LIST + 1, cy, cw - LIST - 1, ch);
    }

    private void drawDossier(GuiGraphics ms, int mx, int my, int x0, int cy, int w0, int ch) {
        int dx = x0 + 10;
        int dw = w0 - 20;
        if (selUuid.isEmpty()) {
            Ui.textCenter(ms, "Alege un cetatean din lista", x0 + w0 / 2, cy + ch / 2 - 10, DIM);
            Ui.textCenter(ms, "sau scrie un nume la cautare.", x0 + w0 / 2, cy + ch / 2 + 2, FAINT);
            return;
        }
        JsonObject d = dossier();
        if (d == null) {
            Ui.textCenter(ms, "Se incarca dosarul...", x0 + w0 / 2, cy + ch / 2 - 4, FAINT);
            return;
        }
        boolean wanted = b(d, "w");
        // antet de dosar
        Gfx.text(ms, Ui.clip(s(d, "n"), dw - 90), dx, cy + 22, 14, INK, 0, true, 0f);
        Ui.text(ms, "Fisa  #" + shortId(s(d, "u")) + "   " + (b(d, "on") ? "online" : "offline"), dx, cy + 27, FAINT);
        if (wanted) {
            ms.pose().pushPose();
            ms.pose().translate(x0 + w0 - 74, cy + 12, 0);
            ms.pose().mulPose(Vector3f.ZP.rotationDegrees(-6f));
            Ui.rect(ms, 0, 0, 64, 16, RED);
            Ui.textCenter(ms, "URMARIT", 32, 4, DARK);
            ms.pose().popPose();
        } else {
            pill(ms, "FARA MANDAT", x0 + w0 - 10, cy + 12, GREEN);
        }
        int y = cy + 40;
        if (wanted) {
            List<String> lines = Wrap.lines("Motiv: " + s(d, "reason") + "  (emis de " + s(d, "by") + ")", dw);
            for (int k = 0; k < Math.min(2, lines.size()); k++) Ui.text(ms, lines.get(k), dx, y + k * 10, 0xFFE6968F);
            y += Math.min(2, lines.size()) * 10 + 4;
        }
        final boolean w = wanted;
        button(ms, wanted ? "Anuleaza mandat" : "Emite mandat", dx, y, 104, 14, mx, my, true,
            wanted ? 0xFF1F4D38 : 0xFF6A2A26, () -> {
                if (w) MdtClient.act("wanted", "u", selUuid, "on", false, "reason", "");
                else openModal(3);
            });
        y += 22;
        // sub-taburi
        int sx = dx;
        for (int k = 0; k < SUBS.length; k++) {
            int n = arr(d, SUB_KEYS[k]).size();
            String label = SUBS[k];
            String cnt = n > 0 ? " " + n : "";
            int tw = Ui.smallCapsWidth(label) + Ui.width(cnt);
            boolean on = sub == k;
            Ui.smallCaps(ms, label, sx, y, on ? INK : DIM);
            if (!cnt.isEmpty()) Ui.text(ms, cnt, sx + Ui.smallCapsWidth(label), y, on ? Sk.ACC : FAINT);
            if (on) Ui.rect(ms, sx, y + 11, tw, 2, Sk.ACC);
            final int kk = k;
            hit(sx - 3, y - 3, tw + 6, 18, () -> {
                sub = kk;
                sel = -1;
                rowScroll = 0;
            });
            sx += tw + 18;
        }
        Ui.rect(ms, dx, y + 13, dw, 1, RULE);
        y += 18;
        int footH = 24;
        int bottom = cy + ch - footH;
        int rowH = 26;
        List<JsonObject> rows = list(arr(d, SUB_KEYS[sub]));
        int visible = Math.max(1, (bottom - y) / rowH);
        rowScroll = clampScroll(rowScroll, rows.size(), visible);
        if (rows.isEmpty()) {
            String[] empty = {"Nicio fapta in cazier.", "Nicio amenda.", "Niciun vehicul inregistrat.", "Nicio locuinta inregistrata."};
            Ui.text(ms, empty[sub], dx, y + 6, FAINT);
        }
        for (int k = rowScroll; k < Math.min(rows.size(), rowScroll + visible); k++) {
            JsonObject r = rows.get(k);
            int ry = y + (k - rowScroll) * rowH;
            boolean on = sel == k;
            boolean hover = Ui.in(mx, my, dx - 4, ry, dw + 8, rowH);
            if (on) Ui.rect(ms, dx - 4, ry, dw + 8, rowH, CARD2);
            else if (hover) Ui.rect(ms, dx - 4, ry, dw + 8, rowH, CARD);
            if (on) Sk.slant(ms, dx - 4, ry, 4, rowH, 0f, Sk.ACC);
            drawRow(ms, r, dx, ry, dw);
            Ui.rect(ms, dx, ry + rowH - 1, dw, 1, 0xFF1B2330);
            final int kk = k;
            hit(dx - 4, ry, dw + 8, rowH, () -> sel = kk);
        }
        // bara de jos cu actiuni
        Ui.rect(ms, x0, bottom, w0, footH, SIDE);
        Ui.rect(ms, x0, bottom, w0, 1, RULE);
        String[] add = {"+ Adauga fapta", "+ Emite amenda", "+ Inmatriculeaza", "+ Adauga locuinta"};
        int[] kinds = {1, 2, 4, 5};
        final int kind = kinds[sub];
        button(ms, add[sub], dx, bottom + 5, 100, 14, mx, my, true, 0xFF24467A, () -> openModal(kind));
        JsonObject cur = sel >= 0 && sel < rows.size() ? rows.get(sel) : null;
        int rx = x0 + w0 - 10;
        if (cur != null) {
            final long id = JsonClientModule.lng(cur, "id", -1);
            if (sub == 0) {
                rx -= 52;
                button(ms, "Sterge", rx, bottom + 5, 52, 14, mx, my, true, 0xFF6B2F2B, () -> MdtClient.act("record_del", "u", selUuid, "id", id));
            } else if (sub == 1) {
                rx -= 52;
                button(ms, "Sterge", rx, bottom + 5, 52, 14, mx, my, true, 0xFF6B2F2B, () -> MdtClient.act("fine_del", "u", selUuid, "id", id));
                if (!b(cur, "paid")) {
                    rx -= 60;
                    button(ms, "Incaseaza", rx, bottom + 5, 56, 14, mx, my, true, 0xFF1F4D38, () -> MdtClient.act("fine_pay", "u", selUuid, "id", id, "charge", true));
                    rx -= 78;
                    button(ms, "Marcheaza platita", rx, bottom + 5, 74, 14, mx, my, true, 0xFF2B3A52, () -> MdtClient.act("fine_pay", "u", selUuid, "id", id, "charge", false));
                }
            } else if (sub == 2) {
                rx -= 52;
                button(ms, "Scoate", rx, bottom + 5, 52, 14, mx, my, true, 0xFF6B2F2B, () -> MdtClient.act("veh_del", "u", selUuid, "id", id));
                rx -= 82;
                button(ms, "Schimba starea", rx, bottom + 5, 78, 14, mx, my, true, 0xFF2B3A52, () -> MdtClient.act("veh_status", "u", selUuid, "id", id));
            } else {
                rx -= 52;
                button(ms, "Sterge", rx, bottom + 5, 52, 14, mx, my, true, 0xFF6B2F2B, () -> MdtClient.act("prop_del", "u", selUuid, "id", id));
            }
        }
    }

    private void drawRow(GuiGraphics ms, JsonObject r, int dx, int ry, int dw) {
        if (sub == 0) {
            Ui.text(ms, Ui.clip(s(r, "t"), dw - 8), dx + 2, ry + 4, INK);
            Ui.text(ms, s(r, "d") + "  -  " + s(r, "by"), dx + 2, ry + 15, FAINT);
        } else if (sub == 1) {
            boolean paid = b(r, "paid");
            String amt = i(r, "a") + " lei";
            Ui.text(ms, amt, dx + 2, ry + 4, paid ? GREEN : AMBER);
            int left = pill(ms, paid ? "PLATITA" : "NEPLATITA", dx + dw, ry + 3, paid ? GREEN : AMBER);
            Ui.text(ms, Ui.clip(s(r, "r"), left - dx - Ui.width(amt) - 14), dx + 8 + Ui.width(amt), ry + 4, INK);
            Ui.text(ms, s(r, "d") + "  -  " + s(r, "by"), dx + 2, ry + 15, FAINT);
        } else if (sub == 2) {
            Ui.text(ms, Ui.clip(s(r, "m"), dw - 110), dx + 2, ry + 4, INK);
            String st = s(r, "s");
            int c = "Clean".equalsIgnoreCase(st) ? GREEN : "Stolen".equalsIgnoreCase(st) ? RED : AMBER;
            String label = "Clean".equalsIgnoreCase(st) ? "CURAT" : "Stolen".equalsIgnoreCase(st) ? "FURAT" : "CONFISCAT";
            pill(ms, label, dx + dw, ry + 15, c);
            plateChip(ms, s(r, "p"), dx + dw - Ui.width(s(r, "p")) - 14, ry + 2);
        } else {
            Ui.text(ms, Ui.clip(s(r, "a"), dw - 8), dx + 2, ry + 9, INK);
        }
    }

    /** Numar de inmatriculare in stil european (banda albastra in stanga). */
    private void plateChip(GuiGraphics ms, String plate, int x, int y) {
        int w = Ui.width(plate) + 14;
        Ui.rect(ms, x, y, w, 12, 0xFFE9ECEF);
        Ui.rect(ms, x, y + 1, 5, 10, 0xFF2A56B8);
        Ui.text(ms, plate, x + 8, y + 2, 0xFF12161C);
    }

    // ------------------------------------------------------------------ tab 1: urmariti

    private void drawWanted(GuiGraphics ms, int mx, int my, int cx, int cy, int cw, int ch) {
        List<JsonObject> items = list(arr(MdtState.state, "wanted"));
        int x = cx + 12;
        int w = cw - 24;
        title(ms, "Persoane urmarite", x, cy + 10);
        Ui.textRight(ms, items.size() + " active", cx + cw - 12, cy + 10, items.isEmpty() ? FAINT : RED);
        int hy = cy + 28;
        Ui.rect(ms, x, hy + 12, w, 1, RULE);
        Ui.smallCaps(ms, "Nume", x + 4, hy, FAINT);
        Ui.smallCaps(ms, "Motiv", x + 120, hy, FAINT);
        Ui.smallCaps(ms, "Emis de", x + w - 80, hy, FAINT);
        int top = hy + 16;
        int rowH = 24;
        int visible = Math.max(1, (cy + ch - top - 6) / rowH);
        wantedScroll = clampScroll(wantedScroll, items.size(), visible);
        if (items.isEmpty()) Ui.text(ms, "Nimeni nu este urmarit in acest moment.", x + 4, top + 6, FAINT);
        for (int k = wantedScroll; k < Math.min(items.size(), wantedScroll + visible); k++) {
            JsonObject c = items.get(k);
            int y = top + (k - wantedScroll) * rowH;
            if (Ui.in(mx, my, x, y, w, rowH)) Ui.rect(ms, x, y, w, rowH, CARD);
            Ui.rect(ms, x + 2, y + 8, 4, 4, b(c, "on") ? GREEN : 0xFF3A4455);
            Ui.text(ms, Ui.clip(s(c, "n"), 100), x + 12, y + 8, INK);
            Ui.text(ms, Ui.clip(s(c, "reason"), w - 120 - 96), x + 120, y + 8, 0xFFE6968F);
            Ui.text(ms, Ui.clip(s(c, "by"), 76), x + w - 80, y + 8, DIM);
            Ui.rect(ms, x, y + rowH - 1, w, 1, 0xFF1B2330);
            final String u = s(c, "u");
            hit(x, y, w, rowH, () -> {
                setTab(0);
                selectCitizen(u);
            });
        }
    }

    // ------------------------------------------------------------------ tab 2: numere de inmatriculare

    private void drawPlates(GuiGraphics ms, int mx, int my, int cx, int cy, int cw, int ch) {
        int x = cx + 14;
        title(ms, "Cautare dupa numar de inmatriculare", x, cy + 12);
        // placuta
        int pwid = 190;
        int py0 = cy + 30;
        Ui.rect(ms, x, py0, pwid, 26, 0xFFE9ECEF);
        Ui.rect(ms, x, py0, 18, 26, 0xFF2A56B8);
        Ui.textCenter(ms, "RO", x + 9, py0 + 9, 0xFFFFFFFF);
        plateField.x = x + 24;
        plateField.y = py0 + 9;
        plateField.setWidth(pwid - 30);
        plateField.render(ms.pose(), mx, my, 0);
        button(ms, "Cauta", x + pwid + 8, py0 + 5, 54, 16, mx, my, true, 0xFF24467A, this::lookupPlate);
        int y = py0 + 42;
        JsonObject p = MdtState.plate;
        if (p == null) {
            Ui.text(ms, "Scrie numarul si apasa Enter.", x, y, FAINT);
            return;
        }
        if (!b(p, "found")) {
            Ui.text(ms, "Numarul " + s(p, "plate") + " nu este inregistrat.", x, y, DIM);
            return;
        }
        int w = cw - 28;
        Ui.frame(ms, x, y, w, 70, CARD, RULE);
        Ui.smallCaps(ms, "Vehicul identificat", x + 10, y + 8, FAINT);
        Gfx.text(ms, Ui.clip(s(p, "model"), w - 130), x + 10, y + 36, 13, INK, 0, true, 0f);
        String st = s(p, "status");
        int c = "Clean".equalsIgnoreCase(st) ? GREEN : "Stolen".equalsIgnoreCase(st) ? RED : AMBER;
        String label = "Clean".equalsIgnoreCase(st) ? "CURAT" : "Stolen".equalsIgnoreCase(st) ? "FURAT" : "CONFISCAT";
        pill(ms, label, x + w - 10, y + 8, c);
        plateChip(ms, s(p, "plate"), x + w - Ui.width(s(p, "plate")) - 24, y + 24);
        Ui.text(ms, "Proprietar:  " + s(p, "owner") + (b(p, "wanted") ? "   (URMARIT)" : ""), x + 10, y + 46, b(p, "wanted") ? RED : DIM);
        final String owner = s(p, "ownerUuid");
        button(ms, "Vezi proprietar", x + w - 98, y + 46, 88, 16, mx, my, true, 0xFF24467A, () -> {
            setTab(0);
            sub = 2;
            selectCitizen(owner);
        });
    }

    // ------------------------------------------------------------------ tab 3: dispecerat

    private void drawDispatch(GuiGraphics ms, int mx, int my, int cx, int cy, int cw, int ch) {
        int split = cx + (cw * 11) / 20;
        Ui.rect(ms, split, cy, 1, ch, RULE);
        // ---- unitati
        int x = cx + 10;
        int w = split - cx - 18;
        title(ms, "Unitati", x, cy + 10);
        List<JsonObject> units = list(arr(MdtState.state, "units"));
        String mine = s(me(), "unit");
        int formH = 52;
        int top = cy + 26;
        int rowH = 34;
        int visible = Math.max(1, (cy + ch - formH - top - 4) / rowH);
        unitScroll = clampScroll(unitScroll, units.size(), visible);
        if (units.isEmpty()) Ui.text(ms, "Nicio unitate pe teren.", x, top + 6, FAINT);
        for (int k = unitScroll; k < Math.min(units.size(), unitScroll + visible); k++) {
            JsonObject u = units.get(k);
            int y = top + (k - unitScroll) * rowH;
            boolean isMine = s(u, "id").equals(mine);
            Ui.rect(ms, x - 2, y, w + 4, rowH - 3, isMine ? CARD2 : CARD);
            if (isMine) Sk.slant(ms, x - 2, y, 4, rowH - 3, 0f, Sk.ACC);
            boolean free = "Available".equals(s(u, "status"));
            Ui.rect(ms, x + 6, y + 6, 5, 5, free ? GREEN : AMBER);
            Ui.text(ms, Ui.clip(s(u, "name"), w - 90), x + 16, y + 5, INK);
            Ui.text(ms, s(u, "type").toLowerCase(), x + 18 + Ui.width(Ui.clip(s(u, "name"), w - 90)), y + 5, FAINT);
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : arr(u, "members")) sb.append(sb.length() == 0 ? "" : ", ").append(e.getAsString());
            Ui.text(ms, Ui.clip(sb.toString(), w - 70), x + 16, y + 18, DIM);
            if (!isMine) {
                final String id = s(u, "id");
                button(ms, "Intra", x + w - 40, y + 8, 38, 14, mx, my, true, 0xFF24467A, () -> MdtClient.act("unit_join", "id", id));
            } else {
                pill(ms, "A TA", x + w - 2, y + 6, Sk.ACC);
            }
        }
        // formular jos
        int fy = cy + ch - formH + 4;
        Ui.rect(ms, cx + 1, cy + ch - formH, split - cx - 1, formH, SIDE);
        Ui.rect(ms, cx + 1, cy + ch - formH, split - cx - 1, 1, RULE);
        drawField(ms, unitName, x, fy + 2, 96, 16, mx, my);
        int tx = x + 102;
        for (String t : UNIT_TYPES) {
            int tw = Ui.width(t.substring(0, 3)) + 12;
            final String tt = t;
            chip(ms, t.substring(0, 3), tx, fy + 2, tw, 16, mx, my, t.equals(unitType), BLUE, () -> unitType = tt);
            tx += tw + 3;
        }
        button(ms, "Creeaza", x, fy + 24, 52, 14, mx, my, true, 0xFF24467A, this::createUnit);
        boolean inUnit = !mine.isEmpty();
        button(ms, "Disponibil", x + 56, fy + 24, 58, 14, mx, my, inUnit, 0xFF1F4D38, () -> MdtClient.act("unit_status", "status", "Available"));
        button(ms, "Ocupat", x + 118, fy + 24, 46, 14, mx, my, inUnit, 0xFF6B4D14, () -> MdtClient.act("unit_status", "status", "Busy"));
        button(ms, "Paraseste", x + 168, fy + 24, 56, 14, mx, my, inUnit, 0xFF6B2F2B, () -> MdtClient.act("unit_leave"));

        // ---- backup
        int bx = split + 10;
        int bw = cx + cw - bx - 10;
        title(ms, "Apeluri de ajutor", bx, cy + 10);
        int by = cy + 26;
        int cwid = (bw - 8) / 3;
        String[] names = {"BK1 urgent", "BK2 transport", "BK3 urmarire"};
        int[] cols = {0xFF7A2A26, 0xFF6B4D14, 0xFF24467A};
        for (int k = 0; k < 3; k++) {
            final int code = k + 1;
            button(ms, names[k], bx + k * (cwid + 4), by, cwid, 15, mx, my, true, cols[k], () -> MdtClient.act("backup", "code", code));
        }
        List<JsonObject> bks = list(arr(MdtState.state, "backups"));
        int top2 = by + 24;
        int rowH2 = 30;
        int vis2 = Math.max(1, (cy + ch - top2 - 4) / rowH2);
        backupScroll = clampScroll(backupScroll, bks.size(), vis2);
        if (bks.isEmpty()) Ui.text(ms, "Niciun apel activ.", bx, top2 + 4, FAINT);
        for (int k = backupScroll; k < Math.min(bks.size(), backupScroll + vis2); k++) {
            JsonObject bk = bks.get(k);
            int y = top2 + (k - backupScroll) * rowH2;
            int code = i(bk, "code");
            int c = code == 99 ? 0xFFB3261E : code == 1 ? RED : code == 2 ? AMBER : BLUE;
            Ui.rect(ms, bx - 2, y, bw + 4, rowH2 - 3, CARD);
            Sk.slant(ms, bx - 2, y, 26, rowH2 - 3, 5f, c);
            Ui.textCenter(ms, code == 99 ? "99" : "BK" + code, bx + 11, y + 9, DARK);
            Ui.text(ms, Ui.clip(s(bk, "name"), bw - 120), bx + 28, y + 4, INK);
            Ui.text(ms, (int) JsonClientModule.num(bk, "x", 0) + ", " + (int) JsonClientModule.num(bk, "z", 0) + "   acum " + ago(JsonClientModule.lng(bk, "age", 0)), bx + 28, y + 15, FAINT);
            final String id = s(bk, "id");
            final String nm = s(bk, "name");
            final double bxp = JsonClientModule.num(bk, "x", 0);
            final double bzp = JsonClientModule.num(bk, "z", 0);
            button(ms, "OK", bx + bw - 24, y + 8, 22, 14, mx, my, true, 0xFF1F4D38, () -> MdtClient.act("backup_resolve", "id", id));
        }
    }

    // ------------------------------------------------------------------ tab 4: echipa

    private boolean canManage(JsonObject o) {
        JsonObject m = me();
        if (b(m, "admin")) return true;
        return i(m, "rank") >= 4 && i(o, "rank") < i(m, "rank");
    }

    private void drawTeam(GuiGraphics ms, int mx, int my, int cx, int cy, int cw, int ch) {
        int lw = 200;
        Ui.rect(ms, cx + lw, cy, 1, ch, RULE);
        List<JsonObject> offs = list(arr(MdtState.state, "officers"));
        int x = cx + 10;
        title(ms, "Personal", x, cy + 10);
        Ui.textRight(ms, offs.size() + " ofiteri", cx + lw - 10, cy + 10, FAINT);
        int top = cy + 26;
        int rowH = 22;
        int visible = Math.max(1, (cy + ch - top - 4) / rowH);
        officerScroll = clampScroll(officerScroll, offs.size(), visible);
        if (offs.isEmpty()) Ui.text(ms, "Niciun ofiter angajat.", x, top + 6, FAINT);
        JsonObject cur = selOfficer >= 0 && selOfficer < offs.size() ? offs.get(selOfficer) : null;
        for (int k = officerScroll; k < Math.min(offs.size(), officerScroll + visible); k++) {
            JsonObject o = offs.get(k);
            int y = top + (k - officerScroll) * rowH;
            boolean on = k == selOfficer;
            boolean hover = Ui.in(mx, my, cx + 1, y, lw - 1, rowH);
            if (on) Sk.slant(ms, cx + 4, y + 1, lw - 8, rowH - 2, 5f, Sk.ACC);
            else if (hover) Sk.slant(ms, cx + 4, y + 1, lw - 8, rowH - 2, 5f, Sk.ROW_HOT);
            Ui.rect(ms, x + 2, y + 8, 4, 4, b(o, "on") ? (on ? DARK : GREEN) : 0xFF3A4455);
            Ui.text(ms, Ui.clip(s(o, "n"), 90), x + 12, y + 7, on ? DARK : INK);
            Ui.textRight(ms, rankLabel(i(o, "rank")), cx + lw - 10, y + 7, on ? DARK : DIM);
            
            final int kk = k;
            hit(cx + 1, y, lw - 1, rowH, () -> {
                selOfficer = kk;
                rankConfig = false;
            });
        }
        int rx = cx + lw + 12;
        int rw = cx + cw - rx - 10;
        boolean admin = b(me(), "admin");
        if (rankConfig && admin) {
            drawRankConfig(ms, mx, my, rx, rw, cy);
        } else if (cur != null) {
            Gfx.text(ms, Ui.clip(s(cur, "n"), rw), rx, cy + 22, 13, INK, 0, true, 0f);
            Ui.text(ms, "Grad " + i(cur, "rank") + "  -  " + rankLabel(i(cur, "rank")), rx, cy + 28, DIM);
            Ui.text(ms, b(cur, "on") ? "online" : "offline", rx, cy + 40, b(cur, "on") ? GREEN : FAINT);
            boolean ok = canManage(cur);
            final String u = s(cur, "u");
            final int rk = i(cur, "rank");
            button(ms, "Promoveaza", rx, cy + 62, 80, 15, mx, my, ok && rk < 6, 0xFF1F4D38, () -> MdtClient.act("officer_set", "u", u, "rank", rk + 1));
            button(ms, "Retrogradeaza", rx + 86, cy + 62, 84, 15, mx, my, ok && rk > 1, 0xFF6B4D14, () -> MdtClient.act("officer_set", "u", u, "rank", rk - 1));
            button(ms, "Da afara", rx, cy + 84, 80, 15, mx, my, ok, 0xFF6B2F2B, () -> {
                MdtClient.act("officer_fire", "u", u);
                selOfficer = -1;
            });
            if (!ok) Ui.text(ms, "Nu ai grad suficient pentru acest ofiter.", rx, cy + 108, FAINT);
        } else {
            Ui.text(ms, "Alege un ofiter din lista.", rx, cy + 14, FAINT);
        }
        if (admin) {
            int by = cy + ch - 24;
            button(ms, "Angajeaza", rx, by, 70, 15, mx, my, true, 0xFF24467A, () -> openModal(6));
            button(ms, rankConfig ? "Inchide grade" : "Configureaza grade", rx + 76, by, 96, 15, mx, my, true, 0xFF2B3A52, () -> {
                unfocusAll();
                rankConfig = !rankConfig;
                loadRank(cfgRank);
            });
        }
    }

    private String rankLabel(int r) {
        for (JsonObject k : list(arr(MdtState.state, "ranks"))) if (i(k, "r") == r) return s(k, "name");
        return "Grad " + r;
    }

    private void loadRank(int r) {
        cfgRank = r;
        for (JsonObject k : list(arr(MdtState.state, "ranks"))) {
            if (i(k, "r") != r) continue;
            rkName.setValue(s(k, "name"));
            rkUp.setValue(s(k, "up"));
            rkDown.setValue(s(k, "down"));
        }
    }

    private void drawRankConfig(GuiGraphics ms, int mx, int my, int rx, int rw, int cy) {
        title(ms, "Configurare grade", rx, cy + 10);
        int cxp = rx;
        for (int r = 1; r <= 6; r++) {
            final int rr = r;
            chip(ms, String.valueOf(r), cxp, cy + 26, 20, 16, mx, my, cfgRank == r, AMBER, () -> loadRank(rr));
            cxp += 24;
        }
        Ui.text(ms, "Nume", rx, cy + 52, FAINT);
        drawField(ms, rkName, rx, cy + 62, rw, 16, mx, my);
        Ui.text(ms, "Comanda la promovare in acest grad", rx, cy + 86, FAINT);
        drawField(ms, rkUp, rx, cy + 96, rw, 16, mx, my);
        Ui.text(ms, "Comanda la retrogradare", rx, cy + 120, FAINT);
        drawField(ms, rkDown, rx, cy + 130, rw, 16, mx, my);
        button(ms, "Salveaza gradul", rx, cy + 156, 90, 15, mx, my, true, 0xFF1F4D38,
            () -> MdtClient.act("rank_save", "rank", cfgRank, "name", rkName.getValue(), "up", rkUp.getValue(), "down", rkDown.getValue()));
        Ui.text(ms, "Comenzile ruleaza din consola. Ex: lp user %player% parent set politist", rx, cy + 178, FAINT);
    }

    // ------------------------------------------------------------------ fereastra de formular

    private void drawModal(GuiGraphics ms, int mx, int my) {
        hit(0, 0, width, height, () -> { });
        Ui.rect(ms, px, py, pw, ph, 0xA0050709);
        String[] titles = {"", "Fapta in cazier", "Emite amenda", "Emite mandat", "Inmatriculeaza vehicul", "Adauga locuinta", "Angajeaza ofiter"};
        List<TextFieldWidget> fs = fields();
        String[][] labels = {{}, {"Descriere"}, {"Suma (lei)", "Motiv"}, {"Motiv urmarire"}, {"Model", "Numar de inmatriculare"}, {"Adresa"}, {"Nume jucator (trebuie sa fie online)"}};
        int w = 250;
        int h = 56 + fs.size() * 32 + (modal == 6 ? 34 : 0);
        int x = px + (pw - w) / 2;
        int y = py + (ph - h) / 2;
        Sk.cut(ms, x, y, w, h, 10f, CARD);
        Ui.rect(ms, x + 10, y, 40, 2, Sk.ACC);
        title(ms, titles[modal], x + 12, y + 7);
        int fy = y + 28;
        for (int k = 0; k < fs.size(); k++) {
            Ui.text(ms, labels[modal][k], x + 10, fy, FAINT);
            drawField(ms, fs.get(k), x + 10, fy + 10, w - 20, 16, mx, my);
            fy += 32;
        }
        if (modal == 6) {
            Ui.text(ms, "Grad de start", x + 10, fy, FAINT);
            int cxp = x + 10;
            for (int r = 1; r <= 6; r++) {
                final int rr = r;
                chip(ms, String.valueOf(r), cxp, fy + 10, 20, 14, mx, my, newRank == r, AMBER, () -> newRank = rr);
                cxp += 24;
            }
            Ui.text(ms, rankLabel(newRank), cxp + 4, fy + 13, DIM);
            fy += 34;
        }
        button(ms, "Confirma", x + w - 148, y + h - 24, 66, 16, mx, my, true, 0xFF1F4D38, this::confirmModal);
        button(ms, "Renunta", x + w - 76, y + h - 24, 66, 16, mx, my, true, 0xFF3A2328, () -> modal = 0);
        if (pendingFocus != null) {
            focus(pendingFocus, true);
            pendingFocus = null;
        }
    }

    // ------------------------------------------------------------------ scroll

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int d = -(int) Math.signum(delta);
        int cx = px + RAIL;
        switch (tab) {
            case 0:
                if (mx < cx + LIST) listScroll += d;
                else rowScroll += d;
                break;
            case 1:
                wantedScroll += d;
                break;
            case 3:
                if (mx < cx + (pw - RAIL) * 11 / 20) unitScroll += d;
                else backupScroll += d;
                break;
            default:
                officerScroll += d;
                break;
        }
        return true;
    }
}
