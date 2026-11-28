package ro.solarstudios.flowcity.bridge.items;

import java.io.File;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Catuse: politistul incatuseaza (activitate de cateva secunde, la distanta mica), escorteaza si elibereaza. Cat e incatusat, jucatorul
 * nu poate folosi iteme, lovi, sparge/pune blocuri, conduce sau da comenzi (in afara celor permise). Starea se salveaza in fisier:
 * ramane si dupa deconectare sau restart; eliberarea automata (implicit 30 min) previne blocarea. Serverul decide totul.
 */
public final class CuffsHandler implements FlowCityItemActions.Handler, Listener {
    private FlowCityItemActions fx;
    private CuffRegistry reg;

    @Override
    public String id() {
        return "catuse";
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
        this.reg = new CuffRegistry(new File(fx.plugin.getDataFolder(), "items/cuffs.tsv"));
        Bukkit.getPluginManager().registerEvents(this, fx.plugin);
        Bukkit.getScheduler().runTaskTimer(fx.plugin, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 20L, 20L);
    }

    public boolean isCuffed(UUID u) {
        return reg != null && reg.isCuffed(u);
    }

    public List<UUID> all() {
        return reg.all();
    }

    private void tick() {
        long max = Math.round(fx.cfg.getDouble("items.catuse.auto-release-minutes", 30) * 60_000.0);
        for (UUID u : reg.expired(System.currentTimeMillis(), max)) {
            Player t = Bukkit.getPlayer(u);
            doRelease(u, t, null, "eliberare automata");
            if (t != null) t.sendMessage("§aCatusele ti-au fost scoase (timp expirat).");
        }
        for (UUID u : reg.all()) {
            Player t = Bukkit.getPlayer(u);
            if (t != null) effects(t);
        }
    }

