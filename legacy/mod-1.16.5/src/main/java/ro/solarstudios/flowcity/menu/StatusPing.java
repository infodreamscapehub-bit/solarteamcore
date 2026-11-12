package ro.solarstudios.flowcity.menu;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.ServerPinger;

/** Status live al serverului (online, jucatori, ping) pentru meniul principal. Reimprospatat la ~15s. */
public final class StatusPing {
    private StatusPing() {}

    private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowCity-StatusPing");
        t.setDaemon(true);
        return t;
    });
    private static final ServerPinger PINGER = new ServerPinger();
    private static ServerData data;
    private static String address = "";
    private static long lastStart;
    private static volatile boolean running;
    private static volatile boolean failed;
    private static long answeredAt;

    public static boolean online;
    public static boolean known;
    public static int players = -1;
    public static int max = -1;
    public static long ping = -1;

    /** Apelat la fiecare tick de meniu. */
    public static void tick(String ip) {
        try {
            PINGER.pingPendingNetworks();
        } catch (RuntimeException ignored) {
            // conexiune inchisa
        }
        long now = System.currentTimeMillis();
        if (data != null) read(now);
        if (!running && now - lastStart > 15000) start(ip, now);
        if (running && now - lastStart > 8000) {
            running = false;
            if (answeredAt < lastStart) {
                online = false;
                known = true;
            }
        }
    }

    private static void start(String ip, long now) {
        lastStart = now;
        running = true;
        failed = false;
        address = ip;
        final ServerData sd = new ServerData("FlowCity", ip, false);
        sd.pingToServer = -1L;
        data = sd;
        POOL.execute(() -> {
            try {
                PINGER.ping(sd, () -> { });
            } catch (Exception e) {
                failed = true;
            }
        });
    }

    private static void read(long now) {
        ServerData sd = data;
        if (failed) {
            online = false;
            known = true;
            running = false;
            return;
        }
        if (sd.populationInfo != null) {
            Matcher m = COUNT.matcher(sd.populationInfo.getString().replaceAll("§.", ""));
            if (m.find()) {
                players = Integer.parseInt(m.group(1));
                max = Integer.parseInt(m.group(2));
                online = true;
                known = true;
            }
        }
        if (sd.pingToServer >= 0) {
            ping = sd.pingToServer;
            online = true;
            known = true;
            answeredAt = now;
            running = false;
        }
    }
}
