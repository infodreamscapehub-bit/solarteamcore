package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * SPALATOR DE GEAMURI: o cladire = grup de puncte cu acelasi prefix ("Bloc A - ..."): punctul "platform" (platforma de lucru) si
 * punctele "window" (ferestrele). Misiune: mergi la platforma (urci pe ea), apoi speli fiecare fereastra cu racleta (click dreapta pe
 * geam). Fiecare fereastra are murdarie 0-100%, vizibila doar tie (geamurile din jur devin colorate, iar culoarea se schimba pe masura
 * ce speli). Etape (Iteme profesionale 2.0, optionale, dar cresc calitatea si plata): 1) umezire cu galeata cu apa, 2) solutie de
 * curatare (se consuma), 3) curatare cu buretele (unealta jobului), 4) finisare cu racleta cand murdaria scade sub prag. Starea suprafetei
 * (ud / solutie / finisat / murdarie) e tinuta separat, pe server, per fereastra; blocurile reale (orice geam, geam colorat sau panou)
 * nu sunt inlocuite niciodata - culoarea murdariei e trimisa doar clientului. Ploaia murdareste din nou ferestrele neterminate. Plata: per fereastra (dupa murdaria indepartata) + bonus de finalizare dupa timpul fata de timpul-tinta.
 */
public final class WindowsActivity extends BaseActivity<WindowsActivity.St> {
    static final class Win {
        FJobDef.Point pt;
        double dirt;
        double initial;
        final List<Location> glass = new ArrayList<>();
        int shown = -1;
        boolean paid;
        boolean wet;
        boolean soap;
        boolean polished;

        /** Calitatea curatarii (55-100%): +15 umezire, +15 solutie, +15 finisare cu racleta. */
        int quality() {
            return 55 + (wet ? 15 : 0) + (soap ? 15 : 0) + (polished ? 15 : 0);
        }
    }

    static final class St {
        String building;
        FJobDef.Point platform;
        final List<Win> wins = new ArrayList<>();
        boolean onPlatform;
        long startedAt;
        long nextAt;
        long parMs;
        long lastStroke;
        int doneShift;
        String lastBuilding = "";
        double earnedMission;
    }

