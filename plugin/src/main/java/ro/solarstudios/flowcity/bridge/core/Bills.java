package ro.solarstudios.flowcity.bridge.core;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Bancnotele fizice (itemele flowcitycore:1_lei ... 500_lei din mod). Cash-ul unui jucator = bancnotele din inventar +
 * restul din economia Vault. Daca materialele modului nu exista pe server, tot sistemul de bancnote e oprit si totul
 * ramane in Vault ca inainte.
 */
public final class Bills {
    private Bills() {}

    public static final int[] DENOMS = {500, 200, 100, 50, 20, 10, 5, 1};
    private static final Map<Integer, Material> MATS = new HashMap<>();
    private static final Map<Material, Integer> REVERSE = new EnumMap<>(Material.class);
    private static boolean init;
    private static boolean ok;

    private static void init() {
        if (init) return;
        init = true;
        for (int d : DENOMS) {
            Material m = null;
            try {
                m = Material.getMaterial("FLOWCITYCORE_" + d + "_LEI");
                if (m == null) {
                    for (Material x : Material.values()) {
                        String n = x.name().toUpperCase(Locale.ROOT);
                        if (n.equals("FLOWCITYCORE_" + d + "_LEI")) m = x;
                        else if (m == null && n.contains("FLOWCITYCORE") && n.endsWith("_" + d + "_LEI")) m = x;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                m = null;
            }
            if (m != null) {
                MATS.put(d, m);
                REVERSE.put(m, d);
            }
        }
        ok = MATS.size() == DENOMS.length;
    }

    /** Exista bancnotele pe acest server? */
    public static boolean available() {
        init();
        return ok;
    }

    private static int denomOf(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return 0;
        Integer d = REVERSE.get(it.getType());
        return d == null ? 0 : d;
    }

    private static int[] count(Player p) {
        int[] c = new int[DENOMS.length];
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getStorageContents()) add(c, it);
        add(c, inv.getItemInOffHand());
        return c;
    }

    private static void add(int[] c, ItemStack it) {
        int d = denomOf(it);
        if (d == 0) return;
        for (int i = 0; i < DENOMS.length; i++) if (DENOMS[i] == d) c[i] += it.getAmount();
    }

    /** Valoarea totala a bancnotelor din inventar (lei). */
    public static long value(Player p) {
        if (!available() || p == null) return 0;
        int[] c = count(p);
        long v = 0;
        for (int i = 0; i < DENOMS.length; i++) v += (long) c[i] * DENOMS[i];
        return v;
    }

    /** Planul de scoatere a "need" lei: cate bancnote din fiecare fel; null daca nu ajung. Restul peste suma e "rest". */
    private static int[] plan(int[] have, long need, long[] rest) {
        int[] used = new int[DENOMS.length];
        long remaining = need;
        for (int i = 0; i < DENOMS.length; i++) {
            int use = (int) Math.min(have[i], remaining / DENOMS[i]);
            used[i] = use;
            remaining -= (long) use * DENOMS[i];
        }
        for (int i = DENOMS.length - 1; i >= 0 && remaining > 0; i--) {
            while (remaining > 0 && used[i] < have[i]) {
                used[i]++;
                remaining -= DENOMS[i];
            }
        }
        if (remaining > 0) return null;
        rest[0] = -remaining;
        return used;
    }

    private static void removeBills(Player p, int[] used) {
        PlayerInventory inv = p.getInventory();
        int[] left = used.clone();
        ItemStack[] st = inv.getStorageContents();
        for (int s = 0; s < st.length; s++) left = strip(st, s, left);
        inv.setStorageContents(st);
        ItemStack off = inv.getItemInOffHand();
        ItemStack[] one = {off};
        strip(one, 0, left);
        inv.setItemInOffHand(one[0]);
    }

    private static int[] strip(ItemStack[] arr, int idx, int[] left) {
        ItemStack it = arr[idx];
        int d = denomOf(it);
        if (d == 0) return left;
        for (int i = 0; i < DENOMS.length; i++) {
            if (DENOMS[i] != d || left[i] <= 0) continue;
            int take = Math.min(left[i], it.getAmount());
            left[i] -= take;
            if (take >= it.getAmount()) arr[idx] = null;
            else it.setAmount(it.getAmount() - take);
        }
        return left;
    }

    /** Scoate bancnote pentru "need" lei (cu rest dat inapoi tot in bancnote). Fals daca nu ajung. */
    public static boolean take(Player p, long need) {
        if (need <= 0) return true;
        if (!available()) return false;
        long[] rest = new long[1];
        int[] used = plan(count(p), need, rest);
        if (used == null) return false;
        removeBills(p, used);
        if (rest[0] > 0) give(p, rest[0]);
        return true;
    }

    /** Scoate TOATE bancnotele (le intoarce ca valoare); pentru platile care trec si de Vault. */
    public static long takeAll(Player p) {
        long v = value(p);
        if (v > 0) take(p, v);
        return v;
    }

    /** Da bancnote pentru suma (lei intregi, cu cele mai mari valori); ce nu incape in inventar cade jos. */
    public static void give(Player p, long amount) {
        if (amount <= 0 || !available()) return;
        long left = amount;
        for (int d : DENOMS) {
            long n = left / d;
            left -= n * d;
            while (n > 0) {
                int stack = (int) Math.min(n, 64);
                n -= stack;
                ItemStack it = new ItemStack(MATS.get(d), stack);
                Map<Integer, ItemStack> over = p.getInventory().addItem(it);
                Location l = p.getLocation();
                for (ItemStack o : over.values()) l.getWorld().dropItemNaturally(l, o);
            }
        }
    }
}
