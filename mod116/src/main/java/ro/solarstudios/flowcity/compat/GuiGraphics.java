package ro.solarstudios.flowcity.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;

/**
 * Strat de compatibilitate pentru 1.16.5: aceleasi metode ca GuiGraphics din 1.20.1, desenate cu MatrixStack +
 * AbstractGui. Asa codul interfetelor ramane identic pe ambele versiuni.
 */
public final class GuiGraphics {
    private final MatrixStack pose;
    private final IRenderTypeBuffer.Impl buffers;

    public GuiGraphics(MatrixStack pose) {
        this.pose = pose;
        this.buffers = Minecraft.getInstance().renderBuffers().bufferSource();
    }

    public MatrixStack pose() {
        return pose;
    }

    public IRenderTypeBuffer.Impl bufferSource() {
        return buffers;
    }

    public void flush() {
        buffers.endBatch();
    }

    public void fill(int x0, int y0, int x1, int y1, int color) {
        AbstractGui.fill(pose, x0, y0, x1, y1, color);
    }

    /** Gradient vertical (de sus in jos), desenat din benzi orizontale. */
    public void fillGradient(int x0, int y0, int x1, int y1, int top, int bottom) {
        int h = Math.max(1, y1 - y0);
        for (int i = 0; i < h; i++) {
            float t = h == 1 ? 0 : i / (float) (h - 1);
            fill(x0, y0 + i, x1, y0 + i + 1, mix(top, bottom, t));
        }
    }

    private static int mix(int a, int b, float t) {
        int aa = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (aa << 24) | (r << 16) | (g << 8) | bl;
    }

    public int drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public int drawString(FontRenderer font, ITextComponent text, int x, int y, int color, boolean shadow) {
        return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
    }

    public int drawString(FontRenderer font, String text, int x, int y, int color) {
        return drawString(font, text, x, y, color, true);
    }

    /** blit(textura, x, y, latime, inaltime, u, v, latimeUV, inaltimeUV, latimeTextura, inaltimeTextura) */
    public void blit(ResourceLocation tex, int x, int y, int w, int h, float u, float v, int uw, int vh, int tw, int th) {
        Minecraft.getInstance().getTextureManager().bind(tex);
        AbstractGui.blit(pose, x, y, w, h, u, v, uw, vh, tw, th);
    }

    /** blit(textura, x, y, u, v, latime, inaltime, latimeTextura, inaltimeTextura) */
    public void blit(ResourceLocation tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
        Minecraft.getInstance().getTextureManager().bind(tex);
        AbstractGui.blit(pose, x, y, u, v, w, h, tw, th);
    }

    public void renderItem(ItemStack stack, int x, int y) {
        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(pose.last().pose());
        Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(stack, x, y);
        RenderSystem.popMatrix();
    }
}
