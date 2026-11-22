package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * MINER: minerezi doar in zonele setate ("zone", raza din config) cu unealta potrivita (treapta minima per minereu, ca "echipament":
 * lemn/piatra/fier/diamant). Fiecare minereu spart legal (nu pus de jucatori) da XP si creeaza o alocatie pentru resursa obtinuta; la
 * depozit ("depot", click dreapta) predai resursele din inventar (in limita alocatiei) si primesti bani dupa tabelul de preturi. Contract
 * de colectare per tura cu bonus si termen. Minereurile rare se deblocheaza dupa nivelul jobului.
 */
public final class MinerActivity extends GatherBase {
    static final class Ore {
        Material block;
        Material drop;
        int tier;
        long xp;
        int minLevel;
        double price;
    }

    public MinerActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "miner";
    }

    @Override
    protected String prefix() {
        return "miner";
    }

    @Override
    protected String depotKind() {
        return "depot";
    }

    @Override
    protected String depotLabel() {
        return "depozitul de minereu";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"zone", "Zone de minerit (raza din config)"});
        l.add(new String[] {"depot", "Depozit de predare resurse"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("zone").isEmpty()) return "seteaza cel putin o zona de minerit ('zone').";
        if (jd.stationList("depot").isEmpty()) return "seteaza depozitul unde se predau resursele ('depot').";
        return null;
    }

    private Map<Material, Ore> ores(FJobDef jd) {
        Map<Material, Ore> m = new LinkedHashMap<>();
        ConfigurationSection s = jd.conf == null ? null : jd.conf.getConfigurationSection("ores");
        if (s != null && !s.getKeys(false).isEmpty()) {
            for (String k : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(k);
                Material b = Material.matchMaterial(k);
                if (c == null || b == null) continue;
                Ore o = new Ore();
                o.block = b;
                o.drop = Items.mat(c.getString("drop", k), b);
                o.tier = c.getInt("tier", 0);
                o.xp = Math.max(0, c.getLong("xp", 2));
                o.minLevel = Math.max(0, c.getInt("min-level", 0));
                o.price = Math.max(0, c.getDouble("price", 2));
                m.put(b, o);
            }
        } else {
            Object[][] def = {{"COAL_ORE", "COAL", 0, 2L, 0, 1.8}, {"IRON_ORE", "IRON_ORE", 1, 3L, 0, 3.0}, {"REDSTONE_ORE", "REDSTONE", 2, 3L, 2, 1.2},
                {"LAPIS_ORE", "LAPIS_LAZULI", 1, 3L, 2, 1.6}, {"GOLD_ORE", "GOLD_ORE", 2, 5L, 4, 5.0}, {"DIAMOND_ORE", "DIAMOND", 2, 9L, 7, 18.0},
                {"EMERALD_ORE", "EMERALD", 2, 9L, 9, 22.0}};
            for (Object[] d : def) {
                Material b = Material.matchMaterial((String) d[0]);
                if (b == null) continue;
                Ore o = new Ore();
                o.block = b;
                o.drop = Items.mat((String) d[1], b);
                o.tier = (Integer) d[2];
                o.xp = (Long) d[3];
                o.minLevel = (Integer) d[4];
                o.price = (Double) d[5];
                m.put(b, o);
            }
        }
        return m;
    }

    @Override
    protected Map<Material, Double> prices(FJobDef jd) {
        Map<Material, Double> m = new LinkedHashMap<>();
        for (Ore o : ores(jd).values()) m.put(o.drop, o.price);
        return m;
    }

    @Override
    protected List<Material> contractPool(Player p, FJobDef jd) {
        int lvl = api.jobLevel(p, jd.id);
        List<Material> l = new ArrayList<>();
        for (Ore o : ores(jd).values()) if (o.minLevel <= lvl && o.price <= jd.cd("contract-max-price", 8) && !l.contains(o.drop)) l.add(o.drop);
        return l;
    }

    private boolean inZone(Block b, FJobDef jd) {
        double r = jd.cd("zone-radius", 40);
        double h = jd.cd("zone-height", 70);
        for (FJobDef.Point z : jd.stationList("zone")) {
            if (!z.world.equals(b.getWorld().getName())) continue;
            double dx = b.getX() + 0.5 - z.x;
            double dz = b.getZ() + 0.5 - z.z;
            if (dx * dx + dz * dz <= r * r && Math.abs(b.getY() - z.y) <= h) return true;
        }
        return false;
    }

    @Override
    public void onBreak(Player p, FJobDef jd, BlockBreakEvent e) {
        Block b = e.getBlock();
        Ore o = ores(jd).get(b.getType());
        if (o == null) return;
        if (!inZone(b, jd)) {
            G g = state(p);
            long now = System.currentTimeMillis();
            if (now - g.lastWarn > 8000) {
                g.lastWarn = now;
                Fx.bar(p, "§eAici nu e zona de minerit: minereul nu se plateste.");
            }
            return;
        }
        if (api.isPlaced(b)) return;
        int lvl = api.jobLevel(p, jd.id);
        ItemStack tool = p.getInventory().getItemInMainHand();
        int t = tier(tool == null ? null : tool.getType());
        if (!tool.getType().name().endsWith("_PICKAXE")) return;
        G g = state(p);
        if (lvl < o.minLevel) {
            long now = System.currentTimeMillis();
            if (now - g.lastWarn > 6000) {
                g.lastWarn = now;
                Fx.bar(p, "§e" + nice(o.block) + " se deblocheaza la nivelul " + o.minLevel + " al jobului.");
            }
            return;
        }
        if (t < o.tier) {
            long now = System.currentTimeMillis();
            if (now - g.lastWarn > 6000) {
                g.lastWarn = now;
                Fx.bar(p, "§cAi nevoie de o spaca mai buna pentru " + nice(o.block) + ".");
            }
            return;
        }
        Diff d = diff(p, jd);
        addAllowance(p, o.drop, 1);
        g.collected++;
        api.addCounter(p, "miner.mined", 1);
        api.pay(p, jd, 0, Math.round(o.xp * d.xp), false);
        Fx.particle(p, "CRIT", b.getLocation().add(0.5, 0.5, 0.5), 8, 0.3);
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, G g, Panel pn) {
        fillCommon(p, jd, g, pn);
        pn.obj("Mineraza in zonele de minerit cu spaca potrivita.");
        pn.obj("Du minereul la depozit (click dreapta) pentru a fi platit.");
        pn.stat("Minereuri sparte", String.valueOf(api.counter(p, "miner.mined")));
        pn.stat("Predate (total)", String.valueOf(api.counter(p, "miner.sold")));
        pn.stat("Zone setate", String.valueOf(pts(jd, "zone").size()));
        int lvl = api.jobLevel(p, jd.id);
        for (Ore o : ores(jd).values()) {
            if (o.minLevel > lvl || o.tier > 0) {
                pn.tip(nice(o.block) + ": spaca treapta " + o.tier + (o.minLevel > lvl ? ", nivel " + o.minLevel : ""));
            }
        }
        pn.tip("Blocurile puse de jucatori nu se platesc.");
    }
}
