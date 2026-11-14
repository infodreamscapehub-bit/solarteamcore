package ro.solarstudios.flowcity.fines;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** "Amenzile mele": lista amenzilor primite din MDT, fiecare cu buton de plata (cash sau card). Tema de iarna FlowCity. */
public final class FinesScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int ROW = 44;

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
        scroll = 0;
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

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int visible = (int) ((H - 12 - 76) / ROW);
        scroll = Math.max(0, Math.min(Math.max(0, fines.size() - visible), scroll - (int) Math.signum(delta)));
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
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        Gfx.text(ms, "AMENZILE MELE", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "POLIȚIA FLOWCITY", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        long total = 0;
        for (Fine f : fines) total += f.amountDue;
        if (!fines.isEmpty()) {
            Gfx.text(ms, "DE PLATĂ", W - 48, 24, 6f, Sk.FAINT, 2, false, 1f);
            Gfx.text(ms, money(total) + " lei", W - 48, 38, 11.5f, Sk.BAD, 2, true, 0f);
        }

        if (fines.isEmpty()) {
            Gfx.text(ms, "Nu ai nicio amendă neplătită.", W / 2f, H / 2f - 2, 11, Sk.WHITE, 1, true, 0f);
            Gfx.text(ms, "Condu cu grijă!", W / 2f, H / 2f + 14, 8, Sk.MUTED, 1);
            return;
        }
        float top = 66f;
        int visible = (int) ((H - 12 - top) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, fines.size() - visible)));
        float y = top;
        for (int i = scroll; i < Math.min(fines.size(), scroll + visible); i++) {
            Fine f = fines.get(i);
            Sk.slant(ms, 12, y, W - 36, ROW - 6, 8f, Sk.ROW);
            Gfx.rrect(ms, 26, y + 8, 3, ROW - 22, 0, f.overdue ? Sk.BAD : Sk.WARN);
            Gfx.text(ms, clip(f.reason, 9.4f, true, 280), 38, y + 16, 9.4f, Sk.WHITE, 0, true, 0f);
            String sub = "de la " + f.by + "  ·  " + f.date;
            if (f.overdue) sub += "  ·  ÎNTÂRZIAT +" + f.pct + "%";
            else if (!f.dueDate.isEmpty()) sub += "  ·  termen " + f.dueDate;
            Gfx.text(ms, clip(sub, 6.6f, false, 280), 38, y + 29, 6.6f, f.overdue ? Sk.BAD : Sk.MUTED, 0);
            Gfx.text(ms, money(f.amountDue) + " lei", W - 140, y + 22, 11, f.overdue ? Sk.BAD : Sk.WHITE, 2, true, 0f);
            if (f.overdue) Gfx.text(ms, "inițial " + money(f.amount), W - 140, y + 33, 6f, Sk.FAINT, 2);
            boolean h = Sk.in(lmx, lmy, W - 124, y + 6, 94, 26);
            Sk.button(ms, "PLĂTEȘTE", W - 124, y + 6, 94, 26, true, true, h);
            final long id = f.id;
            hitL(W - 124, y + 6, 94, 26, () -> FinesClient.pay(id));
            y += ROW;
        }
        if (fines.size() > visible) {
            float trackH = H - 12 - top;
            float th = Math.max(14f, trackH * visible / fines.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, fines.size() - visible);
            Gfx.rrect(ms, W - 14, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, W - 14, ty, 2, th, 0, Sk.ACC);
        }
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
