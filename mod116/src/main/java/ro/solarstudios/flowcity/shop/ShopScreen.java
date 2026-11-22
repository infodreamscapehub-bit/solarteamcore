package ro.solarstudios.flowcity.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * E-shop deschis de un NPC, refacut in tema de iarna FlowCity: antet cu breadcrumb si solduri, categorii ca tab-uri cu
 * colturi taiate, grila de carduri cu iconita itemului (orice item din orice mod), articole blocate de permis (gri, cu
 * lacat), cantitate + CUMPARA cu dialog de confirmare. Plata (cash sau card) vine din PayScreen. Protocolul cu pluginul
 * ramane acelasi (buy: cat, idx, qty); in plus pluginul trimite solduri si permisele jucatorului (open/state).
 * Taste: sageti = alegi articolul, Enter = cumperi/confirmi, Tab / 1-9 = categorii, + / - = cantitate, Esc = inchide.
 */
public final class ShopScreen extends FlowScreen {
    private static final int W = 480;
    private static final int H = 300;
    private static final int COLS = 4;
    private static final int CW = 101;
    private static final int CH = 68;
    private static final int GAP = 8;
    private static final int GX = 22;
    private static final int GY = 86;
    private static final int GH = 146;
    private static final int VIS_ROWS = 2;

    private static final class It {
        String name;
        double price;
        int amount;
        String desc;
        String permit = "";
        final List<String> req = new ArrayList<>();
        final List<String> reqn = new ArrayList<>();
        ItemStack stack = ItemStack.EMPTY;
    }

    private final String title;
    private final List<String> catNames = new ArrayList<>();
    private final List<List<It>> cats = new ArrayList<>();
    private final Set<String> have = new HashSet<>();
    private double cash = -1;
    private double bank = -1;
    private int cat;
    private int sel = -1;
    private int qty = 1;
    private int scrollRow;
    private float scrollY;
    private float tabX = -1f;
    private boolean confirm;
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private final long born = System.currentTimeMillis();
    private long animStart = born;
    private float offX;
    private float offY;
    private float scale = 1f;

