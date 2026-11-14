package ro.solarstudios.flowcity.bridge.lpsetup;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** /lpsetup: ruleaza liniile din plugins/FlowCityBridge/lp-setup.txt (grupuri LuckPerms cu small caps + gradient) din consola. */
public final class LpSetupModule extends JsonModule {
    private boolean running;

    @Override
    public String id() {
        return "lpsetup";
    }

    @Override
    protected void onEnable() {
        File f = new File(plugin.getDataFolder(), "lp-setup.txt");
        if (!f.exists() && plugin.getResource("lp-setup.txt") != null) plugin.saveResource("lp-setup.txt", false);
        Commands.register(plugin, "lpsetup", "flowcity.lpsetup", (s, c, l, a) -> {
            run(s);
            return true;
        }, null);
    }

    private void run(CommandSender s) {
        if (running) {
            s.sendMessage("§cRuleaza deja.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            s.sendMessage("§cLuckPerms nu e instalat.");
            return;
        }
        List<String> cmds = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(new File(plugin.getDataFolder(), "lp-setup.txt").toPath(), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                cmds.add(t.startsWith("/") ? t.substring(1) : t);
            }
        } catch (Exception e) {
            s.sendMessage("§cNu pot citi lp-setup.txt: " + e.getMessage());
            return;
        }
        running = true;
        s.sendMessage("§9[LuckPerms] §fRulez " + cmds.size() + " comenzi...");
        final int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (i[0] >= cmds.size()) {
                task.cancel();
                running = false;
                s.sendMessage("§aGata! Ruleaza §f/lp sync§a si pune-ti grupul: §f/lp user <nume> parent set owner");
                return;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmds.get(i[0]++));
        }, 20L, 4L);
    }
}
