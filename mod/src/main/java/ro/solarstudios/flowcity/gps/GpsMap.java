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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Harta lumii desenata pe client din chunk-urile incarcate (culoarea blocului de sus + relief), nu dintr-o imagine statica.
 * Pamantul se tine in dale de 64x64 blocuri (cate o textura dinamica), se actualizeaza incremental (cateva chunk-uri pe tick),
 * se salveaza pe disc (folder flowcity/gpsmap/&lt;server&gt;/&lt;dimensiune&gt;) si se elibereaza la iesire. Zonele neexplorate raman goale.
 *
 * <p>HARTA PARTAJATA: aceleasi dale (aceeasi grila, aceeasi transformare) se sincronizeaza cu serverul (vezi {@link GpsShared}):
 * pixelii schimbati de scanare se marcheaza (bitset per dala) si se trimit; dalele primite de la server se unesc in cele locale
 * (pixelii goali se completeaza; cei existenti se suprascriu doar daca dala a fost actualizata de altcineva si nu am scanat-o recent).
 * La zoom mic se deseneaza grupuri de 4x4 dale (o textura de 64x64 per grup, construita pe un thread separat).
 */
public final class GpsMap {
    private GpsMap() {}

    private static final int TS = 64;
    private static final int MAGIC = 0x47505331;
    private static final int META_MAGIC = 0x4750534D;
    private static final int MAX_TILES = 1400;
    private static final int SCAN_RADIUS = 8;
    /** Sub acest zoom se deseneaza grupurile (prezentare generala) in loc de dale. */
    public static final float OV_ZOOM = 0.45f;
    private static final int OV = 4;
    private static final int MAX_OV = 700;
    private static final long FADE_MS = 380L;
    private static final String OVERWORLD = "minecraft:overworld";

    private static final class Tile {
        final int tx;
        final int tz;
        NativeImage img;
        DynamicTexture tex;
        ResourceLocation rl;
        boolean glDirty;
        boolean diskDirty;
        long used;
        /** Versiunea de pe server cu care e sincronizata dala (0 = nesincronizata). */
        int sver;
        /** Pixelii schimbati de scanare si netrimisi serverului (bitset 4096 biti). */
        long[] dirty;
        boolean upDirty;
        long lastScan;
        long fadeStart;

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }

