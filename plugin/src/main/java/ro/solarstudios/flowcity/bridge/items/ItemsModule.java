package ro.solarstudios.flowcity.bridge.items;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Modulul "itemfx": canalul dintre framework-ul de iteme (server) si ecranul/bara de progres din mod (client), plus comanda /fcitem.
 * Server -> client: progress, progress_end, panel, flash. Client -> server: act (buton), close, cancel. Clientul nu decide nimic.
 */
public final class ItemsModule extends JsonModule {
    public static ItemsModule instance;
    private FlowCityItemActions actions;

    @Override
    public String id() {
        return "itemfx";
    }

    public FlowCityItemActions actions() {
        return actions;
    }

    static boolean sane(Player p) {
        return p != null && p.isOnline() && !p.isDead();
    }

    @Override
    protected void onEnable() {
        instance = this;
        actions = new FlowCityItemActions(plugin, this);
        actions.register(new CuffsHandler());
        actions.register(new FingerprintHandler());
        actions.register(new BodycamHandler());
        actions.register(new RadioHandler());
        actions.register(new MedicalHandlers.Bag());
        actions.register(new MedicalHandlers.Defib());
        actions.register(new VehicleToolsHandler.Scanner());
        actions.register(new VehicleToolsHandler.Repair());
        actions.register(new VehicleToolsHandler.Part());
        actions.register(new AdminPanel());
        actions.register(new CameraHandler());
        actions.register(new TaxiMeterHandler());
        actions.enable();
        on("act", (p, j) -> actions.panelAction(p, str(j, "panel", ""), str(j, "btn", "")));
        on("close", (p, j) -> actions.closePanel(p));
        on("cancel", (p, j) -> actions.activities.cancel(p.getUniqueId(), "anulat de tine"));
        Commands.register(plugin, "fcitem", null, (s, c, l, a) -> command(s, a), null);
    }

    @Override
    public void disable() {
        if (actions != null) actions.disable();
    }

    @Override
    public void onQuit(Player p) {
        if (actions != null) actions.closePanel(p);
    }

    // ------------------------------------------------------------------ catre client

    public void progress(Player p, String label, long ms) {
        JsonObject o = new JsonObject();
        o.addProperty("label", label);
        o.addProperty("ms", ms);
        send(p, "progress", o);
        if (!plugin.channel().hasMod(p)) p.sendMessage("§7" + label + "... (" + Math.max(1, ms / 1000) + "s, nu te misca)");
    }

    public void progressEnd(Player p) {
        send(p, "progress_end", new JsonObject());
    }

    public void sendPanel(Player p, Panel panel) {
        send(p, "panel", panel.toJson());
    }

    public void flash(Player p) {
        send(p, "flash", new JsonObject());
    }

    // ------------------------------------------------------------------ comanda

    private boolean command(org.bukkit.command.CommandSender s, String[] a) {
        boolean console = !(s instanceof Player);
        boolean admin = console || s.hasPermission("flowcity.items.admin");
        if (a.length >= 1 && "vehreport".equalsIgnoreCase(a[0])) {
            if (!console) {
                s.sendMessage("§cDoar consola (modul).");
                return true;
            }
            if (a.length >= 4) VehicleToolsHandler.report(this, a[1], a[2], a[3]);
            return true;
        }
        if (!admin) {
            s.sendMessage("§cNu ai permisiunea.");
            return true;
        }
        if (a.length == 0 || "admin".equalsIgnoreCase(a[0])) {
            if (s instanceof Player) AdminPanel.open(actions, (Player) s);
            else s.sendMessage("§7Folosire: /fcitem give|list|revoke|release|revive|reload|admin");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give": {
                if (a.length < 3) {
                    s.sendMessage("§7/fcitem give <jucator> <item> [numar]");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    s.sendMessage("§cJucator offline.");
                    return true;
                }
                int n = 1;
                try {
                    n = Math.max(1, Math.min(16, a.length > 3 ? Integer.parseInt(a[3]) : 1));
                } catch (NumberFormatException ignored) {
                    // 1
                }
                int done = 0;
                for (int i = 0; i < n; i++) if (actions.give(t, a[2].toLowerCase(Locale.ROOT))) done++;
                s.sendMessage(done > 0 ? "§aAi dat " + done + "x " + a[2] + " lui " + t.getName() + "." : "§cItem necunoscut. Vezi /fcitem list.");
                return true;
            }
            case "list":
                s.sendMessage("§6Iteme: §f" + String.join(", ", actions.ids()));
                return true;
            case "revoke":
                if (a.length >= 2) {
                    actions.serials.revoke(a[1]);
                    s.sendMessage("§aSerial revocat (itemul nu mai poate fi folosit).");
                }
                return true;
            case "release": {
                Player t = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) {
                    s.sendMessage("§cJucator offline.");
                    return true;
                }
                CuffsHandler ch = (CuffsHandler) actions.handler("catuse");
                s.sendMessage(ch != null && ch.adminRelease(t, s.getName()) ? "§aEliberat." : "§7Nu e incatusat.");
                return true;
            }
            case "revive": {
                Player t = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) {
                    s.sendMessage("§cJucator offline.");
                    return true;
                }
                s.sendMessage(MedicalHandlers.adminRevive(t, s.getName()) ? "§aRidicat." : "§7Nu e la pamant.");
                return true;
            }
            case "reload":
                actions.reload();
                s.sendMessage("§aItems reincarcat.");
                return true;
            default:
                s.sendMessage("§7/fcitem give|list|revoke|release|revive|reload|admin");
                return true;
        }
    }

    static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static String unb64(String s) {
        return new String(java.util.Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    static List<String> list(String... s) {
        return java.util.Arrays.asList(s);
    }
}
