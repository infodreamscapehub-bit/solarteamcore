package ro.solarstudios.flowcity.bridge.cars;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Dealerul auto (partea de plugin): catalogul din hype.yml (sectiunea "dealership") si plata prin Vault.
 * Dupa plata ruleaza comanda modului "fccars give ..." din consola; masina ajunge in garajul jucatorului si e
 * inregistrata in MDT cu numarul generat. Daca comanda nu exista (mod lipsa), banii se returneaza.
 * Canal "cars": client -> server: catalog, buy; server -> client: catalog, buy_result.
 */
public final class CarsModule extends JsonModule {
    private static final SecureRandom RNG = new SecureRandom();
    private YamlConfiguration cfg;
    private final Map<UUID, Long> lastBuy = new HashMap<>();

    /** Sesiune de dealer: deschisa de un NPC (ZNPCs/Citizens) cu comanda "dealer open <jucator>". Cumparatul merge doar langa NPC. */
    private static final class Session {
        final Location loc;
        final long at;

        Session(Location loc, long at) {
            this.loc = loc;
            this.at = at;
        }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();

    @Override
    public String id() {
        return "cars";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "hype");
        loadStock();
        long every = 20L * 60 * Math.max(1, cfg.getInt("dealership.restock-minutes", 30));
        Bukkit.getScheduler().runTaskTimer(plugin, this::restock, every, every);
        on("catalog", (p, j) -> send(p, "catalog", catalog(p)));
        on("buy", this::buy);
        // blocul de dealer din mod: mergem la plugin, care decide (implicit dealerul merge doar prin NPC)
        on("dealer_block", (p, j) -> {
            if (cfg.getBoolean("dealership.allow-block", false) && blockNear(p)) openDealer(p);
            else plugin.channel().toast(p, "Dealerul functioneaza prin vanzator: vorbeste cu NPC-ul.", true);
        });
        // actiunea NPC-ului (ZNPCs: CONSOLE "dealer open %player%"; Citizens: /npc command add dealer open <p> -c)
        Commands.register(plugin, "dealer", "flowcity.dealer.admin", (s, c, l, a) -> {
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage("\u00a7cJucator offline.");
                    return true;
                }
                openDealer(t);
                return true;
            }
            if (a.length >= 1 && "stock".equalsIgnoreCase(a[0])) {
                ConfigurationSection vs = vehicles();
                if (vs == null) return true;
                if (a.length == 1) {
                    for (String key : vs.getKeys(false)) {
                        ConfigurationSection v = vs.getConfigurationSection(key);
                        if (v == null) continue;
                        int st = stockOf(key, v);
                        s.sendMessage("\u00a77" + key + ": \u00a7f" + (st < 0 ? "nelimitat" : st + "/" + maxStock(v)));
                    }
                    return true;
                }
                ConfigurationSection v = vs.getConfigurationSection(a[1]);
                if (v == null || a.length < 3) {
                    s.sendMessage("\u00a77Folosire: /dealer stock [<vehicul> <numar>]");
                    return true;
                }
                try {
                    int n = Math.max(0, Integer.parseInt(a[2]));
                    stockCfg.set(a[1], n);
                    saveStock();
                    s.sendMessage("\u00a7aStoc " + a[1] + " = " + n);
                } catch (NumberFormatException ex) {
                    s.sendMessage("\u00a7cNumar invalid.");
                }
                return true;
            }
            if (a.length == 0 && s instanceof Player) {
                openDealer((Player) s);
                return true;
            }
            s.sendMessage("\u00a77Folosire: /dealer open <jucator> | /dealer stock [<vehicul> <numar>]  (actiune NPC in ZNPCs: CONSOLE dealer open %player%)");
            return true;
        }, null, "dealership");
        on("tune", this::tune);
        Commands.register(plugin, "tuning", "flowcity.dealer.admin", (s, c, l, a) -> {
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage("\u00a7cJucator offline.");
                    return true;
                }
                openTuning(t);
                return true;
            }
            if (a.length == 0 && s instanceof Player) {
                openTuning((Player) s);
                return true;
            }
            s.sendMessage("\u00a77Folosire: /tuning open <jucator>  (actiune NPC in ZNPCs: CONSOLE tuning open %player%)");
            return true;
        }, null);
        on("pound_info", (p, j) -> {
            JsonObject o = new JsonObject();
            o.addProperty("fee", poundFee());
            o.addProperty("money", plugin.money().balance(p.getUniqueId()));
            send(p, "pound_info", o);
        });
        on("pound_pay", this::poundPay);
        on("parking_fine", this::parkingFine);
        on("impound", this::impound);
        on("fuel_info", (p, j) -> {
            JsonObject o = new JsonObject();
            o.addProperty("price", fuelPrice());
            o.addProperty("money", plugin.money().balance(p.getUniqueId()));
            send(p, "fuel_info", o);
        });
        on("fuel_buy", this::fuelBuy);
        // tag-urile de factiune pentru garaje: politistii din MDT primesc fc_politie, restul dupa permisiuni
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncFactions, 100L, 400L);
    }

    private double poundFee() {
        return Math.max(0, cfg.getDouble("garages.pound-fee", 500));
    }

    private boolean console(String cmd) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void syncFactions() {
        java.util.List<String> extra = cfg.getStringList("garages.factions");
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean cop = MdtModule.instance != null && MdtModule.instance.isOfficer(p);
            setTag(p, "politie", cop || p.hasPermission("flowcity.faction.politie"));
            for (String f : extra) {
                String fac = f.toLowerCase(java.util.Locale.ROOT);
                if (!fac.equals("politie")) setTag(p, fac, p.hasPermission("flowcity.faction." + fac));
            }
        }
    }

    private void setTag(Player p, String faction, boolean on) {
        String tag = "fc_" + faction.replaceAll("[^a-z0-9_]", "");
        if (on == p.getScoreboardTags().contains(tag)) return;
        if (on) p.addScoreboardTag(tag);
        else p.removeScoreboardTag(tag);
    }

    private void poundPay(Player p, JsonObject j) {
        long now = System.currentTimeMillis();
        if (now - lastBuy.getOrDefault(p.getUniqueId(), 0L) < 2000) return;
        lastBuy.put(p.getUniqueId(), now);
        int car = (int) num(j, "car", 0);
        int garage = (int) num(j, "garage", 0);
        double fee = poundFee();
        if (car <= 0 || garage <= 0) return;
        PayModule.instance.request(p, "Taxa pound", fee, () -> {
            JsonObject o = new JsonObject();
            boolean done = console("fccars unpound " + p.getName() + " " + car + " " + garage);
            o.addProperty("ok", done);
            o.addProperty("msg", done ? "Ai platit " + fee + " lei. Masina e in parcare." : "Pound-ul nu functioneaza acum. Banii au fost returnati.");
            o.addProperty("money", plugin.money().balance(p.getUniqueId()));
            send(p, "pound_result", o);
            return done;
        }, msg -> {
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("msg", msg);
            o.addProperty("money", plugin.money().balance(p.getUniqueId()));
            send(p, "pound_result", o);
        });
    }

    private double fuelPrice() {
        return Math.max(0, cfg.getDouble("fuel.price-per-liter", 8));
    }

    /** Benzinarie: platesti litrii (cash sau card), apoi modul pune benzina in masina (fccars fuel). */
    private void fuelBuy(Player p, JsonObject j) {
        long now = System.currentTimeMillis();
        if (now - lastBuy.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
        lastBuy.put(p.getUniqueId(), now);
        int station = (int) num(j, "station", 0);
        int entity = (int) num(j, "entity", 0);
        double liters = Math.max(0, Math.min(200, num(j, "liters", 0)));
        liters = Math.round(liters * 10) / 10.0;
        double cost = Math.round(liters * fuelPrice() * 100) / 100.0;
        if (station <= 0 || liters <= 0) {
            fuelReply(p, false, "Alege cati litri vrei.", 0, 0);
            return;
        }
        final double fl = liters;
        PayModule.instance.request(p, "Benzina " + fl + " L", cost, () -> {
            boolean done = console("fccars fuel " + p.getName() + " " + station + " " + entity + " " + (int) Math.round(fl * 1000));
            if (done) fuelReply(p, true, "Ai alimentat " + fl + " L pentru " + cost + " lei.", fl, cost);
            else fuelReply(p, false, "Pompa nu merge acum. Banii au fost returnati.", 0, 0);
            return done;
        }, msg -> fuelReply(p, false, msg, 0, 0));
    }

    private void fuelReply(Player p, boolean ok, String msg, double liters, double cost) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        if (ok) {
            o.addProperty("liters", liters);
            o.addProperty("cost", cost);
        }
        o.addProperty("money", plugin.money().balance(p.getUniqueId()));
        send(p, "fuel_result", o);
    }

    private void police(Player p, boolean ok, String msg, boolean close) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        o.addProperty("close", close);
        send(p, "police_result", o);
    }

    private void parkingFine(Player p, JsonObject j) {
        if (MdtModule.instance == null || !MdtModule.instance.isOfficer(p)) {
            police(p, false, "Doar politia poate da amenzi.", false);
            return;
        }
        int amount = (int) num(j, "amount", 0);
        String reason = str(j, "reason", "Parcare neregulamentara");
        if (amount <= 0 || amount > cfg.getInt("garages.max-parking-fine", 5000)) {
            police(p, false, "Suma nu e permisa.", false);
            return;
        }
        String owner = MdtModule.instance.fineByPlate(p, str(j, "plate", ""), amount, reason);
        if (owner == null) police(p, false, "Numarul nu e inregistrat in MDT.", false);
        else police(p, true, "Amenda de " + amount + " lei a fost trecuta pe numele lui " + owner + ".", false);
    }

    private void impound(Player p, JsonObject j) {
        if (MdtModule.instance == null || !MdtModule.instance.isOfficer(p)) {
            police(p, false, "Doar politia poate ridica vehicule.", false);
            return;
        }
        String entity = str(j, "entity", "");
        if (!entity.matches("[0-9a-fA-F-]{36}")) return;
        String reason = str(j, "reason", "Parcare neregulamentara").replaceAll("[\\r\\n\"]", "");
        boolean ok = console("fccars impound " + p.getName() + " " + entity + " " + reason);
        police(p, ok, ok ? "Vehiculul a fost ridicat la pound." : "Nu am putut ridica vehiculul.", ok);
    }

    private ConfigurationSection vehicles() {
        return cfg.getConfigurationSection("dealership.vehicles");
    }

    private JsonObject catalog(Player p) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        ConfigurationSection s = vehicles();
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection v = s.getConfigurationSection(key);
                if (v == null) continue;
                JsonObject j = new JsonObject();
                j.addProperty("id", v.getString("entity", "vehicle:" + key));
                j.addProperty("key", key);
                j.addProperty("name", v.getString("name", key));
                j.addProperty("category", v.getString("category", "Diverse"));
                j.addProperty("price", v.getDouble("price", 0));
                j.addProperty("desc", v.getString("desc", ""));
                j.addProperty("engine", v.getString("engine", "-"));
                j.addProperty("wheels", v.getString("wheels", "-"));
                j.addProperty("stock", stockOf(key, v));
                j.addProperty("maxStock", Math.max(0, maxStock(v)));
                arr.add(j);
            }
        }
        o.add("vehicles", arr);
        o.add("tuning", tuningTable());
        o.addProperty("money", plugin.money().balance(p.getUniqueId()));
        return o;
    }

    // ------------------------------------------------------------------ tuning

    private static final java.util.regex.Pattern ENGINE = java.util.regex.Pattern.compile("^vehicle:(wood_|stone_|iron_|gold_|diamond_)?(small|large|electric)_engine$");
    private static final String[] TIERS = {"wood", "stone", "iron", "gold", "diamond"};
    private static final double[] TIER_PRICE = {0, 3000, 6000, 12000, 25000};

    /** Tier-ul motorului (0..4) sau -1 pentru motorul simplu fara tier; -2 daca nu e un motor valid. */
    private static int tierOf(String engine) {
        java.util.regex.Matcher m = ENGINE.matcher(engine == null ? "" : engine);
        if (!m.matches()) return -2;
        if (m.group(1) == null) return -1;
        String t = m.group(1).replace("_", "");
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equals(t)) return i;
        return -2;
    }

    private static String family(String engine) {
        return engine.replaceFirst("^vehicle:(wood_|stone_|iron_|gold_|diamond_)", "vehicle:");
    }

    private double tierPrice(int tier) {
        if (tier < 0) return 0;
        return Math.max(0, cfg.getDouble("tuning.engines." + TIERS[tier] + ".price", TIER_PRICE[tier]));
    }

    private double paintPrice() {
        return Math.max(0, cfg.getDouble("tuning.paint-price", 1500));
    }

    private JsonObject tuningTable() {
        JsonObject t = new JsonObject();
        t.addProperty("paint", paintPrice());
        JsonArray engines = new JsonArray();
        for (int i = 0; i < TIERS.length; i++) {
            JsonObject e = new JsonObject();
            e.addProperty("tier", TIERS[i]);
            e.addProperty("price", tierPrice(i));
            e.addProperty("power", cfg.getInt("tuning.engines." + TIERS[i] + ".power", i + 1));
            engines.add(e);
        }
        t.add("engines", engines);
        JsonArray wheels = new JsonArray();
        ConfigurationSection ws = cfg.getConfigurationSection("tuning.wheels");
        if (ws != null) {
            for (String k : ws.getKeys(false)) {
                ConfigurationSection w = ws.getConfigurationSection(k);
                if (w == null || w.getString("id", "").isEmpty()) continue;
                JsonObject j = new JsonObject();
                j.addProperty("id", w.getString("id"));
                j.addProperty("name", w.getString("name", k));
                j.addProperty("price", w.getDouble("price", 0));
                j.addProperty("grip", w.getInt("grip", 3));
                j.addProperty("speed", w.getInt("speed", 3));
                j.addProperty("terrain", w.getInt("terrain", 3));
                wheels.add(j);
            }
        }
        t.add("wheels", wheels);
        return t;
    }

    /** Pretul rotilor (-1 = roti care nu exista in config). */
    private double wheelPrice(String id) {
        ConfigurationSection ws = cfg.getConfigurationSection("tuning.wheels");
        if (ws == null) return -1;
        for (String k : ws.getKeys(false)) {
            ConfigurationSection w = ws.getConfigurationSection(k);
            if (w != null && id.equals(w.getString("id", ""))) return Math.max(0, w.getDouble("price", 0));
        }
        return -1;
    }

    /** Costul trecerii de la un motor la altul: la urcare diferenta de pret, la coborare 0. -1 = schimbare invalida. */
    private double engineCost(String from, String to) {
        if (to.equals(from)) return 0;
        if ("-".equals(from) || "-".equals(to)) return -1;
        int a = tierOf(from);
        int b = tierOf(to);
        if (a == -2 || b == -2 || !family(from).equals(family(to))) return -1;
        return Math.max(0, tierPrice(b) - tierPrice(a));
    }

    private double wheelsCost(String from, String to) {
        if (to.equals(from)) return 0;
        if ("-".equals(from) || "-".equals(to)) return -1;
        return wheelPrice(to);
    }

    private final Map<UUID, Session> tuneSessions = new HashMap<>();

    // ------------------------------------------------------------------ stoc dealer

    private YamlConfiguration stockCfg;
    private java.io.File stockFile;

    private void loadStock() {
        stockFile = new java.io.File(plugin.getDataFolder(), "dealer-stock.yml");
        stockCfg = YamlConfiguration.loadConfiguration(stockFile);
    }

    private void saveStock() {
        try {
            stockCfg.save(stockFile);
        } catch (java.io.IOException ex) {
            plugin.getLogger().warning("Nu pot salva dealer-stock.yml: " + ex);
        }
    }

    /** Stocul maxim al unui vehicul (0 sau mai putin = nelimitat). */
    private int maxStock(ConfigurationSection v) {
        return v.getInt("stock", cfg.getInt("dealership.default-stock", 5));
    }

    /** Stocul curent; -1 = nelimitat. */
    private int stockOf(String key, ConfigurationSection v) {
        int max = maxStock(v);
        if (max <= 0) return -1;
        return Math.max(0, Math.min(max, stockCfg.getInt(key, max)));
    }

    private void restock() {
        int amount = Math.max(0, cfg.getInt("dealership.restock-amount", 1));
        ConfigurationSection s = vehicles();
        if (amount == 0 || s == null) return;
        boolean changed = false;
        for (String key : s.getKeys(false)) {
            ConfigurationSection v = s.getConfigurationSection(key);
            if (v == null) continue;
            int max = maxStock(v);
            if (max <= 0) continue;
            int cur = stockOf(key, v);
            if (cur < max) {
                stockCfg.set(key, Math.min(max, cur + amount));
                changed = true;
            }
        }
        if (changed) saveStock();
    }

    private void openTuning(Player p) {
        if (!plugin.channel().hasMod(p)) {
            p.sendMessage("\u00a7cTuning-ul necesita modul FlowCity pe client.");
            return;
        }
        tuneSessions.put(p.getUniqueId(), new Session(p.getLocation().clone(), System.currentTimeMillis()));
        JsonObject o = tuningTable();
        o.addProperty("money", plugin.money().balance(p.getUniqueId()));
        send(p, "tuning_open", o);
    }

    private boolean nearTuning(Player p) {
        Session s = tuneSessions.get(p.getUniqueId());
        double r = Math.max(3, cfg.getDouble("tuning.npc-radius", 12));
        return s != null && System.currentTimeMillis() - s.at < 15 * 60_000L && s.loc.getWorld() != null
            && s.loc.getWorld().equals(p.getWorld()) && s.loc.distanceSquared(p.getLocation()) <= r * r;
    }

    private void tuneReply(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        o.addProperty("money", plugin.money().balance(p.getUniqueId()));
        send(p, "tune_result", o);
    }

    /** Cerere de tuning pentru o masina parcata. mode = "garage" (mod verifica garajul) sau "npc" (sesiune de la mecanic). */
    private void tune(Player p, JsonObject j) {
        long now = System.currentTimeMillis();
        if (now - lastBuy.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
        lastBuy.put(p.getUniqueId(), now);
        int car = (int) num(j, "car", 0);
        String mode = "npc".equals(str(j, "mode", "")) ? "npc" : "garage";
        String fromEngine = clean(str(j, "fromEngine", "-"));
        String fromWheels = clean(str(j, "fromWheels", "-"));
        String engine = clean(str(j, "engine", "-"));
        String wheels = clean(str(j, "wheels", "-"));
        int fromColor = (int) num(j, "fromColor", 0) & 0xFFFFFF;
        int color = (int) num(j, "color", 0) & 0xFFFFFF;
        if (car <= 0) return;
        if ("npc".equals(mode) && !nearTuning(p)) {
            tuneReply(p, false, "Ai plecat de la mecanic. Vorbeste din nou cu el.");
            return;
        }
        double ce = engineCost(fromEngine, engine);
        double cw = wheelsCost(fromWheels, wheels);
        if (ce < 0 || cw < 0) {
            tuneReply(p, false, "Alegere invalida.");
            return;
        }
        double cost = ce + cw + (color != fromColor ? paintPrice() : 0);
        if (engine.equals(fromEngine) && wheels.equals(fromWheels) && color == fromColor) {
            tuneReply(p, false, "Nu ai schimbat nimic.");
            return;
        }
        final double fcost = cost;
        final String cmd = "fccars tune " + p.getName() + " " + car + " " + mode + " \"" + fromEngine + "\" \"" + fromWheels + "\" " + fromColor + " " + color
            + " \"" + engine + "\" \"" + wheels + "\"";
        PayModule.instance.request(p, "Tuning", fcost, () -> {
            if (!console(cmd)) {
                tuneReply(p, false, "Tuning-ul nu s-a putut face (masina trebuie sa fie parcata in garaj). Banii au fost returnati.");
                return false;
            }
            tuneReply(p, true, "Masina a fost tunata" + (fcost > 0 ? " (" + Math.round(fcost) + " lei)." : "."));
            return true;
        }, msg -> tuneReply(p, false, msg));
    }

    private static String clean(String s) {
        return s.matches("[a-z0-9_:\\-]{1,64}") ? s : "-";
    }

    private void openDealer(Player p) {
        if (!plugin.channel().hasMod(p)) {
            p.sendMessage("\u00a7cDealerul necesita modul FlowCity pe client.");
            return;
        }
        sessions.put(p.getUniqueId(), new Session(p.getLocation().clone(), System.currentTimeMillis()));
        send(p, "dealer_open", new JsonObject());
    }

    /** Are voie sa cumpere: a deschis dealerul de la un NPC si e inca langa el (sau, daca e permis, langa blocul de dealer). */
    private boolean nearDealer(Player p) {
        Session s = sessions.get(p.getUniqueId());
        double r = Math.max(3, cfg.getDouble("dealership.npc-radius", 12));
        if (s != null && System.currentTimeMillis() - s.at < 15 * 60_000L && s.loc.getWorld() != null
            && s.loc.getWorld().equals(p.getWorld()) && s.loc.distanceSquared(p.getLocation()) <= r * r) return true;
        return cfg.getBoolean("dealership.allow-block", false) && blockNear(p);
    }

    private boolean blockNear(Player p) {
        int r = Math.max(2, Math.min(12, cfg.getInt("dealership.radius", 8)));
        Location l = p.getLocation();
        for (int x = -r; x <= r; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -r; z <= r; z++) {
                    if (l.getWorld().getBlockAt(l.getBlockX() + x, l.getBlockY() + y, l.getBlockZ() + z).getType().name().endsWith("DEALER_AUTO")) return true;
                }
            }
        }
        return false;
    }

    private String newPlate() {
        String letters = "ABCDEFGHJKLMNPRSTUVWXYZ";
        MdtModule mdt = MdtModule.instance;
        for (int i = 0; i < 50; i++) {
            String plate = "FC-" + (10 + RNG.nextInt(90)) + "-" + letters.charAt(RNG.nextInt(letters.length()))
                + letters.charAt(RNG.nextInt(letters.length())) + letters.charAt(RNG.nextInt(letters.length()));
            if (mdt == null || !mdt.plateTaken(plate)) return plate;
        }
        return "FC-" + (100 + RNG.nextInt(900)) + "-" + letters.charAt(RNG.nextInt(letters.length()));
    }

    private void reply(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        o.addProperty("money", plugin.money().balance(p.getUniqueId()));
        send(p, "buy_result", o);
    }

    private void buy(Player p, JsonObject j) {
        long now = System.currentTimeMillis();
        if (now - lastBuy.getOrDefault(p.getUniqueId(), 0L) < 2000) return;
        lastBuy.put(p.getUniqueId(), now);
        String id = str(j, "id", "");
        int color = (int) num(j, "color", 0xF2F2F0) & 0xFFFFFF;
        ConfigurationSection s = vehicles();
        ConfigurationSection v = null;
        String vKey = "";
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(key);
                if (c != null && c.getString("entity", "vehicle:" + key).equals(id)) {
                    v = c;
                    vKey = key;
                }
            }
        }
        if (v == null) {
            reply(p, false, "Vehiculul nu e de vanzare.");
            return;
        }
        if (!nearDealer(p)) {
            reply(p, false, "Ai plecat de la dealer. Vorbeste din nou cu vanzatorul.");
            return;
        }
        int stock = stockOf(vKey, v);
        if (stock == 0) {
            reply(p, false, "Stoc epuizat. Revino mai tarziu, dealerul se reaproviziona.");
            send(p, "catalog", catalog(p));
            return;
        }
        double price = v.getDouble("price", 0);
        String baseEngine = v.getString("engine", "-");
        String baseWheels = v.getString("wheels", "-");
        String engine = clean(str(j, "engine", baseEngine));
        String wheels = clean(str(j, "wheels", baseWheels));
        double ce = engineCost(baseEngine, engine);
        double cw = wheelsCost(baseWheels, wheels);
        if (ce < 0 || cw < 0) {
            reply(p, false, "Optiuni de tuning invalide.");
            return;
        }
        price += ce + cw;
        final String name = v.getString("name", id).replaceAll("[^A-Za-z0-9 ._-]", "");
        final String fKey = vKey;
        final int fStock = stock;
        final String fEngine = engine;
        final String fWheels = wheels;
        PayModule.instance.request(p, name, price, () -> {
            if (fStock > 0) {
                stockCfg.set(fKey, fStock - 1);
                saveStock();
            }
            String plate = newPlate();
            String cmd = "fccars give " + p.getName() + " \"" + id + "\" " + color + " \"" + fEngine + "\" \""
                + fWheels + "\" \"" + plate + "\" " + name;
            boolean done;
            try {
                done = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                done = false;
            }
            if (!done) {
                if (fStock > 0) {
                    stockCfg.set(fKey, fStock);
                    saveStock();
                }
                reply(p, false, "Dealerul nu functioneaza acum (mod lipsa pe server). Banii au fost returnati.");
                return false;
            }
            if (MdtModule.instance != null) MdtModule.instance.registerVehicle(p, name, plate);
            reply(p, true, "Felicitari! " + name + " (" + plate + ") te asteapta la garaj.");
            send(p, "catalog", catalog(p));
            return true;
        }, msg -> reply(p, false, msg));
    }
}
