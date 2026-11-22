package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;
import ro.solarstudios.flowcity.bridge.hype.PlacedSet;

/**
 * FERMIER: lucrezi in loturile agricole ("plot", raza din config). 1) PLANTARE: culturile puse in lot dau un mic bonus (o data per pozitie).
 * 2) UDARE: click dreapta cu galeata cu apa pe pamant arat/cultura uda culturile din jur (fara sa puna apa): fiecare cultura nematura
 * creste cu o treapta (efect real) si e marcata "udata" 150 s. 3) RECOLTARE: se plateste doar planta MATURA din lot; produsul creeaza o
 * alocatie (si una de calitate daca era udata). 4) TRANSPORT si VANZARE: la piata ("market", click dreapta) vinzi produsele din
 * inventar (in limita alocatiei); pretul creste cu calitatea (udat) si cu distanta fata de lot. Contract per tura pe produs.
 */
public final class FarmerActivity extends GatherBase {
    static final class Crop {
        Material block;
        Material product;
        double price;
        long xp;
        double plantPay;
    }

    private final Map<String, Long> wet = new HashMap<>();
    private final PlacedSet planted = new PlacedSet(3 * 3_600_000L, 20000);

    public FarmerActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "farmer";
    }

    @Override
    protected String prefix() {
        return "farm";
    }

    @Override
    protected String depotKind() {
        return "market";
    }

    @Override
    protected String depotLabel() {
        return "piata";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"plot", "Loturi agricole (raza din config)"});
        l.add(new String[] {"market", "Piata (vanzare)"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("plot").isEmpty()) return "seteaza cel putin un lot agricol ('plot').";
        if (jd.stationList("market").isEmpty()) return "seteaza piata unde se vand produsele ('market').";
        return null;
    }

    private Map<Material, Crop> crops(FJobDef jd) {
        Map<Material, Crop> m = new LinkedHashMap<>();
        ConfigurationSection s = jd.conf == null ? null : jd.conf.getConfigurationSection("crops");
        if (s != null && !s.getKeys(false).isEmpty()) {
            for (String k : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(k);
                Material b = Material.matchMaterial(k);
                if (c == null || b == null) continue;
                Crop x = new Crop();
                x.block = b;
                x.product = Items.mat(c.getString("product", k), b);
                x.price = Math.max(0, c.getDouble("price", 1.5));
                x.xp = Math.max(0, c.getLong("xp", 2));
                x.plantPay = Math.max(0, c.getDouble("plant-pay", 0.2));
                m.put(b, x);
            }
        } else {
            Object[][] d = {{"WHEAT", "WHEAT", 1.6}, {"CARROTS", "CARROT", 1.4}, {"POTATOES", "POTATO", 1.4}, {"BEETROOTS", "BEETROOT", 1.8}};
            for (Object[] r : d) {
                Material b = Material.matchMaterial((String) r[0]);
                if (b == null) continue;
                Crop x = new Crop();
                x.block = b;
                x.product = Items.mat((String) r[1], b);
                x.price = (Double) r[2];
                x.xp = 2;
                x.plantPay = 0.2;
                m.put(b, x);
            }
        }
        return m;
    }

    @Override
    protected Map<Material, Double> prices(FJobDef jd) {
        Map<Material, Double> m = new LinkedHashMap<>();
        for (Crop c : crops(jd).values()) m.put(c.product, c.price);
        return m;
    }

    private FJobDef.Point plotOf(Block b, FJobDef jd) {
        double r = jd.cd("plot-radius", 16);
        for (FJobDef.Point z : jd.stationList("plot")) {
            if (!z.world.equals(b.getWorld().getName())) continue;
            double dx = b.getX() + 0.5 - z.x;
            double dz = b.getZ() + 0.5 - z.z;
            if (dx * dx + dz * dz <= r * r && Math.abs(b.getY() - z.y) <= 8) return z;
        }
        return null;
    }

    private static String key(Block b) {
        return PlacedSet.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    @Override
    protected void tickGather(Player p, FJobDef jd, G g, long now) {
        if (now % 20_000 < 1000) {
            for (Iterator<Map.Entry<String, Long>> it = wet.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue() < now) it.remove();
        }
        mark(p, pts(jd, "plot"), "VILLAGER_HAPPY", 30);
        planted.purge(now);
    }

    @Override
    public void onPlace(Player p, FJobDef jd, BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        Crop c = crops(jd).get(b.getType());
        if (c == null || plotOf(b, jd) == null) return;
        long now = System.currentTimeMillis();
        if (planted.contains(key(b), now)) return;
        planted.mark(key(b), now);
        api.pay(p, jd, c.plantPay, 0, false);
        api.addCounter(p, "farm.planted", 1);
        Fx.sound(p, "ITEM_CROP_PLANT", 0.6f, 1f);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getHand() == EquipmentSlot.HAND && e.getClickedBlock() != null
            && p.getInventory().getItemInMainHand().getType() == Material.WATER_BUCKET) {
            Block cb = e.getClickedBlock();
            if (plotOf(cb, jd) != null && (cb.getType() == Material.FARMLAND || crops(jd).containsKey(cb.getType()))) {
                e.setCancelled(true);
                if (!cooling(p, "water", 900)) water(p, jd, cb);
                return;
            }
        }
        super.onInteract(p, jd, e);
    }

    private void water(Player p, FJobDef jd, Block center) {
        int r = (int) jd.cd("water-radius", 2);
        long now = System.currentTimeMillis();
        long until = now + (long) (jd.cd("water-seconds", 150) * 1000);
        int n = 0;
        Map<Material, Crop> cs = crops(jd);
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = 0; y <= 1; y++) {
                    Block b = center.getRelative(x, y, z);
                    if (!cs.containsKey(b.getType())) continue;
                    Long w = wet.get(key(b));
                    if (w != null && w > now) continue;
                    BlockData bd = b.getBlockData();
                    if (bd instanceof Ageable) {
                        Ageable a = (Ageable) bd;
                        if (a.getAge() < a.getMaximumAge()) {
                            a.setAge(a.getAge() + 1);
                            b.setBlockData(a);
                        }
                    }
                    wet.put(key(b), until);
                    n++;
                }
            }
        }
        if (n == 0) {
            Fx.bar(p, "§7Nu sunt culturi de udat aici (sau sunt deja udate).");
            return;
        }
        Fx.particle(p, "WATER_SPLASH", center.getLocation().add(0.5, 1.2, 0.5), 25, 0.9);
        Fx.sound(p, "ITEM_BUCKET_EMPTY", 0.6f, 1.4f);
        api.addCounter(p, "farm.watered", n);
        api.pay(p, jd, n * jd.cd("water-pay", 0.25), Math.round(n * jd.cd("water-xp", 0.3)), false);
        Fx.bar(p, "§bAi udat " + n + " culturi");
    }

    @Override
    public void onBreak(Player p, FJobDef jd, BlockBreakEvent e) {
        Block b = e.getBlock();
        Crop c = crops(jd).get(b.getType());
        if (c == null || plotOf(b, jd) == null) return;
        if (!(b.getBlockData() instanceof Ageable)) return;
        Ageable a = (Ageable) b.getBlockData();
        if (a.getAge() < a.getMaximumAge()) return;
        G g = state(p);
        Diff d = diff(p, jd);
        long now = System.currentTimeMillis();
        Long w = wet.remove(key(b));
        addAllowance(p, c.product, 1);
        if (w != null && w > now) api.addCounter(p, "farm.wet." + c.product.name(), 1);
        g.collected++;
        api.addCounter(p, "farm.harvested", 1);
        api.pay(p, jd, 0, Math.round(c.xp * d.xp), false);
        Fx.particle(p, "VILLAGER_HAPPY", b.getLocation().add(0.5, 0.5, 0.5), 5, 0.3);
    }

    @Override
    protected double sellMult(Player p, FJobDef jd, Material m, long count, FJobDef.Point at) {
        long allow = Math.max(1, allowance(p, m));
        long wetN = api.counter(p, "farm.wet." + m.name());
        double ratio = Math.min(1.0, (double) wetN / allow);
        api.addCounter(p, "farm.wet." + m.name(), -Math.min(wetN, Math.round(count * ratio)));
        double minDist = Double.MAX_VALUE;
        for (FJobDef.Point z : jd.stationList("plot")) if (z.world.equals(at.world)) minDist = Math.min(minDist, z.dist(at));
        double transport = minDist == Double.MAX_VALUE ? 0 : Math.min(jd.cd("transport-max", 0.25), minDist / 100.0 * jd.cd("transport-per-100m", 0.04));
        return 1.0 + ratio * jd.cd("water-bonus", 0.25) + transport;
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, G g, Panel pn) {
        fillCommon(p, jd, g, pn);
        pn.obj("Planteaza in loturile agricole, uda cu galeata cu apa, recolteaza culturile mature.");
        pn.obj("Du produsele la piata (click dreapta) si vinde-le.");
        pn.stat("Plantate", String.valueOf(api.counter(p, "farm.planted")));
        pn.stat("Culturi udate", String.valueOf(api.counter(p, "farm.watered")));
        pn.stat("Recoltate", String.valueOf(api.counter(p, "farm.harvested")));
        pn.stat("Loturi setate", String.valueOf(pts(jd, "plot").size()));
        pn.reward("Calitate (udat): pana la +" + (int) (jd.cd("water-bonus", 0.25) * 100) + "% pret; transport: +" + (int) (jd.cd("transport-per-100m", 0.04) * 100) + "% / 100 m");
        pn.tip("Udarea: click dreapta cu galeata cu apa pe pamant arat (nu pune apa).");
        pn.tip("Se plateste doar recolta matura din loturi.");
    }
}