    public WindowsActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "windows";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"platform", "Platforme de lucru (una per cladire)"});
        l.add(new String[] {"window", "Ferestre (acelasi prefix ca platforma)"});
        return l;
    }

    private Map<String, List<FJobDef.Point>> buildings(FJobDef jd) {
        Map<String, List<FJobDef.Point>> m = new LinkedHashMap<>();
        Set<String> withPlatform = new LinkedHashSet<>();
        for (FJobDef.Point p : jd.stationList("platform")) withPlatform.add(group(p));
        for (FJobDef.Point w : jd.stationList("window")) {
            String g = group(w);
            if (withPlatform.contains(g)) m.computeIfAbsent(g, k -> new ArrayList<>()).add(w);
        }
        return m;
    }

    @Override
    public String missing(FJobDef jd) {
        return buildings(jd).isEmpty() ? "seteaza cel putin o cladire: un punct 'platform' si cel putin un punct 'window' cu acelasi prefix (ex. 'Bloc A - platforma', 'Bloc A - geam 1')." : null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        s.nextAt = System.currentTimeMillis() + 1500;
        giveTool(p, jd);
    }

    private Material toolMat(FJobDef jd) {
        return Items.mat(jd.cs("tool", "SPONGE"), Material.SPONGE);
    }

    private void giveTool(Player p, FJobDef jd) {
        if (Items.tagged(api.plugin(), p, "fj_tool").isEmpty()) {
            Items.give(p, Items.make(api.plugin(), toolMat(jd), "§bBurete profesional", Items.lore("§7Click dreapta pe geam, pe platforma (dupa apa si solutie).", "§8Unealta de serviciu FJobs"), "fj_tool", "windows"));
        }
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        restore(p, s);
        Items.removeTagged(api.plugin(), p, "fj_tool");
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) {
            restore(p, s);
            Items.removeTagged(api.plugin(), p, "fj_tool");
        }
    }

    private void restore(Player p, St s) {
        for (Win w : s.wins) for (Location l : w.glass) p.sendBlockChange(l, l.getBlock().getBlockData());
        s.wins.clear();
        s.building = null;
        s.platform = null;
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.building != null;
    }

    private static boolean isGlass(Material m) {
        String n = m.name();
        return n.equals("GLASS") || n.equals("GLASS_PANE") || n.endsWith("_STAINED_GLASS") || n.endsWith("_STAINED_GLASS_PANE");
    }

    private void assign(Player p, FJobDef jd, St s, long now) {
        Map<String, List<FJobDef.Point>> bs = buildings(jd);
        if (bs.isEmpty()) return;
        List<String> names = new ArrayList<>(bs.keySet());
        if (names.size() > 1) names.remove(s.lastBuilding);
        String b = names.get(api.rnd().nextInt(names.size()));
        s.building = b;
        s.lastBuilding = b;
        for (FJobDef.Point pl : jd.stationList("platform")) if (group(pl).equals(b)) {
            s.platform = pl;
            break;
        }
        List<FJobDef.Point> ws = new ArrayList<>(bs.get(b));
        Collections.shuffle(ws, api.rnd());
        Diff d = diff(p, jd);
        int n = Math.min(ws.size(), Math.max(1, d.size));
        double minDirt = jd.cd("dirt-min", 55);
        s.wins.clear();
        double total = 0;
        for (int i = 0; i < n; i++) {
            Win w = new Win();
            w.pt = ws.get(i);
            w.dirt = Math.min(100, minDirt + api.rnd().nextInt((int) Math.max(1, 101 - minDirt)));
            w.initial = w.dirt;
            total += w.dirt;
            scan(p, w);
            s.wins.add(w);
        }
        s.parMs = (long) (total / Math.max(5, jd.cd("stroke", 25)) * jd.cd("par-seconds-per-stroke", 2.4) * 1000) + 20_000;
        s.startedAt = now;
        s.onPlatform = false;
        s.earnedMission = 0;
        giveTool(p, jd);
        api.gps(p, s.platform);
        api.toast(p, jd.name, "Misiune: " + b + " (" + n + " ferestre). Urca pe platforma de lucru.");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_BELL", 0.8f, 1.3f);
        api.refreshPanel(p);
    }

    private void scan(Player p, Win w) {
        int r = 2;
        Location c = new Location(Bukkit_world(w.pt.world), w.pt.x, w.pt.y, w.pt.z);
        if (c.getWorld() == null) return;
        int bx = c.getBlockX();
        int by = c.getBlockY();
        int bz = c.getBlockZ();
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r + 1; y++) {
                for (int z = -r; z <= r; z++) {
                    Block b = c.getWorld().getBlockAt(bx + x, by + y, bz + z);
                    if (isGlass(b.getType())) w.glass.add(b.getLocation());
                }
            }
        }
    }

    private static org.bukkit.World Bukkit_world(String n) {
        return org.bukkit.Bukkit.getWorld(n);
    }

    private void paint(Player p, Win w) {
        int lvl = w.dirt > 66 ? 3 : w.dirt > 33 ? 2 : w.dirt > 0 ? 1 : 0;
        if (lvl == w.shown) return;
        w.shown = lvl;
        for (Location l : w.glass) {
            Block b = l.getBlock();
            if (lvl == 0) {
                p.sendBlockChange(l, b.getBlockData());
                continue;
            }
            boolean pane = b.getType().name().endsWith("_PANE");
            String base = lvl == 3 ? "BROWN_STAINED_GLASS" : lvl == 2 ? "GRAY_STAINED_GLASS" : "LIGHT_GRAY_STAINED_GLASS";
            Material m = Items.mat(pane ? base + "_PANE" : base, Material.GRAY_STAINED_GLASS);
            BlockData bd = org.bukkit.Bukkit.createBlockData(m);
            if (pane && b.getBlockData() instanceof org.bukkit.block.data.MultipleFacing && bd instanceof org.bukkit.block.data.MultipleFacing) {
                org.bukkit.block.data.MultipleFacing from = (org.bukkit.block.data.MultipleFacing) b.getBlockData();
                org.bukkit.block.data.MultipleFacing to = (org.bukkit.block.data.MultipleFacing) bd;
                for (org.bukkit.block.BlockFace f : from.getAllowedFaces()) to.setFace(f, from.hasFace(f));
            }
            p.sendBlockChange(l, bd);
        }
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        if (s.building == null) {
            if (now >= s.nextAt) assign(p, jd, s, now);
            return;
        }
        if (s.platform != null) {
            boolean on = near(p, s.platform, jd.cd("platform-radius", 5)) && p.getLocation().getY() >= s.platform.y - 2;
            if (on && !s.onPlatform) {
                api.toast(p, jd.name, "Esti pe platforma. Curata ferestrele cu racleta (click dreapta pe geam).");
                Fx.sound(p, "BLOCK_SCAFFOLDING_PLACE", 0.8f, 1f);
            } else if (!on && s.onPlatform && dist(p, s.platform) > jd.cd("platform-radius", 5) * 3) {
                Fx.bar(p, "§eTe-ai indepartat de platforma de lucru.");
            }
            if (on) s.onPlatform = true;
            else if (dist(p, s.platform) > jd.cd("platform-radius", 5) * 2) s.onPlatform = false;
        }
        boolean rain = p.getWorld().hasStorm() && jd.cd("rain-dirt", 6) > 0;
        for (Win w : s.wins) {
            if (rain && w.dirt > 0 && w.dirt < 100 && now % 15_000 < 1000) w.dirt = Math.min(100, w.dirt + jd.cd("rain-dirt", 6));
            paint(p, w);
            if (w.dirt > 0 && dist(p, w.pt) < 30) Fx.particle(p, "SMOKE_NORMAL", new Location(p.getWorld(), w.pt.x, w.pt.y + 1, w.pt.z), 1, 0.4);
        }
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        St s = state(p);
        if (s.building == null) return;
        if (!isGlass(e.getClickedBlock().getType())) return;
        boolean sponge = "windows".equals(Items.tag(api.plugin(), e.getItem(), "fj_tool"));
        String gtag = Items.tag(api.plugin(), e.getItem(), ro.solarstudios.flowcity.bridge.fjobs.FJobsGear.TAG);
        String g = gtag == null || !gtag.startsWith(jd.id + ":") ? "" : gtag.substring(jd.id.length() + 1);
        boolean water = "water".equals(g);
        boolean soap = "solution".equals(g);
        boolean squeegee = "squeegee".equals(g);
        if (!sponge && !water && !soap && !squeegee) return;
        e.setCancelled(true);
        if (!s.onPlatform) {
            Fx.bar(p, "§cUrca intai pe platforma de lucru.");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - s.lastStroke < jd.cd("stroke-cooldown-ms", 280)) return;
        Location bl = e.getClickedBlock().getLocation();
        Win target = null;
        double bd = Double.MAX_VALUE;
        for (Win w : s.wins) {
            if (w.dirt <= 0) continue;
            double d = Math.pow(bl.getX() - w.pt.x, 2) + Math.pow(bl.getY() - w.pt.y, 2) + Math.pow(bl.getZ() - w.pt.z, 2);
            if (d < bd && d <= Math.pow(jd.cd("window-radius", 4), 2)) {
                bd = d;
                target = w;
            }
        }
        if (target == null) {
            Fx.bar(p, "§7Geamul acesta nu face parte din misiune sau e deja curat.");
            return;
        }
        s.lastStroke = now;
        if (water) {
            if (target.wet) {
                Fx.bar(p, "§7Geamul e deja umezit. Aplica solutia, apoi buretele.");
                return;
            }
            target.wet = true;
            Fx.particle(p, "WATER_SPLASH", bl.clone().add(0.5, 0.5, 0.5), 18, 0.4);
            Fx.sound(p, "ITEM_BUCKET_EMPTY", 0.6f, 1.3f);
            Fx.bar(p, "§bUmezit (" + target.quality() + "% calitate). Urmeaza: solutia de curatare.");
            return;
        }
        if (soap) {
            if (target.soap) {
                Fx.bar(p, "§7Solutia e deja aplicata. Curata cu buretele.");
                return;
            }
            ItemStack it = e.getItem();
            if (it.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
            else it.setAmount(it.getAmount() - 1);
            target.soap = true;
            Fx.particle(p, "SPELL_INSTANT", bl.clone().add(0.5, 0.5, 0.5), 10, 0.3);
            Fx.sound(p, "BLOCK_BREWING_STAND_BREW", 0.4f, 1.6f);
            Fx.bar(p, "§bSolutie aplicata (" + target.quality() + "% calitate). Curata cu buretele.");
            return;
        }
        double finishAt = jd.cd("squeegee-at", 25);
        if (squeegee) {
            if (target.dirt > finishAt) {
                Fx.bar(p, "§7Racleta se foloseste la final: curata intai cu buretele sub " + (int) finishAt + "% murdarie.");
                return;
            }
            target.polished = true;
        }
        double stroke = jd.cd("stroke", 25) * (target.soap ? 1.25 : 1.0) * (target.wet ? 1.15 : 1.0);
        if (squeegee) stroke = target.dirt; // finisarea indeparteaza tot ce a ramas
        double removed = Math.min(stroke, target.dirt);
        target.dirt -= removed;
        Fx.particle(p, "WATER_SPLASH", bl.clone().add(0.5, 0.5, 0.5), 10, 0.3);
        Fx.sound(p, "BLOCK_GLASS_HIT", 0.5f, 1.5f);
        paint(p, target);
        Diff d = diff(p, jd);
        double qf = 0.7 + 0.3 * (target.quality() - 55) / 45.0;   // calitatea scade/creste plata (70%-100%)
        double wpay = jd.cd("window-pay", 14) * (removed / Math.max(1, target.initial)) * qf;
        s.earnedMission += Math.max(0, api.pay(p, jd, wpay * d.pay, 0, false));
        if (target.dirt <= 0) {
            target.paid = true;
            api.pay(p, jd, 0, Math.round(jd.cd("window-xp", 3) * d.xp), false);
            Fx.particle(p, "VILLAGER_HAPPY", bl.clone().add(0.5, 0.5, 0.5), 12, 0.4);
            Fx.sound(p, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.6f, 1.4f);
            Fx.bar(p, "§aGeam curat - calitate " + target.quality() + "%");
            api.addCounter(p, "windows.quality", target.quality());
        } else {
            Fx.bar(p, "§bMurdarie: " + (int) Math.ceil(target.dirt) + "%  |  calitate " + target.quality() + "%" + (target.dirt <= finishAt ? "  |  §efinisaza cu racleta" : ""));
        }
        boolean all = true;
        for (Win w : s.wins) if (w.dirt > 0) all = false;
        if (all) finish(p, jd, s, now);
    }

    private void finish(Player p, FJobDef jd, St s, long now) {
        Diff d = diff(p, jd);
        long el = now - s.startedAt;
        double perf = Math.max(0.6, Math.min(1.35, (double) s.parMs / Math.max(1, el)));
        String grade = perf >= 1.2 ? "S" : perf >= 1.0 ? "A" : perf >= 0.8 ? "B" : "C";
        double bonus = payQ(p, jd, jd.cd("complete-bonus", 40) * s.wins.size() / 3.0, Math.round(jd.cd("complete-xp", 12)), d, perf, true);
        s.doneShift++;
        api.addCounter(p, "windows.buildings", 1);
        api.addCounter(p, "windows.windows", s.wins.size());
        api.toast(p, "Cladire terminata (" + grade + ")", "Bonus " + money(Math.max(0, bonus)) + " | timp " + time(el / 1000) + " (tinta " + time(s.parMs / 1000) + ")");
        Fx.sound(p, "ENTITY_PLAYER_LEVELUP", 0.7f, 1.1f);
        restore(p, s);
        s.nextAt = now + 6000;
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        if (s.building == null) {
            lines.add("Se alege o cladire...");
            return -1;
        }
        lines.add("Cladire: " + s.building);
        if (!s.onPlatform) {
            lines.add("Urca pe platforma (" + Math.round(dist(p, s.platform)) + " m)");
        }
        double left = 0;
        double tot = 0;
        int done = 0;
        for (Win w : s.wins) {
            left += w.dirt;
            tot += w.initial;
            if (w.dirt <= 0) done++;
        }
        lines.add("Ferestre: " + done + "/" + s.wins.size());
        lines.add("Timp: " + time((System.currentTimeMillis() - s.startedAt) / 1000) + " / tinta " + time(s.parMs / 1000));
        return tot <= 0 ? 1 : 1.0 - left / tot;
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status(s.building == null ? "Astepti o cladire" : "Cladire: " + s.building);
        if (s.building != null) {
            pn.obj(s.onPlatform ? "Esti pe platforma. Curata toate ferestrele." : "Urca pe platforma de lucru a cladirii.");
            for (Win w : s.wins) pn.obj(w.pt.name + ": " + (w.dirt <= 0 ? "curat" : "murdarie " + (int) Math.ceil(w.dirt) + "%")
                + "  [" + (w.wet ? "ud" : "uscat") + (w.soap ? ", solutie" : "") + (w.polished ? ", finisat" : "") + "] " + w.quality() + "%");
        } else {
            pn.obj("Porneste tura: primesti o cladire si racleta de geamuri.");
        }
        pn.stat("Cladiri terminate", String.valueOf(api.counter(p, "windows.buildings")));
        pn.stat("Ferestre spalate", String.valueOf(api.counter(p, "windows.windows")));
        pn.stat("Cladiri setate", String.valueOf(buildings(jd).size()));
        pn.btn("gps", "Navigheaza la platforma", s.platform != null);
        pn.btn("tool", "Primeste buretele", true);
        pn.btn("skip", "Alta cladire", s.building != null);
        pn.reward("Per fereastra: " + money(jd.cd("window-pay", 14)) + " (proportional cu murdaria indepartata)");
        pn.reward("Bonus de cladire: " + money(jd.cd("complete-bonus", 40)) + " pentru 3 ferestre, dupa timp (nota S/A/B/C)");
        pn.tip("Ordinea: galeata cu apa -> solutie -> burete -> racleta (sub " + (int) jd.cd("squeegee-at", 25) + "%). Fiecare etapa creste calitatea si plata.");
        pn.tip("Ploaia murdareste din nou ferestrele neterminate.");
        pn.tip("Trebuie sa fii pe platforma; geamurile colorate sunt vazute doar de tine.");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, com.google.gson.JsonObject j) {
        St s = state(p);
        if ("gps".equals(act) && s.platform != null) {
            api.gps(p, s.platform);
            return true;
        }
        if ("tool".equals(act)) {
            giveTool(p, jd);
            return true;
        }
        if ("skip".equals(act) && s.building != null) {
            if (cooling(p, "skip", 60_000)) {
                api.msg(p, false, "Poti schimba cladirea o data pe minut.");
                return false;
            }
            restore(p, s);
            s.nextAt = System.currentTimeMillis() + 1500;
            return true;
        }
        return false;
    }
}
