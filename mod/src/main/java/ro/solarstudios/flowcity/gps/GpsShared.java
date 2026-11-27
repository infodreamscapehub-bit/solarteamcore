package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ClientBridge;

/**
 * Sincronizarea hartii cu serverul (client): trimite dalele explorate (rate-limited, comprimate, cel mult 3 per mesaj, doar cele
 * schimbate si aflate aproape de jucator) si cere de la server doar dalele pe care le vede pe ecran/minimap si nu le are la zi.
 * Compresia/decompresia ruleaza pe un thread separat; pe thread-ul principal se aplica cel mult cateva dale pe tick.
 * Protocolul si validarile de pe server: vezi SharedMap in plugin.
 */
public final class GpsShared {
    private GpsShared() {}

    // limite primite de la server (map.cfg)
    public static boolean enabled;
    public static boolean upload;
    private static int quality = 1;
    private static int gapMs = 600;
    private static int perMin = 90;
    private static int range = 640;
    private static int maxPer = 3;

    private static final class Dec {
        int tx;
        int tz;
        int ver;
        int q;
        int[] px;
    }

    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowCity-GpsShared");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentLinkedQueue<Dec> decoded = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<JsonObject> outUp = new ConcurrentLinkedQueue<>();
    private static final Map<Long, Long> inflight = new HashMap<>();
    private static final long[] need = new long[24];
    private static volatile boolean compressing;
    private static long lastReq;
    private static long lastPut;
    private static long minStart;
    private static int minCount;
    private static int tickN;

    /** Rezultatul ultimei operatii de admin (sterge/reimprospateaza zona) pentru afisare in harta. */
    public static String adminMsg = "";
    public static boolean adminOk;
    public static long adminMsgAt;

    public static void reset() {
        enabled = false;
        upload = false;
        decoded.clear();
        outUp.clear();
        inflight.clear();
        compressing = false;
        GpsMap.syncReset();
    }

    // ------------------------------------------------------------------ mesaje de la server

    public static void onCfg(JsonObject d) {
        enabled = GpsClient.bool(d, "en", false);
        upload = GpsClient.bool(d, "up", false);
        int q = (int) GpsClient.num(d, "q", 1);
        quality = q == 2 || q == 4 ? q : 1;
        gapMs = (int) Math.max(100, Math.min(5000, GpsClient.num(d, "gap", 600)));
        perMin = (int) Math.max(6, Math.min(600, GpsClient.num(d, "perMin", 90)));
        range = (int) Math.max(64, Math.min(2048, GpsClient.num(d, "range", 640)));
        maxPer = (int) Math.max(1, Math.min(3, GpsClient.num(d, "max", 3)));
    }

