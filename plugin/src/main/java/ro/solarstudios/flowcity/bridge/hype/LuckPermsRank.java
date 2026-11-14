package ro.solarstudios.flowcity.bridge.hype;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Rangul jucatorului din LuckPerms (grup principal sau prefix/sufix), citit prin reflectie: pluginul nu depinde de LuckPerms la compilare. */
final class LuckPermsRank {
    private LuckPermsRank() {}

    private static Object lp;
    private static ClassLoader loader;
    private static long nextTry;

    private static boolean ready() {
        if (lp != null) return true;
        long now = System.currentTimeMillis();
        if (now < nextTry) return false;
        nextTry = now + 15000;
        try {
            Class<?> c = Class.forName("net.luckperms.api.LuckPerms");
            @SuppressWarnings({"unchecked", "rawtypes"})
            RegisteredServiceProvider<?> r = Bukkit.getServicesManager().getRegistration((Class) c);
            if (r == null) return false;
            lp = r.getProvider();
            loader = c.getClassLoader();
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    private static Object call(Object target, String iface, String method, Class<?>[] types, Object... args) throws Exception {
        if (target == null) return null;
        Method m = Class.forName(iface, true, loader).getMethod(method, types);
        return m.invoke(target, args);
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.replaceAll("(?i)[&§][0-9a-fk-or]", "").replaceAll("(?i)&#[0-9a-f]{6}", "").replaceAll("(?i)§x(§[0-9a-f]){6}", "").trim();
        return s.isEmpty() ? null : s;
    }

    /** mode: group (numele afisat al grupului principal), prefix sau suffix. null daca LuckPerms lipseste / nu are date. */
    static String of(UUID u, String mode) {
        if (!ready()) return null;
        try {
            Object um = call(lp, "net.luckperms.api.LuckPerms", "getUserManager", new Class<?>[0]);
            Object user = call(um, "net.luckperms.api.model.user.UserManager", "getUser", new Class<?>[] {UUID.class}, u);
            if (user == null) return null;
            Object cd = call(user, "net.luckperms.api.model.PermissionHolder", "getCachedData", new Class<?>[0]);
            Object meta = call(cd, "net.luckperms.api.cacheddata.CachedDataManager", "getMetaData", new Class<?>[0]);
            if ("prefix".equalsIgnoreCase(mode)) return clean((String) call(meta, "net.luckperms.api.cacheddata.CachedMetaData", "getPrefix", new Class<?>[0]));
            if ("suffix".equalsIgnoreCase(mode)) return clean((String) call(meta, "net.luckperms.api.cacheddata.CachedMetaData", "getSuffix", new Class<?>[0]));
            String g = (String) call(user, "net.luckperms.api.model.user.User", "getPrimaryGroup", new Class<?>[0]);
            if (g == null) return null;
            Object gm = call(lp, "net.luckperms.api.LuckPerms", "getGroupManager", new Class<?>[0]);
            Object grp = call(gm, "net.luckperms.api.model.group.GroupManager", "getGroup", new Class<?>[] {String.class}, g);
            if (grp != null) {
                String dn = clean((String) call(grp, "net.luckperms.api.model.group.Group", "getDisplayName", new Class<?>[0]));
                if (dn != null) return dn;
                String fn = (String) call(grp, "net.luckperms.api.model.group.Group", "getFriendlyName", new Class<?>[0]);
                if (fn != null && !fn.isEmpty()) return fn;
            }
            return g.substring(0, 1).toUpperCase() + g.substring(1);
        } catch (Throwable t) {
            lp = null;
            return null;
        }
    }
}
