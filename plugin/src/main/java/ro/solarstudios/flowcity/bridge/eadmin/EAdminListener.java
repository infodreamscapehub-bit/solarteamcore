package ro.solarstudios.flowcity.bridge.eadmin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.net.BridgeChannel;

/** Evenimente Bukkit -> loguri eAdmin, aplicarea mute/ban, flag-uri automate. */
public final class EAdminListener implements Listener {
    private final FlowCityBridge plugin;
    private final EAdminService service;
    private final BridgeChannel channel;
    private final Map<UUID, Deque<Long>> commandTimes = new ConcurrentHashMap<>();
    private final Set<Material> creativeBlacklist = new HashSet<>();
    private final Set<String> mutedCommands = new HashSet<>();

    public EAdminListener(FlowCityBridge plugin, EAdminService service, BridgeChannel channel) {
        this.plugin = plugin;
        this.service = service;
        this.channel = channel;
        reloadConfig();
    }

    public void reloadConfig() {
        creativeBlacklist.clear();
        for (String s : plugin.getConfig().getStringList("eadmin.creative-blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) creativeBlacklist.add(m);
        }
        mutedCommands.clear();
        for (String s : plugin.getConfig().getStringList("eadmin.muted-blocked-commands")) {
            mutedCommands.add(s.toLowerCase(Locale.ROOT));
        }
    }

    private static String coords(Location l) {
        if (l == null) return "-";
        return l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private static String world(Location l) {
        return l == null || l.getWorld() == null ? "-" : l.getWorld().getName();
    }

    // ------------------------------------------------------------ login / sesiuni

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String reason = service.checkLogin(e.getUniqueId());
        if (reason != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        service.recordJoin(p);
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        service.logConnection(p.getUniqueId(), ip, "JOIN", coords(p.getLocation()), world(p.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        service.logConnection(p.getUniqueId(), ip, "QUIT", coords(p.getLocation()), world(p.getLocation()));
        service.recordQuit(p);
        ro.solarstudios.flowcity.bridge.module.Modules.quit(p);
        commandTimes.remove(p.getUniqueId());
        channel.forget(p.getUniqueId());
    }

    // ------------------------------------------------------------ chat / comenzi

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        long exp = service.muteExpiry(p.getUniqueId());
        if (exp > 0) {
            e.setCancelled(true);
            String left = exp == Long.MAX_VALUE ? "permanent" : "pana la " + EAdminService.formatDate(exp);
            p.sendMessage("§cEsti pe mute (" + left + ").");
            return;
        }
        service.logChat(p.getUniqueId(), e.getMessage());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();
        String label = msg.length() > 1 ? msg.substring(1).split(" ")[0].toLowerCase(Locale.ROOT) : "";
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (service.muteExpiry(p.getUniqueId()) > 0 && mutedCommands.contains(label)) {
            e.setCancelled(true);
            p.sendMessage("§cNu poti folosi aceasta comanda cat timp esti pe mute.");
            return;
        }
        service.logCommand(p.getUniqueId(), msg);
        checkCommandSpam(p);
    }

    private void checkCommandSpam(Player p) {
        int max = plugin.getConfig().getInt("eadmin.command-spam.max-commands", 8);
        long window = plugin.getConfig().getLong("eadmin.command-spam.window-seconds", 3) * 1000L;
        if (max <= 0) return;
        long now = System.currentTimeMillis();
        Deque<Long> q = commandTimes.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && now - q.peekFirst() > window) q.pollFirst();
            if (q.size() > max) {
                q.clear();
                service.flag(p.getUniqueId(), "LOW", "COMMAND_SPAM", p.getName() + " a trimis >" + max + " comenzi in " + (window / 1000) + "s");
            }
        }
    }

    // ------------------------------------------------------------ creativ

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        service.logCreative(p.getUniqueId(), "GAMEMODE", e.getNewGameMode().name(), 1, "de la " + p.getGameMode().name());
        if (e.getNewGameMode() == GameMode.CREATIVE) {
            service.flag(p.getUniqueId(), "LOW", "CREATIVE_ON", p.getName() + " a intrat in creativ");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        ItemStack item = e.getCursor();
        if (item == null || item.getType() == Material.AIR) return;
        Player p = (Player) e.getWhoClicked();
        service.logCreative(p.getUniqueId(), "SPAWN", item.getType().name(), item.getAmount(), "slot " + e.getSlot());
        if (creativeBlacklist.contains(item.getType())) {
            service.flag(p.getUniqueId(), "HIGH", "CREATIVE_BLACKLIST", p.getName() + " a luat " + item.getType().name() + " x" + item.getAmount());
        }
    }

    // ------------------------------------------------------------ containere

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        InventoryType top = e.getView().getTopInventory().getType();
        if (top == InventoryType.PLAYER || top == InventoryType.CRAFTING || top == InventoryType.CREATIVE) return;
        if (e.getClickedInventory() == null) return;
        boolean clickedTop = e.getClickedInventory().equals(e.getView().getTopInventory());
        InventoryAction a = e.getAction();
        String action;
        ItemStack item;
        switch (a) {
            case PLACE_ALL:
            case PLACE_ONE:
            case PLACE_SOME:
            case SWAP_WITH_CURSOR:
                if (!clickedTop) return;
                action = "PUT";
                item = e.getCursor();
                break;
            case PICKUP_ALL:
            case PICKUP_HALF:
            case PICKUP_ONE:
            case PICKUP_SOME:
                if (!clickedTop) return;
                action = "TAKE";
                item = e.getCurrentItem();
                break;
            case MOVE_TO_OTHER_INVENTORY:
                action = clickedTop ? "TAKE" : "PUT";
                item = e.getCurrentItem();
                break;
            default:
                return;
        }
        if (item == null || item.getType() == Material.AIR) return;
        Location loc = e.getView().getTopInventory().getLocation();
        Player p = (Player) e.getWhoClicked();
        int amount = a == InventoryAction.PICKUP_ONE || a == InventoryAction.PLACE_ONE ? 1 : item.getAmount();
        service.logInventory(p.getUniqueId(), action, item.getType().name(), amount, top.name(), world(loc) + " " + coords(loc));
    }
}
