package ro.solarstudios.flowcity.bridge.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Harta GPS PARTAJATA (server): clientii trimit dale de 64x64 blocuri (culori de suprafata din chunk-urile incarcate la ei),
 * serverul le valideaza, le uneste (pixelii noi suprascriu pe cei vechi) si le tine pe disc in {@code plugins/FlowCityBridge/gps/map/}
 * (un fisier per dala, scriere atomica, versiune per dala). Orice client primeste doar dalele de care are nevoie (cerute explicit,
 * dupa versiune), deci tot orasul apare pentru toti, fara ca fiecare sa-l explorez.
 *
 * <p>Protocol (modulul "gps", JSON):
 * <ul>
 *   <li>server -> client: {@code map.cfg} (limite), {@code map.idx} (index de versiuni, pe pagini), {@code map.upd} (versiuni noi),
 *       {@code map.tiles} (continut cerut), {@code map.ack} (confirmare upload), {@code map.del} (zona stearsa), {@code map.res} (rezultat admin)</li>
 *   <li>client -> server: {@code map.put} (upload, max 3 dale), {@code map.get} (cerere dale), {@code map.del} (admin)</li>
 * </ul>
 * Pixelii unei dale: (64/q)^2 intregi ARGB big-endian, comprimati cu zlib; alpha 0 = neexplorat. Toate operatiile pe disc ruleaza
 * pe un thread separat; thread-ul principal doar valideaza si trimite.
 */
final class SharedMap {
    static final int TS = 64;
    private static final int MAGIC = 0x46434D31; // FCM1
    private static final int GET_MAX_KEYS = 32;
    private static final int PUT_MAX_TILES = 3;
    private static final int PUT_MAX_B64 = 24000;
    private static final int IDX_PAGE = 1500;

    private final GpsModule mod;
    private final Plugin plugin;
    private final Logger log;
    private final File dir;

    // configurare (gps.yml > map)
    private final boolean enabled;
    private final int quality;
    private final int maxTiles;
    private final int radius;
    private final int uploadRange;
    private final int perMinute;
    private final int putGapMs;
    private final int adminMaxSide;

