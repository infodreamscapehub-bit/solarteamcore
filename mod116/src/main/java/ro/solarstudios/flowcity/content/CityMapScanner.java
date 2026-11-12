package ro.solarstudios.flowcity.content;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.MaterialColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

/**
 * Harta orasului (server): scaneaza chunk-urile unei zone din overworld si salveaza culoarea + inaltimea fiecarei coloane in
 * flowcity-citymap.bin. Pluginul trimite fisierul jucatorilor, care il combina cu harta lor descoperita.
 * Format fisier (GZIP): int n, apoi n x {int cx, int cz, 256 octeti culoare (index MaterialColor | 64 = apa), 256 octeti inaltime}.
 * Apelabila prin reflexie din pluginul Bukkit.
 */
public final class CityMapScanner {
    private CityMapScanner() {}

    private static final Map<Long, byte[]> DATA = new ConcurrentHashMap<>();
    private static final ArrayDeque<int[]> QUEUE = new ArrayDeque<>();
    private static boolean loaded;
    private static volatile boolean running;
    private static volatile int total;
    private static volatile int done;

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("flowcity-citymap.bin");
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int cx = in.readInt();
                int cz = in.readInt();
                byte[] b = new byte[512];
                in.readFully(b);
                DATA.put(key(cx, cz), b);
            }
        } catch (IOException | RuntimeException ignored) {
            DATA.clear();
        }
    }

    private static synchronized void save() {
        try {
            Path f = file();
            Path tmp = f.resolveSibling("flowcity-citymap.tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
                out.writeInt(DATA.size());
                for (Map.Entry<Long, byte[]> e : DATA.entrySet()) {
                    long k = e.getKey();
                    out.writeInt((int) (k >> 32));
                    out.writeInt((int) k);
                    out.write(e.getValue());
                }
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // nu e critic
        }
    }

    public static boolean running() {
        return running;
    }

    public static int done() {
        return done;
    }

    public static int total() {
        return total;
    }

    public static int chunks() {
        ensureLoaded();
        return DATA.size();
    }

    /** Porneste scanarea dreptunghiului (coordonate blocuri). Intoarce null daca a pornit, altfel motivul. */
    public static synchronized String start(int x1, int z1, int x2, int z2) {
        if (running) return "O scanare e deja in curs (" + done + "/" + total + ").";
        ensureLoaded();
        int cx1 = Math.min(x1, x2) >> 4;
        int cx2 = Math.max(x1, x2) >> 4;
        int cz1 = Math.min(z1, z2) >> 4;
        int cz2 = Math.max(z1, z2) >> 4;
        long n = (long) (cx2 - cx1 + 1) * (cz2 - cz1 + 1);
        if (n > 16384) return "Zona e prea mare (max 128x128 chunk-uri = 2048x2048 blocuri).";
        QUEUE.clear();
        for (int cz = cz1; cz <= cz2; cz++) for (int cx = cx1; cx <= cx2; cx++) QUEUE.add(new int[] {cx, cz});
        total = (int) n;
        done = 0;
        running = true;
        return null;
    }

    private static void scan(ServerWorld w, int cx, int cz) {
        w.getChunk(cx, cz); // incarca (si genereaza daca nu exista)
        byte[] b = new byte[512];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = (cx << 4) + x;
                int wz = (cz << 4) + z;
                int h = w.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
                int i = (z << 4) | x;
                if (h <= 0) continue;
                pos.set(wx, h - 1, wz);
                BlockState st = w.getBlockState(pos);
                MaterialColor mc = st.getMapColor(w, pos);
                int idx = mc.id & 63;
                if (idx == 0) idx = 11; // gri implicit (ca 0x404040 pe client)
                if (!st.getFluidState().isEmpty()) idx |= 64;
                b[i] = (byte) idx;
                b[256 + i] = (byte) Math.min(255, h);
            }
        }
        DATA.put(key(cx, cz), b);
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || !running) return;
            net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            ServerWorld w = server == null ? null : server.getLevel(World.OVERWORLD);
            if (w == null) {
                running = false;
                return;
            }
            try {
                for (int i = 0; i < 4 && !QUEUE.isEmpty(); i++) {
                    int[] c = QUEUE.poll();
                    scan(w, c[0], c[1]);
                    done++;
                }
            } catch (RuntimeException ex) {
                QUEUE.clear();
            }
            if (QUEUE.isEmpty()) {
                running = false;
                save();
            }
        }
    }
}
