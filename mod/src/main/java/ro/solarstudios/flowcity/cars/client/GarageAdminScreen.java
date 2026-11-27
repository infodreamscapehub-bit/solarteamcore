package ro.solarstudios.flowcity.cars.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.CarsNet;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Administrarea garajelor (/garaje): lista garajelor cu actiuni de sters / sters toate / teleport / mutare / parcare / locuri / creare.
 * Stil iarna (Sk), ecran scalat 520x290. Datele vin din GaragesClient.garages (sincronizate de server dupa fiecare actiune),
 * actiunile pleaca prin CarsNet "gadmin" si sunt verificate pe server (permisiune de operator).
 * Operatii gadmin: 0 refresh, 1 sterge, 2 sterge toate, 3 muta aici, 4 teleport, 5 creeaza public, 6 creeaza pound,
 * 7 creeaza factiune ("factiune:nume"), 8 parcare aici, 9 loc spawn aici, 10 sterge locurile.
 */
public final class GarageAdminScreen extends Screen {
    private static final class Hit {
        final float x;
        final float y;
        final float w;
        final float h;
        final Runnable action;

        Hit(float x, float y, float w, float h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }
    }

    private static final class Modal {
        String title;
        String body;
        String okLabel;
        int color;
        Runnable ok;
    }

    private static final float W = 520f;
    private static final float H = 290f;
    private static final int LINE = 0xFF22324A;
    private static final int ROWS = 8;
    private static final float ROW_H = 21f;

    private final List<Hit> hits = new ArrayList<>();
    private final Map<EditBox, float[]> fieldRects = new HashMap<>();
    private final long openedAt = System.currentTimeMillis();

    private EditBox nameField;
    private EditBox factionField;
    private int selectedId = -1;
    private int page;
    private int createType = 5; // 5 public, 6 pound, 7 factiune
    private Modal modal;
    private boolean drawingModal;
    private String toastText = "";
    private long toastAt;
    private long toastUntil;
    private boolean swallowChar;

    private float offX;
    private float offY;
    private float scale = 1f;
    private float lmx;
    private float lmy;
    private GuiGraphics g;

    public GarageAdminScreen() {
        super(Component.literal("Garaje"));
    }

    @Override
    protected void init() {
        nameField = field("Nume garaj...", 40);
        factionField = field("factiune (ex. politie)", 20);
        CarsNet.send("gadmin", 0, 0);
    }

    private EditBox field(String hint, int max) {
        EditBox f = new EditBox(font, 0, 0, 100, 14, Component.literal(hint));
        f.setMaxLength(max);
        f.setBordered(false);
        f.setTextColor(Sk.WHITE);
        f.setSuggestion(hint);
        f.setResponder(s -> f.setSuggestion(s.isEmpty() ? hint : null));
        return f;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        nameField.tick();
        factionField.tick();
    }

    // ------------------------------------------------------------------ date + actiuni

    private GaragesClient.G selected() {
        for (GaragesClient.G x : GaragesClient.garages) if (x.id == selectedId) return x;
        return null;
    }

    private void send(int op, int id, String text, String toast) {
        CarsNet.send("gadmin", op, id, text);
        toast(toast);
    }

    private void toast(String msg) {
        toastText = msg;
        toastAt = System.currentTimeMillis();
        toastUntil = toastAt + 3000;
    }

    private void confirm(String title, String body, String okLabel, int color, Runnable ok) {
        Modal m = new Modal();
        m.title = title;
        m.body = body;
        m.okLabel = okLabel;
        m.color = color;
        m.ok = ok;
        modal = m;
        focus(nameField, false);
        focus(factionField, false);
    }

    private void deleteSelected() {
        final GaragesClient.G s = selected();
        if (s == null) return;
        confirm("STERGI GARAJUL?", "#" + s.id + "  " + s.name + "\nMasinile jucatorilor raman ale lor.", "DA, STERGE", Sk.BAD, () -> {
            send(1, s.id, "", "Garaj sters.");
            selectedId = -1;
        });
    }

    private void deleteAll() {
        final int n = GaragesClient.garages.size();
        if (n == 0) return;
        confirm("STERGI TOATE GARAJELE?", n + " garaje vor fi sterse.\nMasinile jucatorilor raman ale lor.", "CONTINUA", Sk.BAD, () ->
            confirm("ESTI SIGUR?", "Nu se poate anula.\nSterg toate cele " + n + " garaje.", "DA, STERGE TOT", Sk.BAD, () -> {
                send(2, 0, "", "Toate garajele au fost sterse.");
                selectedId = -1;
                page = 0;
            }));
    }

