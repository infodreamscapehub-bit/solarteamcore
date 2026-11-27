package ro.solarstudios.flowcity.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * E-shop deschis de un NPC, refacut in tema de iarna FlowCity: antet cu breadcrumb si solduri, categorii ca tab-uri cu
 * colturi taiate, grila de carduri cu iconita itemului (orice item din orice mod), articole blocate de permis (gri, cu
 * lacat), cantitate + CUMPARA cu dialog de confirmare. Plata (cash sau card) vine din PayScreen. Protocolul cu pluginul
 * ramane acelasi (buy: cat, idx, qty); in plus pluginul trimite solduri si permisele jucatorului (open/state).
 * Taste: sageti = alegi articolul, Enter = cumperi/confirmi, Tab / 1-9 = categorii, + / - = cantitate, Esc = inchide.
 *
 * Editor (doar pentru cei cu flowcity.shop.admin, pluginul trimite "admin"): butonul EDITARE din antet porneste modul de editare: pe fiecare
 * card apar EDIT / STERGE, un card "+ ADAUGA ARTICOL", un tab "+" pentru categorii si formulare in stil iarna (nume, item cu "DIN MANA",
 * pret, cantitate, descriere, permise necesare, comenzi pentru op). Acțiunile pleaca la server prin mesajul "edit"; serverul re-verifica
 * permisiunea, valideaza si salveaza, apoi trimite "update" (lista noua) si "editres" (rezultatul).
 */
public final class ShopScreen extends FlowScreen {
    private static final int W = 560;
    private static final int H = 320;
    private static final int SBW = 100;
    private static final int COLS = 4;
    private static final int GAP = 8;
    private static final int GX = 112;
    private static final int GY = 60;
    private static final int CW = (W - GX - 12 - (COLS - 1) * GAP) / COLS;
    private static final int CH = 88;
    private static final int ROWS = 2;
    private static final int PER = COLS * ROWS;
    private static final int GH = ROWS * CH + (ROWS - 1) * GAP;
    /** Accent portocaliu-rosu al magazinului (diferit de cyan-ul telefonului). */
    private static final int HOT = 0xFFFF5A2E;
    private static final int HOT_D = 0xFF7A2A14;
    private static final int SIDE = 0xFF0B0E14;
    private static final int CARD = 0xFF141922;
    private static final int CARD_HOT = 0xFF1D2430;

    private static final class It {
        String name;
        double price;
        int amount;
        String desc;
        String permit = "";
        final List<String> req = new ArrayList<>();
        final List<String> reqn = new ArrayList<>();
        String itemId = "";
        final List<String> cmds = new ArrayList<>();
        ItemStack stack = ItemStack.EMPTY;
    }

    private String title;
    private final List<Integer> catIdx = new ArrayList<>();
    private boolean admin;
    private boolean cmdOk;
    private boolean editing;
    private final List<String[]> permits = new ArrayList<>();
    private final List<String> catNames = new ArrayList<>();
    private final List<List<It>> cats = new ArrayList<>();
    private final Set<String> have = new HashSet<>();
    private double cash = -1;
    private double bank = -1;
    private int cat;
    private int sel = -1;
    private int qty = 1;
    private int scrollRow;
    private boolean confirm;
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private final long born = System.currentTimeMillis();
    private long animStart = born;
    private float offX;
    private float offY;
    private float scale = 1f;

    // editor: 0 = nimic, 1 = formular articol, 2 = formular categorie, 3 = stergere articol, 4 = stergere categorie
    private int modal;
    private boolean modalNew;
    private boolean formPermit;
    private int editIdx = -1;
    private long pendingUntil;
    private final Set<String> formReq = new LinkedHashSet<>();
    private final EditBox fName;
    private final EditBox fItem;
    private final EditBox fPrice;
    private final EditBox fAmount;
    private final EditBox fDesc;
    private final EditBox fCmd;
    private final EditBox fCat;

    ShopScreen(JsonObject d) {
        super("Magazin", W, H);
        fName = field("Nume articol", 40, null);
        fItem = field("minecraft:bread sau tac:ak47", 80, null);
        fPrice = field("ex. 2500", 10, null);
        fAmount = field("1", 4, null);
        fDesc = field("Descriere scurtă (opțional)", 80, null);
        fCmd = field("comenzi consolă separate prin ;", 300, null);
        fCat = field("Nume categorie", 24, null);
        load(d);
    }

