package ro.solarstudios.flowcity.bridge.fjobs.act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * TAIETOR DE LEMNE: lucrezi in zonele de padure ("forest"). Un copac e identificat pe server: butucul spart trebuie sa fie natural (nu pus
 * de jucatori) si sa aiba frunze in jur; cu securea in mana, copacul se taie ENTIRE (trunchiul conectat cade, cu itemele reale, in limita
 * din config) si primesti XP + alocatie pentru lemnul respectiv. Lemnul se preda la depozit ("depot", click dreapta) dupa tabelul de
 * preturi. Contract per tura pe specie (ex. 24 x stejar). Plantarea de puieti in padure da un mic bonus (o data per pozitie).
 */
public final class LumberjackActivity extends GatherBase {
    private final ro.solarstudios.flowcity.bridge.hype.PlacedSet saplings = new ro.solarstudios.flowcity.bridge.hype.PlacedSet(3 * 3_600_000L, 20000);

    public LumberjackActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "lumberjack";
    }

    @Override
    protected String prefix() {
        return "wood";
    }

    @Override
    protected String depotKind() {
        return "depot";
    }

    @Override
    protected String depotLabel() {
        return "depozitul de lemn";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"forest", "Zone de padure (raza din config)"});
        l.add(new String[] {"depot", "Depozit de lemn"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        if (jd.stationList("forest").isEmpty()) return "seteaza cel putin o zona de padure ('forest').";
        if (jd.stationList("depot").isEmpty()) return "seteaza depozitul de lemn ('depot').";
        return null;
    }

    @Override
    protected Map<Material, Double> prices(FJobDef jd) {
        Map<String, Double> def = new LinkedHashMap<>();
        def.put("OAK_LOG", 2.2);
        def.put("SPRUCE_LOG", 2.2);
        def.put("BIRCH_LOG", 2.4);
        def.put("JUNGLE_LOG", 3.0);
        def.put("ACACIA_LOG", 2.8);
        def.put("DARK_OAK_LOG", 3.2);
        return priceTable(jd, "woods", def);
    }

    private boolean inForest(Block b, FJobDef jd) {
        double r = jd.cd("forest-radius", 60);
        for (FJobDef.Point z : jd.stationList("forest")) {
            if (!z.world.equals(b.getWorld().getName())) continue;
            double dx = b.getX() + 0.5 - z.x;
            double dz = b.getZ() + 0.5 - z.z;
            if (dx * dx + dz * dz <= r * r && Math.abs(b.getY() - z.y) <= 40) return true;
        }
        return false;
    }

    private static boolean leaf(Material m) {
        return m.name().endsWith("_LEAVES");
    }

    private boolean looksLikeTree(Block b) {
        int leaves = 0;
        for (int x = -3; x <= 3; x++) {
            for (int y = 0; y <= 6; y++) {
                for (int z = -3; z <= 3; z++) {
                    if (leaf(b.getRelative(x, y, z).getType())) leaves++;
                }
            }
        }
        return leaves >= 4;
    }

    @Override
    public void onBreak(Player p, FJobDef jd, BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        Double price = prices(jd).get(m);
        if (price == null) return;
        G g = state(p);
        long now = System.currentTimeMillis();
        if (!inForest(b, jd)) {
            if (now - g.lastWarn > 8000) {
                g.lastWarn = now;
                Fx.bar(p, "§eAici nu e zona de padure: lemnul nu se plateste.");
            }
            return;
        }
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_AXE")) {
            if (now - g.lastWarn > 8000) {
                g.lastWarn = now;
                Fx.bar(p, "§cFoloseste o secure ca sa tai copacul.");
            }
            return;
        }
        if (api.isPlaced(b) || !looksLikeTree(b)) {
            if (now - g.lastWarn > 8000) {
                g.lastWarn = now;
                Fx.bar(p, "§7Nu e un copac natural: nu se plateste.");
            }
            return;
        }
        // copacul identificat: taie tot trunchiul conectat (butuci de acelasi tip, nepusi de jucatori)
        int max = (int) jd.cd("max-logs-per-tree", 48);
        List<Block> trunk = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> q = new ArrayDeque<>();
        q.add(b);
        seen.add(b);
        while (!q.isEmpty() && trunk.size() < max) {
            Block c = q.poll();
            trunk.add(c);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = c.getRelative(dx, dy, dz);
                        if (n.getType() == m && !seen.contains(n) && !api.isPlaced(n)) {
                            seen.add(n);
                            q.add(n);
                        }
                    }
                }
            }
        }
        int n = trunk.size();
        for (Block t : trunk) if (!t.equals(b)) t.breakNaturally(tool);
        damage(p, tool, Math.max(1, n / 2));
        Diff d = diff(p, jd);
        addAllowance(p, m, n);
        g.collected += n;
        api.addCounter(p, "wood.felled", 1);
        api.addCounter(p, "wood.logs", n);
        api.pay(p, jd, 0, Math.round(n * jd.cd("xp-per-log", 1.5) * d.xp), false);
        String size = n >= 14 ? "mare" : n >= 7 ? "mediu" : "mic";
        Fx.bar(p, "§aCopac " + size + " (" + nice(m) + "): " + n + " butuci");
        Fx.sound(p, "BLOCK_WOOD_BREAK", 0.9f, 0.8f);
    }

    private void damage(Player p, ItemStack tool, int amount) {
        ItemMeta meta = tool.getItemMeta();
        if (meta instanceof Damageable) {
            Damageable dm = (Damageable) meta;
            int left = tool.getType().getMaxDurability() - dm.getDamage();
            dm.setDamage(dm.getDamage() + Math.max(0, Math.min(amount, left - 1)));
            tool.setItemMeta(meta);
        }
    }

    @Override
    public void onPlace(Player p, FJobDef jd, BlockPlaceEvent e) {
        Material m = e.getBlockPlaced().getType();
        if (!m.name().endsWith("_SAPLING") || !inForest(e.getBlockPlaced(), jd)) return;
        Block sb = e.getBlockPlaced();
        String sk = ro.solarstudios.flowcity.bridge.hype.PlacedSet.key(sb.getWorld().getName(), sb.getX(), sb.getY(), sb.getZ());
        long nowMs = System.currentTimeMillis();
        if (saplings.contains(sk, nowMs)) return;
        saplings.mark(sk, nowMs);
        api.pay(p, jd, jd.cd("sapling-pay", 1.5), Math.round(jd.cd("sapling-xp", 1)), false);
        api.addCounter(p, "wood.planted", 1);
        Fx.particle(p, "VILLAGER_HAPPY", e.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5), 6, 0.3);
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, G g, Panel pn) {
        fillCommon(p, jd, g, pn);
        pn.obj("Taie copaci naturali din zonele de padure, cu securea in mana.");
        pn.obj("Du lemnul la depozit (click dreapta) pentru plata.");
        pn.stat("Copaci taiati", String.valueOf(api.counter(p, "wood.felled")));
        pn.stat("Butuci", String.valueOf(api.counter(p, "wood.logs")));
        pn.stat("Puieti plantati", String.valueOf(api.counter(p, "wood.planted")));
        pn.stat("Zone setate", String.valueOf(pts(jd, "forest").size()));
        pn.reward("Puiet plantat in padure: " + money(jd.cd("sapling-pay", 1.5)) + " (o data per pozitie)");
        pn.tip("Constructiile din lemn si butucii pusi de jucatori nu se platesc.");
        pn.tip("Un copac se taie intreg odata (pana la " + (int) jd.cd("max-logs-per-tree", 48) + " butuci).");
    }
}
