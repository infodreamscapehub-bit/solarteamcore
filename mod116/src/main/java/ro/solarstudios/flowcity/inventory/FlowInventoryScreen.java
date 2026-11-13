package ro.solarstudios.flowcity.inventory;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.matrix.MatrixStack;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.gui.screen.inventory.InventoryScreen;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Inventarul FlowCity (in stil FiveM): personajul 3D cu armura in stanga, inventarul si hotbar-ul cu sloturi
 * rotunjite in dreapta, crafting mic, bani si cat de plin esti. Sloturile sunt cele ale jocului (merge cu orice mod);
 * le mutam doar pe client cat timp e deschis ecranul si le punem la loc la inchidere.
 */
public final class FlowInventoryScreen extends ContainerScreen<PlayerContainer> {
    private static final int W = 360;
    private static final int H = 232;
    private static final int GX = 148;   // inceputul grilei
    private static final int GY = 62;
    private static final int STEP = 22;
    private static final String[] ARMOR = {"Cap", "Piept", "Picioare", "Bocanci"};

    private final int[][] original;
    private final ItemStack[] last;
    private final long[] changedAt;
    private final float[] hov;
    private final long born = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float xMouse;
    private float yMouse;

    public FlowInventoryScreen(ClientPlayerEntity player) {
        super(player.inventoryMenu, player.inventory, new StringTextComponent("Inventar"));
        this.imageWidth = W;
        this.imageHeight = H;
        List<Slot> sl = menu.slots;
        original = new int[sl.size()][2];
        last = new ItemStack[sl.size()];
        changedAt = new long[sl.size()];
        hov = new float[sl.size()];
        for (int i = 0; i < sl.size(); i++) {
            original[i][0] = sl.get(i).x;
            original[i][1] = sl.get(i).y;
        }
        layout();
    }

    // ------------------------------------------------------------------ asezare

    private static Field fx;
    private static Field fy;

    private static void move(Slot s, int x, int y) {
        try {
            if (fx == null) {
                fx = ObfuscationReflectionHelper.findField(Slot.class, "field_75223_e");
                fy = ObfuscationReflectionHelper.findField(Slot.class, "field_75221_f");
                fx.setAccessible(true);
                fy.setAccessible(true);
            }
            fx.setInt(s, x);
            fy.setInt(s, y);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // raman pozitiile jocului
        }
    }

    /** 0 rezultat, 1-4 crafting, 5-8 armura, 9-35 inventar, 36-44 hotbar, 45 mana stanga. */
    private void layout() {
        List<Slot> sl = menu.slots;
        for (int i = 0; i < sl.size(); i++) {
            Slot s = sl.get(i);
            if (i == 0) move(s, GX + 2 * STEP + 34 + 3, 176 + 12);
            else if (i <= 4) move(s, GX + 3 + ((i - 1) % 2) * STEP, 176 + 3 + ((i - 1) / 2) * STEP);
            else if (i <= 8) move(s, 22, 50 + (i - 5) * 30);
            else if (i <= 35) move(s, GX + 3 + ((i - 9) % 9) * STEP, GY + 3 + ((i - 9) / 9) * STEP);
            else if (i <= 44) move(s, GX + 3 + (i - 36) * STEP, GY + 3 + 3 * STEP + 10);
            else if (i == 45) move(s, 106, 170);
        }
    }

    @Override
    public void removed() {
        super.removed();
        List<Slot> sl = menu.slots;
        for (int i = 0; i < sl.size() && i < original.length; i++) move(sl.get(i), original[i][0], original[i][1]);
    }

    // ------------------------------------------------------------------ desen

    @Override
    public void render(MatrixStack ms, int mx, int my, float pt) {
        xMouse = mx;
        yMouse = my;
        super.render(ms, mx, my, pt);
        renderTooltip(ms, mx, my);
    }

