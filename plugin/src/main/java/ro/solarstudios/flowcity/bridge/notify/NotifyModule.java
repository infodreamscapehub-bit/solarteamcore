package ro.solarstudios.flowcity.bridge.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Notificari: toast animat pe ecran (cu modul) sau mesaj in chat (fara), plus istoric salvat per jucator.
 * API pentru alte module: {@code plugin.notifications().send(uuid, Type.MONEY, "Titlu", "Text")} (merge si pentru jucatori offline).
 * Canal "notify": client -> server: history, clear; server -> client: push, history, unread.
 */
public final class NotifyModule extends JsonModule implements Listener {
    public enum Type {
        INFO("§b"), OK("§a"), WARN("§e"), BAD("§c"), MONEY("§6"), JOB("§d"), NEWS("§9");

        final String color;

        Type(String color) {
            this.color = color;
        }
    }

    private Db db;
    private int maxKept = 50;

    @Override
    public String id() {
        return "notify";
    }

    @Override
    protected void onEnable() {
        db = plugin.db();
        maxKept = Math.max(10, plugin.getConfig().getInt("notify.max-kept", 50));
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_notifications (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', ts INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_notif_uuid ON core_notifications(uuid, id)");
                }
                return null;
            }).get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("Notificari: nu pot porni tabela: " + e);
            return;
        }
        on("history", (p, j) -> sendHistory(p, bool(j, "open", false)));
        on("clear", (p, j) -> clear(p.getUniqueId()));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
    }

    // ------------------------------------------------------------------ API

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Trimite (si salveaza) o notificare. Poate fi apelat pe thread-ul principal. */
    public void send(UUID u, Type type, String title, String text) {
        final String ti = clip(title, 40);
        final String tx = clip(text, 160);
        if (ti.isEmpty()) return;
        final long ts = System.currentTimeMillis();
        Player online = Bukkit.getPlayer(u);
        final boolean mod = online != null && plugin.channel().hasMod(online);
        final int read = online != null && !mod ? 1 : 0; // cine primeste in chat o vede deja
        final String key = u.toString();
        final int keep = maxKept;
        db.async(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_notifications(uuid, type, title, text, ts, read) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, type.name());
                ps.setString(3, ti);
                ps.setString(4, tx);
                ps.setLong(5, ts);
                ps.setInt(6, read);
                ps.executeUpdate();
            }
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                id = rs.next() ? rs.getLong(1) : 0;
            }
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM core_notifications WHERE uuid=? AND id NOT IN (SELECT id FROM core_notifications WHERE uuid=? ORDER BY id DESC LIMIT ?)")) {
                ps.setString(1, key);
                ps.setString(2, key);
                ps.setInt(3, keep);
                ps.executeUpdate();
            }
            int unread = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_notifications WHERE uuid=? AND read=0")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) unread = rs.getInt(1);
                }
            }
            return new long[] {id, unread};
        }).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || res == null) return;
            Player p = Bukkit.getPlayer(u);
            if (p == null) return;
            if (plugin.channel().hasMod(p)) {
                JsonObject o = new JsonObject();
                o.addProperty("id", res[0]);
                o.addProperty("t", type.name());
                o.addProperty("title", ti);
                o.addProperty("text", tx);
                o.addProperty("ts", ts);
                o.addProperty("unread", res[1]);
                send(p, "push", o);
            } else {
                p.sendMessage(type.color + "§l[!] §r§f" + ti + (tx.isEmpty() ? "" : " §7- " + tx));
            }
        }));
    }

    public void send(Player p, Type type, String title, String text) {
        send(p.getUniqueId(), type, title, text);
    }

    // ------------------------------------------------------------------ istoric

    private void sendHistory(Player p, boolean open) {
        final String key = p.getUniqueId().toString();
        final int keep = maxKept;
        db.async(c -> {
            JsonArray arr = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, type, title, text, ts, read FROM core_notifications WHERE uuid=? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, key);
                ps.setInt(2, keep);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("id", rs.getLong(1));
                        o.addProperty("t", rs.getString(2));
                        o.addProperty("title", rs.getString(3));
                        o.addProperty("text", rs.getString(4));
                        o.addProperty("ts", rs.getLong(5));
                        o.addProperty("read", rs.getInt(6) == 1);
                        arr.add(o);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_notifications SET read=1 WHERE uuid=? AND read=0")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return arr;
        }).whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || arr == null || !p.isOnline()) return;
            if (plugin.channel().hasMod(p)) {
                JsonObject o = new JsonObject();
                o.add("items", arr);
                o.addProperty("open", open);
                send(p, "history", o);
            } else {
                if (arr.size() == 0) p.sendMessage("§7Nu ai nicio notificare.");
                int n = 0;
                for (int i = 0; i < arr.size() && n < 8; i++, n++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    Type t = parse(o.get("t").getAsString());
                    p.sendMessage((t == null ? Type.INFO : t).color + "• §f" + o.get("title").getAsString()
                        + (o.get("text").getAsString().isEmpty() ? "" : " §7- " + o.get("text").getAsString()));
                }
            }
        }));
    }

    private void clear(UUID u) {
        final String key = u.toString();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_notifications WHERE uuid=?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Type parse(String s) {
        try {
            return Type.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ evenimente

    @Override
    public void onClientReady(Player p) {
        final String key = p.getUniqueId().toString();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_notifications WHERE uuid=? AND read=0")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).whenComplete((n, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || n == null || !p.isOnline()) return;
            JsonObject o = new JsonObject();
            o.addProperty("n", n);
            send(p, "unread", o);
        }));
    }

    /** Fara modul: dupa intrare le spunem cate notificari necitite are. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || plugin.channel().hasMod(p)) return;
            final String key = p.getUniqueId().toString();
            db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_notifications WHERE uuid=? AND read=0")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getInt(1) : 0;
                    }
                }
            }).whenComplete((n, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (err == null && n != null && n > 0 && p.isOnline()) {
                    p.sendMessage("§bAi §f" + n + " §bnotificari necitite. §7Scrie /notificari");
                }
            }));
        }, 100L);
    }

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.permission("flowcity.notify.admin", "Trimite notificari", PermissionDefault.OP);
        Commands.register(plugin, "notificari", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatorii pot folosi aceasta comanda.");
                return true;
            }
            Player p = (Player) s;
            if (a.length > 0 && (a[0].equalsIgnoreCase("sterge") || a[0].equalsIgnoreCase("clear"))) {
                clear(p.getUniqueId());
                p.sendMessage("§aNotificarile au fost sterse.");
                return true;
            }
            sendHistory(p, true);
            return true;
        }, (s, c, l, a) -> a.length == 1 ? Commands.filter(java.util.Arrays.asList("sterge"), a[0]) : new ArrayList<String>(), "notif");

        Commands.register(plugin, "notify", "flowcity.notify.admin", (s, c, l, a) -> {
            if (a.length < 3) {
                s.sendMessage("§7Folosire: /notify <jucator|*> <info|ok|warn|bad|money|job|news> <titlu> [| text]");
                return true;
            }
            Type t = parse(a[1]);
            if (t == null) {
                s.sendMessage("§cTip invalid. Optiuni: info, ok, warn, bad, money, job, news.");
                return true;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < a.length; i++) sb.append(i > 2 ? " " : "").append(a[i]);
            String all = sb.toString();
            int bar = all.indexOf('|');
            String title = (bar < 0 ? all : all.substring(0, bar)).trim();
            String text = bar < 0 ? "" : all.substring(bar + 1).trim();
            if (a[0].equals("*")) {
                for (Player p : Bukkit.getOnlinePlayers()) send(p, t, title, text);
                s.sendMessage("§aTrimis catre toti jucatorii online.");
            } else {
                UUID u = plugin.ids().resolveAny(a[0]);
                if (u == null) {
                    s.sendMessage("§cJucator negasit.");
                    return true;
                }
                send(u, t, title, text);
                s.sendMessage("§aNotificare trimisa.");
            }
            return true;
        }, (s, c, l, a) -> {
            if (a.length == 1) {
                List<String> n = new ArrayList<>();
                n.add("*");
                for (Player p : Bukkit.getOnlinePlayers()) n.add(p.getName());
                return Commands.filter(n, a[0]);
            }
            if (a.length == 2) return Commands.filter(java.util.Arrays.asList("info", "ok", "warn", "bad", "money", "job", "news"), a[1]);
            return new ArrayList<String>();
        });
    }
}