    private void effects(Player t) {
        t.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 2, false, false, false), true);
        t.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 60, 128, false, false, false), true);
    }

    private void doRelease(UUID target, Player t, Player by, String why) {
        reg.release(target);
        fx.setEscort(target, null);
        if (t != null) {
            t.removePotionEffect(PotionEffectType.SLOW);
            t.removePotionEffect(PotionEffectType.JUMP);
        }
        fx.log(by, "catuse", "RELEASE", target, t == null ? target.toString() : t.getName(), why);
    }

    /** Eliberare de catre un admin (comanda /fcitem release sau panoul de admin). */
    public boolean adminRelease(Player t, String admin) {
        if (!reg.isCuffed(t.getUniqueId())) return false;
        doRelease(t.getUniqueId(), t, null, "admin " + admin);
        t.sendMessage("§aCatusele ti-au fost scoase.");
        return true;
    }

    // ------------------------------------------------------------------ utilizare

    @Override
    public boolean onUsePlayer(final FlowCityItemActions.Ctx c, final Player target) {
        final Player officer = c.player;
        if (target.equals(officer)) return true;
        if (!c.near(target)) {
            c.err(target.getName() + " e prea departe.");
            return true;
        }
        if (!c.ready("use", 1500)) return true;
        final boolean cuffed = reg.isCuffed(target.getUniqueId());
        if (cuffed) {
            if (c.sneaking()) {
                toggleEscort(c, target);
                return true;
            }
            c.start("Scoti catusele", c.num("release-seconds", 2) * 1000L, check(c, target, true), new Runnable() {
                @Override
                public void run() {
                    if (!reg.isCuffed(target.getUniqueId())) return;
                    doRelease(target.getUniqueId(), target, officer, "eliberat de " + officer.getName());
                    c.ok("L-ai eliberat pe " + target.getName() + ".");
                    target.sendMessage("§a" + officer.getName() + " ti-a scos catusele.");
                }
            });
            return true;
        }
        if (target.getGameMode() == GameMode.SPECTATOR || target.getGameMode() == GameMode.CREATIVE) {
            c.err("Nu poti incatusa acest jucator.");
            return true;
        }
        if (target.isInsideVehicle()) {
            c.err("Cobora-l din vehicul inainte sa-l incatusezi.");
            return true;
        }
        if (!c.flag("cuff-officers", false) && target.hasPermission("flowcity.politie.officer")) {
            c.err("Nu poti incatusa un coleg.");
            return true;
        }
        c.start("Incatusezi pe " + target.getName(), c.num("cuff-seconds", 3) * 1000L, check(c, target, false), new Runnable() {
            @Override
            public void run() {
                if (!reg.cuff(target.getUniqueId(), officer.getUniqueId(), System.currentTimeMillis())) {
                    c.err(target.getName() + " e deja incatusat.");
                    return;
                }
                effects(target);
                c.ok("L-ai incatusat pe " + target.getName() + ". Shift + click dreapta = escorteaza.");
                target.sendMessage("§cAi fost incatusat de " + officer.getName() + ".");
                FlowCityItemActions.snd(target, "minecraft:block.chain.place", 1f, 1f);
                c.log("CUFF", target, "incatusat");
            }
        });
        return true;
    }

    private java.util.function.Supplier<String> check(final FlowCityItemActions.Ctx c, final Player target, final boolean wantCuffed) {
        return new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                if (!target.isOnline()) return "tinta s-a deconectat";
                if (!c.near(target)) return "tinta s-a departat";
                if (reg.isCuffed(target.getUniqueId()) != wantCuffed) return "starea tintei s-a schimbat";
                return null;
            }
        };
    }

    private void toggleEscort(FlowCityItemActions.Ctx c, Player target) {
        UUID cur = fx.escortOf(target.getUniqueId());
        if (cur != null && cur.equals(c.player.getUniqueId())) {
            reg.setEscort(target.getUniqueId(), null);
            fx.setEscort(target.getUniqueId(), null);
            c.ok("Ai oprit escortarea.");
            c.log("ESCORT_STOP", target, "");
        } else if (cur != null) {
            c.err("E deja escortat de altcineva.");
        } else {
            reg.setEscort(target.getUniqueId(), c.player.getUniqueId());
            fx.setEscort(target.getUniqueId(), c.player.getUniqueId());
            c.ok("Il escortezi pe " + target.getName() + ".");
            c.log("ESCORT_START", target, "");
        }
    }

    // ------------------------------------------------------------------ restrictii pentru cel incatusat

    private boolean bound(Player p) {
        return reg != null && reg.isCuffed(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (bound(e.getPlayer())) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (bound(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (bound(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (bound(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (bound(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player && bound((Player) e.getWhoClicked())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && bound((Player) e.getDamager())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicle(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player && bound((Player) e.getEntered())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!bound(e.getPlayer())) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase(java.util.Locale.ROOT);
        for (String ok : fx.cfg.getStringList("items.catuse.allowed-commands")) if (ok.equalsIgnoreCase(cmd)) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage("§cEsti incatusat: nu poti folosi comenzi.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (bound(e.getPlayer())) {
            e.getPlayer().sendMessage("§cEsti inca incatusat. Un politist te poate elibera.");
            effects(e.getPlayer());
        }
    }

    @EventHandler
    public void onQuitCuffed(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!bound(p)) return;
        reg.setEscort(p.getUniqueId(), null);
        fx.setEscort(p.getUniqueId(), null);
        // iesirea in timp ce esti incatusat e semnalata politiei: ramane incatusat la reintrare
        fx.log(p, "catuse", "LOGOUT_CUFFED", p.getUniqueId(), p.getName(), "deconectat incatusat");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.hasPermission("flowcity.politie.officer")) {
                fx.plugin.notifications().send(o.getUniqueId(), NotifyModule.Type.WARN, "Incatusat deconectat", p.getName() + " a iesit de pe server incatusat. Ramane incatusat la reintrare.");
            }
        }
    }

    @Override
    public void onQuit(Player p) {
        // activitatile se anuleaza in framework; escorta se opreste in onQuitCuffed
    }
}