        void markDirty(int idx) {
            if (dirty == null) dirty = new long[64];
            dirty[idx >> 6] |= 1L << (idx & 63);
            upDirty = true;
        }
    }

    /** Grup de 4x4 dale, o singura textura 64x64 (1 pixel = 4 blocuri). */
    private static final class Ov {
        final int gx;
        final int gz;
        NativeImage img;
        DynamicTexture tex;
        ResourceLocation rl;
        volatile int[] ready;
        volatile boolean building;
        boolean hasData;
        long lastBuild;
        long used;
        long fadeStart;

        Ov(int gx, int gz) {
            this.gx = gx;
            this.gz = gz;
        }
    }

    /** O dala de trimis serverului: pixeli ABGR, alpha 0 = nu se trimite. */
    public static final class UpTile {
        public int tx;
        public int tz;
        public final int[] px = new int[TS * TS];
    }

    private static final Map<Long, Tile> tiles = new HashMap<>();
    private static final Set<Long> known = new HashSet<>();
    private static final Map<Long, Ov> ovs = new HashMap<>();
    private static final Map<Long, Integer> groupCount = new HashMap<>();
    private static final Set<Long> ovDirty = new HashSet<>();
    /** Versiunile dalelor de pe server (doar Overworld); raman la schimbarea dimensiunii, se sterg la logout. */
    private static final Map<Long, Integer> serverVer = new HashMap<>();
    /** Versiunea serverului cu care e sincronizata fiecare dala de pe disc (persistata in meta.bin). */
    private static final Map<Long, Integer> sverMap = new HashMap<>();
    /** Dale care trebuie trimise integral (explorate offline / evacuate cu modificari netrimise). */
    private static final Set<Long> needFull = new HashSet<>();
    private static boolean metaDirty;
    private static boolean indexDone;
    private static boolean overworld;
    private static String dimKey;
    private static Path dir;
    private static int counter;
    private static long frame;
    private static int cursor;
    private static int saveTick;
    private static int metaTick;
    private static int lastNeed;
    // ultima zona vizibila (in dale), pentru cererea de dale de la server
    private static int vt0x;
    private static int vt1x;
    private static int vt0z;
    private static int vt1z;
    private static long viewFrame = -1000;
    private static final int[] S1 = new int[TS * TS];
    private static final int[] S2 = new int[TS * TS];
    private static final int[] HS = new int[256];
    private static final ExecutorService ovPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowCity-GpsMap-Overview");
        t.setDaemon(true);
        return t;
    });

    private static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    private static int kx(long k) {
        return (int) (k >> 32);
    }

    private static int kz(long k) {
        return (int) k;
    }


    private static long gkey(int tx, int tz) {
        return key(Math.floorDiv(tx, OV), Math.floorDiv(tz, OV));
    }

    private static void markKnown(long k) {
        if (known.add(k)) groupCount.merge(gkey(kx(k), kz(k)), 1, Integer::sum);
    }

    private static void unknown(long k) {
        if (!known.remove(k)) return;
        long g = gkey(kx(k), kz(k));
        Integer c = groupCount.get(g);
        if (c == null || c <= 1) groupCount.remove(g);
        else groupCount.put(g, c - 1);
        ovDirty.add(g);
    }

    private static void touchGroup(int tx, int tz) {
        ovDirty.add(gkey(tx, tz));
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
        overworld = OVERWORLD.equals(GpsData.dimOf(mc));
        dir = FMLPaths.GAMEDIR.get().resolve("flowcity").resolve("gpsmap").resolve(san(server)).resolve(san(GpsData.dimOf(mc)));
        try {
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bin")) {
                    for (Path p : ds) {
                        String n = p.getFileName().toString();
                        if (n.equals("meta.bin")) continue;
                        n = n.substring(0, n.length() - 4);
                        int u = n.indexOf('_', 1);
                        if (u < 0) continue;
                        try {
                            markKnown(key(Integer.parseInt(n.substring(0, u)), Integer.parseInt(n.substring(u + 1))));
                        } catch (NumberFormatException ignored) {
                            // fisier strain in folder: il sarim
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // folder ilizibil: harta porneste goala
        }
        loadMeta();
        reconcile();
    }

    private static Path fileOf(int tx, int tz) {
        return dir.resolve(tx + "_" + tz + ".bin");
    }

    /** Citeste o dala de pe disc ca ABGR in out (4096 intregi). Sigur de apelat si de pe alt thread. */
    private static boolean readFile(Path f, int[] out) {
        if (!Files.isRegularFile(f)) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            if (in.readInt() != MAGIC) return false;
            for (int i = 0; i < TS * TS; i++) out[i] = in.readInt();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean writeFile(Path f, int[] px) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(MAGIC);
                for (int i = 0; i < TS * TS; i++) out.writeInt(px[i]);
            }
            try {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void load(Tile t) {
        if (!readFile(fileOf(t.tx, t.tz), S1)) return;
        for (int z = 0; z < TS; z++) for (int x = 0; x < TS; x++) t.img.setPixelRGBA(x, z, S1[z * TS + x]);
        t.glDirty = true;
    }

    private static void save(Tile t) {
        if (dir == null || t.img == null) return;
        for (int z = 0; z < TS; z++) for (int x = 0; x < TS; x++) S1[z * TS + x] = t.img.getPixelRGBA(x, z);
        if (writeFile(fileOf(t.tx, t.tz), S1)) {
            t.diskDirty = false;
            sverMap.put(key(t.tx, t.tz), t.sver);
            metaDirty = true;
        }
    }

    // ------------------------------------------------------------------ meta (versiuni server + dale de trimis)

    private static void loadMeta() {
        sverMap.clear();
        needFull.clear();
        Path f = dir.resolve("meta.bin");
        boolean ok = false;
        if (Files.isRegularFile(f)) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                if (in.readInt() == META_MAGIC) {
                    int n = in.readInt();
                    for (int i = 0; i < n && i < 2000000; i++) {
                        long k = in.readLong();
                        int v = in.readInt();
                        int fl = in.readUnsignedByte();
                        if (v != 0) sverMap.put(k, v);
                        if ((fl & 1) != 0) needFull.add(k);
                    }
                    ok = true;
                }
            } catch (IOException | RuntimeException ignored) {
                ok = false;
            }
        }
        // prima rulare dupa actualizare (sau meta stricat): tot ce ai explorat pana acum se trimite serverului cand treci pe acolo
        if (!ok) {
            needFull.addAll(known);
            metaDirty = true;
        }
    }

    private static void saveMeta() {
        if (dir == null || !metaDirty) return;
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve("meta.tmp");
            Set<Long> all = new HashSet<>(sverMap.keySet());
            all.addAll(needFull);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(META_MAGIC);
                out.writeInt(all.size());
                for (long k : all) {
                    Integer v = sverMap.get(k);
                    out.writeLong(k);
                    out.writeInt(v == null ? 0 : v);
                    out.writeByte(needFull.contains(k) ? 1 : 0);
                }
            }
            Path f = dir.resolve("meta.bin");
            try {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            metaDirty = false;
        } catch (IOException | RuntimeException ignored) {
            // reincercam la urmatorul flush
        }
    }

    // ------------------------------------------------------------------ dale

    private static ResourceLocation register(DynamicTexture tex, String prefix, boolean linear) {
        ResourceLocation rl = new ResourceLocation("flowcitycore", "gps/" + prefix + (counter++));
        Minecraft.getInstance().getTextureManager().register(rl, tex);
        tex.bind();
        int f = linear ? GL11.GL_LINEAR : GL11.GL_NEAREST;
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, f);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, f);
        return rl;
    }

    private static Tile tile(int tx, int tz, boolean create) {
        long k = key(tx, tz);
        Tile t = tiles.get(k);
        if (t != null) return t;
        if (!create && !known.contains(k)) return null;
        if (tiles.size() >= MAX_TILES) evict();
        t = new Tile(tx, tz);
        t.img = new NativeImage(TS, TS, true);
        t.tex = new DynamicTexture(t.img);
        t.rl = register(t.tex, "t", false);
        Integer sv = sverMap.get(k);
        t.sver = sv == null ? 0 : sv;
        if (known.contains(k)) load(t);
        t.used = frame;
        t.fadeStart = System.currentTimeMillis();
        tiles.put(k, t);
        markKnown(k);
        return t;
    }

    private static void evict() {
        Tile oldest = null;
        for (Tile t : tiles.values()) if (oldest == null || t.used < oldest.used) oldest = t;
        if (oldest != null) release(oldest, true);
    }

    private static void release(Tile t, boolean saveFirst) {
        long k = key(t.tx, t.tz);
        if (saveFirst && t.diskDirty) save(t);
        if (saveFirst && t.upDirty) {
            // modificari netrimise serverului: la urmatoarea trecere pe acolo se trimite dala integral
            needFull.add(k);
            metaDirty = true;
        }
        sverMap.put(k, t.sver);
        tiles.remove(k);
        try {
            Minecraft.getInstance().getTextureManager().release(t.rl);
        } catch (RuntimeException ignored) {
            // textura deja eliberata
        }
        t.img = null;
        t.tex = null;
    }

    private static void releaseOv(Ov o) {
        ovs.remove(key(o.gx, o.gz));
        try {
            Minecraft.getInstance().getTextureManager().release(o.rl);
        } catch (RuntimeException ignored) {
            // textura deja eliberata
        }
        o.img = null;
        o.tex = null;
    }

    /** Salveaza ce s-a schimbat si elibereaza toate texturile (la iesire din lume / schimbare de dimensiune). */
    public static void shutdown() {
        for (Tile t : new ArrayList<>(tiles.values())) release(t, true);
        saveMeta();
        tiles.clear();
        for (Ov o : new ArrayList<>(ovs.values())) releaseOv(o);
        ovs.clear();
        groupCount.clear();
        ovDirty.clear();
        known.clear();
        sverMap.clear();
        needFull.clear();
        metaDirty = false;
        dimKey = null;
        dir = null;
        overworld = false;
        cursor = 0;
    }

    /** Salveaza dalele modificate (apelat la inchiderea ecranului). */
    public static void flush() {
        for (Tile t : tiles.values()) if (t.diskDirty) save(t);
        saveMeta();
    }

    /** Limitele zonei explorate in blocuri {minX, minZ, maxX, maxZ}, sau null daca harta e goala (include dalele existente pe server). */
    public static int[] explored() {
        if (known.isEmpty() && serverVer.isEmpty()) return null;
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
        if (overworld) {
            for (long k : serverVer.keySet()) {
                a = Math.min(a, kx(k));
                b = Math.min(b, kz(k));
                c = Math.max(c, kx(k));
                d = Math.max(d, kz(k));
            }
        }
        if (a == Integer.MAX_VALUE) return null;
        return new int[] {a * TS, b * TS, (c + 1) * TS, (d + 1) * TS};
    }

    // ------------------------------------------------------------------ scanare

    /** La fiecare tick de client: scaneaza cateva chunk-uri din jurul jucatorului (budget = chunk-uri pe tick). */
    public static void tick(Minecraft mc, int budget) {
        if (mc.level == null || mc.player == null) return;
        ensureDim(mc);
        int pcx = Mth.floor(mc.player.getX()) >> 4;
        int pcz = Mth.floor(mc.player.getZ()) >> 4;
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
        if (++metaTick % 400 == 0) saveMeta();
    }

    private static final BlockPos.Mutable POS = new BlockPos.Mutable();

    private static void scan(ClientLevel w, int cx, int cz) {
        ChunkAccess ch = w.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (ch == null) return;
        Tile t = tile(cx >> 2, cz >> 2, true);
        int ox = (cx & 3) * 16;
        int oz = (cz & 3) * 16;
        int wx0 = cx << 4;
        int wz0 = cz << 4;
        int[] hs = HS;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) hs[(z << 4) | x] = w.getHeight(Heightmap.Types.MOTION_BLOCKING, wx0 + x, wz0 + z);
        boolean changed = false;
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
                // blocurile subtiri (garduri, ziduri joase, gratii, panouri) si geamul nu se deseneaza ca pixel plin: se coboara spre sol
                boolean glass = false;
                int yy = y;
                BlockState cur = st;
                int kind = K_NORMAL;
                for (int k = 0; k < 10 && yy > 0; k++) {
                    POS.set(wx, yy, wz);
                    cur = w.getBlockState(POS);
                    if (cur.isAir(w, POS)) {
                        yy--;
                        continue;
                    }
                    if (!cur.getFluidState().isEmpty()) {
                        kind = K_NORMAL;
                        break;
                    }
                    kind = kindOf(cur.getBlock());
                    if (kind == K_GLASS) glass = true;
                    if (kind != K_THIN && kind != K_GLASS) break;
                    yy--;
                }
                if (yy <= 0) continue;
                POS.set(wx, yy, wz);
                cur = w.getBlockState(POS);
                boolean water = !cur.getFluidState().isEmpty();
                // vecinii pe inaltime (cu 4 directii; la marginea chunk-ului se cere vecinul doar daca e incarcat)
                int hn = nbH(w, hs, wx0, wz0, x, z - 1, h);
                int hsu = nbH(w, hs, wx0, wz0, x, z + 1, h);
                int hw = nbH(w, hs, wx0, wz0, x - 1, z, h);
                int he = nbH(w, hs, wx0, wz0, x + 1, z, h);
                if (water) {
                    int depth = 1;
                    for (int k = 1; k < 14 && yy - k > 0; k++) {
                        POS.set(wx, yy - k, wz);
                        if (w.getBlockState(POS).getFluidState().isEmpty()) break;
                        depth++;
                    }
                    float dt = Math.min(1f, depth / 10f);
                    rgb = mix(0x62C4EE, 0x1A4C9A, dt);
                    // luciu discret: carouri usoare, ca apa sa nu fie o suprafata moarta
                    if (((wx + wz) & 1) == 0) rgb = mix(rgb, 0xFFFFFF, 0.035f);
                } else {
                    int col = 0;
                    BlockState c2 = cur;
                    int y2 = yy;
                    for (int k = 0; k < 6 && y2 > 0; k++) {
                        POS.set(wx, y2, wz);
                        MapColor mc = c2.getMapColor(w, POS);
                        col = mc == null ? 0 : mc.col;
                        if (col != 0) break;
                        y2--;
                        POS.set(wx, y2, wz);
                        c2 = w.getBlockState(POS);
                    }
                    if (col == 0) col = 0x404040;
                    int hash = (wx * 73856093) ^ (wz * 19349663);
                    boolean roadLike = false;
                    if (kind == K_GRASS) {
                        int v = (hash >>> 8) & 3;
                        col = mix(0x7AB83F, 0x68A236, v / 3f);
                    } else if (kind == K_LEAVES) {
                        int v = ((wx ^ wz) & 1) + ((hash >>> 9) & 1);
                        col = v == 0 ? 0x2F7A26 : (v == 1 ? 0x3A8A2D : 0x479A35);
                    } else if (kind == K_SAND) {
                        col = mix(0xE6D59C, 0xDCC98E, ((hash >>> 8) & 3) / 3f);
                    } else {
                        // gri/negru fara nuanta (beton, blackstone, asfalt) = drum: culoare unica, curata, fara relief puternic
                        int r0 = (col >> 16) & 255;
                        int g0 = (col >> 8) & 255;
                        int b0 = col & 255;
                        int mxc = Math.max(r0, Math.max(g0, b0));
                        int mnc = Math.min(r0, Math.min(g0, b0));
                        if (mxc - mnc < 22 && mxc < 0x62) {
                            col = 0x3A3E46;
                            roadLike = true;
                        }
                    }
                    // relief: lumina din NV; gradient pe ambele axe
                    float slope = Math.max(-4f, Math.min(4f, ((hsu - hn) + (he - hw)) * 0.5f + ((h - hn) + (h - hw)) * 0.5f));
                    float hi = Math.max(-0.07f, Math.min(0.10f, (h - 64) / 220f));
                    float f = (1f + slope * (roadLike ? 0.02f : 0.065f)) * (1f + hi);
                    int r = clamp(((col >> 16) & 255) * f);
                    int g = clamp(((col >> 8) & 255) * f);
                    int b = clamp((col & 255) * f);
                    rgb = (r << 16) | (g << 8) | b;
                    if (glass) rgb = mix(rgb, 0xBFE8F4, 0.38f);
                    // contur de constructii: marginea de sus a unui perete (cadere >= 3 blocuri) se deschide, baza lui (urcare >= 3) se umbreste
                    if (kind != K_LEAVES) {
                        int drop = Math.max(Math.max(h - hn, h - hsu), Math.max(h - hw, h - he));
                        int rise = Math.max(Math.max(hn - h, hsu - h), Math.max(hw - h, he - h));
                        if (drop >= 3) rgb = mix(rgb, 0xFFFFFF, 0.42f);
                        else if (rise >= 3) rgb = mix(rgb, 0x000000, 0.30f);
                    }
                }
                int abgr = 0xFF000000 | ((rgb & 255) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 255);
                int px = ox + x;
                int pz = oz + z;
                if (t.img.getPixelRGBA(px, pz) != abgr) {
                    t.img.setPixelRGBA(px, pz, abgr);
                    t.markDirty(pz * TS + px);
                    changed = true;
                }
            }
        }
        t.lastScan = System.currentTimeMillis();
        if (changed) {
            t.glDirty = true;
            t.diskDirty = true;
            touchGroup(t.tx, t.tz);
        }
    }

    private static final int K_NORMAL = 0;
    private static final int K_THIN = 1;
    private static final int K_GLASS = 2;
    private static final int K_LEAVES = 3;
    private static final int K_GRASS = 4;
    private static final int K_SAND = 5;
    private static final Map<Block, Integer> KINDS = new HashMap<>();

    /** Tipul de bloc pentru paleta hartii (cache pe bloc; decis dupa numele din registru). */
    private static int kindOf(Block b) {
        Integer k = KINDS.get(b);
        if (k != null) return k;
        ResourceLocation rl = b.getRegistryName();
        String p = rl == null ? "" : rl.getPath();
        int v = K_NORMAL;
        if (p.contains("fence") || p.endsWith("_wall") || p.contains("bars") || p.contains("pane") || p.contains("rail")
            || p.contains("ladder") || p.contains("chain") || p.contains("scaffolding")) v = K_THIN;
        else if (p.contains("glass")) v = K_GLASS;
        else if (p.endsWith("leaves")) v = K_LEAVES;
        else if (p.equals("grass_block")) v = K_GRASS;
        else if (p.equals("sand")) v = K_SAND;
        KINDS.put(b, v);
        return v;
    }

    /** Inaltimea (MOTION_BLOCKING) a unui vecin; in afara chunk-ului curent doar daca acela e incarcat, altfel inaltimea proprie. */
    private static int nbH(ClientLevel w, int[] hs, int wx0, int wz0, int x, int z, int own) {
        if (x >= 0 && x < 16 && z >= 0 && z < 16) return hs[(z << 4) | x];
        int wx = wx0 + x;
        int wz = wz0 + z;
        if (w.getChunkSource().getChunk(wx >> 4, wz >> 4, ChunkStatus.FULL, false) == null) return own;
        return w.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
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

    // ------------------------------------------------------------------ harta partajata (sincronizare cu serverul)

    /** Dimensiunea curenta e Overworld (singura sincronizata). */
    public static boolean overworld() {
        return overworld;
    }

    /** La iesirea de pe server: uita versiunile serverului. */
    public static void syncReset() {
        serverVer.clear();
        indexDone = false;
        lastNeed = 0;
    }

    public static void serverVersion(int tx, int tz, int ver) {
        if (ver <= 0) serverVer.remove(key(tx, tz));
        else serverVer.put(key(tx, tz), ver);
    }

    /** Indexul de versiuni a fost primit complet. */
    public static void indexComplete() {
        indexDone = true;
        reconcile();
    }

    public static boolean indexReady() {
        return indexDone;
    }

    public static int serverTiles() {
        return serverVer.size();
    }

    /** Dalele de pe disc pe care serverul nu le are se marcheaza ca "de trimis integral". */
    private static void reconcile() {
        if (!indexDone || !overworld || dir == null) return;
        for (long k : known) {
            if (serverVer.containsKey(k)) continue;
            if (needFull.add(k)) metaDirty = true;
            if (sverMap.remove(k) != null) {
                Tile t = tiles.get(k);
                if (t != null) t.sver = 0;
            }
        }
    }

    private static int localSver(long k) {
        Tile t = tiles.get(k);
        if (t != null) return t.sver;
        Integer v = sverMap.get(k);
        return v == null ? 0 : v;
    }

    private static void setLocalSver(long k, int v) {
        Tile t = tiles.get(k);
        if (t != null) t.sver = v;
        sverMap.put(k, v);
        metaDirty = true;
    }

    /**
     * Alege pana la max dale vizibile care lipsesc / sunt vechi fata de server (cele mai apropiate de centrul vizibil intai).
     * Intoarce numarul ales; out contine cheile (tx in 32 de biti sus, tz jos). skip = cheile deja cerute.
     */
    public static int collectNeeded(long[] out, int max, Set<Long> skip) {
        lastNeed = 0;
        if (!overworld || !indexDone || frame - viewFrame > 90 || serverVer.isEmpty()) return 0;
        long area = (long) (vt1x - vt0x + 1) * (vt1z - vt0z + 1);
        int cxm = (vt0x + vt1x) / 2;
        int czm = (vt0z + vt1z) / 2;
        int got = 0;
        long[] bestD = new long[max];
        if (area <= 12000) {
            for (int tz = vt0z; tz <= vt1z; tz++) {
                for (int tx = vt0x; tx <= vt1x; tx++) {
                    long k = key(tx, tz);
                    Integer sv = serverVer.get(k);
                    if (sv == null || sv == localSver(k)) continue;
                    lastNeed++;
                    if (skip.contains(k)) continue;
                    got = insertNear(out, bestD, got, max, k, tx - cxm, tz - czm);
                }
            }
        } else {
            for (Map.Entry<Long, Integer> e : serverVer.entrySet()) {
                long k = e.getKey();
                int tx = kx(k);
                int tz = kz(k);
                if (tx < vt0x || tx > vt1x || tz < vt0z || tz > vt1z) continue;
                if (e.getValue() == localSver(k)) continue;
                lastNeed++;
                if (skip.contains(k)) continue;
                got = insertNear(out, bestD, got, max, k, tx - cxm, tz - czm);
            }
        }
        return got;
    }

    private static int insertNear(long[] out, long[] bestD, int got, int max, long k, int dx, int dz) {
        long d = (long) dx * dx + (long) dz * dz;
        int pos = got;
        while (pos > 0 && bestD[pos - 1] > d) pos--;
        if (pos >= max) return got;
        int last = Math.min(got, max - 1);
        for (int i = last; i > pos; i--) {
            out[i] = out[i - 1];
            bestD[i] = bestD[i - 1];
        }
        out[pos] = k;
        bestD[pos] = d;
        return Math.min(max, got + 1);
    }

    /** Cate dale vizibile mai asteapta sincronizarea (dupa ultima cautare). */
    public static int pendingNeeded() {
        return lastNeed;
    }

    public static int keyX(long k) {
        return kx(k);
    }

    public static int keyZ(long k) {
        return kz(k);
    }

    /** Serverul nu are (sau nu mai are) aceasta dala. */
    public static void serverAbsent(int tx, int tz) {
        serverVer.remove(key(tx, tz));
    }

    private static int toAbgr(int argb) {
        return 0xFF000000 | ((argb & 255) << 16) | (argb & 0xFF00) | ((argb >> 16) & 255);
    }

    /**
     * Uneste pixelii de la server (argb, (64/q)^2) in loc (ABGR 4096). Goalurile locale se completeaza; pixelii existenti se
     * suprascriu doar cu takeServer. Pixelii locali pe care serverul nu-i are se marcheaza in extra (daca nu e null).
     * Intoarce: bit 1 = loc s-a schimbat, bit 2 = exista pixeli locali lipsa pe server.
     */
    private static int mergeServer(int[] loc, int[] argb, int q, boolean takeServer, long[] extra) {
        int n = TS / q;
        int res = 0;
        for (int z = 0; z < TS; z++) {
            int row = (z / q) * n;
            for (int x = 0; x < TS; x++) {
                int sv = argb[row + x / q];
                int i = z * TS + x;
                boolean has = (sv >>> 24) != 0;
                int lv = loc[i];
                if (!has) {
                    if ((lv >>> 24) != 0) {
                        res |= 2;
                        if (extra != null) extra[i >> 6] |= 1L << (i & 63);
                    }
                    continue;
                }
                int nv = toAbgr(sv);
                if ((lv >>> 24) == 0 || (takeServer && lv != nv)) {
                    loc[i] = nv;
                    res |= 1;
                }
            }
        }
        return res;
    }

    /** O dala primita de la server (deja decomprimata pe un thread separat); se uneste in harta locala. */
    public static void applyServerTile(int tx, int tz, int ver, int q, int[] argb) {
        if (!overworld || dir == null || q < 1 || argb.length != (TS / q) * (TS / q)) return;
        long k = key(tx, tz);
        serverVer.put(k, ver);
        if (localSver(k) == ver) return;
        long now = System.currentTimeMillis();
        Tile t = tiles.get(k);
        if (t != null) {
            boolean takeServer = t.sver != 0 && now - t.lastScan > 300000L;
            boolean wasEmpty = true;
            for (int z = 0; z < TS; z++) {
                for (int x = 0; x < TS; x++) {
                    int v = t.img.getPixelRGBA(x, z);
                    S2[z * TS + x] = v;
                    if ((v >>> 24) != 0) wasEmpty = false;
                }
            }
            System.arraycopy(S2, 0, S1, 0, TS * TS);
            long[] extra = new long[64];
            int r = mergeServer(S2, argb, q, takeServer, extra);
            if ((r & 1) != 0) {
                for (int i = 0; i < TS * TS; i++) if (S2[i] != S1[i]) t.img.setPixelRGBA(i % TS, i / TS, S2[i]);
                t.glDirty = true;
                t.diskDirty = true;
                touchGroup(tx, tz);
                if (wasEmpty) t.fadeStart = now;
            }
            if ((r & 2) != 0) {
                if (t.dirty == null) t.dirty = new long[64];
                for (int i = 0; i < 64; i++) t.dirty[i] |= extra[i];
                t.upDirty = true;
            }
            needFull.remove(k);
            t.sver = ver;
        } else if (known.contains(k)) {
            // dala doar pe disc: unire fara textura
            if (!readFile(fileOf(tx, tz), S2)) java.util.Arrays.fill(S2, 0);
            int r = mergeServer(S2, argb, q, localSver(k) != 0, null);
            if ((r & 1) != 0) {
                writeFile(fileOf(tx, tz), S2);
                touchGroup(tx, tz);
            }
            if ((r & 2) != 0) needFull.add(k);
            else needFull.remove(k);
        } else {
            java.util.Arrays.fill(S2, 0);
            mergeServer(S2, argb, q, false, null);
            if (writeFile(fileOf(tx, tz), S2)) {
                markKnown(k);
                touchGroup(tx, tz);
            } else {
                return;
            }
        }
        sverMap.put(k, ver);
        metaDirty = true;
    }

    /** Confirmarea unui upload: daca eram sincronizati cu versiunea anterioara, acum suntem cu cea noua. */
    public static void ack(int tx, int tz, int ver, int prev) {
        long k = key(tx, tz);
        serverVer.put(k, ver);
        if (localSver(k) == prev) setLocalSver(k, ver);
    }

    private static boolean near(int tx, int tz, double px, double pz, int range) {
        return Math.abs(tx * (double) TS + TS / 2d - px) <= range + TS && Math.abs(tz * (double) TS + TS / 2d - pz) <= range + TS;
    }

    /** Pana la max dale cu modificari netrimise aflate in raza serverului fata de jucator; marcheaza ca trimise. */
    public static List<UpTile> gatherUploads(int max, double px, double pz, int range) {
        List<UpTile> out = new ArrayList<>(max);
        if (!overworld || dir == null) return out;
        for (Tile t : tiles.values()) {
            if (out.size() >= max) break;
            if (!t.upDirty || !near(t.tx, t.tz, px, pz, range)) continue;
            UpTile u = fill(t, false);
            if (u != null) out.add(u);
        }
        if (out.size() < max && !needFull.isEmpty()) {
            Long pick = null;
            for (long k : needFull) {
                if (near(kx(k), kz(k), px, pz, range) && known.contains(k)) {
                    pick = k;
                    break;
                }
            }
            if (pick != null) {
                Tile t = tile(kx(pick), kz(pick), false);
                if (t == null || t.img == null) {
                    needFull.remove(pick);
                    metaDirty = true;
                } else {
                    UpTile u = fill(t, true);
                    if (u != null) out.add(u);
                }
            }
        }
        return out;
    }

    private static UpTile fill(Tile t, boolean full) {
        UpTile u = new UpTile();
        u.tx = t.tx;
        u.tz = t.tz;
        int n = 0;
        for (int i = 0; i < TS * TS; i++) {
            if (!full && (t.dirty == null || (t.dirty[i >> 6] & (1L << (i & 63))) == 0)) continue;
            int v = t.img.getPixelRGBA(i % TS, i / TS);
            if ((v >>> 24) == 0) continue;
            u.px[i] = v;
            n++;
        }
        if (t.dirty != null) java.util.Arrays.fill(t.dirty, 0L);
        t.upDirty = false;
        if (needFull.remove(key(t.tx, t.tz))) metaDirty = true;
        return n == 0 ? null : u;
    }

    /** Zona stearsa/reimprospatata de un admin: uita dalele locale din dreptunghi (blocuri); scanarea le reface din ce se vede. */
    public static int deleteRect(int x1, int z1, int x2, int z2) {
        if (dir == null || !overworld) return 0;
        int t0x = Math.floorDiv(Math.min(x1, x2), TS);
        int t1x = Math.floorDiv(Math.max(x1, x2) - 1, TS);
        int t0z = Math.floorDiv(Math.min(z1, z2), TS);
        int t1z = Math.floorDiv(Math.max(z1, z2) - 1, TS);
        int n = 0;
        for (long k : new ArrayList<>(known)) {
            int tx = kx(k);
            int tz = kz(k);
            if (tx < t0x || tx > t1x || tz < t0z || tz > t1z) continue;
            Tile t = tiles.get(k);
            if (t != null) {
                t.diskDirty = false;
                t.upDirty = false;
                t.sver = 0;
                release(t, false);
            }
            try {
                Files.deleteIfExists(fileOf(tx, tz));
            } catch (IOException ignored) {
                // fisier blocat: ramane, dar nu mai e cunoscut
            }
            unknown(k);
            sverMap.remove(k);
            needFull.remove(k);
            n++;
        }
        for (long k : new ArrayList<>(serverVer.keySet())) {
            int tx = kx(k);
            int tz = kz(k);
            if (tx >= t0x && tx <= t1x && tz >= t0z && tz <= t1z) serverVer.remove(k);
        }
        metaDirty = true;
        return n;
    }

    // ------------------------------------------------------------------ prezentare generala (zoom mic)

    private static void startBuild(Ov o) {
        o.building = true;
        o.lastBuild = System.currentTimeMillis();
        long gk = key(o.gx, o.gz);
        ovDirty.remove(gk);
        final int[][] mem = new int[OV * OV][];
        final Path[] files = new Path[OV * OV];
        for (int j = 0; j < OV; j++) {
            for (int i = 0; i < OV; i++) {
                int tx = o.gx * OV + i;
                int tz = o.gz * OV + j;
                long k = key(tx, tz);
                Tile t = tiles.get(k);
                if (t != null && t.img != null) {
                    int[] px = new int[TS * TS];
                    for (int z = 0; z < TS; z++) for (int x = 0; x < TS; x++) px[z * TS + x] = t.img.getPixelRGBA(x, z);
                    mem[j * OV + i] = px;
                } else if (known.contains(k)) {
                    files[j * OV + i] = fileOf(tx, tz);
                }
            }
        }
        ovPool.execute(() -> {
            int[] out = new int[TS * TS];
            int[] buf = new int[TS * TS];
            int cell = TS / OV; // 16 pixeli de grup per dala
            int step = TS / cell; // 4 pixeli de dala per pixel de grup
            for (int j = 0; j < OV; j++) {
                for (int i = 0; i < OV; i++) {
                    int[] px = mem[j * OV + i];
                    if (px == null) {
                        Path f = files[j * OV + i];
                        if (f == null || !readFile(f, buf)) continue;
                        px = buf;
                    }
                    for (int cz = 0; cz < cell; cz++) {
                        for (int cx = 0; cx < cell; cx++) {
                            int r = 0;
                            int g = 0;
                            int b = 0;
                            int c = 0;
                            for (int dz = 0; dz < step; dz++) {
                                for (int dx = 0; dx < step; dx++) {
                                    int v = px[(cz * step + dz) * TS + cx * step + dx];
                                    if ((v >>> 24) == 0) continue;
                                    r += v & 255;
                                    g += (v >> 8) & 255;
                                    b += (v >> 16) & 255;
                                    c++;
                                }
                            }
                            if (c == 0) continue;
                            out[(j * cell + cz) * TS + i * cell + cx] = 0xFF000000 | ((b / c) << 16) | ((g / c) << 8) | (r / c);
                        }
                    }
                }
            }
            o.ready = out;
            o.building = false;
        });
    }

    private static void drawOverview(GuiGraphics g, double camX, double camZ, float zoom, float cx, float cy, double minX, double maxX,
                                     double minZ, double maxZ, float alpha) {
        int span = TS * OV;
        int g0x = Mth.floor(minX / span);
        int g1x = Mth.floor(maxX / span);
        int g0z = Mth.floor(minZ / span);
        int g1z = Mth.floor(maxZ / span);
        Matrix4f m = g.pose().last().pose();
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        int uploads = 3;
        int builds = 2;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        int drawn = 0;
        for (int gz = g0z; gz <= g1z; gz++) {
            for (int gx = g0x; gx <= g1x; gx++) {
                long gk = key(gx, gz);
                if (!groupCount.containsKey(gk)) continue;
                Ov o = ovs.get(gk);
                if (o == null) {
                    if (builds <= 0) continue;
                    if (ovs.size() >= MAX_OV) {
                        Ov old = null;
                        for (Ov v : ovs.values()) if (old == null || v.used < old.used) old = v;
                        if (old != null && !old.building) releaseOv(old);
                    }
                    o = new Ov(gx, gz);
                    o.img = new NativeImage(TS, TS, true);
                    o.tex = new DynamicTexture(o.img);
                    o.rl = register(o.tex, "o", true);
                    ovs.put(gk, o);
                }
                o.used = frame;
                int[] rd = o.ready;
                if (rd != null && uploads > 0) {
                    o.ready = null;
                    for (int z = 0; z < TS; z++) for (int x = 0; x < TS; x++) o.img.setPixelRGBA(x, z, rd[z * TS + x]);
                    o.tex.upload();
                    if (!o.hasData) o.fadeStart = now;
                    o.hasData = true;
                    uploads--;
                }
                if (!o.building && builds > 0 && (!o.hasData && rd == null || (ovDirty.contains(gk) && now - o.lastBuild > 4000L))) {
                    builds--;
                    startBuild(o);
                }
                if (!o.hasData) continue;
                if (++drawn > 900) break;
                long dt = now - o.fadeStart;
                float fa = dt >= FADE_MS ? 1f : Math.max(0f, dt / (float) FADE_MS);
                RenderSystem.setShaderColor(1f, 1f, 1f, alpha * fa);
                float x0 = (float) (cx + (gx * span - camX) * zoom);
                float y0 = (float) (cy + (gz * span - camZ) * zoom);
                float x1 = x0 + span * zoom;
                float y1 = y0 + span * zoom;
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, o.rl);
                BufferBuilder b = Tesselator.getInstance().getBuilder();
                b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                b.vertex(m, x0, y1, 0f).uv(0f, 1f).endVertex();
                b.vertex(m, x1, y1, 0f).uv(1f, 1f).endVertex();
                b.vertex(m, x1, y0, 0f).uv(1f, 0f).endVertex();
                b.vertex(m, x0, y0, 0f).uv(0f, 0f).endVertex();
                Tesselator.getInstance().end();
            }
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ desen

    /**
     * Deseneaza dalele vizibile (cu aparitie in fundal la prima afisare). Ecran: sx = cx + (wx - camX) * zoom,
     * sy = cy + (wz - camZ) * zoom; nord (-Z) in sus. La zoom mic (sub {@link #OV_ZOOM}) deseneaza grupuri de 4x4 dale.
     */
    public static void draw(GuiGraphics g, double camX, double camZ, float zoom, float cx, float cy, float sw, float sh, float alpha) {
        frame++;
        if (dimKey == null) return;
        double minX = camX - cx / zoom - 1;
        double maxX = camX + (sw - cx) / zoom + 1;
        double minZ = camZ - cy / zoom - 1;
        double maxZ = camZ + (sh - cy) / zoom + 1;
        int t0x = Mth.floor(minX / TS);
        int t1x = Mth.floor(maxX / TS);
        int t0z = Mth.floor(minZ / TS);
        int t1z = Mth.floor(maxZ / TS);
        vt0x = t0x;
        vt1x = t1x;
        vt0z = t0z;
        vt1z = t1z;
        viewFrame = frame;
        if (zoom < OV_ZOOM) {
            drawOverview(g, camX, camZ, zoom, cx, cy, minX, maxX, minZ, maxZ, alpha);
            return;
        }
        Matrix4f m = g.pose().last().pose();
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        int uploads = 4;
        int loads = 6;
        int drawn = 0;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
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
            long dt = now - t.fadeStart;
            float fa = dt >= FADE_MS ? 1f : Math.max(0f, dt / (float) FADE_MS);
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha * fa);
            float x0 = (float) (cx + (tx * TS - camX) * zoom);
            float y0 = (float) (cy + (tz * TS - camZ) * zoom);
            float x1 = x0 + TS * zoom;
            float y1 = y0 + TS * zoom;
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, t.rl);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            b.vertex(m, x0, y1, 0f).uv(0f, 1f).endVertex();
            b.vertex(m, x1, y1, 0f).uv(1f, 1f).endVertex();
            b.vertex(m, x1, y0, 0f).uv(1f, 0f).endVertex();
            b.vertex(m, x0, y0, 0f).uv(0f, 0f).endVertex();
            Tesselator.getInstance().end();
        }
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    private static float[] shA = new float[4 * (MiniMapClip.MAX + 8)];
    private static float[] shB = new float[4 * (MiniMapClip.MAX + 8)];
    private static float[] shIn = new float[16];

    /**
     * Desenul pentru minimap: aceleasi dale si aceeasi transformare ca {@link #draw}, dar cu harta rotita cu unghiul theta
     * (radiani, in jurul centrului ecranului cx, cy) si taiata geometric pe poligonul convex clip (x0,y0,x1,y1,...; clipN varfuri).
     * Ecran: p = c + R(theta) * (w - cam) * zoom; theta = 0 pastreaza nordul (-Z) in sus. rad = raza cercului care incadreaza
     * poligonul (pentru a alege dalele vizibile).
     */
    public static void drawClipped(GuiGraphics g, double camX, double camZ, float zoom, float cx, float cy, double theta,
                                   float[] clip, int clipN, float rad, float alpha) {
        frame++;
        if (dimKey == null || clipN < 3 || clipN > MiniMapClip.MAX) return;
        double ct = Math.cos(theta);
        double st = Math.sin(theta);
        double wr = rad / zoom + 2.0;
        int t0x = Mth.floor((camX - wr) / TS);
        int t1x = Mth.floor((camX + wr) / TS);
        int t0z = Mth.floor((camZ - wr) / TS);
        int t1z = Mth.floor((camZ + wr) / TS);
        vt0x = t0x;
        vt1x = t1x;
        vt0z = t0z;
        vt1z = t1z;
        viewFrame = frame;
        Matrix4f m = g.pose().last().pose();
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        int uploads = 2;
        int loads = 3;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        for (int tz = t0z; tz <= t1z; tz++) {
            for (int tx = t0x; tx <= t1x; tx++) {
                long k = key(tx, tz);
                Tile t = tiles.get(k);
                if (t == null) {
                    if (!known.contains(k) || loads <= 0) continue;
                    loads--;
                    t = tile(tx, tz, false);
                    if (t == null) continue;
                }
                t.used = frame;
                if (t.glDirty && uploads > 0) {
                    t.tex.upload();
                    t.glDirty = false;
                    uploads--;
                }
                long dt = now - t.fadeStart;
                float fa = dt >= FADE_MS ? 1f : Math.max(0f, dt / (float) FADE_MS);
                RenderSystem.setShaderColor(1f, 1f, 1f, alpha * fa);
                // colturile dalei: (x0,z0) (x1,z0) (x1,z1) (x0,z1) cu u,v
                for (int c = 0; c < 4; c++) {
                    double wx = (tx + (c == 1 || c == 2 ? 1 : 0)) * (double) TS;
                    double wz = (tz + (c >= 2 ? 1 : 0)) * (double) TS;
                    double dx = (wx - camX) * zoom;
                    double dz = (wz - camZ) * zoom;
                    shIn[c * 4] = (float) (cx + dx * ct - dz * st);
                    shIn[c * 4 + 1] = (float) (cy + dx * st + dz * ct);
                    shIn[c * 4 + 2] = (c == 1 || c == 2) ? 1f : 0f;
                    shIn[c * 4 + 3] = c >= 2 ? 1f : 0f;
                }
                int n = MiniMapClip.clip(shIn, 4, 4, clip, clipN, shA, shB);
                if (n < 3) continue;
                float[] r = MiniMapClip.resultInTmp ? shB : shA;
                RenderSystem.setShader(GameRenderer::getPositionTexShader);
                RenderSystem.setShaderTexture(0, t.rl);
                BufferBuilder b = Tesselator.getInstance().getBuilder();
                b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX);
                for (int i = 0; i < n; i++) b.vertex(m, r[i * 4], r[i * 4 + 1], 0f).uv(r[i * 4 + 2], r[i * 4 + 3]).endVertex();
                Tesselator.getInstance().end();
            }
        }
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
