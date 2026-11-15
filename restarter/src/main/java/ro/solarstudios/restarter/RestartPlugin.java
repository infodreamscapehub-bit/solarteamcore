package ro.solarstudios.restarter;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * FlowCityRestart: restart / reload / load / unload la pluginuri in timpul rularii, fara restart de server.
 * "restart" = dezactiveaza, descarca din memorie, reciteste jar-ul de pe disc (deci preia si versiunea noua) si porneste din nou.
 * Atentie: plugin care tine date in alte pluginuri sau care foloseste biblioteci shaded poate cere restart complet.
 */
public final class RestartPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final String P = "§8[§bPlugin§8] ";
    private static final List<String> SUBS = Arrays.asList("restart", "reload", "load", "unload", "enable", "disable", "list", "info");

    @Override
    public void onEnable() {
        PluginCommand a = getCommand("pmgr");
        PluginCommand b = getCommand("prestart");
        if (a != null) {
            a.setExecutor(this);
            a.setTabCompleter(this);
        }
        if (b != null) {
            b.setExecutor(this);
            b.setTabCompleter(this);
        }
    }

    // ------------------------------------------------------------------ comenzi

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("prestart")) {
            if (a.length == 0) {
                s.sendMessage(P + "§cFolosire: /prestart <plugin> [plugin2 ...]");
                return true;
            }
            for (String n : a) restart(s, n);
            return true;
        }
        if (a.length == 0) {
            help(s);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String arg = a.length > 1 ? a[1] : "";
        switch (sub) {
            case "restart":
            case "reload":
                if (arg.isEmpty()) {
                    s.sendMessage(P + "§cFolosire: /pmgr " + sub + " <plugin> [plugin2 ...]");
                    return true;
                }
                for (int i = 1; i < a.length; i++) {
                    if (sub.equals("reload")) softReload(s, a[i]);
                    else restart(s, a[i]);
                }
                return true;
            case "load":
                if (arg.isEmpty()) {
                    s.sendMessage(P + "§cFolosire: /pmgr load <fisier.jar>");
                    return true;
                }
                load(s, arg);
                return true;
            case "unload":
                if (arg.isEmpty()) {
                    s.sendMessage(P + "§cFolosire: /pmgr unload <plugin>");
                    return true;
                }
                unload(s, arg);
                return true;
            case "enable":
            case "disable":
                if (arg.isEmpty()) {
                    s.sendMessage(P + "§cFolosire: /pmgr " + sub + " <plugin>");
                    return true;
                }
                toggle(s, arg, sub.equals("enable"));
                return true;
            case "list":
                list(s);
                return true;
            case "info":
                if (arg.isEmpty()) {
                    s.sendMessage(P + "§cFolosire: /pmgr info <plugin>");
                    return true;
                }
                info(s, arg);
                return true;
            default:
                help(s);
                return true;
        }
    }

    private void help(CommandSender s) {
        s.sendMessage("§b§lFlowCityRestart §8- §7manager de pluginuri");
        s.sendMessage("§3/prestart <plugin...> §7- restart rapid (reciteste jar-ul)");
        s.sendMessage("§3/pmgr restart <plugin...> §7- dezactiveaza, descarca, reincarca din jar, porneste");
        s.sendMessage("§3/pmgr reload <plugin...> §7- doar dezactiveaza si porneste din nou (fara sa recitesti jar-ul)");
        s.sendMessage("§3/pmgr load <fisier.jar> §7- incarca un plugin nou din folderul plugins");
        s.sendMessage("§3/pmgr unload <plugin> §7- descarca complet un plugin");
        s.sendMessage("§3/pmgr enable|disable <plugin> §7- porneste / opreste");
        s.sendMessage("§3/pmgr list §7/ §3info <plugin>");
    }

    // ------------------------------------------------------------------ actiuni

    private Plugin find(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        if (p != null) return p;
        for (Plugin x : Bukkit.getPluginManager().getPlugins()) {
            if (x.getName().equalsIgnoreCase(name)) return x;
        }
        return null;
    }

    private boolean protectedPlugin(CommandSender s, Plugin p) {
        if (p == this) {
            s.sendMessage(P + "§cNu pot opera asupra mea insumi.");
            return true;
        }
        return false;
    }

    private void toggle(CommandSender s, String name, boolean on) {
        Plugin p = find(name);
        if (p == null) {
            s.sendMessage(P + "§cPluginul §f" + name + " §cnu exista.");
            return;
        }
        if (protectedPlugin(s, p)) return;
        PluginManager pm = Bukkit.getPluginManager();
        if (on) pm.enablePlugin(p);
        else pm.disablePlugin(p);
        s.sendMessage(P + "§a" + p.getName() + (on ? " pornit." : " oprit."));
    }

    private void softReload(CommandSender s, String name) {
        Plugin p = find(name);
        if (p == null) {
            s.sendMessage(P + "§cPluginul §f" + name + " §cnu exista.");
            return;
        }
        if (protectedPlugin(s, p)) return;
        try {
            PluginManager pm = Bukkit.getPluginManager();
            pm.disablePlugin(p);
            pm.enablePlugin(p);
            s.sendMessage(P + "§a" + p.getName() + " §7reincarcat (fara recitirea jar-ului).");
        } catch (Throwable t) {
            s.sendMessage(P + "§cEroare la reload: " + t);
            getLogger().warning("reload " + name + ": " + t);
        }
    }

    private void restart(CommandSender s, String name) {
        Plugin p = find(name);
        if (p == null) {
            s.sendMessage(P + "§cPluginul §f" + name + " §cnu exista.");
            return;
        }
        if (protectedPlugin(s, p)) return;
        File jar = fileOf(p);
        if (jar == null || !jar.isFile()) {
            s.sendMessage(P + "§cNu gasesc jar-ul lui " + p.getName() + "; incerc doar reload.");
            softReload(s, name);
            return;
        }
        // pluginurile care depind de acesta se opresc inainte si pornesc dupa el
        List<Plugin> deps = dependents(p);
        List<File> depJars = new ArrayList<>();
        List<String> depNames = new ArrayList<>();
        try {
            for (Plugin d : deps) {
                File f = fileOf(d);
                if (f != null && f.isFile() && d != this) {
                    depJars.add(f);
                    depNames.add(d.getName());
                }
            }
            for (int i = deps.size() - 1; i >= 0; i--) {
                if (deps.get(i) != this) unloadQuiet(deps.get(i));
            }
            String pn = p.getName();
            unloadQuiet(p);
            Plugin fresh = loadQuiet(jar);
            if (fresh == null) {
                s.sendMessage(P + "§c" + pn + " nu s-a putut reincarca (vezi consola). Jar: " + jar.getName());
                return;
            }
            StringBuilder extra = new StringBuilder();
            for (File f : depJars) {
                Plugin d = loadQuiet(f);
                extra.append(d != null ? d.getName() + " " : "§c" + f.getName() + "(eroare) §7");
            }
            s.sendMessage(P + "§a" + fresh.getName() + " v" + fresh.getDescription().getVersion() + " §7repornit din " + jar.getName()
                + (depJars.isEmpty() ? "" : "; dependente repornite: " + extra));
        } catch (Throwable t) {
            s.sendMessage(P + "§cEroare la restart: " + t);
            getLogger().warning("restart " + name + ": " + t);
        }
    }

    private void load(CommandSender s, String file) {
        File jar = new File(getDataFolder().getParentFile(), file.endsWith(".jar") ? file : file + ".jar");
        if (!jar.isFile()) {
            s.sendMessage(P + "§cNu exista fisierul §f" + jar.getName() + " §cin folderul plugins.");
            return;
        }
        Plugin p = loadQuiet(jar);
        if (p == null) s.sendMessage(P + "§cNu s-a putut incarca " + jar.getName() + " (vezi consola).");
        else s.sendMessage(P + "§a" + p.getName() + " v" + p.getDescription().getVersion() + " incarcat si pornit.");
    }

    private void unload(CommandSender s, String name) {
        Plugin p = find(name);
        if (p == null) {
            s.sendMessage(P + "§cPluginul §f" + name + " §cnu exista.");
            return;
        }
        if (protectedPlugin(s, p)) return;
        List<Plugin> deps = dependents(p);
        if (!deps.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Plugin d : deps) sb.append(d.getName()).append(' ');
            s.sendMessage(P + "§eAtentie: depind de el " + sb + "§e(le opresc si pe ele).");
            for (int i = deps.size() - 1; i >= 0; i--) if (deps.get(i) != this) unloadQuiet(deps.get(i));
        }
        unloadQuiet(p);
        s.sendMessage(P + "§a" + p.getName() + " descarcat din memorie.");
    }

    private void list(CommandSender s) {
        Plugin[] all = Bukkit.getPluginManager().getPlugins();
        StringBuilder sb = new StringBuilder();
        for (Plugin p : all) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append(p.isEnabled() ? "§a" : "§c").append(p.getName());
        }
        s.sendMessage(P + "§7Pluginuri (" + all.length + "): " + sb);
    }

    private void info(CommandSender s, String name) {
        Plugin p = find(name);
        if (p == null) {
            s.sendMessage(P + "§cPluginul §f" + name + " §cnu exista.");
            return;
        }
        File f = fileOf(p);
        s.sendMessage(P + "§b" + p.getName() + " §7v" + p.getDescription().getVersion() + (p.isEnabled() ? " §a(pornit)" : " §c(oprit)"));
        s.sendMessage("§7Autori: §f" + p.getDescription().getAuthors());
        s.sendMessage("§7Jar: §f" + (f == null ? "?" : f.getName()));
        s.sendMessage("§7Depinde de: §f" + p.getDescription().getDepend() + " §7soft: §f" + p.getDescription().getSoftDepend());
        List<Plugin> deps = dependents(p);
        StringBuilder sb = new StringBuilder();
        for (Plugin d : deps) sb.append(d.getName()).append(' ');
        s.sendMessage("§7Dependente: §f" + (sb.length() == 0 ? "-" : sb));
    }

    // ------------------------------------------------------------------ miezul: descarcare / incarcare

    /** Pluginurile care depind (hard sau soft) de p, direct sau indirect, in ordinea in care trebuie oprite ultimele. */
    private List<Plugin> dependents(Plugin p) {
        Set<Plugin> out = new LinkedHashSet<>();
        collect(p, out);
        return new ArrayList<>(out);
    }

    private void collect(Plugin p, Set<Plugin> out) {
        for (Plugin x : Bukkit.getPluginManager().getPlugins()) {
            if (x == p || x == this || out.contains(x)) continue;
            if (x.getDescription().getDepend().contains(p.getName()) || x.getDescription().getSoftDepend().contains(p.getName())) {
                out.add(x);
                collect(x, out);
            }
        }
    }

    private File fileOf(Plugin p) {
        try {
            Method m = JavaPlugin.class.getDeclaredMethod("getFile");
            m.setAccessible(true);
            return (File) m.invoke(p);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void unloadQuiet(Plugin p) {
        PluginManager pm = Bukkit.getPluginManager();
        String name = p.getName();
        try {
            pm.disablePlugin(p);
        } catch (Throwable t) {
            getLogger().warning("disable " + name + ": " + t);
        }
        try {
            HandlerList.unregisterAll(p);
            Bukkit.getScheduler().cancelTasks(p);
            Bukkit.getServicesManager().unregisterAll(p);
            Bukkit.getMessenger().unregisterIncomingPluginChannel(p);
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(p);
        } catch (Throwable t) {
            getLogger().warning("cleanup " + name + ": " + t);
        }
        try {
            Field lf = pm.getClass().getDeclaredField("plugins");
            lf.setAccessible(true);
            ((Collection<Plugin>) lf.get(pm)).remove(p);
            Field nf = pm.getClass().getDeclaredField("lookupNames");
            nf.setAccessible(true);
            Map<String, Plugin> names = (Map<String, Plugin>) nf.get(pm);
            for (Iterator<Map.Entry<String, Plugin>> it = names.entrySet().iterator(); it.hasNext();) {
                if (it.next().getValue() == p) it.remove();
            }
        } catch (Throwable t) {
            getLogger().warning("scoatere din lista " + name + ": " + t);
        }
        try {
            Field cf = pm.getClass().getDeclaredField("commandMap");
            cf.setAccessible(true);
            CommandMap cm = (CommandMap) cf.get(pm);
            Field kf = cm.getClass().getDeclaredField("knownCommands");
            kf.setAccessible(true);
            Map<String, Command> known = (Map<String, Command>) kf.get(cm);
            for (Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator(); it.hasNext();) {
                Command cmd = it.next().getValue();
                if (cmd instanceof PluginCommand && ((PluginCommand) cmd).getPlugin() == p) {
                    cmd.unregister(cm);
                    it.remove();
                }
            }
        } catch (Throwable t) {
            getLogger().warning("scoatere comenzi " + name + ": " + t);
        }
        try {
            ClassLoader cl = p.getClass().getClassLoader();
            try {
                Field lf = Class.forName("org.bukkit.plugin.java.JavaPluginLoader").getDeclaredField("loaders");
                lf.setAccessible(true);
                Object loader = null;
                Field plf = cl.getClass().getDeclaredField("loader");
                plf.setAccessible(true);
                loader = plf.get(cl);
                Object loaders = lf.get(loader);
                if (loaders instanceof Collection) ((Collection<Object>) loaders).remove(cl);
            } catch (Throwable ignored) {
                // alta versiune de server: nu e grav
            }
            if (cl instanceof URLClassLoader) ((URLClassLoader) cl).close();
        } catch (Throwable t) {
            getLogger().warning("inchidere classloader " + name + ": " + t);
        }
        syncCommands();
        System.gc();
    }

    private Plugin loadQuiet(File jar) {
        try {
            PluginManager pm = Bukkit.getPluginManager();
            Plugin p = pm.loadPlugin(jar);
            if (p == null) return null;
            try {
                p.onLoad();
            } catch (Throwable t) {
                getLogger().warning("onLoad " + p.getName() + ": " + t);
            }
            pm.enablePlugin(p);
            syncCommands();
            return p;
        } catch (Throwable t) {
            getLogger().warning("load " + jar.getName() + ": " + t);
            return null;
        }
    }

    /** Actualizeaza lista de comenzi trimisa clientilor (tab-complete), daca serverul are metoda. */
    private void syncCommands() {
        try {
            Method m = Bukkit.getServer().getClass().getDeclaredMethod("syncCommands");
            m.setAccessible(true);
            m.invoke(Bukkit.getServer());
        } catch (Throwable ignored) {
            // nu exista pe toate versiunile
        }
    }

    // ------------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        boolean quick = c.getName().equalsIgnoreCase("prestart");
        if (!quick && a.length == 1) {
            for (String x : SUBS) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
            return out;
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        if (!quick && a.length >= 2 && a[0].equalsIgnoreCase("load")) {
            File[] files = getDataFolder().getParentFile().listFiles();
            if (files != null) {
                for (File f : files) if (f.getName().toLowerCase(Locale.ROOT).endsWith(".jar") && f.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(f.getName());
            }
            Collections.sort(out);
            return out;
        }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p != this && p.getName().toLowerCase(Locale.ROOT).startsWith(last)) out.add(p.getName());
        }
        Collections.sort(out);
        return out;
    }
}
