package ro.solarstudios.flowcity.bridge.eadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * eAdmin+ (modul "admx"): actiuni rapide din stilul eAdmin EGK - FREEZE, SPEC, CASH, INV, tab Joburi,
 * verificare IP / conturi multiple si captura de ecran (SS) de la jucator.
 * Permisiuni (implicit OP): flowcity.eadmin.freeze/spectate/money/inv/inv.edit/ss, flowcity.eadmin.ip, flowcity.eadmin.jobs.
 */
public final class AdminPlusModule extends JsonModule implements Listener {
    private static final int MAX_CHUNKS = 64;
    private static final long SS_TTL_MS = 120_000L;

    private static final class Spec {
        final Location back;
        final GameMode mode;

        Spec(Location back, GameMode mode) {
            this.back = back;
            this.mode = mode;
        }
    }

    private static final class SsSession {
        final UUID staff;
        final long at = System.currentTimeMillis();
        int chunks;

        SsSession(UUID staff) {
            this.staff = staff;
        }
    }

    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Spec> specs = new HashMap<>();
    private final Map<UUID, SsSession> ss = new ConcurrentHashMap<>(); // cheie = jucatorul cerut

    @Override
    public String id() {
        return "admx";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.eadmin.freeze", "eAdmin: ingheata jucatori", PermissionDefault.OP);
        Commands.permission("flowcity.eadmin.spectate", "eAdmin: spectate", PermissionDefault.OP);
        Commands.permission("flowcity.eadmin.money", "eAdmin: modifica banii", PermissionDefault.OP);
        Commands.permission("flowcity.eadmin.inv", "eAdmin: vezi inventarul", PermissionDefault.OP);
        Commands.permission("flowcity.eadmin.inv.edit", "eAdmin: modifica inventarul altui jucator", PermissionDefault.OP);
        Commands.permission("flowcity.eadmin.ss", "eAdmin: cere captura de ecran", PermissionDefault.OP);
        Commands.permission("flowcity.eadmin.jobs", "eAdmin: vezi joburile", PermissionDefault.OP);
        Bukkit.getPluginManager().registerEvents(this, plugin);

        on("freeze", (p, j) -> freeze(p, uuid(j)));
        on("spec", (p, j) -> spec(p, uuid(j)));
        on("cash", this::cash);
        on("inv", (p, j) -> inv(p, uuid(j)));
        on("jobs", (p, j) -> jobs(p, uuid(j)));
        on("ips", (p, j) -> ips(p, uuid(j)));
        on("ss", (p, j) -> ssRequest(p, uuid(j)));
        on("ss_chunk", this::ssChunk);
        on("fzq", (p, j) -> {
            if (!p.hasPermission("flowcity.eadmin")) return;
            UUID u = uuid(j);
            if (u == null) return;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", u.toString());
            o.addProperty("on", frozen.contains(u));
            send(p, "fz", o);
        });
    }

    @Override
    public void onQuit(Player p) {
        specs.remove(p.getUniqueId());
        ss.remove(p.getUniqueId());
    }

