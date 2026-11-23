package ro.solarstudios.flowcity.bridge.fjobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import ro.solarstudios.flowcity.bridge.fjobs.act.FJobsApi;
import ro.solarstudios.flowcity.bridge.fjobs.act.Items;

/**
 * Echipament de job ca iteme REALE din inventar (unelte si consumabile), comun tuturor joburilor cu sectiunea "gear:" in fjobs.yml.
 * Itemele sunt marcate cu PersistentDataContainer (nu pot fi falsificate prin redenumire); efectele (obligatoriu, bonus, consum, uzura)
 * se aplica DOAR pe server, la fiecare plata, in {@link ro.solarstudios.flowcity.bridge.fjobs.FJobsModule}. Electricianul isi are sistemul propriu.
 */
public final class FJobsGear implements Listener {
    public static final String TAG = "fj_gear";
    public static final String OWNER = "fj_gear_owner";
    public static final String USES = "fj_gear_uses";

    /** O piesa de echipament, citita din jobs.X.gear.Y. */
    public static final class Def {
        public String id;
        public String name;
        public String desc = "";
        public String material = "PAPER";
        public double price = 50;
        public int pack = 1;           // cate bucati primesti la o cumparare
        public int max = 1;            // cate bucati poti avea in inventar
        public int minLevel = 0;       // nivelul jobului necesar pentru cumparare
        public int starter = 0;        // bucati primite gratuit la prima tura a jobului
        public int uses = 0;           // 0 = fara uzura; >0 = utilizari (scade la fiecare plata cu "consume")
        public boolean enabled = true;
        public boolean required;       // fara el nu incepi tura; daca il pierzi, plata scade
        public boolean consumable;     // se consuma din stiva
        public boolean soulbound;      // nu poate fi aruncat/pus in containere
        public boolean removeOnEnd;    // se sterge la sfarsitul turei
        public boolean glow = true;
        public String consume = "none"; // none | pay (la fiecare plata) | mission (la fiecare misiune finalizata)
        public double bonusPay;        // +X (ex. 0.08 = +8%) la plata, cat timp il ai (si, la consumabile, il consumi)
        public double bonusXp;
        public double refund;          // 0..1: ce parte din pret se returneaza daca e sters la sfarsitul turei
        public final Map<String, Integer> enchants = new LinkedHashMap<>();
    }

    /** Efectul echipamentului asupra unei plati. */
    public static final class Fx {
        public double pay = 1;
        public double xp = 1;
    }

    private final FJobsApi api;
    private final Map<String, List<Def>> cache = new HashMap<>();
    private final Map<UUID, Long> warned = new HashMap<>();

    public FJobsGear(FJobsApi api) {
        this.api = api;
    }

    public void invalidate() {
        cache.clear();
    }

    // ------------------------------------------------------------------ config

    private boolean gs(String k, boolean def) {
        return api.cfg().getBoolean("settings.gear." + k, def);
    }

    private double gd(String k, double def) {
        return api.cfg().getDouble("settings.gear." + k, def);
    }

