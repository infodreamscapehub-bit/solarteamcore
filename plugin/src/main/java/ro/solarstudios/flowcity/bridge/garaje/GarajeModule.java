package ro.solarstudios.flowcity.bridge.garaje;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * /garaje (alias /garajeadmin): deschide in mod ecranul de administrare a garajelor (lista, sterge, sterge toate, creeaza, muta,
 * teleport, parcare, locuri). Doar pentru staff (flowcity.garaje.admin, implicit op); modul verifica din nou pe server ca jucatorul e operator.
 */
public final class GarajeModule extends JsonModule {
    @Override
    public String id() {
        return "garaje";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.garaje.admin", "Poate administra garajele din ecranul /garaje", PermissionDefault.OP);
        Commands.register(plugin, "garaje", "flowcity.garaje.admin", (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Comanda se foloseste in joc. Din consola: garaj lista / garaj sterge <id> / garaj sterge toate");
                return true;
            }
            if (!s.hasPermission("flowcity.garaje.admin")) {
                s.sendMessage("§cNu ai permisiunea.");
                return true;
            }
            send((Player) s, "open", new JsonObject());
            s.sendMessage("§b[Garaje] §7Se deschide ecranul de administrare... (dacă nu apare: scrie §f.garaje§7 în chat)");
            return true;
        }, null, "garajeadmin");
    }
}
