package ro.solarstudios.flowcity.bridge.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Creeaza si citeste itemele FlowCity de echipament: id + serial in PersistentDataContainer (nu pot fi falsificate prin redenumire). */
public final class ItemKit {
    private static Plugin plugin;
    private static NamespacedKey kItem;
    private static NamespacedKey kSerial;

    private ItemKit() {}

    public static void init(Plugin pl) {
        plugin = pl;
        kItem = new NamespacedKey(pl, "fc_item");
        kSerial = new NamespacedKey(pl, "fc_serial");
    }

    /** Materialul modului pentru un item: FLOWCITYCORE_<ID>; daca modul nu e incarcat (server fara mod) cade pe PAPER. */
    public static Material materialFor(String id) {
        Material m = Material.matchMaterial("FLOWCITYCORE_" + id.toUpperCase(Locale.ROOT));
        return m == null ? Material.PAPER : m;
    }

    public static ItemStack make(String id, String name, List<String> lore, String serial, int amount) {
        ItemStack it = new ItemStack(materialFor(id), Math.max(1, amount));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(new ArrayList<String>(lore));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(kItem, PersistentDataType.STRING, id);
            pdc.set(kSerial, PersistentDataType.STRING, serial);
            it.setItemMeta(meta);
        }
        return it;
    }

    public static String idOf(ItemStack it) {
        return read(it, kItem);
    }

    public static String serialOf(ItemStack it) {
        return read(it, kSerial);
    }

    private static String read(ItemStack it, NamespacedKey k) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(k, PersistentDataType.STRING);
    }

    /** Date proprii ale itemului (ex. canalul unui radio, id-ul unei probe). */
    public static String data(ItemStack it, String key) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "fcd_" + key), PersistentDataType.STRING);
    }

    public static void setData(ItemStack it, String key, String value) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        NamespacedKey k = new NamespacedKey(plugin, "fcd_" + key);
        if (value == null) meta.getPersistentDataContainer().remove(k);
        else meta.getPersistentDataContainer().set(k, PersistentDataType.STRING, value);
        it.setItemMeta(meta);
    }

    public static void setLore(ItemStack it, List<String> lore) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        meta.setLore(new ArrayList<String>(lore));
        it.setItemMeta(meta);
    }

    public static void setName(ItemStack it, String name) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(name);
        it.setItemMeta(meta);
    }
}
