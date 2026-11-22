package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;
import ro.solarstudios.flowcity.bridge.hype.PlacedSet;

/**
 * CONSTRUCTOR: misiuni la santiere ("site", raza din config). 1) Iei materialele de la depozitul de materiale ("supply", click dreapta):
 * primesti iteme marcate (doar acestea conteaza la misiune). 2) Misiunea e de doua feluri: CONSTRUIRE (pui N blocuri din materialul
 * cerut in raza santierului) sau REPARATIE (sparge blocurile crapate din santier, cele din repair-blocks, si pune la loc materialul;
 * se alege automat doar daca exista astfel de blocuri construite de admin). 3) Cand obiectivul e complet primesti bonus de finalizare
 * dupa timp. Blocurile se platesc o singura data per pozitie (anti-farm).
 */
public final class BuilderActivity extends BaseActivity<BuilderActivity.St> {
    static final class St {
        FJobDef.Point site;
        boolean repair;
        Material mat = Material.STONE_BRICKS;
        int need;
        int done;
        String orderId = "";
        int seq;
        long startedAt;
        long nextAt;
        int supplied;
        boolean refilled;
        final List<Location> targets = new ArrayList<>();
        final Set<String> broken = new HashSet<>();
        final Set<String> fixed = new HashSet<>();
        int missions;
        String lastSite = "";
    }

    private final PlacedSet counted = new PlacedSet(3 * 3_600_000L, 30000);

