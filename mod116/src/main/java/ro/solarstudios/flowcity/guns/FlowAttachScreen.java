package ro.solarstudios.flowcity.guns;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Meniul de atasamente FlowCity pentru Timeless and Classics (TaC, 1.16.5). Foloseste acelasi container ca ecranul TaC
 * (punerea/scoaterea atasamentelor ramane a lor); noi doar mutam sloturile pe client si desenam totul: arma 3D care se
 * roteste, casute cu iconite si linii spre arma, inventarul cu sloturi rotunjite si animatii.
 */
public final class FlowAttachScreen extends ContainerScreen<Container> {
    private static final int W = 300;
    private static final int H = 266;
    /** Ordinea sloturilor TaC: SCOPE, BARREL, STOCK, UNDER_BARREL, SIDE_RAIL, EXTENDED_MAG, GUN_SKIN, AMMO. */
    private static final String[] NAMES = {"Luneta", "Teava", "Pat", "Sub teava", "Lateral", "Incarcator", "Skin", "Munitie"};
    /** Pozitia casutelor (colt stanga-sus, 24x24) in fereastra. */
    private static final int[][] BOX = {{138, 38}, {256, 80}, {20, 80}, {184, 124}, {214, 38}, {108, 124}, {20, 38}, {20, 124}};
    /** Punctul de pe arma spre care merge linia fiecarei casute (relativ la centrul previzualizarii). */
    private static final float[][] ANCHOR = {{0, -16}, {52, -2}, {-50, 0}, {24, 12}, {18, -8}, {-4, 14}, {-20, 0}, {-12, 16}};
    private static final int PX = 12;
    private static final int PY = 32;
    private static final int PW = 276;
    private static final int PH = 120;

    private final List<Slot> attach = new ArrayList<>();
    private final List<Slot> inv = new ArrayList<>();
    private final ItemStack[] last = new ItemStack[8];
    private final long[] changedAt = new long[8];
    private final float[] hover = new float[8];
    private final long born = System.currentTimeMillis();
    private long lastNs = System.nanoTime();
    private float rotY = -30f;
    private float rotX = 12f;
    private float zoom = 1f;
    private boolean spun;

    public FlowAttachScreen(ContainerScreen<?> original) {
        super(cast(original.getMenu()), Minecraft.getInstance().player.inventory, original.getTitle());
        this.imageWidth = W;
        this.imageHeight = H;
        for (Slot s : menu.slots) {
            if (s.getClass().getName().endsWith("AttachmentSlot") && attach.size() < 8) attach.add(s);
            else inv.add(s);
        }
        layout();
    }

    @SuppressWarnings("unchecked")
    private static Container cast(Object c) {
        return (Container) c;
    }