    private static JsonArray arr(JsonObject d, String k) {
        JsonElement e = d.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static void onIdx(JsonObject d) {
        if (!enabled) return;
        if ((int) GpsClient.num(d, "page", 0) == 0) GpsMap.syncReset();
        for (JsonElement e : arr(d, "t")) {
            if (!e.isJsonArray() || e.getAsJsonArray().size() < 3) continue;
            JsonArray t = e.getAsJsonArray();
            GpsMap.serverVersion(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt());
        }
        if (GpsClient.bool(d, "last", false)) GpsMap.indexComplete();
    }

    public static void onUpd(JsonObject d) {
        for (JsonElement e : arr(d, "t")) {
            if (!e.isJsonArray() || e.getAsJsonArray().size() < 3) continue;
            JsonArray t = e.getAsJsonArray();
            GpsMap.serverVersion(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt());
        }
    }

    public static void onAck(JsonObject d) {
        for (JsonElement e : arr(d, "t")) {
            if (!e.isJsonArray() || e.getAsJsonArray().size() < 4) continue;
            JsonArray t = e.getAsJsonArray();
            GpsMap.ack(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt(), t.get(3).getAsInt());
        }
    }

    public static void onTiles(JsonObject d) {
        for (JsonElement e : arr(d, "t")) {
            if (!e.isJsonObject()) continue;
            final JsonObject o = e.getAsJsonObject();
            final int tx = (int) GpsClient.num(o, "x", 0);
            final int tz = (int) GpsClient.num(o, "z", 0);
            final int v = (int) GpsClient.num(o, "v", 0);
            inflight.remove(key(tx, tz));
            if (v <= 0) {
                GpsMap.serverAbsent(tx, tz);
                continue;
            }
            final int q = (int) GpsClient.num(o, "q", 1);
            final String b64 = GpsClient.str(o, "d", "");
            if ((q != 1 && q != 2 && q != 4) || b64.isEmpty()) continue;
            pool.execute(() -> {
                int[] px = inflate(b64, q);
                if (px == null) return;
                Dec dd = new Dec();
                dd.tx = tx;
                dd.tz = tz;
                dd.ver = v;
                dd.q = q;
                dd.px = px;
                decoded.add(dd);
            });
        }
    }

    public static void onDel(JsonObject d) {
        int x1 = (int) GpsClient.num(d, "x1", 0);
        int z1 = (int) GpsClient.num(d, "z1", 0);
        int x2 = (int) GpsClient.num(d, "x2", 0);
        int z2 = (int) GpsClient.num(d, "z2", 0);
        GpsMap.deleteRect(x1, z1, x2, z2);
    }

    public static void onRes(JsonObject d) {
        adminOk = GpsClient.bool(d, "ok", false);
        adminMsg = GpsClient.str(d, "msg", "");
        adminMsgAt = System.currentTimeMillis();
    }

    private static long key(int tx, int tz) {
        return ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
    }

    private static int[] inflate(String b64, int q) {
        int n = 64 / q;
        int expected = n * n * 4;
        Inflater inf = new Inflater();
        try {
            inf.setInput(Base64.getDecoder().decode(b64));
            byte[] out = new byte[expected];
            int got = 0;
            while (got < expected && !inf.finished()) {
                int r = inf.inflate(out, got, expected - got);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                got += r;
            }
            if (got != expected) return null;
            int[] px = new int[n * n];
            ByteBuffer.wrap(out).asIntBuffer().get(px);
            return px;
        } catch (IllegalArgumentException | DataFormatException e) {
            return null;
        } finally {
            inf.end();
        }
    }

    // ------------------------------------------------------------------ tick

    /** La fiecare tick de client: aplica dalele primite, cere ce lipseste din vedere si trimite ce s-a schimbat. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        tickN++;
        for (int i = 0; i < 4; i++) {
            Dec d = decoded.poll();
            if (d == null) break;
            GpsMap.applyServerTile(d.tx, d.tz, d.ver, d.q, d.px);
        }
        JsonObject up = outUp.poll();
        if (up != null) GpsClient.sendMap("map.put", up);
        if (!enabled || !ClientBridge.connected || !GpsMap.overworld()) return;
        long now = System.currentTimeMillis();
        if (tickN % 4 == 0) request(now);
        if (upload && tickN % 10 == 0) upload(mc, now);
    }

    private static void request(long now) {
        if (!GpsMap.indexReady() || now - lastReq < 200L) return;
        for (Iterator<Map.Entry<Long, Long>> it = inflight.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > 8000L) it.remove();
        }
        if (inflight.size() > 24) return;
        int n = GpsMap.collectNeeded(need, need.length, inflight.keySet());
        if (n <= 0) return;
        lastReq = now;
        JsonArray k = new JsonArray();
        for (int i = 0; i < n; i++) {
            JsonArray t = new JsonArray();
            t.add(GpsMap.keyX(need[i]));
            t.add(GpsMap.keyZ(need[i]));
            k.add(t);
            inflight.put(need[i], now);
        }
        JsonObject o = new JsonObject();
        o.add("k", k);
        GpsClient.sendMap("map.get", o);
    }

    private static void upload(Minecraft mc, long now) {
        if (compressing || now - lastPut < Math.max(gapMs, 700) || !outUp.isEmpty()) return;
        if (now - minStart > 60000L) {
            minStart = now;
            minCount = 0;
        }
        if (minCount + maxPer > perMin) return;
        final List<GpsMap.UpTile> list = GpsMap.gatherUploads(maxPer, mc.player.getX(), mc.player.getZ(), range);
        if (list.isEmpty()) return;
        lastPut = now;
        minCount += list.size();
        compressing = true;
        final int q = quality;
        pool.execute(() -> {
            try {
                JsonArray arr = new JsonArray();
                for (GpsMap.UpTile u : list) {
                    String b64 = pack(u.px, q);
                    if (b64 == null) continue;
                    JsonObject t = new JsonObject();
                    t.addProperty("x", u.tx);
                    t.addProperty("z", u.tz);
                    t.addProperty("q", q);
                    t.addProperty("d", b64);
                    arr.add(t);
                }
                if (arr.size() > 0) {
                    JsonObject o = new JsonObject();
                    o.add("t", arr);
                    outUp.add(o);
                }
            } finally {
                compressing = false;
            }
        });
    }

    /** ABGR 64x64 (alpha 0 = nu se trimite) -> ARGB (64/q)^2, zlib, base64; null daca e prea mare / goala. */
    private static String pack(int[] abgr, int q) {
        int n = 64 / q;
        ByteBuffer bb = ByteBuffer.allocate(n * n * 4);
        boolean any = false;
        for (int bz = 0; bz < n; bz++) {
            for (int bx = 0; bx < n; bx++) {
                int v = 0;
                for (int dz = 0; dz < q && v == 0; dz++) {
                    for (int dx = 0; dx < q; dx++) {
                        int p = abgr[(bz * q + dz) * 64 + bx * q + dx];
                        if ((p >>> 24) != 0) {
                            v = 0xFF000000 | ((p & 255) << 16) | (p & 0xFF00) | ((p >> 16) & 255);
                            break;
                        }
                    }
                }
                if (v != 0) any = true;
                bb.putInt(v);
            }
        }
        if (!any) return null;
        Deflater d = new Deflater(6);
        try {
            d.setInput(bb.array());
            d.finish();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
            String s = Base64.getEncoder().encodeToString(bo.toByteArray());
            return s.length() > 23000 ? null : s;
        } finally {
            d.end();
        }
    }

    /** Cerere admin: sterge sau reimprospateaza zona (blocuri). */
    public static void adminZone(boolean refresh, int x1, int z1, int x2, int z2) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", refresh ? "refresh" : "delete");
        o.addProperty("x1", x1);
        o.addProperty("z1", z1);
        o.addProperty("x2", x2);
        o.addProperty("z2", z2);
        GpsClient.sendMap("map.del", o);
    }

    /** Text scurt de stare pentru interfata (gol cand totul e la zi). */
    public static String status() {
        if (!enabled) return "";
        if (!GpsMap.indexReady()) return "Se conectează harta orașului…";
        int p = GpsMap.pendingNeeded();
        if (p > 0) return "Se sincronizează harta: " + p + " dale";
        return "";
    }
}