    public BuilderActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "builder";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"site", "Santiere (raza din config)"});
        l.add(new String[] {"supply", "Depozit de materiale"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("site").isEmpty()) return "seteaza cel putin un santier ('site').";
        if (jd.stationList("supply").isEmpty()) return "seteaza depozitul de materiale ('supply').";
        return null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        s.nextAt = System.currentTimeMillis() + 1500;
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        Items.removeTagged(api.plugin(), p, "fj_mat");
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) Items.removeTagged(api.plugin(), p, "fj_mat");
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.site != null;
    }

    private static String key(Location l) {
        return PlacedSet.key(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private List<Material> repairBlocks(FJobDef jd) {
        List<String> names = jd.conf == null ? new ArrayList<String>() : jd.conf.getStringList("repair-blocks");
        if (names.isEmpty()) names = java.util.Arrays.asList("CRACKED_STONE_BRICKS", "CRACKED_NETHER_BRICKS", "CRACKED_POLISHED_BLACKSTONE_BRICKS");
        List<Material> l = new ArrayList<>();
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) l.add(m);
        }
        return l;
    }

    private void assign(Player p, FJobDef jd, St s, long now) {
        List<FJobDef.Point> sites = new ArrayList<>(pts(jd, "site"));
        if (sites.isEmpty()) return;
        if (sites.size() > 1) sites.removeIf(x -> x.name.equals(s.lastSite));
        s.site = sites.get(api.rnd().nextInt(sites.size()));
        s.lastSite = s.site.name;
        Diff d = diff(p, jd);
        s.orderId = Integer.toString(++s.seq, 36) + api.rnd().nextInt(99);
        s.done = 0;
        s.supplied = 0;
        s.refilled = false;
        s.targets.clear();
        s.broken.clear();
        s.fixed.clear();
        s.startedAt = now;
        s.repair = false;
        org.bukkit.World w = org.bukkit.Bukkit.getWorld(s.site.world);
        if (w != null && api.rnd().nextDouble() < jd.cd("repair-chance", 0.5)) {
            List<Material> rb = repairBlocks(jd);
            int r = (int) jd.cd("site-radius", 14);
            int want = Math.max(2, d.size * 2);
            for (int x = -r; x <= r && s.targets.size() < want; x++) {
                for (int y = -6; y <= 8 && s.targets.size() < want; y++) {
                    for (int z = -r; z <= r && s.targets.size() < want; z++) {
                        Block b = w.getBlockAt((int) s.site.x + x, (int) s.site.y + y, (int) s.site.z + z);
                        if (rb.contains(b.getType())) s.targets.add(b.getLocation());
                    }
                }
            }
            if (s.targets.size() >= 2) {
                s.repair = true;
                s.need = s.targets.size();
                s.mat = Items.mat(jd.cs("repair-with", "STONE_BRICKS"), Material.STONE_BRICKS);
            } else {
                s.targets.clear();
            }
        }
        if (!s.repair) {
            List<String> mats = jd.conf == null ? new ArrayList<String>() : jd.conf.getStringList("materials");
            if (mats.isEmpty()) mats = java.util.Arrays.asList("STONE_BRICKS", "BRICKS", "OAK_PLANKS", "COBBLESTONE", "SMOOTH_STONE");
            s.mat = Items.mat(mats.get(api.rnd().nextInt(mats.size())), Material.STONE_BRICKS);
            s.need = Math.max(6, d.size * (int) jd.cd("blocks-per-size", 8));
        }
        api.gps(p, pts(jd, "supply").get(0));
        api.toast(p, jd.name, (s.repair ? "Reparatie" : "Constructie") + " la " + s.site.name + ": " + s.need + " x " + nice(s.mat) + ". Ia materialele de la depozit.");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_BELL", 0.8f, 0.9f);
        api.refreshPanel(p);
    }

    private static String nice(Material m) {
        String t = m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        if (s.site == null) {
            if (now >= s.nextAt) assign(p, jd, s, now);
            return;
        }
        if (now - s.startedAt > jd.cd("mission-timeout-seconds", 900) * 1000) {
            api.toast(p, jd.name, "Misiunea a expirat.");
            clear(p, s, 5000);
            return;
        }
        mark(p, java.util.Collections.singletonList(s.site), "END_ROD", 60);
        mark(p, pts(jd, "supply"), "VILLAGER_HAPPY", 40);
        for (Location t : s.targets) {
            if (s.fixed.contains(key(t))) continue;
            if (p.getLocation().distanceSquared(t) < 900) Fx.particle(p, "CRIT", t.clone().add(0.5, 0.5, 0.5), 2, 0.3);
        }
    }

    private void clear(Player p, St s, long delay) {
        s.site = null;
        s.targets.clear();
        s.broken.clear();
        s.fixed.clear();
        s.nextAt = System.currentTimeMillis() + delay;
        Items.removeTagged(api.plugin(), p, "fj_mat");
    }

    private boolean inSite(Location l, FJobDef jd, St s) {
        return s.site != null && nearLoc(l, s.site, jd.cd("site-radius", 14)) && Math.abs(l.getY() - s.site.y) <= 14;
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        St s = state(p);
        if (s.site == null) return;
        if (nearest(p, pts(jd, "supply"), jd.cd("supply-radius", 5)) == null) return;
        e.setCancelled(true);
        if (cooling(p, "supply", 1500)) return;
        int have = 0;
        for (org.bukkit.inventory.ItemStack it : Items.tagged(api.plugin(), p, "fj_mat")) have += it.getAmount();
        int remaining = s.need - s.done;
        int give = remaining - have;
        if (s.supplied == 0) give = remaining + (int) Math.ceil(remaining * jd.cd("spare", 0.15));
        else if (give <= 0 || s.refilled) {
            Fx.bar(p, give <= 0 ? "§eAi deja destule materiale." : "§cAi luat deja un refill pentru misiune.");
            return;
        } else {
            s.refilled = true;
        }
        int left = give;
        while (left > 0) {
            int n = Math.min(left, 64);
            org.bukkit.inventory.ItemStack it = Items.make(api.plugin(), s.mat, "§6" + nice(s.mat) + " de santier", Items.lore("§7Santier: " + s.site.name, "§8Folosesti doar la misiune"), "fj_mat", s.orderId);
            it.setAmount(n);
            Items.give(p, it);
            left -= n;
        }
        s.supplied += give;
        Fx.sound(p, "ENTITY_ITEM_PICKUP", 0.8f, 0.9f);
        api.gps(p, s.site);
        api.toast(p, jd.name, "Ai primit " + give + " x " + nice(s.mat) + ". Mergi la santier: " + s.site.name + ".");
        api.refreshPanel(p);
    }

    @Override
    public void onBreak(Player p, FJobDef jd, BlockBreakEvent e) {
        St s = state(p);
        if (s.site == null || !s.repair) return;
        String k = key(e.getBlock().getLocation());
        for (Location t : s.targets) {
            if (key(t).equals(k) && !s.fixed.contains(k)) {
                s.broken.add(k);
                Fx.sound(p, "BLOCK_STONE_BREAK", 0.6f, 1.2f);
                Fx.bar(p, "§eBloc crapat scos: pune la loc " + nice(s.mat) + ".");
                return;
            }
        }
    }

    @Override
    public void onPlace(Player p, FJobDef jd, BlockPlaceEvent e) {
        St s = state(p);
        if (s.site == null || s.done >= s.need) return;
        Block b = e.getBlockPlaced();
        if (b.getType() != s.mat || !s.orderId.equals(Items.tag(api.plugin(), e.getItemInHand(), "fj_mat"))) return;
        if (!inSite(b.getLocation(), jd, s)) {
            Fx.bar(p, "§eInafara santierului: nu se numara.");
            return;
        }
        long now = System.currentTimeMillis();
        String k = key(b.getLocation());
        if (s.repair) {
            if (!s.broken.contains(k)) return;
            s.broken.remove(k);
            s.fixed.add(k);
        } else {
            if (counted.contains(k, now)) return;
            counted.mark(k, now);
        }
        s.done++;
        Diff d = diff(p, jd);
        api.pay(p, jd, jd.cd("block-pay", 1.2) * d.pay, Math.round(jd.cd("block-xp", 0.6) * d.xp), false);
        Fx.particle(p, "CRIT", b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3);
        Fx.bar(p, "§aSantier: " + s.done + "/" + s.need);
        if (s.done >= s.need) finish(p, jd, s);
    }

    private void finish(Player p, FJobDef jd, St s) {
        long now = System.currentTimeMillis();
        Diff d = diff(p, jd);
        double par = (s.need * jd.cd("par-seconds-per-block", 6) + 60) * 1000;
        double perf = Math.max(0.7, Math.min(1.35, par / Math.max(1, now - s.startedAt)));
        double bonus = payQ(p, jd, jd.cd("complete-bonus", 45) * (s.need / 16.0 + 0.5) * (s.repair ? 1.2 : 1.0), Math.round(jd.cd("complete-xp", 14)), d, perf, true);
        s.missions++;
        api.addCounter(p, s.repair ? "builder.repairs" : "builder.builds", 1);
        api.addCounter(p, "builder.blocks", s.need);
        api.toast(p, "Santier terminat: " + s.site.name, "Bonus " + money(Math.max(0, bonus)) + " | timp " + time((now - s.startedAt) / 1000));
        Fx.sound(p, "ENTITY_PLAYER_LEVELUP", 0.7f, 1.0f);
        clear(p, s, 6000);
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.site == null) {
            lines.add("Se asteapta o misiune...");
            return -1;
        }
        lines.add((s.repair ? "Reparatie: " : "Constructie: ") + s.site.name);
        lines.add(nice(s.mat) + ": " + s.done + "/" + s.need);
        if (s.supplied == 0) lines.add("Ia materialele de la depozit");
        else lines.add("Santier: " + Math.round(dist(p, s.site)) + " m");
        return (double) s.done / Math.max(1, s.need);
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.site == null ? "Astepti o misiune" : s.site.name);
        if (s.site != null) {
            pn.obj((s.supplied > 0 ? "[x] " : "[ ] ") + "Ia materialele de la depozit (click dreapta)");
            pn.obj(s.repair ? "Scoate blocurile crapate si pune " + nice(s.mat) + " la loc: " + s.done + "/" + s.need
                : "Pune " + s.need + " x " + nice(s.mat) + " in raza santierului: " + s.done + "/" + s.need);
            pn.obj("Foloseste DOAR materialele marcate primite de la depozit.");
        } else {
            pn.obj("Porneste tura: primesti o misiune de santier.");
        }
        pn.stat("Constructii", String.valueOf(api.counter(p, "builder.builds")));
        pn.stat("Reparatii", String.valueOf(api.counter(p, "builder.repairs")));
        pn.stat("Blocuri", String.valueOf(api.counter(p, "builder.blocks")));
        pn.stat("Santiere setate", String.valueOf(pts(jd, "site").size()));
        pn.btn("gps", "Navigheaza", s.site != null);
        pn.btn("skip", "Alta misiune", s.site != null && s.done == 0);
        pn.reward(money(jd.cd("block-pay", 1.2)) + " per bloc + bonus de finalizare " + money(jd.cd("complete-bonus", 45)) + " (dupa timp)");
        pn.tip("Reparatia apare doar daca santierul are blocuri crapate construite de admin.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        St s = state(p);
        if ("gps".equals(act) && s.site != null) {
            boolean needSupply = s.supplied == 0;
            api.gps(p, needSupply ? pts(jd, "supply").get(0) : s.site);
            return true;
        }
        if ("skip".equals(act) && s.site != null && s.done == 0) {
            if (cooling(p, "skip", 45_000)) {
                api.msg(p, false, "Poti schimba misiunea o data la 45 s.");
                return false;
            }
            clear(p, s, 1500);
            return true;
        }
        return false;
    }
}
