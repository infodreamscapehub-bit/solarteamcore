package ro.solarstudios.flowcity.clothes;

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

/** Garderoba: tinute civile, uniforme de job si de sarbatori; cumpara sau echipeaza. Tema de iarna FlowCity. */
public final class ClothesScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final String[] CATS = {"", "civil", "joburi", "sarbatori", "piese"};
    private static final String[] CAT_NAMES = {"TOATE", "CIVIL", "JOBURI", "SĂRBĂTORI", "PIESE"};
    private static final String[] SLOTS = {"", "cap", "fata", "top", "pantaloni", "pantofi", "accesoriu"};
    private static final String[] SLOT_NAMES = {"TOATE", "CAP", "FAȚĂ", "TOP", "PANTALONI", "PANTOFI", "ACCESORII"};
    private static final int CARD_W = 238;
    private static final int CARD_H = 52;
    private static final int STEP = CARD_H + 6;

    private static final class Outfit {
        String id;
        String name;
        String cat;
        int price;
        String state;
        String slot;
        String kind;
        boolean worn;
    }

    private final List<Outfit> all = new ArrayList<>();
    private int cat;
    private int slot;
    private int scroll;
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    ClothesScreen(JsonObject d) {
        super("Garderoba", W, H);
        update(d);
    }

    void update(JsonObject d) {
        all.clear();
        JsonArray a = d.has("outfits") && d.get("outfits").isJsonArray() ? d.getAsJsonArray("outfits") : new JsonArray();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            Outfit f = new Outfit();
            f.id = JsonClientModule.str(o, "id", "");
            f.name = JsonClientModule.str(o, "name", f.id);
            f.cat = JsonClientModule.str(o, "cat", "civil");
            f.price = JsonClientModule.integer(o, "price", 0);
            f.state = JsonClientModule.str(o, "state", "buy");
            f.worn = JsonClientModule.bool(o, "worn", false);
            f.slot = JsonClientModule.str(o, "slot", "");
            f.kind = JsonClientModule.str(o, "kind", "outfit");
            all.add(f);
        }
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

    private List<Outfit> shown() {
        List<Outfit> l = new ArrayList<>();
        for (Outfit f : all) {
            if (cat != 0 && !CATS[cat].equals(f.cat)) continue;
            if (cat != 0 && "piese".equals(CATS[cat]) && slot != 0 && !SLOTS[slot].equals(f.slot)) continue;
            l.add(f);
        }
        return l;
    }

    private boolean piecesTab() {
        return "piese".equals(CATS[cat]);
    }

    private float listTop() {
        return piecesTab() ? 108f : 90f;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int rows = (shown().size() + 1) / 2;
        int visible = 3;
        scroll = Math.max(0, Math.min(Math.max(0, rows - visible), scroll - (int) Math.signum(delta)));
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
        Gfx.text(ms, "GARDEROBA", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "TINUTE · UNIFORME · SĂRBĂTORI", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        float cx = 16;
        for (int i = 0; i < CATS.length; i++) {
            float w = Gfx.width(CAT_NAMES[i], 7f, true) + 20;
            boolean on = i == cat;
            boolean h = Sk.in(lmx, lmy, cx, 60, w, 18);
            Sk.slant(ms, cx, 60, w, 18, 5f, on ? Sk.ACC : h ? Sk.ROW_HOT : Sk.ROW);
            Gfx.text(ms, CAT_NAMES[i], cx + w / 2f, 72, 7f, on ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
            final int ci = i;
            hitL(cx, 60, w, 18, () -> {
                cat = ci;
                slot = 0;
                scroll = 0;
            });
            cx += w + 6;
        }
        if (piecesTab()) {
            float sx = 16;
            for (int i = 0; i < SLOTS.length; i++) {
                float w = Gfx.width(SLOT_NAMES[i], 6.4f, true) + 14;
                boolean on = i == slot;
                boolean h = Sk.in(lmx, lmy, sx, 82, w, 16);
                Sk.slant(ms, sx, 82, w, 16, 4f, on ? Sk.ACC_DARK : h ? Sk.ROW_HOT : Sk.ROW);
                Gfx.text(ms, SLOT_NAMES[i], sx + w / 2f, 92.5f, 6.4f, on ? Sk.WHITE : Sk.MUTED, 1, true, 0f);
                final int si = i;
                hitL(sx, 82, w, 16, () -> {
                    slot = si;
                    scroll = 0;
                });
                sx += w + 5;
            }
        }

        List<Outfit> list = shown();
        if (list.isEmpty()) {
            Gfx.text(ms, "Nimic aici.", W / 2f, H / 2f + 30, 10, Sk.MUTED, 1);
        }
        int rows = (list.size() + 1) / 2;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows - 3)));
        for (int i = scroll * 2; i < Math.min(list.size(), (scroll + 3) * 2); i++) {
            Outfit f = list.get(i);
            int rr = i / 2 - scroll;
            float x = 14 + (i % 2) * (CARD_W + 10);
            float y = listTop() + rr * STEP;
            Sk.slant(ms, x, y, CARD_W, CARD_H, 8f, f.worn ? Sk.ROW_HOT : Sk.ROW);
            Gfx.rrect(ms, x + 10, y + 10, 3, CARD_H - 20, 0, f.worn ? Sk.GOOD : Sk.ACC);
            Gfx.text(ms, f.name, x + 22, y + 20, 9.4f, Sk.WHITE, 0, true, 0f);
            String sub = "locked".equals(f.state) ? "Doar pentru jobul potrivit" : f.worn ? "Echipată" : "owned".equals(f.state) ? ("piece".equals(f.kind) ? "În garderobă  ·  " + f.slot : "În garderobă") : f.price + " lei" + ("piece".equals(f.kind) ? "  ·  " + f.slot : "");
            Gfx.text(ms, sub, x + 22, y + 33, 6.8f, "locked".equals(f.state) ? Sk.WARN : f.worn ? Sk.GOOD : Sk.MUTED, 0);
            float bx = x + CARD_W - 92;
            float by = y + 15;
            if ("locked".equals(f.state)) {
                Sk.button(ms, "BLOCAT", bx, by, 80, 26, false, false, false);
            } else if (f.worn) {
                boolean h = Sk.in(lmx, lmy, bx, by, 80, 26);
                Sk.button(ms, "SCOATE", bx, by, 80, 26, true, false, h);
                final String off = "piece".equals(f.kind) ? f.id : "";
                hitL(bx, by, 80, 26, () -> ClothesClient.send("equip", off));
            } else if ("owned".equals(f.state)) {
                boolean h = Sk.in(lmx, lmy, bx, by, 80, 26);
                Sk.button(ms, "ECHIPEAZĂ", bx, by, 80, 26, true, true, h);
                final String id = f.id;
                hitL(bx, by, 80, 26, () -> ClothesClient.send("equip", id));
            } else {
                boolean h = Sk.in(lmx, lmy, bx, by, 80, 26);
                Sk.button(ms, "CUMPĂRĂ", bx, by, 80, 26, true, true, h);
                final String id = f.id;
                hitL(bx, by, 80, 26, () -> ClothesClient.send("buy", id));
            }
        }
        if (rows > 3) {
            float trackH = 3 * STEP - 6;
            float th = Math.max(14f, trackH * 3 / rows);
            float ty = listTop() + (trackH - th) * scroll / Math.max(1, rows - 3);
            Gfx.rrect(ms, W - 10, listTop(), 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, W - 10, ty, 2, th, 0, Sk.ACC);
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
