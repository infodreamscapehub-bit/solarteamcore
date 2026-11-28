package ro.solarstudios.flowcity.bridge.items;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;

/**
 * Sistem medical RP: un jucator care ar muri (si nu din /kill sau gol) ramane la pamant. Starile sunt in {@link DownedRegistry}
 * (persistente): sangereaza -> stop cardiac -> moarte. Geanta medicala stabilizeaza si trateaza, defibrilatorul reporneste inima in
 * stop cardiac. Sistemul se opreste din items.yml (downed.enabled: false) si atunci moartea ramane cea normala din Minecraft.
 */
public final class MedicalHandlers {
    private MedicalHandlers() {}

    static DownedRegistry reg;
    static DownedSystem sys;

    static void init(FlowCityItemActions fx) {
        if (sys != null) return;
        reg = new DownedRegistry(new File(fx.plugin.getDataFolder(), "items/downed.tsv"));
        sys = new DownedSystem(fx);
        Bukkit.getPluginManager().registerEvents(sys, fx.plugin);
        Bukkit.getScheduler().runTaskTimer(fx.plugin, new Runnable() {
            @Override
            public void run() {
                sys.tick();
            }
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(fx.plugin, new Runnable() {
            @Override
            public void run() {
                sys.effects();
            }
        }, 10L, 10L);
    }

    /** Ridicare fortata de un admin. */
    public static boolean adminRevive(Player t, String by) {
        if (reg == null || !reg.recover(t.getUniqueId())) return false;
        sys.clear(t, 10.0);
        FlowCityItemActions.instance.log(null, "medical", "ADMIN_REVIVE", t.getUniqueId(), t.getName(), "de " + by);
        return true;
    }

    // ------------------------------------------------------------------ sistemul de la pamant

    static final class DownedSystem implements Listener {
        private final FlowCityItemActions fx;
        private final Set<UUID> pendingKill = new HashSet<UUID>();
        private final File killFile;
        private final Random rnd = new Random();

        DownedSystem(FlowCityItemActions fx) {
            this.fx = fx;
            this.killFile = new File(fx.plugin.getDataFolder(), "items/pending-death.txt");
            if (killFile.isFile()) {
                try {
                    for (String l : Files.readAllLines(killFile.toPath(), StandardCharsets.UTF_8)) pendingKill.add(UUID.fromString(l.trim()));
                } catch (IOException | RuntimeException ignored) {
                    // fisier corupt
                }
            }
        }

        boolean enabled() {
            return fx.cfg.getBoolean("downed.enabled", true);
        }

        long bleedMs() {
            return Math.round(fx.cfg.getDouble("downed.bleed-seconds", 240) * 1000.0);
        }

        long arrestMs() {
            return Math.round(fx.cfg.getDouble("downed.arrest-seconds", 120) * 1000.0);
        }

        private void saveKills() {
            try {
                File parent = killFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                List<String> lines = new ArrayList<String>();
                for (UUID u : pendingKill) lines.add(u.toString());
                Files.write(killFile.toPath(), lines, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // la urmatoarea salvare
            }
        }

        // ---- intrare in starea de la pamant

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent e) {
            if (!(e.getEntity() instanceof Player) || !enabled()) return;
            Player p = (Player) e.getEntity();
            if (reg.isDown(p.getUniqueId())) {
                if (fx.cfg.getBoolean("downed.invulnerable", true)) e.setCancelled(true);
                return;
            }
            EntityDamageEvent.DamageCause cause = e.getCause();
            if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.SUICIDE) return;
            if (p.getHealth() - e.getFinalDamage() > 0) return;
            if (p.hasPermission("flowcity.items.nodown")) return;
            e.setCancelled(true);
            goDown(p);
        }

        private void goDown(Player p) {
            if (!reg.down(p.getUniqueId(), now())) return;
            p.setHealth(Math.min(2.0, p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
            p.setFireTicks(0);
            apply(p);
            p.sendMessage("§c§lEsti la pamant. §7Sangerezi - cheama un medic pe radio sau la 112.");
            fx.log(p, "medical", "DOWN", p.getUniqueId(), p.getName(), "la pamant");
            alertEms(p, "Pacient la pamant", p.getName() + " sangereaza la " + loc(p.getLocation()) + ".");
        }

        void alertEms(Player patient, String title, String text) {
            List<String> perms = fx.cfg.getStringList("downed.notify-permissions");
            for (Player q : Bukkit.getOnlinePlayers()) {
                if (q.equals(patient) || !Rules.hasAny(q::hasPermission, perms)) continue;
                fx.plugin.notifications().send(q.getUniqueId(), NotifyModule.Type.WARN, title, text);
            }
        }

        private String loc(Location l) {
            return l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
        }

        private void apply(Player p) {
            int dur = 60;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, dur, 6, false, false, false), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, dur, 128, false, false, false), true);
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, dur, 4, false, false, false), true);
            p.setSprinting(false);
        }

        void clear(Player p, double health) {
            p.removePotionEffect(PotionEffectType.SLOW);
            p.removePotionEffect(PotionEffectType.JUMP);
            p.removePotionEffect(PotionEffectType.WEAKNESS);
            fx.setEscort(p.getUniqueId(), null);
            double max = p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            p.setHealth(Math.max(1.0, Math.min(max, health)));
        }

        void effects() {
            for (UUID u : reg.all()) {
                Player p = Bukkit.getPlayer(u);
                if (p != null && p.isOnline()) apply(p);
            }
        }

        // ---- trecerea timpului

        private String mmss(long ms) {
            long s = Math.max(0, ms / 1000);
            return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
        }

        void tick() {
            long now = now();
            for (DownedRegistry.Change ch : reg.advance(now, bleedMs(), arrestMs())) {
                Player p = Bukkit.getPlayer(ch.who);
                if (ch.to == DownedRegistry.State.ARREST) {
                    if (p != null) {
                        p.sendMessage("§4§lStop cardiac! §7Doar un defibrilator te mai poate salva.");
                        alertEms(p, "Stop cardiac", p.getName() + " are stop cardiac la " + loc(p.getLocation()) + ".");
                        fx.log(p, "medical", "ARREST", p.getUniqueId(), p.getName(), "stop cardiac");
                    }
                } else if (ch.to == null) {
                    fx.setEscort(ch.who, null);
                    if (p != null && p.isOnline()) {
                        fx.log(p, "medical", "DEATH", p.getUniqueId(), p.getName(), "nu a fost resuscitat");
                        kill(p);
                    } else {
                        pendingKill.add(ch.who);
                        saveKills();
                    }
                }
            }
            for (UUID u : reg.all()) {
                Player p = Bukkit.getPlayer(u);
                if (p == null) continue;
                DownedRegistry.Entry en = reg.get(u);
                if (en == null) continue;
                String msg;
                if (en.state == DownedRegistry.State.BLEEDING) msg = "§cSangerezi - stop cardiac in " + mmss(bleedMs() - (now - en.since));
                else if (en.state == DownedRegistry.State.STABLE) msg = "§aStabilizat - asteapta transport / tratament";
                else msg = "§4Stop cardiac - moarte in " + mmss(arrestMs() - (now - en.since));
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(msg));
            }
        }

        private void kill(Player p) {
            p.removePotionEffect(PotionEffectType.SLOW);
            p.removePotionEffect(PotionEffectType.JUMP);
            p.removePotionEffect(PotionEffectType.WEAKNESS);
            p.setHealth(0.0);
        }

        // ---- restrictii cat timp esti la pamant

        private boolean down(Player p) {
            return reg != null && reg.isDown(p.getUniqueId());
        }

        @EventHandler(ignoreCancelled = true)
        public void onInteract(PlayerInteractEvent e) {
            if (down(e.getPlayer())) e.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onBreak(BlockBreakEvent e) {
            if (down(e.getPlayer())) e.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlace(BlockPlaceEvent e) {
            if (down(e.getPlayer())) e.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrop(PlayerDropItemEvent e) {
            if (down(e.getPlayer())) e.setCancelled(true);
        }

        @EventHandler(ignoreCancelled = true)
        public void onHit(EntityDamageByEntityEvent e) {
            if (e.getDamager() instanceof Player && down((Player) e.getDamager())) e.setCancelled(true);
        }

        // ---- moarte reala / revenire

        @EventHandler
        public void onDeath(PlayerDeathEvent e) {
            if (reg != null && reg.recover(e.getEntity().getUniqueId())) fx.setEscort(e.getEntity().getUniqueId(), null);
        }

        @EventHandler
        public void onRespawn(PlayerRespawnEvent e) {
            Player p = e.getPlayer();
            Bukkit.getScheduler().runTask(fx.plugin, () -> {
                p.removePotionEffect(PotionEffectType.SLOW);
                p.removePotionEffect(PotionEffectType.JUMP);
                p.removePotionEffect(PotionEffectType.WEAKNESS);
            });
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            final Player p = e.getPlayer();
            if (pendingKill.remove(p.getUniqueId())) {
                saveKills();
                Bukkit.getScheduler().runTaskLater(fx.plugin, () -> {
                    if (p.isOnline()) {
                        p.sendMessage("§cNu ai fost resuscitat cat timp erai deconectat.");
                        fx.log(p, "medical", "DEATH", p.getUniqueId(), p.getName(), "murit offline");
                        kill(p);
                    }
                }, 20L);
                return;
            }
            if (reg.isDown(p.getUniqueId())) {
                Bukkit.getScheduler().runTaskLater(fx.plugin, () -> {
                    if (p.isOnline() && reg.isDown(p.getUniqueId())) {
                        p.setHealth(Math.min(2.0, p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
                        apply(p);
                        p.sendMessage("§cEsti inca la pamant.");
                    }
                }, 20L);
            }
        }
    }

    static long now() {
        return java.lang.System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ geanta medicala

    public static final class Bag implements FlowCityItemActions.Handler {
        private FlowCityItemActions fx;

        @Override
        public String id() {
            return "geanta_medicala";
        }

        @Override
        public void enable(FlowCityItemActions fx) {
            this.fx = fx;
            init(fx);
        }

        private boolean hospital(Player p) {
            List<String> zones = fx.cfg.getStringList("items.geanta_medicala.hospital-zones");
            if (zones.isEmpty()) return true;
            Location l = p.getLocation();
            for (String z : zones) {
                String[] a = z.split(",");
                if (a.length < 5 || !a[0].equals(l.getWorld().getName())) continue;
                try {
                    double dx = l.getX() - Double.parseDouble(a[1]);
                    double dy = l.getY() - Double.parseDouble(a[2]);
                    double dz = l.getZ() - Double.parseDouble(a[3]);
                    double r = Double.parseDouble(a[4]);
                    if (dx * dx + dy * dy + dz * dz <= r * r) return true;
                } catch (NumberFormatException ignored) {
                    // zona invalida
                }
            }
            return false;
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
            Panel pn = new Panel("med:main", "Geanta medicala").sub("pacienti in apropiere");
            double r = fx.cfg.getDouble("items.geanta_medicala.scan-radius", 25);
            int n = 0;
            for (UUID u : reg.all()) {
                Player t = Bukkit.getPlayer(u);
                DownedRegistry.Entry en = reg.get(u);
                if (t == null || en == null || !t.getWorld().equals(c.player.getWorld()) || t.getLocation().distance(c.player.getLocation()) > r) continue;
                String st = en.state == DownedRegistry.State.BLEEDING ? "sangereaza" : en.state == DownedRegistry.State.STABLE ? "stabilizat" : "STOP CARDIAC";
                pn.row(t.getName(), st + ", " + Math.round(t.getLocation().distance(c.player.getLocation())) + " m", en.state == DownedRegistry.State.ARREST ? Panel.BAD : en.state == DownedRegistry.State.STABLE ? Panel.OK : Panel.WARN);
                n++;
            }
            if (n == 0) pn.row("Niciun pacient", "Nu e nimeni la pamant in " + Math.round(r) + " m.", Panel.INFO);
            pn.row("Folosire", "click dreapta pe pacient; sneak = transport (stabilizat)", Panel.INFO);
            fx.openPanel(c.player, "geanta_medicala", pn, c.serial);
            return true;
        }

        @Override
        public boolean onUsePlayer(final FlowCityItemActions.Ctx c, final Player target) {
            if (!c.near(target)) {
                c.err(target.getName() + " e prea departe.");
                return true;
            }
            if (target.equals(c.player)) {
                c.err("Nu te poti trata singur; cere ajutor unui coleg.");
                return true;
            }
            final DownedRegistry.Entry en = reg.get(target.getUniqueId());
            if (en == null) {
                heal(c, target);
                return true;
            }
            if (en.state == DownedRegistry.State.ARREST) {
                c.err(target.getName() + " are stop cardiac: foloseste defibrilatorul.");
                return true;
            }
            if (en.state == DownedRegistry.State.BLEEDING) {
                if (!c.ready("stabilize", 2000)) return true;
                c.start("Stabilizezi pe " + target.getName(), c.num("stabilize-seconds", 8) * 1000L, check(c, target, DownedRegistry.State.BLEEDING), new Runnable() {
                    @Override
                    public void run() {
                        if (!c.charge()) {
                            c.err("Geanta e goala.");
                            return;
                        }
                        if (!reg.stabilize(target.getUniqueId(), now())) {
                            c.err("Pacientul nu mai are nevoie de stabilizare.");
                            return;
                        }
                        c.ok(target.getName() + " e stabilizat. Transporta-l sau trateaza-l la spital.");
                        target.sendMessage("§aAi fost stabilizat de " + c.player.getName() + ".");
                        c.log("STABILIZE", target, "");
                    }
                });
                return true;
            }
            // STABLE: sneak = transport, altfel tratament complet
            if (c.sneaking()) {
                UUID cur = fx.escortOf(target.getUniqueId());
                if (cur != null && cur.equals(c.player.getUniqueId())) {
                    fx.setEscort(target.getUniqueId(), null);
                    c.ok("Ai lasat pacientul jos.");
                    c.log("CARRY_STOP", target, "");
                } else if (cur != null) {
                    c.err("Pacientul e deja transportat de altcineva.");
                } else {
                    fx.setEscort(target.getUniqueId(), c.player.getUniqueId());
                    c.ok("Il transporti pe " + target.getName() + ". Sneak + click ca sa-l lasi jos.");
                    c.log("CARRY", target, "");
                }
                return true;
            }
            if (!hospital(c.player)) {
                c.err("Tratamentul complet se face doar intr-o zona de spital.");
                return true;
            }
            if (!c.ready("treat", 2000)) return true;
            c.start("Tratezi pe " + target.getName(), c.num("treat-seconds", 15) * 1000L, check(c, target, DownedRegistry.State.STABLE), new Runnable() {
                @Override
                public void run() {
                    if (!c.charge()) {
                        c.err("Geanta e goala.");
                        return;
                    }
                    if (!reg.recover(target.getUniqueId())) {
                        c.err("Pacientul e deja tratat.");
                        return;
                    }
                    sys.clear(target, target.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * c.dbl("treat-health-fraction", 0.6));
                    c.ok(target.getName() + " a fost tratat si se poate ridica.");
                    target.sendMessage("§aAi fost tratat de " + c.player.getName() + ".");
                    c.log("TREAT", target, "");
                }
            });
            return true;
        }

        private void heal(final FlowCityItemActions.Ctx c, final Player target) {
            double max = target.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
            if (target.getHealth() >= max - 0.5) {
                c.err(target.getName() + " nu are nevoie de tratament.");
                return;
            }
            if (!c.ready("heal", 2000)) return;
            c.start("Pansezi pe " + target.getName(), c.num("heal-seconds", 5) * 1000L, check(c, target, null), new Runnable() {
                @Override
                public void run() {
                    if (!c.charge()) {
                        c.err("Geanta e goala.");
                        return;
                    }
                    double mx = target.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                    target.setHealth(Math.min(mx, target.getHealth() + c.dbl("heal-amount", 6.0)));
                    c.ok(target.getName() + " a fost pansat.");
                    c.log("HEAL", target, "");
                }
            });
        }

        private java.util.function.Supplier<String> check(final FlowCityItemActions.Ctx c, final Player target, final DownedRegistry.State want) {
            return new java.util.function.Supplier<String>() {
                @Override
                public String get() {
                    if (!target.isOnline()) return "pacientul s-a deconectat";
                    if (!c.near(target)) return "pacientul e prea departe";
                    if (want != null && reg.state(target.getUniqueId()) != want) return "starea pacientului s-a schimbat";
                    if (want == null && reg.isDown(target.getUniqueId())) return "starea pacientului s-a schimbat";
                    return null;
                }
            };
        }
    }

    // ------------------------------------------------------------------ defibrilator

    public static final class Defib implements FlowCityItemActions.Handler {
        private FlowCityItemActions fx;
        private final Random rnd = new Random();

        @Override
        public String id() {
            return "defibrilator";
        }

        @Override
        public void enable(FlowCityItemActions fx) {
            this.fx = fx;
            init(fx);
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
            c.info("Foloseste defibrilatorul pe un pacient in stop cardiac (click dreapta pe el).");
            return true;
        }

        @Override
        public boolean onUsePlayer(final FlowCityItemActions.Ctx c, final Player target) {
            if (!c.near(target)) {
                c.err(target.getName() + " e prea departe.");
                return true;
            }
            if (reg.state(target.getUniqueId()) != DownedRegistry.State.ARREST) {
                c.err(target.getName() + " nu are stop cardiac.");
                return true;
            }
            if (!c.ready("shock", 2000)) return true;
            c.start("Incarci defibrilatorul", c.num("charge-seconds", 5) * 1000L, new java.util.function.Supplier<String>() {
                @Override
                public String get() {
                    if (!target.isOnline()) return "pacientul s-a deconectat";
                    if (!c.near(target)) return "pacientul e prea departe";
                    if (reg.state(target.getUniqueId()) != DownedRegistry.State.ARREST) return "starea pacientului s-a schimbat";
                    return null;
                }
            }, new Runnable() {
                @Override
                public void run() {
                    DownedRegistry.Entry en = reg.get(target.getUniqueId());
                    if (en == null || en.state != DownedRegistry.State.ARREST) {
                        c.err("Pacientul nu mai are stop cardiac.");
                        return;
                    }
                    if (!c.charge()) {
                        c.err("Defibrilatorul nu mai are incarcari.");
                        return;
                    }
                    double chance = DownedRegistry.shockChance(now() - en.since, sys.arrestMs(), en.shocks);
                    fx.fx.flash(target);
                    FlowCityItemActions.snd(target, "minecraft:entity.lightning_bolt.thunder", 0.4f, 2.0f);
                    FlowCityItemActions.snd(c.player, "minecraft:entity.lightning_bolt.thunder", 0.4f, 2.0f);
                    if (rnd.nextDouble() < chance) {
                        reg.recover(target.getUniqueId());
                        sys.clear(target, c.dbl("revive-health", 6.0));
                        c.ok("Soc reusit: inima lui " + target.getName() + " bate din nou.");
                        target.sendMessage("§aAi fost resuscitat de " + c.player.getName() + ".");
                        c.log("SHOCK_OK", target, "sansa " + Math.round(chance * 100) + "%");
                    } else {
                        reg.addShock(target.getUniqueId());
                        c.info("Soc fara efect. Continua resuscitarea (sansa scade).");
                        c.log("SHOCK_FAIL", target, "sansa " + Math.round(chance * 100) + "%");
                    }
                }
            });
            return true;
        }
    }
}
