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
