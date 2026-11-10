package ro.solarstudios.flowcity.bridge.update;

import com.google.gson.JsonArray;
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
import java.util.Properties;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Sistem de update: verifica periodic release-ul "latest" din GitHub, anunta staff-ul si clientii cu mod,
 * iar /flowcity update download pune noul plugin in plugins/update (Bukkit il aplica la urmatoarea pornire).
 * Configurare in config.yml: update.enabled, update.repo, update.check-minutes, update.auto-download.
 */
public final class UpdateModule extends JsonModule {
    private static final long MAX_BYTES = 40L * 1024 * 1024;

    private int current;
    private volatile int latestBuild;
    private volatile String notes = "";
    private volatile String pluginName = "";
    private volatile String pluginUrl = "";
    private volatile long pluginSize;
    private volatile String modName = "";
    private volatile long modSize;
    private volatile boolean announced;
    private volatile boolean downloaded;

    @Override
    public String id() {
        return "update";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.update", "Vezi si descarca update-uri FlowCity", PermissionDefault.OP);
        current = readBuild();
        Commands.register(plugin, "flowcity", "flowcity.update", (s, c, l, a) -> {
            command(s, a);
            return true;
        }, (s, c, l, a) -> a.length == 1
            ? Commands.filter(java.util.Arrays.asList("update", "check", "download"), a[0])
            : java.util.Collections.<String>emptyList(), "fc");
        if (!plugin.getConfig().getBoolean("update.enabled", true)) return;
        long minutes = Math.max(5, plugin.getConfig().getLong("update.check-minutes", 30));
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L * 20, 20L * 60 * minutes);
    }

    @Override
    public void onClientReady(Player p) {
        if (latestBuild > 0) send(p, "info", info());
        if (latestBuild > current && current > 0 && p.hasPermission("flowcity.update")) {
            p.sendMessage("§b[FlowCity] §fUpdate disponibil: build §a#" + latestBuild + " §7(ai #" + current + "). §f/flowcity update");
        }
    }

    private JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("build", latestBuild);
        o.addProperty("serverBuild", current);
        o.addProperty("notes", notes);
        o.addProperty("modSize", modSize);
        o.addProperty("pluginSize", pluginSize);
        return o;
    }

    private int readBuild() {
        try (InputStream in = plugin.getResource("build.properties")) {
            if (in == null) return 0;
            Properties p = new Properties();
            p.load(in);
            return Integer.parseInt(p.getProperty("build", "0").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ verificare

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "FlowCityBridge-updater");
        return c;
    }

    /** Ruleaza pe thread asincron. */
    public void check() {
        String repo = plugin.getConfig().getString("update.repo", "infodreamscapehub-bit/solarteamcore");
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) return;
        try {
            HttpURLConnection c = open("https://api.github.com/repos/" + repo + "/releases/tags/latest");
            if (c.getResponseCode() != 200) return;
            JsonObject r;
            try (InputStreamReader rd = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
                r = new JsonParser().parse(rd).getAsJsonObject();
            }
            String body = r.has("body") && !r.get("body").isJsonNull() ? r.get("body").getAsString() : "";
            int build = 0;
            StringBuilder changes = new StringBuilder();
            for (String line : body.split("\\r?\\n")) {
                if (line.startsWith("build=")) {
                    try {
                        build = Integer.parseInt(line.substring(6).trim());
                    } catch (NumberFormatException ignored) {
                        // lipsa numarului de build
                    }
                } else if (!line.trim().isEmpty()) {
                    if (changes.length() > 0) changes.append('\n');
                    changes.append(line.trim());
                }
            }
            String pName = "";
            String pUrl = "";
            long pSize = 0;
            String mName = "";
            long mSize = 0;
            if (r.has("assets")) {
                for (JsonElement e : r.getAsJsonArray("assets")) {
                    JsonObject a = e.getAsJsonObject();
                    String n = a.get("name").getAsString();
                    if (n.startsWith("FlowCityBridge")) {
                        pName = n;
                        pUrl = a.get("browser_download_url").getAsString();
                        pSize = a.get("size").getAsLong();
                    } else if (n.startsWith("FlowCityCore")) {
                        mName = n;
                        mSize = a.get("size").getAsLong();
                    }
                }
            }
            if (build <= 0) return;
            final int newBuild = build;
            boolean fresh = newBuild != latestBuild;
            latestBuild = newBuild;
            notes = changes.toString();
            pluginName = pName;
            pluginUrl = pUrl;
            pluginSize = pSize;
            modName = mName;
            modSize = mSize;
            if (fresh) {
                downloaded = false;
                announced = false;
            }
            if (newBuild > current && current > 0 && !announced) {
                announced = true;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getLogger().info("Update disponibil: build #" + newBuild + " (rulezi #" + current + "). Foloseste /flowcity update.");
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission("flowcity.update")) {
                            p.sendMessage("§b[FlowCity] §fUpdate disponibil: build §a#" + build + "§7. §f/flowcity update");
                        }
                    }
                    if (plugin.getConfig().getBoolean("update.auto-download", false)) download(Bukkit.getConsoleSender());
                });
            }
            if (fresh) Bukkit.getScheduler().runTask(plugin, () -> broadcast("info", info()));
        } catch (Exception e) {
            plugin.getLogger().fine("Verificare update esuata: " + e);
        }
    }

    // ------------------------------------------------------------------ comenzi

    private void command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "update" : a[0].toLowerCase();
        switch (sub) {
            case "check":
                s.sendMessage("§7Verific...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    check();
                    Bukkit.getScheduler().runTask(plugin, () -> status(s));
                });
                break;
            case "download":
                download(s);
                break;
            default:
                status(s);
                if (s instanceof Player && plugin.channel().hasMod((Player) s) && latestBuild > 0) {
                    JsonObject o = new JsonObject();
                    send((Player) s, "open", o);
                }
                break;
        }
    }

    private void status(CommandSender s) {
        s.sendMessage("§bFlowCity §7- plugin build §f#" + (current == 0 ? "dev" : String.valueOf(current))
            + "§7, ultimul disponibil: §f#" + (latestBuild == 0 ? "?" : String.valueOf(latestBuild)));
        if (latestBuild > current && current > 0) {
            s.sendMessage("§aExista un update.§7 Schimbari:");
            for (String l : notes.split("\n")) s.sendMessage("§7 " + l);
            s.sendMessage("§f/flowcity download §7- pune noul plugin in plugins/update (se aplica la repornire).");
        } else {
            s.sendMessage("§aEsti la zi.");
        }
    }

    private void download(CommandSender s) {
        if (pluginUrl.isEmpty()) {
            s.sendMessage("§cNu am informatii despre update. Ruleaza /flowcity check.");
            return;
        }
        if (downloaded) {
            s.sendMessage("§eUpdate-ul e deja descarcat; reporneste serverul.");
            return;
        }
        final String url = pluginUrl;
        final String name = pluginName;
        s.sendMessage("§7Descarc " + name + "...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File dir = Bukkit.getUpdateFolderFile();
            File tmp = new File(dir, name + ".part");
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("nu pot crea " + dir);
                HttpURLConnection c = open(url);
                c.setRequestProperty("Accept", "application/octet-stream");
                if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
                long total = 0;
                try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) throw new IllegalStateException("fisier prea mare");
                        out.write(buf, 0, n);
                    }
                }
                byte[] head = new byte[2];
                try (InputStream in = Files.newInputStream(tmp.toPath())) {
                    if (in.read(head) != 2 || head[0] != 'P' || head[1] != 'K') throw new IllegalStateException("nu e un jar valid");
                }
                // Bukkit aplica din plugins/update fisierul cu acelasi nume ca pluginul instalat
                File target = new File(dir, jarName());
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                downloaded = true;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    s.sendMessage("§aDescarcat. Reporneste serverul ca sa aplici update-ul (" + (total() ) + ").");
                    plugin.getLogger().info("Update descarcat in plugins/update. Reporneste serverul.");
                });
            } catch (Exception e) {
                tmp.delete();
                Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage("§cDescarcarea a esuat: " + e.getMessage()));
            }
        });
    }

    /** Numele fisierului jar al pluginului instalat (Bukkit aplica din plugins/update fisierul cu acelasi nume). */
    private String jarName() {
        try {
            return new File(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).getName();
        } catch (Exception e) {
            return pluginName;
        }
    }

    private String total() {
        return pluginSize > 0 ? (pluginSize / 1024) + " KB" : "ok";
    }
}
