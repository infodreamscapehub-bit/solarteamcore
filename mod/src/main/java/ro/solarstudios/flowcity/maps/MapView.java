package ro.solarstudios.flowcity.maps;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.Heightmap;

/** Textura dinamica cu terenul din jurul unui punct, vazut de sus (culori ca pe harta vanilla, cu relief). */
public final class MapView {
    public final int size;
    private final DynamicTexture tex;
    private final ResourceLocation loc;
    public int cx;
    public int cz;
    public int scale = 1;
    public long updated;
    private boolean ready;

    public MapView(int size, String name) {
        this.size = size;
        NativeImage img = new NativeImage(NativeImage.PixelFormat.RGBA, size, size, false);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setPixelRGBA(x, y, 0xFF201008);
        this.tex = new DynamicTexture(img);
        this.loc = Minecraft.getInstance().getTextureManager().getDynamicTextureLocation(name, tex);
    }

    public void close() {
        try {
            Minecraft.getInstance().getTextureManager().deleteTexture(loc);
        } catch (RuntimeException ignored) {
            // deja eliberata
        }
    }

    public boolean needsRefresh(int ncx, int ncz, int nscale, long maxAgeMs) {
        return !ready || nscale != scale || Math.abs(ncx - cx) >= Math.max(3, scale * 3) || Math.abs(ncz - cz) >= Math.max(3, scale * 3)
            || System.currentTimeMillis() - updated > maxAgeMs;
    }

    /** Reface imaginea centrata pe (ncx, ncz), cu nscale blocuri per pixel. */
    public void refresh(ClientWorld w, int ncx, int ncz, int nscale) {
        if (w == null) return;
        cx = ncx;
        cz = ncz;
        scale = nscale;
        updated = System.currentTimeMillis();
        NativeImage img = tex.getTextureData();
        if (img == null) return;
        int half = size * scale / 2;
        Map<Long, Boolean> loaded = new HashMap<>();
        int[] prevH = new int[size];
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int j = 0; j < size; j++) {
            int wz = cz - half + j * scale;
            for (int i = 0; i < size; i++) {
                int wx = cx - half + i * scale;
                long ck = ((long) (wx >> 4) << 32) ^ ((wz >> 4) & 0xFFFFFFFFL);
                Boolean ok = loaded.get(ck);
                if (ok == null) {
                    ok = w.getChunkProvider().getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false) != null;
                    loaded.put(ck, ok);
                }
                int color;
                if (!ok) {
                    color = 0xFF2A1A0E; // necunoscut (ABGR: albastru inchis)
                    prevH[i] = 0;
                } else {
                    int h = w.getHeight(Heightmap.Type.MOTION_BLOCKING, wx, wz);
                    if (h <= 0) {
                        color = 0xFF2A1A0E;
                    } else {
                        pos.setPos(wx, h - 1, wz);
                        int rgb = w.getBlockState(pos).getMaterialColor(w, pos).colorValue;
                        if (rgb == 0) rgb = 0x404040;
                        float shade = 1f;
                        if (j > 0 && prevH[i] > 0) shade += Math.max(-3, Math.min(3, h - prevH[i])) * 0.07f;
                        int r = Math.min(255, (int) (((rgb >> 16) & 255) * shade));
                        int g = Math.min(255, (int) (((rgb >> 8) & 255) * shade));
                        int b = Math.min(255, (int) ((rgb & 255) * shade));
                        color = 0xFF000000 | (b << 16) | (g << 8) | r;
                    }
                    prevH[i] = h;
                }
                img.setPixelRGBA(i, j, color);
            }
        }
        tex.updateDynamicTexture();
        ready = true;
    }

    public void draw(MatrixStack ms, int x, int y, int w, int h) {
        RenderSystem.enableBlend();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        Minecraft.getInstance().getTextureManager().bindTexture(loc);
        AbstractGui.blit(ms, x, y, w, h, 0f, 0f, size, size, size, size);
    }

    /** Lume -> pixel ecran pentru o vedere desenata la (x,y) cu latimea w. */
    public float sx(double wx, int x, int w) {
        return x + (float) ((wx - (cx - size * scale / 2.0)) / (size * (double) scale)) * w;
    }

    public float sy(double wz, int y, int h) {
        return y + (float) ((wz - (cz - size * scale / 2.0)) / (size * (double) scale)) * h;
    }
}
