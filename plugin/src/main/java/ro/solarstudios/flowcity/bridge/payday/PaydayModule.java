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
import ro.solarstudios.flowcity.bridge.hype.HypeModule;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * PayDay: salariul periodic, virat DIRECT in contul bancar (nu in cash).
 *
 * <p>Salariul de baza vine din permisiunea {@code flowcity.payday.<grup>} (cea mai mare se ia in calcul). Peste el se
 * adauga, daca sunt pornite in payday.yml: salariul gradului de politie configurat in MDT si un spor pentru nivelul
 * profesional din FJobs. Din brut se scade impozitul, iar netul intra in banca, se trece in istoricul bancar (tip
 * PAYDAY, vizibil la ATM si in aplicatia bancara) si se anunta printr-o notificare laterala.
 *
 * <p>Conditiile de eligibilitate (timp activ, fara AFK, salariu minim, permisiune obligatorie) sunt in payday.yml.
 * Jucatorii nu pot modifica nimic: comenzile de administrare cer {@code flowcity.payday.admin}.
 */
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
            if (a.length >= 1 && ("test".equalsIgnoreCase(a[0]) || "now".equalsIgnoreCase(a[0])) && s.hasPermission("flowcity.payday.admin")) {
                Player t = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player ? (Player) s : null);
                if (t == null) {
                    s.sendMessage("§cFolosire: /payday " + a[0] + " <jucator> (jucatorul trebuie sa fie online)");
                    return true;
                }
                if ("now".equalsIgnoreCase(a[0])) {
                    pay(t);
                    s.sendMessage("§aPayDay platit acum lui " + t.getName() + ".");
                } else {
                    int g = salary(t);
                    int tx = (int) Math.round(g * cfg.getDouble("tax-percent", 5) / 100.0);
                    notifyPaid(t, g - tx, g, tx, plugin.bank().balance(t.getUniqueId()), true);
                    s.sendMessage("§aNotificare PayDay de test trimisa lui " + t.getName() + " (fara bani).");
                }
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

    // ------------------------------------------------------------------ salariu

    /** Salariul brut: grupul cel mai bine platit, plus gradul de politie si nivelul profesional, daca sunt pornite. */
    private int salary(Player p) {
        int best = cfg.getInt("base-salary", 200);
        ConfigurationSection g = cfg.getConfigurationSection("groups");
        if (g != null) {
            for (String k : g.getKeys(false)) {
                if (p.hasPermission("flowcity.payday." + k)) best = Math.max(best, g.getInt(k));
            }
        }
        if (cfg.getBoolean("bonuses.use-mdt-rank", true) && MdtModule.instance != null) {
            best = Math.max(best, Math.max(0, MdtModule.instance.rankSalary(p)));
        }
        int perLevel = cfg.getInt("bonuses.per-job-level", 0);
        int maxLevel = cfg.getInt("bonuses.max-job-level", 20);
        if (perLevel > 0) {
            int lvl = Math.min(maxLevel, Math.max(0, jobLevel(p)));
            best += perLevel * lvl;
        }
        return Math.max(0, best);
    }

    /** Nivelul profesional din FJobs la jobul curent (0 daca nu are job sau modulul lipseste). */
    private int jobLevel(Player p) {
        try {
            ro.solarstudios.flowcity.bridge.fjobs.FJobsModule f = ro.solarstudios.flowcity.bridge.fjobs.FJobsModule.instance;
            return f == null ? 0 : f.currentJobLevel(p);
        } catch (RuntimeException | NoClassDefFoundError ex) {
            return 0;
        }
    }

    /** Conditiile din config: permisiune obligatorie si salariu minim. */
    private boolean eligible(Player p, int gross) {
        String perm = cfg.getString("require-permission", "");
        if (perm != null && !perm.isEmpty() && !p.hasPermission(perm)) return false;
        return gross >= cfg.getInt("min-salary", 1);
    }

    // ------------------------------------------------------------------ ceas

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

    // ------------------------------------------------------------------ plata

    private void pay(Player p) {
        int gross = salary(p);
        if (!eligible(p, gross)) return;
        int tax = (int) Math.round(gross * cfg.getDouble("tax-percent", 5) / 100.0);
        int net = gross - tax;
        if (net <= 0) return;
        UUID u = p.getUniqueId();
        if (plugin.bank() == null || !plugin.bank().deposit(u, net)) {
            p.sendMessage("§cPayDay: nu am putut vira salariul in cont.");
            return;
        }
        double balance = plugin.bank().balance(u);
        if (HypeModule.instance != null) HypeModule.instance.logTx(u, "PAYDAY", net, "Salariu");
        if (plugin.service() != null) plugin.service().logTransaction(u, "PAYDAY", net, "Salariu", "brut " + gross + ", impozit " + tax);
        notifyPaid(p, net, gross, tax, balance, false);
    }

    /** Notificarea laterala: suma mare, metoda, sursa si soldul nou. */
    private void notifyPaid(Player p, int net, int gross, int tax, double balance, boolean test) {
        String title = cfg.getString("messages.title", "FlowCity PayDay");
        String source = cfg.getString("messages.source", "Salariu");
        String method = cfg.getString("messages.method", "Transfer bancar");
        String text = (test ? "TEST " : "") + "+" + money(net) + " lei"
            + "|" + method + " · " + source
            + "|Sold nou: " + money(balance) + " lei"
            + (tax > 0 ? "|Brut " + money(gross) + " lei, impozit " + money(tax) + " lei" : "");
        if (plugin.notifications() != null) {
            plugin.notifications().send(p.getUniqueId(), NotifyModule.Type.PAYDAY, title, text, "payday", "bank");
        } else {
            p.sendMessage("§a§l[" + title + "] §r§f+" + money(net) + " lei in cont (sold " + money(balance) + ").");
        }
    }

    private static String money(double v) {
        long n = Math.round(v);
        String s = String.valueOf(Math.abs(n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + b;
    }
}
