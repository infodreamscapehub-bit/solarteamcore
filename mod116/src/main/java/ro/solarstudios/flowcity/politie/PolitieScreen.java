package ro.solarstudios.flowcity.politie;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Hub "POLITIE": ecran de intampinare cu 3 carduri mari, fiecare cu submeniuri (acte, amenzi + masini, servicii).
 * Tema de iarna FlowCity (520x290, scalata).
 */
public final class PolitieScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int CX = 16;
    private static final int CW = W - CX - 14;
    private static final int TOP = 62;
    private static final int BOT = H - 12;
    private static final int ROW = 40;

    // vederi (continut)
    private static final int C_AMENZI = 0;
    private static final int C_CAZIER = 2;
    private static final int C_PLANGERI = 3;
    private static final int C_MASINI = 4;
    private static final int C_ANGAJARE = 5;
    private static final int C_SESIZARI = 6;
    private static final int V_PERMIS = 7;
    private static final int V_ARMA = 8;
    private static final int V_BULETIN = 9;

    // iconite
    private static final int I_DOC = 0;
    private static final int I_CAR = 1;
    private static final int I_CHAT = 2;
    private static final int I_ID = 3;
    private static final int I_SHIELD = 4;
    private static final int I_FOLDER = 5;
    private static final int I_CASE = 6;
    private static final int I_RECEIPT = 7;

    private static final String[] MENU_TITLE = {"ACTE ȘI DOCUMENTE", "AMENZI ȘI MAȘINI", "SERVICII"};

    private static final class Item {
        final String title;
        final String desc;
        final int view;
        final int icon;
        final int badge;
        final int badgeColor;

        Item(String title, String desc, int view, int icon, int badge, int badgeColor) {
            this.title = title;
            this.desc = desc;
            this.view = view;
            this.icon = icon;
            this.badge = badge;
            this.badgeColor = badgeColor;
        }
    }

    private JsonObject d = new JsonObject();
    private JsonObject cert;
    /** Sectiunea deschisa: -1 = ecranul principal, 0..2 = submeniu. */
    private int menu = -1;
    /** Vederea deschisa din submeniu: -1 = lista de carduri a submeniului. */
    private int view = -1;
    private int mainSel;
    private int subSel;
    private int scroll;
    private long navT = System.currentTimeMillis();
    private int navDir = 1;
    private final Set<String> picked = new LinkedHashSet<>();
    private int roleIdx;
    private final TextFieldWidget title = field("Titlu sesizare", 60, null);
    private final TextFieldWidget text = field("Descrie pe scurt ce s-a intamplat", 200, null);
    private final TextFieldWidget jobMsg = field("Mesaj pentru conducerea politiei", 200, null);
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    PolitieScreen(JsonObject data) {
        super("Politie", W, H);
        update(data);
    }

    void update(JsonObject data) {
        d = data;
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
        if (ok && m.contains("a fost trimisa")) {
            title.setValue("");
            text.setValue("");
            jobMsg.setValue("");
        }
    }

    void certificate(JsonObject c) {
        cert = c;
        menu = 0;
        view = C_CAZIER;
        scroll = 0;
        navDir = 1;
        navT = System.currentTimeMillis();
    }

    private boolean officer() {
        return JsonClientModule.bool(d, "officer", false);
    }

    private String playerName() {
        String n = JsonClientModule.str(d, "name", "");
        if (n.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) n = mc.player.getName().getString();
        }
        return n.isEmpty() ? "cetățean" : n;
    }

    private int unpaid() {
        return arr(d, "fines").size();
    }

    private int freshInbox() {
        int fresh = 0;
        for (JsonElement e : arr(d, "inbox")) if ("NOUA".equals(JsonClientModule.str(e.getAsJsonObject(), "status", ""))) fresh++;
        return fresh;
    }

    /** Cardurile principale (EXACT 3). */
    private List<Item> mainItems() {
        List<Item> l = new ArrayList<>();
        l.add(new Item("ACTE ȘI DOCUMENTE", "Permis, port-armă, cazier, duplicat", 0, I_DOC, 0, 0));
        l.add(new Item("AMENZI ȘI MAȘINI", "Amenzile tale și mașinile înmatriculate", 1, I_CAR, unpaid(), Sk.BAD));
        l.add(new Item("SERVICII", "Plângeri, sesizări și angajare", 2, I_CHAT, officer() ? freshInbox() : 0, Sk.ACC));
        return l;
    }

    /** Cardurile submeniului curent. */
    private List<Item> subItems(int m) {
        List<Item> l = new ArrayList<>();
        if (m == 0) {
            l.add(new Item("PERMIS DE CONDUCERE", "Cerere categorii și valabilitate", V_PERMIS, I_ID, 0, 0));
            l.add(new Item("PORT-ARMĂ", "Autorizație de port-armă", V_ARMA, I_SHIELD, 0, 0));
            l.add(new Item("CAZIER JUDICIAR", "Situația ta și certificatul", C_CAZIER, I_FOLDER, 0, 0));
            l.add(new Item("DUPLICAT PERMIS", "Înlocuiește un permis pierdut", V_PERMIS, I_DOC, 0, 0));
            l.add(new Item("BULETIN", "Se eliberează la Primărie", V_BULETIN, I_ID, 0, 0));
        } else if (m == 1) {
            l.add(new Item("AMENZILE MELE", "Lista, termene și plată", C_AMENZI, I_RECEIPT, unpaid(), Sk.BAD));
            l.add(new Item("MAȘINILE MELE", "Modele, numere, ITP și RCA", C_MASINI, I_CAR, 0, 0));
        } else {
            l.add(new Item("PLÂNGERI ȘI SESIZĂRI", "Anunță poliția despre o problemă", C_PLANGERI, I_CHAT, 0, 0));
            l.add(new Item("CERERE DE ANGAJARE", "Alătură-te echipei poliției", C_ANGAJARE, I_CASE, 0, 0));
            if (officer()) l.add(new Item("SESIZĂRI PRIMITE", "Preia și arhivează cererile", C_SESIZARI, I_FOLDER, freshInbox(), Sk.ACC));
        }
        return l;
    }

    private static String viewName(int v) {
        switch (v) {
            case C_AMENZI: return "AMENZILE MELE";
            case C_CAZIER: return "CAZIER JUDICIAR";
            case C_PLANGERI: return "PLÂNGERI ȘI SESIZĂRI";
            case C_MASINI: return "MAȘINILE MELE";
            case C_ANGAJARE: return "CERERE DE ANGAJARE";
            case C_SESIZARI: return "SESIZĂRI PRIMITE";
            case V_PERMIS: return "PERMIS DE CONDUCERE";
            case V_ARMA: return "PORT-ARMĂ";
            default: return "BULETIN";
        }
    }

    private static int colsFor(int n) {
        return n <= 3 ? Math.max(1, n) : 3;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    private void go(int m, int v, int dir) {
        menu = m;
        view = v;
        navDir = dir;
        navT = System.currentTimeMillis();
        scroll = 0;
        if (v != C_CAZIER) cert = null;
        for (TextFieldWidget f : new TextFieldWidget[] {title, text, jobMsg}) focus(f, false);
    }

    private void back() {
        if (view >= 0) go(menu, -1, -1);
        else if (menu >= 0) go(-1, -1, -1);
        else onClose();
    }

    private void activate(int i) {
        if (menu < 0) {
            subSel = 0;
            go(mainItems().get(i).view, -1, 1);
        } else {
            List<Item> l = subItems(menu);
            if (i >= 0 && i < l.size()) go(menu, l.get(i).view, 1);
        }
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        if (view == C_PLANGERI) {
            l.add(title);
            l.add(text);
        } else if (view == C_ANGAJARE) {
            l.add(jobMsg);
        }
        return l;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (TextFieldWidget f : fields()) if (f.isFocused()) return super.keyPressed(key, scan, mods);
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            back();
            return true;
        }
        if (view < 0) {
            List<Item> l = menu < 0 ? mainItems() : subItems(menu);
            int n = l.size();
            int cols = menu < 0 ? 3 : colsFor(n);
            int cur = menu < 0 ? mainSel : subSel;
            if (key == GLFW.GLFW_KEY_RIGHT) cur = (cur + 1) % n;
            else if (key == GLFW.GLFW_KEY_LEFT) cur = (cur - 1 + n) % n;
            else if (key == GLFW.GLFW_KEY_DOWN) cur = cur + cols < n ? cur + cols : cur;
            else if (key == GLFW.GLFW_KEY_UP) cur = cur - cols >= 0 ? cur - cols : cur;
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                activate(cur);
                return true;
            } else {
                return super.keyPressed(key, scan, mods);
            }
            if (menu < 0) mainSel = cur;
            else subSel = cur;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        submit(f == jobMsg);
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

    private static String money(long v) {
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
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

    private void chip(GuiGraphics ms, String label, float x, float y, float w, float h, boolean on, float lmx, float lmy, Runnable r) {
        boolean hov = Sk.in(lmx, lmy, x, y, w, h);
        Sk.slant(ms, x, y, w, h, 4f, on ? Sk.ACC : hov ? Sk.ROW_HOT : Sk.ROW);
        Gfx.text(ms, label, x + w / 2f, y + h / 2f + 3f, 7f, on ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
        hitL(x, y, w, h, r);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (view < 0) return true;
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
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
            content(ms, lmx, lmy);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        // campurile de text se deseneaza in coordonate de ecran, dupa restaurarea transformarii
        if (System.currentTimeMillis() - navT >= 240) for (TextFieldWidget f : fields()) f.render(ms.pose(), mx, my, 0);
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        if (menu >= 0) {
            boolean bh = Sk.in(lmx, lmy, W - 120, 14, 78, 20);
            Sk.slant(ms, W - 120, 14, 78, 20, 5f, bh ? Sk.ACC : 0xFF14202F);
            Gfx.text(ms, "‹ ÎNAPOI", W - 81, 28f, 7.6f, bh ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
            hitL(W - 120, 14, 78, 20, this::back);
            breadcrumb(ms);
        }

        float t = easeOut((System.currentTimeMillis() - navT) / 240f);
        float prevAlpha = Gfx.alpha;
        ms.pose().pushPose();
        ms.pose().translate(navDir * (1f - t) * 28f, 0, 0);
        Gfx.alpha = prevAlpha * t;
        try {
            if (view >= 0) viewBody(ms, lmx, lmy);
            else if (menu < 0) mainBody(ms, lmx, lmy);
            else subBody(ms, lmx, lmy);
        } finally {
            Gfx.alpha = prevAlpha;
            ms.pose().popPose();
        }

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, clip(msg, 8f, false, W - 90), W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    /** POLIȚIE › SECȚIUNE › VEDERE. */
    private void breadcrumb(GuiGraphics ms) {
        shield(ms, 16, 14, 18, 22, false);
        float x = 42;
        String[] parts = view >= 0 ? new String[] {"POLIȚIE", MENU_TITLE[menu], viewName(view)} : new String[] {"POLIȚIE", MENU_TITLE[menu]};
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            Gfx.text(ms, parts[i], x, 30f, last ? 11f : 8.6f, last ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            x += Gfx.width(parts[i], last ? 11f : 8.6f, true) + 6;
            if (!last) {
                Gfx.text(ms, "›", x, 30f, 9f, Sk.FAINT, 0, true, 0f);
                x += 12;
            }
        }
    }

    /** Insigna: scut simplu propriu (fara embleme oficiale). */
    private void shield(GuiGraphics ms, float x, float y, float w, float h, boolean letters) {
        Gfx.poly(ms, new float[] {x, y, x + w, y, x + w, y + h * 0.55f, x + w / 2f, y + h, x, y + h * 0.55f}, Sk.ACC);
        float m = Math.max(2f, w * 0.1f);
        Gfx.poly(ms, new float[] {x + m, y + m, x + w - m, y + m, x + w - m, y + h * 0.55f - m * 0.2f, x + w / 2f, y + h - m * 1.6f, x + m, y + h * 0.55f - m * 0.2f}, 0xFF0E1A2E);
        if (letters) {
            Gfx.text(ms, "FC", x + w / 2f, y + h * 0.5f, w * 0.34f, Sk.ACC, 1, true, 0f);
            Gfx.rrect(ms, x + w * 0.3f, y + h * 0.6f, w * 0.4f, 1.6f, 0, Sk.WHITE);
        } else {
            Gfx.rrect(ms, x + w * 0.3f, y + h * 0.3f, w * 0.4f, 2f, 0, Sk.WHITE);
            Gfx.rrect(ms, x + w / 2f - 1f, y + h * 0.22f, 2f, h * 0.36f, 0, Sk.WHITE);
        }
    }

    private float seg(GuiGraphics ms, String s, float x, float y, int color) {
        Gfx.text(ms, s, x, y, 6.9f, color, 0, false, 0f);
        return x + Gfx.width(s, 6.9f, false);
    }

    private void mainBody(GuiGraphics ms, float lmx, float lmy) {
        shield(ms, 18, 16, 36, 46, true);
        Gfx.text(ms, "POLIȚIA FLOWCITY", 66, 36, 17, Sk.WHITE, 0, true, 0f);
        String pre = "Bun venit, ";
        String name = playerName();
        Gfx.text(ms, pre, 67, 52, 9.5f, Sk.MUTED, 0, false, 0f);
        float nx = 67 + Gfx.width(pre, 9.5f, false);
        Gfx.text(ms, name, nx, 52, 9.5f, Sk.ACC, 0, true, 0f);
        Gfx.text(ms, "!", nx + Gfx.width(name, 9.5f, true) + 1, 52, 9.5f, Sk.MUTED, 0, false, 0f);

        // linia de stare
        int fines = unpaid();
        JsonObject permis = obj(obj(d, "docs"), "permis");
        boolean pValid = JsonClientModule.bool(permis, "valid", false);
        boolean pHas = JsonClientModule.bool(permis, "has", false);
        JsonObject c = obj(d, "cazier");
        boolean wanted = JsonClientModule.bool(c, "wanted", false);
        int recs = arr(c, "records").size();
        float sx = 67;
        sx = seg(ms, "Amenzi neplătite: ", sx, 65, Sk.MUTED);
        sx = seg(ms, String.valueOf(fines), sx, 65, fines > 0 ? Sk.BAD : Sk.GOOD);
        sx = seg(ms, "  ·  Permis: ", sx, 65, Sk.MUTED);
        sx = seg(ms, pValid ? "valid" : pHas ? "expirat" : "nu ai", sx, 65, pValid ? Sk.GOOD : Sk.BAD);
        sx = seg(ms, "  ·  Cazier: ", sx, 65, Sk.MUTED);
        seg(ms, wanted ? "mandat" : recs > 0 ? "cu înregistrări" : "curat", sx, 65, wanted ? Sk.BAD : recs > 0 ? Sk.WARN : Sk.GOOD);

        List<Item> items = mainItems();
        float gap = 12;
        float cw = (W - 28 - gap * 2) / 3f;
        float cy = 88;
        float ch = BOT - cy;
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            float x = 14 + i * (cw + gap);
            if (Sk.in(lmx, lmy, x, cy, cw, ch)) mainSel = i;
            drawCard(ms, x, cy, cw, ch, items.get(i), mainSel == i, true, lmx, lmy, () -> activate(idx));
        }
    }

    private void subBody(GuiGraphics ms, float lmx, float lmy) {
        List<Item> items = subItems(menu);
        int n = items.size();
        int cols = colsFor(n);
        int rows = (n + cols - 1) / cols;
        float gx = 12;
        float gy = 10;
        float cw = (W - 28 - gx * (cols - 1)) / cols;
        float avail = BOT - TOP;
        float ch = rows == 1 ? 170f : (avail - gy * (rows - 1)) / rows;
        if (subSel >= n) subSel = 0;
        for (int i = 0; i < n; i++) {
            final int idx = i;
            float x = 14 + (i % cols) * (cw + gx);
            float y = TOP + (i / cols) * (ch + gy);
            if (Sk.in(lmx, lmy, x, y, cw, ch)) subSel = i;
            drawCard(ms, x, y, cw, ch, items.get(i), subSel == i, rows == 1, lmx, lmy, () -> activate(idx));
        }
    }

    private void drawCard(GuiGraphics ms, float x, float y, float w, float h, Item it, boolean sel, boolean big, float lmx, float lmy, Runnable r) {
        boolean hot = sel || Sk.in(lmx, lmy, x, y, w, h);
        Sk.cut(ms, x, y, w, h, 10f, hot ? Sk.ROW_HOT : Sk.ROW);
        Gfx.rrect(ms, x, y + h - 3, w, 3, 0, hot ? Sk.ACC : 0xFF22344D);
        float s = big ? 30f : 20f;
        float iy = y + (big ? h * 0.34f : h * 0.36f);
        Gfx.disc(ms, x + w / 2f, iy, s * 0.95f, hot ? 0xFF1B2E47 : 0xFF14202F);
        icon(ms, it.icon, x + w / 2f, iy, s, Sk.ACC);
        float ty = y + (big ? h * 0.66f : h * 0.7f);
        Gfx.text(ms, clip(it.title, big ? 10f : 8.2f, true, w - 16), x + w / 2f, ty, big ? 10f : 8.2f, Sk.WHITE, 1, true, 0f);
        Gfx.text(ms, clip(it.desc, 6.6f, false, w - 16), x + w / 2f, ty + (big ? 14 : 12), 6.6f, hot ? Sk.WHITE : Sk.MUTED, 1);
        if (it.badge > 0) {
            Gfx.rrect(ms, x + w - 26, y + 8, 18, 13, 0, it.badgeColor);
            Gfx.text(ms, String.valueOf(it.badge), x + w - 17, y + 17.5f, 6.6f, 0xFF061426, 1, true, 0f);
        }
        hitL(x, y, w, h, r);
    }

    /** Iconite simple desenate cu primitive (cx, cy = centru, s = marime). */
    private void icon(GuiGraphics ms, int type, float cx, float cy, float s, int col) {
        int dark = 0xFF0E1A2E;
        switch (type) {
            case I_DOC:
                Gfx.rrect(ms, cx - s * 0.35f, cy - s * 0.45f, s * 0.7f, s * 0.9f, 3, col);
                for (int i = 0; i < 3; i++) Gfx.rrect(ms, cx - s * 0.22f, cy - s * 0.22f + i * s * 0.2f, s * 0.44f, 2, 0, dark);
                break;
            case I_CAR:
                Gfx.rrect(ms, cx - s * 0.5f, cy - s * 0.02f, s, s * 0.3f, 4, col);
                Gfx.poly(ms, new float[] {cx - s * 0.3f, cy - s * 0.02f, cx - s * 0.18f, cy - s * 0.28f, cx + s * 0.18f, cy - s * 0.28f, cx + s * 0.3f, cy - s * 0.02f}, col);
                Gfx.disc(ms, cx - s * 0.28f, cy + s * 0.3f, s * 0.14f, dark);
                Gfx.disc(ms, cx + s * 0.28f, cy + s * 0.3f, s * 0.14f, dark);
                Gfx.disc(ms, cx - s * 0.28f, cy + s * 0.3f, s * 0.07f, col);
                Gfx.disc(ms, cx + s * 0.28f, cy + s * 0.3f, s * 0.07f, col);
                break;
            case I_CHAT:
                Gfx.rrect(ms, cx - s * 0.5f, cy - s * 0.4f, s, s * 0.6f, 5, col);
                Gfx.poly(ms, new float[] {cx - s * 0.25f, cy + s * 0.18f, cx - s * 0.02f, cy + s * 0.18f, cx - s * 0.3f, cy + s * 0.42f}, col);
                for (int i = 0; i < 3; i++) Gfx.disc(ms, cx - s * 0.22f + i * s * 0.22f, cy - s * 0.1f, s * 0.07f, dark);
                break;
            case I_ID:
                Gfx.rrect(ms, cx - s * 0.55f, cy - s * 0.35f, s * 1.1f, s * 0.7f, 4, col);
                Gfx.rrect(ms, cx - s * 0.44f, cy - s * 0.2f, s * 0.3f, s * 0.4f, 2, dark);
                for (int i = 0; i < 3; i++) Gfx.rrect(ms, cx - s * 0.04f, cy - s * 0.2f + i * s * 0.15f, s * 0.42f, 2, 0, dark);
                break;
            case I_SHIELD:
                Gfx.poly(ms, new float[] {cx - s * 0.4f, cy - s * 0.45f, cx + s * 0.4f, cy - s * 0.45f, cx + s * 0.4f, cy + s * 0.05f, cx, cy + s * 0.5f, cx - s * 0.4f, cy + s * 0.05f}, col);
                Gfx.rrect(ms, cx - s * 0.2f, cy - s * 0.15f, s * 0.4f, 2.2f, 0, dark);
                Gfx.rrect(ms, cx - 1.1f, cy - s * 0.27f, 2.2f, s * 0.42f, 0, dark);
                break;
            case I_FOLDER:
                Gfx.rrect(ms, cx - s * 0.5f, cy - s * 0.4f, s * 0.42f, s * 0.2f, 3, col);
                Gfx.rrect(ms, cx - s * 0.5f, cy - s * 0.26f, s, s * 0.68f, 4, col);
                Gfx.rrect(ms, cx - s * 0.32f, cy + s * 0.02f, s * 0.64f, 2, 0, dark);
                break;
            case I_CASE:
                Gfx.rrect(ms, cx - s * 0.2f, cy - s * 0.42f, s * 0.4f, s * 0.24f, 3, col);
                Gfx.rrect(ms, cx - s * 0.12f, cy - s * 0.36f, s * 0.24f, s * 0.12f, 2, dark);
                Gfx.rrect(ms, cx - s * 0.5f, cy - s * 0.2f, s, s * 0.62f, 4, col);
                Gfx.rrect(ms, cx - s * 0.5f, cy + s * 0.05f, s, 2, 0, dark);
                break;
            default:
                Gfx.rrect(ms, cx - s * 0.32f, cy - s * 0.45f, s * 0.64f, s * 0.8f, 2, col);
                Gfx.poly(ms, new float[] {cx - s * 0.32f, cy + s * 0.34f, cx - s * 0.11f, cy + s * 0.34f, cx - s * 0.215f, cy + s * 0.5f}, col);
                Gfx.poly(ms, new float[] {cx - s * 0.11f, cy + s * 0.34f, cx + s * 0.11f, cy + s * 0.34f, cx, cy + s * 0.5f}, col);
                Gfx.poly(ms, new float[] {cx + s * 0.11f, cy + s * 0.34f, cx + s * 0.32f, cy + s * 0.34f, cx + s * 0.215f, cy + s * 0.5f}, col);
                for (int i = 0; i < 3; i++) Gfx.rrect(ms, cx - s * 0.2f, cy - s * 0.28f + i * s * 0.2f, s * 0.4f, 2, 0, dark);
        }
    }

    private void viewBody(GuiGraphics ms, float lmx, float lmy) {
        switch (view) {
            case C_AMENZI: amenzi(ms, lmx, lmy); break;
            case V_PERMIS: permis(ms, lmx, lmy); break;
            case V_ARMA: arma(ms, lmx, lmy); break;
            case V_BULETIN: buletin(ms); break;
            case C_CAZIER: cazier(ms, lmx, lmy); break;
            case C_PLANGERI: plangeri(ms, lmx, lmy); break;
            case C_MASINI: masini(ms, lmx, lmy); break;
            case C_ANGAJARE: angajare(ms, lmx, lmy); break;
            default: sesizari(ms, lmx, lmy);
        }
    }

    private void scrollbar(GuiGraphics ms, int total, int visible) {
        if (total <= visible) return;
        float trackH = BOT - TOP - 8;
        float th = Math.max(14f, trackH * visible / total);
        float ty = TOP + 8 + (trackH - th) * scroll / Math.max(1, total - visible);
        Gfx.rrect(ms, W - 10, TOP + 8, 2, trackH, 0, 0xFF14202F);
        Gfx.rrect(ms, W - 10, ty, 2, th, 0, Sk.ACC);
    }

    // ------------------------------------------------------------------ AMENZI

    private void amenzi(GuiGraphics ms, float lmx, float lmy) {
        JsonArray fines = arr(d, "fines");
        long total = 0;
        for (JsonElement e : fines) total += JsonClientModule.integer(e.getAsJsonObject(), "amountDue", JsonClientModule.integer(e.getAsJsonObject(), "amount", 0));
        if (fines.size() == 0) {
            Gfx.text(ms, "Nu ai nicio amendă neplătită.", CX + CW / 2f, H / 2f + 4, 11, Sk.WHITE, 1, true, 0f);
            Gfx.text(ms, "Condu cu grijă!", CX + CW / 2f, H / 2f + 20, 8, Sk.MUTED, 1);
            return;
        }
        Gfx.text(ms, "DE PLATĂ  " + money(total) + " lei", CX + CW, 56, 8.5f, Sk.BAD, 2, true, 0f);
        int visible = (BOT - TOP - 8) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, fines.size() - visible)));
        float y = TOP + 8;
        for (int i = scroll; i < Math.min(fines.size(), scroll + visible); i++) {
            JsonObject f = fines.get(i).getAsJsonObject();
            int amount = JsonClientModule.integer(f, "amount", 0);
            int due = JsonClientModule.integer(f, "amountDue", amount);
            int pct = JsonClientModule.integer(f, "penaltyPct", 0);
            boolean over = JsonClientModule.bool(f, "overdue", false);
            String dueDate = JsonClientModule.str(f, "dueDate", "");
            Sk.slant(ms, CX, y, CW - 8, ROW - 6, 8f, Sk.ROW);
            Gfx.rrect(ms, CX + 12, y + 8, 3, ROW - 22, 0, over ? Sk.BAD : Sk.WARN);
            Gfx.text(ms, clip(JsonClientModule.str(f, "reason", ""), 9f, true, 170), CX + 24, y + 15, 9f, Sk.WHITE, 0, true, 0f);
            String sub = JsonClientModule.str(f, "date", "");
            if (over) sub += "  ·  ÎNTÂRZIAT +" + pct + "%";
            else if (!dueDate.isEmpty()) sub += "  ·  termen " + dueDate;
            Gfx.text(ms, clip(sub, 6.4f, false, 170), CX + 24, y + 27, 6.4f, over ? Sk.BAD : Sk.MUTED, 0);
            Gfx.text(ms, money(due) + " lei", CX + CW - 112, y + 21, 10, over ? Sk.BAD : Sk.WHITE, 2, true, 0f);
            final long id = (long) JsonClientModule.num(f, "id", 0);
            button(ms, "PLĂTEȘTE", CX + CW - 104, y + 5, 90, 24, true, true, lmx, lmy, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                PolitieClient.act("pay", o);
            });
            y += ROW;
        }
        scrollbar(ms, fines.size(), visible);
    }

    // ------------------------------------------------------------------ DOCUMENTE

    private void docCard(GuiGraphics ms, String name, JsonObject doc, String missing, float y, float h) {
        boolean valid = JsonClientModule.bool(doc, "valid", false);
        boolean has = JsonClientModule.bool(doc, "has", false);
        Sk.slant(ms, CX, y, CW - 8, h, 8f, Sk.ROW);
        Gfx.rrect(ms, CX + 12, y + 7, 3, 20, 0, valid ? Sk.GOOD : Sk.BAD);
        Gfx.text(ms, name, CX + 24, y + 15, 9f, Sk.WHITE, 0, true, 0f);
        String sub = valid ? "VALID până la " + JsonClientModule.str(doc, "expires", "") + "  ·  " + JsonClientModule.str(doc, "serie", "")
            : has ? "EXPIRAT" : missing;
        String cats = JsonClientModule.str(doc, "categories", "");
        if (valid && !cats.isEmpty()) sub += "  ·  cat. " + cats;
        Gfx.text(ms, clip(sub, 6.4f, false, CW - 160), CX + 24, y + 27, 6.4f, valid ? Sk.GOOD : Sk.MUTED, 0);
    }

    private void permis(GuiGraphics ms, float lmx, float lmy) {
        JsonObject permis = obj(obj(d, "docs"), "permis");
        JsonObject fees = obj(d, "fees");
        float y = TOP + 6;
        boolean permValid = JsonClientModule.bool(permis, "valid", false);
        docCard(ms, "PERMIS DE CONDUCERE", permis, "Nu ai permis", y, 34);
        if (permValid) {
            button(ms, "DUPLICAT " + JsonClientModule.integer(fees, "duplicat", 0) + " lei", CX + CW - 150, y + 6, 136, 22, true, false, lmx, lmy, () -> PolitieClient.act("duplicat"));
            Gfx.text(ms, "Ai permis valid. Dacă l-ai pierdut, poți cere un duplicat.", CX, y + 56, 7.6f, Sk.MUTED, 0);
        } else {
            y += 44;
            Gfx.text(ms, "ALEGE CATEGORIILE", CX, y, 6.4f, Sk.FAINT, 0, false, 1.2f);
            y += 8;
            JsonArray cats = arr(d, "cats");
            float cw = 36;
            float gap = 4;
            for (int i = 0; i < cats.size(); i++) {
                String c = cats.get(i).getAsString();
                float x = CX + (i % 9) * (cw + gap);
                float cy = y + (i / 9) * 20;
                chip(ms, c, x, cy, cw, 17, picked.contains(c), lmx, lmy, () -> {
                    if (!picked.remove(c)) picked.add(c);
                });
            }
            y += 46;
            button(ms, "CERE PERMIS · " + JsonClientModule.integer(fees, "permis", 0) + " lei", CX, y, 190, 24, !picked.isEmpty(), true, lmx, lmy, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("categories", String.join(",", picked));
                PolitieClient.act("permis", o);
            });
            Gfx.text(ms, "Condiții: buletin valid, minim " + JsonClientModule.integer(d, "minAge", 18) + " ani, fără amenzi neplătite.", CX, y + 40, 6.8f, Sk.FAINT, 0);
        }
    }

    private void arma(GuiGraphics ms, float lmx, float lmy) {
        JsonObject arma = obj(obj(d, "docs"), "portarma");
        JsonObject fees = obj(d, "fees");
        float y = TOP + 6;
        docCard(ms, "PORT-ARMĂ", arma, "Nu ai port-armă", y, 34);
        if (!JsonClientModule.bool(arma, "valid", false)) {
            button(ms, "CERE · " + JsonClientModule.integer(fees, "portarma", 0) + " lei", CX + CW - 150, y + 6, 136, 22, true, true, lmx, lmy, () -> PolitieClient.act("portarma"));
        }
        y += 52;
        Gfx.text(ms, "Condiții:", CX, y, 8.4f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "• buletin valid și minim " + JsonClientModule.integer(d, "minAge", 18) + " ani", CX, y + 14, 7.4f, Sk.MUTED, 0);
        Gfx.text(ms, "• fără amenzi neplătite", CX, y + 26, 7.4f, Sk.MUTED, 0);
        Gfx.text(ms, "• cazier curat și fără mandat de urmărire", CX, y + 38, 7.4f, Sk.MUTED, 0);
    }

    private void buletin(GuiGraphics ms) {
        JsonObject buletin = obj(obj(d, "docs"), "buletin");
        float y = TOP + 6;
        docCard(ms, "BULETIN", buletin, "Nu ai buletin", y, 34);
        Gfx.text(ms, "Buletinul se eliberează la Primărie.", CX, y + 62, 10, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "Folosește comanda /primarie sau mergi la ghișeul Primăriei din oraș.", CX, y + 78, 7.6f, Sk.MUTED, 0);
        Gfx.text(ms, "Vârsta minimă și taxele sunt stabilite acolo; Poliția eliberează doar permisul și portul de armă.", CX, y + 90, 7.0f, Sk.FAINT, 0);
    }

    // ------------------------------------------------------------------ CAZIER

    private void cazier(GuiGraphics ms, float lmx, float lmy) {
        if (cert != null) {
            float y = TOP + 8;
            Sk.slant(ms, CX, y, CW - 8, BOT - y, 8f, Sk.ROW);
            Gfx.text(ms, "CERTIFICAT DE CAZIER JUDICIAR", CX + (CW - 8) / 2f, y + 20, 10, Sk.WHITE, 1, true, 0f);
            Gfx.text(ms, "Nume: " + JsonClientModule.str(cert, "name", ""), CX + 16, y + 42, 8.5f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, "Data eliberării: " + JsonClientModule.str(cert, "date", ""), CX + 16, y + 56, 7.5f, Sk.MUTED, 0);
            JsonArray lines = arr(cert, "lines");
            if (JsonClientModule.bool(cert, "clean", false)) {
                Gfx.text(ms, "FĂRĂ ANTECEDENTE PENALE", CX + (CW - 8) / 2f, y + 100, 11, Sk.GOOD, 1, true, 0f);
            } else {
                float ly = y + 76;
                for (JsonElement e : lines) {
                    Gfx.text(ms, clip("• " + e.getAsString(), 7f, false, CW - 40), CX + 16, ly, 7f, Sk.BAD, 0);
                    ly += 11;
                    if (ly > BOT - 34) break;
                }
            }
            button(ms, "ÎNAPOI", CX + CW - 108, BOT - 32, 90, 22, true, false, lmx, lmy, () -> cert = null);
            return;
        }
        JsonObject c = obj(d, "cazier");
        float y = TOP + 8;
        int unpaid = JsonClientModule.integer(c, "unpaidCount", 0);
        boolean wanted = JsonClientModule.bool(c, "wanted", false);
        JsonArray recs = arr(c, "records");
        Sk.slant(ms, CX, y, CW - 8, 84, 8f, Sk.ROW);
        Gfx.text(ms, "Amenzi plătite: " + JsonClientModule.integer(c, "paidCount", 0) + "  (" + money((long) JsonClientModule.num(c, "paidSum", 0)) + " lei)", CX + 16, y + 18, 8, Sk.WHITE, 0);
        Gfx.text(ms, "Amenzi neplătite: " + unpaid, CX + 16, y + 33, 8, unpaid > 0 ? Sk.BAD : Sk.GOOD, 0);
        Gfx.text(ms, "Mandat de urmărire: " + (wanted ? "DA - " + clip(JsonClientModule.str(c, "reason", ""), 8f, false, 220) : "nu"), CX + 16, y + 48, 8, wanted ? Sk.BAD : Sk.GOOD, 0);
        Gfx.text(ms, "Înregistrări / arestări: " + recs.size(), CX + 16, y + 63, 8, recs.size() > 0 ? Sk.WARN : Sk.GOOD, 0);
        y += 92;
        for (int i = 0; i < Math.min(3, recs.size()); i++) {
            JsonObject r = recs.get(i).getAsJsonObject();
            Gfx.text(ms, clip(JsonClientModule.str(r, "date", "") + " · " + JsonClientModule.str(r, "text", ""), 6.8f, false, CW - 24), CX + 8, y, 6.8f, Sk.MUTED, 0);
            y += 11;
        }
        button(ms, "CERE CERTIFICAT · " + JsonClientModule.integer(obj(d, "fees"), "cazier", 0) + " lei", CX, BOT - 32, 200, 24, true, true, lmx, lmy, () -> PolitieClient.act("cazier"));
    }

    // ------------------------------------------------------------------ PLANGERI

    private void mineList(GuiGraphics ms, String type, float y) {
        Gfx.text(ms, "TRIMISE DE TINE", CX, y, 6.4f, Sk.FAINT, 0, false, 1.2f);
        y += 6;
        int n = 0;
        for (JsonElement e : arr(d, "mine")) {
            JsonObject o = e.getAsJsonObject();
            if (!type.equals(JsonClientModule.str(o, "type", ""))) continue;
            if (y + 16 > BOT) break;
            Sk.slant(ms, CX, y, CW - 8, 16, 4f, Sk.ROW);
            Gfx.text(ms, clip("#" + JsonClientModule.integer(o, "id", 0) + "  " + JsonClientModule.str(o, "title", ""), 7f, false, CW - 160), CX + 8, y + 11, 7f, Sk.WHITE, 0);
            String st = JsonClientModule.str(o, "status", "");
            Gfx.text(ms, JsonClientModule.str(o, "date", "") + "  " + st, CX + CW - 16, y + 11, 6.6f, "NOUA".equals(st) ? Sk.WARN : "PREASA".equals(st) ? Sk.ACC : Sk.FAINT, 2);
            y += 19;
            n++;
        }
        if (n == 0) Gfx.text(ms, "Nimic trimis încă.", CX, y + 8, 7f, Sk.FAINT, 0);
    }

    private void plangeri(GuiGraphics ms, float lmx, float lmy) {
        float y = TOP + 8;
        fieldBox(ms, title, CX, y, CW - 8, 22);
        y += 28;
        fieldBox(ms, text, CX, y, CW - 8, 22);
        y += 28;
        button(ms, "TRIMITE SESIZAREA", CX, y, 170, 24, true, true, lmx, lmy, () -> submit(false));
        mineList(ms, "PLANGERE", y + 40);
    }

    private void submit(boolean job) {
        JsonObject o = new JsonObject();
        if (job) {
            JsonArray roles = arr(d, "roles");
            o.addProperty("type", "ANGAJARE");
            o.addProperty("role", roles.size() > 0 ? roles.get(Math.min(roleIdx, roles.size() - 1)).getAsString() : "");
            o.addProperty("text", jobMsg.getValue());
        } else {
            o.addProperty("type", "PLANGERE");
            o.addProperty("title", title.getValue());
            o.addProperty("text", text.getValue());
        }
        PolitieClient.act("submit", o);
    }

    // ------------------------------------------------------------------ MASINI

    private void masini(GuiGraphics ms, float lmx, float lmy) {
        float y = TOP + 12;
        Gfx.text(ms, "Vezi lista mașinilor tale înmatriculate:", CX, y + 8, 9, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "model, număr și starea fiecărei mașini.", CX, y + 22, 7.5f, Sk.MUTED, 0);
        Gfx.text(ms, "Lista se deschide într-un ecran separat.", CX, y + 34, 7.5f, Sk.MUTED, 0);
        button(ms, "VEZI MAȘINILE MELE", CX, y + 52, 190, 26, true, true, lmx, lmy, () -> {
            PolitieClient.act("mechlist");
            onClose();
        });
    }

    // ------------------------------------------------------------------ ANGAJARE

    private void angajare(GuiGraphics ms, float lmx, float lmy) {
        float y = TOP + 8;
        Gfx.text(ms, "Rolul dorit", CX, y + 6, 7f, Sk.MUTED, 0);
        y += 12;
        JsonArray roles = arr(d, "roles");
        for (int i = 0; i < roles.size(); i++) {
            final int idx = i;
            float x = CX + (i % 4) * 92;
            float cy = y + (i / 4) * 21;
            chip(ms, clip(roles.get(i).getAsString(), 7f, true, 84), x, cy, 88, 18, i == roleIdx, lmx, lmy, () -> roleIdx = idx);
        }
        y += ((roles.size() + 3) / 4) * 21 + 6;
        fieldBox(ms, jobMsg, CX, y, CW - 8, 22);
        y += 28;
        button(ms, "TRIMITE CEREREA", CX, y, 170, 24, true, true, lmx, lmy, () -> submit(true));
        mineList(ms, "ANGAJARE", y + 40);
    }

    // ------------------------------------------------------------------ SESIZARI (ofiteri)

    private void sesizari(GuiGraphics ms, float lmx, float lmy) {
        JsonArray in = arr(d, "inbox");
        if (in.size() == 0) {
            Gfx.text(ms, "Nu sunt sesizări active.", CX + CW / 2f, H / 2f + 4, 10, Sk.WHITE, 1, true, 0f);
            return;
        }
        int visible = (BOT - TOP - 8) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, in.size() - visible)));
        float y = TOP + 8;
        for (int i = scroll; i < Math.min(in.size(), scroll + visible); i++) {
            JsonObject o = in.get(i).getAsJsonObject();
            String st = JsonClientModule.str(o, "status", "");
            Sk.slant(ms, CX, y, CW - 8, ROW - 6, 8f, Sk.ROW);
            Gfx.rrect(ms, CX + 12, y + 8, 3, ROW - 22, 0, "NOUA".equals(st) ? Sk.WARN : Sk.ACC);
            String head = ("ANGAJARE".equals(JsonClientModule.str(o, "type", "")) ? "[ANGAJARE] " : "") + JsonClientModule.str(o, "title", "");
            Gfx.text(ms, clip(head, 8.6f, true, 330), CX + 24, y + 13, 8.6f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, clip("#" + JsonClientModule.integer(o, "id", 0) + " · " + JsonClientModule.str(o, "author", "") + " · " + JsonClientModule.str(o, "date", "") + " · " + st, 6.2f, false, 330), CX + 24, y + 22, 6.2f, Sk.MUTED, 0);
            Gfx.text(ms, clip(JsonClientModule.str(o, "text", ""), 6.6f, false, 330), CX + 24, y + 31, 6.6f, Sk.FAINT, 0);
            final long id = (long) JsonClientModule.num(o, "id", 0);
            button(ms, "PREIA", CX + CW - 132, y + 6, 56, 22, !"PREASA".equals(st), true, lmx, lmy, () -> act("take", id));
            button(ms, "ARHIVEAZĂ", CX + CW - 72, y + 6, 62, 22, true, false, lmx, lmy, () -> act("archive", id));
            y += ROW;
        }
        scrollbar(ms, in.size(), visible);
    }

    private static void act(String action, long id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        PolitieClient.act(action, o);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
