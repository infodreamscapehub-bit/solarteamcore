package ro.solarstudios.flowcity.bridge.core;

import com.google.gson.JsonObject;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/** Canal "ids": panoul eAdmin poate seta ID-ul unui jucator (permisiune flowcity.id.admin). */
public final class IdsModule extends JsonModule {
    @Override
    public String id() {
        return "ids";
    }

    private JsonObject all() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", plugin.getConfig().getBoolean("ids.overhead", true));
        JsonObject map = new JsonObject();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int id = plugin.ids().idOf(p.getUniqueId());
            if (id > 0) map.addProperty(p.getUniqueId().toString(), id);
        }
        o.add("ids", map);
        return o;
    }

    @Override
    public void onClientReady(Player p) {
        send(p, "map", all());
    }

    @Override
    protected void onEnable() {
        // cand se schimba un ID, toti clientii cu modul primesc noua valoare (pentru numele de deasupra capului)
        plugin.ids().onChange(u -> {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", u.toString());
            o.addProperty("id", plugin.ids().idOf(u));
            broadcast("upd", o);
        });
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