    public List<Def> defs(FJobDef jd) {
        if (jd == null) return new ArrayList<>();
        List<Def> got = cache.get(jd.id);
        if (got != null) return got;
        List<Def> out = new ArrayList<>();
        ConfigurationSection gsec = jd.conf == null ? null : jd.conf.getConfigurationSection("gear");
        if (gsec != null) {
            for (String id : gsec.getKeys(false)) {
                ConfigurationSection s = gsec.getConfigurationSection(id);
                if (s == null) continue;
                Def d = new Def();
                d.id = id.toLowerCase(Locale.ROOT);
                d.name = clip(s.getString("name", id), 40);
                d.desc = clip(s.getString("desc", ""), 120);
                d.material = s.getString("material", "PAPER");
                d.price = clamp(s.getDouble("price", 50), 0, 1_000_000);
                d.pack = (int) clamp(s.getInt("pack", 1), 1, 64);
                d.max = (int) clamp(s.getInt("max", 1), 1, 256);
                d.minLevel = (int) clamp(s.getInt("min-level", 0), 0, 100);
                d.starter = (int) clamp(s.getInt("starter", 0), 0, 64);
                d.uses = (int) clamp(s.getInt("uses", 0), 0, 100000);
                d.enabled = s.getBoolean("enabled", true);
                d.required = s.getBoolean("required", false);
                d.consumable = s.getBoolean("consumable", false);
                d.soulbound = s.getBoolean("soulbound", false);
                d.removeOnEnd = s.getBoolean("remove-on-end", gs("remove-on-end", false));
                d.glow = s.getBoolean("glow", true);
                String c = s.getString("consume", d.consumable || d.uses > 0 ? "pay" : "none").toLowerCase(Locale.ROOT);
                d.consume = "mission".equals(c) || "pay".equals(c) ? c : "none";
                d.bonusPay = clamp(s.getDouble("bonus-pay", 0), 0, 2);
                d.bonusXp = clamp(s.getDouble("bonus-xp", 0), 0, 2);
                d.refund = clamp(s.getDouble("refund", 0), 0, 1);
                ConfigurationSection es = s.getConfigurationSection("enchants");
                if (es != null) {
                    for (String ek : es.getKeys(false)) d.enchants.put(ek.toLowerCase(Locale.ROOT), (int) clamp(es.getInt(ek, 1), 1, 10));
                }
                if (d.uses > 0) d.pack = 1;       // uneltele cu uzura se dau pe rand (fiecare are contorul ei)
                out.add(d);
            }
        }
        cache.put(jd.id, out);
        return out;
    }

    public Def def(FJobDef jd, String id) {
        if (id == null) return null;
        for (Def d : defs(jd)) if (d.id.equalsIgnoreCase(id)) return d;
        return null;
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ------------------------------------------------------------------ iteme

    private NamespacedKey key(String k) {
        return Items.key(api.plugin(), k);
    }

    private static String tagOf(FJobDef jd, Def d) {
        return jd.id + ":" + d.id;
    }

    /** Eticheta e a lui si (daca e legat de jucator) apartine acestui jucator. */
    private boolean mine(Player p, ItemStack it, String tag) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return false;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        if (!tag.equals(pdc.get(key(TAG), PersistentDataType.STRING))) return false;
        String owner = pdc.get(key(OWNER), PersistentDataType.STRING);
        return owner == null || owner.equals(p.getUniqueId().toString());
    }

