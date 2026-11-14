package ro.solarstudios.flowcity.bridge.fines;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * "Amenzile mele": jucatorul isi vede amenzile primite din MDT si le plateste singur (cash sau card, prin PayModule).
 * Comenzi: /amenzi (propriile amenzi), /amenzi open <jucator> (pentru NPC/ghiseu: CONSOLE amenzi open %player%),
 * /amenzi plateste <id> (fara mod). Dupa plata, hype.yml "mdt.fines-payment-commands" se ruleaza din consola
 * (%player%, %amount%) - de ex. "eco give politia %amount%" ca banii sa ajunga la un cont.
 */
public final class FinesModule extends JsonModule {
    private YamlConfiguration cfg;

    @Override
    public String id() {
        return "fines";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "hype");
        on("pay", (p, j) -> pay(p, (long) num(j, "id", -1)));
        on("refresh", (p, j) -> sendList(p));
        Commands.register(plugin, "amenzi", null, (s, c, l, a) -> {
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                if (s instanceof Player && !s.hasPermission("flowcity.fines.admin")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) open(t);
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Folosire: amenzi open <jucator>");
                return true;
            }
            Player p = (Player) s;
            if (a.length >= 2 && "plateste".equalsIgnoreCase(a[0])) {
                try {
                    pay(p, Long.parseLong(a[1]));
                } catch (NumberFormatException e) {
                    p.sendMessage("§cId invalid.");
                }
                return true;
            }
            open(p);
            return true;
        }, null, "fines", "amenda");
    }

    private void open(Player p) {
        MdtModule m = MdtModule.instance;
        if (m == null) {
            p.sendMessage("§cSistemul de amenzi nu e disponibil.");
            return;
        }
        JsonArray f = m.unpaidFines(p.getUniqueId());
        if (plugin.channel().hasMod(p)) {
            sendList(p);
            return;
        }
        if (f.size() == 0) {
            p.sendMessage("§aNu ai nicio amenda neplatita.");
            return;
        }
        p.sendMessage("§9[Politie] §fAmenzile tale:");
        for (int i = 0; i < f.size(); i++) {
            JsonObject o = f.get(i).getAsJsonObject();
            p.sendMessage("§7#" + o.get("id").getAsLong() + " §e" + o.get("amount").getAsInt() + " lei §f- " + o.get("reason").getAsString()
                + " §7(/amenzi plateste " + o.get("id").getAsLong() + ")");
        }
    }

    private void sendList(Player p) {
        MdtModule m = MdtModule.instance;
        if (m == null) return;
        JsonObject o = new JsonObject();
        o.add("fines", m.unpaidFines(p.getUniqueId()));
        send(p, "open", o);
    }

    private void result(Player p, boolean ok, String msg) {
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("msg", msg);
            send(p, "result", o);
        } else {
            p.sendMessage((ok ? "§a" : "§c") + msg);
        }
    }

    private void pay(Player p, long id) {
        MdtModule m = MdtModule.instance;
        PayModule pay = PayModule.instance;
        if (m == null || pay == null) {
            result(p, false, "Plata nu e disponibila.");
            return;
        }
        final int amount = m.fineAmount(p.getUniqueId(), id);
        if (amount < 0) {
            result(p, false, "Amenda nu mai exista sau e deja platita.");
            sendList(p);
            return;
        }
        pay.request(p, "Amenda #" + id, amount, () -> {
            if (!m.payFineByCitizen(p.getUniqueId(), id)) return false;
            for (String c : cfg.getStringList("mdt.fines-payment-commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()).replace("%amount%", String.valueOf(amount)));
            }
            result(p, true, "Amenda de " + amount + " lei a fost platita.");
            if (plugin.channel().hasMod(p)) sendList(p);
            return true;
        }, msg -> result(p, false, msg));
    }
}
