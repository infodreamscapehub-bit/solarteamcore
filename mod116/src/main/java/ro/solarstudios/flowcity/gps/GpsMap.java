package ro.solarstudios.flowcity.gps;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.gen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Harta lumii desenata pe client din chunk-urile incarcate (culoarea blocului de sus + relief), nu dintr-o imagine statica.
 * Pamantul se tine in dale de 64x64 blocuri (cate o textura dinamica), se actualizeaza incremental (cateva chunk-uri pe tick),
 * se salveaza pe disc (folder flowcity/gpsmap/&lt;server&gt;/&lt;dimensiune&gt;) si se elibereaza la iesire. Zonele neexplorate raman goale.
 */
public final class GpsMap {
    private GpsMap() {}

    private static final int TS = 64;
    private static final int MAGIC = 0x47505331;
    private static final int MAX_TILES = 1400;
    private static final int SCAN_RADIUS = 8;

    private static final class Tile {
        final int tx;
        final int tz;
        NativeImage img;
        DynamicTexture tex;
        ResourceLocation rl;
        boolean glDirty;
        boolean diskDirty;
        long used;

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }
    }

    private static final Map<Long, Tile> tiles = new HashMap<>();
    private static final Set<Long> known = new HashSet<>();
    private static String dimKey;
    private static Path dir;
    private static int counter;
    private static long frame;
    private static int cursor;
    private static int saveTick;

    private static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    private static int kx(long k) {
        return (int) (k >> 32);
    }

    private static int kz(long k) {
        return (int) k;
    }

    // ------------------------------------------------------------------ dimensiune / disc

    private static String san(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void ensureDim(Minecraft mc) {
        String server = mc.getCurrentServer() != null && mc.getCurrentServer().ip != null ? mc.getCurrentServer().ip : "local";
        String k = san(server) + "/" + san(GpsData.dimOf(mc));
        if (k.equals(dimKey)) return;
        shutdown();
        dimKey = k;
        dir = FMLPaths.GAMEDIR.get().resolve("flowcity").resolve("gpsmap").resolve(san(server)).resolve(san(GpsData.dimOf(mc)));
        try {
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
                    for (Path p : ds) {
                        String n = p.getFileName().toString();
                        n = n.substring(0, n.length() - 4);
                        int u = n.indexOf('_', 1);
                        if (u < 0) continue;
                        try {
                            known.add(key(Integer.parseInt(n.substring(0, u)), Integer.parseInt(n.substring(u + 1))));
                        } catch (NumberFormatException ignored) {
                            // fisier strain in folder: il sarim
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // folder ilizibil: harta porneste goala
        }
    }

    private static Path fileOf(int tx, int tz) {
        return dir.resolve(tx + "_" + tz + ".bin");
    }

    private static void load(Tile t) {
        Path f = fileOf(t.tx, t.tz);
        if (!Files.isRegularFile(f)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            if (in.readInt() != MAGIC) return;
            for (int z = 0; z < TS; z++) for (int x = 0; x < TS; x++) t.img.setPixelRGBA(x, z, in.readInt());
            t.glDirty = true;
        } catch (IOException | RuntimeException ignored) {
            // dala stricata: ramane goala si se rescrie la urmatoarea explorare
        }
    }

    private static void save(Tile t) {
        if (dir == null || t.img == null) return;
        try {
            Files.createDirectories(dir);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(fileOf(t.tx, t.tz)))))) {
                out.writeInt(MAGIC);
                for (int z = 0; z < TS; z++) for (int x = 0; x < TS; x++) out.writeInt(t.img.getPixelRGBA(x, z));
            }
            t.diskDirty = false;
        } catch (IOException | RuntimeException ignored) {
            // disc plin / blocat: reincercam mai tarziu
        }
    }

    // ------------------------------------------------------------------ dale

    private static Tile tile(int tx, int tz, boolean create) {
        long k = key(tx, tz);
        Tile t = tiles.get(k);
        if (t != null) return t;
        if (!create && !known.contains(k)) return null;
        if (tiles.size() >= MAX_TILES) evict();
        t = new Tile(tx, tz);
        t.img = new NativeImage(TS, TS, true);
        t.tex = new DynamicTexture(t.img);
        t.rl = new ResourceLocation("flowcitycore", "gps/t" + (counter++));
        Minecraft mc = Minecraft.getInstance();
        mc.getTextureManager().register(t.rl, t.tex);
        t.tex.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        if (known.contains(k)) load(t);
        t.used = frame;
        tiles.put(k, t);
        known.add(k);
        return t;
    }

    private static void evict() {
        Tile oldest = null;
        for (Tile t : tiles.values()) if (oldest == null || t.used < oldest.used) oldest = t;
        if (oldest != null) release(oldest, true);
    }

    private static void release(Tile t, boolean saveFirst) {
        if (saveFirst && t.diskDirty) save(t);
        tiles.remove(key(t.tx, t.tz));
        try {
            Minecraft.getInstance().getTextureManager().release(t.rl);
        } catch (RuntimeException ignored) {
            // textura deja eliberata
        }
        t.img = null;
        t.tex = null;
    }

    /** Salveaza ce s-a schimbat si elibereaza toate texturile (la iesire din lume / schimbare de dimensiune). */
    public static void shutdown() {
        for (Tile t : new ArrayList<>(tiles.values())) release(t, true);
        tiles.clear();
        known.clear();
        dimKey = null;
        dir = null;
        cursor = 0;
    }

    /** Salveaza dalele modificate (apelat la inchiderea ecranului). */
    public static void flush() {
        for (Tile t : tiles.values()) if (t.diskDirty) save(t);
    }

    /** Limitele zonei explorate in blocuri {minX, minZ, maxX, maxZ}, sau null daca harta e goala. */
    public static int[] explored() {
        if (known.isEmpty()) return null;
        int a = Integer.MAX_VALUE;
        int b = Integer.MAX_VALUE;
        int c = Integer.MIN_VALUE;
        int d = Integer.MIN_VALUE;
        for (long k : known) {
            a = Math.min(a, kx(k));
            b = Math.min(b, kz(k));
            c = Math.max(c, kx(k));
            d = Math.max(d, kz(k));
        }
        return new int[] {a * TS, b * TS, (c + 1) * TS, (d + 1) * TS};
    }

    // ------------------------------------------------------------------ scanare

    /** La fiecare tick de client: scaneaza cateva chunk-uri din jurul jucatorului (budget = chunk-uri pe tick). */
    public static void tick(Minecraft mc, int budget) {
        if (mc.level == null || mc.player == null) return;
        ensureDim(mc);
        int pcx = MathHelper.floor(mc.player.getX()) >> 4;
        int pcz = MathHelper.floor(mc.player.getZ()) >> 4;
        int side = SCAN_RADIUS * 2 + 1;
        for (int n = 0; n < budget; n++) {
            int i = cursor++ % (side * side);
            scan(mc.level, pcx - SCAN_RADIUS + i % side, pcz - SCAN_RADIUS + i / side);
        }
        if (++saveTick % 60 == 0) {
            int left = 3;
            for (Tile t : tiles.values()) {
                if (t.diskDirty && left-- > 0) save(t);
            }
        }
    }

    private static final BlockPos.Mutable POS = new BlockPos.Mutable();

    private static void scan(ClientWorld w, int cx, int cz) {
        IChunk ch = w.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (ch == null) return;
        Tile t = tile(cx >> 2, cz >> 2, true);
        int ox = (cx & 3) * 16;
        int oz = (cz & 3) * 16;
        int wx0 = cx << 4;
        int wz0 = cz << 4;
        int[] hs = new int[256];
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) hs[(z << 4) | x] = w.getHeight(Heightmap.Type.MOTION_BLOCKING, wx0 + x, wz0 + z);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = hs[(z << 4) | x];
                int wx = wx0 + x;
                int wz = wz0 + z;
                int y = h - 1;
                BlockState st = null;
                for (int k = 0; k < 6 && y > 0; k++) {
                    POS.set(wx, y, wz);
                    st = w.getBlockState(POS);
                    if (!st.isAir(w, POS)) break;
                    y--;
                }
                if (st == null || y <= 0) continue;
                int rgb;
                if (!st.getFluidState().isEmpty()) {
                    int depth = 1;
                    for (int k = 1; k < 14 && y - k > 0; k++) {
                        POS.set(wx, y - k, wz);
                        if (w.getBlockState(POS).getFluidState().isEmpty()) break;
                        depth++;
                    }
                    rgb = mix(0x4FA3D8, 0x143F78, Math.min(1f, depth / 12f));
                } else {
                    int col = 0;
                    int yy = y;
                    BlockState cur = st;
                    for (int k = 0; k < 6 && yy > 0; k++) {
                        POS.set(wx, yy, wz);
                        MaterialColor mc = cur.getMapColor(w, POS);
                        col = mc == null ? 0 : mc.col;
                        if (col != 0) break;
                        yy--;
                        POS.set(wx, yy, wz);
                        cur = w.getBlockState(POS);
                    }
                    if (col == 0) col = 0x404040;
                    int hn = z > 0 ? hs[((z - 1) << 4) | x] : h;
                    int hw = x > 0 ? hs[(z << 4) | (x - 1)] : h;
                    float slope = Math.max(-3f, Math.min(3f, (h - hn) + (h - hw)));
                    float f = 1f + slope * 0.07f;
                    int r = clamp(((col >> 16) & 255) * f);
                    int g = clamp(((col >> 8) & 255) * f);
                    int b = clamp((col & 255) * f);
                    rgb = (r << 16) | (g << 8) | b;
                }
                int abgr = 0xFF000000 | ((rgb & 255) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 255);
                t.img.setPixelRGBA(ox + x, oz + z, abgr);
            }
        }
        t.glDirty = true;
        t.diskDirty = true;
    }

    private static int clamp(float v) {
        return v < 0f ? 0 : (v > 255f ? 255 : (int) v);
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ desen

    /**
     * Deseneaza dalele vizibile. Ecran: sx = cx + (wx - camX) * zoom, sy = cy + (wz - camZ) * zoom; nord (-Z) in sus.
     */
    public static void draw(GuiGraphics g, double camX, double camZ, float zoom, float cx, float cy, float sw, float sh, float alpha) {
        frame++;
        if (dimKey == null) return;
        double minX = camX - cx / zoom - 1;
        double maxX = camX + (sw - cx) / zoom + 1;
        double minZ = camZ - cy / zoom - 1;
        double maxZ = camZ + (sh - cy) / zoom + 1;
        int t0x = MathHelper.floor(minX / TS);
        int t1x = MathHelper.floor(maxX / TS);
        int t0z = MathHelper.floor(minZ / TS);
        int t1z = MathHelper.floor(maxZ / TS);
        Matrix4f m = g.pose().last().pose();
        Minecraft mc = Minecraft.getInstance();
        int uploads = 4;
        int loads = 6;
        int drawn = 0;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableTexture();
        RenderSystem.color4f(1f, 1f, 1f, alpha);
        for (long k : known) {
            int tx = kx(k);
            int tz = kz(k);
            if (tx < t0x || tx > t1x || tz < t0z || tz > t1z) continue;
            Tile t = tiles.get(k);
            if (t == null) {
                if (loads-- <= 0) continue;
                t = tile(tx, tz, false);
                if (t == null) continue;
            }
            t.used = frame;
            if (t.glDirty && uploads > 0) {
                t.tex.upload();
                t.glDirty = false;
                uploads--;
            }
            if (++drawn > 900) break;
            float x0 = (float) (cx + (tx * TS - camX) * zoom);
            float y0 = (float) (cy + (tz * TS - camZ) * zoom);
            float x1 = x0 + TS * zoom;
            float y1 = y0 + TS * zoom;
            mc.getTextureManager().bind(t.rl);
            BufferBuilder b = Tessellator.getInstance().getBuilder();
            b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            b.vertex(m, x0, y1, 0f).uv(0f, 1f).endVertex();
            b.vertex(m, x1, y1, 0f).uv(1f, 1f).endVertex();
            b.vertex(m, x1, y0, 0f).uv(1f, 0f).endVertex();
            b.vertex(m, x0, y0, 0f).uv(0f, 0f).endVertex();
            Tessellator.getInstance().end();
        }
        RenderSystem.color4f(1f, 1f, 1f, 1f);
    }

    /** Numarul de dale in memorie (pentru depanare). */
    public static int loadedTiles() {
        return tiles.size();
    }

    /** Dalele cunoscute (pentru depanare). */
    public static List<Long> knownKeys() {
        return new ArrayList<>(known);
    }
}
