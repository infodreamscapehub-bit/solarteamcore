package ro.solarstudios.flowcity.bridge.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * E-shop (arme, orice): se deschide de la un NPC cu "shop open <jucator> <magazin>" (ZNPCs: CONSOLE). Continutul sta in
 * shop.yml. Plata trece prin PayModule (cash sau card), apoi itemele se dau cu "give" din consola (sau cu comenzile din
 * articol). Canal "shop": server -> client: open, result; client -> server: buy.
 */
public final class ShopModule extends JsonModule {
    private static final class Item {
        String name;
        String item;
        double price;
        int amount;
        String desc;
        List<String> commands = new ArrayList<>();
    }

    private static final class Cat {
        String name;
        List<Item> items = new ArrayList<>();
    }

    private static final class Shop {
        String id;
        String title;
        List<Cat> cats = new ArrayList<>();
    }

    private static final class Session {
        final String shop;
        final Location loc;
        final long at = System.currentTimeMillis();

        Session(String shop, Location loc) {
            this.shop = shop;
            this.loc = loc;
        }
    }

    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private double radius = 12;

    @Override
    public String id() {
        return "shop";
    }

    @Override
    protected void onEnable() {
        File f = new File(plugin.getDataFolder(), "shop.yml");
        if (!f.exists()) plugin.saveResource("shop.yml", false);
        load();
        on("buy", this::buy);
        Commands.register(plugin, "shop", "flowcity.shop.admin", (s, c, l, a) -> {
            if (a.length >= 3 && "open".equalsIgnoreCase(a[0])) {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage("§cJucator offline.");
                    return true;
                }
                open(t, a[2].toLowerCase());
                return true;
            }
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0])) {
                load();
                s.sendMessage("§aShop reincarcat: " + shops.keySet());
                return true;
            }
            s.sendMessage("§7Folosire: /shop open <jucator> <magazin> | /shop reload   (ZNPCs: CONSOLE shop open %player% <magazin>)  Magazine: " + shops.keySet());
            return true;
        }, null);
    }

    @Override
    public void disable() {
        sessions.clear();
        shops.clear();
    }

    private void load() {
        shops.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "shop.yml"));
        radius = Math.max(3, y.getDouble("radius", 12));
        ConfigurationSection ss = y.getConfigurationSection("shops");
        if (ss == null) return;
        for (String key : ss.getKeys(false)) {
            ConfigurationSection sc = ss.getConfigurationSection(key);
            if (sc == null) continue;
            Shop sh = new Shop();
            sh.id = key.toLowerCase();
            sh.title = sc.getString("title", key.toUpperCase());
            for (Map<?, ?> cm : sc.getMapList("categories")) {
                Cat c = new Cat();
                c.name = String.valueOf(cm.get("name"));
                Object its = cm.get("items");
                if (its instanceof List) {
                    for (Object o : (List<?>) its) {
                        if (!(o instanceof Map)) continue;
                        Map<?, ?> m = (Map<?, ?>) o;
                        Item it = new Item();
                        it.name = String.valueOf(m.get("name"));
                        it.item = m.get("item") == null ? "minecraft:stone" : String.valueOf(m.get("item"));
                        it.price = m.get("price") instanceof Number ? ((Number) m.get("price")).doubleValue() : 0;
                        it.amount = m.get("amount") instanceof Number ? Math.max(1, ((Number) m.get("amount")).intValue()) : 1;
                        it.desc = m.get("desc") == null ? "" : String.valueOf(m.get("desc"));
                        Object cmds = m.get("commands");
                        if (cmds instanceof List) for (Object x : (List<?>) cmds) it.commands.add(String.valueOf(x));
                        if (it.price > 0) c.items.add(it);
                    }
                }
                if (!c.items.isEmpty()) sh.cats.add(c);
            }
            shops.put(sh.id, sh);
        }
    }

    private void open(Player p, String id) {
        Shop sh = shops.get(id);
        if (sh == null) {
            p.sendMessage("§cMagazin inexistent: " + id);
            return;
        }
        if (!plugin.channel().hasMod(p)) {
            p.sendMessage("§cMagazinul necesita modul FlowCity pe client.");
            return;
        }
        sessions.put(p.getUniqueId(), new Session(sh.id, p.getLocation().clone()));
        JsonObject o = new JsonObject();
        o.addProperty("shop", sh.id);
        o.addProperty("title", sh.title);
        JsonArray cats = new JsonArray();
        for (Cat c : sh.cats) {
            JsonObject co = new JsonObject();
            co.addProperty("name", c.name);
            JsonArray items = new JsonArray();
            for (Item it : c.items) {
                JsonObject io = new JsonObject();
                io.addProperty("name", it.name);
                io.addProperty("item", it.item);
                io.addProperty("price", it.price);
                io.addProperty("amount", it.amount);
                io.addProperty("desc", it.desc);
                items.add(io);
            }
            co.add("items", items);
            cats.add(co);
        }
        o.add("cats", cats);
        send(p, "open", o);
    }

    private void result(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "result", o);
    }

    private void buy(Player p, JsonObject j) {
        Session se = sessions.get(p.getUniqueId());
        if (se == null || System.currentTimeMillis() - se.at > 20 * 60_000L || !se.loc.getWorld().equals(p.getWorld())
            || se.loc.distanceSquared(p.getLocation()) > radius * radius) {
            result(p, false, "Du-te langa vanzator si deschide magazinul din nou.");
            return;
        }
        Shop sh = shops.get(se.shop);
        int ci = integer(j, "cat", -1);
        int ii = integer(j, "idx", -1);
        int qty = Math.max(1, Math.min(64, integer(j, "qty", 1)));
        if (sh == null || ci < 0 || ci >= sh.cats.size() || ii < 0 || ii >= sh.cats.get(ci).items.size()) {
            result(p, false, "Articol invalid.");
            return;
        }
        final Item it = sh.cats.get(ci).items.get(ii);
        final int q = qty;
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, q + " x " + it.name, it.price * q, () -> {
            if (it.commands.isEmpty() && it.item.contains(":") && !it.item.startsWith("minecraft:")) {
                // item de mod: verificam ca exista (pe Arclight apare ca Material)
                if (Material.matchMaterial(it.item.replace(':', '_').toUpperCase()) == null) {
                    result(p, false, "Articolul nu e disponibil pe server (" + it.item + ").");
                    return false;
                }
            }
            if (it.commands.isEmpty()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + p.getName() + " " + it.item + " " + (it.amount * q));
            } else {
                for (int n = 0; n < q; n++) {
                    for (String c : it.commands) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            c.replace("%player%", p.getName()).replace("%amount%", String.valueOf(it.amount)));
                    }
                }
            }
            result(p, true, "Ai cumparat " + q + " x " + it.name + ".");
            return true;
        }, msg -> result(p, false, msg));
    }
}
