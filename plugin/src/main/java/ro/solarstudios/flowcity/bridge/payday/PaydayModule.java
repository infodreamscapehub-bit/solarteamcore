package ro.solarstudios.flowcity.bridge.payday;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** PayDay: salariu periodic pe timp de joc activ (fara AFK), in functie de permisiunea flowcity.payday.<grup>. */
public final class PaydayModule extends JsonModule {
    private YamlConfiguration cfg;
    private final Map<UUID, Integer> minutes = new HashMap<>();
    private final Map<UUID, Integer> idle = new HashMap<>();
    private final Map<UUID, String> lastPos = new HashMap<>();

    @Override
    public String id() {
        return "payday";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "payday");
        if (!cfg.getBoolean("enabled", true)) return;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 60, 20L * 60);
        Commands.register(plugin, "payday", null, (s, c, l, a) -> {
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0]) && s.hasPermission("flowcity.payday.admin")) {
                cfg = ModConfig.load(plugin, "payday");
                s.sendMessage("§aPayDay reincarcat.");
                return true;
            }
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            int interval = cfg.getInt("interval-minutes", 60);
            int left = Math.max(0, interval - minutes.getOrDefault(p.getUniqueId(), 0));
            p.sendMessage("§9[PayDay] §fSalariu: §a" + salary(p) + " lei §7| urmatorul peste §f" + left + " min");
            return true;
        }, null, "salariu");
    }

    private int salary(Player p) {
        int best = cfg.getInt("base-salary", 200);
        ConfigurationSection g = cfg.getConfigurationSection("groups");
        if (g != null) {
            for (String k : g.getKeys(false)) {
                if (p.hasPermission("flowcity.payday." + k)) best = Math.max(best, g.getInt(k));
            }
        }
        return best;
    }

    private void tick() {
        int interval = Math.max(1, cfg.getInt("interval-minutes", 60));
        int afk = cfg.getInt("afk-minutes", 5);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            org.bukkit.Location l = p.getLocation();
            String pos = l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + "," + (int) (l.getYaw() / 15);
            if (pos.equals(lastPos.get(u))) idle.merge(u, 1, Integer::sum);
            else idle.put(u, 0);
            lastPos.put(u, pos);
            if (idle.getOrDefault(u, 0) >= afk) continue;
            int m = minutes.merge(u, 1, Integer::sum);
            if (m >= interval) {
                minutes.put(u, 0);
                pay(p);
            }
        }
        minutes.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private void pay(Player p) {
        int gross = salary(p);
        int tax = (int) Math.round(gross * cfg.getDouble("tax-percent", 5) / 100.0);
        int net = gross - tax;
        if (plugin.money() == null || !plugin.money().deposit(p.getUniqueId(), net)) {
            p.sendMessage("§cPayDay: nu am putut depune salariul.");
            return;
        }
        p.sendMessage("§9§l[PayDay] §fAi primit salariul: §a+" + net + " lei §7(brut " + gross + ", impozit " + tax + ")");
    }
}