    private static UUID uuid(JsonObject j) {
        try {
            return UUID.fromString(str(j, "uuid", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean need(Player p, String perm) {
        if (p.hasPermission("flowcity.eadmin") && p.hasPermission(perm)) return true;
        plugin.channel().toast(p, "Nu ai permisiunea (" + perm + ").", true);
        return false;
    }

    private void ok(Player p, String text) {
        plugin.channel().toast(p, text, false);
    }

    private void err(Player p, String text) {
        plugin.channel().toast(p, text, true);
    }

    // ------------------------------------------------------------------ FREEZE

    private void freeze(Player staff, UUID u) {
        if (u == null || !need(staff, "flowcity.eadmin.freeze")) return;
        Player t = Bukkit.getPlayer(u);
        if (t == null) {
            err(staff, "Jucatorul nu este online.");
            return;
        }
        if (t.hasPermission("flowcity.eadmin.immune") && !staff.isOp()) {
            err(staff, "Jucatorul este imun.");
            return;
        }
        boolean now;
        if (frozen.remove(u)) {
            now = false;
            t.sendMessage("§aAi fost dezghetat.");
        } else {
            frozen.add(u);
            now = true;
            t.sendTitle("§b§lINGHETAT", "§7Un membru staff te verifica. Nu parasi jocul.", 5, 80, 10);
            t.sendMessage("§bAi fost inghetat de staff pentru verificare. Nu parasi jocul.");
        }
        plugin.getLogger().info("eAdmin: " + staff.getName() + (now ? " a inghetat " : " a dezghetat ") + t.getName());
        plugin.service().logTransaction(u, now ? "ADMIN_FREEZE" : "ADMIN_UNFREEZE", 0, staff.getName(), "");
        JsonObject o = new JsonObject();
        o.addProperty("uuid", u.toString());
        o.addProperty("on", now);
        send(staff, "fz", o);
        ok(staff, t.getName() + (now ? " a fost inghetat." : " a fost dezghetat."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() || !frozen.contains(e.getPlayer().getUniqueId())) return;
        Location a = e.getFrom();
        Location b = e.getTo();
        if (b == null) return;
        if (a.getX() != b.getX() || a.getY() != b.getY() || a.getZ() != b.getZ()) {
            Location keep = a.clone();
            keep.setYaw(b.getYaw());
            keep.setPitch(b.getPitch());
            e.setTo(keep);
        }
    }

    // ------------------------------------------------------------------ SPEC

    private void spec(Player staff, UUID u) {
        if (!need(staff, "flowcity.eadmin.spectate")) return;
        Spec s = specs.remove(staff.getUniqueId());
        if (s != null) {
            staff.setGameMode(s.mode);
            staff.teleport(s.back);
            ok(staff, "Ai iesit din spectate.");
            return;
        }
        if (u == null) return;
        Player t = Bukkit.getPlayer(u);
        if (t == null) {
            err(staff, "Jucatorul nu este online.");
            return;
        }
        specs.put(staff.getUniqueId(), new Spec(staff.getLocation(), staff.getGameMode()));
        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleport(t.getLocation());
        plugin.getLogger().info("eAdmin: " + staff.getName() + " spectate " + t.getName());
        ok(staff, "Spectate " + t.getName() + " (apasa din nou SPEC ca sa te intorci).");
    }

    // ------------------------------------------------------------------ CASH

    private void cash(Player staff, JsonObject j) {
        if (!need(staff, "flowcity.eadmin.money")) return;
        UUID u = uuid(j);
        if (u == null) return;
        String mode = str(j, "mode", "set");
        double amount = Math.round(num(j, "amount", -1) * 100.0) / 100.0;
        if (amount < 0 || amount > 1_000_000_000d || Double.isNaN(amount)) {
            err(staff, "Suma invalida.");
            return;
        }
        double cur = plugin.money().balance(u);
        double target;
        switch (mode) {
            case "add": target = cur + amount; break;
            case "take": target = Math.max(0, cur - amount); break;
            default: target = amount; break;
        }
        double delta = Math.round((target - cur) * 100.0) / 100.0;
        boolean success = true;
        if (delta > 0) success = plugin.money().deposit(u, delta);
        else if (delta < 0) success = plugin.money().withdraw(u, -delta);
        if (!success) {
            err(staff, "Operatiunea a esuat (economie).");
            return;
        }
        plugin.service().logTransaction(u, "ADMIN_CASH_" + mode.toUpperCase(), delta, staff.getName(), "");
        plugin.getLogger().info("eAdmin: " + staff.getName() + " cash " + mode + " " + amount + " pentru " + u + " (acum " + target + ")");
        ok(staff, "Cash: $" + String.format(java.util.Locale.US, "%.2f", target));
    }

    // ------------------------------------------------------------------ INV

    private void inv(Player staff, UUID u) {
        if (u == null || !need(staff, "flowcity.eadmin.inv")) return;
        Player t = Bukkit.getPlayer(u);
        if (t == null) {
            err(staff, "Doar jucatorii online pot fi inspectati.");
            return;
        }
        staff.openInventory(t.getInventory());
        plugin.getLogger().info("eAdmin: " + staff.getName() + " a deschis inventarul lui " + t.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) e.getWhoClicked();
        if (isForeignInv(viewer) && !viewer.hasPermission("flowcity.eadmin.inv.edit")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) e.getWhoClicked();
        if (isForeignInv(viewer) && !viewer.hasPermission("flowcity.eadmin.inv.edit")) e.setCancelled(true);
    }

    private static boolean isForeignInv(Player viewer) {
        Object holder = viewer.getOpenInventory().getTopInventory().getHolder();
        return holder instanceof Player && !holder.equals(viewer);
    }

    // ------------------------------------------------------------------ JOBURI

    private void jobs(Player staff, UUID u) {
        if (u == null || !need(staff, "flowcity.eadmin.jobs")) return;
        plugin.db().async(c -> {
            JsonObject o = new JsonObject();
            JsonArray rows = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement("SELECT job, changed_at FROM hype_job_current WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        o.addProperty("current", rs.getString(1) == null ? "" : rs.getString(1));
                        o.addProperty("changedAt", rs.getLong(2));
                    }
                }
            } catch (java.sql.SQLException ignored) {
                // tabela hype lipseste daca modulul Hype e oprit
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT job, xp, earned FROM hype_job_xp WHERE uuid=? ORDER BY xp DESC")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject r = new JsonObject();
                        r.addProperty("job", rs.getString(1));
                        r.addProperty("xp", rs.getLong(2));
                        r.addProperty("earned", rs.getDouble(3));
                        rows.add(r);
                    }
                }
            } catch (java.sql.SQLException ignored) {
                // idem
            }
            o.addProperty("uuid", u.toString());
            o.add("rows", rows);
            return o;
        }).whenComplete((o, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err == null && o != null) send(staff, "jobs", o);
        }));
    }

    // ------------------------------------------------------------------ IP / conturi multiple

    private void ips(Player staff, UUID u) {
        if (u == null || !need(staff, "flowcity.eadmin.ip")) return;
        plugin.db().async(c -> {
            Set<String> mine = new LinkedHashSet<>();
            JsonArray history = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT ip_address, MAX(timestamp) FROM eadmin_connection_logs WHERE player_uuid=? AND ip_address<>'' GROUP BY ip_address ORDER BY 2 DESC LIMIT 12")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        mine.add(rs.getString(1));
                        JsonObject r = new JsonObject();
                        r.addProperty("ip", rs.getString(1));
                        r.addProperty("ts", rs.getLong(2));
                        history.add(r);
                    }
                }
            }
            JsonArray others = new JsonArray();
            Set<String> seen = new HashSet<>();
            for (String ip : mine) {
                try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT p.uuid, p.name FROM eadmin_connection_logs l JOIN eadmin_players p ON p.uuid=l.player_uuid "
                        + "WHERE l.ip_address=? AND l.player_uuid<>? LIMIT 20")) {
                    ps.setString(1, ip);
                    ps.setString(2, u.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (!seen.add(rs.getString(1))) continue;
                            JsonObject r = new JsonObject();
                            r.addProperty("uuid", rs.getString(1));
                            r.addProperty("name", rs.getString(2));
                            r.addProperty("ip", ip);
                            others.add(r);
                        }
                    }
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("uuid", u.toString());
            o.add("history", history);
            o.add("others", others);
            return o;
        }).whenComplete((o, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err == null && o != null) send(staff, "ips", o);
        }));
    }

    // ------------------------------------------------------------------ SS (captura de ecran)

    private void ssRequest(Player staff, UUID u) {
        if (u == null || !need(staff, "flowcity.eadmin.ss")) return;
        Player t = Bukkit.getPlayer(u);
        if (t == null) {
            err(staff, "Jucatorul nu este online.");
            return;
        }
        if (!plugin.channel().hasMod(t)) {
            err(staff, t.getName() + " nu are modul FlowCityCore.");
            return;
        }
        ss.put(u, new SsSession(staff.getUniqueId()));
        plugin.getLogger().info("eAdmin: " + staff.getName() + " a cerut captura de ecran de la " + t.getName());
        if (plugin.getConfig().getBoolean("eadmin.ss-notify", true)) {
            t.sendMessage("§eStaff-ul a cerut o captura de ecran pentru verificare.");
        }
        JsonObject o = new JsonObject();
        o.addProperty("by", staff.getName());
        send(t, "ss_req", o);
        ok(staff, "Cerere trimisa. Astept captura...");
    }

    private void ssChunk(Player from, JsonObject j) {
        SsSession s = ss.get(from.getUniqueId());
        if (s == null || System.currentTimeMillis() - s.at > SS_TTL_MS) {
            ss.remove(from.getUniqueId());
            return;
        }
        int n = integer(j, "n", 0);
        int i = integer(j, "i", -1);
        if (n < 1 || n > MAX_CHUNKS || i < 0 || i >= n || ++s.chunks > MAX_CHUNKS) {
            ss.remove(from.getUniqueId());
            return;
        }
        Player staff = Bukkit.getPlayer(s.staff);
        if (staff == null || !staff.hasPermission("flowcity.eadmin.ss")) {
            ss.remove(from.getUniqueId());
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("uuid", from.getUniqueId().toString());
        o.addProperty("name", from.getName());
        o.addProperty("i", i);
        o.addProperty("n", n);
        o.addProperty("d", str(j, "d", ""));
        send(staff, "ss_chunk", o);
        if (i == n - 1) {
            ss.remove(from.getUniqueId());
            ok(staff, "Captura primita de la " + from.getName() + ".");
        }
    }
}