    /** Versiunea fiecarei dale existente (citibila de oriunde). */
    private final ConcurrentHashMap<Long, Integer> vers = new ConcurrentHashMap<>();
    private final ExecutorService io;
    /** Doar thread-ul io: cache de dale proaspete (cheie -> dala). */
    private final LinkedHashMap<Long, Rec> cache = new LinkedHashMap<Long, Rec>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Rec> e) {
            return size() > 256;
        }
    };
    private final Map<UUID, PState> states = new HashMap<>();
    /** Versiuni noi de anuntat (cheie -> cine a urcat; null = mai multi). Doar thread-ul principal. */
    private final Map<Long, UUID> pendingUpd = new LinkedHashMap<>();
    private BukkitTask flusher;
    private long lastAdminOp;

    private static final class Rec {
        int ver;
        int q;
        byte[] z;
    }

    private static final class PState {
        boolean ready;
        long lastPut;
        long lastGet;
        long minuteStart;
        int minuteCount;
        long bytesWindow;
        long bytesCount;
    }

    SharedMap(GpsModule mod, Plugin plugin, File gpsDir, YamlConfiguration cfg) {
        this.mod = mod;
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.dir = new File(gpsDir, "map");
        enabled = cfg.getBoolean("map.enabled", true);
        int q = cfg.getInt("map.quality", 1);
        quality = q == 2 || q == 4 ? q : 1;
        maxTiles = Math.max(100, Math.min(400000, cfg.getInt("map.max-tiles", 40000)));
        radius = Math.max(256, Math.min(30000000, cfg.getInt("map.radius", 12000)));
        uploadRange = Math.max(64, Math.min(2048, cfg.getInt("map.upload-range", 640)));
        perMinute = Math.max(6, Math.min(600, cfg.getInt("map.upload-per-minute", 90)));
        putGapMs = Math.max(100, Math.min(5000, cfg.getInt("map.upload-min-gap-ms", 600)));
        adminMaxSide = Math.max(64, Math.min(16384, cfg.getInt("map.admin-max-side", 4096)));
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FlowCity-GpsMap-IO");
            t.setDaemon(true);
            return t;
        });
        if (enabled) {
            index();
            flusher = Bukkit.getScheduler().runTaskTimer(plugin, this::flushUpdates, 40L, 20L);
            log.info("[GPS] Harta partajata: " + vers.size() + " dale pe disc (" + dir.getPath() + "), calitate 1/" + quality + ".");
        }
    }

    void shutdown() {
        if (flusher != null) flusher.cancel();
        io.shutdown();
        try {
            io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ cheie / fisiere

    private static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    private static int kx(long k) {
        return (int) (k >> 32);
    }

    private static int kz(long k) {
        return (int) k;
    }

    private File file(int tx, int tz) {
        return new File(dir, tx + "_" + tz + ".t");
    }

    /** Citeste doar antetul fiecarui fisier (versiunea) la pornire. */
    private void index() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".t")) continue;
            n = n.substring(0, n.length() - 2);
            int u = n.indexOf('_', 1);
            if (u < 0) continue;
            try (DataInputStream in = new DataInputStream(Files.newInputStream(f.toPath()))) {
                if (in.readInt() != MAGIC) continue;
                int v = in.readInt();
                vers.put(key(Integer.parseInt(n.substring(0, u)), Integer.parseInt(n.substring(u + 1))), v);
            } catch (IOException | NumberFormatException ignored) {
                // fisier strain sau stricat: il ignoram
            }
        }
    }

    // ------------------------------------------------------------------ operatii pe disc (thread io)

    private Rec read(int tx, int tz) {
        long k = key(tx, tz);
        Rec r = cache.get(k);
        if (r != null) return r;
        File f = file(tx, tz);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f.toPath()))) {
            if (in.readInt() != MAGIC) return null;
            r = new Rec();
            r.ver = in.readInt();
            r.q = in.readUnsignedByte();
            int len = in.readInt();
            if (len < 0 || len > 70000 || r.q < 1) return null;
            r.z = new byte[len];
            in.readFully(r.z);
            cache.put(k, r);
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    private void write(int tx, int tz, Rec r) throws IOException {
        dir.mkdirs();
        File f = file(tx, tz);
        File tmp = new File(dir, tx + "_" + tz + ".tmp");
        ByteBuffer b = ByteBuffer.allocate(13 + r.z.length);
        b.putInt(MAGIC).putInt(r.ver).put((byte) r.q).putInt(r.z.length).put(r.z);
        Files.write(tmp.toPath(), b.array());
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] inflate(byte[] z, int expected) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inf.finished()) {
                int r = inf.inflate(out, n, expected - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
            if (n != expected || !inf.finished()) return null; // prea scurt sau prea lung (bomba zip)
            return out;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(6);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length / 3 + 64);
            byte[] buf = new byte[4096];
            while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
            return bo.toByteArray();
        } finally {
            d.end();
        }
    }

    private static int[] toInts(byte[] raw) {
        int[] a = new int[raw.length / 4];
        ByteBuffer.wrap(raw).asIntBuffer().get(a);
        return a;
    }

    private static byte[] toBytes(int[] a) {
        ByteBuffer b = ByteBuffer.allocate(a.length * 4);
        b.asIntBuffer().put(a);
        return b.array();
    }

    /** Schimba rezolutia unei dale (calitate q1 -> q2), folosita doar daca se schimba setarea map.quality. */
    private static int[] resample(int[] src, int q1, int q2) {
        int n1 = TS / q1;
        int n2 = TS / q2;
        int[] out = new int[n2 * n2];
        for (int z = 0; z < n2; z++) {
            for (int x = 0; x < n2; x++) out[z * n2 + x] = src[Math.min(n1 - 1, z * q2 / q1) * n1 + Math.min(n1 - 1, x * q2 / q1)];
        }
        return out;
    }

    private static final class MergeRes {
        boolean changed;
        int ver;
        int prev;
        String err;
    }

    /** Uneste o dala primita in cea de pe disc. Ruleaza pe thread-ul io. */
    private MergeRes merge(int tx, int tz, int q, byte[] z) {
        MergeRes res = new MergeRes();
        int n = TS / q;
        byte[] raw = inflate(z, n * n * 4);
        if (raw == null) {
            res.err = "dala invalida";
            return res;
        }
        int[] in = toInts(raw);
        Rec old = read(tx, tz);
        int[] cur;
        if (old == null) {
            if (vers.size() >= maxTiles) {
                res.err = "limita de dale atinsa";
                return res;
            }
            cur = new int[n * n];
            res.prev = 0;
        } else {
            byte[] oraw = inflate(old.z, (TS / old.q) * (TS / old.q) * 4);
            cur = oraw == null ? new int[n * n] : toInts(oraw);
            if (old.q != q) cur = resample(cur, old.q, q);
            res.prev = old.ver;
        }
        boolean changed = old == null;
        boolean any = false;
        for (int i = 0; i < in.length; i++) {
            int p = in[i];
            if ((p >>> 24) == 0) continue;
            p |= 0xFF000000;
            any = true;
            if (cur[i] != p) {
                cur[i] = p;
                changed = true;
            }
        }
        if (!any && old == null) {
            res.err = "dala goala";
            return res;
        }
        if (!changed) {
            res.ver = old.ver;
            res.changed = false;
            return res;
        }
        Rec r = new Rec();
        r.q = q;
        r.z = deflate(toBytes(cur));
        r.ver = Math.max(res.prev + 1, (int) (System.currentTimeMillis() / 1000L));
        try {
            write(tx, tz, r);
        } catch (IOException e) {
            log.warning("[GPS] Nu pot salva dala " + tx + "," + tz + ": " + e);
            res.err = "eroare disc";
            return res;
        }
        cache.put(key(tx, tz), r);
        vers.put(key(tx, tz), r.ver);
        res.ver = r.ver;
        res.changed = true;
        return res;
    }

    // ------------------------------------------------------------------ jucatori

    private PState st(Player p) {
        PState s = states.get(p.getUniqueId());
        if (s == null) {
            s = new PState();
            states.put(p.getUniqueId(), s);
        }
        return s;
    }

    void onQuit(Player p) {
        states.remove(p.getUniqueId());
    }

    /** Trimite limitele si indexul de versiuni (pe pagini, la cate 2 tick-uri) cand clientul e gata. */
    void onClientReady(Player p) {
        JsonObject c = new JsonObject();
        c.addProperty("en", enabled);
        c.addProperty("q", quality);
        c.addProperty("up", enabled && p.hasPermission("flowcity.gps.upload"));
        c.addProperty("gap", putGapMs);
        c.addProperty("perMin", perMinute);
        c.addProperty("range", uploadRange);
        c.addProperty("max", PUT_MAX_TILES);
        mod.send(p, "map.cfg", c);
        if (!enabled) return;
        final PState s = st(p);
        s.ready = false;
        final List<long[]> snap = new ArrayList<>(vers.size());
        for (Map.Entry<Long, Integer> e : vers.entrySet()) snap.add(new long[] {kx(e.getKey()), kz(e.getKey()), e.getValue()});
        final int pages = Math.max(1, (snap.size() + IDX_PAGE - 1) / IDX_PAGE);
        final UUID id = p.getUniqueId();
        for (int pg = 0; pg < pages; pg++) {
            final int page = pg;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player q = Bukkit.getPlayer(id);
                if (q == null || !q.isOnline()) return;
                JsonObject o = new JsonObject();
                o.addProperty("page", page);
                o.addProperty("last", page == pages - 1);
                JsonArray a = new JsonArray();
                for (int i = page * IDX_PAGE; i < Math.min(snap.size(), (page + 1) * IDX_PAGE); i++) {
                    long[] e = snap.get(i);
                    JsonArray t = new JsonArray();
                    t.add((int) e[0]);
                    t.add((int) e[1]);
                    t.add((int) e[2]);
                    a.add(t);
                }
                o.add("t", a);
                mod.send(q, "map.idx", o);
                if (page == pages - 1) {
                    PState ps = states.get(id);
                    if (ps != null) ps.ready = true;
                }
            }, 2L + page * 2L);
        }
    }

    // ------------------------------------------------------------------ cereri client

    /** Upload: {@code t:[{x,z,q,d(base64 zlib)}...]}. */
    void put(Player p, JsonObject j) {
        if (!enabled || !p.hasPermission("flowcity.gps.upload")) return;
        if (p.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        PState s = st(p);
        long now = System.currentTimeMillis();
        if (now - s.lastPut < putGapMs - 50L) return;
        JsonElement te = j.get("t");
        if (te == null || !te.isJsonArray()) return;
        JsonArray arr = te.getAsJsonArray();
        if (arr.size() == 0 || arr.size() > PUT_MAX_TILES) return;
        if (now - s.minuteStart > 60000L) {
            s.minuteStart = now;
            s.minuteCount = 0;
        }
        if (s.minuteCount + arr.size() > perMinute) return;
        double px = p.getLocation().getX();
        double pz = p.getLocation().getZ();
        final List<int[]> meta = new ArrayList<>();
        final List<byte[]> data = new ArrayList<>();
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) return;
            JsonObject o = e.getAsJsonObject();
            int tx = (int) Math.max(-2000000, Math.min(2000000, o.has("x") ? o.get("x").getAsDouble() : 0));
            int tz = (int) Math.max(-2000000, Math.min(2000000, o.has("z") ? o.get("z").getAsDouble() : 0));
            int q = o.has("q") ? o.get("q").getAsInt() : 0;
            if (q != quality) return;
            // dala in raza hartii si aproape de jucator: nu se poate picta orice coordonata de la distanta
            double cx = tx * (double) TS + TS / 2d;
            double cz = tz * (double) TS + TS / 2d;
            if (Math.abs(cx) > radius || Math.abs(cz) > radius) continue;
            if (Math.abs(cx - px) > uploadRange + TS || Math.abs(cz - pz) > uploadRange + TS) continue;
            String d = o.has("d") ? o.get("d").getAsString() : "";
            if (d.isEmpty() || d.length() > PUT_MAX_B64) continue;
            byte[] z;
            try {
                z = Base64.getDecoder().decode(d);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            meta.add(new int[] {tx, tz, q});
            data.add(z);
        }
        if (meta.isEmpty()) return;
        s.lastPut = now;
        s.minuteCount += meta.size();
        final UUID id = p.getUniqueId();
        io.execute(() -> {
            final JsonArray ack = new JsonArray();
            final List<Long> changed = new ArrayList<>();
            for (int i = 0; i < meta.size(); i++) {
                int[] m = meta.get(i);
                MergeRes r;
                try {
                    r = merge(m[0], m[1], m[2], data.get(i));
                } catch (RuntimeException ex) {
                    log.warning("[GPS] Eroare la unirea dalei " + m[0] + "," + m[1] + ": " + ex);
                    continue;
                }
                if (r.err != null) continue;
                JsonArray t = new JsonArray();
                t.add(m[0]);
                t.add(m[1]);
                t.add(r.ver);
                t.add(r.prev);
                ack.add(t);
                if (r.changed) changed.add(key(m[0], m[1]));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player q = Bukkit.getPlayer(id);
                if (ack.size() > 0 && q != null && q.isOnline()) {
                    JsonObject o = new JsonObject();
                    o.add("t", ack);
                    mod.send(q, "map.ack", o);
                }
                for (long k : changed) {
                    UUID prev = pendingUpd.get(k);
                    pendingUpd.put(k, pendingUpd.containsKey(k) && !id.equals(prev) ? null : id);
                }
            });
        });
    }

    /** Cerere de dale: {@code k:[[tx,tz]...]} (max 32), raspuns in {@code map.tiles} (cu v=0 pentru dale inexistente). */
    void get(Player p, JsonObject j) {
        if (!enabled) return;
        PState s = st(p);
        long now = System.currentTimeMillis();
        if (now - s.lastGet < 100L) return;
        if (now - s.bytesWindow > 1000L) {
            s.bytesWindow = now;
            s.bytesCount = 0;
        }
        if (s.bytesCount > 900000L) return; // ~0.9 MB/s per jucator
        JsonElement ke = j.get("k");
        if (ke == null || !ke.isJsonArray()) return;
        final List<int[]> keys = new ArrayList<>();
        for (JsonElement e : ke.getAsJsonArray()) {
            if (keys.size() >= GET_MAX_KEYS) break;
            if (!e.isJsonArray() || e.getAsJsonArray().size() < 2) continue;
            int tx = e.getAsJsonArray().get(0).getAsInt();
            int tz = e.getAsJsonArray().get(1).getAsInt();
            if (Math.abs(tx) > 2000000 || Math.abs(tz) > 2000000) continue;
            keys.add(new int[] {tx, tz});
        }
        if (keys.isEmpty()) return;
        s.lastGet = now;
        final UUID id = p.getUniqueId();
        io.execute(() -> {
            final List<JsonObject> out = new ArrayList<>();
            for (int[] k : keys) {
                JsonObject o = new JsonObject();
                o.addProperty("x", k[0]);
                o.addProperty("z", k[1]);
                Rec r = null;
                try {
                    if (vers.containsKey(key(k[0], k[1]))) r = read(k[0], k[1]);
                } catch (RuntimeException ignored) {
                    // dala ilizibila: raspundem ca nu exista
                }
                if (r == null) {
                    o.addProperty("v", 0);
                } else {
                    o.addProperty("v", r.ver);
                    o.addProperty("q", r.q);
                    o.addProperty("d", Base64.getEncoder().encodeToString(r.z));
                }
                out.add(o);
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player q = Bukkit.getPlayer(id);
                if (q == null || !q.isOnline()) return;
                PState ps = states.get(id);
                JsonArray a = new JsonArray();
                int size = 0;
                for (JsonObject o : out) {
                    a.add(o);
                    size += o.has("d") ? o.get("d").getAsString().length() + 40 : 40;
                    if (size > 380000) {
                        JsonObject m = new JsonObject();
                        m.add("t", a);
                        mod.send(q, "map.tiles", m);
                        if (ps != null) ps.bytesCount += size;
                        a = new JsonArray();
                        size = 0;
                    }
                }
                if (a.size() > 0) {
                    JsonObject m = new JsonObject();
                    m.add("t", a);
                    mod.send(q, "map.tiles", m);
                    if (ps != null) ps.bytesCount += size;
                }
            });
        });
    }

    /** Admin: sterge/reimprospateaza o zona (dreptunghi in blocuri). {@code mode} = "delete" | "refresh". */
    void adminZone(Player p, JsonObject j) {
        long now = System.currentTimeMillis();
        if (now - lastAdminOp < 800L) {
            mod.mapResult(p, false, "Prea repede, mai încearcă o dată.");
            return;
        }
        lastAdminOp = now;
        if (!enabled) {
            mod.mapResult(p, false, "Harta partajată e oprită în gps.yml.");
            return;
        }
        double a = j.has("x1") ? j.get("x1").getAsDouble() : Double.NaN;
        double b = j.has("z1") ? j.get("z1").getAsDouble() : Double.NaN;
        double c = j.has("x2") ? j.get("x2").getAsDouble() : Double.NaN;
        double d = j.has("z2") ? j.get("z2").getAsDouble() : Double.NaN;
        if (Double.isNaN(a + b + c + d) || Double.isInfinite(a + b + c + d)) {
            mod.mapResult(p, false, "Zonă invalidă.");
            return;
        }
        final int x0 = (int) Math.floor(Math.min(a, c));
        final int x1 = (int) Math.ceil(Math.max(a, c));
        final int z0 = (int) Math.floor(Math.min(b, d));
        final int z1 = (int) Math.ceil(Math.max(b, d));
        if (x1 - x0 > adminMaxSide || z1 - z0 > adminMaxSide || Math.abs(x0) > 30000000 || Math.abs(x1) > 30000000 || Math.abs(z0) > 30000000 || Math.abs(z1) > 30000000) {
            mod.mapResult(p, false, "Zonă prea mare (max " + adminMaxSide + " blocuri pe latură).");
            return;
        }
        final boolean refresh = "refresh".equals(j.has("mode") ? j.get("mode").getAsString() : "delete");
        final int tx0 = Math.floorDiv(x0, TS);
        final int tx1 = Math.floorDiv(x1, TS);
        final int tz0 = Math.floorDiv(z0, TS);
        final int tz1 = Math.floorDiv(z1, TS);
        final UUID id = p.getUniqueId();
        final String name = p.getName();
        io.execute(() -> {
            int n = 0;
            for (int tx = tx0; tx <= tx1; tx++) {
                for (int tz = tz0; tz <= tz1; tz++) {
                    long k = key(tx, tz);
                    if (vers.remove(k) == null) continue;
                    cache.remove(k);
                    file(tx, tz).delete();
                    n++;
                }
            }
            final int count = n;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (int tx = tx0; tx <= tx1; tx++) for (int tz = tz0; tz <= tz1; tz++) pendingUpd.remove(key(tx, tz));
                JsonObject o = new JsonObject();
                o.addProperty("x1", tx0 * TS);
                o.addProperty("z1", tz0 * TS);
                o.addProperty("x2", (tx1 + 1) * TS);
                o.addProperty("z2", (tz1 + 1) * TS);
                o.addProperty("refresh", refresh);
                mod.broadcast("map.del", o);
                log.info("[GPS] " + name + " a " + (refresh ? "reîmprospătat" : "șters") + " zona harții [" + x0 + "," + z0 + " .. " + x1 + "," + z1 + "]: " + count + " dale.");
                Player q = Bukkit.getPlayer(id);
                if (q != null) mod.mapResult(q, true, (refresh ? "Zonă reîmprospătată: " : "Zonă ștearsă: ") + count + " dale" + (refresh ? " (se refac când jucătorii trec pe acolo)." : "."));
            });
        });
    }

    // ------------------------------------------------------------------ anunturi de versiuni noi

    /** La 1 secunda: trimite versiunile noi catre jucatorii care au primit indexul (uploader-ul primeste doar ack). */
    private void flushUpdates() {
        if (pendingUpd.isEmpty()) return;
        List<Map.Entry<Long, UUID>> list = new ArrayList<>(pendingUpd.entrySet());
        pendingUpd.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PState s = states.get(p.getUniqueId());
            if (s == null || !s.ready) continue;
            JsonArray a = new JsonArray();
            for (Map.Entry<Long, UUID> e : list) {
                if (p.getUniqueId().equals(e.getValue())) continue;
                Integer v = vers.get(e.getKey());
                if (v == null) continue;
                JsonArray t = new JsonArray();
                t.add(kx(e.getKey()));
                t.add(kz(e.getKey()));
                t.add(v);
                a.add(t);
                if (a.size() >= 1500) break;
            }
            if (a.size() == 0) continue;
            JsonObject o = new JsonObject();
            o.add("t", a);
            mod.send(p, "map.upd", o);
        }
    }

    int tileCount() {
        return vers.size();
    }

    boolean enabled() {
        return enabled;
    }
}
