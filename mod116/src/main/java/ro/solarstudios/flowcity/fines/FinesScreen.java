package ro.solarstudios.flowcity.fines;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * "Amenzile mele": rezumat al sumei de plata, lista amenzilor (scadenta, penalizare) si detaliul amenzii selectate, cu
 * plata printr-un dialog de confirmare. Tema de iarna FlowCity (panouri inghetate).
 */
public final class FinesScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int ROW = 42;
    private static final float TOP = 66f;
    private static final float LX = 172f;
    private static final float LW = W - LX - 24f;

    private static final class Fine {
        long id;
        int amount;
        int amountDue;
        int pct;
        boolean overdue;
        String dueDate;
        String reason;
        String by;
        String date;
    }

    private final List<Fine> fines = new ArrayList<>();
    private int scroll;
    private int sel;
    private boolean confirm;
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    FinesScreen(JsonObject d) {
        super("Amenzi", W, H);
        update(d);
    }

    void update(JsonObject d) {
        long keep = sel >= 0 && sel < fines.size() ? fines.get(sel).id : -1L;
        fines.clear();
        JsonArray a = d.has("fines") && d.get("fines").isJsonArray() ? d.getAsJsonArray("fines") : new JsonArray();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            Fine f = new Fine();
            f.id = (long) JsonClientModule.num(o, "id", 0);
            f.amount = JsonClientModule.integer(o, "amount", 0);
            f.amountDue = JsonClientModule.integer(o, "amountDue", f.amount);
            f.pct = JsonClientModule.integer(o, "penaltyPct", 0);
            f.overdue = JsonClientModule.bool(o, "overdue", false);
            f.dueDate = JsonClientModule.str(o, "dueDate", "");
            f.reason = JsonClientModule.str(o, "reason", "");
            f.by = JsonClientModule.str(o, "by", "");
            f.date = JsonClientModule.str(o, "date", "");
            fines.add(f);
        }
        sel = 0;
        for (int i = 0; i < fines.size(); i++) {
            if (fines.get(i).id == keep) {
                sel = i;
                break;
            }
        }
        scroll = 0;
        confirm = false;
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
        confirm = false;
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

    /** Imparte textul pe cel mult maxLines randuri (ultimul se termina cu "..." daca e taiat). */
    private static List<String> wrap(String s, float size, boolean xl, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean cut = false;
        for (String w : (s == null ? "" : s).split("\\s+")) {
            if (w.isEmpty()) continue;
            String t = cur.length() == 0 ? w : cur + " " + w;
            if (Gfx.width(t, size, xl) <= maxW) {
                cur.setLength(0);
                cur.append(t);
                continue;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (out.size() >= maxLines) {
                cut = true;
                break;
            }
            cur.append(w);
        }
        if (!cut && cur.length() > 0) {
            if (out.size() < maxLines) out.add(cur.toString());
            else cut = true;
        }
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i);
            out.set(i, clip(cut && i == out.size() - 1 ? line + "..." : line, size, xl, maxW));
        }
        return out;
    }

    private static void frost(GuiGraphics ms, float x, float y, float w, float h) {
        Gfx.rrect(ms, x, y, w, h, 7, Gfx.fade(0xFFBFD9F5, 0.11f), Gfx.fade(0xFF8FB5E0, 0.05f));
        Gfx.rrect(ms, x + 5, y, w - 10, 1f, 0, 0x40FFFFFF);
        Gfx.rrect(ms, x, y + 5, 1f, h - 10, 0, 0x22FFFFFF);
        Gfx.rrect(ms, x + w - 1, y + 5, 1f, h - 10, 0, 0x16FFFFFF);
        Gfx.rrect(ms, x + 5, y + h - 1, w - 10, 1f, 0, 0x16FFFFFF);
    }

    /** Fulg de nea din 3 axe cu ramuri (decor). */
    private static void flake(GuiGraphics ms, float cx, float cy, float r, float w, int c) {
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * i / 3.0;
            float dx = (float) Math.cos(a) * r;
            float dy = (float) Math.sin(a) * r;
            Gfx.line(ms, cx - dx, cy - dy, cx + dx, cy + dy, w, c);
            for (int e = -1; e <= 1; e += 2) {
                double out = a + (e < 0 ? Math.PI : 0.0);
                float bx = cx + (float) Math.cos(out) * r * 0.62f;
                float by = cy + (float) Math.sin(out) * r * 0.62f;
                for (int k = -1; k <= 1; k += 2) {
                    double b = out + k * 0.8;
                    Gfx.line(ms, bx, by, bx + (float) Math.cos(b) * r * 0.3f, by + (float) Math.sin(b) * r * 0.3f, w, c);
                }
            }
        }
    }

    private Fine selected() {
        return sel >= 0 && sel < fines.size() ? fines.get(sel) : null;
    }

    private static int visibleRows() {
        return (int) ((H - 12 - TOP) / ROW);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (confirm) return true;
        scroll = Math.max(0, Math.min(Math.max(0, fines.size() - visibleRows()), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (confirm && key == GLFW.GLFW_KEY_ESCAPE) {
            confirm = false;
            return true;
        }
        return super.keyPressed(key, scan, mods);
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
    }

    private void btn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean filled, float lmx, float lmy, Runnable r) {
        boolean hov = Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, true, filled, hov);
        hitL(x, y, w, h, r);
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        Gfx.text(ms, "AMENZILE MELE", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "POLIȚIA FLOWCITY  ·  AMENZI NEPLĂTITE", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        if (fines.isEmpty()) {
            frost(ms, 120, 86, W - 240, 150);
            flake(ms, W / 2f, 128, 20f, 1.2f, Gfx.fade(Sk.ACC, 0.9f));
            Gfx.text(ms, "Nu ai nicio amendă neplătită.", W / 2f, 182, 11, Sk.WHITE, 1, true, 0f);
            Gfx.text(ms, "Condu cu grijă și respectă regulile orașului!", W / 2f, 198, 7.4f, Sk.MUTED, 1);
            drawMsg(ms);
            return;
        }

        sel = Math.max(0, Math.min(sel, fines.size() - 1));
        summary(ms, lmx, lmy);
        list(ms, lmx, lmy);
        if (confirm) dialog(ms, lmx, lmy);
        drawMsg(ms);
    }

    private void drawMsg(GuiGraphics ms) {
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    /** Coloana din stanga: totalul de plata si detaliul amenzii selectate. */
    private void summary(GuiGraphics ms, float lmx, float lmy) {
        long total = 0;
        long extra = 0;
        int overdue = 0;
        for (Fine f : fines) {
            total += f.amountDue;
            if (f.amountDue > f.amount) extra += f.amountDue - f.amount;
            if (f.overdue) overdue++;
        }
        frost(ms, 12, 62, 150, 216);
        Gfx.rrect(ms, 24, 66, 28, 2, 0, overdue > 0 ? Sk.BAD : Sk.ACC);
        Gfx.text(ms, "TOTAL DE PLATĂ", 24, 82, 5.6f, Sk.FAINT, 0, false, 1.2f);
        Gfx.text(ms, money(total) + " lei", 24, 99, 14, overdue > 0 ? Sk.BAD : Sk.WHITE, 0, true, 0f);
        String sub = fines.size() + (fines.size() == 1 ? " amendă" : " amenzi");
        if (overdue > 0) sub += "  ·  " + overdue + " întârziat" + (overdue == 1 ? "ă" : "e");
        Gfx.text(ms, clip(sub, 6.2f, false, 126), 24, 110, 6.2f, overdue > 0 ? Sk.BAD : Sk.MUTED, 0);
        Gfx.rrect(ms, 24, 117, 126, 1f, 0, 0x30FFFFFF);

        Fine f = selected();
        if (f == null) return;
        Gfx.text(ms, "AMENDA SELECTATĂ", 24, 130, 5.6f, Sk.FAINT, 0, false, 1.2f);
        List<String> lines = wrap(f.reason, 8f, true, 126, 2);
        float y = 141;
        for (String ln : lines) {
            Gfx.text(ms, ln, 24, y, 8f, Sk.WHITE, 0, true, 0f);
            y += 10;
        }
        float iy = 164;
        Gfx.text(ms, clip("de la " + f.by, 6.2f, false, 126), 24, iy, 6.2f, Sk.MUTED, 0);
        Gfx.text(ms, clip("emisă " + f.date, 6.2f, false, 126), 24, iy + 9, 6.2f, Sk.MUTED, 0);
        if (f.overdue) Gfx.text(ms, "termen depășit", 24, iy + 18, 6.2f, Sk.BAD, 0);
        else if (!f.dueDate.isEmpty()) Gfx.text(ms, "termen " + f.dueDate, 24, iy + 18, 6.2f, Sk.WARN, 0);
        Gfx.rrect(ms, 24, 192, 126, 1f, 0, 0x30FFFFFF);
        Gfx.text(ms, "Valoare inițială", 24, 203, 6.2f, Sk.MUTED, 0);
        Gfx.text(ms, money(f.amount) + " lei", 150, 203, 6.6f, Sk.WHITE, 2, true, 0f);
        if (f.overdue || f.amountDue > f.amount) {
            Gfx.text(ms, "Penalizare +" + f.pct + "%", 24, 213, 6.2f, Sk.BAD, 0);
            Gfx.text(ms, "+" + money(Math.max(0, f.amountDue - f.amount)) + " lei", 150, 213, 6.6f, Sk.BAD, 2, true, 0f);
        }
        Gfx.text(ms, "DE PLATĂ", 24, 229, 6.2f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, money(f.amountDue) + " lei", 150, 230, 10, f.overdue ? Sk.BAD : Sk.WHITE, 2, true, 0f);
        btn(ms, "PLĂTEȘTE", 22, 240, 130, 30, true, lmx, lmy, () -> confirm = true);
    }

    private void list(GuiGraphics ms, float lmx, float lmy) {
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, fines.size() - visible)));
        float y = TOP;
        for (int i = scroll; i < Math.min(fines.size(), scroll + visible); i++) {
            Fine f = fines.get(i);
            final int idx = i;
            boolean on = i == sel;
            boolean hot = Sk.in(lmx, lmy, LX, y, LW, ROW - 6);
            Sk.slant(ms, LX, y, LW, ROW - 6, 6f, on ? Sk.ROW_HOT : hot ? 0xC0162338 : Sk.ROW);
            if (on) Gfx.rrect(ms, LX + 8, y + ROW - 8, LW - 14, 1.5f, 0, Sk.ACC);
            int tone = f.overdue ? Sk.BAD : Sk.WARN;
            Gfx.rrect(ms, LX + 14, y + 7, 3, ROW - 20, 0, tone);
            Gfx.text(ms, clip(f.reason, 8.6f, true, LW - 140), LX + 26, y + 15, 8.6f, Sk.WHITE, 0, true, 0f);
            String sub = "de la " + f.by + "  ·  " + f.date;
            Gfx.text(ms, clip(sub, 6.2f, false, LW - 140), LX + 26, y + 27, 6.2f, Sk.MUTED, 0);
            Gfx.text(ms, money(f.amountDue) + " lei", LX + LW - 14, y + 17, 10.5f, f.overdue ? Sk.BAD : Sk.WHITE, 2, true, 0f);
            String chip = f.overdue ? "ÎNTÂRZIATĂ +" + f.pct + "%" : !f.dueDate.isEmpty() ? "TERMEN " + f.dueDate : "ACTIVĂ";
            Gfx.text(ms, clip(chip, 5.6f, true, 124), LX + LW - 14, y + 28, 5.6f, tone, 2, true, 0.4f);
            hitL(LX, y, LW, ROW - 6, () -> sel = idx);
            y += ROW;
        }
        if (fines.size() > visible) {
            float trackH = H - 12 - TOP;
            float th = Math.max(14f, trackH * visible / fines.size());
            float ty = TOP + (trackH - th) * scroll / Math.max(1, fines.size() - visible);
            Gfx.rrect(ms, W - 16, TOP, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, W - 16, ty, 2, th, 0, Sk.ACC);
        }
    }

    /** Dialog de confirmare a platii (blocheaza clickurile de dedesubt). */
    private void dialog(GuiGraphics ms, float lmx, float lmy) {
        final Fine f = selected();
        if (f == null) {
            confirm = false;
            return;
        }
        hit(0, 0, width, height, () -> { });
        Gfx.rrect(ms, -3000, -3000, 7000, 7000, 0, 0xB0050A14);
        float bw = 300f;
        float bh = 122f;
        float bx = W / 2f - bw / 2f;
        float by = H / 2f - bh / 2f;
        Gfx.rrect(ms, bx - 1, by - 1, bw + 2, bh + 2, 9, 0x40FFFFFF);
        Gfx.rrect(ms, bx, by, bw, bh, 8, 0xFF14202F, 0xFF0B1320);
        Gfx.rrect(ms, bx + 16, by + 1, 36, 2, 0, Sk.ACC);
        Gfx.text(ms, "CONFIRMI PLATA?", bx + 16, by + 22, 11, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, clip(f.reason, 7.4f, false, bw - 32), bx + 16, by + 38, 7.4f, Sk.MUTED, 0);
        Gfx.text(ms, "Suma de plată", bx + 16, by + 62, 6.4f, Sk.FAINT, 0);
        Gfx.text(ms, money(f.amountDue) + " lei", bx + bw - 16, by + 64, 14, f.overdue ? Sk.BAD : Sk.WHITE, 2, true, 0f);
        final long id = f.id;
        btn(ms, "ANULEAZĂ", bx + 16, by + 80, 124, 28, false, lmx, lmy, () -> confirm = false);
        btn(ms, "PLĂTEȘTE", bx + bw - 140, by + 80, 124, 28, true, lmx, lmy, () -> {
            confirm = false;
            FinesClient.pay(id);
        });
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
