package ro.solarstudios.flowcity.maps;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.util.ResourceLocation;

/**
 * Textura dinamica a hartii in stil GTA (apa, parcuri, strazi albe, cladiri), construita din {@link MapCache}.
 * Stilul "atlas" e deschis (harta mare); stilul "radar" e inchis (minimap).
 */
public final class MapView {
    public final int size;
    private final DynamicTexture tex;
    private final ResourceLocation loc;
    public int cx;
    public int cz;
    public int scale = 1;
    public long updated;
    private boolean ready;
    private final boolean radar;

    // clase
    private static final int UNKNOWN = 0, WATER = 1, LAND = 2, LAND_ALT = 3, PARK = 4, ROAD = 5, ROAD_EDGE = 6, BUILDING = 7, BUILD_EDGE = 8;

    private static final int[] ATLAS = {0xFFB9C7D6, 0xFF7BA6D6, 0xFFEAE6D8, 0xFFE0DCCB, 0xFFB5D6A0, 0xFFFFFFFF, 0xFFB8B4A6, 0xFFD2CEC0, 0xFFA9A596};
    private static final int[] RADAR = {0xFF10151C, 0xFF2B4C6F, 0xFF2A2F38, 0xFF303642, 0xFF2B4A36, 0xFF7E8798, 0xFF4D5565, 0xFF3B4150, 0xFF1F232B};

    public MapView(int size, String name, boolean radar) {
        this.size = size;
        this.radar = radar;
        NativeImage img = new NativeImage(NativeImage.PixelFormat.RGBA, size, size, false);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setPixelRGBA(x, y, abgr((radar ? RADAR : ATLAS)[UNKNOWN]));
        this.tex = new DynamicTexture(img);
        this.loc = Minecraft.getInstance().getTextureManager().getDynamicTextureLocation(name, tex);
    }

