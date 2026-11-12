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
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;
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

    @Override
    public String id() {
        return "cars";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "hype");
        on("catalog", (p, j) -> send(p, "catalog", catalog(p)));
        on("buy", this::buy);
        on("pound_info", (p, j) -> {
            JsonObject o = new JsonObject();
            o.addProperty("fee", poundFee());
            o.addProperty("money", plugin.money().balance(p.getUniqueId()));
            send(p, "pound_info", o);
        });
        on("pound_pay", this::poundPay);
        on("parking_fine", this::parkingFine);
        on("impound", this::impound);
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
        UUID u = p.getUniqueId();
        JsonObject o = new JsonObject();
        if (car <= 0 || garage <= 0 || !plugin.money().has(u, fee) || !plugin.money().withdraw(u, fee)) {
            o.addProperty("ok", false);
            o.addProperty("msg", "Nu ai destui bani pentru taxa de pound.");
        } else if (!console("fccars unpound " + p.getName() + " " + car + " " + garage)) {
            plugin.money().deposit(u, fee);
            o.addProperty("ok", false);
            o.addProperty("msg", "Pound-ul nu functioneaza acum. Banii au fost returnati.");
        } else {
            o.addProperty("ok", true);
            o.addProperty("msg", "Ai platit " + fee + " lei. Masina e in parcare.");
        }
        o.addProperty("money", plugin.money().balance(u));
        send(p, "pound_result", o);
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
                arr.add(j);
            }
        }
        o.add("vehicles", arr);
        o.addProperty("money", plugin.money().balance(p.getUniqueId()));
        return o;
    }

    private boolean nearDealer(Player p) {
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
        if (s != null) {
            for (String key : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(key);
                if (c != null && c.getString("entity", "vehicle:" + key).equals(id)) v = c;
            }
        }
        if (v == null) {
            reply(p, false, "Vehiculul nu e de vanzare.");
            return;
        }
        if (!nearDealer(p)) {
            reply(p, false, "Trebuie sa fii la dealer.");
            return;
        }
        double price = v.getDouble("price", 0);
        UUID u = p.getUniqueId();
        if (!plugin.money().has(u, price) || !plugin.money().withdraw(u, price)) {
            reply(p, false, "Nu ai destui bani.");
            return;
        }
        String name = v.getString("name", id).replaceAll("[^A-Za-z0-9 ._-]", "");
        String plate = newPlate();
        String cmd = "fccars give " + p.getName() + " \"" + id + "\" " + color + " \"" + v.getString("engine", "-") + "\" \""
            + v.getString("wheels", "-") + "\" \"" + plate + "\" " + name;
        boolean done;
        try {
            done = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (RuntimeException ex) {
            done = false;
        }
        if (!done) {
            plugin.money().deposit(u, price);
            reply(p, false, "Dealerul nu functioneaza acum (mod lipsa pe server). Banii au fost returnati.");
            return;
        }
        if (MdtModule.instance != null) MdtModule.instance.registerVehicle(p, name, plate);
        reply(p, true, "Felicitari! " + name + " (" + plate + ") te asteapta la garaj.");
    }
}
