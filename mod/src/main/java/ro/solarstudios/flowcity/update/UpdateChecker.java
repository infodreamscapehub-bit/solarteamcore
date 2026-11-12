package ro.solarstudios.flowcity.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Verificare si descarcare de update pentru modul client. Citeste release-ul "latest" din GitHub, compara numarul
 * de build cu cel din jar-ul curent si poate descarca noul jar in <joc>/flowcity-update/ (nu il instaleaza singur).
 * Adresa de descarcare e fixata aici, nu vine niciodata de la server.
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    private static final String REPO = "infodreamscapehub-bit/solarteamcore";
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/latest/";
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    public static volatile int latestBuild;
    public static volatile String modName = "";
    public static volatile long modSize;
    public static volatile List<String> notes = new ArrayList<>();
    public static volatile boolean checking;
    public static volatile boolean checkedOk;
    public static volatile long checkedAt;

    public static volatile boolean downloading;
    public static volatile float progress;
    public static volatile boolean downloaded;
    public static volatile String status = "";
    private static boolean toastShown;

    private static int mine = -1;

    /** Numarul de build al acestui jar (0 = build de dezvoltare, fara numar). */
    public static int currentBuild() {
        if (mine >= 0) return mine;
        mine = 0;
        try (InputStream in = UpdateChecker.class.getResourceAsStream("/build.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                mine = Integer.parseInt(p.getProperty("build", "0").trim());
            }
        } catch (Exception ignored) {
            // ramane 0
        }
        return mine;
    }

    public static boolean updateAvailable() {
        return currentBuild() > 0 && latestBuild > currentBuild();
    }

    /** Pornește o verificare asincrona (cel mult una la 10 minute, daca merge). */
    public static void checkAsync(boolean force) {
        if (checking) return;
        if (!force && checkedOk && System.currentTimeMillis() - checkedAt < 600_000L) return;
        checking = true;
        Thread t = new Thread(UpdateChecker::check, "FlowCity-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void check() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/tags/latest").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "FlowCityCore-updater");
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            JsonObject r;
            try (InputStreamReader rd = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                r = new JsonParser().parse(rd).getAsJsonObject();
            }
            String body = r.has("body") && !r.get("body").isJsonNull() ? r.get("body").getAsString() : "";
            int build = 0;
            List<String> lines = new ArrayList<>();
            for (String l : body.split("\\r?\\n")) {
                String t = l.trim();
                if (t.startsWith("build=")) {
                    try {
                        build = Integer.parseInt(t.substring(6).trim());
                    } catch (NumberFormatException ignored) {
                        // fara numar
                    }
                } else if (!t.isEmpty()) {
                    lines.add(t.startsWith("- ") ? t.substring(2) : t);
                }
            }
            String name = "";
            long size = 0;
            if (r.has("assets")) {
                for (JsonElement e : r.getAsJsonArray("assets")) {
                    JsonObject a = e.getAsJsonObject();
                    String n = a.get("name").getAsString();
                    if (n.startsWith("FlowCityCore") && n.endsWith(".jar")) {
                        name = n;
                        size = a.get("size").getAsLong();
                    }
                }
            }
            if (build > 0) {
                latestBuild = build;
                notes = lines;
                modName = name;
                modSize = size;
                checkedOk = true;
            }
            checkedAt = System.currentTimeMillis();
        } catch (Exception e) {
            checkedOk = false;
            checkedAt = System.currentTimeMillis();
        } finally {
            checking = false;
        }
    }

    /** Folosit cand serverul trimite informatii de update (acelasi continut ca verificarea directa). */
    public static void fromServer(int build, String changelog, long size) {
        if (build <= 0) return;
        latestBuild = build;
        if (changelog != null && !changelog.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (String s : changelog.split("\n")) {
                String t = s.trim();
                if (t.startsWith("- ")) t = t.substring(2);
                if (!t.isEmpty()) l.add(t);
            }
            notes = l;
        }
        if (size > 0) modSize = size;
        checkedOk = true;
    }

    public static boolean toastOnce() {
        if (toastShown) return false;
        toastShown = true;
        return true;
    }

    public static File updateDir() {
        return new File(Minecraft.getInstance().gameDirectory, "flowcity-update");
    }

    public static File modsDir() {
        return new File(Minecraft.getInstance().gameDirectory, "mods");
    }

    /** Descarca noul jar in flowcity-update/ pe un thread separat. */
    public static void download() {
        if (downloading || modName.isEmpty()) return;
        downloading = true;
        downloaded = false;
        progress = 0f;
        status = "Se descarca...";
        final String name = modName;
        final long expected = modSize;
        Thread t = new Thread(() -> {
            File dir = updateDir();
            File tmp = new File(dir, name + ".part");
            try {
                if (!name.matches("[A-Za-z0-9._-]+\\.jar")) throw new IllegalStateException("nume invalid");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("nu pot crea folderul");
                HttpURLConnection c = (HttpURLConnection) new URL(DOWNLOAD_PREFIX + name).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "FlowCityCore-updater");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : expected;
                long got = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        got += n;
                        if (got > MAX_BYTES) throw new IllegalStateException("fisier prea mare");
                        out.write(buf, 0, n);
                        if (total > 0) progress = Math.min(1f, got / (float) total);
                    }
                }
                byte[] head = new byte[2];
                try (InputStream in = Files.newInputStream(tmp.toPath())) {
                    if (in.read(head) != 2 || head[0] != 'P' || head[1] != 'K') throw new IllegalStateException("fisier invalid");
                }
                Files.move(tmp.toPath(), new File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                progress = 1f;
                downloaded = true;
                status = "Descarcat!";
            } catch (Exception e) {
                tmp.delete();
                status = "Eroare: " + e.getMessage();
            } finally {
                downloading = false;
            }
        }, "FlowCity-update-download");
        t.setDaemon(true);
        t.start();
    }
}