    private void create() {
        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            toast("Scrie un nume pentru garaj.");
            return;
        }
        String text = name;
        if (createType == 7) {
            String f = factionField.getValue().trim().toLowerCase();
            if (!f.matches("[a-z0-9_]{1,20}")) {
                toast("Factiune invalida (litere mici, fara spatii).");
                return;
            }
            text = f + ":" + name;
        }
        send(createType, 0, text, "Garaj creat la pozitia ta.");
        nameField.setValue("");
        focus(nameField, false);
    }

    private void focus(EditBox f, boolean on) {
        if (f == null) return;
        f.mouseClicked(on ? f.getX() + 1 : -10000, on ? f.getY() + 1 : -10000, 0);
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ------------------------------------------------------------------ primitive de desen

    private static float easeOut(float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - c) * (1f - c) * (1f - c);
    }

    private void tx(GuiGraphics ms, String s, float x, float top, float size, int color) {
        Gfx.text(ms, s, x, top + size * 0.9f, size, color, 0, false, 0f);
    }

    private void txb(GuiGraphics ms, String s, float x, float top, float size, int color) {
        Gfx.text(ms, s, x, top + size * 0.9f, size, color, 0, true, 0f);
    }

    private void txr(GuiGraphics ms, String s, float xRight, float top, float size, int color) {
        Gfx.text(ms, s, xRight, top + size * 0.9f, size, color, 2, false, 0f);
    }

    private void txc(GuiGraphics ms, String s, float cx, float top, float size, int color, boolean bold) {
        Gfx.text(ms, s, cx, top + size * 0.9f, size, color, 1, bold, 0f);
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        if (modal != null && !drawingModal) return;
        hits.add(new Hit(x, y, w, h, r));
    }

    private void button(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, boolean filled, Runnable r) {
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, enabled, filled, hov);
        if (enabled) hitL(x, y, w, h, r);
    }

    /** Buton de actiune: kind 0 neutru, 1 succes, 2 pericol, 3 accent, 4 avertisment. */
    private void abtn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean enabled, int kind, Runnable r) {
        int col = kind == 1 ? Sk.GOOD : kind == 2 ? Sk.BAD : kind == 3 ? Sk.ACC : kind == 4 ? Sk.WARN : Sk.MUTED;
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        int bg = !enabled ? 0xFF121C2A : hov ? Theme.alpha(col, 0x70) : Theme.alpha(col, 0x30);
        Sk.slant(ms, x, y, w, h, 4f, bg);
        if (enabled) Gfx.rrect(ms, x + 2, y + h - 2, w - 6, 1.5f, 0, col);
        txc(ms, label, x + w / 2f + 1.5f, y + h / 2f - 3f, 6.4f, !enabled ? Sk.FAINT : hov ? 0xFFFFFFFF : Sk.WHITE, true);
        if (enabled) hitL(x, y, w, h, r);
    }

    private void chip(GuiGraphics ms, String label, float x, float y, float w, float h, boolean on, Runnable r) {
        boolean hov = Sk.in(lmx, lmy, x, y, w, h);
        Sk.slant(ms, x, y, w, h, 4f, on ? Sk.ACC : hov ? Sk.ROW_HOT : Sk.ROW);
        txc(ms, label, x + w / 2f + 1.5f, y + h / 2f - 3f, 6.4f, on ? 0xFF061426 : Sk.WHITE, true);
        hitL(x, y, w, h, r);
    }

    private void fieldBox(GuiGraphics ms, EditBox f, float x, float y, float w, float h) {
        Sk.slant(ms, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, f.isFocused() ? Sk.ACC : LINE);
        f.setX(Math.round(offX + (x + 7f) * scale));
        f.setY(Math.round(offY + (y + (h - 8f) / 2f) * scale));
        f.setWidth(Math.max(10, Math.round((w - 14f) * scale)));
        fieldRects.put(f, new float[] {x, y, w, h});
    }

    private int pill(GuiGraphics ms, String label, float x, float y, int color) {
        float w = Gfx.width(label, 5.8f, true) + 9f;
        Sk.slant(ms, x, y, w, 10f, 3f, Theme.alpha(color, 0x40));
        txc(ms, label, x + w / 2f + 1f, y + 2f, 5.8f, color, true);
        return Math.round(x + w + 4f);
    }

    private static int typeColor(GaragesClient.G x) {
        return "pound".equals(x.type) ? Sk.BAD : "factiune".equals(x.type) ? Sk.WARN : Sk.ACC;
    }

    private static String typeLabel(GaragesClient.G x) {
        if ("pound".equals(x.type)) return "POUND";
        if ("factiune".equals(x.type)) return x.faction == null || x.faction.isEmpty() ? "FACTIUNE" : x.faction.toUpperCase();
        return "PUBLIC";
    }

    // ------------------------------------------------------------------ desenare

    @Override
    public void render(GuiGraphics ms, int mx, int my, float pt) {
        hits.clear();
        fieldRects.clear();
        float open = easeOut((System.currentTimeMillis() - openedAt) / 300f);
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        lmx = (mx - offX) / scale;
        lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
        for (EditBox f : fields()) f.render(ms, mx, my, 0);
    }

    private void content(GuiGraphics ms) {
        g = ms;
        if (selectedId >= 0 && selected() == null) selectedId = -1;
        Sk.cut(ms, 8, 8, 504, 274, 12f, Gfx.fade(Sk.ACC, 0.40f));
        Sk.cut(ms, 9, 9, 502, 272, 12f, 0xF20A121D);
        Gfx.scrim(ms, 260, 22, 250, 36, Gfx.fade(0xFF9FD0FF, 0.07f));
        Gfx.rrect(ms, 22, 9, 96, 2, 0, Sk.ACC);

        // header
        tx(ms, "FLOWCITY  >  ADMIN  >  GARAJE", 22, 14, 5.8f, Sk.MUTED);
        txb(ms, "GARAJE", 22, 22, 12.5f, Sk.ACC);
        Gfx.rrect(ms, 18, 42, 484, 1, 0, LINE);
        button(ms, "REFRESH", 418, 14, 62, 20, true, false, () -> CarsNet.send("gadmin", 0, 0));
        Sk.close(ms, 484, 14, lmx, lmy);
        hitL(484, 14, 20, 20, this::onClose);

        list(ms);
        detail(ms);
        create(ms);

        txc(ms, "ESC INCHIDE   SUS-JOS  ALEGE GARAJ   DEL  STERGE", 260, 274f, 5.8f, Sk.FAINT, false);
        toastDraw(ms);
        modalDraw(ms);
    }

    private void list(GuiGraphics ms) {
        float x = 18;
        float w = 292;
        float y0 = 50;
        List<GaragesClient.G> all = GaragesClient.garages;
        int pages = Math.max(1, (all.size() + ROWS - 1) / ROWS);
        if (page >= pages) page = pages - 1;
        if (all.isEmpty()) {
            tx(ms, "Nu exista garaje.", x + 4, y0 + 8, 7.4f, Sk.MUTED);
            tx(ms, "Creeaza unul din panoul din dreapta (la pozitia ta).", x + 4, y0 + 20, 6.6f, Sk.FAINT);
        }
        for (int i = 0; i < ROWS; i++) {
            int idx = page * ROWS + i;
            if (idx >= all.size()) break;
            final GaragesClient.G gar = all.get(idx);
            float y = y0 + i * ROW_H;
            boolean sel = gar.id == selectedId;
            boolean hov = Sk.in(lmx, lmy, x, y, w, ROW_H - 2);
            if (sel) {
                Sk.slant(ms, x, y, w, ROW_H - 2, 4f, Sk.ROW_HOT);
                Gfx.rrect(ms, x, y + 1, 2f, ROW_H - 4, 0, Sk.ACC);
            } else if (hov) {
                Sk.slant(ms, x, y, w, ROW_H - 2, 4f, Sk.ROW);
            }
            tx(ms, "#" + gar.id, x + 7, y + 3.2f, 6.6f, sel ? Sk.WHITE : Sk.FAINT);
            tx(ms, clip(gar.name, 7.4f, true, 130f), x + 30, y + 2.6f, 7.4f, Sk.WHITE);
            tx(ms, (int) gar.mx + " " + (int) gar.my + " " + (int) gar.mz, x + 30, y + 11.4f, 5.6f, Sk.MUTED);
            float px = x + 170;
            px = pill(ms, typeLabel(gar), px, y + 2.5f, typeColor(gar));
            txr(ms, "P:" + (gar.store ? "da" : "nu") + "  L:" + gar.slots + "  B:" + gar.boatSlots, x + w - 6, y + 11.2f, 5.8f, gar.store && gar.slots > 0 ? Sk.GOOD : Sk.WARN);
            hitL(x, y, w, ROW_H - 2, () -> selectedId = gar.id);
        }
        float py = 254;
        button(ms, "<", x, py, 22, 18, page > 0, false, () -> page--);
        button(ms, ">", x + 70, py, 22, 18, page + 1 < pages, false, () -> page++);
        txc(ms, (page + 1) + "/" + pages + "  (" + all.size() + ")", x + 46, py + 5f, 6.4f, Sk.MUTED, false);
        abtn(ms, "STERGE TOATE", x + 190, py, 102, 18, !all.isEmpty(), 2, this::deleteAll);
        Gfx.rrect(ms, 314, 48, 1, 224, 0, LINE);
    }

    private void detail(GuiGraphics ms) {
        float x = 322;
        float w = 180;
        Sk.cut(ms, x, 48, w, 118, 6f, Sk.ROW);
        final GaragesClient.G s = selected();
        if (s == null) {
            txc(ms, "Alege un garaj din lista", x + w / 2f, 96, 6.8f, Sk.MUTED, false);
            return;
        }
        txb(ms, clip(s.name, 8.4f, true, w - 16), x + 8, 54, 8.4f, Sk.WHITE);
        tx(ms, "#" + s.id + "  " + typeLabel(s) + "  " + dimShort(s.dim), x + 8, 65, 5.8f, Sk.MUTED);
        tx(ms, "Marker: " + (int) s.mx + " " + (int) s.my + " " + (int) s.mz, x + 8, 74, 5.8f, Sk.MUTED);
        tx(ms, "Parcare: " + (s.store ? "da (raza " + (int) s.r + ")" : "nu") + "   Teren: " + s.slots + "  Barci: " + s.boatSlots, x + 8, 82, 5.8f, Sk.MUTED);
        float bw = (w - 22) / 2f;
        float by = 92;
        final int id = s.id;
        abtn(ms, "TELEPORT", x + 8, by, bw, 15, true, 3, () -> send(4, id, "", "Teleport..."));
        abtn(ms, "MUTA AICI", x + 14 + bw, by, bw, 15, true, 0, () -> send(3, id, "", "Marker mutat aici."));
        abtn(ms, "PARCARE AICI", x + 8, by + 18, bw, 15, true, 0, () -> send(8, id, "", "Zona de parcare setata."));
        abtn(ms, "LOC SPAWN +", x + 14 + bw, by + 18, bw, 15, true, 0, () -> send(9, id, "", "Loc de spawn adaugat."));
        abtn(ms, "SPAWN BARCA +", x + 8, by + 36, bw, 15, true, 3, () -> send(11, id, "", "Punct de spawn barca adaugat."));
        abtn(ms, "STERGE LOCURI", x + 14 + bw, by + 36, bw, 15, s.slots > 0 || s.boatSlots > 0, 4, () -> send(10, id, "", "Locuri sterse."));
        abtn(ms, "STERGE GARAJ", x + 8, by + 54, w - 16, 15, true, 2, this::deleteSelected);
    }

    private static String dimShort(String d) {
        if (d == null) return "";
        int i = d.indexOf(':');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    private void create(GuiGraphics ms) {
        float x = 322;
        float w = 180;
        Sk.cut(ms, x, 172, w, 100, 6f, Sk.ROW);
        txb(ms, "CREEAZA GARAJ AICI", x + 8, 177, 6.8f, Sk.ACC);
        float cw = (w - 22) / 3f;
        chip(ms, "PUBLIC", x + 8, 189, cw, 13, createType == 5, () -> createType = 5);
        chip(ms, "POUND", x + 11 + cw, 189, cw, 13, createType == 6, () -> createType = 6);
        chip(ms, "FACTIUNE", x + 14 + cw * 2, 189, cw, 13, createType == 7, () -> createType = 7);
        fieldBox(ms, nameField, x + 8, 207, w - 16, 16);
        if (createType == 7) {
            fieldBox(ms, factionField, x + 8, 226, w - 16, 16);
        }
        abtn(ms, "CREEAZA", x + 8, 248, w - 16, 18, true, 1, this::create);
    }

    // ---- toast + dialog

    private void toastDraw(GuiGraphics ms) {
        long now = System.currentTimeMillis();
        if (now > toastUntil || toastText.isEmpty()) return;
        float in = easeOut((now - toastAt) / 220f);
        float out = Math.min(1f, (toastUntil - now) / 300f);
        float tw = Math.min(380f, Gfx.width(toastText, 7f, true) + 34f);
        float x = (W - tw) / 2f;
        float y = 282f - 30f * in;
        float prev = Gfx.alpha;
        Gfx.alpha = prev * out;
        try {
            Sk.cut(ms, x - 1, y - 1, tw + 2, 22, 6f, Theme.alpha(Sk.GOOD, 0xA0));
            Sk.cut(ms, x, y, tw, 20, 6f, 0xFF0B1422);
            Gfx.rrect(ms, x + 8, y + 7, 6, 6, 0, Sk.GOOD);
            tx(ms, clip(toastText, 7f, true, tw - 34), x + 20, y + 5.4f, 7f, Sk.WHITE);
        } finally {
            Gfx.alpha = prev;
        }
    }

    private void modalDraw(GuiGraphics ms) {
        if (modal == null) return;
        final Modal m = modal;
        drawingModal = true;
        try {
            Gfx.rrect(ms, 9, 9, 502, 272, 0, 0xC4040810);
            float w = 280;
            float h = 112;
            float x = (W - w) / 2f;
            float y = (H - h) / 2f;
            Sk.cut(ms, x - 1, y - 1, w + 2, h + 2, 10f, Theme.alpha(m.color, 0xB0));
            Sk.cut(ms, x, y, w, h, 10f, 0xFF0B1422);
            Gfx.scrim(ms, x + w / 2f, y + 8, w * 0.4f, 22, Gfx.fade(m.color, 0.16f));
            txb(ms, m.title, x + 16, y + 13, 10f, m.color);
            Gfx.rrect(ms, x + 16, y + 29, w - 32, 1, 0, LINE);
            float ty = y + 38;
            for (String line : m.body.split("\n")) {
                tx(ms, clip(line, 7.4f, false, w - 32), x + 16, ty, 7.4f, Sk.WHITE);
                ty += 11;
            }
            button(ms, "ANULEAZA", x + 16, y + h - 30, 112, 20, true, false, () -> modal = null);
            button(ms, m.okLabel, x + w - 128, y + h - 30, 112, 20, true, true, () -> runModal());
        } finally {
            drawingModal = false;
        }
    }

    private void runModal() {
        Modal m = modal;
        modal = null; // pasul urmator (confirmare dubla) isi pune propriul dialog
        if (m != null && m.ok != null) m.ok.run();
    }

    // ------------------------------------------------------------------ input

    private List<EditBox> fields() {
        List<EditBox> f = new ArrayList<>();
        if (modal != null) return f;
        f.add(nameField);
        if (createType == 7) f.add(factionField);
        return f;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            float lx = (float) ((mx - offX) / scale);
            float ly = (float) ((my - offY) / scale);
            if (modal == null) {
                boolean onField = false;
                for (EditBox f : fields()) {
                    float[] r = fieldRects.get(f);
                    boolean inside = r != null && Sk.in(lx, ly, r[0], r[1], r[2], r[3]);
                    if (inside) {
                        f.mouseClicked(mx, my, button);
                        focus(f, true);
                        onField = true;
                    } else {
                        focus(f, false);
                    }
                }
                if (onField) return true;
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Sk.in(lx, ly, h.x, h.y, h.w, h.h)) {
                    click();
                    h.action.run();
                    return true;
                }
            }
            if (modal != null) return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (modal != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                modal = null;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                runModal();
            }
            return true;
        }
        for (EditBox f : fields()) {
            if (f.isFocused()) {
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    focus(f, false);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                    create();
                    return true;
                }
                f.keyPressed(key, scan, mods);
                return true;
            }
        }
        List<GaragesClient.G> all = GaragesClient.garages;
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && !all.isEmpty()) {
            int idx = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id == selectedId) idx = i;
            int n = all.size();
            int next = key == GLFW.GLFW_KEY_DOWN ? (idx + 1) % n : (idx <= 0 ? n - 1 : idx - 1);
            selectedId = all.get(next).id;
            page = next / ROWS;
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE && selected() != null) {
            deleteSelected();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (swallowChar) {
            swallowChar = false;
            return true;
        }
        if (modal != null) return true;
        for (EditBox f : fields()) {
            if (f.isFocused() && f.charTyped(c, mods)) return true;
        }
        return super.charTyped(c, mods);
    }
}
