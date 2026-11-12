package ro.solarstudios.flowcity.maps;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Memoria hartii: culoarea si inaltimea fiecarei coloane din chunk-urile pe care le-ai vazut (ca la GTA, harta se "descopera").
 * Se salveaza pe disc per server, deci orasul ramane pe harta si dupa ce chunk-urile se descarca sau repornesti jocul.
 */
public final class MapCache {
    private MapCache() {}

    public static final int WATER = 0x01000000;

    private static final class Chunk {
        final int[] rgb = new int[256];
        final short[] h = new short[256];
    }

    private static final Map<Long, Chunk> CHUNKS = new ConcurrentHashMap<>();
    private static final Set<Long> SCANNED = new HashSet<>();
    private static String loadedKey = "";
    private static boolean dirty;
    private static long lastSave;
    private static int tick;
    private static byte[] pendingServer;

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static String cacheName(ClientLevel w) {
        ServerData sd = Minecraft.getInstance().getCurrentServer();
        String base = sd == null ? "local" : sd.ip;
        return base.replaceAll("[^a-zA-Z0-9.\\-]", "_") + "_" + w.dimension().location().getPath();
    }

    private static Path file(String name) {
        return FMLPaths.GAMEDIR.get().resolve("flowcity-mapcache").resolve(name + ".bin");
    }

    /** Valoare: inaltime (>=0) sau -1 daca coloana nu e cunoscuta. */
    public static int height(int wx, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        if (c == null) return -1;
        int h = c.h[((wz & 15) << 4) | (wx & 15)];
        return h <= 0 ? -1 : h;
    }

    /** RGB (+ bitul WATER daca e apa). */
    public static int color(int wx, int wz) {
        Chunk c = CHUNKS.get(key(wx >> 4, wz >> 4));
        return c == null ? 0 : c.rgb[((wz & 15) << 4) | (wx & 15)];
    }

    public static int chunkCount() {
        return CHUNKS.size();
    }

    // ------------------------------------------------------------------ scanare

    public static void tick(ClientLevel w, Player p) {
        String name = cacheName(w);
        if (!name.equals(loadedKey)) {
            save();
            CHUNKS.clear();
            SCANNED.clear();
            loadedKey = name;
            load(name);
            if (pendingServer != null) {
                byte[] pd = pendingServer;
                pendingServer = null;
                mergeServer(pd);
            }
        }
        tick++;
        int pcx = ((int) Math.floor(p.getX())) >> 4;
        int pcz = ((int) Math.floor(p.getZ())) >> 4;
        int budget = 2;
        // 1) chunk-uri noi in jurul jucatorului
        outer:
        for (int r = 0; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    long k = key(pcx + dx, pcz + dz);
                    if (SCANNED.contains(k)) continue;
                    if (w.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false) == null) continue;
                    scan(w, pcx + dx, pcz + dz);
                    SCANNED.add(k);
                    if (--budget <= 0) break outer;
                }
            }
        }
        // 2) reimprospatare periodica a zonei apropiate (cladiri noi etc.)
        if (budget > 0 && tick % 100 == 0) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (w.getChunkSource().getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false) != null) scan(w, pcx + dx, pcz + dz);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (dirty && now - lastSave > 90000) {
            lastSave = now;
            saveAsync();
        }
    }

    private static void scan(ClientLevel w, int cx, int cz) {
        Chunk c = CHUNKS.computeIfAbsent(key(cx, cz), k -> new Chunk());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = (cx << 4) + x;
                int wz = (cz << 4) + z;
                int h = w.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
                int i = (z << 4) | x;
                if (h <= 0) {
                    c.h[i] = 0;
                    c.rgb[i] = 0;
                    continue;
                }
                pos.set(wx, h - 1, wz);
                net.minecraft.world.level.block.state.BlockState st = w.getBlockState(pos);
                int rgb = st.getMapColor(w, pos).col;
                if (rgb == 0) rgb = 0x404040;
                if (!st.getFluidState().isEmpty()) rgb |= WATER;
                c.h[i] = (short) h;
                c.rgb[i] = rgb;
            }
        }
        dirty = true;
    }

    /** Combina harta orasului primita de la server (GZIP, vezi CityMapScanner). Chunk-urile scanate deja de client raman neatinse. */
    public static void mergeServer(byte[] gz) {
        if (loadedKey.isEmpty()) {
            pendingServer = gz;
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new java.io.ByteArrayInputStream(gz))))) {
            int n = in.readInt();
            byte[] b = new byte[512];
            for (int i = 0; i < n; i++) {
                int cx = in.readInt();
                int cz = in.readInt();
                in.readFully(b);
                long k = key(cx, cz);
                if (SCANNED.contains(k)) continue;
                Chunk c = new Chunk();
                for (int j = 0; j < 256; j++) {
                    int idx = b[j] & 63;
                    boolean water = (b[j] & 64) != 0;
                    net.minecraft.world.level.material.MapColor mc = net.minecraft.world.level.material.MapColor.byId(idx);
                    int rgb = mc == null || mc.col == 0 ? 0x404040 : mc.col;
                    int h = b[256 + j] & 0xFF;
                    if (h == 0) {
                        c.rgb[j] = 0;
                        c.h[j] = 0;
                        continue;
                    }
                    c.rgb[j] = water ? rgb | WATER : rgb;
                    c.h[j] = (short) h;
                }
                CHUNKS.put(k, c);
            }
            dirty = true;
        } catch (IOException | RuntimeException ignored) {
            // date stricate: ignorate
        }
    }

    // ------------------------------------------------------------------ disc

    private static void load(String name) {
        Path f = file(name);
        if (!Files.exists(f)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                Chunk c = new Chunk();
                for (int j = 0; j < 256; j++) c.rgb[j] = in.readInt();
                for (int j = 0; j < 256; j++) c.h[j] = in.readShort();
                CHUNKS.put(k, c);
            }
        } catch (IOException | RuntimeException ignored) {
            CHUNKS.clear();
        }
        dirty = false;
    }

    private static void saveAsync() {
        final String name = loadedKey;
        Thread t = new Thread(() -> write(name), "FlowCity-MapSave");
        t.setDaemon(true);
        t.start();
    }

    public static void save() {
        if (dirty && !loadedKey.isEmpty()) write(loadedKey);
    }

    private static synchronized void write(String name) {
        try {
            Path f = file(name);
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(name + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(CHUNKS.size());
                for (Map.Entry<Long, Chunk> e : CHUNKS.entrySet()) {
                    out.writeLong(e.getKey());
                    for (int j = 0; j < 256; j++) out.writeInt(e.getValue().rgb[j]);
                    for (int j = 0; j < 256; j++) out.writeShort(e.getValue().h[j]);
                }
            }
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException | RuntimeException ignored) {
            // nu e critic
        }
    }

    public static void unload() {
        save();
        CHUNKS.clear();
        SCANNED.clear();
        loadedKey = "";
    }
}