    // ------------------------------------------------------------------ asezarea sloturilor (doar pe client)

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
            // daca nu putem muta, sloturile raman unde le-a pus TaC
        }
    }

    private void layout() {
        for (int i = 0; i < attach.size(); i++) move(attach.get(i), BOX[i][0] + 4, BOX[i][1] + 4);
        int x0 = (W - 9 * 20 + 2) / 2;
        for (int i = 0; i < inv.size(); i++) {
            if (i < 27) move(inv.get(i), x0 + (i % 9) * 20, 176 + (i / 9) * 20);
            else move(inv.get(i), x0 + ((i - 27) % 9) * 20, 242);
        }
    }

    // ------------------------------------------------------------------ desen

    @Override
    public void render(MatrixStack ms, int mx, int my, float pt) {
        super.render(ms, mx, my, pt);
        // casuta goala sub mouse: nume + "Gol"
        for (int i = 0; i < attach.size(); i++) {
            Slot s = attach.get(i);
            if (!s.hasItem() && Ui.in(mx, my, leftPos + BOX[i][0], topPos + BOX[i][1], 24, 24)) {
                String t = s.isActive() ? NAMES[i] + " - gol" : NAMES[i] + " - nu se potriveste";
                renderTooltip(ms, new StringTextComponent(t), mx, my);
            }
        }
        renderTooltip(ms, mx, my);
    }

    @Override
    protected void renderLabels(MatrixStack ms, int mx, int my) {
        // titlurile le desenam noi in renderBg
    }

    @Override
    protected void renderBg(MatrixStack pose, float pt, int mx, int my) {
        GuiGraphics g = new GuiGraphics(pose);
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        double t = now / 1000.0;
        float open = ease((now - born) / 260f);

        Gfx.rrect(g, 0, 0, width, height, 0, Gfx.fade(0xB0050608, open));

        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        // fereastra
        Gfx.rrect(g, -4, 0, W + 8, H + 6, 18, Gfx.fade(0x55000000, open));
        Gfx.rrect(g, 0, 0, W, H, 16, Gfx.fade(0xFF1C1F25, open), Gfx.fade(0xFF121418, open));
        Gfx.rrect(g, 0.5f, 0.5f, W - 1, 1, 0, Gfx.fade(0x14FFFFFF, open));

        // antet
        Gfx.text(g, "ATASAMENTE", 14, 20, 13, Gfx.fade(0xFFF4F1EA, open), 0, true, 1f);
        String gun = getTitle().getString();
        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty()) gun = held.getHoverName().getString();
        float aw = Gfx.width("ATASAMENTE", 13, true) + 10 + 14;
        Gfx.text(g, gun, 14 + aw, 19.5f, 10, Gfx.fade(0xFFE9A23B, open), 0);
        Gfx.text(g, "Trage = roteste   Rotita = zoom", W - 14, 19.5f, 8.5f, Gfx.fade(0xFF6F6D68, open), 2);

        // previzualizare
        Gfx.rrect(g, PX, PY, PW, PH, 12, Gfx.fade(0xFF0E1013, open));
        float pcx = PX + PW / 2f;
        float pcy = PY + PH / 2f + 2;
        Gfx.scrim(g, pcx, pcy, 110, 46, Gfx.fade(0x33E9A23B, open));
        // podea / reflexie
        Gfx.scrim(g, pcx, PY + PH - 16, 90, 7, Gfx.fade(0x55000000, open));
        g.pose().popPose();

        drawGun(pose, mc, held, leftPos + pcx, topPos + pcy, dt, open);

        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        // casute + linii spre arma
        for (int i = 0; i < attach.size(); i++) {
            Slot s = attach.get(i);
            ItemStack st = s.getItem();
            if (last[i] == null || !ItemStack.matches(last[i], st)) {
                if (last[i] != null) changedAt[i] = now;
                last[i] = st.copy();
            }
            boolean active = s.isActive();
            boolean full = s.hasItem();
            boolean h = Ui.in(mx, my, leftPos + BOX[i][0], topPos + BOX[i][1], 24, 24);
            hover[i] += ((h ? 1f : 0f) - hover[i]) * Math.min(1f, dt * 14f);
            float bx = BOX[i][0];
            float by = BOX[i][1];
            float ax = pcx + ANCHOR[i][0] * zoom;
            float ay = pcy + ANCHOR[i][1] * zoom;
            float cx = bx + 12;
            float cy = by + 12;
            if (active) {
                int lc = full ? 0xFFE9A23B : 0x40FFFFFF;
                dashed(g, cx, cy, ax, ay, full ? 1.2f : 0.9f, Gfx.fade(lc, open * (full ? 0.85f : 0.6f)), full ? 0 : t);
                float pulse = full ? 0.5f + 0.5f * (float) Math.sin(t * 3 + i) : 0f;
                if (full) Gfx.disc(g, ax, ay, 4 + 3 * pulse, Gfx.fade(0x55E9A23B, open), 0x00E9A23B, 0, 0);
                Gfx.disc(g, ax, ay, 1.8f, Gfx.fade(full ? 0xFFF6C15A : 0x80FFFFFF, open));
            }
            // casuta
            float flash = Math.max(0f, 1f - (now - changedAt[i]) / 500f);
            if (flash > 0) Gfx.rrect(g, bx - 4 * flash - 2, by - 4 * flash - 2, 28 + 8 * flash, 28 + 8 * flash, 10, Gfx.fade(0x66F6C15A, open * flash));
            int border = !active ? 0xFF22252B : (full ? 0xFFE9A23B : Gfx.mixArgb(0xFF353943, 0xFF8E8C86, hover[i]));
            if (h && active) Gfx.rrect(g, bx - 2, by - 2, 28, 28, 9, Gfx.fade(0x30E9A23B, open));
            Gfx.rrect(g, bx, by, 24, 24, 7, Gfx.fade(border, open));
            Gfx.rrect(g, bx + 1, by + 1, 22, 22, 6, Gfx.fade(active ? (full ? 0xFF2A2418 : 0xFF181B20) : 0xFF14161A, open));
            if (!full) slotIcon(g, i, bx + 12, by + 12, active ? Gfx.fade(0xFF5E636D, open) : Gfx.fade(0xFF2E3238, open));
            // eticheta
            boolean leftCol = bx < 60;
            boolean rightCol = bx > 240;
            float lx = leftCol ? bx + 30 : (rightCol ? bx - 6 : bx + 12);
            float ly = leftCol || rightCol ? by + 15 : (by < 80 ? by - 3 : by + 33);
            int align = leftCol ? 0 : (rightCol ? 2 : 1);
            int lcol = !active ? 0xFF3A3D44 : (full ? 0xFFE9D3A8 : 0xFF8E8C86);
            Gfx.text(g, NAMES[i].toUpperCase(), lx, ly, 7, Gfx.fade(lcol, open), align, false, 0.6f);
        }

        // inventar
        int x0 = (W - 9 * 20 + 2) / 2;
        Gfx.text(g, "INVENTAR", x0, 170, 7.5f, Gfx.fade(0xFF8E8C86, open), 0, false, 0.8f);
        for (int i = 0; i < inv.size(); i++) {
            Slot s = inv.get(i);
            int sx = s.x - 1;
            int sy = s.y - 1;
            boolean hot = i >= 27;
            boolean h = Ui.in(mx, my, leftPos + sx, topPos + sy, 18, 18);
            int fill = h ? 0xFF262A32 : (hot ? 0xFF1B1E24 : 0xFF181B20);
            Gfx.rrect(g, sx, sy, 18, 18, 5, Gfx.fade(h ? 0xFF4A4E58 : 0xFF2A2E36, open));
            Gfx.rrect(g, sx + 1, sy + 1, 16, 16, 4, Gfx.fade(fill, open));
            if (hot && i - 27 == mc.player.inventory.selected) Gfx.rrect(g, sx + 3, sy + 16, 12, 1.5f, 0.75f, Gfx.fade(0xFFE9A23B, open));
        }
        Gfx.rrect(g, x0, 236, 9 * 20 - 2, 1, 0, Gfx.fade(0x14FFFFFF, open));
        g.pose().popPose();
    }

    /** Arma 3D in centrul previzualizarii: se roteste singura usor, iar cu mouse-ul o invarti. */
    private void drawGun(MatrixStack pose, Minecraft mc, ItemStack gun, float cx, float cy, float dt, float open) {
        if (gun.isEmpty()) return;
        if (!spun) rotY += dt * 12f;
        float bob = (float) Math.sin(System.currentTimeMillis() / 700.0) * 1.5f;
        Ui.scissor(leftPos + PX, topPos + PY, PW, PH);
        pose.pushPose();
        pose.translate(cx, cy + bob, 150);
        float s = 74f * zoom * (0.9f + 0.1f * open);
        pose.scale(s, -s, s);
        pose.mulPose(Vector3f.XP.rotationDegrees(rotX));
        pose.mulPose(Vector3f.YP.rotationDegrees(rotY));
        RenderSystem.enableDepthTest();
        RenderHelper.setupFor3DItems();
        IRenderTypeBuffer.Impl buf = mc.renderBuffers().bufferSource();
        try {
            mc.getItemRenderer().renderStatic(gun, ItemCameraTransforms.TransformType.FIXED, 15728880, OverlayTexture.NO_OVERLAY, pose, buf);
            buf.endBatch();
        } catch (RuntimeException ignored) {
            // un model ciudat nu trebuie sa strice ecranul
        }
        RenderSystem.disableDepthTest();
        pose.popPose();
        Ui.endScissor();
    }

    /** Iconita discreta pentru casuta goala (desenata, fara imagini). */
    private static void slotIcon(GuiGraphics g, int i, float cx, float cy, int c) {
        switch (i) {
            case 0: // luneta: cerc cu cruce
                Gfx.ring(g, cx, cy, 4.2f, 5.4f, c, c);
                Gfx.line(g, cx - 7, cy, cx + 7, cy, 1f, c);
                Gfx.line(g, cx, cy - 7, cx, cy + 7, 1f, c);
                break;
            case 1: // teava
                Gfx.rrect(g, cx - 7, cy - 2, 14, 4, 2, c);
                Gfx.rrect(g, cx + 4, cy - 3, 3, 6, 1, c);
                break;
            case 2: // pat
                Gfx.poly(g, new float[] {cx - 7, cy - 3, cx + 6, cy - 3, cx + 6, cy + 1, cx - 2, cy + 5, cx - 7, cy + 5}, c);
                break;
            case 3: // sub teava: maner
                Gfx.rrect(g, cx - 7, cy - 4, 14, 3, 1.5f, c);
                Gfx.rrect(g, cx - 2, cy - 2, 4, 9, 2, c);
                break;
            case 4: // lateral: laser
                Gfx.rrect(g, cx - 6, cy - 2.5f, 8, 5, 1.5f, c);
                Gfx.line(g, cx + 2, cy, cx + 7, cy, 1.2f, c);
                break;
            case 5: // incarcator
                Gfx.poly(g, new float[] {cx - 3, cy - 7, cx + 3, cy - 7, cx + 5, cy + 7, cx - 1, cy + 7}, c);
                break;
            case 6: // skin: picatura de vopsea
                Gfx.disc(g, cx, cy + 2, 4.5f, c);
                Gfx.poly(g, new float[] {cx, cy - 7, cx + 4, cy + 1, cx - 4, cy + 1}, c);
                break;
            default: // munitie: 3 gloante
                for (int k = -1; k <= 1; k++) {
                    Gfx.rrect(g, cx + k * 4 - 1.5f, cy - 3, 3, 9, 1.2f, c);
                    Gfx.disc(g, cx + k * 4, cy - 3, 1.5f, c);
                }
                break;
        }
    }

    /** Linie punctata (sau plina daca phase = 0) care "curge" in timp. */
    private static void dashed(GuiGraphics g, float x0, float y0, float x1, float y1, float w, int c, double phase) {
        if (phase == 0) {
            Gfx.line(g, x0, y0, x1, y1, w, c);
            return;
        }
        float len = (float) Math.hypot(x1 - x0, y1 - y0);
        if (len < 1) return;
        float dash = 3;
        float off = (float) ((phase * 8) % (dash * 2));
        for (float d = -off; d < len; d += dash * 2) {
            float a = Math.max(0, d) / len;
            float b = Math.min(len, d + dash) / len;
            if (b <= a) continue;
            Gfx.line(g, x0 + (x1 - x0) * a, y0 + (y1 - y0) * a, x0 + (x1 - x0) * b, y0 + (y1 - y0) * b, w, c);
        }
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    // ------------------------------------------------------------------ mouse

    private boolean inPreview(double mx, double my) {
        return Ui.in((int) mx, (int) my, leftPos + PX, topPos + PY, PW, PH);
    }

    private boolean overBox(double mx, double my) {
        for (int i = 0; i < attach.size(); i++) if (Ui.in((int) mx, (int) my, leftPos + BOX[i][0], topPos + BOX[i][1], 24, 24)) return true;
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && inPreview(mx, my) && !overBox(mx, my) && Minecraft.getInstance().player.inventory.getCarried().isEmpty()) {
            spun = true;
            rotY += (float) dx * 1.6f;
            rotX = Math.max(-60f, Math.min(60f, rotX + (float) dy * 1.2f));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inPreview(mx, my)) {
            zoom = Math.max(0.6f, Math.min(1.8f, zoom + (float) delta * 0.1f));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    public static ITextComponent title(String s) {
        return new StringTextComponent(s);
    }
}
