package ro.solarstudios.flowcity.bridge.news;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * CNN (stiri): publicare, istoric, banner animat pe ecran (cu modul) sau anunt in chat (fara).
 * Comenzi: /cnn, /cnn <categorie> <titlu> [| text], /cnn list, /cnn delete <id>.
 * Permisiuni: flowcity.hype.cnn (publica), flowcity.hype.cnn.delete (sterge). Config: sectiunea "news" din hype.yml.
 * Canal "news": client -> server: list, publish, delete; server -> client: list, banner, open.
 */
public final class NewsModule extends JsonModule {
    private YamlConfiguration cfg;
    private Db db;
    private final Map<UUID, Long> lastPublish = new HashMap<>();

    @Override
    public String id() {
        return "news";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "hype");
        db = plugin.db();
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_news (id INTEGER PRIMARY KEY AUTOINCREMENT, cat TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '', author TEXT NOT NULL, ts INTEGER NOT NULL)");
                }
                return null;
            }).get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("CNN: nu pot porni tabela: " + e);
            return;
        }
        on("list", (p, j) -> sendList(p, false));
        on("publish", (p, j) -> publish(p, str(j, "cat", ""), str(j, "title", ""), str(j, "body", "")));
        on("delete", (p, j) -> delete(p, (long) num(j, "id", -1)));
        registerCommands();
    }

    private List<String> categories() {
        List<String> c = cfg.getStringList("news.categories");
        if (c.isEmpty()) {
            c = new ArrayList<>();
            c.add("breaking");
            c.add("politie");
            c.add("eveniment");
            c.add("guvern");
        }
        return c;
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replace("§", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ lista

    private void sendList(Player p, boolean open) {
        final int keep = Math.max(5, cfg.getInt("news.max-kept", 30));
        db.async(c -> {
            JsonArray arr = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, cat, title, body, author, ts FROM core_news ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, keep);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", rs.getLong(1));
                        o.addProperty("cat", rs.getString(2));
                        o.addProperty("title", rs.getString(3));
                        o.addProperty("body", rs.getString(4));
                        o.addProperty("author", rs.getString(5));
                        o.addProperty("ts", rs.getLong(6));
                        arr.add(o);
                    }
                }
            }
            return arr;
        }).whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || arr == null || !p.isOnline()) return;
            if (plugin.channel().hasMod(p)) {
                JsonObject o = new JsonObject();
                o.add("items", arr);
                o.addProperty("open", open);
                o.addProperty("canPublish", p.hasPermission("flowcity.hype.cnn"));
                o.addProperty("canDelete", p.hasPermission("flowcity.hype.cnn.delete"));
                JsonArray cats = new JsonArray();
                for (String cat : categories()) cats.add(cat);
                o.add("cats", cats);
                send(p, "list", o);
            } else {
                if (arr.size() == 0) p.sendMessage("§7Nicio stire momentan.");
                for (int i = 0; i < Math.min(5, arr.size()); i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    p.sendMessage("§c[" + o.get("cat").getAsString().toUpperCase(Locale.ROOT) + "] §f" + o.get("title").getAsString()
                        + " §8#" + o.get("id").getAsLong());
                }
            }
        }));
    }

    // ------------------------------------------------------------------ publicare / stergere

    private void publish(Player p, String catIn, String titleIn, String bodyIn) {
        if (!p.hasPermission("flowcity.hype.cnn")) {
            plugin.channel().toast(p, "Nu ai permisiunea de a publica stiri.", true);
            return;
        }
        final String cat = catIn.toLowerCase(Locale.ROOT);
        if (!categories().contains(cat)) {
            fail(p, "Categorie invalida. Optiuni: " + String.join(", ", categories()));
            return;
        }
        final String title = clean(titleIn, 80).replace('\n', ' ');
        final String body = clean(bodyIn, 600);
        if (title.length() < 3) {
            fail(p, "Titlul e prea scurt.");
            return;
        }
        long now = System.currentTimeMillis();
        long cd = cfg.getInt("news.publish-cooldown-seconds", 10) * 1000L;
        Long last = lastPublish.get(p.getUniqueId());
        if (!p.hasPermission("flowcity.hype.admin") && last != null && now - last < cd) {
            fail(p, "Asteapta " + ((cd - (now - last)) / 1000 + 1) + "s inainte sa publici din nou.");
            return;
        }
        lastPublish.put(p.getUniqueId(), now);
        final String author = p.getName();
        final int keep = Math.max(5, cfg.getInt("news.max-kept", 30));
        db.async(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_news(cat, title, body, author, ts) VALUES (?,?,?,?,?)")) {
                ps.setString(1, cat);
                ps.setString(2, title);
                ps.setString(3, body);
                ps.setString(4, author);
                ps.setLong(5, now);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                id = rs.next() ? rs.getLong(1) : 0;
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_news WHERE id NOT IN (SELECT id FROM core_news ORDER BY id DESC LIMIT ?)")) {
                ps.setInt(1, keep);
                ps.executeUpdate();
            }
            return id;
        }).whenComplete((id, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || id == null) {
                fail(p, "Eroare la salvarea stirii.");
                return;
            }
            announce(id, cat, title, body, author, now);
            if (plugin.channel().hasMod(p)) sendList(p, false);
        }));
    }

    private void fail(Player p, String msg) {
        if (plugin.channel().hasMod(p)) plugin.channel().toast(p, msg, true);
        p.sendMessage("§c" + msg);
    }

    private void announce(long id, String cat, String title, String body, String author, long ts) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("cat", cat);
        o.addProperty("title", title);
        o.addProperty("author", author);
        o.addProperty("ts", ts);
        o.addProperty("seconds", cfg.getInt("news.banner-seconds", 9));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.channel().hasMod(p)) {
                send(p, "banner", o);
            } else {
                p.sendMessage("");
                p.sendMessage("§c§l[CNN - " + cat.toUpperCase(Locale.ROOT) + "] §r§f" + title);
                if (!body.isEmpty()) p.sendMessage("§7" + (body.length() > 200 ? body.substring(0, 200) + "..." : body.replace('\n', ' ')));
                p.sendMessage("§8- " + author);
                p.sendMessage("");
            }
        }
        plugin.getLogger().info("[CNN/" + cat + "] " + title + " (" + author + ")");
    }

    private void delete(Player p, long id) {
        if (!p.hasPermission("flowcity.hype.cnn.delete")) {
            plugin.channel().toast(p, "Nu ai permisiunea de a sterge stiri.", true);
            return;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_news WHERE id=?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        }).whenComplete((n, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || n == null) return;
            String msg = n > 0 ? "Stirea #" + id + " a fost stearsa." : "Stirea #" + id + " nu exista.";
            if (plugin.channel().hasMod(p)) {
                plugin.channel().toast(p, msg, n == 0);
                sendList(p, false);
            }
            p.sendMessage((n > 0 ? "§a" : "§c") + msg);
        }));
    }

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.permission("flowcity.hype.cnn", "Publica stiri CNN", PermissionDefault.OP);
        Commands.permission("flowcity.hype.cnn.delete", "Sterge stiri CNN", PermissionDefault.OP);
        Commands.register(plugin, "cnn", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatorii pot folosi aceasta comanda.");
                return true;
            }
            Player p = (Player) s;
            if (a.length == 0 || a[0].equalsIgnoreCase("list")) {
                sendList(p, a.length == 0);
                return true;
            }
            if (a[0].equalsIgnoreCase("delete") || a[0].equalsIgnoreCase("sterge")) {
                if (a.length < 2) {
                    p.sendMessage("§7Folosire: /cnn delete <id>");
                    return true;
                }
                try {
                    delete(p, Long.parseLong(a[1].replace("#", "")));
                } catch (NumberFormatException e) {
                    p.sendMessage("§cID invalid.");
                }
                return true;
            }
            if (a.length < 2) {
                p.sendMessage("§7Folosire: /cnn <" + String.join("|", categories()) + "> <titlu> [| text]");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < a.length; i++) sb.append(i > 1 ? " " : "").append(a[i]);
            String all = sb.toString();
            int bar = all.indexOf('|');
            publish(p, a[0], bar < 0 ? all : all.substring(0, bar), bar < 0 ? "" : all.substring(bar + 1));
            return true;
        }, (s, c, l, a) -> a.length == 1
            ? Commands.filter(withExtras(), a[0]) : new ArrayList<String>(), "stiri");
    }

    private List<String> withExtras() {
        List<String> l = new ArrayList<>(categories());
        l.add("list");
        l.add("delete");
        return l;
    }
}