    private static int abgr(int argb) {
        return (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
    }

    public void close() {
        try {
            Minecraft.getInstance().getTextureManager().deleteTexture(loc);
        } catch (RuntimeException ignored) {
            // deja eliberata
        }
    }

    /** Trebuie refacuta imaginea pentru o vedere centrata la (vx,vz) cu o fereastra de wTex x hTex texeli? */
    public boolean needsRefresh(double vx, double vz, int nscale, int viewTexW, int viewTexH, long maxAgeMs) {
        if (!ready || nscale != scale) return true;
        double offX = Math.abs(vx - cx) / scale + viewTexW / 2.0;
        double offZ = Math.abs(vz - cz) / scale + viewTexH / 2.0;
        if (offX > size / 2.0 - 3 || offZ > size / 2.0 - 3) return true;
        return System.currentTimeMillis() - updated > maxAgeMs;
    }

    public void refresh(int ncx, int ncz, int nscale) {
        cx = ncx;
        cz = ncz;
        scale = nscale;
        updated = System.currentTimeMillis();
        NativeImage img = tex.getTextureData();
        if (img == null) return;
        int n = size;
        int half = n * scale / 2;
        int[] hh = new int[n * n];
        int[] cc = new int[n * n];
        for (int j = 0; j < n; j++) {
            int wz = cz - half + j * scale;
            for (int i = 0; i < n; i++) {
                int wx = cx - half + i * scale;
                hh[j * n + i] = MapCache.height(wx, wz);
                cc[j * n + i] = MapCache.color(wx, wz);
            }
        }
        int[] cls = new int[n * n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                int h = hh[k];
                if (h < 0) {
                    cls[k] = UNKNOWN;
                    continue;
                }
                int c = cc[k];
                if ((c & MapCache.WATER) != 0) {
                    cls[k] = WATER;
                    continue;
                }
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                int mx = Math.max(r, Math.max(g, b));
                int mn = Math.min(r, Math.min(g, b));
                int d = 2;
                int low = h;
                low = Math.min(low, known(hh, n, i - d, j, h));
                low = Math.min(low, known(hh, n, i + d, j, h));
                low = Math.min(low, known(hh, n, i, j - d, h));
                low = Math.min(low, known(hh, n, i, j + d, h));
                if (h - low >= 4) cls[k] = BUILDING;
                else if (g > r + 6 && g > b + 6) cls[k] = PARK;
                else if (mx - mn < 30 && mx < 110) cls[k] = ROAD;
                else if (mx - mn < 30) cls[k] = LAND_ALT;
                else cls[k] = LAND;
            }
        }
        int[] pal = radar ? RADAR : ATLAS;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int k = j * n + i;
                int cl = cls[k];
                if (cl == ROAD && edge(cls, n, i, j, ROAD)) cl = ROAD_EDGE;
                else if (cl == BUILDING && edge(cls, n, i, j, BUILDING)) cl = BUILD_EDGE;
                img.setPixelRGBA(i, j, abgr(pal[cl]));
            }
        }
        tex.updateDynamicTexture();
        ready = true;
    }

    private static int known(int[] hh, int n, int i, int j, int def) {
        if (i < 0 || j < 0 || i >= n || j >= n) return def;
        int v = hh[j * n + i];
        return v < 0 ? def : v;
    }

    private static boolean edge(int[] cls, int n, int i, int j, int self) {
        return diff(cls, n, i - 1, j, self) || diff(cls, n, i + 1, j, self) || diff(cls, n, i, j - 1, self) || diff(cls, n, i, j + 1, self);
    }

    private static boolean diff(int[] cls, int n, int i, int j, int self) {
        if (i < 0 || j < 0 || i >= n || j >= n) return false;
        return cls[j * n + i] != self;
    }

    // ------------------------------------------------------------------ desen

    private void bind() {
        RenderSystem.enableBlend();
        RenderSystem.color4f(1f, 1f, 1f, 1f);
        Minecraft.getInstance().getTextureManager().bindTexture(loc);
        RenderSystem.texParameter(3553, 10241, 9728);
        RenderSystem.texParameter(3553, 10240, 9728);
    }

    private int u0(double vx, int w, int f) {
        return (int) Math.round(size / 2.0 + (vx - cx) / scale - w / (2.0 * f));
    }

    private int v0(double vz, int h, int f) {
        return (int) Math.round(size / 2.0 + (vz - cz) / scale - h / (2.0 * f));
    }

    /** Deseneaza fereastra (w x h pixeli GUI, w si h multiplu de f) centrata la (vx,vz); f = pixeli GUI per texel. */
    public void draw(MatrixStack ms, int x, int y, int w, int h, double vx, double vz, int f) {
        bind();
        int rw = w / f;
        int rh = h / f;
        int u = Math.max(0, Math.min(size - rw, u0(vx, w, f)));
        int v = Math.max(0, Math.min(size - rh, v0(vz, h, f)));
        AbstractGui.blit(ms, x, y, rw * f, rh * f, (float) u, (float) v, rw, rh, size, size);
    }

    public float gx(double wx, int x, int w, double vx, int f) {
        return x + (float) (((wx - cx) / scale + size / 2.0 - u0(vx, w, f)) * f);
    }

    public float gy(double wz, int y, int h, double vz, int f) {
        return y + (float) (((wz - cz) / scale + size / 2.0 - v0(vz, h, f)) * f);
    }

    /** Radar rotit: imaginea intreaga centrata la (sx,sy), deplasata cu pozitia jucatorului si rotita cu rotDeg. */
    public void drawRotated(MatrixStack ms, float sx, float sy, double px, double pz, float rotDeg) {
        bind();
        ms.push();
        ms.translate(sx, sy, 0);
        ms.rotate(net.minecraft.util.math.vector.Vector3f.ZP.rotationDegrees(rotDeg));
        ms.translate(-(px - cx) / scale, -(pz - cz) / scale, 0);
        AbstractGui.blit(ms, -size / 2, -size / 2, size, size, 0f, 0f, size, size, size, size);
        ms.pop();
    }
}