    private String gearTag(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key(TAG), PersistentDataType.STRING);
    }

    private boolean isSoulbound(ItemStack it) {
        if (gearTag(it) == null) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key(OWNER), PersistentDataType.STRING);
    }

    /** Utilizarile ramase pe cel mai uzat exemplar detinut (-1 = fara uzura sau nu il are). */
    public int usesLeft(Player p, FJobDef jd, Def d) {
        if (d.uses <= 0) return -1;
        String tag = tagOf(jd, d);
        int best = -1;
        for (ItemStack it : p.getInventory().getContents()) {
            if (!mine(p, it, tag)) continue;
            Integer left = it.getItemMeta().getPersistentDataContainer().get(key(USES), PersistentDataType.INTEGER);
            int u = left == null ? d.uses : left;
            if (best < 0 || u < best) best = u;
        }
        return best;
    }

    public int count(Player p, FJobDef jd, Def d) {
        String tag = tagOf(jd, d);
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (mine(p, it, tag)) n += it.getAmount();
        return n;
    }

    private List<String> lore(FJobDef jd, Def d, int usesLeft) {
        List<String> l = new ArrayList<>();
        if (!d.desc.isEmpty()) l.add("§7" + d.desc);
        l.add("§8Echipament " + jd.name);
        if (d.required) l.add("§6Obligatoriu pentru tura");
        if (d.consumable) l.add("§7Consumabil");
        if (d.bonusPay > 0) l.add("§aBonus plata: +" + Math.round(d.bonusPay * 100) + "%");
        if (d.bonusXp > 0) l.add("§aBonus XP: +" + Math.round(d.bonusXp * 100) + "%");
        if (d.uses > 0) l.add("§7Utilizari: " + usesLeft + "/" + d.uses);
        if (d.soulbound) l.add("§cLegat de proprietar");
        return l;
    }

    private ItemStack build(Player p, FJobDef jd, Def d, int amount) {
        ItemStack it = new ItemStack(Items.mat(d.material, Material.PAPER), Math.max(1, Math.min(64, amount)));
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        meta.setDisplayName((d.required ? "§6" : d.consumable ? "§a" : "§b") + d.name);
        meta.setLore(lore(jd, d, d.uses));
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(key(TAG), PersistentDataType.STRING, tagOf(jd, d));
        if (d.soulbound) pdc.set(key(OWNER), PersistentDataType.STRING, p.getUniqueId().toString());
        if (d.uses > 0) pdc.set(key(USES), PersistentDataType.INTEGER, d.uses);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_POTION_EFFECTS);
        meta.setUnbreakable(true);
        boolean any = false;
        for (Map.Entry<String, Integer> en : d.enchants.entrySet()) {
            Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft(en.getKey()));
            if (e != null) {
                meta.addEnchant(e, en.getValue(), true);
                any = true;
            }
        }
        if (!any && d.glow) meta.addEnchant(Enchantment.DURABILITY, 1, true);
        it.setItemMeta(meta);
        return it;
    }

    private void give(Player p, FJobDef jd, Def d, int amount) {
        if (d.uses > 0) {
            for (int i = 0; i < amount; i++) Items.give(p, build(p, jd, d, 1));
        } else {
            int left = amount;
            while (left > 0) {
                int n = Math.min(left, 64);
                Items.give(p, build(p, jd, d, n));
                left -= n;
            }
        }
    }

    private void take(Player p, FJobDef jd, Def d, int n) {
        String tag = tagOf(jd, d);
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && n > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (!mine(p, it, tag)) continue;
            int t = Math.min(n, it.getAmount());
            n -= t;
            if (it.getAmount() <= t) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - t);
        }
    }

    /** Consuma o unitate: din stiva (consumabil) sau o utilizare a unei unelte cu uzura. Intoarce false daca nu a avut ce. */
    private boolean spend(Player p, FJobDef jd, Def d) {
        String tag = tagOf(jd, d);
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!mine(p, it, tag)) continue;
            if (d.uses > 0) {
                ItemMeta meta = it.getItemMeta();
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                Integer left = pdc.get(key(USES), PersistentDataType.INTEGER);
                int u = (left == null ? d.uses : left) - 1;
                if (u <= 0) {
                    inv.setItem(i, null);
                    p.sendMessage("§e" + d.name + " s-a uzat. Cumpara altul din panoul jobului (Echipament).");
                } else {
                    pdc.set(key(USES), PersistentDataType.INTEGER, u);
                    meta.setLore(lore(jd, d, u));
                    it.setItemMeta(meta);
                }
                return true;
            }
            if (it.getAmount() <= 1) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - 1);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ efecte (server-side, la fiecare plata)

    public Fx apply(Player p, FJobDef jd, boolean mission, double rawPay, long rawXp) {
        Fx fx = new Fx();
        List<Def> ds = defs(jd);
        if (ds.isEmpty() || (rawPay <= 0 && rawXp <= 0)) return fx;
        double bp = 0;
        double bx = 0;
        double penalty = 1;
        int lvl = api.jobLevel(p, jd.id);
        boolean warnedNow = false;
        for (Def d : ds) {
            if (!d.enabled || lvl < d.minLevel) continue;
            boolean has = count(p, jd, d) > 0;
            boolean due = "pay".equals(d.consume) || ("mission".equals(d.consume) && mission);
            if (has) {
                if (due && (d.consumable || d.uses > 0)) {
                    if (!spend(p, jd, d)) has = false;
                }
                if (has) {
                    bp += d.bonusPay;
                    bx += d.bonusXp;
                }
            }
            if (!has && d.required && (!d.consumable || due)) {
                penalty *= clamp(gd("missing-required-pay", 0.5), 0, 1);
                if (!warnedNow) {
                    warnedNow = true;
                    long now = System.currentTimeMillis();
                    Long last = warned.get(p.getUniqueId());
                    if (last == null || now - last > 20_000) {
                        warned.put(p.getUniqueId(), now);
                        p.sendMessage("§eIti lipseste echipamentul: §f" + d.name + "§e. Plata este redusa pana il cumperi (Panou -> Echipament).");
                    }
                }
            }
        }
        double cap = clamp(gd("max-bonus", 1.0), 0, 5);
        fx.pay = (1 + Math.min(cap, bp)) * penalty;
        fx.xp = (1 + Math.min(cap, bx)) * (penalty < 1 ? Math.max(0.5, penalty) : 1);
        return fx;
    }

    /** Numele echipamentului obligatoriu care lipseste (nivelul jobului permite cumpararea), sau null daca are tot. */
    public String missingRequired(Player p, FJobDef jd) {
        List<String> miss = new ArrayList<>();
        int lvl = api.jobLevel(p, jd.id);
        for (Def d : defs(jd)) {
            if (d.enabled && d.required && lvl >= d.minLevel && count(p, jd, d) == 0) miss.add(d.name);
        }
        return miss.isEmpty() ? null : String.join(", ", miss);
    }

    /** Kitul de start (o singura data per jucator si job). */
    public void giveKit(Player p, FJobDef jd) {
        if (!gs("starter-kit", true) || api.counter(p, "gear.kit." + jd.id) > 0) return;
        boolean any = false;
        for (Def d : defs(jd)) {
            if (d.enabled && d.starter > 0) {
                give(p, jd, d, d.starter);
                any = true;
            }
        }
        api.addCounter(p, "gear.kit." + jd.id, 1);
        if (any) api.toast(p, jd.name, "Ai primit kitul de start. Echipament nou: din panoul jobului.");
    }

    /** La sfarsitul turei: sterge (cu returnare optionala) uneltele marcate remove-on-end. */
    public void onEnd(Player p, FJobDef jd) {
        for (Def d : defs(jd)) {
            if (!d.removeOnEnd) continue;
            int n = count(p, jd, d);
            if (n <= 0) continue;
            take(p, jd, d, n);
            if (d.refund > 0 && d.price > 0) {
                double back = Math.round(d.price * d.refund * n * 100) / 100.0;
                if (back > 0) {
                    api.plugin().money().deposit(p.getUniqueId(), back);
                    api.msg(p, true, d.name + " returnat: +" + back + "$");
                }
            }
        }
    }

    // ------------------------------------------------------------------ cumparare / administrare (comenzi de la client, validate aici)

    public boolean buy(Player p, FJobDef jd, String id, int qty) {
        Def d = def(jd, id);
        if (d == null || !d.enabled) {
            api.msg(p, false, "Articol indisponibil.");
            return false;
        }
        int lvl = api.jobLevel(p, jd.id);
        if (lvl < d.minLevel) {
            api.msg(p, false, d.name + " se deblocheaza la nivelul " + d.minLevel + " al jobului.");
            return false;
        }
        int packs = d.consumable ? (int) clamp(qty, 1, 8) : 1;
        int amount = d.pack * packs;
        if (count(p, jd, d) + amount > d.max) {
            api.msg(p, false, "Ai deja suficient din " + d.name + " (maxim " + d.max + ").");
            return false;
        }
        double price = Math.round(d.price * packs * 100) / 100.0;
        if (price > 0 && !api.plugin().money().withdraw(p.getUniqueId(), price)) {
            api.msg(p, false, "Nu ai destui bani (" + price + "$).");
            return false;
        }
        give(p, jd, d, amount);
        api.msg(p, true, "Ai cumparat " + d.name + (amount > 1 ? " x" + amount : "") + " pentru " + price + "$.");
        return true;
    }

    /** Administrare din GUI: price | toggle | required. */
    public boolean admin(Player p, FJobDef jd, String op, String id, double value) {
        if (!api.isAdmin(p)) {
            api.msg(p, false, "Nu ai permisiunea de administrare.");
            return false;
        }
        Def d = def(jd, id);
        if (d == null) return false;
        if (Double.isNaN(value) || Double.isInfinite(value)) value = 0;
        switch (op) {
            case "price":
                api.setConf(jd, "gear." + d.id + ".price", Math.round(clamp(value, 0, 1_000_000) * 100) / 100.0);
                break;
            case "toggle":
                api.setConf(jd, "gear." + d.id + ".enabled", !d.enabled);
                break;
            case "required":
                api.setConf(jd, "gear." + d.id + ".required", !d.required);
                break;
            default:
                return false;
        }
        invalidate();
        api.msg(p, true, "Salvat.");
        return true;
    }

    /** Starea echipamentului pentru panoul clientului. */
    public JsonArray state(Player p, FJobDef jd) {
        JsonArray a = new JsonArray();
        int lvl = api.jobLevel(p, jd.id);
        boolean admin = api.isAdmin(p);
        for (Def d : defs(jd)) {
            if (!d.enabled && !admin) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id);
            o.addProperty("name", d.name);
            o.addProperty("desc", d.desc);
            o.addProperty("price", d.price);
            o.addProperty("pack", d.pack);
            o.addProperty("max", d.max);
            o.addProperty("owned", count(p, jd, d));
            o.addProperty("required", d.required);
            o.addProperty("consumable", d.consumable);
            o.addProperty("soulbound", d.soulbound);
            o.addProperty("uses", d.uses);
            o.addProperty("minLevel", d.minLevel);
            o.addProperty("locked", lvl < d.minLevel);
            o.addProperty("enabled", d.enabled);
            o.addProperty("bonusPay", d.bonusPay);
            o.addProperty("bonusXp", d.bonusXp);
            o.addProperty("usesLeft", usesLeft(p, jd, d));
            o.addProperty("material", d.material);
            a.add(o);
        }
        return a;
    }

    // ------------------------------------------------------------------ protectii (soulbound, plasare)

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isSoulbound(e.getItemDrop().getItemStack())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§eEchipamentul de job nu poate fi aruncat.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (gearTag(e.getItemInHand()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        boolean sb = isSoulbound(e.getCurrentItem()) || isSoulbound(e.getCursor());
        if (!sb && e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getWhoClicked() instanceof Player) {
            sb = isSoulbound(((Player) e.getWhoClicked()).getInventory().getItem(e.getHotbarButton()));
        }
        if (!sb) return;
        Inventory top = e.getView().getTopInventory();
        InventoryType tt = top.getType();
        if (tt == InventoryType.CRAFTING || tt == InventoryType.PLAYER) return;
        boolean ownBottom = e.getClickedInventory() != null && e.getClickedInventory() == e.getView().getBottomInventory();
        InventoryAction a = e.getAction();
        if (ownBottom && a != InventoryAction.MOVE_TO_OTHER_INVENTORY && a != InventoryAction.COLLECT_TO_CURSOR
            && e.getClick() != org.bukkit.event.inventory.ClickType.NUMBER_KEY) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!isSoulbound(e.getOldCursor())) return;
        InventoryType tt = e.getView().getTopInventory().getType();
        if (tt == InventoryType.CRAFTING || tt == InventoryType.PLAYER) return;
        int top = e.getView().getTopInventory().getSize();
        for (int slot : e.getRawSlots()) {
            if (slot < top) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isSoulbound);
    }
}
