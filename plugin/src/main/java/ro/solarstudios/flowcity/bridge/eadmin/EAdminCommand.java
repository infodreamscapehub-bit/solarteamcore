package ro.solarstudios.flowcity.bridge.eadmin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminService.SType;
import ro.solarstudios.flowcity.bridge.net.BridgeChannel;

/** /eadmin - deschide panoul in joc sau ruleaza actiuni rapide din chat/consola. */
public final class EAdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUB = Arrays.asList("open", "info", "warn", "mute", "unmute", "kick", "ban", "unban", "note");

    private final FlowCityBridge plugin;
    private final EAdminService service;
    private final BridgeChannel channel;

    public EAdminCommand(FlowCityBridge plugin, EAdminService service, BridgeChannel channel) {
        this.plugin = plugin;
        this.service = service;
        this.channel = channel;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("open")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Foloseste: /eadmin <info|warn|mute|unmute|kick|ban|unban|note> <jucator> ...");
                return true;
            }
            Player p = (Player) sender;
            if (channel.hasMod(p)) {
                channel.open(p);
            } else {
                p.sendMessage("§eNu am detectat modul FlowCityCore in client. §7Instaleaza-l pentru panou, sau foloseste §f/eadmin info <jucator>§7.");
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!SUB.contains(sub)) {
            sender.sendMessage("§cSubcomanda necunoscuta. §7Foloseste: " + String.join(", ", SUB));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cSpecifica un jucator: /eadmin " + sub + " <jucator>");
            return true;
        }
        final String targetName = args[1];
        final String[] rest = Arrays.copyOfRange(args, 2, args.length);
        final String adminName = sender instanceof Player ? sender.getName() : "CONSOLA";

        if (!sub.equals("info") && !sub.equals("note") && !sender.hasPermission("flowcity.eadmin.sanction")) {
            sender.sendMessage("§cNu ai permisiunea flowcity.eadmin.sanction");
            return true;
        }
        if (sub.equals("note") && !sender.hasPermission("flowcity.eadmin.notes")) {
            sender.sendMessage("§cNu ai permisiunea flowcity.eadmin.notes");
            return true;
        }

        service.findUuid(targetName).whenComplete((uuid, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || uuid == null) {
                sender.sendMessage("§cJucatorul §f" + targetName + " §cnu a fost gasit.");
                return;
            }
            run(sender, sub, uuid, targetName, rest, adminName);
        }));
        return true;
    }

    private void run(CommandSender sender, String sub, UUID uuid, String name, String[] rest, String admin) {
        EAdminService.Result reply = (ok, msg) -> sender.sendMessage((ok ? "§a" : "§c") + msg);
        switch (sub) {
            case "info":
                service.summary(uuid, lines -> lines.forEach(sender::sendMessage));
                break;
            case "warn":
                service.applySanction(admin, uuid, name, SType.WARN, 0, String.join(" ", rest), reply);
                break;
            case "kick":
                service.applySanction(admin, uuid, name, SType.KICK, 0, String.join(" ", rest), reply);
                break;
            case "mute":
            case "ban": {
                int minutes = 0;
                int from = 0;
                if (rest.length > 0) {
                    int parsed = parseDuration(rest[0]);
                    if (parsed >= 0) {
                        minutes = parsed;
                        from = 1;
                    }
                }
                String reason = String.join(" ", Arrays.copyOfRange(rest, from, rest.length));
                service.applySanction(admin, uuid, name, sub.equals("mute") ? SType.MUTE : SType.BAN, minutes, reason, reply);
                break;
            }
            case "unmute":
                service.revokeActive(uuid, SType.MUTE, admin, reply::done);
                break;
            case "unban":
                service.revokeActive(uuid, SType.BAN, admin, reply::done);
                break;
            case "note":
                if (rest.length == 0) {
                    sender.sendMessage("§cScrie textul notei.");
                } else {
                    service.addNote(uuid, admin, String.join(" ", rest), () -> sender.sendMessage("§aNota adaugata."));
                }
                break;
            default:
                break;
        }
    }

    /** "30m", "2h", "7d", "perm" -> minute (0 = permanent); -1 daca nu este durata. */
    static int parseDuration(String s) {
        s = s.toLowerCase(Locale.ROOT);
        if (s.equals("perm") || s.equals("permanent") || s.equals("0")) return 0;
        if (s.length() < 2) return -1;
        char unit = s.charAt(s.length() - 1);
        int n;
        try {
            n = Integer.parseInt(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (n <= 0) return -1;
        long m;
        switch (unit) {
            case 'm':
                m = n;
                break;
            case 'h':
                m = n * 60L;
                break;
            case 'd':
            case 'z':
                m = n * 1440L;
                break;
            case 'w':
            case 's':
                m = n * 10080L;
                break;
            default:
                return -1;
        }
        return (int) Math.min(m, 5256000L); // max ~10 ani
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUB) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("mute") || args[0].equalsIgnoreCase("ban"))) {
            out.addAll(Arrays.asList("15m", "1h", "1d", "7d", "30d", "perm"));
        }
        return out;
    }
}