    private void load(JsonObject d) {
        this.title = JsonClientModule.str(d, "title", "MAGAZIN");
        admin = JsonClientModule.bool(d, "admin", false);
        cmdOk = admin && JsonClientModule.bool(d, "cmds", false);
        permits.clear();
        if (admin && d.has("permits") && d.get("permits").isJsonArray()) {
            for (JsonElement e : d.getAsJsonArray("permits")) {
                JsonObject po = e.getAsJsonObject();
                permits.add(new String[] {JsonClientModule.str(po, "id", ""), JsonClientModule.str(po, "name", "")});
            }
        }
        readState(d);
        catNames.clear();
        cats.clear();
        catIdx.clear();
        JsonArray ca = d.has("cats") && d.get("cats").isJsonArray() ? d.getAsJsonArray("cats") : new JsonArray();
        int n = 0;
        for (JsonElement ce : ca) {
            JsonObject co = ce.getAsJsonObject();
            catIdx.add(co.has("ci") ? JsonClientModule.integer(co, "ci", n) : n);
            n++;
            catNames.add(JsonClientModule.str(co, "name", "?"));
            List<It> list = new ArrayList<>();
            JsonArray ia = co.has("items") && co.get("items").isJsonArray() ? co.getAsJsonArray("items") : new JsonArray();
            for (JsonElement ie : ia) {
                JsonObject io = ie.getAsJsonObject();
                It it = new It();
                it.name = JsonClientModule.str(io, "name", "?");
                it.price = JsonClientModule.num(io, "price", 0);
                it.amount = Math.max(1, JsonClientModule.integer(io, "amount", 1));
                it.desc = JsonClientModule.str(io, "desc", "");
                it.permit = JsonClientModule.str(io, "permit", "");
                it.itemId = JsonClientModule.str(io, "item", "minecraft:stone");
                if (io.has("req") && io.get("req").isJsonArray()) {
                    for (JsonElement e : io.getAsJsonArray("req")) it.req.add(e.getAsString());
                }
                if (io.has("reqn") && io.get("reqn").isJsonArray()) {
                    for (JsonElement e : io.getAsJsonArray("reqn")) it.reqn.add(e.getAsString());
                }
                if (io.has("cmd") && io.get("cmd").isJsonArray()) {
                    for (JsonElement e : io.getAsJsonArray("cmd")) it.cmds.add(e.getAsString());
                }
                try {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(it.itemId));
                    if (item != null) it.stack = new ItemStack(item, Math.min(64, it.amount));
                } catch (RuntimeException ignored) {
                    // id invalid: ramane fara iconita
                }
                list.add(it);
            }
            cats.add(list);
        }
    }

    /** Lista noua dupa o editare (trimisa de server tuturor celor care au magazinul deschis): pastreaza categoria si modul de editare. */
    void refresh(JsonObject d) {
        int realCat = cat >= 0 && cat < catIdx.size() ? catIdx.get(cat) : 0;
        load(d);
        int pos = catIdx.indexOf(realCat);
        cat = pos >= 0 ? pos : Math.min(cat, Math.max(0, cats.size() - 1));
        if (!admin) {
            editing = false;
            if (modal != 0) closeModal();
        }
        if (sel >= items().size()) sel = -1;
        scrollRow = Math.max(0, Math.min(maxRow(), scrollRow));
        confirm = false;
    }

    /** Raspunsul serverului la o editare: ok -> inchidem formularul, altfel il lasam deschis cu mesajul de eroare. */
    void editResult(boolean ok, String m) {
        pendingUntil = 0;
        if (ok) closeModal();
        result(ok, ok ? "Salvat." : m);
    }

    private void readState(JsonObject d) {
        cash = d.has("cash") ? JsonClientModule.num(d, "cash", -1) : cash;
        bank = d.has("bank") ? JsonClientModule.num(d, "bank", -1) : bank;
        if (d.has("have") && d.get("have").isJsonArray()) {
            have.clear();
            for (JsonElement e : d.getAsJsonArray("have")) have.add(e.getAsString());
        }
    }

    /** Solduri si permise actualizate dupa o cumparare. */
    void state(JsonObject d) {
        readState(d);
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
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

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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

    private static String clip(String s, float size, float max) {
        String r = s;
        while (r.length() > 1 && Gfx.width(r, size, false) > max) r = r.substring(0, r.length() - 1);
        return r.length() < s.length() ? r.trim() + "…" : r;
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private List<It> items() {
        return cat >= 0 && cat < cats.size() ? cats.get(cat) : new ArrayList<It>();
    }

    private It selected() {
        List<It> l = items();
        return sel >= 0 && sel < l.size() ? l.get(sel) : null;
    }

    private boolean locked(It it) {
        for (String r : it.req) if (!have.contains(r)) return true;
        return false;
    }

    private boolean owned(It it) {
        return !it.permit.isEmpty() && have.contains(it.permit);
    }

    private boolean buyable(It it) {
        return it != null && !locked(it) && !owned(it);
    }

    private String lockText(It it) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < it.req.size(); i++) {
            if (have.contains(it.req.get(i))) continue;
            if (b.length() > 0) b.append(", ");
            b.append(i < it.reqn.size() ? it.reqn.get(i) : it.req.get(i));
        }
        return "NECESITĂ PERMIS: " + b.toString().toUpperCase(Locale.ROOT);
    }

    private boolean editMode() {
        return admin && editing;
    }

    private int gridCount() {
        return items().size() + (editMode() ? 1 : 0);
    }

    private int pages() {
        return Math.max(1, (gridCount() + PER - 1) / PER);
    }

    private int realCat() {
        return cat >= 0 && cat < catIdx.size() ? catIdx.get(cat) : -1;
    }

    private int maxRow() {
        return pages() - 1;
    }

    private void setCat(int c) {
        if (c < 0 || c >= cats.size() || c == cat) return;
        cat = c;
        sel = -1;
        scrollRow = 0;
        qty = 1;
        confirm = false;
        animStart = System.currentTimeMillis();
    }

    private void select(int i) {
        sel = i;
        qty = 1;
        scrollRow = Math.max(0, Math.min(maxRow(), i / PER));
    }

    private void doBuy() {
        It it = selected();
        if (!buyable(it)) return;
        confirm = false;
        ShopClient.buy(realCat(), sel, it.permit.isEmpty() ? qty : 1);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (confirm || modal != 0) return true;
        scrollRow = Math.max(0, Math.min(maxRow(), scrollRow - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (modal != 0) return modalKey(key, scan, mods);
        if (confirm) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                doBuy();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                confirm = false;
                return true;
            }
            return true;
        }
        List<It> l = items();
        int n = l.size();
        switch (key) {
            case GLFW.GLFW_KEY_RIGHT:
                if (n > 0) select(sel < 0 ? 0 : Math.min(n - 1, sel + 1));
                return true;
            case GLFW.GLFW_KEY_LEFT:
                if (n > 0) select(sel < 0 ? 0 : Math.max(0, sel - 1));
                return true;
            case GLFW.GLFW_KEY_DOWN:
                if (n > 0) select(sel < 0 ? 0 : Math.min(n - 1, sel + COLS));
                return true;
            case GLFW.GLFW_KEY_UP:
                if (n > 0) select(sel < 0 ? 0 : Math.max(0, sel - COLS));
                return true;
            case GLFW.GLFW_KEY_PAGE_DOWN:
                scrollRow = Math.min(maxRow(), scrollRow + 1);
                return true;
            case GLFW.GLFW_KEY_PAGE_UP:
                scrollRow = Math.max(0, scrollRow - 1);
                return true;
            case GLFW.GLFW_KEY_TAB:
                if (!cats.isEmpty()) setCat((cat + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? cats.size() - 1 : 1)) % cats.size());
                return true;
            case GLFW.GLFW_KEY_EQUAL:
            case GLFW.GLFW_KEY_KP_ADD:
                if (selected() != null && selected().permit.isEmpty()) qty = Math.min(64, qty + 1);
                return true;
            case GLFW.GLFW_KEY_MINUS:
            case GLFW.GLFW_KEY_KP_SUBTRACT:
                qty = Math.max(1, qty - 1);
                return true;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
                if (!editMode() && buyable(selected())) confirm = true;
                return true;
            default:
                if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                    setCat(key - GLFW.GLFW_KEY_1);
                    return true;
                }
                return super.keyPressed(key, scan, mods);
        }
    }

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
        Gfx.rrect(ms, -offX / scale, -offY / scale, (width) / scale, (height) / scale, 0, Gfx.fade(0xFF10131A, 0.97f * open), Gfx.fade(0xFF07080C, 0.98f * open));
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy, open);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        for (EditBox f : fields()) f.render(ms, mx, my, 0);
    }

    private void content(GuiGraphics ms, float lmx, float lmy, float open) {
        long now = System.currentTimeMillis();
        // bara laterala cu categorii
        Gfx.rrect(ms, 0, -3000, SBW, 7000, 0, SIDE);
        Gfx.rrect(ms, SBW, -3000, 1, 7000, 0, 0x22FFFFFF);
        Gfx.text(ms, "FLOWCITY", 14, 24, 9.5f, HOT, 0, true, 1.6f);
        Gfx.text(ms, clip(title, 6.2f, SBW - 20).toUpperCase(), 14, 35, 6.2f, Sk.MUTED, 0, false, 1.0f);
        Gfx.rrect(ms, 14, 41, 22, 2, 0, HOT);
        boolean plusTab = editMode() && catNames.size() < 8;
        float cy0 = 52;
        for (int i = 0; i < catNames.size(); i++) {
            float y = cy0 + i * 30;
            boolean on = i == cat;
            boolean hot = Sk.in(lmx, lmy, 6, y, SBW - 12, 26);
            if (on) {
                Gfx.rrect(ms, 6, y, SBW - 12, 26, 4, 0xFF2A1810);
                Gfx.rrect(ms, 6, y + 4, 2, 18, 0, HOT);
            } else if (hot) {
                Gfx.rrect(ms, 6, y, SBW - 12, 26, 4, 0xFF161B24);
            }
            Gfx.rrect(ms, 14, y + 6, 14, 14, 3, on ? HOT : 0xFF2A3140);
            String nm0 = catNames.get(i);
            Gfx.text(ms, nm0.isEmpty() ? "?" : nm0.substring(0, 1).toUpperCase(), 21, y + 16.2f, 8f, on ? 0xFF160A04 : Sk.MUTED, 1, true, 0f);
            Gfx.text(ms, clip(nm0, 7.4f, SBW - 48), 34, y + 16.2f, 7.4f, on ? Sk.WHITE : (hot ? Sk.WHITE : Sk.MUTED), 0, on, 0.2f);
            final int ci = i;
            hitL(6, y, SBW - 12, 26, () -> setCat(ci));
        }
        if (plusTab) {
            float y = cy0 + catNames.size() * 30;
            boolean hp = Sk.in(lmx, lmy, 6, y, SBW - 12, 26);
            Gfx.rrect(ms, 6, y, SBW - 12, 26, 4, hp ? 0xFF161B24 : 0x600B0E14);
            Gfx.text(ms, "+  CATEGORIE", 14, y + 16.2f, 6.8f, hp ? Sk.WHITE : Sk.MUTED, 0, true, 0.3f);
            hitL(6, y, SBW - 12, 26, this::openCatNew);
        }

        // antet: breadcrumb + titlu + butoane
        Gfx.text(ms, "FLOWCITY  ›  " + title + (catNames.isEmpty() || cat >= catNames.size() ? "" : "  ›  " + catNames.get(cat)), GX, 24, 6.4f, Sk.MUTED, 0, false, 1.1f);
        Gfx.text(ms, clip(cat >= 0 && cat < catNames.size() ? catNames.get(cat) : title, 16f, 220), GX, 46, 16, Sk.WHITE, 0, true, 0f);
        boolean hc = Sk.in(lmx, lmy, W - 88, 12, 76, 22);
        Gfx.rrect(ms, W - 88, 12, 76, 22, 4, hc ? HOT : 0xFF2A1810);
        Gfx.text(ms, "ÎNCHIDE", W - 50, 26.6f, 7.6f, hc ? 0xFF160A04 : HOT, 1, true, 0.6f);
        hitL(W - 88, 12, 76, 22, this::onClose);
        if (admin) {
            boolean he = Sk.in(lmx, lmy, W - 180, 12, 84, 22);
            Gfx.rrect(ms, W - 180, 12, 84, 22, 4, editing ? HOT : (he ? 0xFF2A3140 : 0xFF1D2430));
            Gfx.text(ms, editing ? "GATA" : "EDITARE", W - 138, 26.6f, 7.6f, editing ? 0xFF160A04 : Sk.WHITE, 1, true, 0.6f);
            hitL(W - 180, 12, 84, 22, () -> {
                editing = !editing;
                confirm = false;
                sel = -1;
                scrollRow = 0;
                animStart = System.currentTimeMillis();
            });
        }

        // grila de carduri (pagina curenta)
        List<It> its = items();
        scrollRow = Math.max(0, Math.min(maxRow(), scrollRow));
        int count = gridCount();
        int from = scrollRow * PER;
        int to = Math.min(count, from + PER);
        for (int i = from; i < to; i++) {
            int k = i - from;
            int col = k % COLS;
            int row = k / COLS;
            float x = GX + col * (CW + GAP);
            float y = GY + row * (CH + GAP);
            float t = easeOut((now - animStart - 60 - k * 30L) / 240f);
            if (t <= 0f) continue;
            float a = open * t;
            float yy = y + (1f - t) * 8f;
            Gfx.alpha = a;
            boolean isAdd = i >= its.size();
            if (isAdd) drawAddCard(ms, x, yy, lmx, lmy);
            else drawCard(ms, its.get(i), i, x, yy, lmx, lmy, a);
            Gfx.alpha = open;
            final int fi = i;
            if (isAdd) hitL(x, y, CW, CH, () -> openItemForm(-1));
            else hitL(x, y, CW, CH, () -> select(fi));
            if (!isAdd && editMode()) {
                hitL(x + 3, yy + 3, 44, 13, () -> openItemForm(fi));
                hitL(x + CW - 47, yy + 3, 44, 13, () -> openDelItem(fi));
            }
        }
        if (its.isEmpty() && !editMode()) Gfx.text(ms, "Nimic de vândut aici.", GX + (W - GX - 12) / 2f, GY + 76, 8.4f, Sk.MUTED, 1);

        // paginare
        float py = GY + GH + 6;
        int pg = scrollRow + 1;
        int np = pages();
        Gfx.text(ms, count == 0 ? "AFIȘARE 0 DIN 0" : "AFIȘARE " + (from + 1) + " – " + to + " DIN " + count, GX, py + 10, 6.4f, Sk.MUTED, 0, false, 1.0f);
        float cx = GX + (W - GX - 12) / 2f;
        pageBtn(ms, cx - 70, py, "<", scrollRow > 0, lmx, lmy, () -> scrollRow = Math.max(0, scrollRow - 1));
        Gfx.text(ms, "PAGINA " + pg + " / " + np, cx, py + 10.4f, 7f, Sk.WHITE, 1, true, 1.0f);
        pageBtn(ms, cx + 54, py, ">", scrollRow < np - 1, lmx, lmy, () -> scrollRow = Math.min(maxRow(), scrollRow + 1));
        if (bank >= 0 || cash >= 0) {
            float rx = W - 14;
            if (bank >= 0) {
                String b = "CARD  " + money(bank) + " RON";
                Gfx.text(ms, b, rx, H - 4, 6.8f, Sk.WHITE, 2, true, 0.4f);
                rx -= Gfx.width(b, 6.8f, true) + 16;
            }
            if (cash >= 0) Gfx.text(ms, "CASH  " + money(cash) + " RON", rx, H - 4, 6.8f, Sk.MUTED, 2, false, 0.4f);
        }

        bottomBar(ms, lmx, lmy);

        if (confirm) dialog(ms, lmx, lmy);
        if (modal == 1) itemForm(ms, lmx, lmy);
        else if (modal == 2) catForm(ms, lmx, lmy);
        else if (modal == 3 || modal == 4) delDialog(ms, lmx, lmy);
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    private static String rarity(double price) {
        if (price < 500) return "COMMON";
        if (price < 2500) return "UNCOMMON";
        if (price < 10000) return "RARE";
        if (price < 50000) return "EPIC";
        return "LEGENDARY";
    }

    private static int rarityColor(String r) {
        switch (r) {
            case "UNCOMMON": return 0xFF4CD964;
            case "RARE": return 0xFF4C9BFF;
            case "EPIC": return 0xFFB45CFF;
            case "LEGENDARY": return 0xFFFFB02E;
            default: return 0xFF9AA3B2;
        }
    }

    private void pageBtn(GuiGraphics ms, float x, float y, String l, boolean on, float lmx, float lmy, Runnable r) {
        boolean hv = on && Sk.in(lmx, lmy, x, y, 16, 14);
        Gfx.rrect(ms, x, y, 16, 14, 3, hv ? HOT : (on ? 0xFF1D2430 : 0xFF11151C));
        Gfx.text(ms, l, x + 8, y + 10.6f, 8f, hv ? 0xFF160A04 : (on ? Sk.WHITE : Sk.FAINT), 1, true, 0f);
        if (on) hitL(x, y, 16, 14, r);
    }

    private void drawCard(GuiGraphics ms, It it, int i, float x, float y, float lmx, float lmy, float a) {
        boolean lk = locked(it);
        boolean ow = owned(it);
        boolean chosen = i == sel;
        boolean hot = Sk.in(lmx, lmy, x, y, CW, CH);
        String rar = rarity(it.price);
        int rc = rarityColor(rar);
        if (chosen) Gfx.rrect(ms, x - 1.5f, y - 1.5f, CW + 3, CH + 3, 6, lk ? Sk.FAINT : HOT);
        Gfx.rrect(ms, x, y, CW, CH, 5, lk ? 0xFF10141B : (chosen || hot ? CARD_HOT : CARD));
        Gfx.rrect(ms, x + 8, y + 1, CW - 16, 1.5f, 0, lk ? Sk.FAINT : rc);
        // iconita
        Gfx.rrect(ms, x + CW / 2f - 20, y + 7, 40, 34, 4, 0xFF0D1117);
        if (!it.stack.isEmpty() && a > 0.6f) {
            ms.pose().pushPose();
            ms.pose().translate(x + CW / 2f - 16, y + 8, 0);
            ms.pose().scale(2f, 2f, 1f);
            ms.renderItem(it.stack, 0, 0);
            ms.pose().popPose();
        } else if (it.stack.isEmpty()) {
            Gfx.text(ms, "?", x + CW / 2f, y + 31, 12, Sk.MUTED, 1, true, 0f);
        }
        if (lk) {
            Gfx.rrect(ms, x + CW / 2f - 20, y + 7, 40, 34, 4, 0xAA070C14);
            lock(ms, x + CW / 2f - 5, y + 18, Sk.MUTED);
        }
        Gfx.text(ms, clip(it.name, 7.6f, CW - 10), x + CW / 2f, y + 52, 7.6f, lk ? Sk.MUTED : Sk.WHITE, 1, true, 0f);
        Gfx.text(ms, rar, x + 6, y + 63, 5.6f, lk ? Sk.FAINT : rc, 0, true, 0.7f);
        Gfx.text(ms, "STOC: INF", x + CW - 6, y + 63, 5.6f, Sk.MUTED, 2, false, 0.5f);
        boolean ed = editMode();
        if (ed) {
            boolean he = Sk.in(lmx, lmy, x + 3, y + 3, 44, 13);
            boolean hd = Sk.in(lmx, lmy, x + CW - 47, y + 3, 44, 13);
            Gfx.rrect(ms, x + 3, y + 3, 44, 13, 2, he ? HOT : 0xE0061426);
            Gfx.text(ms, "EDIT", x + 25, y + 12.2f, 6.6f, he ? 0xFF160A04 : HOT, 1, true, 0.5f);
            Gfx.rrect(ms, x + CW - 47, y + 3, 44, 13, 2, hd ? Sk.BAD : 0xE0061426);
            Gfx.text(ms, "ȘTERGE", x + CW - 25, y + 12.2f, 6.6f, hd ? 0xFF061426 : Sk.BAD, 1, true, 0.5f);
        }
        // pret + buton CUMPARA
        boolean canBuy = !lk && !ow;
        float bw = CW - 12;
        boolean hb = canBuy && Sk.in(lmx, lmy, x + 6, y + CH - 18, bw, 14);
        Gfx.rrect(ms, x + 6, y + CH - 18, bw, 14, 3, ow ? 0xFF16301F : (lk ? 0xFF1A1F29 : (hb ? 0xFFFF7A52 : HOT)));
        String lab = ow ? "DEȚINUT" : (lk ? "BLOCAT" : "CUMPĂRĂ  " + money(it.price));
        Gfx.text(ms, clip(lab, 6.6f, bw - 6), x + 6 + bw / 2f, y + CH - 8.2f, 6.6f, ow ? Sk.GOOD : (lk ? Sk.FAINT : 0xFF160A04), 1, true, 0.4f);
        if (canBuy) {
            final int fi = i;
            hitL(x + 6, y + CH - 18, bw, 14, () -> {
                select(fi);
                if (!editMode()) confirm = true;
            });
        }
        if (it.amount > 1 && !ed) {
            String b = "x" + it.amount;
            float bw2 = Gfx.width(b, 6.4f, false) + 8;
            Gfx.rrect(ms, x + CW - bw2 - 3, y + 3, bw2, 11, 2, 0xB0061426);
            Gfx.text(ms, b, x + CW - bw2 / 2f - 3, y + 11.2f, 6.4f, Sk.WHITE, 1);
        }
        if (!it.permit.isEmpty() && !ow && !ed) {
            Gfx.rrect(ms, x + 3, y + 3, 34, 11, 2, 0xB0061426);
            Gfx.text(ms, "PERMIS", x + 20, y + 11.2f, 6f, Sk.WARN, 1, true, 0.4f);
        }
    }

    /** Lacat mic (10x12). */
    private static void lock(GuiGraphics ms, float x, float y, int c) {
        Gfx.line(ms, x + 2, y + 5, x + 2, y + 2.5f, 1.3f, c);
        Gfx.line(ms, x + 2, y + 2.5f, x + 3.6f, y + 0.6f, 1.3f, c);
        Gfx.line(ms, x + 3.6f, y + 0.6f, x + 6.4f, y + 0.6f, 1.3f, c);
        Gfx.line(ms, x + 6.4f, y + 0.6f, x + 8, y + 2.5f, 1.3f, c);
        Gfx.line(ms, x + 8, y + 2.5f, x + 8, y + 5, 1.3f, c);
        Gfx.rrect(ms, x - 1, y + 5, 10, 7, 1.5f, c);
    }

    private void bottomBar(GuiGraphics ms, float lmx, float lmy) {
        float by = H - 56;
        Gfx.rrect(ms, GX - 4, by - 6, W - GX - 8, 40, 5, 0xFF10141B);
        if (editMode()) {
            editBar(ms, by, lmx, lmy);
            return;
        }
        It it = selected();
        if (it == null) {
            Gfx.text(ms, "Alege un articol (săgeți sau click).", GX + 10, by + 18, 8.6f, Sk.FAINT, 0);
            return;
        }
        boolean lk = locked(it);
        boolean ow = owned(it);
        boolean single = !it.permit.isEmpty();
        Gfx.text(ms, clip(it.name, 10f, 140), GX + 10, by + 8, 10, Sk.WHITE, 0, true, 0f);
        if (lk) Gfx.text(ms, clip(lockText(it), 7f, 150), GX + 10, by + 21, 7f, Sk.WARN, 0, true, 0.2f);
        else if (!it.desc.isEmpty()) Gfx.text(ms, clip(it.desc, 7.2f, 150), GX + 10, by + 21, 7.2f, Sk.MUTED, 0);
        if (!single && !lk && !ow) {
            float qx = W - 290;
            step(ms, qx, by - 1, "-", lmx, lmy, () -> qty = Math.max(1, qty - 1));
            Gfx.text(ms, String.valueOf(qty), qx + 40, by + 15, 11, Sk.WHITE, 1, true, 0f);
            step(ms, qx + 58, by - 1, "+", lmx, lmy, () -> qty = Math.min(64, qty + 1));
        }
        boolean ok = !lk && !ow;
        double total = it.price * (single ? 1 : qty);
        String label = ow ? "DEȚINUT" : (lk ? "BLOCAT" : "CUMPĂRĂ  " + money(total) + " RON");
        boolean hb = ok && Sk.in(lmx, lmy, W - 176, by - 3, 156, 28);
        Gfx.rrect(ms, W - 176, by - 3, 156, 28, 4, ow ? 0xFF16301F : (lk ? 0xFF1A1F29 : (hb ? 0xFFFF7A52 : HOT)));
        Gfx.text(ms, label, W - 98, by + 14.4f, 8f, ow ? Sk.GOOD : (lk ? Sk.FAINT : 0xFF160A04), 1, true, 0.4f);
        if (ok) hitL(W - 176, by - 3, 156, 28, () -> confirm = true);
    }

    private void step(GuiGraphics ms, float x, float y, String label, float lmx, float lmy, Runnable r) {
        boolean hv = Sk.in(lmx, lmy, x, y, 22, 22);
        Gfx.rrect(ms, x, y, 22, 22, 0, hv ? Sk.ACC : 0xFF14202F);
        Gfx.text(ms, label, x + 11, y + 15.5f, 11, hv ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
        hitL(x, y, 22, 22, r);
    }

    /** Dialog de confirmare a cumpararii (blocheaza clickurile de dedesubt). */
    private void dialog(GuiGraphics ms, float lmx, float lmy) {
        final It it = selected();
        if (it == null) {
            confirm = false;
            return;
        }
        int q = it.permit.isEmpty() ? qty : 1;
        hit(0, 0, width, height, () -> { });
        Gfx.rrect(ms, -3000, -3000, 7000, 7000, 0, 0xB0050A14);
        float bw = 300f;
        float bh = 132f;
        float bx = W / 2f - bw / 2f;
        float by = H / 2f - bh / 2f;
        Gfx.rrect(ms, bx - 1, by - 1, bw + 2, bh + 2, 9, 0x40FFFFFF);
        Gfx.rrect(ms, bx, by, bw, bh, 8, 0xFF14202F, 0xFF0B1320);
        Gfx.rrect(ms, bx + 16, by + 1, 36, 2, 0, HOT);
        Gfx.text(ms, "CONFIRMI CUMPĂRAREA?", bx + 16, by + 22, 11, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, clip(q + " x " + it.name, 8f, bw - 32), bx + 16, by + 40, 8f, Sk.MUTED, 0);
        Gfx.text(ms, "Total de plată", bx + 16, by + 66, 6.4f, Sk.FAINT, 0);
        Gfx.text(ms, money(it.price * q) + " RON", bx + bw - 16, by + 68, 14, Sk.WHITE, 2, true, 0f);
        Gfx.text(ms, "Alegi cash sau card la pasul următor.", bx + 16, by + 82, 6.6f, Sk.FAINT, 0);
        btn(ms, "ANULEAZĂ", bx + 16, by + 92, 124, 28, false, lmx, lmy, () -> confirm = false);
        btn(ms, "CONFIRMĂ", bx + bw - 140, by + 92, 124, 28, true, lmx, lmy, this::doBuy);
    }

    private void btn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean filled, float lmx, float lmy, Runnable r) {
        Sk.button(ms, label, x, y, w, h, true, filled, Sk.in(lmx, lmy, x, y, w, h));
        hitL(x, y, w, h, r);
    }

    // ------------------------------------------------------------------ editor

    private void drawAddCard(GuiGraphics ms, float x, float y, float lmx, float lmy) {
        boolean hot = Sk.in(lmx, lmy, x, y, CW, CH);
        Gfx.rrect(ms, x, y, CW, CH, 5, hot ? CARD_HOT : 0x80141922);
        int c = hot ? HOT : Sk.MUTED;
        Gfx.line(ms, x + CW / 2f - 9, y + 34, x + CW / 2f + 9, y + 34, 2.2f, c);
        Gfx.line(ms, x + CW / 2f, y + 25, x + CW / 2f, y + 43, 2.2f, c);
        Gfx.text(ms, "ADAUGĂ ARTICOL", x + CW / 2f, y + 62, 7.2f, hot ? Sk.WHITE : Sk.MUTED, 1, true, 0.5f);
    }

    private void editBar(GuiGraphics ms, float by, float lmx, float lmy) {
        boolean has = cat >= 0 && cat < cats.size();
        String cn = has ? catNames.get(cat) : "—";
        Gfx.text(ms, "MOD EDITARE", GX + 10, by + 5, 6.4f, Sk.WARN, 0, true, 1.2f);
        Gfx.text(ms, clip("Categorie: " + cn + "  ·  " + items().size() + " articole", 8.6f, 140), GX + 10, by + 19, 8.6f, Sk.WHITE, 0);
        btnE(ms, "REDENUMEȘTE", W - 316, by - 3, 134, 28, has, false, lmx, lmy, this::openCatRename);
        btnE(ms, "ȘTERGE CATEGORIA", W - 176, by - 3, 146, 28, has, false, lmx, lmy, this::openDelCat);
    }

    private void btnE(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean filled, float lmx, float lmy, Runnable r) {
        Sk.button(ms, label, x, y, w, h, enabled, filled, enabled && Sk.in(lmx, lmy, x, y, w, h));
        if (enabled) hitL(x, y, w, h, r);
    }

    private static String num(double v) {
        return v == Math.floor(v) && Math.abs(v) < 1e12 ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void openItemForm(int idx) {
        if (!editMode() || realCat() < 0) {
            result(false, "Creează mai întâi o categorie (tab-ul +).");
            return;
        }
        List<It> l = items();
        modalNew = idx < 0 || idx >= l.size();
        editIdx = modalNew ? -1 : idx;
        formReq.clear();
        if (modalNew) {
            formPermit = false;
            fName.setValue("");
            fItem.setValue("");
            fPrice.setValue("");
            fAmount.setValue("1");
            fDesc.setValue("");
            fCmd.setValue("");
        } else {
            It it = l.get(idx);
            formPermit = !it.permit.isEmpty();
            fName.setValue(it.name);
            fItem.setValue(it.itemId);
            fPrice.setValue(num(it.price));
            fAmount.setValue(String.valueOf(it.amount));
            fDesc.setValue(it.desc);
            fCmd.setValue(String.join("; ", it.cmds));
            formReq.addAll(it.req);
        }
        confirm = false;
        modal = 1;
        focus(fName, true);
    }

    private void openDelItem(int idx) {
        if (idx < 0 || idx >= items().size()) return;
        editIdx = idx;
        modal = 3;
    }

    private void openCatNew() {
        modalNew = true;
        fCat.setValue("");
        confirm = false;
        modal = 2;
        focus(fCat, true);
    }

    private void openCatRename() {
        if (cat < 0 || cat >= catNames.size()) return;
        modalNew = false;
        fCat.setValue(catNames.get(cat));
        confirm = false;
        modal = 2;
        focus(fCat, true);
    }

    private void openDelCat() {
        if (cat < 0 || cat >= catNames.size()) return;
        modal = 4;
    }

    private void closeModal() {
        modal = 0;
        pendingUntil = 0;
        for (EditBox f : new EditBox[] {fName, fItem, fPrice, fAmount, fDesc, fCmd, fCat}) focus(f, false);
    }

    private void toggleReq(String id) {
        if (!formReq.remove(id)) formReq.add(id);
    }

    private void fromHand() {
        Minecraft mc = Minecraft.getInstance();
        ItemStack st = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        if (st.isEmpty()) {
            result(false, "Nu ții niciun item în mână.");
            return;
        }
        ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(st.getItem());
        if (rl == null) return;
        fItem.setValue(rl.toString());
        if (fName.getValue().trim().isEmpty()) fName.setValue(st.getHoverName().getString());
    }

    private boolean pending() {
        return System.currentTimeMillis() < pendingUntil;
    }

    private void submitItem() {
        if (pending() || realCat() < 0) return;
        String name = fName.getValue().trim();
        if (name.isEmpty()) {
            result(false, "Numele articolului lipsește.");
            return;
        }
        double price;
        try {
            price = Double.parseDouble(fPrice.getValue().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            price = -1;
        }
        if (!(price > 0)) {
            result(false, "Preț invalid (trebuie să fie mai mare ca 0).");
            return;
        }
        int amount = 1;
        if (!formPermit) {
            try {
                amount = Integer.parseInt(fAmount.getValue().trim());
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1 || amount > 999) {
                result(false, "Cantitate invalidă (1-999).");
                return;
            }
            String id = fItem.getValue().trim();
            if (id.indexOf(':') <= 0) {
                result(false, "Id item invalid (ex. minecraft:bread). Folosește DIN MÂNĂ.");
                return;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("op", modalNew ? "addItem" : "setItem");
        o.addProperty("cat", realCat());
        o.addProperty("idx", editIdx);
        o.addProperty("name", name);
        o.addProperty("item", fItem.getValue().trim());
        o.addProperty("price", price);
        o.addProperty("amount", amount);
        o.addProperty("desc", fDesc.getValue().trim());
        JsonArray rq = new JsonArray();
        for (String r : formReq) rq.add(new com.google.gson.JsonPrimitive(r));
        o.add("req", rq);
        if (cmdOk && !formPermit) {
            JsonArray ca = new JsonArray();
            for (String c : fCmd.getValue().split(";")) {
                String t = c.trim();
                if (!t.isEmpty()) ca.add(new com.google.gson.JsonPrimitive(t));
            }
            o.add("cmd", ca);
        }
        pendingUntil = System.currentTimeMillis() + 6000;
        ShopClient.edit(o);
    }

    private void submitCat() {
        if (pending()) return;
        String n = fCat.getValue().trim();
        if (n.isEmpty()) {
            result(false, "Numele categoriei lipsește.");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("op", modalNew ? "addCat" : "renCat");
        o.addProperty("cat", realCat());
        o.addProperty("name", n);
        pendingUntil = System.currentTimeMillis() + 6000;
        ShopClient.edit(o);
    }

    private void submitDelete() {
        JsonObject o = new JsonObject();
        if (modal == 3) {
            o.addProperty("op", "delItem");
            o.addProperty("cat", realCat());
            o.addProperty("idx", editIdx);
        } else {
            o.addProperty("op", "delCat");
            o.addProperty("cat", realCat());
        }
        ShopClient.edit(o);
        closeModal();
        sel = -1;
    }

    private void submitModal() {
        if (modal == 1) submitItem();
        else if (modal == 2) submitCat();
        else if (modal == 3 || modal == 4) submitDelete();
    }

    @Override
    protected List<EditBox> fields() {
        List<EditBox> l = new ArrayList<>();
        if (modal == 1) {
            l.add(fName);
            if (!formPermit) {
                l.add(fItem);
                l.add(fPrice);
                l.add(fAmount);
            } else {
                l.add(fPrice);
            }
            l.add(fDesc);
            if (cmdOk && !formPermit) l.add(fCmd);
        } else if (modal == 2) {
            l.add(fCat);
        }
        return l;
    }

    @Override
    protected void onEnter(EditBox field) {
        submitModal();
    }

    private boolean modalKey(int key, int scan, int mods) {
        List<EditBox> fs = fields();
        for (int i = 0; i < fs.size(); i++) {
            EditBox f = fs.get(i);
            if (!f.isFocused()) continue;
            if (key == GLFW.GLFW_KEY_TAB) {
                int next = (i + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? fs.size() - 1 : 1)) % fs.size();
                focus(f, false);
                focus(fs.get(next), true);
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            closeModal();
        } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submitModal();
        } else if (key == GLFW.GLFW_KEY_TAB && !fs.isEmpty()) {
            focus(fs.get(0), true);
        }
        return true;
    }

    /** Camp de text cu fundal in stilul iarna; pozitia in pixeli de ecran se calculeaza din coordonatele de design. */
    private void fieldBox(GuiGraphics ms, EditBox f, float x, float y, float w, float h, boolean enabled) {
        Sk.slant(ms, x, y, w, h, 4f, enabled ? Sk.ROW : 0x60111A28);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, enabled && f.isFocused() ? Sk.ACC : 0xFF22344D);
        f.setX(Math.round(offX + (x + 6f) * scale));
        f.setY(Math.round(offY + (y + (h - 8f) / 2f) * scale));
        f.setWidth(Math.max(10, Math.round((w - 12f) * scale)));
    }

    private void label(GuiGraphics ms, String t, float x, float y) {
        Gfx.text(ms, t, x, y, 5.8f, Sk.MUTED, 0, true, 0.9f);
    }

    private void overlay(GuiGraphics ms) {
        hit(0, 0, width, height, () -> { });
        Gfx.rrect(ms, -3000, -3000, 7000, 7000, 0, 0xB0050A14);
    }

    private void frame(GuiGraphics ms, float bx, float by, float bw, float bh) {
        Gfx.rrect(ms, bx - 1, by - 1, bw + 2, bh + 2, 9, 0x40FFFFFF);
        Gfx.rrect(ms, bx, by, bw, bh, 8, 0xFF14202F, 0xFF0B1320);
        Gfx.rrect(ms, bx + 16, by + 1, 36, 2, 0, HOT);
    }

    private void itemForm(GuiGraphics ms, float lmx, float lmy) {
        overlay(ms);
        float bw = 400f;
        float bh = 244f;
        float bx = W / 2f - bw / 2f;
        float by = H / 2f - bh / 2f;
        frame(ms, bx, by, bw, bh);
        Gfx.text(ms, modalNew ? "ARTICOL NOU" : "EDITEAZĂ ARTICOLUL", bx + 16, by + 22, 11, Sk.WHITE, 0, true, 0f);
        String cn = cat >= 0 && cat < catNames.size() ? catNames.get(cat) : "";
        Gfx.text(ms, clip("Categoria: " + cn, 6.8f, 150), bx + bw - 16, by + 21, 6.8f, Sk.MUTED, 2);

        label(ms, "NUME", bx + 16, by + 36);
        fieldBox(ms, fName, bx + 16, by + 40, 368, 16, true);

        label(ms, formPermit ? "ITEM (articol-permis: fix)" : "ITEM  (id: minecraft:bread, tac:ak47 ...)", bx + 16, by + 66);
        fieldBox(ms, fItem, bx + 16, by + 70, 268, 16, !formPermit);
        if (formPermit) Gfx.text(ms, clip(fItem.getValue(), 7f, 250), bx + 22, by + 81, 7f, Sk.FAINT, 0);
        else {
            boolean hh = Sk.in(lmx, lmy, bx + 292, by + 70, 92, 16);
            Sk.slant(ms, bx + 292, by + 70, 92, 16, 4f, hh ? Sk.ACC : 0xFF14202F);
            Gfx.text(ms, "DIN MÂNĂ", bx + 292 + 46 + 2, by + 81, 6.8f, hh ? 0xFF061426 : Sk.ACC, 1, true, 0.6f);
            hitL(bx + 292, by + 70, 92, 16, this::fromHand);
        }

        label(ms, "PREȚ (RON)", bx + 16, by + 96);
        fieldBox(ms, fPrice, bx + 16, by + 100, 110, 16, true);
        label(ms, "CANTITATE", bx + 138, by + 96);
        fieldBox(ms, fAmount, bx + 138, by + 100, 90, 16, !formPermit);
        if (formPermit) Gfx.text(ms, "1", bx + 144, by + 111, 7f, Sk.FAINT, 0);

        label(ms, "DESCRIERE", bx + 16, by + 126);
        fieldBox(ms, fDesc, bx + 16, by + 130, 368, 16, true);

        label(ms, "NECESITĂ PERMIS (opțional, click = pornit/oprit)", bx + 16, by + 156);
        float cx = bx + 16;
        float cyy = by + 160;
        if (permits.isEmpty()) Gfx.text(ms, "(niciun permis definit în permits.yml)", cx, cyy + 9.5f, 6.6f, Sk.FAINT, 0);
        for (String[] pr : permits) {
            String lab = clip(pr[1].isEmpty() ? pr[0] : pr[1], 6.6f, 100);
            float w = Gfx.width(lab, 6.6f, false) + 14;
            if (cx + w > bx + bw - 12) break;
            boolean on = formReq.contains(pr[0]);
            boolean hv = Sk.in(lmx, lmy, cx, cyy, w, 13);
            Sk.slant(ms, cx, cyy, w, 13, 4f, on ? Sk.ACC : (hv ? Sk.ROW_HOT : Sk.ROW));
            Gfx.text(ms, lab, cx + w / 2f + 2, cyy + 9.4f, 6.6f, on ? 0xFF061426 : Sk.WHITE, 1);
            final String pid = pr[0];
            hitL(cx, cyy, w, 13, () -> toggleReq(pid));
            cx += w + 4;
        }

        if (cmdOk && !formPermit) {
            label(ms, "COMENZI din consolă (opțional, separate prin ;  —  %player%, %amount%)", bx + 16, by + 182);
            fieldBox(ms, fCmd, bx + 16, by + 186, 368, 16, true);
        } else if (!cmdOk && !formPermit) {
            Gfx.text(ms, "Comenzile pe articole pot fi setate doar de op.", bx + 16, by + 195, 6.4f, Sk.FAINT, 0);
        }

        btnE(ms, "ANULEAZĂ", bx + 16, by + bh - 36, 124, 28, true, false, lmx, lmy, this::closeModal);
        btnE(ms, pending() ? "SE SALVEAZĂ…" : "SALVEAZĂ", bx + bw - 140, by + bh - 36, 124, 28, !pending(), true, lmx, lmy, this::submitItem);
    }

    private void catForm(GuiGraphics ms, float lmx, float lmy) {
        overlay(ms);
        float bw = 300f;
        float bh = 130f;
        float bx = W / 2f - bw / 2f;
        float by = H / 2f - bh / 2f;
        frame(ms, bx, by, bw, bh);
        Gfx.text(ms, modalNew ? "CATEGORIE NOUĂ" : "REDENUMEȘTE CATEGORIA", bx + 16, by + 22, 11, Sk.WHITE, 0, true, 0f);
        label(ms, "NUME", bx + 16, by + 42);
        fieldBox(ms, fCat, bx + 16, by + 46, bw - 32, 16, true);
        btnE(ms, "ANULEAZĂ", bx + 16, by + bh - 40, 124, 28, true, false, lmx, lmy, this::closeModal);
        btnE(ms, pending() ? "SE SALVEAZĂ…" : "SALVEAZĂ", bx + bw - 140, by + bh - 40, 124, 28, !pending(), true, lmx, lmy, this::submitCat);
    }

    private void delDialog(GuiGraphics ms, float lmx, float lmy) {
        overlay(ms);
        float bw = 300f;
        float bh = 124f;
        float bx = W / 2f - bw / 2f;
        float by = H / 2f - bh / 2f;
        frame(ms, bx, by, bw, bh);
        if (modal == 3) {
            It it = editIdx >= 0 && editIdx < items().size() ? items().get(editIdx) : null;
            Gfx.text(ms, "ȘTERGI ARTICOLUL?", bx + 16, by + 22, 11, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, clip(it == null ? "" : it.name, 8.4f, bw - 32), bx + 16, by + 42, 8.4f, Sk.MUTED, 0);
        } else {
            String cn = cat >= 0 && cat < catNames.size() ? catNames.get(cat) : "";
            Gfx.text(ms, "ȘTERGI CATEGORIA?", bx + 16, by + 22, 11, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, clip("„" + cn + "” și cele " + items().size() + " articole din ea", 8f, bw - 32), bx + 16, by + 42, 8f, Sk.MUTED, 0);
        }
        Gfx.text(ms, "Acțiunea nu poate fi anulată.", bx + 16, by + 62, 6.6f, Sk.FAINT, 0);
        btnE(ms, "ANULEAZĂ", bx + 16, by + bh - 40, 124, 28, true, false, lmx, lmy, this::closeModal);
        btnE(ms, "ȘTERGE", bx + bw - 140, by + bh - 40, 124, 28, true, true, lmx, lmy, this::submitDelete);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
