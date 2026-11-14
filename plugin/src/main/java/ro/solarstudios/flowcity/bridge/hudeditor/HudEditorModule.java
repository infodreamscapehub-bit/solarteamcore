package ro.solarstudios.flowcity.bridge.hudeditor;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** /hudeditor [jucator]: deschide editorul de HUD din mod (acelasi ecran ca tasta F8). Pentru altul: permisiunea flowcity.hudeditor.others. */
public final class HudEditorModule extends JsonModule {
    @Override
    public String id() {
        return "hudeditor";
    }

    @Override
    protected void onEnable() {
        Commands.register(plugin, "hudeditor", null, (s, c, l, a) -> {
            Player t;
            if (a.length >= 1) {
                if (s instanceof Player && !s.hasPermission("flowcity.hudeditor.others")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                t = Bukkit.getPlayerExact(a[0]);
                if (t == null) {
                    s.sendMessage("§cJucatorul nu e online.");
                    return true;
                }
            } else if (s instanceof Player) {
                t = (Player) s;
            } else {
                s.sendMessage("Folosire: hudeditor <jucator>");
                return true;
            }
            send(t, "open", new JsonObject());
            return true;
        }, null, "hud");
    }
}
