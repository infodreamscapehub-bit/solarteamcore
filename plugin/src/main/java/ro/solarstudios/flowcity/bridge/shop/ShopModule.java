package ro.solarstudios.flowcity.bridge.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import ro.solarstudios.flowcity.bridge.permits.PermitsModule;

/**
 * E-shop (arme, orice): se deschide de la un NPC cu "shop open <jucator> <magazin>" (Citizens: /npc command add shop open <p> <magazin> -c). Continutul sta in
 * shop.yml. Articolele pot cere permis ("requires: port_arma", verificat pe server) sau pot vinde un permis ("permit: port_arma", vezi PermitsModule). Plata trece prin PayModule (cash sau card), apoi itemele se dau cu "give" din consola (sau cu comenzile din
 * articol). Canal "shop": server -> client: open, update, result, editres; client -> server: buy, edit.
 *
 * Editor in meniu: cine are permisiunea flowcity.shop.admin (implicit op) primeste in "open" flag-ul admin si poate adauga/edita/sterge
 * articole si categorii direct din interfata (mesajul "edit"). Serverul RE-VERIFICA permisiunea la fiecare operatie, valideaza datele si
 * salveaza magazinul modificat in shop-data.yml (suprascrie magazinul cu acelasi id din shop.yml, care ramane neatins, cu comentarii cu tot).
 * Comenzile unui articol (rulate din consola) pot fi setate doar de op (sau cu flowcity.shop.commands).
 */
public final class ShopModule extends JsonModule {
    private static final class Item {
        String name;
        String item;
        double price;
        int amount;
        String desc;
        List<String> commands = new ArrayList<>();
        List<String> requires = new ArrayList<>();
        String permit = "";
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