    @Override
    protected void renderLabels(MatrixStack ms, int mx, int my) {
        // titlurile le desenam noi
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    @Override
    protected void renderBg(MatrixStack pose, float pt, int mx, int my) {
        GuiGraphics g = new GuiGraphics(pose);
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float open = ease((now - born) / 260f);
        double t = now / 1000.0;

        Gfx.rrect(g, 0, 0, width, height, 0, Gfx.fade(0x99050608, open));
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        Gfx.rrect(g, -4, 2, W + 8, H + 6, 18, Gfx.fade(0x55000000, open));
        Gfx.rrect(g, 0, 0, W, H, 16, Gfx.fade(0xFF1C1F25, open), Gfx.fade(0xFF121418, open));
        Gfx.rrect(g, 0.5f, 0.5f, W - 1, 1, 0, Gfx.fade(0x14FFFFFF, open));

        // personaj
        Gfx.rrect(g, 10, 10, 128, H - 20, 12, Gfx.fade(0xFF0E1013, open));
        Gfx.scrim(g, 80, 120, 56, 80, Gfx.fade(0x26E9A23B, open));
        Gfx.scrim(g, 80, 196, 34, 5, Gfx.fade(0x66000000, open));
        String name = mc.player.getName().getString();
        Gfx.text(g, Ui.clip(name, 110), 74, 28, 10, Gfx.fade(0xFFF4F1EA, open), 1, true, 0);
        int lvl = mc.player.experienceLevel;
        Gfx.text(g, "Nivel " + lvl, 74, 39, 7.5f, Gfx.fade(0xFF8E8C86, open), 1);
        for (int i = 0; i < 4; i++) Gfx.text(g, ARMOR[i], 21, 50 + i * 30 - 3, 6.5f, Gfx.fade(0xFF6F6D68, open), 0, false, 0.4f);
        Gfx.text(g, "Mana", 105, 167, 6.5f, Gfx.fade(0xFF6F6D68, open), 0, false, 0.4f);

        // antet dreapta: titlu, bani, ocupare
        Gfx.text(g, "INVENTAR", GX, 26, 13, Gfx.fade(0xFFF4F1EA, open), 0, true, 1f);
        JsonObject st = ro.solarstudios.flowcity.hype.HypeState.state;
        if (st != null && st.has("cash")) {
            String cash = "$" + Ui.money(JsonClientModule.num(st, "cash", 0));
            String bank = "Banca $" + Ui.money(JsonClientModule.num(st, "bank", 0));
            Gfx.text(g, cash, W - 12, 25, 12, Gfx.fade(0xFF5FDC8F, open), 2, true, 0);
            Gfx.text(g, bank, W - 12, 36, 7.5f, Gfx.fade(0xFF8E8C86, open), 2);
        }
        int used = 0;
        for (int i = 9; i <= 44 && i < menu.slots.size(); i++) if (menu.slots.get(i).hasItem()) used++;
        float frac = used / 36f;
        Gfx.text(g, "Ocupat " + used + " / 36", GX, 44, 7.5f, Gfx.fade(0xFF8E8C86, open), 0);
        float bx = GX + 70;
        float bw = 9 * STEP - 2 - 70;
        Gfx.rrect(g, bx, 40, bw, 4, 2, Gfx.fade(0xFF262A31, open));
        int bc = frac > 0.9f ? 0xFFE2453F : (frac > 0.7f ? 0xFFE9A23B : 0xFF4CC878);
        Gfx.rrect(g, bx, 40, Math.max(4, bw * frac), 4, 2, Gfx.fade(bc, open));

        // sloturi
        List<Slot> sl = menu.slots;
        for (int i = 0; i < sl.size(); i++) {
            Slot s = sl.get(i);
            ItemStack is = s.getItem();
            if (last[i] == null || !ItemStack.matches(last[i], is)) {
                if (last[i] != null && now - born > 300) changedAt[i] = now;
                last[i] = is.copy();
            }
            boolean h = Ui.in(mx, my, leftPos + s.x - 2, topPos + s.y - 2, 20, 20);
            hov[i] += ((h ? 1f : 0f) - hov[i]) * Math.min(1f, dt * 14f);
            boolean hot = i >= 36 && i <= 44;
            boolean sel = hot && i - 36 == mc.player.inventory.selected;
            float pop = Math.max(0f, 1f - (now - changedAt[i]) / 350f);
            float x = s.x - 2 - pop * 1.5f;
            float y = s.y - 2 - pop * 1.5f;
            float sz = 20 + pop * 3;
            if (hov[i] > 0.02f) Gfx.rrect(g, x - 2, y - 2, sz + 4, sz + 4, 8, Gfx.fade(0x2EE9A23B, open * hov[i]));
            int border = sel ? 0xFFE9A23B : Gfx.mixArgb(i == 0 ? 0xFF4A4E58 : 0xFF2A2E36, 0xFF6F7380, hov[i]);
            Gfx.rrect(g, x, y, sz, sz, 6, Gfx.fade(border, open));
            int fill = i == 0 ? 0xFF22252B : (hot ? 0xFF1B1E24 : 0xFF17191E);
            Gfx.rrect(g, x + 1, y + 1, sz - 2, sz - 2, 5, Gfx.fade(Gfx.mixArgb(fill, 0xFF262A32, hov[i]), open));
            if (pop > 0) Gfx.rrect(g, x - 1, y - 1, sz + 2, sz + 2, 7, Gfx.fade(0x55F6C15A, open * pop));
            if (hot) Gfx.text(g, String.valueOf(i - 35), x + 3, y + 7, 5.5f, Gfx.fade(0x55FFFFFF, open), 0);
            if (sel) Gfx.rrect(g, x + 5, y + sz - 2.5f, sz - 10, 1.5f, 0.75f, Gfx.fade(0xFFE9A23B, open));
        }
        // eticheta hotbar + crafting
        Gfx.text(g, "RAPID 1-9", GX, GY + 3 * STEP + 8, 6.5f, Gfx.fade(0xFF6F6D68, open), 0, false, 0.6f);
        Gfx.text(g, "CRAFTING", GX, 174, 6.5f, Gfx.fade(0xFF6F6D68, open), 0, false, 0.6f);
        float ax = GX + 2 * STEP + 8;
        float ay = 176 + 22;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 3);
        int ac = sl.get(0).hasItem() ? Gfx.mixArgb(0xFFE9A23B, 0xFFF6C15A, pulse) : 0xFF3A3E47;
        Gfx.line(g, ax, ay, ax + 18, ay, 1.6f, Gfx.fade(ac, open));
        Gfx.poly(g, new float[] {ax + 22, ay, ax + 16, ay - 4, ax + 16, ay + 4}, Gfx.fade(ac, open));
        Gfx.text(g, "E / ESC  inchide", W - 12, H - 10, 7, Gfx.fade(0xFF5C606A, open), 2);
        g.pose().popPose();

        // personajul 3D (se uita dupa mouse)
        float cx = leftPos + 80;
        float cy = topPos + 186;
        InventoryScreen.renderEntityInInventory((int) cx, (int) cy, 48, cx - xMouse, cy - 90 - yMouse, mc.player);
    }
}
