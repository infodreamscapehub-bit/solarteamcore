package ro.solarstudios.flowcity.bridge.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

/** Inregistrare dinamica de comenzi si permisiuni (fara sa editam plugin.yml). Functioneaza pe CraftBukkit/Mohist/Arclight. */
public final class Commands {
    private Commands() {}

    public static void register(Plugin plugin, String name, String permission, CommandExecutor executor,
                                TabCompleter completer, String... aliases) {
        try {
            Constructor<PluginCommand> ctor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            ctor.setAccessible(true);
            PluginCommand cmd = ctor.newInstance(name, plugin);
            cmd.setExecutor(executor);
            if (completer != null) cmd.setTabCompleter(completer);
            if (aliases.length > 0) cmd.setAliases(Arrays.asList(aliases));
            if (permission != null && !permission.isEmpty()) cmd.setPermission(permission);
            commandMap().register(plugin.getName().toLowerCase(), cmd);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot inregistra comanda /" + name + ": " + e);
        }
    }

    public static void permission(String node, String description, PermissionDefault def) {
        if (Bukkit.getPluginManager().getPermission(node) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(node, description, def));
        }
    }

    private static CommandMap commandMap() throws Exception {
        Object server = Bukkit.getServer();
        for (Class<?> c = server.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("commandMap");
                f.setAccessible(true);
                return (CommandMap) f.get(server);
            } catch (NoSuchFieldException ignored) {
                // cautam in superclasa
            }
        }
        Object pm = Bukkit.getPluginManager();
        Field f = pm.getClass().getDeclaredField("commandMap");
        f.setAccessible(true);
        return (CommandMap) f.get(pm);
    }

    /** Utilitar pentru tab-complete: filtreaza dupa prefix. */
    public static List<String> filter(List<String> options, String prefix) {
        List<String> out = new java.util.ArrayList<>();
        String p = prefix == null ? "" : prefix.toLowerCase();
        for (String o : options) if (o.toLowerCase().startsWith(p)) out.add(o);
        return out;
    }
}
