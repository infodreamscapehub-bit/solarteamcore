package ro.solarstudios.flowcity.bridge.eadmin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * API-ul LuckPerms, apelat prin reflectie (pluginul nu depinde de LuckPerms la compilare, la fel ca restul modulului). Citeste grupurile,
 * permisiunile si grupurile unui jucator si le (de)atribuie prin UserManager/NodeMap, deci LuckPerms isi pastreaza propria validare si
 * salvare; nimic nu trece pe langa el.
 */
final class LpApi {
    private static final String LP = "net.luckperms.api.LuckPerms";
    private static final String NODE = "net.luckperms.api.node.Node";
    private static final String USER = "net.luckperms.api.model.user.User";
    private static final String GROUP = "net.luckperms.api.model.group.Group";
    private static final String HOLDER = "net.luckperms.api.model.PermissionHolder";
    private static final String UMGR = "net.luckperms.api.model.user.UserManager";
    private static final String GMGR = "net.luckperms.api.model.group.GroupManager";

    private LpApi() {}

    private static Object lp;
    private static ClassLoader loader;

    static boolean available() {
        if (lp != null) return true;
        try {
            Class<?> c = Class.forName(LP);
            @SuppressWarnings({"unchecked", "rawtypes"})
            RegisteredServiceProvider<?> r = Bukkit.getServicesManager().getRegistration((Class) c);
            if (r == null) return false;
            lp = r.getProvider();
            loader = c.getClassLoader();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object call(Object target, String iface, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = Class.forName(iface, true, loader).getMethod(method, types);
        return m.invoke(target, args);
    }

    private static Object userManager() throws Exception {
        return call(lp, LP, "getUserManager", new Class<?>[0]);
    }

    private static Object groupManager() throws Exception {
        return call(lp, LP, "getGroupManager", new Class<?>[0]);
    }

    /** Numele tuturor grupurilor incarcate. */
    static List<String> groups() {
        List<String> out = new ArrayList<>();
        if (!available()) return out;
        try {
            Object gm = groupManager();
            Collection<?> all = (Collection<?>) call(gm, GMGR, "getLoadedGroups", new Class<?>[0]);
            for (Object g : all) out.add((String) call(g, GROUP, "getName", new Class<?>[0]));
        } catch (Throwable t) {
            // LuckPerms indisponibil
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Nodurile unui grup (cheie, valoare), grupurile mostenite apar ca "group.nume". */
    static List<String[]> groupNodes(String group) {
        List<String[]> out = new ArrayList<>();
        if (!available()) return out;
        try {
            Object gm = groupManager();
            Object g = call(gm, GMGR, "getGroup", new Class<?>[] {String.class}, group);
            if (g != null) nodes(g, out);
        } catch (Throwable t) {
            // ignorat
        }
        return out;
    }

    private static void nodes(Object holder, List<String[]> out) throws Exception {
        Collection<?> ns = (Collection<?>) call(holder, HOLDER, "getNodes", new Class<?>[0]);
        for (Object n : ns) {
            String key = (String) call(n, NODE, "getKey", new Class<?>[0]);
            Object val = call(n, NODE, "getValue", new Class<?>[0]);
            out.add(new String[] {key, String.valueOf(val)});
        }
    }

    /** Incarca utilizatorul (si offline) si ruleaza actiunea pe un thread LuckPerms; rezultatul vine inapoi pe thread-ul serverului. */
    interface UserFn<T> {
        T apply(Object user) throws Exception;
    }

    static <T> CompletableFuture<T> withUser(UUID u, UserFn<T> fn) {
        CompletableFuture<T> out = new CompletableFuture<>();
        if (!available()) {
            out.completeExceptionally(new IllegalStateException("LuckPerms lipseste"));
            return out;
        }
        try {
            @SuppressWarnings("unchecked")
            CompletableFuture<Object> f = (CompletableFuture<Object>) call(userManager(), UMGR, "loadUser", new Class<?>[] {UUID.class}, u);
            f.whenComplete((user, err) -> {
                if (err != null || user == null) {
                    out.completeExceptionally(err == null ? new IllegalStateException("utilizator necunoscut") : err);
                    return;
                }
                try {
                    out.complete(fn.apply(user));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            out.completeExceptionally(t);
        }
        return out;
    }

    /** Grupurile (mostenire) active ale utilizatorului. */
    static List<String> userGroups(Object user) throws Exception {
        List<String[]> ns = new ArrayList<>();
        nodes(user, ns);
        List<String> out = new ArrayList<>();
        for (String[] n : ns) if (n[0].startsWith("group.") && n[1].equals("true")) out.add(n[0].substring(6));
        java.util.Collections.sort(out);
        return out;
    }

    /** Permisiunile directe ale utilizatorului (fara grupuri, prefixe, greutati). */
    static List<String[]> userPerms(Object user) throws Exception {
        List<String[]> ns = new ArrayList<>();
        nodes(user, ns);
        List<String[]> out = new ArrayList<>();
        for (String[] n : ns) {
            String k = n[0];
            if (k.startsWith("group.") || k.startsWith("prefix.") || k.startsWith("suffix.") || k.startsWith("weight.") || k.startsWith("meta.")) continue;
            out.add(n);
        }
        return out;
    }

    /** Adauga (add=true) sau scoate un grup; salveaza prin UserManager. Intoarce false daca nimic nu s-a schimbat. */
    static boolean setGroup(Object user, String group, boolean add) throws Exception {
        Class<?> inh = Class.forName("net.luckperms.api.node.types.InheritanceNode", true, loader);
        Object builder = inh.getMethod("builder", String.class).invoke(null, group);
        Object node = builder.getClass().getMethod("build").invoke(builder);
        Object data = call(user, HOLDER, "data", new Class<?>[0]);
        Class<?> map = Class.forName("net.luckperms.api.model.data.NodeMap", true, loader);
        Object res = map.getMethod(add ? "add" : "remove", Class.forName(NODE, true, loader)).invoke(data, node);
        boolean changed = res == null || String.valueOf(res).contains("SUCCESS");
        try {
            Object r = res;
            Method wasSuccessful = r.getClass().getMethod("wasSuccessful");
            wasSuccessful.setAccessible(true);
            changed = Boolean.TRUE.equals(wasSuccessful.invoke(r));
        } catch (Throwable ignored) {
            // pastram euristica de mai sus
        }
        call(userManager(), UMGR, "saveUser", new Class<?>[] {Class.forName(USER, true, loader)}, user);
        return changed;
    }

    static String userPrimary(Object user) throws Exception {
        return (String) call(user, USER, "getPrimaryGroup", new Class<?>[0]);
    }

    /** Numele grupului principal (afisat in tabelul de jucatori), din cache pentru cei online; null daca lipseste. */
    static String primary(UUID u) {
        if (!available()) return null;
        try {
            Object user = call(userManager(), UMGR, "getUser", new Class<?>[] {UUID.class}, u);
            if (user == null) return null;
            return (String) call(user, USER, "getPrimaryGroup", new Class<?>[0]);
        } catch (Throwable t) {
            return null;
        }
    }
}
