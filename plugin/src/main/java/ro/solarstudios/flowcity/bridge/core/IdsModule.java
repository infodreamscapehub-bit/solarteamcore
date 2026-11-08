package ro.solarstudios.flowcity.bridge.core;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** Canal "ids": panoul eAdmin poate seta ID-ul unui jucator (permisiune flowcity.id.admin). */
public final class IdsModule extends JsonModule {
    @Override
    public String id() {
        return "ids";
    }

    @Override
    protected void onEnable() {
        on("set", (p, j) -> {
            if (!p.hasPermission("flowcity.id.admin")) {
                plugin.channel().toast(p, "Nu ai permisiunea de a seta ID-uri.", true);
                return;
            }
            UUID u;
            try {
                u = UUID.fromString(str(j, "uuid", ""));
            } catch (IllegalArgumentException e) {
                return;
            }
            int n = integer(j, "id", 0);
            boolean swap = bool(j, "swap", false);
            plugin.ids().setId(u, n, swap, (ok, msg) -> {
                plugin.channel().toast(p, msg, !ok);
                if (ok) plugin.channel().sendProfile(p, u);
            });
        });
    }

    @Override
    public void onQuit(Player player) {}
}