    private static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final int MAX_CATS = 8;
    private static final int MAX_ITEMS = 200;

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
        on("edit", this::edit);
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
                s.sendMessage("§aShop reincarcat (shop.yml + shop-data.yml): " + shops.keySet());
                return true;
            }
            s.sendMessage("§7Folosire: /shop open <jucator> <magazin> | /shop reload   (Citizens: /npc command add shop open <p> <magazin> -c)  Magazine: " + shops.keySet());
            return true;
        }, null);
    }

    @Override
    public void disable() {
        sessions.clear();
        shops.clear();
    }

    private File dataFile() {
        return new File(plugin.getDataFolder(), "shop-data.yml");
    }

    private void load() {
        shops.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "shop.yml"));
        radius = Math.max(3, y.getDouble("radius", 12));
        readShops(y);
        File df = dataFile();
        if (df.exists()) readShops(YamlConfiguration.loadConfiguration(df)); // magazinele editate din joc suprascriu pe cele din shop.yml
    }

    private void readShops(YamlConfiguration y) {
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
                        PermitsModule pm = PermitsModule.instance;
                        if (m.get("permit") != null) {
                            it.permit = PermitsModule.norm(String.valueOf(m.get("permit")));
                            PermitsModule.Def d = pm == null ? null : pm.def(it.permit);
                            if (d == null) {
                                plugin.getLogger().warning("shop.yml: permis necunoscut '" + it.permit + "' (vezi permits.yml)");
                                continue;
                            }
                            it.name = m.get("name") == null ? d.name : String.valueOf(m.get("name"));
                            it.item = m.get("item") == null ? d.icon : String.valueOf(m.get("item"));
                            it.price = m.get("price") instanceof Number ? ((Number) m.get("price")).doubleValue() : d.price;
                            it.desc = m.get("desc") == null ? d.desc : String.valueOf(m.get("desc"));
                            it.amount = 1;
                        } else {
                            it.name = String.valueOf(m.get("name"));
                            it.item = m.get("item") == null ? "minecraft:stone" : String.valueOf(m.get("item"));
                            it.price = m.get("price") instanceof Number ? ((Number) m.get("price")).doubleValue() : 0;
                            it.amount = m.get("amount") instanceof Number ? Math.max(1, ((Number) m.get("amount")).intValue()) : 1;
                            it.desc = m.get("desc") == null ? "" : String.valueOf(m.get("desc"));
                            Object cmds = m.get("commands");
                            if (cmds instanceof List) for (Object x : (List<?>) cmds) it.commands.add(String.valueOf(x));
                        }
                        Object rq = m.get("requires");
                        if (rq instanceof List) {
                            for (Object x : (List<?>) rq) it.requires.add(PermitsModule.norm(String.valueOf(x)));
                        } else if (rq != null) {
                            for (String x : String.valueOf(rq).split("[,; ]+")) if (!x.isEmpty()) it.requires.add(PermitsModule.norm(x));
                        }
                        if (it.price > 0) c.items.add(it);
                    }
                }
                sh.cats.add(c); // si categoriile goale: indexul lor conteaza pentru buy/edit (clientul le ascunde de cumparatori)
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
        JsonObject o = payload(p, sh);
        addState(p, o);
        send(p, "open", o);
    }

    private boolean canEdit(Player p) {
        return p.hasPermission("flowcity.shop.admin");
    }

    private boolean canCmd(Player p) {
        return p.isOp() || p.hasPermission("flowcity.shop.commands");
    }

    /** Continutul magazinului pentru un jucator. Categoriile au "ci" = indexul real (buy/edit il folosesc); cumparatorii nu vad categorii goale. */
    private JsonObject payload(Player p, Shop sh) {
        boolean admin = canEdit(p);
        boolean cmd = admin && canCmd(p);
        JsonObject o = new JsonObject();
        o.addProperty("shop", sh.id);
        o.addProperty("title", sh.title);
        if (admin) {
            o.addProperty("admin", true);
            o.addProperty("cmds", cmd);
            JsonArray pl = new JsonArray();
            PermitsModule pm = PermitsModule.instance;
            if (pm != null) {
                for (PermitsModule.Def d : pm.defs()) {
                    JsonObject po = new JsonObject();
                    po.addProperty("id", d.id);
                    po.addProperty("name", d.name);
                    pl.add(po);
                }
            }
            o.add("permits", pl);
        }
        JsonArray cats = new JsonArray();
        for (int ci = 0; ci < sh.cats.size(); ci++) {
            Cat c = sh.cats.get(ci);
            if (!admin && c.items.isEmpty()) continue;
            JsonObject co = new JsonObject();
            co.addProperty("ci", ci);
            co.addProperty("name", c.name);
            JsonArray items = new JsonArray();
            for (Item it : c.items) {
                JsonObject io = new JsonObject();
                io.addProperty("name", it.name);
                io.addProperty("item", it.item);
                io.addProperty("price", it.price);
                io.addProperty("amount", it.amount);
                io.addProperty("desc", it.desc);
                if (!it.permit.isEmpty()) io.addProperty("permit", it.permit);
                if (!it.requires.isEmpty()) {
                    JsonArray rq = new JsonArray();
                    JsonArray rn = new JsonArray();
                    for (String r : it.requires) {
                        rq.add(new com.google.gson.JsonPrimitive(r));
                        rn.add(new com.google.gson.JsonPrimitive(permitName(r)));
                    }
                    io.add("req", rq);
                    io.add("reqn", rn);
                }
                if (cmd && !it.commands.isEmpty()) {
                    JsonArray ca = new JsonArray();
                    for (String c2 : it.commands) ca.add(new com.google.gson.JsonPrimitive(c2));
                    io.add("cmd", ca);
                }
                items.add(io);
            }
            co.add("items", items);
            cats.add(co);
        }
        o.add("cats", cats);
        return o;
    }

    /** Retrimite magazinul tuturor celor care il au deschis (dupa o editare). */
    private void sendUpdate(Shop sh) {
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (!sh.id.equals(e.getValue().shop)) continue;
            Player t = Bukkit.getPlayer(e.getKey());
            if (t == null || !t.isOnline()) continue;
            JsonObject o = payload(t, sh);
            addState(t, o);
            send(t, "update", o);
        }
    }

    private static String permitName(String id) {
        PermitsModule pm = PermitsModule.instance;
        return pm == null ? id : pm.nameOf(id);
    }

    /** Solduri (cash/card) si permisele jucatorului, pentru meniu. */
    private void addState(Player p, JsonObject o) {
        o.addProperty("cash", plugin.money().balance(p.getUniqueId()));
        o.addProperty("bank", plugin.bank().balance(p.getUniqueId()));
        JsonArray have = new JsonArray();
        PermitsModule pm = PermitsModule.instance;
        if (pm != null) for (String id : pm.held(p)) have.add(new com.google.gson.JsonPrimitive(id));
        o.add("have", have);
    }

    private void sendState(Player p) {
        JsonObject o = new JsonObject();
        addState(p, o);
        send(p, "state", o);
    }

    /** Motivul pentru care jucatorul nu poate cumpara articolul (permis lipsa / permis deja detinut) sau null. */
    private String blocked(Player p, Item it) {
        PermitsModule pm = PermitsModule.instance;
        for (String r : it.requires) {
            if (pm == null || !pm.has(p, r)) {
                return "Ai nevoie de permisul \"" + permitName(r) + "\" ca sa cumperi asta. Il obtii de la " + (pm == null ? "Politie" : pm.issuerOf(r))
                    + " sau din magazinul de permise.";
            }
        }
        if (!it.permit.isEmpty() && pm != null && pm.has(p, it.permit)) return "Ai deja permisul \"" + permitName(it.permit) + "\".";
        return null;
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
        final int q = it.permit.isEmpty() ? qty : 1;
        String why = blocked(p, it);
        if (why != null) {
            result(p, false, why);
            return;
        }
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, q + " x " + it.name, it.price * q, () -> {
            // reverificare dupa plata (permisul se poate fi schimbat intre timp): banii se intorc daca nu mai e permis
            String again = blocked(p, it);
            if (again != null) {
                result(p, false, again);
                return false;
            }
            if (!it.permit.isEmpty()) {
                if (PermitsModule.instance == null || !PermitsModule.instance.grant(p, it.permit, "magazin")) {
                    result(p, false, "Permisul nu poate fi acordat acum.");
                    return false;
                }
                result(p, true, "Ai obtinut permisul: " + it.name + ".");
                sendState(p);
                return true;
            }
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
            ro.solarstudios.flowcity.bridge.battlepass.BattlepassModule.bought(p, q);
            sendState(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    // ------------------------------------------------------------------ editor (doar flowcity.shop.admin)

    private void editRes(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "editres", o);
    }

    private static String clean(String s, int max) {
        String r = s == null ? "" : s.replaceAll("[\\p{Cntrl}\u00a7]", "").trim();
        return r.length() > max ? r.substring(0, max).trim() : r;
    }

    private static boolean itemExists(String id) {
        String key = id.startsWith("minecraft:") ? id.substring(10) : id.replace(':', '_');
        return Material.matchMaterial(key.toUpperCase(Locale.ROOT)) != null;
    }

    private static List<String> strings(JsonObject j, String key) {
        List<String> r = new ArrayList<>();
        if (j.has(key) && j.get(key).isJsonArray()) {
            for (com.google.gson.JsonElement e : j.getAsJsonArray(key)) {
                if (e.isJsonPrimitive()) r.add(e.getAsString());
            }
        }
        return r;
    }

    /** Valideaza si aplica campurile unui articol. Intoarce mesajul de eroare sau null. */
    private String readItem(Player p, JsonObject j, Item it) {
        String name = clean(str(j, "name", ""), 40);
        if (name.isEmpty()) return "Numele articolului lipseste.";
        double price = num(j, "price", -1);
        if (!(price > 0) || price > 10_000_000d) return "Pret invalid (trebuie sa fie mai mare ca 0).";
        price = Math.round(price * 100d) / 100d;
        String desc = clean(str(j, "desc", ""), 80);
        PermitsModule pm = PermitsModule.instance;
        List<String> req = new ArrayList<>();
        for (String r : strings(j, "req")) {
            String id = PermitsModule.norm(r);
            if (id.isEmpty() || req.contains(id)) continue;
            if (pm == null || pm.def(id) == null) return "Permis necunoscut: " + id + " (vezi permits.yml).";
            req.add(id);
        }
        if (req.size() > 4) return "Cel mult 4 permise necesare.";
        boolean permitItem = !it.permit.isEmpty();
        int amount = it.amount;
        String item = it.item;
        List<String> cmds = new ArrayList<>(it.commands);
        if (!permitItem) {
            amount = integer(j, "amount", 1);
            if (amount < 1 || amount > 999) return "Cantitate invalida (1-999).";
            if (canCmd(p)) {
                cmds.clear();
                for (String c : strings(j, "cmd")) {
                    String cc = clean(c, 120);
                    if (cc.startsWith("/")) cc = cc.substring(1).trim();
                    if (!cc.isEmpty()) cmds.add(cc);
                }
                if (cmds.size() > 5) return "Cel mult 5 comenzi.";
            } else if (!strings(j, "cmd").isEmpty() && !strings(j, "cmd").equals(it.commands)) {
                return "Doar op-ii pot seta comenzi pe articole.";
            }
            item = str(j, "item", "").trim().toLowerCase(Locale.ROOT);
            if (item.length() > 80 || !ITEM_ID.matcher(item).matches()) return "Id item invalid (ex. minecraft:bread sau tac:ak47).";
            if (cmds.isEmpty() && !itemExists(item)) return "Itemul " + item + " nu exista pe server (verifica cu /give).";
        }
        it.name = name;
        it.price = price;
        it.desc = desc;
        it.requires = req;
        it.amount = amount;
        it.item = item;
        it.commands = cmds;
        return null;
    }

    private void edit(Player p, JsonObject j) {
        if (!canEdit(p)) {
            editRes(p, false, "Nu ai permisiunea de a edita magazinul.");
            return;
        }
        Session se = sessions.get(p.getUniqueId());
        Shop sh = se == null ? null : shops.get(se.shop);
        if (sh == null) {
            editRes(p, false, "Deschide magazinul din nou.");
            return;
        }
        String op = str(j, "op", "");
        int ci = integer(j, "cat", -1);
        int ii = integer(j, "idx", -1);
        boolean catOk = ci >= 0 && ci < sh.cats.size();
        String log;
        switch (op) {
            case "addCat": {
                String n = clean(str(j, "name", ""), 24);
                if (n.isEmpty()) {
                    editRes(p, false, "Numele categoriei lipseste.");
                    return;
                }
                if (sh.cats.size() >= MAX_CATS) {
                    editRes(p, false, "Cel mult " + MAX_CATS + " categorii.");
                    return;
                }
                Cat c = new Cat();
                c.name = n;
                sh.cats.add(c);
                log = "categorie noua \"" + n + "\"";
                break;
            }
            case "renCat": {
                String n = clean(str(j, "name", ""), 24);
                if (!catOk || n.isEmpty()) {
                    editRes(p, false, "Categorie sau nume invalid.");
                    return;
                }
                log = "categorie redenumita \"" + sh.cats.get(ci).name + "\" -> \"" + n + "\"";
                sh.cats.get(ci).name = n;
                break;
            }
            case "delCat": {
                if (!catOk) {
                    editRes(p, false, "Categorie invalida.");
                    return;
                }
                Cat c = sh.cats.remove(ci);
                log = "categorie stearsa \"" + c.name + "\" (" + c.items.size() + " articole)";
                break;
            }
            case "addItem": {
                if (!catOk) {
                    editRes(p, false, "Categorie invalida.");
                    return;
                }
                Cat c = sh.cats.get(ci);
                if (c.items.size() >= MAX_ITEMS) {
                    editRes(p, false, "Prea multe articole in categorie.");
                    return;
                }
                Item it = new Item();
                String err = readItem(p, j, it);
                if (err != null) {
                    editRes(p, false, err);
                    return;
                }
                c.items.add(it);
                log = "articol adaugat in \"" + c.name + "\": " + describe(it);
                break;
            }
            case "setItem": {
                if (!catOk || ii < 0 || ii >= sh.cats.get(ci).items.size()) {
                    editRes(p, false, "Articol invalid.");
                    return;
                }
                Item it = sh.cats.get(ci).items.get(ii);
                String before = describe(it);
                String err = readItem(p, j, it);
                if (err != null) {
                    editRes(p, false, err);
                    return;
                }
                log = "articol modificat in \"" + sh.cats.get(ci).name + "\": " + before + " -> " + describe(it);
                break;
            }
            case "delItem": {
                if (!catOk || ii < 0 || ii >= sh.cats.get(ci).items.size()) {
                    editRes(p, false, "Articol invalid.");
                    return;
                }
                Item it = sh.cats.get(ci).items.remove(ii);
                log = "articol sters din \"" + sh.cats.get(ci).name + "\": " + describe(it);
                break;
            }
            default:
                editRes(p, false, "Operatie necunoscuta.");
                return;
        }
        try {
            save(sh);
        } catch (IOException ex) {
            plugin.getLogger().warning("[shop] nu pot salva shop-data.yml: " + ex);
            load();
            editRes(p, false, "Nu am putut salva (vezi consola). Modificarea nu s-a aplicat.");
            sendUpdateReloaded();
            return;
        }
        plugin.getLogger().info("[shop] " + p.getName() + " (" + sh.id + "): " + log);
        editRes(p, true, "Salvat.");
        sendUpdate(sh);
    }

    private static String describe(Item it) {
        return "\"" + it.name + "\" " + (it.permit.isEmpty() ? it.item + " x" + it.amount : "permis " + it.permit) + " pret " + it.price
            + (it.requires.isEmpty() ? "" : " requires " + it.requires) + (it.commands.isEmpty() ? "" : " commands " + it.commands);
    }

    /** Dupa un reload fortat: retrimite fiecarui jucator cu sesiune magazinul lui (daca mai exista). */
    private void sendUpdateReloaded() {
        for (Shop sh : new ArrayList<>(shops.values())) sendUpdate(sh);
    }

    /** Scrie magazinul in shop-data.yml (shop.yml ramane neatins, cu comentarii). */
    private void save(Shop sh) throws IOException {
        File f = dataFile();
        YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        y.options().header("Magazine editate din joc. Suprascriu magazinul cu acelasi id din shop.yml. Nu edita cat ruleaza serverul.");
        String base = "shops." + sh.id;
        y.set(base, null);
        y.set(base + ".title", sh.title);
        List<Map<String, Object>> cats = new ArrayList<>();
        for (Cat c : sh.cats) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.name);
            List<Map<String, Object>> its = new ArrayList<>();
            for (Item it : c.items) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("name", it.name);
                if (!it.permit.isEmpty()) {
                    im.put("permit", it.permit);
                } else {
                    im.put("item", it.item);
                    im.put("amount", it.amount);
                }
                im.put("price", it.price);
                if (!it.desc.isEmpty()) im.put("desc", it.desc);
                if (!it.commands.isEmpty()) im.put("commands", new ArrayList<>(it.commands));
                if (!it.requires.isEmpty()) im.put("requires", new ArrayList<>(it.requires));
                its.add(im);
            }
            cm.put("items", its);
            cats.add(cm);
        }
        y.set(base + ".categories", cats);
        y.save(f);
    }
}