    ShopScreen(JsonObject d) {
        super("Magazin", W, H);
        this.title = JsonClientModule.str(d, "title", "MAGAZIN");
        readState(d);
        JsonArray ca = d.has("cats") && d.get("cats").isJsonArray() ? d.getAsJsonArray("cats") : new JsonArray();
        for (JsonElement ce : ca) {
            JsonObject co = ce.getAsJsonObject();
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
                if (io.has("req") && io.get("req").isJsonArray()) {
                    for (JsonElement e : io.getAsJsonArray("req")) it.req.add(e.getAsString());
                }
                if (io.has("reqn") && io.get("reqn").isJsonArray()) {
                    for (JsonElement e : io.getAsJsonArray("reqn")) it.reqn.add(e.getAsString());
                }
                try {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(JsonClientModule.str(io, "item", "minecraft:stone")));
                    if (item != null) it.stack = new ItemStack(item, Math.min(64, it.amount));
                } catch (RuntimeException ignored) {
                    // id invalid: ramane fara iconita
                }
                list.add(it);
            }
            cats.add(list);
        }
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

    private int rowsTotal() {
        return (items().size() + COLS - 1) / COLS;
    }

    private int maxRow() {
        return Math.max(0, rowsTotal() - VIS_ROWS);
    }

    private void setCat(int c) {
        if (c < 0 || c >= cats.size() || c == cat) return;
        cat = c;
        sel = -1;
        scrollRow = 0;
        scrollY = 0;
        qty = 1;
        confirm = false;
        animStart = System.currentTimeMillis();
    }

    private void select(int i) {
        sel = i;
        qty = 1;
        int row = i / COLS;
        if (row < scrollRow) scrollRow = row;
        else if (row >= scrollRow + VIS_ROWS) scrollRow = row - VIS_ROWS + 1;
        scrollRow = Math.max(0, Math.min(maxRow(), scrollRow));
    }

    private void doBuy() {
        It it = selected();
        if (!buyable(it)) return;
        confirm = false;
        ShopClient.buy(cat, sel, it.permit.isEmpty() ? qty : 1);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (confirm) return true;
        scrollRow = Math.max(0, Math.min(maxRow(), scrollRow - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
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
                if (buyable(selected())) confirm = true;
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
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy, open);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy, float open) {
        long now = System.currentTimeMillis();
        // antet: breadcrumb + titlu + solduri
        Gfx.text(ms, "FLOWCITY  ›  " + title, 17, 27, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Gfx.text(ms, title, 16, 45, 17, Sk.WHITE, 0, true, 0f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        if (bank >= 0 || cash >= 0) {
            float rx = W - 44;
            if (bank >= 0) {
                String b = "CARD  " + money(bank) + " RON";
                Gfx.text(ms, b, rx, 41, 6.8f, Sk.WHITE, 2, false, 0.4f);
                rx -= Gfx.width(b, 6.8f, false) + 16;
            }
            if (cash >= 0) Gfx.text(ms, "CASH  " + money(cash) + " RON", rx, 41, 6.8f, Sk.MUTED, 2, false, 0.4f);
        }

        // tab-uri de categorii
        int nc = Math.max(1, catNames.size());
        float tw = Math.min(104f, (W - 32f - (nc - 1) * 4f) / nc);
        float targetX = 16 + cat * (tw + 4);
        if (tabX < 0) tabX = targetX;
        tabX += (targetX - tabX) * 0.28f;
        float ty = 54;
        for (int i = 0; i < catNames.size(); i++) {
            float x = 16 + i * (tw + 4);
            boolean hot = Sk.in(lmx, lmy, x, ty, tw, 22);
            Sk.slant(ms, x, ty, tw, 22, 6f, hot ? Sk.ROW_HOT : Sk.ROW);
        }
        Sk.slant(ms, tabX, ty, tw, 22, 6f, Sk.ACC);
        for (int i = 0; i < catNames.size(); i++) {
            float x = 16 + i * (tw + 4);
            boolean on = Math.abs(tabX - x) < tw / 2f;
            boolean hot = Sk.in(lmx, lmy, x, ty, tw, 22);
            Gfx.text(ms, clip(catNames.get(i), 8.2f, tw - 14), x + tw / 2f + 3, ty + 14.6f, 8.2f, on ? 0xFF061426 : (hot ? Sk.WHITE : Sk.MUTED), 1, true, 0.3f);
            final int ci = i;
            hitL(x, ty, tw, 22, () -> setCat(ci));
        }

        // grila de carduri
        List<It> its = items();
        Gfx.rrect(ms, GX - 6, GY - 4, W - 2 * (GX - 6), GH + 8, 0, 0x14FFFFFF);
        scrollY += (scrollRow * (CH + GAP) - scrollY) * 0.3f;
        for (int i = 0; i < its.size(); i++) {
            int col = i % COLS;
            int row = i / COLS;
            float x = GX + col * (CW + GAP);
            float y = GY + row * (CH + GAP) - scrollY;
            if (y + CH < GY || y > GY + GH) continue;
            float edge = clamp01(Math.min(y + CH - GY, GY + GH - y) / 18f);
            float t = easeOut((now - animStart - 80 - i * 35L) / 260f);
            if (t <= 0f) continue;
            float a = open * t * edge;
            if (a <= 0.02f) continue;
            float yy = y + (1f - t) * 10f;
            Gfx.alpha = a;
            drawCard(ms, its.get(i), i, x, yy, lmx, lmy, a);
            Gfx.alpha = open;
            final int fi = i;
            float hy = Math.max(y, GY);
            float hh = Math.min(y + CH, GY + GH) - hy;
            if (hh > 4) hitL(x, hy, CW, hh, () -> select(fi));
        }
        if (its.isEmpty()) Gfx.text(ms, "Nimic de vândut aici.", W / 2f, GY + 76, 8.4f, Sk.MUTED, 1);
        if (maxRow() > 0) {
            float total = rowsTotal() * (CH + GAP) - GAP;
            float th = Math.max(20, GH * GH / total);
            float tp = GY + (GH - th) * (scrollY / (maxRow() * (CH + GAP)));
            Gfx.rrect(ms, W - 21, GY, 2, GH, 0, 0xFF14202F);
            Gfx.rrect(ms, W - 21, tp, 2, th, 0, Sk.ACC);
        }

        bottomBar(ms, lmx, lmy);

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
        if (confirm) dialog(ms, lmx, lmy);
    }

    private void drawCard(GuiGraphics ms, It it, int i, float x, float y, float lmx, float lmy, float a) {
        boolean lk = locked(it);
        boolean ow = owned(it);
        boolean chosen = i == sel;
        boolean hot = Sk.in(lmx, lmy, x, y, CW, CH) && lmy >= GY && lmy < GY + GH;
        if (chosen) Sk.cut(ms, x - 2, y - 2, CW + 4, CH + 4, 10, lk ? Sk.FAINT : Sk.ACC);
        Sk.cut(ms, x, y, CW, CH, 8, lk ? 0x90101620 : (chosen || hot ? Sk.ROW_HOT : Sk.ROW));
        if (!it.stack.isEmpty() && a > 0.6f) {
            ms.pose().pushPose();
            ms.pose().translate(x + CW / 2f - 16, y + 5, 0);
            ms.pose().scale(2f, 2f, 1f);
            ms.renderItem(it.stack, 0, 0);
            ms.pose().popPose();
        } else if (it.stack.isEmpty()) {
            Gfx.disc(ms, x + CW / 2f, y + 21, 12, 0xFF14202F);
            Gfx.text(ms, "?", x + CW / 2f, y + 26, 12, Sk.MUTED, 1, true, 0f);
        }
        if (lk) {
            Gfx.rrect(ms, x + CW / 2f - 19, y + 3, 38, 36, 4, 0xAA070C14);
            lock(ms, x + CW / 2f - 5, y + 14, Sk.MUTED);
        }
        String nm = clip(it.name, 7.6f, CW - 10);
        Gfx.text(ms, nm, x + CW / 2f, y + 48, 7.6f, lk ? Sk.MUTED : Sk.WHITE, 1);
        if (ow) {
            Gfx.text(ms, "DEȚINUT", x + CW / 2f, y + 60, 7f, Sk.GOOD, 1, true, 0.6f);
        } else {
            Gfx.text(ms, money(it.price) + " RON", x + CW / 2f, y + 60, 7.2f, lk ? Sk.FAINT : Sk.ACC, 1);
        }
        if (it.amount > 1) {
            String b = "x" + it.amount;
            float bw = Gfx.width(b, 6.4f, false) + 8;
            Gfx.rrect(ms, x + CW - bw - 3, y + 3, bw, 11, 2, 0xB0061426);
            Gfx.text(ms, b, x + CW - bw / 2f - 3, y + 11.2f, 6.4f, Sk.WHITE, 1);
        }
        if (!it.permit.isEmpty() && !ow) {
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
        Sk.slant(ms, 12, by, W - 36, 40, 8f, Sk.PANEL);
        It it = selected();
        if (it == null) {
            Gfx.text(ms, "Alege un articol (săgeți sau click).", 32, by + 24, 8.6f, Sk.FAINT, 0);
            return;
        }
        boolean lk = locked(it);
        boolean ow = owned(it);
        boolean single = !it.permit.isEmpty();
        Gfx.text(ms, clip(it.name, 10f, 190), 32, by + 17, 10, Sk.WHITE, 0, true, 0f);
        if (lk) Gfx.text(ms, clip(lockText(it), 7f, 200), 32, by + 30, 7f, Sk.WARN, 0, true, 0.2f);
        else if (!it.desc.isEmpty()) Gfx.text(ms, clip(it.desc, 7.2f, 200), 32, by + 30, 7.2f, Sk.MUTED, 0);
        if (!single && !lk && !ow) {
            float qx = W - 290;
            step(ms, qx, by + 9, "-", lmx, lmy, () -> qty = Math.max(1, qty - 1));
            Gfx.text(ms, String.valueOf(qty), qx + 40, by + 25, 11, Sk.WHITE, 1, true, 0f);
            step(ms, qx + 58, by + 9, "+", lmx, lmy, () -> qty = Math.min(64, qty + 1));
        }
        boolean ok = !lk && !ow;
        double total = it.price * (single ? 1 : qty);
        String label = ow ? "DEȚINUT" : (lk ? "BLOCAT" : "CUMPĂRĂ  " + money(total) + " RON");
        boolean hb = ok && Sk.in(lmx, lmy, W - 176, by + 6, 146, 28);
        Sk.button(ms, label, W - 176, by + 6, 146, 28, ok, true, hb);
        if (ok) hitL(W - 176, by + 6, 146, 28, () -> confirm = true);
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
        Gfx.rrect(ms, bx + 16, by + 1, 36, 2, 0, Sk.ACC);
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

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
