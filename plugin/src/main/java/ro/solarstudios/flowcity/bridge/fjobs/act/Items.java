package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Obiecte de job (colete, scrisori, unelte): marcate cu PersistentDataContainer, ca sa nu fie confundate cu iteme obisnuite. */
public final class Items {
    private Items() {}

    public static NamespacedKey key(Plugin pl, String k) {
        return new NamespacedKey(pl, k);
    }

    public static ItemStack make(Plugin pl, Material m, String name, List<String> lore, String tagKey, String tagValue) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(key(pl, tagKey), PersistentDataType.STRING, tagValue);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static String tag(Plugin pl, ItemStack it, String tagKey) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key(pl, tagKey), PersistentDataType.STRING);
    }

    /** Toate itemele din inventar care au eticheta (indiferent de valoare). */
    public static List<ItemStack> tagged(Plugin pl, Player p, String tagKey) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) if (tag(pl, it, tagKey) != null) out.add(it);
        return out;
    }

    public static int removeTagged(Plugin pl, Player p, String tagKey) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (tag(pl, it, tagKey) != null) {
                n += it.getAmount();
                inv.setItem(i, null);
            }
        }
        return n;
    }

    /** Scoate o singura bucata cu eticheta si valoarea data. */
    public static boolean removeOne(Plugin pl, Player p, String tagKey, String value) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (value.equals(tag(pl, it, tagKey))) {
                if (it.getAmount() <= 1) inv.setItem(i, null);
                else it.setAmount(it.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    public static boolean has(Plugin pl, Player p, String tagKey, String value) {
        for (ItemStack it : p.getInventory().getContents()) if (value.equals(tag(pl, it, tagKey))) return true;
        return false;
    }

    /** Da itemul; daca inventarul e plin, il arunca la picioare. */
    public static void give(Player p, ItemStack it) {
        java.util.Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack r : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), r);
    }

    public static Material mat(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name.trim());
        return m == null ? def : m;
    }

    public static List<String> lore(String... s) {
        return new ArrayList<>(Arrays.asList(s));
    }
}
