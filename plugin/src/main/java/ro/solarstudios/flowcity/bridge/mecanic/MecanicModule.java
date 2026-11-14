package ro.solarstudios.flowcity.bridge.mecanic;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Mecanic (NPC): reparatie, ITP si asigurare RCA pentru masinile jucatorului. Platile trec prin PayModule, apoi modul aplica
 * serviciul cu "fccars service" (daca intoarce 0, banii se returneaza). Lista masinilor vine de la mod (fccars mechlist).
 * Canal "mecanic": client -> server: service {car, svc}; server -> client: prices, result.
 */
public final class MecanicModule extends JsonModule {
    private YamlConfiguration cfg;

    @Override
    public String id() {
        return "mecanic";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "mecanic");
        on("service", (p, j) -> service(p, (int) num(j, "car", 0), str(j, "svc", "")));
        Commands.register(plugin, "mecanic", null, (s, c, l, a) -> {
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0]) && s.hasPermission("flowcity.mecanic.admin")) {
                cfg = ModConfig.load(plugin, "mecanic");
                s.sendMessage("§aMecanic reincarcat.");
                return true;
            }
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                if (s instanceof Player && !s.hasPermission("flowcity.mecanic.admin")) {
                    s.sendMessage("§cMecanicul se deschide de la NPC.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) open(t);
                return true;
            }
            s.sendMessage("§7Folosire: /mecanic open <jucator>  (ZNPCs: CONSOLE mecanic open %player%)");
            return true;
        }, null);
    }

    private int price(String svc) {
        return (int) Math.round(Math.max(0, cfg.getDouble("prices." + svc, 0)));
    }

    private void open(Player p) {
        if (!plugin.channel().hasMod(p)) {
            p.sendMessage("§cMecanicul necesita modul FlowCity pe client.");
            return;
        }
        JsonObject o = new JsonObject();
        for (String k : new String[] {"repair", "itp", "rca7", "rca30"}) o.addProperty(k, price(k));
        send(p, "prices", o);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fccars mechlist " + p.getName());
    }

    private void result(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "result", o);
    }

    private void service(Player p, int car, String svc) {
        String label;
        switch (svc) {
            case "repair":
                label = "Reparatie";
                break;
            case "itp":
                label = "ITP";
                break;
            case "rca7":
                label = "Asigurare RCA 7 zile";
                break;
            case "rca30":
                label = "Asigurare RCA 30 zile";
                break;
            default:
                return;
        }
        if (car <= 0) return;
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        final String name = label;
        pay.request(p, name, price(svc), () -> {
            boolean done;
            try {
                done = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fccars service " + p.getName() + " " + car + " " + svc);
            } catch (RuntimeException ex) {
                done = false;
            }
            result(p, done, done ? name + " efectuat." : "Serviciul nu se poate face acum. Banii au fost returnati.");
            return done;
        }, msg -> result(p, false, msg));
    }
}
