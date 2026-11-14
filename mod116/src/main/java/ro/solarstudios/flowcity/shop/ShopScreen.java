package ro.solarstudios.flowcity.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * E-shop deschis de un NPC (tema de iarna FlowCity, ca DealerScreen/FinesScreen): categorii in stanga, carduri cu iconita itemului (orice item din orice mod), la selectie
 * cantitate + CUMPARA. Plata (cash sau card) vine din PayScreen. Desenat tot din cod.
 */
public final class ShopScreen extends FlowScreen {
    private static final int W = 480;
    private static final int H = 300;
    private static final int COLS = 3;
    private static final int CW = 108;
    private static final int CH = 82;
    private static final int GAP = 8;

    private static final class It {
        String name;
        double price;
        int amount;
        String desc;
        ItemStack stack = ItemStack.EMPTY;
    }

    private final String title;
    private final List<String> catNames = new ArrayList<>();
    private final List<List<It>> cats = new ArrayList<>();
    private int cat;
    private int sel = -1;
    private int qty = 1;
    private int scroll;
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private final long born = System.currentTimeMillis();
    private float offX;
    private float offY;
    private float scale = 1f;

    ShopScreen(JsonObject d) {
        super("Magazin", W, H);
        this.title = JsonClientModule.str(d, "title", "MAGAZIN");
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

    private static String money(double v) {
        String s = String.valueOf(Math.round(v));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private List<It> items() {
        return cat >= 0 && cat < cats.size() ? cats.get(cat) : new ArrayList<>();
    }

    private int rowsTotal() {
        return (items().size() + COLS - 1) / COLS;
    }

    private static final int GX = 142;
    private static final int GY = 58;
    private static final int GH = 168;

    private int maxScroll() {
        return Math.max(0, rowsTotal() * (CH + GAP) - GAP - GH);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(delta) * 30));
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
        Gfx.text(ms, title, 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "PLATĂ CASH SAU CARD", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        // categorii
        float cy = 66;
        for (int i = 0; i < catNames.size(); i++) {
            boolean active = i == cat;
            boolean hot = Sk.in(lmx, lmy, 18, cy, 112, 24);
            Sk.slant(ms, 18, cy, 112, 24, 7f, active ? Sk.ACC : (hot ? Sk.ROW_HOT : Sk.ROW));
            Gfx.text(ms, catNames.get(i), 74 + 3, cy + 15.5f, 8.6f, active ? 0xFF061426 : (hot ? Sk.WHITE : Sk.MUTED), 1, true, 0.3f);
            final int ci = i;
            hitL(18, cy, 112, 24, () -> {
                cat = ci;
                sel = -1;
                scroll = 0;
                qty = 1;
            });
            cy += 29;
        }

        // carduri
        List<It> its = items();
        Gfx.rrect(ms, GX - 6, GY - 4, W - GX - 12, GH + 8, 0, 0x14FFFFFF);
        for (int i = 0; i < its.size(); i++) {
            int col = i % COLS;
            int row = i / COLS;
            float x = GX + col * (CW + GAP);
            float y = GY + row * (CH + GAP) - scroll;
            if (y + CH < GY - 2 || y > GY + GH + 2) continue;
            It it = its.get(i);
            boolean chosen = i == sel;
            boolean hot = Sk.in(lmx, lmy, x, y, CW, CH) && lmy >= GY && lmy < GY + GH;
            if (chosen) Sk.cut(ms, x - 2, y - 2, CW + 4, CH + 4, 10, Sk.ACC);
            Sk.cut(ms, x, y, CW, CH, 9, chosen || hot ? Sk.ROW_HOT : Sk.ROW);
            if (!it.stack.isEmpty()) {
                ms.pose().pushPose();
                ms.pose().translate(x + CW / 2f - 16, y + 8, 0);
                ms.pose().scale(2f, 2f, 1f);
                ms.renderItem(it.stack, 0, 0);
                ms.pose().popPose();
            } else {
                Gfx.disc(ms, x + CW / 2f, y + 24, 12, 0xFF14202F);
                Gfx.text(ms, "?", x + CW / 2f, y + 29, 12, Sk.MUTED, 1, true, 0f);
            }
            String nm = it.name;
            while (nm.length() > 1 && Gfx.width(nm, 8f, false) > CW - 10) nm = nm.substring(0, nm.length() - 1);
            Gfx.text(ms, nm, x + CW / 2f, y + 52, 8f, Sk.WHITE, 1);
            Gfx.text(ms, money(it.price) + " lei" + (it.amount > 1 ? "  ·  " + it.amount + " buc" : ""), x + CW / 2f, y + 66, 7.4f, Sk.ACC, 1);
            if (y >= GY - 4 && y + CH <= GY + GH + 4) {
                final int fi = i;
                hitL(x, Math.max(y, GY), CW, Math.min(CH, GY + GH - Math.max(y, GY)), () -> {
                    sel = fi;
                    qty = 1;
                });
            }
        }
        if (its.isEmpty()) Gfx.text(ms, "Nimic de vândut aici.", GX + 140, GY + 80, 8.4f, Sk.MUTED, 1);
        if (maxScroll() > 0) {
            float th = Math.max(20, GH * GH / (float) (GH + maxScroll()));
            float ty = GY + (GH - th) * scroll / maxScroll();
            Gfx.rrect(ms, W - 16, GY, 2, GH, 0, 0xFF14202F);
            Gfx.rrect(ms, W - 16, ty, 2, th, 0, Sk.ACC);
        }

        // bara de jos: selectie + cantitate + cumpara
        float by = H - 56;
        Sk.slant(ms, 12, by, W - 36, 40, 8f, Sk.PANEL);
        if (sel >= 0 && sel < its.size()) {
            It it = its.get(sel);
            Gfx.text(ms, it.name, 32, by + 17, 10, Sk.WHITE, 0, true, 0f);
            if (!it.desc.isEmpty()) Gfx.text(ms, it.desc, 32, by + 30, 7.2f, Sk.MUTED, 0);
            float qx = W - 270;
            step(ms, qx, by + 9, "-", lmx, lmy, () -> qty = Math.max(1, qty - 1));
            Gfx.text(ms, String.valueOf(qty), qx + 40, by + 25, 11, Sk.WHITE, 1, true, 0f);
            step(ms, qx + 58, by + 9, "+", lmx, lmy, () -> qty = Math.min(64, qty + 1));
            double total = it.price * qty;
            boolean hb = Sk.in(lmx, lmy, W - 168, by + 6, 138, 28);
            Sk.button(ms, "CUMPĂRĂ  " + money(total) + " lei", W - 168, by + 6, 138, 28, true, true, hb);
            final int fq = qty;
            final int fs = sel;
            hitL(W - 168, by + 6, 138, 28, () -> ShopClient.buy(cat, fs, fq));
        } else {
            Gfx.text(ms, "Alege un articol.", 32, by + 24, 8.6f, Sk.FAINT, 0);
        }
        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    private void step(GuiGraphics ms, float x, float y, String label, float lmx, float lmy, Runnable r) {
        boolean hv = Sk.in(lmx, lmy, x, y, 22, 22);
        Gfx.rrect(ms, x, y, 22, 22, 0, hv ? Sk.ACC : 0xFF14202F);
        Gfx.text(ms, label, x + 11, y + 15.5f, 11, hv ? 0xFF061426 : Sk.WHITE, 1, true, 0f);
        hitL(x, y, 22, 22, r);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
