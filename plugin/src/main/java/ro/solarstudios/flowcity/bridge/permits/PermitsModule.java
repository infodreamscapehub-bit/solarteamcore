package ro.solarstudios.flowcity.bridge.permits;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Permise (port-arma, vanatoare, licenta de afaceri...). Definitiile stau in permits.yml, ce are fiecare jucator in
 * permits-data.yml. Cand un permis se acorda se seteaza si permisiunea LuckPerms "flowcity.permit.&lt;id&gt;" (comanda din consola
 * din permits.yml). Se cumpara din magazinul "permise" (shop.yml, intrari {permit: port_arma}); articolele din alte magazine pot cere un
 * permis cu "requires: port_arma". Politistii (flowcity.politie.officer) dau si retrag permise cu /permis; jucatorul isi vede permisele cu /permise.
 */
public final class PermitsModule extends JsonModule {
    public static PermitsModule instance;

    public static final class Def {
        public String id;
        public String name;
        public String desc;
        public String issuer;
        public String icon;
        public double price;
    }

    private final Map<String, Def> defs = new LinkedHashMap<>();
    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;
    private boolean saveScheduled;
    private String prefix = "flowcity.permit.";

    @Override
    public String id() {
        return "permits";
    }

    @Override
    protected void onEnable() {
        instance = this;
        dataFile = new File(plugin.getDataFolder(), "permits-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        load();
        Commands.permission("flowcity.permits.admin", "Permise: reincarca configul si administreaza orice permis", PermissionDefault.OP);
        Commands.register(plugin, "permis", null, (s, c, l, a) -> command(s, a), (s, c, l, a) -> complete(s, a));
        Commands.register(plugin, "permise", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Comanda se foloseste in joc. Din consola: permis lista <jucator>");
                return true;
            }
            list(s, (Player) s);
            return true;
        }, null, "permisele");
    }

    @Override
    public void disable() {
        saveNow();
        instance = null;
    }

    private void load() {
        cfg = ModConfig.load(plugin, "permits");
        prefix = cfg.getString("permission-prefix", "flowcity.permit.");
        defs.clear();
        ConfigurationSection ps = cfg.getConfigurationSection("permits");
        if (ps == null) return;
        for (String key : ps.getKeys(false)) {
            ConfigurationSection sc = ps.getConfigurationSection(key);
            if (sc == null) continue;
            Def d = new Def();
            d.id = norm(key);
            d.name = sc.getString("name", key);
            d.desc = sc.getString("desc", "");
            d.issuer = sc.getString("issued-by", "Politia");
            d.icon = sc.getString("icon", "minecraft:writable_book");
            d.price = Math.max(0, sc.getDouble("price", 0));
            defs.put(d.id, d);
        }
    }

    // ------------------------------------------------------------------ API folosit de alte module (magazin)

    public static String norm(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public Def def(String id) {
        return defs.get(norm(id));
    }

    public Collection<Def> defs() {
        return defs.values();
    }

    public String nameOf(String id) {
        Def d = def(id);
        return d == null ? id : d.name;
    }

    public String issuerOf(String id) {
        Def d = def(id);
        return d == null ? "Politia" : d.issuer;
    }

    /** Are jucatorul permisul? (inregistrat aici SAU permisiunea LuckPerms setata de mana). */
    public boolean has(Player p, String id) {
        String k = norm(id);
        return data.contains(path(p.getUniqueId(), k)) || p.hasPermission(prefix + k);
    }

    /** Ids ale permiselor pe care le are jucatorul. */
    public Set<String> held(Player p) {
        Set<String> out = new LinkedHashSet<>();
        for (Def d : defs.values()) if (has(p, d.id)) out.add(d.id);
        return out;
    }

    private static String path(UUID u, String id) {
        return "players." + u + "." + id;
    }

    /** Acorda un permis (inregistrare + permisiune LuckPerms). Returneaza fals daca permisul nu exista. */
    public boolean grant(OfflinePlayer t, String id, String by) {
        Def d = def(id);
        if (d == null || t == null || t.getName() == null) return false;
        data.set(path(t.getUniqueId(), d.id), System.currentTimeMillis());
        data.set("names." + t.getUniqueId(), t.getName());
        if (by != null) data.set("by." + t.getUniqueId() + "." + d.id, by);
        scheduleSave();
        run(cfg.getString("grant-command", "lp user %player% permission set %perm% true"), t.getName(), d.id);
        if (t.isOnline() && t.getPlayer() != null) t.getPlayer().sendMessage("§a[Permise] §7Ai primit permisul: §f" + d.name + "§7.");
        return true;
    }

    /** Retrage un permis. Returneaza fals daca permisul nu exista. */
    public boolean revoke(OfflinePlayer t, String id) {
        Def d = def(id);
        if (d == null || t == null || t.getName() == null) return false;
        data.set(path(t.getUniqueId(), d.id), null);
        data.set("by." + t.getUniqueId() + "." + d.id, null);
        scheduleSave();
        run(cfg.getString("revoke-command", "lp user %player% permission unset %perm%"), t.getName(), d.id);
        if (t.isOnline() && t.getPlayer() != null) t.getPlayer().sendMessage("§c[Permise] §7Ti s-a retras permisul: §f" + d.name + "§7.");
        return true;
    }

    private void run(String cmd, String player, String id) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player).replace("%perm%", prefix + id));
    }

    // ------------------------------------------------------------------ comenzi

    private boolean isIssuer(CommandSender s) {
        if (!(s instanceof Player)) return true;
        return s.isOp() || s.hasPermission(cfg.getString("issue-permission", "flowcity.politie.officer"))
            || s.hasPermission("flowcity.politie") || s.hasPermission("flowcity.permits.admin");
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off.hasPlayedBefore() ? off : null;
    }

    private boolean command(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (sub.isEmpty()) {
            if (s instanceof Player) list(s, (Player) s);
            usage(s);
            return true;
        }
        if (sub.equals("reload")) {
            if (!(s instanceof Player) || s.hasPermission("flowcity.permits.admin")) {
                load();
                s.sendMessage("§aPermise reincarcate: " + defs.keySet());
            } else {
                s.sendMessage("§cNu ai permisiunea.");
            }
            return true;
        }
        if (sub.equals("lista")) {
            if (a.length < 2) {
                if (s instanceof Player) list(s, (Player) s);
                else s.sendMessage("Folosire: permis lista <jucator>");
                return true;
            }
            if (!isIssuer(s) && !(s instanceof Player && ((Player) s).getName().equalsIgnoreCase(a[1]))) {
                s.sendMessage("§cDoar politistii pot vedea permisele altui jucator.");
                return true;
            }
            OfflinePlayer t = find(a[1]);
            if (t == null) {
                s.sendMessage("§cJucatorul nu a intrat niciodata pe server.");
                return true;
            }
            list(s, t);
            return true;
        }
        if (sub.equals("da") || sub.equals("retrage")) {
            if (!isIssuer(s)) {
                s.sendMessage("§cDoar politistii pot da sau retrage permise.");
                return true;
            }
            if (a.length < 3) {
                usage(s);
                return true;
            }
            OfflinePlayer t = find(a[1]);
            if (t == null) {
                s.sendMessage("§cJucatorul nu a intrat niciodata pe server.");
                return true;
            }
            Def d = def(a[2]);
            if (d == null) {
                s.sendMessage("§cPermis inexistent: " + a[2] + "  Disponibile: " + defs.keySet());
                return true;
            }
            String by = s.getName();
            if (sub.equals("da")) {
                grant(t, d.id, by);
                s.sendMessage("§a[Permise] §7I-ai acordat lui §f" + t.getName() + "§7 permisul §f" + d.name + "§7.");
            } else {
                revoke(t, d.id);
                s.sendMessage("§a[Permise] §7I-ai retras lui §f" + t.getName() + "§7 permisul §f" + d.name + "§7.");
            }
            return true;
        }
        usage(s);
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage("§7Folosire: §f/permise §7(permisele tale)"
            + (isIssuer(s) ? "  §f/permis lista [jucator]  /permis da <jucator> <id>  /permis retrage <jucator> <id>" : "")
            + (s.hasPermission("flowcity.permits.admin") ? "  /permis reload" : ""));
        if (isIssuer(s)) s.sendMessage("§7Permise: §f" + defs.keySet());
    }

    private void list(CommandSender to, OfflinePlayer t) {
        to.sendMessage("§b§lPermise §7- §f" + t.getName());
        boolean any = false;
        for (Def d : defs.values()) {
            boolean has = data.contains(path(t.getUniqueId(), d.id))
                || (t.isOnline() && t.getPlayer() != null && t.getPlayer().hasPermission(prefix + d.id));
            if (!has) continue;
            any = true;
            to.sendMessage("  §a+ §f" + d.name + " §8(" + d.id + ")");
        }
        if (!any) to.sendMessage("  §7Niciun permis. Se cumpara de la Primarie / Politie (magazinul de permise).");
    }

    private List<String> complete(CommandSender s, String[] a) {
        List<String> opts = new ArrayList<>();
        if (a.length == 1) {
            opts.add("lista");
            if (isIssuer(s)) {
                opts.add("da");
                opts.add("retrage");
            }
            if (s.hasPermission("flowcity.permits.admin")) opts.add("reload");
            return Commands.filter(opts, a[0]);
        }
        if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
            return Commands.filter(opts, a[1]);
        }
        if (a.length == 3 && (a[0].equalsIgnoreCase("da") || a[0].equalsIgnoreCase("retrage"))) {
            opts.addAll(defs.keySet());
            return Commands.filter(opts, a[2]);
        }
        return opts;
    }

    // ------------------------------------------------------------------ salvare

    private void scheduleSave() {
        if (saveScheduled) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveScheduled = false;
            saveNow();
        }, 100L);
    }

    private void saveNow() {
        try {
            data.save(dataFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Nu pot salva permits-data.yml: " + e);
        }
    }
}
