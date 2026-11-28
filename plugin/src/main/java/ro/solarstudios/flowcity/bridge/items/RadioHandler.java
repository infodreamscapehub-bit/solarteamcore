package ro.solarstudios.flowcity.bridge.items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.fjobs.FJobsModule;

/**
 * Radio cu canale. Itemul tine canalul si starea (pornit/oprit) in datele lui; /radio &lt;mesaj&gt; trimite pe canalul curent doar la
 * jucatorii care au la ei un radio emis de server, pornit, pe acelasi canal. Canalele pot cere permisiune sau job in tura (ex. politie).
 * Butonul de urgenta trimite pozitia pe canal. Comunicatia e text (vocea proximity nu e integrata - vezi README).
 */
public final class RadioHandler implements FlowCityItemActions.Handler {
    public static RadioHandler instance;
    private FlowCityItemActions fx;

    @Override
    public String id() {
        return "radio";
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
        instance = this;
        Commands.register(fx.plugin, "radio", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) {
                s.sendMessage("Doar jucatori.");
                return true;
            }
            send((Player) s, String.join(" ", a));
            return true;
        }, null, "rd");
    }

    // ------------------------------------------------------------------ canale

    private ConfigurationSection channels() {
        return fx.cfg.getConfigurationSection("items.radio.channels");
    }

    /** Folosit de statiile personale: jucatorul are un radio valid la el. */
    public boolean hasRadio(Player p) {
        return radioOf(p) != null;
    }

    /** Porneste/opreste radioul din inventar (din ecranul de statii). */
    public boolean setPower(Player p, boolean on) {
        ItemStack r = radioOf(p);
        if (r == null) return false;
        ItemKit.setData(r, "on", on ? "1" : "0");
        return true;
    }

    /** Radio valid si pornit. */
    public boolean radioOn(Player p) {
        ItemStack r = radioOf(p);
        return r != null && isOn(r);
    }

    /** Are jucatorul acces la un canal oficial (permisiune / job in tura)? */
    public boolean channelOk(Player p, String chan) {
        return channelAllowed(p, chan);
    }

    private boolean channelAllowed(Player p, String chan) {
        ConfigurationSection s = channels() == null ? null : channels().getConfigurationSection(chan);
        if (s == null) return false;
        List<String> perms = s.getStringList("permissions");
        List<String> jobs = s.getStringList("jobs");
        if (perms.isEmpty() && jobs.isEmpty()) return true;
        if (Rules.hasAny(p::hasPermission, perms)) return true;
        String job = FJobsModule.shiftJob(p);
        for (String j : jobs) if (!job.isEmpty() && j.equalsIgnoreCase(job)) return true;
        return false;
    }

    private String chanName(String chan) {
        ConfigurationSection s = channels() == null ? null : channels().getConfigurationSection(chan);
        return s == null ? chan : s.getString("name", chan).replace('&', '§');
    }

    private String chanOf(ItemStack it) {
        String c = ItemKit.data(it, "chan");
        return c == null ? fx.cfg.getString("items.radio.default-channel", "public") : c;
    }

    private boolean isOn(ItemStack it) {
        return !"0".equals(ItemKit.data(it, "on"));
    }

    /** Radio valid (serial neanulat) in inventarul jucatorului, pornit; null daca nu are. */
    private ItemStack radioOf(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (usable(held)) return held;
        for (ItemStack it : p.getInventory().getContents()) if (usable(it)) return it;
        return null;
    }

    private boolean usable(ItemStack it) {
        if (it == null || !"radio".equals(ItemKit.idOf(it))) return false;
        return fx.serials.validate(ItemKit.serialOf(it), "radio") == Serials.Check.OK;
    }

    // ------------------------------------------------------------------ transmisie

    void send(Player p, String msgIn) {
        String msg = msgIn == null ? "" : msgIn.replaceAll("[\\p{Cntrl}]", " ").replace("§", "").trim();
        if (msg.length() > 160) msg = msg.substring(0, 160);
        ItemStack radio = radioOf(p);
        if (radio == null) {
            p.sendMessage("§cNu ai un radio la tine.");
            return;
        }
        if (msg.isEmpty()) {
            p.sendMessage("§7Folosire: /radio <mesaj>. Canal: §f" + chanName(chanOf(radio)) + "§7" + (isOn(radio) ? "" : " (oprit)"));
            return;
        }
        if (!isOn(radio)) {
            p.sendMessage("§cRadioul e oprit.");
            return;
        }
        if (!fx.cooldowns.tryUse(p.getUniqueId() + ":radio:send", 1000L)) {
            p.sendMessage("§eVorbesti prea repede.");
            return;
        }
        ro.solarstudios.flowcity.bridge.radio.RadioStationsModule stations = ro.solarstudios.flowcity.bridge.radio.RadioStationsModule.instance;
        if (stations != null && stations.textSend(p, msg)) {
            fx.log(p, "radio", "SEND", null, null, "statie: " + msg);
            return;
        }
        String chan = chanOf(radio);
        if (!channelAllowed(p, chan)) {
            p.sendMessage("§cNu mai ai acces pe acest canal. Schimba canalul din radio.");
            return;
        }
        broadcast(p, chan, "§f" + p.getName() + "§7: §f" + msg, false);
        fx.log(p, "radio", "SEND", null, null, chan + ": " + msg);
    }

    private void broadcast(Player from, String chan, String body, boolean urgent) {
        String line = (urgent ? "§c§l[URGENTA] " : "") + "§8[§bRadio §7" + chanName(chan) + "§8] " + body;
        int heard = 0;
        for (Player q : Bukkit.getOnlinePlayers()) {
            ItemStack r = radioOf(q);
            if (r == null || !isOn(r) || !chanOf(r).equals(chan) || !channelAllowed(q, chan)) continue;
            q.sendMessage(line);
            if (urgent) FlowCityItemActions.snd(q, "minecraft:block.note_block.bell", 1f, 0.8f);
            else FlowCityItemActions.snd(q, "minecraft:block.note_block.hat", 0.5f, 1.8f);
            if (!q.equals(from)) heard++;
        }
        if (heard == 0) from.sendMessage("§7(Nimeni altcineva nu te aude pe acest canal.)");
    }

    // ------------------------------------------------------------------ panou

    @Override
    public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
        ro.solarstudios.flowcity.bridge.radio.RadioStationsModule rs = ro.solarstudios.flowcity.bridge.radio.RadioStationsModule.instance;
        if (rs != null && !c.player.isSneaking() && rs.openFor(c.player)) return true;
        panel(c);
        return true;
    }

    private void panel(FlowCityItemActions.Ctx c) {
        ItemStack it = c.item;
        String chan = chanOf(it);
        Panel pn = new Panel("radio:main", "Radio").sub(isOn(it) ? "pornit" : "oprit");
        pn.row("Canal", chanName(chan), channelAllowed(c.player, chan) ? Panel.OK : Panel.BAD);
        pn.row("Folosire", "/radio <mesaj> trimite pe canal", Panel.INFO);
        pn.button("toggle", isOn(it) ? "Opreste" : "Porneste", isOn(it) ? Panel.WARN : Panel.OK);
        pn.button("sos", "Apel de urgenta", Panel.BAD);
        if (channels() != null) {
            for (String k : channels().getKeys(false)) {
                if (channelAllowed(c.player, k)) pn.button("c:" + k, (k.equals(chan) ? "* " : "") + ChatStrip.strip(chanName(k)), Panel.INFO);
            }
        }
        fx.openPanel(c.player, "radio", pn, c.serial);
    }

    @Override
    public void onPanel(FlowCityItemActions.Ctx c, String panelId, String button) {
        ItemStack it = c.item;
        if (it == null) return;
        if ("toggle".equals(button)) {
            ItemKit.setData(it, "on", isOn(it) ? "0" : "1");
            c.info("Radio " + (isOn(it) ? "pornit" : "oprit") + ".");
            c.log(isOn(it) ? "ON" : "OFF", null, "");
        } else if (button.startsWith("c:")) {
            String chan = button.substring(2).toLowerCase(Locale.ROOT);
            if (!channelAllowed(c.player, chan)) {
                c.err("Nu ai acces pe acel canal.");
                return;
            }
            ItemKit.setData(it, "chan", chan);
            c.info("Canal: " + ChatStrip.strip(chanName(chan)));
            c.log("CHANNEL", null, chan);
        } else if ("sos".equals(button)) {
            if (!isOn(it)) {
                c.err("Radioul e oprit.");
                return;
            }
            if (!c.ready("sos", 15000)) return;
            String chan = chanOf(it);
            if (!channelAllowed(c.player, chan)) {
                c.err("Nu ai acces pe acest canal.");
                return;
            }
            org.bukkit.Location l = c.player.getLocation();
            broadcast(c.player, chan, "§f" + c.player.getName() + " cere ajutor la §e" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(), true);
            c.log("SOS", null, chan + " @ " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ());
        }
        panel(c);
    }
}
