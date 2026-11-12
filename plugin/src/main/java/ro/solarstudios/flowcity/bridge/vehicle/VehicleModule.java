package ro.solarstudios.flowcity.bridge.vehicle;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Vehicule (partea de plugin): plata la pompa de benzina. Masina e in modul Forge; dupa plata, pluginul ruleaza
 * comanda modului "fcveh fuel ..." din consola. Daca comanda nu exista (mod lipsa pe server), banii se returneaza.
 * Config: sectiunea "vehicles" din hype.yml (fuel-price, pump-radius).
 * Canal "vehicle": client -> server: pump, fuel_buy; server -> client: pump, fuel_result.
 */
public final class VehicleModule extends JsonModule {
    private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F-]{36}");
    private YamlConfiguration cfg;
    private final Map<UUID, Long> lastBuy = new HashMap<>();

    @Override
    public String id() {
        return "vehicle";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "hype");
        on("pump", (p, j) -> send(p, "pump", state(p)));
        on("fuel_buy", this::buy);
    }

    private double price() {
        return Math.max(0, cfg.getDouble("vehicles.fuel-price", 7.0));
    }

    private JsonObject state(Player p) {
        JsonObject o = new JsonObject();
        o.addProperty("price", price());
        o.addProperty("cash", plugin.money().balance(p.getUniqueId()));
        o.addProperty("bank", plugin.bank().balance(p.getUniqueId()));
        return o;
    }

    /** Jucatorul sta langa o pompa? (blocul modului apare in Bukkit ca FLOWCITYCORE_POMPA_BENZINA) */
    private boolean nearPump(Player p) {
        int r = Math.max(2, Math.min(10, cfg.getInt("vehicles.pump-radius", 6)));
        Location l = p.getLocation();
        for (int x = -r; x <= r; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = l.getWorld().getBlockAt(l.getBlockX() + x, l.getBlockY() + y, l.getBlockZ() + z);
                    if (b.getType().name().endsWith("POMPA_BENZINA")) return true;
                }
            }
        }
        return false;
    }

    private void reply(Player p, boolean ok, String msg, int liters) {
        JsonObject o = state(p);
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        o.addProperty("liters", ok ? liters : 0);
        send(p, "fuel_result", o);
    }

    private void buy(Player p, JsonObject j) {
        long now = System.currentTimeMillis();
        if (now - lastBuy.getOrDefault(p.getUniqueId(), 0L) < 1500) return;
        lastBuy.put(p.getUniqueId(), now);
        int liters = (int) num(j, "liters", 0);
        boolean can = bool(j, "can", false);
        String car = str(j, "car", "");
        boolean card = "card".equals(str(j, "method", "cash"));
        if (liters < 1 || liters > (can ? 20 : 50)) {
            reply(p, false, "Cantitate invalida.", 0);
            return;
        }
        if (!can && !UUID_RE.matcher(car).matches()) {
            reply(p, false, "Masina nu a fost gasita.", 0);
            return;
        }
        if (!nearPump(p)) {
            reply(p, false, "Trebuie sa fii langa pompa.", 0);
            return;
        }
        double total = Math.round(liters * price() * 100.0) / 100.0;
        UUID u = p.getUniqueId();
        boolean paid = card ? plugin.bank().withdraw(u, total) : plugin.money().withdraw(u, total);
        if (!paid) {
            reply(p, false, card ? "Nu ai destui bani pe card." : "Nu ai destui bani cash.", 0);
            return;
        }
        String cmd = can ? "fcveh fuelcan " + p.getName() + " " + liters : "fcveh fuel " + car + " " + liters;
        boolean done;
        try {
            done = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (RuntimeException ex) {
            done = false;
        }
        if (!done) {
            if (card) plugin.bank().deposit(u, total);
            else plugin.money().deposit(u, total);
            reply(p, false, "Pompa nu functioneaza (mod lipsa pe server). Banii au fost returnati.", 0);
            return;
        }
        reply(p, true, "Ai platit " + total + " lei pentru " + liters + " L.", liters);
    }
}
